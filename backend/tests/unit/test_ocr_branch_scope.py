"""Least-privilege OCR branch references stay inside the tenant boundary."""

from __future__ import annotations

from types import SimpleNamespace
from typing import TYPE_CHECKING, Any, cast
from uuid import UUID, uuid4

import pytest

import app.api.v1.ocr.router as ocr_router
from app.core.tenant import TenantContext

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")


class _Result:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows

    def scalars(self) -> _Result:
        return self

    def all(self) -> list[Any]:
        return self.rows


class _Session:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.statements: list[Any] = []

    async def execute(self, statement: Any) -> _Result:
        self.statements.append(statement)
        return _Result(self.rows)


def _tenant(*, branch_id: UUID | None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=branch_id,
        terminal_id=None,
        roles=("manager",),
    )


def _statement_values(statement: Any) -> list[object]:
    return list(statement.compile().params.values())


@pytest.mark.asyncio
async def test_branch_bound_ocr_operator_only_receives_assigned_branch() -> None:
    tenant = _tenant(branch_id=BRANCH_ID)
    branch = SimpleNamespace(id=BRANCH_ID, name="Main Shop", code="MAIN")
    session = _Session([branch])

    result = await ocr_router.list_ocr_branches(
        cast("AsyncSession", session),
        tenant,
    )

    assert result == [ocr_router.OcrBranchRead(id=BRANCH_ID, name="Main Shop", code="MAIN")]
    statement = session.statements[0]
    sql = str(statement)
    values = _statement_values(statement)
    assert "branches.company_id" in sql
    assert "branches.deleted_at IS NULL" in sql
    assert "branches.id" in sql
    assert COMPANY_ID in values
    assert BRANCH_ID in values


@pytest.mark.asyncio
async def test_company_wide_ocr_operator_receives_active_company_branches() -> None:
    tenant = _tenant(branch_id=None)
    session = _Session([])

    assert (
        await ocr_router.list_ocr_branches(
            cast("AsyncSession", session),
            tenant,
        )
        == []
    )

    statement = session.statements[0]
    values = _statement_values(statement)
    assert COMPANY_ID in values
    assert BRANCH_ID not in values
    assert "branches.deleted_at IS NULL" in str(statement)
