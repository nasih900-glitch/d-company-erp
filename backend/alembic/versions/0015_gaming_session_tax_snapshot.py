"""Snapshot Station tax fields onto GamingSession at start.

Revision ID: 0015
Revises: 0014
Create Date: 2026-07-14
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0015"
down_revision = "0014"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("tax_rate", sa.Numeric(5, 4), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("sac_code", sa.String(8), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("rate_includes_tax", sa.Boolean(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("gaming_sessions", "rate_includes_tax")
    op.drop_column("gaming_sessions", "sac_code")
    op.drop_column("gaming_sessions", "tax_rate")
