"""Classify Gaming Centre sale categories independently of editable names.

Revision ID: 0070
Revises: 0069

The one-time name match below only preserves the categories already offered by
the Code 24 clients.  After this migration, clients consume the stored flag and
renaming a category cannot change product visibility.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0070"
down_revision = "0069"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "menu_categories",
        sa.Column(
            "is_gaming_centre_catalog",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
    )
    op.execute(
        """
        UPDATE menu_categories
        SET is_gaming_centre_catalog = true
        WHERE deleted_at IS NULL
          AND lower(trim(name)) IN (
              'soft drinks', 'drinks & snacks', 'snacks', 'crisps'
          )
        """
    )


def downgrade() -> None:
    # Once an owner renames or explicitly reclassifies a category, an older
    # name-based client cannot reproduce the decision. Refuse to erase that
    # operational configuration during rollback rather than silently hiding or
    # exposing products after a later re-upgrade.
    op.execute(
        """
        DO $$ BEGIN
            IF EXISTS (
                SELECT 1
                FROM menu_categories
                WHERE is_gaming_centre_catalog IS DISTINCT FROM (
                    deleted_at IS NULL
                    AND lower(trim(name)) IN (
                        'soft drinks', 'drinks & snacks', 'snacks', 'crisps'
                    )
                )
            ) THEN
                RAISE EXCEPTION
                    'gaming centre catalogue classification has changed; preserving it';
            END IF;
        END $$
        """
    )
    op.drop_column("menu_categories", "is_gaming_centre_catalog")
