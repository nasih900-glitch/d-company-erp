"""Free a customer's phone number for reuse after they're deleted.

Revision ID: 0030
Revises: 0029
Create Date: 2026-08-16

Customer already carries deleted_at via SoftDeleteMixin, but the phone
uniqueness constraint was a plain UniqueConstraint(company_id, phone) that
doesn't know about it — soft-deleting a customer left their phone number
permanently unusable, since a real DB-level unique constraint sees the old
row regardless of deleted_at. Replaced with a partial unique index scoped
to `WHERE deleted_at IS NULL`, so the number frees up the instant the
customer is deleted (see DELETE /customers/{id} in customers/router.py).
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0030"
down_revision = "0029"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.drop_constraint("uq_customer_phone_per_company", "customers", type_="unique")
    op.create_index(
        "uq_customer_phone_per_company_live",
        "customers",
        ["company_id", "phone"],
        unique=True,
        postgresql_where=sa.text("deleted_at IS NULL"),
    )


def downgrade() -> None:
    op.drop_index("uq_customer_phone_per_company_live", table_name="customers")
    op.create_unique_constraint(
        "uq_customer_phone_per_company", "customers", ["company_id", "phone"]
    )
