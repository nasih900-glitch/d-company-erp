"""Read-only paid-presence estimates; never payroll entitlements or postings."""

from __future__ import annotations

import calendar
from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from app.services.staff_time import AttendanceInterval, month_bounds

MAX_SAFE_INTEGER = 9_007_199_254_740_991
MICROSECONDS_PER_SECOND = 1_000_000


def _microseconds(delta: timedelta) -> int:
    return (delta.days * 86400 + delta.seconds) * MICROSECONDS_PER_SECOND + delta.microseconds


def _rounded_ratio(numerator: int, denominator: int) -> int:
    # Non-negative amounts: exact half-up rounding once per monthly component.
    return (2 * numerator + denominator) // (2 * denominator)


def presence_pay_estimate(
    intervals: list[AttendanceInterval],
    *,
    month: date,
    timezone: str,
    now: datetime,
    monthly_salary_minor: int,
    daily_presence_minutes: int,
    working_weekday_mask: int,
    policy_covers_full_month: bool,
) -> dict[str, object]:
    """Keep each completed visit on its company-local clock-in workday.

    Breaks are included in this explicitly paid-presence estimate. Ordinary time
    is capped per arrival workday; overnight visits stay together. All elapsed
    timestamps are measured in UTC and rounded only at monetary aggregation.
    """
    result: dict[str, object] = {
        "status": "unavailable",
        "reason": None,
        "basis": "paid_presence_clock_in_workday",
        "monthly_salary_minor": monthly_salary_minor,
        "daily_paid_seconds": daily_presence_minutes * 60,
        "scheduled_days": None,
        "scheduled_paid_seconds": None,
        "recorded_paid_seconds": None,
        "ordinary_seconds": None,
        "extra_seconds": None,
        "hourly_rate_minor": None,
        "base_pay_minor": None,
        "overtime_125_minor": None,
        "overtime_150_minor": None,
        "days": [],
    }
    if not policy_covers_full_month:
        result["reason"] = "One salary policy must cover the full selected month."
        return result
    if (
        not 0 <= monthly_salary_minor <= 1_000_000_000_000
        or not 1 <= daily_presence_minutes <= 1440
        or not 1 <= working_weekday_mask <= 127
    ):
        raise ValueError("invalid estimate policy")
    zone = ZoneInfo(timezone)
    month_bounds(month, timezone)  # Validate the same calendar/timezone contract as attendance.
    if now.tzinfo is None or now.utcoffset() is None:
        raise ValueError("now must include a timezone")
    now = now.astimezone(UTC)
    days_in_month = calendar.monthrange(month.year, month.month)[1]
    scheduled_days = sum(
        bool(working_weekday_mask & (1 << date(month.year, month.month, day).weekday()))
        for day in range(1, days_in_month + 1)
    )
    daily_us = daily_presence_minutes * 60 * MICROSECONDS_PER_SECOND
    denominator = scheduled_days * daily_us
    by_day: dict[date, int] = {}
    normalized = []
    for row in intervals:
        if (
            row.clock_in_at.tzinfo is None
            or row.clock_in_at.utcoffset() is None
            or (
                row.clock_out_at is not None
                and (row.clock_out_at.tzinfo is None or row.clock_out_at.utcoffset() is None)
            )
        ):
            raise ValueError("attendance timestamps must include a timezone")
        entered = row.clock_in_at.astimezone(UTC)
        exited = row.clock_out_at.astimezone(UTC) if row.clock_out_at else None
        day = entered.astimezone(zone).date()
        relevant = day.year == month.year and day.month == month.month
        normalized.append((entered, exited, day, relevant, row))
    previous_end: datetime | None = None
    previous_relevant = False
    for entered, exited, day, relevant, row in sorted(normalized, key=lambda v: v[0]):
        stop = exited or now
        invalid = entered > now or stop < entered or stop > now
        if relevant and exited is not None:
            invalid = (
                invalid
                or not row.break_confirmed
                or row.break_minutes < 0
                or row.break_minutes * 60 * MICROSECONDS_PER_SECOND > _microseconds(stop - entered)
            )
        if previous_end is not None and entered < previous_end and (relevant or previous_relevant):
            invalid = True
        if invalid:
            result["reason"] = "Attendance needs Owner review before pay can be estimated."
            return result
        if previous_end is None or stop > previous_end:
            previous_end, previous_relevant = stop, relevant
        if not relevant or exited is None:
            continue
        elapsed = exited - entered
        if elapsed > timedelta(days=1):
            result["reason"] = "Review unusually long clock-in/out before estimating pay."
            return result
        by_day[day] = by_day.get(day, 0) + _microseconds(elapsed)
    ordinary_us = extra_us = recorded_us = 0
    breakdown = []
    for day, paid_us in sorted(by_day.items()):
        scheduled = bool(working_weekday_mask & (1 << day.weekday()))
        normal_us = min(paid_us, daily_us) if scheduled else 0
        additional_us = paid_us - normal_us
        ordinary_us += normal_us
        extra_us += additional_us
        recorded_us += paid_us
        breakdown.append(
            {
                "date": day.isoformat(),
                "paid_seconds": paid_us // MICROSECONDS_PER_SECOND,
                "ordinary_seconds": normal_us // MICROSECONDS_PER_SECOND,
                "extra_seconds": additional_us // MICROSECONDS_PER_SECOND,
            }
        )
    base_minor = _rounded_ratio(monthly_salary_minor * ordinary_us, denominator)
    overtime_125_minor = _rounded_ratio(
        monthly_salary_minor * extra_us * 12500, denominator * 10000
    )
    overtime_150_minor = _rounded_ratio(
        monthly_salary_minor * extra_us * 15000, denominator * 10000
    )
    hourly_minor = _rounded_ratio(
        monthly_salary_minor * 3600 * MICROSECONDS_PER_SECOND, denominator
    )
    if max(base_minor + overtime_150_minor, hourly_minor) > MAX_SAFE_INTEGER:
        result["reason"] = "The estimate exceeds the supported exact money range."
        return result
    result.update(
        {
            "status": "available",
            "scheduled_days": scheduled_days,
            "scheduled_paid_seconds": denominator // MICROSECONDS_PER_SECOND,
            "recorded_paid_seconds": recorded_us // MICROSECONDS_PER_SECOND,
            "ordinary_seconds": ordinary_us // MICROSECONDS_PER_SECOND,
            "extra_seconds": extra_us // MICROSECONDS_PER_SECOND,
            "hourly_rate_minor": hourly_minor,
            "base_pay_minor": base_minor,
            "overtime_125_minor": overtime_125_minor,
            "overtime_150_minor": overtime_150_minor,
            "days": breakdown,
        }
    )
    return result
