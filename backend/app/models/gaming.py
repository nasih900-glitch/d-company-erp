"""Gaming module models."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, Numeric, String
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TenantMixin, TimestampMixin, _uuid_pk


class Station(Base, TimestampMixin, TenantMixin):
    __tablename__ = "stations"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    code: Mapped[str] = mapped_column(String(20), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # ps5|vr|simulator|projector
    rate_per_hour_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    is_active: Mapped[bool] = mapped_column(default=True, nullable=False)
    notes: Mapped[str | None] = mapped_column(String(500))
    # ----- India / GST -----
    # Gaming / amusement service taxed at 18% under SAC 999692.
    # Stored on the station so future rate changes don't rewrite history;
    # snapshotted onto each session at session_start.
    sac_code: Mapped[str] = mapped_column(String(8), nullable=False, default="999692")
    tax_rate: Mapped[float] = mapped_column(Numeric(5, 4), nullable=False, default=0.18)
    # Hourly rate convention: True = rate is GST-inclusive (customer-friendly).
    rate_includes_tax: Mapped[bool] = mapped_column(default=True, nullable=False)


class GamingSession(Base, TimestampMixin, TenantMixin):
    __tablename__ = "gaming_sessions"

    id: Mapped[UUID] = _uuid_pk()
    station_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("stations.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    order_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("orders.id", ondelete="SET NULL")
    )
    opened_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    start_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    end_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    paused_minutes: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    rate_per_hour_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    package_id: Mapped[UUID | None] = mapped_column(PG_UUID(as_uuid=True))
    billable_minutes: Mapped[int | None] = mapped_column(Integer)
    amount_minor: Mapped[int | None] = mapped_column(BigInteger)
    status: Mapped[str] = mapped_column(String(20), default="active")  # active|paused|ended|cancelled
    customer_name: Mapped[str | None] = mapped_column(String(200))
    customer_phone: Mapped[str | None] = mapped_column(String(20))


class GamingBooking(Base, TimestampMixin):
    __tablename__ = "gaming_bookings"

    id: Mapped[UUID] = _uuid_pk()
    station_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("stations.id", ondelete="CASCADE"), nullable=False, index=True
    )
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    guest_name: Mapped[str] = mapped_column(String(200), nullable=False)
    contact: Mapped[str | None] = mapped_column(String(50))
    party_size: Mapped[int] = mapped_column(Integer, default=1)
    deposit_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    status: Mapped[str] = mapped_column(String(20), default="held")  # held|consumed|no_show|cancelled
    created_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )

    # NOTE: A Postgres EXCLUDE constraint using tstzrange should be added in a
    # follow-up migration to prevent overlapping bookings per station.
    # CREATE EXTENSION btree_gist; then EXCLUDE USING gist
    # (station_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)


class Tournament(Base, TimestampMixin, TenantMixin):
    __tablename__ = "tournaments"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    game_title: Mapped[str | None] = mapped_column(String(200))
    format: Mapped[str | None] = mapped_column(String(50))  # bracket|round_robin|league
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ends_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    entry_fee_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    prize_pool_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    status: Mapped[str] = mapped_column(String(20), default="scheduled")
