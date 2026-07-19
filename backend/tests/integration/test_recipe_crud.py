"""DB-backed CRUD tests for the Recipe/RecipeLine (BOM) endpoints.

Recipe/RecipeLine could previously only be written by one-off seed scripts
(scripts/seed.py, scripts/add_excel_marketing_drinks.py) — this covers the
live API added in app/api/v1/inventory/router.py: create/list/update/delete
for Recipe, and add/update/delete for its RecipeLine children, plus tenant
isolation and the deletion-is-deactivation semantics deduction.py depends on.

The final test (test_recipe_created_via_api_is_consumed_by_deduction) is the
integration-style check the task asked for: a recipe built purely through
the HTTP API must be shaped exactly the way
app/services/inventory/deduction.py expects (is_active, qty, wastage_pct as
a fraction) — it reuses the same Ingredient/Batch construction helpers as
tests/unit/test_inventory_deduction.py, just against a real Postgres session
and a real order-payment-shaped call into deduct_for_order.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.models import (
    Batch,
    Company,
    Ingredient,
    MenuCategory,
    MenuItem,
    Recipe,
    RecipeLine,
    StockMovement,
)
from app.services.inventory.deduction import deduct_for_order


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _login(client, seed_owner) -> str:
    r = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


async def _menu_item(session, company_id) -> MenuItem:
    cat = MenuCategory(id=uuid4(), company_id=company_id, name=f"Cat-{uuid4().hex[:6]}")
    item = MenuItem(
        id=uuid4(),
        company_id=company_id,
        category_id=cat.id,
        sku=f"SKU-{uuid4().hex[:8]}",
        name="Cappuccino",
        type="drink",
        base_price_minor=15000,
        tax_rate=0.05,
    )
    session.add_all([cat, item])
    await session.flush()
    return item


async def _ingredient(session, company_id, *, name: str = "Milk") -> Ingredient:
    ing = Ingredient(
        id=uuid4(),
        company_id=company_id,
        sku=f"ING-{uuid4().hex[:8]}",
        name=name,
        base_unit="ml",
        current_qty=1000.0,
    )
    session.add(ing)
    await session.flush()
    return ing


@pytest.mark.asyncio
async def test_create_recipe_creates_recipe_and_lines(client, session, seed_owner) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)
    milk = await _ingredient(session, company_id, name="Milk")
    beans = await _ingredient(session, company_id, name="Coffee Beans")
    await session.commit()

    token = await _login(client, seed_owner)
    r = await client.post(
        "/api/v1/inventory/recipes",
        json={
            "menu_item_id": str(item.id),
            "name": "Cappuccino v1",
            "yield_qty": 1,
            "lines": [
                {"ingredient_id": str(milk.id), "qty": 150, "wastage_pct": 0.05},
                {"ingredient_id": str(beans.id), "qty": 18, "wastage_pct": 0},
            ],
        },
        headers=_auth(token),
    )
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["menu_item_id"] == str(item.id)
    assert body["is_active"] is True
    assert body["version"] == 1
    assert len(body["lines"]) == 2
    line_qtys = sorted(ln["qty"] for ln in body["lines"])
    assert line_qtys == [18, 150]


@pytest.mark.asyncio
async def test_create_recipe_rejects_ingredient_from_another_company(client, session, seed_owner) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)

    other_company = Company(id=uuid4(), name="OtherCo")
    session.add(other_company)
    await session.flush()
    foreign_ingredient = await _ingredient(session, other_company.id, name="Foreign Milk")
    await session.commit()

    token = await _login(client, seed_owner)
    r = await client.post(
        "/api/v1/inventory/recipes",
        json={
            "menu_item_id": str(item.id),
            "name": "Cappuccino v1",
            "lines": [{"ingredient_id": str(foreign_ingredient.id), "qty": 150}],
        },
        headers=_auth(token),
    )
    assert r.status_code == 404, r.text


@pytest.mark.asyncio
async def test_create_recipe_conflicts_with_existing_active_recipe(client, session, seed_owner) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)
    milk = await _ingredient(session, company_id)
    await session.commit()

    token = await _login(client, seed_owner)
    body = {
        "menu_item_id": str(item.id),
        "name": "Cappuccino v1",
        "lines": [{"ingredient_id": str(milk.id), "qty": 150}],
    }
    r1 = await client.post("/api/v1/inventory/recipes", json=body, headers=_auth(token))
    assert r1.status_code == 201, r1.text

    r2 = await client.post("/api/v1/inventory/recipes", json=body, headers=_auth(token))
    assert r2.status_code == 409, r2.text


@pytest.mark.asyncio
async def test_list_recipes_by_menu_item(client, session, seed_owner) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)
    other_item = await _menu_item(session, company_id)
    milk = await _ingredient(session, company_id)
    await session.commit()

    token = await _login(client, seed_owner)
    await client.post(
        "/api/v1/inventory/recipes",
        json={"menu_item_id": str(item.id), "name": "R1", "lines": [{"ingredient_id": str(milk.id), "qty": 10}]},
        headers=_auth(token),
    )
    await client.post(
        "/api/v1/inventory/recipes",
        json={"menu_item_id": str(other_item.id), "name": "R2", "lines": []},
        headers=_auth(token),
    )

    r = await client.get(
        "/api/v1/inventory/recipes", params={"menu_item_id": str(item.id)}, headers=_auth(token),
    )
    assert r.status_code == 200, r.text
    rows = r.json()
    assert len(rows) == 1
    assert rows[0]["menu_item_id"] == str(item.id)
    assert len(rows[0]["lines"]) == 1


@pytest.mark.asyncio
async def test_update_recipe_changes_name_and_yield(client, session, seed_owner) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)
    milk = await _ingredient(session, company_id)
    await session.commit()

    token = await _login(client, seed_owner)
    created = (await client.post(
        "/api/v1/inventory/recipes",
        json={"menu_item_id": str(item.id), "name": "Old name", "yield_qty": 1,
              "lines": [{"ingredient_id": str(milk.id), "qty": 10}]},
        headers=_auth(token),
    )).json()

    r = await client.patch(
        f"/api/v1/inventory/recipes/{created['id']}",
        json={"name": "New name", "yield_qty": 2},
        headers=_auth(token),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["name"] == "New name"
    assert body["yield_qty"] == 2
    assert len(body["lines"]) == 1  # untouched by a metadata-only update


@pytest.mark.asyncio
async def test_delete_recipe_deactivates_and_allows_recreation(client, session, seed_owner) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)
    milk = await _ingredient(session, company_id)
    await session.commit()

    token = await _login(client, seed_owner)
    created = (await client.post(
        "/api/v1/inventory/recipes",
        json={"menu_item_id": str(item.id), "name": "v1", "lines": [{"ingredient_id": str(milk.id), "qty": 10}]},
        headers=_auth(token),
    )).json()

    r = await client.delete(f"/api/v1/inventory/recipes/{created['id']}", headers=_auth(token))
    assert r.status_code == 204, r.text

    # The row survives (soft-delete-equivalent), just deactivated.
    listed = (await client.get(
        "/api/v1/inventory/recipes", params={"menu_item_id": str(item.id)}, headers=_auth(token),
    )).json()
    assert len(listed) == 1
    assert listed[0]["id"] == created["id"]
    assert listed[0]["is_active"] is False

    # A new recipe can now be created for the same item (no active one blocks it).
    recreated = await client.post(
        "/api/v1/inventory/recipes",
        json={"menu_item_id": str(item.id), "name": "v2", "lines": [{"ingredient_id": str(milk.id), "qty": 20}]},
        headers=_auth(token),
    )
    assert recreated.status_code == 201, recreated.text
    assert recreated.json()["version"] == 2
    assert recreated.json()["is_active"] is True

    # Editing the deactivated recipe is no longer allowed (mirrors the
    # deleted_at check every other resource in this router uses).
    edit_attempt = await client.patch(
        f"/api/v1/inventory/recipes/{created['id']}",
        json={"name": "should fail"},
        headers=_auth(token),
    )
    assert edit_attempt.status_code == 404


@pytest.mark.asyncio
async def test_recipe_line_add_update_delete(client, session, seed_owner) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)
    milk = await _ingredient(session, company_id, name="Milk")
    sugar = await _ingredient(session, company_id, name="Sugar")
    await session.commit()

    token = await _login(client, seed_owner)
    created = (await client.post(
        "/api/v1/inventory/recipes",
        json={"menu_item_id": str(item.id), "name": "v1", "lines": [{"ingredient_id": str(milk.id), "qty": 150}]},
        headers=_auth(token),
    )).json()
    recipe_id = created["id"]

    # add
    add_r = await client.post(
        f"/api/v1/inventory/recipes/{recipe_id}/lines",
        json={"ingredient_id": str(sugar.id), "qty": 5, "wastage_pct": 0.1},
        headers=_auth(token),
    )
    assert add_r.status_code == 201, add_r.text
    sugar_line = add_r.json()
    assert sugar_line["qty"] == 5
    assert sugar_line["wastage_pct"] == 0.1

    # update
    upd_r = await client.patch(
        f"/api/v1/inventory/recipes/{recipe_id}/lines/{sugar_line['id']}",
        json={"qty": 8},
        headers=_auth(token),
    )
    assert upd_r.status_code == 200, upd_r.text
    assert upd_r.json()["qty"] == 8
    assert upd_r.json()["wastage_pct"] == 0.1  # untouched field preserved

    # delete
    del_r = await client.delete(
        f"/api/v1/inventory/recipes/{recipe_id}/lines/{sugar_line['id']}",
        headers=_auth(token),
    )
    assert del_r.status_code == 204, del_r.text

    listed = (await client.get(
        "/api/v1/inventory/recipes", params={"menu_item_id": str(item.id)}, headers=_auth(token),
    )).json()
    assert len(listed[0]["lines"]) == 1
    assert listed[0]["lines"][0]["ingredient_id"] == str(milk.id)


@pytest.mark.asyncio
async def test_recipe_and_lines_are_tenant_isolated(client, session, seed_owner) -> None:
    """A recipe (and its lines) belonging to another company must be
    invisible/unwritable through this company's token — same guarantee as
    every other resource in this router."""
    other_company = Company(id=uuid4(), name="OtherCo2")
    session.add(other_company)
    await session.flush()
    other_item = await _menu_item(session, other_company.id)
    other_ing = await _ingredient(session, other_company.id)
    other_recipe = Recipe(id=uuid4(), menu_item_id=other_item.id, name="Not yours", is_active=True)
    session.add(other_recipe)
    await session.flush()
    other_line = RecipeLine(id=uuid4(), recipe_id=other_recipe.id, ingredient_id=other_ing.id, qty=10)
    session.add(other_line)
    await session.commit()

    token = await _login(client, seed_owner)

    list_r = await client.get(
        "/api/v1/inventory/recipes", params={"menu_item_id": str(other_item.id)}, headers=_auth(token),
    )
    assert list_r.status_code == 404  # menu item itself isn't visible to this tenant

    patch_r = await client.patch(
        f"/api/v1/inventory/recipes/{other_recipe.id}", json={"name": "hijacked"}, headers=_auth(token),
    )
    assert patch_r.status_code == 404

    delete_r = await client.delete(f"/api/v1/inventory/recipes/{other_recipe.id}", headers=_auth(token))
    assert delete_r.status_code == 404

    add_line_r = await client.post(
        f"/api/v1/inventory/recipes/{other_recipe.id}/lines",
        json={"ingredient_id": str(other_ing.id), "qty": 1},
        headers=_auth(token),
    )
    assert add_line_r.status_code == 404


@pytest.mark.asyncio
async def test_recipe_created_via_api_is_consumed_by_deduction(client, session, seed_owner) -> None:
    """The integration check the task asked for: build a recipe purely
    through the CRUD API, then run the exact same deduct_for_order() call
    _finalize_order() makes at order payment, against a real Postgres
    session, and confirm stock actually moves by the right amount."""
    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id
    item = await _menu_item(session, company_id)
    milk = await _ingredient(session, company_id, name="Milk")
    await session.commit()

    # A real batch to deduct from — same helper shape as
    # tests/unit/test_inventory_deduction.py's _batch().
    batch = Batch(
        id=uuid4(),
        ingredient_id=milk.id,
        branch_id=branch_id,
        received_at=datetime(2026, 7, 1, tzinfo=UTC),
        qty_initial=1000.0,
        qty_on_hand=1000.0,
        cost_per_unit_minor=50,
    )
    session.add(batch)
    await session.commit()

    token = await _login(client, seed_owner)
    created = (await client.post(
        "/api/v1/inventory/recipes",
        json={
            "menu_item_id": str(item.id),
            "name": "Cappuccino v1",
            "lines": [{"ingredient_id": str(milk.id), "qty": 150, "wastage_pct": 0.1}],
        },
        headers=_auth(token),
    )).json()
    assert created["is_active"] is True

    order_id = uuid4()
    order_line = SimpleNamespace(menu_item_id=item.id, qty=2.0)

    movements = await deduct_for_order(
        session,
        order_id=order_id,
        order_lines=[order_line],
        branch_id=branch_id,
        created_by=seed_owner["owner"].id,
    )
    await session.commit()

    assert movements == 1
    # 150ml * (1 + 0.1 wastage) * 2 units = 330ml
    expected_deduction = 150 * 1.1 * 2

    refreshed_batch = (
        await session.execute(select(Batch).where(Batch.id == batch.id))
    ).scalar_one()
    assert float(refreshed_batch.qty_on_hand) == pytest.approx(1000.0 - expected_deduction)

    refreshed_ingredient = (
        await session.execute(select(Ingredient).where(Ingredient.id == milk.id))
    ).scalar_one()
    assert float(refreshed_ingredient.current_qty) == pytest.approx(1000.0 - expected_deduction)

    movement = (
        await session.execute(
            select(StockMovement).where(StockMovement.ref_id == order_id)
        )
    ).scalar_one()
    assert movement.type == "sale"
    assert movement.ref_type == "order"
    assert float(movement.qty_delta) == pytest.approx(-expected_deduction)
