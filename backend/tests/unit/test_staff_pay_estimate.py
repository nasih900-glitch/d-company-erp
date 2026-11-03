from datetime import UTC, date, datetime, timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest

from app.services.staff_pay_estimate import presence_pay_estimate
from app.services.staff_time import AttendanceInterval


def interval(start, end, *, confirmed=True, breaks=0):
    return AttendanceInterval(uuid4(), start, end, breaks, confirmed)


def estimate(entries, month=date(2026, 11, 1), timezone="UTC", **changes):
    return presence_pay_estimate(
        entries,
        month=month,
        timezone=timezone,
        now=datetime(2029, 1, 1, tzinfo=UTC),
        **{
            "monthly_salary_minor": 1200000,
            "daily_presence_minutes": 720,
            "working_weekday_mask": 127,
            "policy_covers_full_month": True,
            **changes,
        },
    )


@pytest.mark.parametrize(
    ("month", "days"),
    [
        (date(2026, 11, 1), 30),
        (date(2026, 10, 1), 31),
        (date(2026, 2, 1), 28),
        (date(2028, 2, 1), 29),
    ],
)
def test_full_actual_calendar_month_equals_monthly_salary_including_paid_breaks(month, days):
    entries = []
    for day in range(1, days + 1):
        start = datetime(month.year, month.month, day, 7, tzinfo=UTC)
        entries.append(interval(start, start + timedelta(hours=12), breaks=60))
    p = estimate(entries, month)
    assert p["base_pay_minor"] == 1200000
    assert p["ordinary_seconds"] == days * 12 * 3600
    assert p["extra_seconds"] == p["overtime_125_minor"] == p["overtime_150_minor"] == 0


def test_leaving_early_reduces_base_and_another_days_extra_does_not_cancel_shortage():
    a = datetime(2026, 11, 1, 5, tzinfo=UTC)
    b = datetime(2026, 11, 2, 5, tzinfo=UTC)
    p = estimate([interval(a, a + timedelta(hours=6)), interval(b, b + timedelta(hours=14))])
    assert p["recorded_paid_seconds"] == 20 * 3600
    assert p["ordinary_seconds"] == 18 * 3600
    assert p["extra_seconds"] == 2 * 3600
    assert p["base_pay_minor"] == 60000
    assert p["overtime_125_minor"] == 8333
    assert p["overtime_150_minor"] == 10000


def test_disjoint_visits_on_one_day_share_one_daily_cap():
    start = datetime(2026, 11, 1, tzinfo=UTC)
    p = estimate(
        [
            interval(start, start + timedelta(hours=8)),
            interval(start + timedelta(hours=9), start + timedelta(hours=15)),
        ]
    )
    assert p["ordinary_seconds"] == 43200
    assert p["extra_seconds"] == 7200
    assert len(p["days"]) == 1


def test_overnight_stays_on_arrival_workday_and_receives_extra_hours():
    zone = ZoneInfo("Asia/Kolkata")
    start = datetime(2026, 11, 1, 20, tzinfo=zone)
    p = estimate([interval(start, start + timedelta(hours=14))], timezone=zone.key)
    assert [(d["date"], d["paid_seconds"]) for d in p["days"]] == [
        ("2026-11-01", 14 * 3600),
    ]
    assert p["ordinary_seconds"] == 12 * 3600
    assert p["extra_seconds"] == 2 * 3600
    assert p["basis"] == "paid_presence_clock_in_workday"


def test_cross_month_visit_with_paid_break_stays_entirely_in_arrival_month():
    zone = ZoneInfo("Asia/Kolkata")
    start = datetime(2026, 11, 30, 20, tzinfo=zone)
    entries = [interval(start, start + timedelta(hours=14), breaks=60)]
    november = estimate(entries, timezone=zone.key)
    december = estimate(entries, month=date(2026, 12, 1), timezone=zone.key)
    assert november["status"] == december["status"] == "available"
    assert november["recorded_paid_seconds"] == 14 * 3600
    assert november["ordinary_seconds"] == 12 * 3600
    assert november["extra_seconds"] == 2 * 3600
    assert november["days"][0]["date"] == "2026-11-30"
    assert december["recorded_paid_seconds"] == december["base_pay_minor"] == 0
    assert december["days"] == []


@pytest.mark.parametrize(
    ("month", "start", "hours"),
    [
        (date(2026, 3, 1), datetime(2026, 3, 8, 0, tzinfo=ZoneInfo("America/New_York")), 3),
        (date(2026, 11, 1), datetime(2026, 11, 1, 0, tzinfo=ZoneInfo("America/New_York")), 5),
    ],
)
def test_dst_uses_elapsed_time_not_wall_time(month, start, hours):
    p = estimate([interval(start, start.replace(hour=4))], month, "America/New_York")
    assert p["recorded_paid_seconds"] == hours * 3600


def test_open_entries_remain_provisional_and_do_not_increase_estimated_pay():
    start = datetime(2026, 11, 1, tzinfo=UTC)
    p = estimate([interval(start, None)])
    assert p["status"] == "available"
    assert p["base_pay_minor"] == p["extra_seconds"] == 0
    assert p["days"] == []


@pytest.mark.parametrize("kind", ["unknown_break", "invalid_break", "overlap", "future"])
def test_unverified_attendance_withholds_all_money(kind):
    start = datetime(2026, 11, 1, tzinfo=UTC)
    entries = [
        interval(
            start,
            start + timedelta(hours=12),
            confirmed=kind != "unknown_break",
            breaks=721 if kind == "invalid_break" else 0,
        )
    ]
    if kind == "overlap":
        entries.append(interval(start + timedelta(hours=11), start + timedelta(hours=13)))
    if kind == "future":
        entries = [interval(datetime(2026, 11, 30, tzinfo=UTC), datetime(2030, 1, 1, tzinfo=UTC))]
    p = estimate(entries)
    assert p["status"] == "unavailable"
    assert p["base_pay_minor"] is p["overtime_150_minor"] is None


def test_midmonth_terms_do_not_infer_an_agreement_for_the_other_days():
    p = estimate([], policy_covers_full_month=False)
    assert p["status"] == "unavailable"
    assert "full selected month" in p["reason"]


def test_subsecond_intervals_are_combined_before_monetary_rounding():
    start = datetime(2026, 11, 1, tzinfo=UTC)
    p = estimate(
        [
            interval(start, start + timedelta(microseconds=500000)),
            interval(start + timedelta(seconds=1), start + timedelta(seconds=1.5)),
        ],
        monthly_salary_minor=1296000,
    )
    assert p["recorded_paid_seconds"] == 1
    assert p["base_pay_minor"] == 1  # One exact minor unit; per-interval truncation would lose it.


def test_money_beyond_javascript_exact_integer_range_is_unavailable():
    entries = [
        interval(
            datetime(2026, 11, day, tzinfo=UTC),
            datetime(2026, 11, day, tzinfo=UTC) + timedelta(days=1),
        )
        for day in range(1, 31)
    ]
    p = estimate(
        entries,
        monthly_salary_minor=1000000000000,
        daily_presence_minutes=1,
        working_weekday_mask=1,
    )
    assert p["status"] == "unavailable"
    assert p["base_pay_minor"] is None


def test_forgotten_day_long_clockout_requires_review_but_exact_24_hours_is_supported():
    start = datetime(2026, 11, 1, tzinfo=UTC)
    assert estimate([interval(start, start + timedelta(hours=24))])["status"] == "available"
    p = estimate([interval(start, start + timedelta(hours=25))])
    assert p["status"] == "unavailable"
    assert "unusually long" in p["reason"]


def test_off_schedule_day_is_extra_not_regular_salary_time():
    start = datetime(2026, 11, 1, 6, tzinfo=UTC)  # Sunday; Monday-Friday policy.
    p = estimate([interval(start, start + timedelta(hours=6))], working_weekday_mask=31)
    assert p["ordinary_seconds"] == 0
    assert p["extra_seconds"] == 21600
