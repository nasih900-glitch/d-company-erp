"""Persist server-priced Gaming session add-ons before POS handoff.

Revision ID: 0055
Revises: 0054
Create Date: 2026-08-28

Drinks/snacks consumed during a running gaming session must survive process
restart and offline response loss without creating a second charge.  This
revision stores immutable price/tax snapshots, permits only a complete one-way
reasoned void, and lets the later held POS order retain the add-time reporting
snapshot even if the catalogue is renamed before handoff.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

# ruff: noqa: S608 -- SQL interpolation below selects one fixed migration branch.

revision = "0055"
down_revision = "0054"
branch_labels = None
depends_on = None


def _install_addon_guard() -> None:
    op.execute(
        """
        CREATE FUNCTION guard_gaming_session_addon()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_company uuid;
            source_branch uuid;
            source_shift_status text;
            source_session_status text;
            source_shift_terminal uuid;
            source_order uuid;
            item_company uuid;
            item_name text;
            item_type text;
            item_available boolean;
            item_deleted_at timestamptz;
            variant_company uuid;
            variant_item uuid;
            variant_active boolean;
            creator_company uuid;
            creator_terminal_branch uuid;
            voider_company uuid;
            void_terminal_branch uuid;
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION
                    'gaming session add-ons are immutable; void the add-on instead'
                    USING ERRCODE = '23514';
            END IF;

            SELECT gs.company_id,
                   station.branch_id,
                   shift.status,
                   gs.status,
                   shift.terminal_id,
                   gs.order_id
              INTO source_company,
                   source_branch,
                   source_shift_status,
                   source_session_status,
                   source_shift_terminal,
                   source_order
              FROM gaming_sessions gs
              JOIN stations station ON station.id = gs.station_id
              JOIN shifts shift ON shift.id = gs.shift_id
             WHERE gs.id = NEW.gaming_session_id
               AND station.company_id = gs.company_id
               AND shift.company_id = gs.company_id
               AND shift.branch_id = station.branch_id
               FOR UPDATE OF gs;

            IF source_company IS NULL
               OR source_company IS DISTINCT FROM NEW.company_id THEN
                RAISE EXCEPTION
                    'gaming session add-on must match session company/branch/shift provenance'
                    USING ERRCODE = '23514';
            END IF;

            IF TG_OP = 'INSERT' THEN
                IF source_order IS NOT NULL
                   OR source_shift_status IS DISTINCT FROM 'open' THEN
                    RAISE EXCEPTION
                        'gaming session add-on requires an unsent session on an open shift'
                        USING ERRCODE = '23514';
                END IF;

                IF source_session_status NOT IN ('active', 'paused') THEN
                    RAISE EXCEPTION
                        'new gaming session add-ons require an active or paused session'
                        USING ERRCODE = '23514';
                END IF;

                SELECT item.company_id,
                       item.name,
                       item.type,
                       item.is_available,
                       item.deleted_at
                  INTO item_company,
                       item_name,
                       item_type,
                       item_available,
                       item_deleted_at
                  FROM menu_items item
                 WHERE item.id = NEW.menu_item_id;
                IF item_company IS DISTINCT FROM NEW.company_id
                   OR item_deleted_at IS NOT NULL
                   OR item_available IS DISTINCT FROM TRUE
                   OR item_type NOT IN ('food', 'drink', 'dessert')
                   OR item_name IS NULL
                   OR length(trim(item_name)) < 1
                   OR NEW.menu_item_name_snapshot IS DISTINCT FROM item_name
                   OR NEW.menu_item_type_snapshot IS DISTINCT FROM item_type THEN
                    RAISE EXCEPTION
                        'gaming add-on must snapshot an available same-company '
                        'food/drink/dessert item'
                        USING ERRCODE = '23514';
                END IF;

                IF NEW.variant_id IS NOT NULL THEN
                    SELECT variant.company_id,
                           variant.menu_item_id,
                           variant.is_active
                      INTO variant_company,
                           variant_item,
                           variant_active
                      FROM menu_variants variant
                     WHERE variant.id = NEW.variant_id;
                    IF variant_company IS DISTINCT FROM NEW.company_id
                       OR variant_item IS DISTINCT FROM NEW.menu_item_id
                       OR variant_active IS DISTINCT FROM TRUE THEN
                        RAISE EXCEPTION
                            'gaming add-on variant must be active and match '
                            'item/company provenance'
                            USING ERRCODE = '23514';
                    END IF;
                END IF;

                SELECT actor.company_id
                  INTO creator_company
                  FROM users actor
                 WHERE actor.id = NEW.created_by;
                SELECT branch.id
                  INTO creator_terminal_branch
                  FROM terminals terminal
                  JOIN branches branch ON branch.id = terminal.branch_id
                 WHERE terminal.id = NEW.created_terminal_id
                   AND branch.company_id = NEW.company_id;
                IF creator_company IS DISTINCT FROM NEW.company_id
                   OR creator_terminal_branch IS DISTINCT FROM source_branch
                   OR NEW.created_terminal_id IS DISTINCT FROM source_shift_terminal THEN
                    RAISE EXCEPTION
                        'gaming session add-on actor and terminal must match the source shift'
                        USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;

            IF ROW(
                NEW.id,
                NEW.company_id,
                NEW.gaming_session_id,
                NEW.client_line_id,
                NEW.menu_item_id,
                NEW.menu_item_name_snapshot,
                NEW.menu_item_type_snapshot,
                NEW.variant_id,
                NEW.variant_snapshot,
                NEW.modifiers,
                NEW.qty,
                NEW.catalog_unit_price_minor,
                NEW.unit_price_minor,
                NEW.line_total_minor,
                NEW.discount_minor,
                NEW.hsn_or_sac,
                NEW.tax_rate,
                NEW.taxable_value_minor,
                NEW.cgst_minor,
                NEW.sgst_minor,
                NEW.igst_minor,
                NEW.cess_minor,
                NEW.note,
                NEW.idempotency_key,
                NEW.request_hash,
                NEW.created_by,
                NEW.created_terminal_id,
                NEW.created_at
            ) IS DISTINCT FROM ROW(
                OLD.id,
                OLD.company_id,
                OLD.gaming_session_id,
                OLD.client_line_id,
                OLD.menu_item_id,
                OLD.menu_item_name_snapshot,
                OLD.menu_item_type_snapshot,
                OLD.variant_id,
                OLD.variant_snapshot,
                OLD.modifiers,
                OLD.qty,
                OLD.catalog_unit_price_minor,
                OLD.unit_price_minor,
                OLD.line_total_minor,
                OLD.discount_minor,
                OLD.hsn_or_sac,
                OLD.tax_rate,
                OLD.taxable_value_minor,
                OLD.cgst_minor,
                OLD.sgst_minor,
                OLD.igst_minor,
                OLD.cess_minor,
                OLD.note,
                OLD.idempotency_key,
                OLD.request_hash,
                OLD.created_by,
                OLD.created_terminal_id,
                OLD.created_at
            ) THEN
                RAISE EXCEPTION
                    'gaming session add-on financial/provenance fields are immutable'
                    USING ERRCODE = '23514';
            END IF;

            IF OLD.voided_at IS NOT NULL THEN
                RAISE EXCEPTION
                    'a gaming session add-on void cannot be changed or reversed'
                    USING ERRCODE = '23514';
            END IF;
            IF source_order IS NOT NULL
               OR source_session_status NOT IN ('active', 'paused', 'ended')
               OR source_shift_status IS DISTINCT FROM 'open' THEN
                RAISE EXCEPTION
                    'a gaming session add-on can only be voided before POS handoff on an open shift'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.voided_at IS NULL
               OR NEW.voided_by IS NULL
               OR NEW.void_reason IS NULL
               OR length(trim(NEW.void_reason)) < 3
               OR NEW.void_idempotency_key IS NULL
               OR length(trim(NEW.void_idempotency_key)) < 1
               OR NEW.void_request_hash !~ '^[0-9a-f]{64}$'
               OR NEW.voided_terminal_id IS NULL THEN
                RAISE EXCEPTION
                    'a gaming session add-on void must be populated atomically'
                    USING ERRCODE = '23514';
            END IF;

            SELECT actor.company_id
              INTO voider_company
              FROM users actor
             WHERE actor.id = NEW.voided_by;
            SELECT branch.id
              INTO void_terminal_branch
              FROM terminals terminal
              JOIN branches branch ON branch.id = terminal.branch_id
             WHERE terminal.id = NEW.voided_terminal_id
               AND branch.company_id = NEW.company_id;
            IF voider_company IS DISTINCT FROM NEW.company_id
               OR void_terminal_branch IS DISTINCT FROM source_branch
               OR NEW.voided_terminal_id IS DISTINCT FROM source_shift_terminal THEN
                RAISE EXCEPTION
                    'gaming session add-on void actor and terminal must match the source shift'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_gaming_session_addons_guard
        BEFORE INSERT OR UPDATE OR DELETE ON gaming_session_addons
        FOR EACH ROW EXECUTE FUNCTION guard_gaming_session_addon();

        CREATE FUNCTION guard_gaming_session_cancel_with_addons()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF NEW.status = 'cancelled'
               AND OLD.status IS DISTINCT FROM 'cancelled'
               AND EXISTS (
                    SELECT 1
                      FROM gaming_session_addons addon
                     WHERE addon.gaming_session_id = OLD.id
                       AND addon.company_id = OLD.company_id
                       AND addon.voided_at IS NULL
               ) THEN
                RAISE EXCEPTION
                    'active Gaming add-ons must be voided before session cancellation'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_gaming_sessions_guard_addon_cancel
        BEFORE UPDATE OF status ON gaming_sessions
        FOR EACH ROW EXECUTE FUNCTION guard_gaming_session_cancel_with_addons();
        """
    )


def _install_order_line_snapshot_function(*, include_session_addons: bool) -> None:
    addon_branch = """
            SELECT addon.menu_item_name_snapshot,
                   addon.menu_item_type_snapshot,
                   addon.company_id
              INTO staged_item_name, staged_item_type, staged_company
              FROM gaming_session_addons addon
              JOIN gaming_sessions gs
                ON gs.id = addon.gaming_session_id
               AND gs.order_id = NEW.order_id
             WHERE addon.client_line_id = NEW.client_line_id
               AND addon.menu_item_id = NEW.menu_item_id
               AND addon.voided_at IS NULL
             LIMIT 1;
            IF staged_company IS NOT NULL THEN
                IF staged_company IS DISTINCT FROM sale_company
                   OR (NEW.menu_item_name_snapshot IS NOT NULL
                       AND NEW.menu_item_name_snapshot IS DISTINCT FROM staged_item_name)
                   OR (NEW.menu_item_type_snapshot IS NOT NULL
                       AND NEW.menu_item_type_snapshot IS DISTINCT FROM staged_item_type) THEN
                    RAISE EXCEPTION
                        'gaming add-on order-line snapshot does not match its immutable source'
                        USING ERRCODE = '23514';
                END IF;
                NEW.menu_item_name_snapshot := staged_item_name;
                NEW.menu_item_type_snapshot := staged_item_type;
                NEW.reporting_snapshot_revision := 49;
                RETURN NEW;
            END IF;
    """ if include_session_addons else ""

    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION capture_order_line_reporting_snapshot()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            sold_item_name text;
            sold_item_type text;
            sold_item_company uuid;
            sale_company uuid;
            staged_item_name text;
            staged_item_type text;
            staged_company uuid;
        BEGIN
            IF TG_OP = 'UPDATE'
               AND NEW.order_id IS NOT DISTINCT FROM OLD.order_id
               AND NEW.menu_item_id IS NOT DISTINCT FROM OLD.menu_item_id THEN
                IF NEW.menu_item_name_snapshot IS DISTINCT FROM OLD.menu_item_name_snapshot
                   OR NEW.menu_item_type_snapshot IS DISTINCT FROM OLD.menu_item_type_snapshot
                   OR NEW.reporting_snapshot_revision
                        IS DISTINCT FROM OLD.reporting_snapshot_revision THEN
                    RAISE EXCEPTION
                        'sold item reporting snapshots are immutable'
                        USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;

            SELECT item.name, item.type, item.company_id
              INTO sold_item_name, sold_item_type, sold_item_company
              FROM menu_items item
             WHERE item.id = NEW.menu_item_id;
            SELECT sale.company_id
              INTO sale_company
              FROM orders sale
             WHERE sale.id = NEW.order_id;
            IF sold_item_name IS NULL
               OR char_length(trim(sold_item_name)) < 1
               OR sold_item_type IS NULL
               OR char_length(trim(sold_item_type)) < 1
               OR sale_company IS NULL
               OR sold_item_company IS DISTINCT FROM sale_company THEN
                RAISE EXCEPTION
                    'order line and sold item must have matching company provenance'
                    USING ERRCODE = '23514';
            END IF;

            {addon_branch}

            IF TG_OP = 'INSERT'
               AND (
                    (NEW.menu_item_name_snapshot IS NOT NULL
                     AND NEW.menu_item_name_snapshot IS DISTINCT FROM sold_item_name)
                    OR
                    (NEW.menu_item_type_snapshot IS NOT NULL
                     AND NEW.menu_item_type_snapshot IS DISTINCT FROM sold_item_type)
               ) THEN
                RAISE EXCEPTION
                    'client-supplied sold item snapshot does not match the catalogue'
                    USING ERRCODE = '23514';
            END IF;

            NEW.menu_item_name_snapshot := sold_item_name;
            NEW.menu_item_type_snapshot := sold_item_type;
            NEW.reporting_snapshot_revision := 49;
            RETURN NEW;
        END
        $$;
        """
    )


def upgrade() -> None:
    op.create_table(
        "gaming_session_addons",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("gaming_session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_line_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("menu_item_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("menu_item_name_snapshot", sa.String(length=200), nullable=False),
        sa.Column("menu_item_type_snapshot", sa.String(length=20), nullable=False),
        sa.Column("variant_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column(
            "variant_snapshot",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
        sa.Column(
            "modifiers",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=True,
        ),
        sa.Column("qty", sa.Integer(), nullable=False),
        sa.Column("catalog_unit_price_minor", sa.BigInteger(), nullable=False),
        sa.Column("unit_price_minor", sa.BigInteger(), nullable=False),
        sa.Column("line_total_minor", sa.BigInteger(), nullable=False),
        sa.Column("discount_minor", sa.BigInteger(), nullable=False),
        sa.Column("hsn_or_sac", sa.String(length=8), nullable=True),
        sa.Column("tax_rate", sa.Numeric(precision=5, scale=4), nullable=False),
        sa.Column("taxable_value_minor", sa.BigInteger(), nullable=False),
        sa.Column("cgst_minor", sa.BigInteger(), nullable=False),
        sa.Column("sgst_minor", sa.BigInteger(), nullable=False),
        sa.Column("igst_minor", sa.BigInteger(), nullable=False),
        sa.Column("cess_minor", sa.BigInteger(), nullable=False),
        sa.Column("note", sa.String(length=500), nullable=True),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("created_terminal_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("voided_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("voided_by", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("void_reason", sa.String(length=500), nullable=True),
        sa.Column("void_idempotency_key", sa.String(length=160), nullable=True),
        sa.Column("void_request_hash", sa.String(length=64), nullable=True),
        sa.Column("voided_terminal_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.CheckConstraint("qty > 0", name="ck_gaming_session_addon_qty_positive"),
        sa.CheckConstraint(
            "catalog_unit_price_minor >= 0 AND unit_price_minor >= 0 "
            "AND line_total_minor >= 0 AND discount_minor >= 0 "
            "AND taxable_value_minor >= 0 AND cgst_minor >= 0 "
            "AND sgst_minor >= 0 AND igst_minor >= 0 AND cess_minor >= 0",
            name="ck_gaming_session_addon_amounts_non_negative",
        ),
        sa.CheckConstraint(
            "line_total_minor = taxable_value_minor + cgst_minor + sgst_minor "
            "+ igst_minor + cess_minor",
            name="ck_gaming_session_addon_total_matches_tax_parts",
        ),
        sa.CheckConstraint(
            "variant_snapshot IS NULL OR jsonb_typeof(variant_snapshot) = 'object'",
            name="ck_gaming_session_addon_variant_snapshot_object",
        ),
        sa.CheckConstraint(
            "modifiers IS NULL OR jsonb_typeof(modifiers) = 'array'",
            name="ck_gaming_session_addon_modifiers_array",
        ),
        sa.CheckConstraint(
            "length(trim(menu_item_name_snapshot)) >= 1 "
            "AND menu_item_type_snapshot IN ('food', 'drink', 'dessert')",
            name="ck_gaming_session_addon_reporting_snapshot",
        ),
        sa.CheckConstraint(
            "length(trim(idempotency_key)) > 0 "
            "AND request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_gaming_session_addon_create_receipt",
        ),
        sa.CheckConstraint(
            "void_request_hash IS NULL OR void_request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_gaming_session_addon_void_hash",
        ),
        sa.CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL "
            "AND void_idempotency_key IS NULL AND void_request_hash IS NULL "
            "AND voided_terminal_id IS NULL) OR "
            "(voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3 "
            "AND length(trim(void_idempotency_key)) > 0 "
            "AND void_request_hash ~ '^[0-9a-f]{64}$' "
            "AND voided_terminal_id IS NOT NULL)",
            name="ck_gaming_session_addon_void_state",
        ),
        sa.ForeignKeyConstraint(
            ["company_id"],
            ["companies.id"],
            name="fk_gaming_session_addons_company_id_companies",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["gaming_session_id"],
            ["gaming_sessions.id"],
            name="fk_gaming_session_addon_session",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["menu_item_id"],
            ["menu_items.id"],
            name="fk_gaming_session_addon_menu_item",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["variant_id"],
            ["menu_variants.id"],
            name="fk_gaming_session_addon_variant",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["created_by"],
            ["users.id"],
            name="fk_gaming_session_addon_creator",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["created_terminal_id"],
            ["terminals.id"],
            name="fk_gaming_session_addon_created_terminal",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["voided_by"],
            ["users.id"],
            name="fk_gaming_session_addon_voider",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["voided_terminal_id"],
            ["terminals.id"],
            name="fk_gaming_session_addon_void_terminal",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id", name="pk_gaming_session_addons"),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_gaming_session_addon_company_idempotency",
        ),
        sa.UniqueConstraint(
            "gaming_session_id",
            "client_line_id",
            name="uq_gaming_session_addon_session_client_line",
        ),
    )
    op.create_index(
        "ix_gaming_session_addons_company_id",
        "gaming_session_addons",
        ["company_id"],
    )
    op.create_index(
        "ix_gaming_session_addons_gaming_session_id",
        "gaming_session_addons",
        ["gaming_session_id"],
    )
    op.create_index(
        "ix_gaming_session_addon_session_active",
        "gaming_session_addons",
        ["gaming_session_id", "voided_at"],
    )
    op.create_index(
        "uq_gaming_session_addon_company_void_idempotency",
        "gaming_session_addons",
        ["company_id", "void_idempotency_key"],
        unique=True,
        postgresql_where=sa.text("void_idempotency_key IS NOT NULL"),
    )
    _install_addon_guard()
    _install_order_line_snapshot_function(include_session_addons=True)


def downgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM gaming_session_addons LIMIT 1) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0055 after gaming session add-on activity'
                    USING HINT =
                        'Keep the immutable add-on and void history; restore the '
                        'application at revision 0055 or later.';
            END IF;
        END
        $$;
        """
    )
    _install_order_line_snapshot_function(include_session_addons=False)
    op.execute(
        "DROP TRIGGER IF EXISTS trg_gaming_sessions_guard_addon_cancel "
        "ON gaming_sessions"
    )
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_session_cancel_with_addons()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_gaming_session_addons_guard "
        "ON gaming_session_addons"
    )
    op.execute("DROP FUNCTION IF EXISTS guard_gaming_session_addon()")
    op.drop_index(
        "uq_gaming_session_addon_company_void_idempotency",
        table_name="gaming_session_addons",
    )
    op.drop_index(
        "ix_gaming_session_addon_session_active",
        table_name="gaming_session_addons",
    )
    op.drop_index(
        "ix_gaming_session_addons_gaming_session_id",
        table_name="gaming_session_addons",
    )
    op.drop_index(
        "ix_gaming_session_addons_company_id",
        table_name="gaming_session_addons",
    )
    op.drop_table("gaming_session_addons")
