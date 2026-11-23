from datetime import UTC, date, datetime
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest

from app.services.staff_time import (
    AttendanceInterval,
    draft_schedule,
    month_bounds,
    summarize_intervals,
)


def confirmed_interval(*args):
    """These fixture inputs explicitly confirm their actual break, including zero."""
    return AttendanceInterval(*args, break_confirmed=True)


def test_draft_schedule_separates_presence_breaks_and_never_creates_pay():
    result = draft_schedule(
        month=date(2026, 11, 1),
        monthly_salary_minor=1200000,
        daily_presence_minutes=720,
        daily_break_minutes=60,
        working_weekday_mask=127,
    )
    assert result["scheduled_days"] == 30
    assert result["scheduled_work_minutes"] == 19800
    assert result["reference_hourly_numerator_minor"] == 72000000
    assert result["reference_hourly_denominator_minutes"] == 19800
    assert result["payable_minor"] is None
    assert result["pay_status"] == "draft"


@pytest.mark.parametrize(
    ("month", "days"), [(date(2026, 10, 1), 31), (date(2026, 2, 1), 28), (date(2028, 2, 1), 29)]
)
def test_calendar_month_days_are_not_a_fixed_30_day_divisor(month, days):
    result = draft_schedule(
        month=month,
        monthly_salary_minor=1200000,
        daily_presence_minutes=720,
        daily_break_minutes=60,
        working_weekday_mask=127,
    )
    assert result["scheduled_days"] == days
    assert result["scheduled_work_minutes"] == days * 660


def test_weekday_mask_and_dst_month_boundaries_are_explicit():
    result = draft_schedule(
        month=date(2026, 11, 1),
        monthly_salary_minor=1200000,
        daily_presence_minutes=480,
        daily_break_minutes=0,
        working_weekday_mask=31,
    )
    assert result["scheduled_days"] == 21
    start, end = month_bounds(date(2026, 3, 1), "America/New_York")
    assert (end - start).total_seconds() == 31 * 86400 - 3600
    assert month_bounds(date(2026, 12, 1), "Asia/Kolkata")[1] == datetime(
        2026, 12, 31, 18, 30, tzinfo=UTC
    )


def test_closed_work_and_open_provisional_time_are_separate():
    entries = [
        confirmed_interval(
            uuid4(), datetime(2026, 11, 2, 4, tzinfo=UTC), datetime(2026, 11, 2, 16, tzinfo=UTC), 60
        ),
        confirmed_interval(uuid4(), datetime(2026, 11, 3, 4, tzinfo=UTC), None, 0),
    ]
    result = summarize_intervals(
        entries,
        month=date(2026, 11, 1),
        timezone="Asia/Kolkata",
        now=datetime(2026, 11, 3, 6, tzinfo=UTC),
    )
    assert result["closed_gross_seconds"] == 43200
    assert result["closed_work_seconds"] == 39600
    assert result["provisional_open_seconds"] == 7200
    assert result["overtime_pay_minor"] is None
    assert result["payable_minor"] is None


def test_cross_month_break_total_requires_review_instead_of_invented_allocation():
    entry = confirmed_interval(
        uuid4(), datetime(2026, 10, 31, 17, tzinfo=UTC), datetime(2026, 11, 1, 5, tzinfo=UTC), 60
    )
    result = summarize_intervals(
        [entry],
        month=date(2026, 11, 1),
        timezone="Asia/Kolkata",
        now=datetime(2026, 11, 3, tzinfo=UTC),
    )
    assert result["closed_gross_seconds"] == 37800
    assert result["closed_work_seconds"] is None
    assert result["review_attendance_ids"] == [str(entry.id)]


@pytest.mark.parametrize("break_minutes", [-1, 721])
def test_invalid_legacy_breaks_never_produce_a_plausible_net_total(break_minutes):
    entry = confirmed_interval(
        uuid4(),
        datetime(2026, 11, 2, 4, tzinfo=UTC),
        datetime(2026, 11, 2, 16, tzinfo=UTC),
        break_minutes,
    )
    result = summarize_intervals(
        [entry], month=date(2026, 11, 1), timezone="UTC", now=datetime(2026, 11, 3, tzinfo=UTC)
    )
    assert result["closed_work_seconds"] is None
    assert result["review_attendance_ids"] == [str(entry.id)]


def test_overlapping_entries_require_review_instead_of_silently_doubling_work():
    first = confirmed_interval(
        uuid4(), datetime(2026, 11, 2, 4, tzinfo=UTC), datetime(2026, 11, 2, 16, tzinfo=UTC), 0
    )
    second = confirmed_interval(
        uuid4(), datetime(2026, 11, 2, 15, tzinfo=UTC), datetime(2026, 11, 2, 17, tzinfo=UTC), 0
    )
    result = summarize_intervals(
        [second, first],
        month=date(2026, 11, 1),
        timezone="UTC",
        now=datetime(2026, 11, 3, tzinfo=UTC),
    )
    assert result["closed_work_seconds"] is None
    assert result["review_attendance_ids"] == [str(first.id), str(second.id)]


@pytest.mark.parametrize(("day", "hours"), [(date(2026, 11, 1), 5), (date(2026, 3, 8), 3)])
def test_dst_work_uses_elapsed_seconds_not_local_wall_time(day, hours):
    zone = ZoneInfo("America/New_York")
    entered = datetime(day.year, day.month, day.day, 0, tzinfo=zone)
    exited = datetime(day.year, day.month, day.day, 4, tzinfo=zone)
    key = uuid4()
    local = confirmed_interval(key, entered, exited, 0)
    utc = confirmed_interval(key, entered.astimezone(UTC), exited.astimezone(UTC), 0)
    kwargs = {"month": day.replace(day=1), "timezone": "America/New_York", "now": exited}
    assert summarize_intervals([local], **kwargs) == summarize_intervals([utc], **kwargs)
    assert summarize_intervals([local], **kwargs)["closed_work_seconds"] == hours * 3600


def test_mixed_naive_inputs_are_rejected_before_sorting():
    entries = [
        confirmed_interval(uuid4(), datetime(2026, 11, 2, tzinfo=UTC), None, 0),
        confirmed_interval(uuid4(), datetime(2026, 11, 3), None, 0),
    ]
    with pytest.raises(ValueError, match="timestamps must include a timezone"):
        summarize_intervals(
            entries, month=date(2026, 11, 1), timezone="UTC", now=datetime(2026, 11, 4, tzinfo=UTC)
        )


def test_unconfirmed_legacy_zero_break_withholds_work_but_retains_presence():
    entry = AttendanceInterval(
        uuid4(), datetime(2026, 11, 1, 4, tzinfo=UTC), datetime(2026, 11, 1, 16, tzinfo=UTC), 0
    )
    result = summarize_intervals(
        [entry], month=date(2026, 11, 1), timezone="UTC", now=datetime(2026, 11, 2, tzinfo=UTC)
    )
    assert result["closed_gross_seconds"] == 43200
    assert result["closed_work_seconds"] is None
    assert result["review_attendance_ids"] == [str(entry.id)]
    assert result["payable_minor"] is None
