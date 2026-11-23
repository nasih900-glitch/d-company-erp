"""Fail-closed no-value eligibility and exact immutable review fingerprints."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from app.api.v1.pos.receipt_corrections import ReceiptCorrectionPreviewRequest
from app.services.pos.receipt_corrections import review_source, source_fingerprint


@pytest.fixture
def source():
    return {
        "order": {
            "id": "order",
            "company_id": "company",
            "branch_id": "branch",
            "terminal_id": "terminal",
            "shift_id": "shift",
            "type": "takeaway",
            "status": "paid",
            "invoice_no": "TEST/INVOICE",
            "fiscal_year": "2026-27",
            "invoice_issued_at": "2026-11-01T12:00:00Z",
            "closed_at": "2026-11-01T12:00:00Z",
            "source_integrity_revision": 48,
            "total_minor": 4600,
            "subtotal_minor": 4600,
        },
        "shift": {
            "id": "shift",
            "company_id": "company",
            "branch_id": "branch",
            "terminal_id": "terminal",
            "status": "closed",
            "closed_at": "2026-11-01T20:00:00Z",
        },
        "branch": {"id": "branch", "company_id": "company", "deleted_at": None},
        "terminal": {"id": "terminal", "branch_id": "branch"},
        "company": {"id": "company", "currency": "INR", "timezone": "Asia/Kolkata"},
        "lines": [
            {
                "id": "line",
                "menu_item_id": "product",
                "menu_item_name_snapshot": "Plain test drink",
                "menu_item_type_snapshot": "drink",
                "qty": 2,
                "unit_price_minor": 2300,
                "line_total_minor": 4600,
                "taxable_value_minor": 4600,
            }
        ],
        "payments": [
            {
                "id": "payment",
                "order_id": "order",
                "shift_id": "shift",
                "method": "upi",
                "amount_minor": 4600,
                "source_integrity_revision": 48,
            }
        ],
        **{
            key: []
            for key in (
                "refunds",
                "refund_requests",
                "tender_corrections",
                "corrections",
                "active_claims",
                "stock_movements",
                "benefits",
                "points_redemptions",
                "loyalty_settlements",
                "rank_awards",
                "gaming_sessions",
                "gaming_voids",
                "gaming_links",
                "gaming_stations",
                "gaming_addons",
            )
        },
    }


def test_plain_multiple_quantity_receipt_is_reviewable_without_mutation(source):
    before = deepcopy(source)
    review = review_source(source)
    assert review.eligible
    assert not review.blockers
    assert review.amount_minor == 4600
    assert source == before
    assert review.source_fingerprint == source_fingerprint(deepcopy(source))


@pytest.mark.parametrize(
    "key",
    [
        "refunds",
        "refund_requests",
        "tender_corrections",
        "corrections",
        "active_claims",
        "stock_movements",
        "benefits",
        "points_redemptions",
        "loyalty_settlements",
        "rank_awards",
    ],
)
def test_recorded_side_effect_blocks_even_if_current_catalogue_changed(source, key):
    source[key].append({"id": "existing-evidence"})
    assert not review_source(source).eligible


@pytest.mark.parametrize(
    ("section", "key", "value"),
    [
        ("order", "tax_minor", 1),
        ("order", "tip_minor", 1),
        ("order", "round_off_minor", -1),
        ("order", "manual_discount_minor", 1),
        ("order", "customer_id", "customer"),
        ("order", "customer_phone", "test-phone"),
        ("order", "status", "held"),
        ("order", "irn", "issued-einvoice"),
        ("order", "source_integrity_revision", None),
        ("shift", "status", "open"),
        ("shift", "company_id", "other-company"),
        ("terminal", "branch_id", "other-branch"),
        ("branch", "deleted_at", "2026-01-01T00:00:00Z"),
    ],
)
def test_unsupported_or_incomplete_source_blocks(source, section, key, value):
    source[section][key] = value
    assert not review_source(source).eligible


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("method", "cash"),
        ("amount_minor", 4500),
        ("ref_external", "provider-reference"),
        ("change_minor", 0),
        ("shift_id", "other-shift"),
        ("source_integrity_revision", None),
    ],
)
def test_not_one_complete_manual_upi_payment_blocks(source, key, value):
    source["payments"][0][key] = value
    assert not review_source(source).eligible


def test_mixed_payment_and_zero_total_block(source):
    source["payments"].append(deepcopy(source["payments"][0]))
    assert not review_source(source).eligible
    source["payments"].pop()
    source["order"]["total_minor"] = 0
    assert not review_source(source).eligible


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("menu_item_type_snapshot", "gaming"),
        ("qty", "NaN"),
        ("qty", -1),
        ("discount_minor", 1),
        ("cgst_minor", 1),
        ("line_total_minor", 4500),
        ("taxable_value_minor", 4500),
        ("kitchen_released_at", "2026-11-01T12:00:00Z"),
        ("variant_snapshot", {"id": "variant"}),
        ("unit_price_minor", 2300.0),
    ],
)
def test_nonplain_line_blocks(source, key, value):
    source["lines"][0][key] = value
    assert not review_source(source).eligible


def retained(source):
    source["order"]["type"] = "session"
    source["lines"][0].update(
        created_at="2026-11-01T10:00:00Z",
        kitchen_released_at="2026-11-01T10:00:00Z",
        kitchen_served_at="2026-11-01T10:00:00Z",
        kitchen_status="served",
        kitchen_round_no=1,
    )
    session = {
        "id": "gaming",
        "company_id": "company",
        "status": "cancelled",
        "order_id": None,
        "retained_addon_order_id": "order",
        "amount_minor": 0,
        "billable_minutes": 0,
        "station_id": "station",
        "cancelled_at": "at",
        "cancelled_by": "actor",
        "cancel_reason": "original cancellation",
    }
    void = {
        "id": "void",
        "company_id": "company",
        "mode": "unlinked_addons",
        "retained_order_id": "order",
        "gaming_session_id": "gaming",
        "retained_total_minor": 4600,
        "addon_count": 1,
        "voided_at": "at",
        "actor_user_id": "actor",
        "reason": "original cancellation",
    }
    addon = {
        **source["lines"][0],
        "id": "addon",
        "company_id": "company",
        "gaming_session_id": "gaming",
    }
    source.update(
        gaming_sessions=[session],
        gaming_voids=[void],
        gaming_addons=[addon],
        gaming_stations=[{"id": "station", "company_id": "company", "branch_id": "branch"}],
        gaming_links=[
            {
                "link": {
                    "receipt_id": "void",
                    "addon_id": "addon",
                    "order_line_id": "line",
                    "retained_order_id": "order",
                },
                "addon": addon,
            }
        ],
    )


def test_exact_cancelled_gaming_void_history_remains_allowed(source):
    retained(source)
    before = deepcopy(source)
    assert review_source(source).eligible
    assert source == before


def auto_materialization(source):
    retained(source)
    source["gaming_links"][0]["addon"]["created_at"] = "2026-11-01T10:00:00Z"
    source["lines"][0].update(
        created_at="2026-11-01T10:00:00Z",
        kitchen_released_at="2026-11-01T10:00:00Z",
        kitchen_served_at="2026-11-01T10:00:00Z",
        kitchen_status="served",
        kitchen_round_no=1,
    )


def test_automatic_retained_timestamps_are_not_a_separate_delivery_event(source):
    auto_materialization(source)
    source["lines"][0]["qty"] = 2.000
    assert review_source(source).eligible


@pytest.mark.parametrize(
    "field",
    [
        "created_at",
        "kitchen_released_at",
        "kitchen_served_at",
        "kitchen_status",
        "kitchen_round_no",
    ],
)
def test_other_kitchen_history_is_not_mistaken_for_auto_materialization(source, field):
    auto_materialization(source)
    source["lines"][0][field] = "different"
    assert not review_source(source).eligible


def test_retained_history_does_not_waive_actual_stock_consumption(source):
    auto_materialization(source)
    source["stock_movements"] = [{"id": "actual-consumption", "qty_delta": -2}]
    assert not review_source(source).eligible


def test_unmapped_active_addon_or_missing_auto_materialization_blocks(source):
    retained(source)
    source["gaming_addons"].append({"id": "unmapped-active-addon"})
    assert not review_source(source).eligible
    source["gaming_addons"].pop()
    source["lines"][0]["kitchen_served_at"] = None
    assert not review_source(source).eligible


@pytest.mark.parametrize(
    ("collection", "key", "value"),
    [
        ("gaming_sessions", "status", "running"),
        ("gaming_sessions", "amount_minor", 1),
        ("gaming_sessions", "billable_minutes", 1),
        ("gaming_sessions", "order_id", "order"),
        ("gaming_voids", "mode", "linked_pos"),
        ("gaming_voids", "addon_count", 2),
        ("gaming_voids", "retained_total_minor", 4500),
        ("gaming_stations", "branch_id", "other-branch"),
    ],
)
def test_live_or_mismatched_gaming_provenance_blocks(source, collection, key, value):
    retained(source)
    source[collection][0][key] = value
    assert not review_source(source).eligible


def test_mapping_and_historical_source_drift_changes_fingerprint(source):
    retained(source)
    before = source_fingerprint(source)
    source["gaming_links"][0]["addon"]["qty"] = 1
    assert not review_source(source).eligible
    assert before != source_fingerprint(source)
    source["shift"]["counted_minor"] = 123
    assert before != source_fingerprint(source)


@pytest.mark.parametrize("extra", ["amount_minor", "actor_id", "accounting_at", "payment_id"])
def test_preview_cannot_choose_money_actor_accounting_date_or_payment(extra):
    with pytest.raises(ValidationError):
        ReceiptCorrectionPreviewRequest.model_validate(
            {
                "order_id": "00000000-0000-0000-0000-000000000001",
                extra: "anything",
            }
        )


@pytest.mark.parametrize("rate", [0, "0.4", "1.0"])
def test_configured_rate_with_zero_actual_tax_is_not_tax_collection(source, rate):
    source["lines"][0]["tax_rate"] = rate
    assert review_source(source).eligible


@pytest.mark.parametrize("rate", ["NaN", "Infinity", "-0.1", "1.01", "invalid"])
def test_invalid_configured_rate_is_never_reviewable(source, rate):
    source["lines"][0]["tax_rate"] = rate
    assert not review_source(source).eligible
