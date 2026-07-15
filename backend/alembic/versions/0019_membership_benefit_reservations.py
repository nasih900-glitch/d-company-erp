"""reserve and consume period-scoped membership allowances

Revision ID: 0019
Revises: 0018
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0019"
down_revision = "0018"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "customer_memberships",
        sa.Column("gaming_usage_week_start", sa.Date(), nullable=True),
    )
    op.add_column(
        "customer_memberships",
        sa.Column("hookah_usage_month_start", sa.Date(), nullable=True),
    )
    op.create_table(
        "membership_benefit_reservations",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
        ),
        sa.Column(
            "membership_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customer_memberships.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("benefit_type", sa.String(length=30), nullable=False),
        sa.Column("period_start", sa.Date(), nullable=False),
        sa.Column("quantity", sa.Integer(), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
        sa.CheckConstraint(
            "quantity > 0",
            name="ck_membership_benefit_quantity_positive",
        ),
        sa.CheckConstraint(
            "benefit_type IN ('gaming_minutes', 'hookah_count')",
            name="ck_membership_benefit_type",
        ),
        sa.UniqueConstraint(
            "order_id",
            "benefit_type",
            name="uq_membership_benefit_order_type",
        ),
    )
    op.create_index(
        "ix_membership_benefit_reservations_membership_id",
        "membership_benefit_reservations",
        ["membership_id"],
    )
    op.create_index(
        "ix_membership_benefit_reservations_order_id",
        "membership_benefit_reservations",
        ["order_id"],
    )
    op.create_index(
        "ix_membership_benefit_period",
        "membership_benefit_reservations",
        ["membership_id", "benefit_type", "period_start"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_membership_benefit_period",
        table_name="membership_benefit_reservations",
    )
    op.drop_index(
        "ix_membership_benefit_reservations_order_id",
        table_name="membership_benefit_reservations",
    )
    op.drop_index(
        "ix_membership_benefit_reservations_membership_id",
        table_name="membership_benefit_reservations",
    )
    op.drop_table("membership_benefit_reservations")
    op.drop_column("customer_memberships", "hookah_usage_month_start")
    op.drop_column("customer_memberships", "gaming_usage_week_start")
