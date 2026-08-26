"""Add exclusive checkout claims for shared held orders.

Revision ID: 0031
Revises: 0030
Create Date: 2026-08-25
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0031"
down_revision = "0030"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "orders",
        sa.Column(
            "checkout_version",
            sa.Integer(),
            nullable=False,
            server_default="1",
        ),
    )
    op.create_table(
        "order_checkout_claims",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="CASCADE"),
            nullable=False,
        ),
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
        sa.Column(
            "terminal_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "claimed_by_user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("token_hash", sa.String(length=64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("order_total_minor", sa.BigInteger(), nullable=False),
        sa.Column("due_minor", sa.BigInteger(), nullable=False),
        sa.Column("order_version", sa.Integer(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "char_length(token_hash) = 64",
            name="ck_order_checkout_claim_token_hash_length",
        ),
        sa.CheckConstraint(
            "order_total_minor >= 0 AND due_minor >= 0 "
            "AND due_minor <= order_total_minor",
            name="ck_order_checkout_claim_nonnegative_balance",
        ),
        sa.CheckConstraint(
            "order_version > 0",
            name="ck_order_checkout_claim_positive_version",
        ),
        sa.UniqueConstraint("order_id", name="uq_order_checkout_claim_order"),
    )
    op.create_index(
        "ix_order_checkout_claims_company_id",
        "order_checkout_claims",
        ["company_id"],
    )
    op.create_index(
        "ix_order_checkout_claims_expires_at",
        "order_checkout_claims",
        ["expires_at"],
    )

    # This database-level version is deliberately independent of individual
    # API call sites.  Imports, admin scripts, and future clients cannot alter
    # a checkout-relevant field without invalidating an earlier claim.
    op.execute(
        """
        CREATE FUNCTION dcompany_bump_order_checkout_version()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            NEW.checkout_version := OLD.checkout_version + 1;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_orders_checkout_version
        BEFORE UPDATE OF
            status,
            table_id,
            subtotal_minor,
            discount_minor,
            manual_discount_minor,
            points_redeemed_minor,
            cgst_minor,
            sgst_minor,
            igst_minor,
            cess_minor,
            tax_minor,
            round_off_minor,
            tip_minor,
            total_minor,
            customer_name,
            customer_phone
        ON orders
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_bump_order_checkout_version()
        """
    )


def downgrade() -> None:
    op.execute("DROP TRIGGER IF EXISTS trg_orders_checkout_version ON orders")
    op.execute("DROP FUNCTION IF EXISTS dcompany_bump_order_checkout_version()")
    op.drop_index(
        "ix_order_checkout_claims_expires_at",
        table_name="order_checkout_claims",
    )
    op.drop_index(
        "ix_order_checkout_claims_company_id",
        table_name="order_checkout_claims",
    )
    op.drop_table("order_checkout_claims")
    op.drop_column("orders", "checkout_version")
