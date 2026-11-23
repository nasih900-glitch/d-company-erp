"""Append-only evidence of a wholly mistaken invoice with no value exchanged."""

# SQLAlchemy evaluates mapped annotations at runtime.
# ruff: noqa: TC003
from __future__ import annotations

from datetime import date, datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    String,
    UniqueConstraint,
    event,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, _uuid_pk


class PosReceiptCorrection(Base, TenantMixin):
    """A correction is neither a refund nor proof of a provider payout."""

    __tablename__ = "pos_receipt_corrections"
    __table_args__ = (
        CheckConstraint(
            "stock_source_snapshot IS NULL OR jsonb_typeof(stock_source_snapshot)='object'",
            name="ck_receipt_stock_snapshot",
        ),
        UniqueConstraint("order_id", name="uq_pos_receipt_correction_order"),
        UniqueConstraint("payment_id", name="uq_pos_receipt_correction_payment"),
        UniqueConstraint("company_id", "idempotency_key", name="uq_pos_receipt_correction_action"),
        CheckConstraint(
            "kind = 'payment_not_received_full_mistake' AND amount_minor > 0 "
            "AND original_method = 'upi' AND no_funds_received "
            "AND all_items_not_supplied AND acknowledges_no_payout",
            name="ck_pos_receipt_correction_no_value",
        ),
        CheckConstraint(
            "length(trim(reason)) BETWEEN 3 AND 500 "
            "AND length(trim(idempotency_key)) BETWEEN 1 AND 160 "
            "AND source_fingerprint ~ '^[0-9a-f]{64}$' "
            "AND request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_pos_receipt_correction_identity",
        ),
        CheckConstraint(
            "jsonb_typeof(source_snapshot) = 'object' "
            "AND jsonb_typeof(request_payload) = 'object' "
            "AND jsonb_typeof(response_payload) = 'object'",
            name="ck_pos_receipt_correction_snapshots",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="RESTRICT"), nullable=False
    )
    payment_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("payments.id", ondelete="RESTRICT"), nullable=False
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    original_shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    recorded_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    kind: Mapped[str] = mapped_column(String(48), nullable=False)
    original_method: Mapped[str] = mapped_column(String(20), nullable=False)
    invoice_no: Mapped[str] = mapped_column(String(32), nullable=False)
    fiscal_year: Mapped[str] = mapped_column(String(7), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    no_funds_received: Mapped[bool] = mapped_column(Boolean, nullable=False)
    all_items_not_supplied: Mapped[bool] = mapped_column(Boolean, nullable=False)
    acknowledges_no_payout: Mapped[bool] = mapped_column(Boolean, nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    request_path: Mapped[str] = mapped_column(String(200), nullable=False)
    source_fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    source_snapshot: Mapped[dict] = mapped_column(JSONB, nullable=False)
    stock_source_snapshot: Mapped[dict | None] = mapped_column(
        JSONB(none_as_null=True), nullable=True
    )
    request_payload: Mapped[dict] = mapped_column(JSONB, nullable=False)
    response_payload: Mapped[dict] = mapped_column(JSONB, nullable=False)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    accounting_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    business_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    business_timezone: Mapped[str] = mapped_column(String(64), nullable=False)


@event.listens_for(PosReceiptCorrection, "before_update")
@event.listens_for(PosReceiptCorrection, "before_delete")
def _immutable(_mapper, _connection, _row):
    raise ValueError("POS receipt corrections are append-only")


class PosReceiptStockRestoration(Base, TenantMixin):
    """One exact historical consumption reversal, owned by an immutable correction."""

    __tablename__ = "pos_receipt_stock_restorations"
    __table_args__ = (
        UniqueConstraint("original_movement_id", name="uq_receipt_restore_original"),
        UniqueConstraint("restoration_movement_id", name="uq_receipt_restore_movement"),
    )
    id: Mapped[UUID] = _uuid_pk()
    correction_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pos_receipt_corrections.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    original_movement_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("stock_movements.id", ondelete="RESTRICT"),
        nullable=False,
    )
    restoration_movement_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("stock_movements.id", ondelete="RESTRICT"),
        nullable=False,
    )


@event.listens_for(PosReceiptStockRestoration, "before_update")
@event.listens_for(PosReceiptStockRestoration, "before_delete")
def _immutable_stock_restoration(_mapper, _connection, _row):
    raise ValueError("POS receipt stock restorations are append-only")
