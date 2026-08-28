"""Phase 7 inventory valuation, lifecycle, recipe-yield and sign safety."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError

from app.api.v1.inventory import router as inventory_router
from app.core.errors import BusinessRuleError, ConflictError
from app.core.tenant import TenantContext
from app.models import Ingredient, Recipe, RecipeLine
from app.services.inventory import deduction as deduction_service
from app.services.inventory.accounting import load_inventory_value_changes
from app.services.inventory.valuation import (
    RemainingBatchTotal,
    allocate_batch_movement_value_deltas,
    remaining_batch_totals,
    round_minor,
)


class _Result:
    def __init__(self, *, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = [] if rows is None else rows

    def scalar_one_or_none(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


class _Session:
    def __init__(self, *results: _Result, entity=None) -> None:
        self.results = list(results)
        self.entity = entity
        self.statements = []
        self.added = []
        self.flush_count = 0

    async def get(self, _model, _key):
        return self.entity

    async def execute(self, statement):
        self.statements.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flush_count += 1


def _tenant(*, branch_id: UUID | None = None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=branch_id or uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
    )


def _request() -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key=f"adjustment:{uuid4()}",
            idempotency_request_hash=uuid4().hex,
        )
    )


def test_remaining_batch_total_rounding_and_weighted_cost_are_explicit() -> None:
    total = RemainingBatchTotal(
        ingredient_id=uuid4(),
        qty=Decimal("8"),
        valuation=Decimal("4000"),
    )
    assert total.valuation_minor == 4000
    assert total.weighted_cost_minor == 500
    assert round_minor(Decimal("1.5")) == 2
    assert round_minor(Decimal("-1.5")) == -2


def test_fractional_movement_value_allocation_carries_rounding_residual() -> None:
    movements = [
        SimpleNamespace(qty_delta=Decimal("-0.5"), cost_per_unit_minor=1),
        SimpleNamespace(qty_delta=Decimal("-0.5"), cost_per_unit_minor=1),
    ]

    # The first half-unit keeps the rounded 1-paise batch asset unchanged; the
    # second consumes the carried residual. Independent int()/round() calls
    # would incorrectly post [0, 0] and leave Inventory overstated forever.
    assert allocate_batch_movement_value_deltas(
        qty_initial=Decimal("1"),
        cost_per_unit_minor=1,
        movements=movements,
    ) == [0, -1]


def test_refund_and_count_adjustment_value_deltas_telescope_to_batch_value() -> None:
    movements = [
        SimpleNamespace(qty_delta=Decimal("-0.3333"), cost_per_unit_minor=3),
        SimpleNamespace(qty_delta=Decimal("-0.6667"), cost_per_unit_minor=3),
        SimpleNamespace(qty_delta=Decimal("0.3333"), cost_per_unit_minor=3),
        SimpleNamespace(qty_delta=Decimal("0.6667"), cost_per_unit_minor=3),
        SimpleNamespace(qty_delta=Decimal("-0.5"), cost_per_unit_minor=3),
    ]

    deltas = allocate_batch_movement_value_deltas(
        qty_initial=Decimal("1"),
        cost_per_unit_minor=3,
        movements=movements,
    )
    assert deltas == [-1, -2, 1, 2, -1]
    assert 3 + sum(deltas) == round_minor(Decimal("0.5") * Decimal("3"))


def test_movement_cost_must_match_its_immutable_batch_cost() -> None:
    with pytest.raises(ValueError, match="does not match"):
        allocate_batch_movement_value_deltas(
            qty_initial=Decimal("1"),
            cost_per_unit_minor=3,
            movements=[
                SimpleNamespace(qty_delta=Decimal("-1"), cost_per_unit_minor=4)
            ],
        )


@pytest.mark.asyncio
async def test_value_change_replay_carries_pre_range_rounding_residual() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    batch = SimpleNamespace(
        id=uuid4(),
        branch_id=branch_id,
        qty_initial=Decimal("1"),
        qty_on_hand=Decimal("0"),
        cost_per_unit_minor=1,
    )
    ingredient = SimpleNamespace(company_id=company_id)
    branch = SimpleNamespace(company_id=company_id)
    first_at = datetime(2026, 8, 26, tzinfo=UTC)
    second_at = first_at + timedelta(days=1)
    first = SimpleNamespace(
        id=uuid4(),
        batch_id=batch.id,
        branch_id=branch_id,
        type="sale",
        qty_delta=Decimal("-0.5"),
        cost_per_unit_minor=1,
        created_at=first_at,
        note=None,
    )
    second = SimpleNamespace(
        id=uuid4(),
        batch_id=batch.id,
        branch_id=branch_id,
        type="sale",
        qty_delta=Decimal("-0.5"),
        cost_per_unit_minor=1,
        created_at=second_at,
        note=None,
    )
    session = _Session(
        _Result(
            rows=[
                (batch, ingredient, branch, first),
                (batch, ingredient, branch, second),
            ]
        )
    )

    changes = await load_inventory_value_changes(
        session,
        company_id=company_id,
        start_at=second_at,
        end_exclusive=second_at + timedelta(days=1),
    )

    assert [(change.movement.id, change.inventory_delta_minor) for change in changes] == [
        (second.id, -1)
    ]


@pytest.mark.asyncio
async def test_value_change_replay_rejects_quantity_or_tenant_drift() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    batch = SimpleNamespace(
        id=uuid4(),
        branch_id=branch_id,
        qty_initial=Decimal("1"),
        qty_on_hand=Decimal("0.7"),
        cost_per_unit_minor=10,
    )
    movement = SimpleNamespace(
        id=uuid4(),
        batch_id=batch.id,
        branch_id=branch_id,
        type="sale",
        qty_delta=Decimal("-0.2"),
        cost_per_unit_minor=10,
        created_at=datetime(2026, 8, 27, tzinfo=UTC),
        note=None,
    )
    quantity_drift = _Session(
        _Result(
            rows=[
                (
                    batch,
                    SimpleNamespace(company_id=company_id),
                    SimpleNamespace(company_id=company_id),
                    movement,
                )
            ]
        )
    )
    with pytest.raises(BusinessRuleError, match="does not reconcile"):
        await load_inventory_value_changes(quantity_drift, company_id=company_id)

    cross_tenant = _Session(
        _Result(
            rows=[
                (
                    SimpleNamespace(
                        id=uuid4(),
                        branch_id=branch_id,
                        qty_initial=Decimal("1"),
                        qty_on_hand=Decimal("1"),
                        cost_per_unit_minor=10,
                    ),
                    SimpleNamespace(company_id=uuid4()),
                    SimpleNamespace(company_id=company_id),
                    None,
                )
            ]
        )
    )
    with pytest.raises(BusinessRuleError, match="tenant scope"):
        await load_inventory_value_changes(cross_tenant, company_id=company_id)


@pytest.mark.parametrize("invalid_delta", [float("nan"), float("inf"), float("-inf")])
def test_adjustment_quantity_must_be_finite(invalid_delta: float) -> None:
    with pytest.raises(ValidationError):
        inventory_router.StockAdjustment(
            ingredient_id=uuid4(),
            branch_id=uuid4(),
            qty_delta=invalid_delta,
            type="adjustment",
        )


@pytest.mark.asyncio
async def test_adjustment_rejects_quantity_database_cannot_preserve() -> None:
    tenant = _tenant()
    session = _Session()
    with (
        patch.object(
            inventory_router,
            "check_or_reserve",
            AsyncMock(return_value=None),
        ),
        pytest.raises(BusinessRuleError, match="at most four decimal places"),
    ):
        await inventory_router.post_adjustment(
            inventory_router.StockAdjustment(
                ingredient_id=uuid4(),
                branch_id=tenant.branch_id,
                qty_delta=0.00006,
                type="adjustment",
            ),
            session,
            _request(),
            tenant,
        )
    assert session.statements == []


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("qty", 0),
        ("qty", -1),
        ("qty", float("nan")),
        ("wastage_pct", -0.01),
        ("wastage_pct", 1.01),
        ("wastage_pct", float("inf")),
    ],
)
def test_recipe_line_quantity_and_wastage_validation_is_strict(
    field: str,
    value: float,
) -> None:
    payload = {"ingredient_id": uuid4(), "qty": 1, "wastage_pct": 0}
    payload[field] = value
    with pytest.raises(ValidationError):
        inventory_router.RecipeLineIn.model_validate(payload)


@pytest.mark.parametrize("invalid_yield", [0, -1, float("nan"), float("inf")])
def test_recipe_yield_validation_is_positive_and_finite(invalid_yield: float) -> None:
    with pytest.raises(ValidationError):
        inventory_router.RecipeCreate(
            menu_item_id=uuid4(),
            name="Invalid recipe",
            yield_qty=invalid_yield,
        )


@pytest.mark.asyncio
async def test_remaining_batch_valuation_is_scoped_and_keeps_fifo_layers() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    ingredient_id = uuid4()
    session = _Session(_Result(rows=[(ingredient_id, Decimal("8"), Decimal("4000"))]))

    totals = await remaining_batch_totals(
        session,
        company_id=company_id,
        branch_id=branch_id,
    )

    assert totals[ingredient_id].qty == Decimal("8")
    assert totals[ingredient_id].valuation_minor == 4000
    sql = str(session.statements[0]).lower()
    assert "sum(round(batches.qty_on_hand * batches.cost_per_unit_minor" in sql
    assert "batches.branch_id" in sql
    assert "ingredients.company_id" in sql
    assert "branches.company_id" in sql


@pytest.mark.asyncio
@pytest.mark.parametrize("movement_type", ["waste", "damage"])
async def test_reducing_adjustment_types_reject_a_positive_delta(movement_type: str) -> None:
    tenant = _tenant()
    session = _Session()
    with (
        patch.object(
            inventory_router,
            "check_or_reserve",
            AsyncMock(return_value=None),
        ),
        pytest.raises(BusinessRuleError, match="must reduce stock"),
    ):
        await inventory_router.post_adjustment(
            inventory_router.StockAdjustment(
                ingredient_id=uuid4(),
                branch_id=tenant.branch_id,
                qty_delta=2,
                type=movement_type,
            ),
            session,
            _request(),
            tenant,
        )
    assert session.statements == []


@pytest.mark.asyncio
@pytest.mark.parametrize("qty_delta", [-2, 2])
async def test_single_sided_transfer_is_rejected_for_every_sign(qty_delta: float) -> None:
    tenant = _tenant()
    session = _Session()
    with (
        patch.object(
            inventory_router,
            "check_or_reserve",
            AsyncMock(return_value=None),
        ),
        pytest.raises(BusinessRuleError, match="atomic source and destination"),
    ):
        await inventory_router.post_adjustment(
            inventory_router.StockAdjustment(
                ingredient_id=uuid4(),
                branch_id=tenant.branch_id,
                qty_delta=qty_delta,
                type="transfer",
            ),
            session,
            _request(),
            tenant,
        )
    assert session.statements == []


@pytest.mark.asyncio
async def test_base_unit_change_is_rejected_once_a_batch_exists() -> None:
    tenant = _tenant()
    ingredient = Ingredient(
        id=uuid4(),
        company_id=tenant.company_id,
        sku="MILK",
        name="Milk",
        base_unit="ml",
    )
    session = _Session(_Result(scalar=uuid4()), entity=ingredient)

    with pytest.raises(ConflictError, match="base unit cannot be changed"):
        await inventory_router.update_ingredient(
            ingredient.id,
            inventory_router.IngredientUpdate(base_unit="unit"),
            session,
            tenant,
        )

    assert ingredient.base_unit == "ml"
    assert session.flush_count == 0


@pytest.mark.asyncio
async def test_base_unit_change_is_rejected_for_recipe_history_without_batches() -> None:
    tenant = _tenant()
    ingredient = Ingredient(
        id=uuid4(),
        company_id=tenant.company_id,
        sku="BEANS",
        name="Beans",
        base_unit="g",
    )
    session = _Session(
        _Result(scalar=None),
        _Result(scalar=uuid4()),
        entity=ingredient,
    )

    with pytest.raises(ConflictError, match="base unit cannot be changed"):
        await inventory_router.update_ingredient(
            ingredient.id,
            inventory_router.IngredientUpdate(base_unit="unit"),
            session,
            tenant,
        )


@pytest.mark.asyncio
async def test_delete_ingredient_rejects_an_active_recipe_reference() -> None:
    tenant = _tenant()
    ingredient = Ingredient(
        id=uuid4(),
        company_id=tenant.company_id,
        sku="SUGAR",
        name="Sugar",
        base_unit="g",
    )
    session = _Session(
        _Result(scalar=None),
        _Result(scalar=uuid4()),
        entity=ingredient,
    )

    with pytest.raises(ConflictError, match="active recipe"):
        await inventory_router.delete_ingredient(ingredient.id, session, tenant)

    assert ingredient.deleted_at is None
    assert session.flush_count == 0


@pytest.mark.asyncio
async def test_delete_ingredient_never_hides_existing_stock_history() -> None:
    tenant = _tenant()
    ingredient = Ingredient(
        id=uuid4(),
        company_id=tenant.company_id,
        sku="HISTORY",
        name="Historical stock",
        base_unit="unit",
    )
    session = _Session(_Result(scalar=uuid4()), entity=ingredient)

    with pytest.raises(ConflictError, match="stock history"):
        await inventory_router.delete_ingredient(ingredient.id, session, tenant)

    assert ingredient.deleted_at is None
    assert len(session.statements) == 1


@pytest.mark.asyncio
async def test_recipe_yield_scales_consumption_per_sold_unit(monkeypatch) -> None:
    item_id = uuid4()
    ingredient_id = uuid4()
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item_id,
        name="Four portions",
        yield_qty=4,
        is_active=True,
    )
    line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient_id,
        qty=8,
        wastage_pct=0,
    )
    session = _Session(_Result(rows=[recipe]), _Result(rows=[line]))
    calls = []

    async def _capture(_session, **kwargs):
        calls.append(kwargs)
        return 1

    monkeypatch.setattr(deduction_service, "_deduct_ingredient", _capture)
    await deduction_service.deduct_for_order(
        session,
        order_id=uuid4(),
        order_lines=[SimpleNamespace(menu_item_id=item_id, qty=2)],
        branch_id=uuid4(),
        created_by=uuid4(),
    )

    # Eight units of ingredient make four menu units: two ingredient units per
    # sale, therefore a two-item order consumes four.
    assert calls[0]["qty_needed"] == pytest.approx(4)


def test_recipe_model_has_database_guards_for_yield_and_active_version() -> None:
    constraints = {constraint.name for constraint in Recipe.__table__.constraints}
    indexes = {index.name: index for index in Recipe.__table__.indexes}

    assert "ck_recipe_positive_yield" in constraints
    assert "uq_recipe_one_active_per_menu_item" in indexes
    active_index = indexes["uq_recipe_one_active_per_menu_item"]
    assert active_index.unique is True
    assert "is_active IS TRUE" in str(active_index.dialect_options["postgresql"]["where"])
