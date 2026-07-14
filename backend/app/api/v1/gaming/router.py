"""Gaming endpoints — stations, sessions, bookings."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal
from math import ceil
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.models import (
    Branch,
    GamingBooking,
    GamingSession,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Shift,
    Station,
)
from app.services.pos.pricing import OrderPricingService
from app.services.pos.shift_validation import require_open_operational_shift, require_shift_opener

router = APIRouter()

_MENU_TYPE_FOR_STATION = {"hookah": "hookah", "streaming": "streaming"}
_SESSION_ITEM_LABEL = {
    "ps5": "Gaming Session",
    "vr": "VR Session",
    "simulator": "Simulator Session",
    "projector": "Projector Session",
    "hookah": "Shisha Session",
    "streaming": "Streaming Session",
}


class StationRead(BaseModel):
    id: UUID
    branch_id: UUID
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
    notes: str | None = Field(default=None, max_length=500)


class StationUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    rate_per_hour_minor: int | None = Field(default=None, ge=0)
    is_active: bool | None = None
    notes: str | None = Field(default=None, max_length=500)


class SessionStart(BaseModel):
    station_id: UUID
    shift_id: UUID
    customer_name: str | None = Field(default=None, max_length=200)
    customer_phone: str | None = Field(default=None, max_length=20)
    # Planned duration in minutes (e.g. a 60-minute PS5 slot). Omit for open-ended.
    timer_minutes: int | None = Field(default=None, ge=1, le=1440)


class SessionTimerUpdate(BaseModel):
    # Minutes from the session's start_at. null clears the timer (open-ended).
    timer_minutes: int | None = Field(default=None, ge=1, le=1440)


class SessionRead(BaseModel):
    id: UUID
    station_id: UUID
    status: str
    start_at: datetime
    end_at: datetime | None
    timer_minutes: int | None = None
    timer_ends_at: datetime | None = None
    billable_minutes: int | None
    amount_minor: int | None
    customer_name: str | None = None
    customer_phone: str | None = None
    rate_per_hour_minor: int | None = None
    order_id: UUID | None = None


class BookingCreate(BaseModel):
    station_id: UUID
    starts_at: datetime
    ends_at: datetime
    guest_name: str = Field(min_length=1, max_length=200)
    contact: str | None = Field(default=None, max_length=50)
    party_size: int = Field(default=1, gt=0)
    deposit_minor: int = Field(default=0, ge=0)


def session_read(gs: GamingSession) -> SessionRead:
    timer_ends_at = (
        gs.start_at + timedelta(minutes=gs.timer_minutes) if gs.timer_minutes else None
    )
    return SessionRead(
        id=gs.id,
        station_id=gs.station_id,
        status=gs.status,
        start_at=gs.start_at,
        end_at=gs.end_at,
        timer_minutes=gs.timer_minutes,
        timer_ends_at=timer_ends_at,
        billable_minutes=gs.billable_minutes,
        amount_minor=gs.amount_minor,
        customer_name=gs.customer_name,
        customer_phone=gs.customer_phone,
        rate_per_hour_minor=gs.rate_per_hour_minor,
        order_id=gs.order_id,
    )


@router.get("/stations", response_model=list[StationRead])
async def list_stations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[StationRead]:
    stmt = select(Station).where(Station.company_id == tenant.company_id)
    if tenant.branch_id is not None:
        stmt = stmt.where(Station.branch_id == tenant.branch_id)
    rows = (await session.execute(stmt)).scalars().all()
    return [
        StationRead(
            id=r.id, branch_id=r.branch_id, code=r.code, name=r.name, type=r.type,
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
        id=st.id, branch_id=st.branch_id, code=st.code, name=st.name, type=st.type,
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
        id=st.id, branch_id=st.branch_id, code=st.code, name=st.name, type=st.type,
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
    # Historical sessions reference stations for billing, GST, and audit trail.
    # Keep the row and hide it from active operations instead of breaking history.
    st.is_active = False
    await session.flush()


@router.get("/sessions", response_model=list[SessionRead])
async def list_sessions(
    session: SessionDep,
    status_filter: str | None = Query(default=None, alias="status"),
    limit: int = Query(default=80, ge=1, le=200),
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[SessionRead]:
    stmt = select(GamingSession).where(GamingSession.company_id == tenant.company_id)
    if tenant.branch_id is not None:
        stmt = stmt.join(Station, Station.id == GamingSession.station_id).where(
            Station.branch_id == tenant.branch_id
        )
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
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="starting a gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="start a session on this shift",
    )
    active = (
        await session.execute(
            select(GamingSession.id).where(
                GamingSession.company_id == tenant.company_id,
                GamingSession.station_id == payload.station_id,
                GamingSession.status.in_(("active", "paused")),
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
        timer_minutes=payload.timer_minutes,
        tax_rate=station.tax_rate,
        sac_code=station.sac_code,
        rate_includes_tax=station.rate_includes_tax,
    )
    session.add(gs)
    await session.flush()
    return session_read(gs)


@router.patch("/sessions/{session_id}/timer", response_model=SessionRead)
async def set_session_timer(
    session_id: UUID,
    payload: SessionTimerUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),  # noqa: B008
) -> SessionRead:
    gs = await session.get(GamingSession, session_id)
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if gs.status not in ("active", "paused"):
        raise BusinessRuleError("session is not running")
    gs.timer_minutes = payload.timer_minutes
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
    elapsed_seconds = max(0.0, (gs.end_at - gs.start_at).total_seconds())
    elapsed_minutes = ceil(elapsed_seconds / 60) if elapsed_seconds > 0 else 0
    gs.billable_minutes = max(0, elapsed_minutes - gs.paused_minutes)
    gs.amount_minor = ceil(gs.billable_minutes / 60 * gs.rate_per_hour_minor)
    gs.status = "ended"
    return session_read(gs)


async def _ensure_session_menu_item(
    session, *, company_id: UUID, station: Station
) -> MenuItem:
    """Get-or-create the hidden MenuItem used to bill a station's time-based
    sessions. Never shown in the normal POS menu grid (is_available=False) —
    price/tax are always taken from the session, not this item's base price.
    """
    sku = f"SESSION-{station.type.upper()}"
    existing = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.company_id == company_id, MenuItem.sku == sku
            )
        )
    ).scalar_one_or_none()
    if existing:
        return existing

    category = (
        await session.execute(
            select(MenuCategory).where(
                MenuCategory.company_id == company_id,
                MenuCategory.name == "Session Billing",
            )
        )
    ).scalar_one_or_none()
    if not category:
        category = MenuCategory(
            id=uuid4(), company_id=company_id, name="Session Billing", sort_order=999,
        )
        session.add(category)
        await session.flush()

    item = MenuItem(
        id=uuid4(),
        company_id=company_id,
        category_id=category.id,
        sku=sku,
        name=_SESSION_ITEM_LABEL.get(station.type, f"{station.type.title()} Session"),
        type=_MENU_TYPE_FOR_STATION.get(station.type, "gaming"),
        base_price_minor=0,
        tax_rate=station.tax_rate,
        hsn_code=station.sac_code,
        price_includes_tax=station.rate_includes_tax,
        is_available=False,
    )
    session.add(item)
    await session.flush()
    return item


@router.post("/sessions/{session_id}/send-to-pos", status_code=status.HTTP_201_CREATED)
async def send_session_to_pos(
    session_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> dict:
    """Turn a stopped session's computed amount into a held POS order,
    ready for a cashier to find and bill — replacing the old manual
    re-entry workaround."""
    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if gs.status != "ended":
        raise BusinessRuleError("stop the session before sending it to POS")
    if gs.order_id is not None:
        raise BusinessRuleError("session was already sent to POS")
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")

    station = await session.get(Station, gs.station_id)
    if not station:
        raise NotFoundError("station not found")

    shift = (
        await session.execute(
            select(Shift).where(
                Shift.company_id == tenant.company_id,
                Shift.branch_id == tenant.branch_id,
                Shift.terminal_id == tenant.terminal_id,
                Shift.status == "open",
            )
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending a session to POS",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    item = await _ensure_session_menu_item(session, company_id=tenant.company_id, station=station)

    tax_rate = Decimal(str(gs.tax_rate if gs.tax_rate is not None else station.tax_rate))
    rate_includes_tax = (
        gs.rate_includes_tax if gs.rate_includes_tax is not None else station.rate_includes_tax
    )
    amount_minor = int(gs.amount_minor or 0)
    priced = await OrderPricingService(session).price_time_based_line(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        amount_minor=amount_minor,
        tax_rate=tax_rate,
        rate_includes_tax=rate_includes_tax,
    )

    now = datetime.now(timezone.utc)
    note = f"{gs.billable_minutes or 0} min @ {gs.rate_per_hour_minor / 100:.2f}/hr"
    order = Order(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        opened_by=tenant.user_id,
        table_id=None,
        type="session",
        status="held",
        opened_at=now,
        held_at=now,
        subtotal_minor=priced.taxable_minor,
        cgst_minor=priced.cgst_minor,
        sgst_minor=priced.sgst_minor,
        igst_minor=priced.igst_minor,
        cess_minor=0,
        discount_minor=0,
        tax_minor=priced.cgst_minor + priced.sgst_minor + priced.igst_minor,
        round_off_minor=0,
        total_minor=priced.total_minor,
        customer_name=gs.customer_name,
        customer_phone=gs.customer_phone,
        notes=f"{station.name} — {note}",
    )
    session.add(order)
    await session.flush()

    ol = OrderLine(
        id=uuid4(),
        order_id=order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=priced.total_minor,
        line_total_minor=priced.total_minor,
        discount_minor=0,
        hsn_or_sac=item.hsn_code or "",
        tax_rate=float(tax_rate),
        taxable_value_minor=priced.taxable_minor,
        cgst_minor=priced.cgst_minor,
        sgst_minor=priced.sgst_minor,
        igst_minor=priced.igst_minor,
        cess_minor=0,
        note=note,
    )
    session.add(ol)
    gs.order_id = order.id
    await session.flush()
    return {"order_id": str(order.id), "amount_minor": priced.total_minor}


@router.post("/bookings", status_code=status.HTTP_201_CREATED)
async def create_booking(
    payload: BookingCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> dict:
    if payload.ends_at <= payload.starts_at:
        raise BusinessRuleError("ends_at must be after starts_at")
    station = await session.get(Station, payload.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if not station.is_active:
        raise BusinessRuleError("station is not active")
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
