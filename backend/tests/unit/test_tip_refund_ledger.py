"""Regression test for the tip/refund ledger-misbooking fix.

A single Payment bundles bill + tip (see the TIPS_PAYABLE credit of the full
order.tip_minor at sale time in app/services/accounting/ledger.py's
build_operational_ledger). Before this fix, a refund's non-tax amount was
booked ENTIRELY to SALES_RETURNS, with TIPS_PAYABLE never debited anywhere —
so a full refund of a tipped order silently misbooked the tip as a sales
return instead of reversing the tip liability.

This test exercises the real build_operational_ledger refund block (not a
re-implementation of its math) against a fake, order-queued session, the same
pattern already used in tests/unit/test_manual_collections.py.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.services.accounting.ledger import build_operational_ledger
from app.services.reports.aggregator import PnLReport, RevenueBreakdown, TaxBreakdown

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")


class _Result:
    def __init__(self, *, scalar=None, rows: list | None = None) -> None:
        self.scalar = scalar
        self.rows = rows or []

    def scalar_one_or_none(self):
        return self.scalar

    def all(self):
        return self.rows

    def scalars(self):
        return self


class _QueuedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)


@pytest.mark.asyncio
async def test_full_refund_of_tipped_order_debits_tips_payable_not_sales_returns() -> None:
    # subtotal 1_000 + CGST 90 + SGST 90 + a 100 tip folded on at payment
    # time = order.total_minor 1_280.
    order_id = uuid4()
    order = SimpleNamespace(
        id=order_id,
        invoice_no="INV-0001",
        subtotal_minor=1_000,
        cgst_minor=90,
        sgst_minor=90,
        igst_minor=0,
        cess_minor=0,
        tip_minor=100,
        total_minor=1_280,
    )
    refund = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        amount_minor=1_280,  # full refund, including the tip
        created_at=datetime(2026, 7, 20, tzinfo=UTC),
        settlement_method="cash",
        mode="original",
    )
    session = _QueuedSession(
        [
            _Result(rows=[]),  # order payments
            _Result(scalar="Asia/Kolkata"),  # company_timezone
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # membership payments
            _Result(rows=[]),  # membership refund settlements
            _Result(rows=[]),  # orders (sale-side lines — not under test here)
            _Result(rows=[]),  # stock movements
            _Result(rows=[(refund, order)]),  # refunds
            _Result(rows=[]),  # tip payouts
            _Result(rows=[]),  # expenses
            _Result(rows=[]),  # capital entries
            _Result(rows=[]),  # assets (depreciation)
            _Result(rows=[]),  # approved posted journals
        ]
    )

    lines = await build_operational_ledger(
        session,
        company_id=COMPANY_ID,
        end_exclusive=datetime(2026, 8, 1, tzinfo=UTC),
    )

    assert not session.results  # every queued query was consumed exactly once

    refund_lines = [line for line in lines if line.ref_type == "refund"]
    assert refund_lines
    assert all(line.ref_id == refund.id for line in refund_lines)

    by_code = {line.account_code: line for line in refund_lines}
    assert by_code["2400"].debit_minor == 100  # TIPS_PAYABLE — the tip, debited
    assert by_code["4090"].debit_minor == 1_000  # SALES_RETURNS — non-tip, non-tax only
    assert by_code["2110"].debit_minor == 90  # CGST_PAYABLE
    assert by_code["2120"].debit_minor == 90  # SGST_PAYABLE

    total_debit = sum(line.debit_minor for line in refund_lines)
    total_credit = sum(line.credit_minor for line in refund_lines)
    assert total_debit == total_credit == 1_280


@pytest.mark.asyncio
async def test_partial_refund_below_taxable_total_does_not_touch_tip() -> None:
    """A partial refund smaller than subtotal+tax shouldn't claw back any tip
    yet — mirrors how ledger.py treats the tip as sitting "on top of" the
    taxable total (same reasoning already applied to the GST reversal).
    """
    order_id = uuid4()
    order = SimpleNamespace(
        id=order_id,
        invoice_no="INV-0002",
        subtotal_minor=1_000,
        cgst_minor=90,
        sgst_minor=90,
        igst_minor=0,
        cess_minor=0,
        tip_minor=100,
        total_minor=1_280,
    )
    refund = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        amount_minor=500,  # well within the 1_180 taxable total
        created_at=datetime(2026, 7, 20, tzinfo=UTC),
        settlement_method="cash",
        mode="original",
    )
    session = _QueuedSession(
        [
            _Result(rows=[]),
            _Result(scalar="Asia/Kolkata"),
            _Result(rows=[]),
            _Result(rows=[]),  # membership payments
            _Result(rows=[]),  # membership refund settlements
            _Result(rows=[]),
            _Result(rows=[]),
            _Result(rows=[(refund, order)]),
            _Result(rows=[]),
            _Result(rows=[]),
            _Result(rows=[]),
            _Result(rows=[]),
            _Result(rows=[]),
        ]
    )

    lines = await build_operational_ledger(
        session,
        company_id=COMPANY_ID,
        end_exclusive=datetime(2026, 8, 1, tzinfo=UTC),
    )

    refund_lines = [line for line in lines if line.ref_type == "refund"]
    by_code = {line.account_code: line for line in refund_lines}
    assert "2400" not in by_code  # TIPS_PAYABLE untouched

    total_debit = sum(line.debit_minor for line in refund_lines)
    total_credit = sum(line.credit_minor for line in refund_lines)
    assert total_debit == total_credit == 500


def test_net_revenue_minor_no_longer_double_penalizes_tip_refund() -> None:
    """Mirror-image bug in aggregator.py: refunds_issued_minor sums the raw
    Refund.amount_minor (which includes any tip portion), but
    gross_revenue_minor never included tip in the first place (see
    RevenueBreakdown — it's built purely from line/category sums). Subtracting
    the full refund from revenue therefore double-penalized revenue by the
    tip amount. refunded_tips_minor is tracked precisely so that only the
    non-tip portion of a refund reduces revenue.
    """
    report = object.__new__(PnLReport)
    object.__setattr__(report, "revenue", RevenueBreakdown(food_minor=1_000))
    object.__setattr__(report, "tax_collected", TaxBreakdown())
    object.__setattr__(report, "refunds_issued_minor", 1_100)
    object.__setattr__(report, "refunded_tips_minor", 100)

    # Full refund of the $1_000 sale plus its $100 tip: revenue should land
    # back at exactly 0, not -100 (which is what double-counting the tip
    # would give).
    assert report.gross_revenue_minor == 1_000
    assert report.net_revenue_minor == 0

    # refunds_issued_minor itself must stay the full cash amount refunded —
    # it still drives the "Less: refunds" UI row and
    # net_payments_received_minor, both of which are real cash-flow figures
    # that must include the tip.
    assert report.refunds_issued_minor == 1_100
