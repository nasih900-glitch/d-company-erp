"""A staff observation about a finalized receipt, independent of adjustments."""

# SQLAlchemy resolves mapped annotations at runtime.
# ruff: noqa: TC003
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import CheckConstraint, DateTime, ForeignKey, Index, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, _uuid_pk


class PosReceiptMistakeReport(Base, TenantMixin):
    __tablename__ = "pos_receipt_mistake_reports"
    __table_args__ = (
        UniqueConstraint(
            "company_id",
            "branch_id",
            "reporter_user_id",
            "idempotency_key",
            name="uq_pos_receipt_mistake_request",
        ),
        CheckConstraint(
            "reason IN ('wrong_item', 'wrong_payment_method', 'no_funds_received', 'other')",
            name="ck_pos_receipt_mistake_reason",
        ),
        CheckConstraint("request_hash ~ '^[0-9a-f]{64}$'", name="ck_pos_receipt_mistake_hash"),
        Index(
            "ix_pos_receipt_mistake_branch_history", "company_id", "branch_id", "created_at", "id"
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(ForeignKey("orders.id", ondelete="RESTRICT"))
    branch_id: Mapped[UUID] = mapped_column(ForeignKey("branches.id", ondelete="RESTRICT"))
    original_shift_id: Mapped[UUID] = mapped_column(ForeignKey("shifts.id", ondelete="RESTRICT"))
    original_terminal_id: Mapped[UUID] = mapped_column(
        ForeignKey("terminals.id", ondelete="RESTRICT")
    )
    reporter_user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="RESTRICT"))
    idempotency_key: Mapped[UUID] = mapped_column(info={"audit_redact": True})
    request_hash: Mapped[str] = mapped_column(String(64), info={"audit_redact": True})
    invoice_no: Mapped[str] = mapped_column(String(32))
    reason: Mapped[str] = mapped_column(String(32))
    note: Mapped[str | None] = mapped_column(String(500), info={"audit_redact": True})
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
