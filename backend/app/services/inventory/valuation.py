"""Authoritative inventory quantities and value from remaining batch balances.

``Ingredient.current_qty`` and ``Ingredient.avg_cost_minor`` are convenient
catalogue summaries, but FIFO consumption means their product is not an
inventory valuation.  The remaining batches are the source of truth: every
batch retains its own acquisition cost, branch, and on-hand quantity.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import TYPE_CHECKING, Protocol

from sqlalchemy import func, select

from app.models import Batch, Branch, Ingredient

if TYPE_CHECKING:
    from collections.abc import Iterable
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


_ONE_MINOR = Decimal("1")


def _as_decimal(value: object | None) -> Decimal:
    if value is None:
        return Decimal(0)
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def round_minor(value: Decimal) -> int:
    """Round a fractional minor-unit valuation with commercial half-up rules."""

    return int(value.quantize(_ONE_MINOR, rounding=ROUND_HALF_UP))


@dataclass(frozen=True, slots=True)
class RemainingBatchTotal:
    ingredient_id: UUID
    qty: Decimal
    valuation: Decimal

    @property
    def valuation_minor(self) -> int:
        return round_minor(self.valuation)

    @property
    def weighted_cost_minor(self) -> int:
        if self.qty == 0:
            return 0
        return round_minor(self.valuation / self.qty)


class CostedStockMovement(Protocol):
    """Minimum immutable movement shape needed for book-value allocation."""

    qty_delta: object
    cost_per_unit_minor: int


def allocate_batch_movement_value_deltas(
    *,
    qty_initial: object,
    cost_per_unit_minor: int,
    movements: Iterable[CostedStockMovement],
) -> list[int]:
    """Allocate signed whole-paise value changes without cumulative drift.

    Inventory quantity supports four decimal places while the ledger supports
    only whole paise. Rounding every movement independently is therefore not
    additive: two 0.5-unit sales at 1 paise/unit would each truncate/round to
    zero even though together they consume the full 1-paise asset.

    A batch has one immutable acquisition cost. Start from its initial raw book
    value, apply each movement in chronological order, and allocate the change
    between the HALF_UP-rounded before/after values. The deltas telescope, so
    their sum always equals the authoritative change in rounded batch value.

    GRN receipt movements are deliberately not passed here: ``qty_initial`` is
    already the post-receipt balance and the receipt itself is posted by the
    source-linked GRN journal.
    """

    unit_cost = int(cost_per_unit_minor)
    if unit_cost < 0:
        raise ValueError("batch unit cost cannot be negative")

    raw_value = _as_decimal(qty_initial) * Decimal(unit_cost)
    rounded_value = round_minor(raw_value)
    allocated: list[int] = []
    for movement in movements:
        movement_cost = int(movement.cost_per_unit_minor)
        if movement_cost != unit_cost:
            raise ValueError(
                "stock movement unit cost does not match its batch acquisition cost"
            )
        raw_value += _as_decimal(movement.qty_delta) * Decimal(unit_cost)
        next_rounded_value = round_minor(raw_value)
        allocated.append(next_rounded_value - rounded_value)
        rounded_value = next_rounded_value
    return allocated


async def remaining_batch_totals(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID | None = None,
) -> dict[UUID, RemainingBatchTotal]:
    """Return remaining quantity/value per ingredient within a branch scope.

    Positive stock, zero-cost stock, and explicit negative shortage batches
    all participate. Excluding negative balances would overstate the asset;
    excluding expired batches would silently write off physical stock without
    an authorised waste/impairment movement. Company-wide valuation also keeps
    batches in an inactive branch visible until they are explicitly transferred
    or written off. Expiry and operational branch status do not erase an asset.
    """

    statement = (
        select(
            Batch.ingredient_id,
            func.coalesce(func.sum(Batch.qty_on_hand), 0),
            func.coalesce(
                # Every GRN capitalises each receipt/batch line separately in
                # whole paise. Round each remaining FIFO layer the same way
                # before summing; rounding only after ingredient aggregation
                # can differ by one or more paise across fractional batches.
                func.sum(
                    func.round(
                        Batch.qty_on_hand * Batch.cost_per_unit_minor,
                        0,
                    )
                ),
                0,
            ),
        )
        .join(Ingredient, Ingredient.id == Batch.ingredient_id)
        .join(Branch, Branch.id == Batch.branch_id)
        .where(
            Ingredient.company_id == company_id,
            Ingredient.deleted_at.is_(None),
            Branch.company_id == company_id,
        )
        .group_by(Batch.ingredient_id)
    )
    if branch_id is not None:
        statement = statement.where(Batch.branch_id == branch_id)

    rows = (await session.execute(statement)).all()
    return {
        ingredient_id: RemainingBatchTotal(
            ingredient_id=ingredient_id,
            qty=_as_decimal(qty),
            valuation=_as_decimal(valuation),
        )
        for ingredient_id, qty, valuation in rows
    }


async def remaining_inventory_value_minor(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID | None = None,
) -> int:
    """Return the displayed total, exactly equal to the sum of line values."""

    totals = await remaining_batch_totals(
        session,
        company_id=company_id,
        branch_id=branch_id,
    )
    return sum(total.valuation_minor for total in totals.values())
