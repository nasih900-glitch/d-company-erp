"""Unit contract separating event operations from financial evidence."""

from __future__ import annotations

from datetime import date
from types import SimpleNamespace
from uuid import UUID

import pytest

from app.services.reports import ReportsAggregator

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")


class _Result:
    def __init__(self, *, scalar=None, rows=None, one=None) -> None:
        self.scalar = scalar
        self.rows = rows or []
        self.one_value = one

    def scalar_one(self):
        return self.scalar

    def scalar_one_or_none(self):
        return self.scalar

    def all(self):
        return self.rows

    def one(self):
        return self.one_value

    def scalars(self):
        return self


class _Session:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.statements = []

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"unexpected SQL: {statement}"
        return self.results.pop(0)


@pytest.mark.asyncio
async def test_only_paid_pos_event_lines_contribute_event_revenue() -> None:
    session = _Session(
        [
            _Result(scalar="Asia/Kolkata"),
            _Result(
                one=SimpleNamespace(
                    n=1,
                    gross=11_800,
                    tips=0,
                    cgst=900,
                    sgst=900,
                    igst=0,
                    cess=0,
                )
            ),
            _Result(scalar=0),  # delivery
            _Result(scalar=0),  # discounts and points
            _Result(one=SimpleNamespace(income=0, expense=0)),  # invoice rounding
            _Result(rows=[SimpleNamespace(type="event", amount=11_800)]),
            _Result(scalar=1),  # one operational EventTicket
            _Result(rows=[]),  # COGS
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # membership payments
            _Result(rows=[SimpleNamespace(method="cash", amount=11_800)]),
            _Result(rows=[]),  # refunds
            _Result(scalar=0),  # membership refund settlements
            _Result(rows=[]),  # assets
            _Result(rows=[]),  # expenses
        ]
    )

    report = await ReportsAggregator(session).aggregate_daily(
        company_id=COMPANY_ID,
        d=date(2026, 8, 25),
    )

    assert not session.results
    assert report.tickets_count == 1
    assert report.revenue.event_tickets_minor == 11_800
    assert report.gross_revenue_minor == 11_800
    assert report.tax_collected.total_minor == 1_800
    ticket_count_sql = str(session.statements[6])
    assert "count(event_tickets.id)" in ticket_count_sql.lower()
    assert "price_paid_minor" not in ticket_count_sql
