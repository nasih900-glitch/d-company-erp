"""Add drawer-linked manual cash and immutable closed-shift corrections.

Revision ID: 0078
Revises: 0077

New live cash collections name one open drawer.  A source whose original
drawer is already closed is never rewritten: a full, append-only correction
posts the opposite movement into a selected current open drawer.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0078"
down_revision = "0077"
branch_labels = None
depends_on = None


def _supplier_payment_insert_scope_function(*, exclude_corrected: bool) -> str:
    """Build the 0050 guard with 0078's append-only correction semantics.

    A corrected supplier payment stays immutable and unvoided, but no longer
    settles the GRN's Accounts Payable balance.  The upgraded function excludes
    that source when authorising a replacement.  Downgrade restores the exact
    pre-0078 active-payment definition before the correction table is removed.
    """

    corrected_filter = (
        """
               AND NOT EXISTS (
                   SELECT 1
                     FROM finance_source_corrections correction
                    WHERE correction.supplier_payment_id = payment.id
               )
        """
        if exclude_corrected
        else ""
    )
    return f"""
        CREATE OR REPLACE FUNCTION validate_supplier_payment_insert_scope()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_company uuid;
            source_branch uuid;
            source_supplier uuid;
            received_at timestamptz;
            receipt_total bigint;
            receipt_company uuid;
            receipt_branch uuid;
            receipt_ref_type text;
            receipt_ref_id uuid;
            receipt_voided_at timestamptz;
            receipt_voided_by uuid;
            receipt_void_reason text;
            branch_company uuid;
            supplier_company uuid;
            creator_company uuid;
            payment_journal_company uuid;
            payment_journal_branch uuid;
            payment_journal_ref_type text;
            payment_journal_ref_id uuid;
            payment_journal_total bigint;
            payment_journal_posted_at timestamptz;
            payment_journal_voided_at timestamptz;
            active_paid bigint;
        BEGIN
            IF NEW.voided_at IS NOT NULL
               OR NEW.voided_by IS NOT NULL
               OR NEW.void_reason IS NOT NULL THEN
                RAISE EXCEPTION 'new supplier payments must begin active';
            END IF;

            SELECT purchase_order.company_id,
                   purchase_order.branch_id,
                   purchase_order.supplier_id,
                   grn.received_at,
                   receipt.total_minor,
                   receipt.company_id,
                   receipt.branch_id,
                   receipt.ref_type,
                   receipt.ref_id,
                   receipt.voided_at,
                   receipt.voided_by,
                   receipt.void_reason
              INTO source_company, source_branch, source_supplier, received_at,
                   receipt_total, receipt_company, receipt_branch,
                   receipt_ref_type, receipt_ref_id, receipt_voided_at,
                   receipt_voided_by, receipt_void_reason
              FROM grns grn
              JOIN purchase_orders purchase_order
                ON purchase_order.id = grn.purchase_order_id
              LEFT JOIN journal_entries receipt
                ON receipt.id = grn.journal_entry_id
             WHERE grn.id = NEW.grn_id
             FOR UPDATE OF grn;

            SELECT company_id INTO branch_company
              FROM branches WHERE id = NEW.branch_id;
            SELECT company_id INTO supplier_company
              FROM suppliers WHERE id = NEW.supplier_id;
            SELECT company_id INTO creator_company
              FROM users WHERE id = NEW.created_by;
            SELECT company_id, branch_id, ref_type, ref_id, total_minor,
                   posted_at, voided_at
              INTO payment_journal_company, payment_journal_branch,
                   payment_journal_ref_type, payment_journal_ref_id,
                   payment_journal_total, payment_journal_posted_at,
                   payment_journal_voided_at
              FROM journal_entries WHERE id = NEW.journal_entry_id;

            IF source_company IS DISTINCT FROM NEW.company_id
               OR source_branch IS DISTINCT FROM NEW.branch_id
               OR source_supplier IS DISTINCT FROM NEW.supplier_id
               OR branch_company IS DISTINCT FROM NEW.company_id
               OR supplier_company IS DISTINCT FROM NEW.company_id
               OR creator_company IS DISTINCT FROM NEW.company_id
               OR receipt_company IS DISTINCT FROM NEW.company_id
               OR receipt_branch IS DISTINCT FROM NEW.branch_id
               OR receipt_ref_type IS DISTINCT FROM 'grn_receipt'
               OR receipt_ref_id IS DISTINCT FROM NEW.grn_id
               OR receipt_voided_at IS NOT NULL
               OR receipt_voided_by IS NOT NULL
               OR receipt_void_reason IS NOT NULL THEN
                RAISE EXCEPTION
                    'supplier payment source references must share company, branch, '
                    'supplier, and GRN provenance';
            END IF;
            IF NEW.paid_at < received_at THEN
                RAISE EXCEPTION 'supplier payment cannot predate its GRN';
            END IF;
            IF payment_journal_company IS DISTINCT FROM NEW.company_id
               OR payment_journal_branch IS DISTINCT FROM NEW.branch_id
               OR payment_journal_ref_type IS DISTINCT FROM 'supplier_payment'
               OR payment_journal_ref_id IS DISTINCT FROM NEW.id
               OR payment_journal_total IS DISTINCT FROM NEW.amount_minor
               OR payment_journal_posted_at IS DISTINCT FROM NEW.paid_at
               OR payment_journal_voided_at IS NOT NULL THEN
                RAISE EXCEPTION
                    'supplier payment journal must exactly match its active source';
            END IF;

            SELECT COALESCE(SUM(payment.amount_minor), 0)
              INTO active_paid
              FROM supplier_payments payment
             WHERE payment.grn_id = NEW.grn_id
               AND payment.voided_at IS NULL
               {corrected_filter};
            IF receipt_total IS NULL
               OR active_paid + NEW.amount_minor > receipt_total THEN
                RAISE EXCEPTION
                    'supplier payment exceeds the authoritative GRN AP balance';
            END IF;
            RETURN NEW;
        END
        $$;
    """


def upgrade() -> None:
    op.add_column(
        "manual_collections",
        sa.Column("shift_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "manual_collections",
        sa.Column("request_hash", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "manual_collections",
        sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
    )
    op.create_foreign_key(
        "fk_manual_collections_shift",
        "manual_collections",
        "shifts",
        ["shift_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index(
        "ix_manual_collections_shift_id",
        "manual_collections",
        ["shift_id"],
    )
    op.create_check_constraint(
        "ck_manual_collection_source_integrity_revision",
        "manual_collections",
        "source_integrity_revision IS NULL OR source_integrity_revision = 1",
    )
    op.create_check_constraint(
        "ck_manual_collection_drawer_receipt",
        "manual_collections",
        "(source_integrity_revision IS NULL AND shift_id IS NULL "
        "AND request_hash IS NULL) OR "
        "(source_integrity_revision = 1 AND request_hash ~ '^[0-9a-f]{64}$' "
        "AND idempotency_key ~ "
        "'^manual-collection:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' "
        "AND ((method = 'cash' AND shift_id IS NOT NULL) "
        "OR (method <> 'cash' AND shift_id IS NULL)))",
    )
    op.alter_column(
        "manual_collections",
        "source_integrity_revision",
        server_default=sa.text("1"),
    )

    for table_name in ("tip_payouts", "supplier_payments"):
        op.add_column(
            table_name,
            sa.Column("shift_id", postgresql.UUID(as_uuid=True), nullable=True),
        )
        op.add_column(
            table_name,
            sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
        )
        op.create_foreign_key(
            f"fk_{table_name}_shift",
            table_name,
            "shifts",
            ["shift_id"],
            ["id"],
            ondelete="RESTRICT",
        )
        op.create_index(f"ix_{table_name}_shift_id", table_name, ["shift_id"])
        op.create_check_constraint(
            f"ck_{table_name[:-1]}_source_integrity_revision",
            table_name,
            "source_integrity_revision IS NULL OR source_integrity_revision = 1",
        )
        op.alter_column(
            table_name,
            "source_integrity_revision",
            server_default=sa.text("1"),
        )
    op.add_column(
        "tip_payouts",
        sa.Column("request_hash", sa.String(length=64), nullable=True),
    )
    op.create_check_constraint(
        "ck_tip_payout_drawer_receipt",
        "tip_payouts",
        "(source_integrity_revision IS NULL AND shift_id IS NULL "
        "AND request_hash IS NULL) OR "
        "(source_integrity_revision = 1 AND request_hash ~ '^[0-9a-f]{64}$' "
        "AND idempotency_key ~ "
        "'^tip-payout:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' "
        "AND ((method = 'cash' AND shift_id IS NOT NULL) "
        "OR (method <> 'cash' AND shift_id IS NULL)))",
    )
    op.create_check_constraint(
        "ck_supplier_payment_drawer_receipt",
        "supplier_payments",
        "(source_integrity_revision IS NULL AND shift_id IS NULL) OR "
        "(source_integrity_revision = 1 AND request_hash ~ '^[0-9a-f]{64}$' "
        "AND idempotency_key ~ "
        "'^supplier-payment:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' "
        "AND ((method = 'cash' AND shift_id IS NOT NULL) "
        "OR (method <> 'cash' AND shift_id IS NULL)))",
    )

    op.create_table(
        "finance_source_corrections",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
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
        sa.Column("source_type", sa.String(length=30), nullable=False),
        sa.Column(
            "expense_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("expenses.id", ondelete="RESTRICT"),
            nullable=True,
        ),
        sa.Column(
            "manual_collection_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("manual_collections.id", ondelete="RESTRICT"),
            nullable=True,
        ),
        sa.Column(
            "tip_payout_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("tip_payouts.id", ondelete="RESTRICT"),
            nullable=True,
        ),
        sa.Column(
            "supplier_payment_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("supplier_payments.id", ondelete="RESTRICT"),
            nullable=True,
        ),
        sa.Column(
            "original_shift_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "settlement_shift_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column(
            "corrected_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column(
            "corrected_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "source_type IN ('expense', 'manual_collection', 'tip_payout', "
            "'supplier_payment')",
            name="ck_finance_source_correction_type",
        ),
        sa.CheckConstraint(
            "(source_type = 'expense' AND expense_id IS NOT NULL "
            "AND manual_collection_id IS NULL AND tip_payout_id IS NULL "
            "AND supplier_payment_id IS NULL) OR "
            "(source_type = 'manual_collection' AND manual_collection_id IS NOT NULL "
            "AND expense_id IS NULL AND tip_payout_id IS NULL "
            "AND supplier_payment_id IS NULL) OR "
            "(source_type = 'tip_payout' AND tip_payout_id IS NOT NULL "
            "AND expense_id IS NULL AND manual_collection_id IS NULL "
            "AND supplier_payment_id IS NULL) OR "
            "(source_type = 'supplier_payment' AND supplier_payment_id IS NOT NULL "
            "AND expense_id IS NULL AND manual_collection_id IS NULL "
            "AND tip_payout_id IS NULL)",
            name="ck_finance_source_correction_exact_source",
        ),
        sa.CheckConstraint(
            "amount_minor > 0",
            name="ck_finance_source_correction_positive_amount",
        ),
        sa.CheckConstraint(
            "length(trim(reason)) >= 3",
            name="ck_finance_source_correction_reason",
        ),
        sa.CheckConstraint(
            "request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_finance_source_correction_request_hash",
        ),
        sa.CheckConstraint(
            "(source_type = 'expense' AND idempotency_key ~ "
            "'^expense-correction:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') OR "
            "(source_type = 'manual_collection' AND idempotency_key ~ "
            "'^manual-collection-correction:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') OR "
            "(source_type = 'tip_payout' AND idempotency_key ~ "
            "'^tip-payout-correction:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') OR "
            "(source_type = 'supplier_payment' AND idempotency_key ~ "
            "'^supplier-payment-correction:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$')",
            name="ck_finance_source_correction_action_identity",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_finance_source_correction_idempotency",
        ),
        sa.UniqueConstraint(
            "expense_id",
            name="uq_finance_source_correction_expense",
        ),
        sa.UniqueConstraint(
            "manual_collection_id",
            name="uq_finance_source_correction_manual_collection",
        ),
        sa.UniqueConstraint(
            "tip_payout_id",
            name="uq_finance_source_correction_tip_payout",
        ),
        sa.UniqueConstraint(
            "supplier_payment_id",
            name="uq_finance_source_correction_supplier_payment",
        ),
    )
    for column in (
        "company_id",
        "branch_id",
        "expense_id",
        "manual_collection_id",
        "tip_payout_id",
        "supplier_payment_id",
        "original_shift_id",
        "settlement_shift_id",
        "corrected_by",
        "corrected_at",
    ):
        op.create_index(
            f"ix_finance_source_corrections_{column}",
            "finance_source_corrections",
            [column],
        )

    # Migration 0050's active-balance guard predates append-only corrections.
    # A corrected payment must stop settling AP so a replacement can be posted,
    # while the original source and journal remain immutable historical facts.
    op.execute(_supplier_payment_insert_scope_function(exclude_corrected=True))

    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_manual_collection_drawer()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            branch_company uuid;
            actor_company uuid;
            actor_status text;
            actor_deleted_at timestamptz;
            shift_company uuid;
            shift_branch uuid;
            shift_status text;
            shift_expected_minor bigint;
        BEGIN
            IF TG_OP = 'INSERT' THEN
                SELECT company_id INTO branch_company
                  FROM branches WHERE id = NEW.branch_id;
                SELECT company_id, status, deleted_at
                  INTO actor_company, actor_status, actor_deleted_at
                  FROM users WHERE id = NEW.created_by;
                IF branch_company IS DISTINCT FROM NEW.company_id
                   OR actor_company IS DISTINCT FROM NEW.company_id
                   OR actor_status IS DISTINCT FROM 'active'
                   OR actor_deleted_at IS NOT NULL THEN
                    RAISE EXCEPTION
                        'manual collection has invalid tenant, branch, or actor';
                END IF;

                IF NEW.source_integrity_revision IS DISTINCT FROM 1 THEN
                    RAISE EXCEPTION
                        'new manual collection requires revision 1 drawer provenance';
                ELSE
                    IF NEW.source_kind IS DISTINCT FROM 'manual_daily' THEN
                        RAISE EXCEPTION
                            'new drawer-linked collection must be manual_daily';
                    END IF;
                    IF NEW.method = 'cash' THEN
                        SELECT company_id, branch_id, status, expected_minor
                          INTO shift_company, shift_branch, shift_status,
                               shift_expected_minor
                          FROM shifts
                         WHERE id = NEW.shift_id
                           FOR UPDATE;
                        IF shift_company IS DISTINCT FROM NEW.company_id
                           OR shift_branch IS DISTINCT FROM NEW.branch_id
                           OR shift_status IS DISTINCT FROM 'open' THEN
                            RAISE EXCEPTION
                                'cash collection requires a current open same-branch shift';
                        END IF;
                        UPDATE shifts
                           SET expected_minor = expected_minor + NEW.amount_minor
                         WHERE id = NEW.shift_id;
                    ELSIF NEW.shift_id IS NOT NULL THEN
                        RAISE EXCEPTION
                            'noncash collection cannot name a cash drawer';
                    END IF;
                END IF;
                RETURN NEW;
            END IF;

            IF OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL THEN
                IF EXISTS (
                    SELECT 1 FROM finance_source_corrections
                     WHERE manual_collection_id = OLD.id
                ) THEN
                    RAISE EXCEPTION
                        'corrected manual collection cannot also be voided';
                END IF;
                SELECT company_id, status, deleted_at
                  INTO actor_company, actor_status, actor_deleted_at
                  FROM users WHERE id = NEW.voided_by;
                IF actor_company IS DISTINCT FROM OLD.company_id
                   OR actor_status IS DISTINCT FROM 'active'
                   OR actor_deleted_at IS NOT NULL THEN
                    RAISE EXCEPTION 'manual collection void actor is invalid';
                END IF;
                IF OLD.source_integrity_revision = 1 AND OLD.method = 'cash' THEN
                    SELECT company_id, branch_id, status, expected_minor
                      INTO shift_company, shift_branch, shift_status,
                           shift_expected_minor
                      FROM shifts
                     WHERE id = OLD.shift_id
                       FOR UPDATE;
                    IF shift_company IS DISTINCT FROM OLD.company_id
                       OR shift_branch IS DISTINCT FROM OLD.branch_id
                       OR shift_status IS DISTINCT FROM 'open' THEN
                        RAISE EXCEPTION
                            'closed-shift cash collection requires a current-period correction';
                    END IF;
                    IF shift_expected_minor < OLD.amount_minor THEN
                        RAISE EXCEPTION
                            'cash collection void exceeds expected cash in its drawer';
                    END IF;
                    UPDATE shifts
                       SET expected_minor = expected_minor - OLD.amount_minor
                     WHERE id = OLD.shift_id;
                END IF;
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_manual_collections_drawer
        BEFORE INSERT OR UPDATE ON manual_collections
        FOR EACH ROW EXECUTE FUNCTION validate_manual_collection_drawer();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_outgoing_finance_drawer()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            actor_company uuid;
            actor_status text;
            actor_deleted_at timestamptz;
            branch_company uuid;
            shift_company uuid;
            shift_branch uuid;
            shift_status text;
            shift_expected_minor bigint;
            already_corrected boolean;
        BEGIN
            IF TG_OP = 'INSERT' THEN
                SELECT company_id INTO branch_company
                  FROM branches WHERE id = NEW.branch_id;
                SELECT company_id, status, deleted_at
                  INTO actor_company, actor_status, actor_deleted_at
                  FROM users WHERE id = NEW.created_by;
                IF branch_company IS DISTINCT FROM NEW.company_id
                   OR actor_company IS DISTINCT FROM NEW.company_id
                   OR actor_status IS DISTINCT FROM 'active'
                   OR actor_deleted_at IS NOT NULL THEN
                    RAISE EXCEPTION '% actor is invalid', TG_TABLE_NAME;
                END IF;
                IF NEW.source_integrity_revision IS DISTINCT FROM 1 THEN
                    RAISE EXCEPTION
                        'new % requires revision 1 drawer provenance', TG_TABLE_NAME;
                ELSE
                    IF NEW.method = 'cash' THEN
                        SELECT company_id, branch_id, status, expected_minor
                          INTO shift_company, shift_branch, shift_status,
                               shift_expected_minor
                          FROM shifts
                         WHERE id = NEW.shift_id
                           FOR UPDATE;
                        IF shift_company IS DISTINCT FROM NEW.company_id
                           OR shift_branch IS DISTINCT FROM NEW.branch_id
                           OR shift_status IS DISTINCT FROM 'open' THEN
                            RAISE EXCEPTION
                                '% cash requires a current open same-branch shift',
                                TG_TABLE_NAME;
                        END IF;
                        IF shift_expected_minor < NEW.amount_minor THEN
                            RAISE EXCEPTION
                                '% exceeds expected cash in the selected drawer',
                                TG_TABLE_NAME;
                        END IF;
                        UPDATE shifts
                           SET expected_minor = expected_minor - NEW.amount_minor
                         WHERE id = NEW.shift_id;
                    ELSIF NEW.shift_id IS NOT NULL THEN
                        RAISE EXCEPTION
                            'noncash % cannot name a cash drawer', TG_TABLE_NAME;
                    END IF;
                END IF;
                RETURN NEW;
            END IF;

            IF OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL THEN
                IF TG_TABLE_NAME = 'tip_payouts' THEN
                    SELECT EXISTS (
                        SELECT 1 FROM finance_source_corrections
                         WHERE tip_payout_id = OLD.id
                    ) INTO already_corrected;
                ELSE
                    SELECT EXISTS (
                        SELECT 1 FROM finance_source_corrections
                         WHERE supplier_payment_id = OLD.id
                    ) INTO already_corrected;
                END IF;
                IF already_corrected THEN
                    RAISE EXCEPTION 'corrected % cannot also be voided', TG_TABLE_NAME;
                END IF;
                SELECT company_id, status, deleted_at
                  INTO actor_company, actor_status, actor_deleted_at
                  FROM users WHERE id = NEW.voided_by;
                IF actor_company IS DISTINCT FROM OLD.company_id
                   OR actor_status IS DISTINCT FROM 'active'
                   OR actor_deleted_at IS NOT NULL THEN
                    RAISE EXCEPTION '% void actor is invalid', TG_TABLE_NAME;
                END IF;
                IF OLD.source_integrity_revision = 1 AND OLD.method = 'cash' THEN
                    SELECT company_id, branch_id, status
                      INTO shift_company, shift_branch, shift_status
                      FROM shifts
                     WHERE id = OLD.shift_id
                       FOR UPDATE;
                    IF shift_company IS DISTINCT FROM OLD.company_id
                       OR shift_branch IS DISTINCT FROM OLD.branch_id
                       OR shift_status IS DISTINCT FROM 'open' THEN
                        RAISE EXCEPTION
                            'closed-shift % requires a current-period correction',
                            TG_TABLE_NAME;
                    END IF;
                    UPDATE shifts
                       SET expected_minor = expected_minor + OLD.amount_minor
                     WHERE id = OLD.shift_id;
                END IF;
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_tip_payouts_drawer
        BEFORE INSERT OR UPDATE ON tip_payouts
        FOR EACH ROW EXECUTE FUNCTION validate_outgoing_finance_drawer();

        CREATE TRIGGER trg_supplier_payments_drawer
        BEFORE INSERT OR UPDATE ON supplier_payments
        FOR EACH ROW EXECUTE FUNCTION validate_outgoing_finance_drawer();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_and_apply_finance_source_correction()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_company uuid;
            source_branch uuid;
            source_shift uuid;
            source_amount bigint;
            source_method text;
            source_voided_at timestamptz;
            source_deleted_at timestamptz;
            source_revision smallint;
            source_kind_value text;
            source_grn uuid;
            original_status text;
            original_company uuid;
            original_branch uuid;
            settlement_company uuid;
            settlement_branch uuid;
            settlement_status text;
            settlement_expected bigint;
            actor_company uuid;
            actor_status text;
            actor_deleted_at timestamptz;
            drawer_delta bigint;
        BEGIN
            IF TG_OP <> 'INSERT' THEN
                RAISE EXCEPTION 'finance source corrections are append-only';
            END IF;

            NEW.corrected_at := CURRENT_TIMESTAMP;
            SELECT company_id, status, deleted_at
              INTO actor_company, actor_status, actor_deleted_at
              FROM users WHERE id = NEW.corrected_by;
            IF actor_company IS DISTINCT FROM NEW.company_id
               OR actor_status IS DISTINCT FROM 'active'
               OR actor_deleted_at IS NOT NULL THEN
                RAISE EXCEPTION 'finance correction actor is invalid';
            END IF;

            PERFORM id FROM shifts
             WHERE id = NEW.original_shift_id OR id = NEW.settlement_shift_id
             ORDER BY id
             FOR UPDATE;
            SELECT company_id, branch_id, status, expected_minor
              INTO settlement_company, settlement_branch, settlement_status,
                   settlement_expected
              FROM shifts WHERE id = NEW.settlement_shift_id;
            SELECT company_id, branch_id, status
              INTO original_company, original_branch, original_status
              FROM shifts WHERE id = NEW.original_shift_id;
            IF settlement_company IS DISTINCT FROM NEW.company_id
               OR settlement_branch IS DISTINCT FROM NEW.branch_id
               OR settlement_status IS DISTINCT FROM 'open'
               OR original_company IS DISTINCT FROM NEW.company_id
               OR original_branch IS DISTINCT FROM NEW.branch_id
               OR original_status NOT IN ('closed', 'reconciled') THEN
                RAISE EXCEPTION
                    'finance correction requires a closed original shift and current open same-branch shift';
            END IF;

            IF NEW.source_type = 'expense' THEN
                SELECT company_id, branch_id, shift_id, amount_minor, paid_via,
                       voided_at, deleted_at, source_integrity_revision
                  INTO source_company, source_branch, source_shift,
                       source_amount, source_method, source_voided_at,
                       source_deleted_at, source_revision
                  FROM expenses
                 WHERE id = NEW.expense_id
                   FOR UPDATE;
                IF source_company IS DISTINCT FROM NEW.company_id
                   OR source_branch IS DISTINCT FROM NEW.branch_id
                   OR source_shift IS DISTINCT FROM NEW.original_shift_id
                   OR source_amount IS DISTINCT FROM NEW.amount_minor
                   OR source_method IS DISTINCT FROM 'cash'
                   OR source_revision NOT IN (51, 52)
                   OR source_voided_at IS NOT NULL
                   OR source_deleted_at IS NOT NULL THEN
                    RAISE EXCEPTION
                        'expense correction does not match an active closed-shift cash expense';
                END IF;
                drawer_delta := NEW.amount_minor;
            ELSIF NEW.source_type = 'manual_collection' THEN
                SELECT collection.company_id, collection.branch_id,
                       collection.shift_id, collection.amount_minor,
                       collection.method, collection.voided_at,
                       collection.source_integrity_revision,
                       collection.source_kind
                  INTO source_company, source_branch, source_shift,
                       source_amount, source_method, source_voided_at,
                       source_revision, source_kind_value
                  FROM manual_collections AS collection
                 WHERE collection.id = NEW.manual_collection_id
                   FOR UPDATE;
                IF source_company IS DISTINCT FROM NEW.company_id
                   OR source_branch IS DISTINCT FROM NEW.branch_id
                   OR source_shift IS DISTINCT FROM NEW.original_shift_id
                   OR source_amount IS DISTINCT FROM NEW.amount_minor
                   OR source_method IS DISTINCT FROM 'cash'
                   OR source_revision IS DISTINCT FROM 1
                   OR source_kind_value IS DISTINCT FROM 'manual_daily'
                   OR source_voided_at IS NOT NULL THEN
                    RAISE EXCEPTION
                        'manual collection correction does not match an active closed-shift cash collection';
                END IF;
                IF settlement_expected < NEW.amount_minor THEN
                    RAISE EXCEPTION
                        'manual collection correction exceeds current drawer cash';
                END IF;
                drawer_delta := -NEW.amount_minor;
            ELSIF NEW.source_type = 'tip_payout' THEN
                SELECT company_id, branch_id, shift_id, amount_minor, method,
                       voided_at, source_integrity_revision
                  INTO source_company, source_branch, source_shift,
                       source_amount, source_method, source_voided_at,
                       source_revision
                  FROM tip_payouts
                 WHERE id = NEW.tip_payout_id
                   FOR UPDATE;
                IF source_company IS DISTINCT FROM NEW.company_id
                   OR source_branch IS DISTINCT FROM NEW.branch_id
                   OR source_shift IS DISTINCT FROM NEW.original_shift_id
                   OR source_amount IS DISTINCT FROM NEW.amount_minor
                   OR source_method IS DISTINCT FROM 'cash'
                   OR source_revision IS DISTINCT FROM 1
                   OR source_voided_at IS NOT NULL THEN
                    RAISE EXCEPTION
                        'tip payout correction does not match an active closed-shift cash payout';
                END IF;
                drawer_delta := NEW.amount_minor;
            ELSIF NEW.source_type = 'supplier_payment' THEN
                SELECT company_id, branch_id, shift_id, amount_minor, method,
                       voided_at, source_integrity_revision, grn_id
                  INTO source_company, source_branch, source_shift,
                       source_amount, source_method, source_voided_at,
                       source_revision, source_grn
                  FROM supplier_payments
                 WHERE id = NEW.supplier_payment_id
                   FOR UPDATE;
                IF source_company IS DISTINCT FROM NEW.company_id
                   OR source_branch IS DISTINCT FROM NEW.branch_id
                   OR source_shift IS DISTINCT FROM NEW.original_shift_id
                   OR source_amount IS DISTINCT FROM NEW.amount_minor
                   OR source_method IS DISTINCT FROM 'cash'
                   OR source_revision IS DISTINCT FROM 1
                   OR source_voided_at IS NOT NULL THEN
                    RAISE EXCEPTION
                        'supplier payment correction does not match an active closed-shift cash payment';
                END IF;
                -- Serialize the AP balance transition with replacement-payment
                -- inserts, whose 0050 guard locks this same GRN before summing
                -- active payments.
                PERFORM id FROM grns WHERE id = source_grn FOR UPDATE;
                IF NOT FOUND THEN
                    RAISE EXCEPTION
                        'supplier payment correction has no authoritative GRN';
                END IF;
                drawer_delta := NEW.amount_minor;
            ELSE
                RAISE EXCEPTION 'unsupported finance correction source';
            END IF;

            UPDATE shifts
               SET expected_minor = expected_minor + drawer_delta
             WHERE id = NEW.settlement_shift_id;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_finance_source_corrections_apply
        BEFORE INSERT OR UPDATE OR DELETE ON finance_source_corrections
        FOR EACH ROW EXECUTE FUNCTION validate_and_apply_finance_source_correction();

        CREATE OR REPLACE FUNCTION reject_corrected_expense_void()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL
               AND EXISTS (
                   SELECT 1 FROM finance_source_corrections
                    WHERE expense_id = OLD.id
               ) THEN
                RAISE EXCEPTION 'corrected expense cannot also be voided';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_expenses_a_corrected_void_guard
        BEFORE UPDATE ON expenses
        FOR EACH ROW EXECUTE FUNCTION reject_corrected_expense_void();
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DO $$ BEGIN
            IF EXISTS (SELECT 1 FROM finance_source_corrections)
               OR EXISTS (
                   SELECT 1 FROM manual_collections
                    WHERE source_integrity_revision = 1
               ) OR EXISTS (
                   SELECT 1 FROM tip_payouts
                    WHERE source_integrity_revision = 1
               ) OR EXISTS (
                   SELECT 1 FROM supplier_payments
                    WHERE source_integrity_revision = 1
               ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0078 after drawer-linked finance activity'
                    USING HINT =
                        'Preserve immutable corrections and drawer receipts; '
                        'restore a backup or run revision 0078 or later.';
            END IF;
        END $$;
        """
    )
    # Remove 0078's reference to the correction table before dropping it.
    op.execute(_supplier_payment_insert_scope_function(exclude_corrected=False))
    op.execute("DROP TRIGGER trg_expenses_a_corrected_void_guard ON expenses")
    op.execute("DROP FUNCTION reject_corrected_expense_void()")
    op.execute(
        "DROP TRIGGER trg_finance_source_corrections_apply "
        "ON finance_source_corrections"
    )
    op.execute("DROP FUNCTION validate_and_apply_finance_source_correction()")
    op.execute("DROP TRIGGER trg_supplier_payments_drawer ON supplier_payments")
    op.execute("DROP TRIGGER trg_tip_payouts_drawer ON tip_payouts")
    op.execute("DROP FUNCTION validate_outgoing_finance_drawer()")
    op.execute("DROP TRIGGER trg_manual_collections_drawer ON manual_collections")
    op.execute("DROP FUNCTION validate_manual_collection_drawer()")
    op.drop_table("finance_source_corrections")
    op.drop_constraint(
        "ck_supplier_payment_drawer_receipt",
        "supplier_payments",
        type_="check",
    )
    op.drop_constraint(
        "ck_tip_payout_drawer_receipt",
        "tip_payouts",
        type_="check",
    )
    op.drop_column("tip_payouts", "request_hash")
    for table_name in ("supplier_payments", "tip_payouts"):
        op.drop_constraint(
            f"ck_{table_name[:-1]}_source_integrity_revision",
            table_name,
            type_="check",
        )
        op.drop_index(f"ix_{table_name}_shift_id", table_name=table_name)
        op.drop_constraint(
            f"fk_{table_name}_shift",
            table_name,
            type_="foreignkey",
        )
        op.drop_column(table_name, "source_integrity_revision")
        op.drop_column(table_name, "shift_id")
    op.drop_constraint(
        "ck_manual_collection_drawer_receipt",
        "manual_collections",
        type_="check",
    )
    op.drop_constraint(
        "ck_manual_collection_source_integrity_revision",
        "manual_collections",
        type_="check",
    )
    op.drop_index("ix_manual_collections_shift_id", table_name="manual_collections")
    op.drop_constraint(
        "fk_manual_collections_shift",
        "manual_collections",
        type_="foreignkey",
    )
    op.drop_column("manual_collections", "source_integrity_revision")
    op.drop_column("manual_collections", "request_hash")
    op.drop_column("manual_collections", "shift_id")
