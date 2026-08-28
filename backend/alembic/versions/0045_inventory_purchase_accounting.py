"""Post inventory receipts and supplier settlement to Accounts Payable.

Revision ID: 0045
Revises: 0044
Create Date: 2026-08-27

Every positive-value GRN receives one immutable balanced journal entry:
Dr Inventory / Cr Accounts Payable. Supplier settlements are separate,
source-linked records that post Dr Accounts Payable / Cr Cash or Bank.

The migration refuses legacy invoice variance instead of guessing whether it
is recoverable GST, freight, discount, or another category. It also refuses a
missing or repurposed canonical account before backfilling historical GRNs.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0045"
down_revision = "0044"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("grns", sa.Column("idempotency_key", sa.String(length=160)))
    op.add_column("grns", sa.Column("request_hash", sa.String(length=64)))
    op.add_column(
        "grns",
        sa.Column("journal_entry_id", postgresql.UUID(as_uuid=True)),
    )
    op.create_foreign_key(
        "fk_grn_purchase_journal",
        "grns",
        "journal_entries",
        ["journal_entry_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_check_constraint(
        "ck_grn_idempotency_pair",
        "grns",
        "(idempotency_key IS NULL AND request_hash IS NULL) OR "
        "(idempotency_key IS NOT NULL AND request_hash IS NOT NULL "
        "AND length(trim(idempotency_key)) BETWEEN 1 AND 160 "
        "AND length(trim(request_hash)) = 64)",
    )
    op.create_unique_constraint(
        "uq_grn_idempotency_key",
        "grns",
        ["idempotency_key"],
    )
    op.create_unique_constraint(
        "uq_grn_journal_entry",
        "grns",
        ["journal_entry_id"],
    )
    op.create_index(
        "ix_grns_journal_entry_id",
        "grns",
        ["journal_entry_id"],
    )

    op.create_table(
        "supplier_payments",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("branch_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("supplier_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("grn_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("journal_entry_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("paid_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("payment_reference", sa.String(length=160), nullable=False),
        sa.Column("note", sa.String(length=500)),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("created_by", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("voided_at", sa.DateTime(timezone=True)),
        sa.Column("voided_by", postgresql.UUID(as_uuid=True)),
        sa.Column("void_reason", sa.String(length=500)),
        sa.CheckConstraint(
            "method IN ('cash', 'bank')",
            name="ck_supplier_payment_method",
        ),
        sa.CheckConstraint(
            "amount_minor > 0",
            name="ck_supplier_payment_positive_amount",
        ),
        sa.CheckConstraint(
            "length(trim(payment_reference)) >= 1",
            name="ck_supplier_payment_reference_present",
        ),
        sa.CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_supplier_payment_idempotency_key_present",
        ),
        sa.CheckConstraint(
            "length(trim(request_hash)) = 64",
            name="ck_supplier_payment_request_hash_present",
        ),
        sa.CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) "
            "OR (voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND void_reason IS NOT NULL AND length(trim(void_reason)) >= 3)",
            name="ck_supplier_payment_void_state",
        ),
        sa.ForeignKeyConstraint(
            ["company_id"], ["companies.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["branch_id"], ["branches.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["supplier_id"], ["suppliers.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["grn_id"], ["grns.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["journal_entry_id"], ["journal_entries.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["created_by"], ["users.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["voided_by"], ["users.id"], ondelete="RESTRICT"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_supplier_payment_company_idempotency",
        ),
        sa.UniqueConstraint(
            "journal_entry_id",
            name="uq_supplier_payment_journal_entry",
        ),
    )
    for column in (
        "company_id",
        "branch_id",
        "supplier_id",
        "grn_id",
        "journal_entry_id",
        "created_by",
        "voided_by",
    ):
        op.create_index(
            f"ix_supplier_payments_{column}",
            "supplier_payments",
            [column],
        )
    op.create_index(
        "ix_supplier_payment_company_paid_at",
        "supplier_payments",
        ["company_id", "paid_at"],
    )
    op.create_index(
        "ix_supplier_payment_grn_active",
        "supplier_payments",
        ["grn_id", "voided_at"],
    )

    # Only the dedicated transactional write paths may create these journal
    # types. One source row can never be posted twice, even after response-cache
    # loss or a concurrent replay.
    op.create_index(
        "uq_journal_entries_purchase_source",
        "journal_entries",
        ["company_id", "ref_type", "ref_id"],
        unique=True,
        postgresql_where=sa.text(
            "ref_type IN ('grn_receipt', 'supplier_payment')"
        ),
    )

    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM journal_entries
                 WHERE ref_type IN ('grn_receipt', 'supplier_payment')
            ) THEN
                RAISE EXCEPTION
                    'Reserved purchase-accounting journal types already exist'
                    USING HINT =
                        'Reclassify unsupported manual journals before retrying.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM grn_lines
                 WHERE qty_received <= 0
                    OR cost_per_unit_minor < 0
            ) THEN
                RAISE EXCEPTION
                    'Cannot post legacy GRNs: invalid received quantity or unit cost exists'
                    USING HINT =
                        'Every received quantity must be positive and every unit cost '
                        'must be zero or greater; reconcile the source GRN lines, then retry.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM stock_movements
                 WHERE type NOT IN (
                     'grn', 'sale', 'refund_restock', 'waste', 'damage', 'adjustment'
                 )
            ) THEN
                RAISE EXCEPTION
                    'Cannot post inventory accounting: unsupported stock movement exists'
                    USING HINT =
                        'Reconcile single-sided transfers or unknown movement types into '
                        'audited source and destination records before retrying migration 0045.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM batches b
                  LEFT JOIN stock_movements sm ON sm.batch_id = b.id
                 GROUP BY b.id, b.qty_initial, b.qty_on_hand
                HAVING b.qty_initial
                           + COALESCE(
                               SUM(
                                   CASE
                                       WHEN sm.type <> 'grn' THEN sm.qty_delta
                                       ELSE 0
                                   END
                               ),
                               0
                           )
                           <> b.qty_on_hand
                    OR COALESCE(
                           BOOL_OR(
                               sm.cost_per_unit_minor <> b.cost_per_unit_minor
                               OR sm.branch_id <> b.branch_id
                           ),
                           FALSE
                       )
            ) THEN
                RAISE EXCEPTION
                    'Cannot post inventory accounting: batch movement evidence is inconsistent'
                    USING HINT =
                        'Reconcile batch quantity, branch, and immutable unit-cost history '
                        'before retrying migration 0045.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM grn_lines gl
                  JOIN grns g ON g.id = gl.grn_id
                  JOIN purchase_orders po ON po.id = g.purchase_order_id
                  LEFT JOIN batches b ON b.id = gl.batch_id
                  LEFT JOIN ingredients i ON i.id = gl.ingredient_id
                  LEFT JOIN suppliers s ON s.id = po.supplier_id
                  LEFT JOIN branches br ON br.id = po.branch_id
                 WHERE gl.batch_id IS NULL
                    OR b.id IS NULL
                    OR b.grn_id IS DISTINCT FROM g.id
                    OR b.ingredient_id IS DISTINCT FROM gl.ingredient_id
                    OR b.branch_id IS DISTINCT FROM po.branch_id
                    OR b.supplier_id IS DISTINCT FROM po.supplier_id
                    OR b.qty_initial IS DISTINCT FROM gl.qty_received
                    OR b.cost_per_unit_minor IS DISTINCT FROM gl.cost_per_unit_minor
                    OR b.received_at IS DISTINCT FROM g.received_at
                    OR i.company_id IS DISTINCT FROM po.company_id
                    OR s.company_id IS DISTINCT FROM po.company_id
                    OR br.company_id IS DISTINCT FROM po.company_id
            )
            OR EXISTS (
                SELECT 1
                  FROM batches b
                 WHERE b.grn_id IS NOT NULL
                   AND (
                       SELECT COUNT(*)
                         FROM grn_lines gl
                        WHERE gl.batch_id = b.id
                   ) <> 1
            ) THEN
                RAISE EXCEPTION
                    'Cannot post legacy GRNs: receipt lines and physical batches diverge'
                    USING HINT =
                        'Reconcile each GRN line to one same-company, same-branch batch '
                        'with identical quantity, cost, supplier, and receipt time.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM grns g
                  LEFT JOIN (
                      SELECT grn_id,
                             COUNT(*) AS line_count,
                             COALESCE(
                                 SUM(ROUND(qty_received * cost_per_unit_minor, 0)),
                                 0
                             )::bigint AS capitalised_minor
                        FROM grn_lines
                       GROUP BY grn_id
                  ) totals ON totals.grn_id = g.id
                 WHERE COALESCE(totals.line_count, 0) = 0
                    OR (
                        g.supplier_invoice_amount_minor IS NOT NULL
                        AND g.supplier_invoice_amount_minor
                            <> COALESCE(totals.capitalised_minor, 0)
                    )
            ) THEN
                RAISE EXCEPTION
                    'Cannot post legacy GRNs: missing lines or unallocated supplier '
                    'invoice variance exists'
                    USING HINT =
                        'Reconcile freight, tax, discounts, and invoice variance into '
                        'exact GRN line unit costs, then retry.';
            END IF;

            IF EXISTS (
                SELECT 1
                  FROM (
                      SELECT DISTINCT po.company_id
                        FROM grns g
                        JOIN purchase_orders po ON po.id = g.purchase_order_id
                        JOIN (
                            SELECT grn_id,
                                   SUM(ROUND(qty_received * cost_per_unit_minor, 0))::bigint
                                       AS total_minor
                              FROM grn_lines
                             GROUP BY grn_id
                        ) totals ON totals.grn_id = g.id
                       WHERE totals.total_minor > 0
                  ) companies_with_grns
                 WHERE NOT EXISTS (
                           SELECT 1 FROM accounts a
                            WHERE a.company_id = companies_with_grns.company_id
                              AND a.code = '1200'
                              AND a.name = 'Inventory'
                              AND a.type = 'asset'
                              AND a.normal_side = 'dr'
                              AND a.is_active IS TRUE
                       )
                    OR NOT EXISTS (
                           SELECT 1 FROM accounts a
                            WHERE a.company_id = companies_with_grns.company_id
                              AND a.code = '2000'
                              AND a.name = 'Accounts Payable'
                              AND a.type = 'liability'
                              AND a.normal_side = 'cr'
                              AND a.is_active IS TRUE
                       )
            ) THEN
                RAISE EXCEPTION
                    'Cannot post legacy GRNs: canonical Inventory or Accounts Payable '
                    'account is missing/incompatible'
                    USING HINT =
                        'Run the guarded chart-of-accounts reconciliation, then retry.';
            END IF;
        END
        $$;
        """
    )

    op.execute(
        """
        WITH totals AS (
            SELECT grn_id,
                   SUM(ROUND(qty_received * cost_per_unit_minor, 0))::bigint
                       AS total_minor
              FROM grn_lines
             GROUP BY grn_id
        )
        INSERT INTO journal_entries (
            id, company_id, branch_id, ref_type, ref_id, posted_at, memo,
            total_minor, created_at, updated_at
        )
        SELECT gen_random_uuid(), po.company_id, po.branch_id, 'grn_receipt',
               g.id, g.received_at,
               CASE
                   WHEN g.supplier_invoice_no IS NULL THEN
                       'Inventory receipt ' || g.id::text
                   ELSE 'Inventory receipt ' || g.supplier_invoice_no
               END,
               totals.total_minor, now(), now()
          FROM grns g
          JOIN purchase_orders po ON po.id = g.purchase_order_id
          JOIN totals ON totals.grn_id = g.id
         WHERE totals.total_minor > 0
        """
    )
    op.execute(
        """
        UPDATE grns g
           SET journal_entry_id = je.id
          FROM journal_entries je
         WHERE je.ref_type = 'grn_receipt'
           AND je.ref_id = g.id
        """
    )
    op.execute(
        """
        INSERT INTO journal_lines (
            id, journal_entry_id, account_id, side, amount_minor, memo,
            created_at, updated_at
        )
        SELECT gen_random_uuid(), je.id, a.id, sides.side, je.total_minor,
               je.memo, now(), now()
          FROM journal_entries je
          CROSS JOIN (VALUES ('1200', 'dr'), ('2000', 'cr')) AS sides(code, side)
          JOIN accounts a
            ON a.company_id = je.company_id AND a.code = sides.code
         WHERE je.ref_type = 'grn_receipt'
        """
    )


def downgrade() -> None:
    # A downgrade after forward money/stock activity would have to erase the
    # durable replay identity and/or supplier settlement source. Refuse that
    # history loss. Only the deterministic legacy-GRN backfill (whose source
    # rows predate this revision and therefore have NULL idempotency keys) is
    # reproducibly removable.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM supplier_payments)
               OR EXISTS (
                    SELECT 1 FROM grns WHERE idempotency_key IS NOT NULL
               )
               OR EXISTS (
                    SELECT 1
                      FROM journal_entries je
                     WHERE je.ref_type = 'supplier_payment'
                        OR (
                            je.ref_type = 'grn_receipt'
                            AND NOT EXISTS (
                                SELECT 1
                                  FROM grns g
                                 WHERE g.id = je.ref_id
                                   AND g.journal_entry_id = je.id
                                   AND g.idempotency_key IS NULL
                            )
                        )
               ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0045 after forward purchase-accounting activity'
                    USING HINT =
                        'Preserve the immutable GRN/payment history and restore the '
                        'application at revision 0045 or later.';
            END IF;
        END
        $$;
        """
    )

    # Remove only deterministic backfill journals owned by legacy GRNs.
    op.execute(
        """
        DELETE FROM journal_lines jl
         USING journal_entries je
         WHERE jl.journal_entry_id = je.id
           AND je.ref_type IN ('grn_receipt', 'supplier_payment')
        """
    )
    op.execute(
        "UPDATE grns SET journal_entry_id = NULL WHERE journal_entry_id IS NOT NULL"
    )
    op.drop_index("ix_supplier_payment_grn_active", table_name="supplier_payments")
    op.drop_index("ix_supplier_payment_company_paid_at", table_name="supplier_payments")
    for column in reversed(
        (
            "company_id",
            "branch_id",
            "supplier_id",
            "grn_id",
            "journal_entry_id",
            "created_by",
            "voided_by",
        )
    ):
        op.drop_index(
            f"ix_supplier_payments_{column}",
            table_name="supplier_payments",
        )
    op.drop_table("supplier_payments")
    op.execute(
        "DELETE FROM journal_entries WHERE ref_type IN ('grn_receipt', 'supplier_payment')"
    )
    op.drop_index(
        "uq_journal_entries_purchase_source",
        table_name="journal_entries",
    )

    op.drop_index("ix_grns_journal_entry_id", table_name="grns")
    op.drop_constraint("uq_grn_journal_entry", "grns", type_="unique")
    op.drop_constraint("uq_grn_idempotency_key", "grns", type_="unique")
    op.drop_constraint("ck_grn_idempotency_pair", "grns", type_="check")
    op.drop_constraint("fk_grn_purchase_journal", "grns", type_="foreignkey")
    op.drop_column("grns", "journal_entry_id")
    op.drop_column("grns", "request_hash")
    op.drop_column("grns", "idempotency_key")
