"""First-party, consent-gated, ERP-only remote assistance.

The control plane deliberately exposes semantic commands rather than taps,
keystrokes, shell access, URLs, selectors, or arbitrary JSON.  A protected
owner can see only the latest short-lived JPEG after an authenticated Android
user accepted the grant and the session was started.  Redis is the sole frame
relay and is required before starting or controlling a session.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
from datetime import UTC, datetime, timedelta
from functools import partial
from math import ceil
from typing import TYPE_CHECKING, Annotated, Literal, cast, get_args
from uuid import UUID, uuid4

from anyio import CapacityLimiter, to_thread
from fastapi import APIRouter, Depends, Header, Query, Request, Response
from fastapi.responses import Response as BinaryResponse
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationInfo,
    field_validator,
    model_validator,
)
from sqlalchemy import func, select, text

from app.core.config import get_settings
from app.core.db import SessionDep  # noqa: TC001 - FastAPI dependency annotation
from app.core.errors import (
    AuthError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
    RemoteActionGoneError,
    RemoteFrameTimeoutError,
    RemotePairingCodeMismatchError,
    ValidationError,
)
from app.core.permissions import requires
from app.core.tenant import TenantContext, TenantDep
from app.models import (
    AuditLog,
    ClientInstallation,
    RemoteAssistanceCommand,
    RemoteAssistanceDeviceKey,
    RemoteAssistanceGrant,
    RemoteAssistanceSession,
    Terminal,
    User,
)
from app.models.remote_assistance import (
    REMOTE_ASSISTANCE_CAPABILITIES,
    REMOTE_ASSISTANCE_COMMAND_REJECTION_REASONS,
    REMOTE_ASSISTANCE_COMMAND_STATUSES,
    REMOTE_ASSISTANCE_COMMAND_TYPES,
    REMOTE_ASSISTANCE_DEVICE_KEY_STATUSES,
    REMOTE_ASSISTANCE_END_REASONS,
    REMOTE_ASSISTANCE_GRANT_KINDS,
    REMOTE_ASSISTANCE_GRANT_STATUSES,
    REMOTE_ASSISTANCE_NAVIGATION_MODULES,
    REMOTE_ASSISTANCE_SESSION_STATUSES,
)
from app.services.audit.recorder import actor_ctx
from app.services.client_updates.rate_limit import enforce_client_heartbeat_rate_limit
from app.services.remote_assistance.consent import reconcile_remote_assistance_user_binding
from app.services.remote_assistance.device_auth import (
    authenticate_device_request,
    authenticate_enrollment_request,
    pairing_code,
    parse_p256_spki,
    verify_actual_content,
)
from app.services.remote_assistance.relay import (
    admit_frame_upload,
    delete_latest_frame,
    ensure_relay_available,
    get_latest_frame,
    store_latest_frame,
    validate_and_sanitize_jpeg,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

router = APIRouter()

AdminTenantDep = Annotated[TenantContext, Depends(requires("admin.system"))]
GrantKind = Literal["one_time", "anytime"]
GrantStatus = Literal["requested", "active", "declined", "revoked", "expired", "consumed"]
SessionStatus = Literal["requested", "active", "ended", "expired"]
SharingCapability = Literal["available", "permission_required", "unsupported"]
CommandType = Literal["navigate", "refresh", "collect_diagnostics"]
DeviceKeyStatus = Literal["pending", "active", "revoked", "expired"]
NavigationModule = Literal["help"]
CommandStatus = Literal["pending", "acknowledged", "rejected"]
CommandOutcome = Literal["acknowledged", "rejected"]
CommandRejectionReason = Literal[
    "unsupported_command",
    "module_unavailable",
    "permission_denied",
    "not_in_foreground",
    "session_inactive",
    "execution_failed",
    "session_ended",
]
DeviceEndReason = Literal[
    "user_ended",
    "permission_revoked",
    "capture_stopped",
    "app_backgrounded",
]

assert set(get_args(GrantKind)) == set(REMOTE_ASSISTANCE_GRANT_KINDS)
assert set(get_args(GrantStatus)) == set(REMOTE_ASSISTANCE_GRANT_STATUSES)
assert set(get_args(SessionStatus)) == set(REMOTE_ASSISTANCE_SESSION_STATUSES)
assert set(get_args(SharingCapability)) == set(REMOTE_ASSISTANCE_CAPABILITIES)
assert set(get_args(CommandType)) == set(REMOTE_ASSISTANCE_COMMAND_TYPES)
assert set(get_args(DeviceKeyStatus)) == set(REMOTE_ASSISTANCE_DEVICE_KEY_STATUSES)
assert set(get_args(NavigationModule)) == set(REMOTE_ASSISTANCE_NAVIGATION_MODULES)
assert set(get_args(CommandStatus)) == set(REMOTE_ASSISTANCE_COMMAND_STATUSES)
assert set(get_args(CommandRejectionReason)) == set(REMOTE_ASSISTANCE_COMMAND_REJECTION_REASONS)
assert set(get_args(DeviceEndReason)) < set(REMOTE_ASSISTANCE_END_REASONS)

_MAX_COMMANDS_PER_SESSION = 100
_ONE_TIME_GRANT_MAX_SECONDS = 900
_FRAME_SEQUENCE_MAX = 9_223_372_036_854_775_807
_COMMAND_ISSUE_COOLDOWN_SECONDS = 2
_DIAGNOSTICS_COMMAND_COOLDOWN_SECONDS = 60
_FRAME_DECODER_LIMITER = CapacityLimiter(2)


def _uuid4(value: UUID, *, label: str) -> UUID:
    if value.version != 4:
        raise ValueError(f"{label} must be a random UUID v4")
    return value


class DeviceHeartbeatWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    protocol_version: int = Field(ge=1, le=10)
    sharing_capability: SharingCapability

    @field_validator("installation_id")
    @classmethod
    def validate_installation_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="installation_id")


class DeviceHeartbeatRead(BaseModel):
    installation_id: UUID
    server_time: datetime
    remote_support_last_seen_at: datetime
    protocol_version: int
    sharing_capability: SharingCapability


class DeviceKeyEnrollmentWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    key_id: UUID
    enrollment_id: UUID
    installation_id: UUID
    public_key_spki: str = Field(min_length=100, max_length=256)
    signed_at_epoch_seconds: int = Field(ge=0, le=9_223_372_036_854_775_807)
    nonce: UUID
    signature: str = Field(min_length=80, max_length=112)

    @field_validator("key_id", "enrollment_id", "installation_id", "nonce")
    @classmethod
    def validate_random_ids(cls, value: UUID, info: ValidationInfo) -> UUID:
        return _uuid4(value, label=info.field_name or "identifier")


class DeviceKeyApproveWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    approval_id: UUID
    pairing_code: str = Field(
        min_length=12,
        max_length=12,
        pattern=r"^[0-9A-HJKMNP-TV-Z]{12}$",
    )

    @field_validator("pairing_code", mode="before")
    @classmethod
    def canonicalize_pairing_code(cls, value: object) -> object:
        if isinstance(value, str):
            return value.replace(" ", "").replace("-", "").upper()
        return value

    @field_validator("approval_id")
    @classmethod
    def validate_approval_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="approval_id")


class DeviceKeyRevokeWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    revocation_id: UUID

    @field_validator("revocation_id")
    @classmethod
    def validate_revocation_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="revocation_id")


class DeviceKeyAdminRead(BaseModel):
    key_id: UUID
    installation_id: UUID
    status: DeviceKeyStatus
    fingerprint_sha256: str | None
    enrolled_by_user_id: UUID
    enrolled_by_name: str | None
    enrolled_at: datetime
    pending_expires_at: datetime
    approved_by_user_id: UUID | None
    approved_by_name: str | None
    approved_at: datetime | None
    revoked_by_user_id: UUID | None
    revoked_by_name: str | None
    revoked_at: datetime | None


class DeviceKeyStatusRead(BaseModel):
    server_time: datetime
    key_id: UUID
    installation_id: UUID
    status: DeviceKeyStatus
    fingerprint_sha256: str
    enrolled_at: datetime
    pending_expires_at: datetime
    approved_at: datetime | None
    revoked_at: datetime | None
    pairing_code: str | None


class RemoteRequestCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: UUID
    installation_id: UUID
    grant_kind: GrantKind
    grant_ttl_seconds: int = Field(ge=60, le=24 * 60 * 60)
    session_ttl_seconds: int = Field(ge=60, le=900)

    @field_validator("request_id", "installation_id")
    @classmethod
    def validate_random_ids(cls, value: UUID, info: ValidationInfo) -> UUID:
        return _uuid4(value, label=info.field_name or "identifier")

    @model_validator(mode="after")
    def validate_grant_ttl(self) -> RemoteRequestCreate:
        if self.grant_kind == "one_time" and self.grant_ttl_seconds > _ONE_TIME_GRANT_MAX_SECONDS:
            raise ValueError("one_time grant_ttl_seconds must be at most 900")
        return self


class SessionCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    session_id: UUID
    installation_id: UUID
    grant_id: UUID
    session_ttl_seconds: int = Field(ge=60, le=900)

    @field_validator("session_id", "installation_id", "grant_id")
    @classmethod
    def validate_random_ids(cls, value: UUID, info: ValidationInfo) -> UUID:
        return _uuid4(value, label=info.field_name or "identifier")


class SessionStart(BaseModel):
    model_config = ConfigDict(extra="forbid")

    start_id: UUID

    @field_validator("start_id")
    @classmethod
    def validate_start_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="start_id")


class OwnerEndWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    end_id: UUID

    @field_validator("end_id")
    @classmethod
    def validate_end_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="end_id")


class OwnerGrantRevokeWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    revoke_id: UUID

    @field_validator("revoke_id")
    @classmethod
    def validate_revoke_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="revoke_id")


class GrantDecisionWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    decision: Literal["accepted", "declined"]
    decision_id: UUID

    @field_validator("installation_id", "decision_id")
    @classmethod
    def validate_random_ids(cls, value: UUID, info: ValidationInfo) -> UUID:
        return _uuid4(value, label=info.field_name or "identifier")


class DeviceGrantRevokeWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    revocation_id: UUID

    @field_validator("installation_id", "revocation_id")
    @classmethod
    def validate_random_ids(cls, value: UUID, info: ValidationInfo) -> UUID:
        return _uuid4(value, label=info.field_name or "identifier")


class DeviceSessionEndWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    end_id: UUID
    reason: DeviceEndReason

    @field_validator("installation_id", "end_id")
    @classmethod
    def validate_random_ids(cls, value: UUID, info: ValidationInfo) -> UUID:
        return _uuid4(value, label=info.field_name or "identifier")


class CommandCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    command_id: UUID
    sequence: int = Field(ge=1, le=_MAX_COMMANDS_PER_SESSION)
    type: CommandType
    module: NavigationModule | None = None

    @field_validator("command_id")
    @classmethod
    def validate_command_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="command_id")

    @model_validator(mode="after")
    def validate_closed_payload(self) -> CommandCreate:
        if self.type == "navigate" and self.module is None:
            raise ValueError("navigate requires an allowlisted module")
        if self.type != "navigate" and self.module is not None:
            raise ValueError("module is valid only for navigate")
        return self


class CommandResultWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installation_id: UUID
    sequence: int = Field(ge=1, le=_MAX_COMMANDS_PER_SESSION)
    outcome: CommandOutcome
    reason_code: CommandRejectionReason | None = None

    @field_validator("installation_id")
    @classmethod
    def validate_installation_id(cls, value: UUID) -> UUID:
        return _uuid4(value, label="installation_id")

    @model_validator(mode="after")
    def validate_rejection_evidence(self) -> CommandResultWrite:
        if self.outcome == "rejected" and self.reason_code is None:
            raise ValueError("rejected commands require reason_code")
        if self.outcome == "acknowledged" and self.reason_code is not None:
            raise ValueError("acknowledged commands must not include reason_code")
        return self


class GrantRead(BaseModel):
    id: UUID
    installation_id: UUID
    kind: GrantKind
    status: GrantStatus
    requested_by_user_id: UUID
    requested_by_name: str | None
    requested_for_user_id: UUID
    requested_for_name: str | None
    responded_by_user_id: UUID | None
    responded_by_name: str | None
    requested_at: datetime
    expires_at: datetime
    responded_at: datetime | None
    revoked_at: datetime | None
    consumed_at: datetime | None


class SessionRead(BaseModel):
    id: UUID
    installation_id: UUID
    grant_id: UUID
    status: SessionStatus
    duration_seconds: int
    requested_by_user_id: UUID
    requested_by_name: str | None
    started_by_user_id: UUID | None
    started_by_name: str | None
    ended_by_user_id: UUID | None
    ended_by_name: str | None
    requested_at: datetime
    request_expires_at: datetime
    started_at: datetime | None
    expires_at: datetime | None
    ended_at: datetime | None
    end_reason: str | None
    next_sequence: int


class CommandRead(BaseModel):
    command_id: UUID
    session_id: UUID
    sequence: int
    type: CommandType
    module: NavigationModule | None
    status: CommandStatus
    issued_by_user_id: UUID
    issued_at: datetime
    resolved_by_user_id: UUID | None
    resolved_at: datetime | None
    rejection_reason_code: CommandRejectionReason | None


class RemoteRequestRead(BaseModel):
    grant: GrantRead
    session: SessionRead


class DeviceRead(BaseModel):
    installation_id: UUID
    terminal_id: UUID | None
    terminal_name: str | None
    version_name: str
    version_code: int
    last_user_id: UUID | None
    last_user_name: str | None
    last_seen_at: datetime
    remote_support_last_seen_at: datetime | None
    is_remote_online: bool
    protocol_version: int | None
    sharing_capability: SharingCapability | None
    device_key_id: UUID | None
    device_key_status: DeviceKeyStatus | None
    device_key_fingerprint_sha256: str | None
    device_key_approved_at: datetime | None
    pending_device_key_id: UUID | None
    pending_device_key_enrolled_by_user_id: UUID | None
    pending_device_key_enrolled_by_name: str | None
    pending_device_key_enrolled_at: datetime | None
    pending_device_key_expires_at: datetime | None
    pairing_required: bool
    grant_status: GrantStatus | None
    current_grant_id: UUID | None
    current_grant_kind: GrantKind | None
    current_grant_expires_at: datetime | None
    current_grant_responded_by_user_id: UUID | None
    current_grant_responded_by_name: str | None
    current_grant_responded_at: datetime | None
    session_status: SessionStatus | None
    current_session_id: UUID | None
    current_session_expires_at: datetime | None
    current_session_next_sequence: int | None


class DeviceList(BaseModel):
    server_time: datetime
    online_within_seconds: int
    total: int
    items: list[DeviceRead]


class SessionPage(BaseModel):
    total: int
    limit: int
    offset: int
    items: list[SessionRead]


class DeviceStateRead(BaseModel):
    server_time: datetime
    pending_grants: list[GrantRead]
    session: SessionRead | None
    commands: list[CommandRead]


class FrameAcceptedRead(BaseModel):
    frame_id: UUID
    sequence: int
    width: int
    height: int
    received_at: datetime
    expires_at: datetime


def _device_ref(company_id: UUID, installation_id: UUID) -> str:
    return hashlib.sha256(f"{company_id}:{installation_id}".encode("ascii")).hexdigest()[:20]


def _audit(
    session: AsyncSession,
    *,
    company_id: UUID,
    actor_user_id: UUID | None,
    action: str,
    entity_type: str,
    entity_id: UUID,
    device_ref: str,
    before_status: str | None = None,
    after_status: str | None = None,
    extra: dict[str, object] | None = None,
) -> None:
    before = {"status": before_status} if before_status is not None else None
    after: dict[str, object] = {"device_ref": device_ref}
    if after_status is not None:
        after["status"] = after_status
    if extra:
        after.update(extra)
    context = actor_ctx.get() or {}
    context_terminal_id = context.get("terminal_id")
    session.add(
        AuditLog(
            actor_user_id=actor_user_id,
            company_id=company_id,
            terminal_id=(context_terminal_id if isinstance(context_terminal_id, UUID) else None),
            action=action,
            entity_type=entity_type,
            entity_id=str(entity_id),
            before=before,
            after=after,
        )
    )


async def _lock_device(session: AsyncSession, company_id: UUID, device_id: UUID) -> None:
    await session.execute(
        text("select pg_advisory_xact_lock(hashtextextended(:scope, 0))"),
        {"scope": f"dcompany-remote-assistance:{company_id}:{device_id}"},
    )


async def _lock_device_key_action(
    session: AsyncSession,
    company_id: UUID,
    action_id: UUID,
) -> None:
    await session.execute(
        text("select pg_advisory_xact_lock(hashtextextended(:scope, 0))"),
        {"scope": f"dcompany-remote-assistance-key-action:{company_id}:{action_id}"},
    )


async def _installation_by_public_id(
    session: AsyncSession,
    *,
    company_id: UUID,
    installation_id: UUID,
    for_update: bool = False,
) -> ClientInstallation:
    statement = select(ClientInstallation).where(
        ClientInstallation.company_id == company_id,
        ClientInstallation.installation_id == installation_id,
        ClientInstallation.platform == "android",
    )
    if for_update:
        statement = statement.with_for_update()
    row = (await session.execute(statement)).scalar_one_or_none()
    if row is None:
        raise NotFoundError("Registered Android support device not found.")
    return row


def _require_device_actor(installation: ClientInstallation, tenant: TenantContext) -> None:
    if installation.last_user_id != tenant.user_id:
        raise ForbiddenError(
            "This support action must be performed by the authenticated user on this device."
        )
    if (
        installation.terminal_id is not None
        and tenant.terminal_id is not None
        and installation.terminal_id != tenant.terminal_id
    ):
        raise ForbiddenError("This support device is registered to a different terminal.")


def _require_device_mutation_actor(
    installation: ClientInstallation,
    tenant: TenantContext,
) -> None:
    """Return a terminal response for a signed action queued before user handoff."""

    if installation.last_user_id != tenant.user_id:
        raise RemoteActionGoneError(
            "This remote-support action belongs to a former tablet user."
        )
    _require_device_actor(installation, tenant)


def _require_android_request(request: Request) -> None:
    if request.headers.get("X-Client-Platform", "").strip().lower() != "android":
        raise ForbiddenError("Remote device endpoints are available only to the Android ERP app.")


def _require_device_online(installation: ClientInstallation, *, now: datetime) -> None:
    settings = get_settings()
    seen = installation.remote_support_last_seen_at
    if seen is None or seen < now - timedelta(
        seconds=settings.remote_assistance_device_online_seconds
    ):
        raise ConflictError(
            "The device does not have a recent authenticated remote-assistance heartbeat."
        )


def _require_device_ready(installation: ClientInstallation, *, now: datetime) -> None:
    _require_device_online(installation, now=now)
    if installation.remote_support_capability != "available":
        raise ConflictError(
            "The device is not currently ready for a visible remote-assistance session."
        )


async def _require_active_device_key(
    session: AsyncSession,
    *,
    installation: ClientInstallation,
) -> None:
    active = (
        await session.execute(
            select(RemoteAssistanceDeviceKey.id).where(
                RemoteAssistanceDeviceKey.company_id == installation.company_id,
                RemoteAssistanceDeviceKey.client_installation_id == installation.id,
                RemoteAssistanceDeviceKey.status == "active",
            )
        )
    ).scalar_one_or_none()
    if active is None:
        raise ConflictError("The tablet must complete physical device-key pairing first.")


async def _require_exact_active_device_key(
    session: AsyncSession,
    *,
    installation: ClientInstallation,
    key_id: UUID,
) -> RemoteAssistanceDeviceKey:
    row = (
        await session.execute(
            select(RemoteAssistanceDeviceKey)
            .where(
                RemoteAssistanceDeviceKey.company_id == installation.company_id,
                RemoteAssistanceDeviceKey.client_installation_id == installation.id,
                RemoteAssistanceDeviceKey.id == key_id,
                RemoteAssistanceDeviceKey.status == "active",
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if row is None:
        raise RemoteActionGoneError(
            "The device key used for this support action is no longer active."
        )
    return row


async def _locked_active_frame_session(
    session: AsyncSession,
    *,
    company_id: UUID,
    session_id: UUID,
    installation: ClientInstallation,
    now: datetime,
) -> tuple[RemoteAssistanceSession, RemoteAssistanceGrant] | None:
    """Lock and authorize the exact active session/consent user for a frame."""

    row = (
        await session.execute(
            select(RemoteAssistanceSession, RemoteAssistanceGrant)
            .join(
                RemoteAssistanceGrant,
                RemoteAssistanceGrant.id == RemoteAssistanceSession.grant_id,
            )
            .where(
                RemoteAssistanceSession.company_id == company_id,
                RemoteAssistanceSession.id == session_id,
                RemoteAssistanceSession.client_installation_id == installation.id,
                RemoteAssistanceSession.status == "active",
                RemoteAssistanceSession.expires_at > now,
                RemoteAssistanceGrant.company_id == company_id,
                RemoteAssistanceGrant.status.in_(("active", "consumed")),
                RemoteAssistanceGrant.requested_for_user_id == installation.last_user_id,
                RemoteAssistanceGrant.responded_by_user_id == installation.last_user_id,
            )
            .with_for_update(of=(RemoteAssistanceGrant, RemoteAssistanceSession))
        )
    ).one_or_none()
    if row is None:
        return None
    return row[0], row[1]


async def _reconcile_consent_user(
    session: AsyncSession,
    *,
    installation: ClientInstallation,
    now: datetime,
    device_actor: TenantContext | None = None,
) -> None:
    """Invalidate consent that belongs to a former user of this installation."""

    current_user_id = installation.last_user_id
    if current_user_id is None:
        return
    await reconcile_remote_assistance_user_binding(
        session,
        installation=installation,
        current_user_id=current_user_id,
        terminal_id=(
            device_actor.terminal_id
            if device_actor is not None
            else installation.terminal_id
        ),
        now=now,
    )


def _require_current_consent_user(
    grant: RemoteAssistanceGrant,
    installation: ClientInstallation,
    *,
    device_user_id: UUID | None = None,
) -> None:
    current_user_id = installation.last_user_id
    if (
        grant.responded_by_user_id is None
        or grant.responded_by_user_id != current_user_id
        or (device_user_id is not None and grant.responded_by_user_id != device_user_id)
    ):
        raise ConflictError(
            "Support consent belongs to a different tablet user and is no longer valid."
        )


def _command_cooldown_conflict(
    *,
    message: str,
    issued_at: datetime,
    now: datetime,
    cooldown_seconds: int,
    existing_command_id: UUID,
) -> ConflictError:
    remaining = cooldown_seconds - (now - issued_at).total_seconds()
    return ConflictError(
        message,
        details={
            "existing_command_id": str(existing_command_id),
            "retry_after_seconds": max(1, ceil(remaining)),
        },
        headers={"Retry-After": str(max(1, ceil(remaining)))},
    )


async def _names(session: AsyncSession, ids: set[UUID | None]) -> dict[UUID, str]:
    real_ids = {value for value in ids if value is not None}
    if not real_ids:
        return {}
    rows = (await session.execute(select(User.id, User.name).where(User.id.in_(real_ids)))).all()
    names: dict[UUID, str] = {}
    for user_id, name in rows:
        names[user_id] = name
    return names


def _key_ref(company_id: UUID, row: RemoteAssistanceDeviceKey) -> str:
    material = f"{company_id}:{row.id}:{row.public_key_fingerprint_sha256}"
    return hashlib.sha256(material.encode("ascii")).hexdigest()[:20]


async def _expire_pending_device_keys(
    session: AsyncSession,
    *,
    company_id: UUID,
    now: datetime,
    client_installation_id: UUID | None = None,
) -> None:
    predicates = [
        RemoteAssistanceDeviceKey.company_id == company_id,
        RemoteAssistanceDeviceKey.status == "pending",
        RemoteAssistanceDeviceKey.pending_expires_at <= now,
    ]
    if client_installation_id is not None:
        predicates.append(
            RemoteAssistanceDeviceKey.client_installation_id == client_installation_id
        )
    rows = (
        (
            await session.execute(
                select(RemoteAssistanceDeviceKey, ClientInstallation.installation_id)
                .join(
                    ClientInstallation,
                    ClientInstallation.id == RemoteAssistanceDeviceKey.client_installation_id,
                )
                .where(*predicates)
                # The join supplies the public id for audit only. Locking the
                # installation here would invert the installation -> key order.
                .with_for_update(of=RemoteAssistanceDeviceKey)
            )
        )
        .all()
    )
    for row, installation_id in rows:
        row.status = "expired"
        _audit(
            session,
            company_id=company_id,
            actor_user_id=None,
            action="remote_assistance.device_key.expired",
            entity_type="RemoteAssistanceDeviceKey",
            entity_id=row.id,
            device_ref=_device_ref(company_id, installation_id),
            before_status="pending",
            after_status="expired",
            extra={"key_ref": _key_ref(company_id, row)},
        )
    if rows:
        await session.flush()


def _device_key_admin_read(
    row: RemoteAssistanceDeviceKey,
    *,
    installation_id: UUID,
    names: dict[UUID, str],
) -> DeviceKeyAdminRead:
    # Before physical pairing succeeds, the full fingerprint is tablet-only.
    # This prevents the owner channel becoming an alternate pairing display.
    fingerprint = row.public_key_fingerprint_sha256 if row.approved_at is not None else None
    return DeviceKeyAdminRead(
        key_id=row.id,
        installation_id=installation_id,
        status=cast("DeviceKeyStatus", row.status),
        fingerprint_sha256=fingerprint,
        enrolled_by_user_id=row.enrolled_by_user_id,
        enrolled_by_name=names.get(row.enrolled_by_user_id),
        enrolled_at=row.enrolled_at,
        pending_expires_at=row.pending_expires_at,
        approved_by_user_id=row.approved_by_user_id,
        approved_by_name=(
            names.get(row.approved_by_user_id) if row.approved_by_user_id is not None else None
        ),
        approved_at=row.approved_at,
        revoked_by_user_id=row.revoked_by_user_id,
        revoked_by_name=(
            names.get(row.revoked_by_user_id) if row.revoked_by_user_id is not None else None
        ),
        revoked_at=row.revoked_at,
    )


def _device_key_status_read(
    row: RemoteAssistanceDeviceKey,
    *,
    installation: ClientInstallation,
) -> DeviceKeyStatusRead:
    return DeviceKeyStatusRead(
        server_time=datetime.now(UTC),
        key_id=row.id,
        installation_id=installation.installation_id,
        status=cast("DeviceKeyStatus", row.status),
        fingerprint_sha256=row.public_key_fingerprint_sha256,
        enrolled_at=row.enrolled_at,
        pending_expires_at=row.pending_expires_at,
        approved_at=row.approved_at,
        revoked_at=row.revoked_at,
        pairing_code=(
            pairing_code(
                company_id=row.company_id,
                installation_id=installation.installation_id,
                key_id=row.id,
                fingerprint_sha256=row.public_key_fingerprint_sha256,
            )
            if row.status == "pending"
            else None
        ),
    )


async def _authenticate_device_json(
    *,
    request: Request,
    session: AsyncSession,
    tenant: TenantContext,
    installation: ClientInstallation,
    now: datetime | None = None,
) -> None:
    await authenticate_device_request(
        request=request,
        session=session,
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        actual_content=await request.body(),
        now=now,
    )


def _grant_read(
    row: RemoteAssistanceGrant,
    *,
    installation_id: UUID,
    names: dict[UUID, str],
) -> GrantRead:
    return GrantRead(
        id=row.id,
        installation_id=installation_id,
        kind=cast("GrantKind", row.kind),
        status=cast("GrantStatus", row.status),
        requested_by_user_id=row.requested_by_user_id,
        requested_by_name=names.get(row.requested_by_user_id),
        requested_for_user_id=row.requested_for_user_id,
        requested_for_name=names.get(row.requested_for_user_id),
        responded_by_user_id=row.responded_by_user_id,
        responded_by_name=names.get(row.responded_by_user_id) if row.responded_by_user_id else None,
        requested_at=row.requested_at,
        expires_at=row.expires_at,
        responded_at=row.responded_at,
        revoked_at=row.revoked_at,
        consumed_at=row.consumed_at,
    )


def _session_read(
    row: RemoteAssistanceSession,
    *,
    installation_id: UUID,
    names: dict[UUID, str],
    next_sequence: int,
) -> SessionRead:
    return SessionRead(
        id=row.id,
        installation_id=installation_id,
        grant_id=row.grant_id,
        status=cast("SessionStatus", row.status),
        duration_seconds=row.duration_seconds,
        requested_by_user_id=row.requested_by_user_id,
        requested_by_name=names.get(row.requested_by_user_id),
        started_by_user_id=row.started_by_user_id,
        started_by_name=names.get(row.started_by_user_id) if row.started_by_user_id else None,
        ended_by_user_id=row.ended_by_user_id,
        ended_by_name=names.get(row.ended_by_user_id) if row.ended_by_user_id else None,
        requested_at=row.requested_at,
        request_expires_at=row.request_expires_at,
        started_at=row.started_at,
        expires_at=row.expires_at,
        ended_at=row.ended_at,
        end_reason=row.end_reason,
        next_sequence=next_sequence,
    )


def _command_read(row: RemoteAssistanceCommand) -> CommandRead:
    return CommandRead(
        command_id=row.id,
        session_id=row.session_id,
        sequence=row.sequence,
        type=cast("CommandType", row.command_type),
        module=cast("NavigationModule | None", row.module),
        status=cast("CommandStatus", row.status),
        issued_by_user_id=row.issued_by_user_id,
        issued_at=row.issued_at,
        resolved_by_user_id=row.resolved_by_user_id,
        resolved_at=row.resolved_at,
        rejection_reason_code=cast("CommandRejectionReason | None", row.rejection_reason_code),
    )


async def _next_sequence(session: AsyncSession, session_id: UUID) -> int:
    current = int(
        (
            await session.execute(
                select(func.coalesce(func.max(RemoteAssistanceCommand.sequence), 0)).where(
                    RemoteAssistanceCommand.session_id == session_id
                )
            )
        ).scalar_one()
    )
    return current + 1


async def _read_bounded_body(request: Request, *, max_bytes: int) -> bytes:
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > max_bytes:
            raise ValidationError(
                "The support frame exceeds the configured byte limit.",
                details={"max_bytes": max_bytes},
            )
        chunks.append(chunk)
    return b"".join(chunks)


async def _reject_pending_commands(
    session: AsyncSession,
    *,
    row: RemoteAssistanceSession,
    now: datetime,
    actor_user_id: UUID | None,
    device_ref: str,
) -> None:
    commands = (
        (
            await session.execute(
                select(RemoteAssistanceCommand)
                .where(
                    RemoteAssistanceCommand.company_id == row.company_id,
                    RemoteAssistanceCommand.session_id == row.id,
                    RemoteAssistanceCommand.status == "pending",
                )
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    for command in commands:
        command.status = "rejected"
        command.resolved_at = now
        command.resolved_by_user_id = actor_user_id
        command.rejection_reason_code = "session_ended"
        _audit(
            session,
            company_id=row.company_id,
            actor_user_id=actor_user_id,
            action="remote_assistance.command.rejected",
            entity_type="RemoteAssistanceCommand",
            entity_id=command.id,
            device_ref=device_ref,
            before_status="pending",
            after_status="rejected",
            extra={"reason_code": "session_ended", "sequence": command.sequence},
        )


async def _expire_stale(
    session: AsyncSession,
    *,
    company_id: UUID,
    now: datetime,
    client_installation_id: UUID | None = None,
) -> None:
    changed = False
    grant_predicates = [
        RemoteAssistanceGrant.company_id == company_id,
        RemoteAssistanceGrant.status.in_(("requested", "active")),
        RemoteAssistanceGrant.expires_at <= now,
    ]
    if client_installation_id is not None:
        grant_predicates.append(
            RemoteAssistanceGrant.client_installation_id == client_installation_id
        )
    grants = (
        (
            await session.execute(
                select(RemoteAssistanceGrant)
                .where(*grant_predicates)
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    expired_grant_ids: set[UUID] = set()
    for grant in grants:
        changed = True
        before = grant.status
        grant.status = "expired"
        expired_grant_ids.add(grant.id)
        installation_id = (
            await session.execute(
                select(ClientInstallation.installation_id).where(
                    ClientInstallation.id == grant.client_installation_id
                )
            )
        ).scalar_one()
        _audit(
            session,
            company_id=company_id,
            actor_user_id=None,
            action="remote_assistance.grant.expired",
            entity_type="RemoteAssistanceGrant",
            entity_id=grant.id,
            device_ref=_device_ref(company_id, installation_id),
            before_status=before,
            after_status="expired",
        )

    conditions = [
        (RemoteAssistanceSession.status == "requested")
        & (RemoteAssistanceSession.request_expires_at <= now),
        (RemoteAssistanceSession.status == "active") & (RemoteAssistanceSession.expires_at <= now),
    ]
    session_predicates = [
        RemoteAssistanceSession.company_id == company_id,
        conditions[0] | conditions[1],
    ]
    if client_installation_id is not None:
        session_predicates.append(
            RemoteAssistanceSession.client_installation_id == client_installation_id
        )
    session_statement = select(RemoteAssistanceSession).where(*session_predicates)
    if expired_grant_ids:
        scoped_condition = (conditions[0] | conditions[1]) | (
            RemoteAssistanceSession.grant_id.in_(expired_grant_ids)
            & RemoteAssistanceSession.status.in_(("requested", "active"))
        )
        session_predicates = [
            RemoteAssistanceSession.company_id == company_id,
            scoped_condition,
        ]
        if client_installation_id is not None:
            session_predicates.append(
                RemoteAssistanceSession.client_installation_id == client_installation_id
            )
        session_statement = select(RemoteAssistanceSession).where(*session_predicates)
    stale_sessions = (await session.execute(session_statement.with_for_update())).scalars().all()
    for support_session in stale_sessions:
        if support_session.status not in {"requested", "active"}:
            continue
        changed = True
        before = support_session.status
        support_session.status = "expired"
        installation_id = (
            await session.execute(
                select(ClientInstallation.installation_id).where(
                    ClientInstallation.id == support_session.client_installation_id
                )
            )
        ).scalar_one()
        ref = _device_ref(company_id, installation_id)
        await _reject_pending_commands(
            session,
            row=support_session,
            now=now,
            actor_user_id=None,
            device_ref=ref,
        )
        _audit(
            session,
            company_id=company_id,
            actor_user_id=None,
            action="remote_assistance.session.expired",
            entity_type="RemoteAssistanceSession",
            entity_id=support_session.id,
            device_ref=ref,
            before_status=before,
            after_status="expired",
        )

    # This codebase deliberately disables autoflush.  Persist reconciled state
    # before callers issue their read query so an expired identity-map object
    # cannot still be selected by its former database status.
    if changed:
        await session.flush()


async def _grant_response(
    session: AsyncSession,
    row: RemoteAssistanceGrant,
    installation: ClientInstallation,
) -> GrantRead:
    names = await _names(
        session,
        {
            row.requested_by_user_id,
            row.requested_for_user_id,
            row.responded_by_user_id,
            row.revoked_by_user_id,
        },
    )
    return _grant_read(row, installation_id=installation.installation_id, names=names)


async def _session_response(
    session: AsyncSession,
    row: RemoteAssistanceSession,
    installation: ClientInstallation,
) -> SessionRead:
    names = await _names(
        session,
        {row.requested_by_user_id, row.started_by_user_id, row.ended_by_user_id},
    )
    return _session_read(
        row,
        installation_id=installation.installation_id,
        names=names,
        next_sequence=await _next_sequence(session, row.id),
    )


async def _end_session(
    session: AsyncSession,
    *,
    row: RemoteAssistanceSession,
    installation: ClientInstallation,
    actor_user_id: UUID,
    end_id: UUID,
    reason: str,
) -> None:
    if row.status not in {"requested", "active"}:
        if row.end_id == end_id and row.end_reason == reason:
            return
        raise ConflictError("This support session already has a different terminal outcome.")
    reused_end_id = (
        await session.execute(
            select(RemoteAssistanceSession.id).where(
                RemoteAssistanceSession.company_id == row.company_id,
                RemoteAssistanceSession.end_id == end_id,
                RemoteAssistanceSession.id != row.id,
            )
        )
    ).scalar_one_or_none()
    if reused_end_id is not None:
        raise ConflictError("The end action id was already used for another support session.")
    now = datetime.now(UTC)
    before = row.status
    row.status = "ended"
    row.ended_at = now
    row.ended_by_user_id = actor_user_id
    row.end_id = end_id
    row.end_reason = reason
    ref = _device_ref(row.company_id, installation.installation_id)
    await _reject_pending_commands(
        session,
        row=row,
        now=now,
        actor_user_id=actor_user_id,
        device_ref=ref,
    )
    _audit(
        session,
        company_id=row.company_id,
        actor_user_id=actor_user_id,
        action="remote_assistance.session.ended",
        entity_type="RemoteAssistanceSession",
        entity_id=row.id,
        device_ref=ref,
        before_status=before,
        after_status="ended",
        extra={"reason": reason},
    )


async def _revoke_grant(
    session: AsyncSession,
    *,
    row: RemoteAssistanceGrant,
    installation: ClientInstallation,
    actor_user_id: UUID,
    revocation_id: UUID,
) -> None:
    if row.status == "revoked":
        if row.revocation_id != revocation_id:
            raise ConflictError("This grant was already revoked by a different action.")
        return
    if row.status not in {"requested", "active", "consumed"}:
        raise ConflictError("Only a requested or active support grant can be revoked.")

    reused_revocation_id = (
        await session.execute(
            select(RemoteAssistanceGrant.id).where(
                RemoteAssistanceGrant.company_id == row.company_id,
                RemoteAssistanceGrant.revocation_id == revocation_id,
                RemoteAssistanceGrant.id != row.id,
            )
        )
    ).scalar_one_or_none()
    if reused_revocation_id is not None:
        raise ConflictError("The revoke action id was already used for another support grant.")

    now = datetime.now(UTC)
    before = row.status
    row.status = "revoked"
    row.revoked_at = now
    row.revoked_by_user_id = actor_user_id
    row.revocation_id = revocation_id
    ref = _device_ref(row.company_id, installation.installation_id)
    _audit(
        session,
        company_id=row.company_id,
        actor_user_id=actor_user_id,
        action="remote_assistance.grant.revoked",
        entity_type="RemoteAssistanceGrant",
        entity_id=row.id,
        device_ref=ref,
        before_status=before,
        after_status="revoked",
    )
    open_sessions = (
        (
            await session.execute(
                select(RemoteAssistanceSession)
                .where(
                    RemoteAssistanceSession.company_id == row.company_id,
                    RemoteAssistanceSession.grant_id == row.id,
                    RemoteAssistanceSession.status.in_(("requested", "active")),
                )
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    for support_session in open_sessions:
        await _end_session(
            session,
            row=support_session,
            installation=installation,
            actor_user_id=actor_user_id,
            end_id=revocation_id,
            reason="grant_revoked",
        )


async def _terminate_device_support(
    session: AsyncSession,
    *,
    installation: ClientInstallation,
    actor_user_id: UUID,
) -> None:
    """Revoke every usable consent and stop the one possible open session."""

    grants = (
        (
            await session.execute(
                select(RemoteAssistanceGrant)
                .where(
                    RemoteAssistanceGrant.company_id == installation.company_id,
                    RemoteAssistanceGrant.client_installation_id == installation.id,
                    RemoteAssistanceGrant.status.in_(("requested", "active", "consumed")),
                )
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    for grant in grants:
        await _revoke_grant(
            session,
            row=grant,
            installation=installation,
            actor_user_id=actor_user_id,
            revocation_id=uuid4(),
        )
        await delete_latest_frame_for_grant(session, grant)

    if grants:
        # Autoflush is intentionally disabled in this service. Persist grant
        # and session terminal states before the defensive open-session query.
        await session.flush()

    # Defensive reconciliation for any legacy/open session whose grant was
    # already terminal before key revocation.
    open_sessions = (
        (
            await session.execute(
                select(RemoteAssistanceSession)
                .where(
                    RemoteAssistanceSession.company_id == installation.company_id,
                    RemoteAssistanceSession.client_installation_id == installation.id,
                    RemoteAssistanceSession.status.in_(("requested", "active")),
                )
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    for support_session in open_sessions:
        if support_session.status not in {"requested", "active"}:
            continue
        await _end_session(
            session,
            row=support_session,
            installation=installation,
            actor_user_id=actor_user_id,
            end_id=uuid4(),
            reason="grant_revoked",
        )
        await delete_latest_frame(
            company_id=installation.company_id,
            session_id=support_session.id,
        )


@router.post("/device/keys/enroll", response_model=DeviceKeyStatusRead)
async def enroll_device_key(
    request: Request,
    response: Response,
    payload: DeviceKeyEnrollmentWrite,
    session: SessionDep,
    tenant: TenantDep,
) -> DeviceKeyStatusRead:
    """Enroll proof-verified public material as pending; never self-approve it."""

    _require_android_request(request)
    response.headers["Cache-Control"] = "private, no-store"
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
    )
    _require_device_actor(installation, tenant)
    public_key = parse_p256_spki(payload.public_key_spki)
    await authenticate_enrollment_request(
        company_id=tenant.company_id,
        installation_id=installation.installation_id,
        key_id=payload.key_id,
        enrollment_id=payload.enrollment_id,
        signed_at_epoch_seconds=payload.signed_at_epoch_seconds,
        nonce=payload.nonce,
        public_key=public_key,
        signature=payload.signature,
        now=now,
    )
    await _lock_device(session, tenant.company_id, installation.id)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    _require_device_actor(installation, tenant)
    await _reconcile_consent_user(session, installation=installation, now=now)
    await _lock_device_key_action(session, tenant.company_id, payload.enrollment_id)
    await _expire_pending_device_keys(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )

    existing_id = (
        await session.execute(
            select(RemoteAssistanceDeviceKey)
            .where(RemoteAssistanceDeviceKey.id == payload.key_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing_id is not None:
        if (
            existing_id.company_id != tenant.company_id
            or existing_id.client_installation_id != installation.id
            or existing_id.enrollment_id != payload.enrollment_id
            or existing_id.public_key_spki != public_key.spki_der
            or existing_id.public_key_fingerprint_sha256 != public_key.fingerprint_sha256
        ):
            raise ConflictError("The device key id is already in use.")
        return _device_key_status_read(existing_id, installation=installation)

    reused_identity = (
        await session.execute(
            select(RemoteAssistanceDeviceKey.id).where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                (
                    (RemoteAssistanceDeviceKey.enrollment_id == payload.enrollment_id)
                    | (
                        RemoteAssistanceDeviceKey.public_key_fingerprint_sha256
                        == public_key.fingerprint_sha256
                    )
                    | (RemoteAssistanceDeviceKey.approval_id == payload.enrollment_id)
                    | (RemoteAssistanceDeviceKey.revocation_id == payload.enrollment_id)
                ),
            ).limit(1)
        )
    ).scalar_one_or_none()
    if reused_identity is not None:
        raise ConflictError("The enrollment id or public key was already used.")
    open_pending = (
        await session.execute(
            select(RemoteAssistanceDeviceKey.id).where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                RemoteAssistanceDeviceKey.client_installation_id == installation.id,
                RemoteAssistanceDeviceKey.status == "pending",
            ).limit(1)
        )
    ).scalar_one_or_none()
    if open_pending is not None:
        raise ConflictError("This device already has an unexpired pending key enrollment.")

    row = RemoteAssistanceDeviceKey(
        id=payload.key_id,
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        public_key_spki=public_key.spki_der,
        public_key_fingerprint_sha256=public_key.fingerprint_sha256,
        status="pending",
        enrollment_id=payload.enrollment_id,
        enrolled_by_user_id=tenant.user_id,
        enrolled_at=now,
        pending_expires_at=now
        + timedelta(seconds=get_settings().remote_assistance_device_key_pending_seconds),
    )
    session.add(row)
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.device_key.enrolled",
        entity_type="RemoteAssistanceDeviceKey",
        entity_id=row.id,
        device_ref=_device_ref(tenant.company_id, installation.installation_id),
        after_status="pending",
        extra={"key_ref": _key_ref(tenant.company_id, row)},
    )
    await session.flush()
    return _device_key_status_read(row, installation=installation)


@router.get("/device/keys/{key_id}/status", response_model=DeviceKeyStatusRead)
async def device_key_status(
    request: Request,
    response: Response,
    key_id: UUID,
    session: SessionDep,
    tenant: TenantDep,
    installation_id: Annotated[UUID, Query()],
) -> DeviceKeyStatusRead:
    _require_android_request(request)
    response.headers["Cache-Control"] = "private, no-store"
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=installation_id,
    )
    _require_device_actor(installation, tenant)
    await _expire_pending_device_keys(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    proof = await authenticate_device_request(
        request=request,
        session=session,
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        allowed_statuses=frozenset(REMOTE_ASSISTANCE_DEVICE_KEY_STATUSES),
        actual_content=b"",
        now=now,
    )
    if proof.device_key.id != key_id:
        raise AuthError("The signed device key does not match the requested key status.")
    return _device_key_status_read(proof.device_key, installation=installation)


async def _device_key_admin_response(
    session: AsyncSession,
    *,
    row: RemoteAssistanceDeviceKey,
    installation: ClientInstallation,
) -> DeviceKeyAdminRead:
    names = await _names(
        session,
        {row.enrolled_by_user_id, row.approved_by_user_id, row.revoked_by_user_id},
    )
    return _device_key_admin_read(
        row,
        installation_id=installation.installation_id,
        names=names,
    )


@router.post("/device-keys/{key_id}/approve", response_model=DeviceKeyAdminRead)
async def approve_device_key(
    key_id: UUID,
    payload: DeviceKeyApproveWrite,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> DeviceKeyAdminRead:
    now = datetime.now(UTC)
    initial_installation_id = (
        await session.execute(
            select(RemoteAssistanceDeviceKey.client_installation_id).where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                RemoteAssistanceDeviceKey.id == key_id,
            )
        )
    ).scalar_one_or_none()
    if initial_installation_id is None:
        raise NotFoundError("Pending device key not found.")
    await _lock_device(session, tenant.company_id, initial_installation_id)
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(
                ClientInstallation.company_id == tenant.company_id,
                ClientInstallation.id == initial_installation_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    await _lock_device_key_action(session, tenant.company_id, payload.approval_id)
    await _expire_pending_device_keys(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    row = (
        await session.execute(
            select(RemoteAssistanceDeviceKey)
            .where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                RemoteAssistanceDeviceKey.id == key_id,
            )
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    if row.client_installation_id != installation.id:
        raise ConflictError("The device key changed installation scope.")
    if row.status == "active":
        if row.approval_id == payload.approval_id:
            return await _device_key_admin_response(
                session,
                row=row,
                installation=installation,
            )
        raise ConflictError("This device key was already approved by a different action.")
    if row.status != "pending":
        raise ConflictError("This device key is no longer awaiting pairing approval.")
    reused_action = (
        await session.execute(
            select(RemoteAssistanceDeviceKey.id).where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                (
                    (RemoteAssistanceDeviceKey.id == payload.approval_id)
                    | (RemoteAssistanceDeviceKey.enrollment_id == payload.approval_id)
                    | (RemoteAssistanceDeviceKey.approval_id == payload.approval_id)
                    | (RemoteAssistanceDeviceKey.revocation_id == payload.approval_id)
                ),
            ).limit(1)
        )
    ).scalar_one_or_none()
    if reused_action is not None:
        raise ConflictError("The approval id is already in use.")
    expected_code = pairing_code(
        company_id=tenant.company_id,
        installation_id=installation.installation_id,
        key_id=row.id,
        fingerprint_sha256=row.public_key_fingerprint_sha256,
    )
    if not hmac.compare_digest(payload.pairing_code, expected_code):
        raise RemotePairingCodeMismatchError("The tablet pairing code does not match.")

    previous_active = (
        await session.execute(
            select(RemoteAssistanceDeviceKey)
            .where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                RemoteAssistanceDeviceKey.client_installation_id == installation.id,
                RemoteAssistanceDeviceKey.status == "active",
                RemoteAssistanceDeviceKey.id != row.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if previous_active is not None:
        previous_active.status = "revoked"
        previous_active.revoked_at = now
        previous_active.revoked_by_user_id = tenant.user_id
        # Rotation revocation is a distinct internal action. Reusing the owner
        # approval UUID here would make one action identity resolve to two rows.
        previous_active.revocation_id = uuid4()
        _audit(
            session,
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            action="remote_assistance.device_key.rotated",
            entity_type="RemoteAssistanceDeviceKey",
            entity_id=previous_active.id,
            device_ref=_device_ref(tenant.company_id, installation.installation_id),
            before_status="active",
            after_status="revoked",
            extra={"key_ref": _key_ref(tenant.company_id, previous_active)},
        )
        # The active-key partial unique index is immediate. Flush the old key's
        # revocation before promoting its replacement; both remain in this one
        # transaction and therefore rotate atomically to other connections.
        await session.flush([previous_active])

    row.status = "active"
    row.approval_id = payload.approval_id
    row.approved_by_user_id = tenant.user_id
    row.approved_at = now
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.device_key.approved",
        entity_type="RemoteAssistanceDeviceKey",
        entity_id=row.id,
        device_ref=_device_ref(tenant.company_id, installation.installation_id),
        before_status="pending",
        after_status="active",
        extra={
            "key_ref": _key_ref(tenant.company_id, row),
            "fingerprint_sha256": row.public_key_fingerprint_sha256,
        },
    )
    if previous_active is not None:
        await _terminate_device_support(
            session,
            installation=installation,
            actor_user_id=tenant.user_id,
        )
    await session.flush()
    return await _device_key_admin_response(session, row=row, installation=installation)


@router.post("/device-keys/{key_id}/revoke", response_model=DeviceKeyAdminRead)
async def revoke_device_key(
    key_id: UUID,
    payload: DeviceKeyRevokeWrite,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> DeviceKeyAdminRead:
    initial_installation_id = (
        await session.execute(
            select(RemoteAssistanceDeviceKey.client_installation_id).where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                RemoteAssistanceDeviceKey.id == key_id,
            )
        )
    ).scalar_one_or_none()
    if initial_installation_id is None:
        raise NotFoundError("Device key not found.")
    await _lock_device(session, tenant.company_id, initial_installation_id)
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(
                ClientInstallation.company_id == tenant.company_id,
                ClientInstallation.id == initial_installation_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    await _lock_device_key_action(session, tenant.company_id, payload.revocation_id)
    row = (
        await session.execute(
            select(RemoteAssistanceDeviceKey)
            .where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                RemoteAssistanceDeviceKey.id == key_id,
            )
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one()
    if row.client_installation_id != installation.id:
        raise ConflictError("The device key changed installation scope.")
    if row.status == "revoked":
        if row.revocation_id == payload.revocation_id:
            return await _device_key_admin_response(
                session,
                row=row,
                installation=installation,
            )
        raise ConflictError("This device key was already revoked by a different action.")
    if row.status not in {"pending", "active"}:
        raise ConflictError("This device key can no longer be revoked.")
    reused_action = (
        await session.execute(
            select(RemoteAssistanceDeviceKey.id).where(
                RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                (
                    (RemoteAssistanceDeviceKey.id == payload.revocation_id)
                    | (RemoteAssistanceDeviceKey.enrollment_id == payload.revocation_id)
                    | (RemoteAssistanceDeviceKey.approval_id == payload.revocation_id)
                    | (RemoteAssistanceDeviceKey.revocation_id == payload.revocation_id)
                ),
            ).limit(1)
        )
    ).scalar_one_or_none()
    if reused_action is not None:
        raise ConflictError("The revocation id is already in use.")
    was_active = row.status == "active"
    row.status = "revoked"
    row.revocation_id = payload.revocation_id
    row.revoked_by_user_id = tenant.user_id
    row.revoked_at = datetime.now(UTC)
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.device_key.revoked",
        entity_type="RemoteAssistanceDeviceKey",
        entity_id=row.id,
        device_ref=_device_ref(tenant.company_id, installation.installation_id),
        before_status="active" if was_active else "pending",
        after_status="revoked",
        extra={"key_ref": _key_ref(tenant.company_id, row)},
    )
    if was_active:
        await _terminate_device_support(
            session,
            installation=installation,
            actor_user_id=tenant.user_id,
        )
    await session.flush()
    return await _device_key_admin_response(session, row=row, installation=installation)


@router.post("/device/heartbeat", response_model=DeviceHeartbeatRead)
async def device_heartbeat(
    request: Request,
    payload: DeviceHeartbeatWrite,
    session: SessionDep,
    tenant: TenantDep,
) -> DeviceHeartbeatRead:
    _require_android_request(request)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    _require_device_actor(installation, tenant)
    now = datetime.now(UTC)
    await _authenticate_device_json(
        request=request,
        session=session,
        tenant=tenant,
        installation=installation,
        now=now,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=now,
        device_actor=tenant,
    )
    await enforce_client_heartbeat_rate_limit(
        company_id=tenant.company_id,
        user_id=tenant.user_id,
    )
    installation.remote_support_protocol_version = payload.protocol_version
    installation.remote_support_capability = payload.sharing_capability
    installation.remote_support_last_seen_at = now
    return DeviceHeartbeatRead(
        installation_id=installation.installation_id,
        server_time=now,
        remote_support_last_seen_at=now,
        protocol_version=payload.protocol_version,
        sharing_capability=payload.sharing_capability,
    )


@router.get("/devices", response_model=DeviceList)
async def list_devices(
    response: Response,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> DeviceList:
    response.headers["Cache-Control"] = "private, no-store"
    now = datetime.now(UTC)
    await _expire_stale(session, company_id=tenant.company_id, now=now)
    await _expire_pending_device_keys(session, company_id=tenant.company_id, now=now)
    rows = (
        await session.execute(
            select(ClientInstallation, Terminal.name, User.name)
            .outerjoin(Terminal, Terminal.id == ClientInstallation.terminal_id)
            .outerjoin(User, User.id == ClientInstallation.last_user_id)
            .where(
                ClientInstallation.company_id == tenant.company_id,
                ClientInstallation.platform == "android",
            )
            .order_by(ClientInstallation.last_seen_at.desc(), ClientInstallation.id)
        )
    ).all()
    internal_ids = {installation.id for installation, _, _ in rows}
    current_key_rows = (
        (
            await session.execute(
                select(RemoteAssistanceDeviceKey).where(
                    RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                    RemoteAssistanceDeviceKey.client_installation_id.in_(internal_ids),
                    RemoteAssistanceDeviceKey.status.in_(("active", "pending")),
                )
            )
        )
        .scalars()
        .all()
        if internal_ids
        else []
    )
    terminal_key_rows = (
        (
            await session.execute(
                select(RemoteAssistanceDeviceKey)
                .where(
                    RemoteAssistanceDeviceKey.company_id == tenant.company_id,
                    RemoteAssistanceDeviceKey.client_installation_id.in_(internal_ids),
                    RemoteAssistanceDeviceKey.status.in_(("revoked", "expired")),
                )
                .order_by(
                    RemoteAssistanceDeviceKey.client_installation_id,
                    RemoteAssistanceDeviceKey.enrolled_at.desc(),
                    RemoteAssistanceDeviceKey.id.desc(),
                )
                .distinct(RemoteAssistanceDeviceKey.client_installation_id)
            )
        )
        .scalars()
        .all()
        if internal_ids
        else []
    )
    active_keys = {
        row.client_installation_id: row for row in current_key_rows if row.status == "active"
    }
    pending_keys = {
        row.client_installation_id: row for row in current_key_rows if row.status == "pending"
    }
    terminal_keys = {row.client_installation_id: row for row in terminal_key_rows}
    pending_key_names = await _names(
        session,
        {row.enrolled_by_user_id for row in pending_keys.values()},
    )
    grant_rows = (
        (
            await session.execute(
                select(RemoteAssistanceGrant)
                .where(
                    RemoteAssistanceGrant.company_id == tenant.company_id,
                    RemoteAssistanceGrant.client_installation_id.in_(internal_ids),
                )
                .order_by(
                    RemoteAssistanceGrant.client_installation_id,
                    RemoteAssistanceGrant.requested_at.desc(),
                    RemoteAssistanceGrant.id.desc(),
                )
                .distinct(RemoteAssistanceGrant.client_installation_id)
            )
        )
        .scalars()
        .all()
        if internal_ids
        else []
    )
    session_rows = (
        (
            await session.execute(
                select(RemoteAssistanceSession)
                .where(
                    RemoteAssistanceSession.company_id == tenant.company_id,
                    RemoteAssistanceSession.client_installation_id.in_(internal_ids),
                )
                .order_by(
                    RemoteAssistanceSession.client_installation_id,
                    RemoteAssistanceSession.requested_at.desc(),
                    RemoteAssistanceSession.id.desc(),
                )
                .distinct(RemoteAssistanceSession.client_installation_id)
            )
        )
        .scalars()
        .all()
        if internal_ids
        else []
    )
    latest_grant: dict[UUID, RemoteAssistanceGrant] = {}
    for grant in grant_rows:
        latest_grant.setdefault(grant.client_installation_id, grant)
    grant_responder_names = await _names(
        session,
        {grant.responded_by_user_id for grant in latest_grant.values()},
    )
    latest_session: dict[UUID, RemoteAssistanceSession] = {}
    for support_session in session_rows:
        latest_session.setdefault(support_session.client_installation_id, support_session)
    current_session_ids = {
        item.id for item in latest_session.values() if item.status in {"requested", "active"}
    }
    sequence_rows = (
        (
            await session.execute(
                select(
                    RemoteAssistanceCommand.session_id,
                    func.coalesce(func.max(RemoteAssistanceCommand.sequence), 0),
                )
                .where(RemoteAssistanceCommand.session_id.in_(current_session_ids))
                .group_by(RemoteAssistanceCommand.session_id)
            )
        ).all()
        if current_session_ids
        else []
    )
    next_sequences = {row_id: int(maximum) + 1 for row_id, maximum in sequence_rows}
    threshold = get_settings().remote_assistance_device_online_seconds
    online_after = now - timedelta(seconds=threshold)
    items = [
        DeviceRead(
            installation_id=installation.installation_id,
            terminal_id=installation.terminal_id,
            terminal_name=terminal_name,
            version_name=installation.version_name,
            version_code=installation.version_code,
            last_user_id=installation.last_user_id,
            last_user_name=last_user_name,
            last_seen_at=installation.last_seen_at,
            remote_support_last_seen_at=installation.remote_support_last_seen_at,
            is_remote_online=(
                installation.remote_support_last_seen_at is not None
                and installation.remote_support_last_seen_at >= online_after
            ),
            protocol_version=installation.remote_support_protocol_version,
            sharing_capability=cast(
                "SharingCapability | None",
                installation.remote_support_capability,
            ),
            device_key_id=(
                active_keys.get(installation.id)
                or pending_keys.get(installation.id)
                or terminal_keys.get(installation.id)
            ).id
            if (
                active_keys.get(installation.id)
                or pending_keys.get(installation.id)
                or terminal_keys.get(installation.id)
            )
            is not None
            else None,
            device_key_status=cast(
                "DeviceKeyStatus | None",
                (
                    active_keys.get(installation.id)
                    or pending_keys.get(installation.id)
                    or terminal_keys.get(installation.id)
                ).status
                if (
                    active_keys.get(installation.id)
                    or pending_keys.get(installation.id)
                    or terminal_keys.get(installation.id)
                )
                is not None
                else None,
            ),
            device_key_fingerprint_sha256=(
                active_keys[installation.id].public_key_fingerprint_sha256
                if installation.id in active_keys
                else (
                    terminal_keys[installation.id].public_key_fingerprint_sha256
                    if installation.id in terminal_keys
                    and terminal_keys[installation.id].approved_at is not None
                    else None
                )
            ),
            device_key_approved_at=(
                active_keys[installation.id].approved_at
                if installation.id in active_keys
                else (
                    terminal_keys[installation.id].approved_at
                    if installation.id in terminal_keys
                    else None
                )
            ),
            pending_device_key_id=(
                pending_keys[installation.id].id if installation.id in pending_keys else None
            ),
            pending_device_key_enrolled_by_user_id=(
                pending_keys[installation.id].enrolled_by_user_id
                if installation.id in pending_keys
                else None
            ),
            pending_device_key_enrolled_by_name=(
                pending_key_names.get(pending_keys[installation.id].enrolled_by_user_id)
                if installation.id in pending_keys
                else None
            ),
            pending_device_key_enrolled_at=(
                pending_keys[installation.id].enrolled_at
                if installation.id in pending_keys
                else None
            ),
            pending_device_key_expires_at=(
                pending_keys[installation.id].pending_expires_at
                if installation.id in pending_keys
                else None
            ),
            pairing_required=installation.id in pending_keys,
            grant_status=cast(
                "GrantStatus | None",
                latest_grant[installation.id].status if installation.id in latest_grant else None,
            ),
            current_grant_id=(
                latest_grant[installation.id].id
                if installation.id in latest_grant
                and latest_grant[installation.id].status in {"requested", "active", "consumed"}
                else None
            ),
            current_grant_kind=cast(
                "GrantKind | None",
                latest_grant[installation.id].kind
                if installation.id in latest_grant
                and latest_grant[installation.id].status in {"requested", "active", "consumed"}
                else None,
            ),
            current_grant_expires_at=(
                latest_grant[installation.id].expires_at
                if installation.id in latest_grant
                and latest_grant[installation.id].status in {"requested", "active", "consumed"}
                else None
            ),
            current_grant_responded_by_user_id=(
                latest_grant[installation.id].responded_by_user_id
                if installation.id in latest_grant
                and latest_grant[installation.id].status in {"requested", "active", "consumed"}
                else None
            ),
            current_grant_responded_by_name=(
                grant_responder_names.get(
                    cast("UUID", latest_grant[installation.id].responded_by_user_id)
                )
                if installation.id in latest_grant
                and latest_grant[installation.id].status in {"requested", "active", "consumed"}
                and latest_grant[installation.id].responded_by_user_id is not None
                else None
            ),
            current_grant_responded_at=(
                latest_grant[installation.id].responded_at
                if installation.id in latest_grant
                and latest_grant[installation.id].status in {"requested", "active", "consumed"}
                else None
            ),
            session_status=cast(
                "SessionStatus | None",
                latest_session[installation.id].status
                if installation.id in latest_session
                else None,
            ),
            current_session_id=(
                latest_session[installation.id].id
                if installation.id in latest_session
                and latest_session[installation.id].status in {"requested", "active"}
                else None
            ),
            current_session_expires_at=(
                latest_session[installation.id].expires_at
                if installation.id in latest_session
                and latest_session[installation.id].status in {"requested", "active"}
                else None
            ),
            current_session_next_sequence=(
                next_sequences.get(latest_session[installation.id].id, 1)
                if installation.id in latest_session
                and latest_session[installation.id].status in {"requested", "active"}
                else None
            ),
        )
        for installation, terminal_name, last_user_name in rows
    ]
    return DeviceList(
        server_time=now,
        online_within_seconds=threshold,
        total=len(items),
        items=items,
    )


@router.post("/requests", response_model=RemoteRequestRead)
async def request_assistance(
    payload: RemoteRequestCreate,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> RemoteRequestRead:
    settings = get_settings()
    if payload.session_ttl_seconds > settings.remote_assistance_session_max_seconds:
        raise ValidationError("session_ttl_seconds exceeds the configured session maximum.")
    if (
        payload.grant_kind == "anytime"
        and payload.grant_ttl_seconds > settings.remote_assistance_anytime_grant_max_seconds
    ):
        raise ValidationError("grant_ttl_seconds exceeds the configured anytime maximum.")
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
    )
    await _lock_device(session, tenant.company_id, installation.id)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(session, installation=installation, now=now)
    existing_any_tenant = (
        await session.execute(
            select(RemoteAssistanceGrant).where(RemoteAssistanceGrant.id == payload.request_id)
        )
    ).scalar_one_or_none()
    if existing_any_tenant is not None:
        if existing_any_tenant.company_id != tenant.company_id:
            raise ConflictError("The request id is already in use.")
        initial_session = (
            await session.execute(
                select(RemoteAssistanceSession).where(
                    RemoteAssistanceSession.company_id == tenant.company_id,
                    RemoteAssistanceSession.grant_id == existing_any_tenant.id,
                )
                .order_by(
                    RemoteAssistanceSession.requested_at,
                    RemoteAssistanceSession.id,
                )
                .limit(1)
            )
        ).scalar_one()
        actual_grant_ttl = int(
            (existing_any_tenant.expires_at - existing_any_tenant.requested_at).total_seconds()
        )
        if (
            existing_any_tenant.client_installation_id != installation.id
            or existing_any_tenant.requested_for_user_id != installation.last_user_id
            or existing_any_tenant.kind != payload.grant_kind
            or actual_grant_ttl != payload.grant_ttl_seconds
            or initial_session.duration_seconds != payload.session_ttl_seconds
        ):
            raise ConflictError("The request id was already used with different support terms.")
        names = await _names(
            session,
            {
                existing_any_tenant.requested_by_user_id,
                existing_any_tenant.requested_for_user_id,
                existing_any_tenant.responded_by_user_id,
                initial_session.requested_by_user_id,
                initial_session.started_by_user_id,
                initial_session.ended_by_user_id,
            },
        )
        return RemoteRequestRead(
            grant=_grant_read(
                existing_any_tenant,
                installation_id=installation.installation_id,
                names=names,
            ),
            session=_session_read(
                initial_session,
                installation_id=installation.installation_id,
                names=names,
                next_sequence=await _next_sequence(session, initial_session.id),
            ),
        )

    await _require_active_device_key(session, installation=installation)
    if installation.remote_support_capability in {None, "unsupported"}:
        raise ConflictError(
            "This registered Android device has not advertised remote-assistance support."
        )
    _require_device_online(installation, now=now)
    open_grant = (
        await session.execute(
            select(RemoteAssistanceGrant.id).where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.client_installation_id == installation.id,
                RemoteAssistanceGrant.status.in_(("requested", "active")),
            )
        )
    ).scalar_one_or_none()
    if open_grant is not None:
        raise ConflictError("This device already has a requested or active support grant.")
    open_session = (
        await session.execute(
            select(RemoteAssistanceSession.id).where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.client_installation_id == installation.id,
                RemoteAssistanceSession.status.in_(("requested", "active")),
            )
        )
    ).scalar_one_or_none()
    if open_session is not None:
        raise ConflictError("This device already has a requested or active support session.")

    if installation.last_user_id is None:
        raise ConflictError("The tablet has no authenticated user for a consent request.")
    grant_expires = now + timedelta(seconds=payload.grant_ttl_seconds)
    grant = RemoteAssistanceGrant(
        id=payload.request_id,
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        requested_by_user_id=tenant.user_id,
        requested_for_user_id=installation.last_user_id,
        kind=payload.grant_kind,
        status="requested",
        requested_at=now,
        expires_at=grant_expires,
    )
    support_session = RemoteAssistanceSession(
        id=uuid4(),
        company_id=tenant.company_id,
        grant_id=grant.id,
        client_installation_id=installation.id,
        requested_by_user_id=tenant.user_id,
        status="requested",
        duration_seconds=payload.session_ttl_seconds,
        requested_at=now,
        request_expires_at=min(
            grant_expires,
            now + timedelta(seconds=settings.remote_assistance_request_ttl_seconds),
        ),
    )
    session.add_all([grant, support_session])
    ref = _device_ref(tenant.company_id, installation.installation_id)
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.grant.requested",
        entity_type="RemoteAssistanceGrant",
        entity_id=grant.id,
        device_ref=ref,
        after_status="requested",
        extra={"kind": grant.kind},
    )
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.session.requested",
        entity_type="RemoteAssistanceSession",
        entity_id=support_session.id,
        device_ref=ref,
        after_status="requested",
    )
    names = await _names(session, {tenant.user_id, grant.requested_for_user_id})
    return RemoteRequestRead(
        grant=_grant_read(grant, installation_id=installation.installation_id, names=names),
        session=_session_read(
            support_session,
            installation_id=installation.installation_id,
            names=names,
            next_sequence=1,
        ),
    )


@router.post("/sessions", response_model=SessionRead)
async def create_session(
    payload: SessionCreate,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> SessionRead:
    settings = get_settings()
    if payload.session_ttl_seconds > settings.remote_assistance_session_max_seconds:
        raise ValidationError("session_ttl_seconds exceeds the configured session maximum.")
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
    )
    await _lock_device(session, tenant.company_id, installation.id)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(session, installation=installation, now=now)
    existing = (
        await session.execute(
            select(RemoteAssistanceSession).where(RemoteAssistanceSession.id == payload.session_id)
        )
    ).scalar_one_or_none()
    if existing is not None:
        if (
            existing.company_id != tenant.company_id
            or existing.client_installation_id != installation.id
            or existing.grant_id != payload.grant_id
            or existing.duration_seconds != payload.session_ttl_seconds
        ):
            raise ConflictError("The session id was already used with different support terms.")
        return await _session_response(session, existing, installation)

    await _require_active_device_key(session, installation=installation)
    grant = (
        await session.execute(
            select(RemoteAssistanceGrant)
            .where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.id == payload.grant_id,
                RemoteAssistanceGrant.client_installation_id == installation.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if grant is None:
        raise NotFoundError("Active anytime support grant not found.")
    if grant.kind != "anytime" or grant.status != "active" or grant.expires_at <= now:
        raise ConflictError("A currently active anytime grant is required for another session.")
    _require_current_consent_user(grant, installation)
    open_session = (
        await session.execute(
            select(RemoteAssistanceSession.id).where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.client_installation_id == installation.id,
                RemoteAssistanceSession.status.in_(("requested", "active")),
            )
        )
    ).scalar_one_or_none()
    if open_session is not None:
        raise ConflictError("This device already has a requested or active support session.")
    support_session = RemoteAssistanceSession(
        id=payload.session_id,
        company_id=tenant.company_id,
        grant_id=grant.id,
        client_installation_id=installation.id,
        requested_by_user_id=tenant.user_id,
        status="requested",
        duration_seconds=payload.session_ttl_seconds,
        requested_at=now,
        request_expires_at=min(
            grant.expires_at,
            now + timedelta(seconds=settings.remote_assistance_request_ttl_seconds),
        ),
    )
    session.add(support_session)
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.session.requested",
        entity_type="RemoteAssistanceSession",
        entity_id=support_session.id,
        device_ref=_device_ref(tenant.company_id, installation.installation_id),
        after_status="requested",
    )
    return await _session_response(session, support_session, installation)


@router.post("/sessions/{session_id}/start", response_model=SessionRead)
async def start_session(
    session_id: UUID,
    payload: SessionStart,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> SessionRead:
    now = datetime.now(UTC)
    support_scope = (
        await session.execute(
            select(
                RemoteAssistanceSession.client_installation_id,
                RemoteAssistanceSession.grant_id,
            )
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
            )
        )
    ).one_or_none()
    if support_scope is None:
        raise NotFoundError("Support session not found.")
    client_installation_id, grant_id = support_scope
    await _lock_device(session, tenant.company_id, client_installation_id)
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(ClientInstallation.id == client_installation_id)
            .with_for_update()
        )
    ).scalar_one()
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(session, installation=installation, now=now)
    grant = (
        await session.execute(
            select(RemoteAssistanceGrant)
            .where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.id == grant_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    support_session = (
        await session.execute(
            select(RemoteAssistanceSession)
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    if support_session.start_id is not None:
        if support_session.start_id != payload.start_id:
            raise ConflictError("This session was already started by a different action.")
        return await _session_response(session, support_session, installation)
    await _require_active_device_key(session, installation=installation)
    reused_start_id = (
        await session.execute(
            select(RemoteAssistanceSession.id).where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.start_id == payload.start_id,
                RemoteAssistanceSession.id != support_session.id,
            )
        )
    ).scalar_one_or_none()
    if reused_start_id is not None:
        raise ConflictError("The start action id was already used for another support session.")
    if support_session.status != "requested" or support_session.request_expires_at <= now:
        raise ConflictError("This support session is no longer awaiting start.")
    if grant.status != "active" or grant.expires_at <= now:
        raise ConflictError("The device user has not granted active support access.")
    _require_current_consent_user(grant, installation)
    _require_device_ready(installation, now=now)
    await ensure_relay_available()

    support_session.status = "active"
    support_session.started_at = now
    support_session.started_by_user_id = tenant.user_id
    support_session.start_id = payload.start_id
    requested_expiry = now + timedelta(seconds=support_session.duration_seconds)
    support_session.expires_at = (
        requested_expiry if grant.kind == "one_time" else min(requested_expiry, grant.expires_at)
    )
    ref = _device_ref(tenant.company_id, installation.installation_id)
    if grant.kind == "one_time":
        grant.status = "consumed"
        grant.consumed_at = now
        _audit(
            session,
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            action="remote_assistance.grant.consumed",
            entity_type="RemoteAssistanceGrant",
            entity_id=grant.id,
            device_ref=ref,
            before_status="active",
            after_status="consumed",
        )
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.session.started",
        entity_type="RemoteAssistanceSession",
        entity_id=support_session.id,
        device_ref=ref,
        before_status="requested",
        after_status="active",
    )
    return await _session_response(session, support_session, installation)


@router.get("/sessions", response_model=SessionPage)
async def list_sessions(
    response: Response,
    session: SessionDep,
    tenant: AdminTenantDep,
    installation_id: Annotated[UUID | None, Query()] = None,
    status: Annotated[SessionStatus | None, Query()] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
    offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
) -> SessionPage:
    response.headers["Cache-Control"] = "private, no-store"
    await _expire_stale(session, company_id=tenant.company_id, now=datetime.now(UTC))
    predicates = [RemoteAssistanceSession.company_id == tenant.company_id]
    if installation_id is not None:
        installation = await _installation_by_public_id(
            session,
            company_id=tenant.company_id,
            installation_id=installation_id,
        )
        predicates.append(RemoteAssistanceSession.client_installation_id == installation.id)
    if status is not None:
        predicates.append(RemoteAssistanceSession.status == status)
    rows = (
        (
            await session.execute(
                select(RemoteAssistanceSession)
                .where(*predicates)
                .order_by(RemoteAssistanceSession.requested_at.desc())
                .offset(offset)
                .limit(limit)
            )
        )
        .scalars()
        .all()
    )
    total = int(
        (
            await session.execute(select(func.count(RemoteAssistanceSession.id)).where(*predicates))
        ).scalar_one()
    )
    installations = {
        row.id: row.installation_id
        for row in (
            (
                await session.execute(
                    select(ClientInstallation).where(
                        ClientInstallation.id.in_({item.client_installation_id for item in rows})
                    )
                )
            )
            .scalars()
            .all()
            if rows
            else []
        )
    }
    names = await _names(
        session,
        {
            actor
            for row in rows
            for actor in (
                row.requested_by_user_id,
                row.started_by_user_id,
                row.ended_by_user_id,
            )
        },
    )
    sequence_rows = (
        (
            await session.execute(
                select(
                    RemoteAssistanceCommand.session_id,
                    func.coalesce(func.max(RemoteAssistanceCommand.sequence), 0),
                )
                .where(RemoteAssistanceCommand.session_id.in_({row.id for row in rows}))
                .group_by(RemoteAssistanceCommand.session_id)
            )
        ).all()
        if rows
        else []
    )
    next_sequences = {row_id: int(maximum) + 1 for row_id, maximum in sequence_rows}
    return SessionPage(
        total=total,
        limit=limit,
        offset=offset,
        items=[
            _session_read(
                row,
                installation_id=installations[row.client_installation_id],
                names=names,
                next_sequence=next_sequences.get(row.id, 1),
            )
            for row in rows
        ],
    )


@router.post("/grants/{grant_id}/revoke", response_model=GrantRead)
async def owner_revoke_grant(
    grant_id: UUID,
    payload: OwnerGrantRevokeWrite,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> GrantRead:
    now = datetime.now(UTC)
    client_installation_id = (
        await session.execute(
            select(RemoteAssistanceGrant.client_installation_id)
            .where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.id == grant_id,
            )
        )
    ).scalar_one_or_none()
    if client_installation_id is None:
        raise NotFoundError("Support grant not found.")
    await _lock_device(session, tenant.company_id, client_installation_id)
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(ClientInstallation.id == client_installation_id)
            .with_for_update()
        )
    ).scalar_one()
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(session, installation=installation, now=now)
    grant = (
        await session.execute(
            select(RemoteAssistanceGrant)
            .where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.id == grant_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    await _revoke_grant(
        session,
        row=grant,
        installation=installation,
        actor_user_id=tenant.user_id,
        revocation_id=payload.revoke_id,
    )
    await delete_latest_frame_for_grant(session, grant)
    return await _grant_response(session, grant, installation)


@router.post("/sessions/{session_id}/end", response_model=SessionRead)
async def owner_end_session(
    session_id: UUID,
    payload: OwnerEndWrite,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> SessionRead:
    now = datetime.now(UTC)
    client_installation_id = (
        await session.execute(
            select(RemoteAssistanceSession.client_installation_id)
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
            )
        )
    ).scalar_one_or_none()
    if client_installation_id is None:
        raise NotFoundError("Support session not found.")
    await _lock_device(session, tenant.company_id, client_installation_id)
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(ClientInstallation.id == client_installation_id)
            .with_for_update()
        )
    ).scalar_one()
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(session, installation=installation, now=now)
    support_session = (
        await session.execute(
            select(RemoteAssistanceSession)
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    if support_session.status in {"requested", "active"}:
        await _end_session(
            session,
            row=support_session,
            installation=installation,
            actor_user_id=tenant.user_id,
            end_id=payload.end_id,
            reason="owner_ended",
        )
    elif support_session.end_id != payload.end_id or support_session.end_reason != "owner_ended":
        raise ConflictError("This support session already has a different terminal outcome.")
    await delete_latest_frame(company_id=tenant.company_id, session_id=support_session.id)
    return await _session_response(session, support_session, installation)


@router.post("/sessions/{session_id}/commands", response_model=CommandRead)
async def issue_command(
    session_id: UUID,
    payload: CommandCreate,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> CommandRead:
    now = datetime.now(UTC)
    client_installation_id = (
        await session.execute(
            select(RemoteAssistanceSession.client_installation_id)
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
            )
        )
    ).scalar_one_or_none()
    if client_installation_id is None:
        raise NotFoundError("Active support session not found.")
    await _lock_device(session, tenant.company_id, client_installation_id)
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(ClientInstallation.id == client_installation_id)
            .with_for_update()
        )
    ).scalar_one()
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(session, installation=installation, now=now)
    support_session = (
        await session.execute(
            select(RemoteAssistanceSession)
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    existing = (
        await session.execute(
            select(RemoteAssistanceCommand).where(RemoteAssistanceCommand.id == payload.command_id)
        )
    ).scalar_one_or_none()
    if existing is not None:
        if (
            existing.company_id != tenant.company_id
            or existing.session_id != session_id
            or existing.sequence != payload.sequence
            or existing.command_type != payload.type
            or existing.module != payload.module
        ):
            raise ConflictError("The command id was already used with a different command.")
        return _command_read(existing)
    await _require_active_device_key(session, installation=installation)
    if support_session.status != "active" or (
        support_session.expires_at is not None and support_session.expires_at <= now
    ):
        raise ConflictError("Commands require an active, unexpired support session.")
    command_grant = (
        await session.execute(
            select(RemoteAssistanceGrant).where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.id == support_session.grant_id,
            )
        )
    ).scalar_one()
    _require_current_consent_user(command_grant, installation)
    pending_command = (
        await session.execute(
            select(RemoteAssistanceCommand.id).where(
                RemoteAssistanceCommand.company_id == tenant.company_id,
                RemoteAssistanceCommand.session_id == support_session.id,
                RemoteAssistanceCommand.status == "pending",
            )
        )
    ).scalar_one_or_none()
    if pending_command is not None:
        raise ConflictError(
            "Resolve the pending support command before issuing another.",
            details={"existing_command_id": str(pending_command)},
        )
    latest_command = (
        await session.execute(
            select(RemoteAssistanceCommand.id, RemoteAssistanceCommand.issued_at)
            .where(
                RemoteAssistanceCommand.company_id == tenant.company_id,
                RemoteAssistanceCommand.session_id == support_session.id,
            )
            .order_by(RemoteAssistanceCommand.issued_at.desc(), RemoteAssistanceCommand.id.desc())
            .limit(1)
        )
    ).one_or_none()
    if (
        latest_command is not None
        and latest_command.issued_at
        > now - timedelta(seconds=_COMMAND_ISSUE_COOLDOWN_SECONDS)
    ):
        raise _command_cooldown_conflict(
            message="Support commands are being issued too quickly.",
            issued_at=latest_command.issued_at,
            now=now,
            cooldown_seconds=_COMMAND_ISSUE_COOLDOWN_SECONDS,
            existing_command_id=latest_command.id,
        )
    if payload.type == "collect_diagnostics":
        latest_diagnostics = (
            await session.execute(
                select(RemoteAssistanceCommand.id, RemoteAssistanceCommand.issued_at)
                .where(
                    RemoteAssistanceCommand.company_id == tenant.company_id,
                    RemoteAssistanceCommand.session_id == support_session.id,
                    RemoteAssistanceCommand.command_type == "collect_diagnostics",
                )
                .order_by(
                    RemoteAssistanceCommand.issued_at.desc(),
                    RemoteAssistanceCommand.id.desc(),
                )
                .limit(1)
            )
        ).one_or_none()
        if (
            latest_diagnostics is not None
            and latest_diagnostics.issued_at
            > now - timedelta(seconds=_DIAGNOSTICS_COMMAND_COOLDOWN_SECONDS)
        ):
            raise _command_cooldown_conflict(
                message="A recent diagnostics request already covers this session.",
                issued_at=latest_diagnostics.issued_at,
                now=now,
                cooldown_seconds=_DIAGNOSTICS_COMMAND_COOLDOWN_SECONDS,
                existing_command_id=latest_diagnostics.id,
            )
    _require_device_ready(installation, now=now)
    await ensure_relay_available()
    expected = await _next_sequence(session, support_session.id)
    if payload.sequence != expected:
        raise ConflictError(
            "Command sequence is not the next expected value.",
            details={"expected_sequence": expected},
        )
    command = RemoteAssistanceCommand(
        id=payload.command_id,
        company_id=tenant.company_id,
        session_id=support_session.id,
        sequence=payload.sequence,
        issued_by_user_id=tenant.user_id,
        command_type=payload.type,
        module=payload.module,
        status="pending",
        issued_at=now,
    )
    session.add(command)
    ref = _device_ref(tenant.company_id, installation.installation_id)
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="remote_assistance.command.issued",
        entity_type="RemoteAssistanceCommand",
        entity_id=command.id,
        device_ref=ref,
        after_status="pending",
        extra={"command_type": command.command_type, "sequence": command.sequence},
    )
    return _command_read(command)


@router.get("/sessions/{session_id}/commands/{command_id}", response_model=CommandRead)
async def get_command(
    session_id: UUID,
    command_id: UUID,
    response: Response,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> CommandRead:
    response.headers["Cache-Control"] = "private, no-store"
    await _expire_stale(session, company_id=tenant.company_id, now=datetime.now(UTC))
    command = (
        await session.execute(
            select(RemoteAssistanceCommand).where(
                RemoteAssistanceCommand.company_id == tenant.company_id,
                RemoteAssistanceCommand.session_id == session_id,
                RemoteAssistanceCommand.id == command_id,
            )
        )
    ).scalar_one_or_none()
    if command is None:
        raise NotFoundError("Support command not found for this session.")
    return _command_read(command)


@router.get("/device/state", response_model=DeviceStateRead)
async def device_state(
    request: Request,
    response: Response,
    session: SessionDep,
    tenant: TenantDep,
    installation_id: Annotated[UUID, Query()],
    after_sequence: Annotated[int, Query(ge=0, le=_MAX_COMMANDS_PER_SESSION)] = 0,
) -> DeviceStateRead:
    _require_android_request(request)
    response.headers["Cache-Control"] = "private, no-store"
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=installation_id,
        for_update=True,
    )
    _require_device_actor(installation, tenant)
    await authenticate_device_request(
        request=request,
        session=session,
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        actual_content=b"",
        now=now,
    )
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=now,
        device_actor=tenant,
    )
    grants = (
        (
            await session.execute(
                select(RemoteAssistanceGrant)
                .where(
                    RemoteAssistanceGrant.company_id == tenant.company_id,
                    RemoteAssistanceGrant.client_installation_id == installation.id,
                    RemoteAssistanceGrant.status == "requested",
                    RemoteAssistanceGrant.requested_for_user_id == tenant.user_id,
                )
                .order_by(RemoteAssistanceGrant.requested_at)
            )
        )
        .scalars()
        .all()
    )
    support_session = (
        await session.execute(
            select(RemoteAssistanceSession)
            .join(
                RemoteAssistanceGrant,
                RemoteAssistanceGrant.id == RemoteAssistanceSession.grant_id,
            )
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.client_installation_id == installation.id,
                RemoteAssistanceSession.status.in_(("requested", "active")),
                RemoteAssistanceGrant.requested_for_user_id == tenant.user_id,
            )
            .order_by(RemoteAssistanceSession.requested_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    commands: list[RemoteAssistanceCommand] = []
    if support_session is not None:
        commands = list(
            (
                await session.execute(
                    select(RemoteAssistanceCommand)
                    .where(
                        RemoteAssistanceCommand.company_id == tenant.company_id,
                        RemoteAssistanceCommand.session_id == support_session.id,
                        RemoteAssistanceCommand.status == "pending",
                        RemoteAssistanceCommand.sequence > after_sequence,
                    )
                    .order_by(RemoteAssistanceCommand.sequence)
                )
            )
            .scalars()
            .all()
        )
        if support_session.status == "active":
            await ensure_relay_available()
    actor_ids = {
        actor
        for grant in grants
        for actor in (
            grant.requested_by_user_id,
            grant.requested_for_user_id,
            grant.responded_by_user_id,
        )
    }
    if support_session is not None:
        actor_ids.update(
            {
                support_session.requested_by_user_id,
                support_session.started_by_user_id,
                support_session.ended_by_user_id,
            }
        )
    names = await _names(session, actor_ids)
    return DeviceStateRead(
        server_time=now,
        pending_grants=[
            _grant_read(grant, installation_id=installation.installation_id, names=names)
            for grant in grants
        ],
        session=(
            _session_read(
                support_session,
                installation_id=installation.installation_id,
                names=names,
                next_sequence=await _next_sequence(session, support_session.id),
            )
            if support_session is not None
            else None
        ),
        commands=[_command_read(command) for command in commands],
    )


@router.post("/device/grants/{grant_id}/decision", response_model=GrantRead)
async def decide_grant(
    request: Request,
    grant_id: UUID,
    payload: GrantDecisionWrite,
    session: SessionDep,
    tenant: TenantDep,
) -> GrantRead:
    _require_android_request(request)
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    _require_device_mutation_actor(installation, tenant)
    await _authenticate_device_json(
        request=request,
        session=session,
        tenant=tenant,
        installation=installation,
        now=now,
    )
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=now,
        device_actor=tenant,
    )
    grant = (
        await session.execute(
            select(RemoteAssistanceGrant)
            .where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.id == grant_id,
                RemoteAssistanceGrant.client_installation_id == installation.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if grant is None:
        raise NotFoundError("Support grant request not found for this device.")
    reused_decision_id = (
        await session.execute(
            select(RemoteAssistanceGrant.id).where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.decision_id == payload.decision_id,
                RemoteAssistanceGrant.id != grant.id,
            )
        )
    ).scalar_one_or_none()
    if reused_decision_id is not None:
        raise ConflictError("The decision id was already used for another support grant.")
    desired_status = "active" if payload.decision == "accepted" else "declined"
    if grant.decision_id is not None:
        recorded_decision = "declined" if grant.status == "declined" else "accepted"
        if grant.decision_id != payload.decision_id or payload.decision != recorded_decision:
            raise ConflictError("This grant request already has a different decision.")
        return await _grant_response(session, grant, installation)
    if grant.status != "requested" or grant.expires_at <= now:
        raise RemoteActionGoneError(
            "This support grant request is no longer awaiting a decision."
        )
    if (
        grant.requested_for_user_id != tenant.user_id
        or grant.requested_for_user_id != installation.last_user_id
    ):
        raise ConflictError("This support request was issued for a different tablet user.")
    grant.status = desired_status
    grant.responded_at = now
    grant.responded_by_user_id = tenant.user_id
    grant.decision_id = payload.decision_id
    ref = _device_ref(tenant.company_id, installation.installation_id)
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action=f"remote_assistance.grant.{payload.decision}",
        entity_type="RemoteAssistanceGrant",
        entity_id=grant.id,
        device_ref=ref,
        before_status="requested",
        after_status=desired_status,
    )
    if payload.decision == "declined":
        initial_session = (
            await session.execute(
                select(RemoteAssistanceSession)
                .where(
                    RemoteAssistanceSession.company_id == tenant.company_id,
                    RemoteAssistanceSession.grant_id == grant.id,
                    RemoteAssistanceSession.status == "requested",
                )
                .with_for_update()
            )
        ).scalar_one_or_none()
        if initial_session is not None:
            await _end_session(
                session,
                row=initial_session,
                installation=installation,
                actor_user_id=tenant.user_id,
                end_id=payload.decision_id,
                reason="grant_declined",
            )
    return await _grant_response(session, grant, installation)


@router.post("/device/grants/{grant_id}/revoke", response_model=GrantRead)
async def device_revoke_grant(
    request: Request,
    grant_id: UUID,
    payload: DeviceGrantRevokeWrite,
    session: SessionDep,
    tenant: TenantDep,
) -> GrantRead:
    _require_android_request(request)
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
    )
    await _lock_device(session, tenant.company_id, installation.id)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    _require_device_mutation_actor(installation, tenant)
    await _authenticate_device_json(
        request=request,
        session=session,
        tenant=tenant,
        installation=installation,
    )
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=now,
        device_actor=tenant,
    )
    grant = (
        await session.execute(
            select(RemoteAssistanceGrant)
            .where(
                RemoteAssistanceGrant.company_id == tenant.company_id,
                RemoteAssistanceGrant.id == grant_id,
                RemoteAssistanceGrant.client_installation_id == installation.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if grant is None:
        raise NotFoundError("Support grant not found for this device.")
    if grant.status == "revoked" and grant.revocation_id != payload.revocation_id:
        raise RemoteActionGoneError("This support grant was already revoked.")
    if grant.status not in {"requested", "active", "consumed", "revoked"}:
        raise RemoteActionGoneError("This support grant is no longer revocable.")
    await _revoke_grant(
        session,
        row=grant,
        installation=installation,
        actor_user_id=tenant.user_id,
        revocation_id=payload.revocation_id,
    )
    await delete_latest_frame_for_grant(session, grant)
    return await _grant_response(session, grant, installation)


async def delete_latest_frame_for_grant(
    session: AsyncSession,
    grant: RemoteAssistanceGrant,
) -> None:
    # At most one session per device can be requested/active.  After revoke it
    # is already terminal, so evict only the latest session that could have
    # owned the five-second frame rather than scanning all grant history.
    session_ids = list(
        (
            await session.execute(
                select(RemoteAssistanceSession.id)
                .where(
                    RemoteAssistanceSession.company_id == grant.company_id,
                    RemoteAssistanceSession.grant_id == grant.id,
                )
                .order_by(RemoteAssistanceSession.requested_at.desc())
                .limit(1)
            )
        ).scalars()
    )
    for session_id in session_ids:
        await delete_latest_frame(company_id=grant.company_id, session_id=session_id)


@router.post("/device/sessions/{session_id}/end", response_model=SessionRead)
async def device_end_session(
    request: Request,
    session_id: UUID,
    payload: DeviceSessionEndWrite,
    session: SessionDep,
    tenant: TenantDep,
) -> SessionRead:
    _require_android_request(request)
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
    )
    await _lock_device(session, tenant.company_id, installation.id)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    _require_device_mutation_actor(installation, tenant)
    await _authenticate_device_json(
        request=request,
        session=session,
        tenant=tenant,
        installation=installation,
    )
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=now,
        device_actor=tenant,
    )
    support_session = (
        await session.execute(
            select(RemoteAssistanceSession)
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
                RemoteAssistanceSession.client_installation_id == installation.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if support_session is None:
        raise NotFoundError("Support session not found for this device.")
    if support_session.end_id is not None:
        if (
            support_session.end_id == payload.end_id
            and support_session.end_reason == payload.reason
        ):
            return await _session_response(session, support_session, installation)
        if support_session.status == "ended":
            if support_session.end_reason in get_args(DeviceEndReason):
                raise ConflictError(
                    "This end action id or reason conflicts with the recorded device outcome."
                )
            raise RemoteActionGoneError(
                "This support session was ended by another terminal action."
            )
    if support_session.status == "expired":
        raise RemoteActionGoneError("This support session already expired.")
    if support_session.status in {"requested", "active"}:
        await _end_session(
            session,
            row=support_session,
            installation=installation,
            actor_user_id=tenant.user_id,
            end_id=payload.end_id,
            reason=payload.reason,
        )
    await delete_latest_frame(company_id=tenant.company_id, session_id=support_session.id)
    return await _session_response(session, support_session, installation)


@router.post("/device/commands/{command_id}/result", response_model=CommandRead)
async def command_result(
    request: Request,
    command_id: UUID,
    payload: CommandResultWrite,
    session: SessionDep,
    tenant: TenantDep,
) -> CommandRead:
    _require_android_request(request)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=payload.installation_id,
        for_update=True,
    )
    _require_device_mutation_actor(installation, tenant)
    await _authenticate_device_json(
        request=request,
        session=session,
        tenant=tenant,
        installation=installation,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=datetime.now(UTC),
        device_actor=tenant,
    )
    row = (
        await session.execute(
            select(RemoteAssistanceCommand, RemoteAssistanceSession)
            .join(
                RemoteAssistanceSession,
                RemoteAssistanceSession.id == RemoteAssistanceCommand.session_id,
            )
            .where(
                RemoteAssistanceCommand.company_id == tenant.company_id,
                RemoteAssistanceCommand.id == command_id,
                RemoteAssistanceSession.client_installation_id == installation.id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("Support command not found for this device.")
    command, _support_session = row
    if command.sequence != payload.sequence:
        raise ConflictError("The command sequence does not match this command id.")
    if command.status != "pending":
        if (
            command.status != payload.outcome
            or command.rejection_reason_code != payload.reason_code
        ):
            if command.rejection_reason_code == "session_ended":
                raise RemoteActionGoneError(
                    "This support command became terminal when its session ended."
                )
            raise ConflictError("This command already has a different result.")
        return _command_read(command)
    now = datetime.now(UTC)
    command.status = payload.outcome
    command.resolved_at = now
    command.resolved_by_user_id = tenant.user_id
    command.rejection_reason_code = payload.reason_code
    _audit(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action=f"remote_assistance.command.{payload.outcome}",
        entity_type="RemoteAssistanceCommand",
        entity_id=command.id,
        device_ref=_device_ref(tenant.company_id, installation.installation_id),
        before_status="pending",
        after_status=payload.outcome,
        extra={"reason_code": payload.reason_code, "sequence": command.sequence},
    )
    return _command_read(command)


@router.put("/device/sessions/{session_id}/frame", response_model=FrameAcceptedRead)
async def upload_frame(
    request: Request,
    session_id: UUID,
    session: SessionDep,
    tenant: TenantDep,
    x_installation_id: Annotated[UUID, Header(alias="X-Installation-Id")],
    x_frame_id: Annotated[UUID, Header(alias="X-Frame-Id")],
    x_frame_sequence: Annotated[
        int,
        Header(alias="X-Frame-Sequence", ge=1, le=_FRAME_SEQUENCE_MAX),
    ],
    x_frame_width: Annotated[int, Header(alias="X-Frame-Width", ge=1)],
    x_frame_height: Annotated[int, Header(alias="X-Frame-Height", ge=1)],
    x_erp_frame_redacted: Annotated[str, Header(alias="X-ERP-Frame-Redacted")],
    content_length: Annotated[int | None, Header(alias="Content-Length", ge=0)] = None,
) -> FrameAcceptedRead:
    _require_android_request(request)
    try:
        _uuid4(x_installation_id, label="X-Installation-Id")
        _uuid4(x_frame_id, label="X-Frame-Id")
    except ValueError as exc:
        raise ValidationError(str(exc)) from exc
    if x_erp_frame_redacted.strip().lower() != "true":
        raise ValidationError(
            "Every support frame must pass the ERP-window privacy and redaction pipeline."
        )
    media_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if media_type != "image/jpeg":
        raise ValidationError("Support frames must use Content-Type image/jpeg.")
    settings = get_settings()
    if content_length is not None and content_length > settings.remote_assistance_frame_max_bytes:
        raise ValidationError(
            "The support frame exceeds the configured byte limit.",
            details={"max_bytes": settings.remote_assistance_frame_max_bytes},
        )
    # Phase 1 authenticates the signed request and snapshots authority in a
    # short transaction. No installation/session lock is retained while an
    # untrusted client streams bytes or while Pillow performs CPU work.
    now = datetime.now(UTC)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=x_installation_id,
    )
    device_proof = await authenticate_device_request(
        request=request,
        session=session,
        company_id=tenant.company_id,
        client_installation_id=installation.id,
        # Verify the signed asserted hash and claim the nonce before admission;
        # compare that hash to the bounded stream immediately after reading it.
        actual_content=None,
        now=now,
    )
    await _lock_device(session, tenant.company_id, installation.id)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=x_installation_id,
        for_update=True,
    )
    _require_device_mutation_actor(installation, tenant)
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=now,
        device_actor=tenant,
    )
    _require_device_ready(installation, now=now)
    await _require_exact_active_device_key(
        session,
        installation=installation,
        key_id=device_proof.device_key.id,
    )
    frame_scope = await _locked_active_frame_session(
        session,
        company_id=tenant.company_id,
        session_id=session_id,
        installation=installation,
        now=now,
    )
    if frame_scope is None:
        raise RemoteActionGoneError("The support session is no longer active for this user.")
    support_session, _grant = frame_scope
    await session.commit()

    # Admission is Redis-backed and fail-closed before body streaming or
    # Pillow work, so a compromised authenticated tablet cannot turn malformed
    # frames into an unbounded CPU queue shared with POS/payment requests.
    await admit_frame_upload(
        company_id=tenant.company_id,
        session_id=support_session.id,
        frame_id=x_frame_id,
        sequence=x_frame_sequence,
    )
    try:
        async with asyncio.timeout(settings.remote_assistance_frame_read_timeout_seconds):
            content = await _read_bounded_body(
                request,
                max_bytes=settings.remote_assistance_frame_max_bytes,
            )
    except TimeoutError as exc:
        raise RemoteFrameTimeoutError(
            "The support frame upload did not finish within the allowed time."
        ) from exc
    verify_actual_content(device_proof, content)
    # The two-worker global limiter bounds CPU/memory pressure for the small
    # VPS; per-session Redis admission prevents one session filling both slots.
    frame = await to_thread.run_sync(
        partial(
            validate_and_sanitize_jpeg,
            content,
            declared_width=x_frame_width,
            declared_height=x_frame_height,
        ),
        limiter=_FRAME_DECODER_LIMITER,
    )

    # Phase 2 linearizes frame publication against Stop, key rotation/revoke,
    # grant revoke, expiry, and account handoff. Only the final fail-fast Redis
    # store occurs while these locks are held; body and image work are outside.
    final_now = datetime.now(UTC)
    await _lock_device(session, tenant.company_id, installation.id)
    installation = await _installation_by_public_id(
        session,
        company_id=tenant.company_id,
        installation_id=x_installation_id,
        for_update=True,
    )
    _require_device_mutation_actor(installation, tenant)
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=final_now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(
        session,
        installation=installation,
        now=final_now,
        device_actor=tenant,
    )
    _require_device_ready(installation, now=final_now)
    await _require_exact_active_device_key(
        session,
        installation=installation,
        key_id=device_proof.device_key.id,
    )
    if (
        await _locked_active_frame_session(
            session,
            company_id=tenant.company_id,
            session_id=session_id,
            installation=installation,
            now=final_now,
        )
        is None
    ):
        raise RemoteActionGoneError("The support session ended before the frame was published.")
    metadata = await store_latest_frame(
        company_id=tenant.company_id,
        session_id=support_session.id,
        frame_id=x_frame_id,
        sequence=x_frame_sequence,
        frame=frame,
    )
    return FrameAcceptedRead(
        frame_id=metadata.frame_id,
        sequence=metadata.sequence,
        width=metadata.width,
        height=metadata.height,
        received_at=metadata.received_at,
        expires_at=metadata.expires_at,
    )


@router.get("/sessions/{session_id}/frame")
async def latest_frame(
    session_id: UUID,
    session: SessionDep,
    tenant: AdminTenantDep,
) -> BinaryResponse:
    now = datetime.now(UTC)
    client_installation_id = (
        await session.execute(
            select(RemoteAssistanceSession.client_installation_id)
            .where(
                RemoteAssistanceSession.company_id == tenant.company_id,
                RemoteAssistanceSession.id == session_id,
            )
        )
    ).scalar_one_or_none()
    if client_installation_id is None:
        raise NotFoundError("Active support session not found.")
    await _lock_device(session, tenant.company_id, client_installation_id)
    installation = (
        await session.execute(
            select(ClientInstallation)
            .where(
                ClientInstallation.company_id == tenant.company_id,
                ClientInstallation.id == client_installation_id,
            )
            .with_for_update()
        )
    ).scalar_one()
    await _expire_stale(
        session,
        company_id=tenant.company_id,
        now=now,
        client_installation_id=installation.id,
    )
    await _reconcile_consent_user(session, installation=installation, now=now)
    await _require_active_device_key(session, installation=installation)
    if (
        await _locked_active_frame_session(
            session,
            company_id=tenant.company_id,
            session_id=session_id,
            installation=installation,
            now=now,
        )
        is None
    ):
        raise NotFoundError("Active support session not found.")
    # Keep the device/session locks through the fail-fast Redis read so revoke,
    # Stop, key rotation, and user handoff are linearizable with disclosure.
    frame = await get_latest_frame(company_id=tenant.company_id, session_id=session_id)
    if frame is None:
        raise NotFoundError("No unexpired support frame is available.")
    metadata = frame.metadata
    return BinaryResponse(
        content=frame.content,
        media_type="image/jpeg",
        headers={
            "Cache-Control": "private, no-store, max-age=0",
            "Pragma": "no-cache",
            "X-Content-Type-Options": "nosniff",
            "X-Frame-Id": str(metadata.frame_id),
            "X-Frame-Sequence": str(metadata.sequence),
            "X-Frame-Width": str(metadata.width),
            "X-Frame-Height": str(metadata.height),
            "X-Frame-Received-At": metadata.received_at.isoformat(),
        },
    )


__all__ = [
    "CommandCreate",
    "CommandRead",
    "CommandResultWrite",
    "DeviceHeartbeatWrite",
    "DeviceSessionEndWrite",
    "GrantDecisionWrite",
    "RemoteRequestCreate",
    "SessionCreate",
    "router",
]
