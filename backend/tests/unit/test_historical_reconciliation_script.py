import pytest

from app.services.accounting.accounts import (
    DEFAULT_CHART_OF_ACCOUNTS,
    DEFAULT_EXPENSE_CATEGORY_ACCOUNTS,
)
from scripts.data.d_company_historical_reconciliation import CAPITAL_IMPORT_ROWS
from scripts.reconcile_d_company_history import (
    ACCOUNT_SPECS,
    CONFIRMATION,
    EXPECTED_EXPENSE_CATEGORY_IDS,
    EXPECTED_LIVE_ACCOUNT_IDS,
    LEGACY_ACCOUNT_NAMES,
    SETUP_CREDIT_LINE_ID,
    SETUP_DEBIT_LINE_ID,
    SETUP_ENTRY_ID,
    SETUP_MEMO,
    ComponentState,
    ReconciliationError,
    _append_marker,
    _parse_args,
    classify_presence,
    state_fingerprint,
)


def test_state_fingerprint_is_key_order_stable_and_change_sensitive() -> None:
    left = {"company": {"name": "D Company", "id": "1"}, "rows": [1, 2]}
    right = {"rows": [1, 2], "company": {"id": "1", "name": "D Company"}}

    assert state_fingerprint(left) == state_fingerprint(right)
    assert state_fingerprint(left) != state_fingerprint({**left, "rows": [1, 3]})


@pytest.mark.parametrize(
    ("found", "expected", "state"),
    [(0, 64, ComponentState.FRESH), (64, 64, ComponentState.APPLIED)],
)
def test_classify_presence_accepts_only_fresh_or_complete(
    found: int,
    expected: int,
    state: ComponentState,
) -> None:
    assert classify_presence(found=found, expected=expected, label="capital") is state


def test_classify_presence_rejects_partial_state() -> None:
    with pytest.raises(ReconciliationError, match="partially applied"):
        classify_presence(found=1, expected=64, label="capital")


def test_order_marker_append_is_idempotent_and_preserves_original_note() -> None:
    marker = "[reconciliation=v1]"
    once = _append_marker("original evidence", marker)

    assert once == "original evidence [reconciliation=v1]"
    assert _append_marker(once, marker) == once


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


def test_reconciliation_identifiers_and_memo_are_deterministic() -> None:
    assert len({SETUP_ENTRY_ID, SETUP_DEBIT_LINE_ID, SETUP_CREDIT_LINE_ID}) == 3
    assert "503 rows" in SETUP_MEMO
    assert "matched_minor=114684205" in SETUP_MEMO
    assert "unmatched_minor=87526069" in SETUP_MEMO
    assert len(SETUP_MEMO) <= 500


def test_capital_source_refs_are_globally_namespaced() -> None:
    refs = {row.source_ref for row in CAPITAL_IMPORT_ROWS}

    assert len(refs) == 64
    assert all(ref.startswith("dcompany:partner-investments:row:") for ref in refs)


def test_chart_reconciliation_covers_canonical_accounts_and_live_categories() -> None:
    canonical_codes = {account.code for account in DEFAULT_CHART_OF_ACCOUNTS}

    assert set(ACCOUNT_SPECS) == canonical_codes
    assert set(EXPECTED_LIVE_ACCOUNT_IDS) < canonical_codes
    assert set(EXPECTED_EXPENSE_CATEGORY_IDS) == set(DEFAULT_EXPENSE_CATEGORY_ACCOUNTS)
    assert ACCOUNT_SPECS["1185"] == (
        "Historical Funds Pending Reconciliation",
        "asset",
        "dr",
    )
    assert ACCOUNT_SPECS["1490"] == (
        "Historical Setup Costs Pending Classification",
        "asset",
        "dr",
    )


def test_only_audited_legacy_account_names_can_be_renamed() -> None:
    assert LEGACY_ACCOUNT_NAMES == {
        "1110": "UPI Clearing",
        "2110": "Output CGST Payable",
        "2120": "Output SGST Payable",
        "2130": "Output IGST Payable",
        "2140": "Output Cess Payable",
        "2400": "Tips Payable to Staff",
        "4000": "Revenue — Food",
        "4160": "Revenue - Streaming",
        "5000": "COGS",
        "5800": "Round-off (Income/Expense)",
    }
