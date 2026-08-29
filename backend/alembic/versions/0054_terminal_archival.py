"""Preserve terminal history while allowing a one-workspace operation.

Revision ID: 0054
Revises: 0053
Create Date: 2026-08-28

Terminals own durable shift, order, payment, audit and offline references.
Archiving is therefore safer than deleting or rewriting historical foreign
keys when a shop simplifies from multiple counters to one workspace.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0054"
down_revision = "0053"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "terminals",
        sa.Column(
            "is_active",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )
    op.create_index(
        "ix_terminals_branch_active",
        "terminals",
        ["branch_id", "is_active"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index("ix_terminals_branch_active", table_name="terminals")
    op.drop_column("terminals", "is_active")
