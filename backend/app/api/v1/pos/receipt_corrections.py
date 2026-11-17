"""Protected Owner review and committed no-payout receipt corrections."""

# FastAPI resolves dependency and DTO annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, BackgroundTasks, Depends, Header
from pydantic import BaseModel, ConfigDict

from app.api.v1.finance.tender_corrections import TenderRoute, require_protected_owner
from app.core.db import SessionDep
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.services.pos.receipt_corrections import (
    ReceiptCorrectionCommit,
    ReceiptStockCorrectionCommit,
    commit_receipt_correction,
    load_review_source,
    review_source,
)
from app.services.realtime import manager as realtime_manager


class ReceiptCorrectionRoute(TenderRoute):
    # Reuse the audited complete-transaction/deferred-commit retry boundary.
    source_conflict_message = (
        "The receipt source or saved action no longer matches. "
        "Keep the saved action and review its receipt before retrying."
    )


router = APIRouter(route_class=ReceiptCorrectionRoute)
CorrectionOwner = Annotated[TenantContext, Depends(requires("finance.receipt.correct"))]


class ReceiptCorrectionPreviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    order_id: UUID


class ReceiptCorrectionPreviewLine(BaseModel):
    line_id: UUID
    name: str
    quantity: str
    total_minor: int


class ReceiptCorrectionPreview(BaseModel):
    kind: Literal["payment_not_received_full_mistake"] = "payment_not_received_full_mistake"
    order_id: UUID
    invoice_no: str | None
    payment_id: UUID | None
    amount_minor: int
    eligible: bool
    blockers: list[str]
    source_fingerprint: str
    lines: list[ReceiptCorrectionPreviewLine]
    financial_effect: Literal[False] = False
    payout_minor: Literal[0] = 0
    commit_available: Literal[True] = True


@router.post("/preview", response_model=ReceiptCorrectionPreview)
async def preview(
    payload: ReceiptCorrectionPreviewRequest,
    session: SessionDep,
    tenant: CorrectionOwner,
) -> ReceiptCorrectionPreview:
    await require_protected_owner(session, tenant)
    captured = await load_review_source(session, tenant, payload.order_id)
    source = captured.snapshot
    review = review_source(source, fingerprint=captured.fingerprint)
    return ReceiptCorrectionPreview(
        order_id=payload.order_id,
        invoice_no=source["order"]["invoice_no"],
        payment_id=source["payments"][0]["id"] if len(source["payments"]) == 1 else None,
        amount_minor=review.amount_minor,
        eligible=review.eligible,
        blockers=list(review.blockers),
        source_fingerprint=review.source_fingerprint,
        lines=[
            ReceiptCorrectionPreviewLine(
                line_id=line["id"],
                name=line["menu_item_name_snapshot"],
                quantity=str(line["qty"]),
                total_minor=line["line_total_minor"],
            )
            for line in source["lines"]
        ],
    )


class ReceiptCorrectionResult(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID
    order_id: UUID
    payment_id: UUID
    original_shift_id: UUID
    invoice_no: str
    kind: Literal["payment_not_received_full_mistake"]
    amount_minor: int
    accounting_at: datetime
    payout_minor: Literal[0]
    original_records_unchanged: Literal[True]


@router.post("/commit", response_model=ReceiptCorrectionResult, status_code=201)
async def commit(
    payload: ReceiptCorrectionCommit,
    session: SessionDep,
    tenant: CorrectionOwner,
    background_tasks: BackgroundTasks,
    idempotency_key: Annotated[str, Header(min_length=1, max_length=160)],
) -> dict:
    # SessionDep teardown commits before 2xx; the route retries complete fresh
    # transactions only for deadlocks/serialization failures, same key/body.
    result = await commit_receipt_correction(
        session, tenant, payload=payload, idempotency_key=idempotency_key
    )
    for resource in ("orders", "receipts", "shifts", "reports", "finance", "audit"):
        background_tasks.add_task(realtime_manager.broadcast, tenant.company_id, resource)
    return result


class ReceiptStockMovementPreview(BaseModel):
    movement_id: UUID
    batch_id: UUID
    ingredient_id: UUID
    ingredient_name: str
    base_unit: Literal["ml", "g", "unit"]
    quantity: str
    cost_per_unit_minor: int


class ReceiptStockCorrectionPreview(ReceiptCorrectionPreview):
    stock_fingerprint: str
    stock_movements: list[ReceiptStockMovementPreview]
    inventory_increase_minor: int


@router.post("/stock/preview", response_model=ReceiptStockCorrectionPreview)
async def preview_stock(
    payload: ReceiptCorrectionPreviewRequest, session: SessionDep, tenant: CorrectionOwner
):
    from app.services.pos.receipt_stock import load_stock_source, review_stock_source

    await require_protected_owner(session, tenant)
    captured = await load_review_source(session, tenant, payload.order_id)
    snapshot, fingerprint = await load_stock_source(session, tenant, payload.order_id)
    review = review_stock_source(snapshot)
    source = captured.snapshot
    return ReceiptStockCorrectionPreview(
        order_id=payload.order_id,
        invoice_no=source["order"]["invoice_no"],
        payment_id=source["payments"][0]["id"] if len(source["payments"]) == 1 else None,
        amount_minor=source["order"]["total_minor"],
        eligible=not review.blockers,
        blockers=list(review.blockers),
        source_fingerprint=captured.fingerprint,
        lines=[
            ReceiptCorrectionPreviewLine(
                line_id=line["id"],
                name=line["menu_item_name_snapshot"],
                quantity=str(line["qty"]),
                total_minor=line["line_total_minor"],
            )
            for line in source["lines"]
        ],
        stock_fingerprint=fingerprint,
        stock_movements=list(review.movements),
        inventory_increase_minor=review.inventory_increase_minor,
    )


class ReceiptStockCorrectionResult(ReceiptCorrectionResult):
    stock_restored: Literal[True]
    restored_movement_count: int
    inventory_increase_minor: int


@router.post("/stock/commit", response_model=ReceiptStockCorrectionResult, status_code=201)
async def commit_stock(
    payload: ReceiptStockCorrectionCommit,
    session: SessionDep,
    tenant: CorrectionOwner,
    background_tasks: BackgroundTasks,
    idempotency_key: Annotated[str, Header(min_length=1, max_length=160)],
    x_tender_token: Annotated[str | None, Header()] = None,
):
    result = await commit_receipt_correction(
        session,
        tenant,
        payload=payload,
        idempotency_key=idempotency_key,
        confirmation_token=x_tender_token,
    )
    for resource in ("orders", "receipts", "shifts", "reports", "finance", "audit", "inventory"):
        background_tasks.add_task(realtime_manager.broadcast, tenant.company_id, resource)
    return result
