"""Retention maintenance for private bug-report screenshot bytes."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import select
from sqlalchemy.orm import undefer

from app.models import BugReportAttachment

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


@dataclass(frozen=True, slots=True)
class AttachmentPurgeResult:
    rows: int
    bytes_released: int


async def purge_expired_bug_report_attachments(
    session: AsyncSession,
    *,
    now: datetime | None = None,
    batch_size: int = 100,
    company_id: UUID | None = None,
) -> AttachmentPurgeResult:
    """Erase expired bytes in a bounded, lock-safe batch while retaining metadata."""
    if not 1 <= batch_size <= 1_000:
        raise ValueError("batch_size must be between 1 and 1000")
    cutoff = now or datetime.now(UTC)
    if cutoff.tzinfo is None:
        raise ValueError("now must include a timezone")
    conditions = [
        BugReportAttachment.payload.is_not(None),
        BugReportAttachment.expires_at <= cutoff,
    ]
    if company_id is not None:
        conditions.append(BugReportAttachment.company_id == company_id)
    rows = (
        (
            await session.execute(
                select(BugReportAttachment)
                .options(undefer(BugReportAttachment.payload))
                .where(*conditions)
                .order_by(BugReportAttachment.expires_at, BugReportAttachment.id)
                .limit(batch_size)
                .with_for_update(skip_locked=True)
            )
        )
        .scalars()
        .all()
    )
    released = 0
    for attachment in rows:
        released += attachment.byte_size
        attachment.payload = None
        attachment.purged_at = cutoff
    if rows:
        await session.flush()
    return AttachmentPurgeResult(rows=len(rows), bytes_released=released)


__all__ = ["AttachmentPurgeResult", "purge_expired_bug_report_attachments"]
