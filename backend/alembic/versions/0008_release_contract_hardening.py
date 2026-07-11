"""Harden release-critical input storage contracts.

Revision ID: 0008
Revises: 0007
Create Date: 2026-07-10
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.alter_column(
        "orders",
        "idempotency_key",
        existing_type=sa.String(length=80),
        type_=sa.String(length=160),
        existing_nullable=True,
    )
    op.alter_column(
        "idempotency_keys",
        "key",
        existing_type=sa.String(length=80),
        type_=sa.String(length=160),
        existing_nullable=False,
    )
    op.add_column("expense_categories", sa.Column("code", sa.String(length=20)))
    op.create_unique_constraint(
        "uq_expense_category_code_per_company",
        "expense_categories",
        ["company_id", "code"],
    )


def downgrade() -> None:
    op.drop_constraint(
        "uq_expense_category_code_per_company",
        "expense_categories",
        type_="unique",
    )
    op.drop_column("expense_categories", "code")
    op.alter_column(
        "idempotency_keys",
        "key",
        existing_type=sa.String(length=160),
        type_=sa.String(length=80),
        existing_nullable=False,
    )
    op.alter_column(
        "orders",
        "idempotency_key",
        existing_type=sa.String(length=160),
        type_=sa.String(length=80),
        existing_nullable=True,
    )
