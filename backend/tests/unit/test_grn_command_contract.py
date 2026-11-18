"""Closed new producer domain and exact wire identity, not a replacement purchase policy."""

from __future__ import annotations

import json
from decimal import Decimal
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.schemas.grn_commands import MAX_MINOR, WireLine, parse_wire
from app.services.accounting.purchases import received_line_total_minor
from app.services.inventory.grn_commands import raw_hash


def body(qty="1.005", cost=100):
    return {
        "branch_id": str(uuid4()),
        "supplier_id": str(uuid4()),
        "lines": [{"ingredient_id": str(uuid4()), "qty": qty, "unit_cost_minor": cost}],
    }


@pytest.mark.parametrize(
    ("qty", "cost"),
    [
        ("0.0001", 5000),
        ("0.0001", 4999),
        ("0.0001", 5001),
        ("1.005", 100),
        ("9999999999.9999", 0),
        ("9007199254.7409", 1000000),
        ("1", MAX_MINOR),
    ],
)
def test_exact_integer_line_rounding_agrees_with_unchanged_decimal_authority(qty, cost):
    line = WireLine(ingredient_id=uuid4(), qty=qty, unit_cost_minor=cost)
    assert line.line_minor == received_line_total_minor(Decimal(qty), cost)


@pytest.mark.parametrize(
    "qty", ["0", "-1", "1.0000", "01", "1.00001", "1e2", "NaN", "Infinity", "10000000000", 1, True]
)
def test_noncanonical_or_overflow_quantity_rejects_before_any_economic_write(qty):
    with pytest.raises(ValidationError):
        parse_wire(json.dumps(body(qty)))


@pytest.mark.parametrize("cost", [True, "100", 1.0, -1, MAX_MINOR + 1])
def test_cost_is_a_bounded_primitive_integer(cost):
    with pytest.raises(ValidationError):
        parse_wire(json.dumps(body(cost=cost)))


def test_each_rounded_line_and_sum_must_fit_and_invoice_must_match():
    b = body("9999999999.9999", MAX_MINOR)
    with pytest.raises(ValidationError):
        parse_wire(json.dumps(b))
    b = body("1", MAX_MINOR)
    b["lines"] *= 2
    with pytest.raises(ValidationError):
        parse_wire(json.dumps(b))
    b = body()
    b["supplier_invoice_amount_minor"] = 100
    with pytest.raises(ValidationError):
        parse_wire(json.dumps(b))
    b["supplier_invoice_amount_minor"] = 101
    assert parse_wire(json.dumps(b)).lines[0].line_minor == 101


def test_duplicate_and_unknown_fields_are_not_normalized_away():
    b = body()
    wire = json.dumps(b)
    with pytest.raises(ValueError, match="Duplicate JSON field"):
        parse_wire(wire.replace('"lines":', '"lines":[],"lines":'))
    b["extra_private_field"] = "not allowed"
    with pytest.raises(ValidationError):
        parse_wire(json.dumps(b))


def test_hash_preserves_wire_order_whitespace_omission_numeric_spelling_and_unicode():
    b = body()
    wire = json.dumps(b, ensure_ascii=False)
    key = "grn:v1:" + str(uuid4())
    variants = [
        wire,
        json.dumps(b, sort_keys=True),
        json.dumps(b, separators=(",", ":")),
        wire.replace('"1.005"', "1.005"),
        json.dumps({**b, "notes": None}),
        json.dumps({**b, "notes": "കേരളം"}, ensure_ascii=False),
        json.dumps({**b, "notes": "കേരളം"}, ensure_ascii=True),
    ]
    assert len({raw_hash(key, v) for v in variants}) == len(variants)
    assert raw_hash(key, wire) != raw_hash(key + "x", wire)


@pytest.mark.parametrize(("qty", "cost"), [("0.01", 29), ("0.0001", 49), ("0.001", MAX_MINOR)])
def test_empty_inventory_cache_cannot_lose_one_paise_from_binary_float(qty, cost):
    from app.services.inventory.receipt_cache import receive_summary

    quantity, average = receive_summary(0, 0, qty, cost)
    assert quantity == Decimal(qty)
    assert average == cost


@pytest.mark.parametrize(
    ("previous", "average", "received", "cost", "expected_qty", "expected_average"),
    [
        ("-0.0001", 4, "0.0003", 1, "0.0002", 0),
        ("-2", 100, "1", 29, "-1", 29),
        ("-2", 100, "3", 0, "1", -200),
        ("9999999999.9998", MAX_MINOR, "0.0001", MAX_MINOR, "9999999999.9999", MAX_MINOR),
    ],
)
def test_cache_uses_exact_quantity_and_original_truncation_policy(
    previous, average, received, cost, expected_qty, expected_average
):
    from app.services.inventory.receipt_cache import receive_summary

    quantity, updated = receive_summary(previous, average, received, cost)
    assert quantity == Decimal(expected_qty)
    assert updated == expected_average


def test_duplicate_ingredient_lines_retain_sequential_rounding_not_one_new_aggregate_policy():
    from app.services.inventory.receipt_cache import receive_summary

    qty, average = Decimal(0), 0
    for cost in [2, 1, 3]:
        qty, average = receive_summary(qty, average, "1", cost)
    assert qty == 3
    assert average == 1  # A single aggregate average would be2.
