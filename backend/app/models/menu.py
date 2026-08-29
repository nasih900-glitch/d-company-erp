"""Menu module models."""

from __future__ import annotations

from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
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
    __table_args__ = (
        UniqueConstraint("company_id", "sku", name="uq_menu_sku"),
        # Composite child FKs use this key to enforce tenant ownership in the
        # database instead of relying only on application-side joins.
        UniqueConstraint("company_id", "id", name="uq_menu_items_company_id_id"),
    )

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
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "menu_item_id"],
            ["menu_items.company_id", "menu_items.id"],
            name="fk_menu_variants_company_item",
            ondelete="CASCADE",
        ),
        CheckConstraint("char_length(trim(name)) >= 1", name="ck_menu_variant_name"),
        CheckConstraint("sort_order >= 0", name="ck_menu_variant_sort_order"),
        Index(
            "uq_menu_variants_company_item_name_ci",
            "company_id",
            "menu_item_id",
            text("lower(name)"),
            unique=True,
        ),
        Index(
            "ix_menu_variants_company_item_active_sort",
            "company_id",
            "menu_item_id",
            "is_active",
            "sort_order",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("companies.id", ondelete="RESTRICT"),
        nullable=False,
    )
    menu_item_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    name: Mapped[str] = mapped_column(String(50), nullable=False)  # S, M, L, etc.
    price_delta_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)


class MenuModifierGroup(Base, TimestampMixin):
    __tablename__ = "menu_modifier_groups"
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "menu_item_id"],
            ["menu_items.company_id", "menu_items.id"],
            name="fk_menu_modifier_groups_company_item",
            ondelete="CASCADE",
        ),
        UniqueConstraint(
            "company_id",
            "menu_item_id",
            "id",
            name="uq_menu_modifier_groups_scope_id",
        ),
        CheckConstraint("char_length(trim(name)) >= 1", name="ck_menu_modifier_group_name"),
        CheckConstraint("min_select >= 0", name="ck_menu_modifier_group_min_select"),
        CheckConstraint("max_select >= 1", name="ck_menu_modifier_group_max_select"),
        CheckConstraint(
            "min_select <= max_select",
            name="ck_menu_modifier_group_selection_bounds",
        ),
        CheckConstraint("sort_order >= 0", name="ck_menu_modifier_group_sort_order"),
        Index(
            "uq_menu_modifier_groups_company_item_name_ci",
            "company_id",
            "menu_item_id",
            text("lower(name)"),
            unique=True,
        ),
        Index(
            "ix_menu_modifier_groups_company_item_active_sort",
            "company_id",
            "menu_item_id",
            "is_active",
            "sort_order",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("companies.id", ondelete="RESTRICT"),
        nullable=False,
    )
    menu_item_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    min_select: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    max_select: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)


class MenuModifier(Base, TimestampMixin):
    __tablename__ = "menu_modifiers"
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "menu_item_id", "modifier_group_id"],
            [
                "menu_modifier_groups.company_id",
                "menu_modifier_groups.menu_item_id",
                "menu_modifier_groups.id",
            ],
            name="fk_menu_modifiers_group_scope",
            ondelete="CASCADE",
        ),
        CheckConstraint("char_length(trim(name)) >= 1", name="ck_menu_modifier_name"),
        CheckConstraint("max_quantity >= 1", name="ck_menu_modifier_max_quantity"),
        CheckConstraint("sort_order >= 0", name="ck_menu_modifier_sort_order"),
        Index(
            "uq_menu_modifiers_company_group_name_ci",
            "company_id",
            "modifier_group_id",
            text("lower(name)"),
            unique=True,
        ),
        Index(
            "ix_menu_modifiers_company_item_group_active_sort",
            "company_id",
            "menu_item_id",
            "modifier_group_id",
            "is_active",
            "sort_order",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    company_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("companies.id", ondelete="RESTRICT"),
        nullable=False,
    )
    menu_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_items.id", ondelete="CASCADE"), nullable=False
    )
    modifier_group_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    price_delta_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    max_quantity: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
