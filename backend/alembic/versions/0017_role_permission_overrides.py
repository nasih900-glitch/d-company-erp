"""Add role_permission_overrides table for the Access Control settings panel.

Revision ID: 0017
Revises: 0016
Create Date: 2026-07-14
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0017"
down_revision = "0016"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "role_permission_overrides",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="CASCADE"), nullable=False,
        ),
        sa.Column("role_code", sa.String(30), nullable=False),
        sa.Column("module", sa.String(30), nullable=False),
        sa.Column("allowed", sa.Boolean(), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True),
            server_default=sa.text("now()"), nullable=False,
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True),
            server_default=sa.text("now()"), nullable=False,
        ),
        sa.UniqueConstraint("company_id", "role_code", "module", name="uq_role_perm_override"),
    )
    op.create_index(
        "ix_role_permission_overrides_company_id",
        "role_permission_overrides", ["company_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_role_permission_overrides_company_id", table_name="role_permission_overrides")
    op.drop_table("role_permission_overrides")
