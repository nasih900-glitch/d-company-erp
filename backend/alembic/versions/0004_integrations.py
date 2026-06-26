"""integrations — google_sheets_webhook_url on companies

Revision ID: 0004
Revises: 0003
Create Date: 2026-05-25

Adds:
  - companies.google_sheets_webhook_url — Apps Script web app URL for the
    server-side push (see services/integrations/google_sheets.py).
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("companies", sa.Column("google_sheets_webhook_url", sa.String(500)))


def downgrade() -> None:
    op.drop_column("companies", "google_sheets_webhook_url")
