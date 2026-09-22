"""PostgreSQL and endpoint proof for atomic multi-rail POS settlement."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import psycopg
import pytest
from psycopg import errors
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.api.v1.pos import router as pos_router
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone
from app.models import (
    GoogleSheetsDelivery,
    IdempotencyKey,
    Order,
    OrderCheckoutClaim,
    Payment,
    Shift,
)
from app.services.accounting.accounts import (
    CASH,
    POS_SETTLEMENT_CLEARING,
    SALES_RETURNS,
    SALES_REVENUE,
    UPI_CLEARING,
)
from app.services.accounting.ledger import build_operational_ledger
from app.services.pos.checkout_claims import acquire_checkout_claim
from app.services.reports import ReportsAggregator
from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)
from tests.integration.test_pos_payment_source_integrity import (
    _seed_open_order,
    _sync_dsn,
)


def _request(
    key: str,
    body_hash: str | None = None,
    *,
    client_action_id: str | None = None,
) -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key=key,
            idempotency_request_hash=body_hash or f"hash:{key}",
        ),
        headers={"X-Client-Action-Id": client_action_id} if client_action_id is not None else {},
    )


def _tenant(ids: dict[str, UUID | datetime | int]) -> TenantContext:
    return TenantContext(
        user_id=ids["user"],
        company_id=ids["company"],
        branch_id=ids["branch"],
        terminal_id=ids["terminal"],
        roles=("owner",),
        protected_access=True,
    )


def _payload(*, cash_minor: int = 1_000, upi_minor: int = 1_500):
    return pos_router.PaymentBundleCreate(
        payments=[
            pos_router.PaymentBundleLegCreate(
                method="cash",
                amount_minor=cash_minor,
                tendered_minor=cash_minor + 200,
            ),
            pos_router.PaymentBundleLegCreate(
                method="upi",
                amount_minor=upi_minor,
                ref_external=f"UPI-SPLIT-{uuid4().hex[:12]}",
            ),
        ],
        expected_order_total_minor=2_500,
        expected_due_minor=2_500,
    )


def _mark_paid(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime | int],
) -> datetime:
    issued_at = datetime.now(UTC).replace(microsecond=0)
    connection.execute(
        "UPDATE orders SET status='paid', closed_at=%s, invoice_issued_at=%s, "
        "invoice_no=%s, fiscal_year='2026-27' WHERE id=%s",
        (issued_at, issued_at, f"D/MA/26-27/{uuid4().hex[:8]}", ids["order"]),
    )
    return issued_at


def _insert_payment(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime | int],
    *,
    method: str,
    amount_minor: int,
    paid_at: datetime,
) -> None:
    tendered_minor = amount_minor if method == "cash" else None
    change_minor = 0 if method == "cash" else None
    connection.execute(
        "INSERT INTO payments "
        "(id, order_id, shift_id, method, amount_minor, tendered_minor, "
        "change_minor, paid_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
        (
            uuid4(),
            ids["order"],
            ids["shift"],
            method,
            amount_minor,
            tendered_minor,
            change_minor,
            paid_at,
        ),
    )


def _attempt_partial_bundle(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime | int],
) -> None:
    with connection.transaction():
        issued_at = _mark_paid(connection, ids)
        _insert_payment(
            connection,
            ids,
            method="cash",
            amount_minor=1_000,
            paid_at=issued_at,
        )


def _attempt_overpaid_bundle(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime | int],
) -> None:
    with connection.transaction():
        issued_at = _mark_paid(connection, ids)
        _insert_payment(
            connection,
            ids,
            method="cash",
            amount_minor=1_500,
            paid_at=issued_at,
        )
        _insert_payment(
            connection,
            ids,
            method="upi",
            amount_minor=1_500,
            paid_at=issued_at,
        )


@pytest.mark.integration
def test_0080_allows_complete_bundle_but_rejects_partial_and_overpaid_commit() -> None:
    with _disposable_database("erp_pos_split_guard") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "head")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        with psycopg.connect(_sync_dsn(database_url)) as connection:
            complete = _seed_open_order(connection)
            with connection.transaction():
                issued_at = _mark_paid(connection, complete)
                _insert_payment(
                    connection,
                    complete,
                    method="cash",
                    amount_minor=1_000,
                    paid_at=issued_at,
                )
                _insert_payment(
                    connection,
                    complete,
                    method="upi",
                    amount_minor=1_500,
                    paid_at=issued_at,
                )
            rows = connection.execute(
                "SELECT method, amount_minor FROM payments WHERE order_id=%s ORDER BY method",
                (complete["order"],),
            ).fetchall()
            assert rows == [("cash", 1_000), ("upi", 1_500)]

            partial = _seed_open_order(connection)
            with pytest.raises(errors.CheckViolation, match="payment total"):
                _attempt_partial_bundle(connection, partial)
            assert connection.execute(
                "SELECT status FROM orders WHERE id=%s", (partial["order"],)
            ).fetchone() == ("open",)
            assert connection.execute(
                "SELECT count(*) FROM payments WHERE order_id=%s", (partial["order"],)
            ).fetchone() == (0,)

            overpaid = _seed_open_order(connection)
            with pytest.raises(errors.CheckViolation, match="exceeds"):
                _attempt_overpaid_bundle(connection, overpaid)

        downgraded = _run_alembic(database_url, "downgrade", "0079")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        reupgraded = _run_alembic(database_url, "upgrade", "head")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr


@pytest.mark.integration
@pytest.mark.asyncio
async def test_payment_bundle_endpoint_is_atomic_replay_safe_and_claim_scoped(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with _disposable_database("erp_pos_split_api") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "head")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            paid_ids = _seed_open_order(connection)
            rollback_ids = _seed_open_order(connection)
            connection.execute(
                "UPDATE companies SET google_sheets_mirror_enabled=true, "
                "google_sheets_webhook_url='https://sheets.test.invalid/mirror', "
                "google_sheets_signing_secret_ciphertext='test-ciphertext', "
                "google_sheets_configured_at=%s, google_sheets_configuration_id=%s "
                "WHERE id=%s",
                (datetime.now(UTC), uuid4(), paid_ids["company"]),
            )
            connection.commit()

        engine = create_async_engine(
            database_url.replace("postgresql+psycopg://", "postgresql+asyncpg://", 1),
            pool_pre_ping=True,
        )
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        try:
            # Turn the first bill into shared checkout work and acquire its one
            # claim before collecting either tender leg.
            async with sessions() as session:
                order = await session.get(Order, paid_ids["order"], with_for_update=True)
                assert order is not None
                order.status = "held"
                order.held_at = datetime.now(UTC)
                await session.commit()
            async with sessions() as session:
                order = await session.get(Order, paid_ids["order"], with_for_update=True)
                assert order is not None
                grant = await acquire_checkout_claim(
                    session,
                    order=order,
                    claimant_user_id=paid_ids["user"],
                    terminal_id=paid_ids["terminal"],
                    paid_minor=0,
                )
                claim_token = grant.token
                await session.commit()

            key = f"split-payment:{uuid4()}"
            payload = _payload()
            async with sessions() as session:
                response = await pos_router.record_payment_bundle(
                    paid_ids["order"],
                    payload,
                    session,
                    _request(key),
                    _tenant(paid_ids),
                    claim_token,
                )
                await session.commit()
            assert response.total_amount_minor == 2_500
            assert response.payment_breakdown_minor == {
                "cash": 1_000,
                "card": 0,
                "upi": 1_500,
                "qr": 0,
                "wallet": 0,
            }
            assert response.payments[0].change_minor == 200
            assert response.order_status == "paid"

            # A mixed-rail receipt can only enter the explicit cash-refund
            # workflow. Never guess one "original" rail and silently move the
            # entire refund through it.
            refund_action_id = f"split-refund:{uuid4()}"
            async with sessions() as session:
                with pytest.raises(
                    BusinessRuleError,
                    match="Mixed-payment orders need an explicit cash refund",
                ):
                    await pos_router.create_pos_refund_request(
                        pos_router.PosRefundRequestCreate(
                            order_id=paid_ids["order"],
                            shift_id=paid_ids["shift"],
                            reason_code="customer_request",
                            amount_minor=500,
                            expected_paid_minor=2_500,
                            expected_refundable_minor=2_500,
                            mode="original",
                            client_action_id=refund_action_id,
                            note="Prove mixed tender never guesses an original rail",
                        ),
                        session,
                        _request(
                            refund_action_id,
                            client_action_id=refund_action_id,
                        ),
                        _tenant(paid_ids),
                    )
                await session.rollback()

            async with sessions() as session:
                delivery = (
                    await session.execute(
                        select(GoogleSheetsDelivery).where(
                            GoogleSheetsDelivery.company_id == paid_ids["company"],
                            GoogleSheetsDelivery.source_type == "pos_order",
                            GoogleSheetsDelivery.source_id == str(paid_ids["order"]),
                        )
                    )
                ).scalar_one()
            assert delivery.payload["payment_method"] == "split"
            assert delivery.payload["payment_breakdown_minor"] == {
                "cash": 1_000,
                "card": 0,
                "upi": 1_500,
                "qr": 0,
                "wallet": 0,
            }

            # Response-loss replay reads the one stored bundle receipt. It does
            # not recreate rows, finalize again, or emit another mirror event.
            async with sessions() as session:
                replay = await pos_router.record_payment_bundle(
                    paid_ids["order"],
                    payload,
                    session,
                    _request(key),
                    _tenant(paid_ids),
                    claim_token,
                )
                await session.commit()
            assert replay.model_dump(mode="json") == response.model_dump(mode="json")

            async with sessions() as session:
                payment_count = await session.scalar(
                    select(func.count())
                    .select_from(Payment)
                    .where(Payment.order_id == paid_ids["order"])
                )
                claim_count = await session.scalar(
                    select(func.count())
                    .select_from(OrderCheckoutClaim)
                    .where(OrderCheckoutClaim.order_id == paid_ids["order"])
                )
                shift = await session.get(Shift, paid_ids["shift"])
                delivery_count = await session.scalar(
                    select(func.count())
                    .select_from(GoogleSheetsDelivery)
                    .where(
                        GoogleSheetsDelivery.company_id == paid_ids["company"],
                        GoogleSheetsDelivery.source_type == "pos_order",
                        GoogleSheetsDelivery.source_id == str(paid_ids["order"]),
                    )
                )
            assert payment_count == 2
            assert claim_count == 0
            assert delivery_count == 1
            assert shift is not None
            assert shift.expected_minor == 1_000

            assert response.invoice_issued_at is not None
            async with sessions() as session:
                receipt = await pos_router.get_receipt_history(
                    paid_ids["order"],
                    session,
                    _tenant(paid_ids),
                )
                shift_reads = await pos_router.list_shifts(
                    session,
                    request=None,
                    tenant=_tenant(paid_ids),
                )
                timezone_name = await company_timezone(session, paid_ids["company"])
                business_date = response.invoice_issued_at.astimezone(
                    ZoneInfo(timezone_name)
                ).date()
                report = await ReportsAggregator(session).aggregate_daily(
                    company_id=paid_ids["company"],
                    branch_id=paid_ids["branch"],
                    d=business_date,
                )
                ledger = await build_operational_ledger(
                    session,
                    company_id=paid_ids["company"],
                    start_at=response.invoice_issued_at - timedelta(seconds=1),
                    end_exclusive=response.invoice_issued_at + timedelta(seconds=1),
                )

            payment_history = {payment.method: payment for payment in receipt.payments}
            assert receipt.paid_minor == 2_500
            assert receipt.refunded_minor == 0
            assert receipt.net_collected_minor == 2_500
            assert set(payment_history) == {"cash", "upi"}
            assert payment_history["cash"].amount_minor == 1_000
            assert payment_history["cash"].tendered_minor == 1_200
            assert payment_history["cash"].change_minor == 200
            assert payment_history["upi"].amount_minor == 1_500
            assert payment_history["upi"].reference is not None
            assert payment_history["upi"].reference.startswith("UPI-SPLIT-")

            shift_read = next(item for item in shift_reads if item.id == paid_ids["shift"])
            assert shift_read.pos_sales_minor == 2_500
            assert shift_read.gross_collections_minor == 2_500
            assert shift_read.cash_collections_minor == 1_000
            assert shift_read.card_collections_minor == 0
            assert shift_read.upi_collections_minor == 1_500
            assert shift_read.other_collections_minor == 0
            assert shift_read.settled_pos_refunds_minor == 0
            assert shift_read.total_refunds_minor == 0
            assert shift_read.net_collections_minor == 2_500
            assert shift_read.expected_minor == 1_000

            assert report.orders_count == 1
            assert report.gross_revenue_minor == 2_500
            assert report.net_revenue_minor == 2_500
            assert report.payments_received.cash_minor == 1_000
            assert report.payments_received.upi_minor == 1_500
            assert report.payments_received.total_minor == 2_500
            assert report.refunds_issued_minor == 0
            assert report.settled_refunds_issued_minor == 0
            assert report.net_payments_received_minor == 2_500

            assert sum(line.debit_minor for line in ledger) == 5_000
            assert sum(line.credit_minor for line in ledger) == 5_000
            expected_payment_entries = {
                response.payments[0].id: {
                    (CASH.code, 1_000, 0),
                    (POS_SETTLEMENT_CLEARING.code, 0, 1_000),
                },
                response.payments[1].id: {
                    (UPI_CLEARING.code, 1_500, 0),
                    (POS_SETTLEMENT_CLEARING.code, 0, 1_500),
                },
            }
            for payment_id, expected_entries in expected_payment_entries.items():
                assert {
                    (line.account_code, line.debit_minor, line.credit_minor)
                    for line in ledger
                    if line.ref_type == "payment" and line.ref_id == payment_id
                } == expected_entries
            assert {
                (line.account_code, line.debit_minor, line.credit_minor)
                for line in ledger
                if line.ref_type == "order" and line.ref_id == paid_ids["order"]
            } == {
                (POS_SETTLEMENT_CLEARING.code, 2_500, 0),
                (SALES_REVENUE.code, 0, 2_500),
            }

            # Complete a real partial cash refund against the mixed-rail bill.
            # The accepted request, physical handoff, and accounting receipt
            # are intentionally separate committed recovery points.
            cash_refund_action_id = f"split-cash-refund:{uuid4()}"
            async with sessions() as session:
                accepted_refund = await pos_router.create_pos_refund_request(
                    pos_router.PosRefundRequestCreate(
                        order_id=paid_ids["order"],
                        shift_id=paid_ids["shift"],
                        reason_code="customer_request",
                        amount_minor=500,
                        expected_paid_minor=2_500,
                        expected_refundable_minor=2_500,
                        mode="cash",
                        client_action_id=cash_refund_action_id,
                        note="Explicit cash return from a mixed payment",
                    ),
                    session,
                    _request(
                        cash_refund_action_id,
                        client_action_id=cash_refund_action_id,
                    ),
                    _tenant(paid_ids),
                )
                await session.commit()
            assert accepted_refund.status == "accepted_cash_due"
            assert accepted_refund.settlement_method == "cash"

            begin_refund_key = f"split-refund-begin:{uuid4()}"
            async with sessions() as session:
                begun_refund = await pos_router.begin_pos_cash_refund_handoff(
                    accepted_refund.id,
                    pos_router.PosRefundHandoffRequest(
                        shift_id=paid_ids["shift"],
                        expected_amount_minor=500,
                        ready_to_handover=True,
                    ),
                    session,
                    _request(begin_refund_key),
                    _tenant(paid_ids),
                )
                await session.commit()
            assert begun_refund.status == "cash_handoff_in_progress"
            assert begun_refund.handoff_started_at is not None

            settle_refund_key = f"split-refund-settle:{uuid4()}"
            async with sessions() as session:
                handed_over_refund = await pos_router.settle_pos_cash_refund(
                    accepted_refund.id,
                    pos_router.PosRefundCashSettlementRequest(
                        shift_id=paid_ids["shift"],
                        expected_amount_minor=500,
                        cash_handed_over=True,
                        settled_at=begun_refund.handoff_started_at,
                    ),
                    session,
                    _request(settle_refund_key),
                    _tenant(paid_ids),
                )
                await session.commit()
            assert handed_over_refund.status == "cash_handed_over_pending_accounting"
            assert handed_over_refund.cash_handed_over_recorded_at is not None

            finalize_refund_key = f"split-refund-final:{uuid4()}"
            async with sessions() as session:
                finalized_refund = await pos_router.finalize_pos_cash_refund(
                    accepted_refund.id,
                    pos_router.PosRefundAccountingFinalizationRequest(
                        shift_id=paid_ids["shift"],
                        expected_amount_minor=500,
                    ),
                    session,
                    _request(finalize_refund_key),
                    _tenant(paid_ids),
                )
                await session.commit()
            assert finalized_refund.status == "settled"
            assert finalized_refund.refund_id is not None
            assert finalized_refund.receipt_no is not None
            assert finalized_refund.settled_at == handed_over_refund.cash_handed_over_recorded_at

            async with sessions() as session:
                refunded_receipt = await pos_router.get_receipt_history(
                    paid_ids["order"],
                    session,
                    _tenant(paid_ids),
                )
                refunded_shift_reads = await pos_router.list_shifts(
                    session,
                    request=None,
                    tenant=_tenant(paid_ids),
                )
                refunded_report = await ReportsAggregator(session).aggregate_daily(
                    company_id=paid_ids["company"],
                    branch_id=paid_ids["branch"],
                    d=business_date,
                )
                refunded_ledger = await build_operational_ledger(
                    session,
                    company_id=paid_ids["company"],
                    start_at=response.invoice_issued_at - timedelta(seconds=1),
                    end_exclusive=finalized_refund.settled_at + timedelta(seconds=1),
                )
                refund_delivery = (
                    await session.execute(
                        select(GoogleSheetsDelivery).where(
                            GoogleSheetsDelivery.company_id == paid_ids["company"],
                            GoogleSheetsDelivery.source_type == "pos_refund",
                            GoogleSheetsDelivery.source_id == str(finalized_refund.refund_id),
                        )
                    )
                ).scalar_one()

            assert refunded_receipt.status == "paid"
            assert refunded_receipt.paid_minor == 2_500
            assert refunded_receipt.refunded_minor == 500
            assert refunded_receipt.net_collected_minor == 2_000
            assert len(refunded_receipt.payments) == 2
            assert len(refunded_receipt.refunds) == 1
            receipt_refund = refunded_receipt.refunds[0]
            assert receipt_refund.id == finalized_refund.refund_id
            assert receipt_refund.request_id == accepted_refund.id
            assert receipt_refund.amount_minor == 500
            assert receipt_refund.mode == "cash"
            assert receipt_refund.settlement_method == "cash"
            assert receipt_refund.settlement_shift_id == paid_ids["shift"]
            assert receipt_refund.receipt_no == finalized_refund.receipt_no

            refunded_shift = next(
                item for item in refunded_shift_reads if item.id == paid_ids["shift"]
            )
            assert refunded_shift.pos_sales_minor == 2_500
            assert refunded_shift.gross_collections_minor == 2_500
            assert refunded_shift.cash_collections_minor == 1_000
            assert refunded_shift.upi_collections_minor == 1_500
            assert refunded_shift.settled_pos_refunds_minor == 500
            assert refunded_shift.total_refunds_minor == 500
            assert refunded_shift.net_collections_minor == 2_000
            assert refunded_shift.expected_minor == 500

            assert refunded_report.orders_count == 1
            assert refunded_report.gross_revenue_minor == 2_500
            assert refunded_report.refunds_issued_minor == 500
            assert refunded_report.settled_refunds_issued_minor == 500
            assert refunded_report.net_revenue_minor == 2_000
            assert refunded_report.payments_received.cash_minor == 1_000
            assert refunded_report.payments_received.upi_minor == 1_500
            assert refunded_report.payments_received.total_minor == 2_500
            assert refunded_report.net_payments_received_minor == 2_000

            assert sum(line.debit_minor for line in refunded_ledger) == 5_500
            assert sum(line.credit_minor for line in refunded_ledger) == 5_500
            assert {
                (line.account_code, line.debit_minor, line.credit_minor)
                for line in refunded_ledger
                if line.ref_type == "refund" and line.ref_id == finalized_refund.refund_id
            } == {
                (SALES_RETURNS.code, 500, 0),
                (CASH.code, 0, 500),
            }
            assert refund_delivery.payload["amount_minor"] == -500
            assert refund_delivery.payload["payment_method"] == "cash"
            assert refund_delivery.payload["order_id"] == str(paid_ids["order"])
            assert refund_delivery.payload["shift_id"] == str(paid_ids["shift"])

            # Any late failure rolls back every leg, drawer movement,
            # finalization, and idempotency receipt together.
            async def reject_mirror(_session, **_kwargs):
                raise RuntimeError("injected mirror failure")

            monkeypatch.setattr(pos_router, "_enqueue_paid_order_mirror", reject_mirror)
            rollback_key = f"split-payment:{uuid4()}"
            async with sessions() as session:
                with pytest.raises(RuntimeError, match="injected mirror failure"):
                    await pos_router.record_payment_bundle(
                        rollback_ids["order"],
                        _payload(),
                        session,
                        _request(rollback_key),
                        _tenant(rollback_ids),
                        None,
                    )
                await session.rollback()
            async with sessions() as session:
                rolled_back_order = await session.get(Order, rollback_ids["order"])
                rolled_back_shift = await session.get(Shift, rollback_ids["shift"])
                rolled_back_payments = await session.scalar(
                    select(func.count())
                    .select_from(Payment)
                    .where(Payment.order_id == rollback_ids["order"])
                )
                saved_key = await session.get(IdempotencyKey, rollback_key)
            assert rolled_back_order is not None
            assert rolled_back_order.status == "open"
            assert rolled_back_shift is not None
            assert rolled_back_shift.expected_minor == 0
            assert rolled_back_payments == 0
            assert saved_key is None

            # A structurally valid but underpaid bundle is rejected before any
            # monetary or finalization row can be written.
            invalid_key = f"split-payment:{uuid4()}"
            async with sessions() as session:
                with pytest.raises(BusinessRuleError, match="must equal the exact amount due"):
                    await pos_router.record_payment_bundle(
                        rollback_ids["order"],
                        _payload(cash_minor=500, upi_minor=1_500),
                        session,
                        _request(invalid_key),
                        _tenant(rollback_ids),
                        None,
                    )
                await session.rollback()
        finally:
            await engine.dispose()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_payment_bundles_cannot_double_settle() -> None:
    with _disposable_database("erp_pos_split_race") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "head")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_open_order(connection)

        engine = create_async_engine(
            database_url.replace("postgresql+psycopg://", "postgresql+asyncpg://", 1),
            pool_pre_ping=True,
        )
        sessions = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        payload = _payload()

        async def settle(key: str):
            async with sessions() as session:
                try:
                    response = await pos_router.record_payment_bundle(
                        ids["order"],
                        payload,
                        session,
                        _request(key),
                        _tenant(ids),
                        None,
                    )
                    await session.commit()
                    return response, None
                except BusinessRuleError as exc:
                    await session.rollback()
                    return None, exc

        try:
            outcomes = await asyncio.gather(
                settle(f"split-race-a:{uuid4()}"),
                settle(f"split-race-b:{uuid4()}"),
            )
            successes = [response for response, error in outcomes if response and not error]
            failures = [error for response, error in outcomes if error and not response]
            assert len(successes) == 1
            assert len(failures) == 1
            assert "status=paid" in str(failures[0])

            async with sessions() as session:
                order = await session.get(Order, ids["order"])
                payment_count = await session.scalar(
                    select(func.count())
                    .select_from(Payment)
                    .where(Payment.order_id == ids["order"])
                )
            assert order is not None
            assert order.status == "paid"
            assert payment_count == 2
        finally:
            await engine.dispose()
