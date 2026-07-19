"""Pure straight-line depreciation math — no DB access, no wall-clock reads.

Depreciation is never manually entered or "posted" by a cron job. Like every
other derived figure in ledger.py (refund tax allocation, proportional
rounding, the historical-journal balance check, ...) it is computed
analytically from source data — here, the Asset register — as of whatever
moment a caller asks for, so the ledger, trial balance, and P&L can never
disagree with each other and there is nothing to backfill or re-post if a
period is re-requested.

Only Asset.depreciation_method == "straight_line" is implemented below. Any
other value (the column is a plain String(20), not an enum, so nothing stops
a future "declining_balance") is a documented no-op — see TODO.
"""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal
from typing import TYPE_CHECKING, Protocol

if TYPE_CHECKING:
    from datetime import datetime

STRAIGHT_LINE = "straight_line"


class Depreciable(Protocol):
    """Structural shape this module needs from an Asset row.

    A Protocol rather than importing the ORM model directly, so this stays
    pure and trivially testable with a plain SimpleNamespace (see
    test_depreciation.py) — no DB/session/model setup required.
    """

    depreciation_method: str
    purchase_minor: int
    salvage_minor: int
    useful_life_months: int
    purchase_date: datetime


def _elapsed_whole_months(purchase_date: datetime, as_of: datetime) -> int:
    """Count of complete calendar months of service between two datetimes.

    A month only counts once the purchase-date "anniversary" day (and, for
    same-day precision, time-of-day) has passed — matching how a once-a-month
    depreciation close would recognize a full month, and giving exact,
    easily-testable fractions at month boundaries (e.g. exactly half the
    depreciable base at the midpoint of useful_life_months).
    """
    if as_of <= purchase_date:
        return 0
    months = (as_of.year - purchase_date.year) * 12 + (as_of.month - purchase_date.month)
    if (as_of.day, as_of.hour, as_of.minute, as_of.second, as_of.microsecond) < (
        purchase_date.day,
        purchase_date.hour,
        purchase_date.minute,
        purchase_date.second,
        purchase_date.microsecond,
    ):
        months -= 1
    return max(0, months)


def accumulated_depreciation_minor(
    *,
    depreciation_method: str,
    purchase_minor: int,
    salvage_minor: int,
    useful_life_months: int,
    purchase_date: datetime,
    as_of: datetime,
) -> int:
    """Straight-line accumulated depreciation as of `as_of`.

    Floored at 0 (never before the purchase date) and capped at
    (purchase_minor - salvage_minor) once useful_life_months has fully
    elapsed, so book value never drops below salvage.

    This function is pure and never reads the wall clock — "never depreciate
    into the future" is the caller's responsibility (pass an `as_of` already
    capped at real now). That keeps this directly unit-testable with an
    arbitrary as_of instead of depending on when the test happens to run.
    """
    if depreciation_method != STRAIGHT_LINE:
        # TODO: implement declining-balance or any other method the business
        # actually adopts. Silently guessing at an unimplemented formula here
        # would misstate depreciation for those assets, so until then they
        # simply accrue none.
        return 0
    if useful_life_months <= 0:
        return 0
    depreciable_base = max(0, int(purchase_minor) - int(salvage_minor))
    if depreciable_base <= 0:
        return 0
    elapsed = min(_elapsed_whole_months(purchase_date, as_of), useful_life_months)
    if elapsed <= 0:
        return 0
    if elapsed >= useful_life_months:
        return depreciable_base
    accumulated = (
        Decimal(depreciable_base) * Decimal(elapsed) / Decimal(useful_life_months)
    ).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return min(int(accumulated), depreciable_base)


def book_value_minor(
    *,
    depreciation_method: str,
    purchase_minor: int,
    salvage_minor: int,
    useful_life_months: int,
    purchase_date: datetime,
    as_of: datetime,
) -> int:
    """Net book value = cost - accumulated depreciation, floored at salvage."""
    accumulated = accumulated_depreciation_minor(
        depreciation_method=depreciation_method,
        purchase_minor=purchase_minor,
        salvage_minor=salvage_minor,
        useful_life_months=useful_life_months,
        purchase_date=purchase_date,
        as_of=as_of,
    )
    return max(int(salvage_minor), int(purchase_minor) - accumulated)


def depreciation_expense_minor(
    *,
    depreciation_method: str,
    purchase_minor: int,
    salvage_minor: int,
    useful_life_months: int,
    purchase_date: datetime,
    start_at: datetime | None,
    end_as_of: datetime,
) -> int:
    """Depreciation recognized within the half-open window [start_at, end_as_of).

    Computed as accumulated(end_as_of) - accumulated(start_at) — the same
    cumulative-diff technique app/services/accounting/ledger.py already uses
    for refund tax allocation (`_proportional`). It guarantees consecutive
    slices always sum to the total accumulated depreciation with no drift, no
    matter how a caller chops the timeline into periods.

    start_at=None means "since the beginning of time" (no lower bound).
    """
    end_accumulated = accumulated_depreciation_minor(
        depreciation_method=depreciation_method,
        purchase_minor=purchase_minor,
        salvage_minor=salvage_minor,
        useful_life_months=useful_life_months,
        purchase_date=purchase_date,
        as_of=end_as_of,
    )
    if start_at is None:
        return end_accumulated
    start_accumulated = accumulated_depreciation_minor(
        depreciation_method=depreciation_method,
        purchase_minor=purchase_minor,
        salvage_minor=salvage_minor,
        useful_life_months=useful_life_months,
        purchase_date=purchase_date,
        as_of=start_at,
    )
    return max(0, end_accumulated - start_accumulated)


# ---------------------------------------------------------------------------
# Convenience wrappers over anything matching Depreciable (e.g. an Asset row)
# ---------------------------------------------------------------------------
def asset_accumulated_depreciation_minor(asset: Depreciable, as_of: datetime) -> int:
    return accumulated_depreciation_minor(
        depreciation_method=asset.depreciation_method,
        purchase_minor=asset.purchase_minor,
        salvage_minor=asset.salvage_minor,
        useful_life_months=asset.useful_life_months,
        purchase_date=asset.purchase_date,
        as_of=as_of,
    )


def asset_book_value_minor(asset: Depreciable, as_of: datetime) -> int:
    return book_value_minor(
        depreciation_method=asset.depreciation_method,
        purchase_minor=asset.purchase_minor,
        salvage_minor=asset.salvage_minor,
        useful_life_months=asset.useful_life_months,
        purchase_date=asset.purchase_date,
        as_of=as_of,
    )


def asset_depreciation_expense_minor(
    asset: Depreciable, *, start_at: datetime | None, end_as_of: datetime
) -> int:
    return depreciation_expense_minor(
        depreciation_method=asset.depreciation_method,
        purchase_minor=asset.purchase_minor,
        salvage_minor=asset.salvage_minor,
        useful_life_months=asset.useful_life_months,
        purchase_date=asset.purchase_date,
        start_at=start_at,
        end_as_of=end_as_of,
    )


__all__ = [
    "STRAIGHT_LINE",
    "Depreciable",
    "accumulated_depreciation_minor",
    "book_value_minor",
    "depreciation_expense_minor",
    "asset_accumulated_depreciation_minor",
    "asset_book_value_minor",
    "asset_depreciation_expense_minor",
]
