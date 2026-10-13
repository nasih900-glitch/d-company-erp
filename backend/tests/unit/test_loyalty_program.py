from __future__ import annotations

from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.models import Customer, PointsRedemption
from app.models.pos import Order
from app.services.pos import points as points_service
from app.services.pos.loyalty_program import (
    LEGACY_RULES,
    LoadedLoyaltyProgram,
    LoyaltyProgramRules,
    project_rank,
    require_expected_revision,
)
from app.core.errors import BusinessRuleError


def test_legacy_revision_rules_preserve_existing_economics() -> None:
    assert LEGACY_RULES.gaming_earn_points == 2
    assert LEGACY_RULES.gaming_earn_rupees == 10
    assert LEGACY_RULES.minor_per_point == 10
    assert [(r.key, r.threshold, r.bonus_points) for r in LEGACY_RULES.ranks] == [
        ("rookie", 0, 0), ("player", 200, 50),
        ("pro", 500, 150), ("legend", 1000, 300),
    ]


def test_program_rejects_aggregate_points_overflow() -> None:
    raw = LEGACY_RULES.model_dump(mode="json")
    raw["ranks"] = [{**rank, "bonus_points": 2_000_000_000} for rank in raw["ranks"]]
    with pytest.raises(ValidationError, match="combined rank bonuses"):
        LoyaltyProgramRules.model_validate(raw)


def test_awarded_rank_remains_stable_after_threshold_increase() -> None:
    raw = LEGACY_RULES.model_dump(mode="json")
    raw["ranks"][1]["threshold"] = 600
    raw["ranks"][2]["threshold"] = 900
    raw["ranks"][3]["threshold"] = 1_200
    raised = LoyaltyProgramRules.model_validate(raw)
    projection = project_rank(raised, lifetime_points=250, awarded_keys={"rookie", "player"})
    assert projection.key == "player"
    assert projection.points_to_next == 650


def test_revisionless_legacy_client_gets_upgrade_guidance_after_customization() -> None:
    program = LoadedLoyaltyProgram(
        revision=1,
        rules=LEGACY_RULES,
        published_by=None,
        published_at=None,
    )
    with pytest.raises(BusinessRuleError, match="Update the app or use the current Web ERP"):
        require_expected_revision(program, None)


def test_stale_current_client_gets_refresh_guidance() -> None:
    program = LoadedLoyaltyProgram(
        revision=2,
        rules=LEGACY_RULES,
        published_by=None,
        published_at=None,
    )
    with pytest.raises(BusinessRuleError, match="Refresh this bill"):
        require_expected_revision(program, 1)


class _ScalarResult:
    def __init__(self, value):
        self.value = value

    def scalar_one_or_none(self):
        return self.value


class _Session:
    def __init__(self, redemption):
        self.redemption = redemption

    async def execute(self, _statement):
        return _ScalarResult(self.redemption)

    async def delete(self, _row):
        raise AssertionError("non-zero capped redemption must not be deleted")

    async def flush(self):
        pass


@pytest.mark.asyncio
async def test_generic_reprice_shrink_then_grow_keeps_accepted_snapshot(monkeypatch) -> None:
    company_id = uuid4()
    customer_id = uuid4()
    order = Order(id=uuid4(), company_id=company_id, customer_id=customer_id, total_minor=10_000)
    customer = Customer(id=customer_id, company_id=company_id, phone="9999999999")
    redemption = PointsRedemption(
        id=uuid4(), customer_id=customer_id, order_id=order.id,
        points_spent=100, amount_minor=1_000, program_revision=2,
        redemption_kind="generic", accepted_points_cost=100,
        accepted_value_minor=1_000, rules_snapshot=LEGACY_RULES.model_dump(mode="json"),
    )

    async def resolve(*_args, **_kwargs):
        return customer

    monkeypatch.setattr(points_service, "resolve_order_customer", resolve)
    session = _Session(redemption)
    shrunk = await points_service.reprice_existing_points_redemption(
        session, order=order, company_id=company_id, remaining_minor=400,
    )
    assert (shrunk.points_spent, shrunk.amount_minor) == (40, 400)
    assert (redemption.accepted_points_cost, redemption.accepted_value_minor) == (100, 1_000)
    grown = await points_service.reprice_existing_points_redemption(
        session, order=order, company_id=company_id, remaining_minor=5_000,
    )
    assert (grown.points_spent, grown.amount_minor) == (40, 400)
    assert (redemption.accepted_points_cost, redemption.accepted_value_minor) == (100, 1_000)


@pytest.mark.asyncio
async def test_fixed_reward_reprice_never_partially_changes_accepted_credit(monkeypatch) -> None:
    company_id = uuid4()
    customer_id = uuid4()
    order = Order(id=uuid4(), company_id=company_id, customer_id=customer_id, total_minor=5_000)
    customer = Customer(id=customer_id, company_id=company_id, phone="9999999998")
    redemption = PointsRedemption(
        id=uuid4(), customer_id=customer_id, order_id=order.id,
        points_spent=300, amount_minor=3_000, program_revision=2,
        redemption_kind="fixed_reward", reward_key="credit_30",
        accepted_points_cost=300, accepted_value_minor=3_000,
        rules_snapshot=LEGACY_RULES.model_dump(mode="json"),
    )

    async def resolve(*_args, **_kwargs):
        return customer

    monkeypatch.setattr(points_service, "resolve_order_customer", resolve)
    session = _Session(redemption)
    unchanged = await points_service.reprice_existing_points_redemption(
        session, order=order, company_id=company_id, remaining_minor=3_000,
    )
    assert (unchanged.points_spent, unchanged.amount_minor) == (300, 3_000)
    with pytest.raises(BusinessRuleError, match="below the accepted fixed points credit"):
        await points_service.reprice_existing_points_redemption(
            session, order=order, company_id=company_id, remaining_minor=2_999,
        )
    assert (redemption.points_spent, redemption.amount_minor) == (300, 3_000)
