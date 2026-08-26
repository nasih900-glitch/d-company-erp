"""Durable table rounds, kitchen release, and reasoned line cancellation.

Revision ID: 0037
Revises: 0036
Create Date: 2026-08-26

The order row remains the serialization point for every bill mutation. Stable
client line IDs make offline action replay safe, explicit kitchen release
metadata prevents unpaid direct-POS work from appearing on KDS, and reasoned
soft voids remain visible until kitchen acknowledges them.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0037"
down_revision = "0036"
branch_labels = None
depends_on = None

_LEGACY_VOID_REASON = "Legacy cancellation - reason not recorded"
_LEGACY_VOID_ACTOR_REASON = (
    "Legacy cancellation - actor and reason not recorded"
)


def upgrade() -> None:
    op.add_column(
        "order_lines",
        sa.Column("client_line_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "order_lines",
        sa.Column("kitchen_released_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "order_lines",
        sa.Column("kitchen_round_no", sa.Integer(), nullable=True),
    )
    op.add_column(
        "order_lines",
        sa.Column("void_reason", sa.String(length=500), nullable=True),
    )
    op.add_column(
        "order_lines",
        sa.Column(
            "kitchen_void_acknowledged_at",
            sa.DateTime(timezone=True),
            nullable=True,
        ),
    )
    op.add_column(
        "order_lines",
        sa.Column(
            "kitchen_void_acknowledged_by",
            postgresql.UUID(as_uuid=True),
            nullable=True,
        ),
    )
    op.create_foreign_key(
        "fk_order_lines_kitchen_void_acknowledged_by_users",
        "order_lines",
        "users",
        ["kitchen_void_acknowledged_by"],
        ["id"],
        ondelete="RESTRICT",
    )

    # Preserve only work the old contract could truthfully have released:
    # active table service rounds and paid historical tickets. Before 0037 a
    # direct POS draft received a parent kitchen_state at creation time even
    # though payment had not succeeded, so kitchen_state alone is not release
    # evidence. In particular, open/held/void direct tickets stay unreleased.
    # This happens before the checkout-version line trigger is installed
    # because it is a representational backfill, not a new bill mutation.
    op.execute(
        """
        UPDATE order_lines AS line
           SET kitchen_released_at = line.created_at,
               kitchen_round_no = 1
          FROM orders AS bill
          JOIN menu_items AS item
            ON item.company_id = bill.company_id
         WHERE line.order_id = bill.id
           AND line.menu_item_id = item.id
           AND item.type IN ('food', 'drink', 'dessert')
           AND bill.kitchen_state IS NOT NULL
           AND (
                bill.status = 'paid'
                OR (
                    bill.table_id IS NOT NULL
                    AND bill.status IN ('open', 'held')
                )
           )
           AND line.kitchen_released_at IS NULL
           AND line.kitchen_round_no IS NULL
        """
    )

    # 0001 allowed a hard-deleted user to SET NULL on historical void actor
    # evidence. Preserve that truth explicitly rather than inventing an actor.
    # A timestamp-less actor is nonsensical and cannot be repaired safely, so
    # fail with line IDs instead of silently changing production history.
    op.execute(
        """
        DO $$
        DECLARE
            invalid_line_ids uuid[];
        BEGIN
            SELECT array_agg(id ORDER BY id)
              INTO invalid_line_ids
              FROM order_lines
             WHERE voided_at IS NULL
               AND voided_by IS NOT NULL;

            IF invalid_line_ids IS NOT NULL THEN
                RAISE EXCEPTION USING
                    MESSAGE = format(
                        '0037 found order line void actors without timestamps: lines=%s',
                        invalid_line_ids
                    ),
                    HINT = 'Reconcile these historical half-void rows before rerunning the migration.';
            END IF;
        END;
        $$
        """
    )
    op.execute(
        sa.text(
            """
            UPDATE order_lines
               SET void_reason = CASE
                   WHEN voided_by IS NULL THEN :missing_actor_reason
                   ELSE :missing_reason
               END
             WHERE voided_at IS NOT NULL
               AND void_reason IS NULL
            """
        ).bindparams(
            missing_actor_reason=_LEGACY_VOID_ACTOR_REASON,
            missing_reason=_LEGACY_VOID_REASON,
        )
    )

    # A void actor is durable audit evidence. The previous SET NULL action is
    # incompatible with the paired provenance invariant below.
    op.drop_constraint(
        "order_lines_voided_by_fkey", "order_lines", type_="foreignkey"
    )
    op.create_foreign_key(
        "order_lines_voided_by_fkey",
        "order_lines",
        "users",
        ["voided_by"],
        ["id"],
        ondelete="RESTRICT",
    )

    op.create_check_constraint(
        "ck_order_line_kitchen_release_pair",
        "order_lines",
        "(kitchen_released_at IS NULL AND kitchen_round_no IS NULL) OR "
        "(kitchen_released_at IS NOT NULL AND kitchen_round_no IS NOT NULL "
        "AND kitchen_round_no > 0)",
    )
    # The marker branch is a narrow quarantine for legacy rows whose actor was
    # already erased by 0001's SET NULL FK. All new API writes require a real
    # actor and non-blank reason. Unlike NOT VALID, this fully validated check
    # also permits later KDS acknowledgement of migrated legacy cancellations.
    op.create_check_constraint(
        "ck_order_line_void_provenance",
        "order_lines",
        "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) OR "
        "(voided_at IS NOT NULL AND voided_by IS NOT NULL "
        "AND void_reason IS NOT NULL AND char_length(trim(void_reason)) >= 1) OR "
        "(voided_at IS NOT NULL AND voided_by IS NULL "
        f"AND void_reason = '{_LEGACY_VOID_ACTOR_REASON}')",
    )
    op.create_check_constraint(
        "ck_order_line_kitchen_void_ack_pair",
        "order_lines",
        "(kitchen_void_acknowledged_at IS NULL "
        "AND kitchen_void_acknowledged_by IS NULL) OR "
        "(kitchen_void_acknowledged_at IS NOT NULL "
        "AND kitchen_void_acknowledged_by IS NOT NULL "
        "AND voided_at IS NOT NULL AND kitchen_released_at IS NOT NULL)",
    )

    # The anonymous-marker branch above exists only for rows proven historical
    # by this migration. A trigger prevents any future insert/transition from
    # manufacturing the same marker and also makes established void evidence
    # immutable. KDS acknowledgement does not update these columns and remains
    # legal for migrated rows.
    op.execute(
        """
        CREATE FUNCTION dcompany_guard_order_line_void_provenance()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                IF NEW.voided_at IS NOT NULL AND NEW.voided_by IS NULL THEN
                    RAISE EXCEPTION USING
                        ERRCODE = 'check_violation',
                        CONSTRAINT = 'ck_order_line_void_provenance',
                        MESSAGE = 'new order line cancellations require an actor';
                END IF;
                RETURN NEW;
            END IF;

            IF OLD.voided_at IS NOT NULL AND (
                NEW.voided_at IS DISTINCT FROM OLD.voided_at
                OR NEW.voided_by IS DISTINCT FROM OLD.voided_by
                OR NEW.void_reason IS DISTINCT FROM OLD.void_reason
            ) THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'check_violation',
                    CONSTRAINT = 'ck_order_line_void_provenance',
                    MESSAGE = 'order line cancellation provenance is immutable';
            END IF;

            IF OLD.voided_at IS NULL
               AND NEW.voided_at IS NOT NULL
               AND NEW.voided_by IS NULL THEN
                RAISE EXCEPTION USING
                    ERRCODE = 'check_violation',
                    CONSTRAINT = 'ck_order_line_void_provenance',
                    MESSAGE = 'order line cancellations require an actor';
            END IF;
            RETURN NEW;
        END;
        $$;

        CREATE TRIGGER trg_order_lines_void_provenance_guard
        BEFORE INSERT OR UPDATE OF voided_at, voided_by, void_reason
        ON order_lines
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_guard_order_line_void_provenance()
        """
    )

    op.create_index(
        "uq_order_lines_order_client_line",
        "order_lines",
        ["order_id", "client_line_id"],
        unique=True,
        postgresql_where=sa.text("client_line_id IS NOT NULL"),
    )
    op.create_index(
        "ix_order_lines_kitchen_released_active",
        "order_lines",
        ["order_id", "kitchen_released_at"],
        postgresql_where=sa.text(
            "kitchen_released_at IS NOT NULL AND voided_at IS NULL"
        ),
    )
    op.create_index(
        "ix_order_lines_kitchen_pending_cancel",
        "order_lines",
        ["order_id", "voided_at"],
        postgresql_where=sa.text(
            "kitchen_released_at IS NOT NULL AND voided_at IS NOT NULL "
            "AND kitchen_void_acknowledged_at IS NULL"
        ),
    )
    op.create_index(
        "ix_order_lines_kitchen_void_acknowledged_by",
        "order_lines",
        ["kitchen_void_acknowledged_by"],
        postgresql_where=sa.text("kitchen_void_acknowledged_by IS NOT NULL"),
    )

    # Fail with an actionable diagnostic rather than letting CREATE UNIQUE
    # INDEX emit an opaque duplicate-key error. No historical bill is merged,
    # deleted, or silently selected as the winner.
    op.execute(
        """
        DO $$
        DECLARE
            duplicate record;
        BEGIN
            SELECT
                company_id,
                branch_id,
                table_id,
                array_agg(id ORDER BY created_at, id) AS order_ids
              INTO duplicate
              FROM orders
             WHERE table_id IS NOT NULL
               AND status IN ('open', 'held')
             GROUP BY company_id, branch_id, table_id
            HAVING count(*) > 1
             ORDER BY company_id, branch_id, table_id
             LIMIT 1;

            IF FOUND THEN
                RAISE EXCEPTION USING
                    MESSAGE = format(
                        '0037 cannot enforce one active table bill: company=%s branch=%s table=%s orders=%s',
                        duplicate.company_id,
                        duplicate.branch_id,
                        duplicate.table_id,
                        duplicate.order_ids
                    ),
                    HINT = 'Resolve the duplicate bills through the normal void/payment audit workflow, then rerun the migration.';
            END IF;
        END;
        $$
        """
    )
    op.create_index(
        "uq_orders_active_table_bill",
        "orders",
        ["company_id", "branch_id", "table_id"],
        unique=True,
        postgresql_where=sa.text(
            "table_id IS NOT NULL AND status IN ('open', 'held')"
        ),
    )

    # Claims snapshot the parent checkout_version. The trigger covers writes
    # outside this API as well, while deliberately ignoring kitchen progress,
    # release timing, and acknowledgement-only updates.
    op.execute(
        """
        CREATE FUNCTION dcompany_bump_order_checkout_version_from_line()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF TG_OP = 'UPDATE' AND
               OLD.order_id IS NOT DISTINCT FROM NEW.order_id AND
               OLD.client_line_id IS NOT DISTINCT FROM NEW.client_line_id AND
               OLD.menu_item_id IS NOT DISTINCT FROM NEW.menu_item_id AND
               OLD.variant_id IS NOT DISTINCT FROM NEW.variant_id AND
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
    )
    op.execute(
        """
        CREATE TRIGGER trg_order_lines_checkout_version_insert_delete
        AFTER INSERT OR DELETE ON order_lines
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_bump_order_checkout_version_from_line()
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_order_lines_checkout_version_update
        AFTER UPDATE OF
            order_id,
            client_line_id,
            menu_item_id,
            variant_id,
            modifiers,
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


def downgrade() -> None:
    # Downgrading may remove representation-only round-1 backfill, but must
    # never erase offline identity, later rounds, cancellation provenance, or
    # a post-creation direct-POS kitchen release.
    op.execute(
        """
        DO $$
        DECLARE
            evidence_count bigint;
        BEGIN
            SELECT count(*)
              INTO evidence_count
              FROM order_lines
             WHERE client_line_id IS NOT NULL
                OR (
                    void_reason IS NOT NULL
                    AND void_reason NOT IN (
                        'Legacy cancellation - reason not recorded',
                        'Legacy cancellation - actor and reason not recorded'
                    )
                )
                OR kitchen_void_acknowledged_at IS NOT NULL
                OR kitchen_void_acknowledged_by IS NOT NULL
                OR kitchen_round_no > 1
                OR (
                    kitchen_released_at IS NOT NULL
                    AND kitchen_released_at IS DISTINCT FROM created_at
                );
            IF evidence_count > 0 THEN
                RAISE EXCEPTION USING
                    MESSAGE = format(
                        '0037 downgrade refused: %s order line(s) contain durable cafe workflow evidence',
                        evidence_count
                    ),
                    HINT = 'Export and reconcile the line identity, release, round, void, and acknowledgement evidence before any downgrade.';
            END IF;
        END;
        $$
        """
    )

    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_lines_checkout_version_update ON order_lines"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_lines_checkout_version_insert_delete ON order_lines"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS dcompany_bump_order_checkout_version_from_line()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_lines_void_provenance_guard ON order_lines"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS dcompany_guard_order_line_void_provenance()"
    )
    op.drop_index("uq_orders_active_table_bill", table_name="orders")
    op.drop_index(
        "ix_order_lines_kitchen_void_acknowledged_by", table_name="order_lines"
    )
    op.drop_index(
        "ix_order_lines_kitchen_pending_cancel", table_name="order_lines"
    )
    op.drop_index(
        "ix_order_lines_kitchen_released_active", table_name="order_lines"
    )
    op.drop_index("uq_order_lines_order_client_line", table_name="order_lines")
    op.drop_constraint(
        "ck_order_line_kitchen_void_ack_pair", "order_lines", type_="check"
    )
    op.drop_constraint(
        "ck_order_line_void_provenance", "order_lines", type_="check"
    )
    op.drop_constraint(
        "order_lines_voided_by_fkey", "order_lines", type_="foreignkey"
    )
    op.create_foreign_key(
        "order_lines_voided_by_fkey",
        "order_lines",
        "users",
        ["voided_by"],
        ["id"],
        ondelete="SET NULL",
    )
    op.drop_constraint(
        "ck_order_line_kitchen_release_pair", "order_lines", type_="check"
    )
    op.drop_constraint(
        "fk_order_lines_kitchen_void_acknowledged_by_users",
        "order_lines",
        type_="foreignkey",
    )
    op.drop_column("order_lines", "kitchen_void_acknowledged_by")
    op.drop_column("order_lines", "kitchen_void_acknowledged_at")
    op.drop_column("order_lines", "void_reason")
    op.drop_column("order_lines", "kitchen_round_no")
    op.drop_column("order_lines", "kitchen_released_at")
    op.drop_column("order_lines", "client_line_id")
