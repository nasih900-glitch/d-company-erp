"""PostgreSQL proof for recoverable, two-stage POS refunds."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, or_, select, text, update
from sqlalchemy.exc import DBAPIError

from app.api.v1.insights.router import _period_stats
from app.api.v1.reports.router import _to_dto
from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.models import (
    AuditLog,
    Batch,
    Company,
    Customer,
    CustomerSpendReconciliation,
    IdempotencyKey,
    Ingredient,
    InvoiceCounter,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    OrderLoyaltySettlement,
    Payment,
    PointsRedemption,
    PosRefundCashHandoff,
    PosRefundCashHandoffCompletion,
    PosRefundEvidenceReconciliation,
    PosRefundProviderPayoutStart,
    PosRefundProviderSettlement,
    PosRefundRequest,
    PosRefundWithdrawal,
    Recipe,
    RecipeLine,
    Refund,
    RefundLoyaltyAdjustment,
    Shift,
    StockMovement,
    User,
)
from app.services.accounting.accounts import (
    COST_OF_GOODS_SOLD,
    INVENTORY,
    SALES_REVENUE,
)
from app.services.accounting.ledger import build_operational_ledger
from app.services.audit.recorder import install_audit_listeners
from app.services.reports import ReportsAggregator

_POS_APPEND_ONLY_TRIGGERS = (
    ("payments", "trg_payments_immutable"),
    ("payments", "trg_payments_final_order_balance"),
    ("orders", "trg_orders_paid_source_integrity"),
    ("orders", "trg_orders_final_payment_balance"),
    ("order_lines", "trg_order_lines_paid_source_integrity"),
    ("refunds", "trg_refunds_immutable"),
    ("refunds", "trg_refunds_final_order_balance"),
    ("pos_refund_requests", "trg_pos_refund_requests_immutable"),
    ("pos_refund_cash_handoffs", "trg_pos_refund_cash_handoffs_immutable"),
    (
        "pos_refund_cash_handoff_completions",
        "trg_pos_refund_cash_completions_immutable",
    ),
    (
        "pos_refund_provider_payout_starts",
        "trg_pos_refund_provider_starts_immutable",
    ),
    (
        "pos_refund_provider_settlements",
        "trg_pos_refund_provider_settlements_immutable",
    ),
    ("pos_refund_withdrawals", "trg_pos_refund_withdrawals_immutable"),
    (
        "customer_spend_reconciliations",
        "trg_customer_spend_reconciliations_immutable",
    ),
    (
        "pos_refund_evidence_reconciliations",
        "trg_pos_refund_evidence_reconciliations_immutable",
    ),
    (
        "pos_refund_workflow_guards",
        "trg_pos_refund_workflow_guards_internal",
    ),
    (
        "order_loyalty_settlements",
        "trg_order_loyalty_settlements_immutable",
    ),
    (
        "refund_loyalty_adjustments",
        "trg_refund_loyalty_adjustments_immutable",
    ),
)


async def _set_disposable_cleanup_triggers(session, *, enabled: bool) -> None:
    """Explicit test-only DDL; production has no caller-settable bypass."""
    verb = "ENABLE" if enabled else "DISABLE"
    for table_name, trigger_name in _POS_APPEND_ONLY_TRIGGERS:
        await session.execute(
            text(f"ALTER TABLE {table_name} {verb} TRIGGER {trigger_name}")
        )


@pytest_asyncio.fixture(autouse=True)
async def require_pos_refund_schema(session) -> None:
    try:
        exists = (
            await session.execute(
                text(
                    "SELECT to_regclass('public.pos_refund_requests') IS NOT NULL "
                    "AND to_regclass('public.pos_refund_provider_payout_starts') "
                    "IS NOT NULL "
                    "AND to_regclass('public.pos_refund_cash_handoff_completions') "
                    "IS NOT NULL "
                    "AND to_regclass('public.order_loyalty_settlements') "
                    "IS NOT NULL "
                    "AND to_regclass('public.refund_loyalty_adjustments') "
                    "IS NOT NULL "
                    "AND EXISTS (SELECT 1 FROM information_schema.columns "
                    "WHERE table_name='refunds' "
                    "AND column_name='captured_time_reconciled') "
                    "AND EXISTS (SELECT 1 FROM pg_trigger "
                    "WHERE tgname='trg_payments_immutable' AND NOT tgisinternal)"
                )
            )
        ).scalar_one()
    except Exception as exc:
        pytest.skip(f"local PostgreSQL unavailable: {exc}")
    if not exists:
        pytest.skip("test database is not migrated through 0048")


@dataclass(frozen=True, slots=True)
class _RefundCase:
    company_id: UUID
    branch_id: UUID
    terminal_id: UUID
    owner_id: UUID
    owner_auth_version: int
    customer_id: UUID
    shift_id: UUID
    order_id: UUID
    captured_at: datetime
    amount_minor: int
    opening_float_minor: int
    payment_method: str
    token: str


async def _seed_case(
    session,
    seed_owner,
    *,
    payment_method: str,
    invoice_issued: bool = True,
    defer_settlement: bool = False,
    points_redeemed_minor: int = 0,
) -> _RefundCase:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC).replace(microsecond=0)
    amount = 24_500
    opening = 100_000
    sale_at = now - timedelta(minutes=20)
    customer = Customer(
        id=uuid4(),
        company_id=company.id,
        name="POS refund customer",
        phone=f"8{uuid4().int % 10**9:09d}",
        # A deferred case is still an open cart.  Do not pre-credit customer
        # history before the payment endpoint performs the authoritative sale
        # finalisation.
        visit_count=0 if defer_settlement else 1,
        total_spent_minor=0 if defer_settlement else amount,
        loyalty_points=0,
        lifetime_gaming_points_earned=0,
        first_visit_at=now,
        last_visit_at=now,
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=opening,
        expected_minor=opening + (
            amount if payment_method == "cash" and not defer_settlement else 0
        ),
        status="open",
    )
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        customer_id=customer.id,
        customer_name=customer.name,
        customer_phone=customer.phone,
        type="takeaway",
        status="open" if defer_settlement else "paid",
        subtotal_minor=amount,
        total_minor=amount,
        points_redeemed_minor=points_redeemed_minor,
        opened_at=now - timedelta(minutes=30),
        closed_at=None if defer_settlement else sale_at,
        invoice_issued_at=(
            sale_at if invoice_issued and not defer_settlement else None
        ),
        invoice_no=(
            None
            if defer_settlement
            else f"D/RF/26-27/{uuid4().int % 100000:05d}"
        ),
        fiscal_year=None if defer_settlement else "2026-27",
    )
    payment = Payment(
        id=uuid4(),
        order_id=order.id,
        shift_id=shift.id,
        method=payment_method,
        amount_minor=amount,
        tendered_minor=amount if payment_method == "cash" else None,
        change_minor=0 if payment_method == "cash" else None,
        paid_at=sale_at,
        ref_external="sale-provider-ref" if payment_method != "cash" else None,
    )
    # These models intentionally expose IDs rather than ORM relationships, so
    # make the FK insertion order explicit for a clean PostgreSQL database.
    session.add_all([customer, shift])
    await session.flush()
    if not invoice_issued and not defer_settlement:
        await session.execute(
            text(
                "ALTER TABLE orders DISABLE TRIGGER "
                "trg_orders_final_payment_balance"
            )
        )
    session.add(order)
    await session.flush()
    if defer_settlement:
        await session.commit()
    elif invoice_issued:
        session.add(payment)
        await session.commit()
    else:
        # Intentional corruption-compatibility fixture: revision 0048 refuses
        # to admit this shape during migration or normal writes. Keep one
        # runtime resilience proof by bypassing only the production insert
        # trigger in this disposable test transaction, then restore it before
        # exercising application code.
        await session.execute(
            text(
                "ALTER TABLE payments DISABLE TRIGGER "
                "trg_payments_insert_integrity"
            )
        )
        await session.execute(
            text(
                "ALTER TABLE payments DISABLE TRIGGER "
                "trg_payments_final_order_balance"
            )
        )
        try:
            session.add(payment)
            await session.flush()
        finally:
            await session.execute(
                text(
                    "ALTER TABLE payments ENABLE TRIGGER "
                    "trg_payments_insert_integrity"
                )
            )
            await session.execute(
                text(
                    "ALTER TABLE payments ENABLE TRIGGER "
                    "trg_payments_final_order_balance"
                )
            )
            await session.execute(
                text(
                    "ALTER TABLE orders ENABLE TRIGGER "
                    "trg_orders_final_payment_balance"
                )
            )
        await session.commit()
    token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    return _RefundCase(
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        owner_id=owner.id,
        owner_auth_version=owner.auth_version,
        customer_id=customer.id,
        shift_id=shift.id,
        order_id=order.id,
        captured_at=now,
        amount_minor=amount,
        opening_float_minor=opening,
        payment_method=payment_method,
        token=token,
    )


def _headers(case: _RefundCase, key: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {case.token}",
        "X-Terminal-Id": str(case.terminal_id),
        "Idempotency-Key": key,
        "X-Client-Action-Id": key,
    }


def _request_payload(
    case: _RefundCase,
    *,
    action_id: str,
    mode: str,
) -> dict:
    return {
        "order_id": str(case.order_id),
        "shift_id": str(case.shift_id),
        "reason_code": "CUSTOMER_REQUEST",
        "amount_minor": case.amount_minor,
        "expected_paid_minor": case.amount_minor,
        "expected_refundable_minor": case.amount_minor,
        "mode": mode,
        "client_action_id": action_id,
        "note": "Integration proof",
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_refund_client_action_replay_rejects_changed_paid_snapshot(
    client,
    session,
    seed_owner,
) -> None:
    """A stable action ID cannot be replayed against a different bill snapshot."""
    case = await _seed_case(session, seed_owner, payment_method="cash")
    blank_reason_key = f"pos-refund-request-blank-reason:{uuid4()}"
    action_id = f"pos-refund-request:{uuid4()}"
    conflicting_key = f"pos-refund-request-conflict:{uuid4()}"
    keys = (blank_reason_key, action_id, conflicting_key)
    try:
        blank_reason_payload = _request_payload(
            case,
            action_id=blank_reason_key,
            mode="cash",
        )
        blank_reason_payload["reason_code"] = "   "
        blank_reason = await client.post(
            "/api/v1/pos/refund-requests",
            json=blank_reason_payload,
            headers=_headers(case, blank_reason_key),
        )
        assert blank_reason.status_code == 422, blank_reason.text
        assert "Choose a refund reason" in blank_reason.text

        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=action_id, mode="cash"),
            headers=_headers(case, action_id),
        )
        assert accepted.status_code == 201, accepted.text

        changed_snapshot = _request_payload(
            case,
            action_id=action_id,
            mode="cash",
        )
        changed_snapshot["expected_paid_minor"] = case.amount_minor + 1
        conflict_headers = _headers(case, conflicting_key)
        conflict_headers["X-Client-Action-Id"] = action_id
        conflict = await client.post(
            "/api/v1/pos/refund-requests",
            json=changed_snapshot,
            headers=conflict_headers,
        )

        assert conflict.status_code == 422, conflict.text
        assert "action ID was already used with different details" in conflict.text
        async with AsyncSessionLocal() as verify:
            requests = (
                (
                    await verify.execute(
                        select(PosRefundRequest).where(
                            PosRefundRequest.order_id == case.order_id
                        )
                    )
                )
                .scalars()
                .all()
            )
            assert len(requests) == 1
            assert requests[0].order_paid_snapshot_minor == case.amount_minor
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_full_balance_refund_requests_reserve_once(
    client,
    session,
    seed_owner,
) -> None:
    """Different rapid-tap actions serialize on the order refundable balance."""
    case = await _seed_case(session, seed_owner, payment_method="cash")
    first_key = f"pos-refund-request-race-a:{uuid4()}"
    second_key = f"pos-refund-request-race-b:{uuid4()}"
    keys = (first_key, second_key)
    try:
        first, second = await asyncio.gather(
            client.post(
                "/api/v1/pos/refund-requests",
                json=_request_payload(case, action_id=first_key, mode="cash"),
                headers=_headers(case, first_key),
            ),
            client.post(
                "/api/v1/pos/refund-requests",
                json=_request_payload(case, action_id=second_key, mode="cash"),
                headers=_headers(case, second_key),
            ),
        )
        assert sorted((first.status_code, second.status_code)) == [201, 422]
        rejected = first if first.status_code == 422 else second
        assert "refundable balance changed" in rejected.text

        async with AsyncSessionLocal() as verify:
            requests = (
                (
                    await verify.execute(
                        select(PosRefundRequest).where(
                            PosRefundRequest.order_id == case.order_id
                        )
                    )
                )
                .scalars()
                .all()
            )
            assert len(requests) == 1
            assert requests[0].amount_minor == case.amount_minor
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_recomputes_refund_balance_and_serializes_direct_writers(
    session,
    seed_owner,
) -> None:
    """The database does not trust client snapshots or an API-only mutex."""
    case = await _seed_case(session, seed_owner, payment_method="cash")
    attempted_keys: list[str] = []

    def _direct_request(
        *,
        paid_snapshot: int,
        refundable_snapshot: int,
        amount_minor: int,
    ) -> PosRefundRequest:
        key = f"pos-refund-direct-balance:{uuid4()}"
        attempted_keys.append(key)
        return PosRefundRequest(
            id=uuid4(),
            company_id=case.company_id,
            order_id=case.order_id,
            branch_id=case.branch_id,
            terminal_id=case.terminal_id,
            shift_id=case.shift_id,
            approved_by=case.owner_id,
            manager_override_user_id=None,
            reason_code="CUSTOMER_REQUEST",
            amount_minor=amount_minor,
            mode="cash",
            settlement_method="cash",
            order_paid_snapshot_minor=paid_snapshot,
            order_refundable_snapshot_minor=refundable_snapshot,
            accepted_at=datetime.now(UTC),
            external_reference=None,
            provider_settled_at=None,
            client_action_id=key,
            idempotency_key=key,
            note="Database balance proof",
        )

    async def _commit(row: PosRefundRequest) -> tuple[bool, str]:
        async with AsyncSessionLocal() as writer:
            writer.add(row)
            try:
                await writer.commit()
                return True, ""
            except DBAPIError as exc:
                await writer.rollback()
                return False, str(exc.orig)

    try:
        for row in (
            _direct_request(
                paid_snapshot=case.amount_minor + 1,
                refundable_snapshot=case.amount_minor,
                amount_minor=case.amount_minor,
            ),
            _direct_request(
                paid_snapshot=case.amount_minor,
                refundable_snapshot=case.amount_minor + 1,
                amount_minor=case.amount_minor,
            ),
            _direct_request(
                paid_snapshot=case.amount_minor + 1,
                refundable_snapshot=case.amount_minor + 1,
                amount_minor=case.amount_minor + 1,
            ),
        ):
            committed, error = await _commit(row)
            assert committed is False
            assert "financial snapshot is stale or invalid" in error

        first = _direct_request(
            paid_snapshot=case.amount_minor,
            refundable_snapshot=case.amount_minor,
            amount_minor=case.amount_minor,
        )
        second = _direct_request(
            paid_snapshot=case.amount_minor,
            refundable_snapshot=case.amount_minor,
            amount_minor=case.amount_minor,
        )
        outcomes = await asyncio.gather(_commit(first), _commit(second))
        assert sorted(committed for committed, _ in outcomes) == [False, True]
        rejected_error = next(error for committed, error in outcomes if not committed)
        assert "financial snapshot is stale or invalid" in rejected_error

        async with AsyncSessionLocal() as verify:
            rows = (
                await verify.execute(
                    select(PosRefundRequest).where(
                        PosRefundRequest.order_id == case.order_id
                    )
                )
            ).scalars().all()
            assert len(rows) == 1
            assert rows[0].amount_minor == case.amount_minor
    finally:
        await _cleanup(case, keys=tuple(attempted_keys))


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_refundable_snapshot_subtracts_settled_refunds(
    client,
    session,
    seed_owner,
) -> None:
    """A settled partial payout cannot be reserved again by a direct writer."""
    case = await _seed_case(session, seed_owner, payment_method="cash")
    partial_amount = case.amount_minor // 2
    remaining_amount = case.amount_minor - partial_amount
    request_key = f"pos-refund-partial-request:{uuid4()}"
    begin_key = f"pos-refund-partial-begin:{uuid4()}"
    complete_key = f"pos-refund-partial-complete:{uuid4()}"
    finalize_key = f"pos-refund-partial-finalize:{uuid4()}"
    stale_key = f"pos-refund-partial-stale:{uuid4()}"
    exact_key = f"pos-refund-partial-exact:{uuid4()}"
    keys = (
        request_key,
        begin_key,
        complete_key,
        finalize_key,
        stale_key,
        exact_key,
    )
    try:
        partial_payload = _request_payload(
            case,
            action_id=request_key,
            mode="cash",
        )
        partial_payload["amount_minor"] = partial_amount
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=partial_payload,
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": partial_amount,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": partial_amount,
                "cash_handed_over": True,
                "settled_at": begun.json()["handoff_started_at"],
            },
            headers=_headers(case, complete_key),
        )
        assert completed.status_code == 201, completed.text
        finalized = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": partial_amount,
            },
            headers=_headers(case, finalize_key),
        )
        assert finalized.status_code == 201, finalized.text

        def _remaining_request(key: str, refundable_snapshot: int) -> PosRefundRequest:
            return PosRefundRequest(
                id=uuid4(),
                company_id=case.company_id,
                order_id=case.order_id,
                branch_id=case.branch_id,
                terminal_id=case.terminal_id,
                shift_id=case.shift_id,
                approved_by=case.owner_id,
                manager_override_user_id=None,
                reason_code="CUSTOMER_REQUEST",
                amount_minor=remaining_amount,
                mode="cash",
                settlement_method="cash",
                order_paid_snapshot_minor=case.amount_minor,
                order_refundable_snapshot_minor=refundable_snapshot,
                accepted_at=datetime.now(UTC),
                external_reference=None,
                provider_settled_at=None,
                client_action_id=key,
                idempotency_key=key,
                note="Settled refund subtraction proof",
            )

        async with AsyncSessionLocal() as stale:
            stale.add(_remaining_request(stale_key, case.amount_minor))
            with pytest.raises(DBAPIError, match="financial snapshot is stale or invalid"):
                await stale.commit()
            await stale.rollback()

        async with AsyncSessionLocal() as exact:
            exact.add(_remaining_request(exact_key, remaining_amount))
            await exact.commit()

        async with AsyncSessionLocal() as verify:
            requests = (
                await verify.execute(
                    select(PosRefundRequest).where(
                        PosRefundRequest.order_id == case.order_id
                    )
                )
            ).scalars().all()
            assert len(requests) == 2
            assert sum(int(row.amount_minor) for row in requests) == case.amount_minor
    finally:
        await _cleanup(case, keys=keys)


async def _cleanup(case: _RefundCase, *, keys: tuple[str, ...]) -> None:
    async with AsyncSessionLocal() as cleanup:
        await _set_disposable_cleanup_triggers(cleanup, enabled=False)
        request_ids = select(PosRefundRequest.id).where(
            PosRefundRequest.order_id == case.order_id
        )
        refund_ids = select(Refund.id).where(Refund.request_id.in_(request_ids))
        entity_ids = {
            str(case.customer_id),
            str(case.shift_id),
            str(case.order_id),
        }
        entity_ids.update(
            str(value)
            for value in (
                await cleanup.execute(select(PosRefundRequest.id).where(
                    PosRefundRequest.order_id == case.order_id
                ))
            ).scalars()
        )
        entity_ids.update(
            str(value)
            for value in (
                await cleanup.execute(select(Refund.id).where(Refund.id.in_(refund_ids)))
            ).scalars()
        )
        await cleanup.execute(
            delete(AuditLog).where(
                AuditLog.company_id == case.company_id,
                or_(
                    AuditLog.client_action_id.in_(keys),
                    AuditLog.entity_id.in_(entity_ids),
                ),
            )
        )
        await cleanup.execute(delete(IdempotencyKey).where(IdempotencyKey.key.in_(keys)))
        await cleanup.execute(
            delete(CustomerSpendReconciliation).where(
                CustomerSpendReconciliation.customer_id == case.customer_id
            )
        )
        await cleanup.execute(
            delete(PosRefundEvidenceReconciliation).where(
                PosRefundEvidenceReconciliation.refund_id.in_(refund_ids)
            )
        )
        await cleanup.execute(
            delete(RefundLoyaltyAdjustment).where(
                RefundLoyaltyAdjustment.refund_id.in_(refund_ids)
            )
        )
        await cleanup.execute(delete(Refund).where(Refund.request_id.in_(request_ids)))
        await cleanup.execute(
            delete(PosRefundProviderSettlement).where(
                PosRefundProviderSettlement.refund_request_id.in_(request_ids)
            )
        )
        await cleanup.execute(
            delete(PosRefundProviderPayoutStart).where(
                PosRefundProviderPayoutStart.refund_request_id.in_(request_ids)
            )
        )
        await cleanup.execute(
            delete(PosRefundCashHandoffCompletion).where(
                PosRefundCashHandoffCompletion.refund_request_id.in_(request_ids)
            )
        )
        await cleanup.execute(
            delete(PosRefundCashHandoff).where(
                PosRefundCashHandoff.refund_request_id.in_(request_ids)
            )
        )
        await cleanup.execute(
            delete(PosRefundWithdrawal).where(
                PosRefundWithdrawal.refund_request_id.in_(request_ids)
            )
        )
        await cleanup.execute(
            text(
                "DELETE FROM pos_refund_workflow_guards "
                "WHERE refund_request_id IN ("
                "SELECT id FROM pos_refund_requests WHERE order_id = :order_id)"
            ),
            {"order_id": case.order_id},
        )
        await cleanup.execute(
            delete(PosRefundRequest).where(PosRefundRequest.order_id == case.order_id)
        )
        await cleanup.execute(delete(Payment).where(Payment.order_id == case.order_id))
        await cleanup.execute(
            delete(OrderLoyaltySettlement).where(
                OrderLoyaltySettlement.order_id == case.order_id
            )
        )
        await cleanup.execute(delete(Order).where(Order.id == case.order_id))
        await cleanup.execute(
            delete(InvoiceCounter).where(
                InvoiceCounter.branch_id == case.branch_id,
                InvoiceCounter.series == "pos_refund",
            )
        )
        await cleanup.execute(delete(Shift).where(Shift.id == case.shift_id))
        await cleanup.execute(delete(Customer).where(Customer.id == case.customer_id))
        await _set_disposable_cleanup_triggers(cleanup, enabled=True)
        await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_checkout_records_exact_loyalty_snapshot_for_future_refunds(
    client,
    session,
    seed_owner,
) -> None:
    """Forward checkout persists facts instead of relying on later recompute."""
    case = await _seed_case(
        session,
        seed_owner,
        payment_method="cash",
        defer_settlement=True,
    )
    order = await session.get(Order, case.order_id)
    assert order is not None
    category = MenuCategory(
        id=uuid4(),
        company_id=case.company_id,
        name=f"Loyalty ledger {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=case.company_id,
        category_id=category.id,
        sku=f"LOYALTY-{uuid4().hex[:10]}",
        name="Gaming loyalty proof",
        type="gaming",
        base_price_minor=case.amount_minor,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    line = OrderLine(
        id=uuid4(),
        order_id=case.order_id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=case.amount_minor,
        line_total_minor=case.amount_minor,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=case.amount_minor,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="queued",
    )
    order.customer_id = None
    session.add(category)
    await session.flush()
    session.add(item)
    await session.flush()
    session.add(line)
    await session.commit()

    payment_key = f"loyalty-checkout:{uuid4()}"
    try:
        paid = await client.post(
            f"/api/v1/pos/orders/{case.order_id}/payments",
            json={
                "method": "cash",
                "amount_minor": case.amount_minor,
                "tendered_minor": case.amount_minor,
                "expected_order_total_minor": case.amount_minor,
                "expected_due_minor": case.amount_minor,
            },
            headers=_headers(case, payment_key),
        )
        assert paid.status_code == 201, paid.text
        async with AsyncSessionLocal() as verify:
            customer = await verify.get(Customer, case.customer_id)
            ledger = (
                await verify.execute(
                select(OrderLoyaltySettlement).where(
                    OrderLoyaltySettlement.order_id == case.order_id
                )
                )
            ).scalar_one()
            paid_order = await verify.get(Order, case.order_id)
            assert customer is not None
            assert paid_order is not None
            assert customer.loyalty_points == 49
            assert customer.lifetime_gaming_points_earned == 49
            assert ledger.provenance == "exact"
            assert ledger.customer_id == customer.id
            assert ledger.order_paid_minor == case.amount_minor
            assert ledger.points_redeemed == 0
            assert ledger.points_earned == 49
            assert ledger.rank_bonus_points == 0
            assert ledger.settled_at == paid_order.invoice_issued_at
    finally:
        await _cleanup(case, keys=(payment_key,))
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(
                delete(MenuCategory).where(MenuCategory.id == category.id)
            )
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_money_refund_does_not_restock_or_reverse_sale_cogs(
    client,
    session,
    seed_owner,
) -> None:
    """A payment refund is not evidence that prepared stock was returned."""
    case = await _seed_case(
        session,
        seed_owner,
        payment_method="cash",
        defer_settlement=True,
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=case.company_id,
        name=f"Refund stock contract {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=case.company_id,
        category_id=category.id,
        sku=f"RF-STOCK-{uuid4().hex[:10]}",
        name="Prepared stock refund proof",
        type="food",
        base_price_minor=case.amount_minor,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=case.company_id,
        sku=f"RF-ING-{uuid4().hex[:10]}",
        name="Refund proof ingredient",
        base_unit="unit",
        reorder_threshold=0,
        reorder_qty=0,
        avg_cost_minor=125,
        current_qty=10,
    )
    batch = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=case.branch_id,
        received_at=case.captured_at - timedelta(days=1),
        expires_at=None,
        qty_initial=10,
        qty_on_hand=10,
        cost_per_unit_minor=125,
        lot_code=f"RF-{uuid4().hex[:8]}",
    )
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="One ingredient per prepared item",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=125,
    )
    recipe_line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=1,
        wastage_pct=0,
    )
    order_line = OrderLine(
        id=uuid4(),
        order_id=case.order_id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=case.amount_minor,
        line_total_minor=case.amount_minor,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=case.amount_minor,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="served",
        kitchen_released_at=case.captured_at - timedelta(minutes=25),
        kitchen_round_no=1,
        kitchen_served_at=case.captured_at,
    )
    session.add_all([category, ingredient])
    await session.flush()
    session.add_all([item, batch])
    await session.flush()
    session.add(recipe)
    await session.flush()
    session.add_all([recipe_line, order_line])
    await session.commit()

    payment_key = f"refund-stock-payment:{uuid4()}"
    request_key = f"refund-stock-request:{uuid4()}"
    begin_key = f"refund-stock-begin:{uuid4()}"
    complete_key = f"refund-stock-complete:{uuid4()}"
    finalize_key = f"refund-stock-finalize:{uuid4()}"
    keys = (payment_key, request_key, begin_key, complete_key, finalize_key)
    try:
        paid = await client.post(
            f"/api/v1/pos/orders/{case.order_id}/payments",
            json={
                "method": "cash",
                "amount_minor": case.amount_minor,
                "tendered_minor": case.amount_minor,
                "expected_order_total_minor": case.amount_minor,
                "expected_due_minor": case.amount_minor,
            },
            headers=_headers(case, payment_key),
        )
        assert paid.status_code == 201, paid.text

        async with AsyncSessionLocal() as verify:
            paid_batch = await verify.get(Batch, batch.id)
            paid_ingredient = await verify.get(Ingredient, ingredient.id)
            sale_movements = (
                (
                    await verify.execute(
                        select(StockMovement).where(
                            StockMovement.ref_type == "order",
                            StockMovement.ref_id == case.order_id,
                        )
                    )
                )
                .scalars()
                .all()
            )
            assert paid_batch is not None
            assert paid_ingredient is not None
            assert float(paid_batch.qty_on_hand) == 9
            assert float(paid_ingredient.current_qty) == 9
            assert len(sale_movements) == 1
            assert sale_movements[0].type == "sale"
            assert float(sale_movements[0].qty_delta) == -1
            assert sale_movements[0].cost_per_unit_minor == 125
            sale_movement_id = sale_movements[0].id

        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_handed_over": True,
                "settled_at": begun.json()["handoff_started_at"],
            },
            headers=_headers(case, complete_key),
        )
        assert completed.status_code == 201, completed.text
        finalized = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=_headers(case, finalize_key),
        )
        assert finalized.status_code == 201, finalized.text

        period_start = datetime.combine(
            case.captured_at.date() - timedelta(days=1),
            datetime.min.time(),
            tzinfo=UTC,
        )
        period_end = datetime.combine(
            case.captured_at.date() + timedelta(days=2),
            datetime.min.time(),
            tzinfo=UTC,
        )
        async with AsyncSessionLocal() as verify:
            refunded_batch = await verify.get(Batch, batch.id)
            refunded_ingredient = await verify.get(Ingredient, ingredient.id)
            movements = (
                (
                    await verify.execute(
                        select(StockMovement).where(
                            StockMovement.ref_type == "order",
                            StockMovement.ref_id == case.order_id,
                        )
                    )
                )
                .scalars()
                .all()
            )
            assert refunded_batch is not None
            assert refunded_ingredient is not None
            assert float(refunded_batch.qty_on_hand) == 9
            assert float(refunded_ingredient.current_qty) == 9
            assert [(row.id, row.type) for row in movements] == [
                (sale_movement_id, "sale")
            ]
            assert not any(row.type == "refund_restock" for row in movements)

            ledger = await build_operational_ledger(
                verify,
                company_id=case.company_id,
                start_at=period_start,
                end_exclusive=period_end,
            )
            inventory_lines = [
                line for line in ledger if line.ref_id == sale_movement_id
            ]
            assert {
                (line.account_code, line.debit_minor, line.credit_minor)
                for line in inventory_lines
            } == {
                (COST_OF_GOODS_SOLD.code, 125, 0),
                (INVENTORY.code, 0, 125),
            }
    finally:
        await _cleanup(case, keys=keys)
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(StockMovement).where(StockMovement.batch_id == batch.id)
            )
            await cleanup.execute(delete(Batch).where(Batch.id == batch.id))
            await cleanup.execute(delete(RecipeLine).where(RecipeLine.id == recipe_line.id))
            await cleanup.execute(delete(Recipe).where(Recipe.id == recipe.id))
            await cleanup.execute(delete(Ingredient).where(Ingredient.id == ingredient.id))
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(
                delete(MenuCategory).where(MenuCategory.id == category.id)
            )
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_partial_refunds_restore_and_reverse_exact_loyalty_once(
    client,
    session,
    seed_owner,
) -> None:
    """Cumulative allocation is exact, drift-free and replay-idempotent."""
    case = await _seed_case(
        session,
        seed_owner,
        payment_method="cash",
        points_redeemed_minor=70,
    )
    customer = await session.get(Customer, case.customer_id)
    order = await session.get(Order, case.order_id)
    assert customer is not None
    assert order is not None
    customer.loyalty_points = 37
    customer.lifetime_gaming_points_earned = 123
    redemption = PointsRedemption(
        id=uuid4(),
        customer_id=case.customer_id,
        order_id=case.order_id,
        points_spent=7,
        amount_minor=70,
        consumed_at=case.captured_at - timedelta(minutes=19),
    )
    settlement = OrderLoyaltySettlement(
        id=uuid4(),
        company_id=case.company_id,
        customer_id=case.customer_id,
        order_id=case.order_id,
        order_paid_minor=case.amount_minor,
        points_redeemed=7,
        points_earned=5,
        rank_bonus_points=3,
        settled_at=order.invoice_issued_at,
        provenance="exact",
    )
    session.add(redemption)
    # The database guard intentionally reads the immutable order/redemption
    # facts before accepting the derived settlement snapshot.
    await session.flush()
    session.add(settlement)
    await session.commit()

    keys: list[str] = []

    async def _settle_partial(
        *, amount_minor: int, refundable_before: int, label: str
    ) -> tuple[str, str]:
        request_key = f"loyalty-{label}-request:{uuid4()}"
        begin_key = f"loyalty-{label}-begin:{uuid4()}"
        complete_key = f"loyalty-{label}-complete:{uuid4()}"
        finalize_key = f"loyalty-{label}-finalize:{uuid4()}"
        keys.extend((request_key, begin_key, complete_key, finalize_key))
        payload = _request_payload(case, action_id=request_key, mode="cash")
        payload["amount_minor"] = amount_minor
        payload["expected_refundable_minor"] = refundable_before
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=payload,
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": amount_minor,
                "cash_handed_over": True,
                "settled_at": begun.json()["handoff_started_at"],
            },
            headers=_headers(case, complete_key),
        )
        assert completed.status_code == 201, completed.text
        finalized = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": amount_minor,
            },
            headers=_headers(case, finalize_key),
        )
        assert finalized.status_code == 201, finalized.text
        return request_id, finalize_key

    first_amount = 8_000
    try:
        first_request_id, first_finalize_key = await _settle_partial(
            amount_minor=first_amount,
            refundable_before=case.amount_minor,
            label="first",
        )
        async with AsyncSessionLocal() as verify:
            first_customer = await verify.get(Customer, case.customer_id)
            first_refund = (
                await verify.execute(
                    select(Refund).where(
                        Refund.request_id == UUID(first_request_id)
                    )
                )
            ).scalar_one()
            first_adjustment = (
                await verify.execute(
                    select(RefundLoyaltyAdjustment).where(
                        RefundLoyaltyAdjustment.refund_id == first_refund.id
                    )
                )
            ).scalar_one()
            assert first_customer is not None
            assert first_customer.loyalty_points == 38
            assert first_customer.lifetime_gaming_points_earned == 123
            assert first_refund.loyalty_reconciliation_state == "applied"
            assert first_adjustment.cumulative_refunded_minor == first_amount
            assert first_adjustment.redeemed_points_restored == 2
            assert first_adjustment.points_earned_reversed == 1
            assert first_adjustment.rank_bonus_points_reversed == 0
            assert first_adjustment.net_points_delta == 1

        remaining = case.amount_minor - first_amount
        second_request_id, _ = await _settle_partial(
            amount_minor=remaining,
            refundable_before=remaining,
            label="second",
        )

        # Response-loss/rapid-tap replay returns the immutable result and does
        # not insert or apply a third loyalty adjustment.
        replay = await client.post(
            f"/api/v1/pos/refund-requests/{first_request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": first_amount,
            },
            headers=_headers(case, first_finalize_key),
        )
        assert replay.status_code == 201, replay.text

        async with AsyncSessionLocal() as verify:
            final_customer = await verify.get(Customer, case.customer_id)
            adjustments = (
                (
                    await verify.execute(
                        select(RefundLoyaltyAdjustment)
                        .where(
                            RefundLoyaltyAdjustment.order_id == case.order_id
                        )
                        .order_by(
                            RefundLoyaltyAdjustment.cumulative_refunded_minor
                        )
                    )
                )
                .scalars()
                .all()
            )
            second_refund = (
                await verify.execute(
                    select(Refund).where(
                        Refund.request_id == UUID(second_request_id)
                    )
                )
            ).scalar_one()
            assert final_customer is not None
            assert final_customer.loyalty_points == 36
            assert final_customer.lifetime_gaming_points_earned == 123
            assert second_refund.loyalty_reconciliation_state == "applied"
            assert len(adjustments) == 2
            assert sum(row.redeemed_points_restored for row in adjustments) == 7
            assert sum(row.points_earned_reversed for row in adjustments) == 5
            assert sum(row.rank_bonus_points_reversed for row in adjustments) == 3
            assert sum(row.net_points_delta for row in adjustments) == -1

        async with AsyncSessionLocal() as tamper:
            with pytest.raises(DBAPIError, match="append-only"):
                await tamper.execute(
                    update(RefundLoyaltyAdjustment)
                    .where(RefundLoyaltyAdjustment.order_id == case.order_id)
                    .values(net_points_delta=999)
                )
            await tamper.rollback()
    finally:
        await _cleanup(case, keys=tuple(keys))


@pytest.mark.integration
@pytest.mark.asyncio
async def test_full_refund_restores_legacy_redemption_without_inventing_earn(
    client,
    session,
    seed_owner,
) -> None:
    """A 0043 backfill row restores known spend while leaving unknown earn alone."""
    case = await _seed_case(
        session,
        seed_owner,
        payment_method="cash",
        points_redeemed_minor=200,
    )
    customer = await session.get(Customer, case.customer_id)
    order = await session.get(Order, case.order_id)
    assert customer is not None
    assert order is not None
    customer.loyalty_points = 10
    redemption = PointsRedemption(
        id=uuid4(),
        customer_id=case.customer_id,
        order_id=case.order_id,
        points_spent=20,
        amount_minor=200,
        consumed_at=case.captured_at - timedelta(minutes=19),
    )
    session.add(redemption)
    await session.flush()

    # Only migration 0043 may create redemption-only provenance. This narrowly
    # disabled fixture trigger recreates its output in an already-at-head test
    # database; the trigger is restored before any refund code runs.
    await session.execute(
        text(
            "ALTER TABLE order_loyalty_settlements DISABLE TRIGGER "
            "trg_order_loyalty_settlement_guard"
        )
    )
    session.add(
        OrderLoyaltySettlement(
            id=uuid4(),
            company_id=case.company_id,
            customer_id=case.customer_id,
            order_id=case.order_id,
            order_paid_minor=case.amount_minor,
            points_redeemed=20,
            points_earned=0,
            rank_bonus_points=0,
            settled_at=redemption.consumed_at,
            provenance="legacy_redemption_only",
        )
    )
    await session.flush()
    await session.execute(
        text(
            "ALTER TABLE order_loyalty_settlements ENABLE TRIGGER "
            "trg_order_loyalty_settlement_guard"
        )
    )
    await session.commit()

    request_key = f"legacy-loyalty-request:{uuid4()}"
    begin_key = f"legacy-loyalty-begin:{uuid4()}"
    complete_key = f"legacy-loyalty-complete:{uuid4()}"
    finalize_key = f"legacy-loyalty-finalize:{uuid4()}"
    keys = (request_key, begin_key, complete_key, finalize_key)
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_handed_over": True,
                "settled_at": begun.json()["handoff_started_at"],
            },
            headers=_headers(case, complete_key),
        )
        assert completed.status_code == 201, completed.text
        finalized = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=_headers(case, finalize_key),
        )
        assert finalized.status_code == 201, finalized.text
        assert finalized.json()["loyalty_reconciliation_state"] == (
            "legacy_redemption_restored"
        )

        async with AsyncSessionLocal() as verify:
            final_customer = await verify.get(Customer, case.customer_id)
            refund = (
                await verify.execute(
                    select(Refund).where(
                        Refund.request_id == UUID(request_id)
                    )
                )
            ).scalar_one()
            adjustment = (
                await verify.execute(
                    select(RefundLoyaltyAdjustment).where(
                        RefundLoyaltyAdjustment.refund_id == refund.id
                    )
                )
            ).scalar_one()
            assert final_customer is not None
            assert final_customer.loyalty_points == 30
            assert refund.loyalty_reconciliation_state == (
                "legacy_redemption_restored"
            )
            assert adjustment.redeemed_points_restored == 20
            assert adjustment.points_earned_reversed == 0
            assert adjustment.rank_bonus_points_reversed == 0
            assert adjustment.net_points_delta == 20

        # Prove the forward-state constraint itself, independently of the
        # append-only triggers that normally reject every Refund update first.
        # The DDL and failed mutation are rolled back together, restoring both
        # triggers before cleanup.
        async with AsyncSessionLocal() as constraint_probe:
            await constraint_probe.execute(
                text(
                    "ALTER TABLE refunds DISABLE TRIGGER "
                    "trg_refunds_immutable"
                )
            )
            await constraint_probe.execute(
                text(
                    "ALTER TABLE refunds DISABLE TRIGGER "
                    "trg_refunds_transition_guard"
                )
            )
            with pytest.raises(
                DBAPIError,
                match="ck_refund_loyalty_reconciliation_state",
            ):
                await constraint_probe.execute(
                    update(Refund)
                    .where(Refund.id == refund.id)
                    .values(loyalty_reconciliation_state=None)
                )
            await constraint_probe.rollback()
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_refund_completion_survives_before_concurrent_finalize(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    install_audit_listeners()
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-begin:{uuid4()}"
    wrong_completion_key = f"pos-refund-cash-wrong-amount:{uuid4()}"
    completion_key = f"pos-refund-cash-complete:{uuid4()}"
    finalize_key = f"pos-refund-cash-finalize:{uuid4()}"
    keys = (
        request_key,
        begin_key,
        wrong_completion_key,
        completion_key,
        finalize_key,
    )
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        body = accepted.json()
        assert body["status"] == "accepted_cash_due"
        request_id = UUID(body["id"])

        async with AsyncSessionLocal() as verify:
            assert (
                await verify.execute(
                    select(Refund).where(Refund.request_id == request_id)
                )
            ).scalar_one_or_none() is None
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            order = await verify.get(Order, case.order_id)
            assert shift.expected_minor == case.opening_float_minor + case.amount_minor
            assert customer.total_spent_minor == case.amount_minor
            assert order.status == "paid"

        blocked_close = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_float_minor + case.amount_minor},
            headers=_headers(case, f"close-blocked:{uuid4()}"),
        )
        assert blocked_close.status_code == 422
        assert "accepted POS refund" in blocked_close.text

        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        assert begun.json()["status"] == "cash_handoff_in_progress"
        handoff_started_at = datetime.fromisoformat(begun.json()["handoff_started_at"])
        cash_handed_over_at = handoff_started_at

        visible_task = await client.get(
            "/api/v1/pos/refund-requests",
            params={"shift_id": str(case.shift_id)},
            headers=_headers(case, f"refund-list:{uuid4()}"),
        )
        assert visible_task.status_code == 200, visible_task.text
        task_row = next(row for row in visible_task.json() if row["id"] == str(request_id))
        assert task_row["accepted_by"] == str(case.owner_id)
        assert task_row["accepted_by_name"] == seed_owner["owner"].name
        assert task_row["handoff_started_by"] == str(case.owner_id)
        assert task_row["handoff_started_by_name"] == seed_owner["owner"].name

        wrong_amount = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor + 1,
                "cash_handed_over": True,
                "settled_at": cash_handed_over_at.isoformat(),
            },
            headers=_headers(case, wrong_completion_key),
        )
        assert wrong_amount.status_code == 422
        assert "server-confirmed cash amount differs" in wrong_amount.text
        async with AsyncSessionLocal() as verify:
            assert (
                await verify.execute(
                    select(PosRefundCashHandoffCompletion).where(
                        PosRefundCashHandoffCompletion.refund_request_id == request_id
                    )
                )
            ).scalar_one_or_none() is None

        settle_payload = {
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "cash_handed_over": True,
            "settled_at": cash_handed_over_at.isoformat(),
        }
        first, duplicate = await asyncio.gather(
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
                json=settle_payload,
                headers=_headers(case, completion_key),
            ),
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
                json=settle_payload,
                headers=_headers(case, completion_key),
            ),
        )
        assert all(response.status_code in {201, 409} for response in (first, duplicate))
        replay = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json=settle_payload,
            headers=_headers(case, completion_key),
        )
        assert replay.status_code == 201, replay.text
        assert replay.json()["status"] == "cash_handed_over_pending_accounting"
        assert replay.json()["cash_handed_over_by"] == str(case.owner_id)

        # The completion is a committed server obligation before any receipt,
        # drawer or LTV mutation. A new device can list it and safely finalize.
        async with AsyncSessionLocal() as verify:
            completion = (
                await verify.execute(
                    select(PosRefundCashHandoffCompletion).where(
                        PosRefundCashHandoffCompletion.refund_request_id == request_id
                    )
                )
            ).scalar_one()
            assert completion.handed_over_at == cash_handed_over_at
            completion_recorded_at = completion.recorded_at
            assert (
                await verify.execute(
                    select(Refund).where(Refund.request_id == request_id)
                )
            ).scalar_one_or_none() is None

        recovered_task = await client.get(
            "/api/v1/pos/refund-requests",
            params={"shift_id": str(case.shift_id)},
            headers=_headers(case, f"refund-recovery-list:{uuid4()}"),
        )
        assert recovered_task.status_code == 200, recovered_task.text
        recovered_row = next(
            row for row in recovered_task.json() if row["id"] == str(request_id)
        )
        assert recovered_row["status"] == "cash_handed_over_pending_accounting"
        assert recovered_row["cash_handed_over_by"] == str(case.owner_id)
        assert recovered_row["cash_handed_over_by_name"] == seed_owner["owner"].name
        assert recovered_row["cash_handed_over_recorded_at"] is not None

        finalize_payload = {
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
        }
        first_finalize, duplicate_finalize = await asyncio.gather(
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
                json=finalize_payload,
                headers=_headers(case, finalize_key),
            ),
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
                json=finalize_payload,
                headers=_headers(case, finalize_key),
            ),
        )
        assert all(
            response.status_code in {201, 409}
            for response in (first_finalize, duplicate_finalize)
        )
        finalized = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json=finalize_payload,
            headers=_headers(case, finalize_key),
        )
        assert finalized.status_code == 201, finalized.text
        assert finalized.json()["status"] == "settled"
        receipt_no = finalized.json()["receipt_no"]
        assert receipt_no.startswith(
            f"R/{seed_owner['branch'].invoice_series_code}/"
        )
        assert len(receipt_no) == 16

        async with AsyncSessionLocal() as verify:
            refunds = (
                (
                    await verify.execute(
                        select(Refund).where(Refund.request_id == request_id)
                    )
                )
                .scalars()
                .all()
            )
            assert len(refunds) == 1
            assert refunds[0].settlement_shift_id == case.shift_id
            assert refunds[0].settled_at == completion_recorded_at
            assert refunds[0].settled_at >= handoff_started_at
            assert refunds[0].receipt_issued_at >= refunds[0].settled_at
            assert refunds[0].client_occurred_at == cash_handed_over_at
            assert refunds[0].captured_time_reconciled is True
            assert refunds[0].settlement_idempotency_key == finalize_key
            assert refunds[0].customer_spend_reconciled is True
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            order = await verify.get(Order, case.order_id)
            assert shift.expected_minor == case.opening_float_minor
            assert customer.total_spent_minor == 0
            assert order.status == "refunded"

        shifts = await client.get(
            "/api/v1/pos/shifts?only_open=true",
            headers=_headers(case, f"shift-read:{uuid4()}"),
        )
        assert shifts.status_code == 200, shifts.text
        summary = next(row for row in shifts.json() if row["id"] == str(case.shift_id))
        assert summary["gross_collections_minor"] == case.amount_minor
        assert summary["settled_pos_refunds_minor"] == case.amount_minor
        assert summary["net_collections_minor"] == 0

        async with AsyncSessionLocal() as tamper:
            with pytest.raises(DBAPIError, match="append-only"):
                await tamper.execute(
                    update(PosRefundRequest)
                    .where(PosRefundRequest.id == request_id)
                    .values(reason_code="TAMPERED")
                )
            await tamper.rollback()
            with pytest.raises(DBAPIError, match="append-only"):
                await tamper.execute(
                    delete(Refund).where(Refund.request_id == request_id)
                )
            await tamper.rollback()
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_completion_survives_failed_accounting_and_is_recoverable(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-begin:{uuid4()}"
    completion_key = f"pos-refund-complete:{uuid4()}"
    rejected_finalize_key = f"pos-refund-finalize-rejected:{uuid4()}"
    recovery_finalize_key = f"pos-refund-finalize-recovery:{uuid4()}"
    keys = (
        request_key,
        begin_key,
        completion_key,
        rejected_finalize_key,
        recovery_finalize_key,
    )
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_handed_over": True,
                "settled_at": begun.json()["handoff_started_at"],
            },
            headers=_headers(case, completion_key),
        )
        assert completed.status_code == 201, completed.text
        assert completed.json()["status"] == "cash_handed_over_pending_accounting"

        rejected = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor + 1,
            },
            headers=_headers(case, rejected_finalize_key),
        )
        assert rejected.status_code == 422, rejected.text
        assert "do not hand over cash again" in rejected.text

        async with AsyncSessionLocal() as verify:
            completion = (
                await verify.execute(
                    select(PosRefundCashHandoffCompletion).where(
                        PosRefundCashHandoffCompletion.refund_request_id
                        == UUID(request_id)
                    )
                )
            ).scalar_one()
            assert completion.recorded_by == case.owner_id
            assert (
                await verify.execute(
                    select(Refund).where(Refund.request_id == UUID(request_id))
                )
            ).scalar_one_or_none() is None

        blocked_close = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_float_minor},
            headers=_headers(case, f"close-with-completion:{uuid4()}"),
        )
        assert blocked_close.status_code == 422, blocked_close.text
        assert "accepted POS refund" in blocked_close.text

        recovered = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=_headers(case, recovery_finalize_key),
        )
        assert recovered.status_code == 201, recovered.text
        assert recovered.json()["status"] == "settled"
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_legacy_unissued_paid_order_stays_consistent_across_reports_and_ledger(
    client,
    session,
    seed_owner,
) -> None:
    """A corrupt legacy sale remains reportable but cannot move more money.

    Revision 0048 refuses this shape during upgrade. This narrow fixture proves
    defence in depth: reporting still exposes the historical collection, while
    the refund workflow quarantines it before any cash handoff is accepted.
    """
    case = await _seed_case(
        session,
        seed_owner,
        payment_method="cash",
        invoice_issued=False,
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=case.company_id,
        name=f"Legacy refund proof {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=case.company_id,
        category_id=category.id,
        sku=f"LEGACY-RF-{uuid4().hex[:10]}",
        name="Legacy refund proof item",
        type="food",
        base_price_minor=case.amount_minor,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    session.add(category)
    await session.flush()
    session.add(item)
    await session.flush()
    # Same intentional legacy shape: the paid-source guard correctly refuses
    # a new financial line, so install the historical line only while that one
    # trigger is disabled and restore it before the refund workflow begins.
    await session.execute(
        text(
            "ALTER TABLE order_lines DISABLE TRIGGER "
            "trg_order_lines_paid_source_integrity"
        )
    )
    await session.execute(
        text(
            "ALTER TABLE orders DISABLE TRIGGER "
            "trg_orders_final_payment_balance"
        )
    )
    try:
        session.add(
            OrderLine(
            id=uuid4(),
            order_id=case.order_id,
            menu_item_id=item.id,
            qty=1,
            unit_price_minor=case.amount_minor,
            line_total_minor=case.amount_minor,
            discount_minor=0,
            tax_rate=0,
            taxable_value_minor=case.amount_minor,
            cgst_minor=0,
            sgst_minor=0,
            igst_minor=0,
            cess_minor=0,
            kitchen_status="served",
            kitchen_served_at=case.captured_at,
            )
        )
        await session.flush()
    finally:
        await session.execute(
            text(
                "ALTER TABLE order_lines ENABLE TRIGGER "
                "trg_order_lines_paid_source_integrity"
            )
        )
        await session.execute(
            text(
                "ALTER TABLE orders ENABLE TRIGGER "
                "trg_orders_final_payment_balance"
            )
        )
    await session.commit()

    request_key = f"pos-refund-report-request:{uuid4()}"
    keys = (request_key,)
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 422, accepted.text
        assert "missing immutable invoice evidence" in accepted.text

        period_start = (case.captured_at - timedelta(days=1)).date()
        period_end = (case.captured_at + timedelta(days=1)).date()
        ledger_start = datetime.combine(period_start, datetime.min.time(), tzinfo=UTC)
        ledger_end = datetime.combine(
            period_end + timedelta(days=1), datetime.min.time(), tzinfo=UTC
        )
        async with AsyncSessionLocal() as verify:
            report = await ReportsAggregator(verify).aggregate(
                company_id=case.company_id,
                period_start=period_start,
                period_end=period_end,
            )
            assert report.orders_count == 1
            assert report.unissued_paid_orders_count == 1
            assert report.gross_revenue_minor == case.amount_minor
            assert report.payments_received.cash_minor == case.amount_minor
            assert report.refunds_issued_minor == 0
            assert report.settled_refunds_issued_minor == 0
            assert report.net_revenue_minor == case.amount_minor
            assert report.net_payments_received_minor == case.amount_minor
            assert _to_dto(report).unissued_paid_orders_count == 1

            insights = await _period_stats(
                verify,
                case.company_id,
                period_start,
                period_end,
                "UTC",
            )
            assert insights.orders_count == report.orders_count
            assert insights.refunds_minor == report.refunds_issued_minor
            assert insights.revenue_minor == report.net_revenue_minor

            ledger = await build_operational_ledger(
                verify,
                company_id=case.company_id,
                start_at=ledger_start,
                end_exclusive=ledger_end,
            )
            assert sum(line.debit_minor for line in ledger) == sum(
                line.credit_minor for line in ledger
            )
            assert sum(
                line.credit_minor
                for line in ledger
                if line.account_code == SALES_REVENUE.code
            ) == case.amount_minor
    finally:
        await _cleanup(case, keys=keys)
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(delete(MenuItem).where(MenuItem.id == item.id))
            await cleanup.execute(
                delete(MenuCategory).where(MenuCategory.id == category.id)
            )
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_started_cash_handoff_requires_explicit_no_cash_resolution(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-begin:{uuid4()}"
    resolve_key = f"pos-refund-resolve-handoff:{uuid4()}"
    keys = (request_key, begin_key, resolve_key)
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        blocked_withdrawal = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/withdraw-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_not_handed_over": True,
                "reason": "Customer left before cash was handed over",
                "withdrawn_at": case.captured_at.isoformat(),
            },
            headers=_headers(case, f"unsafe-withdraw:{uuid4()}"),
        )
        assert blocked_withdrawal.status_code == 422
        assert "handover is already in progress" in blocked_withdrawal.text

        withdrawn = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/resolve-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_not_handed_over": True,
                "drawer_unchanged": True,
                "reason": "Customer left before cash was handed over",
                "resolved_at": begun.json()["handoff_started_at"],
            },
            headers=_headers(case, resolve_key),
        )
        assert withdrawn.status_code == 201, withdrawn.text
        assert withdrawn.json()["status"] == "withdrawn"
        async with AsyncSessionLocal() as verify:
            request_uuid = UUID(request_id)
            assert (
                await verify.execute(select(Refund).where(Refund.request_id == request_uuid))
            ).scalar_one_or_none() is None
            assert (
                await verify.execute(
                    select(PosRefundWithdrawal).where(
                        PosRefundWithdrawal.refund_request_id == request_uuid
                    )
                )
            ).scalar_one_or_none() is not None
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            order = await verify.get(Order, case.order_id)
            assert shift.expected_minor == case.opening_float_minor + case.amount_minor
            assert customer.total_spent_minor == case.amount_minor
            assert order.status == "paid"
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_handoff_names_starter_and_requires_explicit_owner_takeover(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    takeover_owner = User(
        id=uuid4(),
        company_id=case.company_id,
        email=f"refund-takeover-{uuid4().hex[:8]}@test.local",
        name="Refund Takeover Owner",
        password_hash="not-used-by-refund-test",
        status="active",
    )
    session.add(takeover_owner)
    await session.commit()
    takeover_token = issue_access_token(
        user_id=takeover_owner.id,
        company_id=case.company_id,
        branch_id=case.branch_id,
        roles=["owner"],
        auth_version=takeover_owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )

    def takeover_headers(key: str) -> dict[str, str]:
        headers = _headers(case, key)
        headers["Authorization"] = f"Bearer {takeover_token}"
        return headers

    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-begin:{uuid4()}"
    rejected_begin_key = f"pos-refund-begin-other:{uuid4()}"
    completion_key = f"pos-refund-complete-takeover:{uuid4()}"
    finalize_key = f"pos-refund-finalize-takeover:{uuid4()}"
    keys = (
        request_key,
        begin_key,
        rejected_begin_key,
        completion_key,
        finalize_key,
    )
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text

        duplicate_begin = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=takeover_headers(rejected_begin_key),
        )
        assert duplicate_begin.status_code == 422, duplicate_begin.text
        assert seed_owner["owner"].name in duplicate_begin.text
        assert "Do not hand over cash a second time" in duplicate_begin.text

        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_handed_over": True,
                "settled_at": begun.json()["handoff_started_at"],
            },
            headers=takeover_headers(completion_key),
        )
        assert completed.status_code == 201, completed.text
        assert completed.json()["cash_handed_over_by"] == str(takeover_owner.id)

        recovered = await client.get(
            "/api/v1/pos/refund-requests",
            params={"shift_id": str(case.shift_id)},
            headers=takeover_headers(f"refund-takeover-list:{uuid4()}"),
        )
        assert recovered.status_code == 200, recovered.text
        row = next(item for item in recovered.json() if item["id"] == request_id)
        assert row["handoff_started_by_name"] == seed_owner["owner"].name
        assert row["cash_handed_over_by_name"] == takeover_owner.name

        finalized = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=takeover_headers(finalize_key),
        )
        assert finalized.status_code == 201, finalized.text
        assert finalized.json()["settled_by"] == str(takeover_owner.id)
    finally:
        await _cleanup(case, keys=keys)
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(AuditLog).where(AuditLog.entity_id == str(takeover_owner.id))
            )
            await cleanup.execute(delete(User).where(User.id == takeover_owner.id))
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_handoff_start_and_pre_handoff_withdrawal_cannot_coexist(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-begin:{uuid4()}"
    withdraw_key = f"pos-refund-withdraw:{uuid4()}"
    keys = (request_key, begin_key, withdraw_key)
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        accepted_at = accepted.json()["accepted_at"]
        begin, withdraw = await asyncio.gather(
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
                json={
                    "shift_id": str(case.shift_id),
                    "expected_amount_minor": case.amount_minor,
                    "ready_to_handover": True,
                },
                headers=_headers(case, begin_key),
            ),
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/withdraw-cash",
                json={
                    "shift_id": str(case.shift_id),
                    "expected_amount_minor": case.amount_minor,
                    "cash_not_handed_over": True,
                    "reason": "Customer cancelled before drawer handover started",
                    "withdrawn_at": accepted_at,
                },
                headers=_headers(case, withdraw_key),
            ),
        )
        assert begin.status_code in {201, 422}
        assert withdraw.status_code in {201, 422}
        assert 201 in {begin.status_code, withdraw.status_code}

        async with AsyncSessionLocal() as verify:
            request_uuid = UUID(request_id)
            handoff = (
                await verify.execute(
                    select(PosRefundCashHandoff).where(
                        PosRefundCashHandoff.refund_request_id == request_uuid
                    )
                )
            ).scalar_one_or_none()
            withdrawal = (
                await verify.execute(
                    select(PosRefundWithdrawal).where(
                        PosRefundWithdrawal.refund_request_id == request_uuid
                    )
                )
            ).scalar_one_or_none()
            assert (handoff is None) != (withdrawal is None)
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_guard_serializes_raw_start_and_withdrawal(
    client,
    session,
    seed_owner,
) -> None:
    """Independent direct writers still get exactly one accepted transition."""
    case = await _seed_case(session, seed_owner, payment_method="cash")
    request_key = f"pos-refund-request:{uuid4()}"
    start_key = f"raw-start:{uuid4()}"
    withdraw_key = f"raw-withdraw:{uuid4()}"
    keys = (request_key, start_key, withdraw_key)
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = UUID(accepted.json()["id"])
        happened_at = datetime.now(UTC)

        async def insert_start() -> bool:
            try:
                async with AsyncSessionLocal() as writer:
                    await writer.execute(
                        text(
                            "INSERT INTO pos_refund_cash_handoffs ("
                            "id, refund_request_id, company_id, branch_id, terminal_id, "
                            "shift_id, started_at, started_by, idempotency_key) VALUES ("
                            ":id, :request_id, :company_id, :branch_id, :terminal_id, "
                            ":shift_id, :at, :actor, :key)"
                        ),
                        {
                            "id": uuid4(),
                            "request_id": request_id,
                            "company_id": case.company_id,
                            "branch_id": case.branch_id,
                            "terminal_id": case.terminal_id,
                            "shift_id": case.shift_id,
                            "at": happened_at,
                            "actor": case.owner_id,
                            "key": start_key,
                        },
                    )
                    await writer.commit()
                return True
            except DBAPIError:
                return False

        async def insert_withdrawal() -> bool:
            try:
                async with AsyncSessionLocal() as writer:
                    await writer.execute(
                        text(
                            "INSERT INTO pos_refund_withdrawals ("
                            "id, refund_request_id, company_id, branch_id, terminal_id, "
                            "shift_id, resolution, reason, withdrawn_at, withdrawn_by, "
                            "idempotency_key) VALUES ("
                            ":id, :request_id, :company_id, :branch_id, :terminal_id, "
                            ":shift_id, 'cash_not_handed_over', :reason, :at, :actor, :key)"
                        ),
                        {
                            "id": uuid4(),
                            "request_id": request_id,
                            "company_id": case.company_id,
                            "branch_id": case.branch_id,
                            "terminal_id": case.terminal_id,
                            "shift_id": case.shift_id,
                            "reason": "Raw concurrency proof: no cash moved",
                            "at": happened_at,
                            "actor": case.owner_id,
                            "key": withdraw_key,
                        },
                    )
                    await writer.commit()
                return True
            except DBAPIError:
                return False

        outcomes = await asyncio.gather(insert_start(), insert_withdrawal())
        assert sorted(outcomes) == [False, True]
        async with AsyncSessionLocal() as verify:
            handoffs = (
                (
                    await verify.execute(
                        select(PosRefundCashHandoff).where(
                            PosRefundCashHandoff.refund_request_id == request_id
                        )
                    )
                )
                .scalars()
                .all()
            )
            withdrawals = (
                (
                    await verify.execute(
                        select(PosRefundWithdrawal).where(
                            PosRefundWithdrawal.refund_request_id == request_id
                        )
                    )
                )
                .scalars()
                .all()
            )
            assert len(handoffs) + len(withdrawals) == 1
            with pytest.raises(DBAPIError, match="internal"):
                await verify.execute(
                    text(
                        "UPDATE pos_refund_workflow_guards SET terminal_state = NULL "
                        "WHERE refund_request_id = :request_id"
                    ),
                    {"request_id": request_id},
                )
            await verify.rollback()
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_rejects_cross_tenant_root_actor_time_and_null_forward_flags(
    client,
    session,
    seed_owner,
) -> None:
    """Direct writers cannot fabricate provenance or hide repair warnings."""
    case = await _seed_case(session, seed_owner, payment_method="cash")
    other_company = Company(id=uuid4(), name="Foreign refund provenance")
    other_owner = User(
        id=uuid4(),
        company_id=other_company.id,
        email=f"foreign-refund-{uuid4().hex[:8]}@test.local",
        name="Foreign refund actor",
        password_hash="not-used-by-refund-test",
        status="active",
    )
    inactive_actor = User(
        id=uuid4(),
        company_id=case.company_id,
        email=f"inactive-refund-{uuid4().hex[:8]}@test.local",
        name="Inactive refund actor",
        password_hash="not-used-by-refund-test",
        status="inactive",
    )
    session.add(other_company)
    await session.flush()
    session.add_all([other_owner, inactive_actor])
    await session.commit()

    request_key = f"pos-refund-provenance-request:{uuid4()}"
    begin_key = f"pos-refund-provenance-begin:{uuid4()}"
    completion_key = f"pos-refund-provenance-complete:{uuid4()}"
    finalize_key = f"pos-refund-provenance-finalize:{uuid4()}"
    keys = (request_key, begin_key, completion_key, finalize_key)
    try:
        async with AsyncSessionLocal() as tamper:
            tamper.add(
                PosRefundRequest(
                    id=uuid4(),
                    company_id=other_company.id,
                    order_id=case.order_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=case.shift_id,
                    approved_by=other_owner.id,
                    manager_override_user_id=None,
                    reason_code="CUSTOMER_REQUEST",
                    amount_minor=case.amount_minor,
                    mode="cash",
                    settlement_method="cash",
                    order_paid_snapshot_minor=case.amount_minor,
                    order_refundable_snapshot_minor=case.amount_minor,
                    accepted_at=datetime.now(UTC),
                    external_reference=None,
                    provider_settled_at=None,
                    client_action_id=f"cross-tenant:{uuid4()}",
                    idempotency_key=f"cross-tenant:{uuid4()}",
                    note="Must be rejected",
                )
            )
            with pytest.raises(DBAPIError, match="order provenance is invalid"):
                await tamper.commit()
            await tamper.rollback()

        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = UUID(accepted.json()["id"])
        accepted_at = datetime.fromisoformat(accepted.json()["accepted_at"])

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                PosRefundCashHandoff(
                    id=uuid4(),
                    company_id=case.company_id,
                    refund_request_id=request_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=case.shift_id,
                    started_at=accepted_at,
                    started_by=other_owner.id,
                    idempotency_key=f"foreign-start:{uuid4()}",
                )
            )
            with pytest.raises(DBAPIError, match="actor is outside"):
                await tamper.commit()
            await tamper.rollback()

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                PosRefundCashHandoff(
                    id=uuid4(),
                    company_id=case.company_id,
                    refund_request_id=request_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=case.shift_id,
                    started_at=accepted_at,
                    started_by=inactive_actor.id,
                    idempotency_key=f"inactive-start:{uuid4()}",
                )
            )
            with pytest.raises(DBAPIError, match="inactive"):
                await tamper.commit()
            await tamper.rollback()

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                PosRefundCashHandoff(
                    id=uuid4(),
                    company_id=case.company_id,
                    refund_request_id=request_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=case.shift_id,
                    started_at=accepted_at - timedelta(seconds=1),
                    started_by=case.owner_id,
                    idempotency_key=f"pre-acceptance-start:{uuid4()}",
                )
            )
            with pytest.raises(DBAPIError, match="outside its accepted workflow"):
                await tamper.commit()
            await tamper.rollback()

        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        handoff_at = datetime.fromisoformat(begun.json()["handoff_started_at"])

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                PosRefundCashHandoffCompletion(
                    id=uuid4(),
                    company_id=case.company_id,
                    refund_request_id=request_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=case.shift_id,
                    handed_over_at=handoff_at,
                    # Server accounting time cannot precede the durable begin
                    # acknowledgement, even when client evidence is skewed.
                    recorded_at=accepted_at,
                    recorded_by=case.owner_id,
                    captured_time_reconciled=False,
                    idempotency_key=f"pre-handoff-completion:{uuid4()}",
                )
            )
            with pytest.raises(DBAPIError, match="completion time predates"):
                await tamper.commit()
            await tamper.rollback()

        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_handed_over": True,
                "settled_at": handoff_at.isoformat(),
            },
            headers=_headers(case, completion_key),
        )
        assert completed.status_code == 201, completed.text
        assert completed.json()["status"] == "cash_handed_over_pending_accounting"
        completion_recorded_at = datetime.fromisoformat(
            completed.json()["cash_handed_over_recorded_at"]
        )

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                Refund(
                    id=uuid4(),
                    request_id=request_id,
                    order_id=case.order_id,
                    company_id=case.company_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    settlement_shift_id=case.shift_id,
                    approved_by=case.owner_id,
                    manager_override_user_id=None,
                    reason_code="CUSTOMER_REQUEST",
                    amount_minor=case.amount_minor,
                    mode="cash",
                    settlement_method="cash",
                    settled_at=handoff_at,
                    settled_by=case.owner_id,
                    external_reference=None,
                    provider_settled_at=None,
                    client_occurred_at=handoff_at,
                    captured_time_reconciled=None,
                    provider_evidence_reconciled=None,
                    settlement_idempotency_key=f"null-flags:{uuid4()}",
                    receipt_no=f"R/RF/26-27/{uuid4().int % 100000:05d}",
                    receipt_fiscal_year="2026-27",
                    receipt_issued_at=handoff_at,
                    customer_spend_reconciled=None,
                    note="Integration proof",
                )
            )
            with pytest.raises(DBAPIError, match="flags cannot be NULL"):
                await tamper.commit()
            await tamper.rollback()

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                Refund(
                    id=uuid4(),
                    request_id=request_id,
                    order_id=case.order_id,
                    company_id=case.company_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    settlement_shift_id=case.shift_id,
                    approved_by=case.owner_id,
                    manager_override_user_id=None,
                    reason_code="CUSTOMER_REQUEST",
                    amount_minor=case.amount_minor,
                    mode="cash",
                    settlement_method="cash",
                    settled_at=completion_recorded_at,
                    settled_by=case.owner_id,
                    external_reference=None,
                    provider_settled_at=None,
                    client_occurred_at=handoff_at,
                    captured_time_reconciled=True,
                    provider_evidence_reconciled=None,
                    settlement_idempotency_key=f"wrong-receipt-time:{uuid4()}",
                    receipt_no=f"R/RF/26-27/{uuid4().int % 100000:05d}",
                    receipt_fiscal_year="2026-27",
                    receipt_issued_at=completion_recorded_at - timedelta(seconds=1),
                    customer_spend_reconciled=True,
                    note="Integration proof",
                )
            )
            with pytest.raises(DBAPIError, match="receipt issue time"):
                await tamper.commit()
            await tamper.rollback()

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                Refund(
                    id=uuid4(),
                    request_id=request_id,
                    order_id=case.order_id,
                    company_id=case.company_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    settlement_shift_id=case.shift_id,
                    approved_by=case.owner_id,
                    manager_override_user_id=None,
                    reason_code="CUSTOMER_REQUEST",
                    amount_minor=case.amount_minor,
                    mode="cash",
                    settlement_method="cash",
                    settled_at=completion_recorded_at + timedelta(seconds=1),
                    settled_by=case.owner_id,
                    external_reference=None,
                    provider_settled_at=None,
                    client_occurred_at=handoff_at,
                    captured_time_reconciled=True,
                    provider_evidence_reconciled=None,
                    settlement_idempotency_key=f"wrong-accounting-time:{uuid4()}",
                    receipt_no=f"R/RF/26-27/{uuid4().int % 100000:05d}",
                    receipt_fiscal_year="2026-27",
                    receipt_issued_at=completion_recorded_at + timedelta(seconds=1),
                    customer_spend_reconciled=True,
                    note="Integration proof",
                )
            )
            with pytest.raises(DBAPIError, match="no matching durable handover"):
                await tamper.commit()
            await tamper.rollback()

        settled = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=_headers(case, finalize_key),
        )
        assert settled.status_code == 201, settled.text
        assert settled.json()["status"] == "settled"
    finally:
        await _cleanup(case, keys=keys)
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(delete(User).where(User.id == inactive_actor.id))
            await cleanup.execute(
                delete(AuditLog).where(AuditLog.company_id == other_company.id)
            )
            await cleanup.execute(delete(User).where(User.id == other_owner.id))
            await cleanup.execute(delete(Company).where(Company.id == other_company.id))
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("payment_method", ("card", "upi"))
async def test_original_provider_refund_is_reserved_before_provider_settlement(
    client,
    session,
    seed_owner,
    payment_method: str,
) -> None:
    case = await _seed_case(
        session,
        seed_owner,
        payment_method=payment_method,
    )
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-provider-begin:{uuid4()}"
    wrong_completion_key = f"pos-refund-provider-wrong-amount:{uuid4()}"
    completion_key = f"pos-refund-provider-complete:{uuid4()}"
    conflicting_completion_key = f"pos-refund-provider-conflict:{uuid4()}"
    finalize_key = f"pos-refund-provider-finalize:{uuid4()}"
    reconcile_key = f"pos-refund-evidence:{uuid4()}"
    replay_reconcile_key = f"pos-refund-evidence-replay:{uuid4()}"
    conflict_reconcile_key = f"pos-refund-evidence-conflict:{uuid4()}"
    keys = (
        request_key,
        begin_key,
        wrong_completion_key,
        completion_key,
        conflicting_completion_key,
        finalize_key,
        reconcile_key,
        replay_reconcile_key,
        conflict_reconcile_key,
    )
    # Provider references are opaque. A one-character success token must not
    # orphan real money after payout; it is recorded and flagged for review.
    provider_reference = "X"
    try:
        listed = await client.get(
            "/api/v1/pos/orders",
            params={"status": "paid"},
            headers=_headers(case, f"provider-order-list:{uuid4()}"),
        )
        assert listed.status_code == 200, listed.text
        listed_order = next(
            row for row in listed.json() if row["id"] == str(case.order_id)
        )
        assert listed_order["payment_methods"] == [payment_method]

        unsafe_prepaid_request = await client.post(
            "/api/v1/pos/refund-requests",
            json={
                **_request_payload(case, action_id=request_key, mode="original"),
                "external_reference": provider_reference,
                "provider_settled_at": case.captured_at.isoformat(),
            },
            headers=_headers(case, request_key),
        )
        assert unsafe_prepaid_request.status_code == 422
        assert (
            "Reserve the refund with the server before moving money"
            in unsafe_prepaid_request.text
        )

        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="original"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        assert accepted.json()["status"] == "accepted_provider_due"
        assert accepted.json()["external_reference"] is None
        request_id = accepted.json()["id"]
        rejected_before_begin = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "provider_completed": True,
                "external_reference": provider_reference,
                "provider_settled_at": case.captured_at.isoformat(),
            },
            headers=_headers(case, f"unsafe-provider-settle:{uuid4()}"),
        )
        assert rejected_before_begin.status_code == 422
        assert "has not opened this provider payout" in rejected_before_begin.text

        # A future writer cannot fabricate an in-progress fact on another
        # shift even if it knows the request UUID.
        async with AsyncSessionLocal() as tamper:
            tamper.add(
                PosRefundProviderPayoutStart(
                    id=uuid4(),
                    company_id=case.company_id,
                    refund_request_id=UUID(request_id),
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=uuid4(),
                    started_at=datetime.now(UTC),
                    started_by=case.owner_id,
                    idempotency_key=f"tampered-provider-start:{uuid4()}",
                )
            )
            with pytest.raises(DBAPIError, match="provenance"):
                await tamper.commit()
            await tamper.rollback()

        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-provider-payout",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_start_provider_payout": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        assert begun.json()["status"] == "provider_payout_in_progress"
        provider_settled_at = datetime.fromisoformat(
            begun.json()["provider_payout_started_at"]
        )

        blocked_close = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_float_minor},
            headers=_headers(case, f"close-blocked:{uuid4()}"),
        )
        assert blocked_close.status_code == 422
        assert "accepted POS refund" in blocked_close.text

        wrong_amount = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor + 1,
                "provider_completed": True,
                "external_reference": provider_reference,
                "provider_settled_at": provider_settled_at.isoformat(),
            },
            headers=_headers(case, wrong_completion_key),
        )
        assert wrong_amount.status_code == 422
        assert "server-confirmed provider amount differs" in wrong_amount.text
        async with AsyncSessionLocal() as verify:
            assert (
                await verify.execute(
                    select(PosRefundProviderSettlement).where(
                        PosRefundProviderSettlement.refund_request_id
                        == UUID(request_id)
                    )
                )
            ).scalar_one_or_none() is None

        settle_payload = {
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "provider_completed": True,
            "external_reference": provider_reference,
            "provider_settled_at": provider_settled_at.isoformat(),
        }
        first, duplicate = await asyncio.gather(
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
                json=settle_payload,
                headers=_headers(case, completion_key),
            ),
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
                json=settle_payload,
                headers=_headers(case, completion_key),
            ),
        )
        assert all(response.status_code in {201, 409} for response in (first, duplicate))
        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
            json=settle_payload,
            headers=_headers(case, completion_key),
        )
        assert completed.status_code == 201, completed.text
        assert completed.json()["status"] == "provider_completed_pending_accounting"
        assert completed.json()["external_reference"] == provider_reference

        # Completion is durable and visible even though financial accounting
        # has not yet run. This is the reinstall/response-loss recovery point.
        async with AsyncSessionLocal() as verify:
            provider_fact = (
                await verify.execute(
                    select(PosRefundProviderSettlement).where(
                        PosRefundProviderSettlement.refund_request_id == UUID(request_id)
                    )
                )
            ).scalar_one()
            assert provider_fact.external_reference == provider_reference
            provider_recorded_at = provider_fact.created_at
            assert (
                await verify.execute(
                    select(Refund).where(Refund.order_id == case.order_id)
                )
            ).scalar_one_or_none() is None

        recovered_task = await client.get(
            "/api/v1/pos/refund-requests",
            params={"shift_id": str(case.shift_id)},
            headers=_headers(case, f"provider-recovery-list:{uuid4()}"),
        )
        assert recovered_task.status_code == 200, recovered_task.text
        recovered_row = next(
            row for row in recovered_task.json() if row["id"] == request_id
        )
        assert recovered_row["status"] == "provider_completed_pending_accounting"
        assert recovered_row["provider_completed_by"] == str(case.owner_id)
        assert recovered_row["provider_completed_by_name"] == seed_owner["owner"].name
        assert recovered_row["provider_completion_recorded_at"] is not None

        finalize_payload = {
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
        }
        first_finalize, duplicate_finalize = await asyncio.gather(
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/finalize-provider",
                json=finalize_payload,
                headers=_headers(case, finalize_key),
            ),
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/finalize-provider",
                json=finalize_payload,
                headers=_headers(case, finalize_key),
            ),
        )
        assert all(
            response.status_code in {201, 409}
            for response in (first_finalize, duplicate_finalize)
        )
        settled = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-provider",
            json=finalize_payload,
            headers=_headers(case, finalize_key),
        )
        assert settled.status_code == 201, settled.text
        assert settled.json()["status"] == "settled"
        assert settled.json()["settlement_method"] == payment_method
        assert settled.json()["provider_evidence_reconciled"] is False
        conflicting_completion = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
            json={
                **settle_payload,
                "external_reference": "different-provider-reference",
            },
            headers=_headers(case, conflicting_completion_key),
        )
        assert conflicting_completion.status_code == 422
        assert "different immutable completion evidence" in conflicting_completion.text
        async with AsyncSessionLocal() as verify:
            refund = (
                await verify.execute(select(Refund).where(Refund.order_id == case.order_id))
            ).scalar_one()
            provider_fact = (
                await verify.execute(
                    select(PosRefundProviderSettlement).where(
                        PosRefundProviderSettlement.refund_request_id == UUID(request_id)
                    )
                )
            ).scalar_one()
            provider_start = (
                await verify.execute(
                    select(PosRefundProviderPayoutStart).where(
                        PosRefundProviderPayoutStart.refund_request_id
                        == UUID(request_id)
                    )
                )
            ).scalar_one()
            assert refund.external_reference == provider_reference
            assert refund.settled_at == provider_recorded_at
            assert refund.provider_settled_at == provider_settled_at
            assert refund.client_occurred_at == provider_settled_at
            assert refund.captured_time_reconciled is True
            assert refund.provider_evidence_reconciled is False
            assert refund.customer_spend_reconciled is True
            assert provider_start.started_at == provider_settled_at
            assert provider_fact.external_reference == provider_reference
            assert provider_fact.provider_settled_at == provider_settled_at
            assert provider_fact.provider_evidence_reconciled is False
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert shift.expected_minor == case.opening_float_minor
            assert customer.total_spent_minor == 0

        pending_evidence = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations/pending",
            headers=_headers(case, f"pending-refund-evidence:{uuid4()}"),
        )
        assert pending_evidence.status_code == 200, pending_evidence.text
        pending_provider = next(
            row
            for row in pending_evidence.json()
            if row["refund_id"] == str(refund.id)
            and row["evidence_kind"] == "provider_reference"
        )
        assert pending_provider["external_reference"] == provider_reference

        evidence_payload = {
            "refund_id": str(refund.id),
            "evidence_kind": "provider_reference",
            "proof_reference": "UPI dashboard case 8841",
            "reason": "Owner matched the short provider token to the completed payout",
        }
        reconciled = await client.post(
            "/api/v1/pos/refund-evidence-reconciliations",
            json=evidence_payload,
            headers=_headers(case, reconcile_key),
        )
        assert reconciled.status_code == 201, reconciled.text
        reconciliation_id = reconciled.json()["id"]
        assert reconciled.json()["reconciled_by"] == str(case.owner_id)
        assert reconciled.json()["reconciled_by_name"] == seed_owner["owner"].name

        # A new idempotency key with the exact same proof reuses the immutable
        # resolution; materially different proof is rejected rather than
        # overwriting the audit record.
        exact_duplicate = await client.post(
            "/api/v1/pos/refund-evidence-reconciliations",
            json=evidence_payload,
            headers=_headers(case, replay_reconcile_key),
        )
        assert exact_duplicate.status_code == 201, exact_duplicate.text
        assert exact_duplicate.json()["id"] == reconciliation_id
        conflict = await client.post(
            "/api/v1/pos/refund-evidence-reconciliations",
            json={
                **evidence_payload,
                "proof_reference": "Different provider case 9999",
            },
            headers=_headers(case, conflict_reconcile_key),
        )
        assert conflict.status_code == 422
        assert "already reconciled with different proof" in conflict.text

        pending_after = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations/pending",
            headers=_headers(case, f"pending-refund-evidence-after:{uuid4()}"),
        )
        assert pending_after.status_code == 200, pending_after.text
        assert not any(
            row["refund_id"] == str(refund.id)
            and row["evidence_kind"] == "provider_reference"
            for row in pending_after.json()
        )
        evidence_history = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations",
            headers=_headers(case, f"refund-evidence-history:{uuid4()}"),
        )
        assert evidence_history.status_code == 200, evidence_history.text
        assert any(
            row["id"] == reconciliation_id
            and row["proof_reference"] == evidence_payload["proof_reference"]
            for row in evidence_history.json()
        )

        async with AsyncSessionLocal() as verify:
            source_refund = await verify.get(Refund, refund.id)
            assert source_refund is not None
            assert source_refund.provider_evidence_reconciled is False
            rows = (
                (
                    await verify.execute(
                        select(PosRefundEvidenceReconciliation).where(
                            PosRefundEvidenceReconciliation.refund_id == refund.id
                        )
                    )
                )
                .scalars()
                .all()
            )
            assert len(rows) == 1

        async with AsyncSessionLocal() as tamper:
            with pytest.raises(DBAPIError, match="append-only"):
                await tamper.execute(
                    update(PosRefundEvidenceReconciliation)
                    .where(
                        PosRefundEvidenceReconciliation.id
                        == UUID(reconciliation_id)
                    )
                    .values(proof_reference="tampered")
                )
            await tamper.rollback()

        async with AsyncSessionLocal() as tamper:
            with pytest.raises(DBAPIError, match="append-only"):
                await tamper.execute(
                    update(PosRefundProviderPayoutStart)
                    .where(
                        PosRefundProviderPayoutStart.refund_request_id
                        == UUID(request_id)
                    )
                    .values(started_by=uuid4())
                )
            await tamper.rollback()
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_provider_completion_cannot_be_resolved_during_finalization(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="upi")
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-provider-begin:{uuid4()}"
    settle_key = f"pos-refund-provider-settle:{uuid4()}"
    finalize_key = f"pos-refund-provider-finalize:{uuid4()}"
    resolve_key = f"pos-refund-provider-resolve:{uuid4()}"
    keys = (request_key, begin_key, settle_key, finalize_key, resolve_key)
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="original"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-provider-payout",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_start_provider_payout": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        outcome_at = datetime.fromisoformat(begun.json()["provider_payout_started_at"])

        # PostgreSQL CHECK semantics treat UNKNOWN as passing unless every
        # required provider-evidence field is explicitly non-NULL. Revision
        # 0048 closes that historical hole independently of API validation.
        async with AsyncSessionLocal() as asymmetric_evidence:
            with pytest.raises(
                DBAPIError,
                match="ck_pos_refund_withdrawal_verification",
            ):
                await asymmetric_evidence.execute(
                    text(
                        "INSERT INTO pos_refund_withdrawals ("
                        "id, company_id, refund_request_id, branch_id, "
                        "terminal_id, shift_id, resolution, reason, "
                        "verification_reference, verification_status, "
                        "verified_at, withdrawn_at, withdrawn_by, "
                        "idempotency_key) VALUES ("
                        ":id, :company_id, :request_id, :branch_id, "
                        ":terminal_id, :shift_id, 'provider_payout_abandoned', "
                        ":reason, NULL, 'no_matching_transaction', :verified_at, "
                        ":withdrawn_at, :actor, :key)"
                    ),
                    {
                        "id": uuid4(),
                        "company_id": case.company_id,
                        "request_id": UUID(request_id),
                        "branch_id": case.branch_id,
                        "terminal_id": case.terminal_id,
                        "shift_id": case.shift_id,
                        "reason": "Provider evidence asymmetry proof",
                        "verified_at": outcome_at,
                        "withdrawn_at": outcome_at,
                        "actor": case.owner_id,
                        "key": f"asymmetric-provider-evidence:{uuid4()}",
                    },
                )
                await asymmetric_evidence.commit()
            await asymmetric_evidence.rollback()

        unsafe_withdrawal = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/withdraw-provider",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "provider_not_completed": True,
                "reason": "Provider dashboard showed no completed refund",
                "withdrawn_at": outcome_at.isoformat(),
            },
            headers=_headers(case, f"unsafe-withdraw:{uuid4()}"),
        )
        assert unsafe_withdrawal.status_code == 422
        assert "already in progress" in unsafe_withdrawal.text

        same_actor_unprotected_token = issue_access_token(
            user_id=case.owner_id,
            company_id=case.company_id,
            branch_id=case.branch_id,
            roles=["staff"],
            auth_version=case.owner_auth_version,
            extra={"protected_access": False, "audit_access": False},
        )
        unprotected_headers = _headers(case, f"unprotected-resolve:{uuid4()}")
        unprotected_headers["Authorization"] = (
            f"Bearer {same_actor_unprotected_token}"
        )
        unprotected = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/resolve-provider-payout",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "provider_not_completed": True,
                "provider_status": "no_matching_transaction",
                "verification_reference": "provider-search-123",
                "provider_checked_at": outcome_at.isoformat(),
                "reason": "Provider dashboard showed no completed refund",
            },
            headers=unprotected_headers,
        )
        assert unprotected.status_code == 403

        unknown_status = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/resolve-provider-payout",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "provider_not_completed": True,
                "provider_status": "unknown",
                "verification_reference": "provider-search-123",
                "provider_checked_at": outcome_at.isoformat(),
                "reason": "Provider response was ambiguous",
            },
            headers=_headers(case, f"unknown-resolve:{uuid4()}"),
        )
        assert unknown_status.status_code == 422

        completion = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "provider_completed": True,
                "external_reference": f"upi-race-{uuid4()}",
                "provider_settled_at": outcome_at.isoformat(),
            },
            headers=_headers(case, settle_key),
        )
        assert completion.status_code == 201, completion.text
        assert completion.json()["status"] == "provider_completed_pending_accounting"

        # Once the external value movement is committed, a competing "not
        # completed" decision can no longer erase it. Accounting finalization
        # remains safe to retry from another process/device.
        finalization, resolution = await asyncio.gather(
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/finalize-provider",
                json={
                    "shift_id": str(case.shift_id),
                    "expected_amount_minor": case.amount_minor,
                },
                headers=_headers(case, finalize_key),
            ),
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/resolve-provider-payout",
                json={
                    "shift_id": str(case.shift_id),
                    "expected_amount_minor": case.amount_minor,
                    "provider_not_completed": True,
                    "provider_status": "no_matching_transaction",
                    "verification_reference": "provider-search-123",
                    "provider_checked_at": outcome_at.isoformat(),
                    "reason": "Provider dashboard showed no completed refund",
                },
                headers=_headers(case, resolve_key),
            ),
        )
        assert finalization.status_code == 201, finalization.text
        assert finalization.json()["status"] == "settled"
        assert resolution.status_code == 422, resolution.text
        # Both observations are valid under READ COMMITTED: the losing owner
        # resolution may see either the durable completion or the finalized
        # Refund.  Neither outcome permits an abandonment fact.
        assert any(
            message in resolution.text
            for message in (
                "completion is already recorded",
                "already settled and cannot be resolved as failed",
            )
        )

        async with AsyncSessionLocal() as verify:
            request_uuid = UUID(request_id)
            refund = (
                await verify.execute(
                    select(Refund).where(Refund.request_id == request_uuid)
                )
            ).scalar_one_or_none()
            withdrawn = (
                await verify.execute(
                    select(PosRefundWithdrawal).where(
                        PosRefundWithdrawal.refund_request_id == request_uuid
                    )
                )
            ).scalar_one_or_none()
            provider_fact = (
                await verify.execute(
                    select(PosRefundProviderSettlement).where(
                        PosRefundProviderSettlement.refund_request_id == request_uuid
                    )
                )
            ).scalar_one_or_none()
            assert refund is not None
            assert provider_fact is not None
            assert withdrawn is None
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_handover_posts_even_when_customer_ltv_drifts_after_authorisation(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-begin:{uuid4()}"
    completion_key = f"pos-refund-complete:{uuid4()}"
    finalize_key = f"pos-refund-finalize:{uuid4()}"
    reconcile_key = f"customer-spend-reconcile:{uuid4()}"
    evidence_key = f"captured-time-reconcile:{uuid4()}"
    keys = (
        request_key,
        begin_key,
        completion_key,
        finalize_key,
        reconcile_key,
        evidence_key,
    )
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        # A skewed tablet clock is preserved as evidence but must not erase a
        # real cash payout after the server-confirmed handover began.
        handed_over_at = datetime.fromisoformat(
            begun.json()["handoff_started_at"]
        ) - timedelta(hours=1)

        # Simulate an ancillary LTV repair racing after the server authorised
        # the physical handover. The real payout must remain recordable.
        async with AsyncSessionLocal() as drift:
            customer = await drift.get(Customer, case.customer_id)
            assert customer is not None
            customer.total_spent_minor = 0
            await drift.commit()

        completed = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_handed_over": True,
                "settled_at": handed_over_at.isoformat(),
            },
            headers=_headers(case, completion_key),
        )
        assert completed.status_code == 201, completed.text
        assert completed.json()["status"] == "cash_handed_over_pending_accounting"
        assert completed.json()["captured_time_reconciled"] is False

        settled = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=_headers(case, finalize_key),
        )
        assert settled.status_code == 201, settled.text
        assert settled.json()["status"] == "settled"
        assert settled.json()["customer_spend_reconciled"] is False
        assert settled.json()["captured_time_reconciled"] is False

        async with AsyncSessionLocal() as verify:
            refund = (
                await verify.execute(
                    select(Refund).where(Refund.order_id == case.order_id)
                )
            ).scalar_one()
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert refund.customer_spend_reconciled is False
            assert refund.client_occurred_at == handed_over_at
            assert refund.captured_time_reconciled is False
            assert shift.expected_minor == case.opening_float_minor
            assert customer.total_spent_minor == 0

        pending = await client.get(
            "/api/v1/pos/customer-spend-reconciliations/pending",
            headers=_headers(case, f"pending-reconciliations:{uuid4()}"),
        )
        assert pending.status_code == 200, pending.text
        pending_source = next(
            item
            for item in pending.json()
            if item["source_type"] == "pos_refund"
            and item["customer_id"] == str(case.customer_id)
        )
        reconcile_payload = {
            "customer_id": str(case.customer_id),
            "pos_refund_id": pending_source["source_id"],
            "expected_current_total_spent_minor": 0,
            "reason": "Repair drift from legacy gross-only lifetime spend",
        }
        reconciled = await client.post(
            "/api/v1/pos/customer-spend-reconciliations",
            json=reconcile_payload,
            headers=_headers(case, reconcile_key),
        )
        assert reconciled.status_code == 201, reconciled.text
        assert reconciled.json()["before_total_spent_minor"] == 0
        assert reconciled.json()["after_total_spent_minor"] == 0
        assert reconciled.json()["adjustment_minor"] == 0
        assert reconciled.json()["pos_gross_minor"] == case.amount_minor
        assert reconciled.json()["pos_refunds_minor"] == case.amount_minor
        replayed = await client.post(
            "/api/v1/pos/customer-spend-reconciliations",
            json=reconcile_payload,
            headers=_headers(case, reconcile_key),
        )
        assert replayed.status_code == 201
        assert replayed.json()["id"] == reconciled.json()["id"]
        after_pending = await client.get(
            "/api/v1/pos/customer-spend-reconciliations/pending",
            headers=_headers(case, f"pending-reconciliations-after:{uuid4()}"),
        )
        assert all(
            item["source_id"] != pending_source["source_id"]
            for item in after_pending.json()
        )

        pending_evidence = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations/pending",
            headers=_headers(case, f"pending-time-evidence:{uuid4()}"),
        )
        assert pending_evidence.status_code == 200, pending_evidence.text
        assert any(
            item["refund_id"] == str(refund.id)
            and item["evidence_kind"] == "captured_time"
            for item in pending_evidence.json()
        )
        time_reconciled = await client.post(
            "/api/v1/pos/refund-evidence-reconciliations",
            json={
                "refund_id": str(refund.id),
                "evidence_kind": "captured_time",
                "proof_reference": "CCTV handover frame 2026-08-26T12:14Z",
                "reason": "Owner verified the physical handover despite tablet clock skew",
            },
            headers=_headers(case, evidence_key),
        )
        assert time_reconciled.status_code == 201, time_reconciled.text
        pending_evidence_after = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations/pending",
            headers=_headers(case, f"pending-time-evidence-after:{uuid4()}"),
        )
        assert pending_evidence_after.status_code == 200
        assert not any(
            item["refund_id"] == str(refund.id)
            and item["evidence_kind"] == "captured_time"
            for item in pending_evidence_after.json()
        )
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_completion_cannot_be_resolved_during_finalization(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    request_key = f"pos-refund-request:{uuid4()}"
    begin_key = f"pos-refund-begin:{uuid4()}"
    completion_key = f"pos-refund-complete:{uuid4()}"
    finalize_key = f"pos-refund-finalize:{uuid4()}"
    resolve_key = f"pos-refund-resolve:{uuid4()}"
    keys = (request_key, begin_key, completion_key, finalize_key, resolve_key)
    try:
        accepted = await client.post(
            "/api/v1/pos/refund-requests",
            json=_request_payload(case, action_id=request_key, mode="cash"),
            headers=_headers(case, request_key),
        )
        assert accepted.status_code == 201, accepted.text
        request_id = accepted.json()["id"]
        begun = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_handover": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        event_at = begun.json()["handoff_started_at"]

        completion = await client.post(
            f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "cash_handed_over": True,
                "settled_at": event_at,
            },
            headers=_headers(case, completion_key),
        )
        assert completion.status_code == 201, completion.text
        assert completion.json()["status"] == "cash_handed_over_pending_accounting"

        finalization, resolution = await asyncio.gather(
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
                json={
                    "shift_id": str(case.shift_id),
                    "expected_amount_minor": case.amount_minor,
                },
                headers=_headers(case, finalize_key),
            ),
            client.post(
                f"/api/v1/pos/refund-requests/{request_id}/resolve-cash-handoff",
                json={
                    "shift_id": str(case.shift_id),
                    "expected_amount_minor": case.amount_minor,
                    "cash_not_handed_over": True,
                    "drawer_unchanged": True,
                    "reason": "Cashier and owner confirmed no cash left the drawer",
                    "resolved_at": event_at,
                },
                headers=_headers(case, resolve_key),
            ),
        )
        assert finalization.status_code == 201, finalization.text
        assert finalization.json()["status"] == "settled"
        assert resolution.status_code == 422, resolution.text
        assert any(
            message in resolution.text
            for message in (
                "already recorded as handed",
                "already settled. The drawer movement remains recorded",
            )
        )

        async with AsyncSessionLocal() as verify:
            request_uuid = UUID(request_id)
            refund = (
                await verify.execute(
                    select(Refund).where(Refund.request_id == request_uuid)
                )
            ).scalar_one_or_none()
            withdrawal = (
                await verify.execute(
                    select(PosRefundWithdrawal).where(
                        PosRefundWithdrawal.refund_request_id == request_uuid
                    )
                )
            ).scalar_one_or_none()
            completion_fact = (
                await verify.execute(
                    select(PosRefundCashHandoffCompletion).where(
                        PosRefundCashHandoffCompletion.refund_request_id
                        == request_uuid
                    )
                )
            ).scalar_one_or_none()
            assert completion_fact is not None
            assert refund is not None
            assert withdrawal is None
    finally:
        await _cleanup(case, keys=keys)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_legacy_refund_is_visible_reconciled_once_and_append_only(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    legacy_refund_id = uuid4()
    wrong_state_key = f"legacy-ltv-wrong-state:{uuid4()}"
    first_key = f"legacy-ltv-reconcile-a:{uuid4()}"
    second_key = f"legacy-ltv-reconcile-b:{uuid4()}"
    keys = (wrong_state_key, first_key, second_key)
    other_company = Company(id=uuid4(), name="Foreign legacy LTV repair")
    other_owner = User(
        id=uuid4(),
        company_id=other_company.id,
        email=f"foreign-ltv-{uuid4().hex[:8]}@test.local",
        name="Foreign LTV actor",
        password_hash="not-used-by-refund-test",
        status="active",
    )
    session.add(other_company)
    await session.flush()
    session.add(other_owner)
    await session.commit()
    try:
        async with AsyncSessionLocal() as writer:
            # Recreate the exact post-migration state in this already-at-head
            # HTTP fixture. PostgreSQL NOT VALID keeps rows that existed at
            # upgrade time but correctly rejects inserting this shape now, so
            # the test removes and reinstalls the constraint atomically around
            # the historical fixture only.
            await writer.execute(
                text(
                    "ALTER TABLE refunds DROP CONSTRAINT "
                    "ck_refund_forward_write_linkage"
                )
            )
            await writer.execute(
                text(
                    "ALTER TABLE refunds DISABLE TRIGGER "
                    "trg_refunds_final_order_balance"
                )
            )
            writer.add(
                Refund(
                    id=legacy_refund_id,
                    order_id=case.order_id,
                    approved_by=case.owner_id,
                    reason_code="LEGACY_HISTORY",
                    amount_minor=100,
                    mode="cash",
                )
            )
            await writer.flush()
            await writer.execute(
                text(
                    "ALTER TABLE refunds ADD CONSTRAINT "
                    "ck_refund_forward_write_linkage "
                    "CHECK (request_id IS NOT NULL) NOT VALID"
                )
            )
            await writer.execute(
                text(
                    "ALTER TABLE refunds ENABLE TRIGGER "
                    "trg_refunds_final_order_balance"
                )
            )
            await writer.commit()

        pending = await client.get(
            "/api/v1/pos/customer-spend-reconciliations/pending",
            headers=_headers(case, f"legacy-ltv-pending:{uuid4()}"),
        )
        assert pending.status_code == 200, pending.text
        pending_row = next(
            row for row in pending.json() if row["source_id"] == str(legacy_refund_id)
        )
        assert pending_row["source_type"] == "pos_refund"
        assert pending_row["reconciliation_state"] == "legacy_unknown"
        assert pending_row["amount_minor"] == 100
        assert pending_row["order_id"] == str(case.order_id)
        assert pending_row["refund_reason_code"] == "LEGACY_HISTORY"
        assert pending_row["current_total_spent_minor"] == case.amount_minor

        # The database independently proves both tenant scope and the source's
        # unknown legacy state; callers cannot label it as a verified forward
        # underflow or repair another company's customer.
        async with AsyncSessionLocal() as tamper:
            tamper.add(
                CustomerSpendReconciliation(
                    id=uuid4(),
                    company_id=case.company_id,
                    customer_id=case.customer_id,
                    pos_refund_id=legacy_refund_id,
                    membership_refund_settlement_id=None,
                    source_reconciliation_state="unreconciled",
                    source_amount_minor=100,
                    before_total_spent_minor=case.amount_minor,
                    after_total_spent_minor=case.amount_minor - 100,
                    adjustment_minor=-100,
                    pos_gross_minor=case.amount_minor,
                    membership_gross_minor=0,
                    pos_refunds_minor=100,
                    membership_refunds_minor=0,
                    reason="Wrong source-state proof",
                    reconciled_at=datetime.now(UTC),
                    reconciled_by=case.owner_id,
                    idempotency_key=wrong_state_key,
                )
            )
            with pytest.raises(DBAPIError, match="source state is incorrect"):
                await tamper.commit()
            await tamper.rollback()

        async with AsyncSessionLocal() as tamper:
            tamper.add(
                CustomerSpendReconciliation(
                    id=uuid4(),
                    company_id=other_company.id,
                    customer_id=case.customer_id,
                    pos_refund_id=legacy_refund_id,
                    membership_refund_settlement_id=None,
                    source_reconciliation_state="legacy_unknown",
                    source_amount_minor=100,
                    before_total_spent_minor=case.amount_minor,
                    after_total_spent_minor=case.amount_minor - 100,
                    adjustment_minor=-100,
                    pos_gross_minor=case.amount_minor,
                    membership_gross_minor=0,
                    pos_refunds_minor=100,
                    membership_refunds_minor=0,
                    reason="Cross-company source must fail",
                    reconciled_at=datetime.now(UTC),
                    reconciled_by=other_owner.id,
                    idempotency_key=f"foreign-legacy-ltv:{uuid4()}",
                )
            )
            with pytest.raises(DBAPIError, match="invalid customer"):
                await tamper.commit()
            await tamper.rollback()

        reconcile_payload = {
            "customer_id": str(case.customer_id),
            "pos_refund_id": str(legacy_refund_id),
            "expected_current_total_spent_minor": case.amount_minor,
            "reason": "Owner verified historical order, payment and refund history",
        }
        first, second = await asyncio.gather(
            client.post(
                "/api/v1/pos/customer-spend-reconciliations",
                json=reconcile_payload,
                headers=_headers(case, first_key),
            ),
            client.post(
                "/api/v1/pos/customer-spend-reconciliations",
                json=reconcile_payload,
                headers=_headers(case, second_key),
            ),
        )
        assert sorted((first.status_code, second.status_code)) == [201, 422]
        winner = first if first.status_code == 201 else second
        winner_key = first_key if first.status_code == 201 else second_key
        assert winner.json()["source_reconciliation_state"] == "legacy_unknown"
        assert winner.json()["before_total_spent_minor"] == case.amount_minor
        assert winner.json()["after_total_spent_minor"] == case.amount_minor - 100
        replay = await client.post(
            "/api/v1/pos/customer-spend-reconciliations",
            json=reconcile_payload,
            headers=_headers(case, winner_key),
        )
        assert replay.status_code == 201, replay.text
        assert replay.json()["id"] == winner.json()["id"]

        async with AsyncSessionLocal() as verify:
            customer = await verify.get(Customer, case.customer_id)
            fact = (
                await verify.execute(
                    select(CustomerSpendReconciliation).where(
                        CustomerSpendReconciliation.pos_refund_id == legacy_refund_id
                    )
                )
            ).scalar_one()
            assert customer.total_spent_minor == case.amount_minor - 100
            assert fact.source_reconciliation_state == "legacy_unknown"
            assert fact.reconciled_by == case.owner_id

        async with AsyncSessionLocal() as tamper:
            with pytest.raises(DBAPIError, match="append-only"):
                await tamper.execute(
                    update(Refund)
                    .where(Refund.id == legacy_refund_id)
                    .values(amount_minor=200)
                )
            await tamper.rollback()
            with pytest.raises(DBAPIError, match="append-only"):
                await tamper.execute(delete(Refund).where(Refund.id == legacy_refund_id))
            await tamper.rollback()
    finally:
        async with AsyncSessionLocal() as cleanup:
            await _set_disposable_cleanup_triggers(cleanup, enabled=False)
            reconciliation_ids = list(
                (
                    await cleanup.execute(
                        select(CustomerSpendReconciliation.id).where(
                            CustomerSpendReconciliation.pos_refund_id == legacy_refund_id
                        )
                    )
                ).scalars()
            )
            await cleanup.execute(
                delete(AuditLog).where(
                    AuditLog.entity_id.in_(
                        [str(legacy_refund_id), *(str(row_id) for row_id in reconciliation_ids)]
                    )
                )
            )
            await cleanup.execute(
                delete(CustomerSpendReconciliation).where(
                    CustomerSpendReconciliation.pos_refund_id == legacy_refund_id
                )
            )
            await cleanup.execute(delete(Refund).where(Refund.id == legacy_refund_id))
            await _set_disposable_cleanup_triggers(cleanup, enabled=True)
            await cleanup.commit()
        await _cleanup(case, keys=keys)
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(AuditLog).where(AuditLog.company_id == other_company.id)
            )
            await cleanup.execute(delete(User).where(User.id == other_owner.id))
            await cleanup.execute(delete(Company).where(Company.id == other_company.id))
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_refund_request_rejects_untrusted_approval_and_missing_action_provenance(
    client,
    session,
    seed_owner,
) -> None:
    case = await _seed_case(session, seed_owner, payment_method="cash")
    request_key = f"pos-refund-request:{uuid4()}"
    keys = (request_key,)
    payload = _request_payload(case, action_id=request_key, mode="cash")
    try:
        missing_action_headers = _headers(case, request_key)
        missing_action_headers.pop("X-Client-Action-Id")
        missing_action = await client.post(
            "/api/v1/pos/refund-requests",
            json=payload,
            headers=missing_action_headers,
        )
        assert missing_action.status_code == 422
        assert "X-Client-Action-Id is required" in missing_action.text

        untrusted_approval = await client.post(
            "/api/v1/pos/refund-requests",
            json={**payload, "manager_override_user_id": str(uuid4())},
            headers=_headers(case, request_key),
        )
        assert untrusted_approval.status_code == 422
        assert "cannot name its own manager approver" in untrusted_approval.text

        async with AsyncSessionLocal() as verify:
            requests = (
                await verify.execute(
                    select(PosRefundRequest).where(
                        PosRefundRequest.order_id == case.order_id
                    )
                )
            ).scalars().all()
            assert requests == []
    finally:
        await _cleanup(case, keys=keys)
