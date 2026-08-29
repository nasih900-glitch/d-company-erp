"""Tenant-scoped bug-report submission and protected support inbox APIs."""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import TYPE_CHECKING, Annotated, Literal, cast
from uuid import UUID, uuid4

from anyio import CapacityLimiter, to_thread
from fastapi import APIRouter, Depends, File, Query, Request, UploadFile, status
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import and_, func, or_, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import undefer

from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves dependency aliases
from app.core.errors import BusinessRuleError, NotFoundError, RateLimitError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.tenant import (  # noqa: TC001 - FastAPI resolves dependency aliases
    TenantContext,
    TenantDep,
)
from app.models import (
    AuditLog,
    Branch,
    BugReport,
    BugReportAttachment,
    BugReportInboxRead,
    BugReportPublicReply,
    Company,
    Terminal,
    User,
)
from app.models.bug_report import BUG_REPORT_STATUS_TRANSITIONS
from app.services.bug_reports.attachments import purge_expired_bug_report_attachments
from app.services.bug_reports.images import (
    MAX_BUG_REPORT_IMAGE_BYTES,
    BugReportImageError,
    sanitize_bug_report_image,
)
from app.services.bug_reports.sanitization import sanitize_bug_report_text

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession
    from sqlalchemy.sql.elements import ColumnElement

router = APIRouter()

BugCategory = Literal[
    "crash",
    "incorrect_data",
    "payment",
    "sync",
    "permission",
    "performance",
    "usability",
    "other",
]
BugSeverity = Literal["low", "medium", "high", "critical"]
BugConnectivity = Literal["online", "offline", "unknown"]
BugStatus = Literal[
    "open",
    "acknowledged",
    "in_progress",
    "resolved",
    "closed",
    "rejected",
]
AdminTenantDep = Annotated[TenantContext, Depends(requires("admin.system"))]
ReportStatusFilter = Annotated[BugStatus | None, Query(alias="status")]

_STATUS_VALUES = ("open", "acknowledged", "in_progress", "resolved", "closed", "rejected")
_SEVERITY_VALUES = ("low", "medium", "high", "critical")
_MAX_REPORTS_PER_HOUR = 10
_MAX_ATTACHMENTS_PER_REPORT = 3
_MAX_ATTACHMENT_BYTES = MAX_BUG_REPORT_IMAGE_BYTES
_MAX_COMPANY_ATTACHMENT_BYTES = 100 * 1024 * 1024
_ATTACHMENT_RETENTION_DAYS = 90
_ACTIVE_STATUSES = ("open", "acknowledged", "in_progress")
_IMAGE_DECODER_LIMITER = CapacityLimiter(2)


def _clean_required(value: object) -> object:
    if not isinstance(value, str):
        return value
    return sanitize_bug_report_text(value)


def _clean_optional(value: object) -> object:
    if not isinstance(value, str):
        return value
    cleaned = sanitize_bug_report_text(value)
    return cleaned or None


class BugReportClientContextCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    platform: str = Field(min_length=1, max_length=20, pattern=r"^[a-z0-9_-]+$")
    app_version: str | None = Field(default=None, max_length=40)
    version_code: int | None = Field(default=None, ge=1, le=2_147_483_647)
    device_model: str | None = Field(default=None, max_length=160)
    os_version: str | None = Field(default=None, max_length=100)
    current_screen: str | None = Field(default=None, max_length=100)
    last_action: str | None = Field(default=None, max_length=120)
    error_code: str | None = Field(default=None, max_length=100)
    branch_id: UUID | None = None
    branch_name: str | None = Field(default=None, max_length=200)
    terminal_id: UUID | None = None
    terminal_name: str | None = Field(default=None, max_length=160)
    connectivity: BugConnectivity = "unknown"
    occurred_at: datetime | None = None

    @field_validator(
        "app_version",
        "device_model",
        "os_version",
        "current_screen",
        "last_action",
        "error_code",
        "branch_name",
        "terminal_name",
        mode="before",
    )
    @classmethod
    def sanitize_optional_context(cls, value: object) -> object:
        return _clean_optional(value)

    @field_validator("platform", mode="before")
    @classmethod
    def normalize_platform(cls, value: object) -> object:
        cleaned = _clean_required(value)
        return cleaned.lower() if isinstance(cleaned, str) else cleaned

    @field_validator("occurred_at")
    @classmethod
    def require_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.tzinfo is None:
            raise ValueError("occurred_at must include a timezone")
        return value.astimezone(UTC) if value is not None else None


class BugReportCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: BugCategory
    severity: BugSeverity
    title: str = Field(min_length=5, max_length=160)
    description: str = Field(min_length=10, max_length=4000)
    reproduction_steps: str | None = Field(default=None, max_length=4000)
    expected_behavior: str | None = Field(default=None, max_length=4000)
    actual_behavior: str | None = Field(default=None, max_length=4000)
    client_context: BugReportClientContextCreate

    @field_validator("title", "description", mode="before")
    @classmethod
    def sanitize_required_text(cls, value: object) -> object:
        return _clean_required(value)

    @field_validator("reproduction_steps", "expected_behavior", "actual_behavior", mode="before")
    @classmethod
    def sanitize_optional_text(cls, value: object) -> object:
        return _clean_optional(value)


class BugReportUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: BugStatus | None = None
    internal_resolution_note: str | None = Field(default=None, max_length=4000)

    @field_validator("internal_resolution_note", mode="before")
    @classmethod
    def sanitize_resolution_note(cls, value: object) -> object:
        return _clean_optional(value)

    @model_validator(mode="after")
    def require_an_explicit_field(self) -> BugReportUpdate:
        if not self.model_fields_set:
            raise ValueError("provide status or internal_resolution_note")
        return self


class BugReportReporterRead(BaseModel):
    user_id: UUID
    name: str
    email: str


class BugReportClientContextRead(BaseModel):
    platform: str
    app_version: str | None
    version_code: int | None
    device_model: str | None
    os_version: str | None
    current_screen: str | None
    last_action: str | None
    error_code: str | None
    branch_id: UUID | None
    branch_name: str | None
    terminal_id: UUID | None
    terminal_name: str | None
    connectivity: BugConnectivity
    occurred_at: datetime | None


class BugReportPublicReplyCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    message: str = Field(min_length=2, max_length=4000)

    @field_validator("message", mode="before")
    @classmethod
    def sanitize_message(cls, value: object) -> object:
        return _clean_required(value)


class BugReportPublicReplyRead(BaseModel):
    id: UUID
    author_name: str
    message: str
    created_at: datetime


class BugReportAttachmentRead(BaseModel):
    id: UUID
    filename: str
    content_type: str
    byte_size: int
    sha256: str
    created_at: datetime
    expires_at: datetime
    available: bool


class BugReportRead(BaseModel):
    id: UUID
    category: BugCategory
    severity: BugSeverity
    title: str
    description: str
    reproduction_steps: str | None
    expected_behavior: str | None
    actual_behavior: str | None
    client_context: BugReportClientContextRead
    status: BugStatus
    internal_resolution_note: str | None
    public_replies: list[BugReportPublicReplyRead] = Field(default_factory=list)
    attachments: list[BugReportAttachmentRead] = Field(default_factory=list)
    reporter: BugReportReporterRead
    status_changed_at: datetime | None
    status_changed_by: UUID | None
    resolved_at: datetime | None
    resolved_by: UUID | None
    created_at: datetime
    updated_at: datetime


class BugReportMineRead(BaseModel):
    """Reporter-safe view: no private notes, staff email, or owner-only metadata."""

    id: UUID
    category: BugCategory
    severity: BugSeverity
    title: str
    description: str
    reproduction_steps: str | None
    expected_behavior: str | None
    actual_behavior: str | None
    client_context: BugReportClientContextRead
    status: BugStatus
    public_replies: list[BugReportPublicReplyRead] = Field(default_factory=list)
    attachments: list[BugReportAttachmentRead] = Field(default_factory=list)
    status_changed_at: datetime | None
    resolved_at: datetime | None
    created_at: datetime
    updated_at: datetime


class BugReportSummary(BaseModel):
    counts_by_status: dict[str, int]
    counts_by_severity: dict[str, int]


class BugReportPage(BaseModel):
    items: list[BugReportRead]
    total: int
    limit: int
    offset: int
    summary: BugReportSummary


class BugReportMinePage(BaseModel):
    items: list[BugReportMineRead]
    total: int
    limit: int
    offset: int


class BugReportInboxSummary(BaseModel):
    active: int
    unread: int
    urgent_unread: int
    critical_active: int
    last_activity_at: datetime | None


def _reply_to_read(reply: BugReportPublicReply) -> BugReportPublicReplyRead:
    return BugReportPublicReplyRead(
        id=reply.id,
        author_name=reply.author_name,
        message=reply.message,
        created_at=reply.created_at,
    )


def _attachment_to_read(
    attachment: BugReportAttachment,
    *,
    now: datetime | None = None,
) -> BugReportAttachmentRead:
    effective_now = now or datetime.now(UTC)
    return BugReportAttachmentRead(
        id=attachment.id,
        filename=attachment.original_filename,
        content_type=attachment.content_type,
        byte_size=attachment.byte_size,
        sha256=attachment.sha256,
        created_at=attachment.created_at,
        expires_at=attachment.expires_at,
        available=attachment.purged_at is None and attachment.expires_at > effective_now,
    )


def _client_context_to_read(report: BugReport) -> BugReportClientContextRead:
    return BugReportClientContextRead(
        platform=report.client_platform,
        app_version=report.app_version,
        version_code=report.version_code,
        device_model=report.device_model,
        os_version=report.os_version,
        current_screen=report.current_screen,
        last_action=report.last_action,
        error_code=report.error_code,
        branch_id=report.branch_id,
        branch_name=report.branch_name,
        terminal_id=report.terminal_id,
        terminal_name=report.terminal_name,
        connectivity=cast("BugConnectivity", report.connectivity),
        occurred_at=report.occurred_at,
    )


def _to_read(
    report: BugReport,
    *,
    replies: list[BugReportPublicReplyRead] | None = None,
    attachments: list[BugReportAttachmentRead] | None = None,
) -> BugReportRead:
    return BugReportRead(
        id=report.id,
        category=cast("BugCategory", report.category),
        severity=cast("BugSeverity", report.severity),
        title=report.title,
        description=report.description,
        reproduction_steps=report.reproduction_steps,
        expected_behavior=report.expected_behavior,
        actual_behavior=report.actual_behavior,
        client_context=_client_context_to_read(report),
        status=cast("BugStatus", report.status),
        internal_resolution_note=report.internal_resolution_note,
        public_replies=replies or [],
        attachments=attachments or [],
        reporter=BugReportReporterRead(
            user_id=report.reporter_user_id,
            name=report.reporter_name,
            email=report.reporter_email,
        ),
        status_changed_at=report.status_changed_at,
        status_changed_by=report.status_changed_by,
        resolved_at=report.resolved_at,
        resolved_by=report.resolved_by,
        created_at=report.created_at,
        updated_at=report.updated_at,
    )


def _to_mine_read(
    report: BugReport,
    *,
    replies: list[BugReportPublicReplyRead] | None = None,
    attachments: list[BugReportAttachmentRead] | None = None,
) -> BugReportMineRead:
    return BugReportMineRead(
        id=report.id,
        category=cast("BugCategory", report.category),
        severity=cast("BugSeverity", report.severity),
        title=report.title,
        description=report.description,
        reproduction_steps=report.reproduction_steps,
        expected_behavior=report.expected_behavior,
        actual_behavior=report.actual_behavior,
        client_context=_client_context_to_read(report),
        status=cast("BugStatus", report.status),
        public_replies=replies or [],
        attachments=attachments or [],
        status_changed_at=report.status_changed_at,
        resolved_at=report.resolved_at,
        created_at=report.created_at,
        updated_at=report.updated_at,
    )


async def _support_children(
    session: AsyncSession,
    *,
    company_id: UUID,
    report_ids: list[UUID],
) -> tuple[
    dict[UUID, list[BugReportPublicReplyRead]],
    dict[UUID, list[BugReportAttachmentRead]],
]:
    replies_by_report: dict[UUID, list[BugReportPublicReplyRead]] = {}
    attachments_by_report: dict[UUID, list[BugReportAttachmentRead]] = {}
    if not report_ids:
        return replies_by_report, attachments_by_report
    reply_rows = (
        (
            await session.execute(
                select(BugReportPublicReply)
                .where(
                    BugReportPublicReply.company_id == company_id,
                    BugReportPublicReply.bug_report_id.in_(report_ids),
                )
                .order_by(BugReportPublicReply.created_at, BugReportPublicReply.id)
            )
        )
        .scalars()
        .all()
    )
    for reply in reply_rows:
        replies_by_report.setdefault(reply.bug_report_id, []).append(_reply_to_read(reply))

    attachment_rows = (
        (
            await session.execute(
                select(BugReportAttachment)
                .where(
                    BugReportAttachment.company_id == company_id,
                    BugReportAttachment.bug_report_id.in_(report_ids),
                )
                .order_by(BugReportAttachment.created_at, BugReportAttachment.id)
            )
        )
        .scalars()
        .all()
    )
    now = datetime.now(UTC)
    for attachment in attachment_rows:
        attachments_by_report.setdefault(attachment.bug_report_id, []).append(
            _attachment_to_read(attachment, now=now)
        )
    return replies_by_report, attachments_by_report


def _require_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for bug report submission")
    return str(key), str(request_hash)


async def _resolve_context(
    session: AsyncSession,
    *,
    tenant: TenantContext,
    context: BugReportClientContextCreate,
) -> tuple[UUID | None, str | None, UUID | None, str | None]:
    # A client may supply context hints, but it may not attribute a report to
    # a different branch or terminal than the authenticated request is bound
    # to. Besides preserving evidence integrity, returning the same 404 used
    # for an unknown identifier avoids disclosing another branch's metadata.
    if (
        context.branch_id is not None
        and tenant.branch_id is not None
        and context.branch_id != tenant.branch_id
    ):
        raise NotFoundError("branch not found")
    if (
        context.terminal_id is not None
        and tenant.terminal_id is not None
        and context.terminal_id != tenant.terminal_id
    ):
        raise NotFoundError("terminal not found")

    branch_id = context.branch_id or tenant.branch_id
    terminal_id = context.terminal_id or tenant.terminal_id
    branch: Branch | None = None
    terminal: Terminal | None = None

    if branch_id is not None:
        branch = (
            await session.execute(
                select(Branch).where(
                    Branch.id == branch_id,
                    Branch.company_id == tenant.company_id,
                    Branch.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if branch is None:
            raise NotFoundError("branch not found")

    if terminal_id is not None:
        terminal_row = (
            await session.execute(
                select(Terminal, Branch)
                .join(Branch, Branch.id == Terminal.branch_id)
                .where(
                    Terminal.id == terminal_id,
                    Branch.company_id == tenant.company_id,
                    Branch.deleted_at.is_(None),
                )
            )
        ).first()
        if terminal_row is None:
            raise NotFoundError("terminal not found")
        terminal = terminal_row.Terminal
        terminal_branch = terminal_row.Branch
        if branch is not None and terminal.branch_id != branch.id:
            raise NotFoundError("terminal not found")
        if branch is None:
            branch = terminal_branch
            branch_id = branch.id

    branch_name = branch.name if branch is not None else context.branch_name
    terminal_name = terminal.name if terminal is not None else context.terminal_name
    return branch_id, branch_name, terminal_id, terminal_name


def _escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _list_conditions(
    *,
    company_id: UUID,
    report_status: BugStatus | None,
    category: BugCategory | None,
    severity: BugSeverity | None,
    platform: str | None,
    reporter_user_id: UUID | None,
    branch_id: UUID | None,
    terminal_id: UUID | None,
    created_from: datetime | None,
    created_to: datetime | None,
    search: str | None,
    exclude: frozenset[str] = frozenset(),
) -> list[ColumnElement[bool]]:
    conditions: list[ColumnElement[bool]] = [BugReport.company_id == company_id]
    if "status" not in exclude and report_status is not None:
        conditions.append(BugReport.status == report_status)
    if "category" not in exclude and category is not None:
        conditions.append(BugReport.category == category)
    if "severity" not in exclude and severity is not None:
        conditions.append(BugReport.severity == severity)
    if "platform" not in exclude and platform is not None:
        conditions.append(BugReport.client_platform == platform)
    if "reporter_user_id" not in exclude and reporter_user_id is not None:
        conditions.append(BugReport.reporter_user_id == reporter_user_id)
    if "branch_id" not in exclude and branch_id is not None:
        conditions.append(BugReport.branch_id == branch_id)
    if "terminal_id" not in exclude and terminal_id is not None:
        conditions.append(BugReport.terminal_id == terminal_id)
    if created_from is not None:
        conditions.append(BugReport.created_at >= created_from)
    if created_to is not None:
        conditions.append(BugReport.created_at <= created_to)
    if search:
        like = f"%{_escape_like(search)}%"
        conditions.append(
            or_(
                BugReport.title.ilike(like, escape="\\"),
                BugReport.description.ilike(like, escape="\\"),
                BugReport.reporter_name.ilike(like, escape="\\"),
                BugReport.reporter_email.ilike(like, escape="\\"),
            )
        )
    return conditions


@router.post("", response_model=BugReportMineRead, status_code=status.HTTP_201_CREATED)
async def create_bug_report(
    payload: BugReportCreate,
    request: Request,
    session: SessionDep,
    tenant: TenantDep,
) -> BugReportMineRead:
    """Accept a report from any authenticated active staff account."""
    idempotency_key, request_hash = _require_idempotency(request)
    # Lock the reporter before reserving the idempotency key. The key row has a
    # foreign key to users, so inserting it first takes a KEY SHARE lock on this
    # same user. Concurrent distinct keys would then each try to upgrade that
    # shared lock to FOR UPDATE and can deadlock. This single lock order also
    # serializes the durable count-then-insert rate-limit check per reporter.
    reporter = (
        await session.execute(
            select(User)
            .where(
                User.id == tenant.user_id,
                User.company_id == tenant.company_id,
                User.status == "active",
                User.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if reporter is None:
        raise NotFoundError("reporter not found")

    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return BugReportMineRead.model_validate(replay["body"])

    cutoff = datetime.now(UTC) - timedelta(hours=1)
    recent_count = int(
        (
            await session.execute(
                select(func.count(BugReport.id)).where(
                    BugReport.company_id == tenant.company_id,
                    BugReport.reporter_user_id == tenant.user_id,
                    BugReport.created_at >= cutoff,
                )
            )
        ).scalar_one()
        or 0
    )
    if recent_count >= _MAX_REPORTS_PER_HOUR:
        raise RateLimitError(
            "Too many bug reports from this account. Wait and try again in up to one hour.",
            details={"limit": _MAX_REPORTS_PER_HOUR, "window_seconds": 3600},
        )

    branch_id, branch_name, terminal_id, terminal_name = await _resolve_context(
        session,
        tenant=tenant,
        context=payload.client_context,
    )
    now = datetime.now(UTC)
    report = BugReport(
        id=uuid4(),
        company_id=tenant.company_id,
        reporter_user_id=tenant.user_id,
        reporter_name=reporter.name,
        reporter_email=reporter.email,
        category=payload.category,
        severity=payload.severity,
        title=payload.title,
        description=payload.description,
        reproduction_steps=payload.reproduction_steps,
        expected_behavior=payload.expected_behavior,
        actual_behavior=payload.actual_behavior,
        client_platform=payload.client_context.platform,
        app_version=payload.client_context.app_version,
        version_code=payload.client_context.version_code,
        device_model=payload.client_context.device_model,
        os_version=payload.client_context.os_version,
        current_screen=payload.client_context.current_screen,
        last_action=payload.client_context.last_action,
        error_code=payload.client_context.error_code,
        branch_id=branch_id,
        branch_name=branch_name,
        terminal_id=terminal_id,
        terminal_name=terminal_name,
        connectivity=payload.client_context.connectivity,
        occurred_at=payload.client_context.occurred_at,
        status="open",
        status_changed_at=now,
        status_changed_by=tenant.user_id,
    )
    session.add(report)
    await session.flush()
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="bug_report_create",
            entity_type="BugReport",
            entity_id=str(report.id),
            before=None,
            after={
                "category": report.category,
                "severity": report.severity,
                "status": report.status,
            },
        )
    )
    await session.flush()
    response = _to_mine_read(report)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get("", response_model=BugReportPage)
async def list_bug_reports(
    session: SessionDep,
    tenant: AdminTenantDep,
    limit: int = Query(default=50, ge=1, le=100),
    offset: int = Query(default=0, ge=0, le=1_000_000),
    report_status: ReportStatusFilter = None,
    category: BugCategory | None = None,
    severity: BugSeverity | None = None,
    platform: str | None = Query(
        default=None,
        min_length=1,
        max_length=20,
        pattern=r"^[A-Za-z0-9_-]+$",
    ),
    reporter_user_id: UUID | None = None,
    branch_id: UUID | None = None,
    terminal_id: UUID | None = None,
    created_from: datetime | None = None,
    created_to: datetime | None = None,
    q: str | None = Query(default=None, min_length=1, max_length=100),
) -> BugReportPage:
    if created_from is not None and created_from.tzinfo is None:
        raise BusinessRuleError("created_from must include a timezone")
    if created_to is not None and created_to.tzinfo is None:
        raise BusinessRuleError("created_to must include a timezone")
    if created_from is not None and created_to is not None and created_from > created_to:
        raise BusinessRuleError("created_from must be on or before created_to")
    normalized_platform = platform.strip().lower() if platform else None
    normalized_search = sanitize_bug_report_text(q) if q else None

    def conditions_for(*, exclude: frozenset[str] = frozenset()) -> list[ColumnElement[bool]]:
        return _list_conditions(
            company_id=tenant.company_id,
            report_status=report_status,
            category=category,
            severity=severity,
            platform=normalized_platform,
            reporter_user_id=reporter_user_id,
            branch_id=branch_id,
            terminal_id=terminal_id,
            created_from=created_from,
            created_to=created_to,
            search=normalized_search,
            exclude=exclude,
        )

    conditions = conditions_for()
    total = int(
        (await session.execute(select(func.count(BugReport.id)).where(*conditions))).scalar_one()
        or 0
    )
    reports = (
        (
            await session.execute(
                select(BugReport)
                .where(*conditions)
                .order_by(BugReport.created_at.desc(), BugReport.id.desc())
                .offset(offset)
                .limit(limit)
            )
        )
        .scalars()
        .all()
    )

    status_rows = (
        await session.execute(
            select(BugReport.status, func.count(BugReport.id))
            .where(*conditions_for(exclude=frozenset({"status"})))
            .group_by(BugReport.status)
        )
    ).all()
    severity_rows = (
        await session.execute(
            select(BugReport.severity, func.count(BugReport.id))
            .where(*conditions_for(exclude=frozenset({"severity"})))
            .group_by(BugReport.severity)
        )
    ).all()
    counts_by_status = dict.fromkeys(_STATUS_VALUES, 0)
    counts_by_status.update({str(value): int(count) for value, count in status_rows})
    counts_by_severity = dict.fromkeys(_SEVERITY_VALUES, 0)
    counts_by_severity.update({str(value): int(count) for value, count in severity_rows})
    replies_by_report, attachments_by_report = await _support_children(
        session,
        company_id=tenant.company_id,
        report_ids=[report.id for report in reports],
    )
    return BugReportPage(
        items=[
            _to_read(
                report,
                replies=replies_by_report.get(report.id),
                attachments=attachments_by_report.get(report.id),
            )
            for report in reports
        ],
        total=total,
        limit=limit,
        offset=offset,
        summary=BugReportSummary(
            counts_by_status=counts_by_status,
            counts_by_severity=counts_by_severity,
        ),
    )


@router.get("/mine", response_model=BugReportMinePage)
async def list_my_bug_reports(
    session: SessionDep,
    tenant: TenantDep,
    limit: int = Query(default=20, ge=1, le=50),
    offset: int = Query(default=0, ge=0, le=1_000_000),
) -> BugReportMinePage:
    """Return only reports authored by the authenticated staff account."""
    conditions = (
        BugReport.company_id == tenant.company_id,
        BugReport.reporter_user_id == tenant.user_id,
    )
    total = int(
        (await session.execute(select(func.count(BugReport.id)).where(*conditions))).scalar_one()
        or 0
    )
    reports = (
        (
            await session.execute(
                select(BugReport)
                .where(*conditions)
                .order_by(BugReport.updated_at.desc(), BugReport.id.desc())
                .offset(offset)
                .limit(limit)
            )
        )
        .scalars()
        .all()
    )
    replies_by_report, attachments_by_report = await _support_children(
        session,
        company_id=tenant.company_id,
        report_ids=[report.id for report in reports],
    )
    return BugReportMinePage(
        items=[
            _to_mine_read(
                report,
                replies=replies_by_report.get(report.id),
                attachments=attachments_by_report.get(report.id),
            )
            for report in reports
        ],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.get("/inbox-summary", response_model=BugReportInboxSummary)
async def bug_report_inbox_summary(
    session: SessionDep,
    tenant: AdminTenantDep,
) -> BugReportInboxSummary:
    """Fast per-owner badge counts without loading the private inbox."""
    read_join = (
        BugReportInboxRead.bug_report_id == BugReport.id,
        BugReportInboxRead.user_id == tenant.user_id,
        BugReportInboxRead.company_id == tenant.company_id,
    )
    base = BugReport.company_id == tenant.company_id
    unread = or_(
        BugReportInboxRead.id.is_(None),
        BugReportInboxRead.last_read_at < BugReport.updated_at,
    )
    row = (
        await session.execute(
            select(
                func.count(BugReport.id).filter(BugReport.status.in_(_ACTIVE_STATUSES)),
                func.count(BugReport.id).filter(unread),
                func.count(BugReport.id).filter(
                    unread,
                    BugReport.status.in_(_ACTIVE_STATUSES),
                    BugReport.severity.in_(("high", "critical")),
                ),
                func.count(BugReport.id).filter(
                    BugReport.status.in_(_ACTIVE_STATUSES),
                    BugReport.severity == "critical",
                ),
                func.max(BugReport.updated_at),
            )
            .select_from(BugReport)
            .outerjoin(BugReportInboxRead, and_(*read_join))
            .where(base)
        )
    ).one()
    return BugReportInboxSummary(
        active=int(row[0] or 0),
        unread=int(row[1] or 0),
        urgent_unread=int(row[2] or 0),
        critical_active=int(row[3] or 0),
        last_activity_at=row[4],
    )


async def _mark_inbox_read(
    session: AsyncSession,
    *,
    company_id: UUID,
    report_id: UUID,
    user_id: UUID,
    read_at: datetime,
) -> None:
    statement = pg_insert(BugReportInboxRead).values(
        id=uuid4(),
        company_id=company_id,
        bug_report_id=report_id,
        user_id=user_id,
        last_read_at=read_at,
        created_at=read_at,
        updated_at=read_at,
    )
    statement = statement.on_conflict_do_update(
        constraint="uq_bug_report_inbox_reads_report_user",
        set_={"last_read_at": read_at, "updated_at": read_at},
    )
    await session.execute(statement)


async def _owned_report_or_404(
    session: AsyncSession,
    *,
    tenant: TenantContext,
    report_id: UUID,
    lock: bool,
) -> BugReport:
    stmt = select(BugReport).where(
        BugReport.id == report_id,
        BugReport.company_id == tenant.company_id,
        BugReport.reporter_user_id == tenant.user_id,
    )
    if lock:
        stmt = stmt.with_for_update()
    report = (await session.execute(stmt)).scalar_one_or_none()
    if report is None:
        raise NotFoundError("bug report not found")
    return report


@router.get("/mine/{report_id}", response_model=BugReportMineRead)
async def get_my_bug_report(
    report_id: UUID,
    session: SessionDep,
    tenant: TenantDep,
) -> BugReportMineRead:
    report = await _owned_report_or_404(
        session,
        tenant=tenant,
        report_id=report_id,
        lock=False,
    )
    replies_by_report, attachments_by_report = await _support_children(
        session,
        company_id=tenant.company_id,
        report_ids=[report.id],
    )
    return _to_mine_read(
        report,
        replies=replies_by_report.get(report.id),
        attachments=attachments_by_report.get(report.id),
    )


def _safe_attachment_filename(filename: str | None, content_type: str) -> str:
    extension = {"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp"}[
        content_type
    ]
    cleaned = sanitize_bug_report_text(Path(filename or f"screenshot{extension}").name)
    if not cleaned:
        return f"screenshot{extension}"
    return cleaned[:160]


@router.post(
    "/mine/{report_id}/attachments",
    response_model=BugReportAttachmentRead,
    status_code=status.HTTP_201_CREATED,
)
async def upload_my_bug_report_attachment(
    report_id: UUID,
    request: Request,
    session: SessionDep,
    tenant: TenantDep,
    file: Annotated[UploadFile, File()],
) -> BugReportAttachmentRead:
    """Store one explicitly selected screenshot; never capture a screen implicitly."""
    idempotency_key, _raw_request_hash = _require_idempotency(request)
    body = await file.read(_MAX_ATTACHMENT_BYTES + 1)
    try:
        sanitized = await to_thread.run_sync(
            sanitize_bug_report_image,
            body,
            file.content_type,
            limiter=_IMAGE_DECODER_LIMITER,
        )
    except BugReportImageError as exc:
        raise BusinessRuleError(str(exc)) from exc

    # Keep replay identity stable across Pillow upgrades while storing only the
    # canonical, metadata-free bytes and their digest.
    source_sha256 = hashlib.sha256(body).hexdigest()
    content_type = sanitized.content_type
    payload = sanitized.payload
    sha256 = sanitized.sha256
    safe_filename = _safe_attachment_filename(file.filename, content_type)
    # Multipart boundaries vary across retries, so the middleware's raw-body
    # hash is not stable. Canonicalize the actual operation identity instead:
    # same report + validated bytes + safe filename replays; anything else
    # conflicts under the same Idempotency-Key.
    request_hash = hashlib.sha256(
        f"{report_id}\0{content_type}\0{source_sha256}\0{safe_filename}".encode()
    ).hexdigest()

    # Authorise before reserving the key. Reserve before taking the company and
    # report locks so retries cannot hold those shared quota locks while they
    # wait for an in-flight request with the same key. The idempotency row's
    # user foreign key also orders its KEY SHARE lock before the later locks.
    await _owned_report_or_404(
        session,
        tenant=tenant,
        report_id=report_id,
        lock=False,
    )
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return BugReportAttachmentRead.model_validate(replay["body"])

    company = (
        await session.execute(
            select(Company)
            .where(Company.id == tenant.company_id, Company.deleted_at.is_(None))
            .with_for_update()
        )
    ).scalar_one_or_none()
    if company is None:
        raise NotFoundError("company not found")
    report = await _owned_report_or_404(
        session,
        tenant=tenant,
        report_id=report_id,
        lock=True,
    )
    duplicate = (
        await session.execute(
            select(BugReportAttachment).where(
                BugReportAttachment.company_id == tenant.company_id,
                BugReportAttachment.bug_report_id == report.id,
                BugReportAttachment.sha256 == sha256,
            )
        )
    ).scalar_one_or_none()
    if duplicate is not None:
        response = _attachment_to_read(duplicate)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    attachment_count = int(
        (
            await session.execute(
                select(func.count(BugReportAttachment.id)).where(
                    BugReportAttachment.company_id == tenant.company_id,
                    BugReportAttachment.bug_report_id == report.id,
                )
            )
        ).scalar_one()
        or 0
    )
    if attachment_count >= _MAX_ATTACHMENTS_PER_REPORT:
        raise BusinessRuleError("A report can contain up to three screenshots.")

    # Reclaim this tenant's expired bytes before enforcing the low-volume
    # database-backed storage budget. The Company row lock serializes uploads
    # across different reports so concurrent requests cannot overrun the cap.
    await purge_expired_bug_report_attachments(
        session,
        now=datetime.now(UTC),
        batch_size=1_000,
        company_id=tenant.company_id,
    )
    company_bytes = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(BugReportAttachment.byte_size), 0)).where(
                    BugReportAttachment.company_id == tenant.company_id,
                    BugReportAttachment.payload.is_not(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if company_bytes + len(payload) > _MAX_COMPANY_ATTACHMENT_BYTES:
        raise BusinessRuleError(
            "The company Support screenshot allowance is full. Wait for retention cleanup "
            "or ask the system owner to archive older evidence."
        )

    now = datetime.now(UTC)
    attachment = BugReportAttachment(
        id=uuid4(),
        company_id=tenant.company_id,
        bug_report_id=report.id,
        uploader_user_id=tenant.user_id,
        original_filename=safe_filename,
        content_type=content_type,
        byte_size=len(payload),
        sha256=sha256,
        payload=payload,
        created_at=now,
        expires_at=now + timedelta(days=_ATTACHMENT_RETENTION_DAYS),
    )
    session.add(attachment)
    report.updated_at = now
    await session.flush()
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="bug_report_attachment_add",
            entity_type="BugReport",
            entity_id=str(report.id),
            before=None,
            after={
                "attachment_id": str(attachment.id),
                "content_type": attachment.content_type,
                "byte_size": attachment.byte_size,
            },
        )
    )
    response = _attachment_to_read(attachment, now=now)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


async def _attachment_or_404(
    session: AsyncSession,
    *,
    company_id: UUID,
    report_id: UUID,
    attachment_id: UUID,
) -> BugReportAttachment:
    attachment = (
        await session.execute(
            select(BugReportAttachment)
            .options(undefer(BugReportAttachment.payload))
            .where(
                BugReportAttachment.id == attachment_id,
                BugReportAttachment.company_id == company_id,
                BugReportAttachment.bug_report_id == report_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if attachment is None:
        raise NotFoundError("bug report attachment not found")
    return attachment


async def _private_attachment_response(
    session: AsyncSession,
    attachment: BugReportAttachment,
) -> Response:
    now = datetime.now(UTC)
    if attachment.purged_at is not None or attachment.expires_at <= now:
        if attachment.payload is not None:
            attachment.payload = None
            attachment.purged_at = now
            await session.flush()
        return Response(
            status_code=status.HTTP_410_GONE,
            headers={"Cache-Control": "private, no-store"},
        )
    payload = attachment.payload
    if payload is None:
        return Response(
            status_code=status.HTTP_410_GONE,
            headers={"Cache-Control": "private, no-store"},
        )
    safe_name = attachment.original_filename.replace('"', "").replace("\r", "").replace(
        "\n", ""
    )
    return Response(
        content=payload,
        media_type=attachment.content_type,
        headers={
            "Cache-Control": "private, no-store",
            "Content-Disposition": f'inline; filename="{safe_name}"',
            "Content-Security-Policy": "sandbox",
            "X-Content-Type-Options": "nosniff",
        },
    )


@router.get("/mine/{report_id}/attachments/{attachment_id}", response_class=Response)
async def download_my_bug_report_attachment(
    report_id: UUID,
    attachment_id: UUID,
    session: SessionDep,
    tenant: TenantDep,
) -> Response:
    await _owned_report_or_404(
        session,
        tenant=tenant,
        report_id=report_id,
        lock=False,
    )
    attachment = await _attachment_or_404(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        attachment_id=attachment_id,
    )
    return await _private_attachment_response(session, attachment)


@router.get("/{report_id}/attachments/{attachment_id}", response_class=Response)
async def download_bug_report_attachment(
    report_id: UUID,
    attachment_id: UUID,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> Response:
    await _report_or_404(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        lock=False,
    )
    attachment = await _attachment_or_404(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        attachment_id=attachment_id,
    )
    return await _private_attachment_response(session, attachment)


@router.post("/{report_id}/read", status_code=status.HTTP_204_NO_CONTENT)
async def mark_bug_report_read(
    report_id: UUID,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> Response:
    await _report_or_404(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        lock=False,
    )
    await _mark_inbox_read(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        user_id=tenant.user_id,
        read_at=datetime.now(UTC),
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/{report_id}/public-replies",
    response_model=BugReportPublicReplyRead,
    status_code=status.HTTP_201_CREATED,
)
async def add_bug_report_public_reply(
    report_id: UUID,
    payload: BugReportPublicReplyCreate,
    request: Request,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> BugReportPublicReplyRead:
    idempotency_key, request_hash = _require_idempotency(request)
    author = (
        await session.execute(
            select(User)
            .where(
                User.id == tenant.user_id,
                User.company_id == tenant.company_id,
                User.status == "active",
                User.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if author is None:
        raise NotFoundError("support author not found")
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return BugReportPublicReplyRead.model_validate(replay["body"])
    report = await _report_or_404(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        lock=True,
    )
    now = datetime.now(UTC)
    reply = BugReportPublicReply(
        id=uuid4(),
        company_id=tenant.company_id,
        bug_report_id=report.id,
        author_user_id=tenant.user_id,
        author_name=author.name,
        message=payload.message,
        created_at=now,
    )
    session.add(reply)
    # Public replies are relevant activity for both the reporter and every
    # other protected owner. The replying owner's own cursor is advanced below.
    report.updated_at = now
    await session.flush()
    await _mark_inbox_read(
        session,
        company_id=tenant.company_id,
        report_id=report.id,
        user_id=tenant.user_id,
        read_at=now,
    )
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="bug_report_public_reply_add",
            entity_type="BugReport",
            entity_id=str(report.id),
            before=None,
            after={"reply_id": str(reply.id)},
        )
    )
    response = _reply_to_read(reply)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


async def _report_or_404(
    session: AsyncSession,
    *,
    company_id: UUID,
    report_id: UUID,
    lock: bool,
) -> BugReport:
    stmt = select(BugReport).where(
        BugReport.id == report_id,
        BugReport.company_id == company_id,
    )
    if lock:
        stmt = stmt.with_for_update()
    report = (await session.execute(stmt)).scalar_one_or_none()
    if report is None:
        raise NotFoundError("bug report not found")
    return report


@router.get("/{report_id}", response_model=BugReportRead)
async def get_bug_report(
    report_id: UUID,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> BugReportRead:
    report = await _report_or_404(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        lock=False,
    )
    replies_by_report, attachments_by_report = await _support_children(
        session,
        company_id=tenant.company_id,
        report_ids=[report.id],
    )
    return _to_read(
        report,
        replies=replies_by_report.get(report.id),
        attachments=attachments_by_report.get(report.id),
    )


@router.patch("/{report_id}", response_model=BugReportRead)
async def update_bug_report(
    report_id: UUID,
    payload: BugReportUpdate,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> BugReportRead:
    report = await _report_or_404(
        session,
        company_id=tenant.company_id,
        report_id=report_id,
        lock=True,
    )
    old_status = report.status
    old_note = report.internal_resolution_note
    status_requested = "status" in payload.model_fields_set
    note_requested = "internal_resolution_note" in payload.model_fields_set
    new_status = payload.status if status_requested else old_status
    new_note = payload.internal_resolution_note if note_requested else old_note

    if new_status is None:
        raise BusinessRuleError("status cannot be cleared")
    status_changed = new_status != old_status
    note_changed = new_note != old_note
    if status_changed and new_status not in BUG_REPORT_STATUS_TRANSITIONS[old_status]:
        raise BusinessRuleError(
            f"bug report cannot move from {old_status} to {new_status}",
            details={"from_status": old_status, "to_status": new_status},
        )
    if new_status in {"resolved", "closed", "rejected"} and (
        new_note is None or len(new_note.strip()) < 3
    ):
        raise BusinessRuleError(
            "Add an internal resolution note before resolving, closing, or rejecting a report."
        )
    if not status_changed and not note_changed:
        # PATCH is idempotent for an exact retry. This matters when the first
        # response is lost after commit: the operator should see the current
        # successful state, not an error suggesting that their update failed.
        await _mark_inbox_read(
            session,
            company_id=tenant.company_id,
            report_id=report.id,
            user_id=tenant.user_id,
            read_at=datetime.now(UTC),
        )
        replies_by_report, attachments_by_report = await _support_children(
            session,
            company_id=tenant.company_id,
            report_ids=[report.id],
        )
        return _to_read(
            report,
            replies=replies_by_report.get(report.id),
            attachments=attachments_by_report.get(report.id),
        )

    now = datetime.now(UTC)
    if note_changed:
        report.internal_resolution_note = new_note
    if status_changed:
        report.status = new_status
        report.status_changed_at = now
        report.status_changed_by = tenant.user_id
        if new_status == "resolved":
            report.resolved_at = now
            report.resolved_by = tenant.user_id
        elif old_status in {"resolved", "closed"} and new_status == "in_progress":
            # An active/reopened report must not retain metadata that makes it
            # look currently resolved in the inbox.
            report.resolved_at = None
            report.resolved_by = None

    if status_changed:
        session.add(
            AuditLog(
                actor_user_id=tenant.user_id,
                company_id=tenant.company_id,
                action="bug_report_status_change",
                entity_type="BugReport",
                entity_id=str(report.id),
                before={"status": old_status},
                after={"status": new_status},
            )
        )
    if note_changed:
        session.add(
            AuditLog(
                actor_user_id=tenant.user_id,
                company_id=tenant.company_id,
                action="bug_report_resolution_note_change",
                entity_type="BugReport",
                entity_id=str(report.id),
                before={"note_present": bool(old_note)},
                after={"note_present": bool(new_note)},
            )
        )
    await session.flush()
    # PostgreSQL's integrity trigger owns updated_at, so refresh the
    # server-mutated value explicitly instead of allowing a lazy async load
    # during response serialization.
    await session.refresh(report)
    await _mark_inbox_read(
        session,
        company_id=tenant.company_id,
        report_id=report.id,
        user_id=tenant.user_id,
        read_at=datetime.now(UTC),
    )
    replies_by_report, attachments_by_report = await _support_children(
        session,
        company_id=tenant.company_id,
        report_ids=[report.id],
    )
    return _to_read(
        report,
        replies=replies_by_report.get(report.id),
        attachments=attachments_by_report.get(report.id),
    )


__all__ = ["router"]
