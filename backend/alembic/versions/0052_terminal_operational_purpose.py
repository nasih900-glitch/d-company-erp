"""Add an explicit operational purpose to terminals.

Revision ID: 0052
Revises: 0051
Create Date: 2026-08-28

Existing terminals become ``hybrid`` so an upgrade cannot disable a working
single-terminal installation. New deployments can distinguish a café POS
destination from a gaming-area source without relying on editable names.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0052"
down_revision = "0051"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "terminals",
        sa.Column(
            "purpose",
            sa.String(length=20),
            server_default="hybrid",
            nullable=False,
        ),
    )
    op.create_check_constraint(
        "ck_terminals_purpose",
        "terminals",
        "purpose IN ('hybrid', 'cafe_pos', 'gaming')",
    )


def downgrade() -> None:
    op.drop_constraint("ck_terminals_purpose", "terminals", type_="check")
    op.drop_column("terminals", "purpose")
