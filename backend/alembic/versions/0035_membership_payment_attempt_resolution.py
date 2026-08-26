"""Reserve membership money before collection and add auditable recovery.

Revision ID: 0035
Revises: 0034
Create Date: 2026-08-26

This migration is schema-only.  It never infers a payment from an entitlement
or old outbox row and never rewrites a drawer, report, or customer balance.
New clients first create a server-visible zero-value reservation, then collect
money and settle it.  The legacy-attempt resolution table is only the audited
escape hatch for rejected pre-reservation client actions.
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0035"
down_revision = "0034"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "membership_payment_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "customer_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "tier_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_tiers.id", ondelete="RESTRICT"),
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
        sa.Column("billing_cycle", sa.String(length=10), nullable=False),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("customer_name_snapshot", sa.String(length=100)),
        sa.Column("customer_phone_snapshot", sa.String(length=20), nullable=False),
        sa.Column("tier_code_snapshot", sa.String(length=20), nullable=False),
        sa.Column("tier_name_snapshot", sa.String(length=100), nullable=False),
        sa.Column("accepted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "prepared_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("client_action_id", sa.String(length=160), nullable=False),
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
            "amount_minor > 0",
            name="ck_membership_payment_request_positive_amount",
        ),
        sa.CheckConstraint(
            "billing_cycle = 'monthly'",
            name="ck_membership_payment_request_monthly_only",
        ),
        sa.CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_payment_request_method",
        ),
        sa.UniqueConstraint(
            "company_id",
            "client_action_id",
            name="uq_membership_payment_request_client_action",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_request_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_payment_requests_company_id",
        "membership_payment_requests",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_payment_request_shift",
        "membership_payment_requests",
        ["shift_id"],
    )
    op.create_index(
        "ix_membership_payment_request_customer_accepted",
        "membership_payment_requests",
        ["customer_id", "accepted_at"],
    )

    op.create_table(
        "membership_payment_cash_collections",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
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
            "request_id",
            name="uq_membership_payment_cash_collection_request",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_cash_collection_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_payment_cash_collections_company_id",
        "membership_payment_cash_collections",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_payment_cash_collection_shift",
        "membership_payment_cash_collections",
        ["shift_id"],
    )

    op.create_table(
        "membership_payment_provider_actions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
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
        sa.Column("method", sa.String(length=20), nullable=False),
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
        sa.CheckConstraint(
            "method IN ('card', 'upi', 'razorpay')",
            name="ck_membership_payment_provider_action_method",
        ),
        sa.UniqueConstraint(
            "request_id",
            name="uq_membership_payment_provider_action_request",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_provider_action_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_payment_provider_actions_company_id",
        "membership_payment_provider_actions",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_payment_provider_action_shift",
        "membership_payment_provider_actions",
        ["shift_id"],
    )

    op.create_table(
        "membership_payment_request_resolutions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
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
        sa.Column("paid_via", sa.String(length=20), nullable=False),
        sa.Column("resolution", sa.String(length=40), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("external_reference", sa.String(length=200)),
        sa.Column(
            "provider_evidence_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "resolved_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "action_state_verified",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column("provider_verification_status", sa.String(length=40)),
        sa.Column("provider_verification_reference", sa.String(length=200)),
        sa.Column("provider_checked_at", sa.DateTime(timezone=True)),
        sa.Column("provider_evidence_occurred_at", sa.DateTime(timezone=True)),
        sa.Column(
            "provider_evidence_time_untrusted",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column(
            "cash_return_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column(
            "action_takeover_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column("action_takeover_reason", sa.String(length=500)),
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
            "(paid_via = 'cash' AND resolution IN "
            "('payment_not_collected', 'cash_not_collected', 'cash_returned') "
            "AND external_reference IS NULL) OR "
            "(paid_via <> 'cash' AND resolution IN "
            "('payment_not_collected', 'provider_not_completed') "
            "AND external_reference IS NULL) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_reversed' "
            "AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_payment_request_resolution_evidence",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_payment_request_resolution_reason",
        ),
        sa.CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_payment_request_resolution_takeover",
        ),
        sa.UniqueConstraint(
            "request_id",
            name="uq_membership_payment_request_resolution_request",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_request_resolution_idempotency",
        ),
        sa.UniqueConstraint(
            "company_id",
            "paid_via",
            "external_reference",
            name="uq_membership_payment_request_resolution_provider_reference",
        ),
    )
    op.create_index(
        "ix_membership_payment_request_resolutions_company_id",
        "membership_payment_request_resolutions",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_payment_request_resolution_shift",
        "membership_payment_request_resolutions",
        ["shift_id"],
    )

    op.create_table(
        "membership_payment_completions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "cash_collection_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey(
                "membership_payment_cash_collections.id", ondelete="RESTRICT"
            ),
        ),
        sa.Column(
            "provider_action_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey(
                "membership_payment_provider_actions.id", ondelete="RESTRICT"
            ),
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
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "completed_by",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("external_reference", sa.String(length=200)),
        sa.Column(
            "provider_evidence_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
        sa.Column("evidence_occurred_at", sa.DateTime(timezone=True)),
        sa.Column(
            "evidence_time_untrusted",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column(
            "action_takeover_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column("action_takeover_reason", sa.String(length=500)),
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
            "amount_minor > 0", name="ck_membership_payment_completion_positive"
        ),
        sa.CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_payment_completion_method",
        ),
        sa.CheckConstraint(
            "(cash_collection_id IS NOT NULL AND provider_action_id IS NULL "
            "AND method = 'cash' AND external_reference IS NULL) OR "
            "(cash_collection_id IS NULL AND provider_action_id IS NOT NULL "
            "AND method <> 'cash' AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_payment_completion_action",
        ),
        sa.CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_payment_completion_takeover",
        ),
        sa.UniqueConstraint(
            "request_id", name="uq_membership_payment_completion_request"
        ),
        sa.UniqueConstraint(
            "cash_collection_id", name="uq_membership_payment_completion_cash_action"
        ),
        sa.UniqueConstraint(
            "provider_action_id",
            name="uq_membership_payment_completion_provider_action",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_payment_completion_idempotency",
        ),
        sa.UniqueConstraint(
            "company_id", "method", "external_reference",
            name="uq_membership_payment_completion_provider_reference",
        ),
    )
    op.create_index(
        "ix_membership_payment_completions_company_id",
        "membership_payment_completions",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_payment_completion_shift",
        "membership_payment_completions",
        ["shift_id"],
    )

    op.add_column(
        "membership_payments",
        sa.Column("request_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "membership_payments",
        sa.Column("completion_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.add_column(
        "membership_payments",
        sa.Column("external_reference", sa.String(length=200), nullable=True),
    )
    op.add_column(
        "membership_payments",
        sa.Column(
            "provider_evidence_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )
    op.add_column(
        "membership_payments",
        sa.Column("evidence_occurred_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "membership_payments",
        sa.Column(
            "evidence_time_untrusted",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_payments",
        sa.Column(
            "customer_spend_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_payments",
        sa.Column(
            "action_takeover_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_payments",
        sa.Column("action_takeover_reason", sa.String(length=500), nullable=True),
    )
    op.create_foreign_key(
        "fk_membership_payments_request_id",
        "membership_payments",
        "membership_payment_requests",
        ["request_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_foreign_key(
        "fk_membership_payments_completion_id",
        "membership_payments",
        "membership_payment_completions",
        ["completion_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_unique_constraint(
        "uq_membership_payment_request",
        "membership_payments",
        ["request_id"],
    )
    op.create_unique_constraint(
        "uq_membership_payment_completion",
        "membership_payments",
        ["completion_id"],
    )
    op.create_unique_constraint(
        "uq_membership_payment_provider_reference",
        "membership_payments",
        ["company_id", "method", "external_reference"],
    )
    # Rows written by 0033 predate the reservation workflow and legitimately
    # have neither link.  NOT VALID preserves those immutable historical rows,
    # while PostgreSQL still enforces the constraint for every INSERT and every
    # subsequently touched row.  Fresh databases therefore have no path for a
    # new settlement to bypass the request/completion guards with NULL links.
    op.execute(
        """
        ALTER TABLE membership_payments
        ADD CONSTRAINT ck_membership_payment_workflow_linkage
        CHECK (request_id IS NOT NULL AND completion_id IS NOT NULL)
        NOT VALID
        """
    )
    op.create_check_constraint(
        "ck_membership_payment_request_evidence",
        "membership_payments",
        "request_id IS NULL OR "
        "(method = 'cash' AND external_reference IS NULL) OR "
        "(method <> 'cash' AND char_length(trim(external_reference)) >= 1)",
    )
    op.create_check_constraint(
        "ck_membership_payment_action_takeover",
        "membership_payments",
        "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
        "(action_takeover_confirmed = true AND "
        "char_length(trim(action_takeover_reason)) >= 3)",
    )

    op.create_table(
        "membership_refund_cash_handoffs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "refund_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
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
            "refund_id",
            name="uq_membership_refund_cash_handoff_refund",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_refund_cash_handoff_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_refund_cash_handoffs_company_id",
        "membership_refund_cash_handoffs",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_refund_cash_handoff_shift",
        "membership_refund_cash_handoffs",
        ["shift_id"],
    )

    op.create_table(
        "membership_refund_provider_actions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "refund_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
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
        sa.Column("method", sa.String(length=20), nullable=False),
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
        sa.CheckConstraint(
            "method IN ('card', 'upi', 'razorpay')",
            name="ck_membership_refund_provider_action_method",
        ),
        sa.UniqueConstraint(
            "refund_id",
            name="uq_membership_refund_provider_action_refund",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_refund_provider_action_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_refund_provider_actions_company_id",
        "membership_refund_provider_actions",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_refund_provider_action_shift",
        "membership_refund_provider_actions",
        ["shift_id"],
    )

    op.drop_constraint(
        "ck_membership_refund_settlement_external_reference",
        "membership_refund_settlements",
        type_="check",
    )
    op.create_check_constraint(
        "ck_membership_refund_settlement_external_reference",
        "membership_refund_settlements",
        "(method = 'cash' AND external_ref IS NULL) OR "
        "(method <> 'cash' AND char_length(trim(external_ref)) >= 1)",
    )
    op.create_unique_constraint(
        "uq_membership_refund_settlement_provider_reference",
        "membership_refund_settlements",
        ["company_id", "method", "external_ref"],
    )
    op.add_column(
        "membership_refund_settlements",
        sa.Column("evidence_occurred_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "membership_refund_settlements",
        sa.Column(
            "provider_evidence_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )
    op.add_column(
        "membership_refund_settlements",
        sa.Column(
            "evidence_time_untrusted",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_refund_settlements",
        sa.Column(
            "customer_spend_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_refund_settlements",
        sa.Column(
            "action_takeover_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_refund_settlements",
        sa.Column("action_takeover_reason", sa.String(length=500), nullable=True),
    )
    op.create_check_constraint(
        "ck_membership_refund_settlement_takeover",
        "membership_refund_settlements",
        "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
        "(action_takeover_confirmed = true AND "
        "char_length(trim(action_takeover_reason)) >= 3)",
    )

    op.drop_constraint(
        "ck_membership_refund_resolution_type",
        "membership_refund_resolutions",
        type_="check",
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column("paid_via", sa.String(length=20), nullable=True),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column("external_reference", sa.String(length=200), nullable=True),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column(
            "provider_evidence_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column(
            "action_state_verified",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column("provider_verification_status", sa.String(length=40), nullable=True),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column("provider_verification_reference", sa.String(length=200), nullable=True),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column("provider_checked_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column("provider_evidence_occurred_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column(
            "provider_evidence_time_untrusted",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column(
            "cash_return_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column(
            "action_takeover_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    op.add_column(
        "membership_refund_resolutions",
        sa.Column("action_takeover_reason", sa.String(length=500), nullable=True),
    )
    op.execute(
        """
        UPDATE membership_refund_resolutions AS resolution
           SET paid_via = refund.method
          FROM membership_refunds AS refund
         WHERE refund.id = resolution.refund_id
        """
    )
    op.alter_column(
        "membership_refund_resolutions",
        "paid_via",
        existing_type=sa.String(length=20),
        nullable=False,
    )
    op.create_check_constraint(
        "ck_membership_refund_resolution_type",
        "membership_refund_resolutions",
        "(paid_via = 'cash' AND resolution IN "
        "('cash_not_handed_over', 'cash_returned') "
        "AND external_reference IS NULL) OR "
        "(paid_via <> 'cash' AND resolution = 'provider_not_completed' "
        "AND external_reference IS NULL) OR "
        "(paid_via <> 'cash' AND resolution = 'provider_reversed' "
        "AND char_length(trim(external_reference)) >= 1)",
    )
    op.create_check_constraint(
        "ck_membership_refund_resolution_takeover",
        "membership_refund_resolutions",
        "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
        "(action_takeover_confirmed = true AND "
        "char_length(trim(action_takeover_reason)) >= 3)",
    )
    op.create_unique_constraint(
        "uq_membership_refund_resolution_provider_reference",
        "membership_refund_resolutions",
        ["company_id", "paid_via", "external_reference"],
    )

    op.create_table(
        "membership_payment_attempt_resolutions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "customer_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "tier_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_tiers.id", ondelete="RESTRICT"),
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
        sa.Column("original_client_action_id", sa.String(length=160), nullable=False),
        sa.Column("paid_via", sa.String(length=20), nullable=False),
        sa.Column("expected_amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("resolution", sa.String(length=40), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("external_reference", sa.String(length=200)),
        sa.Column("provider_verification_status", sa.String(length=40)),
        sa.Column("provider_checked_at", sa.DateTime(timezone=True)),
        sa.Column(
            "provider_evidence_reconciled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.true(),
        ),
        sa.Column("evidence_occurred_at", sa.DateTime(timezone=True)),
        sa.Column(
            "evidence_time_untrusted",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column(
            "cash_return_confirmed",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "resolved_by",
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
            "expected_amount_minor > 0",
            name="ck_membership_attempt_resolution_positive_amount",
        ),
        sa.CheckConstraint(
            "paid_via IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_attempt_resolution_method",
        ),
        sa.CheckConstraint(
            "(paid_via = 'cash' AND resolution = 'payment_not_collected' "
            "AND external_reference IS NULL AND provider_verification_status IS NULL "
            "AND provider_checked_at IS NULL AND cash_return_confirmed = false) OR "
            "(paid_via = 'cash' AND resolution = 'cash_returned' "
            "AND external_reference IS NULL AND provider_verification_status IS NULL "
            "AND provider_checked_at IS NULL AND cash_return_confirmed = true) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_not_completed' "
            "AND char_length(trim(external_reference)) >= 1 "
            "AND provider_verification_status = 'not_completed' "
            "AND provider_checked_at IS NOT NULL AND cash_return_confirmed = false) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_reversed' "
            "AND char_length(trim(external_reference)) >= 1 "
            "AND provider_verification_status = 'reversed' "
            "AND provider_checked_at IS NOT NULL AND cash_return_confirmed = false)",
            name="ck_membership_attempt_resolution_evidence",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_attempt_resolution_reason",
        ),
        sa.UniqueConstraint(
            "company_id",
            "original_client_action_id",
            name="uq_membership_attempt_resolution_original_action",
        ),
        sa.UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_attempt_resolution_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_payment_attempt_resolutions_company_id",
        "membership_payment_attempt_resolutions",
        ["company_id"],
    )
    op.create_index(
        "ix_membership_attempt_resolution_shift",
        "membership_payment_attempt_resolutions",
        ["shift_id"],
    )
    op.create_index(
        "ix_membership_attempt_resolution_company_resolved_at",
        "membership_payment_attempt_resolutions",
        ["company_id", "resolved_at"],
    )

    op.create_table(
        "membership_refund_attempt_recoveries",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "customer_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "membership_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customer_memberships.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "payment_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payments.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "source_branch_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "source_terminal_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "source_shift_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("original_client_action_id", sa.String(length=160), nullable=False),
        sa.Column("paid_via", sa.String(length=20), nullable=False),
        sa.Column("expected_amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("captured_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "captured_time_untrusted", sa.Boolean(), nullable=False,
            server_default=sa.false(),
        ),
        sa.Column("registered_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "registered_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
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
            "expected_amount_minor > 0",
            name="ck_membership_refund_attempt_recovery_positive_amount",
        ),
        sa.CheckConstraint(
            "paid_via IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_attempt_recovery_method",
        ),
        sa.UniqueConstraint(
            "company_id", "original_client_action_id",
            name="uq_membership_refund_attempt_recovery_original_action",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_attempt_recovery_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_refund_attempt_recoveries_company_id",
        "membership_refund_attempt_recoveries", ["company_id"],
    )
    op.create_index(
        "ix_membership_refund_attempt_recovery_source_shift",
        "membership_refund_attempt_recoveries", ["source_shift_id"],
    )
    op.create_index(
        "ix_membership_refund_attempt_recovery_payment",
        "membership_refund_attempt_recoveries", ["payment_id"],
    )

    op.create_table(
        "membership_refund_attempt_resolutions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "recovery_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refund_attempt_recoveries.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column(
            "customer_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "membership_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customer_memberships.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "payment_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payments.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "source_branch_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "source_terminal_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "source_shift_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "reconciliation_shift_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"),
        ),
        sa.Column(
            "refund_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
        ),
        sa.Column("original_client_action_id", sa.String(length=160), nullable=False),
        sa.Column("paid_via", sa.String(length=20), nullable=False),
        sa.Column("expected_amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("outcome", sa.String(length=40), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("provider_status", sa.String(length=40)),
        sa.Column("verification_reference", sa.String(length=200)),
        sa.Column("checked_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("evidence_occurred_at", sa.DateTime(timezone=True)),
        sa.Column(
            "evidence_time_untrusted", sa.Boolean(), nullable=False,
            server_default=sa.false(),
        ),
        sa.Column(
            "provider_evidence_reconciled", sa.Boolean(), nullable=False,
            server_default=sa.true(),
        ),
        sa.Column(
            "cash_handover_confirmed", sa.Boolean(), nullable=False,
            server_default=sa.false(),
        ),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "resolved_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
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
            "expected_amount_minor > 0",
            name="ck_membership_refund_attempt_positive_amount",
        ),
        sa.CheckConstraint(
            "paid_via IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_attempt_method",
        ),
        sa.CheckConstraint(
            "outcome IN ('no_payout', 'cash_not_handed_over', 'cash_handed_over', "
            "'provider_reversed', 'provider_completed')",
            name="ck_membership_refund_attempt_outcome",
        ),
        sa.CheckConstraint(
            "(outcome = 'cash_not_handed_over' AND paid_via = 'cash' "
            "AND refund_id IS NULL AND reconciliation_shift_id IS NULL "
            "AND provider_status IS NULL AND verification_reference IS NULL "
            "AND cash_handover_confirmed = false) OR "
            "(outcome = 'cash_handed_over' AND paid_via = 'cash' "
            "AND refund_id IS NOT NULL AND reconciliation_shift_id IS NOT NULL "
            "AND provider_status IS NULL AND verification_reference IS NULL "
            "AND cash_handover_confirmed = true) OR "
            "(outcome = 'no_payout' AND paid_via <> 'cash' "
            "AND refund_id IS NULL AND reconciliation_shift_id IS NULL "
            "AND provider_status = 'not_completed' "
            "AND char_length(trim(verification_reference)) >= 1 "
            "AND cash_handover_confirmed = false) OR "
            "(outcome = 'provider_reversed' AND paid_via <> 'cash' "
            "AND refund_id IS NULL AND reconciliation_shift_id IS NULL "
            "AND provider_status = 'reversed' "
            "AND char_length(trim(verification_reference)) >= 1 "
            "AND cash_handover_confirmed = false) OR "
            "(outcome = 'provider_completed' AND paid_via <> 'cash' "
            "AND refund_id IS NOT NULL AND reconciliation_shift_id IS NOT NULL "
            "AND provider_status = 'completed' "
            "AND char_length(trim(verification_reference)) >= 1 "
            "AND cash_handover_confirmed = false)",
            name="ck_membership_refund_attempt_evidence",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_refund_attempt_reason",
        ),
        sa.UniqueConstraint(
            "company_id", "original_client_action_id",
            name="uq_membership_refund_attempt_original_action",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_attempt_idempotency",
        ),
        sa.UniqueConstraint("refund_id", name="uq_membership_refund_attempt_refund"),
        sa.UniqueConstraint(
            "recovery_id", name="uq_membership_refund_attempt_resolution_recovery"
        ),
    )
    op.create_index(
        "ix_membership_refund_attempt_resolutions_company_id",
        "membership_refund_attempt_resolutions", ["company_id"],
    )
    op.create_index(
        "ix_membership_refund_attempt_source_shift",
        "membership_refund_attempt_resolutions", ["source_shift_id"],
    )
    op.create_index(
        "ix_membership_refund_attempt_payment",
        "membership_refund_attempt_resolutions", ["payment_id"],
    )

    op.create_table(
        "membership_refund_completions",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "refund_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refunds.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "cash_handoff_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refund_cash_handoffs.id", ondelete="RESTRICT"),
        ),
        sa.Column(
            "provider_action_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refund_provider_actions.id", ondelete="RESTRICT"),
        ),
        sa.Column(
            "legacy_attempt_resolution_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey(
                "membership_refund_attempt_resolutions.id", ondelete="RESTRICT"
            ),
        ),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "branch_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "terminal_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "shift_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("method", sa.String(length=20), nullable=False),
        sa.Column("amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "completed_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("external_reference", sa.String(length=200)),
        sa.Column(
            "provider_evidence_reconciled", sa.Boolean(), nullable=False,
            server_default=sa.true(),
        ),
        sa.Column("evidence_occurred_at", sa.DateTime(timezone=True)),
        sa.Column(
            "evidence_time_untrusted", sa.Boolean(), nullable=False,
            server_default=sa.false(),
        ),
        sa.Column(
            "action_takeover_confirmed", sa.Boolean(), nullable=False,
            server_default=sa.false(),
        ),
        sa.Column("action_takeover_reason", sa.String(length=500)),
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
            "amount_minor > 0", name="ck_membership_refund_completion_positive"
        ),
        sa.CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_completion_method",
        ),
        sa.CheckConstraint(
            "((cash_handoff_id IS NOT NULL)::int + "
            "(provider_action_id IS NOT NULL)::int + "
            "(legacy_attempt_resolution_id IS NOT NULL)::int) = 1",
            name="ck_membership_refund_completion_one_action",
        ),
        sa.CheckConstraint(
            "(method = 'cash' AND provider_action_id IS NULL "
            "AND external_reference IS NULL) OR "
            "(method <> 'cash' AND cash_handoff_id IS NULL "
            "AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_refund_completion_evidence",
        ),
        sa.CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_refund_completion_takeover",
        ),
        sa.UniqueConstraint("refund_id", name="uq_membership_refund_completion_refund"),
        sa.UniqueConstraint(
            "cash_handoff_id", name="uq_membership_refund_completion_cash_action"
        ),
        sa.UniqueConstraint(
            "provider_action_id", name="uq_membership_refund_completion_provider_action"
        ),
        sa.UniqueConstraint(
            "legacy_attempt_resolution_id",
            name="uq_membership_refund_completion_legacy_attempt",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_completion_idempotency",
        ),
        sa.UniqueConstraint(
            "company_id", "method", "external_reference",
            name="uq_membership_refund_completion_provider_reference",
        ),
    )
    op.create_index(
        "ix_membership_refund_completions_company_id",
        "membership_refund_completions", ["company_id"],
    )
    op.create_index(
        "ix_membership_refund_completion_shift",
        "membership_refund_completions", ["shift_id"],
    )
    op.add_column(
        "membership_refund_settlements",
        sa.Column("completion_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.create_foreign_key(
        "fk_membership_refund_settlements_completion_id",
        "membership_refund_settlements",
        "membership_refund_completions",
        ["completion_id"], ["id"], ondelete="RESTRICT",
    )
    op.create_unique_constraint(
        "uq_membership_refund_settlement_completion",
        "membership_refund_settlements",
        ["completion_id"],
    )

    op.create_table(
        "membership_customer_spend_applications",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "customer_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column(
            "payment_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payments.id", ondelete="RESTRICT"),
        ),
        sa.Column(
            "refund_settlement_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refund_settlements.id", ondelete="RESTRICT"),
        ),
        sa.Column("source_amount_minor", sa.BigInteger(), nullable=False),
        sa.Column("before_total_spent_minor", sa.BigInteger(), nullable=False),
        sa.Column("after_total_spent_minor", sa.BigInteger(), nullable=False),
        sa.Column("adjustment_minor", sa.BigInteger(), nullable=False),
        sa.Column("applied_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "applied_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
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
            "(payment_id IS NOT NULL AND refund_settlement_id IS NULL) OR "
            "(payment_id IS NULL AND refund_settlement_id IS NOT NULL)",
            name="ck_membership_spend_application_one_source",
        ),
        sa.CheckConstraint(
            "after_total_spent_minor >= 0",
            name="ck_membership_spend_application_nonnegative",
        ),
        sa.CheckConstraint(
            "adjustment_minor = after_total_spent_minor - before_total_spent_minor",
            name="ck_membership_spend_application_delta",
        ),
        sa.UniqueConstraint("payment_id", name="uq_membership_spend_application_payment"),
        sa.UniqueConstraint(
            "refund_settlement_id", name="uq_membership_spend_application_refund"
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_spend_application_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_customer_spend_applications_company_id",
        "membership_customer_spend_applications", ["company_id"],
    )
    op.create_index(
        "ix_membership_spend_application_customer",
        "membership_customer_spend_applications", ["customer_id"],
    )

    op.create_table(
        "membership_evidence_reconciliations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "company_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("companies.id", ondelete="RESTRICT"), nullable=False,
        ),
        sa.Column("payment_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_payments.id", ondelete="RESTRICT")),
        sa.Column("refund_settlement_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_refund_settlements.id", ondelete="RESTRICT")),
        sa.Column("payment_completion_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_payment_completions.id", ondelete="RESTRICT")),
        sa.Column("refund_completion_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_refund_completions.id", ondelete="RESTRICT")),
        sa.Column("payment_request_resolution_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_payment_request_resolutions.id", ondelete="RESTRICT")),
        sa.Column("refund_resolution_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_refund_resolutions.id", ondelete="RESTRICT")),
        sa.Column("payment_attempt_resolution_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_payment_attempt_resolutions.id", ondelete="RESTRICT")),
        sa.Column("refund_attempt_resolution_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("membership_refund_attempt_resolutions.id", ondelete="RESTRICT")),
        sa.Column("evidence_kind", sa.String(length=40), nullable=False),
        sa.Column("proof_reference", sa.String(length=200), nullable=False),
        sa.Column("verified_occurred_at", sa.DateTime(timezone=True)),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("reconciled_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "reconciled_by", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
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
            "evidence_kind IN ('provider_reference', 'captured_time')",
            name="ck_membership_evidence_reconciliation_kind",
        ),
        sa.CheckConstraint(
            "char_length(trim(proof_reference)) >= 3",
            name="ck_membership_evidence_reconciliation_proof",
        ),
        sa.CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_evidence_reconciliation_reason",
        ),
        sa.CheckConstraint(
            "((payment_id IS NOT NULL)::int + "
            "(refund_settlement_id IS NOT NULL)::int + "
            "(payment_completion_id IS NOT NULL)::int + "
            "(refund_completion_id IS NOT NULL)::int + "
            "(payment_request_resolution_id IS NOT NULL)::int + "
            "(refund_resolution_id IS NOT NULL)::int + "
            "(payment_attempt_resolution_id IS NOT NULL)::int + "
            "(refund_attempt_resolution_id IS NOT NULL)::int) = 1",
            name="ck_membership_evidence_reconciliation_one_source",
        ),
        sa.UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_evidence_reconciliation_idempotency",
        ),
    )
    op.create_index(
        "ix_membership_evidence_reconciliation_company",
        "membership_evidence_reconciliations", ["company_id"],
    )
    for source_column in (
        "payment_id", "refund_settlement_id", "payment_completion_id",
        "refund_completion_id", "payment_request_resolution_id",
        "refund_resolution_id", "payment_attempt_resolution_id",
        "refund_attempt_resolution_id",
    ):
        op.create_index(
            f"uq_membership_evidence_{source_column}_kind",
            "membership_evidence_reconciliations",
            [source_column, "evidence_kind"],
            unique=True,
            postgresql_where=sa.text(f"{source_column} IS NOT NULL"),
        )

    # A single row is the compare-and-set arbiter for each workflow.  Facts
    # remain append-only in their domain tables; these private rows merely
    # serialize action-vs-resolution and settlement-vs-resolution across
    # different tables.  A unique constraint in each individual fact table is
    # not enough because two transactions can otherwise both observe the other
    # outcome table as empty under READ COMMITTED.
    op.create_table(
        "membership_payment_workflow_guards",
        sa.Column(
            "request_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payment_requests.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("action_kind", sa.String(length=30)),
        sa.Column("action_id", postgresql.UUID(as_uuid=True)),
        sa.Column("completion_id", postgresql.UUID(as_uuid=True)),
        sa.Column("outcome_kind", sa.String(length=30)),
        sa.Column("outcome_id", postgresql.UUID(as_uuid=True)),
        sa.CheckConstraint(
            "(action_kind IS NULL AND action_id IS NULL) OR "
            "(action_kind IN ('cash_collection', 'provider_payment') "
            "AND action_id IS NOT NULL)",
            name="ck_membership_payment_guard_action",
        ),
        sa.CheckConstraint(
            "(outcome_kind IS NULL AND outcome_id IS NULL) OR "
            "(outcome_kind IN ('settled', 'withdrawn') AND outcome_id IS NOT NULL)",
            name="ck_membership_payment_guard_outcome",
        ),
    )
    op.create_table(
        "membership_refund_workflow_guards",
        sa.Column(
            "refund_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_refunds.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("action_kind", sa.String(length=30)),
        sa.Column("action_id", postgresql.UUID(as_uuid=True)),
        sa.Column("completion_id", postgresql.UUID(as_uuid=True)),
        sa.Column("outcome_kind", sa.String(length=30)),
        sa.Column("outcome_id", postgresql.UUID(as_uuid=True)),
        sa.CheckConstraint(
            "(action_kind IS NULL AND action_id IS NULL) OR "
            "(action_kind IN ('cash_handoff', 'provider_refund', 'legacy_recovery') "
            "AND action_id IS NOT NULL)",
            name="ck_membership_refund_guard_action",
        ),
        sa.CheckConstraint(
            "(outcome_kind IS NULL AND outcome_id IS NULL) OR "
            "(outcome_kind IN ('settled', 'withdrawn') AND outcome_id IS NOT NULL)",
            name="ck_membership_refund_guard_outcome",
        ),
    )
    op.create_table(
        "membership_payment_refund_guards",
        sa.Column(
            "payment_id", postgresql.UUID(as_uuid=True),
            sa.ForeignKey("membership_payments.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("active_refund_id", postgresql.UUID(as_uuid=True)),
        sa.Column("settled_refund_id", postgresql.UUID(as_uuid=True)),
        sa.CheckConstraint(
            "active_refund_id IS NULL OR settled_refund_id IS NULL",
            name="ck_membership_payment_refund_guard_single_state",
        ),
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                  FROM membership_refunds refund
                  JOIN membership_refund_settlements settlement
                    ON settlement.refund_id = refund.id
                  JOIN membership_refund_resolutions resolution
                    ON resolution.refund_id = refund.id
            ) THEN
                RAISE EXCEPTION
                    'membership refund has both settlement and resolution; reconcile before 0035';
            END IF;
        END $$
        """
    )
    op.execute(
        """
        INSERT INTO membership_payment_workflow_guards (request_id)
        SELECT id FROM membership_payment_requests
        ON CONFLICT (request_id) DO NOTHING
        """
    )
    op.execute(
        """
        INSERT INTO membership_refund_workflow_guards (
            refund_id, outcome_kind, outcome_id
        )
        SELECT refund.id,
               CASE
                   WHEN settlement.id IS NOT NULL THEN 'settled'
                   WHEN resolution.id IS NOT NULL THEN 'withdrawn'
                   ELSE NULL
               END,
               COALESCE(settlement.id, resolution.id)
          FROM membership_refunds refund
          LEFT JOIN membership_refund_settlements settlement
            ON settlement.refund_id = refund.id
          LEFT JOIN membership_refund_resolutions resolution
            ON resolution.refund_id = refund.id
        ON CONFLICT (refund_id) DO NOTHING
        """
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT r.payment_id
                  FROM membership_refunds r
                  LEFT JOIN membership_refund_resolutions rr ON rr.refund_id = r.id
                  LEFT JOIN membership_refund_settlements rs ON rs.refund_id = r.id
                 WHERE rr.id IS NULL OR rs.id IS NOT NULL
                 GROUP BY r.payment_id
                HAVING count(*) > 1
            ) THEN
                RAISE EXCEPTION
                    'membership payment has multiple active/settled refund roots; reconcile before 0035';
            END IF;
        END $$
        """
    )
    op.execute(
        """
        INSERT INTO membership_payment_refund_guards (
            payment_id, active_refund_id, settled_refund_id
        )
        SELECT payment.id,
               CASE WHEN settlement.id IS NULL AND resolution.id IS NULL
                    THEN refund.id ELSE NULL END,
               CASE WHEN settlement.id IS NOT NULL THEN refund.id ELSE NULL END
          FROM membership_payments payment
          LEFT JOIN membership_refunds refund ON refund.payment_id = payment.id
          LEFT JOIN membership_refund_settlements settlement
            ON settlement.refund_id = refund.id
          LEFT JOIN membership_refund_resolutions resolution
            ON resolution.refund_id = refund.id
        ON CONFLICT (payment_id) DO NOTHING
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_init_membership_workflow_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = public, pg_temp
        AS $$
        BEGIN
            IF TG_TABLE_NAME = 'membership_payment_requests' THEN
                INSERT INTO membership_payment_workflow_guards (request_id)
                VALUES (NEW.id);
            ELSIF TG_TABLE_NAME = 'membership_payments' THEN
                INSERT INTO membership_payment_refund_guards (payment_id)
                VALUES (NEW.id);
            ELSE
                INSERT INTO membership_refund_workflow_guards (refund_id)
                VALUES (NEW.id);
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_payment_requests_init_guard
        AFTER INSERT ON membership_payment_requests
        FOR EACH ROW EXECUTE FUNCTION dcompany_init_membership_workflow_guard()
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_refunds_init_guard
        AFTER INSERT ON membership_refunds
        FOR EACH ROW EXECUTE FUNCTION dcompany_init_membership_workflow_guard()
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_payments_init_refund_guard
        AFTER INSERT ON membership_payments
        FOR EACH ROW EXECUTE FUNCTION dcompany_init_membership_workflow_guard()
        """
    )
    # Runtime callers do not need direct access.  The SECURITY DEFINER trigger
    # functions own state transitions; application routes only insert immutable
    # domain facts.  (A deployment should still use distinct migration/runtime
    # roles so the runtime role is not the table owner.)
    op.execute("REVOKE ALL ON membership_payment_workflow_guards FROM PUBLIC")
    op.execute("REVOKE ALL ON membership_refund_workflow_guards FROM PUBLIC")
    op.execute("REVOKE ALL ON membership_payment_refund_guards FROM PUBLIC")
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_workflow_guard_dml()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            -- Direct DML invokes this trigger at depth 1.  The only allowed
            -- writes are nested inside the root/bootstrap or transition
            -- triggers above, which invoke it at depth 2 or greater.
            IF pg_trigger_depth() < 2 THEN
                RAISE EXCEPTION '% is internal-only; direct % is forbidden',
                    TG_TABLE_NAME, TG_OP;
            END IF;
            IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_payment_workflow_guards_internal
        BEFORE INSERT OR UPDATE OR DELETE ON membership_payment_workflow_guards
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_workflow_guard_dml()
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_refund_workflow_guards_internal
        BEFORE INSERT OR UPDATE OR DELETE ON membership_refund_workflow_guards
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_workflow_guard_dml()
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_payment_refund_guards_internal
        BEFORE INSERT OR UPDATE OR DELETE ON membership_payment_refund_guards
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_workflow_guard_dml()
        """
    )

    # Validate the root reservation facts themselves. Foreign keys prove only
    # that referenced rows exist; these triggers also prove that every party,
    # terminal and shift belongs to the same tenant/branch and that the price
    # snapshot was valid when the zero-value reservation was accepted.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_payment_request()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            shift_row record;
            tier_price bigint;
        BEGIN
            SELECT company_id, branch_id, terminal_id, status, opened_at
              INTO shift_row
              FROM shifts
             WHERE id = NEW.shift_id;
            SELECT monthly_price_minor
              INTO tier_price
              FROM membership_tiers
             WHERE id = NEW.tier_id
               AND company_id = NEW.company_id;
            IF shift_row IS NULL
               OR shift_row.company_id IS DISTINCT FROM NEW.company_id
               OR shift_row.branch_id IS DISTINCT FROM NEW.branch_id
               OR shift_row.terminal_id IS DISTINCT FROM NEW.terminal_id
               OR shift_row.status <> 'open'
               OR NEW.accepted_at < shift_row.opened_at
               OR tier_price IS NULL
               OR tier_price IS DISTINCT FROM NEW.amount_minor
               OR NOT EXISTS (
                    SELECT 1 FROM customers
                     WHERE id = NEW.customer_id
                       AND company_id = NEW.company_id
                       AND deleted_at IS NULL
               )
               OR NOT EXISTS (
                    SELECT 1 FROM branches
                     WHERE id = NEW.branch_id AND company_id = NEW.company_id
               )
               OR NOT EXISTS (
                    SELECT 1 FROM terminals
                     WHERE id = NEW.terminal_id AND branch_id = NEW.branch_id
               )
               OR NOT EXISTS (
                    SELECT 1 FROM users
                     WHERE id = NEW.prepared_by AND company_id = NEW.company_id
               )
            THEN
                RAISE EXCEPTION
                    'membership payment request has inconsistent tenant/shift/price provenance';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_payment_requests_provenance
        BEFORE INSERT ON membership_payment_requests
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_payment_request()
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_refund_request()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            shift_row record;
            payment_row record;
            claimed_payment uuid;
        BEGIN
            SELECT company_id, branch_id, terminal_id, status, opened_at
              INTO shift_row
              FROM shifts
             WHERE id = NEW.shift_id;
            SELECT company_id, amount_minor
              INTO payment_row
              FROM membership_payments
             WHERE id = NEW.payment_id;
            IF shift_row IS NULL
               OR shift_row.company_id IS DISTINCT FROM NEW.company_id
               OR shift_row.branch_id IS DISTINCT FROM NEW.branch_id
               OR shift_row.terminal_id IS DISTINCT FROM NEW.terminal_id
               OR shift_row.status <> 'open'
               OR NEW.accepted_at < shift_row.opened_at
               OR payment_row IS NULL
               OR payment_row.company_id IS DISTINCT FROM NEW.company_id
               OR payment_row.amount_minor IS DISTINCT FROM NEW.amount_minor
               OR NOT EXISTS (
                    SELECT 1 FROM branches
                     WHERE id = NEW.branch_id AND company_id = NEW.company_id
               )
               OR NOT EXISTS (
                    SELECT 1 FROM terminals
                     WHERE id = NEW.terminal_id AND branch_id = NEW.branch_id
               )
               OR NOT EXISTS (
                    SELECT 1 FROM users
                     WHERE id = NEW.approved_by AND company_id = NEW.company_id
               )
            THEN
                RAISE EXCEPTION
                    'membership refund request has inconsistent tenant/shift/payment provenance';
            END IF;
            UPDATE membership_payment_refund_guards
               SET active_refund_id = NEW.id
             WHERE payment_id = NEW.payment_id
               AND active_refund_id IS NULL
               AND settled_refund_id IS NULL
            RETURNING payment_id INTO claimed_payment;
            IF claimed_payment IS NULL THEN
                RAISE EXCEPTION
                    'membership payment % already has an active or settled refund',
                    NEW.payment_id;
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_refunds_provenance
        BEFORE INSERT ON membership_refunds
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_refund_request()
        """
    )

    # The workflow guard is updated with one SQL compare-and-set before a fact
    # can be inserted.  PostgreSQL's UPDATE rechecks its predicate after a row
    # lock wait, so two READ COMMITTED transactions cannot both win even when
    # they insert into different action/outcome tables.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_payment_outcome()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = public, pg_temp
        AS $$
        DECLARE
            request_uuid uuid;
            request_row record;
            guard_row record;
            action_row record;
            completion_row record;
            membership_row record;
            outcome_actor uuid;
            desired_action_kind text;
        BEGIN
            request_uuid := NEW.request_id;
            IF request_uuid IS NULL THEN
                RAISE EXCEPTION
                    'new membership payment requires request and completion linkage';
            END IF;
            SELECT * INTO request_row
              FROM membership_payment_requests
             WHERE id = request_uuid;
            IF request_row IS NULL THEN
                RAISE EXCEPTION 'membership payment request % does not exist', request_uuid;
            END IF;

            IF TG_TABLE_NAME = 'membership_payment_completions' THEN
                desired_action_kind := CASE
                    WHEN NEW.method = 'cash' THEN 'cash_collection'
                    ELSE 'provider_payment'
                END;
                UPDATE membership_payment_workflow_guards
                   SET completion_id = NEW.id
                 WHERE request_id = request_uuid
                   AND outcome_kind IS NULL
                   AND action_kind = desired_action_kind
                   AND completion_id IS NULL
                RETURNING * INTO guard_row;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_payment_workflow_guards
                     WHERE request_id = request_uuid;
                    IF guard_row.outcome_kind = 'withdrawn' THEN
                        RAISE EXCEPTION
                            'membership payment request % was already withdrawn', request_uuid;
                    ELSIF guard_row.outcome_kind = 'settled' THEN
                        RAISE EXCEPTION
                            'membership payment request % was already settled', request_uuid;
                    ELSIF guard_row.completion_id IS NOT NULL THEN
                        RAISE EXCEPTION
                            'membership payment request % already has value-completion evidence',
                            request_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership payment request % has no matching begun action',
                            request_uuid;
                    END IF;
                END IF;
                IF NEW.method = 'cash' THEN
                    SELECT * INTO action_row
                    FROM membership_payment_cash_collections
                    WHERE id = guard_row.action_id
                      AND request_id = request_uuid;
                ELSE
                    SELECT * INTO action_row
                    FROM membership_payment_provider_actions
                    WHERE id = guard_row.action_id
                      AND request_id = request_uuid;
                END IF;
                IF action_row IS NULL
                   OR (
                        NEW.method = 'cash'
                        AND NEW.cash_collection_id IS DISTINCT FROM guard_row.action_id
                   )
                   OR (
                        NEW.method <> 'cash'
                        AND NEW.provider_action_id IS DISTINCT FROM guard_row.action_id
                   )
                   OR NEW.company_id IS DISTINCT FROM request_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM request_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM request_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM request_row.shift_id
                   OR NEW.method IS DISTINCT FROM request_row.method
                   OR NEW.amount_minor IS DISTINCT FROM request_row.amount_minor
                   OR NEW.completed_at < action_row.started_at
                   OR action_row.company_id IS DISTINCT FROM request_row.company_id
                   OR action_row.branch_id IS DISTINCT FROM request_row.branch_id
                   OR action_row.terminal_id IS DISTINCT FROM request_row.terminal_id
                   OR action_row.shift_id IS DISTINCT FROM request_row.shift_id
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.completed_by
                           AND company_id = request_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership payment request % has inconsistent completion provenance',
                        request_uuid;
                END IF;
                outcome_actor := NEW.completed_by;
                IF action_row.started_by IS DISTINCT FROM outcome_actor THEN
                    IF NEW.action_takeover_confirmed IS NOT TRUE
                       OR char_length(trim(NEW.action_takeover_reason)) < 3
                    THEN
                        RAISE EXCEPTION
                            'membership payment request % action requires verified takeover',
                            request_uuid;
                    END IF;
                ELSIF NEW.action_takeover_confirmed IS TRUE THEN
                    RAISE EXCEPTION
                        'membership payment request % cannot claim a same-actor takeover',
                        request_uuid;
                END IF;
            ELSIF TG_TABLE_NAME = 'membership_payments' THEN
                UPDATE membership_payment_workflow_guards
                   SET outcome_kind = 'settled', outcome_id = NEW.id
                 WHERE request_id = request_uuid
                   AND outcome_kind IS NULL
                   AND completion_id = NEW.completion_id
                RETURNING * INTO guard_row;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_payment_workflow_guards
                     WHERE request_id = request_uuid;
                    IF guard_row.outcome_kind = 'withdrawn' THEN
                        RAISE EXCEPTION
                            'membership payment request % was already withdrawn', request_uuid;
                    ELSIF guard_row.outcome_kind = 'settled' THEN
                        RAISE EXCEPTION
                            'membership payment request % was already settled', request_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership payment request % has no committed value completion',
                            request_uuid;
                    END IF;
                END IF;
                SELECT * INTO completion_row
                  FROM membership_payment_completions
                 WHERE id = NEW.completion_id
                   AND request_id = request_uuid;
                SELECT customer_id, tier_id, billing_cycle, amount_paid_minor
                  INTO membership_row
                  FROM customer_memberships
                 WHERE id = NEW.membership_id;
                IF completion_row IS NULL
                   OR NEW.company_id IS DISTINCT FROM request_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM request_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM request_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM request_row.shift_id
                   OR NEW.method IS DISTINCT FROM request_row.method
                   OR NEW.amount_minor IS DISTINCT FROM request_row.amount_minor
                   OR NEW.paid_at < completion_row.completed_at
                   OR NEW.external_reference IS DISTINCT FROM completion_row.external_reference
                   OR NEW.provider_evidence_reconciled IS DISTINCT FROM
                        completion_row.provider_evidence_reconciled
                   OR NEW.evidence_occurred_at IS DISTINCT FROM
                        completion_row.evidence_occurred_at
                   OR NEW.evidence_time_untrusted IS DISTINCT FROM
                        completion_row.evidence_time_untrusted
                   OR NEW.created_by IS DISTINCT FROM completion_row.completed_by
                   OR NEW.action_takeover_confirmed IS DISTINCT FROM
                        completion_row.action_takeover_confirmed
                   OR NEW.action_takeover_reason IS DISTINCT FROM
                        completion_row.action_takeover_reason
                   OR membership_row IS NULL
                   OR membership_row.customer_id IS DISTINCT FROM request_row.customer_id
                   OR membership_row.tier_id IS DISTINCT FROM request_row.tier_id
                   OR membership_row.billing_cycle IS DISTINCT FROM request_row.billing_cycle
                   OR membership_row.amount_paid_minor IS DISTINCT FROM request_row.amount_minor
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.created_by
                           AND company_id = request_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership payment request % has inconsistent settlement provenance',
                        request_uuid;
                END IF;
            ELSIF TG_TABLE_NAME = 'membership_payment_request_resolutions' THEN
                UPDATE membership_payment_workflow_guards
                   SET outcome_kind = 'withdrawn', outcome_id = NEW.id
                 WHERE request_id = request_uuid
                   AND outcome_kind IS NULL
                RETURNING * INTO guard_row;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_payment_workflow_guards
                     WHERE request_id = request_uuid;
                    IF guard_row.outcome_kind = 'settled' THEN
                        RAISE EXCEPTION
                            'membership payment request % was already settled', request_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership payment request % was already withdrawn', request_uuid;
                    END IF;
                END IF;
                IF guard_row.action_kind = 'cash_collection' THEN
                    SELECT company_id, branch_id, terminal_id, shift_id,
                           started_at, started_by, 'cash'::text AS action_kind
                      INTO action_row
                      FROM membership_payment_cash_collections
                     WHERE id = guard_row.action_id
                       AND request_id = request_uuid;
                ELSIF guard_row.action_kind = 'provider_payment' THEN
                    SELECT company_id, branch_id, terminal_id, shift_id,
                           started_at, started_by, 'provider'::text AS action_kind
                      INTO action_row
                      FROM membership_payment_provider_actions
                     WHERE id = guard_row.action_id
                       AND request_id = request_uuid;
                END IF;
                IF NEW.company_id IS DISTINCT FROM request_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM request_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM request_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM request_row.shift_id
                   OR NEW.paid_via IS DISTINCT FROM request_row.method
                   OR NEW.resolved_at < request_row.accepted_at
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.resolved_by
                           AND company_id = request_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership payment request % has inconsistent resolution provenance',
                        request_uuid;
                END IF;
                IF action_row IS NULL THEN
                    IF NEW.resolution <> 'payment_not_collected'
                       OR NEW.action_state_verified IS TRUE
                       OR NEW.action_takeover_confirmed IS TRUE
                       OR NEW.provider_verification_status IS NOT NULL
                       OR NEW.provider_verification_reference IS NOT NULL
                       OR NEW.provider_checked_at IS NOT NULL
                       OR NEW.cash_return_confirmed IS TRUE
                    THEN
                        RAISE EXCEPTION
                            'membership payment request % resolution does not match its action state',
                            request_uuid;
                    END IF;
                ELSE
                    IF action_row.company_id IS DISTINCT FROM request_row.company_id
                       OR action_row.branch_id IS DISTINCT FROM request_row.branch_id
                       OR action_row.terminal_id IS DISTINCT FROM request_row.terminal_id
                       OR action_row.shift_id IS DISTINCT FROM request_row.shift_id
                       OR (action_row.action_kind = 'cash' AND NEW.paid_via <> 'cash')
                       OR (action_row.action_kind = 'provider' AND NEW.paid_via = 'cash')
                    THEN
                        RAISE EXCEPTION
                            'membership payment request % has inconsistent action provenance',
                            request_uuid;
                    END IF;
                    IF NEW.action_state_verified IS NOT TRUE THEN
                        RAISE EXCEPTION
                            'membership payment request % action outcome was not verified',
                            request_uuid;
                    END IF;
                    IF action_row.action_kind = 'provider' THEN
                        IF NEW.provider_checked_at IS NULL
                           OR NEW.provider_checked_at < action_row.started_at
                           OR NEW.provider_verification_reference IS NULL
                           OR char_length(trim(NEW.provider_verification_reference)) < 1
                           OR (
                                NEW.resolution = 'provider_not_completed'
                                AND NEW.provider_verification_status <> 'not_completed'
                           )
                           OR (
                                NEW.resolution = 'provider_reversed'
                                AND (
                                    NEW.provider_verification_status <> 'reversed'
                                    OR NEW.external_reference IS DISTINCT FROM
                                       NEW.provider_verification_reference
                                )
                           )
                           OR NEW.resolution NOT IN (
                                'provider_not_completed', 'provider_reversed'
                           )
                        THEN
                            RAISE EXCEPTION
                                'membership payment request % lacks verified provider outcome',
                                request_uuid;
                        END IF;
                        IF NEW.cash_return_confirmed IS TRUE THEN
                            RAISE EXCEPTION
                                'membership payment request % has cash proof on a provider action',
                                request_uuid;
                        END IF;
                    ELSIF NEW.provider_verification_status IS NOT NULL
                       OR NEW.provider_verification_reference IS NOT NULL
                       OR NEW.provider_checked_at IS NOT NULL
                    THEN
                        RAISE EXCEPTION
                            'membership payment request % has provider proof on a cash action',
                            request_uuid;
                    END IF;
                    IF action_row.action_kind = 'cash'
                       AND (
                            (NEW.resolution = 'cash_returned'
                             AND NEW.cash_return_confirmed IS NOT TRUE)
                            OR (NEW.resolution <> 'cash_returned'
                                AND NEW.cash_return_confirmed IS TRUE)
                       )
                    THEN
                        RAISE EXCEPTION
                            'membership payment request % lacks exact cash-return proof',
                            request_uuid;
                    END IF;
                    IF guard_row.completion_id IS NOT NULL
                       AND (
                            (action_row.action_kind = 'cash'
                             AND NEW.resolution <> 'cash_returned')
                            OR (action_row.action_kind = 'provider'
                                AND NEW.resolution <> 'provider_reversed')
                       )
                    THEN
                        RAISE EXCEPTION
                            'membership payment request % value moved and lacks reversal proof',
                            request_uuid;
                    END IF;
                    outcome_actor := NEW.resolved_by;
                    IF action_row.started_by IS DISTINCT FROM outcome_actor THEN
                        IF NEW.action_takeover_confirmed IS NOT TRUE
                           OR char_length(trim(NEW.action_takeover_reason)) < 3
                        THEN
                            RAISE EXCEPTION
                                'membership payment request % action requires verified takeover',
                                request_uuid;
                        END IF;
                    ELSIF NEW.action_takeover_confirmed IS TRUE THEN
                        RAISE EXCEPTION
                            'membership payment request % cannot claim a same-actor takeover',
                            request_uuid;
                    END IF;
                END IF;
            ELSE
                IF NEW.company_id IS DISTINCT FROM request_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM request_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM request_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM request_row.shift_id
                   OR NEW.started_at < request_row.accepted_at
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.started_by
                           AND company_id = request_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership payment request % has inconsistent action provenance',
                        request_uuid;
                END IF;
                IF TG_TABLE_NAME = 'membership_payment_cash_collections' THEN
                    desired_action_kind := 'cash_collection';
                    IF request_row.method <> 'cash' THEN
                        RAISE EXCEPTION
                            'membership payment request % has inconsistent cash-action provenance',
                            request_uuid;
                    END IF;
                ELSE
                    desired_action_kind := 'provider_payment';
                    IF request_row.method = 'cash'
                       OR NEW.method IS DISTINCT FROM request_row.method
                    THEN
                        RAISE EXCEPTION
                            'membership payment request % has inconsistent provider-action provenance',
                            request_uuid;
                    END IF;
                END IF;
                UPDATE membership_payment_workflow_guards
                   SET action_kind = desired_action_kind, action_id = NEW.id
                 WHERE request_id = request_uuid
                   AND action_kind IS NULL
                   AND completion_id IS NULL
                   AND outcome_kind IS NULL
                RETURNING * INTO guard_row;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_payment_workflow_guards
                     WHERE request_id = request_uuid;
                    IF guard_row.outcome_kind IS NOT NULL THEN
                        RAISE EXCEPTION
                            'membership payment request % already has a final outcome',
                            request_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership payment request % already has an action in progress',
                            request_uuid;
                    END IF;
                END IF;
            END IF;
            RETURN NEW;
        END $$
        """
    )
    for table_name, trigger_name in (
        (
            "membership_payment_completions",
            "trg_membership_payment_completions_outcome_exclusive",
        ),
        ("membership_payments", "trg_membership_payments_outcome_exclusive"),
        (
            "membership_payment_request_resolutions",
            "trg_membership_payment_request_resolutions_outcome_exclusive",
        ),
        (
            "membership_payment_cash_collections",
            "trg_membership_payment_cash_collections_outcome_exclusive",
        ),
        (
            "membership_payment_provider_actions",
            "trg_membership_payment_provider_actions_outcome_exclusive",
        ),
    ):
        op.execute(
            f"""
            CREATE TRIGGER {trigger_name}
            BEFORE INSERT ON {table_name}
            FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_payment_outcome()
            """
        )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_refund_outcome()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = public, pg_temp
        AS $$
        DECLARE
            refund_uuid uuid;
            refund_row record;
            guard_row record;
            action_row record;
            completion_row record;
            outcome_actor uuid;
            desired_action_kind text;
        BEGIN
            refund_uuid := NEW.refund_id;
            SELECT * INTO refund_row
              FROM membership_refunds
             WHERE id = refund_uuid;
            IF refund_row IS NULL THEN
                RAISE EXCEPTION 'membership refund % does not exist', refund_uuid;
            END IF;

            IF TG_TABLE_NAME = 'membership_refund_completions' THEN
                desired_action_kind := CASE
                    WHEN NEW.legacy_attempt_resolution_id IS NOT NULL THEN 'legacy_recovery'
                    WHEN NEW.method = 'cash' THEN 'cash_handoff'
                    ELSE 'provider_refund'
                END;
                IF desired_action_kind = 'legacy_recovery' THEN
                    UPDATE membership_refund_workflow_guards
                       SET action_kind = desired_action_kind,
                           action_id = NEW.legacy_attempt_resolution_id,
                           completion_id = NEW.id
                     WHERE refund_id = refund_uuid
                       AND action_kind IS NULL
                       AND completion_id IS NULL
                       AND outcome_kind IS NULL
                    RETURNING * INTO guard_row;
                ELSE
                    UPDATE membership_refund_workflow_guards
                       SET completion_id = NEW.id
                     WHERE refund_id = refund_uuid
                       AND action_kind = desired_action_kind
                       AND completion_id IS NULL
                       AND outcome_kind IS NULL
                    RETURNING * INTO guard_row;
                END IF;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_refund_workflow_guards
                     WHERE refund_id = refund_uuid;
                    IF guard_row.outcome_kind = 'withdrawn' THEN
                        RAISE EXCEPTION
                            'membership refund % was already withdrawn', refund_uuid;
                    ELSIF guard_row.outcome_kind = 'settled' THEN
                        RAISE EXCEPTION
                            'membership refund % was already settled', refund_uuid;
                    ELSIF guard_row.completion_id IS NOT NULL THEN
                        RAISE EXCEPTION
                            'membership refund % already has payout-completion evidence',
                            refund_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership refund % has no matching begun action', refund_uuid;
                    END IF;
                END IF;
                IF desired_action_kind = 'legacy_recovery' THEN
                    SELECT attempt.company_id, refund.branch_id, refund.terminal_id,
                           attempt.reconciliation_shift_id AS shift_id,
                           attempt.checked_at AS started_at,
                           attempt.resolved_by AS started_by,
                           attempt.paid_via::text AS method,
                           attempt.expected_amount_minor,
                           attempt.outcome::text AS outcome,
                           attempt.verification_reference::text AS verification_reference
                      INTO action_row
                      FROM membership_refund_attempt_resolutions attempt
                      JOIN membership_refunds refund ON refund.id = attempt.refund_id
                     WHERE attempt.id = guard_row.action_id
                       AND attempt.refund_id = refund_uuid;
                ELSIF NEW.method = 'cash' THEN
                    SELECT company_id, branch_id, terminal_id, shift_id,
                           started_at, started_by, 'cash'::text AS method,
                           refund_row.amount_minor AS expected_amount_minor,
                           NULL::text AS outcome,
                           NULL::text AS verification_reference
                      INTO action_row
                      FROM membership_refund_cash_handoffs
                     WHERE id = guard_row.action_id
                       AND refund_id = refund_uuid;
                ELSE
                    SELECT company_id, branch_id, terminal_id, shift_id,
                           started_at, started_by, method::text AS method,
                           refund_row.amount_minor AS expected_amount_minor,
                           NULL::text AS outcome,
                           NULL::text AS verification_reference
                      INTO action_row
                      FROM membership_refund_provider_actions
                     WHERE id = guard_row.action_id
                       AND refund_id = refund_uuid;
                END IF;
                IF action_row IS NULL
                   OR (
                        desired_action_kind = 'cash_handoff'
                        AND NEW.cash_handoff_id IS DISTINCT FROM guard_row.action_id
                   )
                   OR (
                        desired_action_kind = 'provider_refund'
                        AND NEW.provider_action_id IS DISTINCT FROM guard_row.action_id
                   )
                   OR (
                        desired_action_kind = 'legacy_recovery'
                        AND NEW.legacy_attempt_resolution_id IS DISTINCT FROM
                            guard_row.action_id
                   )
                   OR NEW.company_id IS DISTINCT FROM refund_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM refund_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM refund_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM refund_row.shift_id
                   OR NEW.method IS DISTINCT FROM refund_row.method
                   OR NEW.amount_minor IS DISTINCT FROM refund_row.amount_minor
                   OR NEW.completed_at < action_row.started_at
                   OR action_row.company_id IS DISTINCT FROM refund_row.company_id
                   OR action_row.shift_id IS DISTINCT FROM refund_row.shift_id
                   OR (
                        desired_action_kind <> 'legacy_recovery'
                        AND (
                            action_row.branch_id IS DISTINCT FROM refund_row.branch_id
                            OR action_row.terminal_id IS DISTINCT FROM refund_row.terminal_id
                        )
                   )
                   OR (
                        desired_action_kind = 'legacy_recovery'
                        AND (
                            action_row.method IS DISTINCT FROM refund_row.method
                            OR action_row.expected_amount_minor IS DISTINCT FROM
                               refund_row.amount_minor
                            OR (
                                action_row.outcome = 'cash_handed_over'
                                AND NEW.method <> 'cash'
                            )
                            OR (
                                action_row.outcome = 'provider_completed'
                                AND (
                                    NEW.method = 'cash'
                                    OR NEW.external_reference IS DISTINCT FROM
                                       action_row.verification_reference
                                )
                            )
                            OR action_row.outcome NOT IN (
                                'cash_handed_over', 'provider_completed'
                            )
                        )
                   )
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.completed_by
                           AND company_id = refund_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership refund % has inconsistent completion provenance',
                        refund_uuid;
                END IF;
                outcome_actor := NEW.completed_by;
                IF action_row.started_by IS DISTINCT FROM outcome_actor THEN
                    IF NEW.action_takeover_confirmed IS NOT TRUE
                       OR char_length(trim(NEW.action_takeover_reason)) < 3
                    THEN
                        RAISE EXCEPTION
                            'membership refund % action requires verified takeover', refund_uuid;
                    END IF;
                ELSIF NEW.action_takeover_confirmed IS TRUE THEN
                    RAISE EXCEPTION
                        'membership refund % cannot claim a same-actor takeover', refund_uuid;
                END IF;
            ELSIF TG_TABLE_NAME = 'membership_refund_settlements' THEN
                UPDATE membership_refund_workflow_guards
                   SET outcome_kind = 'settled', outcome_id = NEW.id
                 WHERE refund_id = refund_uuid
                   AND outcome_kind IS NULL
                   AND completion_id = NEW.completion_id
                RETURNING * INTO guard_row;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_refund_workflow_guards
                     WHERE refund_id = refund_uuid;
                    IF guard_row.outcome_kind = 'withdrawn' THEN
                        RAISE EXCEPTION
                            'membership refund % was already withdrawn', refund_uuid;
                    ELSIF guard_row.outcome_kind = 'settled' THEN
                        RAISE EXCEPTION
                            'membership refund % was already settled', refund_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership refund % has no committed payout completion', refund_uuid;
                    END IF;
                END IF;
                SELECT * INTO completion_row
                  FROM membership_refund_completions
                 WHERE id = NEW.completion_id
                   AND refund_id = refund_uuid;
                IF completion_row IS NULL
                   OR NEW.company_id IS DISTINCT FROM refund_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM completion_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM completion_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM completion_row.shift_id
                   OR NEW.payment_id IS DISTINCT FROM refund_row.payment_id
                   OR NEW.method IS DISTINCT FROM completion_row.method
                   OR NEW.amount_minor IS DISTINCT FROM completion_row.amount_minor
                   OR NEW.settled_at < completion_row.completed_at
                   OR NEW.external_ref IS DISTINCT FROM completion_row.external_reference
                   OR NEW.provider_evidence_reconciled IS DISTINCT FROM
                        completion_row.provider_evidence_reconciled
                   OR NEW.evidence_occurred_at IS DISTINCT FROM
                        completion_row.evidence_occurred_at
                   OR NEW.evidence_time_untrusted IS DISTINCT FROM
                        completion_row.evidence_time_untrusted
                   OR NEW.settled_by IS DISTINCT FROM completion_row.completed_by
                   OR NEW.action_takeover_confirmed IS DISTINCT FROM
                        completion_row.action_takeover_confirmed
                   OR NEW.action_takeover_reason IS DISTINCT FROM
                        completion_row.action_takeover_reason
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.settled_by
                           AND company_id = refund_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership refund % has inconsistent settlement provenance', refund_uuid;
                END IF;
                UPDATE membership_payment_refund_guards
                   SET active_refund_id = NULL, settled_refund_id = refund_uuid
                 WHERE payment_id = refund_row.payment_id
                   AND active_refund_id = refund_uuid
                   AND settled_refund_id IS NULL;
                IF NOT FOUND THEN
                    RAISE EXCEPTION
                        'membership refund % lost its payment-level claim', refund_uuid;
                END IF;
            ELSIF TG_TABLE_NAME = 'membership_refund_resolutions' THEN
                UPDATE membership_refund_workflow_guards
                   SET outcome_kind = 'withdrawn', outcome_id = NEW.id
                 WHERE refund_id = refund_uuid
                   AND outcome_kind IS NULL
                RETURNING * INTO guard_row;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_refund_workflow_guards
                     WHERE refund_id = refund_uuid;
                    IF guard_row.outcome_kind = 'settled' THEN
                        RAISE EXCEPTION
                            'membership refund % was already settled', refund_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership refund % was already withdrawn', refund_uuid;
                    END IF;
                END IF;
                IF guard_row.action_kind = 'cash_handoff' THEN
                    SELECT company_id, branch_id, terminal_id, shift_id,
                           started_at, started_by, 'cash'::text AS action_kind
                      INTO action_row
                      FROM membership_refund_cash_handoffs
                     WHERE id = guard_row.action_id
                       AND refund_id = refund_uuid;
                ELSIF guard_row.action_kind = 'provider_refund' THEN
                    SELECT company_id, branch_id, terminal_id, shift_id,
                           started_at, started_by, 'provider'::text AS action_kind
                      INTO action_row
                      FROM membership_refund_provider_actions
                     WHERE id = guard_row.action_id
                       AND refund_id = refund_uuid;
                ELSIF guard_row.action_kind = 'legacy_recovery' THEN
                    SELECT attempt.company_id, refund.branch_id, refund.terminal_id,
                           attempt.reconciliation_shift_id AS shift_id,
                           attempt.checked_at AS started_at,
                           attempt.resolved_by AS started_by,
                           CASE WHEN attempt.paid_via = 'cash'
                                THEN 'cash' ELSE 'provider' END AS action_kind
                      INTO action_row
                      FROM membership_refund_attempt_resolutions attempt
                      JOIN membership_refunds refund ON refund.id = attempt.refund_id
                     WHERE attempt.id = guard_row.action_id
                       AND attempt.refund_id = refund_uuid;
                END IF;
                IF NEW.company_id IS DISTINCT FROM refund_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM refund_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM refund_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM refund_row.shift_id
                   OR NEW.paid_via IS DISTINCT FROM refund_row.method
                   OR NEW.resolved_at < refund_row.accepted_at
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.resolved_by
                           AND company_id = refund_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership refund % has inconsistent resolution provenance', refund_uuid;
                END IF;
                IF action_row IS NULL THEN
                    IF NEW.action_takeover_confirmed IS TRUE
                       OR NEW.action_state_verified IS TRUE
                       OR (NEW.paid_via = 'cash' AND NEW.resolution <> 'cash_not_handed_over')
                       OR (NEW.paid_via <> 'cash' AND NEW.resolution <> 'provider_not_completed')
                       OR NEW.provider_verification_status IS NOT NULL
                       OR NEW.provider_verification_reference IS NOT NULL
                       OR NEW.provider_checked_at IS NOT NULL
                       OR NEW.cash_return_confirmed IS TRUE
                    THEN
                        RAISE EXCEPTION
                            'membership refund % resolution does not match its action state',
                            refund_uuid;
                    END IF;
                ELSE
                    IF action_row.company_id IS DISTINCT FROM refund_row.company_id
                       OR action_row.branch_id IS DISTINCT FROM refund_row.branch_id
                       OR action_row.terminal_id IS DISTINCT FROM refund_row.terminal_id
                       OR action_row.shift_id IS DISTINCT FROM refund_row.shift_id
                       OR (action_row.action_kind = 'cash' AND NEW.paid_via <> 'cash')
                       OR (action_row.action_kind = 'provider' AND NEW.paid_via = 'cash')
                    THEN
                        RAISE EXCEPTION
                            'membership refund % has inconsistent action provenance', refund_uuid;
                    END IF;
                    IF NEW.action_state_verified IS NOT TRUE THEN
                        RAISE EXCEPTION
                            'membership refund % action outcome was not verified', refund_uuid;
                    END IF;
                    IF action_row.action_kind = 'provider' THEN
                        IF NEW.provider_checked_at IS NULL
                           OR NEW.provider_checked_at < action_row.started_at
                           OR NEW.provider_verification_reference IS NULL
                           OR char_length(trim(NEW.provider_verification_reference)) < 1
                           OR (
                                NEW.resolution = 'provider_not_completed'
                                AND NEW.provider_verification_status <> 'not_completed'
                           )
                           OR (
                                NEW.resolution = 'provider_reversed'
                                AND (
                                    NEW.provider_verification_status <> 'reversed'
                                    OR NEW.external_reference IS DISTINCT FROM
                                       NEW.provider_verification_reference
                                )
                           )
                           OR NEW.resolution NOT IN (
                                'provider_not_completed', 'provider_reversed'
                           )
                        THEN
                            RAISE EXCEPTION
                                'membership refund % lacks verified provider outcome',
                                refund_uuid;
                        END IF;
                        IF NEW.cash_return_confirmed IS TRUE THEN
                            RAISE EXCEPTION
                                'membership refund % has cash proof on a provider action',
                                refund_uuid;
                        END IF;
                    ELSIF NEW.provider_verification_status IS NOT NULL
                       OR NEW.provider_verification_reference IS NOT NULL
                       OR NEW.provider_checked_at IS NOT NULL
                    THEN
                        RAISE EXCEPTION
                            'membership refund % has provider proof on a cash action',
                            refund_uuid;
                    END IF;
                    IF action_row.action_kind = 'cash'
                       AND (
                            (NEW.resolution = 'cash_returned'
                             AND NEW.cash_return_confirmed IS NOT TRUE)
                            OR (NEW.resolution <> 'cash_returned'
                                AND NEW.cash_return_confirmed IS TRUE)
                       )
                    THEN
                        RAISE EXCEPTION
                            'membership refund % lacks exact cash-return proof', refund_uuid;
                    END IF;
                    IF guard_row.completion_id IS NOT NULL
                       AND (
                            (action_row.action_kind = 'cash'
                             AND NEW.resolution <> 'cash_returned')
                            OR (action_row.action_kind = 'provider'
                                AND NEW.resolution <> 'provider_reversed')
                       )
                    THEN
                        RAISE EXCEPTION
                            'membership refund % payout moved and lacks reversal proof',
                            refund_uuid;
                    END IF;
                    outcome_actor := NEW.resolved_by;
                    IF action_row.started_by IS DISTINCT FROM outcome_actor THEN
                        IF NEW.action_takeover_confirmed IS NOT TRUE
                           OR char_length(trim(NEW.action_takeover_reason)) < 3
                        THEN
                            RAISE EXCEPTION
                                'membership refund % action requires verified takeover', refund_uuid;
                        END IF;
                    ELSIF NEW.action_takeover_confirmed IS TRUE THEN
                        RAISE EXCEPTION
                            'membership refund % cannot claim a same-actor takeover', refund_uuid;
                    END IF;
                END IF;
                UPDATE membership_payment_refund_guards
                   SET active_refund_id = NULL
                 WHERE payment_id = refund_row.payment_id
                   AND active_refund_id = refund_uuid
                   AND settled_refund_id IS NULL;
                IF NOT FOUND THEN
                    RAISE EXCEPTION
                        'membership refund % lost its payment-level claim', refund_uuid;
                END IF;
            ELSE
                IF NEW.company_id IS DISTINCT FROM refund_row.company_id
                   OR NEW.branch_id IS DISTINCT FROM refund_row.branch_id
                   OR NEW.terminal_id IS DISTINCT FROM refund_row.terminal_id
                   OR NEW.shift_id IS DISTINCT FROM refund_row.shift_id
                   OR NEW.started_at < refund_row.accepted_at
                   OR NOT EXISTS (
                        SELECT 1 FROM users
                         WHERE id = NEW.started_by
                           AND company_id = refund_row.company_id
                   )
                THEN
                    RAISE EXCEPTION
                        'membership refund % has inconsistent action provenance', refund_uuid;
                END IF;
                IF TG_TABLE_NAME = 'membership_refund_cash_handoffs' THEN
                    desired_action_kind := 'cash_handoff';
                    IF refund_row.method <> 'cash' THEN
                        RAISE EXCEPTION
                            'membership refund % has inconsistent cash-action provenance',
                            refund_uuid;
                    END IF;
                ELSE
                    desired_action_kind := 'provider_refund';
                    IF refund_row.method = 'cash'
                       OR NEW.method IS DISTINCT FROM refund_row.method
                    THEN
                        RAISE EXCEPTION
                            'membership refund % has inconsistent provider-action provenance',
                            refund_uuid;
                    END IF;
                END IF;
                UPDATE membership_refund_workflow_guards
                   SET action_kind = desired_action_kind, action_id = NEW.id
                 WHERE refund_id = refund_uuid
                   AND action_kind IS NULL
                   AND completion_id IS NULL
                   AND outcome_kind IS NULL
                RETURNING * INTO guard_row;
                IF guard_row IS NULL THEN
                    SELECT * INTO guard_row
                      FROM membership_refund_workflow_guards
                     WHERE refund_id = refund_uuid;
                    IF guard_row.outcome_kind IS NOT NULL THEN
                        RAISE EXCEPTION
                            'membership refund % already has a final outcome', refund_uuid;
                    ELSE
                        RAISE EXCEPTION
                            'membership refund % already has an action in progress', refund_uuid;
                    END IF;
                END IF;
            END IF;
            RETURN NEW;
        END $$
        """
    )
    for table_name, trigger_name in (
        (
            "membership_refund_completions",
            "trg_membership_refund_completions_outcome_exclusive",
        ),
        (
            "membership_refund_settlements",
            "trg_membership_refund_settlements_outcome_exclusive",
        ),
        (
            "membership_refund_resolutions",
            "trg_membership_refund_resolutions_outcome_exclusive",
        ),
        (
            "membership_refund_cash_handoffs",
            "trg_membership_refund_cash_handoffs_outcome_exclusive",
        ),
        (
            "membership_refund_provider_actions",
            "trg_membership_refund_provider_actions_outcome_exclusive",
        ),
    ):
        op.execute(
            f"""
            CREATE TRIGGER {trigger_name}
            BEFORE INSERT ON {table_name}
            FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_refund_outcome()
            """
        )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_spend_application()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            current_total bigint;
            source_amount bigint;
            source_customer uuid;
            source_company uuid;
            source_actor uuid;
            source_reconciled boolean;
            expected_delta bigint;
        BEGIN
            SELECT total_spent_minor INTO current_total
              FROM customers
             WHERE id = NEW.customer_id
               AND company_id = NEW.company_id
             FOR UPDATE;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'membership spend application has invalid customer';
            END IF;
            IF NEW.payment_id IS NOT NULL THEN
                SELECT payment.amount_minor, membership.customer_id,
                       payment.company_id, payment.created_by,
                       payment.customer_spend_reconciled
                  INTO source_amount, source_customer, source_company,
                       source_actor, source_reconciled
                  FROM membership_payments payment
                  JOIN customer_memberships membership
                    ON membership.id = payment.membership_id
                 WHERE payment.id = NEW.payment_id;
                expected_delta := source_amount;
            ELSE
                SELECT settlement.amount_minor, membership.customer_id,
                       settlement.company_id, settlement.settled_by,
                       settlement.customer_spend_reconciled
                  INTO source_amount, source_customer, source_company,
                       source_actor, source_reconciled
                  FROM membership_refund_settlements settlement
                  JOIN membership_payments payment
                    ON payment.id = settlement.payment_id
                  JOIN customer_memberships membership
                    ON membership.id = payment.membership_id
                 WHERE settlement.id = NEW.refund_settlement_id;
                expected_delta := -source_amount;
            END IF;
            IF source_amount IS NULL
               OR source_customer IS DISTINCT FROM NEW.customer_id
               OR source_company IS DISTINCT FROM NEW.company_id
               OR source_actor IS DISTINCT FROM NEW.applied_by
               OR source_reconciled IS NOT TRUE
               OR source_amount IS DISTINCT FROM NEW.source_amount_minor
               OR expected_delta IS DISTINCT FROM NEW.adjustment_minor
               OR current_total IS DISTINCT FROM NEW.after_total_spent_minor
               OR NEW.before_total_spent_minor + expected_delta
                    IS DISTINCT FROM NEW.after_total_spent_minor
            THEN
                RAISE EXCEPTION
                    'membership spend application does not match its financial source/accumulator';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_spend_application_guard
        BEFORE INSERT ON membership_customer_spend_applications
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_spend_application()
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_require_membership_spend_application()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF NEW.customer_spend_reconciled IS TRUE THEN
                IF TG_TABLE_NAME = 'membership_payments' THEN
                    IF NOT EXISTS (
                        SELECT 1
                          FROM membership_customer_spend_applications application
                          JOIN customer_memberships membership
                            ON membership.id = NEW.membership_id
                          JOIN customers customer
                            ON customer.id = membership.customer_id
                         WHERE application.payment_id = NEW.id
                           AND application.company_id = NEW.company_id
                           AND application.customer_id = membership.customer_id
                           AND application.adjustment_minor = NEW.amount_minor
                           AND customer.total_spent_minor =
                               application.after_total_spent_minor
                    ) THEN
                        RAISE EXCEPTION
                            'membership payment % claims customer spend without application fact',
                            NEW.id;
                    END IF;
                ELSE
                    IF NOT EXISTS (
                        SELECT 1
                          FROM membership_customer_spend_applications application
                          JOIN membership_payments payment
                            ON payment.id = NEW.payment_id
                          JOIN customer_memberships membership
                            ON membership.id = payment.membership_id
                          JOIN customers customer
                            ON customer.id = membership.customer_id
                         WHERE application.refund_settlement_id = NEW.id
                           AND application.company_id = NEW.company_id
                           AND application.customer_id = membership.customer_id
                           AND application.adjustment_minor = -NEW.amount_minor
                           AND customer.total_spent_minor =
                               application.after_total_spent_minor
                    ) THEN
                        RAISE EXCEPTION
                            'membership refund settlement % claims customer spend without application fact',
                            NEW.id;
                    END IF;
                END IF;
            END IF;
            RETURN NULL;
        END $$
        """
    )
    op.execute(
        """
        CREATE CONSTRAINT TRIGGER trg_membership_payment_spend_application
        AFTER INSERT ON membership_payments
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION dcompany_require_membership_spend_application()
        """
    )
    op.execute(
        """
        CREATE CONSTRAINT TRIGGER trg_membership_refund_spend_application
        AFTER INSERT ON membership_refund_settlements
        DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION dcompany_require_membership_spend_application()
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_evidence_reconciliation()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            source_company uuid;
            provider_pending boolean;
            time_pending boolean;
        BEGIN
            IF NEW.payment_id IS NOT NULL THEN
                SELECT company_id, NOT provider_evidence_reconciled,
                       evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_payments WHERE id = NEW.payment_id;
            ELSIF NEW.refund_settlement_id IS NOT NULL THEN
                SELECT company_id, NOT provider_evidence_reconciled,
                       evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_refund_settlements
                 WHERE id = NEW.refund_settlement_id;
            ELSIF NEW.payment_completion_id IS NOT NULL THEN
                SELECT company_id, NOT provider_evidence_reconciled,
                       evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_payment_completions
                 WHERE id = NEW.payment_completion_id;
            ELSIF NEW.refund_completion_id IS NOT NULL THEN
                SELECT company_id, NOT provider_evidence_reconciled,
                       evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_refund_completions
                 WHERE id = NEW.refund_completion_id;
            ELSIF NEW.payment_request_resolution_id IS NOT NULL THEN
                SELECT company_id, NOT provider_evidence_reconciled,
                       provider_evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_payment_request_resolutions
                 WHERE id = NEW.payment_request_resolution_id;
            ELSIF NEW.refund_resolution_id IS NOT NULL THEN
                SELECT company_id, NOT provider_evidence_reconciled,
                       provider_evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_refund_resolutions
                 WHERE id = NEW.refund_resolution_id;
            ELSIF NEW.payment_attempt_resolution_id IS NOT NULL THEN
                SELECT company_id, NOT provider_evidence_reconciled,
                       evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_payment_attempt_resolutions
                 WHERE id = NEW.payment_attempt_resolution_id;
            ELSE
                SELECT company_id, NOT provider_evidence_reconciled,
                       evidence_time_untrusted
                  INTO source_company, provider_pending, time_pending
                  FROM membership_refund_attempt_resolutions
                 WHERE id = NEW.refund_attempt_resolution_id;
            END IF;
            IF source_company IS NULL
               OR source_company IS DISTINCT FROM NEW.company_id
               OR NOT EXISTS (
                    SELECT 1 FROM users
                     WHERE id = NEW.reconciled_by
                       AND company_id = NEW.company_id
               )
               OR (NEW.evidence_kind = 'provider_reference' AND provider_pending IS NOT TRUE)
               OR (NEW.evidence_kind = 'captured_time' AND time_pending IS NOT TRUE)
               OR (NEW.evidence_kind = 'captured_time'
                   AND NEW.verified_occurred_at IS NULL)
               OR (NEW.evidence_kind = 'provider_reference'
                   AND NEW.verified_occurred_at IS NOT NULL)
            THEN
                RAISE EXCEPTION
                    'membership evidence reconciliation source is invalid or not pending';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_evidence_reconciliation_guard
        BEFORE INSERT ON membership_evidence_reconciliations
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_evidence_reconciliation()
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_payment_attempt_resolution()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            shift_row record;
        BEGIN
            SELECT company_id, branch_id, terminal_id, opened_at
              INTO shift_row
              FROM shifts WHERE id = NEW.shift_id;
            IF shift_row IS NULL
               OR shift_row.company_id IS DISTINCT FROM NEW.company_id
               OR shift_row.branch_id IS DISTINCT FROM NEW.branch_id
               OR shift_row.terminal_id IS DISTINCT FROM NEW.terminal_id
               OR NOT EXISTS (
                    SELECT 1 FROM customers
                     WHERE id = NEW.customer_id
                       AND company_id = NEW.company_id
               )
               OR NOT EXISTS (
                    SELECT 1 FROM membership_tiers
                     WHERE id = NEW.tier_id
                       AND company_id = NEW.company_id
               )
               OR NOT EXISTS (
                    SELECT 1 FROM users
                     WHERE id = NEW.resolved_by
                       AND company_id = NEW.company_id
               )
               OR EXISTS (
                    SELECT 1 FROM membership_payments
                     WHERE company_id = NEW.company_id
                       AND idempotency_key = NEW.original_client_action_id
               )
               OR EXISTS (
                    SELECT 1 FROM membership_payment_requests
                     WHERE company_id = NEW.company_id
                       AND client_action_id = NEW.original_client_action_id
               )
               OR NEW.resolved_at < shift_row.opened_at
               OR NEW.provider_checked_at > NEW.resolved_at
            THEN
                RAISE EXCEPTION
                    'membership payment-attempt resolution has inconsistent tenant/action provenance';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_payment_attempt_resolution_provenance
        BEFORE INSERT ON membership_payment_attempt_resolutions
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_payment_attempt_resolution()
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_refund_attempt_recovery()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            payment_row record;
            source_shift record;
        BEGIN
            SELECT payment.company_id, payment.method, payment.amount_minor,
                   membership.id AS membership_id, membership.customer_id
              INTO payment_row
              FROM membership_payments payment
              JOIN customer_memberships membership
                ON membership.id = payment.membership_id
             WHERE payment.id = NEW.payment_id;
            SELECT company_id, branch_id, terminal_id
              INTO source_shift
              FROM shifts WHERE id = NEW.source_shift_id;
            IF payment_row IS NULL
               OR payment_row.company_id IS DISTINCT FROM NEW.company_id
               OR payment_row.membership_id IS DISTINCT FROM NEW.membership_id
               OR payment_row.customer_id IS DISTINCT FROM NEW.customer_id
               OR payment_row.method IS DISTINCT FROM NEW.paid_via
               OR payment_row.amount_minor IS DISTINCT FROM NEW.expected_amount_minor
               OR source_shift IS NULL
               OR source_shift.company_id IS DISTINCT FROM NEW.company_id
               OR source_shift.branch_id IS DISTINCT FROM NEW.source_branch_id
               OR source_shift.terminal_id IS DISTINCT FROM NEW.source_terminal_id
               OR EXISTS (
                    SELECT 1 FROM membership_refunds
                     WHERE company_id = NEW.company_id
                       AND idempotency_key = NEW.original_client_action_id
               )
               OR (
                    (NEW.captured_at < (
                        SELECT opened_at FROM shifts WHERE id = NEW.source_shift_id
                    )
                     OR NEW.captured_at > NEW.registered_at + interval '5 minutes'
                     OR NEW.captured_at < NEW.registered_at - interval '7 days')
                    AND NEW.captured_time_untrusted IS NOT TRUE
               )
               OR NOT EXISTS (
                    SELECT 1 FROM users
                     WHERE id = NEW.registered_by
                       AND company_id = NEW.company_id
               )
            THEN
                RAISE EXCEPTION
                    'membership refund-attempt recovery has inconsistent tenant/payment/shift provenance';
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_refund_attempt_recovery_provenance
        BEFORE INSERT ON membership_refund_attempt_recoveries
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_refund_attempt_recovery()
        """
    )
    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_refund_attempt_resolution()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        DECLARE
            payment_row record;
            source_shift record;
            reconciliation_shift record;
            refund_row record;
            recovery_row record;
        BEGIN
            SELECT * INTO recovery_row
              FROM membership_refund_attempt_recoveries
             WHERE id = NEW.recovery_id;
            SELECT payment.company_id, payment.method, payment.amount_minor,
                   membership.id AS membership_id,
                   membership.customer_id
              INTO payment_row
              FROM membership_payments payment
              JOIN customer_memberships membership
                ON membership.id = payment.membership_id
             WHERE payment.id = NEW.payment_id;
            SELECT company_id, branch_id, terminal_id
              INTO source_shift
              FROM shifts WHERE id = NEW.source_shift_id;
            IF payment_row IS NULL
               OR recovery_row IS NULL
               OR recovery_row.company_id IS DISTINCT FROM NEW.company_id
               OR recovery_row.customer_id IS DISTINCT FROM NEW.customer_id
               OR recovery_row.membership_id IS DISTINCT FROM NEW.membership_id
               OR recovery_row.payment_id IS DISTINCT FROM NEW.payment_id
               OR recovery_row.source_branch_id IS DISTINCT FROM NEW.source_branch_id
               OR recovery_row.source_terminal_id IS DISTINCT FROM NEW.source_terminal_id
               OR recovery_row.source_shift_id IS DISTINCT FROM NEW.source_shift_id
               OR recovery_row.original_client_action_id IS DISTINCT FROM
                  NEW.original_client_action_id
               OR recovery_row.paid_via IS DISTINCT FROM NEW.paid_via
               OR recovery_row.expected_amount_minor IS DISTINCT FROM
                  NEW.expected_amount_minor
               OR payment_row.company_id IS DISTINCT FROM NEW.company_id
               OR payment_row.membership_id IS DISTINCT FROM NEW.membership_id
               OR payment_row.customer_id IS DISTINCT FROM NEW.customer_id
               OR payment_row.method IS DISTINCT FROM NEW.paid_via
               OR payment_row.amount_minor IS DISTINCT FROM NEW.expected_amount_minor
               OR source_shift IS NULL
               OR source_shift.company_id IS DISTINCT FROM NEW.company_id
               OR source_shift.branch_id IS DISTINCT FROM NEW.source_branch_id
               OR source_shift.terminal_id IS DISTINCT FROM NEW.source_terminal_id
               OR NOT EXISTS (
                    SELECT 1 FROM users
                     WHERE id = NEW.resolved_by
                       AND company_id = NEW.company_id
               )
               OR NEW.checked_at > NEW.resolved_at
               OR NEW.checked_at < recovery_row.registered_at
            THEN
                RAISE EXCEPTION
                    'membership refund-attempt resolution has inconsistent tenant/payment/shift provenance';
            END IF;
            -- No-payout and reversed outcomes deliberately have no reconciliation
            -- shift/refund. Do not dereference an unassigned PL/pgSQL record for
            -- those valid nullable paths. Completed payouts still fail closed on
            -- every reconciliation-shift and refund provenance field.
            IF NEW.refund_id IS NOT NULL THEN
                IF NEW.reconciliation_shift_id IS NULL THEN
                    RAISE EXCEPTION
                        'membership refund-attempt resolution has inconsistent tenant/payment/shift provenance';
                END IF;
                SELECT company_id, branch_id, terminal_id, status, opened_at
                  INTO reconciliation_shift
                  FROM shifts WHERE id = NEW.reconciliation_shift_id;
                IF NOT FOUND THEN
                    RAISE EXCEPTION
                        'membership refund-attempt resolution has inconsistent tenant/payment/shift provenance';
                END IF;
                SELECT company_id, payment_id, branch_id, terminal_id, shift_id,
                       method, amount_minor
                  INTO refund_row
                  FROM membership_refunds WHERE id = NEW.refund_id;
                IF NOT FOUND
                   OR reconciliation_shift.company_id IS DISTINCT FROM NEW.company_id
                   OR reconciliation_shift.status <> 'open'
                   OR refund_row.company_id IS DISTINCT FROM NEW.company_id
                   OR refund_row.payment_id IS DISTINCT FROM NEW.payment_id
                   OR refund_row.shift_id IS DISTINCT FROM NEW.reconciliation_shift_id
                   OR refund_row.branch_id IS DISTINCT FROM reconciliation_shift.branch_id
                   OR refund_row.terminal_id IS DISTINCT FROM reconciliation_shift.terminal_id
                   OR refund_row.method IS DISTINCT FROM NEW.paid_via
                   OR refund_row.amount_minor IS DISTINCT FROM NEW.expected_amount_minor
                THEN
                    RAISE EXCEPTION
                        'membership refund-attempt resolution has inconsistent tenant/payment/shift provenance';
                END IF;
            END IF;
            RETURN NEW;
        END $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER trg_membership_refund_attempt_resolution_provenance
        BEFORE INSERT ON membership_refund_attempt_resolutions
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_refund_attempt_resolution()
        """
    )

    op.execute(
        """
        CREATE OR REPLACE FUNCTION dcompany_guard_membership_immutable()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            RAISE EXCEPTION '% is append-only; % is forbidden', TG_TABLE_NAME, TG_OP;
        END $$
        """
    )
    for table_name, trigger_name in (
        ("membership_payment_requests", "trg_membership_payment_requests_immutable"),
        ("membership_payments", "trg_membership_payments_immutable"),
        (
            "membership_payment_cash_collections",
            "trg_membership_payment_cash_collections_immutable",
        ),
        (
            "membership_payment_provider_actions",
            "trg_membership_payment_provider_actions_immutable",
        ),
        (
            "membership_payment_completions",
            "trg_membership_payment_completions_immutable",
        ),
        (
            "membership_payment_request_resolutions",
            "trg_membership_payment_request_resolutions_immutable",
        ),
        ("membership_refunds", "trg_membership_refunds_immutable"),
        (
            "membership_refund_cash_handoffs",
            "trg_membership_refund_cash_handoffs_immutable",
        ),
        (
            "membership_refund_provider_actions",
            "trg_membership_refund_provider_actions_immutable",
        ),
        (
            "membership_refund_completions",
            "trg_membership_refund_completions_immutable",
        ),
        (
            "membership_refund_settlements",
            "trg_membership_refund_settlements_immutable",
        ),
        (
            "membership_refund_resolutions",
            "trg_membership_refund_resolutions_immutable",
        ),
        (
            "membership_payment_attempt_resolutions",
            "trg_membership_payment_attempt_resolutions_immutable",
        ),
        (
            "membership_refund_attempt_recoveries",
            "trg_membership_refund_attempt_recoveries_immutable",
        ),
        (
            "membership_refund_attempt_resolutions",
            "trg_membership_refund_attempt_resolutions_immutable",
        ),
        (
            "membership_customer_spend_applications",
            "trg_membership_customer_spend_applications_immutable",
        ),
        (
            "membership_evidence_reconciliations",
            "trg_membership_evidence_reconciliations_immutable",
        ),
    ):
        op.execute(
            f"""
            CREATE TRIGGER {trigger_name}
            BEFORE UPDATE OR DELETE ON {table_name}
            FOR EACH ROW EXECUTE FUNCTION dcompany_guard_membership_immutable()
            """
        )


def downgrade() -> None:
    # 0034 cannot represent reservation/action/completion/recovery facts. Refuse
    # to erase them; only a genuinely unused 0035 schema can be reversed.
    workflow_tables = (
        "membership_payment_requests",
        "membership_payment_cash_collections",
        "membership_payment_provider_actions",
        "membership_payment_completions",
        "membership_payment_request_resolutions",
        "membership_payment_attempt_resolutions",
        "membership_refund_cash_handoffs",
        "membership_refund_provider_actions",
        "membership_refund_completions",
        "membership_refund_attempt_recoveries",
        "membership_refund_attempt_resolutions",
        "membership_customer_spend_applications",
        "membership_evidence_reconciliations",
    )
    table_predicates = " OR ".join(
        f"EXISTS (SELECT 1 FROM {table_name})" for table_name in workflow_tables
    )
    op.execute(
        f"""
        DO $$
        BEGIN
            IF {table_predicates}
               OR EXISTS (
                    SELECT 1 FROM membership_payments
                     WHERE request_id IS NOT NULL
                        OR completion_id IS NOT NULL
                        OR external_reference IS NOT NULL
                        OR evidence_occurred_at IS NOT NULL
                        OR evidence_time_untrusted IS TRUE
                        OR customer_spend_reconciled IS TRUE
                        OR action_takeover_confirmed IS TRUE
                        OR action_takeover_reason IS NOT NULL
               )
               OR EXISTS (
                    SELECT 1 FROM membership_refund_settlements
                     WHERE completion_id IS NOT NULL
                        OR evidence_occurred_at IS NOT NULL
                        OR evidence_time_untrusted IS TRUE
                        OR customer_spend_reconciled IS TRUE
                        OR action_takeover_confirmed IS TRUE
                        OR action_takeover_reason IS NOT NULL
               )
               OR EXISTS (
                    SELECT 1 FROM membership_refund_resolutions
                     WHERE paid_via <> 'cash'
                        OR resolution <> 'cash_not_handed_over'
                        OR external_reference IS NOT NULL
                        OR action_state_verified IS TRUE
                        OR provider_verification_status IS NOT NULL
                        OR provider_verification_reference IS NOT NULL
                        OR provider_checked_at IS NOT NULL
                        OR provider_evidence_occurred_at IS NOT NULL
                        OR provider_evidence_time_untrusted IS TRUE
                        OR cash_return_confirmed IS TRUE
                        OR action_takeover_confirmed IS TRUE
                        OR action_takeover_reason IS NOT NULL
               )
            THEN
                RAISE EXCEPTION
                    'cannot downgrade 0035 after membership reservation workflow use';
            END IF;
        END $$
        """
    )

    immutable_triggers = (
        ("membership_payment_requests", "trg_membership_payment_requests_immutable"),
        ("membership_payments", "trg_membership_payments_immutable"),
        ("membership_payment_cash_collections", "trg_membership_payment_cash_collections_immutable"),
        ("membership_payment_provider_actions", "trg_membership_payment_provider_actions_immutable"),
        ("membership_payment_completions", "trg_membership_payment_completions_immutable"),
        ("membership_payment_request_resolutions", "trg_membership_payment_request_resolutions_immutable"),
        ("membership_refunds", "trg_membership_refunds_immutable"),
        ("membership_refund_cash_handoffs", "trg_membership_refund_cash_handoffs_immutable"),
        ("membership_refund_provider_actions", "trg_membership_refund_provider_actions_immutable"),
        ("membership_refund_completions", "trg_membership_refund_completions_immutable"),
        ("membership_refund_settlements", "trg_membership_refund_settlements_immutable"),
        ("membership_refund_resolutions", "trg_membership_refund_resolutions_immutable"),
        ("membership_payment_attempt_resolutions", "trg_membership_payment_attempt_resolutions_immutable"),
        ("membership_refund_attempt_recoveries", "trg_membership_refund_attempt_recoveries_immutable"),
        ("membership_refund_attempt_resolutions", "trg_membership_refund_attempt_resolutions_immutable"),
        ("membership_customer_spend_applications", "trg_membership_customer_spend_applications_immutable"),
        ("membership_evidence_reconciliations", "trg_membership_evidence_reconciliations_immutable"),
    )
    for table_name, trigger_name in immutable_triggers:
        op.execute(f"DROP TRIGGER IF EXISTS {trigger_name} ON {table_name}")

    payment_outcome_tables = (
        "membership_payment_completions",
        "membership_payments",
        "membership_payment_request_resolutions",
        "membership_payment_cash_collections",
        "membership_payment_provider_actions",
    )
    for table_name in payment_outcome_tables:
        op.execute(
            f"DROP TRIGGER IF EXISTS trg_{table_name}_outcome_exclusive ON {table_name}"
        )
    refund_outcome_tables = (
        "membership_refund_completions",
        "membership_refund_settlements",
        "membership_refund_resolutions",
        "membership_refund_cash_handoffs",
        "membership_refund_provider_actions",
    )
    for table_name in refund_outcome_tables:
        op.execute(
            f"DROP TRIGGER IF EXISTS trg_{table_name}_outcome_exclusive ON {table_name}"
        )

    explicit_triggers = (
        ("membership_payment_requests", "trg_membership_payment_requests_provenance"),
        ("membership_refunds", "trg_membership_refunds_provenance"),
        ("membership_payment_requests", "trg_membership_payment_requests_init_guard"),
        ("membership_refunds", "trg_membership_refunds_init_guard"),
        ("membership_payments", "trg_membership_payments_init_refund_guard"),
        ("membership_payment_workflow_guards", "trg_membership_payment_workflow_guards_internal"),
        ("membership_refund_workflow_guards", "trg_membership_refund_workflow_guards_internal"),
        ("membership_payment_refund_guards", "trg_membership_payment_refund_guards_internal"),
        ("membership_customer_spend_applications", "trg_membership_spend_application_guard"),
        ("membership_payments", "trg_membership_payment_spend_application"),
        ("membership_refund_settlements", "trg_membership_refund_spend_application"),
        ("membership_evidence_reconciliations", "trg_membership_evidence_reconciliation_guard"),
        ("membership_payment_attempt_resolutions", "trg_membership_payment_attempt_resolution_provenance"),
        ("membership_refund_attempt_recoveries", "trg_membership_refund_attempt_recovery_provenance"),
        ("membership_refund_attempt_resolutions", "trg_membership_refund_attempt_resolution_provenance"),
    )
    for table_name, trigger_name in explicit_triggers:
        op.execute(f"DROP TRIGGER IF EXISTS {trigger_name} ON {table_name}")

    for function_name in (
        "dcompany_guard_membership_immutable",
        "dcompany_guard_membership_payment_outcome",
        "dcompany_guard_membership_refund_outcome",
        "dcompany_guard_membership_payment_request",
        "dcompany_guard_membership_refund_request",
        "dcompany_init_membership_workflow_guard",
        "dcompany_guard_membership_workflow_guard_dml",
        "dcompany_guard_membership_spend_application",
        "dcompany_require_membership_spend_application",
        "dcompany_guard_membership_evidence_reconciliation",
        "dcompany_guard_membership_payment_attempt_resolution",
        "dcompany_guard_membership_refund_attempt_recovery",
        "dcompany_guard_membership_refund_attempt_resolution",
    ):
        op.execute(f"DROP FUNCTION IF EXISTS {function_name}()")

    # Private CAS rows are implementation state, not audit facts. They can be
    # removed only because the guard above proved every 0035 domain table empty.
    op.drop_table("membership_payment_workflow_guards")
    op.drop_table("membership_refund_workflow_guards")
    op.drop_table("membership_payment_refund_guards")

    op.drop_table("membership_evidence_reconciliations")
    op.drop_table("membership_customer_spend_applications")

    op.drop_constraint(
        "uq_membership_refund_settlement_completion",
        "membership_refund_settlements",
        type_="unique",
    )
    op.drop_constraint(
        "fk_membership_refund_settlements_completion_id",
        "membership_refund_settlements",
        type_="foreignkey",
    )
    op.drop_column("membership_refund_settlements", "completion_id")
    op.drop_table("membership_refund_completions")
    op.drop_table("membership_refund_attempt_resolutions")
    op.drop_table("membership_refund_attempt_recoveries")
    op.drop_table("membership_payment_attempt_resolutions")
    op.drop_table("membership_refund_provider_actions")
    op.drop_table("membership_refund_cash_handoffs")

    op.drop_constraint(
        "uq_membership_refund_settlement_provider_reference",
        "membership_refund_settlements",
        type_="unique",
    )
    op.drop_constraint(
        "ck_membership_refund_settlement_takeover",
        "membership_refund_settlements",
        type_="check",
    )
    op.drop_constraint(
        "ck_membership_refund_settlement_external_reference",
        "membership_refund_settlements",
        type_="check",
    )
    op.create_check_constraint(
        "ck_membership_refund_settlement_external_reference",
        "membership_refund_settlements",
        "(method = 'cash' AND external_ref IS NULL) OR "
        "(method <> 'cash' AND char_length(trim(external_ref)) >= 3)",
    )
    for column_name in (
        "action_takeover_reason",
        "action_takeover_confirmed",
        "customer_spend_reconciled",
        "evidence_time_untrusted",
        "evidence_occurred_at",
        "provider_evidence_reconciled",
    ):
        op.drop_column("membership_refund_settlements", column_name)

    op.drop_constraint(
        "uq_membership_refund_resolution_provider_reference",
        "membership_refund_resolutions",
        type_="unique",
    )
    op.drop_constraint(
        "ck_membership_refund_resolution_takeover",
        "membership_refund_resolutions",
        type_="check",
    )
    op.drop_constraint(
        "ck_membership_refund_resolution_type",
        "membership_refund_resolutions",
        type_="check",
    )
    op.alter_column(
        "membership_refund_resolutions",
        "paid_via",
        existing_type=sa.String(length=20),
        nullable=True,
    )
    for column_name in (
        "action_takeover_reason",
        "action_takeover_confirmed",
        "cash_return_confirmed",
        "provider_evidence_time_untrusted",
        "provider_evidence_occurred_at",
        "provider_checked_at",
        "provider_verification_reference",
        "provider_verification_status",
        "action_state_verified",
        "provider_evidence_reconciled",
        "external_reference",
        "paid_via",
    ):
        op.drop_column("membership_refund_resolutions", column_name)
    op.create_check_constraint(
        "ck_membership_refund_resolution_type",
        "membership_refund_resolutions",
        "resolution = 'cash_not_handed_over'",
    )

    op.drop_constraint(
        "ck_membership_payment_workflow_linkage",
        "membership_payments",
        type_="check",
    )
    op.drop_constraint(
        "ck_membership_payment_request_evidence",
        "membership_payments",
        type_="check",
    )
    op.drop_constraint(
        "ck_membership_payment_action_takeover",
        "membership_payments",
        type_="check",
    )
    op.drop_constraint(
        "uq_membership_payment_provider_reference",
        "membership_payments",
        type_="unique",
    )
    op.drop_constraint(
        "uq_membership_payment_completion",
        "membership_payments",
        type_="unique",
    )
    op.drop_constraint(
        "uq_membership_payment_request",
        "membership_payments",
        type_="unique",
    )
    op.drop_constraint(
        "fk_membership_payments_completion_id",
        "membership_payments",
        type_="foreignkey",
    )
    op.drop_constraint(
        "fk_membership_payments_request_id",
        "membership_payments",
        type_="foreignkey",
    )
    op.drop_column("membership_payments", "completion_id")
    op.drop_table("membership_payment_completions")
    for column_name in (
        "action_takeover_reason",
        "action_takeover_confirmed",
        "customer_spend_reconciled",
        "evidence_time_untrusted",
        "evidence_occurred_at",
        "provider_evidence_reconciled",
        "external_reference",
        "request_id",
    ):
        op.drop_column("membership_payments", column_name)

    op.drop_table("membership_payment_request_resolutions")
    op.drop_table("membership_payment_provider_actions")
    op.drop_table("membership_payment_cash_collections")
    op.drop_table("membership_payment_requests")
