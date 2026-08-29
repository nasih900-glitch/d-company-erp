"""Normalize menu modifier groups and tenant-scoped option pricing.

Revision ID: 0040
Revises: 0039
Create Date: 2026-08-27

The original modifier table stored group rules on individual options. That
could not represent required/exclusive groups consistently and did not enforce
tenant ownership in PostgreSQL. This revision creates one authoritative group
row, preserves every legacy option, and adds the constraints/indexes needed by
server-side pricing.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0040"
down_revision = "0039"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_unique_constraint(
        "uq_menu_items_company_id_id",
        "menu_items",
        ["company_id", "id"],
    )

    op.create_table(
        "menu_modifier_groups",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("menu_item_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column("min_select", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("max_select", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "char_length(trim(name)) >= 1",
            name="ck_menu_modifier_group_name",
        ),
        sa.CheckConstraint(
            "min_select >= 0",
            name="ck_menu_modifier_group_min_select",
        ),
        sa.CheckConstraint(
            "max_select >= 1",
            name="ck_menu_modifier_group_max_select",
        ),
        sa.CheckConstraint(
            "min_select <= max_select",
            name="ck_menu_modifier_group_selection_bounds",
        ),
        sa.CheckConstraint(
            "sort_order >= 0",
            name="ck_menu_modifier_group_sort_order",
        ),
        sa.ForeignKeyConstraint(
            ["company_id"],
            ["companies.id"],
            name="fk_menu_modifier_groups_company",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["company_id", "menu_item_id"],
            ["menu_items.company_id", "menu_items.id"],
            name="fk_menu_modifier_groups_company_item",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "menu_item_id",
            "id",
            name="uq_menu_modifier_groups_scope_id",
        ),
    )

    op.add_column(
        "menu_variants",
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "menu_variants",
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
    )
    op.add_column(
        "menu_modifiers",
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "menu_modifiers",
        sa.Column("modifier_group_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "menu_modifiers",
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
    )
    op.add_column(
        "menu_modifiers",
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.true()),
    )

    op.execute(
        """
        UPDATE menu_variants AS variant
           SET company_id = item.company_id,
               price_delta_minor = COALESCE(variant.price_delta_minor, 0),
               sort_order = COALESCE(variant.sort_order, 0)
          FROM menu_items AS item
         WHERE item.id = variant.menu_item_id
        """
    )
    op.execute(
        """
        UPDATE menu_modifiers AS modifier
           SET company_id = item.company_id,
               price_delta_minor = COALESCE(modifier.price_delta_minor, 0),
               max_per_order = GREATEST(1, COALESCE(modifier.max_per_order, 1))
          FROM menu_items AS item
         WHERE item.id = modifier.menu_item_id
        """
    )

    # Preserve the old, option-level representation. A blank legacy group is
    # one optional "Options" group. Any required legacy option makes the group
    # require one selection, while the sum of per-option maxima preserves every
    # combination that the old rows could express.
    op.execute(
        """
        INSERT INTO menu_modifier_groups (
            id, company_id, menu_item_id, name, min_select, max_select,
            sort_order, is_active, created_at, updated_at
        )
        SELECT
            gen_random_uuid(),
            modifier.company_id,
            modifier.menu_item_id,
            (
                array_agg(
                    CASE
                        WHEN modifier."group" IS NULL OR trim(modifier."group") = ''
                            THEN 'Options'
                        ELSE trim(modifier."group")
                    END
                    ORDER BY modifier.created_at, modifier.id
                )
            )[1],
            CASE WHEN bool_or(COALESCE(modifier.required, false)) THEN 1 ELSE 0 END,
            GREATEST(1, sum(GREATEST(1, COALESCE(modifier.max_per_order, 1)))::integer),
            0,
            true,
            min(modifier.created_at),
            max(modifier.updated_at)
          FROM menu_modifiers AS modifier
         GROUP BY
            modifier.company_id,
            modifier.menu_item_id,
            lower(
                CASE
                    WHEN modifier."group" IS NULL OR trim(modifier."group") = ''
                        THEN 'Options'
                    ELSE trim(modifier."group")
                END
            )
        """
    )
    op.execute(
        """
        UPDATE menu_modifiers AS modifier
           SET modifier_group_id = modifier_group.id
          FROM menu_modifier_groups AS modifier_group
         WHERE modifier_group.company_id = modifier.company_id
           AND modifier_group.menu_item_id = modifier.menu_item_id
           AND lower(modifier_group.name) = lower(
                CASE
                    WHEN modifier."group" IS NULL OR trim(modifier."group") = ''
                        THEN 'Options'
                    ELSE trim(modifier."group")
                END
           )
        """
    )

    op.execute(
        """
        DO $$
        DECLARE
            duplicate record;
        BEGIN
            SELECT company_id, menu_item_id, lower(name) AS normalized_name,
                   array_agg(id ORDER BY id) AS ids
              INTO duplicate
              FROM menu_variants
             GROUP BY company_id, menu_item_id, lower(name)
            HAVING count(*) > 1
             LIMIT 1;
            IF FOUND THEN
                RAISE EXCEPTION USING
                    MESSAGE = format(
                        '0040 found duplicate menu variants: company=%s item=%s name=%s ids=%s',
                        duplicate.company_id, duplicate.menu_item_id,
                        duplicate.normalized_name, duplicate.ids
                    ),
                    HINT = 'Rename or consolidate duplicate variants, then rerun.';
            END IF;

            SELECT company_id, modifier_group_id, lower(name) AS normalized_name,
                   array_agg(id ORDER BY id) AS ids
              INTO duplicate
              FROM menu_modifiers
             GROUP BY company_id, modifier_group_id, lower(name)
            HAVING count(*) > 1
             LIMIT 1;
            IF FOUND THEN
                RAISE EXCEPTION USING
                    MESSAGE = format(
                        '0040 found duplicate modifier options: '
                        'company=%s group=%s name=%s ids=%s',
                        duplicate.company_id, duplicate.modifier_group_id,
                        duplicate.normalized_name, duplicate.ids
                    ),
                    HINT = 'Rename or consolidate the duplicate options, then rerun the migration.';
            END IF;
        END;
        $$
        """
    )

    op.alter_column(
        "menu_variants",
        "company_id",
        existing_type=postgresql.UUID(as_uuid=True),
        nullable=False,
    )
    op.alter_column(
        "menu_variants",
        "price_delta_minor",
        existing_type=sa.BigInteger(),
        nullable=False,
        server_default="0",
    )
    op.alter_column(
        "menu_variants",
        "sort_order",
        existing_type=sa.Integer(),
        nullable=False,
        server_default="0",
    )
    op.alter_column(
        "menu_modifiers",
        "company_id",
        existing_type=postgresql.UUID(as_uuid=True),
        nullable=False,
    )
    op.alter_column(
        "menu_modifiers",
        "modifier_group_id",
        existing_type=postgresql.UUID(as_uuid=True),
        nullable=False,
    )
    op.alter_column(
        "menu_modifiers",
        "price_delta_minor",
        existing_type=sa.BigInteger(),
        nullable=False,
        server_default="0",
    )
    op.alter_column(
        "menu_modifiers",
        "max_per_order",
        existing_type=sa.Integer(),
        nullable=False,
        server_default="1",
        new_column_name="max_quantity",
    )
    op.drop_column("menu_modifiers", "required")
    op.drop_column("menu_modifiers", "group")

    op.drop_constraint(
        "menu_variants_menu_item_id_fkey",
        "menu_variants",
        type_="foreignkey",
    )
    op.create_foreign_key(
        "fk_menu_variants_company",
        "menu_variants",
        "companies",
        ["company_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_menu_variants_company_item",
        "menu_variants",
        "menu_items",
        ["company_id", "menu_item_id"],
        ["company_id", "id"],
        ondelete="CASCADE",
    )
    op.create_foreign_key(
        "fk_menu_modifiers_company",
        "menu_modifiers",
        "companies",
        ["company_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_menu_modifiers_group_scope",
        "menu_modifiers",
        "menu_modifier_groups",
        ["company_id", "menu_item_id", "modifier_group_id"],
        ["company_id", "menu_item_id", "id"],
        ondelete="CASCADE",
    )

    op.create_check_constraint(
        "ck_menu_variant_name",
        "menu_variants",
        "char_length(trim(name)) >= 1",
    )
    op.create_check_constraint(
        "ck_menu_variant_sort_order",
        "menu_variants",
        "sort_order >= 0",
    )
    op.create_check_constraint(
        "ck_menu_modifier_name",
        "menu_modifiers",
        "char_length(trim(name)) >= 1",
    )
    op.create_check_constraint(
        "ck_menu_modifier_max_quantity",
        "menu_modifiers",
        "max_quantity >= 1",
    )
    op.create_check_constraint(
        "ck_menu_modifier_sort_order",
        "menu_modifiers",
        "sort_order >= 0",
    )

    op.create_index(
        "uq_menu_variants_company_item_name_ci",
        "menu_variants",
        ["company_id", "menu_item_id", sa.text("lower(name)")],
        unique=True,
    )
    op.create_index(
        "ix_menu_variants_company_item_active_sort",
        "menu_variants",
        ["company_id", "menu_item_id", "is_active", "sort_order"],
    )
    op.create_index(
        "uq_menu_modifier_groups_company_item_name_ci",
        "menu_modifier_groups",
        ["company_id", "menu_item_id", sa.text("lower(name)")],
        unique=True,
    )
    op.create_index(
        "ix_menu_modifier_groups_company_item_active_sort",
        "menu_modifier_groups",
        ["company_id", "menu_item_id", "is_active", "sort_order"],
    )
    op.create_index(
        "uq_menu_modifiers_company_group_name_ci",
        "menu_modifiers",
        ["company_id", "modifier_group_id", sa.text("lower(name)")],
        unique=True,
    )
    op.create_index(
        "ix_menu_modifiers_company_item_group_active_sort",
        "menu_modifiers",
        [
            "company_id",
            "menu_item_id",
            "modifier_group_id",
            "is_active",
            "sort_order",
        ],
    )


def downgrade() -> None:
    op.add_column(
        "menu_modifiers",
        sa.Column("group", sa.String(length=50), nullable=True),
    )
    op.add_column(
        "menu_modifiers",
        sa.Column("required", sa.Boolean(), nullable=True, server_default=sa.false()),
    )
    op.execute(
        """
        UPDATE menu_modifiers AS modifier
           SET "group" = left(modifier_group.name, 50),
               required = modifier_group.min_select > 0
          FROM menu_modifier_groups AS modifier_group
         WHERE modifier_group.id = modifier.modifier_group_id
        """
    )
    op.alter_column(
        "menu_modifiers",
        "max_quantity",
        existing_type=sa.Integer(),
        nullable=False,
        server_default="1",
        new_column_name="max_per_order",
    )
    op.alter_column(
        "menu_modifiers",
        "max_per_order",
        existing_type=sa.Integer(),
        nullable=True,
        server_default="1",
    )
    op.alter_column(
        "menu_modifiers",
        "price_delta_minor",
        existing_type=sa.BigInteger(),
        nullable=True,
        server_default="0",
    )

    op.drop_index(
        "ix_menu_modifiers_company_item_group_active_sort",
        table_name="menu_modifiers",
    )
    op.drop_index(
        "uq_menu_modifiers_company_group_name_ci",
        table_name="menu_modifiers",
    )
    op.drop_constraint(
        "ck_menu_modifier_sort_order", "menu_modifiers", type_="check"
    )
    op.drop_constraint(
        "ck_menu_modifier_max_quantity", "menu_modifiers", type_="check"
    )
    op.drop_constraint("ck_menu_modifier_name", "menu_modifiers", type_="check")
    op.drop_constraint(
        "fk_menu_modifiers_group_scope", "menu_modifiers", type_="foreignkey"
    )
    op.drop_constraint(
        "fk_menu_modifiers_company", "menu_modifiers", type_="foreignkey"
    )
    op.drop_column("menu_modifiers", "is_active")
    op.drop_column("menu_modifiers", "sort_order")
    op.drop_column("menu_modifiers", "modifier_group_id")
    op.drop_column("menu_modifiers", "company_id")

    op.drop_index(
        "ix_menu_variants_company_item_active_sort", table_name="menu_variants"
    )
    op.drop_index(
        "uq_menu_variants_company_item_name_ci", table_name="menu_variants"
    )
    op.drop_constraint("ck_menu_variant_sort_order", "menu_variants", type_="check")
    op.drop_constraint("ck_menu_variant_name", "menu_variants", type_="check")
    op.drop_constraint(
        "fk_menu_variants_company_item", "menu_variants", type_="foreignkey"
    )
    op.drop_constraint(
        "fk_menu_variants_company", "menu_variants", type_="foreignkey"
    )
    op.create_foreign_key(
        "menu_variants_menu_item_id_fkey",
        "menu_variants",
        "menu_items",
        ["menu_item_id"],
        ["id"],
        ondelete="CASCADE",
    )
    op.alter_column(
        "menu_variants",
        "price_delta_minor",
        existing_type=sa.BigInteger(),
        nullable=True,
        server_default="0",
    )
    op.alter_column(
        "menu_variants",
        "sort_order",
        existing_type=sa.Integer(),
        nullable=True,
        server_default="0",
    )
    op.drop_column("menu_variants", "is_active")
    op.drop_column("menu_variants", "company_id")

    op.drop_index(
        "ix_menu_modifier_groups_company_item_active_sort",
        table_name="menu_modifier_groups",
    )
    op.drop_index(
        "uq_menu_modifier_groups_company_item_name_ci",
        table_name="menu_modifier_groups",
    )
    op.drop_table("menu_modifier_groups")
    op.drop_constraint(
        "uq_menu_items_company_id_id", "menu_items", type_="unique"
    )
