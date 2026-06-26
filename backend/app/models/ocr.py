"""OCR module models."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class OcrUpload(Base, TimestampMixin, TenantMixin):
    __tablename__ = "ocr_uploads"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    uploaded_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    storage_key: Mapped[str] = mapped_column(String(500), nullable=False)
    mime: Mapped[str] = mapped_column(String(100), nullable=False)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    byte_size: Mapped[int] = mapped_column(BigInteger, nullable=False)
    source: Mapped[str] = mapped_column(String(20), default="manual")  # manual|drive|whatsapp|telegram


class OcrExtraction(Base, TimestampMixin):
    __tablename__ = "ocr_extractions"

    id: Mapped[UUID] = _uuid_pk()
    ocr_upload_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ocr_uploads.id", ondelete="CASCADE"), nullable=False, index=True
    )
    vendor_name: Mapped[str | None] = mapped_column(String(200))
    invoice_no: Mapped[str | None] = mapped_column(String(100))
    invoice_date: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    amount_minor: Mapped[int | None] = mapped_column(BigInteger)
    tax_minor: Mapped[int | None] = mapped_column(BigInteger)
    confidence: Mapped[dict | None] = mapped_column(JSONB)
    line_items: Mapped[list[dict] | None] = mapped_column(JSONB)
    status: Mapped[str] = mapped_column(String(20), default="parsed")  # parsed|needs_review|approved|duplicate|rejected
    duplicate_of: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ocr_extractions.id", ondelete="SET NULL")
    )


class OcrVerification(Base, TimestampMixin):
    __tablename__ = "ocr_verifications"

    id: Mapped[UUID] = _uuid_pk()
    ocr_extraction_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ocr_extractions.id", ondelete="CASCADE"), nullable=False, index=True
    )
    reviewed_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    decision: Mapped[str] = mapped_column(String(20), nullable=False)  # approve|reject|edit
    edits: Mapped[dict | None] = mapped_column(JSONB)
    reviewed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    notes: Mapped[str | None] = mapped_column(String(500))
