"""Stable Gaming Centre catalogue classification API regressions."""

from __future__ import annotations

from uuid import UUID, uuid4

import pytest

from app.api.v1.menu import router as menu_router
from app.core.tenant import TenantContext
from app.models import MenuCategory
from scripts.add_excel_marketing_drinks import ensure_gaming_centre_catalog


class _Session:
    def __init__(self, category: MenuCategory) -> None:
        self.category = category
        self.flush_count = 0

    async def get(self, _model, category_id):
        return self.category if category_id == self.category.id else None

    async def flush(self) -> None:
        self.flush_count += 1


def _tenant(company_id: UUID) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=company_id,
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
    )


@pytest.mark.asyncio
async def test_renaming_category_preserves_gaming_centre_visibility() -> None:
    company_id = uuid4()
    category = MenuCategory(
        id=uuid4(),
        company_id=company_id,
        name="Soft Drinks",
        sort_order=10,
        is_gaming_centre_catalog=True,
    )
    session = _Session(category)

    response = await menu_router.update_category(
        category.id,
        menu_router.CategoryUpdate(name="Cold cabinet"),
        session,
        _tenant(company_id),
    )

    assert response.name == "Cold cabinet"
    assert response.is_gaming_centre_catalog is True
    assert category.is_gaming_centre_catalog is True
    assert session.flush_count == 1


@pytest.mark.asyncio
async def test_owner_can_change_category_gaming_centre_visibility_explicitly() -> None:
    company_id = uuid4()
    category = MenuCategory(
        id=uuid4(),
        company_id=company_id,
        name="Future cafe menu",
        sort_order=20,
        is_gaming_centre_catalog=False,
    )
    session = _Session(category)

    response = await menu_router.update_category(
        category.id,
        menu_router.CategoryUpdate(is_gaming_centre_catalog=True),
        session,
        _tenant(company_id),
    )

    assert response.is_gaming_centre_catalog is True
    assert category.name == "Future cafe menu"
    assert session.flush_count == 1


def test_new_categories_fail_closed_unless_owner_classifies_them() -> None:
    assert menu_router.CategoryCreate(name="Cafe meals").is_gaming_centre_catalog is False
    assert menu_router.CategoryCreate(
        name="Packaged counter items",
        is_gaming_centre_catalog=True,
    ).is_gaming_centre_catalog is True


def test_drinks_catalog_rerun_repairs_only_the_selected_category_flag() -> None:
    company_id = uuid4()
    drinks = MenuCategory(
        id=uuid4(),
        company_id=company_id,
        name="Soft Drinks",
        sort_order=50,
        is_gaming_centre_catalog=False,
    )
    unrelated = MenuCategory(
        id=uuid4(),
        company_id=company_id,
        name="Future cafe menu",
        sort_order=60,
        is_gaming_centre_catalog=False,
    )

    assert ensure_gaming_centre_catalog(drinks) is True
    assert drinks.is_gaming_centre_catalog is True
    assert drinks.name == "Soft Drinks"
    assert unrelated.is_gaming_centre_catalog is False
    assert ensure_gaming_centre_catalog(drinks) is False
