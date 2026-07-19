"""Recipe-driven inventory deduction.

When an order is paid, for each line we find the active recipe for that
menu item and deduct the recipe's ingredients from inventory using FIFO
(oldest batch with stock first). Each deduction is recorded as a
StockMovement so the audit trail is complete.

If a menu item has NO recipe (drinks bought wholesale, etc.) the line
is skipped without error. If a recipe exists but stock is insufficient,
we deduct what we can and continue — the cashier still gets to charge
the customer, but Ingredient.current_qty may go briefly negative,
which surfaces in the low-stock alert.

When an order is refunded, restock_for_refund() reverses this: recipe
ingredients for the refunded order's lines are credited back (in full for
a full refund, proportionally for a partial one), with a StockMovement
('refund_restock') written as the audit trail.
"""

from __future__ import annotations

from uuid import UUID, uuid4

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    Batch,
    Ingredient,
    OrderLine,
    Recipe,
    RecipeLine,
    StockMovement,
)


async def deduct_for_order(
    session: AsyncSession,
    *,
    order_id: UUID,
    order_lines: list[OrderLine],
    branch_id: UUID,
    created_by: UUID | None,
) -> int:
    """Deduct ingredients for every line in the order.

    Returns the number of stock movements written (zero is fine — many lines
    don't have recipes yet, e.g. a bottled drink resold as-is).
    """
    movements_written = 0

    # Collect distinct menu_item_ids in this order
    menu_item_ids = list({ln.menu_item_id for ln in order_lines})
    if not menu_item_ids:
        return 0

    # Load active recipes for those items in one query
    recipes = (
        await session.execute(
            select(Recipe).where(
                Recipe.menu_item_id.in_(menu_item_ids), Recipe.is_active.is_(True)
            )
        )
    ).scalars().all()
    recipes_by_item = {r.menu_item_id: r for r in recipes}
    if not recipes_by_item:
        return 0

    # Load all recipe lines for those recipes in one query
    recipe_ids = [r.id for r in recipes]
    recipe_lines = (
        await session.execute(
            select(RecipeLine).where(RecipeLine.recipe_id.in_(recipe_ids))
        )
    ).scalars().all()
    lines_by_recipe: dict[UUID, list[RecipeLine]] = {}
    for rl in recipe_lines:
        lines_by_recipe.setdefault(rl.recipe_id, []).append(rl)

    # For each order line, deduct the recipe's ingredients × qty
    for order_line in order_lines:
        recipe = recipes_by_item.get(order_line.menu_item_id)
        if not recipe:
            continue
        for rl in lines_by_recipe.get(recipe.id, []):
            qty_needed = float(rl.qty) * (1 + float(rl.wastage_pct or 0)) * float(order_line.qty)
            await _deduct_ingredient(
                session,
                ingredient_id=rl.ingredient_id,
                branch_id=branch_id,
                qty_needed=qty_needed,
                order_id=order_id,
                created_by=created_by,
            )
            movements_written += 1

    return movements_written


async def _deduct_ingredient(
    session: AsyncSession,
    *,
    ingredient_id: UUID,
    branch_id: UUID,
    qty_needed: float,
    order_id: UUID,
    created_by: UUID | None,
) -> None:
    """Consume qty_needed from the ingredient's batches using FIFO.

    Writes one StockMovement per batch consumed.
    Also decrements Ingredient.current_qty so the analytics dashboard /
    low-stock alert stays accurate.
    """
    remaining = qty_needed

    # Pull batches in FIFO order (oldest first), only those with stock
    batches = (
        await session.execute(
            select(Batch)
            .where(
                Batch.ingredient_id == ingredient_id,
                Batch.branch_id == branch_id,
                Batch.qty_on_hand > 0,
            )
            .order_by(Batch.received_at)
            .with_for_update()
        )
    ).scalars().all()

    for batch in batches:
        if remaining <= 0:
            break
        take = min(float(batch.qty_on_hand), remaining)
        batch.qty_on_hand = float(batch.qty_on_hand) - take
        session.add(
            StockMovement(
                id=uuid4(),
                batch_id=batch.id,
                branch_id=branch_id,
                type="sale",
                ref_type="order",
                ref_id=order_id,
                qty_delta=-take,
                cost_per_unit_minor=int(batch.cost_per_unit_minor),
                created_by=created_by,
                note=f"Auto-deducted for order {order_id}",
            )
        )
        remaining -= take

    # If we still have remaining (stock was short), log a negative adjustment
    # against the most recent batch so the audit trail shows what happened.
    if remaining > 0 and batches:
        last = batches[-1]
        session.add(
            StockMovement(
                id=uuid4(),
                batch_id=last.id,
                branch_id=branch_id,
                type="sale",
                ref_type="order",
                ref_id=order_id,
                qty_delta=-remaining,
                cost_per_unit_minor=int(last.cost_per_unit_minor),
                created_by=created_by,
                note=f"Negative stock — order {order_id} (restock soon)",
            )
        )

    # Update rolling current_qty
    ing = (
        await session.execute(
            select(Ingredient).where(Ingredient.id == ingredient_id).with_for_update()
        )
    ).scalar_one_or_none()
    if ing:
        ing.current_qty = float(ing.current_qty or 0) - qty_needed


async def restock_for_refund(
    session: AsyncSession,
    *,
    order_id: UUID,
    order_lines: list[OrderLine],
    branch_id: UUID,
    created_by: UUID | None,
    fraction: float,
) -> int:
    """Reverse recipe-ingredient deductions for a refunded order.

    Mirrors deduct_for_order's recipe walk, but scales each line's ingredient
    qty by `fraction` — the share of the order's value this particular refund
    covers. Refunds in this system are order-level amounts, not itemized by
    line, so a full refund (fraction == 1.0) restocks every recipe-linked
    line back to its pre-deduction level, and a partial refund restocks the
    matching fraction of every line uniformly.

    If a menu item has no recipe the line is skipped without error, same as
    deduct_for_order. Returns the number of stock movements written.
    """
    movements_written = 0
    fraction = max(0.0, min(1.0, fraction))
    if fraction <= 0:
        return 0

    menu_item_ids = list({ln.menu_item_id for ln in order_lines})
    if not menu_item_ids:
        return 0

    recipes = (
        await session.execute(
            select(Recipe).where(
                Recipe.menu_item_id.in_(menu_item_ids), Recipe.is_active.is_(True)
            )
        )
    ).scalars().all()
    recipes_by_item = {r.menu_item_id: r for r in recipes}
    if not recipes_by_item:
        return 0

    recipe_ids = [r.id for r in recipes]
    recipe_lines = (
        await session.execute(
            select(RecipeLine).where(RecipeLine.recipe_id.in_(recipe_ids))
        )
    ).scalars().all()
    lines_by_recipe: dict[UUID, list[RecipeLine]] = {}
    for rl in recipe_lines:
        lines_by_recipe.setdefault(rl.recipe_id, []).append(rl)

    for order_line in order_lines:
        recipe = recipes_by_item.get(order_line.menu_item_id)
        if not recipe:
            continue
        for rl in lines_by_recipe.get(recipe.id, []):
            qty_to_restock = (
                float(rl.qty)
                * (1 + float(rl.wastage_pct or 0))
                * float(order_line.qty)
                * fraction
            )
            if qty_to_restock <= 0:
                continue
            await _restock_ingredient(
                session,
                ingredient_id=rl.ingredient_id,
                branch_id=branch_id,
                qty_to_restock=qty_to_restock,
                order_id=order_id,
                created_by=created_by,
            )
            movements_written += 1

    return movements_written


async def _restock_ingredient(
    session: AsyncSession,
    *,
    ingredient_id: UUID,
    branch_id: UUID,
    qty_to_restock: float,
    order_id: UUID,
    created_by: UUID | None,
) -> None:
    """Credit qty_to_restock back onto the ingredient's newest batch.

    Which exact FIFO batch a refunded item's ingredients were originally
    consumed from is ambiguous once batches are mixed/exhausted, so — same
    convention as the manual positive stock-adjustment path in
    app/api/v1/inventory/router.py's post_adjustment — we credit the most
    recently received batch rather than trying to reconstruct the original
    consumption. A StockMovement is written as the audit trail of the
    reversal, and Ingredient.current_qty (what the low-stock alert and
    analytics dashboard read) is always updated.
    """
    batch = (
        await session.execute(
            select(Batch)
            .where(Batch.ingredient_id == ingredient_id, Batch.branch_id == branch_id)
            .order_by(Batch.received_at.desc())
            .limit(1)
            .with_for_update()
        )
    ).scalar_one_or_none()

    if batch:
        batch.qty_on_hand = float(batch.qty_on_hand) + qty_to_restock
        session.add(
            StockMovement(
                id=uuid4(),
                batch_id=batch.id,
                branch_id=branch_id,
                type="refund_restock",
                ref_type="order",
                ref_id=order_id,
                qty_delta=qty_to_restock,
                cost_per_unit_minor=int(batch.cost_per_unit_minor),
                created_by=created_by,
                note=f"Restocked from refund on order {order_id}",
            )
        )
    # If no batch row exists at all for this ingredient/branch (shouldn't
    # happen — a batch must have existed for the original deduction to have
    # drawn from it), we still credit current_qty below rather than blocking
    # the refund on an inventory audit-trail gap.

    ing = (
        await session.execute(
            select(Ingredient).where(Ingredient.id == ingredient_id).with_for_update()
        )
    ).scalar_one_or_none()
    if ing:
        ing.current_qty = float(ing.current_qty or 0) + qty_to_restock
