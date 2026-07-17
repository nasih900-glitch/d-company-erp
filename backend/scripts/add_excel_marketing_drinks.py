"""Register Excel Marketing as a supplier and its 11 packaged drinks as
sellable, stock-tracked menu items (invoice G2627-06142, 11-07-2026).

Costs are the real per-piece rates printed on the invoice. Sell prices are
the printed MRP, confirmed by the owner as D Company's actual selling price
for these items. No stock quantity is set here — there is currently no way
to create a Recipe through the live app (only this kind of one-off script,
or the original seed, ever writes one), so this script only builds the
catalog: supplier, ingredient (stock unit), sellable menu item, and the
1:1 recipe linking them. Post the actual receipt quantities separately via
GRN (Inventory tab, or the receipt-photo upload) once you're ready — this
deliberately does not guess at how many of each you actually received.

Usage:
    python -m scripts.add_excel_marketing_drinks

Idempotent: re-running updates existing rows (by SKU / supplier name)
instead of creating duplicates.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from uuid import uuid4

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Company, Ingredient, MenuCategory, MenuItem, Recipe, RecipeLine, Supplier

SUPPLIER_NAME = "Excel Marketing"
SUPPLIER_CONTACT = "Haris Babu · 04931 230966 / 9562431702"
SUPPLIER_GSTIN = "32AUINPR3047A1Z1"

CATEGORY_NAME = "Soft Drinks"

# Drink type's standard rate in the existing menu (matches Coffee's 0.05) —
# inert while the company is GST-unregistered, but keeps the chart-of-tax
# expectations consistent if/when registration status changes.
DRINK_TAX_RATE = 0.05


@dataclass(frozen=True, slots=True)
class DrinkItem:
    sku_suffix: str
    name: str
    cost_minor: int  # real per-piece rate from the invoice
    sell_minor: int  # printed MRP, confirmed as the actual sell price


DRINKS = (
    DrinkItem("7UPCAN", "7Up 300ml Can", 3429, 4000),
    DrinkItem("PEPCAN", "Pepsi 300ml Can", 3429, 4000),
    DrinkItem("RUSHBLK", "Rush Black 300ml Can", 4800, 6000),
    DrinkItem("RUSHYLW", "Rush Yellow 300ml Can", 4800, 6000),
    DrinkItem("DEW250", "Mountain Dew 250ml", 1750, 2000),
    DrinkItem("7UP250", "7Up 250ml", 1750, 2000),
    DrinkItem("PEP250", "Pepsi 250ml", 1750, 2000),
    DrinkItem("MIRINDA", "Mirinda 400ml", 1750, 2000),
    DrinkItem("STINGRED", "Sting Red 250ml", 1750, 2000),
    DrinkItem("STINGCLS", "Sting Classic 300ml", 1750, 2000),
    DrinkItem("AQUA1L", "Aquafina 1L", 1400, 2000),
)


async def run() -> None:
    async with AsyncSessionLocal() as s:
        company = (await s.execute(select(Company).limit(1))).scalar_one_or_none()
        if not company:
            raise SystemExit("No company found — run `python -m scripts.seed` first.")

        supplier = (
            await s.execute(
                select(Supplier).where(
                    Supplier.company_id == company.id, Supplier.name == SUPPLIER_NAME
                )
            )
        ).scalar_one_or_none()
        if supplier:
            supplier.contact = SUPPLIER_CONTACT
            supplier.gstin = SUPPLIER_GSTIN
            print(f"Supplier {SUPPLIER_NAME!r} exists — updated contact/GSTIN.")
        else:
            supplier = Supplier(
                id=uuid4(),
                company_id=company.id,
                name=SUPPLIER_NAME,
                contact=SUPPLIER_CONTACT,
                gstin=SUPPLIER_GSTIN,
            )
            s.add(supplier)
            print(f"Created supplier {SUPPLIER_NAME!r}.")

        category = (
            await s.execute(
                select(MenuCategory).where(
                    MenuCategory.company_id == company.id, MenuCategory.name == CATEGORY_NAME
                )
            )
        ).scalar_one_or_none()
        if not category:
            category = MenuCategory(id=uuid4(), company_id=company.id, name=CATEGORY_NAME, sort_order=50)
            s.add(category)
            print(f"Created menu category {CATEGORY_NAME!r}.")
        await s.flush()

        for drink in DRINKS:
            ing_sku = f"ING-{drink.sku_suffix}"
            item_sku = f"DRK-{drink.sku_suffix}"

            ingredient = (
                await s.execute(
                    select(Ingredient).where(
                        Ingredient.company_id == company.id, Ingredient.sku == ing_sku
                    )
                )
            ).scalar_one_or_none()
            if ingredient:
                ingredient.avg_cost_minor = drink.cost_minor
                ingredient.name = drink.name
            else:
                ingredient = Ingredient(
                    id=uuid4(),
                    company_id=company.id,
                    sku=ing_sku,
                    name=drink.name,
                    base_unit="unit",
                    reorder_threshold=12,
                    reorder_qty=24,
                    avg_cost_minor=drink.cost_minor,
                    current_qty=0,
                )
                s.add(ingredient)
            await s.flush()

            item = (
                await s.execute(
                    select(MenuItem).where(
                        MenuItem.company_id == company.id, MenuItem.sku == item_sku
                    )
                )
            ).scalar_one_or_none()
            if item:
                item.base_price_minor = drink.sell_minor
                item.name = drink.name
            else:
                item = MenuItem(
                    id=uuid4(),
                    company_id=company.id,
                    category_id=category.id,
                    sku=item_sku,
                    name=drink.name,
                    type="drink",
                    base_price_minor=drink.sell_minor,
                    tax_rate=DRINK_TAX_RATE,
                    price_includes_tax=True,
                    hsn_code="22021010",
                )
                s.add(item)
            await s.flush()

            recipe = (
                await s.execute(select(Recipe).where(Recipe.menu_item_id == item.id))
            ).scalar_one_or_none()
            if not recipe:
                recipe = Recipe(id=uuid4(), menu_item_id=item.id, name=f"{drink.name} (packaged)", yield_qty=1, version=1)
                s.add(recipe)
                await s.flush()
                s.add(RecipeLine(id=uuid4(), recipe_id=recipe.id, ingredient_id=ingredient.id, qty=1))

            print(f"  • {drink.name}: cost {drink.cost_minor/100:.2f}, sell {drink.sell_minor/100:.2f}")

        await s.commit()
        print(f"\nDone — {len(DRINKS)} drinks ready under {CATEGORY_NAME!r}.")
        print("Stock is 0 until you post a real GRN with actual received quantities.")


def main() -> None:
    asyncio.run(run())


if __name__ == "__main__":
    main()
