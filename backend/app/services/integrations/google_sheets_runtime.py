"""Runtime configuration and lifecycle for the durable Google Sheets mirror."""

from __future__ import annotations

import asyncio
import os
import socket
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.core.logging import get_logger
from app.models import Company
from app.services.integrations.google_sheets_collection_summary import (
    scan_daily_collection_summaries,
)
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
    worker_id = f"{socket.gethostname()}:{os.getpid()}:{uuid4().hex[:12]}"
    return GoogleSheetsMirrorDispatcher(
        session_factory=AsyncSessionLocal,
        destination_resolver=resolve_google_sheets_destination,
        worker_id=worker_id,
    )


async def maintain_google_sheets_mirror(
    *,
    poll_seconds: float = 5.0,
    summary_scan_seconds: float = 300.0,
) -> None:
    """Continuously drain committed events without blocking business writes."""

    dispatcher = build_google_sheets_dispatcher()
    next_summary_scan_at = datetime.min.replace(tzinfo=UTC)
    while True:
        now = datetime.now(UTC)
        if now >= next_summary_scan_at:
            try:
                companies_scanned = 0
                companies_failed = 0
                days_scanned = 0
                deliveries_enqueued = 0
                scanned_company_ids: set[UUID] = set()
                for _ in range(10):
                    async with AsyncSessionLocal() as session, session.begin():
                        scan = await scan_daily_collection_summaries(
                            session,
                            now=now,
                            company_limit=1,
                            exclude_company_ids=frozenset(scanned_company_ids),
                        )
                    if scan.companies_scanned == 0:
                        break
                    companies_scanned += scan.companies_scanned
                    companies_failed += scan.companies_failed
                    days_scanned += scan.days_scanned
                    deliveries_enqueued += scan.deliveries_enqueued
                    if scan.company_id is not None:
                        scanned_company_ids.add(scan.company_id)
                if deliveries_enqueued or companies_failed:
                    log.info(
                        "google_sheets_summary.scan_completed",
                        companies_scanned=companies_scanned,
                        companies_failed=companies_failed,
                        days_scanned=days_scanned,
                        deliveries_enqueued=deliveries_enqueued,
                    )
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - isolate optional summary export
                log.warning(
                    "google_sheets_summary.scan_failed",
                    error_type=type(exc).__name__,
                )
            next_summary_scan_at = now + timedelta(seconds=summary_scan_seconds)
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
