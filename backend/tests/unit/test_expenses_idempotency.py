"""Database-free tests for POST /finance/expenses idempotency.

Expense has a fresh uuid4() PK on every create and no unique constraint a
duplicate could collide against — unlike ingredient SKU or customer phone,
there is no fallback, so the Idempotency-Key header is mandatory, matching
Inventory's GRN/adjustment writes (see test_inventory_router_idempotency.py)
and this same file's create_capital_entry/create_asset siblings.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

import app.api.v1.finance.router as finance_router
from app.api.v1.finance.router import ExpenseCreate, ExpenseRead, create_expense
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext
from app.models import Branch, ExpenseCategory

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
CATEGORY_ID = UUID("33333333-3333-3333-3333-333333333333")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID, company_id=COMPANY_ID, branch_id=None,
        terminal_id=None, roles=("owner",),
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="expense-2026-08-22-v1",
            idempotency_request_hash="request-hash",
        )
    )


def _payload(**overrides) -> ExpenseCreate:
    fields = {
        "branch_id": BRANCH_ID,
        "category_id": CATEGORY_ID,
        "amount_minor": 45_000,
        "paid_via": "cash",
        "paid_at": datetime(2026, 8, 22, 10, 0, tzinfo=UTC),
        "vendor_name": "Local Roasters",
        "invoice_no": "INV-2201",
        "note": "Weekly coffee bean restock",
        **overrides,
    }
    return ExpenseCreate(**fields)


class _CreateSession:
    """session.get(Branch|ExpenseCategory, id) + add()/flush(), matching
    _validate_expense_references' two session.get lookups."""

    def __init__(self, *, branch, category) -> None:
        self._branch = branch
        self._category = category
        self.added: list = []
        self.flushes = 0

    async def get(self, model, key):
        if model is Branch:
            return self._branch
        if model is ExpenseCategory:
            return self._category
        raise AssertionError(f"unexpected get({model}, {key})")

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


def _branch() -> SimpleNamespace:
    return SimpleNamespace(id=BRANCH_ID, company_id=COMPANY_ID, deleted_at=None)


def _category() -> SimpleNamespace:
    return SimpleNamespace(id=CATEGORY_ID, company_id=COMPANY_ID)


@pytest.mark.asyncio
async def test_create_expense_requires_idempotency_key() -> None:
    session = _CreateSession(branch=_branch(), category=_category())
    bare_request = SimpleNamespace(state=SimpleNamespace())

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await create_expense(_payload(), session, bare_request, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_expense_persists_correct_fields(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)
    monkeypatch.setattr(finance_router, "store_response", store)

    session = _CreateSession(branch=_branch(), category=_category())
    result = await create_expense(_payload(), session, _request(), _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    stored = session.added[0]
    assert stored.company_id == COMPANY_ID
    assert stored.branch_id == BRANCH_ID
    assert stored.amount_minor == 45_000
    assert stored.paid_via == "cash"
    assert stored.vendor_name == "Local Roasters"

    assert result.id == stored.id
    assert result.amount_minor == 45_000
    assert result.vendor_name == "Local Roasters"


@pytest.mark.asyncio
async def test_create_expense_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = ExpenseRead(
        id=uuid4(),
        branch_id=BRANCH_ID,
        category_id=CATEGORY_ID,
        supplier_id=None,
        amount_minor=45_000,
        paid_via="cash",
        paid_at=datetime(2026, 8, 22, 10, 0, tzinfo=UTC),
        vendor_name="Local Roasters",
        invoice_no="INV-2201",
        note="Weekly coffee bean restock",
    )

    async def replay(*_args, **_kwargs):
        return {"status_code": 201, "body": existing.model_dump(mode="json")}

    monkeypatch.setattr(finance_router, "check_or_reserve", replay)

    class _NoMutationSession:
        def __getattr__(self, name):
            raise AssertionError(f"Replay attempted database mutation via {name}")

    response = await create_expense(
        _payload(), _NoMutationSession(), _request(), _tenant(),
    )
    assert response == existing


@pytest.mark.asyncio
async def test_create_expense_second_call_with_a_fresh_key_creates_a_second_row(
    monkeypatch,
) -> None:
    """Sanity check that idempotency doesn't over-suppress: two genuinely
    different submissions (different keys, as a real second purchase would
    be) both reach the database, they are not conflated into one."""
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)
    monkeypatch.setattr(finance_router, "store_response", store)

    session = _CreateSession(branch=_branch(), category=_category())
    await create_expense(_payload(), session, _request(), _tenant())
    await create_expense(_payload(), session, _request(), _tenant())

    assert session.flushes == 2
    assert len(session.added) == 2
    assert session.added[0].id != session.added[1].id
