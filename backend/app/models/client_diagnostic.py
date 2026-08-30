"""Bounded, privacy-preserving native client failure evidence.

The diagnostic ledger intentionally stores categorical operational facts only.
It must never contain stack traces, exception messages, request URLs/bodies,
headers, hardware identifiers, or customer/payment identifiers.  Client UUIDs
provide replay idempotency; authenticated request context provides tenant,
actor, and optional terminal attribution.
"""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime
from uuid import UUID  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    event,
    func,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, _uuid_pk

CLIENT_DIAGNOSTIC_EVENT_TYPES = ("crash", "anr", "api_failure", "sync_stall")
CLIENT_DIAGNOSTIC_SEVERITIES = ("warning", "error", "critical")
CLIENT_DIAGNOSTIC_COMPONENTS = (
    "app",
    "auth",
    "gaming",
    "pos",
    "finance",
    "sync",
    "network",
    "updates",
    "storage",
)
CLIENT_DIAGNOSTIC_CONNECTIVITY = ("online", "offline", "unknown")
CLIENT_DIAGNOSTIC_DURATION_BUCKETS = (
    "under_5s",
    "5_to_30s",
    "30s_to_2m",
    "2_to_10m",
    "over_10m",
)

# The API purges expired rows before admitting a batch.  The database trigger
# applies the same admission ceilings to scripts and future writers.
CLIENT_DIAGNOSTIC_RETENTION_DAYS = 90
CLIENT_DIAGNOSTICS_MAX_PER_INSTALLATION = 10_000
CLIENT_DIAGNOSTICS_MAX_PER_USER = 20_000
CLIENT_DIAGNOSTICS_MAX_PER_COMPANY = 50_000


def _quoted(values: tuple[str, ...]) -> str:
    return ", ".join(f"'{value}'" for value in values)


class ClientDiagnosticEvent(Base, TenantMixin):
    """One immutable, sanitized failure signal retained for at most 90 days."""

    __tablename__ = "client_diagnostic_events"
    __table_args__ = (
        CheckConstraint(
            f"event_type IN ({_quoted(CLIENT_DIAGNOSTIC_EVENT_TYPES)})",
            name="ck_client_diagnostic_events_type",
        ),
        CheckConstraint(
            f"severity IN ({_quoted(CLIENT_DIAGNOSTIC_SEVERITIES)})",
            name="ck_client_diagnostic_events_severity",
        ),
        CheckConstraint(
            f"component IN ({_quoted(CLIENT_DIAGNOSTIC_COMPONENTS)})",
            name="ck_client_diagnostic_events_component",
        ),
        CheckConstraint(
            f"connectivity IN ({_quoted(CLIENT_DIAGNOSTIC_CONNECTIVITY)})",
            name="ck_client_diagnostic_events_connectivity",
        ),
        CheckConstraint(
            "duration_bucket IS NULL OR duration_bucket IN "
            f"({_quoted(CLIENT_DIAGNOSTIC_DURATION_BUCKETS)})",
            name="ck_client_diagnostic_events_duration_bucket",
        ),
        CheckConstraint(
            "version_code BETWEEN 1 AND 2147483647",
            name="ck_client_diagnostic_events_version_code",
        ),
        CheckConstraint(
            "version_name ~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$'",
            name="ck_client_diagnostic_events_version_name",
        ),
        CheckConstraint(
            "os_api_level IS NULL OR os_api_level BETWEEN 21 AND 100",
            name="ck_client_diagnostic_events_os_api_level",
        ),
        CheckConstraint(
            "reason_code ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'",
            name="ck_client_diagnostic_events_reason_code",
        ),
        CheckConstraint(
            "failure_fingerprint IS NULL OR failure_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_client_diagnostic_events_fingerprint",
        ),
        CheckConstraint(
            "http_status IS NULL OR http_status BETWEEN 100 AND 599",
            name="ck_client_diagnostic_events_http_status",
        ),
        CheckConstraint(
            "pending_outbox_count IS NULL OR "
            "pending_outbox_count BETWEEN 0 AND 1000000",
            name="ck_client_diagnostic_events_pending_outbox_count",
        ),
        CheckConstraint(
            "event_type = 'api_failure' OR http_status IS NULL",
            name="ck_client_diagnostic_events_http_status_scope",
        ),
        UniqueConstraint(
            "company_id",
            "installation_id",
            "client_event_id",
            name="uq_client_diagnostic_events_company_installation_event",
        ),
        Index(
            "ix_client_diagnostic_events_company_received",
            "company_id",
            "received_at",
        ),
        Index(
            "ix_client_diagnostic_events_company_type_occurred",
            "company_id",
            "event_type",
            "occurred_at",
        ),
        Index(
            "ix_client_diagnostic_events_installation_occurred",
            "installation_id",
            "occurred_at",
        ),
        Index(
            "ix_client_diagnostic_events_company_severity",
            "company_id",
            "severity",
            "occurred_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    installation_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    client_event_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    actor_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    terminal_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("terminals.id", ondelete="RESTRICT"),
    )
    event_type: Mapped[str] = mapped_column(String(32), nullable=False)
    severity: Mapped[str] = mapped_column(String(16), nullable=False)
    component: Mapped[str] = mapped_column(String(24), nullable=False)
    reason_code: Mapped[str] = mapped_column(String(64), nullable=False)
    failure_fingerprint: Mapped[str | None] = mapped_column(String(64))
    version_name: Mapped[str] = mapped_column(String(80), nullable=False)
    version_code: Mapped[int] = mapped_column(Integer, nullable=False)
    os_api_level: Mapped[int | None] = mapped_column(Integer)
    http_status: Mapped[int | None] = mapped_column(Integer)
    duration_bucket: Mapped[str | None] = mapped_column(String(20))
    connectivity: Mapped[str] = mapped_column(String(16), nullable=False)
    pending_outbox_count: Mapped[int | None] = mapped_column(Integer)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


@event.listens_for(ClientDiagnosticEvent, "before_update")
def _guard_immutable_client_diagnostic(*_args: object, **_kwargs: object) -> None:
    raise ValueError("client diagnostic events are immutable")


__all__ = [
    "CLIENT_DIAGNOSTIC_COMPONENTS",
    "CLIENT_DIAGNOSTIC_CONNECTIVITY",
    "CLIENT_DIAGNOSTIC_DURATION_BUCKETS",
    "CLIENT_DIAGNOSTIC_EVENT_TYPES",
    "CLIENT_DIAGNOSTIC_RETENTION_DAYS",
    "CLIENT_DIAGNOSTIC_SEVERITIES",
    "CLIENT_DIAGNOSTICS_MAX_PER_COMPANY",
    "CLIENT_DIAGNOSTICS_MAX_PER_INSTALLATION",
    "CLIENT_DIAGNOSTICS_MAX_PER_USER",
    "ClientDiagnosticEvent",
]
