"""expand idempotency keys for scoped operation names

Revision ID: 0021
Revises: 0020
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0021"
down_revision = "0020"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.alter_column(
        "idempotency_keys",
        "key",
        existing_type=sa.String(length=80),
        type_=sa.String(length=160),
        existing_nullable=False,
    )
    op.alter_column(
        "orders",
        "idempotency_key",
        existing_type=sa.String(length=80),
        type_=sa.String(length=160),
        existing_nullable=True,
    )


def downgrade() -> None:
    # PostgreSQL deliberately rejects this downgrade if any persisted key is
    # longer than 80 characters; silently truncating an idempotency key could
    # merge unrelated operations and is never safe.
    op.alter_column(
        "orders",
        "idempotency_key",
        existing_type=sa.String(length=160),
        type_=sa.String(length=80),
        existing_nullable=True,
    )
    op.alter_column(
        "idempotency_keys",
        "key",
        existing_type=sa.String(length=160),
        type_=sa.String(length=80),
        existing_nullable=False,
    )
