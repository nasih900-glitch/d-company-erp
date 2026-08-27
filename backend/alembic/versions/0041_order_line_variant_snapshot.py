"""Persist immutable POS variant snapshots on order lines.

Revision ID: 0041
Revises: 0040
Create Date: 2026-08-27

``variant_id`` is a nullable catalog reference with ``ON DELETE SET NULL``.
That reference is useful for traceability, but it cannot preserve the name and
price printed on a historical receipt.  This revision adds the immutable JSON
snapshot used by POS, receipts, and KDS while leaving ``modifiers`` dedicated
to modifier-option snapshots.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0041"
down_revision = "0040"
branch_labels = None
depends_on = None


_CHECKOUT_VERSION_FUNCTION_WITH_VARIANT = """
CREATE OR REPLACE FUNCTION dcompany_bump_order_checkout_version_from_line()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND
       OLD.order_id IS NOT DISTINCT FROM NEW.order_id AND
       OLD.client_line_id IS NOT DISTINCT FROM NEW.client_line_id AND
       OLD.menu_item_id IS NOT DISTINCT FROM NEW.menu_item_id AND
       OLD.variant_id IS NOT DISTINCT FROM NEW.variant_id AND
       OLD.variant_snapshot IS NOT DISTINCT FROM NEW.variant_snapshot AND
       OLD.modifiers IS NOT DISTINCT FROM NEW.modifiers AND
       OLD.qty IS NOT DISTINCT FROM NEW.qty AND
       OLD.unit_price_minor IS NOT DISTINCT FROM NEW.unit_price_minor AND
       OLD.line_total_minor IS NOT DISTINCT FROM NEW.line_total_minor AND
       OLD.discount_minor IS NOT DISTINCT FROM NEW.discount_minor AND
       OLD.hsn_or_sac IS NOT DISTINCT FROM NEW.hsn_or_sac AND
       OLD.tax_rate IS NOT DISTINCT FROM NEW.tax_rate AND
       OLD.taxable_value_minor IS NOT DISTINCT FROM NEW.taxable_value_minor AND
       OLD.cgst_minor IS NOT DISTINCT FROM NEW.cgst_minor AND
       OLD.sgst_minor IS NOT DISTINCT FROM NEW.sgst_minor AND
       OLD.igst_minor IS NOT DISTINCT FROM NEW.igst_minor AND
       OLD.cess_minor IS NOT DISTINCT FROM NEW.cess_minor AND
       OLD.note IS NOT DISTINCT FROM NEW.note AND
       OLD.voided_at IS NOT DISTINCT FROM NEW.voided_at AND
       OLD.voided_by IS NOT DISTINCT FROM NEW.voided_by AND
       OLD.void_reason IS NOT DISTINCT FROM NEW.void_reason THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' OR
       (TG_OP = 'UPDATE' AND OLD.order_id IS DISTINCT FROM NEW.order_id) THEN
        UPDATE orders
           SET checkout_version = checkout_version + 1
         WHERE id = OLD.order_id;
    END IF;

    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        UPDATE orders
           SET checkout_version = checkout_version + 1
         WHERE id = NEW.order_id;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$
"""


_CHECKOUT_VERSION_FUNCTION_LEGACY = _CHECKOUT_VERSION_FUNCTION_WITH_VARIANT.replace(
    "       OLD.variant_snapshot IS NOT DISTINCT FROM NEW.variant_snapshot AND\n",
    "",
)


def _create_update_trigger(*, include_variant_snapshot: bool) -> None:
    variant_column = "            variant_snapshot,\n" if include_variant_snapshot else ""
    op.execute(
        f"""
        CREATE TRIGGER trg_order_lines_checkout_version_update
        AFTER UPDATE OF
            order_id,
            client_line_id,
            menu_item_id,
            variant_id,
{variant_column}            modifiers,
            qty,
            unit_price_minor,
            line_total_minor,
            discount_minor,
            hsn_or_sac,
            tax_rate,
            taxable_value_minor,
            cgst_minor,
            sgst_minor,
            igst_minor,
            cess_minor,
            note,
            voided_at,
            voided_by,
            void_reason
        ON order_lines
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_bump_order_checkout_version_from_line()
        """
    )


def upgrade() -> None:
    op.add_column(
        "order_lines",
        sa.Column("variant_snapshot", postgresql.JSONB(astext_type=sa.Text())),
    )
    op.create_check_constraint(
        "ck_order_line_variant_snapshot_object",
        "order_lines",
        "variant_snapshot IS NULL OR jsonb_typeof(variant_snapshot) = 'object'",
    )

    # Keep optimistic checkout claims sensitive to a snapshot-only mutation.
    # The insert/delete trigger is unchanged; only the UPDATE column list and
    # equality short-circuit need the new field.
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_lines_checkout_version_update "
        "ON order_lines"
    )
    op.execute(_CHECKOUT_VERSION_FUNCTION_WITH_VARIANT)
    _create_update_trigger(include_variant_snapshot=True)


def downgrade() -> None:
    # Dropping a populated snapshot would make historical receipts dependent
    # on mutable/deletable catalog data.  Refuse that destructive downgrade.
    op.execute(
        """
        DO $$
        DECLARE
            snapshot_count bigint;
        BEGIN
            SELECT count(*)
              INTO snapshot_count
              FROM order_lines
             WHERE variant_snapshot IS NOT NULL;
            IF snapshot_count > 0 THEN
                RAISE EXCEPTION USING
                    MESSAGE = format(
                        '0041 downgrade refused: %s order line(s) contain immutable variant snapshots',
                        snapshot_count
                    ),
                    HINT = 'Export and reconcile historical POS customization evidence before downgrading.';
            END IF;
        END;
        $$
        """
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_lines_checkout_version_update "
        "ON order_lines"
    )
    op.execute(_CHECKOUT_VERSION_FUNCTION_LEGACY)
    _create_update_trigger(include_variant_snapshot=False)
    op.drop_constraint(
        "ck_order_line_variant_snapshot_object",
        "order_lines",
        type_="check",
    )
    op.drop_column("order_lines", "variant_snapshot")
