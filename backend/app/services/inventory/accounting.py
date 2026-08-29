"""Audited whole-paise value changes for physical inventory movements.

Batch balances are the inventory subledger. This module validates that every
batch still reconciles to its append-only movement trail, then allocates the
signed change in rounded batch value to each movement. Both the general ledger
and operational P&L consume this one result so their COGS/variance numbers
cannot drift through duplicated rounding logic.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import TYPE_CHECKING

from sqlalchemy import and_, or_, select

from app.core.errors import BusinessRuleError
from app.models import Batch, Branch, Ingredient, StockMovement
from app.services.inventory.valuation import allocate_batch_movement_value_deltas

if TYPE_CHECKING:
    from datetime import datetime
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


@dataclass(frozen=True, slots=True)
class InventoryValueChange:
    movement: StockMovement
    inventory_delta_minor: int


async def load_inventory_value_changes(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID | None = None,
    start_at: datetime | None = None,
    end_exclusive: datetime | None = None,
) -> list[InventoryValueChange]:
    """Validate the current subledger and return allocated movement values.

    The query intentionally loads every current company batch and every
    non-GRN movement, including rows outside the requested report window.
    Out-of-range rows are needed both to validate today's physical balance and
    to carry fractional-paise residuals into the correct in-range movement.
    """

    branch_scope = (Batch.branch_id == branch_id,) if branch_id is not None else ()
    statement = (
        select(Batch, Ingredient, Branch, StockMovement)
        .join(Ingredient, Ingredient.id == Batch.ingredient_id)
        .join(Branch, Branch.id == Batch.branch_id)
        .outerjoin(
            StockMovement,
            and_(
                StockMovement.batch_id == Batch.id,
                StockMovement.type != "grn",
            ),
        )
        # A malformed cross-company batch must fail for either affected
        # tenant; filtering on only one parent would make it disappear from
        # the other tenant's audit instead of surfacing the corruption.
        .where(
            *branch_scope,
            or_(
                Branch.company_id == company_id,
                Ingredient.company_id == company_id,
            )
        )
        .order_by(Batch.id, StockMovement.created_at, StockMovement.id)
    )
    rows = (await session.execute(statement)).all()
    grouped: dict[UUID, tuple[Batch, Ingredient, Branch, list[StockMovement]]] = {}
    for batch, ingredient, branch, movement in rows:
        group = grouped.setdefault(batch.id, (batch, ingredient, branch, []))
        if movement is not None:
            group[3].append(movement)

    output: list[InventoryValueChange] = []
    for batch, ingredient, branch, movements in grouped.values():
        if ingredient.company_id != company_id or branch.company_id != company_id:
            raise BusinessRuleError(
                f"inventory batch {batch.id} crosses its tenant scope"
            )

        replayed_qty = Decimal(str(batch.qty_initial or 0)) + sum(
            (Decimal(str(movement.qty_delta or 0)) for movement in movements),
            start=Decimal(0),
        )
        recorded_qty = Decimal(str(batch.qty_on_hand or 0))
        if replayed_qty != recorded_qty:
            raise BusinessRuleError(
                f"inventory batch {batch.id} quantity does not reconcile to its movement trail"
            )

        try:
            value_deltas = allocate_batch_movement_value_deltas(
                qty_initial=batch.qty_initial,
                cost_per_unit_minor=int(batch.cost_per_unit_minor),
                movements=movements,
            )
        except ValueError as exc:
            raise BusinessRuleError(
                f"inventory batch {batch.id} has inconsistent costing evidence"
            ) from exc

        for movement, inventory_delta in zip(movements, value_deltas, strict=True):
            quantity = Decimal(str(movement.qty_delta or 0))
            if batch.branch_id != movement.branch_id:
                raise BusinessRuleError(
                    f"inventory movement {movement.id} crosses its batch branch"
                )
            expected_sign = {
                "sale": -1,
                "refund_restock": 1,
                "waste": -1,
                "damage": -1,
            }.get(movement.type)
            if quantity == 0 or (
                expected_sign is not None
                and (quantity > 0) != (expected_sign > 0)
            ):
                raise BusinessRuleError(
                    f"inventory movement {movement.id} has invalid {movement.type} sign semantics"
                )
            if movement.type == "transfer":
                raise BusinessRuleError(
                    "Historical single-sided stock transfer requires owner reconciliation; "
                    "no destination inventory movement exists to prove an internal transfer."
                )
            if movement.type not in {
                "sale",
                "refund_restock",
                "waste",
                "damage",
                "adjustment",
            }:
                raise BusinessRuleError(
                    f"unsupported inventory movement type: {movement.type}"
                )
            if start_at is not None and movement.created_at < start_at:
                continue
            if end_exclusive is not None and movement.created_at >= end_exclusive:
                continue
            output.append(
                InventoryValueChange(
                    movement=movement,
                    inventory_delta_minor=inventory_delta,
                )
            )
    return output


__all__ = ["InventoryValueChange", "load_inventory_value_changes"]
