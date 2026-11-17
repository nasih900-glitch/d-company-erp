"""Cold exact-command recovery, never ordinary session establishment."""

# Pydantic resolves imported model types at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from typing import Literal
from uuid import UUID

from pydantic import Field, SecretStr, model_validator

from app.schemas.paid_bill_action_decision import StrictDecisionModel
from app.schemas.paid_bill_amendment import AmendmentCommitRequest

PAID_BILL_HISTORICAL_RECOVERY_PROTOCOL = 1
PURPOSE = "paid_bill_action_recovery"


class RecoveryUnlockRequest(StrictDecisionModel):
    company_id: UUID
    actor_user_id: UUID
    branch_id: UUID
    terminal_id: UUID
    order_id: UUID
    request_path: Literal["/api/v1/pos/paid-bill-amendments/commit"]
    original_command: AmendmentCommitRequest
    password: SecretStr = Field(min_length=1, max_length=1024)

    @model_validator(mode="after")
    def exact_order(self):
        if self.original_command.order_id != self.order_id:
            raise ValueError("The original command and receipt must match")
        return self


class RecoveryUnlockResponse(StrictDecisionModel):
    protocol: Literal[1] = 1
    purpose: Literal["paid_bill_action_recovery"] = PURPOSE
    token: str
    expires_in_seconds: Literal[300] = 300


class RecoveryLockResponse(StrictDecisionModel):
    protocol: Literal[1] = 1
    locked: Literal[True] = True
