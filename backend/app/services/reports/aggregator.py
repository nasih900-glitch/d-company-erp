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

from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from typing import Literal
from uuid import UUID

from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    Branch,
    Event,
    EventTicket,
    Expense,
    ExpenseCategory,
    MenuItem,
    Order,
    OrderLine,
    Payment,
)
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
    y, m = map(int, yyyy_mm.split("-"))
    start = date(y, m, 1)
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

    @property
    def total_minor(self) -> int:
        return (
            self.food_minor
            + self.gaming_minor
            + self.hookah_minor
            + self.event_tickets_minor
            + self.delivery_aggregator_minor
            + self.other_minor
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
    qr_minor: int = 0
    wallet_minor: int = 0
    other_minor: int = 0

    @property
    def total_minor(self) -> int:
        return (
            self.cash_minor
            + self.upi_minor
            + self.card_minor
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
    expenses: list[ExpenseLine] = field(default_factory=list)
    expense_total_minor: int = 0

    @property
    def gross_revenue_minor(self) -> int:
        return self.revenue.total_minor

    @property
    def net_revenue_minor(self) -> int:
        """Revenue minus GST collected (which belongs to government)."""
        return self.gross_revenue_minor - self.tax_collected.total_minor

    @property
    def net_profit_minor(self) -> int:
        return self.net_revenue_minor - self.expense_total_minor


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
        # Inclusive of period_end (end of day)
        start_at = datetime.combine(period_start, time.min, tzinfo=timezone.utc)
        end_at = datetime.combine(period_end, time.max, tzinfo=timezone.utc)

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
            Order.opened_at >= start_at,
            Order.opened_at <= end_at,
            Order.status == "paid",
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
            Order.opened_at >= start_at,
            Order.opened_at <= end_at,
            Order.status == "paid",
            Order.delivery_via.isnot(None),
            Order.delivery_via != "inhouse",
        )
        delivery_total = int((await self.session.execute(delivery_q)).scalar_one() or 0)

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
                Order.opened_at >= start_at,
                Order.opened_at <= end_at,
                Order.status == "paid",
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
                Event.tax_rate,
                Branch.state_code,
            )
            .join(Event, Event.id == EventTicket.event_id)
            .join(Branch, Branch.id == Event.branch_id)
            .where(
                Event.company_id == company_id,
                EventTicket.created_at >= start_at,
                EventTicket.created_at <= end_at,
                EventTicket.status.in_(("sold", "checked_in")),
            )
        )
        ticket_rows = (await self.session.execute(tickets_q)).all()
        tickets_count = len(ticket_rows)
        tickets_total = 0
        ticket_cgst = ticket_sgst = ticket_igst = 0
        for ticket in ticket_rows:
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

        revenue = RevenueBreakdown(
            food_minor=food_total,
            gaming_minor=gaming_total,
            hookah_minor=hookah_total,
            event_tickets_minor=tickets_total,
            delivery_aggregator_minor=delivery_total,
            other_minor=other_total,
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
                Payment.paid_at <= end_at,
            )
            .group_by(Payment.method)
        )
        cash = upi = card = qr = wallet = other_pay = 0
        for row in (await self.session.execute(pay_q)).all():
            m = row.method
            amt = int(row.amount)
            if m == "cash":   cash = amt
            elif m == "upi":  upi = amt
            elif m == "card": card = amt
            elif m == "qr":   qr = amt
            elif m == "wallet": wallet = amt
            else:             other_pay += amt
        payments = PaymentBreakdown(
            cash_minor=cash, upi_minor=upi, card_minor=card,
            qr_minor=qr, wallet_minor=wallet, other_minor=other_pay,
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
                Expense.paid_at <= end_at,
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
            expenses=expense_lines,
            expense_total_minor=expense_total,
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
