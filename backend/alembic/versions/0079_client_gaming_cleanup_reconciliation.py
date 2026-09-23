"""Add revisioned tenant-scoped Gaming cleanup reconciliation ledger.

Revision ID: 0079
Revises: 0078
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision = "0079"
down_revision = "0078"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "client_gaming_cleanup_reconciliation",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("company_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_installation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("branch_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("terminal_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("station_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("local_action_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("server_session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("reported_local_state", sa.String(length=32), nullable=False),
        sa.Column("local_evidence_revision", sa.BigInteger(), nullable=False),
        sa.Column("local_snapshot", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("local_snapshot_sha256", sa.String(length=64), nullable=False),
        sa.Column("start_request_hash", sa.String(length=64), nullable=False),
        sa.Column("stop_request_hash", sa.String(length=64), nullable=False),
        sa.Column("original_action_user_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("candidate_sha256", sa.String(length=64), nullable=False),
        sa.Column("unresolved_child_count", sa.BigInteger(), nullable=False),
        sa.Column("cleanup_receipt_audit_id", sa.BigInteger(), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("reported_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("approved_at", sa.DateTime(timezone=True)),
        sa.Column("approved_by", postgresql.UUID(as_uuid=True)),
        sa.Column("approval_reason", sa.String(length=500)),
        sa.Column("approval_idempotency_key", sa.String(length=200)),
        sa.Column("applied_at", sa.DateTime(timezone=True)),
        sa.Column("applied_by", postgresql.UUID(as_uuid=True)),
        sa.Column("superseded_at", sa.DateTime(timezone=True)),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.ForeignKeyConstraint(["company_id"], ["companies.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["branch_id"], ["branches.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["terminal_id"], ["terminals.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["station_id"], ["stations.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["approved_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["applied_by"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(["original_action_user_id"], ["users.id"], ondelete="RESTRICT"),
        sa.ForeignKeyConstraint(
            ["cleanup_receipt_audit_id"], ["audit_log.id"], ondelete="RESTRICT"
        ),
        sa.ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_client_gaming_cleanup_scoped_installation",
            ondelete="RESTRICT",
        ),
        sa.UniqueConstraint(
            "company_id",
            "client_installation_id",
            "local_action_id",
            "revision",
            name="uq_client_gaming_cleanup_installation_action_revision",
        ),
        sa.UniqueConstraint(
            "company_id",
            "client_installation_id",
            "server_session_id",
            "revision",
            name="uq_client_gaming_cleanup_installation_session_revision",
        ),
        sa.CheckConstraint(
            "status IN ('reported', 'approved', 'applied', 'superseded')",
            name="ck_client_gaming_cleanup_status",
        ),
        sa.CheckConstraint(
            "revision BETWEEN 1 AND 32 AND local_evidence_revision >= 0",
            name="ck_client_gaming_cleanup_revisions",
        ),
        sa.CheckConstraint(
            "reported_local_state IN ('stop_pending', 'stop_rejected', 'ended_unbilled', "
            "'send_pending', 'send_rejected')",
            name="ck_client_gaming_cleanup_reported_state",
        ),
        sa.CheckConstraint(
            "candidate_sha256 ~ '^[0-9a-f]{64}$' AND "
            "local_snapshot_sha256 ~ '^[0-9a-f]{64}$' AND "
            "start_request_hash ~ '^[0-9a-f]{64}$' AND "
            "stop_request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_client_gaming_cleanup_hashes",
        ),
        sa.CheckConstraint(
            "unresolved_child_count BETWEEN 0 AND 1000000",
            name="ck_client_gaming_cleanup_child_count",
        ),
        sa.CheckConstraint(
            "(status = 'reported' AND approved_at IS NULL AND approved_by IS NULL "
            "AND approval_reason IS NULL AND approval_idempotency_key IS NULL "
            "AND applied_at IS NULL AND applied_by IS NULL AND superseded_at IS NULL) OR "
            "(status = 'approved' AND approved_at IS NOT NULL AND approved_by IS NOT NULL "
            "AND approval_reason IS NOT NULL AND approval_idempotency_key IS NOT NULL "
            "AND applied_at IS NULL AND applied_by IS NULL AND superseded_at IS NULL) OR "
            "(status = 'applied' AND approved_at IS NOT NULL AND approved_by IS NOT NULL "
            "AND approval_reason IS NOT NULL AND approval_idempotency_key IS NOT NULL "
            "AND applied_at IS NOT NULL AND applied_by IS NOT NULL AND superseded_at IS NULL) OR "
            "(status = 'superseded' AND applied_at IS NULL AND applied_by IS NULL "
            "AND superseded_at IS NOT NULL AND ((approved_at IS NULL AND approved_by IS NULL "
            "AND approval_reason IS NULL AND approval_idempotency_key IS NULL) OR "
            "(approved_at IS NOT NULL AND approved_by IS NOT NULL "
            "AND approval_reason IS NOT NULL AND approval_idempotency_key IS NOT NULL)))",
            name="ck_client_gaming_cleanup_complete_evidence",
        ),
    )
    op.create_index(
        "ix_client_gaming_cleanup_company_branch_status",
        "client_gaming_cleanup_reconciliation",
        ["company_id", "branch_id", "status", "reported_at"],
    )
    op.create_index(
        "ix_client_gaming_cleanup_reconciliation_company_id",
        "client_gaming_cleanup_reconciliation",
        ["company_id"],
    )
    op.create_index(
        "uq_client_gaming_cleanup_current_action",
        "client_gaming_cleanup_reconciliation",
        ["company_id", "client_installation_id", "local_action_id"],
        unique=True,
        postgresql_where=sa.text("status <> 'superseded'"),
    )
    op.create_index(
        "uq_client_gaming_cleanup_current_session",
        "client_gaming_cleanup_reconciliation",
        ["company_id", "client_installation_id", "server_session_id"],
        unique=True,
        postgresql_where=sa.text("status <> 'superseded'"),
    )
    op.execute(
        """
        CREATE FUNCTION dcompany_guard_client_gaming_cleanup_reconciliation()
        RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF TG_OP = 'DELETE' THEN
                RAISE EXCEPTION 'Gaming cleanup reconciliation evidence cannot be deleted'
                    USING ERRCODE = '23514';
            END IF;
            IF ROW(NEW.company_id, NEW.client_installation_id, NEW.branch_id,
                   NEW.terminal_id, NEW.station_id, NEW.local_action_id,
                   NEW.server_session_id, NEW.revision, NEW.reported_local_state,
                   NEW.local_evidence_revision, NEW.local_snapshot,
                   NEW.local_snapshot_sha256, NEW.start_request_hash,
                   NEW.stop_request_hash, NEW.original_action_user_id, NEW.candidate_sha256,
                   NEW.unresolved_child_count, NEW.cleanup_receipt_audit_id,
                   NEW.reported_at, NEW.created_at)
               IS DISTINCT FROM
               ROW(OLD.company_id, OLD.client_installation_id, OLD.branch_id,
                   OLD.terminal_id, OLD.station_id, OLD.local_action_id,
                   OLD.server_session_id, OLD.revision, OLD.reported_local_state,
                   OLD.local_evidence_revision, OLD.local_snapshot,
                   OLD.local_snapshot_sha256, OLD.start_request_hash,
                   OLD.stop_request_hash, OLD.original_action_user_id, OLD.candidate_sha256,
                   OLD.unresolved_child_count, OLD.cleanup_receipt_audit_id,
                   OLD.reported_at, OLD.created_at) THEN
                RAISE EXCEPTION 'Gaming cleanup reconciliation identity is immutable'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.status <> OLD.status AND NOT (
                (OLD.status = 'reported' AND NEW.status = 'approved') OR
                (OLD.status = 'approved' AND NEW.status = 'applied') OR
                (OLD.status = 'reported' AND NEW.status = 'superseded') OR
                (OLD.status = 'approved' AND NEW.status = 'superseded')
            ) THEN
                RAISE EXCEPTION 'invalid Gaming cleanup reconciliation transition'
                    USING ERRCODE = '23514';
            END IF;
            IF NEW.status = OLD.status AND ROW(
                NEW.approved_at, NEW.approved_by, NEW.approval_reason,
                NEW.approval_idempotency_key, NEW.applied_at, NEW.applied_by,
                NEW.superseded_at
            ) IS DISTINCT FROM ROW(
                OLD.approved_at, OLD.approved_by, OLD.approval_reason,
                OLD.approval_idempotency_key, OLD.applied_at, OLD.applied_by,
                OLD.superseded_at
            ) THEN
                RAISE EXCEPTION 'Gaming cleanup transition evidence is immutable'
                    USING ERRCODE = '23514';
            END IF;
            IF (
                (OLD.status = 'approved' AND NEW.status = 'applied') OR
                (OLD.status IN ('reported', 'approved') AND NEW.status = 'superseded')
            ) AND ROW(
                NEW.approved_at, NEW.approved_by, NEW.approval_reason,
                NEW.approval_idempotency_key
            ) IS DISTINCT FROM ROW(
                OLD.approved_at, OLD.approved_by, OLD.approval_reason,
                OLD.approval_idempotency_key
            ) THEN
                RAISE EXCEPTION 'Gaming cleanup approval evidence is immutable'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END $$;
        CREATE TRIGGER trg_client_gaming_cleanup_reconciliation_guard
        BEFORE UPDATE OR DELETE ON client_gaming_cleanup_reconciliation
        FOR EACH ROW EXECUTE FUNCTION dcompany_guard_client_gaming_cleanup_reconciliation();
        """
    )


def downgrade() -> None:
    op.execute("LOCK TABLE client_gaming_cleanup_reconciliation IN ACCESS EXCLUSIVE MODE")
    evidence_exists = bool(
        op.get_bind()
        .execute(sa.text("SELECT EXISTS (SELECT 1 FROM client_gaming_cleanup_reconciliation)"))
        .scalar_one()
    )
    if evidence_exists:
        raise RuntimeError("0079 downgrade refused: Gaming cleanup reconciliation evidence exists")
    op.execute(
        "DROP TRIGGER IF EXISTS trg_client_gaming_cleanup_reconciliation_guard "
        "ON client_gaming_cleanup_reconciliation"
    )
    op.execute("DROP FUNCTION IF EXISTS dcompany_guard_client_gaming_cleanup_reconciliation()")
    op.drop_index(
        "uq_client_gaming_cleanup_current_session",
        table_name="client_gaming_cleanup_reconciliation",
    )
    op.drop_index(
        "uq_client_gaming_cleanup_current_action",
        table_name="client_gaming_cleanup_reconciliation",
    )
    op.drop_index(
        "ix_client_gaming_cleanup_reconciliation_company_id",
        table_name="client_gaming_cleanup_reconciliation",
    )
    op.drop_index(
        "ix_client_gaming_cleanup_company_branch_status",
        table_name="client_gaming_cleanup_reconciliation",
    )
    op.drop_table("client_gaming_cleanup_reconciliation")
