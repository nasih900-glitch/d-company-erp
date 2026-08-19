"""Database-free route regressions for the cafe's operational handoff rules.

These tests call the real endpoint functions with a deliberately small async
session double.  They protect state-transition and response contracts without
turning the unit suite into an implicit PostgreSQL integration suite.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from fastapi import BackgroundTasks
from pydantic import ValidationError as PydanticValidationError

from app.api.v1.gaming import router as gaming_router
from app.api.v1.pos import router as pos_router
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext
from app.events.events import OrderPaid
from app.models import Branch, Order, OrderLine, Payment, Refund, Station, Table


class _Result:
    def __init__(self, *, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = [] if rows is None else rows

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows

    def first(self):
        return self.rows[0] if self.rows else self.scalar


class _Session:
    def __init__(self, *results: _Result, entities=None) -> None:
        self.results = list(results)
        self.entities = {} if entities is None else entities
        self.statements = []
        self.added = []
        self.flush_count = 0

    async def execute(self, statement):
        self.statements.append(statement)
        if not self.results:
            raise AssertionError(f"unexpected database statement: {statement}")
        return self.results.pop(0)

    async def get(self, model, entity_id):
        return self.entities.get((model, entity_id))

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flush_count += 1


def _tenant(
    *,
    company_id: UUID | None = None,
    branch_id: UUID | None = None,
    terminal_id: UUID | None = None,
    user_id: UUID | None = None,
    protected_access: bool = False,
) -> TenantContext:
    return TenantContext(
        user_id=user_id or uuid4(),
        company_id=company_id or uuid4(),
        branch_id=branch_id or uuid4(),
        terminal_id=terminal_id or uuid4(),
        roles=("owner",),
        protected_access=protected_access,
    )


def _shift(tenant: TenantContext, **overrides):
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "branch_id": tenant.branch_id,
        "terminal_id": tenant.terminal_id,
        "opened_by": tenant.user_id,
        "opened_at": datetime.now(UTC),
        "closed_at": None,
        "opening_float_minor": 0,
        "expected_minor": 5_000,
        "counted_minor": None,
        "variance_minor": None,
        "status": "open",
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _gaming_session(tenant: TenantContext, station_id: UUID, shift_id: UUID, **overrides):
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "station_id": station_id,
        "shift_id": shift_id,
        "opened_by": tenant.user_id,
        "order_id": None,
        "start_at": datetime(2026, 7, 15, 10, tzinfo=UTC),
        "end_at": datetime(2026, 7, 15, 10, 47, tzinfo=UTC),
        "paused_minutes": 0,
        "timer_minutes": None,
        "billable_minutes": 47,
        "rate_per_hour_minor": 20_000,
        "amount_minor": 15_667,
        "status": "ended",
        "cancelled_at": None,
        "cancelled_by": None,
        "cancel_reason": None,
        "customer_name": "Cafe Guest",
        "customer_phone": "9000000000",
        "tax_rate": 0.18,
        "sac_code": "999692",
        "rate_includes_tax": True,
        "package_id": None,
        "extra_controllers": 0,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _station(tenant: TenantContext, **overrides):
    values = {
        "id": uuid4(),
        "company_id": tenant.company_id,
        "branch_id": tenant.branch_id,
        "name": "PS5 1",
        "type": "ps5",
        "tax_rate": 0.18,
        "sac_code": "999692",
        "rate_includes_tax": True,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def test_confirmed_payment_rejects_a_stale_or_partial_full_settlement() -> None:
    exact = pos_router.PaymentCreate(
        method="upi",
        amount_minor=10_000,
        expected_order_total_minor=12_000,
        expected_due_minor=10_000,
    )
    pos_router._validate_confirmed_payment_balance(
        exact,
        order_total_minor=12_000,
        due_minor=10_000,
    )

    with pytest.raises(BusinessRuleError, match="Order total changed"):
        pos_router._validate_confirmed_payment_balance(
            exact,
            order_total_minor=12_100,
            due_minor=10_000,
        )
    with pytest.raises(BusinessRuleError, match="Order balance changed"):
        pos_router._validate_confirmed_payment_balance(
            exact,
            order_total_minor=12_000,
            due_minor=9_000,
        )

    partial = exact.model_copy(update={"amount_minor": 5_000})
    with pytest.raises(BusinessRuleError, match="must equal"):
        pos_router._validate_confirmed_payment_balance(
            partial,
            order_total_minor=12_000,
            due_minor=10_000,
        )

    partial_without_client_expectations = pos_router.PaymentCreate(
        method="cash",
        amount_minor=5_000,
    )
    with pytest.raises(BusinessRuleError, match="Split payments are not enabled"):
        pos_router._validate_confirmed_payment_balance(
            partial_without_client_expectations,
            order_total_minor=10_000,
            due_minor=10_000,
        )


@pytest.mark.asyncio
async def test_record_payment_with_tip_grows_order_total_and_settles_in_one_shot(
    monkeypatch,
) -> None:
    """A tip is additional money collected alongside the bill.

    It must never be folded into amount_minor or the exact-amount-due match
    in _validate_confirmed_payment_balance (that check protects the bill
    itself from stale reads/split payments, and is exercised untouched by
    the amount_minor==due_minor path above). Instead the endpoint layers it
    on afterward: Order.tip_minor and Order.total_minor both grow by the tip,
    and the Payment row records what was actually collected (bill + tip) —
    exactly what ledger.py's TIPS_PAYABLE line and the reports balance check
    (app/api/v1/reports/router.py) already expect from order.total_minor.
    """
    tenant = _tenant()
    shift = _shift(tenant, opened_by=tenant.user_id, status="open")
    branch = SimpleNamespace(
        id=tenant.branch_id,
        company_id=tenant.company_id,
        deleted_at=None,
        timezone="Asia/Kolkata",
        code="MN",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        status="open",
        total_minor=10_000,
        tip_minor=0,
        table_id=None,
        customer_phone=None,
        # Pre-set so _finalize_order skips invoice allocation — this test is
        # about the tip/settlement math, not the invoice-numbering service.
        invoice_no="INV-PRESET-0001",
        fiscal_year="2026-27",
        closed_at=None,
        invoice_issued_at=None,
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    stored: dict = {}

    async def _store_response(*_args, **kwargs):
        stored.update(kwargs)
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)

    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="payment-with-tip-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),  # order lookup
        _Result(scalar=shift),  # shift lookup
        _Result(scalar=0),  # _paid_total: nothing paid yet
        _Result(rows=[]),  # consume_membership_benefits: no reservations
        _Result(scalar=None),  # consume_points_redemption: no redemption row
        _Result(rows=[]),  # order_lines fetched for finalization/deduction
        entities={(Branch, tenant.branch_id): branch},
    )

    background_tasks = BackgroundTasks()
    response = await pos_router.record_payment(
        order.id,
        pos_router.PaymentCreate(
            method="upi",
            amount_minor=10_000,
            tip_minor=1_500,
            expected_order_total_minor=10_000,
            expected_due_minor=10_000,
        ),
        session,
        request,
        background_tasks,
        tenant,
    )

    # The bill itself was matched exactly (amount_minor == due_minor == the
    # pre-tip total) — the tip rode along on top without touching that check.
    assert order.tip_minor == 1_500
    assert order.total_minor == 11_500
    assert order.status == "paid"

    payment = next(entity for entity in session.added if isinstance(entity, Payment))
    assert payment.amount_minor == 11_500  # bill + tip actually collected

    assert response["amount_minor"] == 11_500
    assert response["tip_minor"] == 1_500
    assert response["order_status"] == "paid"
    assert stored["status_code"] == 201

    # A fully-settled payment must queue exactly one OrderPaid publish as a
    # BackgroundTask — never awaited inline (that would delay the response
    # on a slow webhook) and never fired before the response, since
    # BackgroundTasks only run after this request's session has committed.
    assert len(background_tasks.tasks) == 1


@pytest.mark.asyncio
async def test_record_payment_schedules_order_paid_event_with_correct_shape(
    monkeypatch,
) -> None:
    """The queued background task must publish OrderPaid with the real
    order/company/branch ids, the settled total, and the payment method —
    and must never raise even if the bus itself blows up.
    """
    tenant = _tenant()
    shift = _shift(tenant, opened_by=tenant.user_id, status="open")
    branch = SimpleNamespace(
        id=tenant.branch_id,
        company_id=tenant.company_id,
        deleted_at=None,
        timezone="Asia/Kolkata",
        code="MN",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        status="open",
        total_minor=5_000,
        tip_minor=0,
        table_id=None,
        customer_phone=None,
        invoice_no="INV-PRESET-0002",
        fiscal_year="2026-27",
        closed_at=None,
        invoice_issued_at=None,
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    async def _store_response(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)

    published: list[OrderPaid] = []

    class _FakeBus:
        async def publish(self, event):
            published.append(event)

    monkeypatch.setattr(pos_router, "get_event_bus", lambda: _FakeBus())

    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="payment-event-shape-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(rows=[]),
        _Result(scalar=None),
        _Result(rows=[]),
        entities={(Branch, tenant.branch_id): branch},
    )

    background_tasks = BackgroundTasks()
    await pos_router.record_payment(
        order.id,
        pos_router.PaymentCreate(
            method="cash",
            amount_minor=5_000,
            expected_order_total_minor=5_000,
            expected_due_minor=5_000,
        ),
        session,
        request,
        background_tasks,
        tenant,
    )

    assert published == []  # not fired inline — only queued so far
    await background_tasks()  # simulate Starlette running it post-response

    assert len(published) == 1
    event = published[0]
    assert event.order_id == order.id
    assert event.company_id == tenant.company_id
    assert event.branch_id == tenant.branch_id
    assert event.total_minor == 5_000
    assert event.method == "cash"


@pytest.mark.asyncio
async def test_record_payment_order_paid_publish_failure_never_raises(
    monkeypatch,
) -> None:
    """A broken/unreachable event bus must not surface as an error — the
    payment already succeeded and the response was already sent by the time
    this background task runs.
    """
    tenant = _tenant()
    shift = _shift(tenant, opened_by=tenant.user_id, status="open")
    branch = SimpleNamespace(
        id=tenant.branch_id,
        company_id=tenant.company_id,
        deleted_at=None,
        timezone="Asia/Kolkata",
        code="MN",
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=shift.id,
        status="open",
        total_minor=5_000,
        tip_minor=0,
        table_id=None,
        customer_phone=None,
        invoice_no="INV-PRESET-0003",
        fiscal_year="2026-27",
        closed_at=None,
        invoice_issued_at=None,
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    async def _store_response(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)

    class _ExplodingBus:
        async def publish(self, _event):
            raise RuntimeError("event bus is down")

    monkeypatch.setattr(pos_router, "get_event_bus", lambda: _ExplodingBus())

    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="payment-event-failure-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=shift),
        _Result(scalar=0),
        _Result(rows=[]),
        _Result(scalar=None),
        _Result(rows=[]),
        entities={(Branch, tenant.branch_id): branch},
    )

    background_tasks = BackgroundTasks()
    response = await pos_router.record_payment(
        order.id,
        pos_router.PaymentCreate(
            method="cash",
            amount_minor=5_000,
            expected_order_total_minor=5_000,
            expected_due_minor=5_000,
        ),
        session,
        request,
        background_tasks,
        tenant,
    )
    assert response["order_status"] == "paid"

    # Must not raise even though the bus explodes.
    await background_tasks()


@pytest.mark.asyncio
async def test_gaming_points_ratio_excludes_tip_on_a_tipped_and_discounted_order() -> None:
    """A tip folded into order.total_minor by record_payment (see the test
    above) must not inflate the loyalty-points paid ratio.

    Setup: a ₹1000 gaming line (200 raw points before ratio scaling), a ₹400
    manual discount, and a ₹600 tip collected on top of the discounted ₹600
    bill — the same 100%-of-bill tip a generous, fully-discount-covered
    customer might leave. order.total_minor therefore already includes the
    tip (600 bill + 600 tip = 1200), exactly as it sits post-record_payment.

    Correct ratio uses the taxable (pre-tip) amounts on both sides:
    600 / (600 + 400) = 0.6, so points = 200 * 0.6 = 120.

    The pre-fix bug used order.total_minor (tip included) as the numerator
    and folded it into the denominator too: 1200 / (1200 + 400) = 0.75,
    over-awarding 150 points on money that was actually a tip, not bill
    payment.
    """
    item_id = uuid4()
    gaming_item = SimpleNamespace(id=item_id, type="gaming")
    order_line = SimpleNamespace(menu_item_id=item_id, line_total_minor=100_000)
    order = SimpleNamespace(
        total_minor=120_000,  # 600 bill + 600 tip, in minor units (paise)
        tip_minor=60_000,
        manual_discount_minor=40_000,
        points_redeemed_minor=0,
    )
    session = _Session(_Result(rows=[gaming_item]))

    points_earned = await pos_router._compute_points_with_multiplier(
        session,
        order=order,
        order_lines=[order_line],
        membership_multiplier=1.0,
    )

    assert points_earned == 120
    assert points_earned != 150  # the tip-inflated result the bug produced


def test_customer_repricing_recovers_stored_gross_without_current_menu_price() -> None:
    inclusive = SimpleNamespace(
        line_total_minor=16_000,
        taxable_value_minor=13_559,
        discount_minor=4_000,
    )
    exclusive = SimpleNamespace(
        line_total_minor=18_880,
        taxable_value_minor=16_000,
        discount_minor=4_000,
    )

    assert pos_router._stored_line_gross_amount(
        inclusive,
        price_includes_tax=True,
    ) == 20_000
    assert pos_router._stored_line_gross_amount(
        exclusive,
        price_includes_tax=False,
    ) == 20_000


@pytest.mark.asyncio
async def test_customer_repricing_updates_existing_lines_and_canonical_order_total(
    monkeypatch,
) -> None:
    company_id = uuid4()
    branch_id = uuid4()
    item_id = uuid4()
    order = SimpleNamespace(
        id=uuid4(),
        branch_id=branch_id,
        customer_phone="9000000000",
        place_of_supply_state_code="32",
        delivery_via=None,
        subtotal_minor=0,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        tax_minor=0,
        round_off_minor=0,
        total_minor=0,
    )
    line = SimpleNamespace(
        menu_item_id=item_id,
        qty=1,
        unit_price_minor=20_000,
        line_total_minor=20_000,
        discount_minor=0,
        taxable_value_minor=16_949,
        tax_rate=0.18,
        cgst_minor=1_525,
        sgst_minor=1_526,
        igst_minor=0,
        cess_minor=0,
    )
    item = SimpleNamespace(
        id=item_id,
        sku="FOOD-1",
        type="food",
        price_includes_tax=True,
    )

    class _Pricing:
        def __init__(self, _session) -> None:
            pass

        async def price_time_based_line(self, **kwargs):
            assert kwargs["amount_minor"] == 20_000
            assert kwargs["customer_phone"] == "9000000000"
            assert kwargs["item_type"] == "food"
            return SimpleNamespace(
                total_minor=16_000,
                discount_minor=4_000,
                taxable_minor=13_559,
                cgst_minor=1_220,
                sgst_minor=1_221,
                igst_minor=0,
            )

    monkeypatch.setattr(pos_router, "OrderPricingService", _Pricing)

    async def _no_free_allowance(*_args, **_kwargs):
        return SimpleNamespace(gaming_minutes=0, hookah_count=0)

    monkeypatch.setattr(pos_router, "reserve_membership_benefits", _no_free_allowance)
    session = _Session(
        _Result(rows=[line]),
        _Result(rows=[item]),
        _Result(rows=[]),
        _Result(),  # points redemption: existing reservation lookup (none)
        _Result(),  # points redemption: reserve_points_redemption's own lookup (none)
    )

    await pos_router._reprice_unpaid_order_for_customer(
        session,
        order=order,
        company_id=company_id,
    )

    assert line.line_total_minor == 16_000
    assert line.discount_minor == 4_000
    assert order.total_minor == 16_000
    assert order.discount_minor == 4_000
    assert order.tax_minor == 2_441
    assert order.round_off_minor == 0


def test_cancel_reason_is_trimmed_and_whitespace_only_is_rejected() -> None:
    assert gaming_router.SessionCancel(reason="  Customer left  ").reason == "Customer left"
    with pytest.raises(PydanticValidationError):
        gaming_router.SessionCancel(reason="   \t  ")


@pytest.mark.asyncio
async def test_close_shift_replays_only_the_same_count_and_keeps_opener_accountability() -> None:
    tenant = _tenant()
    closed = _shift(
        tenant,
        status="closed",
        counted_minor=4_900,
        variance_minor=-100,
        closed_at=datetime.now(UTC),
    )

    replay = await pos_router.close_shift(
        closed.id,
        pos_router.ShiftCloseRequest(counted_minor=4_900),
        _Session(_Result(scalar=closed)),
        tenant,
    )
    assert replay == {"id": str(closed.id), "status": "closed", "variance_minor": -100}

    with pytest.raises(BusinessRuleError, match="different counted amount"):
        await pos_router.close_shift(
            closed.id,
            pos_router.ShiftCloseRequest(counted_minor=4_800),
            _Session(_Result(scalar=closed)),
            tenant,
        )

    other_staff = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
    )
    with pytest.raises(BusinessRuleError, match="Only the staff member who opened this shift"):
        await pos_router.close_shift(
            closed.id,
            pos_router.ShiftCloseRequest(counted_minor=4_900),
            _Session(_Result(scalar=closed)),
            other_staff,
        )


@pytest.mark.asyncio
async def test_close_shift_blocks_stopped_sessions_not_sent_to_pos() -> None:
    tenant = _tenant()
    shift = _shift(tenant)
    session = _Session(
        _Result(scalar=shift),
        _Result(scalar=0),  # unfinished POS orders
        _Result(scalar=0),  # running sessions
        _Result(scalar=1),  # stopped, unbilled sessions
    )

    with pytest.raises(BusinessRuleError, match="1 stopped session.*not yet sent to POS"):
        await pos_router.close_shift(
            shift.id,
            pos_router.ShiftCloseRequest(counted_minor=5_000),
            session,
            tenant,
        )
    assert shift.status == "open"
    assert shift.closed_at is None


@pytest.mark.asyncio
async def test_non_opener_cannot_refund_an_original_non_cash_payment(
    monkeypatch,
) -> None:
    tenant = _tenant()
    original_shift = _shift(
        tenant,
        opened_by=uuid4(),
        status="closed",
        closed_at=datetime.now(UTC),
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=original_shift.id,
        status="paid",
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="refund-accountability-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=original_shift),
    )

    with pytest.raises(
        BusinessRuleError,
        match="Only the staff member who opened this shift",
    ):
        await pos_router.issue_refund(
            order.id,
            pos_router.RefundCreate(
                reason_code="CUSTOMER_REQUEST",
                amount_minor=1_000,
                mode="original",
            ),
            session,
            request,
            tenant,
        )

    assert len(session.statements) == 2
    assert original_shift.id in session.statements[1].compile().params.values()
    assert session.added == []


@pytest.mark.asyncio
async def test_protected_owner_can_refund_original_non_cash_on_closed_shift(
    monkeypatch,
) -> None:
    tenant = _tenant(protected_access=True)
    original_shift = _shift(
        tenant,
        opened_by=uuid4(),
        status="closed",
        closed_at=datetime.now(UTC),
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=original_shift.id,
        status="paid",
        total_minor=5_000,
        tip_minor=0,
    )
    payment = SimpleNamespace(
        amount_minor=5_000,
        method="upi",
        paid_at=datetime.now(UTC),
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    async def _store_response(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    monkeypatch.setattr(pos_router, "store_response", _store_response)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="protected-owner-refund-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=original_shift),
        _Result(rows=[payment]),
        _Result(scalar=0),
        _Result(rows=[]),  # sale StockMovements fetched for refund-restock; none found
    )

    response = await pos_router.issue_refund(
        order.id,
        pos_router.RefundCreate(
            reason_code="CUSTOMER_REQUEST",
            amount_minor=5_000,
            mode="original",
        ),
        session,
        request,
        tenant,
    )

    refund = next(entity for entity in session.added if isinstance(entity, Refund))
    assert response == {"id": str(refund.id), "settlement_method": "upi"}
    assert refund.approved_by == tenant.user_id
    assert order.status == "refunded"


@pytest.mark.asyncio
async def test_cash_refund_still_requires_a_current_open_drawer_shift(
    monkeypatch,
) -> None:
    tenant = _tenant()
    original_shift = _shift(
        tenant,
        status="closed",
        closed_at=datetime.now(UTC),
    )
    order = SimpleNamespace(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=original_shift.id,
        status="paid",
    )
    payment = SimpleNamespace(
        amount_minor=5_000,
        method="upi",
        paid_at=datetime.now(UTC),
    )

    async def _reserve_idempotency(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve_idempotency)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="cash-refund-open-drawer-test",
            idempotency_request_hash="hash",
        )
    )
    session = _Session(
        _Result(scalar=order),
        _Result(scalar=original_shift),
        _Result(rows=[payment]),
        _Result(scalar=0),
        _Result(rows=[]),
    )

    with pytest.raises(
        BusinessRuleError,
        match="needs an open shift on this terminal",
    ):
        await pos_router.issue_refund(
            order.id,
            pos_router.RefundCreate(
                reason_code="CUSTOMER_REQUEST",
                amount_minor=1_000,
                mode="cash",
            ),
            session,
            request,
            tenant,
        )


@pytest.mark.asyncio
async def test_session_send_uses_the_sessions_original_shift(monkeypatch) -> None:
    tenant = _tenant()
    station = _station(tenant)
    original_shift = _shift(tenant)
    gaming_session = _gaming_session(tenant, station.id, original_shift.id)
    menu_item = SimpleNamespace(id=uuid4(), hsn_code="999692")
    session = _Session(
        _Result(scalar=gaming_session),
        _Result(scalar=original_shift),
        entities={(Station, station.id): station},
    )

    async def _menu_item(_session, *, company_id, station):
        assert company_id == tenant.company_id
        assert station.id == gaming_session.station_id
        return menu_item

    class _Pricing:
        def __init__(self, _session) -> None:
            pass

        async def price_time_based_line(self, **kwargs):
            assert kwargs["branch_id"] == original_shift.branch_id
            assert kwargs["amount_minor"] == gaming_session.amount_minor
            return SimpleNamespace(
                taxable_minor=13_277,
                discount_minor=0,
                cgst_minor=1_195,
                sgst_minor=1_195,
                igst_minor=0,
                total_minor=15_667,
            )

    monkeypatch.setattr(gaming_router, "_ensure_session_menu_item", _menu_item)
    monkeypatch.setattr(gaming_router, "OrderPricingService", _Pricing)

    async def _reprice_membership(_session, *, order, company_id):
        assert order.customer_phone == gaming_session.customer_phone
        assert company_id == tenant.company_id

    monkeypatch.setattr(
        gaming_router,
        "_reprice_session_order_for_customer",
        _reprice_membership,
    )

    response = await gaming_router.send_session_to_pos(
        gaming_session.id,
        session,
        tenant,
    )

    created_order = next(entity for entity in session.added if isinstance(entity, Order))
    created_line = next(entity for entity in session.added if isinstance(entity, OrderLine))
    assert response == {"order_id": str(created_order.id), "amount_minor": 15_700}
    assert created_order.shift_id == gaming_session.shift_id == original_shift.id
    assert created_order.branch_id == original_shift.branch_id
    assert created_order.terminal_id == original_shift.terminal_id
    assert created_order.held_at is not None
    assert created_order.total_minor == 15_700
    assert created_order.round_off_minor == 33
    assert created_order.discount_minor == 0
    assert gaming_session.order_id == created_order.id
    assert created_line.note == "47 min @ 200.00/hr"
    assert gaming_session.shift_id in session.statements[1].compile().params.values()


@pytest.mark.asyncio
async def test_session_send_retry_returns_the_existing_order_without_creating_another() -> None:
    tenant = _tenant()
    station = _station(tenant)
    order_id = uuid4()
    gaming_session = _gaming_session(tenant, station.id, uuid4(), order_id=order_id)
    existing_order = SimpleNamespace(
        id=order_id,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        total_minor=15_667,
    )
    session = _Session(
        _Result(scalar=gaming_session),
        entities={(Station, station.id): station, (Order, order_id): existing_order},
    )

    response = await gaming_router.send_session_to_pos(gaming_session.id, session, tenant)

    assert response == {"order_id": str(order_id), "amount_minor": 15_667}
    assert session.added == []
    assert session.flush_count == 0
    assert len(session.statements) == 1


@pytest.mark.asyncio
async def test_cancel_session_records_trace_and_retry_preserves_the_original_reason() -> None:
    tenant = _tenant()
    station = _station(tenant)
    shift = _shift(tenant)
    gaming_session = _gaming_session(tenant, station.id, shift.id)
    first_session = _Session(
        _Result(scalar=gaming_session),
        _Result(scalar=shift),
        entities={(Station, station.id): station},
    )

    first = await gaming_router.cancel_session(
        gaming_session.id,
        gaming_router.SessionCancel(reason="  Customer left before billing  "),
        first_session,
        tenant,
    )

    assert first.status == "cancelled"
    assert first.cancel_reason == "Customer left before billing"
    assert gaming_session.cancelled_by == tenant.user_id
    assert gaming_session.cancelled_at is not None
    assert gaming_session.end_at is not None
    assert gaming_session.billable_minutes == 0
    assert gaming_session.amount_minor == 0
    original_cancelled_at = gaming_session.cancelled_at

    # The original opener can safely recover a lost response even after the
    # exact shift has closed; scope and opener checks still run first.
    shift.status = "closed"
    retry_session = _Session(
        _Result(scalar=gaming_session),
        _Result(scalar=shift),
        entities={(Station, station.id): station},
    )
    retry = await gaming_router.cancel_session(
        gaming_session.id,
        gaming_router.SessionCancel(reason="Different retry body"),
        retry_session,
        tenant,
    )
    assert retry.cancel_reason == "Customer left before billing"
    assert gaming_session.cancelled_at == original_cancelled_at
    assert retry_session.flush_count == 0

    wrong_terminal = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=uuid4(),
        user_id=tenant.user_id,
    )
    with pytest.raises(BusinessRuleError, match="different terminal"):
        await gaming_router.cancel_session(
            gaming_session.id,
            gaming_router.SessionCancel(reason="Retry from wrong drawer"),
            _Session(
                _Result(scalar=gaming_session),
                _Result(scalar=shift),
                entities={(Station, station.id): station},
            ),
            wrong_terminal,
        )

    # Cancelling removes a billable session, so ordinary staff on the same
    # terminal still need the accountable shift opener to perform it.
    gaming_session.status = "ended"
    shift.status = "open"
    other_staff = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
    )
    with pytest.raises(BusinessRuleError, match="Only the staff member who opened this shift"):
        await gaming_router.cancel_session(
            gaming_session.id,
            gaming_router.SessionCancel(reason="Waive this charge"),
            _Session(
                _Result(scalar=gaming_session),
                _Result(scalar=shift),
                entities={(Station, station.id): station},
            ),
            other_staff,
        )

    gaming_session.status = "active"
    shift.status = "closed"
    protected_owner = _tenant(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        protected_access=True,
    )
    with pytest.raises(BusinessRuleError, match="only cancel a legacy ended session"):
        await gaming_router.cancel_session(
            gaming_session.id,
            gaming_router.SessionCancel(reason="Invalid closed-shift cleanup"),
            _Session(
                _Result(scalar=gaming_session),
                _Result(scalar=shift),
                entities={(Station, station.id): station},
            ),
            protected_owner,
        )


@pytest.mark.asyncio
async def test_start_session_replay_returns_stored_response_without_recreating() -> None:
    """A dropped-response retry of session start must replay the stored
    response, not re-run the "station already has an active session" check
    against the session it itself just created — that false rejection is
    exactly what the idempotency wiring in start_session fixes. Only the
    idempotency-key lookup should touch the database on a replay; no second
    GamingSession may be added.
    """
    tenant = _tenant()
    station = _station(tenant)
    shift = _shift(tenant)
    stored_body = {
        "id": str(uuid4()),
        "station_id": str(station.id),
        "status": "active",
        "start_at": datetime.now(UTC).isoformat(),
        "end_at": None,
        "timer_minutes": None,
        "timer_ends_at": None,
        "billable_minutes": None,
        "amount_minor": None,
        "customer_name": None,
        "customer_phone": None,
        "rate_per_hour_minor": 20_000,
        "order_id": None,
        "cancel_reason": None,
        "package_id": None,
        "extra_controllers": 0,
    }
    stored_key_row = SimpleNamespace(
        request_hash="same-hash",
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        response_status=201,
        response_body=stored_body,
    )
    session = _Session(_Result(scalar=stored_key_row))
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="session-start-retry-key",
            idempotency_request_hash="same-hash",
        )
    )

    response = await gaming_router.start_session(
        gaming_router.SessionStart(station_id=station.id, shift_id=shift.id),
        session,
        request,
        tenant,
    )

    assert response.id == UUID(stored_body["id"])
    assert response.status == "active"
    assert session.added == []
    assert len(session.statements) == 1


@pytest.mark.asyncio
async def test_order_detail_returns_held_timestamp_and_line_preparation_note() -> None:
    table_id = uuid4()
    held_at = datetime(2026, 7, 15, 11, 30, tzinfo=UTC)
    order = SimpleNamespace(
        id=uuid4(),
        invoice_no=None,
        fiscal_year=None,
        status="held",
        type="dine_in",
        table_id=table_id,
        subtotal_minor=1_000,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        cgst_minor=25,
        sgst_minor=25,
        igst_minor=0,
        cess_minor=0,
        tax_minor=50,
        round_off_minor=0,
        tip_minor=0,
        total_minor=1_050,
        delivery_via=None,
        place_of_supply_state_code="32",
        customer_name=None,
        customer_phone=None,
        customer_gstin=None,
        customer_state_code=None,
        opened_at=datetime(2026, 7, 15, 11, tzinfo=UTC),
        closed_at=None,
        invoice_issued_at=None,
        held_at=held_at,
    )
    line = SimpleNamespace(
        menu_item_id=uuid4(),
        variant_id=None,
        modifiers=None,
        qty=1,
        unit_price_minor=1_050,
        line_total_minor=1_050,
        taxable_value_minor=1_000,
        tax_rate=0.05,
        cgst_minor=25,
        sgst_minor=25,
        igst_minor=0,
        note="No onions, extra spicy",
        hsn_or_sac="996331",
    )
    item = SimpleNamespace(name="Sandwich", sku="FOOD-1", hsn_code="996331")
    session = _Session(
        _Result(rows=[(line, item)]),
        _Result(scalar=0),
        _Result(rows=[]),
        _Result(),  # points redemption lookup (none)
        entities={(Table, table_id): SimpleNamespace(code="T1")},
    )

    response = await pos_router._build_order_read(session, order)

    assert response.held_at == held_at
    assert response.paid_minor == 0
    assert response.due_minor == 1_050
    assert response.source_label == "Table T1"
    assert response.lines[0].note == "No onions, extra spicy"


@pytest.mark.asyncio
async def test_held_order_list_returns_authoritative_held_timestamp() -> None:
    tenant = _tenant()
    held_at = datetime(2026, 7, 15, 11, 30, tzinfo=UTC)
    order = SimpleNamespace(
        id=uuid4(),
        invoice_no=None,
        type="session",
        status="held",
        table_id=None,
        total_minor=15_667,
        customer_name="Cafe Guest",
        created_at=datetime(2026, 7, 15, 10, tzinfo=UTC),
        held_at=held_at,
    )
    session = _Session(
        _Result(rows=[order]),
        _Result(rows=[(order.id, 1)]),
        _Result(rows=[(order.id, "PS5 1")]),
    )

    response = await pos_router.list_orders(
        session,
        tenant,
        status_filter=["held"],
    )

    assert response[0].held_at == held_at
    assert response[0].source_label == "PS5 1"
    order_params = session.statements[0].compile().params.values()
    assert tenant.company_id in order_params
    assert tenant.branch_id in order_params
    assert tenant.terminal_id in order_params


# ---------------------------------------------------------------------------
# _incremental_restock_fraction — cumulative-safe refund-restock proportion
#
# Found by re-audit: computing each refund's restock fraction against the
# order's full taxable total (rather than cumulatively) double-restocks
# inventory across multiple partial refunds on the same order. Repro from
# the audit: taxable=900, tip=100 (total_minor=1000, fully paid). Two
# sequential Rs500 partial refunds each naively computed fraction=500/900,
# restocking 55.6% twice — 111% of what was ever deducted.
# ---------------------------------------------------------------------------


def test_incremental_restock_fraction_single_full_refund() -> None:
    fraction = pos_router._incremental_restock_fraction(
        refunded_before=0, refund_amount=900, taxable_total_minor=900
    )
    assert fraction == pytest.approx(1.0)


def test_incremental_restock_fraction_single_partial_refund() -> None:
    fraction = pos_router._incremental_restock_fraction(
        refunded_before=0, refund_amount=450, taxable_total_minor=900
    )
    assert fraction == pytest.approx(0.5)


def test_incremental_restock_fraction_two_partial_refunds_sum_to_full_not_double() -> None:
    # Exact repro from the audit: taxable=900, tip=100. First Rs500 refund,
    # then the remaining Rs500 — together they must restock exactly 100%
    # of the order, not ~111%.
    taxable_total = 900

    first = pos_router._incremental_restock_fraction(
        refunded_before=0, refund_amount=500, taxable_total_minor=taxable_total
    )
    assert first == pytest.approx(500 / 900)

    second = pos_router._incremental_restock_fraction(
        refunded_before=500, refund_amount=500, taxable_total_minor=taxable_total
    )
    # Only the remaining 400/900 should restock this time, not another 500/900.
    assert second == pytest.approx(400 / 900)

    assert first + second == pytest.approx(1.0)


def test_incremental_restock_fraction_three_way_split_sums_to_one() -> None:
    taxable_total = 900
    fractions = []
    refunded_so_far = 0
    for amount in (300, 300, 300):
        fractions.append(
            pos_router._incremental_restock_fraction(
                refunded_before=refunded_so_far,
                refund_amount=amount,
                taxable_total_minor=taxable_total,
            )
        )
        refunded_so_far += amount
    assert sum(fractions) == pytest.approx(1.0)


def test_incremental_restock_fraction_clamps_when_refund_exceeds_taxable_total() -> None:
    # A refund can legitimately exceed the taxable total if it also covers
    # the tip (Refund.amount_minor isn't split from tip) — must clamp at
    # 100% restocked, never go over or negative on a later call.
    fraction = pos_router._incremental_restock_fraction(
        refunded_before=0, refund_amount=1000, taxable_total_minor=900
    )
    assert fraction == pytest.approx(1.0)

    # A further refund attempt after the order is already fully restocked
    # must restock nothing more (not go negative).
    fraction = pos_router._incremental_restock_fraction(
        refunded_before=1000, refund_amount=100, taxable_total_minor=900
    )
    assert fraction == 0.0


def test_incremental_restock_fraction_zero_taxable_total_is_a_noop() -> None:
    assert (
        pos_router._incremental_restock_fraction(
            refunded_before=0, refund_amount=500, taxable_total_minor=0
        )
        == 0.0
    )
