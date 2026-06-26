"""events — projector screening tickets (football, cricket, movies, esports)

Revision ID: 0003
Revises: 0002
Create Date: 2026-05-25

Adds the events module:
  - events           — one row per scheduled screening (capacity, ticket price, SAC, GST)
  - event_tickets    — one row per sold ticket (ticket_no, customer, check-in)

Tax treatment matches gaming sessions: SAC 999692 (amusement & recreation),
18% GST (CGST 9% + SGST 9%) — see docs/INDIA_TAX_COMPLIANCE.md §6.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "branch_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("description", sa.String(1000)),
        sa.Column("event_type", sa.String(20), nullable=False),
        sa.Column("screen", sa.String(50), nullable=False, server_default="Main Screen"),
        sa.Column("starts_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ends_at", sa.DateTime(timezone=True)),
        sa.Column("capacity", sa.Integer, nullable=False),
        sa.Column("base_ticket_price_minor", sa.BigInteger, nullable=False),
        sa.Column("sac_code", sa.String(8), nullable=False, server_default="999692"),
        sa.Column("tax_rate", sa.Numeric(5, 4), nullable=False, server_default="0.18"),
        sa.Column("status", sa.String(20), nullable=False, server_default="scheduled"),
        sa.Column("poster_url", sa.String(500)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
        sa.CheckConstraint("capacity > 0", name="ck_events_capacity_positive"),
    )
    op.create_index("ix_events_branch", "events", ["branch_id"])
    op.create_index("ix_events_starts_at", "events", ["starts_at"])

    op.create_table(
        "event_tickets",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "event_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("events.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("ticket_no", sa.String(40), nullable=False),
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="SET NULL"),
        ),
        sa.Column("customer_name", sa.String(200)),
        sa.Column("customer_phone", sa.String(20)),
        sa.Column("seat", sa.String(10)),
        sa.Column("price_paid_minor", sa.BigInteger, nullable=False),
        sa.Column("checked_in_at", sa.DateTime(timezone=True)),
        sa.Column(
            "checked_in_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
        ),
        sa.Column("status", sa.String(20), nullable=False, server_default="sold"),
        sa.Column(
            "sold_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
        ),
        sa.Column("note", sa.String(500)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("ticket_no", name="uq_event_ticket_no"),
    )
    op.create_index("ix_event_tickets_event", "event_tickets", ["event_id"])


def downgrade() -> None:
    op.drop_index("ix_event_tickets_event", table_name="event_tickets")
    op.drop_table("event_tickets")
    op.drop_index("ix_events_starts_at", table_name="events")
    op.drop_index("ix_events_branch", table_name="events")
    op.drop_table("events")
