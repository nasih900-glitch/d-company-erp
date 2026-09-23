"""Permit atomic multi-row POS settlements while preserving final balance integrity.

Revision ID: 0080
Revises: 0079
"""

from __future__ import annotations

from alembic import op

revision = "0080"
down_revision = "0079"
branch_labels = None
depends_on = None


_SPLIT_AWARE_PAYMENT_GUARD = """
CREATE OR REPLACE FUNCTION enforce_pos_payment_insert()
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
            'POS payment must settle an issued paid order'
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
    IF paid_before + NEW.amount_minor > sale_total THEN
        RAISE EXCEPTION
            'POS payment bundle exceeds the order unpaid balance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;
"""


_SINGLE_PAYMENT_GUARD = """
CREATE OR REPLACE FUNCTION enforce_pos_payment_insert()
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
"""


def upgrade() -> None:
    # The row guard permits intermediate legs only inside the current
    # transaction. Migration 0048's deferred final-balance trigger remains the
    # commit boundary: a paid invoice whose legs do not sum exactly cannot
    # commit, so a crash can never strand a partial split settlement.
    op.execute(_SPLIT_AWARE_PAYMENT_GUARD)
    op.execute(
        """
        DO $$
        BEGIN
            IF NOT EXISTS (
                SELECT 1
                  FROM pg_trigger trigger_row
                 WHERE trigger_row.tgname = 'trg_payments_final_order_balance'
                   AND trigger_row.tgrelid = 'payments'::regclass
                   AND trigger_row.tgdeferrable
                   AND trigger_row.tginitdeferred
                   AND NOT trigger_row.tgisinternal
            ) THEN
                RAISE EXCEPTION
                    'Atomic split payments require the deferred final payment balance guard';
            END IF;
        END
        $$;
        """
    )


def downgrade() -> None:
    op.execute(_SINGLE_PAYMENT_GUARD)
