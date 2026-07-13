"""Business-time helpers.

Database timestamps stay in UTC. Calendar dates shown in reports and used for
Indian fiscal periods are interpreted in the company's configured timezone.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import get_settings
from app.models.tenant import Company


def business_zone(name: str | None) -> ZoneInfo:
    timezone_name = name or get_settings().default_timezone
    try:
        return ZoneInfo(timezone_name)
    except ZoneInfoNotFoundError as exc:
        raise ValueError(f"unknown business timezone: {timezone_name}") from exc


def local_today(timezone_name: str | None, *, now: datetime | None = None) -> date:
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    return current.astimezone(business_zone(timezone_name)).date()


def local_date_bounds_utc(
    start: date,
    end: date,
    timezone_name: str | None,
) -> tuple[datetime, datetime]:
    """Return a half-open UTC interval for inclusive local calendar dates."""
    if end < start:
        raise ValueError("end date cannot be before start date")
    zone = business_zone(timezone_name)
    start_local = datetime.combine(start, time.min, tzinfo=zone)
    end_exclusive_local = datetime.combine(end + timedelta(days=1), time.min, tzinfo=zone)
    return (
        start_local.astimezone(timezone.utc),
        end_exclusive_local.astimezone(timezone.utc),
    )


async def company_timezone(session: AsyncSession, company_id: UUID) -> str:
    value = (
        await session.execute(select(Company.timezone).where(Company.id == company_id))
    ).scalar_one_or_none()
    return value or get_settings().default_timezone
