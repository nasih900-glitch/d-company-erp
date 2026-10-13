"""Versioned, company-scoped loyalty program rules and lock protocol."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, ROUND_FLOOR
from typing import Literal
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, field_validator, model_validator
from sqlalchemy import select

from app.core.errors import BusinessRuleError
from app.models import Company, LoyaltyProgramRevision

RANK_KEYS = ("rookie", "player", "pro", "legend")
POSTGRES_INTEGER_MAX = 2_147_483_647
MAX_ORDER_MINOR = 9_999_999_999
REWARD_KEYS = (
    "extra_controller", "snack", "ps5_30", "ps5_60", "squad_night",
    "vr_legend", "legend_credit_1000",
)


class RankRule(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    key: Literal["rookie", "player", "pro", "legend"]
    label: str = Field(min_length=1, max_length=40)
    threshold: StrictInt = Field(ge=0, le=2_000_000_000)
    bonus_points: StrictInt = Field(ge=0, le=2_000_000_000)

    @field_validator("label")
    @classmethod
    def normalize_label(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("rank label is required")
        return normalized


class RewardRule(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    key: Literal[
        "extra_controller", "snack", "ps5_30", "ps5_60", "squad_night",
        "vr_legend", "legend_credit_1000",
    ]
    label: str = Field(min_length=1, max_length=80)
    description: str = Field(min_length=1, max_length=240)
    points_cost: StrictInt = Field(ge=1, le=2_000_000_000)
    value_minor: StrictInt = Field(ge=1, le=9_999_999_999)
    min_rank_key: Literal["rookie", "player", "pro", "legend"]
    enabled: StrictBool

    @field_validator("label", "description")
    @classmethod
    def normalize_text(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if not normalized:
            raise ValueError("reward text is required")
        return normalized


class LoyaltyProgramRules(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid")
    gaming_earn_points: StrictInt = Field(ge=0, le=1_000_000)
    gaming_earn_rupees: StrictInt = Field(ge=1, le=1_000_000)
    minor_per_point: StrictInt = Field(ge=1, le=1_000_000)
    ranks: list[RankRule] = Field(min_length=4, max_length=4)
    rewards: list[RewardRule] = Field(min_length=7, max_length=7)

    @model_validator(mode="after")
    def validate_stable_shape(self) -> "LoyaltyProgramRules":
        if tuple(rank.key for rank in self.ranks) != RANK_KEYS:
            raise ValueError("rank keys must remain rookie, player, pro, legend in order")
        thresholds = [rank.threshold for rank in self.ranks]
        if thresholds[0] != 0 or thresholds != sorted(thresholds) or len(set(thresholds)) != 4:
            raise ValueError("rank thresholds must start at zero and increase strictly")
        if tuple(reward.key for reward in self.rewards) != REWARD_KEYS:
            raise ValueError("reward keys and order are fixed")
        if sum(rank.bonus_points for rank in self.ranks) > POSTGRES_INTEGER_MAX:
            raise ValueError("combined rank bonuses exceed the points storage limit")
        max_order_earn = (
            MAX_ORDER_MINOR * self.gaming_earn_points
            // (self.gaming_earn_rupees * 100)
        )
        worst_case_points = max_order_earn * 10 + sum(
            rank.bonus_points for rank in self.ranks
        )
        if worst_case_points > POSTGRES_INTEGER_MAX:
            raise ValueError(
                "earning rate and bonuses can exceed the points storage limit for one bill"
            )
        return self


LEGACY_RULES = LoyaltyProgramRules.model_validate({
    "gaming_earn_points": 2,
    "gaming_earn_rupees": 10,
    "minor_per_point": 10,
    "ranks": [
        {"key": "rookie", "label": "Rookie", "threshold": 0, "bonus_points": 0},
        {"key": "player", "label": "Player", "threshold": 200, "bonus_points": 50},
        {"key": "pro", "label": "Pro", "threshold": 500, "bonus_points": 150},
        {"key": "legend", "label": "Legend", "threshold": 1000, "bonus_points": 300},
    ],
    "rewards": [
        {"key": "extra_controller", "label": "Free extra controller", "description": "One session, any dual-mode station", "points_cost": 50, "value_minor": 3000, "min_rank_key": "rookie", "enabled": True},
        {"key": "snack", "label": "Free snack or drink", "description": "Any single food or drink item", "points_cost": 60, "value_minor": 4000, "min_rank_key": "rookie", "enabled": True},
        {"key": "ps5_30", "label": "Free 30 min · PS5 Single", "description": "Redeem at session start", "points_cost": 150, "value_minor": 8000, "min_rank_key": "rookie", "enabled": True},
        {"key": "ps5_60", "label": "Free 1 hour · PS5 Single", "description": "Redeem at session start", "points_cost": 220, "value_minor": 12000, "min_rank_key": "player", "enabled": True},
        {"key": "squad_night", "label": "Squad Night", "description": "1 hour PS5 Dual + a free extra controller", "points_cost": 350, "value_minor": 18000, "min_rank_key": "pro", "enabled": True},
        {"key": "vr_legend", "label": "Legend pick: 1 hour VR Racing Sim", "description": "Our top-tier experience, for our top-tier players", "points_cost": 500, "value_minor": 25000, "min_rank_key": "legend", "enabled": True},
        {"key": "legend_credit_1000", "label": "Legend's ₹1,000 Credit", "description": "Up to ₹1,000 off any single bill", "points_cost": 1000, "value_minor": 100000, "min_rank_key": "legend", "enabled": True},
    ],
})


@dataclass(frozen=True, slots=True)
class LoadedLoyaltyProgram:
    revision: int
    rules: LoyaltyProgramRules
    published_by: UUID | None
    published_at: datetime

    def snapshot(self) -> dict:
        return self.rules.model_dump(mode="json")


@dataclass(frozen=True, slots=True)
class LoyaltyRankProjection:
    key: str
    label: str
    lifetime_points: int
    rank_floor: int
    next_key: str | None
    next_label: str | None
    next_floor: int | None
    points_to_next: int | None


async def load_loyalty_program(
    session,
    *,
    company_id: UUID,
    company_lock: Literal["share", "update", "none"] = "share",
) -> LoadedLoyaltyProgram:
    """Load rules under the global lock order: Company, then order/customer rows."""
    if company_lock != "none":
        company_stmt = select(Company.id).where(Company.id == company_id)
        company_stmt = company_stmt.with_for_update(read=company_lock == "share")
        if (await session.execute(company_stmt)).scalar_one_or_none() is None:
            raise BusinessRuleError("company loyalty program is unavailable")
    row = (
        await session.execute(
            select(LoyaltyProgramRevision)
            .where(LoyaltyProgramRevision.company_id == company_id)
            .order_by(LoyaltyProgramRevision.revision.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    if row is None:
        raise BusinessRuleError("company loyalty program is missing; points changes are unavailable")
    try:
        rules = LoyaltyProgramRules.model_validate(row.rules)
    except Exception as exc:
        raise BusinessRuleError(
            "company loyalty program is invalid; points changes are unavailable"
        ) from exc
    return LoadedLoyaltyProgram(
        revision=int(row.revision),
        rules=rules,
        published_by=row.published_by,
        published_at=row.published_at,
    )


def require_expected_revision(program: LoadedLoyaltyProgram, expected: int | None) -> None:
    if expected is None and program.revision == 0:
        return
    if expected is None:
        raise BusinessRuleError(
            "This app version cannot apply points after the points program was customized. "
            "Update the app or use the current Web ERP; ordinary payments remain available."
        )
    if expected != program.revision:
        raise BusinessRuleError(
            "The points program changed. Refresh this bill and review the points value again."
        )


def new_legacy_loyalty_program(
    company_id: UUID,
    *,
    published_at: datetime | None = None,
) -> LoyaltyProgramRevision:
    """Build the explicit revision-zero row for a newly provisioned tenant.

    Alembic covers companies that exist during the upgrade. Every company
    created afterwards must call this factory in its own provisioning
    transaction; readers deliberately stay fail-closed when the row is absent.
    """
    return LoyaltyProgramRevision(
        id=uuid4(),
        company_id=company_id,
        revision=0,
        rules=LEGACY_RULES.model_dump(mode="json"),
        published_by=None,
        published_at=published_at or datetime.now(timezone.utc),
    )


def points_to_minor(points: int, rules: LoyaltyProgramRules) -> int:
    return max(0, int(points)) * int(rules.minor_per_point)


def minor_to_points(minor: int, rules: LoyaltyProgramRules) -> int:
    return max(0, int(minor)) // int(rules.minor_per_point)


def earned_gaming_points(
    *, gaming_minor: int, paid_numerator: int, paid_denominator: int, multiplier: Decimal
) -> int:
    if gaming_minor <= 0 or paid_numerator <= 0 or paid_denominator <= 0:
        return 0
    value = (
        Decimal(gaming_minor)
        * Decimal(paid_numerator)
        * multiplier
        / Decimal(paid_denominator)
    )
    return int(value.quantize(Decimal("1"), rounding=ROUND_FLOOR))


def checked_points_add(current: int, delta: int, *, label: str) -> int:
    result = int(current) + int(delta)
    if result > POSTGRES_INTEGER_MAX or result < -POSTGRES_INTEGER_MAX - 1:
        raise BusinessRuleError(f"{label} exceeds the supported points storage limit")
    return result


def effective_rank_key(rules: LoyaltyProgramRules, awarded_keys: set[str]) -> str:
    awarded = [rank.key for rank in rules.ranks if rank.key in awarded_keys]
    return awarded[-1] if awarded else "rookie"


def project_rank(
    rules: LoyaltyProgramRules,
    *,
    lifetime_points: int,
    awarded_keys: set[str],
) -> LoyaltyRankProjection:
    """Project current labels/threshold progress from permanent award evidence."""
    points = max(0, int(lifetime_points))
    key = effective_rank_key(rules, awarded_keys)
    index = rank_index(key)
    current = rules.ranks[index]
    next_rank = rules.ranks[index + 1] if index + 1 < len(rules.ranks) else None
    return LoyaltyRankProjection(
        key=key,
        label=current.label,
        lifetime_points=points,
        rank_floor=current.threshold,
        next_key=next_rank.key if next_rank else None,
        next_label=next_rank.label if next_rank else None,
        next_floor=next_rank.threshold if next_rank else None,
        points_to_next=(max(0, next_rank.threshold - points) if next_rank else None),
    )


def rank_index(key: str) -> int:
    return RANK_KEYS.index(key) if key in RANK_KEYS else 0


def now_utc() -> datetime:
    return datetime.now(timezone.utc)
