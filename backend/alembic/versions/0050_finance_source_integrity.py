"""Freeze finance sources and separate spendable cash from clearing value.

Revision ID: 0050
Revises: 0049
Create Date: 2026-08-28

Expense, asset, category, account, partner, supplier-payment, capital, and
journal rows feed historical P&L, depreciation, ownership allocation,
Accounts Payable, partner capital, and balance-sheet results. Those facts must
not be editable or physically deleted after posting. This revision fails
closed on tenant/reference corruption, gives expenses an auditable one-way
void, and installs database guards so ORM bulk/native SQL cannot bypass the
source contract.

The asset register remains a depreciation schedule only: it has no proven
payment rail or acquisition journal linkage, so this migration deliberately
does not invent a cash/bank movement from ``purchase_minor``.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0050"
down_revision = "0049"
branch_labels = None
depends_on = None


def _assert_existing_rows_are_safe() -> None:
    op.execute(
        """
        DO $$
        DECLARE
            bad_expense uuid;
            bad_asset uuid;
            bad_category uuid;
            bad_partner uuid;
            bad_partner_company uuid;
            bad_supplier_payment uuid;
            bad_supplier_journal uuid;
            bad_account uuid;
            bad_journal uuid;
            bad_capital uuid;
            bad_manual_collection uuid;
            bad_tip_payout uuid;
            bad_grn uuid;
            bad_grn_journal uuid;
        BEGIN
            SELECT expense.id
              INTO bad_expense
              FROM expenses expense
              LEFT JOIN branches branch ON branch.id = expense.branch_id
              LEFT JOIN expense_categories category
                ON category.id = expense.category_id
              LEFT JOIN suppliers supplier ON supplier.id = expense.supplier_id
              LEFT JOIN ocr_extractions extraction
                ON extraction.id = expense.ocr_extraction_id
              LEFT JOIN ocr_uploads upload
                ON upload.id = extraction.ocr_upload_id
             WHERE expense.amount_minor <= 0
                OR expense.deleted_at IS NOT NULL
                OR expense.paid_via NOT IN ('cash', 'card', 'bank', 'upi')
                OR branch.id IS NULL
                OR branch.company_id IS DISTINCT FROM expense.company_id
                OR category.id IS NULL
                OR category.company_id IS DISTINCT FROM expense.company_id
                OR (
                    expense.supplier_id IS NOT NULL
                    AND (
                        supplier.id IS NULL
                        OR supplier.company_id IS DISTINCT FROM expense.company_id
                    )
                )
                OR (
                    expense.ocr_extraction_id IS NOT NULL
                    AND (
                        extraction.id IS NULL
                        OR upload.id IS NULL
                        OR upload.company_id IS DISTINCT FROM expense.company_id
                    )
                )
             ORDER BY expense.id
             LIMIT 1;
            IF bad_expense IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: expense % has an '
                    'invalid amount, rail, tenant/reference scope, or '
                    'unaudited soft-delete',
                    bad_expense
                    USING HINT =
                        'Reconcile the expense source explicitly; do not delete '
                        'or silently move historical expenditure.';
            END IF;

            SELECT asset.id
              INTO bad_asset
              FROM assets asset
              LEFT JOIN branches branch ON branch.id = asset.branch_id
             WHERE asset.purchase_minor <= 0
                OR asset.deleted_at IS NOT NULL
                OR asset.salvage_minor < 0
                OR asset.salvage_minor > asset.purchase_minor
                OR asset.useful_life_months <= 0
                OR asset.depreciation_method IS DISTINCT FROM 'straight_line'
                OR branch.id IS NULL
                OR branch.company_id IS DISTINCT FROM asset.company_id
             ORDER BY asset.id
             LIMIT 1;
            IF bad_asset IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: asset % has an '
                    'invalid valuation, depreciation method, tenant scope, or '
                    'unaudited soft-delete',
                    bad_asset
                    USING HINT =
                        'Reconcile the fixed-asset register explicitly before '
                        'freezing depreciation source facts.';
            END IF;

            SELECT category.id
              INTO bad_category
              FROM expense_categories category
              LEFT JOIN accounts account ON account.id = category.gl_account_id
             WHERE category.gl_account_id IS NOT NULL
               AND (
                    account.id IS NULL
                    OR account.company_id IS DISTINCT FROM category.company_id
                    OR account.type IS DISTINCT FROM 'expense'
               )
             ORDER BY category.id
             LIMIT 1;
            IF bad_category IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: expense category % '
                    'references an invalid or cross-tenant account',
                    bad_category
                    USING HINT =
                        'Map the category to a same-company expense account; do '
                        'not recategorize posted expense history implicitly.';
            END IF;

            SELECT partner.id
              INTO bad_partner
             FROM partners partner
              LEFT JOIN users linked_user ON linked_user.id = partner.user_id
             WHERE partner.share_pct IS NULL
                OR partner.share_pct <= 0
                OR partner.share_pct > 100
                OR partner.name IS NULL
                OR length(trim(partner.name)) = 0
                OR (
                    partner.user_id IS NOT NULL
                    AND (
                        linked_user.id IS NULL
                        OR linked_user.company_id IS DISTINCT FROM partner.company_id
                    )
                )
             ORDER BY partner.id
             LIMIT 1;
            IF bad_partner IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: partner % has an '
                    'invalid share, name, or tenant-scoped user link',
                    bad_partner
                    USING HINT =
                        'Reconcile the ownership agreement explicitly; do not '
                        'rewrite historical partner allocation.';
            END IF;

            SELECT partner.company_id
              INTO bad_partner_company
              FROM partners partner
             GROUP BY partner.company_id
            HAVING SUM(partner.share_pct) > 100
             ORDER BY partner.company_id
             LIMIT 1;
            IF bad_partner_company IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: partner shares for '
                    'company % exceed 100 percent',
                    bad_partner_company
                    USING HINT =
                        'Reconcile the ownership agreement explicitly; reports '
                        'must not normalize contradictory ownership percentages.';
            END IF;

            SELECT payment.id
              INTO bad_supplier_payment
              FROM supplier_payments payment
              LEFT JOIN branches branch ON branch.id = payment.branch_id
              LEFT JOIN suppliers supplier ON supplier.id = payment.supplier_id
              LEFT JOIN grns grn ON grn.id = payment.grn_id
              LEFT JOIN purchase_orders purchase_order
                ON purchase_order.id = grn.purchase_order_id
              LEFT JOIN journal_entries receipt_journal
                ON receipt_journal.id = grn.journal_entry_id
              LEFT JOIN journal_entries payment_journal
                ON payment_journal.id = payment.journal_entry_id
              LEFT JOIN users creator ON creator.id = payment.created_by
              LEFT JOIN users voider ON voider.id = payment.voided_by
             WHERE branch.company_id IS DISTINCT FROM payment.company_id
                OR supplier.company_id IS DISTINCT FROM payment.company_id
                OR purchase_order.company_id IS DISTINCT FROM payment.company_id
                OR purchase_order.branch_id IS DISTINCT FROM payment.branch_id
                OR purchase_order.supplier_id IS DISTINCT FROM payment.supplier_id
                OR receipt_journal.company_id IS DISTINCT FROM payment.company_id
                OR receipt_journal.branch_id IS DISTINCT FROM payment.branch_id
                OR receipt_journal.ref_type IS DISTINCT FROM 'grn_receipt'
                OR receipt_journal.ref_id IS DISTINCT FROM payment.grn_id
                OR receipt_journal.voided_at IS NOT NULL
                OR receipt_journal.voided_by IS NOT NULL
                OR receipt_journal.void_reason IS NOT NULL
                OR payment.paid_at < grn.received_at
                OR creator.company_id IS DISTINCT FROM payment.company_id
                OR (
                    payment.voided_by IS NOT NULL
                    AND voider.company_id IS DISTINCT FROM payment.company_id
                )
                OR payment_journal.company_id IS DISTINCT FROM payment.company_id
                OR payment_journal.branch_id IS DISTINCT FROM payment.branch_id
                OR payment_journal.ref_type IS DISTINCT FROM 'supplier_payment'
                OR payment_journal.ref_id IS DISTINCT FROM payment.id
                OR payment_journal.total_minor IS DISTINCT FROM payment.amount_minor
                OR payment_journal.posted_at IS DISTINCT FROM payment.paid_at
                OR ROW(
                    payment_journal.voided_at,
                    payment_journal.voided_by,
                    payment_journal.void_reason
                ) IS DISTINCT FROM ROW(
                    payment.voided_at,
                    payment.voided_by,
                    payment.void_reason
                )
                OR (
                    payment.voided_at IS NULL
                    AND (
                        SELECT COALESCE(SUM(active.amount_minor), 0)
                          FROM supplier_payments active
                         WHERE active.grn_id = payment.grn_id
                           AND active.voided_at IS NULL
                    ) > receipt_journal.total_minor
                )
             ORDER BY payment.id
             LIMIT 1;
            IF bad_supplier_payment IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: supplier payment % '
                    'has invalid tenant, GRN, journal, actor, timing, or AP balance '
                    'provenance',
                    bad_supplier_payment
                    USING HINT =
                        'Reconcile the supplier settlement and its paired journal '
                        'explicitly before retrying the migration.';
            END IF;

            SELECT journal.id
              INTO bad_supplier_journal
              FROM journal_entries journal
             WHERE journal.ref_type = 'supplier_payment'
               AND NOT EXISTS (
                    SELECT 1
                      FROM supplier_payments payment
                     WHERE payment.id = journal.ref_id
                       AND payment.journal_entry_id = journal.id
                       AND payment.company_id = journal.company_id
                       AND payment.branch_id IS NOT DISTINCT FROM journal.branch_id
                       AND payment.amount_minor = journal.total_minor
               )
             ORDER BY journal.id
             LIMIT 1;
            IF bad_supplier_journal IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: supplier-payment '
                    'journal % has no exact settlement source',
                    bad_supplier_journal
                    USING HINT =
                        'Reconcile the orphan journal without inventing a payment.';
            END IF;

            SELECT account.id
              INTO bad_account
              FROM accounts account
              LEFT JOIN accounts parent ON parent.id = account.parent_id
             WHERE account.type NOT IN (
                       'asset', 'liability', 'equity', 'revenue', 'expense'
                   )
                OR account.normal_side NOT IN ('dr', 'cr')
                OR length(trim(account.code)) = 0
                OR length(trim(account.name)) = 0
                OR (
                    account.parent_id IS NOT NULL
                    AND (
                        parent.id IS NULL
                        OR parent.company_id IS DISTINCT FROM account.company_id
                    )
                )
             ORDER BY account.id
             LIMIT 1;
            IF bad_account IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: account % has an '
                    'invalid identity, normal side, type, or parent tenant scope',
                    bad_account
                    USING HINT =
                        'Reconcile chart-of-account identity before freezing it.';
            END IF;

            SELECT journal.id
              INTO bad_journal
              FROM journal_entries journal
              LEFT JOIN branches branch ON branch.id = journal.branch_id
              LEFT JOIN users voider ON voider.id = journal.voided_by
              LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS line_count,
                           COALESCE(SUM(line.amount_minor)
                               FILTER (WHERE line.side = 'dr'), 0) AS debit_total,
                           COALESCE(SUM(line.amount_minor)
                               FILTER (WHERE line.side = 'cr'), 0) AS credit_total,
                           BOOL_OR(
                               account.id IS NULL
                               OR account.company_id IS DISTINCT FROM
                                  journal.company_id
                               OR line.side NOT IN ('dr', 'cr')
                               OR line.amount_minor <= 0
                           ) AS invalid_line
                      FROM journal_lines line
                      LEFT JOIN accounts account ON account.id = line.account_id
                     WHERE line.journal_entry_id = journal.id
              ) totals ON TRUE
             WHERE journal.total_minor <= 0
                OR length(trim(journal.ref_type)) = 0
                OR (
                    journal.branch_id IS NOT NULL
                    AND (
                        branch.id IS NULL
                        OR branch.company_id IS DISTINCT FROM journal.company_id
                    )
                )
                OR totals.line_count < 2
                OR totals.invalid_line IS TRUE
                OR totals.debit_total IS DISTINCT FROM journal.total_minor
                OR totals.credit_total IS DISTINCT FROM journal.total_minor
                OR NOT (
                    (
                        journal.voided_at IS NULL
                        AND journal.voided_by IS NULL
                        AND journal.void_reason IS NULL
                    ) OR (
                        journal.voided_at IS NOT NULL
                        AND journal.voided_by IS NOT NULL
                        AND journal.void_reason IS NOT NULL
                        AND length(trim(journal.void_reason)) >= 3
                        AND voider.company_id IS NOT DISTINCT FROM journal.company_id
                    )
                )
             ORDER BY journal.id
             LIMIT 1;
            IF bad_journal IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: journal % is '
                    'unbalanced or has invalid tenant, account, line, or void provenance',
                    bad_journal
                    USING HINT =
                        'Reconcile the posted journal explicitly; migration 0050 '
                        'will not freeze contradictory accounting facts.';
            END IF;

            SELECT capital.id
              INTO bad_capital
              FROM capital_entries capital
              JOIN partners partner ON partner.id = capital.partner_id
              LEFT JOIN users creator ON creator.id = capital.created_by
              LEFT JOIN users voider ON voider.id = capital.voided_by
             WHERE capital.type NOT IN ('invest', 'withdraw')
                OR capital.amount_minor <= 0
                OR capital.settlement_account NOT IN (
                    'cash', 'bank', 'upi', 'historical_funds'
                )
                OR (
                    capital.source_ref IS NOT NULL
                    AND length(trim(capital.source_ref)) = 0
                )
                OR (
                    capital.created_by IS NOT NULL
                    AND creator.company_id IS DISTINCT FROM partner.company_id
                )
                OR NOT (
                    (
                        capital.voided_at IS NULL
                        AND capital.voided_by IS NULL
                        AND capital.void_reason IS NULL
                    ) OR (
                        capital.voided_at IS NOT NULL
                        AND capital.voided_by IS NOT NULL
                        AND capital.void_reason IS NOT NULL
                        AND length(trim(capital.void_reason)) >= 3
                        AND voider.company_id IS NOT DISTINCT FROM partner.company_id
                    )
                )
             ORDER BY capital.id
             LIMIT 1;
            IF bad_capital IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: capital entry % has '
                    'invalid type, amount, rail, actor, or void provenance',
                    bad_capital;
            END IF;

            SELECT collection.id
              INTO bad_manual_collection
              FROM manual_collections collection
              LEFT JOIN branches branch ON branch.id = collection.branch_id
              LEFT JOIN users creator ON creator.id = collection.created_by
              LEFT JOIN users voider ON voider.id = collection.voided_by
             WHERE branch.company_id IS DISTINCT FROM collection.company_id
                OR creator.company_id IS DISTINCT FROM collection.company_id
                OR collection.method NOT IN ('cash', 'upi', 'card', 'bank')
                OR collection.amount_minor <= 0
                OR collection.source_kind NOT IN ('manual_daily', 'legacy_daily')
                OR length(trim(collection.source_ref)) = 0
                OR length(trim(collection.idempotency_key)) = 0
                OR NOT (
                    (
                        collection.voided_at IS NULL
                        AND collection.voided_by IS NULL
                        AND collection.void_reason IS NULL
                    ) OR (
                        collection.voided_at IS NOT NULL
                        AND collection.voided_by IS NOT NULL
                        AND collection.void_reason IS NOT NULL
                        AND length(trim(collection.void_reason)) >= 3
                        AND voider.company_id IS NOT DISTINCT FROM
                            collection.company_id
                    )
                )
             ORDER BY collection.id
             LIMIT 1;
            IF bad_manual_collection IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: manual collection % '
                    'has invalid tenant, rail, amount, source, actor, or void provenance',
                    bad_manual_collection;
            END IF;

            SELECT payout.id
              INTO bad_tip_payout
              FROM tip_payouts payout
              LEFT JOIN branches branch ON branch.id = payout.branch_id
              LEFT JOIN users creator ON creator.id = payout.created_by
              LEFT JOIN users voider ON voider.id = payout.voided_by
             WHERE branch.company_id IS DISTINCT FROM payout.company_id
                OR creator.company_id IS DISTINCT FROM payout.company_id
                OR payout.method NOT IN ('cash', 'upi', 'card', 'bank')
                OR payout.amount_minor <= 0
                OR length(trim(payout.note)) < 3
                OR length(trim(payout.idempotency_key)) = 0
                OR NOT (
                    (
                        payout.voided_at IS NULL
                        AND payout.voided_by IS NULL
                        AND payout.void_reason IS NULL
                    ) OR (
                        payout.voided_at IS NOT NULL
                        AND payout.voided_by IS NOT NULL
                        AND payout.void_reason IS NOT NULL
                        AND length(trim(payout.void_reason)) >= 3
                        AND voider.company_id IS NOT DISTINCT FROM payout.company_id
                    )
                )
             ORDER BY payout.id
             LIMIT 1;
            IF bad_tip_payout IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: tip payout % has '
                    'invalid tenant, rail, amount, actor, note, or void provenance',
                    bad_tip_payout;
            END IF;

            SELECT grn.id
              INTO bad_grn
              FROM grns grn
              JOIN purchase_orders purchase_order
                ON purchase_order.id = grn.purchase_order_id
              LEFT JOIN users receiver ON receiver.id = grn.received_by
              LEFT JOIN journal_entries journal
                ON journal.id = grn.journal_entry_id
              LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS line_count,
                           COALESCE(SUM(ROUND(
                               line.qty_received * line.cost_per_unit_minor, 0
                           )), 0)::bigint AS receipt_total,
                           BOOL_OR(
                               line.qty_received <= 0
                               OR line.cost_per_unit_minor < 0
                               OR ingredient.company_id IS DISTINCT FROM
                                  purchase_order.company_id
                               OR batch.id IS NULL
                               OR batch.grn_id IS DISTINCT FROM grn.id
                               OR batch.ingredient_id IS DISTINCT FROM
                                  line.ingredient_id
                               OR batch.branch_id IS DISTINCT FROM
                                  purchase_order.branch_id
                               OR batch.qty_initial IS DISTINCT FROM
                                  line.qty_received
                               OR batch.cost_per_unit_minor IS DISTINCT FROM
                                  line.cost_per_unit_minor
                           ) AS invalid_line
                      FROM grn_lines line
                      LEFT JOIN ingredients ingredient
                        ON ingredient.id = line.ingredient_id
                      LEFT JOIN batches batch ON batch.id = line.batch_id
                     WHERE line.grn_id = grn.id
              ) totals ON TRUE
             WHERE grn.journal_entry_id IS NOT NULL
               AND (
                    totals.line_count = 0
                    OR totals.invalid_line IS TRUE
                    OR journal.id IS NULL
                    OR journal.company_id IS DISTINCT FROM purchase_order.company_id
                    OR journal.branch_id IS DISTINCT FROM purchase_order.branch_id
                    OR journal.ref_type IS DISTINCT FROM 'grn_receipt'
                    OR journal.ref_id IS DISTINCT FROM grn.id
                    OR journal.posted_at IS DISTINCT FROM grn.received_at
                    OR journal.total_minor IS DISTINCT FROM totals.receipt_total
                    OR journal.voided_at IS NOT NULL
                    OR journal.voided_by IS NOT NULL
                    OR journal.void_reason IS NOT NULL
                    OR (
                        grn.received_by IS NOT NULL
                        AND receiver.company_id IS DISTINCT FROM
                            purchase_order.company_id
                    )
                    OR (
                        grn.supplier_invoice_amount_minor IS NOT NULL
                        AND grn.supplier_invoice_amount_minor IS DISTINCT FROM
                            totals.receipt_total
                    )
               )
             ORDER BY grn.id
             LIMIT 1;
            IF bad_grn IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: posted GRN % has '
                    'invalid line, batch, tenant, actor, amount, or receipt-journal provenance',
                    bad_grn
                    USING HINT =
                        'Reconcile the inventory receipt and journal explicitly; '
                        'do not mutate or void posted stock receipt history.';
            END IF;

            SELECT journal.id
              INTO bad_grn_journal
              FROM journal_entries journal
             WHERE journal.ref_type = 'grn_receipt'
               AND NOT EXISTS (
                    SELECT 1
                      FROM grns grn
                      JOIN purchase_orders purchase_order
                        ON purchase_order.id = grn.purchase_order_id
                     WHERE grn.id = journal.ref_id
                       AND grn.journal_entry_id = journal.id
                       AND purchase_order.company_id = journal.company_id
                       AND purchase_order.branch_id IS NOT DISTINCT FROM
                           journal.branch_id
               )
             ORDER BY journal.id
             LIMIT 1;
            IF bad_grn_journal IS NOT NULL THEN
                RAISE EXCEPTION
                    'Cannot enforce finance source integrity: GRN receipt journal % '
                    'has no exact posted inventory receipt source',
                    bad_grn_journal;
            END IF;
        END
        $$;
        """
    )


def upgrade() -> None:
    _assert_existing_rows_are_safe()

    op.add_column(
        "expenses",
        sa.Column("voided_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "expenses",
        sa.Column("voided_by", sa.UUID(), nullable=True),
    )
    op.add_column(
        "expenses",
        sa.Column("void_reason", sa.String(length=500), nullable=True),
    )
    op.add_column(
        "expenses",
        sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
    )
    op.create_foreign_key(
        "fk_expense_voided_by_user",
        "expenses",
        "users",
        ["voided_by"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index("ix_expenses_voided_at", "expenses", ["voided_at"])
    op.create_index("ix_expenses_voided_by", "expenses", ["voided_by"])
    op.create_check_constraint(
        "ck_expense_positive_amount", "expenses", "amount_minor > 0"
    )
    op.create_check_constraint(
        "ck_expense_payment_method",
        "expenses",
        "paid_via IN ('cash', 'card', 'bank', 'upi')",
    )
    op.create_check_constraint(
        "ck_expense_void_state",
        "expenses",
        "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
        "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
        "AND length(trim(void_reason)) >= 3)",
    )
    op.create_check_constraint(
        "ck_expense_source_integrity_revision",
        "expenses",
        "source_integrity_revision IS NULL OR source_integrity_revision = 50",
    )
    op.execute(
        "ALTER TABLE expenses ALTER COLUMN source_integrity_revision SET DEFAULT 50"
    )

    op.add_column(
        "assets",
        sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
    )
    op.create_check_constraint(
        "ck_asset_positive_purchase", "assets", "purchase_minor > 0"
    )
    op.create_check_constraint(
        "ck_asset_salvage_range",
        "assets",
        "salvage_minor >= 0 AND salvage_minor <= purchase_minor",
    )
    op.create_check_constraint(
        "ck_asset_positive_useful_life", "assets", "useful_life_months > 0"
    )
    op.create_check_constraint(
        "ck_asset_supported_depreciation_method",
        "assets",
        "depreciation_method = 'straight_line'",
    )
    op.create_check_constraint(
        "ck_asset_source_integrity_revision",
        "assets",
        "source_integrity_revision IS NULL OR source_integrity_revision = 50",
    )
    op.execute(
        "ALTER TABLE assets ALTER COLUMN source_integrity_revision SET DEFAULT 50"
    )
    op.alter_column("assets", "depreciation_method", nullable=False)
    op.alter_column("assets", "useful_life_months", nullable=False)
    op.alter_column("assets", "salvage_minor", nullable=False)

    op.add_column(
        "partners",
        sa.Column("source_integrity_revision", sa.SmallInteger(), nullable=True),
    )
    op.create_check_constraint(
        "ck_partner_share_pct_range",
        "partners",
        "share_pct IS NOT NULL AND share_pct > 0 AND share_pct <= 100",
    )
    op.create_check_constraint(
        "ck_partner_name_present",
        "partners",
        "name IS NOT NULL AND length(trim(name)) > 0",
    )
    op.create_check_constraint(
        "ck_partner_source_integrity_revision",
        "partners",
        "source_integrity_revision IS NULL OR source_integrity_revision = 50",
    )
    op.execute(
        "ALTER TABLE partners ALTER COLUMN source_integrity_revision SET DEFAULT 50"
    )

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
                NEW.source_integrity_revision := 50;
                RETURN NEW;
            END IF;

            IF ROW(
                NEW.company_id, NEW.branch_id, NEW.category_id, NEW.supplier_id,
                NEW.ocr_extraction_id, NEW.amount_minor, NEW.paid_via,
                NEW.paid_at, NEW.vendor_name, NEW.invoice_no, NEW.note,
                NEW.created_at, NEW.deleted_at
            ) IS DISTINCT FROM ROW(
                OLD.company_id, OLD.branch_id, OLD.category_id, OLD.supplier_id,
                OLD.ocr_extraction_id, OLD.amount_minor, OLD.paid_via,
                OLD.paid_at, OLD.vendor_name, OLD.invoice_no, OLD.note,
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
                NEW.source_integrity_revision := 50;
            END IF;

            IF OLD.source_integrity_revision IS NOT NULL
               AND NEW.source_integrity_revision IS DISTINCT FROM
                   OLD.source_integrity_revision THEN
                RAISE EXCEPTION 'expense source revision is immutable';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_expenses_source_integrity
        BEFORE INSERT OR UPDATE ON expenses
        FOR EACH ROW EXECUTE FUNCTION validate_and_protect_expense_source();

        CREATE OR REPLACE FUNCTION reject_expense_delete()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION 'expenses are immutable; void the expense instead';
        END
        $$;

        CREATE TRIGGER trg_expenses_reject_delete
        BEFORE DELETE ON expenses
        FOR EACH ROW EXECUTE FUNCTION reject_expense_delete();
        """
    )

    op.execute(
        """
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

            SELECT COALESCE(SUM(amount_minor), 0)
              INTO active_paid
              FROM supplier_payments
             WHERE grn_id = NEW.grn_id
               AND voided_at IS NULL;
            IF receipt_total IS NULL
               OR active_paid + NEW.amount_minor > receipt_total THEN
                RAISE EXCEPTION
                    'supplier payment exceeds the authoritative GRN AP balance';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_supplier_payments_insert_scope
        BEFORE INSERT ON supplier_payments
        FOR EACH ROW EXECUTE FUNCTION validate_supplier_payment_insert_scope();

        CREATE OR REPLACE FUNCTION enforce_supplier_payment_journal_pair()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            payment_id uuid;
        BEGIN
            IF TG_TABLE_NAME = 'journal_entries' THEN
                IF NEW.ref_type IS DISTINCT FROM 'supplier_payment' THEN
                    RETURN NULL;
                END IF;
                payment_id := NEW.ref_id;
            ELSE
                payment_id := NEW.id;
            END IF;

            IF NOT EXISTS (
                SELECT 1
                  FROM supplier_payments payment
                  JOIN journal_entries journal
                    ON journal.id = payment.journal_entry_id
                 WHERE payment.id = payment_id
                   AND journal.company_id = payment.company_id
                   AND journal.branch_id IS NOT DISTINCT FROM payment.branch_id
                   AND journal.ref_type = 'supplier_payment'
                   AND journal.ref_id = payment.id
                   AND journal.total_minor = payment.amount_minor
                   AND journal.posted_at = payment.paid_at
                   AND ROW(
                       journal.voided_at, journal.voided_by, journal.void_reason
                   ) IS NOT DISTINCT FROM ROW(
                       payment.voided_at, payment.voided_by, payment.void_reason
                   )
            ) THEN
                RAISE EXCEPTION
                    'supplier payment and journal must remain an exact paired source';
            END IF;
            RETURN NULL;
        END
        $$;

        CREATE CONSTRAINT TRIGGER trg_supplier_payments_journal_pair
        AFTER INSERT OR UPDATE ON supplier_payments
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_supplier_payment_journal_pair();

        CREATE CONSTRAINT TRIGGER trg_supplier_payment_journals_pair
        AFTER INSERT OR UPDATE ON journal_entries
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_supplier_payment_journal_pair();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION protect_posted_grn_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                IF OLD.journal_entry_id IS NOT NULL THEN
                    RAISE EXCEPTION
                        'posted GRNs are immutable and cannot be deleted';
                END IF;
                RETURN OLD;
            END IF;

            IF OLD.journal_entry_id IS NOT NULL THEN
                IF (to_jsonb(NEW) - 'updated_at') IS DISTINCT FROM
                   (to_jsonb(OLD) - 'updated_at') THEN
                    RAISE EXCEPTION
                        'posted GRN financial/provenance fields are immutable';
                END IF;
                RETURN NEW;
            END IF;

            IF NEW.journal_entry_id IS NOT NULL
               AND (to_jsonb(NEW) - ARRAY['updated_at', 'journal_entry_id'])
                   IS DISTINCT FROM
                   (to_jsonb(OLD) - ARRAY['updated_at', 'journal_entry_id']) THEN
                RAISE EXCEPTION
                    'only the initial GRN receipt-journal link may be posted';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_grns_posted_source
        BEFORE UPDATE OR DELETE ON grns
        FOR EACH ROW EXECUTE FUNCTION protect_posted_grn_source();

        CREATE OR REPLACE FUNCTION protect_posted_grn_line_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_is_posted boolean;
            source_created_at timestamptz;
        BEGIN
            IF TG_OP = 'INSERT' THEN
                SELECT journal_entry_id IS NOT NULL, created_at
                  INTO source_is_posted, source_created_at
                  FROM grns
                 WHERE id = NEW.grn_id;
                IF source_is_posted
                   AND source_created_at IS DISTINCT FROM transaction_timestamp() THEN
                    RAISE EXCEPTION
                        'posted GRN lines are immutable after receipt posting';
                END IF;
                RETURN NEW;
            END IF;

            SELECT COALESCE(BOOL_OR(journal_entry_id IS NOT NULL), false)
              INTO source_is_posted
              FROM grns
             WHERE id IN (OLD.grn_id, COALESCE(NEW.grn_id, OLD.grn_id));
            IF source_is_posted THEN
                RAISE EXCEPTION
                    'posted GRN lines are immutable after receipt posting';
            END IF;
            RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
        END
        $$;

        CREATE TRIGGER trg_grn_lines_posted_source
        BEFORE INSERT OR UPDATE OR DELETE ON grn_lines
        FOR EACH ROW EXECUTE FUNCTION protect_posted_grn_line_source();

        CREATE OR REPLACE FUNCTION enforce_grn_receipt_journal_pair()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_id uuid;
            source_journal_id uuid;
        BEGIN
            IF TG_TABLE_NAME = 'journal_entries' THEN
                IF NEW.ref_type IS DISTINCT FROM 'grn_receipt' THEN
                    RETURN NULL;
                END IF;
                source_id := NEW.ref_id;
            ELSIF TG_TABLE_NAME = 'grn_lines' THEN
                source_id := COALESCE(NEW.grn_id, OLD.grn_id);
            ELSE
                source_id := COALESCE(NEW.id, OLD.id);
            END IF;

            SELECT journal_entry_id INTO source_journal_id
              FROM grns WHERE id = source_id;
            IF NOT FOUND THEN
                IF EXISTS (
                    SELECT 1 FROM journal_entries
                     WHERE ref_type = 'grn_receipt' AND ref_id = source_id
                ) THEN
                    RAISE EXCEPTION
                        'GRN receipt journal has no exact posted inventory source';
                END IF;
                RETURN NULL;
            END IF;

            IF source_journal_id IS NULL THEN
                IF EXISTS (
                    SELECT 1 FROM journal_entries
                     WHERE ref_type = 'grn_receipt' AND ref_id = source_id
                ) THEN
                    RAISE EXCEPTION
                        'GRN receipt journal is not linked to its inventory source';
                END IF;
                RETURN NULL;
            END IF;

            IF NOT EXISTS (
                SELECT 1
                  FROM grns grn
                  JOIN purchase_orders purchase_order
                    ON purchase_order.id = grn.purchase_order_id
                  JOIN journal_entries journal
                    ON journal.id = grn.journal_entry_id
                  JOIN LATERAL (
                        SELECT COUNT(*) AS line_count,
                               COALESCE(SUM(ROUND(
                                   line.qty_received * line.cost_per_unit_minor, 0
                               )), 0)::bigint AS receipt_total,
                               BOOL_OR(
                                   line.qty_received <= 0
                                   OR line.cost_per_unit_minor < 0
                                   OR ingredient.company_id IS DISTINCT FROM
                                      purchase_order.company_id
                                   OR batch.id IS NULL
                                   OR batch.grn_id IS DISTINCT FROM grn.id
                                   OR batch.ingredient_id IS DISTINCT FROM
                                      line.ingredient_id
                                   OR batch.branch_id IS DISTINCT FROM
                                      purchase_order.branch_id
                                   OR batch.qty_initial IS DISTINCT FROM
                                      line.qty_received
                                   OR batch.cost_per_unit_minor IS DISTINCT FROM
                                      line.cost_per_unit_minor
                               ) AS invalid_line
                          FROM grn_lines line
                          LEFT JOIN ingredients ingredient
                            ON ingredient.id = line.ingredient_id
                          LEFT JOIN batches batch ON batch.id = line.batch_id
                         WHERE line.grn_id = grn.id
                  ) totals ON TRUE
                 WHERE grn.id = source_id
                   AND totals.line_count > 0
                   AND totals.invalid_line IS NOT TRUE
                   AND journal.company_id = purchase_order.company_id
                   AND journal.branch_id IS NOT DISTINCT FROM
                       purchase_order.branch_id
                   AND journal.ref_type = 'grn_receipt'
                   AND journal.ref_id = grn.id
                   AND journal.posted_at = grn.received_at
                   AND journal.total_minor = totals.receipt_total
                   AND journal.voided_at IS NULL
                   AND journal.voided_by IS NULL
                   AND journal.void_reason IS NULL
                   AND (
                       grn.supplier_invoice_amount_minor IS NULL
                       OR grn.supplier_invoice_amount_minor = totals.receipt_total
                   )
            ) THEN
                RAISE EXCEPTION
                    'posted GRN and receipt journal must remain an exact, '
                    'active inventory source pair';
            END IF;
            RETURN NULL;
        END
        $$;

        CREATE CONSTRAINT TRIGGER trg_grns_receipt_pair
        AFTER INSERT OR UPDATE ON grns
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_grn_receipt_journal_pair();

        CREATE CONSTRAINT TRIGGER trg_grn_lines_receipt_pair
        AFTER INSERT OR UPDATE OR DELETE ON grn_lines
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_grn_receipt_journal_pair();

        CREATE CONSTRAINT TRIGGER trg_grn_journals_receipt_pair
        AFTER INSERT OR UPDATE ON journal_entries
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_grn_receipt_journal_pair();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_and_protect_asset_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            branch_company uuid;
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'assets are immutable and cannot be deleted';
            END IF;
            IF TG_OP = 'UPDATE' THEN
                RAISE EXCEPTION 'asset financial/provenance fields are immutable';
            END IF;
            SELECT company_id INTO branch_company
              FROM branches WHERE id = NEW.branch_id;
            IF branch_company IS DISTINCT FROM NEW.company_id THEN
                RAISE EXCEPTION 'asset branch must belong to its company';
            END IF;
            IF NEW.deleted_at IS NOT NULL THEN
                RAISE EXCEPTION 'new assets cannot begin deleted';
            END IF;
            NEW.source_integrity_revision := 50;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_assets_source_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON assets
        FOR EACH ROW EXECUTE FUNCTION validate_and_protect_asset_source();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_and_protect_partner_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            linked_user_company uuid;
            existing_share_total numeric;
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION
                    'partner ownership records are immutable and cannot be deleted';
            END IF;

            IF NEW.user_id IS NOT NULL THEN
                SELECT company_id INTO linked_user_company
                  FROM users WHERE id = NEW.user_id;
                IF linked_user_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION
                        'partner user link must belong to the same company';
                END IF;
            END IF;

            IF TG_OP = 'INSERT' THEN
                -- Serialize every ownership insert for a company so two
                -- concurrent writers cannot each pass a stale <=100 check.
                PERFORM 1 FROM companies
                 WHERE id = NEW.company_id
                 FOR UPDATE;
                IF NOT FOUND THEN
                    RAISE EXCEPTION 'partner company does not exist';
                END IF;
                SELECT COALESCE(SUM(share_pct), 0)
                  INTO existing_share_total
                  FROM partners
                 WHERE company_id = NEW.company_id
                   AND id IS DISTINCT FROM NEW.id;
                IF existing_share_total + NEW.share_pct > 100 THEN
                    RAISE EXCEPTION
                        'partner ownership shares cannot exceed 100 percent';
                END IF;
                NEW.source_integrity_revision := 50;
                RETURN NEW;
            END IF;

            IF ROW(
                NEW.company_id, NEW.user_id, NEW.name, NEW.share_pct,
                NEW.joined_at, NEW.created_at
            ) IS DISTINCT FROM ROW(
                OLD.company_id, OLD.user_id, OLD.name, OLD.share_pct,
                OLD.joined_at, OLD.created_at
            ) THEN
                RAISE EXCEPTION
                    'partner financial identity/share is immutable; owner '
                    'reconciliation is required';
            END IF;
            IF OLD.source_integrity_revision IS NOT NULL
               AND NEW.source_integrity_revision IS DISTINCT FROM
                   OLD.source_integrity_revision THEN
                RAISE EXCEPTION 'partner source revision is immutable';
            END IF;
            NEW.source_integrity_revision := 50;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_partners_source_integrity
        BEFORE INSERT OR UPDATE OR DELETE ON partners
        FOR EACH ROW EXECUTE FUNCTION validate_and_protect_partner_source();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION protect_expense_category_identity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            account_company uuid;
            account_type text;
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'expense categories cannot be deleted';
            END IF;
            IF TG_OP = 'UPDATE'
               AND ROW(NEW.company_id, NEW.name, NEW.code, NEW.gl_account_id,
                       NEW.created_at)
                   IS DISTINCT FROM
                   ROW(OLD.company_id, OLD.name, OLD.code, OLD.gl_account_id,
                       OLD.created_at) THEN
                RAISE EXCEPTION 'expense category accounting identity is immutable';
            END IF;
            IF NEW.gl_account_id IS NOT NULL THEN
                SELECT company_id, type INTO account_company, account_type
                  FROM accounts WHERE id = NEW.gl_account_id;
                IF account_company IS DISTINCT FROM NEW.company_id
                   OR account_type IS DISTINCT FROM 'expense' THEN
                    RAISE EXCEPTION
                        'expense category account must be a same-company expense account';
                END IF;
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_expense_categories_identity
        BEFORE INSERT OR UPDATE OR DELETE ON expense_categories
        FOR EACH ROW EXECUTE FUNCTION protect_expense_category_identity();

        CREATE OR REPLACE FUNCTION protect_account_identity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            parent_company uuid;
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'accounts are financial reference data and cannot be deleted';
            END IF;
            IF NEW.type NOT IN ('asset', 'liability', 'equity', 'revenue', 'expense')
               OR NEW.normal_side NOT IN ('dr', 'cr')
               OR length(trim(NEW.code)) = 0
               OR length(trim(NEW.name)) = 0 THEN
                RAISE EXCEPTION 'account identity, type, and normal side are invalid';
            END IF;
            IF NEW.parent_id IS NOT NULL THEN
                SELECT company_id INTO parent_company
                  FROM accounts WHERE id = NEW.parent_id;
                IF parent_company IS DISTINCT FROM NEW.company_id THEN
                    RAISE EXCEPTION
                        'account parent must belong to the same company';
                END IF;
            END IF;
            IF TG_OP = 'INSERT' THEN
                RETURN NEW;
            END IF;
            IF ROW(NEW.company_id, NEW.code, NEW.name, NEW.type,
                   NEW.normal_side, NEW.created_at)
               IS DISTINCT FROM
               ROW(OLD.company_id, OLD.code, OLD.name, OLD.type,
                   OLD.normal_side, OLD.created_at) THEN
                RAISE EXCEPTION 'account financial identity is immutable';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_accounts_identity
        BEFORE INSERT OR UPDATE OR DELETE ON accounts
        FOR EACH ROW EXECUTE FUNCTION protect_account_identity();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION validate_finance_source_insert_scope()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_company uuid;
            branch_company uuid;
            creator_company uuid;
            voider_company uuid;
        BEGIN
            IF TG_TABLE_NAME = 'capital_entries' THEN
                SELECT partner.company_id INTO source_company
                  FROM partners partner WHERE partner.id = NEW.partner_id;
                IF NEW.created_by IS NOT NULL THEN
                    SELECT company_id INTO creator_company
                      FROM users WHERE id = NEW.created_by;
                END IF;
                IF NEW.voided_by IS NOT NULL THEN
                    SELECT company_id INTO voider_company
                      FROM users WHERE id = NEW.voided_by;
                END IF;
                IF source_company IS NULL
                   OR (
                       NEW.created_by IS NOT NULL
                       AND creator_company IS DISTINCT FROM source_company
                   )
                   OR (
                       NEW.voided_by IS NOT NULL
                       AND voider_company IS DISTINCT FROM source_company
                   )
                   OR NEW.type NOT IN ('invest', 'withdraw')
                   OR NEW.amount_minor <= 0
                   OR NEW.settlement_account NOT IN (
                       'cash', 'bank', 'upi', 'historical_funds'
                   ) THEN
                    RAISE EXCEPTION
                        'capital entry has invalid tenant, actor, type, amount, or rail';
                END IF;
                RETURN NEW;
            END IF;

            SELECT company_id INTO branch_company
              FROM branches WHERE id = NEW.branch_id;
            SELECT company_id INTO creator_company
              FROM users WHERE id = NEW.created_by;
            IF NEW.voided_by IS NOT NULL THEN
                SELECT company_id INTO voider_company
                  FROM users WHERE id = NEW.voided_by;
            END IF;
            IF branch_company IS DISTINCT FROM NEW.company_id
               OR creator_company IS DISTINCT FROM NEW.company_id
               OR (
                   NEW.voided_by IS NOT NULL
                   AND voider_company IS DISTINCT FROM NEW.company_id
               )
               OR NEW.method NOT IN ('cash', 'upi', 'card', 'bank')
               OR NEW.amount_minor <= 0 THEN
                RAISE EXCEPTION
                    '% has invalid tenant, actor, rail, or amount', TG_TABLE_NAME;
            END IF;
            IF TG_TABLE_NAME = 'manual_collections'
               AND (
                   NEW.source_kind NOT IN ('manual_daily', 'legacy_daily')
                   OR length(trim(NEW.source_ref)) = 0
                   OR length(trim(NEW.idempotency_key)) = 0
               ) THEN
                RAISE EXCEPTION 'manual collection source identity is invalid';
            END IF;
            IF TG_TABLE_NAME = 'tip_payouts'
               AND (
                   length(trim(NEW.note)) < 3
                   OR length(trim(NEW.idempotency_key)) = 0
               ) THEN
                RAISE EXCEPTION 'tip payout source identity is invalid';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_capital_entries_insert_scope
        BEFORE INSERT ON capital_entries
        FOR EACH ROW EXECUTE FUNCTION validate_finance_source_insert_scope();
        CREATE TRIGGER trg_manual_collections_insert_scope
        BEFORE INSERT ON manual_collections
        FOR EACH ROW EXECUTE FUNCTION validate_finance_source_insert_scope();
        CREATE TRIGGER trg_tip_payouts_insert_scope
        BEFORE INSERT ON tip_payouts
        FOR EACH ROW EXECUTE FUNCTION validate_finance_source_insert_scope();
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION protect_voidable_finance_source()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION '% rows are immutable and cannot be deleted', TG_TABLE_NAME;
            END IF;
            IF (to_jsonb(NEW) - ARRAY[
                    'updated_at', 'voided_at', 'voided_by', 'void_reason'
                ]) IS DISTINCT FROM
               (to_jsonb(OLD) - ARRAY[
                    'updated_at', 'voided_at', 'voided_by', 'void_reason'
                ]) THEN
                RAISE EXCEPTION '% financial/provenance fields are immutable',
                    TG_TABLE_NAME;
            END IF;
            IF OLD.voided_at IS NOT NULL THEN
                IF ROW(NEW.voided_at, NEW.voided_by, NEW.void_reason)
                   IS DISTINCT FROM
                   ROW(OLD.voided_at, OLD.voided_by, OLD.void_reason) THEN
                    RAISE EXCEPTION '% void cannot be changed or reversed',
                        TG_TABLE_NAME;
                END IF;
            ELSIF NEW.voided_at IS NOT NULL
                  OR NEW.voided_by IS NOT NULL
                  OR NEW.void_reason IS NOT NULL THEN
                IF NEW.voided_at IS NULL
                   OR NEW.voided_by IS NULL
                   OR NEW.void_reason IS NULL
                   OR length(trim(NEW.void_reason)) < 3 THEN
                    RAISE EXCEPTION '% void must be populated atomically',
                        TG_TABLE_NAME;
                END IF;
            END IF;
            RETURN NEW;
        END
        $$;
        """
    )
    for table_name in (
        "capital_entries",
        "manual_collections",
        "tip_payouts",
        "journal_entries",
        "supplier_payments",
    ):
        op.execute(
            f"CREATE TRIGGER trg_{table_name}_append_only "  # noqa: S608
            f"BEFORE UPDATE OR DELETE ON {table_name} "
            "FOR EACH ROW EXECUTE FUNCTION protect_voidable_finance_source()"
        )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION reject_journal_line_mutation()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION 'posted journal lines are immutable';
        END
        $$;

        CREATE TRIGGER trg_journal_lines_append_only
        BEFORE UPDATE OR DELETE ON journal_lines
        FOR EACH ROW EXECUTE FUNCTION reject_journal_line_mutation();

        CREATE OR REPLACE FUNCTION enforce_posted_journal_integrity()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            entry_id uuid;
        BEGIN
            IF TG_TABLE_NAME = 'journal_lines' THEN
                entry_id := COALESCE(NEW.journal_entry_id, OLD.journal_entry_id);
            ELSE
                entry_id := COALESCE(NEW.id, OLD.id);
            END IF;

            IF NOT EXISTS (SELECT 1 FROM journal_entries WHERE id = entry_id) THEN
                RETURN NULL;
            END IF;
            IF NOT EXISTS (
                SELECT 1
                  FROM journal_entries journal
                  LEFT JOIN branches branch ON branch.id = journal.branch_id
                  LEFT JOIN users voider ON voider.id = journal.voided_by
                  JOIN LATERAL (
                        SELECT COUNT(*) AS line_count,
                               COALESCE(SUM(line.amount_minor)
                                   FILTER (WHERE line.side = 'dr'), 0) AS debit_total,
                               COALESCE(SUM(line.amount_minor)
                                   FILTER (WHERE line.side = 'cr'), 0) AS credit_total,
                               BOOL_OR(
                                   account.id IS NULL
                                   OR account.company_id IS DISTINCT FROM
                                      journal.company_id
                                   OR line.side NOT IN ('dr', 'cr')
                                   OR line.amount_minor <= 0
                               ) AS invalid_line
                          FROM journal_lines line
                          LEFT JOIN accounts account ON account.id = line.account_id
                         WHERE line.journal_entry_id = journal.id
                  ) totals ON TRUE
                 WHERE journal.id = entry_id
                   AND journal.total_minor > 0
                   AND length(trim(journal.ref_type)) > 0
                   AND (
                       journal.branch_id IS NULL
                       OR branch.company_id = journal.company_id
                   )
                   AND totals.line_count >= 2
                   AND totals.invalid_line IS NOT TRUE
                   AND totals.debit_total = journal.total_minor
                   AND totals.credit_total = journal.total_minor
                   AND (
                       (
                           journal.voided_at IS NULL
                           AND journal.voided_by IS NULL
                           AND journal.void_reason IS NULL
                       ) OR (
                           journal.voided_at IS NOT NULL
                           AND journal.voided_by IS NOT NULL
                           AND journal.void_reason IS NOT NULL
                           AND length(trim(journal.void_reason)) >= 3
                           AND voider.company_id = journal.company_id
                       )
                   )
            ) THEN
                RAISE EXCEPTION
                    'posted journal must remain balanced with same-company '
                    'accounts, branch, and void provenance';
            END IF;
            RETURN NULL;
        END
        $$;

        CREATE CONSTRAINT TRIGGER trg_journal_entries_balanced_source
        AFTER INSERT OR UPDATE ON journal_entries
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_posted_journal_integrity();
        CREATE CONSTRAINT TRIGGER trg_journal_lines_balanced_source
        AFTER INSERT OR UPDATE OR DELETE ON journal_lines
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION enforce_posted_journal_integrity();
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1 FROM expenses WHERE source_integrity_revision = 50
            ) OR EXISTS (
                SELECT 1 FROM assets WHERE source_integrity_revision = 50
            ) OR EXISTS (
                SELECT 1 FROM partners WHERE source_integrity_revision = 50
            ) OR EXISTS (
                SELECT 1 FROM supplier_payments
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0050 after forward finance source activity'
                    USING HINT =
                        'Preserve immutable finance and supplier-settlement history; '
                        'restore the '
                        'application at revision 0050 or later.';
            END IF;
        END
        $$;
        """
    )

    op.execute("DROP TRIGGER trg_journal_lines_append_only ON journal_lines")
    op.execute("DROP FUNCTION reject_journal_line_mutation()")
    op.execute("DROP TRIGGER trg_journal_lines_balanced_source ON journal_lines")
    op.execute(
        "DROP TRIGGER trg_journal_entries_balanced_source ON journal_entries"
    )
    op.execute("DROP FUNCTION enforce_posted_journal_integrity()")
    op.execute("DROP TRIGGER trg_grn_journals_receipt_pair ON journal_entries")
    op.execute("DROP TRIGGER trg_grn_lines_receipt_pair ON grn_lines")
    op.execute("DROP TRIGGER trg_grns_receipt_pair ON grns")
    op.execute("DROP FUNCTION enforce_grn_receipt_journal_pair()")
    op.execute("DROP TRIGGER trg_grn_lines_posted_source ON grn_lines")
    op.execute("DROP FUNCTION protect_posted_grn_line_source()")
    op.execute("DROP TRIGGER trg_grns_posted_source ON grns")
    op.execute("DROP FUNCTION protect_posted_grn_source()")
    op.execute(
        "DROP TRIGGER trg_supplier_payment_journals_pair ON journal_entries"
    )
    op.execute(
        "DROP TRIGGER trg_supplier_payments_journal_pair ON supplier_payments"
    )
    op.execute("DROP FUNCTION enforce_supplier_payment_journal_pair()")
    op.execute(
        "DROP TRIGGER trg_supplier_payments_insert_scope ON supplier_payments"
    )
    op.execute("DROP FUNCTION validate_supplier_payment_insert_scope()")
    for table_name in reversed(
        (
            "capital_entries",
            "manual_collections",
            "tip_payouts",
            "journal_entries",
            "supplier_payments",
        )
    ):
        op.execute(
            f"DROP TRIGGER trg_{table_name}_append_only ON {table_name}"  # noqa: S608
        )
    op.execute("DROP FUNCTION protect_voidable_finance_source()")

    op.execute("DROP TRIGGER trg_accounts_identity ON accounts")
    op.execute("DROP FUNCTION protect_account_identity()")
    op.execute("DROP TRIGGER trg_tip_payouts_insert_scope ON tip_payouts")
    op.execute(
        "DROP TRIGGER trg_manual_collections_insert_scope ON manual_collections"
    )
    op.execute("DROP TRIGGER trg_capital_entries_insert_scope ON capital_entries")
    op.execute("DROP FUNCTION validate_finance_source_insert_scope()")
    op.execute(
        "DROP TRIGGER trg_expense_categories_identity ON expense_categories"
    )
    op.execute("DROP FUNCTION protect_expense_category_identity()")
    op.execute("DROP TRIGGER trg_partners_source_integrity ON partners")
    op.execute("DROP FUNCTION validate_and_protect_partner_source()")
    op.execute("DROP TRIGGER trg_assets_source_integrity ON assets")
    op.execute("DROP FUNCTION validate_and_protect_asset_source()")
    op.execute("DROP TRIGGER trg_expenses_reject_delete ON expenses")
    op.execute("DROP FUNCTION reject_expense_delete()")
    op.execute("DROP TRIGGER trg_expenses_source_integrity ON expenses")
    op.execute("DROP FUNCTION validate_and_protect_expense_source()")

    op.execute(
        "ALTER TABLE partners ALTER COLUMN source_integrity_revision DROP DEFAULT"
    )
    op.drop_constraint(
        "ck_partner_source_integrity_revision", "partners", type_="check"
    )
    op.drop_constraint("ck_partner_name_present", "partners", type_="check")
    op.drop_constraint("ck_partner_share_pct_range", "partners", type_="check")
    op.drop_column("partners", "source_integrity_revision")

    op.alter_column("assets", "salvage_minor", nullable=True)
    op.alter_column("assets", "useful_life_months", nullable=True)
    op.alter_column("assets", "depreciation_method", nullable=True)
    op.execute("ALTER TABLE assets ALTER COLUMN source_integrity_revision DROP DEFAULT")
    op.drop_constraint("ck_asset_source_integrity_revision", "assets", type_="check")
    op.drop_constraint(
        "ck_asset_supported_depreciation_method", "assets", type_="check"
    )
    op.drop_constraint("ck_asset_positive_useful_life", "assets", type_="check")
    op.drop_constraint("ck_asset_salvage_range", "assets", type_="check")
    op.drop_constraint("ck_asset_positive_purchase", "assets", type_="check")
    op.drop_column("assets", "source_integrity_revision")

    op.execute(
        "ALTER TABLE expenses ALTER COLUMN source_integrity_revision DROP DEFAULT"
    )
    op.drop_constraint("ck_expense_source_integrity_revision", "expenses", type_="check")
    op.drop_constraint("ck_expense_void_state", "expenses", type_="check")
    op.drop_constraint("ck_expense_payment_method", "expenses", type_="check")
    op.drop_constraint("ck_expense_positive_amount", "expenses", type_="check")
    op.drop_index("ix_expenses_voided_by", table_name="expenses")
    op.drop_index("ix_expenses_voided_at", table_name="expenses")
    op.drop_constraint("fk_expense_voided_by_user", "expenses", type_="foreignkey")
    op.drop_column("expenses", "source_integrity_revision")
    op.drop_column("expenses", "void_reason")
    op.drop_column("expenses", "voided_by")
    op.drop_column("expenses", "voided_at")
