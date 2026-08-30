"""Consent-gated, semantic remote-assistance control plane.

Image bytes deliberately do not belong in these models.  The only frame is a
short-lived, latest-value Redis relay managed by ``services.remote_assistance``.
"""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime
from uuid import UUID  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime

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
    text,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk

REMOTE_ASSISTANCE_CAPABILITIES = ("available", "permission_required", "unsupported")
REMOTE_ASSISTANCE_GRANT_KINDS = ("one_time", "anytime")
REMOTE_ASSISTANCE_GRANT_STATUSES = (
    "requested",
    "active",
    "declined",
    "revoked",
    "expired",
    "consumed",
)
REMOTE_ASSISTANCE_SESSION_STATUSES = ("requested", "active", "ended", "expired")
REMOTE_ASSISTANCE_COMMAND_TYPES = (
    "navigate",
    "refresh",
    "sync_now",
    "collect_diagnostics",
)
REMOTE_ASSISTANCE_NAVIGATION_MODULES = ("dashboard", "gaming", "pos", "shift", "help")
REMOTE_ASSISTANCE_COMMAND_STATUSES = ("pending", "acknowledged", "rejected")
REMOTE_ASSISTANCE_END_REASONS = (
    "owner_ended",
    "user_ended",
    "permission_revoked",
    "capture_stopped",
    "app_backgrounded",
    "grant_revoked",
    "grant_declined",
)
REMOTE_ASSISTANCE_COMMAND_REJECTION_REASONS = (
    "unsupported_command",
    "module_unavailable",
    "permission_denied",
    "not_in_foreground",
    "session_inactive",
    "execution_failed",
    "session_ended",
)


def _quoted(values: tuple[str, ...]) -> str:
    return ", ".join(f"'{value}'" for value in values)


class RemoteAssistanceGrant(Base, TimestampMixin, TenantMixin):
    """A device user's explicit consent decision.

    ``one_time`` is consumed atomically when its first session starts.
    ``anytime`` may authorize multiple sequential short sessions until it is
    revoked or expires.  It never permits overlapping sessions.
    """

    __tablename__ = "remote_assistance_grants"
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_remote_assistance_grants_scoped_installation",
            ondelete="RESTRICT",
        ),
        UniqueConstraint("company_id", "id", name="uq_remote_assistance_grants_company_id_id"),
        UniqueConstraint(
            "company_id",
            "decision_id",
            name="uq_remote_assistance_grants_company_decision_id",
        ),
        UniqueConstraint(
            "company_id",
            "revocation_id",
            name="uq_remote_assistance_grants_company_revocation_id",
        ),
        CheckConstraint(
            f"kind IN ({_quoted(REMOTE_ASSISTANCE_GRANT_KINDS)})",
            name="ck_remote_assistance_grants_kind",
        ),
        CheckConstraint(
            f"status IN ({_quoted(REMOTE_ASSISTANCE_GRANT_STATUSES)})",
            name="ck_remote_assistance_grants_status",
        ),
        CheckConstraint(
            "expires_at > requested_at",
            name="ck_remote_assistance_grants_expiry",
        ),
        CheckConstraint(
            "(status = 'requested' AND responded_at IS NULL "
            "AND responded_by_user_id IS NULL AND decision_id IS NULL) OR "
            "(status IN ('active', 'declined', 'consumed') "
            "AND responded_at IS NOT NULL AND responded_by_user_id IS NOT NULL "
            "AND decision_id IS NOT NULL) OR "
            "(status IN ('revoked', 'expired') AND ((responded_at IS NULL "
            "AND responded_by_user_id IS NULL AND decision_id IS NULL) OR "
            "(responded_at IS NOT NULL AND responded_by_user_id IS NOT NULL "
            "AND decision_id IS NOT NULL)))",
            name="ck_remote_assistance_grants_response_evidence",
        ),
        CheckConstraint(
            "(status <> 'revoked' AND revoked_at IS NULL "
            "AND revoked_by_user_id IS NULL AND revocation_id IS NULL) OR "
            "(status = 'revoked' AND revoked_at IS NOT NULL "
            "AND revoked_by_user_id IS NOT NULL AND revocation_id IS NOT NULL)",
            name="ck_remote_assistance_grants_revocation_evidence",
        ),
        CheckConstraint(
            "(status <> 'consumed' AND consumed_at IS NULL) OR "
            "(status = 'consumed' AND kind = 'one_time' AND consumed_at IS NOT NULL) OR "
            "(status = 'revoked' AND kind = 'one_time' AND consumed_at IS NOT NULL)",
            name="ck_remote_assistance_grants_consumption",
        ),
        Index(
            "ix_remote_assistance_grants_company_device_requested",
            "company_id",
            "client_installation_id",
            "requested_at",
        ),
        Index(
            "uq_remote_assistance_grants_device_open",
            "company_id",
            "client_installation_id",
            unique=True,
            postgresql_where=text("status IN ('requested', 'active')"),
            sqlite_where=text("status IN ('requested', 'active')"),
        ),
        Index("ix_remote_assistance_grants_expires_at", "expires_at"),
    )

    id: Mapped[UUID] = _uuid_pk()
    client_installation_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    requested_by_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    responded_by_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    revoked_by_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    responded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    decision_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revocation_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class RemoteAssistanceSession(Base, TimestampMixin, TenantMixin):
    """One bounded support session authorized by one consent grant."""

    __tablename__ = "remote_assistance_sessions"
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "grant_id"],
            ["remote_assistance_grants.company_id", "remote_assistance_grants.id"],
            name="fk_remote_assistance_sessions_scoped_grant",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_remote_assistance_sessions_scoped_installation",
            ondelete="RESTRICT",
        ),
        UniqueConstraint("company_id", "id", name="uq_remote_assistance_sessions_company_id_id"),
        UniqueConstraint(
            "company_id",
            "start_id",
            name="uq_remote_assistance_sessions_company_start_id",
        ),
        UniqueConstraint(
            "company_id",
            "end_id",
            name="uq_remote_assistance_sessions_company_end_id",
        ),
        CheckConstraint(
            f"status IN ({_quoted(REMOTE_ASSISTANCE_SESSION_STATUSES)})",
            name="ck_remote_assistance_sessions_status",
        ),
        CheckConstraint(
            "duration_seconds BETWEEN 60 AND 900",
            name="ck_remote_assistance_sessions_duration",
        ),
        CheckConstraint(
            "request_expires_at > requested_at",
            name="ck_remote_assistance_sessions_request_expiry",
        ),
        CheckConstraint(
            "(status = 'requested' AND started_at IS NULL AND expires_at IS NULL "
            "AND started_by_user_id IS NULL AND start_id IS NULL) OR "
            "(status = 'active' AND started_at IS NOT NULL AND expires_at IS NOT NULL "
            "AND started_by_user_id IS NOT NULL AND start_id IS NOT NULL "
            "AND expires_at > started_at) OR "
            "(status IN ('ended', 'expired') AND ((started_at IS NULL "
            "AND expires_at IS NULL AND started_by_user_id IS NULL AND start_id IS NULL) OR "
            "(started_at IS NOT NULL AND expires_at IS NOT NULL "
            "AND started_by_user_id IS NOT NULL AND start_id IS NOT NULL "
            "AND expires_at > started_at)))",
            name="ck_remote_assistance_sessions_start_evidence",
        ),
        CheckConstraint(
            "(status <> 'ended' AND ended_at IS NULL AND ended_by_user_id IS NULL "
            "AND end_id IS NULL AND end_reason IS NULL) OR "
            "(status = 'ended' AND ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL "
            "AND end_id IS NOT NULL AND end_reason IS NOT NULL)",
            name="ck_remote_assistance_sessions_end_evidence",
        ),
        CheckConstraint(
            f"end_reason IS NULL OR end_reason IN ({_quoted(REMOTE_ASSISTANCE_END_REASONS)})",
            name="ck_remote_assistance_sessions_end_reason",
        ),
        Index(
            "ix_remote_assistance_sessions_company_device_requested",
            "company_id",
            "client_installation_id",
            "requested_at",
        ),
        Index(
            "uq_remote_assistance_sessions_device_open",
            "company_id",
            "client_installation_id",
            unique=True,
            postgresql_where=text("status IN ('requested', 'active')"),
            sqlite_where=text("status IN ('requested', 'active')"),
        ),
        Index("ix_remote_assistance_sessions_expires_at", "expires_at"),
    )

    id: Mapped[UUID] = _uuid_pk()
    grant_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    client_installation_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    requested_by_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    started_by_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    ended_by_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    duration_seconds: Mapped[int] = mapped_column(Integer, nullable=False)
    requested_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    request_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    start_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    end_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    end_reason: Mapped[str | None] = mapped_column(String(32))


class RemoteAssistanceCommand(Base, TenantMixin):
    """A closed-set semantic instruction; arbitrary input is intentionally absent."""

    __tablename__ = "remote_assistance_commands"
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "session_id"],
            ["remote_assistance_sessions.company_id", "remote_assistance_sessions.id"],
            name="fk_remote_assistance_commands_scoped_session",
            ondelete="RESTRICT",
        ),
        UniqueConstraint(
            "company_id",
            "session_id",
            "sequence",
            name="uq_remote_assistance_commands_session_sequence",
        ),
        CheckConstraint(
            f"command_type IN ({_quoted(REMOTE_ASSISTANCE_COMMAND_TYPES)})",
            name="ck_remote_assistance_commands_type",
        ),
        CheckConstraint(
            f"module IS NULL OR module IN ({_quoted(REMOTE_ASSISTANCE_NAVIGATION_MODULES)})",
            name="ck_remote_assistance_commands_module",
        ),
        CheckConstraint(
            "(command_type = 'navigate' AND module IS NOT NULL) OR "
            "(command_type <> 'navigate' AND module IS NULL)",
            name="ck_remote_assistance_commands_payload",
        ),
        CheckConstraint(
            f"status IN ({_quoted(REMOTE_ASSISTANCE_COMMAND_STATUSES)})",
            name="ck_remote_assistance_commands_status",
        ),
        CheckConstraint(
            "sequence BETWEEN 1 AND 100",
            name="ck_remote_assistance_commands_sequence",
        ),
        CheckConstraint(
            "(status = 'pending' AND resolved_at IS NULL AND resolved_by_user_id IS NULL "
            "AND rejection_reason_code IS NULL) OR "
            "(status = 'acknowledged' AND resolved_at IS NOT NULL "
            "AND resolved_by_user_id IS NOT NULL AND rejection_reason_code IS NULL) OR "
            "(status = 'rejected' AND resolved_at IS NOT NULL "
            "AND rejection_reason_code IS NOT NULL "
            "AND (resolved_by_user_id IS NOT NULL "
            "OR rejection_reason_code = 'session_ended'))",
            name="ck_remote_assistance_commands_resolution",
        ),
        CheckConstraint(
            "rejection_reason_code IS NULL OR rejection_reason_code IN "
            f"({_quoted(REMOTE_ASSISTANCE_COMMAND_REJECTION_REASONS)})",
            name="ck_remote_assistance_commands_rejection_reason",
        ),
        Index(
            "ix_remote_assistance_commands_session_status_sequence",
            "session_id",
            "status",
            "sequence",
        ),
        Index(
            "ix_remote_assistance_commands_company_issued",
            "company_id",
            "issued_at",
        ),
    )

    # The client-supplied UUIDv4 is both primary identity and idempotency key.
    id: Mapped[UUID] = _uuid_pk()
    session_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    sequence: Mapped[int] = mapped_column(BigInteger, nullable=False)
    issued_by_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    resolved_by_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    command_type: Mapped[str] = mapped_column(String(32), nullable=False)
    module: Mapped[str | None] = mapped_column(String(32))
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    issued_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    rejection_reason_code: Mapped[str | None] = mapped_column(String(32))


__all__ = [
    "REMOTE_ASSISTANCE_CAPABILITIES",
    "REMOTE_ASSISTANCE_COMMAND_REJECTION_REASONS",
    "REMOTE_ASSISTANCE_COMMAND_STATUSES",
    "REMOTE_ASSISTANCE_COMMAND_TYPES",
    "REMOTE_ASSISTANCE_END_REASONS",
    "REMOTE_ASSISTANCE_GRANT_KINDS",
    "REMOTE_ASSISTANCE_GRANT_STATUSES",
    "REMOTE_ASSISTANCE_NAVIGATION_MODULES",
    "REMOTE_ASSISTANCE_SESSION_STATUSES",
    "RemoteAssistanceCommand",
    "RemoteAssistanceGrant",
    "RemoteAssistanceSession",
]
