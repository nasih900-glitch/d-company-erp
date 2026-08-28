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

Money refunds deliberately do not change physical stock or COGS: prepared
food, consumed gaming time, and opened ingredients have not returned merely
because money left the business. ``restock_for_refund`` is retained only as a
low-level reversal primitive for a future explicit item-disposition workflow.
It has no production refund-route caller and must not be wired in until that
workflow records returned quantities, a disposition identifier, authority,
and idempotency. When such evidence exists, the helper reverses historical
sale movements rather than guessing from the current recipe.
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import or_, select

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import BusinessRuleError
from app.models import (
    Batch,
    Ingredient,
    OrderLine,
    Recipe,
    RecipeLine,
    StockMovement,
)

# A shortage still needs a durable batch anchor because StockMovement.batch_id
# is non-null and refunds reverse the exact original movement.  This marker
# distinguishes a zero cost that means "unknown" from a genuine free item.
UNCOSTED_SHORTAGE_LOT_CODE = "SYSTEM-UNCOSTED-SHORTAGE"
_QUANTITY_QUANTUM = Decimal("0.0001")


def _quantity(value: object) -> Decimal:
    """Return one database-representable inventory quantity."""

    try:
        return Decimal(str(value)).quantize(
            _QUANTITY_QUANTUM,
            rounding=ROUND_HALF_UP,
        )
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise BusinessRuleError("inventory quantity must be a finite number") from exc


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
    # Soft-voided order lines remain immutable audit evidence and must never
    # consume or restore physical stock as part of the paid bill.
    order_lines = [
        line for line in order_lines if getattr(line, "voided_at", None) is None
    ]

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

    # Aggregate before taking any locks.  A transaction can cover multiple
    # menu items whose recipes reference the same ingredients in different
    # orders.  Locking as we encounter recipe lines would let two payments
    # acquire ingredient A/B in opposite orders and deadlock.  One combined
    # deduction per ingredient, acquired in canonical UUID order, also avoids
    # fragmenting the audit trail merely because an ingredient appears in
    # more than one line.
    required_by_ingredient: dict[UUID, Decimal] = {}
    for order_line in order_lines:
        recipe = recipes_by_item.get(order_line.menu_item_id)
        if not recipe:
            continue
        # SQLAlchemy column defaults are applied at INSERT/flush time. Unit
        # construction and legacy rows may therefore expose ``None`` for the
        # historical default, which means one output unit rather than zero.
        recipe_yield = Decimal(
            str(recipe.yield_qty if recipe.yield_qty is not None else 1)
        )
        if recipe_yield <= 0:
            raise BusinessRuleError(
                f"active recipe {recipe.id} has an invalid yield; correct the recipe before payment"
            )
        for rl in lines_by_recipe.get(recipe.id, []):
            raw_qty_needed = (
                Decimal(str(rl.qty))
                * (Decimal(1) + Decimal(str(rl.wastage_pct or 0)))
                * Decimal(str(order_line.qty))
                / recipe_yield
            )
            required_by_ingredient[rl.ingredient_id] = (
                required_by_ingredient.get(rl.ingredient_id, Decimal(0))
                + raw_qty_needed
            )

    for ingredient_id, raw_qty_needed in sorted(
        required_by_ingredient.items(), key=lambda item: item[0].int
    ):
        qty_needed = _quantity(raw_qty_needed)
        if raw_qty_needed > 0 and qty_needed == 0:
            raise BusinessRuleError(
                "recipe consumption is below the inventory precision of 0.0001; "
                "change the ingredient base unit or recipe quantity"
            )
        if qty_needed > 0:
            movements_written += await _deduct_ingredient(
                session,
                ingredient_id=ingredient_id,
                branch_id=branch_id,
                qty_needed=qty_needed,
                order_id=order_id,
                created_by=created_by,
            )

    return movements_written


async def _deduct_ingredient(
    session: AsyncSession,
    *,
    ingredient_id: UUID,
    branch_id: UUID,
    qty_needed: Decimal | float,
    order_id: UUID,
    created_by: UUID | None,
) -> int:
    """Consume qty_needed from the ingredient's batches using FIFO.

    Writes one StockMovement per batch consumed.
    Also decrements Ingredient.current_qty so the analytics dashboard /
    low-stock alert stays accurate.
    """
    qty_needed = _quantity(qty_needed)
    remaining = qty_needed
    movements_written = 0

    # Global inventory-write lock hierarchy: Ingredient, then Batch.  Manual
    # adjustments and GRNs already start with Ingredient; doing the same here
    # prevents a sale (formerly Batch -> Ingredient) from deadlocking against
    # an adjustment (Ingredient -> Batch).  The ingredient row also serializes
    # every batch mutation for this ingredient, including the shortage path.
    ing = (
        await session.execute(
            select(Ingredient)
            .where(
                Ingredient.id == ingredient_id,
                Ingredient.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not ing:
        return 0

    # Pull batches in FIFO order (oldest first), only those with stock that is
    # still saleable. At the exact UTC expiry instant the batch is expired, so
    # the strict ``>`` boundary is intentional. Expired physical stock remains
    # untouched until an authorised waste/damage movement records its disposal.
    now = datetime.now(UTC)
    batches = (
        await session.execute(
            select(Batch)
            .where(
                Batch.ingredient_id == ingredient_id,
                Batch.branch_id == branch_id,
                Batch.qty_on_hand > 0,
                or_(Batch.expires_at.is_(None), Batch.expires_at > now),
            )
            .order_by(Batch.received_at, Batch.id)
            .with_for_update()
        )
    ).scalars().all()

    for batch in batches:
        if remaining <= 0:
            break
        take = min(_quantity(batch.qty_on_hand), remaining)
        batch.qty_on_hand = _quantity(batch.qty_on_hand) - take
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
        movements_written += 1
        remaining -= take

    # If stock is short, keep the shortage and its later refund algebraically
    # reversible.  The batch balance goes negative by the same quantity as the
    # movement; otherwise refunding the movement would create phantom stock.
    # With no historical batch, create an explicit system deficit batch whose
    # zero cost is labelled UNKNOWN rather than silently treating the sale as
    # genuinely free.
    if remaining > 0:
        last = batches[-1] if batches else (
            await session.execute(
                select(Batch)
                .where(
                    Batch.ingredient_id == ingredient_id,
                    Batch.branch_id == branch_id,
                    or_(Batch.expires_at.is_(None), Batch.expires_at > now),
                )
                .order_by(Batch.received_at.desc(), Batch.id.desc())
                .limit(1)
                .with_for_update()
            )
        ).scalar_one_or_none()
        if last is None:
            last = Batch(
                id=uuid4(),
                ingredient_id=ingredient_id,
                branch_id=branch_id,
                supplier_id=None,
                grn_id=None,
                received_at=datetime.now(UTC),
                expires_at=None,
                qty_initial=0,
                qty_on_hand=-remaining,
                cost_per_unit_minor=0,
                lot_code=UNCOSTED_SHORTAGE_LOT_CODE,
            )
            session.add(last)
        else:
            last.qty_on_hand = _quantity(last.qty_on_hand or 0) - remaining

        cost_per_unit_minor = int(last.cost_per_unit_minor or 0)
        cost_unknown = (
            cost_per_unit_minor <= 0
            or last.lot_code == UNCOSTED_SHORTAGE_LOT_CODE
        )
        note_suffix = (
            "cost basis unknown; reconcile inventory costing"
            if cost_unknown
            else "restock soon"
        )
        session.add(
            StockMovement(
                id=uuid4(),
                batch_id=last.id,
                branch_id=branch_id,
                type="sale",
                ref_type="order",
                ref_id=order_id,
                qty_delta=-remaining,
                cost_per_unit_minor=cost_per_unit_minor,
                created_by=created_by,
                note=f"Negative stock — order {order_id} ({note_suffix})",
            )
        )
        movements_written += 1

    # The ingredient row remains locked from before the batch reads above.
    ing.current_qty = _quantity(ing.current_qty or 0) - qty_needed
    return movements_written


async def restock_for_refund(
    session: AsyncSession,
    *,
    order_id: UUID,
    branch_id: UUID,
    created_by: UUID | None,
    fraction: float,
) -> int:
    """Low-level stock reversal for an explicit returned-item disposition.

    This function is intentionally not called by the monetary POS refund
    workflow. ``order_id`` and a fraction alone are not sufficient evidence
    that any physical item returned, and this primitive is not independently
    idempotent. A production caller must first persist and deduplicate an
    authorised item-level disposition, then invoke this within that same
    transaction.

    Reads back the "sale" StockMovement rows deduct_for_order wrote for this
    exact order (matched via ref_type="order", ref_id=order_id) and credits
    each one back onto the *same batch*, scaled by `fraction` — the share of
    the order's taxable value this particular refund covers. A full refund
    (fraction == 1.0) restocks every consumed movement back in full, a
    partial refund restocks the matching fraction of each one.

    This deliberately does NOT re-derive quantities from the menu item's
    *current* recipe (unlike deduct_for_order): if a recipe is edited or
    deactivated between the sale and a later refund, re-deriving would
    restock the wrong ingredients/quantities, or silently restock nothing
    at all. Reversing the actual historical movements is correct regardless
    of what the recipe looks like today, and also means the reversing
    cost_per_unit_minor exactly mirrors the original deduction's cost basis
    (see ledger.py's stock_stmt), keeping the ledger reversal exact.

    Returns the number of stock movements written.
    """
    movements_written = 0
    fraction_decimal = max(Decimal(0), min(Decimal(1), Decimal(str(fraction))))
    if fraction_decimal <= 0:
        return 0

    sale_movements = (
        await session.execute(
            select(StockMovement)
            .join(Batch, Batch.id == StockMovement.batch_id)
            .where(
                StockMovement.ref_type == "order",
                StockMovement.ref_id == order_id,
                StockMovement.type == "sale",
                StockMovement.branch_id == branch_id,
                Batch.branch_id == branch_id,
            )
            # Refunds can span several ingredients/batches.  Process them in
            # the same canonical Ingredient -> Batch order as deductions so
            # concurrent refund/payment transactions cannot invert locks.
            .order_by(Batch.ingredient_id, Batch.id, StockMovement.id)
        )
    ).scalars().all()

    for movement in sale_movements:
        qty_to_restock = _quantity(
            abs(Decimal(str(movement.qty_delta or 0))) * fraction_decimal
        )
        if qty_to_restock <= 0:
            continue
        await _restock_ingredient(
            session,
            batch_id=movement.batch_id,
            branch_id=branch_id,
            qty_to_restock=qty_to_restock,
            cost_per_unit_minor=int(movement.cost_per_unit_minor or 0),
            order_id=order_id,
            created_by=created_by,
        )
        movements_written += 1

    return movements_written


async def _restock_ingredient(
    session: AsyncSession,
    *,
    batch_id: UUID,
    branch_id: UUID,
    qty_to_restock: Decimal | float,
    cost_per_unit_minor: int,
    order_id: UUID,
    created_by: UUID | None,
) -> None:
    """Credit qty_to_restock back onto the exact batch it was deducted from.

    Using the same batch_id and cost_per_unit_minor as the original
    deduction (rather than guessing at "the newest batch") means the
    resulting StockMovement is an exact mirror-image of the sale it
    reverses. A StockMovement is written as the audit trail of the
    reversal, and Ingredient.current_qty (what the low-stock alert and
    analytics dashboard read) is always updated.
    """
    # Batch.ingredient_id is immutable.  Read that identifier without taking
    # a row lock, then follow the global Ingredient -> Batch lock hierarchy.
    # Filtering both reads by branch also prevents an inconsistent caller from
    # writing a movement for a batch outside the refund's branch.
    ingredient_id = (
        await session.execute(
            select(Batch.ingredient_id).where(
                Batch.id == batch_id,
                Batch.branch_id == branch_id,
            )
        )
    ).scalar_one_or_none()
    # A batch must have existed for the original deduction to have drawn
    # from it, so this should never be missing in practice — but if it
    # somehow is, there's no ingredient to credit without it, so skip.
    if ingredient_id is None:
        return

    ing = (
        await session.execute(
            select(Ingredient).where(Ingredient.id == ingredient_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not ing:
        return

    batch = (
        await session.execute(
            select(Batch)
            .where(Batch.id == batch_id, Batch.branch_id == branch_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not batch:
        return

    qty_to_restock = _quantity(qty_to_restock)
    batch.qty_on_hand = _quantity(batch.qty_on_hand) + qty_to_restock
    session.add(
        StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=branch_id,
            type="refund_restock",
            ref_type="order",
            ref_id=order_id,
            qty_delta=qty_to_restock,
            cost_per_unit_minor=cost_per_unit_minor,
            created_by=created_by,
            note=f"Restocked from refund on order {order_id}",
        )
    )

    ing.current_qty = _quantity(ing.current_qty or 0) + qty_to_restock
