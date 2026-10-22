"""Protected-owner Android release activation and withdrawal controls."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from typing import Literal, cast
from uuid import UUID  # noqa: TC003 - Pydantic resolves this model type at runtime.

from fastapi import APIRouter, Query, Response
from pydantic import BaseModel, Field, model_validator
from sqlalchemy import func, select, text

from app.core.config import get_settings
from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves this dependency at runtime.
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError, ServiceUnavailableError
from app.core.release_control import (  # noqa: TC001 - FastAPI runtime dependency.
    ReleaseControllerDep,
)
from app.core.tenant import TenantContext  # noqa: TC001 - audit DTO runtime type.
from app.models import AndroidRelease, AndroidUpdatePolicy, AuditLog
from app.services.client_updates.policy import (
    DIRECT_RELEASE_LOCK_KEY,
    POLICY_INTEGER_MAX,
    policy_snapshot,
    release_fingerprint,
)
from app.services.client_updates.releases import (
    AndroidReleaseFingerprint,
    ArtifactVerificationError,
    verify_public_apk,
)
from app.services.client_updates.runtime_parity import (
    RuntimeParityError,
    record_verified_runtime_parity_for_public_offer,
    verify_runtime_parity,
)

router = APIRouter()


_DIRECT_RELEASE_LOCK_KEY = DIRECT_RELEASE_LOCK_KEY
_REQUIRED_POLICY_VERIFY_DEADLINE_SECONDS = 120


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
    policy_fingerprint: str


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
        status=cast("Literal['staged', 'active', 'withdrawn']", release.status),
        registered_at=release.registered_at,
        activated_at=release.activated_at,
        activated_by=release.activated_by,
        withdrawn_at=release.withdrawn_at,
        withdrawn_by=release.withdrawn_by,
        updated_at=release.updated_at,
        policy_fingerprint=release_fingerprint(release),
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


class AndroidUpdatePolicyRead(BaseModel):
    mode: Literal["inherit", "optional", "required"]
    revision: int
    environment_minimum_version_code: int
    minimum_supported_version_code: int
    required_release_id: UUID | None
    required_release_fingerprint: str | None
    active_release: AndroidReleaseRead | None
    reason: str | None
    updated_at: datetime | None


class AndroidUpdatePolicyWrite(BaseModel):
    mode: Literal["optional", "required"]
    expected_revision: int = Field(ge=1, le=POLICY_INTEGER_MAX)
    release_id: UUID | None = None
    expected_release_fingerprint: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")
    reason: str = Field(min_length=3, max_length=500)

    @model_validator(mode="after")
    def target_matches_mode(self) -> AndroidUpdatePolicyWrite:
        self.reason = self.reason.strip()
        if len(self.reason) < 3:
            raise ValueError("Give a reason of at least three characters")
        if self.mode == "required" and (
            not self.release_id or not self.expected_release_fingerprint
        ):
            raise ValueError("Review the exact active release before requiring it")
        if self.mode == "optional" and (self.release_id or self.expected_release_fingerprint):
            raise ValueError("Optional policy cannot select an artifact")
        return self


async def _policy_read(session: SessionDep) -> AndroidUpdatePolicyRead:
    row = (
        await session.execute(select(AndroidUpdatePolicy).where(AndroidUpdatePolicy.id == 1))
    ).scalar_one_or_none()
    active = (
        await session.execute(
            select(AndroidRelease).where(
                AndroidRelease.channel == "direct", AndroidRelease.status == "active"
            )
        )
    ).scalar_one_or_none()
    settings = get_settings()
    snapshot = policy_snapshot(
        settings, row, active if row and row.release_id == getattr(active, "id", None) else None
    )
    return AndroidUpdatePolicyRead(
        mode=snapshot.mode,
        revision=snapshot.revision,
        environment_minimum_version_code=settings.android_min_supported_version_code,
        minimum_supported_version_code=snapshot.minimum,
        required_release_id=row.release_id if row else None,
        required_release_fingerprint=row.release_fingerprint if row else None,
        active_release=_read(active) if active else None,
        reason=row.reason if row else None,
        updated_at=row.updated_at if row else None,
    )


async def _guard_required_target(
    session: SessionDep, *, replacing_with: UUID | None = None, withdrawing: UUID | None = None
) -> None:
    policy = (
        await session.execute(
            select(AndroidUpdatePolicy).where(AndroidUpdatePolicy.id == 1).with_for_update()
        )
    ).scalar_one_or_none()
    if (
        policy
        and policy.mode == "required"
        and (
            policy.release_id == withdrawing
            or (replacing_with is not None and policy.release_id != replacing_with)
        )
    ):
        raise ConflictError(
            "This release is required. Set updates to Optional before withdrawing or replacing its recovery target."
        )


@router.get("/android/policy", response_model=AndroidUpdatePolicyRead)
async def get_android_update_policy(
    response: Response, session: SessionDep, _tenant: ReleaseControllerDep
) -> AndroidUpdatePolicyRead:
    response.headers["Cache-Control"] = "private, no-store"
    return await _policy_read(session)


@router.put("/android/policy", response_model=AndroidUpdatePolicyRead)
async def set_android_update_policy(
    payload: AndroidUpdatePolicyWrite,
    response: Response,
    session: SessionDep,
    tenant: ReleaseControllerDep,
) -> AndroidUpdatePolicyRead:
    response.headers["Cache-Control"] = "private, no-store"
    settings = get_settings()
    verified = None
    state = None
    if payload.mode == "required":
        candidate = (
            await session.execute(
                select(AndroidRelease).where(AndroidRelease.id == payload.release_id)
            )
        ).scalar_one_or_none()
        if (
            candidate is None
            or candidate.status != "active"
            or candidate.channel != "direct"
            or release_fingerprint(candidate) != payload.expected_release_fingerprint
        ):
            raise ConflictError(
                "The reviewed active release has changed. Refresh and review it again."
            )
        verified, state = _fingerprint(candidate), _state_token(candidate)
        await session.rollback()
        try:
            async with asyncio.timeout(_REQUIRED_POLICY_VERIFY_DEADLINE_SECONDS):
                await verify_public_apk(
                    verified,
                    configured_update_url=(
                        str(settings.android_update_allowed_origin)
                        if settings.android_update_allowed_origin
                        else None
                    ),
                )
                await verify_runtime_parity(
                    version_name=verified.version_name,
                    source_git_sha=verified.source_git_sha,
                    settings=settings,
                )
        except (ArtifactVerificationError, RuntimeParityError, TimeoutError) as exc:
            raise ServiceUnavailableError(
                "Required update was not changed because its public APK and live release could not be verified."
            ) from exc
    await session.execute(
        text("SELECT pg_advisory_xact_lock(:key)"), {"key": _DIRECT_RELEASE_LOCK_KEY}
    )
    row = (
        await session.execute(
            select(AndroidUpdatePolicy).where(AndroidUpdatePolicy.id == 1).with_for_update()
        )
    ).scalar_one_or_none()
    effective_revision = max(
        settings.client_compatibility_policy_revision, row.revision if row else 0
    )
    if effective_revision != payload.expected_revision:
        raise ConflictError(
            "Update policy changed. Refresh and review the current policy before retrying."
        )
    if effective_revision >= POLICY_INTEGER_MAX:
        raise ConflictError("Update policy revision limit reached. No policy was changed.")
    if verified:
        candidate = (
            await session.execute(
                select(AndroidRelease)
                .where(AndroidRelease.id == payload.release_id)
                .with_for_update()
            )
        ).scalar_one_or_none()
        if (
            candidate is None
            or _fingerprint(candidate) != verified
            or _state_token(candidate) != state
        ):
            raise ConflictError("The verified release changed. Refresh and review it again.")
    before = {
        "mode": row.mode if row else "inherit",
        "revision": effective_revision,
        "release_id": str(row.release_id) if row and row.release_id else None,
    }
    if row is None:
        row = AndroidUpdatePolicy(id=1)
        session.add(row)
    row.mode, row.revision = payload.mode, effective_revision + 1
    row.release_id = payload.release_id
    row.release_fingerprint = payload.expected_release_fingerprint
    row.reason, row.updated_by, row.updated_at = payload.reason, tenant.user_id, datetime.now(UTC)
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            terminal_id=tenant.terminal_id,
            action="android_update_policy_changed",
            entity_type="AndroidUpdatePolicy",
            entity_id="1",
            business_details={
                "update_mode_before": before["mode"],
                "update_mode_after": row.mode,
                "policy_revision": row.revision,
                "minimum_supported_build": max(
                    settings.android_min_supported_version_code,
                    verified.version_code
                    if verified
                    else settings.android_min_supported_version_code,
                ),
                "environment_safety_floor": settings.android_min_supported_version_code,
                "global_installations": True,
                "reason": row.reason,
            },
            before=before,
            after={
                "mode": row.mode,
                "revision": row.revision,
                "release_id": str(row.release_id) if row.release_id else None,
                "release_fingerprint": row.release_fingerprint,
                "reason": row.reason,
                "environment_minimum_version_code": settings.android_min_supported_version_code,
            },
        )
    )
    await session.flush()
    return await _policy_read(session)


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

    try:
        await verify_runtime_parity(
            version_name=before_network.version_name,
            source_git_sha=before_network.source_git_sha,
            settings=settings,
        )
    except RuntimeParityError as exc:
        raise ServiceUnavailableError(
            "This update cannot be offered because the live web and server release "
            "do not yet match the APK, or their identity could not be verified. "
            "Deploy the matching release, check the connection, then retry. "
            "No release state was changed.",
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

    await _guard_required_target(session, replacing_with=candidate.id)

    if candidate.status == "active":
        await record_verified_runtime_parity_for_public_offer(
            version_name=before_network.version_name,
            source_git_sha=before_network.source_git_sha,
            settings=settings,
        )
        return _read(candidate)

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
    await record_verified_runtime_parity_for_public_offer(
        version_name=before_network.version_name,
        source_git_sha=before_network.source_git_sha,
        settings=settings,
    )
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
    await _guard_required_target(session, withdrawing=release.id)
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
