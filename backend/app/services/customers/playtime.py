"""Read-only customer playtime projection for the disabled draft program."""

from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID

from sqlalchemy import and_, case, func, or_, select

from app.models import (
    Customer,
    GamingSession,
    GamingSessionExtension,
    MembershipBenefitReservation,
    Order,
    OrderLine,
    Payment,
    Refund,
    Station,
)


@dataclass(frozen=True)
class PlaytimeProgram:
    threshold_paid_minutes: int
    reward_minutes: int

    def estimate(self, qualifying_minutes: int) -> int:
        return (max(0, qualifying_minutes) // self.threshold_paid_minutes) * self.reward_minutes


def mask_phone(phone: str) -> str:
    if len(phone) <= 4:
        return "•" * len(phone)
    return f"{'•' * min(6, len(phone) - 4)}{phone[-4:]}"


def _scoped_aggregates(company_id: UUID):
    payments = (
        select(Payment.order_id, func.sum(Payment.amount_minor).label("paid_minor"))
        .join(Order, Order.id == Payment.order_id)
        .where(Order.company_id == company_id)
        .group_by(Payment.order_id)
        .subquery()
    )
    refunds = (
        select(Refund.order_id, func.sum(Refund.amount_minor).label("refunded_minor"))
        .join(Order, Order.id == Refund.order_id)
        .where(Order.company_id == company_id)
        .group_by(Refund.order_id)
        .subquery()
    )
    extensions = (
        select(
            GamingSessionExtension.gaming_session_id,
            func.sum(GamingSessionExtension.duration_minutes).label("extension_minutes"),
        )
        .where(GamingSessionExtension.company_id == company_id)
        .group_by(GamingSessionExtension.gaming_session_id)
        .subquery()
    )
    line_flags = (
        select(
            OrderLine.order_id,
            func.max(
                case(
                    (
                        or_(
                            OrderLine.discount_minor > 0,
                            OrderLine.voided_at.is_not(None),
                        ),
                        1,
                    ),
                    else_=0,
                )
            ).label("has_discount_or_void"),
        )
        .join(Order, Order.id == OrderLine.order_id)
        .where(Order.company_id == company_id)
        .group_by(OrderLine.order_id)
        .subquery()
    )
    benefits = (
        select(MembershipBenefitReservation.order_id)
        .join(Order, Order.id == MembershipBenefitReservation.order_id)
        .where(
            Order.company_id == company_id,
            MembershipBenefitReservation.consumed_at.is_not(None),
        )
        .distinct()
        .subquery()
    )
    return payments, refunds, extensions, line_flags, benefits


def _facts(company_id: UUID):
    payments, refunds, extensions, line_flags, benefits = _scoped_aggregates(company_id)
    effective_customer_id = case(
        (
            GamingSession.customer_identity_provenance == "historical",
            func.coalesce(GamingSession.customer_id, Order.customer_id),
        ),
        else_=GamingSession.customer_id,
    )
    played = func.greatest(func.coalesce(GamingSession.billable_minutes, 0), 0)
    package_cap = (
        func.coalesce(GamingSession.package_duration_minutes_snapshot, 0)
        + func.coalesce(extensions.c.extension_minutes, 0)
    )
    qualifying_minutes = case(
        (
            and_(
                GamingSession.status == "ended",
                played > 0,
                GamingSession.amount_minor > 0,
                GamingSession.billing_mode != "legacy_ambiguous",
                Order.id.is_not(None),
                Order.status == "paid",
                Order.customer_id == effective_customer_id,
                func.coalesce(payments.c.paid_minor, 0) > 0,
                func.coalesce(refunds.c.refunded_minor, 0) == 0,
                func.coalesce(Order.discount_minor, 0) == 0,
                func.coalesce(Order.manual_discount_minor, 0) == 0,
                func.coalesce(Order.points_redeemed_minor, 0) == 0,
                func.coalesce(line_flags.c.has_discount_or_void, 0) == 0,
                benefits.c.order_id.is_(None),
            ),
            case(
                (
                    GamingSession.billing_mode == "package",
                    func.least(played, package_cap),
                ),
                else_=played,
            ),
        ),
        else_=0,
    )
    stmt = (
        select(
            GamingSession.id.label("session_id"),
            effective_customer_id.label("customer_id"),
            Station.name.label("station_name"),
            GamingSession.end_at,
            played.label("played_minutes"),
            qualifying_minutes.label("qualifying_minutes"),
            GamingSession.status.label("session_status"),
            GamingSession.billing_mode.label("billing_mode"),
            package_cap.label("package_cap_minutes"),
            GamingSession.customer_id.label("session_customer_id"),
            Order.customer_id.label("order_customer_id"),
            Order.status.label("order_status"),
            GamingSession.amount_minor.label("gaming_amount_minor"),
            func.coalesce(payments.c.paid_minor, 0).label("paid_minor"),
            func.coalesce(refunds.c.refunded_minor, 0).label("refunded_minor"),
            (
                func.coalesce(Order.discount_minor, 0)
                + func.coalesce(Order.manual_discount_minor, 0)
                + func.coalesce(Order.points_redeemed_minor, 0)
            ).label("order_discount_minor"),
            func.coalesce(line_flags.c.has_discount_or_void, 0).label("line_flag"),
            benefits.c.order_id.label("benefit_order_id"),
        )
        .outerjoin(
            Order,
            and_(
                Order.id == GamingSession.order_id,
                Order.company_id == company_id,
            ),
        )
        .join(
            Station,
            and_(
                Station.id == GamingSession.station_id,
                Station.company_id == company_id,
            ),
        )
        .outerjoin(payments, payments.c.order_id == Order.id)
        .outerjoin(refunds, refunds.c.order_id == Order.id)
        .outerjoin(extensions, extensions.c.gaming_session_id == GamingSession.id)
        .outerjoin(line_flags, line_flags.c.order_id == Order.id)
        .outerjoin(benefits, benefits.c.order_id == Order.id)
        .where(GamingSession.company_id == company_id)
    )
    return stmt, effective_customer_id, qualifying_minutes


async def leaderboard(
    session,
    *,
    company_id: UUID,
    program: PlaytimeProgram,
    q: str | None,
    page: int,
    limit: int,
) -> tuple[list[dict], int]:
    facts, _effective_customer_id, _qualifying_minutes = _facts(company_id)
    fact_rows = facts.where(GamingSession.status == "ended").subquery()
    totals = (
        select(
            fact_rows.c.customer_id,
            func.sum(fact_rows.c.played_minutes).label("total_played_minutes"),
            func.sum(fact_rows.c.qualifying_minutes).label("qualifying_paid_minutes"),
        )
        .where(fact_rows.c.customer_id.is_not(None))
        .group_by(fact_rows.c.customer_id)
        .subquery()
    )
    played_total = func.coalesce(totals.c.total_played_minutes, 0)
    qualifying_total = func.coalesce(totals.c.qualifying_paid_minutes, 0)
    ranked = (
        select(
            Customer.id.label("customer_id"),
            Customer.name,
            Customer.phone,
            played_total.label("total_played_minutes"),
            qualifying_total.label("qualifying_paid_minutes"),
            func.row_number()
            .over(order_by=(played_total.desc(), Customer.created_at.asc(), Customer.id.asc()))
            .label("rank"),
        )
        .outerjoin(totals, totals.c.customer_id == Customer.id)
        .where(
            Customer.company_id == company_id,
            Customer.deleted_at.is_(None),
        )
        .cte("ranked_customer_playtime")
    )
    filtered = select(ranked)
    count_stmt = select(func.count()).select_from(ranked)
    clean_q = q.strip() if q else ""
    if clean_q:
        like = f"%{clean_q}%"
        predicate = or_(ranked.c.name.ilike(like), ranked.c.phone.ilike(like))
        filtered = filtered.where(predicate)
        count_stmt = count_stmt.where(predicate)
    total = int((await session.execute(count_stmt)).scalar_one())
    rows = (
        await session.execute(
            filtered.order_by(ranked.c.rank).offset((page - 1) * limit).limit(limit)
        )
    ).mappings().all()
    return [
        {
            "rank": int(row["rank"]),
            "customer_id": row["customer_id"],
            "name": row["name"],
            "masked_phone": mask_phone(row["phone"]),
            "total_played_minutes": int(row["total_played_minutes"]),
            "qualifying_paid_minutes": int(row["qualifying_paid_minutes"]),
            "draft_estimated_reward_minutes": program.estimate(
                int(row["qualifying_paid_minutes"])
            ),
        }
        for row in rows
    ], total


def _qualification_status(row) -> str:
    if (
        row.session_customer_id is not None
        and row.order_customer_id is not None
        and row.order_customer_id != row.session_customer_id
    ):
        return "customer_mismatch"
    if int(row.refunded_minor) > 0 or row.order_status == "refunded":
        return "refunded"
    if row.order_status != "paid" or int(row.paid_minor) <= 0:
        return "unpaid"
    if int(row.gaming_amount_minor or 0) <= 0:
        return "no_paid_gaming_amount"
    if row.billing_mode == "legacy_ambiguous":
        return "legacy_billing_unverified"
    if row.billing_mode == "package" and int(row.package_cap_minutes) <= 0:
        return "unproven_package_duration"
    if int(row.order_discount_minor) > 0 or int(row.line_flag) or row.benefit_order_id:
        return "discounted_or_free"
    return "eligible" if int(row.qualifying_minutes) > 0 else "not_qualifying"


async def customer_playtime(
    session,
    *,
    company_id: UUID,
    customer_id: UUID,
    program: PlaytimeProgram,
    limit: int,
    offset: int,
) -> dict:
    facts, effective_customer_id, _qualifying = _facts(company_id)
    scoped = facts.where(
        GamingSession.status == "ended",
        effective_customer_id == customer_id,
    )
    scoped_rows = scoped.subquery()
    count_row = (
        await session.execute(
            select(
                func.count(),
                func.coalesce(func.sum(scoped_rows.c.played_minutes), 0),
                func.coalesce(func.sum(scoped_rows.c.qualifying_minutes), 0),
            ).select_from(scoped_rows)
        )
    ).one()
    rows = (
        await session.execute(
            select(scoped_rows)
            .order_by(scoped_rows.c.end_at.desc(), scoped_rows.c.session_id.desc())
            .offset(offset)
            .limit(limit)
        )
    ).all()
    total_played = int(count_row[1])
    qualifying = int(count_row[2])
    return {
        "total_sessions": int(count_row[0]),
        "total_played_minutes": total_played,
        "qualifying_paid_minutes": qualifying,
        "draft_estimated_reward_minutes": program.estimate(qualifying),
        "history": [
            {
                "session_id": row.session_id,
                "station_name": row.station_name,
                "ended_at": row.end_at,
                "played_minutes": int(row.played_minutes),
                "qualifying_paid_minutes": int(row.qualifying_minutes),
                "qualification_status": _qualification_status(row),
            }
            for row in rows
        ],
    }
