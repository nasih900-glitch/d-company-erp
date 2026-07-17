from collections import Counter, defaultdict
from datetime import date
from decimal import Decimal

from scripts.data.d_company_historical_reconciliation import (
    BAD_CAPITAL_ENTRIES,
    CAPITAL_IMPORT_ROWS,
    COLLECTION_IMPORT_ROWS,
    PARTNER_TARGETS,
    SYNTHETIC_ORDERS,
    TRANSACTIONS_MATCHED_MINOR,
    TRANSACTIONS_SHEET_ROW_COUNT,
    TRANSACTIONS_TOTAL_MINOR,
    validate_payload,
)


def test_payload_self_validation_passes() -> None:
    validate_payload()


def test_partner_capital_preserves_actual_contributions_not_equal_split() -> None:
    actual: dict[str, int] = defaultdict(int)
    counts: Counter[str] = Counter()
    for row in CAPITAL_IMPORT_ROWS:
        actual[row.partner_name] += row.amount_minor
        counts[row.partner_name] += 1

    assert actual == {
        "Nasih": 60_798_490,
        "Shemeer": 71_000_000,
        "Rafi": 75_144_300,
    }
    assert counts == {"Nasih": 14, "Shemeer": 21, "Rafi": 29}
    assert sum(actual.values()) == 206_942_790
    assert len(set(actual.values())) == 3


def test_ownership_is_equal_but_separate_from_capital() -> None:
    shares = {target.name: target.ownership_share_pct for target in PARTNER_TARGETS}

    assert shares == {
        "Nasih": Decimal("33.3334"),
        "Shemeer": Decimal("33.3333"),
        "Rafi": Decimal("33.3333"),
    }
    assert sum(shares.values()) == Decimal("100.0000")


def test_bad_capital_snapshot_is_the_exact_equal_split_being_voided() -> None:
    totals: dict[str, int] = defaultdict(int)
    for row in BAD_CAPITAL_ENTRIES:
        totals[row.partner_name] += row.amount_minor

    assert len(BAD_CAPITAL_ENTRIES) == 27
    assert totals == {
        "Nasih": 68_980_930,
        "Shemeer": 68_980_930,
        "Rafi": 68_980_930,
    }


def test_sheet_dates_are_not_reinterpreted() -> None:
    by_sheet_row = {row.sheet_row: row for row in CAPITAL_IMPORT_ROWS}

    assert by_sheet_row[18].business_date == date(2026, 4, 2)
    assert by_sheet_row[19].business_date == date(2026, 5, 2)


def test_legacy_orders_equal_only_july_11_to_15_collections() -> None:
    historical = [row for row in COLLECTION_IMPORT_ROWS if row.business_date < date(2026, 7, 16)]

    assert sum(row.amount_minor for row in historical) == 904_100
    assert sum(row.amount_minor for row in SYNTHETIC_ORDERS) == 904_100
    assert sorted(
        (row.business_date, row.amount_minor, row.method) for row in historical
    ) == sorted(
        (row.business_date, row.amount_minor, row.payment_method) for row in SYNTHETIC_ORDERS
    )


def test_july_16_owner_attested_collection_is_manual_daily() -> None:
    rows = [row for row in COLLECTION_IMPORT_ROWS if row.business_date == date(2026, 7, 16)]

    assert {(row.method, row.amount_minor, row.source_kind) for row in rows} == {
        ("cash", 21_000, "manual_daily"),
        ("upi", 162_000, "manual_daily"),
    }
    assert sum(row.amount_minor for row in COLLECTION_IMPORT_ROWS) == 1_087_100


def test_setup_transactions_remain_unclassified_pending_evidence() -> None:
    assert TRANSACTIONS_SHEET_ROW_COUNT == 503
    assert TRANSACTIONS_TOTAL_MINOR == 202_210_274
    assert TRANSACTIONS_MATCHED_MINOR == 114_684_205
    assert TRANSACTIONS_MATCHED_MINOR < TRANSACTIONS_TOTAL_MINOR
    assert 206_942_790 - TRANSACTIONS_TOTAL_MINOR == 4_732_516
