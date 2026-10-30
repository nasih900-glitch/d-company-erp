"""Shared, read-only report projection primitives.

The first consumer is the Home read model.  Reports Overview can reuse the
same local-hour cohort later without copying financial or timezone rules.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Literal
from uuid import UUID

from sqlalchemy import and_, case, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timezone import local_date_bounds_utc
from app.models import GamingSession, Order, OrderLine, Station
from app.services.reports.aggregator import PnLReport, ReportsAggregator


POS_LOCAL_HOUR_PROFILE_BASIS: Literal["pos_sales_tip_exclusive_local_hour_profile"] = (
    "pos_sales_tip_exclusive_local_hour_profile"
)


@dataclass(frozen=True, slots=True)
class LocalHourPoint:
    hour: int
    revenue_minor: int
    orders_count: int


@dataclass(frozen=True, slots=True)
class PosLocalHourProfile:
    timezone: str
    business_date: date
    day_length_minutes: int
    basis: Literal["pos_sales_tip_exclusive_local_hour_profile"]
    reconciles_to_daily_gross: Literal[False]
    total_revenue_minor: int
    total_orders_count: int
    points: tuple[LocalHourPoint, ...]


@dataclass(frozen=True, slots=True)
class LinkedCustomerCoverage:
    """Identity-free coverage for the current settled-order customer linkage."""

    basis: Literal["current_order_linkage"]
    distinct_linked_customers_count: int
    linked_orders_count: int
    unlinked_orders_count: int


@dataclass(frozen=True, slots=True)
class OverviewTopProduct:
    menu_item_id: UUID
    name: str
    product_type: str
    quantity_sold: Decimal
    gross_line_sales_minor: int


@dataclass(frozen=True, slots=True)
class OverviewDeviceUsage:
    device_type: str
    completed_sessions_count: int
    play_elapsed_ms: int | None
    unknown_play_sessions_count: int


@dataclass(frozen=True, slots=True)
class OverviewPanels:
    top_products: tuple[OverviewTopProduct, ...]
    device_usage: tuple[OverviewDeviceUsage, ...]


@dataclass(frozen=True, slots=True)
class ReportsOverviewProjection:
    """One-snapshot, analytics-safe projection for Reports Overview.

    ``report`` is retained only inside the service/router boundary so the
    established ReportsAggregator remains the single financial calculator.
    The API maps an explicit operational allowlist and never serializes the
    finance-bearing P&L object itself.
    """

    report: PnLReport
    timezone: str
    snapshot_at: datetime
    sessions_started_count: int
    linked_customers: LinkedCustomerCoverage
    local_hour_profile: PosLocalHourProfile | None
    panels: OverviewPanels | None = None


class ReportsOverviewService:
    """Build the reduced Overview from one read-only transaction snapshot."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def load(
        self,
        *,
        company_id: UUID,
        branch_id: UUID,
        period_start: date,
        period_end: date,
        timezone_name: str,
        include_panels: bool = False,
    ) -> ReportsOverviewProjection:
        start_at, end_at = local_date_bounds_utc(
            period_start,
            period_end,
            timezone_name,
        )
        sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)

        # Invoke the established financial calculator exactly once. The
        # reduced response maps only its analytics-safe operational totals.
        report = await ReportsAggregator(self.session).aggregate(
            company_id=company_id,
            branch_id=branch_id,
            period_start=period_start,
            period_end=period_end,
            period="custom",
            label=f"{period_start.isoformat()} → {period_end.isoformat()}",
        )

        sessions_started_count = int(
            (
                await self.session.execute(
                    select(func.count(GamingSession.id))
                    .join(Station, Station.id == GamingSession.station_id)
                    .where(
                        GamingSession.company_id == company_id,
                        Station.company_id == company_id,
                        Station.branch_id == branch_id,
                        GamingSession.start_at >= start_at,
                        GamingSession.start_at < end_at,
                        GamingSession.status != "cancelled",
                    )
                )
            ).scalar_one()
            or 0
        )

        coverage_row = (
            await self.session.execute(
                select(
                    func.count(func.distinct(Order.customer_id)).label(
                        "distinct_linked_customers_count"
                    ),
                    func.coalesce(
                        func.sum(
                            case((Order.customer_id.isnot(None), 1), else_=0)
                        ),
                        0,
                    ).label("linked_orders_count"),
                    func.coalesce(
                        func.sum(
                            case((Order.customer_id.is_(None), 1), else_=0)
                        ),
                        0,
                    ).label("unlinked_orders_count"),
                ).where(
                    Order.company_id == company_id,
                    Order.branch_id == branch_id,
                    sale_at >= start_at,
                    sale_at < end_at,
                    Order.status.in_(("paid", "refunded")),
                )
            )
        ).one()
        linked_customers = LinkedCustomerCoverage(
            basis="current_order_linkage",
            distinct_linked_customers_count=int(
                coverage_row.distinct_linked_customers_count or 0
            ),
            linked_orders_count=int(coverage_row.linked_orders_count or 0),
            unlinked_orders_count=int(coverage_row.unlinked_orders_count or 0),
        )

        local_hour_profile = None
        if period_start == period_end:
            local_hour_profile = await load_pos_local_hour_profile(
                self.session,
                company_id=company_id,
                branch_id=branch_id,
                business_date=period_start,
                timezone_name=timezone_name,
            )

        panels = None
        if include_panels:
            panels = OverviewPanels(
                top_products=await load_overview_top_products(
                    self.session,
                    company_id=company_id,
                    branch_id=branch_id,
                    start_at=start_at,
                    end_at=end_at,
                ),
                device_usage=await load_overview_device_usage(
                    self.session,
                    company_id=company_id,
                    branch_id=branch_id,
                    start_at=start_at,
                    end_at=end_at,
                ),
            )

        snapshot_at = (
            await self.session.execute(select(func.transaction_timestamp()))
        ).scalar_one()
        return ReportsOverviewProjection(
            report=report,
            timezone=timezone_name,
            snapshot_at=snapshot_at,
            sessions_started_count=sessions_started_count,
            linked_customers=linked_customers,
            local_hour_profile=local_hour_profile,
            panels=panels,
        )


async def load_overview_top_products(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    start_at: datetime,
    end_at: datetime,
) -> tuple[OverviewTopProduct, ...]:
    """Rank original settled line sales, including subsequently refunded sales."""

    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    revenue = func.sum(OrderLine.line_total_minor)
    rows = (
        await session.execute(
            select(
                OrderLine.menu_item_id,
                OrderLine.menu_item_name_snapshot,
                OrderLine.menu_item_type_snapshot,
                func.sum(OrderLine.qty).label("quantity_sold"),
                revenue.label("gross_line_sales_minor"),
            )
            .select_from(OrderLine)
            .join(Order, Order.id == OrderLine.order_id)
            .where(
                Order.company_id == company_id,
                Order.branch_id == branch_id,
                sale_at >= start_at,
                sale_at < end_at,
                Order.status.in_(("paid", "refunded")),
                OrderLine.voided_at.is_(None),
            )
            .group_by(
                OrderLine.menu_item_id,
                OrderLine.menu_item_name_snapshot,
                OrderLine.menu_item_type_snapshot,
            )
            .order_by(
                revenue.desc(),
                OrderLine.menu_item_name_snapshot.collate("C"),
                OrderLine.menu_item_type_snapshot.collate("C"),
                OrderLine.menu_item_id,
            )
            .limit(5)
        )
    ).all()
    return tuple(
        OverviewTopProduct(
            menu_item_id=row.menu_item_id,
            name=row.menu_item_name_snapshot,
            product_type=row.menu_item_type_snapshot,
            quantity_sold=row.quantity_sold,
            gross_line_sales_minor=int(row.gross_line_sales_minor),
        )
        for row in rows
    )


async def load_overview_device_usage(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    start_at: datetime,
    end_at: datetime,
) -> tuple[OverviewDeviceUsage, ...]:
    """Whole-session play for sessions ended in the range, grouped by type.

    Transfers preserve type but replace station_id, so physical-station
    occupancy cannot be inferred. Missing end times cannot enter this dated
    cohort. Invalid timing within it stays unknown rather than becoming zero.
    """

    # PostgreSQL EXTRACT is numeric: floor once before subtracting precise
    # pauses, matching pause_clock.duration_ms/play_elapsed_ms without floats
    # or the rounded billing clock. 0068 already migrated legacy pause minutes.
    elapsed_ms = func.floor(
        func.extract("epoch", GamingSession.end_at - GamingSession.start_at) * 1_000
    )
    valid_timing = and_(
        GamingSession.end_at >= GamingSession.start_at,
        GamingSession.paused_at.is_(None),
        GamingSession.paused_duration_ms >= 0,
        GamingSession.paused_duration_ms <= elapsed_ms,
    )
    rows = (
        await session.execute(
            select(
                Station.type.label("device_type"),
                func.count(GamingSession.id).label("completed_sessions_count"),
                func.sum(
                    case(
                        (valid_timing, elapsed_ms - GamingSession.paused_duration_ms),
                        else_=0,
                    )
                ).label("known_play_elapsed_ms"),
                func.sum(case((valid_timing, 0), else_=1)).label(
                    "unknown_play_sessions_count"
                ),
            )
            .select_from(GamingSession)
            .join(Station, Station.id == GamingSession.station_id)
            .where(
                GamingSession.company_id == company_id,
                Station.company_id == company_id,
                Station.branch_id == branch_id,
                GamingSession.status == "ended",
                GamingSession.end_at >= start_at,
                GamingSession.end_at < end_at,
            )
            .group_by(Station.type)
            .order_by(func.count(GamingSession.id).desc(), Station.type.collate("C"))
        )
    ).all()
    return tuple(
        OverviewDeviceUsage(
            device_type=row.device_type,
            completed_sessions_count=int(row.completed_sessions_count),
            play_elapsed_ms=(
                None
                if row.unknown_play_sessions_count
                else int(row.known_play_elapsed_ms)
            ),
            unknown_play_sessions_count=int(row.unknown_play_sessions_count),
        )
        for row in rows
    )


def percentage_delta(current: int, previous: int) -> float | None:
    """Return a meaningful percentage or no baseline for zero/negative history."""

    if previous <= 0:
        return None
    return (current - previous) / previous * 100


async def load_pos_local_hour_profile(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    business_date: date,
    timezone_name: str,
) -> PosLocalHourProfile:
    """Load one local-date POS cohort as 24 local-hour bins.

    Missing DST hours remain zero and repeated hours deliberately combine in
    the same 0..23 bin.  Money is tip-exclusive, matching the existing
    Insights heatmap rather than the broader all-business Reports aggregate.
    """

    from_dt, to_dt = local_date_bounds_utc(business_date, business_date, timezone_name)
    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    local_sale_at = func.timezone(timezone_name, sale_at)
    hour = func.extract("hour", local_sale_at)
    rows = (
        await session.execute(
            select(
                hour.label("hour"),
                func.coalesce(func.sum(Order.total_minor - Order.tip_minor), 0).label(
                    "revenue_minor"
                ),
                func.count(Order.id).label("orders_count"),
            )
            .where(
                Order.company_id == company_id,
                Order.branch_id == branch_id,
                sale_at >= from_dt,
                sale_at < to_dt,
                Order.status.in_(("paid", "refunded")),
            )
            .group_by(hour)
            .order_by(hour)
        )
    ).all()

    populated = {
        int(row.hour): LocalHourPoint(
            hour=int(row.hour),
            revenue_minor=int(row.revenue_minor or 0),
            orders_count=int(row.orders_count or 0),
        )
        for row in rows
    }
    if any(hour_value not in range(24) for hour_value in populated):
        raise RuntimeError("POS local-hour query returned an invalid hour")
    points = tuple(
        populated.get(hour_value, LocalHourPoint(hour_value, 0, 0))
        for hour_value in range(24)
    )
    return PosLocalHourProfile(
        timezone=timezone_name,
        business_date=business_date,
        day_length_minutes=int((to_dt - from_dt).total_seconds() // 60),
        basis=POS_LOCAL_HOUR_PROFILE_BASIS,
        reconciles_to_daily_gross=False,
        total_revenue_minor=sum(point.revenue_minor for point in points),
        total_orders_count=sum(point.orders_count for point in points),
        points=points,
    )
