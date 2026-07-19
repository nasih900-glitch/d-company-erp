"""Database-free route regressions for reservation / gaming-booking visibility.

Reservation (table bookings) and GamingBooking (station bookings) had a
create endpoint each but no way to see or act on what got created — a
booking went into the database and was never seen again. These tests cover
the list (date range + status filter) and status-transition endpoints added
to close that gap, including that an invalid transition (e.g. cancelling an
already-settled booking) is rejected instead of silently overwriting it.
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from uuid import UUID, uuid4

import pytest

from app.api.v1.gaming import router as gaming_router
from app.api.v1.tables import router as tables_router
from app.core.errors import ConflictError, NotFoundError
from app.core.tenant import TenantContext
from app.models import GamingBooking, Reservation


class _Result:
    def __init__(self, *, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = [] if rows is None else rows

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


class _Session:
    def __init__(self, *results: _Result) -> None:
        self.results = list(results)
        self.statements = []
        self.flush_count = 0

    async def execute(self, statement):
        self.statements.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    async def flush(self) -> None:
        self.flush_count += 1


def _statement_values(statement) -> list[object]:
    return list(statement.compile().params.values())


def _tenant(*, company_id: UUID | None = None, branch_id: UUID | None = None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=company_id or uuid4(),
        branch_id=branch_id or uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
    )


def _reservation(tenant: TenantContext, **overrides) -> Reservation:
    values = {
        "id": uuid4(),
        "table_id": uuid4(),
        "created_by": tenant.user_id,
        "guest_name": "Anoop",
        "party_size": 4,
        "contact": "9000000000",
        "starts_at": datetime(2026, 7, 20, 19, 0, tzinfo=UTC),
        "ends_at": datetime(2026, 7, 20, 20, 30, tzinfo=UTC),
        "notes": None,
        "status": "held",
        "created_at": datetime(2026, 7, 19, 12, 0, tzinfo=UTC),
    }
    values.update(overrides)
    return Reservation(**values)


def _booking(tenant: TenantContext, **overrides) -> GamingBooking:
    values = {
        "id": uuid4(),
        "station_id": uuid4(),
        "starts_at": datetime(2026, 7, 20, 19, 0, tzinfo=UTC),
        "ends_at": datetime(2026, 7, 20, 20, 0, tzinfo=UTC),
        "guest_name": "Fahad",
        "contact": "9000000001",
        "party_size": 2,
        "deposit_minor": 10_000,
        "status": "held",
        "created_by": tenant.user_id,
        "created_at": datetime(2026, 7, 19, 12, 0, tzinfo=UTC),
    }
    values.update(overrides)
    return GamingBooking(**values)


# ============================================================================
# Reservations — GET /tables/reservations
# ============================================================================
@pytest.mark.asyncio
async def test_list_reservations_filters_by_status_and_explicit_date_range() -> None:
    tenant = _tenant()
    r = _reservation(tenant)
    session = _Session(
        _Result(scalar="Asia/Kolkata"),  # company_timezone lookup
        _Result(rows=[(r, "T1")]),        # main list query
    )

    out = await tables_router.list_reservations(
        session,
        tenant,
        from_date=date(2026, 7, 20),
        to_date=date(2026, 7, 21),
        status_filter=["held", "seated"],
        limit=200,
    )

    assert len(out) == 1
    assert out[0].id == r.id
    assert out[0].table_code == "T1"
    assert out[0].guest_name == "Anoop"

    list_stmt = session.statements[1]
    where_sql = str(list_stmt.whereclause)
    assert "reservations.status IN" in where_sql
    assert "reservations.starts_at >=" in where_sql
    assert "reservations.starts_at <" in where_sql
    values = _statement_values(list_stmt)
    assert ["held", "seated"] in values


@pytest.mark.asyncio
async def test_list_reservations_defaults_to_upcoming_only() -> None:
    tenant = _tenant()
    session = _Session(
        _Result(scalar="Asia/Kolkata"),
        _Result(rows=[]),
    )

    out = await tables_router.list_reservations(
        session, tenant, status_filter=None, limit=200,
    )

    assert out == []
    # No status filter requested -> the WHERE clause never touches status,
    # and no upper bound is applied (to_date omitted -> open-ended future).
    list_stmt = session.statements[1]
    where_sql = str(list_stmt.whereclause)
    assert "status" not in where_sql
    assert "reservations.starts_at >=" in where_sql
    assert "reservations.starts_at <" not in where_sql


@pytest.mark.asyncio
async def test_reservation_status_transition_held_to_seated_succeeds() -> None:
    tenant = _tenant()
    r = _reservation(tenant, status="held")
    session = _Session(
        _Result(scalar=r),       # _tenant_reservation lookup
        _Result(scalar="T7"),    # table_code lookup
    )

    out = await tables_router.update_reservation_status(
        r.id,
        tables_router.ReservationStatusUpdate(status="seated"),
        session,
        tenant,
    )

    assert out.status == "seated"
    assert r.status == "seated"
    assert session.flush_count == 1


@pytest.mark.asyncio
async def test_reservation_status_transition_rejects_cancelling_an_already_settled_row() -> None:
    tenant = _tenant()
    r = _reservation(tenant, status="cancelled")
    session = _Session(_Result(scalar=r))  # only the lookup — no mutation follows

    with pytest.raises(ConflictError, match="cannot change reservation"):
        await tables_router.update_reservation_status(
            r.id,
            tables_router.ReservationStatusUpdate(status="cancelled"),
            session,
            tenant,
        )

    # Rejected before any write — status is untouched and nothing was flushed.
    assert r.status == "cancelled"
    assert session.flush_count == 0


@pytest.mark.asyncio
async def test_reservation_status_transition_404s_outside_the_caller_branch() -> None:
    tenant = _tenant()
    session = _Session(_Result(scalar=None))

    with pytest.raises(NotFoundError, match="reservation not found"):
        await tables_router.update_reservation_status(
            uuid4(),
            tables_router.ReservationStatusUpdate(status="cancelled"),
            session,
            tenant,
        )


# ============================================================================
# Gaming bookings — GET /gaming/bookings
# ============================================================================
@pytest.mark.asyncio
async def test_list_bookings_filters_by_status_and_explicit_date_range() -> None:
    tenant = _tenant()
    bk = _booking(tenant)
    session = _Session(
        _Result(scalar="Asia/Kolkata"),
        _Result(rows=[(bk, "PS5-1")]),
    )

    out = await gaming_router.list_bookings(
        session,
        tenant,
        from_date=date(2026, 7, 20),
        to_date=date(2026, 7, 21),
        status_filter=["held"],
        limit=200,
    )

    assert len(out) == 1
    assert out[0].id == bk.id
    assert out[0].station_code == "PS5-1"

    list_stmt = session.statements[1]
    assert "gaming_bookings.status IN" in str(list_stmt.whereclause)
    values = _statement_values(list_stmt)
    assert ["held"] in values


@pytest.mark.asyncio
async def test_booking_status_transition_held_to_consumed_succeeds() -> None:
    tenant = _tenant()
    bk = _booking(tenant, status="held")
    session = _Session(
        _Result(scalar=bk),
        _Result(scalar="PS5-2"),
    )

    out = await gaming_router.update_booking_status(
        bk.id,
        gaming_router.BookingStatusUpdate(status="consumed"),
        session,
        tenant,
    )

    assert out.status == "consumed"
    assert bk.status == "consumed"
    assert session.flush_count == 1


@pytest.mark.asyncio
async def test_booking_status_transition_rejects_cancelling_an_already_completed_booking() -> None:
    tenant = _tenant()
    bk = _booking(tenant, status="consumed")
    session = _Session(_Result(scalar=bk))

    with pytest.raises(ConflictError, match="cannot change booking"):
        await gaming_router.update_booking_status(
            bk.id,
            gaming_router.BookingStatusUpdate(status="cancelled"),
            session,
            tenant,
        )

    assert bk.status == "consumed"
    assert session.flush_count == 0
