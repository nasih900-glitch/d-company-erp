"""Database-free test for create_recipe's concurrent-request race guard.

Mirrors test_attendance.py's fake-session pattern for the identical class of
bug just fixed on clock_in(): create_recipe() checked for an existing
is_active=True Recipe on the same menu item with a plain SELECT and no row
lock behind it, so two near-simultaneous "create recipe" calls for the same
item could both pass the check and insert two active rows —
deduct_for_order's dict-comprehension recipe lookup then arbitrarily picks
one with no defined order.

The fix locks the parent MenuItem row with `.with_for_update()` before the
existing-active check, same lock-the-parent-row pattern as clock_in()
locking User before checking Attendance. This test asserts the actual query
shape (first statement is a `SELECT ... FOR UPDATE` on the menu_items row,
ahead of the existing-active check) and that a recipe already visible after
the lock is acquired is still rejected with ConflictError.
"""

from __future__ import annotations

from uuid import UUID, uuid4

import pytest

from app.api.v1.inventory.router import RecipeCreate, create_recipe
from app.core.errors import ConflictError
from app.core.tenant import TenantContext
from app.models import MenuItem, Recipe

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("33333333-3333-3333-3333-333333333333")
MENU_ITEM_ID = UUID("44444444-4444-4444-4444-444444444444")


class _Result:
    def __init__(self, *, rows: list | None = None, scalar=None) -> None:
        self.rows = [] if rows is None else rows
        self.scalar = scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows

    def scalar_one_or_none(self):
        return self.scalar


class _Session:
    """Queued-result fake session — matches the order create_recipe issues
    statements in; `.get()` is hardcoded to the one menu-item lookup it
    needs (no recipe lines in these tests, so no ingredient lookups).
    """

    def __init__(self, *, menu_item: MenuItem) -> None:
        self.menu_item = menu_item
        self.results: list[_Result] = []
        self.added: list = []
        self.flushes = 0
        self.statements: list = []

    def queue(self, result: _Result) -> None:
        self.results.append(result)

    async def get(self, model, key):
        assert model is MenuItem and key == self.menu_item.id
        return self.menu_item

    async def execute(self, statement):
        self.statements.append(statement)
        assert self.results, f"unexpected statement: {statement}"
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    def add_all(self, entities) -> None:
        self.added.extend(entities)

    async def flush(self) -> None:
        self.flushes += 1


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=None,
        roles=("owner",),
    )


def _menu_item() -> MenuItem:
    return MenuItem(
        id=MENU_ITEM_ID,
        company_id=COMPANY_ID,
        category_id=uuid4(),
        sku="SKU-1",
        name="Cappuccino",
        type="drink",
        base_price_minor=15000,
        tax_rate=0.05,
    )


@pytest.mark.asyncio
async def test_create_recipe_locks_the_menu_item_row_before_the_active_check() -> None:
    """Asserts the actual query shape: the first statement create_recipe()
    issues is a `SELECT ... FOR UPDATE` on the menu_items row, ahead of the
    existing-active-recipe check.
    """
    session = _Session(menu_item=_menu_item())
    tenant = _tenant()

    session.queue(_Result())  # menu-item-row lock
    session.queue(_Result(scalar=None))  # existing-active check: none yet
    session.queue(_Result(scalar=None))  # max-version check: no prior recipe

    result = await create_recipe(
        RecipeCreate(menu_item_id=MENU_ITEM_ID, name="Cappuccino recipe"),
        session,
        tenant,
    )
    assert result.version == 1
    assert len(session.added) == 1  # just the Recipe row, no lines

    assert len(session.statements) == 3
    lock_stmt, active_check_stmt, version_stmt = session.statements

    sql = str(lock_stmt)
    assert "FOR UPDATE" in sql.upper()
    assert "menu_items" in sql.lower()
    assert MENU_ITEM_ID in list(lock_stmt.compile().params.values())

    # The existing-active check runs after the lock is acquired, not before.
    assert "FOR UPDATE" not in str(active_check_stmt).upper()
    assert "FOR UPDATE" not in str(version_stmt).upper()


@pytest.mark.asyncio
async def test_create_recipe_rejects_when_active_recipe_is_visible_after_the_lock() -> None:
    """Once the menu-item lock is acquired, a concurrent recipe that
    committed while this request waited must still be caught by the
    existing-active check and rejected with ConflictError — not silently
    raced past into a second active row.
    """
    session = _Session(menu_item=_menu_item())
    tenant = _tenant()

    other_recipe = Recipe(
        id=uuid4(),
        menu_item_id=MENU_ITEM_ID,
        name="Cappuccino recipe (v1, from the other request)",
        yield_qty=1,
        version=1,
        is_active=True,
    )

    session.queue(_Result())  # menu-item-row lock (blocks until commit, then acquired)
    session.queue(_Result(scalar=other_recipe))  # existing-active check now sees it

    with pytest.raises(ConflictError):
        await create_recipe(
            RecipeCreate(menu_item_id=MENU_ITEM_ID, name="Cappuccino recipe"),
            session,
            tenant,
        )
    assert session.added == []  # nothing inserted once rejected
