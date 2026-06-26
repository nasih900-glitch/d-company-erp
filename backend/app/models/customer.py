"""Customer model — phone-based loyalty foundation.

A row per unique phone number. Captured at POS checkout. Visit count,
total spent, and loyalty points accumulate automatically as orders are
attached. Later: WhatsApp birthday wishes, loyalty redemption flow.
"""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class Customer(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    __tablename__ = "customers"
    __table_args__ = (
        UniqueConstraint("company_id", "phone", name="uq_customer_phone_per_company"),
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
    notes: Mapped[str | None] = mapped_column(String(500))
