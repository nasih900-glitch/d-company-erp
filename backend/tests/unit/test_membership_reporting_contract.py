"""Membership revenue must remain visible across Reports, Analytics and Finance."""

from __future__ import annotations

from datetime import date
from uuid import UUID

import pytest

import app.api.v1.analytics.router as analytics_router
import app.api.v1.finance.router as finance_router
from app.api.v1.reports.router import _to_dto
from app.core.tenant import TenantContext
from app.services.reports.aggregator import (
    PaymentBreakdown,
    PnLReport,
    RevenueBreakdown,
    TaxBreakdown,
)

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
USER_ID = UUID("22222222-2222-2222-2222-222222222222")
DAY = date(2026, 8, 25)


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=None,
        terminal_id=None,
        roles=("owner",),
        protected_access=True,
    )


def _qa_report() -> PnLReport:
    # Fixture under audit: Rs836 POS + Rs1,999 membership - Rs10 refund.
    return PnLReport(
        period="daily",
        label="25-Aug-2026",
        period_start=DAY,
        period_end=DAY,
        fiscal_year="2026-27",
        orders_count=9,
        tickets_count=0,
        avg_ticket_minor=9_178,
        revenue=RevenueBreakdown(
            gaming_minor=83_600,
            memberships_minor=199_900,
        ),
        tax_collected=TaxBreakdown(),
        payments_received=PaymentBreakdown(upi_minor=283_500),
        refunds_issued_minor=1_000,
        settled_refunds_issued_minor=1_000,
        membership_refunds_issued_minor=1_000,
    )


class _Result:
    def __init__(self, *, scalar=0, rows=None, one=None) -> None:
        self.scalar = scalar
        self.rows = [] if rows is None else rows
        self.one_value = one

    def scalar_one(self):
        return self.scalar

    def all(self):
        return self.rows

    def one(self):
        return self.one_value


class _Session:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)
        self.statements = []

    async def execute(self, statement):
        assert self.results
        self.statements.append(statement)
        return self.results.pop(0)


@pytest.mark.asyncio
async def test_analytics_exposes_membership_as_a_named_revenue_stream(monkeypatch) -> None:
    report = _qa_report()

    async def timezone(*_args, **_kwargs):
        return "Asia/Kolkata"

    class _Aggregator:
        def __init__(self, _session) -> None:
            pass

        async def aggregate_daily(self, **_kwargs):
            return report

    monkeypatch.setattr(analytics_router, "company_timezone", timezone)
    monkeypatch.setattr(analytics_router, "ReportsAggregator", _Aggregator)
    session = _Session(
        _Result(rows=[]),  # inventory value rows
        _Result(scalar=0),  # low-stock count
        _Result(scalar=0),  # active gaming sessions
    )

    dashboard = await analytics_router.dashboard(session, DAY, _tenant())

    assert dashboard.revenue_memberships_minor == 199_900
    assert dashboard.revenue_gaming_minor == 83_600
    assert dashboard.revenue_total_minor == 283_500
    assert dashboard.net_profit_minor == 282_500


@pytest.mark.asyncio
async def test_finance_pnl_uses_same_receipt_basis_membership_total(monkeypatch) -> None:
    report = _qa_report()

    async def timezone(*_args, **_kwargs):
        return "Asia/Kolkata"

    class _Aggregator:
        def __init__(self, _session) -> None:
            pass

        async def aggregate(self, **_kwargs):
            return report

    monkeypatch.setattr(finance_router, "company_timezone", timezone)
    monkeypatch.setattr(finance_router, "ReportsAggregator", _Aggregator)

    pnl = await finance_router.profit_loss(
        _Session(),
        period_start=DAY,
        period_end=DAY,
        tenant=_tenant(),
    )

    assert pnl.accounting_basis == "operational_receipt"
    assert pnl.revenue_minor == 282_500
    assert pnl.memberships_minor == 199_900
    assert pnl.net_profit_minor == 282_500


def test_report_contract_names_membership_receipts_and_settled_reversals() -> None:
    dto = _to_dto(_qa_report())

    assert dto.revenue.memberships_minor == 199_900
    assert dto.membership_refunds_issued_minor == 1_000
    assert dto.refunds_issued_minor == 1_000
    assert dto.net_payments_received_minor == 282_500


@pytest.mark.asyncio
async def test_withdrawn_cash_refund_restores_active_member_kpi(monkeypatch) -> None:
    """A withdrawn attempt is history, not a completed refund.

    The withdrawal route restores ``revoked_at`` when the term is still valid.
    Finance must then count the verified paid term again instead of excluding
    it forever merely because an append-only MembershipRefund audit row exists.
    """
    report = _qa_report()

    async def timezone(*_args, **_kwargs):
        return "Asia/Kolkata"

    class _Aggregator:
        def __init__(self, _session) -> None:
            pass

        async def aggregate(self, **_kwargs):
            return report

    monkeypatch.setattr(finance_router, "company_timezone", timezone)
    monkeypatch.setattr(finance_router, "ReportsAggregator", _Aggregator)
    session = _Session(
        _Result(
            rows=[
                ("monthly", False, 199_900),  # restored paid term
                ("monthly", False, None),  # legacy entitlement-only term
            ]
        ),
        _Result(scalar=0),  # marketing spend
        _Result(scalar=0),  # new customers
        _Result(one=(1, 199_900)),  # all-time customer spend/LTV
    )

    metrics = await finance_router.business_metrics(
        session,
        period_start=DAY,
        period_end=DAY,
        tenant=_tenant(),
    )

    assert metrics.active_members_count == 2
    assert metrics.mrr_minor == 0  # a manual prepaid term is not recurring
    assert metrics.arr_minor == 0
    assert metrics.ltv_minor == 199_900
    membership_sql = str(session.statements[0])
    assert "membership_payments" in membership_sql
    assert "LEFT OUTER JOIN membership_payments" in membership_sql
    assert "membership_refunds" not in membership_sql
