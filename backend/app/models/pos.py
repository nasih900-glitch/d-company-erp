"""POS module models."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    FetchedValue,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    SmallInteger,
    String,
    UniqueConstraint,
    event,
    inspect,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class Shift(Base, TimestampMixin, TenantMixin):
    __tablename__ = "shifts"
    __table_args__ = (
        CheckConstraint(
            "opening_float_minor >= 0",
            name="ck_shifts_opening_float_nonnegative",
        ),
        Index(
            "uq_shifts_terminal_open",
            "terminal_id",
            unique=True,
            postgresql_where=text("status = 'open'"),
            sqlite_where=text("status = 'open'"),
        ),
    )

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
    __table_args__ = (
        CheckConstraint(
            "source_integrity_revision IS NULL "
            "OR source_integrity_revision = 48",
            name="ck_order_source_integrity_revision",
        ),
        UniqueConstraint(
            "company_id",
            "invoice_no",
            name="uq_orders_company_invoice_no",
        ),
        Index(
            "uq_orders_active_table_bill",
            "company_id",
            "branch_id",
            "table_id",
            unique=True,
            postgresql_where=text(
                "table_id IS NOT NULL AND status IN ('open', 'held')"
            ),
        ),
    )

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
    # Stable customer linkage for forward-looking LTV corrections. Historical
    # orders remain NULL until an owner explicitly reconciles them; a phone
    # snapshot is not safe evidence after a number changes or is re-used.
    customer_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("customers.id", ondelete="SET NULL"), index=True
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
    # Cashier-entered custom discount (e.g. "unregistered business, take ₹50
    # off the raw total"). Kept separate from discount_minor above — that
    # field is fully recomputed from line totals whenever a membership is
    # attached or a line is added, and folding this in would get silently
    # wiped by either of those recomputes.
    manual_discount_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    # Loyalty-points redemption value — same reprice-survival reasoning as
    # manual_discount_minor above; see app/services/pos/points.py.
    points_redeemed_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
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
    # Authoritative tax-invoice issue time. Unlike opened_at, this is populated
    # only when final payment succeeds and the invoice number is allocated.
    invoice_issued_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), index=True
    )
    idempotency_key: Mapped[str | None] = mapped_column(String(160), unique=True, index=True)
    # ----- Invoice numbering (per branch, per FY, no gaps) -----
    invoice_no: Mapped[str | None] = mapped_column(String(20), index=True)
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
    # Set the moment status transitions to "held" (sent to POS, awaiting
    # billing). Drives the POS held-order aging alarm — distinct from
    # opened_at, which for a Tables order predates being sent over.
    held_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # Monotonic, database-maintained version of checkout-relevant state.  A
    # PostgreSQL trigger (migration 0031) bumps it whenever totals, customer,
    # table, or settlement status changes, including writes outside this API.
    checkout_version: Mapped[int] = mapped_column(
        Integer,
        default=1,
        server_default="1",
        server_onupdate=FetchedValue(),
        nullable=False,
    )
    # NULL means the final invoice predates revision 0048. The database marks
    # only forward transitions into paid/refunded/invoiced state with 48, so
    # open drafts do not make a rollback unnecessarily irreversible.
    source_integrity_revision: Mapped[int | None] = mapped_column(SmallInteger)


class OrderCheckoutClaim(Base, TimestampMixin, TenantMixin):
    """Short-lived exclusive right to collect a shared held order.

    Only a SHA-256 digest of the bearer token is persisted.  The raw token is
    returned once to the claimant and must be supplied at settlement.
    """

    __tablename__ = "order_checkout_claims"
    __table_args__ = (
        CheckConstraint(
            "char_length(token_hash) = 64",
            name="ck_order_checkout_claim_token_hash_length",
        ),
        CheckConstraint(
            "client_instance_hash IS NULL OR char_length(client_instance_hash) = 64",
            name="ck_order_checkout_claim_client_instance_hash_length",
        ),
        CheckConstraint(
            "order_total_minor >= 0 AND due_minor >= 0 "
            "AND due_minor <= order_total_minor",
            name="ck_order_checkout_claim_nonnegative_balance",
        ),
        CheckConstraint(
            "order_version > 0",
            name="ck_order_checkout_claim_positive_version",
        ),
        UniqueConstraint("order_id", name="uq_order_checkout_claim_order"),
    )

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("orders.id", ondelete="CASCADE"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("terminals.id", ondelete="RESTRICT"),
        nullable=False,
    )
    claimed_by_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    # Optional for rolling compatibility with Code 14/21. New clients bind a
    # lease to one installation without persisting the raw installation UUID.
    client_instance_hash: Mapped[str | None] = mapped_column(
        String(64), nullable=True
    )
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    order_total_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    due_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    order_version: Mapped[int] = mapped_column(Integer, nullable=False)


class OrderLine(Base, TimestampMixin):
    __tablename__ = "order_lines"
    __table_args__ = (
        CheckConstraint(
            "variant_snapshot IS NULL OR jsonb_typeof(variant_snapshot) = 'object'",
            name="ck_order_line_variant_snapshot_object",
        ),
        CheckConstraint(
            "(kitchen_released_at IS NULL AND kitchen_round_no IS NULL) OR "
            "(kitchen_released_at IS NOT NULL AND kitchen_round_no IS NOT NULL "
            "AND kitchen_round_no > 0)",
            name="ck_order_line_kitchen_release_pair",
        ),
        CheckConstraint(
            "(COALESCE(kitchen_status, 'queued') = 'served' "
            "AND kitchen_served_at IS NOT NULL) OR "
            "(COALESCE(kitchen_status, 'queued') <> 'served' "
            "AND kitchen_served_at IS NULL)",
            name="ck_order_line_kitchen_served_pair",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) OR "
            "(voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND void_reason IS NOT NULL "
            "AND char_length(trim(void_reason)) >= 1) OR "
            "(voided_at IS NOT NULL AND voided_by IS NULL "
            "AND void_reason = "
            "'Legacy cancellation - actor and reason not recorded')",
            name="ck_order_line_void_provenance",
        ),
        CheckConstraint(
            "(kitchen_void_acknowledged_at IS NULL "
            "AND kitchen_void_acknowledged_by IS NULL) OR "
            "(kitchen_void_acknowledged_at IS NOT NULL "
            "AND kitchen_void_acknowledged_by IS NOT NULL "
            "AND voided_at IS NOT NULL AND kitchen_released_at IS NOT NULL)",
            name="ck_order_line_kitchen_void_ack_pair",
        ),
        CheckConstraint(
            "char_length(trim(menu_item_name_snapshot)) >= 1",
            name="ck_order_line_reporting_snapshot_name",
        ),
        CheckConstraint(
            "char_length(trim(menu_item_type_snapshot)) >= 1",
            name="ck_order_line_reporting_snapshot_type",
        ),
        CheckConstraint(
            "reporting_snapshot_revision IS NULL "
            "OR reporting_snapshot_revision = 49",
            name="ck_order_line_reporting_snapshot_revision",
        ),
        Index(
            "uq_order_lines_order_client_line",
            "order_id",
            "client_line_id",
            unique=True,
            postgresql_where=text("client_line_id IS NOT NULL"),
        ),
        Index(
            "ix_order_lines_kitchen_released_active",
            "order_id",
            "kitchen_released_at",
            postgresql_where=text(
                "kitchen_released_at IS NOT NULL AND voided_at IS NULL"
            ),
        ),
        Index(
            "ix_order_lines_kitchen_pending_cancel",
            "order_id",
            "voided_at",
            postgresql_where=text(
                "kitchen_released_at IS NOT NULL AND voided_at IS NOT NULL "
                "AND kitchen_void_acknowledged_at IS NULL"
            ),
        ),
        Index(
            "ix_order_lines_kitchen_served_history",
            "order_id",
            "kitchen_served_at",
            postgresql_where=text(
                "kitchen_released_at IS NOT NULL AND voided_at IS NULL "
                "AND kitchen_status = 'served'"
            ),
        ),
        Index(
            "ix_order_lines_kitchen_void_acknowledged_by",
            "kitchen_void_acknowledged_by",
            postgresql_where=text(
                "kitchen_void_acknowledged_by IS NOT NULL"
            ),
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="CASCADE"), nullable=False, index=True
    )
    # Stable client identity for offline action replay. Historical rows remain
    # nullable; every new Android table action supplies one.
    client_line_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    menu_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_items.id", ondelete="RESTRICT"), nullable=False
    )
    # Immutable catalogue facts captured when the line is written. Historical
    # reports and receipts must not change when a menu item is renamed or
    # reclassified later (migration 0049 enforces this in PostgreSQL).
    menu_item_name_snapshot: Mapped[str] = mapped_column(String(200), nullable=False)
    menu_item_type_snapshot: Mapped[str] = mapped_column(String(20), nullable=False)
    reporting_snapshot_revision: Mapped[int | None] = mapped_column(
        SmallInteger,
        server_default=text("49"),
    )
    variant_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_variants.id", ondelete="SET NULL")
    )
    # Immutable server-priced snapshot. ``variant_id`` may be cleared when a
    # catalog option is deleted, but historical receipts must retain the sold
    # name and delta.
    # PostgreSQL JSONB normally serializes Python ``None`` as the JSON literal
    # ``null``. That is not SQL NULL and therefore violates the object-only
    # snapshot constraint for every ordinary, non-variant menu item.
    variant_snapshot: Mapped[dict | None] = mapped_column(JSONB(none_as_null=True))
    # Immutable modifier-option snapshots; never persist client-supplied names
    # or prices here.
    modifiers: Mapped[list[dict] | None] = mapped_column(JSONB(none_as_null=True))
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
    # A kitchen line does not exist operationally until it is released. Table
    # rounds release at order time; direct POS food releases only when payment
    # finalizes. Gaming and shisha lines never receive these fields.
    kitchen_released_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    kitchen_round_no: Mapped[int | None] = mapped_column(Integer)
    # Exact completion time used by the branch-local "Served today" history.
    # Order.opened_at is deliberately insufficient: a ticket can cross the
    # cafe's local midnight before the kitchen finishes it.
    kitchen_served_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    voided_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    void_reason: Mapped[str | None] = mapped_column(String(500))
    kitchen_void_acknowledged_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    kitchen_void_acknowledged_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
    )


class Payment(Base, TimestampMixin):
    __tablename__ = "payments"
    __table_args__ = (
        CheckConstraint("amount_minor > 0", name="ck_payment_positive_amount"),
        CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'qr', 'wallet')",
            name="ck_payment_supported_method",
        ),
        CheckConstraint(
            "(method = 'cash' AND tendered_minor IS NOT NULL "
            "AND tendered_minor >= amount_minor "
            "AND change_minor IS NOT NULL "
            "AND change_minor = tendered_minor - amount_minor) OR "
            "(method <> 'cash' AND tendered_minor IS NULL "
            "AND change_minor IS NULL)",
            name="ck_payment_tender_contract",
            postgresql_not_valid=True,
        ),
        CheckConstraint(
            "source_integrity_revision IS NOT NULL "
            "AND source_integrity_revision = 48",
            name="ck_payment_source_integrity_revision",
            postgresql_not_valid=True,
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    # Immutable cashier attribution. Historical rows created before migration
    # 0057 remain NULL rather than being falsely attributed to the order opener.
    # Forward API writes always set this from the authenticated tenant context;
    # Payment itself is append-only at the database layer (migration 0048).
    recorded_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)  # cash|card|upi|qr|wallet
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    tendered_minor: Mapped[int | None] = mapped_column(BigInteger)
    change_minor: Mapped[int | None] = mapped_column(BigInteger)
    ref_external: Mapped[str | None] = mapped_column(String(200))
    paid_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    source_integrity_revision: Mapped[int | None] = mapped_column(
        SmallInteger,
        server_default=text("48"),
    )


class Refund(Base, TimestampMixin):
    """Immutable proof that a POS refund actually left the business.

    Rows created before the two-stage refund release have NULL settlement
    provenance and retain their historical ``created_at`` accounting date.
    ``request_id`` remains nullable only so those migrated rows can be read.
    PostgreSQL installs ``ck_refund_forward_write_linkage`` as NOT VALID: it
    preserves that history while rejecting every new or updated unlinked row.
    New cash refunds are created only after physical handover; new non-cash
    refunds require provider completion evidence first.
    """

    __tablename__ = "refunds"
    __table_args__ = (
        CheckConstraint("amount_minor > 0", name="ck_refund_positive_amount"),
        CheckConstraint(
            "source_integrity_revision IS NOT NULL "
            "AND source_integrity_revision = 48",
            name="ck_refund_source_integrity_revision",
            postgresql_not_valid=True,
        ),
        CheckConstraint(
            "request_id IS NOT NULL",
            name="ck_refund_forward_write_linkage",
            postgresql_not_valid=True,
        ),
        CheckConstraint(
            "request_id IS NULL OR ("
            "company_id IS NOT NULL AND branch_id IS NOT NULL AND "
            "terminal_id IS NOT NULL AND settlement_shift_id IS NOT NULL AND "
            "settled_at IS NOT NULL AND settled_by IS NOT NULL AND "
            "settlement_idempotency_key IS NOT NULL AND receipt_no IS NOT NULL AND "
            "receipt_fiscal_year IS NOT NULL AND receipt_issued_at IS NOT NULL)",
            name="ck_refund_request_settlement_provenance",
        ),
        CheckConstraint(
            "request_id IS NULL OR "
            "(settlement_method = 'cash' AND external_reference IS NULL "
            "AND provider_settled_at IS NULL "
            "AND provider_evidence_reconciled IS NULL) OR "
            "(settlement_method <> 'cash' "
            "AND char_length(trim(external_reference)) >= 1 "
            "AND provider_settled_at IS NOT NULL "
            "AND provider_evidence_reconciled IS NOT NULL)",
            name="ck_refund_request_external_provenance",
        ),
        CheckConstraint(
            "request_id IS NULL OR (loyalty_reconciliation_state IS NOT NULL "
            "AND loyalty_reconciliation_state IN ('not_applicable', 'applied', "
            "'legacy_redemption_restored', 'legacy_unknown'))",
            name="ck_refund_loyalty_reconciliation_state",
            postgresql_not_valid=True,
        ),
        UniqueConstraint("request_id", name="uq_refund_request"),
        UniqueConstraint(
            "company_id",
            "settlement_idempotency_key",
            name="uq_refund_company_settlement_idempotency",
        ),
        UniqueConstraint(
            "company_id", "receipt_no", name="uq_refund_company_receipt"
        ),
        Index(
            "ix_refund_company_settled_at", "company_id", "settled_at"
        ),
        Index("ix_refund_settlement_shift", "settlement_shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    request_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
    )
    company_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("companies.id", ondelete="RESTRICT")
    )
    branch_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT")
    )
    terminal_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT")
    )
    settlement_shift_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT")
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
    # Actual settlement rail, snapshotted for cash movement and accounting.
    settlement_method: Mapped[str | None] = mapped_column(String(20))
    settled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    settled_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    external_reference: Mapped[str | None] = mapped_column(String(200))
    provider_settled_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    # The immutable server value-capture time in settled_at is authoritative
    # for accounting; receipt_issued_at may be later if finalization is retried.
    # Preserve tablet/provider occurrence evidence separately even when its
    # clock is suspect, so a real payout is never erased after money moved.
    client_occurred_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    captured_time_reconciled: Mapped[bool | None] = mapped_column(Boolean)
    # False does not invalidate a real payout; it tells an owner that the
    # provider returned an unusually short opaque reference requiring review.
    provider_evidence_reconciled: Mapped[bool | None] = mapped_column(Boolean)
    settlement_idempotency_key: Mapped[str | None] = mapped_column(String(160))
    receipt_no: Mapped[str | None] = mapped_column(String(32))
    receipt_fiscal_year: Mapped[str | None] = mapped_column(String(7))
    receipt_issued_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # NULL for legacy rows, true when Customer.total_spent was netted in the
    # same transaction, false when the real payout posted but owner LTV
    # reconciliation remains necessary.
    customer_spend_reconciled: Mapped[bool | None] = mapped_column(Boolean)
    # Forward refunds explicitly record whether loyalty was fully adjusted,
    # safely restored from redemption-only legacy evidence, not applicable,
    # or requires owner review because pre-ledger earn history is unknowable.
    # NULL is retained only for refunds created before revision 0043.
    loyalty_reconciliation_state: Mapped[str | None] = mapped_column(String(40))
    note: Mapped[str | None] = mapped_column(String(500))
    source_integrity_revision: Mapped[int | None] = mapped_column(
        SmallInteger,
        server_default=text("48"),
    )


class PosRefundRequest(Base, TimestampMixin, TenantMixin):
    """Immutable acceptance/reservation for a POS refund.

    Acceptance reserves the order's refundable balance and, for cash, the
    exact shift's expected drawer. It is not itself a financial movement.
    State is derived from append-only handoff, settlement, and withdrawal
    facts so a restart or second tablet can recover the obligation safely.
    """

    __tablename__ = "pos_refund_requests"
    __table_args__ = (
        CheckConstraint("amount_minor > 0", name="ck_pos_refund_request_positive"),
        CheckConstraint(
            "order_paid_snapshot_minor >= amount_minor AND "
            "order_refundable_snapshot_minor >= amount_minor",
            name="ck_pos_refund_request_snapshot_balance",
        ),
        CheckConstraint(
            "mode IN ('cash', 'original')", name="ck_pos_refund_request_mode"
        ),
        CheckConstraint(
            "settlement_method IN ('cash', 'card', 'upi', 'qr', 'wallet')",
            name="ck_pos_refund_request_method",
        ),
        CheckConstraint(
            "(mode = 'cash' AND settlement_method = 'cash') OR mode = 'original'",
            name="ck_pos_refund_request_mode_method",
        ),
        UniqueConstraint(
            "company_id", "idempotency_key", name="uq_pos_refund_request_company_idempotency"
        ),
        UniqueConstraint(
            "company_id", "client_action_id", name="uq_pos_refund_request_company_action"
        ),
        Index(
            "ix_pos_refund_request_order_accepted", "order_id", "accepted_at"
        ),
        Index("ix_pos_refund_request_shift_method", "shift_id", "settlement_method"),
    )

    id: Mapped[UUID] = _uuid_pk()
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("orders.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    approved_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    manager_override_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    reason_code: Mapped[str] = mapped_column(String(50), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    mode: Mapped[str] = mapped_column(String(20), nullable=False)
    settlement_method: Mapped[str] = mapped_column(String(20), nullable=False)
    # Captured by the application under the Order workflow lock and rechecked
    # by PostgreSQL against Payment, settled Refund, and unresolved reservation
    # facts before a forward request may be inserted.
    order_paid_snapshot_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    order_refundable_snapshot_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    accepted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # 0034 briefly stored provider completion evidence on the request itself.
    # Forward requests keep these legacy columns NULL: provider money is
    # completed only after reservation and recorded in
    # PosRefundProviderSettlement.
    external_reference: Mapped[str | None] = mapped_column(String(200))
    provider_settled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    client_action_id: Mapped[str] = mapped_column(String(160), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    note: Mapped[str | None] = mapped_column(String(500))


class PosRefundCashHandoff(Base, TimestampMixin, TenantMixin):
    """Append-only server acknowledgement before staff touch drawer cash."""

    __tablename__ = "pos_refund_cash_handoffs"
    __table_args__ = (
        UniqueConstraint("refund_request_id", name="uq_pos_refund_handoff_request"),
        UniqueConstraint(
            "company_id", "idempotency_key", name="uq_pos_refund_handoff_company_idempotency"
        ),
        Index("ix_pos_refund_handoff_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    started_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


class PosRefundCashHandoffCompletion(Base, TimestampMixin, TenantMixin):
    """Append-only proof that drawer cash physically reached the customer.

    This fact is deliberately committed before the separate accounting
    finalization creates ``Refund``.  If receipt allocation or another
    accounting side effect fails, a restart or second terminal can still see
    that value moved and finish the same obligation without paying twice.
    """

    __tablename__ = "pos_refund_cash_handoff_completions"
    __table_args__ = (
        UniqueConstraint(
            "refund_request_id", name="uq_pos_refund_cash_completion_request"
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_cash_completion_company_idempotency",
        ),
        Index("ix_pos_refund_cash_completion_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    handed_over_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    recorded_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    captured_time_reconciled: Mapped[bool] = mapped_column(Boolean, nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


class PosRefundProviderSettlement(Base, TimestampMixin, TenantMixin):
    """Append-only provider completion captured before accounting finalization."""

    __tablename__ = "pos_refund_provider_settlements"
    __table_args__ = (
        CheckConstraint(
            "settlement_method IN ('card', 'upi', 'qr', 'wallet')",
            name="ck_pos_refund_provider_method",
        ),
        CheckConstraint(
            "char_length(trim(external_reference)) >= 1",
            name="ck_pos_refund_provider_reference",
        ),
        UniqueConstraint(
            "refund_request_id", name="uq_pos_refund_provider_settlement_request"
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_provider_settlement_company_idempotency",
        ),
        Index("ix_pos_refund_provider_settlement_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    settlement_method: Mapped[str] = mapped_column(String(20), nullable=False)
    external_reference: Mapped[str] = mapped_column(String(200), nullable=False)
    provider_settled_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    settled_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    captured_time_reconciled: Mapped[bool] = mapped_column(Boolean, nullable=False)
    provider_evidence_reconciled: Mapped[bool] = mapped_column(Boolean, nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


class PosRefundProviderPayoutStart(Base, TimestampMixin, TenantMixin):
    """Append-only acknowledgement before staff initiate provider payout."""

    __tablename__ = "pos_refund_provider_payout_starts"
    __table_args__ = (
        UniqueConstraint(
            "refund_request_id", name="uq_pos_refund_provider_start_request"
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_provider_start_company_idempotency",
        ),
        Index("ix_pos_refund_provider_start_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    started_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


class PosRefundWithdrawal(Base, TimestampMixin, TenantMixin):
    """Append-only resolution proving an accepted refund was not paid."""

    __tablename__ = "pos_refund_withdrawals"
    __table_args__ = (
        CheckConstraint(
            "resolution IN ("
            "'cash_not_handed_over', 'cash_handoff_abandoned', "
            "'provider_not_started', 'provider_payout_abandoned'"
            ")",
            name="ck_pos_refund_withdrawal_resolution",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3", name="ck_pos_refund_withdrawal_reason"
        ),
        CheckConstraint(
            "(resolution = 'provider_payout_abandoned' "
            "AND verification_reference IS NOT NULL "
            "AND char_length(trim(verification_reference)) >= 3 "
            "AND verification_status IS NOT NULL "
            "AND verification_status IN ("
            "'no_matching_transaction', 'provider_declined', 'provider_reversed'"
            ") AND verified_at IS NOT NULL) OR "
            "(resolution <> 'provider_payout_abandoned' "
            "AND verification_reference IS NULL AND verification_status IS NULL "
            "AND verified_at IS NULL)",
            name="ck_pos_refund_withdrawal_verification",
        ),
        UniqueConstraint("refund_request_id", name="uq_pos_refund_withdrawal_request"),
        UniqueConstraint(
            "company_id", "idempotency_key", name="uq_pos_refund_withdrawal_company_idempotency"
        ),
        Index("ix_pos_refund_withdrawal_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    resolution: Mapped[str] = mapped_column(String(40), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    verification_reference: Mapped[str | None] = mapped_column(String(200))
    verification_status: Mapped[str | None] = mapped_column(String(40))
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    withdrawn_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    withdrawn_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


class PosRefundEvidenceReconciliation(Base, TimestampMixin, TenantMixin):
    """Append-only owner proof resolving weak captured refund evidence.

    The source Refund remains immutable.  One fact per evidence kind records
    what the protected owner checked, who checked it, and when.
    """

    __tablename__ = "pos_refund_evidence_reconciliations"
    __table_args__ = (
        CheckConstraint(
            "evidence_kind IN ('provider_reference', 'captured_time')",
            name="ck_pos_refund_evidence_reconciliation_kind",
        ),
        CheckConstraint(
            "char_length(trim(proof_reference)) >= 3",
            name="ck_pos_refund_evidence_reconciliation_proof",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_pos_refund_evidence_reconciliation_reason",
        ),
        UniqueConstraint(
            "refund_id",
            "evidence_kind",
            name="uq_pos_refund_evidence_reconciliation_kind",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_evidence_reconciliation_idempotency",
        ),
        Index("ix_pos_refund_evidence_reconciliations_refund_id", "refund_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("refunds.id", ondelete="RESTRICT"),
        nullable=False,
    )
    evidence_kind: Mapped[str] = mapped_column(String(40), nullable=False)
    proof_reference: Mapped[str] = mapped_column(String(200), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    reconciled_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    reconciled_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


class CustomerSpendReconciliation(Base, TimestampMixin, TenantMixin):
    """Append-only owner correction derived from normalized financial facts.

    ``source_reconciliation_state`` preserves whether the immutable source was
    a forward settlement with a known underflow or a pre-0036 legacy refund
    whose accumulator outcome was unknown.  The source row is never rewritten.
    """

    __tablename__ = "customer_spend_reconciliations"
    __table_args__ = (
        CheckConstraint(
            "(pos_refund_id IS NOT NULL AND membership_refund_settlement_id IS NULL) "
            "OR (pos_refund_id IS NULL AND membership_refund_settlement_id IS NOT NULL)",
            name="ck_customer_spend_reconciliation_one_source",
        ),
        CheckConstraint(
            "after_total_spent_minor >= 0",
            name="ck_customer_spend_reconciliation_nonnegative_after",
        ),
        CheckConstraint(
            "adjustment_minor = after_total_spent_minor - before_total_spent_minor",
            name="ck_customer_spend_reconciliation_delta",
        ),
        CheckConstraint(
            "source_reconciliation_state IN ('unreconciled', 'legacy_unknown')",
            name="ck_customer_spend_reconciliation_source_state",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_customer_spend_reconciliation_reason",
        ),
        UniqueConstraint(
            "pos_refund_id", name="uq_customer_spend_reconciliation_pos_refund"
        ),
        UniqueConstraint(
            "membership_refund_settlement_id",
            name="uq_customer_spend_reconciliation_membership_refund",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_customer_spend_reconciliation_idempotency",
        ),
        Index("ix_customer_spend_reconciliation_customer", "customer_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customers.id", ondelete="RESTRICT"),
        nullable=False,
    )
    pos_refund_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("refunds.id", ondelete="RESTRICT"),
    )
    membership_refund_settlement_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_settlements.id", ondelete="RESTRICT"),
    )
    source_reconciliation_state: Mapped[str] = mapped_column(
        String(30), nullable=False
    )
    source_amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    before_total_spent_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    after_total_spent_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    adjustment_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    pos_gross_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    membership_gross_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    pos_refunds_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    membership_refunds_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    reconciled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    reconciled_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


def _guard_append_only_financial_row(row: object, label: str) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(f"{label} is immutable: " + ", ".join(changed))


@event.listens_for(Payment, "before_update")
def _guard_payment_update(_mapper, _connection, row: Payment) -> None:
    _guard_append_only_financial_row(row, "POS payment")


@event.listens_for(Payment, "before_delete")
def _guard_payment_delete(_mapper, _connection, _row: Payment) -> None:
    raise ValueError("POS payment is immutable and cannot be deleted")


@event.listens_for(Refund, "before_update")
def _guard_refund_update(_mapper, _connection, row: Refund) -> None:
    _guard_append_only_financial_row(row, "POS refund settlement")


@event.listens_for(PosRefundRequest, "before_update")
def _guard_pos_refund_request_update(_mapper, _connection, row: PosRefundRequest) -> None:
    _guard_append_only_financial_row(row, "POS refund request")


@event.listens_for(PosRefundCashHandoff, "before_update")
def _guard_pos_refund_handoff_update(
    _mapper, _connection, row: PosRefundCashHandoff
) -> None:
    _guard_append_only_financial_row(row, "POS refund cash handoff")


@event.listens_for(PosRefundCashHandoffCompletion, "before_update")
def _guard_pos_refund_cash_completion_update(
    _mapper, _connection, row: PosRefundCashHandoffCompletion
) -> None:
    _guard_append_only_financial_row(row, "POS refund cash handoff completion")


@event.listens_for(PosRefundProviderSettlement, "before_update")
def _guard_pos_refund_provider_settlement_update(
    _mapper, _connection, row: PosRefundProviderSettlement
) -> None:
    _guard_append_only_financial_row(row, "POS refund provider settlement")


@event.listens_for(PosRefundProviderPayoutStart, "before_update")
def _guard_pos_refund_provider_start_update(
    _mapper, _connection, row: PosRefundProviderPayoutStart
) -> None:
    _guard_append_only_financial_row(row, "POS refund provider payout start")


@event.listens_for(PosRefundWithdrawal, "before_update")
def _guard_pos_refund_withdrawal_update(
    _mapper, _connection, row: PosRefundWithdrawal
) -> None:
    _guard_append_only_financial_row(row, "POS refund withdrawal")


@event.listens_for(PosRefundEvidenceReconciliation, "before_update")
def _guard_pos_refund_evidence_reconciliation_update(
    _mapper, _connection, row: PosRefundEvidenceReconciliation
) -> None:
    _guard_append_only_financial_row(row, "POS refund evidence reconciliation")


@event.listens_for(CustomerSpendReconciliation, "before_update")
def _guard_customer_spend_reconciliation_update(
    _mapper, _connection, row: CustomerSpendReconciliation
) -> None:
    _guard_append_only_financial_row(row, "customer spend reconciliation")
