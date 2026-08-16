"""Customer model — phone-based loyalty foundation.

A row per unique phone number. Captured at POS checkout. Visit count,
total spent, and loyalty points accumulate automatically as orders are
attached. Later: WhatsApp birthday wishes, loyalty redemption flow.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class Customer(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "customers"
    __table_args__ = (
        # Scoped to live rows only — a deleted customer's phone number must
        # be free for reuse (see DELETE /customers/{id}). A plain
        # UniqueConstraint here would keep blocking it forever, since it
        # doesn't know about deleted_at.
        Index(
            "uq_customer_phone_per_company_live",
            "company_id", "phone",
            unique=True,
            postgresql_where=text("deleted_at IS NULL"),
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    name: Mapped[str | None] = mapped_column(String(200))
    phone: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    email: Mapped[str | None] = mapped_column(String(254))
    birthday: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    first_visit_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    last_visit_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    visit_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    total_spent_minor: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    loyalty_points: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    # Monotonically increasing — never decremented by redemption, unlike
    # loyalty_points above. Drives the gaming rank ladder (see
    # app/services/pos/points.py) so spending points never demotes you.
    lifetime_gaming_points_earned: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    notes: Mapped[str | None] = mapped_column(String(500))


class PointsRedemption(Base, TimestampMixin):
    """A customer's loyalty points reserved by an unpaid order, spent for
    playtime value at POINTS_PER_RUPEE (see app/services/pos/points.py).

    Mirrors MembershipBenefitReservation's reserve-then-consume-at-settlement
    discipline (row stays for audit even if the order voids; only a live or
    consumed row counts against the customer's real points balance) — but
    against Customer.loyalty_points directly rather than a period allowance,
    since points redemption isn't tied to having a paid membership tier.
    """

    __tablename__ = "points_redemptions"
    __table_args__ = (
        UniqueConstraint("order_id", name="uq_points_redemption_order"),
        CheckConstraint("points_spent > 0", name="ck_points_redemption_positive"),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="CASCADE"), nullable=False, index=True
    )
    points_spent: Mapped[int] = mapped_column(Integer, nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
