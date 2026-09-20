"""Add immutable expense receipt evidence and append-only review history.

Revision ID: 0074
Revises: 0073
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0074"
down_revision = "0073"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "expense_receipts",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("expense_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("uploader_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("original_filename", sa.String(length=200), nullable=False),
        sa.Column("content_type", sa.String(length=40), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("sha256", sa.String(length=64), nullable=False),
        sa.Column("source", sa.String(length=20), nullable=False),
        sa.Column("payload", sa.LargeBinary(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint(
            "content_type IN ('image/jpeg', 'image/png', 'image/webp', "
            "'application/pdf')",
            name="ck_expense_receipts_content_type",
        ),
        sa.CheckConstraint(
            "source IN ('camera', 'gallery', 'file')",
            name="ck_expense_receipts_source",
        ),
        sa.CheckConstraint(
            "size_bytes BETWEEN 1 AND 10485760",
            name="ck_expense_receipts_size",
        ),
        sa.CheckConstraint(
            "sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_expense_receipts_sha256",
        ),
        sa.CheckConstraint(
            "octet_length(payload) = size_bytes "
            "AND octet_length(payload) <= 10485760 "
            "AND sha256 = encode(digest(payload, 'sha256'), 'hex')",
            name="ck_expense_receipts_payload_integrity",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["expense_id"], ["expenses.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["uploader_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "expense_id",
            "sha256",
            name="uq_expense_receipts_expense_sha256",
        ),
    )
    op.create_index(
        "ix_expense_receipts_company_id",
        "expense_receipts",
        ["company_id"],
    )
    op.create_index(
        "ix_expense_receipts_expense_id",
        "expense_receipts",
        ["expense_id"],
    )
    op.create_index(
        "ix_expense_receipts_company_expense_created",
        "expense_receipts",
        ["company_id", "expense_id", "created_at"],
    )

    op.create_table(
        "expense_receipt_reviews",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("expense_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False),
        sa.Column("review_note", sa.String(length=500), nullable=True),
        sa.Column("reviewed_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.CheckConstraint(
            "status IN ('pending', 'verified', 'not_required', 'rejected')",
            name="ck_expense_receipt_reviews_status",
        ),
        sa.CheckConstraint(
            "(status IN ('rejected', 'not_required') "
            "AND review_note IS NOT NULL AND length(trim(review_note)) >= 3) "
            "OR status IN ('pending', 'verified')",
            name="ck_expense_receipt_reviews_note",
        ),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["expense_id"], ["expenses.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["reviewed_by"], ["users.id"], ondelete="RESTRICT"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_expense_receipt_reviews_company_id",
        "expense_receipt_reviews",
        ["company_id"],
    )
    op.create_index(
        "ix_expense_receipt_reviews_expense_id",
        "expense_receipt_reviews",
        ["expense_id"],
    )
    op.create_index(
        "ix_expense_receipt_reviews_company_expense_created",
        "expense_receipt_reviews",
        ["company_id", "expense_id", "created_at"],
    )

    op.execute(
        """
        CREATE FUNCTION enforce_expense_receipt_integrity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            expense_company uuid;
            expense_deleted_at timestamptz;
            expense_voided_at timestamptz;
            uploader_company uuid;
            uploader_status text;
            uploader_deleted_at timestamptz;
        BEGIN
            IF TG_OP <> 'INSERT' THEN
                RAISE EXCEPTION
                    'expense receipt evidence is immutable and cannot be changed or deleted';
            END IF;

            SELECT company_id, deleted_at, voided_at
              INTO expense_company, expense_deleted_at, expense_voided_at
              FROM expenses
             WHERE id = NEW.expense_id
               FOR UPDATE;
            IF expense_company IS NULL
               OR expense_company IS DISTINCT FROM NEW.company_id
               OR expense_deleted_at IS NOT NULL THEN
                RAISE EXCEPTION
                    'expense receipt must reference a visible expense in the same company';
            END IF;
            IF expense_voided_at IS NOT NULL THEN
                RAISE EXCEPTION 'cannot add receipt evidence to a voided expense';
            END IF;
            IF (
                SELECT COUNT(*)
                  FROM expense_receipts
                 WHERE company_id = NEW.company_id
                   AND expense_id = NEW.expense_id
            ) >= 5 THEN
                RAISE EXCEPTION 'an expense can contain up to five receipt files';
            END IF;

            SELECT company_id, status, deleted_at
              INTO uploader_company, uploader_status, uploader_deleted_at
              FROM users
             WHERE id = NEW.uploader_user_id;
            IF uploader_company IS NULL
               OR uploader_company IS DISTINCT FROM NEW.company_id
               OR uploader_status IS DISTINCT FROM 'active'
               OR uploader_deleted_at IS NOT NULL THEN
                RAISE EXCEPTION
                    'expense receipt uploader must be an active user in the same company';
            END IF;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_expense_receipt_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON expense_receipts
        FOR EACH ROW EXECUTE FUNCTION enforce_expense_receipt_integrity()
        """
    )

    op.execute(
        """
        CREATE FUNCTION enforce_expense_receipt_review_integrity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            expense_company uuid;
            expense_deleted_at timestamptz;
            expense_voided_at timestamptz;
            reviewer_company uuid;
            reviewer_status text;
            reviewer_deleted_at timestamptz;
        BEGIN
            IF TG_OP <> 'INSERT' THEN
                RAISE EXCEPTION
                    'expense receipt review history is immutable';
            END IF;

            SELECT company_id, deleted_at, voided_at
              INTO expense_company, expense_deleted_at, expense_voided_at
              FROM expenses
             WHERE id = NEW.expense_id
               FOR UPDATE;
            IF expense_company IS NULL
               OR expense_company IS DISTINCT FROM NEW.company_id
               OR expense_deleted_at IS NOT NULL THEN
                RAISE EXCEPTION
                    'expense receipt review must reference a visible expense in the same company';
            END IF;
            IF expense_voided_at IS NOT NULL THEN
                RAISE EXCEPTION 'cannot review receipt evidence for a voided expense';
            END IF;

            SELECT company_id, status, deleted_at
              INTO reviewer_company, reviewer_status, reviewer_deleted_at
              FROM users
             WHERE id = NEW.reviewed_by;
            IF reviewer_company IS NULL
               OR reviewer_company IS DISTINCT FROM NEW.company_id
               OR reviewer_status IS DISTINCT FROM 'active'
               OR reviewer_deleted_at IS NOT NULL THEN
                RAISE EXCEPTION
                    'expense receipt reviewer must be an active user in the same company';
            END IF;

            IF NEW.status IN ('verified', 'rejected')
               AND NOT EXISTS (
                    SELECT 1
                      FROM expense_receipts receipt
                     WHERE receipt.company_id = NEW.company_id
                       AND receipt.expense_id = NEW.expense_id
               ) THEN
                RAISE EXCEPTION
                    'verified or rejected receipt status requires retained evidence';
            END IF;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_expense_receipt_review_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON expense_receipt_reviews
        FOR EACH ROW EXECUTE FUNCTION enforce_expense_receipt_review_integrity()
        """
    )


def downgrade() -> None:
    evidence_exists = bool(
        op.get_bind().execute(
            sa.text(
                """
                SELECT EXISTS (SELECT 1 FROM expense_receipts)
                    OR EXISTS (SELECT 1 FROM expense_receipt_reviews)
                """
            )
        ).scalar_one()
    )
    if evidence_exists:
        raise RuntimeError(
            "0074 downgrade refused: expense receipt evidence or review history exists"
        )

    op.execute(
        "DROP TRIGGER IF EXISTS trg_expense_receipt_review_integrity "
        "ON expense_receipt_reviews"
    )
    op.execute("DROP FUNCTION IF EXISTS enforce_expense_receipt_review_integrity()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_expense_receipt_integrity ON expense_receipts"
    )
    op.execute("DROP FUNCTION IF EXISTS enforce_expense_receipt_integrity()")
    op.drop_table("expense_receipt_reviews")
    op.drop_table("expense_receipts")
