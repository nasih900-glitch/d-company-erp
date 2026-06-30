"""Menu module models."""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import BigInteger, ForeignKey, Integer, Numeric, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class MenuCategory(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "menu_categories"
    __table_args__ = (UniqueConstraint("company_id", "name", name="uq_menu_cat_name"),)

    id: Mapped[UUID] = _uuid_pk()
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)
    icon: Mapped[str | None] = mapped_column(String(64))


class MenuItem(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "menu_items"
    __table_args__ = (UniqueConstraint("company_id", "sku", name="uq_menu_sku"),)

    id: Mapped[UUID] = _uuid_pk()
    category_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_categories.id"), nullable=False, index=True
    )
    sku: Mapped[str] = mapped_column(String(50), nullable=False)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str | None] = mapped_column(String(1000))
    image_url: Mapped[str | None] = mapped_column(String(500))
    type: Mapped[str] = mapped_column(
        String(20), nullable=False
    )  # food|drink|dessert|gaming|event|hookah|streaming
    base_price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    tax_rate: Mapped[float] = mapped_column(Numeric(5, 4), nullable=False, default=0)
    # ----- India / GST -----
    # HSN code (goods) or SAC code (services). For café items sold AS restaurant
    # supply, the line itself is taxed under SAC 996331 (restaurant service) at
    # 5%, but we keep the underlying HSN for inventory/cost tracking and B2B
    # invoicing. Six digits — see docs/INDIA_TAX_COMPLIANCE.md §6.
    hsn_code: Mapped[str | None] = mapped_column(String(8))
    # When True, base_price_minor is the menu-displayed (tax-inclusive) price.
    # The pricing engine works backwards: taxable_value = price / (1 + rate).
    # Default true because every café in Kerala prints inclusive prices.
    price_includes_tax: Mapped[bool] = mapped_column(default=True, nullable=False)
    is_available: Mapped[bool] = mapped_column(default=True, nullable=False)
    availability_window: Mapped[dict | None] = mapped_column(JSONB)


class MenuVariant(Base, TimestampMixin):
    __tablename__ = "menu_variants"

    id: Mapped[UUID] = _uuid_pk()
    menu_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_items.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(50), nullable=False)  # S, M, L, etc.
    price_delta_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    sort_order: Mapped[int] = mapped_column(Integer, default=0)


class MenuModifier(Base, TimestampMixin):
    __tablename__ = "menu_modifiers"

    id: Mapped[UUID] = _uuid_pk()
    menu_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_items.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    price_delta_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    group: Mapped[str | None] = mapped_column(String(50))
    max_per_order: Mapped[int] = mapped_column(Integer, default=1)
    required: Mapped[bool] = mapped_column(default=False)
