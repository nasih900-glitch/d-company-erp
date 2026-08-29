"""Pure boundary and cohort-metric proofs for Reports/Insights."""

from __future__ import annotations

from datetime import date

import pytest

from app.api.v1.insights.router import _date_range_for_period, _percentage_delta
from app.core.errors import BusinessRuleError
from app.services.reports import fy_full_range, fy_quarter_range, month_range
from app.services.reports.metrics import average_ticket_minor


@pytest.mark.parametrize(
    ("period", "today", "current", "previous"),
    [
        (
            "mom",
            date(2026, 8, 28),
            (date(2026, 8, 1), date(2026, 8, 28)),
            (date(2026, 7, 1), date(2026, 7, 28)),
        ),
        (
            "mom",
            date(2026, 3, 31),
            (date(2026, 3, 1), date(2026, 3, 31)),
            (date(2026, 2, 1), date(2026, 2, 28)),
        ),
        (
            "wow",
            date(2026, 8, 28),
            (date(2026, 8, 24), date(2026, 8, 28)),
            (date(2026, 8, 17), date(2026, 8, 21)),
        ),
        (
            "yoy",
            date(2028, 2, 29),
            (date(2028, 1, 1), date(2028, 2, 29)),
            (date(2027, 1, 1), date(2027, 2, 28)),
        ),
    ],
)
def test_growth_periods_compare_equal_elapsed_windows(
    period: str,
    today: date,
    current: tuple[date, date],
    previous: tuple[date, date],
) -> None:
    actual_current, actual_previous, _current_label, _previous_label = (
        _date_range_for_period(period, today)
    )
    assert actual_current == current
    assert actual_previous == previous


def test_growth_period_rejects_unknown_comparison() -> None:
    with pytest.raises(BusinessRuleError, match="wow, mom or yoy"):
        _date_range_for_period("quarter", date(2026, 8, 28))


def test_growth_percentage_does_not_invent_a_zero_baseline() -> None:
    assert _percentage_delta(500, 0) is None
    assert _percentage_delta(500, -100) is None
    assert _percentage_delta(150, 100) == 50.0


@pytest.mark.parametrize("fy", ["2026-99", "2026-28", "abcd-ef", "202627"])
def test_fiscal_year_rejects_mislabelled_or_malformed_ranges(fy: str) -> None:
    with pytest.raises(BusinessRuleError):
        fy_full_range(fy)


def test_daily_month_quarter_and_year_ranges_are_exact_and_inclusive() -> None:
    assert month_range("2028-02") == (date(2028, 2, 1), date(2028, 2, 29))
    assert fy_quarter_range("2026-27", 1) == (
        date(2026, 4, 1),
        date(2026, 6, 30),
    )
    assert fy_quarter_range("2026-27", 4) == (
        date(2027, 1, 1),
        date(2027, 3, 31),
    )
    assert fy_full_range("2026-27") == (
        date(2026, 4, 1),
        date(2027, 3, 31),
    )


def test_average_ticket_is_half_up_and_ignores_prior_cohort_refunds() -> None:
    assert average_ticket_minor(
        gross_sale_cohort_minor=101,
        same_cohort_refunds_minor=0,
        orders_count=2,
    ) == 51
    assert average_ticket_minor(
        gross_sale_cohort_minor=10_000,
        same_cohort_refunds_minor=2_000,
        orders_count=2,
    ) == 4_000
    # The caller deliberately does not pass a refund for an older invoice.
    assert average_ticket_minor(
        gross_sale_cohort_minor=10_000,
        same_cohort_refunds_minor=0,
        orders_count=1,
    ) == 10_000
