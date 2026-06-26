"""OCR endpoints — upload, list verification queue, approve/reject."""

from __future__ import annotations

import hashlib
from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, File, Form, UploadFile, status
from pydantic import BaseModel
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import NotFoundError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import OcrExtraction, OcrUpload, OcrVerification

router = APIRouter()


class UploadResponse(BaseModel):
    id: UUID
    sha256: str
    byte_size: int


class ExtractionRead(BaseModel):
    id: UUID
    vendor_name: str | None
    invoice_no: str | None
    invoice_date: datetime | None
    amount_minor: int | None
    status: str


class VerificationDecision(BaseModel):
    decision: Literal["approve", "reject", "edit"]
    edits: dict | None = None
    notes: str | None = None


@router.post(
    "/uploads",
    response_model=UploadResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Upload a receipt / invoice image for OCR processing",
)
async def upload_receipt(
    session: SessionDep,
    file: UploadFile = File(...),
    branch_id: UUID = Form(...),
    source: Literal["manual", "drive", "whatsapp", "telegram"] = Form("manual"),
    tenant: TenantContext = Depends(requires("ocr.upload")),
) -> UploadResponse:
    body = await file.read()
    sha = hashlib.sha256(body).hexdigest()
    # NOTE: in production, body goes to object storage and we only store the key.
    storage_key = f"ocr/{tenant.company_id}/{sha}/{file.filename}"
    upload = OcrUpload(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=branch_id,
        uploaded_by=tenant.user_id,
        storage_key=storage_key,
        mime=file.content_type or "application/octet-stream",
        sha256=sha,
        byte_size=len(body),
        source=source,
    )
    session.add(upload)
    # Background worker enqueues OCR job; for now we insert a stub extraction.
    session.add(
        OcrExtraction(
            id=uuid4(),
            ocr_upload_id=upload.id,
            status="parsed",
            confidence={"vendor": 0.0, "amount": 0.0, "date": 0.0},
        )
    )
    return UploadResponse(id=upload.id, sha256=sha, byte_size=len(body))


@router.get("/queue", response_model=list[ExtractionRead])
async def verification_queue(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("ocr.verify")),
) -> list[ExtractionRead]:
    """All active extractions for this company — parsed, needs_review, plus
    recently approved/rejected so the cashier sees the history of uploads.
    Joined back to OcrUpload to enforce company scoping.
    """
    from app.models import OcrUpload  # local import — avoids top-level cycle
    rows = (
        await session.execute(
            select(OcrExtraction)
            .join(OcrUpload, OcrUpload.id == OcrExtraction.ocr_upload_id)
            .where(OcrUpload.company_id == tenant.company_id)
            .order_by(OcrExtraction.created_at.desc())
            .limit(100)
        )
    ).scalars().all()
    return [
        ExtractionRead(
            id=r.id,
            vendor_name=r.vendor_name,
            invoice_no=r.invoice_no,
            invoice_date=r.invoice_date,
            amount_minor=r.amount_minor,
            status=r.status,
        )
        for r in rows
    ]


@router.post("/extractions/{extraction_id}/verify")
async def verify_extraction(
    extraction_id: UUID,
    payload: VerificationDecision,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("ocr.verify")),
) -> dict:
    ex = await session.get(OcrExtraction, extraction_id)
    if not ex:
        raise NotFoundError("extraction not found")
    v = OcrVerification(
        id=uuid4(),
        ocr_extraction_id=extraction_id,
        reviewed_by=tenant.user_id,
        decision=payload.decision,
        edits=payload.edits,
        reviewed_at=datetime.now(timezone.utc),
        notes=payload.notes,
    )
    session.add(v)
    if payload.decision == "approve":
        ex.status = "approved"
    elif payload.decision == "reject":
        ex.status = "rejected"
    return {"id": str(v.id), "extraction_status": ex.status}
