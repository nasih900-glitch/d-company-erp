from scripts.data.d_company_historical_reconciliation import (
    TRANSACTIONS_SHEET_ROW_COUNT,
    TRANSACTIONS_TOTAL_MINOR,
)
from scripts.data.d_company_transaction_itemization import (
    CATEGORY_TOTALS,
    ITEMIZATION_SOURCE_REF_PREFIX,
    SETUP_VOID_REASON,
    validate_payload,
)


def test_payload_self_validation_passes() -> None:
    validate_payload()


def test_category_totals_sum_to_the_audited_lump_total() -> None:
    assert sum(row.amount_minor for row in CATEGORY_TOTALS) == TRANSACTIONS_TOTAL_MINOR


def test_category_row_counts_sum_to_the_audited_sheet_row_count() -> None:
    assert sum(row.row_count for row in CATEGORY_TOTALS) == TRANSACTIONS_SHEET_ROW_COUNT


def test_fourteen_categories_with_exact_breakdown() -> None:
    by_name = {row.category_name: (row.amount_minor, row.row_count) for row in CATEGORY_TOTALS}
    assert by_name == {
        "Building Materials": (86_610_600, 190),
        "Gaming Equipments": (42_389_800, 13),
        "Labour Charges": (34_305_800, 112),
        "Others": (14_132_200, 23),
        "Wages": (5_654_400, 7),
        "Cafe Equipments": (5_181_600, 4),
        "Furnitures": (3_379_900, 10),
        "Rent": (3_050_900, 25),
        "Design & Architecture": (3_000_000, 4),
        "Transportation Cost": (2_030_174, 53),
        "Kitchen Equipments": (967_800, 3),
        "Marketing": (600_000, 1),
        "Food & Water": (473_100, 53),
        "PaperWork Charges": (434_000, 5),
    }


def test_source_refs_are_unique_and_namespaced() -> None:
    refs = {row.source_ref for row in CATEGORY_TOTALS}
    assert len(refs) == 14
    assert all(ref.startswith(f"{ITEMIZATION_SOURCE_REF_PREFIX}:") for ref in refs)


def test_void_reason_meets_the_journal_entry_check_constraint() -> None:
    assert len(SETUP_VOID_REASON.strip()) >= 3
    assert len(SETUP_VOID_REASON) <= 500
