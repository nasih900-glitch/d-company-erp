"""Schema and append-only invariants for recoverable POS refunds."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from sqlalchemy import UniqueConstraint
from sqlalchemy.orm.attributes import set_committed_value

from app.models import (
    CustomerSpendReconciliation,
    Order,
    PosRefundCashHandoff,
    PosRefundCashHandoffCompletion,
    PosRefundEvidenceReconciliation,
    PosRefundProviderPayoutStart,
    PosRefundProviderSettlement,
    PosRefundRequest,
    PosRefundWithdrawal,
    Refund,
)
from app.services.audit.recorder import TRACKED


def _unique_column_sets(model: type) -> set[tuple[str, ...]]:
    return {
        tuple(column.name for column in constraint.columns)
        for constraint in model.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }


def test_pos_refund_request_and_resolution_facts_are_shift_bound() -> None:
    for model, actor, occurred_at in (
        (PosRefundRequest, "approved_by", "accepted_at"),
        (PosRefundCashHandoff, "started_by", "started_at"),
        (PosRefundCashHandoffCompletion, "recorded_by", "recorded_at"),
        (PosRefundProviderPayoutStart, "started_by", "started_at"),
        (PosRefundProviderSettlement, "settled_by", "provider_settled_at"),
        (PosRefundWithdrawal, "withdrawn_by", "withdrawn_at"),
    ):
        table = model.__table__
        for column in (
            "company_id",
            "branch_id",
            "terminal_id",
            "shift_id",
            actor,
            occurred_at,
            "idempotency_key",
        ):
            assert table.c[column].nullable is False, f"{table.name}.{column}"
        assert model in TRACKED

    request_uniques = _unique_column_sets(PosRefundRequest)
    assert ("company_id", "idempotency_key") in request_uniques
    assert ("company_id", "client_action_id") in request_uniques
    provider_uniques = _unique_column_sets(PosRefundProviderSettlement)
    assert ("refund_request_id",) in provider_uniques
    assert ("company_id", "idempotency_key") in provider_uniques
    # Provider references may be short or reused by the external rail. Once
    # value moved, both facts must be retained and queued for evidence review.
    assert (
        "company_id",
        "settlement_method",
        "external_reference",
    ) not in provider_uniques
    assert PosRefundProviderSettlement.__table__.c.captured_time_reconciled.nullable is False
    assert (
        PosRefundProviderSettlement.__table__.c.provider_evidence_reconciled.nullable
        is False
    )

    handoff_uniques = _unique_column_sets(PosRefundCashHandoff)
    assert ("refund_request_id",) in handoff_uniques
    cash_completion_uniques = _unique_column_sets(PosRefundCashHandoffCompletion)
    assert ("refund_request_id",) in cash_completion_uniques
    assert ("company_id", "idempotency_key") in cash_completion_uniques
    assert PosRefundCashHandoffCompletion.__table__.c.handed_over_at.nullable is False
    assert (
        PosRefundCashHandoffCompletion.__table__.c.captured_time_reconciled.nullable
        is False
    )
    provider_start_uniques = _unique_column_sets(PosRefundProviderPayoutStart)
    assert ("refund_request_id",) in provider_start_uniques
    assert ("company_id", "idempotency_key") in provider_start_uniques
    withdrawal_uniques = _unique_column_sets(PosRefundWithdrawal)
    assert ("refund_request_id",) in withdrawal_uniques


def test_refund_keeps_legacy_columns_nullable_but_new_settlements_unique() -> None:
    table = Refund.__table__
    for legacy_nullable in (
        "request_id",
        "company_id",
        "branch_id",
        "terminal_id",
        "settlement_shift_id",
        "settled_at",
        "settled_by",
        "settlement_idempotency_key",
        "receipt_no",
        "customer_spend_reconciled",
        "client_occurred_at",
        "captured_time_reconciled",
        "provider_evidence_reconciled",
    ):
        assert table.c[legacy_nullable].nullable is True
    uniques = _unique_column_sets(Refund)
    assert ("request_id",) in uniques
    assert ("company_id", "settlement_idempotency_key") in uniques
    assert ("company_id", "receipt_no") in uniques
    assert Refund in TRACKED
    assert Order.__table__.c.customer_id.nullable is True
    linkage = next(
        constraint
        for constraint in table.constraints
        if constraint.name == "ck_refund_forward_write_linkage"
    )
    assert str(linkage.sqltext) == "request_id IS NOT NULL"
    assert linkage.dialect_options["postgresql"]["not_valid"] is True


def test_customer_spend_reconciliation_is_append_only_and_source_unique() -> None:
    table = CustomerSpendReconciliation.__table__
    assert CustomerSpendReconciliation in TRACKED
    assert table.c.customer_id.nullable is False
    assert table.c.reconciled_by.nullable is False
    assert table.c.reconciled_at.nullable is False
    assert table.c.source_reconciliation_state.nullable is False
    uniques = _unique_column_sets(CustomerSpendReconciliation)
    assert ("pos_refund_id",) in uniques
    assert ("membership_refund_settlement_id",) in uniques
    assert ("company_id", "idempotency_key") in uniques


def test_pos_refund_evidence_reconciliation_is_append_only_and_kind_unique() -> None:
    table = PosRefundEvidenceReconciliation.__table__
    assert PosRefundEvidenceReconciliation in TRACKED
    assert table.c.refund_id.nullable is False
    assert table.c.evidence_kind.nullable is False
    assert table.c.proof_reference.nullable is False
    assert table.c.reconciled_by.nullable is False
    assert table.c.reconciled_at.nullable is False
    uniques = _unique_column_sets(PosRefundEvidenceReconciliation)
    assert ("refund_id", "evidence_kind") in uniques
    assert ("company_id", "idempotency_key") in uniques


def test_pos_refund_request_is_append_only() -> None:
    now = datetime.now(UTC)
    row = PosRefundRequest(
        id=uuid4(),
        company_id=uuid4(),
        order_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        shift_id=uuid4(),
        approved_by=uuid4(),
        reason_code="CUSTOMER_REQUEST",
        amount_minor=1_000,
        mode="cash",
        settlement_method="cash",
        order_paid_snapshot_minor=2_000,
        order_refundable_snapshot_minor=2_000,
        accepted_at=now,
        client_action_id=f"refund:{uuid4()}",
        idempotency_key=f"refund:{uuid4()}",
    )
    set_committed_value(row, "amount_minor", 1_000)
    row.amount_minor = 900

    from app.models.pos import _guard_pos_refund_request_update

    with pytest.raises(ValueError, match="POS refund request is immutable.*amount_minor"):
        _guard_pos_refund_request_update(None, None, row)
