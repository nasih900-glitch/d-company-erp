"""Gaming endpoints — stations, sessions, bookings."""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from math import ceil
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Query, Request, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.core.db import SessionDep
from app.core.errors import (
    BusinessRuleError,
    ConflictError,
    ForbiddenError,
    GamingSourceShiftClosedError,
    NotFoundError,
)
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import (
    AuditLog,
    Branch,
    Company,
    GamingBooking,
    GamingPackage,
    GamingSession,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Shift,
    Station,
)
from app.services.pos.order_validation import require_operational_order
from app.services.pos.pricing import OrderPricingService, _round_to_rupee
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_operational_shift_scope,
    require_shift_opener,
)

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
    # A fixed-price base package (see GET /gaming/packages) — locks in the
    # price immediately and sets timer_minutes from the package's duration.
    # Omit for the legacy open-ended flow (billed by elapsed time at the
    # station's plain hourly rate).
    package_id: UUID | None = None
    # Planned duration in minutes (e.g. a 60-minute PS5 slot). Ignored if
    # package_id is set (the package's own duration wins). Omit for open-ended.
    timer_minutes: int | None = Field(default=None, ge=1, le=1440)
    # Extra controllers/players beyond the package's base mode — only
    # meaningful together with package_id.
    extra_controllers: int = Field(default=0, ge=0, le=8)

    @field_validator("customer_name", "customer_phone")
    @classmethod
    def normalize_optional_customer_identity(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None


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
    cancel_reason: str | None = None
    package_id: UUID | None = None
    extra_controllers: int = 0


class GamingPackageRead(BaseModel):
    id: UUID
    station_type: str
    variant: str
    kind: str
    name: str
    duration_minutes: int
    price_minor: int


class SessionExtend(BaseModel):
    package_id: UUID


class SessionCancel(BaseModel):
    reason: str = Field(min_length=1, max_length=500)

    @field_validator("reason")
    @classmethod
    def require_meaningful_reason(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("cancellation reason cannot be blank")
        return value


class SessionReconcileToPos(BaseModel):
    target_shift_id: UUID
    reason: str = Field(min_length=3, max_length=500)

    @field_validator("reason")
    @classmethod
    def require_meaningful_reason(cls, value: str) -> str:
        normalized = value.strip()
        if len(normalized) < 3:
            raise ValueError("reconciliation reason must be at least 3 characters")
        return normalized


class SessionReconcileToPosRead(BaseModel):
    order_id: UUID
    amount_minor: int
    source_shift_id: UUID
    target_shift_id: UUID
    already_linked: bool


class BookingCreate(BaseModel):
    station_id: UUID
    starts_at: datetime
    ends_at: datetime
    guest_name: str = Field(min_length=1, max_length=200)
    contact: str | None = Field(default=None, max_length=50)
    party_size: int = Field(default=1, gt=0)
    deposit_minor: int = Field(default=0, ge=0)


class BookingRead(BaseModel):
    id: UUID
    station_id: UUID
    station_code: str
    starts_at: datetime
    ends_at: datetime
    guest_name: str
    contact: str | None
    party_size: int
    deposit_minor: int
    status: str
    created_at: datetime


class BookingStatusUpdate(BaseModel):
    # "held" is the creation-only state — not a valid PATCH target, so it's
    # deliberately excluded here (see _BOOKING_TRANSITIONS below).
    status: Literal["consumed", "no_show", "cancelled"]


# A booking only ever transitions once, out of "held" — every other status
# is terminal. Keyed by *current* status so an invalid transition (e.g.
# cancelling an already-consumed/no-show/cancelled booking) is rejected
# instead of silently overwriting a settled outcome.
_BOOKING_TRANSITIONS: dict[str, set[str]] = {
    "held": {"consumed", "no_show", "cancelled"},
}


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
        cancel_reason=gs.cancel_reason,
        package_id=gs.package_id,
        extra_controllers=int(gs.extra_controllers or 0),
    )


def session_amount_minor(billable_minutes: int, rate_per_hour_minor: int) -> int:
    """Ceiling-bill whole minutes using integer minor-unit arithmetic."""
    if billable_minutes <= 0 or rate_per_hour_minor <= 0:
        return 0
    return (billable_minutes * rate_per_hour_minor + 59) // 60


# ₹30 per extra controller per hour, ₹30 minimum per controller — printed
# pricing card, "applicable for more than 2 players".
EXTRA_CONTROLLER_PRICE_PER_HOUR_MINOR = 3000
EXTRA_CONTROLLER_MIN_CHARGE_MINOR = 3000


def extra_controller_surcharge_minor(*, extra_controllers: int, duration_minutes: int) -> int:
    """One extra-controller charge per player beyond the base package mode.

    Priced per hour of the package's own duration (not actual elapsed play
    time — package sessions are prepaid, fixed-price slots), with a ₹30
    floor per controller so even a 15-minute slot costs a full ₹30 to add
    a controller to.
    """
    if extra_controllers <= 0 or duration_minutes <= 0:
        return 0
    hours = ceil(duration_minutes / 60)
    per_controller = max(
        EXTRA_CONTROLLER_MIN_CHARGE_MINOR, hours * EXTRA_CONTROLLER_PRICE_PER_HOUR_MINOR
    )
    return extra_controllers * per_controller


def _current_gaming_branch_id(tenant: TenantContext) -> UUID:
    if tenant.branch_id is None:
        raise BusinessRuleError("select a branch or terminal before managing gaming stations")
    return tenant.branch_id


def _require_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for gaming session writes")
    return str(key), str(request_hash)


def _requested_gaming_branch_id(
    tenant: TenantContext,
    requested_branch_id: UUID | None,
) -> UUID:
    branch_id = _current_gaming_branch_id(tenant)
    if requested_branch_id is not None and requested_branch_id != branch_id:
        raise NotFoundError("branch not found")
    return branch_id


async def _operational_station(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    station_id: UUID,
    for_update: bool = False,
) -> Station | None:
    stmt = (
        select(Station)
        .join(Branch, Branch.id == Station.branch_id)
        .where(
            Station.id == station_id,
            Station.company_id == company_id,
            Station.branch_id == branch_id,
            Branch.company_id == company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if for_update:
        stmt = stmt.with_for_update()
    return (await session.execute(stmt)).scalar_one_or_none()


async def _tenant_booking(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    booking_id: UUID,
    for_update: bool = False,
) -> GamingBooking | None:
    stmt = (
        select(GamingBooking)
        .join(Station, Station.id == GamingBooking.station_id)
        .where(
            GamingBooking.id == booking_id,
            Station.company_id == company_id,
            Station.branch_id == branch_id,
        )
    )
    if for_update:
        stmt = stmt.with_for_update()
    return (await session.execute(stmt)).scalar_one_or_none()


@router.get("/stations", response_model=list[StationRead])
async def list_stations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[StationRead]:
    branch_id = _current_gaming_branch_id(tenant)
    stmt = (
        select(Station)
        .join(Branch, Branch.id == Station.branch_id)
        .where(
            Station.company_id == tenant.company_id,
            Station.branch_id == branch_id,
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
    )
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
    branch_id = _requested_gaming_branch_id(tenant, payload.branch_id)
    branch = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if branch is None:
        raise NotFoundError("branch not found")

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
    branch_id = _current_gaming_branch_id(tenant)
    st = await _operational_station(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        station_id=station_id,
        for_update=True,
    )
    if not st:
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
    branch_id = _current_gaming_branch_id(tenant)
    st = await _operational_station(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        station_id=station_id,
        for_update=True,
    )
    if not st:
        raise NotFoundError("station not found")
    # Historical sessions reference stations for billing, GST, and audit trail.
    # Keep the row and hide it from active operations instead of breaking history.
    st.is_active = False
    await session.flush()


@router.get("/packages", response_model=list[GamingPackageRead])
async def list_packages(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
    station_type: str | None = None,
) -> list[GamingPackageRead]:
    branch_id = _current_gaming_branch_id(tenant)
    stmt = select(GamingPackage).where(
        GamingPackage.company_id == tenant.company_id,
        GamingPackage.branch_id == branch_id,
        GamingPackage.deleted_at.is_(None),
        GamingPackage.is_active.is_(True),
    )
    if station_type:
        stmt = stmt.where(GamingPackage.station_type == station_type)
    stmt = stmt.order_by(
        GamingPackage.station_type, GamingPackage.variant, GamingPackage.sort_order
    )
    rows = (await session.execute(stmt)).scalars().all()
    return [
        GamingPackageRead(
            id=p.id,
            station_type=p.station_type,
            variant=p.variant,
            kind=p.kind,
            name=p.name,
            duration_minutes=p.duration_minutes,
            price_minor=p.price_minor,
        )
        for p in rows
    ]


@router.get("/sessions", response_model=list[SessionRead])
async def list_sessions(
    session: SessionDep,
    status_filter: str | None = Query(default=None, alias="status"),
    unbilled_only: bool = Query(default=False),
    limit: int = Query(default=80, ge=1, le=500),
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[SessionRead]:
    stmt = select(GamingSession).where(GamingSession.company_id == tenant.company_id)
    if tenant.branch_id is not None:
        stmt = stmt.join(Station, Station.id == GamingSession.station_id).where(
            Station.branch_id == tenant.branch_id
        )
    if status_filter:
        stmt = stmt.where(GamingSession.status == status_filter)
    if unbilled_only:
        stmt = stmt.where(GamingSession.order_id.is_(None))
    stmt = stmt.order_by(GamingSession.start_at.desc()).limit(limit)
    rows = (await session.execute(stmt)).scalars().all()
    return [session_read(row) for row in rows]


@router.post("/sessions/start", response_model=SessionRead, status_code=status.HTTP_201_CREATED)
async def start_session(
    payload: SessionStart,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionRead.model_validate(existing_response["body"])

    # Serialise starts per station so concurrent devices cannot both pass the
    # availability check and create overlapping/unbilled sessions.
    station = (
        await session.execute(
            select(Station).where(Station.id == payload.station_id).with_for_update()
        )
    ).scalar_one_or_none()
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
    blocking = (
        await session.execute(
            select(GamingSession).where(
                GamingSession.company_id == tenant.company_id,
                GamingSession.station_id == payload.station_id,
                (
                    GamingSession.status.in_(("active", "paused"))
                    | (
                        (GamingSession.status == "ended")
                        & GamingSession.order_id.is_(None)
                    )
                ),
            ).order_by(GamingSession.start_at.desc()).limit(1)
        )
    ).scalar_one_or_none()
    if blocking and blocking.status == "ended":
        raise ConflictError(
            "station has a stopped session that must be sent to POS or cancelled first"
        )
    if blocking:
        raise ConflictError("station already has an active session")

    package: GamingPackage | None = None
    timer_minutes = payload.timer_minutes
    locked_in_amount_minor: int | None = None
    if payload.package_id is not None:
        package = await session.get(GamingPackage, payload.package_id)
        if (
            not package
            or package.company_id != tenant.company_id
            or package.branch_id != station.branch_id
            or package.deleted_at is not None
            or not package.is_active
        ):
            raise NotFoundError("package not found")
        if package.kind != "base":
            raise BusinessRuleError("only a base package can start a session")
        if package.station_type != station.type:
            raise BusinessRuleError("this package is not offered for this station type")
        timer_minutes = package.duration_minutes
        locked_in_amount_minor = package.price_minor + extra_controller_surcharge_minor(
            extra_controllers=payload.extra_controllers,
            duration_minutes=package.duration_minutes,
        )
    elif payload.extra_controllers:
        raise BusinessRuleError("extra_controllers requires a package_id")

    gs = GamingSession(
        id=uuid4(),
        company_id=tenant.company_id,
        station_id=payload.station_id,
        opened_by=tenant.user_id,
        shift_id=payload.shift_id,
        start_at=datetime.now(timezone.utc),
        rate_per_hour_minor=station.rate_per_hour_minor,
        package_id=package.id if package else None,
        extra_controllers=payload.extra_controllers,
        amount_minor=locked_in_amount_minor,
        status="active",
        customer_name=payload.customer_name,
        customer_phone=payload.customer_phone,
        timer_minutes=timer_minutes,
        tax_rate=station.tax_rate,
        sac_code=station.sac_code,
        rate_includes_tax=station.rate_includes_tax,
    )
    session.add(gs)
    await session.flush()
    response = session_read(gs)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


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
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = await session.get(Shift, gs.shift_id)
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="updating a gaming session timer",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    gs.timer_minutes = payload.timer_minutes
    await session.flush()
    return session_read(gs)


@router.post("/sessions/{session_id}/extend", response_model=SessionRead)
async def extend_session_with_package(
    session_id: UUID,
    payload: SessionExtend,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    """Add a paid extension package on top of a running package session.

    Only for sessions that started with a base package — open-ended sessions
    have no fixed price to extend and just keep running (use the plain timer
    endpoint above to move their reminder alarm instead).
    """
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionRead.model_validate(existing_response["body"])

    # Different idempotency keys represent distinct purchased extensions.
    # Lock the session before reading its current totals so concurrent devices
    # apply their increments serially instead of both writing from the same
    # stale amount/timer snapshot and losing one purchase.
    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if gs.status not in ("active", "paused"):
        raise BusinessRuleError("session is not running")
    if gs.package_id is None:
        raise BusinessRuleError(
            "this session has no base package to extend — it bills by elapsed time instead"
        )
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = await session.get(Shift, gs.shift_id)
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="extending a gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    extension = await session.get(GamingPackage, payload.package_id)
    if (
        not extension
        or extension.company_id != tenant.company_id
        or extension.branch_id != station.branch_id
        or extension.deleted_at is not None
        or not extension.is_active
    ):
        raise NotFoundError("package not found")
    if extension.kind != "extension":
        raise BusinessRuleError("only an extension package can extend a running session")
    if extension.station_type != station.type:
        raise BusinessRuleError("this extension is not offered for this station type")

    extra_surcharge = extra_controller_surcharge_minor(
        extra_controllers=gs.extra_controllers,
        duration_minutes=extension.duration_minutes,
    )
    gs.amount_minor = int(gs.amount_minor or 0) + extension.price_minor + extra_surcharge
    gs.timer_minutes = int(gs.timer_minutes or 0) + extension.duration_minutes
    await session.flush()
    response = session_read(gs)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post("/sessions/{session_id}/stop", response_model=SessionRead)
async def stop_session(
    session_id: UUID,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionRead.model_validate(existing_response["body"])

    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="stopping a gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    if gs.status == "ended":
        # Response-loss retry: validate the exact operational scope first,
        # then return the already-computed result.
        # The original shift may have been closed after the successful stop.
        response = session_read(gs)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_200_OK,
            body=response.model_dump(mode="json"),
        )
        return response
    if gs.status == "cancelled":
        raise BusinessRuleError("session was cancelled")
    if gs.status not in ("active", "paused"):
        raise BusinessRuleError(f"cannot stop a session in status={gs.status}")
    if shift.status != "open":
        raise BusinessRuleError(
            "Shift is "
            f"{shift.status}. Open a shift for this terminal before stopping a gaming session."
        )
    gs.end_at = datetime.now(timezone.utc)
    elapsed_seconds = max(0.0, (gs.end_at - gs.start_at).total_seconds())
    elapsed_minutes = ceil(elapsed_seconds / 60) if elapsed_seconds > 0 else 0
    gs.billable_minutes = max(0, elapsed_minutes - gs.paused_minutes)
    if gs.package_id is None:
        # Open-ended session — bill by actual elapsed time, as before.
        gs.amount_minor = session_amount_minor(gs.billable_minutes, gs.rate_per_hour_minor)
    # else: a package session's amount_minor was locked in at start (plus any
    # paid extensions) and never changes based on how long they actually
    # played — billable_minutes above is still recorded for the audit trail.
    gs.status = "ended"
    response = session_read(gs)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post("/sessions/{session_id}/cancel", response_model=SessionRead)
async def cancel_session(
    session_id: UUID,
    payload: SessionCancel,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    """Cancel a mistaken/unbillable session with an auditable reason.

    Operational staff may cancel a session on the exact current branch and
    terminal while its shift is open. A protected owner may also clean up a
    legacy ended session whose shift was already closed, but cannot cancel a
    session that already produced an order.
    """
    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="cancelling a gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    if gs.status == "cancelled":
        # Safe response-loss replay, including after the original shift closes.
        return session_read(gs)
    if gs.order_id is not None:
        raise BusinessRuleError("cannot cancel a session that was already sent to POS")
    if gs.status not in ("active", "paused", "ended"):
        raise BusinessRuleError(f"cannot cancel a session in status={gs.status}")
    if shift.status != "open" and not tenant.protected_access:
        raise BusinessRuleError(
            f"Shift is {shift.status}. Ask a protected owner to cancel this legacy session."
        )
    if shift.status != "open" and gs.status != "ended":
        raise BusinessRuleError(
            "A protected owner may only cancel a legacy ended session after its shift closed."
        )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="cancel a gaming or shisha session on this shift",
    )

    now = datetime.now(timezone.utc)
    gs.status = "cancelled"
    gs.end_at = gs.end_at or now
    gs.billable_minutes = 0
    gs.amount_minor = 0
    gs.cancelled_at = now
    gs.cancelled_by = tenant.user_id
    gs.cancel_reason = payload.reason
    await session.flush()
    return session_read(gs)


async def _ensure_session_menu_item(
    session, *, company_id: UUID, station: Station
) -> MenuItem:
    """Get-or-create the hidden MenuItem used to bill a station's time-based
    sessions. Never shown in the normal POS menu grid (is_available=False) —
    price/tax are always taken from the session, not this item's base price.
    """
    # Serialize the first hidden-item/category creation per company. Without
    # this lock two stations of the same type can both pass the SELECT and race
    # into the company/SKU or company/category unique constraints.
    company = (
        await session.execute(
            select(Company).where(Company.id == company_id).with_for_update()
        )
    ).scalar_one_or_none()
    if company is None:
        raise NotFoundError("company not found")

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


async def _reprice_session_order_for_customer(
    session,
    *,
    order: Order,
    company_id: UUID,
) -> None:
    """Apply POS membership pricing after the session/order link exists.

    The local import avoids a module cycle because the API composer imports
    Gaming before POS.  Keeping this bridge patchable also makes the gaming
    route's handoff contract testable without a database.
    """
    from app.api.v1.pos.router import _reprice_unpaid_order_for_customer

    await _reprice_unpaid_order_for_customer(
        session,
        order=order,
        company_id=company_id,
    )


async def _create_session_pos_order(
    session,
    *,
    gaming_session: GamingSession,
    station: Station,
    target_shift: Shift,
    company_id: UUID,
    opened_by: UUID,
) -> Order:
    """Create one held POS order without rewriting session provenance."""
    item = await _ensure_session_menu_item(
        session,
        company_id=company_id,
        station=station,
    )

    tax_rate = Decimal(
        str(
            gaming_session.tax_rate
            if gaming_session.tax_rate is not None
            else station.tax_rate
        )
    )
    rate_includes_tax = (
        gaming_session.rate_includes_tax
        if gaming_session.rate_includes_tax is not None
        else station.rate_includes_tax
    )
    amount_minor = int(gaming_session.amount_minor or 0)
    priced = await OrderPricingService(session).price_time_based_line(
        company_id=company_id,
        branch_id=target_shift.branch_id,
        amount_minor=amount_minor,
        tax_rate=tax_rate,
        rate_includes_tax=rate_includes_tax,
        customer_phone=gaming_session.customer_phone,
        item_type=_MENU_TYPE_FOR_STATION.get(station.type, "gaming"),
    )
    order_total_minor, round_off_minor = _round_to_rupee(priced.total_minor)

    now = datetime.now(timezone.utc)
    note = (
        f"{gaming_session.billable_minutes or 0} min @ "
        f"{gaming_session.rate_per_hour_minor / 100:.2f}/hr"
    )
    order = Order(
        id=uuid4(),
        company_id=company_id,
        branch_id=target_shift.branch_id,
        terminal_id=target_shift.terminal_id,
        shift_id=target_shift.id,
        opened_by=opened_by,
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
        discount_minor=priced.discount_minor,
        tax_minor=priced.cgst_minor + priced.sgst_minor + priced.igst_minor,
        round_off_minor=round_off_minor,
        total_minor=order_total_minor,
        customer_name=gaming_session.customer_name,
        customer_phone=gaming_session.customer_phone,
        notes=f"{station.name} — {note}",
    )
    session.add(order)
    await session.flush()

    session.add(
        OrderLine(
            id=uuid4(),
            order_id=order.id,
            menu_item_id=item.id,
            qty=1,
            unit_price_minor=priced.total_minor,
            line_total_minor=priced.total_minor,
            discount_minor=priced.discount_minor,
            hsn_or_sac=(
                gaming_session.sac_code or station.sac_code or item.hsn_code or ""
            ),
            tax_rate=float(tax_rate),
            taxable_value_minor=priced.taxable_minor,
            cgst_minor=priced.cgst_minor,
            sgst_minor=priced.sgst_minor,
            igst_minor=priced.igst_minor,
            cess_minor=0,
            note=note,
        )
    )
    gaming_session.order_id = order.id
    await session.flush()
    if order.customer_phone:
        await _reprice_session_order_for_customer(
            session,
            order=order,
            company_id=company_id,
        )
        await session.flush()
    return order


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
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")

    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")

    if gs.order_id is not None:
        existing_order = await session.get(Order, gs.order_id)
        existing_order = require_operational_order(
            existing_order,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="resuming a session sent to POS",
        )
        return {
            "order_id": str(existing_order.id),
            "amount_minor": int(existing_order.total_minor),
        }
    if gs.status != "ended":
        raise BusinessRuleError("stop the session before sending it to POS")
    if int(gs.amount_minor or 0) <= 0:
        raise BusinessRuleError(
            "session has no billable amount; cancel it with a reason instead of sending it to POS"
        )

    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    # A closed source shift is precisely the recoverable condition. Detect it
    # after tenant/branch validation but before comparing terminals: the
    # protected owner may intentionally recover an old terminal's session onto
    # the current operational terminal through the dedicated endpoint.
    if shift is None or shift.company_id != tenant.company_id:
        raise NotFoundError("Shift not found for this company.")
    if shift.branch_id != station.branch_id:
        raise BusinessRuleError(
            "Shift branch does not match the gaming station branch."
        )
    if shift.branch_id != tenant.branch_id:
        raise BusinessRuleError("Shift belongs to a different branch.")
    if shift.status != "open":
        raise GamingSourceShiftClosedError(
            "The session's original shift is closed. Ask the protected owner to "
            "reconcile this stopped session to the current terminal's open shift."
        )
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending a session to POS",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    order = await _create_session_pos_order(
        session,
        gaming_session=gs,
        station=station,
        target_shift=shift,
        company_id=tenant.company_id,
        opened_by=tenant.user_id,
    )
    return {"order_id": str(order.id), "amount_minor": int(order.total_minor)}


@router.post(
    "/sessions/{session_id}/reconcile-to-pos",
    response_model=SessionReconcileToPosRead,
    status_code=status.HTTP_201_CREATED,
)
async def reconcile_session_to_pos(
    session_id: UUID,
    payload: SessionReconcileToPos,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
) -> SessionReconcileToPosRead:
    """Move an ended bill off a closed source shift without rewriting history."""
    if not tenant.audit_access:
        raise ForbiddenError(
            "Only the protected owner can reconcile a stopped session to another shift."
        )
    if tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the POS terminal used by this device before reconciling a session."
        )
    if tenant.branch_id is None:
        raise BusinessRuleError(
            "This account has no branch assigned. Assign one before reconciling a session."
        )

    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")

    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if station.branch_id != tenant.branch_id:
        raise BusinessRuleError(
            "The gaming station belongs to a different branch than this terminal."
        )

    if gs.order_id is not None:
        existing_order = await session.get(Order, gs.order_id)
        existing_order = require_operational_order(
            existing_order,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="resuming a reconciled session sent to POS",
        )
        return SessionReconcileToPosRead(
            order_id=existing_order.id,
            amount_minor=int(existing_order.total_minor),
            source_shift_id=gs.shift_id,
            target_shift_id=existing_order.shift_id,
            already_linked=True,
        )
    if gs.status != "ended":
        raise BusinessRuleError(
            "Only an ended session can be reconciled. Stop the session first."
        )
    if int(gs.amount_minor or 0) <= 0:
        raise BusinessRuleError(
            "The session has no billable amount. Cancel it with a reason instead."
        )

    source_shift = await session.get(Shift, gs.shift_id)
    if not source_shift or source_shift.company_id != tenant.company_id:
        raise NotFoundError("The session's original shift was not found for this company.")
    if source_shift.branch_id != station.branch_id:
        raise BusinessRuleError(
            "The session's original shift does not match the gaming station branch."
        )
    if source_shift.status == "open":
        raise BusinessRuleError(
            "The session's original shift is still open. Use Send to POS normally."
        )
    if source_shift.status not in {"closed", "reconciled"}:
        raise BusinessRuleError(
            "The session's original shift has an unsupported status and cannot be reconciled."
        )

    target_shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.target_shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    target_shift = require_open_operational_shift(
        target_shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="reconciling a stopped session to POS",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    source_shift_id = gs.shift_id
    order = await _create_session_pos_order(
        session,
        gaming_session=gs,
        station=station,
        target_shift=target_shift,
        company_id=tenant.company_id,
        opened_by=tenant.user_id,
    )
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_session_reconcile_to_pos",
            entity_type="GamingSession",
            entity_id=str(gs.id),
            before={
                "source_shift_id": str(source_shift_id),
                "order_id": None,
            },
            after={
                "source_shift_id": str(source_shift_id),
                "target_shift_id": str(target_shift.id),
                "order_id": str(order.id),
                "reason": payload.reason,
            },
            terminal_id=tenant.terminal_id,
            reason=payload.reason,
        )
    )
    await session.flush()
    return SessionReconcileToPosRead(
        order_id=order.id,
        amount_minor=int(order.total_minor),
        source_shift_id=source_shift_id,
        target_shift_id=target_shift.id,
        already_linked=False,
    )


def _booking_read(bk: GamingBooking, *, station_code: str) -> BookingRead:
    return BookingRead(
        id=bk.id, station_id=bk.station_id, station_code=station_code,
        starts_at=bk.starts_at, ends_at=bk.ends_at, guest_name=bk.guest_name,
        contact=bk.contact, party_size=bk.party_size, deposit_minor=bk.deposit_minor,
        status=bk.status, created_at=bk.created_at,
    )


@router.get("/bookings", response_model=list[BookingRead])
async def list_bookings(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    status_filter: list[str] | None = Query(default=None, alias="status"),
    limit: int = Query(default=200, ge=1, le=500),
) -> list[BookingRead]:
    """List station bookings for the current branch, soonest first.

    Defaults to upcoming only (starts_at from today onward, local time) so a
    booking made months ago doesn't clutter the staff view forever — pass an
    explicit `from_date` to look further back. `to_date` is optional and
    unbounded when omitted.
    """
    branch_id = _current_gaming_branch_id(tenant)
    stmt = (
        select(GamingBooking, Station.code)
        .join(Station, Station.id == GamingBooking.station_id)
        .where(
            Station.company_id == tenant.company_id,
            Station.branch_id == branch_id,
        )
    )
    if status_filter:
        stmt = stmt.where(GamingBooking.status.in_(status_filter))
    timezone_name = await company_timezone(session, tenant.company_id)
    f_d = from_date or local_today(timezone_name)
    f_dt, _ = local_date_bounds_utc(f_d, f_d, timezone_name)
    stmt = stmt.where(GamingBooking.starts_at >= f_dt)
    if to_date:
        _, t_dt = local_date_bounds_utc(to_date, to_date, timezone_name)
        stmt = stmt.where(GamingBooking.starts_at < t_dt)
    stmt = stmt.order_by(GamingBooking.starts_at.asc()).limit(limit)
    rows = (await session.execute(stmt)).all()
    return [_booking_read(bk, station_code=code) for bk, code in rows]


@router.post("/bookings", status_code=status.HTTP_201_CREATED)
async def create_booking(
    payload: BookingCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> dict:
    if payload.ends_at <= payload.starts_at:
        raise BusinessRuleError("ends_at must be after starts_at")
    branch_id = _current_gaming_branch_id(tenant)
    station = await _operational_station(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        station_id=payload.station_id,
        for_update=True,
    )
    if not station:
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
    try:
        # The EXCLUDE constraint at the DB level rejects overlapping bookings.
        # Flushing here (rather than letting it surface at the implicit
        # request-boundary commit) lets us turn that into a clean 409 instead
        # of an unhandled IntegrityError reaching the generic 500 handler.
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise ConflictError(
            "This station is already booked for that time slot."
        ) from exc
    return {"id": str(bk.id), "status": bk.status}


@router.patch("/bookings/{booking_id}/status", response_model=BookingRead)
async def update_booking_status(
    booking_id: UUID,
    payload: BookingStatusUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> BookingRead:
    branch_id = _current_gaming_branch_id(tenant)
    bk = await _tenant_booking(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        booking_id=booking_id,
        for_update=True,
    )
    if not bk:
        raise NotFoundError("booking not found")
    allowed = _BOOKING_TRANSITIONS.get(bk.status, set())
    if payload.status not in allowed:
        raise ConflictError(
            f"cannot change booking from '{bk.status}' to '{payload.status}'"
        )
    bk.status = payload.status
    await session.flush()
    station_code = (
        await session.execute(select(Station.code).where(Station.id == bk.station_id))
    ).scalar_one()
    return _booking_read(bk, station_code=station_code)
