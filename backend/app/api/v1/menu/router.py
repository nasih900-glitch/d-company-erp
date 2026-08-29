"""Menu endpoints — categories + items, full CRUD."""

from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Request, status
from pydantic import BaseModel, Field, field_validator, model_validator
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.models import (
    MenuCategory,
    MenuItem,
    MenuModifier,
    MenuModifierGroup,
    MenuVariant,
)

router = APIRouter()


def _optional_idempotency(request: Request) -> tuple[str, str] | tuple[None, None]:
    """Unlike gaming's `_require_idempotency`, a missing key is not an error
    here — the web app's menu screen has never sent one and must keep
    working unchanged. The native app's offline outbox always sends one
    (deterministic, derived from its local row id), so it gets full
    check_or_reserve replay-safety; a caller that doesn't still gets the
    IntegrityError-catch safety net below, just not response replay on an
    exact-retry.
    """
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        return None, None
    return str(key), str(request_hash)


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


def _clean_name(value: str) -> str:
    cleaned = value.strip()
    if not cleaned:
        raise ValueError("name must not be blank")
    return cleaned


class VariantRead(BaseModel):
    id: UUID
    name: str
    price_delta_minor: int
    sort_order: int
    is_active: bool


class VariantCreate(BaseModel):
    name: str = Field(min_length=1, max_length=50)
    price_delta_minor: int = 0
    sort_order: int = Field(default=0, ge=0)
    is_active: bool = True

    _normalize_name = field_validator("name")(_clean_name)


class VariantUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=50)
    price_delta_minor: int | None = None
    sort_order: int | None = Field(default=None, ge=0)
    is_active: bool | None = None

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value: str | None) -> str | None:
        return _clean_name(value) if value is not None else None


class ModifierOptionRead(BaseModel):
    id: UUID
    modifier_group_id: UUID
    name: str
    price_delta_minor: int
    max_quantity: int
    sort_order: int
    is_active: bool


class ModifierOptionCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    price_delta_minor: int = 0
    max_quantity: int = Field(default=1, ge=1, le=99)
    sort_order: int = Field(default=0, ge=0)
    is_active: bool = True

    _normalize_name = field_validator("name")(_clean_name)


class ModifierOptionUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    price_delta_minor: int | None = None
    max_quantity: int | None = Field(default=None, ge=1, le=99)
    sort_order: int | None = Field(default=None, ge=0)
    is_active: bool | None = None

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value: str | None) -> str | None:
        return _clean_name(value) if value is not None else None


class ModifierGroupRead(BaseModel):
    id: UUID
    name: str
    min_select: int
    max_select: int
    sort_order: int
    is_active: bool
    options: list[ModifierOptionRead] = Field(default_factory=list)


class ModifierGroupCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    min_select: int = Field(default=0, ge=0, le=99)
    max_select: int = Field(default=1, ge=1, le=99)
    sort_order: int = Field(default=0, ge=0)
    is_active: bool = True

    _normalize_name = field_validator("name")(_clean_name)

    @model_validator(mode="after")
    def valid_selection_bounds(self) -> ModifierGroupCreate:
        if self.min_select > self.max_select:
            raise ValueError("min_select must not exceed max_select")
        return self


class ModifierGroupUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    min_select: int | None = Field(default=None, ge=0, le=99)
    max_select: int | None = Field(default=None, ge=1, le=99)
    sort_order: int | None = Field(default=None, ge=0)
    is_active: bool | None = None

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value: str | None) -> str | None:
        return _clean_name(value) if value is not None else None

    @model_validator(mode="after")
    def valid_selection_bounds(self) -> ModifierGroupUpdate:
        if (
            self.min_select is not None
            and self.max_select is not None
            and self.min_select > self.max_select
        ):
            raise ValueError("min_select must not exceed max_select")
        return self


class ItemRead(BaseModel):
    id: UUID
    category_id: UUID
    sku: str
    name: str
    type: str
    base_price_minor: int
    tax_rate: float
    hsn_code: str | None = None
    price_includes_tax: bool
    is_available: bool
    description: str | None = None
    variants: list[VariantRead] = Field(default_factory=list)
    modifier_groups: list[ModifierGroupRead] = Field(default_factory=list)


class ItemCreate(BaseModel):
    category_id: UUID
    sku: str = Field(min_length=1, max_length=50)
    name: str = Field(min_length=1, max_length=200)
    type: Literal["food", "drink", "dessert", "gaming", "event", "hookah", "streaming"]
    base_price_minor: int = Field(ge=0)
    tax_rate: float = Field(ge=0, le=1, default=0)
    hsn_code: str | None = Field(default=None, max_length=8)
    price_includes_tax: bool = True
    description: str | None = None


class ItemUpdate(BaseModel):
    category_id: UUID | None = None
    name: str | None = None
    base_price_minor: int | None = Field(default=None, ge=0)
    tax_rate: float | None = Field(default=None, ge=0, le=1)
    hsn_code: str | None = Field(default=None, max_length=8)
    price_includes_tax: bool | None = None
    description: str | None = None
    is_available: bool | None = None


def default_hsn_or_sac(item_type: str) -> str:
    """Fallback codes for D Company billing categories.

    These are conservative defaults; the business accountant can override them
    item-by-item from the menu screen.
    """
    if item_type in {"gaming", "event", "hookah", "streaming"}:
        return "999692"
    return "996331"


def clean_hsn_or_sac(value: str | None, item_type: str) -> str:
    cleaned = (value or "").strip()
    return cleaned or default_hsn_or_sac(item_type)


def variant_read(variant: MenuVariant) -> VariantRead:
    return VariantRead(
        id=variant.id,
        name=variant.name,
        price_delta_minor=variant.price_delta_minor,
        sort_order=variant.sort_order,
        is_active=variant.is_active,
    )


def modifier_option_read(option: MenuModifier) -> ModifierOptionRead:
    return ModifierOptionRead(
        id=option.id,
        modifier_group_id=option.modifier_group_id,
        name=option.name,
        price_delta_minor=option.price_delta_minor,
        max_quantity=option.max_quantity,
        sort_order=option.sort_order,
        is_active=option.is_active,
    )


def modifier_group_read(
    group: MenuModifierGroup,
    options: list[MenuModifier] | None = None,
) -> ModifierGroupRead:
    return ModifierGroupRead(
        id=group.id,
        name=group.name,
        min_select=group.min_select,
        max_select=group.max_select,
        sort_order=group.sort_order,
        is_active=group.is_active,
        options=[modifier_option_read(option) for option in (options or [])],
    )


def item_read(
    item: MenuItem,
    *,
    variants: list[MenuVariant] | None = None,
    modifier_groups: list[ModifierGroupRead] | None = None,
) -> ItemRead:
    return ItemRead(
        id=item.id,
        category_id=item.category_id,
        sku=item.sku,
        name=item.name,
        type=item.type,
        base_price_minor=item.base_price_minor,
        tax_rate=float(item.tax_rate),
        hsn_code=item.hsn_code,
        price_includes_tax=bool(item.price_includes_tax),
        is_available=item.is_available,
        description=item.description,
        variants=[variant_read(variant) for variant in (variants or [])],
        modifier_groups=modifier_groups or [],
    )


async def _configuration_by_item(
    session: SessionDep,
    *,
    company_id: UUID,
    item_ids: list[UUID],
) -> tuple[dict[UUID, list[MenuVariant]], dict[UUID, list[ModifierGroupRead]]]:
    variants_by_item: dict[UUID, list[MenuVariant]] = defaultdict(list)
    groups_by_item: dict[UUID, list[MenuModifierGroup]] = defaultdict(list)
    options_by_group: dict[UUID, list[MenuModifier]] = defaultdict(list)
    if not item_ids:
        return variants_by_item, {}

    variants = (
        await session.execute(
            select(MenuVariant)
            .where(
                MenuVariant.company_id == company_id,
                MenuVariant.menu_item_id.in_(item_ids),
            )
            .order_by(MenuVariant.menu_item_id, MenuVariant.sort_order, MenuVariant.name)
        )
    ).scalars().all()
    groups = (
        await session.execute(
            select(MenuModifierGroup)
            .where(
                MenuModifierGroup.company_id == company_id,
                MenuModifierGroup.menu_item_id.in_(item_ids),
            )
            .order_by(
                MenuModifierGroup.menu_item_id,
                MenuModifierGroup.sort_order,
                MenuModifierGroup.name,
            )
        )
    ).scalars().all()
    group_ids = [group.id for group in groups]
    options: list[MenuModifier] = []
    if group_ids:
        options = (
            await session.execute(
                select(MenuModifier)
                .where(
                    MenuModifier.company_id == company_id,
                    MenuModifier.modifier_group_id.in_(group_ids),
                )
                .order_by(
                    MenuModifier.modifier_group_id,
                    MenuModifier.sort_order,
                    MenuModifier.name,
                )
            )
        ).scalars().all()

    for variant in variants:
        variants_by_item[variant.menu_item_id].append(variant)
    for group in groups:
        groups_by_item[group.menu_item_id].append(group)
    for option in options:
        options_by_group[option.modifier_group_id].append(option)

    reads_by_item = {
        item_id: [
            modifier_group_read(group, options_by_group[group.id])
            for group in item_groups
        ]
        for item_id, item_groups in groups_by_item.items()
    }
    return variants_by_item, reads_by_item


async def _item_read_with_configuration(
    session: SessionDep,
    *,
    company_id: UUID,
    item: MenuItem,
) -> ItemRead:
    variants, groups = await _configuration_by_item(
        session,
        company_id=company_id,
        item_ids=[item.id],
    )
    return item_read(
        item,
        variants=variants.get(item.id, []),
        modifier_groups=groups.get(item.id, []),
    )


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
    request: Request,
    tenant: TenantContext = Depends(requires("menu.write")),
) -> CategoryRead:
    idempotency_key, request_hash = _optional_idempotency(request)
    if idempotency_key:
        assert request_hash is not None
        existing_response = await check_or_reserve(
            session,
            key=idempotency_key,
            request_hash=request_hash,
            user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return CategoryRead.model_validate(existing_response["body"])

    c = MenuCategory(
        id=uuid4(),
        company_id=tenant.company_id,
        name=payload.name,
        sort_order=payload.sort_order,
    )
    session.add(c)
    try:
        await session.flush()
    except IntegrityError as exc:
        # uq_menu_cat_name — no pre-check existed before this fix, so this
        # was a raw 500 on any collision (two offline devices queueing the
        # same new category name, most plausibly). Unlike a customer's
        # phone, a category name collision isn't a confirmed "same real
        # entity" — surface a clean, actionable conflict rather than
        # silently merging into whichever row won the race.
        await session.rollback()
        raise ConflictError(f"A category named '{payload.name}' already exists") from exc

    response = CategoryRead(id=c.id, name=c.name, sort_order=c.sort_order)
    if idempotency_key:
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
    return response


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
    rows = (await session.execute(stmt.order_by(MenuItem.name))).scalars().all()
    variants, groups = await _configuration_by_item(
        session,
        company_id=tenant.company_id,
        item_ids=[row.id for row in rows],
    )
    return [
        item_read(
            row,
            variants=variants.get(row.id, []),
            modifier_groups=groups.get(row.id, []),
        )
        for row in rows
    ]


@router.post("/items", response_model=ItemRead, status_code=status.HTTP_201_CREATED)
async def create_item(
    payload: ItemCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ItemRead:
    require_pricing_unlock(x_pricing_token, tenant)

    idempotency_key, request_hash = _optional_idempotency(request)
    if idempotency_key:
        assert request_hash is not None
        existing_response = await check_or_reserve(
            session,
            key=idempotency_key,
            request_hash=request_hash,
            user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return ItemRead.model_validate(existing_response["body"])

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
        hsn_code=clean_hsn_or_sac(payload.hsn_code, payload.type),
        price_includes_tax=payload.price_includes_tax,
        description=payload.description, is_available=True,
    )
    session.add(item)
    try:
        await session.flush()
    except IntegrityError as exc:
        # The pre-check above is a plain SELECT-then-INSERT, so it still has
        # a TOCTOU gap under real concurrency (two offline devices queueing
        # the same new SKU) — this closes it with a clean conflict instead
        # of a raw 500, same reasoning as create_category above.
        await session.rollback()
        raise ConflictError(f"an item with SKU '{payload.sku}' already exists") from exc

    response = item_read(item)
    if idempotency_key:
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
    return response


@router.patch("/items/{item_id}", response_model=ItemRead)
async def update_item(
    item_id: UUID,
    payload: ItemUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ItemRead:
    pricing_fields = {"base_price_minor", "tax_rate", "hsn_code", "price_includes_tax"}
    if pricing_fields.intersection(payload.model_fields_set):
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
    if "hsn_code" in payload.model_fields_set:
        item.hsn_code = clean_hsn_or_sac(payload.hsn_code, item.type)
    if payload.price_includes_tax is not None:
        item.price_includes_tax = payload.price_includes_tax
    if payload.description is not None: item.description = payload.description
    if payload.is_available is not None: item.is_available = payload.is_available
    await session.flush()
    return await _item_read_with_configuration(
        session,
        company_id=tenant.company_id,
        item=item,
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


async def _scoped_item(
    session: SessionDep,
    *,
    company_id: UUID,
    item_id: UUID,
) -> MenuItem:
    item = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.id == item_id,
                MenuItem.company_id == company_id,
                MenuItem.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if item is None:
        raise NotFoundError("menu item not found")
    return item


async def _scoped_variant(
    session: SessionDep,
    *,
    company_id: UUID,
    variant_id: UUID,
    for_update: bool = False,
) -> MenuVariant:
    stmt = select(MenuVariant).where(
        MenuVariant.id == variant_id,
        MenuVariant.company_id == company_id,
    )
    if for_update:
        stmt = stmt.with_for_update()
    variant = (await session.execute(stmt)).scalar_one_or_none()
    if variant is None:
        raise NotFoundError("menu variant not found")
    return variant


async def _scoped_modifier_group(
    session: SessionDep,
    *,
    company_id: UUID,
    group_id: UUID,
    for_update: bool = False,
) -> MenuModifierGroup:
    stmt = select(MenuModifierGroup).where(
        MenuModifierGroup.id == group_id,
        MenuModifierGroup.company_id == company_id,
    )
    if for_update:
        stmt = stmt.with_for_update()
    group = (await session.execute(stmt)).scalar_one_or_none()
    if group is None:
        raise NotFoundError("modifier group not found")
    return group


async def _scoped_modifier(
    session: SessionDep,
    *,
    company_id: UUID,
    modifier_id: UUID,
    for_update: bool = False,
) -> MenuModifier:
    stmt = select(MenuModifier).where(
        MenuModifier.id == modifier_id,
        MenuModifier.company_id == company_id,
    )
    if for_update:
        stmt = stmt.with_for_update()
    modifier = (await session.execute(stmt)).scalar_one_or_none()
    if modifier is None:
        raise NotFoundError("modifier option not found")
    return modifier


async def _active_modifier_capacity(
    session: SessionDep,
    *,
    group_id: UUID,
    excluding_modifier_id: UUID | None = None,
) -> int:
    stmt = select(func.coalesce(func.sum(MenuModifier.max_quantity), 0)).where(
        MenuModifier.modifier_group_id == group_id,
        MenuModifier.is_active.is_(True),
    )
    if excluding_modifier_id is not None:
        stmt = stmt.where(MenuModifier.id != excluding_modifier_id)
    return int((await session.execute(stmt)).scalar_one())


async def _flush_menu_configuration(
    session: SessionDep,
    *,
    conflict_message: str,
) -> None:
    try:
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise ConflictError(conflict_message) from exc


# ---------------------------------------------------------------- VARIANTS
@router.post(
    "/items/{item_id}/variants",
    response_model=VariantRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_variant(
    item_id: UUID,
    payload: VariantCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> VariantRead:
    require_pricing_unlock(x_pricing_token, tenant)
    idempotency_key, request_hash = _optional_idempotency(request)
    if idempotency_key:
        assert request_hash is not None
        existing_response = await check_or_reserve(
            session,
            key=idempotency_key,
            request_hash=request_hash,
            user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return VariantRead.model_validate(existing_response["body"])

    await _scoped_item(session, company_id=tenant.company_id, item_id=item_id)
    variant = MenuVariant(
        id=uuid4(),
        company_id=tenant.company_id,
        menu_item_id=item_id,
        name=payload.name,
        price_delta_minor=payload.price_delta_minor,
        sort_order=payload.sort_order,
        is_active=payload.is_active,
    )
    session.add(variant)
    await _flush_menu_configuration(
        session,
        conflict_message=f"a variant named '{payload.name}' already exists for this item",
    )
    response = variant_read(variant)
    if idempotency_key:
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
    return response


@router.patch("/variants/{variant_id}", response_model=VariantRead)
async def update_variant(
    variant_id: UUID,
    payload: VariantUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> VariantRead:
    require_pricing_unlock(x_pricing_token, tenant)
    variant = await _scoped_variant(
        session,
        company_id=tenant.company_id,
        variant_id=variant_id,
        for_update=True,
    )
    for field in ("name", "price_delta_minor", "sort_order", "is_active"):
        value = getattr(payload, field)
        if value is not None:
            setattr(variant, field, value)
    await _flush_menu_configuration(
        session,
        conflict_message=f"a variant named '{variant.name}' already exists for this item",
    )
    return variant_read(variant)


@router.delete("/variants/{variant_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_variant(
    variant_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> None:
    require_pricing_unlock(x_pricing_token, tenant)
    variant = await _scoped_variant(
        session,
        company_id=tenant.company_id,
        variant_id=variant_id,
        for_update=True,
    )
    variant.is_active = False
    await session.flush()


# ------------------------------------------------------------- MODIFIER GROUPS
@router.post(
    "/items/{item_id}/modifier-groups",
    response_model=ModifierGroupRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_modifier_group(
    item_id: UUID,
    payload: ModifierGroupCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ModifierGroupRead:
    require_pricing_unlock(x_pricing_token, tenant)
    if payload.is_active and payload.min_select > 0:
        raise BusinessRuleError(
            "create a required modifier group as inactive, add its options, then activate it"
        )
    idempotency_key, request_hash = _optional_idempotency(request)
    if idempotency_key:
        assert request_hash is not None
        existing_response = await check_or_reserve(
            session,
            key=idempotency_key,
            request_hash=request_hash,
            user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return ModifierGroupRead.model_validate(existing_response["body"])

    await _scoped_item(session, company_id=tenant.company_id, item_id=item_id)
    group = MenuModifierGroup(
        id=uuid4(),
        company_id=tenant.company_id,
        menu_item_id=item_id,
        name=payload.name,
        min_select=payload.min_select,
        max_select=payload.max_select,
        sort_order=payload.sort_order,
        is_active=payload.is_active,
    )
    session.add(group)
    await _flush_menu_configuration(
        session,
        conflict_message=f"a modifier group named '{payload.name}' already exists for this item",
    )
    response = modifier_group_read(group)
    if idempotency_key:
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
    return response


@router.patch("/modifier-groups/{group_id}", response_model=ModifierGroupRead)
async def update_modifier_group(
    group_id: UUID,
    payload: ModifierGroupUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ModifierGroupRead:
    require_pricing_unlock(x_pricing_token, tenant)
    group = await _scoped_modifier_group(
        session,
        company_id=tenant.company_id,
        group_id=group_id,
        for_update=True,
    )
    final_min = payload.min_select if payload.min_select is not None else group.min_select
    final_max = payload.max_select if payload.max_select is not None else group.max_select
    final_active = payload.is_active if payload.is_active is not None else group.is_active
    if final_min > final_max:
        raise BusinessRuleError("min_select must not exceed max_select")
    if final_active and final_min > 0:
        capacity = await _active_modifier_capacity(session, group_id=group.id)
        if capacity < final_min:
            raise BusinessRuleError(
                "active modifier options cannot satisfy this group's minimum selection"
            )

    for field in ("name", "min_select", "max_select", "sort_order", "is_active"):
        value = getattr(payload, field)
        if value is not None:
            setattr(group, field, value)
    await _flush_menu_configuration(
        session,
        conflict_message=f"a modifier group named '{group.name}' already exists for this item",
    )
    options = (
        await session.execute(
            select(MenuModifier)
            .where(
                MenuModifier.company_id == tenant.company_id,
                MenuModifier.modifier_group_id == group.id,
            )
            .order_by(MenuModifier.sort_order, MenuModifier.name)
        )
    ).scalars().all()
    return modifier_group_read(group, list(options))


@router.delete(
    "/modifier-groups/{group_id}",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def delete_modifier_group(
    group_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> None:
    require_pricing_unlock(x_pricing_token, tenant)
    group = await _scoped_modifier_group(
        session,
        company_id=tenant.company_id,
        group_id=group_id,
        for_update=True,
    )
    group.is_active = False
    await session.flush()


# ------------------------------------------------------------ MODIFIER OPTIONS
@router.post(
    "/modifier-groups/{group_id}/options",
    response_model=ModifierOptionRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_modifier_option(
    group_id: UUID,
    payload: ModifierOptionCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ModifierOptionRead:
    require_pricing_unlock(x_pricing_token, tenant)
    idempotency_key, request_hash = _optional_idempotency(request)
    if idempotency_key:
        assert request_hash is not None
        existing_response = await check_or_reserve(
            session,
            key=idempotency_key,
            request_hash=request_hash,
            user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return ModifierOptionRead.model_validate(existing_response["body"])

    group = await _scoped_modifier_group(
        session,
        company_id=tenant.company_id,
        group_id=group_id,
        for_update=True,
    )
    option = MenuModifier(
        id=uuid4(),
        company_id=tenant.company_id,
        menu_item_id=group.menu_item_id,
        modifier_group_id=group.id,
        name=payload.name,
        price_delta_minor=payload.price_delta_minor,
        max_quantity=payload.max_quantity,
        sort_order=payload.sort_order,
        is_active=payload.is_active,
    )
    session.add(option)
    await _flush_menu_configuration(
        session,
        conflict_message=f"a modifier option named '{payload.name}' already exists in this group",
    )
    response = modifier_option_read(option)
    if idempotency_key:
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
    return response


@router.patch("/modifiers/{modifier_id}", response_model=ModifierOptionRead)
async def update_modifier_option(
    modifier_id: UUID,
    payload: ModifierOptionUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> ModifierOptionRead:
    require_pricing_unlock(x_pricing_token, tenant)
    option = await _scoped_modifier(
        session,
        company_id=tenant.company_id,
        modifier_id=modifier_id,
        for_update=True,
    )
    group = await _scoped_modifier_group(
        session,
        company_id=tenant.company_id,
        group_id=option.modifier_group_id,
        for_update=True,
    )
    final_active = payload.is_active if payload.is_active is not None else option.is_active
    final_max_quantity = (
        payload.max_quantity if payload.max_quantity is not None else option.max_quantity
    )
    if group.is_active and group.min_select > 0 and (
        not final_active or final_max_quantity < option.max_quantity
    ):
        capacity_without_option = await _active_modifier_capacity(
            session,
            group_id=group.id,
            excluding_modifier_id=option.id,
        )
        final_capacity = capacity_without_option + (final_max_quantity if final_active else 0)
        if final_capacity < group.min_select:
            raise BusinessRuleError(
                "this change would leave the active group unable to satisfy its minimum"
            )

    for field in (
        "name",
        "price_delta_minor",
        "max_quantity",
        "sort_order",
        "is_active",
    ):
        value = getattr(payload, field)
        if value is not None:
            setattr(option, field, value)
    await _flush_menu_configuration(
        session,
        conflict_message=f"a modifier option named '{option.name}' already exists in this group",
    )
    return modifier_option_read(option)


@router.delete("/modifiers/{modifier_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_modifier_option(
    modifier_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> None:
    await update_modifier_option(
        modifier_id,
        ModifierOptionUpdate(is_active=False),
        session,
        tenant,
        x_pricing_token,
    )
