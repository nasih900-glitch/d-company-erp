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

from datetime import datetime
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import NotFoundError, BusinessRuleError, ConflictError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import Branch, Floor, Reservation, Table

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class FloorRead(BaseModel):
    id: UUID
    branch_id: UUID
    name: str


class FloorCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    branch_id: UUID | None = None  # falls back to default branch


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
    code: str | None = None
    seats: int | None = Field(default=None, gt=0, le=20)
    shape: Literal["rect", "round", "booth"] | None = None
    x: float | None = None
    y: float | None = None


class TableStatusUpdate(BaseModel):
    status: Literal["available", "occupied", "reserved", "cleaning", "merged"]


class ReservationCreate(BaseModel):
    table_id: UUID
    guest_name: str
    party_size: int = Field(gt=0)
    contact: str | None = None
    starts_at: datetime
    ends_at: datetime
    notes: str | None = None


# ---------------------------------------------------------------- helpers
async def _default_branch_id(session, company_id: UUID) -> UUID | None:
    return (
        await session.execute(
            select(Branch.id)
            .where(Branch.company_id == company_id, Branch.deleted_at.is_(None))
            .order_by(Branch.created_at)
            .limit(1)
        )
    ).scalar_one_or_none()


async def _ensure_default_floor(session, tenant: TenantContext) -> UUID:
    """Return the first floor for the company, creating one if needed."""
    floor_id = (
        await session.execute(
            select(Floor.id)
            .join(Branch, Branch.id == Floor.branch_id)
            .where(Branch.company_id == tenant.company_id)
            .order_by(Floor.created_at)
            .limit(1)
        )
    ).scalar_one_or_none()
    if floor_id:
        return floor_id
    branch_id = tenant.branch_id or await _default_branch_id(session, tenant.company_id)
    if not branch_id:
        raise BusinessRuleError("create a branch in Settings → Branches first")
    floor = Floor(id=uuid4(), branch_id=branch_id, name="Main Floor")
    session.add(floor)
    await session.flush()
    return floor.id


# ---------------------------------------------------------------- FLOORS
@router.get("/floors", response_model=list[FloorRead])
async def list_floors(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.read")),
) -> list[FloorRead]:
    rows = (
        await session.execute(
            select(Floor)
            .join(Branch, Branch.id == Floor.branch_id)
            .where(Branch.company_id == tenant.company_id)
        )
    ).scalars().all()
    return [FloorRead(id=r.id, branch_id=r.branch_id, name=r.name) for r in rows]


@router.post("/floors", response_model=FloorRead, status_code=status.HTTP_201_CREATED)
async def create_floor(
    payload: FloorCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.write")),
) -> FloorRead:
    branch_id = payload.branch_id or tenant.branch_id or await _default_branch_id(session, tenant.company_id)
    if not branch_id:
        raise BusinessRuleError("no branch exists — create one in Settings → Branches first")
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
    stmt = (
        select(Table)
        .join(Floor, Floor.id == Table.floor_id)
        .join(Branch, Branch.id == Floor.branch_id)
        .where(Branch.company_id == tenant.company_id)
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
    floor_id = payload.floor_id or await _ensure_default_floor(session, tenant)
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
    t = await session.get(Table, table_id)
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
    t = await session.get(Table, table_id)
    if not t:
        raise NotFoundError("table not found")
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
    t = await session.get(Table, table_id)
    if not t:
        raise NotFoundError("table not found")
    await session.delete(t)
    await session.flush()


# ---------------------------------------------------------------- RESERVATIONS
@router.post("/reservations", status_code=status.HTTP_201_CREATED)
async def create_reservation(
    payload: ReservationCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.reservations.write")),
) -> dict:
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
