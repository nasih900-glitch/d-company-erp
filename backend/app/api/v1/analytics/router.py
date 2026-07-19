"""Analytics endpoints — KPIs, dashboards, exports.

The dashboard delegates to ReportsAggregator (for revenue/orders/avg ticket)
plus a few quick aggregate queries for inventory + open gaming sessions.
"""

from __future__ import annotations

import csv
import io
from datetime import date as _date

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from sqlalchemy import func, select

from app.core.db import SessionDep
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_today
from app.models import GamingSession, Ingredient
from app.services.reports import ReportsAggregator

router = APIRouter()


class DashboardKPIs(BaseModel):
    date: _date
    revenue_food_minor: int
    revenue_gaming_minor: int
    revenue_hookah_minor: int
    revenue_events_minor: int
    revenue_manual_collections_minor: int
    discounts_and_points_redeemed_minor: int
    revenue_total_minor: int
    orders_count: int
    tickets_count: int
    avg_ticket_minor: int
    inventory_value_minor: int
    low_stock_items: int
    open_sessions: int
    net_profit_minor: int


@router.get("/dashboard", response_model=DashboardKPIs)
async def dashboard(
    session: SessionDep,
    on_date: _date | None = None,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> DashboardKPIs:
    """Real-time KPIs for the given date.

    Pulls revenue + orders + expenses from the reports aggregator so the
    numbers always agree with what /reports/daily and the Finance Overview
    tab display.
    """
    timezone_name = await company_timezone(session, tenant.company_id)
    target_date = on_date or local_today(timezone_name)
    agg = ReportsAggregator(session)
    report = await agg.aggregate_daily(company_id=tenant.company_id, d=target_date)

    # Inventory value = sum(current_qty * avg_cost_minor) for non-deleted
    inv_rows = (
        await session.execute(
            select(Ingredient.current_qty, Ingredient.avg_cost_minor).where(
                Ingredient.company_id == tenant.company_id,
                Ingredient.deleted_at.is_(None),
            )
        )
    ).all()
    inventory_value = sum(
        int(float(qty or 0) * int(cost or 0)) for qty, cost in inv_rows
    )
    low_stock = (
        await session.execute(
            select(func.count())
            .select_from(Ingredient)
            .where(
                Ingredient.company_id == tenant.company_id,
                Ingredient.deleted_at.is_(None),
                Ingredient.current_qty < Ingredient.reorder_threshold,
                Ingredient.reorder_threshold > 0,
            )
        )
    ).scalar_one()

    open_sessions = (
        await session.execute(
            select(func.count())
            .select_from(GamingSession)
            .where(
                GamingSession.company_id == tenant.company_id,
                GamingSession.status == "active",
            )
        )
    ).scalar_one()

    return DashboardKPIs(
        date=target_date,
        revenue_food_minor=report.revenue.food_minor,
        revenue_gaming_minor=report.revenue.gaming_minor,
        revenue_hookah_minor=report.revenue.hookah_minor,
        revenue_events_minor=report.revenue.event_tickets_minor,
        revenue_manual_collections_minor=report.manual_collections_minor,
        discounts_and_points_redeemed_minor=report.revenue.discounts_and_points_redeemed_minor,
        revenue_total_minor=report.gross_revenue_minor,
        orders_count=report.orders_count,
        tickets_count=report.tickets_count,
        avg_ticket_minor=report.avg_ticket_minor,
        inventory_value_minor=inventory_value,
        low_stock_items=int(low_stock),
        open_sessions=int(open_sessions),
        net_profit_minor=report.net_profit_minor,
    )


@router.get("/export.csv")
async def export_csv(
    session: SessionDep,
    period_start: _date,
    period_end: _date,
    tenant: TenantContext = Depends(requires("analytics.export")),
) -> StreamingResponse:
    if period_end < period_start:
        raise HTTPException(status_code=400, detail="period_end must be on or after period_start")

    report = await ReportsAggregator(session).aggregate(
        company_id=tenant.company_id,
        period_start=period_start,
        period_end=period_end,
        period="custom",
        label=f"{period_start.isoformat()} to {period_end.isoformat()}",
    )

    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["D Company ERP analytics export"])
    writer.writerow(["Period", report.period_start.isoformat(), report.period_end.isoformat()])
    writer.writerow([])
    writer.writerow(["Metric", "Amount minor", "Amount INR"])
    writer.writerow(["Gross revenue", report.gross_revenue_minor, f"{report.gross_revenue_minor / 100:.2f}"])
    writer.writerow(["Net revenue", report.net_revenue_minor, f"{report.net_revenue_minor / 100:.2f}"])
    writer.writerow(["Net profit", report.net_profit_minor, f"{report.net_profit_minor / 100:.2f}"])
    writer.writerow(["Average ticket", report.avg_ticket_minor, f"{report.avg_ticket_minor / 100:.2f}"])
    writer.writerow(["Orders count", report.orders_count, ""])
    writer.writerow(["Event tickets count", report.tickets_count, ""])
    writer.writerow([])
    writer.writerow(["Revenue stream", "Amount minor", "Amount INR"])
    writer.writerow(["Food, drinks, desserts", report.revenue.food_minor, f"{report.revenue.food_minor / 100:.2f}"])
    writer.writerow(["Gaming", report.revenue.gaming_minor, f"{report.revenue.gaming_minor / 100:.2f}"])
    writer.writerow(["Shisha", report.revenue.hookah_minor, f"{report.revenue.hookah_minor / 100:.2f}"])
    writer.writerow(["Events", report.revenue.event_tickets_minor, f"{report.revenue.event_tickets_minor / 100:.2f}"])
    writer.writerow(["Delivery aggregators", report.revenue.delivery_aggregator_minor, f"{report.revenue.delivery_aggregator_minor / 100:.2f}"])
    writer.writerow(["Other", report.revenue.other_minor, f"{report.revenue.other_minor / 100:.2f}"])
    writer.writerow([
        "Manual collections (not itemized orders)",
        report.manual_collections_minor,
        f"{report.manual_collections_minor / 100:.2f}",
    ])
    writer.writerow([
        "Less: discounts & points redeemed",
        -report.revenue.discounts_and_points_redeemed_minor,
        f"{-report.revenue.discounts_and_points_redeemed_minor / 100:.2f}",
    ])
    writer.writerow([])
    writer.writerow(["Tax", "Amount minor", "Amount INR"])
    writer.writerow(["CGST", report.tax_collected.cgst_minor, f"{report.tax_collected.cgst_minor / 100:.2f}"])
    writer.writerow(["SGST", report.tax_collected.sgst_minor, f"{report.tax_collected.sgst_minor / 100:.2f}"])
    writer.writerow(["IGST", report.tax_collected.igst_minor, f"{report.tax_collected.igst_minor / 100:.2f}"])
    writer.writerow(["CESS", report.tax_collected.cess_minor, f"{report.tax_collected.cess_minor / 100:.2f}"])
    writer.writerow(["Total GST", report.tax_collected.total_minor, f"{report.tax_collected.total_minor / 100:.2f}"])
    writer.writerow([])
    writer.writerow(["Payment method", "Amount minor", "Amount INR"])
    writer.writerow(["Cash", report.payments_received.cash_minor, f"{report.payments_received.cash_minor / 100:.2f}"])
    writer.writerow(["UPI", report.payments_received.upi_minor, f"{report.payments_received.upi_minor / 100:.2f}"])
    writer.writerow(["Card", report.payments_received.card_minor, f"{report.payments_received.card_minor / 100:.2f}"])
    writer.writerow(["Bank", report.payments_received.bank_minor, f"{report.payments_received.bank_minor / 100:.2f}"])
    writer.writerow(["QR", report.payments_received.qr_minor, f"{report.payments_received.qr_minor / 100:.2f}"])
    writer.writerow(["Wallet", report.payments_received.wallet_minor, f"{report.payments_received.wallet_minor / 100:.2f}"])
    writer.writerow(["Other", report.payments_received.other_minor, f"{report.payments_received.other_minor / 100:.2f}"])
    writer.writerow([])
    writer.writerow(["Expense category", "Amount minor", "Amount INR"])
    for expense in report.expenses:
        writer.writerow([expense.category, expense.amount_minor, f"{expense.amount_minor / 100:.2f}"])
    writer.writerow(["Total expenses", report.expense_total_minor, f"{report.expense_total_minor / 100:.2f}"])
    writer.writerow(["Equipment depreciation", report.depreciation_minor, f"{report.depreciation_minor / 100:.2f}"])

    filename = f"dcompany-analytics-{period_start.isoformat()}-to-{period_end.isoformat()}.csv"
    return StreamingResponse(
        iter([buffer.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
