"""Cash-capacity contract: clearing receivables are not partner-spendable cash."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

from app.api.v1.finance.router import _cash_position_from_ledger
from app.services.accounting import LedgerLine
from app.services.reports.business_metrics import compute_distributable_capacity


def _line(code: str, *, debit: int = 0, credit: int = 0) -> LedgerLine:
    return LedgerLine(
        occurred_at=datetime.now(UTC),
        ref_type="test",
        ref_id=uuid4(),
        account_code=code,
        account_name=f"Account {code}",
        account_type="asset",
        debit_minor=debit,
        credit_minor=credit,
    )


def test_cash_position_separates_spendable_bank_cash_from_clearing_value() -> None:
    position, legacy_liquid = _cash_position_from_ledger(
        [
            _line("1000", debit=20_000),
            _line("1000", credit=5_000),
            _line("1010", debit=30_000),
            _line("1100", debit=40_000),
            _line("1110", debit=50_000),
            _line("1120", debit=60_000),
            _line("1210", debit=70_000),
            _line("1185", debit=80_000),
            _line("1190", debit=90_000),
        ]
    )

    assert position.cash_on_hand_minor == 15_000
    assert position.bank_balance_minor == 30_000
    assert position.spendable_cash_bank_minor == 45_000
    assert position.card_clearing_minor == 40_000
    assert position.upi_qr_clearing_minor == 50_000
    assert position.wallet_clearing_minor == 60_000
    assert position.pos_settlement_clearing_minor == 70_000
    assert position.settlement_receivables_minor == 220_000
    assert position.reconciliation_only_minor == 170_000
    # Compatibility preserves the historic cash + bank + UPI definition only.
    assert legacy_liquid == 95_000

    capacity = compute_distributable_capacity(
        lifetime_net_profit_minor=1_000_000,
        lifetime_withdrawn_minor=0,
        avg_monthly_cost_minor=0,
        reserve_months=6,
        liquid_cash_minor=position.spendable_cash_bank_minor,
    )
    assert capacity.cash_based_capacity_minor == 45_000
    assert capacity.safe_to_distribute_minor == 45_000
