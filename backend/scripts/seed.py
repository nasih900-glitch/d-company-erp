"""Seed script — creates a default company, branch, terminal, owner user,
roles, chart of accounts, and a minimal menu so the system is ready
to take its first order.

Run: `python -m scripts.seed` (from the backend/ directory)
"""

from __future__ import annotations

import asyncio
import os
from datetime import UTC, datetime
from uuid import uuid4

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password
from app.models import (
    Account,
    Branch,
    CapitalEntry,
    Company,
    ExpenseCategory,
    Ingredient,
    MembershipTier,
    MenuCategory,
    MenuItem,
    Partner,
    Recipe,
    RecipeLine,
    Role,
    Station,
    Terminal,
    User,
    UserRole,
)

DEFAULT_ACCOUNTS: list[dict] = [
    # (code, name, type, normal_side)
    {"code": "1000", "name": "Cash", "type": "asset", "normal_side": "dr"},
    {"code": "1010", "name": "Bank", "type": "asset", "normal_side": "dr"},
    {"code": "1100", "name": "Card Clearing", "type": "asset", "normal_side": "dr"},
    {"code": "1110", "name": "UPI Clearing", "type": "asset", "normal_side": "dr"},
    {"code": "1200", "name": "Inventory", "type": "asset", "normal_side": "dr"},
    {"code": "1500", "name": "Fixed Assets", "type": "asset", "normal_side": "dr"},
    {"code": "2000", "name": "Accounts Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2100", "name": "Tax Payable", "type": "liability", "normal_side": "cr"},
    {"code": "3000", "name": "Partner Capital", "type": "equity", "normal_side": "cr"},
    {"code": "4000", "name": "Revenue — Food", "type": "revenue", "normal_side": "cr"},
    {"code": "4010", "name": "Revenue — Coffee", "type": "revenue", "normal_side": "cr"},
    {"code": "4020", "name": "Revenue — Desserts", "type": "revenue", "normal_side": "cr"},
    {"code": "4100", "name": "Revenue — Gaming", "type": "revenue", "normal_side": "cr"},
    {"code": "4150", "name": "Revenue — Hookah", "type": "revenue", "normal_side": "cr"},
    {"code": "4200", "name": "Revenue — Events", "type": "revenue", "normal_side": "cr"},
    {"code": "5000", "name": "COGS", "type": "expense", "normal_side": "dr"},
    {"code": "5100", "name": "Wages", "type": "expense", "normal_side": "dr"},
    {"code": "5200", "name": "Rent", "type": "expense", "normal_side": "dr"},
    {"code": "5300", "name": "Utilities", "type": "expense", "normal_side": "dr"},
    {"code": "5400", "name": "Marketing", "type": "expense", "normal_side": "dr"},
    {"code": "5500", "name": "Repairs & Maintenance", "type": "expense", "normal_side": "dr"},
    {"code": "5900", "name": "Other Expenses", "type": "expense", "normal_side": "dr"},
]

DEFAULT_ROLES: list[tuple[str, str]] = [
    ("super_owner", "Super owner — protected full access"),
    ("owner", "Owner — business owner access"),
    ("partner", "Partner — finance read, capital write"),
    ("manager", "Manager — branch operations"),
    ("cashier", "Cashier — POS"),
    ("kitchen", "Kitchen display"),
    ("gaming_supervisor", "Gaming supervisor"),
    ("auditor", "Read-only auditor"),
]


def _seed_owner_password() -> str:
    configured = (
        os.getenv("SEED_OWNER_PASSWORD")
        or os.getenv("OWNER_PASSWORD")
        or os.getenv("BOOTSTRAP_OWNER_PASSWORD")
    )
    if configured:
        return configured
    if os.getenv("ENV", "").lower() == "prod":
        raise RuntimeError("SEED_OWNER_PASSWORD must be set for production bootstrap")
    return "local-dev-only-password"


async def seed() -> None:
    async with AsyncSessionLocal() as s:
        existing = (await s.execute(select(Company).limit(1))).scalar_one_or_none()
        if existing:
            print(f"company already exists: {existing.name} ({existing.id}); skipping.")
            return

        company = Company(
            id=uuid4(),
            name="D Company",
            legal_name="D Company Cafés & Gaming",
            currency="INR",
            timezone="Asia/Kolkata",
            country="IN",
        )
        s.add(company)
        # Flush so the company row is INSERTed in the DB before any rows
        # that reference its id (branch, roles, users, accounts, etc.)
        # SQLAlchemy 2.0 async batching doesn't always pick the right
        # INSERT order across many add() calls in one session.
        await s.flush()

        branch = Branch(
            id=uuid4(),
            company_id=company.id,
            name="Main Branch",
            timezone="Asia/Kolkata",
            state_code="32",
        )
        s.add(branch)
        await s.flush()

        terminal = Terminal(
            id=uuid4(),
            branch_id=branch.id,
            name="POS-01",
            device_id="seed-terminal-1",
        )
        s.add(terminal)
        await s.flush()

        # Roles
        roles_by_code: dict[str, Role] = {}
        for code, desc in DEFAULT_ROLES:
            r = Role(
                id=uuid4(),
                company_id=company.id,
                code=code,
                name=code.title().replace("_", " "),
                description=desc,
                permissions=[],
            )
            s.add(r)
            roles_by_code[code] = r

        # Flush roles so user_role can reference them
        await s.flush()

        # Owner user
        owner = User(
            id=uuid4(),
            company_id=company.id,
            email="owner@dcompany.local",
            password_hash=hash_password(_seed_owner_password()),
            name="Owner",
            status="active",
        )
        s.add(owner)
        await s.flush()
        s.add(UserRole(id=uuid4(), user_id=owner.id, role_id=roles_by_code["owner"].id))

        # Chart of accounts
        for a in DEFAULT_ACCOUNTS:
            s.add(Account(id=uuid4(), company_id=company.id, **a))
        await s.flush()

        # Menu skeleton
        coffee = MenuCategory(id=uuid4(), company_id=company.id, name="Coffee", sort_order=10)
        gaming = MenuCategory(id=uuid4(), company_id=company.id, name="Gaming", sort_order=90)
        s.add_all([coffee, gaming])
        await s.flush()  # flush categories so menu items can reference category_id

        cappuccino = MenuItem(
            id=uuid4(),
            company_id=company.id,
            category_id=coffee.id,
            sku="COF-CAP",
            name="Cappuccino",
            type="drink",
            base_price_minor=18000,  # ₹180.00
            tax_rate=0.05,
        )
        s.add(cappuccino)

        # Ingredients + recipe
        milk = Ingredient(
            id=uuid4(), company_id=company.id, sku="ING-MILK",
            name="Milk", base_unit="ml", reorder_threshold=1000, current_qty=0,
        )
        beans = Ingredient(
            id=uuid4(), company_id=company.id, sku="ING-BEAN",
            name="Coffee Beans", base_unit="g", reorder_threshold=500, current_qty=0,
        )
        sugar = Ingredient(
            id=uuid4(), company_id=company.id, sku="ING-SUG",
            name="Sugar", base_unit="g", reorder_threshold=200, current_qty=0,
        )
        s.add_all([milk, beans, sugar])
        await s.flush()  # flush menu_items + ingredients so recipes/recipe_lines can reference them

        recipe = Recipe(id=uuid4(), menu_item_id=cappuccino.id, name="Cappuccino v1", yield_qty=1)
        s.add(recipe)
        await s.flush()  # flush recipe so recipe_lines can reference recipe_id
        s.add_all([
            RecipeLine(id=uuid4(), recipe_id=recipe.id, ingredient_id=milk.id, qty=150),
            RecipeLine(id=uuid4(), recipe_id=recipe.id, ingredient_id=beans.id, qty=18),
            RecipeLine(id=uuid4(), recipe_id=recipe.id, ingredient_id=sugar.id, qty=1),
        ])
        await s.flush()

        # PS5 stations
        for n in range(1, 5):
            s.add(Station(
                id=uuid4(),
                company_id=company.id,
                branch_id=branch.id,
                code=f"PS5-{n:02d}",
                name=f"PS5 Station {n}",
                type="ps5",
                rate_per_hour_minor=20000,  # ₹200/hr
            ))

        # ============================================================
        # REAL D COMPANY DATA — partners + expense categories
        # ============================================================
        # The three partners with their actual capital invested to-date
        # (from the Partner Investments tab in your Google Sheet,
        #  pre-opening construction phase ~Nov 2025 → Jun 2026).
        # Idempotent: only seeded if no partner with the same name exists.
        existing_partners = (
            await s.execute(
                select(Partner).where(Partner.company_id == company.id)
            )
        ).scalars().all()
        if not existing_partners:
            partner_seed = [
                # (name, share_pct, joined_at, opening_capital_minor)
                ("Nasih",   29.27, datetime(2025, 12,  6, tzinfo=UTC), 56_298_490),
                ("Shemeer", 33.79, datetime(2025, 12,  5, tzinfo=UTC), 64_994_000),
                ("Rafi",    36.94, datetime(2025, 11, 30, tzinfo=UTC), 71_044_100),
            ]
            for name, pct, joined, capital in partner_seed:
                p = Partner(
                    id=uuid4(),
                    company_id=company.id,
                    name=name,
                    share_pct=pct,
                    joined_at=joined,
                    notes="Opening capital balance imported from Partner Investments sheet",
                )
                s.add(p)
                await s.flush()
                s.add(CapitalEntry(
                    id=uuid4(),
                    partner_id=p.id,
                    type="invest",
                    amount_minor=capital,
                    effective_at=joined,
                    note=f"Opening balance ({name})",
                ))

        # Their real expense categories (from Lists tab — spelling preserved)
        existing_cats = (
            await s.execute(
                select(ExpenseCategory).where(ExpenseCategory.company_id == company.id)
            )
        ).scalars().all()
        if not existing_cats:
            real_categories = [
                "Furnitures",            # sic — matches the user's spelling
                "Design & Architecture",
                "Utilities",
                "Labour Charges",
                "Building Materials",
                "Rent",
                "PaperWork Charges",
                "Food & Water",
                "Gaming Equipments",
                "Kitchen Equipments",
                "Marketing",
                "Transportation Cost",
                "Wages",
                "Cafe Equipments",
                "Others",
            ]
            for name in real_categories:
                s.add(ExpenseCategory(
                    id=uuid4(),
                    company_id=company.id,
                    name=name,
                ))

        # ============================================================
        # MEMBERSHIP TIERS — D Club Silver / Gold / Platinum
        # ============================================================
        existing_tiers = (
            await s.execute(
                select(MembershipTier).where(MembershipTier.company_id == company.id)
            )
        ).scalars().all()
        if not existing_tiers:
            tier_seed = [
                # (code, name, monthly_paise, annual_paise, food%, gaming%, hookah%, point_mult,
                #  free_gaming_min/wk, free_hookah/mo, priority, desc)
                ("silver", "D Club Silver", 99_900, 999_000,
                 0.10, 0.00, 0.00, 1.25, 0, 0, False,
                 "10% off food · 1.25× loyalty points · members-only Instagram updates"),
                ("gold", "D Club Gold", 199_900, 1_999_000,
                 0.15, 0.20, 0.10, 1.50, 240, 1, True,
                 "15% off food · 20% off gaming · 10% off hookah · "
                 "4hr free PS5/week · 1 free hookah/month · priority booking · "
                 "1.5× points"),
                ("platinum", "D Club Platinum", 399_900, 3_999_000,
                 0.25, 0.50, 0.25, 2.00, 600, 4, True,
                 "25% off food · 50% off gaming · 25% off hookah · "
                 "10hr free PS5/week · 4 free hookahs/month · highest priority · "
                 "2× points · free birthday treat"),
            ]
            for idx, (code, name, monthly, annual, food, gaming, hookah, pt_mult,
                     gm_wk, hk_mo, prio, desc) in enumerate(tier_seed):
                s.add(MembershipTier(
                    id=uuid4(),
                    company_id=company.id,
                    code=code, name=name,
                    monthly_price_minor=monthly,
                    annual_price_minor=annual,
                    food_discount_pct=food,
                    gaming_discount_pct=gaming,
                    hookah_discount_pct=hookah,
                    point_multiplier=pt_mult,
                    free_gaming_minutes_per_week=gm_wk,
                    free_hookah_per_month=hk_mo,
                    priority_booking=prio,
                    description=desc,
                    sort_order=idx,
                ))

        await s.commit()

        print(f"seeded company {company.name} ({company.id})")
        print(f"  branch: {branch.id}")
        print(f"  terminal: {terminal.id}")
        print("  owner login: created from seed defaults; rotate before production use")
        if not existing_partners:
            print("  ✓ seeded 3 partners (Nasih, Shemeer, Rafi) with opening capital")
        if not existing_cats:
            print("  ✓ seeded 15 expense categories (Furnitures, Building Materials, …)")
        if not existing_tiers:
            print("  ✓ seeded 3 membership tiers (D Club Silver / Gold / Platinum)")


if __name__ == "__main__":
    asyncio.run(seed())
