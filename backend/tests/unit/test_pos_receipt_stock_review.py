"""Inventory evidence, historical shortages and fractional cost allocation."""

# pytest fixtures retain their injected names.
# ruff: noqa: F811
from copy import deepcopy

import pytest

from app.services.pos.receipt_corrections import review_source
from app.services.pos.receipt_stock import review_stock_source
from tests.unit.test_pos_receipt_corrections import source  # noqa: F401


@pytest.fixture
def stock_source(source):
    movement = {
        "id": "movement",
        "batch_id": "batch",
        "type": "sale",
        "ref_type": "order",
        "ref_id": "order",
        "branch_id": "branch",
        "qty_delta": -2,
        "cost_per_unit_minor": 101,
        "created_at": "2026-01-01T00:00:00+00:00",
    }
    source["stock_movements"] = [movement]
    batch = {
        "id": "batch",
        "ingredient_id": "ingredient",
        "branch_id": "branch",
        "qty_initial": 5,
        "qty_on_hand": 3,
        "cost_per_unit_minor": 101,
        "lot_code": None,
    }
    return {
        "receipt": source,
        "restorations": [],
        "batches": [
            {
                "batch": batch,
                "ingredient": {
                    "id": "ingredient",
                    "name": "Historical ingredient",
                    "base_unit": "unit",
                    "company_id": "company",
                    "deleted_at": None,
                    "current_qty": 3,
                },
                "branch": {"company_id": "company"},
                "movements": [deepcopy(movement)],
                "ingredient_batches": [{**batch, "company_id": "company"}],
            }
        ],
    }


def test_exact_original_stock_can_use_only_the_separate_contract(stock_source):
    review = review_stock_source(stock_source)
    assert review.blockers == ()
    assert review.inventory_increase_minor == 202
    assert review.movements[0]["quantity"] == "2"
    assert review.movements[0]["base_unit"] == "unit"
    assert review_source(stock_source["receipt"]).blockers == ("recorded_stock_or_cogs_movement",)


@pytest.mark.parametrize(
    "bad",
    [
        "scope",
        "branch",
        "batch_balance",
        "ingredient_balance",
        "cost",
        "unknown_cost",
        "shortage_lot",
        "missing_batch",
        "non_sale",
        "unrelated_ref",
        "positive_sale",
        "future_trail",
        "unit",
    ],
)
def test_unreconciled_or_unrelated_stock_is_never_silently_filtered(stock_source, bad):
    item = stock_source["batches"][0]
    if bad == "unit":
        item["ingredient"]["base_unit"] = "bottle"
    elif bad == "scope":
        item["ingredient"]["company_id"] = "other"
    elif bad == "branch":
        item["batch"]["branch_id"] = "other"
    elif bad == "batch_balance":
        item["batch"]["qty_on_hand"] = 4
    elif bad == "ingredient_balance":
        item["ingredient"]["current_qty"] = 4
    elif bad == "cost":
        item["movements"][0]["cost_per_unit_minor"] = 100
    elif bad == "unknown_cost":
        item["batch"]["cost_per_unit_minor"] = 0
    elif bad == "shortage_lot":
        item["batch"]["lot_code"] = "SYSTEM-UNCOSTED-SHORTAGE"
    elif bad == "missing_batch":
        stock_source["batches"] = []
    elif bad == "non_sale":
        stock_source["receipt"]["stock_movements"][0]["type"] = "adjustment"
    elif bad == "unrelated_ref":
        stock_source["receipt"]["stock_movements"][0]["ref_type"] = "unrelated"
    elif bad == "positive_sale":
        stock_source["receipt"]["stock_movements"][0]["qty_delta"] = 2
    elif bad == "future_trail":
        item["movements"][0]["created_at"] = "2200-01-01T00:00:00+00:00"
    assert "unreconciled_stock_scope_quantity_or_cost" in review_stock_source(stock_source).blockers


def test_historical_positive_cost_shortage_is_blocked_even_after_balance_recovers(stock_source):
    item = stock_source["batches"][0]
    template = item["movements"][0]
    item["movements"] = [
        {**template, "type": "adjustment", "qty_delta": -6},
        {**template, "type": "adjustment", "qty_delta": 6},
        template,
    ]
    assert "unreconciled_stock_scope_quantity_or_cost" in review_stock_source(stock_source).blockers


def test_fractional_paise_use_current_chronological_batch_value_delta(stock_source):
    item = stock_source["batches"][0]
    original = stock_source["receipt"]["stock_movements"][0]
    original.update(qty_delta=-0.5, cost_per_unit_minor=1)
    item["movements"] = [deepcopy(original), {**original, "id": "later", "ref_id": "another_order"}]
    item["batch"].update(qty_initial=2, qty_on_hand=1, cost_per_unit_minor=1)
    item["ingredient"]["current_qty"] = 1
    item["ingredient_batches"][0].update(qty_initial=2, qty_on_hand=1, cost_per_unit_minor=1)
    review = review_stock_source(stock_source)
    assert not review.blockers
    # Initial2 ->1.5 consumed0 rounded paise; intervening1.5 ->1 consumed1.
    # Restoration1 ->1.5 restores1, without rewriting either historical row.
    assert review.inventory_increase_minor == 1


def test_existing_restoration_and_other_financial_effects_still_block(stock_source):
    stock_source["restorations"] = [{"original_movement_id": "movement"}]
    stock_source["receipt"]["refund_requests"] = [{"id": "request"}]
    assert set(review_stock_source(stock_source).blockers) == {
        "recorded_refund_request",
        "stock_already_restored",
    }
