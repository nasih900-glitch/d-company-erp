"""Customer endpoints — phone-based loyalty foundation.

Endpoints:
  GET    /customers                     — list (filterable by phone substring)
  GET    /customers/by-phone/{phone}    — lookup by exact phone (POS quick-attach)
  GET    /customers/{id}                — detail
  GET    /customers/{id}/history        — stable customer-linked purchase history
  POST   /customers                     — upsert by phone (create or fetch)
  PATCH  /customers/{id}                — edit name/phone/email/birthday/notes
  DELETE /customers/{id}                — soft-delete + anonymise
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select
from sqlalchemy.exc import IntegrityError

from app.core.db import SessionDep
from app.core.errors import ConflictError, NotFoundError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import Customer, GamingSession, Order, OrderLine, Payment, Refund, Station, Table
from app.services.pos.points import gaming_rank, rank_progress, rewards_available_to

router = APIRouter()


class RewardRead(BaseModel):
    key: str
    name: str
    description: str
    points_cost: int
    value_minor: int
    min_rank: str
    affordable: bool


# ---------------------------------------------------------------- DTOs
class CustomerRead(BaseModel):
    id: UUID
    name: str | None
    phone: str
    email: str | None
    birthday: datetime | None
    visit_count: int
    total_spent_minor: int
    loyalty_points: int
    lifetime_gaming_points_earned: int
    gaming_rank: str
    gaming_rank_floor: int
    next_gaming_rank: str | None
    next_gaming_rank_floor: int | None
    points_to_next_gaming_rank: int | None
    last_visit_at: datetime | None
    notes: str | None


class CustomerUpsert(BaseModel):
    phone: str = Field(min_length=4, max_length=20)
    name: str | None = Field(default=None, max_length=200)
    email: str | None = Field(default=None, max_length=254)
    birthday: datetime | None = None
    notes: str | None = Field(default=None, max_length=500)


class CustomerUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=200)
    phone: str | None = Field(default=None, max_length=20)
    email: str | None = Field(default=None, max_length=254)
    birthday: datetime | None = None
    notes: str | None = Field(default=None, max_length=500)


class CustomerOrderHistoryRead(BaseModel):
    """A compact, financially truthful customer purchase-history row.

    The stable ``Order.customer_id`` relationship is deliberately used rather
    than matching phone snapshots. Phone numbers can be corrected or reused;
    treating an old phone string as identity would expose one customer's
    purchases to another and corrupt lifetime-value review.
    """

    id: UUID
    invoice_no: str | None
    status: str
    type: str
    source_label: str | None
    total_minor: int
    paid_minor: int
    refunded_minor: int
    points_redeemed_minor: int
    items_count: int
    payment_methods: list[str]
    created_at: datetime
    invoice_issued_at: datetime | None


# ---------------------------------------------------------------- helpers
def _to_read(c: Customer) -> CustomerRead:
    progress = rank_progress(c.lifetime_gaming_points_earned)
    return CustomerRead(
        id=c.id, name=c.name, phone=c.phone, email=c.email,
        birthday=c.birthday, visit_count=c.visit_count,
        total_spent_minor=c.total_spent_minor, loyalty_points=c.loyalty_points,
        lifetime_gaming_points_earned=progress.lifetime_points,
        gaming_rank=progress.rank,
        gaming_rank_floor=progress.rank_floor,
        next_gaming_rank=progress.next_rank,
        next_gaming_rank_floor=progress.next_rank_floor,
        points_to_next_gaming_rank=progress.points_to_next_rank,
        last_visit_at=c.last_visit_at, notes=c.notes,
    )


# ---------------------------------------------------------------- endpoints
@router.get("", response_model=list[CustomerRead])
async def list_customers(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    q: str | None = None,
    limit: int = 100,
) -> list[CustomerRead]:
    stmt = (
        select(Customer)
        .where(Customer.company_id == tenant.company_id, Customer.deleted_at.is_(None))
        .order_by(Customer.last_visit_at.desc().nullslast())
        .limit(min(limit, 500))
    )
    if q:
        like = f"%{q}%"
        stmt = stmt.where(or_(Customer.phone.ilike(like), Customer.name.ilike(like)))
    rows = (await session.execute(stmt)).scalars().all()
    return [_to_read(c) for c in rows]


@router.get("/by-phone/{phone}", response_model=CustomerRead | None)
async def get_by_phone(
    phone: str,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> CustomerRead | None:
    """Quick POS lookup — returns the customer or null. Used during checkout
    to auto-fill a returning customer's name."""
    c = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == tenant.company_id,
                Customer.phone == phone,
                Customer.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    return _to_read(c) if c else None


@router.get("/by-phone/{phone}/rewards", response_model=list[RewardRead])
async def get_rewards_by_phone(
    phone: str,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> list[RewardRead]:
    """Rewards this phone number's rank has unlocked, with an affordable flag
    against their current spendable balance — for the POS redemption menu."""
    c = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == tenant.company_id,
                Customer.phone == phone,
                Customer.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if not c:
        return []
    rank = gaming_rank(c.lifetime_gaming_points_earned)
    return [
        RewardRead(
            key=r.key, name=r.name, description=r.description,
            points_cost=r.points_cost, value_minor=r.value_minor, min_rank=r.min_rank,
            affordable=c.loyalty_points >= r.points_cost,
        )
        for r in rewards_available_to(rank)
    ]


@router.get("/{customer_id}", response_model=CustomerRead)
async def get_customer(
    customer_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> CustomerRead:
    c = await session.get(Customer, customer_id)
    if not c or c.company_id != tenant.company_id or c.deleted_at:
        raise NotFoundError("customer not found")
    return _to_read(c)


@router.get("/{customer_id}/history", response_model=list[CustomerOrderHistoryRead])
async def get_customer_purchase_history(
    customer_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    limit: int = 50,
) -> list[CustomerOrderHistoryRead]:
    """Return recent purchases linked to this exact customer record.

    This is company-scoped rather than terminal-scoped: a returning customer
    should see their own history even when a different till handled the sale.
    Only the stable customer foreign key is accepted. Historical orders that
    pre-date that linkage remain outside this view until an owner explicitly
    reconciles them; guessing by a mutable phone snapshot is unsafe.
    """

    customer = await session.get(Customer, customer_id)
    if (
        not customer
        or customer.company_id != tenant.company_id
        or customer.deleted_at is not None
    ):
        raise NotFoundError("customer not found")

    orders = (
        await session.execute(
            select(Order)
            .where(
                Order.company_id == tenant.company_id,
                Order.customer_id == customer_id,
            )
            .order_by(Order.created_at.desc())
            .limit(min(max(limit, 1), 100))
        )
    ).scalars().all()
    if not orders:
        return []

    order_ids = [order.id for order in orders]
    item_counts = dict(
        (
            await session.execute(
                select(OrderLine.order_id, func.count())
                .where(
                    OrderLine.order_id.in_(order_ids),
                    OrderLine.voided_at.is_(None),
                )
                .group_by(OrderLine.order_id)
            )
        ).all()
    )
    payment_rows = (
        await session.execute(
            select(Payment.order_id, Payment.method, Payment.amount_minor)
            .where(Payment.order_id.in_(order_ids))
            .order_by(Payment.paid_at)
        )
    ).all()
    paid_by_order: dict[UUID, int] = {}
    methods_by_order: dict[UUID, list[str]] = {}
    for order_id, method, amount_minor in payment_rows:
        paid_by_order[order_id] = paid_by_order.get(order_id, 0) + int(amount_minor)
        methods = methods_by_order.setdefault(order_id, [])
        if method not in methods:
            methods.append(method)

    refunded_by_order = dict(
        (
            await session.execute(
                select(Refund.order_id, func.coalesce(func.sum(Refund.amount_minor), 0))
                .where(Refund.order_id.in_(order_ids))
                .group_by(Refund.order_id)
            )
        ).all()
    )
    table_ids = [order.table_id for order in orders if order.table_id is not None]
    table_codes = dict(
        (
            await session.execute(
                select(Table.id, Table.code).where(Table.id.in_(table_ids))
            )
        ).all()
    ) if table_ids else {}
    station_names = dict(
        (
            await session.execute(
                select(GamingSession.order_id, Station.name)
                .join(Station, Station.id == GamingSession.station_id)
                .where(GamingSession.order_id.in_(order_ids))
            )
        ).all()
    )

    result: list[CustomerOrderHistoryRead] = []
    for order in orders:
        source_label = station_names.get(order.id)
        if source_label is None and order.table_id in table_codes:
            source_label = f"Table {table_codes[order.table_id]}"
        result.append(
            CustomerOrderHistoryRead(
                id=order.id,
                invoice_no=order.invoice_no,
                status=order.status,
                type=order.type,
                source_label=source_label,
                total_minor=int(order.total_minor or 0),
                paid_minor=int(paid_by_order.get(order.id, 0)),
                refunded_minor=int(refunded_by_order.get(order.id, 0)),
                points_redeemed_minor=int(order.points_redeemed_minor or 0),
                items_count=int(item_counts.get(order.id, 0)),
                payment_methods=methods_by_order.get(order.id, []),
                created_at=order.created_at,
                invoice_issued_at=order.invoice_issued_at,
            )
        )
    return result


@router.post("", response_model=CustomerRead, status_code=status.HTTP_201_CREATED)
async def upsert_customer(
    payload: CustomerUpsert,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> CustomerRead:
    """Upsert by phone — creates if new, returns existing if phone seen before.
    Lets POS just call this without worrying about whether the customer exists.
    """
    existing = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == tenant.company_id,
                Customer.phone == payload.phone,
                Customer.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if existing:
        # Update missing fields if caller supplied better data
        if payload.name and not existing.name:
            existing.name = payload.name
        if payload.email and not existing.email:
            existing.email = payload.email
        if payload.birthday and not existing.birthday:
            existing.birthday = payload.birthday
        await session.flush()
        return _to_read(existing)

    c = Customer(
        id=uuid4(),
        company_id=tenant.company_id,
        phone=payload.phone,
        name=payload.name,
        email=payload.email,
        birthday=payload.birthday,
        notes=payload.notes,
    )
    session.add(c)
    try:
        await session.flush()
    except IntegrityError:
        # Two concurrent upserts for a brand-new phone number both saw
        # `existing is None` above and both tried to insert — the partial
        # unique index (uq_customer_phone_per_company_live) catches the
        # loser here. That's not a real conflict for an *upsert*: the whole
        # point of this endpoint is "return the customer for this phone,
        # whoever created it," so heal into the winner's row instead of
        # surfacing a 500 (or a 409 a queued offline write would have no
        # way to recover from on its own).
        await session.rollback()
        winner = (
            await session.execute(
                select(Customer).where(
                    Customer.company_id == tenant.company_id,
                    Customer.phone == payload.phone,
                    Customer.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if winner is None:
            # Vanishingly unlikely (the winner would have to be soft-deleted
            # in the instant between the IntegrityError and this re-query),
            # but `scalar_one()` raising NoResultFound here would surface as
            # a bare 500 with no useful message — a clean, retryable 409 is
            # the honest response to a real (if tiny) transient race, not a
            # crash.
            raise ConflictError("This phone number was just freed up by another request — try again.")
        return _to_read(winner)
    return _to_read(c)


@router.patch("/{customer_id}", response_model=CustomerRead)
async def update_customer(
    customer_id: UUID,
    payload: CustomerUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> CustomerRead:
    c = await session.get(Customer, customer_id)
    if not c or c.company_id != tenant.company_id or c.deleted_at:
        raise NotFoundError("customer not found")
    if payload.phone is not None and payload.phone != c.phone:
        # Updated in place, by ID — never by the upsert-by-phone POST route
        # above — so the same row (and all its points/order/visit history)
        # carries over. Just needs a manual uniqueness check first, since a
        # collision here should read as a clean "already in use" rather than
        # the raw IntegrityError the DB's unique constraint would otherwise
        # surface as a 500.
        clash = (
            await session.execute(
                select(Customer).where(
                    Customer.company_id == tenant.company_id,
                    Customer.phone == payload.phone,
                    Customer.deleted_at.is_(None),
                    Customer.id != customer_id,
                )
            )
        ).scalar_one_or_none()
        if clash:
            raise ConflictError(f"Another customer already uses {payload.phone}")
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(c, f, v)
    await session.flush()
    return _to_read(c)


@router.delete("/{customer_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_customer(
    customer_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> None:
    """Soft-delete and anonymise — added by mistake, a duplicate, or a
    customer asking to have their data removed.

    Not a hard delete: PointsRedemption.customer_id is ON DELETE RESTRICT
    (any customer who's ever redeemed a reward would block a real row
    delete), and order history references customers by phone string, not by
    this row, so nothing needs the row gone — just gone from view and
    stripped of anything identifying. total_spent_minor / loyalty_points /
    visit_count are left as-is: aggregate numbers, not personal data, and
    deleting them would quietly corrupt any period report that already
    included this customer. The phone is overwritten (not just nulled) so
    it immediately frees up for a new customer — see the partial unique
    index in the customers migration.
    """
    c = await session.get(Customer, customer_id)
    if not c or c.company_id != tenant.company_id or c.deleted_at:
        raise NotFoundError("customer not found")
    c.deleted_at = datetime.now(timezone.utc)
    c.name = None
    c.email = None
    c.birthday = None
    c.notes = None
    # phone is String(20) — "deleted-" (8) + 12 hex chars fits exactly.
    c.phone = f"deleted-{uuid4().hex[:12]}"
