from datetime import date

from app.workers.pnl_alerts import due_periods, period_spec


def test_daily_period_is_yesterday() -> None:
    spec = period_spec("daily", date(2026, 6, 13))

    assert spec.start == date(2026, 6, 12)
    assert spec.end == date(2026, 6, 12)
    assert spec.name == "Daily"


def test_weekly_period_is_previous_completed_monday_to_sunday() -> None:
    spec = period_spec("weekly", date(2026, 6, 13))

    assert spec.start == date(2026, 6, 1)
    assert spec.end == date(2026, 6, 7)


def test_half_yearly_period_uses_indian_financial_year() -> None:
    spec = period_spec("half_yearly", date(2026, 6, 13))

    assert spec.start == date(2025, 10, 1)
    assert spec.end == date(2026, 3, 31)
    assert spec.label == "2025-26 H2"


def test_all_due_on_april_first_includes_fy_reports() -> None:
    assert due_periods(date(2026, 4, 1)) == [
        "daily",
        "monthly",
        "quarterly",
        "half_yearly",
        "yearly",
    ]
