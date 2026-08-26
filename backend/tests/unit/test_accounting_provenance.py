"""Capital provenance and canonical-ledger integrity tests."""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from sqlalchemy.orm.attributes import set_committed_value

from app.api.v1.finance.router import (
    CapitalEntryVoid,
    _partner_balance,
    void_capital_entry,
)
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext
from app.models import (
    Account,
    CapitalEntry,
    ExpenseCategory,
    JournalEntry,
    JournalLine,
    Partner,
    User,
)
from app.models.finance import _guard_capital_entry_update
from app.services.accounting.accounts import (
    DEFAULT_CHART_OF_ACCOUNTS,
    DEFAULT_EXPENSE_CATEGORY_ACCOUNTS,
    SALES_REVENUE,
    TIPS_PAYABLE,
)
from app.services.accounting.ledger import (
    _expense_account,
    _posted_journal_ledger_lines,
    _validate_account_code_meanings,
    build_operational_ledger,
)

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
PARTNER_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("33333333-3333-3333-3333-333333333333")
NOW = datetime(2026, 7, 16, 12, 0, tzinfo=UTC)


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
        self.statements = []
        self.flushes = 0

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"Unexpected SQL statement: {statement}"
        return self.results.pop(0)

    async def flush(self) -> None:
        self.flushes += 1

    async def get(self, model, key):
        assert model is User
        if key is None:
            return None
        return SimpleNamespace(id=key, name="Owner")


def _capital(
    *,
    settlement_account: str = "bank",
    voided: bool = False,
) -> CapitalEntry:
    return CapitalEntry(
        id=uuid4(),
        partner_id=PARTNER_ID,
        type="invest",
        amount_minor=1_000_00,
        effective_at=NOW,
        note="Opening contribution",
        settlement_account=settlement_account,
        source_ref=f"test:{uuid4()}",
        created_by=USER_ID,
        created_at=NOW,
        voided_at=NOW if voided else None,
        voided_by=USER_ID if voided else None,
        void_reason="Duplicate entry" if voided else None,
    )


def _paid_order_with_tip(
    *,
    subtotal_minor: int,
    cgst_minor: int = 0,
    sgst_minor: int = 0,
    tip_minor: int,
    round_off_minor: int = 0,
    manual_discount_minor: int = 0,
    points_redeemed_minor: int = 0,
) -> SimpleNamespace:
    """A settled order the way record_payment leaves it: total_minor already
    folds in the tip, exactly like ledger.py's TIPS_PAYABLE credit and the
    reports balance check (app/api/v1/reports/router.py) expect."""
    total_minor = (
        subtotal_minor
        + cgst_minor
        + sgst_minor
        + tip_minor
        + round_off_minor
        - manual_discount_minor
        - points_redeemed_minor
    )
    return SimpleNamespace(
        id=uuid4(),
        invoice_no="INV-0099",
        invoice_issued_at=NOW,
        closed_at=NOW,
        opened_at=NOW,
        subtotal_minor=subtotal_minor,
        cgst_minor=cgst_minor,
        sgst_minor=sgst_minor,
        igst_minor=0,
        cess_minor=0,
        tip_minor=tip_minor,
        round_off_minor=round_off_minor,
        manual_discount_minor=manual_discount_minor,
        points_redeemed_minor=points_redeemed_minor,
        total_minor=total_minor,
    )


def _partner() -> Partner:
    return Partner(
        id=PARTNER_ID,
        company_id=COMPANY_ID,
        name="Nasih",
        share_pct=33.33,
        joined_at=NOW,
    )


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=None,
        terminal_id=None,
        roles=("owner",),
    )


def test_canonical_chart_and_expense_categories_have_one_meaning_per_code() -> None:
    meanings = {account.code: (account.name, account.type) for account in DEFAULT_CHART_OF_ACCOUNTS}
    assert len(meanings) == len(DEFAULT_CHART_OF_ACCOUNTS)
    assert SALES_REVENUE.name == "POS Sales Revenue"

    expected_category_codes = {
        "Wages": "5100",
        "Rent": "5200",
        "Utilities": "5300",
        "Marketing": "5400",
        "Furnitures": "5901",
        "Design & Architecture": "5902",
        "Labour Charges": "5903",
        "Building Materials": "5904",
        "PaperWork Charges": "5905",
        "Food & Water": "5906",
        "Gaming Equipments": "5907",
        "Kitchen Equipments": "5908",
        "Transportation Cost": "5909",
        "Cafe Equipments": "5910",
        "Others": "5911",
    }
    assert {
        name: account.code for name, account in DEFAULT_EXPENSE_CATEGORY_ACCOUNTS.items()
    } == expected_category_codes
    assert len(set(expected_category_codes.values())) == len(expected_category_codes)

    emitted = [
        _expense_account(
            ExpenseCategory(
                id=uuid4(),
                company_id=COMPANY_ID,
                name=name,
                code=None,
            )
        )
        for name in expected_category_codes
    ]
    assert {code for code, _, _ in emitted} == set(expected_category_codes.values())

    with pytest.raises(BusinessRuleError, match="non-expense account code"):
        _expense_account(
            ExpenseCategory(
                id=uuid4(),
                company_id=COMPANY_ID,
                name="Bad category",
                code="4000",
            )
        )


def test_capital_model_is_immutable_except_atomic_first_void() -> None:
    row = _capital()
    for field in (
        "partner_id",
        "type",
        "amount_minor",
        "effective_at",
        "note",
        "settlement_account",
        "source_ref",
        "created_by",
        "created_at",
    ):
        set_committed_value(row, field, getattr(row, field))

    row.amount_minor += 1
    with pytest.raises(ValueError, match="amount_minor"):
        _guard_capital_entry_update(None, None, row)

    set_committed_value(row, "amount_minor", 1_000_00)
    for field in ("voided_at", "voided_by", "void_reason"):
        set_committed_value(row, field, None)
    row.voided_at = NOW
    row.voided_by = USER_ID
    row.void_reason = "Duplicate entry"
    _guard_capital_entry_update(None, None, row)

    for field in ("voided_at", "voided_by", "void_reason"):
        set_committed_value(row, field, getattr(row, field))
    row.void_reason = "Changed reason"
    with pytest.raises(ValueError, match="cannot be changed"):
        _guard_capital_entry_update(None, None, row)


@pytest.mark.asyncio
async def test_capital_balance_and_ledger_exclude_voids_and_map_historical_funds() -> None:
    balance_session = _QueuedSession([_Result(rows=[("invest", 2_000_00), ("withdraw", 500_00)])])
    assert await _partner_balance(balance_session, PARTNER_ID) == 1_500_00
    balance_sql = str(balance_session.statements[0])
    assert "capital_entries.voided_at IS NULL" in balance_sql
    assert "capital_entries.type IN" in balance_sql

    active = _capital(settlement_account="historical_funds")
    voided = _capital(settlement_account="bank", voided=True)
    ledger_session = _QueuedSession(
        [
            _Result(rows=[]),  # payments
            _Result(scalar="Asia/Kolkata"),
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # membership payments
            _Result(rows=[]),  # membership refund settlements
            _Result(rows=[]),  # orders
            _Result(rows=[]),  # stock
            _Result(rows=[]),  # refunds
            _Result(rows=[]),  # tip payouts
            _Result(rows=[]),  # expenses
            _Result(rows=[(active, _partner()), (voided, _partner())]),
            _Result(rows=[]),  # assets (depreciation)
            _Result(rows=[]),  # approved posted journals
        ]
    )
    lines = await build_operational_ledger(
        ledger_session,
        company_id=COMPANY_ID,
        end_exclusive=datetime(2026, 7, 17, tzinfo=UTC),
    )
    assert len(lines) == 2
    assert {line.ref_id for line in lines} == {active.id}
    assert {(line.account_code, line.debit_minor) for line in lines} >= {
        ("1185", active.amount_minor)
    }
    assert {(line.account_code, line.credit_minor) for line in lines} >= {
        ("3000", active.amount_minor)
    }
    capital_sql = str(ledger_session.statements[10])
    assert "capital_entries.voided_at IS NULL" in capital_sql


@pytest.mark.asyncio
async def test_capital_void_is_reasoned_one_way_and_exact_replay_is_safe() -> None:
    row = _capital()
    first = _QueuedSession([_Result(scalar=row)])
    response = await void_capital_entry(
        row.id,
        CapitalEntryVoid(reason="Duplicate source"),
        first,
        _tenant(),
    )
    assert first.flushes == 1
    assert response.is_voided is True
    assert response.void_reason == "Duplicate source"
    assert response.voided_by == USER_ID

    replay = _QueuedSession([_Result(scalar=row)])
    replay_response = await void_capital_entry(
        row.id,
        CapitalEntryVoid(reason="Duplicate source"),
        replay,
        _tenant(),
    )
    assert replay.flushes == 0
    assert replay_response.void_reason == "Duplicate source"

    conflict = _QueuedSession([_Result(scalar=row)])
    with pytest.raises(BusinessRuleError, match="different reason"):
        await void_capital_entry(
            row.id,
            CapitalEntryVoid(reason="Different correction"),
            conflict,
            _tenant(),
        )


@pytest.mark.asyncio
async def test_whitelisted_posted_journal_is_loaded_and_validated() -> None:
    entry = JournalEntry(
        id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=None,
        ref_type="historical_setup_reconciliation",
        ref_id=uuid4(),
        posted_at=NOW,
        memo="Google Sheet setup reconciliation",
        total_minor=2_022_102_74,
    )
    debit_account = Account(
        id=uuid4(),
        company_id=COMPANY_ID,
        code="1490",
        name="Historical Setup Costs Pending Classification",
        type="asset",
        normal_side="dr",
    )
    credit_account = Account(
        id=uuid4(),
        company_id=COMPANY_ID,
        code="1185",
        name="Historical Funds Pending Reconciliation",
        type="asset",
        normal_side="dr",
    )
    debit = JournalLine(
        id=uuid4(),
        journal_entry_id=entry.id,
        account_id=debit_account.id,
        side="dr",
        amount_minor=entry.total_minor,
    )
    credit = JournalLine(
        id=uuid4(),
        journal_entry_id=entry.id,
        account_id=credit_account.id,
        side="cr",
        amount_minor=entry.total_minor,
    )
    session = _QueuedSession(
        [
            _Result(rows=[entry]),
            _Result(rows=[(debit, debit_account), (credit, credit_account)]),
        ]
    )

    lines = await _posted_journal_ledger_lines(
        session,
        company_id=COMPANY_ID,
        start_at=None,
        end_exclusive=datetime(2026, 7, 17, tzinfo=UTC),
    )
    assert sum(line.debit_minor for line in lines) == entry.total_minor
    assert sum(line.credit_minor for line in lines) == entry.total_minor
    assert {line.account_code for line in lines} == {"1185", "1490"}
    _validate_account_code_meanings(lines)

    sql = str(session.statements[0].compile(compile_kwargs={"literal_binds": True}))
    assert "historical_setup_reconciliation" in sql


@pytest.mark.asyncio
async def test_order_tip_minor_posts_a_balanced_tips_payable_ledger_line() -> None:
    """record_payment folds a tip into Order.total_minor (see
    app/api/v1/pos/router.py); this proves the ledger side of that contract:
    the tip shows up as its own Tips Payable credit and the whole order
    entry still balances exactly, debit for debit."""
    order = _paid_order_with_tip(subtotal_minor=8_000, cgst_minor=720, sgst_minor=720, tip_minor=1_500)
    assert order.total_minor == 10_940

    ledger_session = _QueuedSession(
        [
            _Result(rows=[]),  # payments
            _Result(scalar="Asia/Kolkata"),
            _Result(rows=[]),  # manual collections
            _Result(rows=[]),  # membership payments
            _Result(rows=[]),  # membership refund settlements
            _Result(rows=[order]),  # orders
            _Result(rows=[]),  # stock
            _Result(rows=[]),  # refunds
            _Result(rows=[]),  # tip payouts
            _Result(rows=[]),  # expenses
            _Result(rows=[]),  # capital entries
            _Result(rows=[]),  # assets (depreciation)
            _Result(rows=[]),  # approved posted journals
        ]
    )
    lines = await build_operational_ledger(
        ledger_session,
        company_id=COMPANY_ID,
        end_exclusive=datetime(2026, 7, 19, tzinfo=UTC),
    )

    tips_lines = [line for line in lines if line.account_code == TIPS_PAYABLE.code]
    assert len(tips_lines) == 1
    assert tips_lines[0].credit_minor == 1_500
    assert tips_lines[0].debit_minor == 0
    assert tips_lines[0].ref_id == order.id
    assert tips_lines[0].account_name == "Tips Payable"

    order_lines = [line for line in lines if line.ref_id == order.id]
    assert sum(line.debit_minor for line in order_lines) == order.total_minor
    assert sum(line.credit_minor for line in order_lines) == order.total_minor
