"""POS module models."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, Numeric, String
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class Shift(Base, TimestampMixin, TenantMixin):
    __tablename__ = "shifts"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    opened_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    opening_float_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    expected_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    counted_minor: Mapped[int | None] = mapped_column(BigInteger)
    variance_minor: Mapped[int | None] = mapped_column(BigInteger)
    status: Mapped[str] = mapped_column(String(20), default="open", nullable=False)  # open|closed|reconciled


class Order(Base, TimestampMixin, TenantMixin):
    __tablename__ = "orders"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    opened_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    table_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("tables.id", ondelete="SET NULL"), index=True
    )
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # dine_in|takeaway|delivery
    # Section 9(5): when delivery_via is an aggregator (Zomato/Swiggy/UberEats),
    # the aggregator is the deemed restaurant for GST. Our invoice shows ZERO tax;
    # the aggregator's invoice carries the 5%. delivery_via='inhouse' or NULL
    # means D Company collects GST normally.
    delivery_via: Mapped[str | None] = mapped_column(String(30))
    status: Mapped[str] = mapped_column(String(20), nullable=False, default="open")  # open|paid|void|refunded|held
    subtotal_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    discount_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    # Tax breakdown — India needs explicit CGST/SGST/IGST/Cess columns for
    # GSTR-1 reporting and auditor reconciliation. tax_minor stays as the sum.
    cgst_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    sgst_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    igst_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    cess_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    tax_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    # Round-off shown as a line so sum(lines) + tax + round_off == total exactly.
    round_off_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    # Voluntary tip — post-tax, never feeds tax base. Settled to "Tips Payable".
    tip_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    total_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    opened_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    idempotency_key: Mapped[str | None] = mapped_column(String(80), unique=True, index=True)
    # ----- Invoice numbering (per branch, per FY, no gaps) -----
    invoice_no: Mapped[str | None] = mapped_column(String(20), unique=True, index=True)
    fiscal_year: Mapped[str | None] = mapped_column(String(7))  # e.g. "2026-27"
    # ----- Customer identity for GSTR-1 categorization -----
    customer_name: Mapped[str | None] = mapped_column(String(200))
    customer_phone: Mapped[str | None] = mapped_column(String(20))
    customer_gstin: Mapped[str | None] = mapped_column(String(15))
    customer_address: Mapped[str | None] = mapped_column(String(500))
    customer_state_code: Mapped[str | None] = mapped_column(String(2))
    # Place of supply — usually the branch state for dine-in/takeaway; the
    # customer state for inter-state delivery. Drives CGST+SGST vs IGST split.
    place_of_supply_state_code: Mapped[str | None] = mapped_column(String(2))
    # Reverse charge — almost always false for a café. True for
    # landlord-rent-from-unregistered or unregistered GTA.
    is_reverse_charge: Mapped[bool] = mapped_column(default=False, nullable=False)
    # ----- E-invoice (IRP) fields, populated only if e_invoicing_enabled -----
    irn: Mapped[str | None] = mapped_column(String(64))
    irn_ack_no: Mapped[str | None] = mapped_column(String(64))
    irn_acknowledged_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    e_invoice_qr: Mapped[str | None] = mapped_column(String(2048))
    notes: Mapped[str | None] = mapped_column(String(500))
    # ----- Kitchen Display System -----
    kitchen_state: Mapped[str | None] = mapped_column(String(20))  # received|preparing|ready|served
    kitchen_ready_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class OrderLine(Base, TimestampMixin):
    __tablename__ = "order_lines"

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="CASCADE"), nullable=False, index=True
    )
    menu_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_items.id", ondelete="RESTRICT"), nullable=False
    )
    variant_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_variants.id", ondelete="SET NULL")
    )
    modifiers: Mapped[list[dict] | None] = mapped_column(JSONB)
    qty: Mapped[float] = mapped_column(Numeric(10, 3), nullable=False)
    unit_price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    line_total_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    discount_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    # ----- Per-line tax breakdown (India / GST) -----
    # Snapshotted from menu_item at write-time so menu changes don't rewrite history.
    hsn_or_sac: Mapped[str | None] = mapped_column(String(8))
    tax_rate: Mapped[float] = mapped_column(Numeric(5, 4), default=0, nullable=False)
    taxable_value_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    cgst_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    sgst_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    igst_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    cess_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    note: Mapped[str | None] = mapped_column(String(500))
    kitchen_status: Mapped[str] = mapped_column(String(20), default="queued")  # queued|cooking|ready|served
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    voided_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )


class Payment(Base, TimestampMixin):
    __tablename__ = "payments"

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)  # cash|card|upi|qr|wallet
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    tendered_minor: Mapped[int | None] = mapped_column(BigInteger)
    change_minor: Mapped[int | None] = mapped_column(BigInteger)
    ref_external: Mapped[str | None] = mapped_column(String(200))
    paid_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class Refund(Base, TimestampMixin):
    __tablename__ = "refunds"

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    approved_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    manager_override_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    reason_code: Mapped[str] = mapped_column(String(50), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    mode: Mapped[str] = mapped_column(String(20), nullable=False)  # cash|original|credit_note
    note: Mapped[str | None] = mapped_column(String(500))
