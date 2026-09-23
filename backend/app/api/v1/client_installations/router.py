"""Bounded, authenticated runtime health for native ERP installations."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal, get_args
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import func, select, text, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.core.cleanup_replay_fence import require_canonical_gaming_cleanup_receipt
from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves dependency annotations
from app.core.errors import (
    BusinessRuleError,
    ClientTelemetryCapacityError,
    ClientTelemetryIdentityConflictError,
    ConflictError,
)
from app.core.middleware import idempotency_request_hash, parse_client_version_code
from app.core.permissions import requires
from app.core.tenant import (  # noqa: TC001 - FastAPI resolves dependency annotations
    TenantContext,
    TenantDep,
)
from app.models import (
    ClientGamingCleanupReconciliation,
    ClientInstallation,
    ClientUpdateEvent,
    Terminal,
    User,
)
from app.models.client_update import (
    CLIENT_DISTRIBUTION_CHANNELS,
    CLIENT_INSTALLATIONS_MAX_PER_COMPANY,
    CLIENT_INSTALLATIONS_MAX_PER_USER,
    CLIENT_UPDATE_ERROR_CODES,
    CLIENT_UPDATE_EVENT_TYPES,
    CLIENT_UPDATE_EVENTS_MAX_PER_COMPANY,
    CLIENT_UPDATE_EVENTS_MAX_PER_INSTALLATION,
    CLIENT_UPDATE_EVENTS_MAX_PER_USER,
    CLIENT_UPDATE_STATES,
)
from app.services.client_updates.rate_limit import enforce_client_heartbeat_rate_limit
from app.services.remote_assistance.consent import reconcile_remote_assistance_user_binding
from app.services.remote_assistance.device_auth import authenticate_device_request

router = APIRouter()

DistributionChannel = Literal["direct", "play", "managed"]
UpdateState = Literal[
    "idle",
    "update_available",
    "downloading",
    "verifying",
    "verified",
    "installer_opened",
    "failed",
]
UpdateEventType = Literal[
    "update_offered",
    "download_started",
    "download_verified",
    "installer_opened",
    "upgrade_confirmed",
    "update_cancelled",
    "update_failed",
]
UpdateErrorCode = Literal[
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
]

_VERSION_NAME_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._+-]{0,79}$")
_MAX_FUTURE_SKEW = timedelta(hours=24)
_CLIENT_TELEMETRY_LOCK_PREFIX = "dcompany-client-telemetry:"
MAX_GAMING_CLEANUP_REVISIONS_PER_ACTION = 32
MIN_GAMING_CLEANUP_VERSION_CODE = 38
MAX_GAMING_CLEANUP_EPOCH_MILLIS = 253_402_300_799_999
MAX_GAMING_CLEANUP_EVIDENCE_REVISION = 9_223_372_036_854_775_807

# Keep the API literals and database/model allowlists mechanically aligned.
assert set(get_args(DistributionChannel)) == set(CLIENT_DISTRIBUTION_CHANNELS)
assert set(get_args(UpdateState)) == set(CLIENT_UPDATE_STATES)
assert set(get_args(UpdateEventType)) == set(CLIENT_UPDATE_EVENT_TYPES)
assert set(get_args(UpdateErrorCode)) == set(CLIENT_UPDATE_ERROR_CODES)


def _aware_not_implausibly_future(value: datetime, *, field_name: str) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field_name} must include a timezone")
    normalized = value.astimezone(UTC)
    if normalized > datetime.now(UTC) + _MAX_FUTURE_SKEW:
        raise ValueError(f"{field_name} cannot be more than 24 hours in the future")
    return normalized


def _version_name(value: str) -> str:
    normalized = value.strip()
    if _VERSION_NAME_RE.fullmatch(normalized) is None:
        raise ValueError(
            "version name may contain only letters, digits, dot, underscore, plus, or hyphen"
        )
    return normalized


class ClientUpdateEventWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    client_event_id: UUID
    event_type: UpdateEventType
    target_version_name: str = Field(min_length=1, max_length=80)
    target_version_code: int = Field(ge=1, le=2_147_483_647)
    error_code: UpdateErrorCode | None = None
    occurred_at: datetime

    @field_validator("client_event_id")
    @classmethod
    def require_random_event_uuid(cls, value: UUID) -> UUID:
        if value.version != 4:
            raise ValueError("client event id must be a random UUID v4")
        return value

    @field_validator("target_version_name")
    @classmethod
    def normalize_version_name(cls, value: str) -> str:
        return _version_name(value)

    @field_validator("occurred_at")
    @classmethod
    def validate_occurred_at(cls, value: datetime) -> datetime:
        return _aware_not_implausibly_future(value, field_name="occurred at")

    @model_validator(mode="after")
    def validate_failure_evidence(self) -> ClientUpdateEventWrite:
        if self.event_type == "update_failed" and self.error_code is None:
            raise ValueError("update_failed events require an allowlisted error code")
        if self.event_type != "update_failed" and self.error_code is not None:
            raise ValueError("only update_failed events may include an error code")
        return self


class ClientInstallationHeartbeat(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    platform: Literal["android"]
    distribution_channel: DistributionChannel
    version_name: str = Field(min_length=1, max_length=80)
    version_code: int = Field(ge=1, le=2_147_483_647)
    pending_outbox_count: int = Field(ge=0, le=1_000_000)
    last_successful_sync_at: datetime | None = None
    update_state: UpdateState
    update_error_code: UpdateErrorCode | None = None
    events: list[ClientUpdateEventWrite] = Field(default_factory=list, max_length=20)

    @field_validator("installation_id")
    @classmethod
    def require_random_installation_uuid(cls, value: UUID) -> UUID:
        if value.version != 4:
            raise ValueError("installation id must be a random UUID v4")
        return value

    @field_validator("version_name")
    @classmethod
    def normalize_version_name(cls, value: str) -> str:
        return _version_name(value)

    @field_validator("last_successful_sync_at")
    @classmethod
    def validate_last_sync(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        return _aware_not_implausibly_future(value, field_name="last successful sync at")

    @model_validator(mode="after")
    def validate_evidence(self) -> ClientInstallationHeartbeat:
        if self.update_state == "failed" and self.update_error_code is None:
            raise ValueError("failed update state requires an allowlisted error code")
        if self.update_state != "failed" and self.update_error_code is not None:
            raise ValueError("only failed update state may include an error code")
        ids = [event.client_event_id for event in self.events]
        if len(ids) != len(set(ids)):
            raise ValueError("events must not repeat a client event id in one heartbeat")
        for event in self.events:
            if event.event_type != "upgrade_confirmed":
                continue
            if event.target_version_code > self.version_code:
                raise ValueError("upgrade_confirmed cannot be newer than the installed app version")
            if (
                event.target_version_code == self.version_code
                and event.target_version_name != self.version_name
            ):
                raise ValueError("upgrade_confirmed must match the installed app version name")
        return self


class ClientInstallationHeartbeatRead(BaseModel):
    installation_id: UUID
    terminal_id: UUID | None
    last_seen_at: datetime
    accepted_event_count: int
    duplicate_event_count: int


class ClientInstallationRead(BaseModel):
    installation_id: UUID
    platform: str
    distribution_channel: str
    version_name: str
    version_code: int
    pending_outbox_count: int
    last_successful_sync_at: datetime | None
    update_state: str
    update_error_code: str | None
    last_seen_at: datetime
    is_stale: bool
    last_user_id: UUID | None
    last_user_name: str | None
    terminal_id: UUID | None
    terminal_name: str | None


class ClientInstallationList(BaseModel):
    server_time: datetime
    stale_after_hours: int
    total: int
    items: list[ClientInstallationRead]


class GamingCleanupLocalSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    shift_id: UUID
    started_at_millis: int = Field(ge=1, le=MAX_GAMING_CLEANUP_EPOCH_MILLIS)
    ended_at_millis: int = Field(ge=1, le=MAX_GAMING_CLEANUP_EPOCH_MILLIS)
    timer_minutes: int | None = Field(default=None, ge=1, le=1440)
    timer_ends_at_millis: int | None = Field(
        default=None,
        ge=1,
        le=MAX_GAMING_CLEANUP_EPOCH_MILLIS,
    )
    billable_minutes: int | None = Field(default=None, ge=0)
    amount_minor: int | None = Field(default=None, ge=0, le=9_999_999_999)
    rate_per_hour_minor: int = Field(ge=0, le=9_999_999_999)
    customer_id: UUID | None = None
    customer_name: str | None = Field(default=None, max_length=200)
    customer_phone: str | None = Field(default=None, max_length=20)
    customer_directory_revision: int | None = Field(
        default=None,
        ge=0,
        le=MAX_GAMING_CLEANUP_EVIDENCE_REVISION,
    )
    customer_directory_company_id: UUID | None = None
    package_id: UUID | None = None
    billing_mode: Literal["hourly", "package", "legacy_ambiguous"] | None = None
    package_price_minor: int | None = Field(default=None, ge=0)
    package_duration_minutes: int | None = Field(default=None, ge=1, le=1440)
    package_variant: str | None = Field(default=None, max_length=20)
    package_station_type_snapshot: str | None = Field(default=None, max_length=20)
    package_pricing_tier_snapshot: str | None = Field(default=None, max_length=20)
    extra_controllers: int = Field(ge=0, le=8)
    player_count: int | None = Field(default=None, ge=1, le=10)
    evidence_revision: int = Field(ge=0, le=MAX_GAMING_CLEANUP_EVIDENCE_REVISION)

    @model_validator(mode="after")
    def validate_chronology_and_catalogue_evidence(self) -> GamingCleanupLocalSnapshot:
        if self.ended_at_millis < self.started_at_millis:
            raise ValueError("ended_at_millis must not precede started_at_millis")
        if (self.customer_directory_revision is None) != (
            self.customer_directory_company_id is None
        ):
            raise ValueError("customer directory revision and company must be captured together")
        package_evidence = (
            self.package_price_minor,
            self.package_duration_minutes,
            self.package_variant,
        )
        if self.package_id is None:
            if (
                any(value is not None for value in package_evidence)
                or self.player_count is not None
            ):
                raise ValueError("package evidence requires package_id")
        elif any(value is None for value in package_evidence):
            raise ValueError("package cleanup evidence must include price, duration, and variant")
        if self.package_variant == "single" and self.player_count != 1:
            raise ValueError("single package evidence must capture one player")
        if self.package_variant == "dual" and self.player_count != 2 + self.extra_controllers:
            raise ValueError("dual package evidence does not match controller count")
        return self


class GamingCleanupReportWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    local_action_id: UUID
    server_session_id: UUID
    branch_id: UUID
    terminal_id: UUID
    station_id: UUID
    reported_local_state: Literal[
        "stop_pending",
        "stop_rejected",
        "ended_unbilled",
        "send_pending",
        "send_rejected",
    ]
    local_snapshot: GamingCleanupLocalSnapshot
    local_snapshot_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    start_request_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    stop_request_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    candidate_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    unresolved_child_count: int = Field(ge=0, le=1_000_000)


class GamingCleanupApprovalWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    expected_candidate_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    reason: str = Field(min_length=3, max_length=500)

    @field_validator("reason")
    @classmethod
    def normalize_reason(cls, value: str) -> str:
        normalized = value.strip()
        if len(normalized) < 3:
            raise ValueError("reason must contain at least three non-space characters")
        return normalized


class GamingCleanupAcknowledgeWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    expected_candidate_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class GamingCleanupReviewProjection(BaseModel):
    """Minimal owner review facts; customer identity never leaves the signed ledger."""

    amount_minor: int | None
    billable_minutes: int | None
    started_at: datetime
    ended_at: datetime


class GamingCleanupReconciliationRead(BaseModel):
    id: UUID
    installation_id: UUID
    branch_id: UUID
    terminal_id: UUID
    station_id: UUID
    local_action_id: UUID
    server_session_id: UUID
    revision: int
    is_current: bool
    reported_local_state: str
    local_evidence_revision: int
    reported_app_version_name: str
    reported_app_version_code: int
    local_snapshot_sha256: str
    start_request_hash: str
    stop_request_hash: str
    original_action_user_id: UUID
    candidate_sha256: str
    unresolved_child_count: int
    cleanup_receipt_audit_id: int
    review: GamingCleanupReviewProjection
    status: str
    reported_at: datetime
    approved_at: datetime | None
    approved_by: UUID | None
    approval_reason: str | None
    applied_at: datetime | None
    applied_by: UUID | None
    superseded_at: datetime | None
    device_directive: Literal["wait_for_owner", "cleanup_retire", "already_applied", "superseded"]


class GamingCleanupReconciliationList(BaseModel):
    items: list[GamingCleanupReconciliationRead]


def gaming_cleanup_snapshot_sha256(snapshot: GamingCleanupLocalSnapshot) -> str:
    canonical = json.dumps(
        snapshot.model_dump(mode="json", exclude_none=True),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _java_instant(epoch_millis: int) -> str:
    seconds, millis = divmod(epoch_millis, 1000)
    base = datetime.fromtimestamp(seconds, tz=UTC).strftime("%Y-%m-%dT%H:%M:%S")
    return f"{base}.{millis:03d}Z" if millis else f"{base}Z"


def _android_start_body(payload: GamingCleanupReportWrite) -> bytes:
    snapshot = payload.local_snapshot
    body: dict[str, object] = {
        "station_id": str(payload.station_id),
        "shift_id": str(snapshot.shift_id),
        "started_at": _java_instant(snapshot.started_at_millis),
    }
    optional = (
        ("customer_id", snapshot.customer_id),
        ("customer_name", snapshot.customer_name),
        ("customer_phone", snapshot.customer_phone),
        ("customer_directory_revision", snapshot.customer_directory_revision),
        ("customer_directory_company_id", snapshot.customer_directory_company_id),
        ("timer_minutes", snapshot.timer_minutes),
        ("package_id", snapshot.package_id),
    )
    for key, value in optional:
        if value is not None:
            body[key] = str(value) if isinstance(value, UUID) else value
    if snapshot.extra_controllers != 0:
        body["extra_controllers"] = snapshot.extra_controllers
    if snapshot.player_count is not None:
        body["player_count"] = snapshot.player_count
    body["expected_rate_per_hour_minor"] = snapshot.rate_per_hour_minor
    package_optional = (
        ("expected_package_price_minor", snapshot.package_price_minor),
        ("expected_package_duration_minutes", snapshot.package_duration_minutes),
        ("expected_package_variant", snapshot.package_variant),
    )
    for key, value in package_optional:
        if value is not None:
            body[key] = value
    return json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def gaming_cleanup_action_hashes(payload: GamingCleanupReportWrite) -> tuple[str, str]:
    key_prefix = str(payload.local_action_id)
    start = idempotency_request_hash(
        method="POST",
        path="/api/v1/gaming/sessions/start",
        query="",
        key=f"gaming-session-start:{key_prefix}",
        body=_android_start_body(payload),
    )
    stop_body = json.dumps(
        {"ended_at": _java_instant(payload.local_snapshot.ended_at_millis)},
        separators=(",", ":"),
    ).encode("utf-8")
    stop = idempotency_request_hash(
        method="POST",
        path=f"/api/v1/gaming/sessions/{payload.server_session_id}/stop",
        query="",
        key=f"gaming-session-stop:{key_prefix}",
        body=stop_body,
    )
    return start, stop


def gaming_cleanup_candidate_sha256(payload: GamingCleanupReportWrite) -> str:
    canonical = "\n".join(
        (
            "client-gaming-cleanup-v2",
            str(payload.installation_id),
            str(payload.local_action_id),
            str(payload.server_session_id),
            str(payload.branch_id),
            str(payload.terminal_id),
            str(payload.station_id),
            payload.reported_local_state,
            str(payload.local_snapshot.evidence_revision),
            payload.local_snapshot_sha256,
            payload.start_request_hash,
            payload.stop_request_hash,
            str(payload.unresolved_child_count),
        )
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _millis_datetime(value: int) -> datetime:
    seconds, millis = divmod(value, 1000)
    return datetime.fromtimestamp(seconds, tz=UTC).replace(microsecond=millis * 1000)


def _cleanup_read(
    row: ClientGamingCleanupReconciliation,
    installation_id: UUID,
) -> GamingCleanupReconciliationRead:
    snapshot = GamingCleanupLocalSnapshot.model_validate(row.local_snapshot)
    directive: Literal["wait_for_owner", "cleanup_retire", "already_applied", "superseded"]
    if row.status == "approved":
        directive = "cleanup_retire"
    elif row.status == "applied":
        directive = "already_applied"
    elif row.status == "superseded":
        directive = "superseded"
    else:
        directive = "wait_for_owner"
    return GamingCleanupReconciliationRead(
        id=row.id,
        installation_id=installation_id,
        branch_id=row.branch_id,
        terminal_id=row.terminal_id,
        station_id=row.station_id,
        local_action_id=row.local_action_id,
        server_session_id=row.server_session_id,
        revision=row.revision,
        is_current=row.status != "superseded",
        reported_local_state=row.reported_local_state,
        local_evidence_revision=int(row.local_evidence_revision),
        reported_app_version_name=row.reported_app_version_name,
        reported_app_version_code=int(row.reported_app_version_code),
        local_snapshot_sha256=row.local_snapshot_sha256,
        start_request_hash=row.start_request_hash,
        stop_request_hash=row.stop_request_hash,
        original_action_user_id=row.original_action_user_id,
        candidate_sha256=row.candidate_sha256,
        unresolved_child_count=int(row.unresolved_child_count),
        cleanup_receipt_audit_id=int(row.cleanup_receipt_audit_id),
        review=GamingCleanupReviewProjection(
            amount_minor=snapshot.amount_minor,
            billable_minutes=snapshot.billable_minutes,
            started_at=_millis_datetime(snapshot.started_at_millis),
            ended_at=_millis_datetime(snapshot.ended_at_millis),
        ),
        status=row.status,
        reported_at=row.reported_at,
        approved_at=row.approved_at,
        approved_by=row.approved_by,
        approval_reason=row.approval_reason,
        applied_at=row.applied_at,
        applied_by=row.applied_by,
        superseded_at=row.superseded_at,
        device_directive=directive,
    )


def _require_cleanup_scope(tenant: TenantContext) -> tuple[UUID, UUID]:
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the tablet's exact branch and terminal before reviewing Gaming cleanup."
        )
    return tenant.branch_id, tenant.terminal_id


async def _cleanup_installation(
    session: SessionDep,
    *,
    company_id: UUID,
    installation_id: UUID,
    terminal_id: UUID,
    lock: bool = False,
) -> ClientInstallation:
    query = select(ClientInstallation).where(
        ClientInstallation.company_id == company_id,
        ClientInstallation.installation_id == installation_id,
        ClientInstallation.terminal_id == terminal_id,
    )
    if lock:
        query = query.with_for_update()
    row = (await session.execute(query)).scalar_one_or_none()
    if row is None:
        raise BusinessRuleError(
            "This installation is not currently bound to the authenticated terminal."
        )
    return row


async def _authenticate_cleanup_device(
    request: Request,
    session: SessionDep,
    *,
    tenant: TenantContext,
    installation: ClientInstallation,
    installation_id: UUID,
) -> None:
    try:
        header_installation_id = UUID(request.headers.get("X-Installation-Id", ""))
    except ValueError as exc:
        raise BusinessRuleError("A canonical X-Installation-Id is required.") from exc
    version_code = parse_client_version_code(request.headers.get("X-Client-Version-Code"))
    if (
        header_installation_id != installation_id
        or request.headers.get("X-Installation-Id") != str(installation_id)
        or request.headers.get("X-Client-Platform", "").strip().lower() != "android"
        or version_code is None
        or version_code < MIN_GAMING_CLEANUP_VERSION_CODE
        or installation.version_code < MIN_GAMING_CLEANUP_VERSION_CODE
        or version_code != installation.version_code
        or installation.platform != "android"
        or installation.last_user_id != tenant.user_id
    ):
        raise BusinessRuleError(
            "Cleanup recovery requires the current authenticated Android installation "
            "on build 38 or later with matching device status."
        )
    await authenticate_device_request(
        request=request,
        session=session,
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        actual_content=await request.body(),
    )


def _capacity_error(*, resource: str, scope: str, limit: int) -> ClientTelemetryCapacityError:
    return ClientTelemetryCapacityError(
        "This shop's retained device-update evidence has reached its safety limit. "
        "Existing records were preserved; ask the protected owner to review support status.",
        details={"resource": resource, "scope": scope, "limit": limit},
    )


def _validate_client_identity_headers(
    request: Request,
    payload: ClientInstallationHeartbeat,
) -> None:
    """Fail closed when authenticated telemetry contradicts native identity headers.

    This is consistency evidence, not device attestation: a modified client can
    forge both values. It still prevents ordinary client/server drift from
    corrupting owner-facing installation health.
    """

    expected: dict[str, object] = {
        "platform": payload.platform,
        "version_code": payload.version_code,
        "distribution_channel": payload.distribution_channel,
    }
    observed: dict[str, object] = {
        "platform": request.headers.get("X-Client-Platform", "").strip().lower(),
        "version_code": parse_client_version_code(request.headers.get("X-Client-Version-Code")),
        "distribution_channel": (
            request.headers.get("X-Client-Distribution-Channel", "direct").strip().lower()
            or "direct"
        ),
    }
    mismatched = sorted(field for field, value in expected.items() if observed[field] != value)
    if mismatched:
        raise ClientTelemetryIdentityConflictError(
            "Device status did not match this app build. Restart the app and try again; "
            "if this repeats, open Help and report the issue.",
            details={"mismatched_fields": mismatched},
        )


async def _lock_company_telemetry(session: SessionDep, company_id: UUID) -> None:
    """Serialize all row-admission decisions for one company transaction."""

    await session.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))"),
        {"scope": f"{_CLIENT_TELEMETRY_LOCK_PREFIX}{company_id}"},
    )


async def _enforce_new_installation_capacity(
    session: SessionDep,
    *,
    company_id: UUID,
    user_id: UUID,
) -> None:
    company_count = int(
        (
            await session.execute(
                select(func.count(ClientInstallation.id)).where(
                    ClientInstallation.company_id == company_id
                )
            )
        ).scalar_one()
    )
    if company_count >= CLIENT_INSTALLATIONS_MAX_PER_COMPANY:
        raise _capacity_error(
            resource="installations",
            scope="company",
            limit=CLIENT_INSTALLATIONS_MAX_PER_COMPANY,
        )
    user_count = int(
        (
            await session.execute(
                select(func.count(ClientInstallation.id)).where(
                    ClientInstallation.company_id == company_id,
                    ClientInstallation.registered_by_user_id == user_id,
                )
            )
        ).scalar_one()
    )
    if user_count >= CLIENT_INSTALLATIONS_MAX_PER_USER:
        raise _capacity_error(
            resource="installations",
            scope="user",
            limit=CLIENT_INSTALLATIONS_MAX_PER_USER,
        )


async def _enforce_new_event_capacity(
    session: SessionDep,
    *,
    company_id: UUID,
    user_id: UUID,
    installation_row_id: UUID,
    new_event_count: int,
) -> None:
    checks = (
        (
            "installation",
            CLIENT_UPDATE_EVENTS_MAX_PER_INSTALLATION,
            ClientUpdateEvent.client_installation_id == installation_row_id,
        ),
        (
            "user",
            CLIENT_UPDATE_EVENTS_MAX_PER_USER,
            ClientUpdateEvent.actor_user_id == user_id,
        ),
        (
            "company",
            CLIENT_UPDATE_EVENTS_MAX_PER_COMPANY,
            ClientUpdateEvent.company_id == company_id,
        ),
    )
    for scope, limit, predicate in checks:
        count = int(
            (
                await session.execute(
                    select(func.count(ClientUpdateEvent.id)).where(
                        ClientUpdateEvent.company_id == company_id,
                        predicate,
                    )
                )
            ).scalar_one()
        )
        if count + new_event_count > limit:
            raise _capacity_error(resource="events", scope=scope, limit=limit)


@router.post("/heartbeat", response_model=ClientInstallationHeartbeatRead)
async def heartbeat(
    request: Request,
    payload: ClientInstallationHeartbeat,
    session: SessionDep,
    tenant: TenantDep,
) -> ClientInstallationHeartbeatRead:
    """Upsert one authenticated installation snapshot and append new events.

    Scope comes exclusively from the validated tenant dependency.  There are
    deliberately no company, user, or terminal fields in the request DTO.
    """
    _validate_client_identity_headers(request, payload)
    await enforce_client_heartbeat_rate_limit(
        company_id=tenant.company_id,
        user_id=tenant.user_id,
    )
    await _lock_company_telemetry(session, tenant.company_id)

    existing_identity = (
        await session.execute(
            select(ClientInstallation.id, ClientInstallation.last_user_id).where(
                ClientInstallation.company_id == tenant.company_id,
                ClientInstallation.installation_id == payload.installation_id,
            )
        )
    ).one_or_none()
    if existing_identity is None:
        await _enforce_new_installation_capacity(
            session,
            company_id=tenant.company_id,
            user_id=tenant.user_id,
        )

    now = datetime.now(UTC)
    statement = (
        pg_insert(ClientInstallation)
        .values(
            id=uuid4(),
            company_id=tenant.company_id,
            installation_id=payload.installation_id,
            registered_by_user_id=tenant.user_id,
            last_user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
            platform=payload.platform,
            distribution_channel=payload.distribution_channel,
            version_name=payload.version_name,
            version_code=payload.version_code,
            pending_outbox_count=payload.pending_outbox_count,
            last_successful_sync_at=payload.last_successful_sync_at,
            update_state=payload.update_state,
            update_error_code=payload.update_error_code,
            last_seen_at=now,
            created_at=now,
            updated_at=now,
        )
        .on_conflict_do_update(
            constraint="uq_client_installations_company_installation",
            set_={
                "last_user_id": tenant.user_id,
                "terminal_id": tenant.terminal_id,
                "platform": payload.platform,
                "distribution_channel": payload.distribution_channel,
                "version_name": payload.version_name,
                "version_code": payload.version_code,
                "pending_outbox_count": payload.pending_outbox_count,
                "last_successful_sync_at": payload.last_successful_sync_at,
                "update_state": payload.update_state,
                "update_error_code": payload.update_error_code,
                "last_seen_at": now,
                "updated_at": now,
            },
        )
        .returning(ClientInstallation.id)
    )
    installation_row_id = (await session.execute(statement)).scalar_one()
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(ClientInstallation.id == installation_row_id)
            .execution_options(populate_existing=True)
            .with_for_update()
        )
    ).scalar_one()
    user_changed = (
        existing_identity is not None and existing_identity.last_user_id != tenant.user_id
    )
    if user_changed:
        # A heartbeat from the newly authenticated tablet user is the
        # serialized identity handoff point. Prior remote-presence evidence and
        # consent cannot carry across that user boundary.
        installation.remote_support_protocol_version = None
        installation.remote_support_capability = None
        installation.remote_support_last_seen_at = None
        await reconcile_remote_assistance_user_binding(
            session,
            installation=installation,
            current_user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
            now=now,
        )

    accepted_event_ids: set[UUID] = set()
    duplicate_event_ids: set[UUID] = set()
    if payload.events:
        submitted_by_id = {item.client_event_id: item for item in payload.events}
        existing_events = (
            (
                await session.execute(
                    select(ClientUpdateEvent).where(
                        ClientUpdateEvent.company_id == tenant.company_id,
                        ClientUpdateEvent.client_installation_id == installation_row_id,
                        ClientUpdateEvent.client_event_id.in_(submitted_by_id),
                    )
                )
            )
            .scalars()
            .all()
        )
        existing_by_id = {item.client_event_id: item for item in existing_events}
        duplicate_event_ids = set(existing_by_id)
        for event_id, existing in existing_by_id.items():
            submitted = submitted_by_id[event_id]
            if (
                existing.event_type != submitted.event_type
                or existing.target_version_name != submitted.target_version_name
                or existing.target_version_code != submitted.target_version_code
                or existing.error_code != submitted.error_code
                or existing.occurred_at.astimezone(UTC) != submitted.occurred_at
            ):
                raise ConflictError(
                    "A client event id was already used for different update evidence."
                )

        new_events = [
            item for item in payload.events if item.client_event_id not in duplicate_event_ids
        ]
        if new_events:
            await _enforce_new_event_capacity(
                session,
                company_id=tenant.company_id,
                user_id=tenant.user_id,
                installation_row_id=installation_row_id,
                new_event_count=len(new_events),
            )
            event_statement = (
                pg_insert(ClientUpdateEvent)
                .values(
                    [
                        {
                            "id": uuid4(),
                            "company_id": tenant.company_id,
                            "client_installation_id": installation_row_id,
                            "client_event_id": item.client_event_id,
                            "actor_user_id": tenant.user_id,
                            "terminal_id": tenant.terminal_id,
                            "event_type": item.event_type,
                            "target_version_name": item.target_version_name,
                            "target_version_code": item.target_version_code,
                            "error_code": item.error_code,
                            "occurred_at": item.occurred_at,
                            "received_at": now,
                        }
                        for item in new_events
                    ]
                )
                .on_conflict_do_nothing(
                    constraint="uq_client_update_events_installation_client_event"
                )
                .returning(ClientUpdateEvent.client_event_id)
            )
            accepted_event_ids = set((await session.execute(event_statement)).scalars().all())
            expected_ids = {item.client_event_id for item in new_events}
            if accepted_event_ids != expected_ids:
                raise ConflictError(
                    "Update evidence changed concurrently. Retry with the same event ids."
                )

    if tenant.terminal_id is not None:
        terminal_scope = [Terminal.id == tenant.terminal_id]
        if tenant.branch_id is not None:
            terminal_scope.append(Terminal.branch_id == tenant.branch_id)
        await session.execute(update(Terminal).where(*terminal_scope).values(last_seen_at=now))

    return ClientInstallationHeartbeatRead(
        installation_id=payload.installation_id,
        terminal_id=tenant.terminal_id,
        last_seen_at=now,
        accepted_event_count=len(accepted_event_ids),
        duplicate_event_count=len(payload.events) - len(accepted_event_ids),
    )


@router.post(
    "/gaming-cleanup-reconciliations/report",
    response_model=GamingCleanupReconciliationRead,
)
async def report_gaming_cleanup_reconciliation(
    request: Request,
    payload: GamingCleanupReportWrite,
    session: SessionDep,
    tenant: Annotated[TenantContext, Depends(requires("gaming.write"))],
) -> GamingCleanupReconciliationRead:
    """Record one exact tablet candidate without authorising local deletion."""
    branch_id, terminal_id = _require_cleanup_scope(tenant)
    if payload.branch_id != branch_id or payload.terminal_id != terminal_id:
        raise BusinessRuleError(
            "The cleanup candidate does not match the current branch and terminal."
        )
    snapshot_hash = gaming_cleanup_snapshot_sha256(payload.local_snapshot)
    start_hash, stop_hash = gaming_cleanup_action_hashes(payload)
    expected_hash = gaming_cleanup_candidate_sha256(payload)
    if (
        payload.local_snapshot_sha256 != snapshot_hash
        or payload.start_request_hash != start_hash
        or payload.stop_request_hash != stop_hash
        or payload.candidate_sha256 != expected_hash
    ):
        raise ConflictError(
            "The cleanup candidate hash does not match its immutable local evidence."
        )
    installation = await _cleanup_installation(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        terminal_id=terminal_id,
        lock=True,
    )
    await _authenticate_cleanup_device(
        request,
        session,
        tenant=tenant,
        installation=installation,
        installation_id=payload.installation_id,
    )
    receipt = await require_canonical_gaming_cleanup_receipt(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        terminal_id=terminal_id,
        station_id=payload.station_id,
        local_action_id=payload.local_action_id,
        server_session_id=payload.server_session_id,
        start_request_hash=payload.start_request_hash,
        stop_request_hash=payload.stop_request_hash,
    )
    existing = (
        await session.execute(
            select(ClientGamingCleanupReconciliation)
            .where(
                ClientGamingCleanupReconciliation.company_id == tenant.company_id,
                ClientGamingCleanupReconciliation.client_installation_id == installation.id,
                ClientGamingCleanupReconciliation.local_action_id == payload.local_action_id,
                ClientGamingCleanupReconciliation.status != "superseded",
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    immutable = (
        payload.server_session_id,
        payload.branch_id,
        payload.terminal_id,
        payload.station_id,
        payload.reported_local_state,
        payload.local_snapshot.evidence_revision,
        payload.local_snapshot_sha256,
        payload.start_request_hash,
        payload.stop_request_hash,
        payload.candidate_sha256,
        payload.unresolved_child_count,
        receipt.audit_id,
    )
    if existing is not None:
        persisted = (
            existing.server_session_id,
            existing.branch_id,
            existing.terminal_id,
            existing.station_id,
            existing.reported_local_state,
            int(existing.local_evidence_revision),
            existing.local_snapshot_sha256,
            existing.start_request_hash,
            existing.stop_request_hash,
            existing.candidate_sha256,
            int(existing.unresolved_child_count),
            int(existing.cleanup_receipt_audit_id),
        )
        if persisted == immutable:
            return _cleanup_read(existing, installation.installation_id)
        if existing.status not in {"reported", "approved"}:
            raise ConflictError(
                "An applied cleanup revision cannot be replaced. Refresh the tablet."
            )
        if payload.local_snapshot.evidence_revision <= existing.local_evidence_revision:
            raise ConflictError(
                "Changed cleanup evidence must advance the tablet evidence revision."
            )
        if existing.revision >= MAX_GAMING_CLEANUP_REVISIONS_PER_ACTION:
            raise BusinessRuleError(
                "This tablet action reached the cleanup review revision limit. "
                "Its current evidence was preserved for support review."
            )
        existing.status = "superseded"
        existing.superseded_at = datetime.now(UTC)
        next_revision = existing.revision + 1
        await session.flush()
    else:
        last_revision = (
            await session.execute(
                select(func.max(ClientGamingCleanupReconciliation.revision)).where(
                    ClientGamingCleanupReconciliation.company_id == tenant.company_id,
                    ClientGamingCleanupReconciliation.client_installation_id == installation.id,
                    ClientGamingCleanupReconciliation.local_action_id == payload.local_action_id,
                )
            )
        ).scalar_one_or_none()
        next_revision = int(last_revision or 0) + 1
        if next_revision > MAX_GAMING_CLEANUP_REVISIONS_PER_ACTION:
            raise BusinessRuleError(
                "This tablet action reached the cleanup review revision limit. "
                "Its current evidence was preserved for support review."
            )

    duplicate_session = (
        await session.execute(
            select(ClientGamingCleanupReconciliation.id).where(
                ClientGamingCleanupReconciliation.company_id == tenant.company_id,
                ClientGamingCleanupReconciliation.client_installation_id == installation.id,
                ClientGamingCleanupReconciliation.server_session_id == payload.server_session_id,
                ClientGamingCleanupReconciliation.status != "superseded",
                ClientGamingCleanupReconciliation.local_action_id != payload.local_action_id,
            )
        )
    ).scalar_one_or_none()
    if duplicate_session is not None:
        raise ConflictError("This deleted server session is already bound to another local action.")
    now = datetime.now(UTC)
    row = ClientGamingCleanupReconciliation(
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        branch_id=branch_id,
        terminal_id=terminal_id,
        station_id=payload.station_id,
        local_action_id=payload.local_action_id,
        server_session_id=payload.server_session_id,
        revision=next_revision,
        reported_local_state=payload.reported_local_state,
        local_evidence_revision=payload.local_snapshot.evidence_revision,
        reported_app_version_name=installation.version_name,
        reported_app_version_code=installation.version_code,
        local_snapshot=payload.local_snapshot.model_dump(mode="json"),
        local_snapshot_sha256=payload.local_snapshot_sha256,
        start_request_hash=payload.start_request_hash,
        stop_request_hash=payload.stop_request_hash,
        original_action_user_id=receipt.original_action_user_id,
        candidate_sha256=payload.candidate_sha256,
        unresolved_child_count=payload.unresolved_child_count,
        cleanup_receipt_audit_id=receipt.audit_id,
        status="reported",
        reported_at=now,
    )
    session.add(row)
    await session.flush()
    return _cleanup_read(row, installation.installation_id)


@router.get(
    "/gaming-cleanup-reconciliations",
    response_model=GamingCleanupReconciliationList,
)
async def list_gaming_cleanup_reconciliations(
    response: Response,
    session: SessionDep,
    tenant: Annotated[TenantContext, Depends(requires("admin.system"))],
) -> GamingCleanupReconciliationList:
    response.headers["Cache-Control"] = "private, no-store"
    branch_id, _terminal_id = _require_cleanup_scope(tenant)
    rows = (
        await session.execute(
            select(ClientGamingCleanupReconciliation, ClientInstallation.installation_id)
            .join(
                ClientInstallation,
                ClientInstallation.id == ClientGamingCleanupReconciliation.client_installation_id,
            )
            .where(
                ClientGamingCleanupReconciliation.company_id == tenant.company_id,
                ClientGamingCleanupReconciliation.branch_id == branch_id,
            )
            .order_by(ClientGamingCleanupReconciliation.reported_at.desc())
            .limit(200)
        )
    ).all()
    return GamingCleanupReconciliationList(
        items=[_cleanup_read(row, installation_id) for row, installation_id in rows]
    )


@router.post(
    "/gaming-cleanup-reconciliations/{reconciliation_id}/approve",
    response_model=GamingCleanupReconciliationRead,
)
async def approve_gaming_cleanup_reconciliation(
    reconciliation_id: UUID,
    payload: GamingCleanupApprovalWrite,
    session: SessionDep,
    tenant: Annotated[TenantContext, Depends(requires("admin.system"))],
    idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
) -> GamingCleanupReconciliationRead:
    branch_id, _terminal_id = _require_cleanup_scope(tenant)
    key = (idempotency_key or "").strip()
    if not key or len(key) > 200:
        raise BusinessRuleError("A bounded Idempotency-Key is required for cleanup approval.")
    result = (
        await session.execute(
            select(ClientGamingCleanupReconciliation, ClientInstallation.installation_id)
            .join(
                ClientInstallation,
                ClientInstallation.id == ClientGamingCleanupReconciliation.client_installation_id,
            )
            .where(
                ClientGamingCleanupReconciliation.id == reconciliation_id,
                ClientGamingCleanupReconciliation.company_id == tenant.company_id,
                ClientGamingCleanupReconciliation.branch_id == branch_id,
                ClientInstallation.company_id == tenant.company_id,
                ClientInstallation.terminal_id == ClientGamingCleanupReconciliation.terminal_id,
            )
            .with_for_update(of=ClientGamingCleanupReconciliation)
        )
    ).one_or_none()
    if result is None:
        raise BusinessRuleError("The cleanup candidate was not found in the current branch.")
    row, installation_id = result
    if row.candidate_sha256 != payload.expected_candidate_sha256:
        raise ConflictError("The cleanup candidate changed; refresh before approving it.")
    if row.status == "superseded":
        raise ConflictError("This cleanup revision was superseded. Refresh before approving it.")
    if row.status in {"approved", "applied"}:
        if row.approval_idempotency_key != key or row.approval_reason != payload.reason:
            raise ConflictError(
                "This cleanup candidate was already approved with different evidence."
            )
        return _cleanup_read(row, installation_id)
    snapshot = GamingCleanupLocalSnapshot.model_validate(row.local_snapshot)
    if snapshot.amount_minor is None or snapshot.billable_minutes is None:
        raise BusinessRuleError(
            "The tablet cleanup evidence is incomplete. "
            "Amount and billable duration are required before approval."
        )
    if row.unresolved_child_count != 0:
        raise BusinessRuleError(
            "Saved Gaming add-on or extension work must be resolved before approval."
        )
    receipt = await require_canonical_gaming_cleanup_receipt(
        session,
        company_id=tenant.company_id,
        branch_id=row.branch_id,
        terminal_id=row.terminal_id,
        station_id=row.station_id,
        local_action_id=row.local_action_id,
        server_session_id=row.server_session_id,
        start_request_hash=row.start_request_hash,
        stop_request_hash=row.stop_request_hash,
    )
    if receipt.original_action_user_id != row.original_action_user_id:
        raise ConflictError("The cleanup receipt actor evidence changed.")
    now = datetime.now(UTC)
    row.status = "approved"
    row.approved_at = now
    row.approved_by = tenant.user_id
    row.approval_reason = payload.reason
    row.approval_idempotency_key = key
    await session.flush()
    return _cleanup_read(row, installation_id)


@router.post(
    "/gaming-cleanup-reconciliations/{reconciliation_id}/acknowledge",
    response_model=GamingCleanupReconciliationRead,
)
async def acknowledge_gaming_cleanup_reconciliation(
    reconciliation_id: UUID,
    request: Request,
    payload: GamingCleanupAcknowledgeWrite,
    session: SessionDep,
    tenant: Annotated[TenantContext, Depends(requires("gaming.write"))],
) -> GamingCleanupReconciliationRead:
    branch_id, terminal_id = _require_cleanup_scope(tenant)
    installation = await _cleanup_installation(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        terminal_id=terminal_id,
        lock=True,
    )
    await _authenticate_cleanup_device(
        request,
        session,
        tenant=tenant,
        installation=installation,
        installation_id=payload.installation_id,
    )
    row = (
        await session.execute(
            select(ClientGamingCleanupReconciliation)
            .where(
                ClientGamingCleanupReconciliation.id == reconciliation_id,
                ClientGamingCleanupReconciliation.company_id == tenant.company_id,
                ClientGamingCleanupReconciliation.branch_id == branch_id,
                ClientGamingCleanupReconciliation.terminal_id == terminal_id,
                ClientGamingCleanupReconciliation.client_installation_id == installation.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if row is None:
        raise BusinessRuleError("The cleanup directive does not belong to this installation.")
    if row.candidate_sha256 != payload.expected_candidate_sha256:
        raise ConflictError("The applied cleanup evidence does not match the approved candidate.")
    if row.status == "applied":
        return _cleanup_read(row, installation.installation_id)
    if row.status != "approved":
        raise BusinessRuleError("The protected owner has not approved this cleanup candidate.")
    receipt = await require_canonical_gaming_cleanup_receipt(
        session,
        company_id=tenant.company_id,
        branch_id=row.branch_id,
        terminal_id=row.terminal_id,
        station_id=row.station_id,
        local_action_id=row.local_action_id,
        server_session_id=row.server_session_id,
        start_request_hash=row.start_request_hash,
        stop_request_hash=row.stop_request_hash,
    )
    if receipt.original_action_user_id != row.original_action_user_id:
        raise ConflictError("The cleanup receipt actor evidence changed.")
    row.status = "applied"
    row.applied_at = datetime.now(UTC)
    row.applied_by = tenant.user_id
    await session.flush()
    return _cleanup_read(row, installation.installation_id)


@router.get("", response_model=ClientInstallationList)
async def list_installations(
    response: Response,
    session: SessionDep,
    tenant: Annotated[TenantContext, Depends(requires("settings.manage"))],
    stale_after_hours: int = Query(default=24, ge=1, le=720),
    limit: int = Query(default=100, ge=1, le=200),
    offset: int = Query(default=0, ge=0, le=100_000),
) -> ClientInstallationList:
    """Owner-authorized installation health, scoped to the caller's company."""
    response.headers["Cache-Control"] = "private, no-store"
    server_time = datetime.now(UTC)
    rows = (
        await session.execute(
            select(ClientInstallation, User.name, Terminal.name)
            .outerjoin(User, User.id == ClientInstallation.last_user_id)
            .outerjoin(Terminal, Terminal.id == ClientInstallation.terminal_id)
            .where(ClientInstallation.company_id == tenant.company_id)
            .order_by(ClientInstallation.last_seen_at.desc(), ClientInstallation.id)
            .offset(offset)
            .limit(limit)
        )
    ).all()
    total = int(
        (
            await session.execute(
                select(func.count(ClientInstallation.id)).where(
                    ClientInstallation.company_id == tenant.company_id
                )
            )
        ).scalar_one()
    )
    stale_before = server_time - timedelta(hours=stale_after_hours)
    return ClientInstallationList(
        server_time=server_time,
        stale_after_hours=stale_after_hours,
        total=total,
        items=[
            ClientInstallationRead(
                installation_id=installation.installation_id,
                platform=installation.platform,
                distribution_channel=installation.distribution_channel,
                version_name=installation.version_name,
                version_code=installation.version_code,
                pending_outbox_count=installation.pending_outbox_count,
                last_successful_sync_at=installation.last_successful_sync_at,
                update_state=installation.update_state,
                update_error_code=installation.update_error_code,
                last_seen_at=installation.last_seen_at,
                is_stale=installation.last_seen_at < stale_before,
                last_user_id=installation.last_user_id,
                last_user_name=user_name,
                terminal_id=installation.terminal_id,
                terminal_name=terminal_name,
            )
            for installation, user_name, terminal_name in rows
        ],
    )
