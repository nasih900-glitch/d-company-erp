"""Accounting-safety tests for standalone manual daily collections."""

from __future__ import annotations

from datetime import UTC, date, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from sqlalchemy import UniqueConstraint
from sqlalchemy.orm.attributes import set_committed_value

import app.api.v1.finance.router as finance_router
from app.api.v1.finance.router import (
    CapitalEntryCreate,
    ManualCollectionCreate,
    ManualCollectionRead,
    ManualCollectionVoid,
    _partner_balance,
    create_manual_collection,
    void_manual_collection,
)
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext
from app.core.timezone import local_date_bounds_utc
from app.models import Company, ManualCollection, Shift, User
from app.models.finance import _guard_manual_collection_update
from app.services.accounting.ledger import build_operational_ledger
from app.services.alerts.business import build_pnl_alerts
from app.services.audit.recorder import TRACKED
from app.services.reports.aggregator import (
    PaymentBreakdown,
    PnLReport,
    ReportsAggregator,
    RevenueBreakdown,
    TaxBreakdown,
    _manual_collection_totals,
)

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("33333333-3333-3333-3333-333333333333")


def _collection(
    *,
    method: str,
    amount_minor: int,
    business_date: date = date(2026, 7, 16),
    voided: bool = False,
) -> ManualCollection:
    return ManualCollection(
        id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        business_date=business_date,
        method=method,
        amount_minor=amount_minor,
        source_kind="manual_daily",
        source_ref=f"owner-confirmed:{business_date.isoformat()}:{uuid4()}",
        note="Owner-confirmed daily collection",
        idempotency_key=f"manual-collection:{uuid4()}",
        created_by=USER_ID,
        # Intentionally later than business_date: reporting must ignore this.
        created_at=datetime(2026, 7, 17, 4, 30, tzinfo=UTC),
        voided_at=datetime(2026, 7, 17, 5, 0, tzinfo=UTC) if voided else None,
        voided_by=USER_ID if voided else None,
        void_reason="Duplicate entry" if voided else None,
    )


class _Result:
    def __init__(
        self,
        *,
        scalar=None,
        rows: list | None = None,
        one=None,
    ) -> None:
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
        self.statements = []

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)


def _tenant(*, branch_id: UUID | None = BRANCH_ID) -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=branch_id,
        terminal_id=None,
        roles=("owner",),
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="manual-collection-2026-07-16-cash-v1",
            idempotency_request_hash="request-hash",
        )
    )


def test_schema_enforces_money_provenance_and_removes_shift_lump_sum() -> None:
    table = ManualCollection.__table__
    constraint_names = {constraint.name for constraint in table.constraints}
    assert {
        "ck_manual_collection_method",
        "ck_manual_collection_positive_amount",
        "ck_manual_collection_source_kind",
        "ck_manual_collection_void_state",
        "uq_manual_collection_company_idempotency",
        "uq_manual_collection_source_method",
    } <= constraint_names

    unique_columns = {
        tuple(column.name for column in constraint.columns)
        for constraint in table.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert ("company_id", "idempotency_key") in unique_columns
    assert (
        "company_id",
        "branch_id",
        "source_kind",
        "source_ref",
        "method",
    ) in unique_columns
    for foreign_key_column in ("company_id", "branch_id", "created_by", "voided_by"):
        assert table.c[foreign_key_column].index is True

    assert "manual_collection_minor" not in Shift.__table__.c
    assert "manual_collection_note" not in Shift.__table__.c
    assert "manual_collection_updated_at" not in Shift.__table__.c
    assert ManualCollection in TRACKED


def test_amount_and_provenance_are_immutable_but_first_void_is_allowed() -> None:
    row = _collection(method="cash", amount_minor=21_000)
    immutable_fields = (
        "company_id",
        "branch_id",
        "business_date",
        "method",
        "amount_minor",
        "source_kind",
        "source_ref",
        "note",
        "idempotency_key",
        "created_by",
        "created_at",
    )
    for field in immutable_fields:
        set_committed_value(row, field, getattr(row, field))
    row.amount_minor = 22_000
    with pytest.raises(ValueError, match="amount_minor"):
        _guard_manual_collection_update(None, None, row)

    set_committed_value(row, "amount_minor", 21_000)
    set_committed_value(row, "voided_at", None)
    set_committed_value(row, "voided_by", None)
    set_committed_value(row, "void_reason", None)
    row.voided_at = datetime(2026, 7, 17, 5, 0, tzinfo=UTC)
    row.voided_by = USER_ID
    row.void_reason = "Duplicate entry"
    _guard_manual_collection_update(None, None, row)


def test_today_cash_and_upi_are_separate_and_voids_do_not_count() -> None:
    rows = [
        _collection(method="cash", amount_minor=21_000),
        _collection(method="upi", amount_minor=162_000),
        _collection(method="cash", amount_minor=999_999, voided=True),
    ]
    totals = _manual_collection_totals(rows)
    assert totals == {"cash": 21_000, "upi": 162_000, "card": 0, "bank": 0}

    revenue = RevenueBreakdown(
        food_minor=5_000,
        manual_collections_minor=sum(totals.values()),
    )
    payments = PaymentBreakdown(
        cash_minor=5_000 + totals["cash"],
        upi_minor=totals["upi"],
    )
    assert revenue.total_minor == 188_000
    assert payments.total_minor == 188_000


@pytest.mark.asyncio
async def test_report_includes_manual_revenue_and_payments_but_not_aov() -> None:
    cash = _collection(method="cash", amount_minor=21_000)
    upi = _collection(method="upi", amount_minor=162_000)
    voided = _collection(method="cash", amount_minor=70_000, voided=True)
    session = _QueuedSession(
        [
            _Result(scalar="Asia/Kolkata"),
            _Result(
                one=SimpleNamespace(
                    n=1,
                    gross=5_000,
                    cgst=0,
                    sgst=0,
                    igst=0,
                    cess=0,
                )
            ),
            _Result(scalar=0),  # delivery
            _Result(scalar=0),  # discounts / points
            _Result(rows=[SimpleNamespace(type="food", amount=5_000)]),
            _Result(rows=[]),  # event tickets
            _Result(rows=[]),  # COGS movements
            _Result(rows=[cash, upi, voided]),
            _Result(rows=[SimpleNamespace(method="cash", amount=5_000)]),
            _Result(rows=[]),  # refunds
            _Result(rows=[]),  # expenses
        ]
    )

    report = await ReportsAggregator(session).aggregate(
        company_id=COMPANY_ID,
        period_start=date(2026, 7, 16),
        period_end=date(2026, 7, 16),
        period="daily",
    )

    assert not session.results
    assert report.orders_count == 1
    assert report.avg_ticket_minor == 5_000
    assert report.manual_collections_minor == 183_000
    assert report.gross_revenue_minor == 188_000
    assert report.net_profit_minor == 188_000
    assert report.payments_received.cash_minor == 26_000
    assert report.payments_received.upi_minor == 162_000
    assert report.payments_received.total_minor == 188_000

    payment_sql = next(
        str(statement)
        for statement in session.statements
        if "FROM payments" in str(statement)
    )
    assert "orders.status IN" in payment_sql


@pytest.mark.asyncio
async def test_ledger_uses_business_date_company_timezone_and_balances() -> None:
    backfilled_cash = _collection(
        method="cash",
        amount_minor=21_000,
        business_date=date(2026, 7, 11),
    )
    backfilled_upi = _collection(
        method="upi",
        amount_minor=162_000,
        business_date=date(2026, 7, 11),
    )
    voided = _collection(
        method="cash",
        amount_minor=500_000,
        business_date=date(2026, 7, 11),
        voided=True,
    )
    session = _QueuedSession(
        [
            _Result(rows=[]),  # order payments
            _Result(scalar="Asia/Kolkata"),
            _Result(rows=[backfilled_cash, backfilled_upi, voided]),
            _Result(rows=[]),  # orders
            _Result(rows=[]),  # stock movements
            _Result(rows=[]),  # refunds
            _Result(rows=[]),  # expenses
            _Result(rows=[]),  # capital
            _Result(rows=[]),  # direct event tickets
            _Result(rows=[]),  # approved posted journals
        ]
    )
    start_at, end_exclusive = local_date_bounds_utc(
        date(2026, 7, 11), date(2026, 7, 11), "Asia/Kolkata"
    )

    lines = await build_operational_ledger(
        session,
        company_id=COMPANY_ID,
        start_at=start_at,
        end_exclusive=end_exclusive,
    )

    assert not session.results
    assert len(lines) == 4
    assert {line.ref_id for line in lines} == {
        backfilled_cash.id,
        backfilled_upi.id,
    }
    assert {line.occurred_at for line in lines} == {start_at}
    assert sum(line.debit_minor for line in lines) == 183_000
    assert sum(line.credit_minor for line in lines) == 183_000
    assert {line.account_code for line in lines if line.debit_minor} == {"1000", "1110"}
    assert {
        line.account_code for line in lines if line.credit_minor
    } == {"4300"}

    payment_sql = str(session.statements[0])
    assert "orders.status IN" in payment_sql
    manual_sql = str(session.statements[2])
    assert "manual_collections.business_date" in manual_sql
    assert "manual_collections.voided_at IS NULL" in manual_sql


@pytest.mark.asyncio
async def test_create_is_idempotent_on_exact_replay(monkeypatch) -> None:
    existing = ManualCollectionRead(
        id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        business_date=date(2026, 7, 16),
        method="cash",
        amount_minor=21_000,
        source_kind="manual_daily",
        source_ref="owner-confirmed:2026-07-16",
        note=None,
        idempotency_key="manual-collection-2026-07-16-cash-v1",
        created_by=USER_ID,
        created_by_name="Owner",
        created_at=datetime(2026, 7, 16, 12, 0, tzinfo=UTC),
        voided_at=None,
        voided_by=None,
        voided_by_name=None,
        void_reason=None,
        is_voided=False,
    )

    async def replay(*_args, **_kwargs):
        return {"status_code": 201, "body": existing.model_dump(mode="json")}

    monkeypatch.setattr(finance_router, "check_or_reserve", replay)

    class _NoMutationSession:
        def __getattr__(self, name):
            raise AssertionError(f"Replay attempted database mutation via {name}")

    response = await create_manual_collection(
        ManualCollectionCreate(
            branch_id=BRANCH_ID,
            business_date=date(2026, 7, 16),
            method="cash",
            amount_minor=21_000,
            source_ref="owner-confirmed:2026-07-16",
        ),
        _NoMutationSession(),
        _request(),
        _tenant(),
    )
    assert response == existing


@pytest.mark.asyncio
async def test_registered_gst_company_rejects_aggregate_collection(monkeypatch) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(finance_router, "check_or_reserve", reserve)

    class _RegisteredCompanySession:
        async def get(self, model, key):
            assert model is Company and key == COMPANY_ID
            return SimpleNamespace(
                id=COMPANY_ID,
                deleted_at=None,
                gst_registration_type="regular",
            )

    with pytest.raises(BusinessRuleError, match="GST-registered"):
        await create_manual_collection(
            ManualCollectionCreate(
                branch_id=BRANCH_ID,
                business_date=date(2026, 7, 16),
                method="upi",
                amount_minor=162_000,
                source_ref="owner-confirmed:2026-07-16",
            ),
            _RegisteredCompanySession(),
            _request(),
            _tenant(),
        )


@pytest.mark.asyncio
async def test_void_is_one_way_and_exact_retry_is_safe() -> None:
    row = _collection(method="cash", amount_minor=21_000)

    class _VoidSession:
        def __init__(self) -> None:
            self.flushes = 0

        async def execute(self, _statement):
            return _Result(scalar=row)

        async def get(self, model, key):
            assert model is User
            return SimpleNamespace(id=key, name="Owner")

        async def flush(self):
            self.flushes += 1

    session = _VoidSession()
    first = await void_manual_collection(
        row.id,
        ManualCollectionVoid(reason="Duplicate collection"),
        session,
        _tenant(),
    )
    assert first.is_voided is True
    assert first.voided_by == USER_ID
    assert first.voided_by_name == "Owner"
    assert session.flushes == 1

    replay = await void_manual_collection(
        row.id,
        ManualCollectionVoid(reason="Duplicate collection"),
        session,
        _tenant(),
    )
    assert replay.voided_at == first.voided_at
    assert session.flushes == 1

    with pytest.raises(BusinessRuleError, match="different reason"):
        await void_manual_collection(
            row.id,
            ManualCollectionVoid(reason="A different correction"),
            session,
            _tenant(),
        )


def test_manual_only_period_does_not_claim_no_sales() -> None:
    report = PnLReport(
        period="daily",
        label="16-Jul-2026",
        period_start=date(2026, 7, 16),
        period_end=date(2026, 7, 16),
        fiscal_year="2026-27",
        orders_count=0,
        tickets_count=0,
        avg_ticket_minor=0,
        revenue=RevenueBreakdown(manual_collections_minor=183_000),
        tax_collected=TaxBreakdown(),
        payments_received=PaymentBreakdown(cash_minor=21_000, upi_minor=162_000),
    )
    alerts = build_pnl_alerts(report)
    assert all(alert.title != "No sales recorded" for alert in alerts)
    assert any(alert.title == "Only manual collections recorded" for alert in alerts)


@pytest.mark.asyncio
async def test_partner_capital_is_investment_less_repayment_only() -> None:
    session = _QueuedSession(
        [
            _Result(
                rows=[
                    ("invest", 751_443_00),
                    ("withdraw", 10_000_00),
                    ("profit_share", 99_000_00),
                ]
            )
        ]
    )
    assert await _partner_balance(session, uuid4()) == 741_443_00

    with pytest.raises(ValueError):
        CapitalEntryCreate(
            partner_id=uuid4(),
            type="profit_share",
            amount_minor=10_000,
            effective_at=datetime.now(UTC),
        )
