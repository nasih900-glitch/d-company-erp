"""Reports endpoints — daily, monthly, quarterly, yearly P&L.

Powered by app/services/reports/aggregator.py — real SQL aggregation
against orders, payments, expenses, event_tickets.
"""

from __future__ import annotations

import csv
import io
from datetime import date, datetime, time, timezone
from decimal import Decimal
import re
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import Branch, Company, Event, EventTicket, MenuItem, Order, OrderLine
from app.services.pos.pricing import split_tax_from_inclusive_minor
from app.services.reports import (
    PnLReport,
    ReportsAggregator,
    month_range,
)

router = APIRouter()


# ----------------------------- response shapes -----------------------------
class RevenueDTO(BaseModel):
    food_minor: int
    gaming_minor: int
    hookah_minor: int
    event_tickets_minor: int
    delivery_aggregator_minor: int
    other_minor: int
    total_minor: int


class TaxDTO(BaseModel):
    cgst_minor: int
    sgst_minor: int
    igst_minor: int
    cess_minor: int
    total_minor: int


class PaymentsDTO(BaseModel):
    cash_minor: int
    upi_minor: int
    card_minor: int
    qr_minor: int
    wallet_minor: int
    other_minor: int
    total_minor: int


class ExpenseLineDTO(BaseModel):
    category: str
    amount_minor: int


class ReportDTO(BaseModel):
    period: Literal["daily", "monthly", "quarterly", "yearly", "custom"]
    label: str
    period_start: date
    period_end: date
    fiscal_year: str

    orders_count: int
    tickets_count: int
    avg_ticket_minor: int

    revenue: RevenueDTO
    tax_collected: TaxDTO
    payments_received: PaymentsDTO
    expenses: list[ExpenseLineDTO] = Field(default_factory=list)
    expense_total_minor: int

    gross_revenue_minor: int
    net_revenue_minor: int
    net_profit_minor: int


class TaxComplianceIssueDTO(BaseModel):
    severity: Literal["critical", "warning", "info"]
    area: str
    title: str
    detail: str
    count: int = 0
    action: str


class TaxComplianceDTO(BaseModel):
    period_start: date
    period_end: date
    company_gst_registered: bool
    gstin: str | None
    checked_orders: int
    checked_order_lines: int
    taxable_minor: int
    gst_collected_minor: int
    aggregator_delivery_minor: int
    event_ticket_revenue_minor: int
    critical_count: int
    warning_count: int
    info_count: int
    issues: list[TaxComplianceIssueDTO]


def _to_dto(r: PnLReport) -> ReportDTO:
    return ReportDTO(
        period=r.period,
        label=r.label,
        period_start=r.period_start,
        period_end=r.period_end,
        fiscal_year=r.fiscal_year,
        orders_count=r.orders_count,
        tickets_count=r.tickets_count,
        avg_ticket_minor=r.avg_ticket_minor,
        revenue=RevenueDTO(
            food_minor=r.revenue.food_minor,
            gaming_minor=r.revenue.gaming_minor,
            hookah_minor=r.revenue.hookah_minor,
            event_tickets_minor=r.revenue.event_tickets_minor,
            delivery_aggregator_minor=r.revenue.delivery_aggregator_minor,
            other_minor=r.revenue.other_minor,
            total_minor=r.revenue.total_minor,
        ),
        tax_collected=TaxDTO(
            cgst_minor=r.tax_collected.cgst_minor,
            sgst_minor=r.tax_collected.sgst_minor,
            igst_minor=r.tax_collected.igst_minor,
            cess_minor=r.tax_collected.cess_minor,
            total_minor=r.tax_collected.total_minor,
        ),
        payments_received=PaymentsDTO(
            cash_minor=r.payments_received.cash_minor,
            upi_minor=r.payments_received.upi_minor,
            card_minor=r.payments_received.card_minor,
            qr_minor=r.payments_received.qr_minor,
            wallet_minor=r.payments_received.wallet_minor,
            other_minor=r.payments_received.other_minor,
            total_minor=r.payments_received.total_minor,
        ),
        expenses=[
            ExpenseLineDTO(category=e.category, amount_minor=e.amount_minor)
            for e in r.expenses
        ],
        expense_total_minor=r.expense_total_minor,
        gross_revenue_minor=r.gross_revenue_minor,
        net_revenue_minor=r.net_revenue_minor,
        net_profit_minor=r.net_profit_minor,
    )


# ----------------------------- endpoints -----------------------------
@router.get("/daily", response_model=ReportDTO)
async def daily_report(
    session: SessionDep,
    on_date: date | None = None,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> ReportDTO:
    """P&L for a single day. Defaults to today if on_date is omitted."""
    d = on_date or date.today()
    agg = ReportsAggregator(session)
    rep = await agg.aggregate_daily(company_id=tenant.company_id, d=d)
    return _to_dto(rep)


@router.get("/monthly", response_model=ReportDTO)
async def monthly_report(
    yyyy_mm: str,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> ReportDTO:
    """P&L for a calendar month. yyyy_mm like '2026-06'."""
    if len(yyyy_mm) != 7 or yyyy_mm[4] != "-":
        raise BusinessRuleError("yyyy_mm must look like '2026-06'")
    agg = ReportsAggregator(session)
    rep = await agg.aggregate_monthly(company_id=tenant.company_id, yyyy_mm=yyyy_mm)
    return _to_dto(rep)


@router.get("/quarterly", response_model=ReportDTO)
async def quarterly_report(
    fy: str,
    q: int,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> ReportDTO:
    """P&L for one Indian fiscal quarter. fy like '2026-27', q in 1..4."""
    if q not in (1, 2, 3, 4):
        raise BusinessRuleError("q must be 1, 2, 3 or 4")
    if len(fy) != 7 or fy[4] != "-":
        raise BusinessRuleError("fy must look like '2026-27'")
    agg = ReportsAggregator(session)
    rep = await agg.aggregate_quarterly(company_id=tenant.company_id, fy=fy, q=q)
    return _to_dto(rep)


@router.get("/yearly", response_model=ReportDTO)
async def yearly_report(
    fy: str,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> ReportDTO:
    """P&L for a full Indian fiscal year (1 Apr → 31 Mar). fy like '2026-27'."""
    if len(fy) != 7 or fy[4] != "-":
        raise BusinessRuleError("fy must look like '2026-27'")
    agg = ReportsAggregator(session)
    rep = await agg.aggregate_yearly(company_id=tenant.company_id, fy=fy)
    return _to_dto(rep)


@router.get("/range", response_model=ReportDTO)
async def range_report(
    from_date: date,
    to_date: date,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> ReportDTO:
    """P&L for an arbitrary date range."""
    if to_date < from_date:
        raise BusinessRuleError("to_date must be on or after from_date")
    agg = ReportsAggregator(session)
    rep = await agg.aggregate(
        company_id=tenant.company_id,
        period_start=from_date,
        period_end=to_date,
        period="custom",
        label=f"{from_date.isoformat()} → {to_date.isoformat()}",
    )
    return _to_dto(rep)


# ===========================================================================
# GST health check
# ===========================================================================
_GSTIN_RE = re.compile(r"^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")
_PAN_RE = re.compile(r"^[A-Z]{5}[0-9]{4}[A-Z]$")
_STATE_CODE_RE = re.compile(r"^[0-9]{2}$")


def _issue(
    issues: list[TaxComplianceIssueDTO],
    *,
    severity: Literal["critical", "warning", "info"],
    area: str,
    title: str,
    detail: str,
    action: str,
    count: int = 0,
) -> None:
    if count == 0 and severity != "info":
        return
    issues.append(
        TaxComplianceIssueDTO(
            severity=severity,
            area=area,
            title=title,
            detail=detail,
            count=count,
            action=action,
        )
    )


def _is_aggregator(delivery_via: str | None) -> bool:
    return bool(delivery_via and delivery_via.lower() != "inhouse")


def _registered_for_gst(company: Company | None) -> bool:
    if not company:
        return False
    return (company.gst_registration_type or "regular") != "unregistered"


@router.get("/tax-compliance", response_model=TaxComplianceDTO)
async def tax_compliance(
    from_date: date,
    to_date: date,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> TaxComplianceDTO:
    """Owner-facing GST sanity check for a reporting period.

    This does not file a return. It highlights configuration and data problems
    before the accountant exports GSTR data.
    """
    if to_date < from_date:
        raise BusinessRuleError("to_date must be on or after from_date")

    start_dt = datetime.combine(from_date, time.min, tzinfo=timezone.utc)
    end_dt = datetime.combine(to_date, time.max, tzinfo=timezone.utc)
    company = await session.get(Company, tenant.company_id)
    branches = (
        await session.execute(
            select(Branch).where(
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    branch_state = {b.id: (b.state_code or "32") for b in branches}

    order_rows = (
        await session.execute(
            select(Order).where(
                Order.company_id == tenant.company_id,
                Order.opened_at >= start_dt,
                Order.opened_at <= end_dt,
                Order.status == "paid",
            )
        )
    ).scalars().all()
    checked_orders = len(order_rows)
    order_ids = [o.id for o in order_rows]
    line_rows = []
    if order_ids:
        line_rows = (
            await session.execute(
                select(OrderLine, Order.delivery_via)
                .join(Order, Order.id == OrderLine.order_id)
                .where(OrderLine.order_id.in_(order_ids))
            )
        ).all()

    line_sum_by_order: dict[UUID, int] = {}
    taxable_minor = 0
    gst_collected_minor = 0
    aggregator_delivery_minor = 0
    checked_order_lines = 0
    for line, delivery_via in line_rows:
        checked_order_lines += 1
        line_sum_by_order[line.order_id] = (
            line_sum_by_order.get(line.order_id, 0) + int(line.line_total_minor or 0)
        )
        if _is_aggregator(delivery_via):
            aggregator_delivery_minor += int(line.line_total_minor or 0)
        else:
            taxable_minor += int(line.taxable_value_minor or 0)
            gst_collected_minor += (
                int(line.cgst_minor or 0)
                + int(line.sgst_minor or 0)
                + int(line.igst_minor or 0)
                + int(line.cess_minor or 0)
            )

    event_rows = (
        await session.execute(
            select(EventTicket.price_paid_minor, EventTicket.order_id, Event.tax_rate)
            .join(Event, Event.id == EventTicket.event_id)
            .where(
                Event.company_id == tenant.company_id,
                EventTicket.created_at >= start_dt,
                EventTicket.created_at <= end_dt,
                EventTicket.status.in_(("sold", "checked_in")),
            )
        )
    ).all()
    event_ticket_revenue_minor = 0
    event_ticket_tax_minor = 0
    for ticket in event_rows:
        amount = int(ticket.price_paid_minor or 0)
        event_ticket_revenue_minor += amount
        ticket_taxable, cgst, sgst, igst = split_tax_from_inclusive_minor(
            amount,
            Decimal(str(ticket.tax_rate or 0)),
            True,
        )
        taxable_minor += ticket_taxable
        event_ticket_tax_minor += cgst + sgst + igst
    gst_collected_minor += event_ticket_tax_minor

    issues: list[TaxComplianceIssueDTO] = []
    gst_registered = _registered_for_gst(company)
    gstin = (company.gstin or "").strip().upper() if company and company.gstin else None

    if gst_registered and not gstin:
        _issue(
            issues,
            severity="critical",
            area="Company setup",
            title="GSTIN missing",
            detail="Company is configured as GST registered, but GSTIN is blank.",
            action="Add the correct GSTIN in Settings before issuing GST tax invoices.",
            count=1,
        )
    if gstin and not _GSTIN_RE.match(gstin):
        _issue(
            issues,
            severity="critical",
            area="Company setup",
            title="GSTIN format looks invalid",
            detail="GSTIN must be 15 characters: state code + PAN + entity + Z + checksum.",
            action="Verify the GSTIN from the GST certificate and correct Settings.",
            count=1,
        )
    if company and company.pan and not _PAN_RE.match(company.pan.strip().upper()):
        _issue(
            issues,
            severity="warning",
            area="Company setup",
            title="PAN format looks invalid",
            detail="PAN should be 10 characters in the standard Indian format.",
            action="Correct the PAN in Settings so GSTIN/PAN records match.",
            count=1,
        )
    if company and ((company.gst_registration_type == "composition") != bool(company.is_composition)):
        _issue(
            issues,
            severity="critical",
            area="Company setup",
            title="Composition flags disagree",
            detail="gst_registration_type and is_composition do not match.",
            action="Use either regular GST or composition consistently before billing.",
            count=1,
        )

    bad_state_count = sum(
        1 for b in branches if not b.state_code or b.state_code == "KL" or not _STATE_CODE_RE.match(b.state_code)
    )
    _issue(
        issues,
        severity="critical",
        area="Branch setup",
        title="Branch GST state code missing or invalid",
        detail="Kerala GST state code is 32. Text like KL is not valid for GST place-of-supply.",
        action="Set every Kerala branch state_code to 32 in Settings.",
        count=bad_state_count,
    )
    missing_fssai_count = sum(1 for b in branches if not b.fssai_license_no)
    _issue(
        issues,
        severity="warning",
        area="Branch setup",
        title="FSSAI licence missing on branch",
        detail="Food bills should carry the branch FSSAI licence number.",
        action="Add the licence number once issued; keep draft bills clearly internal before that.",
        count=missing_fssai_count,
    )

    menu_items = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.company_id == tenant.company_id,
                MenuItem.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    cafe_nonstandard = sum(
        1
        for item in menu_items
        if item.type in {"food", "drink", "dessert"} and Decimal(str(item.tax_rate or 0)) != Decimal("0.05")
    )
    _issue(
        issues,
        severity="warning",
        area="Menu tax",
        title="Cafe item tax rate needs review",
        detail="Food, drinks and desserts sold as restaurant service are generally 5% GST without ITC.",
        action="Review non-5% cafe items with the accountant, especially packaged/aerated resale items.",
        count=cafe_nonstandard,
    )
    service_bad_rate = sum(
        1
        for item in menu_items
        if item.type in {"gaming", "hookah", "streaming", "event"} and Decimal(str(item.tax_rate or 0)) != Decimal("0.18")
    )
    _issue(
        issues,
        severity="critical",
        area="Menu tax",
        title="Gaming/hookah/streaming/event item tax is not 18%",
        detail="Recreation, shisha, streaming and event service items are expected at 18% GST in this ERP setup.",
        action="Correct the rate or document the accountant-approved exception.",
        count=service_bad_rate,
    )
    missing_code_count = sum(1 for item in menu_items if not item.hsn_code)
    _issue(
        issues,
        severity="warning",
        area="Menu tax",
        title="HSN/SAC code missing on menu item",
        detail="Missing classification makes invoice review and GSTR preparation weaker.",
        action="Set SAC 996331 for restaurant service items and SAC 999692 for gaming/event items unless your accountant says otherwise.",
        count=missing_code_count,
    )

    component_mismatch = 0
    balance_mismatch = 0
    aggregator_taxed = 0
    regular_zero_tax = 0
    interstate_without_igst = 0
    invoice_missing = 0
    invoice_too_long = 0
    composition_taxed = 0
    for order in order_rows:
        components = (
            int(order.cgst_minor or 0)
            + int(order.sgst_minor or 0)
            + int(order.igst_minor or 0)
            + int(order.cess_minor or 0)
        )
        if int(order.tax_minor or 0) != components:
            component_mismatch += 1
        line_sum = line_sum_by_order.get(order.id, 0)
        expected_total = line_sum + int(order.round_off_minor or 0) + int(order.tip_minor or 0)
        if expected_total != int(order.total_minor or 0):
            balance_mismatch += 1
        if _is_aggregator(order.delivery_via) and int(order.tax_minor or 0) != 0:
            aggregator_taxed += 1
        if (
            gst_registered
            and not (company and company.is_composition)
            and not _is_aggregator(order.delivery_via)
            and int(order.tax_minor or 0) == 0
            and line_sum > 0
        ):
            regular_zero_tax += 1
        if company and company.is_composition and int(order.tax_minor or 0) > 0:
            composition_taxed += 1
        expected_state = branch_state.get(order.branch_id, "32")
        pos_state = order.place_of_supply_state_code or expected_state
        if pos_state != expected_state and int(order.igst_minor or 0) == 0:
            interstate_without_igst += 1
        if not order.invoice_no:
            invoice_missing += 1
        elif len(order.invoice_no) > 16:
            invoice_too_long += 1

    _issue(
        issues,
        severity="critical",
        area="Order tax",
        title="Order tax component mismatch",
        detail="tax_minor must equal CGST + SGST + IGST + Cess for every paid order.",
        action="Do not file the period until these orders are corrected or reversed.",
        count=component_mismatch,
    )
    _issue(
        issues,
        severity="critical",
        area="Order totals",
        title="Paid order total does not balance",
        detail="Sum of lines + round-off + tips must equal the invoice total.",
        action="Investigate these invoices before closing the day.",
        count=balance_mismatch,
    )
    _issue(
        issues,
        severity="critical",
        area="Delivery tax",
        title="Aggregator delivery was charged D Company GST",
        detail="For restaurant supplies through notified e-commerce operators under section 9(5), the ECO pays GST.",
        action="Mark the order delivery_via correctly and issue correction notes if required.",
        count=aggregator_taxed,
    )
    _issue(
        issues,
        severity="critical",
        area="Order tax",
        title="Regular GST order has zero tax",
        detail="A regular GST restaurant invoice should not have zero tax unless it is exempt, composition, or ECO section 9(5).",
        action="Review zero-tax invoices before filing GSTR-1/3B.",
        count=regular_zero_tax,
    )
    _issue(
        issues,
        severity="critical",
        area="Composition",
        title="Composition bill has tax charged",
        detail="Composition taxpayers issue Bill of Supply and do not show GST charged to customers.",
        action="Switch company settings or correct affected invoices before billing customers.",
        count=composition_taxed,
    )
    _issue(
        issues,
        severity="critical",
        area="Place of supply",
        title="Inter-state order did not use IGST",
        detail="When place of supply differs from branch state, tax should move from CGST/SGST to IGST.",
        action="Review delivery place-of-supply and customer state codes.",
        count=interstate_without_igst,
    )
    _issue(
        issues,
        severity="critical",
        area="Invoice numbering",
        title="Paid order missing invoice number",
        detail="Every paid sale must retain a sequential invoice number.",
        action="Investigate invoice counter and do not delete/overwrite paid invoices.",
        count=invoice_missing,
    )
    _issue(
        issues,
        severity="warning",
        area="Invoice numbering",
        title="Invoice number exceeds 16 characters",
        detail="GST invoice serial number should not exceed 16 characters.",
        action="Shorten branch code or invoice prefix before issuing more invoices.",
        count=invoice_too_long,
    )
    unlinked_event_tickets = sum(1 for t in event_rows if t.order_id is None)
    _issue(
        issues,
        severity="warning",
        area="Event tickets",
        title="Direct event tickets are not tied to POS payments",
        detail="Event ticket GST is included in reports, but ticket payment method is not reconciled through POS yet.",
        action="Prefer selling event tickets through POS or reconcile cash/UPI manually until event-POS payment is wired.",
        count=unlinked_event_tickets,
    )

    if not issues:
        _issue(
            issues,
            severity="info",
            area="GST health",
            title="No GST data issues found",
            detail="The automated checks found no configuration or order-tax problems for this period.",
            action="Still have the accountant verify classification and filing before submission.",
        )

    return TaxComplianceDTO(
        period_start=from_date,
        period_end=to_date,
        company_gst_registered=gst_registered,
        gstin=gstin,
        checked_orders=checked_orders,
        checked_order_lines=checked_order_lines,
        taxable_minor=taxable_minor,
        gst_collected_minor=gst_collected_minor,
        aggregator_delivery_minor=aggregator_delivery_minor,
        event_ticket_revenue_minor=event_ticket_revenue_minor,
        critical_count=sum(1 for i in issues if i.severity == "critical"),
        warning_count=sum(1 for i in issues if i.severity == "warning"),
        info_count=sum(1 for i in issues if i.severity == "info"),
        issues=issues,
    )


# ===========================================================================
# GSTR exports (accountant-review CSVs for gst.gov.in preparation)
# ===========================================================================
def _csv_response(rows: list[list], filename: str) -> StreamingResponse:
    buf = io.StringIO()
    w = csv.writer(buf)
    for r in rows:
        w.writerow(r)
    buf.seek(0)
    return StreamingResponse(
        iter([buf.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


def _add_tax_bucket(
    by_rate: dict[float, dict[str, int]],
    *,
    rate: float,
    taxable: int,
    cgst: int,
    sgst: int,
    igst: int,
    cess: int = 0,
) -> None:
    slot = by_rate.setdefault(
        rate,
        {"taxable": 0, "cgst": 0, "sgst": 0, "igst": 0, "cess": 0},
    )
    slot["taxable"] += taxable
    slot["cgst"] += cgst
    slot["sgst"] += sgst
    slot["igst"] += igst
    slot["cess"] += cess


@router.get("/gstr1.csv")
async def gstr1_csv(
    yyyy_mm: str,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.export")),
):
    """GSTR-1 B2C-Small summary export for accountant review.

    Output follows B2CS-style columns for typical café sales, but the GST
    portal layout should still be verified before filing.
    Two-column intra-state vs inter-state split per GST rate slab.

    Format per row:
      Type, Place of Supply, Rate, Taxable Value, IGST, CGST, SGST, Cess
    """
    start, end = month_range(yyyy_mm)
    start_dt = datetime.combine(start, time.min, tzinfo=timezone.utc)
    end_dt = datetime.combine(end, time.max, tzinfo=timezone.utc)

    company = await session.get(Company, tenant.company_id)
    branch = (
        await session.execute(
            select(Branch).where(Branch.company_id == tenant.company_id).limit(1)
        )
    ).scalar_one_or_none()
    state_name = "Kerala"  # display label
    pos_code = (branch.state_code if branch else "32") or "32"  # 32 = Kerala

    # Group normal D Company taxable order lines by tax_rate; aggregator
    # section 9(5) delivery is exported separately because ECO pays GST.
    rows = (
        await session.execute(
            select(
                OrderLine.tax_rate,
                OrderLine.taxable_value_minor,
                OrderLine.cgst_minor,
                OrderLine.sgst_minor,
                OrderLine.igst_minor,
                OrderLine.cess_minor,
            )
            .join(Order, Order.id == OrderLine.order_id)
            .where(
                Order.company_id == tenant.company_id,
                Order.opened_at >= start_dt,
                Order.opened_at <= end_dt,
                Order.status == "paid",
                or_(Order.delivery_via.is_(None), Order.delivery_via == "inhouse"),
            )
        )
    ).all()

    by_rate: dict[float, dict[str, int]] = {}
    for r in rows:
        _add_tax_bucket(
            by_rate,
            rate=float(r.tax_rate),
            taxable=int(r.taxable_value_minor or 0),
            cgst=int(r.cgst_minor or 0),
            sgst=int(r.sgst_minor or 0),
            igst=int(r.igst_minor or 0),
            cess=int(r.cess_minor or 0),
        )

    event_rows = (
        await session.execute(
            select(EventTicket.price_paid_minor, Event.tax_rate)
            .join(Event, Event.id == EventTicket.event_id)
            .where(
                Event.company_id == tenant.company_id,
                EventTicket.created_at >= start_dt,
                EventTicket.created_at <= end_dt,
                EventTicket.status.in_(("sold", "checked_in")),
            )
        )
    ).all()
    for ticket in event_rows:
        taxable, cgst, sgst, igst = split_tax_from_inclusive_minor(
            int(ticket.price_paid_minor or 0),
            Decimal(str(ticket.tax_rate or 0)),
            True,
        )
        _add_tax_bucket(
            by_rate,
            rate=float(ticket.tax_rate or 0),
            taxable=taxable,
            cgst=cgst,
            sgst=sgst,
            igst=igst,
        )

    eco_rows = (
        await session.execute(
            select(
                OrderLine.tax_rate,
                func.coalesce(func.sum(OrderLine.line_total_minor), 0).label("value"),
            )
            .join(Order, Order.id == OrderLine.order_id)
            .where(
                Order.company_id == tenant.company_id,
                Order.opened_at >= start_dt,
                Order.opened_at <= end_dt,
                Order.status == "paid",
                Order.delivery_via.isnot(None),
                Order.delivery_via != "inhouse",
            )
            .group_by(OrderLine.tax_rate)
        )
    ).all()

    out: list[list] = [
        ["GSTR-1 B2C-Small Summary — accountant review"],
        ["Company", company.name if company else "D Company"],
        ["GSTIN", (company.gstin if company else "") or ""],
        ["Period", yyyy_mm, "—", f"{start.isoformat()} to {end.isoformat()}"],
        ["Note", "Verify portal table mapping before filing. ECO section 9(5) supplies are separated below."],
        [],
        ["Normal outward taxable supplies"],
        ["Type", "Place of Supply", "Rate %", "Taxable Value ₹",
         "IGST ₹", "CGST ₹", "SGST ₹", "Cess ₹"],
    ]
    for rate, vals in sorted(by_rate.items()):
        out.append([
            "OE",  # B2CS Others
            f"{pos_code}-{state_name}",
            f"{rate * 100:.2f}",
            f"{vals['taxable'] / 100:.2f}",
            f"{vals['igst'] / 100:.2f}",
            f"{vals['cgst'] / 100:.2f}",
            f"{vals['sgst'] / 100:.2f}",
            f"{vals['cess'] / 100:.2f}",
        ])
    if eco_rows:
        out.extend([
            [],
            ["Restaurant services through ECO under CGST section 9(5)"],
            ["Delivery platform", "Place of Supply", "Rate %", "Supply Value ₹", "D Company GST ₹"],
        ])
        for row in eco_rows:
            out.append([
                "ECO",
                f"{pos_code}-{state_name}",
                f"{float(row.tax_rate) * 100:.2f}",
                f"{int(row.value or 0) / 100:.2f}",
                "0.00",
            ])
    return _csv_response(out, filename=f"GSTR1-{yyyy_mm}.csv")


@router.get("/gstr3b.csv")
async def gstr3b_csv(
    yyyy_mm: str,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.export")),
):
    """GSTR-3B summary for accountant review — outward taxable supplies.

    Single-page summary: taxable + GST collected, with ECO section 9(5)
    restaurant delivery separated because the platform pays GST.
    """
    start, end = month_range(yyyy_mm)
    start_dt = datetime.combine(start, time.min, tzinfo=timezone.utc)
    end_dt = datetime.combine(end, time.max, tzinfo=timezone.utc)
    agg = ReportsAggregator(session)
    rep = await agg.aggregate_monthly(company_id=tenant.company_id, yyyy_mm=yyyy_mm)
    company = await session.get(Company, tenant.company_id)

    normal_taxable_minor = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(OrderLine.taxable_value_minor), 0))
                .join(Order, Order.id == OrderLine.order_id)
                .where(
                    Order.company_id == tenant.company_id,
                    Order.opened_at >= start_dt,
                    Order.opened_at <= end_dt,
                    Order.status == "paid",
                    or_(Order.delivery_via.is_(None), Order.delivery_via == "inhouse"),
                )
            )
        ).scalar_one()
        or 0
    )
    eco_supply_minor = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(OrderLine.line_total_minor), 0))
                .join(Order, Order.id == OrderLine.order_id)
                .where(
                    Order.company_id == tenant.company_id,
                    Order.opened_at >= start_dt,
                    Order.opened_at <= end_dt,
                    Order.status == "paid",
                    Order.delivery_via.isnot(None),
                    Order.delivery_via != "inhouse",
                )
            )
        ).scalar_one()
        or 0
    )
    event_taxable_minor = 0
    event_rows = (
        await session.execute(
            select(EventTicket.price_paid_minor, Event.tax_rate)
            .join(Event, Event.id == EventTicket.event_id)
            .where(
                Event.company_id == tenant.company_id,
                EventTicket.created_at >= start_dt,
                EventTicket.created_at <= end_dt,
                EventTicket.status.in_(("sold", "checked_in")),
            )
        )
    ).all()
    for ticket in event_rows:
        taxable, _, _, _ = split_tax_from_inclusive_minor(
            int(ticket.price_paid_minor or 0),
            Decimal(str(ticket.tax_rate or 0)),
            True,
        )
        event_taxable_minor += taxable

    rows: list[list] = [
        ["GSTR-3B Summary — accountant review"],
        ["Company", company.name if company else "D Company"],
        ["GSTIN", (company.gstin if company else "") or ""],
        ["Period", yyyy_mm],
        ["Note", "Verify GST portal table mapping before filing."],
        [],
        ["Table 3.1 — Outward taxable supplies"],
        ["", "Total taxable value ₹", "IGST ₹", "CGST ₹", "SGST ₹", "Cess ₹"],
        [
            "Outward taxable supplies (other than zero-rated)",
            f"{(normal_taxable_minor + event_taxable_minor) / 100:.2f}",
            f"{rep.tax_collected.igst_minor / 100:.2f}",
            f"{rep.tax_collected.cgst_minor / 100:.2f}",
            f"{rep.tax_collected.sgst_minor / 100:.2f}",
            f"{rep.tax_collected.cess_minor / 100:.2f}",
        ],
        [],
        ["ECO section 9(5) restaurant delivery — tax payable by platform"],
        [
            "Supply value routed through ECO",
            f"{eco_supply_minor / 100:.2f}",
        ],
        [],
        ["Net GST payable",
         f"{rep.tax_collected.total_minor / 100:.2f}"],
    ]
    return _csv_response(rows, filename=f"GSTR3B-{yyyy_mm}.csv")
