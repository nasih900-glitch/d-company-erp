"""Database-free regression for the concurrent-create race on
POST /menu/categories and POST /menu/items.

Covers a real gap found while scoping the native Android app's offline Menu
outbox (Phase 7): neither endpoint had any protection against two requests
racing a duplicate natural key — `create_category` had no pre-check and no
IntegrityError handling at all (a raw 500 on any `uq_menu_cat_name`
collision), and `create_item` had a pre-check for `sku` but it was a plain
SELECT-then-INSERT with the same TOCTOU gap as Customers had before its own
fix (see test_customers_router_upsert_race.py) — just without the fallback
that closes it. An offline outbox makes this more than theoretical: two
devices could plausibly queue the same new category name, or a duplicate
SKU, and both drain around the same reconnect moment.

Unlike Customers' phone (a genuine "this must be the same real entity"
identity, safe to heal into the winner), a category-name or SKU collision
between two independently-typed requests isn't guaranteed to be the same
intended row — both endpoints now raise a clean, actionable ConflictError
on the race instead of silently merging or crashing.

Same database-free style as `test_customers_router_upsert_race.py` and
`test_reservations_and_bookings.py` — the constraint violation is injected
directly via a fake session's `flush()`, since forcing a real race
deterministically through the ASGI test client is unreliable (the two
requests tend to serialize rather than actually interleave).
"""

from __future__ import annotations

from unittest.mock import patch
from uuid import UUID, uuid4

import pytest
from sqlalchemy.exc import IntegrityError

from app.api.v1.menu import router as menu_router
from app.core.errors import ConflictError
from app.core.tenant import TenantContext
from app.models import MenuCategory


class _Result:
    def __init__(self, *, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar


class _NoIdempotencyState:
    """Mirrors a real Starlette Request whose IdempotencyMiddleware never
    set anything, because no Idempotency-Key header was sent — exactly the
    web app's current call shape for these two endpoints."""


class _Request:
    def __init__(self) -> None:
        self.state = _NoIdempotencyState()


class _Session:
    def __init__(self, *results: _Result, flush_error: Exception | None = None) -> None:
        self.results = list(results)
        self.flush_count = 0
        self.rollback_count = 0
        self.flush_error = flush_error

    async def execute(self, statement):
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    def add(self, _obj) -> None:
        pass

    async def flush(self) -> None:
        self.flush_count += 1
        if self.flush_error is not None:
            error, self.flush_error = self.flush_error, None
            raise error

    async def rollback(self) -> None:
        self.rollback_count += 1


def _tenant(*, company_id: UUID | None = None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=company_id or uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
    )


@pytest.mark.asyncio
async def test_create_category_converts_unique_violation_to_a_clean_conflict() -> None:
    """Before this fix, create_category had no IntegrityError handling at
    all — a name collision from a second offline device syncing in would
    have been a raw 500, not this clean, actionable 409."""
    tenant = _tenant()
    session = _Session(
        flush_error=IntegrityError(
            "INSERT INTO menu_categories ...", {}, Exception("uq_menu_cat_name"),
        ),
    )

    with pytest.raises(ConflictError, match="Beverages"):
        await menu_router.create_category(
            menu_router.CategoryCreate(name="Beverages"),
            session,
            _Request(),
            tenant,
        )

    assert session.flush_count == 1
    assert session.rollback_count == 1


@pytest.mark.asyncio
async def test_create_item_converts_unique_violation_to_a_clean_conflict_past_the_precheck() -> None:
    """The existing SKU pre-check only catches a collision that already
    committed before this request's own SELECT ran — it does nothing for
    two requests racing the same brand-new SKU. This proves the fallback
    that closes that gap.

    require_pricing_unlock is mocked out — a separate, already-covered
    concern (whether the caller supplied a valid X-Pricing-Token), not what
    this test is about.
    """
    tenant = _tenant()
    category = MenuCategory(id=uuid4(), company_id=tenant.company_id, name="Drinks", sort_order=0)

    session = _Session(
        _Result(scalar=None),  # this request's own SKU pre-check — sees nothing yet
        flush_error=IntegrityError(
            "INSERT INTO menu_items ...", {}, Exception("uq_menu_sku"),
        ),
    )

    async def _get(_model, _id):
        return category

    session.get = _get  # type: ignore[assignment]

    with patch.object(menu_router, "require_pricing_unlock"), pytest.raises(
        ConflictError, match="COLA-001",
    ):
        await menu_router.create_item(
            menu_router.ItemCreate(
                category_id=category.id,
                sku="COLA-001",
                name="Cola",
                type="drink",
                base_price_minor=5000,
            ),
            session,
            _Request(),
            tenant,
            None,  # x_pricing_token — the mocked require_pricing_unlock ignores this
        )

    assert session.flush_count == 1
    assert session.rollback_count == 1
