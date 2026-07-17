"""Audited per-category totals for itemizing the setup-transactions journal.

The July 2026 historical reconciliation (see
d_company_historical_reconciliation.py) parked all 503 rows of the owner's
Transactions sheet in one lump "pending classification" entry because the
sheet's *receipt-matched* status was incomplete for many rows. But the
sheet's Category column — a separate field — is populated on every real
transaction row: verified via a live `gviz` query grouping the entire sheet
by Category, which returned exactly 14 categories summing to both the
audited row count (503) and the audited total (TRANSACTIONS_TOTAL_MINOR,
imported from the reconciliation data module) to the paisa. The 15th chart
category, Utilities, has zero rows in this sheet — nothing was logged under
it during the tracked period.

Amounts are integer paise, derived from the sheet's Amount (₹) column
(rupees × 100). Verified 2026-07-17 via:
  gviz/tq?tqx=out:html&tq=select D,count(A),sum(E) where D is not null
  group by D order by sum(E) desc
against gid=1426382815 of the owner's Google Sheet.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.services.accounting.accounts import DEFAULT_EXPENSE_CATEGORY_ACCOUNTS
from scripts.data.d_company_historical_reconciliation import (
    TRANSACTIONS_SHEET_ROW_COUNT,
    TRANSACTIONS_TOTAL_MINOR,
)

ITEMIZATION_VERSION = "d-company-transaction-itemization-v1"
ITEMIZATION_SOURCE_REF_PREFIX = "historical-setup-reconciliation-itemized:2026-07-17"
SETUP_VOID_REASON = (
    "Lump-sum placeholder replaced by per-category itemized entries sourced from "
    "the Transactions sheet's own Category column, verified complete across all "
    f"{TRANSACTIONS_SHEET_ROW_COUNT} rows ({ITEMIZATION_VERSION})."
)


@dataclass(frozen=True, slots=True)
class CategoryTotal:
    category_name: str
    amount_minor: int
    row_count: int

    @property
    def source_ref(self) -> str:
        return f"{ITEMIZATION_SOURCE_REF_PREFIX}:{self.category_name}"


CATEGORY_TOTALS = (
    CategoryTotal("Building Materials", 86_610_600, 190),
    CategoryTotal("Gaming Equipments", 42_389_800, 13),
    CategoryTotal("Labour Charges", 34_305_800, 112),
    CategoryTotal("Others", 14_132_200, 23),
    CategoryTotal("Wages", 5_654_400, 7),
    CategoryTotal("Cafe Equipments", 5_181_600, 4),
    CategoryTotal("Furnitures", 3_379_900, 10),
    CategoryTotal("Rent", 3_050_900, 25),
    CategoryTotal("Design & Architecture", 3_000_000, 4),
    CategoryTotal("Transportation Cost", 2_030_174, 53),
    CategoryTotal("Kitchen Equipments", 967_800, 3),
    CategoryTotal("Marketing", 600_000, 1),
    CategoryTotal("Food & Water", 473_100, 53),
    CategoryTotal("PaperWork Charges", 434_000, 5),
)


def validate_payload() -> None:
    """Fail fast if a future edit corrupts the audited category breakdown."""
    if len(CATEGORY_TOTALS) != 14:
        raise ValueError("expected exactly 14 categories with rows in the sheet")
    if len({row.category_name for row in CATEGORY_TOTALS}) != 14:
        raise ValueError("category names must be unique")
    if len({row.source_ref for row in CATEGORY_TOTALS}) != 14:
        raise ValueError("category source refs must be unique")

    total_minor = sum(row.amount_minor for row in CATEGORY_TOTALS)
    if total_minor != TRANSACTIONS_TOTAL_MINOR:
        raise ValueError(
            "category totals must sum to the audited TRANSACTIONS_TOTAL_MINOR: "
            f"got {total_minor}, expected {TRANSACTIONS_TOTAL_MINOR}"
        )

    total_rows = sum(row.row_count for row in CATEGORY_TOTALS)
    if total_rows != TRANSACTIONS_SHEET_ROW_COUNT:
        raise ValueError(
            "category row counts must sum to the audited TRANSACTIONS_SHEET_ROW_COUNT: "
            f"got {total_rows}, expected {TRANSACTIONS_SHEET_ROW_COUNT}"
        )

    for row in CATEGORY_TOTALS:
        if row.amount_minor <= 0 or row.row_count <= 0:
            raise ValueError(f"category {row.category_name!r} must have a positive total and row count")

    category_names = {row.category_name for row in CATEGORY_TOTALS}
    unknown = category_names - set(DEFAULT_EXPENSE_CATEGORY_ACCOUNTS)
    if unknown:
        raise ValueError(f"unknown category names not in the canonical chart: {sorted(unknown)}")
    uncovered = set(DEFAULT_EXPENSE_CATEGORY_ACCOUNTS) - category_names
    if uncovered != {"Utilities"}:
        raise ValueError(
            "expected exactly one canonical category with no sheet rows (Utilities); "
            f"got: {sorted(uncovered)}"
        )


validate_payload()
