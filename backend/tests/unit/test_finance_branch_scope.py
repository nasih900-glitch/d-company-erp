"""Branch-bound finance readers and writers must not cross their assignment."""

from __future__ import annotations

from types import SimpleNamespace
from typing import TYPE_CHECKING, Any, cast
from uuid import UUID, uuid4

import pytest

import app.api.v1.finance.router as finance_router
from app.core.errors import NotFoundError
from app.core.tenant import TenantContext

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")


class _Result:
    def __init__(self, *, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = [] if rows is None else rows

    def scalar_one_or_none(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


class _ExecuteSession:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)
        self.statements: list[Any] = []

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)


class _GetSession:
    def __init__(self, row) -> None:
        self.row = row
        self.flush_count = 0

    async def get(self, _model, _row_id):
        return self.row

    async def flush(self) -> None:
        self.flush_count += 1


def _tenant(*, branch_id=BRANCH_ID) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=branch_id,
        terminal_id=None,
        roles=("manager",),
    )


def _statement_values(statement) -> list[object]:
    return list(statement.compile().params.values())


@pytest.mark.asyncio
async def test_finance_reference_expense_and_asset_reads_use_assigned_branch() -> None:
    tenant = _tenant()

    branch_session = _ExecuteSession(_Result(rows=[]))
    await finance_router.list_finance_branches(cast("AsyncSession", branch_session), tenant)
    branch_stmt = branch_session.statements[0]
    assert "branches.company_id" in str(branch_stmt)
    assert "branches.deleted_at IS NULL" in str(branch_stmt)
    assert "branches.id" in str(branch_stmt)
    assert tenant.company_id in _statement_values(branch_stmt)
    assert tenant.branch_id in _statement_values(branch_stmt)

    # list_expenses first resolves the company timezone, then reads expenses.
    expense_session = _ExecuteSession(
        _Result(scalar="Asia/Kolkata"),
        _Result(rows=[]),
    )
    await finance_router.list_expenses(cast("AsyncSession", expense_session), tenant)
    expense_stmt = expense_session.statements[1]
    assert "expenses.company_id" in str(expense_stmt)
    assert "expenses.branch_id" in str(expense_stmt)
    assert tenant.company_id in _statement_values(expense_stmt)
    assert tenant.branch_id in _statement_values(expense_stmt)

    asset_session = _ExecuteSession(_Result(rows=[]))
    await finance_router.list_assets(cast("AsyncSession", asset_session), tenant)
    asset_stmt = asset_session.statements[0]
    assert "assets.company_id" in str(asset_stmt)
    assert "assets.branch_id" in str(asset_stmt)
    assert tenant.company_id in _statement_values(asset_stmt)
    assert tenant.branch_id in _statement_values(asset_stmt)


@pytest.mark.asyncio
async def test_expense_update_and_delete_hide_another_branch() -> None:
    tenant = _tenant()
    foreign = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=uuid4(),
        deleted_at=None,
    )

    update_session = _GetSession(foreign)
    with pytest.raises(NotFoundError, match="expense not found"):
        await finance_router.update_expense(
            foreign.id,
            finance_router.ExpenseUpdate(note="should not land"),
            cast("AsyncSession", update_session),
            tenant,
        )
    assert update_session.flush_count == 0
    assert not hasattr(foreign, "note")

    delete_session = _GetSession(foreign)
    with pytest.raises(NotFoundError, match="expense not found"):
        await finance_router.delete_expense(
            foreign.id,
            cast("AsyncSession", delete_session),
            tenant,
        )
    assert delete_session.flush_count == 0
    assert foreign.deleted_at is None


@pytest.mark.asyncio
async def test_company_wide_finance_reader_keeps_all_branch_history() -> None:
    tenant = _tenant(branch_id=None)
    asset_session = _ExecuteSession(_Result(rows=[]))

    await finance_router.list_assets(cast("AsyncSession", asset_session), tenant)

    values = _statement_values(asset_session.statements[0])
    assert tenant.company_id in values
    assert BRANCH_ID not in values
