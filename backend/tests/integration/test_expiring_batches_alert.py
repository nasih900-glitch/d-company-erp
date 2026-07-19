"""DB-backed test for build_expiring_batches_alert's SQL filter.

Unlike tests/unit/test_alerts_business.py (which exercises formatting with a
fake session), this seeds real Ingredient/Batch rows so the actual
expires_at/qty_on_hand WHERE clause gets validated against Postgres.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.models import Batch, Ingredient
from app.services.alerts.business import build_expiring_batches_alert


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


def _ingredient(*, company_id, name: str) -> Ingredient:
    return Ingredient(
        id=uuid4(),
        company_id=company_id,
        sku=f"SKU-{uuid4().hex[:8]}",
        name=name,
        base_unit="unit",
        current_qty=10.0,
    )


def _batch(*, ingredient_id, branch_id, expires_at, qty_on_hand: float = 5.0) -> Batch:
    return Batch(
        id=uuid4(),
        ingredient_id=ingredient_id,
        branch_id=branch_id,
        received_at=datetime(2026, 7, 1, tzinfo=UTC),
        expires_at=expires_at,
        qty_initial=10.0,
        qty_on_hand=qty_on_hand,
        cost_per_unit_minor=5000,
    )


@pytest.mark.asyncio
async def test_near_future_batch_is_included_far_future_and_null_are_excluded(
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id

    near_ingredient = _ingredient(company_id=company_id, name="Near Expiry Cheese")
    far_ingredient = _ingredient(company_id=company_id, name="Far Expiry Cheese")
    null_ingredient = _ingredient(company_id=company_id, name="No Expiry Syrup")

    near_batch = _batch(
        ingredient_id=near_ingredient.id,
        branch_id=branch_id,
        expires_at=datetime.now(UTC) + timedelta(days=2),
        qty_on_hand=4.0,
    )
    far_batch = _batch(
        ingredient_id=far_ingredient.id,
        branch_id=branch_id,
        expires_at=datetime.now(UTC) + timedelta(days=90),
        qty_on_hand=4.0,
    )
    null_batch = _batch(
        ingredient_id=null_ingredient.id,
        branch_id=branch_id,
        expires_at=None,
        qty_on_hand=4.0,
    )
    depleted_batch = _batch(
        ingredient_id=near_ingredient.id,
        branch_id=branch_id,
        expires_at=datetime.now(UTC) + timedelta(days=1),
        qty_on_hand=0,
    )

    session.add_all(
        [
            near_ingredient,
            far_ingredient,
            null_ingredient,
            near_batch,
            far_batch,
            null_batch,
            depleted_batch,
        ]
    )
    await session.flush()

    alerts = await build_expiring_batches_alert(session, company_id=company_id)

    titles = [a.title for a in alerts]
    assert "Expiring soon: Near Expiry Cheese" in titles
    assert "Expiring soon: Far Expiry Cheese" not in titles
    assert "Expiring soon: No Expiry Syrup" not in titles
    assert len(alerts) == 1
