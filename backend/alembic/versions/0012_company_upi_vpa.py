"""Add company UPI VPA for dynamic checkout pay QR.

Revision ID: 0012
Revises: 0011
Create Date: 2026-07-12
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0012"
down_revision = "0011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "companies",
        sa.Column("upi_vpa", sa.String(length=255), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("companies", "upi_vpa")
