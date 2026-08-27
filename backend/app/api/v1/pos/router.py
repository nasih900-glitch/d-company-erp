"""POS endpoints — orders, payments, refunds, shifts.

Skeleton: validation + DB scaffolding wired. The full order pipeline
(recipe deduction, journal posting, receipt rendering) is deferred to
the POS deep build.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal
from typing import Annotated, Literal
from uuid import UUID, uuid4

from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    Header,
    Query,
    Request,
    Response,
    status,
)
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)
from sqlalchemy import func, or_, select
from sqlalchemy import inspect as sa_inspect

from app.core.db import SessionDep
from app.core.errors import (
    BusinessRuleError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
)
from app.core.idempotency import check_or_reserve, store_response
from app.core.logging import get_logger
from app.core.permissions import require_permission, requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.events.bus import get_event_bus
from app.events.events import OrderPaid
from app.models import (
    Branch,
    Company,
    Customer,
    CustomerMembership,
    CustomerSpendReconciliation,
    Floor,
    GamingSession,
    MembershipPayment,
    MembershipPaymentRequest,
    MembershipPaymentRequestResolution,
    MembershipRefund,
    MembershipRefundAttemptRecovery,
    MembershipRefundAttemptResolution,
    MembershipRefundResolution,
    MembershipRefundSettlement,
    MembershipTier,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    PointsRedemption,
    PosRefundCashHandoff,
    PosRefundCashHandoffCompletion,
    PosRefundEvidenceReconciliation,
    PosRefundProviderPayoutStart,
    PosRefundProviderSettlement,
    PosRefundRequest,
    PosRefundWithdrawal,
    Refund,
    Shift,
    Station,
    Table,
    Terminal,
    User,
)
from app.schemas.pos import OrderModifierSnapshotRead, OrderVariantSnapshotRead
from app.services.gaming.billing_mode import is_package_billed
from app.services.inventory.deduction import deduct_for_order
from app.services.pos.checkout_claims import (
    acquire_checkout_claim,
    consume_checkout_claim,
    guard_checkout_relevant_mutation,
    release_checkout_claim,
    validate_checkout_claim,
)
from app.services.pos.membership_benefits import (
    applied_benefits_for_order,
    consume_membership_benefits,
    reserve_membership_benefits,
)
from app.services.pos.order_validation import require_operational_order
from app.services.pos.points import (
    consume_points_redemption,
    minor_to_points,
    points_redeemed_for_order,
    rank_up_bonus_points,
    reserve_catalog_reward_redemption,
    reserve_points_redemption,
)
from app.services.pos.pricing import (
    InvoiceNumberService,
    LineRequest,
    ModifierSelection,
    OrderPricingService,
    _round_to_rupee,
    apply_manual_discount,
    apply_points_redemption,
    gaming_minutes_allowance_minor,
)
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_operational_shift_scope,
    require_shift_opener,
)

log = get_logger(__name__)

router = APIRouter()
_KITCHEN_ITEM_TYPES = {"food", "drink", "dessert"}
_LEGACY_VOID_REASONS = {
    "Legacy cancellation - reason not recorded",
    "Legacy cancellation - actor and reason not recorded",
}
_KITCHEN_LINE_STATUS_BY_ORDER_STATE = {
    "received": "queued",
    "preparing": "cooking",
    "ready": "ready",
    "served": "served",
}


# ----------- schemas -----------
class OrderModifierSelectionCreate(BaseModel):
    """Untrusted client selection; names and prices are resolved server-side."""

    model_config = ConfigDict(extra="forbid")

    modifier_id: UUID
    qty: int = Field(default=1, strict=True, gt=0)


class OrderLineCreate(BaseModel):
    client_line_id: UUID | None = None
    menu_item_id: UUID
    variant_id: UUID | None = None
    qty: int = Field(gt=0)
    modifiers: list[OrderModifierSelectionCreate] | None = Field(
        default=None,
        max_length=100,
    )
    note: str | None = Field(default=None, max_length=500)


class OrderCreate(BaseModel):
    type: Literal["dine_in", "takeaway", "delivery", "session"]
    table_id: UUID | None = None
    shift_id: UUID
    lines: list[OrderLineCreate] = Field(min_length=1, max_length=100)
    delivery_via: Literal["inhouse", "zomato", "swiggy", "ubereats", "other_aggregator"] | None = None
    customer_name: str | None = Field(default=None, max_length=200)
    customer_phone: str | None = Field(default=None, max_length=20)
    customer_gstin: str | None = Field(default=None, max_length=15)
    customer_address: str | None = Field(default=None, max_length=500)
    customer_state_code: str | None = Field(default=None, pattern=r"^\d{2}$")
    place_of_supply_state_code: str | None = Field(default=None, pattern=r"^\d{2}$")
    notes: str | None = Field(default=None, max_length=500)

    @field_validator("customer_name", "customer_phone")
    @classmethod
    def normalize_optional_customer_identity(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None


class OrderLineRead(BaseModel):
    id: UUID
    client_line_id: UUID | None = None
    menu_item_id: UUID
    variant_id: UUID | None = None
    variant_snapshot: OrderVariantSnapshotRead | None = None
    modifiers: list[OrderModifierSnapshotRead] | None = None
    name: str
    sku: str
    hsn_or_sac: str
    qty: float
    unit_price_minor: int
    line_total_minor: int
    taxable_value_minor: int
    tax_rate: float
    cgst_minor: int
    sgst_minor: int
    igst_minor: int
    note: str | None = None
    kitchen_status: str
    kitchen_released_at: datetime | None = None
    kitchen_round_no: int | None = None
    voided_at: datetime | None = None
    voided_by: UUID | None = None
    void_reason: str | None = None
    kitchen_void_acknowledged_at: datetime | None = None
    kitchen_void_acknowledged_by: UUID | None = None


class OrderRead(BaseModel):
    id: UUID
    invoice_no: str | None = None
    fiscal_year: str | None = None
    status: str
    type: str
    table_id: UUID | None = None
    source_label: str | None = None
    subtotal_minor: int
    discount_minor: int
    manual_discount_minor: int = 0
    points_redeemed_minor: int = 0
    points_redeemed: int = 0
    cgst_minor: int = 0
    sgst_minor: int = 0
    igst_minor: int = 0
    cess_minor: int = 0
    tax_minor: int
    round_off_minor: int = 0
    tip_minor: int = 0
    total_minor: int
    paid_minor: int = 0
    due_minor: int = 0
    free_gaming_minutes_applied: int = 0
    free_hookah_count_applied: int = 0
    delivery_via: str | None = None
    place_of_supply_state_code: str | None = None
    customer_name: str | None = None
    customer_phone: str | None = None
    customer_gstin: str | None = None
    customer_state_code: str | None = None
    opened_at: datetime
    closed_at: datetime | None = None
    invoice_issued_at: datetime | None = None
    held_at: datetime | None = None
    checkout_version: int = 1
    lines: list[OrderLineRead] = Field(default_factory=list)
    voided_lines: list[OrderLineRead] = Field(default_factory=list)


class ReceiptBusinessRead(BaseModel):
    """Least-privilege business identity needed to render a POS receipt."""

    brand_name: str
    supplier_name: str
    branch_name: str
    address: str | None
    gstin: str | None
    gst_registration_type: str
    is_composition: bool
    fssai_license_no: str | None
    trade_license_no: str | None
    state_code: str | None
    timezone: str
    upi_vpa: str | None


def _receipt_business_read(company: Company, branch: Branch) -> ReceiptBusinessRead:
    """Project tenant settings onto the deliberately narrow receipt contract."""

    brand_name = company.name.strip()
    legal_name = (company.legal_name or "").strip()
    return ReceiptBusinessRead(
        brand_name=brand_name,
        supplier_name=legal_name or brand_name,
        branch_name=branch.name.strip(),
        address=branch.address,
        gstin=branch.branch_gstin or company.gstin,
        gst_registration_type=company.gst_registration_type,
        is_composition=company.is_composition,
        fssai_license_no=branch.fssai_license_no,
        trade_license_no=branch.trade_license_no,
        state_code=branch.state_code,
        timezone=branch.timezone or company.timezone,
        upi_vpa=company.upi_vpa,
    )


class CheckoutClaimRead(BaseModel):
    claim_id: UUID
    order_id: UUID
    claim_token: str
    expires_at: datetime
    order_total_minor: int
    paid_minor: int
    due_minor: int
    order_version: int
    claimant_user_id: UUID
    terminal_id: UUID
    reused: bool


class PaymentCreate(BaseModel):
    method: Literal["cash", "card", "upi", "qr", "wallet"]
    amount_minor: int = Field(gt=0)
    tendered_minor: int | None = Field(default=None, ge=0)
    ref_external: str | None = Field(default=None, max_length=200)
    expected_order_total_minor: int | None = Field(default=None, ge=0)
    expected_due_minor: int | None = Field(default=None, ge=0)
    # Voluntary tip collected alongside this payment. Additional money on top
    # of the bill — never folded into amount_minor and never part of the
    # exact-amount-due match in _validate_confirmed_payment_balance (that
    # check protects the bill itself from stale reads/split payments; the
    # tip is layered on afterward). See record_payment for how it is applied.
    tip_minor: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def validate_tender_contract(self) -> PaymentCreate:
        """Keep physical tender facts unambiguous across every client.

        Cash must record what the customer actually handed over, including any
        tip. Non-cash rails have no cash tender/change concept and must not
        smuggle one into the receipt or drawer reconciliation.
        """
        collected_minor = self.amount_minor + self.tip_minor
        if self.method == "cash":
            if self.tendered_minor is None:
                raise ValueError("cash tendered amount is required")
            if self.tendered_minor < collected_minor:
                raise ValueError("cash tendered amount must cover the payment and tip")
        elif self.tendered_minor is not None:
            raise ValueError("cash tendered amount is only valid for cash payments")
        return self


class PaymentRead(BaseModel):
    """Authoritative settlement receipt returned by a successful payment."""

    id: UUID
    order_id: UUID
    shift_id: UUID
    method: Literal["cash", "card", "upi", "qr", "wallet"]
    # amount_minor is the total actually collected/banked (bill + tip), which
    # preserves the pre-existing API meaning. bill_amount_minor makes the split
    # explicit so receipt clients never add the tip twice.
    amount_minor: int
    bill_amount_minor: int
    tip_minor: int
    tendered_minor: int | None
    change_minor: int | None
    ref_external: str | None
    paid_at: datetime
    order_status: str
    invoice_no: str | None
    fiscal_year: str | None
    invoice_issued_at: datetime | None


async def _payment_read_from_stored_response(
    session,
    *,
    body: dict,
    order_id: UUID,
) -> PaymentRead:
    """Read both current and pre-receipt-grade idempotency responses safely.

    A payment response can outlive an application deployment. Releases before
    ``PaymentRead`` stored only the payment id, collected amount, tip and
    invoice identity. Replaying one of those keys must still return the
    already-recorded payment rather than fail in a way that tempts a cashier
    to submit a new key. Only that exact legacy shape is reconstructed, using
    the immutable Payment row for every missing settlement fact; other invalid
    stored bodies remain errors instead of being guessed at.
    """
    try:
        return PaymentRead.model_validate(body)
    except ValidationError as validation_error:
        legacy_fields = {
            "id",
            "amount_minor",
            "tip_minor",
            "order_status",
            "invoice_no",
            "fiscal_year",
            "invoice_issued_at",
        }
        if set(body) != legacy_fields:
            raise validation_error

        try:
            payment_id = UUID(str(body["id"]))
            legacy_amount_minor = int(body["amount_minor"])
            legacy_tip_minor = int(body["tip_minor"])
        except (TypeError, ValueError) as exc:
            raise BusinessRuleError(
                "Payment is already recorded, but its saved receipt is invalid. "
                "Do not collect payment again; ask a protected owner to reconcile it."
            ) from exc

        payment = (
            await session.execute(
                select(Payment).where(
                    Payment.id == payment_id,
                    Payment.order_id == order_id,
                )
            )
        ).scalar_one_or_none()
        if (
            payment is None
            or legacy_amount_minor != payment.amount_minor
            or legacy_tip_minor < 0
            or legacy_tip_minor > payment.amount_minor
        ):
            raise BusinessRuleError(
                "Payment is already recorded, but its saved receipt cannot be "
                "reconstructed. Do not collect payment again; ask a protected "
                "owner to reconcile it."
            )

        return PaymentRead(
            id=payment.id,
            order_id=payment.order_id,
            shift_id=payment.shift_id,
            method=payment.method,
            amount_minor=payment.amount_minor,
            bill_amount_minor=payment.amount_minor - legacy_tip_minor,
            tip_minor=legacy_tip_minor,
            tendered_minor=payment.tendered_minor,
            change_minor=payment.change_minor,
            ref_external=payment.ref_external,
            paid_at=payment.paid_at,
            order_status=str(body["order_status"]),
            invoice_no=body["invoice_no"],
            fiscal_year=body["fiscal_year"],
            invoice_issued_at=body["invoice_issued_at"],
        )


class OrderCustomerUpdate(BaseModel):
    customer_name: str | None = Field(default=None, max_length=200)
    customer_phone: str | None = Field(default=None, max_length=20)
    expected_checkout_version: int | None = Field(default=None, ge=1)

    @field_validator("customer_name", "customer_phone")
    @classmethod
    def normalize_optional_customer_identity(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None


class OrderDiscountUpdate(BaseModel):
    # Absolute set, not an increment — re-sending the same value is a no-op,
    # and a cashier who changes their mind sends the new total, not a delta.
    manual_discount_minor: int = Field(ge=0)
    expected_checkout_version: int | None = Field(default=None, ge=1)


class OrderPointsRedemptionUpdate(BaseModel):
    # Absolute set of how many points the customer wants to spend on this
    # bill, not an increment. Requires a customer already attached to the
    # order (points belong to a specific phone number's balance).
    points: int = Field(ge=0)
    expected_checkout_version: int | None = Field(default=None, ge=1)


class RefundCreate(BaseModel):
    """Deprecated one-call refund contract retained only for a clear error."""

    reason_code: str = Field(min_length=1, max_length=50)
    amount_minor: int = Field(gt=0)
    mode: Literal["cash", "original", "credit_note"] = "original"
    manager_override_user_id: UUID | None = None
    note: str | None = Field(default=None, max_length=500)


class PosRefundRequestCreate(BaseModel):
    order_id: UUID
    shift_id: UUID
    reason_code: str = Field(min_length=1, max_length=50)
    amount_minor: int = Field(gt=0)
    expected_paid_minor: int = Field(ge=0)
    expected_refundable_minor: int = Field(ge=0)
    mode: Literal["cash", "original"] = "original"
    manager_override_user_id: UUID | None = None
    client_action_id: str = Field(min_length=8, max_length=160)
    external_reference: str | None = Field(default=None, max_length=200)
    provider_settled_at: datetime | None = None
    note: str | None = Field(default=None, max_length=500)


class PosRefundHandoffRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    ready_to_handover: bool


class PosRefundCashSettlementRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    cash_handed_over: bool
    settled_at: datetime


class PosRefundProviderSettlementRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    provider_completed: bool
    external_reference: str = Field(min_length=1, max_length=200)
    provider_settled_at: datetime


class PosRefundProviderPayoutStartRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    ready_to_start_provider_payout: bool


class PosRefundWithdrawalRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    cash_not_handed_over: bool
    reason: str = Field(min_length=3, max_length=500)
    withdrawn_at: datetime


class PosRefundProviderWithdrawalRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    provider_not_completed: bool
    reason: str = Field(min_length=3, max_length=500)
    withdrawn_at: datetime


class PosRefundCashHandoffResolutionRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    cash_not_handed_over: bool
    drawer_unchanged: bool
    reason: str = Field(min_length=3, max_length=500)
    resolved_at: datetime


class PosRefundProviderPayoutResolutionRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    provider_not_completed: bool
    provider_status: Literal[
        "no_matching_transaction", "provider_declined", "provider_reversed"
    ]
    verification_reference: str = Field(min_length=3, max_length=200)
    provider_checked_at: datetime
    reason: str = Field(min_length=3, max_length=500)


class PosRefundAccountingFinalizationRequest(BaseModel):
    """Safe post-completion step; it never represents a new money movement."""

    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)


class PosRefundRequestRead(BaseModel):
    id: UUID
    order_id: UUID
    shift_id: UUID
    branch_id: UUID
    terminal_id: UUID
    amount_minor: int
    reason_code: str
    mode: str
    settlement_method: str
    status: Literal[
        "accepted_cash_due",
        "accepted_provider_due",
        "cash_handoff_in_progress",
        "cash_handed_over_pending_accounting",
        "provider_payout_in_progress",
        "provider_completed_pending_accounting",
        "settled",
        "withdrawn",
    ]
    accepted_at: datetime
    # Optional only for replaying a pre-0036 cached idempotency response. New
    # server reads always populate the authoritative actor.
    accepted_by: UUID | None = None
    accepted_by_name: str | None = None
    handoff_started_at: datetime | None = None
    handoff_started_by: UUID | None = None
    handoff_started_by_name: str | None = None
    cash_handed_over_at: datetime | None = None
    cash_handed_over_recorded_at: datetime | None = None
    cash_handed_over_by: UUID | None = None
    cash_handed_over_by_name: str | None = None
    provider_payout_started_at: datetime | None = None
    provider_payout_started_by: UUID | None = None
    provider_payout_started_by_name: str | None = None
    provider_completed_at: datetime | None = None
    provider_completion_recorded_at: datetime | None = None
    provider_completed_by: UUID | None = None
    provider_completed_by_name: str | None = None
    settled_at: datetime | None = None
    settled_by: UUID | None = None
    settled_by_name: str | None = None
    client_occurred_at: datetime | None = None
    captured_time_reconciled: bool | None = None
    provider_evidence_reconciled: bool | None = None
    withdrawn_at: datetime | None = None
    withdrawn_by: UUID | None = None
    withdrawn_by_name: str | None = None
    provider_verification_status: str | None = None
    provider_verification_reference: str | None = None
    provider_verified_at: datetime | None = None
    external_reference: str | None = None
    receipt_no: str | None = None
    refund_id: UUID | None = None
    client_action_id: str
    customer_spend_reconciled: bool | None = None
    note: str | None = None


class PosRefundEvidenceReconciliationCreate(BaseModel):
    refund_id: UUID
    evidence_kind: Literal["provider_reference", "captured_time"]
    proof_reference: str = Field(min_length=3, max_length=200)
    reason: str = Field(min_length=3, max_length=500)


class PosRefundEvidenceReconciliationRead(BaseModel):
    id: UUID
    refund_id: UUID
    evidence_kind: Literal["provider_reference", "captured_time"]
    proof_reference: str
    reason: str
    reconciled_at: datetime
    reconciled_by: UUID
    reconciled_by_name: str | None = None


class PendingPosRefundEvidenceRead(BaseModel):
    refund_id: UUID
    request_id: UUID
    order_id: UUID
    evidence_kind: Literal["provider_reference", "captured_time"]
    amount_minor: int
    settlement_method: str
    settled_at: datetime
    external_reference: str | None = None
    client_occurred_at: datetime | None = None


class CustomerSpendReconciliationCreate(BaseModel):
    customer_id: UUID
    pos_refund_id: UUID | None = None
    membership_refund_settlement_id: UUID | None = None
    expected_current_total_spent_minor: int = Field(ge=0)
    reason: str = Field(min_length=3, max_length=500)


class CustomerSpendReconciliationRead(BaseModel):
    id: UUID
    customer_id: UUID
    customer_name: str | None = None
    pos_refund_id: UUID | None = None
    membership_refund_settlement_id: UUID | None = None
    source_reconciliation_state: Literal["unreconciled", "legacy_unknown"]
    source_amount_minor: int
    before_total_spent_minor: int
    after_total_spent_minor: int
    adjustment_minor: int
    pos_gross_minor: int
    membership_gross_minor: int
    pos_refunds_minor: int
    membership_refunds_minor: int
    reason: str
    reconciled_at: datetime
    reconciled_by: UUID


class PendingCustomerSpendReconciliationRead(BaseModel):
    source_type: Literal["pos_refund", "membership_refund"]
    source_id: UUID
    order_id: UUID | None = None
    customer_id: UUID
    customer_name: str | None = None
    current_total_spent_minor: int
    amount_minor: int
    settled_at: datetime
    reconciliation_state: Literal["unreconciled", "legacy_unknown"]
    refund_reason_code: str | None = None


class ShiftOpenRequest(BaseModel):
    opening_float_minor: int = 0


def _require_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for POS money writes")
    return str(key), str(request_hash)


async def _compute_points_with_multiplier(
    session,
    *,
    order: Order,
    order_lines: list[OrderLine],
    membership_multiplier: float = 1.0,
) -> int:
    """Loyalty point allocation — gaming only.

    Rules:
      - Gaming (PS5 / VR / simulator sessions): 2 points per ₹10 spent.
      - Food, drinks, hookah, streaming, event tickets: earn nothing. The
        points program is a gaming rewards ladder, not a general discount.
      - Multiplied by the customer's membership multiplier (e.g. 1.5× for Gold tier).

    Earned on what was actually collected: line_total_minor reflects
    line/membership discounts, but not order.manual_discount_minor or
    order.points_redeemed_minor (both order-level, layered on after line
    pricing). Scaling by the paid ratio stops a customer from re-earning
    points on money a discount or a points redemption already covered.

    record_payment folds any tip into order.total_minor before this runs
    (see app/api/v1/pos/router.py), so it must be excluded here from both
    the numerator and pre_discount_total — otherwise a tipped order's ratio
    gets pulled toward 1.0 by the tip and over-awards points on the
    discounted/redeemed portion of the bill.
    """
    order_lines = [
        line for line in order_lines if getattr(line, "voided_at", None) is None
    ]
    if not order_lines:
        return 0  # no lines to attribute to gaming — nothing earned

    # Pull each line's menu item type in one query
    item_ids = [ol.menu_item_id for ol in order_lines]
    items = (
        await session.execute(select(MenuItem).where(MenuItem.id.in_(item_ids)))
    ).scalars().all()
    type_by_id = {i.id: i.type for i in items}

    GAMING_POINTS_PER_10_RUPEES = 2.0
    raw_points = 0.0
    for ol in order_lines:
        if type_by_id.get(ol.menu_item_id) != "gaming":
            continue
        line_total = int(ol.line_total_minor or 0)
        raw_points += (line_total / 1000) * GAMING_POINTS_PER_10_RUPEES

    taxable_total_minor = int(order.total_minor or 0) - int(order.tip_minor or 0)
    pre_discount_total = (
        taxable_total_minor
        + int(order.manual_discount_minor or 0)
        + int(order.points_redeemed_minor or 0)
    )
    paid_ratio = (
        min(1.0, taxable_total_minor / pre_discount_total) if pre_discount_total > 0 else 0.0
    )
    return int(raw_points * paid_ratio * membership_multiplier)


async def _upsert_and_attach_customer(
    session,
    *,
    company_id: UUID,
    phone: str,
    name: str | None,
    order: Order,
    order_lines: list[OrderLine] | None = None,
) -> Customer:
    """Find or create customer by phone, bump visit_count + total_spent,
    award loyalty points (1× food, 2× gaming/hookah/streaming/events, × membership tier).
    """
    existing = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == company_id,
                Customer.phone == phone,
                Customer.deleted_at.is_(None),
            ).with_for_update()
        )
    ).scalar_one_or_none()
    now = datetime.now(timezone.utc)

    # Resolve membership multiplier if customer already exists and has an active sub
    multiplier = 1.0
    if existing:
        sub = (
            await session.execute(
                select(CustomerMembership, MembershipTier)
                .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
                .where(
                    CustomerMembership.customer_id == existing.id,
                    CustomerMembership.starts_at <= now,
                    CustomerMembership.expires_at > now,
                    CustomerMembership.revoked_at.is_(None),
                )
                .limit(1)
            )
        ).first()
        if sub:
            multiplier = float(sub.MembershipTier.point_multiplier or 1)

    points_earned = await _compute_points_with_multiplier(
        session, order=order, order_lines=order_lines or [],
        membership_multiplier=multiplier,
    )

    if existing:
        old_lifetime = int(existing.lifetime_gaming_points_earned or 0)
        new_lifetime = old_lifetime + int(points_earned)
        bonus = rank_up_bonus_points(old_lifetime=old_lifetime, new_lifetime=new_lifetime)
        existing.visit_count += 1
        existing.total_spent_minor += order.total_minor
        existing.last_visit_at = now
        existing.loyalty_points += int(points_earned) + bonus
        # Points are gaming-only now (see _compute_points_with_multiplier),
        # so every point earned is a gaming point — this counter just never
        # goes back down when loyalty_points is spent (see points.py rank_progress).
        # The rank-up bonus is credited to loyalty_points ONLY, never here —
        # crediting it here would let a bonus cascade into unlocking the next
        # rank too.
        existing.lifetime_gaming_points_earned = new_lifetime
        if name and not existing.name:
            existing.name = name
        order.customer_id = existing.id
        return existing
    else:
        bonus = rank_up_bonus_points(old_lifetime=0, new_lifetime=int(points_earned))
        customer = Customer(
            id=uuid4(),
            company_id=company_id,
            phone=phone,
            name=name,
            visit_count=1,
            total_spent_minor=order.total_minor,
            first_visit_at=now,
            last_visit_at=now,
            loyalty_points=int(points_earned) + bonus,
            lifetime_gaming_points_earned=int(points_earned),
        )
        session.add(customer)
        order.customer_id = customer.id
        return customer


class ShiftCloseRequest(BaseModel):
    counted_minor: int = Field(ge=0)


async def _paid_total(session, order_id: UUID) -> int:
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Payment.amount_minor), 0)).where(
                    Payment.order_id == order_id
                )
            )
        ).scalar_one()
        or 0
    )


async def _refunded_total(session, order_id: UUID) -> int:
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Refund.amount_minor), 0)).where(
                    Refund.order_id == order_id
                )
            )
        ).scalar_one()
        or 0
    )


async def _unresolved_pos_refund_total(
    session,
    *,
    order_id: UUID | None = None,
    shift_id: UUID | None = None,
    customer_id: UUID | None = None,
    cash_only: bool = False,
    exclude_request_id: UUID | None = None,
) -> int:
    """Return accepted POS refunds that have neither settled nor withdrawn."""
    stmt = (
        select(func.coalesce(func.sum(PosRefundRequest.amount_minor), 0))
        .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
        .outerjoin(
            PosRefundWithdrawal,
            PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
        )
        .where(Refund.id.is_(None), PosRefundWithdrawal.id.is_(None))
    )
    if order_id is not None:
        stmt = stmt.where(PosRefundRequest.order_id == order_id)
    if shift_id is not None:
        stmt = stmt.where(PosRefundRequest.shift_id == shift_id)
    if cash_only:
        stmt = stmt.where(PosRefundRequest.settlement_method == "cash")
    if customer_id is not None:
        stmt = stmt.join(Order, Order.id == PosRefundRequest.order_id).where(
            Order.customer_id == customer_id
        )
    if exclude_request_id is not None:
        stmt = stmt.where(PosRefundRequest.id != exclude_request_id)
    return int((await session.execute(stmt)).scalar_one() or 0)


async def _unresolved_membership_cash_refund_total(
    session,
    *,
    shift_id: UUID,
) -> int:
    """Reserve the same physical drawer for membership and POS payouts."""
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(MembershipRefund.amount_minor), 0))
                .outerjoin(
                    MembershipRefundSettlement,
                    MembershipRefundSettlement.refund_id == MembershipRefund.id,
                )
                .outerjoin(
                    MembershipRefundResolution,
                    MembershipRefundResolution.refund_id == MembershipRefund.id,
                )
                .where(
                    MembershipRefund.shift_id == shift_id,
                    MembershipRefund.method == "cash",
                    MembershipRefundSettlement.id.is_(None),
                    MembershipRefundResolution.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )


async def _unresolved_membership_refund_total_for_customer(
    session,
    *,
    customer_id: UUID,
) -> int:
    """Reserve customer LTV for every accepted membership refund rail."""
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(MembershipRefund.amount_minor), 0))
                .join(
                    MembershipPayment,
                    MembershipPayment.id == MembershipRefund.payment_id,
                )
                .join(
                    CustomerMembership,
                    CustomerMembership.id == MembershipPayment.membership_id,
                )
                .outerjoin(
                    MembershipRefundSettlement,
                    MembershipRefundSettlement.refund_id == MembershipRefund.id,
                )
                .outerjoin(
                    MembershipRefundResolution,
                    MembershipRefundResolution.refund_id == MembershipRefund.id,
                )
                .where(
                    CustomerMembership.customer_id == customer_id,
                    MembershipRefundSettlement.id.is_(None),
                    MembershipRefundResolution.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )


def _validated_pos_financial_time(
    *,
    request: Request,
    occurred_at: datetime,
    shift: Shift,
    now: datetime,
    action_name: str,
) -> datetime:
    """Validate captured offline time while retaining server receipt time."""
    if occurred_at.tzinfo is None:
        raise BusinessRuleError(f"{action_name} time must include a timezone.")
    captured = occurred_at.astimezone(timezone.utc)
    if captured > now + timedelta(minutes=5):
        raise BusinessRuleError(
            "This tablet's clock is ahead of the server. Correct the device time, "
            "reopen Refunds, and try again."
        )
    if captured < now - timedelta(days=7):
        raise BusinessRuleError(
            f"This saved {action_name.lower()} is more than 7 days old. A protected "
            "owner must reconcile it before another refund is attempted."
        )
    was_offline = request.headers.get("X-Offline-Captured", "").strip().lower() in {
        "1", "true", "yes",
    }
    header_time = request.headers.get("X-Client-Occurred-At")
    if was_offline:
        if not header_time:
            raise BusinessRuleError(
                f"Offline {action_name.lower()} is missing captured-time provenance."
            )
        try:
            provenance_time = datetime.fromisoformat(
                header_time.strip().replace("Z", "+00:00")
            ).astimezone(timezone.utc)
        except (TypeError, ValueError) as exc:
            raise BusinessRuleError(
                f"Offline {action_name.lower()} has invalid captured-time provenance."
            ) from exc
        if abs((provenance_time - captured).total_seconds()) > 1:
            raise BusinessRuleError(
                f"Saved {action_name.lower()} time does not match its audit provenance. "
                "Nothing was posted."
            )
    elif captured < shift.opened_at - timedelta(minutes=5):
        raise BusinessRuleError(
            f"{action_name} time is before this shift opened. Return to the correct "
            "shift and try again."
        )
    return captured


def _captured_pos_financial_evidence_after_value_moved(
    *,
    request: Request,
    occurred_at: datetime,
    earliest_server_at: datetime,
    server_now: datetime,
) -> tuple[datetime, bool]:
    """Preserve client/provider time without rejecting a real-world payout.

    `server_now` is always the accounting/receipt time. The returned timestamp
    is evidence only; the boolean tells audit/UI whether the evidence is
    consistent with the server-confirmed start and optional offline headers.
    A skewed or stale device must never erase cash/provider money that staff
    have already attested moved.
    """
    timezone_present = occurred_at.tzinfo is not None
    captured = (
        occurred_at.astimezone(timezone.utc)
        if timezone_present
        else occurred_at.replace(tzinfo=timezone.utc)
    )
    reconciled = (
        timezone_present
        and earliest_server_at <= captured <= server_now + timedelta(minutes=5)
        and captured >= server_now - timedelta(days=7)
    )
    was_offline = request.headers.get("X-Offline-Captured", "").strip().lower() in {
        "1",
        "true",
        "yes",
    }
    if was_offline:
        header_time = request.headers.get("X-Client-Occurred-At")
        if not header_time:
            reconciled = False
        else:
            try:
                provenance_time = datetime.fromisoformat(
                    header_time.strip().replace("Z", "+00:00")
                )
                if provenance_time.tzinfo is None:
                    reconciled = False
                else:
                    provenance_time = provenance_time.astimezone(timezone.utc)
                    reconciled = reconciled and abs(
                        (provenance_time - captured).total_seconds()
                    ) <= 1
            except (TypeError, ValueError):
                reconciled = False
    return captured, reconciled


async def _allocate_pos_refund_receipt(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    occurred_at: datetime,
) -> tuple[str, str]:
    branch = await session.get(Branch, branch_id)
    company = await session.get(Company, company_id)
    if branch is None or company is None or branch.company_id != company_id:
        raise BusinessRuleError(
            "Cannot issue the refund receipt because the branch identity is invalid."
        )
    return await InvoiceNumberService(session).allocate(
        branch_id=branch_id,
        branch_code=branch.code or "RF",
        prefix="R",
        series="pos_refund",
        at=occurred_at,
        timezone_name=branch.timezone or company.timezone,
    )


def _pos_refund_request_read(
    refund_request: PosRefundRequest,
    *,
    order: Order,
    handoff: PosRefundCashHandoff | None = None,
    cash_completion: PosRefundCashHandoffCompletion | None = None,
    provider_start: PosRefundProviderPayoutStart | None = None,
    provider_completion: PosRefundProviderSettlement | None = None,
    refund: Refund | None = None,
    withdrawal: PosRefundWithdrawal | None = None,
    actor_names: dict[UUID, str] | None = None,
) -> PosRefundRequestRead:
    actor_names = actor_names or {}
    status_value: str = (
        "settled"
        if refund is not None
        else "withdrawn"
        if withdrawal is not None
        else "cash_handed_over_pending_accounting"
        if cash_completion is not None
        else "provider_completed_pending_accounting"
        if provider_completion is not None
        else "cash_handoff_in_progress"
        if handoff is not None
        else "provider_payout_in_progress"
        if provider_start is not None
        else "accepted_cash_due"
        if refund_request.settlement_method == "cash"
        else "accepted_provider_due"
    )
    return PosRefundRequestRead(
        id=refund_request.id,
        order_id=refund_request.order_id,
        shift_id=refund_request.shift_id,
        branch_id=refund_request.branch_id,
        terminal_id=refund_request.terminal_id,
        amount_minor=int(refund_request.amount_minor),
        reason_code=refund_request.reason_code,
        mode=refund_request.mode,
        settlement_method=refund_request.settlement_method,
        status=status_value,
        accepted_at=refund_request.accepted_at,
        handoff_started_at=handoff.started_at if handoff else None,
        settled_at=(refund.settled_at or refund.created_at) if refund else None,
        withdrawn_at=withdrawal.withdrawn_at if withdrawal else None,
        external_reference=(
            refund.external_reference
            if refund
            else provider_completion.external_reference
            if provider_completion
            else None
        ),
        receipt_no=refund.receipt_no if refund else None,
        refund_id=refund.id if refund else None,
        client_action_id=refund_request.client_action_id,
        accepted_by=refund_request.approved_by,
        accepted_by_name=actor_names.get(refund_request.approved_by),
        handoff_started_by=handoff.started_by if handoff else None,
        handoff_started_by_name=(
            actor_names.get(handoff.started_by) if handoff else None
        ),
        cash_handed_over_at=(
            cash_completion.handed_over_at if cash_completion else None
        ),
        cash_handed_over_recorded_at=(
            cash_completion.recorded_at if cash_completion else None
        ),
        cash_handed_over_by=(
            cash_completion.recorded_by if cash_completion else None
        ),
        cash_handed_over_by_name=(
            actor_names.get(cash_completion.recorded_by)
            if cash_completion
            else None
        ),
        provider_payout_started_at=(
            provider_start.started_at if provider_start else None
        ),
        provider_payout_started_by=(
            provider_start.started_by if provider_start else None
        ),
        provider_payout_started_by_name=(
            actor_names.get(provider_start.started_by) if provider_start else None
        ),
        provider_completed_at=(
            provider_completion.provider_settled_at if provider_completion else None
        ),
        provider_completion_recorded_at=(
            provider_completion.created_at if provider_completion else None
        ),
        provider_completed_by=(
            provider_completion.settled_by if provider_completion else None
        ),
        provider_completed_by_name=(
            actor_names.get(provider_completion.settled_by)
            if provider_completion
            else None
        ),
        settled_by=refund.settled_by if refund else None,
        settled_by_name=(actor_names.get(refund.settled_by) if refund else None),
        client_occurred_at=(
            refund.client_occurred_at
            if refund
            else cash_completion.handed_over_at
            if cash_completion
            else provider_completion.provider_settled_at
            if provider_completion
            else None
        ),
        captured_time_reconciled=(
            refund.captured_time_reconciled
            if refund
            else cash_completion.captured_time_reconciled
            if cash_completion
            else provider_completion.captured_time_reconciled
            if provider_completion
            else None
        ),
        provider_evidence_reconciled=(
            refund.provider_evidence_reconciled
            if refund
            else provider_completion.provider_evidence_reconciled
            if provider_completion
            else None
        ),
        withdrawn_by=withdrawal.withdrawn_by if withdrawal else None,
        withdrawn_by_name=(
            actor_names.get(withdrawal.withdrawn_by) if withdrawal else None
        ),
        provider_verification_status=(
            withdrawal.verification_status if withdrawal else None
        ),
        provider_verification_reference=(
            withdrawal.verification_reference if withdrawal else None
        ),
        provider_verified_at=withdrawal.verified_at if withdrawal else None,
        customer_spend_reconciled=(
            refund.customer_spend_reconciled if refund else None
        ),
        note=refund_request.note,
    )


def _validate_confirmed_payment_balance(
    payload: PaymentCreate,
    *,
    order_total_minor: int,
    due_minor: int,
) -> None:
    """Protect a cashier-confirmed full settlement from stale bill state."""
    if (
        payload.expected_order_total_minor is not None
        and payload.expected_order_total_minor != order_total_minor
    ):
        raise BusinessRuleError(
            "Order total changed before payment. Reload the exact bill before collecting money."
        )
    if payload.expected_due_minor is not None and payload.expected_due_minor != due_minor:
        raise BusinessRuleError(
            "Order balance changed before payment. Reload the exact amount due before collecting money."
        )
    if due_minor > 0 and payload.amount_minor != due_minor:
        raise BusinessRuleError(
            "Split payments are not enabled. Payment must equal the exact amount due."
        )


def _stored_line_gross_amount(line: OrderLine, *, price_includes_tax: bool) -> int:
    """Recover the snapshotted pre-membership amount without current menu prices."""
    discount = int(line.discount_minor or 0)
    if price_includes_tax:
        return max(0, int(line.line_total_minor or 0) + discount)
    return max(0, int(line.taxable_value_minor or 0) + discount)


async def _reprice_unpaid_order_for_customer(
    session,
    *,
    order: Order,
    company_id: UUID,
) -> None:
    """Apply the attached customer's current membership to stored gross lines.

    Repricing uses each order line's existing gross snapshot, not today's menu
    price. A session line uses its GamingSession tax/price-mode snapshot, so a
    later station or catalog edit cannot rewrite the service already consumed.
    """
    lines = (
        await session.execute(
            select(OrderLine)
            .where(
                OrderLine.order_id == order.id,
                OrderLine.voided_at.is_(None),
            )
            .with_for_update()
        )
    ).scalars().all()
    if not lines:
        raise BusinessRuleError("order has no lines to price")

    item_ids = {line.menu_item_id for line in lines}
    items = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.company_id == company_id,
                MenuItem.id.in_(item_ids),
            )
        )
    ).scalars().all()
    items_by_id = {item.id: item for item in items}
    if len(items_by_id) != len(item_ids):
        raise BusinessRuleError(
            "An order item is missing from the catalog. Ask a protected owner to reconcile it."
        )

    session_row = (
        await session.execute(
            select(GamingSession, Station)
            .join(Station, Station.id == GamingSession.station_id)
            .where(GamingSession.order_id == order.id)
            .limit(1)
        )
    ).first()
    gaming_session = session_row[0] if session_row else None
    station = session_row[1] if session_row else None
    requested_gaming_minutes = (
        max(0, int(gaming_session.billable_minutes or 0))
        if gaming_session
        and station
        and station.type == "ps5"
        and not is_package_billed(gaming_session)
        else 0
    )
    requested_hookah_count = (
        1 if gaming_session and station and station.type == "hookah" else 0
    )
    benefits = await reserve_membership_benefits(
        session,
        order=order,
        company_id=company_id,
        requested_gaming_minutes=requested_gaming_minutes,
        requested_hookah_count=requested_hookah_count,
    )

    pricing = OrderPricingService(session)
    for line in lines:
        item = items_by_id[line.menu_item_id]
        is_session_line = bool(gaming_session and item.sku.startswith("SESSION-"))
        price_includes_tax = bool(item.price_includes_tax)
        item_type = item.type
        gross_amount = _stored_line_gross_amount(
            line,
            price_includes_tax=price_includes_tax,
        )
        tax_rate = Decimal(str(line.tax_rate or 0))
        if is_session_line:
            if gaming_session.rate_includes_tax is not None:
                price_includes_tax = bool(gaming_session.rate_includes_tax)
            gross_amount = int(gaming_session.amount_minor or gross_amount)
            tax_rate = Decimal(str(gaming_session.tax_rate or line.tax_rate or 0))
            item_type = (
                "hookah"
                if station and station.type == "hookah"
                else "streaming"
                if station and station.type == "streaming"
                else "gaming"
            )

        allowance_minor = 0
        if is_session_line and gaming_session and station:
            if (
                station.type == "ps5"
                and benefits.gaming_minutes
                and not is_package_billed(gaming_session)
            ):
                # A package session's amount_minor is a fixed, advertised
                # price (see gaming/router.py) with no proportional
                # relationship to billable_minutes — the elapsed-time waiver
                # math below would let a member leave minutes into a package
                # and have it waived almost in full. Free-minutes benefits
                # only apply to legacy open-ended (non-package) sessions.
                allowance_minor = gaming_minutes_allowance_minor(
                    gross_amount_minor=gross_amount,
                    billable_minutes=int(gaming_session.billable_minutes or 0),
                    reserved_minutes=benefits.gaming_minutes,
                    rate_per_hour_minor=int(gaming_session.rate_per_hour_minor or 0),
                )
            elif station.type == "hookah" and benefits.hookah_count:
                # A hookah allowance is a whole-session benefit.  Use the
                # snapshotted session gross, never today's station rate.
                allowance_minor = gross_amount

        priced = await pricing.price_time_based_line(
            company_id=company_id,
            branch_id=order.branch_id,
            amount_minor=gross_amount,
            tax_rate=tax_rate,
            rate_includes_tax=price_includes_tax,
            customer_phone=order.customer_phone,
            item_type=item_type,
            place_of_supply_state_code=order.place_of_supply_state_code,
            delivery_via=order.delivery_via,
            allowance_minor=allowance_minor,
        )
        qty = max(1, int(line.qty))
        line.unit_price_minor = int(
            (Decimal(priced.total_minor) / Decimal(qty)).quantize(
                Decimal("1"), rounding=ROUND_HALF_UP
            )
        )
        line.line_total_minor = priced.total_minor
        line.discount_minor = priced.discount_minor
        line.taxable_value_minor = priced.taxable_minor
        line.cgst_minor = priced.cgst_minor
        line.sgst_minor = priced.sgst_minor
        line.igst_minor = priced.igst_minor
        line.cess_minor = 0

    raw_total = sum(int(line.line_total_minor or 0) for line in lines)
    rounded_total, round_off = _round_to_rupee(raw_total)
    order.subtotal_minor = sum(int(line.taxable_value_minor or 0) for line in lines)
    line_discount_total = sum(int(line.discount_minor or 0) for line in lines)
    order.cgst_minor = sum(int(line.cgst_minor or 0) for line in lines)
    order.sgst_minor = sum(int(line.sgst_minor or 0) for line in lines)
    order.igst_minor = sum(int(line.igst_minor or 0) for line in lines)
    order.cess_minor = sum(int(line.cess_minor or 0) for line in lines)
    order.tax_minor = order.cgst_minor + order.sgst_minor + order.igst_minor + order.cess_minor
    order.round_off_minor = round_off
    # A cashier's manual discount and a customer's points redemption both
    # live outside this line-based recompute — preserve them (clamped so
    # neither can exceed the new total, e.g. if a membership just waived
    # most of the bill). Points must be re-requested at their PRIOR count,
    # never maxed out to fill the new total — this repricing preserves an
    # existing redemption, it doesn't grow it. Only preserve if the order's
    # customer is still the same one the points were reserved against —
    # attaching a different customer must never silently spend their points.
    previously_redeemed_points = 0
    existing_redemption = (
        await session.execute(
            select(PointsRedemption).where(PointsRedemption.order_id == order.id)
        )
    ).scalar_one_or_none()
    if existing_redemption is not None:
        current_customer_id = (
            await session.execute(
                select(Customer.id).where(
                    Customer.company_id == company_id,
                    Customer.phone == order.customer_phone,
                    Customer.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if current_customer_id == existing_redemption.customer_id:
            previously_redeemed_points = existing_redemption.points_spent
    order.manual_discount_minor, discount_after_manual, total_after_manual = apply_manual_discount(
        line_discount_total_minor=line_discount_total,
        manual_discount_minor=int(order.manual_discount_minor or 0),
        rounded_total_minor=rounded_total,
    )
    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=company_id,
        requested_points=min(previously_redeemed_points, minor_to_points(total_after_manual)),
        at=datetime.now(timezone.utc),
    )
    order.points_redeemed_minor, order.discount_minor, order.total_minor = apply_points_redemption(
        discount_so_far_minor=discount_after_manual,
        points_redeemed_minor=points_result.amount_minor,
        remaining_total_minor=total_after_manual,
    )


async def _source_label(session, order: Order) -> str | None:
    """Human label for where an order came from: its table, or (for a
    gaming/hookah session sent to POS) the station that billed it."""
    if order.table_id is not None:
        table = await session.get(Table, order.table_id)
        return f"Table {table.code}" if table else None
    row = (
        await session.execute(
            select(Station.name)
            .join(GamingSession, GamingSession.station_id == Station.id)
            .where(GamingSession.order_id == order.id)
            .limit(1)
        )
    ).scalar_one_or_none()
    return row


def _pricing_line_request(line: OrderLineCreate) -> LineRequest:
    """Translate IDs/quantities only; pricing resolves every mutable fact."""
    return LineRequest(
        menu_item_id=line.menu_item_id,
        qty=int(line.qty),
        variant_id=line.variant_id,
        modifiers=tuple(
            ModifierSelection(
                modifier_id=selection.modifier_id,
                quantity=selection.qty,
            )
            for selection in (line.modifiers or ())
        ),
    )


def _validate_client_line_ids(lines: list[OrderLineCreate]) -> None:
    """Reject an ambiguous offline batch before it reaches a DB constraint."""
    client_ids = [line.client_line_id for line in lines if line.client_line_id is not None]
    if len(client_ids) != len(set(client_ids)):
        raise BusinessRuleError(
            "The same offline line action appears more than once. Reload the table bill "
            "and retry the unsent round once."
        )


async def _reject_persisted_client_line_ids(
    session,
    *,
    order_id: UUID,
    lines: list[OrderLineCreate],
) -> None:
    """Turn a replay under a different request key into a stable conflict.

    The caller holds the parent Order lock, so this check and the later line
    inserts serialize with every API append. The partial unique index remains
    the last-resort database invariant for writes outside the API.
    """
    requested = {
        line.client_line_id
        for line in lines
        if line.client_line_id is not None
    }
    if not requested:
        return
    collisions = set(
        (
            await session.execute(
                select(OrderLine.client_line_id).where(
                    OrderLine.order_id == order_id,
                    OrderLine.client_line_id.in_(requested),
                )
            )
        ).scalars().all()
    )
    if collisions:
        raise ConflictError(
            "One or more table-item actions were already saved under a different "
            "request. Reload the bill; do not submit those items again.",
            details={
                "client_line_ids": sorted(str(value) for value in collisions),
            },
        )


def _require_checkout_version(order: Order, expected: int, *, operation: str) -> None:
    current = max(1, int(order.checkout_version or 1))
    if expected != current:
        raise BusinessRuleError(
            f"This bill changed before {operation}. Reload it and review the latest "
            "items before trying again.",
            details={
                "expected_checkout_version": expected,
                "current_checkout_version": current,
            },
        )


def _require_settlement_metadata_version(
    order: Order,
    expected: int | None,
    *,
    operation: str,
) -> None:
    """Require optimistic concurrency for shared bills awaiting settlement.

    Direct, open POS orders retain backward compatibility for older clients,
    although any client that supplies a version still receives stale-write
    protection. A held bill is shared between operational screens and tills,
    so accepting an unversioned customer/benefit mutation would allow one
    cashier to overwrite a bill another cashier just reviewed.
    """
    if expected is None:
        if order.status == "held":
            raise BusinessRuleError(
                f"Reload this shared bill before {operation}; its current checkout "
                "version is required.",
                details={
                    "current_checkout_version": max(
                        1,
                        int(order.checkout_version or 1),
                    )
                },
            )
        return
    _require_checkout_version(order, expected, operation=operation)


async def _kitchen_item_ids(
    session,
    *,
    company_id: UUID,
    menu_item_ids: list[UUID],
) -> set[UUID]:
    """Return requested items that belong on the kitchen display."""
    return set(
        (
            await session.execute(
                select(MenuItem.id).where(
                    MenuItem.company_id == company_id,
                    MenuItem.id.in_(menu_item_ids),
                    MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
                )
            )
        ).scalars().all()
    )


async def _materialize_legacy_kitchen_line_state(session, order: Order) -> None:
    """Backfill an old all-queued batch while its order row is locked.

    The previous KDS changed only Order.kitchen_state. This must run before a
    late line is inserted and the mirror is reset to received, otherwise old
    served dishes and the genuinely new line become indistinguishable.
    """
    target_status = _KITCHEN_LINE_STATUS_BY_ORDER_STATE.get(order.kitchen_state)
    if target_status in {None, "queued"}:
        return
    existing_lines = (
        (
            await session.execute(
                select(OrderLine)
                .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
                .where(
                    OrderLine.order_id == order.id,
                    OrderLine.voided_at.is_(None),
                    MenuItem.company_id == order.company_id,
                    MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
                )
                .order_by(OrderLine.created_at, OrderLine.id)
            )
        )
        .scalars()
        .all()
    )
    if not existing_lines or any(
        (line.kitchen_status or "queued") != "queued" for line in existing_lines
    ):
        return
    for line in existing_lines:
        line.kitchen_status = target_status


async def _reaggregate_active_order_lines(
    session,
    *,
    order: Order,
    company_id: UUID,
) -> list[OrderLine]:
    """Rebuild a live bill from non-voided snapshots only.

    Appends and reasoned voids share this path so every subtotal, discount,
    redemption, receipt, and payment precondition uses the same active cohort.
    """
    active_lines = (
        await session.execute(
            select(OrderLine)
            .where(
                OrderLine.order_id == order.id,
                OrderLine.voided_at.is_(None),
            )
            .order_by(OrderLine.created_at, OrderLine.id)
            .with_for_update(of=OrderLine)
        )
    ).scalars().all()
    if not active_lines:
        raise BusinessRuleError(
            "A live bill must keep at least one active item. Void the whole bill "
            "with a reason if it should be cancelled."
        )

    sub_inclusive = sum(int(line.line_total_minor or 0) for line in active_lines)
    rounded, round_off = _round_to_rupee(sub_inclusive)
    order.subtotal_minor = sum(
        int(line.taxable_value_minor or 0) for line in active_lines
    )
    order.cgst_minor = sum(int(line.cgst_minor or 0) for line in active_lines)
    order.sgst_minor = sum(int(line.sgst_minor or 0) for line in active_lines)
    order.igst_minor = sum(int(line.igst_minor or 0) for line in active_lines)
    order.cess_minor = sum(int(line.cess_minor or 0) for line in active_lines)
    line_discount_total = sum(
        int(line.discount_minor or 0) for line in active_lines
    )
    order.tax_minor = (
        order.cgst_minor + order.sgst_minor + order.igst_minor + order.cess_minor
    )
    order.round_off_minor = round_off

    previously_redeemed_points = await points_redeemed_for_order(
        session, order=order
    )
    (
        order.manual_discount_minor,
        discount_after_manual,
        total_after_manual,
    ) = apply_manual_discount(
        line_discount_total_minor=line_discount_total,
        manual_discount_minor=int(order.manual_discount_minor or 0),
        rounded_total_minor=rounded,
    )
    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=company_id,
        requested_points=min(
            previously_redeemed_points,
            minor_to_points(total_after_manual),
        ),
        at=datetime.now(timezone.utc),
    )
    (
        order.points_redeemed_minor,
        order.discount_minor,
        order.total_minor,
    ) = apply_points_redemption(
        discount_so_far_minor=discount_after_manual,
        points_redeemed_minor=points_result.amount_minor,
        remaining_total_minor=total_after_manual,
    )
    return list(active_lines)


async def _release_table_if_no_active_orders(session, order: Order) -> None:
    """Free a table only when no other legacy/open ticket still owns it."""
    if order.table_id is None:
        return
    other_active = int(
        (
            await session.execute(
                select(func.count(Order.id)).where(
                    Order.table_id == order.table_id,
                    Order.id != order.id,
                    Order.status.in_(("open", "held")),
                )
            )
        ).scalar_one()
        or 0
    )
    if other_active:
        return
    table = await session.get(Table, order.table_id)
    if table and table.status == "occupied":
        table.status = "available"


def _order_line_read(line: OrderLine, item: MenuItem) -> OrderLineRead:
    return OrderLineRead(
        id=line.id,
        client_line_id=getattr(line, "client_line_id", None),
        menu_item_id=line.menu_item_id,
        variant_id=line.variant_id,
        variant_snapshot=getattr(line, "variant_snapshot", None),
        modifiers=line.modifiers,
        name=item.name,
        sku=item.sku,
        hsn_or_sac=line.hsn_or_sac or item.hsn_code or "",
        qty=float(line.qty),
        unit_price_minor=int(line.unit_price_minor),
        line_total_minor=int(line.line_total_minor),
        taxable_value_minor=int(line.taxable_value_minor),
        tax_rate=float(line.tax_rate),
        cgst_minor=int(line.cgst_minor),
        sgst_minor=int(line.sgst_minor),
        igst_minor=int(line.igst_minor),
        note=line.note,
        kitchen_status=line.kitchen_status or "queued",
        kitchen_released_at=getattr(line, "kitchen_released_at", None),
        kitchen_round_no=getattr(line, "kitchen_round_no", None),
        voided_at=line.voided_at,
        voided_by=line.voided_by,
        void_reason=getattr(line, "void_reason", None),
        kitchen_void_acknowledged_at=getattr(
            line, "kitchen_void_acknowledged_at", None
        ),
        kitchen_void_acknowledged_by=getattr(
            line, "kitchen_void_acknowledged_by", None
        ),
    )


async def _build_order_read(session, order: Order) -> OrderRead:
    # Migration 0031 maintains checkout_version with a PostgreSQL trigger.
    # SQLAlchemy expires server-generated values after an UPDATE; reading the
    # expired attribute synchronously from this async response builder would
    # otherwise attempt implicit I/O and raise MissingGreenlet. Refresh only
    # real ORM instances whose version is expired (unit-test doubles are left
    # untouched).
    order_state = sa_inspect(order, raiseerr=False)
    if order_state is not None and "checkout_version" in order_state.expired_attributes:
        await session.refresh(order, attribute_names=["checkout_version"])
    line_rows = (
        await session.execute(
            select(OrderLine, MenuItem)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(OrderLine.order_id == order.id)
            .order_by(OrderLine.created_at, OrderLine.id)
        )
    ).all()
    paid_minor = await _paid_total(session, order.id)
    due_minor = max(0, int(order.total_minor or 0) - paid_minor)
    benefits = await applied_benefits_for_order(session, order=order)
    active_line_rows = [
        row for row in line_rows if getattr(row[0], "voided_at", None) is None
    ]
    voided_line_rows = [
        row for row in line_rows if getattr(row[0], "voided_at", None) is not None
    ]
    return OrderRead(
        id=order.id,
        invoice_no=order.invoice_no,
        fiscal_year=order.fiscal_year,
        status=order.status,
        type=order.type,
        table_id=order.table_id,
        source_label=await _source_label(session, order),
        subtotal_minor=order.subtotal_minor,
        discount_minor=order.discount_minor,
        manual_discount_minor=order.manual_discount_minor,
        points_redeemed_minor=order.points_redeemed_minor,
        points_redeemed=await points_redeemed_for_order(session, order=order),
        cgst_minor=order.cgst_minor,
        sgst_minor=order.sgst_minor,
        igst_minor=order.igst_minor,
        cess_minor=order.cess_minor,
        tax_minor=order.tax_minor,
        round_off_minor=order.round_off_minor,
        tip_minor=order.tip_minor,
        total_minor=order.total_minor,
        paid_minor=paid_minor,
        due_minor=due_minor,
        free_gaming_minutes_applied=benefits.gaming_minutes,
        free_hookah_count_applied=benefits.hookah_count,
        delivery_via=order.delivery_via,
        place_of_supply_state_code=order.place_of_supply_state_code,
        customer_name=order.customer_name,
        customer_phone=order.customer_phone,
        customer_gstin=order.customer_gstin,
        customer_state_code=order.customer_state_code,
        opened_at=order.opened_at,
        closed_at=order.closed_at,
        invoice_issued_at=order.invoice_issued_at,
        held_at=order.held_at,
        checkout_version=max(1, int(getattr(order, "checkout_version", 1) or 1)),
        lines=[_order_line_read(line, item) for line, item in active_line_rows],
        voided_lines=[
            _order_line_read(line, item) for line, item in voided_line_rows
        ],
    )


async def _finalize_order(
    session,
    *,
    order: Order,
    company_id: UUID,
    actor_user_id: UUID,
    at: datetime,
) -> None:
    """Issue the invoice and run every sale-finalization side effect once.

    The caller holds the order and shift row locks and has proved the balance
    is exactly settled.  Payments and membership-funded zero bills share this
    path so inventory, loyalty, table release, invoice identity, and allowance
    consumption cannot drift apart.
    """
    if order.status not in ("open", "held"):
        raise BusinessRuleError(f"cannot finalize an order in status={order.status}")
    branch = await session.get(Branch, order.branch_id)
    if not branch or branch.company_id != company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    timezone_name = branch.timezone or await company_timezone(session, company_id)
    if not order.invoice_no:
        order.invoice_no, order.fiscal_year = await InvoiceNumberService(session).allocate(
            branch_id=order.branch_id,
            branch_code=branch.code or "MN",
            at=at,
            timezone_name=timezone_name,
        )

    # This row update is in the same database transaction as the invoice and
    # all other finalization effects. A rollback leaves the allowance reserved,
    # never half-consumed.
    await consume_membership_benefits(session, order_id=order.id, at=at)
    await consume_points_redemption(session, order_id=order.id, at=at)
    line_rows = (
        await session.execute(
            select(OrderLine, MenuItem)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.order_id == order.id,
                OrderLine.voided_at.is_(None),
            )
            .order_by(OrderLine.created_at, OrderLine.id)
            .with_for_update(of=OrderLine)
        )
    ).all()
    order_lines = [line for line, _item in line_rows]
    if not order_lines:
        raise BusinessRuleError(
            "This bill has no active items. Reload it and resolve the cancelled lines "
            "before collecting payment."
        )

    # Payment is the release boundary for a direct POS order. Existing table
    # rounds are already stamped, and gaming/shisha item types are deliberately
    # outside the kitchen set. All writes commit atomically with the invoice.
    unreleased_kitchen = [
        line
        for line, item in line_rows
        if item.type in _KITCHEN_ITEM_TYPES and line.kitchen_released_at is None
    ]
    if unreleased_kitchen:
        highest_round = max(
            (
                int(line.kitchen_round_no or 0)
                for line, _item in line_rows
                if line.kitchen_released_at is not None
            ),
            default=0,
        )
        release_round = highest_round + 1
        for line in unreleased_kitchen:
            line.kitchen_released_at = at
            line.kitchen_round_no = release_round
            line.kitchen_status = "queued"
        order.kitchen_state = "received"
        order.kitchen_ready_at = None

    order.status = "paid"
    order.closed_at = at
    order.invoice_issued_at = at
    await _release_table_if_no_active_orders(session, order)

    await deduct_for_order(
        session,
        order_id=order.id,
        order_lines=list(order_lines),
        branch_id=order.branch_id,
        created_by=actor_user_id,
    )
    if order.customer_phone:
        await _upsert_and_attach_customer(
            session,
            company_id=company_id,
            phone=order.customer_phone,
            name=order.customer_name,
            order=order,
            order_lines=list(order_lines),
        )


# ----------- endpoints -----------
@router.get(
    "/receipt-business",
    response_model=ReceiptBusinessRead,
    summary="Get the current branch's receipt identity",
)
async def get_receipt_business(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> ReceiptBusinessRead:
    """Return only fields needed for billing and receipt rendering.

    Company/branch administration remains protected by ``admin.system``. POS
    users get a projection for their JWT/terminal-resolved branch instead of
    access to integration URLs or payment-provider configuration.
    """

    if tenant.branch_id is None:
        raise BusinessRuleError("No current branch is assigned to this POS session.")

    row = (
        await session.execute(
            select(Company, Branch)
            .join(Branch, Branch.company_id == Company.id)
            .where(
                Company.id == tenant.company_id,
                Company.deleted_at.is_(None),
                Branch.id == tenant.branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("Current POS branch was not found for this company.")
    company, branch = row
    return _receipt_business_read(company, branch)


@router.post(
    "/orders",
    response_model=OrderRead,
    status_code=status.HTTP_201_CREATED,
    summary="Create an order (POS billing entry point)",
)
async def create_order(
    payload: OrderCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    if payload.table_id is not None:
        # A table round crosses both the Tables and POS domains. Requiring
        # both permissions prevents a POS-only cashier from impersonating a
        # waiter and prevents a Tables-only role from creating billable rows.
        await require_permission(session, tenant, "tables.write")
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return OrderRead.model_validate(existing_response["body"])

    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if not payload.lines:
        raise BusinessRuleError("order must have at least one line")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
    _validate_client_line_ids(payload.lines)

    branch = await session.get(Branch, tenant.branch_id)
    if not branch:
        raise NotFoundError("branch not found")
    table: Table | None = None
    if payload.table_id is not None:
        # Lock the table before checking for an unfinished ticket. This makes
        # two simultaneous devices serialize instead of creating duplicates.
        table = (
            await session.execute(
                select(Table).where(Table.id == payload.table_id).with_for_update()
            )
        ).scalar_one_or_none()
        if not table:
            raise NotFoundError("table not found")
        floor = await session.get(Floor, table.floor_id)
        if not floor or floor.branch_id != tenant.branch_id:
            raise NotFoundError("table not found")
        existing_table_order = (
            await session.execute(
                select(Order.id).where(
                    Order.company_id == tenant.company_id,
                    Order.branch_id == tenant.branch_id,
                    Order.table_id == payload.table_id,
                    Order.status.in_(("open", "held")),
                ).limit(1)
            )
        ).scalar_one_or_none()
        if existing_table_order is not None:
            raise BusinessRuleError(
                "This table already has an unfinished order. Open that order instead."
            )
    shift = (
        await session.execute(
            select(Shift)
            .where(Shift.id == payload.shift_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="creating an order",
    )
    if payload.table_id is None:
        # A direct POS bill is recovered only on this cashier's device, so only
        # the accountable shift opener may prepare it. Table-originated work is
        # deliberately collaborative and is later selected/billed by cashier.
        require_shift_opener(
            shift,
            user_id=tenant.user_id,
            protected_access=tenant.protected_access,
            operation="create a direct POS order on this shift",
        )

    branch_state = branch.state_code or "32"
    delivery_via = payload.delivery_via if payload.type == "delivery" else None
    if payload.type == "delivery" and delivery_via is None:
        delivery_via = "inhouse"
    place_of_supply = (
        payload.place_of_supply_state_code
        or (payload.customer_state_code if payload.type == "delivery" else None)
        or branch_state
    )

    # 1. Price the order with the India tax engine.
    pricing = OrderPricingService(session)
    priced = await pricing.price_order(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        customer_phone=payload.customer_phone,
        place_of_supply_state_code=place_of_supply,
        delivery_via=delivery_via,
        line_requests=[_pricing_line_request(line) for line in payload.lines],
    )
    kitchen_item_ids = await _kitchen_item_ids(
        session,
        company_id=tenant.company_id,
        menu_item_ids=[line.menu_item_id for line in payload.lines],
    )

    # 2. Insert an unpaid order. Invoice identity, stock consumption, and
    # loyalty are all finalized atomically when the last payment succeeds.
    opened_at = datetime.now(timezone.utc)
    is_table_round = payload.table_id is not None
    order = Order(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=payload.shift_id,
        opened_by=tenant.user_id,
        table_id=payload.table_id,
        type=payload.type,
        delivery_via=delivery_via,
        status="open",
        opened_at=opened_at,
        subtotal_minor=priced.subtotal_taxable_minor,
        cgst_minor=priced.cgst_minor,
        sgst_minor=priced.sgst_minor,
        igst_minor=priced.igst_minor,
        cess_minor=priced.cess_minor,
        discount_minor=priced.discount_minor,
        tax_minor=priced.cgst_minor + priced.sgst_minor + priced.igst_minor + priced.cess_minor,
        round_off_minor=priced.round_off_minor,
        total_minor=priced.total_minor,
        idempotency_key=idempotency_key,
        invoice_no=None,
        fiscal_year=None,
        place_of_supply_state_code=place_of_supply,
        customer_name=payload.customer_name,
        customer_phone=payload.customer_phone,
        customer_gstin=payload.customer_gstin.upper() if payload.customer_gstin else None,
        customer_address=payload.customer_address,
        customer_state_code=payload.customer_state_code,
        notes=payload.notes,
        kitchen_state="received" if is_table_round and kitchen_item_ids else None,
    )
    session.add(order)
    await session.flush()

    # 3. Insert priced order lines.
    order_lines: list[OrderLine] = []
    for requested_line, priced_line in zip(payload.lines, priced.lines, strict=True):
        ol = OrderLine(
            id=uuid4(),
            order_id=order.id,
            client_line_id=requested_line.client_line_id,
            menu_item_id=priced_line.menu_item_id,
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
            modifiers=(
                [snapshot.as_dict() for snapshot in priced_line.modifier_snapshots]
                or None
            ),
            qty=priced_line.qty,
            unit_price_minor=priced_line.unit_inclusive_minor,
            line_total_minor=priced_line.line_inclusive_minor,
            discount_minor=priced_line.discount_minor,
            hsn_or_sac=priced_line.hsn_or_sac,
            tax_rate=float(priced_line.tax_rate),
            taxable_value_minor=priced_line.taxable_value_minor,
            cgst_minor=priced_line.cgst_minor,
            sgst_minor=priced_line.sgst_minor,
            igst_minor=priced_line.igst_minor,
            cess_minor=priced_line.cess_minor,
            note=requested_line.note,
            kitchen_status="queued",
            kitchen_released_at=(
                opened_at
                if is_table_round and priced_line.menu_item_id in kitchen_item_ids
                else None
            ),
            kitchen_round_no=(
                1
                if is_table_round and priced_line.menu_item_id in kitchen_item_ids
                else None
            ),
            created_at=opened_at,
        )
        session.add(ol)
        order_lines.append(ol)

    if table and table.status == "available":
        table.status = "occupied"

    await session.flush()
    await session.refresh(order, attribute_names=["checkout_version"])
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


class OrderLinesAppend(BaseModel):
    expected_checkout_version: int = Field(ge=1)
    lines: list[OrderLineCreate] = Field(min_length=1, max_length=100)


@router.post("/orders/{order_id}/lines", response_model=OrderRead)
async def add_order_lines(
    order_id: UUID,
    payload: OrderLinesAppend,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("tables.write", "pos.write")),
) -> OrderRead:
    """Append lines to an order that hasn't been paid yet.

    Used only by Tables to release a later dine-in round. Once a bill is held,
    its contents are immutable and POS may only claim and settle it.
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
        return OrderRead.model_validate(existing_response["body"])

    _validate_client_line_ids(payload.lines)

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="adding items to an order",
    )
    if order.status != "open":
        if order.status == "held":
            raise BusinessRuleError(
                "This bill has already been sent to POS and its items are locked. "
                "Finish or void it from POS."
            )
        raise BusinessRuleError(f"cannot add lines to an order in status={order.status}")
    if order.table_id is None:
        raise BusinessRuleError(
            "Only an open table bill accepts another service round. Start a new "
            "direct POS bill instead."
        )
    _require_checkout_version(
        order,
        payload.expected_checkout_version,
        operation="adding this table round",
    )
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="add items to this order",
    )
    await _reject_persisted_client_line_ids(
        session,
        order_id=order.id,
        lines=payload.lines,
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="adding items to an order",
    )
    pricing = OrderPricingService(session)
    priced = await pricing.price_order(
        company_id=tenant.company_id,
        branch_id=order.branch_id,
        customer_phone=order.customer_phone,
        place_of_supply_state_code=order.place_of_supply_state_code,
        delivery_via=order.delivery_via,
        line_requests=[_pricing_line_request(line) for line in payload.lines],
    )
    kitchen_item_ids = await _kitchen_item_ids(
        session,
        company_id=tenant.company_id,
        menu_item_ids=[line.menu_item_id for line in payload.lines],
    )
    if kitchen_item_ids:
        await _materialize_legacy_kitchen_line_state(session, order)
    release_round = int(
        (
            await session.execute(
                select(func.coalesce(func.max(OrderLine.kitchen_round_no), 0)).where(
                    OrderLine.order_id == order.id
                )
            )
        ).scalar_one()
        or 0
    ) + 1
    released_at = datetime.now(timezone.utc)
    for requested_line, priced_line in zip(payload.lines, priced.lines, strict=True):
        session.add(OrderLine(
            id=uuid4(),
            order_id=order.id,
            client_line_id=requested_line.client_line_id,
            menu_item_id=priced_line.menu_item_id,
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
            modifiers=(
                [snapshot.as_dict() for snapshot in priced_line.modifier_snapshots]
                or None
            ),
            qty=priced_line.qty,
            unit_price_minor=priced_line.unit_inclusive_minor,
            line_total_minor=priced_line.line_inclusive_minor,
            discount_minor=priced_line.discount_minor,
            hsn_or_sac=priced_line.hsn_or_sac,
            tax_rate=float(priced_line.tax_rate),
            taxable_value_minor=priced_line.taxable_value_minor,
            cgst_minor=priced_line.cgst_minor,
            sgst_minor=priced_line.sgst_minor,
            igst_minor=priced_line.igst_minor,
            cess_minor=priced_line.cess_minor,
            note=requested_line.note,
            kitchen_status="queued",
            kitchen_released_at=(
                released_at if priced_line.menu_item_id in kitchen_item_ids else None
            ),
            kitchen_round_no=(
                release_round if priced_line.menu_item_id in kitchen_item_ids else None
            ),
            created_at=released_at,
        ))

    if kitchen_item_ids:
        # A late kitchen item must become visible again even if the prior
        # batch for this ticket had already reached ready/served.
        order.kitchen_state = "received"
        order.kitchen_ready_at = None
    await session.flush()

    await _reaggregate_active_order_lines(
        session,
        order=order,
        company_id=tenant.company_id,
    )

    if order.table_id is not None:
        table = await session.get(Table, order.table_id)
        if table and table.status == "available":
            table.status = "occupied"

    await session.flush()
    await session.refresh(order, attribute_names=["checkout_version"])
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/customer", response_model=OrderRead)
async def attach_order_customer(
    order_id: UUID,
    payload: OrderCustomerUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Attach a customer/member and deterministically reprice an unpaid bill.

    This is the POS handoff step for Tables and Gaming/Shisha orders, whose
    operational originator may not know the final paying customer. Only the
    accountable shift opener (or protected owner) may change the bill here.
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
        return OrderRead.model_validate(existing_response["body"])

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="attaching a customer to an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change the customer on an order in status={order.status}"
        )
    _require_settlement_metadata_version(
        order,
        payload.expected_checkout_version,
        operation="changing its customer",
    )
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="change the customer on this order",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="attaching a customer to an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="attach a customer or membership to this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change the customer after a payment was recorded")

    previous_phone = (order.customer_phone or "").strip() or None
    order.customer_name = payload.customer_name
    order.customer_phone = payload.customer_phone
    if payload.customer_phone:
        order.customer_id = (
            await session.execute(
                select(Customer.id).where(
                    Customer.company_id == tenant.company_id,
                    Customer.phone == payload.customer_phone,
                    Customer.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
    else:
        order.customer_id = None
    if order.customer_phone != previous_phone:
        await _reprice_unpaid_order_for_customer(
            session,
            order=order,
            company_id=tenant.company_id,
        )
    await session.flush()
    await session.refresh(order, attribute_names=["checkout_version"])
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/discount", response_model=OrderRead)
async def apply_order_discount(
    order_id: UUID,
    payload: OrderDiscountUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Set (or clear) a cashier-entered custom discount on an unpaid bill.

    This company is GST-unregistered, so there is no proportional tax
    recalculation here — the amount is knocked straight off the final total,
    same as an unregistered business would do on a plain cash memo.
    """
    # Manual discounts are discretionary money reductions. Every non-zero
    # value therefore needs the existing high-trust permission; there is no
    # implicit cashier allowance. Clearing a discount remains available to
    # the shift opener with the route's normal pos.write permission.
    if payload.manual_discount_minor > 0:
        await require_permission(session, tenant, "pos.discount.large")

    # Authorisation deliberately precedes reservation. A denied request must
    # not consume an idempotency key that an authorised owner may later use.
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return OrderRead.model_validate(existing_response["body"])

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="applying a discount to an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change the discount on an order in status={order.status}"
        )
    _require_settlement_metadata_version(
        order,
        payload.expected_checkout_version,
        operation="changing its discount",
    )
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="change the discount on this order",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="applying a discount to an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="apply a discount to this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change the discount after a payment was recorded")

    previous_manual = int(order.manual_discount_minor or 0)
    previous_points_minor = int(order.points_redeemed_minor or 0)
    line_discount_only = int(order.discount_minor or 0) - previous_manual - previous_points_minor
    pre_reduction_total = int(order.total_minor or 0) + previous_manual + previous_points_minor
    new_manual = payload.manual_discount_minor
    if new_manual > pre_reduction_total:
        raise BusinessRuleError("discount cannot exceed the order total")

    order.manual_discount_minor = new_manual
    remaining_after_manual = pre_reduction_total - new_manual

    # A points redemption already on this bill must be re-clamped against
    # whatever room the new discount leaves — it can shrink, never grow,
    # from what was previously reserved.
    previously_redeemed_points = await points_redeemed_for_order(session, order=order)
    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=tenant.company_id,
        requested_points=min(previously_redeemed_points, minor_to_points(remaining_after_manual)),
        at=datetime.now(timezone.utc),
    )
    order.points_redeemed_minor = points_result.amount_minor
    order.discount_minor = line_discount_only + new_manual + points_result.amount_minor
    order.total_minor = remaining_after_manual - points_result.amount_minor

    await session.flush()
    await session.refresh(order, attribute_names=["checkout_version"])
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/points", response_model=OrderRead)
async def redeem_points(
    order_id: UUID,
    payload: OrderPointsRedemptionUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Set (or clear) how many loyalty points a customer spends on this bill.

    Points convert to playtime value at a fixed rate (see
    app/services/pos/points.py) and come straight off the total, same as the
    manual discount above. Requires a customer already attached to the order
    — points belong to a specific phone number's balance, never anonymous.
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
        return OrderRead.model_validate(existing_response["body"])

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming points on an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change points on an order in status={order.status}"
        )
    _require_settlement_metadata_version(
        order,
        payload.expected_checkout_version,
        operation="changing its points redemption",
    )
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="change points redemption on this order",
    )
    if not order.customer_phone:
        raise BusinessRuleError("attach a customer to this order before redeeming points")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming points on an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="redeem points on this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change points redemption after a payment was recorded")

    previous_manual = int(order.manual_discount_minor or 0)
    previous_points_minor = int(order.points_redeemed_minor or 0)
    line_discount_only = int(order.discount_minor or 0) - previous_manual - previous_points_minor
    remaining_after_manual = int(order.total_minor or 0) + previous_points_minor

    max_points_for_bill = minor_to_points(remaining_after_manual)
    if payload.points > max_points_for_bill:
        raise BusinessRuleError(
            f"This bill can only absorb {max_points_for_bill} points "
            f"(₹{remaining_after_manual / 100:.2f})."
        )

    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=tenant.company_id,
        requested_points=payload.points,
        at=datetime.now(timezone.utc),
    )
    if points_result.points_spent < payload.points:
        raise BusinessRuleError("Not enough points available on this customer's balance.")

    order.points_redeemed_minor = points_result.amount_minor
    order.discount_minor = line_discount_only + previous_manual + points_result.amount_minor
    order.total_minor = remaining_after_manual - points_result.amount_minor

    await session.flush()
    await session.refresh(order, attribute_names=["checkout_version"])
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


class OrderRewardRedemptionUpdate(BaseModel):
    reward_key: str
    expected_checkout_version: int | None = Field(default=None, ge=1)


@router.patch("/orders/{order_id}/reward", response_model=OrderRead)
async def redeem_reward(
    order_id: UUID,
    payload: OrderRewardRedemptionUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Redeem a named REWARD_CATALOG item (see app/services/pos/points.py) —
    a fixed points cost for a fixed discount value, gated by the customer's
    gaming rank. All-or-nothing, unlike the generic points endpoint above.
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
        return OrderRead.model_validate(existing_response["body"])

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming a reward on an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change a reward on an order in status={order.status}"
        )
    _require_settlement_metadata_version(
        order,
        payload.expected_checkout_version,
        operation="changing its reward redemption",
    )
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="change reward redemption on this order",
    )
    if not order.customer_phone:
        raise BusinessRuleError("attach a customer to this order before redeeming a reward")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming a reward on an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="redeem a reward on this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change reward redemption after a payment was recorded")

    previous_manual = int(order.manual_discount_minor or 0)
    previous_points_minor = int(order.points_redeemed_minor or 0)
    line_discount_only = int(order.discount_minor or 0) - previous_manual - previous_points_minor
    remaining_after_manual = int(order.total_minor or 0) + previous_points_minor

    # reserve_catalog_reward_redemption checks the reward's value against
    # order.total_minor as "remaining bill room" — same convention the manual
    # discount / generic points paths use, so set it to the post-manual-
    # discount remainder (pre-existing-points-redemption) before calling it.
    order.total_minor = remaining_after_manual
    points_result = await reserve_catalog_reward_redemption(
        session,
        order=order,
        company_id=tenant.company_id,
        reward_key=payload.reward_key,
        at=datetime.now(timezone.utc),
    )

    order.points_redeemed_minor = points_result.amount_minor
    order.discount_minor = line_discount_only + previous_manual + points_result.amount_minor
    order.total_minor = remaining_after_manual - points_result.amount_minor

    await session.flush()
    await session.refresh(order, attribute_names=["checkout_version"])
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


class SendOrderToPosRequest(BaseModel):
    expected_checkout_version: int = Field(ge=1)


class VoidOrderLineRequest(BaseModel):
    expected_checkout_version: int = Field(ge=1)
    reason: str = Field(min_length=1, max_length=500)

    @field_validator("reason")
    @classmethod
    def normalize_reason(cls, value: str) -> str:
        clean = value.strip()
        if not clean:
            raise ValueError("reason must not be blank")
        if clean in _LEGACY_VOID_REASONS:
            raise ValueError("reason is reserved for migrated legacy records")
        return clean


@router.post("/orders/{order_id}/lines/{line_id}/void", response_model=OrderRead)
async def void_order_line(
    order_id: UUID,
    line_id: UUID,
    payload: VoidOrderLineRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("tables.write", "pos.void")),
) -> OrderRead:
    """Reasoned whole-line cancellation for an open table bill.

    Released work remains a KDS cancellation until kitchen acknowledges it.
    Quantities are never partially rewritten: a smaller replacement is a new
    stable line action, keeping the original preparation evidence intact.
    """
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Select this tablet's branch and POS terminal before editing Tables."
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
        return OrderRead.model_validate(existing_response["body"])

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="cancelling a table item",
    )
    if order.status != "open" or order.table_id is None:
        if order.status == "held":
            raise BusinessRuleError(
                "This bill is already frozen in POS. Void the whole bill there or "
                "finish payment; individual items can no longer change."
            )
        raise BusinessRuleError("Only an open table bill can cancel an item.")
    _require_checkout_version(
        order,
        payload.expected_checkout_version,
        operation="cancelling this item",
    )
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="cancel this table item",
    )
    line = (
        await session.execute(
            select(OrderLine)
            .where(OrderLine.id == line_id, OrderLine.order_id == order.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if line is None:
        raise NotFoundError("This item is no longer part of the table bill.")

    if line.voided_at is not None:
        if (line.void_reason or "").strip() != payload.reason:
            raise BusinessRuleError(
                "This item was already cancelled with a different reason. Reload the bill."
            )
        response = await _build_order_read(session, order)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_200_OK,
            body=response.model_dump(mode="json"),
        )
        return response

    if line.kitchen_released_at is not None:
        await _materialize_legacy_kitchen_line_state(session, order)
        if (line.kitchen_status or "queued") == "served":
            raise BusinessRuleError(
                "Kitchen already marked this item served, so it cannot be cancelled. "
                "Use the protected refund workflow after billing if money must be returned."
            )

    shift = (
        await session.execute(select(Shift).where(Shift.id == order.shift_id))
    ).scalar_one_or_none()
    require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="cancelling a table item",
    )
    active_count = int(
        (
            await session.execute(
                select(func.count(OrderLine.id)).where(
                    OrderLine.order_id == order.id,
                    OrderLine.voided_at.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if active_count <= 1:
        raise BusinessRuleError(
            "This is the last active item. Void the whole bill with a reason instead "
            "of leaving an empty live table bill."
        )

    now = datetime.now(timezone.utc)
    line.voided_at = now
    line.voided_by = tenant.user_id
    line.void_reason = payload.reason
    line.kitchen_void_acknowledged_at = None
    line.kitchen_void_acknowledged_by = None
    await session.flush()
    await _reaggregate_active_order_lines(
        session,
        order=order,
        company_id=tenant.company_id,
    )
    await session.flush()
    await session.refresh(order, attribute_names=["checkout_version"])
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/send-to-pos", response_model=OrderRead)
async def send_order_to_pos(
    order_id: UUID,
    payload: SendOrderToPosRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("tables.write", "pos.write")),
) -> OrderRead:
    """Freeze one current table-bill snapshot into the POS held queue."""
    if tenant.terminal_id is None:
        raise BusinessRuleError("Select this tablet's POS terminal first.")
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return OrderRead.model_validate(existing_response["body"])

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending an order to POS",
    )
    if order.status != "open":
        if order.status == "held":
            raise BusinessRuleError(
                "This table bill is already waiting in POS. Select it there to bill."
            )
        raise BusinessRuleError(f"cannot send an order in status={order.status} to POS")
    if order.table_id is None:
        raise BusinessRuleError("Only a table bill can use Send to POS.")
    _require_checkout_version(
        order,
        payload.expected_checkout_version,
        operation="sending it to POS",
    )
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="send this order to POS",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending an order to POS",
    )
    has_lines = (
        await session.execute(
            select(func.count()).select_from(OrderLine).where(OrderLine.order_id == order.id)
            .where(OrderLine.voided_at.is_(None))
        )
    ).scalar_one()
    if not has_lines:
        raise BusinessRuleError("order has no items")
    order.status = "held"
    order.held_at = datetime.now(timezone.utc)
    await session.flush()
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get("/table-orders/active", response_model=list[OrderRead])
async def list_active_table_orders(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("tables.read")),
) -> list[OrderRead]:
    """Return editable and at-POS table bills for this exact till scope.

    ``status=open`` is still editable in Tables. ``status=held`` is returned
    read-only so an occupied table never becomes an unexplained dead end after
    handoff to the cashier.
    """
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select this tablet's branch and POS terminal before opening Tables."
        )
    orders = (
        await session.execute(
            select(Order)
            .where(
                Order.company_id == tenant.company_id,
                Order.branch_id == tenant.branch_id,
                Order.terminal_id == tenant.terminal_id,
                Order.table_id.is_not(None),
                Order.status.in_(("open", "held")),
            )
            .order_by(Order.opened_at, Order.id)
        )
    ).scalars().all()
    return [await _build_order_read(session, order) for order in orders]


@router.post(
    "/orders/{order_id}/checkout-claim",
    response_model=CheckoutClaimRead,
    status_code=status.HTTP_201_CREATED,
)
async def claim_order_for_checkout(
    order_id: UUID,
    session: SessionDep,
    response: Response,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> CheckoutClaimRead:
    """Lease one shared held bill to the current cashier and terminal.

    The order lock is the serialization point for claim, payment, repricing,
    void, and finalization.  A second request cannot observe or overwrite a
    half-created claim.
    """
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS checkout")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="claiming an order for checkout",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="claiming an order for checkout",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="claim an order for checkout on this shift",
    )
    paid_minor = await _paid_total(session, order.id)
    grant = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        paid_minor=paid_minor,
    )
    claim = grant.claim
    # The body carries a short-lived bearer credential.  Browsers, reverse
    # proxies, and diagnostic caches must never retain it.
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"
    return CheckoutClaimRead(
        claim_id=claim.id,
        order_id=claim.order_id,
        claim_token=grant.token,
        expires_at=claim.expires_at,
        order_total_minor=int(claim.order_total_minor),
        paid_minor=grant.paid_minor,
        due_minor=int(claim.due_minor),
        order_version=int(claim.order_version),
        claimant_user_id=claim.claimed_by_user_id,
        terminal_id=claim.terminal_id,
        reused=grant.reused,
    )


@router.delete(
    "/orders/{order_id}/checkout-claim",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def unclaim_order_checkout(
    order_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
    checkout_claim_token: Annotated[
        str | None,
        Header(alias="X-Checkout-Claim"),
    ] = None,
) -> None:
    """Release the current cashier's lease when they leave checkout."""
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS checkout")
    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="releasing an order checkout claim",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="releasing an order checkout claim",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="release an order checkout claim on this shift",
    )
    await release_checkout_claim(
        session,
        order=order,
        claimant_user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        token=checkout_claim_token,
    )


class VoidOrderRequest(BaseModel):
    reason: str = Field(min_length=1, max_length=500)

    @field_validator("reason")
    @classmethod
    def normalize_reason(cls, value: str) -> str:
        clean = value.strip()
        if not clean:
            raise ValueError("reason must not be blank")
        if clean in _LEGACY_VOID_REASONS:
            raise ValueError("reason is reserved for migrated legacy records")
        return clean


@router.delete("/orders/{order_id}", status_code=status.HTTP_204_NO_CONTENT)
async def void_held_order(
    order_id: UUID,
    payload: VoidOrderRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.void")),
) -> None:
    """Clear a held order that shouldn't be billed (mistake, duplicate,
    customer walked out). Only the shift's opener or a protected owner may
    do this — same accountability rule as billing it.
    """
    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="voiding an order",
    )
    if order.status == "void":
        # A lost response followed by the same command is harmless, but a new
        # reason is not the same business action. Compare against the latest
        # cancellation batch: whole-order void stamps every then-active line
        # with one timestamp/reason while preserving older line cancellations.
        latest_void_at = (
            await session.execute(
                select(func.max(OrderLine.voided_at)).where(
                    OrderLine.order_id == order.id,
                    OrderLine.voided_at.is_not(None),
                )
            )
        ).scalar_one_or_none()
        latest_reasons: set[str | None] = set()
        if latest_void_at is not None:
            latest_reasons = set(
                (
                    await session.execute(
                        select(OrderLine.void_reason)
                        .where(
                            OrderLine.order_id == order.id,
                            OrderLine.voided_at == latest_void_at,
                        )
                        .distinct()
                    )
                ).scalars().all()
            )
        if latest_reasons == {payload.reason}:
            return None
        recorded = next(iter(latest_reasons), None)
        detail = (
            f' The recorded reason is "{recorded}".'
            if len(latest_reasons) == 1 and recorded
            else " Its original reason cannot be changed or safely reconstructed."
        )
        raise BusinessRuleError(
            "This order was already voided with a different reason."
            f"{detail} Reload order history before taking another action."
        )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(f"cannot clear an order in status={order.status}")
    await guard_checkout_relevant_mutation(
        session,
        order=order,
        operation="void this order",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="clearing a held order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="clear an order on this shift",
    )
    active_lines = (
        await session.execute(
            select(OrderLine)
            .where(
                OrderLine.order_id == order.id,
                OrderLine.voided_at.is_(None),
            )
            .order_by(OrderLine.id)
            .with_for_update()
        )
    ).scalars().all()
    if any(
        line.kitchen_released_at is not None
        and (line.kitchen_status or "queued") == "served"
        for line in active_lines
    ):
        raise BusinessRuleError(
            "At least one kitchen item was already served. Finish the bill and use "
            "the protected refund workflow instead of voiding its service history."
        )
    now = datetime.now(timezone.utc)
    for line in active_lines:
        line.voided_at = now
        line.voided_by = tenant.user_id
        line.void_reason = payload.reason
        line.kitchen_void_acknowledged_at = None
        line.kitchen_void_acknowledged_by = None
    order.status = "void"
    order.notes = f"{order.notes + ' — ' if order.notes else ''}Voided: {payload.reason}"[:500]
    await _release_table_if_no_active_orders(session, order)
    await session.flush()


@router.get("/orders/{order_id}", response_model=OrderRead)
async def get_order(
    order_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> OrderRead:
    order = await session.get(Order, order_id)
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="viewing an order",
    )
    return await _build_order_read(session, order)


class OrderListItem(BaseModel):
    """Slim row for the order-history list."""
    id: UUID
    invoice_no: str | None
    type: str
    status: str
    table_id: UUID | None = None
    source_label: str | None = None
    total_minor: int
    items_count: int
    customer_name: str | None
    created_at: datetime
    held_at: datetime | None = None
    # Checkout claims snapshot this database-maintained version. Shared POS
    # clients must compare the list row against the claim they just acquired;
    # omitting it makes every version-bumped Tables/Gaming bill look stale.
    checkout_version: int
    # What was actually collected, and what's still left to give back after
    # every refund already issued — the Refunds screen is the only consumer
    # of these two, added so it has something real to net against instead of
    # trusting a raw gross figure across repeat partial refunds.
    paid_minor: int = 0
    refundable_minor: int = 0
    pending_refund_minor: int = 0


@router.get("/orders", response_model=list[OrderListItem])
async def list_orders(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    status_filter: list[str] | None = Query(default=None, alias="status"),
    table_id: UUID | None = None,
    limit: int = 200,
) -> list[OrderListItem]:
    """List orders, newest first.

    Defaults to today if no date filter given. When `status` is passed
    (e.g. the POS held-orders queue), the date window is skipped entirely —
    a held order shouldn't vanish from the queue just because it crossed
    local midnight.
    """
    if tenant.branch_id is None:
        raise BusinessRuleError(
            "This account has no branch assigned. Assign one before viewing orders."
        )
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required before viewing orders.")
    stmt = select(Order).where(
        Order.company_id == tenant.company_id,
        Order.branch_id == tenant.branch_id,
        Order.terminal_id == tenant.terminal_id,
    )
    if status_filter:
        stmt = stmt.where(Order.status.in_(status_filter))
    else:
        timezone_name = await company_timezone(session, tenant.company_id)
        today = local_today(timezone_name)
        f_d = from_date or today
        t_d = to_date or today
        f_dt, t_dt = local_date_bounds_utc(f_d, t_d, timezone_name)
        stmt = stmt.where(Order.created_at >= f_dt, Order.created_at < t_dt)
    if table_id is not None:
        stmt = stmt.where(Order.table_id == table_id)
    if status_filter and set(status_filter) == {"held"}:
        stmt = stmt.order_by(
            func.coalesce(Order.held_at, Order.created_at),
            Order.created_at,
        )
    else:
        stmt = stmt.order_by(Order.created_at.desc())
    stmt = stmt.limit(min(limit, 500))

    rows = (await session.execute(stmt)).scalars().all()
    if not rows:
        return []

    order_ids = [o.id for o in rows]
    counts_by_order = dict(
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
    table_ids = [o.table_id for o in rows if o.table_id is not None]
    codes_by_table = dict(
        (
            await session.execute(select(Table.id, Table.code).where(Table.id.in_(table_ids)))
        ).all()
    ) if table_ids else {}
    station_by_order = dict(
        (
            await session.execute(
                select(GamingSession.order_id, Station.name)
                .join(Station, Station.id == GamingSession.station_id)
                .where(GamingSession.order_id.in_(order_ids))
            )
        ).all()
    )
    paid_by_order = dict(
        (
            await session.execute(
                select(Payment.order_id, func.coalesce(func.sum(Payment.amount_minor), 0))
                .where(Payment.order_id.in_(order_ids))
                .group_by(Payment.order_id)
            )
        ).all()
    )
    refunded_by_order = dict(
        (
            await session.execute(
                select(Refund.order_id, func.coalesce(func.sum(Refund.amount_minor), 0))
                .where(Refund.order_id.in_(order_ids))
                .group_by(Refund.order_id)
            )
        ).all()
    )
    reserved_by_order = dict(
        (
            await session.execute(
                select(
                    PosRefundRequest.order_id,
                    func.coalesce(func.sum(PosRefundRequest.amount_minor), 0),
                )
                .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
                .outerjoin(
                    PosRefundWithdrawal,
                    PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
                )
                .where(
                    PosRefundRequest.order_id.in_(order_ids),
                    Refund.id.is_(None),
                    PosRefundWithdrawal.id.is_(None),
                )
                .group_by(PosRefundRequest.order_id)
            )
        ).all()
    )

    out: list[OrderListItem] = []
    for o in rows:
        if o.table_id is not None and o.table_id in codes_by_table:
            label = f"Table {codes_by_table[o.table_id]}"
        else:
            label = station_by_order.get(o.id)
        paid = int(paid_by_order.get(o.id, 0))
        refunded = int(refunded_by_order.get(o.id, 0))
        reserved = int(reserved_by_order.get(o.id, 0))
        out.append(OrderListItem(
            id=o.id,
            invoice_no=o.invoice_no,
            type=o.type,
            status=o.status,
            table_id=o.table_id,
            source_label=label,
            total_minor=o.total_minor,
            items_count=int(counts_by_order.get(o.id, 0)),
            customer_name=o.customer_name,
            created_at=o.created_at,
            held_at=o.held_at,
            checkout_version=int(o.checkout_version),
            paid_minor=paid,
            refundable_minor=max(0, paid - refunded - reserved),
            pending_refund_minor=reserved,
        ))
    return out


class ShiftRead(BaseModel):
    id: UUID
    branch_id: UUID
    terminal_id: UUID | None = None
    status: str
    opened_at: datetime
    closed_at: datetime | None
    opening_float_minor: int
    expected_minor: int | None
    counted_minor: int | None
    variance_minor: int | None
    # Sum of Payment.amount_minor across ALL methods for this shift — unlike
    # expected_minor (cash-drawer float, cash payments only, used for till
    # reconciliation), this is the actual total sold through the POS. Naturally
    # resets to 0 for the next shift since it's scoped by shift_id, not a
    # running total.
    pos_sales_minor: int
    # Paid membership terms collected on this exact shift. Kept separate from
    # POS item sales while still contributing to the operational shift total.
    membership_sales_minor: int
    # Accurate name for Payment receipts + membership receipts. Payment rows
    # may include tips and this is before refunds, so this is not net sales.
    gross_collections_minor: int
    settled_pos_refunds_minor: int
    settled_membership_refunds_minor: int
    total_refunds_minor: int
    net_collections_minor: int
    # Backward-compatible alias for older clients. New UI must label it gross
    # collections, never net sales.
    total_sales_minor: int
    opened_by: UUID
    opened_by_name: str | None = None
    opened_by_email: str | None = None


@router.get("/shifts", response_model=list[ShiftRead])
async def list_shifts(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    only_open: bool = False,
    limit: int = 50,
) -> list[ShiftRead]:
    """Return till reconciliation and collection totals without conflating them.

    ``expected_minor`` remains the physical cash-drawer expectation maintained
    by the payment/refund write paths.  Collection totals are instead derived
    from immutable payment and *settlement* facts for this exact shift.  In
    particular, accepted or in-progress refund requests are reservations, not
    money that has left the business, so they must never reduce net
    collections here.
    """
    sales_subq = (
        select(func.coalesce(func.sum(Payment.amount_minor), 0))
        .where(Payment.shift_id == Shift.id)
        .correlate(Shift)
        .scalar_subquery()
    )
    membership_sales_subq = (
        select(func.coalesce(func.sum(MembershipPayment.amount_minor), 0))
        .where(MembershipPayment.shift_id == Shift.id)
        .correlate(Shift)
        .scalar_subquery()
    )
    membership_refunds_subq = (
        select(func.coalesce(func.sum(MembershipRefundSettlement.amount_minor), 0))
        .where(MembershipRefundSettlement.shift_id == Shift.id)
        .correlate(Shift)
        .scalar_subquery()
    )
    pos_refunds_subq = (
        select(func.coalesce(func.sum(Refund.amount_minor), 0))
        .where(Refund.settlement_shift_id == Shift.id)
        .correlate(Shift)
        .scalar_subquery()
    )
    stmt = (
        select(
            Shift,
            User.name,
            User.email,
            sales_subq.label("pos_sales"),
            membership_sales_subq.label("membership_sales"),
            pos_refunds_subq.label("pos_refunds"),
            membership_refunds_subq.label("membership_refunds"),
        )
        .outerjoin(User, User.id == Shift.opened_by)
        .where(Shift.company_id == tenant.company_id)
        .order_by(Shift.opened_at.desc())
        .limit(min(limit, 200))
    )
    if tenant.branch_id is not None:
        stmt = stmt.where(Shift.branch_id == tenant.branch_id)
    if tenant.terminal_id is not None:
        stmt = stmt.where(Shift.terminal_id == tenant.terminal_id)
    if only_open:
        stmt = stmt.where(Shift.status == "open")
    rows = (await session.execute(stmt)).all()
    result = []
    for (
        s,
        opener_name,
        opener_email,
        pos_sales_value,
        membership_sales_value,
        pos_refunds_value,
        membership_refunds_value,
    ) in rows:
        pos_sales = int(pos_sales_value or 0)
        membership_sales = int(membership_sales_value or 0)
        gross_collections = pos_sales + membership_sales
        pos_refunds = int(pos_refunds_value or 0)
        membership_refunds = int(membership_refunds_value or 0)
        total_refunds = pos_refunds + membership_refunds
        result.append(
            ShiftRead(
                id=s.id, branch_id=s.branch_id, terminal_id=s.terminal_id, status=s.status,
                opened_at=s.opened_at, closed_at=s.closed_at,
                opening_float_minor=int(s.opening_float_minor or 0),
                expected_minor=int(s.expected_minor) if s.expected_minor is not None else None,
                counted_minor=int(s.counted_minor) if s.counted_minor is not None else None,
                variance_minor=int(s.variance_minor) if s.variance_minor is not None else None,
                pos_sales_minor=pos_sales,
                membership_sales_minor=membership_sales,
                gross_collections_minor=gross_collections,
                settled_pos_refunds_minor=pos_refunds,
                settled_membership_refunds_minor=membership_refunds,
                total_refunds_minor=total_refunds,
                net_collections_minor=gross_collections - total_refunds,
                total_sales_minor=gross_collections,
                opened_by=s.opened_by,
                opened_by_name=opener_name,
                opened_by_email=opener_email,
            )
        )
    return result


def _zero_total_finalization_response(order: Order) -> dict:
    return {
        "order_id": str(order.id),
        "amount_minor": 0,
        "order_status": order.status,
        "invoice_no": order.invoice_no,
        "fiscal_year": order.fiscal_year,
        "invoice_issued_at": order.invoice_issued_at.isoformat()
        if order.invoice_issued_at
        else None,
    }


@router.post("/orders/{order_id}/finalize-zero", status_code=status.HTTP_200_OK)
async def finalize_zero_total_order(
    order_id: UUID,
    session: SessionDep,
    request: Request,
    background_tasks: BackgroundTasks,
    tenant: TenantContext = Depends(requires("pos.write")),
    checkout_claim_token: Annotated[
        str | None,
        Header(alias="X-Checkout-Claim"),
    ] = None,
) -> dict:
    """Settle an exact-zero bill without inventing a zero-value payment.

    This is primarily for a PS5/Shisha bill fully covered by a reserved member
    allowance. It remains a high-trust money action: only the shift opener or a
    protected owner may finalize it, and an Idempotency-Key is mandatory.
    """
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return existing_response["body"]

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="finalizing a zero-total order",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    if order.status == "paid":
        # A new idempotency key is still a harmless read of the already-issued
        # zero invoice. The original key is normally replayed above.
        shift = require_operational_shift_scope(
            shift,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="reviewing a finalized zero-total order",
        )
    else:
        shift = require_open_operational_shift(
            shift,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="finalizing a zero-total order",
        )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="finalize a zero-total order on this shift",
    )
    if order.branch_id != shift.branch_id or order.terminal_id != shift.terminal_id:
        raise BusinessRuleError("Order branch or terminal does not match its shift.")

    paid_total = await _paid_total(session, order.id)
    if int(order.total_minor or 0) != 0 or paid_total != 0:
        raise BusinessRuleError(
            "Only an unpaid order with an exact zero balance can use zero-total finalization."
        )
    checkout_claim = await validate_checkout_claim(
        session,
        order=order,
        claimant_user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        paid_minor=paid_total,
        token=checkout_claim_token,
    )
    if order.status == "paid":
        if not order.invoice_no or not order.invoice_issued_at:
            raise BusinessRuleError(
                "Paid zero-total order is missing its invoice; "
                "ask a protected owner to reconcile it."
            )
    else:
        if order.status not in ("open", "held"):
            raise BusinessRuleError(
                f"cannot finalize an order in status={order.status}"
            )
        now = datetime.now(timezone.utc)
        await _finalize_order(
            session,
            order=order,
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            at=now,
        )
        _schedule_order_paid_event(
            background_tasks,
            company_id=tenant.company_id,
            branch_id=order.branch_id,
            order_id=order.id,
            total_minor=0,
            method="zero_total",
            occurred_at=now,
        )

    response = _zero_total_finalization_response(order)
    # The claim and the membership-covered invoice are consumed in one
    # transaction. A later flush/commit failure rolls both operations back.
    await consume_checkout_claim(session, checkout_claim)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response,
    )
    return response


def _schedule_order_paid_event(
    background_tasks: BackgroundTasks,
    *,
    company_id: UUID,
    branch_id: UUID,
    order_id: UUID,
    total_minor: int,
    method: str,
    occurred_at: datetime,
) -> None:
    """Fire OrderPaid on the event bus once the response has been sent.

    Scheduled via FastAPI's BackgroundTasks rather than awaited (or even
    asyncio.create_task'd) inline: Starlette only runs background tasks
    after the response body has been sent, which is after this request's
    SessionDep has already committed (see app/core/db.py get_session). That
    matters here — handlers like the Google Sheets mirror
    (app/services/integrations/google_sheets.py on_order_paid) open their
    own DB session and must see the order in its final committed "paid"
    state, not a pre-commit snapshot that could still roll back.

    Wrapped in its own try/except so a failure constructing or publishing
    the event can never surface to the client — the payment itself already
    succeeded by the time this runs. Mirrors how RealtimeBroadcastMiddleware
    treats its own non-critical side effect (see app/core/middleware.py).
    """

    async def _publish() -> None:
        try:
            await get_event_bus().publish(
                OrderPaid(
                    occurred_at=occurred_at,
                    company_id=company_id,
                    branch_id=branch_id,
                    order_id=order_id,
                    total_minor=total_minor,
                    method=method,
                )
            )
        except Exception:  # noqa: BLE001
            log.warning(
                "pos.order_paid_event.publish_failed",
                order_id=str(order_id),
                exc_info=True,
            )

    background_tasks.add_task(_publish)


@router.post(
    "/orders/{order_id}/payments",
    response_model=PaymentRead,
    status_code=status.HTTP_201_CREATED,
)
async def record_payment(
    order_id: UUID,
    payload: PaymentCreate,
    session: SessionDep,
    request: Request,
    background_tasks: BackgroundTasks,
    tenant: TenantContext = Depends(requires("pos.write")),
    checkout_claim_token: Annotated[
        str | None,
        Header(alias="X-Checkout-Claim"),
    ] = None,
) -> PaymentRead:
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return await _payment_read_from_stored_response(
            session,
            body=existing_response["body"],
            order_id=order_id,
        )

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="recording a payment",
    )
    if order.status in {"paid", "void", "refunded"}:
        raise BusinessRuleError(f"cannot pay an order in status={order.status}")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="recording a payment",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="bill an order on this shift",
    )
    if order.branch_id != shift.branch_id or order.terminal_id != shift.terminal_id:
        raise BusinessRuleError("Order branch or terminal does not match its shift.")

    already_paid = await _paid_total(session, order_id)
    due_minor = max(0, int(order.total_minor or 0) - already_paid)
    checkout_claim = await validate_checkout_claim(
        session,
        order=order,
        claimant_user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        paid_minor=already_paid,
        token=checkout_claim_token,
    )
    _validate_confirmed_payment_balance(
        payload,
        order_total_minor=int(order.total_minor or 0),
        due_minor=due_minor,
    )
    if due_minor <= 0:
        raise BusinessRuleError(
            "Order balance is zero. Finalize it with the zero-total settlement action; "
            "do not record a payment."
        )
    if payload.amount_minor > due_minor:
        raise BusinessRuleError("payment exceeds amount due")

    # Tip is additional money collected alongside this payment — never part
    # of the amount-due match above (that protects the bill itself from
    # stale reads/split payments). It is folded in only from here on, into
    # what is actually recorded as collected/banked, so order.total_minor
    # ends up including it exactly like ledger.py and the reports balance
    # check (app/api/v1/reports/router.py) already expect.
    tip_minor = int(payload.tip_minor or 0)
    collected_minor = payload.amount_minor + tip_minor
    now = datetime.now(timezone.utc)
    if tip_minor:
        order.tip_minor = int(order.tip_minor or 0) + tip_minor
        order.total_minor = int(order.total_minor or 0) + tip_minor
    payment = Payment(
        id=uuid4(),
        order_id=order_id,
        shift_id=order.shift_id,
        method=payload.method,
        amount_minor=collected_minor,
        tendered_minor=payload.tendered_minor,
        change_minor=(payload.tendered_minor - collected_minor)
        if payload.tendered_minor is not None and payload.method == "cash"
        else None,
        ref_external=payload.ref_external,
        paid_at=now,
    )
    session.add(payment)
    if payload.method == "cash":
        shift.expected_minor = int(shift.expected_minor or 0) + collected_minor
    finalized = already_paid + collected_minor >= order.total_minor
    if finalized:
        await _finalize_order(
            session,
            order=order,
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            at=now,
        )
        _schedule_order_paid_event(
            background_tasks,
            company_id=tenant.company_id,
            branch_id=order.branch_id,
            order_id=order.id,
            total_minor=int(order.total_minor or 0),
            method=payload.method,
            occurred_at=now,
        )
    response = PaymentRead(
        id=payment.id,
        order_id=payment.order_id,
        shift_id=payment.shift_id,
        method=payment.method,
        amount_minor=payment.amount_minor,
        bill_amount_minor=payload.amount_minor,
        tip_minor=tip_minor,
        tendered_minor=payment.tendered_minor,
        change_minor=payment.change_minor,
        ref_external=payment.ref_external,
        paid_at=payment.paid_at,
        order_status=order.status,
        invoice_no=order.invoice_no,
        fiscal_year=order.fiscal_year,
        invoice_issued_at=order.invoice_issued_at,
    )
    # Consumed in the same transaction as Payment + invoice finalization.  If
    # any later flush/commit fails the delete rolls back with the sale, so the
    # cashier can safely retry using the same idempotency key and claim.
    await consume_checkout_claim(session, checkout_claim)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


async def _settle_pos_refund(
    session,
    *,
    refund_request: PosRefundRequest,
    order: Order,
    shift: Shift,
    settled_at: datetime,
    accounting_finalized_at: datetime,
    client_occurred_at: datetime,
    captured_time_reconciled: bool,
    provider_evidence_reconciled: bool | None = None,
    settled_by: UUID,
    idempotency_key: str,
    external_reference: str | None = None,
    provider_settled_at: datetime | None = None,
) -> Refund:
    """Create the immutable financial fact and all side effects exactly once."""
    paid_total = await _paid_total(session, order.id)
    refunded_before = await _refunded_total(session, order.id)
    amount = int(refund_request.amount_minor)
    if refund_request.settlement_method == "cash":
        if external_reference is not None or provider_settled_at is not None:
            raise BusinessRuleError(
                "Cash refund settlement cannot contain external provider evidence."
            )
    elif not external_reference or provider_settled_at is None:
        raise BusinessRuleError(
            "Provider refund settlement requires its external reference and exact "
            "completion time."
        )

    receipt_no, receipt_fiscal_year = await _allocate_pos_refund_receipt(
        session,
        company_id=refund_request.company_id,
        branch_id=refund_request.branch_id,
        occurred_at=settled_at,
    )
    refund = Refund(
        id=uuid4(),
        request_id=refund_request.id,
        order_id=order.id,
        company_id=refund_request.company_id,
        branch_id=refund_request.branch_id,
        terminal_id=refund_request.terminal_id,
        settlement_shift_id=shift.id,
        approved_by=refund_request.approved_by,
        manager_override_user_id=refund_request.manager_override_user_id,
        reason_code=refund_request.reason_code,
        amount_minor=amount,
        mode=refund_request.mode,
        settlement_method=refund_request.settlement_method,
        settled_at=settled_at,
        settled_by=settled_by,
        external_reference=external_reference,
        provider_settled_at=provider_settled_at,
        client_occurred_at=client_occurred_at,
        captured_time_reconciled=captured_time_reconciled,
        provider_evidence_reconciled=provider_evidence_reconciled,
        settlement_idempotency_key=idempotency_key,
        receipt_no=receipt_no,
        receipt_fiscal_year=receipt_fiscal_year,
        receipt_issued_at=accounting_finalized_at,
        customer_spend_reconciled=order.customer_id is None,
        note=refund_request.note,
    )
    session.add(refund)

    if refund_request.settlement_method == "cash":
        shift.expected_minor = int(shift.expected_minor or 0) - amount

    # Once value has physically moved, the immutable settlement must not roll
    # back because an ancillary LTV record drifted. Forward orders normally
    # reconcile here; legacy/deleted/inconsistent customers are explicitly
    # flagged for owner repair while the real refund remains recorded.
    if order.customer_id is not None:
        customer = await session.get(Customer, order.customer_id, with_for_update=True)
        if customer is not None and customer.company_id == refund_request.company_id:
            current_spend = int(customer.total_spent_minor or 0)
            if current_spend >= amount:
                customer.total_spent_minor = current_spend - amount
                refund.customer_spend_reconciled = True
            else:
                # Never hide pre-existing LTV drift by flooring the accumulator
                # and claiming success. The payout remains immutable and the
                # owner reconciliation queue derives an authoritative balance
                # from normalized payment/refund facts.
                refund.customer_spend_reconciled = False

    if refunded_before + amount >= paid_total:
        order.status = "refunded"

    # A monetary refund is not proof that prepared food or consumed gaming
    # inventory returned to stock. Inventory reversal requires a separate,
    # item-level disposition workflow; never inflate stock automatically here.
    return refund


async def _locked_pos_refund_context(
    session,
    *,
    refund_request_id: UUID,
    payload_shift_id: UUID,
    tenant: TenantContext,
    operation: str,
) -> tuple[Order, Shift, PosRefundRequest]:
    """Lock Order -> Shift -> request, the canonical refund lock order."""
    preflight = (
        await session.execute(
            select(PosRefundRequest.order_id, PosRefundRequest.shift_id).where(
                PosRefundRequest.id == refund_request_id,
                PosRefundRequest.company_id == tenant.company_id,
            )
        )
    ).one_or_none()
    if preflight is None:
        raise NotFoundError("POS refund request not found")
    order_id, stored_shift_id = preflight
    if stored_shift_id != payload_shift_id:
        raise BusinessRuleError(
            "This refund belongs to a different shift. Return to the exact terminal "
            "and shift that accepted it; no drawer was changed."
        )
    order = (
        await session.execute(select(Order).where(Order.id == order_id).with_for_update())
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation=operation,
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload_shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation=operation,
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation=operation,
    )
    refund_request = (
        await session.execute(
            select(PosRefundRequest)
            .where(
                PosRefundRequest.id == refund_request_id,
                PosRefundRequest.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if refund_request is None:
        raise NotFoundError("POS refund request not found")
    if (
        refund_request.order_id != order.id
        or refund_request.shift_id != shift.id
        or refund_request.branch_id != shift.branch_id
        or refund_request.terminal_id != shift.terminal_id
    ):
        raise BusinessRuleError(
            "Refund, order, and shift provenance do not match. Nothing was posted; "
            "a protected owner must review the audit trail."
        )
    return order, shift, refund_request


async def _pos_refund_state_rows(
    session,
    *,
    refund_request_id: UUID,
) -> tuple[
    PosRefundCashHandoff | None,
    PosRefundCashHandoffCompletion | None,
    PosRefundProviderPayoutStart | None,
    PosRefundProviderSettlement | None,
    Refund | None,
    PosRefundWithdrawal | None,
]:
    handoff = (
        await session.execute(
            select(PosRefundCashHandoff).where(
                PosRefundCashHandoff.refund_request_id == refund_request_id
            )
        )
    ).scalar_one_or_none()
    cash_completion = (
        await session.execute(
            select(PosRefundCashHandoffCompletion).where(
                PosRefundCashHandoffCompletion.refund_request_id == refund_request_id
            )
        )
    ).scalar_one_or_none()
    provider_start = (
        await session.execute(
            select(PosRefundProviderPayoutStart).where(
                PosRefundProviderPayoutStart.refund_request_id == refund_request_id
            )
        )
    ).scalar_one_or_none()
    provider_completion = (
        await session.execute(
            select(PosRefundProviderSettlement).where(
                PosRefundProviderSettlement.refund_request_id == refund_request_id
            )
        )
    ).scalar_one_or_none()
    refund = (
        await session.execute(
            select(Refund).where(Refund.request_id == refund_request_id)
        )
    ).scalar_one_or_none()
    withdrawal = (
        await session.execute(
            select(PosRefundWithdrawal).where(
                PosRefundWithdrawal.refund_request_id == refund_request_id
            )
        )
    ).scalar_one_or_none()
    return (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        refund,
        withdrawal,
    )


@router.post("/orders/{order_id}/refunds", status_code=status.HTTP_201_CREATED)
async def issue_refund(
    order_id: UUID,
    payload: RefundCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> dict:
    """Reject the unsafe pre-0034 one-call refund contract.

    Keeping the route produces an actionable upgrade response instead of a 404,
    but it must never book a payout before physical cash handover.
    """
    raise BusinessRuleError(
        "This refund screen is from an older app and cannot safely record cash "
        "handover. Update D Company ERP, reopen Refunds, and create a recoverable "
        "refund request. No refund or drawer movement was recorded."
    )


@router.post(
    "/refund-requests",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_pos_refund_request(
    payload: PosRefundRequestCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    """Reserve a refund without moving cash or provider money."""
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Select this tablet's branch and terminal, then open its shift before "
            "creating a refund."
        )
    if payload.manager_override_user_id is not None:
        raise BusinessRuleError(
            "A refund client cannot name its own manager approver. Sign in with an "
            "authorised account and create the request again; no refund was recorded."
        )
    clean_reason_code = payload.reason_code.strip()
    if not clean_reason_code:
        raise BusinessRuleError(
            "Choose a refund reason before authorising the payout. No refund was "
            "recorded."
        )
    header_action_id = (request.headers.get("X-Client-Action-Id") or "").strip()
    if not header_action_id:
        raise BusinessRuleError(
            "X-Client-Action-Id is required for refund audit provenance. Update the "
            "app and retry; no refund was recorded."
        )
    if header_action_id != payload.client_action_id:
        raise BusinessRuleError(
            "The saved refund action ID does not match its audit provenance. "
            "Nothing was posted."
        )
    clean_external_ref = (
        payload.external_reference.strip() if payload.external_reference else None
    )
    if clean_external_ref is not None or payload.provider_settled_at is not None:
        raise BusinessRuleError(
            "Reserve the refund with the server before moving money. Do not include "
            "cash-handover or provider-completion evidence in the request; use the "
            "separate settlement step after this request is accepted."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])

    order = (
        await session.execute(
            select(Order).where(Order.id == payload.order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="requesting a POS refund",
    )
    if order.status not in {"paid", "refunded"}:
        raise BusinessRuleError("Only a paid order can be refunded.")

    # The order lock serializes both duplicate client_action_id recovery and
    # different-key partial refunds, preventing over-reservation.
    existing_action = (
        await session.execute(
            select(PosRefundRequest).where(
                PosRefundRequest.company_id == tenant.company_id,
                PosRefundRequest.client_action_id == payload.client_action_id,
            )
        )
    ).scalar_one_or_none()
    if existing_action is not None:
        replay_methods = {
            row.method
            for row in (
                await session.execute(select(Payment).where(Payment.order_id == order.id))
            )
            .scalars()
            .all()
        }
        replay_settlement_method = "cash"
        if payload.mode == "original":
            replay_settlement_method = (
                next(iter(replay_methods)) if len(replay_methods) == 1 else "invalid"
            )
        if (
            existing_action.order_id != payload.order_id
            or existing_action.shift_id != payload.shift_id
            or int(existing_action.amount_minor) != payload.amount_minor
            or existing_action.mode != payload.mode
            or existing_action.reason_code != clean_reason_code
            or existing_action.settlement_method != replay_settlement_method
            or int(existing_action.order_paid_snapshot_minor)
            != payload.expected_paid_minor
            or int(existing_action.order_refundable_snapshot_minor)
            != payload.expected_refundable_minor
            or (existing_action.note or None) != (payload.note or None)
        ):
            raise BusinessRuleError(
                "This refund action ID was already used with different details. "
                "Open the existing refund task instead of creating another."
            )
        (
            handoff,
            cash_completion,
            provider_start,
            provider_completion,
            refund,
            withdrawal,
        ) = await _pos_refund_state_rows(
            session, refund_request_id=existing_action.id
        )
        response = _pos_refund_request_read(
            existing_action,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=refund,
            withdrawal=withdrawal,
        )
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

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
        operation="requesting this POS refund",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="request this POS refund on the current shift",
    )
    original_shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    original_shift = require_operational_shift_scope(
        original_shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="requesting a refund for this order",
        resource_branch_id=order.branch_id,
        resource_name="order",
    )
    require_shift_opener(
        original_shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="refund an order from its original shift",
    )

    payment_rows = (
        await session.execute(
            select(Payment).where(Payment.order_id == order.id).order_by(Payment.paid_at)
        )
    ).scalars().all()
    paid_total = sum(int(row.amount_minor) for row in payment_rows)
    settled_total = await _refunded_total(session, order.id)
    reserved_total = await _unresolved_pos_refund_total(session, order_id=order.id)
    refundable_minor = paid_total - settled_total - reserved_total
    if payload.expected_paid_minor != paid_total:
        raise BusinessRuleError(
            "The order's paid total changed. Refresh the refund screen before "
            "authorising any payout."
        )
    if payload.expected_refundable_minor != refundable_minor:
        raise BusinessRuleError(
            "The refundable balance changed, possibly because another employee "
            "started a refund. Refresh and review the pending task."
        )
    if refundable_minor <= 0:
        raise BusinessRuleError("This order has no unreserved refundable balance.")
    if payload.amount_minor > refundable_minor:
        raise BusinessRuleError("Refund amount exceeds the available paid balance.")

    settlement_method = "cash"
    if payload.mode == "original":
        methods = {row.method for row in payment_rows}
        if len(methods) != 1:
            raise BusinessRuleError(
                "Mixed-payment orders need an explicit cash refund. Review the "
                "payment history before continuing."
            )
        settlement_method = next(iter(methods))
    server_now = datetime.now(timezone.utc)
    if settlement_method == "cash":
        pos_reserved = await _unresolved_pos_refund_total(
            session, shift_id=shift.id, cash_only=True
        )
        membership_reserved = await _unresolved_membership_cash_refund_total(
            session, shift_id=shift.id
        )
        available_drawer = (
            int(shift.expected_minor or 0) - pos_reserved - membership_reserved
        )
        if payload.amount_minor > available_drawer:
            raise BusinessRuleError(
                "This shift does not have enough expected drawer cash after other "
                "accepted refunds. Settle or withdraw the earlier tasks, use the "
                "correct shift, or refund to the original non-cash rail."
            )

    if order.customer_id is not None:
        customer = await session.get(Customer, order.customer_id, with_for_update=True)
        if customer is None or customer.company_id != tenant.company_id:
            raise BusinessRuleError(
                "The order's customer link is invalid. A protected owner must repair "
                "it before any refund is authorised."
            )
        pending_customer_refunds = await _unresolved_pos_refund_total(
            session, customer_id=customer.id
        )
        pending_membership_refunds = (
            await _unresolved_membership_refund_total_for_customer(
                session, customer_id=customer.id
            )
        )
        if int(customer.total_spent_minor or 0) < (
            pending_customer_refunds
            + pending_membership_refunds
            + payload.amount_minor
        ):
            raise BusinessRuleError(
                "Customer lifetime spend is inconsistent with pending refunds. A "
                "protected owner must reconcile LTV before authorising another payout."
            )

    refund_request = PosRefundRequest(
        id=uuid4(),
        company_id=tenant.company_id,
        order_id=order.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        approved_by=tenant.user_id,
        # The authenticated actor is the only approval provenance until a
        # separate, server-authenticated two-person approval flow exists.
        manager_override_user_id=None,
        reason_code=clean_reason_code,
        amount_minor=payload.amount_minor,
        mode=payload.mode,
        settlement_method=settlement_method,
        order_paid_snapshot_minor=paid_total,
        order_refundable_snapshot_minor=refundable_minor,
        accepted_at=server_now,
        external_reference=None,
        provider_settled_at=None,
        client_action_id=payload.client_action_id,
        idempotency_key=idempotency_key,
        note=payload.note,
    )
    session.add(refund_request)
    await session.flush()
    response = _pos_refund_request_read(
        refund_request,
        order=order,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/begin-cash-handoff",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def begin_pos_cash_refund_handoff(
    refund_request_id: UUID,
    payload: PosRefundHandoffRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    if not payload.ready_to_handover:
        raise BusinessRuleError(
            "Confirm that the customer and amount are verified before opening the "
            "cash handover step."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="begin this cash refund handover",
    )
    if refund_request.settlement_method != "cash":
        raise BusinessRuleError(
            "This refund was completed on a non-cash rail and has no drawer handover."
        )
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted refund amount changed on this screen. Refresh before "
            "touching drawer cash."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if (
        cash_completion is not None
        or provider_completion is not None
        or refund is not None
        or withdrawal is not None
    ):
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=refund,
            withdrawal=withdrawal,
        )
    else:
        if handoff is not None and handoff.started_by != tenant.user_id:
            starter = await session.get(User, handoff.started_by)
            starter_name = starter.name if starter is not None else "another employee"
            raise BusinessRuleError(
                f"{starter_name} already started this cash handover. Ask them to finish "
                "it, or ask a protected owner to take over after checking the customer "
                "and drawer. Do not hand over cash a second time."
            )
        other_pos_reserved = await _unresolved_pos_refund_total(
            session,
            shift_id=shift.id,
            cash_only=True,
            exclude_request_id=refund_request.id,
        )
        membership_reserved = await _unresolved_membership_cash_refund_total(
            session, shift_id=shift.id
        )
        available_drawer = (
            int(shift.expected_minor or 0) - other_pos_reserved - membership_reserved
        )
        if int(refund_request.amount_minor) > available_drawer:
            raise BusinessRuleError(
                "The drawer no longer has enough expected cash. Do not hand over "
                "money; resolve earlier refund tasks or withdraw this request."
            )
        if order.customer_id is not None:
            customer = await session.get(
                Customer, order.customer_id, with_for_update=True
            )
            if customer is None or customer.company_id != tenant.company_id:
                raise BusinessRuleError(
                    "The order's linked customer is unavailable. Do not hand over "
                    "cash until a protected owner repairs the customer link."
                )
            pending_pos = await _unresolved_pos_refund_total(
                session, customer_id=customer.id
            )
            pending_memberships = (
                await _unresolved_membership_refund_total_for_customer(
                    session, customer_id=customer.id
                )
            )
            if int(customer.total_spent_minor or 0) < (
                pending_pos + pending_memberships
            ):
                raise BusinessRuleError(
                    "Customer lifetime spend no longer covers accepted refund tasks. "
                    "Do not hand over cash; a protected owner must reconcile LTV."
                )
        if handoff is None:
            handoff = PosRefundCashHandoff(
                id=uuid4(),
                company_id=tenant.company_id,
                refund_request_id=refund_request.id,
                branch_id=shift.branch_id,
                terminal_id=shift.terminal_id,
                shift_id=shift.id,
                started_at=datetime.now(timezone.utc),
                started_by=tenant.user_id,
                idempotency_key=idempotency_key,
            )
            session.add(handoff)
            await session.flush()
        response = _pos_refund_request_read(
            refund_request, order=order, handoff=handoff
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/settle-cash",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def settle_pos_cash_refund(
    refund_request_id: UUID,
    payload: PosRefundCashSettlementRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    """Durably record cash movement; accounting is a separate safe retry."""
    if not payload.cash_handed_over:
        raise BusinessRuleError(
            "Only confirm settlement after the customer physically receives the cash."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="record this completed POS cash handover",
    )
    if refund_request.settlement_method != "cash":
        raise BusinessRuleError(
            "This refund used a non-cash rail and cannot change the drawer."
        )
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The server-confirmed cash amount differs from this handover screen. "
            "Keep the recoverable task open, verify the customer and drawer, and "
            "refresh before recording the outcome; do not hand over cash again."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        existing_refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if existing_refund is not None:
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=existing_refund,
        )
    else:
        if withdrawal is not None:
            raise BusinessRuleError(
                "This refund request was withdrawn because no cash was handed over. "
                "It cannot be settled afterward."
            )
        if handoff is None:
            raise BusinessRuleError(
                "Start the server-confirmed cash handover before touching drawer cash. "
                "This prevents a restart from paying the customer twice."
            )
        if tenant.user_id != handoff.started_by and not tenant.protected_access:
            starter = await session.get(User, handoff.started_by)
            starter_name = starter.name if starter is not None else "another employee"
            raise ForbiddenError(
                f"{starter_name} started this cash handover. They must record whether "
                "cash reached the customer, or a protected owner must take over after "
                "checking the customer and drawer."
            )
        if cash_completion is None:
            server_now = datetime.now(timezone.utc)
            client_occurred_at, captured_time_reconciled = (
                _captured_pos_financial_evidence_after_value_moved(
                    request=request,
                    occurred_at=payload.settled_at,
                    earliest_server_at=handoff.started_at,
                    server_now=server_now,
                )
            )
            # Do not perform receipt allocation, drawer mutation, LTV mutation,
            # or other fallible accounting work in this transaction.  Once this
            # response commits, every device can recover the real cash movement.
            cash_completion = PosRefundCashHandoffCompletion(
                id=uuid4(),
                company_id=tenant.company_id,
                refund_request_id=refund_request.id,
                branch_id=shift.branch_id,
                terminal_id=shift.terminal_id,
                shift_id=shift.id,
                handed_over_at=client_occurred_at,
                recorded_at=server_now,
                recorded_by=tenant.user_id,
                captured_time_reconciled=captured_time_reconciled,
                idempotency_key=idempotency_key,
            )
            session.add(cash_completion)
            await session.flush()
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/finalize-cash",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def finalize_pos_cash_refund(
    refund_request_id: UUID,
    payload: PosRefundAccountingFinalizationRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    """Materialize accounting from an already committed cash-movement fact."""
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="finalize accounting for this completed POS cash handover",
    )
    if refund_request.settlement_method != "cash":
        raise BusinessRuleError("This is not a cash refund task.")
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The completed cash amount differs from this accounting screen. Refresh "
            "the recoverable task; do not hand over cash again."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        existing_refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if existing_refund is not None:
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=existing_refund,
        )
    else:
        if withdrawal is not None:
            raise BusinessRuleError(
                "This task was resolved as unpaid and cannot be finalized. A protected "
                "owner must inspect the conflicting evidence."
            )
        if cash_completion is None:
            raise BusinessRuleError(
                "No durable cash-handover confirmation exists. Record whether the "
                "customer received cash before finalizing accounting."
            )
        if (
            tenant.user_id != cash_completion.recorded_by
            and not tenant.protected_access
        ):
            recorder = await session.get(User, cash_completion.recorded_by)
            recorder_name = recorder.name if recorder is not None else "another employee"
            raise ForbiddenError(
                f"{recorder_name} recorded this cash handover. They must finish its "
                "accounting, or a protected owner must explicitly take over."
            )
        finalized_at = datetime.now(timezone.utc)
        refund = await _settle_pos_refund(
            session,
            refund_request=refund_request,
            order=order,
            shift=shift,
            # Accounting follows the immutable server time at which value was
            # recorded as moved, not a later retry/finalization time. The
            # customer's device time remains separate evidence below.
            settled_at=cash_completion.recorded_at,
            accounting_finalized_at=finalized_at,
            client_occurred_at=cash_completion.handed_over_at,
            captured_time_reconciled=cash_completion.captured_time_reconciled,
            settled_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        await session.flush()
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=refund,
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/withdraw-cash",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def withdraw_pos_cash_refund(
    refund_request_id: UUID,
    payload: PosRefundWithdrawalRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may withdraw an accepted POS cash refund."
        )
    if not payload.cash_not_handed_over:
        raise BusinessRuleError(
            "Withdraw only when no cash reached the customer. If cash left the "
            "drawer, confirm settlement instead."
        )
    clean_reason = payload.reason.strip()
    if len(clean_reason) < 3:
        raise BusinessRuleError("Explain why the accepted cash refund was not paid.")
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="withdraw this unpaid POS cash refund",
    )
    if refund_request.settlement_method != "cash":
        raise BusinessRuleError(
            "A completed non-cash provider refund cannot be withdrawn in the ERP."
        )
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted amount differs from this withdrawal. Refresh the task."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if refund is not None:
        raise BusinessRuleError(
            "This refund is already settled and cannot be withdrawn. No second "
            "drawer movement was made."
        )
    if cash_completion is not None:
        raise BusinessRuleError(
            "Cash was already recorded as handed to the customer. This task cannot be "
            "withdrawn; finish its accounting and do not pay again."
        )
    if handoff is not None and withdrawal is None:
        raise BusinessRuleError(
            "Cash handover is already in progress. It cannot be simply withdrawn "
            "while another employee may be paying the customer. The employee who "
            "started it, or a protected owner, must use Resolve handover only after "
            "confirming the customer received no cash and the drawer is unchanged."
        )
    if withdrawal is None:
        withdrawn_at = _validated_pos_financial_time(
            request=request,
            occurred_at=payload.withdrawn_at,
            shift=shift,
            now=datetime.now(timezone.utc),
            action_name="POS refund withdrawal",
        )
        if withdrawn_at < refund_request.accepted_at:
            raise BusinessRuleError(
                "The withdrawal time predates this accepted refund. Refresh the task "
                "and confirm again; no refund or drawer movement was recorded."
            )
        withdrawal = PosRefundWithdrawal(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_request_id=refund_request.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            resolution="cash_not_handed_over",
            reason=clean_reason,
            withdrawn_at=withdrawn_at,
            withdrawn_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(withdrawal)
        await session.flush()
    response = _pos_refund_request_read(
        refund_request,
        order=order,
        handoff=handoff,
        cash_completion=cash_completion,
        provider_start=provider_start,
        provider_completion=provider_completion,
        withdrawal=withdrawal,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/resolve-cash-handoff",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def resolve_pos_cash_refund_handoff(
    refund_request_id: UUID,
    payload: PosRefundCashHandoffResolutionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    """Resolve a started handoff only after staff verify that no cash moved."""
    if not payload.cash_not_handed_over or not payload.drawer_unchanged:
        raise BusinessRuleError(
            "Resolve a started handover only after confirming both that the customer "
            "received no cash and that the physical drawer is unchanged."
        )
    clean_reason = payload.reason.strip()
    if len(clean_reason) < 3:
        raise BusinessRuleError("Explain why the started cash handover was abandoned.")
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="resolve this started POS cash handover",
    )
    if refund_request.settlement_method != "cash":
        raise BusinessRuleError("This is not a cash refund handover.")
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted amount differs from this resolution. Refresh the task."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if handoff is None:
        raise BusinessRuleError(
            "No cash handover was started. Use Withdraw accepted refund instead."
        )
    if tenant.user_id != handoff.started_by and not tenant.protected_access:
        raise ForbiddenError(
            "This cash handover was started by another employee. That employee or a "
            "protected owner must resolve it after checking the customer and drawer."
        )
    if refund is not None:
        raise BusinessRuleError(
            "This cash refund is already settled. The drawer movement remains recorded."
        )
    if cash_completion is not None:
        raise BusinessRuleError(
            "Cash was already recorded as handed to the customer. This handover cannot "
            "be resolved as abandoned; finish its accounting and do not pay again."
        )
    if withdrawal is None:
        resolved_at = _validated_pos_financial_time(
            request=request,
            occurred_at=payload.resolved_at,
            shift=shift,
            now=datetime.now(timezone.utc),
            action_name="POS cash handover resolution",
        )
        if resolved_at < handoff.started_at:
            raise BusinessRuleError(
                "The resolution time predates the server-confirmed cash handover. "
                "Refresh the task and verify the customer and drawer again."
            )
        withdrawal = PosRefundWithdrawal(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_request_id=refund_request.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            resolution="cash_handoff_abandoned",
            reason=clean_reason,
            withdrawn_at=resolved_at,
            withdrawn_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(withdrawal)
        await session.flush()
    response = _pos_refund_request_read(
        refund_request,
        order=order,
        handoff=handoff,
        cash_completion=cash_completion,
        provider_start=provider_start,
        provider_completion=provider_completion,
        withdrawal=withdrawal,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/begin-provider-payout",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def begin_pos_provider_refund_payout(
    refund_request_id: UUID,
    payload: PosRefundProviderPayoutStartRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    """Create the durable in-progress fact before staff touch a provider app."""
    if not payload.ready_to_start_provider_payout:
        raise BusinessRuleError(
            "Confirm the customer, amount, and original provider rail before "
            "starting the external payout."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="begin this provider POS refund payout",
    )
    if refund_request.settlement_method == "cash":
        raise BusinessRuleError(
            "This is a cash refund. Use the server-confirmed drawer handover flow."
        )
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted amount differs from this provider task. Refresh before "
            "opening the provider app."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if handoff is not None:
        raise BusinessRuleError(
            "This provider refund has inconsistent cash-handover provenance. A "
            "protected owner must review it."
        )
    if (
        provider_completion is not None
        or refund is not None
        or withdrawal is not None
    ):
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=refund,
            withdrawal=withdrawal,
        )
    else:
        if provider_start is not None and provider_start.started_by != tenant.user_id:
            starter = await session.get(User, provider_start.started_by)
            starter_name = starter.name if starter is not None else "another employee"
            raise BusinessRuleError(
                f"{starter_name} already started this provider payout. Ask them to "
                "finish it, or ask a protected owner to take over after checking the "
                "provider. Do not start a second payout."
            )
        # Revalidate the shared LTV reservation immediately before the external
        # action. After this point the immutable settlement must be recorded
        # even if an ancillary accumulator drifts.
        if order.customer_id is not None:
            customer = await session.get(
                Customer, order.customer_id, with_for_update=True
            )
            if customer is None or customer.company_id != tenant.company_id:
                raise BusinessRuleError(
                    "The order's linked customer is unavailable. Do not start the "
                    "provider payout until a protected owner repairs it."
                )
            pending_pos = await _unresolved_pos_refund_total(
                session, customer_id=customer.id
            )
            pending_memberships = (
                await _unresolved_membership_refund_total_for_customer(
                    session, customer_id=customer.id
                )
            )
            if int(customer.total_spent_minor or 0) < (
                pending_pos + pending_memberships
            ):
                raise BusinessRuleError(
                    "Customer lifetime spend no longer covers accepted refund tasks. "
                    "Do not start the provider payout; a protected owner must "
                    "reconcile LTV first."
                )
        if provider_start is None:
            provider_start = PosRefundProviderPayoutStart(
                id=uuid4(),
                company_id=tenant.company_id,
                refund_request_id=refund_request.id,
                branch_id=shift.branch_id,
                terminal_id=shift.terminal_id,
                shift_id=shift.id,
                started_at=datetime.now(timezone.utc),
                started_by=tenant.user_id,
                idempotency_key=idempotency_key,
            )
            session.add(provider_start)
            await session.flush()
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            provider_start=provider_start,
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/settle-provider",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def settle_pos_provider_refund(
    refund_request_id: UUID,
    payload: PosRefundProviderSettlementRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    """Durably capture provider completion before accounting finalization."""
    if not payload.provider_completed:
        raise BusinessRuleError(
            "Confirm provider completion only after the card/UPI/QR/wallet service "
            "returns a successful reference."
        )
    clean_reference = payload.external_reference.strip()
    if not clean_reference:
        raise BusinessRuleError(
            "Enter the provider's successful refund reference before settlement."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="settle this provider POS refund",
    )
    if refund_request.settlement_method == "cash":
        raise BusinessRuleError(
            "This is a cash refund. Use the server-confirmed drawer handover flow."
        )
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The server-confirmed provider amount differs from this payout screen. "
            "Keep the recoverable task open, verify the provider, and refresh before "
            "recording the outcome; do not perform another payout."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        existing_refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if handoff is not None:
        raise BusinessRuleError(
            "This provider refund has inconsistent cash-handover provenance. A "
            "protected owner must review it; nothing new was posted."
        )
    if withdrawal is not None:
        raise BusinessRuleError(
            "This provider refund reservation was withdrawn because no provider "
            "payout occurred. It cannot be settled afterward."
        )
    if provider_start is None:
        raise BusinessRuleError(
            "The server has not opened this provider payout. Return to the refund "
            "task and press Start provider payout while online before moving money."
        )
    if tenant.user_id != provider_start.started_by and not tenant.protected_access:
        starter = await session.get(User, provider_start.started_by)
        starter_name = starter.name if starter is not None else "another employee"
        raise ForbiddenError(
            f"{starter_name} started this provider payout. They must record the "
            "provider outcome, or a protected owner must take over after checking the "
            "provider. Do not start or report a second payout."
        )

    server_now = datetime.now(timezone.utc)
    provider_settled_at, captured_time_reconciled = (
        _captured_pos_financial_evidence_after_value_moved(
            request=request,
            occurred_at=payload.provider_settled_at,
            earliest_server_at=provider_start.started_at,
            server_now=server_now,
        )
    )
    if provider_completion is not None and (
        provider_completion.external_reference != clean_reference
        or provider_completion.provider_settled_at != provider_settled_at
    ):
        raise BusinessRuleError(
            "This provider payout already has different immutable completion "
            "evidence. Open the existing task and do not perform another payout."
        )
    if existing_refund is not None:
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=existing_refund,
        )
    else:
        if provider_completion is None:
            # A provider can return short or reused opaque tokens. After value
            # moved, never reject and lose the completion fact: serialize the
            # evidence check, store it, and flag duplicates for owner review.
            provider_lock_key = (
                f"pos-refund-provider:{tenant.company_id}:"
                f"{refund_request.settlement_method}:{clean_reference}"
            )
            await session.execute(
                select(
                    func.pg_advisory_xact_lock(
                        func.hashtextextended(provider_lock_key, 0)
                    )
                )
            )
            duplicate_reference_count = int(
                (
                    await session.execute(
                        select(func.count(PosRefundProviderSettlement.id)).where(
                            PosRefundProviderSettlement.company_id == tenant.company_id,
                            PosRefundProviderSettlement.settlement_method
                            == refund_request.settlement_method,
                            PosRefundProviderSettlement.external_reference
                            == clean_reference,
                        )
                    )
                ).scalar_one()
                or 0
            )
            provider_completion = PosRefundProviderSettlement(
                id=uuid4(),
                company_id=tenant.company_id,
                refund_request_id=refund_request.id,
                branch_id=shift.branch_id,
                terminal_id=shift.terminal_id,
                shift_id=shift.id,
                settlement_method=refund_request.settlement_method,
                external_reference=clean_reference,
                provider_settled_at=provider_settled_at,
                settled_by=tenant.user_id,
                captured_time_reconciled=captured_time_reconciled,
                provider_evidence_reconciled=(
                    len(clean_reference) >= 3 and duplicate_reference_count == 0
                ),
                idempotency_key=idempotency_key,
            )
            session.add(provider_completion)
            # No receipt allocation, LTV mutation, or Refund insert belongs in
            # this transaction.  A successful response commits the external
            # outcome so restart/reinstall recovery is server-backed.
            await session.flush()
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/finalize-provider",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def finalize_pos_provider_refund(
    refund_request_id: UUID,
    payload: PosRefundAccountingFinalizationRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    """Materialize accounting from an already committed provider outcome."""
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="finalize accounting for this completed provider POS refund",
    )
    if refund_request.settlement_method == "cash":
        raise BusinessRuleError("This is not a provider refund task.")
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The completed provider amount differs from this accounting screen. "
            "Refresh the recoverable task; do not perform another payout."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        existing_refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if existing_refund is not None:
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=existing_refund,
        )
    else:
        if withdrawal is not None:
            raise BusinessRuleError(
                "This provider task was resolved as not completed and cannot be "
                "finalized. A protected owner must inspect the conflicting evidence."
            )
        if provider_completion is None:
            raise BusinessRuleError(
                "No durable provider-completion fact exists. Record the provider "
                "outcome first; do not perform another payout."
            )
        if (
            tenant.user_id != provider_completion.settled_by
            and not tenant.protected_access
        ):
            recorder = await session.get(User, provider_completion.settled_by)
            recorder_name = recorder.name if recorder is not None else "another employee"
            raise ForbiddenError(
                f"{recorder_name} recorded this provider completion. They must finish "
                "its accounting, or a protected owner must explicitly take over."
            )
        finalized_at = datetime.now(timezone.utc)
        refund = await _settle_pos_refund(
            session,
            refund_request=refund_request,
            order=order,
            shift=shift,
            # The completion insert is the authoritative server-side money
            # movement time. A delayed recovery must not shift revenue/refunds
            # into a later accounting period merely because finalization retried.
            settled_at=provider_completion.created_at,
            accounting_finalized_at=finalized_at,
            client_occurred_at=provider_completion.provider_settled_at,
            captured_time_reconciled=provider_completion.captured_time_reconciled,
            provider_evidence_reconciled=(
                provider_completion.provider_evidence_reconciled
            ),
            settled_by=tenant.user_id,
            idempotency_key=idempotency_key,
            external_reference=provider_completion.external_reference,
            provider_settled_at=provider_completion.provider_settled_at,
        )
        await session.flush()
        response = _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=refund,
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/withdraw-provider",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def withdraw_pos_provider_refund(
    refund_request_id: UUID,
    payload: PosRefundProviderWithdrawalRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> PosRefundRequestRead:
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may withdraw a provider refund reservation."
        )
    if not payload.provider_not_completed:
        raise BusinessRuleError(
            "Withdraw only after verifying that no provider payout occurred. If the "
            "provider succeeded, record its reference instead."
        )
    clean_reason = payload.reason.strip()
    if len(clean_reason) < 3:
        raise BusinessRuleError(
            "Explain how the owner verified that no provider refund completed."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="withdraw this incomplete provider POS refund",
    )
    if refund_request.settlement_method == "cash":
        raise BusinessRuleError(
            "This is a cash refund. Use the cash withdrawal decision instead."
        )
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The reserved amount differs from this withdrawal. Refresh the task."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if handoff is not None:
        raise BusinessRuleError(
            "This provider refund has inconsistent cash-handover provenance. A "
            "protected owner must review it."
        )
    if refund is not None:
        raise BusinessRuleError(
            "This provider refund is already settled and cannot be withdrawn. "
            "No second financial movement was made."
        )
    if provider_completion is not None:
        raise BusinessRuleError(
            "Provider completion is already recorded. This task cannot be withdrawn; "
            "finish its accounting and do not perform another payout."
        )
    if provider_start is not None and withdrawal is None:
        raise BusinessRuleError(
            "Provider payout is already in progress. It cannot be simply withdrawn "
            "while an external transaction may be completing. A protected owner must "
            "verify the provider record and use Resolve provider payout. Do not start "
            "a second payout."
        )
    if withdrawal is None:
        withdrawn_at = _validated_pos_financial_time(
            request=request,
            occurred_at=payload.withdrawn_at,
            shift=shift,
            now=datetime.now(timezone.utc),
            action_name="POS provider refund withdrawal",
        )
        if withdrawn_at < refund_request.accepted_at:
            raise BusinessRuleError(
                "The withdrawal time predates this accepted provider refund. Refresh "
                "the task and verify the provider record again; nothing was changed."
            )
        withdrawal = PosRefundWithdrawal(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_request_id=refund_request.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            resolution="provider_not_started",
            reason=clean_reason,
            withdrawn_at=withdrawn_at,
            withdrawn_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(withdrawal)
        await session.flush()
    response = _pos_refund_request_read(
        refund_request,
        order=order,
        cash_completion=cash_completion,
        provider_start=provider_start,
        provider_completion=provider_completion,
        withdrawal=withdrawal,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-requests/{refund_request_id}/resolve-provider-payout",
    response_model=PosRefundRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def resolve_pos_provider_refund_payout(
    refund_request_id: UUID,
    payload: PosRefundProviderPayoutResolutionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> PosRefundRequestRead:
    """Owner resolution after durable provider proof that no payout completed."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner can resolve a started provider payout. "
            "Leave this task open until an owner verifies the provider record."
        )
    if not payload.provider_not_completed:
        raise BusinessRuleError(
            "Resolve a started provider payout only after checking the provider "
            "dashboard and confirming that no refund completed."
        )
    verification_reference = payload.verification_reference.strip()
    if len(verification_reference) < 3:
        raise BusinessRuleError(
            "Enter the provider search, case, reversal, or transaction reference used "
            "to verify that no payout completed."
        )
    clean_reason = payload.reason.strip()
    if len(clean_reason) < 3:
        raise BusinessRuleError(
            "Explain how the provider was checked and why the payout was abandoned."
        )
    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundRequestRead.model_validate(replay["body"])
    order, shift, refund_request = await _locked_pos_refund_context(
        session,
        refund_request_id=refund_request_id,
        payload_shift_id=payload.shift_id,
        tenant=tenant,
        operation="resolve this started provider POS refund payout",
    )
    if refund_request.settlement_method == "cash":
        raise BusinessRuleError("This is not a provider refund payout.")
    if int(refund_request.amount_minor) != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The reserved amount differs from this resolution. Refresh the task."
        )
    (
        handoff,
        cash_completion,
        provider_start,
        provider_completion,
        refund,
        withdrawal,
    ) = await _pos_refund_state_rows(
        session, refund_request_id=refund_request.id
    )
    if handoff is not None:
        raise BusinessRuleError(
            "This provider refund has inconsistent cash-handover provenance. A "
            "protected owner must review it."
        )
    if provider_start is None:
        raise BusinessRuleError(
            "No provider payout was started. Use Withdraw reservation instead."
        )
    if refund is not None:
        raise BusinessRuleError(
            "This provider refund is already settled and cannot be resolved as failed."
        )
    if provider_completion is not None:
        raise BusinessRuleError(
            "Provider completion is already recorded. It cannot be resolved as an "
            "abandoned payout; finish its accounting and do not start another payout."
        )
    if withdrawal is None:
        server_now = datetime.now(timezone.utc)
        if payload.provider_checked_at.tzinfo is None:
            raise BusinessRuleError(
                "Provider verification time must include a timezone."
            )
        provider_checked_at = payload.provider_checked_at.astimezone(timezone.utc)
        if provider_checked_at > server_now + timedelta(minutes=5):
            raise BusinessRuleError(
                "Provider verification time is ahead of the server. Correct the "
                "device time and verify the provider record again."
            )
        if provider_checked_at < provider_start.started_at:
            raise BusinessRuleError(
                "Provider verification must be performed after the payout was started."
            )
        withdrawal = PosRefundWithdrawal(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_request_id=refund_request.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            resolution="provider_payout_abandoned",
            reason=clean_reason,
            verification_reference=verification_reference,
            verification_status=payload.provider_status,
            verified_at=provider_checked_at,
            withdrawn_at=server_now,
            withdrawn_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(withdrawal)
        await session.flush()
    response = _pos_refund_request_read(
        refund_request,
        order=order,
        cash_completion=cash_completion,
        provider_start=provider_start,
        provider_completion=provider_completion,
        withdrawal=withdrawal,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


def _pos_refund_evidence_reconciliation_read(
    row: PosRefundEvidenceReconciliation,
    *,
    actor_name: str | None,
) -> PosRefundEvidenceReconciliationRead:
    return PosRefundEvidenceReconciliationRead(
        id=row.id,
        refund_id=row.refund_id,
        evidence_kind=row.evidence_kind,
        proof_reference=row.proof_reference,
        reason=row.reason,
        reconciled_at=row.reconciled_at,
        reconciled_by=row.reconciled_by,
        reconciled_by_name=actor_name,
    )


@router.get(
    "/refund-evidence-reconciliations/pending",
    response_model=list[PendingPosRefundEvidenceRead],
)
async def list_pending_pos_refund_evidence(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
    limit: int = Query(default=100, ge=1, le=200),
) -> list[PendingPosRefundEvidenceRead]:
    """List weak provider/time evidence that still needs owner verification."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner can review POS refund evidence tasks."
        )

    async def _pending_rows(kind: str, flag_column):
        already_resolved = (
            select(PosRefundEvidenceReconciliation.id)
            .where(
                PosRefundEvidenceReconciliation.refund_id == Refund.id,
                PosRefundEvidenceReconciliation.evidence_kind == kind,
            )
            .exists()
        )
        return (
            (
                await session.execute(
                    select(Refund)
                    .where(
                        Refund.company_id == tenant.company_id,
                        Refund.request_id.is_not(None),
                        flag_column.is_(False),
                        ~already_resolved,
                    )
                    .order_by(Refund.settled_at, Refund.id)
                    .limit(limit)
                )
            )
            .scalars()
            .all()
        )

    provider_rows = await _pending_rows(
        "provider_reference", Refund.provider_evidence_reconciled
    )
    time_rows = await _pending_rows("captured_time", Refund.captured_time_reconciled)
    pending = [
        PendingPosRefundEvidenceRead(
            refund_id=row.id,
            request_id=row.request_id,
            order_id=row.order_id,
            evidence_kind="provider_reference",
            amount_minor=int(row.amount_minor),
            settlement_method=str(row.settlement_method),
            settled_at=row.settled_at or row.created_at,
            external_reference=row.external_reference,
            client_occurred_at=row.client_occurred_at,
        )
        for row in provider_rows
    ] + [
        PendingPosRefundEvidenceRead(
            refund_id=row.id,
            request_id=row.request_id,
            order_id=row.order_id,
            evidence_kind="captured_time",
            amount_minor=int(row.amount_minor),
            settlement_method=str(row.settlement_method),
            settled_at=row.settled_at or row.created_at,
            external_reference=row.external_reference,
            client_occurred_at=row.client_occurred_at,
        )
        for row in time_rows
    ]
    return sorted(pending, key=lambda row: (row.settled_at, row.evidence_kind))[:limit]


@router.get(
    "/refund-evidence-reconciliations",
    response_model=list[PosRefundEvidenceReconciliationRead],
)
async def list_pos_refund_evidence_reconciliations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
    limit: int = Query(default=100, ge=1, le=200),
) -> list[PosRefundEvidenceReconciliationRead]:
    """Return the append-only owner evidence-review register."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner can view POS refund evidence reviews."
        )
    rows = (
        await session.execute(
            select(PosRefundEvidenceReconciliation, User.name)
            .join(User, User.id == PosRefundEvidenceReconciliation.reconciled_by)
            .where(
                PosRefundEvidenceReconciliation.company_id == tenant.company_id
            )
            .order_by(
                PosRefundEvidenceReconciliation.reconciled_at.desc(),
                PosRefundEvidenceReconciliation.id.desc(),
            )
            .limit(limit)
        )
    ).all()
    return [
        _pos_refund_evidence_reconciliation_read(row, actor_name=actor_name)
        for row, actor_name in rows
    ]


@router.post(
    "/refund-evidence-reconciliations",
    response_model=PosRefundEvidenceReconciliationRead,
    status_code=status.HTTP_201_CREATED,
)
async def reconcile_pos_refund_evidence(
    payload: PosRefundEvidenceReconciliationCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> PosRefundEvidenceReconciliationRead:
    """Append owner proof without rewriting the original Refund evidence."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner can reconcile POS refund evidence."
        )
    clean_proof = payload.proof_reference.strip()
    clean_reason = payload.reason.strip()
    if len(clean_proof) < 3:
        raise BusinessRuleError(
            "Enter the provider, CCTV, receipt, or case reference used for review."
        )
    if len(clean_reason) < 3:
        raise BusinessRuleError("Explain how the refund evidence was verified.")

    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return PosRefundEvidenceReconciliationRead.model_validate(replay["body"])

    refund = (
        await session.execute(
            select(Refund)
            .where(
                Refund.id == payload.refund_id,
                Refund.company_id == tenant.company_id,
                Refund.request_id.is_not(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if refund is None:
        raise NotFoundError("POS refund settlement not found")
    flag = (
        refund.provider_evidence_reconciled
        if payload.evidence_kind == "provider_reference"
        else refund.captured_time_reconciled
    )
    if flag is not False:
        raise BusinessRuleError(
            "This evidence was not flagged for reconciliation. The immutable refund "
            "record was not changed."
        )

    existing = (
        await session.execute(
            select(PosRefundEvidenceReconciliation)
            .where(
                PosRefundEvidenceReconciliation.refund_id == refund.id,
                PosRefundEvidenceReconciliation.evidence_kind
                == payload.evidence_kind,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None:
        if (
            existing.proof_reference != clean_proof
            or existing.reason != clean_reason
        ):
            raise BusinessRuleError(
                "This refund evidence was already reconciled with different proof. "
                "Open the immutable review register; do not overwrite it."
            )
        original_actor = await session.get(User, existing.reconciled_by)
        response = _pos_refund_evidence_reconciliation_read(
            existing,
            actor_name=original_actor.name if original_actor is not None else None,
        )
    else:
        row = PosRefundEvidenceReconciliation(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_id=refund.id,
            evidence_kind=payload.evidence_kind,
            proof_reference=clean_proof,
            reason=clean_reason,
            reconciled_at=datetime.now(timezone.utc),
            reconciled_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(row)
        await session.flush()
        current_actor = await session.get(User, tenant.user_id)
        response = _pos_refund_evidence_reconciliation_read(
            row,
            actor_name=current_actor.name if current_actor is not None else None,
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


async def _normalized_customer_spend(
    session,
    *,
    company_id: UUID,
    customer_id: UUID,
) -> tuple[int, int, int, int, int]:
    """Return authoritative gross/refund components and net customer spend."""
    pos_gross = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Order.total_minor), 0)).where(
                    Order.company_id == company_id,
                    Order.customer_id == customer_id,
                    Order.status.in_(("paid", "refunded")),
                )
            )
        ).scalar_one()
        or 0
    )
    pos_refunds = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Refund.amount_minor), 0))
                .join(Order, Order.id == Refund.order_id)
                .where(
                    Order.company_id == company_id,
                    Order.customer_id == customer_id,
                )
            )
        ).scalar_one()
        or 0
    )
    membership_gross = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(MembershipPayment.amount_minor), 0))
                .join(
                    CustomerMembership,
                    CustomerMembership.id == MembershipPayment.membership_id,
                )
                .where(
                    MembershipPayment.company_id == company_id,
                    CustomerMembership.customer_id == customer_id,
                )
            )
        ).scalar_one()
        or 0
    )
    membership_refunds = int(
        (
            await session.execute(
                select(
                    func.coalesce(func.sum(MembershipRefundSettlement.amount_minor), 0)
                )
                .join(
                    MembershipPayment,
                    MembershipPayment.id == MembershipRefundSettlement.payment_id,
                )
                .join(
                    CustomerMembership,
                    CustomerMembership.id == MembershipPayment.membership_id,
                )
                .where(
                    MembershipRefundSettlement.company_id == company_id,
                    CustomerMembership.customer_id == customer_id,
                )
            )
        ).scalar_one()
        or 0
    )
    net = pos_gross + membership_gross - pos_refunds - membership_refunds
    return pos_gross, membership_gross, pos_refunds, membership_refunds, net


def _customer_spend_reconciliation_read(
    row: CustomerSpendReconciliation,
    *,
    customer_name: str | None,
) -> CustomerSpendReconciliationRead:
    return CustomerSpendReconciliationRead(
        id=row.id,
        customer_id=row.customer_id,
        customer_name=customer_name,
        pos_refund_id=row.pos_refund_id,
        membership_refund_settlement_id=row.membership_refund_settlement_id,
        source_reconciliation_state=row.source_reconciliation_state,
        source_amount_minor=int(row.source_amount_minor),
        before_total_spent_minor=int(row.before_total_spent_minor),
        after_total_spent_minor=int(row.after_total_spent_minor),
        adjustment_minor=int(row.adjustment_minor),
        pos_gross_minor=int(row.pos_gross_minor),
        membership_gross_minor=int(row.membership_gross_minor),
        pos_refunds_minor=int(row.pos_refunds_minor),
        membership_refunds_minor=int(row.membership_refunds_minor),
        reason=row.reason,
        reconciled_at=row.reconciled_at,
        reconciled_by=row.reconciled_by,
    )


@router.get(
    "/customer-spend-reconciliations/pending",
    response_model=list[PendingCustomerSpendReconciliationRead],
)
async def list_pending_customer_spend_reconciliations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
    limit: int = Query(default=100, ge=1, le=200),
) -> list[PendingCustomerSpendReconciliationRead]:
    """List known underflows and explicit unknown legacy POS LTV outcomes."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner can review customer-spend reconciliation tasks."
        )
    pos_rows = (
        await session.execute(
            select(Refund, Order, Customer)
            .join(Order, Order.id == Refund.order_id)
            .join(Customer, Customer.id == Order.customer_id)
            .outerjoin(
                CustomerSpendReconciliation,
                CustomerSpendReconciliation.pos_refund_id == Refund.id,
            )
            .where(
                Order.company_id == tenant.company_id,
                Refund.customer_spend_reconciled.is_not(True),
                CustomerSpendReconciliation.id.is_(None),
            )
            .limit(limit)
        )
    ).all()
    membership_rows = (
        await session.execute(
            select(MembershipRefundSettlement, Customer)
            .join(
                MembershipPayment,
                MembershipPayment.id == MembershipRefundSettlement.payment_id,
            )
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .outerjoin(
                CustomerSpendReconciliation,
                CustomerSpendReconciliation.membership_refund_settlement_id
                == MembershipRefundSettlement.id,
            )
            .where(
                MembershipRefundSettlement.company_id == tenant.company_id,
                MembershipRefundSettlement.customer_spend_reconciled.is_(False),
                CustomerSpendReconciliation.id.is_(None),
            )
            .limit(limit)
        )
    ).all()
    pending = [
        PendingCustomerSpendReconciliationRead(
            source_type="pos_refund",
            source_id=refund.id,
            order_id=order.id,
            customer_id=customer.id,
            customer_name=customer.name,
            current_total_spent_minor=int(customer.total_spent_minor or 0),
            amount_minor=int(refund.amount_minor),
            settled_at=refund.settled_at or refund.created_at,
            reconciliation_state=(
                "legacy_unknown"
                if refund.customer_spend_reconciled is None
                else "unreconciled"
            ),
            refund_reason_code=refund.reason_code,
        )
        for refund, order, customer in pos_rows
    ] + [
        PendingCustomerSpendReconciliationRead(
            source_type="membership_refund",
            source_id=settlement.id,
            customer_id=customer.id,
            customer_name=customer.name,
            current_total_spent_minor=int(customer.total_spent_minor or 0),
            amount_minor=int(settlement.amount_minor),
            settled_at=settlement.settled_at,
            reconciliation_state="unreconciled",
        )
        for settlement, customer in membership_rows
    ]
    return sorted(pending, key=lambda row: row.settled_at)[:limit]


@router.post(
    "/customer-spend-reconciliations",
    response_model=CustomerSpendReconciliationRead,
    status_code=status.HTTP_201_CREATED,
)
async def reconcile_customer_spend(
    payload: CustomerSpendReconciliationCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> CustomerSpendReconciliationRead:
    """Append an owner-approved LTV repair without rewriting financial facts."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner can reconcile customer lifetime spend."
        )
    if (payload.pos_refund_id is None) == (
        payload.membership_refund_settlement_id is None
    ):
        raise BusinessRuleError(
            "Choose exactly one pending POS or membership refund settlement."
        )
    clean_reason = payload.reason.strip()
    if len(clean_reason) < 3:
        raise BusinessRuleError("Explain why this customer-spend repair is required.")

    idempotency_key, request_hash = _require_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return CustomerSpendReconciliationRead.model_validate(replay["body"])

    customer = (
        await session.execute(
            select(Customer)
            .where(
                Customer.id == payload.customer_id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if customer is None:
        raise NotFoundError("Customer not found")
    before_total = int(customer.total_spent_minor or 0)
    if before_total < 0:
        raise BusinessRuleError(
            "Customer lifetime spend is negative. Repair the source attribution before "
            "applying a reconciliation."
        )
    if before_total != payload.expected_current_total_spent_minor:
        raise BusinessRuleError(
            "Customer lifetime spend changed. Refresh the reconciliation task before "
            "applying a correction."
        )

    source_order: Order | None = None
    if payload.pos_refund_id is not None:
        pos_source = (
            await session.execute(
                select(Refund, Order)
                .join(Order, Order.id == Refund.order_id)
                .where(
                    Refund.id == payload.pos_refund_id,
                    Order.company_id == tenant.company_id,
                    Order.customer_id == customer.id,
                    or_(
                        Refund.company_id.is_(None),
                        Refund.company_id == tenant.company_id,
                    ),
                )
            )
        ).one_or_none()
        source_row, source_order = pos_source if pos_source is not None else (None, None)
        source_type = "pos"
    else:
        source_row = (
            await session.execute(
                select(MembershipRefundSettlement)
                .join(
                    MembershipPayment,
                    MembershipPayment.id == MembershipRefundSettlement.payment_id,
                )
                .join(
                    CustomerMembership,
                    CustomerMembership.id == MembershipPayment.membership_id,
                )
                .where(
                    MembershipRefundSettlement.id
                    == payload.membership_refund_settlement_id,
                    MembershipRefundSettlement.company_id == tenant.company_id,
                    CustomerMembership.customer_id == customer.id,
                )
            )
        ).scalar_one_or_none()
        source_type = "membership"
    if source_row is None:
        raise NotFoundError("Pending customer-spend reconciliation source not found")
    if source_row.customer_spend_reconciled is True:
        raise BusinessRuleError(
            "This settlement is not marked for customer-spend reconciliation."
        )
    source_reconciliation_state = (
        "legacy_unknown"
        if source_type == "pos" and source_row.customer_spend_reconciled is None
        else "unreconciled"
    )
    if source_type == "membership" and source_row.customer_spend_reconciled is not False:
        raise BusinessRuleError(
            "This membership settlement is not marked for customer-spend "
            "reconciliation."
        )

    if source_type == "pos":
        if source_order is None:
            raise NotFoundError("Pending POS customer-spend source order not found")
        source_approver_company = (
            await session.execute(
                select(User.company_id).where(User.id == source_row.approved_by)
            )
        ).scalar_one_or_none()
        paid_total = await _paid_total(session, source_order.id)
        refunded_total = await _refunded_total(session, source_order.id)
        if (
            source_order.status not in {"paid", "refunded"}
            or source_approver_company != tenant.company_id
            or int(source_row.amount_minor or 0) <= 0
            or paid_total <= 0
            or paid_total != int(source_order.total_minor or 0)
            or int(source_row.amount_minor) > paid_total
            or refunded_total > paid_total
        ):
            raise BusinessRuleError(
                "This historical refund cannot be normalized safely because its "
                "company, approver, paid amount, or cumulative refunded amount is "
                "inconsistent. Repair the source attribution before changing lifetime "
                "spend."
            )

    prior_fact_stmt = select(CustomerSpendReconciliation.id).where(
        CustomerSpendReconciliation.company_id == tenant.company_id
    )
    if source_type == "pos":
        prior_fact_stmt = prior_fact_stmt.where(
            CustomerSpendReconciliation.pos_refund_id == source_row.id
        )
    else:
        prior_fact_stmt = prior_fact_stmt.where(
            CustomerSpendReconciliation.membership_refund_settlement_id == source_row.id
        )
    if (await session.execute(prior_fact_stmt)).scalar_one_or_none() is not None:
        raise BusinessRuleError("This customer-spend task was already reconciled.")

    # A phone-only historical order may belong to this customer but cannot be
    # attributed safely. Refuse to manufacture a deterministic-looking LTV
    # until an owner explicitly repairs the normalized customer link.
    if customer.phone:
        possible_legacy_orders = int(
            (
                await session.execute(
                    select(func.count(Order.id)).where(
                        Order.company_id == tenant.company_id,
                        Order.customer_id.is_(None),
                        Order.customer_phone == customer.phone,
                        Order.status.in_(("paid", "refunded")),
                    )
                )
            ).scalar_one()
            or 0
        )
        if possible_legacy_orders:
            raise BusinessRuleError(
                f"{possible_legacy_orders} historical paid order(s) match this phone "
                "but have no verified customer link. Repair those links before "
                "reconciling lifetime spend."
            )

    (
        pos_gross,
        membership_gross,
        pos_refunds,
        membership_refunds,
        after_total,
    ) = await _normalized_customer_spend(
        session,
        company_id=tenant.company_id,
        customer_id=customer.id,
    )
    if after_total < 0:
        raise BusinessRuleError(
            "Normalized refunds exceed verified customer collections. Repair the "
            "source attribution before changing lifetime spend."
        )

    now = datetime.now(timezone.utc)
    reconciliation = CustomerSpendReconciliation(
        id=uuid4(),
        company_id=tenant.company_id,
        customer_id=customer.id,
        pos_refund_id=(source_row.id if source_type == "pos" else None),
        membership_refund_settlement_id=(
            source_row.id if source_type == "membership" else None
        ),
        source_reconciliation_state=source_reconciliation_state,
        source_amount_minor=int(source_row.amount_minor),
        before_total_spent_minor=before_total,
        after_total_spent_minor=after_total,
        adjustment_minor=after_total - before_total,
        pos_gross_minor=pos_gross,
        membership_gross_minor=membership_gross,
        pos_refunds_minor=pos_refunds,
        membership_refunds_minor=membership_refunds,
        reason=clean_reason,
        reconciled_at=now,
        reconciled_by=tenant.user_id,
        idempotency_key=idempotency_key,
    )
    session.add(reconciliation)
    await session.flush()
    customer.total_spent_minor = after_total
    await session.flush()
    response = _customer_spend_reconciliation_read(
        reconciliation,
        customer_name=customer.name,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get("/refund-requests", response_model=list[PosRefundRequestRead])
async def list_pos_refund_requests(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.refund")),
    unresolved: bool = True,
    shift_id: UUID | None = None,
    client_action_id: str | None = None,
    limit: int = Query(default=100, ge=1, le=200),
) -> list[PosRefundRequestRead]:
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Select this tablet's branch and terminal first.")
    stmt = (
        select(
            PosRefundRequest,
            Order,
            PosRefundCashHandoff,
            PosRefundCashHandoffCompletion,
            PosRefundProviderPayoutStart,
            PosRefundProviderSettlement,
            Refund,
            PosRefundWithdrawal,
        )
        .join(Order, Order.id == PosRefundRequest.order_id)
        .outerjoin(
            PosRefundCashHandoff,
            PosRefundCashHandoff.refund_request_id == PosRefundRequest.id,
        )
        .outerjoin(
            PosRefundCashHandoffCompletion,
            PosRefundCashHandoffCompletion.refund_request_id == PosRefundRequest.id,
        )
        .outerjoin(
            PosRefundProviderPayoutStart,
            PosRefundProviderPayoutStart.refund_request_id == PosRefundRequest.id,
        )
        .outerjoin(
            PosRefundProviderSettlement,
            PosRefundProviderSettlement.refund_request_id == PosRefundRequest.id,
        )
        .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
        .outerjoin(
            PosRefundWithdrawal,
            PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
        )
        .where(
            PosRefundRequest.company_id == tenant.company_id,
            PosRefundRequest.branch_id == tenant.branch_id,
            PosRefundRequest.terminal_id == tenant.terminal_id,
        )
        .order_by(PosRefundRequest.accepted_at.desc())
        .limit(limit)
    )
    if unresolved:
        stmt = stmt.where(Refund.id.is_(None), PosRefundWithdrawal.id.is_(None))
    if shift_id is not None:
        stmt = stmt.where(PosRefundRequest.shift_id == shift_id)
    if client_action_id:
        stmt = stmt.where(PosRefundRequest.client_action_id == client_action_id.strip())
    rows = (await session.execute(stmt)).all()
    actor_ids = {
        actor_id
        for (
            refund_request,
            _order,
            handoff,
            cash_completion,
            provider_start,
            provider_completion,
            refund,
            withdrawal,
        ) in rows
        for actor_id in (
            refund_request.approved_by,
            handoff.started_by if handoff else None,
            cash_completion.recorded_by if cash_completion else None,
            provider_start.started_by if provider_start else None,
            provider_completion.settled_by if provider_completion else None,
            refund.settled_by if refund else None,
            withdrawal.withdrawn_by if withdrawal else None,
        )
        if actor_id is not None
    }
    actor_names = {
        user_id: name
        for user_id, name in (
            await session.execute(
                select(User.id, User.name).where(
                    User.company_id == tenant.company_id,
                    User.id.in_(actor_ids),
                )
            )
        ).all()
    }
    return [
        _pos_refund_request_read(
            refund_request,
            order=order,
            handoff=handoff,
            cash_completion=cash_completion,
            provider_start=provider_start,
            provider_completion=provider_completion,
            refund=refund,
            withdrawal=withdrawal,
            actor_names=actor_names,
        )
        for (
            refund_request,
            order,
            handoff,
            cash_completion,
            provider_start,
            provider_completion,
            refund,
            withdrawal,
        ) in rows
    ]


@router.post("/shifts/open", status_code=status.HTTP_201_CREATED)
async def open_shift(
    payload: ShiftOpenRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.shift.open")),
) -> dict:
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required to open a shift")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
    # Serialize shift opening per terminal so two simultaneous clients cannot
    # create two live shifts for the same drawer.
    await session.execute(
        select(Terminal).where(Terminal.id == tenant.terminal_id).with_for_update()
    )
    existing = (
        await session.execute(
            select(Shift).where(
                Shift.company_id == tenant.company_id,
                Shift.terminal_id == tenant.terminal_id,
                Shift.status == "open",
            )
        )
    ).scalar_one_or_none()
    if existing:
        if existing.opened_by != tenant.user_id:
            raise BusinessRuleError(
                "A shift is already open on this terminal by another staff member. "
                "Open the Shifts tab to see who is responsible for it."
            )
        if int(existing.opening_float_minor or 0) != payload.opening_float_minor:
            raise BusinessRuleError(
                "This shift is already open with a different opening float. "
                "Use the existing shift instead of changing its accountable cash start."
            )
        return {"id": str(existing.id), "status": existing.status}
    shift = Shift(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        opened_by=tenant.user_id,
        opened_at=datetime.now(timezone.utc),
        opening_float_minor=payload.opening_float_minor,
        expected_minor=payload.opening_float_minor,
        status="open",
    )
    session.add(shift)
    return {"id": str(shift.id), "status": "open"}


@router.post("/shifts/{shift_id}/close")
async def close_shift(
    shift_id: UUID,
    payload: ShiftCloseRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.shift.close")),
) -> dict:
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="closing a shift",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="close this shift",
    )
    if shift.status == "closed":
        if shift.counted_minor is None:
            raise BusinessRuleError(
                "Shift is already closed but its saved counted amount is missing. "
                "Ask a protected owner to reconcile the shift before retrying."
            )
        if int(shift.counted_minor) != payload.counted_minor:
            raise BusinessRuleError(
                "Shift is already closed with a different counted amount."
            )
        return {
            "id": str(shift.id),
            "status": shift.status,
            "variance_minor": shift.variance_minor,
        }
    if shift.status != "open":
        raise BusinessRuleError(f"Shift is {shift.status} and cannot be closed.")
    unfinished_orders = int(
        (
            await session.execute(
                select(func.count(Order.id)).where(
                    Order.shift_id == shift.id,
                    Order.status.in_(("open", "held")),
                )
            )
        ).scalar_one()
        or 0
    )
    if unfinished_orders:
        raise BusinessRuleError(
            f"cannot close shift with {unfinished_orders} unfinished order(s)"
        )
    unacknowledged_kitchen_cancellations = int(
        (
            await session.execute(
                select(func.count(OrderLine.id))
                .join(Order, Order.id == OrderLine.order_id)
                .where(
                    Order.shift_id == shift.id,
                    OrderLine.kitchen_released_at.is_not(None),
                    OrderLine.voided_at.is_not(None),
                    OrderLine.kitchen_void_acknowledged_at.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if unacknowledged_kitchen_cancellations:
        raise BusinessRuleError(
            "cannot close shift with "
            f"{unacknowledged_kitchen_cancellations} kitchen cancellation(s) still "
            "waiting for acknowledgement. Open KDS, review each cancelled item, and "
            "acknowledge it before closing the shift."
        )
    running_sessions = int(
        (
            await session.execute(
                select(func.count(GamingSession.id)).where(
                    GamingSession.shift_id == shift.id,
                    GamingSession.status.in_(("active", "paused")),
                )
            )
        ).scalar_one()
        or 0
    )
    if running_sessions:
        raise BusinessRuleError(
            f"cannot close shift with {running_sessions} running gaming session(s)"
        )
    unbilled_sessions = int(
        (
            await session.execute(
                select(func.count(GamingSession.id)).where(
                    GamingSession.shift_id == shift.id,
                    GamingSession.status == "ended",
                    GamingSession.order_id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if unbilled_sessions:
        raise BusinessRuleError(
            f"cannot close shift with {unbilled_sessions} stopped session(s) "
            "not yet sent to POS"
        )
    membership_payments_due = int(
        (
            await session.execute(
                select(func.count(MembershipPaymentRequest.id))
                .outerjoin(
                    MembershipPayment,
                    MembershipPayment.request_id == MembershipPaymentRequest.id,
                )
                .outerjoin(
                    MembershipPaymentRequestResolution,
                    MembershipPaymentRequestResolution.request_id
                    == MembershipPaymentRequest.id,
                )
                .where(
                    MembershipPaymentRequest.shift_id == shift.id,
                    MembershipPayment.id.is_(None),
                    MembershipPaymentRequestResolution.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if membership_payments_due:
        raise BusinessRuleError(
            f"cannot close shift with {membership_payments_due} accepted membership "
            "payment task(s) still unresolved. Open Memberships and either finish "
            "the server-confirmed cash/provider collection, or have a protected "
            "owner withdraw or resolve it after verifying that no money moved."
        )
    unresolved_refund_recovery_ids = (
        (
            await session.execute(
                select(MembershipRefundAttemptRecovery.id)
                .outerjoin(
                    MembershipRefundAttemptResolution,
                    MembershipRefundAttemptResolution.recovery_id
                    == MembershipRefundAttemptRecovery.id,
                )
                .where(
                    MembershipRefundAttemptRecovery.company_id == shift.company_id,
                    MembershipRefundAttemptRecovery.source_branch_id == shift.branch_id,
                    MembershipRefundAttemptRecovery.source_terminal_id
                    == shift.terminal_id,
                    MembershipRefundAttemptRecovery.source_shift_id == shift.id,
                    MembershipRefundAttemptResolution.id.is_(None),
                )
                .order_by(MembershipRefundAttemptRecovery.registered_at)
                .with_for_update(of=MembershipRefundAttemptRecovery)
            )
        )
        .scalars()
        .all()
    )
    if unresolved_refund_recovery_ids:
        first_recovery_id = unresolved_refund_recovery_ids[0]
        raise BusinessRuleError(
            f"cannot close shift with {len(unresolved_refund_recovery_ids)} unresolved "
            "saved membership refund recovery task(s). Open Memberships > Refund "
            f"Recovery and resolve task {first_recovery_id} after a protected owner "
            "verifies whether money moved. Do not repeat the refund."
        )
    membership_refunds_due = int(
        (
            await session.execute(
                select(func.count(MembershipRefund.id))
                .outerjoin(
                    MembershipRefundSettlement,
                    MembershipRefundSettlement.refund_id == MembershipRefund.id,
                )
                .outerjoin(
                    MembershipRefundResolution,
                    MembershipRefundResolution.refund_id == MembershipRefund.id,
                )
                .where(
                    MembershipRefund.shift_id == shift.id,
                    MembershipRefundSettlement.id.is_(None),
                    MembershipRefundResolution.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if membership_refunds_due:
        raise BusinessRuleError(
            f"cannot close shift with {membership_refunds_due} accepted membership "
            "refund task(s) still unresolved. Open Memberships and either finish the "
            "server-confirmed cash handover/provider payout, or have a protected owner "
            "resolve it after verifying that no money moved."
        )
    pos_refunds_due = int(
        (
            await session.execute(
                select(func.count(PosRefundRequest.id))
                .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
                .outerjoin(
                    PosRefundWithdrawal,
                    PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
                )
                .where(
                    PosRefundRequest.shift_id == shift.id,
                    Refund.id.is_(None),
                    PosRefundWithdrawal.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if pos_refunds_due:
        raise BusinessRuleError(
            f"cannot close shift with {pos_refunds_due} accepted POS refund(s) still "
            "unresolved. Open Refunds and finish the server-confirmed cash handover or "
            "provider payout, or ask a protected owner to resolve the task after "
            "verifying that no money moved."
        )
    shift.closed_at = datetime.now(timezone.utc)
    shift.counted_minor = payload.counted_minor
    shift.variance_minor = payload.counted_minor - (shift.expected_minor or 0)
    shift.status = "closed"
    return {
        "id": str(shift.id),
        "status": shift.status,
        "variance_minor": shift.variance_minor,
    }
