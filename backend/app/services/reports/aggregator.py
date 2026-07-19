"""P&L aggregation — the real reporting engine for D Company.

Aggregates over a date range from these tables:
  - orders          → revenue (split by order.type: dine_in, takeaway, delivery)
  - payments        → method split (cash, upi, card, qr, wallet)
  - order_lines     → revenue by menu_item type (food, drink, dessert, gaming, event)
  - event_tickets   → event ticket revenue (separate stream)
  - expenses        → expense buckets (by category)
  - stock_movements → COGS approximation (sale-type movements × cost)

Returns a fully-detailed `PnLReport` data class — same shape regardless of
whether the range is one day or a whole fiscal year. The same object is
rendered to the print-friendly UI, pushed to Google Sheets, and emailed.

Indian fiscal year: 1 April → 31 March of next year (e.g. "2026-27").

All money is integer minor units (paise). Floats are forbidden.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal
from uuid import UUID

from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessRuleError
from app.core.timezone import company_timezone, local_date_bounds_utc
from app.models import (
    Asset,
    Batch,
    Branch,
    Event,
    EventTicket,
    Expense,
    ExpenseCategory,
    ManualCollection,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    Refund,
    StockMovement,
)
from app.services.accounting.depreciation import asset_depreciation_expense_minor
from app.services.pos.pricing import split_tax_from_inclusive_minor

ReportPeriod = Literal["daily", "monthly", "quarterly", "yearly", "custom"]


# ---------------------------------------------------------------------------
# Indian fiscal-year helpers
# ---------------------------------------------------------------------------
def fiscal_year_for_date(d: date) -> str:
    """Indian fiscal year string for a date. April-March convention."""
    if d.month >= 4:
        return f"{d.year}-{str(d.year + 1)[-2:]}"
    return f"{d.year - 1}-{str(d.year)[-2:]}"


def fiscal_quarter(d: date) -> int:
    """Indian fiscal quarter (1=Apr-Jun, 2=Jul-Sep, 3=Oct-Dec, 4=Jan-Mar)."""
    m = d.month
    if 4 <= m <= 6: return 1
    if 7 <= m <= 9: return 2
    if 10 <= m <= 12: return 3
    return 4


def fy_quarter_range(fy: str, q: int) -> tuple[date, date]:
    """Return (start_date, end_date) for a given fiscal year + quarter."""
    fy_start_year = int(fy.split("-")[0])
    month = {1: 4, 2: 7, 3: 10, 4: 1}[q]
    year_offset = 1 if q == 4 else 0
    start = date(fy_start_year + year_offset, month, 1)
    end_month = month + 2
    end_year = start.year
    if end_month > 12:
        end_month -= 12
        end_year += 1
    # Last day of end_month
    if end_month == 12:
        end = date(end_year, 12, 31)
    else:
        end = date(end_year, end_month + 1, 1) - timedelta(days=1)
    return start, end


def fy_full_range(fy: str) -> tuple[date, date]:
    """Return (start, end) for an Indian FY string like '2026-27'."""
    fy_start_year = int(fy.split("-")[0])
    return date(fy_start_year, 4, 1), date(fy_start_year + 1, 3, 31)


def month_range(yyyy_mm: str) -> tuple[date, date]:
    """Return (start, end) for a YYYY-MM string."""
    if len(yyyy_mm) != 7 or yyyy_mm[4] != "-":
        raise BusinessRuleError("yyyy_mm must look like '2026-06'")
    try:
        y, m = int(yyyy_mm[:4]), int(yyyy_mm[5:])
        start = date(y, m, 1)
    except ValueError as exc:
        raise BusinessRuleError("yyyy_mm must look like '2026-06'") from exc
    if m == 12:
        end = date(y, 12, 31)
    else:
        end = date(y, m + 1, 1) - timedelta(days=1)
    return start, end


# ---------------------------------------------------------------------------
# Output structures
# ---------------------------------------------------------------------------
@dataclass(frozen=True, slots=True)
class RevenueBreakdown:
    food_minor: int = 0          # food + drinks + desserts
    gaming_minor: int = 0        # gaming-type menu items (PS5/VR/sim per-session items)
    hookah_minor: int = 0        # hookah flavors + sessions
    event_tickets_minor: int = 0  # event ticket sales
    delivery_aggregator_minor: int = 0  # delivery via Zomato/Swiggy (9(5))
    other_minor: int = 0
    # Revenue received outside itemized POS billing.  It remains explicit so
    # nobody mistakes it for product/category revenue or an order count.
    manual_collections_minor: int = 0
    # food/gaming/hookah/other above are summed from OrderLine.line_total_minor,
    # which does NOT reflect an order-level manual discount or points
    # redemption (both applied after line pricing). delivery_aggregator_minor
    # already uses Order.total_minor and is already net. Subtracted here so
    # gross_revenue_minor reflects what was actually collected, not the
    # pre-discount line total.
    discounts_and_points_redeemed_minor: int = 0

    @property
    def total_minor(self) -> int:
        return (
            self.food_minor
            + self.gaming_minor
            + self.hookah_minor
            + self.event_tickets_minor
            + self.delivery_aggregator_minor
            + self.other_minor
            + self.manual_collections_minor
            - self.discounts_and_points_redeemed_minor
        )


@dataclass(frozen=True, slots=True)
class TaxBreakdown:
    cgst_minor: int = 0
    sgst_minor: int = 0
    igst_minor: int = 0
    cess_minor: int = 0

    @property
    def total_minor(self) -> int:
        return self.cgst_minor + self.sgst_minor + self.igst_minor + self.cess_minor


@dataclass(frozen=True, slots=True)
class PaymentBreakdown:
    cash_minor: int = 0
    upi_minor: int = 0
    card_minor: int = 0
    bank_minor: int = 0
    qr_minor: int = 0
    wallet_minor: int = 0
    other_minor: int = 0

    @property
    def total_minor(self) -> int:
        return (
            self.cash_minor
            + self.upi_minor
            + self.card_minor
            + self.bank_minor
            + self.qr_minor
            + self.wallet_minor
            + self.other_minor
        )


@dataclass(frozen=True, slots=True)
class ExpenseLine:
    category: str
    amount_minor: int


@dataclass(frozen=True, slots=True)
class PnLReport:
    """Single, period-agnostic P&L. Same shape for daily / monthly / yearly."""

    period: ReportPeriod
    label: str                    # "20-May-2026" or "May 2026" or "2026-27 Q1" or "2026-27"
    period_start: date
    period_end: date
    fiscal_year: str

    # Counts (KPIs)
    orders_count: int
    tickets_count: int
    avg_ticket_minor: int

    # Money
    revenue: RevenueBreakdown
    tax_collected: TaxBreakdown
    payments_received: PaymentBreakdown
    refunds_issued_minor: int = 0
    settled_refunds_issued_minor: int = 0
    cogs_minor: int = 0
    expenses: list[ExpenseLine] = field(default_factory=list)
    expense_total_minor: int = 0
    # Straight-line depreciation on the Asset register for this exact
    # period, computed analytically — never manually entered, never a stored
    # row. See app/services/accounting/depreciation.py.
    depreciation_minor: int = 0

    @property
    def gross_revenue_minor(self) -> int:
        return self.revenue.total_minor

    @property
    def manual_collections_minor(self) -> int:
        return self.revenue.manual_collections_minor

    @property
    def net_revenue_minor(self) -> int:
        """Revenue minus GST collected (which belongs to government)."""
        return (
            self.gross_revenue_minor
            - self.refunds_issued_minor
            - self.tax_collected.total_minor
        )

    @property
    def net_profit_minor(self) -> int:
        return (
            self.net_revenue_minor
            - self.cogs_minor
            - self.expense_total_minor
            - self.depreciation_minor
        )

    @property
    def gross_profit_minor(self) -> int:
        return self.net_revenue_minor - self.cogs_minor

    @property
    def net_payments_received_minor(self) -> int:
        """Cash-flow view: gross payments received less refunds issued in-period."""
        settled_refunds = getattr(
            self,
            "settled_refunds_issued_minor",
            self.refunds_issued_minor,
        )
        return self.payments_received.total_minor - settled_refunds


def _proportional_cumulative(component: int, refunded: int, total: int) -> int:
    """Allocate a component over cumulative refunds without rounding drift."""
    if component <= 0 or refunded <= 0 or total <= 0:
        return 0
    bounded = min(refunded, total)
    return int(
        (Decimal(component) * Decimal(bounded) / Decimal(total)).quantize(
            Decimal("1"), rounding=ROUND_HALF_UP
        )
    )


def _manual_collection_totals(
    rows: Iterable[ManualCollection],
) -> dict[str, int]:
    """Return active manual totals by method with a defensive void check."""
    totals = {"cash": 0, "upi": 0, "card": 0, "bank": 0}
    for row in rows:
        if row.voided_at is not None:
            continue
        if row.method not in totals:
            # The database constraint rejects this. Keeping the aggregation
            # defensive prevents an old/corrupt row from being misreported as
            # a supported settlement method.
            continue
        totals[row.method] += int(row.amount_minor)
    return totals


# ---------------------------------------------------------------------------
# Aggregator
# ---------------------------------------------------------------------------
class ReportsAggregator:
    """Computes a PnLReport over a date range, scoped to a company."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def aggregate(
        self,
        *,
        company_id: UUID,
        period_start: date,
        period_end: date,
        period: ReportPeriod = "custom",
        label: str | None = None,
    ) -> PnLReport:
        timezone_name = await company_timezone(self.session, company_id)
        start_at, end_at = local_date_bounds_utc(
            period_start, period_end, timezone_name
        )
        sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)

        # ---------- Orders aggregation ----------
        # Total orders + average ticket
        orders_q = select(
            func.count(Order.id).label("n"),
            func.coalesce(func.sum(Order.total_minor), 0).label("gross"),
            func.coalesce(func.sum(Order.cgst_minor), 0).label("cgst"),
            func.coalesce(func.sum(Order.sgst_minor), 0).label("sgst"),
            func.coalesce(func.sum(Order.igst_minor), 0).label("igst"),
            func.coalesce(func.sum(Order.cess_minor), 0).label("cess"),
        ).where(
            Order.company_id == company_id,
            sale_at >= start_at,
            sale_at < end_at,
            Order.status.in_(("paid", "refunded")),
        )
        orders_row = (await self.session.execute(orders_q)).one()
        orders_count = int(orders_row.n)
        gross_total = int(orders_row.gross)
        avg_ticket = gross_total // orders_count if orders_count else 0
        order_cgst = int(orders_row.cgst)
        order_sgst = int(orders_row.sgst)
        order_igst = int(orders_row.igst)
        order_cess = int(orders_row.cess)

        # Revenue by order type (food vs gaming menu items vs delivery)
        # food = order_lines joined to menu_items.type in (food, drink, dessert)
        # gaming = menu_items.type = 'gaming'
        # delivery_aggregator = orders.delivery_via NOT NULL AND NOT 'inhouse'
        delivery_q = select(
            func.coalesce(func.sum(Order.total_minor), 0).label("d")
        ).where(
            Order.company_id == company_id,
            sale_at >= start_at,
            sale_at < end_at,
            Order.status.in_(("paid", "refunded")),
            Order.delivery_via.isnot(None),
            Order.delivery_via != "inhouse",
        )
        delivery_total = int((await self.session.execute(delivery_q)).scalar_one() or 0)

        # In-house manual discount + points redemption (excluded from the
        # per-category sums below, which are built from pre-discount line
        # totals) — see RevenueBreakdown.discounts_and_points_redeemed_minor.
        discounts_q = select(
            func.coalesce(
                func.sum(Order.manual_discount_minor + Order.points_redeemed_minor), 0
            )
        ).where(
            Order.company_id == company_id,
            sale_at >= start_at,
            sale_at < end_at,
            Order.status.in_(("paid", "refunded")),
            or_(Order.delivery_via.is_(None), Order.delivery_via == "inhouse"),
        )
        discounts_and_points_total = int(
            (await self.session.execute(discounts_q)).scalar_one() or 0
        )

        # Revenue by menu item type (excluding aggregator orders)
        type_q = (
            select(
                MenuItem.type,
                func.coalesce(func.sum(OrderLine.line_total_minor), 0).label("amount"),
            )
            .join(Order, Order.id == OrderLine.order_id)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                Order.company_id == company_id,
                sale_at >= start_at,
                sale_at < end_at,
                Order.status.in_(("paid", "refunded")),
                or_(Order.delivery_via.is_(None), Order.delivery_via == "inhouse"),
            )
            .group_by(MenuItem.type)
        )
        food_total = 0
        gaming_total = 0
        hookah_total = 0
        other_total = 0
        for row in (await self.session.execute(type_q)).all():
            t = row.type
            amt = int(row.amount)
            if t in ("food", "drink", "dessert"):
                food_total += amt
            elif t == "gaming":
                gaming_total += amt
            elif t == "hookah":
                hookah_total += amt
            else:
                other_total += amt

        # ---------- Event tickets ----------
        tickets_q = (
            select(
                EventTicket.price_paid_minor,
                EventTicket.order_id,
                Event.tax_rate,
                Branch.state_code,
            )
            .join(Event, Event.id == EventTicket.event_id)
            .join(Branch, Branch.id == Event.branch_id)
            .where(
                Event.company_id == company_id,
                EventTicket.created_at >= start_at,
                EventTicket.created_at < end_at,
                EventTicket.status.in_(("sold", "checked_in")),
            )
        )
        ticket_rows = (await self.session.execute(tickets_q)).all()
        tickets_count = len(ticket_rows)
        tickets_total = 0
        ticket_cgst = ticket_sgst = ticket_igst = 0
        for ticket in ticket_rows:
            if ticket.order_id is not None:
                continue
            amount = int(ticket.price_paid_minor or 0)
            tickets_total += amount
            _, cgst, sgst, igst = split_tax_from_inclusive_minor(
                amount,
                Decimal(str(ticket.tax_rate or 0)),
                True,
            )
            ticket_cgst += cgst
            ticket_sgst += sgst
            ticket_igst += igst

        tax = TaxBreakdown(
            cgst_minor=order_cgst + ticket_cgst,
            sgst_minor=order_sgst + ticket_sgst,
            igst_minor=order_igst + ticket_igst,
            cess_minor=order_cess,
        )

        # ---------- Cost of goods sold ----------
        movement_rows = (
            await self.session.execute(
                select(
                    StockMovement.qty_delta,
                    StockMovement.cost_per_unit_minor,
                )
                .join(Batch, Batch.id == StockMovement.batch_id)
                .where(
                    StockMovement.branch_id.in_(
                        select(Branch.id).where(Branch.company_id == company_id)
                    ),
                    StockMovement.type.in_(("sale", "refund_restock")),
                    StockMovement.created_at >= start_at,
                    StockMovement.created_at < end_at,
                )
            )
        ).all()
        # Sale movements consume stock (negative delta) and count toward
        # COGS; refund_restock movements reverse a prior sale (positive
        # delta) and must net back out, or a refunded recipe-linked item
        # permanently overstates COGS even though the stock came back.
        cogs_minor = sum(
            int(abs(Decimal(str(qty_delta))) * int(cost_per_unit_minor or 0))
            for qty_delta, cost_per_unit_minor in movement_rows
            if Decimal(str(qty_delta or 0)) < 0
        ) - sum(
            int(abs(Decimal(str(qty_delta))) * int(cost_per_unit_minor or 0))
            for qty_delta, cost_per_unit_minor in movement_rows
            if Decimal(str(qty_delta or 0)) > 0
        )

        revenue = RevenueBreakdown(
            food_minor=food_total,
            gaming_minor=gaming_total,
            hookah_minor=hookah_total,
            event_tickets_minor=tickets_total,
            delivery_aggregator_minor=delivery_total,
            other_minor=other_total,
            manual_collections_minor=0,
            discounts_and_points_redeemed_minor=discounts_and_points_total,
        )

        # ---------- Manual daily collections ----------
        # Business date is explicitly stored in company-local terms, so a
        # backfill created after midnight UTC still belongs to the date the
        # owner attested. Voids remain visible in Finance but never contribute
        # to P&L or payment totals.
        manual_q = (
            select(ManualCollection)
            .where(
                ManualCollection.company_id == company_id,
                ManualCollection.business_date >= period_start,
                ManualCollection.business_date <= period_end,
                ManualCollection.voided_at.is_(None),
            )
            .order_by(ManualCollection.business_date, ManualCollection.id)
        )
        manual_rows = (await self.session.execute(manual_q)).scalars().all()
        manual_by_method = _manual_collection_totals(manual_rows)
        manual_total = sum(manual_by_method.values())
        revenue = RevenueBreakdown(
            food_minor=revenue.food_minor,
            gaming_minor=revenue.gaming_minor,
            hookah_minor=revenue.hookah_minor,
            event_tickets_minor=revenue.event_tickets_minor,
            delivery_aggregator_minor=revenue.delivery_aggregator_minor,
            other_minor=revenue.other_minor,
            manual_collections_minor=manual_total,
            discounts_and_points_redeemed_minor=(
                revenue.discounts_and_points_redeemed_minor
            ),
        )

        # ---------- Payments breakdown ----------
        pay_q = (
            select(
                Payment.method,
                func.coalesce(func.sum(Payment.amount_minor), 0).label("amount"),
            )
            .join(Order, Order.id == Payment.order_id)
            .where(
                Order.company_id == company_id,
                Payment.paid_at >= start_at,
                Payment.paid_at < end_at,
                Order.status.in_(("paid", "refunded")),
            )
            .group_by(Payment.method)
        )
        cash = upi = card = bank = qr = wallet = other_pay = 0
        for row in (await self.session.execute(pay_q)).all():
            m = row.method
            amt = int(row.amount)
            if m == "cash":   cash = amt
            elif m == "upi":  upi = amt
            elif m == "card": card = amt
            elif m == "bank": bank = amt
            elif m == "qr":   qr = amt
            elif m == "wallet": wallet = amt
            else:             other_pay += amt
        payments = PaymentBreakdown(
            cash_minor=cash + manual_by_method.get("cash", 0),
            upi_minor=upi + manual_by_method.get("upi", 0),
            card_minor=card + manual_by_method.get("card", 0),
            bank_minor=bank + manual_by_method.get("bank", 0),
            qr_minor=qr, wallet_minor=wallet, other_minor=other_pay,
        )

        refund_rows = (
            await self.session.execute(
                select(Refund, Order)
                .join(Order, Order.id == Refund.order_id)
                .where(
                    Order.company_id == company_id,
                    Refund.created_at >= start_at,
                    Refund.created_at < end_at,
                )
                .order_by(Refund.created_at, Refund.id)
            )
        ).all()
        refund_order_ids = {refund.order_id for refund, _ in refund_rows}
        prior_by_order: dict[UUID, int] = {}
        if refund_order_ids:
            prior_rows = (
                await self.session.execute(
                    select(
                        Refund.order_id,
                        func.coalesce(func.sum(Refund.amount_minor), 0),
                    )
                    .where(
                        Refund.order_id.in_(refund_order_ids),
                        Refund.created_at < start_at,
                    )
                    .group_by(Refund.order_id)
                )
            ).all()
            prior_by_order = {
                order_id: int(amount or 0) for order_id, amount in prior_rows
            }

        refunds_issued = 0
        settled_refunds_issued = 0
        refund_cgst = refund_sgst = refund_igst = refund_cess = 0
        running = dict(prior_by_order)
        for refund, order in refund_rows:
            amount = int(refund.amount_minor or 0)
            before = running.get(order.id, 0)
            after = before + amount
            # order.total_minor includes any tip folded on at payment time;
            # a tip is never part of the taxable bill, so proportioning
            # against the tip-inclusive total would understate the tax
            # reversal on any refund of a tipped order.
            total = int(order.total_minor or 0) - int(order.tip_minor or 0)
            for component_name in ("cgst", "sgst", "igst", "cess"):
                component = int(getattr(order, f"{component_name}_minor") or 0)
                delta = _proportional_cumulative(
                    component, after, total
                ) - _proportional_cumulative(component, before, total)
                if component_name == "cgst":
                    refund_cgst += delta
                elif component_name == "sgst":
                    refund_sgst += delta
                elif component_name == "igst":
                    refund_igst += delta
                else:
                    refund_cess += delta
            running[order.id] = after
            refunds_issued += amount
            if refund.settlement_method != "store_credit":
                settled_refunds_issued += amount

        tax = TaxBreakdown(
            cgst_minor=tax.cgst_minor - refund_cgst,
            sgst_minor=tax.sgst_minor - refund_sgst,
            igst_minor=tax.igst_minor - refund_igst,
            cess_minor=tax.cess_minor - refund_cess,
        )

        # ---------- Depreciation ----------
        # Never entered manually — computed analytically from the Asset
        # register for exactly this period, using the same accumulated-diff
        # technique as the refund tax allocation above and the operational
        # ledger (see app/services/accounting/depreciation.py). Capped at
        # real "now" so a future-dated period never accrues depreciation
        # that hasn't happened yet.
        now = datetime.now(UTC)
        depreciation_end_as_of = min(end_at, now)
        depreciation_start_at = min(start_at, now)
        asset_rows = (
            await self.session.execute(
                select(Asset).where(
                    Asset.company_id == company_id,
                    Asset.deleted_at.is_(None),
                )
            )
        ).scalars().all()
        depreciation_minor = sum(
            asset_depreciation_expense_minor(
                asset,
                start_at=depreciation_start_at,
                end_as_of=depreciation_end_as_of,
            )
            for asset in asset_rows
        )

        # ---------- Expenses by category ----------
        exp_q = (
            select(
                ExpenseCategory.name,
                func.coalesce(func.sum(Expense.amount_minor), 0).label("amount"),
            )
            .join(ExpenseCategory, ExpenseCategory.id == Expense.category_id)
            .where(
                Expense.company_id == company_id,
                Expense.paid_at >= start_at,
                Expense.paid_at < end_at,
                Expense.deleted_at.is_(None),
            )
            .group_by(ExpenseCategory.name)
            .order_by(func.sum(Expense.amount_minor).desc())
        )
        expense_lines: list[ExpenseLine] = []
        expense_total = 0
        for row in (await self.session.execute(exp_q)).all():
            amt = int(row.amount)
            expense_lines.append(ExpenseLine(category=row.name, amount_minor=amt))
            expense_total += amt

        return PnLReport(
            period=period,
            label=label or f"{period_start.isoformat()} → {period_end.isoformat()}",
            period_start=period_start,
            period_end=period_end,
            fiscal_year=fiscal_year_for_date(period_start),
            orders_count=orders_count,
            tickets_count=tickets_count,
            avg_ticket_minor=avg_ticket,
            revenue=revenue,
            tax_collected=tax,
            payments_received=payments,
            refunds_issued_minor=refunds_issued,
            settled_refunds_issued_minor=settled_refunds_issued,
            cogs_minor=cogs_minor,
            expenses=expense_lines,
            expense_total_minor=expense_total,
            depreciation_minor=int(depreciation_minor),
        )

    async def aggregate_daily(self, *, company_id: UUID, d: date) -> PnLReport:
        return await self.aggregate(
            company_id=company_id,
            period_start=d,
            period_end=d,
            period="daily",
            label=d.strftime("%d-%b-%Y"),
        )

    async def aggregate_monthly(self, *, company_id: UUID, yyyy_mm: str) -> PnLReport:
        start, end = month_range(yyyy_mm)
        return await self.aggregate(
            company_id=company_id,
            period_start=start, period_end=end,
            period="monthly",
            label=start.strftime("%b %Y"),
        )

    async def aggregate_quarterly(
        self, *, company_id: UUID, fy: str, q: int
    ) -> PnLReport:
        start, end = fy_quarter_range(fy, q)
        return await self.aggregate(
            company_id=company_id,
            period_start=start, period_end=end,
            period="quarterly",
            label=f"{fy} Q{q}",
        )

    async def aggregate_yearly(self, *, company_id: UUID, fy: str) -> PnLReport:
        start, end = fy_full_range(fy)
        return await self.aggregate(
            company_id=company_id,
            period_start=start, period_end=end,
            period="yearly",
            label=f"FY {fy}",
        )
