"""Security, refund and concurrency proofs using retained PostgreSQL history."""

import asyncio
import os
from datetime import UTC, date, datetime, timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

import jwt
import psycopg
import pytest
from sqlalchemy import select, text

from app.core.config import get_settings
from app.core.security import hash_password, issue_access_token, issue_tender_token
from app.models import (
    Branch,
    Company,
    IdempotencyKey,
    PaymentTenderCorrection,
    Role,
    User,
    UserRole,
)
from app.services.integrations.google_sheets_collections import calculate_daily_collections
from tests.integration.test_google_sheets_collections import _shift
from tests.integration.test_tender_corrections import case, unlock

BASE = "/api/v1/finance/tender-corrections"


async def prepared(client, session, seed):
    headers, day, order, payment, shift = await case(session, seed)
    h = await unlock(client, seed, headers, "payment")
    preview = await client.post(
        BASE + "/payment/prepare", headers=h, json={"payment_id": str(payment.id)}
    )
    assert preview.status_code == 200, preview.text
    body = {
        "payment_id": str(payment.id),
        "source_fingerprint": preview.json()["source_fingerprint"],
        "reason": "Synthetic confirmed original tender error",
    }
    return headers, h, day, order, payment, shift, body


@pytest.mark.asyncio
async def test_future_actuals_are_rejected_by_prepare_and_commit(client, session, seed_owner):
    headers, _, _, order, _, _, _ = await prepared(client, session, seed_owner)
    h = await unlock(client, seed_owner, headers, "daily")
    future = datetime.now(UTC).astimezone(ZoneInfo("Asia/Kolkata")).date() + timedelta(days=3)
    base = {"branch_id": str(order.branch_id), "business_date": str(future)}
    preview = await client.post(BASE + "/daily/prepare", headers=h, json=base)
    assert preview.status_code == 422, preview.text
    result = await client.post(
        BASE + "/daily/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            **base,
            "source_fingerprint": "f" * 64,
            "actual_cash_minor": 0,
            "actual_upi_minor": 100,
            "reason": "Future collections cannot be attested",
        },
    )
    assert result.status_code == 422, result.text
    assert "future business day" in result.text


@pytest.mark.asyncio
async def test_owner_ceiling_scope_and_confirmation_revocation(client, session, seed_owner):
    headers, h, day, order, payment, shift, body = await prepared(client, session, seed_owner)
    for code in ("owner", "co_owner"):
        role = await session.scalar(
            select(Role).where(Role.company_id == order.company_id, Role.code == code)
        )
        if role is None:
            role = Role(
                id=uuid4(),
                company_id=order.company_id,
                code=code,
                name=code,
                permissions=["finance.tender.correct"],
            )
            session.add(role)
            await session.flush()
        ordinary = User(
            id=uuid4(),
            company_id=order.company_id,
            email=f"ordinary-{uuid4()}@test.local",
            name=code,
            password_hash=hash_password(seed_owner["password"]),
            status="active",
        )
        session.add(ordinary)
        await session.flush()
        session.add(UserRole(id=uuid4(), user_id=ordinary.id, role_id=role.id))
        await session.commit()
        await session.refresh(ordinary)
        flagged_legacy_token = issue_access_token(
            user_id=ordinary.id,
            company_id=order.company_id,
            branch_id=order.branch_id,
            roles=[code],
            auth_version=ordinary.auth_version,
            extra={"protected_access": True, "audit_access": True},
        )
        denied = await client.post(
            BASE + "/unlock",
            headers={"Authorization": "Bearer " + flagged_legacy_token},
            json={
                "branch_id": str(order.branch_id),
                "scope": "payment",
                "password": seed_owner["password"],
            },
        )
        assert denied.status_code == 403, denied.text
    other_company = Company(id=uuid4(), name="Unrelated synthetic tenant")
    session.add(other_company)
    await session.flush()
    foreign = Branch(
        id=uuid4(), company_id=other_company.id, name="Foreign", invoice_series_code="FR"
    )
    sibling = Branch(
        id=uuid4(), company_id=order.company_id, name="Sibling", invoice_series_code="SB"
    )
    session.add_all([foreign, sibling])
    await session.commit()
    for branch in (foreign, sibling):
        denied = await client.post(
            BASE + "/unlock",
            headers=headers,
            json={
                "branch_id": str(branch.id),
                "scope": "daily",
                "password": seed_owner["password"],
            },
        )
        assert denied.status_code == 404, denied.text
    denied = await client.post(
        BASE + "/unlock",
        headers=headers,
        json={
            "branch_id": str(order.branch_id),
            "scope": "payment",
            "password": "wrong synthetic password",
        },
    )
    assert denied.status_code == 401, denied.text
    claims = jwt.decode(h["X-Tender-Token"], options={"verify_signature": False})
    settings = get_settings()
    invalid = [
        None,
        "invalid",
        issue_tender_token(
            user_id=seed_owner["owner"].id,
            company_id=order.company_id,
            branch_id=order.branch_id,
            auth_version=seed_owner["owner"].auth_version,
            scope="daily",
        ),
        issue_tender_token(
            user_id=seed_owner["owner"].id,
            company_id=order.company_id,
            branch_id=sibling.id,
            auth_version=seed_owner["owner"].auth_version,
            scope="payment",
        ),
        jwt.encode(
            {**claims, "exp": datetime.now(UTC) - timedelta(seconds=1)},
            settings.jwt_secret,
            algorithm=settings.jwt_algorithm,
        ),
    ]
    for token in invalid:
        response = await client.post(
            BASE + "/payment/commit",
            headers={**headers, "X-Tender-Token": token or "", "Idempotency-Key": str(uuid4())},
            json=body,
        )
        assert response.status_code == 401, response.text
    await session.execute(
        text("UPDATE users SET auth_version=auth_version+1 WHERE id=:id"),
        {"id": seed_owner["owner"].id},
    )
    await session.commit()
    await session.refresh(seed_owner["owner"])
    refreshed = issue_access_token(
        user_id=seed_owner["owner"].id,
        company_id=order.company_id,
        branch_id=order.branch_id,
        roles=["super_owner"],
        auth_version=seed_owner["owner"].auth_version,
    )
    response = await client.post(
        BASE + "/payment/commit",
        headers={**h, "Authorization": "Bearer " + refreshed, "Idempotency-Key": str(uuid4())},
        json=body,
    )
    assert response.status_code == 401, response.text
    assert (
        await session.scalar(
            select(PaymentTenderCorrection.id).where(
                PaymentTenderCorrection.payment_id == payment.id
            )
        )
        is None
    )


@pytest.mark.asyncio
async def test_durable_replay_refuses_other_actor_and_cross_route_key(client, session, seed_owner):
    headers, h, day, order, payment, shift, body = await prepared(client, session, seed_owner)
    key = "tender:" + str(uuid4())
    result = await client.post(
        BASE + "/payment/commit", headers={**h, "Idempotency-Key": key}, json=body
    )
    assert result.status_code == 201, result.text
    # This action does not depend on the expiring generic replay cache.
    assert await session.scalar(select(IdempotencyKey.key).where(IdempotencyKey.key == key)) is None
    replay = await client.post(
        BASE + "/payment/commit", headers={**h, "Idempotency-Key": key}, json=body
    )
    assert replay.json() == result.json()
    role = await session.scalar(
        select(Role).where(Role.company_id == order.company_id, Role.code == "super_owner")
    )
    actor = User(
        id=uuid4(),
        company_id=order.company_id,
        email=f"other-{uuid4()}@test.local",
        name="Other protected owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add(actor)
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=actor.id, role_id=role.id))
    await session.commit()
    await session.refresh(actor)
    other_headers = {
        **headers,
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=actor.id,
            company_id=order.company_id,
            branch_id=order.branch_id,
            roles=["super_owner"],
            auth_version=actor.auth_version,
        ),
    }
    other_seed = {**seed_owner, "owner": actor}
    other_h = await unlock(client, other_seed, other_headers, "payment")
    denied = await client.post(
        BASE + "/payment/commit", headers={**other_h, "Idempotency-Key": key}, json=body
    )
    assert denied.status_code == 409, denied.text
    daily_h = await unlock(client, seed_owner, headers, "daily")
    denied = await client.post(
        BASE + "/daily/commit",
        headers={**daily_h, "Idempotency-Key": key},
        json={
            "branch_id": str(order.branch_id),
            "business_date": str(day),
            "source_fingerprint": body["source_fingerprint"],
            "actual_cash_minor": 0,
            "actual_upi_minor": 27000,
            "reason": "Cannot reuse another operation key",
        },
    )
    assert denied.status_code == 409, denied.text


@pytest.mark.asyncio
async def test_competing_payment_and_daily_commands_cannot_double_post(client, session, seed_owner):
    headers, h, day, order, payment, shift, body = await prepared(client, session, seed_owner)
    daily_h = await unlock(client, seed_owner, headers, "daily")
    preview = await client.post(
        BASE + "/daily/prepare",
        headers=daily_h,
        json={"branch_id": str(order.branch_id), "business_date": str(day)},
    )
    assert preview.status_code == 200, preview.text
    results = await asyncio.gather(
        client.post(
            BASE + "/payment/commit", headers={**h, "Idempotency-Key": str(uuid4())}, json=body
        ),
        client.post(
            BASE + "/daily/commit",
            headers={**daily_h, "Idempotency-Key": str(uuid4())},
            json={
                "branch_id": str(order.branch_id),
                "business_date": str(day),
                "source_fingerprint": preview.json()["source_fingerprint"],
                "actual_cash_minor": 0,
                "actual_upi_minor": 27000,
                "reason": "Competing owner classification",
            },
        ),
    )
    assert sorted(x.status_code for x in results) == [201, 409], [
        (x.status_code, x.text) for x in results
    ]
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


@pytest.mark.asyncio
async def test_actual_database_deadlock_retries_fresh_transaction(
    client, session, seed_owner, monkeypatch
):
    import app.api.v1.finance.tender_corrections as api

    headers, h, day, order, payment, shift, body = await prepared(client, session, seed_owner)
    original = api.lock_sources
    attempts = []

    async def short_deadlock_detection(current, company):
        attempts.append(current)
        await current.execute(text("SET LOCAL deadlock_timeout='30ms'"))
        await original(current, company)

    monkeypatch.setattr(api, "lock_sources", short_deadlock_detection)
    dsn = os.environ["DATABASE_URL"].replace("postgresql+psycopg://", "postgresql://", 1)
    with psycopg.connect(dsn) as writer, psycopg.connect(dsn, autocommit=True) as observer:
        writer.execute("SELECT dcompany_collection_lock(%s)", (order.company_id,))
        task = asyncio.create_task(
            client.post(
                BASE + "/payment/commit", headers={**h, "Idempotency-Key": str(uuid4())}, json=body
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
        assert waiting
        assert not task.done()
        # Deliberately reverse the parent's lock order. PostgreSQL detects a
        # real cycle and aborts the API attempt; the read-only contender wins.
        await asyncio.to_thread(
            writer.execute, "SELECT id FROM orders WHERE id=%s FOR UPDATE", (order.id,)
        )
        writer.commit()
        response = await asyncio.wait_for(task, 10)
    assert response.status_code == 201, response.text
    assert len(attempts) >= 2
    assert len({id(x) for x in attempts}) == len(attempts)
    await session.refresh(shift)
    assert shift.expected_minor == 263000


@pytest.mark.asyncio
async def test_month_boundary_uses_payment_accounting_date_but_shift_sheet_day(
    client, session, seed_owner
):
    from app.core.timezone import local_date_bounds_utc
    from app.services.accounting.ledger import build_operational_ledger

    first = date.today().replace(day=1)
    day = first - timedelta(days=1)
    opened = datetime(
        day.year, day.month, day.day, 23, 30, tzinfo=ZoneInfo("Asia/Kolkata")
    ).astimezone(UTC)
    headers, _, order, payment, _ = await case(session, seed_owner, opened_at=opened)
    h = await unlock(client, seed_owner, headers, "payment")
    preview = await client.post(
        BASE + "/payment/prepare", headers=h, json={"payment_id": str(payment.id)}
    )
    result = await client.post(
        BASE + "/payment/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            "payment_id": str(payment.id),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "reason": "Synthetic month boundary original payment",
        },
    )
    assert result.status_code == 201, result.text
    assert result.json()["business_date"] == str(day)
    assert datetime.fromisoformat(result.json()["accounting_at"]) == payment.paid_at
    for at, expected_count in ((day, 0), (first, 2)):
        start, end = local_date_bounds_utc(at, at, "Asia/Kolkata")
        ledger = await build_operational_ledger(
            session, company_id=order.company_id, start_at=start, end_exclusive=end
        )
        corrected = [x for x in ledger if x.ref_type == "payment_tender_correction"]
        assert len(corrected) == expected_count
        assert sum(x.debit_minor for x in corrected) == sum(x.credit_minor for x in corrected)
    snapshot = await calculate_daily_collections(
        session,
        company_id=order.company_id,
        branch_id=order.branch_id,
        timezone_name="Asia/Kolkata",
        start_date=day,
        end_date=first,
    )
    assert [(x.cash_minor, x.upi_minor) for x in snapshot] == [(0, 27000), (0, 0)]


async def refund_request(client, headers, order, shift, payment, action):
    body = {
        "order_id": str(order.id),
        "shift_id": str(shift.id),
        "reason_code": "CUSTOMER_REQUEST",
        "amount_minor": payment.amount_minor,
        "expected_paid_minor": payment.amount_minor,
        "expected_refundable_minor": payment.amount_minor,
        "mode": "original",
        "client_action_id": action,
        "note": "Synthetic retained refund proof",
    }
    response = await client.post(
        "/api/v1/pos/refund-requests",
        headers={**headers, "Idempotency-Key": action, "X-Client-Action-Id": action},
        json=body,
    )
    assert response.status_code == 201, response.text
    return body, response.json()


@pytest.mark.asyncio
async def test_pending_refund_refused_withdrawn_replay_retains_cash_new_refund_uses_upi(
    client, session, seed_owner
):
    headers, h, day, order, payment, old_shift, body = await prepared(client, session, seed_owner)
    current = await _shift(
        session,
        seed_owner,
        seed_owner["terminal"],
        opened_at=datetime.now(UTC) - timedelta(hours=1),
        expected_minor=100000,
    )
    await session.commit()
    action = "refund:" + str(uuid4())
    old_body, accepted = await refund_request(client, headers, order, current, payment, action)
    assert accepted["settlement_method"] == "cash"
    refused = await client.post(
        BASE + "/payment/prepare", headers=h, json={"payment_id": str(payment.id)}
    )
    assert refused.status_code == 422, refused.text
    withdrawn = await client.post(
        "/api/v1/pos/refund-requests/" + accepted["id"] + "/withdraw-cash",
        headers={**headers, "Idempotency-Key": str(uuid4()), "X-Client-Action-Id": str(uuid4())},
        json={
            "shift_id": str(current.id),
            "expected_amount_minor": payment.amount_minor,
            "cash_not_handed_over": True,
            "reason": "Cancelled before any cash handoff",
            "withdrawn_at": accepted["accepted_at"],
        },
    )
    assert withdrawn.status_code == 201, withdrawn.text
    preview = await client.post(
        BASE + "/payment/prepare", headers=h, json={"payment_id": str(payment.id)}
    )
    result = await client.post(
        BASE + "/payment/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={**body, "source_fingerprint": preview.json()["source_fingerprint"]},
    )
    assert result.status_code == 201, result.text
    # Remove only the generic cache, preserving immutable original action.
    await session.execute(text("DELETE FROM idempotency_keys WHERE key=:key"), {"key": action})
    await session.commit()
    replay = await client.post(
        "/api/v1/pos/refund-requests",
        headers={
            **headers,
            "Idempotency-Key": "replay:" + str(uuid4()),
            "X-Client-Action-Id": action,
        },
        json=old_body,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json()["id"] == accepted["id"]
    assert replay.json()["settlement_method"] == "cash"
    assert replay.json()["status"] == "withdrawn"
    _, new = await refund_request(client, headers, order, current, payment, "new:" + str(uuid4()))
    assert new["settlement_method"] == "upi"
    assert new["id"] != accepted["id"]


@pytest.mark.asyncio
async def test_correction_and_signed_sheet_outbox_are_atomic(
    client, session, seed_owner, monkeypatch
):
    import app.api.v1.finance.tender_corrections as api
    from app.models.google_sheets_delivery import GoogleSheetsDelivery
    from tests.integration.test_google_sheets_daily_collection_summary import (
        _enable_summary,
        _summary_rows,
        _verify_summary,
    )

    headers, h, day, order, payment, shift, _ = await prepared(client, session, seed_owner)
    company = await _enable_summary(session, seed_owner, target_day=day, now=datetime.now(UTC))
    await _verify_summary(session, company, now=datetime.now(UTC))
    await session.commit()
    preview = await client.post(
        BASE + "/payment/prepare", headers=h, json={"payment_id": str(payment.id)}
    )
    body = {
        "payment_id": str(payment.id),
        "source_fingerprint": preview.json()["source_fingerprint"],
        "reason": "Atomic receipt and normal signed Sheet revision",
    }
    original = api.enqueue_tender_daily_snapshot
    called = []

    async def fail_after_enqueue(current, **kwargs):
        called.append(True)
        await original(current, **kwargs)
        await current.execute(
            text("DO $$ BEGIN RAISE EXCEPTION 'synthetic retry' USING ERRCODE='40001'; END $$")
        )

    monkeypatch.setattr(api, "enqueue_tender_daily_snapshot", fail_after_enqueue)
    action = {**h, "Idempotency-Key": str(uuid4())}
    failed = await client.post(BASE + "/payment/commit", headers=action, json=body)
    assert failed.status_code == 409, failed.text
    assert failed.json()["error"]["details"]["retryable"] is True, failed.text
    assert len(called) == 3
    assert (
        await session.scalar(
            select(PaymentTenderCorrection.id).where(
                PaymentTenderCorrection.payment_id == payment.id
            )
        )
        is None
    )
    assert await _summary_rows(session, company.id) == []
    monkeypatch.setattr(api, "enqueue_tender_daily_snapshot", original)
    result = await client.post(BASE + "/payment/commit", headers=action, json=body)
    assert result.status_code == 201, result.text
    rows = await _summary_rows(session, company.id)
    assert len(rows) == 1
    assert rows[0].payload["cash_minor"] == 0
    assert rows[0].payload["upi_minor"] == 27000
    replay = await client.post(BASE + "/payment/commit", headers=action, json=body)
    assert replay.json() == result.json()
    assert len(await _summary_rows(session, company.id)) == 1
    assert not list(
        await session.scalars(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.source_type == "order",
            )
        )
    )

    company.google_sheets_mirror_enabled = False
    await session.commit()


@pytest.mark.asyncio
async def test_mixed_rail_daily_attestation_changes_only_cash_and_upi(client, session, seed_owner):
    from tests.integration.test_google_sheets_collections import (
        _close_shift,
        _event_time,
        _paid_order,
    )

    headers, _, day, order, _, _, _ = await prepared(client, session, seed_owner)
    # Use another completed day, retaining Card as an independently recorded
    # rail rather than claiming the owner recounted it.
    target = day - timedelta(days=1)
    shift = await _shift(session, seed_owner, seed_owner["terminal"], opened_at=_event_time(target))
    await _paid_order(
        session, seed_owner, shift, (("cash", 10000), ("upi", 10000), ("card", 50000))
    )
    await _close_shift(session, shift, seed_owner["owner"].id)
    await session.commit()
    h = await unlock(client, seed_owner, headers, "daily")
    base = {"branch_id": str(order.branch_id), "business_date": str(target)}
    preview = await client.post(BASE + "/daily/prepare", headers=h, json=base)
    assert preview.status_code == 200, preview.text
    assert preview.json()["attestation_scope"] == "cash_upi_only"
    assert preview.json()["recorded_total_minor"] == 20000
    assert preview.json()["recorded_all_rails_total_minor"] == 70000
    assert preview.json()["unchanged_other_rails"] == {"card": 50000}
    result = await client.post(
        BASE + "/daily/commit",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            **base,
            "source_fingerprint": preview.json()["source_fingerprint"],
            "actual_cash_minor": 8000,
            "actual_upi_minor": 12000,
            "reason": "Cash and UPI actuals; Card unchanged",
        },
    )
    assert result.status_code == 201, result.text
    assert result.json()["total_difference_minor"] == 0
    refreshed = await client.post(BASE + "/daily/prepare", headers=h, json=base)
    assert refreshed.json()["recorded_all_rails_total_minor"] == 70000
    assert refreshed.json()["unchanged_other_rails"] == {"card": 50000}
    assert (refreshed.json()["recorded_cash_minor"], refreshed.json()["recorded_upi_minor"]) == (
        8000,
        12000,
    )


@pytest.mark.asyncio
async def test_identical_simultaneous_commands_recover_one_durable_receipt(
    client, session, seed_owner
):
    headers, h, day, order, payment, shift, body = await prepared(client, session, seed_owner)
    action = {**h, "Idempotency-Key": "same:" + str(uuid4())}
    responses = await asyncio.gather(
        *(client.post(BASE + "/payment/commit", headers=action, json=body) for _ in range(2))
    )
    assert [x.status_code for x in responses] == [201, 201], [
        (x.status_code, x.text) for x in responses
    ]
    assert responses[0].json() == responses[1].json()
    from app.models import AuditLog

    assert (
        len(
            list(
                await session.scalars(
                    select(PaymentTenderCorrection).where(
                        PaymentTenderCorrection.payment_id == payment.id
                    )
                )
            )
        )
        == 1
    )
    assert (
        len(
            list(
                await session.scalars(
                    select(AuditLog.id).where(
                        AuditLog.company_id == order.company_id,
                        AuditLog.action == "payment_method_corrected",
                    )
                )
            )
        )
        == 1
    )


@pytest.mark.asyncio
async def test_concurrent_configuration_commit_invalidates_waiting_preview(
    client, session, seed_owner
):
    headers, h, day, order, payment, shift, body = await prepared(client, session, seed_owner)
    dsn = os.environ["DATABASE_URL"].replace("postgresql+psycopg://", "postgresql://", 1)
    with psycopg.connect(dsn) as writer, psycopg.connect(dsn, autocommit=True) as observer:
        writer.execute(
            "UPDATE companies SET timezone='Asia/Dubai' WHERE id=%s",
            (order.company_id,),
        )
        task = asyncio.create_task(
            client.post(
                BASE + "/payment/commit", headers={**h, "Idempotency-Key": str(uuid4())}, json=body
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
            assert waiting
            assert not task.done()
        finally:
            writer.commit()
        result = await asyncio.wait_for(task, 10)
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
async def test_settled_partial_refund_refuses_whole_leg_correction(client, session, seed_owner):
    headers, h, day, order, payment, old_shift, body = await prepared(client, session, seed_owner)
    current = await _shift(
        session,
        seed_owner,
        seed_owner["terminal"],
        opened_at=datetime.now(UTC) - timedelta(hours=1),
        expected_minor=100000,
    )
    await session.commit()
    action = "partial:" + str(uuid4())
    payload = {
        "order_id": str(order.id),
        "shift_id": str(current.id),
        "reason_code": "CUSTOMER_REQUEST",
        "amount_minor": 1000,
        "expected_paid_minor": payment.amount_minor,
        "expected_refundable_minor": payment.amount_minor,
        "mode": "original",
        "client_action_id": action,
        "note": "Retained partial refund",
    }
    response = await client.post(
        "/api/v1/pos/refund-requests",
        headers={**headers, "Idempotency-Key": action, "X-Client-Action-Id": action},
        json=payload,
    )
    assert response.status_code == 201, response.text
    request_id = response.json()["id"]

    async def post(suffix, payload):
        key = suffix + ":" + str(uuid4())
        result = await client.post(
            "/api/v1/pos/refund-requests/" + request_id + "/" + suffix,
            headers={**headers, "Idempotency-Key": key, "X-Client-Action-Id": key},
            json={"shift_id": str(current.id), "expected_amount_minor": 1000, **payload},
        )
        assert result.status_code == 201, result.text
        return result.json()

    begun = await post("begin-cash-handoff", {"ready_to_handover": True})
    await post("settle-cash", {"cash_handed_over": True, "settled_at": begun["handoff_started_at"]})
    await post("finalize-cash", {})
    await session.refresh(order)
    assert order.status == "paid", "partial settlement must preserve a paid source bill"
    refused = await client.post(
        BASE + "/payment/prepare", headers=h, json={"payment_id": str(payment.id)}
    )
    assert refused.status_code == 422, refused.text
    assert "settled or unresolved refund" in refused.text, refused.text


def test_all_collection_source_tables_have_guard_and_fingerprint_coverage():
    import importlib.util
    from pathlib import Path

    spec = importlib.util.spec_from_file_location(
        "tender_migration",
        Path(__file__).parents[2] / "alembic/versions/0091_tender_correction_receipts.py",
    )
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    dsn = os.environ["DATABASE_URL"].replace("postgresql+psycopg://", "postgresql://", 1)
    with psycopg.connect(dsn) as db:
        covered = {
            row[0]
            for row in db.execute(
                "SELECT c.relname FROM pg_trigger t JOIN pg_class c ON "
                "c.oid=t.tgrelid WHERE t.tgname='zz_collection_fence' AND "
                "t.tgenabled='O'"
            )
        }
        assert covered == set(migration.TABLES)
        function = db.execute(
            "SELECT "
            "pg_get_functiondef('dcompany_collection_fingerprint(uuid,uuid,date,text)'::regprocedure)"
        ).fetchone()[0]
        for table in migration.TABLES:
            assert "FROM " + table + " t WHERE" in function
        config = db.execute(
            "SELECT pg_get_functiondef('dcompany_tender_config_fence()'::regprocedure)"
        ).fetchone()[0]
        for field in (
            "timezone",
            "currency",
            "google_sheets_summary_timezone",
            "google_sheets_summary_branch_id",
        ):
            triggers = " ".join(
                row[0]
                for row in db.execute(
                    "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname IN "
                    "('zz_tender_company_configuration','zz_tender_branch_configuration')"
                )
            )
            assert field in config or field in triggers
