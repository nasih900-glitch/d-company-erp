"""True-unit coverage for branch-safe Tables and Kitchen workflows."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.api.v1.kitchen.router import (
    StateUpdate,
    _next_ready_at,
    _set_line_status,
    _state_from_lines,
    _validate_state_transition,
    kitchen_queue,
    set_kitchen_state,
)
from app.api.v1.pos.router import _materialize_legacy_kitchen_line_state
from app.api.v1.tables.router import (
    _current_branch_id,
    _ensure_default_floor,
    _requested_branch_id,
    _tenant_floor,
    _tenant_table,
)
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.tenant import TenantContext
from app.models import Branch, Floor


def _tenant(*, branch_id: UUID | None = None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=branch_id,
        terminal_id=uuid4(),
        roles=("owner",),
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
        self.statements = []
        self.entity = entity
        self.added = []

    async def get(self, _model, _entity_id):
        return self.entity

    def add(self, entity) -> None:
        self.added.append(entity)

    async def execute(self, statement):
        self.statements.append(statement)
        return self.results.pop(0)

    async def flush(self) -> None:
        return None


def _statement_values(statement) -> list[object]:
    return list(statement.compile().params.values())


def test_tables_require_an_explicit_current_branch() -> None:
    with pytest.raises(BusinessRuleError, match="select a branch or terminal"):
        _current_branch_id(_tenant())


def test_floor_creation_cannot_target_another_branch() -> None:
    current_branch = uuid4()
    tenant = _tenant(branch_id=current_branch)

    assert _requested_branch_id(tenant, None) == current_branch
    assert _requested_branch_id(tenant, current_branch) == current_branch
    with pytest.raises(NotFoundError, match="branch not found"):
        _requested_branch_id(tenant, uuid4())


@pytest.mark.asyncio
async def test_default_floor_is_created_on_the_current_branch_only() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    branch = Branch(
        id=branch_id,
        company_id=tenant.company_id,
        name="Current Branch",
        invoice_series_code="MN",
    )
    session = _Session(_Result(), entity=branch)

    floor_id = await _ensure_default_floor(session, tenant)

    assert len(session.added) == 1
    assert isinstance(session.added[0], Floor)
    assert session.added[0].id == floor_id
    assert session.added[0].branch_id == branch_id


@pytest.mark.asyncio
async def test_floor_and_table_lookups_bind_company_and_branch() -> None:
    company_id = uuid4()
    branch_id = uuid4()

    floor_session = _Session(_Result())
    await _tenant_floor(floor_session, company_id, branch_id, uuid4())
    floor_sql = str(floor_session.statements[0])
    floor_values = _statement_values(floor_session.statements[0])
    assert "floors.branch_id" in floor_sql
    assert "branches.company_id" in floor_sql
    assert company_id in floor_values
    assert branch_id in floor_values

    table_session = _Session(_Result())
    await _tenant_table(table_session, company_id, branch_id, uuid4())
    table_sql = str(table_session.statements[0])
    table_values = _statement_values(table_session.statements[0])
    assert "floors.branch_id" in table_sql
    assert "branches.company_id" in table_sql
    assert company_id in table_values
    assert branch_id in table_values


@pytest.mark.asyncio
async def test_kitchen_queue_is_branch_scoped_includes_held_and_returns_notes() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now,
        kitchen_state=None,
    )
    line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note="No onions",
        kitchen_status="queued",
        created_at=now,
    )
    item = SimpleNamespace(id=uuid4(), name="Sandwich", type="food")
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(line, item)]),
    )

    queue = await kitchen_queue(session, tenant)

    order_statement = session.statements[0]
    order_values = _statement_values(order_statement)
    assert branch_id in order_values
    assert ["paid", "open", "held"] in order_values
    assert queue[0].lines[0].notes == "No onions"


@pytest.mark.asyncio
async def test_kitchen_queue_uses_active_lines_and_late_line_wait_time() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now - timedelta(days=2),
        # add_order_lines resets the compatibility mirror for this new batch.
        kitchen_state="received",
    )
    served_line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note=None,
        kitchen_status="served",
        created_at=now - timedelta(days=2),
    )
    late_line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=2,
        note="Late batch",
        kitchen_status="queued",
        created_at=now - timedelta(minutes=4),
    )
    served_item = SimpleNamespace(id=uuid4(), name="Old Toast", type="food")
    late_item = SimpleNamespace(id=uuid4(), name="Late Fries", type="food")
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(served_line, served_item), (late_line, late_item)]),
    )

    queue = await kitchen_queue(session, tenant)

    assert len(queue) == 1
    assert queue[0].kitchen_state == "received"
    assert [line.name for line in queue[0].lines] == ["Late Fries"]
    assert 3 <= queue[0].minutes_waiting <= 5
    assert "orders.opened_at" not in str(session.statements[0].whereclause)


@pytest.mark.asyncio
async def test_kitchen_queue_hides_orders_when_every_kitchen_line_is_served() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now,
        kitchen_state=None,
    )
    line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note=None,
        kitchen_status="served",
        created_at=now,
    )
    item = SimpleNamespace(id=uuid4(), name="Toast", type="food")
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(line, item)]),
    )

    assert await kitchen_queue(session, tenant) == []


@pytest.mark.asyncio
async def test_served_history_uses_line_completion_not_order_open_date() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now - timedelta(days=1),
        kitchen_state="served",
        status="open",
    )
    line = SimpleNamespace(
        id=uuid4(),
        client_line_id=uuid4(),
        order_id=order_id,
        menu_item_id=uuid4(),
        qty=1,
        note="Crossed midnight",
        kitchen_status="served",
        kitchen_released_at=now - timedelta(minutes=10),
        kitchen_served_at=now,
        kitchen_round_no=1,
        variant_snapshot=None,
        modifiers=[],
        voided_at=None,
        created_at=now - timedelta(days=1),
    )
    item = SimpleNamespace(id=line.menu_item_id, name="Toast", type="food")
    session = _Session(
        _Result(scalar="Asia/Kolkata"),
        _Result(rows=[order]),
        _Result(rows=[(line, item)]),
    )

    history = await kitchen_queue(session, tenant, include_served=True)

    assert [ticket.id for ticket in history] == [order_id]
    assert history[0].kitchen_state == "served"
    order_filter = str(session.statements[1].whereclause)
    line_filter = str(session.statements[2].whereclause)
    assert "order_lines.kitchen_served_at" in order_filter
    assert "orders.opened_at" not in order_filter
    assert "order_lines.kitchen_served_at" in line_filter


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("legacy_state", "order_status"),
    [("served", "paid"), (None, "paid"), (None, "refunded"), (None, "void")],
)
async def test_kitchen_queue_does_not_resurface_legacy_completed_orders(
    legacy_state: str | None,
    order_status: str,
) -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now - timedelta(days=3),
        kitchen_state=legacy_state,
        status=order_status,
    )
    # This is how every line looks in production under the old KDS: the
    # order mirror advanced, but the DB default on each line stayed queued.
    line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note=None,
        kitchen_status="queued",
        created_at=now - timedelta(days=3),
    )
    item = SimpleNamespace(id=uuid4(), name="Old Toast", type="food")
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(line, item)]),
    )

    assert await kitchen_queue(session, tenant) == []


@pytest.mark.asyncio
async def test_kitchen_queue_preserves_legacy_unfinished_null_state_as_queued() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now,
        kitchen_state=None,
        status="held",
    )
    line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note=None,
        kitchen_status="queued",
        created_at=now,
    )
    item = SimpleNamespace(id=uuid4(), name="Pending Toast", type="food")
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(line, item)]),
    )

    queue = await kitchen_queue(session, tenant)

    assert len(queue) == 1
    assert queue[0].kitchen_state == "received"


@pytest.mark.asyncio
async def test_kitchen_state_lookup_hides_orders_from_other_branches() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    session = _Session(_Result())

    with pytest.raises(NotFoundError, match="order not found"):
        await set_kitchen_state(uuid4(), StateUpdate(state="ready"), session, tenant)

    statement = session.statements[0]
    assert "orders.branch_id" in str(statement)
    statement_values = _statement_values(statement)
    assert tenant.company_id in statement_values
    assert branch_id in statement_values


@pytest.mark.asyncio
async def test_kitchen_transition_locks_order_and_advances_only_lagging_lines() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now - timedelta(minutes=10),
        kitchen_state="ready",
        kitchen_ready_at=now - timedelta(minutes=2),
    )
    late_line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note="Late",
        kitchen_status="queued",
        created_at=now - timedelta(minutes=3),
    )
    ready_line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note=None,
        kitchen_status="ready",
        created_at=now - timedelta(minutes=10),
    )
    late_item = SimpleNamespace(id=uuid4(), name="Fries", type="food")
    ready_item = SimpleNamespace(id=uuid4(), name="Burger", type="food")
    session = _Session(
        _Result(scalar=order),
        _Result(rows=[(late_line, late_item), (ready_line, ready_item)]),
    )

    result = await set_kitchen_state(
        order_id,
        StateUpdate(state="preparing"),
        session,
        tenant,
    )

    assert "FOR UPDATE" in str(session.statements[0]).upper()
    assert late_line.kitchen_status == "cooking"
    assert ready_line.kitchen_status == "ready"
    assert result.kitchen_state == "preparing"
    assert order.kitchen_state == "preparing"
    assert order.kitchen_ready_at is None


@pytest.mark.asyncio
async def test_kitchen_transition_materializes_legacy_ready_lines_before_serving() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    order_id = uuid4()
    now = datetime.now(UTC)
    order = SimpleNamespace(
        id=order_id,
        invoice_no=None,
        type="dine_in",
        table_id=None,
        customer_name=None,
        opened_at=now,
        status="open",
        kitchen_state="ready",
        kitchen_ready_at=now,
    )
    legacy_line = SimpleNamespace(
        id=uuid4(),
        order_id=order_id,
        qty=1,
        note=None,
        kitchen_status="queued",
        created_at=now,
    )
    item = SimpleNamespace(id=uuid4(), name="Legacy Burger", type="food")
    session = _Session(
        _Result(scalar=order),
        _Result(rows=[(legacy_line, item)]),
    )

    result = await set_kitchen_state(
        order_id,
        StateUpdate(state="served"),
        session,
        tenant,
    )

    assert legacy_line.kitchen_status == "served"
    assert legacy_line.kitchen_served_at is not None
    assert result.kitchen_state == "served"


@pytest.mark.asyncio
async def test_late_append_materializes_old_served_batch_before_new_line() -> None:
    order = SimpleNamespace(
        id=uuid4(),
        company_id=uuid4(),
        kitchen_state="served",
    )
    old_line = SimpleNamespace(kitchen_status="queued")
    session = _Session(_Result(rows=[old_line]))

    await _materialize_legacy_kitchen_line_state(session, order)

    assert old_line.kitchen_status == "served"
    statement = session.statements[0]
    assert "order_lines.order_id" in str(statement)
    assert "menu_items.company_id" in str(statement)


def test_kitchen_state_is_derived_from_least_advanced_active_line() -> None:
    lines = [
        SimpleNamespace(kitchen_status="ready"),
        SimpleNamespace(kitchen_status="queued"),
        SimpleNamespace(kitchen_status="cooking"),
    ]

    assert _state_from_lines(lines) == "received"


def test_kitchen_state_transitions_are_idempotent_but_cannot_skip() -> None:
    _validate_state_transition("received", "received")
    _validate_state_transition("received", "preparing")

    with pytest.raises(BusinessRuleError, match="cannot skip"):
        _validate_state_transition("received", "ready")
    with pytest.raises(BusinessRuleError, match="back"):
        _validate_state_transition("ready", "preparing")


def test_ready_timestamp_is_idempotent_and_retained_after_service() -> None:
    first_ready_at = datetime(2026, 7, 15, 10, tzinfo=UTC)
    retry_at = datetime(2026, 7, 15, 10, 1, tzinfo=UTC)

    assert _next_ready_at("preparing", "ready", None, first_ready_at) == first_ready_at
    assert _next_ready_at("ready", "ready", first_ready_at, retry_at) == first_ready_at
    assert _next_ready_at("ready", "served", first_ready_at, retry_at) == first_ready_at


def test_served_line_timestamp_is_set_once_and_cleared_before_service() -> None:
    first = datetime(2026, 7, 15, 10, tzinfo=UTC)
    retry = first + timedelta(minutes=1)
    line = SimpleNamespace(kitchen_status="ready", kitchen_served_at=None)

    _set_line_status(line, "served", first)
    _set_line_status(line, "served", retry)

    assert line.kitchen_status == "served"
    assert line.kitchen_served_at == first

    _set_line_status(line, "ready", retry)
    assert line.kitchen_served_at is None


def test_pre_ready_states_clear_stale_ready_timestamp() -> None:
    stale = datetime(2026, 7, 15, 9, tzinfo=UTC)
    now = datetime(2026, 7, 15, 10, tzinfo=UTC)

    assert _next_ready_at("received", "received", stale, now) is None
    assert _next_ready_at("received", "preparing", stale, now) is None
