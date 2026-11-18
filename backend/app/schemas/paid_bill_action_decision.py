"""Additive exact-command retirement; the existing accepted-only DTO stays unchanged."""

# Pydantic resolves imported model types at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.paid_bill_amendment import (
    AmendmentAcceptance,
    AmendmentCommitRequest,
    PaidBillState,
)

PAID_BILL_ACTION_OUTCOME_PROTOCOL = 1


class StrictDecisionModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PaidBillCancelRequest(StrictDecisionModel):
    original_command: AmendmentCommitRequest
    cancellation_reason: str = Field(min_length=3, max_length=500)

    @field_validator("cancellation_reason")
    @classmethod
    def normalized_reason(cls, value: str) -> str:
        value = value.strip()
        if len(value) < 3:
            raise ValueError("Explain why this exact saved command should be retired")
        return value


class PaidBillActionReference(StrictDecisionModel):
    action_key: str
    company_id: UUID
    actor_user_id: UUID
    branch_id: UUID
    terminal_id: UUID
    order_id: UUID
    request_path: Literal["/api/v1/pos/paid-bill-amendments/commit"]
    request_hash: str = Field(pattern=r"^[a-f0-9]{64}$")
    original_command: AmendmentCommitRequest


class PaidBillCancellationReceipt(StrictDecisionModel):
    id: UUID
    cancelled_at: str
    cancellation_reason: str
    financial_effect: Literal[False]
    stock_effect: Literal[False]


class PaidBillAcceptedOutcome(StrictDecisionModel):
    protocol: Literal[1] = 1
    outcome: Literal["accepted"] = "accepted"
    reference: PaidBillActionReference
    accepted: AmendmentAcceptance
    current: PaidBillState


class PaidBillCancelledOutcome(StrictDecisionModel):
    protocol: Literal[1] = 1
    outcome: Literal["cancelled"] = "cancelled"
    reference: PaidBillActionReference
    cancelled: PaidBillCancellationReceipt


class PaidBillUnconfirmedOutcome(StrictDecisionModel):
    protocol: Literal[1] = 1
    outcome: Literal["unconfirmed"] = "unconfirmed"
    action_key: str
    company_id: UUID
    actor_user_id: UUID
    branch_id: UUID
    terminal_id: UUID


PaidBillOutcome = Annotated[
    PaidBillAcceptedOutcome | PaidBillCancelledOutcome | PaidBillUnconfirmedOutcome,
    Field(discriminator="outcome"),
]
