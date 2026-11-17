"""Exact raw-wire GRN recovery; no ordinary operational credentials."""

# Pydantic resolves these imported types at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

import json
import re
from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    StrictInt,
    StrictStr,
    field_validator,
    model_validator,
)

PROTOCOL = 1
PURPOSE = "inventory_grn_recovery"
PATH = "/api/v1/inventory/grn"
MAX_MINOR = 9_007_199_254_740_991
MAX_BODY = 131_072
MAX_QUANTITY_SCALED = 99_999_999_999_999


class ClosedModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class WireLine(ClosedModel):
    ingredient_id: UUID
    qty: StrictStr = Field(max_length=15)
    unit_cost_minor: StrictInt = Field(ge=0, le=MAX_MINOR)
    expires_at: AwareDatetime | None = None
    lot_code: StrictStr | None = Field(default=None, max_length=100)

    @field_validator("expires_at", mode="before")
    @classmethod
    def date_representation(cls, value):
        if value is not None and (
            not isinstance(value, str)
            or len(value) > 64
            or not re.search(r"(Z|[+-][0-9]{2}:[0-9]{2})$", value)
        ):
            raise ValueError("Explicit bounded timezone timestamp required")
        return value

    @field_validator("qty")
    @classmethod
    def quantity(cls, value):
        if not re.fullmatch(r"(?:0|[1-9][0-9]{0,9})(?:\.[0-9]{0,3}[1-9])?", value):
            raise ValueError("Canonical quantity required")
        whole, _, fraction = value.partition(".")
        scaled = int(whole) * 10000 + int(fraction.ljust(4, "0") or "0")
        if not 1 <= scaled <= MAX_QUANTITY_SCALED:
            raise ValueError("Quantity is outside NUMERIC(14,4)")
        return value

    @property
    def line_minor(self):
        whole, _, fraction = self.qty.partition(".")
        scaled = int(whole) * 10000 + int(fraction.ljust(4, "0") or "0")
        return (scaled * self.unit_cost_minor + 5000) // 10000


class WireBody(ClosedModel):
    branch_id: UUID
    supplier_id: UUID
    supplier_invoice_no: StrictStr | None = Field(default=None, max_length=100)
    supplier_invoice_amount_minor: StrictInt | None = Field(default=None, ge=0, le=MAX_MINOR)
    received_at: AwareDatetime | None = None
    notes: StrictStr | None = Field(default=None, max_length=500)
    lines: list[WireLine] = Field(min_length=1, max_length=200)

    @field_validator("received_at", mode="before")
    @classmethod
    def date_representation(cls, value):
        return WireLine.date_representation(value)

    @model_validator(mode="after")
    def totals(self):
        total = sum(line.line_minor for line in self.lines)
        if total > MAX_MINOR or any(line.line_minor > MAX_MINOR for line in self.lines):
            raise ValueError("Rounded purchase total is outside supported range")
        if (
            self.supplier_invoice_amount_minor is not None
            and self.supplier_invoice_amount_minor != total
        ):
            raise ValueError("Invoice must equal the sum of rounded line costs")
        return self


def parse_wire(raw: str) -> WireBody:
    if not isinstance(raw, str) or not 2 <= len(raw.encode("utf-8")) <= MAX_BODY:
        raise ValueError("Bounded original UTF8 body required")

    def unique(pairs):
        out = {}
        for key, value in pairs:
            if key in out:
                raise ValueError("Duplicate JSON field")
            out[key] = value
        return out

    data = json.loads(
        raw,
        object_pairs_hook=unique,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Finite JSON required")),
    )
    return WireBody.model_validate(data)


class RecoveryReference(ClosedModel):
    company_id: UUID
    actor_user_id: UUID
    branch_id: UUID
    terminal_id: UUID | None
    request_method: Literal["POST"] = "POST"
    request_path: Literal["/api/v1/inventory/grn"] = PATH
    request_query: Literal[""] = ""
    original_wire_json: SecretStr = Field(min_length=2, max_length=MAX_BODY)

    @model_validator(mode="after")
    def original_scope(self):
        raw = self.original_wire_json.get_secret_value()
        try:
            value = json.loads(raw)
            if (
                len(raw.encode("utf-8")) > MAX_BODY
                or not isinstance(value, dict)
                or not isinstance(value.get("branch_id"), str)
                or UUID(value["branch_id"]) != self.branch_id
            ):
                raise ValueError
        except (ValueError, TypeError, KeyError, UnicodeError) as exc:
            raise ValueError("Original branch must match") from exc
        return self


class RecoveryUnlock(RecoveryReference):
    password: SecretStr = Field(min_length=1, max_length=1024)


class RecoveryCancel(ClosedModel):
    original_wire_json: SecretStr = Field(min_length=2, max_length=MAX_BODY)
    cancellation_reason: StrictStr = Field(min_length=3, max_length=500)

    @field_validator("cancellation_reason")
    @classmethod
    def reason(cls, value):
        if value != value.strip():
            raise ValueError("Exact trimmed cancellation reason required")
        return value


class UnlockResponse(ClosedModel):
    protocol: Literal[1] = 1
    purpose: Literal["inventory_grn_recovery"] = PURPOSE
    token: str
    expires_in_seconds: Literal[300] = 300


class LockResponse(ClosedModel):
    protocol: Literal[1] = 1
    locked: Literal[True] = True


class Capabilities(ClosedModel):
    protocol: Literal[1] = 1
    purpose: Literal["inventory_grn_receive"] = "inventory_grn_receive"
    recovery_purpose: Literal["inventory_grn_recovery"] = PURPOSE
    max_body_bytes: Literal[131072] = MAX_BODY
    max_lines: Literal[200] = 200
    max_minor: Literal[9007199254740991] = MAX_MINOR


class OutcomeReference(ClosedModel):
    action_key: str
    company_id: UUID
    actor_user_id: UUID
    branch_id: UUID
    terminal_id: UUID | None
    terminal_known: Literal[True] = True
    request_method: Literal["POST"] = "POST"
    request_path: Literal["/api/v1/inventory/grn"] = PATH
    request_query: Literal[""] = ""
    request_hash: str = Field(pattern=r"^[a-f0-9]{64}$")


class AcceptedReceipt(ClosedModel):
    ok: Literal[True] = True
    id: UUID
    purchase_order_id: UUID
    batches_created: StrictInt = Field(ge=1)
    batch_ids: list[UUID]
    line_ids: list[UUID]
    total_minor: StrictInt = Field(ge=0)
    supplier_invoice_no: str | None
    journal_entry_id: UUID | None
    accounting_status: Literal["posted", "no_balance"]


class CancellationProof(ClosedModel):
    id: UUID
    decided_at: datetime
    cancellation_reason: StrictStr
    financial_effect: Literal[False] = False
    stock_effect: Literal[False] = False


class UnconfirmedOutcome(ClosedModel):
    protocol: Literal[1] = 1
    outcome: Literal["unconfirmed"]
    reference: OutcomeReference


class AcceptedOutcome(ClosedModel):
    protocol: Literal[1] = 1
    outcome: Literal["accepted"]
    reference: OutcomeReference
    proof_profile: Literal["legacy", "wire_v1"]
    accepted: AcceptedReceipt


class CancelledOutcome(ClosedModel):
    protocol: Literal[1] = 1
    outcome: Literal["cancelled"]
    reference: OutcomeReference
    cancelled: CancellationProof


Outcome = Annotated[
    UnconfirmedOutcome | AcceptedOutcome | CancelledOutcome, Field(discriminator="outcome")
]
