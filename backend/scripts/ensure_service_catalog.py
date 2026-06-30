"""Ensure service POS items and stations exist for an existing D Company tenant.

Run from the backend folder after deploying code:
    python -m scripts.ensure_service_catalog
"""

from __future__ import annotations

import asyncio
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Account, Branch, Company, MenuCategory, MenuItem, Station


CATEGORY_SEED = [
    {"name": "Gaming", "sort_order": 90},
    {"name": "Shisha", "sort_order": 95},
    {"name": "Streaming", "sort_order": 96},
]

MENU_SEED = [
    {
        "category": "Gaming",
        "sku": "GAM-PS5-15",
        "name": "PS5 Session - 15 min",
        "description": "PlayStation 5 session block",
        "type": "gaming",
        "base_price_minor": 5000,
        "tax_rate": Decimal("0.18"),
        "hsn_code": "999692",
    },
    {
        "category": "Gaming",
        "sku": "GAM-VR-15",
        "name": "VR Session - 15 min",
        "description": "VR pod session block",
        "type": "gaming",
        "base_price_minor": 8750,
        "tax_rate": Decimal("0.18"),
        "hsn_code": "999692",
    },
    {
        "category": "Gaming",
        "sku": "GAM-SIM-15",
        "name": "Simulator Session - 15 min",
        "description": "Racing simulator session block",
        "type": "gaming",
        "base_price_minor": 10000,
        "tax_rate": Decimal("0.18"),
        "hsn_code": "999692",
    },
    {
        "category": "Shisha",
        "sku": "SHI-SESSION",
        "name": "Shisha Session",
        "description": "Shisha lounge session",
        "type": "hookah",
        "base_price_minor": 35000,
        "tax_rate": Decimal("0.18"),
        "hsn_code": "999692",
    },
    {
        "category": "Streaming",
        "sku": "STR-BOOTH-15",
        "name": "Streaming Booth - 15 min",
        "description": "Streaming booth session block",
        "type": "streaming",
        "base_price_minor": 5000,
        "tax_rate": Decimal("0.18"),
        "hsn_code": "999692",
    },
]

STATION_SEED = [
    {"code": "PS5-01", "name": "PS5 Station 1", "type": "ps5", "rate_per_hour_minor": 20000},
    {"code": "PS5-02", "name": "PS5 Station 2", "type": "ps5", "rate_per_hour_minor": 20000},
    {"code": "PS5-03", "name": "PS5 Station 3", "type": "ps5", "rate_per_hour_minor": 20000},
    {"code": "PS5-04", "name": "PS5 Station 4", "type": "ps5", "rate_per_hour_minor": 20000},
    {"code": "VR-01", "name": "VR Pod 1", "type": "vr", "rate_per_hour_minor": 35000},
    {"code": "SIM-01", "name": "Racing Simulator 1", "type": "simulator", "rate_per_hour_minor": 40000},
    {"code": "SH-01", "name": "Shisha Table 1", "type": "hookah", "rate_per_hour_minor": 35000},
    {"code": "STR-01", "name": "Streaming Booth 1", "type": "streaming", "rate_per_hour_minor": 20000},
]


def _changed(obj: object, field: str, value: object) -> bool:
    if getattr(obj, field) == value:
        return False
    setattr(obj, field, value)
    return True


async def main() -> None:
    async with AsyncSessionLocal() as session:
        company = (await session.execute(
            select(Company).where(Company.deleted_at.is_(None)).order_by(Company.created_at)
        )).scalars().first()
        if company is None:
            raise SystemExit("No company found. Run the main seed first.")

        branch = (await session.execute(
            select(Branch)
            .where(Branch.company_id == company.id, Branch.deleted_at.is_(None))
            .order_by(Branch.created_at)
        )).scalars().first()
        if branch is None:
            raise SystemExit(f"No active branch found for company {company.name}.")

        created = 0
        updated = 0

        account = (await session.execute(
            select(Account).where(Account.company_id == company.id, Account.code == "4160")
        )).scalars().first()
        if account is None:
            session.add(Account(
                id=uuid4(),
                company_id=company.id,
                code="4160",
                name="Revenue - Streaming",
                type="revenue",
                normal_side="cr",
            ))
            created += 1

        categories: dict[str, MenuCategory] = {}
        for row in CATEGORY_SEED:
            category = (await session.execute(
                select(MenuCategory).where(
                    MenuCategory.company_id == company.id,
                    MenuCategory.name == row["name"],
                    MenuCategory.deleted_at.is_(None),
                )
            )).scalars().first()
            if category is None:
                category = MenuCategory(
                    id=uuid4(),
                    company_id=company.id,
                    name=row["name"],
                    sort_order=row["sort_order"],
                )
                session.add(category)
                await session.flush()
                created += 1
            elif _changed(category, "sort_order", row["sort_order"]):
                updated += 1
            categories[row["name"]] = category

        for row in MENU_SEED:
            category = categories[row["category"]]
            item = (await session.execute(
                select(MenuItem).where(
                    MenuItem.company_id == company.id,
                    MenuItem.sku == row["sku"],
                    MenuItem.deleted_at.is_(None),
                )
            )).scalars().first()
            if item is None:
                session.add(MenuItem(
                    id=uuid4(),
                    company_id=company.id,
                    category_id=category.id,
                    sku=row["sku"],
                    name=row["name"],
                    description=row["description"],
                    type=row["type"],
                    base_price_minor=row["base_price_minor"],
                    tax_rate=row["tax_rate"],
                    hsn_code=row["hsn_code"],
                    price_includes_tax=True,
                    is_available=True,
                ))
                created += 1
                continue

            row_updated = False
            row_updated |= _changed(item, "category_id", category.id)
            row_updated |= _changed(item, "name", row["name"])
            row_updated |= _changed(item, "description", row["description"])
            row_updated |= _changed(item, "type", row["type"])
            row_updated |= _changed(item, "base_price_minor", row["base_price_minor"])
            row_updated |= _changed(item, "tax_rate", row["tax_rate"])
            row_updated |= _changed(item, "hsn_code", row["hsn_code"])
            row_updated |= _changed(item, "price_includes_tax", True)
            row_updated |= _changed(item, "is_available", True)
            if row_updated:
                updated += 1

        for row in STATION_SEED:
            station = (await session.execute(
                select(Station).where(
                    Station.company_id == company.id,
                    Station.branch_id == branch.id,
                    Station.code == row["code"],
                )
            )).scalars().first()
            if station is None:
                session.add(Station(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    code=row["code"],
                    name=row["name"],
                    type=row["type"],
                    rate_per_hour_minor=row["rate_per_hour_minor"],
                    is_active=True,
                    sac_code="999692",
                    tax_rate=Decimal("0.18"),
                    rate_includes_tax=True,
                ))
                created += 1
                continue

            row_updated = False
            row_updated |= _changed(station, "name", row["name"])
            row_updated |= _changed(station, "type", row["type"])
            row_updated |= _changed(station, "rate_per_hour_minor", row["rate_per_hour_minor"])
            row_updated |= _changed(station, "is_active", True)
            row_updated |= _changed(station, "sac_code", "999692")
            row_updated |= _changed(station, "tax_rate", Decimal("0.18"))
            row_updated |= _changed(station, "rate_includes_tax", True)
            if row_updated:
                updated += 1

        await session.commit()
        print(f"Service catalog ready for {company.name}: created={created}, updated={updated}")


if __name__ == "__main__":
    asyncio.run(main())
