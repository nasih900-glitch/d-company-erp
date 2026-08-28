"""Membership / subscription endpoints.

  GET   /memberships/tiers                       — list tiers (Silver/Gold/Platinum)
  POST  /memberships/tiers                       — create / customize a tier
  PATCH /memberships/tiers/{id}                  — edit a tier
  POST  /memberships/subscribe                   — subscribe a customer (cash for now;
                                                    Razorpay flow lands when keys arrive)
  GET   /memberships/customer/{customer_id}      — current active subscription (or null)
  POST  /memberships/{id}/cancel                 — cancel autorenew (still valid until expiry)
  POST  /memberships/{id}/refund                 — full financial reversal + end entitlement
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Header, Query, Request, Response, status
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ForbiddenError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.pricing_lock import require_pricing_unlock
from app.core.tenant import TenantContext
from app.models import (
    Branch,
    Company,
    Customer,
    CustomerMembership,
    MembershipBenefitReservation,
    MembershipCustomerSpendApplication,
    MembershipEvidenceReconciliation,
    MembershipPayment,
    MembershipPaymentCashCollection,
    MembershipPaymentCompletion,
    MembershipPaymentProviderAction,
    MembershipPaymentAttemptResolution,
    MembershipPaymentRequest,
    MembershipPaymentRequestResolution,
    MembershipRefund,
    MembershipRefundAttemptRecovery,
    MembershipRefundAttemptResolution,
    MembershipRefundCashHandoff,
    MembershipRefundCompletion,
    MembershipRefundProviderAction,
    MembershipRefundResolution,
    MembershipRefundSettlement,
    MembershipTier,
    Order,
    PosRefundRequest,
    PosRefundWithdrawal,
    Refund,
    Shift,
    User,
)
from app.services.pos.pricing import InvoiceNumberService
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_shift_opener,
)

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
    annual_price_minor: int | None = Field(default=None, ge=0)
    food_discount_pct: float = Field(ge=0, le=1, default=0)
    gaming_discount_pct: float = Field(ge=0, le=1, default=0)
    hookah_discount_pct: float = Field(ge=0, le=1, default=0)
    point_multiplier: float = Field(ge=1, le=10, default=1)
    free_gaming_minutes_per_week: int = Field(default=0, ge=0)
    free_hookah_per_month: int = Field(default=0, ge=0)
    priority_booking: bool = False
    description: str | None = Field(default=None, max_length=500)
    sort_order: int = 0


class TierUpdate(BaseModel):
    name: str | None = None
    monthly_price_minor: int | None = Field(default=None, ge=0)
    annual_price_minor: int | None = Field(default=None, ge=0)
    food_discount_pct: float | None = Field(default=None, ge=0, le=1)
    gaming_discount_pct: float | None = Field(default=None, ge=0, le=1)
    hookah_discount_pct: float | None = Field(default=None, ge=0, le=1)
    point_multiplier: float | None = Field(default=None, ge=1, le=10)
    free_gaming_minutes_per_week: int | None = Field(default=None, ge=0)
    free_hookah_per_month: int | None = Field(default=None, ge=0)
    priority_booking: bool | None = None
    description: str | None = Field(default=None, max_length=500)
    sort_order: int | None = None


class SubscribeRequest(BaseModel):
    customer_id: UUID
    tier_id: UUID
    shift_id: UUID
    # Price shown and accepted at capture time. Offline sync must never charge
    # a later tier price silently.
    expected_amount_minor: int = Field(gt=0)
    # Timestamp captured with the local outbox action. It is validated against
    # the request provenance and bounded before becoming the payment date.
    collected_at: datetime
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
    revoked_at: datetime | None = None
    auto_renew: bool
    amount_paid_minor: int
    payment_id: UUID | None = None
    payment_method: str | None = None
    payment_shift_id: UUID | None = None
    payment_receipt_no: str | None = None
    payment_paid_at: datetime | None = None
    payment_evidence_occurred_at: datetime | None = None
    payment_evidence_time_untrusted: bool = False
    payment_provider_evidence_reconciled: bool = True
    refund_id: UUID | None = None
    refund_status: Literal[
        "accepted_cash_due",
        "accepted_provider_due",
        "cash_handoff_in_progress",
        "provider_action_in_progress",
        "payout_completed_pending_posting",
        "settled",
        "withdrawn",
    ] | None = None
    refund_accepted_at: datetime | None = None
    refunded_at: datetime | None = None
    refund_method: str | None = None
    refund_receipt_no: str | None = None
    refund_external_reference: str | None = None
    refund_evidence_occurred_at: datetime | None = None
    refund_evidence_time_untrusted: bool = False
    refund_provider_evidence_reconciled: bool = True
    refund_customer_spend_reconciled: bool = True
    is_active: bool


class MembershipPaymentRequestCreate(BaseModel):
    customer_id: UUID
    tier_id: UUID
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    billing_cycle: Literal["monthly", "annual"] = "monthly"
    paid_via: Literal["cash", "card", "upi", "razorpay"]
    client_action_id: str = Field(min_length=8, max_length=160)


class MembershipPaymentCashCollectionRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    ready_to_collect: bool


class MembershipPaymentProviderActionRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    ready_to_start: bool


class MembershipPaymentSettlementRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    collected_at: datetime
    payment_received: bool
    external_reference: str | None = Field(default=None, max_length=200)
    action_takeover_confirmed: bool = False
    action_takeover_reason: str | None = Field(default=None, max_length=500)


class MembershipPaymentFinalizationRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)


class MembershipPaymentRequestWithdrawal(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    resolution: Literal[
        "payment_not_collected",
        "cash_not_collected",
        "cash_returned",
        "provider_not_completed",
        "provider_reversed",
    ]
    reason: str = Field(min_length=3, max_length=500)
    external_reference: str | None = Field(default=None, max_length=200)
    action_state_verified: bool = False
    provider_verification_status: Literal["not_completed", "reversed"] | None = None
    provider_verification_reference: str | None = Field(default=None, max_length=200)
    provider_evidence_occurred_at: datetime | None = None
    cash_return_confirmed: bool = False
    action_takeover_confirmed: bool = False
    action_takeover_reason: str | None = Field(default=None, max_length=500)


class MembershipPaymentRequestRead(BaseModel):
    id: UUID
    customer_id: UUID
    tier_id: UUID
    shift_id: UUID
    billing_cycle: str
    paid_via: str
    amount_minor: int
    customer_name: str | None
    customer_phone: str
    tier_code: str
    tier_name: str
    status: Literal[
        "accepted_payment_due",
        "cash_collection_in_progress",
        "provider_action_in_progress",
        "payment_completed_pending_posting",
        "settled",
        "withdrawn",
    ]
    accepted_at: datetime
    prepared_by: UUID
    prepared_by_name: str | None = None
    collection_started_at: datetime | None = None
    value_completed_at: datetime | None = None
    value_completed_by: UUID | None = None
    value_completed_by_name: str | None = None
    action_started_by: UUID | None = None
    action_started_by_name: str | None = None
    action_kind: Literal["cash_collection", "provider_payment"] | None = None
    settled_at: datetime | None = None
    settled_by: UUID | None = None
    settled_by_name: str | None = None
    membership_id: UUID | None = None
    payment_id: UUID | None = None
    receipt_no: str | None = None
    external_reference: str | None = None
    evidence_occurred_at: datetime | None = None
    evidence_time_untrusted: bool = False
    provider_evidence_reconciled: bool = True
    customer_spend_reconciled: bool = False
    resolution: str | None = None
    resolved_at: datetime | None = None
    resolved_by: UUID | None = None
    resolved_by_name: str | None = None
    action_state_verified: bool = False
    provider_verification_status: str | None = None
    provider_verification_reference: str | None = None
    provider_checked_at: datetime | None = None
    cash_return_confirmed: bool = False
    action_takeover_confirmed: bool = False
    action_takeover_reason: str | None = None
    client_action_id: str


class MembershipRefundRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    method: Literal["cash", "card", "upi", "razorpay"]
    reason: str = Field(min_length=3, max_length=500)
    # Compatibility fields retained while old clients age out. The acceptance
    # call rejects either field: every rail must be reserved server-side before
    # cash leaves the drawer or an external provider refund is started.
    settled_at: datetime | None = None
    external_reference: str | None = Field(default=None, max_length=200)


class CashMembershipRefundSettlementRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    settled_at: datetime
    cash_handed_over: bool
    action_takeover_confirmed: bool = False
    action_takeover_reason: str | None = Field(default=None, max_length=500)


class MembershipRefundCashHandoffRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    ready_to_handover: bool


class MembershipRefundProviderActionRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    ready_to_start: bool


class ProviderMembershipRefundSettlementRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    settled_at: datetime
    provider_refund_completed: bool
    external_reference: str = Field(min_length=1, max_length=200)
    action_takeover_confirmed: bool = False
    action_takeover_reason: str | None = Field(default=None, max_length=500)


class MembershipRefundFinalizationRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)


class MembershipRefundResolutionRequest(BaseModel):
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    resolution: Literal[
        "cash_not_handed_over",
        "cash_returned",
        "provider_not_completed",
        "provider_reversed",
    ]
    reason: str = Field(min_length=3, max_length=500)
    external_reference: str | None = Field(default=None, max_length=200)
    action_state_verified: bool = False
    provider_verification_status: Literal["not_completed", "reversed"] | None = None
    provider_verification_reference: str | None = Field(default=None, max_length=200)
    provider_evidence_occurred_at: datetime | None = None
    cash_return_confirmed: bool = False
    action_takeover_confirmed: bool = False
    action_takeover_reason: str | None = Field(default=None, max_length=500)


class CashMembershipRefundWithdrawalRequest(BaseModel):
    shift_id: UUID
    cash_not_handed_over: bool
    reason: str = Field(min_length=3, max_length=500)


class MembershipRefundRead(BaseModel):
    id: UUID
    membership_id: UUID
    payment_id: UUID
    shift_id: UUID
    method: str
    amount_minor: int
    accepted_at: datetime
    status: Literal[
        "accepted_cash_due",
        "accepted_provider_due",
        "cash_handoff_in_progress",
        "provider_action_in_progress",
        "payout_completed_pending_posting",
        "settled",
        "withdrawn",
    ]
    handoff_started_at: datetime | None = None
    payout_completed_at: datetime | None = None
    payout_completed_by: UUID | None = None
    payout_completed_by_name: str | None = None
    accepted_by: UUID | None = None
    accepted_by_name: str | None = None
    action_started_by: UUID | None = None
    action_started_by_name: str | None = None
    action_kind: Literal["cash_handoff", "provider_refund"] | None = None
    settled_at: datetime | None = None
    settled_by: UUID | None = None
    settled_by_name: str | None = None
    reason: str
    external_reference: str | None = None
    receipt_no: str | None = None
    entitlement_restored: bool = False
    customer_id: UUID | None = None
    customer_name: str | None = None
    customer_phone: str | None = None
    tier_name: str | None = None
    original_payment_receipt_no: str | None = None
    resolution: str | None = None
    resolution_reason: str | None = None
    resolved_at: datetime | None = None
    resolved_by: UUID | None = None
    resolved_by_name: str | None = None
    evidence_occurred_at: datetime | None = None
    evidence_time_untrusted: bool = False
    provider_evidence_reconciled: bool = True
    customer_spend_reconciled: bool = True
    action_state_verified: bool = False
    provider_verification_status: str | None = None
    provider_verification_reference: str | None = None
    provider_checked_at: datetime | None = None
    cash_return_confirmed: bool = False
    action_takeover_confirmed: bool = False
    action_takeover_reason: str | None = None


class MembershipPaymentAttemptResolutionRequest(BaseModel):
    original_client_action_id: str = Field(min_length=1, max_length=160)
    customer_id: UUID
    tier_id: UUID
    shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    paid_via: Literal["cash", "card", "upi", "razorpay"]
    resolution: Literal[
        "payment_not_collected",
        "cash_returned",
        "provider_not_completed",
        "provider_reversed",
    ]
    reason: str = Field(min_length=3, max_length=500)
    external_reference: str | None = Field(default=None, max_length=200)
    provider_verification_status: Literal["not_completed", "reversed"] | None = None
    provider_evidence_occurred_at: datetime | None = None
    cash_return_confirmed: bool = False


class MembershipPaymentAttemptResolutionRead(BaseModel):
    id: UUID
    original_client_action_id: str
    customer_id: UUID
    tier_id: UUID
    shift_id: UUID
    expected_amount_minor: int
    paid_via: str
    resolution: str
    reason: str
    external_reference: str | None
    provider_verification_status: str | None = None
    provider_checked_at: datetime | None = None
    provider_evidence_reconciled: bool = True
    evidence_occurred_at: datetime | None = None
    evidence_time_untrusted: bool = False
    cash_return_confirmed: bool = False
    resolved_at: datetime


class MembershipRefundAttemptRegistrationRequest(BaseModel):
    original_client_action_id: str = Field(min_length=1, max_length=160)
    customer_id: UUID
    membership_id: UUID
    payment_id: UUID
    source_shift_id: UUID
    expected_amount_minor: int = Field(gt=0)
    paid_via: Literal["cash", "card", "upi", "razorpay"]
    captured_at: datetime


class MembershipRefundAttemptRecoveryRead(BaseModel):
    id: UUID
    original_client_action_id: str
    customer_id: UUID
    membership_id: UUID
    payment_id: UUID
    source_shift_id: UUID
    expected_amount_minor: int
    paid_via: str
    captured_at: datetime
    captured_time_untrusted: bool = False
    registered_at: datetime
    registered_by: UUID
    registered_by_name: str | None = None
    status: Literal["unresolved", "resolved"]
    resolution_id: UUID | None = None


class MembershipRefundAttemptResolutionRequest(BaseModel):
    original_client_action_id: str = Field(min_length=1, max_length=160)
    customer_id: UUID
    membership_id: UUID
    payment_id: UUID
    source_shift_id: UUID
    reconciliation_shift_id: UUID | None = None
    expected_amount_minor: int = Field(gt=0)
    paid_via: Literal["cash", "card", "upi", "razorpay"]
    outcome: Literal[
        "no_payout",
        "cash_not_handed_over",
        "cash_handed_over",
        "provider_reversed",
        "provider_completed",
    ]
    reason: str = Field(min_length=3, max_length=500)
    provider_status: Literal["not_completed", "reversed", "completed"] | None = None
    verification_reference: str | None = Field(default=None, max_length=200)
    evidence_occurred_at: datetime | None = None
    cash_handover_confirmed: bool = False


class MembershipRefundAttemptResolutionRead(BaseModel):
    id: UUID
    recovery_id: UUID
    original_client_action_id: str
    customer_id: UUID
    membership_id: UUID
    payment_id: UUID
    source_shift_id: UUID
    reconciliation_shift_id: UUID | None
    refund_id: UUID | None
    expected_amount_minor: int
    paid_via: str
    outcome: str
    reason: str
    provider_status: str | None
    verification_reference: str | None
    checked_at: datetime
    evidence_occurred_at: datetime | None
    evidence_time_untrusted: bool
    provider_evidence_reconciled: bool
    cash_handover_confirmed: bool
    resolved_at: datetime
    resolved_by: UUID
    resolved_by_name: str | None = None
    financial_status: Literal[
        "no_financial_movement",
        "payout_reversed",
        "payout_completed_pending_posting",
        "settled",
    ]
    refund_receipt_no: str | None = None
    customer_spend_reconciled: bool = True


class MembershipEvidenceReconciliationCreate(BaseModel):
    target_type: Literal[
        "payment",
        "refund_settlement",
        "payment_completion",
        "refund_completion",
        "payment_request_resolution",
        "refund_resolution",
        "payment_attempt_resolution",
        "refund_attempt_resolution",
    ]
    target_id: UUID
    evidence_kind: Literal["provider_reference", "captured_time"]
    proof_reference: str = Field(min_length=3, max_length=200)
    verified_occurred_at: datetime | None = None
    reason: str = Field(min_length=3, max_length=500)


class MembershipEvidenceReconciliationRead(BaseModel):
    id: UUID
    target_type: str
    target_id: UUID
    evidence_kind: str
    proof_reference: str
    verified_occurred_at: datetime | None
    reason: str
    reconciled_at: datetime
    reconciled_by: UUID
    reconciled_by_name: str | None = None


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
    payment = (
        await session.execute(
            select(MembershipPayment).where(MembershipPayment.membership_id == sub.id)
        )
    ).scalar_one_or_none()
    refund = None
    handoff = None
    provider_action = None
    refund_completion = None
    settlement = None
    resolution = None
    if payment is not None:
        refund = (
            await session.execute(
                select(MembershipRefund)
                .where(MembershipRefund.payment_id == payment.id)
                .order_by(MembershipRefund.accepted_at.desc(), MembershipRefund.id.desc())
                .limit(1)
            )
        ).scalar_one_or_none()
        if refund is not None:
            handoff = (
                await session.execute(
                    select(MembershipRefundCashHandoff).where(
                        MembershipRefundCashHandoff.refund_id == refund.id
                    )
                )
            ).scalar_one_or_none()
            provider_action = (
                await session.execute(
                    select(MembershipRefundProviderAction).where(
                        MembershipRefundProviderAction.refund_id == refund.id
                    )
                )
            ).scalar_one_or_none()
            refund_completion = (
                await session.execute(
                    select(MembershipRefundCompletion).where(
                        MembershipRefundCompletion.refund_id == refund.id
                    )
                )
            ).scalar_one_or_none()
            settlement = (
                await session.execute(
                    select(MembershipRefundSettlement).where(
                        MembershipRefundSettlement.refund_id == refund.id
                    )
                )
            ).scalar_one_or_none()
            resolution = (
                await session.execute(
                    select(MembershipRefundResolution).where(
                        MembershipRefundResolution.refund_id == refund.id
                    )
                )
            ).scalar_one_or_none()
    now = datetime.now(timezone.utc)
    return SubscriptionRead(
        id=sub.id, customer_id=sub.customer_id, tier_id=sub.tier_id,
        tier_code=tier.code if tier else "?",
        tier_name=tier.name if tier else "Unknown",
        billing_cycle=sub.billing_cycle,
        starts_at=sub.starts_at, expires_at=sub.expires_at,
        cancelled_at=sub.cancelled_at, revoked_at=sub.revoked_at, auto_renew=sub.auto_renew,
        amount_paid_minor=sub.amount_paid_minor,
        payment_id=payment.id if payment else None,
        payment_method=payment.method if payment else None,
        payment_shift_id=payment.shift_id if payment else None,
        payment_receipt_no=payment.receipt_no if payment else None,
        payment_paid_at=payment.paid_at if payment else None,
        payment_evidence_occurred_at=(
            payment.evidence_occurred_at if payment else None
        ),
        payment_evidence_time_untrusted=(
            payment.evidence_time_untrusted if payment else False
        ),
        payment_provider_evidence_reconciled=(
            payment.provider_evidence_reconciled if payment else True
        ),
        refund_id=refund.id if refund else None,
        refund_status=(
            "settled"
            if settlement
            else "withdrawn"
            if resolution
            else "payout_completed_pending_posting"
            if refund_completion
            else "cash_handoff_in_progress"
            if handoff
            else "provider_action_in_progress"
            if provider_action
            else "accepted_cash_due"
            if refund and refund.method == "cash"
            else "accepted_provider_due"
            if refund
            else None
        ),
        refund_accepted_at=refund.accepted_at if refund else None,
        refunded_at=settlement.settled_at if settlement else None,
        refund_method=refund.method if refund else None,
        refund_receipt_no=settlement.receipt_no if settlement else None,
        refund_external_reference=settlement.external_ref if settlement else None,
        refund_evidence_occurred_at=(
            settlement.evidence_occurred_at if settlement else None
        ),
        refund_evidence_time_untrusted=(
            settlement.evidence_time_untrusted if settlement else False
        ),
        refund_provider_evidence_reconciled=(
            settlement.provider_evidence_reconciled if settlement else True
        ),
        refund_customer_spend_reconciled=(
            settlement.customer_spend_reconciled if settlement else True
        ),
        # cancelled_at stops auto-renew; the already-paid term remains valid.
        is_active=sub.starts_at <= now < sub.expires_at and sub.revoked_at is None,
    )


def _verified_takeover_attestation(
    *,
    started_by: UUID,
    current_user_id: UUID,
    confirmed: bool,
    reason: str | None,
    started_by_name: str,
    action_label: str,
) -> tuple[bool, str | None]:
    """Validate an explicit protected-owner takeover of an in-progress action."""
    clean_reason = reason.strip() if reason else None
    if started_by == current_user_id:
        if confirmed or clean_reason:
            raise BusinessRuleError(
                f"You started this {action_label}; do not record a takeover. "
                "Verify the outcome and complete the task normally."
            )
        return False, None
    if not confirmed or clean_reason is None or len(clean_reason) < 3:
        raise BusinessRuleError(
            f"{started_by_name} started this {action_label}. Before taking over, "
            "a protected owner must verify the drawer/provider state and record why "
            "the original owner cannot finish it. Unknown state must remain unresolved."
        )
    return True, clean_reason


async def _require_same_begin_actor(
    session,
    *,
    started_by: UUID,
    current_user_id: UUID,
    started_at: datetime,
    action_label: str,
) -> None:
    """Converge same-actor begin retries; never let another actor re-begin."""
    if started_by == current_user_id:
        return
    starter = await session.get(User, started_by)
    starter_name = starter.name if starter else str(started_by)
    raise BusinessRuleError(
        f"{starter_name} started this {action_label} at "
        f"{started_at.astimezone(timezone.utc).isoformat()}. Do not repeat the money "
        "action. A protected owner may take over only from the verified completion "
        "or reversal step after checking the drawer/provider result."
    )


def _refund_to_read(
    refund: MembershipRefund,
    *,
    membership_id: UUID,
    handoff: MembershipRefundCashHandoff | None = None,
    provider_action: MembershipRefundProviderAction | None = None,
    completion: MembershipRefundCompletion | None = None,
    settlement: MembershipRefundSettlement | None = None,
    resolution: MembershipRefundResolution | None = None,
    entitlement_restored: bool = False,
    customer: Customer | None = None,
    tier: MembershipTier | None = None,
    payment: MembershipPayment | None = None,
    accepted_by_name: str | None = None,
    action_started_by_name: str | None = None,
    completed_by_name: str | None = None,
    settled_by_name: str | None = None,
    resolved_by_name: str | None = None,
) -> MembershipRefundRead:
    return MembershipRefundRead(
        id=refund.id,
        membership_id=membership_id,
        payment_id=refund.payment_id,
        shift_id=refund.shift_id,
        method=refund.method,
        amount_minor=refund.amount_minor,
        accepted_at=refund.accepted_at,
        status=(
            "settled"
            if settlement is not None
            else "withdrawn"
            if resolution is not None
            else "payout_completed_pending_posting"
            if completion is not None
            else "cash_handoff_in_progress"
            if handoff is not None
            else "provider_action_in_progress"
            if provider_action is not None
            else "accepted_cash_due"
            if refund.method == "cash"
            else "accepted_provider_due"
        ),
        handoff_started_at=handoff.started_at if handoff else None,
        payout_completed_at=completion.completed_at if completion else None,
        payout_completed_by=completion.completed_by if completion else None,
        payout_completed_by_name=(
            completed_by_name if completion is not None else None
        ),
        accepted_by=refund.approved_by,
        accepted_by_name=accepted_by_name,
        action_started_by=(
            handoff.started_by
            if handoff
            else provider_action.started_by
            if provider_action
            else None
        ),
        action_started_by_name=action_started_by_name,
        action_kind=(
            "cash_handoff"
            if handoff
            else "provider_refund"
            if provider_action
            else None
        ),
        settled_at=settlement.settled_at if settlement else None,
        settled_by=settlement.settled_by if settlement else None,
        settled_by_name=(
            settled_by_name
            or (
                action_started_by_name
                if settlement is not None
                and (
                    (handoff is not None and settlement.settled_by == handoff.started_by)
                    or (
                        provider_action is not None
                        and settlement.settled_by == provider_action.started_by
                    )
                )
                else None
            )
        ),
        reason=refund.reason,
        external_reference=(
            settlement.external_ref
            if settlement
            else completion.external_reference
            if completion
            else resolution.external_reference
            if resolution
            else None
        ),
        receipt_no=settlement.receipt_no if settlement else None,
        entitlement_restored=entitlement_restored,
        customer_id=customer.id if customer else None,
        customer_name=customer.name if customer else None,
        customer_phone=customer.phone if customer else None,
        tier_name=tier.name if tier else None,
        original_payment_receipt_no=payment.receipt_no if payment else None,
        resolution=resolution.resolution if resolution else None,
        resolution_reason=resolution.reason if resolution else None,
        resolved_at=resolution.resolved_at if resolution else None,
        resolved_by=resolution.resolved_by if resolution else None,
        resolved_by_name=(
            resolved_by_name
            or (
                action_started_by_name
                if resolution is not None
                and (
                    (handoff is not None and resolution.resolved_by == handoff.started_by)
                    or (
                        provider_action is not None
                        and resolution.resolved_by == provider_action.started_by
                    )
                )
                else accepted_by_name
                if resolution is not None
                and resolution.resolved_by == refund.approved_by
                else None
            )
        ),
        evidence_occurred_at=(
            settlement.evidence_occurred_at
            if settlement
            else completion.evidence_occurred_at
            if completion
            else resolution.provider_evidence_occurred_at
            if resolution
            else None
        ),
        evidence_time_untrusted=(
            settlement.evidence_time_untrusted
            if settlement
            else completion.evidence_time_untrusted
            if completion
            else resolution.provider_evidence_time_untrusted
            if resolution
            else False
        ),
        provider_evidence_reconciled=(
            settlement.provider_evidence_reconciled
            if settlement
            else completion.provider_evidence_reconciled
            if completion
            else resolution.provider_evidence_reconciled
            if resolution
            else True
        ),
        customer_spend_reconciled=(
            settlement.customer_spend_reconciled if settlement else True
        ),
        action_state_verified=(
            resolution.action_state_verified if resolution else False
        ),
        provider_verification_status=(
            resolution.provider_verification_status if resolution else None
        ),
        provider_verification_reference=(
            resolution.provider_verification_reference if resolution else None
        ),
        provider_checked_at=(resolution.provider_checked_at if resolution else None),
        cash_return_confirmed=(
            resolution.cash_return_confirmed if resolution else False
        ),
        action_takeover_confirmed=(
            settlement.action_takeover_confirmed
            if settlement
            else completion.action_takeover_confirmed
            if completion
            else resolution.action_takeover_confirmed
            if resolution
            else False
        ),
        action_takeover_reason=(
            settlement.action_takeover_reason
            if settlement
            else completion.action_takeover_reason
            if completion
            else resolution.action_takeover_reason
            if resolution
            else None
        ),
    )


async def _membership_payment_request_to_read(
    session,
    row: MembershipPaymentRequest,
) -> MembershipPaymentRequestRead:
    payment = (
        await session.execute(
            select(MembershipPayment).where(MembershipPayment.request_id == row.id)
        )
    ).scalar_one_or_none()
    collection = (
        await session.execute(
            select(MembershipPaymentCashCollection).where(
                MembershipPaymentCashCollection.request_id == row.id
            )
        )
    ).scalar_one_or_none()
    provider_action = (
        await session.execute(
            select(MembershipPaymentProviderAction).where(
                MembershipPaymentProviderAction.request_id == row.id
            )
        )
    ).scalar_one_or_none()
    completion = (
        await session.execute(
            select(MembershipPaymentCompletion).where(
                MembershipPaymentCompletion.request_id == row.id
            )
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipPaymentRequestResolution).where(
                MembershipPaymentRequestResolution.request_id == row.id
            )
        )
    ).scalar_one_or_none()
    membership_id = payment.membership_id if payment else None
    status_value = (
        "settled"
        if payment is not None
        else "withdrawn"
        if resolution is not None
        else "payment_completed_pending_posting"
        if completion is not None
        else "cash_collection_in_progress"
        if collection is not None
        else "provider_action_in_progress"
        if provider_action is not None
        else "accepted_payment_due"
    )
    prepared_user = await session.get(User, row.prepared_by)
    action_actor_id = (
        collection.started_by
        if collection is not None
        else provider_action.started_by
        if provider_action is not None
        else None
    )
    action_user = await session.get(User, action_actor_id) if action_actor_id else None
    settled_user = await session.get(User, payment.created_by) if payment else None
    completed_user = (
        await session.get(User, completion.completed_by) if completion else None
    )
    resolved_user = await session.get(User, resolution.resolved_by) if resolution else None
    return MembershipPaymentRequestRead(
        id=row.id,
        customer_id=row.customer_id,
        tier_id=row.tier_id,
        shift_id=row.shift_id,
        billing_cycle=row.billing_cycle,
        paid_via=row.method,
        amount_minor=row.amount_minor,
        customer_name=row.customer_name_snapshot,
        customer_phone=row.customer_phone_snapshot,
        tier_code=row.tier_code_snapshot,
        tier_name=row.tier_name_snapshot,
        status=status_value,
        accepted_at=row.accepted_at,
        prepared_by=row.prepared_by,
        prepared_by_name=prepared_user.name if prepared_user else None,
        collection_started_at=(
            collection.started_at
            if collection
            else provider_action.started_at
            if provider_action
            else None
        ),
        value_completed_at=completion.completed_at if completion else None,
        value_completed_by=completion.completed_by if completion else None,
        value_completed_by_name=completed_user.name if completed_user else None,
        action_started_by=action_actor_id,
        action_started_by_name=action_user.name if action_user else None,
        action_kind=(
            "cash_collection"
            if collection
            else "provider_payment"
            if provider_action
            else None
        ),
        settled_at=payment.paid_at if payment else None,
        settled_by=payment.created_by if payment else None,
        settled_by_name=settled_user.name if settled_user else None,
        membership_id=membership_id,
        payment_id=payment.id if payment else None,
        receipt_no=payment.receipt_no if payment else None,
        evidence_occurred_at=payment.evidence_occurred_at if payment else None,
        evidence_time_untrusted=(payment.evidence_time_untrusted if payment else False),
        provider_evidence_reconciled=(
            payment.provider_evidence_reconciled
            if payment
            else completion.provider_evidence_reconciled
            if completion
            else resolution.provider_evidence_reconciled
            if resolution
            else True
        ),
        external_reference=(
            payment.external_reference
            if payment is not None
            else completion.external_reference
            if completion is not None
            else resolution.external_reference
            if resolution is not None
            else None
        ),
        customer_spend_reconciled=(
            payment.customer_spend_reconciled if payment else False
        ),
        resolution=resolution.resolution if resolution else None,
        resolved_at=resolution.resolved_at if resolution else None,
        resolved_by=resolution.resolved_by if resolution else None,
        resolved_by_name=resolved_user.name if resolved_user else None,
        action_state_verified=(
            resolution.action_state_verified if resolution else False
        ),
        provider_verification_status=(
            resolution.provider_verification_status if resolution else None
        ),
        provider_verification_reference=(
            resolution.provider_verification_reference if resolution else None
        ),
        provider_checked_at=(resolution.provider_checked_at if resolution else None),
        cash_return_confirmed=(
            resolution.cash_return_confirmed if resolution else False
        ),
        action_takeover_confirmed=(
            payment.action_takeover_confirmed
            if payment
            else completion.action_takeover_confirmed
            if completion
            else resolution.action_takeover_confirmed
            if resolution
            else False
        ),
        action_takeover_reason=(
            payment.action_takeover_reason
            if payment
            else completion.action_takeover_reason
            if completion
            else resolution.action_takeover_reason
            if resolution
            else None
        ),
        client_action_id=row.client_action_id,
    )


def _validated_financial_time(
    *,
    request: Request,
    occurred_at: datetime,
    shift: Shift,
    now: datetime,
    action_name: str,
) -> datetime:
    """Validate a client-captured settlement timestamp without trusting it blindly."""
    if occurred_at.tzinfo is None:
        raise BusinessRuleError(f"{action_name} time must include a timezone.")
    captured = occurred_at.astimezone(timezone.utc)
    if captured > now + timedelta(minutes=5):
        raise BusinessRuleError(
            "This tablet's clock is ahead of the server. Correct the device time, "
            "then reopen the membership form."
        )
    if captured < now - timedelta(days=7):
        raise BusinessRuleError(
            f"This saved {action_name.lower()} is more than 7 days old and was not posted. "
            "An owner must reconcile it before creating a new charge."
        )

    was_offline = request.headers.get("X-Offline-Captured", "").strip().lower() in {
        "1", "true", "yes",
    }
    header_time = request.headers.get("X-Client-Occurred-At")
    if was_offline:
        if not header_time:
            raise BusinessRuleError(
                f"Offline {action_name.lower()} is missing its captured-time provenance."
            )
        try:
            provenance_time = datetime.fromisoformat(
                header_time.strip().replace("Z", "+00:00")
            ).astimezone(timezone.utc)
        except (TypeError, ValueError) as exc:
            raise BusinessRuleError(
                f"Offline {action_name.lower()} has an invalid captured-time provenance."
            ) from exc
        if abs((provenance_time - captured).total_seconds()) > 1:
            raise BusinessRuleError(
                f"Saved {action_name.lower()} time does not match its audit provenance. "
                "Nothing was posted."
            )
    elif captured < shift.opened_at - timedelta(minutes=5):
        raise BusinessRuleError(
            f"{action_name} time is before this shift opened. Reopen the form "
            "from the current shift and try again."
        )
    return captured


def _capture_financial_evidence(
    *,
    request: Request,
    occurred_at: datetime,
    shift: Shift,
    action_started_at: datetime,
    server_now: datetime,
) -> tuple[datetime, bool]:
    """Retain device/provider time without letting clock skew erase real money.

    Once an immutable begin-action fact exists, the side effect may already
    have happened. The server timestamp is therefore authoritative accounting
    time; this helper records the submitted time and raises a visible review
    flag instead of rejecting the settlement.
    """
    untrusted = occurred_at.tzinfo is None
    evidence = (
        occurred_at.replace(tzinfo=timezone.utc)
        if occurred_at.tzinfo is None
        else occurred_at.astimezone(timezone.utc)
    )
    if (
        evidence > server_now + timedelta(minutes=5)
        or evidence < server_now - timedelta(days=7)
        or evidence < shift.opened_at
        or evidence < action_started_at
    ):
        untrusted = True
    was_offline = request.headers.get("X-Offline-Captured", "").strip().lower() in {
        "1",
        "true",
        "yes",
    }
    header_time = request.headers.get("X-Client-Occurred-At")
    if was_offline:
        if not header_time:
            untrusted = True
        else:
            try:
                provenance_time = datetime.fromisoformat(
                    header_time.strip().replace("Z", "+00:00")
                ).astimezone(timezone.utc)
            except (TypeError, ValueError):
                untrusted = True
            else:
                if abs((provenance_time - evidence).total_seconds()) > 1:
                    untrusted = True
    return evidence, untrusted


async def _allocate_membership_receipt(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    occurred_at: datetime,
    refund: bool = False,
) -> tuple[str, str]:
    """Allocate an immutable fiscal-year receipt in a dedicated series."""
    branch = await session.get(Branch, branch_id)
    company = await session.get(Company, company_id)
    if branch is None or company is None or branch.company_id != company_id:
        raise BusinessRuleError(
            "Cannot issue a membership receipt because the branch identity is invalid."
        )
    return await InvoiceNumberService(session).allocate(
        company_id=company_id,
        branch_id=branch_id,
        prefix="R" if refund else "M",
        series="membership_refund" if refund else "membership",
        at=occurred_at,
        timezone_name=branch.timezone or company.timezone,
    )


async def _unresolved_cash_refund_total(
    session,
    *,
    shift_id: UUID,
    exclude_refund_id: UUID | None = None,
) -> int:
    stmt = (
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
    if exclude_refund_id is not None:
        stmt = stmt.where(MembershipRefund.id != exclude_refund_id)
    membership_reserved = int((await session.execute(stmt)).scalar_one() or 0)
    pos_reserved = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(PosRefundRequest.amount_minor), 0))
                .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
                .outerjoin(
                    PosRefundWithdrawal,
                    PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
                )
                .where(
                    PosRefundRequest.shift_id == shift_id,
                    PosRefundRequest.settlement_method == "cash",
                    Refund.id.is_(None),
                    PosRefundWithdrawal.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    return membership_reserved + pos_reserved


async def _unresolved_customer_refund_total(
    session,
    *,
    customer_id: UUID,
) -> int:
    """Reserve net customer spend across membership and POS refund tasks."""
    membership_reserved = (
        select(func.coalesce(func.sum(MembershipRefund.amount_minor), 0))
        .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
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
        .scalar_subquery()
    )
    pos_reserved = (
        select(func.coalesce(func.sum(PosRefundRequest.amount_minor), 0))
        .join(Order, Order.id == PosRefundRequest.order_id)
        .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
        .outerjoin(
            PosRefundWithdrawal,
            PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
        )
        .where(
            Order.customer_id == customer_id,
            Refund.id.is_(None),
            PosRefundWithdrawal.id.is_(None),
        )
        .scalar_subquery()
    )
    return int(
        (
            await session.execute(
                select(membership_reserved + pos_reserved)
            )
        ).scalar_one()
        or 0
    )


@router.post("/subscribe", response_model=SubscriptionRead, status_code=status.HTTP_201_CREATED)
async def subscribe(
    payload: SubscribeRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> SubscriptionRead:
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may create a manual membership entitlement."
        )
    if payload.billing_cycle != "monthly":
        raise BusinessRuleError(
            "Annual memberships are temporarily unavailable because this operational "
            "receipt-basis release does not yet defer annual revenue across the service term. "
            "Use a monthly membership."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Cannot collect a membership payment without a verified POS terminal "
            "and branch. Select this tablet's terminal, open a shift, then retry."
        )
    # starts_at/expires_at are computed from datetime.now() fresh on every
    # call, and the overlap check below guards against a *second* real
    # subscription, not against a retry of the *same* one — a retry after a
    # dropped response would hit that guard's BusinessRuleError with no way
    # to recover the original SubscriptionRead. Idempotency-Key is mandatory
    # here for the same reason Inventory's GRN/adjustment writes and Events'
    # ticket sales are: no natural key a duplicate retry could collide
    # against safely.
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for subscribe writes")
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return SubscriptionRead.model_validate(replay["body"])

    # A replay from an older safe completion is returned above.  A *new*
    # one-call collection is refused: staff could already have taken cash or
    # completed UPI before this endpoint rejects, leaving the obligation only
    # on one tablet.  New clients must reserve server-side first.
    raise BusinessRuleError(
        "Direct membership collection is disabled. Update the app, reopen "
        "Memberships, and prepare the payment request before collecting cash or "
        "approving card/UPI. No payment or entitlement was created."
    )


@router.post(
    "/payment-requests",
    response_model=MembershipPaymentRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def prepare_membership_payment(
    payload: MembershipPaymentRequestCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipPaymentRequestRead:
    """Validate and reserve a membership sale before staff collect money."""
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may prepare membership payment.")
    if payload.billing_cycle != "monthly":
        raise BusinessRuleError(
            "Annual memberships are unavailable until deferred revenue recognition exists. "
            "Prepare a monthly membership instead."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Select this tablet's branch and terminal, then open its shift before "
            "preparing a membership payment."
        )
    action_id = payload.client_action_id.strip()
    header_action_id = (request.headers.get("X-Client-Action-Id") or "").strip()
    if not header_action_id or header_action_id != action_id:
        raise BusinessRuleError(
            "The saved membership action ID does not match its audit provenance. "
            "No payment request was created."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for membership payment")
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    if idempotency_key != action_id:
        raise BusinessRuleError(
            "Idempotency-Key must match the saved membership action ID."
        )
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipPaymentRequestRead.model_validate(replay["body"])

    # The exact shift serializes prepare/settle/withdraw and server-side close.
    # No financial fact exists at this stage.
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
        operation="preparing this membership payment",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="prepare this membership payment",
    )

    existing_action = (
        await session.execute(
            select(MembershipPaymentRequest)
            .where(
                MembershipPaymentRequest.company_id == tenant.company_id,
                MembershipPaymentRequest.client_action_id == action_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing_action is not None:
        if (
            existing_action.customer_id != payload.customer_id
            or existing_action.tier_id != payload.tier_id
            or existing_action.shift_id != payload.shift_id
            or existing_action.amount_minor != payload.expected_amount_minor
            or existing_action.method != payload.paid_via
            or existing_action.billing_cycle != payload.billing_cycle
        ):
            raise BusinessRuleError(
                "This membership action ID already belongs to a different payment "
                "request. Open the existing task; it cannot be overwritten."
            )
        response = await _membership_payment_request_to_read(session, existing_action)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    legacy_resolution = (
        await session.execute(
            select(MembershipPaymentAttemptResolution.id).where(
                MembershipPaymentAttemptResolution.company_id == tenant.company_id,
                MembershipPaymentAttemptResolution.original_client_action_id == action_id,
            )
        )
    ).scalar_one_or_none()
    if legacy_resolution is not None:
        raise BusinessRuleError(
            "This old payment action was already resolved after money was returned or "
            "reversed. Start a fresh membership payment request."
        )

    customer = (
        await session.execute(
            select(Customer).where(Customer.id == payload.customer_id).with_for_update()
        )
    ).scalar_one_or_none()
    if customer is None or customer.company_id != tenant.company_id or customer.deleted_at:
        raise NotFoundError("customer not found")
    now = datetime.now(timezone.utc)
    active = (
        await session.execute(
            select(CustomerMembership.id)
            .where(
                CustomerMembership.customer_id == customer.id,
                CustomerMembership.expires_at > now,
                CustomerMembership.revoked_at.is_(None),
            )
            .limit(1)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if active is not None:
        raise BusinessRuleError(
            "Customer already has an unexpired membership. Let it expire or use the "
            "audited refund flow before preparing another payment."
        )

    unresolved_request = (
        await session.execute(
            select(MembershipPaymentRequest.id)
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
                MembershipPaymentRequest.customer_id == customer.id,
                MembershipPayment.id.is_(None),
                MembershipPaymentRequestResolution.id.is_(None),
            )
            .limit(1)
            .with_for_update(of=MembershipPaymentRequest)
        )
    ).scalar_one_or_none()
    if unresolved_request is not None:
        raise BusinessRuleError(
            "This customer already has a membership payment waiting for collection or "
            "withdrawal. Resolve that server task first."
        )
    unresolved_refund = (
        await session.execute(
            select(MembershipRefund.id)
            .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
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
                CustomerMembership.customer_id == customer.id,
                MembershipRefundSettlement.id.is_(None),
                MembershipRefundResolution.id.is_(None),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    if unresolved_refund is not None:
        raise BusinessRuleError(
            "This customer has a membership refund awaiting payout or withdrawal. "
            "Resolve it before preparing another membership payment."
        )

    tier = (
        await session.execute(
            select(MembershipTier)
            .where(MembershipTier.id == payload.tier_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if tier is None or tier.company_id != tenant.company_id or tier.deleted_at:
        raise NotFoundError("tier not found")
    price = int(tier.monthly_price_minor or 0)
    if price <= 0:
        raise BusinessRuleError("This tier has no valid monthly price.")
    if price != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The membership price changed before payment was accepted "
            f"(expected {payload.expected_amount_minor}, current {price} minor units). "
            "Refresh and review the new price; do not collect money yet."
        )

    payment_request = MembershipPaymentRequest(
        id=uuid4(),
        company_id=tenant.company_id,
        customer_id=customer.id,
        tier_id=tier.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        billing_cycle="monthly",
        method=payload.paid_via,
        amount_minor=price,
        customer_name_snapshot=customer.name,
        customer_phone_snapshot=customer.phone,
        tier_code_snapshot=tier.code,
        tier_name_snapshot=tier.name,
        accepted_at=now,
        prepared_by=tenant.user_id,
        client_action_id=action_id,
        idempotency_key=idempotency_key,
    )
    session.add(payment_request)
    await session.flush()
    response = await _membership_payment_request_to_read(session, payment_request)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/payment-requests/{payment_request_id}/begin-cash-collection",
    response_model=MembershipPaymentRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def begin_membership_cash_collection(
    payment_request_id: UUID,
    payload: MembershipPaymentCashCollectionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipPaymentRequestRead:
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may collect membership cash.")
    if not payload.ready_to_collect:
        raise BusinessRuleError(
            "Confirm the customer and amount before opening the cash collection step."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Return to the terminal that prepared this payment.")
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for cash collection")
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipPaymentRequestRead.model_validate(replay["body"])

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
        operation="starting membership cash collection",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="collect this membership cash",
    )
    payment_request = (
        await session.execute(
            select(MembershipPaymentRequest)
            .where(
                MembershipPaymentRequest.id == payment_request_id,
                MembershipPaymentRequest.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if payment_request is None:
        raise NotFoundError("membership payment request not found")
    if payment_request.shift_id != shift.id:
        raise BusinessRuleError("This payment request belongs to a different shift.")
    if payment_request.method != "cash":
        raise BusinessRuleError("Only a cash membership uses the cash collection step.")
    if payment_request.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError("The membership amount changed. Refresh before collecting cash.")
    payment = (
        await session.execute(
            select(MembershipPayment.id).where(
                MembershipPayment.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipPaymentRequestResolution.id).where(
                MembershipPaymentRequestResolution.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    if payment is not None:
        raise BusinessRuleError("This membership payment is already settled. Do not collect again.")
    if resolution is not None:
        raise BusinessRuleError("This payment request was withdrawn. Do not collect money.")
    collection = (
        await session.execute(
            select(MembershipPaymentCashCollection)
            .where(MembershipPaymentCashCollection.request_id == payment_request.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if collection is None:
        collection = MembershipPaymentCashCollection(
            id=uuid4(),
            company_id=tenant.company_id,
            request_id=payment_request.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            started_at=datetime.now(timezone.utc),
            started_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(collection)
        await session.flush()
    else:
        await _require_same_begin_actor(
            session,
            started_by=collection.started_by,
            current_user_id=tenant.user_id,
            started_at=collection.started_at,
            action_label="membership cash collection",
        )
    response = await _membership_payment_request_to_read(session, payment_request)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/payment-requests/{payment_request_id}/begin-provider-action",
    response_model=MembershipPaymentRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def begin_membership_provider_payment(
    payment_request_id: UUID,
    payload: MembershipPaymentProviderActionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipPaymentRequestRead:
    """Persist ownership before staff approve an external payment."""
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may start membership payment.")
    if not payload.ready_to_start:
        raise BusinessRuleError(
            "Confirm the customer, amount, and provider before starting payment."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Return to the terminal that prepared this payment.")
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required before provider membership payment"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipPaymentRequestRead.model_validate(replay["body"])

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
        operation="starting this provider membership payment",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="start this provider membership payment",
    )
    payment_request = (
        await session.execute(
            select(MembershipPaymentRequest)
            .where(
                MembershipPaymentRequest.id == payment_request_id,
                MembershipPaymentRequest.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if payment_request is None:
        raise NotFoundError("membership payment request not found")
    if payment_request.shift_id != shift.id:
        raise BusinessRuleError("This payment request belongs to a different shift.")
    if payment_request.method == "cash":
        raise BusinessRuleError("Use the cash collection step for this membership.")
    if payment_request.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The membership amount changed. Refresh before approving provider payment."
        )
    payment = (
        await session.execute(
            select(MembershipPayment.id).where(
                MembershipPayment.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipPaymentRequestResolution.id).where(
                MembershipPaymentRequestResolution.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    if payment is not None:
        raise BusinessRuleError(
            "This membership payment is already settled. Do not approve it again."
        )
    if resolution is not None:
        raise BusinessRuleError(
            "This payment request was withdrawn. Do not approve provider payment."
        )
    provider_action = (
        await session.execute(
            select(MembershipPaymentProviderAction)
            .where(MembershipPaymentProviderAction.request_id == payment_request.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if provider_action is None:
        provider_action = MembershipPaymentProviderAction(
            id=uuid4(),
            company_id=tenant.company_id,
            request_id=payment_request.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            method=payment_request.method,
            started_at=datetime.now(timezone.utc),
            started_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(provider_action)
        await session.flush()
    else:
        await _require_same_begin_actor(
            session,
            started_by=provider_action.started_by,
            current_user_id=tenant.user_id,
            started_at=provider_action.started_at,
            action_label="membership provider payment",
        )
    response = await _membership_payment_request_to_read(session, payment_request)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/payment-requests/{payment_request_id}/settle",
    response_model=MembershipPaymentRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def settle_membership_payment(
    payment_request_id: UUID,
    payload: MembershipPaymentSettlementRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipPaymentRequestRead:
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may settle membership payment.")
    if not payload.payment_received:
        raise BusinessRuleError(
            "Confirm only after the customer payment is actually received."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Return to the terminal that prepared this payment.")
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for membership settlement")
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipPaymentRequestRead.model_validate(replay["body"])

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
        operation="settling this membership payment",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="settle this membership payment",
    )
    payment_request = (
        await session.execute(
            select(MembershipPaymentRequest)
            .where(
                MembershipPaymentRequest.id == payment_request_id,
                MembershipPaymentRequest.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if payment_request is None:
        raise NotFoundError("membership payment request not found")
    if payment_request.shift_id != shift.id:
        raise BusinessRuleError("This membership payment belongs to a different shift.")
    if payment_request.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError("The prepared membership amount changed. Refresh before settling.")
    external_reference = (
        payload.external_reference.strip() if payload.external_reference else None
    )
    if payment_request.method == "cash":
        if external_reference is not None:
            raise BusinessRuleError("Cash payment cannot contain a provider reference.")
    elif not external_reference:
        raise BusinessRuleError(
            "Enter the completed card/UPI provider transaction reference."
        )
    provider_evidence_reconciled = (
        payment_request.method == "cash" or len(external_reference or "") >= 3
    )

    existing_completion = (
        await session.execute(
            select(MembershipPaymentCompletion)
            .where(MembershipPaymentCompletion.request_id == payment_request.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipPaymentRequestResolution.id).where(
                MembershipPaymentRequestResolution.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    if resolution is not None:
        raise BusinessRuleError("This payment request was withdrawn. Do not take payment.")
    collection = None
    provider_action = None
    if payment_request.method == "cash":
        collection = (
            await session.execute(
                select(MembershipPaymentCashCollection).where(
                    MembershipPaymentCashCollection.request_id == payment_request.id
                )
            )
        ).scalar_one_or_none()
        if collection is None:
            raise BusinessRuleError(
                "Start the server-confirmed cash collection step before accepting notes."
            )
    else:
        provider_action = (
            await session.execute(
                select(MembershipPaymentProviderAction).where(
                    MembershipPaymentProviderAction.request_id == payment_request.id
                )
            )
        ).scalar_one_or_none()
        if provider_action is None:
            raise BusinessRuleError(
                "Start the server-confirmed provider payment step before approving "
                "card/UPI."
            )
    action = collection or provider_action
    assert action is not None
    actor = await session.get(User, action.started_by)
    actor_name = actor.name if actor else str(action.started_by)
    takeover_confirmed, takeover_reason = _verified_takeover_attestation(
        started_by=action.started_by,
        current_user_id=tenant.user_id,
        confirmed=payload.action_takeover_confirmed,
        reason=payload.action_takeover_reason,
        started_by_name=actor_name,
        action_label=(
            "cash collection" if payment_request.method == "cash" else "provider payment"
        ),
    )

    completed_at = datetime.now(timezone.utc)
    evidence_occurred_at, evidence_time_untrusted = _capture_financial_evidence(
        request=request,
        occurred_at=payload.collected_at,
        shift=shift,
        action_started_at=action.started_at,
        server_now=completed_at,
    )
    if existing_completion is not None:
        evidence_matches = (
            existing_completion.amount_minor == payload.expected_amount_minor
            and existing_completion.method == payment_request.method
            and existing_completion.external_reference == external_reference
            and existing_completion.provider_evidence_reconciled
            == provider_evidence_reconciled
            and existing_completion.evidence_time_untrusted == evidence_time_untrusted
            and existing_completion.action_takeover_confirmed == takeover_confirmed
            and existing_completion.action_takeover_reason == takeover_reason
            and existing_completion.evidence_occurred_at is not None
            and abs(
                (
                    existing_completion.evidence_occurred_at
                    - evidence_occurred_at
                ).total_seconds()
            )
            <= 1
        )
        if not evidence_matches:
            raise BusinessRuleError(
                "This membership payment already has immutable completion evidence with "
                "a different amount, rail, provider reference, or collection time. Do not "
                "collect again; open the pending accounting task."
            )
        response = await _membership_payment_request_to_read(session, payment_request)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response
    if external_reference is not None:
        provider_lock_key = (
            f"membership-payment-provider:{tenant.company_id}:"
            f"{payment_request.method}:{external_reference}"
        )
        await session.execute(
            select(
                func.pg_advisory_xact_lock(
                    func.hashtextextended(provider_lock_key, 0)
                )
            )
        )
        duplicate_reference = (
            await session.execute(
                select(MembershipPaymentCompletion.id).where(
                    MembershipPaymentCompletion.company_id == tenant.company_id,
                    MembershipPaymentCompletion.method == payment_request.method,
                    MembershipPaymentCompletion.external_reference == external_reference,
                )
            )
        ).scalar_one_or_none()
        posted_reference = (
            await session.execute(
                select(MembershipPayment.id).where(
                    MembershipPayment.company_id == tenant.company_id,
                    MembershipPayment.method == payment_request.method,
                    MembershipPayment.external_reference == external_reference,
                )
            )
        ).scalar_one_or_none()
        if duplicate_reference is not None or posted_reference is not None:
            raise BusinessRuleError(
                "This provider reference is already attached to another membership "
                "payment. Open that receipt instead of collecting again."
            )
    completion = MembershipPaymentCompletion(
        id=uuid4(),
        company_id=tenant.company_id,
        request_id=payment_request.id,
        cash_collection_id=collection.id if collection else None,
        provider_action_id=provider_action.id if provider_action else None,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        method=payment_request.method,
        amount_minor=payment_request.amount_minor,
        completed_at=completed_at,
        completed_by=tenant.user_id,
        idempotency_key=idempotency_key,
        external_reference=external_reference,
        provider_evidence_reconciled=provider_evidence_reconciled,
        evidence_occurred_at=evidence_occurred_at,
        evidence_time_untrusted=evidence_time_untrusted,
        action_takeover_confirmed=takeover_confirmed,
        action_takeover_reason=takeover_reason,
    )
    session.add(completion)
    await session.flush()
    response = await _membership_payment_request_to_read(session, payment_request)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/payment-requests/{payment_request_id}/finalize",
    response_model=MembershipPaymentRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def finalize_membership_payment(
    payment_request_id: UUID,
    payload: MembershipPaymentFinalizationRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipPaymentRequestRead:
    """Post accounting only after a separately committed value-completion fact."""
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may finalize membership accounting.")
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Return to the terminal that accepted this payment.")
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for membership accounting finalization"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipPaymentRequestRead.model_validate(replay["body"])

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
        operation="finalizing membership accounting",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="finalize this membership payment",
    )
    payment_request = (
        await session.execute(
            select(MembershipPaymentRequest)
            .where(
                MembershipPaymentRequest.id == payment_request_id,
                MembershipPaymentRequest.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if payment_request is None:
        raise NotFoundError("membership payment request not found")
    if payment_request.shift_id != shift.id:
        raise BusinessRuleError("This membership payment belongs to a different shift.")
    if payment_request.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The completed membership amount differs from this accounting task. Refresh."
        )
    completion = (
        await session.execute(
            select(MembershipPaymentCompletion)
            .where(MembershipPaymentCompletion.request_id == payment_request.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if completion is None:
        raise BusinessRuleError(
            "No committed payment-completion evidence exists. Do not post accounting or "
            "collect money again; finish the value-completion step first."
        )
    existing = (
        await session.execute(
            select(MembershipPayment)
            .where(MembershipPayment.request_id == payment_request.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None:
        if existing.completion_id != completion.id:
            raise BusinessRuleError(
                "This payment is already posted from different completion evidence. "
                "Open its receipt and audit record."
            )
        response = await _membership_payment_request_to_read(session, payment_request)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    customer = (
        await session.execute(
            select(Customer)
            .where(
                Customer.id == payment_request.customer_id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if customer is None:
        raise BusinessRuleError(
            "The prepared customer is unavailable. The received payment remains a "
            "pending recovery task; reverse/return it before withdrawing."
        )
    existing_active = (
        await session.execute(
            select(CustomerMembership.id)
            .where(
                CustomerMembership.customer_id == customer.id,
                CustomerMembership.expires_at > completion.completed_at,
                CustomerMembership.revoked_at.is_(None),
            )
            .limit(1)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing_active is not None:
        raise BusinessRuleError(
            "Another membership became active after this payment was prepared. The "
            "completion remains pending; return/reverse the money before withdrawal."
        )

    posted_at = datetime.now(timezone.utc)
    subscription = CustomerMembership(
        id=uuid4(),
        customer_id=customer.id,
        tier_id=payment_request.tier_id,
        billing_cycle="monthly",
        starts_at=completion.completed_at,
        expires_at=completion.completed_at + timedelta(days=30),
        auto_renew=False,
        amount_paid_minor=payment_request.amount_minor,
        notes=(
            "Protected-owner membership finalized from payment completion "
            f"{completion.id}"
        ),
    )
    receipt_no, fiscal_year = await _allocate_membership_receipt(
        session,
        company_id=tenant.company_id,
        branch_id=shift.branch_id,
        occurred_at=posted_at,
    )
    payment = MembershipPayment(
        id=uuid4(),
        company_id=tenant.company_id,
        membership_id=subscription.id,
        request_id=payment_request.id,
        completion_id=completion.id,
        branch_id=completion.branch_id,
        terminal_id=completion.terminal_id,
        shift_id=completion.shift_id,
        method=completion.method,
        amount_minor=completion.amount_minor,
        paid_at=posted_at,
        created_by=completion.completed_by,
        idempotency_key=idempotency_key,
        receipt_no=receipt_no,
        receipt_fiscal_year=fiscal_year,
        receipt_issued_at=posted_at,
        external_reference=completion.external_reference,
        provider_evidence_reconciled=completion.provider_evidence_reconciled,
        evidence_occurred_at=completion.evidence_occurred_at,
        evidence_time_untrusted=completion.evidence_time_untrusted,
        customer_spend_reconciled=True,
        action_takeover_confirmed=completion.action_takeover_confirmed,
        action_takeover_reason=completion.action_takeover_reason,
        note=(
            f"{payment_request.tier_name_snapshot} monthly membership for "
            f"{payment_request.customer_name_snapshot or payment_request.customer_phone_snapshot}"
        ),
    )
    session.add_all([subscription, payment])
    await session.flush()
    before_spend = int(customer.total_spent_minor or 0)
    after_spend = before_spend + int(payment.amount_minor)
    customer.total_spent_minor = after_spend
    if payment.method == "cash":
        shift.expected_minor = int(shift.expected_minor or 0) + int(payment.amount_minor)
    await session.flush()
    application = MembershipCustomerSpendApplication(
        id=uuid4(),
        company_id=tenant.company_id,
        customer_id=customer.id,
        payment_id=payment.id,
        refund_settlement_id=None,
        source_amount_minor=payment.amount_minor,
        before_total_spent_minor=before_spend,
        after_total_spent_minor=after_spend,
        adjustment_minor=payment.amount_minor,
        applied_at=posted_at,
        applied_by=completion.completed_by,
        idempotency_key=f"membership-spend:{payment.id}",
    )
    session.add(application)
    await session.flush()
    response = await _membership_payment_request_to_read(session, payment_request)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/payment-requests/{payment_request_id}/withdraw",
    response_model=MembershipPaymentRequestRead,
    status_code=status.HTTP_201_CREATED,
)
async def withdraw_membership_payment_request(
    payment_request_id: UUID,
    payload: MembershipPaymentRequestWithdrawal,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipPaymentRequestRead:
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may withdraw membership payment.")
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Return to the terminal that prepared this payment.")
    reason = payload.reason.strip()
    if len(reason) < 3:
        raise BusinessRuleError("Enter why this membership payment was not completed.")
    external_reference = (
        payload.external_reference.strip() if payload.external_reference else None
    )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for payment withdrawal")
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipPaymentRequestRead.model_validate(replay["body"])

    shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    if shift is None or shift.company_id != tenant.company_id:
        raise NotFoundError("membership payment shift not found")
    if shift.branch_id != tenant.branch_id or shift.terminal_id != tenant.terminal_id:
        raise BusinessRuleError("Return to the branch and terminal that prepared this payment.")
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="withdraw this membership payment request",
    )
    payment_request = (
        await session.execute(
            select(MembershipPaymentRequest)
            .where(
                MembershipPaymentRequest.id == payment_request_id,
                MembershipPaymentRequest.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if payment_request is None:
        raise NotFoundError("membership payment request not found")
    if payment_request.shift_id != shift.id:
        raise BusinessRuleError("This membership payment belongs to a different shift.")
    if payment_request.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError("The membership amount changed. Refresh before resolving it.")
    payment = (
        await session.execute(
            select(MembershipPayment.id).where(
                MembershipPayment.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    if payment is not None:
        raise BusinessRuleError(
            "This membership payment is settled. Use its audited refund flow instead."
        )
    existing = (
        await session.execute(
            select(MembershipPaymentRequestResolution)
            .where(MembershipPaymentRequestResolution.request_id == payment_request.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    collection = (
        await session.execute(
            select(MembershipPaymentCashCollection).where(
                MembershipPaymentCashCollection.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    provider_action = (
        await session.execute(
            select(MembershipPaymentProviderAction).where(
                MembershipPaymentProviderAction.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    completion = (
        await session.execute(
            select(MembershipPaymentCompletion).where(
                MembershipPaymentCompletion.request_id == payment_request.id
            )
        )
    ).scalar_one_or_none()
    action = collection or provider_action
    verification_reference = (
        payload.provider_verification_reference.strip()
        if payload.provider_verification_reference
        else None
    )
    if existing is not None:
        if (
            existing.paid_via != payment_request.method
            or existing.resolution != payload.resolution
            or existing.reason != reason
            or existing.external_reference != external_reference
            or existing.action_state_verified != payload.action_state_verified
            or existing.provider_verification_status
            != payload.provider_verification_status
            or existing.provider_verification_reference != verification_reference
            or existing.cash_return_confirmed != payload.cash_return_confirmed
        ):
            raise BusinessRuleError(
                "This membership payment task was already resolved with different "
                "evidence. Open its audit record; it cannot be overwritten."
            )
        response = await _membership_payment_request_to_read(session, payment_request)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    takeover_confirmed = False
    takeover_reason = None
    provider_checked_at = None
    provider_evidence_occurred_at = None
    provider_evidence_time_untrusted = False
    provider_evidence_reconciled = True
    if action is None:
        if (
            payload.resolution != "payment_not_collected"
            or payload.action_state_verified
            or external_reference is not None
            or payload.provider_verification_status is not None
            or verification_reference is not None
            or payload.provider_evidence_occurred_at is not None
            or payload.cash_return_confirmed
            or payload.action_takeover_confirmed
            or payload.action_takeover_reason is not None
            or completion is not None
        ):
            raise BusinessRuleError(
                "No collection/provider action started. Confirm payment_not_collected "
                "without cash, provider, or takeover evidence."
            )
    else:
        actor = await session.get(User, action.started_by)
        actor_name = actor.name if actor else str(action.started_by)
        takeover_confirmed, takeover_reason = _verified_takeover_attestation(
            started_by=action.started_by,
            current_user_id=tenant.user_id,
            confirmed=payload.action_takeover_confirmed,
            reason=payload.action_takeover_reason,
            started_by_name=actor_name,
            action_label=(
                "cash collection"
                if payment_request.method == "cash"
                else "provider payment"
            ),
        )
        if not payload.action_state_verified:
            raise BusinessRuleError(
                "Verify the drawer/customer or provider result before resolving this "
                "in-progress membership payment. Unknown state must remain unresolved."
            )
        if collection is not None:
            allowed = {"cash_not_collected", "cash_returned"}
            if completion is not None:
                allowed = {"cash_returned"}
            if (
                payload.resolution not in allowed
                or external_reference is not None
                or payload.provider_verification_status is not None
                or verification_reference is not None
                or payload.provider_evidence_occurred_at is not None
                or payload.cash_return_confirmed
                != (payload.resolution == "cash_returned")
            ):
                raise BusinessRuleError(
                    "Verify the drawer and customer. Use cash_not_collected only when "
                    "no notes changed hands; use cash_returned with explicit physical-return "
                    "confirmation after any collected cash has been handed back."
                )
        else:
            if payload.resolution not in {
                "provider_not_completed",
                "provider_reversed",
            }:
                raise BusinessRuleError(
                    "Verify the provider result and use provider_not_completed or "
                    "provider_reversed. Unknown state must remain unresolved."
                )
            expected_status = (
                "not_completed"
                if payload.resolution == "provider_not_completed"
                else "reversed"
            )
            if (
                payload.provider_verification_status != expected_status
                or not verification_reference
                or payload.cash_return_confirmed
                or (
                    payload.resolution == "provider_not_completed"
                    and external_reference is not None
                )
                or (
                    payload.resolution == "provider_reversed"
                    and external_reference != verification_reference
                )
                or (completion is not None and payload.resolution != "provider_reversed")
            ):
                raise BusinessRuleError(
                    "A protected owner must verify the provider's terminal status and "
                    "record its nonblank verification reference. If value completed, "
                    "reverse it externally and use the same reversal reference."
                )
            provider_checked_at = datetime.now(timezone.utc)
            evidence_input = payload.provider_evidence_occurred_at or provider_checked_at
            (
                provider_evidence_occurred_at,
                provider_evidence_time_untrusted,
            ) = _capture_financial_evidence(
                request=request,
                occurred_at=evidence_input,
                shift=shift,
                action_started_at=provider_action.started_at,
                server_now=provider_checked_at,
            )
            provider_evidence_reconciled = len(verification_reference) >= 3

    existing = MembershipPaymentRequestResolution(
        id=uuid4(),
        company_id=tenant.company_id,
        request_id=payment_request.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        paid_via=payment_request.method,
        resolution=payload.resolution,
        reason=reason,
        external_reference=external_reference,
        provider_evidence_reconciled=provider_evidence_reconciled,
        resolved_at=datetime.now(timezone.utc),
        resolved_by=tenant.user_id,
        action_state_verified=payload.action_state_verified,
        provider_verification_status=payload.provider_verification_status,
        provider_verification_reference=verification_reference,
        provider_checked_at=provider_checked_at,
        provider_evidence_occurred_at=provider_evidence_occurred_at,
        provider_evidence_time_untrusted=provider_evidence_time_untrusted,
        cash_return_confirmed=payload.cash_return_confirmed,
        action_takeover_confirmed=takeover_confirmed,
        action_takeover_reason=takeover_reason,
        idempotency_key=idempotency_key,
    )
    session.add(existing)
    await session.flush()
    response = await _membership_payment_request_to_read(session, payment_request)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get("/payment-requests", response_model=list[MembershipPaymentRequestRead])
async def list_membership_payment_requests(
    session: SessionDep,
    response: Response,
    tenant: TenantContext = Depends(requires("admin.system")),
    unresolved: bool = True,
    shift_id: UUID | None = None,
    request_id: UUID | None = None,
    client_action_id: str | None = None,
    limit: int = Query(default=100, ge=1, le=200),
) -> list[MembershipPaymentRequestRead]:
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may view membership payment tasks.")
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Select this tablet's branch and terminal first.")
    stmt = (
        select(MembershipPaymentRequest)
        .outerjoin(
            MembershipPayment,
            MembershipPayment.request_id == MembershipPaymentRequest.id,
        )
        .outerjoin(
            MembershipPaymentRequestResolution,
            MembershipPaymentRequestResolution.request_id == MembershipPaymentRequest.id,
        )
        .where(
            MembershipPaymentRequest.company_id == tenant.company_id,
            MembershipPaymentRequest.branch_id == tenant.branch_id,
            MembershipPaymentRequest.terminal_id == tenant.terminal_id,
        )
        .order_by(MembershipPaymentRequest.accepted_at.desc())
        .limit(limit + 1)
    )
    if unresolved:
        stmt = stmt.where(
            MembershipPayment.id.is_(None),
            MembershipPaymentRequestResolution.id.is_(None),
        )
    if shift_id is not None:
        stmt = stmt.where(MembershipPaymentRequest.shift_id == shift_id)
    if request_id is not None:
        stmt = stmt.where(MembershipPaymentRequest.id == request_id)
    if client_action_id is not None:
        clean_action_id = client_action_id.strip()
        if not clean_action_id:
            raise BusinessRuleError("Membership payment action ID cannot be blank.")
        stmt = stmt.where(
            MembershipPaymentRequest.client_action_id == clean_action_id
        )
    rows = (await session.execute(stmt)).scalars().all()
    truncated = len(rows) > limit
    rows = rows[:limit]
    response.headers["X-Result-Truncated"] = str(truncated).lower()
    response.headers["X-Result-Limit"] = str(limit)
    return [await _membership_payment_request_to_read(session, row) for row in rows]


def _payment_attempt_resolution_to_read(
    row: MembershipPaymentAttemptResolution,
) -> MembershipPaymentAttemptResolutionRead:
    return MembershipPaymentAttemptResolutionRead(
        id=row.id,
        original_client_action_id=row.original_client_action_id,
        customer_id=row.customer_id,
        tier_id=row.tier_id,
        shift_id=row.shift_id,
        expected_amount_minor=row.expected_amount_minor,
        paid_via=row.paid_via,
        resolution=row.resolution,
        reason=row.reason,
        external_reference=row.external_reference,
        provider_verification_status=row.provider_verification_status,
        provider_checked_at=row.provider_checked_at,
        provider_evidence_reconciled=row.provider_evidence_reconciled,
        evidence_occurred_at=row.evidence_occurred_at,
        evidence_time_untrusted=row.evidence_time_untrusted,
        cash_return_confirmed=row.cash_return_confirmed,
        resolved_at=row.resolved_at,
    )


def _validated_refund_attempt_action_id(value: str) -> str:
    action_id = value.strip()
    prefix = "membership-refund:"
    if not action_id.startswith(prefix):
        raise BusinessRuleError(
            "The saved membership refund action ID is invalid. Refresh the recovery task."
        )
    try:
        UUID(action_id.removeprefix(prefix))
    except (TypeError, ValueError) as exc:
        raise BusinessRuleError(
            "The saved membership refund action ID is invalid. Refresh the recovery task."
        ) from exc
    return action_id


async def _refund_attempt_recovery_to_read(
    session,
    row: MembershipRefundAttemptRecovery,
) -> MembershipRefundAttemptRecoveryRead:
    resolution = (
        await session.execute(
            select(MembershipRefundAttemptResolution).where(
                MembershipRefundAttemptResolution.recovery_id == row.id
            )
        )
    ).scalar_one_or_none()
    actor = await session.get(User, row.registered_by)
    return MembershipRefundAttemptRecoveryRead(
        id=row.id,
        original_client_action_id=row.original_client_action_id,
        customer_id=row.customer_id,
        membership_id=row.membership_id,
        payment_id=row.payment_id,
        source_shift_id=row.source_shift_id,
        expected_amount_minor=row.expected_amount_minor,
        paid_via=row.paid_via,
        captured_at=row.captured_at,
        captured_time_untrusted=row.captured_time_untrusted,
        registered_at=row.registered_at,
        registered_by=row.registered_by,
        registered_by_name=actor.name if actor else None,
        status="resolved" if resolution else "unresolved",
        resolution_id=resolution.id if resolution else None,
    )


async def _refund_attempt_resolution_to_read(
    session,
    row: MembershipRefundAttemptResolution,
) -> MembershipRefundAttemptResolutionRead:
    actor = await session.get(User, row.resolved_by)
    settlement = None
    if row.refund_id is not None:
        settlement = (
            await session.execute(
                select(MembershipRefundSettlement).where(
                    MembershipRefundSettlement.refund_id == row.refund_id
                )
            )
        ).scalar_one_or_none()
    if settlement is not None:
        financial_status = "settled"
    elif row.outcome in {"cash_handed_over", "provider_completed"}:
        financial_status = "payout_completed_pending_posting"
    elif row.outcome == "provider_reversed":
        financial_status = "payout_reversed"
    else:
        financial_status = "no_financial_movement"
    return MembershipRefundAttemptResolutionRead(
        id=row.id,
        recovery_id=row.recovery_id,
        original_client_action_id=row.original_client_action_id,
        customer_id=row.customer_id,
        membership_id=row.membership_id,
        payment_id=row.payment_id,
        source_shift_id=row.source_shift_id,
        reconciliation_shift_id=row.reconciliation_shift_id,
        refund_id=row.refund_id,
        expected_amount_minor=row.expected_amount_minor,
        paid_via=row.paid_via,
        outcome=row.outcome,
        reason=row.reason,
        provider_status=row.provider_status,
        verification_reference=row.verification_reference,
        checked_at=row.checked_at,
        evidence_occurred_at=row.evidence_occurred_at,
        evidence_time_untrusted=row.evidence_time_untrusted,
        provider_evidence_reconciled=row.provider_evidence_reconciled,
        cash_handover_confirmed=row.cash_handover_confirmed,
        resolved_at=row.resolved_at,
        resolved_by=row.resolved_by,
        resolved_by_name=actor.name if actor else None,
        financial_status=financial_status,
        refund_receipt_no=settlement.receipt_no if settlement else None,
        customer_spend_reconciled=(
            settlement.customer_spend_reconciled if settlement else True
        ),
    )


@router.post(
    "/payment-attempts/resolve",
    response_model=MembershipPaymentAttemptResolutionRead,
    status_code=status.HTTP_201_CREATED,
)
async def resolve_rejected_membership_payment_attempt(
    payload: MembershipPaymentAttemptResolutionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipPaymentAttemptResolutionRead:
    """Attest what happened to a rejected legacy membership payment attempt.

    This endpoint never creates or reverses accounting.  Its original action
    created no ``MembershipPayment``; the protected owner is recording the
    verified no-collection or physical recovery needed before the tablet may
    release the exact shift.
    """
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may resolve a rejected membership payment."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Return to the terminal and branch that captured this membership payment."
        )
    original_action_id = payload.original_client_action_id.strip()
    prefix = "membership-subscribe:"
    if not original_action_id.startswith(prefix):
        raise BusinessRuleError(
            "The original membership payment action ID is invalid. Refresh the saved action."
        )
    try:
        UUID(original_action_id.removeprefix(prefix))
    except (TypeError, ValueError) as exc:
        raise BusinessRuleError(
            "The original membership payment action ID is invalid. Refresh the saved action."
        ) from exc

    reason = payload.reason.strip()
    if len(reason) < 3:
        raise BusinessRuleError(
            "Enter how the rejected payment attempt was verified or recovered."
        )
    external_reference = (
        payload.external_reference.strip() if payload.external_reference else None
    )
    provider_checked_at = None
    evidence_occurred_at = None
    evidence_time_untrusted = False
    if payload.paid_via == "cash":
        expected_confirmation = payload.resolution == "cash_returned"
        if (
            payload.resolution not in {"payment_not_collected", "cash_returned"}
            or external_reference is not None
            or payload.provider_verification_status is not None
            or payload.provider_evidence_occurred_at is not None
            or payload.cash_return_confirmed is not expected_confirmation
        ):
            raise BusinessRuleError(
                "For a cash attempt, record payment_not_collected when no notes were "
                "taken, or cash_returned and explicitly confirm the physical return. "
                "Do not enter provider evidence."
            )
    else:
        expected_status = {
            "provider_not_completed": "not_completed",
            "provider_reversed": "reversed",
        }.get(payload.resolution)
        if (
            expected_status is None
            or not external_reference
            or payload.provider_verification_status != expected_status
            or payload.cash_return_confirmed
        ):
            raise BusinessRuleError(
                "For card/UPI payments, verify whether the provider payment never "
                "completed or was reversed, select the matching status, and enter the "
                "provider search or reversal reference. The ERP does not move that money."
            )
        provider_checked_at = datetime.now(timezone.utc)
    provider_evidence_reconciled = (
        payload.paid_via == "cash"
        or external_reference is None
        or len(external_reference) >= 3
    )

    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for membership payment recovery"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipPaymentAttemptResolutionRead.model_validate(replay["body"])

    # Subscribe and recovery both acquire this exact shift first.  Whichever
    # wins determines the truth atomically: a posted payment blocks recovery;
    # a recovery row blocks any later retry of the old payment action.
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    if shift is None or shift.company_id != tenant.company_id:
        raise NotFoundError("membership payment shift not found")
    if shift.branch_id != tenant.branch_id:
        raise BusinessRuleError(
            "This rejected payment belongs to a different branch. Return to its branch."
        )
    if shift.terminal_id != tenant.terminal_id:
        raise BusinessRuleError(
            "This rejected payment belongs to a different terminal. Return to the tablet "
            "that captured it."
        )
    if payload.paid_via != "cash":
        evidence_input = payload.provider_evidence_occurred_at or provider_checked_at
        (
            evidence_occurred_at,
            evidence_time_untrusted,
        ) = _capture_financial_evidence(
            request=request,
            occurred_at=evidence_input,
            shift=shift,
            action_started_at=shift.opened_at,
            server_now=provider_checked_at,
        )

    posted_payment = (
        await session.execute(
            select(MembershipPayment.id)
            .where(
                MembershipPayment.company_id == tenant.company_id,
                MembershipPayment.idempotency_key == original_action_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if posted_payment is not None:
        raise BusinessRuleError(
            "This payment did post successfully. Refresh the customer's membership history "
            "and use its audited refund flow; do not mark the attempt as rejected."
        )

    accepted_request = (
        await session.execute(
            select(MembershipPaymentRequest.id)
            .where(
                MembershipPaymentRequest.company_id == tenant.company_id,
                MembershipPaymentRequest.client_action_id == original_action_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if accepted_request is not None:
        raise BusinessRuleError(
            "This action has a server-accepted payment request. Open that task and "
            "settle or withdraw it; the legacy rejected-attempt recovery cannot hide it."
        )

    existing = (
        await session.execute(
            select(MembershipPaymentAttemptResolution)
            .where(
                MembershipPaymentAttemptResolution.company_id == tenant.company_id,
                MembershipPaymentAttemptResolution.original_client_action_id
                == original_action_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None:
        same_fact = (
            existing.customer_id == payload.customer_id
            and existing.tier_id == payload.tier_id
            and existing.shift_id == payload.shift_id
            and existing.expected_amount_minor == payload.expected_amount_minor
            and existing.paid_via == payload.paid_via
            and existing.resolution == payload.resolution
            and existing.reason == reason
            and existing.external_reference == external_reference
            and existing.provider_verification_status
            == payload.provider_verification_status
            and existing.provider_evidence_reconciled
            == provider_evidence_reconciled
            and existing.cash_return_confirmed == payload.cash_return_confirmed
            and (
                existing.evidence_occurred_at is None
                if evidence_occurred_at is None
                else existing.evidence_occurred_at is not None
                and abs(
                    (existing.evidence_occurred_at - evidence_occurred_at).total_seconds()
                )
                <= 1
            )
        )
        if not same_fact:
            raise BusinessRuleError(
                "This rejected payment action was already resolved with different evidence. "
                "Refresh its recovery record; financial audit history cannot be overwritten."
            )
        response = _payment_attempt_resolution_to_read(existing)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    customer = await session.get(Customer, payload.customer_id)
    if customer is None or customer.company_id != tenant.company_id:
        raise NotFoundError("membership payment customer not found")
    tier = await session.get(MembershipTier, payload.tier_id)
    if tier is None or tier.company_id != tenant.company_id:
        raise NotFoundError("membership payment tier not found")

    row = MembershipPaymentAttemptResolution(
        id=uuid4(),
        company_id=tenant.company_id,
        customer_id=customer.id,
        tier_id=tier.id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        original_client_action_id=original_action_id,
        paid_via=payload.paid_via,
        expected_amount_minor=payload.expected_amount_minor,
        resolution=payload.resolution,
        reason=reason,
        external_reference=external_reference,
        provider_verification_status=payload.provider_verification_status,
        provider_checked_at=provider_checked_at,
        provider_evidence_reconciled=provider_evidence_reconciled,
        evidence_occurred_at=evidence_occurred_at,
        evidence_time_untrusted=evidence_time_untrusted,
        cash_return_confirmed=payload.cash_return_confirmed,
        resolved_at=datetime.now(timezone.utc),
        resolved_by=tenant.user_id,
        idempotency_key=idempotency_key,
    )
    session.add(row)
    await session.flush()
    response = _payment_attempt_resolution_to_read(row)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refund-attempts/register",
    response_model=MembershipRefundAttemptRecoveryRead,
    status_code=status.HTTP_201_CREATED,
)
async def register_rejected_membership_refund_attempt(
    payload: MembershipRefundAttemptRegistrationRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundAttemptRecoveryRead:
    """Quarantine one pre-reservation refund outbox row on the server.

    Registration asserts neither payout nor reversal and has no accounting
    effect. It only makes the possible money movement durable across reinstall,
    another device, and shift-close checks until a protected owner verifies it.
    """
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may register a legacy membership refund task."
        )
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError(
            "Return to the branch and terminal that created this saved refund task."
        )
    original_action_id = _validated_refund_attempt_action_id(
        payload.original_client_action_id
    )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for membership refund recovery registration"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundAttemptRecoveryRead.model_validate(replay["body"])

    source_shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.source_shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    if source_shift is None or source_shift.company_id != tenant.company_id:
        raise NotFoundError("membership refund source shift not found")
    if source_shift.branch_id != tenant.branch_id:
        raise BusinessRuleError(
            "This saved refund belongs to a different branch. Return to that branch."
        )
    if source_shift.terminal_id != tenant.terminal_id:
        raise BusinessRuleError(
            "This saved refund belongs to a different terminal. Return to that tablet."
        )

    payment_row = (
        await session.execute(
            select(MembershipPayment, CustomerMembership, Customer)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .where(
                MembershipPayment.id == payload.payment_id,
                MembershipPayment.company_id == tenant.company_id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update(of=MembershipPayment)
        )
    ).one_or_none()
    if payment_row is None:
        raise NotFoundError("membership payment for this refund recovery was not found")
    payment, membership, customer = payment_row
    if (
        membership.id != payload.membership_id
        or customer.id != payload.customer_id
        or payment.method != payload.paid_via
        or payment.amount_minor != payload.expected_amount_minor
    ):
        raise BusinessRuleError(
            "The saved refund no longer matches its immutable membership payment. "
            "Refresh the payment history; nothing was posted or dismissed."
        )
    posted_refund = (
        await session.execute(
            select(MembershipRefund.id).where(
                MembershipRefund.company_id == tenant.company_id,
                MembershipRefund.idempotency_key == original_action_id,
            )
        )
    ).scalar_one_or_none()
    if posted_refund is not None:
        raise BusinessRuleError(
            "This saved refund already reached the server. Refresh and adopt the existing "
            "refund task instead of registering a legacy attempt."
        )

    registered_at = datetime.now(timezone.utc)
    captured_at, captured_time_untrusted = _capture_financial_evidence(
        request=request,
        occurred_at=payload.captured_at,
        shift=source_shift,
        action_started_at=source_shift.opened_at,
        server_now=registered_at,
    )
    existing = (
        await session.execute(
            select(MembershipRefundAttemptRecovery)
            .where(
                MembershipRefundAttemptRecovery.company_id == tenant.company_id,
                MembershipRefundAttemptRecovery.original_client_action_id
                == original_action_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None:
        same_fact = (
            existing.customer_id == payload.customer_id
            and existing.membership_id == payload.membership_id
            and existing.payment_id == payload.payment_id
            and existing.source_shift_id == payload.source_shift_id
            and existing.expected_amount_minor == payload.expected_amount_minor
            and existing.paid_via == payload.paid_via
            and existing.captured_time_untrusted == captured_time_untrusted
            and abs((existing.captured_at - captured_at).total_seconds()) <= 1
        )
        if not same_fact:
            raise BusinessRuleError(
                "This refund action is already quarantined with different immutable "
                "evidence. Refresh the server task; it cannot be overwritten."
            )
        response = await _refund_attempt_recovery_to_read(session, existing)
    else:
        existing = MembershipRefundAttemptRecovery(
            id=uuid4(),
            company_id=tenant.company_id,
            customer_id=customer.id,
            membership_id=membership.id,
            payment_id=payment.id,
            source_branch_id=tenant.branch_id,
            source_terminal_id=tenant.terminal_id,
            source_shift_id=source_shift.id,
            original_client_action_id=original_action_id,
            paid_via=payment.method,
            expected_amount_minor=payment.amount_minor,
            captured_at=captured_at,
            captured_time_untrusted=captured_time_untrusted,
            registered_at=registered_at,
            registered_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(existing)
        await session.flush()
        response = await _refund_attempt_recovery_to_read(session, existing)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get(
    "/refund-attempts",
    response_model=list[MembershipRefundAttemptRecoveryRead],
)
async def list_rejected_membership_refund_attempts(
    session: SessionDep,
    response: Response,
    tenant: TenantContext = Depends(requires("admin.system")),
    unresolved: bool = True,
    source_shift_id: UUID | None = None,
    recovery_id: UUID | None = None,
    original_client_action_id: str | None = None,
    limit: int = Query(default=100, ge=1, le=200),
) -> list[MembershipRefundAttemptRecoveryRead]:
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may view legacy membership refund tasks."
        )
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError("Select this tablet's branch and terminal first.")
    stmt = (
        select(MembershipRefundAttemptRecovery)
        .outerjoin(
            MembershipRefundAttemptResolution,
            MembershipRefundAttemptResolution.recovery_id
            == MembershipRefundAttemptRecovery.id,
        )
        .where(
            MembershipRefundAttemptRecovery.company_id == tenant.company_id,
            MembershipRefundAttemptRecovery.source_branch_id == tenant.branch_id,
            MembershipRefundAttemptRecovery.source_terminal_id == tenant.terminal_id,
        )
        .order_by(MembershipRefundAttemptRecovery.registered_at.desc())
        .limit(limit + 1)
    )
    if unresolved:
        stmt = stmt.where(MembershipRefundAttemptResolution.id.is_(None))
    if source_shift_id is not None:
        stmt = stmt.where(
            MembershipRefundAttemptRecovery.source_shift_id == source_shift_id
        )
    if recovery_id is not None:
        stmt = stmt.where(MembershipRefundAttemptRecovery.id == recovery_id)
    if original_client_action_id is not None:
        clean_action_id = original_client_action_id.strip()
        if not clean_action_id:
            raise BusinessRuleError("Membership refund recovery action ID cannot be blank.")
        stmt = stmt.where(
            MembershipRefundAttemptRecovery.original_client_action_id
            == clean_action_id
        )
    rows = (await session.execute(stmt)).scalars().all()
    truncated = len(rows) > limit
    rows = rows[:limit]
    response.headers["X-Result-Truncated"] = str(truncated).lower()
    response.headers["X-Result-Limit"] = str(limit)
    return [await _refund_attempt_recovery_to_read(session, row) for row in rows]


@router.post(
    "/refund-attempts/resolve",
    response_model=MembershipRefundAttemptResolutionRead,
    status_code=status.HTTP_201_CREATED,
)
async def resolve_rejected_membership_refund_attempt(
    payload: MembershipRefundAttemptResolutionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundAttemptResolutionRead:
    """Resolve a quarantined pre-reservation refund with verified evidence."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may resolve a legacy membership refund task."
        )
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError(
            "Return to the branch and terminal that owns this refund task."
        )
    original_action_id = _validated_refund_attempt_action_id(
        payload.original_client_action_id
    )
    reason = payload.reason.strip()
    verification_reference = (
        payload.verification_reference.strip()
        if payload.verification_reference
        else None
    )
    financial_outcome = payload.outcome in {"cash_handed_over", "provider_completed"}
    if payload.paid_via == "cash":
        if payload.outcome not in {"cash_not_handed_over", "cash_handed_over"}:
            raise BusinessRuleError(
                "Cash recovery must record either that no notes were handed over or "
                "explicitly confirm that cash was handed over."
            )
        if payload.provider_status is not None or verification_reference is not None:
            raise BusinessRuleError("Do not attach provider evidence to a cash refund.")
        if payload.cash_handover_confirmed != (payload.outcome == "cash_handed_over"):
            raise BusinessRuleError(
                "Explicitly confirm physical cash handover only when it actually occurred."
            )
    else:
        expected_status = {
            "no_payout": "not_completed",
            "provider_reversed": "reversed",
            "provider_completed": "completed",
        }.get(payload.outcome)
        if (
            expected_status is None
            or payload.provider_status != expected_status
            or not verification_reference
            or payload.cash_handover_confirmed
        ):
            raise BusinessRuleError(
                "Verify the provider, choose not completed/reversed/completed, and enter "
                "its nonblank verification reference. Unknown state must stay unresolved."
            )
    if financial_outcome != (payload.reconciliation_shift_id is not None):
        raise BusinessRuleError(
            "A completed payout requires an explicitly selected open reconciliation shift; "
            "a no-payout/reversed outcome must not select one."
        )

    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for membership refund attempt resolution"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundAttemptResolutionRead.model_validate(replay["body"])

    # Shift close always locks its Shift before any recovery rows.  Resolve
    # must use the same order or a same-shift financial recovery can deadlock:
    # resolve holds Recovery and waits for Shift while close holds Shift and
    # waits for Recovery.  Lock every involved shift first, in UUID order, so
    # cross-shift recoveries cannot invert each other either.  Company scope is
    # applied before locking to preserve tenant opacity for guessed UUIDs.
    requested_shift_ids = {payload.source_shift_id}
    if payload.reconciliation_shift_id is not None:
        requested_shift_ids.add(payload.reconciliation_shift_id)
    locked_shift_rows = (
        await session.execute(
            select(Shift)
            .where(
                Shift.id.in_(requested_shift_ids),
                Shift.company_id == tenant.company_id,
            )
            .order_by(Shift.id)
            .with_for_update()
        )
    ).scalars().all()
    locked_shifts = {row.id: row for row in locked_shift_rows}
    source_shift = locked_shifts.get(payload.source_shift_id)
    reconciliation_shift = (
        locked_shifts.get(payload.reconciliation_shift_id)
        if payload.reconciliation_shift_id is not None
        else None
    )
    if source_shift is None:
        raise NotFoundError(
            "Register this legacy refund task on the server before resolving it."
        )
    if financial_outcome and reconciliation_shift is None:
        raise NotFoundError("membership refund reconciliation shift not found")

    recovery = (
        await session.execute(
            select(MembershipRefundAttemptRecovery)
            .where(
                MembershipRefundAttemptRecovery.company_id == tenant.company_id,
                MembershipRefundAttemptRecovery.original_client_action_id
                == original_action_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if recovery is None:
        raise NotFoundError(
            "Register this legacy refund task on the server before resolving it."
        )
    if (
        recovery.source_branch_id != tenant.branch_id
        or recovery.source_terminal_id != tenant.terminal_id
    ):
        raise BusinessRuleError(
            "Return to the exact branch and terminal that registered this refund task."
        )
    if (
        recovery.customer_id != payload.customer_id
        or recovery.membership_id != payload.membership_id
        or recovery.payment_id != payload.payment_id
        or recovery.source_shift_id != payload.source_shift_id
        or recovery.expected_amount_minor != payload.expected_amount_minor
        or recovery.paid_via != payload.paid_via
    ):
        raise BusinessRuleError(
            "The resolution does not match the quarantined refund evidence. Refresh it; "
            "audit history cannot be overwritten."
        )
    existing = (
        await session.execute(
            select(MembershipRefundAttemptResolution)
            .where(MembershipRefundAttemptResolution.recovery_id == recovery.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None:
        same_fact = (
            existing.outcome == payload.outcome
            and existing.reason == reason
            and existing.reconciliation_shift_id == payload.reconciliation_shift_id
            and existing.provider_status == payload.provider_status
            and existing.verification_reference == verification_reference
            and existing.cash_handover_confirmed == payload.cash_handover_confirmed
        )
        if not same_fact:
            raise BusinessRuleError(
                "This legacy refund was already resolved with different immutable evidence."
            )
        response = await _refund_attempt_resolution_to_read(session, existing)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    if (
        source_shift.company_id != tenant.company_id
        or source_shift.branch_id != recovery.source_branch_id
        or source_shift.terminal_id != recovery.source_terminal_id
    ):
        raise BusinessRuleError("The quarantined source-shift provenance is invalid.")

    payment_row = (
        await session.execute(
            select(MembershipPayment, CustomerMembership, Customer, MembershipTier)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .where(
                MembershipPayment.id == recovery.payment_id,
                MembershipPayment.company_id == tenant.company_id,
                Customer.company_id == tenant.company_id,
            )
        )
    ).one_or_none()
    if payment_row is None:
        raise NotFoundError("membership payment for this recovery was not found")
    payment, membership, customer, tier = payment_row
    if (
        membership.id != recovery.membership_id
        or customer.id != recovery.customer_id
        or payment.method != recovery.paid_via
        or payment.amount_minor != recovery.expected_amount_minor
    ):
        raise BusinessRuleError("The immutable payment no longer matches this recovery task.")

    now = datetime.now(timezone.utc)
    checked_at = now
    evidence_occurred_at = None
    evidence_time_untrusted = False
    provider_evidence_reconciled = True
    if payload.paid_via != "cash":
        evidence_occurred_at, evidence_time_untrusted = _capture_financial_evidence(
            request=request,
            occurred_at=payload.evidence_occurred_at or recovery.captured_at,
            shift=source_shift,
            action_started_at=recovery.registered_at,
            server_now=now,
        )
        provider_evidence_reconciled = len(verification_reference or "") >= 3

    refund = None
    if financial_outcome:
        customer_order_ids = (
            await session.execute(
                select(Order.id)
                .where(
                    Order.company_id == tenant.company_id,
                    Order.status.in_(("open", "held")),
                    or_(
                        Order.customer_id == customer.id,
                        Order.customer_phone == customer.phone,
                        Order.id.in_(
                            select(MembershipBenefitReservation.order_id).where(
                                MembershipBenefitReservation.membership_id == membership.id,
                                MembershipBenefitReservation.consumed_at.is_(None),
                            )
                        ),
                    ),
                )
                .order_by(Order.id)
                .with_for_update()
            )
        ).scalars().all()
        if customer_order_ids:
            raise BusinessRuleError(
                "This customer has an open or held bill using possible membership benefits. "
                "Settle, void, or reprice it before posting the recovered payout."
            )
        reconciliation_shift = require_open_operational_shift(
            reconciliation_shift,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="recording this recovered membership refund payout",
        )
        require_shift_opener(
            reconciliation_shift,
            user_id=tenant.user_id,
            protected_access=tenant.protected_access,
            operation="record this recovered membership refund payout",
        )
        locked_payment_row = (
            await session.execute(
                select(MembershipPayment, CustomerMembership, Customer, MembershipTier)
                .join(
                    CustomerMembership,
                    CustomerMembership.id == MembershipPayment.membership_id,
                )
                .join(Customer, Customer.id == CustomerMembership.customer_id)
                .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
                .where(
                    MembershipPayment.id == recovery.payment_id,
                    MembershipPayment.company_id == tenant.company_id,
                    Customer.company_id == tenant.company_id,
                )
                .with_for_update()
            )
        ).one_or_none()
        if locked_payment_row is None:
            raise NotFoundError("membership payment for this recovery was not found")
        payment, membership, customer, tier = locked_payment_row
        if (
            membership.id != recovery.membership_id
            or customer.id != recovery.customer_id
            or payment.method != recovery.paid_via
            or payment.amount_minor != recovery.expected_amount_minor
        ):
            raise BusinessRuleError(
                "The immutable payment changed while this recovery was being checked."
            )
        settled = (
            await session.execute(
                select(MembershipRefundSettlement.id).where(
                    MembershipRefundSettlement.payment_id == payment.id
                )
            )
        ).scalar_one_or_none()
        unresolved_refund = (
            await session.execute(
                select(MembershipRefund.id)
                .outerjoin(
                    MembershipRefundResolution,
                    MembershipRefundResolution.refund_id == MembershipRefund.id,
                )
                .where(
                    MembershipRefund.payment_id == payment.id,
                    MembershipRefundResolution.id.is_(None),
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if settled or unresolved_refund:
            raise BusinessRuleError(
                "This payment already has an active or settled refund. Refresh its history; "
                "a second refund cannot be created."
            )
        live_order = (
            await session.execute(
                select(Order.id)
                .where(
                    Order.company_id == tenant.company_id,
                    Order.status.in_(("open", "held")),
                    or_(
                        Order.customer_id == customer.id,
                        Order.customer_phone == customer.phone,
                    ),
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if live_order is not None:
            raise BusinessRuleError(
                "A bill was opened while this recovery was being checked. Settle, void, "
                "or reprice it before retrying."
            )
        refund = MembershipRefund(
            id=uuid4(),
            company_id=tenant.company_id,
            payment_id=payment.id,
            branch_id=reconciliation_shift.branch_id,
            terminal_id=reconciliation_shift.terminal_id,
            shift_id=reconciliation_shift.id,
            method=payment.method,
            amount_minor=payment.amount_minor,
            accepted_at=now,
            approved_by=tenant.user_id,
            idempotency_key=f"membership-legacy-refund:{recovery.id}",
            reason=reason,
        )
        session.add(refund)
        membership.auto_renew = False
        membership.revoked_at = now
        await session.flush()

    resolution = MembershipRefundAttemptResolution(
        id=uuid4(),
        recovery_id=recovery.id,
        company_id=tenant.company_id,
        customer_id=customer.id,
        membership_id=membership.id,
        payment_id=payment.id,
        source_branch_id=recovery.source_branch_id,
        source_terminal_id=recovery.source_terminal_id,
        source_shift_id=recovery.source_shift_id,
        reconciliation_shift_id=(reconciliation_shift.id if reconciliation_shift else None),
        refund_id=refund.id if refund else None,
        original_client_action_id=original_action_id,
        paid_via=payment.method,
        expected_amount_minor=payment.amount_minor,
        outcome=payload.outcome,
        reason=reason,
        provider_status=payload.provider_status,
        verification_reference=verification_reference,
        checked_at=checked_at,
        evidence_occurred_at=evidence_occurred_at,
        evidence_time_untrusted=evidence_time_untrusted,
        provider_evidence_reconciled=provider_evidence_reconciled,
        cash_handover_confirmed=payload.cash_handover_confirmed,
        resolved_at=now,
        resolved_by=tenant.user_id,
        idempotency_key=idempotency_key,
    )
    session.add(resolution)
    await session.flush()
    if refund is not None:
        session.add(
            MembershipRefundCompletion(
                id=uuid4(),
                company_id=tenant.company_id,
                refund_id=refund.id,
                cash_handoff_id=None,
                provider_action_id=None,
                legacy_attempt_resolution_id=resolution.id,
                branch_id=refund.branch_id,
                terminal_id=refund.terminal_id,
                shift_id=refund.shift_id,
                method=refund.method,
                amount_minor=refund.amount_minor,
                completed_at=now,
                completed_by=tenant.user_id,
                external_reference=(
                    verification_reference if refund.method != "cash" else None
                ),
                provider_evidence_reconciled=provider_evidence_reconciled,
                evidence_occurred_at=evidence_occurred_at,
                evidence_time_untrusted=evidence_time_untrusted,
                action_takeover_confirmed=False,
                action_takeover_reason=None,
                idempotency_key=f"membership-legacy-refund-completion:{resolution.id}",
            )
        )
        await session.flush()
    response = await _refund_attempt_resolution_to_read(session, resolution)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/{subscription_id}/refund",
    response_model=MembershipRefundRead,
    status_code=status.HTTP_201_CREATED,
)
async def refund_membership(
    subscription_id: UUID,
    payload: MembershipRefundRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundRead:
    """Reverse one paid term in full and end its benefits immediately.

    This is deliberately separate from ``cancel``. Cancel only stops renewal;
    refund is an append-only money movement that preserves the original sale.
    """
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may refund a membership payment.")
    if len(payload.reason.strip()) < 3:
        raise BusinessRuleError("Refund reason must contain at least 3 visible characters.")
    if payload.settled_at is not None or payload.external_reference is not None:
        raise BusinessRuleError(
            "Reserve the membership refund in the ERP before handing over cash or "
            "starting a card/UPI provider refund. This first step records no payout; "
            "use the separate settlement step only after server acceptance."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Cannot refund a membership without a verified POS terminal and branch. "
            "Select this tablet's terminal, open a shift, then retry."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for membership refunds")
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundRead.model_validate(replay["body"])

    # Existing POS money paths lock Order -> Shift -> Customer. Check and lock
    # any already-live customer bill first, so refund can never introduce the
    # reverse Shift -> Order edge. A second plain recheck after the membership
    # lock closes the no-row race; a POS action started meanwhile either holds
    # Shift and finishes first, or waits and then observes the revocation.
    preflight = (
        await session.execute(
            select(CustomerMembership.customer_id, Customer.phone)
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .where(
                CustomerMembership.id == subscription_id,
                Customer.company_id == tenant.company_id,
            )
        )
    ).one_or_none()
    if preflight is None:
        raise NotFoundError("membership subscription not found")
    preflight_customer_id, preflight_phone = preflight
    reserved_order_ids = select(MembershipBenefitReservation.order_id).where(
        MembershipBenefitReservation.membership_id == subscription_id,
        MembershipBenefitReservation.consumed_at.is_(None),
    )
    preexisting_orders = (
        await session.execute(
            select(Order.id)
            .where(
                Order.company_id == tenant.company_id,
                Order.status.in_(("open", "held")),
                or_(
                    Order.customer_id == preflight_customer_id,
                    Order.customer_phone == preflight_phone,
                    Order.id.in_(reserved_order_ids),
                ),
            )
            .order_by(Order.id)
            .with_for_update()
        )
    ).scalars().all()
    if preexisting_orders:
        raise BusinessRuleError(
            "This customer has an open or held bill that may contain membership "
            "discounts or benefits. Settle, void, or reprice it before refunding "
            "the membership."
        )

    # Cash changes this verified drawer; non-cash rails are still bound to the
    # approving shift for operational accountability and audit provenance.
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
        operation="refunding this membership payment",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="refund this membership payment",
    )

    sub_and_customer = (
        await session.execute(
            select(CustomerMembership, Customer)
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .where(
                CustomerMembership.id == subscription_id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if sub_and_customer is None:
        raise NotFoundError("membership subscription not found")
    sub, customer = sub_and_customer
    live_order = (
        await session.execute(
            select(Order.id)
            .where(
                Order.company_id == tenant.company_id,
                Order.status.in_(("open", "held")),
                or_(
                    Order.customer_id == customer.id,
                    Order.customer_phone == customer.phone,
                    Order.id.in_(reserved_order_ids),
                ),
            )
            .order_by(Order.id)
            .limit(1)
        )
    ).scalar_one_or_none()
    if live_order is not None:
        raise BusinessRuleError(
            "This customer has an open or held bill that may already contain membership "
            "discounts or benefits. Settle, void, or reprice that bill before refunding "
            "the membership."
        )
    payment = (
        await session.execute(
            select(MembershipPayment)
            .where(
                MembershipPayment.membership_id == sub.id,
                MembershipPayment.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if payment is None:
        raise BusinessRuleError(
            "This historical membership has no verified payment record, so it cannot "
            "be refunded automatically. Reconcile it against independent payment "
            "evidence first."
        )
    if payment.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The saved membership payment amount no longer matches this refund request. "
            "Refresh the membership record and review it before trying again."
        )
    settled_payment = (
        await session.execute(
            select(MembershipRefundSettlement.id)
            .where(MembershipRefundSettlement.payment_id == payment.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if settled_payment is not None:
        raise BusinessRuleError(
            "This membership payment has already been settled as a refund. "
            "No second refund was created."
        )
    unresolved_attempt = (
        await session.execute(
            select(MembershipRefund)
            .outerjoin(
                MembershipRefundSettlement,
                MembershipRefundSettlement.refund_id == MembershipRefund.id,
            )
            .outerjoin(
                MembershipRefundResolution,
                MembershipRefundResolution.refund_id == MembershipRefund.id,
            )
            .where(
                MembershipRefund.payment_id == payment.id,
                MembershipRefundSettlement.id.is_(None),
                MembershipRefundResolution.id.is_(None),
            )
            .limit(1)
            .with_for_update(of=MembershipRefund)
        )
    ).scalar_one_or_none()
    if unresolved_attempt is not None:
        raise BusinessRuleError(
            "This membership payment already has an accepted refund waiting for "
            "settlement or withdrawal. Resolve it before starting another attempt."
        )
    already_reserved_for_customer = await _unresolved_customer_refund_total(
        session,
        customer_id=customer.id,
    )
    if int(customer.total_spent_minor or 0) < (
        already_reserved_for_customer + int(payment.amount_minor)
    ):
        raise BusinessRuleError(
            "Customer lifetime spend is inconsistent with accepted refund tasks. "
            "Reconcile LTV before authorising another payout."
        )
    if payload.method == "cash":
        already_reserved = await _unresolved_cash_refund_total(
            session,
            shift_id=shift.id,
        )
        available_drawer = int(shift.expected_minor or 0) - already_reserved
        if int(payment.amount_minor) > available_drawer:
            raise BusinessRuleError(
                "This drawer does not have enough expected cash for the refund after "
                "other accepted cash refunds. Use the correct shift or settle/withdraw "
                "the earlier cash refund first; do not top up the drawer silently."
            )

    accepted_at = datetime.now(timezone.utc)
    refund = MembershipRefund(
        id=uuid4(),
        company_id=tenant.company_id,
        payment_id=payment.id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        method=payload.method,
        amount_minor=payment.amount_minor,
        accepted_at=accepted_at,
        approved_by=tenant.user_id,
        idempotency_key=idempotency_key,
        reason=payload.reason.strip(),
    )
    session.add(refund)

    # Acceptance is enough to revoke benefits, preventing a bill from using a
    # term while its refund is waiting for physical/provider completion.
    # Accounting changes only when the separate immutable settlement exists.
    # Preserve starts_at/amount_paid and the immutable payment row as history.
    sub.auto_renew = False
    sub.revoked_at = accepted_at
    await session.flush()
    accepted_actor = await session.get(User, refund.approved_by)
    response = _refund_to_read(
        refund,
        membership_id=sub.id,
        customer=customer,
        payment=payment,
        accepted_by_name=accepted_actor.name if accepted_actor else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refunds/{refund_id}/begin-cash-handoff",
    response_model=MembershipRefundRead,
    status_code=status.HTTP_201_CREATED,
)
async def begin_cash_membership_refund_handoff(
    refund_id: UUID,
    payload: MembershipRefundCashHandoffRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundRead:
    """Persist the danger state immediately before cash leaves the drawer."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may begin a cash membership refund handover."
        )
    if not payload.ready_to_handover:
        raise BusinessRuleError(
            "Confirm only when the correct cash amount is ready and the customer is present."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Return to the terminal and shift that accepted this cash refund."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required before cash refund handover"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundRead.model_validate(replay["body"])

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
        operation="beginning this cash membership refund handover",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="begin this cash membership refund handover",
    )
    row = (
        await session.execute(
            select(
                MembershipRefund,
                MembershipPayment,
                CustomerMembership,
                Customer,
                MembershipTier,
            )
            .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
                MembershipRefund.shift_id == shift.id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("membership refund not found for this shift")
    refund, payment, sub, customer, tier = row
    if refund.method != "cash":
        raise BusinessRuleError(
            "This is a provider refund. Wait for server acceptance, complete it with "
            "the provider, then record its reference; do not touch the cash drawer."
        )
    if refund.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted refund amount differs from the cash ready for handover. "
            "Refresh the refund and verify the original receipt."
        )
    settlement = (
        await session.execute(
            select(MembershipRefundSettlement).where(
                MembershipRefundSettlement.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    if settlement is not None:
        raise BusinessRuleError(
            "This cash refund is already settled. Do not hand over cash again."
        )
    resolution = (
        await session.execute(
            select(MembershipRefundResolution).where(
                MembershipRefundResolution.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    if resolution is not None:
        raise BusinessRuleError(
            "This cash refund was withdrawn. Do not hand over cash."
        )
    handoff = (
        await session.execute(
            select(MembershipRefundCashHandoff)
            .where(MembershipRefundCashHandoff.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if handoff is None:
        other_reserved = await _unresolved_cash_refund_total(
            session,
            shift_id=shift.id,
            exclude_refund_id=refund.id,
        )
        if refund.amount_minor > int(shift.expected_minor or 0) - other_reserved:
            raise BusinessRuleError(
                "The drawer no longer has enough expected cash after other reserved "
                "refunds. Resolve those obligations before touching the drawer."
            )
        handoff = MembershipRefundCashHandoff(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_id=refund.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            started_at=datetime.now(timezone.utc),
            started_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(handoff)
        await session.flush()
    else:
        await _require_same_begin_actor(
            session,
            started_by=handoff.started_by,
            current_user_id=tenant.user_id,
            started_at=handoff.started_at,
            action_label="membership cash refund handover",
        )
    accepted_actor = await session.get(User, refund.approved_by)
    action_actor = await session.get(User, handoff.started_by)
    response = _refund_to_read(
        refund,
        membership_id=sub.id,
        handoff=handoff,
        customer=customer,
        tier=tier,
        payment=payment,
        accepted_by_name=accepted_actor.name if accepted_actor else None,
        action_started_by_name=action_actor.name if action_actor else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refunds/{refund_id}/begin-provider-action",
    response_model=MembershipRefundRead,
    status_code=status.HTTP_201_CREATED,
)
async def begin_provider_membership_refund(
    refund_id: UUID,
    payload: MembershipRefundProviderActionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundRead:
    """Persist ownership before staff initiate an external refund."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may start a provider membership refund."
        )
    if not payload.ready_to_start:
        raise BusinessRuleError(
            "Confirm the original receipt, amount, and provider before starting."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Return to the terminal and shift that accepted this provider refund."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required before provider membership refund"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundRead.model_validate(replay["body"])

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
        operation="starting this provider membership refund",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="start this provider membership refund",
    )
    row = (
        await session.execute(
            select(
                MembershipRefund,
                MembershipPayment,
                CustomerMembership,
                Customer,
                MembershipTier,
            )
            .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
                MembershipRefund.shift_id == shift.id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("membership refund not found for this shift")
    refund, payment, sub, customer, tier = row
    if refund.method == "cash":
        raise BusinessRuleError("Use the cash handover workflow for this refund.")
    if refund.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted refund amount changed. Refresh before touching the provider."
        )
    settlement = (
        await session.execute(
            select(MembershipRefundSettlement.id).where(
                MembershipRefundSettlement.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipRefundResolution.id).where(
                MembershipRefundResolution.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    if settlement is not None:
        raise BusinessRuleError(
            "This provider refund is already settled. Do not start another payout."
        )
    if resolution is not None:
        raise BusinessRuleError(
            "This provider refund was withdrawn. Do not start a payout."
        )
    provider_action = (
        await session.execute(
            select(MembershipRefundProviderAction)
            .where(MembershipRefundProviderAction.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if provider_action is None:
        provider_action = MembershipRefundProviderAction(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_id=refund.id,
            branch_id=shift.branch_id,
            terminal_id=shift.terminal_id,
            shift_id=shift.id,
            method=refund.method,
            started_at=datetime.now(timezone.utc),
            started_by=tenant.user_id,
            idempotency_key=idempotency_key,
        )
        session.add(provider_action)
        await session.flush()
    else:
        await _require_same_begin_actor(
            session,
            started_by=provider_action.started_by,
            current_user_id=tenant.user_id,
            started_at=provider_action.started_at,
            action_label="membership provider refund",
        )
    accepted_actor = await session.get(User, refund.approved_by)
    action_actor = await session.get(User, provider_action.started_by)
    response = _refund_to_read(
        refund,
        membership_id=sub.id,
        provider_action=provider_action,
        customer=customer,
        tier=tier,
        payment=payment,
        accepted_by_name=accepted_actor.name if accepted_actor else None,
        action_started_by_name=action_actor.name if action_actor else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refunds/{refund_id}/settle-cash",
    response_model=MembershipRefundRead,
    status_code=status.HTTP_201_CREATED,
)
async def settle_cash_membership_refund(
    refund_id: UUID,
    payload: CashMembershipRefundSettlementRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundRead:
    """Persist the physical cash handover before accounting finalization.

    This endpoint intentionally does not allocate a receipt, change the drawer,
    or touch customer lifetime spend.  Once cash leaves, the completion fact is
    committed on its own so a later receipt/accounting failure cannot erase the
    only durable proof of the handover.
    """
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may settle a cash membership refund."
        )
    if not payload.cash_handed_over:
        raise BusinessRuleError(
            "Hand the cash to the customer, then explicitly confirm the handover."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Cannot settle a cash membership refund without a verified POS terminal "
            "and branch. Return to the original terminal and open its shift."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for cash membership refund settlement"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundRead.model_validate(replay["body"])

    # Read the immutable acceptance only to discover its exact shift. The
    # financial lock order remains Shift -> Refund -> Payment -> Customer.
    preflight = (
        await session.execute(
            select(MembershipRefund.shift_id).where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
            )
        )
    ).scalar_one_or_none()
    if preflight is None:
        raise NotFoundError("membership refund not found")
    if preflight != payload.shift_id:
        raise BusinessRuleError(
            "This cash refund belongs to a different shift. Return to the terminal "
            "and shift that accepted it; no drawer was changed."
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
        operation="settling this cash membership refund",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="settle this cash membership refund",
    )

    row = (
        await session.execute(
            select(
                MembershipRefund,
                MembershipPayment,
                CustomerMembership,
                Customer,
                MembershipTier,
            )
            .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("membership refund not found")
    refund, payment, sub, customer, tier = row
    if refund.method != "cash":
        raise BusinessRuleError(
            "This refund was recorded on a non-cash rail and cannot change the drawer."
        )
    if refund.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted refund amount differs from this confirmation. Refresh and "
            "review the receipt before handing over cash."
        )
    handoff = (
        await session.execute(
            select(MembershipRefundCashHandoff)
            .where(MembershipRefundCashHandoff.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if handoff is None:
        raise BusinessRuleError(
            "Begin the server-confirmed cash handover before cash leaves the drawer."
        )
    actor = await session.get(User, handoff.started_by)
    actor_name = actor.name if actor else str(handoff.started_by)
    takeover_confirmed, takeover_reason = _verified_takeover_attestation(
        started_by=handoff.started_by,
        current_user_id=tenant.user_id,
        confirmed=payload.action_takeover_confirmed,
        reason=payload.action_takeover_reason,
        started_by_name=actor_name,
        action_label="cash refund handover",
    )
    existing = (
        await session.execute(
            select(MembershipRefundCompletion)
            .where(MembershipRefundCompletion.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    settlement = (
        await session.execute(
            select(MembershipRefundSettlement).where(
                MembershipRefundSettlement.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipRefundResolution)
            .where(MembershipRefundResolution.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if resolution is not None:
        raise BusinessRuleError(
            "This cash refund was withdrawn because no cash was handed over. "
            "It cannot be settled afterward."
        )
    completed_at = datetime.now(timezone.utc)
    evidence_occurred_at, evidence_time_untrusted = _capture_financial_evidence(
        request=request,
        occurred_at=payload.settled_at,
        shift=shift,
        action_started_at=handoff.started_at,
        server_now=completed_at,
    )
    if existing is not None:
        if (
            existing.amount_minor != payload.expected_amount_minor
            or existing.method != "cash"
            or existing.cash_handoff_id != handoff.id
            or existing.external_reference is not None
            or existing.evidence_occurred_at is None
            or abs(
                (existing.evidence_occurred_at - evidence_occurred_at).total_seconds()
            )
            > 1
            or existing.evidence_time_untrusted != evidence_time_untrusted
            or existing.action_takeover_confirmed != takeover_confirmed
            or existing.action_takeover_reason != takeover_reason
        ):
            raise BusinessRuleError(
                "This cash handover was already recorded with different evidence. "
                "Open the recovery task; do not hand over cash again."
            )
        completed_actor = await session.get(User, existing.completed_by)
        settled_actor = (
            await session.get(User, settlement.settled_by) if settlement else None
        )
        response = _refund_to_read(
            refund,
            membership_id=sub.id,
            handoff=handoff,
            completion=existing,
            settlement=settlement,
            customer=customer,
            tier=tier,
            payment=payment,
            completed_by_name=completed_actor.name if completed_actor else None,
            settled_by_name=settled_actor.name if settled_actor else None,
        )
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response
    other_reserved = await _unresolved_cash_refund_total(
        session,
        shift_id=shift.id,
        exclude_refund_id=refund.id,
    )
    available_drawer = int(shift.expected_minor or 0) - other_reserved
    if int(refund.amount_minor) > available_drawer:
        raise BusinessRuleError(
            "This drawer no longer has enough expected cash after other accepted "
            "refunds. Resolve those obligations or use the correct shift before "
            "handing over cash."
        )
    completion = MembershipRefundCompletion(
        id=uuid4(),
        company_id=tenant.company_id,
        refund_id=refund.id,
        cash_handoff_id=handoff.id,
        provider_action_id=None,
        legacy_attempt_resolution_id=None,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        method="cash",
        amount_minor=refund.amount_minor,
        completed_at=completed_at,
        completed_by=tenant.user_id,
        idempotency_key=idempotency_key,
        external_reference=None,
        provider_evidence_reconciled=True,
        evidence_occurred_at=evidence_occurred_at,
        evidence_time_untrusted=evidence_time_untrusted,
        action_takeover_confirmed=takeover_confirmed,
        action_takeover_reason=takeover_reason,
    )
    session.add(completion)
    await session.flush()
    accepted_actor = await session.get(User, refund.approved_by)
    action_actor = await session.get(User, handoff.started_by)
    completed_actor = await session.get(User, completion.completed_by)
    response = _refund_to_read(
        refund,
        membership_id=sub.id,
        handoff=handoff,
        completion=completion,
        customer=customer,
        tier=tier,
        payment=payment,
        accepted_by_name=accepted_actor.name if accepted_actor else None,
        action_started_by_name=action_actor.name if action_actor else None,
        completed_by_name=completed_actor.name if completed_actor else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refunds/{refund_id}/settle-provider",
    response_model=MembershipRefundRead,
    status_code=status.HTTP_201_CREATED,
)
async def settle_provider_membership_refund(
    refund_id: UUID,
    payload: ProviderMembershipRefundSettlementRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundRead:
    """Persist provider payout evidence before accounting finalization."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may settle a provider membership refund."
        )
    if not payload.provider_refund_completed:
        raise BusinessRuleError(
            "Do not settle this task until the external card/UPI refund has completed."
        )
    clean_reference = payload.external_reference.strip()
    if not clean_reference:
        raise BusinessRuleError("Enter the completed provider refund reference.")
    provider_evidence_reconciled = len(clean_reference) >= 3
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Return to the terminal and shift that accepted this provider refund."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for provider membership refund settlement"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundRead.model_validate(replay["body"])

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
        operation="settling this provider membership refund",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="settle this provider membership refund",
    )
    row = (
        await session.execute(
            select(
                MembershipRefund,
                MembershipPayment,
                CustomerMembership,
                Customer,
                MembershipTier,
            )
            .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
                MembershipRefund.shift_id == shift.id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("membership refund not found for this shift")
    refund, payment, sub, customer, tier = row
    if refund.method == "cash":
        raise BusinessRuleError(
            "This refund was accepted as cash. Use its cash handover workflow."
        )
    if refund.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted refund amount differs from the provider completion. "
            "Do not submit another provider refund; refresh and review the receipt."
        )
    provider_action = (
        await session.execute(
            select(MembershipRefundProviderAction)
            .where(MembershipRefundProviderAction.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if provider_action is None:
        raise BusinessRuleError(
            "Start the server-confirmed provider refund step before issuing money."
        )
    actor = await session.get(User, provider_action.started_by)
    actor_name = actor.name if actor else str(provider_action.started_by)
    takeover_confirmed, takeover_reason = _verified_takeover_attestation(
        started_by=provider_action.started_by,
        current_user_id=tenant.user_id,
        confirmed=payload.action_takeover_confirmed,
        reason=payload.action_takeover_reason,
        started_by_name=actor_name,
        action_label="provider refund",
    )
    existing = (
        await session.execute(
            select(MembershipRefundCompletion)
            .where(MembershipRefundCompletion.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    settlement = (
        await session.execute(
            select(MembershipRefundSettlement).where(
                MembershipRefundSettlement.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipRefundResolution)
            .where(MembershipRefundResolution.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if resolution is not None:
        raise BusinessRuleError(
            "This provider refund was withdrawn. Do not perform or record another payout."
        )
    completed_at = datetime.now(timezone.utc)
    evidence_occurred_at, evidence_time_untrusted = _capture_financial_evidence(
        request=request,
        occurred_at=payload.settled_at,
        shift=shift,
        action_started_at=provider_action.started_at,
        server_now=completed_at,
    )
    if existing is not None:
        if (
            existing.amount_minor != payload.expected_amount_minor
            or existing.method != refund.method
            or existing.provider_action_id != provider_action.id
            or existing.external_reference != clean_reference
            or existing.provider_evidence_reconciled
            != provider_evidence_reconciled
            or existing.evidence_occurred_at is None
            or abs(
                (existing.evidence_occurred_at - evidence_occurred_at).total_seconds()
            )
            > 1
            or existing.evidence_time_untrusted != evidence_time_untrusted
            or existing.action_takeover_confirmed != takeover_confirmed
            or existing.action_takeover_reason != takeover_reason
        ):
            raise BusinessRuleError(
                "This provider payout was already recorded with different evidence. "
                "Open the recovery task; do not perform another payout."
            )
        completed_actor = await session.get(User, existing.completed_by)
        settled_actor = (
            await session.get(User, settlement.settled_by) if settlement else None
        )
        response = _refund_to_read(
            refund,
            membership_id=sub.id,
            provider_action=provider_action,
            completion=existing,
            settlement=settlement,
            customer=customer,
            tier=tier,
            payment=payment,
            completed_by_name=completed_actor.name if completed_actor else None,
            settled_by_name=settled_actor.name if settled_actor else None,
        )
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    provider_lock_key = (
        f"membership-refund-provider:{tenant.company_id}:"
        f"{refund.method}:{clean_reference}"
    )
    await session.execute(
        select(
            func.pg_advisory_xact_lock(
                func.hashtextextended(provider_lock_key, 0)
            )
        )
    )
    duplicate_reference = (
        await session.execute(
            select(MembershipRefundCompletion.id).where(
                MembershipRefundCompletion.company_id == tenant.company_id,
                MembershipRefundCompletion.method == refund.method,
                MembershipRefundCompletion.external_reference == clean_reference,
            )
        )
    ).scalar_one_or_none()
    if duplicate_reference is not None:
        raise BusinessRuleError(
            "This provider reference is already attached to another membership refund. "
            "Review that receipt instead of paying again."
        )
    completion = MembershipRefundCompletion(
        id=uuid4(),
        company_id=tenant.company_id,
        refund_id=refund.id,
        cash_handoff_id=None,
        provider_action_id=provider_action.id,
        legacy_attempt_resolution_id=None,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        method=refund.method,
        amount_minor=refund.amount_minor,
        completed_at=completed_at,
        completed_by=tenant.user_id,
        idempotency_key=idempotency_key,
        external_reference=clean_reference,
        provider_evidence_reconciled=provider_evidence_reconciled,
        evidence_occurred_at=evidence_occurred_at,
        evidence_time_untrusted=evidence_time_untrusted,
        action_takeover_confirmed=takeover_confirmed,
        action_takeover_reason=takeover_reason,
    )
    session.add(completion)
    await session.flush()
    accepted_actor = await session.get(User, refund.approved_by)
    action_actor = await session.get(User, provider_action.started_by)
    completed_actor = await session.get(User, completion.completed_by)
    response = _refund_to_read(
        refund,
        membership_id=sub.id,
        provider_action=provider_action,
        completion=completion,
        customer=customer,
        tier=tier,
        payment=payment,
        accepted_by_name=accepted_actor.name if accepted_actor else None,
        action_started_by_name=action_actor.name if action_actor else None,
        completed_by_name=completed_actor.name if completed_actor else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refunds/{refund_id}/finalize",
    response_model=MembershipRefundRead,
    status_code=status.HTTP_201_CREATED,
)
async def finalize_membership_refund(
    refund_id: UUID,
    payload: MembershipRefundFinalizationRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundRead:
    """Post a previously committed payout completion exactly once."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may finalize a membership refund."
        )
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Return to the terminal and shift that owns this completed payout."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for membership refund finalization"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundRead.model_validate(replay["body"])

    preflight = (
        await session.execute(
            select(MembershipRefund.shift_id).where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
            )
        )
    ).scalar_one_or_none()
    if preflight is None:
        raise NotFoundError("membership refund not found")
    if preflight != payload.shift_id:
        raise BusinessRuleError(
            "This completed payout belongs to a different shift. Return to its "
            "terminal; do not create a second payout."
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
        operation="finalizing this completed membership refund",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="finalize this completed membership refund",
    )
    row = (
        await session.execute(
            select(
                MembershipRefund,
                MembershipPayment,
                CustomerMembership,
                Customer,
                MembershipTier,
            )
            .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
                MembershipRefund.shift_id == shift.id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("membership refund not found for this shift")
    refund, payment, sub, customer, tier = row
    if refund.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The completed payout amount changed. Refresh the recovery task before "
            "posting its accounting."
        )
    completion = (
        await session.execute(
            select(MembershipRefundCompletion)
            .where(MembershipRefundCompletion.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if completion is None:
        raise BusinessRuleError(
            "No committed payout completion exists. Begin and complete the cash/provider "
            "step before posting accounting."
        )
    existing = (
        await session.execute(
            select(MembershipRefundSettlement)
            .where(MembershipRefundSettlement.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    resolution = (
        await session.execute(
            select(MembershipRefundResolution).where(
                MembershipRefundResolution.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    if resolution is not None:
        raise BusinessRuleError(
            "This payout was already resolved as reversed/returned. Do not post it again."
        )

    handoff = (
        await session.execute(
            select(MembershipRefundCashHandoff).where(
                MembershipRefundCashHandoff.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    provider_action = (
        await session.execute(
            select(MembershipRefundProviderAction).where(
                MembershipRefundProviderAction.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    if existing is None:
        posted_at = datetime.now(timezone.utc)
        receipt_no, receipt_fiscal_year = await _allocate_membership_receipt(
            session,
            company_id=tenant.company_id,
            branch_id=completion.branch_id,
            occurred_at=posted_at,
            refund=True,
        )
        before_spend = int(customer.total_spent_minor or 0)
        spend_reconciled = before_spend >= int(refund.amount_minor)
        existing = MembershipRefundSettlement(
            id=uuid4(),
            company_id=tenant.company_id,
            refund_id=refund.id,
            payment_id=payment.id,
            completion_id=completion.id,
            branch_id=completion.branch_id,
            terminal_id=completion.terminal_id,
            shift_id=completion.shift_id,
            method=completion.method,
            amount_minor=completion.amount_minor,
            settled_at=posted_at,
            settled_by=completion.completed_by,
            idempotency_key=idempotency_key,
            receipt_no=receipt_no,
            receipt_fiscal_year=receipt_fiscal_year,
            receipt_issued_at=posted_at,
            external_ref=completion.external_reference,
            provider_evidence_reconciled=completion.provider_evidence_reconciled,
            evidence_occurred_at=completion.evidence_occurred_at,
            evidence_time_untrusted=completion.evidence_time_untrusted,
            customer_spend_reconciled=spend_reconciled,
            action_takeover_confirmed=completion.action_takeover_confirmed,
            action_takeover_reason=completion.action_takeover_reason,
        )
        session.add(existing)
        await session.flush()
        if completion.method == "cash":
            # Cash already left. Accounting must reflect that fact even if an
            # unrelated reconciliation problem means the expected drawer goes
            # below zero; rejecting here would erase financial truth.
            shift.expected_minor = int(shift.expected_minor or 0) - int(
                completion.amount_minor
            )
        if spend_reconciled:
            after_spend = before_spend - int(completion.amount_minor)
            customer.total_spent_minor = after_spend
            await session.flush()
            session.add(
                MembershipCustomerSpendApplication(
                    id=uuid4(),
                    company_id=tenant.company_id,
                    customer_id=customer.id,
                    payment_id=None,
                    refund_settlement_id=existing.id,
                    source_amount_minor=completion.amount_minor,
                    before_total_spent_minor=before_spend,
                    after_total_spent_minor=after_spend,
                    adjustment_minor=-int(completion.amount_minor),
                    applied_at=posted_at,
                    applied_by=completion.completed_by,
                    idempotency_key=f"membership-spend:{existing.id}",
                )
            )
        await session.flush()
    elif (
        existing.completion_id != completion.id
        or existing.amount_minor != payload.expected_amount_minor
        or existing.method != completion.method
        or existing.external_ref != completion.external_reference
    ):
        raise BusinessRuleError(
            "This payout was already posted with different immutable evidence. Open "
            "its receipt instead of finalizing again."
        )

    accepted_actor = await session.get(User, refund.approved_by)
    action = handoff or provider_action
    action_actor = await session.get(User, action.started_by) if action else None
    completed_actor = await session.get(User, completion.completed_by)
    settled_actor = await session.get(User, existing.settled_by)
    response = _refund_to_read(
        refund,
        membership_id=sub.id,
        handoff=handoff,
        provider_action=provider_action,
        completion=completion,
        settlement=existing,
        customer=customer,
        tier=tier,
        payment=payment,
        accepted_by_name=accepted_actor.name if accepted_actor else None,
        action_started_by_name=action_actor.name if action_actor else None,
        completed_by_name=completed_actor.name if completed_actor else None,
        settled_by_name=settled_actor.name if settled_actor else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/refunds/{refund_id}/withdraw",
    response_model=MembershipRefundRead,
    status_code=status.HTTP_201_CREATED,
)
async def withdraw_membership_refund(
    refund_id: UUID,
    payload: MembershipRefundResolutionRequest,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipRefundRead:
    """Resolve an accepted refund without inventing or deleting money."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may withdraw an unsettled membership refund."
        )
    clean_reason = payload.reason.strip()
    if len(clean_reason) < 3:
        raise BusinessRuleError("Enter why the accepted membership refund was not paid.")
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError(
            "Return to the terminal and shift that accepted this membership refund."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for membership refund withdrawal"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipRefundRead.model_validate(replay["body"])

    preflight = (
        await session.execute(
            select(MembershipRefund.shift_id).where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
            )
        )
    ).scalar_one_or_none()
    if preflight is None:
        raise NotFoundError("membership refund not found")
    if preflight != payload.shift_id:
        raise BusinessRuleError(
            "This refund belongs to a different shift. Return to its original "
            "terminal; no drawer was changed."
        )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == payload.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    if shift is None or shift.company_id != tenant.company_id:
        raise NotFoundError("membership refund shift not found")
    if shift.branch_id != tenant.branch_id or shift.terminal_id != tenant.terminal_id:
        raise BusinessRuleError(
            "Return to the branch and terminal that accepted this membership refund."
        )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="withdraw this unsettled membership refund",
    )
    row = (
        await session.execute(
            select(
                MembershipRefund,
                MembershipPayment,
                CustomerMembership,
                Customer,
                MembershipTier,
            )
            .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
            .join(
                CustomerMembership,
                CustomerMembership.id == MembershipPayment.membership_id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .where(
                MembershipRefund.id == refund_id,
                MembershipRefund.company_id == tenant.company_id,
                Customer.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).one_or_none()
    if row is None:
        raise NotFoundError("membership refund not found")
    refund, payment, sub, customer, tier = row
    if refund.amount_minor != payload.expected_amount_minor:
        raise BusinessRuleError(
            "The accepted refund amount changed. Refresh before resolving it."
        )
    settlement = (
        await session.execute(
            select(MembershipRefundSettlement)
            .where(MembershipRefundSettlement.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if settlement is not None:
        raise BusinessRuleError(
            "This refund is already settled. Money that left cannot be marked unpaid."
        )
    completion = (
        await session.execute(
            select(MembershipRefundCompletion).where(
                MembershipRefundCompletion.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    existing = (
        await session.execute(
            select(MembershipRefundResolution)
            .where(MembershipRefundResolution.refund_id == refund.id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    handoff = (
        await session.execute(
            select(MembershipRefundCashHandoff).where(
                MembershipRefundCashHandoff.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    provider_action = (
        await session.execute(
            select(MembershipRefundProviderAction).where(
                MembershipRefundProviderAction.refund_id == refund.id
            )
        )
    ).scalar_one_or_none()
    action = handoff or provider_action
    clean_reference = (
        payload.external_reference.strip() if payload.external_reference else None
    )
    verification_reference = (
        payload.provider_verification_reference.strip()
        if payload.provider_verification_reference
        else None
    )
    if existing is not None:
        if (
            existing.paid_via != refund.method
            or existing.resolution != payload.resolution
            or existing.reason != clean_reason
            or existing.external_reference != clean_reference
            or existing.action_state_verified != payload.action_state_verified
            or existing.provider_verification_status
            != payload.provider_verification_status
            or existing.provider_verification_reference != verification_reference
            or existing.cash_return_confirmed != payload.cash_return_confirmed
        ):
            raise BusinessRuleError(
                "This refund was already resolved with different evidence. Open its "
                "audit record; it cannot be overwritten."
            )
        accepted_actor = await session.get(User, refund.approved_by)
        action_actor = await session.get(User, action.started_by) if action else None
        resolved_actor = await session.get(User, existing.resolved_by)
        response = _refund_to_read(
            refund,
            membership_id=sub.id,
            handoff=handoff,
            provider_action=provider_action,
            completion=completion,
            resolution=existing,
            entitlement_restored=sub.revoked_at is None,
            customer=customer,
            tier=tier,
            payment=payment,
            accepted_by_name=accepted_actor.name if accepted_actor else None,
            action_started_by_name=action_actor.name if action_actor else None,
            resolved_by_name=resolved_actor.name if resolved_actor else None,
        )
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    takeover_confirmed = False
    takeover_reason = None
    provider_checked_at = None
    provider_evidence_occurred_at = None
    provider_evidence_time_untrusted = False
    provider_evidence_reconciled = True
    if action is None:
        expected_resolution = (
            "cash_not_handed_over" if refund.method == "cash" else "provider_not_completed"
        )
        if (
            payload.resolution != expected_resolution
            or payload.action_state_verified
            or clean_reference is not None
            or payload.provider_verification_status is not None
            or verification_reference is not None
            or payload.provider_evidence_occurred_at is not None
            or payload.cash_return_confirmed
            or payload.action_takeover_confirmed
            or payload.action_takeover_reason is not None
            or completion is not None
        ):
            raise BusinessRuleError(
                "No cash/provider action started. Confirm the matching not-started "
                "outcome without provider, cash-return, or takeover evidence."
            )
    else:
        actor = await session.get(User, action.started_by)
        actor_name = actor.name if actor else str(action.started_by)
        takeover_confirmed, takeover_reason = _verified_takeover_attestation(
            started_by=action.started_by,
            current_user_id=tenant.user_id,
            confirmed=payload.action_takeover_confirmed,
            reason=payload.action_takeover_reason,
            started_by_name=actor_name,
            action_label=(
                "cash refund handover"
                if refund.method == "cash"
                else "provider refund"
            ),
        )
        if not payload.action_state_verified:
            raise BusinessRuleError(
                "Verify the drawer/customer or provider result before resolving this "
                "in-progress refund. Unknown state must remain unresolved."
            )
        if refund.method == "cash":
            allowed = {"cash_not_handed_over", "cash_returned"}
            if completion is not None:
                allowed = {"cash_returned"}
            if (
                provider_action is not None
                or payload.resolution not in allowed
                or clean_reference is not None
                or payload.provider_verification_status is not None
                or verification_reference is not None
                or payload.provider_evidence_occurred_at is not None
                or payload.cash_return_confirmed
                != (payload.resolution == "cash_returned")
            ):
                raise BusinessRuleError(
                    "Verify the drawer and customer. Use cash_not_handed_over only when "
                    "cash never left; use cash_returned with explicit physical-return "
                    "confirmation after a completed payout was recovered."
                )
        else:
            if handoff is not None:
                raise BusinessRuleError(
                    "Provider refund has inconsistent cash-handoff evidence."
                )
            if payload.resolution not in {
                "provider_not_completed",
                "provider_reversed",
            }:
                raise BusinessRuleError(
                    "Use provider_not_completed or provider_reversed for this rail."
                )
            expected_status = (
                "not_completed"
                if payload.resolution == "provider_not_completed"
                else "reversed"
            )
            if (
                payload.provider_verification_status != expected_status
                or not verification_reference
                or payload.cash_return_confirmed
                or (
                    payload.resolution == "provider_not_completed"
                    and clean_reference is not None
                )
                or (
                    payload.resolution == "provider_reversed"
                    and clean_reference != verification_reference
                )
                or (completion is not None and payload.resolution != "provider_reversed")
            ):
                raise BusinessRuleError(
                    "A protected owner must verify the provider's final status and record "
                    "its nonblank verification reference. If payout completed, reverse it "
                    "externally and use the same reversal reference."
                )
            provider_checked_at = datetime.now(timezone.utc)
            evidence_input = payload.provider_evidence_occurred_at or provider_checked_at
            (
                provider_evidence_occurred_at,
                provider_evidence_time_untrusted,
            ) = _capture_financial_evidence(
                request=request,
                occurred_at=evidence_input,
                shift=shift,
                action_started_at=provider_action.started_at,
                server_now=provider_checked_at,
            )
            provider_evidence_reconciled = len(verification_reference) >= 3
    restored = sub.revoked_at is None
    now = datetime.now(timezone.utc)
    existing = MembershipRefundResolution(
        id=uuid4(),
        company_id=tenant.company_id,
        refund_id=refund.id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        paid_via=refund.method,
        resolution=payload.resolution,
        reason=clean_reason,
        external_reference=clean_reference,
        provider_evidence_reconciled=provider_evidence_reconciled,
        resolved_at=now,
        resolved_by=tenant.user_id,
        action_state_verified=payload.action_state_verified,
        provider_verification_status=payload.provider_verification_status,
        provider_verification_reference=verification_reference,
        provider_checked_at=provider_checked_at,
        provider_evidence_occurred_at=provider_evidence_occurred_at,
        provider_evidence_time_untrusted=provider_evidence_time_untrusted,
        cash_return_confirmed=payload.cash_return_confirmed,
        action_takeover_confirmed=takeover_confirmed,
        action_takeover_reason=takeover_reason,
        idempotency_key=idempotency_key,
    )
    session.add(existing)
    if sub.expires_at > now:
        overlap = (
            await session.execute(
                select(CustomerMembership.id)
                .where(
                    CustomerMembership.customer_id == customer.id,
                    CustomerMembership.id != sub.id,
                    CustomerMembership.revoked_at.is_(None),
                    CustomerMembership.starts_at < sub.expires_at,
                    CustomerMembership.expires_at > now,
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if overlap is None:
            sub.revoked_at = None
            restored = True
    await session.flush()
    accepted_actor = await session.get(User, refund.approved_by)
    action_actor = await session.get(User, action.started_by) if action else None
    resolved_actor = await session.get(User, existing.resolved_by)
    response = _refund_to_read(
        refund,
        membership_id=sub.id,
        handoff=handoff,
        provider_action=provider_action,
        resolution=existing,
        entitlement_restored=restored,
        customer=customer,
        tier=tier,
        payment=payment,
        accepted_by_name=accepted_actor.name if accepted_actor else None,
        action_started_by_name=action_actor.name if action_actor else None,
        resolved_by_name=resolved_actor.name if resolved_actor else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get("/refunds", response_model=list[MembershipRefundRead])
async def list_membership_refund_tasks(
    session: SessionDep,
    response: Response,
    tenant: TenantContext = Depends(requires("admin.system")),
    unresolved: bool = True,
    shift_id: UUID | None = None,
    refund_id: UUID | None = None,
    client_action_id: str | None = None,
    limit: int = Query(default=100, ge=1, le=200),
) -> list[MembershipRefundRead]:
    """Recover unsettled money tasks and LTV warnings after restart/reinstall."""
    if not tenant.protected_access:
        raise ForbiddenError("Only a protected owner may view membership refund tasks.")
    if tenant.terminal_id is None or tenant.branch_id is None:
        raise BusinessRuleError("Select this tablet's branch and terminal first.")
    stmt = (
        select(
            MembershipRefund,
            MembershipPayment,
            CustomerMembership,
            Customer,
            MembershipTier,
            MembershipRefundCashHandoff,
            MembershipRefundProviderAction,
            MembershipRefundCompletion,
            MembershipRefundSettlement,
            MembershipRefundResolution,
        )
        .join(MembershipPayment, MembershipPayment.id == MembershipRefund.payment_id)
        .join(
            CustomerMembership,
            CustomerMembership.id == MembershipPayment.membership_id,
        )
        .join(Customer, Customer.id == CustomerMembership.customer_id)
        .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
        .outerjoin(
            MembershipRefundCashHandoff,
            MembershipRefundCashHandoff.refund_id == MembershipRefund.id,
        )
        .outerjoin(
            MembershipRefundProviderAction,
            MembershipRefundProviderAction.refund_id == MembershipRefund.id,
        )
        .outerjoin(
            MembershipRefundCompletion,
            MembershipRefundCompletion.refund_id == MembershipRefund.id,
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
            MembershipRefund.company_id == tenant.company_id,
            MembershipRefund.branch_id == tenant.branch_id,
            MembershipRefund.terminal_id == tenant.terminal_id,
        )
        .order_by(MembershipRefund.accepted_at.desc())
        .limit(limit + 1)
    )
    if unresolved:
        stmt = stmt.where(
            or_(
                (
                    MembershipRefundSettlement.id.is_(None)
                    & MembershipRefundResolution.id.is_(None)
                ),
                MembershipRefundSettlement.customer_spend_reconciled.is_(False),
            )
        )
    if shift_id is not None:
        stmt = stmt.where(MembershipRefund.shift_id == shift_id)
    if refund_id is not None:
        stmt = stmt.where(MembershipRefund.id == refund_id)
    if client_action_id is not None:
        clean_action_id = client_action_id.strip()
        if not clean_action_id:
            raise BusinessRuleError("Membership refund action ID cannot be blank.")
        stmt = stmt.where(MembershipRefund.idempotency_key == clean_action_id)
    rows = (await session.execute(stmt)).all()
    truncated = len(rows) > limit
    rows = rows[:limit]
    response.headers["X-Result-Truncated"] = str(truncated).lower()
    response.headers["X-Result-Limit"] = str(limit)
    result: list[MembershipRefundRead] = []
    for (
        refund,
        payment,
        membership,
        customer,
        tier,
        handoff,
        provider_action,
        completion,
        settlement,
        resolution,
    ) in rows:
        accepted_actor = await session.get(User, refund.approved_by)
        action = handoff or provider_action
        action_actor = await session.get(User, action.started_by) if action else None
        settled_actor = (
            await session.get(User, settlement.settled_by) if settlement else None
        )
        completed_actor = (
            await session.get(User, completion.completed_by) if completion else None
        )
        resolved_actor = (
            await session.get(User, resolution.resolved_by) if resolution else None
        )
        result.append(
            _refund_to_read(
                refund,
                membership_id=membership.id,
                handoff=handoff,
                provider_action=provider_action,
                completion=completion,
                settlement=settlement,
                resolution=resolution,
                entitlement_restored=(
                    resolution is not None and membership.revoked_at is None
                ),
                customer=customer,
                tier=tier,
                payment=payment,
                accepted_by_name=accepted_actor.name if accepted_actor else None,
                action_started_by_name=action_actor.name if action_actor else None,
                completed_by_name=completed_actor.name if completed_actor else None,
                settled_by_name=settled_actor.name if settled_actor else None,
                resolved_by_name=resolved_actor.name if resolved_actor else None,
            )
        )
    return result


_EVIDENCE_TARGETS = {
    "payment": (MembershipPayment, "payment_id", "evidence_time_untrusted"),
    "refund_settlement": (
        MembershipRefundSettlement,
        "refund_settlement_id",
        "evidence_time_untrusted",
    ),
    "payment_completion": (
        MembershipPaymentCompletion,
        "payment_completion_id",
        "evidence_time_untrusted",
    ),
    "refund_completion": (
        MembershipRefundCompletion,
        "refund_completion_id",
        "evidence_time_untrusted",
    ),
    "payment_request_resolution": (
        MembershipPaymentRequestResolution,
        "payment_request_resolution_id",
        "provider_evidence_time_untrusted",
    ),
    "refund_resolution": (
        MembershipRefundResolution,
        "refund_resolution_id",
        "provider_evidence_time_untrusted",
    ),
    "payment_attempt_resolution": (
        MembershipPaymentAttemptResolution,
        "payment_attempt_resolution_id",
        "evidence_time_untrusted",
    ),
    "refund_attempt_resolution": (
        MembershipRefundAttemptResolution,
        "refund_attempt_resolution_id",
        "evidence_time_untrusted",
    ),
}


def _evidence_reconciliation_to_read(
    row: MembershipEvidenceReconciliation,
    *,
    actor_name: str | None,
) -> MembershipEvidenceReconciliationRead:
    target_type = ""
    target_id = None
    for candidate, (_model, column_name, _time_column) in _EVIDENCE_TARGETS.items():
        value = getattr(row, column_name)
        if value is not None:
            target_type, target_id = candidate, value
            break
    if target_id is None:  # DB check prevents this; keep corruption visible.
        raise BusinessRuleError("Membership evidence reconciliation has no source fact.")
    return MembershipEvidenceReconciliationRead(
        id=row.id,
        target_type=target_type,
        target_id=target_id,
        evidence_kind=row.evidence_kind,
        proof_reference=row.proof_reference,
        verified_occurred_at=row.verified_occurred_at,
        reason=row.reason,
        reconciled_at=row.reconciled_at,
        reconciled_by=row.reconciled_by,
        reconciled_by_name=actor_name,
    )


@router.post(
    "/evidence-reconciliations",
    response_model=MembershipEvidenceReconciliationRead,
    status_code=status.HTTP_201_CREATED,
)
async def reconcile_membership_evidence(
    payload: MembershipEvidenceReconciliationCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> MembershipEvidenceReconciliationRead:
    """Append owner verification without rewriting the original money fact."""
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may reconcile membership payment evidence."
        )
    proof_reference = payload.proof_reference.strip()
    reason = payload.reason.strip()
    if len(proof_reference) < 3 or len(reason) < 3:
        raise BusinessRuleError(
            "Enter a meaningful proof reference and reason (at least 3 characters each)."
        )
    verified_occurred_at = payload.verified_occurred_at
    if payload.evidence_kind == "captured_time":
        if verified_occurred_at is None or verified_occurred_at.tzinfo is None:
            raise BusinessRuleError(
                "Verified captured time must include a timezone."
            )
        verified_occurred_at = verified_occurred_at.astimezone(timezone.utc)
    elif verified_occurred_at is not None:
        raise BusinessRuleError(
            "Provider-reference reconciliation must not replace the original timestamp."
        )

    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for membership evidence reconciliation"
        )
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return MembershipEvidenceReconciliationRead.model_validate(replay["body"])

    model, source_column, time_column = _EVIDENCE_TARGETS[payload.target_type]
    source = (
        await session.execute(
            select(model)
            .where(model.id == payload.target_id, model.company_id == tenant.company_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if source is None:
        raise NotFoundError("membership evidence source not found")
    provider_pending = not bool(source.provider_evidence_reconciled)
    time_pending = bool(getattr(source, time_column))
    if payload.evidence_kind == "provider_reference" and not provider_pending:
        raise BusinessRuleError(
            "This provider evidence is already trusted or reconciled; no new fact was added."
        )
    if payload.evidence_kind == "captured_time" and not time_pending:
        raise BusinessRuleError(
            "This captured timestamp is already trusted or reconciled; no new fact was added."
        )
    source_attr = getattr(MembershipEvidenceReconciliation, source_column)
    existing = (
        await session.execute(
            select(MembershipEvidenceReconciliation)
            .where(
                MembershipEvidenceReconciliation.company_id == tenant.company_id,
                source_attr == payload.target_id,
                MembershipEvidenceReconciliation.evidence_kind == payload.evidence_kind,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if existing is not None:
        if (
            existing.proof_reference != proof_reference
            or existing.reason != reason
            or existing.verified_occurred_at != verified_occurred_at
        ):
            raise BusinessRuleError(
                "This evidence issue was already reconciled with a different immutable proof."
            )
        actor = await session.get(User, existing.reconciled_by)
        response = _evidence_reconciliation_to_read(
            existing, actor_name=actor.name if actor else None
        )
    else:
        source_values = {column_name: None for _m, column_name, _t in _EVIDENCE_TARGETS.values()}
        source_values[source_column] = payload.target_id
        existing = MembershipEvidenceReconciliation(
            id=uuid4(),
            company_id=tenant.company_id,
            evidence_kind=payload.evidence_kind,
            proof_reference=proof_reference,
            verified_occurred_at=verified_occurred_at,
            reason=reason,
            reconciled_at=datetime.now(timezone.utc),
            reconciled_by=tenant.user_id,
            idempotency_key=idempotency_key,
            **source_values,
        )
        session.add(existing)
        await session.flush()
        actor = await session.get(User, tenant.user_id)
        response = _evidence_reconciliation_to_read(
            existing, actor_name=actor.name if actor else None
        )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get(
    "/evidence-reconciliations",
    response_model=list[MembershipEvidenceReconciliationRead],
)
async def list_membership_evidence_reconciliations(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
    limit: int = Query(default=100, ge=1, le=200),
) -> list[MembershipEvidenceReconciliationRead]:
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may view membership evidence reconciliations."
        )
    rows = (
        await session.execute(
            select(MembershipEvidenceReconciliation)
            .where(MembershipEvidenceReconciliation.company_id == tenant.company_id)
            .order_by(MembershipEvidenceReconciliation.reconciled_at.desc())
            .limit(limit)
        )
    ).scalars().all()
    result: list[MembershipEvidenceReconciliationRead] = []
    for row in rows:
        actor = await session.get(User, row.reconciled_by)
        result.append(
            _evidence_reconciliation_to_read(
                row, actor_name=actor.name if actor else None
            )
        )
    return result


@router.get("/customer/{customer_id}/history", response_model=list[SubscriptionRead])
async def get_customer_membership_history(
    customer_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> list[SubscriptionRead]:
    """Paid-term and correction history, including expired/revoked records."""
    customer = await session.get(Customer, customer_id)
    if not customer or customer.company_id != tenant.company_id:
        raise NotFoundError("customer not found")
    rows = (
        await session.execute(
            select(CustomerMembership)
            .where(CustomerMembership.customer_id == customer_id)
            .order_by(CustomerMembership.starts_at.desc())
            .limit(100)
        )
    ).scalars().all()
    return [await _subscription_to_read(session, row) for row in rows]


@router.get("/customer/{customer_id}", response_model=SubscriptionRead | None)
async def get_customer_subscription(
    customer_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> SubscriptionRead | None:
    """Most-recent ACTIVE subscription for a customer, or null."""
    customer = await session.get(Customer, customer_id)
    if not customer or customer.company_id != tenant.company_id or customer.deleted_at:
        raise NotFoundError("customer not found")
    now = datetime.now(timezone.utc)
    sub = (
        await session.execute(
            select(CustomerMembership)
            .where(
                CustomerMembership.customer_id == customer_id,
                CustomerMembership.starts_at <= now,
                CustomerMembership.expires_at > now,
                CustomerMembership.revoked_at.is_(None),
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
    request: Request,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> SubscriptionRead:
    if not tenant.protected_access:
        raise ForbiddenError(
            "Only a protected owner may cancel membership auto-renewal."
        )
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for membership cancellation")
    idempotency_key, idempotency_hash = str(key), str(request_hash)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=idempotency_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return SubscriptionRead.model_validate(replay["body"])
    sub = (
        await session.execute(
            select(CustomerMembership)
            .where(CustomerMembership.id == subscription_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not sub:
        raise NotFoundError("subscription not found")
    customer = await session.get(Customer, sub.customer_id)
    if not customer or customer.company_id != tenant.company_id or customer.deleted_at:
        raise NotFoundError("subscription not found")
    # Desired-state convergence: a lost success response retried from another
    # device/key is still success because renewal cancellation has no second
    # money effect. Manual prepaid terms are already non-renewing.
    if sub.auto_renew:
        sub.cancelled_at = sub.cancelled_at or datetime.now(timezone.utc)
        sub.auto_renew = False
        await session.flush()
    response = await _subscription_to_read(session, sub)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response
