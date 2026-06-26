"""Membership / subscription model.

Two tables:
  - MembershipTier — Silver / Gold / Platinum etc., defined by you,
    with perks: discount %, weekday free-gaming minutes, free hookah etc.
  - CustomerMembership — a customer's active subscription to a tier,
    with start/expiry dates and renewal info.

When a customer with an active subscription is attached to an order,
the POS pricing service automatically applies their tier discount.
Loyalty points still accrue on top.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, Numeric, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class MembershipTier(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "membership_tiers"
    __table_args__ = (UniqueConstraint("company_id", "code", name="uq_tier_code_per_company"),)

    id: Mapped[UUID] = _uuid_pk()
    code: Mapped[str] = mapped_column(String(20), nullable=False)  # silver, gold, platinum
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    monthly_price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    annual_price_minor: Mapped[int | None] = mapped_column(BigInteger)
    food_discount_pct: Mapped[float] = mapped_column(Numeric(5, 4), default=0)  # 0.10 = 10%
    gaming_discount_pct: Mapped[float] = mapped_column(Numeric(5, 4), default=0)
    hookah_discount_pct: Mapped[float] = mapped_column(Numeric(5, 4), default=0)
    point_multiplier: Mapped[float] = mapped_column(Numeric(4, 2), default=1)  # 1.5× / 2× points
    free_gaming_minutes_per_week: Mapped[int] = mapped_column(Integer, default=0)
    free_hookah_per_month: Mapped[int] = mapped_column(Integer, default=0)
    priority_booking: Mapped[bool] = mapped_column(default=False, nullable=False)
    description: Mapped[str | None] = mapped_column(String(500))
    sort_order: Mapped[int] = mapped_column(Integer, default=0)


class CustomerMembership(Base, TimestampMixin):
    """A customer's active (or past) subscription to a tier."""
    __tablename__ = "customer_memberships"

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("customers.id", ondelete="CASCADE"),
        nullable=False, index=True,
    )
    tier_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("membership_tiers.id", ondelete="RESTRICT"),
        nullable=False,
    )
    billing_cycle: Mapped[str] = mapped_column(String(10), nullable=False, default="monthly")  # monthly|annual
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    auto_renew: Mapped[bool] = mapped_column(default=True, nullable=False)
    razorpay_subscription_id: Mapped[str | None] = mapped_column(String(50))  # set when Razorpay is wired
    amount_paid_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    # Usage tracking — reset weekly/monthly by a worker
    gaming_minutes_used_this_week: Mapped[int] = mapped_column(Integer, default=0)
    hookah_used_this_month: Mapped[int] = mapped_column(Integer, default=0)
    notes: Mapped[str | None] = mapped_column(String(500))
