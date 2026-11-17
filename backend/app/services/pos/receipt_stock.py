"""Exact historical inventory review for a full, unsupplied mistaken receipt.

This is a separate contract from zero-stock correction. No present-day recipe
is evidence of what the original payment consumed.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import select, text

from app.core.errors import BusinessRuleError
from app.models import Batch, Ingredient, PosReceiptStockRestoration, StockMovement
from app.services.inventory.deduction import UNCOSTED_SHORTAGE_LOT_CODE
from app.services.inventory.valuation import round_minor
from app.services.pos.receipt_corrections import review_source

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

    from app.core.tenant import TenantContext


@dataclass(frozen=True, slots=True)
class StockReview:
    blockers: tuple[str, ...]
    movements: tuple[dict, ...]
    inventory_increase_minor: int


def review_stock_source(snapshot: dict) -> StockReview:
    source = snapshot["receipt"]
    # Only this explicit inventory contract may waive the old stock blocker.
    blockers = list(review_source({**source, "stock_movements": []}).blockers)
    originals = source["stock_movements"]
    if not originals:
        blockers.append("requires_recorded_stock_consumption")
    if snapshot["restorations"]:
        blockers.append("stock_already_restored")
    by_batch = {item["batch"]["id"]: item for item in snapshot["batches"]}
    entries: list[dict] = []
    total = 0
    company_id, branch_id, order_id = (
        source["order"][key] for key in ("company_id", "branch_id", "id")
    )
    quantities: dict[str, Decimal] = {}
    try:
        for movement in originals:
            quantity = -Decimal(str(movement["qty_delta"]))
            item = by_batch.get(movement["batch_id"])
            if (
                item is None
                or movement["type"] != "sale"
                or movement["ref_type"] != "order"
                or movement["ref_id"] != order_id
                or movement["branch_id"] != branch_id
                or not quantity.is_finite()
                or quantity <= 0
            ):
                raise ValueError("unsupported consumption")
            batch, ingredient, branch = (item[key] for key in ("batch", "ingredient", "branch"))
            if (
                ingredient["company_id"] != company_id
                or ingredient["base_unit"] not in {"ml", "g", "unit"}
                or ingredient["deleted_at"] is not None
                or branch["company_id"] != company_id
                or batch["branch_id"] != branch_id
                or movement["cost_per_unit_minor"] != batch["cost_per_unit_minor"]
                or type(batch["cost_per_unit_minor"]) is not int
                or batch["cost_per_unit_minor"] <= 0
                or batch["lot_code"] == UNCOSTED_SHORTAGE_LOT_CODE
            ):
                raise ValueError("scope or cost")
            quantities[batch["id"]] = quantities.get(batch["id"], Decimal(0)) + quantity
            entries.append(
                {
                    "movement_id": movement["id"],
                    "batch_id": batch["id"],
                    "ingredient_id": ingredient["id"],
                    "ingredient_name": ingredient["name"],
                    "base_unit": ingredient["base_unit"],
                    "quantity": str(quantity),
                    "cost_per_unit_minor": batch["cost_per_unit_minor"],
                }
            )
        for batch_id, quantity in quantities.items():
            item = by_batch[batch_id]
            batch, ingredient = item["batch"], item["ingredient"]
            current = Decimal(str(batch["qty_on_hand"]))
            initial = Decimal(str(batch["qty_initial"]))
            if current < 0 or initial < 0:
                raise ValueError("shortage")
            # Validate every intervening movement before allocating its rounding
            # residual. A present balance alone cannot prove historical costing.
            movements = [m for m in item["movements"] if m["type"] != "grn"]
            balance = initial
            for movement in movements:
                q = Decimal(str(movement["qty_delta"]))
                if (
                    movement["branch_id"] != branch_id
                    or not q.is_finite()
                    or q == 0
                    or datetime.fromisoformat(movement["created_at"]) > datetime.now(UTC)
                    or movement["cost_per_unit_minor"] != batch["cost_per_unit_minor"]
                    or movement["type"]
                    not in {
                        "sale",
                        "refund_restock",
                        "receipt_restore",
                        "adjustment",
                        "waste",
                        "damage",
                    }
                    or (movement["type"] in {"sale", "waste", "damage"} and q >= 0)
                    or (movement["type"] in {"refund_restock", "receipt_restore"} and q <= 0)
                ):
                    raise ValueError("movement trail")
                balance += q
                if balance < 0:
                    raise ValueError("historical shortage")
            if balance != current:
                raise ValueError("batch balance")
            ingredient_batches = item["ingredient_batches"]
            if any(
                b["company_id"] != company_id or b["ingredient_id"] != ingredient["id"]
                for b in ingredient_batches
            ) or sum(
                (Decimal(str(b["qty_on_hand"])) for b in ingredient_batches), Decimal(0)
            ) != Decimal(str(ingredient["current_qty"])):
                raise ValueError("ingredient balance")
            # Identical to the shared chronological allocation: the aggregate
            # new value is rounded after all exact restorations, not per sale.
            total += round_minor((current + quantity) * batch["cost_per_unit_minor"]) - round_minor(
                current * batch["cost_per_unit_minor"]
            )
    except (KeyError, TypeError, ValueError, InvalidOperation):
        blockers.append("unreconciled_stock_scope_quantity_or_cost")
    return StockReview(tuple(dict.fromkeys(blockers)), tuple(entries), total)


async def load_stock_source(
    session: AsyncSession, tenant: TenantContext, order_id: UUID
) -> tuple[dict, str]:
    row = (
        await session.execute(
            text("""
        WITH captured AS (SELECT dcompany_pos_receipt_stock_source(:order_id,:company_id) source)
        SELECT source,encode(sha256(convert_to(source::text,'UTF8')),'hex') fingerprint
        FROM captured
    """),
            {"order_id": order_id, "company_id": tenant.company_id},
        )
    ).one()
    return row.source, row.fingerprint


async def lock_stock_source(session: AsyncSession, source: dict) -> None:
    batch_ids = sorted({UUID(m["batch_id"]) for m in source["stock_movements"]})
    ingredient_ids = list(
        (
            await session.scalars(
                select(Batch.ingredient_id)
                .where(Batch.id.in_(batch_ids))
                .distinct()
                .order_by(Batch.ingredient_id)
            )
        ).all()
    )
    # Protect FK parents before inventory locks; raw SQL deadlocks still use
    # the complete-transaction retry boundary, never partial restoration.
    await session.scalars(
        select(StockMovement)
        .where(StockMovement.batch_id.in_(batch_ids))
        .order_by(StockMovement.id)
        .with_for_update(read=True, key_share=True)
    )
    # Match every inventory writer's Ingredient -> Batch order. Lock all batches
    # of affected ingredients because their sum proves the catalogue balance.
    await session.scalars(
        select(Ingredient)
        .where(Ingredient.id.in_(ingredient_ids))
        .order_by(Ingredient.id)
        .with_for_update()
    )
    await session.scalars(
        select(Batch)
        .where(Batch.ingredient_id.in_(ingredient_ids))
        .order_by(Batch.ingredient_id, Batch.id)
        .with_for_update()
    )


async def restore_stock(
    session: AsyncSession,
    *,
    snapshot: dict,
    correction_id: UUID,
    company_id: UUID,
    actor_id: UUID,
    at: datetime,
) -> None:
    review = review_stock_source(snapshot)
    if review.blockers:
        raise BusinessRuleError(
            "Stock correction requires reconciled historical evidence.",
            details={"blockers": list(review.blockers)},
        )
    for entry in sorted(
        review.movements, key=lambda x: (x["ingredient_id"], x["batch_id"], x["movement_id"])
    ):
        batch = await session.get(Batch, UUID(entry["batch_id"]), populate_existing=True)
        ingredient = await session.get(
            Ingredient, UUID(entry["ingredient_id"]), populate_existing=True
        )
        quantity = Decimal(entry["quantity"])
        movement = StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=batch.branch_id,
            type="receipt_restore",
            ref_type="pos_receipt_correction",
            ref_id=correction_id,
            qty_delta=quantity,
            cost_per_unit_minor=entry["cost_per_unit_minor"],
            created_by=actor_id,
            created_at=at,
            note="Unsupplied mistaken receipt — no payout; correction " + str(correction_id),
        )
        batch.qty_on_hand = Decimal(str(batch.qty_on_hand)) + quantity
        ingredient.current_qty = Decimal(str(ingredient.current_qty)) + quantity
        session.add(movement)
        await session.flush()
        session.add(
            PosReceiptStockRestoration(
                id=uuid4(),
                company_id=company_id,
                correction_id=correction_id,
                original_movement_id=UUID(entry["movement_id"]),
                restoration_movement_id=movement.id,
            )
        )
    await session.flush()
