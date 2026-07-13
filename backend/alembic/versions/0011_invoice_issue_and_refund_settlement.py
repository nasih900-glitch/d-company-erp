"""Issue invoices at payment time and record refund settlement rails.

Revision ID: 0011
Revises: 0010
Create Date: 2026-07-12
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op


revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("orders", sa.Column("invoice_issued_at", sa.DateTime(timezone=True)))
    op.create_index(
        "ix_orders_invoice_issued_at",
        "orders",
        ["invoice_issued_at"],
        unique=False,
    )
    op.execute(
        """
        UPDATE orders
           SET invoice_issued_at = COALESCE(closed_at, created_at)
         WHERE invoice_no IS NOT NULL
           AND invoice_issued_at IS NULL
        """
    )

    op.add_column("refunds", sa.Column("settlement_method", sa.String(length=20)))
    op.execute(
        """
        UPDATE refunds
           SET settlement_method = CASE
               WHEN mode = 'cash' THEN 'cash'
               WHEN mode = 'credit_note' THEN 'store_credit'
               ELSE NULL
           END
         WHERE settlement_method IS NULL
        """
    )


def downgrade() -> None:
    op.drop_column("refunds", "settlement_method")
    op.drop_index("ix_orders_invoice_issued_at", table_name="orders")
    op.drop_column("orders", "invoice_issued_at")
