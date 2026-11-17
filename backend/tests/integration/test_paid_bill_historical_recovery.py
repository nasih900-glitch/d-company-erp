"""Cold exact-command recovery does not establish operational authentication."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import select, text

from app.core.db import AsyncSessionLocal
from app.models import Branch, PaidBillRecoverySession, Terminal, User
from app.services.pos import paid_bill_recovery as service
from tests.integration.test_paid_bill_amendments import BASE, accept, quoted
from tests.integration.test_paid_bill_amendments import paid as paid_fixture

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
RECOVERY = BASE + "/recovery"
paid = paid_fixture


def bootstrap(case, seed_owner, command):
    return {
        "company_id": str(case.company_id),
        "actor_user_id": str(seed_owner["owner"].id),
        "branch_id": str(case.branch_id),
        "terminal_id": str(case.terminal_id),
        "order_id": str(case.order_id),
        "request_path": BASE + "/commit",
        "original_command": command,
        "password": seed_owner["password"],
    }


async def unlock(client, case, seed_owner, command, key):
    client.cookies.clear()
    reply = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, command),
        headers={"Idempotency-Key": key},
    )
    assert reply.status_code == 200, reply.text
    assert reply.headers["cache-control"] == "no-store"
    assert set(reply.json()) == {"protocol", "purpose", "token", "expires_in_seconds"}
    assert "set-cookie" not in reply.headers
    return {"Idempotency-Key": key, "X-Paid-Bill-Recovery": reply.json()["token"]}


async def cancel(
    client, command, headers, reason="Retire this exact unresolved historical request"
):
    return await client.post(
        RECOVERY + "/actions/cancel",
        json={
            "original_command": command,
            "cancellation_reason": reason,
        },
        headers=headers,
    )


async def snapshot(session, case):
    return (
        await session.execute(
            text("""SELECT
     (SELECT to_jsonb(o) FROM orders o WHERE id=:o),
     (SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payments p WHERE order_id=:o),
     (SELECT to_jsonb(s) FROM shifts s WHERE id=:s),
     (SELECT count(*) FROM refunds WHERE order_id=:o),
     (SELECT count(*) FROM pos_refund_requests WHERE order_id=:o),
     (SELECT count(*) FROM stock_movements WHERE ref_id=:o),
     (SELECT count(*) FROM auth_refresh_sessions WHERE company_id=:c)
    """),
            {"o": case.order_id, "s": case.shift_id, "c": case.company_id},
        )
    ).one()


async def test_cold_unlock_cancel_replay_and_revocation_leave_business_exact(
    client, session, paid, seed_owner
):
    case, normal = paid
    command = await quoted(client, case, normal)
    key = "historical:" + str(uuid4())
    before = await snapshot(session, case)
    h = await unlock(client, case, seed_owner, command, key)
    assert (await client.get(RECOVERY + "/actions/outcome", headers=h)).json()[
        "outcome"
    ] == "unconfirmed"
    result = await cancel(client, command, h)
    assert result.status_code == 200, result.text
    assert result.json()["outcome"] == "cancelled"
    assert result.json()["reference"]["original_command"] == command
    assert (await cancel(client, command, h)).json() == result.json()
    assert (await client.get(RECOVERY + "/actions/outcome", headers=h)).json() == result.json()
    assert await snapshot(session, case) == before
    assert (
        await client.post(
            BASE + "/commit", json=command, headers={**normal, "Idempotency-Key": key}
        )
    ).status_code == 409
    lock_headers = {"X-Paid-Bill-Recovery": h["X-Paid-Bill-Recovery"]}
    locked = await client.post(RECOVERY + "/lock", headers=lock_headers)
    assert locked.status_code == 200, locked.text
    assert (await client.post(RECOVERY + "/lock", headers=lock_headers)).status_code == 200
    assert (await client.get(RECOVERY + "/actions/outcome", headers=h)).status_code == 401


async def test_archived_terminal_deleted_branch_keeps_normal_login_closed(
    client, session, paid, seed_owner
):
    case, normal = paid
    command = await quoted(client, case, normal)
    expected = await session.scalar(
        text("SELECT expected_minor FROM shifts WHERE id=:s"), {"s": case.shift_id}
    )
    await session.commit()
    closed = await client.post(
        f"/api/v1/pos/shifts/{case.shift_id}/close",
        json={"counted_minor": expected},
        headers=normal,
    )
    assert closed.status_code == 200, closed.text
    other_branch = Branch(
        id=uuid4(), company_id=case.company_id, name="Legacy live branch", invoice_series_code="LB"
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=case.branch_id,
        name="Legacy B",
        purpose="hybrid",
        is_active=True,
        device_id="legacy:" + str(uuid4()),
    )
    session.add(other_branch)
    await session.flush()
    # Explicit legacy synthetic fixture; no trigger bypass or production reactivation.
    await session.execute(
        text("UPDATE terminals SET is_active=false WHERE id=:t"), {"t": case.terminal_id}
    )
    session.add(other_terminal)
    await session.flush()
    await session.execute(
        text("UPDATE user_roles SET branch_id=:b WHERE user_id=:u"),
        {"b": case.branch_id, "u": seed_owner["owner"].id},
    )
    await session.execute(
        text("UPDATE branches SET deleted_at=statement_timestamp() WHERE id=:b"),
        {"b": case.branch_id},
    )
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert login.status_code == 401, login.text
    before = await snapshot(session, case)
    h = await unlock(client, case, seed_owner, command, "archived:" + str(uuid4()))
    result = await cancel(client, command, h)
    assert result.status_code == 200, result.text
    assert result.json()["outcome"] == "cancelled"
    assert await snapshot(session, case) == before
    assert (
        await session.scalar(select(Terminal.is_active).where(Terminal.id == other_terminal.id))
        is True
    )


async def test_accepted_winner_is_information_only_and_keeps_original_task(
    client, session, paid, seed_owner
):
    case, normal = paid
    command, key, accepted = await accept(client, case, normal)
    h = await unlock(client, case, seed_owner, command, key)
    before = await snapshot(session, case)
    result = await cancel(client, command, h)
    assert result.status_code == 200, result.text
    assert result.json()["accepted"] == accepted
    assert result.json()["outcome"] == "accepted"
    assert await snapshot(session, case) == before
    old = await client.get(BASE + "/actions/by-key", headers={**normal, "Idempotency-Key": key})
    assert old.status_code == 200
    assert old.json()["accepted"] == accepted


@pytest.mark.parametrize(
    "change", ["company", "actor", "branch", "terminal", "order", "password", "path", "body_order"]
)
async def test_bootstrap_scope_and_password_do_not_disclose(
    client, session, paid, seed_owner, change
):
    case, normal = paid
    command = await quoted(client, case, normal)
    body = bootstrap(case, seed_owner, command)
    if change in ["company", "actor", "branch", "terminal", "order"]:
        body[
            {
                "company": "company_id",
                "actor": "actor_user_id",
                "branch": "branch_id",
                "terminal": "terminal_id",
                "order": "order_id",
            }[change]
        ] = str(uuid4())
        if change == "order":
            body["original_command"] = {**command, "order_id": body["order_id"]}
    elif change == "password":
        body["password"] = "wrong-private-password-marker"
    elif change == "path":
        body["request_path"] = BASE + "/preview"
    else:
        body["original_command"] = {**command, "order_id": str(uuid4())}
    reply = await client.post(
        RECOVERY + "/unlock", json=body, headers={"Idempotency-Key": "scope:" + str(uuid4())}
    )
    assert reply.status_code in {401, 422}, reply.text
    assert "wrong-private-password-marker" not in reply.text
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PaidBillRecoverySession)
            .where(PaidBillRecoverySession.company_id == case.company_id)
        )
        == 0
    )
    if change == "password":
        await session.refresh(seed_owner["owner"])
        assert seed_owner["owner"].failed_login_count == 1


@pytest.mark.parametrize(
    "header", ["Authorization", "X-Terminal-Id", "Cookie", "X-Offline-Captured"]
)
async def test_bootstrap_never_inherits_ordinary_context(client, paid, seed_owner, header):
    case, normal = paid
    command = await quoted(client, case, normal)
    reply = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, command),
        headers={"Idempotency-Key": "inherit:" + str(uuid4()), header: "private-marker"},
    )
    assert reply.status_code == 401, reply.text


async def test_rebootstrap_replaces_only_same_command_and_tokens_have_no_operational_power(
    client, paid, seed_owner
):
    case, normal = paid
    command = await quoted(client, case, normal)
    key = "renew:" + str(uuid4())
    first = await unlock(client, case, seed_owner, command, key)
    other = await unlock(client, case, seed_owner, command, "other:" + str(uuid4()))
    new = await unlock(client, case, seed_owner, command, key)
    assert (await client.get(RECOVERY + "/actions/outcome", headers=first)).status_code == 401
    assert (await client.get(RECOVERY + "/actions/outcome", headers=other)).status_code == 200
    assert (await client.get(RECOVERY + "/actions/outcome", headers=new)).status_code == 200
    for token in [normal["Authorization"].split(" ", 1)[1], normal["X-Owner-Confirmation"]]:
        assert (
            await client.get(
                RECOVERY + "/actions/outcome",
                headers={"Idempotency-Key": key, "X-Paid-Bill-Recovery": token},
            )
        ).status_code == 401
    token = new["X-Paid-Bill-Recovery"]
    assert (
        await client.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + token})
    ).status_code == 401
    assert (
        await client.post("/api/v1/auth/refresh", json={"refresh_token": token})
    ).status_code == 401
    preview = {
        k: v for k, v in command.items() if k not in ["source_revision", "source_fingerprint"]
    }
    assert (
        await client.post(
            BASE + "/preview",
            json=preview,
            headers={"Authorization": "Bearer " + token, "X-Terminal-Id": str(case.terminal_id)},
        )
    ).status_code == 401


@pytest.mark.parametrize(
    "change",
    ["role", "inactive", "deleted_user", "company_deleted", "auth_version", "password", "locked"],
)
async def test_current_authority_loss_denies_recovery_but_allows_lock(
    client, session, paid, seed_owner, change
):
    from app.core.security import hash_password

    case, normal = paid
    command = await quoted(client, case, normal)
    h = await unlock(client, case, seed_owner, command, "authority:" + str(uuid4()))
    user = seed_owner["owner"]
    if change == "role":
        from tests.integration.test_pos_receipt_correction_preview import set_role

        await set_role(session, seed_owner, "co_owner")
    elif change == "inactive":
        user.status = "inactive"
    elif change == "deleted_user":
        user.deleted_at = datetime.now(UTC)
    elif change == "company_deleted":
        seed_owner["company"].deleted_at = datetime.now(UTC)
    elif change == "auth_version":
        user.auth_version += 1
    elif change == "password":
        user.password_hash = hash_password("changed-password")
        user.auth_version += 1
    else:
        user.locked_until = datetime.now(UTC) + timedelta(minutes=1)
    await session.commit()
    assert (await client.get(RECOVERY + "/actions/outcome", headers=h)).status_code == 401
    assert (await cancel(client, command, h)).status_code == 401
    locked = await client.post(
        RECOVERY + "/lock", headers={"X-Paid-Bill-Recovery": h["X-Paid-Bill-Recovery"]}
    )
    assert locked.status_code == 200, locked.text


async def test_exact_key_command_and_reason_never_retarget(client, paid, seed_owner):
    case, normal = paid
    command = await quoted(client, case, normal)
    key = "bound:" + str(uuid4())
    h = await unlock(client, case, seed_owner, command, key)
    assert (
        await client.get(
            RECOVERY + "/actions/outcome", headers={**h, "Idempotency-Key": key + "different"}
        )
    ).status_code == 401
    changed = {**command, "target_total_minor": command["target_total_minor"] - 1}
    assert (await cancel(client, changed, h)).status_code == 401
    result = await cancel(client, command, h)
    assert result.status_code == 200, result.text
    assert (await cancel(client, command, h, "A different cancellation reason")).status_code == 409
    bad = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, changed),
        headers={"Idempotency-Key": key},
    )
    assert bad.status_code == 409, bad.text


async def wait_for_lock(query):
    async with AsyncSessionLocal() as observer:
        for _ in range(100):
            await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
            if await observer.scalar(
                text(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    "AND wait_event_type='Lock' AND query ILIKE :q"
                ),
                {"q": "%" + query + "%"},
            ):
                return
            await asyncio.sleep(0.01)
    raise AssertionError("Expected actual PostgreSQL lock wait was not observed")


async def test_revocation_committed_before_waiting_reader_is_denied(client, paid, seed_owner):
    case, normal = paid
    command = await quoted(client, case, normal)
    h = await unlock(client, case, seed_owner, command, "revoke-race:" + str(uuid4()))
    from app.core.security import decode_token

    sid = decode_token(h["X-Paid-Bill-Recovery"])["sid"]
    async with AsyncSessionLocal() as revoker:
        await revoker.scalar(
            select(User).where(User.id == seed_owner["owner"].id).with_for_update(key_share=True)
        )
        row = await revoker.scalar(
            select(PaidBillRecoverySession)
            .where(PaidBillRecoverySession.id == sid)
            .with_for_update()
        )
        await service.revoke(revoker, row, "locked")
        await revoker.flush()
        pending = asyncio.create_task(client.get(RECOVERY + "/actions/outcome", headers=h))
        await wait_for_lock("FROM users")
        assert not pending.done()
        await revoker.commit()
    result = await asyncio.wait_for(pending, 3)
    assert result.status_code == 401, result.text


async def test_reader_linearizes_before_waiting_lock_then_future_use_denied(
    client, paid, seed_owner, monkeypatch
):
    case, normal = paid
    command = await quoted(client, case, normal)
    h = await unlock(client, case, seed_owner, command, "reader-first:" + str(uuid4()))
    entered, release = asyncio.Event(), asyncio.Event()
    original = service.historical_authority

    async def hold(*args, **kwargs):
        result = await original(*args, **kwargs)
        if not entered.is_set():
            entered.set()
            await asyncio.wait_for(release.wait(), 4)
        return result

    monkeypatch.setattr(service, "historical_authority", hold)
    reader = asyncio.create_task(client.get(RECOVERY + "/actions/outcome", headers=h))
    await asyncio.wait_for(entered.wait(), 2)
    locking = asyncio.create_task(
        client.post(RECOVERY + "/lock", headers={"X-Paid-Bill-Recovery": h["X-Paid-Bill-Recovery"]})
    )
    try:
        await wait_for_lock("FROM users")
        assert not locking.done()
    finally:
        release.set()
    assert (await reader).status_code == 200
    assert (await locking).status_code == 200
    assert (await client.get(RECOVERY + "/actions/outcome", headers=h)).status_code == 401


async def test_simultaneous_bootstrap_serializes_and_only_latest_exact_family_survives(
    client, session, paid, seed_owner
):
    case, normal = paid
    command = await quoted(client, case, normal)
    key = "bootstrap-race:" + str(uuid4())
    first, second = await asyncio.gather(
        unlock(client, case, seed_owner, command, key),
        unlock(client, case, seed_owner, command, key),
    )
    codes = [
        (await client.get(RECOVERY + "/actions/outcome", headers=h)).status_code
        for h in [first, second]
    ]
    assert sorted(codes) == [200, 401]
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PaidBillRecoverySession)
            .where(
                PaidBillRecoverySession.company_id == case.company_id,
                PaidBillRecoverySession.revoked_at.is_(None),
            )
        )
        == 1
    )


async def test_old_v6_order_holder_does_not_block_historical_cancel(
    client, paid, seed_owner, monkeypatch
):
    from app.services.pos import paid_bill_amendments as old_service

    case, normal = paid
    command = await quoted(client, case, normal)
    key = "historical-old-wait:" + str(uuid4())
    h = await unlock(client, case, seed_owner, command, key)
    entered, release = asyncio.Event(), asyncio.Event()
    original = old_service.scoped_order

    async def hold(*args, **kwargs):
        order = await original(*args, **kwargs)
        if kwargs.get("lock"):
            entered.set()
            await asyncio.wait_for(release.wait(), 4)
        return order

    monkeypatch.setattr(old_service, "scoped_order", hold)
    old = asyncio.create_task(
        client.post(BASE + "/commit", json=command, headers={**normal, "Idempotency-Key": key})
    )
    try:
        await asyncio.wait_for(entered.wait(), 2)
        result = await asyncio.wait_for(cancel(client, command, h), 1.5)
        assert result.status_code == 200, result.text
        assert result.json()["outcome"] == "cancelled"
        assert not old.done()
    finally:
        release.set()
    assert (await old).status_code == 409


@pytest.mark.parametrize("winner_rollback", [False, True])
async def test_acceptance_unique_wait_resolves_only_committed_winner(
    client, paid, seed_owner, monkeypatch, winner_rollback
):
    from app.services.pos import paid_bill_amendments as old_service

    case, normal = paid
    command = await quoted(client, case, normal)
    key = "historical-index:" + str(uuid4())
    h = await unlock(client, case, seed_owner, command, key)
    entered, release = asyncio.Event(), asyncio.Event()
    original = old_service.enqueue_google_sheets_event_if_enabled

    async def hold(*args, **kwargs):
        entered.set()
        await asyncio.wait_for(release.wait(), 4)
        if winner_rollback:
            raise RuntimeError("synthetic winner rollback")
        return await original(*args, **kwargs)

    monkeypatch.setattr(old_service, "enqueue_google_sheets_event_if_enabled", hold)
    old = asyncio.create_task(
        client.post(BASE + "/commit", json=command, headers={**normal, "Idempotency-Key": key})
    )
    try:
        await asyncio.wait_for(entered.wait(), 2)
        pending = asyncio.create_task(cancel(client, command, h))
        await wait_for_lock("INSERT INTO paid_bill_action_decisions")
        assert not pending.done()
    finally:
        release.set()
    old_result = (await asyncio.gather(old, return_exceptions=True))[0]
    result = await asyncio.wait_for(pending, 3)
    assert result.status_code == 200, result.text
    assert result.json()["outcome"] == ("cancelled" if winner_rollback else "accepted")
    if winner_rollback:
        assert isinstance(old_result, RuntimeError)
    else:
        assert old_result.status_code == 201
        assert result.json()["accepted"] == old_result.json()


async def test_expiry_during_actual_unique_wait_rolls_back_historical_cancel(
    client, session, paid, seed_owner, monkeypatch
):
    from app.services.pos import paid_bill_amendments as old_service

    case, normal = paid
    command = await quoted(client, case, normal)
    key = "historical-expiry:" + str(uuid4())
    h = await unlock(client, case, seed_owner, command, key)
    entered, release = asyncio.Event(), asyncio.Event()

    async def hold(*args, **kwargs):
        entered.set()
        await asyncio.wait_for(release.wait(), 4)
        raise RuntimeError("winner rollback after expiry")

    monkeypatch.setattr(old_service, "enqueue_google_sheets_event_if_enabled", hold)
    old = asyncio.create_task(
        client.post(BASE + "/commit", json=command, headers={**normal, "Idempotency-Key": key})
    )
    try:
        await asyncio.wait_for(entered.wait(), 2)
        pending = asyncio.create_task(cancel(client, command, h))
        await wait_for_lock("INSERT INTO paid_bill_action_decisions")

        class ExpiredClock(datetime):
            @classmethod
            def now(cls, tz=None):
                return datetime.now(UTC) + timedelta(seconds=301)

        monkeypatch.setattr(service, "datetime", ExpiredClock)
    finally:
        release.set()
    await asyncio.gather(old, return_exceptions=True)
    result = await asyncio.wait_for(pending, 3)
    assert result.status_code == 401, result.text
    assert (
        await session.scalar(
            text(
                "SELECT count(*) FROM paid_bill_action_decisions "
                "WHERE company_id=:c AND action_key=:k"
            ),
            {"c": case.company_id, "k": key},
        )
        == 0
    )


async def test_safe_logs_audit_and_failed_serialization_do_not_disclose_secrets(
    client, session, paid, seed_owner, capsys, monkeypatch
):
    from app.models import PaidBillActionDecision
    from app.services.audit import recorder

    case, normal = paid
    command = await quoted(client, case, normal)
    key = "PRIVATE_ORIGINAL_KEY_" + str(uuid4())
    reason = "PRIVATE_CANCELLATION_REASON_" + str(uuid4())
    command = {**command, "reason": "PRIVATE_ORIGINAL_REASON_" + str(uuid4())}
    capsys.readouterr()
    h = await unlock(client, case, seed_owner, command, key)
    result = await cancel(client, command, h, reason)
    assert result.status_code == 200, result.text
    captured = capsys.readouterr()
    logs = captured.out + captured.err
    audits = await session.scalar(
        text(
            "SELECT coalesce(jsonb_agg(to_jsonb(a)), '[]'::jsonb)::text FROM audit_log a "
            "WHERE company_id=:c AND (entity_type IN "
            "('PaidBillActionDecision','PaidBillRecoverySession') "
            "OR action LIKE 'paid_bill_recovery_%')"
        ),
        {"c": case.company_id},
    )
    row = await session.scalar(
        select(PaidBillRecoverySession).where(PaidBillRecoverySession.company_id == case.company_id)
    )
    for marker in [
        key,
        reason,
        command["reason"],
        seed_owner["password"],
        h["X-Paid-Bill-Recovery"],
        row.token_hash,
        row.action_key_hash,
        row.command_hash,
    ]:
        assert marker not in logs + audits
    assert result.json()["cancelled"]["cancellation_reason"] == reason
    # Arbitrary malformed field paths must also never become a generic validation log.
    malformed = bootstrap(case, seed_owner, command)
    malformed["PRIVATE_EXTRA_KEY_MARKER"] = "unused"
    bad = await client.post(RECOVERY + "/unlock", json=malformed, headers={"Idempotency-Key": key})
    assert bad.status_code == 422
    assert "PRIVATE_EXTRA_KEY_MARKER" not in bad.text
    captured = capsys.readouterr()
    assert "PRIVATE_EXTRA_KEY_MARKER" not in captured.out + captured.err
    # Failed general-audit serialization must roll back the domain decision and hide repr.
    key2 = "ROLLBACK_KEY_" + str(uuid4())
    h2 = await unlock(client, case, seed_owner, command, key2)
    original = recorder._serialize

    def fail(obj):
        if isinstance(obj, PaidBillActionDecision):
            raise RuntimeError("PRIVATE_EXCEPTION_PARAMETERS_" + key2)
        return original(obj)

    monkeypatch.setattr(recorder, "_serialize", fail)
    denied = await cancel(client, command, h2, reason)
    assert denied.status_code == 503, denied.text
    captured = capsys.readouterr()
    assert key2 not in captured.out + captured.err + denied.text
    assert (
        await session.scalar(
            text(
                "SELECT count(*) FROM paid_bill_action_decisions "
                "WHERE company_id=:c AND action_key=:k"
            ),
            {"c": case.company_id, "k": key2},
        )
        == 0
    )


async def test_normal_settings_soft_delete_blocks_branch_bound_login_but_not_cold_recovery(
    client, session, paid, seed_owner
):
    case, normal = paid
    command = await quoted(client, case, normal)
    expected = await session.scalar(
        text("SELECT expected_minor FROM shifts WHERE id=:s"), {"s": case.shift_id}
    )
    await session.commit()
    assert (
        await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": expected},
            headers=normal,
        )
    ).status_code == 200
    # Valid legacy multi-branch fixture; each branch still has at most one active till.
    other = Branch(
        id=uuid4(),
        company_id=case.company_id,
        name="Existing other branch",
        invoice_series_code="OB",
    )
    session.add(other)
    await session.flush()
    session.add(
        Terminal(
            id=uuid4(),
            branch_id=other.id,
            name="Other branch till",
            purpose="hybrid",
            is_active=True,
            device_id="other:" + str(uuid4()),
        )
    )
    await session.execute(
        text("UPDATE user_roles SET branch_id=:b WHERE user_id=:u"),
        {"b": case.branch_id, "u": seed_owner["owner"].id},
    )
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    current = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(case.terminal_id),
    }
    deleted = await client.delete(f"/api/v1/settings/branches/{case.branch_id}", headers=current)
    assert deleted.status_code in {200, 204}, deleted.text
    after_login = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert after_login.status_code == 401, after_login.text
    before = await snapshot(session, case)
    h = await unlock(client, case, seed_owner, command, "deleted-by-settings:" + str(uuid4()))
    result = await cancel(client, command, h)
    assert result.status_code == 200, result.text
    assert await snapshot(session, case) == before


@pytest.mark.parametrize("role", ["owner", "co_owner", "cashier"])
async def test_other_owner_or_staff_role_cannot_bootstrap(client, session, paid, seed_owner, role):
    from tests.integration.test_pos_receipt_correction_preview import set_role

    case, normal = paid
    command = await quoted(client, case, normal)
    await set_role(session, seed_owner, role)
    result = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, command),
        headers={"Idempotency-Key": "not-protected:" + str(uuid4())},
    )
    assert result.status_code == 401, result.text


async def test_other_branch_assignment_cannot_bootstrap_original_scope(
    client, session, paid, seed_owner
):
    case, normal = paid
    command = await quoted(client, case, normal)
    other = Branch(
        id=uuid4(),
        company_id=case.company_id,
        name="Different assigned branch",
        invoice_series_code="DB",
    )
    session.add(other)
    await session.flush()
    await session.execute(
        text("UPDATE user_roles SET branch_id=:b WHERE user_id=:u"),
        {"b": other.id, "u": seed_owner["owner"].id},
    )
    await session.commit()
    result = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, command),
        headers={"Idempotency-Key": "branch-assignment:" + str(uuid4())},
    )
    assert result.status_code == 401, result.text


@pytest.mark.parametrize("isolation", ["REPEATABLE READ", "SERIALIZABLE"])
async def test_new_recovery_api_service_fails_closed_on_non_rc(
    session, client, paid, seed_owner, isolation
):
    from starlette.requests import Request

    from app.core.errors import ConflictError

    case, normal = paid
    command = await quoted(client, case, normal)
    h = await unlock(client, case, seed_owner, command, "isolation:" + str(uuid4()))
    async with AsyncSessionLocal() as stale:
        await stale.execute(text("SET TRANSACTION ISOLATION LEVEL " + isolation))
        request = Request(
            {
                "type": "http",
                "method": "GET",
                "path": RECOVERY + "/actions/outcome",
                "headers": [],
                "query_string": b"",
            }
        )
        with pytest.raises(ConflictError, match="read-committed"):
            await service.outcome(stale, request, h["Idempotency-Key"], h["X-Paid-Bill-Recovery"])


@pytest.mark.parametrize(
    "mutation",
    [
        "UPDATE paid_bill_recovery_sessions SET command_hash=repeat('a',64) WHERE id=:s",
        "UPDATE paid_bill_recovery_sessions SET branch_id=:other WHERE id=:s",
        "UPDATE paid_bill_recovery_sessions SET auth_version=auth_version+1 WHERE id=:s",
        "UPDATE paid_bill_recovery_sessions "
        "SET expires_at=expires_at+interval '1 second' WHERE id=:s",
        "UPDATE paid_bill_recovery_sessions SET revoked_at=statement_timestamp(),"
        "revocation_reason='locked' WHERE id=:s",
        "DELETE FROM paid_bill_recovery_sessions WHERE id=:s",
        "TRUNCATE paid_bill_recovery_sessions",
    ],
)
async def test_raw_credential_mutation_and_unaudited_revocation_fail_closed(
    client, paid, seed_owner, mutation
):
    from sqlalchemy.exc import IntegrityError

    from app.core.security import decode_token

    case, normal = paid
    command = await quoted(client, case, normal)
    h = await unlock(client, case, seed_owner, command, "raw:" + str(uuid4()))
    sid = decode_token(h["X-Paid-Bill-Recovery"])["sid"]
    async with AsyncSessionLocal() as raw:

        async def mutate():
            await raw.execute(text(mutation), {"s": sid, "other": uuid4()})
            await raw.commit()

        with pytest.raises(IntegrityError) as failure:
            await mutate()
        state = getattr(failure.value.orig, "sqlstate", None)
        assert state == "23514", state
        if "revoked_at" in mutation:
            assert "atomic safe audit" in str(failure.value)
        await raw.rollback()
    assert (await client.get(RECOVERY + "/actions/outcome", headers=h)).status_code == 200


async def test_unaudited_issuance_and_audit_failure_return_no_usable_credential(
    client, session, paid, seed_owner, monkeypatch
):
    from sqlalchemy.exc import IntegrityError

    from app.core.security import decode_token

    case, normal = paid
    command = await quoted(client, case, normal)
    h = await unlock(client, case, seed_owner, command, "issuance:" + str(uuid4()))
    sid = decode_token(h["X-Paid-Bill-Recovery"])["sid"]
    new_id = uuid4()
    async with AsyncSessionLocal() as raw:
        await raw.execute(
            text("""INSERT INTO paid_bill_recovery_sessions
         (id,company_id,user_id,branch_id,terminal_id,order_id,purpose,request_path,
          action_key_hash,command_hash,token_hash,auth_version,issued_at,expires_at)
         SELECT :new,company_id,user_id,branch_id,terminal_id,order_id,purpose,request_path,
          action_key_hash,command_hash,repeat('e',64),auth_version,issued_at,expires_at
         FROM paid_bill_recovery_sessions WHERE id=:old"""),
            {"new": new_id, "old": sid},
        )
        with pytest.raises(IntegrityError, match="atomic safe audit"):
            await raw.commit()
        await raw.rollback()

    def fail(*args):
        raise RuntimeError("PRIVATE_ISSUANCE_AUDIT_FAILURE")

    monkeypatch.setattr(service, "audit", fail)
    bad = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, command),
        headers={"Idempotency-Key": "audit-failure:" + str(uuid4())},
    )
    assert bad.status_code == 503
    assert "token" not in bad.json()
    assert "PRIVATE_ISSUANCE" not in bad.text
    assert (
        await session.scalar(
            text("SELECT count(*) FROM paid_bill_recovery_sessions WHERE company_id=:c"),
            {"c": case.company_id},
        )
        == 1
    )
    assert (await client.get(RECOVERY + "/actions/outcome", headers=h)).status_code == 200


async def test_bootstrap_deferred_audit_failure_cannot_send_prepared_token(
    client, session, paid, seed_owner, monkeypatch
):
    case, normal = paid
    command = await quoted(client, case, normal)
    monkeypatch.setattr(service, "audit", lambda *args: None)
    result = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, command),
        headers={"Idempotency-Key": "deferred-failure:" + str(uuid4())},
    )
    assert result.status_code == 409, result.text
    assert "token" not in result.json()
    assert (
        await session.scalar(
            text("SELECT count(*) FROM paid_bill_recovery_sessions WHERE company_id=:c"),
            {"c": case.company_id},
        )
        == 0
    )


@pytest.mark.parametrize("token", ["not-a-token", "x" * 8193])
async def test_malformed_and_oversized_credentials_are_generic(client, token):
    result = await client.get(
        RECOVERY + "/actions/outcome",
        headers={"Idempotency-Key": "bad-credential", "X-Paid-Bill-Recovery": token},
    )
    assert result.status_code == 401, result.text
    assert token not in result.text


async def test_ordinary_logout_does_not_revive_or_implicitly_revoke_recovery_families(
    client, session, paid, seed_owner
):
    case, normal = paid
    command = await quoted(client, case, normal)
    key = "separate-families:" + str(uuid4())
    first = await unlock(client, case, seed_owner, command, key)
    logged_out = await client.post(
        "/api/v1/auth/logout", headers={"Authorization": normal["Authorization"]}
    )
    assert logged_out.status_code == 200, logged_out.text
    assert (
        await client.get("/api/v1/auth/me", headers={"Authorization": normal["Authorization"]})
    ).status_code == 401
    # Existing logout is ordinary-family-scoped; recovery Lock is a separate operation.
    assert (await client.get(RECOVERY + "/actions/outcome", headers=first)).status_code == 200
    renewed = await unlock(client, case, seed_owner, command, key)
    assert (await client.get(RECOVERY + "/actions/outcome", headers=first)).status_code == 401
    assert (await client.get(RECOVERY + "/actions/outcome", headers=renewed)).status_code == 200
    assert (
        await session.scalar(
            text(
                "SELECT count(*) FROM auth_refresh_sessions "
                "WHERE company_id=:c AND revoked_at IS NULL"
            ),
            {"c": case.company_id},
        )
        == 0
    )


async def test_bootstrap_throttle_failure_precedes_password_verification(
    client, session, paid, seed_owner, monkeypatch
):
    from app.core.errors import ServiceUnavailableError

    case, normal = paid
    command = await quoted(client, case, normal)

    async def unavailable(*args):
        raise ServiceUnavailableError("Login protection is temporarily unavailable")

    def unexpected(*args):
        raise AssertionError("Password work ran after failed throttle")

    monkeypatch.setattr(service, "enforce_login_rate_limit", unavailable)
    monkeypatch.setattr(service, "password_failure", unexpected)
    result = await client.post(
        RECOVERY + "/unlock",
        json=bootstrap(case, seed_owner, command),
        headers={"Idempotency-Key": "throttle:" + str(uuid4())},
    )
    assert result.status_code == 503, result.text
    assert (
        await session.scalar(
            text("SELECT count(*) FROM paid_bill_recovery_sessions WHERE company_id=:c"),
            {"c": case.company_id},
        )
        == 0
    )
