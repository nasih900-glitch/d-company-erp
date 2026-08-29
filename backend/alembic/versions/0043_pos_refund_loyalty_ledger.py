"""Make POS refund loyalty reconciliation ledger-based.

Revision ID: 0043
Revises: 0042
Create Date: 2026-08-27

Mutable menu and membership configuration cannot reconstruct how many points
an historical sale actually awarded.  Forward checkouts now snapshot exact
loyalty facts, and each refund records one cumulative, append-only allocation.

For pre-ledger orders, only consumed ``points_redemptions`` are authoritative.
Those are backfilled as ``legacy_redemption_only`` so later refunds can restore
the exact redemption without inventing an earned-points reversal.  This
migration deliberately does not rewrite any existing customer balance.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0043"
down_revision = "0042"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "refunds",
        sa.Column("loyalty_reconciliation_state", sa.String(length=40)),
    )
    # Preserve append-only historical Refund rows byte-for-byte. NOT VALID
    # skips their pre-0043 NULLs while PostgreSQL still enforces a state on
    # every forward INSERT/UPDATE after this migration commits.
    op.execute(
        """
        ALTER TABLE refunds
        ADD CONSTRAINT ck_refund_loyalty_reconciliation_state
        CHECK (
            request_id IS NULL OR (
                loyalty_reconciliation_state IS NOT NULL
                AND loyalty_reconciliation_state IN (
                    'not_applicable', 'applied', 'legacy_redemption_restored',
                    'legacy_unknown'
                )
            )
        ) NOT VALID
        """
    )

    op.create_table(
        "order_loyalty_settlements",
        sa.Column(
            "id", postgresql.UUID(as_uuid=True), primary_key=True
        ),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "customer_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("order_paid_minor", sa.BigInteger(), nullable=False),
        sa.Column("points_redeemed", sa.Integer(), nullable=False),
        sa.Column("points_earned", sa.Integer(), nullable=False),
        sa.Column("rank_bonus_points", sa.Integer(), nullable=False),
        sa.Column("settled_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("provenance", sa.String(length=40), nullable=False),
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
        sa.UniqueConstraint(
            "order_id", name="uq_order_loyalty_settlement_order"
        ),
        sa.CheckConstraint(
            "order_paid_minor > 0",
            name="ck_order_loyalty_settlement_positive_paid",
        ),
        sa.CheckConstraint(
            "points_redeemed >= 0 AND points_earned >= 0 "
            "AND rank_bonus_points >= 0",
            name="ck_order_loyalty_settlement_nonnegative_points",
        ),
        sa.CheckConstraint(
            "provenance IN ('exact', 'legacy_redemption_only')",
            name="ck_order_loyalty_settlement_provenance",
        ),
        sa.CheckConstraint(
            "provenance <> 'legacy_redemption_only' "
            "OR (points_redeemed > 0 AND points_earned = 0 "
            "AND rank_bonus_points = 0)",
            name="ck_order_loyalty_settlement_legacy_scope",
        ),
    )
    op.create_index(
        "ix_order_loyalty_settlements_company_id",
        "order_loyalty_settlements",
        ["company_id"],
    )
    op.create_index(
        "ix_order_loyalty_settlements_customer_id",
        "order_loyalty_settlements",
        ["customer_id"],
    )

    # Restore only exact historical redemption facts.  Earned points and rank
    # bonuses cannot be reconstructed safely after catalog/multiplier changes.
    op.execute(
        """
        INSERT INTO order_loyalty_settlements (
            id,
            company_id,
            customer_id,
            order_id,
            order_paid_minor,
            points_redeemed,
            points_earned,
            rank_bonus_points,
            settled_at,
            provenance,
            created_at,
            updated_at
        )
        SELECT
            gen_random_uuid(),
            bill.company_id,
            redemption.customer_id,
            bill.id,
            paid.paid_minor,
            redemption.points_spent,
            0,
            0,
            redemption.consumed_at,
            'legacy_redemption_only',
            now(),
            now()
        FROM points_redemptions AS redemption
        JOIN orders AS bill ON bill.id = redemption.order_id
        JOIN LATERAL (
            SELECT COALESCE(sum(payment.amount_minor), 0)::bigint AS paid_minor
              FROM payments AS payment
             WHERE payment.order_id = bill.id
        ) AS paid ON true
        WHERE redemption.consumed_at IS NOT NULL
          AND redemption.points_spent > 0
          AND paid.paid_minor > 0
          AND bill.status IN ('paid', 'refunded')
        ON CONFLICT (order_id) DO NOTHING
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_order_loyalty_settlement()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            order_company uuid;
            order_customer uuid;
            order_status text;
            order_invoice_at timestamptz;
            paid_minor bigint;
            redeemed_points integer;
        BEGIN
            IF NEW.provenance <> 'exact' THEN
                RAISE EXCEPTION
                    'legacy loyalty backfill is closed after migration';
            END IF;
            SELECT
                bill.company_id,
                bill.customer_id,
                bill.status,
                bill.invoice_issued_at,
                COALESCE((
                    SELECT sum(payment.amount_minor)
                      FROM payments AS payment
                     WHERE payment.order_id = bill.id
                ), 0)::bigint,
                COALESCE((
                    SELECT redemption.points_spent
                      FROM points_redemptions AS redemption
                     WHERE redemption.order_id = bill.id
                       AND redemption.consumed_at IS NOT NULL
                ), 0)::integer
              INTO
                order_company,
                order_customer,
                order_status,
                order_invoice_at,
                paid_minor,
                redeemed_points
              FROM orders AS bill
             WHERE bill.id = NEW.order_id;
            IF order_company IS NULL
               OR order_company <> NEW.company_id
               OR order_customer IS DISTINCT FROM NEW.customer_id
               OR order_status NOT IN ('paid', 'refunded')
               OR order_invoice_at IS NULL
               OR NEW.settled_at IS DISTINCT FROM order_invoice_at
               OR paid_minor <> NEW.order_paid_minor
               OR redeemed_points <> NEW.points_redeemed THEN
                RAISE EXCEPTION
                    'order loyalty settlement source facts are inconsistent';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_order_loyalty_settlement_guard
        BEFORE INSERT ON order_loyalty_settlements
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_guard_order_loyalty_settlement()
        """
    )

    op.create_table(
        "refund_loyalty_adjustments",
        sa.Column(
            "id", postgresql.UUID(as_uuid=True), primary_key=True
        ),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "customer_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "order_loyalty_settlement_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("order_loyalty_settlements.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "refund_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("refunds.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("cumulative_refunded_minor", sa.BigInteger(), nullable=False),
        sa.Column("redeemed_points_restored", sa.Integer(), nullable=False),
        sa.Column("points_earned_reversed", sa.Integer(), nullable=False),
        sa.Column("rank_bonus_points_reversed", sa.Integer(), nullable=False),
        sa.Column("net_points_delta", sa.Integer(), nullable=False),
        sa.Column("balance_before", sa.Integer(), nullable=False),
        sa.Column("balance_after", sa.Integer(), nullable=False),
        sa.Column("applied_at", sa.DateTime(timezone=True), nullable=False),
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
        sa.UniqueConstraint(
            "refund_id", name="uq_refund_loyalty_adjustment_refund"
        ),
        sa.CheckConstraint(
            "cumulative_refunded_minor > 0",
            name="ck_refund_loyalty_adjustment_positive_refunded",
        ),
        sa.CheckConstraint(
            "redeemed_points_restored >= 0 AND points_earned_reversed >= 0 "
            "AND rank_bonus_points_reversed >= 0",
            name="ck_refund_loyalty_adjustment_nonnegative_components",
        ),
        sa.CheckConstraint(
            "net_points_delta = redeemed_points_restored "
            "- points_earned_reversed - rank_bonus_points_reversed",
            name="ck_refund_loyalty_adjustment_net_delta",
        ),
        sa.CheckConstraint(
            "balance_after = balance_before + net_points_delta",
            name="ck_refund_loyalty_adjustment_balance_delta",
        ),
    )
    op.create_index(
        "ix_refund_loyalty_adjustments_company_id",
        "refund_loyalty_adjustments",
        ["company_id"],
    )
    op.create_index(
        "ix_refund_loyalty_adjustments_customer_id",
        "refund_loyalty_adjustments",
        ["customer_id"],
    )
    op.create_index(
        "ix_refund_loyalty_adjustments_order_id",
        "refund_loyalty_adjustments",
        ["order_id"],
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_refund_loyalty_adjustment()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            settlement_row order_loyalty_settlements%ROWTYPE;
            refund_row refunds%ROWTYPE;
            cumulative_money bigint;
            restored_before bigint;
            earned_before bigint;
            bonus_before bigint;
            restored_target bigint;
            earned_target bigint;
            bonus_target bigint;
        BEGIN
            SELECT * INTO settlement_row
              FROM order_loyalty_settlements
             WHERE id = NEW.order_loyalty_settlement_id;
            SELECT * INTO refund_row
              FROM refunds
             WHERE id = NEW.refund_id;
            IF settlement_row.id IS NULL OR refund_row.id IS NULL
               OR settlement_row.company_id <> NEW.company_id
               OR settlement_row.customer_id <> NEW.customer_id
               OR settlement_row.order_id <> NEW.order_id
               OR refund_row.company_id <> NEW.company_id
               OR refund_row.order_id <> NEW.order_id THEN
                RAISE EXCEPTION
                    'refund loyalty adjustment source scope is inconsistent';
            END IF;

            SELECT COALESCE(sum(amount_minor), 0)::bigint
              INTO cumulative_money
              FROM refunds
             WHERE order_id = NEW.order_id;
            IF cumulative_money <> NEW.cumulative_refunded_minor
               OR cumulative_money > settlement_row.order_paid_minor THEN
                RAISE EXCEPTION
                    'refund loyalty cumulative amount is inconsistent';
            END IF;

            SELECT
                COALESCE(sum(redeemed_points_restored), 0)::bigint,
                COALESCE(sum(points_earned_reversed), 0)::bigint,
                COALESCE(sum(rank_bonus_points_reversed), 0)::bigint
              INTO restored_before, earned_before, bonus_before
              FROM refund_loyalty_adjustments
             WHERE order_loyalty_settlement_id = settlement_row.id;

            restored_target := (
                settlement_row.points_redeemed::bigint * cumulative_money
            ) / settlement_row.order_paid_minor;
            IF settlement_row.provenance = 'exact' THEN
                earned_target := (
                    settlement_row.points_earned::bigint * cumulative_money
                ) / settlement_row.order_paid_minor;
                bonus_target := (
                    settlement_row.rank_bonus_points::bigint * cumulative_money
                ) / settlement_row.order_paid_minor;
            ELSE
                earned_target := 0;
                bonus_target := 0;
            END IF;

            IF restored_before + NEW.redeemed_points_restored <> restored_target
               OR earned_before + NEW.points_earned_reversed <> earned_target
               OR bonus_before + NEW.rank_bonus_points_reversed <> bonus_target THEN
                RAISE EXCEPTION
                    'refund loyalty cumulative point allocation is inconsistent';
            END IF;
            IF (settlement_row.provenance = 'exact'
                AND refund_row.loyalty_reconciliation_state
                    IS DISTINCT FROM 'applied')
               OR (settlement_row.provenance = 'legacy_redemption_only'
                AND refund_row.loyalty_reconciliation_state
                    IS DISTINCT FROM 'legacy_redemption_restored') THEN
                RAISE EXCEPTION
                    'refund loyalty reconciliation state is inconsistent';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_refund_loyalty_adjustment_guard
        BEFORE INSERT ON refund_loyalty_adjustments
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_guard_refund_loyalty_adjustment()
        """
    )

    for table_name, trigger_name in (
        (
            "order_loyalty_settlements",
            "trg_order_loyalty_settlements_immutable",
        ),
        (
            "refund_loyalty_adjustments",
            "trg_refund_loyalty_adjustments_immutable",
        ),
    ):
        op.execute(
            f"""
            CREATE TRIGGER {trigger_name}
            BEFORE UPDATE OR DELETE ON {table_name}
            FOR EACH ROW EXECUTE FUNCTION dcompany_guard_pos_refund_immutable()
            """
        )


def downgrade() -> None:
    # Exact checkout facts and applied refund deltas are financial/customer
    # audit history.  Refuse to erase them.  Redemption-only migration rows are
    # reproducible from their untouched source and may be removed safely.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM refund_loyalty_adjustments)
               OR EXISTS (
                    SELECT 1 FROM order_loyalty_settlements
                     WHERE provenance = 'exact'
               )
               OR EXISTS (
                    SELECT 1 FROM refunds
                     WHERE loyalty_reconciliation_state IS NOT NULL
               ) THEN
                RAISE EXCEPTION
                    '0043 downgrade refused after loyalty ledger activity';
            END IF;
        END $$
        """
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_refund_loyalty_adjustments_immutable "
        "ON refund_loyalty_adjustments"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_loyalty_settlements_immutable "
        "ON order_loyalty_settlements"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_loyalty_settlement_guard "
        "ON order_loyalty_settlements"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_order_loyalty_settlement()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_refund_loyalty_adjustment_guard "
        "ON refund_loyalty_adjustments"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_refund_loyalty_adjustment()")
    op.drop_index(
        "ix_refund_loyalty_adjustments_order_id",
        table_name="refund_loyalty_adjustments",
    )
    op.drop_index(
        "ix_refund_loyalty_adjustments_customer_id",
        table_name="refund_loyalty_adjustments",
    )
    op.drop_index(
        "ix_refund_loyalty_adjustments_company_id",
        table_name="refund_loyalty_adjustments",
    )
    op.drop_table("refund_loyalty_adjustments")
    op.drop_index(
        "ix_order_loyalty_settlements_customer_id",
        table_name="order_loyalty_settlements",
    )
    op.drop_index(
        "ix_order_loyalty_settlements_company_id",
        table_name="order_loyalty_settlements",
    )
    op.drop_table("order_loyalty_settlements")
    op.drop_constraint(
        "ck_refund_loyalty_reconciliation_state",
        "refunds",
        type_="check",
    )
    op.drop_column("refunds", "loyalty_reconciliation_state")
