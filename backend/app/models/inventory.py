"""Inventory module models."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Numeric, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class Ingredient(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "ingredients"
    __table_args__ = (UniqueConstraint("company_id", "sku", name="uq_ingredient_sku"),)

    id: Mapped[UUID] = _uuid_pk()
    sku: Mapped[str] = mapped_column(String(50), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    base_unit: Mapped[str] = mapped_column(String(10), nullable=False)  # ml|g|unit
    reorder_threshold: Mapped[float] = mapped_column(Numeric(14, 4), default=0)
    reorder_qty: Mapped[float] = mapped_column(Numeric(14, 4), default=0)
    avg_cost_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    current_qty: Mapped[float] = mapped_column(Numeric(14, 4), default=0)


class Recipe(Base, TimestampMixin):
    __tablename__ = "recipes"
    __table_args__ = (UniqueConstraint("menu_item_id", "version", name="uq_recipe_version"),)

    id: Mapped[UUID] = _uuid_pk()
    menu_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_items.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    yield_qty: Mapped[float] = mapped_column(Numeric(14, 4), default=1)
    version: Mapped[int] = mapped_column(default=1, nullable=False)
    is_active: Mapped[bool] = mapped_column(default=True, nullable=False)
    cost_minor: Mapped[int] = mapped_column(BigInteger, default=0)  # computed


class RecipeLine(Base, TimestampMixin):
    __tablename__ = "recipe_lines"

    id: Mapped[UUID] = _uuid_pk()
    recipe_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("recipes.id", ondelete="CASCADE"), nullable=False
    )
    ingredient_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ingredients.id", ondelete="RESTRICT"), nullable=False
    )
    qty: Mapped[float] = mapped_column(Numeric(14, 4), nullable=False)
    wastage_pct: Mapped[float] = mapped_column(Numeric(5, 4), default=0)


class Supplier(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "suppliers"

    id: Mapped[UUID] = _uuid_pk()
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    contact: Mapped[str | None] = mapped_column(String(200))
    gstin: Mapped[str | None] = mapped_column(String(20))
    payment_terms: Mapped[str | None] = mapped_column(String(100))


class Batch(Base, TimestampMixin):
    __tablename__ = "batches"

    id: Mapped[UUID] = _uuid_pk()
    ingredient_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ingredients.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    supplier_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("suppliers.id", ondelete="SET NULL")
    )
    grn_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("grns.id", ondelete="SET NULL")
    )
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True)
    qty_initial: Mapped[float] = mapped_column(Numeric(14, 4), nullable=False)
    qty_on_hand: Mapped[float] = mapped_column(Numeric(14, 4), nullable=False)
    cost_per_unit_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    lot_code: Mapped[str | None] = mapped_column(String(100))


class StockMovement(Base, TimestampMixin):
    __tablename__ = "stock_movements"

    id: Mapped[UUID] = _uuid_pk()
    batch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("batches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # sale|waste|damage|transfer|adjustment|grn
    ref_type: Mapped[str | None] = mapped_column(String(50))
    ref_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True), index=True)
    qty_delta: Mapped[float] = mapped_column(Numeric(14, 4), nullable=False)
    cost_per_unit_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    note: Mapped[str | None] = mapped_column(String(500))


class PurchaseOrder(Base, TimestampMixin, TenantMixin):
    __tablename__ = "purchase_orders"

    id: Mapped[UUID] = _uuid_pk()
    supplier_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("suppliers.id", ondelete="RESTRICT"), nullable=False
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    po_number: Mapped[str] = mapped_column(String(50), nullable=False, unique=True)
    status: Mapped[str] = mapped_column(String(20), default="draft")  # draft|sent|partial|closed|cancelled
    expected_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    total_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    created_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )


class PurchaseOrderLine(Base, TimestampMixin):
    __tablename__ = "purchase_order_lines"

    id: Mapped[UUID] = _uuid_pk()
    purchase_order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("purchase_orders.id", ondelete="CASCADE"), nullable=False
    )
    ingredient_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ingredients.id", ondelete="RESTRICT"), nullable=False
    )
    qty_ordered: Mapped[float] = mapped_column(Numeric(14, 4), nullable=False)
    qty_received: Mapped[float] = mapped_column(Numeric(14, 4), default=0)
    unit_cost_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)


class GRN(Base, TimestampMixin):
    __tablename__ = "grns"

    id: Mapped[UUID] = _uuid_pk()
    purchase_order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("purchase_orders.id", ondelete="RESTRICT"), nullable=False
    )
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    supplier_invoice_no: Mapped[str | None] = mapped_column(String(100))
    supplier_invoice_amount_minor: Mapped[int | None] = mapped_column(BigInteger)
    # E-way bill number — mandatory for goods movement > ₹50k. Supplier
    # generates it; D Company just records the 12-char EBN for audit trail.
    eway_bill_no: Mapped[str | None] = mapped_column(String(20))
    notes: Mapped[str | None] = mapped_column(String(500))


class GRNLine(Base, TimestampMixin):
    __tablename__ = "grn_lines"

    id: Mapped[UUID] = _uuid_pk()
    grn_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("grns.id", ondelete="CASCADE"), nullable=False
    )
    ingredient_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("ingredients.id", ondelete="RESTRICT"), nullable=False
    )
    batch_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("batches.id", ondelete="SET NULL")
    )
    qty_received: Mapped[float] = mapped_column(Numeric(14, 4), nullable=False)
    cost_per_unit_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
