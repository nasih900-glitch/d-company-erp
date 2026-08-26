"""End-to-end database proof for durable Tables -> KDS -> POS handoff."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import uuid4

import psycopg
import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.api.v1.kitchen import router as kitchen_router
from app.api.v1.pos import router as pos_router
from app.core.errors import BusinessRuleError, ConflictError
from app.core.tenant import TenantContext
from app.models import Order, OrderLine
from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


def _request(key: str, body_hash: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key=key,
            idempotency_request_hash=body_hash or f"hash:{key}",
        )
    )


def _tenant(ids: dict[str, object], *, user_key: str = "user") -> TenantContext:
    return TenantContext(
        user_id=ids[user_key],
        company_id=ids["company"],
        branch_id=ids["branch"],
        terminal_id=ids["terminal"],
        roles=("owner",),
        protected_access=True,
    )


class _RollbackProbe(RuntimeError):
    pass


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_line_cancel_and_send_serialize_on_order_snapshot() -> None:
    with _disposable_database("erp_cafe_race_0037") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            connection.execute(
                "DELETE FROM orders WHERE id = %s", (ids["order_two"],)
            )
            connection.commit()
        upgraded = _run_alembic(database_url, "upgrade", "0037")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        engine = create_async_engine(
            database_url.replace(
                "postgresql+psycopg://", "postgresql+asyncpg://", 1
            ),
            pool_pre_ping=True,
        )
        sessions = async_sessionmaker(engine, expire_on_commit=False)
        tenant = _tenant(ids)
        race_line_client_id = uuid4()
        try:
            async with sessions() as session:
                order = await session.get(Order, ids["order_one"])
                assert order is not None
                prepared = await pos_router.add_order_lines(
                    ids["order_one"],
                    pos_router.OrderLinesAppend(
                        expected_checkout_version=order.checkout_version,
                        lines=[
                            pos_router.OrderLineCreate(
                                client_line_id=race_line_client_id,
                                menu_item_id=ids["item"],
                                qty=1,
                            )
                        ],
                    ),
                    session,
                    _request("race-prepare-second-active-line"),
                    tenant,
                )
                await session.commit()
            race_line = next(
                line for line in prepared.lines
                if line.client_line_id == race_line_client_id
            )

            async def _cancel():
                async with sessions() as session:
                    try:
                        result = await pos_router.void_order_line(
                            ids["order_one"],
                            race_line.id,
                            pos_router.VoidOrderLineRequest(
                                expected_checkout_version=prepared.checkout_version,
                                reason="Concurrent cancellation",
                            ),
                            session,
                            _request("race-cancel-line"),
                            tenant,
                        )
                        await session.commit()
                        return ("cancel", result, None)
                    except BusinessRuleError as exc:
                        await session.rollback()
                        return ("cancel", None, exc)

            async def _send():
                async with sessions() as session:
                    try:
                        result = await pos_router.send_order_to_pos(
                            ids["order_one"],
                            pos_router.SendOrderToPosRequest(
                                expected_checkout_version=prepared.checkout_version,
                            ),
                            session,
                            _request("race-send-to-pos"),
                            tenant,
                        )
                        await session.commit()
                        return ("send", result, None)
                    except BusinessRuleError as exc:
                        await session.rollback()
                        return ("send", None, exc)

            outcomes = await asyncio.gather(_cancel(), _send())
            successes = [name for name, result, error in outcomes if result and not error]
            failures = [error for _name, result, error in outcomes if error and not result]
            assert len(successes) == 1
            assert len(failures) == 1

            async with sessions() as session:
                final_order = await session.get(Order, ids["order_one"])
                final_line = await session.get(OrderLine, race_line.id)
            assert final_order is not None
            assert final_line is not None
            if successes == ["send"]:
                assert final_order.status == "held"
                assert final_line.voided_at is None
            else:
                assert successes == ["cancel"]
                assert final_order.status == "open"
                assert final_line.voided_at is not None

            # A response-loss replay with the same whole-order reason is safe,
            # while a rapid second command must not silently rewrite the audit
            # meaning under a different reason.
            void_payload = pos_router.VoidOrderRequest(reason="End of race cleanup")
            async with sessions() as session:
                await pos_router.void_held_order(
                    ids["order_one"], void_payload, session, tenant
                )
                await session.commit()
            async with sessions() as session:
                await pos_router.void_held_order(
                    ids["order_one"], void_payload, session, tenant
                )
                await session.commit()
            async with sessions() as session:
                with pytest.raises(
                    BusinessRuleError,
                    match="already voided with a different reason",
                ):
                    await pos_router.void_held_order(
                        ids["order_one"],
                        pos_router.VoidOrderRequest(reason="Different cleanup reason"),
                        session,
                        tenant,
                    )
                await session.rollback()
        finally:
            await engine.dispose()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_table_round_cancel_ack_handoff_and_direct_payment_release(
    monkeypatch,
) -> None:
    with _disposable_database("erp_cafe_flow_0037") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            connection.execute(
                "DELETE FROM orders WHERE id = %s", (ids["order_two"],)
            )
            connection.commit()
        upgraded = _run_alembic(database_url, "upgrade", "0037")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        async_url = database_url.replace(
            "postgresql+psycopg://", "postgresql+asyncpg://", 1
        )
        engine = create_async_engine(async_url, pool_pre_ping=True)
        sessions = async_sessionmaker(engine, expire_on_commit=False)
        waiter = _tenant(ids)
        kitchen_user = _tenant(ids, user_key="ack_actor")
        appended_client_id = uuid4()

        try:
            # Migration + KDS contract: active table work and paid direct work
            # are visible. The unpaid direct draft is not released or queued.
            async with sessions() as session:
                initial_queue = await kitchen_router.kitchen_queue(session, waiter)
            initial_by_id = {ticket.id: ticket for ticket in initial_queue}
            assert ids["order_one"] in initial_by_id
            assert ids["direct_paid_order"] in initial_by_id
            assert ids["direct_open_order"] not in initial_by_id
            table_ticket = initial_by_id[ids["order_one"]]
            assert {line.round_no for line in table_ticket.lines} == {1}
            pending_by_id = {
                cancellation.line_id: cancellation
                for cancellation in table_ticket.pending_cancellations
            }
            assert ids["legacy_void_line"] in pending_by_id
            assert ids["missing_actor_void_line"] in pending_by_id
            assert pending_by_id[ids["missing_actor_void_line"]].voided_by is None

            # Acknowledgement is durable and response-loss replay safe.
            legacy_ack_request = _request("flow-legacy-kds-ack")
            async with sessions() as session:
                first_ack = await kitchen_router.acknowledge_kitchen_cancellation(
                    ids["order_one"],
                    ids["missing_actor_void_line"],
                    session,
                    legacy_ack_request,
                    kitchen_user,
                )
                await session.commit()
            async with sessions() as session:
                replay_ack = await kitchen_router.acknowledge_kitchen_cancellation(
                    ids["order_one"],
                    ids["missing_actor_void_line"],
                    session,
                    legacy_ack_request,
                    kitchen_user,
                )
                await session.commit()
            assert replay_ack == first_ack

            # Add a second persistent service round. The trigger produces the
            # next authoritative checkout version and the same request replays
            # without adding a second row.
            async with sessions() as session:
                order = await session.get(Order, ids["order_one"])
                assert order is not None
                append_payload = pos_router.OrderLinesAppend(
                    expected_checkout_version=order.checkout_version,
                    lines=[
                        pos_router.OrderLineCreate(
                            client_line_id=appended_client_id,
                            menu_item_id=ids["item"],
                            qty=1,
                            note="Second round",
                        )
                    ],
                )
                append_request = _request("flow-table-round-2")
                appended = await pos_router.add_order_lines(
                    ids["order_one"],
                    append_payload,
                    session,
                    append_request,
                    waiter,
                )
                await session.commit()
            appended_line = next(
                line for line in appended.lines
                if line.client_line_id == appended_client_id
            )
            assert appended_line.kitchen_round_no == 2
            assert appended_line.kitchen_released_at is not None

            async with sessions() as session:
                replay_append = await pos_router.add_order_lines(
                    ids["order_one"],
                    append_payload,
                    session,
                    append_request,
                    waiter,
                )
                await session.commit()
            assert replay_append.model_dump() == appended.model_dump()

            async with sessions() as session:
                line_count_before = int(
                    (
                        await session.execute(
                            select(func.count(OrderLine.id)).where(
                                OrderLine.order_id == ids["order_one"]
                            )
                        )
                    ).scalar_one()
                )
            async with sessions() as session:
                with pytest.raises(ConflictError, match="already saved"):
                    await pos_router.add_order_lines(
                        ids["order_one"],
                        pos_router.OrderLinesAppend(
                            expected_checkout_version=appended.checkout_version,
                            lines=[
                                pos_router.OrderLineCreate(
                                    client_line_id=appended_client_id,
                                    menu_item_id=ids["item"],
                                    qty=1,
                                )
                            ],
                        ),
                        session,
                        _request("flow-table-round-2-wrong-replay"),
                        waiter,
                    )
                await session.rollback()
            async with sessions() as session:
                line_count_after = int(
                    (
                        await session.execute(
                            select(func.count(OrderLine.id)).where(
                                OrderLine.order_id == ids["order_one"]
                            )
                        )
                    ).scalar_one()
                )
            assert line_count_after == line_count_before

            # A released whole-line cancellation leaves the active bill math,
            # but stays on KDS until a durable acknowledgement is recorded.
            async with sessions() as session:
                cancelled = await pos_router.void_order_line(
                    ids["order_one"],
                    appended_line.id,
                    pos_router.VoidOrderLineRequest(
                        expected_checkout_version=appended.checkout_version,
                        reason="Guest changed their mind",
                    ),
                    session,
                    _request("flow-cancel-round-2"),
                    waiter,
                )
                await session.commit()
            assert all(
                line.client_line_id != appended_client_id for line in cancelled.lines
            )
            cancelled_evidence = next(
                line for line in cancelled.voided_lines
                if line.client_line_id == appended_client_id
            )
            assert cancelled_evidence.void_reason == "Guest changed their mind"
            assert cancelled_evidence.kitchen_void_acknowledged_at is None

            async with sessions() as session:
                cancellation_queue = await kitchen_router.kitchen_queue(session, waiter)
            cancellation_ticket = next(
                ticket for ticket in cancellation_queue
                if ticket.id == ids["order_one"]
            )
            assert appended_line.id in {
                row.line_id for row in cancellation_ticket.pending_cancellations
            }

            async with sessions() as session:
                await kitchen_router.acknowledge_kitchen_cancellation(
                    ids["order_one"],
                    appended_line.id,
                    session,
                    _request("flow-cancel-round-2-ack"),
                    kitchen_user,
                )
                await session.commit()
            async with sessions() as session:
                post_ack_queue = await kitchen_router.kitchen_queue(session, waiter)
            post_ack_ticket = next(
                ticket for ticket in post_ack_queue if ticket.id == ids["order_one"]
            )
            assert appended_line.id not in {
                row.line_id for row in post_ack_ticket.pending_cancellations
            }

            # Send to POS freezes the same bill snapshot. Replay is stable and
            # Tables still sees the held bill as read-only until settlement.
            send_request = _request("flow-send-table-to-pos")
            async with sessions() as session:
                held = await pos_router.send_order_to_pos(
                    ids["order_one"],
                    pos_router.SendOrderToPosRequest(
                        expected_checkout_version=cancelled.checkout_version,
                    ),
                    session,
                    send_request,
                    waiter,
                )
                await session.commit()
            assert held.status == "held"
            async with sessions() as session:
                held_replay = await pos_router.send_order_to_pos(
                    ids["order_one"],
                    pos_router.SendOrderToPosRequest(
                        expected_checkout_version=cancelled.checkout_version,
                    ),
                    session,
                    send_request,
                    waiter,
                )
                active_table_bills = await pos_router.list_active_table_orders(
                    session,
                    waiter,
                )
                await session.commit()
            assert held_replay.model_dump() == held.model_dump()
            assert any(
                bill.id == ids["order_one"] and bill.status == "held"
                for bill in active_table_bills
            )

            async with sessions() as session:
                with pytest.raises(BusinessRuleError, match="items are locked"):
                    await pos_router.add_order_lines(
                        ids["order_one"],
                        pos_router.OrderLinesAppend(
                            expected_checkout_version=held.checkout_version,
                            lines=[
                                pos_router.OrderLineCreate(
                                    client_line_id=uuid4(),
                                    menu_item_id=ids["item"],
                                    qty=1,
                                )
                            ],
                        ),
                        session,
                        _request("flow-held-append-rejected"),
                        waiter,
                    )
                await session.rollback()

            # Existing successful finalization is the only release boundary
            # for a direct POS food bill. A rolled-back finalization cannot leak
            # to KDS; the later committed one releases exactly one round.
            async def _noop(*_args, **_kwargs):
                return None

            monkeypatch.setattr(pos_router, "consume_membership_benefits", _noop)
            monkeypatch.setattr(pos_router, "consume_points_redemption", _noop)
            monkeypatch.setattr(pos_router, "deduct_for_order", _noop)

            with pytest.raises(_RollbackProbe):
                async with sessions() as session:
                    async with session.begin():
                        direct = (
                            await session.execute(
                                select(Order)
                                .where(Order.id == ids["direct_open_order"])
                                .with_for_update()
                            )
                        ).scalar_one()
                        direct.invoice_no = "ROLLBACK-PROBE"
                        direct.fiscal_year = "2026-27"
                        await pos_router._finalize_order(
                            session,
                            order=direct,
                            company_id=ids["company"],
                            actor_user_id=ids["user"],
                            at=datetime.now(UTC),
                        )
                        await session.flush()
                        assert direct.status == "paid"
                        raise _RollbackProbe

            async with sessions() as session:
                rolled_back_order = await session.get(
                    Order, ids["direct_open_order"]
                )
                rolled_back_line = await session.get(
                    OrderLine, ids["direct_open_line"]
                )
                assert rolled_back_order is not None
                assert rolled_back_line is not None
                assert rolled_back_order.status == "open"
                assert rolled_back_line.kitchen_released_at is None
                assert rolled_back_line.kitchen_round_no is None

            async with sessions() as session:
                async with session.begin():
                    direct = (
                        await session.execute(
                            select(Order)
                            .where(Order.id == ids["direct_open_order"])
                            .with_for_update()
                        )
                    ).scalar_one()
                    direct.invoice_no = "DIRECT-PAID-1"
                    direct.fiscal_year = "2026-27"
                    await pos_router._finalize_order(
                        session,
                        order=direct,
                        company_id=ids["company"],
                        actor_user_id=ids["user"],
                        at=datetime.now(UTC),
                    )
            async with sessions() as session:
                paid_line = await session.get(OrderLine, ids["direct_open_line"])
                final_queue = await kitchen_router.kitchen_queue(session, waiter)
            assert paid_line is not None
            assert paid_line.kitchen_released_at is not None
            assert paid_line.kitchen_round_no == 1
            assert ids["direct_open_order"] in {ticket.id for ticket in final_queue}
        finally:
            await engine.dispose()
