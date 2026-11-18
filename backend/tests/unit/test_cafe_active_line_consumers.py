"""Query-shape regressions for active-vs-cancelled cafe bill consumers."""

from __future__ import annotations

from datetime import date
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.api.v1.insights import router as insights_router
from app.core.tenant import TenantContext


class _Result:
    def __init__(self, *, scalar=None, row=None, rows=None) -> None:
        self.scalar = scalar
        self.row = row
        self.rows = [] if rows is None else rows

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar

    def one(self):
        return self.row

    def all(self):
        return self.rows


class _Session:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)
        self.statements = []

    async def scalars(self, statement):
        from app.models import PosReceiptCorrection

        assert [column["entity"] for column in statement.column_descriptions] == [PosReceiptCorrection]
        return []  # No immutable no-value corrections in these legacy fixtures.

    async def execute(self, statement):
        from app.models import PosReceiptCorrection

        columns = statement.column_descriptions
        if [column["entity"] for column in columns] == [PosReceiptCorrection, PosReceiptCorrection]:
            assert [str(column["expr"]) for column in columns] == [
                "count(pos_receipt_corrections.id)",
                "coalesce(sum(pos_receipt_corrections.amount_minor), :coalesce_1)",
            ]
            assert "JOIN orders ON orders.id = pos_receipt_corrections.order_id" in str(statement)
            return _Result(row=(0, 0))
        self.statements.append(statement)
        return self.results.pop(0)


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
    )


@pytest.mark.asyncio
async def test_top_items_query_excludes_cancelled_order_lines() -> None:
    tenant = _tenant()
    session = _Session(
        _Result(scalar="Asia/Kolkata"),
        _Result(rows=[]),
    )

    assert await insights_router.top_items(
        session,
        tenant,
        from_date=date(2026, 8, 1),
        to_date=date(2026, 8, 31),
    ) == []

    sql = str(session.statements[1])
    assert "order_lines.voided_at IS NULL" in sql


@pytest.mark.asyncio
async def test_growth_order_totals_do_not_cross_join_order_lines() -> None:
    session = _Session(
        _Result(row=SimpleNamespace(rev=0, tips=0, n=0)),
        _Result(scalar=0),
        _Result(scalar=0),
        _Result(scalar=0),
        _Result(scalar=0),
    )

    await insights_router._period_stats(
        session,
        uuid4(),
        date(2026, 8, 1),
        date(2026, 8, 31),
        "Asia/Kolkata",
    )

    order_total_sql = str(session.statements[0])
    assert "FROM orders" in order_total_sql
    assert "order_lines" not in order_total_sql
