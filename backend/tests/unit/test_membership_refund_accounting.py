"""Focused money-safety regressions for membership settlement and recovery."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.api.v1.memberships.router import (
    CashMembershipRefundSettlementRequest,
    MembershipPaymentRequestWithdrawal,
    MembershipRefundResolutionRequest,
    _capture_financial_evidence,
    _verified_takeover_attestation,
)
from app.core.errors import BusinessRuleError
from app.models import MembershipPayment, MembershipRefund, MembershipRefundSettlement
from app.services.accounting.ledger import (
    _membership_payment_ledger_lines,
    _membership_refund_ledger_lines,
)


COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("12111111-1111-1111-1111-111111111111")
TERMINAL_ID = UUID("13111111-1111-1111-1111-111111111111")
SHIFT_ID = UUID("14111111-1111-1111-1111-111111111111")
USER_ID = UUID("15111111-1111-1111-1111-111111111111")
OTHER_OWNER_ID = UUID("16111111-1111-1111-1111-111111111111")
MEMBERSHIP_ID = UUID("22111111-1111-1111-1111-111111111111")
PAYMENT_ID = UUID("23111111-1111-1111-1111-111111111111")
REFUND_ID = UUID("24111111-1111-1111-1111-111111111111")
NOW = datetime.now(UTC).replace(microsecond=0)
AMOUNT = 199_900


def _payment(*, method: str = "upi") -> MembershipPayment:
    return MembershipPayment(
        id=PAYMENT_ID,
        company_id=COMPANY_ID,
        membership_id=MEMBERSHIP_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        shift_id=SHIFT_ID,
        method=method,
        amount_minor=AMOUNT,
        paid_at=NOW,
        created_by=USER_ID,
        idempotency_key="membership:test:payment",
        receipt_no="M/MAIN/26-27/00001",
        receipt_fiscal_year="2026-27",
        receipt_issued_at=NOW,
    )


def _refund(*, method: str = "upi") -> MembershipRefund:
    return MembershipRefund(
        id=REFUND_ID,
        company_id=COMPANY_ID,
        payment_id=PAYMENT_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        shift_id=SHIFT_ID,
        method=method,
        amount_minor=AMOUNT,
        accepted_at=NOW,
        approved_by=USER_ID,
        idempotency_key="membership:test:refund",
        reason="Customer requested correction",
    )


def test_cross_owner_action_requires_durable_verified_takeover() -> None:
    with pytest.raises(BusinessRuleError, match="Unknown state must remain unresolved"):
        _verified_takeover_attestation(
            started_by=USER_ID,
            current_user_id=OTHER_OWNER_ID,
            confirmed=False,
            reason=None,
            started_by_name="Rafi",
            action_label="provider refund",
        )

    assert _verified_takeover_attestation(
        started_by=USER_ID,
        current_user_id=OTHER_OWNER_ID,
        confirmed=True,
        reason="Verified provider dashboard and original owner is unavailable",
        started_by_name="Rafi",
        action_label="provider refund",
    ) == (
        True,
        "Verified provider dashboard and original owner is unavailable",
    )

    with pytest.raises(BusinessRuleError, match="do not record a takeover"):
        _verified_takeover_attestation(
            started_by=USER_ID,
            current_user_id=USER_ID,
            confirmed=True,
            reason="Not actually a takeover",
            started_by_name="Rafi",
            action_label="cash collection",
        )


def test_resolution_contract_keeps_unknown_action_unresolved_by_default() -> None:
    payment = MembershipPaymentRequestWithdrawal(
        shift_id=SHIFT_ID,
        expected_amount_minor=AMOUNT,
        resolution="provider_not_completed",
        reason="Checked provider dashboard",
    )
    refund = MembershipRefundResolutionRequest(
        shift_id=SHIFT_ID,
        expected_amount_minor=AMOUNT,
        resolution="cash_not_handed_over",
        reason="Customer left before handover",
    )
    assert payment.action_state_verified is False
    assert payment.action_takeover_confirmed is False
    assert refund.action_state_verified is False
    assert refund.action_takeover_confirmed is False


def test_clock_skew_is_retained_as_evidence_instead_of_rolling_back_money() -> None:
    shift = SimpleNamespace(opened_at=NOW - timedelta(hours=2))
    request = SimpleNamespace(headers={})

    evidence, untrusted = _capture_financial_evidence(
        request=request,
        occurred_at=NOW + timedelta(hours=3),
        shift=shift,
        action_started_at=NOW - timedelta(minutes=2),
        server_now=NOW,
    )

    assert evidence == NOW + timedelta(hours=3)
    assert untrusted is True


def test_refund_settlement_requires_explicit_cash_handover_confirmation() -> None:
    payload = CashMembershipRefundSettlementRequest(
        shift_id=SHIFT_ID,
        expected_amount_minor=AMOUNT,
        settled_at=NOW,
        cash_handed_over=False,
    )
    assert payload.cash_handed_over is False
    assert payload.action_takeover_confirmed is False


def test_membership_ledger_uses_only_payment_and_settlement_facts() -> None:
    payment = _payment()
    sale_lines = _membership_payment_ledger_lines([payment])
    assert {(line.account_code, line.debit_minor, line.credit_minor) for line in sale_lines} == {
        ("1110", AMOUNT, 0),
        ("4250", 0, AMOUNT),
    }

    refund = _refund()
    settlement = MembershipRefundSettlement(
        id=uuid4(),
        company_id=COMPANY_ID,
        refund_id=REFUND_ID,
        payment_id=PAYMENT_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        shift_id=SHIFT_ID,
        method="upi",
        amount_minor=AMOUNT,
        settled_at=NOW,
        settled_by=USER_ID,
        idempotency_key="membership:test:refund:settlement",
        receipt_no="R/MAIN/26-27/00001",
        receipt_fiscal_year="2026-27",
        receipt_issued_at=NOW,
        external_ref="UPI-REVERSAL-123",
    )
    refund_lines = _membership_refund_ledger_lines([(settlement, refund)])
    assert {(line.account_code, line.debit_minor, line.credit_minor) for line in refund_lines} == {
        ("4250", AMOUNT, 0),
        ("1110", 0, AMOUNT),
    }
    assert sum(x.debit_minor for x in sale_lines + refund_lines) == sum(
        x.credit_minor for x in sale_lines + refund_lines
    )
