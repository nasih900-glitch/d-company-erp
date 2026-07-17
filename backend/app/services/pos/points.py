"""Loyalty-points redemption for playtime.

Reserve-then-consume against a customer's real points balance, mirroring
membership_benefits.py's concurrency-safe discipline (row stays for audit if
the order voids; only a live or consumed row counts against the balance) —
but with no period allowance, since points aren't tied to a paid membership
tier: any customer with a phone on file can earn and spend them.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import func, or_, select

from app.core.errors import BusinessRuleError
from app.models import Customer, Order, PointsRedemption

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

# 10 points = ₹1 of playtime value. Deliberately conservative: gaming earns 2
# points per ₹10 spent (20 points / ₹100), so this redemption rate is a ~2%
# effective giveback — small enough it can never erode margin. Tune this one
# constant if the owners want a different rate.
MINOR_PER_POINT = 10

_COUNTED_ORDER_STATUSES = ("open", "held", "paid", "refunded")


def points_to_minor(points: int) -> int:
    return max(0, int(points)) * MINOR_PER_POINT


def minor_to_points(minor: int) -> int:
    """Floor, never round up — never grant more point-value than requested."""
    return max(0, int(minor)) // MINOR_PER_POINT


# Gaming rank ladder — keyed off Customer.lifetime_gaming_points_earned, a
# counter that only ever goes up. Ranks must never demote a customer for
# redeeming points, so they're deliberately never computed from the
# spendable loyalty_points balance. Ordered highest threshold first so
# gaming_rank() can return on the first match.
GAMING_RANKS: list[tuple[str, int]] = [
    ("Legend", 1000),
    ("Pro", 500),
    ("Player", 200),
    ("Rookie", 0),
]


def gaming_rank(lifetime_points: int) -> str:
    points = max(0, int(lifetime_points))
    for name, threshold in GAMING_RANKS:
        if points >= threshold:
            return name
    return GAMING_RANKS[-1][0]


# Lowest-to-highest, for comparing "is rank A at least rank B".
_RANK_ORDER: list[str] = [name for name, _ in reversed(GAMING_RANKS)]


def _rank_index(rank: str) -> int:
    return _RANK_ORDER.index(rank) if rank in _RANK_ORDER else 0


def rank_meets(rank: str, minimum: str) -> bool:
    return _rank_index(rank) >= _rank_index(minimum)


# One-time bonus credited to the SPENDABLE loyalty_points balance (never to
# lifetime_gaming_points_earned — crediting the rank-driving counter here
# would let a bonus cascade into unlocking the next rank too). Fires once per
# customer per rank, the moment their lifetime total first crosses that
# threshold — see rank_up_bonus_points().
RANK_UP_BONUS_POINTS: dict[str, int] = {
    "Player": 50,
    "Pro": 150,
    "Legend": 300,
}


def rank_up_bonus_points(*, old_lifetime: int, new_lifetime: int) -> int:
    """Sum of every rank-up bonus crossed between two lifetime-point totals.

    A single big session can cross more than one threshold at once (e.g.
    190 -> 550 crosses both Player and Pro) — every threshold actually
    crossed pays its bonus, not just the final rank landed on.
    """
    old_idx = _rank_index(gaming_rank(old_lifetime))
    new_idx = _rank_index(gaming_rank(new_lifetime))
    if new_idx <= old_idx:
        return 0
    return sum(
        RANK_UP_BONUS_POINTS.get(_RANK_ORDER[i], 0)
        for i in range(old_idx + 1, new_idx + 1)
    )


@dataclass(frozen=True, slots=True)
class RankProgress:
    rank: str
    lifetime_points: int
    rank_floor: int  # threshold that unlocked the current rank
    next_rank: str | None
    next_rank_floor: int | None  # None once at the top rank
    points_to_next_rank: int | None  # None once at the top rank


def rank_progress(lifetime_points: int) -> RankProgress:
    points = max(0, int(lifetime_points))
    current = gaming_rank(points)
    # Ranks are stored highest-first; walk lowest-first to find what's next
    # and to know the current rank's own floor (for a progress-bar fill %).
    ascending = list(reversed(GAMING_RANKS))
    rank_floor = 0
    next_rank: str | None = None
    next_rank_floor: int | None = None
    points_to_next: int | None = None
    for i, (name, threshold) in enumerate(ascending):
        if name == current:
            rank_floor = threshold
            if i + 1 < len(ascending):
                next_name, next_threshold = ascending[i + 1]
                next_rank = next_name
                next_rank_floor = next_threshold
                points_to_next = max(0, next_threshold - points)
            break
    return RankProgress(
        rank=current,
        lifetime_points=points,
        rank_floor=rank_floor,
        next_rank=next_rank,
        next_rank_floor=next_rank_floor,
        points_to_next_rank=points_to_next,
    )


@dataclass(frozen=True, slots=True)
class RewardCatalogItem:
    key: str
    name: str
    description: str
    points_cost: int
    value_minor: int
    min_rank: str


# Named, fixed-value rewards — deliberately a better deal per point than the
# generic MINOR_PER_POINT cash-out above (e.g. "ps5_30" is ~53 paise/point
# vs. the generic 10 paise/point). The generic rate exists so points are
# never worthless; this catalog exists so specific goals feel worth chasing
# — that's the whole point of a rank ladder. All-or-nothing: a reward is
# redeemed in full or not at all, never partially.
REWARD_CATALOG: list[RewardCatalogItem] = [
    RewardCatalogItem(
        key="extra_controller", name="Free extra controller",
        description="One session, any dual-mode station",
        points_cost=50, value_minor=3000, min_rank="Rookie",
    ),
    RewardCatalogItem(
        key="snack", name="Free snack or drink",
        description="Any single food or drink item",
        points_cost=60, value_minor=4000, min_rank="Rookie",
    ),
    RewardCatalogItem(
        key="ps5_30", name="Free 30 min · PS5 Single",
        description="Redeem at session start",
        points_cost=150, value_minor=8000, min_rank="Rookie",
    ),
    RewardCatalogItem(
        key="ps5_60", name="Free 1 hour · PS5 Single",
        description="Redeem at session start",
        points_cost=220, value_minor=12000, min_rank="Player",
    ),
    RewardCatalogItem(
        key="squad_night", name="Squad Night",
        description="1 hour PS5 Dual + a free extra controller",
        points_cost=350, value_minor=18000, min_rank="Pro",
    ),
    RewardCatalogItem(
        key="vr_legend", name="Legend pick: 1 hour VR Racing Sim",
        description="Our top-tier experience, for our top-tier players",
        points_cost=500, value_minor=25000, min_rank="Legend",
    ),
]


def reward_by_key(key: str) -> RewardCatalogItem | None:
    return next((r for r in REWARD_CATALOG if r.key == key), None)


def rewards_available_to(rank: str) -> list[RewardCatalogItem]:
    """Every reward this rank has unlocked (affordability is checked separately)."""
    return [r for r in REWARD_CATALOG if rank_meets(rank, r.min_rank)]


@dataclass(frozen=True, slots=True)
class PointsRedemptionResult:
    points_spent: int = 0
    amount_minor: int = 0


async def _used_or_reserved_points(
    session: AsyncSession, *, customer_id: UUID, excluding_order_id: UUID
) -> int:
    """Points already locked up by this customer's other live/consumed orders."""
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(PointsRedemption.points_spent), 0))
                .select_from(PointsRedemption)
                .join(Order, Order.id == PointsRedemption.order_id)
                .where(
                    PointsRedemption.customer_id == customer_id,
                    PointsRedemption.order_id != excluding_order_id,
                    or_(
                        PointsRedemption.consumed_at.is_not(None),
                        Order.status.in_(_COUNTED_ORDER_STATUSES),
                    ),
                )
            )
        ).scalar_one()
        or 0
    )


async def reserve_points_redemption(
    session: AsyncSession,
    *,
    order: Order,
    company_id: UUID,
    requested_points: int,
    at: datetime,
) -> PointsRedemptionResult:
    """Replace an unpaid order's points reservation under a customer row lock.

    ``requested_points`` should already be capped to what the order's total
    can actually absorb (the caller does this — see the discount/points
    endpoints and the reprice call sites) — this only further caps against
    the customer's real, current points balance, so the two caps compose
    without ever reserving points that end up unused.
    """
    existing = (
        await session.execute(
            select(PointsRedemption).where(PointsRedemption.order_id == order.id).with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None and existing.consumed_at is not None:
        raise BusinessRuleError("cannot change points redemption after settlement")

    if not order.customer_phone or requested_points <= 0:
        if existing is not None:
            await session.delete(existing)
            await session.flush()
        return PointsRedemptionResult()

    customer = (
        await session.execute(
            select(Customer)
            .where(
                Customer.company_id == company_id,
                Customer.phone == order.customer_phone,
                Customer.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if customer is None:
        if existing is not None:
            await session.delete(existing)
            await session.flush()
        return PointsRedemptionResult()

    used_elsewhere = await _used_or_reserved_points(
        session, customer_id=customer.id, excluding_order_id=order.id
    )
    available = max(0, int(customer.loyalty_points or 0) - used_elsewhere)
    reserved = min(available, max(0, int(requested_points)))

    if existing is not None:
        await session.delete(existing)
        await session.flush()

    if reserved <= 0:
        return PointsRedemptionResult()

    session.add(
        PointsRedemption(
            id=uuid4(),
            customer_id=customer.id,
            order_id=order.id,
            points_spent=reserved,
            amount_minor=points_to_minor(reserved),
        )
    )
    return PointsRedemptionResult(points_spent=reserved, amount_minor=points_to_minor(reserved))


async def reserve_catalog_reward_redemption(
    session: AsyncSession,
    *,
    order: Order,
    company_id: UUID,
    reward_key: str,
    at: datetime,
) -> PointsRedemptionResult:
    """Redeem a named REWARD_CATALOG item for its fixed points_cost, crediting
    its fixed value_minor — not the generic MINOR_PER_POINT conversion (see
    REWARD_CATALOG's docstring on why that's deliberate). All-or-nothing: raises
    rather than partially filling, since "80% of a free drink" isn't a thing.
    Mirrors reserve_points_redemption's replace-on-reprice, row-locked discipline.
    """
    reward = reward_by_key(reward_key)
    if reward is None:
        raise BusinessRuleError("unknown reward")

    existing = (
        await session.execute(
            select(PointsRedemption).where(PointsRedemption.order_id == order.id).with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None and existing.consumed_at is not None:
        raise BusinessRuleError("cannot change points redemption after settlement")

    if not order.customer_phone:
        raise BusinessRuleError("attach a customer to this order before redeeming a reward")

    customer = (
        await session.execute(
            select(Customer)
            .where(
                Customer.company_id == company_id,
                Customer.phone == order.customer_phone,
                Customer.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if customer is None:
        raise BusinessRuleError("attach a customer to this order before redeeming a reward")

    if not rank_meets(gaming_rank(customer.lifetime_gaming_points_earned), reward.min_rank):
        raise BusinessRuleError(f"{reward.name} unlocks at {reward.min_rank} rank")

    used_elsewhere = await _used_or_reserved_points(
        session, customer_id=customer.id, excluding_order_id=order.id
    )
    available = max(0, int(customer.loyalty_points or 0) - used_elsewhere)
    if available < reward.points_cost:
        raise BusinessRuleError(
            f"Not enough points for {reward.name} — need {reward.points_cost}, have {available}."
        )
    bill_room = int(order.total_minor or 0) + (existing.amount_minor if existing else 0)
    if reward.value_minor > bill_room:
        raise BusinessRuleError(f"This bill isn't big enough to redeem {reward.name}.")

    if existing is not None:
        await session.delete(existing)
        await session.flush()

    session.add(
        PointsRedemption(
            id=uuid4(),
            customer_id=customer.id,
            order_id=order.id,
            points_spent=reward.points_cost,
            amount_minor=reward.value_minor,
        )
    )
    return PointsRedemptionResult(points_spent=reward.points_cost, amount_minor=reward.value_minor)


async def consume_points_redemption(session: AsyncSession, *, order_id: UUID, at: datetime) -> None:
    """Permanently debit the customer's points balance at final settlement."""
    row = (
        await session.execute(
            select(PointsRedemption).where(PointsRedemption.order_id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    if row is None or row.consumed_at is not None:
        return
    customer = (
        await session.execute(
            select(Customer).where(Customer.id == row.customer_id).with_for_update()
        )
    ).scalar_one_or_none()
    if customer is None:
        raise BusinessRuleError("points redemption cannot be settled; customer is missing")
    customer.loyalty_points = max(0, int(customer.loyalty_points or 0) - row.points_spent)
    row.consumed_at = at


async def points_redeemed_for_order(session: AsyncSession, *, order: Order) -> int:
    """Points spent on a live or settled bill, never a void."""
    if order.status not in _COUNTED_ORDER_STATUSES:
        return 0
    row = (
        await session.execute(
            select(PointsRedemption.points_spent).where(PointsRedemption.order_id == order.id)
        )
    ).scalar_one_or_none()
    return int(row or 0)
