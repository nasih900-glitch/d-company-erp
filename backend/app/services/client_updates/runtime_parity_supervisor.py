"""Continuously maintain verified Android-offer runtime attestation."""

from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.core.logging import get_logger
from app.models import AndroidRelease
from app.services.client_updates.runtime_parity import (
    RuntimeParityError,
    invalidate_all_runtime_parity_for_public_offers,
    invalidate_runtime_parity_for_public_offer,
    record_verified_runtime_parity_for_public_offer,
    verify_runtime_parity,
)

if TYPE_CHECKING:
    from app.core.config import Settings

PUBLIC_RUNTIME_PARITY_REFRESH_INTERVAL_SECONDS = 4.0
PUBLIC_RUNTIME_PARITY_REFRESH_DEADLINE_SECONDS = 6.0

logger = get_logger(__name__)


async def refresh_active_public_runtime_parity(settings: Settings) -> bool:
    """Verify and refresh the active offer without holding DB state over I/O."""
    version_name: str | None = None
    source_git_sha: str | None = None
    try:
        async with asyncio.timeout(PUBLIC_RUNTIME_PARITY_REFRESH_DEADLINE_SECONDS):
            async with AsyncSessionLocal() as session:
                release = (
                    await session.execute(
                        select(AndroidRelease).where(
                            AndroidRelease.channel == "direct",
                            AndroidRelease.status == "active",
                        )
                    )
                ).scalar_one_or_none()
                if release is None:
                    await session.rollback()
                    await invalidate_all_runtime_parity_for_public_offers()
                    return False
                version_name = release.version_name
                source_git_sha = release.source_git_sha
                await session.rollback()

            await verify_runtime_parity(
                version_name=version_name,
                source_git_sha=source_git_sha,
                settings=settings,
            )
            await record_verified_runtime_parity_for_public_offer(
                version_name=version_name,
                source_git_sha=source_git_sha,
                settings=settings,
            )
            return True
    except asyncio.CancelledError:
        raise
    except TimeoutError:
        await invalidate_all_runtime_parity_for_public_offers()
        logger.warning(
            "android_update.runtime_attestation_refresh_timed_out",
            timeout_seconds=PUBLIC_RUNTIME_PARITY_REFRESH_DEADLINE_SECONDS,
        )
        return False
    except RuntimeParityError as exc:
        if version_name is not None and source_git_sha is not None:
            await invalidate_runtime_parity_for_public_offer(
                version_name=version_name,
                source_git_sha=source_git_sha,
            )
        else:
            await invalidate_all_runtime_parity_for_public_offers()
        logger.warning(
            "android_update.runtime_attestation_rejected",
            verification_code=exc.code,
            version_name=version_name,
        )
        return False
    except Exception:
        # Database/pool/config failures leave active-release truth unknown.
        # Remove all positive evidence before the caller logs and retries.
        await invalidate_all_runtime_parity_for_public_offers()
        raise


async def maintain_public_runtime_parity(settings: Settings) -> None:
    """Refresh faster than attestation expiry; never weaken fail-closed reads."""
    while True:
        await asyncio.sleep(PUBLIC_RUNTIME_PARITY_REFRESH_INTERVAL_SECONDS)
        try:
            await refresh_active_public_runtime_parity(settings)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - optional monitor must not stop the API
            logger.warning(
                "android_update.runtime_attestation_refresh_failed",
                error_type=type(exc).__name__,
            )


__all__ = [
    "PUBLIC_RUNTIME_PARITY_REFRESH_DEADLINE_SECONDS",
    "PUBLIC_RUNTIME_PARITY_REFRESH_INTERVAL_SECONDS",
    "maintain_public_runtime_parity",
    "refresh_active_public_runtime_parity",
]
