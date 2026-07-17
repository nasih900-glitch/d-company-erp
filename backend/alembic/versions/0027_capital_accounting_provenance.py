"""Add auditable capital provenance and settlement accounts.

Revision ID: 0027
Revises: 0026
Create Date: 2026-07-16

Capital movements previously assumed every investment arrived in Bank and
could only be "corrected" by adding another movement.  That made the partner
subledger and balance sheet diverge from the source records.  This revision
adds an explicit settlement account plus append-only void/provenance fields so
historical mistakes can remain visible without contributing to balances.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0027"
down_revision = "0026"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "capital_entries",
        sa.Column(
            "settlement_account",
            sa.String(length=30),
            nullable=False,
            server_default="bank",
        ),
    )
    op.add_column(
        "capital_entries",
        sa.Column("source_ref", sa.String(length=160), nullable=True),
    )
    op.add_column(
        "capital_entries",
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "capital_entries",
        sa.Column("voided_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "capital_entries",
        sa.Column("voided_by", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "capital_entries",
        sa.Column("void_reason", sa.String(length=500), nullable=True),
    )

    op.create_foreign_key(
        "fk_capital_entry_created_by_user",
        "capital_entries",
        "users",
        ["created_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_capital_entry_voided_by_user",
        "capital_entries",
        "users",
        ["voided_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_check_constraint(
        "ck_capital_entry_positive_amount",
        "capital_entries",
        "amount_minor > 0",
    )
    op.create_check_constraint(
        "ck_capital_entry_settlement_account",
        "capital_entries",
        "settlement_account IN ('cash', 'bank', 'upi', 'historical_funds')",
    )
    op.create_check_constraint(
        "ck_capital_entry_source_ref_present",
        "capital_entries",
        "source_ref IS NULL OR length(trim(source_ref)) > 0",
    )
    op.create_check_constraint(
        "ck_capital_entry_void_state",
        "capital_entries",
        "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
        "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
        "AND length(trim(void_reason)) >= 3)",
    )
    op.create_unique_constraint(
        "uq_capital_entry_source_ref",
        "capital_entries",
        ["source_ref"],
    )
    op.create_index(
        "ix_capital_entries_created_by",
        "capital_entries",
        ["created_by"],
    )
    op.create_index(
        "ix_capital_entries_voided_by",
        "capital_entries",
        ["voided_by"],
    )
    op.create_index(
        "ix_capital_entries_voided_at",
        "capital_entries",
        ["voided_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_capital_entries_voided_at", table_name="capital_entries")
    op.drop_index("ix_capital_entries_voided_by", table_name="capital_entries")
    op.drop_index("ix_capital_entries_created_by", table_name="capital_entries")
    op.drop_constraint("uq_capital_entry_source_ref", "capital_entries", type_="unique")
    op.drop_constraint("ck_capital_entry_void_state", "capital_entries", type_="check")
    op.drop_constraint("ck_capital_entry_source_ref_present", "capital_entries", type_="check")
    op.drop_constraint("ck_capital_entry_settlement_account", "capital_entries", type_="check")
    op.drop_constraint("ck_capital_entry_positive_amount", "capital_entries", type_="check")
    op.drop_constraint("fk_capital_entry_voided_by_user", "capital_entries", type_="foreignkey")
    op.drop_constraint("fk_capital_entry_created_by_user", "capital_entries", type_="foreignkey")
    op.drop_column("capital_entries", "void_reason")
    op.drop_column("capital_entries", "voided_by")
    op.drop_column("capital_entries", "voided_at")
    op.drop_column("capital_entries", "created_by")
    op.drop_column("capital_entries", "source_ref")
    op.drop_column("capital_entries", "settlement_account")
