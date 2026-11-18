"""Strict money-only commands; confirmation credentials are not business payloads."""

# Pydantic resolves schema types at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator, model_validator

PAID_BILL_AMENDMENT_PROTOCOL = 1


class AmendmentPreviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol: StrictInt = Field(default=1, ge=1, le=1)
    order_id: UUID
    operation: Literal["decrease", "discard"]
    target_total_minor: StrictInt = Field(ge=0, le=9_000_000_000_000_000)
    money_received: Literal["received", "not_received", "unknown"]
    reason: str = Field(min_length=3, max_length=500)
    settlement_shift_id: UUID | None = None
    refund_mode: Literal["cash", "original"] | None = None

    @field_validator("reason")
    @classmethod
    def clean_reason(cls, value):
        value = value.strip()
        if len(value) < 3:
            raise ValueError("Explain why the paid bill needs changing")
        return value

    @model_validator(mode="after")
    def exact_context(self):
        if self.operation == "discard" and self.target_total_minor != 0:
            raise ValueError("Discard must target zero")
        if (self.settlement_shift_id is None) != (self.refund_mode is None):
            raise ValueError("Choose the settlement shift and refund mode together")
        return self


class AmendmentCommitRequest(AmendmentPreviewRequest):
    source_revision: StrictInt = Field(ge=0)
    source_fingerprint: str = Field(pattern=r"^[0-9a-f]{64}$")


class AmendmentAcceptance(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID
    order_id: UUID
    revision: int
    refund_request_id: UUID | None
    target_total_minor: int
    status: Literal["pending_settlement", "discarded"]
    financial_effect: Literal[False]


class AmendmentPreview(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol: Literal[1]
    order_id: UUID
    invoice_no: str | None
    source_revision: int
    source_fingerprint: str
    original_total_minor: int
    actual_refunded_minor: int
    effective_total_minor: int
    proposed_total_minor: int
    proposed_refund_minor: int
    eligible: bool
    commit_available: bool
    blockers: list[str]
    settlement_shift_ids: list[UUID]
    refund_modes: list[Literal["cash", "original"]]
    settlement_shift_id: UUID | None
    refund_mode: Literal["cash", "original"] | None
    financial_effect: Literal[False]
    stock_effect: Literal[False]


AmendmentStatus = Literal[
    "original",
    "corrected_no_payment",
    "pending_settlement",
    "discarded",
    "settled",
    "withdrawn",
    "physical_completed_accounting_pending",
    "payout_in_progress_outcome_unknown",
]


class AmendmentHistory(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID
    revision: int
    operation: Literal["decrease", "discard"]
    target_minor: int
    refund_request_id: UUID | None
    actual_refund_id: UUID | None
    physical_completed_pending_accounting_minor: int
    payout_remaining_minor: int | None
    status: AmendmentStatus


class PaidBillState(BaseModel):
    model_config = ConfigDict(extra="forbid")
    protocol: Literal[1]
    order_id: UUID
    invoice_no: str | None
    original_total_minor: int
    actual_refunded_minor: int
    no_value_corrected_minor: int
    effective_total_minor: int
    source_revision: int
    proposed_total_minor: int | None
    status: AmendmentStatus
    refund_request_id: UUID | None
    history: list[AmendmentHistory]
    physical_completed_pending_accounting_minor: int
    payout_remaining_minor: int | None
    stock_effect: Literal[False]


class AmendmentActionRead(BaseModel):
    model_config = ConfigDict(extra="forbid")
    accepted: AmendmentAcceptance
    current: PaidBillState


class PaidBillReceiptSummary(BaseModel):
    """Ordinary receipt visibility, excluding Owner review and recovery history."""

    model_config = ConfigDict(extra="forbid")
    protocol: Literal[1]
    original_total_minor: int
    actual_refunded_minor: int
    effective_total_minor: int
    proposed_total_minor: int | None
    status: AmendmentStatus
    refund_request_id: UUID | None
    physical_completed_pending_accounting_minor: int
    payout_remaining_minor: int | None
    stock_effect: Literal[False]
