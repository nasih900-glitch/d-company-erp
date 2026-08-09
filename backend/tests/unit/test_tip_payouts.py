"""Database-free tests for tip payouts — the only write path that clears
TIPS_PAYABLE back out to staff (see ledger.py's TIPS_PAYABLE credit on
tipped-order payment and debit on refund).

Same pattern as tests/unit/test_manual_collections.py (idempotent write +
one-way void, both database-free) and tests/unit/test_assets.py (RBAC
exercised through the real Depends() object, multi-tenant scoping).
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from sqlalchemy.orm.attributes import set_committed_value

import app.api.v1.finance.router as finance_router
from app.api.v1.finance.router import (
    TipPayoutCreate,
    TipPayoutRead,
    TipPayoutVoid,
    create_tip_payout,
    list_tip_payouts,
    void_tip_payout,
)
from app.core.errors import BusinessRuleError, ForbiddenError, NotFoundError
from app.core.permissions import ROLE_PERMISSIONS
from app.core.tenant import TenantContext
from app.core.timezone import local_date_bounds_utc
from app.models import Branch, TipPayout
from app.models.finance import _guard_tip_payout_update
from app.services.accounting.accounts import BANK, CASH, TIPS_PAYABLE
from app.services.accounting.ledger import build_operational_ledger
from app.services.audit.recorder import TRACKED

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
OTHER_COMPANY_ID = UUID("99999999-9999-9999-9999-999999999999")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
OTHER_BRANCH_ID = UUID("33333333-3333-3333-3333-333333333333")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


def _tenant(
    *, company_id: UUID = COMPANY_ID, branch_id: UUID | None = None,
    roles: tuple[str, ...] = ("owner",),
) -> TenantContext:
    return TenantContext(
        user_id=USER_ID, company_id=company_id, branch_id=branch_id,
        terminal_id=None, roles=roles,
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="tip-payout-2026-08-09-v1",
            idempotency_request_hash="request-hash",
        )
    )


def _payout(
    *,
    method: str = "cash",
    amount_minor: int = 50_000,
    paid_at: datetime = datetime(2026, 8, 9, 20, 0, tzinfo=UTC),
    voided: bool = False,
) -> TipPayout:
    return TipPayout(
        id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        amount_minor=amount_minor,
        method=method,
        paid_at=paid_at,
        note="Split among staff on shift",
        idempotency_key=f"tip-payout:{uuid4()}",
        created_by=USER_ID,
        created_at=datetime(2026, 8, 9, 21, 0, tzinfo=UTC),
        voided_at=datetime(2026, 8, 9, 22, 0, tzinfo=UTC) if voided else None,
        voided_by=USER_ID if voided else None,
        void_reason="Duplicate entry" if voided else None,
    )


def _branch(*, company_id: UUID = COMPANY_ID, deleted: bool = False) -> SimpleNamespace:
    return SimpleNamespace(
        id=BRANCH_ID, company_id=company_id,
        deleted_at=datetime(2026, 1, 1, tzinfo=UTC) if deleted else None,
    )


class _Result:
    def __init__(self, *, scalar=None, rows: list | None = None) -> None:
        self.scalar = scalar
        self.rows = rows or []

    def scalar_one_or_none(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


class _QueuedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.statements: list = []
        self.added: list = []
        self.flushes = 0

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


def _create_payload(**overrides) -> TipPayoutCreate:
    fields = {
        "branch_id": BRANCH_ID,
        "amount_minor": 50_000,
        "method": "cash",
        "paid_at": datetime(2026, 8, 9, 20, 0, tzinfo=UTC),
        "note": "Split among staff on shift",
        **overrides,
    }
    return TipPayoutCreate(**fields)


# ============================================================================
# Model shape / immutability
# ============================================================================
def test_schema_enforces_amount_note_and_idempotency_provenance() -> None:
    table = TipPayout.__table__
    constraint_names = {constraint.name for constraint in table.constraints}
    assert {
        "ck_tip_payout_method",
        "ck_tip_payout_positive_amount",
        "ck_tip_payout_note_present",
        "ck_tip_payout_idempotency_key_present",
        "ck_tip_payout_void_state",
        "uq_tip_payout_company_idempotency",
    } <= constraint_names
    assert TipPayout in TRACKED


def test_financial_and_provenance_fields_are_immutable_but_first_void_is_allowed() -> None:
    row = _payout(amount_minor=50_000)
    immutable_fields = (
        "company_id", "branch_id", "amount_minor", "method", "paid_at",
        "note", "idempotency_key", "created_by", "created_at",
    )
    for field in immutable_fields:
        set_committed_value(row, field, getattr(row, field))
    row.amount_minor = 60_000
    with pytest.raises(ValueError, match="amount_minor"):
        _guard_tip_payout_update(None, None, row)

    set_committed_value(row, "amount_minor", 50_000)
    set_committed_value(row, "voided_at", None)
    set_committed_value(row, "voided_by", None)
    set_committed_value(row, "void_reason", None)
    row.voided_at = datetime(2026, 8, 9, 22, 0, tzinfo=UTC)
    row.voided_by = USER_ID
    row.void_reason = "Duplicate entry"
    _guard_tip_payout_update(None, None, row)

    for field in ("voided_at", "voided_by", "void_reason"):
        set_committed_value(row, field, getattr(row, field))
    row.void_reason = "Changed my mind"
    with pytest.raises(ValueError, match="cannot be changed"):
        _guard_tip_payout_update(None, None, row)


# ============================================================================
# Create — success + validation
# ============================================================================
@pytest.mark.asyncio
async def test_create_tip_payout_persists_correct_fields(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    async def store(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)
    monkeypatch.setattr(finance_router, "store_response", store)

    branch = _branch()

    class _CreateSession(_QueuedSession):
        async def get(self, model, key):
            from app.models import User
            if model is User:
                return SimpleNamespace(id=key, name="Owner")
            raise AssertionError(f"unexpected get({model}, {key})")

        def add(self, entity) -> None:
            super().add(entity)
            # Mimic the server_default=func.now() a real Postgres insert
            # would populate — this double never actually hits the DB.
            if isinstance(entity, TipPayout) and entity.created_at is None:
                entity.created_at = datetime(2026, 8, 9, 21, 0, tzinfo=UTC)

    session = _CreateSession([_Result(scalar=branch)])
    payload = _create_payload()

    result = await create_tip_payout(payload, session, _request(), _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    stored = session.added[0]
    assert isinstance(stored, TipPayout)
    assert stored.company_id == COMPANY_ID
    assert stored.branch_id == BRANCH_ID
    assert stored.amount_minor == 50_000
    assert stored.method == "cash"
    assert stored.note == "Split among staff on shift"
    assert stored.created_by == USER_ID
    assert stored.idempotency_key == "tip-payout-2026-08-09-v1"

    assert result.amount_minor == 50_000
    assert result.method == "cash"
    assert result.created_by_name == "Owner"
    assert result.is_voided is False


@pytest.mark.asyncio
async def test_create_tip_payout_requires_idempotency_key() -> None:
    session = _QueuedSession([])
    payload = _create_payload()
    bare_request = SimpleNamespace(state=SimpleNamespace())

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await create_tip_payout(payload, session, bare_request, _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_tip_payout_rejects_blank_note(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)

    session = _QueuedSession([_Result(scalar=_branch())])

    # DTO-level validation already enforces min_length=3; construct one that
    # only whitespace-pads down to under 3 chars to hit the router's own
    # stripped-length check.
    payload = TipPayoutCreate.model_construct(
        branch_id=BRANCH_ID,
        amount_minor=50_000,
        method="cash",
        paid_at=datetime(2026, 8, 9, 20, 0, tzinfo=UTC),
        note="  a  ",
    )

    with pytest.raises(BusinessRuleError, match="at least 3 characters"):
        await create_tip_payout(payload, session, _request(), _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = TipPayoutRead(
        id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        amount_minor=50_000,
        method="cash",
        paid_at=datetime(2026, 8, 9, 20, 0, tzinfo=UTC),
        note="Split among staff on shift",
        idempotency_key="tip-payout-2026-08-09-v1",
        created_by=USER_ID,
        created_by_name="Owner",
        created_at=datetime(2026, 8, 9, 21, 0, tzinfo=UTC),
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

    response = await create_tip_payout(
        _create_payload(), _NoMutationSession(), _request(), _tenant(),
    )
    assert response == existing


# ============================================================================
# Multi-tenant scoping
# ============================================================================
@pytest.mark.asyncio
async def test_create_rejects_a_branch_belonging_to_another_company(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)

    # create_tip_payout's branch lookup filters by company_id in the SQL
    # itself (mirrors ManualCollection's pattern), so a real DB simply
    # returns nothing for a branch that belongs to a different company —
    # the fake session mirrors that by returning None here.
    session = _QueuedSession([_Result(scalar=None)])
    with pytest.raises(NotFoundError, match="branch not found"):
        await create_tip_payout(_create_payload(), session, _request(), _tenant())
    assert session.added == []


@pytest.mark.asyncio
async def test_create_rejects_a_branch_outside_a_branch_scoped_tenant(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)

    class _NoLookupSession(_QueuedSession):
        async def execute(self, statement):
            raise AssertionError("in_branch() should short-circuit before any DB lookup")

    session = _NoLookupSession([])
    payload = _create_payload(branch_id=OTHER_BRANCH_ID)

    with pytest.raises(NotFoundError, match="branch not found"):
        await create_tip_payout(payload, session, _request(), _tenant(branch_id=BRANCH_ID))
    assert session.added == []


@pytest.mark.asyncio
async def test_list_tip_payouts_scopes_query_to_the_caller_company() -> None:
    session = _QueuedSession([_Result(rows=[])])
    result = await list_tip_payouts(session, _tenant(company_id=COMPANY_ID), limit=200)

    assert result == []
    assert len(session.statements) == 1
    sql = str(session.statements[0])
    values = list(session.statements[0].compile().params.values())
    assert "tip_payouts.company_id" in sql
    assert COMPANY_ID in values
    assert OTHER_COMPANY_ID not in values


# ============================================================================
# Void — one-way, idempotent retry safe
# ============================================================================
@pytest.mark.asyncio
async def test_void_is_one_way_and_exact_retry_is_safe() -> None:
    row = _payout(amount_minor=50_000)

    class _VoidSession:
        def __init__(self) -> None:
            self.flushes = 0

        async def execute(self, _statement):
            return _Result(scalar=row)

        async def get(self, model, key):
            from app.models import User
            assert model is User
            return SimpleNamespace(id=key, name="Owner")

        async def flush(self):
            self.flushes += 1

    session = _VoidSession()
    first = await void_tip_payout(
        row.id, TipPayoutVoid(reason="Duplicate payout"), session, _tenant(),
    )
    assert first.is_voided is True
    assert first.voided_by == USER_ID
    assert session.flushes == 1

    replay = await void_tip_payout(
        row.id, TipPayoutVoid(reason="Duplicate payout"), session, _tenant(),
    )
    assert replay.voided_at == first.voided_at
    assert session.flushes == 1

    with pytest.raises(BusinessRuleError, match="different reason"):
        await void_tip_payout(
            row.id, TipPayoutVoid(reason="A different correction"), session, _tenant(),
        )


@pytest.mark.asyncio
async def test_void_rejects_a_payout_belonging_to_another_branch() -> None:
    row = _payout()

    class _VoidSession:
        async def execute(self, _statement):
            return _Result(scalar=row)

    session = _VoidSession()
    with pytest.raises(NotFoundError, match="tip payout not found"):
        await void_tip_payout(
            row.id,
            TipPayoutVoid(reason="Duplicate payout"),
            session,
            _tenant(branch_id=OTHER_BRANCH_ID),
        )


# ============================================================================
# Ledger — debits TIPS_PAYABLE, credits the chosen method account, balanced
# ============================================================================
@pytest.mark.asyncio
async def test_ledger_debits_tips_payable_and_credits_chosen_method_account() -> None:
    payout = _payout(method="bank", amount_minor=75_000)
    session = _QueuedSession(
        [
            _Result(rows=[]),  # order payments
            _Result(scalar="Asia/Kolkata"),  # company_timezone
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # orders
            _Result(rows=[]),  # stock movements
            _Result(rows=[]),  # refunds
            _Result(rows=[payout]),  # tip payouts
            _Result(rows=[]),  # expenses
            _Result(rows=[]),  # capital entries
            _Result(rows=[]),  # direct event tickets
            _Result(rows=[]),  # assets (depreciation)
            _Result(rows=[]),  # approved posted journals
        ]
    )
    start_at, end_exclusive = local_date_bounds_utc(
        payout.paid_at.date(), payout.paid_at.date(), "Asia/Kolkata"
    )

    lines = await build_operational_ledger(
        session, company_id=COMPANY_ID, start_at=start_at, end_exclusive=end_exclusive,
    )

    assert not session.results
    payout_lines = [line for line in lines if line.ref_type == "tip_payout"]
    assert len(payout_lines) == 2
    assert all(line.ref_id == payout.id for line in payout_lines)

    by_code = {line.account_code: line for line in payout_lines}
    assert by_code[TIPS_PAYABLE.code].debit_minor == 75_000
    assert by_code[TIPS_PAYABLE.code].credit_minor == 0
    assert by_code[BANK.code].credit_minor == 75_000
    assert by_code[BANK.code].debit_minor == 0

    total_debit = sum(line.debit_minor for line in payout_lines)
    total_credit = sum(line.credit_minor for line in payout_lines)
    assert total_debit == total_credit == 75_000


@pytest.mark.asyncio
async def test_ledger_maps_cash_method_to_cash_account() -> None:
    payout = _payout(method="cash", amount_minor=10_000)
    session = _QueuedSession(
        [
            _Result(rows=[]), _Result(scalar="Asia/Kolkata"), _Result(rows=[]),
            _Result(rows=[]), _Result(rows=[]), _Result(rows=[]),
            _Result(rows=[payout]),  # tip payouts
            _Result(rows=[]), _Result(rows=[]), _Result(rows=[]),
            _Result(rows=[]), _Result(rows=[]),
        ]
    )
    start_at, end_exclusive = local_date_bounds_utc(
        payout.paid_at.date(), payout.paid_at.date(), "Asia/Kolkata"
    )

    lines = await build_operational_ledger(
        session, company_id=COMPANY_ID, start_at=start_at, end_exclusive=end_exclusive,
    )
    payout_lines = [line for line in lines if line.ref_type == "tip_payout"]
    by_code = {line.account_code: line for line in payout_lines}
    assert by_code[CASH.code].credit_minor == 10_000


@pytest.mark.asyncio
async def test_ledger_excludes_voided_tip_payouts() -> None:
    """The SQL WHERE clause already filters voided_at IS NULL, but this
    proves the query itself is scoped that way (same as ManualCollection's
    equivalent test) rather than relying on filtering in Python."""
    session = _QueuedSession(
        [
            _Result(rows=[]), _Result(scalar="Asia/Kolkata"), _Result(rows=[]),
            _Result(rows=[]), _Result(rows=[]), _Result(rows=[]),
            _Result(rows=[]),  # tip payouts — the SQL excludes the voided row itself
            _Result(rows=[]), _Result(rows=[]), _Result(rows=[]),
            _Result(rows=[]), _Result(rows=[]),
        ]
    )

    lines = await build_operational_ledger(
        session, company_id=COMPANY_ID, end_exclusive=datetime(2026, 8, 10, tzinfo=UTC),
    )
    assert [line for line in lines if line.ref_type == "tip_payout"] == []

    tip_payout_sql = str(session.statements[6])
    assert "tip_payouts.voided_at IS NULL" in tip_payout_sql
    assert "tip_payouts.company_id" in tip_payout_sql


# ============================================================================
# RBAC
# ============================================================================
@pytest.mark.asyncio
async def test_create_tip_payout_endpoint_requires_finance_write_permission() -> None:
    """Exercised through the real Depends() object rather than re-deriving
    the permission string, same pattern as test_assets.py."""
    import inspect

    from fastapi.params import Depends as DependsMarker

    sig = inspect.signature(create_tip_payout)
    marker = sig.parameters["tenant"].default
    assert isinstance(marker, DependsMarker)

    class _PermissionSession:
        async def execute(self, statement):
            return _Result(scalar=None)  # no Access Control module override

    session = _PermissionSession()

    auditor = _tenant(roles=("auditor",))
    assert "finance.write" not in ROLE_PERMISSIONS["auditor"]
    with pytest.raises(ForbiddenError, match="finance.write"):
        await marker.dependency(session, auditor)

    owner = _tenant(roles=("owner",))
    assert "finance.write" in ROLE_PERMISSIONS["owner"]
    result = await marker.dependency(session, owner)
    assert result is owner


@pytest.mark.asyncio
async def test_void_tip_payout_endpoint_requires_finance_write_permission() -> None:
    import inspect

    from fastapi.params import Depends as DependsMarker

    sig = inspect.signature(void_tip_payout)
    marker = sig.parameters["tenant"].default
    assert isinstance(marker, DependsMarker)

    class _PermissionSession:
        async def execute(self, statement):
            return _Result(scalar=None)

    auditor = _tenant(roles=("auditor",))
    with pytest.raises(ForbiddenError, match="finance.write"):
        await marker.dependency(_PermissionSession(), auditor)
