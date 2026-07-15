"""hide legacy manual session items from normal POS

Revision ID: 0020
Revises: 0019
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0020"
down_revision = "0019"
branch_labels = None
depends_on = None


_LEGACY_SESSION_SKUS = (
    "GAM-PS5-15",
    "GAM-VR-15",
    "GAM-SIM-15",
    "SHI-SESSION",
    "STR-BOOTH-15",
)


def upgrade() -> None:
    menu_items = sa.table(
        "menu_items",
        sa.column("sku", sa.String()),
        sa.column("is_available", sa.Boolean()),
    )
    op.execute(
        menu_items.update()
        .where(menu_items.c.sku.in_(_LEGACY_SESSION_SKUS))
        .values(is_available=False)
    )


def downgrade() -> None:
    menu_items = sa.table(
        "menu_items",
        sa.column("sku", sa.String()),
        sa.column("is_available", sa.Boolean()),
    )
    op.execute(
        menu_items.update()
        .where(menu_items.c.sku.in_(_LEGACY_SESSION_SKUS))
        .values(is_available=True)
    )
