"""Fail-closed runtime checks for role and branch assignment scope."""

from __future__ import annotations

from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.api.v1.auth.router import _roles_and_branch
from app.core.errors import AuthError


class _Result:
    def __init__(self, *, rows=None, scalar=None) -> None:
        self._rows = [] if rows is None else rows
        self._scalar = scalar

    def all(self):
        return self._rows

    def scalar_one_or_none(self):
        return self._scalar


class _Session:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)

    async def execute(self, statement):
        assert self.results, f"unexpected statement: {statement}"
        return self.results.pop(0)


def _row(*, user_company, role_company=None, branch_id=None, branch_company=None, deleted=None):
    return SimpleNamespace(
        code="cashier",
        role_company_id=role_company or user_company,
        branch_id=branch_id,
        branch_company_id=branch_company,
        branch_deleted_at=deleted,
    )


@pytest.mark.asyncio
async def test_auth_rejects_cross_tenant_role_assignment() -> None:
    company_id = uuid4()
    user = SimpleNamespace(id=uuid4(), company_id=company_id)
    session = _Session(
        _Result(rows=[_row(user_company=company_id, role_company=uuid4())])
    )

    with pytest.raises(AuthError, match="role assignment is invalid"):
        await _roles_and_branch(session, user)


@pytest.mark.asyncio
@pytest.mark.parametrize("deleted", [None, object()])
async def test_auth_rejects_foreign_or_deleted_assigned_branch(deleted) -> None:
    company_id = uuid4()
    branch_id = uuid4()
    user = SimpleNamespace(id=uuid4(), company_id=company_id)
    session = _Session(
        _Result(
            rows=[
                _row(
                    user_company=company_id,
                    branch_id=branch_id,
                    branch_company=(uuid4() if deleted is None else company_id),
                    deleted=deleted,
                )
            ]
        )
    )

    with pytest.raises(AuthError, match="branch assignment is invalid"):
        await _roles_and_branch(session, user)


@pytest.mark.asyncio
async def test_auth_rejects_roles_scoped_to_multiple_branches() -> None:
    company_id = uuid4()
    user = SimpleNamespace(id=uuid4(), company_id=company_id)
    session = _Session(
        _Result(
            rows=[
                _row(
                    user_company=company_id,
                    branch_id=uuid4(),
                    branch_company=company_id,
                ),
                _row(
                    user_company=company_id,
                    branch_id=uuid4(),
                    branch_company=company_id,
                ),
            ]
        )
    )

    with pytest.raises(AuthError, match="multiple branches"):
        await _roles_and_branch(session, user)


@pytest.mark.asyncio
async def test_auth_accepts_multiple_roles_scoped_to_the_same_branch() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    user = SimpleNamespace(id=uuid4(), company_id=company_id)
    cashier = _row(
        user_company=company_id,
        branch_id=branch_id,
        branch_company=company_id,
    )
    manager = _row(
        user_company=company_id,
        branch_id=branch_id,
        branch_company=company_id,
    )
    manager.code = "manager"
    session = _Session(_Result(rows=[cashier, manager]))

    roles, selected_branch = await _roles_and_branch(session, user)

    assert roles == ["cashier", "manager"]
    assert selected_branch == branch_id
