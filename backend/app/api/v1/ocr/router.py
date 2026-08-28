"""OCR endpoints — upload, list verification queue, approve/reject."""

from __future__ import annotations

import hashlib
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, File, Form, UploadFile, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.permissions import requires, requires_any
from app.core.tenant import TenantContext
from app.models import Branch, OcrExtraction, OcrUpload, OcrVerification

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


class OcrBranchRead(BaseModel):
    """Least-privilege branch identity used by receipt upload forms."""

    id: UUID
    name: str
    code: str | None = None


class VerificationDecision(BaseModel):
    decision: Literal["approve", "reject", "edit"]
    edits: dict | None = None
    notes: str | None = Field(default=None, max_length=500)


@router.get("/branches", response_model=list[OcrBranchRead])
async def list_ocr_branches(
    session: SessionDep,
    tenant: TenantContext = Depends(requires_any("ocr.upload", "ocr.verify")),
) -> list[OcrBranchRead]:
    """Return only branches this OCR operator is allowed to reference.

    Receipt upload/review must not depend on the admin-only Settings API, nor
    should it silently require Finance or Inventory access merely to resolve a
    branch label. Branch-bound identities receive exactly their assigned
    branch; company-wide OCR operators receive active branches in name order.
    """

    stmt = select(Branch).where(
        Branch.company_id == tenant.company_id,
        Branch.deleted_at.is_(None),
    )
    if tenant.branch_id is not None:
        stmt = stmt.where(Branch.id == tenant.branch_id)
    rows = (await session.execute(stmt.order_by(Branch.name))).scalars().all()
    return [OcrBranchRead(id=row.id, name=row.name, code=row.code) for row in rows]


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
    branch = await session.get(Branch, branch_id)
    if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    body = await file.read()
    if not body:
        raise BusinessRuleError("uploaded file is empty")
    if len(body) > 10 * 1024 * 1024:
        raise BusinessRuleError("uploaded file exceeds the 10 MB limit")
    allowed_types = {
        "application/pdf",
        "image/heic",
        "image/heif",
        "image/jpeg",
        "image/png",
        "image/webp",
    }
    if file.content_type not in allowed_types:
        raise BusinessRuleError("upload a PDF, JPEG, PNG, WebP, HEIC, or HEIF receipt")
    sha = hashlib.sha256(body).hexdigest()
    # NOTE: in production, body goes to object storage and we only store the key.
    safe_filename = Path(file.filename or "receipt").name[:180]
    storage_key = f"ocr/{tenant.company_id}/{sha}/{safe_filename}"
    upload = OcrUpload(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=branch_id,
        uploaded_by=tenant.user_id,
        storage_key=storage_key,
        mime=file.content_type,
        sha256=sha,
        byte_size=len(body),
        source=source,
    )
    session.add(upload)
    # Persist the parent first. These models intentionally have no ORM
    # relationship, so SQLAlchemy cannot otherwise guarantee insert order.
    await session.flush()
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
    ex = (
        await session.execute(
            select(OcrExtraction)
            .join(OcrUpload, OcrUpload.id == OcrExtraction.ocr_upload_id)
            .where(
                OcrExtraction.id == extraction_id,
                OcrUpload.company_id == tenant.company_id,
            )
        )
    ).scalar_one_or_none()
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
