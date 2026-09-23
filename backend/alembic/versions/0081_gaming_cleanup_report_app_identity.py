"""Add immutable report-time app identity to Gaming cleanup evidence.

Revision ID: 0081
Revises: 0080
"""

from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = "0081"
down_revision = "0080"
branch_labels = None
depends_on = None


_VERSIONED_EVIDENCE_GUARD = """
CREATE OR REPLACE FUNCTION dcompany_guard_client_gaming_cleanup_reconciliation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Gaming cleanup reconciliation evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;
    IF ROW(NEW.company_id, NEW.client_installation_id, NEW.branch_id,
           NEW.terminal_id, NEW.station_id, NEW.local_action_id,
           NEW.server_session_id, NEW.revision, NEW.reported_local_state,
           NEW.local_evidence_revision, NEW.reported_app_version_name,
           NEW.reported_app_version_code, NEW.local_snapshot,
           NEW.local_snapshot_sha256, NEW.start_request_hash,
           NEW.stop_request_hash, NEW.original_action_user_id, NEW.candidate_sha256,
           NEW.unresolved_child_count, NEW.cleanup_receipt_audit_id,
           NEW.reported_at, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.company_id, OLD.client_installation_id, OLD.branch_id,
           OLD.terminal_id, OLD.station_id, OLD.local_action_id,
           OLD.server_session_id, OLD.revision, OLD.reported_local_state,
           OLD.local_evidence_revision, OLD.reported_app_version_name,
           OLD.reported_app_version_code, OLD.local_snapshot,
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
"""


_LEGACY_EVIDENCE_GUARD = """
CREATE OR REPLACE FUNCTION dcompany_guard_client_gaming_cleanup_reconciliation()
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
"""


def _require_empty_legacy_ledger(direction: str) -> None:
    op.execute("LOCK TABLE client_gaming_cleanup_reconciliation IN ACCESS EXCLUSIVE MODE")
    evidence_exists = bool(
        op.get_bind()
        .execute(sa.text("SELECT EXISTS (SELECT 1 FROM client_gaming_cleanup_reconciliation)"))
        .scalar_one()
    )
    if evidence_exists:
        raise RuntimeError(
            f"0081 {direction} refused: existing cleanup evidence has no trustworthy "
            "report-time app identity"
        )


def upgrade() -> None:
    # Revision 0079 did not retain the signed request build. It cannot be
    # reconstructed from a later installation heartbeat without fabricating
    # immutable audit evidence, so an unexpected populated 0079 ledger fails
    # closed. The production 0078 -> head path creates this table empty.
    _require_empty_legacy_ledger("upgrade")
    op.add_column(
        "client_gaming_cleanup_reconciliation",
        sa.Column("reported_app_version_name", sa.String(length=80), nullable=False),
    )
    op.add_column(
        "client_gaming_cleanup_reconciliation",
        sa.Column("reported_app_version_code", sa.Integer(), nullable=False),
    )
    op.create_check_constraint(
        "ck_client_gaming_cleanup_reported_app_version",
        "client_gaming_cleanup_reconciliation",
        "reported_app_version_code BETWEEN 1 AND 2147483647 AND "
        "reported_app_version_name ~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$'",
    )
    op.execute(_VERSIONED_EVIDENCE_GUARD)


def downgrade() -> None:
    # Removing these columns would erase immutable report identity. Match 0079's
    # evidence-preserving downgrade policy and permit only an unused ledger.
    _require_empty_legacy_ledger("downgrade")
    op.execute(_LEGACY_EVIDENCE_GUARD)
    op.drop_constraint(
        "ck_client_gaming_cleanup_reported_app_version",
        "client_gaming_cleanup_reconciliation",
        type_="check",
    )
    op.drop_column("client_gaming_cleanup_reconciliation", "reported_app_version_code")
    op.drop_column("client_gaming_cleanup_reconciliation", "reported_app_version_name")
