"""Database-free regression for Phase 9's inventory idempotency fix.

Covers a real gap found while scoping the native Android app's offline
Inventory outbox: GRN and adjustment writes create/mutate rows with no
natural unique key to fall back on (a fresh uuid4() PK every time, nothing a
duplicate submission could collide against) — unlike menu/customers, there
was no IntegrityError safety net available at all. A resubmitted GRN (e.g.
an offline-outbox retry after a dropped connection whose first attempt
actually landed) would silently double stock and purchase spend with no
error and no trace it happened; a resubmitted adjustment would silently
double-apply a stock change. Idempotency-Key is now REQUIRED on both
endpoints (see `_require_idempotency` in inventory/router.py) — this file
proves that requirement, and proves a genuine replay returns the stored
response instead of re-running the write.

Ingredient creation is different: it already has a real unique constraint
(SKU) and an existing pre-check, just no IntegrityError fallback for the
race the pre-check alone misses (identical shape to Menu's pre-fix
create_category/create_item — see test_menu_router_create_race.py). Fixed
the same way, with the same database-free style, injecting the constraint
violation directly via a fake session's flush().
"""

from __future__ import annotations

from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest
from sqlalchemy.exc import IntegrityError

from app.api.v1.inventory import router as inventory_router
from app.core.errors import BusinessRuleError, ConflictError
from app.core.tenant import TenantContext


class _Result:
    def __init__(self, *, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar


class _NoIdempotencyState:
    """Mirrors a real Starlette Request whose IdempotencyMiddleware never
    set anything, because no Idempotency-Key header was sent."""


class _WithIdempotencyState:
    def __init__(self, key: str, request_hash: str) -> None:
        self.idempotency_key = key
        self.idempotency_request_hash = request_hash


class _Request:
    def __init__(self, state=None) -> None:
        self.state = state or _NoIdempotencyState()


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


# ------------------------------------------------------------- create_ingredient


@pytest.mark.asyncio
async def test_create_ingredient_converts_unique_sku_violation_to_a_clean_conflict() -> None:
    """The existing SKU pre-check only catches a collision that already
    committed before this request's own SELECT ran — nothing for two
    requests racing the same brand-new SKU. Proves the fallback that closes
    that gap, same shape as test_menu_router_create_race.py."""
    tenant = _tenant()
    session = _Session(
        _Result(scalar=None),  # this request's own SKU pre-check — sees nothing yet
        flush_error=IntegrityError("INSERT INTO ingredients ...", {}, Exception("uq_ingredient_sku")),
    )

    with pytest.raises(ConflictError, match="COFFEE-001"):
        await inventory_router.create_ingredient(
            inventory_router.IngredientCreate(sku="COFFEE-001", name="Coffee Beans", base_unit="g"),
            session,
            _Request(),
            tenant,
        )

    assert session.flush_count == 1
    assert session.rollback_count == 1


@pytest.mark.asyncio
async def test_create_ingredient_replay_returns_stored_response_without_touching_the_db() -> None:
    tenant = _tenant()
    session = _Session()  # zero queued results — proves no DB statement runs on a replay
    stored_body = {"id": str(uuid4()), "sku": "COFFEE-001", "name": "Coffee Beans", "base_unit": "g",
                    "current_qty": 0.0, "reorder_threshold": 0.0, "reorder_qty": 0.0, "avg_cost_minor": 0}

    with patch.object(
        inventory_router, "check_or_reserve", AsyncMock(return_value={"body": stored_body}),
    ):
        result = await inventory_router.create_ingredient(
            inventory_router.IngredientCreate(sku="COFFEE-001", name="Coffee Beans", base_unit="g"),
            session,
            _Request(_WithIdempotencyState("ingredient:local-1", "hash")),
            tenant,
        )

    assert result.sku == "COFFEE-001"
    assert session.flush_count == 0


# ------------------------------------------------------------------- post_grn


@pytest.mark.asyncio
async def test_post_grn_without_idempotency_key_is_rejected() -> None:
    """The core fix: GRN has no unique-constraint fallback at all, so a
    missing key must be a hard error, not a silently-unprotected write."""
    tenant = _tenant()
    session = _Session()  # zero queued results — must reject before touching the DB

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await inventory_router.post_grn(
            inventory_router.GrnPost(
                branch_id=uuid4(), supplier_id=uuid4(),
                lines=[inventory_router.GrnLineIn(ingredient_id=uuid4(), qty=5, unit_cost_minor=100)],
            ),
            session,
            _Request(),
            tenant,
        )

    assert session.flush_count == 0


@pytest.mark.asyncio
async def test_post_grn_replay_returns_the_stored_response_without_creating_a_second_grn() -> None:
    """Proves the actual replay-safety promise: a retried identical GRN
    submission (same key+hash — e.g. the offline outbox retrying after a
    dropped response whose first attempt already landed) returns the
    original result instead of receiving stock a second time."""
    tenant = _tenant()
    session = _Session()  # zero queued results — proves no write logic runs on a replay
    stored_body = {"ok": True, "id": str(uuid4()), "purchase_order_id": str(uuid4()),
                    "batches_created": 1, "batch_ids": [str(uuid4())], "line_ids": [str(uuid4())],
                    "total_minor": 500, "supplier_invoice_no": None}

    with patch.object(
        inventory_router, "check_or_reserve", AsyncMock(return_value={"body": stored_body}),
    ):
        result = await inventory_router.post_grn(
            inventory_router.GrnPost(
                branch_id=uuid4(), supplier_id=uuid4(),
                lines=[inventory_router.GrnLineIn(ingredient_id=uuid4(), qty=5, unit_cost_minor=100)],
            ),
            session,
            _Request(_WithIdempotencyState("grn:local-1", "hash")),
            tenant,
        )

    assert result == stored_body
    assert session.flush_count == 0


# --------------------------------------------------------------- post_adjustment


@pytest.mark.asyncio
async def test_post_adjustment_without_idempotency_key_is_rejected() -> None:
    tenant = _tenant()
    session = _Session()

    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await inventory_router.post_adjustment(
            inventory_router.StockAdjustment(
                ingredient_id=uuid4(), branch_id=uuid4(), qty_delta=-2, type="waste",
            ),
            session,
            _Request(),
            tenant,
        )

    assert session.flush_count == 0


@pytest.mark.asyncio
async def test_post_adjustment_replay_returns_the_stored_response_without_double_applying() -> None:
    tenant = _tenant()
    session = _Session()
    stored_body = {"id": str(uuid4()), "remaining": 8.0}

    with patch.object(
        inventory_router, "check_or_reserve", AsyncMock(return_value={"body": stored_body}),
    ):
        result = await inventory_router.post_adjustment(
            inventory_router.StockAdjustment(
                ingredient_id=uuid4(), branch_id=uuid4(), qty_delta=-2, type="waste",
            ),
            session,
            _Request(_WithIdempotencyState("adjustment:local-1", "hash")),
            tenant,
        )

    assert result == stored_body
    assert session.flush_count == 0
