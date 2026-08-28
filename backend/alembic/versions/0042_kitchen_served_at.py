"""Record exact kitchen completion time for served-history boundaries.

Revision ID: 0042
Revises: 0041
Create Date: 2026-08-27

Filtering "Served today" by ``orders.opened_at`` loses tickets opened before
the company's local midnight and served after it.  The line-level timestamp is
the authoritative event time and also supports orders whose rounds finish on
different business dates.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0042"
down_revision = "0041"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "order_lines",
        sa.Column("kitchen_served_at", sa.DateTime(timezone=True), nullable=True),
    )

    # Explicit line state is stronger evidence than the legacy order mirror.
    # updated_at is the best available historical approximation of the state
    # transition; new writes record the exact transition instant below.
    op.execute(
        """
        UPDATE order_lines
           SET kitchen_served_at = COALESCE(
               updated_at,
               kitchen_released_at,
               created_at
           )
         WHERE kitchen_status = 'served'
           AND kitchen_served_at IS NULL
        """
    )

    # Materialise the old all-queued batch contract once, using the same rule
    # the API previously applied lazily before a late round was appended.
    # The NOT EXISTS guard prevents a mixed/newer per-line batch from being
    # rewritten from the compatibility mirror.
    op.execute(
        """
        UPDATE order_lines AS line
           SET kitchen_status = 'served',
               kitchen_served_at = COALESCE(
                   bill.kitchen_ready_at,
                   bill.updated_at,
                   line.updated_at,
                   line.kitchen_released_at,
                   line.created_at
               )
          FROM orders AS bill
         WHERE line.order_id = bill.id
           AND bill.kitchen_state = 'served'
           AND line.kitchen_released_at IS NOT NULL
           AND line.voided_at IS NULL
           AND COALESCE(line.kitchen_status, 'queued') = 'queued'
           AND NOT EXISTS (
               SELECT 1
                 FROM order_lines AS mixed
                WHERE mixed.order_id = bill.id
                  AND mixed.kitchen_released_at IS NOT NULL
                  AND mixed.voided_at IS NULL
                  AND COALESCE(mixed.kitchen_status, 'queued') <> 'queued'
           )
        """
    )

    op.create_check_constraint(
        "ck_order_line_kitchen_served_pair",
        "order_lines",
        "(COALESCE(kitchen_status, 'queued') = 'served' "
        "AND kitchen_served_at IS NOT NULL) OR "
        "(COALESCE(kitchen_status, 'queued') <> 'served' "
        "AND kitchen_served_at IS NULL)",
    )
    op.create_index(
        "ix_order_lines_kitchen_served_history",
        "order_lines",
        ["order_id", "kitchen_served_at"],
        unique=False,
        postgresql_where=sa.text(
            "kitchen_released_at IS NOT NULL AND voided_at IS NULL AND kitchen_status = 'served'"
        ),
    )


def downgrade() -> None:
    # This timestamp improves history precision but is not financial evidence;
    # dropping it leaves the pre-0042 KDS contract intact.
    op.drop_index(
        "ix_order_lines_kitchen_served_history",
        table_name="order_lines",
    )
    op.drop_constraint(
        "ck_order_line_kitchen_served_pair",
        "order_lines",
        type_="check",
    )
    op.drop_column("order_lines", "kitchen_served_at")
