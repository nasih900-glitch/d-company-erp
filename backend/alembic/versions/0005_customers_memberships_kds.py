"""customers + memberships + kitchen display

Revision ID: 0005
Revises: 0004
Create Date: 2026-06-13

Adds:
  - customers                        — phone-based loyalty
  - membership_tiers                 — D Club Silver/Gold/Platinum definitions
  - customer_memberships             — a customer's active/past subscriptions
  - orders.kitchen_state             — Kitchen Display System tile state
  - orders.kitchen_ready_at          — timestamp when kitchen marked ready
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID as PG_UUID, JSONB  # noqa: F401

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # ------------------------------------------------------------ customers
    op.create_table(
        "customers",
        sa.Column("id", PG_UUID(as_uuid=True), primary_key=True),
        sa.Column("company_id", PG_UUID(as_uuid=True),
                  sa.ForeignKey("companies.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("name", sa.String(200)),
        sa.Column("phone", sa.String(20), nullable=False, index=True),
        sa.Column("email", sa.String(254)),
        sa.Column("birthday", sa.DateTime(timezone=True)),
        sa.Column("first_visit_at", sa.DateTime(timezone=True)),
        sa.Column("last_visit_at", sa.DateTime(timezone=True)),
        sa.Column("visit_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("total_spent_minor", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("loyalty_points", sa.Integer, nullable=False, server_default="0"),
        sa.Column("notes", sa.String(500)),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
        sa.UniqueConstraint("company_id", "phone", name="uq_customer_phone_per_company"),
    )

    # ------------------------------------------------------------ membership_tiers
    op.create_table(
        "membership_tiers",
        sa.Column("id", PG_UUID(as_uuid=True), primary_key=True),
        sa.Column("company_id", PG_UUID(as_uuid=True),
                  sa.ForeignKey("companies.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("code", sa.String(20), nullable=False),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("monthly_price_minor", sa.BigInteger, nullable=False),
        sa.Column("annual_price_minor", sa.BigInteger),
        sa.Column("food_discount_pct", sa.Numeric(5, 4), server_default="0"),
        sa.Column("gaming_discount_pct", sa.Numeric(5, 4), server_default="0"),
        sa.Column("hookah_discount_pct", sa.Numeric(5, 4), server_default="0"),
        sa.Column("point_multiplier", sa.Numeric(4, 2), server_default="1"),
        sa.Column("free_gaming_minutes_per_week", sa.Integer, server_default="0"),
        sa.Column("free_hookah_per_month", sa.Integer, server_default="0"),
        sa.Column("priority_booking", sa.Boolean, nullable=False, server_default=sa.text("false")),
        sa.Column("description", sa.String(500)),
        sa.Column("sort_order", sa.Integer, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
        sa.UniqueConstraint("company_id", "code", name="uq_tier_code_per_company"),
    )

    # ------------------------------------------------------------ customer_memberships
    op.create_table(
        "customer_memberships",
        sa.Column("id", PG_UUID(as_uuid=True), primary_key=True),
        sa.Column("customer_id", PG_UUID(as_uuid=True),
                  sa.ForeignKey("customers.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("tier_id", PG_UUID(as_uuid=True),
                  sa.ForeignKey("membership_tiers.id", ondelete="RESTRICT"),
                  nullable=False),
        sa.Column("billing_cycle", sa.String(10),
                  nullable=False, server_default="monthly"),
        sa.Column("starts_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False, index=True),
        sa.Column("cancelled_at", sa.DateTime(timezone=True)),
        sa.Column("auto_renew", sa.Boolean, nullable=False, server_default=sa.text("true")),
        sa.Column("razorpay_subscription_id", sa.String(50)),
        sa.Column("amount_paid_minor", sa.BigInteger,
                  nullable=False, server_default="0"),
        sa.Column("gaming_minutes_used_this_week", sa.Integer, server_default="0"),
        sa.Column("hookah_used_this_month", sa.Integer, server_default="0"),
        sa.Column("notes", sa.String(500)),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", sa.DateTime(timezone=True),
                  nullable=False, server_default=sa.text("now()")),
    )

    # ------------------------------------------------------------ orders columns
    op.add_column("orders", sa.Column("kitchen_state", sa.String(20)))
    op.add_column("orders", sa.Column("kitchen_ready_at", sa.DateTime(timezone=True)))


def downgrade() -> None:
    op.drop_column("orders", "kitchen_ready_at")
    op.drop_column("orders", "kitchen_state")
    op.drop_table("customer_memberships")
    op.drop_table("membership_tiers")
    op.drop_table("customers")
