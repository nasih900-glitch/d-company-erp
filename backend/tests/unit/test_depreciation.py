"""Straight-line depreciation math — pure functions, ledger wiring, P&L wiring.

Covers three layers:
  1. app/services/accounting/depreciation.py — pure math, no clock/DB access.
  2. app/services/accounting/ledger.py — balanced Depreciation Expense /
     Accumulated Depreciation lines derived from the Asset register.
  3. app/services/reports/aggregator.py — depreciation_minor subtracted from
     PnLReport.net_profit_minor, the exact number GET /finance/pnl,
     GET /finance/pnl/partners, and GET /finance/distributable all return
     (each is a thin wrapper over ReportsAggregator.aggregate()'s output —
     see app/api/v1/finance/router.py's profit_loss/partner_profit_split/
     distributable_profit).
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.services.accounting.accounts import ACCUMULATED_DEPRECIATION, DEPRECIATION_EXPENSE
from app.services.accounting.depreciation import (
    accumulated_depreciation_minor,
    asset_book_value_minor,
    asset_depreciation_expense_minor,
    book_value_minor,
    depreciation_expense_minor,
)
from app.services.accounting.ledger import build_operational_ledger
from app.services.reports.aggregator import ReportsAggregator

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")

# Round numbers chosen so straight-line fractions land on whole paise:
# depreciable base = 5,400.00 over 60 months = exactly 90.00/month.
PURCHASE_MINOR = 6_000_00
SALVAGE_MINOR = 600_00
USEFUL_LIFE_MONTHS = 60
DEPRECIABLE_BASE = PURCHASE_MINOR - SALVAGE_MINOR  # 5_400_00
MONTHLY_MINOR = DEPRECIABLE_BASE // USEFUL_LIFE_MONTHS  # 90_00
PURCHASE_DATE = datetime(2020, 1, 1, tzinfo=UTC)


def _asset(**overrides) -> SimpleNamespace:
    defaults = {
        "id": uuid4(),
        "name": "PS5 #1",
        "depreciation_method": "straight_line",
        "purchase_minor": PURCHASE_MINOR,
        "salvage_minor": SALVAGE_MINOR,
        "useful_life_months": USEFUL_LIFE_MONTHS,
        "purchase_date": PURCHASE_DATE,
        "company_id": COMPANY_ID,
        "deleted_at": None,
    }
    defaults.update(overrides)
    return SimpleNamespace(**defaults)


# ---------------------------------------------------------------------------
# Pure math: accumulated_depreciation_minor / book_value_minor
# ---------------------------------------------------------------------------
def test_accumulated_depreciation_is_zero_before_and_at_purchase() -> None:
    asset = _asset()
    assert asset_depreciation_expense_minor(asset, start_at=None, end_as_of=PURCHASE_DATE) == 0
    assert asset_book_value_minor(asset, PURCHASE_DATE) == PURCHASE_MINOR

    # Before the purchase date entirely (e.g. a mis-keyed as_of) — also 0,
    # never negative.
    before = accumulated_depreciation_minor(
        depreciation_method=asset.depreciation_method,
        purchase_minor=asset.purchase_minor,
        salvage_minor=asset.salvage_minor,
        useful_life_months=asset.useful_life_months,
        purchase_date=asset.purchase_date,
        as_of=datetime(2019, 6, 1, tzinfo=UTC),
    )
    assert before == 0


def test_accumulated_depreciation_six_months_in_is_six_whole_months() -> None:
    asset = _asset()
    as_of = datetime(2020, 7, 1, tzinfo=UTC)  # exactly 6 completed months
    assert asset_depreciation_expense_minor(asset, start_at=None, end_as_of=as_of) == (
        6 * MONTHLY_MINOR
    )
    assert asset_book_value_minor(asset, as_of) == PURCHASE_MINOR - 6 * MONTHLY_MINOR


def test_accumulated_depreciation_at_midlife_is_exactly_half_the_depreciable_base() -> None:
    """30 of 60 months — the whole-month elapsed count gives a clean, exactly
    testable fraction rather than an approximate one."""
    asset = _asset()
    midlife = datetime(2022, 7, 1, tzinfo=UTC)  # 30 months after 2020-01-01
    accumulated = asset_depreciation_expense_minor(asset, start_at=None, end_as_of=midlife)
    assert accumulated == DEPRECIABLE_BASE // 2
    assert asset_book_value_minor(asset, midlife) == PURCHASE_MINOR - DEPRECIABLE_BASE // 2


def test_accumulated_depreciation_floors_at_salvage_past_useful_life() -> None:
    asset = _asset()
    exactly_done = datetime(2025, 1, 1, tzinfo=UTC)  # exactly 60 months
    well_past = datetime(2030, 1, 1, tzinfo=UTC)  # 120 months, way past life

    assert asset_book_value_minor(asset, exactly_done) == SALVAGE_MINOR
    assert asset_book_value_minor(asset, well_past) == SALVAGE_MINOR
    # Never goes below salvage — no negative book value.
    assert asset_book_value_minor(asset, well_past) >= 0
    assert asset_depreciation_expense_minor(
        asset, start_at=None, end_as_of=well_past
    ) == DEPRECIABLE_BASE


def test_zero_salvage_and_zero_useful_life_never_divide_by_zero_or_go_negative() -> None:
    zero_life = _asset(useful_life_months=0)
    far_future = datetime(2026, 1, 1, tzinfo=UTC)
    assert (
        accumulated_depreciation_minor(
            depreciation_method="straight_line",
            purchase_minor=PURCHASE_MINOR,
            salvage_minor=SALVAGE_MINOR,
            useful_life_months=0,
            purchase_date=PURCHASE_DATE,
            as_of=far_future,
        )
        == 0
    )
    assert asset_book_value_minor(zero_life, far_future) == PURCHASE_MINOR

    full_salvage = _asset(salvage_minor=PURCHASE_MINOR)
    assert (
        asset_depreciation_expense_minor(
            full_salvage, start_at=None, end_as_of=datetime(2030, 1, 1, tzinfo=UTC)
        )
        == 0
    )
    assert asset_book_value_minor(full_salvage, datetime(2030, 1, 1, tzinfo=UTC)) == PURCHASE_MINOR


def test_unimplemented_depreciation_method_is_a_documented_noop_not_a_guess() -> None:
    """Only straight_line is implemented — any other value on
    Asset.depreciation_method (a plain String(20), not an enum) must not
    silently fabricate a declining-balance-style number."""
    other_method = _asset(depreciation_method="declining_balance")
    far_future = datetime(2030, 1, 1, tzinfo=UTC)
    assert asset_depreciation_expense_minor(other_method, start_at=None, end_as_of=far_future) == 0
    assert asset_book_value_minor(other_method, far_future) == PURCHASE_MINOR


def test_elapsed_months_respects_day_of_month_boundary() -> None:
    """Purchased on the 15th: a month only counts once the 15th recurs."""
    asset = _asset(purchase_date=datetime(2020, 3, 15, tzinfo=UTC))
    day_before_anniversary = datetime(2020, 4, 14, tzinfo=UTC)
    exact_anniversary = datetime(2020, 4, 15, tzinfo=UTC)

    assert asset_depreciation_expense_minor(
        asset, start_at=None, end_as_of=day_before_anniversary
    ) == 0
    assert asset_depreciation_expense_minor(
        asset, start_at=None, end_as_of=exact_anniversary
    ) == MONTHLY_MINOR


def test_period_depreciation_diff_never_drifts_across_slices() -> None:
    """depreciation_expense_minor for consecutive sub-periods must always sum
    to the same total as one single period covering the whole span — the
    same cumulative-diff guarantee ledger.py relies on for refund tax
    allocation."""
    asset = _asset()
    p0 = PURCHASE_DATE
    p1 = datetime(2020, 7, 1, tzinfo=UTC)  # +6 months
    p2 = datetime(2022, 7, 1, tzinfo=UTC)  # +30 months
    p3 = datetime(2025, 6, 1, tzinfo=UTC)  # past useful life

    whole = depreciation_expense_minor(
        depreciation_method=asset.depreciation_method,
        purchase_minor=asset.purchase_minor,
        salvage_minor=asset.salvage_minor,
        useful_life_months=asset.useful_life_months,
        purchase_date=asset.purchase_date,
        start_at=p0,
        end_as_of=p3,
    )
    sliced = (
        asset_depreciation_expense_minor(asset, start_at=p0, end_as_of=p1)
        + asset_depreciation_expense_minor(asset, start_at=p1, end_as_of=p2)
        + asset_depreciation_expense_minor(asset, start_at=p2, end_as_of=p3)
    )
    assert whole == sliced == DEPRECIABLE_BASE


def test_book_value_minor_pure_function_matches_purchase_minus_accumulated() -> None:
    as_of = datetime(2021, 1, 1, tzinfo=UTC)  # 12 months
    accumulated = accumulated_depreciation_minor(
        depreciation_method="straight_line",
        purchase_minor=PURCHASE_MINOR,
        salvage_minor=SALVAGE_MINOR,
        useful_life_months=USEFUL_LIFE_MONTHS,
        purchase_date=PURCHASE_DATE,
        as_of=as_of,
    )
    assert accumulated == 12 * MONTHLY_MINOR
    assert (
        book_value_minor(
            depreciation_method="straight_line",
            purchase_minor=PURCHASE_MINOR,
            salvage_minor=SALVAGE_MINOR,
            useful_life_months=USEFUL_LIFE_MONTHS,
            purchase_date=PURCHASE_DATE,
            as_of=as_of,
        )
        == PURCHASE_MINOR - accumulated
    )


# ---------------------------------------------------------------------------
# Shared fake session — same minimal shape used by test_accounting_provenance.py
# and test_manual_collections.py for exercising async SQLAlchemy call sites.
# ---------------------------------------------------------------------------
class _Result:
    def __init__(self, *, scalar=None, rows: list | None = None, one=None) -> None:
        self.scalar = scalar
        self.rows = rows or []
        self.one_value = one

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar

    def one(self):
        return self.one_value

    def all(self):
        return self.rows

    def scalars(self):
        return self


class _QueuedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.statements: list = []

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)


# ---------------------------------------------------------------------------
# Ledger wiring: build_operational_ledger posts balanced derived lines
# ---------------------------------------------------------------------------
def _empty_ledger_session(*, asset_rows: list) -> _QueuedSession:
    """Every operational query returns nothing except the assets query, so
    the only lines build_operational_ledger can emit are depreciation."""
    return _QueuedSession(
        [
            _Result(rows=[]),  # payments
            _Result(scalar="Asia/Kolkata"),
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # orders
            _Result(rows=[]),  # stock
            _Result(rows=[]),  # refunds
            _Result(rows=[]),  # expenses
            _Result(rows=[]),  # capital entries
            _Result(rows=[]),  # direct event tickets
            _Result(rows=asset_rows),  # assets (depreciation)
            _Result(rows=[]),  # approved posted journals
        ]
    )


@pytest.mark.asyncio
async def test_ledger_posts_a_balanced_depreciation_entry_for_an_asset() -> None:
    asset = _asset()
    end_exclusive = datetime(2020, 7, 1, tzinfo=UTC)  # 6 whole months, well in the past
    session = _empty_ledger_session(asset_rows=[asset])

    lines = await build_operational_ledger(
        session, company_id=COMPANY_ID, end_exclusive=end_exclusive
    )

    assert not session.results
    depreciation_lines = [line for line in lines if line.ref_type == "depreciation"]
    assert len(depreciation_lines) == 2
    assert sum(line.debit_minor for line in depreciation_lines) == 6 * MONTHLY_MINOR
    assert sum(line.credit_minor for line in depreciation_lines) == 6 * MONTHLY_MINOR
    assert {line.ref_id for line in depreciation_lines} == {asset.id}

    debit_line = next(line for line in depreciation_lines if line.debit_minor)
    credit_line = next(line for line in depreciation_lines if line.credit_minor)
    assert debit_line.account_code == DEPRECIATION_EXPENSE.code
    assert debit_line.account_type == "expense"
    assert credit_line.account_code == ACCUMULATED_DEPRECIATION.code
    assert credit_line.account_type == "asset"


@pytest.mark.asyncio
async def test_ledger_omits_an_asset_with_no_depreciation_yet_due() -> None:
    """Purchased the same day the ledger is built: zero whole months
    elapsed, so no line at all — not a balanced zero-amount pair."""
    asset = _asset(purchase_date=datetime(2026, 1, 1, tzinfo=UTC))
    end_exclusive = datetime(2026, 1, 2, tzinfo=UTC)
    session = _empty_ledger_session(asset_rows=[asset])

    lines = await build_operational_ledger(
        session, company_id=COMPANY_ID, end_exclusive=end_exclusive
    )

    assert not session.results
    assert [line for line in lines if line.ref_type == "depreciation"] == []


# ---------------------------------------------------------------------------
# P&L wiring: net_profit_minor drops by exactly the period's depreciation
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_reports_aggregator_subtracts_depreciation_from_net_profit() -> None:
    """This is the exact number GET /finance/pnl, GET /finance/pnl/partners,
    and the lifetime/trailing legs of GET /finance/distributable all read
    off PnLReport.net_profit_minor — see app/api/v1/finance/router.py."""
    asset = _asset()
    period_start = PURCHASE_DATE.date()
    period_end = datetime(2020, 7, 1, tzinfo=UTC).date()  # 6 whole months later

    session = _QueuedSession(
        [
            _Result(scalar="Asia/Kolkata"),  # company_timezone
            _Result(one=SimpleNamespace(n=0, gross=0, tips=0, cgst=0, sgst=0, igst=0, cess=0)),
            _Result(scalar=0),  # delivery
            _Result(scalar=0),  # discounts / points
            _Result(rows=[]),  # revenue by menu item type
            _Result(rows=[]),  # event tickets
            _Result(rows=[]),  # COGS movements
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # payments
            _Result(rows=[]),  # refunds
            _Result(rows=[asset]),  # assets (depreciation)
            _Result(rows=[]),  # expenses
        ]
    )

    report = await ReportsAggregator(session).aggregate(
        company_id=COMPANY_ID,
        period_start=period_start,
        period_end=period_end,
        period="custom",
    )

    assert not session.results
    # 6 whole months of straight-line depreciation on this asset; the
    # Asia/Kolkata local-day bounds run a few hours either side of UTC
    # midnight but don't cross a whole-month boundary, so the count is exact.
    assert report.depreciation_minor == 6 * MONTHLY_MINOR
    assert report.net_profit_minor == -report.depreciation_minor
    assert report.gross_profit_minor == 0  # depreciation must not touch gross profit
