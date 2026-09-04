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
    Index,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
    event,
    func,
    inspect,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
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
    __table_args__ = (
        CheckConstraint(
            "package_pricing_tier_snapshot IS NULL OR "
            "package_pricing_tier_snapshot IN ('standard', 'premium')",
            name="ck_gaming_sessions_package_pricing_tier_snapshot",
        ),
    )

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
    # NULL only for history that predates migration 0057. Stop and POS handoff
    # are different accountable actions and must not be inferred from opened_by.
    stopped_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    sent_to_pos_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        index=True,
    )
    sent_to_pos_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
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
    # Supplemental catalog discriminator for Code 22+ sessions. It stays
    # nullable so sessions created before the tariff migration remain
    # extendable under the legacy station-type + variant compatibility rule.
    package_pricing_tier_snapshot: Mapped[str | None] = mapped_column(String(20))
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


@event.listens_for(GamingSession, "before_update")
def _guard_gaming_session_actor_attribution(_mapper, _connection, row) -> None:
    """A recorded stop or POS-handoff actor can never be replaced or erased."""

    state = inspect(row)
    for field in ("stopped_by", "sent_to_pos_by", "sent_to_pos_at"):
        history = state.attrs[field].history
        if history.has_changes() and any(value is not None for value in history.deleted):
            raise ValueError(f"gaming session {field} attribution is immutable")
    if (row.sent_to_pos_by is None) != (row.sent_to_pos_at is None):
        raise ValueError(
            "gaming session POS handoff actor and timestamp must be recorded together"
        )


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


class GamingSessionAddon(Base, TenantMixin):
    """Immutable menu-price snapshot attached to a live gaming session.

    The row is financial intent, not inventory movement.  It is copied into the
    session's single held POS order after Stop; normal POS finalization remains
    the only path that deducts stock and posts revenue.  Corrections are a
    one-way, reasoned soft void so an accidental drink/snack never disappears
    from the audit trail.
    """

    __tablename__ = "gaming_session_addons"
    __table_args__ = (
        CheckConstraint("qty > 0", name="ck_gaming_session_addon_qty_positive"),
        CheckConstraint(
            "catalog_unit_price_minor >= 0 AND unit_price_minor >= 0 "
            "AND line_total_minor >= 0 AND discount_minor >= 0 "
            "AND taxable_value_minor >= 0 AND cgst_minor >= 0 "
            "AND sgst_minor >= 0 AND igst_minor >= 0 AND cess_minor >= 0",
            name="ck_gaming_session_addon_amounts_non_negative",
        ),
        CheckConstraint(
            "line_total_minor = taxable_value_minor + cgst_minor + sgst_minor "
            "+ igst_minor + cess_minor",
            name="ck_gaming_session_addon_total_matches_tax_parts",
        ),
        CheckConstraint(
            "variant_snapshot IS NULL OR jsonb_typeof(variant_snapshot) = 'object'",
            name="ck_gaming_session_addon_variant_snapshot_object",
        ),
        CheckConstraint(
            "modifiers IS NULL OR jsonb_typeof(modifiers) = 'array'",
            name="ck_gaming_session_addon_modifiers_array",
        ),
        CheckConstraint(
            "length(trim(menu_item_name_snapshot)) >= 1 "
            "AND menu_item_type_snapshot IN ('food', 'drink', 'dessert')",
            name="ck_gaming_session_addon_reporting_snapshot",
        ),
        CheckConstraint(
            "length(trim(idempotency_key)) > 0 "
            "AND request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_gaming_session_addon_create_receipt",
        ),
        CheckConstraint(
            "void_request_hash IS NULL OR void_request_hash ~ '^[0-9a-f]{64}$'",
            name="ck_gaming_session_addon_void_hash",
        ),
        CheckConstraint(
            "(voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL "
            "AND void_idempotency_key IS NULL AND void_request_hash IS NULL "
            "AND voided_terminal_id IS NULL) OR "
            "(voided_at IS NOT NULL AND voided_by IS NOT NULL "
            "AND length(trim(void_reason)) >= 3 "
            "AND length(trim(void_idempotency_key)) > 0 "
            "AND void_request_hash ~ '^[0-9a-f]{64}$' "
            "AND voided_terminal_id IS NOT NULL)",
            name="ck_gaming_session_addon_void_state",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_gaming_session_addon_company_idempotency",
        ),
        UniqueConstraint(
            "gaming_session_id",
            "client_line_id",
            name="uq_gaming_session_addon_session_client_line",
        ),
        Index(
            "uq_gaming_session_addon_company_void_idempotency",
            "company_id",
            "void_idempotency_key",
            unique=True,
            postgresql_where=text("void_idempotency_key IS NOT NULL"),
        ),
        Index(
            "ix_gaming_session_addon_session_active",
            "gaming_session_id",
            "voided_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    gaming_session_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("gaming_sessions.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    client_line_id: Mapped[UUID] = mapped_column(PG_UUID(as_uuid=True), nullable=False)
    menu_item_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("menu_items.id", ondelete="RESTRICT"),
        nullable=False,
    )
    menu_item_name_snapshot: Mapped[str] = mapped_column(String(200), nullable=False)
    menu_item_type_snapshot: Mapped[str] = mapped_column(String(20), nullable=False)
    variant_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("menu_variants.id", ondelete="RESTRICT")
    )
    variant_snapshot: Mapped[dict | None] = mapped_column(JSONB(none_as_null=True))
    modifiers: Mapped[list[dict] | None] = mapped_column(JSONB(none_as_null=True))
    qty: Mapped[int] = mapped_column(Integer, nullable=False)
    catalog_unit_price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    unit_price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    line_total_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    discount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    hsn_or_sac: Mapped[str | None] = mapped_column(String(8))
    tax_rate: Mapped[float] = mapped_column(Numeric(5, 4), nullable=False, default=0)
    taxable_value_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    cgst_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    sgst_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    igst_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    cess_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    note: Mapped[str | None] = mapped_column(String(500))
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    request_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    created_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    created_terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    voided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    voided_by: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT")
    )
    void_reason: Mapped[str | None] = mapped_column(String(500))
    void_idempotency_key: Mapped[str | None] = mapped_column(String(160))
    void_request_hash: Mapped[str | None] = mapped_column(String(64))
    voided_terminal_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT")
    )


@event.listens_for(GamingSessionAddon, "before_update")
def _guard_gaming_session_addon_update(_mapper, _connection, row) -> None:
    """Only the first complete soft-void transition may mutate an add-on."""

    state = inspect(row)
    immutable_fields = {
        "company_id",
        "gaming_session_id",
        "client_line_id",
        "menu_item_id",
        "menu_item_name_snapshot",
        "menu_item_type_snapshot",
        "variant_id",
        "variant_snapshot",
        "modifiers",
        "qty",
        "catalog_unit_price_minor",
        "unit_price_minor",
        "line_total_minor",
        "discount_minor",
        "hsn_or_sac",
        "tax_rate",
        "taxable_value_minor",
        "cgst_minor",
        "sgst_minor",
        "igst_minor",
        "cess_minor",
        "note",
        "idempotency_key",
        "request_hash",
        "created_by",
        "created_terminal_id",
        "created_at",
    }
    changed = sorted(
        field for field in immutable_fields if state.attrs[field].history.has_changes()
    )
    if changed:
        raise ValueError(
            "gaming session add-on financial/provenance fields are immutable: "
            + ", ".join(changed)
        )

    void_fields = (
        "voided_at",
        "voided_by",
        "void_reason",
        "void_idempotency_key",
        "void_request_hash",
        "voided_terminal_id",
    )
    for field in void_fields:
        history = state.attrs[field].history
        if history.has_changes() and history.deleted and history.deleted[0] is not None:
            raise ValueError("a gaming session add-on void cannot be changed or reversed")
    if any(state.attrs[field].history.has_changes() for field in void_fields) and (
        row.voided_at is None
        or row.voided_by is None
        or row.void_reason is None
        or len(row.void_reason.strip()) < 3
        or not row.void_idempotency_key
        or not row.void_request_hash
        or row.voided_terminal_id is None
    ):
        raise ValueError("a gaming session add-on void must be populated atomically")


@event.listens_for(GamingSessionAddon, "before_delete")
def _guard_gaming_session_addon_delete(_mapper, _connection, _row) -> None:
    raise ValueError("gaming session add-ons are immutable; void the add-on instead")


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
    __table_args__ = (
        CheckConstraint(
            "length(trim(code)) > 0",
            name="ck_gaming_packages_code_present",
        ),
        CheckConstraint(
            "pricing_tier IN ('standard', 'premium')",
            name="ck_gaming_packages_pricing_tier",
        ),
        CheckConstraint(
            "included_players BETWEEN 1 AND 10 "
            "AND max_players BETWEEN included_players AND 10",
            name="ck_gaming_packages_player_limits",
        ),
        CheckConstraint(
            "max_players = included_players OR "
            "(station_type = 'ps5' AND variant = 'dual' AND included_players = 2)",
            name="ck_gaming_packages_multiplayer_eligibility",
        ),
        Index(
            "uq_gaming_packages_company_branch_code_active",
            "company_id",
            "branch_id",
            "code",
            unique=True,
            postgresql_where=text("deleted_at IS NULL"),
            sqlite_where=text("deleted_at IS NULL"),
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    # Immutable external/catalog identity. Display names and prices may change;
    # clients and idempotent catalog maintenance must never key on either.
    code: Mapped[str] = mapped_column(String(64), nullable=False)
    station_type: Mapped[str] = mapped_column(String(20), nullable=False)  # ps5|simulator|vr|...
    # Names the specific product line within a station type — e.g. "single"
    # vs "dual" for ps5, "games" vs "racing" for vr. Stations of the same
    # type share one variant unless the mode changes what's on screen.
    variant: Mapped[str] = mapped_column(String(20), nullable=False)
    # Pricing tier is independent from the play mode. Keeping it separate
    # prevents premium-single from masquerading as a new compatibility variant.
    pricing_tier: Mapped[str] = mapped_column(
        String(20), default="standard", server_default="standard", nullable=False
    )
    kind: Mapped[str] = mapped_column(String(20), nullable=False)  # base|extension
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    duration_minutes: Mapped[int] = mapped_column(Integer, nullable=False)
    price_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    included_players: Mapped[int] = mapped_column(
        Integer, default=1, server_default="1", nullable=False
    )
    max_players: Mapped[int] = mapped_column(
        Integer, default=1, server_default="1", nullable=False
    )
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
