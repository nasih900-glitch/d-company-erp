"""Inventory endpoints — ingredients, suppliers, batches, GRN, adjustments, waste."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError, ConflictError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import (
    Batch,
    Branch,
    GRN,
    GRNLine,
    Ingredient,
    MenuItem,
    PurchaseOrder,
    PurchaseOrderLine,
    Recipe,
    RecipeLine,
    StockMovement,
    Supplier,
)

router = APIRouter()


def _optional_idempotency(request: Request) -> tuple[str, str] | tuple[None, None]:
    """Same shape as menu/router.py's helper of the same name — a missing key
    is not an error here, since the web app has never sent one for ingredient/
    supplier writes and must keep working unchanged. Used only where a real
    fallback (a unique-constraint IntegrityError, or nothing worth protecting)
    already covers the no-key case — see `_require_idempotency` below for the
    writes that have no such fallback and can't be optional.
    """
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        return None, None
    return str(key), str(request_hash)


def _require_idempotency(request: Request) -> tuple[str, str]:
    """GRN and adjustments create/mutate rows with no natural unique key to
    fall back on (a fresh uuid4() PK every time, no constraint a duplicate
    could collide against) — unlike menu/customers, there is no IntegrityError
    safety net available if a caller skips this. A resubmitted GRN silently
    inflates stock and purchase spend; a resubmitted adjustment silently
    double-applies a stock change. Idempotency is the only mechanism that
    closes this gap, so it's mandatory here, same as gaming's session-start.
    """
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for GRN/adjustment writes")
    return str(key), str(request_hash)


async def _require_writable_branch(
    session: SessionDep,
    tenant: TenantContext,
    branch_id: UUID,
) -> Branch:
    branch = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not branch or not tenant.in_branch(branch_id):
        raise NotFoundError("branch not found")
    return branch


async def _get_menu_item_for_tenant(
    session: SessionDep, tenant: TenantContext, menu_item_id: UUID
) -> MenuItem:
    item = await session.get(MenuItem, menu_item_id)
    if not item or item.company_id != tenant.company_id or item.deleted_at:
        raise NotFoundError("menu item not found")
    return item


async def _get_ingredient_for_tenant(
    session: SessionDep, tenant: TenantContext, ingredient_id: UUID
) -> Ingredient:
    ing = await session.get(Ingredient, ingredient_id)
    if not ing or ing.company_id != tenant.company_id or ing.deleted_at:
        raise NotFoundError(f"ingredient {ingredient_id} not found")
    return ing


async def _get_active_recipe_for_tenant(
    session: SessionDep, tenant: TenantContext, recipe_id: UUID
) -> Recipe:
    """Recipe carries no company_id of its own — tenant scope is proved by
    joining through its MenuItem, same as the FK relationship deduction.py
    relies on. Requiring is_active mirrors the deleted_at check every other
    resource in this router does before allowing an edit: a soft-deleted
    (is_active=False) recipe behaves like a deleted row for write purposes.
    (Listing recipes is the one place that intentionally bypasses this, so
    deactivated versions stay visible for history — see list_recipes.)
    """
    recipe = (
        await session.execute(
            select(Recipe)
            .join(MenuItem, MenuItem.id == Recipe.menu_item_id)
            .where(Recipe.id == recipe_id, MenuItem.company_id == tenant.company_id)
        )
    ).scalar_one_or_none()
    if not recipe or not recipe.is_active:
        raise NotFoundError("recipe not found")
    return recipe


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


class RecipeLineRead(BaseModel):
    id: UUID
    ingredient_id: UUID
    qty: float
    wastage_pct: float


class RecipeLineIn(BaseModel):
    ingredient_id: UUID
    qty: float = Field(gt=0)
    # Fraction, not a percent — 0.05 means 5% wastage. Matches the column
    # comment/semantics in app/models/inventory.py and how deduction.py
    # consumes it: qty * (1 + wastage_pct).
    wastage_pct: float = Field(default=0, ge=0, le=1)


class RecipeLineUpdate(BaseModel):
    ingredient_id: UUID | None = None
    qty: float | None = Field(default=None, gt=0)
    wastage_pct: float | None = Field(default=None, ge=0, le=1)


class RecipeRead(BaseModel):
    id: UUID
    menu_item_id: UUID
    name: str
    yield_qty: float
    version: int
    is_active: bool
    cost_minor: int
    lines: list[RecipeLineRead]


class RecipeCreate(BaseModel):
    menu_item_id: UUID
    name: str = Field(min_length=1, max_length=200)
    yield_qty: float = Field(default=1, gt=0)
    lines: list[RecipeLineIn] = Field(default_factory=list)


class RecipeUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    yield_qty: float | None = Field(default=None, gt=0)


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
    request: Request,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> IngredientRead:
    idempotency_key, request_hash = _optional_idempotency(request)
    if idempotency_key:
        existing_response = await check_or_reserve(
            session, key=idempotency_key, request_hash=request_hash,
            user_id=tenant.user_id, terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return IngredientRead.model_validate(existing_response["body"])

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
    try:
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise ConflictError(f"an ingredient with SKU '{payload.sku}' already exists") from exc

    response = IngredientRead(
        id=ing.id, sku=ing.sku, name=ing.name, base_unit=ing.base_unit,
        current_qty=0.0, reorder_threshold=float(ing.reorder_threshold),
        reorder_qty=float(ing.reorder_qty), avg_cost_minor=0,
    )
    if idempotency_key:
        await store_response(
            session, key=idempotency_key,
            status_code=status.HTTP_201_CREATED, body=response.model_dump(mode="json"),
        )
    return response


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
# RECIPES / BOM — what a menu item consumes at checkout.
#
# Recipe carries no company_id of its own (see app/models/inventory.py) —
# tenant scope is proved by joining through MenuItem, exactly like
# deduction.py's own Recipe.menu_item_id lookups. Recipe has no deleted_at
# either; its own is_active flag *is* the soft-delete equivalent, and
# deduct_for_order() already filters on Recipe.is_active.is_(True), so
# flipping it off here takes effect at the next checkout with zero changes
# needed on the deduction side. RecipeLine has neither field — it is a pure
# child row of Recipe, so its own delete is a hard delete.
# ============================================================================
def _recipe_read(recipe: Recipe, lines: list[RecipeLine]) -> RecipeRead:
    return RecipeRead(
        id=recipe.id,
        menu_item_id=recipe.menu_item_id,
        name=recipe.name,
        yield_qty=float(recipe.yield_qty),
        version=recipe.version,
        is_active=recipe.is_active,
        cost_minor=int(recipe.cost_minor or 0),
        lines=[
            RecipeLineRead(
                id=ln.id, ingredient_id=ln.ingredient_id,
                qty=float(ln.qty), wastage_pct=float(ln.wastage_pct or 0),
            )
            for ln in lines
        ],
    )


@router.get("/recipes", response_model=list[RecipeRead])
async def list_recipes(
    session: SessionDep,
    menu_item_id: UUID,
    tenant: TenantContext = Depends(requires("inventory.read")),
) -> list[RecipeRead]:
    """List recipes for one menu item, newest version first.

    In practice there's at most one is_active=True recipe per item at a
    time (deduct_for_order relies on that), but older/deactivated versions
    stay listed here for history — they just aren't editable anymore.
    """
    await _get_menu_item_for_tenant(session, tenant, menu_item_id)
    recipes = (
        await session.execute(
            select(Recipe)
            .where(Recipe.menu_item_id == menu_item_id)
            .order_by(Recipe.version.desc())
        )
    ).scalars().all()
    if not recipes:
        return []
    recipe_ids = [r.id for r in recipes]
    all_lines = (
        await session.execute(
            select(RecipeLine).where(RecipeLine.recipe_id.in_(recipe_ids))
        )
    ).scalars().all()
    lines_by_recipe: dict[UUID, list[RecipeLine]] = {}
    for ln in all_lines:
        lines_by_recipe.setdefault(ln.recipe_id, []).append(ln)
    return [_recipe_read(r, lines_by_recipe.get(r.id, [])) for r in recipes]


@router.post("/recipes", response_model=RecipeRead, status_code=status.HTTP_201_CREATED)
async def create_recipe(
    payload: RecipeCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> RecipeRead:
    await _get_menu_item_for_tenant(session, tenant, payload.menu_item_id)

    # Serialize recipe creation per menu item: lock the menu item row first
    # so a second near-simultaneous "create recipe" request for the same
    # item blocks here until the first transaction commits its insert, then
    # correctly sees the row via the existing-active check below and
    # rejects with ConflictError. Locking the Recipe check itself would not
    # close the race — with no active recipe row yet, FOR UPDATE has
    # nothing to lock and both requests would still see zero rows. Same
    # lock-the-parent-row pattern as clock_in() locking User before
    # checking Attendance, and _require_writable_branch() locking Branch
    # before checking for an existing open Shift.
    await session.execute(
        select(MenuItem).where(MenuItem.id == payload.menu_item_id).with_for_update()
    )

    existing_active = (
        await session.execute(
            select(Recipe).where(
                Recipe.menu_item_id == payload.menu_item_id,
                Recipe.is_active.is_(True),
            )
        )
    ).scalar_one_or_none()
    if existing_active:
        raise ConflictError(
            "this menu item already has an active recipe — edit it (PATCH) "
            "or delete it first"
        )

    # Bump past any prior (now-inactive) version so uq_recipe_version never
    # collides, same as the seed script's version=1-and-done in practice but
    # safe if this item ever had a recipe before.
    max_version = (
        await session.execute(
            select(Recipe.version)
            .where(Recipe.menu_item_id == payload.menu_item_id)
            .order_by(Recipe.version.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    next_version = (max_version or 0) + 1

    for line in payload.lines:
        await _get_ingredient_for_tenant(session, tenant, line.ingredient_id)

    recipe = Recipe(
        id=uuid4(),
        menu_item_id=payload.menu_item_id,
        name=payload.name,
        yield_qty=payload.yield_qty,
        version=next_version,
        is_active=True,
    )
    session.add(recipe)
    await session.flush()

    lines = [
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe.id,
            ingredient_id=line.ingredient_id,
            qty=line.qty,
            wastage_pct=line.wastage_pct,
        )
        for line in payload.lines
    ]
    session.add_all(lines)
    await session.flush()

    return _recipe_read(recipe, lines)


@router.patch("/recipes/{recipe_id}", response_model=RecipeRead)
async def update_recipe(
    recipe_id: UUID,
    payload: RecipeUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> RecipeRead:
    recipe = await _get_active_recipe_for_tenant(session, tenant, recipe_id)
    if payload.name is not None:
        recipe.name = payload.name
    if payload.yield_qty is not None:
        recipe.yield_qty = payload.yield_qty
    await session.flush()
    lines = (
        await session.execute(
            select(RecipeLine).where(RecipeLine.recipe_id == recipe.id)
        )
    ).scalars().all()
    return _recipe_read(recipe, list(lines))


@router.delete("/recipes/{recipe_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_recipe(
    recipe_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
):
    """Soft-delete-equivalent: deactivate the recipe.

    deduct_for_order() only ever reads Recipe rows with is_active=True, so
    this takes effect immediately — the menu item just goes back to being
    treated as recipe-less (skipped, no error) at the next checkout.
    """
    recipe = await _get_active_recipe_for_tenant(session, tenant, recipe_id)
    recipe.is_active = False
    await session.flush()


@router.post(
    "/recipes/{recipe_id}/lines",
    response_model=RecipeLineRead,
    status_code=status.HTTP_201_CREATED,
)
async def add_recipe_line(
    recipe_id: UUID,
    payload: RecipeLineIn,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> RecipeLineRead:
    recipe = await _get_active_recipe_for_tenant(session, tenant, recipe_id)
    await _get_ingredient_for_tenant(session, tenant, payload.ingredient_id)
    line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=payload.ingredient_id,
        qty=payload.qty,
        wastage_pct=payload.wastage_pct,
    )
    session.add(line)
    await session.flush()
    return RecipeLineRead(
        id=line.id, ingredient_id=line.ingredient_id,
        qty=float(line.qty), wastage_pct=float(line.wastage_pct or 0),
    )


@router.patch("/recipes/{recipe_id}/lines/{line_id}", response_model=RecipeLineRead)
async def update_recipe_line(
    recipe_id: UUID,
    line_id: UUID,
    payload: RecipeLineUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> RecipeLineRead:
    await _get_active_recipe_for_tenant(session, tenant, recipe_id)
    line = (
        await session.execute(
            select(RecipeLine).where(
                RecipeLine.id == line_id, RecipeLine.recipe_id == recipe_id
            )
        )
    ).scalar_one_or_none()
    if not line:
        raise NotFoundError("recipe line not found")
    if payload.ingredient_id is not None:
        await _get_ingredient_for_tenant(session, tenant, payload.ingredient_id)
        line.ingredient_id = payload.ingredient_id
    if payload.qty is not None:
        line.qty = payload.qty
    if payload.wastage_pct is not None:
        line.wastage_pct = payload.wastage_pct
    await session.flush()
    return RecipeLineRead(
        id=line.id, ingredient_id=line.ingredient_id,
        qty=float(line.qty), wastage_pct=float(line.wastage_pct or 0),
    )


@router.delete("/recipes/{recipe_id}/lines/{line_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_recipe_line(
    recipe_id: UUID,
    line_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.write")),
):
    await _get_active_recipe_for_tenant(session, tenant, recipe_id)
    line = (
        await session.execute(
            select(RecipeLine).where(
                RecipeLine.id == line_id, RecipeLine.recipe_id == recipe_id
            )
        )
    ).scalar_one_or_none()
    if not line:
        raise NotFoundError("recipe line not found")
    await session.delete(line)
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
    request: Request,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> SupplierRead:
    # Supplier has no unique natural key (a duplicate name is legal — the
    # same real-world supplier chain can legitimately have two branches
    # entered with the same name), so there's no IntegrityError fallback to
    # lean on the way ingredient's SKU gives it one. Idempotency-key replay
    # is the only protection available; still optional (not required) since
    # a duplicate supplier row is an annoyance to clean up, not a stock/money
    # correctness bug like GRN/adjustments.
    idempotency_key, request_hash = _optional_idempotency(request)
    if idempotency_key:
        existing_response = await check_or_reserve(
            session, key=idempotency_key, request_hash=request_hash,
            user_id=tenant.user_id, terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return SupplierRead.model_validate(existing_response["body"])

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
    response = SupplierRead(
        id=s.id, name=s.name, contact=s.contact, gstin=s.gstin,
        payment_terms=s.payment_terms,
    )
    if idempotency_key:
        await store_response(
            session, key=idempotency_key,
            status_code=status.HTTP_201_CREATED, body=response.model_dump(mode="json"),
        )
    return response


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
    request: Request,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> dict:
    """Record receipt of stock — creates a Batch per line and bumps Ingredient.current_qty.

    The simple receive-stock path creates an implicit closed purchase order so
    every stock receipt has a durable GRN header, line, batch, and movement trail.

    Idempotency-Key is required (see `_require_idempotency`) — every row this
    endpoint creates gets a fresh uuid4() PK with nothing a duplicate
    submission could collide against, so a resubmitted GRN would otherwise
    silently double stock and spend with no error and no trace it happened.
    """
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session, key=idempotency_key, request_hash=request_hash,
        user_id=tenant.user_id, terminal_id=tenant.terminal_id,
    )
    if existing_response is not None:
        return existing_response["body"]

    if not payload.lines:
        raise BusinessRuleError("at least one line required")
    if payload.supplier_id is None:
        raise BusinessRuleError("supplier_id is required to record a GRN")
    await _require_writable_branch(session, tenant, payload.branch_id)
    supplier = await session.get(Supplier, payload.supplier_id)
    if not supplier or supplier.company_id != tenant.company_id or supplier.deleted_at:
        raise NotFoundError("supplier not found")

    received_at = payload.received_at or datetime.now(timezone.utc)
    total_minor = sum(int(ln.qty * ln.unit_cost_minor) for ln in payload.lines)
    po = PurchaseOrder(
        id=uuid4(),
        company_id=tenant.company_id,
        supplier_id=payload.supplier_id,
        branch_id=payload.branch_id,
        po_number=f"GRN-{received_at:%Y%m%d}-{uuid4().hex[:8].upper()}",
        status="closed",
        expected_at=received_at,
        total_minor=total_minor,
        created_by=tenant.user_id,
    )
    session.add(po)
    await session.flush()

    grn = GRN(
        id=uuid4(),
        purchase_order_id=po.id,
        received_at=received_at,
        received_by=tenant.user_id,
        supplier_invoice_no=payload.supplier_invoice_no,
        supplier_invoice_amount_minor=payload.supplier_invoice_amount_minor,
        notes=payload.notes,
    )
    session.add(grn)
    await session.flush()

    batch_ids: list[str] = []
    line_ids: list[str] = []

    # Ingredient is company-scoped, not branch-scoped, and this loop takes a
    # row lock per line — sorted by id first so two concurrent GRNs against
    # *different* branches that happen to reference the same ingredients in
    # reversed order acquire those locks in the same canonical order instead
    # of deadlocking (A holds flour, waits on sugar; B holds sugar, waits on
    # flour). Order within a payload has no other meaning, so this is free.
    for ln in sorted(payload.lines, key=lambda line: str(line.ingredient_id)):
        ing = (
            await session.execute(
                select(Ingredient)
                .where(Ingredient.id == ln.ingredient_id)
                .with_for_update()
            )
        ).scalar_one_or_none()
        if not ing or ing.company_id != tenant.company_id or ing.deleted_at:
            raise NotFoundError(f"ingredient {ln.ingredient_id} not found")

        po_line = PurchaseOrderLine(
            id=uuid4(),
            purchase_order_id=po.id,
            ingredient_id=ing.id,
            qty_ordered=ln.qty,
            qty_received=ln.qty,
            unit_cost_minor=ln.unit_cost_minor,
        )
        session.add(po_line)

        batch = Batch(
            id=uuid4(),
            ingredient_id=ing.id,
            branch_id=payload.branch_id,
            supplier_id=payload.supplier_id,
            grn_id=grn.id,
            received_at=received_at,
            expires_at=ln.expires_at,
            qty_initial=ln.qty,
            qty_on_hand=ln.qty,
            cost_per_unit_minor=ln.unit_cost_minor,
            lot_code=ln.lot_code,
        )
        session.add(batch)
        await session.flush()

        grn_line = GRNLine(
            id=uuid4(),
            grn_id=grn.id,
            ingredient_id=ing.id,
            batch_id=batch.id,
            qty_received=ln.qty,
            cost_per_unit_minor=ln.unit_cost_minor,
        )
        session.add(grn_line)

        mv = StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=payload.branch_id,
            type="grn",
            ref_type="grn",
            ref_id=grn.id,
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
        line_ids.append(str(grn_line.id))

    await session.flush()
    response = {
        "ok": True,
        "id": str(grn.id),
        "purchase_order_id": str(po.id),
        "batches_created": len(batch_ids),
        "batch_ids": batch_ids,
        "line_ids": line_ids,
        "total_minor": total_minor,
        "supplier_invoice_no": payload.supplier_invoice_no,
    }
    await store_response(
        session, key=idempotency_key,
        status_code=status.HTTP_201_CREATED, body=response,
    )
    return response


# ============================================================================
# Adjustments / Waste
# ============================================================================
@router.post("/adjustments", status_code=status.HTTP_201_CREATED)
async def post_adjustment(
    payload: StockAdjustment,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("inventory.write")),
) -> dict:
    """Manual stock adjustment (waste, damage, transfer, count correction).

    Picks the oldest batch with remaining stock (FIFO for negative deltas).
    Updates Ingredient.current_qty.

    Idempotency-Key is required, same reasoning as post_grn — a resubmitted
    adjustment would otherwise silently double-apply a stock change with
    nothing to catch the duplicate.
    """
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session, key=idempotency_key, request_hash=request_hash,
        user_id=tenant.user_id, terminal_id=tenant.terminal_id,
    )
    if existing_response is not None:
        return existing_response["body"]

    if payload.qty_delta == 0:
        raise BusinessRuleError("qty_delta must be non-zero")

    await _require_writable_branch(session, tenant, payload.branch_id)

    ing = (
        await session.execute(
            select(Ingredient)
            .where(Ingredient.id == payload.ingredient_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
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
                .with_for_update()
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
                .with_for_update()
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
    response = {"id": str(mv.id), "remaining": float(ing.current_qty)}
    await store_response(
        session, key=idempotency_key,
        status_code=status.HTTP_201_CREATED, body=response,
    )
    return response


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
