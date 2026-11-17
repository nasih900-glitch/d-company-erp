"""Record and review branch-scoped observations about finalized POS receipts."""

# FastAPI resolves dependency and response annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Query
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select, tuple_

from app.api.v1.finance.tender_corrections import require_protected_owner
from app.api.v1.pos.router import load_finalized_receipt_order
from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, IdempotencyConflict, NotFoundError, RateLimitError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import PosReceiptMistakeReport, User
from app.services.bug_reports.sanitization import sanitize_bug_report_text

router = APIRouter()
Reporter = Annotated[TenantContext, Depends(requires("pos.read", "pos.write"))]
ReviewOwner = Annotated[TenantContext, Depends(requires("finance.receipt.correct"))]
MistakeReason = Literal["wrong_item", "wrong_payment_method", "no_funds_received", "other"]
_MAX_REPORTS_PER_HOUR = 20


class MistakeReportRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    reason: MistakeReason
    note: str | None = Field(default=None, max_length=500)


class MistakeReportAcknowledgement(BaseModel):
    id: UUID
    order_id: UUID
    created_at: datetime


class MistakeReportRead(MistakeReportAcknowledgement):
    model_config = ConfigDict(from_attributes=True)

    invoice_no: str
    reporter_user_id: UUID
    reporter_name: str
    reason: MistakeReason
    note: str | None


class MistakeReportPage(BaseModel):
    items: list[MistakeReportRead]
    next_before: UUID | None = None


def _clean_note(note: str | None) -> str | None:
    # Remove formatting controls before redacting credentials, then flatten the display text.
    text = "".join(char for char in (note or "") if char.isprintable() or char.isspace())
    return " ".join(sanitize_bug_report_text(text).split())[:500] or None


@router.post(
    "/receipts/{order_id}/mistake-reports",
    status_code=201,
    response_model=MistakeReportAcknowledgement,
    summary="Submit a report for one finalized receipt",
)
async def report_receipt_mistake(
    order_id: UUID,
    body: MistakeReportRequest,
    session: SessionDep,
    tenant: Reporter,
    idempotency_key: Annotated[UUID, Header(alias="Idempotency-Key")],
) -> MistakeReportAcknowledgement:
    order = await load_finalized_receipt_order(order_id, session, tenant)
    # Compare the original decoded content, preserving whitespace and omitted fields.
    request = [str(order.id), body.model_dump(exclude_unset=True)]
    request_hash = hashlib.sha256(json.dumps(request, sort_keys=True).encode("utf-8")).hexdigest()
    report_type = PosReceiptMistakeReport
    lock_key = f"pos-receipt-mistake:{order.company_id}:{order.branch_id}:{tenant.user_id}"
    await session.execute(select(func.pg_advisory_xact_lock(func.hashtextextended(lock_key, 0))))
    reporter_scope = (
        report_type.company_id == tenant.company_id,
        report_type.branch_id == order.branch_id,
        report_type.reporter_user_id == tenant.user_id,
    )
    report = await session.scalar(
        select(report_type).where(
            *reporter_scope,
            report_type.idempotency_key == idempotency_key,
        )
    )
    if report is None:
        cutoff = datetime.now(UTC) - timedelta(hours=1)
        recent_count = await session.scalar(
            select(func.count(report_type.id)).where(
                *reporter_scope, report_type.created_at >= cutoff
            )
        )
        if recent_count >= _MAX_REPORTS_PER_HOUR:
            raise RateLimitError(
                "Too many receipt mistake reports from this account. Wait before sending another.",
                details={"limit": _MAX_REPORTS_PER_HOUR, "window_seconds": 3600},
            )
        report = report_type(
            company_id=order.company_id,
            branch_id=order.branch_id,
            order_id=order.id,
            original_shift_id=order.shift_id,
            original_terminal_id=order.terminal_id,
            reporter_user_id=tenant.user_id,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
            invoice_no=order.invoice_no,
            reason=body.reason,
            note=_clean_note(body.note),
        )
        session.add(report)
        await session.flush()
    elif report.request_hash != request_hash:
        raise IdempotencyConflict("This report key was already used for different details.")
    return MistakeReportAcknowledgement(
        id=report.id, order_id=report.order_id, created_at=report.created_at
    )


@router.get(
    "/receipt-mistake-reports",
    response_model=MistakeReportPage,
    summary="Review this branch's receipt mistake reports as protected Owner",
)
async def list_receipt_mistake_reports(
    session: SessionDep,
    tenant: ReviewOwner,
    before: UUID | None = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 30,
) -> MistakeReportPage:
    await require_protected_owner(
        session,
        tenant,
        denied_message="Only the designated protected owner may review receipt mistake reports.",
    )
    if tenant.branch_id is None:
        raise BusinessRuleError("Assign a branch before viewing receipt mistake reports.")
    report = PosReceiptMistakeReport
    scope = (report.company_id == tenant.company_id, report.branch_id == tenant.branch_id)
    stmt = (
        select(report, User.name)
        .join(User, (User.id == report.reporter_user_id) & (User.company_id == report.company_id))
        .where(*scope)
        .order_by(report.created_at.desc(), report.id.desc())
        .limit(limit + 1)
    )
    if before is not None:
        anchor = await session.scalar(select(report).where(*scope, report.id == before))
        if anchor is None:
            raise NotFoundError("Report cursor not found for this branch.")
        stmt = stmt.where(
            tuple_(report.created_at, report.id) < tuple_(anchor.created_at, anchor.id)
        )
    rows = (await session.execute(stmt)).all()
    items = [
        MistakeReportRead(
            id=row.id,
            order_id=row.order_id,
            created_at=row.created_at,
            invoice_no=row.invoice_no,
            reporter_user_id=row.reporter_user_id,
            reporter_name=reporter_name,
            reason=row.reason,
            note=row.note,
        )
        for row, reporter_name in rows[:limit]
    ]
    return MistakeReportPage(items=items, next_before=items[-1].id if len(rows) > limit else None)
