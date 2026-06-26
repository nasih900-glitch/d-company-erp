"""Inventory endpoints — ingredients, suppliers, batches, GRN, adjustments, waste."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import select, func

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError, ConflictError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import Batch, Ingredient, StockMovement, Supplier

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class IngredientRead(BaseModel):
    id: UUID
    sku: str
    name: str
    base_unit: str
    current_qty: float
    reorder_threshold: float
    reorder_qty: float
    avg_cost_minor: int


class IngredientCreate(BaseModel):
    sku: str = Field(min_length=1, max_length=50)
    name: str = Field(min_length=1, max_length=200)
    base_unit: Literal["ml", "g", "unit"]
    reorder_threshold: float = 0
    reorder_qty: float = 0


class IngredientUpdate(BaseModel):
    name: str | None = None
    base_unit: Literal["ml", "g", "unit"] | None = None
    reorder_threshold: float | None = None
    reorder_qty: float | None = None


class StockAdjustment(BaseModel):
    ingredient_id: UUID
    branch_id: UUID
    qty_delta: float  # can be negative
    type: Literal["waste", "damage", "transfer", "adjustment"]
    note: str | None = None


class SupplierRead(BaseModel):
    id: UUID
    name: str
    contact: str | None
    gstin: str | None
    payment_terms: str | None


class SupplierCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    contact: str | None = None
    gstin: str | None = None
    payment_terms: str | None = None


class SupplierUpdate(BaseModel):
    name: str | None = None
    contact: str | None = None
    gstin: str | None = None
    payment_terms: str | None = None


class GrnLineIn(BaseModel):
    ingredient_id: UUID
    qty: float = Field(gt=0)
    unit_cost_minor: int = Field(ge=0)
    expires_at: datetime | None = None
    lot_code: str | None = None


class GrnPost(BaseModel):
    branch_id: UUID
    supplier_id: UUID | None = None
    supplier_invoice_no: str | None = None
    supplier_invoice_amount_minor: int | None = None
    received_at: datetime | None = None
    notes: str | None = None
    lines: list[GrnLineIn] = Field(min_length=1)


# ============================================================================
# INGREDIENTS
# ============================================================================
@router.get("/ingredients", response_model=list[IngredientRead])
async def list_ingredients(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.read")),
) -> list[IngredientRead]:
    rows = (
        await session.execute(
            select(Ingredient).where(
                Ingredient.company_id == tenant.company_id,
                Ingredient.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    return [
        IngredientRead(
            id=r.id, sku=r.sku, name=r.name, base_unit=r.base_unit,
            current_qty=float(r.current_qty),
            reorder_threshold=float(r.reorder_threshold),
            reorder_qty=float(r.reorder_qty or 0),
            avg_cost_minor=int(r.avg_cost_minor or 0),
        )
        for r in rows
    ]


@router.post("/ingredients", response_model=IngredientRead, status_code=status.HTTP_201_CREATED)
async def create_ingredient(
    payload: IngredientCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> IngredientRead:
    existing = (
        await session.execute(
            select(Ingredient).where(
                Ingredient.company_id == tenant.company_id,
                Ingredient.sku == payload.sku,
            )
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError(f"an ingredient with SKU '{payload.sku}' already exists")
    ing = Ingredient(
        id=uuid4(),
        company_id=tenant.company_id,
        sku=payload.sku,
        name=payload.name,
        base_unit=payload.base_unit,
        reorder_threshold=payload.reorder_threshold,
        reorder_qty=payload.reorder_qty,
        current_qty=0,
        avg_cost_minor=0,
    )
    session.add(ing)
    await session.flush()
    return IngredientRead(
        id=ing.id, sku=ing.sku, name=ing.name, base_unit=ing.base_unit,
        current_qty=0.0, reorder_threshold=float(ing.reorder_threshold),
        reorder_qty=float(ing.reorder_qty), avg_cost_minor=0,
    )


@router.patch("/ingredients/{ingredient_id}", response_model=IngredientRead)
async def update_ingredient(
    ingredient_id: UUID,
    payload: IngredientUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> IngredientRead:
    ing = await session.get(Ingredient, ingredient_id)
    if not ing or ing.company_id != tenant.company_id or ing.deleted_at:
        raise NotFoundError("ingredient not found")
    if payload.name is not None: ing.name = payload.name
    if payload.base_unit is not None: ing.base_unit = payload.base_unit
    if payload.reorder_threshold is not None: ing.reorder_threshold = payload.reorder_threshold
    if payload.reorder_qty is not None: ing.reorder_qty = payload.reorder_qty
    await session.flush()
    return IngredientRead(
        id=ing.id, sku=ing.sku, name=ing.name, base_unit=ing.base_unit,
        current_qty=float(ing.current_qty),
        reorder_threshold=float(ing.reorder_threshold),
        reorder_qty=float(ing.reorder_qty),
        avg_cost_minor=int(ing.avg_cost_minor or 0),
    )


@router.delete("/ingredients/{ingredient_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_ingredient(
    ingredient_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
):
    ing = await session.get(Ingredient, ingredient_id)
    if not ing or ing.company_id != tenant.company_id or ing.deleted_at:
        raise NotFoundError("ingredient not found")
    ing.deleted_at = datetime.now(timezone.utc)
    await session.flush()


# ============================================================================
# SUPPLIERS
# ============================================================================
@router.get("/suppliers", response_model=list[SupplierRead])
async def list_suppliers(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.read")),
) -> list[SupplierRead]:
    rows = (
        await session.execute(
            select(Supplier).where(
                Supplier.company_id == tenant.company_id,
                Supplier.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    return [
        SupplierRead(
            id=r.id, name=r.name, contact=r.contact, gstin=r.gstin,
            payment_terms=r.payment_terms,
        )
        for r in rows
    ]


@router.post("/suppliers", response_model=SupplierRead, status_code=status.HTTP_201_CREATED)
async def create_supplier(
    payload: SupplierCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> SupplierRead:
    s = Supplier(
        id=uuid4(),
        company_id=tenant.company_id,
        name=payload.name,
        contact=payload.contact,
        gstin=payload.gstin,
        payment_terms=payload.payment_terms,
    )
    session.add(s)
    await session.flush()
    return SupplierRead(
        id=s.id, name=s.name, contact=s.contact, gstin=s.gstin,
        payment_terms=s.payment_terms,
    )


@router.patch("/suppliers/{supplier_id}", response_model=SupplierRead)
async def update_supplier(
    supplier_id: UUID,
    payload: SupplierUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> SupplierRead:
    s = await session.get(Supplier, supplier_id)
    if not s or s.company_id != tenant.company_id or s.deleted_at:
        raise NotFoundError("supplier not found")
    if payload.name is not None: s.name = payload.name
    if payload.contact is not None: s.contact = payload.contact
    if payload.gstin is not None: s.gstin = payload.gstin
    if payload.payment_terms is not None: s.payment_terms = payload.payment_terms
    await session.flush()
    return SupplierRead(
        id=s.id, name=s.name, contact=s.contact, gstin=s.gstin,
        payment_terms=s.payment_terms,
    )


@router.delete("/suppliers/{supplier_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_supplier(
    supplier_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
):
    s = await session.get(Supplier, supplier_id)
    if not s or s.company_id != tenant.company_id or s.deleted_at:
        raise NotFoundError("supplier not found")
    s.deleted_at = datetime.now(timezone.utc)
    await session.flush()


# ============================================================================
# GRN — Goods Received Note (receive stock)
# ============================================================================
@router.post("/grn", status_code=status.HTTP_201_CREATED)
async def post_grn(
    payload: GrnPost,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> dict:
    """Record receipt of stock — creates a Batch per line and bumps Ingredient.current_qty.

    Skips the formal PurchaseOrder/GRN tables (used by the heavier procurement
    flow); for the simple "I just received some stock" path the cashier-level
    GRN modal posts here.
    """
    if not payload.lines:
        raise BusinessRuleError("at least one line required")

    received_at = payload.received_at or datetime.now(timezone.utc)
    batch_ids: list[str] = []

    for ln in payload.lines:
        ing = await session.get(Ingredient, ln.ingredient_id)
        if not ing or ing.company_id != tenant.company_id or ing.deleted_at:
            raise NotFoundError(f"ingredient {ln.ingredient_id} not found")

        batch = Batch(
            id=uuid4(),
            ingredient_id=ing.id,
            branch_id=payload.branch_id,
            supplier_id=payload.supplier_id,
            grn_id=None,
            received_at=received_at,
            expires_at=ln.expires_at,
            qty_initial=ln.qty,
            qty_on_hand=ln.qty,
            cost_per_unit_minor=ln.unit_cost_minor,
            lot_code=ln.lot_code,
        )
        session.add(batch)
        await session.flush()

        mv = StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=payload.branch_id,
            type="grn",
            ref_type="grn",
            ref_id=None,
            qty_delta=ln.qty,
            cost_per_unit_minor=ln.unit_cost_minor,
            created_by=tenant.user_id,
            note=payload.notes,
        )
        session.add(mv)

        # Rolling avg-cost + current-qty update
        prev_qty = float(ing.current_qty or 0)
        prev_val = prev_qty * int(ing.avg_cost_minor or 0)
        new_qty = prev_qty + float(ln.qty)
        new_val = prev_val + float(ln.qty) * int(ln.unit_cost_minor)
        ing.current_qty = new_qty
        ing.avg_cost_minor = int(new_val / new_qty) if new_qty > 0 else int(ln.unit_cost_minor)

        batch_ids.append(str(batch.id))

    await session.flush()
    return {
        "ok": True,
        "batches_created": len(batch_ids),
        "batch_ids": batch_ids,
        "supplier_invoice_no": payload.supplier_invoice_no,
    }


# ============================================================================
# Adjustments / Waste
# ============================================================================
@router.post("/adjustments", status_code=status.HTTP_201_CREATED)
async def post_adjustment(
    payload: StockAdjustment,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> dict:
    """Manual stock adjustment (waste, damage, transfer, count correction).

    Picks the oldest batch with remaining stock (FIFO for negative deltas).
    Updates Ingredient.current_qty.
    """
    if payload.qty_delta == 0:
        raise BusinessRuleError("qty_delta must be non-zero")

    ing = await session.get(Ingredient, payload.ingredient_id)
    if not ing or ing.company_id != tenant.company_id or ing.deleted_at:
        raise NotFoundError("ingredient not found")

    if payload.qty_delta < 0:
        # Need to consume from oldest batch with stock
        batch = (
            await session.execute(
                select(Batch)
                .where(
                    Batch.ingredient_id == payload.ingredient_id,
                    Batch.branch_id == payload.branch_id,
                    Batch.qty_on_hand > 0,
                )
                .order_by(Batch.received_at)
                .limit(1)
            )
        ).scalar_one_or_none()
        if not batch:
            raise BusinessRuleError("no stock available to reduce")
        consume = min(float(batch.qty_on_hand), -float(payload.qty_delta))
        batch.qty_on_hand = float(batch.qty_on_hand) - consume
        cost_per_unit = int(batch.cost_per_unit_minor)
    else:
        # Positive adjustment: append to latest batch (or could create a new one).
        batch = (
            await session.execute(
                select(Batch)
                .where(
                    Batch.ingredient_id == payload.ingredient_id,
                    Batch.branch_id == payload.branch_id,
                )
                .order_by(Batch.received_at.desc())
                .limit(1)
            )
        ).scalar_one_or_none()
        if not batch:
            raise BusinessRuleError(
                "no batch exists for this ingredient — record a GRN first"
            )
        batch.qty_on_hand = float(batch.qty_on_hand) + float(payload.qty_delta)
        cost_per_unit = int(batch.cost_per_unit_minor)

    mv = StockMovement(
        id=uuid4(),
        batch_id=batch.id,
        branch_id=payload.branch_id,
        type=payload.type,
        qty_delta=payload.qty_delta,
        cost_per_unit_minor=cost_per_unit,
        created_by=tenant.user_id,
        note=payload.note,
    )
    session.add(mv)
    ing.current_qty = float(ing.current_qty or 0) + float(payload.qty_delta)
    if ing.current_qty < 0:
        ing.current_qty = 0
    await session.flush()
    return {"id": str(mv.id), "remaining": float(ing.current_qty)}


# ============================================================================
# Batches (for FIFO inspection)
# ============================================================================
class BatchRead(BaseModel):
    id: UUID
    ingredient_id: UUID
    received_at: datetime
    expires_at: datetime | None
    qty_on_hand: float
    cost_per_unit_minor: int
    lot_code: str | None


@router.get("/batches", response_model=list[BatchRead])
async def list_batches(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.read")),
    ingredient_id: UUID | None = None,
) -> list[BatchRead]:
    """List active batches, optionally filtered by ingredient."""
    stmt = (
        select(Batch)
        .join(Ingredient, Ingredient.id == Batch.ingredient_id)
        .where(Ingredient.company_id == tenant.company_id, Batch.qty_on_hand > 0)
        .order_by(Batch.received_at)
    )
    if ingredient_id:
        stmt = stmt.where(Batch.ingredient_id == ingredient_id)
    rows = (await session.execute(stmt)).scalars().all()
    return [
        BatchRead(
            id=r.id, ingredient_id=r.ingredient_id,
            received_at=r.received_at, expires_at=r.expires_at,
            qty_on_hand=float(r.qty_on_hand),
            cost_per_unit_minor=int(r.cost_per_unit_minor),
            lot_code=r.lot_code,
        )
        for r in rows
    ]
