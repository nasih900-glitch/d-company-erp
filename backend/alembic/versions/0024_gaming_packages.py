"""Add gaming_packages (fixed-price session tiers) + gaming_sessions.extra_controllers.

Revision ID: 0024
Revises: 0023
Create Date: 2026-07-16
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0024"
down_revision = "0023"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "gaming_packages",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "branch_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("station_type", sa.String(length=20), nullable=False),
        sa.Column("variant", sa.String(length=20), nullable=False),
        sa.Column("kind", sa.String(length=20), nullable=False),
        sa.Column("name", sa.String(length=100), nullable=False),
        sa.Column("duration_minutes", sa.Integer(), nullable=False),
        sa.Column("price_minor", sa.BigInteger(), nullable=False),
        sa.Column("sort_order", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default="true"),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.CheckConstraint("kind IN ('base', 'extension')", name="ck_gaming_package_kind"),
        sa.CheckConstraint("duration_minutes > 0", name="ck_gaming_package_duration_positive"),
        sa.CheckConstraint("price_minor >= 0", name="ck_gaming_package_price_non_negative"),
    )
    op.create_index("ix_gaming_packages_company_id", "gaming_packages", ["company_id"])
    op.create_index("ix_gaming_packages_branch_id", "gaming_packages", ["branch_id"])
    op.create_index(
        "ix_gaming_packages_lookup", "gaming_packages", ["branch_id", "station_type", "variant"]
    )

    op.add_column(
        "gaming_sessions",
        sa.Column("extra_controllers", sa.Integer(), nullable=False, server_default="0"),
    )
    op.create_foreign_key(
        "fk_gaming_sessions_package_id",
        "gaming_sessions",
        "gaming_packages",
        ["package_id"],
        ["id"],
        ondelete="SET NULL",
    )


def downgrade() -> None:
    op.drop_constraint("fk_gaming_sessions_package_id", "gaming_sessions", type_="foreignkey")
    op.drop_column("gaming_sessions", "extra_controllers")
    op.drop_index("ix_gaming_packages_lookup", table_name="gaming_packages")
    op.drop_index("ix_gaming_packages_branch_id", table_name="gaming_packages")
    op.drop_index("ix_gaming_packages_company_id", table_name="gaming_packages")
    op.drop_table("gaming_packages")
