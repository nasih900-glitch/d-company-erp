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

from datetime import date, datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
    event,
    inspect,
)
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
        PG_UUID(as_uuid=True),
        ForeignKey("customers.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    tier_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_tiers.id", ondelete="RESTRICT"),
        nullable=False,
    )
    billing_cycle: Mapped[str] = mapped_column(
        String(10), nullable=False, default="monthly"
    )  # monthly|annual
    starts_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    # Financial correction/revocation ends benefits immediately without
    # rewriting the contractual expiry date of the original paid term.
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True)
    auto_renew: Mapped[bool] = mapped_column(default=True, nullable=False)
    razorpay_subscription_id: Mapped[str | None] = mapped_column(
        String(50)
    )  # set when Razorpay is wired
    amount_paid_minor: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    # Current-period compatibility counters, anchored below. The reservation
    # ledger is authoritative across historical periods.
    gaming_minutes_used_this_week: Mapped[int] = mapped_column(Integer, default=0)
    hookah_used_this_month: Mapped[int] = mapped_column(Integer, default=0)
    gaming_usage_week_start: Mapped[date | None] = mapped_column(Date)
    hookah_usage_month_start: Mapped[date | None] = mapped_column(Date)
    notes: Mapped[str | None] = mapped_column(String(500))


class MembershipPaymentRequest(Base, TimestampMixin, TenantMixin):
    """Server-visible reservation created before staff collect membership money.

    This request has zero accounting effect.  It makes the obligation visible
    across reinstall/restart/other terminals and blocks its exact shift until
    either an immutable ``MembershipPayment`` or request resolution exists.
    """

    __tablename__ = "membership_payment_requests"
    __table_args__ = (
        CheckConstraint(
            "amount_minor > 0",
            name="ck_membership_payment_request_positive_amount",
        ),
        CheckConstraint(
            "billing_cycle = 'monthly'",
            name="ck_membership_payment_request_monthly_only",
        ),
        CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_payment_request_method",
        ),
        UniqueConstraint(
            "company_id",
            "client_action_id",
            name="uq_membership_payment_request_client_action",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_request_idempotency",
        ),
        Index("ix_membership_payment_request_shift", "shift_id"),
        Index(
            "ix_membership_payment_request_customer_accepted",
            "customer_id",
            "accepted_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customers.id", ondelete="RESTRICT"),
        nullable=False,
    )
    tier_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_tiers.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    billing_cycle: Mapped[str] = mapped_column(String(10), nullable=False)
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    customer_name_snapshot: Mapped[str | None] = mapped_column(String(100))
    customer_phone_snapshot: Mapped[str] = mapped_column(String(20), nullable=False)
    tier_code_snapshot: Mapped[str] = mapped_column(String(20), nullable=False)
    tier_name_snapshot: Mapped[str] = mapped_column(String(100), nullable=False)
    accepted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    prepared_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    client_action_id: Mapped[str] = mapped_column(String(160), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipPaymentRequest, "before_update")
def _guard_membership_payment_request_update(
    _mapper, _connection, row: MembershipPaymentRequest
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError("membership payment request is immutable: " + ", ".join(changed))


class MembershipPayment(Base, TimestampMixin, TenantMixin):
    """Immutable settlement evidence for one paid membership term.

    ``CustomerMembership.amount_paid_minor`` is the entitlement's price
    snapshot.  This row is the financial fact: which branch/terminal/shift
    collected it, by which rail, when, and under which idempotent action.
    Keeping that evidence separate prevents a membership allowance from being
    mistaken for proof that money was actually received.

    Ambiguous legacy entitlements deliberately have no payment row.  Every
    financial fact in this table therefore has complete provenance.
    """

    __tablename__ = "membership_payments"
    __table_args__ = (
        CheckConstraint(
            "amount_minor > 0",
            name="ck_membership_payment_positive_amount",
        ),
        CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_payment_method",
        ),
        UniqueConstraint(
            "membership_id",
            name="uq_membership_payment_membership",
        ),
        UniqueConstraint(
            "request_id",
            name="uq_membership_payment_request",
        ),
        UniqueConstraint(
            "completion_id",
            name="uq_membership_payment_completion",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_company_idempotency",
        ),
        UniqueConstraint(
            "company_id",
            "receipt_no",
            name="uq_membership_payment_company_receipt",
        ),
        UniqueConstraint(
            "company_id",
            "method",
            "external_reference",
            name="uq_membership_payment_provider_reference",
        ),
        CheckConstraint(
            "request_id IS NOT NULL AND completion_id IS NOT NULL",
            name="ck_membership_payment_workflow_linkage",
        ),
        CheckConstraint(
            "request_id IS NULL OR "
            "(method = 'cash' AND external_reference IS NULL) OR "
            "(method <> 'cash' AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_payment_request_evidence",
        ),
        CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_payment_action_takeover",
        ),
        Index(
            "ix_membership_payment_company_paid_at",
            "company_id",
            "paid_at",
        ),
        Index(
            "ix_membership_payment_shift",
            "shift_id",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    membership_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customer_memberships.id", ondelete="RESTRICT"),
        nullable=False,
    )
    # Physical NULLability is retained only so immutable pre-0035 payments can
    # still be read. Migration 0035 installs a NOT VALID check that rejects NULL
    # linkage on every new or subsequently touched row.
    request_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
        nullable=True,
    )
    completion_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_completions.id", ondelete="RESTRICT"),
        nullable=True,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("terminals.id", ondelete="RESTRICT"),
        nullable=False,
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("shifts.id", ondelete="RESTRICT"),
        nullable=False,
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    paid_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    created_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    # Sequential, customer-facing proof of collection. This intentionally uses
    # the existing fiscal-year InvoiceCounter allocator with the dedicated
    # ``membership`` series; the UUID remains the internal primary key only.
    receipt_no: Mapped[str] = mapped_column(String(32), nullable=False)
    receipt_fiscal_year: Mapped[str] = mapped_column(String(7), nullable=False)
    receipt_issued_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    external_reference: Mapped[str | None] = mapped_column(String(200))
    # Some providers issue valid one- or two-character opaque references. A
    # short value is retained after money moves and flagged for owner review.
    provider_evidence_reconciled: Mapped[bool] = mapped_column(
        default=True, nullable=False
    )
    # Accounting uses server ``paid_at``. The client/provider time is retained
    # only as evidence and can be flagged without rejecting money that moved.
    evidence_occurred_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    evidence_time_untrusted: Mapped[bool] = mapped_column(default=False, nullable=False)
    # True is accepted only when a matching append-only accumulator application
    # exists in the same transaction. Legacy 0033 rows intentionally remain
    # false until an owner performs a normalized reconciliation.
    customer_spend_reconciled: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    # When the owner who began the physical/provider action is unavailable, a
    # different protected owner may finish it only after recording an explicit
    # verification attestation. The immutable settlement is the takeover fact.
    action_takeover_confirmed: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    action_takeover_reason: Mapped[str | None] = mapped_column(String(500))
    note: Mapped[str | None] = mapped_column(String(500))


@event.listens_for(MembershipPayment, "before_update")
def _guard_membership_payment_update(
    _mapper, _connection, row: MembershipPayment
) -> None:
    """A collected membership payment is append-only."""
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership payment is immutable: " + ", ".join(changed)
        )


class MembershipPaymentCashCollection(Base, TimestampMixin, TenantMixin):
    """Append-only acknowledgment made immediately before accepting cash."""

    __tablename__ = "membership_payment_cash_collections"
    __table_args__ = (
        UniqueConstraint(
            "request_id",
            name="uq_membership_payment_cash_collection_request",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_cash_collection_idempotency",
        ),
        Index("ix_membership_payment_cash_collection_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    started_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipPaymentCashCollection, "before_update")
def _guard_membership_payment_cash_collection_update(
    _mapper, _connection, row: MembershipPaymentCashCollection
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership cash-collection acknowledgment is immutable: "
            + ", ".join(changed)
        )


class MembershipPaymentProviderAction(Base, TimestampMixin, TenantMixin):
    """Append-only acknowledgment made before touching an external payment rail."""

    __tablename__ = "membership_payment_provider_actions"
    __table_args__ = (
        CheckConstraint(
            "method IN ('card', 'upi', 'razorpay')",
            name="ck_membership_payment_provider_action_method",
        ),
        UniqueConstraint(
            "request_id",
            name="uq_membership_payment_provider_action_request",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_provider_action_idempotency",
        ),
        Index("ix_membership_payment_provider_action_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    started_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipPaymentProviderAction, "before_update")
def _guard_membership_payment_provider_action_update(
    _mapper, _connection, row: MembershipPaymentProviderAction
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership provider-payment acknowledgment is immutable: "
            + ", ".join(changed)
        )


class MembershipPaymentCompletion(Base, TimestampMixin, TenantMixin):
    """Durable proof that customer value moved before accounting finalization.

    This fact is committed by the completion endpoint and has no drawer,
    entitlement, ledger, receipt, or LTV effect.  A later idempotent finalizer
    consumes it to create ``MembershipPayment``.  That split preserves the
    only evidence of money received if receipt allocation or accounting fails.
    """

    __tablename__ = "membership_payment_completions"
    __table_args__ = (
        CheckConstraint("amount_minor > 0", name="ck_membership_payment_completion_positive"),
        CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_payment_completion_method",
        ),
        CheckConstraint(
            "(cash_collection_id IS NOT NULL AND provider_action_id IS NULL "
            "AND method = 'cash' AND external_reference IS NULL) OR "
            "(cash_collection_id IS NULL AND provider_action_id IS NOT NULL "
            "AND method <> 'cash' AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_payment_completion_action",
        ),
        CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_payment_completion_takeover",
        ),
        UniqueConstraint("request_id", name="uq_membership_payment_completion_request"),
        UniqueConstraint(
            "cash_collection_id", name="uq_membership_payment_completion_cash_action"
        ),
        UniqueConstraint(
            "provider_action_id", name="uq_membership_payment_completion_provider_action"
        ),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_payment_completion_idempotency",
        ),
        UniqueConstraint(
            "company_id", "method", "external_reference",
            name="uq_membership_payment_completion_provider_reference",
        ),
        Index("ix_membership_payment_completion_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    cash_collection_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_cash_collections.id", ondelete="RESTRICT"),
    )
    provider_action_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_provider_actions.id", ondelete="RESTRICT"),
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    completed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    completed_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    external_reference: Mapped[str | None] = mapped_column(String(200))
    provider_evidence_reconciled: Mapped[bool] = mapped_column(default=True, nullable=False)
    evidence_occurred_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    evidence_time_untrusted: Mapped[bool] = mapped_column(default=False, nullable=False)
    action_takeover_confirmed: Mapped[bool] = mapped_column(default=False, nullable=False)
    action_takeover_reason: Mapped[str | None] = mapped_column(String(500))
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipPaymentCompletion, "before_update")
def _guard_membership_payment_completion_update(
    _mapper, _connection, row: MembershipPaymentCompletion
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError("membership payment completion is immutable: " + ", ".join(changed))


class MembershipPaymentRequestResolution(Base, TimestampMixin, TenantMixin):
    """Append-only evidence that an accepted payment request was withdrawn."""

    __tablename__ = "membership_payment_request_resolutions"
    __table_args__ = (
        CheckConstraint(
            "(paid_via = 'cash' AND resolution IN "
            "('payment_not_collected', 'cash_not_collected', 'cash_returned') "
            "AND external_reference IS NULL) OR "
            "(paid_via <> 'cash' AND resolution IN "
            "('payment_not_collected', 'provider_not_completed') "
            "AND external_reference IS NULL) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_reversed' "
            "AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_payment_request_resolution_evidence",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_payment_request_resolution_reason",
        ),
        CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_payment_request_resolution_takeover",
        ),
        UniqueConstraint(
            "request_id",
            name="uq_membership_payment_request_resolution_request",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_payment_request_resolution_idempotency",
        ),
        UniqueConstraint(
            "company_id",
            "paid_via",
            "external_reference",
            name="uq_membership_payment_request_resolution_provider_reference",
        ),
        Index("ix_membership_payment_request_resolution_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    request_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_requests.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    paid_via: Mapped[str] = mapped_column(String(20), nullable=False)
    resolution: Mapped[str] = mapped_column(String(40), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    external_reference: Mapped[str | None] = mapped_column(String(200))
    provider_evidence_reconciled: Mapped[bool] = mapped_column(
        default=True, nullable=False
    )
    resolved_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    resolved_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    # Required by the DB trigger whenever a physical/provider action had
    # started. False means the outcome is still unknown and cannot unblock the
    # shift merely because somebody typed a reason.
    action_state_verified: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    # A provider action cannot be abandoned using a boolean. These immutable
    # fields record what was checked, when the server accepted that check, and
    # the provider proof used to establish a terminal outcome.
    provider_verification_status: Mapped[str | None] = mapped_column(String(40))
    provider_verification_reference: Mapped[str | None] = mapped_column(String(200))
    provider_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    provider_evidence_occurred_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    provider_evidence_time_untrusted: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    # A cash-return outcome is only safe when the owner explicitly attests
    # that the notes were physically handed back.  Keeping this as a separate
    # immutable fact avoids inferring a real-world handover from free text.
    cash_return_confirmed: Mapped[bool] = mapped_column(default=False, nullable=False)
    action_takeover_confirmed: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    action_takeover_reason: Mapped[str | None] = mapped_column(String(500))
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipPaymentRequestResolution, "before_update")
def _guard_membership_payment_request_resolution_update(
    _mapper, _connection, row: MembershipPaymentRequestResolution
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership payment-request resolution is immutable: "
            + ", ".join(changed)
        )


class MembershipRefund(Base, TimestampMixin, TenantMixin):
    """Immutable acceptance of a full reversal request.

    A unique payment link makes the policy explicit: a membership payment can
    be reversed once, in full.  The separate row preserves both the original
    settlement and its correction instead of rewriting financial history.
    """

    __tablename__ = "membership_refunds"
    __table_args__ = (
        CheckConstraint(
            "amount_minor > 0",
            name="ck_membership_refund_positive_amount",
        ),
        CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_method",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_refund_reason",
        ),
        Index("ix_membership_refund_payment", "payment_id"),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_refund_company_idempotency",
        ),
        Index(
            "ix_membership_refund_company_accepted_at",
            "company_id",
            "accepted_at",
        ),
        Index("ix_membership_refund_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    payment_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payments.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("terminals.id", ondelete="RESTRICT"),
        nullable=False,
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("shifts.id", ondelete="RESTRICT"),
        nullable=False,
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    accepted_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    approved_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)


@event.listens_for(MembershipRefund, "before_update")
def _guard_membership_refund_update(
    _mapper, _connection, row: MembershipRefund
) -> None:
    """A membership reversal is append-only."""
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError("membership refund is immutable: " + ", ".join(changed))


class MembershipRefundCashHandoff(Base, TimestampMixin, TenantMixin):
    """Append-only warning state set before refund cash leaves the drawer."""

    __tablename__ = "membership_refund_cash_handoffs"
    __table_args__ = (
        UniqueConstraint("refund_id", name="uq_membership_refund_cash_handoff_refund"),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_refund_cash_handoff_idempotency",
        ),
        Index("ix_membership_refund_cash_handoff_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    started_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipRefundCashHandoff, "before_update")
def _guard_membership_refund_cash_handoff_update(
    _mapper, _connection, row: MembershipRefundCashHandoff
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership refund cash-handoff is immutable: " + ", ".join(changed)
        )


class MembershipRefundProviderAction(Base, TimestampMixin, TenantMixin):
    """Append-only state set before staff initiate a provider refund."""

    __tablename__ = "membership_refund_provider_actions"
    __table_args__ = (
        CheckConstraint(
            "method IN ('card', 'upi', 'razorpay')",
            name="ck_membership_refund_provider_action_method",
        ),
        UniqueConstraint(
            "refund_id",
            name="uq_membership_refund_provider_action_refund",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_refund_provider_action_idempotency",
        ),
        Index("ix_membership_refund_provider_action_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    started_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipRefundProviderAction, "before_update")
def _guard_membership_refund_provider_action_update(
    _mapper, _connection, row: MembershipRefundProviderAction
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership provider-refund acknowledgment is immutable: "
            + ", ".join(changed)
        )


class MembershipRefundCompletion(Base, TimestampMixin, TenantMixin):
    """Durable proof that refund value left before accounting finalization."""

    __tablename__ = "membership_refund_completions"
    __table_args__ = (
        CheckConstraint("amount_minor > 0", name="ck_membership_refund_completion_positive"),
        CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_completion_method",
        ),
        CheckConstraint(
            "((cash_handoff_id IS NOT NULL)::int + "
            "(provider_action_id IS NOT NULL)::int + "
            "(legacy_attempt_resolution_id IS NOT NULL)::int) = 1",
            name="ck_membership_refund_completion_one_action",
        ),
        CheckConstraint(
            "(method = 'cash' AND provider_action_id IS NULL "
            "AND external_reference IS NULL) OR "
            "(method <> 'cash' AND cash_handoff_id IS NULL "
            "AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_refund_completion_evidence",
        ),
        CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_refund_completion_takeover",
        ),
        UniqueConstraint("refund_id", name="uq_membership_refund_completion_refund"),
        UniqueConstraint("cash_handoff_id", name="uq_membership_refund_completion_cash_action"),
        UniqueConstraint(
            "provider_action_id", name="uq_membership_refund_completion_provider_action"
        ),
        UniqueConstraint(
            "legacy_attempt_resolution_id",
            name="uq_membership_refund_completion_legacy_attempt",
        ),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_completion_idempotency",
        ),
        UniqueConstraint(
            "company_id", "method", "external_reference",
            name="uq_membership_refund_completion_provider_reference",
        ),
        Index("ix_membership_refund_completion_shift", "shift_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
        nullable=False,
    )
    cash_handoff_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_cash_handoffs.id", ondelete="RESTRICT"),
    )
    provider_action_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_provider_actions.id", ondelete="RESTRICT"),
    )
    # Deliberately no ORM relationship; this FK is added after the legacy
    # recovery table exists in migration 0035 to break the creation-order cycle.
    legacy_attempt_resolution_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_attempt_resolutions.id", ondelete="RESTRICT"),
        nullable=True,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    completed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    completed_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    external_reference: Mapped[str | None] = mapped_column(String(200))
    provider_evidence_reconciled: Mapped[bool] = mapped_column(default=True, nullable=False)
    evidence_occurred_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    evidence_time_untrusted: Mapped[bool] = mapped_column(default=False, nullable=False)
    action_takeover_confirmed: Mapped[bool] = mapped_column(default=False, nullable=False)
    action_takeover_reason: Mapped[str | None] = mapped_column(String(500))
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipRefundCompletion, "before_update")
def _guard_membership_refund_completion_update(
    _mapper, _connection, row: MembershipRefundCompletion
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError("membership refund completion is immutable: " + ", ".join(changed))


class MembershipRefundSettlement(Base, TimestampMixin, TenantMixin):
    """Immutable proof that an accepted membership refund actually left."""

    __tablename__ = "membership_refund_settlements"
    __table_args__ = (
        CheckConstraint("amount_minor > 0", name="ck_membership_refund_settlement_positive"),
        CheckConstraint(
            "method IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_settlement_method",
        ),
        CheckConstraint(
            "(method = 'cash' AND external_ref IS NULL) OR "
            "(method <> 'cash' AND char_length(trim(external_ref)) >= 1)",
            name="ck_membership_refund_settlement_external_reference",
        ),
        CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_refund_settlement_takeover",
        ),
        UniqueConstraint("refund_id", name="uq_membership_refund_settlement_refund"),
        UniqueConstraint(
            "completion_id", name="uq_membership_refund_settlement_completion"
        ),
        UniqueConstraint("payment_id", name="uq_membership_refund_settlement_payment"),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_settlement_company_idempotency",
        ),
        UniqueConstraint(
            "company_id", "receipt_no",
            name="uq_membership_refund_settlement_company_receipt",
        ),
        UniqueConstraint(
            "company_id", "method", "external_ref",
            name="uq_membership_refund_settlement_provider_reference",
        ),
        Index(
            "ix_membership_refund_settlement_company_settled_at",
            "company_id", "settled_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
        nullable=False,
    )
    payment_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payments.id", ondelete="RESTRICT"),
        nullable=False,
    )
    completion_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_completions.id", ondelete="RESTRICT"),
        nullable=True,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False,
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False,
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False,
    )
    method: Mapped[str] = mapped_column(String(20), nullable=False)
    amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    settled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    settled_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False, index=True,
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)
    receipt_no: Mapped[str] = mapped_column(String(32), nullable=False)
    receipt_fiscal_year: Mapped[str] = mapped_column(String(7), nullable=False)
    receipt_issued_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    external_ref: Mapped[str | None] = mapped_column(String(200))
    provider_evidence_reconciled: Mapped[bool] = mapped_column(
        default=True, nullable=False
    )
    evidence_occurred_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    evidence_time_untrusted: Mapped[bool] = mapped_column(default=False, nullable=False)
    customer_spend_reconciled: Mapped[bool] = mapped_column(default=False, nullable=False)
    action_takeover_confirmed: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    action_takeover_reason: Mapped[str | None] = mapped_column(String(500))


@event.listens_for(MembershipRefundSettlement, "before_update")
def _guard_membership_refund_settlement_update(
    _mapper, _connection, row: MembershipRefundSettlement
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError("membership refund settlement is immutable: " + ", ".join(changed))


class MembershipRefundResolution(Base, TimestampMixin, TenantMixin):
    """Append-only proof that an accepted cash refund was withdrawn unpaid.

    This is the safe escape hatch when the customer leaves before cash is
    handed over. It never creates a financial movement; it only resolves the
    cash obligation and, when still contractually possible, restores benefits.
    """

    __tablename__ = "membership_refund_resolutions"
    __table_args__ = (
        CheckConstraint(
            "(paid_via = 'cash' AND resolution IN "
            "('cash_not_handed_over', 'cash_returned') "
            "AND external_reference IS NULL) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_not_completed' "
            "AND external_reference IS NULL) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_reversed' "
            "AND char_length(trim(external_reference)) >= 1)",
            name="ck_membership_refund_resolution_type",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_refund_resolution_reason",
        ),
        CheckConstraint(
            "(action_takeover_confirmed = false AND action_takeover_reason IS NULL) OR "
            "(action_takeover_confirmed = true AND "
            "char_length(trim(action_takeover_reason)) >= 3)",
            name="ck_membership_refund_resolution_takeover",
        ),
        UniqueConstraint("refund_id", name="uq_membership_refund_resolution_refund"),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_resolution_company_idempotency",
        ),
        UniqueConstraint(
            "company_id",
            "paid_via",
            "external_reference",
            name="uq_membership_refund_resolution_provider_reference",
        ),
        Index(
            "ix_membership_refund_resolution_company_resolved_at",
            "company_id", "resolved_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    refund_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refunds.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False,
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False,
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False,
    )
    paid_via: Mapped[str] = mapped_column(String(20), nullable=False)
    resolution: Mapped[str] = mapped_column(String(40), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    external_reference: Mapped[str | None] = mapped_column(String(200))
    provider_evidence_reconciled: Mapped[bool] = mapped_column(
        default=True, nullable=False
    )
    resolved_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    resolved_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False,
    )
    action_state_verified: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    provider_verification_status: Mapped[str | None] = mapped_column(String(40))
    provider_verification_reference: Mapped[str | None] = mapped_column(String(200))
    provider_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    provider_evidence_occurred_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True)
    )
    provider_evidence_time_untrusted: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    cash_return_confirmed: Mapped[bool] = mapped_column(default=False, nullable=False)
    action_takeover_confirmed: Mapped[bool] = mapped_column(
        default=False, nullable=False
    )
    action_takeover_reason: Mapped[str | None] = mapped_column(String(500))
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipRefundResolution, "before_update")
def _guard_membership_refund_resolution_update(
    _mapper, _connection, row: MembershipRefundResolution
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError("membership refund resolution is immutable: " + ", ".join(changed))


class MembershipPaymentAttemptResolution(Base, TimestampMixin, TenantMixin):
    """Append-only owner attestation for a rejected membership sale attempt.

    A rejected client outbox row is not a payment and must never enter the
    drawer, ledger, reports, or customer lifetime spend.  It can still
    represent physical cash already taken (or an external payment completed)
    before the server refused the write.  This row is the durable recovery
    fact proving either that value never moved or that it was returned/reversed
    before the tablet is allowed to release the affected shift.

    It intentionally has no foreign key to ``MembershipPayment``: a valid
    resolution is allowed only when that original action created no payment.
    The route checks that invariant under the exact shift lock.
    """

    __tablename__ = "membership_payment_attempt_resolutions"
    __table_args__ = (
        CheckConstraint(
            "expected_amount_minor > 0",
            name="ck_membership_attempt_resolution_positive_amount",
        ),
        CheckConstraint(
            "paid_via IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_attempt_resolution_method",
        ),
        CheckConstraint(
            "(paid_via = 'cash' AND resolution = 'payment_not_collected' "
            "AND external_reference IS NULL AND provider_verification_status IS NULL "
            "AND provider_checked_at IS NULL AND cash_return_confirmed = false) OR "
            "(paid_via = 'cash' AND resolution = 'cash_returned' "
            "AND external_reference IS NULL AND provider_verification_status IS NULL "
            "AND provider_checked_at IS NULL AND cash_return_confirmed = true) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_not_completed' "
            "AND char_length(trim(external_reference)) >= 1 "
            "AND provider_verification_status = 'not_completed' "
            "AND provider_checked_at IS NOT NULL AND cash_return_confirmed = false) OR "
            "(paid_via <> 'cash' AND resolution = 'provider_reversed' "
            "AND char_length(trim(external_reference)) >= 1 "
            "AND provider_verification_status = 'reversed' "
            "AND provider_checked_at IS NOT NULL AND cash_return_confirmed = false)",
            name="ck_membership_attempt_resolution_evidence",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_attempt_resolution_reason",
        ),
        UniqueConstraint(
            "company_id",
            "original_client_action_id",
            name="uq_membership_attempt_resolution_original_action",
        ),
        UniqueConstraint(
            "company_id",
            "idempotency_key",
            name="uq_membership_attempt_resolution_idempotency",
        ),
        Index(
            "ix_membership_attempt_resolution_shift",
            "shift_id",
        ),
        Index(
            "ix_membership_attempt_resolution_company_resolved_at",
            "company_id",
            "resolved_at",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customers.id", ondelete="RESTRICT"),
        nullable=False,
    )
    tier_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_tiers.id", ondelete="RESTRICT"),
        nullable=False,
    )
    branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("branches.id", ondelete="RESTRICT"),
        nullable=False,
    )
    terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("terminals.id", ondelete="RESTRICT"),
        nullable=False,
    )
    shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("shifts.id", ondelete="RESTRICT"),
        nullable=False,
    )
    original_client_action_id: Mapped[str] = mapped_column(String(160), nullable=False)
    paid_via: Mapped[str] = mapped_column(String(20), nullable=False)
    expected_amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    resolution: Mapped[str] = mapped_column(String(40), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    external_reference: Mapped[str | None] = mapped_column(String(200))
    provider_verification_status: Mapped[str | None] = mapped_column(String(40))
    provider_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    provider_evidence_reconciled: Mapped[bool] = mapped_column(
        default=True, nullable=False
    )
    evidence_occurred_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    evidence_time_untrusted: Mapped[bool] = mapped_column(default=False, nullable=False)
    cash_return_confirmed: Mapped[bool] = mapped_column(default=False, nullable=False)
    resolved_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    resolved_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipPaymentAttemptResolution, "before_update")
def _guard_membership_payment_attempt_resolution_update(
    _mapper, _connection, row: MembershipPaymentAttemptResolution
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership payment-attempt resolution is immutable: " + ", ".join(changed)
        )


class MembershipRefundAttemptRecovery(Base, TimestampMixin, TenantMixin):
    """Server-visible quarantine for one pre-reservation refund outbox row.

    Registration has no accounting effect and asserts no outcome.  It exists so
    restart, reinstall, another owner, and the shift-close API all see the
    unresolved possible payout before an owner verifies what happened.
    """

    __tablename__ = "membership_refund_attempt_recoveries"
    __table_args__ = (
        CheckConstraint(
            "expected_amount_minor > 0",
            name="ck_membership_refund_attempt_recovery_positive_amount",
        ),
        CheckConstraint(
            "paid_via IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_attempt_recovery_method",
        ),
        UniqueConstraint(
            "company_id", "original_client_action_id",
            name="uq_membership_refund_attempt_recovery_original_action",
        ),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_attempt_recovery_idempotency",
        ),
        Index("ix_membership_refund_attempt_recovery_source_shift", "source_shift_id"),
        Index("ix_membership_refund_attempt_recovery_payment", "payment_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False
    )
    membership_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customer_memberships.id", ondelete="RESTRICT"),
        nullable=False,
    )
    payment_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("membership_payments.id", ondelete="RESTRICT"),
        nullable=False,
    )
    source_branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    source_terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    source_shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    original_client_action_id: Mapped[str] = mapped_column(String(160), nullable=False)
    paid_via: Mapped[str] = mapped_column(String(20), nullable=False)
    expected_amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    captured_time_untrusted: Mapped[bool] = mapped_column(default=False, nullable=False)
    registered_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    registered_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipRefundAttemptRecovery, "before_update")
def _guard_membership_refund_attempt_recovery_update(
    _mapper, _connection, row: MembershipRefundAttemptRecovery
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership refund-attempt recovery is immutable: " + ", ".join(changed)
        )


class MembershipRefundAttemptResolution(Base, TimestampMixin, TenantMixin):
    """Protected-owner resolution of one quarantined pre-0035 refund attempt.

    The original client action is unique per tenant. Non-financial outcomes
    prove that no payout remains. A completed cash/provider payout is linked to
    a newly accepted refund on an explicitly selected *open reconciliation
    shift*; the historical/closed source shift is never rewritten.
    """

    __tablename__ = "membership_refund_attempt_resolutions"
    __table_args__ = (
        CheckConstraint(
            "expected_amount_minor > 0",
            name="ck_membership_refund_attempt_positive_amount",
        ),
        CheckConstraint(
            "paid_via IN ('cash', 'card', 'upi', 'razorpay')",
            name="ck_membership_refund_attempt_method",
        ),
        CheckConstraint(
            "outcome IN ('no_payout', 'cash_not_handed_over', 'cash_handed_over', "
            "'provider_reversed', 'provider_completed')",
            name="ck_membership_refund_attempt_outcome",
        ),
        CheckConstraint(
            "(outcome = 'cash_not_handed_over' AND paid_via = 'cash' "
            "AND refund_id IS NULL AND reconciliation_shift_id IS NULL "
            "AND provider_status IS NULL AND verification_reference IS NULL "
            "AND cash_handover_confirmed = false) OR "
            "(outcome = 'cash_handed_over' AND paid_via = 'cash' "
            "AND refund_id IS NOT NULL AND reconciliation_shift_id IS NOT NULL "
            "AND provider_status IS NULL AND verification_reference IS NULL "
            "AND cash_handover_confirmed = true) OR "
            "(outcome = 'no_payout' AND paid_via <> 'cash' "
            "AND refund_id IS NULL AND reconciliation_shift_id IS NULL "
            "AND provider_status = 'not_completed' "
            "AND char_length(trim(verification_reference)) >= 1 "
            "AND cash_handover_confirmed = false) OR "
            "(outcome = 'provider_reversed' AND paid_via <> 'cash' "
            "AND refund_id IS NULL AND reconciliation_shift_id IS NULL "
            "AND provider_status = 'reversed' "
            "AND char_length(trim(verification_reference)) >= 1 "
            "AND cash_handover_confirmed = false) OR "
            "(outcome = 'provider_completed' AND paid_via <> 'cash' "
            "AND refund_id IS NOT NULL AND reconciliation_shift_id IS NOT NULL "
            "AND provider_status = 'completed' "
            "AND char_length(trim(verification_reference)) >= 1 "
            "AND cash_handover_confirmed = false)",
            name="ck_membership_refund_attempt_evidence",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_refund_attempt_reason",
        ),
        UniqueConstraint(
            "company_id", "original_client_action_id",
            name="uq_membership_refund_attempt_original_action",
        ),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_refund_attempt_idempotency",
        ),
        UniqueConstraint("refund_id", name="uq_membership_refund_attempt_refund"),
        Index("ix_membership_refund_attempt_source_shift", "source_shift_id"),
        Index("ix_membership_refund_attempt_payment", "payment_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    recovery_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_attempt_recoveries.id", ondelete="RESTRICT"),
        nullable=False,
        unique=True,
    )
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False
    )
    membership_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customer_memberships.id", ondelete="RESTRICT"),
        nullable=False,
    )
    payment_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("membership_payments.id", ondelete="RESTRICT"),
        nullable=False,
    )
    source_branch_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("branches.id", ondelete="RESTRICT"), nullable=False
    )
    source_terminal_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("terminals.id", ondelete="RESTRICT"), nullable=False
    )
    source_shift_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT"), nullable=False
    )
    reconciliation_shift_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("shifts.id", ondelete="RESTRICT")
    )
    refund_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("membership_refunds.id", ondelete="RESTRICT")
    )
    original_client_action_id: Mapped[str] = mapped_column(String(160), nullable=False)
    paid_via: Mapped[str] = mapped_column(String(20), nullable=False)
    expected_amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    outcome: Mapped[str] = mapped_column(String(40), nullable=False)
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    provider_status: Mapped[str | None] = mapped_column(String(40))
    verification_reference: Mapped[str | None] = mapped_column(String(200))
    checked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    evidence_occurred_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    evidence_time_untrusted: Mapped[bool] = mapped_column(default=False, nullable=False)
    provider_evidence_reconciled: Mapped[bool] = mapped_column(default=True, nullable=False)
    cash_handover_confirmed: Mapped[bool] = mapped_column(default=False, nullable=False)
    resolved_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    resolved_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipRefundAttemptResolution, "before_update")
def _guard_membership_refund_attempt_resolution_update(
    _mapper, _connection, row: MembershipRefundAttemptResolution
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership refund-attempt resolution is immutable: " + ", ".join(changed)
        )


class MembershipCustomerSpendApplication(Base, TimestampMixin, TenantMixin):
    """Append-only proof of a membership money fact applied to Customer LTV."""

    __tablename__ = "membership_customer_spend_applications"
    __table_args__ = (
        CheckConstraint(
            "(payment_id IS NOT NULL AND refund_settlement_id IS NULL) OR "
            "(payment_id IS NULL AND refund_settlement_id IS NOT NULL)",
            name="ck_membership_spend_application_one_source",
        ),
        CheckConstraint(
            "after_total_spent_minor >= 0",
            name="ck_membership_spend_application_nonnegative",
        ),
        CheckConstraint(
            "adjustment_minor = after_total_spent_minor - before_total_spent_minor",
            name="ck_membership_spend_application_delta",
        ),
        UniqueConstraint("payment_id", name="uq_membership_spend_application_payment"),
        UniqueConstraint(
            "refund_settlement_id", name="uq_membership_spend_application_refund"
        ),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_spend_application_idempotency",
        ),
        Index("ix_membership_spend_application_customer", "customer_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    customer_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("customers.id", ondelete="RESTRICT"), nullable=False
    )
    payment_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("membership_payments.id", ondelete="RESTRICT")
    )
    refund_settlement_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_settlements.id", ondelete="RESTRICT"),
    )
    source_amount_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    before_total_spent_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    after_total_spent_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    adjustment_minor: Mapped[int] = mapped_column(BigInteger, nullable=False)
    applied_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    applied_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipCustomerSpendApplication, "before_update")
def _guard_membership_customer_spend_application_update(
    _mapper, _connection, row: MembershipCustomerSpendApplication
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership customer-spend application is immutable: " + ", ".join(changed)
        )


class MembershipEvidenceReconciliation(Base, TimestampMixin, TenantMixin):
    """Append-only owner proof resolving one flagged membership evidence issue."""

    __tablename__ = "membership_evidence_reconciliations"
    __table_args__ = (
        CheckConstraint(
            "evidence_kind IN ('provider_reference', 'captured_time')",
            name="ck_membership_evidence_reconciliation_kind",
        ),
        CheckConstraint(
            "char_length(trim(proof_reference)) >= 3",
            name="ck_membership_evidence_reconciliation_proof",
        ),
        CheckConstraint(
            "char_length(trim(reason)) >= 3",
            name="ck_membership_evidence_reconciliation_reason",
        ),
        CheckConstraint(
            "((payment_id IS NOT NULL)::int + "
            "(refund_settlement_id IS NOT NULL)::int + "
            "(payment_completion_id IS NOT NULL)::int + "
            "(refund_completion_id IS NOT NULL)::int + "
            "(payment_request_resolution_id IS NOT NULL)::int + "
            "(refund_resolution_id IS NOT NULL)::int + "
            "(payment_attempt_resolution_id IS NOT NULL)::int + "
            "(refund_attempt_resolution_id IS NOT NULL)::int) = 1",
            name="ck_membership_evidence_reconciliation_one_source",
        ),
        UniqueConstraint(
            "company_id", "idempotency_key",
            name="uq_membership_evidence_reconciliation_idempotency",
        ),
        Index("ix_membership_evidence_reconciliation_company", "company_id"),
    )

    id: Mapped[UUID] = _uuid_pk()
    payment_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("membership_payments.id", ondelete="RESTRICT")
    )
    refund_settlement_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_settlements.id", ondelete="RESTRICT"),
    )
    payment_completion_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_completions.id", ondelete="RESTRICT"),
    )
    refund_completion_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_completions.id", ondelete="RESTRICT"),
    )
    payment_request_resolution_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_request_resolutions.id", ondelete="RESTRICT"),
    )
    refund_resolution_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_resolutions.id", ondelete="RESTRICT"),
    )
    payment_attempt_resolution_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_payment_attempt_resolutions.id", ondelete="RESTRICT"),
    )
    refund_attempt_resolution_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("membership_refund_attempt_resolutions.id", ondelete="RESTRICT"),
    )
    evidence_kind: Mapped[str] = mapped_column(String(40), nullable=False)
    proof_reference: Mapped[str] = mapped_column(String(200), nullable=False)
    verified_occurred_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    reason: Mapped[str] = mapped_column(String(500), nullable=False)
    reconciled_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    reconciled_by: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False
    )
    idempotency_key: Mapped[str] = mapped_column(String(160), nullable=False)


@event.listens_for(MembershipEvidenceReconciliation, "before_update")
def _guard_membership_evidence_reconciliation_update(
    _mapper, _connection, row: MembershipEvidenceReconciliation
) -> None:
    state = inspect(row)
    changed = sorted(
        column.key
        for column in inspect(row.__class__).columns
        if state.attrs[column.key].history.has_changes()
    )
    if changed:
        raise ValueError(
            "membership evidence reconciliation is immutable: " + ", ".join(changed)
        )


class MembershipBenefitReservation(Base, TimestampMixin):
    """A period-scoped free allowance reserved by an unpaid POS order.

    Rows stay in place when an order is voided so the audit history is intact,
    but only live orders and consumed rows count against the allowance.  The
    membership row is locked before rows are reserved or consumed, preventing
    two terminals from spending the same allowance concurrently.
    """

    __tablename__ = "membership_benefit_reservations"
    __table_args__ = (
        UniqueConstraint(
            "order_id",
            "benefit_type",
            name="uq_membership_benefit_order_type",
        ),
        CheckConstraint("quantity > 0", name="ck_membership_benefit_quantity_positive"),
        CheckConstraint(
            "benefit_type IN ('gaming_minutes', 'hookah_count')",
            name="ck_membership_benefit_type",
        ),
        Index(
            "ix_membership_benefit_period",
            "membership_id",
            "benefit_type",
            "period_start",
        ),
    )

    id: Mapped[UUID] = _uuid_pk()
    membership_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("customer_memberships.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    order_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("orders.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    benefit_type: Mapped[str] = mapped_column(String(30), nullable=False)
    period_start: Mapped[date] = mapped_column(Date, nullable=False)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False)
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
