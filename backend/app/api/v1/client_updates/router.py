"""Protected-owner Android release activation and withdrawal controls."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal, cast
from uuid import UUID

from fastapi import APIRouter, Query, Response
from pydantic import BaseModel, Field
from sqlalchemy import func, select, text

from app.core.config import get_settings
from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError, ServiceUnavailableError
from app.core.release_control import ReleaseControllerDep
from app.core.tenant import TenantContext
from app.models import AndroidRelease, AuditLog
from app.services.client_updates.releases import (
    AndroidReleaseFingerprint,
    ArtifactVerificationError,
    verify_public_apk,
)

router = APIRouter()

_DIRECT_RELEASE_LOCK_KEY = 7_310_042_021


class AndroidReleaseRead(BaseModel):
    id: UUID
    channel: Literal["direct"]
    version_code: int
    version_name: str
    update_url: str
    release_notes: str
    apk_sha256: str
    apk_size_bytes: int
    apk_signing_cert_sha256: str
    manifest_sha256: str
    source_git_sha: str
    source_release_ref: str
    # JSON numbers cannot safely represent every PostgreSQL bigint in browser
    # clients.  Preserve the exact workflow run identifier as a decimal string.
    source_workflow_run_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    source_workflow_run_attempt: int
    status: Literal["staged", "active", "withdrawn"]
    registered_at: datetime
    activated_at: datetime | None
    activated_by: UUID | None
    withdrawn_at: datetime | None
    withdrawn_by: UUID | None
    updated_at: datetime


class AndroidReleaseList(BaseModel):
    total: int
    items: list[AndroidReleaseRead]


def _read(release: AndroidRelease) -> AndroidReleaseRead:
    return AndroidReleaseRead(
        id=release.id,
        channel="direct",
        version_code=release.version_code,
        version_name=release.version_name,
        update_url=release.update_url,
        release_notes=release.release_notes,
        apk_sha256=release.apk_sha256,
        apk_size_bytes=release.apk_size_bytes,
        apk_signing_cert_sha256=release.apk_signing_cert_sha256,
        manifest_sha256=release.manifest_sha256,
        source_git_sha=release.source_git_sha,
        source_release_ref=release.source_release_ref,
        source_workflow_run_id=str(release.source_workflow_run_id),
        source_workflow_run_attempt=release.source_workflow_run_attempt,
        status=cast(Literal["staged", "active", "withdrawn"], release.status),
        registered_at=release.registered_at,
        activated_at=release.activated_at,
        activated_by=release.activated_by,
        withdrawn_at=release.withdrawn_at,
        withdrawn_by=release.withdrawn_by,
        updated_at=release.updated_at,
    )


def _fingerprint(release: AndroidRelease) -> AndroidReleaseFingerprint:
    return AndroidReleaseFingerprint(
        id=release.id,
        channel=release.channel,
        version_code=release.version_code,
        version_name=release.version_name,
        update_url=release.update_url,
        release_notes=release.release_notes,
        apk_sha256=release.apk_sha256,
        apk_size_bytes=release.apk_size_bytes,
        apk_signing_cert_sha256=release.apk_signing_cert_sha256,
        manifest_sha256=release.manifest_sha256,
        source_git_sha=release.source_git_sha,
        source_release_ref=release.source_release_ref,
        source_workflow_run_id=release.source_workflow_run_id,
        source_workflow_run_attempt=release.source_workflow_run_attempt,
        status=release.status,
    )


def _state_token(release: AndroidRelease) -> tuple[object, ...]:
    """Detect status-evidence ABA changes while public bytes are verified."""
    return (
        release.status,
        release.activated_at,
        release.activated_by,
        release.withdrawn_at,
        release.withdrawn_by,
        release.updated_at,
    )


def _audit_transition(
    *,
    tenant: TenantContext,
    release: AndroidRelease,
    action: str,
    before_status: str,
) -> AuditLog:
    return AuditLog(
        actor_user_id=tenant.user_id,
        company_id=tenant.company_id,
        terminal_id=tenant.terminal_id,
        action=action,
        entity_type="AndroidRelease",
        entity_id=str(release.id),
        before={
            "status": before_status,
            "version_code": release.version_code,
            "manifest_sha256": release.manifest_sha256,
        },
        after={
            "status": release.status,
            "version_code": release.version_code,
            "manifest_sha256": release.manifest_sha256,
        },
    )


@router.get("/android/releases", response_model=AndroidReleaseList)
async def list_android_releases(
    response: Response,
    session: SessionDep,
    _tenant: ReleaseControllerDep,
    limit: int = Query(default=100, ge=1, le=200),
    offset: int = Query(default=0, ge=0, le=100_000),
) -> AndroidReleaseList:
    """List staged/active/withdrawn artifacts for the protected owner only."""
    response.headers["Cache-Control"] = "private, no-store"
    releases = (
        (
            await session.execute(
                select(AndroidRelease)
                .order_by(AndroidRelease.version_code.desc(), AndroidRelease.registered_at.desc())
                .offset(offset)
                .limit(limit)
            )
        )
        .scalars()
        .all()
    )
    total = int((await session.execute(select(func.count(AndroidRelease.id)))).scalar_one())
    return AndroidReleaseList(total=total, items=[_read(item) for item in releases])


@router.post("/android/releases/{release_id}/activate", response_model=AndroidReleaseRead)
async def activate_android_release(
    release_id: UUID,
    session: SessionDep,
    tenant: ReleaseControllerDep,
) -> AndroidReleaseRead:
    """Verify immutable public bytes, then atomically activate one direct release."""
    candidate = (
        await session.execute(select(AndroidRelease).where(AndroidRelease.id == release_id))
    ).scalar_one_or_none()
    if candidate is None:
        raise NotFoundError("Android release not found")
    before_network = _fingerprint(candidate)
    before_state = _state_token(candidate)
    if candidate.status == "active":
        return _read(candidate)

    settings = get_settings()
    if candidate.version_code < settings.android_min_supported_version_code:
        raise BusinessRuleError(
            "This release is below the server minimum and cannot be offered to clients."
        )

    # Tenant authentication opened a read transaction.  Release it before the
    # potentially slow public download so no DB connection, snapshot, or row
    # lock is retained across network I/O.
    await session.rollback()
    try:
        await verify_public_apk(
            before_network,
            configured_update_url=(
                str(settings.android_update_allowed_origin)
                if settings.android_update_allowed_origin
                else None
            ),
        )
    except ArtifactVerificationError as exc:
        raise ServiceUnavailableError(
            "The public APK could not be verified, so the release remains unchanged.",
            details={"verification_code": exc.code},
        ) from exc

    # Serialize direct-channel state changes only after verification.  The
    # immutable fingerprint is rechecked because status may have changed while
    # bytes were downloaded even though artifact metadata cannot be rewritten.
    await session.execute(
        text("SELECT pg_advisory_xact_lock(:key)"), {"key": _DIRECT_RELEASE_LOCK_KEY}
    )
    candidate = (
        await session.execute(
            select(AndroidRelease).where(AndroidRelease.id == release_id).with_for_update()
        )
    ).scalar_one_or_none()
    if candidate is None:
        raise ConflictError("Android release changed while it was being verified")
    if _fingerprint(candidate) != before_network or _state_token(candidate) != before_state:
        raise ConflictError(
            "Android release state changed while it was being verified. Review and retry."
        )

    now = datetime.now(UTC)
    active_releases = (
        (
            await session.execute(
                select(AndroidRelease)
                .where(
                    AndroidRelease.channel == "direct",
                    AndroidRelease.status == "active",
                    AndroidRelease.id != candidate.id,
                )
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    for active in active_releases:
        previous = active.status
        active.status = "withdrawn"
        active.withdrawn_at = now
        active.withdrawn_by = tenant.user_id
        active.updated_at = now
        session.add(
            _audit_transition(
                tenant=tenant,
                release=active,
                action="android_release_auto_withdraw",
                before_status=previous,
            )
        )

    # Clear the partial unique index before setting the candidate active. Both
    # flushes remain inside this one transaction, so clients never observe the
    # transient no-offer state and a concurrent promoter remains serialized by
    # the advisory lock.
    if active_releases:
        await session.flush()

    previous = candidate.status
    candidate.status = "active"
    candidate.activated_at = now
    candidate.activated_by = tenant.user_id
    candidate.withdrawn_at = None
    candidate.withdrawn_by = None
    candidate.updated_at = now
    session.add(
        _audit_transition(
            tenant=tenant,
            release=candidate,
            action="android_release_activate",
            before_status=previous,
        )
    )
    await session.flush()
    return _read(candidate)


@router.post("/android/releases/{release_id}/withdraw", response_model=AndroidReleaseRead)
async def withdraw_android_release(
    release_id: UUID,
    session: SessionDep,
    tenant: ReleaseControllerDep,
) -> AndroidReleaseRead:
    """Withdraw a staged or active offer without deleting its evidence."""
    await session.execute(
        text("SELECT pg_advisory_xact_lock(:key)"), {"key": _DIRECT_RELEASE_LOCK_KEY}
    )
    release = (
        await session.execute(
            select(AndroidRelease).where(AndroidRelease.id == release_id).with_for_update()
        )
    ).scalar_one_or_none()
    if release is None:
        raise NotFoundError("Android release not found")
    if release.status == "withdrawn":
        return _read(release)

    previous = release.status
    now = datetime.now(UTC)
    release.status = "withdrawn"
    release.withdrawn_at = now
    release.withdrawn_by = tenant.user_id
    release.updated_at = now
    session.add(
        _audit_transition(
            tenant=tenant,
            release=release,
            action="android_release_withdraw",
            before_status=previous,
        )
    )
    await session.flush()
    return _read(release)
