"""Menu endpoints — categories + items, full CRUD."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, status
from pydantic import BaseModel, Field
from sqlalchemy import select, func

from app.core.db import SessionDep
from app.core.errors import NotFoundError, ConflictError
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.models import MenuCategory, MenuItem

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class CategoryRead(BaseModel):
    id: UUID
    name: str
    sort_order: int


class CategoryCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    sort_order: int = 0


class CategoryUpdate(BaseModel):
    name: str | None = None
    sort_order: int | None = None


class ItemRead(BaseModel):
    id: UUID
    category_id: UUID
    sku: str
    name: str
    type: str
    base_price_minor: int
    tax_rate: float
    is_available: bool
    description: str | None = None


class ItemCreate(BaseModel):
    category_id: UUID
    sku: str = Field(min_length=1, max_length=50)
    name: str = Field(min_length=1, max_length=200)
    type: Literal["food", "drink", "dessert", "gaming", "event", "hookah", "streaming"]
    base_price_minor: int = Field(ge=0)
    tax_rate: float = Field(ge=0, le=1, default=0)
    description: str | None = None


class ItemUpdate(BaseModel):
    category_id: UUID | None = None
    name: str | None = None
    base_price_minor: int | None = Field(default=None, ge=0)
    tax_rate: float | None = Field(default=None, ge=0, le=1)
    description: str | None = None
    is_available: bool | None = None


# ---------------------------------------------------------------- CATEGORIES
@router.get("/categories", response_model=list[CategoryRead])
async def list_categories(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.read")),
) -> list[CategoryRead]:
    rows = (
        await session.execute(
            select(MenuCategory)
            .where(MenuCategory.company_id == tenant.company_id, MenuCategory.deleted_at.is_(None))
            .order_by(MenuCategory.sort_order)
        )
    ).scalars().all()
    return [CategoryRead(id=r.id, name=r.name, sort_order=r.sort_order) for r in rows]


@router.post("/categories", response_model=CategoryRead, status_code=status.HTTP_201_CREATED)
async def create_category(
    payload: CategoryCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
) -> CategoryRead:
    c = MenuCategory(
        id=uuid4(),
        company_id=tenant.company_id,
        name=payload.name,
        sort_order=payload.sort_order,
    )
    session.add(c)
    await session.flush()
    return CategoryRead(id=c.id, name=c.name, sort_order=c.sort_order)


@router.patch("/categories/{category_id}", response_model=CategoryRead)
async def update_category(
    category_id: UUID,
    payload: CategoryUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
) -> CategoryRead:
    c = await session.get(MenuCategory, category_id)
    if not c or c.company_id != tenant.company_id or c.deleted_at:
        raise NotFoundError("category not found")
    if payload.name is not None: c.name = payload.name
    if payload.sort_order is not None: c.sort_order = payload.sort_order
    await session.flush()
    return CategoryRead(id=c.id, name=c.name, sort_order=c.sort_order)


@router.delete("/categories/{category_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_category(
    category_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
):
    c = await session.get(MenuCategory, category_id)
    if not c or c.company_id != tenant.company_id or c.deleted_at:
        raise NotFoundError("category not found")
    item_count = (
        await session.execute(
            select(func.count())
            .select_from(MenuItem)
            .where(MenuItem.category_id == category_id, MenuItem.deleted_at.is_(None))
        )
    ).scalar_one()
    if item_count:
        raise ConflictError(
            f"cannot delete category — it has {item_count} item(s). Move or delete them first."
        )
    c.deleted_at = datetime.now(timezone.utc)
    await session.flush()


# ---------------------------------------------------------------- ITEMS
@router.get("/items", response_model=list[ItemRead])
async def list_items(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.read")),
    category_id: UUID | None = None,
) -> list[ItemRead]:
    stmt = select(MenuItem).where(
        MenuItem.company_id == tenant.company_id,
        MenuItem.deleted_at.is_(None),
    )
    if category_id:
        stmt = stmt.where(MenuItem.category_id == category_id)
    rows = (await session.execute(stmt)).scalars().all()
    return [
        ItemRead(
            id=r.id, category_id=r.category_id, sku=r.sku, name=r.name, type=r.type,
            base_price_minor=r.base_price_minor, tax_rate=float(r.tax_rate),
            is_available=r.is_available, description=r.description,
        )
        for r in rows
    ]


@router.post("/items", response_model=ItemRead, status_code=status.HTTP_201_CREATED)
async def create_item(
    payload: ItemCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ItemRead:
    require_pricing_unlock(x_pricing_token, tenant)
    cat = await session.get(MenuCategory, payload.category_id)
    if not cat or cat.company_id != tenant.company_id:
        raise NotFoundError("category not found")
    existing = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.company_id == tenant.company_id,
                MenuItem.sku == payload.sku,
            )
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError(f"an item with SKU '{payload.sku}' already exists")
    item = MenuItem(
        id=uuid4(),
        company_id=tenant.company_id,
        category_id=payload.category_id,
        sku=payload.sku, name=payload.name, type=payload.type,
        base_price_minor=payload.base_price_minor, tax_rate=payload.tax_rate,
        description=payload.description, is_available=True,
    )
    session.add(item)
    await session.flush()
    return ItemRead(
        id=item.id, category_id=item.category_id, sku=item.sku, name=item.name,
        type=item.type, base_price_minor=item.base_price_minor,
        tax_rate=float(item.tax_rate), is_available=item.is_available,
        description=item.description,
    )


@router.patch("/items/{item_id}", response_model=ItemRead)
async def update_item(
    item_id: UUID,
    payload: ItemUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ItemRead:
    if payload.base_price_minor is not None or payload.tax_rate is not None:
        require_pricing_unlock(x_pricing_token, tenant)
    item = await session.get(MenuItem, item_id)
    if not item or item.company_id != tenant.company_id or item.deleted_at:
        raise NotFoundError("item not found")
    if payload.category_id is not None:
        cat = await session.get(MenuCategory, payload.category_id)
        if not cat or cat.company_id != tenant.company_id:
            raise NotFoundError("category not found")
        item.category_id = payload.category_id
    if payload.name is not None: item.name = payload.name
    if payload.base_price_minor is not None: item.base_price_minor = payload.base_price_minor
    if payload.tax_rate is not None: item.tax_rate = payload.tax_rate
    if payload.description is not None: item.description = payload.description
    if payload.is_available is not None: item.is_available = payload.is_available
    await session.flush()
    return ItemRead(
        id=item.id, category_id=item.category_id, sku=item.sku, name=item.name,
        type=item.type, base_price_minor=item.base_price_minor,
        tax_rate=float(item.tax_rate), is_available=item.is_available,
        description=item.description,
    )


@router.delete("/items/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_item(
    item_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
):
    item = await session.get(MenuItem, item_id)
    if not item or item.company_id != tenant.company_id or item.deleted_at:
        raise NotFoundError("item not found")
    item.deleted_at = datetime.now(timezone.utc)
    await session.flush()
