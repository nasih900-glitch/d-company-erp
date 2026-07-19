"""Database-free tests for recipe-ingredient deduction and refund restock.

Covers the bug where refunding an order never reversed the ingredient
deduction that ran when the order was finalized: order paid -> ingredient
deducted -> order refunded -> ingredient restocked back to (at least close
to) its original level.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.models import Batch, Ingredient, Recipe, RecipeLine
from app.services.inventory.deduction import deduct_for_order, restock_for_refund


class _Result:
    def __init__(self, *, rows=None, scalar=None) -> None:
        self.rows = [] if rows is None else rows
        self.scalar = scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows

    def scalar_one_or_none(self):
        return self.scalar


class _Session:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)
        self.added: list = []

    async def execute(self, statement):
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)


def _ingredient(*, current_qty: float) -> Ingredient:
    return Ingredient(
        id=uuid4(),
        company_id=uuid4(),
        sku="COLA-SYR",
        name="Cola Syrup",
        base_unit="ml",
        current_qty=current_qty,
    )


def _batch(*, ingredient_id, branch_id, qty_on_hand: float) -> Batch:
    return Batch(
        id=uuid4(),
        ingredient_id=ingredient_id,
        branch_id=branch_id,
        received_at=datetime(2026, 7, 1, tzinfo=UTC),
        qty_initial=100.0,
        qty_on_hand=qty_on_hand,
        cost_per_unit_minor=50,
    )


@pytest.mark.asyncio
async def test_full_refund_restocks_ingredient_back_to_pre_deduction_level() -> None:
    branch_id = uuid4()
    order_id = uuid4()
    menu_item_id = uuid4()
    created_by = uuid4()

    ingredient = _ingredient(current_qty=100.0)
    batch = _batch(ingredient_id=ingredient.id, branch_id=branch_id, qty_on_hand=100.0)
    recipe = Recipe(id=uuid4(), menu_item_id=menu_item_id, name="Cola", is_active=True)
    recipe_line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=2.0,
        wastage_pct=0,
    )
    order_line = SimpleNamespace(menu_item_id=menu_item_id, qty=3.0)

    # --- Order finalized: deduct 2ml/unit * 3 units = 6ml ---
    deduct_session = _Session(
        _Result(rows=[recipe]),
        _Result(rows=[recipe_line]),
        _Result(rows=[batch]),
        _Result(scalar=ingredient),
    )
    movements = await deduct_for_order(
        deduct_session,
        order_id=order_id,
        order_lines=[order_line],
        branch_id=branch_id,
        created_by=created_by,
    )
    assert movements == 1
    assert batch.qty_on_hand == 94.0
    assert ingredient.current_qty == 94.0
    sale_movement = deduct_session.added[0]
    assert sale_movement.type == "sale"
    assert sale_movement.qty_delta == -6.0

    # --- Order refunded in full: the 6ml comes back ---
    refund_session = _Session(
        _Result(rows=[recipe]),
        _Result(rows=[recipe_line]),
        _Result(scalar=batch),
        _Result(scalar=ingredient),
    )
    restocked = await restock_for_refund(
        refund_session,
        order_id=order_id,
        order_lines=[order_line],
        branch_id=branch_id,
        created_by=created_by,
        fraction=1.0,
    )
    assert restocked == 1
    assert batch.qty_on_hand == 100.0
    assert ingredient.current_qty == 100.0
    restock_movement = refund_session.added[0]
    assert restock_movement.type == "refund_restock"
    assert restock_movement.qty_delta == 6.0
    assert restock_movement.ref_type == "order"
    assert restock_movement.ref_id == order_id


@pytest.mark.asyncio
async def test_partial_refund_restocks_the_proportional_share_only() -> None:
    branch_id = uuid4()
    order_id = uuid4()
    menu_item_id = uuid4()
    created_by = uuid4()

    ingredient = _ingredient(current_qty=94.0)  # already deducted by 6ml
    batch = _batch(ingredient_id=ingredient.id, branch_id=branch_id, qty_on_hand=94.0)
    recipe = Recipe(id=uuid4(), menu_item_id=menu_item_id, name="Cola", is_active=True)
    recipe_line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=2.0,
        wastage_pct=0,
    )
    order_line = SimpleNamespace(menu_item_id=menu_item_id, qty=3.0)

    # Refund covers half the order's value -> half of the 6ml (3ml) comes back.
    refund_session = _Session(
        _Result(rows=[recipe]),
        _Result(rows=[recipe_line]),
        _Result(scalar=batch),
        _Result(scalar=ingredient),
    )
    restocked = await restock_for_refund(
        refund_session,
        order_id=order_id,
        order_lines=[order_line],
        branch_id=branch_id,
        created_by=created_by,
        fraction=0.5,
    )
    assert restocked == 1
    assert batch.qty_on_hand == 97.0
    assert ingredient.current_qty == 97.0
    assert refund_session.added[0].qty_delta == 3.0


@pytest.mark.asyncio
async def test_refund_restock_skips_menu_items_without_a_recipe() -> None:
    branch_id = uuid4()
    order_id = uuid4()
    order_line = SimpleNamespace(menu_item_id=uuid4(), qty=1.0)

    # No Recipe rows match -> restock_for_refund must bail out after the
    # first query without touching any ingredient/batch, same as
    # deduct_for_order does for a wholesale (recipe-less) line.
    refund_session = _Session(_Result(rows=[]))
    restocked = await restock_for_refund(
        refund_session,
        order_id=order_id,
        order_lines=[order_line],
        branch_id=branch_id,
        created_by=uuid4(),
        fraction=1.0,
    )
    assert restocked == 0
    assert refund_session.added == []


@pytest.mark.asyncio
async def test_refund_restock_is_a_noop_for_a_zero_fraction() -> None:
    # fraction <= 0 (e.g. order.total_minor was 0) must not touch the DB at all.
    refund_session = _Session()
    restocked = await restock_for_refund(
        refund_session,
        order_id=uuid4(),
        order_lines=[SimpleNamespace(menu_item_id=uuid4(), qty=1.0)],
        branch_id=uuid4(),
        created_by=uuid4(),
        fraction=0.0,
    )
    assert restocked == 0
    assert refund_session.added == []
