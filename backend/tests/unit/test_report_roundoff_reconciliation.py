"""Invoice round-off reconciliation across P&L, payments, and the ledger."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.api.v1.reports.router import _to_dto
from app.services.accounting.accounts import (
    ROUNDING_EXPENSE,
    ROUNDING_INCOME,
    SALES_RETURNS,
    SALES_REVENUE,
)
from app.services.accounting.ledger import build_operational_ledger
from app.services.reports import ReportsAggregator

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
NOW = datetime(2026, 8, 25, 12, tzinfo=UTC)


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


def _order(*, subtotal: int, round_off: int, invoice: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=uuid4(),
        invoice_no=invoice,
        invoice_issued_at=NOW,
        closed_at=NOW,
        opened_at=NOW,
        subtotal_minor=subtotal,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        tip_minor=0,
        round_off_minor=round_off,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        total_minor=subtotal + round_off,
    )


@pytest.mark.asyncio
async def test_report_reconciles_mixed_rounding_and_partial_refund_to_payments() -> None:
    """Reproduce the Android E2E accounting blocker exactly.

    Item/category lines total Rs836.70, issued invoices and payments total
    Rs836.00 after mixed round-ups/round-downs, a separately receipted Gold
    membership contributes Rs1,999.00, and Rs10.00 of POS money is refunded.
    Gross receipts must therefore be Rs2,835.00 and management-basis net
    revenue/profit Rs2,825.00 (while AOV remains a POS-only Rs91.78). This is
    the exact fixture that previously hid the membership payment entirely.
    """
    refunded_order_id = uuid4()
    refund = SimpleNamespace(
        id=uuid4(),
        order_id=refunded_order_id,
        amount_minor=1_000,
        settlement_method="upi",
        created_at=NOW,
    )
    refunded_order = SimpleNamespace(
        id=refunded_order_id,
        invoice_issued_at=NOW,
        closed_at=NOW,
        total_minor=18_000,
        tip_minor=0,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
    )
    session = _Session(
        [
            _Result(scalar="Asia/Kolkata"),
            _Result(
                one=SimpleNamespace(
                    n=9,
                    gross=83_600,
                    tips=0,
                    cgst=0,
                    sgst=0,
                    igst=0,
                    cess=0,
                )
            ),
            _Result(scalar=0),  # delivery base
            _Result(scalar=0),  # discounts / points
            _Result(one=SimpleNamespace(income=66, expense=136)),
            _Result(rows=[SimpleNamespace(type="gaming", amount=83_670)]),
            _Result(scalar=0),  # event ticket count
            _Result(rows=[]),  # COGS
            _Result(rows=[]),  # manual collections
            _Result(rows=[SimpleNamespace(method="upi", amount=199_900)]),
            _Result(rows=[SimpleNamespace(method="upi", amount=83_600)]),
            _Result(rows=[(refund, refunded_order)]),
            _Result(rows=[]),  # prior refunds for the same order
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
    assert report.revenue.gaming_minor == 83_670
    assert report.revenue.rounding_income_minor == 66
    assert report.revenue.rounding_expense_minor == 136
    assert report.revenue.round_off_minor == -70
    assert report.revenue.memberships_minor == 199_900
    assert report.gross_revenue_minor == 283_500
    assert report.refunds_issued_minor == 1_000
    assert report.net_revenue_minor == 282_500
    assert report.avg_ticket_minor == 9_178
    assert report.net_profit_minor == 282_500
    assert report.payments_received.upi_minor == 283_500
    assert report.payments_received.total_minor == 283_500
    assert report.net_payments_received_minor == 282_500
    assert report.net_revenue_minor == report.net_payments_received_minor

    dto = _to_dto(report)
    assert dto.revenue.rounding_income_minor == 66
    assert dto.revenue.rounding_expense_minor == 136
    assert dto.revenue.round_off_minor == -70
    assert dto.revenue.memberships_minor == 199_900
    assert dto.revenue.total_minor == 283_500
    assert dto.tips_collected_minor == 0
    assert dto.refunds_issued_minor == 1_000
    assert dto.settled_refunds_issued_minor == 1_000
    assert dto.refunded_tips_minor == 0


@pytest.mark.asyncio
async def test_prior_invoice_refund_changes_net_revenue_but_not_current_aov() -> None:
    """Returns are transaction-period P&L, while AOV remains a sale cohort.

    A refund issued today for yesterday's receipt must reduce today's net
    revenue/payment movement without being divided into today's unrelated
    newly issued receipt.
    """
    old_order_id = uuid4()
    refund = SimpleNamespace(
        id=uuid4(),
        order_id=old_order_id,
        amount_minor=5_000,
        settlement_method="cash",
        created_at=NOW,
    )
    old_order = SimpleNamespace(
        id=old_order_id,
        invoice_issued_at=NOW - timedelta(days=1),
        closed_at=NOW - timedelta(days=1),
        total_minor=5_000,
        tip_minor=0,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
    )
    session = _Session(
        [
            _Result(scalar="Asia/Kolkata"),
            _Result(
                one=SimpleNamespace(
                    n=1,
                    gross=10_000,
                    tips=0,
                    cgst=0,
                    sgst=0,
                    igst=0,
                    cess=0,
                )
            ),
            _Result(scalar=0),
            _Result(scalar=0),
            _Result(one=SimpleNamespace(income=0, expense=0)),
            _Result(rows=[SimpleNamespace(type="food", amount=10_000)]),
            _Result(scalar=0),
            _Result(rows=[]),
            _Result(rows=[]),
            _Result(rows=[]),  # membership payments
            _Result(rows=[SimpleNamespace(method="cash", amount=10_000)]),
            _Result(rows=[(refund, old_order)]),
            _Result(rows=[]),
            _Result(scalar=0),  # membership refund settlements
            _Result(rows=[]),
            _Result(rows=[]),
        ]
    )

    report = await ReportsAggregator(session).aggregate_daily(
        company_id=COMPANY_ID,
        d=date(2026, 8, 25),
    )

    assert report.orders_count == 1
    assert report.gross_revenue_minor == 10_000
    assert report.refunds_issued_minor == 5_000
    assert report.net_revenue_minor == 5_000
    assert report.net_payments_received_minor == 5_000
    assert report.avg_ticket_minor == 10_000


@pytest.mark.asyncio
async def test_ledger_keeps_both_rounding_accounts_and_settles_net_refund() -> None:
    round_down = _order(subtotal=10_034, round_off=-34, invoice="INV-DOWN")
    round_up = _order(subtotal=10_067, round_off=33, invoice="INV-UP")
    payments = [
        SimpleNamespace(
            id=uuid4(),
            order_id=round_down.id,
            method="cash",
            amount_minor=round_down.total_minor,
            paid_at=NOW,
        ),
        SimpleNamespace(
            id=uuid4(),
            order_id=round_up.id,
            method="upi",
            amount_minor=round_up.total_minor,
            paid_at=NOW,
        ),
    ]
    refund = SimpleNamespace(
        id=uuid4(),
        order_id=round_down.id,
        amount_minor=1_000,
        settlement_method="cash",
        mode="original",
        created_at=NOW,
    )
    session = _Session(
        [
            _Result(
                rows=[
                    (payments[0], round_down.invoice_no),
                    (payments[1], round_up.invoice_no),
                ]
            ),
            _Result(scalar="Asia/Kolkata"),
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # membership payments
            _Result(rows=[]),  # membership refund settlements
            _Result(rows=[round_down, round_up]),
            _Result(rows=[]),  # stock movements
            _Result(rows=[(refund, round_down)]),
            _Result(rows=[]),  # tip payouts
            _Result(rows=[]),  # expenses
            _Result(rows=[]),  # capital entries
            _Result(rows=[]),  # assets / depreciation
            _Result(rows=[]),  # posted journal entries
        ]
    )

    lines = await build_operational_ledger(
        session,
        company_id=COMPANY_ID,
        start_at=datetime(2026, 8, 25, tzinfo=UTC),
        end_exclusive=datetime(2026, 8, 26, tzinfo=UTC),
    )

    assert not session.results
    assert sum(line.debit_minor for line in lines) == sum(line.credit_minor for line in lines)
    assert (
        sum(line.credit_minor for line in lines if line.account_code == SALES_REVENUE.code)
        == 20_101
    )
    assert (
        sum(line.credit_minor for line in lines if line.account_code == ROUNDING_INCOME.code) == 33
    )
    assert (
        sum(line.debit_minor for line in lines if line.account_code == ROUNDING_EXPENSE.code) == 34
    )
    assert (
        sum(line.debit_minor for line in lines if line.account_code == SALES_RETURNS.code) == 1_000
    )

    net_payment_movement = sum(payment.amount_minor for payment in payments) - refund.amount_minor
    net_income_statement = 20_101 + 33 - 34 - 1_000
    assert net_payment_movement == 19_100
    assert net_income_statement == net_payment_movement
