"""Tables / floors / reservations — full CRUD.

Endpoints:
  GET    /tables/floors                    — list floors
  POST   /tables/floors                    — create floor (auto-creates one if none)
  GET    /tables                           — list tables (across floors)
  POST   /tables                           — create a new table
  PATCH  /tables/{id}                      — rename / resize / move
  PATCH  /tables/{id}/status               — quick status change (occupied/free)
  DELETE /tables/{id}                      — soft delete
  GET    /tables/reservations              — list reservations (date range + status filter)
  POST   /tables/reservations              — create a reservation
  PATCH  /tables/reservations/{id}/status  — transition a reservation's status
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import Branch, Floor, Order, Reservation, Table

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class FloorRead(BaseModel):
    id: UUID
    branch_id: UUID
    name: str


class FloorCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    branch_id: UUID | None = None  # must match the current operational branch


class TableRead(BaseModel):
    id: UUID
    floor_id: UUID
    code: str
    seats: int
    shape: str
    x: float
    y: float
    status: str


class TableCreate(BaseModel):
    floor_id: UUID | None = None  # auto-resolve if only one floor
    code: str = Field(min_length=1, max_length=20)
    seats: int = Field(default=2, gt=0, le=20)
    shape: Literal["rect", "round", "booth"] = "rect"
    x: float = 0
    y: float = 0


class TableUpdate(BaseModel):
    code: str | None = Field(default=None, min_length=1, max_length=20)
    seats: int | None = Field(default=None, gt=0, le=20)
    shape: Literal["rect", "round", "booth"] | None = None
    x: float | None = None
    y: float | None = None


class TableStatusUpdate(BaseModel):
    status: Literal["available", "occupied", "reserved", "cleaning", "merged"]


class ReservationCreate(BaseModel):
    table_id: UUID
    guest_name: str = Field(min_length=1, max_length=200)
    party_size: int = Field(gt=0)
    contact: str | None = Field(default=None, max_length=50)
    starts_at: datetime
    ends_at: datetime
    notes: str | None = Field(default=None, max_length=500)


class ReservationRead(BaseModel):
    id: UUID
    table_id: UUID
    table_code: str
    guest_name: str
    party_size: int
    contact: str | None
    starts_at: datetime
    ends_at: datetime
    notes: str | None
    status: str
    created_at: datetime


class ReservationStatusUpdate(BaseModel):
    # "held" is the creation-only state — not a valid PATCH target, so it's
    # deliberately excluded here (see _RESERVATION_TRANSITIONS below).
    status: Literal["seated", "no_show", "cancelled"]


# A reservation only ever transitions once, out of "held" — every other
# status is terminal. Keyed by *current* status so an invalid transition
# (e.g. cancelling an already-seated/no-show/cancelled reservation) is
# rejected instead of silently overwriting a settled outcome.
_RESERVATION_TRANSITIONS: dict[str, set[str]] = {
    "held": {"seated", "no_show", "cancelled"},
}


# ---------------------------------------------------------------- helpers
def _current_branch_id(tenant: TenantContext) -> UUID:
    if tenant.branch_id is None:
        raise BusinessRuleError("select a branch or terminal before managing tables")
    return tenant.branch_id


def _requested_branch_id(tenant: TenantContext, requested_branch_id: UUID | None) -> UUID:
    branch_id = _current_branch_id(tenant)
    if requested_branch_id is not None and requested_branch_id != branch_id:
        # Do not allow a caller to use an arbitrary branch UUID to escape the
        # branch selected by their token / terminal context.
        raise NotFoundError("branch not found")
    return branch_id


async def _ensure_default_floor(session, tenant: TenantContext) -> UUID:
    """Return the first floor for the current branch, creating one if needed."""
    branch_id = _current_branch_id(tenant)
    branch = await session.get(Branch, branch_id)
    if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    floor_id = (
        await session.execute(
            select(Floor.id)
            .join(Branch, Branch.id == Floor.branch_id)
            .where(
                Branch.company_id == tenant.company_id,
                Branch.id == branch_id,
                Branch.deleted_at.is_(None),
            )
            .order_by(Floor.created_at)
            .limit(1)
        )
    ).scalar_one_or_none()
    if floor_id:
        return floor_id
    floor = Floor(id=uuid4(), branch_id=branch_id, name="Main Floor")
    session.add(floor)
    await session.flush()
    return floor.id


async def _tenant_floor(
    session,
    company_id: UUID,
    branch_id: UUID,
    floor_id: UUID,
) -> Floor | None:
    return (
        await session.execute(
            select(Floor)
            .join(Branch, Branch.id == Floor.branch_id)
            .where(
                Floor.id == floor_id,
                Floor.branch_id == branch_id,
                Branch.company_id == company_id,
                Branch.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()


async def _tenant_table(
    session,
    company_id: UUID,
    branch_id: UUID,
    table_id: UUID,
    *,
    for_update: bool = False,
) -> Table | None:
    stmt = (
        select(Table)
        .join(Floor, Floor.id == Table.floor_id)
        .join(Branch, Branch.id == Floor.branch_id)
        .where(
            Table.id == table_id,
            Floor.branch_id == branch_id,
            Branch.company_id == company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if for_update:
        stmt = stmt.with_for_update()
    return (await session.execute(stmt)).scalar_one_or_none()


async def _tenant_reservation(
    session,
    company_id: UUID,
    branch_id: UUID,
    reservation_id: UUID,
    *,
    for_update: bool = False,
) -> Reservation | None:
    stmt = (
        select(Reservation)
        .join(Table, Table.id == Reservation.table_id)
        .join(Floor, Floor.id == Table.floor_id)
        .join(Branch, Branch.id == Floor.branch_id)
        .where(
            Reservation.id == reservation_id,
            Floor.branch_id == branch_id,
            Branch.company_id == company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if for_update:
        stmt = stmt.with_for_update()
    return (await session.execute(stmt)).scalar_one_or_none()


async def _table_has_unfinished_order(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    table_id: UUID,
) -> bool:
    order_id = (
        await session.execute(
            select(Order.id)
            .where(
                Order.company_id == company_id,
                Order.branch_id == branch_id,
                Order.table_id == table_id,
                Order.status.in_(("open", "held")),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    return order_id is not None


async def _table_has_active_or_future_reservation(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    table_id: UUID,
    now: datetime | None = None,
) -> bool:
    reservation_id = (
        await session.execute(
            select(Reservation.id)
            .join(Table, Table.id == Reservation.table_id)
            .join(Floor, Floor.id == Table.floor_id)
            .join(Branch, Branch.id == Floor.branch_id)
            .where(
                Reservation.table_id == table_id,
                Reservation.status.in_(("held", "seated")),
                Reservation.ends_at > (now or datetime.now(UTC)),
                Floor.branch_id == branch_id,
                Branch.company_id == company_id,
                Branch.deleted_at.is_(None),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    return reservation_id is not None


async def _table_has_source_history(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    table_id: UUID,
) -> bool:
    """Keep the table label attached to every historical order/reservation."""
    order_id = (
        await session.execute(
            select(Order.id)
            .where(
                Order.company_id == company_id,
                Order.branch_id == branch_id,
                Order.table_id == table_id,
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    if order_id is not None:
        return True

    reservation_id = (
        await session.execute(
            select(Reservation.id)
            .join(Table, Table.id == Reservation.table_id)
            .join(Floor, Floor.id == Table.floor_id)
            .join(Branch, Branch.id == Floor.branch_id)
            .where(
                Reservation.table_id == table_id,
                Floor.branch_id == branch_id,
                Branch.company_id == company_id,
                Branch.deleted_at.is_(None),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    return reservation_id is not None


# ---------------------------------------------------------------- FLOORS
@router.get("/floors", response_model=list[FloorRead])
async def list_floors(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.read")),
) -> list[FloorRead]:
    branch_id = _current_branch_id(tenant)
    rows = (
        await session.execute(
            select(Floor)
            .join(Branch, Branch.id == Floor.branch_id)
            .where(
                Branch.company_id == tenant.company_id,
                Branch.id == branch_id,
                Branch.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    return [FloorRead(id=r.id, branch_id=r.branch_id, name=r.name) for r in rows]


@router.post("/floors", response_model=FloorRead, status_code=status.HTTP_201_CREATED)
async def create_floor(
    payload: FloorCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.write")),
) -> FloorRead:
    branch_id = _requested_branch_id(tenant, payload.branch_id)
    branch = await session.get(Branch, branch_id)
    if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    f = Floor(id=uuid4(), branch_id=branch_id, name=payload.name)
    session.add(f)
    await session.flush()
    return FloorRead(id=f.id, branch_id=f.branch_id, name=f.name)


# ---------------------------------------------------------------- TABLES
@router.get("", response_model=list[TableRead])
@router.get("/", response_model=list[TableRead])
async def list_tables(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.read")),
    floor_id: UUID | None = None,
) -> list[TableRead]:
    branch_id = _current_branch_id(tenant)
    stmt = (
        select(Table)
        .join(Floor, Floor.id == Table.floor_id)
        .join(Branch, Branch.id == Floor.branch_id)
        .where(
            Branch.company_id == tenant.company_id,
            Branch.id == branch_id,
            Branch.deleted_at.is_(None),
        )
    )
    if floor_id:
        stmt = stmt.where(Table.floor_id == floor_id)
    rows = (await session.execute(stmt)).scalars().all()
    return [
        TableRead(
            id=r.id, floor_id=r.floor_id, code=r.code, seats=r.seats,
            shape=r.shape, x=float(r.x), y=float(r.y), status=r.status,
        )
        for r in rows
    ]


@router.post("", response_model=TableRead, status_code=status.HTTP_201_CREATED)
async def create_table(
    payload: TableCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.write")),
) -> TableRead:
    branch_id = _current_branch_id(tenant)
    floor_id = payload.floor_id or await _ensure_default_floor(session, tenant)
    if not await _tenant_floor(session, tenant.company_id, branch_id, floor_id):
        raise NotFoundError("floor not found")
    existing = (
        await session.execute(
            select(Table).where(Table.floor_id == floor_id, Table.code == payload.code)
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError(f"a table with code '{payload.code}' already exists on this floor")
    t = Table(
        id=uuid4(),
        floor_id=floor_id,
        code=payload.code,
        seats=payload.seats,
        shape=payload.shape,
        x=payload.x, y=payload.y,
        status="available",
    )
    session.add(t)
    await session.flush()
    return TableRead(
        id=t.id, floor_id=t.floor_id, code=t.code, seats=t.seats,
        shape=t.shape, x=float(t.x), y=float(t.y), status=t.status,
    )


@router.patch("/{table_id}", response_model=TableRead)
async def update_table(
    table_id: UUID,
    payload: TableUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.write")),
) -> TableRead:
    branch_id = _current_branch_id(tenant)
    t = await _tenant_table(
        session,
        tenant.company_id,
        branch_id,
        table_id,
        for_update=True,
    )
    if not t:
        raise NotFoundError("table not found")
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(t, f, v)
    await session.flush()
    return TableRead(
        id=t.id, floor_id=t.floor_id, code=t.code, seats=t.seats,
        shape=t.shape, x=float(t.x), y=float(t.y), status=t.status,
    )


@router.patch("/{table_id}/status", response_model=TableRead)
async def update_status(
    table_id: UUID,
    payload: TableStatusUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.write")),
) -> TableRead:
    branch_id = _current_branch_id(tenant)
    t = await _tenant_table(
        session,
        tenant.company_id,
        branch_id,
        table_id,
        for_update=True,
    )
    if not t:
        raise NotFoundError("table not found")
    if payload.status == "available" and await _table_has_unfinished_order(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        table_id=table_id,
    ):
        raise ConflictError(
            "table has an open or held order; finish or void it before marking the table available"
        )
    t.status = payload.status
    await session.flush()
    return TableRead(
        id=t.id, floor_id=t.floor_id, code=t.code, seats=t.seats,
        shape=t.shape, x=float(t.x), y=float(t.y), status=t.status,
    )


@router.delete("/{table_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_table(
    table_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.write")),
):
    branch_id = _current_branch_id(tenant)
    t = await _tenant_table(
        session,
        tenant.company_id,
        branch_id,
        table_id,
        for_update=True,
    )
    if not t:
        raise NotFoundError("table not found")
    if await _table_has_unfinished_order(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        table_id=table_id,
    ):
        raise ConflictError(
            "table has an open or held order; finish or void it before deleting the table"
        )
    if await _table_has_active_or_future_reservation(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        table_id=table_id,
    ):
        raise ConflictError(
            "table has an active or future reservation; cancel or complete it before "
            "deleting the table"
        )
    if await _table_has_source_history(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        table_id=table_id,
    ):
        raise ConflictError(
            "table has order or reservation history and cannot be deleted because its source label "
            "must remain auditable; rename the table instead"
        )
    await session.delete(t)
    await session.flush()


# ---------------------------------------------------------------- RESERVATIONS
def _reservation_read(r: Reservation, *, table_code: str) -> ReservationRead:
    return ReservationRead(
        id=r.id, table_id=r.table_id, table_code=table_code, guest_name=r.guest_name,
        party_size=r.party_size, contact=r.contact, starts_at=r.starts_at, ends_at=r.ends_at,
        notes=r.notes, status=r.status, created_at=r.created_at,
    )


@router.get("/reservations", response_model=list[ReservationRead])
async def list_reservations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    status_filter: list[str] | None = Query(default=None, alias="status"),
    limit: int = Query(default=200, ge=1, le=500),
) -> list[ReservationRead]:
    """List reservations for the current branch, soonest first.

    Defaults to upcoming only (starts_at from today onward, local time) so a
    booking made months ago doesn't clutter the staff view forever — pass an
    explicit `from_date` to look further back. `to_date` is optional and
    unbounded when omitted.
    """
    branch_id = _current_branch_id(tenant)
    stmt = (
        select(Reservation, Table.code)
        .join(Table, Table.id == Reservation.table_id)
        .join(Floor, Floor.id == Table.floor_id)
        .join(Branch, Branch.id == Floor.branch_id)
        .where(
            Floor.branch_id == branch_id,
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if status_filter:
        stmt = stmt.where(Reservation.status.in_(status_filter))
    timezone_name = await company_timezone(session, tenant.company_id)
    f_d = from_date or local_today(timezone_name)
    f_dt, _ = local_date_bounds_utc(f_d, f_d, timezone_name)
    stmt = stmt.where(Reservation.starts_at >= f_dt)
    if to_date:
        _, t_dt = local_date_bounds_utc(to_date, to_date, timezone_name)
        stmt = stmt.where(Reservation.starts_at < t_dt)
    stmt = stmt.order_by(Reservation.starts_at.asc()).limit(limit)
    rows = (await session.execute(stmt)).all()
    return [_reservation_read(r, table_code=code) for r, code in rows]


@router.post("/reservations", status_code=status.HTTP_201_CREATED)
async def create_reservation(
    payload: ReservationCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.reservations.write")),
) -> dict:
    if payload.ends_at <= payload.starts_at:
        raise BusinessRuleError("ends_at must be after starts_at")
    branch_id = _current_branch_id(tenant)
    table = await _tenant_table(
        session,
        tenant.company_id,
        branch_id,
        payload.table_id,
        for_update=True,
    )
    if not table:
        raise NotFoundError("table not found")
    r = Reservation(
        id=uuid4(),
        table_id=payload.table_id,
        created_by=tenant.user_id,
        guest_name=payload.guest_name,
        party_size=payload.party_size,
        contact=payload.contact,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        notes=payload.notes,
        status="held",
    )
    session.add(r)
    await session.flush()
    return {"id": str(r.id), "status": r.status}


@router.patch("/reservations/{reservation_id}/status", response_model=ReservationRead)
async def update_reservation_status(
    reservation_id: UUID,
    payload: ReservationStatusUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.reservations.write")),
) -> ReservationRead:
    branch_id = _current_branch_id(tenant)
    r = await _tenant_reservation(
        session,
        tenant.company_id,
        branch_id,
        reservation_id,
        for_update=True,
    )
    if not r:
        raise NotFoundError("reservation not found")
    allowed = _RESERVATION_TRANSITIONS.get(r.status, set())
    if payload.status not in allowed:
        raise ConflictError(
            f"cannot change reservation from '{r.status}' to '{payload.status}'"
        )
    r.status = payload.status
    await session.flush()
    table_code = (
        await session.execute(select(Table.code).where(Table.id == r.table_id))
    ).scalar_one()
    return _reservation_read(r, table_code=table_code)
