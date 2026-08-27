"""Shared POS response schemas used by receipts and Kitchen."""

from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, Field


class OrderVariantSnapshotRead(BaseModel):
    variant_id: UUID
    name: str
    price_delta_minor: int
    line_delta_minor: int


class OrderModifierSnapshotRead(BaseModel):
    modifier_id: UUID
    modifier_group_id: UUID
    group_name: str
    name: str
    qty: int = Field(ge=1)
    price_delta_minor: int
    per_item_delta_minor: int
    line_delta_minor: int
