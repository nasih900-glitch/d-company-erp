"""Database-free membership allowance and zero-settlement regressions."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import BackgroundTasks

from app.api.v1.memberships import router as memberships_router
from app.api.v1.pos import router as pos_router
from app.core.errors import BusinessRuleError, ForbiddenError
from app.core.tenant import TenantContext
from app.models import (
    Branch,
    Company,
    Customer,
    CustomerMembership,
    MembershipBenefitReservation,
    MembershipTier,
    Order,
    Shift,
)
from app.services.audit.recorder import TRACKED
from app.services.pos.membership_benefits import (
    GAMING_MINUTES,
    HOOKAH_COUNT,
    _apply_legacy_usage_counter,
    applied_benefits_for_order,
    benefit_period_start,
    reservable_quantity,
    reserve_membership_benefits,
)
from app.services.pos.pricing import (
    MembershipDiscountRates,
    OrderPricingService,
    gaming_minutes_allowance_minor,
)


class _Result:
    def __init__(self, *, rows=None, scalar=None) -> None:
        self.rows = [] if rows is None else rows
        self.scalar = scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows

    def first(self):
        return self.rows[0] if self.rows else None

    def scalar_one(self):
        return self.scalar

    def scalar_one_or_none(self):
        return self.scalar


class _Session:
    def __init__(self, *results: _Result, entities=None) -> None:
        self.results = list(results)
        self.entities = {} if entities is None else entities
        self.statements = []
        self.added = []
        self.deleted = []

    async def execute(self, statement):
        self.statements.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    async def get(self, model, entity_id):
        return self.entities.get((model, entity_id))

    def add(self, entity) -> None:
        self.added.append(entity)

    async def delete(self, entity) -> None:
        self.deleted.append(entity)

    async def flush(self) -> None:
        return None


def _tenant(*, protected_access: bool = False) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
        protected_access=protected_access,
    )


def _request() -> SimpleNamespace:
    action_id = "membership-subscribe-test"
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key=action_id,
            idempotency_request_hash="request-hash",
        ),
        headers={"X-Client-Action-Id": action_id},
    )


def _open_shift(tenant: TenantContext, *, at: datetime) -> Shift:
    return Shift(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        opened_by=tenant.user_id,
        opened_at=at - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )


def test_membership_periods_use_the_business_timezone() -> None:
    # Sunday 20:00 UTC is already Monday in India.
    at = datetime(2026, 7, 19, 20, 0, tzinfo=UTC)
    assert benefit_period_start(
        GAMING_MINUTES,
        at=at,
        timezone_name="Asia/Kolkata",
    ) == date(2026, 7, 20)
    assert benefit_period_start(
        HOOKAH_COUNT,
        at=datetime(2026, 7, 31, 20, 0, tzinfo=UTC),
        timezone_name="Asia/Kolkata",
    ) == date(2026, 8, 1)


def test_membership_reservations_are_money_equivalent_audit_records() -> None:
    assert MembershipBenefitReservation in TRACKED


@pytest.mark.asyncio
async def test_cancelled_auto_renew_stays_active_until_paid_term_expires() -> None:
    now = datetime.now(UTC)
    tier = MembershipTier(
        id=uuid4(),
        company_id=uuid4(),
        code="gold",
        name="Gold",
        monthly_price_minor=199_900,
    )
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=uuid4(),
        tier_id=tier.id,
        billing_cycle="monthly",
        starts_at=now - timedelta(days=10),
        expires_at=now + timedelta(days=20),
        cancelled_at=now - timedelta(days=1),
        auto_renew=False,
        amount_paid_minor=199_900,
    )
    response = await memberships_router._subscription_to_read(
        _Session(
            _Result(scalar=None),
            entities={(MembershipTier, tier.id): tier},
        ),
        membership,
    )
    assert response.cancelled_at is not None
    assert response.auto_renew is False
    assert response.is_active is True


@pytest.mark.asyncio
async def test_only_protected_owner_can_mint_manual_membership_entitlement() -> None:
    tenant = _tenant(protected_access=False)
    session = _Session()
    with pytest.raises(ForbiddenError, match="protected owner"):
        await memberships_router.subscribe(
            memberships_router.SubscribeRequest(
                customer_id=uuid4(),
                tier_id=uuid4(),
                shift_id=uuid4(),
                expected_amount_minor=199_900,
                collected_at=datetime.now(UTC),
                billing_cycle="monthly",
                paid_via="cash",
            ),
            session,
            _request(),
            tenant,
        )
    assert session.statements == []


@pytest.mark.asyncio
async def test_payment_prepare_locks_customer_and_rejects_unexpired_overlap(
    monkeypatch,
) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(memberships_router, "check_or_reserve", reserve)

    tenant = _tenant(protected_access=True)
    customer = Customer(
        id=uuid4(),
        company_id=tenant.company_id,
        phone="9000000000",
    )
    now = datetime.now(UTC)
    shift = _open_shift(tenant, at=now)
    overlapping = CustomerMembership(
        id=uuid4(),
        customer_id=customer.id,
        tier_id=uuid4(),
        billing_cycle="monthly",
        starts_at=now - timedelta(days=2),
        expires_at=now + timedelta(days=28),
        auto_renew=False,
        amount_paid_minor=199_900,
    )
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=None),  # no request already uses this action ID
        _Result(scalar=None),  # no resolved legacy action
        _Result(scalar=customer),
        _Result(scalar=overlapping),
    )

    with pytest.raises(BusinessRuleError, match="already has an unexpired membership"):
        await memberships_router.prepare_membership_payment(
            memberships_router.MembershipPaymentRequestCreate(
                customer_id=customer.id,
                tier_id=uuid4(),
                shift_id=shift.id,
                expected_amount_minor=199_900,
                billing_cycle="monthly",
                paid_via="upi",
                client_action_id="membership-subscribe-test",
            ),
            session,
            _request(),
            tenant,
    )
    assert session.statements[0]._for_update_arg is not None  # exact shift
    assert session.statements[3]._for_update_arg is not None  # customer
    assert session.statements[4]._for_update_arg is not None  # overlapping term


@pytest.mark.asyncio
async def test_legacy_direct_collection_is_refused_without_minting_entitlement(
    monkeypatch,
) -> None:
    async def reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(memberships_router, "check_or_reserve", reserve)
    tenant = _tenant(protected_access=True)
    now = datetime.now(UTC)
    shift = _open_shift(tenant, at=now)
    session = _Session()
    with pytest.raises(BusinessRuleError, match="Direct membership collection is disabled"):
        await memberships_router.subscribe(
            memberships_router.SubscribeRequest(
                customer_id=uuid4(),
                tier_id=uuid4(),
                shift_id=shift.id,
                expected_amount_minor=199_900,
                collected_at=now,
                billing_cycle="monthly",
                paid_via="upi",
            ),
            session,
            _request(),
            tenant,
        )

    assert session.statements == []
    assert session.added == []


def test_reservation_never_exceeds_the_remaining_period_allowance() -> None:
    assert (
        reservable_quantity(
            allowance=240,
            already_used_or_reserved=180,
            requested=90,
        )
        == 60
    )
    assert (
        reservable_quantity(
            allowance=1,
            already_used_or_reserved=1,
            requested=1,
        )
        == 0
    )


def test_ps5_allowance_uses_the_stored_session_rate_and_exact_gross() -> None:
    assert (
        gaming_minutes_allowance_minor(
            gross_amount_minor=15_667,
            billable_minutes=47,
            reserved_minutes=47,
            rate_per_hour_minor=20_000,
        )
        == 15_667
    )
    # 17 of 47 minutes remain payable: ceil(17/60 * 20000) = 5667.
    assert (
        gaming_minutes_allowance_minor(
            gross_amount_minor=15_667,
            billable_minutes=47,
            reserved_minutes=30,
            rate_per_hour_minor=20_000,
        )
        == 10_000
    )


@pytest.mark.asyncio
async def test_percentage_discount_still_applies_after_the_free_allowance() -> None:
    company = Company(
        id=uuid4(),
        name="TestCo",
        gst_registration_type="regular",
        is_composition=False,
        gstin="32ABCDE1234F1Z5",
    )
    branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name="Main",
        state_code="32",
    )

    class _Pricing(OrderPricingService):
        async def _membership_discount_rates(self, **_kwargs):
            return MembershipDiscountRates(gaming=Decimal("0.20"))

    session = _Session(entities={(Company, company.id): company, (Branch, branch.id): branch})
    priced = await _Pricing(session).price_time_based_line(
        company_id=company.id,
        branch_id=branch.id,
        amount_minor=20_000,
        tax_rate=Decimal("0.18"),
        rate_includes_tax=True,
        customer_phone="9000000000",
        item_type="gaming",
        allowance_minor=10_000,
    )

    assert priced.allowance_discount_minor == 10_000
    assert priced.discount_minor == 12_000  # free ₹100 + 20% of the remaining ₹100
    assert priced.total_minor == 8_000
    assert priced.taxable_minor + priced.cgst_minor + priced.sgst_minor == 8_000


@pytest.mark.asyncio
async def test_reservation_locks_valid_paid_term_and_accounts_for_other_live_orders() -> None:
    now = datetime(2026, 7, 15, 12, tzinfo=UTC)
    company_id = uuid4()
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=uuid4(),
        tier_id=uuid4(),
        billing_cycle="monthly",
        starts_at=now - timedelta(days=1),
        expires_at=now + timedelta(days=30),
        # Cancellation stops renewal; this paid term remains valid to expires_at.
        cancelled_at=now,
        auto_renew=True,
        amount_paid_minor=199_900,
    )
    tier = MembershipTier(
        id=membership.tier_id,
        company_id=company_id,
        code="gold",
        name="Gold",
        monthly_price_minor=199_900,
        free_gaming_minutes_per_week=240,
        free_hookah_per_month=1,
    )
    order = Order(
        id=uuid4(),
        company_id=company_id,
        customer_phone="9000000000",
        status="held",
    )
    session = _Session(
        _Result(rows=[]),  # this order's existing reservations
        _Result(rows=[(membership, tier)]),  # active membership candidate
        _Result(rows=[membership]),  # stable FOR UPDATE membership lock
        _Result(scalar=tier),  # tier lock freezes allowance configuration
        _Result(scalar="Asia/Kolkata"),
        _Result(scalar=180),  # consumed/reserved gaming minutes in this week
        _Result(scalar=1),  # consumed/reserved hookahs in this month
    )

    applied = await reserve_membership_benefits(
        session,
        order=order,
        company_id=company_id,
        requested_gaming_minutes=90,
        requested_hookah_count=1,
        at=now,
    )

    assert applied.gaming_minutes == 60
    assert applied.hookah_count == 0
    assert len(session.added) == 1
    assert session.added[0].benefit_type == GAMING_MINUTES
    assert session.added[0].quantity == 60
    assert session.statements[2]._for_update_arg is not None
    assert session.statements[3]._for_update_arg is not None


@pytest.mark.asyncio
async def test_free_benefits_fail_closed_on_legacy_overlapping_memberships() -> None:
    now = datetime(2026, 7, 15, 12, tzinfo=UTC)
    company_id = uuid4()
    memberships = [
        CustomerMembership(
            id=uuid4(),
            customer_id=uuid4(),
            tier_id=uuid4(),
            billing_cycle="monthly",
            starts_at=now - timedelta(days=1),
            expires_at=now + timedelta(days=30 + index),
            auto_renew=False,
            amount_paid_minor=199_900,
        )
        for index in range(2)
    ]
    tiers = [
        MembershipTier(
            id=membership.tier_id,
            company_id=company_id,
            code=f"gold-{index}",
            name="Gold",
            monthly_price_minor=199_900,
            free_gaming_minutes_per_week=240,
        )
        for index, membership in enumerate(memberships)
    ]
    order = Order(
        id=uuid4(),
        company_id=company_id,
        customer_phone="9000000000",
        status="held",
    )
    session = _Session(
        _Result(rows=[]),
        _Result(rows=list(zip(memberships, tiers, strict=True))),
    )

    with pytest.raises(BusinessRuleError, match="overlapping membership terms"):
        await reserve_membership_benefits(
            session,
            order=order,
            company_id=company_id,
            requested_gaming_minutes=60,
            at=now,
        )
    assert session.added == []


@pytest.mark.asyncio
async def test_repricing_releases_provisional_reservation_through_audited_orm_delete() -> None:
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=uuid4(),
        tier_id=uuid4(),
        billing_cycle="monthly",
        starts_at=datetime(2026, 1, 1, tzinfo=UTC),
        expires_at=datetime(2027, 1, 1, tzinfo=UTC),
        auto_renew=True,
        amount_paid_minor=0,
    )
    order = Order(
        id=uuid4(),
        company_id=uuid4(),
        customer_phone=None,
        status="held",
    )
    reservation = MembershipBenefitReservation(
        id=uuid4(),
        membership_id=membership.id,
        order_id=order.id,
        benefit_type=GAMING_MINUTES,
        period_start=date(2026, 7, 13),
        quantity=30,
        consumed_at=None,
    )
    session = _Session(
        _Result(rows=[reservation]),
        _Result(rows=[membership]),
    )

    applied = await reserve_membership_benefits(
        session,
        order=order,
        company_id=order.company_id,
    )

    assert applied.gaming_minutes == 0
    assert session.deleted == [reservation]
    assert session.statements[1]._for_update_arg is not None


def test_legacy_usage_counter_never_rolls_back_a_newer_period() -> None:
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=uuid4(),
        tier_id=uuid4(),
        billing_cycle="monthly",
        starts_at=datetime(2026, 1, 1, tzinfo=UTC),
        expires_at=datetime(2027, 1, 1, tzinfo=UTC),
        auto_renew=True,
        amount_paid_minor=0,
        gaming_usage_week_start=date(2026, 7, 20),
        gaming_minutes_used_this_week=30,
    )
    old_reservation = MembershipBenefitReservation(
        id=uuid4(),
        membership_id=membership.id,
        order_id=uuid4(),
        benefit_type=GAMING_MINUTES,
        period_start=date(2026, 7, 13),
        quantity=60,
    )
    _apply_legacy_usage_counter(membership, old_reservation)
    assert membership.gaming_usage_week_start == date(2026, 7, 20)
    assert membership.gaming_minutes_used_this_week == 30


@pytest.mark.asyncio
async def test_void_order_exposes_no_applied_benefit_without_deleting_history() -> None:
    class _NoQuerySession:
        async def execute(self, _statement):
            raise AssertionError("void benefit history must not be presented as applied")

    benefits = await applied_benefits_for_order(
        _NoQuerySession(),
        order=Order(id=uuid4(), status="void"),
    )
    assert benefits.gaming_minutes == 0
    assert benefits.hookah_count == 0


@pytest.mark.asyncio
async def test_zero_total_finalization_is_replay_safe_and_uses_shared_finalizer(
    monkeypatch,
) -> None:
    tenant = _tenant()
    now = datetime(2026, 7, 15, 12, tzinfo=UTC)
    shift = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        opened_by=tenant.user_id,
        status="open",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        status="held",
        total_minor=0,
        invoice_no=None,
        fiscal_year=None,
        invoice_issued_at=None,
    )
    session = _Session(_Result(scalar=order), _Result(scalar=shift))
    stored = []
    finalized = []
    consumed_claims = []
    checkout_claim = object()

    async def _reserve(*_args, **_kwargs):
        return None

    async def _paid(*_args, **_kwargs):
        return 0

    async def _finalize(_session, **kwargs):
        finalized.append(kwargs)
        order.status = "paid"
        order.invoice_no = "D/MN/26-27/00001"
        order.fiscal_year = "2026-27"
        order.invoice_issued_at = now

    async def _store(*_args, **kwargs):
        stored.append(kwargs)

    async def _validate_claim(*_args, **kwargs):
        assert kwargs["token"] == "membership-checkout-claim-token"
        return checkout_claim

    async def _consume_claim(_session, claim):
        consumed_claims.append(claim)

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    monkeypatch.setattr(pos_router, "_paid_total", _paid)
    monkeypatch.setattr(pos_router, "_finalize_order", _finalize)
    monkeypatch.setattr(pos_router, "store_response", _store)
    monkeypatch.setattr(pos_router, "validate_checkout_claim", _validate_claim)
    monkeypatch.setattr(pos_router, "consume_checkout_claim", _consume_claim)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="zero-membership-order-1",
            idempotency_request_hash="hash-1",
        )
    )

    background_tasks = BackgroundTasks()
    response = await pos_router.finalize_zero_total_order(
        order.id,
        session,
        request,
        background_tasks,
        tenant,
        "membership-checkout-claim-token",
    )

    assert len(finalized) == 1
    assert finalized[0]["actor_user_id"] == tenant.user_id
    assert len(background_tasks.tasks) == 1, (
        "finalize-zero must schedule the same OrderPaid mirror event as "
        "record_payment, or membership-covered visits never reach the "
        "owner's Google Sheets reconciliation mirror"
    )
    assert response == {
        "order_id": str(order.id),
        "amount_minor": 0,
        "order_status": "paid",
        "invoice_no": "D/MN/26-27/00001",
        "fiscal_year": "2026-27",
        "invoice_issued_at": now.isoformat(),
    }
    assert stored[0]["status_code"] == 200
    assert consumed_claims == [checkout_claim]

    async def _replay(*_args, **_kwargs):
        return {"body": response, "status_code": 200}

    monkeypatch.setattr(pos_router, "check_or_reserve", _replay)
    replay_session = _Session()
    assert (
        await pos_router.finalize_zero_total_order(
            order.id,
            replay_session,
            request,
            BackgroundTasks(),
            tenant,
        )
        == response
    )
    assert replay_session.statements == []


@pytest.mark.asyncio
async def test_shared_finalizer_consumes_allowance_and_runs_sale_side_effects(
    monkeypatch,
) -> None:
    company_id = uuid4()
    branch = Branch(
        id=uuid4(),
        company_id=company_id,
        name="Main",
        code="MN",
        timezone="Asia/Kolkata",
    )
    order = Order(
        id=uuid4(),
        company_id=company_id,
        branch_id=branch.id,
        terminal_id=uuid4(),
        shift_id=uuid4(),
        opened_by=uuid4(),
        type="session",
        status="held",
        total_minor=0,
        customer_phone="9000000000",
        customer_name="Member",
    )
    order_line = SimpleNamespace(
        menu_item_id=uuid4(),
        qty=1,
        kitchen_released_at=None,
        kitchen_round_no=None,
        kitchen_status="queued",
    )
    session_item = SimpleNamespace(type="gaming")
    session = _Session(
        _Result(rows=[(order_line, session_item)]),
        entities={(Branch, branch.id): branch},
    )
    events = []

    class _InvoiceNumbers:
        def __init__(self, _session) -> None:
            pass

        async def allocate(self, **kwargs):
            events.append(("invoice", kwargs))
            return "D/MN/26-27/00001", "2026-27"

    async def _consume(_session, **kwargs):
        events.append(("consume", kwargs))

    async def _release(_session, finalized_order):
        events.append(("release", finalized_order.id))

    async def _deduct(_session, **kwargs):
        events.append(("deduct", kwargs))

    async def _loyalty(_session, **kwargs):
        events.append(("loyalty", kwargs))

    async def _consume_points(_session, **kwargs):
        events.append(("consume_points", kwargs))

    monkeypatch.setattr(pos_router, "InvoiceNumberService", _InvoiceNumbers)
    monkeypatch.setattr(pos_router, "consume_membership_benefits", _consume)
    monkeypatch.setattr(pos_router, "consume_points_redemption", _consume_points)
    monkeypatch.setattr(pos_router, "_release_table_if_no_active_orders", _release)
    monkeypatch.setattr(pos_router, "deduct_for_order", _deduct)
    monkeypatch.setattr(pos_router, "_upsert_and_attach_customer", _loyalty)
    finalized_at = datetime(2026, 7, 15, 12, tzinfo=UTC)

    await pos_router._finalize_order(
        session,
        order=order,
        company_id=company_id,
        actor_user_id=order.opened_by,
        at=finalized_at,
    )

    assert order.status == "paid"
    assert order.invoice_no == "D/MN/26-27/00001"
    assert order.invoice_issued_at == finalized_at
    assert [event[0] for event in events] == [
        "invoice",
        "consume",
        "consume_points",
        "release",
        "deduct",
        "loyalty",
    ]


@pytest.mark.asyncio
async def test_zero_total_finalization_keeps_shift_opener_accountability(monkeypatch) -> None:
    tenant = _tenant()
    shift = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        opened_by=uuid4(),
        status="open",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        status="held",
        total_minor=0,
    )
    session = _Session(_Result(scalar=order), _Result(scalar=shift))

    async def _reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="zero-membership-order-2",
            idempotency_request_hash="hash-2",
        )
    )
    with pytest.raises(BusinessRuleError, match="Only the staff member who opened this shift"):
        await pos_router.finalize_zero_total_order(
            order.id,
            session,
            request,
            BackgroundTasks(),
            tenant,
        )
