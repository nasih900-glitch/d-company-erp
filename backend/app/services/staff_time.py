"""Working-time facts and draft planning figures, independent of till shifts/payments."""

from __future__ import annotations

import calendar
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from typing import TYPE_CHECKING
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from app.core.errors import BusinessRuleError

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


@dataclass(frozen=True)
class AttendanceInterval:
    id: UUID
    clock_in_at: datetime
    clock_out_at: datetime | None
    break_minutes: int
    break_confirmed: bool = False


def month_bounds(month: date, timezone: str) -> tuple[datetime, datetime]:
    if month.day != 1:
        raise ValueError("month must be its first calendar day")
    zone = ZoneInfo(timezone)
    following = (
        date(month.year + 1, 1, 1) if month.month == 12 else date(month.year, month.month + 1, 1)
    )
    return (
        datetime.combine(month, datetime.min.time(), zone).astimezone(UTC),
        datetime.combine(following, datetime.min.time(), zone).astimezone(UTC),
    )


def _seconds(delta: timedelta) -> int:
    return max(0, delta // timedelta(seconds=1))


def summarize_intervals(
    intervals: list[AttendanceInterval],
    *,
    month: date,
    timezone: str,
    now: datetime,
) -> dict[str, object]:
    start, end = month_bounds(month, timezone)
    if now.tzinfo is None or now.utcoffset() is None:
        raise ValueError("now must include a timezone")
    now = now.astimezone(UTC)
    normalized: list[AttendanceInterval] = []
    for interval in intervals:
        entered, exited = interval.clock_in_at, interval.clock_out_at
        if (
            entered.tzinfo is None
            or entered.utcoffset() is None
            or (exited is not None and (exited.tzinfo is None or exited.utcoffset() is None))
        ):
            raise ValueError("attendance timestamps must include a timezone")
        # Same-ZoneInfo subtraction is wall time across DST, not elapsed time.
        normalized.append(
            AttendanceInterval(
                interval.id,
                entered.astimezone(UTC),
                exited.astimezone(UTC) if exited else None,
                interval.break_minutes,
                interval.break_confirmed,
            )
        )
    closed_gross = closed_net = open_seconds = 0
    review_ids: list[str] = []
    previous_end: datetime | None = None
    previous_id: str | None = None
    for interval in sorted(normalized, key=lambda row: row.clock_in_at):
        entered, exited = interval.clock_in_at, interval.clock_out_at
        if entered >= end:
            continue
        if entered > now or (exited is not None and (exited < entered or exited > now)):
            review_ids.append(str(interval.id))
            continue
        if exited is not None and exited <= start:
            continue
        overlap_start = max(entered, start)
        overlap_end = min(exited or now, end)
        if overlap_end <= overlap_start:
            continue
        if previous_end is not None and overlap_start < previous_end:
            if previous_id is not None:
                review_ids.append(previous_id)
            review_ids.append(str(interval.id))
        if previous_end is None or overlap_end > previous_end:
            previous_end, previous_id = overlap_end, str(interval.id)
        seconds = _seconds(overlap_end - overlap_start)
        if exited is None:
            # An unfinished entry is provisional, never treated as completed work/pay.
            open_seconds += seconds
            continue
        closed_gross += seconds
        break_seconds = interval.break_minutes * 60
        if (
            not interval.break_confirmed
            or break_seconds < 0
            or break_seconds > _seconds(exited - entered)
        ):
            review_ids.append(str(interval.id))
        elif break_seconds and (entered < start or exited > end):
            # Legacy records have only a break total: do not invent its month.
            review_ids.append(str(interval.id))
        else:
            closed_net += seconds - break_seconds
    return {
        "closed_gross_seconds": closed_gross,
        "closed_work_seconds": None if review_ids else closed_net,
        "provisional_open_seconds": open_seconds,
        "review_attendance_ids": list(dict.fromkeys(review_ids)),
        "pay_status": "draft",
        "overtime_pay_minor": None,
        "payable_minor": None,
    }


def draft_schedule(
    *,
    month: date,
    monthly_salary_minor: int,
    daily_presence_minutes: int,
    daily_break_minutes: int,
    working_weekday_mask: int,
) -> dict[str, int | str | None]:
    if month.day != 1 or monthly_salary_minor < 0:
        raise ValueError("invalid draft month or salary")
    if not 0 <= daily_break_minutes < daily_presence_minutes <= 1440:
        raise ValueError("invalid presence/break minutes")
    if not 1 <= working_weekday_mask <= 127:
        raise ValueError("at least one working weekday is required")
    days = sum(
        bool(working_weekday_mask & (1 << date(month.year, month.month, day).weekday()))
        for day in range(1, calendar.monthrange(month.year, month.month)[1] + 1)
    )
    minutes = days * (daily_presence_minutes - daily_break_minutes)
    return {
        "scheduled_days": days,
        "scheduled_work_minutes": minutes,
        # Exact rational for a display-only equivalent; do not multiply rounded rates.
        "reference_hourly_numerator_minor": monthly_salary_minor * 60,
        "reference_hourly_denominator_minutes": minutes or None,
        "pay_status": "draft",
        "payable_minor": None,
    }


async def next_attendance_version(session: AsyncSession, previous: datetime) -> datetime:
    """Call only while holding the person's User lock, before updating attendance.

    PostgreSQL now() is transaction-start time: a waiter can commit later with
    an earlier timestamp. A strict per-row version keeps Sheet projections and
    optimistic corrections ordered by actual serialized writes instead.
    """
    database_now = (await session.execute(select(func.clock_timestamp()))).scalar_one()
    if previous.tzinfo is None or previous.utcoffset() is None:
        raise BusinessRuleError(
            "The attendance version needs Owner review before it can be changed."
        )
    try:
        following = previous.astimezone(UTC) + timedelta(microseconds=1)
    except OverflowError as exc:
        raise BusinessRuleError(
            "The attendance version needs Owner review before it can be changed."
        ) from exc
    return max(database_now.astimezone(UTC), following)
