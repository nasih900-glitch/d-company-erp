"""Database-free tests for POST /finance/capital-entries idempotency.

Before this, create_capital_entry had only an "incidental guard": a
table-wide UNIQUE constraint on source_ref plus a pre-SELECT that raises a
bare 409 with just {capital_entry_id} in details — not a real replay of the
stored response, and no protection at all against a plain network-timeout
retry with the same key. This adds real check_or_reserve/store_response
idempotency (mirroring create_manual_collection/create_tip_payout in this
same router) while KEEPING the source_ref uniqueness check as a second,
independent layer — it catches a different failure mode (two different
offline drafts citing the same real bank UTR) that idempotency alone
wouldn't. See tests/unit/test_tip_payouts.py for the precedent this mirrors.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

import app.api.v1.finance.router as finance_router
from app.api.v1.finance.router import (
    CapitalEntryCreate,
    CapitalEntryRead,
    create_capital_entry,
)
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.tenant import TenantContext

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
OTHER_COMPANY_ID = UUID("99999999-9999-9999-9999-999999999999")
PARTNER_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID, company_id=COMPANY_ID, branch_id=None,
        terminal_id=None, roles=("owner",),
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="capital-entry-2026-08-22-v1",
            idempotency_request_hash="request-hash",
        )
    )


def _payload(**overrides) -> CapitalEntryCreate:
    fields = {
        "partner_id": PARTNER_ID,
        "type": "invest",
        "amount_minor": 500_000_00,
        "effective_at": datetime(2026, 8, 22, 9, 0, tzinfo=UTC),
        "settlement_account": "bank",
        "source_ref": "UTR2026082212345",
        "note": "Initial capital top-up",
        **overrides,
    }
    return CapitalEntryCreate(**fields)


def _partner(*, company_id: UUID = COMPANY_ID) -> SimpleNamespace:
    return SimpleNamespace(id=PARTNER_ID, company_id=company_id)


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
        self.rollbacks = 0

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)

    async def get(self, model, key):
        from app.models import User
        if model is User:
            return SimpleNamespace(id=key, name="Owner")
        raise AssertionError(f"unexpected get({model}, {key})")

    def add(self, entity) -> None:
        self.added.append(entity)
        # Mimic server_default=func.now() the way test_tip_payouts.py's
        # _CreateSession does — this double never actually hits the DB.
        if entity.created_at is None:
            entity.created_at = datetime(2026, 8, 22, 9, 5, tzinfo=UTC)

    async def flush(self) -> None:
        self.flushes += 1

    async def rollback(self) -> None:
        self.rollbacks += 1


@pytest.mark.asyncio
async def test_create_capital_entry_requires_idempotency_key() -> None:
    session = _QueuedSession([])
    bare_request = SimpleNamespace(state=SimpleNamespace())

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await create_capital_entry(_payload(), session, bare_request, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_capital_entry_persists_correct_fields(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)
    monkeypatch.setattr(finance_router, "store_response", store)

    session = _QueuedSession(
        [_Result(scalar=_partner()), _Result(scalar=None)]  # partner lock, source_ref check
    )
    result = await create_capital_entry(_payload(), session, _request(), _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    stored = session.added[0]
    assert stored.partner_id == PARTNER_ID
    assert stored.type == "invest"
    assert stored.amount_minor == 500_000_00
    assert stored.source_ref == "UTR2026082212345"
    assert stored.created_by == USER_ID

    assert result.amount_minor == 500_000_00
    assert result.created_by_name == "Owner"
    assert result.is_voided is False


@pytest.mark.asyncio
async def test_create_capital_entry_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = CapitalEntryRead(
        id=uuid4(),
        partner_id=PARTNER_ID,
        type="invest",
        amount_minor=500_000_00,
        effective_at=datetime(2026, 8, 22, 9, 0, tzinfo=UTC),
        settlement_account="bank",
        source_ref="UTR2026082212345",
        note="Initial capital top-up",
        created_by=USER_ID,
        created_by_name="Owner",
        created_at=datetime(2026, 8, 22, 9, 5, tzinfo=UTC),
        voided_at=None,
        voided_by=None,
        voided_by_name=None,
        void_reason=None,
        is_voided=False,
    )

    async def replay(*_args, **_kwargs):
        return {"status_code": 201, "body": existing.model_dump(mode="json")}

    monkeypatch.setattr(finance_router, "check_or_reserve", replay)

    class _NoMutationSession:
        def __getattr__(self, name):
            raise AssertionError(f"Replay attempted database mutation via {name}")

    response = await create_capital_entry(
        _payload(), _NoMutationSession(), _request(), _tenant(),
    )
    assert response == existing


@pytest.mark.asyncio
async def test_create_capital_entry_still_rejects_a_reused_source_ref(monkeypatch) -> None:
    """The idempotency-key check and the source_ref uniqueness check are
    independent layers — a *different* idempotency key citing a source_ref
    that already exists must still be rejected, not silently treated as a
    replay of the earlier entry."""
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)

    existing_id = uuid4()
    session = _QueuedSession(
        [_Result(scalar=_partner()), _Result(scalar=existing_id)]
    )

    with pytest.raises(ConflictError, match="already exists"):
        await create_capital_entry(_payload(), session, _request(), _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_capital_entry_rejects_a_partner_belonging_to_another_company(
    monkeypatch,
) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)

    session = _QueuedSession([_Result(scalar=None)])

    with pytest.raises(NotFoundError, match="partner not found"):
        await create_capital_entry(_payload(), session, _request(), _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_capital_entry_converts_a_concurrent_duplicate_source_ref_race(
    monkeypatch,
) -> None:
    """The pre-check SELECT for source_ref has no lock on CapitalEntry
    itself (only Partner is locked) — two concurrent requests citing the
    same source_ref can both pass the pre-check and race to insert. This
    proves the IntegrityError from the DB's real unique constraint is
    converted into a clean ConflictError, not a raw 500, mirroring
    Inventory's create_ingredient duplicate-SKU handling."""
    from sqlalchemy.exc import IntegrityError

    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)

    class _RaceSession(_QueuedSession):
        async def flush(self):
            await super().flush()
            raise IntegrityError("insert", {}, Exception("uq_capital_entry_source_ref"))

    session = _RaceSession([_Result(scalar=_partner()), _Result(scalar=None)])

    with pytest.raises(ConflictError, match="already exists"):
        await create_capital_entry(_payload(), session, _request(), _tenant())
    assert session.rollbacks == 1
