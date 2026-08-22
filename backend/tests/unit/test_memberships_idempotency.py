"""Database-free tests for POST /memberships/subscribe idempotency.

starts_at/expires_at are computed from datetime.now() fresh on every call,
and the overlap check (a customer cannot hold two unexpired terms at once)
guards against a *second real* subscription, not against a retry of the
*same* one — a retry after a dropped response would hit that guard's
BusinessRuleError with no way to recover the original SubscriptionRead.
Idempotency-Key is therefore mandatory here, same reasoning as Inventory's
GRN/adjustment writes (see test_inventory_router_idempotency.py) and
Events' ticket sales (see test_events_idempotency.py, which this mirrors).

cancel_subscription is deliberately NOT covered here — it targets an
existing row by id and already has a natural one-way guard
(`if sub.cancelled_at: raise BusinessRuleError(...)`), the same shape as
Events' check_in_ticket, which Phase 11 established needs no idempotency
key (a retry on an already-cancelled subscription is a clean 4xx, not a
silent duplicate).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

import app.api.v1.memberships.router as memberships_router
from app.api.v1.memberships.router import SubscribeRequest, SubscriptionRead, subscribe
from app.core.errors import BusinessRuleError, ForbiddenError
from app.core.tenant import TenantContext

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
CUSTOMER_ID = UUID("22222222-2222-2222-2222-222222222222")
TIER_ID = UUID("33333333-3333-3333-3333-333333333333")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


def _tenant(*, protected_access: bool = True) -> TenantContext:
    return TenantContext(
        user_id=USER_ID, company_id=COMPANY_ID, branch_id=None,
        terminal_id=None, roles=("owner",), protected_access=protected_access,
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="membership-subscribe-2026-08-22-v1",
            idempotency_request_hash="request-hash",
        )
    )


def _payload(**overrides) -> SubscribeRequest:
    fields = {
        "customer_id": CUSTOMER_ID,
        "tier_id": TIER_ID,
        "billing_cycle": "monthly",
        "paid_via": "cash",
        **overrides,
    }
    return SubscribeRequest(**fields)


def _customer() -> SimpleNamespace:
    return SimpleNamespace(id=CUSTOMER_ID, company_id=COMPANY_ID)


def _tier(*, monthly_price_minor: int = 99900) -> SimpleNamespace:
    return SimpleNamespace(
        id=TIER_ID, company_id=COMPANY_ID, deleted_at=None,
        code="GOLD", name="Gold", monthly_price_minor=monthly_price_minor,
        annual_price_minor=None,
    )


class _Result:
    def __init__(self, *, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar


class _QueuedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.added: list = []
        self.flushes = 0

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)

    async def get(self, model, key):
        raise AssertionError(f"unexpected get({model}, {key})")

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


@pytest.mark.asyncio
async def test_subscribe_requires_idempotency_key() -> None:
    session = _QueuedSession([])
    bare_request = SimpleNamespace(state=SimpleNamespace())

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await subscribe(_payload(), session, bare_request, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_subscribe_rejects_a_non_protected_owner_before_touching_idempotency() -> None:
    """The protected_access check happens before Idempotency-Key is even
    read — a non-owner request never reserves a key at all."""
    session = _QueuedSession([])
    with pytest.raises(ForbiddenError, match="protected owner"):
        await subscribe(_payload(), session, _request(), _tenant(protected_access=False))
    assert session.added == []


@pytest.mark.asyncio
async def test_subscribe_persists_correct_fields(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    monkeypatch.setattr(memberships_router, "check_or_reserve", reserve)
    monkeypatch.setattr(memberships_router, "store_response", store)

    class _CreateSession(_QueuedSession):
        async def get(self, model, key):
            from app.models import MembershipTier
            if model is MembershipTier:
                return _tier()
            raise AssertionError(f"unexpected get({model}, {key})")

    session = _CreateSession(
        [
            _Result(scalar=_customer()),  # customer lock
            _Result(scalar=None),  # no overlapping membership
            _Result(scalar=_tier()),  # tier lock
        ]
    )
    result = await subscribe(_payload(), session, _request(), _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    stored = session.added[0]
    assert stored.customer_id == CUSTOMER_ID
    assert stored.tier_id == TIER_ID
    assert stored.billing_cycle == "monthly"
    assert stored.amount_paid_minor == 99900
    assert stored.auto_renew is False

    assert result.customer_id == CUSTOMER_ID
    assert result.tier_code == "GOLD"
    assert result.amount_paid_minor == 99900
    assert result.is_active is True


@pytest.mark.asyncio
async def test_subscribe_rejects_an_overlapping_active_membership(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(memberships_router, "check_or_reserve", reserve)

    existing = SimpleNamespace(
        id=uuid4(), expires_at=datetime.now(UTC) + timedelta(days=10),
    )
    session = _QueuedSession(
        [_Result(scalar=_customer()), _Result(scalar=existing)]
    )

    with pytest.raises(BusinessRuleError, match="already has a membership term"):
        await subscribe(_payload(), session, _request(), _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_subscribe_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = SubscriptionRead(
        id=uuid4(),
        customer_id=CUSTOMER_ID,
        tier_id=TIER_ID,
        tier_code="GOLD",
        tier_name="Gold",
        billing_cycle="monthly",
        starts_at=datetime(2026, 8, 22, 10, 0, tzinfo=UTC),
        expires_at=datetime(2026, 9, 21, 10, 0, tzinfo=UTC),
        cancelled_at=None,
        auto_renew=False,
        amount_paid_minor=99900,
        is_active=True,
    )

    async def replay(*_args, **_kwargs):
        return {"status_code": 201, "body": existing.model_dump(mode="json")}

    monkeypatch.setattr(memberships_router, "check_or_reserve", replay)

    class _NoMutationSession:
        def __getattr__(self, name):
            raise AssertionError(f"Replay attempted database mutation via {name}")

    response = await subscribe(_payload(), _NoMutationSession(), _request(), _tenant())
    assert response == existing
