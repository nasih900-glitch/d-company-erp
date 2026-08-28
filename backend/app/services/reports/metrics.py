"""Canonical, integer-safe report metric helpers."""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal


def average_ticket_minor(
    *,
    gross_sale_cohort_minor: int,
    same_cohort_refunds_minor: int,
    orders_count: int,
) -> int:
    """Return the HALF_UP average for the selected sale cohort.

    Refunds for invoices issued outside the cohort are cash/revenue movement
    for the refund period, but must not reduce the value of unrelated receipts
    issued inside it. Tips are excluded by the caller from both inputs.
    """
    if orders_count <= 0:
        return 0
    net_cohort_minor = max(
        0,
        int(gross_sale_cohort_minor) - int(same_cohort_refunds_minor),
    )
    return int(
        (Decimal(net_cohort_minor) / Decimal(orders_count)).quantize(
            Decimal("1"),
            rounding=ROUND_HALF_UP,
        )
    )
