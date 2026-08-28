from __future__ import annotations

import pytest

from app.core.errors import BusinessRuleError
from app.services.accounting.refund_allocation import cumulative_refunded_tip_minor


@pytest.mark.parametrize(
    ("total", "tip", "refunded", "expected"),
    [
        (1_100, 100, 0, 0),  # no refund control
        (1_100, 100, 500, 0),  # partial bill refund does not touch tip
        (1_100, 100, 1_050, 50),  # refund crosses into tip
        (1_100, 100, 1_100, 100),  # full refund clears the exact tip
        (100, 100, 40, 40),  # zero-subtotal/tip-only payment
        (100, 100, 100, 100),
        (1_000, 0, 1_000, 0),  # untipped order
    ],
)
def test_cumulative_refunded_tip_allocation(
    total: int,
    tip: int,
    refunded: int,
    expected: int,
) -> None:
    assert cumulative_refunded_tip_minor(
        total_minor=total,
        tip_minor=tip,
        cumulative_refunded_minor=refunded,
    ) == expected


def test_multiple_partial_refunds_telescope_to_exact_tip() -> None:
    cumulative = [0, 600, 1_050, 1_100]
    allocated = [
        cumulative_refunded_tip_minor(
            total_minor=1_100,
            tip_minor=100,
            cumulative_refunded_minor=after,
        )
        - cumulative_refunded_tip_minor(
            total_minor=1_100,
            tip_minor=100,
            cumulative_refunded_minor=before,
        )
        for before, after in zip(cumulative[:-1], cumulative[1:], strict=True)
    ]

    assert allocated == [0, 50, 50]
    assert sum(allocated) == 100


@pytest.mark.parametrize(
    ("total", "tip", "refunded"),
    [
        (-1, 0, 0),
        (100, -1, 0),
        (100, 101, 0),
        (100, 0, -1),
        (100, 0, 101),
    ],
)
def test_refund_allocation_fails_closed_on_incoherent_source(
    total: int,
    tip: int,
    refunded: int,
) -> None:
    with pytest.raises(BusinessRuleError):
        cumulative_refunded_tip_minor(
            total_minor=total,
            tip_minor=tip,
            cumulative_refunded_minor=refunded,
        )
