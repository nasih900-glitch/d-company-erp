"""Tables / floors / reservations — full CRUD.

Endpoints:
  GET    /tables/floors                    — list floors
  POST   /tables/floors                    — create floor (auto-creates one if none)
  GET    /tables                           — list tables (across floors)
  POST   /tables                           — create a new table
  PATCH  /tables/{id}                      — rename / resize / move
  PATCH  /tables/{id}/status               — quick status change (occupied/free)
  DELETE /tables/{id}                      — soft delete
  POST   /tables/reservations              — create a reservation
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.permissions import requires
from app.core.tenant import TenantContext
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
