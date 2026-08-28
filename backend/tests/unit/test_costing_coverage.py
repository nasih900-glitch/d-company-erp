"""Regression tests for COGS catalogue completeness disclosure."""

from types import SimpleNamespace
from uuid import uuid4

from app.api.v1.insights.router import _build_costing_coverage, _fifo_costed_pairs

TEST_BRANCH_ID = uuid4()


def _item(name: str):
    return SimpleNamespace(id=uuid4(), sku=name.upper(), name=name, type="food")


def _recipe(item):
    return SimpleNamespace(id=uuid4(), menu_item_id=item.id)


def _line(recipe, ingredient):
    return (
        SimpleNamespace(recipe_id=recipe.id, ingredient_id=ingredient.id),
        ingredient,
    )


def _branch(name: str):
    return SimpleNamespace(id=uuid4(), name=name)


def test_costing_coverage_does_not_present_unknown_cost_as_zero() -> None:
    missing_recipe = _item("No recipe")
    empty_recipe = _item("Empty recipe")
    zero_cost = _item("Unknown ingredient cost")
    complete = _item("Fully costed")

    empty = _recipe(empty_recipe)
    unknown = _recipe(zero_cost)
    costed = _recipe(complete)
    free_ingredient = SimpleNamespace(id=uuid4(), name="Coffee", avg_cost_minor=0)
    costed_ingredient = SimpleNamespace(id=uuid4(), name="Milk", avg_cost_minor=25)

    result = _build_costing_coverage(
        [missing_recipe, empty_recipe, zero_cost, complete],
        [empty, unknown, costed],
        [_line(unknown, free_ingredient), _line(costed, costed_ingredient)],
        branch_id=TEST_BRANCH_ID,
    )

    assert result.inventory_item_count == 4
    assert result.fully_costed_item_count == 1
    assert result.incomplete_item_count == 3
    assert result.missing_recipe_count == 1
    assert result.empty_recipe_count == 1
    assert result.missing_ingredient_cost_count == 1
    assert result.is_complete is False
    assert {issue.issue for issue in result.issues} == {
        "missing_recipe",
        "empty_recipe",
        "missing_ingredient_cost",
    }
    unknown_issue = next(
        issue for issue in result.issues if issue.issue == "missing_ingredient_cost"
    )
    assert "Coffee" in unknown_issue.detail


def test_costing_coverage_is_complete_only_when_every_item_is_costed() -> None:
    item = _item("Tea")
    recipe = _recipe(item)
    ingredient = SimpleNamespace(id=uuid4(), name="Tea leaves", avg_cost_minor=10)

    result = _build_costing_coverage(
        [item],
        [recipe],
        [_line(recipe, ingredient)],
        branch_id=TEST_BRANCH_ID,
    )

    assert result.is_complete is True
    assert result.fully_costed_item_count == 1
    assert result.incomplete_item_count == 0
    assert result.issues == []


def test_empty_catalogue_is_not_reported_as_incomplete() -> None:
    result = _build_costing_coverage([], [], [], branch_id=TEST_BRANCH_ID)

    assert result.is_complete is True
    assert result.inventory_item_count == 0
    assert result.issues == []


def test_positive_average_without_a_costed_batch_is_still_unverified() -> None:
    item = _item("Coffee")
    recipe = _recipe(item)
    ingredient = SimpleNamespace(id=uuid4(), name="Beans", avg_cost_minor=30)
    branch = _branch("Main Cafe")

    result = _build_costing_coverage(
        [item],
        [recipe],
        [_line(recipe, ingredient)],
        branches=[branch],
        fifo_costed_pairs=set(),
        branch_id=branch.id,
    )

    assert result.is_complete is False
    assert result.missing_ingredient_cost_count == 1
    assert "Beans" in result.issues[0].detail
    assert "Main Cafe" in result.issues[0].detail


def test_costed_batch_in_another_branch_does_not_hide_missing_fifo_cost() -> None:
    item = _item("Coffee")
    recipe = _recipe(item)
    ingredient = SimpleNamespace(id=uuid4(), name="Beans", avg_cost_minor=30)
    main = _branch("Main Cafe")
    kiosk = _branch("Kiosk")

    result = _build_costing_coverage(
        [item],
        [recipe],
        [_line(recipe, ingredient)],
        branches=[main, kiosk],
        fifo_costed_pairs={(ingredient.id, main.id)},
        branch_id=main.id,
    )

    assert result.is_complete is False
    assert "Kiosk" in result.issues[0].detail


def test_fifo_cost_requires_every_positive_batch_to_have_a_cost() -> None:
    ingredient_id = uuid4()
    branch_id = uuid4()

    verified = _fifo_costed_pairs(
        [
            (ingredient_id, branch_id, 25),
            (ingredient_id, branch_id, 0),
        ],
        [(ingredient_id, branch_id, 25)],
    )

    assert (ingredient_id, branch_id) not in verified


def test_exhausted_stock_uses_only_latest_historical_batch_cost() -> None:
    ingredient_id = uuid4()
    branch_id = uuid4()

    assert _fifo_costed_pairs(
        [],
        [(ingredient_id, branch_id, 25)],
    ) == {(ingredient_id, branch_id)}
    assert _fifo_costed_pairs(
        [],
        [(ingredient_id, branch_id, 0)],
    ) == set()


def test_later_costed_stock_does_not_hide_unreversed_zero_cost_sales() -> None:
    ingredient_id = uuid4()
    branch_id = uuid4()
    pair = (ingredient_id, branch_id)

    assert _fifo_costed_pairs(
        [(ingredient_id, branch_id, 25)],
        [(ingredient_id, branch_id, 25)],
        unresolved_uncosted_pairs={pair},
    ) == set()
