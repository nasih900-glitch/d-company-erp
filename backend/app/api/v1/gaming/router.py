"""Gaming endpoints — stations, sessions, bookings."""

from __future__ import annotations

from datetime import datetime, timezone
from math import ceil
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError, ConflictError
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.models import Branch, GamingBooking, GamingSession, Station

router = APIRouter()


class StationRead(BaseModel):
    id: UUID
    code: str
    name: str
    type: str
    rate_per_hour_minor: int
    is_active: bool


class StationCreate(BaseModel):
    code: str = Field(min_length=1, max_length=20)
    name: str = Field(min_length=1, max_length=100)
    type: str = Field(min_length=1, max_length=20)  # ps5|vr|simulator|projector|hookah|streaming
    rate_per_hour_minor: int = Field(ge=0)
    branch_id: UUID | None = None
    notes: str | None = None


class StationUpdate(BaseModel):
    name: str | None = None
    rate_per_hour_minor: int | None = Field(default=None, ge=0)
    is_active: bool | None = None
    notes: str | None = None


class SessionStart(BaseModel):
    station_id: UUID
    shift_id: UUID
    customer_name: str | None = None
    customer_phone: str | None = None


class SessionRead(BaseModel):
    id: UUID
    station_id: UUID
    status: str
    start_at: datetime
    end_at: datetime | None
    billable_minutes: int | None
    amount_minor: int | None
    customer_name: str | None = None
    customer_phone: str | None = None
    rate_per_hour_minor: int | None = None


class BookingCreate(BaseModel):
    station_id: UUID
    starts_at: datetime
    ends_at: datetime
    guest_name: str
    contact: str | None = None
    party_size: int = Field(default=1, gt=0)
    deposit_minor: int = 0


def session_read(gs: GamingSession) -> SessionRead:
    return SessionRead(
        id=gs.id,
        station_id=gs.station_id,
        status=gs.status,
        start_at=gs.start_at,
        end_at=gs.end_at,
        billable_minutes=gs.billable_minutes,
        amount_minor=gs.amount_minor,
        customer_name=gs.customer_name,
        customer_phone=gs.customer_phone,
        rate_per_hour_minor=gs.rate_per_hour_minor,
    )


@router.get("/stations", response_model=list[StationRead])
async def list_stations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[StationRead]:
    rows = (
        await session.execute(
            select(Station).where(Station.company_id == tenant.company_id)
        )
    ).scalars().all()
    return [
        StationRead(
            id=r.id, code=r.code, name=r.name, type=r.type,
            rate_per_hour_minor=r.rate_per_hour_minor, is_active=r.is_active,
        )
        for r in rows
    ]


@router.post("/stations", response_model=StationRead, status_code=status.HTTP_201_CREATED)
async def create_station(
    payload: StationCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> StationRead:
    require_pricing_unlock(x_pricing_token, tenant)
    branch_id = payload.branch_id or tenant.branch_id
    if branch_id is None:
        branch_id = (
            await session.execute(
                select(Branch.id)
                .where(Branch.company_id == tenant.company_id, Branch.deleted_at.is_(None))
                .order_by(Branch.created_at)
                .limit(1)
            )
        ).scalar_one_or_none()
    if branch_id is None:
        raise BusinessRuleError(
            "no branch exists — create one in Settings → Branches first"
        )

    existing = (
        await session.execute(
            select(Station).where(
                Station.company_id == tenant.company_id, Station.code == payload.code
            )
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError(f"a station with code '{payload.code}' already exists")

    st = Station(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=branch_id,
        code=payload.code,
        name=payload.name,
        type=payload.type,
        rate_per_hour_minor=payload.rate_per_hour_minor,
        is_active=True,
        notes=payload.notes,
    )
    session.add(st)
    await session.flush()
    return StationRead(
        id=st.id, code=st.code, name=st.name, type=st.type,
        rate_per_hour_minor=st.rate_per_hour_minor, is_active=st.is_active,
    )


@router.patch("/stations/{station_id}", response_model=StationRead)
async def update_station(
    station_id: UUID,
    payload: StationUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> StationRead:
    if payload.rate_per_hour_minor is not None:
        require_pricing_unlock(x_pricing_token, tenant)
    st = await session.get(Station, station_id)
    if not st or st.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(st, f, v)
    await session.flush()
    return StationRead(
        id=st.id, code=st.code, name=st.name, type=st.type,
        rate_per_hour_minor=st.rate_per_hour_minor, is_active=st.is_active,
    )


@router.delete("/stations/{station_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_station(
    station_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
):
    require_pricing_unlock(x_pricing_token, tenant)
    st = await session.get(Station, station_id)
    if not st or st.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    await session.delete(st)
    await session.flush()


@router.get("/sessions", response_model=list[SessionRead])
async def list_sessions(
    session: SessionDep,
    status_filter: str | None = Query(default=None, alias="status"),
    limit: int = Query(default=80, ge=1, le=200),
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[SessionRead]:
    stmt = select(GamingSession).where(GamingSession.company_id == tenant.company_id)
    if status_filter:
        stmt = stmt.where(GamingSession.status == status_filter)
    stmt = stmt.order_by(GamingSession.start_at.desc()).limit(limit)
    rows = (await session.execute(stmt)).scalars().all()
    return [session_read(row) for row in rows]


@router.post("/sessions/start", response_model=SessionRead, status_code=status.HTTP_201_CREATED)
async def start_session(
    payload: SessionStart,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    station = await session.get(Station, payload.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if not station.is_active:
        raise BusinessRuleError("station is not active")
    active = (
        await session.execute(
            select(GamingSession.id).where(
                GamingSession.company_id == tenant.company_id,
                GamingSession.station_id == payload.station_id,
                GamingSession.status == "active",
            )
        )
    ).scalar_one_or_none()
    if active:
        raise ConflictError("station already has an active session")
    gs = GamingSession(
        id=uuid4(),
        company_id=tenant.company_id,
        station_id=payload.station_id,
        opened_by=tenant.user_id,
        shift_id=payload.shift_id,
        start_at=datetime.now(timezone.utc),
        rate_per_hour_minor=station.rate_per_hour_minor,
        status="active",
        customer_name=payload.customer_name,
        customer_phone=payload.customer_phone,
    )
    session.add(gs)
    await session.flush()
    return session_read(gs)


@router.post("/sessions/{session_id}/stop", response_model=SessionRead)
async def stop_session(
    session_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    gs = await session.get(GamingSession, session_id)
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if gs.status == "ended":
        raise BusinessRuleError("session already ended")
    gs.end_at = datetime.now(timezone.utc)
    elapsed_minutes = max(0, int((gs.end_at - gs.start_at).total_seconds() // 60))
    gs.billable_minutes = max(0, elapsed_minutes - gs.paused_minutes)
    gs.amount_minor = ceil(gs.billable_minutes / 60 * gs.rate_per_hour_minor)
    gs.status = "ended"
    return session_read(gs)


@router.post("/bookings", status_code=status.HTTP_201_CREATED)
async def create_booking(
    payload: BookingCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> dict:
    if payload.ends_at <= payload.starts_at:
        raise BusinessRuleError("ends_at must be after starts_at")
    bk = GamingBooking(
        id=uuid4(),
        station_id=payload.station_id,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        guest_name=payload.guest_name,
        contact=payload.contact,
        party_size=payload.party_size,
        deposit_minor=payload.deposit_minor,
        status="held",
        created_by=tenant.user_id,
    )
    session.add(bk)
    # The EXCLUDE constraint at the DB level will reject overlapping bookings.
    return {"id": str(bk.id), "status": bk.status}
