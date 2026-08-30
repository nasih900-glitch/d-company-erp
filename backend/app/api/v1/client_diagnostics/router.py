"""Offline-safe diagnostic ingestion and protected owner System Health."""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Annotated, Literal, cast, get_args
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Query, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from redis.asyncio import Redis
from redis.exceptions import RedisError
from sqlalchemy import delete, func, select, text
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.core.config import get_settings
from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves dependency annotations
from app.core.errors import (
    BusinessRuleError,
    ClientTelemetryCapacityError,
    DiagnosticIdempotencyConflictError,
    DiagnosticIngestRetryError,
)
from app.core.logging import get_logger
from app.core.permissions import requires
from app.core.tenant import (  # noqa: TC001 - FastAPI resolves dependency annotations
    TenantContext,
    TenantDep,
)
from app.models import AndroidRelease, ClientDiagnosticEvent, ClientInstallation
from app.models.client_diagnostic import (
    CLIENT_DIAGNOSTIC_COMPONENTS,
    CLIENT_DIAGNOSTIC_CONNECTIVITY,
    CLIENT_DIAGNOSTIC_DURATION_BUCKETS,
    CLIENT_DIAGNOSTIC_EVENT_TYPES,
    CLIENT_DIAGNOSTIC_RETENTION_DAYS,
    CLIENT_DIAGNOSTIC_SEVERITIES,
    CLIENT_DIAGNOSTICS_MAX_PER_COMPANY,
    CLIENT_DIAGNOSTICS_MAX_PER_INSTALLATION,
    CLIENT_DIAGNOSTICS_MAX_PER_USER,
)
from app.services.client_diagnostics.rate_limit import enforce_client_diagnostic_rate_limit

if TYPE_CHECKING:
    from sqlalchemy.orm import InstrumentedAttribute

router = APIRouter()
log = get_logger(__name__)

DiagnosticEventType = Literal["crash", "anr", "api_failure", "sync_stall"]
DiagnosticSeverity = Literal["warning", "error", "critical"]
DiagnosticComponent = Literal[
    "app",
    "auth",
    "gaming",
    "pos",
    "finance",
    "sync",
    "network",
    "updates",
    "storage",
]
DiagnosticConnectivity = Literal["online", "offline", "unknown"]
DiagnosticDurationBucket = Literal[
    "under_5s",
    "5_to_30s",
    "30s_to_2m",
    "2_to_10m",
    "over_10m",
]
SystemHealthStatus = Literal["healthy", "degraded", "action_required"]
DependencyStatus = Literal["operational", "unavailable"]
BackupStatus = Literal["operational", "unavailable", "unknown"]

AdminTenantDep = Annotated[TenantContext, Depends(requires("admin.system"))]

_VERSION_NAME_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$")
_REASON_CODE_RE = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,63}$")
_FINGERPRINT_RE = re.compile(r"^[0-9a-f]{64}$")
_MAX_FUTURE_SKEW = timedelta(hours=24)
_RETENTION = timedelta(days=CLIENT_DIAGNOSTIC_RETENTION_DAYS)
_LOCK_PREFIX = "dcompany-client-diagnostics:"

assert set(get_args(DiagnosticEventType)) == set(CLIENT_DIAGNOSTIC_EVENT_TYPES)
assert set(get_args(DiagnosticSeverity)) == set(CLIENT_DIAGNOSTIC_SEVERITIES)
assert set(get_args(DiagnosticComponent)) == set(CLIENT_DIAGNOSTIC_COMPONENTS)
assert set(get_args(DiagnosticConnectivity)) == set(CLIENT_DIAGNOSTIC_CONNECTIVITY)
assert set(get_args(DiagnosticDurationBucket)) == set(CLIENT_DIAGNOSTIC_DURATION_BUCKETS)


def _validate_event_time(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("occurred_at must include a timezone")
    normalized = value.astimezone(UTC)
    now = datetime.now(UTC)
    if normalized > now + _MAX_FUTURE_SKEW:
        raise ValueError("occurred_at cannot be more than 24 hours in the future")
    if normalized < now - _RETENTION:
        raise ValueError("occurred_at is outside the 90-day diagnostic retention window")
    return normalized


class ClientDiagnosticWrite(BaseModel):
    """Allowlisted categorical evidence; deliberately no free-form message field."""

    model_config = ConfigDict(extra="forbid")

    client_event_id: UUID
    event_type: DiagnosticEventType
    severity: DiagnosticSeverity
    occurred_at: datetime
    version_name: str = Field(min_length=1, max_length=80)
    version_code: int = Field(ge=1, le=2_147_483_647)
    os_api_level: int | None = Field(default=None, ge=21, le=100)
    component: DiagnosticComponent
    reason_code: str = Field(min_length=1, max_length=64)
    failure_fingerprint: str | None = Field(default=None, min_length=64, max_length=64)
    http_status: int | None = Field(default=None, ge=100, le=599)
    duration_bucket: DiagnosticDurationBucket | None = None
    connectivity: DiagnosticConnectivity = "unknown"
    pending_outbox_count: int | None = Field(default=None, ge=0, le=1_000_000)

    @field_validator("client_event_id")
    @classmethod
    def require_random_event_uuid(cls, value: UUID) -> UUID:
        if value.version != 4:
            raise ValueError("client_event_id must be a random UUID v4")
        return value

    @field_validator("occurred_at")
    @classmethod
    def require_retained_time(cls, value: datetime) -> datetime:
        return _validate_event_time(value)

    @field_validator("version_name")
    @classmethod
    def normalize_version_name(cls, value: str) -> str:
        normalized = value.strip()
        if _VERSION_NAME_RE.fullmatch(normalized) is None:
            raise ValueError(
                "version_name may contain only letters, digits, dot, underscore, plus, or hyphen"
            )
        return normalized

    @field_validator("reason_code")
    @classmethod
    def normalize_reason_code(cls, value: str) -> str:
        normalized = value.strip().lower()
        if _REASON_CODE_RE.fullmatch(normalized) is None:
            raise ValueError("reason_code must be a lowercase diagnostic code, not message text")
        return normalized

    @field_validator("failure_fingerprint", mode="before")
    @classmethod
    def normalize_fingerprint(cls, value: object) -> object:
        if value is None or not isinstance(value, str):
            return value
        normalized = value.strip().lower()
        if _FINGERPRINT_RE.fullmatch(normalized) is None:
            raise ValueError("failure_fingerprint must be a SHA-256 hex digest")
        return normalized

    @model_validator(mode="after")
    def validate_http_evidence(self) -> ClientDiagnosticWrite:
        if self.event_type != "api_failure" and self.http_status is not None:
            raise ValueError("http_status is valid only for api_failure events")
        return self


class ClientDiagnosticBatchWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    events: list[ClientDiagnosticWrite] = Field(min_length=1, max_length=25)

    @field_validator("installation_id")
    @classmethod
    def require_random_installation_uuid(cls, value: UUID) -> UUID:
        if value.version != 4:
            raise ValueError("installation_id must be a random UUID v4")
        return value

    @model_validator(mode="after")
    def require_unique_event_ids(self) -> ClientDiagnosticBatchWrite:
        event_ids = [item.client_event_id for item in self.events]
        if len(event_ids) != len(set(event_ids)):
            raise ValueError("events must not repeat client_event_id in one batch")
        return self


class ClientDiagnosticBatchRead(BaseModel):
    installation_id: UUID
    server_time: datetime
    accepted_event_ids: list[UUID]
    duplicate_event_ids: list[UUID]


class ClientDiagnosticRead(BaseModel):
    id: UUID
    installation_id: UUID
    client_event_id: UUID
    actor_user_id: UUID
    terminal_id: UUID | None
    event_type: DiagnosticEventType
    severity: DiagnosticSeverity
    occurred_at: datetime
    received_at: datetime
    version_name: str
    version_code: int
    os_api_level: int | None
    component: DiagnosticComponent
    reason_code: str
    failure_fingerprint: str | None
    http_status: int | None
    duration_bucket: DiagnosticDurationBucket | None
    connectivity: DiagnosticConnectivity
    pending_outbox_count: int | None


class ClientDiagnosticPage(BaseModel):
    items: list[ClientDiagnosticRead]
    total: int
    limit: int
    offset: int
    retention_days: int


class ClientDiagnosticSummary(BaseModel):
    server_time: datetime
    window_hours: int
    total: int
    critical_count: int
    affected_installations: int
    offline_event_count: int
    latest_event_at: datetime | None
    counts_by_type: dict[str, int]
    counts_by_severity: dict[str, int]
    counts_by_component: dict[str, int]


class SystemHealthDependencies(BaseModel):
    api: DependencyStatus
    database: DependencyStatus
    redis: DependencyStatus


class SystemHealthDevices(BaseModel):
    total: int
    seen_last_24h: int
    stale: int
    with_pending_sync: int
    sync_stalled: int
    max_pending_outbox_count: int
    latest_supported_version_code: int
    outdated_installations: int


class SystemHealthBackups(BaseModel):
    status: BackupStatus
    last_success_at: datetime | None
    restore_tested_at: datetime | None
    evidence_code: Literal["host_monitor_not_connected"]


class SystemHealthRead(BaseModel):
    status: SystemHealthStatus
    server_time: datetime
    retention_days: int
    dependencies: SystemHealthDependencies
    backups: SystemHealthBackups
    devices: SystemHealthDevices
    diagnostics: ClientDiagnosticSummary
    recommendations: list[str]


def _event_payload(row: ClientDiagnosticEvent) -> tuple[object, ...]:
    return (
        row.event_type,
        row.severity,
        row.occurred_at.astimezone(UTC),
        row.version_name,
        row.version_code,
        row.os_api_level,
        row.component,
        row.reason_code,
        row.failure_fingerprint,
        row.http_status,
        row.duration_bucket,
        row.connectivity,
        row.pending_outbox_count,
    )


def _submitted_payload(item: ClientDiagnosticWrite) -> tuple[object, ...]:
    return (
        item.event_type,
        item.severity,
        item.occurred_at,
        item.version_name,
        item.version_code,
        item.os_api_level,
        item.component,
        item.reason_code,
        item.failure_fingerprint,
        item.http_status,
        item.duration_bucket,
        item.connectivity,
        item.pending_outbox_count,
    )


def _read(row: ClientDiagnosticEvent) -> ClientDiagnosticRead:
    return ClientDiagnosticRead(
        id=row.id,
        installation_id=row.installation_id,
        client_event_id=row.client_event_id,
        actor_user_id=row.actor_user_id,
        terminal_id=row.terminal_id,
        event_type=cast("DiagnosticEventType", row.event_type),
        severity=cast("DiagnosticSeverity", row.severity),
        occurred_at=row.occurred_at,
        received_at=row.received_at,
        version_name=row.version_name,
        version_code=row.version_code,
        os_api_level=row.os_api_level,
        component=cast("DiagnosticComponent", row.component),
        reason_code=row.reason_code,
        failure_fingerprint=row.failure_fingerprint,
        http_status=row.http_status,
        duration_bucket=cast("DiagnosticDurationBucket | None", row.duration_bucket),
        connectivity=cast("DiagnosticConnectivity", row.connectivity),
        pending_outbox_count=row.pending_outbox_count,
    )


async def _lock_company(session: SessionDep, company_id: UUID) -> None:
    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))"),
        {"scope": f"{_LOCK_PREFIX}{company_id}"},
    )


def _capacity_error(*, scope: str, limit: int) -> ClientTelemetryCapacityError:
    return ClientTelemetryCapacityError(
        "This shop's retained diagnostic evidence has reached its safety limit. "
        "Existing records were preserved; the protected owner should review System Health.",
        details={"resource": "client_diagnostics", "scope": scope, "limit": limit},
    )


async def _enforce_capacity(
    session: SessionDep,
    *,
    company_id: UUID,
    user_id: UUID,
    installation_id: UUID,
    new_event_count: int,
) -> None:
    checks = (
        (
            "installation",
            CLIENT_DIAGNOSTICS_MAX_PER_INSTALLATION,
            ClientDiagnosticEvent.installation_id == installation_id,
        ),
        (
            "user",
            CLIENT_DIAGNOSTICS_MAX_PER_USER,
            ClientDiagnosticEvent.actor_user_id == user_id,
        ),
        (
            "company",
            CLIENT_DIAGNOSTICS_MAX_PER_COMPANY,
            ClientDiagnosticEvent.company_id == company_id,
        ),
    )
    for scope, limit, predicate in checks:
        count = int(
            (
                await session.execute(
                    select(func.count(ClientDiagnosticEvent.id)).where(
                        ClientDiagnosticEvent.company_id == company_id,
                        predicate,
                    )
                )
            ).scalar_one()
        )
        if count + new_event_count > limit:
            raise _capacity_error(scope=scope, limit=limit)


@router.post("/events", response_model=ClientDiagnosticBatchRead)
async def ingest_events(
    payload: ClientDiagnosticBatchWrite,
    session: SessionDep,
    tenant: TenantDep,
) -> ClientDiagnosticBatchRead:
    """Accept one offline-safe batch with per-event replay idempotency."""
    await enforce_client_diagnostic_rate_limit(
        company_id=tenant.company_id,
        user_id=tenant.user_id,
        event_count=len(payload.events),
    )
    await _lock_company(session, tenant.company_id)

    server_time = datetime.now(UTC)
    await session.execute(
        delete(ClientDiagnosticEvent).where(
            ClientDiagnosticEvent.company_id == tenant.company_id,
            ClientDiagnosticEvent.received_at < server_time - _RETENTION,
        )
    )

    submitted = {item.client_event_id: item for item in payload.events}
    existing_rows = (
        (
            await session.execute(
                select(ClientDiagnosticEvent).where(
                    ClientDiagnosticEvent.company_id == tenant.company_id,
                    ClientDiagnosticEvent.installation_id == payload.installation_id,
                    ClientDiagnosticEvent.client_event_id.in_(submitted),
                )
            )
        )
        .scalars()
        .all()
    )
    existing = {row.client_event_id: row for row in existing_rows}
    for event_id, row in existing.items():
        if _event_payload(row) != _submitted_payload(submitted[event_id]):
            raise DiagnosticIdempotencyConflictError(
                "A client_event_id was already used for different diagnostic evidence.",
                details={"client_event_id": str(event_id)},
            )

    new_events = [item for item in payload.events if item.client_event_id not in existing]
    if new_events:
        await _enforce_capacity(
            session,
            company_id=tenant.company_id,
            user_id=tenant.user_id,
            installation_id=payload.installation_id,
            new_event_count=len(new_events),
        )
        statement = (
            pg_insert(ClientDiagnosticEvent)
            .values(
                [
                    {
                        "id": uuid4(),
                        "company_id": tenant.company_id,
                        "installation_id": payload.installation_id,
                        "client_event_id": item.client_event_id,
                        "actor_user_id": tenant.user_id,
                        "terminal_id": tenant.terminal_id,
                        "event_type": item.event_type,
                        "severity": item.severity,
                        "component": item.component,
                        "reason_code": item.reason_code,
                        "failure_fingerprint": item.failure_fingerprint,
                        "version_name": item.version_name,
                        "version_code": item.version_code,
                        "os_api_level": item.os_api_level,
                        "http_status": item.http_status,
                        "duration_bucket": item.duration_bucket,
                        "connectivity": item.connectivity,
                        "pending_outbox_count": item.pending_outbox_count,
                        "occurred_at": item.occurred_at,
                    }
                    for item in new_events
                ]
            )
            .on_conflict_do_nothing(
                constraint="uq_client_diagnostic_events_company_installation_event"
            )
            .returning(ClientDiagnosticEvent.client_event_id)
        )
        accepted = set((await session.execute(statement)).scalars().all())
        expected = {item.client_event_id for item in new_events}
        if accepted != expected:
            raise DiagnosticIngestRetryError(
                "Diagnostic evidence changed concurrently. Retry with the same event ids."
            )
    else:
        accepted = set()

    return ClientDiagnosticBatchRead(
        installation_id=payload.installation_id,
        server_time=server_time,
        accepted_event_ids=[
            item.client_event_id for item in payload.events if item.client_event_id in accepted
        ],
        duplicate_event_ids=[
            item.client_event_id for item in payload.events if item.client_event_id in existing
        ],
    )


def _bounded_filter_time(value: datetime | None, *, field_name: str) -> datetime | None:
    if value is None:
        return None
    if value.tzinfo is None or value.utcoffset() is None:
        raise BusinessRuleError(f"{field_name} must include a timezone")
    return value.astimezone(UTC)


@router.get("/events", response_model=ClientDiagnosticPage)
async def list_events(
    response: Response,
    session: SessionDep,
    tenant: AdminTenantDep,
    event_type: DiagnosticEventType | None = None,
    severity: DiagnosticSeverity | None = None,
    component: DiagnosticComponent | None = None,
    installation_id: UUID | None = None,
    since: datetime | None = None,
    until: datetime | None = None,
    limit: int = Query(default=100, ge=1, le=200),
    offset: int = Query(default=0, ge=0, le=100_000),
) -> ClientDiagnosticPage:
    """List sanitized diagnostics for the protected owner only."""
    response.headers["Cache-Control"] = "private, no-store"
    since = _bounded_filter_time(since, field_name="since")
    until = _bounded_filter_time(until, field_name="until")
    if since is not None and until is not None and since > until:
        raise BusinessRuleError("since must not be later than until")

    conditions = [ClientDiagnosticEvent.company_id == tenant.company_id]
    if event_type is not None:
        conditions.append(ClientDiagnosticEvent.event_type == event_type)
    if severity is not None:
        conditions.append(ClientDiagnosticEvent.severity == severity)
    if component is not None:
        conditions.append(ClientDiagnosticEvent.component == component)
    if installation_id is not None:
        conditions.append(ClientDiagnosticEvent.installation_id == installation_id)
    if since is not None:
        conditions.append(ClientDiagnosticEvent.occurred_at >= since)
    if until is not None:
        conditions.append(ClientDiagnosticEvent.occurred_at <= until)

    rows = (
        (
            await session.execute(
                select(ClientDiagnosticEvent)
                .where(*conditions)
                .order_by(
                    ClientDiagnosticEvent.occurred_at.desc(),
                    ClientDiagnosticEvent.id.desc(),
                )
                .offset(offset)
                .limit(limit)
            )
        )
        .scalars()
        .all()
    )
    total = int(
        (
            await session.execute(
                select(func.count(ClientDiagnosticEvent.id)).where(*conditions)
            )
        ).scalar_one()
    )
    return ClientDiagnosticPage(
        items=[_read(row) for row in rows],
        total=total,
        limit=limit,
        offset=offset,
        retention_days=CLIENT_DIAGNOSTIC_RETENTION_DAYS,
    )


async def _build_summary(
    session: SessionDep,
    *,
    company_id: UUID,
    window_hours: int,
    server_time: datetime,
) -> ClientDiagnosticSummary:
    cutoff = server_time - timedelta(hours=window_hours)
    conditions = (
        ClientDiagnosticEvent.company_id == company_id,
        ClientDiagnosticEvent.occurred_at >= cutoff,
        ClientDiagnosticEvent.occurred_at <= server_time,
    )
    total, critical_count, affected, offline_count, latest = (
        await session.execute(
            select(
                func.count(ClientDiagnosticEvent.id),
                func.count(ClientDiagnosticEvent.id).filter(
                    ClientDiagnosticEvent.severity == "critical"
                ),
                func.count(func.distinct(ClientDiagnosticEvent.installation_id)),
                func.count(ClientDiagnosticEvent.id).filter(
                    ClientDiagnosticEvent.connectivity == "offline"
                ),
                func.max(ClientDiagnosticEvent.occurred_at),
            ).where(*conditions)
        )
    ).one()

    async def grouped(column: InstrumentedAttribute[str]) -> dict[str, int]:
        result = await session.execute(
            select(column, func.count(ClientDiagnosticEvent.id))
            .where(*conditions)
            .group_by(column)
        )
        return {str(key): int(count) for key, count in result.all()}

    type_counts = dict.fromkeys(CLIENT_DIAGNOSTIC_EVENT_TYPES, 0)
    type_counts.update(await grouped(ClientDiagnosticEvent.event_type))
    severity_counts = dict.fromkeys(CLIENT_DIAGNOSTIC_SEVERITIES, 0)
    severity_counts.update(await grouped(ClientDiagnosticEvent.severity))
    component_counts = dict.fromkeys(CLIENT_DIAGNOSTIC_COMPONENTS, 0)
    component_counts.update(await grouped(ClientDiagnosticEvent.component))

    return ClientDiagnosticSummary(
        server_time=server_time,
        window_hours=window_hours,
        total=int(total),
        critical_count=int(critical_count),
        affected_installations=int(affected),
        offline_event_count=int(offline_count),
        latest_event_at=latest,
        counts_by_type=type_counts,
        counts_by_severity=severity_counts,
        counts_by_component=component_counts,
    )


@router.get("/summary", response_model=ClientDiagnosticSummary)
async def diagnostic_summary(
    response: Response,
    session: SessionDep,
    tenant: AdminTenantDep,
    window_hours: int = Query(default=24, ge=1, le=720),
) -> ClientDiagnosticSummary:
    response.headers["Cache-Control"] = "private, no-store"
    return await _build_summary(
        session,
        company_id=tenant.company_id,
        window_hours=window_hours,
        server_time=datetime.now(UTC),
    )


async def _redis_dependency_status() -> DependencyStatus:
    redis = Redis.from_url(
        str(get_settings().redis_url),
        decode_responses=True,
        socket_connect_timeout=1,
        socket_timeout=1,
    )
    try:
        await redis.ping()
    except RedisError as exc:
        log.warning(
            "system_health.dependency_unavailable",
            dependency="redis",
            error=type(exc).__name__,
        )
        return "unavailable"
    finally:
        await redis.aclose()
    return "operational"


@router.get("/system-health", response_model=SystemHealthRead)
async def system_health(
    response: Response,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> SystemHealthRead:
    """Return owner-safe health signals without infrastructure or log details."""
    response.headers["Cache-Control"] = "private, no-store"
    server_time = datetime.now(UTC)
    diagnostics = await _build_summary(
        session,
        company_id=tenant.company_id,
        window_hours=24,
        server_time=server_time,
    )

    stale_before = server_time - timedelta(hours=24)
    # `sync_stalled` is incident evidence, not a guess from the age of the
    # latest successful sync. A pending queue can be entirely new and healthy.
    # Count only installations that explicitly reported a sync stall recently.
    sync_stall_since = server_time - timedelta(hours=2)
    (
        total_devices,
        recent_devices,
        stale_devices,
        pending_devices,
        max_pending,
    ) = (
        await session.execute(
            select(
                func.count(ClientInstallation.id),
                func.count(ClientInstallation.id).filter(
                    ClientInstallation.last_seen_at >= stale_before
                ),
                func.count(ClientInstallation.id).filter(
                    ClientInstallation.last_seen_at < stale_before
                ),
                func.count(ClientInstallation.id).filter(
                    ClientInstallation.pending_outbox_count > 0
                ),
                func.coalesce(func.max(ClientInstallation.pending_outbox_count), 0),
            ).where(ClientInstallation.company_id == tenant.company_id)
        )
    ).one()
    stalled_devices = int(
        (
            await session.execute(
                select(func.count(func.distinct(ClientDiagnosticEvent.installation_id))).where(
                    ClientDiagnosticEvent.company_id == tenant.company_id,
                    ClientDiagnosticEvent.event_type == "sync_stall",
                    ClientDiagnosticEvent.occurred_at >= sync_stall_since,
                    ClientDiagnosticEvent.occurred_at <= server_time,
                )
            )
        ).scalar_one()
    )

    active_release_code = (
        await session.execute(
            select(AndroidRelease.version_code).where(
                AndroidRelease.channel == "direct",
                AndroidRelease.status == "active",
            )
        )
    ).scalar_one_or_none()
    latest_supported = max(
        get_settings().android_latest_version_code,
        int(active_release_code) if active_release_code is not None else 0,
    )
    outdated_devices = int(
        (
            await session.execute(
                select(func.count(ClientInstallation.id)).where(
                    ClientInstallation.company_id == tenant.company_id,
                    ClientInstallation.version_code < latest_supported,
                )
            )
        ).scalar_one()
    )
    redis_status = await _redis_dependency_status()

    devices = SystemHealthDevices(
        total=int(total_devices),
        seen_last_24h=int(recent_devices),
        stale=int(stale_devices),
        with_pending_sync=int(pending_devices),
        sync_stalled=stalled_devices,
        max_pending_outbox_count=int(max_pending),
        latest_supported_version_code=latest_supported,
        outdated_installations=outdated_devices,
    )

    # The existing host monitor owns backup evidence outside the backend
    # container. Until a signed/read-only bridge is deployed, expose unknown
    # rather than inferring success from a configured timer.
    backup_status: BackupStatus = "unknown"
    recommendations = ["Connect verified backup monitoring to System Health."]
    if redis_status == "unavailable":
        recommendations.append("Server protection services need operator attention.")
    if diagnostics.counts_by_type["crash"] or diagnostics.counts_by_type["anr"]:
        recommendations.append("Review recent crash or unresponsive-app diagnostics.")
    if devices.sync_stalled:
        recommendations.append(
            "Recent explicit sync-stall reports need review on the affected devices."
        )
    if devices.outdated_installations:
        recommendations.append(
            "Update outdated app installations after verifying the signed release."
        )

    if redis_status == "unavailable" or diagnostics.critical_count or devices.sync_stalled:
        status: SystemHealthStatus = "action_required"
    elif (
        diagnostics.total
        or devices.with_pending_sync
        or devices.outdated_installations
        or backup_status == "unknown"
    ):
        status = "degraded"
    else:
        status = "healthy"

    return SystemHealthRead(
        status=status,
        server_time=server_time,
        retention_days=CLIENT_DIAGNOSTIC_RETENTION_DAYS,
        dependencies=SystemHealthDependencies(
            api="operational",
            database="operational",
            redis=redis_status,
        ),
        backups=SystemHealthBackups(
            status=backup_status,
            last_success_at=None,
            restore_tested_at=None,
            evidence_code="host_monitor_not_connected",
        ),
        devices=devices,
        diagnostics=diagnostics,
        recommendations=recommendations,
    )


__all__ = [
    "ClientDiagnosticBatchRead",
    "ClientDiagnosticBatchWrite",
    "ClientDiagnosticPage",
    "ClientDiagnosticRead",
    "ClientDiagnosticSummary",
    "ClientDiagnosticWrite",
    "SystemHealthRead",
    "router",
]
