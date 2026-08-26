"""Reserve provider refunds before external money movement.

Revision ID: 0036
Revises: 0035
Create Date: 2026-08-26

0034 accepted provider evidence only after staff had already completed the
external refund. A definitive validation failure could therefore leave real
money outside the ERP. This revision separates the server reservation, the
append-only cash/provider value-movement completion, and the retryable
accounting finalization. Existing 0034 provider settlements, if any, are copied
from their exact recorded evidence without changing any financial amount,
customer balance, drawer value, receipt, or report date.

Pre-0036 POS refunds retain ``customer_spend_reconciled = NULL``. They are
surfaced as explicit unknown legacy outcomes and may be normalized only by a
protected-owner append-only reconciliation after source provenance is proven.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0036"
down_revision = "0035"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "refunds",
        sa.Column("customer_spend_reconciled", sa.Boolean(), nullable=True),
    )
    op.add_column(
        "refunds",
        sa.Column("client_occurred_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "refunds",
        sa.Column("captured_time_reconciled", sa.Boolean(), nullable=True),
    )
    op.add_column(
        "refunds",
        sa.Column("provider_evidence_reconciled", sa.Boolean(), nullable=True),
    )
    op.execute(
        """
        UPDATE refunds
           SET provider_evidence_reconciled = true
         WHERE request_id IS NOT NULL AND settlement_method <> 'cash'
        """
    )
    op.drop_constraint(
        "ck_refund_request_external_provenance", "refunds", type_="check"
    )
    op.create_check_constraint(
        "ck_refund_request_external_provenance",
        "refunds",
        "request_id IS NULL OR "
        "(settlement_method = 'cash' AND external_reference IS NULL "
        "AND provider_settled_at IS NULL "
        "AND provider_evidence_reconciled IS NULL) OR "
        "(settlement_method <> 'cash' "
        "AND char_length(trim(external_reference)) >= 1 "
        "AND provider_settled_at IS NOT NULL "
        "AND provider_evidence_reconciled IS NOT NULL)",
    )
    # ``request_id`` remains nullable because PostgreSQL must retain historical
    # refund rows that predate the reservation workflow.  A NOT VALID check
    # deliberately skips that historical scan while still rejecting every new
    # INSERT and every UPDATE of an unlinked row after this migration commits.
    op.execute(
        """
        ALTER TABLE refunds
        ADD CONSTRAINT ck_refund_forward_write_linkage
        CHECK (request_id IS NOT NULL) NOT VALID
        """
    )
    # Forward requests are reservations only.  The legacy columns remain so a
    # 0034 settlement is still readable, but new provider evidence belongs to
    # the separate append-only completion table below.
    op.drop_constraint(
        "ck_pos_refund_request_external_provenance",
        "pos_refund_requests",
        type_="check",
    )
    op.drop_constraint(
        "uq_pos_refund_request_provider_reference",
        "pos_refund_requests",
        type_="unique",
    )

    op.add_column(
        "pos_refund_withdrawals",
        sa.Column("verification_reference", sa.String(length=200), nullable=True),
    )
    op.add_column(
        "pos_refund_withdrawals",
        sa.Column("verification_status", sa.String(length=40), nullable=True),
    )
    op.add_column(
        "pos_refund_withdrawals",
        sa.Column("verified_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.drop_constraint(
        "ck_pos_refund_withdrawal_resolution",
        "pos_refund_withdrawals",
        type_="check",
    )
    op.create_check_constraint(
        "ck_pos_refund_withdrawal_resolution",
        "pos_refund_withdrawals",
        "resolution IN ("
        "'cash_not_handed_over', 'cash_handoff_abandoned', "
        "'provider_not_started', 'provider_payout_abandoned'"
        ")",
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
        "AND verification_reference IS NULL AND verification_status IS NULL "
        "AND verified_at IS NULL)",
    )

    op.create_table(
        "pos_refund_cash_handoff_completions",
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
        sa.Column("handed_over_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "recorded_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("captured_time_reconciled", sa.Boolean(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
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
        sa.UniqueConstraint(
            "refund_request_id", name="uq_pos_refund_cash_completion_request"
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_cash_completion_company_idempotency",
        ),
    )
    op.create_index(
        "ix_pos_refund_cash_completion_company_id",
        "pos_refund_cash_handoff_completions",
        ["company_id"],
    )
    op.create_index(
        "ix_pos_refund_cash_completion_shift",
        "pos_refund_cash_handoff_completions",
        ["shift_id"],
    )

    op.create_table(
        "pos_refund_provider_payout_starts",
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
        sa.UniqueConstraint(
            "refund_request_id",
            name="uq_pos_refund_provider_start_request",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_provider_start_company_idempotency",
        ),
    )
    op.create_index(
        "ix_pos_refund_provider_payout_starts_company_id",
        "pos_refund_provider_payout_starts",
        ["company_id"],
    )
    op.create_index(
        "ix_pos_refund_provider_start_shift",
        "pos_refund_provider_payout_starts",
        ["shift_id"],
    )

    op.create_table(
        "pos_refund_provider_settlements",
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
        sa.Column("settlement_method", sa.String(length=20), nullable=False),
        sa.Column("external_reference", sa.String(length=200), nullable=False),
        sa.Column(
            "provider_settled_at", sa.DateTime(timezone=True), nullable=False
        ),
        sa.Column(
            "settled_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("captured_time_reconciled", sa.Boolean(), nullable=False),
        sa.Column("provider_evidence_reconciled", sa.Boolean(), nullable=False),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
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
            "settlement_method IN ('card', 'upi', 'qr', 'wallet')",
            name="ck_pos_refund_provider_method",
        ),
        sa.CheckConstraint(
            "char_length(trim(external_reference)) >= 1",
            name="ck_pos_refund_provider_reference",
        ),
        sa.UniqueConstraint(
            "refund_request_id",
            name="uq_pos_refund_provider_settlement_request",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_provider_settlement_company_idempotency",
        ),
    )
    op.create_index(
        "ix_pos_refund_provider_settlements_company_id",
        "pos_refund_provider_settlements",
        ["company_id"],
    )
    op.create_index(
        "ix_pos_refund_provider_settlement_shift",
        "pos_refund_provider_settlements",
        ["shift_id"],
    )

    op.create_table(
        "customer_spend_reconciliations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "customer_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "pos_refund_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("refunds.id", ondelete="RESTRICT"),
        ),
        sa.Column(
            "membership_refund_settlement_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refund_settlements.id", ondelete="RESTRICT"),
        ),
        sa.Column(
            "source_reconciliation_state", sa.String(length=30), nullable=False
        ),
        sa.Column("source_amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("before_total_spent_minor", sa.BigInteger(), nullable=False),
        sa.Column("after_total_spent_minor", sa.BigInteger(), nullable=False),
        sa.Column("adjustment_minor", sa.BigInteger(), nullable=False),
        sa.Column("pos_gross_minor", sa.BigInteger(), nullable=False),
        sa.Column("membership_gross_minor", sa.BigInteger(), nullable=False),
        sa.Column("pos_refunds_minor", sa.BigInteger(), nullable=False),
        sa.Column("membership_refunds_minor", sa.BigInteger(), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("reconciled_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "reconciled_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
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
            "(pos_refund_id IS NOT NULL AND membership_refund_settlement_id IS NULL) "
            "OR (pos_refund_id IS NULL AND membership_refund_settlement_id IS NOT NULL)",
            name="ck_customer_spend_reconciliation_one_source",
        ),
        sa.CheckConstraint(
            "after_total_spent_minor >= 0",
            name="ck_customer_spend_reconciliation_nonnegative_after",
        ),
        sa.CheckConstraint(
            "adjustment_minor = after_total_spent_minor - before_total_spent_minor",
            name="ck_customer_spend_reconciliation_delta",
        ),
        sa.CheckConstraint(
            "source_reconciliation_state IN ('unreconciled', 'legacy_unknown')",
            name="ck_customer_spend_reconciliation_source_state",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_customer_spend_reconciliation_reason",
        ),
        sa.UniqueConstraint(
            "pos_refund_id",
            name="uq_customer_spend_reconciliation_pos_refund",
        ),
        sa.UniqueConstraint(
            "membership_refund_settlement_id",
            name="uq_customer_spend_reconciliation_membership_refund",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_customer_spend_reconciliation_idempotency",
        ),
    )
    op.create_index(
        "ix_customer_spend_reconciliations_company_id",
        "customer_spend_reconciliations",
        ["company_id"],
    )
    op.create_index(
        "ix_customer_spend_reconciliation_customer",
        "customer_spend_reconciliations",
        ["customer_id"],
    )

    op.create_table(
        "pos_refund_evidence_reconciliations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "refund_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("refunds.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("evidence_kind", sa.String(length=40), nullable=False),
        sa.Column("proof_reference", sa.String(length=200), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("reconciled_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "reconciled_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("idempotency_key", sa.String(length=160), nullable=False),
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
            "evidence_kind IN ('provider_reference', 'captured_time')",
            name="ck_pos_refund_evidence_reconciliation_kind",
        ),
        sa.CheckConstraint(
            "char_length(trim(proof_reference)) >= 3",
            name="ck_pos_refund_evidence_reconciliation_proof",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_pos_refund_evidence_reconciliation_reason",
        ),
        sa.UniqueConstraint(
            "refund_id",
            "evidence_kind",
            name="uq_pos_refund_evidence_reconciliation_kind",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_pos_refund_evidence_reconciliation_idempotency",
        ),
    )
    op.create_index(
        "ix_pos_refund_evidence_reconciliations_company_id",
        "pos_refund_evidence_reconciliations",
        ["company_id"],
    )
    op.create_index(
        "ix_pos_refund_evidence_reconciliations_refund_id",
        "pos_refund_evidence_reconciliations",
        ["refund_id"],
    )

    # A single mutable coordination row is the database serialization point for
    # each append-only refund workflow.  Merely taking an advisory lock inside
    # an INSERT trigger is insufficient under READ COMMITTED: the statement can
    # retain a snapshot from before a competing transaction committed.  Every
    # transition below instead performs an atomic UPDATE ... WHERE ... RETURNING
    # on this row, so PostgreSQL re-checks the expected state after any row-lock
    # wait.  The financial facts remain append-only; this table contains no
    # business amount and exists only to make mutually-exclusive transitions
    # race-safe.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM pos_refund_requests pr
                LEFT JOIN orders o ON o.id = pr.order_id
                LEFT JOIN shifts original_shift ON original_shift.id = o.shift_id
                LEFT JOIN branches b ON b.id = pr.branch_id
                LEFT JOIN terminals t ON t.id = pr.terminal_id
                LEFT JOIN shifts s ON s.id = pr.shift_id
                LEFT JOIN users approver ON approver.id = pr.approved_by
                WHERE o.id IS NULL
                   OR o.company_id IS DISTINCT FROM pr.company_id
                   OR o.branch_id IS DISTINCT FROM pr.branch_id
                   OR o.terminal_id IS DISTINCT FROM pr.terminal_id
                   OR o.status NOT IN ('paid', 'refunded')
                   OR original_shift.id IS NULL
                   OR original_shift.company_id IS DISTINCT FROM o.company_id
                   OR original_shift.branch_id IS DISTINCT FROM o.branch_id
                   OR original_shift.terminal_id IS DISTINCT FROM o.terminal_id
                   OR b.id IS NULL
                   OR b.company_id IS DISTINCT FROM pr.company_id
                   OR t.id IS NULL
                   OR t.branch_id IS DISTINCT FROM pr.branch_id
                   OR s.id IS NULL
                   OR s.company_id IS DISTINCT FROM pr.company_id
                   OR s.branch_id IS DISTINCT FROM pr.branch_id
                   OR s.terminal_id IS DISTINCT FROM pr.terminal_id
                   OR pr.accepted_at < s.opened_at
                   OR (s.closed_at IS NOT NULL AND pr.accepted_at > s.closed_at)
                   OR approver.id IS NULL
                   OR approver.company_id IS DISTINCT FROM pr.company_id
                   -- 0034 accepted this value from the client and had no
                   -- authenticated second-approver ceremony.  Even a real
                   -- same-company UUID is therefore untrusted provenance;
                   -- require explicit pre-upgrade reconciliation instead of
                   -- preserving it as if the manager actually approved.
                   OR pr.manager_override_user_id IS NOT NULL
            ) THEN
                RAISE EXCEPTION
                    'Cannot upgrade 0036: invalid legacy POS refund root provenance';
            END IF;

            IF EXISTS (
                SELECT 1
                FROM pos_refund_requests pr
                LEFT JOIN refunds r ON r.request_id = pr.id
                LEFT JOIN pos_refund_cash_handoffs h
                  ON h.refund_request_id = pr.id
                LEFT JOIN pos_refund_withdrawals w
                  ON w.refund_request_id = pr.id
                WHERE
                    (r.id IS NOT NULL AND w.id IS NOT NULL)
                    OR (h.id IS NOT NULL AND w.id IS NOT NULL)
                    OR (pr.settlement_method = 'cash'
                        AND r.id IS NOT NULL AND h.id IS NULL)
                    -- In 0034 every non-cash request itself carried provider
                    -- completion evidence.  Without its matching Refund we
                    -- cannot prove whether accounting failed after real money
                    -- moved; exposing it as a new payable reservation could
                    -- cause a second payout, so the upgrade must stop for
                    -- explicit owner reconciliation.
                    OR (pr.settlement_method <> 'cash' AND r.id IS NULL)
                    OR (pr.settlement_method <> 'cash' AND h.id IS NOT NULL)
                    OR (pr.settlement_method <> 'cash' AND w.id IS NOT NULL)
                    OR (h.id IS NOT NULL AND (
                        h.company_id IS DISTINCT FROM pr.company_id
                        OR h.branch_id IS DISTINCT FROM pr.branch_id
                        OR h.terminal_id IS DISTINCT FROM pr.terminal_id
                        OR h.shift_id IS DISTINCT FROM pr.shift_id
                    ))
                    OR (w.id IS NOT NULL AND (
                        w.company_id IS DISTINCT FROM pr.company_id
                        OR w.branch_id IS DISTINCT FROM pr.branch_id
                        OR w.terminal_id IS DISTINCT FROM pr.terminal_id
                        OR w.shift_id IS DISTINCT FROM pr.shift_id
                        OR w.resolution <> 'cash_not_handed_over'
                    ))
                    OR (r.id IS NOT NULL AND (
                        r.company_id IS DISTINCT FROM pr.company_id
                        OR r.branch_id IS DISTINCT FROM pr.branch_id
                        OR r.terminal_id IS DISTINCT FROM pr.terminal_id
                        OR r.settlement_shift_id IS DISTINCT FROM pr.shift_id
                        OR r.settlement_method IS DISTINCT FROM pr.settlement_method
                        OR (
                            pr.settlement_method <> 'cash'
                            AND (
                                r.external_reference IS DISTINCT FROM
                                    pr.external_reference
                                OR r.provider_settled_at IS DISTINCT FROM
                                    pr.provider_settled_at
                            )
                        )
                    ))
                    OR (r.id IS NOT NULL AND h.id IS NOT NULL
                        AND COALESCE(r.settled_at, r.created_at) < h.started_at)
            ) THEN
                RAISE EXCEPTION
                    'Cannot upgrade 0036: invalid legacy POS refund transition facts';
            END IF;
        END $$
        """
    )
    op.create_table(
        "pos_refund_workflow_guards",
        sa.Column(
            "refund_request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("pos_refund_requests.id", ondelete="RESTRICT"),
            primary_key=True,
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
        sa.Column("settlement_method", sa.String(length=20), nullable=False),
        sa.Column(
            "action_state",
            sa.String(length=24),
            nullable=False,
            server_default="accepted",
        ),
        sa.Column("action_started_at", sa.DateTime(timezone=True)),
        sa.Column(
            "action_started_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
        ),
        sa.Column("terminal_state", sa.String(length=20)),
        sa.Column("terminal_at", sa.DateTime(timezone=True)),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "settlement_method IN ('cash', 'card', 'upi', 'qr', 'wallet')",
            name="ck_pos_refund_guard_method",
        ),
        sa.CheckConstraint(
            "action_state IN ('accepted', 'cash_started', 'cash_completed', "
            "'provider_started', 'provider_completed')",
            name="ck_pos_refund_guard_action",
        ),
        sa.CheckConstraint(
            "action_state = 'accepted' "
            "OR (action_state IN ('cash_started', 'cash_completed') "
            "AND settlement_method = 'cash') "
            "OR (action_state IN ('provider_started', 'provider_completed') "
            "AND settlement_method <> 'cash')",
            name="ck_pos_refund_guard_action_method",
        ),
        sa.CheckConstraint(
            "(action_state = 'accepted' AND action_started_at IS NULL "
            "AND action_started_by IS NULL) OR "
            "(action_state <> 'accepted' AND action_started_at IS NOT NULL "
            "AND action_started_by IS NOT NULL)",
            name="ck_pos_refund_guard_action_evidence",
        ),
        sa.CheckConstraint(
            "terminal_state IS NULL OR terminal_state IN ('settled', 'withdrawn')",
            name="ck_pos_refund_guard_terminal",
        ),
        sa.CheckConstraint(
            "(terminal_state IS NULL AND terminal_at IS NULL) OR "
            "(terminal_state IS NOT NULL AND terminal_at IS NOT NULL)",
            name="ck_pos_refund_guard_terminal_time",
        ),
        sa.CheckConstraint(
            "terminal_state <> 'settled' OR action_state <> 'accepted'",
            name="ck_pos_refund_guard_settlement_started",
        ),
    )
    op.create_index(
        "ix_pos_refund_workflow_guards_company_id",
        "pos_refund_workflow_guards",
        ["company_id"],
    )
    op.execute(
        """
        INSERT INTO pos_refund_workflow_guards (
            refund_request_id, company_id, branch_id, terminal_id, shift_id,
            settlement_method, action_state, action_started_at,
            action_started_by, terminal_state, terminal_at, updated_at
        )
        SELECT
            pr.id, pr.company_id, pr.branch_id, pr.terminal_id, pr.shift_id,
            pr.settlement_method,
            CASE
                WHEN h.id IS NOT NULL THEN 'cash_started'
                WHEN pr.settlement_method <> 'cash' AND r.id IS NOT NULL
                    THEN 'provider_completed'
                ELSE 'accepted'
            END,
            CASE
                WHEN h.id IS NOT NULL THEN h.started_at
                WHEN pr.settlement_method <> 'cash' AND r.id IS NOT NULL
                    THEN COALESCE(r.provider_settled_at, r.settled_at, r.created_at)
                ELSE NULL
            END,
            CASE
                WHEN h.id IS NOT NULL THEN h.started_by
                WHEN pr.settlement_method <> 'cash' AND r.id IS NOT NULL
                    THEN r.settled_by
                ELSE NULL
            END,
            CASE
                WHEN r.id IS NOT NULL THEN 'settled'
                WHEN w.id IS NOT NULL THEN 'withdrawn'
                ELSE NULL
            END,
            COALESCE(r.settled_at, r.created_at, w.withdrawn_at),
            GREATEST(pr.updated_at, COALESCE(r.updated_at, pr.updated_at),
                     COALESCE(w.updated_at, pr.updated_at),
                     COALESCE(h.updated_at, pr.updated_at))
        FROM pos_refund_requests pr
        LEFT JOIN pos_refund_cash_handoffs h ON h.refund_request_id = pr.id
        LEFT JOIN refunds r ON r.request_id = pr.id
        LEFT JOIN pos_refund_withdrawals w ON w.refund_request_id = pr.id
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_create_pos_refund_workflow_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            INSERT INTO pos_refund_workflow_guards (
                refund_request_id, company_id, branch_id, terminal_id, shift_id,
                settlement_method, action_state, updated_at
            ) VALUES (
                NEW.id, NEW.company_id, NEW.branch_id, NEW.terminal_id,
                NEW.shift_id, NEW.settlement_method, 'accepted', NEW.created_at
            );
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_pos_refund_request_create_workflow_guard
        AFTER INSERT ON pos_refund_requests
        FOR EACH ROW EXECUTE FUNCTION dcompany_create_pos_refund_workflow_guard()
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_protect_pos_refund_workflow_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF pg_trigger_depth() < 2 THEN
                RAISE EXCEPTION
                    'POS refund workflow guards are internal and cannot be changed directly';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_pos_refund_workflow_guards_internal
        BEFORE INSERT OR UPDATE OR DELETE ON pos_refund_workflow_guards
        FOR EACH ROW EXECUTE FUNCTION dcompany_protect_pos_refund_workflow_guard()
        """
    )

    # Foreign keys prove only that each referenced row exists.  They do not
    # prove that an order, refund shift, terminal and approving user belong to
    # one operational tenant.  Validate the root reservation before its CAS
    # guard is created so later append-only facts inherit authoritative
    # provenance instead of merely repeating client-supplied UUIDs.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_validate_pos_refund_request()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            order_company uuid;
            order_branch uuid;
            order_terminal uuid;
            order_shift uuid;
            order_status text;
            authoritative_paid_minor bigint;
            authoritative_settled_refund_minor bigint;
            authoritative_reserved_refund_minor bigint;
            authoritative_refundable_minor bigint;
            original_shift_company uuid;
            original_shift_branch uuid;
            original_shift_terminal uuid;
            request_shift_company uuid;
            request_shift_branch uuid;
            request_shift_terminal uuid;
            request_shift_opened_at timestamptz;
            request_shift_closed_at timestamptz;
            request_shift_status text;
        BEGIN
            SELECT o.company_id, o.branch_id, o.terminal_id, o.shift_id, o.status
              INTO order_company, order_branch, order_terminal, order_shift,
                   order_status
              FROM orders o
             WHERE o.id = NEW.order_id
             FOR UPDATE;
            IF NOT FOUND
               OR order_company IS DISTINCT FROM NEW.company_id
               OR order_branch IS DISTINCT FROM NEW.branch_id
               OR order_terminal IS DISTINCT FROM NEW.terminal_id
               OR order_status NOT IN ('paid', 'refunded') THEN
                RAISE EXCEPTION
                    'POS refund request order provenance is invalid';
            END IF;

            -- The Order row is the workflow mutex used by every application
            -- refund transition.  Recompute the financial snapshots only
            -- after acquiring it so a direct writer cannot over-reserve by
            -- submitting stale or invented client totals.  Completed-but-not-
            -- finalized requests stay reserved because they have neither a
            -- Refund nor a Withdrawal terminal fact yet.
            SELECT COALESCE(sum(p.amount_minor), 0)
              INTO authoritative_paid_minor
              FROM payments p
             WHERE p.order_id = NEW.order_id;
            SELECT COALESCE(sum(r.amount_minor), 0)
              INTO authoritative_settled_refund_minor
              FROM refunds r
             WHERE r.order_id = NEW.order_id;
            SELECT COALESCE(sum(pr.amount_minor), 0)
              INTO authoritative_reserved_refund_minor
              FROM pos_refund_requests pr
             WHERE pr.order_id = NEW.order_id
               AND NOT EXISTS (
                    SELECT 1 FROM refunds r
                     WHERE r.request_id = pr.id
               )
               AND NOT EXISTS (
                    SELECT 1 FROM pos_refund_withdrawals w
                     WHERE w.refund_request_id = pr.id
               );
            authoritative_refundable_minor :=
                authoritative_paid_minor
                - authoritative_settled_refund_minor
                - authoritative_reserved_refund_minor;
            IF NEW.order_paid_snapshot_minor IS DISTINCT FROM authoritative_paid_minor
               OR NEW.order_refundable_snapshot_minor IS DISTINCT FROM
                  authoritative_refundable_minor
               OR authoritative_refundable_minor <= 0
               OR NEW.amount_minor > authoritative_refundable_minor THEN
                RAISE EXCEPTION
                    'POS refund request financial snapshot is stale or invalid';
            END IF;

            SELECT s.company_id, s.branch_id, s.terminal_id
              INTO original_shift_company, original_shift_branch,
                   original_shift_terminal
              FROM shifts s
             WHERE s.id = order_shift;
            IF NOT FOUND
               OR original_shift_company IS DISTINCT FROM order_company
               OR original_shift_branch IS DISTINCT FROM order_branch
               OR original_shift_terminal IS DISTINCT FROM order_terminal THEN
                RAISE EXCEPTION
                    'POS refund request original shift provenance is invalid';
            END IF;

            -- This lock serializes request acceptance with shift close.  The
            -- application follows the same Order -> Shift lock order.
            SELECT s.company_id, s.branch_id, s.terminal_id, s.opened_at,
                   s.closed_at, s.status
              INTO request_shift_company, request_shift_branch,
                   request_shift_terminal, request_shift_opened_at,
                   request_shift_closed_at, request_shift_status
              FROM shifts s
             WHERE s.id = NEW.shift_id
             FOR UPDATE;
            IF NOT FOUND
               OR request_shift_company IS DISTINCT FROM NEW.company_id
               OR request_shift_branch IS DISTINCT FROM NEW.branch_id
               OR request_shift_terminal IS DISTINCT FROM NEW.terminal_id
               OR request_shift_status <> 'open'
               OR request_shift_closed_at IS NOT NULL
               OR NEW.accepted_at < request_shift_opened_at
               OR NEW.accepted_at > clock_timestamp() + interval '5 minutes' THEN
                RAISE EXCEPTION
                    'POS refund request shift provenance is invalid';
            END IF;

            IF NOT EXISTS (
                SELECT 1 FROM branches b
                 WHERE b.id = NEW.branch_id
                   AND b.company_id = NEW.company_id
            ) OR NOT EXISTS (
                SELECT 1 FROM terminals t
                 WHERE t.id = NEW.terminal_id
                   AND t.branch_id = NEW.branch_id
            ) THEN
                RAISE EXCEPTION
                    'POS refund request branch or terminal provenance is invalid';
            END IF;

            IF NEW.manager_override_user_id IS NOT NULL THEN
                RAISE EXCEPTION
                    'POS refund request cannot name a client-supplied manager override';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.approved_by
                   AND u.company_id = NEW.company_id
                   AND u.status = 'active'
                   AND u.deleted_at IS NULL
            ) THEN
                RAISE EXCEPTION
                    'POS refund request approver provenance is invalid';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_pos_refund_request_provenance
        BEFORE INSERT ON pos_refund_requests
        FOR EACH ROW EXECUTE FUNCTION dcompany_validate_pos_refund_request()
        """
    )

    # Exact-evidence compatibility for any 0034 provider refunds.  Refund.id is
    # reused as a deterministic identifier in a different table; no financial
    # fact is inferred or rewritten.
    op.execute(
        """
        INSERT INTO pos_refund_provider_settlements (
            id, refund_request_id, company_id, branch_id, terminal_id, shift_id,
            settlement_method, external_reference, provider_settled_at,
            settled_by, captured_time_reconciled,
            provider_evidence_reconciled, idempotency_key, created_at, updated_at
        )
        SELECT
            r.id, r.request_id, r.company_id, r.branch_id, r.terminal_id,
            r.settlement_shift_id, r.settlement_method,
            COALESCE(r.external_reference, pr.external_reference),
            COALESCE(r.provider_settled_at, pr.provider_settled_at),
            r.settled_by, COALESCE(r.captured_time_reconciled, true),
            COALESCE(r.provider_evidence_reconciled, true),
            r.settlement_idempotency_key,
            r.created_at, r.updated_at
        FROM refunds r
        JOIN pos_refund_requests pr ON pr.id = r.request_id
        WHERE r.request_id IS NOT NULL
          AND r.settlement_method <> 'cash'
        ON CONFLICT (refund_request_id) DO NOTHING
        """
    )

    # Enforce the transition graph below the API as well. Legacy 0034
    # provider facts are backfilled first because that release had no durable
    # provider-start acknowledgement.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_pos_refund_transition()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            request_uuid uuid;
            request_method text;
            request_company uuid;
            request_branch uuid;
            request_terminal uuid;
            request_shift uuid;
            request_order uuid;
            request_amount bigint;
            request_mode text;
            request_reason text;
            request_note text;
            request_approved_by uuid;
            request_manager_override uuid;
            request_accepted_at timestamptz;
            transition_actor uuid;
            transition_at timestamptz;
            guard_action text;
            guard_terminal text;
            guard_action_started_at timestamptz;
        BEGIN
            IF TG_TABLE_NAME = 'refunds' THEN
                request_uuid := NEW.request_id;
                IF request_uuid IS NULL THEN RETURN NEW; END IF;
            ELSE
                request_uuid := NEW.refund_request_id;
            END IF;

            IF TG_TABLE_NAME = 'pos_refund_cash_handoffs' THEN
                UPDATE pos_refund_workflow_guards
                   SET action_state = 'cash_started',
                       action_started_at = NEW.started_at,
                       action_started_by = NEW.started_by,
                       updated_at = clock_timestamp()
                 WHERE refund_request_id = request_uuid
                   AND settlement_method = 'cash'
                   AND action_state = 'accepted'
                   AND terminal_state IS NULL
                RETURNING company_id, branch_id, terminal_id, shift_id,
                          settlement_method, action_state, terminal_state,
                          action_started_at
                     INTO request_company, request_branch, request_terminal,
                          request_shift, request_method, guard_action,
                          guard_terminal, guard_action_started_at;
            ELSIF TG_TABLE_NAME = 'pos_refund_cash_handoff_completions' THEN
                UPDATE pos_refund_workflow_guards
                   SET action_state = 'cash_completed',
                       updated_at = clock_timestamp()
                 WHERE refund_request_id = request_uuid
                   AND settlement_method = 'cash'
                   AND action_state = 'cash_started'
                   AND terminal_state IS NULL
                RETURNING company_id, branch_id, terminal_id, shift_id,
                          settlement_method, action_state, terminal_state,
                          action_started_at
                     INTO request_company, request_branch, request_terminal,
                          request_shift, request_method, guard_action,
                          guard_terminal, guard_action_started_at;
            ELSIF TG_TABLE_NAME = 'pos_refund_provider_payout_starts' THEN
                UPDATE pos_refund_workflow_guards
                   SET action_state = 'provider_started',
                       action_started_at = NEW.started_at,
                       action_started_by = NEW.started_by,
                       updated_at = clock_timestamp()
                 WHERE refund_request_id = request_uuid
                   AND settlement_method <> 'cash'
                   AND action_state = 'accepted'
                   AND terminal_state IS NULL
                RETURNING company_id, branch_id, terminal_id, shift_id,
                          settlement_method, action_state, terminal_state,
                          action_started_at
                     INTO request_company, request_branch, request_terminal,
                          request_shift, request_method, guard_action,
                          guard_terminal, guard_action_started_at;
            ELSIF TG_TABLE_NAME = 'pos_refund_provider_settlements' THEN
                UPDATE pos_refund_workflow_guards
                   SET action_state = 'provider_completed',
                       updated_at = clock_timestamp()
                 WHERE refund_request_id = request_uuid
                   AND settlement_method <> 'cash'
                   AND action_state = 'provider_started'
                   AND terminal_state IS NULL
                RETURNING company_id, branch_id, terminal_id, shift_id,
                          settlement_method, action_state, terminal_state,
                          action_started_at
                     INTO request_company, request_branch, request_terminal,
                          request_shift, request_method, guard_action,
                          guard_terminal, guard_action_started_at;
            ELSIF TG_TABLE_NAME = 'refunds' THEN
                UPDATE pos_refund_workflow_guards
                   SET terminal_state = 'settled',
                       terminal_at = COALESCE(terminal_at, clock_timestamp()),
                       updated_at = clock_timestamp()
                 WHERE refund_request_id = request_uuid
                   AND (
                       (settlement_method = 'cash' AND action_state = 'cash_completed')
                       OR (settlement_method <> 'cash'
                           AND action_state = 'provider_completed')
                   )
                   AND terminal_state IS NULL
                RETURNING company_id, branch_id, terminal_id, shift_id,
                          settlement_method, action_state, terminal_state,
                          action_started_at
                     INTO request_company, request_branch, request_terminal,
                          request_shift, request_method, guard_action,
                          guard_terminal, guard_action_started_at;
            ELSE
                UPDATE pos_refund_workflow_guards
                   SET terminal_state = 'withdrawn',
                       terminal_at = NEW.withdrawn_at,
                       updated_at = clock_timestamp()
                 WHERE refund_request_id = request_uuid
                   AND terminal_state IS NULL
                   AND (
                       (NEW.resolution = 'cash_not_handed_over'
                        AND settlement_method = 'cash'
                        AND action_state = 'accepted')
                       OR (NEW.resolution = 'cash_handoff_abandoned'
                           AND settlement_method = 'cash'
                           AND action_state = 'cash_started')
                       OR (NEW.resolution = 'provider_not_started'
                           AND settlement_method <> 'cash'
                           AND action_state = 'accepted')
                       OR (NEW.resolution = 'provider_payout_abandoned'
                           AND settlement_method <> 'cash'
                           AND action_state = 'provider_started')
                   )
                RETURNING company_id, branch_id, terminal_id, shift_id,
                          settlement_method, action_state, terminal_state,
                          action_started_at
                     INTO request_company, request_branch, request_terminal,
                          request_shift, request_method, guard_action,
                          guard_terminal, guard_action_started_at;
            END IF;

            IF NOT FOUND THEN
                RAISE EXCEPTION
                    'POS refund request % cannot make transition % from its current state',
                    request_uuid, TG_TABLE_NAME;
            END IF;

            SELECT pr.order_id, pr.amount_minor, pr.mode, pr.reason_code, pr.note,
                   pr.approved_by, pr.manager_override_user_id, pr.accepted_at
              INTO request_order, request_amount, request_mode, request_reason,
                   request_note, request_approved_by, request_manager_override,
                   request_accepted_at
              FROM pos_refund_requests pr
             WHERE pr.id = request_uuid;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'POS refund request % no longer exists', request_uuid;
            END IF;

            IF TG_TABLE_NAME = 'refunds' THEN
                IF NEW.company_id IS DISTINCT FROM request_company
                   OR NEW.branch_id IS DISTINCT FROM request_branch
                   OR NEW.terminal_id IS DISTINCT FROM request_terminal
                   OR NEW.settlement_shift_id IS DISTINCT FROM request_shift
                   OR NEW.settlement_method IS DISTINCT FROM request_method
                   OR NEW.order_id IS DISTINCT FROM request_order
                   OR NEW.amount_minor IS DISTINCT FROM request_amount
                   OR NEW.mode IS DISTINCT FROM request_mode
                   OR NEW.reason_code IS DISTINCT FROM request_reason
                   OR NEW.note IS DISTINCT FROM request_note
                   OR NEW.approved_by IS DISTINCT FROM request_approved_by
                   OR NEW.manager_override_user_id IS DISTINCT FROM
                      request_manager_override THEN
                    RAISE EXCEPTION
                        'Refund provenance does not match request %', request_uuid;
                END IF;
                transition_actor := NEW.settled_by;
                transition_at := NEW.settled_at;
            ELSE
                IF NEW.company_id IS DISTINCT FROM request_company
                   OR NEW.branch_id IS DISTINCT FROM request_branch
                   OR NEW.terminal_id IS DISTINCT FROM request_terminal
                   OR NEW.shift_id IS DISTINCT FROM request_shift THEN
                    RAISE EXCEPTION
                        'Refund transition provenance does not match request %', request_uuid;
                END IF;
                IF TG_TABLE_NAME = 'pos_refund_provider_settlements' THEN
                    IF NEW.settlement_method IS DISTINCT FROM request_method THEN
                        RAISE EXCEPTION
                            'Provider settlement rail does not match request %', request_uuid;
                    END IF;
                    transition_actor := NEW.settled_by;
                    -- provider_settled_at is external evidence and may carry
                    -- device/provider clock skew. created_at is the server
                    -- fact time used for the workflow provenance check.
                    transition_at := NEW.created_at;
                ELSIF TG_TABLE_NAME = 'pos_refund_cash_handoff_completions' THEN
                    transition_actor := NEW.recorded_by;
                    -- handed_over_at is client evidence and may carry clock
                    -- skew. recorded_at is the durable server fact time.
                    transition_at := NEW.recorded_at;
                ELSIF TG_TABLE_NAME IN (
                    'pos_refund_cash_handoffs',
                    'pos_refund_provider_payout_starts'
                ) THEN
                    transition_actor := NEW.started_by;
                    transition_at := NEW.started_at;
                ELSE
                    transition_actor := NEW.withdrawn_by;
                    transition_at := NEW.withdrawn_at;
                END IF;
            END IF;

            IF transition_at < request_accepted_at
               OR transition_at > clock_timestamp() + interval '5 minutes' THEN
                RAISE EXCEPTION
                    'POS refund transition time is outside its accepted workflow';
            END IF;
            IF TG_TABLE_NAME IN (
                'pos_refund_cash_handoff_completions',
                'pos_refund_provider_settlements'
            ) AND transition_at < guard_action_started_at THEN
                RAISE EXCEPTION
                    'POS refund completion time predates its server-confirmed action start';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = transition_actor
                   AND u.company_id = request_company
                   AND u.status = 'active'
                   AND u.deleted_at IS NULL
            ) THEN
                RAISE EXCEPTION
                    'POS refund transition actor is outside the request company or inactive';
            END IF;

            IF TG_TABLE_NAME = 'refunds' THEN
                IF NEW.customer_spend_reconciled IS NULL
                   OR NEW.captured_time_reconciled IS NULL THEN
                    RAISE EXCEPTION
                        'Forward POS refund reconciliation flags cannot be NULL';
                ELSIF NEW.receipt_issued_at < NEW.settled_at
                   OR NEW.receipt_issued_at > clock_timestamp() + interval '5 minutes' THEN
                    RAISE EXCEPTION
                        'POS refund receipt issue time is outside accounting finalization';
                ELSIF NEW.settled_at < guard_action_started_at THEN
                    RAISE EXCEPTION
                        'Refund accounting time precedes its server-confirmed action start';
                ELSIF request_method = 'cash' AND (
                    NEW.external_reference IS NOT NULL
                    OR NEW.provider_settled_at IS NOT NULL
                ) THEN
                    RAISE EXCEPTION
                        'Cash refund % contains provider evidence', request_uuid;
                ELSIF request_method = 'cash' AND NOT EXISTS (
                    SELECT 1 FROM pos_refund_cash_handoff_completions cc
                    WHERE cc.refund_request_id = request_uuid
                      AND cc.handed_over_at = NEW.client_occurred_at
                      AND cc.recorded_at = NEW.settled_at
                      AND cc.captured_time_reconciled =
                          NEW.captured_time_reconciled
                ) THEN
                    RAISE EXCEPTION
                        'Cash refund % has no matching durable handover completion',
                        request_uuid;
                ELSIF request_method <> 'cash' AND NOT EXISTS (
                    SELECT 1 FROM pos_refund_provider_settlements ps
                    WHERE ps.refund_request_id = request_uuid
                      AND ps.external_reference = NEW.external_reference
                      AND ps.provider_settled_at = NEW.provider_settled_at
                      AND ps.created_at = NEW.settled_at
                      AND ps.captured_time_reconciled =
                          NEW.captured_time_reconciled
                      AND ps.provider_evidence_reconciled =
                          NEW.provider_evidence_reconciled
                ) THEN
                    RAISE EXCEPTION
                        'Provider refund % has no matching completion fact', request_uuid;
                END IF;
            ELSIF TG_TABLE_NAME = 'pos_refund_withdrawals' THEN
                IF NEW.resolution IN (
                    'cash_handoff_abandoned', 'provider_payout_abandoned'
                ) AND NEW.withdrawn_at < guard_action_started_at THEN
                    RAISE EXCEPTION
                        'Refund resolution time precedes its server-confirmed action start';
                ELSIF NEW.resolution = 'provider_payout_abandoned'
                   AND NEW.verified_at < guard_action_started_at THEN
                    RAISE EXCEPTION
                        'Provider verification predates the payout start';
                END IF;
            END IF;
            RETURN NEW;
        END $$
        """
    )
    for table_name, trigger_name in (
        ("refunds", "trg_refunds_transition_guard"),
        ("pos_refund_cash_handoffs", "trg_pos_refund_cash_handoffs_transition_guard"),
        (
            "pos_refund_cash_handoff_completions",
            "trg_pos_refund_cash_completions_transition_guard",
        ),
        (
            "pos_refund_provider_payout_starts",
            "trg_pos_refund_provider_starts_transition_guard",
        ),
        (
            "pos_refund_provider_settlements",
            "trg_pos_refund_provider_settlements_transition_guard",
        ),
        ("pos_refund_withdrawals", "trg_pos_refund_withdrawals_transition_guard"),
    ):
        op.execute(
            f"""
            CREATE TRIGGER {trigger_name}
            BEFORE INSERT ON {table_name}
            FOR EACH ROW EXECUTE FUNCTION dcompany_guard_pos_refund_transition()
            """
        )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_customer_spend_reconciliation()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            current_total bigint;
            source_amount bigint;
            source_customer uuid;
            source_company uuid;
            source_refund_company uuid;
            source_reconciled boolean;
            source_settled_at timestamptz;
            source_order uuid;
            source_order_status text;
            source_order_total bigint;
            source_approved_by uuid;
            source_paid bigint;
            source_order_refunds bigint;
            expected_source_state text;
            expected_pos_gross bigint;
            expected_membership_gross bigint;
            expected_pos_refunds bigint;
            expected_membership_refunds bigint;
            expected_after bigint;
        BEGIN
            SELECT total_spent_minor INTO current_total
              FROM customers
             WHERE id = NEW.customer_id AND company_id = NEW.company_id
             FOR UPDATE;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'Customer spend reconciliation has invalid customer';
            END IF;
            IF current_total < 0 THEN
                RAISE EXCEPTION
                    'Customer spend reconciliation cannot normalize a negative accumulator';
            END IF;

            IF NEW.pos_refund_id IS NOT NULL THEN
                SELECT r.amount_minor, o.customer_id, o.company_id, r.company_id,
                       r.customer_spend_reconciled, o.id, o.status, o.total_minor,
                       r.approved_by,
                       COALESCE(r.settled_at, r.created_at)
                  INTO source_amount, source_customer, source_company,
                       source_refund_company, source_reconciled, source_order,
                       source_order_status, source_order_total, source_approved_by,
                       source_settled_at
                  FROM refunds r
                  JOIN orders o ON o.id = r.order_id
                 WHERE r.id = NEW.pos_refund_id;
            ELSE
                SELECT s.amount_minor, cm.customer_id, s.company_id,
                       s.customer_spend_reconciled, s.settled_at
                  INTO source_amount, source_customer, source_company,
                       source_reconciled, source_settled_at
                  FROM membership_refund_settlements s
                  JOIN membership_payments mp ON mp.id = s.payment_id
                  JOIN customer_memberships cm ON cm.id = mp.membership_id
                 WHERE s.id = NEW.membership_refund_settlement_id;
            END IF;
            IF source_amount IS NULL
               OR source_customer IS DISTINCT FROM NEW.customer_id
               OR source_company IS DISTINCT FROM NEW.company_id
               OR source_amount <= 0
               OR source_amount IS DISTINCT FROM NEW.source_amount_minor THEN
                RAISE EXCEPTION
                    'Customer spend reconciliation source is invalid or not pending';
            END IF;
            IF NEW.pos_refund_id IS NOT NULL THEN
                IF source_refund_company IS NOT NULL
                   AND source_refund_company IS DISTINCT FROM source_company THEN
                    RAISE EXCEPTION
                        'Legacy POS refund company does not match its linked order';
                END IF;
                IF source_order_status NOT IN ('paid', 'refunded')
                   OR NOT EXISTS (
                       SELECT 1 FROM users u
                        WHERE u.id = source_approved_by
                          AND u.company_id = NEW.company_id
                   ) THEN
                    RAISE EXCEPTION
                        'Legacy POS refund order or approver provenance is invalid';
                END IF;
                SELECT COALESCE(sum(p.amount_minor), 0) INTO source_paid
                  FROM payments p WHERE p.order_id = source_order;
                SELECT COALESCE(sum(r.amount_minor), 0) INTO source_order_refunds
                  FROM refunds r WHERE r.order_id = source_order;
                IF source_paid <= 0
                   OR source_paid IS DISTINCT FROM source_order_total
                   OR source_amount > source_paid
                   OR source_order_refunds > source_paid THEN
                    RAISE EXCEPTION
                        'Legacy POS refund exceeds verified order collections';
                END IF;
                IF source_reconciled IS FALSE THEN
                    expected_source_state := 'unreconciled';
                ELSIF source_reconciled IS NULL THEN
                    expected_source_state := 'legacy_unknown';
                ELSE
                    RAISE EXCEPTION
                        'Customer spend reconciliation source is already reconciled';
                END IF;
            ELSE
                IF source_reconciled IS NOT FALSE THEN
                    RAISE EXCEPTION
                        'Membership customer spend source is not pending reconciliation';
                END IF;
                expected_source_state := 'unreconciled';
            END IF;
            IF NEW.source_reconciliation_state IS DISTINCT FROM expected_source_state THEN
                RAISE EXCEPTION
                    'Customer spend reconciliation source state is incorrect';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.reconciled_by
                   AND u.company_id = NEW.company_id
                   AND u.status = 'active'
                   AND u.deleted_at IS NULL
            ) THEN
                RAISE EXCEPTION
                    'Customer spend reconciliation actor is outside the company or inactive';
            END IF;
            IF NEW.reconciled_at < source_settled_at
               OR NEW.reconciled_at > clock_timestamp() + interval '5 minutes' THEN
                RAISE EXCEPTION
                    'Customer spend reconciliation time is outside the settlement history';
            END IF;
            IF current_total IS DISTINCT FROM NEW.before_total_spent_minor THEN
                RAISE EXCEPTION
                    'Customer spend changed before reconciliation';
            END IF;

            SELECT COALESCE(sum(o.total_minor), 0) INTO expected_pos_gross
              FROM orders o
             WHERE o.company_id = NEW.company_id
               AND o.customer_id = NEW.customer_id
               AND o.status IN ('paid', 'refunded');
            SELECT COALESCE(sum(r.amount_minor), 0) INTO expected_pos_refunds
              FROM refunds r
              JOIN orders o ON o.id = r.order_id
             WHERE o.company_id = NEW.company_id
               AND o.customer_id = NEW.customer_id;
            SELECT COALESCE(sum(mp.amount_minor), 0)
              INTO expected_membership_gross
              FROM membership_payments mp
              JOIN customer_memberships cm ON cm.id = mp.membership_id
             WHERE mp.company_id = NEW.company_id
               AND cm.customer_id = NEW.customer_id;
            SELECT COALESCE(sum(s.amount_minor), 0)
              INTO expected_membership_refunds
              FROM membership_refund_settlements s
              JOIN membership_payments mp ON mp.id = s.payment_id
              JOIN customer_memberships cm ON cm.id = mp.membership_id
             WHERE s.company_id = NEW.company_id
               AND cm.customer_id = NEW.customer_id;
            expected_after := expected_pos_gross + expected_membership_gross
                - expected_pos_refunds - expected_membership_refunds;
            IF expected_after < 0 THEN
                RAISE EXCEPTION
                    'Normalized customer spend is negative; repair source attribution first';
            END IF;
            IF NEW.pos_gross_minor IS DISTINCT FROM expected_pos_gross
               OR NEW.membership_gross_minor IS DISTINCT FROM expected_membership_gross
               OR NEW.pos_refunds_minor IS DISTINCT FROM expected_pos_refunds
               OR NEW.membership_refunds_minor IS DISTINCT FROM expected_membership_refunds
               OR NEW.after_total_spent_minor IS DISTINCT FROM expected_after
               OR NEW.adjustment_minor IS DISTINCT FROM (
                   expected_after - current_total
               ) THEN
                RAISE EXCEPTION
                    'Customer spend reconciliation does not match normalized ledger';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_customer_spend_reconciliation_guard
        BEFORE INSERT ON customer_spend_reconciliations
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_customer_spend_reconciliation()
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_require_customer_spend_reconciled()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF NOT EXISTS (
                SELECT 1 FROM customers c
                 WHERE c.id = NEW.customer_id
                   AND c.company_id = NEW.company_id
                   AND c.total_spent_minor = NEW.after_total_spent_minor
            ) THEN
                RAISE EXCEPTION
                    'Customer accumulator was not updated with reconciliation %', NEW.id;
            END IF;
            RETURN NULL;
        END $$
        """
    )
    op.execute(
        """
        CREATE CONSTRAINT TRIGGER trg_customer_spend_reconciliation_applied
        AFTER INSERT ON customer_spend_reconciliations
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION dcompany_require_customer_spend_reconciled()
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_pos_refund_evidence_reconciliation()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_company uuid;
            source_settled_at timestamptz;
            source_provider_reconciled boolean;
            source_time_reconciled boolean;
        BEGIN
            SELECT r.company_id, COALESCE(r.settled_at, r.created_at),
                   r.provider_evidence_reconciled, r.captured_time_reconciled
              INTO source_company, source_settled_at,
                   source_provider_reconciled, source_time_reconciled
              FROM refunds r
             WHERE r.id = NEW.refund_id
               AND r.request_id IS NOT NULL;
            IF NOT FOUND OR source_company IS DISTINCT FROM NEW.company_id THEN
                RAISE EXCEPTION
                    'POS refund evidence reconciliation source is invalid';
            END IF;
            IF NEW.evidence_kind = 'provider_reference' THEN
                IF source_provider_reconciled IS DISTINCT FROM FALSE THEN
                    RAISE EXCEPTION
                        'POS refund provider evidence is not pending reconciliation';
                END IF;
            ELSIF NEW.evidence_kind = 'captured_time' THEN
                IF source_time_reconciled IS DISTINCT FROM FALSE THEN
                    RAISE EXCEPTION
                        'POS refund captured time is not pending reconciliation';
                END IF;
            ELSE
                RAISE EXCEPTION 'Unsupported POS refund evidence kind';
            END IF;
            IF NEW.reconciled_at < source_settled_at
               OR NEW.reconciled_at > clock_timestamp() + interval '5 minutes' THEN
                RAISE EXCEPTION
                    'POS refund evidence review time is outside the settlement history';
            END IF;
            IF NOT EXISTS (
                SELECT 1 FROM users u
                 WHERE u.id = NEW.reconciled_by
                   AND u.company_id = NEW.company_id
                   AND u.status = 'active'
                   AND u.deleted_at IS NULL
            ) THEN
                RAISE EXCEPTION
                    'POS refund evidence reviewer is outside the refund company';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_pos_refund_evidence_reconciliation_guard
        BEFORE INSERT ON pos_refund_evidence_reconciliations
        FOR EACH ROW
        EXECUTE FUNCTION dcompany_guard_pos_refund_evidence_reconciliation()
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_pos_refund_immutable()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION '% is append-only; % is forbidden', TG_TABLE_NAME, TG_OP;
        END $$
        """
    )
    for table_name, trigger_name in (
        ("refunds", "trg_refunds_immutable"),
        ("pos_refund_requests", "trg_pos_refund_requests_immutable"),
        ("pos_refund_cash_handoffs", "trg_pos_refund_cash_handoffs_immutable"),
        (
            "pos_refund_cash_handoff_completions",
            "trg_pos_refund_cash_completions_immutable",
        ),
        (
            "pos_refund_provider_settlements",
            "trg_pos_refund_provider_settlements_immutable",
        ),
        (
            "pos_refund_provider_payout_starts",
            "trg_pos_refund_provider_starts_immutable",
        ),
        ("pos_refund_withdrawals", "trg_pos_refund_withdrawals_immutable"),
        (
            "customer_spend_reconciliations",
            "trg_customer_spend_reconciliations_immutable",
        ),
        (
            "pos_refund_evidence_reconciliations",
            "trg_pos_refund_evidence_reconciliations_immutable",
        ),
    ):
        op.execute(
            f"""
            CREATE TRIGGER {trigger_name}
            BEFORE UPDATE OR DELETE ON {table_name}
            FOR EACH ROW EXECUTE FUNCTION dcompany_guard_pos_refund_immutable()
            """
        )


def downgrade() -> None:
    # 0035 cannot represent provider-start provenance, post-start resolutions,
    # or captured-time evidence. Fail closed after any 0036 workflow write
    # instead of silently deleting append-only financial history.
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (SELECT 1 FROM pos_refund_evidence_reconciliations)
               OR EXISTS (SELECT 1 FROM customer_spend_reconciliations)
               OR EXISTS (SELECT 1 FROM pos_refund_cash_handoff_completions)
               OR EXISTS (SELECT 1 FROM pos_refund_provider_payout_starts)
               OR EXISTS (
                   SELECT 1 FROM refunds
                   WHERE captured_time_reconciled IS NOT NULL
               )
               OR EXISTS (
                   SELECT 1 FROM pos_refund_withdrawals
                   WHERE resolution <> 'cash_not_handed_over'
               )
               OR EXISTS (
                SELECT 1
                FROM pos_refund_requests pr
                LEFT JOIN refunds r ON r.request_id = pr.id
                WHERE pr.settlement_method <> 'cash' AND r.id IS NULL
            ) THEN
                RAISE EXCEPTION
                    'Cannot downgrade 0036 after new POS refund workflow activity';
            END IF;
        END $$
        """
    )
    # Restore the 0035 write contract explicitly.  The constraint is NOT VALID
    # only to preserve migrated rows; leaving it behind would silently change
    # 0035 semantics after a successful rollback.
    op.drop_constraint(
        "ck_refund_forward_write_linkage", "refunds", type_="check"
    )
    for table_name, trigger_name in (
        ("refunds", "trg_refunds_immutable"),
        ("pos_refund_requests", "trg_pos_refund_requests_immutable"),
        ("pos_refund_cash_handoffs", "trg_pos_refund_cash_handoffs_immutable"),
        (
            "pos_refund_cash_handoff_completions",
            "trg_pos_refund_cash_completions_immutable",
        ),
        (
            "pos_refund_provider_settlements",
            "trg_pos_refund_provider_settlements_immutable",
        ),
        (
            "pos_refund_provider_payout_starts",
            "trg_pos_refund_provider_starts_immutable",
        ),
        ("pos_refund_withdrawals", "trg_pos_refund_withdrawals_immutable"),
        (
            "customer_spend_reconciliations",
            "trg_customer_spend_reconciliations_immutable",
        ),
        (
            "pos_refund_evidence_reconciliations",
            "trg_pos_refund_evidence_reconciliations_immutable",
        ),
    ):
        op.execute(f"DROP TRIGGER IF EXISTS {trigger_name} ON {table_name}")
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_pos_refund_immutable()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_pos_refund_request_provenance "
        "ON pos_refund_requests"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_validate_pos_refund_request()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_customer_spend_reconciliation_applied "
        "ON customer_spend_reconciliations"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS dcompany_require_customer_spend_reconciled()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_customer_spend_reconciliation_guard "
        "ON customer_spend_reconciliations"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS dcompany_guard_customer_spend_reconciliation()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_pos_refund_evidence_reconciliation_guard "
        "ON pos_refund_evidence_reconciliations"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "dcompany_guard_pos_refund_evidence_reconciliation()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS trg_pos_refund_provider_settlement_pair "
        "ON pos_refund_provider_settlements"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_require_pos_provider_refund_pair()")
    for table_name, trigger_name in (
        ("refunds", "trg_refunds_transition_guard"),
        ("pos_refund_cash_handoffs", "trg_pos_refund_cash_handoffs_transition_guard"),
        (
            "pos_refund_cash_handoff_completions",
            "trg_pos_refund_cash_completions_transition_guard",
        ),
        (
            "pos_refund_provider_payout_starts",
            "trg_pos_refund_provider_starts_transition_guard",
        ),
        (
            "pos_refund_provider_settlements",
            "trg_pos_refund_provider_settlements_transition_guard",
        ),
        ("pos_refund_withdrawals", "trg_pos_refund_withdrawals_transition_guard"),
    ):
        op.execute(f"DROP TRIGGER IF EXISTS {trigger_name} ON {table_name}")
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_pos_refund_transition()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_pos_refund_request_create_workflow_guard "
        "ON pos_refund_requests"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_create_pos_refund_workflow_guard()")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_pos_refund_workflow_guards_internal "
        "ON pos_refund_workflow_guards"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS dcompany_protect_pos_refund_workflow_guard()"
    )
    op.drop_index(
        "ix_pos_refund_workflow_guards_company_id",
        table_name="pos_refund_workflow_guards",
    )
    op.drop_table("pos_refund_workflow_guards")
    # Restore the exact evidence representation required by 0034 before its
    # check constraint is recreated.
    op.execute(
        """
        UPDATE pos_refund_requests pr
           SET external_reference = ps.external_reference,
               provider_settled_at = ps.provider_settled_at
          FROM pos_refund_provider_settlements ps
         WHERE ps.refund_request_id = pr.id
           AND pr.settlement_method <> 'cash'
        """
    )

    op.drop_index(
        "ix_pos_refund_evidence_reconciliations_refund_id",
        table_name="pos_refund_evidence_reconciliations",
    )
    op.drop_index(
        "ix_pos_refund_evidence_reconciliations_company_id",
        table_name="pos_refund_evidence_reconciliations",
    )
    op.drop_table("pos_refund_evidence_reconciliations")

    op.drop_index(
        "ix_customer_spend_reconciliation_customer",
        table_name="customer_spend_reconciliations",
    )
    op.drop_index(
        "ix_customer_spend_reconciliations_company_id",
        table_name="customer_spend_reconciliations",
    )
    op.drop_table("customer_spend_reconciliations")

    op.drop_index(
        "ix_pos_refund_cash_completion_shift",
        table_name="pos_refund_cash_handoff_completions",
    )
    op.drop_index(
        "ix_pos_refund_cash_completion_company_id",
        table_name="pos_refund_cash_handoff_completions",
    )
    op.drop_table("pos_refund_cash_handoff_completions")

    op.drop_index(
        "ix_pos_refund_provider_settlement_shift",
        table_name="pos_refund_provider_settlements",
    )
    op.drop_index(
        "ix_pos_refund_provider_settlements_company_id",
        table_name="pos_refund_provider_settlements",
    )
    op.drop_table("pos_refund_provider_settlements")
    op.drop_index(
        "ix_pos_refund_provider_start_shift",
        table_name="pos_refund_provider_payout_starts",
    )
    op.drop_index(
        "ix_pos_refund_provider_payout_starts_company_id",
        table_name="pos_refund_provider_payout_starts",
    )
    op.drop_table("pos_refund_provider_payout_starts")

    op.drop_constraint(
        "ck_pos_refund_withdrawal_verification",
        "pos_refund_withdrawals",
        type_="check",
    )
    op.drop_constraint(
        "ck_pos_refund_withdrawal_resolution",
        "pos_refund_withdrawals",
        type_="check",
    )
    op.create_check_constraint(
        "ck_pos_refund_withdrawal_resolution",
        "pos_refund_withdrawals",
        "resolution = 'cash_not_handed_over'",
    )
    op.drop_column("pos_refund_withdrawals", "verified_at")
    op.drop_column("pos_refund_withdrawals", "verification_status")
    op.drop_column("pos_refund_withdrawals", "verification_reference")
    op.create_unique_constraint(
        "uq_pos_refund_request_provider_reference",
        "pos_refund_requests",
        ["company_id", "settlement_method", "external_reference"],
    )
    op.create_check_constraint(
        "ck_pos_refund_request_external_provenance",
        "pos_refund_requests",
        "(settlement_method = 'cash' AND external_reference IS NULL "
        "AND provider_settled_at IS NULL) OR "
        "(settlement_method <> 'cash' "
        "AND char_length(trim(external_reference)) >= 3 "
        "AND provider_settled_at IS NOT NULL)",
    )
    op.drop_column("refunds", "captured_time_reconciled")
    op.drop_constraint(
        "ck_refund_request_external_provenance", "refunds", type_="check"
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
    op.drop_column("refunds", "provider_evidence_reconciled")
    op.drop_column("refunds", "client_occurred_at")
    op.drop_column("refunds", "customer_spend_reconciled")
