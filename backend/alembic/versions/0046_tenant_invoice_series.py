"""Make fiscal invoice series explicit and tenant-scoped.

Revision ID: 0046
Revises: 0045
Create Date: 2026-08-27

The old formatter silently truncated ``branches.code`` to two characters
while counters were maintained per branch and ``orders.invoice_no`` was
globally unique. Two branches (or two unrelated tenants) could therefore
allocate the same visible number and fail payment at flush/commit time.

This migration derives the *existing* formatter output only when that mapping
is unambiguous, then freezes it into an explicit two-character series code.
It refuses short/ambiguous/changed legacy evidence instead of inventing a new
fiscal identity. Invoice-number uniqueness becomes tenant-scoped, matching
the legal issuer boundary.
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0046"
down_revision = "0045"
branch_labels = None
depends_on = None


_LEGACY_SERIES_SQL = """
LEFT(
    COALESCE(
        NULLIF(REGEXP_REPLACE(UPPER(COALESCE(code, '')), '[^A-Z0-9]', '', 'g'), ''),
        'MN'
    ),
    2
)
"""


def upgrade() -> None:
    op.add_column(
        "branches",
        sa.Column("invoice_series_code", sa.String(length=2)),
    )

    op.execute(
        f"""
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM branches
                 WHERE CHAR_LENGTH({_LEGACY_SERIES_SQL}) <> 2
            ) THEN
                RAISE EXCEPTION
                    'Cannot assign invoice series: legacy branch code is not two characters'
                    USING HINT =
                        'Set each branch code to at least two alphanumeric characters, '
                        'preserve its issued invoice prefix, then retry.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM branches
                 GROUP BY company_id, {_LEGACY_SERIES_SQL}
                HAVING COUNT(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'Cannot assign invoice series: branch prefixes collide within a company'
                    USING HINT =
                        'Give each branch a distinct two-character prefix without '
                        'rewriting issued invoices, then retry.';
            END IF;

            IF EXISTS (
                WITH fiscal_documents AS (
                    SELECT company_id, branch_id, invoice_no AS document_no
                      FROM orders
                     WHERE invoice_no IS NOT NULL
                    UNION ALL
                    SELECT company_id, branch_id, receipt_no
                      FROM refunds
                     WHERE receipt_no IS NOT NULL
                    UNION ALL
                    SELECT company_id, branch_id, receipt_no
                      FROM membership_payments
                     WHERE receipt_no IS NOT NULL
                    UNION ALL
                    SELECT company_id, branch_id, receipt_no
                      FROM membership_refund_settlements
                     WHERE receipt_no IS NOT NULL
                )
                SELECT 1
                  FROM fiscal_documents document
                  LEFT JOIN branches branch ON branch.id = document.branch_id
                 WHERE branch.id IS NULL
                    OR document.company_id IS DISTINCT FROM branch.company_id
                    OR SPLIT_PART(document.document_no, '/', 2) <> LEFT(
                        COALESCE(
                            NULLIF(
                                REGEXP_REPLACE(
                                    UPPER(COALESCE(branch.code, '')),
                                    '[^A-Z0-9]',
                                    '',
                                    'g'
                                ),
                                ''
                            ),
                            'MN'
                        ),
                        2
                    )
            ) THEN
                RAISE EXCEPTION
                    'Cannot assign invoice series: fiscal history disagrees with branch identity'
                    USING HINT =
                        'Reconcile branch scope and code to every historical receipt prefix; '
                        'do not renumber issued documents.';
            END IF;
        END
        $$;
        """  # noqa: S608 -- interpolates only the static migration SQL above
    )

    op.execute(
        f"""
        UPDATE branches
           SET invoice_series_code = {_LEGACY_SERIES_SQL}
        """  # noqa: S608 -- interpolates only the static migration SQL above
    )
    op.alter_column(
        "branches",
        "invoice_series_code",
        existing_type=sa.String(length=2),
        nullable=False,
    )
    op.create_check_constraint(
        "ck_branch_invoice_series_code_format",
        "branches",
        "invoice_series_code ~ '^[A-Z0-9]{2}$'",
    )
    op.create_unique_constraint(
        "uq_branch_invoice_series_per_company",
        "branches",
        ["company_id", "invoice_series_code"],
    )
    op.execute(
        """
        CREATE FUNCTION prevent_used_branch_invoice_series_change()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF NEW.invoice_series_code IS DISTINCT FROM OLD.invoice_series_code
               AND (
                    EXISTS (
                        SELECT 1
                          FROM in_invoice_counters counter
                         WHERE counter.branch_id = OLD.id
                    )
                    OR EXISTS (
                        SELECT 1
                          FROM orders document
                         WHERE document.branch_id = OLD.id
                           AND document.invoice_no IS NOT NULL
                    )
                    OR EXISTS (
                        SELECT 1
                          FROM refunds document
                         WHERE document.branch_id = OLD.id
                           AND document.receipt_no IS NOT NULL
                    )
                    OR EXISTS (
                        SELECT 1
                          FROM membership_payments document
                         WHERE document.branch_id = OLD.id
                           AND document.receipt_no IS NOT NULL
                    )
                    OR EXISTS (
                        SELECT 1
                          FROM membership_refund_settlements document
                         WHERE document.branch_id = OLD.id
                           AND document.receipt_no IS NOT NULL
                    )
               )
            THEN
                RAISE EXCEPTION
                    'invoice series cannot change after fiscal document history exists'
                    USING ERRCODE = '23514',
                          HINT =
                              'Keep the existing series so receipt numbering '
                              'remains continuous.';
            END IF;
            RETURN NEW;
        END
        $$;

        CREATE TRIGGER trg_prevent_used_branch_invoice_series_change
        BEFORE UPDATE OF invoice_series_code ON branches
        FOR EACH ROW
        EXECUTE FUNCTION prevent_used_branch_invoice_series_change();
        """
    )

    op.drop_constraint("uq_orders_invoice_no", "orders", type_="unique")
    op.create_unique_constraint(
        "uq_orders_company_invoice_no",
        "orders",
        ["company_id", "invoice_no"],
    )


def downgrade() -> None:
    # The old schema cannot represent tenant-local duplicate invoice strings,
    # nor can it preserve a series that intentionally differs from the mutable
    # display code. Refuse history loss rather than guessing during rollback.
    op.execute(
        f"""
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM orders
                 WHERE invoice_no IS NOT NULL
                 GROUP BY invoice_no
                HAVING COUNT(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade invoice namespace: duplicate numbers now exist across tenants'
                    USING HINT =
                        'Keep revision 0046 or later; do not merge legal-entity invoice histories.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM branches
                 WHERE invoice_series_code <> {_LEGACY_SERIES_SQL}
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade: explicit invoice series differs from branch code'
                    USING HINT =
                        'Keep revision 0046 or later so documents retain their explicit series.';
            END IF;
        END
        $$;
        """  # noqa: S608 -- interpolates only the static migration SQL above
    )

    op.drop_constraint("uq_orders_company_invoice_no", "orders", type_="unique")
    op.create_unique_constraint("uq_orders_invoice_no", "orders", ["invoice_no"])

    op.drop_constraint(
        "uq_branch_invoice_series_per_company",
        "branches",
        type_="unique",
    )
    op.execute(
        "DROP TRIGGER trg_prevent_used_branch_invoice_series_change ON branches"
    )
    op.execute("DROP FUNCTION prevent_used_branch_invoice_series_change()")
    op.drop_constraint(
        "ck_branch_invoice_series_code_format",
        "branches",
        type_="check",
    )
    op.drop_column("branches", "invoice_series_code")
