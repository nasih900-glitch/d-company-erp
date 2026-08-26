"""Schema-level invariants for auditable membership money facts."""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy import CheckConstraint, UniqueConstraint
from sqlalchemy.orm.attributes import set_committed_value

from app.models import (
    CustomerMembership,
    MembershipPayment,
    MembershipPaymentAttemptResolution,
    MembershipPaymentCashCollection,
    MembershipPaymentProviderAction,
    MembershipPaymentRequest,
    MembershipPaymentRequestResolution,
    MembershipRefund,
    MembershipRefundCashHandoff,
    MembershipRefundProviderAction,
    MembershipRefundResolution,
    MembershipRefundSettlement,
)
from app.services.audit.recorder import TRACKED


def _unique_column_sets(model: type) -> set[tuple[str, ...]]:
    return {
        tuple(column.name for column in constraint.columns)
        for constraint in model.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }


def _check_constraints(model: type) -> dict[str, str]:
    return {
        constraint.name: str(constraint.sqltext)
        for constraint in model.__table__.constraints
        if isinstance(constraint, CheckConstraint) and constraint.name is not None
    }


def test_membership_money_facts_require_complete_provenance() -> None:
    for model, actor_column, time_column in (
        (MembershipPaymentRequest, "prepared_by", "accepted_at"),
        (MembershipPaymentCashCollection, "started_by", "started_at"),
        (MembershipPaymentProviderAction, "started_by", "started_at"),
        (MembershipPayment, "created_by", "paid_at"),
        (MembershipPaymentRequestResolution, "resolved_by", "resolved_at"),
        (MembershipPaymentAttemptResolution, "resolved_by", "resolved_at"),
        (MembershipRefund, "approved_by", "accepted_at"),
        (MembershipRefundCashHandoff, "started_by", "started_at"),
        (MembershipRefundProviderAction, "started_by", "started_at"),
        (MembershipRefundSettlement, "settled_by", "settled_at"),
        (MembershipRefundResolution, "resolved_by", "resolved_at"),
    ):
        table = model.__table__
        for column in (
            "company_id",
            "branch_id",
            "terminal_id",
            "shift_id",
            actor_column,
            time_column,
            "idempotency_key",
        ):
            assert table.c[column].nullable is False, f"{table.name}.{column}"
        assert model in TRACKED

    request_uniques = _unique_column_sets(MembershipPaymentRequest)
    assert ("company_id", "client_action_id") in request_uniques
    assert ("company_id", "idempotency_key") in request_uniques

    for action_model, root_column in (
        (MembershipPaymentCashCollection, "request_id"),
        (MembershipPaymentProviderAction, "request_id"),
        (MembershipPaymentRequestResolution, "request_id"),
        (MembershipRefundCashHandoff, "refund_id"),
        (MembershipRefundProviderAction, "refund_id"),
    ):
        uniques = _unique_column_sets(action_model)
        assert (root_column,) in uniques
        assert ("company_id", "idempotency_key") in uniques

    payment_uniques = _unique_column_sets(MembershipPayment)
    assert ("membership_id",) in payment_uniques
    assert ("request_id",) in payment_uniques
    assert ("company_id", "idempotency_key") in payment_uniques
    assert ("company_id", "receipt_no") in payment_uniques
    assert ("company_id", "method", "external_reference") in payment_uniques
    # The physical columns remain nullable solely so pre-0035 immutable rows
    # remain readable. Fresh/touched rows are governed by this database check.
    assert MembershipPayment.__table__.c.request_id.nullable is True
    assert MembershipPayment.__table__.c.completion_id.nullable is True
    assert _check_constraints(MembershipPayment)[
        "ck_membership_payment_workflow_linkage"
    ] == "request_id IS NOT NULL AND completion_id IS NOT NULL"

    settlement_uniques = _unique_column_sets(MembershipRefundSettlement)
    assert ("refund_id",) in settlement_uniques
    assert ("payment_id",) in settlement_uniques
    assert ("company_id", "idempotency_key") in settlement_uniques
    assert ("company_id", "receipt_no") in settlement_uniques
    assert ("company_id", "method", "external_ref") in settlement_uniques

    resolution_uniques = _unique_column_sets(MembershipRefundResolution)
    assert ("refund_id",) in resolution_uniques
    assert ("company_id", "idempotency_key") in resolution_uniques
    assert ("company_id", "paid_via", "external_reference") in resolution_uniques

    request_resolution_uniques = _unique_column_sets(
        MembershipPaymentRequestResolution
    )
    assert (
        "company_id",
        "paid_via",
        "external_reference",
    ) in request_resolution_uniques

    attempt_resolution_uniques = _unique_column_sets(
        MembershipPaymentAttemptResolution
    )
    assert (
        "company_id",
        "original_client_action_id",
    ) in attempt_resolution_uniques
    assert ("company_id", "idempotency_key") in attempt_resolution_uniques
    attempt_evidence = _check_constraints(MembershipPaymentAttemptResolution)[
        "ck_membership_attempt_resolution_evidence"
    ]
    for truthful_outcome in (
        "payment_not_collected",
        "cash_returned",
        "provider_not_completed",
        "provider_reversed",
    ):
        assert truthful_outcome in attempt_evidence

    assert "revoked_at" in CustomerMembership.__table__.c

    for model in (
        MembershipPayment,
        MembershipPaymentRequestResolution,
        MembershipRefundSettlement,
        MembershipRefundResolution,
    ):
        assert model.__table__.c.action_takeover_confirmed.nullable is False
        assert "action_takeover_reason" in model.__table__.c
    for model in (
        MembershipPaymentRequestResolution,
        MembershipRefundResolution,
    ):
        assert model.__table__.c.action_state_verified.nullable is False
    for model in (
        MembershipPayment,
        MembershipPaymentRequestResolution,
        MembershipPaymentAttemptResolution,
        MembershipRefundSettlement,
        MembershipRefundResolution,
    ):
        assert model.__table__.c.provider_evidence_reconciled.nullable is False


def test_membership_migrations_fail_closed_and_never_infer_legacy_money() -> None:
    versions = Path(__file__).parents[2] / "alembic" / "versions"
    migration_0033 = (
        versions / "0033_membership_payment_accounting.py"
    ).read_text(encoding="utf-8")
    migration_0035 = (
        versions / "0035_membership_payment_attempt_resolution.py"
    ).read_text(encoding="utf-8")

    # A schema migration cannot prove that a historical entitlement was paid
    # or that the amount was not already captured as a manual collection.
    assert "INSERT INTO membership_payments" not in migration_0033
    assert "UPDATE shifts" not in migration_0033
    assert "UPDATE customers" not in migration_0033
    assert "cannot downgrade 0033 after membership financial" in migration_0033

    assert "dcompany_guard_membership_payment_request" in migration_0035
    assert "dcompany_guard_membership_refund_request" in migration_0035
    assert "dcompany_guard_membership_payment_outcome" in migration_0035
    assert "dcompany_guard_membership_refund_outcome" in migration_0035
    assert "membership_payment_workflow_guards" in migration_0035
    assert "membership_refund_workflow_guards" in migration_0035
    assert "pg_trigger_depth() < 2" in migration_0035
    assert "UPDATE membership_payment_workflow_guards" in migration_0035
    assert "UPDATE membership_refund_workflow_guards" in migration_0035
    assert "allow_financial_history_maintenance" not in migration_0035
    assert "dcompany_guard_membership_immutable" in migration_0035
    assert "cannot downgrade 0035 after membership reservation workflow use" in migration_0035
    assert "ck_membership_payment_workflow_linkage" in migration_0035
    assert "NOT VALID" in migration_0035
    assert "new membership payment requires request and completion linkage" in migration_0035
    assert (
        "NEW.cash_collection_id IS DISTINCT FROM guard_row.action_id"
        in migration_0035
    )
    assert migration_0035.count(
        "NEW.provider_action_id IS DISTINCT FROM guard_row.action_id"
    ) == 2  # payment completion and refund completion
    assert "NEW.cash_handoff_id IS DISTINCT FROM guard_row.action_id" in migration_0035
    assert (
        "NEW.legacy_attempt_resolution_id IS DISTINCT FROM"
        in migration_0035
    )
    assert (
        'op.drop_constraint(\n        "ck_membership_payment_workflow_linkage"'
        in migration_0035
    )


def test_membership_payment_is_append_only() -> None:
    now = datetime.now(UTC)
    payment = MembershipPayment(
        id=uuid4(),
        company_id=uuid4(),
        membership_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        shift_id=uuid4(),
        method="upi",
        amount_minor=199_900,
        paid_at=now,
        created_by=uuid4(),
        idempotency_key=f"membership:{uuid4()}",
        receipt_no="M/QA/26-27/00001",
        receipt_fiscal_year="2026-27",
        receipt_issued_at=now,
    )
    set_committed_value(payment, "amount_minor", 199_900)
    payment.amount_minor = 199_800

    with pytest.raises(ValueError, match="membership payment is immutable.*amount_minor"):
        # Invoke the registered model invariant directly; the same listener is
        # called automatically by SQLAlchemy before every UPDATE flush.
        from app.models.membership import _guard_membership_payment_update

        _guard_membership_payment_update(None, None, payment)
