"""Add single-use OTP challenges for account security.

Revision ID: 0009
Revises: 0008
Create Date: 2026-07-11
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "auth_otp_challenges",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("purpose", sa.String(length=40), nullable=False),
        sa.Column("target_email", sa.String(length=254), nullable=False),
        sa.Column("target_user_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("code_hash", sa.String(length=64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("failed_attempts", sa.Integer(), nullable=False),
        sa.Column("requested_by_user_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("requested_ip", sa.String(length=64), nullable=True),
        sa.Column("request_user_agent", sa.String(length=500), nullable=True),
        sa.Column("pending_name", sa.String(length=200), nullable=True),
        sa.Column("pending_phone", sa.String(length=20), nullable=True),
        sa.Column("pending_role_code", sa.String(length=50), nullable=True),
        sa.Column("pending_password_hash", sa.String(length=255), nullable=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            nullable=False,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["target_user_id"], ["users.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["requested_by_user_id"], ["users.id"], ondelete="SET NULL"
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_auth_otp_challenges_company_id",
        "auth_otp_challenges",
        ["company_id"],
    )
    op.create_index(
        "ix_auth_otp_challenges_purpose",
        "auth_otp_challenges",
        ["purpose"],
    )
    op.create_index(
        "ix_auth_otp_challenges_target_email",
        "auth_otp_challenges",
        ["target_email"],
    )
    op.create_index(
        "ix_auth_otp_target_created",
        "auth_otp_challenges",
        ["purpose", "target_email", "created_at"],
    )
    op.create_index(
        "ix_auth_otp_ip_created",
        "auth_otp_challenges",
        ["requested_ip", "created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_auth_otp_ip_created", table_name="auth_otp_challenges")
    op.drop_index("ix_auth_otp_target_created", table_name="auth_otp_challenges")
    op.drop_index("ix_auth_otp_challenges_target_email", table_name="auth_otp_challenges")
    op.drop_index("ix_auth_otp_challenges_purpose", table_name="auth_otp_challenges")
    op.drop_index("ix_auth_otp_challenges_company_id", table_name="auth_otp_challenges")
    op.drop_table("auth_otp_challenges")
