from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import text

from app.api.v1.pos.router import _settle_pos_refund
from app.models import (
    Branch,
    Customer,
    CustomerMembership,
    FinanceSourceCorrection,
    ManualCollection,
    MembershipPayment,
    MembershipPaymentCompletion,
    MembershipPaymentProviderAction,
    MembershipPaymentRequest,
    MembershipRefund,
    MembershipRefundCompletion,
    MembershipRefundProviderAction,
    MembershipRefundSettlement,
    MembershipTier,
    Order,
    Payment,
    PosRefundCashHandoff,
    PosRefundCashHandoffCompletion,
    PosRefundProviderPayoutStart,
    PosRefundProviderSettlement,
    PosRefundRequest,
    Refund,
    Shift,
    Terminal,
)
from app.services.integrations.google_sheets_collections import (
    CollectionReadinessBlocker,
    UnsupportedCollectionRail,
    calculate_daily_collections,
)

TIMEZONE = "Asia/Kolkata"


def _event_time(day: date, *, hour: int = 12) -> datetime:
    return datetime.combine(day, time(hour=hour), ZoneInfo(TIMEZONE)).astimezone(UTC)


async def _terminal(session, branch: Branch, label: str) -> Terminal:
    terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name=f"Collections {label}",
        device_id=f"collections-{uuid4()}",
        is_active=False,
    )
    session.add(terminal)
    await session.flush()
    return terminal


async def _shift(
    session,
    seed_owner: dict,
    terminal: Terminal,
    *,
    opened_at: datetime,
    expected_minor: int = 20_000,
) -> Shift:
    shift = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=terminal.branch_id,
        terminal_id=terminal.id,
        opened_by=seed_owner["owner"].id,
        opened_at=opened_at,
        opening_float_minor=expected_minor,
        expected_minor=expected_minor,
        status="open",
    )
    session.add(shift)
    await session.flush()
    return shift


async def _close_shift(session, shift: Shift, owner_id: UUID) -> None:
    shift.closed_by = owner_id
    shift.closed_at = shift.opened_at + timedelta(hours=8)
    shift.counted_minor = shift.expected_minor
    shift.variance_minor = 0
    shift.status = "closed"
    await session.flush()


async def _paid_order(
    session,
    seed_owner: dict,
    shift: Shift,
    payments: tuple[tuple[str, int], ...],
) -> Order:
    total = sum(amount for _, amount in payments)
    at = shift.opened_at + timedelta(hours=1)
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="takeaway",
        status="paid",
        subtotal_minor=total,
        total_minor=total,
        opened_at=at - timedelta(minutes=10),
        closed_at=at,
        invoice_issued_at=at,
        invoice_no=f"T/C/{uuid4().hex[:12]}",
        fiscal_year="2026-27",
    )
    session.add(order)
    await session.flush()
    session.add_all(
        [
            Payment(
                id=uuid4(),
                order_id=order.id,
                shift_id=shift.id,
                method=method,
                amount_minor=amount,
                tendered_minor=amount if method == "cash" else None,
                change_minor=0 if method == "cash" else None,
                ref_external=None if method == "cash" else f"ref-{uuid4()}",
                paid_at=at,
            )
            for method, amount in payments
        ]
    )
    await session.flush()
    return order


async def _membership_payment(
    session,
    seed_owner: dict,
    shift: Shift,
    *,
    amount_minor: int,
    method: str = "upi",
) -> tuple[CustomerMembership, MembershipPayment]:
    at = shift.opened_at + timedelta(hours=2)
    customer = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Collections test customer",
        phone=f"7{uuid4().int % 10**9:09d}",
    )
    tier = MembershipTier(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code=f"C{uuid4().hex[:8]}",
        name="Collections test tier",
        monthly_price_minor=amount_minor,
    )
    session.add_all([customer, tier])
    await session.flush()
    request = MembershipPaymentRequest(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        customer_id=customer.id,
        tier_id=tier.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        billing_cycle="monthly",
        method=method,
        amount_minor=amount_minor,
        customer_name_snapshot=customer.name,
        customer_phone_snapshot=customer.phone,
        tier_code_snapshot=tier.code,
        tier_name_snapshot=tier.name,
        accepted_at=at,
        prepared_by=seed_owner["owner"].id,
        client_action_id=f"membership-prepare:{uuid4()}",
        idempotency_key=f"membership-prepare:{uuid4()}",
    )
    session.add(request)
    await session.flush()
    action = MembershipPaymentProviderAction(
        id=uuid4(),
        company_id=request.company_id,
        request_id=request.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        method=method,
        started_at=at,
        started_by=seed_owner["owner"].id,
        idempotency_key=f"membership-provider-action:{uuid4()}",
    )
    session.add(action)
    await session.flush()
    external_reference = f"membership-{uuid4()}"
    completion = MembershipPaymentCompletion(
        id=uuid4(),
        company_id=request.company_id,
        request_id=request.id,
        cash_collection_id=None,
        provider_action_id=action.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        method=method,
        amount_minor=amount_minor,
        completed_at=at,
        completed_by=seed_owner["owner"].id,
        external_reference=external_reference,
        provider_evidence_reconciled=True,
        evidence_occurred_at=at,
        evidence_time_untrusted=False,
        action_takeover_confirmed=False,
        action_takeover_reason=None,
        idempotency_key=f"membership-completion:{uuid4()}",
    )
    session.add(completion)
    await session.flush()
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=customer.id,
        tier_id=tier.id,
        billing_cycle="monthly",
        starts_at=at,
        expires_at=at + timedelta(days=30),
        auto_renew=False,
        amount_paid_minor=amount_minor,
    )
    session.add(membership)
    await session.flush()
    payment = MembershipPayment(
        id=uuid4(),
        company_id=request.company_id,
        membership_id=membership.id,
        request_id=request.id,
        completion_id=completion.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        method=method,
        amount_minor=amount_minor,
        paid_at=at,
        created_by=seed_owner["owner"].id,
        idempotency_key=f"membership-payment:{uuid4()}",
        receipt_no=f"M/C/{uuid4().hex[:12]}",
        receipt_fiscal_year="2026-27",
        receipt_issued_at=at,
        external_reference=external_reference,
        provider_evidence_reconciled=True,
        evidence_occurred_at=at,
        evidence_time_untrusted=False,
        customer_spend_reconciled=False,
        action_takeover_confirmed=False,
        action_takeover_reason=None,
    )
    session.add(payment)
    await session.flush()
    return membership, payment


async def _membership_refund(
    session,
    seed_owner: dict,
    membership: CustomerMembership,
    payment: MembershipPayment,
    settlement_shift: Shift,
) -> None:
    at = settlement_shift.opened_at + timedelta(hours=1)
    membership.revoked_at = at
    refund = MembershipRefund(
        id=uuid4(),
        company_id=payment.company_id,
        payment_id=payment.id,
        branch_id=settlement_shift.branch_id,
        terminal_id=settlement_shift.terminal_id,
        shift_id=settlement_shift.id,
        method=payment.method,
        amount_minor=payment.amount_minor,
        accepted_at=at,
        approved_by=seed_owner["owner"].id,
        idempotency_key=f"membership-refund:{uuid4()}",
        reason="Collections settlement test",
    )
    session.add(refund)
    await session.flush()
    action = MembershipRefundProviderAction(
        id=uuid4(),
        company_id=payment.company_id,
        refund_id=refund.id,
        branch_id=settlement_shift.branch_id,
        terminal_id=settlement_shift.terminal_id,
        shift_id=settlement_shift.id,
        method=payment.method,
        started_at=at,
        started_by=seed_owner["owner"].id,
        idempotency_key=f"membership-refund-action:{uuid4()}",
    )
    session.add(action)
    await session.flush()
    external_reference = f"membership-refund-{uuid4()}"
    completion = MembershipRefundCompletion(
        id=uuid4(),
        company_id=payment.company_id,
        refund_id=refund.id,
        cash_handoff_id=None,
        provider_action_id=action.id,
        legacy_attempt_resolution_id=None,
        branch_id=settlement_shift.branch_id,
        terminal_id=settlement_shift.terminal_id,
        shift_id=settlement_shift.id,
        method=payment.method,
        amount_minor=payment.amount_minor,
        completed_at=at,
        completed_by=seed_owner["owner"].id,
        external_reference=external_reference,
        provider_evidence_reconciled=True,
        evidence_occurred_at=at,
        evidence_time_untrusted=False,
        action_takeover_confirmed=False,
        action_takeover_reason=None,
        idempotency_key=f"membership-refund-completion:{uuid4()}",
    )
    session.add(completion)
    await session.flush()
    session.add(
        MembershipRefundSettlement(
            id=uuid4(),
            company_id=payment.company_id,
            refund_id=refund.id,
            payment_id=payment.id,
            completion_id=completion.id,
            branch_id=settlement_shift.branch_id,
            terminal_id=settlement_shift.terminal_id,
            shift_id=settlement_shift.id,
            method=payment.method,
            amount_minor=payment.amount_minor,
            settled_at=at,
            settled_by=seed_owner["owner"].id,
            idempotency_key=f"membership-refund-settlement:{uuid4()}",
            receipt_no=f"MR/C/{uuid4().hex[:10]}",
            receipt_fiscal_year="2026-27",
            receipt_issued_at=at,
            external_ref=external_reference,
            provider_evidence_reconciled=True,
            evidence_occurred_at=at,
            evidence_time_untrusted=False,
            customer_spend_reconciled=False,
            action_takeover_confirmed=False,
            action_takeover_reason=None,
        )
    )
    await session.flush()


async def _pos_refund(
    session,
    seed_owner: dict,
    order: Order,
    settlement_shift: Shift,
    *,
    method: str,
    amount_minor: int,
) -> None:
    at = settlement_shift.opened_at + timedelta(hours=2)
    request = PosRefundRequest(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        order_id=order.id,
        branch_id=settlement_shift.branch_id,
        terminal_id=settlement_shift.terminal_id,
        shift_id=settlement_shift.id,
        approved_by=seed_owner["owner"].id,
        manager_override_user_id=None,
        reason_code="CUSTOMER_REQUEST",
        amount_minor=amount_minor,
        mode="cash" if method == "cash" else "original",
        settlement_method=method,
        order_paid_snapshot_minor=order.total_minor,
        order_refundable_snapshot_minor=order.total_minor,
        accepted_at=at,
        external_reference=None,
        provider_settled_at=None,
        client_action_id=f"pos-refund:{uuid4()}",
        idempotency_key=f"pos-refund:{uuid4()}",
        note="Collections settlement test",
    )
    session.add(request)
    await session.flush()
    external_reference = None
    provider_settled_at = None
    if method == "cash":
        handoff = PosRefundCashHandoff(
            id=uuid4(),
            company_id=request.company_id,
            refund_request_id=request.id,
            branch_id=settlement_shift.branch_id,
            terminal_id=settlement_shift.terminal_id,
            shift_id=settlement_shift.id,
            started_at=at,
            started_by=seed_owner["owner"].id,
            idempotency_key=f"pos-refund-handoff:{uuid4()}",
        )
        session.add(handoff)
        await session.flush()
        session.add(
            PosRefundCashHandoffCompletion(
                id=uuid4(),
                company_id=request.company_id,
                refund_request_id=request.id,
                branch_id=settlement_shift.branch_id,
                terminal_id=settlement_shift.terminal_id,
                shift_id=settlement_shift.id,
                handed_over_at=at,
                recorded_at=at,
                recorded_by=seed_owner["owner"].id,
                captured_time_reconciled=True,
                idempotency_key=f"pos-refund-complete:{uuid4()}",
            )
        )
    else:
        start = PosRefundProviderPayoutStart(
            id=uuid4(),
            company_id=request.company_id,
            refund_request_id=request.id,
            branch_id=settlement_shift.branch_id,
            terminal_id=settlement_shift.terminal_id,
            shift_id=settlement_shift.id,
            started_at=at,
            started_by=seed_owner["owner"].id,
            idempotency_key=f"pos-refund-provider-start:{uuid4()}",
        )
        session.add(start)
        await session.flush()
        external_reference = f"pos-refund-{uuid4()}"
        provider_settled_at = at
        session.add(
            PosRefundProviderSettlement(
                id=uuid4(),
                company_id=request.company_id,
                refund_request_id=request.id,
                branch_id=settlement_shift.branch_id,
                terminal_id=settlement_shift.terminal_id,
                shift_id=settlement_shift.id,
                settlement_method=method,
                external_reference=external_reference,
                provider_settled_at=at,
                settled_by=seed_owner["owner"].id,
                captured_time_reconciled=True,
                provider_evidence_reconciled=True,
                idempotency_key=f"pos-refund-provider-complete:{uuid4()}",
                created_at=at,
            )
        )
    await session.flush()
    await _settle_pos_refund(
        session,
        refund_request=request,
        order=order,
        shift=settlement_shift,
        settled_at=at,
        accounting_finalized_at=at,
        client_occurred_at=at,
        captured_time_reconciled=True,
        provider_evidence_reconciled=True if method != "cash" else None,
        settled_by=seed_owner["owner"].id,
        idempotency_key=f"pos-refund-finalize:{uuid4()}",
        external_reference=external_reference,
        provider_settled_at=provider_settled_at,
    )
    await session.flush()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_collections_group_by_shift_opening_day_and_tenant_scope(session, seed_owner) -> None:
    target_day = datetime.now(ZoneInfo(TIMEZONE)).date() - timedelta(days=10)
    branch = seed_owner["branch"]
    terminal_a = await _terminal(session, branch, "A")
    terminal_b = await _terminal(session, branch, "B")
    shift_a = await _shift(
        session,
        seed_owner,
        terminal_a,
        opened_at=_event_time(target_day, hour=0) + timedelta(minutes=30),
    )
    shift_b = await _shift(
        session, seed_owner, terminal_b, opened_at=_event_time(target_day, hour=22)
    )
    await _paid_order(session, seed_owner, shift_a, (("cash", 1_000), ("upi", 2_000)))
    await _paid_order(session, seed_owner, shift_b, (("cash", 500),))
    await _membership_payment(session, seed_owner, shift_b, amount_minor=700)
    session.add(
        ManualCollection(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            branch_id=branch.id,
            shift_id=None,
            business_date=target_day,
            method="upi",
            amount_minor=300,
            source_kind="manual_daily",
            source_ref=f"test-{uuid4()}",
            idempotency_key=f"manual-collection:{uuid4()}",
            request_hash="a" * 64,
            created_by=seed_owner["owner"].id,
            source_integrity_revision=1,
        )
    )
    await session.flush()

    source_terminal = await _terminal(session, branch, "correction-source")
    source_shift = await _shift(
        session,
        seed_owner,
        source_terminal,
        opened_at=_event_time(target_day - timedelta(days=1)),
    )
    manual_id = uuid4()
    session.add(
        ManualCollection(
            id=manual_id,
            company_id=seed_owner["company"].id,
            branch_id=branch.id,
            shift_id=source_shift.id,
            business_date=target_day - timedelta(days=1),
            method="cash",
            amount_minor=400,
            source_kind="manual_daily",
            source_ref=f"test-{uuid4()}",
            idempotency_key=f"manual-collection:{uuid4()}",
            request_hash="b" * 64,
            created_by=seed_owner["owner"].id,
            source_integrity_revision=1,
        )
    )
    await session.flush()
    await _close_shift(session, source_shift, seed_owner["owner"].id)
    session.add(
        FinanceSourceCorrection(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            branch_id=branch.id,
            source_type="manual_collection",
            manual_collection_id=manual_id,
            original_shift_id=source_shift.id,
            settlement_shift_id=shift_b.id,
            amount_minor=400,
            idempotency_key=f"manual-collection-correction:{uuid4()}",
            request_hash="c" * 64,
            corrected_by=seed_owner["owner"].id,
            reason="Collections correction test",
        )
    )
    await session.flush()

    foreign_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Foreign collections branch",
        invoice_series_code="FZ",
    )
    session.add(foreign_branch)
    await session.flush()
    foreign_terminal = await _terminal(session, foreign_branch, "foreign")
    foreign_shift = await _shift(
        session, seed_owner, foreign_terminal, opened_at=_event_time(target_day)
    )
    await _paid_order(session, seed_owner, foreign_shift, (("cash", 99_999),))
    await _close_shift(session, foreign_shift, seed_owner["owner"].id)
    await _close_shift(session, shift_a, seed_owner["owner"].id)
    await _close_shift(session, shift_b, seed_owner["owner"].id)

    (snapshot,) = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=branch.id,
        timezone_name=TIMEZONE,
        start_date=target_day,
        end_date=target_day,
    )
    assert snapshot.ready
    assert snapshot.cash_minor == 1_100
    assert snapshot.upi_minor == 3_000
    assert snapshot.total_minor == 4_100
    assert snapshot.shift_count == 2
    assert snapshot.open_shift_count == 0
    assert snapshot.unsupported_rails == ()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_collections_use_settlement_shift_and_block_each_unsupported_rail(
    session, seed_owner
) -> None:
    target_day = datetime.now(ZoneInfo(TIMEZONE)).date() - timedelta(days=8)
    source_day = target_day - timedelta(days=1)
    branch = seed_owner["branch"]
    source_terminal = await _terminal(session, branch, "refund-source")
    source_shift = await _shift(
        session, seed_owner, source_terminal, opened_at=_event_time(source_day)
    )
    wallet_order = await _paid_order(session, seed_owner, source_shift, (("wallet", 2_000),))
    cash_order = await _paid_order(session, seed_owner, source_shift, (("cash", 4_000),))
    membership, membership_payment = await _membership_payment(
        session, seed_owner, source_shift, amount_minor=800
    )
    await _close_shift(session, source_shift, seed_owner["owner"].id)
    target_shift = await _shift(
        session, seed_owner, source_terminal, opened_at=_event_time(target_day)
    )

    await _paid_order(session, seed_owner, target_shift, (("card", 1_500),))
    await _paid_order(session, seed_owner, target_shift, (("cash", 1_000),))
    await _paid_order(session, seed_owner, target_shift, (("upi", 800),))
    await _pos_refund(
        session,
        seed_owner,
        wallet_order,
        target_shift,
        method="wallet",
        amount_minor=1_500,
    )
    await _pos_refund(
        session,
        seed_owner,
        cash_order,
        target_shift,
        method="cash",
        amount_minor=2_500,
    )
    await _membership_refund(session, seed_owner, membership, membership_payment, target_shift)

    # Recreate a linked historical credit-note row: it is a refund fact, but
    # no money left on any collection rail.
    await session.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))
    await session.execute(
        text("ALTER TABLE refunds DROP CONSTRAINT ck_refund_forward_write_linkage")
    )
    await session.execute(text("ALTER TABLE refunds DISABLE TRIGGER trg_refunds_transition_guard"))
    await session.execute(
        text("ALTER TABLE refunds DISABLE TRIGGER trg_refunds_final_order_balance")
    )
    session.add(
        Refund(
            id=uuid4(),
            order_id=cash_order.id,
            company_id=seed_owner["company"].id,
            branch_id=branch.id,
            terminal_id=target_shift.terminal_id,
            settlement_shift_id=target_shift.id,
            approved_by=seed_owner["owner"].id,
            reason_code="LEGACY_CREDIT_NOTE",
            amount_minor=999,
            mode="credit_note",
            settlement_method="store_credit",
            settled_at=target_shift.opened_at,
            settled_by=seed_owner["owner"].id,
        )
    )
    await session.flush()
    await session.execute(
        text(
            "ALTER TABLE refunds ADD CONSTRAINT ck_refund_forward_write_linkage "
            "CHECK (request_id IS NOT NULL) NOT VALID"
        )
    )
    await session.execute(text("ALTER TABLE refunds ENABLE TRIGGER trg_refunds_transition_guard"))
    await session.execute(
        text("ALTER TABLE refunds ENABLE TRIGGER trg_refunds_final_order_balance")
    )
    await _close_shift(session, target_shift, seed_owner["owner"].id)

    (snapshot,) = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=branch.id,
        timezone_name=TIMEZONE,
        start_date=target_day,
        end_date=target_day,
    )
    assert snapshot.cash_minor == -1_500
    assert snapshot.upi_minor == 0
    assert snapshot.total_minor == -1_500
    assert snapshot.unsupported_rails == (
        UnsupportedCollectionRail("card", 1_500),
        UnsupportedCollectionRail("wallet", -1_500),
    )
    assert snapshot.blockers == (CollectionReadinessBlocker.UNSUPPORTED_RAIL,)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_collections_expose_open_shift_and_legacy_refund_blockers(
    session, seed_owner
) -> None:
    target_day = datetime.now(ZoneInfo(TIMEZONE)).date() - timedelta(days=6)
    branch = seed_owner["branch"]
    terminal = await _terminal(session, branch, "blocked")
    shift = await _shift(session, seed_owner, terminal, opened_at=_event_time(target_day))
    order = await _paid_order(session, seed_owner, shift, (("cash", 1_000),))
    await session.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))
    await session.execute(
        text("ALTER TABLE refunds DROP CONSTRAINT ck_refund_forward_write_linkage")
    )
    await session.execute(
        text("ALTER TABLE refunds DISABLE TRIGGER trg_refunds_final_order_balance")
    )
    session.add(
        Refund(
            id=uuid4(),
            order_id=order.id,
            approved_by=seed_owner["owner"].id,
            reason_code="LEGACY_HISTORY",
            amount_minor=100,
            mode="cash",
            created_at=_event_time(target_day),
        )
    )
    await session.flush()
    await session.execute(
        text(
            "ALTER TABLE refunds ADD CONSTRAINT ck_refund_forward_write_linkage "
            "CHECK (request_id IS NOT NULL) NOT VALID"
        )
    )
    await session.execute(
        text("ALTER TABLE refunds ENABLE TRIGGER trg_refunds_final_order_balance")
    )

    (snapshot,) = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=branch.id,
        timezone_name=TIMEZONE,
        start_date=target_day,
        end_date=target_day,
    )
    assert snapshot.legacy_refund_count == 1
    assert snapshot.blockers == (
        CollectionReadinessBlocker.OPEN_SHIFT,
        CollectionReadinessBlocker.LEGACY_REFUND_WITHOUT_SETTLEMENT_SCOPE,
    )


@pytest.mark.asyncio
async def test_collections_reject_invalid_or_unbounded_ranges() -> None:
    async def call(start_date: date, end_date: date, timezone_name: str = TIMEZONE):
        return await calculate_daily_collections(
            None,
            company_id=uuid4(),
            branch_id=uuid4(),
            timezone_name=timezone_name,
            start_date=start_date,
            end_date=end_date,
        )

    day = date.today()
    with pytest.raises(ValueError, match="must not exceed"):
        await call(day, day - timedelta(days=1))
    with pytest.raises(ValueError, match="cannot exceed"):
        await call(day, day + timedelta(days=31))
    with pytest.raises(ValueError, match="valid IANA"):
        await call(day, day, "not/a-zone")


@pytest.mark.integration
@pytest.mark.asyncio
async def test_backdated_cash_on_an_open_shift_blocks_its_explicit_day(session, seed_owner) -> None:
    today = datetime.now(ZoneInfo(TIMEZONE)).date()
    target_day = today - timedelta(days=12)
    terminal = await _terminal(session, seed_owner["branch"], "backdated-open")
    shift = await _shift(
        session,
        seed_owner,
        terminal,
        opened_at=_event_time(today),
    )
    session.add(
        ManualCollection(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id,
            shift_id=shift.id,
            business_date=target_day,
            method="cash",
            amount_minor=750,
            source_kind="manual_daily",
            source_ref=f"test-{uuid4()}",
            idempotency_key=f"manual-collection:{uuid4()}",
            request_hash="d" * 64,
            created_by=seed_owner["owner"].id,
            source_integrity_revision=1,
        )
    )
    await session.flush()

    (snapshot,) = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        timezone_name=TIMEZONE,
        start_date=target_day,
        end_date=target_day,
    )

    assert snapshot.cash_minor == 750
    assert snapshot.open_shift_count == 1
    assert snapshot.blockers == (CollectionReadinessBlocker.OPEN_SHIFT,)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_unsupported_rail_blocks_even_when_movements_net_to_zero(session, seed_owner) -> None:
    target_day = datetime.now(ZoneInfo(TIMEZONE)).date() - timedelta(days=9)
    terminal = await _terminal(session, seed_owner["branch"], "zero-rail")
    shift = await _shift(session, seed_owner, terminal, opened_at=_event_time(target_day))
    order = await _paid_order(session, seed_owner, shift, (("wallet", 1_000),))
    await _pos_refund(
        session,
        seed_owner,
        order,
        shift,
        method="wallet",
        amount_minor=1_000,
    )
    await _close_shift(session, shift, seed_owner["owner"].id)

    (snapshot,) = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        timezone_name=TIMEZONE,
        start_date=target_day,
        end_date=target_day,
    )

    assert snapshot.total_minor == 0
    assert snapshot.unsupported_rails == (UnsupportedCollectionRail("wallet", 0),)
    assert snapshot.blockers == (CollectionReadinessBlocker.UNSUPPORTED_RAIL,)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_shift_bounds_follow_dst_local_midnights(session, seed_owner) -> None:
    timezone_name = "America/New_York"
    target_day = date(2026, 3, 8)
    timezone = ZoneInfo(timezone_name)
    first_terminal = await _terminal(session, seed_owner["branch"], "dst-first")
    last_terminal = await _terminal(session, seed_owner["branch"], "dst-last")
    outside_terminal = await _terminal(session, seed_owner["branch"], "dst-outside")
    first = await _shift(
        session,
        seed_owner,
        first_terminal,
        opened_at=datetime.combine(target_day, time(0, 30), timezone).astimezone(UTC),
    )
    last = await _shift(
        session,
        seed_owner,
        last_terminal,
        opened_at=datetime.combine(target_day, time(23, 30), timezone).astimezone(UTC),
    )
    outside = await _shift(
        session,
        seed_owner,
        outside_terminal,
        opened_at=datetime.combine(target_day + timedelta(days=1), time(0), timezone).astimezone(
            UTC
        ),
    )
    await _paid_order(session, seed_owner, first, (("cash", 100),))
    await _paid_order(session, seed_owner, last, (("upi", 200),))
    await _paid_order(session, seed_owner, outside, (("cash", 10_000),))
    for shift in (first, last, outside):
        await _close_shift(session, shift, seed_owner["owner"].id)

    (snapshot,) = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        timezone_name=timezone_name,
        start_date=target_day,
        end_date=target_day,
        now=datetime(2026, 3, 10, tzinfo=UTC),
    )

    assert snapshot.shift_count == 2
    assert snapshot.cash_minor == 100
    assert snapshot.upi_minor == 200


@pytest.mark.integration
@pytest.mark.asyncio
async def test_actual_manual_correction_is_counted_on_its_settlement_day(
    session, seed_owner
) -> None:
    source_day = datetime.now(ZoneInfo(TIMEZONE)).date() - timedelta(days=15)
    settlement_day = source_day + timedelta(days=1)
    terminal = await _terminal(session, seed_owner["branch"], "void-correction")
    source_shift = await _shift(session, seed_owner, terminal, opened_at=_event_time(source_day))
    manual = ManualCollection(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        shift_id=source_shift.id,
        business_date=source_day,
        method="cash",
        amount_minor=600,
        source_kind="manual_daily",
        source_ref=f"test-{uuid4()}",
        idempotency_key=f"manual-collection:{uuid4()}",
        request_hash="e" * 64,
        created_by=seed_owner["owner"].id,
        source_integrity_revision=1,
    )
    session.add(manual)
    await session.flush()
    await _close_shift(session, source_shift, seed_owner["owner"].id)
    settlement_shift = await _shift(
        session, seed_owner, terminal, opened_at=_event_time(settlement_day)
    )
    session.add(
        FinanceSourceCorrection(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id,
            source_type="manual_collection",
            manual_collection_id=manual.id,
            original_shift_id=source_shift.id,
            settlement_shift_id=settlement_shift.id,
            amount_minor=600,
            idempotency_key=f"manual-collection-correction:{uuid4()}",
            request_hash="f" * 64,
            corrected_by=seed_owner["owner"].id,
            reason="Void and correction rescan",
        )
    )
    await _close_shift(session, settlement_shift, seed_owner["owner"].id)
    await session.flush()

    source_snapshot, settlement_snapshot = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        timezone_name=TIMEZONE,
        start_date=source_day,
        end_date=settlement_day,
    )

    assert source_snapshot.cash_minor == 600
    assert settlement_snapshot.cash_minor == -600
    assert settlement_snapshot.ready


@pytest.mark.integration
@pytest.mark.asyncio
async def test_pre_workflow_membership_payment_remains_collection_evidence(
    session, seed_owner
) -> None:
    target_day = datetime.now(ZoneInfo(TIMEZONE)).date() - timedelta(days=18)
    terminal = await _terminal(session, seed_owner["branch"], "legacy-membership")
    shift = await _shift(session, seed_owner, terminal, opened_at=_event_time(target_day))
    _membership, payment = await _membership_payment(session, seed_owner, shift, amount_minor=900)
    await _close_shift(session, shift, seed_owner["owner"].id)

    # Recreate one immutable pre-0035 payment whose later workflow-link
    # columns did not exist. The payment row itself remains fully scoped
    # financial evidence and must not disappear from the summary.
    await session.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))
    await session.execute(
        text(
            "ALTER TABLE membership_payments DROP CONSTRAINT ck_membership_payment_workflow_linkage"
        )
    )
    await session.execute(text("ALTER TABLE membership_payments DISABLE TRIGGER USER"))
    await session.execute(
        text(
            "UPDATE membership_payments SET request_id = NULL, completion_id = NULL "
            "WHERE id = :payment_id"
        ),
        {"payment_id": payment.id},
    )
    await session.execute(
        text(
            "ALTER TABLE membership_payments ADD CONSTRAINT "
            "ck_membership_payment_workflow_linkage "
            "CHECK (request_id IS NOT NULL AND completion_id IS NOT NULL) NOT VALID"
        )
    )
    await session.execute(text("ALTER TABLE membership_payments ENABLE TRIGGER USER"))

    (snapshot,) = await calculate_daily_collections(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        timezone_name=TIMEZONE,
        start_date=target_day,
        end_date=target_day,
    )
    assert snapshot.upi_minor == 900
    assert snapshot.ready
