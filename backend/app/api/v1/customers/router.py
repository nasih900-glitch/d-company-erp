"""Customer endpoints — phone-based loyalty foundation.

Endpoints:
  GET    /customers                     — list (filterable by phone substring)
  GET    /customers/by-phone/{phone}    — lookup by exact phone (POS quick-attach)
  GET    /customers/{id}                — detail
  POST   /customers                     — upsert by phone (create or fetch)
  PATCH  /customers/{id}                — edit name/email/birthday/notes
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import or_, select

from app.core.db import SessionDep
from app.core.errors import NotFoundError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import Customer

router = APIRouter()


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
    last_visit_at: datetime | None
    notes: str | None


class CustomerUpsert(BaseModel):
    phone: str = Field(min_length=4, max_length=20)
    name: str | None = None
    email: str | None = None
    birthday: datetime | None = None
    notes: str | None = None


class CustomerUpdate(BaseModel):
    name: str | None = None
    email: str | None = None
    birthday: datetime | None = None
    notes: str | None = None


# ---------------------------------------------------------------- helpers
def _to_read(c: Customer) -> CustomerRead:
    return CustomerRead(
        id=c.id, name=c.name, phone=c.phone, email=c.email,
        birthday=c.birthday, visit_count=c.visit_count,
        total_spent_minor=c.total_spent_minor, loyalty_points=c.loyalty_points,
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
    await session.flush()
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
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(c, f, v)
    await session.flush()
    return _to_read(c)
