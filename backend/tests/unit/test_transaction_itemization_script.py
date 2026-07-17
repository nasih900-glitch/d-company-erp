import pytest

from scripts.data.d_company_transaction_itemization import CATEGORY_TOTALS
from scripts.itemize_d_company_setup_transactions import (
    CONFIRMATION,
    itemized_credit_line_id,
    itemized_debit_line_id,
    itemized_entry_id,
    itemized_memo,
    _parse_args,
)
from scripts.reconcile_d_company_history import CONFIRMATION as BASE_CONFIRMATION
from scripts.reconcile_d_company_history import SETUP_ENTRY_ID, _lock_key
from scripts.itemize_d_company_setup_transactions import _lock_key as script_lock_key


def test_confirmation_phrase_is_distinct_from_the_base_reconciliation() -> None:
    assert CONFIRMATION != BASE_CONFIRMATION
    assert "ITEMIZATION" in CONFIRMATION


def test_shares_the_base_reconciliations_advisory_lock_key() -> None:
    # Both scripts touch the same journal_entries rows and must never run
    # concurrently with each other or with themselves.
    assert script_lock_key() == _lock_key()


def test_apply_cli_requires_confirmation_and_fingerprint() -> None:
    with pytest.raises(SystemExit):
        _parse_args(["--apply"])
    with pytest.raises(SystemExit):
        _parse_args(["--apply", "--confirm", CONFIRMATION])

    parsed = _parse_args(
        [
            "--apply",
            "--confirm",
            CONFIRMATION,
            "--expected-state-fingerprint",
            "a" * 64,
        ]
    )
    assert parsed.apply is True


def test_apply_cli_rejects_the_base_reconciliations_confirmation_phrase() -> None:
    with pytest.raises(SystemExit):
        _parse_args(
            [
                "--apply",
                "--confirm",
                BASE_CONFIRMATION,
                "--expected-state-fingerprint",
                "a" * 64,
            ]
        )


def test_itemized_entry_ids_are_deterministic_and_unique_per_category() -> None:
    ids = {itemized_entry_id(row.category_name) for row in CATEGORY_TOTALS}
    assert len(ids) == len(CATEGORY_TOTALS)
    # Deterministic: same category name always yields the same UUID.
    assert itemized_entry_id("Rent") == itemized_entry_id("Rent")
    # Namespaced away from the lump entry it replaces.
    assert SETUP_ENTRY_ID not in ids


def test_itemized_line_ids_are_deterministic_and_distinct_from_each_other() -> None:
    entry_id = itemized_entry_id("Rent")
    debit_id = itemized_debit_line_id(entry_id)
    credit_id = itemized_credit_line_id(entry_id)
    assert debit_id != credit_id
    assert itemized_debit_line_id(entry_id) == debit_id


def test_itemized_memo_stays_within_the_journal_line_length_limit() -> None:
    longest = max(CATEGORY_TOTALS, key=lambda row: len(row.category_name))
    memo = itemized_memo(longest.category_name, longest.row_count, longest.amount_minor)
    assert len(memo) <= 500
    assert longest.category_name in memo
