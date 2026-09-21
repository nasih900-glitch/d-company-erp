"""Durable revision ledger for exact tablet Gaming cleanup recovery."""

from __future__ import annotations

from datetime import datetime  # noqa: TC003
from uuid import UUID  # noqa: TC003

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    String,
    UniqueConstraint,
    event,
    inspect,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class ClientGamingCleanupReconciliation(Base, TimestampMixin, TenantMixin):
    """One immutable tablet snapshot in a forward-only revision chain."""

    __tablename__ = "client_gaming_cleanup_reconciliation"
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_client_gaming_cleanup_scoped_installation",
            ondelete="RESTRICT",
        ),
        UniqueConstraint(
            "company_id",
            "client_installation_id",
            "local_action_id",
            "revision",
            name="uq_client_gaming_cleanup_installation_action_revision",
        ),
        UniqueConstraint(
            "company_id",
            "client_installation_id",
            "server_session_id",
            "revision",
            name="uq_client_gaming_cleanup_installation_session_revision",
        ),
        CheckConstraint(
            "status IN ('reported', 'approved', 'applied', 'superseded')",
            name="ck_client_gaming_cleanup_status",
        ),
        CheckConstraint(
            "revision BETWEEN 1 AND 32 AND local_evidence_revision >= 0",
            name="ck_client_gaming_cleanup_revisions",
        ),
        CheckConstraint(
            "reported_local_state IN ('stop_pending', 'stop_rejected', "
            "'ended_unbilled', 'send_pending', 'send_rejected')",
            name="ck_client_gaming_cleanup_reported_state",
        ),
        CheckConstraint(
            "candidate_sha256 ~ '^[0-9a-f]{64}$' AND "
            "local_snapshot_sha256 ~ '^[0-9a-f]{64}$' AND "
            "start_request_hash ~ '^[0-9a-f]{64}$' AND "
            "stop_request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_client_gaming_cleanup_hashes",
        ),
        CheckConstraint(
            "unresolved_child_count BETWEEN 0 AND 1000000",
            name="ck_client_gaming_cleanup_child_count",
        ),
        CheckConstraint(
            "(status = 'reported' AND approved_at IS NULL AND approved_by IS NULL "
            "AND approval_reason IS NULL AND approval_idempotency_key IS NULL "
            "AND applied_at IS NULL AND applied_by IS NULL AND superseded_at IS NULL) OR "
            "(status = 'approved' AND approved_at IS NOT NULL AND approved_by IS NOT NULL "
            "AND approval_reason IS NOT NULL AND approval_idempotency_key IS NOT NULL "
            "AND applied_at IS NULL AND applied_by IS NULL AND superseded_at IS NULL) OR "
            "(status = 'applied' AND approved_at IS NOT NULL AND approved_by IS NOT NULL "
            "AND approval_reason IS NOT NULL AND approval_idempotency_key IS NOT NULL "
            "AND applied_at IS NOT NULL AND applied_by IS NOT NULL "
            "AND superseded_at IS NULL) OR "
            "(status = 'superseded' AND applied_at IS NULL AND applied_by IS NULL "
            "AND superseded_at IS NOT NULL AND ((approved_at IS NULL AND approved_by IS NULL "
            "AND approval_reason IS NULL AND approval_idempotency_key IS NULL) OR "
            "(approved_at IS NOT NULL AND approved_by IS NOT NULL "
            "AND approval_reason IS NOT NULL AND approval_idempotency_key IS NOT NULL)))",
            name="ck_client_gaming_cleanup_complete_evidence",
        ),
        Index(
            "ix_client_gaming_cleanup_company_branch_status",
            "company_id",
            "branch_id",
            "status",
            "reported_at",
        ),
        Index(
            "uq_client_gaming_cleanup_current_action",
            "company_id",
            "client_installation_id",
            "local_action_id",
            unique=True,
            postgresql_where=text("status <> 'superseded'"),
        ),
        Index(
            "uq_client_gaming_cleanup_current_session",
            "company_id",
            "client_installation_id",
            "server_session_id",
            unique=True,
            postgresql_where=text("status <> 'superseded'"),
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    client_installation_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    station_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("stations.id", ondelete="RESTRICT"), nullable=False
    )
    local_action_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    server_session_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    revision: Mapped[int] = mapped_column(Integer, nullable=False)
    reported_local_state: Mapped[str] = mapped_column(String(32), nullable=False)
    local_evidence_revision: Mapped[int] = mapped_column(BigInteger, nullable=False)
    local_snapshot: Mapped[dict[str, object]] = mapped_column(
        JSONB,
        nullable=False,
        info={"audit_redact": True},
    )
    local_snapshot_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    start_request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    stop_request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    original_action_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    candidate_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    unresolved_child_count: Mapped[int] = mapped_column(BigInteger, nullable=False)
    cleanup_receipt_audit_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("audit_log.id", ondelete="RESTRICT"), nullable=False
    )
    status: Mapped[str] = mapped_column(String(16), nullable=False, default="reported")
    reported_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    approved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    approved_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    approval_reason: Mapped[str | None] = mapped_column(String(500))
    approval_idempotency_key: Mapped[str | None] = mapped_column(String(200))
    applied_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    applied_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    superseded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


@event.listens_for(ClientGamingCleanupReconciliation, "before_update")
def _guard_cleanup_reconciliation(_mapper: object, _connection: object, target: object) -> None:
    row = target
    state = inspect(row)
    immutable = {
        "company_id",
        "client_installation_id",
        "branch_id",
        "terminal_id",
        "station_id",
        "local_action_id",
        "server_session_id",
        "revision",
        "reported_local_state",
        "local_evidence_revision",
        "local_snapshot",
        "local_snapshot_sha256",
        "start_request_hash",
        "stop_request_hash",
        "candidate_sha256",
        "original_action_user_id",
        "unresolved_child_count",
        "cleanup_receipt_audit_id",
        "reported_at",
        "created_at",
    }
    changed = sorted(field for field in immutable if state.attrs[field].history.has_changes())
    if changed:
        raise ValueError(
            "Gaming cleanup reconciliation identity is immutable: " + ", ".join(changed)
        )
    old_status = (
        state.attrs.status.history.deleted[0] if state.attrs.status.history.deleted else row.status
    )
    if old_status != row.status and (old_status, row.status) not in {
        ("reported", "approved"),
        ("approved", "applied"),
        ("reported", "superseded"),
        ("approved", "superseded"),
    }:
        raise ValueError(f"invalid Gaming cleanup transition: {old_status} -> {row.status}")
    evidence = {
        "approved_at",
        "approved_by",
        "approval_reason",
        "approval_idempotency_key",
        "applied_at",
        "applied_by",
        "superseded_at",
    }
    changed_evidence = sorted(
        field for field in evidence if state.attrs[field].history.has_changes()
    )
    if old_status == row.status and changed_evidence:
        raise ValueError(
            "Gaming cleanup transition evidence is immutable: " + ", ".join(changed_evidence)
        )
    approval = {"approved_at", "approved_by", "approval_reason", "approval_idempotency_key"}
    changed_approval = sorted(
        field for field in approval if state.attrs[field].history.has_changes()
    )
    approval_must_be_preserved = (old_status == "approved" and row.status == "applied") or (
        old_status in {"reported", "approved"} and row.status == "superseded"
    )
    if approval_must_be_preserved and changed_approval:
        raise ValueError(
            "Gaming cleanup approval evidence is immutable: " + ", ".join(changed_approval)
        )


@event.listens_for(ClientGamingCleanupReconciliation, "before_delete")
def _prevent_cleanup_reconciliation_delete(*_args: object, **_kwargs: object) -> None:
    raise ValueError("Gaming cleanup reconciliation evidence cannot be deleted")
