"""Retirement keeps the original semantic command separate from authorization/reason."""

from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.schemas.paid_bill_action_decision import PaidBillCancelRequest
from app.schemas.paid_bill_amendment import AmendmentCommitRequest


def command():
    return dict(
        protocol=1,
        order_id=str(uuid4()),
        operation="decrease",
        target_total_minor=6000,
        money_received="received",
        reason="Original reason",
        settlement_shift_id=None,
        refund_mode=None,
        source_revision=0,
        source_fingerprint="a" * 64,
    )


@pytest.mark.parametrize(
    "field", ["password", "confirmation", "recorded_by", "provider_settled_at"]
)
def test_cancel_cannot_persist_authorization_or_invent_payout_evidence(field):
    with pytest.raises(ValidationError):
        PaidBillCancelRequest.model_validate(
            dict(
                original_command=command(),
                cancellation_reason="Retire unresolved command",
                **{field: "forbidden"},
            )
        )


def test_cancellation_metadata_never_changes_the_original_command():
    original = command()
    value = PaidBillCancelRequest.model_validate(
        dict(original_command=original, cancellation_reason="  Retire unresolved command  ")
    )
    assert value.cancellation_reason == "Retire unresolved command"
    assert value.original_command.model_dump(mode="json") == original
    with pytest.raises(ValidationError):
        AmendmentCommitRequest.model_validate(
            {**original, "cancellation_reason": value.cancellation_reason}
        )


@pytest.mark.parametrize("reason", ["", "  ", "x" * 501, True, None])
def test_cancellation_reason_is_bounded_nonempty_text(reason):
    with pytest.raises(ValidationError):
        PaidBillCancelRequest.model_validate(
            dict(original_command=command(), cancellation_reason=reason)
        )
