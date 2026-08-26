"""Record paid memberships as real, shift-bound financial settlements.

Revision ID: 0033
Revises: 0032
Create Date: 2026-08-26

Historical ``CustomerMembership.amount_paid_minor`` values are deliberately
not backfilled. They prove the price of an entitlement that was minted, not
that cash/card/UPI was actually received. Any old terms need a separate,
owner-attested reconciliation; a schema migration must never invent payment
or rewrite a closed drawer from ambiguous evidence.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0033"
down_revision = "0032"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "customer_memberships",
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_customer_memberships_revoked_at",
        "customer_memberships",
        ["revoked_at"],
    )
    op.create_table(
        "membership_payments",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "membership_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customer_memberships.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "branch_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "terminal_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "shift_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("paid_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("receipt_no", sa.String(length=32), nullable=False),
        sa.Column("receipt_fiscal_year", sa.String(length=7), nullable=False),
        sa.Column("receipt_issued_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("note", sa.String(length=500)),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "amount_minor > 0", name="ck_membership_payment_positive_amount",
        ),
        sa.CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_payment_method",
        ),
        sa.UniqueConstraint(
            "membership_id", name="uq_membership_payment_membership",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_payment_company_idempotency",
        ),
        sa.UniqueConstraint(
            "company_id", "receipt_no",
            name="uq_membership_payment_company_receipt",
        ),
    )
    op.create_index(
        "ix_membership_payments_company_id", "membership_payments", ["company_id"],
    )
    op.create_index(
        "ix_membership_payments_created_by", "membership_payments", ["created_by"],
    )
    op.create_index(
        "ix_membership_payments_paid_at", "membership_payments", ["paid_at"],
    )
    op.create_index(
        "ix_membership_payment_company_paid_at", "membership_payments",
        ["company_id", "paid_at"],
    )
    op.create_index(
        "ix_membership_payment_shift", "membership_payments", ["shift_id"],
    )

    op.create_table(
        "membership_refunds",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "payment_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payments.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "branch_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "terminal_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "shift_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("accepted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "approved_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "amount_minor > 0", name="ck_membership_refund_positive_amount",
        ),
        sa.CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_method",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_refund_reason",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_company_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_refunds_company_id", "membership_refunds", ["company_id"],
    )
    op.create_index(
        "ix_membership_refunds_approved_by", "membership_refunds", ["approved_by"],
    )
    op.create_index(
        "ix_membership_refunds_accepted_at", "membership_refunds", ["accepted_at"],
    )
    op.create_index(
        "ix_membership_refund_company_accepted_at", "membership_refunds",
        ["company_id", "accepted_at"],
    )
    op.create_index(
        "ix_membership_refund_shift", "membership_refunds", ["shift_id"],
    )
    op.create_index(
        "ix_membership_refund_payment", "membership_refunds", ["payment_id"],
    )

    op.create_table(
        "membership_refund_settlements",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "refund_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refunds.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "payment_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payments.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "branch_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "terminal_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "shift_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("settled_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "settled_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("receipt_no", sa.String(length=32), nullable=False),
        sa.Column("receipt_fiscal_year", sa.String(length=7), nullable=False),
        sa.Column("receipt_issued_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("external_ref", sa.String(length=200)),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "amount_minor > 0", name="ck_membership_refund_settlement_positive",
        ),
        sa.CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_settlement_method",
        ),
        sa.CheckConstraint(
            "(method = 'cash' AND external_ref IS NULL) OR "
            "(method <> 'cash' AND char_length(trim(external_ref)) >= 3)",
            name="ck_membership_refund_settlement_external_reference",
        ),
        sa.UniqueConstraint(
            "refund_id", name="uq_membership_refund_settlement_refund",
        ),
        sa.UniqueConstraint(
            "payment_id", name="uq_membership_refund_settlement_payment",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_settlement_company_idempotency",
        ),
        sa.UniqueConstraint(
            "company_id", "receipt_no",
            name="uq_membership_refund_settlement_company_receipt",
        ),
    )
    op.create_index(
        "ix_membership_refund_settlements_company_id",
        "membership_refund_settlements", ["company_id"],
    )
    op.create_index(
        "ix_membership_refund_settlements_settled_by",
        "membership_refund_settlements", ["settled_by"],
    )
    op.create_index(
        "ix_membership_refund_settlements_settled_at",
        "membership_refund_settlements", ["settled_at"],
    )
    op.create_index(
        "ix_membership_refund_settlement_company_settled_at",
        "membership_refund_settlements", ["company_id", "settled_at"],
    )

    op.create_table(
        "membership_refund_resolutions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "refund_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refunds.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "branch_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "terminal_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "shift_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("resolution", sa.String(length=40), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "resolved_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "resolution = 'cash_not_handed_over'",
            name="ck_membership_refund_resolution_type",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_refund_resolution_reason",
        ),
        sa.UniqueConstraint(
            "refund_id", name="uq_membership_refund_resolution_refund",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_resolution_company_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_refund_resolutions_company_id",
        "membership_refund_resolutions", ["company_id"],
    )
    op.create_index(
        "ix_membership_refund_resolution_company_resolved_at",
        "membership_refund_resolutions", ["company_id", "resolved_at"],
    )

    # Make the dedicated revenue account visible in companies created before
    # this release as well as newly seeded companies.
    op.execute(
        """
        INSERT INTO accounts (
            id, company_id, parent_id, code, name, type, normal_side,
            is_active, created_at, updated_at
        )
        SELECT
            gen_random_uuid(), c.id, NULL, '4250', 'Membership Revenue',
            'revenue', 'cr', TRUE, now(), now()
        FROM companies c
        WHERE NOT EXISTS (
            SELECT 1 FROM accounts a
            WHERE a.company_id = c.id AND a.code = '4250'
        )
        """
    )

    # Existing CustomerMembership rows remain entitlement-only. Even detailed
    # audit provenance cannot prove that cash/UPI was actually collected or
    # that it was not already included in a manual collection. Financial
    # reconciliation is an explicit owner workflow, never a schema side effect.
    op.execute(
        """
        DO $$
        DECLARE unresolved_count bigint;
        BEGIN
            SELECT count(*) INTO unresolved_count
            FROM customer_memberships cm
            WHERE cm.amount_paid_minor > 0
              AND NOT EXISTS (
                  SELECT 1 FROM membership_payments mp
                  WHERE mp.membership_id = cm.id
              );
            IF unresolved_count > 0 THEN
                RAISE WARNING
                    '% historical membership entitlement(s) remain unposted; reconcile only against independent owner/payment evidence',
                    unresolved_count;
            END IF;
        END $$
        """
    )


def downgrade() -> None:
    # 0032 cannot represent immutable membership money or revocation history.
    # Refuse a destructive downgrade once this workflow has ever been used.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM membership_payments)
               OR EXISTS (SELECT 1 FROM membership_refunds)
               OR EXISTS (SELECT 1 FROM membership_refund_settlements)
               OR EXISTS (SELECT 1 FROM membership_refund_resolutions)
               OR EXISTS (
                    SELECT 1 FROM customer_memberships WHERE revoked_at IS NOT NULL
               )
            THEN
                RAISE EXCEPTION
                    'cannot downgrade 0033 after membership financial or revocation history exists';
            END IF;
        END $$
        """
    )
    op.drop_index(
        "ix_membership_refund_resolution_company_resolved_at",
        table_name="membership_refund_resolutions",
    )
    op.drop_index(
        "ix_membership_refund_resolutions_company_id",
        table_name="membership_refund_resolutions",
    )
    op.drop_table("membership_refund_resolutions")
    op.drop_index(
        "ix_membership_refund_settlement_company_settled_at",
        table_name="membership_refund_settlements",
    )
    op.drop_index(
        "ix_membership_refund_settlements_settled_at",
        table_name="membership_refund_settlements",
    )
    op.drop_index(
        "ix_membership_refund_settlements_settled_by",
        table_name="membership_refund_settlements",
    )
    op.drop_index(
        "ix_membership_refund_settlements_company_id",
        table_name="membership_refund_settlements",
    )
    op.drop_table("membership_refund_settlements")
    op.drop_index("ix_membership_refund_payment", table_name="membership_refunds")
    op.drop_index("ix_membership_refund_shift", table_name="membership_refunds")
    op.drop_index(
        "ix_membership_refund_company_accepted_at", table_name="membership_refunds",
    )
    op.drop_index("ix_membership_refunds_accepted_at", table_name="membership_refunds")
    op.drop_index("ix_membership_refunds_approved_by", table_name="membership_refunds")
    op.drop_index("ix_membership_refunds_company_id", table_name="membership_refunds")
    op.drop_table("membership_refunds")
    op.drop_index("ix_membership_payment_shift", table_name="membership_payments")
    op.drop_index(
        "ix_membership_payment_company_paid_at", table_name="membership_payments",
    )
    op.drop_index("ix_membership_payments_paid_at", table_name="membership_payments")
    op.drop_index("ix_membership_payments_created_by", table_name="membership_payments")
    op.drop_index("ix_membership_payments_company_id", table_name="membership_payments")
    op.drop_table("membership_payments")
    op.drop_index(
        "ix_customer_memberships_revoked_at",
        table_name="customer_memberships",
    )
    op.drop_column("customer_memberships", "revoked_at")
    # Retain account 4250.  It may predate this migration or have been used by
    # a later manual journal; a downgrade cannot prove ownership safely.
