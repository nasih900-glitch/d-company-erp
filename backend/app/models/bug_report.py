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
    String,
    event,
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


__all__ = [
    "BUG_REPORT_CATEGORIES",
    "BUG_REPORT_CONNECTIVITY_STATES",
    "BUG_REPORT_SEVERITIES",
    "BUG_REPORT_STATUSES",
    "BUG_REPORT_STATUS_TRANSITIONS",
    "BugReport",
]
