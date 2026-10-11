"""Bounded retention maintenance for expired refresh-session digests."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING

from sqlalchemy import delete, select

from app.models import AuthRefreshSession

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


AUTH_REFRESH_SESSION_RETENTION_DAYS = 90


async def purge_expired_refresh_sessions(
    session: AsyncSession,
    *,
    batch_size: int,
    now: datetime | None = None,
) -> int:
    """Delete one lock-safe batch whose credentials expired over 90 days ago."""

    if not 1 <= batch_size <= 1_000:
        raise ValueError("batch_size must be between 1 and 1000")
    server_time = now or datetime.now(UTC)
    if server_time.tzinfo is None or server_time.utcoffset() is None:
        raise ValueError("now must include a timezone")
    cutoff = server_time.astimezone(UTC) - timedelta(
        days=AUTH_REFRESH_SESSION_RETENTION_DAYS
    )
    session_ids = (
        (
            await session.execute(
                select(AuthRefreshSession.id)
                .where(AuthRefreshSession.expires_at < cutoff)
                .order_by(
                    AuthRefreshSession.expires_at.asc(),
                    AuthRefreshSession.id.asc(),
                )
                .limit(batch_size)
                .with_for_update(skip_locked=True)
            )
        )
        .scalars()
        .all()
    )
    if not session_ids:
        return 0
    await session.execute(
        delete(AuthRefreshSession).where(AuthRefreshSession.id.in_(session_ids))
    )
    return len(session_ids)


__all__ = [
    "AUTH_REFRESH_SESSION_RETENTION_DAYS",
    "purge_expired_refresh_sessions",
]
