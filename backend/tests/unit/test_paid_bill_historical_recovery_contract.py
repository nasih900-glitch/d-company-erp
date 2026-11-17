"""The cold credential is neither an ordinary auth token nor an audited business row."""

from __future__ import annotations

from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.core.security import decode_token, issue_paid_bill_recovery_token
from app.models import PaidBillRecoverySession
from app.schemas.paid_bill_recovery import RecoveryUnlockRequest
from app.services.audit.recorder import TRACKED


def body():
    order = str(uuid4())
    return {
        "company_id": str(uuid4()),
        "actor_user_id": str(uuid4()),
        "branch_id": str(uuid4()),
        "terminal_id": str(uuid4()),
        "order_id": order,
        "request_path": "/api/v1/pos/paid-bill-amendments/commit",
        "password": "RAM_ONLY_PASSWORD",
        "original_command": {
            "protocol": 1,
            "order_id": order,
            "operation": "decrease",
            "target_total_minor": 1,
            "money_received": "received",
            "reason": "Mistaken original amount",
            "settlement_shift_id": str(uuid4()),
            "refund_mode": "cash",
            "source_revision": 0,
            "source_fingerprint": "a" * 64,
        },
    }


def test_password_repr_and_json_contract_do_not_make_operational_claims():
    parsed = RecoveryUnlockRequest.model_validate(body())
    assert "RAM_ONLY_PASSWORD" not in repr(parsed)
    assert parsed.password.get_secret_value() == "RAM_ONLY_PASSWORD"
    assert PaidBillRecoverySession not in TRACKED
    assert not any(c.foreign_keys for c in PaidBillRecoverySession.__table__.columns)
    assert not any(
        x in PaidBillRecoverySession.__table__.columns
        for x in ["password", "token", "action_key", "request_body"]
    )


@pytest.mark.parametrize("change", ["path", "order", "extra", "array_operation"])
def test_strict_original_scope_body(change):
    value = body()
    if change == "path":
        value["request_path"] = "/api/v1/auth/login"
    elif change == "order":
        value["order_id"] = str(uuid4())
    elif change == "extra":
        value["request_hash"] = "client-supplied"
    else:
        value["original_command"]["operation"] = ["decrease"]
    with pytest.raises(ValidationError):
        RecoveryUnlockRequest.model_validate(value)


def test_recovery_issuer_cannot_be_turned_into_access_or_refresh():
    claims = decode_token(
        issue_paid_bill_recovery_token({"sub": str(uuid4()), "type": "access", "purpose": "login"})
    )
    assert claims["type"] == "paid_bill_recovery"
    assert claims["purpose"] == "paid_bill_action_recovery"
