"""Regression tests for the 2026-07-12 production-release audit fixes."""

from __future__ import annotations

from datetime import date, datetime, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.v1.accounting.router import _trial_balance_lines
from app.api.v1.reports.router import _allocated_component, _refund_adjustments_by_rate
from app.core.config import Settings
from app.core.errors import BusinessRuleError
from app.core.middleware import RequestBodyLimitMiddleware
from app.core.timezone import local_date_bounds_utc, local_today
from app.services.accounting.ledger import LedgerLine, _proportional
from app.services.reports.aggregator import month_range


def _settings(**over):
    base = dict(
        database_url="postgresql+psycopg://e:e@localhost:5432/e",
        account_security_email="b@x.com",
    )
    base.update(over)
    return Settings(**base)


@pytest.mark.parametrize("bad", ["2026-13", "2026", "June", "2026-1", "2026/06", ""])
def test_month_range_rejects_malformed(bad):
    with pytest.raises(BusinessRuleError):
        month_range(bad)


def test_month_range_valid():
    assert month_range("2026-06") == (date(2026, 6, 1), date(2026, 6, 30))
    assert month_range("2026-12") == (date(2026, 12, 1), date(2026, 12, 31))


def test_prod_rejects_default_jwt_secret():
    with pytest.raises(Exception):
        _settings(env="prod", jwt_secret="CHANGE_ME_IN_PROD_AT_LEAST_32_CHARS")


def test_prod_accepts_strong_jwt_secret():
    s = _settings(env="prod", jwt_secret="k" * 48)
    assert s.env == "prod"


def test_dev_allows_default_secret():
    s = _settings(env="dev")
    assert s.env == "dev"


def test_yoy_leap_day_does_not_crash():
    from app.api.v1.insights.router import _date_range_for_period

    # 29-Feb-2028 (leap) compared to 2027 (non-leap) must not raise.
    (_cur, prev, _cl, _pl) = _date_range_for_period("yoy", date(2028, 2, 29))
    assert prev[1] == date(2027, 2, 28)


def test_kerala_calendar_day_uses_local_midnight() -> None:
    start, end = local_date_bounds_utc(
        date(2026, 3, 31),
        date(2026, 3, 31),
        "Asia/Kolkata",
    )

    assert start == datetime(2026, 3, 30, 18, 30, tzinfo=timezone.utc)
    assert end == datetime(2026, 3, 31, 18, 30, tzinfo=timezone.utc)


def test_local_today_respects_company_timezone() -> None:
    now = datetime(2026, 3, 31, 20, 0, tzinfo=timezone.utc)
    assert local_today("Asia/Kolkata", now=now) == date(2026, 4, 1)
    assert local_today("Europe/London", now=now) == date(2026, 3, 31)


def test_refund_component_allocation_has_no_final_rounding_drift() -> None:
    assert _proportional(5, 33, 100) == 2
    assert _proportional(5, 66, 100) == 3
    assert _proportional(5, 100, 100) == 5
    assert _allocated_component(5, 33, 100) == 2
    assert _allocated_component(5, 66, 100) == 3
    assert _allocated_component(5, 100, 100) == 5


class _RefundQueryResult:
    def __init__(self, rows: list) -> None:
        self.rows = rows

    def all(self):
        return self.rows

    def scalars(self):
        return self


class _RefundQuerySession:
    def __init__(self, *results: _RefundQueryResult) -> None:
        self.results = list(results)

    async def execute(self, statement):
        assert self.results, f"unexpected extra statement: {statement}"
        return self.results.pop(0)


@pytest.mark.asyncio
async def test_refund_adjustments_by_rate_excludes_tip_from_taxable_denominator() -> None:
    """Same tip-exclusion bug already fixed in ledger.py's build_operational_ledger
    (taxable_total = total_minor - tip_minor) and aggregator.py's refund
    cgst/sgst/igst/cess loop (total = total_minor - tip_minor): a refund on a
    tipped order must proportion the GST reversal against the taxable total,
    never order.total_minor, which includes the tip folded on at payment time.
    """
    order_id = uuid4()
    # Taxable bill: Rs800 taxable + Rs72 CGST + Rs72 SGST = Rs944. A Rs200 tip
    # is folded into total_minor at payment time (total_minor = 944 + 200 =
    # 1144), but a tip is never part of the tax base.
    order = SimpleNamespace(id=order_id, total_minor=1_144, tip_minor=200)
    # Refund is exactly half of the *taxable* total (472 of 944) — a clean
    # round split only if tip_minor is correctly excluded from the
    # denominator. Against the buggy tip-inclusive 1144, 472 is ~41.3% and
    # would instead yield taxable=330, cgst=30, sgst=30.
    refund = SimpleNamespace(
        order_id=order_id,
        amount_minor=472,
        created_at=datetime(2026, 7, 20, tzinfo=timezone.utc),
    )
    line = SimpleNamespace(
        order_id=order_id,
        tax_rate=0.18,
        taxable_value_minor=800,
        cgst_minor=72,
        sgst_minor=72,
        igst_minor=0,
        cess_minor=0,
        line_total_minor=944,
    )
    session = _RefundQuerySession(
        _RefundQueryResult(rows=[(refund, order)]),
        _RefundQueryResult(rows=[line]),
    )

    adjustments = await _refund_adjustments_by_rate(
        session,
        company_id=uuid4(),
        start_at=datetime(2026, 7, 1, tzinfo=timezone.utc),
        end_exclusive=datetime(2026, 8, 1, tzinfo=timezone.utc),
        eco=False,
    )

    assert adjustments[0.18] == {
        "taxable": 400,
        "cgst": 36,
        "sgst": 36,
        "igst": 0,
        "cess": 0,
    }


def test_trial_balance_groups_to_equal_debits_and_credits() -> None:
    occurred_at = datetime(2026, 7, 12, 10, tzinfo=timezone.utc)
    ref_id = uuid4()
    lines = [
        LedgerLine(
            occurred_at=occurred_at,
            ref_type="payment",
            ref_id=ref_id,
            account_code="1000",
            account_name="Cash",
            account_type="asset",
            debit_minor=10_500,
        ),
        LedgerLine(
            occurred_at=occurred_at,
            ref_type="order",
            ref_id=ref_id,
            account_code="4000",
            account_name="Sales Revenue",
            account_type="revenue",
            credit_minor=10_000,
        ),
        LedgerLine(
            occurred_at=occurred_at,
            ref_type="order",
            ref_id=ref_id,
            account_code="2101",
            account_name="CGST Payable",
            account_type="liability",
            credit_minor=250,
        ),
        LedgerLine(
            occurred_at=occurred_at,
            ref_type="order",
            ref_id=ref_id,
            account_code="2102",
            account_name="SGST Payable",
            account_type="liability",
            credit_minor=250,
        ),
    ]

    grouped = _trial_balance_lines(lines)

    assert sum(line.debit_minor for line in grouped) == 10_500
    assert sum(line.credit_minor for line in grouped) == 10_500


def test_request_body_limit_rejects_large_content_length() -> None:
    app = FastAPI()

    @app.post("/echo")
    async def echo() -> dict[str, bool]:
        return {"ok": True}

    client = TestClient(RequestBodyLimitMiddleware(app, max_bytes=4))
    response = client.post("/echo", content=b"12345")

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "request_too_large"
