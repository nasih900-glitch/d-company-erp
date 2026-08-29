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

import asyncio
from contextlib import suppress
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError

import app.services.inventory.deduction as deduction_service
from app.api.v1.insights.router import costing_coverage
from app.core.db import AsyncSessionLocal
from app.core.tenant import TenantContext
from app.models import (
    Batch,
    Branch,
    Company,
    Ingredient,
    MenuCategory,
    MenuItem,
    Recipe,
    RecipeLine,
    StockMovement,
)
from app.services.inventory.deduction import (
    UNCOSTED_SHORTAGE_LOT_CODE,
    deduct_for_order,
    restock_for_refund,
)


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
async def test_create_recipe_rejects_ingredient_from_another_company(
    client, session, seed_owner
) -> None:
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
async def test_create_recipe_conflicts_with_existing_active_recipe(
    client, session, seed_owner
) -> None:
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
async def test_database_rejects_a_second_active_recipe_outside_the_api(
    session,
    seed_owner,
) -> None:
    """The database guard must hold even for a script that bypasses API locks."""

    item = await _menu_item(session, seed_owner["company"].id)
    first = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="Authoritative recipe",
        version=1,
        yield_qty=1,
        is_active=True,
    )
    session.add(first)
    await session.commit()

    session.add(
        Recipe(
            id=uuid4(),
            menu_item_id=item.id,
            name="Conflicting recipe",
            version=2,
            yield_qty=1,
            is_active=True,
        )
    )
    with pytest.raises(IntegrityError, match="uq_recipe_one_active_per_menu_item"):
        await session.flush()
    await session.rollback()


@pytest.mark.asyncio
async def test_database_rejects_nonpositive_recipe_yield(session, seed_owner) -> None:
    item = await _menu_item(session, seed_owner["company"].id)
    session.add(
        Recipe(
            id=uuid4(),
            menu_item_id=item.id,
            name="Invalid yield",
            version=1,
            yield_qty=0,
            is_active=True,
        )
    )
    with pytest.raises(IntegrityError, match="ck_recipe_positive_yield"):
        await session.flush()
    await session.rollback()


@pytest.mark.asyncio
async def test_database_rejects_null_recipe_yield(session, seed_owner) -> None:
    item = await _menu_item(session, seed_owner["company"].id)
    with pytest.raises(IntegrityError, match="yield_qty"):
        await session.execute(
            text(
                """
                INSERT INTO recipes (
                    id, menu_item_id, name, yield_qty, version, is_active
                ) VALUES (
                    :id, :menu_item_id, :name, NULL, 1, TRUE
                )
                """
            ),
            {
                "id": uuid4(),
                "menu_item_id": item.id,
                "name": "Null yield",
            },
        )
    await session.rollback()


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
        json={
            "menu_item_id": str(item.id),
            "name": "R1",
            "lines": [{"ingredient_id": str(milk.id), "qty": 10}],
        },
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
        json={
            "menu_item_id": str(item.id),
            "name": "v1",
            "lines": [{"ingredient_id": str(milk.id), "qty": 10}],
        },
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
        json={
            "menu_item_id": str(item.id),
            "name": "v2",
            "lines": [{"ingredient_id": str(milk.id), "qty": 20}],
        },
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
        json={
            "menu_item_id": str(item.id),
            "name": "v1",
            "lines": [{"ingredient_id": str(milk.id), "qty": 150}],
        },
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
async def test_active_recipe_prevents_ingredient_soft_delete(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    item = await _menu_item(session, company_id)
    ingredient = await _ingredient(session, company_id, name="Protected recipe ingredient")
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="Protected active recipe",
        yield_qty=1,
        is_active=True,
    )
    session.add(recipe)
    await session.flush()
    session.add(
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe.id,
            ingredient_id=ingredient.id,
            qty=1,
            wastage_pct=0,
        )
    )
    await session.commit()

    token = await _login(client, seed_owner)
    response = await client.delete(
        f"/api/v1/inventory/ingredients/{ingredient.id}",
        headers=_auth(token),
    )

    assert response.status_code == 409, response.text
    assert "active recipe" in response.json()["error"]["message"]
    await session.refresh(ingredient)
    assert ingredient.deleted_at is None


@pytest.mark.asyncio
async def test_batch_history_prevents_ingredient_delete_and_unit_rewrite(
    client,
    session,
    seed_owner,
) -> None:
    ingredient = await _ingredient(
        session,
        seed_owner["company"].id,
        name="Historical unit ingredient",
    )
    batch = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=seed_owner["branch"].id,
        received_at=datetime.now(UTC),
        qty_initial=0,
        qty_on_hand=0,
        cost_per_unit_minor=25,
    )
    session.add(batch)
    await session.commit()

    token = await _login(client, seed_owner)
    update = await client.patch(
        f"/api/v1/inventory/ingredients/{ingredient.id}",
        json={"base_unit": "unit"},
        headers=_auth(token),
    )
    delete = await client.delete(
        f"/api/v1/inventory/ingredients/{ingredient.id}",
        headers=_auth(token),
    )

    assert update.status_code == 409, update.text
    assert "base unit cannot be changed" in update.json()["error"]["message"]
    assert delete.status_code == 409, delete.text
    assert "stock history" in delete.json()["error"]["message"]
    await session.refresh(ingredient)
    assert ingredient.base_unit == "ml"
    assert ingredient.deleted_at is None


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
    other_line = RecipeLine(
        id=uuid4(),
        recipe_id=other_recipe.id,
        ingredient_id=other_ing.id,
        qty=10,
    )
    session.add(other_line)
    await session.commit()

    token = await _login(client, seed_owner)

    list_r = await client.get(
        "/api/v1/inventory/recipes",
        params={"menu_item_id": str(other_item.id)},
        headers=_auth(token),
    )
    assert list_r.status_code == 404  # menu item itself isn't visible to this tenant

    patch_r = await client.patch(
        f"/api/v1/inventory/recipes/{other_recipe.id}",
        json={"name": "hijacked"},
        headers=_auth(token),
    )
    assert patch_r.status_code == 404

    delete_r = await client.delete(
        f"/api/v1/inventory/recipes/{other_recipe.id}", headers=_auth(token)
    )
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
            "yield_qty": 2,
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
    # The recipe quantity makes two menu units: 150ml * 1.1 wastage / 2 yield
    # * 2 sold units = 165ml. Ignoring yield_qty would incorrectly take 330ml.
    expected_deduction = 150 * 1.1 / 2 * 2

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


@pytest.mark.asyncio
async def test_sale_skips_expired_fifo_batch_and_uses_next_saleable_batch(
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id
    item = await _menu_item(session, company_id)
    ingredient = await _ingredient(session, company_id, name="Expiry-safe milk")
    ingredient.current_qty = 20
    now = datetime.now(UTC)
    expired = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=branch_id,
        received_at=now - timedelta(days=2),
        expires_at=now - timedelta(microseconds=1),
        qty_initial=10,
        qty_on_hand=10,
        cost_per_unit_minor=40,
        lot_code="EXPIRED",
    )
    saleable = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=branch_id,
        received_at=now - timedelta(days=1),
        expires_at=now + timedelta(days=1),
        qty_initial=10,
        qty_on_hand=10,
        cost_per_unit_minor=60,
        lot_code="SALEABLE",
    )
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="Expiry-safe recipe",
        yield_qty=1,
        is_active=True,
    )
    line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=3,
        wastage_pct=0,
    )
    session.add_all([expired, saleable, recipe, line])
    await session.flush()

    order_id = uuid4()
    movements = await deduct_for_order(
        session,
        order_id=order_id,
        order_lines=[SimpleNamespace(menu_item_id=item.id, qty=1)],
        branch_id=branch_id,
        created_by=seed_owner["owner"].id,
    )
    await session.flush()

    assert movements == 1
    assert float(expired.qty_on_hand) == pytest.approx(10)
    assert float(saleable.qty_on_hand) == pytest.approx(7)
    movement = (
        await session.execute(
            select(StockMovement).where(StockMovement.ref_id == order_id)
        )
    ).scalar_one()
    assert movement.batch_id == saleable.id
    assert int(movement.cost_per_unit_minor) == 60


@pytest.mark.asyncio
async def test_unknown_cost_shortage_round_trips_through_full_refund(
    session,
    seed_owner,
) -> None:
    """A batch-less sale must remain auditable and exactly reversible."""
    item = await _menu_item(session, seed_owner["company"].id)
    ingredient = await _ingredient(
        session,
        seed_owner["company"].id,
        name="Never received ingredient",
    )
    ingredient.current_qty = 0
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="Unknown-cost recipe",
        is_active=True,
    )
    line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=2,
        wastage_pct=0,
    )
    session.add_all([recipe, line])
    await session.flush()

    order_id = uuid4()
    movements = await deduct_for_order(
        session,
        order_id=order_id,
        order_lines=[SimpleNamespace(menu_item_id=item.id, qty=1)],
        branch_id=seed_owner["branch"].id,
        created_by=seed_owner["owner"].id,
    )
    await session.flush()

    shortage_batch = (
        await session.execute(
            select(Batch).where(
                Batch.ingredient_id == ingredient.id,
                Batch.branch_id == seed_owner["branch"].id,
            )
        )
    ).scalar_one()
    assert movements == 1
    assert shortage_batch.lot_code == UNCOSTED_SHORTAGE_LOT_CODE
    assert float(shortage_batch.qty_on_hand) == pytest.approx(-2)
    assert int(shortage_batch.cost_per_unit_minor) == 0
    assert float(ingredient.current_qty) == pytest.approx(-2)

    restocked = await restock_for_refund(
        session,
        order_id=order_id,
        branch_id=seed_owner["branch"].id,
        created_by=seed_owner["owner"].id,
        fraction=1.0,
    )
    await session.flush()

    assert restocked == 1
    assert float(shortage_batch.qty_on_hand) == pytest.approx(0)
    assert float(ingredient.current_qty) == pytest.approx(0)
    movement_rows = (
        await session.execute(
            select(StockMovement)
            .where(StockMovement.ref_id == order_id)
            .order_by(StockMovement.created_at, StockMovement.id)
        )
    ).scalars().all()
    assert {movement.type for movement in movement_rows} == {"sale", "refund_restock"}
    assert all(int(movement.cost_per_unit_minor) == 0 for movement in movement_rows)


@pytest.mark.asyncio
async def test_concurrent_opposite_recipe_orders_use_one_inventory_lock_order(
    session,
    seed_owner,
    monkeypatch,
) -> None:
    """Two opposite BOM orders must complete without a database deadlock.

    The wrapper synchronizes both transactions before their first inventory
    lock, then briefly holds the transaction that acquired its first
    ingredient.  Without canonical ingredient ordering each transaction owns
    a different first ingredient and deadlocks on the second; with canonical
    ordering one waits at the shared first ingredient, then both complete.
    Recipe query rows are supplied in-memory so the intentionally opposite
    source order is deterministic while the inventory locks remain real
    PostgreSQL row locks.
    """
    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id
    first = await _ingredient(session, company_id, name="Concurrency A")
    second = await _ingredient(session, company_id, name="Concurrency B")
    first.current_qty = 100
    second.current_qty = 100
    low, high = sorted((first, second), key=lambda ingredient: ingredient.id.int)
    session.add_all(
        [
            Batch(
                id=uuid4(),
                ingredient_id=ingredient.id,
                branch_id=branch_id,
                received_at=datetime(2026, 8, 25, tzinfo=UTC),
                qty_initial=100,
                qty_on_hand=100,
                cost_per_unit_minor=10,
            )
            for ingredient in (low, high)
        ]
    )
    await session.commit()

    menu_a = uuid4()
    menu_b = uuid4()
    recipe_a = Recipe(id=uuid4(), menu_item_id=menu_a, name="A then B", is_active=True)
    recipe_b = Recipe(id=uuid4(), menu_item_id=menu_b, name="B then A", is_active=True)
    lines_a = [
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_a.id,
            ingredient_id=low.id,
            qty=1,
            wastage_pct=0,
        ),
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_a.id,
            ingredient_id=high.id,
            qty=1,
            wastage_pct=0,
        ),
    ]
    lines_b = [
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_b.id,
            ingredient_id=high.id,
            qty=1,
            wastage_pct=0,
        ),
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe_b.id,
            ingredient_id=low.id,
            qty=1,
            wastage_pct=0,
        ),
    ]
    order_a = uuid4()
    order_b = uuid4()
    peer = {order_a: order_b, order_b: order_a}
    started = {order_a: asyncio.Event(), order_b: asyncio.Event()}
    first_lock_complete = {order_a: asyncio.Event(), order_b: asyncio.Event()}
    calls = {order_a: 0, order_b: 0}

    class _Rows:
        def __init__(self, rows) -> None:
            self.rows = rows

        def scalars(self):
            return self

        def all(self):
            return self.rows

    class _RecipeRowsThenDatabase:
        def __init__(self, database_session, recipe, lines) -> None:
            self.database_session = database_session
            self.results = [_Rows([recipe]), _Rows(lines)]

        async def execute(self, statement):
            if self.results:
                return self.results.pop(0)
            return await self.database_session.execute(statement)

        def add(self, entity) -> None:
            self.database_session.add(entity)

    original_deduct = deduction_service._deduct_ingredient

    async def _coordinated_deduct(database_session, **kwargs) -> int:
        order_id = kwargs["order_id"]
        if calls[order_id] == 0:
            started[order_id].set()
            await asyncio.wait_for(started[peer[order_id]].wait(), timeout=2)

        result = await original_deduct(database_session, **kwargs)
        calls[order_id] += 1
        if calls[order_id] == 1:
            first_lock_complete[order_id].set()
            # Expected to time out with canonical ordering: the peer is
            # waiting on this transaction's first Ingredient row lock.
            with suppress(TimeoutError):
                await asyncio.wait_for(
                    first_lock_complete[peer[order_id]].wait(),
                    timeout=0.25,
                )
        return result

    monkeypatch.setattr(deduction_service, "_deduct_ingredient", _coordinated_deduct)

    async def _run_payment(*, order_id, menu_item_id, recipe, lines) -> int:
        async with AsyncSessionLocal() as database_session:
            await database_session.execute(text("SET LOCAL lock_timeout = '3s'"))
            proxy = _RecipeRowsThenDatabase(database_session, recipe, lines)
            try:
                movements = await deduction_service.deduct_for_order(
                    proxy,
                    order_id=order_id,
                    order_lines=[SimpleNamespace(menu_item_id=menu_item_id, qty=1)],
                    branch_id=branch_id,
                    created_by=seed_owner["owner"].id,
                )
                await database_session.commit()
                return movements
            except Exception:
                await database_session.rollback()
                raise

    movements = await asyncio.wait_for(
        asyncio.gather(
            _run_payment(
                order_id=order_a,
                menu_item_id=menu_a,
                recipe=recipe_a,
                lines=lines_a,
            ),
            _run_payment(
                order_id=order_b,
                menu_item_id=menu_b,
                recipe=recipe_b,
                lines=lines_b,
            ),
        ),
        timeout=8,
    )
    assert movements == [2, 2]

    async with AsyncSessionLocal() as verify:
        ingredients = (
            await verify.execute(
                select(Ingredient).where(Ingredient.id.in_((low.id, high.id)))
            )
        ).scalars().all()
        batches = (
            await verify.execute(
                select(Batch).where(
                    Batch.ingredient_id.in_((low.id, high.id)),
                    Batch.branch_id == branch_id,
                )
            )
        ).scalars().all()
        movements_written = (
            await verify.execute(
                select(StockMovement).where(StockMovement.ref_id.in_((order_a, order_b)))
            )
        ).scalars().all()

    assert len(ingredients) == 2
    assert all(float(ingredient.current_qty) == pytest.approx(98) for ingredient in ingredients)
    assert len(batches) == 2
    assert all(float(batch.qty_on_hand) == pytest.approx(98) for batch in batches)
    assert len(movements_written) == 4


@pytest.mark.asyncio
async def test_costing_coverage_requires_branch_local_fifo_cost(
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    main_branch = seed_owner["branch"]
    kiosk = Branch(
        id=uuid4(),
        company_id=company_id,
        name="Kiosk",
        invoice_series_code="KS",
    )
    session.add(kiosk)
    await session.flush()
    item = await _menu_item(session, company_id)
    ingredient = await _ingredient(session, company_id, name="Beans")
    ingredient.avg_cost_minor = 30
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="Branch-aware recipe",
        is_active=True,
    )
    line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=1,
        wastage_pct=0,
    )
    main_costed = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=main_branch.id,
        received_at=datetime(2026, 8, 1, tzinfo=UTC),
        qty_initial=10,
        qty_on_hand=10,
        cost_per_unit_minor=30,
    )
    kiosk_uncosted = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=kiosk.id,
        received_at=datetime(2026, 8, 1, tzinfo=UTC),
        qty_initial=10,
        qty_on_hand=10,
        cost_per_unit_minor=0,
    )
    session.add_all([recipe, line, main_costed, kiosk_uncosted])
    await session.flush()
    tenant = TenantContext(
        user_id=seed_owner["owner"].id,
        company_id=company_id,
        branch_id=main_branch.id,
        terminal_id=seed_owner["terminal"].id,
        roles=("owner",),
    )
    kiosk_tenant = TenantContext(
        user_id=seed_owner["owner"].id,
        company_id=company_id,
        branch_id=kiosk.id,
        terminal_id=None,
        roles=("owner",),
    )

    # Authenticated report/insight endpoints are selected-branch views. A
    # kiosk costing gap must not contaminate Main's figures, and Main's costed
    # batch must not make the kiosk look complete.
    main_complete = await costing_coverage(session, tenant)
    assert main_complete.branch_id == main_branch.id
    assert main_complete.is_complete is True
    assert main_complete.fully_costed_item_count == 1

    incomplete = await costing_coverage(session, kiosk_tenant)
    assert incomplete.branch_id == kiosk.id
    assert incomplete.is_complete is False
    assert incomplete.incomplete_item_count == 1
    assert "Beans at Kiosk" in incomplete.issues[0].detail

    kiosk_uncosted.cost_per_unit_minor = 30
    await session.flush()
    complete = await costing_coverage(session, kiosk_tenant)
    assert complete.is_complete is True
    assert complete.fully_costed_item_count == 1

    unresolved_order_id = uuid4()
    kiosk_uncosted.qty_on_hand = 9
    session.add(
        StockMovement(
            id=uuid4(),
            batch_id=kiosk_uncosted.id,
            branch_id=kiosk.id,
            type="sale",
            ref_type="order",
            ref_id=unresolved_order_id,
            qty_delta=-1,
            cost_per_unit_minor=0,
            created_by=seed_owner["owner"].id,
            note="historical unknown-cost sale",
        )
    )
    await session.flush()
    unresolved = await costing_coverage(session, kiosk_tenant)
    assert unresolved.is_complete is False
    assert "Beans at Kiosk" in unresolved.issues[0].detail

    kiosk_uncosted.qty_on_hand = 10
    session.add(
        StockMovement(
            id=uuid4(),
            batch_id=kiosk_uncosted.id,
            branch_id=kiosk.id,
            type="refund_restock",
            ref_type="order",
            ref_id=unresolved_order_id,
            qty_delta=1,
            cost_per_unit_minor=0,
            created_by=seed_owner["owner"].id,
            note="historical unknown-cost sale reversed",
        )
    )
    await session.flush()
    resolved = await costing_coverage(session, kiosk_tenant)
    assert resolved.is_complete is True
