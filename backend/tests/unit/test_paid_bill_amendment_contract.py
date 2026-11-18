"""Reject commands that could turn a money-only intent into a different financial writer."""

from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.schemas.paid_bill_amendment import AmendmentPreviewRequest


def command():
    return {
        "order_id": str(uuid4()),
        "operation": "decrease",
        "target_total_minor": 6000,
        "money_received": "received",
        "reason": "Paid amount entered incorrectly",
    }


@pytest.mark.parametrize("value", [True, False, "6000", 6000.0, -1])
def test_target_is_a_strict_nonnegative_integer(value):
    with pytest.raises(ValidationError):
        AmendmentPreviewRequest.model_validate({**command(), "target_total_minor": value})


@pytest.mark.parametrize(
    "field",
    [
        "password",
        "confirmation_token",
        "approved_by",
        "amount_minor",
        "line_price_minor",
        "tax_minor",
        "restore_stock",
        "provider_settled_at",
    ],
)
def test_command_cannot_smuggle_credentials_approver_or_other_financial_effect(field):
    with pytest.raises(ValidationError):
        AmendmentPreviewRequest.model_validate({**command(), field: "forbidden"})


def test_discard_and_settlement_selection_have_exact_semantics():
    with pytest.raises(ValidationError):
        AmendmentPreviewRequest.model_validate({**command(), "operation": "discard"})
    with pytest.raises(ValidationError):
        AmendmentPreviewRequest.model_validate({**command(), "settlement_shift_id": str(uuid4())})


@pytest.mark.parametrize("version", [True, False, 2, "1"])
def test_command_protocol_rejects_boolean_unknown_and_coerced_versions(version):
    with pytest.raises(ValidationError):
        AmendmentPreviewRequest.model_validate({**command(), "protocol": version})
