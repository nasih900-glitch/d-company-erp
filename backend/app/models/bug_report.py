"""Durable, tenant-scoped reports submitted by ERP clients."""

from __future__ import annotations

from datetime import datetime  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime
from typing import Any
from uuid import UUID  # noqa: TC003 - SQLAlchemy resolves mapped types at runtime

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    String,
    UniqueConstraint,
    event,
    func,
    inspect,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk

BUG_REPORT_CATEGORIES = (
    "crash",
    "incorrect_data",
    "payment",
    "sync",
    "permission",
    "performance",
    "usability",
    "other",
)
BUG_REPORT_SEVERITIES = ("low", "medium", "high", "critical")
BUG_REPORT_CONNECTIVITY_STATES = ("online", "offline", "unknown")
BUG_REPORT_STATUSES = (
    "open",
    "acknowledged",
    "in_progress",
    "resolved",
    "closed",
    "rejected",
)

BUG_REPORT_STATUS_TRANSITIONS: dict[str, frozenset[str]] = {
    "open": frozenset({"acknowledged", "in_progress", "resolved", "rejected"}),
    "acknowledged": frozenset({"open", "in_progress", "resolved", "rejected"}),
    "in_progress": frozenset({"open", "acknowledged", "resolved", "rejected"}),
    "resolved": frozenset({"in_progress", "closed"}),
    "closed": frozenset({"in_progress"}),
    "rejected": frozenset({"open", "in_progress"}),
}


class BugReport(Base, TimestampMixin, TenantMixin):
    """A support report whose submission evidence is immutable after creation."""

    __tablename__ = "bug_reports"
    __table_args__ = (
        CheckConstraint(
            "category IN ('crash', 'incorrect_data', 'payment', 'sync', "
            "'permission', 'performance', 'usability', 'other')",
            name="ck_bug_reports_category",
        ),
        CheckConstraint(
            "severity IN ('low', 'medium', 'high', 'critical')",
            name="ck_bug_reports_severity",
        ),
        CheckConstraint(
            "connectivity IN ('online', 'offline', 'unknown')",
            name="ck_bug_reports_connectivity",
        ),
        CheckConstraint(
            "status IN ('open', 'acknowledged', 'in_progress', 'resolved', 'closed', 'rejected')",
            name="ck_bug_reports_status",
        ),
        CheckConstraint("length(trim(title)) >= 5", name="ck_bug_reports_title_present"),
        CheckConstraint(
            "length(trim(description)) >= 10",
            name="ck_bug_reports_description_present",
        ),
        CheckConstraint(
            "length(trim(client_platform)) > 0",
            name="ck_bug_reports_platform_present",
        ),
        CheckConstraint(
            "version_code IS NULL OR version_code >= 1",
            name="ck_bug_reports_version_code_positive",
        ),
        Index(
            "ix_bug_reports_company_status_created",
            "company_id",
            "status",
            "created_at",
        ),
        Index(
            "ix_bug_reports_company_reporter_created",
            "company_id",
            "reporter_user_id",
            "created_at",
        ),
        Index(
            "ix_bug_reports_company_severity_created",
            "company_id",
            "severity",
            "created_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    reporter_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    reporter_name: Mapped[str] = mapped_column(String(200), nullable=False)
    reporter_email: Mapped[str] = mapped_column(String(254), nullable=False)

    category: Mapped[str] = mapped_column(String(30), nullable=False)
    severity: Mapped[str] = mapped_column(String(20), nullable=False)
    title: Mapped[str] = mapped_column(String(160), nullable=False)
    description: Mapped[str] = mapped_column(String(4000), nullable=False)
    reproduction_steps: Mapped[str | None] = mapped_column(String(4000))
    expected_behavior: Mapped[str | None] = mapped_column(String(4000))
    actual_behavior: Mapped[str | None] = mapped_column(String(4000))

    client_platform: Mapped[str] = mapped_column(String(20), nullable=False)
    app_version: Mapped[str | None] = mapped_column(String(40))
    version_code: Mapped[int | None] = mapped_column(Integer)
    device_model: Mapped[str | None] = mapped_column(String(160))
    os_version: Mapped[str | None] = mapped_column(String(100))
    current_screen: Mapped[str | None] = mapped_column(String(100))
    last_action: Mapped[str | None] = mapped_column(String(120))
    error_code: Mapped[str | None] = mapped_column(String(100))
    branch_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        index=True,
    )
    # Branch names are allowed to be 200 characters by the canonical Branch
    # model. Keep the immutable snapshot equally wide so a valid branch cannot
    # make report submission fail at flush time.
    branch_name: Mapped[str | None] = mapped_column(String(200))
    terminal_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("terminals.id", ondelete="RESTRICT"),
        index=True,
    )
    terminal_name: Mapped[str | None] = mapped_column(String(160))
    connectivity: Mapped[str] = mapped_column(
        String(20), nullable=False, default="unknown", server_default="unknown"
    )
    occurred_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    status: Mapped[str] = mapped_column(
        String(20), nullable=False, default="open", server_default="open", index=True
    )
    internal_resolution_note: Mapped[str | None] = mapped_column(String(4000))
    status_changed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status_changed_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    resolved_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )


class BugReportPublicReply(Base, TenantMixin):
    """An immutable support reply that is safe for the reporter to read."""

    __tablename__ = "bug_report_public_replies"
    __table_args__ = (
        CheckConstraint(
            "length(trim(message)) >= 2",
            name="ck_bug_report_public_replies_message_present",
        ),
        Index(
            "ix_bug_report_public_replies_company_report_created",
            "company_id",
            "bug_report_id",
            "created_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    bug_report_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("bug_reports.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    author_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    author_name: Mapped[str] = mapped_column(String(200), nullable=False)
    message: Mapped[str] = mapped_column(String(4000), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class BugReportAttachment(Base, TenantMixin):
    """A private, authenticated screenshot attachment with bounded storage."""

    __tablename__ = "bug_report_attachments"
    __table_args__ = (
        CheckConstraint(
            "content_type IN ('image/png', 'image/jpeg', 'image/webp')",
            name="ck_bug_report_attachments_content_type",
        ),
        CheckConstraint(
            "byte_size BETWEEN 1 AND 2097152",
            name="ck_bug_report_attachments_byte_size",
        ),
        CheckConstraint(
            "sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_bug_report_attachments_sha256",
        ),
        CheckConstraint(
            "(payload IS NULL AND purged_at IS NOT NULL) OR ("
            "payload IS NOT NULL AND purged_at IS NULL "
            "AND octet_length(payload) = byte_size "
            "AND octet_length(payload) <= 2097152 "
            "AND sha256 = encode(digest(payload, 'sha256'), 'hex'))",
            name="ck_bug_report_attachments_payload_integrity",
        ),
        UniqueConstraint(
            "company_id",
            "bug_report_id",
            "sha256",
            name="uq_bug_report_attachments_report_sha256",
        ),
        Index(
            "ix_bug_report_attachments_company_report_created",
            "company_id",
            "bug_report_id",
            "created_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    bug_report_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("bug_reports.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    uploader_user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    original_filename: Mapped[str] = mapped_column(String(160), nullable=False)
    content_type: Mapped[str] = mapped_column(String(32), nullable=False)
    byte_size: Mapped[int] = mapped_column(Integer, nullable=False)
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    # The support workflow deliberately avoids public object URLs. Two MiB is
    # small enough to keep the initial private implementation transactional in
    # PostgreSQL; the column is deferred so ordinary inbox queries never load
    # screenshot bytes.
    payload: Mapped[bytes | None] = mapped_column(LargeBinary, nullable=True, deferred=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    purged_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class BugReportInboxRead(Base, TenantMixin):
    """Per-owner read cursor used for accurate unread Support counts."""

    __tablename__ = "bug_report_inbox_reads"
    __table_args__ = (
        UniqueConstraint(
            "bug_report_id",
            "user_id",
            name="uq_bug_report_inbox_reads_report_user",
        ),
        Index(
            "ix_bug_report_inbox_reads_company_user",
            "company_id",
            "user_id",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    bug_report_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("bug_reports.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    user_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    last_read_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


_IMMUTABLE_REPORT_FIELDS = {
    "id",
    "company_id",
    "reporter_user_id",
    "reporter_name",
    "reporter_email",
    "category",
    "severity",
    "title",
    "description",
    "reproduction_steps",
    "expected_behavior",
    "actual_behavior",
    "client_platform",
    "app_version",
    "version_code",
    "device_model",
    "os_version",
    "current_screen",
    "last_action",
    "error_code",
    "branch_id",
    "branch_name",
    "terminal_id",
    "terminal_name",
    "connectivity",
    "occurred_at",
    "created_at",
}


@event.listens_for(BugReport, "before_update")
def _guard_bug_report_update(
    _mapper: Any,
    _connection: Any,
    report: BugReport,
) -> None:
    """Reject presentation/client edits and invalid lifecycle changes in the ORM."""
    state = inspect(report)
    changed_immutable = sorted(
        field for field in _IMMUTABLE_REPORT_FIELDS if state.attrs[field].history.has_changes()
    )
    if changed_immutable:
        raise ValueError(
            "bug report submission context is immutable: " + ", ".join(changed_immutable)
        )

    status_history = state.attrs.status.history
    if status_history.has_changes() and status_history.deleted:
        old_status = str(status_history.deleted[0])
        if report.status not in BUG_REPORT_STATUS_TRANSITIONS.get(old_status, frozenset()):
            raise ValueError(
                f"invalid bug report status transition: {old_status} -> {report.status}"
            )


@event.listens_for(BugReport, "before_delete")
def _guard_bug_report_delete(
    _mapper: Any,
    _connection: Any,
    _report: BugReport,
) -> None:
    raise ValueError("bug reports are durable support evidence and cannot be deleted")


@event.listens_for(BugReportPublicReply, "before_update")
@event.listens_for(BugReportPublicReply, "before_delete")
def _guard_public_reply_mutation(
    _mapper: Any,
    _connection: Any,
    _reply: BugReportPublicReply,
) -> None:
    raise ValueError("public support replies are durable and cannot be changed or deleted")


@event.listens_for(BugReportAttachment, "before_update")
def _guard_attachment_update(
    _mapper: Any,
    _connection: Any,
    attachment: BugReportAttachment,
) -> None:
    """Only expiry cleanup may erase bytes; metadata remains durable."""
    state = inspect(attachment)
    changed = {attr.key for attr in state.attrs if attr.history.has_changes()}
    if changed - {"payload", "purged_at"}:
        raise ValueError("bug report attachment metadata is immutable")
    payload_history = state.attrs.payload.history
    if payload_history.has_changes() and attachment.payload is not None:
        raise ValueError("purged attachment bytes cannot be restored or replaced")
    if attachment.payload is None and attachment.purged_at is None:
        raise ValueError("attachment purge requires purged_at")


@event.listens_for(BugReportAttachment, "before_delete")
def _guard_attachment_delete(
    _mapper: Any,
    _connection: Any,
    _attachment: BugReportAttachment,
) -> None:
    raise ValueError("bug report attachment metadata is durable and cannot be deleted")


__all__ = [
    "BUG_REPORT_CATEGORIES",
    "BUG_REPORT_CONNECTIVITY_STATES",
    "BUG_REPORT_SEVERITIES",
    "BUG_REPORT_STATUSES",
    "BUG_REPORT_STATUS_TRANSITIONS",
    "BugReport",
    "BugReportAttachment",
    "BugReportInboxRead",
    "BugReportPublicReply",
]
