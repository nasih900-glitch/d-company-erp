"""Add a session version used to revoke issued authentication tokens.

Revision ID: 0010
Revises: 0009
Create Date: 2026-07-12
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column(
            "auth_version",
            sa.Integer(),
            server_default="0",
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_column("users", "auth_version")
