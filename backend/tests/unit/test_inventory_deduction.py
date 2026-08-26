"""Database-free tests for recipe-ingredient deduction and refund restock.

Covers the bug where refunding an order never reversed the ingredient
deduction that ran when the order was finalized: order paid -> ingredient
deducted -> order refunded -> ingredient restocked back to (at least close
to) its original level.

restock_for_refund reverses the actual historical "sale" StockMovement
rows recorded for the order (not the menu item's *current* recipe), so
these tests mock a sale-movements query rather than a Recipe/RecipeLine
lookup — see the docstring on restock_for_refund for why.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

import app.services.inventory.deduction as deduction_service
from app.models import Batch, Ingredient, Recipe, RecipeLine
from app.services.inventory.deduction import (
    UNCOSTED_SHORTAGE_LOT_CODE,
    deduct_for_order,
    restock_for_refund,
)


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
        self.statements: list = []

    async def execute(self, statement):
        self.statements.append(statement)
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


def _sale_movement(*, batch_id, qty_delta: float, cost_per_unit_minor: int = 50):
    # restock_for_refund only reads qty_delta / batch_id / cost_per_unit_minor
    # off each row, so a SimpleNamespace stands in fine for a real StockMovement.
    return SimpleNamespace(
        batch_id=batch_id, qty_delta=qty_delta, cost_per_unit_minor=cost_per_unit_minor
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
        _Result(scalar=ingredient),
        _Result(rows=[batch]),
    )
    movements = await deduct_for_order(
        deduct_session,
        order_id=order_id,
        order_lines=[order_line],
        branch_id=branch_id,
        created_by=created_by,
    )
    assert movements == 1
    ingredient_lock_sql = str(deduct_session.statements[2]).upper()
    batch_lock_sql = str(deduct_session.statements[3]).upper()
    assert "FROM INGREDIENTS" in ingredient_lock_sql
    assert "FOR UPDATE" in ingredient_lock_sql
    assert "FROM BATCHES" in batch_lock_sql
    assert "FOR UPDATE" in batch_lock_sql
    assert batch.qty_on_hand == 94.0
    assert ingredient.current_qty == 94.0
    sale_movement = deduct_session.added[0]
    assert sale_movement.type == "sale"
    assert sale_movement.qty_delta == -6.0

    # --- Order refunded in full: the 6ml comes back, reversing the exact
    # sale movement just written above (same batch, same cost basis) ---
    refund_session = _Session(
        _Result(rows=[_sale_movement(batch_id=batch.id, qty_delta=-6.0)]),
        _Result(scalar=ingredient.id),
        _Result(scalar=ingredient),
        _Result(scalar=batch),
    )
    restocked = await restock_for_refund(
        refund_session,
        order_id=order_id,
        branch_id=branch_id,
        created_by=created_by,
        fraction=1.0,
    )
    assert restocked == 1
    sale_lookup_sql = str(refund_session.statements[0]).upper()
    refund_ingredient_lock_sql = str(refund_session.statements[2]).upper()
    refund_batch_lock_sql = str(refund_session.statements[3]).upper()
    assert "JOIN BATCHES" in sale_lookup_sql
    assert "ORDER BY BATCHES.INGREDIENT_ID, BATCHES.ID" in sale_lookup_sql
    assert "FROM INGREDIENTS" in refund_ingredient_lock_sql
    assert "FOR UPDATE" in refund_ingredient_lock_sql
    assert "FROM BATCHES" in refund_batch_lock_sql
    assert "FOR UPDATE" in refund_batch_lock_sql
    assert batch.qty_on_hand == 100.0
    assert ingredient.current_qty == 100.0
    restock_movement = refund_session.added[0]
    assert restock_movement.type == "refund_restock"
    assert restock_movement.qty_delta == 6.0
    assert restock_movement.cost_per_unit_minor == 50
    assert restock_movement.ref_type == "order"
    assert restock_movement.ref_id == order_id


@pytest.mark.asyncio
async def test_zero_stock_uses_last_known_batch_cost_for_negative_sale() -> None:
    """Running out of stock must not silently turn the next sale into zero COGS."""
    branch_id = uuid4()
    order_id = uuid4()
    menu_item_id = uuid4()
    ingredient = _ingredient(current_qty=0.0)
    exhausted_batch = _batch(
        ingredient_id=ingredient.id,
        branch_id=branch_id,
        qty_on_hand=0.0,
    )
    recipe = Recipe(id=uuid4(), menu_item_id=menu_item_id, name="Cola", is_active=True)
    recipe_line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=2.0,
        wastage_pct=0,
    )
    session = _Session(
        _Result(rows=[recipe]),
        _Result(rows=[recipe_line]),
        _Result(scalar=ingredient),
        _Result(rows=[]),  # no positive FIFO batch
        _Result(scalar=exhausted_batch),  # last known cost basis
    )

    movements = await deduct_for_order(
        session,
        order_id=order_id,
        order_lines=[SimpleNamespace(menu_item_id=menu_item_id, qty=1.0)],
        branch_id=branch_id,
        created_by=uuid4(),
    )

    assert movements == 1
    assert ingredient.current_qty == -2.0
    assert exhausted_batch.qty_on_hand == -2.0
    movement = session.added[0]
    assert movement.qty_delta == -2.0
    assert movement.cost_per_unit_minor == 50
    assert movement.note.startswith("Negative stock")

    refund_session = _Session(
        _Result(rows=[movement]),
        _Result(scalar=ingredient.id),
        _Result(scalar=ingredient),
        _Result(scalar=exhausted_batch),
    )
    restocked = await restock_for_refund(
        refund_session,
        order_id=order_id,
        branch_id=branch_id,
        created_by=uuid4(),
        fraction=1.0,
    )
    assert restocked == 1
    assert exhausted_batch.qty_on_hand == 0.0
    assert ingredient.current_qty == 0.0


@pytest.mark.asyncio
async def test_no_historical_batch_journals_reversible_unknown_cost_shortage() -> None:
    """Unknown cost stays explicit without losing the refund audit trail."""
    branch_id = uuid4()
    order_id = uuid4()
    menu_item_id = uuid4()
    ingredient = _ingredient(current_qty=0.0)
    recipe = Recipe(id=uuid4(), menu_item_id=menu_item_id, name="Cola", is_active=True)
    recipe_line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=1.0,
        wastage_pct=0,
    )
    session = _Session(
        _Result(rows=[recipe]),
        _Result(rows=[recipe_line]),
        _Result(scalar=ingredient),
        _Result(rows=[]),
        _Result(scalar=None),
    )

    movements = await deduct_for_order(
        session,
        order_id=order_id,
        order_lines=[SimpleNamespace(menu_item_id=menu_item_id, qty=1.0)],
        branch_id=branch_id,
        created_by=uuid4(),
    )

    assert movements == 1
    assert ingredient.current_qty == -1.0
    assert len(session.added) == 2
    shortage_batch, movement = session.added
    assert isinstance(shortage_batch, Batch)
    assert shortage_batch.qty_initial == 0
    assert shortage_batch.qty_on_hand == -1.0
    assert shortage_batch.cost_per_unit_minor == 0
    assert shortage_batch.lot_code == UNCOSTED_SHORTAGE_LOT_CODE
    assert movement.batch_id == shortage_batch.id
    assert movement.qty_delta == -1.0
    assert movement.cost_per_unit_minor == 0
    assert "cost basis unknown" in movement.note

    refund_session = _Session(
        _Result(rows=[movement]),
        _Result(scalar=ingredient.id),
        _Result(scalar=ingredient),
        _Result(scalar=shortage_batch),
    )
    restocked = await restock_for_refund(
        refund_session,
        order_id=order_id,
        branch_id=branch_id,
        created_by=uuid4(),
        fraction=1.0,
    )
    assert restocked == 1
    assert shortage_batch.qty_on_hand == 0.0
    assert ingredient.current_qty == 0.0
    assert refund_session.added[0].cost_per_unit_minor == 0


@pytest.mark.asyncio
async def test_partial_refund_restocks_the_proportional_share_only() -> None:
    branch_id = uuid4()
    order_id = uuid4()

    ingredient = _ingredient(current_qty=94.0)  # already deducted by 6ml
    batch = _batch(ingredient_id=ingredient.id, branch_id=branch_id, qty_on_hand=94.0)

    # Refund covers half the order's taxable value -> half of the original
    # 6ml sale movement (3ml) comes back.
    refund_session = _Session(
        _Result(rows=[_sale_movement(batch_id=batch.id, qty_delta=-6.0)]),
        _Result(scalar=ingredient.id),
        _Result(scalar=ingredient),
        _Result(scalar=batch),
    )
    restocked = await restock_for_refund(
        refund_session,
        order_id=order_id,
        branch_id=branch_id,
        created_by=uuid4(),
        fraction=0.5,
    )
    assert restocked == 1
    assert batch.qty_on_hand == 97.0
    assert ingredient.current_qty == 97.0
    assert refund_session.added[0].qty_delta == 3.0


@pytest.mark.asyncio
async def test_refund_restock_is_a_noop_when_no_sale_movements_exist() -> None:
    # No "sale" StockMovement rows are tied to this order (e.g. every line
    # was a wholesale item with no recipe) -> restock_for_refund must bail
    # out after the lookup query without touching any batch/ingredient.
    refund_session = _Session(_Result(rows=[]))
    restocked = await restock_for_refund(
        refund_session,
        order_id=uuid4(),
        branch_id=uuid4(),
        created_by=uuid4(),
        fraction=1.0,
    )
    assert restocked == 0
    assert refund_session.added == []


@pytest.mark.asyncio
async def test_multi_ingredient_deduction_uses_one_canonical_lock_order(
    monkeypatch,
) -> None:
    """Opposite recipe/line order must still acquire A then B exactly once.

    This is the deterministic regression for the former multi-ingredient
    deadlock: two transactions paying differently ordered menus now derive
    the same lock sequence before touching Ingredient/Batch rows.
    """
    low_id = UUID("00000000-0000-0000-0000-000000000001")
    high_id = UUID("00000000-0000-0000-0000-000000000002")
    item_a = uuid4()
    item_b = uuid4()
    recipe_a = Recipe(id=uuid4(), menu_item_id=item_a, name="A", is_active=True)
    recipe_b = Recipe(id=uuid4(), menu_item_id=item_b, name="B", is_active=True)
    recipe_lines = [
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_b.id,
            ingredient_id=high_id,
            qty=4,
            wastage_pct=0,
        ),
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_a.id,
            ingredient_id=low_id,
            qty=1,
            wastage_pct=0,
        ),
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_b.id,
            ingredient_id=low_id,
            qty=3,
            wastage_pct=0,
        ),
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_a.id,
            ingredient_id=high_id,
            qty=2,
            wastage_pct=0,
        ),
    ]
    session = _Session(
        _Result(rows=[recipe_b, recipe_a]),
        _Result(rows=recipe_lines),
    )
    calls: list[tuple[UUID, float]] = []

    async def _record_deduction(_session, **kwargs) -> int:
        calls.append((kwargs["ingredient_id"], kwargs["qty_needed"]))
        return 1

    monkeypatch.setattr(deduction_service, "_deduct_ingredient", _record_deduction)

    movements = await deduct_for_order(
        session,
        order_id=uuid4(),
        # Deliberately reverse the menu-item order too.
        order_lines=[
            SimpleNamespace(menu_item_id=item_b, qty=1),
            SimpleNamespace(menu_item_id=item_a, qty=2),
        ],
        branch_id=uuid4(),
        created_by=uuid4(),
    )

    assert movements == 2
    assert [ingredient_id for ingredient_id, _qty in calls] == [low_id, high_id]
    assert calls[0][1] == pytest.approx(5)  # (1 * 2) + (3 * 1)
    assert calls[1][1] == pytest.approx(8)  # (2 * 2) + (4 * 1)


@pytest.mark.asyncio
async def test_refund_restock_is_a_noop_for_a_zero_fraction() -> None:
    # fraction <= 0 (e.g. order.total_minor was 0) must not touch the DB at all.
    refund_session = _Session()
    restocked = await restock_for_refund(
        refund_session,
        order_id=uuid4(),
        branch_id=uuid4(),
        created_by=uuid4(),
        fraction=0.0,
    )
    assert restocked == 0
    assert refund_session.added == []


@pytest.mark.asyncio
async def test_refund_restock_skips_a_movement_whose_batch_no_longer_exists() -> None:
    # Defensive edge case: the batch a sale movement pointed to is somehow
    # gone by refund time. Nothing to credit without it, so no StockMovement
    # or quantity change happens for this movement (the returned count is
    # movements *attempted*, not confirmed writes — same as the pre-existing
    # deduct_for_order/restock_for_refund convention).
    refund_session = _Session(
        _Result(rows=[_sale_movement(batch_id=uuid4(), qty_delta=-6.0)]),
        _Result(scalar=None),
    )
    await restock_for_refund(
        refund_session,
        order_id=uuid4(),
        branch_id=uuid4(),
        created_by=uuid4(),
        fraction=1.0,
    )
    assert refund_session.added == []
