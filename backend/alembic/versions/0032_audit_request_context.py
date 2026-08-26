"""Add device, request, and offline-sync provenance to audit history.

Revision ID: 0032
Revises: 0031
Create Date: 2026-08-25
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0032"
down_revision = "0031"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "audit_log",
        sa.Column(
            "terminal_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.add_column("audit_log", sa.Column("request_id", sa.String(length=64)))
    op.add_column("audit_log", sa.Column("client_platform", sa.String(length=20)))
    op.add_column("audit_log", sa.Column("client_version_code", sa.Integer()))
    op.add_column("audit_log", sa.Column("client_action_id", sa.String(length=100)))
    op.add_column(
        "audit_log",
        sa.Column("client_reported_at", sa.DateTime(timezone=True)),
    )
    op.add_column("audit_log", sa.Column("client_was_offline", sa.Boolean()))
    op.add_column("audit_log", sa.Column("synced_at", sa.DateTime(timezone=True)))
    op.add_column("audit_log", sa.Column("reason", sa.String(length=500)))
    op.create_index(
        "ix_audit_log_company_id_id",
        "audit_log",
        ["company_id", "id"],
    )
    op.create_index("ix_audit_log_terminal_id", "audit_log", ["terminal_id"])
    op.create_index("ix_audit_log_request_id", "audit_log", ["request_id"])


def downgrade() -> None:
    op.drop_index("ix_audit_log_request_id", table_name="audit_log")
    op.drop_index("ix_audit_log_terminal_id", table_name="audit_log")
    # 0032 was exercised locally before this performance index was added.
    # Tolerate that pre-release draft state during a developer downgrade.
    op.drop_index(
        "ix_audit_log_company_id_id",
        table_name="audit_log",
        if_exists=True,
    )
    op.drop_column("audit_log", "reason")
    op.drop_column("audit_log", "synced_at")
    op.drop_column("audit_log", "client_was_offline")
    op.drop_column("audit_log", "client_reported_at")
    op.drop_column("audit_log", "client_action_id")
    op.drop_column("audit_log", "client_version_code")
    op.drop_column("audit_log", "client_platform")
    op.drop_column("audit_log", "request_id")
    op.drop_column("audit_log", "terminal_id")
