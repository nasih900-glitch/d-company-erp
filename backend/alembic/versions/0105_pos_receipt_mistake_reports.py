"""Keep receipt mistake reports separate from financial corrections."""

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0105"
down_revision = "0104"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "pos_receipt_mistake_reports",
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
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "original_shift_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "original_terminal_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "reporter_user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("idempotency_key", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("invoice_no", sa.String(32), nullable=False),
        sa.Column("reason", sa.String(32), nullable=False),
        sa.Column("note", sa.String(500), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.UniqueConstraint(
            "company_id",
            "branch_id",
            "reporter_user_id",
            "idempotency_key",
            name="uq_pos_receipt_mistake_request",
        ),
        sa.CheckConstraint(
            "reason IN ('wrong_item', 'wrong_payment_method', 'no_funds_received', 'other')",
            name="ck_pos_receipt_mistake_reason",
        ),
        sa.CheckConstraint("request_hash ~ '^[0-9a-f]{64}$'", name="ck_pos_receipt_mistake_hash"),
    )
    op.create_index(
        "ix_pos_receipt_mistake_reports_company_id", "pos_receipt_mistake_reports", ["company_id"]
    )
    op.create_index(
        "ix_pos_receipt_mistake_branch_history",
        "pos_receipt_mistake_reports",
        ["company_id", "branch_id", "created_at", "id"],
    )
    op.execute("""
        CREATE FUNCTION dcompany_keep_pos_receipt_mistake_reports()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION 'Receipt mistake reports are append-only' USING ERRCODE = '23514';
        END;
        $$;
        CREATE TRIGGER pos_receipt_mistake_report_immutable
            BEFORE UPDATE OR DELETE ON pos_receipt_mistake_reports FOR EACH ROW
            EXECUTE FUNCTION dcompany_keep_pos_receipt_mistake_reports();
        CREATE TRIGGER pos_receipt_mistake_report_no_truncate
            BEFORE TRUNCATE ON pos_receipt_mistake_reports FOR EACH STATEMENT
            EXECUTE FUNCTION dcompany_keep_pos_receipt_mistake_reports();
    """)


def downgrade() -> None:
    op.execute("""
        LOCK TABLE pos_receipt_mistake_reports IN ACCESS EXCLUSIVE MODE;
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM pos_receipt_mistake_reports) THEN
                RAISE EXCEPTION 'Retained receipt mistake reports prevent downgrade';
            END IF;
        END $$;
    """)
    op.drop_table("pos_receipt_mistake_reports")
    op.execute("DROP FUNCTION dcompany_keep_pos_receipt_mistake_reports()")
