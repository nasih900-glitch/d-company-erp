"""Add append-only void fields to journal entries.

Revision ID: 0028
Revises: 0027
Create Date: 2026-07-17

Journal entries had no way to correct a mistaken posting other than deleting
it outright. That's unacceptable for a real ledger: a hard delete leaves no
trace of what was there or why it changed. This mirrors capital_entries'
void-in-place pattern (0027) so a wrong entry can be superseded without ever
losing its own history.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0028"
down_revision = "0027"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "journal_entries",
        sa.Column("voided_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "journal_entries",
        sa.Column("voided_by", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "journal_entries",
        sa.Column("void_reason", sa.String(length=500), nullable=True),
    )

    op.create_foreign_key(
        "fk_journal_entry_voided_by_user",
        "journal_entries",
        "users",
        ["voided_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_check_constraint(
        "ck_journal_entry_void_state",
        "journal_entries",
        "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
        "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
        "AND length(trim(void_reason)) >= 3)",
    )
    op.create_index(
        "ix_journal_entries_voided_by",
        "journal_entries",
        ["voided_by"],
    )
    op.create_index(
        "ix_journal_entries_voided_at",
        "journal_entries",
        ["voided_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_journal_entries_voided_at", table_name="journal_entries")
    op.drop_index("ix_journal_entries_voided_by", table_name="journal_entries")
    op.drop_constraint("ck_journal_entry_void_state", "journal_entries", type_="check")
    op.drop_constraint("fk_journal_entry_voided_by_user", "journal_entries", type_="foreignkey")
    op.drop_column("journal_entries", "void_reason")
    op.drop_column("journal_entries", "voided_by")
    op.drop_column("journal_entries", "voided_at")
