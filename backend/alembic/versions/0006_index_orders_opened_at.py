"""index on orders.opened_at — speeds up daily/monthly aggregation

Revision ID: 0006
Revises: 0005
Create Date: 2026-06-13

Reports queries scan orders by opened_at constantly (daily/monthly P&L,
analytics dashboard, growth comparisons). With ~100 orders/day this is
unnoticeable; at 10,000+ orders the seq scan starts to bite. Adding now
is a one-line insurance policy.
"""

from __future__ import annotations

from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index("ix_orders_opened_at", "orders", ["opened_at"])


def downgrade() -> None:
    op.drop_index("ix_orders_opened_at", table_name="orders")
