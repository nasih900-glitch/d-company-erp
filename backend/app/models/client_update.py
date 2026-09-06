"""Native client installation health and verified Android release metadata.

The installation ledger deliberately stores only operational state supplied by
the app.  It does not collect hardware identifiers, free-form logs, or
client-supplied tenant/user/terminal identity.  Update events are append-only;
the database migration installs equivalent guards below the ORM boundary.
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
    event,
    func,
    inspect,
    text,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk

CLIENT_PLATFORMS = ("android",)
CLIENT_DISTRIBUTION_CHANNELS = ("direct", "play", "managed")
CLIENT_UPDATE_STATES = (
    "idle",
    "update_available",
    "downloading",
    "verifying",
    "verified",
    "installer_opened",
    "failed",
)
CLIENT_UPDATE_EVENT_TYPES = (
    "update_offered",
    "download_started",
    "download_verified",
    "installer_opened",
    "upgrade_confirmed",
    "update_cancelled",
    "update_failed",
)
CLIENT_UPDATE_ERROR_CODES = (
    "network_error",
    "http_error",
    "insufficient_storage",
    "invalid_metadata",
    "size_mismatch",
    "checksum_mismatch",
    "archive_unreadable",
    "package_mismatch",
    "version_mismatch",
    "signer_mismatch",
    "installer_permission_denied",
    "installer_unavailable",
    "installer_not_completed",
    "unknown",
)
ANDROID_RELEASE_STATUSES = ("staged", "active", "withdrawn")

# Fixed database admission ceilings keep authenticated telemetry useful while
# preventing a stolen staff token from growing immutable evidence without
# bound.  These are deliberately generous for a one-tablet shop and are also
# enforced by migration-installed PostgreSQL triggers, not only by the API.
CLIENT_INSTALLATIONS_MAX_PER_USER = 8
CLIENT_INSTALLATIONS_MAX_PER_COMPANY = 32
CLIENT_UPDATE_EVENTS_MAX_PER_INSTALLATION = 1_000
CLIENT_UPDATE_EVENTS_MAX_PER_USER = 2_000
CLIENT_UPDATE_EVENTS_MAX_PER_COMPANY = 10_000


def _quoted(values: tuple[str, ...]) -> str:
    return ", ".join(f"'{value}'" for value in values)


class ClientInstallation(Base, TimestampMixin, TenantMixin):
    """Latest authenticated health snapshot for one random app installation UUID."""

    __tablename__ = "client_installations"
    __table_args__ = (
        UniqueConstraint(
            "company_id",
            "installation_id",
            name="uq_client_installations_company_installation",
        ),
        # Required by the composite event foreign key.  The primary key is
        # globally unique, while this tuple also proves tenant scope in SQL.
        UniqueConstraint(
            "company_id",
            "id",
            name="uq_client_installations_company_id_id",
        ),
        CheckConstraint(
            f"platform IN ({_quoted(CLIENT_PLATFORMS)})",
            name="ck_client_installations_platform",
        ),
        CheckConstraint(
            f"distribution_channel IN ({_quoted(CLIENT_DISTRIBUTION_CHANNELS)})",
            name="ck_client_installations_distribution_channel",
        ),
        CheckConstraint(
            f"update_state IN ({_quoted(CLIENT_UPDATE_STATES)})",
            name="ck_client_installations_update_state",
        ),
        CheckConstraint(
            "update_error_code IS NULL OR update_error_code IN "
            f"({_quoted(CLIENT_UPDATE_ERROR_CODES)})",
            name="ck_client_installations_error_code",
        ),
        CheckConstraint(
            "(update_state = 'failed' AND update_error_code IS NOT NULL) OR "
            "(update_state <> 'failed' AND update_error_code IS NULL)",
            name="ck_client_installations_failure_evidence",
        ),
        CheckConstraint(
            "version_code BETWEEN 1 AND 2147483647",
            name="ck_client_installations_version_code",
        ),
        CheckConstraint(
            "pending_outbox_count BETWEEN 0 AND 1000000",
            name="ck_client_installations_pending_outbox_count",
        ),
        CheckConstraint(
            "length(trim(version_name)) BETWEEN 1 AND 80",
            name="ck_client_installations_version_name",
        ),
        CheckConstraint(
            "remote_support_protocol_version IS NULL OR "
            "remote_support_protocol_version BETWEEN 1 AND 10",
            name="ck_client_installations_remote_protocol",
        ),
        CheckConstraint(
            "remote_support_capability IS NULL OR remote_support_capability IN "
            "('available', 'permission_required', 'unsupported')",
            name="ck_client_installations_remote_capability",
        ),
        CheckConstraint(
            "(remote_support_protocol_version IS NULL "
            "AND remote_support_capability IS NULL "
            "AND remote_support_last_seen_at IS NULL) OR "
            "(remote_support_protocol_version IS NOT NULL "
            "AND remote_support_capability IS NOT NULL "
            "AND remote_support_last_seen_at IS NOT NULL)",
            name="ck_client_installations_remote_heartbeat",
        ),
        Index(
            "ix_client_installations_company_last_seen",
            "company_id",
            "last_seen_at",
        ),
        Index(
            "ix_client_installations_company_version",
            "company_id",
            "platform",
            "version_code",
        ),
        Index(
            "ix_client_installations_company_terminal",
            "company_id",
            "terminal_id",
        ),
        Index(
            "ix_client_installations_company_registered_by",
            "company_id",
            "registered_by_user_id",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    installation_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    registered_by_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    last_user_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
    )
    terminal_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("terminals.id", ondelete="SET NULL"),
        nullable=True,
    )
    platform: Mapped[str] = mapped_column(String(20), nullable=False)
    distribution_channel: Mapped[str] = mapped_column(String(20), nullable=False)
    version_name: Mapped[str] = mapped_column(String(80), nullable=False)
    version_code: Mapped[int] = mapped_column(Integer, nullable=False)
    pending_outbox_count: Mapped[int] = mapped_column(Integer, nullable=False)
    last_successful_sync_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    update_state: Mapped[str] = mapped_column(String(32), nullable=False)
    update_error_code: Mapped[str | None] = mapped_column(String(64))
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    # Remote assistance is opt-in and separately heartbeated.  Null means this
    # installation has never advertised the constrained support protocol.
    remote_support_protocol_version: Mapped[int | None] = mapped_column(Integer)
    remote_support_capability: Mapped[str | None] = mapped_column(String(32))
    remote_support_last_seen_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )


class ClientUpdateEvent(Base, TenantMixin):
    """Immutable, client-idempotent update lifecycle evidence."""

    __tablename__ = "client_update_events"
    __table_args__ = (
        ForeignKeyConstraint(
            ["company_id", "client_installation_id"],
            ["client_installations.company_id", "client_installations.id"],
            name="fk_client_update_events_scoped_installation",
            ondelete="RESTRICT",
        ),
        UniqueConstraint(
            "company_id",
            "client_installation_id",
            "client_event_id",
            name="uq_client_update_events_installation_client_event",
        ),
        CheckConstraint(
            f"event_type IN ({_quoted(CLIENT_UPDATE_EVENT_TYPES)})",
            name="ck_client_update_events_type",
        ),
        CheckConstraint(
            f"error_code IS NULL OR error_code IN ({_quoted(CLIENT_UPDATE_ERROR_CODES)})",
            name="ck_client_update_events_error_code",
        ),
        CheckConstraint(
            "(event_type = 'update_failed' AND error_code IS NOT NULL) OR "
            "(event_type <> 'update_failed' AND error_code IS NULL)",
            name="ck_client_update_events_failure_evidence",
        ),
        CheckConstraint(
            "target_version_code BETWEEN 1 AND 2147483647",
            name="ck_client_update_events_target_version_code",
        ),
        CheckConstraint(
            "length(trim(target_version_name)) BETWEEN 1 AND 80",
            name="ck_client_update_events_target_version_name",
        ),
        Index(
            "ix_client_update_events_company_received",
            "company_id",
            "received_at",
        ),
        Index(
            "ix_client_update_events_installation_occurred",
            "client_installation_id",
            "occurred_at",
        ),
        Index(
            "ix_client_update_events_company_actor",
            "company_id",
            "actor_user_id",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    client_installation_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    client_event_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    actor_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    terminal_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        # Update evidence is immutable, so its contextual terminal reference
        # must be preserved as well.  RESTRICT is intentional: SET NULL would
        # require an UPDATE that the append-only trigger correctly rejects.
        ForeignKey("terminals.id", ondelete="RESTRICT"),
    )
    event_type: Mapped[str] = mapped_column(String(32), nullable=False)
    target_version_name: Mapped[str] = mapped_column(String(80), nullable=False)
    target_version_code: Mapped[int] = mapped_column(Integer, nullable=False)
    error_code: Mapped[str | None] = mapped_column(String(64))
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class AndroidRelease(Base, TimestampMixin):
    """Preverified global Android artifact registered only by an operator CLI.

    There is intentionally no API create/update/upload DTO for these metadata.
    Owners may only activate or withdraw a row that the promotion path staged.
    """

    __tablename__ = "android_releases"
    __table_args__ = (
        UniqueConstraint("channel", "version_code", name="uq_android_releases_channel_version"),
        CheckConstraint("channel = 'direct'", name="ck_android_releases_channel"),
        CheckConstraint(
            f"status IN ({_quoted(ANDROID_RELEASE_STATUSES)})",
            name="ck_android_releases_status",
        ),
        CheckConstraint(
            "version_code BETWEEN 15 AND 2147483647",
            name="ck_android_releases_version_code",
        ),
        CheckConstraint(
            "version_name ~ '^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$'",
            name="ck_android_releases_version_name",
        ),
        CheckConstraint(
            "length(release_notes) BETWEEN 1 AND 2000 AND release_notes !~ '[^ -~]'",
            name="ck_android_releases_release_notes",
        ),
        CheckConstraint(
            "apk_size_bytes BETWEEN 1 AND 536870912",
            name="ck_android_releases_apk_size",
        ),
        CheckConstraint(
            "apk_sha256 ~ '^[0-9a-f]{64}$' AND "
            "apk_signing_cert_sha256 ~ '^[0-9a-f]{64}$' AND "
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "source_git_sha ~ '^[0-9a-f]{40}$'",
            name="ck_android_releases_hashes",
        ),
        CheckConstraint(
            "length(source_release_ref) BETWEEN 2 AND 81 "
            "AND source_release_ref = 'v' || version_name "
            "AND source_release_ref !~ '[^ -~]'",
            name="ck_android_releases_source_release_ref",
        ),
        CheckConstraint(
            "source_workflow_run_id BETWEEN 1 AND 9223372036854775807",
            name="ck_android_releases_source_workflow_run_id",
        ),
        CheckConstraint(
            "source_workflow_run_attempt BETWEEN 1 AND 2147483647",
            name="ck_android_releases_source_workflow_run_attempt",
        ),
        CheckConstraint(
            "left(update_url, 8) = 'https://'",
            name="ck_android_releases_https_url",
        ),
        CheckConstraint(
            "(status = 'staged' AND activated_at IS NULL AND activated_by IS NULL "
            "AND withdrawn_at IS NULL AND withdrawn_by IS NULL) OR "
            "(status = 'active' AND activated_at IS NOT NULL AND activated_by IS NOT NULL "
            "AND withdrawn_at IS NULL AND withdrawn_by IS NULL) OR "
            "(status = 'withdrawn' AND withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL)",
            name="ck_android_releases_state_evidence",
        ),
        Index("ix_android_releases_status_registered", "status", "registered_at"),
        Index(
            "uq_android_releases_one_active_direct",
            "channel",
            unique=True,
            postgresql_where=text("status = 'active'"),
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    channel: Mapped[str] = mapped_column(
        String(20), nullable=False, default="direct", server_default="direct"
    )
    version_code: Mapped[int] = mapped_column(Integer, nullable=False)
    version_name: Mapped[str] = mapped_column(String(80), nullable=False)
    update_url: Mapped[str] = mapped_column(String(1000), nullable=False)
    release_notes: Mapped[str] = mapped_column(String(2000), nullable=False)
    apk_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    apk_size_bytes: Mapped[int] = mapped_column(BigInteger, nullable=False)
    apk_signing_cert_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    manifest_sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    source_git_sha: Mapped[str] = mapped_column(String(40), nullable=False)
    source_release_ref: Mapped[str] = mapped_column(String(81), nullable=False)
    source_workflow_run_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    source_workflow_run_attempt: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="staged", server_default="staged"
    )
    registered_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    activated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    activated_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    withdrawn_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    withdrawn_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )


@event.listens_for(ClientUpdateEvent, "before_update")
@event.listens_for(ClientUpdateEvent, "before_delete")
def _guard_immutable_client_update_event(*_args: object, **_kwargs: object) -> None:
    raise ValueError("client update events are append-only and cannot be changed or deleted")


@event.listens_for(AndroidRelease, "before_delete")
def _guard_android_release_delete(*_args: object, **_kwargs: object) -> None:
    raise ValueError("Android release evidence cannot be deleted")


_ANDROID_RELEASE_IMMUTABLE_FIELDS = frozenset(
    {
        "id",
        "channel",
        "version_code",
        "version_name",
        "update_url",
        "release_notes",
        "apk_sha256",
        "apk_size_bytes",
        "apk_signing_cert_sha256",
        "manifest_sha256",
        "source_git_sha",
        "source_release_ref",
        "source_workflow_run_id",
        "source_workflow_run_attempt",
        "registered_at",
        "created_at",
    }
)


@event.listens_for(AndroidRelease, "before_update")
def _guard_android_release_metadata(
    _mapper: object, _connection: object, target: AndroidRelease
) -> None:
    state = inspect(target)
    changed = {
        field
        for field in _ANDROID_RELEASE_IMMUTABLE_FIELDS
        if state.attrs[field].history.has_changes()
    }
    if changed:
        raise ValueError(
            "Android release artifact metadata is immutable: " + ", ".join(sorted(changed))
        )


__all__ = [
    "ANDROID_RELEASE_STATUSES",
    "CLIENT_INSTALLATIONS_MAX_PER_COMPANY",
    "CLIENT_INSTALLATIONS_MAX_PER_USER",
    "CLIENT_DISTRIBUTION_CHANNELS",
    "CLIENT_PLATFORMS",
    "CLIENT_UPDATE_ERROR_CODES",
    "CLIENT_UPDATE_EVENT_TYPES",
    "CLIENT_UPDATE_EVENTS_MAX_PER_COMPANY",
    "CLIENT_UPDATE_EVENTS_MAX_PER_INSTALLATION",
    "CLIENT_UPDATE_EVENTS_MAX_PER_USER",
    "CLIENT_UPDATE_STATES",
    "AndroidRelease",
    "ClientInstallation",
    "ClientUpdateEvent",
]
