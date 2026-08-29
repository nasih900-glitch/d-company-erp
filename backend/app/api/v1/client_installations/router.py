"""Bounded, authenticated runtime health for native ERP installations."""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal, get_args
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Query, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import func, select, text, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves dependency annotations
from app.core.errors import ClientTelemetryCapacityError, ConflictError
from app.core.permissions import requires
from app.core.tenant import (  # noqa: TC001 - FastAPI resolves dependency annotations
    TenantContext,
    TenantDep,
)
from app.models import ClientInstallation, ClientUpdateEvent, Terminal, User
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


def _capacity_error(*, resource: str, scope: str, limit: int) -> ClientTelemetryCapacityError:
    return ClientTelemetryCapacityError(
        "This shop's retained device-update evidence has reached its safety limit. "
        "Existing records were preserved; ask the protected owner to review support status.",
        details={"resource": resource, "scope": scope, "limit": limit},
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
    payload: ClientInstallationHeartbeat,
    session: SessionDep,
    tenant: TenantDep,
) -> ClientInstallationHeartbeatRead:
    """Upsert one authenticated installation snapshot and append new events.

    Scope comes exclusively from the validated tenant dependency.  There are
    deliberately no company, user, or terminal fields in the request DTO.
    """
    await enforce_client_heartbeat_rate_limit(
        company_id=tenant.company_id,
        user_id=tenant.user_id,
    )
    await _lock_company_telemetry(session, tenant.company_id)

    existing_installation_id = (
        await session.execute(
            select(ClientInstallation.id).where(
                ClientInstallation.company_id == tenant.company_id,
                ClientInstallation.installation_id == payload.installation_id,
            )
        )
    ).scalar_one_or_none()
    if existing_installation_id is None:
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
