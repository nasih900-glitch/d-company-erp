"""Authenticated Google Sheets mirror configuration.

Revision ID: 0076
Revises: 0075
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0076"
down_revision = "0075"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_mirror_enabled",
            sa.Boolean(),
            server_default=sa.false(),
            nullable=False,
        ),
    )
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_signing_secret_ciphertext",
            sa.String(length=256),
            nullable=True,
        ),
    )
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_configured_at",
            sa.DateTime(timezone=True),
            nullable=True,
        ),
    )
    op.add_column(
        "companies",
        sa.Column(
            "google_sheets_configuration_id",
            postgresql.UUID(as_uuid=True),
            nullable=True,
        ),
    )
    op.create_check_constraint(
        "ck_company_google_sheets_mirror_config",
        "companies",
        "(google_sheets_mirror_enabled = false) OR "
        "(google_sheets_webhook_url IS NOT NULL "
        "AND length(trim(google_sheets_webhook_url)) > 0 "
        "AND google_sheets_signing_secret_ciphertext IS NOT NULL "
        "AND length(google_sheets_signing_secret_ciphertext) > 0 "
        "AND google_sheets_configured_at IS NOT NULL "
        "AND google_sheets_configuration_id IS NOT NULL)",
    )


def downgrade() -> None:
    active = bool(
        op.get_bind()
        .execute(
            sa.text(
                "SELECT EXISTS (SELECT 1 FROM companies "
                "WHERE google_sheets_mirror_enabled = true)"
            )
        )
        .scalar_one()
    )
    if active:
        raise RuntimeError(
            "0076 downgrade refused: disable every Google Sheets mirror first"
        )
    op.drop_constraint(
        "ck_company_google_sheets_mirror_config",
        "companies",
        type_="check",
    )
    op.drop_column("companies", "google_sheets_configuration_id")
    op.drop_column("companies", "google_sheets_configured_at")
    op.drop_column("companies", "google_sheets_signing_secret_ciphertext")
    op.drop_column("companies", "google_sheets_mirror_enabled")
