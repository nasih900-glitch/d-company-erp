"""PostgreSQL proof for inventory batch branch isolation."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.models import Batch, Branch, Ingredient


@pytest_asyncio.fixture(autouse=True)
async def require_local_postgres(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.mark.asyncio
async def test_branch_bound_user_only_receives_batches_for_its_branch(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    local_branch_id = seed_owner["branch"].id
    other_branch = Branch(
        id=uuid4(),
        company_id=company_id,
        name=f"Other-{uuid4().hex[:8]}",
        invoice_series_code="OB",
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=company_id,
        sku=f"SCOPE-{uuid4().hex[:12]}",
        name="Branch-scoped test ingredient",
        base_unit="unit",
        current_qty=5,
        reorder_threshold=0,
        reorder_qty=0,
        avg_cost_minor=100,
    )
    local_batch = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=local_branch_id,
        received_at=datetime(2026, 8, 27, 9, tzinfo=UTC),
        qty_initial=2,
        qty_on_hand=2,
        cost_per_unit_minor=100,
        lot_code="LOCAL",
    )
    other_batch = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=other_branch.id,
        received_at=datetime(2026, 8, 27, 10, tzinfo=UTC),
        qty_initial=3,
        qty_on_hand=3,
        cost_per_unit_minor=110,
        lot_code="OTHER",
    )
    # These legacy inventory mappers expose no ORM relationships between
    # Ingredient and Batch, so make the FK ordering explicit in the fixture.
    session.add_all([other_branch, ingredient])
    await session.flush()
    session.add_all([local_batch, other_batch])
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    listed = await client.get(
        "/api/v1/inventory/batches",
        params={"ingredient_id": str(ingredient.id)},
        headers=headers,
    )
    assert listed.status_code == 200, listed.text
    assert listed.json() == [
        {
            "id": str(local_batch.id),
            "ingredient_id": str(ingredient.id),
            "branch_id": str(local_branch_id),
            "received_at": "2026-08-27T09:00:00Z",
            "expires_at": None,
            "qty_on_hand": 2.0,
            "cost_per_unit_minor": 100,
            "lot_code": "LOCAL",
        }
    ]

    widened = await client.get(
        "/api/v1/inventory/batches",
        params={"branch_id": str(other_branch.id)},
        headers=headers,
    )
    assert widened.status_code == 404
    assert widened.json()["error"]["message"] == "branch not found"

    # Ingredient summaries and valuation must use the same branch scope. The
    # legacy Ingredient.current_qty/avg-cost pair says 5 @ 100 company-wide,
    # while this branch actually owns 2 @ 100. Neither the other branch's
    # quantity nor its different cost may leak into this terminal's stock UI.
    ingredients_response = await client.get(
        "/api/v1/inventory/ingredients",
        headers=headers,
    )
    assert ingredients_response.status_code == 200, ingredients_response.text
    ingredient_row = next(
        row for row in ingredients_response.json() if row["id"] == str(ingredient.id)
    )
    assert ingredient_row["current_qty"] == 2.0
    assert ingredient_row["avg_cost_minor"] == 100

    valuation_response = await client.get(
        "/api/v1/insights/inventory/valuation",
        headers=headers,
    )
    assert valuation_response.status_code == 200, valuation_response.text
    valuation = valuation_response.json()
    assert valuation["branch_id"] == str(local_branch_id)
    valuation_line = next(
        row for row in valuation["lines"] if row["ingredient_id"] == str(ingredient.id)
    )
    assert valuation_line["current_qty"] == 2.0
    assert valuation_line["avg_cost_minor"] == 100
    assert valuation_line["valuation_minor"] == 200


@pytest.mark.asyncio
async def test_valuation_uses_remaining_fifo_layers_not_stale_average_cost(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id
    ingredient = Ingredient(
        id=uuid4(),
        company_id=company_id,
        sku=f"FIFO-VALUE-{uuid4().hex[:10]}",
        name="FIFO valuation test",
        base_unit="unit",
        # Deliberately stale legacy summary: 8 * 350 would incorrectly report
        # 2,800. The remaining high-cost layer is really worth 4,000.
        current_qty=8,
        avg_cost_minor=350,
    )
    consumed_old_layer = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=branch_id,
        received_at=datetime(2026, 8, 1, tzinfo=UTC),
        qty_initial=10,
        qty_on_hand=0,
        cost_per_unit_minor=200,
    )
    remaining_new_layer = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=branch_id,
        received_at=datetime(2026, 8, 2, tzinfo=UTC),
        qty_initial=10,
        qty_on_hand=8,
        cost_per_unit_minor=500,
    )
    session.add(ingredient)
    await session.flush()
    session.add_all([consumed_old_layer, remaining_new_layer])
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200, login.text
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    response = await client.get(
        "/api/v1/insights/inventory/valuation",
        headers=headers,
    )
    assert response.status_code == 200, response.text
    line = next(
        row for row in response.json()["lines"] if row["ingredient_id"] == str(ingredient.id)
    )
    assert line["current_qty"] == 8.0
    assert line["avg_cost_minor"] == 500
    assert line["valuation_minor"] == 4000
