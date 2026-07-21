"""Database-free tests for build_expiring_batches_alert.

Follows the same fake-session pattern as tests/unit/test_inventory_deduction.py
so these run without a live Postgres connection.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from app.models import Batch, Ingredient
from app.services.alerts.business import EXPIRING_SOON_DAYS, build_expiring_batches_alert


class _Result:
    def __init__(self, rows: list) -> None:
        self.rows = rows

    def all(self):
        return self.rows


class _Session:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)

    async def execute(self, statement):
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)


def _ingredient(*, company_id, name: str = "Fresh Milk") -> Ingredient:
    return Ingredient(
        id=uuid4(),
        company_id=company_id,
        sku="MILK-1L",
        name=name,
        base_unit="unit",
        current_qty=10.0,
    )


def _batch(*, ingredient_id, expires_at, qty_on_hand: float = 5.0) -> Batch:
    return Batch(
        id=uuid4(),
        ingredient_id=ingredient_id,
        branch_id=uuid4(),
        received_at=datetime(2026, 7, 1, tzinfo=UTC),
        expires_at=expires_at,
        qty_initial=10.0,
        qty_on_hand=qty_on_hand,
        cost_per_unit_minor=5000,
    )


@pytest.mark.asyncio
async def test_batch_expiring_within_window_is_reported() -> None:
    company_id = uuid4()
    ingredient = _ingredient(company_id=company_id)
    near_future = datetime.now(UTC) + timedelta(days=2)
    batch = _batch(ingredient_id=ingredient.id, expires_at=near_future, qty_on_hand=6.0)

    session = _Session(_Result(rows=[(batch, ingredient)]))
    alerts = await build_expiring_batches_alert(session, company_id=company_id)

    assert len(alerts) == 1
    alert = alerts[0]
    assert alert.severity == "warning"
    assert alert.title == "Expiring soon: Fresh Milk"
    assert "Fresh Milk" in alert.detail
    assert "6" in alert.detail


@pytest.mark.asyncio
async def test_already_expired_batch_is_reported_as_critical() -> None:
    company_id = uuid4()
    ingredient = _ingredient(company_id=company_id)
    already_expired = datetime.now(UTC) - timedelta(days=3)
    batch = _batch(ingredient_id=ingredient.id, expires_at=already_expired, qty_on_hand=6.0)

    session = _Session(_Result(rows=[(batch, ingredient)]))
    alerts = await build_expiring_batches_alert(session, company_id=company_id)

    assert len(alerts) == 1
    alert = alerts[0]
    assert alert.severity == "critical"
    assert "expired" in alert.detail


@pytest.mark.asyncio
async def test_no_qualifying_rows_yields_no_alerts() -> None:
    # The actual expires_at/qty_on_hand filtering happens in the SQL WHERE
    # clause (see tests/integration/test_expiring_batches_alert.py for a
    # DB-backed test proving a far-future or null-expiry batch never reaches
    # this point). Here we only confirm the builder degrades gracefully when
    # the query returns nothing.
    company_id = uuid4()

    session = _Session(_Result(rows=[]))
    alerts = await build_expiring_batches_alert(session, company_id=company_id)

    assert alerts == []


@pytest.mark.asyncio
async def test_default_window_matches_expiring_soon_days_constant() -> None:
    assert EXPIRING_SOON_DAYS == 7
