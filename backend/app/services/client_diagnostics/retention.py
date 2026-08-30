"""Bounded global retention maintenance for sanitized client diagnostics."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING

from sqlalchemy import delete, select

from app.models import ClientDiagnosticEvent
from app.models.client_diagnostic import CLIENT_DIAGNOSTIC_RETENTION_DAYS

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


async def purge_expired_client_diagnostics(
    session: AsyncSession,
    *,
    batch_size: int,
    now: datetime | None = None,
) -> int:
    """Delete one globally scoped, lock-safe batch past the retention horizon.

    The production worker calls this independently of uploads, so a dormant
    company cannot retain telemetry forever. Rows are selected with SKIP LOCKED
    and the migration trigger independently refuses deletion before expiry.
    """
    if not 1 <= batch_size <= 1_000:
        raise ValueError("batch_size must be between 1 and 1000")
    server_time = now or datetime.now(UTC)
    if server_time.tzinfo is None or server_time.utcoffset() is None:
        raise ValueError("now must include a timezone")
    server_time = server_time.astimezone(UTC)
    cutoff = server_time - timedelta(days=CLIENT_DIAGNOSTIC_RETENTION_DAYS)
    event_ids = (
        (
            await session.execute(
                select(ClientDiagnosticEvent.id)
                .where(ClientDiagnosticEvent.received_at < cutoff)
                .order_by(
                    ClientDiagnosticEvent.received_at.asc(),
                    ClientDiagnosticEvent.id.asc(),
                )
                .limit(batch_size)
                .with_for_update(skip_locked=True)
            )
        )
        .scalars()
        .all()
    )
    if not event_ids:
        return 0
    await session.execute(
        delete(ClientDiagnosticEvent).where(ClientDiagnosticEvent.id.in_(event_ids))
    )
    return len(event_ids)


__all__ = ["purge_expired_client_diagnostics"]
