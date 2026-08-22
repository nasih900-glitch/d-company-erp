"""Database-free tests for POST /events/{event_id}/tickets idempotency.

ticket_no is computed fresh from the current sold count on every call
(_ticket_number(event_id, starts_at, sold + i + 1)), so it is NOT a safe
dedup fallback — a retry after a dropped response would recompute a higher
sold count and mint brand-new, non-colliding ticket numbers, silently
double-issuing tickets and double-consuming capacity. Idempotency-Key is
therefore mandatory here, same reasoning as Inventory's GRN/adjustment
writes (see test_inventory_router_idempotency.py) and Finance's
expenses/assets (see test_expenses_idempotency.py).
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

import app.api.v1.events.router as events_router
from app.api.v1.events.router import TicketRead, TicketSell, sell_tickets
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
EVENT_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID, company_id=COMPANY_ID, branch_id=None,
        terminal_id=None, roles=("owner",),
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="ticket-sale-2026-08-22-v1",
            idempotency_request_hash="request-hash",
        )
    )


def _payload(**overrides) -> TicketSell:
    fields = {
        "customer_name": "Rahul",
        "customer_phone": "9876543210",
        "seat": None,
        "qty": 2,
        "note": None,
        **overrides,
    }
    return TicketSell(**fields)


def _event(*, capacity: int = 50, status: str = "scheduled") -> SimpleNamespace:
    return SimpleNamespace(
        id=EVENT_ID, company_id=COMPANY_ID, name="Champions League Final",
        starts_at=datetime(2026, 8, 22, 20, 0, tzinfo=UTC), capacity=capacity,
        base_ticket_price_minor=25000, status=status, deleted_at=None,
    )


class _Result:
    def __init__(self, *, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar


class _QueuedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.added: list = []
        self.flushes = 0

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


@pytest.mark.asyncio
async def test_sell_tickets_requires_idempotency_key() -> None:
    session = _QueuedSession([])
    bare_request = SimpleNamespace(state=SimpleNamespace())

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await sell_tickets(EVENT_ID, _payload(), session, bare_request, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_sell_tickets_persists_correct_rows(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    monkeypatch.setattr(events_router, "check_or_reserve", reserve)
    monkeypatch.setattr(events_router, "store_response", store)

    session = _QueuedSession([_Result(scalar=_event()), _Result(scalar=0)])
    result = await sell_tickets(EVENT_ID, _payload(qty=2), session, _request(), _tenant())

    assert session.flushes == 2
    assert len(session.added) == 2
    assert len(result) == 2
    assert result[0].ticket_no != result[1].ticket_no
    assert all(t.price_paid_minor == 25000 for t in result)
    assert all(t.status == "sold" for t in result)


@pytest.mark.asyncio
async def test_sell_tickets_rejects_when_capacity_exceeded(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(events_router, "check_or_reserve", reserve)

    session = _QueuedSession([_Result(scalar=_event(capacity=3)), _Result(scalar=2)])

    with pytest.raises(BusinessRuleError, match="capacity exceeded"):
        await sell_tickets(EVENT_ID, _payload(qty=2), session, _request(), _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_sell_tickets_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = [
        TicketRead(
            id=uuid4(), ticket_no="EVT-20260822-2222-0001", event_id=EVENT_ID,
            event_name="Champions League Final", customer_name="Rahul",
            customer_phone="9876543210", seat=None, price_paid_minor=25000,
            status="sold", checked_in_at=None,
        ),
        TicketRead(
            id=uuid4(), ticket_no="EVT-20260822-2222-0002", event_id=EVENT_ID,
            event_name="Champions League Final", customer_name="Rahul",
            customer_phone="9876543210", seat=None, price_paid_minor=25000,
            status="sold", checked_in_at=None,
        ),
    ]

    async def replay(*_args, **_kwargs):
        return {
            "status_code": 201,
            "body": {"tickets": [t.model_dump(mode="json") for t in existing]},
        }

    monkeypatch.setattr(events_router, "check_or_reserve", replay)

    class _NoMutationSession:
        def __getattr__(self, name):
            raise AssertionError(f"Replay attempted database mutation via {name}")

    response = await sell_tickets(
        EVENT_ID, _payload(qty=2), _NoMutationSession(), _request(), _tenant(),
    )
    assert response == existing


@pytest.mark.asyncio
async def test_sell_tickets_rejects_when_event_not_scheduled_or_live(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(events_router, "check_or_reserve", reserve)

    session = _QueuedSession([_Result(scalar=_event(status="ended"))])

    with pytest.raises(BusinessRuleError, match="cannot sell tickets"):
        await sell_tickets(EVENT_ID, _payload(), session, _request(), _tenant())
    assert session.added == []
