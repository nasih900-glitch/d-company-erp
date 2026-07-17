"""Add customers.lifetime_gaming_points_earned for the gaming rank ladder.

Revision ID: 0025
Revises: 0024
Create Date: 2026-07-16
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0025"
down_revision = "0024"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "customers",
        sa.Column(
            "lifetime_gaming_points_earned",
            sa.Integer(),
            nullable=False,
            server_default="0",
        ),
    )


def downgrade() -> None:
    op.drop_column("customers", "lifetime_gaming_points_earned")
