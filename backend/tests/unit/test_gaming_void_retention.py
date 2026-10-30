from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.core.errors import BusinessRuleError
from app.services.gaming.void import (
    immutable_addon_snapshot,
    require_exact_addon_line_mapping,
    require_exact_reviewed_order_aggregate,
)
from app.services.pos.pricing import aggregate_immutable_line_snapshots


def _addon(**changes):
    values = {
        "id": uuid4(),
        "client_line_id": uuid4(),
        "menu_item_id": uuid4(),
        "qty": 2,
        "unit_price_minor": 625,
        "line_total_minor": 1_249,
        "discount_minor": 0,
        "taxable_value_minor": 1_249,
        "cgst_minor": 0,
        "sgst_minor": 0,
        "igst_minor": 0,
        "cess_minor": 0,
        "hsn_or_sac": "2202",
        "tax_rate": 0,
        "menu_item_name_snapshot": "Cold can",
        "menu_item_type_snapshot": "drink",
        "variant_id": None,
        "variant_snapshot": None,
        "modifiers": None,
        "note": None,
        "created_at": datetime.now(UTC),
    }
    values.update(changes)
    return SimpleNamespace(**values)


def _line(addon, **changes):
    values = {
        "id": uuid4(),
        "client_line_id": addon.client_line_id,
        "menu_item_id": addon.menu_item_id,
        "qty": addon.qty,
        "unit_price_minor": addon.unit_price_minor,
        "line_total_minor": addon.line_total_minor,
        "discount_minor": addon.discount_minor,
        "taxable_value_minor": addon.taxable_value_minor,
        "cgst_minor": addon.cgst_minor,
        "sgst_minor": addon.sgst_minor,
        "igst_minor": addon.igst_minor,
        "cess_minor": addon.cess_minor,
        "hsn_or_sac": addon.hsn_or_sac,
        "tax_rate": addon.tax_rate,
        "menu_item_name_snapshot": addon.menu_item_name_snapshot,
        "menu_item_type_snapshot": addon.menu_item_type_snapshot,
        "variant_id": addon.variant_id,
        "variant_snapshot": addon.variant_snapshot,
        "modifiers": addon.modifiers,
        "note": addon.note,
        "kitchen_status": "served",
        "kitchen_released_at": addon.created_at,
        "kitchen_served_at": addon.created_at,
        "created_at": addon.created_at,
    }
    values.update(changes)
    return SimpleNamespace(**values)


def _mapped(addon, *, charge_changes=None, line_changes=None):
    charge_values = {
        "id": uuid4(),
        "client_line_id": None,
        "qty": 1,
        "menu_item_type_snapshot": "gaming",
        "unit_price_minor": 5_100,
        "line_total_minor": 5_100,
        "discount_minor": 0,
        "taxable_value_minor": 5_100,
        "cgst_minor": 0,
        "sgst_minor": 0,
        "igst_minor": 0,
        "cess_minor": 0,
    }
    charge_values.update(charge_changes or {})
    charge = SimpleNamespace(**charge_values)
    return require_exact_addon_line_mapping(
        addons=[addon],
        active_lines=[charge, _line(addon, **(line_changes or {}))],
        expected_charge_item_type="gaming",
        original_session_amount_minor=5_100,
    )


def test_snapshot_aggregation_preserves_tax_parts_and_rounds_only_order_total() -> None:
    first = _addon()
    second = _addon(
        client_line_id=uuid4(),
        menu_item_id=uuid4(),
        qty=1,
        unit_price_minor=551,
        line_total_minor=551,
        taxable_value_minor=500,
        cgst_minor=25,
        sgst_minor=26,
    )
    totals = aggregate_immutable_line_snapshots(
        [immutable_addon_snapshot(first), immutable_addon_snapshot(second)]
    )
    assert totals.subtotal_minor == 1_749
    assert totals.tax_minor == 51
    assert totals.round_off_minor == 0
    assert totals.total_minor == 1_800


def test_snapshot_aggregation_rejects_changed_tax_arithmetic() -> None:
    corrupt = _addon(line_total_minor=1_250)
    with pytest.raises(BusinessRuleError, match="immutable tax parts"):
        aggregate_immutable_line_snapshots([immutable_addon_snapshot(corrupt)])


def test_reviewed_order_aggregate_binds_every_financial_bucket() -> None:
    lines = [
        SimpleNamespace(
            line_total_minor=4_590,
            discount_minor=510,
            taxable_value_minor=4_100,
            cgst_minor=245,
            sgst_minor=245,
            igst_minor=0,
            cess_minor=0,
        ),
        SimpleNamespace(
            line_total_minor=1_249,
            discount_minor=0,
            taxable_value_minor=1_249,
            cgst_minor=0,
            sgst_minor=0,
            igst_minor=0,
            cess_minor=0,
        ),
    ]
    order = SimpleNamespace(
        subtotal_minor=5_349,
        cgst_minor=245,
        sgst_minor=245,
        igst_minor=0,
        cess_minor=0,
        tax_minor=490,
        discount_minor=510,
        round_off_minor=-39,
        total_minor=5_800,
    )
    require_exact_reviewed_order_aggregate(order=order, active_lines=lines)

    order.total_minor = 5_801
    with pytest.raises(BusinessRuleError, match="totals no longer match"):
        require_exact_reviewed_order_aggregate(order=order, active_lines=lines)


def test_linked_mapping_requires_one_charge_and_exact_addon_bijection() -> None:
    addon = _addon()
    addon_line = _line(addon)
    charge_line = SimpleNamespace(
        id=uuid4(),
        client_line_id=None,
        qty=1,
        menu_item_type_snapshot="gaming",
        unit_price_minor=5_100,
        line_total_minor=5_100,
        discount_minor=0,
        taxable_value_minor=5_100,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
    )
    charge, mappings, charge_snapshot = require_exact_addon_line_mapping(
        addons=[addon],
        active_lines=[charge_line, addon_line],
        expected_charge_item_type="gaming",
        original_session_amount_minor=5_100,
    )
    assert charge is charge_line
    assert mappings == [(addon, addon_line)]
    assert charge_snapshot.line_total_minor == 5_100

    with pytest.raises(BusinessRuleError, match="changed after review"):
        require_exact_addon_line_mapping(
            addons=[addon],
            active_lines=[charge_line, _line(addon, line_total_minor=1_250)],
            expected_charge_item_type="gaming",
            original_session_amount_minor=5_100,
        )
    with pytest.raises(BusinessRuleError, match="changed after review"):
        require_exact_addon_line_mapping(
            addons=[addon],
            active_lines=[charge_line, _line(addon, kitchen_served_at=None)],
            expected_charge_item_type="gaming",
            original_session_amount_minor=5_100,
        )
    with pytest.raises(BusinessRuleError, match="exactly one Gaming charge"):
        require_exact_addon_line_mapping(
            addons=[addon],
            active_lines=[
                charge_line,
                SimpleNamespace(id=uuid4(), client_line_id=None),
                addon_line,
            ],
            expected_charge_item_type="gaming",
            original_session_amount_minor=5_100,
        )
    charge, _mappings, discounted = _mapped(
        addon,
        charge_changes={
            "unit_price_minor": 4_590,
            "line_total_minor": 4_590,
            "discount_minor": 510,
            "taxable_value_minor": 4_590,
        },
    )
    assert charge.line_total_minor == 4_590
    assert discounted.discount_minor == 510

    with pytest.raises(BusinessRuleError, match="inconsistent immutable pricing"):
        _mapped(
            addon,
            charge_changes={
                "line_total_minor": 5_099,
                "taxable_value_minor": 5_100,
            },
        )
