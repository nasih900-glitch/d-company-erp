"""Regression tests for protected internal-role administration."""

from __future__ import annotations

from uuid import uuid4

import pytest

from app.api.v1.staff import router as staff_router
from app.core.errors import BusinessRuleError, ForbiddenError
from app.core.tenant import TenantContext
from app.models import Role, User


class _Result:
    def __init__(self, *, rows=None, scalar=None) -> None:
        self._rows = [] if rows is None else rows
        self._scalar = scalar

    def scalars(self):
        return self

    def all(self):
        return self._rows

    def scalar_one_or_none(self):
        return self._scalar


class _Session:
    def __init__(self, *results: _Result, user: User | None = None) -> None:
        self.results = list(results)
        self.user = user
        self.statements = []
        self.added = []
        self.flushes = 0

    async def execute(self, statement):
        self.statements.append(statement)
        if self.user is not None and statement.column_descriptions[0].get("entity") is User:
            return _Result(scalar=self.user)
        assert self.results, f"unexpected statement: {statement}"
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


def _tenant(*, audit_access: bool = False) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("manager",),
        protected_access=audit_access,
        audit_access=audit_access,
    )


def _user(company_id, *, user_id=None) -> User:
    return User(
        id=user_id or uuid4(),
        company_id=company_id,
        email="target@example.com",
        password_hash="x",
        name="Target User",
        status="active",
        auth_version=0,
    )


@pytest.mark.asyncio
async def test_ordinary_staff_manager_cannot_assign_co_owner() -> None:
    tenant = _tenant()
    session = _Session(_Result(rows=["cashier"]))

    with pytest.raises(ForbiddenError, match="protected owner"):
        await staff_router._set_role(session, tenant, uuid4(), "co_owner")

    assert session.added == []


@pytest.mark.asyncio
async def test_ordinary_staff_manager_cannot_assign_public_owner() -> None:
    tenant = _tenant()
    session = _Session()

    with pytest.raises(ForbiddenError, match="protected owner"):
        await staff_router._set_role(session, tenant, uuid4(), "owner")

    assert session.statements == []
    assert session.added == []


@pytest.mark.asyncio
async def test_protected_owner_can_assign_co_owner() -> None:
    tenant = _tenant(audit_access=True)
    role = Role(
        id=uuid4(),
        company_id=tenant.company_id,
        code="co_owner",
        name="Co-owner",
        permissions=[],
    )
    session = _Session(
        _Result(rows=["cashier"]),
        _Result(rows=[]),
        _Result(scalar=role),
        _Result(),
    )

    await staff_router._set_role(session, tenant, uuid4(), "co_owner")

    assert len(session.added) == 1
    assert session.added[0].role_id == role.id
    assert session.added[0].granted_by == tenant.user_id


@pytest.mark.asyncio
async def test_protected_owner_can_assign_public_owner() -> None:
    tenant = _tenant(audit_access=True)
    role = Role(
        id=uuid4(),
        company_id=tenant.company_id,
        code="owner",
        name="Owner",
        permissions=[],
    )
    session = _Session(
        _Result(rows=["cashier"]),
        _Result(rows=[]),
        _Result(scalar=role),
        _Result(),
    )

    await staff_router._set_role(session, tenant, uuid4(), "owner")

    assert len(session.added) == 1
    assert session.added[0].role_id == role.id


@pytest.mark.asyncio
async def test_role_replacement_preserves_single_existing_branch() -> None:
    tenant = _tenant(audit_access=True)
    branch_id = uuid4()
    role = Role(
        id=uuid4(),
        company_id=tenant.company_id,
        code="manager",
        name="Manager",
        permissions=[],
    )
    session = _Session(
        _Result(rows=["cashier"]),
        _Result(rows=[branch_id]),
        _Result(scalar=role),
        _Result(),
    )

    await staff_router._set_role(session, tenant, uuid4(), "manager")

    assert len(session.added) == 1
    assert session.added[0].branch_id == branch_id


@pytest.mark.asyncio
async def test_role_replacement_fails_closed_for_multiple_existing_branches() -> None:
    tenant = _tenant(audit_access=True)
    session = _Session(
        _Result(rows=["cashier", "staff"]),
        _Result(rows=[uuid4(), uuid4()]),
    )

    with pytest.raises(BusinessRuleError, match="multiple branches"):
        await staff_router._set_role(session, tenant, uuid4(), "manager")
    assert session.added == []


@pytest.mark.asyncio
async def test_ordinary_manager_cannot_change_or_suspend_existing_co_owner() -> None:
    tenant = _tenant()
    target = _user(tenant.company_id)
    session = _Session(_Result(rows=["co_owner"]), user=target)

    with pytest.raises(ForbiddenError, match="protected owner"):
        await staff_router.update_user(
            target.id,
            staff_router.UserUpdate(status="suspended"),
            session,
            tenant,
        )

    assert target.status == "active"
    assert target.auth_version == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("role_code", ["owner", "co_owner"])
async def test_ordinary_manager_cannot_demote_or_suspend_any_owner(
    role_code: str,
) -> None:
    tenant = _tenant()
    target = _user(tenant.company_id)
    session = _Session(_Result(rows=[role_code]), user=target)

    with pytest.raises(ForbiddenError, match="protected owner"):
        await staff_router.update_user(
            target.id,
            staff_router.UserUpdate(role_code="cashier", status="suspended"),
            session,
            tenant,
        )

    assert target.status == "active"
    assert target.auth_version == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("role_code", ["owner", "co_owner"])
async def test_ordinary_manager_cannot_delete_any_owner(role_code: str) -> None:
    tenant = _tenant()
    target = _user(tenant.company_id)
    session = _Session(_Result(rows=[role_code]), user=target)

    with pytest.raises(ForbiddenError, match="protected owner"):
        await staff_router.delete_user(target.id, session, tenant)

    assert target.deleted_at is None
    assert target.status == "active"
    assert target.auth_version == 0


@pytest.mark.asyncio
async def test_role_catalog_hides_internal_owner_roles_from_ordinary_callers() -> None:
    tenant = _tenant()
    session = _Session(_Result(rows=[]))

    assert await staff_router.list_roles(session, tenant) == []

    params = list(session.statements[0].compile().params.values())
    hidden = next(value for value in params if isinstance(value, list))
    assert set(hidden) == {"super_owner", "co_owner", "owner"}


@pytest.mark.asyncio
async def test_role_catalog_allows_protected_owner_to_see_owner_tiers() -> None:
    tenant = _tenant(audit_access=True)
    session = _Session(_Result(rows=[]))

    assert await staff_router.list_roles(session, tenant) == []

    params = list(session.statements[0].compile().params.values())
    hidden = next(value for value in params if isinstance(value, list))
    assert set(hidden) == {"super_owner"}
