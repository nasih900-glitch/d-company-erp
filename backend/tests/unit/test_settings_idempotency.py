"""Database-free tests for POST /settings/branches and POST
/settings/terminals idempotency.

create_terminal has no unique constraint a duplicate retry could collide
against at all. create_branch has a company+name uniqueness guard, but that
only turns a duplicate retry into a confusing 409 rather than replaying the
original success. Both get the same mandatory-idempotency treatment as
every other create endpoint in this rebuild (see test_events_idempotency.py,
which this mirrors).

update_company/update_branch (PATCH, "set fields to X") and
delete_branch/delete_terminal are deliberately NOT covered here — they are
naturally safe to retry (same value or already-gone), the same reasoning
established for Events' check_in_ticket and Memberships' cancel_subscription.
"""

from __future__ import annotations

from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

import app.api.v1.settings.router as settings_router
from app.api.v1.settings.router import (
    BranchCreate,
    BranchRead,
    BranchUpdate,
    TerminalCreate,
    TerminalRead,
    create_branch,
    create_terminal,
    list_terminals,
    update_branch,
)
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.tenant import TenantContext

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID, company_id=COMPANY_ID, branch_id=None,
        terminal_id=None, roles=("owner",),
    )


def _branch_tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID, company_id=COMPANY_ID, branch_id=BRANCH_ID,
        terminal_id=None, roles=("cashier",),
    )


def _system_admin_tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=None,
        roles=("super_owner",),
        protected_access=True,
        audit_access=True,
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="settings-write-2026-08-23-v1",
            idempotency_request_hash="request-hash",
        )
    )


def _branch() -> SimpleNamespace:
    return SimpleNamespace(id=BRANCH_ID, company_id=COMPANY_ID, deleted_at=None)


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
        self.statements = []
        self.added: list = []
        self.flushes = 0

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        self.statements.append(statement)
        return self.results.pop(0)

    async def get(self, model, key):
        raise AssertionError(f"unexpected get({model}, {key})")

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


# ============================================================================
# create_branch
# ============================================================================
@pytest.mark.asyncio
async def test_create_branch_requires_idempotency_key() -> None:
    session = _QueuedSession([])
    bare_request = SimpleNamespace(state=SimpleNamespace())
    payload = BranchCreate(name="New Branch")

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await create_branch(payload, bare_request, session, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_branch_persists_and_stores_response(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    stored: dict = {}

    async def store(*_args, **kwargs):
        stored.update(kwargs)

    monkeypatch.setattr(settings_router, "check_or_reserve", reserve)
    monkeypatch.setattr(settings_router, "store_response", store)

    session = _QueuedSession(
        [
            _Result(scalar=COMPANY_ID),
            _Result(scalar=None),  # no existing branch by name
            _Result(scalar=None),  # no existing fiscal series
        ]
    )
    payload = BranchCreate(name="Kochi Branch", invoice_series_code="KC")

    result = await create_branch(payload, _request(), session, _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    assert result.name == "Kochi Branch"
    assert result.invoice_series_code == "KC"
    assert stored["status_code"] == 201
    assert stored["body"]["name"] == "Kochi Branch"


@pytest.mark.asyncio
async def test_create_branch_compatibility_accepts_only_an_exact_two_char_code(
    monkeypatch,
) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    monkeypatch.setattr(settings_router, "check_or_reserve", reserve)
    monkeypatch.setattr(settings_router, "store_response", store)
    session = _QueuedSession(
        [_Result(scalar=COMPANY_ID), _Result(scalar=None), _Result(scalar=None)]
    )

    result = await create_branch(
        BranchCreate(name="Kiosk", code="k1"),
        _request(),
        session,
        _tenant(),
    )

    assert result.invoice_series_code == "K1"

    with pytest.raises(BusinessRuleError, match="invoice_series_code is required"):
        await create_branch(
            BranchCreate(name="Unsafe truncation", code="MAIN"),
            _request(),
            _QueuedSession([]),
            _tenant(),
        )


def test_explicit_invoice_series_is_normalized_and_strict() -> None:
    assert (
        BranchCreate(name="Kiosk", invoice_series_code=" k1 ").invoice_series_code
        == "K1"
    )
    with pytest.raises(ValueError, match="exactly two"):
        BranchCreate(name="Kiosk", invoice_series_code="MAIN")


@pytest.mark.asyncio
async def test_create_branch_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = BranchRead(
        id=BRANCH_ID, name="Kochi Branch", code=None,
        invoice_series_code="KC", address=None,
        timezone="Asia/Kolkata", opens_at=None, closes_at=None,
        state_code="32", fssai_license_no=None, trade_license_no=None,
        branch_gstin=None,
    )

    async def replay(*_args, **_kwargs):
        return {"status_code": 201, "body": existing.model_dump(mode="json")}

    monkeypatch.setattr(settings_router, "check_or_reserve", replay)

    class _NoMutationSession:
        def __getattr__(self, name):
            raise AssertionError(f"Replay attempted database mutation via {name}")

    payload = BranchCreate(name="Kochi Branch")
    result = await create_branch(payload, _request(), _NoMutationSession(), _tenant())
    assert result == existing


@pytest.mark.asyncio
async def test_update_branch_rejects_series_change_after_counter_history() -> None:
    branch = SimpleNamespace(
        id=BRANCH_ID,
        company_id=COMPANY_ID,
        deleted_at=None,
        invoice_series_code="MN",
    )
    session = _QueuedSession(
        [
            _Result(scalar=COMPANY_ID),
            _Result(scalar=branch),
            _Result(scalar=True),
        ]
    )

    with pytest.raises(ConflictError, match="cannot be changed"):
        await update_branch(
            BRANCH_ID,
            BranchUpdate(invoice_series_code="K1"),
            session,
            _tenant(),
        )
    assert session.flushes == 0
    history_sql = str(session.statements[2]).lower()
    assert "in_invoice_counters" in history_sql
    assert "orders" in history_sql
    assert "refunds" in history_sql
    assert "membership_payments" in history_sql
    assert "membership_refund_settlements" in history_sql


# ============================================================================
# create_terminal
# ============================================================================
@pytest.mark.asyncio
async def test_create_terminal_requires_idempotency_key() -> None:
    session = _QueuedSession([])
    bare_request = SimpleNamespace(state=SimpleNamespace())
    payload = TerminalCreate(branch_id=BRANCH_ID, name="POS-T2")

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await create_terminal(payload, bare_request, session, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_terminal_persists_and_stores_response(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    stored: dict = {}

    async def store(*_args, **kwargs):
        stored.update(kwargs)

    monkeypatch.setattr(settings_router, "check_or_reserve", reserve)
    monkeypatch.setattr(settings_router, "store_response", store)

    class _CreateSession(_QueuedSession):
        async def get(self, model, key):
            assert key == BRANCH_ID
            return _branch()

    session = _CreateSession([])
    payload = TerminalCreate(branch_id=BRANCH_ID, name="POS-T2")

    result = await create_terminal(payload, _request(), session, _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    assert result.name == "POS-T2"
    assert stored["status_code"] == 201
    assert stored["body"]["name"] == "POS-T2"


@pytest.mark.asyncio
async def test_create_terminal_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = TerminalRead(
        id=uuid4(), branch_id=BRANCH_ID, name="POS-T2", device_id=None, last_seen_at=None,
    )

    async def replay(*_args, **_kwargs):
        return {"status_code": 201, "body": existing.model_dump(mode="json")}

    monkeypatch.setattr(settings_router, "check_or_reserve", replay)

    class _NoMutationSession:
        def __getattr__(self, name):
            raise AssertionError(f"Replay attempted database mutation via {name}")

    payload = TerminalCreate(branch_id=BRANCH_ID, name="POS-T2")
    result = await create_terminal(payload, _request(), _NoMutationSession(), _tenant())
    assert result == existing


@pytest.mark.asyncio
async def test_list_terminals_is_scoped_to_active_tenant_branch() -> None:
    class _RowsResult:
        def scalars(self):
            return self

        def all(self):
            return []

    class _TerminalListSession:
        statement = None

        async def execute(self, statement):
            self.statement = statement
            return _RowsResult()

    session = _TerminalListSession()
    assert await list_terminals(session, _branch_tenant()) == []
    compiled = session.statement.compile()
    assert COMPANY_ID in compiled.params.values()
    assert BRANCH_ID in compiled.params.values()
    assert "deleted_at IS NULL" in str(session.statement)


@pytest.mark.asyncio
async def test_list_terminals_hides_other_branch_from_branch_scoped_user() -> None:
    class _NoQuerySession:
        async def execute(self, statement):
            raise AssertionError(f"cross-branch request reached SQL: {statement}")

    with pytest.raises(NotFoundError, match="branch not found"):
        await list_terminals(_NoQuerySession(), _branch_tenant(), uuid4())


@pytest.mark.asyncio
async def test_system_admin_can_inspect_an_explicit_company_branch() -> None:
    other_branch_id = uuid4()

    class _RowsResult:
        def scalars(self):
            return self

        def all(self):
            return []

    class _TerminalListSession:
        statement = None

        async def execute(self, statement):
            self.statement = statement
            return _RowsResult()

    session = _TerminalListSession()
    assert await list_terminals(session, _system_admin_tenant(), other_branch_id) == []
    compiled = session.statement.compile()
    assert COMPANY_ID in compiled.params.values()
    assert other_branch_id in compiled.params.values()
    assert "deleted_at IS NULL" in str(session.statement)
