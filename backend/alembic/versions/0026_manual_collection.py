"""Add immutable, auditable manual daily collections.

Revision ID: 0026
Revises: 0025
Create Date: 2026-07-16

This revision was never deployed in its earlier shift-lump-sum form, so it is
safe to repurpose it before release.  A collection is an independent finance
record: it never creates an order, invoice, shift, customer, stock movement,
or loyalty event.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0026"
down_revision = "0025"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "manual_collections",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("branch_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("business_date", sa.Date(), nullable=False),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column(
            "source_kind",
            sa.String(length=30),
            nullable=False,
            server_default="manual_daily",
        ),
        sa.Column("source_ref", sa.String(length=160), nullable=False),
        sa.Column("note", sa.String(length=500), nullable=True),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("voided_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("voided_by", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("void_reason", sa.String(length=500), nullable=True),
        sa.CheckConstraint(
            "method IN ('cash', 'upi', 'card', 'bank')",
            name="ck_manual_collection_method",
        ),
        sa.CheckConstraint(
            "amount_minor > 0",
            name="ck_manual_collection_positive_amount",
        ),
        sa.CheckConstraint(
            "source_kind IN ('manual_daily', 'legacy_daily')",
            name="ck_manual_collection_source_kind",
        ),
        sa.CheckConstraint(
            "length(trim(source_ref)) > 0",
            name="ck_manual_collection_source_ref_present",
        ),
        sa.CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_manual_collection_idempotency_key_present",
        ),
        sa.CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3)",
            name="ck_manual_collection_void_state",
        ),
        sa.ForeignKeyConstraint(
            ["company_id"], ["companies.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["branch_id"], ["branches.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["created_by"], ["users.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["voided_by"], ["users.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_manual_collection_company_idempotency",
        ),
        sa.UniqueConstraint(
            "company_id",
            "branch_id",
            "source_kind",
            "source_ref",
            "method",
            name="uq_manual_collection_source_method",
        ),
    )
    op.create_index(
        "ix_manual_collections_company_id",
        "manual_collections",
        ["company_id"],
    )
    op.create_index(
        "ix_manual_collections_branch_id",
        "manual_collections",
        ["branch_id"],
    )
    op.create_index(
        "ix_manual_collections_created_by",
        "manual_collections",
        ["created_by"],
    )
    op.create_index(
        "ix_manual_collections_voided_by",
        "manual_collections",
        ["voided_by"],
    )
    op.create_index(
        "ix_manual_collection_company_business_date",
        "manual_collections",
        ["company_id", "business_date"],
    )
    op.create_index(
        "ix_manual_collection_branch_business_date",
        "manual_collections",
        ["branch_id", "business_date"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_manual_collection_branch_business_date",
        table_name="manual_collections",
    )
    op.drop_index(
        "ix_manual_collection_company_business_date",
        table_name="manual_collections",
    )
    op.drop_index("ix_manual_collections_voided_by", table_name="manual_collections")
    op.drop_index("ix_manual_collections_created_by", table_name="manual_collections")
    op.drop_index("ix_manual_collections_branch_id", table_name="manual_collections")
    op.drop_index("ix_manual_collections_company_id", table_name="manual_collections")
    op.drop_table("manual_collections")
