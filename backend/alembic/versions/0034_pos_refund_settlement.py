"""Make POS refunds server-authoritative and shift-bound.

Revision ID: 0034
Revises: 0033
Create Date: 2026-08-26

Legacy ``refunds`` rows remain settled financial facts with their original
``created_at`` accounting date.  This migration deliberately leaves their new
provenance columns NULL: neither an order phone snapshot nor the original sale
shift can prove which customer or drawer actually received a historical payout.
No customer spend, drawer, report, or inventory value is rewritten here.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0034"
down_revision = "0033"
branch_labels = None
depends_on = None


def _replace_checkout_version_trigger(*, include_customer_id: bool) -> None:
    customer_id_column = ",\n            customer_id" if include_customer_id else ""
    op.execute("DROP TRIGGER IF EXISTS trg_orders_checkout_version ON orders")
    op.execute(
        f"""
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
            customer_phone{customer_id_column}
        ON orders
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_bump_order_checkout_version()
        """
    )


def upgrade() -> None:
    op.add_column(
        "orders",
        sa.Column(
            "customer_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.create_index("ix_orders_customer_id", "orders", ["customer_id"])
    _replace_checkout_version_trigger(include_customer_id=True)

    op.create_table(
        "pos_refund_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "order_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("orders.id", ondelete="RESTRICT"),
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
            "shift_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "approved_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "manager_override_user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
        ),
        sa.Column("reason_code", sa.String(length=50), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("mode", sa.String(length=20), nullable=False),
        sa.Column("settlement_method", sa.String(length=20), nullable=False),
        sa.Column("order_paid_snapshot_minor", sa.BigInteger(), nullable=False),
        sa.Column("order_refundable_snapshot_minor", sa.BigInteger(), nullable=False),
        sa.Column("accepted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("external_reference", sa.String(length=200)),
        sa.Column("provider_settled_at", sa.DateTime(timezone=True)),
        sa.Column("client_action_id", sa.String(length=160), nullable=False),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("note", sa.String(length=500)),
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
            "amount_minor > 0", name="ck_pos_refund_request_positive"
        ),
        sa.CheckConstraint(
            "order_paid_snapshot_minor >= amount_minor AND "
            "order_refundable_snapshot_minor >= amount_minor",
            name="ck_pos_refund_request_snapshot_balance",
        ),
        sa.CheckConstraint(
            "mode IN ('cash', 'original')", name="ck_pos_refund_request_mode"
        ),
        sa.CheckConstraint(
            "settlement_method IN ('cash', 'card', 'upi', 'qr', 'wallet')",
            name="ck_pos_refund_request_method",
        ),
        sa.CheckConstraint(
            "(mode = 'cash' AND settlement_method = 'cash') OR mode = 'original'",
            name="ck_pos_refund_request_mode_method",
        ),
        sa.CheckConstraint(
            "(settlement_method = 'cash' AND external_reference IS NULL "
            "AND provider_settled_at IS NULL) OR "
            "(settlement_method <> 'cash' "
            "AND char_length(trim(external_reference)) >= 3 "
            "AND provider_settled_at IS NOT NULL)",
            name="ck_pos_refund_request_external_provenance",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_request_company_idempotency",
        ),
        sa.UniqueConstraint(
            "company_id",
            "client_action_id",
            name="uq_pos_refund_request_company_action",
        ),
        sa.UniqueConstraint(
            "company_id",
            "settlement_method",
            "external_reference",
            name="uq_pos_refund_request_provider_reference",
        ),
    )
    op.create_index(
        "ix_pos_refund_requests_company_id", "pos_refund_requests", ["company_id"]
    )
    op.create_index(
        "ix_pos_refund_request_order_accepted",
        "pos_refund_requests",
        ["order_id", "accepted_at"],
    )
    op.create_index(
        "ix_pos_refund_request_shift_method",
        "pos_refund_requests",
        ["shift_id", "settlement_method"],
    )

    op.create_table(
        "pos_refund_cash_handoffs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "refund_request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
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
            "shift_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "started_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.UniqueConstraint(
            "refund_request_id", name="uq_pos_refund_handoff_request"
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_handoff_company_idempotency",
        ),
    )
    op.create_index(
        "ix_pos_refund_cash_handoffs_company_id",
        "pos_refund_cash_handoffs",
        ["company_id"],
    )
    op.create_index(
        "ix_pos_refund_handoff_shift", "pos_refund_cash_handoffs", ["shift_id"]
    )

    op.create_table(
        "pos_refund_withdrawals",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "refund_request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
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
            "shift_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("resolution", sa.String(length=40), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("withdrawn_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "withdrawn_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "resolution = 'cash_not_handed_over'",
            name="ck_pos_refund_withdrawal_resolution",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3", name="ck_pos_refund_withdrawal_reason"
        ),
        sa.UniqueConstraint(
            "refund_request_id", name="uq_pos_refund_withdrawal_request"
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_withdrawal_company_idempotency",
        ),
    )
    op.create_index(
        "ix_pos_refund_withdrawals_company_id",
        "pos_refund_withdrawals",
        ["company_id"],
    )
    op.create_index(
        "ix_pos_refund_withdrawal_shift", "pos_refund_withdrawals", ["shift_id"]
    )

    op.add_column(
        "refunds", sa.Column("request_id", postgresql.UUID(as_uuid=True))
    )
    op.add_column(
        "refunds", sa.Column("company_id", postgresql.UUID(as_uuid=True))
    )
    op.add_column(
        "refunds", sa.Column("branch_id", postgresql.UUID(as_uuid=True))
    )
    op.add_column(
        "refunds", sa.Column("terminal_id", postgresql.UUID(as_uuid=True))
    )
    op.add_column(
        "refunds", sa.Column("settlement_shift_id", postgresql.UUID(as_uuid=True))
    )
    op.add_column("refunds", sa.Column("settled_at", sa.DateTime(timezone=True)))
    op.add_column(
        "refunds", sa.Column("settled_by", postgresql.UUID(as_uuid=True))
    )
    op.add_column("refunds", sa.Column("external_reference", sa.String(length=200)))
    op.add_column(
        "refunds", sa.Column("provider_settled_at", sa.DateTime(timezone=True))
    )
    op.add_column(
        "refunds", sa.Column("settlement_idempotency_key", sa.String(length=160))
    )
    op.add_column("refunds", sa.Column("receipt_no", sa.String(length=32)))
    op.add_column("refunds", sa.Column("receipt_fiscal_year", sa.String(length=7)))
    op.add_column(
        "refunds", sa.Column("receipt_issued_at", sa.DateTime(timezone=True))
    )
    op.create_foreign_key(
        "fk_refunds_request_id_pos_refund_requests",
        "refunds", "pos_refund_requests", ["request_id"], ["id"], ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_refunds_company_id_companies",
        "refunds", "companies", ["company_id"], ["id"], ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_refunds_branch_id_branches",
        "refunds", "branches", ["branch_id"], ["id"], ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_refunds_terminal_id_terminals",
        "refunds", "terminals", ["terminal_id"], ["id"], ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_refunds_settlement_shift_id_shifts",
        "refunds", "shifts", ["settlement_shift_id"], ["id"], ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_refunds_settled_by_users",
        "refunds", "users", ["settled_by"], ["id"], ondelete="RESTRICT",
    )
    op.create_check_constraint(
        "ck_refund_request_settlement_provenance",
        "refunds",
        "request_id IS NULL OR (company_id IS NOT NULL AND branch_id IS NOT NULL "
        "AND terminal_id IS NOT NULL AND settlement_shift_id IS NOT NULL "
        "AND settled_at IS NOT NULL AND settled_by IS NOT NULL "
        "AND settlement_idempotency_key IS NOT NULL AND receipt_no IS NOT NULL "
        "AND receipt_fiscal_year IS NOT NULL AND receipt_issued_at IS NOT NULL)",
    )
    op.create_check_constraint(
        "ck_refund_request_external_provenance",
        "refunds",
        "request_id IS NULL OR "
        "(settlement_method = 'cash' AND external_reference IS NULL "
        "AND provider_settled_at IS NULL) OR "
        "(settlement_method <> 'cash' "
        "AND char_length(trim(external_reference)) >= 3 "
        "AND provider_settled_at IS NOT NULL)",
    )
    op.create_unique_constraint("uq_refund_request", "refunds", ["request_id"])
    op.create_unique_constraint(
        "uq_refund_company_settlement_idempotency",
        "refunds", ["company_id", "settlement_idempotency_key"],
    )
    op.create_unique_constraint(
        "uq_refund_company_receipt", "refunds", ["company_id", "receipt_no"]
    )
    op.create_index(
        "ix_refund_company_settled_at", "refunds", ["company_id", "settled_at"]
    )
    op.create_index(
        "ix_refund_settlement_shift", "refunds", ["settlement_shift_id"]
    )

    op.execute(
        """
        DO $$
        DECLARE legacy_count bigint;
        BEGIN
            SELECT count(*) INTO legacy_count FROM refunds WHERE request_id IS NULL;
            IF legacy_count > 0 THEN
                RAISE WARNING
                    '% legacy POS refund(s) need owner reconciliation',
                    legacy_count;
            END IF;
        END $$
        """
    )


def downgrade() -> None:
    # 0033 has no representation for an accepted, settled, or withdrawn POS
    # refund request. Never erase financial/audit history during rollback.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM pos_refund_requests)
               OR EXISTS (SELECT 1 FROM orders WHERE customer_id IS NOT NULL)
               OR EXISTS (
                   SELECT 1 FROM refunds
                    WHERE request_id IS NOT NULL
                       OR company_id IS NOT NULL
                       OR branch_id IS NOT NULL
                       OR terminal_id IS NOT NULL
                       OR settlement_shift_id IS NOT NULL
                       OR settled_at IS NOT NULL
                       OR settled_by IS NOT NULL
                       OR external_reference IS NOT NULL
                       OR provider_settled_at IS NOT NULL
                       OR settlement_idempotency_key IS NOT NULL
                       OR receipt_no IS NOT NULL
                       OR receipt_fiscal_year IS NOT NULL
                       OR receipt_issued_at IS NOT NULL
                ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0034: customer or refund provenance exists';
            END IF;
        END $$
        """
    )
    op.drop_index("ix_refund_settlement_shift", table_name="refunds")
    op.drop_index("ix_refund_company_settled_at", table_name="refunds")
    op.drop_constraint("uq_refund_company_receipt", "refunds", type_="unique")
    op.drop_constraint(
        "uq_refund_company_settlement_idempotency", "refunds", type_="unique"
    )
    op.drop_constraint("uq_refund_request", "refunds", type_="unique")
    op.drop_constraint(
        "ck_refund_request_external_provenance", "refunds", type_="check"
    )
    op.drop_constraint(
        "ck_refund_request_settlement_provenance", "refunds", type_="check"
    )
    for constraint in (
        "fk_refunds_settled_by_users",
        "fk_refunds_settlement_shift_id_shifts",
        "fk_refunds_terminal_id_terminals",
        "fk_refunds_branch_id_branches",
        "fk_refunds_company_id_companies",
        "fk_refunds_request_id_pos_refund_requests",
    ):
        op.drop_constraint(constraint, "refunds", type_="foreignkey")
    for column in (
        "receipt_issued_at",
        "receipt_fiscal_year",
        "receipt_no",
        "settlement_idempotency_key",
        "provider_settled_at",
        "external_reference",
        "settled_by",
        "settled_at",
        "settlement_shift_id",
        "terminal_id",
        "branch_id",
        "company_id",
        "request_id",
    ):
        op.drop_column("refunds", column)

    op.drop_index("ix_pos_refund_withdrawal_shift", table_name="pos_refund_withdrawals")
    op.drop_index(
        "ix_pos_refund_withdrawals_company_id", table_name="pos_refund_withdrawals"
    )
    op.drop_table("pos_refund_withdrawals")
    op.drop_index("ix_pos_refund_handoff_shift", table_name="pos_refund_cash_handoffs")
    op.drop_index(
        "ix_pos_refund_cash_handoffs_company_id",
        table_name="pos_refund_cash_handoffs",
    )
    op.drop_table("pos_refund_cash_handoffs")
    op.drop_index("ix_pos_refund_request_shift_method", table_name="pos_refund_requests")
    op.drop_index("ix_pos_refund_request_order_accepted", table_name="pos_refund_requests")
    op.drop_index("ix_pos_refund_requests_company_id", table_name="pos_refund_requests")
    op.drop_table("pos_refund_requests")

    _replace_checkout_version_trigger(include_customer_id=False)
    op.drop_index("ix_orders_customer_id", table_name="orders")
    op.drop_column("orders", "customer_id")
