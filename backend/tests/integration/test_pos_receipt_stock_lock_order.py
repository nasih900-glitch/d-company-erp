"""Actual HTTP lock races: payment, attendance, invoice identity and old stock correction."""

# Fixture imports retain their injected names.
# ruff: noqa: F401, F811
from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select, text

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password, issue_access_token
from app.models import (
    Attendance,
    Batch,
    Branch,
    Ingredient,
    Order,
    OrderLine,
    Payment,
    PosReceiptCorrection,
    Role,
    Shift,
    ShiftStartWorkReceipt,
    StockMovement,
    Terminal,
    User,
    UserRole,
)
from app.services.audit.recorder import install_audit_listeners
from tests.integration.test_pos_receipt_correction_preview import paid_retained
from tests.integration.test_pos_receipt_stock_corrections import command, stocked

install_audit_listeners()

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
CORRECT = "/api/v1/pos/receipt-corrections/stock/commit"
START = "/api/v1/pos/shifts/start-work"


async def wait_blocked(waiter, blocker):
    async with AsyncSessionLocal() as observer:
        for _ in range(150):
            await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
            row = (
                (
                    await observer.execute(
                        text(
                            "SELECT pg_blocking_pids(:p) blockers "
                            "FROM pg_stat_activity WHERE pid=:p"
                        ),
                        {"p": waiter},
                    )
                )
                .mappings()
                .one_or_none()
            )
            if row and blocker in row["blockers"]:
                return
            await asyncio.sleep(0.02)
    raise AssertionError("Expected real PostgreSQL lock wait was not observed")


async def backend_pid(session):
    return await session.scalar(text("SELECT pg_backend_pid()"))


async def count(session, table, company):
    return await session.scalar(
        select(text("count(*)")).select_from(table).where(table.company_id == company)
    )


async def prepare_sale(client, session, seed, case, different_branch):
    company, actor, version = seed["company"].id, seed["owner"].id, seed["owner"].auth_version
    branch, terminal = seed["branch"].id, seed["terminal"].id
    menu = await session.scalar(
        select(OrderLine.menu_item_id).where(OrderLine.order_id == UUID(case["order_id"])).limit(1)
    )
    if different_branch:
        branch, terminal = uuid4(), uuid4()
        session.add(
            Branch(
                id=branch,
                company_id=company,
                name="Other QA branch",
                invoice_series_code="OT",
                state_code="32",
            )
        )
        await session.flush()
        session.add(
            Terminal(
                id=terminal,
                branch_id=branch,
                name="Other QA till",
                device_id=uuid4().hex,
                purpose="hybrid",
            )
        )
        ingredient = await session.get(Ingredient, case["ingredient_id"])
        ingredient.current_qty = Decimal(str(ingredient.current_qty)) + 5
        session.add(
            Batch(
                id=uuid4(),
                ingredient_id=ingredient.id,
                branch_id=branch,
                received_at=datetime(2026, 1, 1, tzinfo=UTC),
                qty_initial=5,
                qty_on_hand=5,
                cost_per_unit_minor=150,
            )
        )
        await session.commit()
    headers = {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=actor,
            company_id=company,
            branch_id=branch,
            roles=["super_owner"],
            auth_version=version,
        ),
        "X-Terminal-Id": str(terminal),
    }
    opened = await client.post(
        "/api/v1/pos/shifts/open",
        headers={**headers, "Idempotency-Key": str(uuid4())},
        json={"opening_float_minor": 0},
    )
    assert opened.status_code == 201, opened.text
    created = await client.post(
        "/api/v1/pos/orders",
        headers={**headers, "Idempotency-Key": str(uuid4())},
        json={
            "type": "takeaway",
            "shift_id": opened.json()["id"],
            "lines": [{"client_line_id": str(uuid4()), "menu_item_id": str(menu), "qty": 1}],
        },
    )
    assert created.status_code == 201, created.text
    return created.json(), headers


@pytest.mark.parametrize("different_branch", [False, True])
@pytest.mark.parametrize("leader", ["payment", "correction"])
async def test_full_upi_and_stock_restore_have_one_lock_order(
    client, session, seed_owner, stocked, monkeypatch, different_branch, leader
):
    import app.api.v1.pos.router as pos
    import app.services.pos.receipt_corrections as correction
    import app.services.pos.receipt_stock as stock

    sale, headers = await prepare_sale(client, session, seed_owner, stocked, different_branch)
    body, correction_headers = await command(client, stocked)
    company, ingredient_id = stocked["company_id"], stocked["ingredient_id"]
    await session.rollback()
    held, release, correction_entered = asyncio.Event(), asyncio.Event(), asyncio.Event()
    pids = {}
    original_deduct, original_stock, original_company = (
        pos.deduct_for_order,
        stock.lock_stock_source,
        correction.lock_receipt_company,
    )

    async def deduct(other, **kwargs):
        result = await original_deduct(other, **kwargs)
        if str(kwargs["order_id"]) == sale["id"]:
            pids["payment"] = await backend_pid(other)
            if leader == "payment":
                held.set()
                await asyncio.wait_for(release.wait(), 8)
        return result

    async def stock_lock(other, snapshot):
        await original_stock(other, snapshot)
        if leader == "correction":
            pids["correction"] = await backend_pid(other)
            held.set()
            await asyncio.wait_for(release.wait(), 8)

    async def company_lock(other, company_id):
        pids["correction"] = await backend_pid(other)
        correction_entered.set()
        await original_company(other, company_id)

    monkeypatch.setattr(pos, "deduct_for_order", deduct)
    monkeypatch.setattr(stock, "lock_stock_source", stock_lock)
    monkeypatch.setattr(correction, "lock_receipt_company", company_lock)
    payment_key = str(uuid4())

    async def pay():
        return await client.post(
            f"/api/v1/pos/orders/{sale['id']}/payments",
            headers={**headers, "Idempotency-Key": payment_key},
            json={
                "method": "upi",
                "amount_minor": sale["total_minor"],
                "expected_order_total_minor": sale["total_minor"],
                "expected_due_minor": sale["total_minor"],
            },
        )

    async def correct():
        return await client.post(CORRECT, headers=correction_headers, json=body)

    first = asyncio.create_task(pay() if leader == "payment" else correct())
    second = None
    try:
        await asyncio.wait_for(held.wait(), 8)
        second = asyncio.create_task(correct() if leader == "payment" else pay())
        if leader == "payment":
            await asyncio.wait_for(correction_entered.wait(), 5)
            await wait_blocked(pids["correction"], pids["payment"])
        else:
            # Payment can be waiting on Branch identity or on the collection
            # fence, but it cannot reach inventory while restoration owns it.
            await asyncio.sleep(0.1)
            assert not second.done()
        release.set()
        one, two = await asyncio.wait_for(asyncio.gather(first, second), 12)
    finally:
        release.set()
        await asyncio.gather(first, *([second] if second else []), return_exceptions=True)
    paid, corrected = (one, two) if leader == "payment" else (two, one)
    assert paid.status_code == 201, paid.text
    assert corrected.status_code == (409 if leader == "payment" else 201), corrected.text
    async with AsyncSessionLocal() as check:
        assert (
            await check.scalar(select(Order.status).where(Order.id == UUID(sale["id"]))) == "paid"
        )
        assert (
            await check.scalar(
                select(text("count(*)"))
                .select_from(Payment)
                .where(Payment.order_id == UUID(sale["id"]))
            )
            == 1
        )
        assert (
            await check.scalar(
                select(text("count(*)"))
                .select_from(StockMovement)
                .where(StockMovement.ref_id == UUID(sale["id"]))
            )
            == 1
        )
        assert await count(check, PosReceiptCorrection, company) == (
            0 if leader == "payment" else 1
        )
        assert await count(check, Attendance, company) == 0
        payment_id = await check.scalar(
            select(Payment.id).where(Payment.order_id == UUID(sale["id"]))
        )
        assert (
            await check.scalar(
                text(
                    "SELECT count(*) FROM audit_log WHERE company_id=:c "
                    "AND entity_type='Payment' AND entity_id=:p AND action_code='POS-PAY'"
                ),
                {"c": company, "p": str(payment_id)},
            )
            == 1
        )
        quantity = await check.scalar(
            select(Ingredient.current_qty).where(Ingredient.id == ingredient_id)
        )
        assert quantity == Decimal(
            (7 if different_branch else 2)
            if leader == "payment"
            else (9 if different_branch else 4)
        )
    if corrected.status_code == 201:
        again = await correct()
        assert again.status_code == 201
        assert again.json() == corrected.json()
        async with AsyncSessionLocal() as audit:
            assert (
                await audit.scalar(
                    text(
                        "SELECT count(*) FROM audit_log WHERE company_id=:c "
                        "AND action='receipt_corrected_no_value' AND entity_id=:id"
                    ),
                    {"c": company, "id": corrected.json()["id"]},
                )
                == 1
            )


@pytest.mark.parametrize("same_owner", [True, False])
@pytest.mark.parametrize("leader", ["start", "correction"])
async def test_start_work_and_old_closed_receipt_restore_serialize_identity_before_fence(
    client, session, seed_owner, stocked, monkeypatch, same_owner, leader
):
    import app.api.v1.pos.start_work as start
    import app.services.pos.receipt_corrections as correction
    import app.services.pos.receipt_stock as stock

    body, correction_headers = await command(client, stocked)
    company, branch, terminal = (
        seed_owner["company"].id,
        seed_owner["branch"].id,
        seed_owner["terminal"].id,
    )
    actor = seed_owner["owner"]
    if not same_owner:
        role = await session.scalar(
            select(Role).where(Role.company_id == company, Role.code == "super_owner")
        )
        actor = User(
            id=uuid4(),
            company_id=company,
            email=f"other-{uuid4().hex}@test.local",
            name="Other Owner",
            password_hash=hash_password("password1234"),
            status="active",
        )
        session.add(actor)
        await session.flush()
        session.add(UserRole(id=uuid4(), user_id=actor.id, role_id=role.id))
        await session.commit()
        await session.refresh(actor)
    actor_id = actor.id
    headers = {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=actor.id,
            company_id=company,
            branch_id=branch,
            roles=["super_owner"],
            auth_version=actor.auth_version,
        ),
        "X-Terminal-Id": str(terminal),
        "Idempotency-Key": "shift-open:" + str(uuid4()),
    }
    await session.rollback()
    held, release, entered = asyncio.Event(), asyncio.Event(), asyncio.Event()
    pids, fresh_calls = {}, 0
    original_fresh, original_stock, original_company = (
        start._fresh,
        stock.lock_stock_source,
        correction.lock_receipt_company,
    )

    async def fresh(request, other, tenant):
        nonlocal fresh_calls
        await original_fresh(request, other, tenant)
        fresh_calls += 1
        if leader == "start" and fresh_calls == (1 if same_owner else 2):
            pids["start"] = await backend_pid(other)
            held.set()
            await asyncio.wait_for(release.wait(), 8)

    async def stock_lock(other, snapshot):
        await original_stock(other, snapshot)
        if leader == "correction":
            pids["correction"] = await backend_pid(other)
            held.set()
            await asyncio.wait_for(release.wait(), 8)

    async def company_lock(other, company_id):
        pids["correction"] = await backend_pid(other)
        entered.set()
        await original_company(other, company_id)

    monkeypatch.setattr(start, "_fresh", fresh)
    monkeypatch.setattr(stock, "lock_stock_source", stock_lock)
    monkeypatch.setattr(correction, "lock_receipt_company", company_lock)

    async def begin():
        return await client.post(START, headers=headers, json={"opening_float_minor": 0})

    async def correct():
        return await client.post(CORRECT, headers=correction_headers, json=body)

    first = asyncio.create_task(begin() if leader == "start" else correct())
    second = None
    try:
        await asyncio.wait_for(held.wait(), 8)
        second = asyncio.create_task(correct() if leader == "start" else begin())
        if leader == "start" and not same_owner:
            await asyncio.wait_for(entered.wait(), 5)
            await wait_blocked(pids["correction"], pids["start"])
        else:
            await asyncio.sleep(0.1)
            assert not second.done()
        release.set()
        one, two = await asyncio.wait_for(asyncio.gather(first, second), 12)
    finally:
        release.set()
        await asyncio.gather(first, *([second] if second else []), return_exceptions=True)
    started, corrected = (one, two) if leader == "start" else (two, one)
    assert started.status_code == 201, started.text
    assert corrected.status_code == 201, corrected.text
    assert (await begin()).json() == started.json()
    assert (await correct()).json() == corrected.json()
    async with AsyncSessionLocal() as check:
        assert await count(check, Attendance, company) == 1
        assert await count(check, ShiftStartWorkReceipt, company) == 1
        assert (
            await check.scalar(
                text(
                    "SELECT count(*) FROM audit_log WHERE company_id=:c "
                    "AND action='shift_opened' AND entity_id=:id"
                ),
                {"c": company, "id": started.json()["shift_id"]},
            )
            == 1
        )
        assert await count(check, PosReceiptCorrection, company) == 1
        assert (
            await check.scalar(select(Attendance.user_id).where(Attendance.company_id == company))
            == actor_id
        )
        assert (
            await check.scalar(
                select(text("count(*)"))
                .select_from(Shift)
                .where(Shift.company_id == company, Shift.status == "open")
            )
            == 1
        )


async def test_branch_settings_company_lock_precedes_correction_parent_locks(
    client, session, seed_owner, stocked, monkeypatch
):
    from sqlalchemy.ext.asyncio import AsyncSession

    import app.services.pos.receipt_corrections as correction

    body, headers = await command(client, stocked)
    branch = seed_owner["branch"].id
    await session.rollback()
    settings_held, correction_entered, release = asyncio.Event(), asyncio.Event(), asyncio.Event()
    pids = {}
    original_execute, original_company = AsyncSession.execute, correction.lock_receipt_company

    async def execute(other, statement, *args, **kwargs):
        descriptions = getattr(statement, "column_descriptions", [])
        if (
            descriptions
            and descriptions[0].get("expr") is Branch
            and "FOR UPDATE" in str(statement)
            and not settings_held.is_set()
        ):
            pids["settings"] = await backend_pid(other)
            settings_held.set()
            await asyncio.wait_for(release.wait(), 8)
        return await original_execute(other, statement, *args, **kwargs)

    async def company_lock(other, company_id):
        pids["correction"] = await backend_pid(other)
        correction_entered.set()
        await original_company(other, company_id)

    monkeypatch.setattr(AsyncSession, "execute", execute)
    monkeypatch.setattr(correction, "lock_receipt_company", company_lock)
    settings = asyncio.create_task(
        client.patch(
            f"/api/v1/settings/branches/{branch}",
            headers=stocked["headers"],
            json={"name": "QA renamed shop"},
        )
    )
    task = None
    try:
        await asyncio.wait_for(settings_held.wait(), 8)
        task = asyncio.create_task(client.post(CORRECT, headers=headers, json=body))
        await asyncio.wait_for(correction_entered.wait(), 8)
        await wait_blocked(pids["correction"], pids["settings"])
        release.set()
        changed, corrected = await asyncio.wait_for(asyncio.gather(settings, task), 12)
    finally:
        release.set()
        await asyncio.gather(settings, *([task] if task else []), return_exceptions=True)
    assert changed.status_code == 200, changed.text
    assert corrected.status_code in {201, 409}, corrected.text


async def test_same_owner_start_and_legacy_zero_stock_correction_take_actor_before_fence(
    client, session, seed_owner, paid_retained, monkeypatch
):
    import app.api.v1.pos.start_work as start
    from tests.integration.test_pos_receipt_corrections import body_for

    body = await body_for(client, paid_retained)
    company = seed_owner["company"].id
    start_headers = {**paid_retained["headers"], "Idempotency-Key": "shift-open:" + str(uuid4())}
    correction_headers = {**paid_retained["headers"], "Idempotency-Key": str(uuid4())}
    await session.rollback()
    held, release = asyncio.Event(), asyncio.Event()
    original = start._fresh
    calls = 0

    async def pause(request, other, tenant):
        nonlocal calls
        await original(request, other, tenant)
        calls += 1
        if calls == 1:
            held.set()
            await asyncio.wait_for(release.wait(), 8)

    monkeypatch.setattr(start, "_fresh", pause)
    first = asyncio.create_task(
        client.post(START, headers=start_headers, json={"opening_float_minor": 0})
    )
    second = None
    try:
        await asyncio.wait_for(held.wait(), 8)
        second = asyncio.create_task(
            client.post(
                "/api/v1/pos/receipt-corrections/commit",
                headers=correction_headers,
                json=body.model_dump(mode="json"),
            )
        )
        await asyncio.sleep(0.1)
        assert not second.done()
        release.set()
        started, corrected = await asyncio.wait_for(asyncio.gather(first, second), 12)
    finally:
        release.set()
        await asyncio.gather(first, *([second] if second else []), return_exceptions=True)
    assert started.status_code == 201, started.text
    assert corrected.status_code == 201, corrected.text
    again = await client.post(
        "/api/v1/pos/receipt-corrections/commit",
        headers=correction_headers,
        json=body.model_dump(mode="json"),
    )
    assert again.json() == corrected.json()
    async with AsyncSessionLocal() as check:
        assert await count(check, Attendance, company) == 1
        assert await count(check, ShiftStartWorkReceipt, company) == 1
        assert await count(check, PosReceiptCorrection, company) == 1
