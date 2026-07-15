"""Concurrency-safe reservation and settlement of membership free allowances."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from typing import TYPE_CHECKING, Literal
from uuid import UUID, uuid4

from sqlalchemy import func, or_, select

from app.core.errors import BusinessRuleError
from app.core.timezone import company_timezone, local_today
from app.models import (
    Customer,
    CustomerMembership,
    MembershipBenefitReservation,
    MembershipTier,
    Order,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

BenefitType = Literal["gaming_minutes", "hookah_count"]
GAMING_MINUTES: BenefitType = "gaming_minutes"
HOOKAH_COUNT: BenefitType = "hookah_count"
_COUNTED_ORDER_STATUSES = ("open", "held", "paid", "refunded")


@dataclass(frozen=True, slots=True)
class AppliedMembershipBenefits:
    gaming_minutes: int = 0
    hookah_count: int = 0


def benefit_period_start(
    benefit_type: BenefitType,
    *,
    at: datetime,
    timezone_name: str | None,
) -> date:
    """Return the venue-local ISO week or calendar-month anchor."""
    local_date = local_today(timezone_name, now=at)
    if benefit_type == GAMING_MINUTES:
        return local_date - timedelta(days=local_date.weekday())
    if benefit_type == HOOKAH_COUNT:
        return local_date.replace(day=1)
    raise ValueError(f"unsupported membership benefit: {benefit_type}")


def reservable_quantity(*, allowance: int, already_used_or_reserved: int, requested: int) -> int:
    """Pure allocation rule shared by both benefit types."""
    remaining = max(0, int(allowance) - max(0, int(already_used_or_reserved)))
    return min(remaining, max(0, int(requested)))


async def _candidate_membership(
    session: AsyncSession,
    *,
    company_id: UUID,
    customer_phone: str | None,
    at: datetime,
) -> tuple[CustomerMembership, MembershipTier] | None:
    if not customer_phone:
        return None
    rows = (
        await session.execute(
            select(CustomerMembership, MembershipTier)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .where(
                Customer.company_id == company_id,
                Customer.phone == customer_phone,
                Customer.deleted_at.is_(None),
                CustomerMembership.starts_at <= at,
                CustomerMembership.expires_at > at,
                MembershipTier.company_id == company_id,
                MembershipTier.deleted_at.is_(None),
            )
            .order_by(CustomerMembership.expires_at.desc(), CustomerMembership.id)
            .limit(2)
        )
    ).all()
    if len(rows) > 1:
        raise BusinessRuleError(
            "Customer has overlapping membership terms. "
            "A protected owner must reconcile them before benefits can be applied."
        )
    if not rows:
        return None
    row = rows[0]
    return row[0], row[1]


async def _lock_memberships(
    session: AsyncSession,
    membership_ids: set[UUID],
) -> dict[UUID, CustomerMembership]:
    if not membership_ids:
        return {}
    rows = (
        (
            await session.execute(
                select(CustomerMembership)
                .where(CustomerMembership.id.in_(membership_ids))
                .order_by(CustomerMembership.id)
                .with_for_update()
                .execution_options(populate_existing=True)
            )
        )
        .scalars()
        .all()
    )
    return {row.id: row for row in rows}


async def _used_or_reserved_quantity(
    session: AsyncSession,
    *,
    membership_id: UUID,
    benefit_type: BenefitType,
    period_start: date,
    excluding_order_id: UUID,
) -> int:
    """Count consumed history plus reservations held by still-live bills."""
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(MembershipBenefitReservation.quantity), 0))
                .select_from(MembershipBenefitReservation)
                .join(Order, Order.id == MembershipBenefitReservation.order_id)
                .where(
                    MembershipBenefitReservation.membership_id == membership_id,
                    MembershipBenefitReservation.benefit_type == benefit_type,
                    MembershipBenefitReservation.period_start == period_start,
                    MembershipBenefitReservation.order_id != excluding_order_id,
                    or_(
                        MembershipBenefitReservation.consumed_at.is_not(None),
                        Order.status.in_(_COUNTED_ORDER_STATUSES),
                    ),
                )
            )
        ).scalar_one()
        or 0
    )


async def reserve_membership_benefits(
    session: AsyncSession,
    *,
    order: Order,
    company_id: UUID,
    requested_gaming_minutes: int = 0,
    requested_hookah_count: int = 0,
    at: datetime | None = None,
) -> AppliedMembershipBenefits:
    """Replace an unpaid order's reservations under a membership row lock.

    The caller already holds the order row lock.  Membership IDs are then
    locked in stable UUID order so two terminals cannot reserve the same
    period allowance or deadlock while a customer is changed on a bill.
    """
    now = at or datetime.now(UTC)
    existing_rows = (
        (
            await session.execute(
                select(MembershipBenefitReservation)
                .where(MembershipBenefitReservation.order_id == order.id)
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    if any(row.consumed_at is not None for row in existing_rows):
        raise BusinessRuleError("cannot reprice membership benefits after settlement")

    candidate = await _candidate_membership(
        session,
        company_id=company_id,
        customer_phone=order.customer_phone,
        at=now,
    )
    membership_ids = {row.membership_id for row in existing_rows}
    if candidate:
        membership_ids.add(candidate[0].id)
    locked = await _lock_memberships(session, membership_ids)

    # Revalidate time-sensitive membership state after acquiring its lock.
    membership: CustomerMembership | None = None
    tier: MembershipTier | None = None
    if candidate:
        membership = locked.get(candidate[0].id)
        if membership is None or membership.starts_at > now or membership.expires_at <= now:
            membership = None
        else:
            tier = (
                await session.execute(
                    select(MembershipTier)
                    .where(MembershipTier.id == membership.tier_id)
                    .with_for_update()
                    .execution_options(populate_existing=True)
                )
            ).scalar_one_or_none()
            if tier is None or tier.deleted_at is not None or tier.company_id != company_id:
                membership = None
                tier = None

    # Repricing or changing the attached phone replaces this order's prior
    # provisional claim. Consumed rows were rejected above. Delete through the
    # ORM so the automatic audit recorder captures the released reservation.
    for existing_row in existing_rows:
        await session.delete(existing_row)

    if membership is None or tier is None:
        return AppliedMembershipBenefits()
    if requested_gaming_minutes <= 0 and requested_hookah_count <= 0:
        return AppliedMembershipBenefits()

    timezone_name = await company_timezone(session, company_id)
    gaming_period = benefit_period_start(
        GAMING_MINUTES,
        at=now,
        timezone_name=timezone_name,
    )
    hookah_period = benefit_period_start(
        HOOKAH_COUNT,
        at=now,
        timezone_name=timezone_name,
    )
    gaming_used = await _used_or_reserved_quantity(
        session,
        membership_id=membership.id,
        benefit_type=GAMING_MINUTES,
        period_start=gaming_period,
        excluding_order_id=order.id,
    )
    hookah_used = await _used_or_reserved_quantity(
        session,
        membership_id=membership.id,
        benefit_type=HOOKAH_COUNT,
        period_start=hookah_period,
        excluding_order_id=order.id,
    )
    gaming_reserved = reservable_quantity(
        allowance=int(tier.free_gaming_minutes_per_week or 0),
        already_used_or_reserved=gaming_used,
        requested=requested_gaming_minutes,
    )
    hookah_reserved = reservable_quantity(
        allowance=int(tier.free_hookah_per_month or 0),
        already_used_or_reserved=hookah_used,
        requested=requested_hookah_count,
    )

    if gaming_reserved:
        session.add(
            MembershipBenefitReservation(
                id=uuid4(),
                membership_id=membership.id,
                order_id=order.id,
                benefit_type=GAMING_MINUTES,
                period_start=gaming_period,
                quantity=gaming_reserved,
            )
        )
    if hookah_reserved:
        session.add(
            MembershipBenefitReservation(
                id=uuid4(),
                membership_id=membership.id,
                order_id=order.id,
                benefit_type=HOOKAH_COUNT,
                period_start=hookah_period,
                quantity=hookah_reserved,
            )
        )
    return AppliedMembershipBenefits(
        gaming_minutes=gaming_reserved,
        hookah_count=hookah_reserved,
    )


def _apply_legacy_usage_counter(
    membership: CustomerMembership,
    reservation: MembershipBenefitReservation,
) -> None:
    """Maintain old current-period counters without corrupting newer periods."""
    if reservation.benefit_type == GAMING_MINUTES:
        anchor = membership.gaming_usage_week_start
        if anchor is None or reservation.period_start > anchor:
            membership.gaming_usage_week_start = reservation.period_start
            membership.gaming_minutes_used_this_week = reservation.quantity
        elif reservation.period_start == anchor:
            membership.gaming_minutes_used_this_week = (
                int(membership.gaming_minutes_used_this_week or 0) + reservation.quantity
            )
        return
    if reservation.benefit_type == HOOKAH_COUNT:
        anchor = membership.hookah_usage_month_start
        if anchor is None or reservation.period_start > anchor:
            membership.hookah_usage_month_start = reservation.period_start
            membership.hookah_used_this_month = reservation.quantity
        elif reservation.period_start == anchor:
            membership.hookah_used_this_month = (
                int(membership.hookah_used_this_month or 0) + reservation.quantity
            )
        return
    raise BusinessRuleError("unknown membership benefit reservation type")


async def consume_membership_benefits(
    session: AsyncSession,
    *,
    order_id: UUID,
    at: datetime,
) -> None:
    """Consume all of an order's reservations inside final settlement."""
    rows = (
        (
            await session.execute(
                select(MembershipBenefitReservation)
                .where(MembershipBenefitReservation.order_id == order_id)
                .order_by(MembershipBenefitReservation.benefit_type)
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    pending = [row for row in rows if row.consumed_at is None]
    if not pending:
        return
    locked = await _lock_memberships(session, {row.membership_id for row in pending})
    if len(locked) != len({row.membership_id for row in pending}):
        raise BusinessRuleError("membership reservation cannot be settled; membership is missing")
    for row in pending:
        membership = locked[row.membership_id]
        _apply_legacy_usage_counter(membership, row)
        row.consumed_at = at


async def applied_benefits_for_order(
    session: AsyncSession,
    *,
    order: Order,
) -> AppliedMembershipBenefits:
    """Return benefits visible on a live or settled bill, never a void."""
    if order.status not in _COUNTED_ORDER_STATUSES:
        return AppliedMembershipBenefits()
    rows = (
        await session.execute(
            select(
                MembershipBenefitReservation.benefit_type,
                func.sum(MembershipBenefitReservation.quantity),
            )
            .where(MembershipBenefitReservation.order_id == order.id)
            .group_by(MembershipBenefitReservation.benefit_type)
        )
    ).all()
    values = {benefit_type: int(quantity or 0) for benefit_type, quantity in rows}
    return AppliedMembershipBenefits(
        gaming_minutes=values.get(GAMING_MINUTES, 0),
        hookah_count=values.get(HOOKAH_COUNT, 0),
    )
