"""Add company payment gateway / bank connection credentials.

Revision ID: 0013
Revises: 0012
Create Date: 2026-07-13
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("companies", sa.Column("payment_provider", sa.String(length=50), nullable=True))
    op.add_column("companies", sa.Column("payment_key_id", sa.String(length=255), nullable=True))
    op.add_column(
        "companies",
        sa.Column("payment_key_secret", sa.String(length=512), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("companies", "payment_key_secret")
    op.drop_column("companies", "payment_key_id")
    op.drop_column("companies", "payment_provider")
