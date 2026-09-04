"""Gaming endpoints — stations, sessions, bookings."""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from math import ceil
from typing import Literal, Self
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Query, Request, status
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)
from sqlalchemy import func, or_, select
from sqlalchemy.exc import IntegrityError

from app.core.db import SessionDep
from app.core.errors import (
    BusinessRuleError,
    ConflictError,
    ForbiddenError,
    GamingBillingRepairRequiredError,
    GamingExtensionNotAppliedError,
    GamingLegacyServerSessionNotFoundError,
    GamingLegacyStopOwnerReviewRequiredError,
    GamingSourceShiftClosedError,
    NotFoundError,
)
from app.core.idempotency import check_or_reserve, store_response
from app.core.middleware import parse_client_version_code
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import (
    AuditLog,
    Branch,
    Company,
    GamingBooking,
    GamingPackage,
    GamingSession,
    GamingSessionAddon,
    GamingSessionExtension,
    IdempotencyKey,
    MenuCategory,
    MenuItem,
    MenuModifier,
    MenuModifierGroup,
    MenuVariant,
    Order,
    OrderLine,
    Payment,
    Refund,
    Shift,
    Station,
    Terminal,
    User,
)
from app.services.gaming.billing_mode import (
    has_complete_package_snapshot,
    has_partial_package_snapshot,
    is_package_billed,
    resolved_billing_mode,
)
from app.services.pos.order_validation import require_operational_order
from app.services.pos.pricing import (
    LineRequest,
    ModifierSelection,
    OrderPricingService,
    _round_to_rupee,
)
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_operational_shift_scope,
    require_shift_opener,
)

router = APIRouter()

_MENU_TYPE_FOR_STATION = {"hookah": "hookah", "streaming": "streaming"}
_SESSION_ITEM_LABEL = {
    "ps5": "Gaming Session",
    "vr": "VR Session",
    "simulator": "Simulator Session",
    "projector": "Projector Session",
    "hookah": "Shisha Session",
    "streaming": "Streaming Session",
}
_GAMING_SOURCE_TERMINAL_PURPOSES = frozenset({"gaming", "hybrid"})
_POS_DESTINATION_TERMINAL_PURPOSES = frozenset({"cafe_pos", "hybrid"})


class StationRead(BaseModel):
    id: UUID
    branch_id: UUID
    code: str
    name: str
    type: str
    rate_per_hour_minor: int
    is_active: bool


class StationCreate(BaseModel):
    code: str = Field(min_length=1, max_length=20)
    name: str = Field(min_length=1, max_length=100)
    type: str = Field(min_length=1, max_length=20)  # ps5|vr|simulator|projector|hookah|streaming
    rate_per_hour_minor: int = Field(ge=0)
    branch_id: UUID | None = None
    notes: str | None = Field(default=None, max_length=500)


class StationUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    rate_per_hour_minor: int | None = Field(default=None, ge=0)
    is_active: bool | None = None
    notes: str | None = Field(default=None, max_length=500)


class SessionStart(BaseModel):
    station_id: UUID
    shift_id: UUID
    # Durable native outbox actions capture when the employee tapped Start.
    # Ordinary web/online callers omit this and retain authoritative server
    # receipt time. A supplied value is accepted only with matching offline
    # provenance headers and the same durable action/idempotency identity.
    started_at: datetime | None = None
    customer_name: str | None = Field(default=None, max_length=200)
    customer_phone: str | None = Field(default=None, max_length=20)
    # A fixed-price base package (see GET /gaming/packages) — locks in the
    # price immediately and sets timer_minutes from the package's duration.
    # Omit for the legacy open-ended flow (billed by elapsed time at the
    # station's plain hourly rate).
    package_id: UUID | None = None
    # A non-package session is billed from the station's hourly rate. The
    # employee must therefore confirm the exact rate displayed by the client;
    # a rate edited after the screen loaded is a conflict, not a silent reprice.
    expected_rate_per_hour_minor: int | None = Field(default=None, ge=0)
    # Package catalog snapshots are conditionally required when package_id is
    # present. They protect a cached/offline selection from silently accepting
    # a newly edited duration, price, or product variant.
    expected_package_price_minor: int | None = Field(default=None, ge=0)
    expected_package_duration_minutes: int | None = Field(
        default=None, ge=1, le=1440
    )
    expected_package_variant: str | None = Field(default=None, min_length=1, max_length=20)
    # Planned duration in minutes (e.g. a 60-minute PS5 slot). Ignored if
    # package_id is set (the package's own duration wins). Omit for open-ended.
    timer_minutes: int | None = Field(default=None, ge=1, le=1440)
    # Extra controllers/players beyond the package's base mode — only
    # meaningful together with package_id.
    extra_controllers: int = Field(default=0, ge=0, le=8)

    @field_validator("customer_name", "customer_phone")
    @classmethod
    def normalize_optional_customer_identity(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None

    @field_validator("expected_package_variant")
    @classmethod
    def normalize_optional_package_variant(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None

    @model_validator(mode="after")
    def require_relevant_price_snapshot(self) -> Self:
        package_snapshot = (
            self.expected_package_price_minor,
            self.expected_package_duration_minutes,
            self.expected_package_variant,
        )
        if self.package_id is not None and any(value is None for value in package_snapshot):
            raise ValueError(
                "package price, duration, and variant snapshots are required"
            )
        if self.package_id is None and any(
            value is not None for value in package_snapshot
        ):
            raise ValueError("package snapshots require a package_id")
        return self


class SessionTimerUpdate(BaseModel):
    # Minutes from the session's start_at. null clears the timer (open-ended).
    timer_minutes: int | None = Field(default=None, ge=1, le=1440)


class SessionTimerExtend(BaseModel):
    """Conflict-safe relative timer increment for an open-rate session."""

    # Required even when the observed value was JSON null. This makes the
    # caller's read snapshot explicit and lets the server reject stale screens.
    expected_timer_minutes: int | None = Field(ge=1, le=1440)
    additional_minutes: int = Field(ge=1, le=1440)


class SessionTransfer(BaseModel):
    # Compare-and-swap source snapshot. Without this, a stale client that still
    # displays A can unintentionally move a session from C to B after another
    # terminal already completed A -> C.
    expected_source_station_id: UUID
    target_station_id: UUID


class SessionStop(BaseModel):
    # Only offline outbox replays may supply the time at which Stop was tapped.
    # Online/legacy callers omit the body and retain server-receipt behaviour.
    ended_at: datetime | None = None


class SessionRead(BaseModel):
    id: UUID
    station_id: UUID
    shift_id: UUID | None = None
    status: str
    start_at: datetime
    end_at: datetime | None
    timer_minutes: int | None = None
    timer_ends_at: datetime | None = None
    paused_minutes: int = 0
    billable_minutes: int | None
    amount_minor: int | None
    customer_name: str | None = None
    customer_phone: str | None = None
    rate_per_hour_minor: int | None = None
    order_id: UUID | None = None
    cancel_reason: str | None = None
    # package_id is a nullable catalog reference (ON DELETE SET NULL), not the
    # financial billing-mode source of truth. Package snapshots remain locked
    # on the session after a catalog row is retired or hard-deleted.
    billing_mode: Literal["hourly", "package", "legacy_ambiguous"] = "hourly"
    package_id: UUID | None = None
    package_price_minor_snapshot: int | None = None
    package_duration_minutes_snapshot: int | None = None
    package_variant_snapshot: str | None = None
    package_station_type_snapshot: str | None = None
    extra_controllers: int = 0

    @model_validator(mode="before")
    @classmethod
    def derive_legacy_billing_mode(cls, value: object) -> object:
        """Keep pre-0038 idempotency response replays backward-compatible."""
        if not isinstance(value, dict):
            return value
        normalized = value
        if "billing_mode" not in value:
            package_evidence = value.get("package_id") is not None or any(
                value.get(field) is not None
                for field in (
                    "package_price_minor_snapshot",
                    "package_duration_minutes_snapshot",
                    "package_variant_snapshot",
                    "package_station_type_snapshot",
                )
            )
            normalized = {
                **normalized,
                "billing_mode": "package" if package_evidence else "hourly",
            }
        # The ORM-side create audit can run before PostgreSQL applies the
        # paused_minutes default. Treat that immutable null snapshot as the
        # model's initial zero value when recovering after key expiry.
        if normalized.get("paused_minutes") is None:
            normalized = {**normalized, "paused_minutes": 0}
        return normalized


class GamingPackageRead(BaseModel):
    id: UUID
    station_type: str
    variant: str
    kind: str
    name: str
    duration_minutes: int
    price_minor: int


class SessionExtend(BaseModel):
    package_id: UUID
    expected_timer_minutes: int = Field(ge=1, le=1440)
    expected_amount_minor: int = Field(ge=0, le=9_999_999_999)
    expected_package_price_minor: int = Field(ge=0)
    expected_package_duration_minutes: int = Field(ge=1, le=1440)
    expected_package_variant: str = Field(min_length=1, max_length=20)

    @field_validator("expected_package_variant")
    @classmethod
    def normalize_package_variant(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("expected package variant cannot be blank")
        return normalized


class SessionCancel(BaseModel):
    reason: str = Field(min_length=1, max_length=500)

    @field_validator("reason")
    @classmethod
    def require_meaningful_reason(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("cancellation reason cannot be blank")
        return value


class SessionAddonModifierSelection(BaseModel):
    model_config = ConfigDict(extra="forbid")

    modifier_id: UUID
    qty: int = Field(default=1, ge=1, le=100)


class SessionAddonCreate(BaseModel):
    """One catalog item consumed during a live Gaming session."""

    model_config = ConfigDict(extra="forbid")

    client_line_id: UUID
    menu_item_id: UUID
    variant_id: UUID | None = None
    modifiers: list[SessionAddonModifierSelection] = Field(
        default_factory=list,
        max_length=50,
    )
    qty: int = Field(ge=1, le=100)
    expected_unit_price_minor: int = Field(ge=0, le=9_999_999_999)
    note: str | None = Field(default=None, max_length=500)

    @field_validator("note")
    @classmethod
    def normalize_note(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None


class SessionAddonVoid(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reason: str = Field(min_length=3, max_length=500)

    @field_validator("reason")
    @classmethod
    def normalize_reason(cls, value: str) -> str:
        normalized = value.strip()
        if len(normalized) < 3:
            raise ValueError("void reason must be at least 3 characters")
        return normalized


class SessionAddonRead(BaseModel):
    id: UUID
    gaming_session_id: UUID
    client_line_id: UUID
    menu_item_id: UUID
    menu_item_name: str
    menu_item_type: Literal["food", "drink", "dessert"]
    variant_id: UUID | None
    variant_snapshot: dict | None
    modifiers: list[dict]
    qty: int
    catalog_unit_price_minor: int
    unit_price_minor: int
    line_total_minor: int
    discount_minor: int
    hsn_or_sac: str | None
    tax_rate: float
    taxable_value_minor: int
    cgst_minor: int
    sgst_minor: int
    igst_minor: int
    cess_minor: int
    note: str | None
    created_by: UUID
    created_terminal_id: UUID
    created_at: datetime
    voided_at: datetime | None
    voided_by: UUID | None
    void_reason: str | None


class SessionBillingRepair(BaseModel):
    # Required compare-and-swap snapshot. The normal repair case is explicit
    # JSON null, proving the owner reviewed a row with a missing billed amount.
    expected_amount_minor: int | None = Field(ge=0)
    amount_minor: int = Field(ge=0, le=9_999_999_999)
    reason: str = Field(min_length=3, max_length=500)

    @field_validator("reason")
    @classmethod
    def require_meaningful_reason(cls, value: str) -> str:
        normalized = value.strip()
        if len(normalized) < 3:
            raise ValueError("repair reason must be at least 3 characters")
        return normalized


class LegacyOutboxResolution(BaseModel):
    local_action_id: UUID
    station_id: UUID
    # Server shift UUID captured by the durable start action. Legacy rows that
    # genuinely never reached the server may omit it, but recovering an
    # accepted start requires this immutable scope discriminator.
    shift_id: UUID | None = None
    captured_started_at: datetime
    captured_stopped_at: datetime | None = None
    package_id: UUID | None = None
    # Hourly v27 rows retain the exact rate displayed at Start. Package rows
    # are price-locked by package snapshots and must not carry an hourly rate.
    expected_rate_per_hour_minor: int | None = Field(default=None, ge=0)
    resolution: Literal[
        "manual_bill_recorded",
        "confirmed_no_play",
        "server_session_recovered",
    ]
    reference_order_id: UUID | None = None
    reason: str = Field(min_length=3, max_length=500)

    @field_validator("reason")
    @classmethod
    def require_meaningful_reason(cls, value: str) -> str:
        normalized = value.strip()
        if len(normalized) < 3:
            raise ValueError("resolution reason must be at least 3 characters")
        return normalized

    @model_validator(mode="after")
    def require_resolution_evidence(self) -> Self:
        if self.package_id is None and self.expected_rate_per_hour_minor is None:
            raise ValueError(
                "hourly legacy recovery requires expected_rate_per_hour_minor"
            )
        if self.package_id is not None and self.expected_rate_per_hour_minor is not None:
            raise ValueError(
                "package legacy recovery must not include expected_rate_per_hour_minor"
            )
        if self.resolution == "manual_bill_recorded" and self.reference_order_id is None:
            raise ValueError("manual_bill_recorded requires reference_order_id")
        if (
            self.resolution == "manual_bill_recorded"
            and self.package_id is None
            and self.captured_stopped_at is None
        ):
            raise ValueError(
                "hourly manual_bill_recorded requires captured_stopped_at"
            )
        if self.resolution == "confirmed_no_play" and self.reference_order_id is not None:
            raise ValueError("confirmed_no_play must not include reference_order_id")
        if (
            self.resolution == "server_session_recovered"
            and self.reference_order_id is not None
        ):
            raise ValueError("server_session_recovered must not include reference_order_id")
        return self


class LegacyOutboxResolutionRead(BaseModel):
    receipt_id: int
    local_action_id: UUID
    station_id: UUID
    branch_id: UUID
    terminal_id: UUID
    package_id: UUID | None
    resolution: Literal[
        "manual_bill_recorded",
        "confirmed_no_play",
        "server_session_recovered",
    ]
    reference_order_id: UUID | None
    server_session: SessionRead | None = None
    resolved_at: datetime


class SessionReconcileToPos(BaseModel):
    target_shift_id: UUID
    reason: str = Field(min_length=3, max_length=500)

    @field_validator("reason")
    @classmethod
    def require_meaningful_reason(cls, value: str) -> str:
        normalized = value.strip()
        if len(normalized) < 3:
            raise ValueError("reconciliation reason must be at least 3 characters")
        return normalized


class SessionReconcileToPosRead(BaseModel):
    order_id: UUID
    amount_minor: int
    source_shift_id: UUID
    target_shift_id: UUID
    already_linked: bool


class SessionPosHandoff(BaseModel):
    """Explicit destination for a normal cross-terminal POS handoff."""

    target_shift_id: UUID


class SessionPosHandoffRead(BaseModel):
    order_id: UUID
    amount_minor: int
    source_shift_id: UUID
    source_terminal_id: UUID
    target_shift_id: UUID
    target_terminal_id: UUID
    already_linked: bool


class PosTargetShiftRead(BaseModel):
    shift_id: UUID
    terminal_id: UUID
    terminal_name: str
    opened_by: UUID
    opened_by_name: str
    opened_at: datetime


class BookingCreate(BaseModel):
    station_id: UUID
    starts_at: datetime
    ends_at: datetime
    guest_name: str = Field(min_length=1, max_length=200)
    contact: str | None = Field(default=None, max_length=50)
    party_size: int = Field(default=1, gt=0)
    deposit_minor: int = Field(default=0, ge=0)


class BookingRead(BaseModel):
    id: UUID
    station_id: UUID
    station_code: str
    starts_at: datetime
    ends_at: datetime
    guest_name: str
    contact: str | None
    party_size: int
    deposit_minor: int
    status: str
    created_at: datetime


class BookingStatusUpdate(BaseModel):
    # "held" is the creation-only state — not a valid PATCH target, so it's
    # deliberately excluded here (see _BOOKING_TRANSITIONS below).
    status: Literal["consumed", "no_show", "cancelled"]


# A booking only ever transitions once, out of "held" — every other status
# is terminal. Keyed by *current* status so an invalid transition (e.g.
# cancelling an already-consumed/no-show/cancelled booking) is rejected
# instead of silently overwriting a settled outcome.
_BOOKING_TRANSITIONS: dict[str, set[str]] = {
    "held": {"consumed", "no_show", "cancelled"},
}


def _require_complete_package_billing_snapshot(
    gs: GamingSession,
    *,
    operation: str,
) -> None:
    if not has_complete_package_snapshot(gs):
        raise GamingBillingRepairRequiredError(
            "This package session's locked pricing snapshot is incomplete. Nothing was "
            f"{operation}. A protected owner must review the session before staff continue."
        )


def session_read(gs: GamingSession) -> SessionRead:
    timer_ends_at = (
        gs.start_at + timedelta(minutes=gs.timer_minutes) if gs.timer_minutes else None
    )
    return SessionRead(
        id=gs.id,
        station_id=gs.station_id,
        shift_id=gs.shift_id,
        status=gs.status,
        start_at=gs.start_at,
        end_at=gs.end_at,
        timer_minutes=gs.timer_minutes,
        timer_ends_at=timer_ends_at,
        paused_minutes=int(getattr(gs, "paused_minutes", 0) or 0),
        billable_minutes=gs.billable_minutes,
        amount_minor=gs.amount_minor,
        customer_name=gs.customer_name,
        customer_phone=gs.customer_phone,
        rate_per_hour_minor=gs.rate_per_hour_minor,
        order_id=gs.order_id,
        cancel_reason=gs.cancel_reason,
        billing_mode=resolved_billing_mode(gs),
        package_id=gs.package_id,
        package_price_minor_snapshot=getattr(gs, "package_price_minor_snapshot", None),
        package_duration_minutes_snapshot=getattr(
            gs, "package_duration_minutes_snapshot", None
        ),
        package_variant_snapshot=getattr(gs, "package_variant_snapshot", None),
        package_station_type_snapshot=getattr(
            gs, "package_station_type_snapshot", None
        ),
        extra_controllers=int(gs.extra_controllers or 0),
    )


def session_addon_read(addon: GamingSessionAddon) -> SessionAddonRead:
    return SessionAddonRead(
        id=addon.id,
        gaming_session_id=addon.gaming_session_id,
        client_line_id=addon.client_line_id,
        menu_item_id=addon.menu_item_id,
        menu_item_name=addon.menu_item_name_snapshot,
        menu_item_type=addon.menu_item_type_snapshot,
        variant_id=addon.variant_id,
        variant_snapshot=addon.variant_snapshot,
        modifiers=list(addon.modifiers or []),
        qty=int(addon.qty),
        catalog_unit_price_minor=int(addon.catalog_unit_price_minor),
        unit_price_minor=int(addon.unit_price_minor),
        line_total_minor=int(addon.line_total_minor),
        discount_minor=int(addon.discount_minor),
        hsn_or_sac=addon.hsn_or_sac,
        tax_rate=float(addon.tax_rate),
        taxable_value_minor=int(addon.taxable_value_minor),
        cgst_minor=int(addon.cgst_minor),
        sgst_minor=int(addon.sgst_minor),
        igst_minor=int(addon.igst_minor),
        cess_minor=int(addon.cess_minor),
        note=addon.note,
        created_by=addon.created_by,
        created_terminal_id=addon.created_terminal_id,
        created_at=addon.created_at,
        voided_at=addon.voided_at,
        voided_by=addon.voided_by,
        void_reason=addon.void_reason,
    )


def _extension_not_applied(
    gaming_session: GamingSession,
    *,
    reason_code: str,
    message: str,
) -> GamingExtensionNotAppliedError:
    """Build the sole discard-safe extension rejection.

    Callers may use this only after proving the durable ledger has no matching
    key and the session's company/branch/terminal scope is exact.
    """
    return GamingExtensionNotAppliedError(
        f"{message} This saved extension has no charge receipt and was not applied.",
        details={
            "session_id": str(gaming_session.id),
            "session_status": gaming_session.status,
            "reason_code": reason_code,
        },
    )


def session_amount_minor(billable_minutes: int, rate_per_hour_minor: int) -> int:
    """Ceiling-bill whole minutes using integer minor-unit arithmetic."""
    if billable_minutes <= 0 or rate_per_hour_minor <= 0:
        return 0
    return (billable_minutes * rate_per_hour_minor + 59) // 60


# ₹30 per extra controller per hour, ₹30 minimum per controller — printed
# pricing card, "applicable for more than 2 players".
EXTRA_CONTROLLER_PRICE_PER_HOUR_MINOR = 3000
EXTRA_CONTROLLER_MIN_CHARGE_MINOR = 3000


def extra_controller_surcharge_minor(*, extra_controllers: int, duration_minutes: int) -> int:
    """One extra-controller charge per player beyond the base package mode.

    Priced per hour of the package's own duration (not actual elapsed play
    time — package sessions are prepaid, fixed-price slots), with a ₹30
    floor per controller so even a 15-minute slot costs a full ₹30 to add
    a controller to.
    """
    if extra_controllers <= 0 or duration_minutes <= 0:
        return 0
    hours = ceil(duration_minutes / 60)
    per_controller = max(
        EXTRA_CONTROLLER_MIN_CHARGE_MINOR, hours * EXTRA_CONTROLLER_PRICE_PER_HOUR_MINOR
    )
    return extra_controllers * per_controller


def _current_gaming_branch_id(tenant: TenantContext) -> UUID:
    if tenant.branch_id is None:
        raise BusinessRuleError("select a branch or terminal before managing gaming stations")
    return tenant.branch_id


async def _require_terminal_purpose(
    session,
    *,
    tenant: TenantContext,
    allowed: frozenset[str],
    invalid_message: str,
) -> Terminal:
    """Resolve the selected terminal and enforce its operational capability.

    Terminal names are editable display labels and therefore must never drive
    financial routing. Purpose is the durable, database-constrained contract.
    """
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Select a terminal in this shop before managing gaming sessions."
        )
    terminal = await session.get(Terminal, tenant.terminal_id)
    if terminal is None or terminal.branch_id != tenant.branch_id:
        raise BusinessRuleError(
            "The selected terminal is not valid for this shop. Refresh your terminal selection."
        )
    if getattr(terminal, "is_active", True) is False:
        raise BusinessRuleError(
            "The selected workspace is archived. Reload the app and use the active "
            "Hybrid workspace."
        )
    if terminal.purpose not in allowed:
        raise BusinessRuleError(invalid_message)
    return terminal


def _require_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for gaming session writes")
    return str(key), str(request_hash)


def _is_legacy_ios_online_request(request: Request) -> bool:
    """Recognize the shipped pre-version-header iOS client narrowly.

    The current release scope is Android, but deploying the hardened backend
    must not turn the already-distributed iOS Gaming flow into an unexplained
    422. This path does not trust client time or cached pricing: it is online
    only, uses the current locked station rate, and relies on station/session
    row locks for natural duplicate safety.
    """
    headers = getattr(request, "headers", {})
    user_agent = str(headers.get("user-agent", "")).strip()
    return (
        user_agent.startswith("DCompanyERP-iOSNative/")
        and not str(headers.get("X-Client-Platform", "")).strip()
        and not str(headers.get("X-Offline-Captured", "")).strip()
    )


def _gaming_idempotency_or_legacy_ios(
    request: Request,
) -> tuple[str | None, str | None]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if key and request_hash:
        return str(key), str(request_hash)
    if _is_legacy_ios_online_request(request):
        return None, None
    return _require_idempotency(request)


def _require_package_snapshot(
    package: GamingPackage,
    *,
    expected_price_minor: int,
    expected_duration_minutes: int,
    expected_variant: str,
) -> None:
    """Reject a cached package selection instead of silently repricing it."""
    if (
        int(package.price_minor) != expected_price_minor
        or int(package.duration_minutes) != expected_duration_minutes
        or package.variant != expected_variant
    ):
        raise ConflictError(
            "Package pricing changed after it was selected. Refresh Gaming and "
            "select the package again."
        )


def _elapsed_billable_whole_minutes(
    *,
    started_at: datetime,
    server_now: datetime,
    paused_minutes: int,
) -> int:
    elapsed_seconds = max(0.0, (server_now - started_at).total_seconds())
    elapsed_minutes = ceil(elapsed_seconds / 60) if elapsed_seconds > 0 else 0
    return max(0, elapsed_minutes - max(0, int(paused_minutes or 0)))


def _require_repaired_ended_amount(gs: GamingSession) -> int:
    """Fail closed when a historical ended row has no authoritative bill."""
    if gs.amount_minor is None:
        raise GamingBillingRepairRequiredError(
            "This stopped session is missing its billed amount. Nothing was changed. "
            "A protected owner must review the session and use Repair billing before "
            "it can be sent to POS or cancelled."
        )
    return int(gs.amount_minor)


async def _require_session_pos_eligible(
    session,
    *,
    gaming_session: GamingSession,
) -> None:
    """Require either a charge for play or an active staged POS line.

    Complimentary play is valid, and a staged item may itself legitimately
    round the final order to zero. Such an item must still reach POS so its
    stock movement, receipt, and audit lifecycle can complete through the
    existing zero-total finalization path. Only a zero-value session with no
    active item belongs on the reasoned session-cancellation path.
    """
    gaming_amount_minor = _require_repaired_ended_amount(gaming_session)
    # The overwhelmingly common paid-session path needs no add-on lookup.
    # Besides avoiding an unnecessary query on every handoff, this preserves
    # compatibility with callers/tests whose lightweight session adapter only
    # implements the reads required for an ordinary positive play charge.
    if gaming_amount_minor > 0:
        return
    active_addon_count = int(
        (
            await session.execute(
                select(func.count(GamingSessionAddon.id)).where(
                    GamingSessionAddon.company_id == gaming_session.company_id,
                    GamingSessionAddon.gaming_session_id == gaming_session.id,
                    GamingSessionAddon.voided_at.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if active_addon_count == 0:
        raise BusinessRuleError(
            "The session has no play charge or saved items. Cancel it with a reason instead."
        )


def _validated_offline_action_time(
    *,
    request: Request,
    captured_at: datetime,
    server_now: datetime,
    action_label: str,
    minimum_at: datetime | None = None,
) -> datetime:
    """Validate one client-captured timestamp used by a durable outbox write.

    Caller-supplied session times change a financial result. Accept them only
    when offline provenance headers agree with the body and are bound to the
    exact idempotent action. Ordinary callers remain on server receipt time.
    """
    action_title = action_label.capitalize()
    if captured_at.tzinfo is None:
        raise BusinessRuleError(f"{action_title} time must include a timezone.")

    offline_header = request.headers.get("X-Offline-Captured", "").strip().lower()
    if offline_header not in {"1", "true", "yes"}:
        raise BusinessRuleError(
            f"A captured {action_label} time is accepted only for an offline saved action."
        )

    idempotency_key = str(getattr(request.state, "idempotency_key", "") or "")
    client_action_id = request.headers.get("X-Client-Action-Id", "").strip()
    if not client_action_id:
        raise BusinessRuleError(
            f"Offline {action_label} is missing its durable action identity."
        )
    if client_action_id != idempotency_key:
        raise BusinessRuleError(
            f"Offline {action_label} action identity does not match its idempotency key. "
            "Nothing was changed."
        )

    provenance_raw = request.headers.get("X-Client-Occurred-At")
    if not provenance_raw:
        raise BusinessRuleError(
            f"Offline {action_label} is missing captured-time provenance."
        )
    try:
        provenance = datetime.fromisoformat(
            provenance_raw.strip().replace("Z", "+00:00")
        )
    except (TypeError, ValueError) as exc:
        raise BusinessRuleError(
            f"Offline {action_label} has invalid captured-time provenance."
        ) from exc
    if provenance.tzinfo is None:
        raise BusinessRuleError(
            f"Offline {action_label} provenance must include a timezone."
        )

    captured = captured_at.astimezone(timezone.utc)
    provenance = provenance.astimezone(timezone.utc)
    now = server_now.astimezone(timezone.utc)
    if abs((provenance - captured).total_seconds()) > 1:
        raise BusinessRuleError(
            f"Saved {action_label} time does not match its audit provenance. "
            "Nothing was changed."
        )
    if minimum_at is not None and captured < minimum_at.astimezone(timezone.utc):
        raise BusinessRuleError(
            f"{action_title} time cannot be before the session started."
        )
    if captured > now + timedelta(minutes=5):
        raise BusinessRuleError(
            f"{action_title} time is in the future. Correct the tablet clock and try again."
        )
    return captured


def _validated_offline_session_end(
    *,
    request: Request,
    ended_at: datetime,
    started_at: datetime,
    server_now: datetime,
) -> datetime:
    return _validated_offline_action_time(
        request=request,
        captured_at=ended_at,
        server_now=server_now,
        action_label="session stop",
        minimum_at=started_at,
    )


def _validated_legacy_outbox_times(
    *,
    captured_started_at: datetime,
    captured_stopped_at: datetime | None,
    server_now: datetime,
) -> tuple[datetime, datetime | None]:
    if captured_started_at.tzinfo is None:
        raise BusinessRuleError("Captured session start time must include a timezone.")
    if captured_stopped_at is not None and captured_stopped_at.tzinfo is None:
        raise BusinessRuleError("Captured session stop time must include a timezone.")

    started = captured_started_at.astimezone(timezone.utc)
    stopped = (
        captured_stopped_at.astimezone(timezone.utc)
        if captured_stopped_at is not None
        else None
    )
    now = server_now.astimezone(timezone.utc)
    if started > now + timedelta(minutes=5):
        raise BusinessRuleError(
            "Captured session start time is in the future. Correct the tablet clock first."
        )
    if stopped is not None:
        if stopped < started:
            raise BusinessRuleError(
                "Captured session stop time cannot be before its captured start time."
            )
        if stopped > now + timedelta(minutes=5):
            raise BusinessRuleError(
                "Captured session stop time is in the future. Correct the tablet clock first."
            )
    return started, stopped


def _legacy_resolution_request_snapshot(
    *,
    payload: LegacyOutboxResolution,
    captured_started_at: datetime,
    captured_stopped_at: datetime | None,
    tenant: TenantContext,
) -> dict[str, str | None]:
    """Canonical immutable owner decision recorded outside the prunable key store."""
    return {
        "company_id": str(tenant.company_id),
        "branch_id": str(tenant.branch_id),
        "terminal_id": str(tenant.terminal_id),
        "local_action_id": str(payload.local_action_id),
        "station_id": str(payload.station_id),
        "shift_id": str(payload.shift_id) if payload.shift_id is not None else None,
        "captured_started_at": captured_started_at.isoformat(),
        "captured_stopped_at": (
            captured_stopped_at.isoformat()
            if captured_stopped_at is not None
            else None
        ),
        "package_id": str(payload.package_id) if payload.package_id is not None else None,
        "expected_rate_per_hour_minor": (
            str(payload.expected_rate_per_hour_minor)
            if payload.expected_rate_per_hour_minor is not None
            else None
        ),
        "resolution": payload.resolution,
        "reference_order_id": (
            str(payload.reference_order_id)
            if payload.reference_order_id is not None
            else None
        ),
        "reason": payload.reason,
    }


async def _durable_legacy_resolution_receipt(
    session,
    *,
    payload: LegacyOutboxResolution,
    request_hash: str,
    request_snapshot: dict[str, str | None],
    tenant: TenantContext,
) -> LegacyOutboxResolutionRead | None:
    """Replay the append-only receipt after its ordinary idempotency row expires.

    The audit receipt is intentionally checked before reserving a new key. One
    local action can therefore never acquire a second owner decision or paid
    order merely because retention cleanup removed ``idempotency_keys``.
    """
    rows = (
        (
            await session.execute(
                select(AuditLog)
                .where(
                    AuditLog.company_id == tenant.company_id,
                    AuditLog.action == "gaming_legacy_outbox_resolution",
                    AuditLog.entity_type == "GamingLegacyOutbox",
                    AuditLog.entity_id == str(payload.local_action_id),
                )
                .order_by(AuditLog.id)
            )
        )
        .scalars()
        .all()
    )
    if not rows:
        return None
    if len(rows) != 1:
        raise ConflictError(
            "More than one protected-owner receipt exists for this saved gaming "
            "action. Nothing was changed; contact support for audit review."
        )

    audit = rows[0]
    after = audit.after if isinstance(audit.after, dict) else {}
    stored_request = after.get("resolution_request")
    stored_receipt = after.get("resolution_receipt")
    if audit.actor_user_id != tenant.user_id:
        raise ConflictError(
            "This saved gaming action was resolved by a different protected owner. "
            "The original receipt was preserved unchanged."
        )
    if (
        after.get("resolution_idempotency_key")
        != f"gaming-legacy-outbox-resolution:{payload.local_action_id}"
        or after.get("resolution_request_hash") != request_hash
        or stored_request != request_snapshot
    ):
        raise ConflictError(
            "This saved gaming action already has a different protected-owner "
            "resolution receipt. The original decision was preserved unchanged."
        )
    if not isinstance(stored_receipt, dict):
        raise ConflictError(
            "This saved gaming action has an incomplete protected-owner receipt. "
            "Nothing was changed; contact support for audit review."
        )
    try:
        return LegacyOutboxResolutionRead.model_validate(
            {
                **stored_receipt,
                "receipt_id": audit.id,
                "resolved_at": audit.created_at,
            }
        )
    except ValidationError as exc:
        raise ConflictError(
            "This saved gaming action has an invalid protected-owner receipt. "
            "Nothing was changed; contact support for audit review."
        ) from exc


def _same_utc_instant(left: datetime, right: datetime) -> bool:
    """Compare two already-validated financial timestamps without tolerance."""
    return left.astimezone(timezone.utc) == right.astimezone(timezone.utc)


async def _legacy_resolution_shift(
    session,
    *,
    shift_id: UUID,
    station: Station,
    captured_started_at: datetime,
    tenant: TenantContext,
) -> Shift:
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == shift_id)
        )
    ).scalar_one_or_none()
    if shift is None or shift.company_id != tenant.company_id:
        raise NotFoundError("captured shift not found")
    if (
        shift.branch_id != tenant.branch_id
        or shift.terminal_id != tenant.terminal_id
        or shift.branch_id != station.branch_id
    ):
        raise BusinessRuleError(
            "The captured shift does not belong to the selected branch, terminal, "
            "and gaming station. The saved action was kept unchanged."
        )
    if captured_started_at < shift.opened_at.astimezone(timezone.utc) - timedelta(
        seconds=1
    ):
        raise BusinessRuleError(
            "The captured gaming start predates its shift. The saved action was kept "
            "unchanged for owner review."
        )
    return shift


def _legacy_start_audit_matches_session(
    audit: AuditLog,
    *,
    gaming_session: GamingSession,
    shift: Shift,
    station: Station,
    captured_started_at: datetime,
    original_start_key: str,
    package_id: UUID | None,
    expected_rate_per_hour_minor: int | None,
) -> bool:
    after = audit.after if isinstance(audit.after, dict) else {}
    try:
        recorded_start = datetime.fromisoformat(
            str(after["start_at"]).replace("Z", "+00:00")
        )
    except (KeyError, TypeError, ValueError):
        return False
    if recorded_start.tzinfo is None:
        return False
    recorded_package_id = after.get("package_id")
    recorded_opened_by = after.get("opened_by")
    try:
        recorded_rate = int(after["rate_per_hour_minor"])
    except (KeyError, TypeError, ValueError):
        recorded_rate = None
    return all(
        (
            audit.action == "create",
            audit.entity_type == "GamingSession",
            audit.entity_id == str(gaming_session.id),
            audit.company_id == gaming_session.company_id,
            audit.client_action_id == original_start_key,
            audit.client_platform == "android",
            audit.client_was_offline is True,
            audit.client_reported_at is not None,
            _same_utc_instant(audit.client_reported_at, captured_started_at),
            audit.terminal_id == shift.terminal_id,
            str(after.get("company_id")) == str(gaming_session.company_id),
            str(after.get("station_id")) == str(station.id),
            str(after.get("shift_id")) == str(shift.id),
            recorded_opened_by is not None,
            (
                audit.actor_user_id is None
                or str(audit.actor_user_id) == str(recorded_opened_by)
            ),
            (
                gaming_session.opened_by is None
                or str(gaming_session.opened_by) == str(recorded_opened_by)
            ),
            _same_utc_instant(recorded_start, gaming_session.start_at),
            (
                recorded_package_id is None
                if package_id is None
                else str(recorded_package_id) == str(package_id)
            ),
            (
                expected_rate_per_hour_minor is None
                or (
                    recorded_rate is not None
                    and recorded_rate == expected_rate_per_hour_minor
                    and int(gaming_session.rate_per_hour_minor)
                    == expected_rate_per_hour_minor
                )
            ),
        )
    )


async def _legacy_stop_recovery_outcome(
    session,
    *,
    gaming_session: GamingSession,
    captured_stopped_at: datetime | None,
    original_local_action_id: UUID,
    shift: Shift,
) -> str:
    if captured_stopped_at is None:
        return "not_captured"
    if gaming_session.end_at is None:
        return "pending_against_active_session"
    if _same_utc_instant(captured_stopped_at, gaming_session.end_at):
        return "matches_authoritative_end"

    stop_key = f"gaming-session-stop:{original_local_action_id}"
    rows = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == gaming_session.company_id,
                    AuditLog.entity_type == "GamingSession",
                    AuditLog.entity_id == str(gaming_session.id),
                    AuditLog.client_action_id == stop_key,
                )
            )
        )
        .scalars()
        .all()
    )
    if len(rows) != 1:
        return "superseded_by_authoritative_terminal_state"
    audit = rows[0]
    after = audit.after if isinstance(audit.after, dict) else {}
    try:
        recorded_end = datetime.fromisoformat(
            str(after["end_at"]).replace("Z", "+00:00")
        )
    except (KeyError, TypeError, ValueError):
        return "superseded_by_authoritative_terminal_state"
    matching_audit = bool(
        recorded_end.tzinfo is not None
        and audit.action == "update"
        and audit.client_platform == "android"
        and audit.client_was_offline is True
        and audit.client_reported_at is not None
        and _same_utc_instant(audit.client_reported_at, captured_stopped_at)
        and audit.terminal_id == shift.terminal_id
        and _same_utc_instant(recorded_end, gaming_session.end_at)
    )
    return (
        "matched_original_stop_audit"
        if matching_audit
        else "superseded_by_authoritative_terminal_state"
    )


async def _authoritative_legacy_server_session(
    session,
    *,
    payload: LegacyOutboxResolution,
    station: Station,
    shift: Shift | None,
    captured_started_at: datetime,
    captured_stopped_at: datetime | None,
    tenant: TenantContext,
) -> tuple[GamingSession | None, str | None, str, str | None, SessionRead | None]:
    """Resolve an accepted client start without guessing from wall-clock proximity.

    The original idempotency response is the primary receipt. If that response
    was aged out, the append-only create audit can still bind the durable
    action ID to exactly one server session. A timestamp-only candidate never
    becomes authority; it merely blocks a contradictory no-play/manual-bill
    attestation for owner investigation.
    """
    original_start_key = f"gaming-session-start:{payload.local_action_id}"
    accepted_start = (
        await session.execute(
            select(IdempotencyKey)
            .where(IdempotencyKey.key == original_start_key)
            .with_for_update()
        )
    ).scalar_one_or_none()
    action_audits = (
        (
            await session.execute(
                select(AuditLog)
                .where(
                    AuditLog.company_id == tenant.company_id,
                    AuditLog.client_action_id == original_start_key,
                )
                .order_by(AuditLog.id)
            )
        )
        .scalars()
        .all()
    )

    if (accepted_start is not None or action_audits) and shift is None:
        raise ConflictError(
            "The server has evidence for this gaming start, but the saved request has "
            "no captured server shift. The action was kept unchanged; refresh the "
            "tablet's shift mapping before owner recovery."
        )

    proof_source: str | None = None
    stored_response: SessionRead | None = None
    gaming_session: GamingSession | None = None

    if accepted_start is not None:
        assert shift is not None
        if (
            accepted_start.terminal_id != shift.terminal_id
            or accepted_start.response_status != status.HTTP_201_CREATED
            or accepted_start.response_body is None
        ):
            raise ConflictError(
                "The original gaming start has partial or conflicting server evidence. "
                "The saved action was kept unchanged for support review."
            )
        try:
            stored_response = SessionRead.model_validate(accepted_start.response_body)
        except ValidationError as exc:
            raise ConflictError(
                "The original gaming start receipt is incomplete. The saved action was "
                "kept unchanged for support review."
            ) from exc
        gaming_session = (
            await session.execute(
                select(GamingSession)
                .where(GamingSession.id == stored_response.id)
                .with_for_update()
                .execution_options(populate_existing=True)
            )
        ).scalar_one_or_none()
        if gaming_session is None:
            raise ConflictError(
                "The original start receipt exists but its server session is missing. "
                "The saved action was kept unchanged for support review."
            )
        proof_source = "idempotency_response"
    elif action_audits:
        assert shift is not None
        if len(action_audits) != 1:
            raise ConflictError(
                "More than one server audit row claims this gaming action. The saved "
                "action was kept unchanged for support review."
            )
        try:
            audited_session_id = UUID(action_audits[0].entity_id)
        except (TypeError, ValueError) as exc:
            raise ConflictError(
                "The original gaming audit receipt is incomplete. The saved action was "
                "kept unchanged for support review."
            ) from exc
        gaming_session = (
            await session.execute(
                select(GamingSession)
                .where(GamingSession.id == audited_session_id)
                .with_for_update()
                .execution_options(populate_existing=True)
            )
        ).scalar_one_or_none()
        if gaming_session is None:
            raise ConflictError(
                "The original gaming audit exists but its server session is missing. "
                "The saved action was kept unchanged for support review."
            )
        proof_source = "audit_action"
    else:
        # No unkeyed/time-only candidate is authoritative enough to attach.
        # It is still contradictory evidence: a protected owner must never
        # attest no-play (or link a second manual bill) merely because the
        # prunable key is gone and the immutable action key is corrupt.
        plausible_audit_ids: list[int] = []
        plausible_session_ids: list[UUID] = []
        if shift is not None:
            if payload.package_id is not None:
                plausible_audit_ids = (
                    await session.execute(
                        select(AuditLog.id).where(
                            AuditLog.company_id == tenant.company_id,
                            AuditLog.action == "create",
                            AuditLog.entity_type == "GamingSession",
                            AuditLog.terminal_id == shift.terminal_id,
                            AuditLog.after.contains(
                                {
                                    "shift_id": str(shift.id),
                                    "package_id": str(payload.package_id),
                                }
                            ),
                        )
                    )
                ).scalars().all()
                plausible_session_ids = (
                    await session.execute(
                        select(GamingSession.id).where(
                            GamingSession.company_id == tenant.company_id,
                            GamingSession.shift_id == shift.id,
                            or_(
                                GamingSession.package_id == payload.package_id,
                                (
                                    GamingSession.package_id.is_(None)
                                    & (
                                        (GamingSession.billing_mode == "package")
                                        | GamingSession.package_price_minor_snapshot.is_not(
                                            None
                                        )
                                        | GamingSession.package_duration_minutes_snapshot.is_not(
                                            None
                                        )
                                    )
                                ),
                            ),
                        )
                    )
                ).scalars().all()
            else:
                plausible_audit_ids = (
                    await session.execute(
                        select(AuditLog.id).where(
                            AuditLog.company_id == tenant.company_id,
                            AuditLog.action == "create",
                            AuditLog.entity_type == "GamingSession",
                            AuditLog.terminal_id == shift.terminal_id,
                            AuditLog.after.contains(
                                {
                                    "shift_id": str(shift.id),
                                    "station_id": str(station.id),
                                    "package_id": None,
                                }
                            ),
                        )
                    )
                ).scalars().all()
                plausible_session_ids = (
                    await session.execute(
                        select(GamingSession.id).where(
                            GamingSession.company_id == tenant.company_id,
                            GamingSession.shift_id == shift.id,
                            GamingSession.station_id == station.id,
                            GamingSession.package_id.is_(None),
                            GamingSession.package_price_minor_snapshot.is_(None),
                            GamingSession.package_duration_minutes_snapshot.is_(None),
                        )
                    )
                ).scalars().all()
        else:
            plausible_session_ids = (
                await session.execute(
                    select(GamingSession.id).where(
                        GamingSession.company_id == tenant.company_id,
                        GamingSession.station_id == station.id,
                        GamingSession.start_at
                        >= captured_started_at - timedelta(seconds=1),
                        GamingSession.start_at
                        <= captured_started_at + timedelta(seconds=1),
                    )
                )
            ).scalars().all()
        if plausible_audit_ids or plausible_session_ids:
            raise ConflictError(
                "Server session history remains in the captured shift or station, but "
                "no exact original action receipt proves which row belongs to this Start. "
                "The saved action was kept unchanged for support review."
            )
        return None, None, original_start_key, None, None

    assert gaming_session is not None
    assert shift is not None
    current_station = await session.get(Station, gaming_session.station_id)
    if (
        gaming_session.company_id != tenant.company_id
        or gaming_session.shift_id != shift.id
        or current_station is None
        or current_station.company_id != tenant.company_id
        or current_station.branch_id != shift.branch_id
    ):
        raise ConflictError(
            "The original gaming receipt points to a different company, branch, or "
            "shift. The saved action was kept unchanged."
        )

    if accepted_start is not None:
        assert stored_response is not None
        audited_actor_id = None
        if len(action_audits) == 1 and isinstance(action_audits[0].after, dict):
            raw_audited_actor_id = action_audits[0].after.get("opened_by")
            if raw_audited_actor_id is not None:
                try:
                    audited_actor_id = UUID(str(raw_audited_actor_id))
                except ValueError:
                    audited_actor_id = None
        original_actor_ids = {
            actor_id
            for actor_id in (
                accepted_start.user_id,
                gaming_session.opened_by,
                audited_actor_id,
            )
            if actor_id is not None
        }
        if (
            not original_actor_ids
            or len(original_actor_ids) != 1
            or stored_response.id != gaming_session.id
            or stored_response.station_id != station.id
            or (
                stored_response.shift_id is not None
                and stored_response.shift_id != shift.id
            )
            or not _same_utc_instant(stored_response.start_at, gaming_session.start_at)
        ):
            raise ConflictError(
                "The original gaming start receipt does not match the current server "
                "session. The saved action was kept unchanged."
            )

    if len(action_audits) > 1:
        raise ConflictError(
            "More than one server audit row claims this gaming action. The saved action "
            "was kept unchanged for support review."
        )
    start_audit = action_audits[0] if action_audits else None
    if start_audit is not None:
        if not _legacy_start_audit_matches_session(
            start_audit,
            gaming_session=gaming_session,
            shift=shift,
            station=station,
            captured_started_at=captured_started_at,
            original_start_key=original_start_key,
            package_id=payload.package_id,
            expected_rate_per_hour_minor=payload.expected_rate_per_hour_minor,
        ):
            raise ConflictError(
                "The original gaming audit does not exactly match the saved action and "
                "server session. The saved action was kept unchanged."
            )
    elif not _same_utc_instant(captured_started_at, gaming_session.start_at):
        raise ConflictError(
            "The original start receipt lacks exact captured-time audit provenance. "
            "The saved action was kept unchanged for support review."
        )

    if stored_response is None:
        assert start_audit is not None
        try:
            stored_response = SessionRead.model_validate(start_audit.after)
        except ValidationError as exc:
            raise ConflictError(
                "The original gaming audit lacks a complete initial session snapshot. "
                "The saved action was kept unchanged for support review."
            ) from exc

    recorded_package_id = (
        stored_response.package_id if stored_response is not None else payload.package_id
    )
    if recorded_package_id != payload.package_id:
        raise ConflictError(
            "The recovered server session uses a different package than the saved action. "
            "The saved action was kept unchanged."
        )
    if (
        gaming_session.package_id is not None
        and gaming_session.package_id != payload.package_id
    ):
        raise ConflictError(
            "The current server session package does not match the saved action. The "
            "saved action was kept unchanged."
        )
    if payload.package_id is None:
        expected_rate = payload.expected_rate_per_hour_minor
        assert expected_rate is not None
        if (
            is_package_billed(gaming_session)
            or stored_response.billing_mode != "hourly"
            or stored_response.rate_per_hour_minor != expected_rate
            or int(gaming_session.rate_per_hour_minor) != expected_rate
        ):
            raise ConflictError(
                "The recovered hourly session rate or billing mode does not match the "
                "saved Start. The action was kept unchanged for owner review."
            )
    stop_recovery_outcome = await _legacy_stop_recovery_outcome(
        session,
        gaming_session=gaming_session,
        captured_stopped_at=captured_stopped_at,
        original_local_action_id=payload.local_action_id,
        shift=shift,
    )
    return (
        gaming_session,
        proof_source,
        original_start_key,
        stop_recovery_outcome,
        stored_response,
    )


async def _validated_legacy_manual_order(
    session,
    *,
    reference_order_id: UUID,
    station: Station,
    local_action_id: UUID,
    tenant: TenantContext,
    recovered_session_id: UUID | None = None,
) -> tuple[Order, int, int, UUID, str, int]:
    """Lock and validate one traceable paid order used by legacy recovery."""
    reference_order = (
        await session.execute(
            select(Order).where(Order.id == reference_order_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not reference_order or reference_order.company_id != tenant.company_id:
        raise NotFoundError("reference POS order not found")
    if (
        reference_order.branch_id != tenant.branch_id
        or reference_order.terminal_id != tenant.terminal_id
    ):
        raise BusinessRuleError(
            "The reference POS order must belong to the selected branch and terminal."
        )
    if reference_order.status != "paid":
        raise BusinessRuleError(
            "The reference POS order must be finalized and paid before it can resolve "
            "played gaming time."
        )
    if int(reference_order.total_minor) <= 0:
        raise BusinessRuleError(
            "The reference POS order has no positive bill and cannot resolve played "
            "gaming time."
        )
    if not reference_order.invoice_no or reference_order.invoice_issued_at is None:
        raise BusinessRuleError(
            "The reference POS order has no issued invoice and cannot resolve played "
            "gaming time. Finalize payment first."
        )
    reference_payment_total_minor = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Payment.amount_minor), 0)).where(
                    Payment.order_id == reference_order.id
                )
            )
        ).scalar_one()
    )
    if reference_payment_total_minor < int(reference_order.total_minor):
        raise BusinessRuleError(
            "The reference POS order does not have complete payment evidence and "
            "cannot resolve played gaming time."
        )
    settled_refunds = (
        (
            await session.execute(
                select(Refund)
                .where(Refund.order_id == reference_order.id)
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    reference_refunded_total_minor = sum(
        int(refund.amount_minor) for refund in settled_refunds
    )
    if reference_refunded_total_minor != 0:
        raise BusinessRuleError(
            "The reference POS order has a settled refund and cannot be linked as a "
            "fully paid legacy gaming bill. Nothing was changed."
        )
    compatible_service_type = _MENU_TYPE_FOR_STATION.get(station.type, "gaming")
    compatible_service_lines = (
        await session.execute(
            select(OrderLine.id, MenuItem.type, OrderLine.line_total_minor)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.order_id == reference_order.id,
                OrderLine.voided_at.is_(None),
                OrderLine.qty > 0,
                OrderLine.line_total_minor > 0,
                MenuItem.company_id == tenant.company_id,
                MenuItem.type == compatible_service_type,
            )
            .order_by(OrderLine.created_at, OrderLine.id)
        )
    ).all()
    if not compatible_service_lines:
        raise BusinessRuleError(
            "The reference POS order has no non-voided paid service line compatible "
            "with this station. Use the invoice that actually billed this gaming "
            "or shisha session."
        )
    if len(compatible_service_lines) != 1:
        raise ConflictError(
            "The reference POS order has more than one compatible service line, so it "
            "cannot be linked to one saved gaming action without guessing."
        )
    compatible_service_line = compatible_service_lines[0]

    linked_session_ids = (
        await session.execute(
            select(GamingSession.id)
            .where(GamingSession.order_id == reference_order.id)
            .with_for_update()
        )
    ).scalars().all()
    if any(
        recovered_session_id is None or linked_id != recovered_session_id
        for linked_id in linked_session_ids
    ):
        raise ConflictError(
            "This POS order is already linked to a different gaming session. Use the "
            "invoice that belongs to this exact saved action."
        )
    prior_reference = (
        await session.execute(
            select(AuditLog.id)
            .where(
                AuditLog.company_id == tenant.company_id,
                AuditLog.action == "gaming_legacy_outbox_resolution",
                AuditLog.entity_id != str(local_action_id),
                AuditLog.after.contains(
                    {"reference_order_id": str(reference_order.id)}
                ),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    if prior_reference is not None:
        raise ConflictError(
            "This POS order already resolves another legacy gaming action. Use a "
            "distinct traceable bill."
        )
    return (
        reference_order,
        reference_payment_total_minor,
        reference_refunded_total_minor,
        compatible_service_line.id,
        compatible_service_line.type,
        int(compatible_service_line.line_total_minor),
    )


def _legacy_hourly_captured_amount(
    *,
    captured_started_at: datetime,
    captured_stopped_at: datetime,
    rate_per_hour_minor: int,
) -> tuple[int, int]:
    elapsed_seconds = max(
        0.0,
        (captured_stopped_at - captured_started_at).total_seconds(),
    )
    billable_minutes = ceil(elapsed_seconds / 60) if elapsed_seconds > 0 else 0
    return (
        billable_minutes,
        session_amount_minor(billable_minutes, rate_per_hour_minor),
    )


async def _cancel_untouched_recovered_no_play(
    session,
    *,
    gaming_session: GamingSession,
    original_snapshot: SessionRead,
    payload: LegacyOutboxResolution,
    captured_stopped_at: datetime | None,
    tenant: TenantContext,
) -> None:
    """Cancel only an exact, untouched accepted Start under owner authority."""
    current = session_read(gaming_session)
    if (
        gaming_session.status not in {"active", "paused"}
        or gaming_session.station_id != payload.station_id
        or gaming_session.order_id is not None
        or gaming_session.end_at is not None
        or gaming_session.cancelled_at is not None
        or gaming_session.cancelled_by is not None
        or gaming_session.cancel_reason is not None
        or current.shift_id != original_snapshot.shift_id
        or current.station_id != original_snapshot.station_id
        or current.status != original_snapshot.status
        or not _same_utc_instant(current.start_at, original_snapshot.start_at)
        or current.customer_name != original_snapshot.customer_name
        or current.customer_phone != original_snapshot.customer_phone
        or current.timer_minutes != original_snapshot.timer_minutes
        or current.paused_minutes != original_snapshot.paused_minutes
        or current.billable_minutes != original_snapshot.billable_minutes
        or current.amount_minor != original_snapshot.amount_minor
        or current.rate_per_hour_minor != original_snapshot.rate_per_hour_minor
        or current.billing_mode != original_snapshot.billing_mode
        or current.package_id != original_snapshot.package_id
        or current.package_price_minor_snapshot
        != original_snapshot.package_price_minor_snapshot
        or current.package_duration_minutes_snapshot
        != original_snapshot.package_duration_minutes_snapshot
        or current.package_variant_snapshot
        != original_snapshot.package_variant_snapshot
        or current.package_station_type_snapshot
        != original_snapshot.package_station_type_snapshot
        or current.extra_controllers != original_snapshot.extra_controllers
    ):
        raise ConflictError(
            "The recovered server session is not an untouched Start, so it cannot be "
            "cancelled as no-play. The saved action remains blocked for owner review."
        )

    extension_ids = (
        await session.execute(
            select(GamingSessionExtension.id)
            .where(
                GamingSessionExtension.company_id == tenant.company_id,
                GamingSessionExtension.gaming_session_id == gaming_session.id,
            )
            .with_for_update()
        )
    ).scalars().all()
    contradictory_audits = (
        await session.execute(
            select(AuditLog.id).where(
                AuditLog.company_id == tenant.company_id,
                AuditLog.entity_type == "GamingSession",
                AuditLog.entity_id == str(gaming_session.id),
                AuditLog.action != "create",
            )
        )
    ).scalars().all()
    if extension_ids or contradictory_audits:
        raise ConflictError(
            "The recovered server session has extension, transfer, pause, stop, or "
            "billing evidence and cannot be cancelled as no-play. Nothing was changed."
        )

    cancelled_at = datetime.now(timezone.utc)
    before = current.model_dump(mode="json")
    gaming_session.status = "cancelled"
    gaming_session.end_at = cancelled_at
    gaming_session.billable_minutes = 0
    gaming_session.amount_minor = 0
    gaming_session.cancelled_at = cancelled_at
    gaming_session.cancelled_by = tenant.user_id
    gaming_session.cancel_reason = payload.reason
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_legacy_server_session_no_play_cancel",
            entity_type="GamingSession",
            entity_id=str(gaming_session.id),
            before=before,
            after={
                **session_read(gaming_session).model_dump(mode="json"),
                "local_action_id": str(payload.local_action_id),
                "owner_attestation": "confirmed_no_play",
                "captured_started_at": payload.captured_started_at.isoformat(),
                "captured_stopped_at": (
                    captured_stopped_at.isoformat()
                    if captured_stopped_at is not None
                    else None
                ),
            },
            terminal_id=tenant.terminal_id,
            reason=payload.reason,
        )
    )
    await session.flush()


def _requested_gaming_branch_id(
    tenant: TenantContext,
    requested_branch_id: UUID | None,
) -> UUID:
    branch_id = _current_gaming_branch_id(tenant)
    if requested_branch_id is not None and requested_branch_id != branch_id:
        raise NotFoundError("branch not found")
    return branch_id


async def _operational_station(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    station_id: UUID,
    for_update: bool = False,
) -> Station | None:
    stmt = (
        select(Station)
        .join(Branch, Branch.id == Station.branch_id)
        .where(
            Station.id == station_id,
            Station.company_id == company_id,
            Station.branch_id == branch_id,
            Branch.company_id == company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if for_update:
        stmt = stmt.with_for_update()
    return (await session.execute(stmt)).scalar_one_or_none()


async def _tenant_booking(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    booking_id: UUID,
    for_update: bool = False,
) -> GamingBooking | None:
    stmt = (
        select(GamingBooking)
        .join(Station, Station.id == GamingBooking.station_id)
        .where(
            GamingBooking.id == booking_id,
            Station.company_id == company_id,
            Station.branch_id == branch_id,
        )
    )
    if for_update:
        stmt = stmt.with_for_update()
    return (await session.execute(stmt)).scalar_one_or_none()


@router.get("/stations", response_model=list[StationRead])
async def list_stations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[StationRead]:
    branch_id = _current_gaming_branch_id(tenant)
    stmt = (
        select(Station)
        .join(Branch, Branch.id == Station.branch_id)
        .where(
            Station.company_id == tenant.company_id,
            Station.branch_id == branch_id,
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
    )
    rows = (await session.execute(stmt)).scalars().all()
    return [
        StationRead(
            id=r.id, branch_id=r.branch_id, code=r.code, name=r.name, type=r.type,
            rate_per_hour_minor=r.rate_per_hour_minor, is_active=r.is_active,
        )
        for r in rows
    ]


@router.post("/stations", response_model=StationRead, status_code=status.HTTP_201_CREATED)
async def create_station(
    payload: StationCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> StationRead:
    require_pricing_unlock(x_pricing_token, tenant)
    branch_id = _requested_gaming_branch_id(tenant, payload.branch_id)
    branch = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if branch is None:
        raise NotFoundError("branch not found")

    existing = (
        await session.execute(
            select(Station).where(
                Station.company_id == tenant.company_id, Station.code == payload.code
            )
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError(f"a station with code '{payload.code}' already exists")

    st = Station(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=branch_id,
        code=payload.code,
        name=payload.name,
        type=payload.type,
        rate_per_hour_minor=payload.rate_per_hour_minor,
        is_active=True,
        notes=payload.notes,
    )
    session.add(st)
    await session.flush()
    return StationRead(
        id=st.id, branch_id=st.branch_id, code=st.code, name=st.name, type=st.type,
        rate_per_hour_minor=st.rate_per_hour_minor, is_active=st.is_active,
    )


@router.patch("/stations/{station_id}", response_model=StationRead)
async def update_station(
    station_id: UUID,
    payload: StationUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
) -> StationRead:
    if payload.rate_per_hour_minor is not None:
        require_pricing_unlock(x_pricing_token, tenant)
    branch_id = _current_gaming_branch_id(tenant)
    st = await _operational_station(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        station_id=station_id,
        for_update=True,
    )
    if not st:
        raise NotFoundError("station not found")
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(st, f, v)
    await session.flush()
    return StationRead(
        id=st.id, branch_id=st.branch_id, code=st.code, name=st.name, type=st.type,
        rate_per_hour_minor=st.rate_per_hour_minor, is_active=st.is_active,
    )


@router.delete("/stations/{station_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_station(
    station_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
    x_pricing_token: str | None = Header(default=None, alias="X-Pricing-Token"),
):
    require_pricing_unlock(x_pricing_token, tenant)
    branch_id = _current_gaming_branch_id(tenant)
    st = await _operational_station(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        station_id=station_id,
        for_update=True,
    )
    if not st:
        raise NotFoundError("station not found")
    # Historical sessions reference stations for billing, GST, and audit trail.
    # Keep the row and hide it from active operations instead of breaking history.
    st.is_active = False
    await session.flush()


@router.get("/packages", response_model=list[GamingPackageRead])
async def list_packages(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
    station_type: str | None = None,
) -> list[GamingPackageRead]:
    branch_id = _current_gaming_branch_id(tenant)
    stmt = select(GamingPackage).where(
        GamingPackage.company_id == tenant.company_id,
        GamingPackage.branch_id == branch_id,
        GamingPackage.deleted_at.is_(None),
        GamingPackage.is_active.is_(True),
    )
    if station_type:
        stmt = stmt.where(GamingPackage.station_type == station_type)
    stmt = stmt.order_by(
        GamingPackage.station_type, GamingPackage.variant, GamingPackage.sort_order
    )
    rows = (await session.execute(stmt)).scalars().all()
    return [
        GamingPackageRead(
            id=p.id,
            station_type=p.station_type,
            variant=p.variant,
            kind=p.kind,
            name=p.name,
            duration_minutes=p.duration_minutes,
            price_minor=p.price_minor,
        )
        for p in rows
    ]


@router.get("/sessions", response_model=list[SessionRead])
async def list_sessions(
    request: Request,
    session: SessionDep,
    status_filter: str | None = Query(default=None, alias="status"),
    unbilled_only: bool = Query(default=False),
    limit: int = Query(default=80, ge=1, le=500),
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[SessionRead]:
    stmt = select(GamingSession).where(GamingSession.company_id == tenant.company_id)
    if tenant.branch_id is not None:
        stmt = stmt.join(Station, Station.id == GamingSession.station_id).where(
            Station.branch_id == tenant.branch_id
        )
    if status_filter:
        stmt = stmt.where(GamingSession.status == status_filter)
    include_cancelled_for_code21 = False
    if unbilled_only:
        unbilled_filter = or_(
            GamingSession.status.in_(("active", "paused")),
            (
                (GamingSession.status == "ended")
                & GamingSession.order_id.is_(None)
            ),
        )
        # Android Code 21 can reconcile a stale local Stop only when a pull
        # returns the authoritative terminal session row. A cancellation made
        # from web otherwise remains hidden by the ordinary unbilled filter.
        # Code 22+ performs an exact lookup for missing local lifecycle IDs;
        # web and all other clients keep the strict unbilled contract.
        client_platform = request.headers.get("X-Client-Platform", "").strip().lower()
        client_version_code = parse_client_version_code(
            request.headers.get("X-Client-Version-Code")
        )
        include_cancelled_for_code21 = (
            client_platform == "android" and client_version_code == 21
        )
        if include_cancelled_for_code21:
            unbilled_filter = or_(
                unbilled_filter,
                GamingSession.status == "cancelled",
            )
        stmt = stmt.where(unbilled_filter)
    if unbilled_only and include_cancelled_for_code21:
        # Preserve every operational obligation ahead of compatibility-only
        # cancellation history when the caller supplies a bounded limit.
        stmt = stmt.order_by(
            (GamingSession.status == "cancelled").asc(),
            GamingSession.start_at.desc(),
        )
    else:
        stmt = stmt.order_by(GamingSession.start_at.desc())
    stmt = stmt.limit(limit)
    rows = (await session.execute(stmt)).scalars().all()
    return [session_read(row) for row in rows]


@router.get("/sessions/{session_id}", response_model=SessionRead)
async def get_session(
    session_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> SessionRead:
    """Return one authoritative session without relying on bounded history."""
    branch_id = _current_gaming_branch_id(tenant)
    gaming_session = (
        await session.execute(
            select(GamingSession)
            .join(Station, Station.id == GamingSession.station_id)
            .where(
                GamingSession.id == session_id,
                GamingSession.company_id == tenant.company_id,
                Station.company_id == tenant.company_id,
                Station.branch_id == branch_id,
            )
        )
    ).scalar_one_or_none()
    if gaming_session is None:
        raise NotFoundError("session not found")
    return session_read(gaming_session)


@router.post("/sessions/start", response_model=SessionRead, status_code=status.HTTP_201_CREATED)
async def start_session(
    payload: SessionStart,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    idempotency_key, request_hash = _gaming_idempotency_or_legacy_ios(request)
    if idempotency_key is not None:
        assert request_hash is not None
        existing_response = await check_or_reserve(
            session,
            key=idempotency_key,
            request_hash=request_hash,
            user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return SessionRead.model_validate(existing_response["body"])

    server_now = datetime.now(timezone.utc)
    started_at = (
        _validated_offline_action_time(
            request=request,
            captured_at=payload.started_at,
            server_now=server_now,
            action_label="session start",
        )
        if payload.started_at is not None
        else server_now
    )

    # Canonical operational lock order is Station -> Shift -> GamingSession.
    # Serialise starts on the station before locking either terminal's shift so
    # distinct terminals cannot both pass the availability check and create
    # overlapping/unbilled sessions for one physical resource.
    station = (
        await session.execute(
            select(Station)
            .where(Station.id == payload.station_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if not station.is_active:
        raise BusinessRuleError("station is not active")
    await _require_terminal_purpose(
        session,
        tenant=tenant,
        allowed=_GAMING_SOURCE_TERMINAL_PURPOSES,
        invalid_message=(
            "This terminal is configured for Cafe POS and cannot start gaming "
            "sessions. Select the Gaming Area terminal."
        ),
    )
    if (
        payload.package_id is None
        and payload.expected_rate_per_hour_minor is None
        and not _is_legacy_ios_online_request(request)
    ):
        raise BusinessRuleError(
            "expected_rate_per_hour_minor is required. Refresh Gaming and confirm "
            "the current station rate before starting the session."
        )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="starting a gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    # Financial time cannot predate its owning shift. Permit one second only
    # for JSON/database timestamp precision at an immediately-following tap.
    shift_opened_at = shift.opened_at.astimezone(timezone.utc)
    if started_at < shift_opened_at - timedelta(seconds=1):
        raise BusinessRuleError(
            "Session start time cannot be before the owning shift opened. "
            "Nothing was changed; review the tablet's saved shift and session actions."
        )
    blocking = (
        await session.execute(
            select(GamingSession).where(
                GamingSession.company_id == tenant.company_id,
                GamingSession.station_id == payload.station_id,
                (
                    GamingSession.status.in_(("active", "paused"))
                    | (
                        (GamingSession.status == "ended")
                        & GamingSession.order_id.is_(None)
                    )
                ),
            ).order_by(GamingSession.start_at.desc()).limit(1)
        )
    ).scalar_one_or_none()
    if blocking and blocking.status == "ended":
        raise ConflictError(
            "station has a stopped session that must be sent to POS or cancelled first"
        )
    if blocking:
        raise ConflictError("station already has an active session")

    if (
        payload.package_id is None
        and payload.expected_rate_per_hour_minor is not None
        and int(station.rate_per_hour_minor) != payload.expected_rate_per_hour_minor
    ):
        raise ConflictError(
            "The station hourly rate changed after it was selected. Refresh Gaming "
            "and confirm the current rate before starting the session."
        )

    package: GamingPackage | None = None
    timer_minutes = payload.timer_minutes
    locked_in_amount_minor: int | None = None
    if payload.package_id is not None:
        package = (
            await session.execute(
                select(GamingPackage)
                .where(GamingPackage.id == payload.package_id)
                .with_for_update()
            )
        ).scalar_one_or_none()
        if (
            not package
            or package.company_id != tenant.company_id
            or package.branch_id != station.branch_id
            or package.deleted_at is not None
            or not package.is_active
        ):
            raise NotFoundError("package not found")
        if package.kind != "base":
            raise BusinessRuleError("only a base package can start a session")
        if package.station_type != station.type:
            raise BusinessRuleError("this package is not offered for this station type")
        # Presence is guaranteed by SessionStart's conditional validator.
        assert payload.expected_package_price_minor is not None
        assert payload.expected_package_duration_minutes is not None
        assert payload.expected_package_variant is not None
        _require_package_snapshot(
            package,
            expected_price_minor=payload.expected_package_price_minor,
            expected_duration_minutes=payload.expected_package_duration_minutes,
            expected_variant=payload.expected_package_variant,
        )
        timer_minutes = package.duration_minutes
        locked_in_amount_minor = package.price_minor + extra_controller_surcharge_minor(
            extra_controllers=payload.extra_controllers,
            duration_minutes=package.duration_minutes,
        )
    elif payload.extra_controllers:
        raise BusinessRuleError("extra_controllers requires a package_id")

    gs = GamingSession(
        id=uuid4(),
        company_id=tenant.company_id,
        station_id=payload.station_id,
        opened_by=tenant.user_id,
        shift_id=payload.shift_id,
        start_at=started_at,
        rate_per_hour_minor=station.rate_per_hour_minor,
        package_id=package.id if package else None,
        billing_mode="package" if package else "hourly",
        package_price_minor_snapshot=(int(package.price_minor) if package else None),
        package_duration_minutes_snapshot=(
            int(package.duration_minutes) if package else None
        ),
        package_variant_snapshot=(package.variant if package else None),
        package_station_type_snapshot=(package.station_type if package else None),
        extra_controllers=payload.extra_controllers,
        amount_minor=locked_in_amount_minor,
        status="active",
        customer_name=payload.customer_name,
        customer_phone=payload.customer_phone,
        timer_minutes=timer_minutes,
        tax_rate=station.tax_rate,
        sac_code=station.sac_code,
        rate_includes_tax=station.rate_includes_tax,
    )
    session.add(gs)
    await session.flush()
    response = session_read(gs)
    if idempotency_key is not None:
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
    return response


@router.patch("/sessions/{session_id}/timer", response_model=SessionRead)
async def set_session_timer(
    session_id: UUID,
    payload: SessionTimerUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),  # noqa: B008
) -> SessionRead:
    gs = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == session_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if gs.status not in ("active", "paused"):
        raise BusinessRuleError("session is not running")
    if is_package_billed(gs):
        raise BusinessRuleError(
            "A package session timer can only be extended with a paid extension package."
        )
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = await session.get(Shift, gs.shift_id)
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="updating a gaming session timer",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    gs.timer_minutes = payload.timer_minutes
    await session.flush()
    return session_read(gs)


@router.post("/sessions/{session_id}/extend-timer", response_model=SessionRead)
async def extend_session_timer(
    session_id: UUID,
    payload: SessionTimerExtend,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    """Add time using a locked server clock and an explicit client snapshot."""
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionRead.model_validate(existing_response["body"])

    gs = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == session_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if gs.status not in ("active", "paused"):
        raise BusinessRuleError("session is not running")
    if is_package_billed(gs):
        raise BusinessRuleError(
            "A package session timer can only be extended with a paid extension package."
        )

    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = await session.get(Shift, gs.shift_id)
    require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="extending a gaming session timer",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    stored_timer = gs.timer_minutes
    if stored_timer != payload.expected_timer_minutes:
        raise ConflictError(
            "Session timer changed on another device. Refresh Gaming before adding time."
        )

    elapsed_minutes = _elapsed_billable_whole_minutes(
        started_at=gs.start_at,
        server_now=datetime.now(timezone.utc),
        paused_minutes=gs.paused_minutes,
    )
    timer_base = max(int(stored_timer or 0), elapsed_minutes)
    target_timer = timer_base + payload.additional_minutes
    if target_timer > 1440:
        raise BusinessRuleError(
            "Session timer cannot exceed 1440 minutes. Stop this session and start "
            "a new one if more time is needed."
        )

    gs.timer_minutes = target_timer
    await session.flush()
    response = session_read(gs)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post("/sessions/{session_id}/transfer", response_model=SessionRead)
async def transfer_session(
    session_id: UUID,
    payload: SessionTransfer,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    """Move a running session to an available station without repricing it."""
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionRead.model_validate(existing_response["body"])

    # Lock the caller's exact source snapshot and target in global UUID order,
    # then compare the authoritative session under its own row lock. This both
    # prevents station-transfer deadlocks and refuses stale A -> B commands
    # after another device has already moved the session A -> C.
    station_ids = sorted(
        {payload.expected_source_station_id, payload.target_station_id},
        key=lambda value: value.int,
    )
    locked_stations = (
        await session.execute(
            select(Station)
            .where(Station.id.in_(station_ids))
            .order_by(Station.id)
            .with_for_update()
        )
    ).scalars().all()
    stations_by_id = {row.id: row for row in locked_stations}

    gs = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == session_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if gs.status not in ("active", "paused"):
        raise BusinessRuleError("session is not running")

    if gs.station_id != payload.expected_source_station_id:
        raise ConflictError(
            "Session was transferred on another device. Refresh Gaming and try again."
        )

    source = stations_by_id.get(payload.expected_source_station_id)
    target = stations_by_id.get(payload.target_station_id)
    if not source or source.company_id != tenant.company_id:
        raise NotFoundError("source station not found")
    if not target or target.company_id != tenant.company_id:
        raise NotFoundError("target station not found")
    if source.branch_id != target.branch_id:
        raise BusinessRuleError("Target station belongs to a different branch.")
    if tenant.branch_id is None or source.branch_id != tenant.branch_id:
        raise BusinessRuleError("Gaming session belongs to a different branch.")
    if source.type != target.type:
        raise BusinessRuleError("Transfer requires a station of the same type.")
    if not target.is_active:
        raise BusinessRuleError("Target station is not active.")

    shift = await session.get(Shift, gs.shift_id)
    require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="transferring a gaming session",
        resource_branch_id=source.branch_id,
        resource_name="gaming station",
    )

    if source.id != target.id:
        blocking = (
            await session.execute(
                select(GamingSession)
                .where(
                    GamingSession.company_id == tenant.company_id,
                    GamingSession.station_id == target.id,
                    GamingSession.id != gs.id,
                    or_(
                        GamingSession.status.in_(("active", "paused")),
                        (
                            (GamingSession.status == "ended")
                            & GamingSession.order_id.is_(None)
                        ),
                    ),
                )
                .order_by(GamingSession.start_at.desc())
                .limit(1)
            )
        ).scalar_one_or_none()
        if blocking and blocking.status == "ended":
            raise ConflictError(
                "Target station has a stopped session awaiting POS or cancellation."
            )
        if blocking:
            raise ConflictError("Target station already has a running session.")
        # Deliberately change only station_id. Historical rate, package,
        # controller surcharge, timer, and source shift remain locked in.
        gs.station_id = target.id
        await session.flush()

    response = session_read(gs)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post("/sessions/{session_id}/extend", response_model=SessionRead)
async def extend_session_with_package(
    session_id: UUID,
    payload: SessionExtend,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    """Add a paid extension package on top of a running package session.

    Only for sessions that started with a base package — open-ended sessions
    have no fixed price to extend and just keep running (use the plain timer
    endpoint above to move their reminder alarm instead).
    """
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionRead.model_validate(existing_response["body"])

    # The immutable extension ledger outlives the generic idempotency cache.
    # If an operator retries the same durable action after that cache row was
    # purged, recognize the already-applied charge before checking whether the
    # session is still running (it may since have been stopped and sent to POS).
    durable_replay = (
        await session.execute(
            select(GamingSessionExtension)
            .where(
                GamingSessionExtension.company_id == tenant.company_id,
                GamingSessionExtension.idempotency_key == idempotency_key,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if durable_replay is not None:
        replay_matches = (
            durable_replay.gaming_session_id == session_id
            and int(durable_replay.timer_before_minutes)
            == payload.expected_timer_minutes
            and int(durable_replay.amount_before_minor)
            == payload.expected_amount_minor
            and int(durable_replay.package_price_minor)
            == payload.expected_package_price_minor
            and int(durable_replay.duration_minutes)
            == payload.expected_package_duration_minutes
            and durable_replay.package_variant == payload.expected_package_variant
            and (
                durable_replay.package_id is None
                or durable_replay.package_id == payload.package_id
            )
        )
        if not replay_matches:
            raise ConflictError(
                "Idempotency-Key already belongs to a different gaming extension. "
                "The session was not charged again."
            )
        if durable_replay.created_by != tenant.user_id:
            raise ConflictError(
                "This saved gaming extension belongs to a different employee. "
                "The session was not charged again."
            )

        replay_session = (
            await session.execute(
                select(GamingSession)
                .where(GamingSession.id == session_id)
                .with_for_update()
            )
        ).scalar_one_or_none()
        if not replay_session or replay_session.company_id != tenant.company_id:
            raise NotFoundError("session not found")
        replay_station = await session.get(Station, replay_session.station_id)
        replay_shift = await session.get(Shift, replay_session.shift_id)
        if (
            not replay_station
            or replay_station.company_id != tenant.company_id
            or replay_station.branch_id != tenant.branch_id
            or not replay_shift
            or replay_shift.company_id != tenant.company_id
            or replay_shift.branch_id != tenant.branch_id
            or replay_shift.terminal_id != tenant.terminal_id
        ):
            raise NotFoundError("session not found")
        if (
            replay_session.timer_minutes is None
            or replay_session.amount_minor is None
            or int(replay_session.timer_minutes)
            < int(durable_replay.timer_after_minutes)
            or int(replay_session.amount_minor)
            < int(durable_replay.amount_after_minor)
        ):
            raise GamingBillingRepairRequiredError(
                "The immutable extension receipt exists, but the session total no longer "
                "contains that charge. A protected owner must repair this session; it was "
                "not charged again."
            )
        response = session_read(replay_session)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_200_OK,
            body=response.model_dump(mode="json"),
        )
        return response

    # Different idempotency keys represent distinct purchased extensions.
    # Lock the session before reading its current totals so concurrent devices
    # apply their increments serially instead of both writing from the same
    # stale amount/timer snapshot and losing one purchase.
    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")

    # Establish exact tenant/branch/terminal ownership before returning the
    # only error code Android may treat as proof that this durable action was
    # never charged. Validation/idempotency/ledger conflicts intentionally use
    # their ordinary non-discard-safe codes.
    station = await session.get(Station, gs.station_id)
    shift = await session.get(Shift, gs.shift_id)
    if (
        not station
        or station.company_id != tenant.company_id
        or station.branch_id != tenant.branch_id
        or not shift
        or shift.company_id != tenant.company_id
        or shift.branch_id != tenant.branch_id
        or shift.terminal_id != tenant.terminal_id
    ):
        raise NotFoundError("session not found")
    if gs.order_id is not None:
        raise _extension_not_applied(
            gs,
            reason_code="session_already_linked_to_pos",
            message="This session is already linked to a POS order.",
        )
    if gs.status not in ("active", "paused"):
        raise _extension_not_applied(
            gs,
            reason_code="session_not_running",
            message="The session is no longer running.",
        )
    if shift.status != "open":
        raise _extension_not_applied(
            gs,
            reason_code="source_shift_closed",
            message=f"The source shift is {shift.status}.",
        )
    if not is_package_billed(gs):
        raise _extension_not_applied(
            gs,
            reason_code="session_not_package_billed",
            message="This session has no base package to extend.",
        )
    if gs.timer_minutes is None or gs.amount_minor is None:
        raise _extension_not_applied(
            gs,
            reason_code="session_totals_incomplete",
            message=(
                "This package session is missing its locked timer or billed amount; "
                "a protected owner must review it."
            ),
        )
    if (
        int(gs.timer_minutes) != payload.expected_timer_minutes
        or int(gs.amount_minor) != payload.expected_amount_minor
    ):
        raise _extension_not_applied(
            gs,
            reason_code="session_snapshot_stale",
            message="Session time or billed amount changed on another device.",
        )
    if not has_complete_package_snapshot(gs):
        raise _extension_not_applied(
            gs,
            reason_code="session_package_snapshot_incomplete",
            message=(
                "The session's locked package evidence is incomplete; a protected "
                "owner must review it."
            ),
        )
    if gs.package_station_type_snapshot != station.type:
        raise _extension_not_applied(
            gs,
            reason_code="session_station_type_mismatch",
            message="The session's locked package station type no longer matches.",
        )

    extension = (
        await session.execute(
            select(GamingPackage)
            .where(GamingPackage.id == payload.package_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if extension is None:
        raise _extension_not_applied(
            gs,
            reason_code="extension_package_missing",
            message="The selected extension package no longer exists.",
        )
    if (
        extension.company_id != tenant.company_id
        or extension.branch_id != station.branch_id
    ):
        # A package from another tenant/branch is a scope violation, never
        # discard proof for a local durable action.
        raise NotFoundError("package not found")
    if extension.deleted_at is not None or not extension.is_active:
        raise _extension_not_applied(
            gs,
            reason_code="extension_package_retired",
            message="The selected extension package is no longer active.",
        )
    if extension.kind != "extension":
        raise _extension_not_applied(
            gs,
            reason_code="package_kind_incompatible",
            message="The selected package is not an extension package.",
        )
    if extension.station_type != station.type:
        raise _extension_not_applied(
            gs,
            reason_code="package_station_type_incompatible",
            message="The selected extension is not offered for this station type.",
        )
    if extension.variant != gs.package_variant_snapshot:
        raise _extension_not_applied(
            gs,
            reason_code="package_variant_incompatible",
            message="The extension does not match the session's original package variant.",
        )
    if (
        int(extension.price_minor) != payload.expected_package_price_minor
        or int(extension.duration_minutes)
        != payload.expected_package_duration_minutes
        or extension.variant != payload.expected_package_variant
    ):
        raise _extension_not_applied(
            gs,
            reason_code="extension_package_snapshot_stale",
            message="The selected extension package changed after it was reviewed.",
        )

    extra_surcharge = extra_controller_surcharge_minor(
        extra_controllers=gs.extra_controllers,
        duration_minutes=extension.duration_minutes,
    )
    timer_before = int(gs.timer_minutes or 0)
    timer_after = timer_before + int(extension.duration_minutes)
    if timer_after > 1440:
        raise _extension_not_applied(
            gs,
            reason_code="session_timer_limit",
            message="The session timer cannot exceed 1440 minutes.",
        )
    amount_before = int(gs.amount_minor)
    extension_total = int(extension.price_minor) + extra_surcharge
    amount_after = amount_before + extension_total
    session.add(
        GamingSessionExtension(
            id=uuid4(),
            company_id=tenant.company_id,
            gaming_session_id=gs.id,
            package_id=extension.id,
            package_name=extension.name,
            package_variant=extension.variant,
            station_type=extension.station_type,
            duration_minutes=int(extension.duration_minutes),
            package_price_minor=int(extension.price_minor),
            controller_surcharge_minor=extra_surcharge,
            total_minor=extension_total,
            timer_before_minutes=timer_before,
            timer_after_minutes=timer_after,
            amount_before_minor=amount_before,
            amount_after_minor=amount_after,
            idempotency_key=idempotency_key,
            created_by=tenant.user_id,
        )
    )
    gs.amount_minor = amount_after
    gs.timer_minutes = timer_after
    await session.flush()
    response = session_read(gs)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post("/sessions/{session_id}/stop", response_model=SessionRead)
async def stop_session(
    session_id: UUID,
    session: SessionDep,
    request: Request,
    payload: SessionStop | None = None,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    idempotency_key, request_hash = _gaming_idempotency_or_legacy_ios(request)
    if idempotency_key is not None:
        assert request_hash is not None
        existing_response = await check_or_reserve(
            session,
            key=idempotency_key,
            request_hash=request_hash,
            user_id=tenant.user_id,
            terminal_id=tenant.terminal_id,
        )
        if existing_response:
            return SessionRead.model_validate(existing_response["body"])

    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="stopping a gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    server_now = datetime.now(timezone.utc)
    has_captured_end = payload is not None and payload.ended_at is not None
    captured_end = (
        _validated_offline_session_end(
            request=request,
            ended_at=payload.ended_at,
            started_at=gs.start_at,
            server_now=server_now,
        )
        if has_captured_end
        else server_now
    )
    if gs.status == "ended":
        # Response-loss retry: validate the exact operational scope first,
        # then return the already-computed result.
        # The original shift may have been closed after the successful stop.
        if has_captured_end and (
            gs.end_at is None
            or abs((gs.end_at - captured_end).total_seconds()) > 1
        ):
            raise ConflictError(
                "Session was already stopped at a different time. Refresh Gaming."
            )
        response = session_read(gs)
        if idempotency_key is not None:
            await store_response(
                session,
                key=idempotency_key,
                status_code=status.HTTP_200_OK,
                body=response.model_dump(mode="json"),
            )
        return response
    if gs.status == "cancelled":
        raise BusinessRuleError("session was cancelled")
    if gs.status not in ("active", "paused"):
        raise BusinessRuleError(f"cannot stop a session in status={gs.status}")
    if shift.status != "open":
        raise BusinessRuleError(
            "Shift is "
            f"{shift.status}. Open a shift for this terminal before stopping a gaming session."
        )
    package_billing = is_package_billed(gs)
    if package_billing:
        # A discriminator-only legacy package row can still stop safely because
        # its amount was locked before play. A *partial* snapshot is evidence of
        # corrupt/incomplete financial provenance and must be repaired first.
        if has_partial_package_snapshot(gs):
            _require_complete_package_billing_snapshot(gs, operation="stopped")
        if gs.amount_minor is None:
            raise GamingBillingRepairRequiredError(
                "This package session is missing its locked billed amount. Nothing was "
                "stopped. A protected owner must review and repair billing first."
            )
    gs.end_at = captured_end
    elapsed_seconds = max(0.0, (gs.end_at - gs.start_at).total_seconds())
    elapsed_minutes = ceil(elapsed_seconds / 60) if elapsed_seconds > 0 else 0
    gs.billable_minutes = max(0, elapsed_minutes - gs.paused_minutes)
    if not package_billing:
        # Open-ended session — bill by actual elapsed time, as before.
        gs.amount_minor = session_amount_minor(gs.billable_minutes, gs.rate_per_hour_minor)
    # else: a package session's amount_minor was locked in at start (plus any
    # paid extensions) and never changes based on how long they actually
    # played — billable_minutes above is still recorded for the audit trail.
    gs.status = "ended"
    gs.stopped_by = tenant.user_id
    response = session_read(gs)
    if idempotency_key is not None:
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_200_OK,
            body=response.model_dump(mode="json"),
        )
    return response


async def _addon_receipt_for_key(
    session,
    *,
    company_id: UUID,
    idempotency_key: str,
) -> GamingSessionAddon | None:
    """Find a durable add or void receipt after generic-key retention expires."""
    return (
        await session.execute(
            select(GamingSessionAddon)
            .where(
                GamingSessionAddon.company_id == company_id,
                or_(
                    GamingSessionAddon.idempotency_key == idempotency_key,
                    GamingSessionAddon.void_idempotency_key == idempotency_key,
                ),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()


async def _lock_session_addon_catalog(
    session,
    *,
    company_id: UUID,
    payload: SessionAddonCreate,
) -> MenuItem:
    """Lock every mutable catalog row used by one immutable price snapshot."""
    item = (
        await session.execute(
            select(MenuItem)
            .where(
                MenuItem.id == payload.menu_item_id,
                MenuItem.company_id == company_id,
                MenuItem.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if item is None:
        raise NotFoundError("menu item not found")
    if not item.is_available:
        raise BusinessRuleError("menu item is not available")
    if item.type not in {"food", "drink", "dessert"}:
        raise BusinessRuleError(
            "Only food, drink, or dessert catalog items may be added to a Gaming session."
        )

    if payload.variant_id is not None:
        # The pricing service performs the semantic item/active checks. This
        # lock prevents a concurrent catalog edit between validation and the
        # immutable snapshot insert.
        await session.execute(
            select(MenuVariant)
            .where(
                MenuVariant.company_id == company_id,
                MenuVariant.id == payload.variant_id,
            )
            .with_for_update()
        )
    await session.execute(
        select(MenuModifierGroup)
        .where(
            MenuModifierGroup.company_id == company_id,
            MenuModifierGroup.menu_item_id == payload.menu_item_id,
        )
        .order_by(MenuModifierGroup.id)
        .with_for_update()
    )
    modifier_ids = sorted(
        {selection.modifier_id for selection in payload.modifiers},
        key=str,
    )
    if modifier_ids:
        await session.execute(
            select(MenuModifier)
            .where(
                MenuModifier.company_id == company_id,
                MenuModifier.id.in_(modifier_ids),
            )
            .order_by(MenuModifier.id)
            .with_for_update()
        )
    return item


@router.get(
    "/sessions/{session_id}/addons",
    response_model=list[SessionAddonRead],
)
async def list_session_addons(
    session_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
) -> list[SessionAddonRead]:
    """Return active and voided staged items so clients can recover after restart."""
    gs = (
        await session.execute(
            select(GamingSession).where(
                GamingSession.id == session_id,
                GamingSession.company_id == tenant.company_id,
            )
        )
    ).scalar_one_or_none()
    if gs is None:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if station is None or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if tenant.branch_id is None or station.branch_id != tenant.branch_id:
        raise NotFoundError("session not found")

    addons = (
        await session.execute(
            select(GamingSessionAddon)
            .where(
                GamingSessionAddon.company_id == tenant.company_id,
                GamingSessionAddon.gaming_session_id == gs.id,
            )
            .order_by(GamingSessionAddon.created_at, GamingSessionAddon.id)
        )
    ).scalars().all()
    return [session_addon_read(addon) for addon in addons]


@router.post(
    "/sessions/{session_id}/addons",
    response_model=SessionAddonRead,
    status_code=status.HTTP_201_CREATED,
)
async def add_session_addon(
    session_id: UUID,
    payload: SessionAddonCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionAddonRead:
    """Stage one server-priced snack/drink without moving stock yet."""
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionAddonRead.model_validate(existing_response["body"])
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for Gaming add-ons")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")

    durable_receipt = await _addon_receipt_for_key(
        session,
        company_id=tenant.company_id,
        idempotency_key=idempotency_key,
    )
    if durable_receipt is not None:
        if (
            durable_receipt.idempotency_key != idempotency_key
            or durable_receipt.request_hash != request_hash
            or durable_receipt.gaming_session_id != session_id
            or durable_receipt.created_by != tenant.user_id
            or durable_receipt.created_terminal_id != tenant.terminal_id
        ):
            raise ConflictError(
                "Idempotency-Key already belongs to a different Gaming add-on action."
            )
        response = session_addon_read(durable_receipt)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    gs = (
        await session.execute(
            select(GamingSession)
            .where(
                GamingSession.id == session_id,
                GamingSession.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if gs is None:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if station is None or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if station.branch_id != tenant.branch_id:
        raise NotFoundError("session not found")
    if gs.order_id is not None:
        raise BusinessRuleError("This session was already sent to POS.")
    if gs.status not in {"active", "paused"}:
        raise BusinessRuleError(
            "New Gaming add-ons require an active or paused session."
        )
    await _require_terminal_purpose(
        session,
        tenant=tenant,
        allowed=_GAMING_SOURCE_TERMINAL_PURPOSES,
        invalid_message=(
            "This terminal is not configured to host Gaming sessions. "
            "Select the Gaming Area terminal."
        ),
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="adding an item to this Gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    duplicate_line = (
        await session.execute(
            select(GamingSessionAddon.id).where(
                GamingSessionAddon.gaming_session_id == gs.id,
                GamingSessionAddon.client_line_id == payload.client_line_id,
            )
        )
    ).scalar_one_or_none()
    if duplicate_line is not None:
        raise ConflictError(
            "client_line_id already belongs to another saved item on this session.",
            details={"addon_id": str(duplicate_line)},
        )

    item = await _lock_session_addon_catalog(
        session,
        company_id=tenant.company_id,
        payload=payload,
    )
    priced_order = await OrderPricingService(session).price_order(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        line_requests=[
            LineRequest(
                menu_item_id=payload.menu_item_id,
                qty=payload.qty,
                variant_id=payload.variant_id,
                modifiers=tuple(
                    ModifierSelection(
                        modifier_id=selection.modifier_id,
                        quantity=selection.qty,
                    )
                    for selection in payload.modifiers
                ),
            )
        ],
        customer_phone=gs.customer_phone,
    )
    priced_line = priced_order.lines[0]
    catalog_unit_price_minor = int(
        priced_line.base_unit_price_minor
        + priced_line.customization_unit_delta_minor
    )
    if catalog_unit_price_minor != payload.expected_unit_price_minor:
        raise ConflictError(
            "Menu pricing changed after this item was selected. Refresh Gaming and try again.",
            details={
                "expected_unit_price_minor": payload.expected_unit_price_minor,
                "current_unit_price_minor": catalog_unit_price_minor,
            },
        )

    addon = GamingSessionAddon(
        id=uuid4(),
        company_id=tenant.company_id,
        gaming_session_id=gs.id,
        client_line_id=payload.client_line_id,
        menu_item_id=item.id,
        menu_item_name_snapshot=priced_line.name,
        menu_item_type_snapshot=priced_line.item_type,
        variant_id=(
            priced_line.variant_snapshot.id
            if priced_line.variant_snapshot is not None
            else None
        ),
        variant_snapshot=(
            priced_line.variant_snapshot.as_dict()
            if priced_line.variant_snapshot is not None
            else None
        ),
        modifiers=[snapshot.as_dict() for snapshot in priced_line.modifier_snapshots],
        qty=priced_line.qty,
        catalog_unit_price_minor=catalog_unit_price_minor,
        unit_price_minor=priced_line.unit_inclusive_minor,
        line_total_minor=priced_line.line_inclusive_minor,
        discount_minor=priced_line.discount_minor,
        hsn_or_sac=priced_line.hsn_or_sac or None,
        tax_rate=priced_line.tax_rate,
        taxable_value_minor=priced_line.taxable_value_minor,
        cgst_minor=priced_line.cgst_minor,
        sgst_minor=priced_line.sgst_minor,
        igst_minor=priced_line.igst_minor,
        cess_minor=priced_line.cess_minor,
        note=payload.note,
        idempotency_key=idempotency_key,
        request_hash=request_hash,
        created_by=tenant.user_id,
        created_terminal_id=tenant.terminal_id,
    )
    session.add(addon)
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_session_addon_added",
            entity_type="GamingSessionAddon",
            entity_id=str(addon.id),
            before=None,
            after={
                "gaming_session_id": str(gs.id),
                "client_line_id": str(payload.client_line_id),
                "menu_item_id": str(item.id),
                "menu_item_name": priced_line.name,
                "menu_item_type": priced_line.item_type,
                "qty": priced_line.qty,
                "line_total_minor": priced_line.line_inclusive_minor,
                "idempotency_key": idempotency_key,
            },
            terminal_id=tenant.terminal_id,
            reason="Item consumed during Gaming session; staged for combined POS bill",
        )
    )
    await session.flush()
    response = session_addon_read(addon)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/sessions/{session_id}/addons/{addon_id}/void",
    response_model=SessionAddonRead,
)
async def void_session_addon(
    session_id: UUID,
    addon_id: UUID,
    payload: SessionAddonVoid,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionAddonRead:
    """Reason-soft-void one staged item before POS handoff."""
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionAddonRead.model_validate(existing_response["body"])
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for Gaming add-on voids")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")

    durable_receipt = await _addon_receipt_for_key(
        session,
        company_id=tenant.company_id,
        idempotency_key=idempotency_key,
    )
    if durable_receipt is not None:
        if (
            durable_receipt.id != addon_id
            or durable_receipt.gaming_session_id != session_id
            or durable_receipt.void_idempotency_key != idempotency_key
            or durable_receipt.void_request_hash != request_hash
            or durable_receipt.voided_by != tenant.user_id
            or durable_receipt.voided_terminal_id != tenant.terminal_id
        ):
            raise ConflictError(
                "Idempotency-Key already belongs to a different Gaming add-on action."
            )
        response = session_addon_read(durable_receipt)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_200_OK,
            body=response.model_dump(mode="json"),
        )
        return response

    gs = (
        await session.execute(
            select(GamingSession)
            .where(
                GamingSession.id == session_id,
                GamingSession.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if gs is None:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if station is None or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if station.branch_id != tenant.branch_id:
        raise NotFoundError("session not found")
    if gs.order_id is not None:
        raise BusinessRuleError("An item cannot be voided after POS handoff.")
    if gs.status not in {"active", "paused", "ended"}:
        raise BusinessRuleError(
            "A Gaming add-on can only be voided before handoff from an active, "
            "paused, or stopped session."
        )
    await _require_terminal_purpose(
        session,
        tenant=tenant,
        allowed=_GAMING_SOURCE_TERMINAL_PURPOSES,
        invalid_message=(
            "This terminal is not configured to host Gaming sessions. "
            "Select the Gaming Area terminal."
        ),
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="voiding an item on this Gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    addon = (
        await session.execute(
            select(GamingSessionAddon)
            .where(
                GamingSessionAddon.id == addon_id,
                GamingSessionAddon.company_id == tenant.company_id,
                GamingSessionAddon.gaming_session_id == gs.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if addon is None:
        raise NotFoundError("Gaming session add-on not found")
    if addon.voided_at is not None:
        raise ConflictError(
            "This Gaming add-on was already voided by another action. Refresh Gaming."
        )

    now = datetime.now(timezone.utc)
    addon.voided_at = now
    addon.voided_by = tenant.user_id
    addon.void_reason = payload.reason
    addon.void_idempotency_key = idempotency_key
    addon.void_request_hash = request_hash
    addon.voided_terminal_id = tenant.terminal_id
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_session_addon_voided",
            entity_type="GamingSessionAddon",
            entity_id=str(addon.id),
            before={"voided_at": None},
            after={
                "gaming_session_id": str(gs.id),
                "voided_at": now.isoformat(),
                "void_reason": payload.reason,
                "void_idempotency_key": idempotency_key,
            },
            terminal_id=tenant.terminal_id,
            reason=payload.reason,
        )
    )
    await session.flush()
    response = session_addon_read(addon)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post("/sessions/{session_id}/cancel", response_model=SessionRead)
async def cancel_session(
    session_id: UUID,
    payload: SessionCancel,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> SessionRead:
    """Cancel a mistaken/unbillable session with an auditable reason.

    Operational staff may cancel a session on the exact current branch and
    terminal while its shift is open. A protected owner may also clean up a
    legacy ended session whose shift was already closed, but cannot cancel a
    session that already produced an order.
    """
    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="cancelling a gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    if gs.status == "cancelled":
        # Safe response-loss replay, including after the original shift closes.
        return session_read(gs)
    if gs.order_id is not None:
        raise BusinessRuleError("cannot cancel a session that was already sent to POS")
    if gs.status not in ("active", "paused", "ended"):
        raise BusinessRuleError(f"cannot cancel a session in status={gs.status}")
    active_addon_count = int(
        (
            await session.execute(
                select(func.count(GamingSessionAddon.id)).where(
                    GamingSessionAddon.company_id == tenant.company_id,
                    GamingSessionAddon.gaming_session_id == gs.id,
                    GamingSessionAddon.voided_at.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if active_addon_count:
        raise BusinessRuleError(
            "Void every active Gaming add-on before cancelling this session; "
            "consumed items must not disappear from the bill."
        )
    if gs.status == "ended":
        _require_repaired_ended_amount(gs)
    if shift.status != "open" and not tenant.protected_access:
        raise BusinessRuleError(
            f"Shift is {shift.status}. Ask a protected owner to cancel this legacy session."
        )
    if shift.status != "open" and gs.status != "ended":
        raise BusinessRuleError(
            "A protected owner may only cancel a legacy ended session after its shift closed."
        )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="cancel a gaming or shisha session on this shift",
    )

    now = datetime.now(timezone.utc)
    gs.status = "cancelled"
    gs.end_at = gs.end_at or now
    gs.billable_minutes = 0
    gs.amount_minor = 0
    gs.cancelled_at = now
    gs.cancelled_by = tenant.user_id
    gs.cancel_reason = payload.reason
    await session.flush()
    return session_read(gs)


@router.post("/sessions/{session_id}/repair-billing", response_model=SessionRead)
async def repair_session_billing(
    session_id: UUID,
    payload: SessionBillingRepair,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
) -> SessionRead:
    """Protected-owner compare-and-swap repair for a missing ended bill."""
    if not tenant.audit_access:
        raise ForbiddenError(
            "Only the protected owner can repair a stopped session's billing."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return SessionRead.model_validate(existing_response["body"])

    gs = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == session_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if tenant.branch_id is None or station.branch_id != tenant.branch_id:
        raise BusinessRuleError(
            "The gaming session belongs to a different branch. Select its branch "
            "before repairing billing."
        )
    if gs.status != "ended":
        raise BusinessRuleError("Only an ended session can have missing billing repaired.")
    if gs.order_id is not None:
        raise BusinessRuleError(
            "This session already has a POS order. Repair the order through the "
            "accounting reconciliation workflow instead."
        )
    if gs.amount_minor != payload.expected_amount_minor:
        raise ConflictError(
            "The session billed amount changed after it was reviewed. Refresh Gaming "
            "before attempting a repair."
        )
    if gs.amount_minor is not None:
        raise ConflictError(
            "This session already has a billed amount and cannot use missing-bill repair."
        )

    before = {
        "amount_minor": None,
        "billable_minutes": gs.billable_minutes,
        "rate_per_hour_minor": int(gs.rate_per_hour_minor),
        "package_id": str(gs.package_id) if gs.package_id else None,
    }
    gs.amount_minor = payload.amount_minor
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_session_billing_repair",
            entity_type="GamingSession",
            entity_id=str(gs.id),
            before=before,
            after={
                **before,
                "amount_minor": payload.amount_minor,
                "reason": payload.reason,
            },
            terminal_id=tenant.terminal_id,
            reason=payload.reason,
        )
    )
    await session.flush()
    response = session_read(gs)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/legacy-outbox-resolutions",
    response_model=LegacyOutboxResolutionRead,
    status_code=status.HTTP_201_CREATED,
)
async def resolve_legacy_gaming_outbox(
    payload: LegacyOutboxResolution,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
) -> LegacyOutboxResolutionRead:
    """Record protected-owner evidence that unblocks a client-only legacy row.

    This deliberately creates no GamingSession, Order, payment, or guessed
    amount. If the original Start was accepted, it returns that authoritative
    session; an exact protected-owner manual-bill resolution may link the
    already-paid invoice so the session cannot be billed twice. Otherwise a
    played row must reference the real manual POS order that carries its
    financial obligation; a no-play row is an explicit owner attestation.
    """
    if not tenant.audit_access:
        raise ForbiddenError(
            "Only the protected owner can resolve a legacy gaming action."
        )
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the original branch and terminal before resolving this saved action."
        )

    idempotency_key, request_hash = _require_idempotency(request)
    expected_key = f"gaming-legacy-outbox-resolution:{payload.local_action_id}"
    if idempotency_key != expected_key:
        raise BusinessRuleError(
            "Idempotency-Key must be derived from this saved action's immutable local ID."
        )
    captured_started_at, captured_stopped_at = _validated_legacy_outbox_times(
        captured_started_at=payload.captured_started_at,
        captured_stopped_at=payload.captured_stopped_at,
        server_now=datetime.now(timezone.utc),
    )
    resolution_request = _legacy_resolution_request_snapshot(
        payload=payload,
        captured_started_at=captured_started_at,
        captured_stopped_at=captured_stopped_at,
        tenant=tenant,
    )
    durable_receipt = await _durable_legacy_resolution_receipt(
        session,
        payload=payload,
        request_hash=request_hash,
        request_snapshot=resolution_request,
        tenant=tenant,
    )
    if durable_receipt is not None:
        return durable_receipt

    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return LegacyOutboxResolutionRead.model_validate(existing_response["body"])

    station = (
        await session.execute(
            select(Station).where(Station.id == payload.station_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if station.branch_id != tenant.branch_id:
        raise BusinessRuleError(
            "The saved gaming action belongs to a different branch. Select that branch first."
        )

    captured_shift = (
        await _legacy_resolution_shift(
            session,
            shift_id=payload.shift_id,
            station=station,
            captured_started_at=captured_started_at,
            tenant=tenant,
        )
        if payload.shift_id is not None
        else None
    )
    (
        recovered_session,
        recovery_proof,
        original_start_key,
        stop_recovery_outcome,
        original_session_snapshot,
    ) = (
        await _authoritative_legacy_server_session(
            session,
            payload=payload,
            station=station,
            shift=captured_shift,
            captured_started_at=captured_started_at,
            captured_stopped_at=captured_stopped_at,
            tenant=tenant,
        )
    )
    reference_order: Order | None = None
    reference_payment_total_minor: int | None = None
    reference_refunded_total_minor: int | None = None
    reference_service_line_id: UUID | None = None
    reference_service_type: str | None = None
    reference_service_line_total_minor: int | None = None
    manual_order_linked_now = False
    expected_session_amount_minor: int | None = None
    expected_captured_billable_minutes: int | None = None
    server_session_cancelled_as_no_play = False
    if recovered_session is not None:
        assert original_session_snapshot is not None
        if (
            payload.resolution == "server_session_recovered"
            and payload.package_id is None
            and recovered_session.status in {"active", "paused"}
            and recovered_session.order_id is None
            and captured_stopped_at is not None
            and captured_stopped_at < recovered_session.start_at.astimezone(timezone.utc)
        ):
            raise GamingLegacyStopOwnerReviewRequiredError(
                "The retained hourly Stop predates the authoritative server Start, so "
                "the server cannot invent a billable duration. Nothing was changed; "
                "the protected owner must verify a paid manual bill or confirm no play.",
                details={
                    "reason_code": "captured_stop_precedes_authoritative_start",
                    "local_action_id": str(payload.local_action_id),
                    "captured_stopped_at": captured_stopped_at.isoformat(),
                    "authoritative_started_at": recovered_session.start_at.isoformat(),
                },
            )
        if (
            payload.resolution == "confirmed_no_play"
            and recovered_session.status in {"active", "paused"}
        ):
            await _cancel_untouched_recovered_no_play(
                session,
                gaming_session=recovered_session,
                original_snapshot=original_session_snapshot,
                payload=payload,
                captured_stopped_at=captured_stopped_at,
                tenant=tenant,
            )
            server_session_cancelled_as_no_play = True
        if payload.reference_order_id is not None:
            if (
                recovered_session.order_id is not None
                and recovered_session.order_id != payload.reference_order_id
            ):
                raise ConflictError(
                    "The recovered server session is already linked to a different POS "
                    "order. Nothing was changed."
                )
            (
                reference_order,
                reference_payment_total_minor,
                reference_refunded_total_minor,
                reference_service_line_id,
                reference_service_type,
                reference_service_line_total_minor,
            ) = await _validated_legacy_manual_order(
                session,
                reference_order_id=payload.reference_order_id,
                station=station,
                local_action_id=payload.local_action_id,
                tenant=tenant,
                recovered_session_id=recovered_session.id,
            )
            if recovered_session.status == "cancelled":
                raise ConflictError(
                    "This server row is cancelled and cannot be "
                    "linked to the paid POS order. Nothing was linked or cleared."
                )
            if is_package_billed(recovered_session):
                if (
                    recovered_session.amount_minor is None
                    or int(recovered_session.amount_minor) <= 0
                ):
                    raise ConflictError(
                        "This package session has no positive authoritative bill and "
                        "cannot be linked to the paid POS order. Nothing was changed."
                    )
                expected_session_amount_minor = int(recovered_session.amount_minor)
            else:
                if captured_stopped_at is None:
                    raise ConflictError(
                        "Hourly manual-bill recovery requires the retained Stop time. "
                        "Nothing was linked or cleared."
                    )
                expected_rate = payload.expected_rate_per_hour_minor
                assert expected_rate is not None
                (
                    expected_captured_billable_minutes,
                    expected_session_amount_minor,
                ) = _legacy_hourly_captured_amount(
                    captured_started_at=captured_started_at,
                    captured_stopped_at=captured_stopped_at,
                    rate_per_hour_minor=expected_rate,
                )
            if recovered_session.order_id is None:
                recovered_session.order_id = reference_order.id
                recovered_session.sent_to_pos_by = tenant.user_id
                recovered_session.sent_to_pos_at = datetime.now(timezone.utc)
                manual_order_linked_now = True
                session.add(
                    AuditLog(
                        actor_user_id=tenant.user_id,
                        company_id=tenant.company_id,
                        action="gaming_legacy_server_session_manual_bill_link",
                        entity_type="GamingSession",
                        entity_id=str(recovered_session.id),
                        before={"order_id": None},
                        after={
                            "order_id": str(reference_order.id),
                            "local_action_id": str(payload.local_action_id),
                            "expected_session_amount_minor": (
                                expected_session_amount_minor
                            ),
                            "expected_captured_billable_minutes": (
                                expected_captured_billable_minutes
                            ),
                            "expected_rate_per_hour_minor": (
                                payload.expected_rate_per_hour_minor
                            ),
                            "expected_amount_source": (
                                "locked_package_amount"
                                if is_package_billed(recovered_session)
                                else "captured_hourly_duration"
                            ),
                            "reference_order_total_minor": int(
                                reference_order.total_minor
                            ),
                            "reference_payment_total_minor": (
                                reference_payment_total_minor
                            ),
                            "reference_refunded_total_minor": (
                                reference_refunded_total_minor
                            ),
                            "reference_net_paid_minor": (
                                reference_payment_total_minor
                                - reference_refunded_total_minor
                            ),
                            "reference_service_line_total_minor": (
                                reference_service_line_total_minor
                            ),
                            "order_variance_minor": int(reference_order.total_minor)
                            - int(expected_session_amount_minor),
                            "service_line_variance_minor": int(
                                reference_service_line_total_minor
                            )
                            - int(expected_session_amount_minor),
                            "reference_service_line_id": str(
                                reference_service_line_id
                            ),
                        },
                        terminal_id=tenant.terminal_id,
                        reason=payload.reason,
                    )
                )
                await session.flush()
        # A real server session always wins over an attempted no-play/manual
        # attestation. This receipt only links the client row back to existing
        # authoritative state; it does not stop, cancel, bill, or otherwise
        # mutate the session.
        authoritative = session_read(recovered_session)
        resolved_at = datetime.now(timezone.utc)
        resolution_receipt = {
            "local_action_id": str(payload.local_action_id),
            "station_id": str(station.id),
            "branch_id": str(tenant.branch_id),
            "terminal_id": str(tenant.terminal_id),
            "package_id": (
                str(payload.package_id) if payload.package_id is not None else None
            ),
            "resolution": "server_session_recovered",
            "reference_order_id": (
                str(reference_order.id) if reference_order is not None else None
            ),
            "server_session": authoritative.model_dump(mode="json"),
        }
        audit = AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_legacy_outbox_resolution",
            entity_type="GamingLegacyOutbox",
            entity_id=str(payload.local_action_id),
            before=None,
            after={
                "resolution_idempotency_key": idempotency_key,
                "resolution_request_hash": request_hash,
                "resolution_request": resolution_request,
                "resolution_receipt": resolution_receipt,
                "local_action_id": str(payload.local_action_id),
                "station_id": str(station.id),
                "shift_id": str(recovered_session.shift_id),
                "branch_id": str(tenant.branch_id),
                "terminal_id": str(tenant.terminal_id),
                "captured_started_at": captured_started_at.isoformat(),
                "captured_stopped_at": (
                    captured_stopped_at.isoformat()
                    if captured_stopped_at is not None
                    else None
                ),
                "package_id": (
                    str(payload.package_id) if payload.package_id is not None else None
                ),
                "requested_resolution": payload.resolution,
                "requested_reference_order_id": (
                    str(payload.reference_order_id)
                    if payload.reference_order_id is not None
                    else None
                ),
                "resolution": "server_session_recovered",
                "reference_order_id": (
                    str(reference_order.id) if reference_order is not None else None
                ),
                "manual_order_linked_now": manual_order_linked_now,
                "reference_order_status": (
                    reference_order.status if reference_order is not None else None
                ),
                "reference_order_invoice_no": (
                    reference_order.invoice_no if reference_order is not None else None
                ),
                "reference_order_total_minor": (
                    int(reference_order.total_minor)
                    if reference_order is not None
                    else None
                ),
                "reference_payment_total_minor": reference_payment_total_minor,
                "reference_refunded_total_minor": reference_refunded_total_minor,
                "reference_net_paid_minor": (
                    reference_payment_total_minor
                    - reference_refunded_total_minor
                    if reference_payment_total_minor is not None
                    and reference_refunded_total_minor is not None
                    else None
                ),
                "reference_service_line_id": (
                    str(reference_service_line_id)
                    if reference_service_line_id is not None
                    else None
                ),
                "reference_service_type": reference_service_type,
                "reference_service_line_total_minor": (
                    reference_service_line_total_minor
                ),
                "expected_session_amount_minor": (
                    expected_session_amount_minor
                    if expected_session_amount_minor is not None
                    else (
                        int(recovered_session.amount_minor)
                        if recovered_session.amount_minor is not None
                        else None
                    )
                ),
                "expected_captured_billable_minutes": (
                    expected_captured_billable_minutes
                ),
                "expected_rate_per_hour_minor": (
                    payload.expected_rate_per_hour_minor
                ),
                "expected_amount_source": (
                    "locked_package_amount"
                    if reference_order is not None
                    and is_package_billed(recovered_session)
                    else (
                        "captured_hourly_duration"
                        if reference_order is not None
                        else None
                    )
                ),
                "order_variance_minor": (
                    int(reference_order.total_minor)
                    - int(expected_session_amount_minor)
                    if reference_order is not None
                    and expected_session_amount_minor is not None
                    else None
                ),
                "service_line_variance_minor": (
                    int(reference_service_line_total_minor)
                    - int(expected_session_amount_minor)
                    if reference_service_line_total_minor is not None
                    and expected_session_amount_minor is not None
                    else None
                ),
                "server_session_id": str(recovered_session.id),
                "server_session_status": recovered_session.status,
                "server_session_order_id": (
                    str(recovered_session.order_id)
                    if recovered_session.order_id is not None
                    else None
                ),
                "server_session_start_at": recovered_session.start_at.isoformat(),
                "server_session_end_at": (
                    recovered_session.end_at.isoformat()
                    if recovered_session.end_at is not None
                    else None
                ),
                "captured_stop_outcome": stop_recovery_outcome,
                "server_session_cancelled_as_no_play": (
                    server_session_cancelled_as_no_play
                ),
                "original_start_idempotency_key": original_start_key,
                "recovery_proof": recovery_proof,
            },
            terminal_id=tenant.terminal_id,
            reason=payload.reason,
            created_at=resolved_at,
        )
        session.add(audit)
        await session.flush()
        response = LegacyOutboxResolutionRead.model_validate(
            {
                **resolution_receipt,
                "receipt_id": audit.id,
                "resolved_at": resolved_at,
            }
        )
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    if payload.resolution == "server_session_recovered":
        raise GamingLegacyServerSessionNotFoundError(
            "No authoritative server Start exists for this saved action. Nothing was "
            "changed; the protected owner may now record a verified manual bill or "
            "confirm no play.",
            details={
                "local_action_id": str(payload.local_action_id),
                "safe_to_choose_another_resolution": True,
            },
        )

    package_catalog_verified = False
    package: GamingPackage | None = None
    if payload.package_id is not None:
        package = await session.get(GamingPackage, payload.package_id)
        if package is not None:
            if (
                package.company_id != tenant.company_id
                or package.branch_id != tenant.branch_id
            ):
                raise NotFoundError("package not found")
            package_catalog_verified = True

    if payload.reference_order_id is not None:
        (
            reference_order,
            reference_payment_total_minor,
            reference_refunded_total_minor,
            reference_service_line_id,
            reference_service_type,
            reference_service_line_total_minor,
        ) = await _validated_legacy_manual_order(
            session,
            reference_order_id=payload.reference_order_id,
            station=station,
            local_action_id=payload.local_action_id,
            tenant=tenant,
        )
        if payload.package_id is None:
            assert captured_stopped_at is not None
            expected_rate = payload.expected_rate_per_hour_minor
            assert expected_rate is not None
            (
                expected_captured_billable_minutes,
                expected_session_amount_minor,
            ) = _legacy_hourly_captured_amount(
                captured_started_at=captured_started_at,
                captured_stopped_at=captured_stopped_at,
                rate_per_hour_minor=expected_rate,
            )
        elif package is not None:
            expected_session_amount_minor = int(package.price_minor)

    resolved_at = datetime.now(timezone.utc)
    resolution_receipt = {
        "local_action_id": str(payload.local_action_id),
        "station_id": str(station.id),
        "branch_id": str(tenant.branch_id),
        "terminal_id": str(tenant.terminal_id),
        "package_id": (
            str(payload.package_id) if payload.package_id is not None else None
        ),
        "resolution": payload.resolution,
        "reference_order_id": (
            str(reference_order.id) if reference_order is not None else None
        ),
        "server_session": None,
    }
    audit = AuditLog(
        actor_user_id=tenant.user_id,
        company_id=tenant.company_id,
        action="gaming_legacy_outbox_resolution",
        entity_type="GamingLegacyOutbox",
        entity_id=str(payload.local_action_id),
        before=None,
        after={
            "resolution_idempotency_key": idempotency_key,
            "resolution_request_hash": request_hash,
            "resolution_request": resolution_request,
            "resolution_receipt": resolution_receipt,
            "local_action_id": str(payload.local_action_id),
            "station_id": str(station.id),
            "shift_id": str(payload.shift_id) if payload.shift_id else None,
            "branch_id": str(tenant.branch_id),
            "terminal_id": str(tenant.terminal_id),
            "captured_started_at": captured_started_at.isoformat(),
            "captured_stopped_at": (
                captured_stopped_at.isoformat()
                if captured_stopped_at is not None
                else None
            ),
            "package_id": str(payload.package_id) if payload.package_id else None,
            "expected_rate_per_hour_minor": payload.expected_rate_per_hour_minor,
            "package_catalog_verified": package_catalog_verified,
            "resolution": payload.resolution,
            "reference_order_id": (
                str(reference_order.id) if reference_order is not None else None
            ),
            "reference_order_status": (
                reference_order.status if reference_order is not None else None
            ),
            "reference_order_invoice_no": (
                reference_order.invoice_no if reference_order is not None else None
            ),
            "reference_order_invoice_issued_at": (
                reference_order.invoice_issued_at.isoformat()
                if reference_order is not None
                else None
            ),
            "reference_order_total_minor": (
                int(reference_order.total_minor)
                if reference_order is not None
                else None
            ),
            "reference_payment_total_minor": reference_payment_total_minor,
            "reference_refunded_total_minor": reference_refunded_total_minor,
            "reference_net_paid_minor": (
                reference_payment_total_minor - reference_refunded_total_minor
                if reference_payment_total_minor is not None
                and reference_refunded_total_minor is not None
                else None
            ),
            "reference_service_line_id": (
                str(reference_service_line_id)
                if reference_service_line_id is not None
                else None
            ),
            "reference_service_type": reference_service_type,
            "expected_captured_billable_minutes": (
                expected_captured_billable_minutes
            ),
            "expected_session_amount_minor": expected_session_amount_minor,
            "expected_amount_source": (
                "captured_hourly_duration"
                if reference_order is not None and payload.package_id is None
                else (
                    "catalog_package_amount"
                    if reference_order is not None and package is not None
                    else None
                )
            ),
            "order_variance_minor": (
                int(reference_order.total_minor)
                - int(expected_session_amount_minor)
                if reference_order is not None
                and expected_session_amount_minor is not None
                else None
            ),
            "service_line_variance_minor": (
                int(reference_service_line_total_minor)
                - int(expected_session_amount_minor)
                if reference_service_line_total_minor is not None
                and expected_session_amount_minor is not None
                else None
            ),
        },
        terminal_id=tenant.terminal_id,
        reason=payload.reason,
        created_at=resolved_at,
    )
    session.add(audit)
    await session.flush()
    response = LegacyOutboxResolutionRead.model_validate(
        {
            **resolution_receipt,
            "receipt_id": audit.id,
            "resolved_at": resolved_at,
        }
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


async def _ensure_session_menu_item(
    session, *, company_id: UUID, station: Station
) -> MenuItem:
    """Get-or-create the hidden MenuItem used to bill a station's time-based
    sessions. Never shown in the normal POS menu grid (is_available=False) —
    price/tax are always taken from the session, not this item's base price.
    """
    # Serialize the first hidden-item/category creation per company. Without
    # this lock two stations of the same type can both pass the SELECT and race
    # into the company/SKU or company/category unique constraints.
    company = (
        await session.execute(
            select(Company).where(Company.id == company_id).with_for_update()
        )
    ).scalar_one_or_none()
    if company is None:
        raise NotFoundError("company not found")

    sku = f"SESSION-{station.type.upper()}"
    existing = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.company_id == company_id, MenuItem.sku == sku
            )
        )
    ).scalar_one_or_none()
    if existing:
        return existing

    category = (
        await session.execute(
            select(MenuCategory).where(
                MenuCategory.company_id == company_id,
                MenuCategory.name == "Session Billing",
            )
        )
    ).scalar_one_or_none()
    if not category:
        category = MenuCategory(
            id=uuid4(), company_id=company_id, name="Session Billing", sort_order=999,
        )
        session.add(category)
        await session.flush()

    item = MenuItem(
        id=uuid4(),
        company_id=company_id,
        category_id=category.id,
        sku=sku,
        name=_SESSION_ITEM_LABEL.get(station.type, f"{station.type.title()} Session"),
        type=_MENU_TYPE_FOR_STATION.get(station.type, "gaming"),
        base_price_minor=0,
        tax_rate=station.tax_rate,
        hsn_code=station.sac_code,
        price_includes_tax=station.rate_includes_tax,
        is_available=False,
    )
    session.add(item)
    await session.flush()
    return item


async def _reprice_session_order_for_customer(
    session,
    *,
    order: Order,
    company_id: UUID,
) -> None:
    """Apply POS membership pricing after the session/order link exists.

    The local import avoids a module cycle because the API composer imports
    Gaming before POS.  Keeping this bridge patchable also makes the gaming
    route's handoff contract testable without a database.
    """
    from app.api.v1.pos.router import _reprice_unpaid_order_for_customer

    await _reprice_unpaid_order_for_customer(
        session,
        order=order,
        company_id=company_id,
    )


async def _session_pos_description(session, gaming_session: GamingSession) -> str:
    """Return an operator-readable, historically honest POS line note."""
    played_minutes = int(gaming_session.billable_minutes or 0)
    if resolved_billing_mode(gaming_session) == "legacy_ambiguous":
        return f"Legacy session · billing mode unverified · {played_minutes} min played"
    package_id = getattr(gaming_session, "package_id", None)
    if not is_package_billed(gaming_session):
        return (
            f"{played_minutes} min @ "
            f"{gaming_session.rate_per_hour_minor / 100:.2f}/hr"
        )

    base_package = await session.get(GamingPackage, package_id) if package_id else None
    if base_package:
        base_label = base_package.name
    else:
        variant = getattr(gaming_session, "package_variant_snapshot", None)
        duration = getattr(gaming_session, "package_duration_minutes_snapshot", None)
        locked_details = " · ".join(
            part
            for part in (
                variant.title() if variant else None,
                f"{int(duration)} min" if duration is not None else None,
            )
            if part
        )
        base_label = f"Package session ({locked_details})" if locked_details else "Package session"
    parts = [base_label]
    extension_rows = (
        await session.execute(
            select(GamingSessionExtension)
            .where(
                GamingSessionExtension.company_id == gaming_session.company_id,
                GamingSessionExtension.gaming_session_id == gaming_session.id,
            )
            .order_by(
                GamingSessionExtension.created_at,
                GamingSessionExtension.id,
            )
        )
    ).scalars().all()
    if extension_rows:
        grouped: dict[tuple[str, int, int], int] = {}
        for row in extension_rows:
            key = (row.package_name, int(row.duration_minutes), int(row.total_minor))
            grouped[key] = grouped.get(key, 0) + 1
        summaries: list[str] = []
        for (name, duration, total_minor), count in list(grouped.items())[:3]:
            prefix = f"{count}× " if count > 1 else ""
            summaries.append(
                f"{prefix}{name} (+{count * duration} min, "
                f"{count * total_minor / 100:.2f})"
            )
        if len(grouped) > 3:
            summaries.append(f"+{len(grouped) - 3} more extension types")
        parts.extend(summaries)
    else:
        # Pre-0038 sessions have no item ledger. Preserve an honest aggregate
        # derived from the locked timer without inventing package names/prices.
        timer_minutes = int(gaming_session.timer_minutes or 0)
        base_minutes = int(
            getattr(gaming_session, "package_duration_minutes_snapshot", None)
            or (base_package.duration_minutes if base_package else timer_minutes)
        )
        extension_minutes = max(0, timer_minutes - base_minutes)
        if extension_minutes:
            parts.append(f"{extension_minutes} min paid extension (legacy aggregate)")
    parts.append(f"{played_minutes} min played")
    extra_controllers = int(getattr(gaming_session, "extra_controllers", 0) or 0)
    if extra_controllers:
        suffix = "controller" if extra_controllers == 1 else "controllers"
        parts.append(f"{extra_controllers} extra {suffix}")
    return " · ".join(parts)


async def _create_session_pos_order(
    session,
    *,
    gaming_session: GamingSession,
    station: Station,
    target_shift: Shift,
    company_id: UUID,
    opened_by: UUID,
) -> Order:
    """Create one held POS order without rewriting session provenance."""
    item = await _ensure_session_menu_item(
        session,
        company_id=company_id,
        station=station,
    )

    tax_rate = Decimal(
        str(
            gaming_session.tax_rate
            if gaming_session.tax_rate is not None
            else station.tax_rate
        )
    )
    rate_includes_tax = (
        gaming_session.rate_includes_tax
        if gaming_session.rate_includes_tax is not None
        else station.rate_includes_tax
    )
    amount_minor = _require_repaired_ended_amount(gaming_session)
    priced = await OrderPricingService(session).price_time_based_line(
        company_id=company_id,
        branch_id=target_shift.branch_id,
        amount_minor=amount_minor,
        tax_rate=tax_rate,
        rate_includes_tax=rate_includes_tax,
        customer_phone=gaming_session.customer_phone,
        item_type=_MENU_TYPE_FOR_STATION.get(station.type, "gaming"),
    )
    order_total_minor, round_off_minor = _round_to_rupee(priced.total_minor)

    now = datetime.now(timezone.utc)
    note = await _session_pos_description(session, gaming_session)
    order = Order(
        id=uuid4(),
        company_id=company_id,
        branch_id=target_shift.branch_id,
        terminal_id=target_shift.terminal_id,
        shift_id=target_shift.id,
        opened_by=opened_by,
        table_id=None,
        type="session",
        status="held",
        opened_at=now,
        held_at=now,
        subtotal_minor=priced.taxable_minor,
        cgst_minor=priced.cgst_minor,
        sgst_minor=priced.sgst_minor,
        igst_minor=priced.igst_minor,
        cess_minor=0,
        discount_minor=priced.discount_minor,
        tax_minor=priced.cgst_minor + priced.sgst_minor + priced.igst_minor,
        round_off_minor=round_off_minor,
        total_minor=order_total_minor,
        customer_name=gaming_session.customer_name,
        customer_phone=gaming_session.customer_phone,
        notes=f"{station.name} — {note}",
    )
    session.add(order)
    await session.flush()

    # Link the source row before any staged OrderLine insert. Migration 0055's
    # snapshot trigger resolves an add-on through GamingSession.order_id; both
    # the link and every copied line remain atomic with this transaction.
    gaming_session.order_id = order.id
    gaming_session.sent_to_pos_by = opened_by
    gaming_session.sent_to_pos_at = now
    await session.flush()

    gaming_line = OrderLine(
        id=uuid4(),
        order_id=order.id,
        menu_item_id=item.id,
        menu_item_name_snapshot=item.name,
        menu_item_type_snapshot=item.type,
        qty=1,
        unit_price_minor=priced.total_minor,
        line_total_minor=priced.total_minor,
        discount_minor=priced.discount_minor,
        hsn_or_sac=(
            gaming_session.sac_code or station.sac_code or item.hsn_code or ""
        ),
        tax_rate=float(tax_rate),
        taxable_value_minor=priced.taxable_minor,
        cgst_minor=priced.cgst_minor,
        sgst_minor=priced.sgst_minor,
        igst_minor=priced.igst_minor,
        cess_minor=0,
        note=note,
    )
    session.add(gaming_line)

    active_addons = (
        await session.execute(
            select(GamingSessionAddon)
            .where(
                GamingSessionAddon.company_id == company_id,
                GamingSessionAddon.gaming_session_id == gaming_session.id,
                GamingSessionAddon.voided_at.is_(None),
            )
            .order_by(GamingSessionAddon.created_at, GamingSessionAddon.id)
            .with_for_update()
        )
    ).scalars().all()
    copied_addons: list[tuple[GamingSessionAddon, OrderLine]] = []
    for addon in active_addons:
        copied_line = OrderLine(
            id=uuid4(),
            order_id=order.id,
            client_line_id=addon.client_line_id,
            menu_item_id=addon.menu_item_id,
            menu_item_name_snapshot=addon.menu_item_name_snapshot,
            menu_item_type_snapshot=addon.menu_item_type_snapshot,
            variant_id=addon.variant_id,
            variant_snapshot=addon.variant_snapshot,
            modifiers=addon.modifiers,
            qty=addon.qty,
            unit_price_minor=addon.unit_price_minor,
            line_total_minor=addon.line_total_minor,
            discount_minor=addon.discount_minor,
            hsn_or_sac=addon.hsn_or_sac,
            tax_rate=addon.tax_rate,
            taxable_value_minor=addon.taxable_value_minor,
            cgst_minor=addon.cgst_minor,
            sgst_minor=addon.sgst_minor,
            igst_minor=addon.igst_minor,
            cess_minor=addon.cess_minor,
            note=addon.note,
            # Gaming-centre snacks are handed directly to the customer. They
            # remain real POS/inventory lines but must never become live KDS
            # work when the combined bill is paid later.
            kitchen_status="served",
            kitchen_released_at=addon.created_at,
            kitchen_round_no=1,
            kitchen_served_at=addon.created_at,
            created_at=addon.created_at,
        )
        session.add(copied_line)
        copied_addons.append((addon, copied_line))
    await session.flush()

    if order.customer_phone:
        await _reprice_session_order_for_customer(
            session,
            order=order,
            company_id=company_id,
        )
        # Membership allowance reservation is owned by the normal POS bridge,
        # but an add-on's add-time server price is immutable financial intent.
        # Restore those exact staged line amounts if membership state changed
        # between consumption and handoff.
        for addon, copied_line in copied_addons:
            copied_line.unit_price_minor = addon.unit_price_minor
            copied_line.line_total_minor = addon.line_total_minor
            copied_line.discount_minor = addon.discount_minor
            copied_line.taxable_value_minor = addon.taxable_value_minor
            copied_line.cgst_minor = addon.cgst_minor
            copied_line.sgst_minor = addon.sgst_minor
            copied_line.igst_minor = addon.igst_minor
            copied_line.cess_minor = addon.cess_minor

    if copied_addons:
        from app.api.v1.pos.router import _reaggregate_active_order_lines

        await _reaggregate_active_order_lines(
            session,
            order=order,
            company_id=company_id,
        )
    await session.flush()
    return order


@router.get(
    "/sessions/{session_id}/pos-target-shifts",
    response_model=list[PosTargetShiftRead],
)
async def list_session_pos_target_shifts(
    session_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write", "pos.read")),
) -> list[PosTargetShiftRead]:
    """List explicit, currently eligible POS destinations for one session.

    The source gaming shift remains the session's provenance.  A destination
    is another open terminal shift in the same company and branch; listing it
    does not make held orders branch-wide or grant checkout authority there.
    """
    if tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the gaming terminal used by this device before choosing a POS till."
        )
    if tenant.branch_id is None:
        raise BusinessRuleError(
            "This account has no branch assigned. Assign one before choosing a POS till."
        )

    gs = await session.get(GamingSession, session_id)
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if gs.order_id is not None:
        raise BusinessRuleError("This session was already sent to POS.")
    if gs.status != "ended":
        raise BusinessRuleError("Stop the session before choosing a POS till.")
    await _require_session_pos_eligible(session, gaming_session=gs)

    source_shift = await session.get(Shift, gs.shift_id)
    source_shift = require_open_operational_shift(
        source_shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="choosing a POS till for this gaming session",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    await _require_terminal_purpose(
        session,
        tenant=tenant,
        allowed=_GAMING_SOURCE_TERMINAL_PURPOSES,
        invalid_message=(
            "This terminal is not configured to host gaming sessions. "
            "Select the Gaming Area terminal."
        ),
    )

    rows = (
        await session.execute(
            select(Shift, Terminal, User)
            .join(
                Terminal,
                (Terminal.id == Shift.terminal_id)
                & (Terminal.branch_id == Shift.branch_id),
            )
            .join(User, User.id == Shift.opened_by)
            .where(
                Shift.company_id == tenant.company_id,
                Shift.branch_id == tenant.branch_id,
                Shift.status == "open",
                Shift.id != source_shift.id,
                Shift.terminal_id != source_shift.terminal_id,
                Terminal.is_active.is_(True),
                Terminal.purpose.in_(_POS_DESTINATION_TERMINAL_PURPOSES),
                User.company_id == tenant.company_id,
            )
            .order_by(Terminal.name, Shift.opened_at, Shift.id)
        )
    ).all()
    return [
        PosTargetShiftRead(
            shift_id=target_shift.id,
            terminal_id=terminal.id,
            terminal_name=terminal.name,
            opened_by=opener.id,
            opened_by_name=opener.name,
            opened_at=target_shift.opened_at,
        )
        for target_shift, terminal, opener in rows
    ]


@router.post(
    "/sessions/{session_id}/handoff-to-pos",
    response_model=SessionPosHandoffRead,
    status_code=status.HTTP_201_CREATED,
)
async def handoff_session_to_pos(
    session_id: UUID,
    payload: SessionPosHandoff,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write", "pos.read")),
) -> SessionPosHandoffRead:
    """Create a held order on an explicitly selected POS terminal shift.

    This is the normal open-source-shift path for a gaming area and a café POS
    using separate logical terminals.  The gaming session keeps its original
    shift; only the held order belongs to the destination drawer.
    """
    if tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the gaming terminal used by this device before sending to POS."
        )
    if tenant.branch_id is None:
        raise BusinessRuleError(
            "This account has no branch assigned. Assign one before sending to POS."
        )

    gs = (
        await session.execute(
            select(GamingSession)
            .where(GamingSession.id == session_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")

    source_shift = await session.get(Shift, gs.shift_id)
    source_shift = require_operational_shift_scope(
        source_shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending this gaming session to another POS terminal",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    if gs.order_id is not None:
        existing_order = await session.get(Order, gs.order_id)
        if existing_order is None or existing_order.company_id != tenant.company_id:
            raise NotFoundError("Order not found for this company.")
        if existing_order.branch_id != tenant.branch_id:
            raise BusinessRuleError("Order belongs to a different branch.")
        if existing_order.shift_id != payload.target_shift_id:
            raise ConflictError(
                "This session was already sent to a different POS shift. "
                "Refresh Gaming and open the linked held order."
            )
        return SessionPosHandoffRead(
            order_id=existing_order.id,
            amount_minor=int(existing_order.total_minor),
            source_shift_id=source_shift.id,
            source_terminal_id=source_shift.terminal_id,
            target_shift_id=existing_order.shift_id,
            target_terminal_id=existing_order.terminal_id,
            already_linked=True,
        )

    if gs.status != "ended":
        raise BusinessRuleError("Stop the session before sending it to POS.")
    await _require_session_pos_eligible(session, gaming_session=gs)
    await _require_terminal_purpose(
        session,
        tenant=tenant,
        allowed=_GAMING_SOURCE_TERMINAL_PURPOSES,
        invalid_message=(
            "This terminal is not configured to host gaming sessions. "
            "Select the Gaming Area terminal."
        ),
    )

    # Lock both shifts in UUID order. Two simultaneous A->B and B->A handoffs
    # must never deadlock by taking the same pair in opposite orders.
    shift_ids = sorted({source_shift.id, payload.target_shift_id}, key=str)
    locked_shifts = (
        await session.execute(
            select(Shift)
            .where(Shift.id.in_(shift_ids))
            .order_by(Shift.id)
            .with_for_update()
            # ``source_shift`` was read above for tenant scoping. Refresh it
            # under the lock so a concurrent close cannot leave this request
            # validating a stale in-memory ``status == open`` value.
            .execution_options(populate_existing=True)
        )
    ).scalars().all()
    shifts_by_id = {shift.id: shift for shift in locked_shifts}
    source_shift = require_open_operational_shift(
        shifts_by_id.get(gs.shift_id),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="handing this gaming session to POS",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    target_shift = shifts_by_id.get(payload.target_shift_id)
    if target_shift is None or target_shift.company_id != tenant.company_id:
        raise NotFoundError("Target POS shift not found for this company.")
    if target_shift.branch_id != tenant.branch_id:
        raise BusinessRuleError("Target POS shift belongs to a different branch.")
    if target_shift.branch_id != station.branch_id:
        raise BusinessRuleError(
            "Target POS shift branch does not match the gaming station branch."
        )
    if target_shift.status != "open":
        raise BusinessRuleError(
            "The selected POS shift is not open. Refresh the till list and choose an open shift."
        )
    if target_shift.id == source_shift.id:
        raise BusinessRuleError(
            "The selected shift is this gaming terminal's shift. Use Send to POS normally."
        )
    if target_shift.terminal_id == source_shift.terminal_id:
        raise BusinessRuleError(
            "Choose a shift on another terminal for a cross-terminal handoff."
        )

    target_terminal = await session.get(Terminal, target_shift.terminal_id)
    if target_terminal is None or target_terminal.branch_id != target_shift.branch_id:
        raise BusinessRuleError(
            "The selected POS terminal is not valid for this shop. Refresh the till list."
        )
    if getattr(target_terminal, "is_active", True) is False:
        raise BusinessRuleError(
            "The selected POS workspace is archived. Refresh the till list and use "
            "the active Hybrid workspace."
        )
    if target_terminal.purpose not in _POS_DESTINATION_TERMINAL_PURPOSES:
        raise BusinessRuleError(
            "The selected terminal cannot receive POS bills. Choose an open Cafe POS "
            "or Hybrid terminal shift."
        )

    source_shift_id = source_shift.id
    source_terminal_id = source_shift.terminal_id
    order = await _create_session_pos_order(
        session,
        gaming_session=gs,
        station=station,
        target_shift=target_shift,
        company_id=tenant.company_id,
        opened_by=tenant.user_id,
    )
    audit_reason = "Explicit cross-terminal Gaming to POS handoff"
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_session_handoff_to_pos",
            entity_type="GamingSession",
            entity_id=str(gs.id),
            before={
                "source_shift_id": str(source_shift_id),
                "source_terminal_id": str(source_terminal_id),
                "order_id": None,
            },
            after={
                "source_shift_id": str(source_shift_id),
                "source_terminal_id": str(source_terminal_id),
                "target_shift_id": str(target_shift.id),
                "target_terminal_id": str(target_shift.terminal_id),
                "order_id": str(order.id),
            },
            terminal_id=tenant.terminal_id,
            reason=audit_reason,
        )
    )
    await session.flush()
    return SessionPosHandoffRead(
        order_id=order.id,
        amount_minor=int(order.total_minor),
        source_shift_id=source_shift_id,
        source_terminal_id=source_terminal_id,
        target_shift_id=target_shift.id,
        target_terminal_id=target_shift.terminal_id,
        already_linked=False,
    )


@router.post("/sessions/{session_id}/send-to-pos", status_code=status.HTTP_201_CREATED)
async def send_session_to_pos(
    session_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> dict:
    """Turn a stopped session's computed amount into a held POS order,
    ready for a cashier to find and bill — replacing the old manual
    re-entry workaround."""
    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")

    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")

    if gs.order_id is not None:
        existing_order = await session.get(Order, gs.order_id)
        existing_order = require_operational_order(
            existing_order,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="resuming a session sent to POS",
        )
        return {
            "order_id": str(existing_order.id),
            "amount_minor": int(existing_order.total_minor),
        }
    if gs.status != "ended":
        raise BusinessRuleError("stop the session before sending it to POS")
    await _require_session_pos_eligible(session, gaming_session=gs)

    shift = (
        await session.execute(
            select(Shift).where(Shift.id == gs.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    # A closed source shift is precisely the recoverable condition. Detect it
    # after tenant/branch validation but before comparing terminals: the
    # protected owner may intentionally recover an old terminal's session onto
    # the current operational terminal through the dedicated endpoint.
    if shift is None or shift.company_id != tenant.company_id:
        raise NotFoundError("Shift not found for this company.")
    if shift.branch_id != station.branch_id:
        raise BusinessRuleError(
            "Shift branch does not match the gaming station branch."
        )
    if shift.branch_id != tenant.branch_id:
        raise BusinessRuleError("Shift belongs to a different branch.")
    if shift.status != "open":
        raise GamingSourceShiftClosedError(
            "The session's original shift is closed. Ask the protected owner to "
            "reconcile this stopped session to the current terminal's open shift."
        )
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending a session to POS",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )
    await _require_terminal_purpose(
        session,
        tenant=tenant,
        allowed=_POS_DESTINATION_TERMINAL_PURPOSES,
        invalid_message=(
            "This terminal is configured for Gaming only. Use the cross-terminal "
            "Send to POS handoff and choose an open Cafe POS shift."
        ),
    )

    order = await _create_session_pos_order(
        session,
        gaming_session=gs,
        station=station,
        target_shift=shift,
        company_id=tenant.company_id,
        opened_by=tenant.user_id,
    )
    return {"order_id": str(order.id), "amount_minor": int(order.total_minor)}


@router.post(
    "/sessions/{session_id}/reconcile-to-pos",
    response_model=SessionReconcileToPosRead,
    status_code=status.HTTP_201_CREATED,
)
async def reconcile_session_to_pos(
    session_id: UUID,
    payload: SessionReconcileToPos,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.audit.read")),
) -> SessionReconcileToPosRead:
    """Move an ended bill off a closed source shift without rewriting history."""
    if not tenant.audit_access:
        raise ForbiddenError(
            "Only the protected owner can reconcile a stopped session to another shift."
        )
    if tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the POS terminal used by this device before reconciling a session."
        )
    if tenant.branch_id is None:
        raise BusinessRuleError(
            "This account has no branch assigned. Assign one before reconciling a session."
        )

    gs = (
        await session.execute(
            select(GamingSession).where(GamingSession.id == session_id).with_for_update()
        )
    ).scalar_one_or_none()
    if not gs or gs.company_id != tenant.company_id:
        raise NotFoundError("session not found")

    station = await session.get(Station, gs.station_id)
    if not station or station.company_id != tenant.company_id:
        raise NotFoundError("station not found")
    if station.branch_id != tenant.branch_id:
        raise BusinessRuleError(
            "The gaming station belongs to a different branch than this terminal."
        )

    if gs.order_id is not None:
        existing_order = await session.get(Order, gs.order_id)
        existing_order = require_operational_order(
            existing_order,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="resuming a reconciled session sent to POS",
        )
        return SessionReconcileToPosRead(
            order_id=existing_order.id,
            amount_minor=int(existing_order.total_minor),
            source_shift_id=gs.shift_id,
            target_shift_id=existing_order.shift_id,
            already_linked=True,
        )
    if gs.status != "ended":
        raise BusinessRuleError(
            "Only an ended session can be reconciled. Stop the session first."
        )
    await _require_session_pos_eligible(session, gaming_session=gs)

    source_shift = await session.get(Shift, gs.shift_id)
    if not source_shift or source_shift.company_id != tenant.company_id:
        raise NotFoundError("The session's original shift was not found for this company.")
    if source_shift.branch_id != station.branch_id:
        raise BusinessRuleError(
            "The session's original shift does not match the gaming station branch."
        )
    if source_shift.status == "open":
        raise BusinessRuleError(
            "The session's original shift is still open. Use Send to POS normally."
        )
    if source_shift.status not in {"closed", "reconciled"}:
        raise BusinessRuleError(
            "The session's original shift has an unsupported status and cannot be reconciled."
        )

    target_shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.target_shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    target_shift = require_open_operational_shift(
        target_shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="reconciling a stopped session to POS",
        resource_branch_id=station.branch_id,
        resource_name="gaming station",
    )

    source_shift_id = gs.shift_id
    order = await _create_session_pos_order(
        session,
        gaming_session=gs,
        station=station,
        target_shift=target_shift,
        company_id=tenant.company_id,
        opened_by=tenant.user_id,
    )
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="gaming_session_reconcile_to_pos",
            entity_type="GamingSession",
            entity_id=str(gs.id),
            before={
                "source_shift_id": str(source_shift_id),
                "order_id": None,
            },
            after={
                "source_shift_id": str(source_shift_id),
                "target_shift_id": str(target_shift.id),
                "order_id": str(order.id),
                "reason": payload.reason,
            },
            terminal_id=tenant.terminal_id,
            reason=payload.reason,
        )
    )
    await session.flush()
    return SessionReconcileToPosRead(
        order_id=order.id,
        amount_minor=int(order.total_minor),
        source_shift_id=source_shift_id,
        target_shift_id=target_shift.id,
        already_linked=False,
    )


def _booking_read(bk: GamingBooking, *, station_code: str) -> BookingRead:
    return BookingRead(
        id=bk.id, station_id=bk.station_id, station_code=station_code,
        starts_at=bk.starts_at, ends_at=bk.ends_at, guest_name=bk.guest_name,
        contact=bk.contact, party_size=bk.party_size, deposit_minor=bk.deposit_minor,
        status=bk.status, created_at=bk.created_at,
    )


@router.get("/bookings", response_model=list[BookingRead])
async def list_bookings(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    status_filter: list[str] | None = Query(default=None, alias="status"),
    limit: int = Query(default=200, ge=1, le=500),
) -> list[BookingRead]:
    """List station bookings for the current branch, soonest first.

    Defaults to upcoming only (starts_at from today onward, local time) so a
    booking made months ago doesn't clutter the staff view forever — pass an
    explicit `from_date` to look further back. `to_date` is optional and
    unbounded when omitted.
    """
    branch_id = _current_gaming_branch_id(tenant)
    stmt = (
        select(GamingBooking, Station.code)
        .join(Station, Station.id == GamingBooking.station_id)
        .where(
            Station.company_id == tenant.company_id,
            Station.branch_id == branch_id,
        )
    )
    if status_filter:
        stmt = stmt.where(GamingBooking.status.in_(status_filter))
    timezone_name = await company_timezone(session, tenant.company_id)
    f_d = from_date or local_today(timezone_name)
    f_dt, _ = local_date_bounds_utc(f_d, f_d, timezone_name)
    stmt = stmt.where(GamingBooking.starts_at >= f_dt)
    if to_date:
        _, t_dt = local_date_bounds_utc(to_date, to_date, timezone_name)
        stmt = stmt.where(GamingBooking.starts_at < t_dt)
    stmt = stmt.order_by(GamingBooking.starts_at.asc()).limit(limit)
    rows = (await session.execute(stmt)).all()
    return [_booking_read(bk, station_code=code) for bk, code in rows]


@router.post("/bookings", status_code=status.HTTP_201_CREATED)
async def create_booking(
    payload: BookingCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> dict:
    if payload.ends_at <= payload.starts_at:
        raise BusinessRuleError("ends_at must be after starts_at")
    branch_id = _current_gaming_branch_id(tenant)
    station = await _operational_station(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        station_id=payload.station_id,
        for_update=True,
    )
    if not station:
        raise NotFoundError("station not found")
    if not station.is_active:
        raise BusinessRuleError("station is not active")
    bk = GamingBooking(
        id=uuid4(),
        station_id=payload.station_id,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        guest_name=payload.guest_name,
        contact=payload.contact,
        party_size=payload.party_size,
        deposit_minor=payload.deposit_minor,
        status="held",
        created_by=tenant.user_id,
    )
    session.add(bk)
    try:
        # The EXCLUDE constraint at the DB level rejects overlapping bookings.
        # Flushing here (rather than letting it surface at the implicit
        # request-boundary commit) lets us turn that into a clean 409 instead
        # of an unhandled IntegrityError reaching the generic 500 handler.
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        raise ConflictError(
            "This station is already booked for that time slot."
        ) from exc
    return {"id": str(bk.id), "status": bk.status}


@router.patch("/bookings/{booking_id}/status", response_model=BookingRead)
async def update_booking_status(
    booking_id: UUID,
    payload: BookingStatusUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("gaming.write")),
) -> BookingRead:
    branch_id = _current_gaming_branch_id(tenant)
    bk = await _tenant_booking(
        session,
        company_id=tenant.company_id,
        branch_id=branch_id,
        booking_id=booking_id,
        for_update=True,
    )
    if not bk:
        raise NotFoundError("booking not found")
    allowed = _BOOKING_TRANSITIONS.get(bk.status, set())
    if payload.status not in allowed:
        raise ConflictError(
            f"cannot change booking from '{bk.status}' to '{payload.status}'"
        )
    bk.status = payload.status
    await session.flush()
    station_code = (
        await session.execute(select(Station.code).where(Station.id == bk.station_id))
    ).scalar_one()
    return _booking_read(bk, station_code=station_code)
