"""Static and pure contracts for normalized menu customizations."""

from __future__ import annotations

from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.api.v1.menu.router import ModifierGroupCreate, ModifierOptionCreate
from app.api.v1.pos.router import OrderLineCreate
from app.models import MenuModifier, MenuModifierGroup, MenuVariant, OrderLine
from app.services.audit.recorder import TRACKED
from app.services.pos.pricing import (
    LineRequest,
    ModifierSelection,
    PricedModifierSnapshot,
    PricedVariantSnapshot,
)


def test_menu_customization_models_are_tenant_scoped_constrained_and_audited() -> None:
    group_constraints = {constraint.name for constraint in MenuModifierGroup.__table__.constraints}
    option_constraints = {constraint.name for constraint in MenuModifier.__table__.constraints}
    variant_constraints = {constraint.name for constraint in MenuVariant.__table__.constraints}
    order_line_constraints = {constraint.name for constraint in OrderLine.__table__.constraints}
    group_indexes = {index.name for index in MenuModifierGroup.__table__.indexes}
    option_indexes = {index.name for index in MenuModifier.__table__.indexes}

    assert "fk_menu_modifier_groups_company_item" in group_constraints
    assert "ck_menu_modifier_group_selection_bounds" in group_constraints
    assert "fk_menu_modifiers_group_scope" in option_constraints
    assert "ck_menu_modifier_max_quantity" in option_constraints
    assert "fk_menu_variants_company_item" in variant_constraints
    assert "ck_order_line_variant_snapshot_object" in order_line_constraints
    assert OrderLine.__table__.c.variant_snapshot.type.none_as_null is True
    assert OrderLine.__table__.c.modifiers.type.none_as_null is True
    assert "uq_menu_modifier_groups_company_item_name_ci" in group_indexes
    assert "ix_menu_modifiers_company_item_group_active_sort" in option_indexes
    assert {MenuModifierGroup, MenuModifier, MenuVariant} <= TRACKED


def test_modifier_payloads_trim_names_and_reject_impossible_bounds() -> None:
    group = ModifierGroupCreate(name="  Milk choice  ", min_select=1, max_select=2)
    option = ModifierOptionCreate(name="  Oat milk  ", max_quantity=2)

    assert group.name == "Milk choice"
    assert option.name == "Oat milk"
    with pytest.raises(ValidationError, match="min_select must not exceed max_select"):
        ModifierGroupCreate(name="Choice", min_select=2, max_select=1)
    with pytest.raises(ValidationError, match="name must not be blank"):
        ModifierOptionCreate(name="   ")


def test_priced_snapshots_are_json_ready_and_detached_from_catalog_rows() -> None:
    variant_id = uuid4()
    group_id = uuid4()
    modifier_id = uuid4()
    variant = PricedVariantSnapshot(
        id=variant_id,
        name="Large",
        price_delta_minor=1000,
        line_delta_minor=2000,
    )
    modifier = PricedModifierSnapshot(
        id=modifier_id,
        modifier_group_id=group_id,
        group_name="Milk",
        name="Oat",
        quantity_per_item=2,
        unit_price_delta_minor=250,
        per_item_delta_minor=500,
        line_delta_minor=1000,
    )

    assert variant.as_dict() == {
        "variant_id": str(variant_id),
        "name": "Large",
        "price_delta_minor": 1000,
        "line_delta_minor": 2000,
    }
    assert modifier.as_dict() == {
        "modifier_id": str(modifier_id),
        "modifier_group_id": str(group_id),
        "group_name": "Milk",
        "name": "Oat",
        "qty": 2,
        "price_delta_minor": 250,
        "per_item_delta_minor": 500,
        "line_delta_minor": 1000,
    }


def test_line_request_remains_backward_compatible_and_accepts_typed_selections() -> None:
    item_id = uuid4()
    modifier_id = uuid4()

    assert LineRequest(menu_item_id=item_id, qty=1).modifiers == ()
    request = LineRequest(
        menu_item_id=item_id,
        qty=2,
        modifiers=(ModifierSelection(modifier_id=modifier_id, quantity=2),),
    )
    assert request.modifiers[0].modifier_id == modifier_id
    assert request.modifiers[0].quantity == 2


def test_pos_modifier_request_accepts_android_alias_and_rejects_client_prices() -> None:
    item_id = uuid4()
    modifier_id = uuid4()
    line = OrderLineCreate(
        menu_item_id=item_id,
        qty=1,
        modifiers=[{"modifier_id": modifier_id, "qty": 2}],
    )
    assert line.modifiers is not None
    assert line.modifiers[0].modifier_id == modifier_id
    assert line.modifiers[0].qty == 2

    with pytest.raises(ValidationError, match="price_delta_minor"):
        OrderLineCreate(
            menu_item_id=item_id,
            qty=1,
            modifiers=[
                {
                    "modifier_id": modifier_id,
                    "qty": 1,
                    "price_delta_minor": 1,
                }
            ],
        )
    with pytest.raises(ValidationError):
        OrderLineCreate(
            menu_item_id=item_id,
            qty=1,
            modifiers=[{"modifier_id": modifier_id, "qty": True}],
        )
