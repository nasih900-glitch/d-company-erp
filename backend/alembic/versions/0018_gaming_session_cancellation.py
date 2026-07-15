"""record auditable gaming session cancellations

Revision ID: 0018
Revises: 0017
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql


revision = "0018"
down_revision = "0017"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "gaming_sessions",
        sa.Column("cancelled_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column(
            "cancelled_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("cancel_reason", sa.String(length=500), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("gaming_sessions", "cancel_reason")
    op.drop_column("gaming_sessions", "cancelled_by")
    op.drop_column("gaming_sessions", "cancelled_at")
