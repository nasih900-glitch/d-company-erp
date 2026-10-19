"""Real PostgreSQL proof of effective rails without rewriting paid history."""

from datetime import date, timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import select, text

from app.core.security import issue_access_token
from app.models import (
    AuditLog,
    Payment,
    PaymentTenderCorrection,
    Role,
    UserRole,
)
from app.services.integrations.google_sheets_collections import calculate_daily_collections
from tests.integration.test_google_sheets_collections import (
    _close_shift,
    _event_time,
    _paid_order,
    _shift,
)


async def case(session, seed, *, opened_at=None):
    from app.core.roles import PROTECTED_OWNER_ROLE

    role = Role(
        id=uuid4(),
        company_id=seed["company"].id,
        code=PROTECTED_OWNER_ROLE,
        name="Protected",
        permissions=[],
    )
    session.add(role)
    await session.flush()
    assignment = await session.scalar(select(UserRole).where(UserRole.user_id == seed["owner"].id))
    assignment.role_id = role.id
    day = (
        opened_at.astimezone(ZoneInfo("Asia/Kolkata")).date()
        if opened_at
        else date.today() - timedelta(days=2)
    )
    terminal = seed["terminal"]
    shift = await _shift(
        session, seed, terminal, opened_at=opened_at or _event_time(day), expected_minor=263000
    )
    order = await _paid_order(session, seed, shift, (("cash", 27000),))
    await _close_shift(session, shift, seed["owner"].id)
    await session.commit()
    await session.refresh(seed["owner"])
    payment = await session.scalar(select(Payment).where(Payment.order_id == order.id))
    headers = {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=seed["owner"].id,
            company_id=seed["company"].id,
            branch_id=seed["branch"].id,
            roles=[PROTECTED_OWNER_ROLE],
            auth_version=seed["owner"].auth_version,
        ),
        "X-Terminal-Id": str(terminal.id),
    }
    return headers, day, order, payment, shift


async def unlock(client, seed, headers, scope):
    result = await client.post(
        "/api/v1/finance/tender-corrections/unlock",
        headers=headers,
        json={"branch_id": str(seed["branch"].id), "scope": scope, "password": seed["password"]},
    )
    assert result.status_code == 200, result.text
    return {**headers, "X-Tender-Token": result.json()["token"]}


@pytest.mark.asyncio
async def test_complete_payment_correction_replay_receipts_and_sheet(client, session, seed_owner):
    headers, day, order, payment, shift = await case(session, seed_owner)
    original = (payment.method, payment.amount_minor, payment.tendered_minor, payment.change_minor)
    h = await unlock(client, seed_owner, headers, "payment")
    preview = await client.post(
        "/api/v1/finance/tender-corrections/payment/prepare",
        headers=h,
        json={"payment_id": str(payment.id)},
    )
    assert preview.status_code == 200, preview.text
    body = {
        "payment_id": str(payment.id),
        "source_fingerprint": preview.json()["source_fingerprint"],
        "reason": "Synthetic owner confirmed UPI instead of Cash",
    }
    action = {**h, "Idempotency-Key": "tender:" + str(uuid4())}
    result = await client.post(
        "/api/v1/finance/tender-corrections/payment/commit", headers=action, json=body
    )
    assert result.status_code == 201, result.text
    assert result.json()["financial_effect"] is True
    again = await client.post(
        "/api/v1/finance/tender-corrections/payment/commit", headers=action, json=body
    )
    assert again.status_code == 201, again.text
    assert again.json() == result.json()
    changed = await client.post(
        "/api/v1/finance/tender-corrections/payment/commit",
        headers=action,
        json={**body, "reason": "Different source attestation"},
    )
    assert changed.status_code == 409, changed.text
    await session.refresh(payment)
    await session.refresh(shift)
    assert (
        payment.method,
        payment.amount_minor,
        payment.tendered_minor,
        payment.change_minor,
    ) == original
    assert shift.expected_minor == 263000
    assert shift.counted_minor == 263000
    assert (
        len(
            (
                await session.scalars(
                    select(PaymentTenderCorrection).where(
                        PaymentTenderCorrection.company_id == order.company_id
                    )
                )
            ).all()
        )
        == 1
    )
    snapshot = (
        await calculate_daily_collections(
            session,
            company_id=order.company_id,
            branch_id=order.branch_id,
            timezone_name="Asia/Kolkata",
            start_date=day,
            end_date=day,
        )
    )[0]
    assert (snapshot.cash_minor, snapshot.upi_minor, snapshot.total_minor) == (0, 27000, 27000)
    receipt = await client.get("/api/v1/pos/receipts/" + str(order.id), headers=headers)
    assert receipt.status_code == 200, receipt.text
    leg = receipt.json()["payments"][0]
    assert leg["method"] == "upi"
    assert leg["original_method"] == "cash"
    assert leg["tendered_minor"] is None
    assert leg["original_tendered_minor"] == 27000
    assert leg["tender_correction_id"] == result.json()["id"]
    orders = await client.get("/api/v1/pos/orders", headers=headers, params={"status": "paid"})
    assert orders.status_code == 200, orders.text
    assert next(row for row in orders.json() if row["id"] == str(order.id))["payment_methods"] == [
        "upi"
    ]
    assert (
        await session.scalar(
            select(AuditLog.id).where(
                AuditLog.company_id == order.company_id,
                AuditLog.action == "payment_method_corrected",
            )
        )
        is not None
    )


@pytest.mark.asyncio
async def test_daily_residual_and_nonposting_actuals(client, session, seed_owner):
    headers, day, order, payment, shift = await case(session, seed_owner)
    h = await unlock(client, seed_owner, headers, "daily")
    payload = {"branch_id": str(order.branch_id), "business_date": str(day)}
    preview = await client.post(
        "/api/v1/finance/tender-corrections/daily/prepare", headers=h, json=payload
    )
    assert preview.status_code == 200, preview.text
    action = {**h, "Idempotency-Key": "daily:" + str(uuid4())}
    result = await client.post(
        "/api/v1/finance/tender-corrections/daily/commit",
        headers=action,
        json={
            **payload,
            "source_fingerprint": preview.json()["source_fingerprint"],
            "actual_cash_minor": 21000,
            "actual_upi_minor": 6000,
            "reason": "Unallocated owner actuals; source bill unknown",
        },
    )
    assert result.status_code == 201, result.text
    assert (result.json()["cash_delta_minor"], result.json()["upi_delta_minor"]) == (-6000, 6000)
    preview = await client.post(
        "/api/v1/finance/tender-corrections/daily/prepare", headers=h, json=payload
    )
    result = await client.post(
        "/api/v1/finance/tender-corrections/daily/commit",
        headers={**h, "Idempotency-Key": "actual:" + str(uuid4())},
        json={
            **payload,
            "source_fingerprint": preview.json()["source_fingerprint"],
            "actual_cash_minor": 21000,
            "actual_upi_minor": 8000,
            "reason": "Missing source revenue remains unresolved",
        },
    )
    assert result.status_code == 201, result.text
    assert result.json()["kind"] == "unresolved"
    assert result.json()["financial_effect"] is False
    assert result.json()["cash_delta_minor"] == 0
    assert result.json()["upi_delta_minor"] == 0
    snapshot = (
        await calculate_daily_collections(
            session,
            company_id=order.company_id,
            branch_id=order.branch_id,
            timezone_name="Asia/Kolkata",
            start_date=day,
            end_date=day,
        )
    )[0]
    assert (snapshot.cash_minor, snapshot.upi_minor, snapshot.total_minor) == (21000, 6000, 27000)
    payment_h = await unlock(client, seed_owner, headers, "payment")
    refused = await client.post(
        "/api/v1/finance/tender-corrections/payment/prepare",
        headers=payment_h,
        json={"payment_id": str(payment.id)},
    )
    assert refused.status_code == 422, refused.text


@pytest.mark.asyncio
async def test_tender_authority_and_stale_fence(client, session, seed_owner):
    headers, day, order, payment, shift = await case(session, seed_owner)
    ordinary = {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=seed_owner["owner"].id,
            company_id=order.company_id,
            branch_id=order.branch_id,
            roles=["co_owner"],
            auth_version=seed_owner["owner"].auth_version,
        )
    }
    denied = await client.post(
        "/api/v1/finance/tender-corrections/unlock",
        headers=ordinary,
        json={
            "branch_id": str(order.branch_id),
            "scope": "payment",
            "password": seed_owner["password"],
        },
    )
    assert denied.status_code == 403, denied.text
    h = await unlock(client, seed_owner, headers, "payment")
    preview = await client.post(
        "/api/v1/finance/tender-corrections/payment/prepare",
        headers=h,
        json={"payment_id": str(payment.id)},
    )
    # A distinct relevant source write changes the guard even if day totals do not.
    await session.execute(
        text("UPDATE shifts SET expected_minor=expected_minor WHERE id=:id"), {"id": shift.id}
    )
    await session.commit()
    result = await client.post(
        "/api/v1/finance/tender-corrections/payment/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            "payment_id": str(payment.id),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "reason": "Stale owner source",
        },
    )
    assert result.status_code == 409, result.text
    assert (
        await session.scalar(
            select(PaymentTenderCorrection.id).where(
                PaymentTenderCorrection.payment_id == payment.id
            )
        )
        is None
    )


@pytest.mark.asyncio
async def test_complete_transaction_retry_rolls_back_all_failed_attempts(
    client, session, seed_owner, monkeypatch
):
    import app.api.v1.finance.tender_corrections as api

    headers, day, order, payment, shift = await case(session, seed_owner)
    h = await unlock(client, seed_owner, headers, "payment")
    preview = await client.post(
        "/api/v1/finance/tender-corrections/payment/prepare",
        headers=h,
        json={"payment_id": str(payment.id)},
    )
    original = api.lock_sources
    attempts = []

    async def transient(current, company):
        attempts.append(current)
        await original(current, company)
        if len(attempts) < 3:
            await current.execute(
                text("UPDATE shifts SET expected_minor=expected_minor+1 WHERE id=:id"),
                {"id": shift.id},
            )
            await current.execute(
                text(
                    "DO $$ BEGIN RAISE EXCEPTION 'synthetic transaction abort' USING "
                    "ERRCODE='40P01'; END $$"
                )
            )

    monkeypatch.setattr(api, "lock_sources", transient)
    response = await client.post(
        "/api/v1/finance/tender-corrections/payment/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            "payment_id": str(payment.id),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "reason": "Retried original complete owner command",
        },
    )
    assert response.status_code == 201, response.text
    assert len(attempts) == 3
    assert len({id(x) for x in attempts}) == 3
    await session.refresh(shift)
    assert shift.expected_minor == 263000
    assert (
        len(
            (
                await session.scalars(
                    select(PaymentTenderCorrection).where(
                        PaymentTenderCorrection.payment_id == payment.id
                    )
                )
            ).all()
        )
        == 1
    )


@pytest.mark.asyncio
async def test_backdated_source_phantom_invalidates_waiting_commit(client, session, seed_owner):
    import asyncio
    import os

    import psycopg

    headers, day, order, payment, shift = await case(session, seed_owner)
    h = await unlock(client, seed_owner, headers, "payment")
    preview = await client.post(
        "/api/v1/finance/tender-corrections/payment/prepare",
        headers=h,
        json={"payment_id": str(payment.id)},
    )
    dsn = os.environ["DATABASE_URL"].replace("postgresql+psycopg://", "postgresql://", 1)
    with psycopg.connect(dsn) as writer, psycopg.connect(dsn, autocommit=True) as observer:
        writer.execute(
            "INSERT INTO "
            "manual_collections(id,company_id,branch_id,business_date,method,"
            "amount_minor,source_kind,source_ref,idempotency_key,created_by,request_hash) "
            "VALUES(%s,%s,%s,%s,'upi',2000,'manual_daily','Synthetic missing "
            "entry',%s,%s,repeat('a',64))",
            (
                uuid4(),
                order.company_id,
                order.branch_id,
                day,
                "manual-collection:" + str(uuid4()),
                seed_owner["owner"].id,
            ),
        )
        task = asyncio.create_task(
            client.post(
                "/api/v1/finance/tender-corrections/payment/commit",
                headers={**h, "Idempotency-Key": str(uuid4())},
                json={
                    "payment_id": str(payment.id),
                    "source_fingerprint": preview.json()["source_fingerprint"],
                    "reason": "Preview before late manual collection",
                },
            )
        )
        waiting = False
        for _ in range(250):
            await asyncio.sleep(0.02)
            waiting = observer.execute(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE "
                "datname=current_database() AND wait_event_type='Lock' AND query "
                "LIKE 'SELECT dcompany_collection_lock%%')"
            ).fetchone()[0]
            if waiting:
                break
        try:
            assert waiting, "commit must wait for the uncommitted backdated source fence"
            assert not task.done(), "commit must wait for the uncommitted backdated source fence"
        finally:
            writer.commit()
        response = await asyncio.wait_for(task, 10)
        assert response.status_code == 409, response.text
    assert (
        await session.scalar(
            select(PaymentTenderCorrection.id).where(
                PaymentTenderCorrection.payment_id == payment.id
            )
        )
        is None
    )


@pytest.mark.asyncio
async def test_confirmed_pattern_keeps_gross_revenue_and_original_shift_evidence(
    client, session, seed_owner
):
    from app.core.timezone import local_date_bounds_utc
    from app.models import ManualCollection
    from app.services.accounting.ledger import build_operational_ledger
    from app.services.reports.aggregator import ReportsAggregator

    headers, day, order, payment, original_shift = await case(session, seed_owner)
    terminal = seed_owner["terminal"]
    extra_shift = await _shift(session, seed_owner, terminal, opened_at=_event_time(day, hour=15))
    await _paid_order(session, seed_owner, extra_shift, (("cash", 236000), ("upi", 368800)))
    await _close_shift(session, extra_shift, seed_owner["owner"].id)
    older = day - timedelta(days=1)
    older_shift = await _shift(session, seed_owner, terminal, opened_at=_event_time(older))
    await _paid_order(session, seed_owner, older_shift, (("cash", 95000), ("upi", 302000)))
    await _close_shift(session, older_shift, seed_owner["owner"].id)
    for at in (older, day):
        session.add(
            ManualCollection(
                id=uuid4(),
                company_id=order.company_id,
                branch_id=order.branch_id,
                business_date=at,
                method="upi",
                amount_minor=2000,
                source_kind="manual_daily",
                source_ref="Synthetic previously recorded omitted receipt " + str(at),
                idempotency_key="manual-collection:" + str(uuid4()),
                request_hash="a" * 64,
                created_by=seed_owner["owner"].id,
            )
        )
    await session.commit()
    before = await ReportsAggregator(session).aggregate(
        company_id=order.company_id, branch_id=order.branch_id, period_start=day, period_end=day
    )
    start, end = local_date_bounds_utc(day, day, "Asia/Kolkata")
    before_ledger = await build_operational_ledger(
        session, company_id=order.company_id, start_at=start, end_exclusive=end
    )
    h = await unlock(client, seed_owner, headers, "payment")
    preview = await client.post(
        "/api/v1/finance/tender-corrections/payment/prepare",
        headers=h,
        json={"payment_id": str(payment.id)},
    )
    assert preview.status_code == 200, preview.text
    result = await client.post(
        "/api/v1/finance/tender-corrections/payment/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            "payment_id": str(payment.id),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "reason": "Known complete payment classification",
        },
    )
    assert result.status_code == 201, result.text
    h = await unlock(client, seed_owner, headers, "daily")
    payload = {"branch_id": str(order.branch_id), "business_date": str(day)}
    preview = await client.post(
        "/api/v1/finance/tender-corrections/daily/prepare", headers=h, json=payload
    )
    assert (preview.json()["recorded_cash_minor"], preview.json()["recorded_upi_minor"]) == (
        236000,
        397800,
    )
    result = await client.post(
        "/api/v1/finance/tender-corrections/daily/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            **payload,
            "source_fingerprint": preview.json()["source_fingerprint"],
            "actual_cash_minor": 230000,
            "actual_upi_minor": 403800,
            "reason": "Unallocated remainder confirmed; bill unidentified",
        },
    )
    assert result.status_code == 201, result.text
    assert (result.json()["cash_delta_minor"], result.json()["upi_delta_minor"]) == (-6000, 6000)
    snapshots = await calculate_daily_collections(
        session,
        company_id=order.company_id,
        branch_id=order.branch_id,
        timezone_name="Asia/Kolkata",
        start_date=older,
        end_date=day + timedelta(days=1),
    )
    assert [(x.cash_minor, x.upi_minor, x.total_minor) for x in snapshots] == [
        (95000, 304000, 399000),
        (230000, 403800, 633800),
        (0, 0, 0),
    ]
    assert (
        len(
            (
                await session.scalars(
                    select(ManualCollection).where(ManualCollection.company_id == order.company_id)
                )
            ).all()
        )
        == 2
    )
    after = await ReportsAggregator(session).aggregate(
        company_id=order.company_id, branch_id=order.branch_id, period_start=day, period_end=day
    )
    assert after.gross_revenue_minor == before.gross_revenue_minor
    assert after.net_profit_minor == before.net_profit_minor
    assert (after.payments_received.cash_minor, after.payments_received.upi_minor) == (
        236000,
        397800,
    )
    assert len(after.tender_reclassifications) == 1
    assert after.tender_reclassifications[0]["cash_delta_minor"] == -6000
    after_ledger = await build_operational_ledger(
        session, company_id=order.company_id, start_at=start, end_exclusive=end
    )
    assert sum(x.debit_minor for x in after_ledger) == sum(x.credit_minor for x in after_ledger)
    original = [x for x in before_ledger if x.ref_type == "payment"]
    assert [x for x in after_ledger if x.ref_type == "payment"] == original
    assert (
        sum(x.debit_minor for x in after_ledger) - sum(x.debit_minor for x in before_ledger)
        == 33000
    )
    shifts = await client.get("/api/v1/pos/shifts", headers=headers)
    assert shifts.status_code == 200, shifts.text
    original_read = next(x for x in shifts.json() if x["id"] == str(original_shift.id))
    assert original_read["expected_minor"] == 263000
    assert original_read["counted_minor"] == 263000
    assert original_read["tender_correction_minor"] == 27000
    assert original_read["reconstructed_expected_minor"] == 236000


@pytest.mark.asyncio
async def test_receipt_filters_bind_cursor_and_preserve_unknown_cashier(
    client, session, seed_owner
):
    headers, day, order, payment, shift = await case(session, seed_owner)
    other_shift = await _shift(
        session, seed_owner, seed_owner["terminal"], opened_at=_event_time(day, hour=16)
    )
    await _paid_order(session, seed_owner, other_shift, (("cash", 27000),))
    await _close_shift(session, other_shift, seed_owner["owner"].id)
    await session.commit()
    query = {
        "date_from": str(day),
        "date_to": str(day),
        "amount_minor": 27000,
        "method": "cash",
        "limit": 1,
    }
    response = await client.get("/api/v1/pos/receipts", headers=headers, params=query)
    assert response.status_code == 200, response.text
    assert len(response.json()["items"]) == 1
    assert response.json()["has_more"] is True
    cursor = response.json()["next_cursor"]
    changed = await client.get(
        "/api/v1/pos/receipts", headers=headers, params={**query, "method": "upi", "cursor": cursor}
    )
    assert changed.status_code == 422, changed.text
    next_page = await client.get(
        "/api/v1/pos/receipts", headers=headers, params={**query, "cursor": cursor}
    )
    assert next_page.status_code == 200, next_page.text
    assert next_page.json()["items"][0]["order_id"] != response.json()["items"][0]["order_id"]
    unknown = await client.get(
        "/api/v1/pos/receipts",
        headers=headers,
        params={**query, "staff_user_id": str(seed_owner["owner"].id)},
    )
    assert unknown.status_code == 200
    assert unknown.json()["items"] == []
    unsupported = await client.get(
        "/api/v1/pos/receipts", headers=headers, params={**query, "made_up_filter": "x"}
    )
    assert unsupported.status_code == 422, unsupported.text
    investigation = await client.get(
        "/api/v1/pos/receipts/" + str(order.id) + "/investigation", headers=headers
    )
    assert investigation.status_code == 200, investigation.text
    assert investigation.json()["receipt"]["order_id"] == str(order.id)
    assert investigation.json()["participant_intervals"] == []
    assert investigation.json()["shop_timezone"] == "Asia/Kolkata"


@pytest.mark.asyncio
async def test_one_confirmation_bundle_keeps_read_write_scope_and_revocation(
    client, session, seed_owner, monkeypatch
):
    from app.api.v1.finance import tender_corrections as route

    headers, _, order, payment, _ = await case(session, seed_owner)
    calls = []
    original_verify = route.verify_password

    def verify_once(secret, hashed):
        calls.append(True)
        return original_verify(secret, hashed)

    monkeypatch.setattr(route, "verify_password", verify_once)
    result = await client.post(
        "/api/v1/finance/tender-corrections/unlock",
        headers=headers,
        json={
            "branch_id": str(order.branch_id),
            "scope": "payment",
            "password": seed_owner["password"],
        },
    )
    assert result.status_code == 200, result.text
    assert len(calls) == 1
    bundle = result.json()
    assert bundle["expires_in"] == 300
    assert bundle["token"] != bundle["history_token"]
    action = {**headers, "X-Tender-Token": bundle["token"]}
    history = {**headers, "X-Tender-Token": bundle["history_token"]}
    prepare = "/api/v1/finance/tender-corrections/payment/prepare"
    history_path = "/api/v1/finance/tender-corrections/history"
    assert (
        await client.post(prepare, headers=action, json={"payment_id": str(payment.id)})
    ).status_code == 200
    assert (
        await client.post(prepare, headers=history, json={"payment_id": str(payment.id)})
    ).status_code == 401
    assert (
        await client.get(history_path, headers=action, params={"branch_id": str(order.branch_id)})
    ).status_code == 401
    assert (
        await client.get(history_path, headers=history, params={"branch_id": str(order.branch_id)})
    ).status_code == 200
    await session.execute(
        text("UPDATE users SET auth_version=auth_version+1 WHERE id=:id"),
        {"id": seed_owner["owner"].id},
    )
    await session.commit()
    await session.refresh(seed_owner["owner"])
    refreshed_access = "Bearer " + issue_access_token(
        user_id=seed_owner["owner"].id,
        company_id=order.company_id,
        branch_id=order.branch_id,
        roles=["super_owner"],
        auth_version=seed_owner["owner"].auth_version,
    )
    assert (
        await client.post(
            prepare,
            headers={**action, "Authorization": refreshed_access},
            json={"payment_id": str(payment.id)},
        )
    ).status_code == 401
    assert (
        await client.get(
            history_path,
            headers={**history, "Authorization": refreshed_access},
            params={"branch_id": str(order.branch_id)},
        )
    ).status_code == 401
