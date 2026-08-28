"""Make fiscal invoice series explicit and tenant-scoped.

Revision ID: 0046
Revises: 0045
Create Date: 2026-08-27

The old formatter silently truncated ``branches.code`` to two characters
while counters were maintained per branch and ``orders.invoice_no`` was
globally unique. Two branches (or two unrelated tenants) could therefore
allocate the same visible number and fail payment at flush/commit time.

This migration freezes the existing formatter output for every active branch
and every branch with a counter or numbered fiscal document. Automatic repair
is deliberately limited to history-free, soft-deleted tombstones: retained
codes prefer fiscal/active owners, while eligible tombstones receive an unused
deterministic two-character code. Invoice-number uniqueness becomes
tenant-scoped, matching the legal issuer boundary.
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

    # Freeze the exact branch/counter/document snapshot while the plan is built.
    # Without these locks, a checkout could allocate a counter or document after
    # classification but before the branch receives its explicit series.
    op.execute(
        """
        LOCK TABLE branches, in_invoice_counters, orders, refunds,
                   membership_payments, membership_refund_settlements
        IN SHARE ROW EXCLUSIVE MODE
        """
    )

    op.execute(
        f"""
        CREATE TEMPORARY TABLE migration_0046_branch_facts ON COMMIT DROP AS
        SELECT branch.id AS branch_id,
               branch.company_id,
               branch.name,
               branch.deleted_at,
               branch.created_at,
               {_LEGACY_SERIES_SQL} AS legacy_series,
               (
                   EXISTS (
                       SELECT 1
                         FROM in_invoice_counters counter
                        WHERE counter.branch_id = branch.id
                   )
                   OR EXISTS (
                       SELECT 1
                         FROM orders document
                        WHERE document.branch_id = branch.id
                          AND document.invoice_no IS NOT NULL
                   )
                   OR EXISTS (
                       SELECT 1
                         FROM refunds document
                        WHERE document.branch_id = branch.id
                          AND document.receipt_no IS NOT NULL
                   )
                   OR EXISTS (
                       SELECT 1
                         FROM membership_payments document
                        WHERE document.branch_id = branch.id
                          AND document.receipt_no IS NOT NULL
                   )
                   OR EXISTS (
                       SELECT 1
                         FROM membership_refund_settlements document
                        WHERE document.branch_id = branch.id
                          AND document.receipt_no IS NOT NULL
                   )
               ) AS has_fiscal_history
          FROM branches branch;

        CREATE UNIQUE INDEX migration_0046_branch_facts_pk
            ON migration_0046_branch_facts (branch_id);
        """  # noqa: S608 -- interpolates only the static migration SQL above
    )

    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM migration_0046_branch_facts
                 WHERE (has_fiscal_history OR deleted_at IS NULL)
                   AND CHAR_LENGTH(legacy_series) <> 2
            ) THEN
                RAISE EXCEPTION
                    'Cannot assign invoice series: legacy branch code is not two characters'
                    USING HINT =
                        'Preserve the active/fiscal identity and reconcile the branch '
                        'code before retrying.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM migration_0046_branch_facts
                 WHERE has_fiscal_history OR deleted_at IS NULL
                 GROUP BY company_id, legacy_series
                HAVING COUNT(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'Cannot assign invoice series: branch prefixes collide within a company'
                    USING HINT =
                        'Active or fiscal branch identities cannot be silently renumbered; '
                        'reconcile the conflicting branch identities before retrying.';
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
                  LEFT JOIN migration_0046_branch_facts branch
                    ON branch.branch_id = document.branch_id
                 WHERE branch.branch_id IS NULL
                    OR document.company_id IS DISTINCT FROM branch.company_id
                    OR SPLIT_PART(document.document_no, '/', 2)
                       IS DISTINCT FROM branch.legacy_series
            ) THEN
                RAISE EXCEPTION
                    'Cannot assign invoice series: fiscal history disagrees with branch identity'
                    USING HINT =
                        'Reconcile branch scope and code to every historical receipt prefix; '
                        'do not renumber issued documents.';
            END IF;
        END
        $$;
        """
    )

    op.execute(
        """
        CREATE TEMPORARY TABLE migration_0046_branch_series_plan (
            branch_id uuid PRIMARY KEY,
            company_id uuid NOT NULL,
            series_code varchar(2) NOT NULL
                CHECK (series_code ~ '^[A-Z0-9]{2}$'),
            assignment_reason text NOT NULL,
            UNIQUE (company_id, series_code)
        ) ON COMMIT DROP;

        -- Active branches are operational identities, while branches with a
        -- counter/document own fiscal history. Neither class may be silently
        -- renumbered by a migration. They reserve their legacy prefix first.
        INSERT INTO migration_0046_branch_series_plan (
            branch_id, company_id, series_code, assignment_reason
        )
        SELECT branch_id,
               company_id,
               legacy_series,
               CASE
                   WHEN has_fiscal_history THEN 'fiscal_history'
                   ELSE 'active_identity'
               END
          FROM migration_0046_branch_facts
         WHERE has_fiscal_history OR deleted_at IS NULL;

        -- Only history-free, soft-deleted tombstones are eligible for repair.
        -- Preserve an otherwise-free legacy prefix for the oldest stable
        -- tombstone before deriving a replacement.
        WITH ranked_preferred AS (
            SELECT branch.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY branch.company_id, branch.legacy_series
                       ORDER BY branch.created_at,
                                branch.branch_id
                   ) AS preference_rank
              FROM migration_0046_branch_facts branch
             WHERE NOT branch.has_fiscal_history
               AND branch.deleted_at IS NOT NULL
               AND CHAR_LENGTH(branch.legacy_series) = 2
               AND NOT EXISTS (
                    SELECT 1
                      FROM migration_0046_branch_series_plan reserved
                     WHERE reserved.company_id = branch.company_id
                       AND reserved.series_code = branch.legacy_series
               )
        )
        INSERT INTO migration_0046_branch_series_plan (
            branch_id, company_id, series_code, assignment_reason
        )
        SELECT branch_id, company_id, legacy_series, 'unused_legacy_preferred'
          FROM ranked_preferred
         WHERE preference_rank = 1;

        -- A meaningful two-character name prefix is the next deterministic
        -- choice, but it never displaces a fiscal or retained legacy owner.
        WITH name_candidates AS (
            SELECT branch.*,
                   LEFT(
                       REGEXP_REPLACE(
                           UPPER(COALESCE(branch.name, '')),
                           '[^A-Z0-9]',
                           '',
                           'g'
                       ),
                       2
                   ) AS name_series
              FROM migration_0046_branch_facts branch
             WHERE NOT branch.has_fiscal_history
               AND branch.deleted_at IS NOT NULL
               AND NOT EXISTS (
                    SELECT 1
                      FROM migration_0046_branch_series_plan assigned
                     WHERE assigned.branch_id = branch.branch_id
               )
        ),
        ranked_names AS (
            SELECT candidate.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY candidate.company_id, candidate.name_series
                       ORDER BY candidate.created_at,
                                candidate.branch_id
                   ) AS preference_rank
              FROM name_candidates candidate
             WHERE CHAR_LENGTH(candidate.name_series) = 2
               AND NOT EXISTS (
                    SELECT 1
                      FROM migration_0046_branch_series_plan reserved
                     WHERE reserved.company_id = candidate.company_id
                       AND reserved.series_code = candidate.name_series
               )
        )
        INSERT INTO migration_0046_branch_series_plan (
            branch_id, company_id, series_code, assignment_reason
        )
        SELECT branch_id, company_id, name_series, 'unused_name_fallback'
          FROM ranked_names
         WHERE preference_rank = 1;

        -- Remaining history-free branches draw from a stable 36^2 code space.
        DO $$
        DECLARE
            pending record;
            allocated_code text;
            alphabet constant text := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
        BEGIN
            FOR pending IN
                SELECT branch.*
                  FROM migration_0046_branch_facts branch
                 WHERE NOT branch.has_fiscal_history
                   AND branch.deleted_at IS NOT NULL
                   AND NOT EXISTS (
                        SELECT 1
                          FROM migration_0046_branch_series_plan assigned
                         WHERE assigned.branch_id = branch.branch_id
                 )
                 ORDER BY branch.company_id,
                          branch.created_at,
                          branch.branch_id
            LOOP
                allocated_code := NULL;
                SELECT
                    SUBSTRING(
                        alphabet
                        FROM ((candidate.ordinal - 1) / 36) + 1
                        FOR 1
                    ) ||
                    SUBSTRING(
                        alphabet
                        FROM MOD(candidate.ordinal - 1, 36) + 1
                        FOR 1
                    )
                  INTO allocated_code
                  FROM GENERATE_SERIES(1, 1296) candidate(ordinal)
                 WHERE NOT EXISTS (
                        SELECT 1
                          FROM migration_0046_branch_series_plan reserved
                         WHERE reserved.company_id = pending.company_id
                           AND reserved.series_code =
                               SUBSTRING(
                                   alphabet
                                   FROM ((candidate.ordinal - 1) / 36) + 1
                                   FOR 1
                               ) ||
                               SUBSTRING(
                                   alphabet
                                   FROM MOD(candidate.ordinal - 1, 36) + 1
                                   FOR 1
                               )
                 )
                 ORDER BY candidate.ordinal
                 LIMIT 1;

                IF allocated_code IS NULL THEN
                    RAISE EXCEPTION
                        'Cannot assign invoice series: company exhausted 1296 unique codes'
                        USING HINT =
                            'Archive cannot free a fiscal prefix; contact support to '
                            'design a larger explicit namespace.';
                END IF;

                INSERT INTO migration_0046_branch_series_plan (
                    branch_id, company_id, series_code, assignment_reason
                ) VALUES (
                    pending.branch_id,
                    pending.company_id,
                    allocated_code,
                    'unused_ordered_fallback'
                );
            END LOOP;

            IF (SELECT COUNT(*) FROM migration_0046_branch_series_plan)
               <> (SELECT COUNT(*) FROM migration_0046_branch_facts) THEN
                RAISE EXCEPTION 'Cannot assign invoice series: migration plan is incomplete';
            END IF;
        END
        $$;

        UPDATE branches branch
           SET invoice_series_code = plan.series_code
          FROM migration_0046_branch_series_plan plan
         WHERE plan.branch_id = branch.id;
        """
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
