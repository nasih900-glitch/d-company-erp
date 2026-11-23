"""Commercial intent only; immutable Refund remains the sole money effect."""

# SQLAlchemy resolves mapped types at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, String, UniqueConstraint, event
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, _uuid_pk


class PaidBillAmendmentIntent(Base, TenantMixin):
    __tablename__ = "paid_bill_amendment_intents"
    __table_args__ = (
        UniqueConstraint("company_id", "action_key", name="uq_paid_bill_action"),
        UniqueConstraint("order_id", "revision", name="uq_paid_bill_revision"),
        UniqueConstraint("refund_request_id", name="uq_paid_bill_refund_link"),
    )
    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(ForeignKey("orders.id", ondelete="RESTRICT"))
    branch_id: Mapped[UUID] = mapped_column(
        ForeignKey("branches.id", ondelete="RESTRICT"), index=True
    )
    terminal_id: Mapped[UUID] = mapped_column(
        ForeignKey("terminals.id", ondelete="RESTRICT"), index=True
    )
    original_shift_id: Mapped[UUID] = mapped_column(
        ForeignKey("shifts.id", ondelete="RESTRICT"), index=True
    )
    settlement_shift_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("shifts.id", ondelete="RESTRICT"), index=True
    )
    recorded_by: Mapped[UUID] = mapped_column(
        ForeignKey("users.id", ondelete="RESTRICT"), index=True
    )
    refund_request_id: Mapped[UUID | None] = mapped_column(
        ForeignKey(
            "pos_refund_requests.id",
            ondelete="RESTRICT",
            deferrable=True,
            initially="DEFERRED",
        )
    )
    invoice_no: Mapped[str] = mapped_column(String(80))
    operation: Mapped[str] = mapped_column(String(16))
    refund_mode: Mapped[str | None] = mapped_column(String(16))
    prior_effective_minor: Mapped[int] = mapped_column(BigInteger)
    target_minor: Mapped[int] = mapped_column(BigInteger)
    difference_minor: Mapped[int] = mapped_column(BigInteger)
    source_revision: Mapped[int] = mapped_column(BigInteger)
    revision: Mapped[int] = mapped_column(BigInteger)
    source_fingerprint: Mapped[str] = mapped_column(String(64))
    source_snapshot: Mapped[dict] = mapped_column(JSONB, info={"audit_redact": True})
    reason: Mapped[str] = mapped_column(String(500), info={"audit_redact": True})
    action_key: Mapped[str] = mapped_column(String(160))
    request_path: Mapped[str] = mapped_column(String(100))
    request_hash: Mapped[str] = mapped_column(String(64))
    request_body: Mapped[dict] = mapped_column(JSONB, info={"audit_redact": True})
    response_body: Mapped[dict] = mapped_column(JSONB, info={"audit_redact": True})
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


@event.listens_for(PaidBillAmendmentIntent, "before_update")
@event.listens_for(PaidBillAmendmentIntent, "before_delete")
def _immutable(*_):
    raise ValueError("Paid bill amendment intents are append-only")
