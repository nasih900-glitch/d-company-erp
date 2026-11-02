"""PII-minimal Home analytics projection."""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Literal
from uuid import UUID

from sqlalchemy import BigInteger, String, cast, func, literal, select, union_all
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.permissions import has_permission
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_today
from app.models import Customer, GamingSession, Order, Shift, Station
from app.services.reports import ReportsAggregator
from app.services.reports.overview import (
    PosLocalHourProfile,
    load_pos_local_hour_profile,
    percentage_delta,
)

ActivityKind = Literal["pos_order_paid", "pos_shift_opened", "gaming_session_started"]
PosActivityCapability = Literal["available", "missing_permission", "missing_terminal"]
GamingActivityCapability = Literal["available", "missing_permission"]


@dataclass(frozen=True, slots=True)
class MetricComparison:
    current: int
    previous: int
    delta_pct: float | None
    comparison_status: Literal["available", "no_baseline"]


@dataclass(frozen=True, slots=True)
class HomeActivity:
    kind: ActivityKind
    occurred_at: datetime
    display_reference: str
    title: str
    amount_minor: int | None
    scope: Literal["terminal", "branch"]


@dataclass(frozen=True, slots=True)
class HomeReadModel:
    branch_id: UUID
    terminal_id: UUID | None
    business_date: date
    timezone: str
    snapshot_at: datetime
    revenue: MetricComparison
    orders: MetricComparison
    customer_count: int
    active_station_capacity: int
    open_sessions: int
    local_hour_profile: PosLocalHourProfile
    recent_activity: tuple[HomeActivity, ...]
    pos_activity_capability: PosActivityCapability
    gaming_activity_capability: GamingActivityCapability


def _comparison(current: int, previous: int) -> MetricComparison:
    delta = percentage_delta(current, previous)
    return MetricComparison(
        current=current,
        previous=previous,
        delta_pct=delta,
        comparison_status="available" if delta is not None else "no_baseline",
    )


def _safe_display(value: object, *, fallback: str) -> str:
    """Keep operational labels compact and strip control/newline formatting."""

    printable = "".join(
        character
        for character in str(value or "")
        if not unicodedata.category(character).startswith("C")
    )
    cleaned = " ".join(printable.split()).strip()
    return (cleaned or fallback)[:80]


async def _load_pos_activity(
    session: AsyncSession,
    *,
    tenant: TenantContext,
) -> tuple[HomeActivity, ...]:
    branch_id = tenant.branch_id
    terminal_id = tenant.terminal_id
    if branch_id is None or terminal_id is None:
        raise ValueError("POS Home activity requires branch and terminal scope")
    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    paid_orders = select(
        literal("pos_order_paid").label("kind"),
        sale_at.label("occurred_at"),
        func.coalesce(Order.invoice_no, literal("Unissued paid order")).label(
            "display_reference"
        ),
        literal("POS order paid").label("title"),
        cast(Order.total_minor - Order.tip_minor, BigInteger).label("amount_minor"),
        literal("terminal").label("scope"),
    ).where(
        Order.company_id == tenant.company_id,
        Order.branch_id == branch_id,
        Order.terminal_id == terminal_id,
        Order.status.in_(("paid", "refunded")),
        sale_at.is_not(None),
    )
    shifts = select(
        literal("pos_shift_opened").label("kind"),
        Shift.opened_at.label("occurred_at"),
        literal("POS shift").label("display_reference"),
        literal("POS shift opened").label("title"),
        cast(literal(None), BigInteger).label("amount_minor"),
        literal("terminal").label("scope"),
    ).where(
        Shift.company_id == tenant.company_id,
        Shift.branch_id == branch_id,
        Shift.terminal_id == terminal_id,
    )
    activity = union_all(paid_orders, shifts).subquery()
    rows = (
        await session.execute(
            select(activity)
            .order_by(
                activity.c.occurred_at.desc(),
                activity.c.kind.asc(),
                activity.c.display_reference.asc(),
            )
            .limit(8)
        )
    ).all()
    return tuple(
        HomeActivity(
            kind=row.kind,
            occurred_at=row.occurred_at,
            display_reference=_safe_display(row.display_reference, fallback="POS activity"),
            title=row.title,
            amount_minor=int(row.amount_minor) if row.amount_minor is not None else None,
            scope=row.scope,
        )
        for row in rows
    )


async def _load_gaming_activity(
    session: AsyncSession,
    *,
    tenant: TenantContext,
) -> tuple[HomeActivity, ...]:
    branch_id = tenant.branch_id
    if branch_id is None:
        raise ValueError("Gaming Home activity requires branch scope")
    rows = (
        await session.execute(
            select(GamingSession.start_at, Station.name, Station.code)
            .join(Station, Station.id == GamingSession.station_id)
            .where(
                GamingSession.company_id == tenant.company_id,
                Station.company_id == tenant.company_id,
                Station.branch_id == branch_id,
                GamingSession.status != "cancelled",
            )
            .order_by(
                GamingSession.start_at.desc(),
                Station.code.asc(),
            )
            .limit(8)
        )
    ).all()
    return tuple(
        HomeActivity(
            kind="gaming_session_started",
            occurred_at=row.start_at,
            display_reference=_safe_display(row.name, fallback="Gaming station"),
            title="Gaming session started",
            amount_minor=None,
            scope="branch",
        )
        for row in rows
    )


class HomeReadModelService:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def load(self, *, tenant: TenantContext) -> HomeReadModel:
        branch_id = tenant.branch_id
        if branch_id is None:
            raise ValueError("a selected branch is required")

        timezone_name = await company_timezone(self.session, tenant.company_id)
        today = local_today(timezone_name)
        yesterday = today - timedelta(days=1)
        aggregator = ReportsAggregator(self.session)
        current_report = await aggregator.aggregate_daily(
            company_id=tenant.company_id,
            branch_id=branch_id,
            d=today,
        )
        previous_report = await aggregator.aggregate_daily(
            company_id=tenant.company_id,
            branch_id=branch_id,
            d=yesterday,
        )

        customer_count = int(
            (
                await self.session.execute(
                    select(func.count(Customer.id)).where(
                        Customer.company_id == tenant.company_id,
                        Customer.deleted_at.is_(None),
                    )
                )
            ).scalar_one()
            or 0
        )
        gaming_counts = (
            await self.session.execute(
                select(
                    select(func.count(Station.id))
                    .where(
                        Station.company_id == tenant.company_id,
                        Station.branch_id == branch_id,
                        Station.is_active.is_(True),
                    )
                    .scalar_subquery()
                    .label("active_station_capacity"),
                    select(func.count(GamingSession.id))
                    .select_from(GamingSession)
                    .join(Station, Station.id == GamingSession.station_id)
                    .where(
                        GamingSession.company_id == tenant.company_id,
                        Station.company_id == tenant.company_id,
                        Station.branch_id == branch_id,
                        GamingSession.status.in_(("active", "paused")),
                    )
                    .scalar_subquery()
                    .label("open_sessions"),
                )
            )
        ).one()
        local_hour_profile = await load_pos_local_hour_profile(
            self.session,
            company_id=tenant.company_id,
            branch_id=branch_id,
            business_date=today,
            timezone_name=timezone_name,
        )

        can_read_pos = await has_permission(self.session, tenant, "pos.read")
        can_read_gaming = await has_permission(self.session, tenant, "gaming.read")
        if not can_read_pos:
            pos_capability: PosActivityCapability = "missing_permission"
            pos_activity: tuple[HomeActivity, ...] = ()
        elif tenant.terminal_id is None:
            pos_capability = "missing_terminal"
            pos_activity = ()
        else:
            pos_capability = "available"
            pos_activity = await _load_pos_activity(self.session, tenant=tenant)

        if can_read_gaming:
            gaming_capability: GamingActivityCapability = "available"
            gaming_activity = await _load_gaming_activity(self.session, tenant=tenant)
        else:
            gaming_capability = "missing_permission"
            gaming_activity = ()

        recent_activity = tuple(
            sorted(
                (*pos_activity, *gaming_activity),
                key=lambda row: (row.occurred_at, row.kind, row.display_reference),
                reverse=True,
            )[:8]
        )
        snapshot_at = (
            await self.session.execute(select(func.transaction_timestamp()))
        ).scalar_one()
        return HomeReadModel(
            branch_id=branch_id,
            terminal_id=tenant.terminal_id,
            business_date=today,
            timezone=timezone_name,
            snapshot_at=snapshot_at,
            revenue=_comparison(
                current_report.gross_revenue_minor,
                previous_report.gross_revenue_minor,
            ),
            orders=_comparison(current_report.orders_count, previous_report.orders_count),
            customer_count=customer_count,
            active_station_capacity=int(gaming_counts.active_station_capacity or 0),
            open_sessions=int(gaming_counts.open_sessions or 0),
            local_hour_profile=local_hour_profile,
            recent_activity=recent_activity,
            pos_activity_capability=pos_capability,
            gaming_activity_capability=gaming_capability,
        )
