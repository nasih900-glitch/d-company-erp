"""Normalize Kerala branch GST state code.

Revision ID: 0007
Revises: 0006
Create Date: 2026-06-15
"""

from __future__ import annotations

from alembic import op


revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        UPDATE branches
        SET state_code = '32'
        WHERE deleted_at IS NULL
          AND (state_code IS NULL OR state_code = '' OR state_code = 'KL')
        """
    )


def downgrade() -> None:
    # Data normalization is intentionally not reversible.
    pass
