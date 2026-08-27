"""Gaming module models."""

from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
    event,
    func,
)
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, SoftDeleteMixin, TenantMixin, TimestampMixin, _uuid_pk


class Station(Base, TimestampMixin, TenantMixin):
    __tablename__ = "stations"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    code: Mapped[str] = mapped_column(String(20), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    type: Mapped[str] = mapped_column(String(20), nullable=False)  # ps5|vr|simulator|projector|hookah|streaming
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
    # Persisted financial discriminator. package_id is only a nullable catalog
    # reference and may be cleared by ON DELETE SET NULL; it must never decide
    # whether Stop preserves a locked package amount or recomputes hourly.
    # legacy_ambiguous is a conservative migration state for ended, unbilled,
    # pre-snapshot rows whose deleted package FK made exact classification
    # impossible; financial benefits treat it as non-hourly until reviewed.
    billing_mode: Mapped[str] = mapped_column(
        String(20), default="hourly", server_default="hourly", nullable=False
    )
    package_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("gaming_packages.id", ondelete="SET NULL")
    )
    # Immutable base-package snapshots. The catalog row may later be renamed,
    # repriced, or retired; extension compatibility must still use what the
    # employee actually confirmed when this session started.
    package_price_minor_snapshot: Mapped[int | None] = mapped_column(BigInteger)
    package_duration_minutes_snapshot: Mapped[int | None] = mapped_column(Integer)
    package_variant_snapshot: Mapped[str | None] = mapped_column(String(20))
    package_station_type_snapshot: Mapped[str | None] = mapped_column(String(20))
    # Planned duration in minutes from start_at (e.g. a 60-minute PS5 slot).
    # NULL = open-ended, billed by actual elapsed time as before.
    timer_minutes: Mapped[int | None] = mapped_column(Integer)
    billable_minutes: Mapped[int | None] = mapped_column(Integer)
    amount_minor: Mapped[int | None] = mapped_column(BigInteger)
    status: Mapped[str] = mapped_column(String(20), default="active")  # active|paused|ended|cancelled
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    cancelled_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL")
    )
    cancel_reason: Mapped[str | None] = mapped_column(String(500))
    customer_name: Mapped[str | None] = mapped_column(String(200))
    customer_phone: Mapped[str | None] = mapped_column(String(20))
    # Snapshotted from Station at start_at so a later station rate/tax edit
    # never rewrites an already-running or historical session's GST split.
    # Nullable: sessions started before this column existed have neither.
    tax_rate: Mapped[float | None] = mapped_column(Numeric(5, 4))
    sac_code: Mapped[str | None] = mapped_column(String(8))
    rate_includes_tax: Mapped[bool | None] = mapped_column()
    # Extra controllers/players beyond what the package's base mode covers
    # (e.g. a 3rd/4th player joining a Dual-mode PS5 slot). Surcharge is
    # computed at package-price time, never re-derived from elapsed time.
    extra_controllers: Mapped[int] = mapped_column(Integer, default=0, nullable=False)


class GamingSessionExtension(Base, TenantMixin):
    """Immutable itemisation of each paid package extension."""

    __tablename__ = "gaming_session_extensions"
    __table_args__ = (
        CheckConstraint(
            "duration_minutes > 0",
            name="ck_gaming_session_extension_duration_positive",
        ),
        CheckConstraint(
            "package_price_minor >= 0 AND controller_surcharge_minor >= 0 "
            "AND total_minor >= 0",
            name="ck_gaming_session_extension_amounts_non_negative",
        ),
        CheckConstraint(
            "total_minor = package_price_minor + controller_surcharge_minor",
            name="ck_gaming_session_extension_total_matches_parts",
        ),
        CheckConstraint(
            "timer_before_minutes >= 0 "
            "AND timer_after_minutes = timer_before_minutes + duration_minutes",
            name="ck_gaming_session_extension_timer_chain",
        ),
        CheckConstraint(
            "amount_before_minor >= 0 "
            "AND amount_after_minor = amount_before_minor + total_minor",
            name="ck_gaming_session_extension_amount_chain",
        ),
        CheckConstraint(
            "length(trim(idempotency_key)) > 0",
            name="ck_gaming_session_extension_idempotency_present",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_gaming_session_extension_company_idempotency",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    gaming_session_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("gaming_sessions.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    package_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("gaming_packages.id", ondelete="SET NULL"),
        index=True,
    )
    package_name: Mapped[str] = mapped_column(String(100), nullable=False)
    package_variant: Mapped[str] = mapped_column(String(20), nullable=False)
    station_type: Mapped[str] = mapped_column(String(20), nullable=False)
    duration_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    package_price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    controller_surcharge_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    total_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    timer_before_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    timer_after_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    amount_before_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    amount_after_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    created_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


@event.listens_for(GamingSessionExtension, "before_update")
def _guard_gaming_session_extension_update(_mapper, _connection, _row) -> None:
    raise ValueError("gaming session extension ledger rows are immutable")


@event.listens_for(GamingSessionExtension, "before_delete")
def _guard_gaming_session_extension_delete(_mapper, _connection, _row) -> None:
    raise ValueError("gaming session extension ledger rows cannot be deleted")


class GamingPackage(Base, TimestampMixin, SoftDeleteMixin, TenantMixin):
    """A named, fixed-price session tier — e.g. "Single Mode · 30 min" = ₹80.

    Session pricing is package-driven, not elapsed-time-driven: the price is
    locked in the moment a package is selected, not recomputed from how long
    the customer actually played. kind='extension' rows are added on top of
    an already-running package session (own duration + price), never used to
    start a fresh session. Stations with no active packages for their type
    fall back to the station's plain rate_per_hour_minor (open-ended, billed
    by elapsed time) — packages are additive, not a hard requirement.
    """

    __tablename__ = "gaming_packages"

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    station_type: Mapped[str] = mapped_column(String(20), nullable=False)  # ps5|simulator|vr|...
    # Names the specific product line within a station type — e.g. "single"
    # vs "dual" for ps5, "games" vs "racing" for vr. Stations of the same
    # type share one variant unless the mode changes what's on screen.
    variant: Mapped[str] = mapped_column(String(20), nullable=False)
    kind: Mapped[str] = mapped_column(String(20), nullable=False)  # base|extension
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    duration_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)


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
