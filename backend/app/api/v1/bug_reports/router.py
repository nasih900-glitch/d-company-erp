"""Tenant-scoped bug-report submission and protected support inbox APIs."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Annotated, Literal, cast
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Query, Request, status
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import func, or_, select

from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves dependency aliases
from app.core.errors import BusinessRuleError, NotFoundError, RateLimitError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.tenant import (  # noqa: TC001 - FastAPI resolves dependency aliases
    TenantContext,
    TenantDep,
)
from app.models import AuditLog, Branch, BugReport, Terminal, User
from app.models.bug_report import BUG_REPORT_STATUS_TRANSITIONS
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
    branch_id: UUID | None
    branch_name: str | None
    terminal_id: UUID | None
    terminal_name: str | None
    connectivity: BugConnectivity
    occurred_at: datetime | None


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
    reporter: BugReportReporterRead
    status_changed_at: datetime | None
    status_changed_by: UUID | None
    resolved_at: datetime | None
    resolved_by: UUID | None
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


def _to_read(report: BugReport) -> BugReportRead:
    return BugReportRead(
        id=report.id,
        category=cast("BugCategory", report.category),
        severity=cast("BugSeverity", report.severity),
        title=report.title,
        description=report.description,
        reproduction_steps=report.reproduction_steps,
        expected_behavior=report.expected_behavior,
        actual_behavior=report.actual_behavior,
        client_context=BugReportClientContextRead(
            platform=report.client_platform,
            app_version=report.app_version,
            version_code=report.version_code,
            device_model=report.device_model,
            os_version=report.os_version,
            current_screen=report.current_screen,
            branch_id=report.branch_id,
            branch_name=report.branch_name,
            terminal_id=report.terminal_id,
            terminal_name=report.terminal_name,
            connectivity=cast("BugConnectivity", report.connectivity),
            occurred_at=report.occurred_at,
        ),
        status=cast("BugStatus", report.status),
        internal_resolution_note=report.internal_resolution_note,
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


@router.post("", response_model=BugReportRead, status_code=status.HTTP_201_CREATED)
async def create_bug_report(
    payload: BugReportCreate,
    request: Request,
    session: SessionDep,
    tenant: TenantDep,
) -> BugReportRead:
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
        return BugReportRead.model_validate(replay["body"])

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
    response = _to_read(report)
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
    return BugReportPage(
        items=[_to_read(report) for report in reports],
        total=total,
        limit=limit,
        offset=offset,
        summary=BugReportSummary(
            counts_by_status=counts_by_status,
            counts_by_severity=counts_by_severity,
        ),
    )


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
    return _to_read(
        await _report_or_404(
            session,
            company_id=tenant.company_id,
            report_id=report_id,
            lock=False,
        )
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
        return _to_read(report)

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
    return _to_read(report)


__all__ = ["router"]
