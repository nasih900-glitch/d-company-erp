"""Database-free regressions for table lifecycle and gaming admin scope."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest

from app.api.v1.gaming import router as gaming_router
from app.api.v1.tables import router as tables_router
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.tenant import TenantContext


class _Result:
    def __init__(self, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = [] if rows is None else rows

    def scalar_one_or_none(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


class _Session:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)
        self.statements = []
        self.added = []
        self.deleted = []
        self.flush_count = 0

    async def execute(self, statement):
        self.statements.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    async def delete(self, entity) -> None:
        self.deleted.append(entity)

    async def flush(self) -> None:
        self.flush_count += 1


def _tenant(*, branch_id: UUID | None = None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=branch_id,
        terminal_id=uuid4(),
        roles=("owner",),
        protected_access=True,
    )


def _table() -> SimpleNamespace:
    return SimpleNamespace(
        id=uuid4(),
        floor_id=uuid4(),
        code="T1",
        seats=4,
        shape="rect",
        x=0,
        y=0,
        status="occupied",
    )


def _statement_values(statement) -> list[object]:
    return list(statement.compile().params.values())


def test_gaming_admin_requires_the_current_branch_and_rejects_an_override() -> None:
    with pytest.raises(BusinessRuleError, match="select a branch or terminal"):
        gaming_router._current_gaming_branch_id(_tenant())

    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    assert gaming_router._requested_gaming_branch_id(tenant, None) == branch_id
    assert gaming_router._requested_gaming_branch_id(tenant, branch_id) == branch_id
    with pytest.raises(NotFoundError, match="branch not found"):
        gaming_router._requested_gaming_branch_id(tenant, uuid4())


@pytest.mark.asyncio
async def test_station_lookup_is_company_branch_and_live_branch_scoped_and_locked() -> None:
    tenant = _tenant(branch_id=uuid4())
    station_id = uuid4()
    session = _Session(_Result())

    await gaming_router._operational_station(
        session,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        station_id=station_id,
        for_update=True,
    )

    statement = session.statements[0]
    sql = str(statement)
    values = _statement_values(statement)
    assert "JOIN branches" in sql
    assert "stations.company_id" in sql
    assert "stations.branch_id" in sql
    assert "branches.company_id" in sql
    assert "branches.deleted_at IS NULL" in sql
    assert "FOR UPDATE" in sql
    assert values.count(tenant.company_id) == 2
    assert tenant.branch_id in values
    assert station_id in values


@pytest.mark.asyncio
async def test_create_station_uses_only_the_validated_current_branch(monkeypatch) -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    branch = SimpleNamespace(id=branch_id, company_id=tenant.company_id, deleted_at=None)
    session = _Session(_Result(branch), _Result())
    monkeypatch.setattr(gaming_router, "require_pricing_unlock", lambda *_args: None)

    created = await gaming_router.create_station(
        gaming_router.StationCreate(
            code="PS5-1",
            name="PS5 One",
            type="ps5",
            rate_per_hour_minor=20_000,
            branch_id=branch_id,
        ),
        session,
        tenant,
        None,
    )

    branch_sql = str(session.statements[0])
    branch_values = _statement_values(session.statements[0])
    assert "branches.deleted_at IS NULL" in branch_sql
    assert "FOR UPDATE" in branch_sql
    assert tenant.company_id in branch_values
    assert branch_id in branch_values
    assert created.branch_id == branch_id
    assert session.added[0].branch_id == branch_id


@pytest.mark.asyncio
async def test_station_update_and_booking_cannot_reach_another_branch(monkeypatch) -> None:
    tenant = _tenant(branch_id=uuid4())
    station_id = uuid4()
    monkeypatch.setattr(gaming_router, "require_pricing_unlock", lambda *_args: None)

    update_session = _Session(_Result())
    with pytest.raises(NotFoundError, match="station not found"):
        await gaming_router.update_station(
            station_id,
            gaming_router.StationUpdate(rate_per_hour_minor=10_000),
            update_session,
            tenant,
            None,
        )
    assert tenant.branch_id in _statement_values(update_session.statements[0])
    assert "FOR UPDATE" in str(update_session.statements[0])

    delete_session = _Session(_Result())
    with pytest.raises(NotFoundError, match="station not found"):
        await gaming_router.delete_station(
            station_id,
            delete_session,
            tenant,
            None,
        )
    assert tenant.branch_id in _statement_values(delete_session.statements[0])
    assert "FOR UPDATE" in str(delete_session.statements[0])

    booking_session = _Session(_Result())
    with pytest.raises(NotFoundError, match="station not found"):
        await gaming_router.create_booking(
            gaming_router.BookingCreate(
                station_id=station_id,
                starts_at=datetime.now(UTC) + timedelta(hours=1),
                ends_at=datetime.now(UTC) + timedelta(hours=2),
                guest_name="Guest",
            ),
            booking_session,
            tenant,
        )
    assert tenant.branch_id in _statement_values(booking_session.statements[0])
    assert "FOR UPDATE" in str(booking_session.statements[0])


@pytest.mark.asyncio
async def test_table_cannot_be_marked_available_with_an_unfinished_order() -> None:
    tenant = _tenant(branch_id=uuid4())
    table = _table()
    session = _Session(_Result(table), _Result(uuid4()))

    with pytest.raises(ConflictError, match="open or held order"):
        await tables_router.update_status(
            table.id,
            tables_router.TableStatusUpdate(status="available"),
            session,
            tenant,
        )

    assert table.status == "occupied"
    table_lookup = session.statements[0]
    order_lookup = session.statements[1]
    assert "FOR UPDATE" in str(table_lookup)
    order_values = _statement_values(order_lookup)
    assert tenant.company_id in order_values
    assert tenant.branch_id in order_values
    assert ["open", "held"] in order_values


@pytest.mark.asyncio
async def test_table_delete_reports_current_reservation_before_history() -> None:
    tenant = _tenant(branch_id=uuid4())
    table = _table()
    session = _Session(
        _Result(table),
        _Result(),
        _Result(uuid4()),
    )

    with pytest.raises(ConflictError, match="active or future reservation"):
        await tables_router.delete_table(table.id, session, tenant)

    assert session.deleted == []
    reservation_statement = session.statements[2]
    reservation_values = _statement_values(reservation_statement)
    assert tenant.company_id in reservation_values
    assert tenant.branch_id in reservation_values
    assert ["held", "seated"] in reservation_values
    assert "reservations.ends_at" in str(reservation_statement)


@pytest.mark.asyncio
async def test_table_delete_preserves_historical_order_source_label() -> None:
    tenant = _tenant(branch_id=uuid4())
    table = _table()
    session = _Session(
        _Result(table),
        _Result(),
        _Result(),
        _Result(uuid4()),
    )

    with pytest.raises(ConflictError, match="source label must remain auditable"):
        await tables_router.delete_table(table.id, session, tenant)

    assert session.deleted == []
    history_values = _statement_values(session.statements[3])
    assert tenant.company_id in history_values
    assert tenant.branch_id in history_values
