"""Fail-closed contract for direct event-ticket writes.

Ticket reads and check-in remain operational for historical records, but a
new ticket cannot be minted until ticket issuance and POS settlement are one
atomic workflow.
"""

from __future__ import annotations

from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.api.v1.events.router import (
    TicketSell,
    check_in_ticket,
    list_tickets,
    sell_tickets,
)
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
EVENT_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=None,
        terminal_id=None,
        roles=("owner",),
    )


def _payload() -> TicketSell:
    return TicketSell(
        customer_name="Rahul",
        customer_phone="9876543210",
        seat=None,
        qty=2,
        note=None,
    )


class _NoDatabaseSession:
    """Any attempted reservation, query, flush, or mutation fails the test."""

    def __getattr__(self, name):
        raise AssertionError(f"disabled ticket sale touched the database via {name}")


@pytest.mark.asyncio
async def test_sell_tickets_rejects_before_reservation_or_database_mutation() -> None:
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="must-not-be-reserved",
            idempotency_request_hash="must-not-be-reserved",
        )
    )

    with pytest.raises(BusinessRuleError) as error:
        await sell_tickets(
            EVENT_ID,
            _payload(),
            _NoDatabaseSession(),
            request,
            _tenant(),
        )

    assert error.value.code == "business_rule"
    assert "temporarily disabled" in error.value.message
    assert "POS order, payment, shift, or invoice" in error.value.message
    assert "existing tickets can still be viewed and checked in" in error.value.message


class _Result:
    def __init__(self, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = rows or []

    def scalar_one_or_none(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


@pytest.mark.asyncio
async def test_historical_unlinked_ticket_remains_readable() -> None:
    event = SimpleNamespace(
        id=EVENT_ID,
        company_id=COMPANY_ID,
        name="Champions League Final",
        deleted_at=None,
    )
    ticket = SimpleNamespace(
        id=uuid4(),
        event_id=EVENT_ID,
        order_id=None,
        ticket_no="EVT-20260822-2222-0001",
        customer_name="Rahul",
        customer_phone="9876543210",
        seat=None,
        price_paid_minor=25_000,
        status="sold",
        checked_in_at=None,
    )

    class _HistoricalReadSession:
        def __init__(self) -> None:
            self.results = [_Result(scalar=event), _Result(rows=[ticket])]

        async def execute(self, _statement):
            return self.results.pop(0)

    session = _HistoricalReadSession()
    response = await list_tickets(EVENT_ID, session, _tenant())

    assert not session.results
    assert len(response) == 1
    assert response[0].id == ticket.id
    assert response[0].status == "sold"


@pytest.mark.asyncio
async def test_historical_unlinked_ticket_can_still_be_checked_in() -> None:
    event = SimpleNamespace(
        id=EVENT_ID,
        company_id=COMPANY_ID,
        name="Champions League Final",
        deleted_at=None,
    )
    ticket = SimpleNamespace(
        id=uuid4(),
        event_id=EVENT_ID,
        order_id=None,
        ticket_no="EVT-20260822-2222-0001",
        customer_name="Rahul",
        customer_phone="9876543210",
        seat=None,
        price_paid_minor=25_000,
        status="sold",
        checked_in_at=None,
        checked_in_by=None,
    )

    class _HistoricalSession:
        async def execute(self, _statement):
            return _Result(event)

        async def get(self, _model, entity_id):
            assert entity_id == ticket.id
            return ticket

    response = await check_in_ticket(
        EVENT_ID,
        ticket.id,
        _HistoricalSession(),
        _tenant(),
    )

    assert response.status == "checked_in"
    assert response.id == ticket.id
    assert ticket.checked_in_by == USER_ID
    assert ticket.checked_in_at is not None
