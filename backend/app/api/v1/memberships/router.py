"""Membership / subscription endpoints.

  GET   /memberships/tiers                       — list tiers (Silver/Gold/Platinum)
  POST  /memberships/tiers                       — create / customize a tier
  PATCH /memberships/tiers/{id}                  — edit a tier
  POST  /memberships/subscribe                   — subscribe a customer (cash for now;
                                                    Razorpay flow lands when keys arrive)
  GET   /memberships/customer/{customer_id}      — current active subscription (or null)
  POST  /memberships/{id}/cancel                 — cancel autorenew (still valid until expiry)
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.models import Customer, CustomerMembership, MembershipTier

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class TierRead(BaseModel):
    id: UUID
    code: str
    name: str
    monthly_price_minor: int
    annual_price_minor: int | None
    food_discount_pct: float
    gaming_discount_pct: float
    hookah_discount_pct: float
    point_multiplier: float
    free_gaming_minutes_per_week: int
    free_hookah_per_month: int
    priority_booking: bool
    description: str | None
    sort_order: int


class TierCreate(BaseModel):
    code: str = Field(min_length=1, max_length=20)
    name: str = Field(min_length=1, max_length=100)
    monthly_price_minor: int = Field(ge=0)
    annual_price_minor: int | None = None
    food_discount_pct: float = Field(ge=0, le=1, default=0)
    gaming_discount_pct: float = Field(ge=0, le=1, default=0)
    hookah_discount_pct: float = Field(ge=0, le=1, default=0)
    point_multiplier: float = Field(ge=1, le=10, default=1)
    free_gaming_minutes_per_week: int = 0
    free_hookah_per_month: int = 0
    priority_booking: bool = False
    description: str | None = None
    sort_order: int = 0


class TierUpdate(BaseModel):
    name: str | None = None
    monthly_price_minor: int | None = Field(default=None, ge=0)
    annual_price_minor: int | None = None
    food_discount_pct: float | None = Field(default=None, ge=0, le=1)
    gaming_discount_pct: float | None = Field(default=None, ge=0, le=1)
    hookah_discount_pct: float | None = Field(default=None, ge=0, le=1)
    point_multiplier: float | None = Field(default=None, ge=1, le=10)
    free_gaming_minutes_per_week: int | None = None
    free_hookah_per_month: int | None = None
    priority_booking: bool | None = None
    description: str | None = None
    sort_order: int | None = None


class SubscribeRequest(BaseModel):
    customer_id: UUID
    tier_id: UUID
    billing_cycle: Literal["monthly", "annual"] = "monthly"
    paid_via: Literal["cash", "card", "upi", "razorpay"] = "cash"


class SubscriptionRead(BaseModel):
    id: UUID
    customer_id: UUID
    tier_id: UUID
    tier_code: str
    tier_name: str
    billing_cycle: str
    starts_at: datetime
    expires_at: datetime
    cancelled_at: datetime | None
    auto_renew: bool
    amount_paid_minor: int
    is_active: bool


# ---------------------------------------------------------------- TIERS
def _to_tier_read(t: MembershipTier) -> TierRead:
    return TierRead(
        id=t.id, code=t.code, name=t.name,
        monthly_price_minor=t.monthly_price_minor,
        annual_price_minor=t.annual_price_minor,
        food_discount_pct=float(t.food_discount_pct or 0),
        gaming_discount_pct=float(t.gaming_discount_pct or 0),
        hookah_discount_pct=float(t.hookah_discount_pct or 0),
        point_multiplier=float(t.point_multiplier or 1),
        free_gaming_minutes_per_week=int(t.free_gaming_minutes_per_week or 0),
        free_hookah_per_month=int(t.free_hookah_per_month or 0),
        priority_booking=t.priority_booking,
        description=t.description, sort_order=int(t.sort_order or 0),
    )


@router.get("/tiers", response_model=list[TierRead])
async def list_tiers(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> list[TierRead]:
    rows = (
        await session.execute(
            select(MembershipTier)
            .where(MembershipTier.company_id == tenant.company_id, MembershipTier.deleted_at.is_(None))
            .order_by(MembershipTier.sort_order, MembershipTier.monthly_price_minor)
        )
    ).scalars().all()
    return [_to_tier_read(t) for t in rows]


@router.post("/tiers", response_model=TierRead, status_code=status.HTTP_201_CREATED)
async def create_tier(
    payload: TierCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> TierRead:
    require_pricing_unlock(x_pricing_token, tenant)
    t = MembershipTier(
        id=uuid4(),
        company_id=tenant.company_id,
        **payload.model_dump(),
    )
    session.add(t)
    await session.flush()
    return _to_tier_read(t)


@router.patch("/tiers/{tier_id}", response_model=TierRead)
async def update_tier(
    tier_id: UUID,
    payload: TierUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> TierRead:
    if payload.monthly_price_minor is not None or "annual_price_minor" in payload.model_fields_set:
        require_pricing_unlock(x_pricing_token, tenant)
    t = await session.get(MembershipTier, tier_id)
    if not t or t.company_id != tenant.company_id or t.deleted_at:
        raise NotFoundError("tier not found")
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(t, f, v)
    await session.flush()
    return _to_tier_read(t)


# ---------------------------------------------------------------- SUBSCRIPTIONS
async def _subscription_to_read(session, sub: CustomerMembership) -> SubscriptionRead:
    tier = await session.get(MembershipTier, sub.tier_id)
    now = datetime.now(timezone.utc)
    return SubscriptionRead(
        id=sub.id, customer_id=sub.customer_id, tier_id=sub.tier_id,
        tier_code=tier.code if tier else "?",
        tier_name=tier.name if tier else "Unknown",
        billing_cycle=sub.billing_cycle,
        starts_at=sub.starts_at, expires_at=sub.expires_at,
        cancelled_at=sub.cancelled_at, auto_renew=sub.auto_renew,
        amount_paid_minor=sub.amount_paid_minor,
        is_active=sub.cancelled_at is None and sub.expires_at > now,
    )


@router.post("/subscribe", response_model=SubscriptionRead, status_code=status.HTTP_201_CREATED)
async def subscribe(
    payload: SubscribeRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> SubscriptionRead:
    customer = await session.get(Customer, payload.customer_id)
    if not customer or customer.company_id != tenant.company_id:
        raise NotFoundError("customer not found")
    tier = await session.get(MembershipTier, payload.tier_id)
    if not tier or tier.company_id != tenant.company_id or tier.deleted_at:
        raise NotFoundError("tier not found")

    price = (
        tier.annual_price_minor or (tier.monthly_price_minor * 12)
        if payload.billing_cycle == "annual"
        else tier.monthly_price_minor
    )
    if not price:
        raise BusinessRuleError("tier has no price for the requested billing cycle")

    now = datetime.now(timezone.utc)
    expires = now + (timedelta(days=365) if payload.billing_cycle == "annual" else timedelta(days=30))

    sub = CustomerMembership(
        id=uuid4(),
        customer_id=customer.id,
        tier_id=tier.id,
        billing_cycle=payload.billing_cycle,
        starts_at=now,
        expires_at=expires,
        auto_renew=True,
        amount_paid_minor=price,
    )
    session.add(sub)
    await session.flush()
    return await _subscription_to_read(session, sub)


@router.get("/customer/{customer_id}", response_model=SubscriptionRead | None)
async def get_customer_subscription(
    customer_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> SubscriptionRead | None:
    """Most-recent ACTIVE subscription for a customer, or null."""
    now = datetime.now(timezone.utc)
    sub = (
        await session.execute(
            select(CustomerMembership)
            .where(
                CustomerMembership.customer_id == customer_id,
                CustomerMembership.cancelled_at.is_(None),
                CustomerMembership.expires_at > now,
            )
            .order_by(CustomerMembership.starts_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    return await _subscription_to_read(session, sub) if sub else None


@router.post("/{subscription_id}/cancel", response_model=SubscriptionRead)
async def cancel_subscription(
    subscription_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> SubscriptionRead:
    sub = await session.get(CustomerMembership, subscription_id)
    if not sub:
        raise NotFoundError("subscription not found")
    if sub.cancelled_at:
        raise BusinessRuleError("subscription already cancelled")
    sub.cancelled_at = datetime.now(timezone.utc)
    sub.auto_renew = False
    await session.flush()
    return await _subscription_to_read(session, sub)
