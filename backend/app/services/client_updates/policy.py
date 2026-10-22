"""One authoritative Android policy snapshot per HTTP request.

The singleton is global because the direct APK release registry is global.
Optional-offer runtime probes are deliberately not part of this authority.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
from dataclasses import dataclass, field
from typing import TYPE_CHECKING
from weakref import WeakKeyDictionary

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.core.errors import ServiceUnavailableError
from app.models import AndroidRelease, AndroidUpdatePolicy
from app.services.client_updates.runtime_parity import verify_runtime_parity_for_public_offer

if TYPE_CHECKING:
    from app.core.config import Settings

DIRECT_RELEASE_LOCK_KEY = 7_310_042_021
POLICY_INTEGER_MAX = 2_147_483_647
POLICY_LOOKUP_BUDGET_SECONDS = 0.8


def release_fingerprint(release: AndroidRelease) -> str:
    fields = (
        "id",
        "channel",
        "version_code",
        "version_name",
        "update_url",
        "release_notes",
        "apk_sha256",
        "apk_size_bytes",
        "apk_signing_cert_sha256",
        "manifest_sha256",
        "source_git_sha",
        "source_release_ref",
        "source_workflow_run_id",
        "source_workflow_run_attempt",
    )
    data = {name: str(getattr(release, name)) for name in fields}
    return hashlib.sha256(
        json.dumps(data, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


@dataclass(frozen=True, slots=True)
class AndroidPolicySnapshot:
    revision: int
    minimum: int
    mode: str = "inherit"
    release: dict | None = None
    source_git_sha: str | None = None


def policy_snapshot(
    settings: Settings, row: AndroidUpdatePolicy | None, release: AndroidRelease | None = None
) -> AndroidPolicySnapshot:
    revision = max(settings.client_compatibility_policy_revision, row.revision if row else 0)
    if not 1 <= revision <= POLICY_INTEGER_MAX:
        raise ServiceUnavailableError("Android update policy revision is invalid. Retry later.")
    minimum = settings.android_min_supported_version_code
    if row is None or row.mode == "optional":
        return AndroidPolicySnapshot(revision, minimum, row.mode if row else "inherit")
    if (
        row.mode != "required"
        or release is None
        or release.status != "active"
        or release.channel != "direct"
        or release.version_code < minimum
        or row.release_fingerprint != release_fingerprint(release)
    ):
        raise ServiceUnavailableError(
            "The required Android release cannot be verified. Retry later."
        )
    artifact = {
        name: getattr(release, name)
        for name in (
            "version_code",
            "version_name",
            "update_url",
            "release_notes",
            "apk_sha256",
            "apk_size_bytes",
            "apk_signing_cert_sha256",
        )
    }
    return AndroidPolicySnapshot(
        revision, max(minimum, release.version_code), "required", artifact, release.source_git_sha
    )


@dataclass(slots=True)
class _Lookup:
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    task: asyncio.Task[AndroidPolicySnapshot] | None = None


_lookups: WeakKeyDictionary[asyncio.AbstractEventLoop, dict[tuple[int, int], _Lookup]] = (
    WeakKeyDictionary()
)


async def _load(settings: Settings) -> AndroidPolicySnapshot:
    async with AsyncSessionLocal() as session:
        # Join in one statement so a required target cannot come from another snapshot.
        record = (
            await session.execute(
                select(AndroidUpdatePolicy, AndroidRelease)
                .outerjoin(AndroidRelease, AndroidRelease.id == AndroidUpdatePolicy.release_id)
                .where(AndroidUpdatePolicy.id == 1)
            )
        ).one_or_none()
        return policy_snapshot(
            settings, record[0] if record else None, record[1] if record else None
        )


async def resolve_android_update_policy(settings: Settings) -> AndroidPolicySnapshot:
    """Bound callers, not DB cleanup; one stalled lookup cannot fan out more work."""
    loop = asyncio.get_running_loop()
    settings = settings.model_copy()
    key = (
        settings.android_min_supported_version_code,
        settings.client_compatibility_policy_revision,
    )
    state = _lookups.setdefault(loop, {}).setdefault(key, _Lookup())
    async with state.lock:
        if state.task is None or state.task.done():
            state.task = asyncio.create_task(_load(settings), name="android-policy-read")
            # Retrieve failures even if all waiting clients disconnect.
            state.task.add_done_callback(
                lambda task: None if task.cancelled() else task.exception()
            )
        task = state.task
    try:
        async with asyncio.timeout(POLICY_LOOKUP_BUDGET_SECONDS):
            snapshot = await asyncio.shield(task)
        if snapshot.release is not None:
            # Reuse the bounded, success-cached live-identity probe. A DB row
            # survives deployment rollback and is not current runtime evidence.
            await verify_runtime_parity_for_public_offer(
                version_name=snapshot.release["version_name"],
                source_git_sha=snapshot.source_git_sha,
                settings=settings,
            )
        return snapshot
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        raise ServiceUnavailableError(
            "Android update policy is temporarily unavailable. Saved work is unchanged. Retry shortly."
        ) from exc
