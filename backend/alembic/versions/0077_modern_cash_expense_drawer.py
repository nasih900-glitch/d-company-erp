"""Add the explicit-shift modern cash paid-out expense contract.

Revision ID: 0077
Revises: 0076

Revision 51 remains the exact compatibility receipt for the deployed Code 21
Android outbox. Revision 52 is the modern contract: callers name the shift,
the database serializes on that drawer, and the expense insert/void moves cash
inside the same transaction as its immutable source fact.
"""

from __future__ import annotations

from alembic import op

revision = "0077"
down_revision = "0076"
branch_labels = None
depends_on = None


def _install_revision_52_expense_guard() -> None:
    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_and_protect_expense_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            branch_company uuid;
            category_company uuid;
            supplier_company uuid;
            upload_company uuid;
            shift_company uuid;
            shift_branch uuid;
            shift_status text;
            shift_opened_at timestamptz;
            shift_expected_minor bigint;
            creator_company uuid;
            creator_status text;
            creator_deleted_at timestamptz;
        BEGIN
            SELECT company_id INTO branch_company
              FROM branches WHERE id = NEW.branch_id;
            SELECT company_id INTO category_company
              FROM expense_categories WHERE id = NEW.category_id;
            IF branch_company IS DISTINCT FROM NEW.company_id
               OR category_company IS DISTINCT FROM NEW.company_id THEN
                RAISE EXCEPTION 'expense branch/category must belong to its company';
            END IF;

            IF NEW.supplier_id IS NOT NULL THEN
                SELECT company_id INTO supplier_company
                  FROM suppliers WHERE id = NEW.supplier_id;
                IF supplier_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION 'expense supplier must belong to its company';
                END IF;
            END IF;

            IF NEW.ocr_extraction_id IS NOT NULL THEN
                SELECT upload.company_id INTO upload_company
                  FROM ocr_extractions extraction
                  JOIN ocr_uploads upload ON upload.id = extraction.ocr_upload_id
                 WHERE extraction.id = NEW.ocr_extraction_id;
                IF upload_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION 'expense OCR evidence must belong to its company';
                END IF;
            END IF;

            IF TG_OP = 'INSERT' THEN
                IF NEW.deleted_at IS NOT NULL
                   OR NEW.voided_at IS NOT NULL
                   OR NEW.voided_by IS NOT NULL
                   OR NEW.void_reason IS NOT NULL THEN
                    RAISE EXCEPTION 'new expenses must begin active and unvoided';
                END IF;

                IF NEW.source_integrity_revision IN (51, 52) THEN
                    SELECT company_id, branch_id, status, opened_at, expected_minor
                      INTO shift_company, shift_branch, shift_status,
                           shift_opened_at, shift_expected_minor
                      FROM shifts
                     WHERE id = NEW.shift_id
                       FOR UPDATE;
                    SELECT company_id, status, deleted_at
                      INTO creator_company, creator_status, creator_deleted_at
                      FROM users WHERE id = NEW.created_by;
                    IF NEW.paid_via <> 'cash'
                       OR shift_company IS DISTINCT FROM NEW.company_id
                       OR shift_branch IS DISTINCT FROM NEW.branch_id
                       OR shift_status IS DISTINCT FROM 'open'
                       OR creator_company IS DISTINCT FROM NEW.company_id
                       OR NEW.paid_at < shift_opened_at
                       OR NEW.paid_at > CURRENT_TIMESTAMP THEN
                        RAISE EXCEPTION
                            'shift-linked cash expense has invalid scope, actor, state, or time';
                    END IF;
                    IF NEW.source_integrity_revision = 52 THEN
                        IF creator_status IS DISTINCT FROM 'active'
                           OR creator_deleted_at IS NOT NULL THEN
                            RAISE EXCEPTION
                                'modern cash expense actor must be active in its company';
                        END IF;
                        IF NEW.idempotency_key !~
                           '^expense:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' THEN
                            RAISE EXCEPTION
                                'modern cash expense action identity is not canonical';
                        END IF;
                        IF shift_expected_minor < NEW.amount_minor THEN
                            RAISE EXCEPTION
                                'cash expense exceeds expected cash in the shift drawer';
                        END IF;
                        UPDATE shifts
                           SET expected_minor = expected_minor - NEW.amount_minor
                         WHERE id = NEW.shift_id;
                    END IF;
                ELSE
                    NEW.source_integrity_revision := 50;
                END IF;
                RETURN NEW;
            END IF;

            IF ROW(
                NEW.company_id, NEW.branch_id, NEW.shift_id,
                NEW.idempotency_key, NEW.request_hash, NEW.created_by,
                NEW.category_id, NEW.supplier_id, NEW.ocr_extraction_id,
                NEW.amount_minor, NEW.paid_via, NEW.paid_at,
                NEW.vendor_name, NEW.invoice_no, NEW.note,
                NEW.created_at, NEW.deleted_at
            ) IS DISTINCT FROM ROW(
                OLD.company_id, OLD.branch_id, OLD.shift_id,
                OLD.idempotency_key, OLD.request_hash, OLD.created_by,
                OLD.category_id, OLD.supplier_id, OLD.ocr_extraction_id,
                OLD.amount_minor, OLD.paid_via, OLD.paid_at,
                OLD.vendor_name, OLD.invoice_no, OLD.note,
                OLD.created_at, OLD.deleted_at
            ) THEN
                RAISE EXCEPTION 'expense financial/provenance fields are immutable';
            END IF;

            IF OLD.voided_at IS NOT NULL THEN
                IF ROW(NEW.voided_at, NEW.voided_by, NEW.void_reason)
                   IS DISTINCT FROM
                   ROW(OLD.voided_at, OLD.voided_by, OLD.void_reason) THEN
                    RAISE EXCEPTION 'an expense void cannot be changed or reversed';
                END IF;
            ELSIF NEW.voided_at IS NOT NULL
                  OR NEW.voided_by IS NOT NULL
                  OR NEW.void_reason IS NOT NULL THEN
                IF NEW.voided_at IS NULL
                   OR NEW.voided_by IS NULL
                   OR NEW.void_reason IS NULL
                   OR length(trim(NEW.void_reason)) < 3 THEN
                    RAISE EXCEPTION 'an expense void must be populated atomically';
                END IF;
                IF OLD.source_integrity_revision IN (51, 52) THEN
                    SELECT company_id, branch_id, status
                      INTO shift_company, shift_branch, shift_status
                      FROM shifts
                     WHERE id = OLD.shift_id
                       FOR UPDATE;
                    IF shift_company IS DISTINCT FROM OLD.company_id
                       OR shift_branch IS DISTINCT FROM OLD.branch_id
                       OR shift_status IS DISTINCT FROM 'open' THEN
                        RAISE EXCEPTION
                            'shift-linked cash expense cannot be voided after shift close';
                    END IF;
                    IF OLD.source_integrity_revision = 52 THEN
                        UPDATE shifts
                           SET expected_minor = expected_minor + OLD.amount_minor
                         WHERE id = OLD.shift_id;
                    END IF;
                END IF;
            END IF;

            IF OLD.source_integrity_revision IS NOT NULL
               AND NEW.source_integrity_revision IS DISTINCT FROM
                   OLD.source_integrity_revision THEN
                RAISE EXCEPTION 'expense source revision is immutable';
            ELSIF OLD.source_integrity_revision IS NULL THEN
                NEW.source_integrity_revision := 50;
            END IF;
            RETURN NEW;
        END
        $$;
        """
    )


def _install_revision_51_expense_guard() -> None:
    """Restore the exact 0071 behavior when a clean 0077 is rolled back."""

    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_and_protect_expense_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            branch_company uuid;
            category_company uuid;
            supplier_company uuid;
            upload_company uuid;
            shift_company uuid;
            shift_branch uuid;
            shift_status text;
            shift_opened_at timestamptz;
            creator_company uuid;
        BEGIN
            SELECT company_id INTO branch_company
              FROM branches WHERE id = NEW.branch_id;
            SELECT company_id INTO category_company
              FROM expense_categories WHERE id = NEW.category_id;
            IF branch_company IS DISTINCT FROM NEW.company_id
               OR category_company IS DISTINCT FROM NEW.company_id THEN
                RAISE EXCEPTION 'expense branch/category must belong to its company';
            END IF;

            IF NEW.supplier_id IS NOT NULL THEN
                SELECT company_id INTO supplier_company
                  FROM suppliers WHERE id = NEW.supplier_id;
                IF supplier_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION 'expense supplier must belong to its company';
                END IF;
            END IF;

            IF NEW.ocr_extraction_id IS NOT NULL THEN
                SELECT upload.company_id INTO upload_company
                  FROM ocr_extractions extraction
                  JOIN ocr_uploads upload ON upload.id = extraction.ocr_upload_id
                 WHERE extraction.id = NEW.ocr_extraction_id;
                IF upload_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION 'expense OCR evidence must belong to its company';
                END IF;
            END IF;

            IF TG_OP = 'INSERT' THEN
                IF NEW.deleted_at IS NOT NULL
                   OR NEW.voided_at IS NOT NULL
                   OR NEW.voided_by IS NOT NULL
                   OR NEW.void_reason IS NOT NULL THEN
                    RAISE EXCEPTION 'new expenses must begin active and unvoided';
                END IF;

                IF NEW.source_integrity_revision = 51 THEN
                    SELECT company_id, branch_id, status, opened_at
                      INTO shift_company, shift_branch, shift_status, shift_opened_at
                      FROM shifts WHERE id = NEW.shift_id;
                    SELECT company_id INTO creator_company
                      FROM users WHERE id = NEW.created_by;
                    IF NEW.paid_via <> 'cash'
                       OR shift_company IS DISTINCT FROM NEW.company_id
                       OR shift_branch IS DISTINCT FROM NEW.branch_id
                       OR shift_status IS DISTINCT FROM 'open'
                       OR creator_company IS DISTINCT FROM NEW.company_id
                       OR NEW.paid_at < shift_opened_at
                       OR NEW.paid_at > CURRENT_TIMESTAMP THEN
                        RAISE EXCEPTION
                            'shift-linked cash expense has invalid scope, actor, state, or time';
                    END IF;
                ELSE
                    NEW.source_integrity_revision := 50;
                END IF;
                RETURN NEW;
            END IF;

            IF ROW(
                NEW.company_id, NEW.branch_id, NEW.shift_id,
                NEW.idempotency_key, NEW.request_hash, NEW.created_by,
                NEW.category_id, NEW.supplier_id, NEW.ocr_extraction_id,
                NEW.amount_minor, NEW.paid_via, NEW.paid_at,
                NEW.vendor_name, NEW.invoice_no, NEW.note,
                NEW.created_at, NEW.deleted_at
            ) IS DISTINCT FROM ROW(
                OLD.company_id, OLD.branch_id, OLD.shift_id,
                OLD.idempotency_key, OLD.request_hash, OLD.created_by,
                OLD.category_id, OLD.supplier_id, OLD.ocr_extraction_id,
                OLD.amount_minor, OLD.paid_via, OLD.paid_at,
                OLD.vendor_name, OLD.invoice_no, OLD.note,
                OLD.created_at, OLD.deleted_at
            ) THEN
                RAISE EXCEPTION 'expense financial/provenance fields are immutable';
            END IF;

            IF OLD.voided_at IS NOT NULL THEN
                IF ROW(NEW.voided_at, NEW.voided_by, NEW.void_reason)
                   IS DISTINCT FROM
                   ROW(OLD.voided_at, OLD.voided_by, OLD.void_reason) THEN
                    RAISE EXCEPTION 'an expense void cannot be changed or reversed';
                END IF;
            ELSIF NEW.voided_at IS NOT NULL
                  OR NEW.voided_by IS NOT NULL
                  OR NEW.void_reason IS NOT NULL THEN
                IF NEW.voided_at IS NULL
                   OR NEW.voided_by IS NULL
                   OR NEW.void_reason IS NULL
                   OR length(trim(NEW.void_reason)) < 3 THEN
                    RAISE EXCEPTION 'an expense void must be populated atomically';
                END IF;
                IF OLD.source_integrity_revision = 51 THEN
                    SELECT company_id, branch_id, status
                      INTO shift_company, shift_branch, shift_status
                      FROM shifts WHERE id = OLD.shift_id;
                    IF shift_company IS DISTINCT FROM OLD.company_id
                       OR shift_branch IS DISTINCT FROM OLD.branch_id
                       OR shift_status IS DISTINCT FROM 'open' THEN
                        RAISE EXCEPTION
                            'shift-linked cash expense cannot be voided after shift close';
                    END IF;
                END IF;
            END IF;

            IF OLD.source_integrity_revision IS NOT NULL
               AND NEW.source_integrity_revision IS DISTINCT FROM
                   OLD.source_integrity_revision THEN
                RAISE EXCEPTION 'expense source revision is immutable';
            ELSIF OLD.source_integrity_revision IS NULL THEN
                NEW.source_integrity_revision := 50;
            END IF;
            RETURN NEW;
        END
        $$;
        """
    )


def upgrade() -> None:
    op.drop_constraint("ck_expense_shift_receipt_provenance", "expenses", type_="check")
    op.drop_constraint("ck_expense_source_integrity_revision", "expenses", type_="check")
    op.create_check_constraint(
        "ck_expense_source_integrity_revision",
        "expenses",
        "source_integrity_revision IS NULL OR source_integrity_revision IN (50, 51, 52)",
    )
    op.create_check_constraint(
        "ck_expense_shift_receipt_provenance",
        "expenses",
        "((source_integrity_revision IS NULL OR source_integrity_revision = 50) "
        "AND shift_id IS NULL AND idempotency_key IS NULL "
        "AND request_hash IS NULL AND created_by IS NULL) OR "
        "(source_integrity_revision IN (51, 52) AND paid_via = 'cash' "
        "AND shift_id IS NOT NULL AND idempotency_key IS NOT NULL "
        "AND idempotency_key LIKE 'expense:%' "
        "AND request_hash ~ '^[0-9a-f]{64}$' AND created_by IS NOT NULL)",
    )
    _install_revision_52_expense_guard()


def downgrade() -> None:
    op.execute(
        """
        DO $$ BEGIN
            IF EXISTS (
                SELECT 1 FROM expenses WHERE source_integrity_revision = 52
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0077 after modern cash paid-out activity'
                    USING HINT =
                        'Preserve the durable drawer receipt and run revision '
                        '0077 or later.';
            END IF;
        END $$;
        """
    )
    _install_revision_51_expense_guard()
    op.drop_constraint("ck_expense_shift_receipt_provenance", "expenses", type_="check")
    op.drop_constraint("ck_expense_source_integrity_revision", "expenses", type_="check")
    op.create_check_constraint(
        "ck_expense_source_integrity_revision",
        "expenses",
        "source_integrity_revision IS NULL OR source_integrity_revision IN (50, 51)",
    )
    op.create_check_constraint(
        "ck_expense_shift_receipt_provenance",
        "expenses",
        "(source_integrity_revision IS DISTINCT FROM 51 "
        "AND shift_id IS NULL AND idempotency_key IS NULL "
        "AND request_hash IS NULL AND created_by IS NULL) OR "
        "(source_integrity_revision = 51 AND paid_via = 'cash' "
        "AND shift_id IS NOT NULL AND idempotency_key IS NOT NULL "
        "AND idempotency_key LIKE 'expense:%' "
        "AND request_hash ~ '^[0-9a-f]{64}$' AND created_by IS NOT NULL)",
    )
