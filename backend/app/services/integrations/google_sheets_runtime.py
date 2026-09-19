"""Runtime configuration and lifecycle for the durable Google Sheets mirror."""

from __future__ import annotations

import asyncio
import os
import socket
from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.core.logging import get_logger
from app.models import Company
from app.services.integrations.google_sheets_mirror import (
    GoogleSheetsMirrorDispatcher,
    MirrorDestination,
)
from app.services.integrations.google_sheets_secrets import (
    GoogleSheetsSecretDecryptionError,
    decrypt_google_sheets_signing_secret,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

log = get_logger(__name__)


async def resolve_google_sheets_destination(
    session: AsyncSession,
    company_id: UUID,
    configuration_id: UUID,
) -> MirrorDestination | None:
    company = (
        await session.execute(
            select(Company)
            .where(
                Company.id == company_id,
                Company.deleted_at.is_(None),
                Company.google_sheets_configuration_id == configuration_id,
            )
            .with_for_update(read=True)
        )
    ).scalar_one_or_none()
    if (
        company is None
        or not company.google_sheets_mirror_enabled
        or not company.google_sheets_webhook_url
        or not company.google_sheets_signing_secret_ciphertext
    ):
        return None
    try:
        signing_secret = decrypt_google_sheets_signing_secret(
            company_id=company.id,
            ciphertext=company.google_sheets_signing_secret_ciphertext,
        )
    except GoogleSheetsSecretDecryptionError:
        log.error(
            "google_sheets_mirror.secret_decryption_failed",
            company_id=str(company.id),
        )
        return None
    return MirrorDestination(
        webhook_url=company.google_sheets_webhook_url,
        signing_secret=signing_secret,
    )


def build_google_sheets_dispatcher() -> GoogleSheetsMirrorDispatcher:
    worker_id = (
        f"{socket.gethostname()}:{os.getpid()}:"
        f"{uuid4().hex[:12]}"
    )
    return GoogleSheetsMirrorDispatcher(
        session_factory=AsyncSessionLocal,
        destination_resolver=resolve_google_sheets_destination,
        worker_id=worker_id,
    )


async def maintain_google_sheets_mirror(
    *,
    poll_seconds: float = 5.0,
) -> None:
    """Continuously drain committed events without blocking business writes."""

    dispatcher = build_google_sheets_dispatcher()
    while True:
        try:
            claimed = await dispatcher.dispatch_once(now=datetime.now(UTC))
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - isolate the optional mirror
            log.warning(
                "google_sheets_mirror.dispatch_pass_failed",
                error_type=type(exc).__name__,
            )
            claimed = 0
        await asyncio.sleep(0.2 if claimed else poll_seconds)


__all__ = [
    "build_google_sheets_dispatcher",
    "maintain_google_sheets_mirror",
    "resolve_google_sheets_destination",
]
