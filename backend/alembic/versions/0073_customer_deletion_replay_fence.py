"""Fence delayed customer identity writes after deletion.

Revision ID: 0073
Revises: 0072
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0073"
down_revision = "0072"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "customer_directory_state",
        sa.Column("company_id", sa.UUID(), nullable=False),
        sa.Column("deletion_revision", sa.BigInteger(), server_default="0", nullable=False),
        sa.CheckConstraint(
            "deletion_revision >= 0",
            name="ck_customer_directory_state_nonnegative_revision",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("company_id"),
    )
    op.execute(
        """
        INSERT INTO customer_directory_state (company_id, deletion_revision)
        SELECT companies.id, COUNT(customers.id)::bigint
        FROM companies
        LEFT JOIN customers
          ON customers.company_id = companies.id
         AND customers.deleted_at IS NOT NULL
        GROUP BY companies.id
        """
    )
    op.add_column(
        "orders",
        sa.Column("customer_directory_revision", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "gaming_sessions",
        sa.Column("customer_directory_revision", sa.BigInteger(), nullable=True),
    )


def downgrade() -> None:
    durable_evidence_exists = bool(
        op.get_bind().execute(
            sa.text(
                """
                SELECT
                    EXISTS (
                        SELECT 1 FROM customer_directory_state
                        WHERE deletion_revision > 0
                    )
                    OR EXISTS (
                        SELECT 1 FROM orders
                        WHERE customer_directory_revision IS NOT NULL
                    )
                    OR EXISTS (
                        SELECT 1 FROM gaming_sessions
                        WHERE customer_directory_revision IS NOT NULL
                    )
                """
            )
        ).scalar_one()
    )
    if durable_evidence_exists:
        raise RuntimeError(
            "0073 downgrade refused: customer deletion or captured directory revision "
            "evidence exists"
        )
    op.drop_column("gaming_sessions", "customer_directory_revision")
    op.drop_column("orders", "customer_directory_revision")
    op.drop_table("customer_directory_state")
