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
    TerminalUpdate,
    create_branch,
    create_terminal,
    delete_branch,
    list_terminals,
    update_branch,
    update_terminal,
)
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.tenant import TenantContext
from app.models import Terminal

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
            _Result(scalar=None),  # no existing active shop
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
        [
            _Result(scalar=COMPANY_ID),
            _Result(scalar=None),
            _Result(scalar=None),
            _Result(scalar=None),
        ]
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
async def test_create_branch_rejects_a_second_active_shop(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(settings_router, "check_or_reserve", reserve)
    session = _QueuedSession(
        [
            _Result(scalar=COMPANY_ID),
            _Result(scalar=BRANCH_ID),
        ]
    )

    with pytest.raises(BusinessRuleError, match="configured for one shop"):
        await create_branch(
            BranchCreate(name="Second Shop", invoice_series_code="S2"),
            _request(),
            session,
            _tenant(),
        )

    assert session.added == []
    assert session.flushes == 0


@pytest.mark.asyncio
async def test_delete_branch_rejects_the_only_active_shop() -> None:
    branch = _branch()
    session = _QueuedSession(
        [
            _Result(scalar=COMPANY_ID),
            _Result(scalar=branch),
            _Result(scalar=1),
        ]
    )

    with pytest.raises(BusinessRuleError, match="only shop cannot be deleted"):
        await delete_branch(BRANCH_ID, session, _tenant())

    assert branch.deleted_at is None
    assert session.flushes == 0


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
    payload = TerminalCreate(
        branch_id=BRANCH_ID,
        name="POS-T2",
        purpose="cafe_pos",
    )

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

    session = _QueuedSession([
        _Result(scalar=_branch()),
        _Result(scalar=None),  # no case-insensitive name conflict
    ])
    payload = TerminalCreate(
        branch_id=BRANCH_ID,
        name="POS-T2",
        purpose="cafe_pos",
    )

    result = await create_terminal(payload, _request(), session, _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    assert result.name == "POS-T2"
    assert result.purpose == "cafe_pos"
    assert session.added[0].purpose == "cafe_pos"
    assert stored["status_code"] == 201
    assert stored["body"]["name"] == "POS-T2"
    assert stored["body"]["purpose"] == "cafe_pos"


def test_terminal_purpose_is_explicit_strict_and_migration_safe_by_default() -> None:
    assert TerminalCreate(branch_id=BRANCH_ID, name="Legacy till").purpose == "hybrid"
    assert TerminalUpdate(purpose="gaming").purpose == "gaming"
    with pytest.raises(ValueError, match="hybrid"):
        TerminalCreate(
            branch_id=BRANCH_ID,
            name="Invalid till",
            purpose="pos",  # type: ignore[arg-type]
        )


@pytest.mark.asyncio
async def test_create_terminal_rejects_duplicate_name_in_the_same_branch(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(settings_router, "check_or_reserve", reserve)
    session = _QueuedSession([
        _Result(scalar=_branch()),
        _Result(scalar=uuid4()),
    ])

    with pytest.raises(ConflictError, match="already exists in this branch"):
        await create_terminal(
            TerminalCreate(branch_id=BRANCH_ID, name=" cafe pos "),
            _request(),
            session,
            _tenant(),
        )

    assert session.added == []


@pytest.mark.asyncio
async def test_update_terminal_renames_history_bearing_row_in_place() -> None:
    terminal_id = uuid4()
    terminal = Terminal(
        id=terminal_id,
        branch_id=BRANCH_ID,
        name="Main Terminal",
        purpose="hybrid",
        device_id="old-device",
    )
    session = _QueuedSession([
        _Result(scalar=BRANCH_ID),
        _Result(scalar=_branch()),
        _Result(scalar=terminal),
        _Result(scalar=None),  # no open or unresolved shift blocks purpose change
        _Result(scalar=None),  # no name conflict
        _Result(scalar=None),  # no device conflict
    ])

    result = await update_terminal(
        terminal_id,
        TerminalUpdate(
            name=" Cafe POS ",
            purpose="cafe_pos",
            device_id=" cafe-tablet ",
        ),
        session,
        _tenant(),
    )

    assert result.id == terminal_id
    assert result.branch_id == BRANCH_ID
    assert result.name == "Cafe POS"
    assert result.purpose == "cafe_pos"
    assert result.device_id == "cafe-tablet"
    assert terminal.name == "Cafe POS"
    assert terminal.purpose == "cafe_pos"
    assert session.flushes == 1
    assert session.added == []


@pytest.mark.asyncio
async def test_update_terminal_blocks_purpose_change_during_an_open_shift() -> None:
    terminal_id = uuid4()
    terminal = Terminal(
        id=terminal_id,
        branch_id=BRANCH_ID,
        name="Gaming Area",
        purpose="gaming",
        device_id="gaming-tablet",
    )
    session = _QueuedSession([
        _Result(scalar=BRANCH_ID),
        _Result(scalar=_branch()),
        _Result(scalar=terminal),
        _Result(scalar=uuid4()),
    ])

    with pytest.raises(BusinessRuleError, match="current shift"):
        await update_terminal(
            terminal_id,
            TerminalUpdate(purpose="cafe_pos"),
            session,
            _tenant(),
        )

    assert terminal.purpose == "gaming"
    assert session.flushes == 0


@pytest.mark.asyncio
async def test_update_terminal_archives_history_in_place_when_another_workspace_remains() -> None:
    terminal_id = uuid4()
    terminal = Terminal(
        id=terminal_id,
        branch_id=BRANCH_ID,
        name="Old Cafe POS",
        purpose="cafe_pos",
        is_active=True,
        device_id=None,
    )
    session = _QueuedSession([
        _Result(scalar=BRANCH_ID),
        _Result(scalar=_branch()),
        _Result(scalar=terminal),
        _Result(scalar=None),  # no unsettled shift blocks configuration change
        _Result(scalar=2),  # another active workspace remains
        _Result(scalar=None),  # no unsettled shift blocks archival
        _Result(scalar=None),  # no name conflict
    ])

    result = await update_terminal(
        terminal_id,
        TerminalUpdate(is_active=False),
        session,
        _tenant(),
    )

    assert result.is_active is False
    assert terminal.is_active is False
    assert session.flushes == 1


@pytest.mark.asyncio
async def test_update_terminal_refuses_to_archive_the_only_active_workspace() -> None:
    terminal_id = uuid4()
    terminal = Terminal(
        id=terminal_id,
        branch_id=BRANCH_ID,
        name="Main Workspace",
        purpose="hybrid",
        is_active=True,
        device_id=None,
    )
    session = _QueuedSession([
        _Result(scalar=BRANCH_ID),
        _Result(scalar=_branch()),
        _Result(scalar=terminal),
        _Result(scalar=None),
        _Result(scalar=1),
    ])

    with pytest.raises(BusinessRuleError, match="only active workspace"):
        await update_terminal(
            terminal_id,
            TerminalUpdate(is_active=False),
            session,
            _tenant(),
        )

    assert terminal.is_active is True
    assert session.flushes == 0


@pytest.mark.asyncio
async def test_update_terminal_explicit_null_clears_device_binding() -> None:
    terminal_id = uuid4()
    terminal = Terminal(
        id=terminal_id,
        branch_id=BRANCH_ID,
        name="Cafe POS",
        purpose="cafe_pos",
        device_id="old-device",
    )
    session = _QueuedSession([
        _Result(scalar=BRANCH_ID),
        _Result(scalar=_branch()),
        _Result(scalar=terminal),
        _Result(scalar=None),
    ])

    result = await update_terminal(
        terminal_id,
        TerminalUpdate(device_id=None),
        session,
        _tenant(),
    )

    assert result.device_id is None
    assert terminal.device_id is None


@pytest.mark.asyncio
async def test_update_terminal_hides_cross_tenant_target() -> None:
    session = _QueuedSession([_Result(scalar=None)])

    with pytest.raises(NotFoundError, match="terminal not found"):
        await update_terminal(
            uuid4(),
            TerminalUpdate(name="Cafe POS"),
            session,
            _tenant(),
        )

    assert session.flushes == 0


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
    assert "terminals.is_active IS true" in str(session.statement)


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
