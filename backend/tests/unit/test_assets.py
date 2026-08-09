"""Database-free tests for the fixed-asset register (create + list).

Companion to tests/unit/test_depreciation.py (which covers the pure
straight-line math) — this file covers the write-path around it: creation,
RBAC scope, and multi-tenant scoping, all without a DB (fake session +
directly-constructed TenantContext, same pattern as
tests/unit/test_manual_collections.py).
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.api.v1.finance.router import AssetCreate, create_asset, list_assets
from app.core.errors import BusinessRuleError, ForbiddenError, NotFoundError
from app.core.permissions import ROLE_PERMISSIONS
from app.core.tenant import TenantContext
from app.models import Asset, Branch
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


class _CreateSession:
    """Minimal session double: session.get(Branch, id) + add()/flush()."""

    def __init__(self, branch: SimpleNamespace | None) -> None:
        self._branch = branch
        self.added: list = []
        self.flushes = 0

    async def get(self, model, key):
        if model is Branch:
            return self._branch
        raise AssertionError(f"unexpected get({model}, {key})")

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


def _asset_payload(**overrides) -> AssetCreate:
    fields = {
        "branch_id": BRANCH_ID,
        "name": "Double-door commercial fridge",
        "type": "kitchen_equipment",
        "purchase_minor": 12_000_00,
        "purchase_date": datetime.now(UTC),
        "useful_life_months": 96,
        "salvage_minor": 0,
        **overrides,
    }
    return AssetCreate(**fields)


# ============================================================================
# Successful creation
# ============================================================================
@pytest.mark.asyncio
async def test_create_asset_success_persists_correct_fields_and_computes_book_value() -> None:
    session = _CreateSession(_branch())
    payload = _asset_payload()

    result = await create_asset(payload, session, _tenant())

    assert session.flushes == 1
    assert len(session.added) == 1
    stored = session.added[0]
    assert isinstance(stored, Asset)
    assert stored.company_id == COMPANY_ID
    assert stored.branch_id == BRANCH_ID
    assert stored.name == "Double-door commercial fridge"
    assert stored.type == "kitchen_equipment"
    assert stored.purchase_minor == 12_000_00
    assert stored.salvage_minor == 0
    # Every asset created through this endpoint is explicitly straight_line —
    # there is no method picker yet (see create_asset's comment).
    assert stored.depreciation_method == "straight_line"

    # purchase_date == now, so essentially zero elapsed depreciation.
    assert result.id == stored.id
    assert result.branch_id == BRANCH_ID
    assert result.accumulated_depreciation_minor == 0
    assert result.book_value_minor == 12_000_00


@pytest.mark.asyncio
async def test_create_asset_persists_optional_notes() -> None:
    session = _CreateSession(_branch())
    payload = _asset_payload(notes="Purchased for the new kitchen build-out")

    result = await create_asset(payload, session, _tenant())

    assert session.added[0].notes == "Purchased for the new kitchen build-out"
    assert result.notes == "Purchased for the new kitchen build-out"


# ============================================================================
# Business-rule validation
# ============================================================================
@pytest.mark.asyncio
async def test_create_asset_rejects_salvage_value_exceeding_purchase_cost() -> None:
    session = _CreateSession(_branch())
    payload = _asset_payload(purchase_minor=10_000_00, salvage_minor=10_000_01)

    with pytest.raises(BusinessRuleError, match="salvage value cannot exceed purchase value"):
        await create_asset(payload, session, _tenant())

    assert session.added == []


# ============================================================================
# Multi-tenant scoping
# ============================================================================
@pytest.mark.asyncio
async def test_create_asset_rejects_a_branch_belonging_to_another_company() -> None:
    session = _CreateSession(_branch(company_id=OTHER_COMPANY_ID))
    payload = _asset_payload()

    with pytest.raises(NotFoundError, match="branch not found"):
        await create_asset(payload, session, _tenant(company_id=COMPANY_ID))

    assert session.added == []


@pytest.mark.asyncio
async def test_create_asset_rejects_a_soft_deleted_branch() -> None:
    session = _CreateSession(_branch(deleted=True))
    payload = _asset_payload()

    with pytest.raises(NotFoundError, match="branch not found"):
        await create_asset(payload, session, _tenant())

    assert session.added == []


@pytest.mark.asyncio
async def test_create_asset_rejects_a_branch_outside_a_branch_scoped_tenant() -> None:
    """A tenant pinned to one branch (branch_id set, not company-wide) cannot
    create an asset in a different branch — checked via tenant.in_branch()
    before the branch is even looked up (no session.get call expected)."""

    class _NoLookupSession(_CreateSession):
        async def get(self, model, key):
            raise AssertionError("in_branch() should short-circuit before any DB lookup")

    session = _NoLookupSession(None)
    payload = _asset_payload(branch_id=OTHER_BRANCH_ID)

    with pytest.raises(NotFoundError, match="branch not found"):
        await create_asset(payload, session, _tenant(branch_id=BRANCH_ID))

    assert session.added == []


@pytest.mark.asyncio
async def test_list_assets_scopes_query_to_the_caller_company_and_excludes_soft_deleted() -> None:
    class _ListSession:
        def __init__(self) -> None:
            self.statements: list = []

        async def execute(self, statement):
            self.statements.append(statement)
            return _Result(rows=[])

    session = _ListSession()
    result = await list_assets(session, _tenant(company_id=COMPANY_ID))

    assert result == []
    assert len(session.statements) == 1
    sql = str(session.statements[0])
    values = list(session.statements[0].compile().params.values())
    assert "assets.company_id" in sql
    assert "assets.deleted_at IS NULL" in sql
    assert COMPANY_ID in values
    assert OTHER_COMPANY_ID not in values


# ============================================================================
# RBAC
# ============================================================================
@pytest.mark.asyncio
async def test_create_asset_endpoint_requires_finance_assets_write_permission() -> None:
    """The dependency actually wired onto create_asset is requires(
    "finance.assets.write") — the dedicated scope, not the generic
    finance.write. Exercised through the real Depends() object rather than
    re-deriving the permission string, so this fails if the endpoint
    regresses to a broader/narrower scope."""
    import inspect

    from fastapi.params import Depends as DependsMarker

    sig = inspect.signature(create_asset)
    marker = sig.parameters["tenant"].default
    assert isinstance(marker, DependsMarker)

    class _PermissionSession:
        async def execute(self, statement):
            return _Result(scalar=None)  # no Access Control module override

    session = _PermissionSession()

    # auditor: read-only, has finance.read but neither finance.write nor
    # finance.assets.write.
    auditor = _tenant(roles=("auditor",))
    assert "finance.assets.write" not in ROLE_PERMISSIONS["auditor"]
    with pytest.raises(ForbiddenError, match="finance.assets.write"):
        await marker.dependency(session, auditor)

    # owner: standard access, has the scope.
    owner = _tenant(roles=("owner",))
    assert "finance.assets.write" in ROLE_PERMISSIONS["owner"]
    result = await marker.dependency(session, owner)
    assert result is owner


@pytest.mark.asyncio
async def test_create_asset_scope_is_specific_not_satisfied_by_generic_finance_write_alone(
    monkeypatch,
) -> None:
    """Distinguishes the dedicated finance.assets.write scope from the
    generic finance.write one: a role granted only finance.write (no
    dedicated assets scope) must still be rejected by create_asset's
    dependency — proving the endpoint checks the specific permission rather
    than merely reusing the expense-record scope."""
    import inspect

    from fastapi.params import Depends as DependsMarker

    monkeypatch.setitem(ROLE_PERMISSIONS, "expenses_only_role", {"finance.write"})

    sig = inspect.signature(create_asset)
    marker = sig.parameters["tenant"].default
    assert isinstance(marker, DependsMarker)

    class _PermissionSession:
        async def execute(self, statement):
            return _Result(scalar=None)

    tenant = _tenant(roles=("expenses_only_role",))
    with pytest.raises(ForbiddenError, match="finance.assets.write"):
        await marker.dependency(_PermissionSession(), tenant)


def test_asset_model_is_registered_for_audit_logging() -> None:
    assert Asset in TRACKED
