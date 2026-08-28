"""Freeze paid POS source facts used by refunds and accounting.

Revision ID: 0048
Revises: 0047
Create Date: 2026-08-28

Refundable balance, original settlement rail, revenue, tax, and the operational
ledger all derive from ``payments`` plus the paid Order/OrderLine snapshots.
Those sources were previously mutable through bulk or direct SQL even though
``refunds`` are append-only. This revision fails closed on fundamental legacy
corruption, makes forward payments valid and append-only, and freezes paid
financial snapshots while retaining the explicitly operational KDS fields.

Cash tender evidence was not required by the oldest releases. Its constraint
is therefore installed NOT VALID: historical rows remain readable and frozen,
while every new payment must satisfy the current exact tender contract.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0048"
down_revision = "0047"
branch_labels = None
depends_on = None


def _assert_existing_rows_are_safe() -> None:
    op.execute(
        """
        DO $$
        DECLARE
            bad_payment uuid;
            bad_order uuid;
            bad_withdrawal uuid;
        BEGIN
            SELECT payment.id
              INTO bad_payment
              FROM payments payment
              LEFT JOIN orders sale ON sale.id = payment.order_id
              LEFT JOIN shifts sale_shift ON sale_shift.id = payment.shift_id
             WHERE payment.amount_minor <= 0
                OR payment.method NOT IN ('cash', 'card', 'upi', 'qr', 'wallet')
                OR sale.id IS NULL
                OR sale_shift.id IS NULL
                OR payment.shift_id IS DISTINCT FROM sale.shift_id
                OR sale_shift.company_id IS DISTINCT FROM sale.company_id
                OR sale_shift.branch_id IS DISTINCT FROM sale.branch_id
                OR sale_shift.terminal_id IS DISTINCT FROM sale.terminal_id
                OR sale.status NOT IN ('paid', 'refunded')
                OR sale.closed_at IS NULL
                OR sale.invoice_issued_at IS NULL
                OR sale.invoice_no IS NULL
                OR char_length(trim(sale.invoice_no)) = 0
                OR sale.fiscal_year IS NULL
                OR char_length(trim(sale.fiscal_year)) = 0
                OR payment.paid_at IS DISTINCT FROM sale.invoice_issued_at
                OR payment.paid_at < sale_shift.opened_at
                OR (
                    sale_shift.closed_at IS NOT NULL
                    AND payment.paid_at > sale_shift.closed_at
                )
             ORDER BY payment.id
             LIMIT 1;
            IF bad_payment IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce POS payment integrity: payment % has invalid '
                    'amount, rail, final-sale, or order/shift provenance',
                    bad_payment
                    USING HINT =
                        'Reconcile the immutable sale evidence explicitly; do not '
                        'delete or guess historical money movement.';
            END IF;

            SELECT sale.id
              INTO bad_order
              FROM orders sale
              LEFT JOIN (
                    SELECT payment.order_id, sum(payment.amount_minor) AS paid_minor
                      FROM payments payment
                     GROUP BY payment.order_id
              ) payment_totals ON payment_totals.order_id = sale.id
             WHERE (
                    sale.status IN ('paid', 'refunded')
                    OR sale.invoice_issued_at IS NOT NULL
                    OR payment_totals.order_id IS NOT NULL
             )
               AND (
                    COALESCE(payment_totals.paid_minor, 0) <> sale.total_minor
                    OR sale.status NOT IN ('paid', 'refunded')
                    OR sale.closed_at IS NULL
                    OR sale.invoice_issued_at IS NULL
                    OR sale.invoice_no IS NULL
                    OR char_length(trim(sale.invoice_no)) = 0
                    OR sale.fiscal_year IS NULL
                    OR char_length(trim(sale.fiscal_year)) = 0
               )
             ORDER BY sale.id
             LIMIT 1;
            IF bad_order IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce POS payment integrity: final order % has an '
                    'invalid invoice identity or payment balance',
                    bad_order
                    USING HINT =
                        'Reconcile missing or excess payment evidence explicitly; '
                        'do not rewrite or delete collected money.';
            END IF;

            SELECT sale.id
              INTO bad_order
              FROM orders sale
              LEFT JOIN (
                    SELECT refund.order_id, sum(refund.amount_minor) AS refunded_minor
                      FROM refunds refund
                     GROUP BY refund.order_id
              ) refund_totals ON refund_totals.order_id = sale.id
              LEFT JOIN (
                    SELECT payment.order_id, sum(payment.amount_minor) AS paid_minor
                      FROM payments payment
                     GROUP BY payment.order_id
              ) payment_totals ON payment_totals.order_id = sale.id
             WHERE (
                    refund_totals.order_id IS NOT NULL
                    AND (
                        refund_totals.refunded_minor <= 0
                        OR refund_totals.refunded_minor
                            > COALESCE(payment_totals.paid_minor, 0)
                    )
             ) OR (
                    sale.status = 'refunded'
                    AND (
                        COALESCE(payment_totals.paid_minor, 0) <= 0
                        OR COALESCE(refund_totals.refunded_minor, 0)
                            <> COALESCE(payment_totals.paid_minor, 0)
                    )
             ) OR (
                    sale.status = 'paid'
                    AND COALESCE(payment_totals.paid_minor, 0) > 0
                    AND COALESCE(refund_totals.refunded_minor, 0)
                        = COALESCE(payment_totals.paid_minor, 0)
             )
             ORDER BY sale.id
             LIMIT 1;
            IF bad_order IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce POS refund integrity: order % has non-positive '
                    'or over-paid refund history',
                    bad_order
                    USING HINT =
                        'Reconcile payments and refunds as auditable financial '
                        'facts; do not discard settled history.';
            END IF;

            SELECT withdrawal.id
              INTO bad_withdrawal
              FROM pos_refund_withdrawals withdrawal
             WHERE (
                    withdrawal.resolution = 'provider_payout_abandoned'
                    AND (
                        withdrawal.verification_reference IS NULL
                        OR char_length(trim(withdrawal.verification_reference)) < 3
                        OR withdrawal.verification_status IS NULL
                        OR withdrawal.verification_status NOT IN (
                            'no_matching_transaction',
                            'provider_declined',
                            'provider_reversed'
                        )
                        OR withdrawal.verified_at IS NULL
                    )
                )
                OR (
                    withdrawal.resolution <> 'provider_payout_abandoned'
                    AND (
                        withdrawal.verification_reference IS NOT NULL
                        OR withdrawal.verification_status IS NOT NULL
                        OR withdrawal.verified_at IS NOT NULL
                    )
                )
             ORDER BY withdrawal.id
             LIMIT 1;
            IF bad_withdrawal IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce POS refund integrity: withdrawal % has '
                    'incomplete or misplaced provider evidence',
                    bad_withdrawal
                    USING HINT =
                        'Reconcile provider outcome evidence explicitly; NULL '
                        'verification fields cannot prove that money did not move.';
            END IF;
        END
        $$;
        """
    )


def upgrade() -> None:
    _assert_existing_rows_are_safe()

    # NULL marks rows that predate this source-integrity contract. PostgreSQL
    # enforces the NOT VALID constraints for every forward insert, while the
    # revision marker lets downgrade distinguish a no-activity rollback from
    # one that would remove protection from newly accepted financial facts.
    op.add_column(
        "orders",
        sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
    )
    op.create_check_constraint(
        "ck_order_source_integrity_revision",
        "orders",
        "source_integrity_revision IS NULL OR source_integrity_revision = 48",
    )
    op.add_column(
        "payments",
        sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
    )
    op.add_column(
        "refunds",
        sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
    )
    op.execute(
        "ALTER TABLE payments ALTER COLUMN source_integrity_revision SET DEFAULT 48"
    )
    op.execute(
        "ALTER TABLE refunds ALTER COLUMN source_integrity_revision SET DEFAULT 48"
    )
    op.execute(
        "ALTER TABLE payments ADD CONSTRAINT ck_payment_source_integrity_revision "
        "CHECK (source_integrity_revision IS NOT NULL "
        "AND source_integrity_revision = 48) NOT VALID"
    )
    op.execute(
        "ALTER TABLE refunds ADD CONSTRAINT ck_refund_source_integrity_revision "
        "CHECK (source_integrity_revision IS NOT NULL "
        "AND source_integrity_revision = 48) NOT VALID"
    )

    op.create_check_constraint(
        "ck_payment_positive_amount",
        "payments",
        "amount_minor > 0",
    )
    op.create_check_constraint(
        "ck_payment_supported_method",
        "payments",
        "method IN ('cash', 'card', 'upi', 'qr', 'wallet')",
    )
    # Old cash rows may predate tender capture. Keep that truthful legacy
    # evidence readable, but reject every new row that omits or invents tender.
    op.execute(
        """
        ALTER TABLE payments
        ADD CONSTRAINT ck_payment_tender_contract
        CHECK (
            (method = 'cash'
             AND tendered_minor IS NOT NULL
             AND tendered_minor >= amount_minor
             AND change_minor IS NOT NULL
             AND change_minor = tendered_minor - amount_minor)
            OR
            (method <> 'cash'
             AND tendered_minor IS NULL
             AND change_minor IS NULL)
        ) NOT VALID
        """
    )
    op.create_check_constraint(
        "ck_refund_positive_amount",
        "refunds",
        "amount_minor > 0",
    )
    op.drop_constraint(
        "ck_pos_refund_withdrawal_verification",
        "pos_refund_withdrawals",
        type_="check",
    )
    op.create_check_constraint(
        "ck_pos_refund_withdrawal_verification",
        "pos_refund_withdrawals",
        "(resolution = 'provider_payout_abandoned' "
        "AND verification_reference IS NOT NULL "
        "AND char_length(trim(verification_reference)) >= 3 "
        "AND verification_status IS NOT NULL "
        "AND verification_status IN ("
        "'no_matching_transaction', 'provider_declined', 'provider_reversed'"
        ") AND verified_at IS NOT NULL) OR "
        "(resolution <> 'provider_payout_abandoned' "
        "AND verification_reference IS NULL "
        "AND verification_status IS NULL AND verified_at IS NULL)",
    )

    # The API finalizes the Order in the transaction before Payment is
    # flushed. Serialize direct writers on the Order and Shift, then require
    # an exact, invoice-issued settlement rather than permitting later top-ups.
    op.execute(
        """
        CREATE FUNCTION mark_pos_order_source_revision()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            new_is_final boolean;
            old_is_final boolean;
        BEGIN
            new_is_final := NEW.status IN ('paid', 'refunded')
                OR NEW.invoice_issued_at IS NOT NULL;
            IF TG_OP = 'INSERT' THEN
                NEW.source_integrity_revision := CASE
                    WHEN new_is_final THEN 48 ELSE NULL
                END;
                RETURN NEW;
            END IF;

            old_is_final := OLD.status IN ('paid', 'refunded')
                OR OLD.invoice_issued_at IS NOT NULL;
            IF OLD.source_integrity_revision IS NOT NULL THEN
                NEW.source_integrity_revision := OLD.source_integrity_revision;
            ELSIF NOT old_is_final AND new_is_final THEN
                NEW.source_integrity_revision := 48;
            ELSE
                NEW.source_integrity_revision := NULL;
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_orders_mark_source_revision
        BEFORE INSERT OR UPDATE ON orders
        FOR EACH ROW EXECUTE FUNCTION mark_pos_order_source_revision();

        CREATE FUNCTION enforce_pos_payment_insert()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            sale_company uuid;
            sale_branch uuid;
            sale_terminal uuid;
            sale_shift uuid;
            sale_status text;
            sale_total bigint;
            sale_invoice_at timestamptz;
            shift_company uuid;
            shift_branch uuid;
            shift_terminal uuid;
            shift_status text;
            shift_opened_at timestamptz;
            shift_closed_at timestamptz;
            paid_before bigint;
        BEGIN
            SELECT company_id, branch_id, terminal_id, shift_id, status,
                   total_minor, invoice_issued_at
              INTO sale_company, sale_branch, sale_terminal, sale_shift,
                   sale_status, sale_total, sale_invoice_at
              FROM orders
             WHERE id = NEW.order_id
             FOR UPDATE;
            IF NOT FOUND
               OR sale_status <> 'paid'
               OR sale_invoice_at IS NULL
               OR NEW.shift_id IS DISTINCT FROM sale_shift
               OR NEW.paid_at IS DISTINCT FROM sale_invoice_at THEN
                RAISE EXCEPTION
                    'POS payment must exactly settle an issued paid order'
                    USING ERRCODE = '23514';
            END IF;

            SELECT company_id, branch_id, terminal_id, status, opened_at, closed_at
              INTO shift_company, shift_branch, shift_terminal, shift_status,
                   shift_opened_at, shift_closed_at
              FROM shifts
             WHERE id = NEW.shift_id
             FOR UPDATE;
            IF NOT FOUND
               OR shift_company IS DISTINCT FROM sale_company
               OR shift_branch IS DISTINCT FROM sale_branch
               OR shift_terminal IS DISTINCT FROM sale_terminal
               OR shift_status <> 'open'
               OR shift_closed_at IS NOT NULL
               OR NEW.paid_at < shift_opened_at
               OR NEW.paid_at > clock_timestamp() + interval '5 minutes' THEN
                RAISE EXCEPTION
                    'POS payment order and open-shift provenance do not match'
                    USING ERRCODE = '23514';
            END IF;

            IF NOT EXISTS (
                SELECT 1
                  FROM branches branch
                 WHERE branch.id = sale_branch
                   AND branch.company_id = sale_company
            ) OR NOT EXISTS (
                SELECT 1
                  FROM terminals terminal
                 WHERE terminal.id = sale_terminal
                   AND terminal.branch_id = sale_branch
            ) THEN
                RAISE EXCEPTION
                    'POS payment branch or terminal provenance is invalid'
                    USING ERRCODE = '23514';
            END IF;

            SELECT COALESCE(sum(amount_minor), 0)
              INTO paid_before
              FROM payments
             WHERE order_id = NEW.order_id;
            IF paid_before + NEW.amount_minor IS DISTINCT FROM sale_total THEN
                RAISE EXCEPTION
                    'POS payment must equal the order unpaid balance exactly'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_payments_insert_integrity
        BEFORE INSERT ON payments
        FOR EACH ROW EXECUTE FUNCTION enforce_pos_payment_insert();

        CREATE FUNCTION prevent_pos_payment_mutation()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION 'POS payments are append-only and cannot be updated or deleted'
                USING ERRCODE = '23514';
        END
        $$;

        CREATE TRIGGER trg_payments_immutable
        BEFORE UPDATE OR DELETE ON payments
        FOR EACH ROW EXECUTE FUNCTION prevent_pos_payment_mutation();

        CREATE FUNCTION enforce_final_order_payment_balance()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            current_status text;
            current_total bigint;
            current_closed_at timestamptz;
            current_invoice_at timestamptz;
            current_invoice_no text;
            current_fiscal_year text;
            current_paid bigint;
            current_refunded bigint;
            source_order uuid;
        BEGIN
            IF TG_TABLE_NAME = 'orders' THEN
                source_order := NEW.id;
            ELSE
                source_order := NEW.order_id;
            END IF;
            SELECT status, total_minor, closed_at, invoice_issued_at,
                   invoice_no, fiscal_year
              INTO current_status, current_total, current_closed_at,
                   current_invoice_at, current_invoice_no,
                   current_fiscal_year
              FROM orders
             WHERE id = source_order;
            IF NOT FOUND THEN
                RETURN NULL;
            END IF;
            IF current_status NOT IN ('paid', 'refunded')
               AND current_invoice_at IS NULL THEN
                RETURN NULL;
            END IF;
            IF current_status NOT IN ('paid', 'refunded')
               OR current_closed_at IS NULL
               OR current_invoice_at IS NULL
               OR current_invoice_no IS NULL
               OR char_length(trim(current_invoice_no)) = 0
               OR current_fiscal_year IS NULL
               OR char_length(trim(current_fiscal_year)) = 0 THEN
                RAISE EXCEPTION
                    'issued POS order must have a coherent final status and timestamps'
                    USING ERRCODE = '23514';
            END IF;
            SELECT COALESCE(sum(amount_minor), 0)
              INTO current_paid
              FROM payments
             WHERE order_id = source_order;
            IF current_paid IS DISTINCT FROM current_total THEN
                RAISE EXCEPTION
                    'final POS order payment total must equal its invoice total'
                    USING ERRCODE = '23514';
            END IF;
            SELECT COALESCE(sum(amount_minor), 0)
              INTO current_refunded
              FROM refunds
             WHERE order_id = source_order;
            IF current_status = 'refunded' THEN
                IF current_paid <= 0
                   OR current_refunded IS DISTINCT FROM current_paid THEN
                    RAISE EXCEPTION
                        'refunded POS order must have full immutable refund settlement'
                        USING ERRCODE = '23514';
                END IF;
            ELSIF current_status = 'paid'
                  AND current_paid > 0
                  AND current_refunded >= current_paid THEN
                RAISE EXCEPTION
                    'fully refunded POS order must advance to refunded status'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NULL;
        END
        $$;

        CREATE CONSTRAINT TRIGGER trg_orders_final_payment_balance
        AFTER INSERT OR UPDATE ON orders
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_final_order_payment_balance();

        CREATE CONSTRAINT TRIGGER trg_payments_final_order_balance
        AFTER INSERT ON payments
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_final_order_payment_balance();

        CREATE CONSTRAINT TRIGGER trg_refunds_final_order_balance
        AFTER INSERT ON refunds
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_final_order_payment_balance();
        """
    )

    # Paid/refunded invoices may still advance through KDS states, and a paid
    # status may transition to refunded. Everything that feeds receipt,
    # refund, tax, loyalty, or ledger calculations is otherwise frozen.
    op.execute(
        """
        CREATE FUNCTION protect_paid_order_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_is_final boolean;
        BEGIN
            source_is_final :=
                OLD.status IN ('paid', 'refunded')
                OR OLD.invoice_issued_at IS NOT NULL
                OR EXISTS (SELECT 1 FROM payments WHERE order_id = OLD.id)
                OR EXISTS (SELECT 1 FROM refunds WHERE order_id = OLD.id);
            IF NOT source_is_final THEN
                RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
            END IF;
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'paid POS order source is immutable and cannot be deleted'
                    USING ERRCODE = '23514';
            END IF;

            IF ROW(
                NEW.id, NEW.company_id, NEW.branch_id, NEW.terminal_id,
                NEW.shift_id, NEW.opened_by, NEW.table_id, NEW.customer_id,
                NEW.type, NEW.delivery_via, NEW.subtotal_minor,
                NEW.discount_minor, NEW.manual_discount_minor,
                NEW.points_redeemed_minor, NEW.cgst_minor, NEW.sgst_minor,
                NEW.igst_minor, NEW.cess_minor, NEW.tax_minor,
                NEW.round_off_minor, NEW.tip_minor, NEW.total_minor,
                NEW.opened_at, NEW.closed_at, NEW.invoice_issued_at,
                NEW.idempotency_key, NEW.invoice_no, NEW.fiscal_year,
                NEW.customer_name, NEW.customer_phone, NEW.customer_gstin,
                NEW.customer_address, NEW.customer_state_code,
                NEW.place_of_supply_state_code, NEW.is_reverse_charge,
                NEW.irn, NEW.irn_ack_no, NEW.irn_acknowledged_at,
                NEW.e_invoice_qr, NEW.notes, NEW.held_at, NEW.created_at,
                NEW.source_integrity_revision
            ) IS DISTINCT FROM ROW(
                OLD.id, OLD.company_id, OLD.branch_id, OLD.terminal_id,
                OLD.shift_id, OLD.opened_by, OLD.table_id, OLD.customer_id,
                OLD.type, OLD.delivery_via, OLD.subtotal_minor,
                OLD.discount_minor, OLD.manual_discount_minor,
                OLD.points_redeemed_minor, OLD.cgst_minor, OLD.sgst_minor,
                OLD.igst_minor, OLD.cess_minor, OLD.tax_minor,
                OLD.round_off_minor, OLD.tip_minor, OLD.total_minor,
                OLD.opened_at, OLD.closed_at, OLD.invoice_issued_at,
                OLD.idempotency_key, OLD.invoice_no, OLD.fiscal_year,
                OLD.customer_name, OLD.customer_phone, OLD.customer_gstin,
                OLD.customer_address, OLD.customer_state_code,
                OLD.place_of_supply_state_code, OLD.is_reverse_charge,
                OLD.irn, OLD.irn_ack_no, OLD.irn_acknowledged_at,
                OLD.e_invoice_qr, OLD.notes, OLD.held_at, OLD.created_at,
                OLD.source_integrity_revision
            ) THEN
                RAISE EXCEPTION 'paid POS order financial snapshot is immutable'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.status IS DISTINCT FROM OLD.status
               AND NOT (OLD.status = 'paid' AND NEW.status = 'refunded') THEN
                RAISE EXCEPTION 'paid POS order status transition is invalid'
                    USING ERRCODE = '23514';
            END IF;
            IF OLD.status = 'paid' AND NEW.status = 'refunded'
               AND (
                    COALESCE((
                        SELECT sum(amount_minor) FROM payments
                         WHERE order_id = OLD.id
                    ), 0) <= 0
                    OR COALESCE((
                        SELECT sum(amount_minor) FROM refunds
                         WHERE order_id = OLD.id
                    ), 0) IS DISTINCT FROM COALESCE((
                        SELECT sum(amount_minor) FROM payments
                         WHERE order_id = OLD.id
                    ), 0)
               ) THEN
                RAISE EXCEPTION
                    'paid POS order can become refunded only after its full '
                    'collected amount is settled'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_orders_paid_source_integrity
        BEFORE UPDATE OR DELETE ON orders
        FOR EACH ROW EXECUTE FUNCTION protect_paid_order_source();
        """
    )

    op.execute(
        """
        CREATE FUNCTION protect_paid_order_line_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_is_final boolean;
        BEGIN
            IF TG_OP = 'INSERT' THEN
                SELECT (
                    sale.status IN ('paid', 'refunded')
                    OR sale.invoice_issued_at IS NOT NULL
                    OR EXISTS (SELECT 1 FROM payments WHERE order_id = sale.id)
                    OR EXISTS (SELECT 1 FROM refunds WHERE order_id = sale.id)
                )
                  INTO source_is_final
                  FROM orders sale
                 WHERE sale.id = NEW.order_id
                 FOR UPDATE;
            ELSIF TG_OP = 'DELETE' THEN
                SELECT (
                    sale.status IN ('paid', 'refunded')
                    OR sale.invoice_issued_at IS NOT NULL
                    OR EXISTS (SELECT 1 FROM payments WHERE order_id = sale.id)
                    OR EXISTS (SELECT 1 FROM refunds WHERE order_id = sale.id)
                )
                  INTO source_is_final
                  FROM orders sale
                 WHERE sale.id = OLD.order_id
                 FOR UPDATE;
            ELSE
                PERFORM 1
                  FROM orders sale
                 WHERE sale.id IN (OLD.order_id, NEW.order_id)
                 ORDER BY sale.id
                 FOR UPDATE;
                SELECT COALESCE(bool_or(
                    sale.status IN ('paid', 'refunded')
                    OR sale.invoice_issued_at IS NOT NULL
                    OR EXISTS (SELECT 1 FROM payments WHERE order_id = sale.id)
                    OR EXISTS (SELECT 1 FROM refunds WHERE order_id = sale.id)
                ), false)
                  INTO source_is_final
                  FROM orders sale
                 WHERE sale.id IN (OLD.order_id, NEW.order_id);
            END IF;
            IF NOT COALESCE(source_is_final, false) THEN
                RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
            END IF;
            IF TG_OP = 'INSERT' THEN
                RAISE EXCEPTION 'paid POS order cannot accept new item snapshots'
                    USING ERRCODE = '23514';
            ELSIF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'paid POS order item snapshot cannot be deleted'
                    USING ERRCODE = '23514';
            END IF;

            IF ROW(
                NEW.id, NEW.order_id, NEW.client_line_id, NEW.menu_item_id,
                NEW.variant_snapshot, NEW.modifiers, NEW.qty,
                NEW.unit_price_minor, NEW.line_total_minor, NEW.discount_minor,
                NEW.hsn_or_sac, NEW.tax_rate, NEW.taxable_value_minor,
                NEW.cgst_minor, NEW.sgst_minor, NEW.igst_minor, NEW.cess_minor,
                NEW.note, NEW.voided_at, NEW.voided_by, NEW.void_reason,
                NEW.created_at
            ) IS DISTINCT FROM ROW(
                OLD.id, OLD.order_id, OLD.client_line_id, OLD.menu_item_id,
                OLD.variant_snapshot, OLD.modifiers, OLD.qty,
                OLD.unit_price_minor, OLD.line_total_minor, OLD.discount_minor,
                OLD.hsn_or_sac, OLD.tax_rate, OLD.taxable_value_minor,
                OLD.cgst_minor, OLD.sgst_minor, OLD.igst_minor, OLD.cess_minor,
                OLD.note, OLD.voided_at, OLD.voided_by, OLD.void_reason,
                OLD.created_at
            ) OR (
                NEW.variant_id IS DISTINCT FROM OLD.variant_id
                AND NEW.variant_id IS NOT NULL
            ) THEN
                RAISE EXCEPTION 'paid POS order item financial snapshot is immutable'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_order_lines_paid_source_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON order_lines
        FOR EACH ROW EXECUTE FUNCTION protect_paid_order_line_source();
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM orders
                 WHERE source_integrity_revision IS NOT NULL
            ) OR EXISTS (
                SELECT 1 FROM payments
                 WHERE source_integrity_revision IS NOT NULL
            ) OR EXISTS (
                SELECT 1 FROM refunds
                 WHERE source_integrity_revision IS NOT NULL
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0048 after forward POS payment or refund activity'
                    USING HINT =
                        'Keep the append-only payment and paid-source guards in place; '
                        'restore the application at revision 0048 or later.';
            END IF;
        END
        $$;
        """
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_order_lines_paid_source_integrity ON order_lines"
    )
    op.execute("DROP FUNCTION IF EXISTS protect_paid_order_line_source()")
    op.execute("DROP TRIGGER IF EXISTS trg_orders_paid_source_integrity ON orders")
    op.execute("DROP FUNCTION IF EXISTS protect_paid_order_source()")
    op.execute("DROP TRIGGER IF EXISTS trg_refunds_final_order_balance ON refunds")
    op.execute("DROP TRIGGER IF EXISTS trg_payments_final_order_balance ON payments")
    op.execute("DROP TRIGGER IF EXISTS trg_orders_final_payment_balance ON orders")
    op.execute("DROP FUNCTION IF EXISTS enforce_final_order_payment_balance()")
    op.execute("DROP TRIGGER IF EXISTS trg_payments_immutable ON payments")
    op.execute("DROP FUNCTION IF EXISTS prevent_pos_payment_mutation()")
    op.execute("DROP TRIGGER IF EXISTS trg_payments_insert_integrity ON payments")
    op.execute("DROP FUNCTION IF EXISTS enforce_pos_payment_insert()")

    op.drop_constraint(
        "ck_pos_refund_withdrawal_verification",
        "pos_refund_withdrawals",
        type_="check",
    )
    op.create_check_constraint(
        "ck_pos_refund_withdrawal_verification",
        "pos_refund_withdrawals",
        "(resolution = 'provider_payout_abandoned' "
        "AND char_length(trim(verification_reference)) >= 3 "
        "AND verification_status IN ("
        "'no_matching_transaction', 'provider_declined', 'provider_reversed'"
        ") AND verified_at IS NOT NULL) OR "
        "(resolution <> 'provider_payout_abandoned' "
        "AND verification_reference IS NULL "
        "AND verification_status IS NULL AND verified_at IS NULL)",
    )
    op.drop_constraint("ck_refund_positive_amount", "refunds", type_="check")
    op.drop_constraint(
        "ck_refund_source_integrity_revision", "refunds", type_="check"
    )
    op.drop_constraint("ck_payment_tender_contract", "payments", type_="check")
    op.drop_constraint("ck_payment_supported_method", "payments", type_="check")
    op.drop_constraint("ck_payment_positive_amount", "payments", type_="check")
    op.drop_constraint(
        "ck_payment_source_integrity_revision", "payments", type_="check"
    )
    op.execute("DROP TRIGGER IF EXISTS trg_orders_mark_source_revision ON orders")
    op.execute("DROP FUNCTION IF EXISTS mark_pos_order_source_revision()")
    op.drop_constraint(
        "ck_order_source_integrity_revision", "orders", type_="check"
    )
    op.drop_column("refunds", "source_integrity_revision")
    op.drop_column("payments", "source_integrity_revision")
    op.drop_column("orders", "source_integrity_revision")
