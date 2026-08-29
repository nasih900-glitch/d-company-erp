"""Freeze sold item name and type for historically correct reporting.

Revision ID: 0049
Revises: 0048
Create Date: 2026-08-28

Reports previously joined an OrderLine back to the mutable MenuItem row. A
later rename or type/category change therefore rewrote old receipts and
historical category revenue. This revision backfills the catalogue facts that
were true at migration time and makes every forward line capture authoritative
server-side snapshots.

The revision fails closed when legacy tenant provenance is incoherent. Its
downgrade is also fail closed after forward line activity or after a catalogue
change would make dropping the snapshots lossy.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0049"
down_revision = "0048"
branch_labels = None
depends_on = None


def _assert_legacy_line_provenance() -> None:
    op.execute(
        """
        DO $$
        DECLARE
            bad_line uuid;
        BEGIN
            SELECT line.id
              INTO bad_line
              FROM order_lines line
              LEFT JOIN orders sale ON sale.id = line.order_id
              LEFT JOIN menu_items item ON item.id = line.menu_item_id
             WHERE sale.id IS NULL
                OR item.id IS NULL
                OR sale.company_id IS DISTINCT FROM item.company_id
                OR item.name IS NULL
                OR char_length(trim(item.name)) < 1
                OR item.type IS NULL
                OR char_length(trim(item.type)) < 1
             ORDER BY line.id
             LIMIT 1;
            IF bad_line IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot snapshot order-line reporting facts: line % has '
                    'missing, blank, or cross-company catalogue provenance',
                    bad_line
                    USING HINT =
                        'Reconcile the original sold item identity explicitly; '
                        'do not guess or reassign historical revenue.';
            END IF;
        END
        $$;
        """
    )


def upgrade() -> None:
    _assert_legacy_line_provenance()

    op.add_column(
        "order_lines",
        sa.Column("menu_item_name_snapshot", sa.String(length=200), nullable=True),
    )
    op.add_column(
        "order_lines",
        sa.Column("menu_item_type_snapshot", sa.String(length=20), nullable=True),
    )
    # NULL identifies backfilled history. Every forward insert is marked 49 so
    # downgrade cannot silently remove a source fact accepted under this
    # contract even when the catalogue has not changed yet.
    op.add_column(
        "order_lines",
        sa.Column(
            "reporting_snapshot_revision",
            sa.SmallInteger(),
            nullable=True,
            server_default=sa.text("49"),
        ),
    )

    op.execute(
        """
        UPDATE order_lines line
           SET menu_item_name_snapshot = item.name,
               menu_item_type_snapshot = item.type,
               reporting_snapshot_revision = NULL
          FROM menu_items item
         WHERE item.id = line.menu_item_id
        """
    )
    op.alter_column(
        "order_lines", "menu_item_name_snapshot", existing_type=sa.String(200), nullable=False
    )
    op.alter_column(
        "order_lines", "menu_item_type_snapshot", existing_type=sa.String(20), nullable=False
    )
    op.create_check_constraint(
        "ck_order_line_reporting_snapshot_name",
        "order_lines",
        "char_length(trim(menu_item_name_snapshot)) >= 1",
    )
    op.create_check_constraint(
        "ck_order_line_reporting_snapshot_type",
        "order_lines",
        "char_length(trim(menu_item_type_snapshot)) >= 1",
    )
    op.create_check_constraint(
        "ck_order_line_reporting_snapshot_revision",
        "order_lines",
        "reporting_snapshot_revision IS NULL OR reporting_snapshot_revision = 49",
    )

    op.execute(
        """
        CREATE FUNCTION capture_order_line_reporting_snapshot()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            sold_item_name text;
            sold_item_type text;
            sold_item_company uuid;
            sale_company uuid;
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

        CREATE TRIGGER trg_order_lines_reporting_snapshot
        BEFORE INSERT OR UPDATE ON order_lines
        FOR EACH ROW EXECUTE FUNCTION capture_order_line_reporting_snapshot();
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM order_lines line
                  JOIN menu_items item ON item.id = line.menu_item_id
                 WHERE line.reporting_snapshot_revision = 49
                    OR line.menu_item_name_snapshot IS DISTINCT FROM item.name
                    OR line.menu_item_type_snapshot IS DISTINCT FROM item.type
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0049 after forward order-line activity or '
                    'catalogue history has diverged'
                    USING HINT =
                        'Keep immutable sold-item snapshots in place; restore the '
                        'application at revision 0049 or later.';
            END IF;
        END
        $$;
        """
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_lines_reporting_snapshot ON order_lines"
    )
    op.execute("DROP FUNCTION IF EXISTS capture_order_line_reporting_snapshot()")
    op.drop_constraint(
        "ck_order_line_reporting_snapshot_revision", "order_lines", type_="check"
    )
    op.drop_constraint(
        "ck_order_line_reporting_snapshot_type", "order_lines", type_="check"
    )
    op.drop_constraint(
        "ck_order_line_reporting_snapshot_name", "order_lines", type_="check"
    )
    op.drop_column("order_lines", "reporting_snapshot_revision")
    op.drop_column("order_lines", "menu_item_type_snapshot")
    op.drop_column("order_lines", "menu_item_name_snapshot")
