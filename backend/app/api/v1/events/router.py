"""Events module — projector screenings + ticket sale + check-in.

Endpoints:
  GET    /events/upcoming                       — list scheduled events
  POST   /events                                — create event (manager)
  GET    /events/{event_id}                     — event detail (capacity remaining)
  POST   /events/{event_id}/tickets             — sell a ticket
  POST   /events/{event_id}/tickets/{ticket_id}/check-in
  GET    /events/{event_id}/tickets             — list tickets for an event (manager)

Tax: SAC 999692, 18% (CGST 9% + SGST 9%). Same engine as gaming.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, status
from pydantic import BaseModel, Field
from sqlalchemy import and_, func, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.models import Branch, Event, EventTicket

router = APIRouter()


# ----------------------------- schemas -----------------------------
class EventCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    description: str | None = None
    event_type: Literal["football", "cricket", "movie", "esports", "other"]
    screen: str = "Main Screen"
    starts_at: datetime
    ends_at: datetime | None = None
    capacity: int = Field(gt=0)
    base_ticket_price_minor: int = Field(ge=0)
    poster_url: str | None = None
    branch_id: UUID | None = None  # optional — falls back to company default


class EventUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = None
    event_type: Literal["football", "cricket", "movie", "esports", "other"] | None = None
    screen: str | None = None
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    capacity: int | None = Field(default=None, gt=0)
    base_ticket_price_minor: int | None = Field(default=None, ge=0)
    poster_url: str | None = None
    status: Literal["scheduled", "live", "ended", "cancelled"] | None = None


class EventRead(BaseModel):
    id: UUID
    name: str
    description: str | None
    event_type: str
    screen: str
    starts_at: datetime
    ends_at: datetime | None
    capacity: int
    sold: int
    remaining: int
    base_ticket_price_minor: int
    sac_code: str
    tax_rate: float
    status: str
    poster_url: str | None


class TicketSell(BaseModel):
    customer_name: str = Field(min_length=1, max_length=200)
    customer_phone: str | None = None
    seat: str | None = None
    qty: int = Field(default=1, ge=1, le=20)
    note: str | None = None


class TicketRead(BaseModel):
    id: UUID
    ticket_no: str
    event_id: UUID
    event_name: str
    customer_name: str | None
    customer_phone: str | None
    seat: str | None
    price_paid_minor: int
    status: str
    checked_in_at: datetime | None


# ----------------------------- helpers -----------------------------
async def _event_or_404(session, event_id: UUID, company_id: UUID) -> Event:
    ev = await session.get(Event, event_id)
    if not ev or ev.company_id != company_id or ev.deleted_at is not None:
        raise NotFoundError("event not found")
    return ev


async def _sold_count(session, event_id: UUID) -> int:
    """Active (non-cancelled, non-refunded) ticket count for an event."""
    result = await session.execute(
        select(func.count(EventTicket.id)).where(
            and_(
                EventTicket.event_id == event_id,
                EventTicket.status.in_(("sold", "checked_in")),
            )
        )
    )
    return int(result.scalar_one() or 0)


def _ticket_number(event_starts: datetime, seq: int) -> str:
    """Format: EVT-YYYYMMDD-NNNN  (e.g. EVT-20260612-0001)."""
    return f"EVT-{event_starts.strftime('%Y%m%d')}-{seq:04d}"


# ----------------------------- endpoints -----------------------------
@router.get("/upcoming", response_model=list[EventRead])
async def list_upcoming(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
    limit: int = 50,
) -> list[EventRead]:
    """Events with status='scheduled' OR 'live' whose start is ≥ today."""
    now = datetime.now(timezone.utc)
    rows = (
        await session.execute(
            select(Event)
            .where(
                Event.company_id == tenant.company_id,
                Event.deleted_at.is_(None),
                Event.status.in_(("scheduled", "live")),
                Event.starts_at >= now,
            )
            .order_by(Event.starts_at)
            .limit(min(limit, 200))
        )
    ).scalars().all()

    out: list[EventRead] = []
    for ev in rows:
        sold = await _sold_count(session, ev.id)
        out.append(_to_read(ev, sold))
    return out


@router.post("", response_model=EventRead, status_code=status.HTTP_201_CREATED)
async def create_event(
    payload: EventCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.tournament.manage")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> EventRead:
    require_pricing_unlock(x_pricing_token, tenant)
    if payload.ends_at and payload.ends_at <= payload.starts_at:
        raise BusinessRuleError("ends_at must be after starts_at")

    # Resolve branch: payload → token → company's first non-deleted branch.
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
            "no branch exists for this company — create one in Settings → Branches first"
        )

    ev = Event(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=branch_id,
        name=payload.name,
        description=payload.description,
        event_type=payload.event_type,
        screen=payload.screen,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        capacity=payload.capacity,
        base_ticket_price_minor=payload.base_ticket_price_minor,
        poster_url=payload.poster_url,
    )
    session.add(ev)
    await session.flush()
    return _to_read(ev, 0)


@router.patch("/{event_id}", response_model=EventRead)
async def update_event(
    event_id: UUID,
    payload: EventUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.tournament.manage")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> EventRead:
    if payload.base_ticket_price_minor is not None:
        require_pricing_unlock(x_pricing_token, tenant)
    ev = await _event_or_404(session, event_id, tenant.company_id)
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(ev, f, v)
    if ev.ends_at and ev.ends_at <= ev.starts_at:
        raise BusinessRuleError("ends_at must be after starts_at")
    await session.flush()
    sold = await _sold_count(session, ev.id)
    return _to_read(ev, sold)


@router.delete("/{event_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_event(
    event_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.tournament.manage")),
):
    ev = await _event_or_404(session, event_id, tenant.company_id)
    sold = await _sold_count(session, event_id)
    if sold > 0:
        raise BusinessRuleError(
            f"cannot delete — {sold} ticket(s) already sold. Cancel the event instead "
            "(PATCH with status='cancelled')."
        )
    ev.deleted_at = datetime.now(timezone.utc)
    await session.flush()


@router.get("/all", response_model=list[EventRead])
async def list_all_events(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
    include_past: bool = True,
    limit: int = 200,
) -> list[EventRead]:
    """All non-deleted events, newest first. Useful for the management screen."""
    stmt = (
        select(Event)
        .where(Event.company_id == tenant.company_id, Event.deleted_at.is_(None))
        .order_by(Event.starts_at.desc())
        .limit(min(limit, 500))
    )
    if not include_past:
        now = datetime.now(timezone.utc)
        stmt = stmt.where(Event.starts_at >= now)
    rows = (await session.execute(stmt)).scalars().all()
    out: list[EventRead] = []
    for ev in rows:
        sold = await _sold_count(session, ev.id)
        out.append(_to_read(ev, sold))
    return out


@router.get("/{event_id}", response_model=EventRead)
async def get_event(
    event_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> EventRead:
    ev = await _event_or_404(session, event_id, tenant.company_id)
    sold = await _sold_count(session, event_id)
    return _to_read(ev, sold)


@router.post(
    "/{event_id}/tickets",
    response_model=list[TicketRead],
    status_code=status.HTTP_201_CREATED,
)
async def sell_tickets(
    event_id: UUID,
    payload: TicketSell,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> list[TicketRead]:
    """Sell N tickets to the same customer.

    Each ticket gets its own row + sequential ticket_no. Payment is recorded
    against the parent Order (next iteration wires this into the POS pipeline);
    for now we record price_paid_minor on the ticket as the GST-inclusive amount.
    """
    ev = await _event_or_404(session, event_id, tenant.company_id)
    if ev.status not in {"scheduled", "live"}:
        raise BusinessRuleError(f"event status={ev.status}, cannot sell tickets")

    sold = await _sold_count(session, event_id)
    if sold + payload.qty > ev.capacity:
        raise BusinessRuleError(
            f"capacity exceeded — {ev.capacity - sold} seat(s) remaining"
        )

    out: list[TicketRead] = []
    for i in range(payload.qty):
        tno = _ticket_number(ev.starts_at, sold + i + 1)
        ticket = EventTicket(
            id=uuid4(),
            event_id=ev.id,
            ticket_no=tno,
            order_id=None,  # wired in POS-integration pass
            customer_name=payload.customer_name,
            customer_phone=payload.customer_phone,
            seat=payload.seat,
            price_paid_minor=ev.base_ticket_price_minor,
            status="sold",
            sold_by=tenant.user_id,
            note=payload.note,
        )
        session.add(ticket)
        await session.flush()
        out.append(
            TicketRead(
                id=ticket.id,
                ticket_no=ticket.ticket_no,
                event_id=ev.id,
                event_name=ev.name,
                customer_name=ticket.customer_name,
                customer_phone=ticket.customer_phone,
                seat=ticket.seat,
                price_paid_minor=ticket.price_paid_minor,
                status=ticket.status,
                checked_in_at=ticket.checked_in_at,
            )
        )
    return out


@router.post("/{event_id}/tickets/{ticket_id}/check-in", response_model=TicketRead)
async def check_in_ticket(
    event_id: UUID,
    ticket_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> TicketRead:
    ev = await _event_or_404(session, event_id, tenant.company_id)
    ticket = await session.get(EventTicket, ticket_id)
    if not ticket or ticket.event_id != event_id:
        raise NotFoundError("ticket not found")
    if ticket.status == "checked_in":
        raise BusinessRuleError("ticket already checked in")
    if ticket.status in {"cancelled", "refunded", "no_show"}:
        raise BusinessRuleError(f"ticket status={ticket.status}, cannot check in")
    ticket.status = "checked_in"
    ticket.checked_in_at = datetime.now(timezone.utc)
    ticket.checked_in_by = tenant.user_id
    return TicketRead(
        id=ticket.id,
        ticket_no=ticket.ticket_no,
        event_id=ev.id,
        event_name=ev.name,
        customer_name=ticket.customer_name,
        customer_phone=ticket.customer_phone,
        seat=ticket.seat,
        price_paid_minor=ticket.price_paid_minor,
        status=ticket.status,
        checked_in_at=ticket.checked_in_at,
    )


@router.get("/{event_id}/tickets", response_model=list[TicketRead])
async def list_tickets(
    event_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[TicketRead]:
    ev = await _event_or_404(session, event_id, tenant.company_id)
    rows = (
        await session.execute(
            select(EventTicket)
            .where(EventTicket.event_id == event_id)
            .order_by(EventTicket.ticket_no)
        )
    ).scalars().all()
    return [
        TicketRead(
            id=t.id,
            ticket_no=t.ticket_no,
            event_id=ev.id,
            event_name=ev.name,
            customer_name=t.customer_name,
            customer_phone=t.customer_phone,
            seat=t.seat,
            price_paid_minor=t.price_paid_minor,
            status=t.status,
            checked_in_at=t.checked_in_at,
        )
        for t in rows
    ]


def _to_read(ev: Event, sold: int) -> EventRead:
    return EventRead(
        id=ev.id,
        name=ev.name,
        description=ev.description,
        event_type=ev.event_type,
        screen=ev.screen,
        starts_at=ev.starts_at,
        ends_at=ev.ends_at,
        capacity=ev.capacity,
        sold=sold,
        remaining=max(0, ev.capacity - sold),
        base_ticket_price_minor=ev.base_ticket_price_minor,
        sac_code=ev.sac_code,
        tax_rate=float(ev.tax_rate),
        status=ev.status,
        poster_url=ev.poster_url,
    )
