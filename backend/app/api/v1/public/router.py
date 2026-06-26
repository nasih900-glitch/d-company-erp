"""Public (unauthenticated) endpoints.

These power the public-facing pages — QR-coded menu at the table,
event detail pages, future bookings flow. Read-only and scoped to a
single hard-coded company because the URL is the only identifier.

If you ever run multi-company, swap the company lookup to use a path
prefix like /public/{company_slug}/menu.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter
from pydantic import BaseModel
from sqlalchemy import select

from app.core.db import SessionDep
from app.models import Company, MenuCategory, MenuItem

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class PublicItemDTO(BaseModel):
    id: UUID
    sku: str
    name: str
    type: str
    base_price_minor: int
    tax_rate: float
    description: str | None
    category_id: UUID
    category_name: str
    category_sort: int


class PublicMenuDTO(BaseModel):
    company_name: str
    company_gstin: str | None
    categories: list[dict]
    items: list[PublicItemDTO]


# ---------------------------------------------------------------- endpoints
@router.get("/menu", response_model=PublicMenuDTO)
async def public_menu(session: SessionDep) -> PublicMenuDTO:
    """Public read-only menu — what QR-at-the-table customers see.

    Filters to one company (the only one in this deployment). Hides
    items with is_available=False and items in deleted categories.
    """
    company = (
        await session.execute(
            select(Company).where(Company.deleted_at.is_(None)).limit(1)
        )
    ).scalar_one_or_none()
    if not company:
        return PublicMenuDTO(company_name="D Company", company_gstin=None,
                             categories=[], items=[])

    cats = (
        await session.execute(
            select(MenuCategory)
            .where(
                MenuCategory.company_id == company.id,
                MenuCategory.deleted_at.is_(None),
            )
            .order_by(MenuCategory.sort_order)
        )
    ).scalars().all()
    cat_meta = {c.id: (c.name, c.sort_order) for c in cats}

    items = (
        await session.execute(
            select(MenuItem)
            .where(
                MenuItem.company_id == company.id,
                MenuItem.deleted_at.is_(None),
                MenuItem.is_available.is_(True),
            )
            .order_by(MenuItem.name)
        )
    ).scalars().all()

    out_items: list[PublicItemDTO] = []
    for it in items:
        cm = cat_meta.get(it.category_id)
        if not cm:
            continue
        out_items.append(
            PublicItemDTO(
                id=it.id, sku=it.sku, name=it.name, type=it.type,
                base_price_minor=it.base_price_minor,
                tax_rate=float(it.tax_rate),
                description=it.description,
                category_id=it.category_id,
                category_name=cm[0],
                category_sort=cm[1],
            )
        )

    return PublicMenuDTO(
        company_name=company.name,
        company_gstin=company.gstin,
        categories=[
            {"id": str(c.id), "name": c.name, "sort_order": c.sort_order}
            for c in cats
        ],
        items=out_items,
    )
