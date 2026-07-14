"""Add planned timer duration to gaming sessions.

Revision ID: 0014
Revises: 0013
Create Date: 2026-07-14
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("timer_minutes", sa.Integer(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("gaming_sessions", "timer_minutes")
