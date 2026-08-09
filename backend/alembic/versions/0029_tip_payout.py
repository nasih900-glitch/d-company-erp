"""Add tip_payouts — the only record that clears TIPS_PAYABLE.

Revision ID: 0029
Revises: 0028
Create Date: 2026-08-09

TIPS_PAYABLE is credited whenever a tipped order is paid and debited on
refund of a tipped order (see ledger.py), but nothing previously ever paid
it back out to staff, so it accumulated forever. This is a standalone
record of "we paid out ₹X in tips to staff" — deliberately not the full
Payroll feature. It follows the same immutable-plus-void, idempotent-write
shape as manual_collections.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0029"
down_revision = "0028"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "tip_payouts",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("branch_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("paid_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("note", sa.String(length=500), nullable=False),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("voided_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("voided_by", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("void_reason", sa.String(length=500), nullable=True),
        sa.CheckConstraint(
            "method IN ('cash', 'upi', 'card', 'bank')",
            name="ck_tip_payout_method",
        ),
        sa.CheckConstraint(
            "amount_minor > 0",
            name="ck_tip_payout_positive_amount",
        ),
        sa.CheckConstraint(
            "length(trim(note)) >= 3",
            name="ck_tip_payout_note_present",
        ),
        sa.CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_tip_payout_idempotency_key_present",
        ),
        sa.CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3)",
            name="ck_tip_payout_void_state",
        ),
        sa.ForeignKeyConstraint(
            ["company_id"], ["companies.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["branch_id"], ["branches.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["created_by"], ["users.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["voided_by"], ["users.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_tip_payout_company_idempotency",
        ),
    )
    op.create_index(
        "ix_tip_payouts_company_id",
        "tip_payouts",
        ["company_id"],
    )
    op.create_index(
        "ix_tip_payouts_branch_id",
        "tip_payouts",
        ["branch_id"],
    )
    op.create_index(
        "ix_tip_payouts_created_by",
        "tip_payouts",
        ["created_by"],
    )
    op.create_index(
        "ix_tip_payouts_voided_by",
        "tip_payouts",
        ["voided_by"],
    )
    op.create_index(
        "ix_tip_payout_company_paid_at",
        "tip_payouts",
        ["company_id", "paid_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_tip_payout_company_paid_at", table_name="tip_payouts")
    op.drop_index("ix_tip_payouts_voided_by", table_name="tip_payouts")
    op.drop_index("ix_tip_payouts_created_by", table_name="tip_payouts")
    op.drop_index("ix_tip_payouts_branch_id", table_name="tip_payouts")
    op.drop_index("ix_tip_payouts_company_id", table_name="tip_payouts")
    op.drop_table("tip_payouts")
