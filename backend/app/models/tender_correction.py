"""Immutable owner evidence; original payments and closing receipts never change."""

# SQLAlchemy resolves mapped annotations at runtime.
# ruff: noqa: TC003
from __future__ import annotations

from datetime import date, datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    String,
    UniqueConstraint,
    event,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, _uuid_pk


class TenderReceiptMixin:
    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("companies.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    business_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    business_timezone: Mapped[str] = mapped_column(String(64), nullable=False)
    recorded_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    recorded_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    evidence_kind: Mapped[str] = mapped_column(
        String(30), nullable=False, default="owner_attestation"
    )
    evidence_reference: Mapped[str | None] = mapped_column(String(200))
    source_fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    request_path: Mapped[str] = mapped_column(String(200), nullable=False)
    request_body: Mapped[dict] = mapped_column(JSONB, nullable=False)


class PaymentTenderCorrection(TenderReceiptMixin, Base):
    __tablename__ = "payment_tender_corrections"
    __table_args__ = (
        UniqueConstraint("payment_id", name="uq_tender_corrected_payment"),
        UniqueConstraint("company_id", "idempotency_key", name="uq_payment_tender_action"),
        CheckConstraint(
            "amount_minor > 0 AND original_method = 'cash' AND effective_method = 'upi'",
            name="ck_payment_tender_direction",
        ),
        CheckConstraint(
            "length(trim(reason)) >= 3 AND evidence_kind = 'owner_attestation'",
            name="ck_payment_tender_attestation",
        ),
    )
    payment_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("payments.id", ondelete="RESTRICT"), nullable=False
    )
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("orders.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    invoice_no: Mapped[str] = mapped_column(String(32), nullable=False)
    original_method: Mapped[str] = mapped_column(String(20), nullable=False, default="cash")
    effective_method: Mapped[str] = mapped_column(String(20), nullable=False, default="upi")
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    original_tendered_minor: Mapped[int | None] = mapped_column(BigInteger)
    original_change_minor: Mapped[int | None] = mapped_column(BigInteger)
    accounting_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class DailyTenderReconciliation(TenderReceiptMixin, Base):
    __tablename__ = "daily_tender_reconciliations"
    __table_args__ = (
        UniqueConstraint("company_id", "idempotency_key", name="uq_daily_tender_action"),
        CheckConstraint("kind IN ('applied', 'unresolved')", name="ck_daily_tender_kind"),
        CheckConstraint(
            "actual_cash_minor >= 0 AND actual_upi_minor >= 0", name="ck_daily_tender_nonnegative"
        ),
        CheckConstraint(
            "length(trim(reason)) >= 3 AND evidence_kind = 'owner_attestation'",
            name="ck_daily_tender_attestation",
        ),
        CheckConstraint(
            "(kind = 'applied' AND cash_delta_minor < 0 "
            "AND upi_delta_minor = -cash_delta_minor AND total_difference_minor = 0) "
            "OR (kind = 'unresolved' AND cash_delta_minor = 0 "
            "AND upi_delta_minor = 0 AND total_difference_minor <> 0)",
            name="ck_daily_tender_posting",
        ),
    )
    kind: Mapped[str] = mapped_column(String(20), nullable=False)
    before_cash_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    before_upi_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    actual_cash_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    actual_upi_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    cash_delta_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    upi_delta_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    total_difference_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)


for _model in (PaymentTenderCorrection, DailyTenderReconciliation):

    @event.listens_for(_model, "before_update")
    @event.listens_for(_model, "before_delete")
    def _immutable(_mapper, _connection, _row):
        raise ValueError("tender receipts are append-only")
