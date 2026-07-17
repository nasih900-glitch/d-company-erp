"""Add loyalty-points redemption: orders.points_redeemed_minor + points_redemptions.

Revision ID: 0023
Revises: 0022
Create Date: 2026-07-16
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0023"
down_revision = "0022"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "orders",
        sa.Column(
            "points_redeemed_minor",
            sa.BigInteger(),
            nullable=False,
            server_default="0",
        ),
    )
    op.create_table(
        "points_redemptions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "customer_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("points_spent", sa.Integer(), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.CheckConstraint("points_spent > 0", name="ck_points_redemption_positive"),
        sa.UniqueConstraint("order_id", name="uq_points_redemption_order"),
    )
    op.create_index(
        "ix_points_redemptions_customer_id", "points_redemptions", ["customer_id"]
    )
    op.create_index(
        "ix_points_redemptions_order_id", "points_redemptions", ["order_id"]
    )


def downgrade() -> None:
    op.drop_index("ix_points_redemptions_order_id", table_name="points_redemptions")
    op.drop_index("ix_points_redemptions_customer_id", table_name="points_redemptions")
    op.drop_table("points_redemptions")
    op.drop_column("orders", "points_redeemed_minor")
