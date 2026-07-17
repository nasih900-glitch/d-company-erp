"""Add manual_discount_minor to orders, for the POS custom-discount option.

Tracked separately from discount_minor (which is fully recomputed from line
totals whenever a membership is attached or an item is added to an open/held
order) so a cashier's manual discount survives those recomputes instead of
silently resetting to zero.

Revision ID: 0022
Revises: 0021
Create Date: 2026-07-16
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0022"
down_revision = "0021"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "orders",
        sa.Column(
            "manual_discount_minor",
            sa.BigInteger(),
            nullable=False,
            server_default="0",
        ),
    )


def downgrade() -> None:
    op.drop_column("orders", "manual_discount_minor")
