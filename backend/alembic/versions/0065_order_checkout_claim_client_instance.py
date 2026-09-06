"""Bind checkout claims to one client installation when supplied.

Revision ID: 0065
Revises: 0064
Create Date: 2026-09-03

The column is nullable for rolling compatibility with Code 14/21 clients.
Only a SHA-256 digest of the client installation UUID is persisted.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0065"
down_revision = "0064"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "order_checkout_claims",
        sa.Column("client_instance_hash", sa.String(length=64), nullable=True),
    )
    op.create_check_constraint(
        "ck_order_checkout_claim_client_instance_hash_length",
        "order_checkout_claims",
        "client_instance_hash IS NULL OR char_length(client_instance_hash) = 64",
    )


def downgrade() -> None:
    op.drop_constraint(
        "ck_order_checkout_claim_client_instance_hash_length",
        "order_checkout_claims",
        type_="check",
    )
    op.drop_column("order_checkout_claims", "client_instance_hash")
