"""Analytics endpoints — KPIs, dashboards, exports.

The dashboard delegates to ReportsAggregator (for revenue/orders/avg ticket)
plus a few quick aggregate queries for inventory + open gaming sessions.
"""

from __future__ import annotations

from datetime import date as _date, datetime, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import func, select

from app.core.db import SessionDep
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import GamingSession, Ingredient
from app.services.reports import ReportsAggregator

router = APIRouter()


class DashboardKPIs(BaseModel):
    date: _date
    revenue_food_minor: int
    revenue_gaming_minor: int
    revenue_hookah_minor: int
    revenue_events_minor: int
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
    on_date: _date,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
) -> DashboardKPIs:
    """Real-time KPIs for the given date.

    Pulls revenue + orders + expenses from the reports aggregator so the
    numbers always agree with what /reports/daily and the Finance Overview
    tab display.
    """
    agg = ReportsAggregator(session)
    report = await agg.aggregate_daily(company_id=tenant.company_id, d=on_date)

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
        date=on_date,
        revenue_food_minor=report.revenue.food_minor,
        revenue_gaming_minor=report.revenue.gaming_minor,
        revenue_hookah_minor=report.revenue.hookah_minor,
        revenue_events_minor=report.revenue.event_tickets_minor,
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
    period_start: _date,
    period_end: _date,
    tenant: TenantContext = Depends(requires("analytics.export")),
) -> dict:
    return {
        "url": "/exports/placeholder.csv",
        "period_start": period_start.isoformat(),
        "period_end": period_end.isoformat(),
    }
