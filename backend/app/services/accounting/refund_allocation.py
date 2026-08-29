"""Canonical allocation helpers for refund accounting and reporting.

An order payment contains the taxable bill and, optionally, a tip. Refunds are
applied to the bill first and to the tip only after the bill has been fully
returned.  Every consumer must use the same cumulative allocation so multiple
partial refunds telescope to the exact original tip without rounding or
cross-period drift.
"""

from app.core.errors import BusinessRuleError


def cumulative_refunded_tip_minor(
    *,
    total_minor: int,
    tip_minor: int,
    cumulative_refunded_minor: int,
) -> int:
    """Return the tip portion of all refunds through ``cumulative_refunded``.

    Values are integer minor units. Financial source inconsistencies fail
    closed instead of being silently clamped into a plausible-looking report.
    """

    total = int(total_minor)
    tip = int(tip_minor)
    refunded = int(cumulative_refunded_minor)
    if total < 0 or tip < 0 or refunded < 0:
        raise BusinessRuleError("refund allocation contains a negative money value")
    if tip > total:
        raise BusinessRuleError("order tip exceeds the collected order total")
    if refunded > total:
        raise BusinessRuleError("cumulative refunds exceed the collected order total")

    bill_minor = total - tip
    return max(0, refunded - bill_minor)
