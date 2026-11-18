"""Exact-key retirement fences delayed acceptance without changing the paid receipt."""

from __future__ import annotations

from uuid import uuid4

import pytest
from sqlalchemy import text

from tests.integration.test_paid_bill_amendments import BASE, accept, paid, quoted

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]


async def cancel(client, headers, command, key, reason="Stop this unresolved original command"):
    return await client.post(
        BASE + "/actions/cancel",
        json={
            "original_command": command,
            "cancellation_reason": reason,
        },
        headers={**headers, "Idempotency-Key": key},
    )


async def test_cancel_proves_exact_outcome_and_fences_delayed_original(client, session, paid):
    case, headers = paid
    command = await quoted(client, case, headers)
    key = "retire:" + str(uuid4())
    h = {**headers, "Idempotency-Key": key}
    absent = await client.get(BASE + "/actions/outcome", headers=h)
    assert absent.status_code == 200, absent.text
    assert absent.json()["outcome"] == "unconfirmed"
    first = await cancel(client, headers, command, key)
    assert first.status_code == 200, first.text
    result = first.json()
    assert result["outcome"] == "cancelled"
    assert result["reference"]["original_command"] == command
    assert result["cancelled"]["financial_effect"] is False
    assert result["cancelled"]["stock_effect"] is False
    replay = await cancel(client, headers, command, key)
    assert replay.status_code == 200, replay.text
    assert replay.json() == result
    found = await client.get(BASE + "/actions/outcome", headers=h)
    assert found.status_code == 200 and found.json() == result
    late = await client.post(BASE + "/commit", json=command, headers=h)
    assert late.status_code == 409, late.text
    row = (
        await session.execute(
            text("""SELECT
      (SELECT count(*) FROM paid_bill_action_decisions WHERE order_id=:id),
      (SELECT count(*) FROM paid_bill_amendment_intents WHERE order_id=:id),
      (SELECT count(*) FROM pos_refund_requests WHERE order_id=:id),
      (SELECT count(*) FROM refunds WHERE order_id=:id),
      (SELECT count(*) FROM audit_log WHERE entity_type='PaidBillActionDecision'
       AND entity_id=:decision)"""),
            {"id": case.order_id, "decision": result["cancelled"]["id"]},
        )
    ).one()
    assert tuple(row) == (1, 0, 0, 0, 1)


async def test_accepted_winner_preserves_original_task_and_legacy_reader(client, paid):
    case, headers = paid
    command, key, accepted = await accept(client, case, headers)
    result = await cancel(client, headers, command, key)
    assert result.status_code == 200, result.text
    assert result.json()["outcome"] == "accepted"
    assert result.json()["accepted"] == accepted
    old = await client.get(BASE + "/actions/by-key", headers={**headers, "Idempotency-Key": key})
    assert old.status_code == 200 and old.json()["accepted"] == accepted
    assert set(old.json()) == {"accepted", "current"}


@pytest.mark.parametrize("change", ["reason", "body"])
async def test_cancel_key_never_retargets_body_or_reason(client, paid, change):
    case, headers = paid
    command = await quoted(client, case, headers)
    key = "retire:" + str(uuid4())
    assert (await cancel(client, headers, command, key)).status_code == 200
    changed = dict(command)
    if change == "body":
        changed["target_total_minor"] -= 1
    result = await cancel(
        client,
        headers,
        changed,
        key,
        reason="Different retirement reason"
        if change == "reason"
        else "Stop this unresolved original command",
    )
    assert result.status_code == 409, result.text


async def test_old_v6_order_holder_can_be_retired_without_parent_wait(client, paid, monkeypatch):
    import asyncio

    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "old-order-holder:" + str(uuid4())
    locked, release = asyncio.Event(), asyncio.Event()
    original = service.scoped_order

    async def hold(*args, **kwargs):
        order = await original(*args, **kwargs)
        if kwargs.get("lock"):
            locked.set()
            await asyncio.wait_for(release.wait(), 4)
        return order

    monkeypatch.setattr(service, "scoped_order", hold)
    old = asyncio.create_task(
        client.post(BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key})
    )
    try:
        await asyncio.wait_for(locked.wait(), 2)
        # Must finish while old V6 still owns Order FOR UPDATE. A parent FK or lock deadlocks here.
        retired = await asyncio.wait_for(cancel(client, headers, command, key), 1.5)
        assert retired.status_code == 200, retired.text
        assert retired.json()["outcome"] == "cancelled"
        assert not old.done()
    finally:
        release.set()
    late = await asyncio.wait_for(old, 3)
    assert late.status_code == 409, late.text


@pytest.mark.parametrize("roll_back", [False, True])
async def test_uncommitted_acceptance_unique_wait_resolves_in_fresh_transaction(
    client, paid, monkeypatch, roll_back
):
    import asyncio

    from app.core.db import AsyncSessionLocal
    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "acceptance-winner:" + str(uuid4())
    inserted, release = asyncio.Event(), asyncio.Event()
    original = service.enqueue_google_sheets_event_if_enabled

    async def hold(*args, **kwargs):
        inserted.set()
        await asyncio.wait_for(release.wait(), 4)
        if roll_back:
            raise RuntimeError("injected acceptance rollback")
        return await original(*args, **kwargs)

    monkeypatch.setattr(service, "enqueue_google_sheets_event_if_enabled", hold)
    old = asyncio.create_task(
        client.post(BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key})
    )
    pending = None
    try:
        await asyncio.wait_for(inserted.wait(), 2)
        pending = asyncio.create_task(cancel(client, headers, command, key))
        waited = False
        async with AsyncSessionLocal() as observer:
            for _ in range(80):
                await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
                waited = bool(
                    await observer.scalar(
                        text("""SELECT count(*) FROM pg_stat_activity
                  WHERE datname=current_database() AND wait_event_type='Lock'
                  AND query ILIKE '%INSERT INTO paid_bill_action_decisions%'""")
                    )
                )
                if waited:
                    break
                await asyncio.sleep(0.01)
        assert waited and not pending.done(), (
            "cancellation did not wait on unique-index arbitration"
        )
    finally:
        release.set()
    old_result = (await asyncio.gather(old, return_exceptions=True))[0]
    result = await asyncio.wait_for(pending, 3)
    assert result.status_code == 200, result.text
    assert result.json()["outcome"] == ("cancelled" if roll_back else "accepted")
    if roll_back:
        assert isinstance(old_result, RuntimeError)
    else:
        assert old_result.status_code == 201
        assert result.json()["accepted"] == old_result.json()


async def test_cancellation_audit_failure_rolls_back_tombstone(client, paid, monkeypatch):
    from sqlalchemy import select

    from app.core.db import AsyncSessionLocal
    from app.models import PaidBillActionDecision
    from app.services.audit import recorder

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "cancel-audit-failure:" + str(uuid4())
    original = recorder._serialize

    def fail(obj):
        if isinstance(obj, PaidBillActionDecision):
            raise RuntimeError("injected cancellation audit failure")
        return original(obj)

    monkeypatch.setattr(recorder, "_serialize", fail)
    with pytest.raises(RuntimeError, match="injected cancellation audit failure"):
        await cancel(client, headers, command, key)
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(
                select(PaidBillActionDecision.id).where(
                    PaidBillActionDecision.company_id == case.company_id,
                    PaidBillActionDecision.action_key == key,
                )
            )
            is None
        )


@pytest.mark.parametrize("stage", ["cancel", "outcome_cancelled", "outcome_accepted"])
async def test_family_logout_before_actor_lock_denies_retirement_and_disclosure(
    client, paid, monkeypatch, stage
):
    import asyncio

    from app.core.db import AsyncSessionLocal
    from app.services.auth import refresh_sessions
    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "decision-logout:" + str(uuid4())
    if stage == "outcome_cancelled":
        assert (await cancel(client, headers, command, key)).status_code == 200
    elif stage == "outcome_accepted":
        response = await client.post(
            BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
        )
        assert response.status_code == 201, response.text
    locked, release, actor = asyncio.Event(), asyncio.Event(), asyncio.Event()
    original_user, original_actor = refresh_sessions._locked_user, service.lock_actor

    async def hold(*args, **kwargs):
        user = await original_user(*args, **kwargs)
        locked.set()
        await asyncio.wait_for(release.wait(), 4)
        return user

    async def observed(*args, **kwargs):
        actor.set()
        return await original_actor(*args, **kwargs)

    monkeypatch.setattr(refresh_sessions, "_locked_user", hold)
    monkeypatch.setattr(service, "lock_actor", observed)
    logout = asyncio.create_task(client.post("/api/v1/auth/logout", headers=headers))
    pending = None
    try:
        await asyncio.wait_for(locked.wait(), 2)
        pending = asyncio.create_task(
            cancel(client, headers, command, key)
            if stage == "cancel"
            else client.get(BASE + "/actions/outcome", headers={**headers, "Idempotency-Key": key})
        )
        await asyncio.wait_for(actor.wait(), 2)
        waited = False
        async with AsyncSessionLocal() as observer:
            for _ in range(80):
                await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
                waited = bool(
                    await observer.scalar(
                        text("""SELECT count(*) FROM pg_stat_activity
                  WHERE datname=current_database() AND wait_event_type='Lock'
                  AND query ILIKE '%users%' AND query ILIKE '%FOR SHARE%'""")
                    )
                )
                if waited:
                    break
                await asyncio.sleep(0.01)
        assert waited and not pending.done()
    finally:
        release.set()
    assert (await asyncio.wait_for(logout, 3)).status_code == 200
    denied = await asyncio.wait_for(pending, 3)
    assert denied.status_code == 401, denied.text


@pytest.mark.parametrize("grant", ["missing", "expired", "wrong_purpose"])
async def test_outcome_and_cancel_require_live_specific_confirmation(client, paid, grant):
    from datetime import UTC, datetime

    import jwt

    from app.core.config import get_settings

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "grant:" + str(uuid4())
    checked = dict(headers)
    if grant == "missing":
        checked.pop("X-Owner-Confirmation")
    else:
        claims = jwt.decode(headers["X-Owner-Confirmation"], options={"verify_signature": False})
        if grant == "expired":
            claims["exp"] = int(datetime.now(UTC).timestamp()) - 1
        else:
            claims["scope"] = "receipt_stock"
        checked["X-Owner-Confirmation"] = jwt.encode(
            claims, get_settings().jwt_secret, algorithm=get_settings().jwt_algorithm
        )
    first = await cancel(client, checked, command, key)
    assert first.status_code == 401, first.text
    read = await client.get(BASE + "/actions/outcome", headers={**checked, "Idempotency-Key": key})
    assert read.status_code == 401, read.text


async def test_stale_quote_closed_settlement_retirement_changes_no_source(client, session, paid):
    from datetime import UTC, datetime

    from app.models import Shift

    case, headers = paid
    command = await quoted(client, case, headers)
    shift = await session.get(Shift, case.shift_id)
    shift.status = "closed"
    shift.closed_at = datetime.now(UTC)
    shift.closed_by = case.owner_id
    await session.commit()
    before = (
        await session.execute(
            text(
                "SELECT to_jsonb(o),(SELECT to_jsonb(s) FROM shifts s WHERE id=:s) FROM orders o WHERE o.id=:o"
            ),
            {"o": case.order_id, "s": case.shift_id},
        )
    ).one()
    await session.rollback()
    command["source_fingerprint"] = "0" * 64
    command["source_revision"] = 0
    key = "stale-retirement:" + str(uuid4())
    result = await cancel(client, headers, command, key)
    assert result.status_code == 200, result.text
    assert result.json()["outcome"] == "cancelled"
    after = (
        await session.execute(
            text(
                "SELECT to_jsonb(o),(SELECT to_jsonb(s) FROM shifts s WHERE id=:s) FROM orders o WHERE o.id=:o"
            ),
            {"o": case.order_id, "s": case.shift_id},
        )
    ).one()
    assert tuple(before) == tuple(after)
    # Closed settlement remains ineligible to pay; retirement does not reopen it.
    delayed = await client.post(
        BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
    )
    assert delayed.status_code == 422, delayed.text
    assert "Shift is closed" in delayed.text


COPY_CANCEL = """INSERT INTO paid_bill_action_decisions
 (id,company_id,recorded_by,order_id,branch_id,terminal_id,action_key,request_path,
 request_hash,request_body,outcome,accepted_intent_id,cancellation_reason,response_body,decided_at)
 SELECT :new_id,{company},{actor},{order},{branch},{terminal},:new_key,{path},
 {digest},{body},{outcome},{intent},cancellation_reason,{response},statement_timestamp()
 FROM paid_bill_action_decisions WHERE id=:template"""


def raw_cancel_sql(**changes):
    fields = dict(
        company="company_id",
        actor="recorded_by",
        order="order_id",
        branch="branch_id",
        terminal="terminal_id",
        path="request_path",
        digest="request_hash",
        body="request_body",
        outcome="'cancelled'",
        intent="NULL",
        response="dcompany_paid_action_cancel_response(:new_id,statement_timestamp(),cancellation_reason)",
    )
    fields.update(changes)
    return text(COPY_CANCEL.format(**fields))


@pytest.mark.parametrize(
    ("change", "expected"),
    [
        ({"company": "gen_random_uuid()"}, "current protected Owner"),
        ({"actor": "gen_random_uuid()"}, "current protected Owner"),
        ({"order": "gen_random_uuid()"}, "command or hash is invalid"),
        ({"branch": "gen_random_uuid()"}, "exact original issued receipt scope"),
        ({"terminal": "gen_random_uuid()"}, "exact original issued receipt scope"),
        ({"path": "'/wrong/path'"}, "command or hash is invalid"),
        ({"digest": "repeat('0',64)"}, "command or hash is invalid"),
        (
            {"body": "request_body||jsonb_build_object('protocol',true)"},
            "command or hash is invalid",
        ),
        ({"response": "'{}'::jsonb"}, "response or decision time is invalid"),
        ({"outcome": "'accepted'", "intent": "gen_random_uuid()"}, "exactly derive"),
        ({}, "exact atomic audit record"),
    ],
)
async def test_raw_decision_guards_fail_for_intended_reason(client, paid, change, expected):
    from sqlalchemy.exc import DBAPIError

    from app.core.db import AsyncSessionLocal

    case, headers = paid
    command = await quoted(client, case, headers)
    made = await cancel(client, headers, command, "template:" + str(uuid4()))
    assert made.status_code == 200, made.text
    async with AsyncSessionLocal() as raw:
        with pytest.raises(DBAPIError) as denied:
            await raw.execute(
                raw_cancel_sql(**change),
                {
                    "new_id": uuid4(),
                    "new_key": "raw:" + str(uuid4()),
                    "template": made.json()["cancelled"]["id"],
                },
            )
            await raw.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))
        assert expected in str(denied.value.orig)
        await raw.rollback()


@pytest.mark.parametrize(
    "operation",
    [
        "UPDATE paid_bill_action_decisions SET action_key=action_key",
        "DELETE FROM paid_bill_action_decisions",
        "TRUNCATE paid_bill_action_decisions",
    ],
)
async def test_decisions_are_permanent(client, paid, operation):
    from sqlalchemy.exc import DBAPIError

    from app.core.db import AsyncSessionLocal

    case, headers = paid
    command = await quoted(client, case, headers)
    made = await cancel(client, headers, command, "immutable:" + str(uuid4()))
    assert made.status_code == 200, made.text
    async with AsyncSessionLocal() as raw:
        with pytest.raises(DBAPIError, match="append.only"):
            await raw.execute(text(operation))
        await raw.rollback()
        assert (
            await raw.scalar(
                text("SELECT count(*) FROM paid_bill_action_decisions WHERE id=:id"),
                {"id": made.json()["cancelled"]["id"]},
            )
            == 1
        )


@pytest.mark.parametrize("isolation", ["READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"])
async def test_unique_index_fences_opposite_outcome_from_preexisting_snapshot(
    client, paid, isolation
):
    from sqlalchemy.exc import DBAPIError

    from app.core.db import AsyncSessionLocal

    case, headers = paid
    command = await quoted(client, case, headers)
    template = await cancel(client, headers, command, "snapshot-template:" + str(uuid4()))
    assert template.status_code == 200, template.text
    key = "snapshot-winner:" + str(uuid4())
    async with AsyncSessionLocal() as raw:
        await raw.execute(text("SET TRANSACTION ISOLATION LEVEL " + isolation))
        assert (
            await raw.scalar(
                text(
                    "SELECT count(*) FROM paid_bill_action_decisions WHERE company_id=:c AND action_key=:k"
                ),
                {"c": case.company_id, "k": key},
            )
            == 0
        )
        accepted = await client.post(
            BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
        )
        assert accepted.status_code == 201, accepted.text
        with pytest.raises(DBAPIError) as rejected:
            await raw.execute(
                raw_cancel_sql(),
                {"new_id": uuid4(), "new_key": key, "template": template.json()["cancelled"]["id"]},
            )
        assert getattr(rejected.value.orig, "sqlstate", None) in {"23505", "40001"}
        await raw.rollback()
    outcome = await client.get(
        BASE + "/actions/outcome", headers={**headers, "Idempotency-Key": key}
    )
    assert outcome.status_code == 200 and outcome.json()["outcome"] == "accepted"


async def test_confirmation_expiring_during_unique_wait_discloses_no_winner(
    client, paid, monkeypatch
):
    import asyncio
    from datetime import UTC, datetime

    import jwt

    from app.core.config import get_settings
    from app.core.db import AsyncSessionLocal
    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "expiry-index:" + str(uuid4())
    claims = jwt.decode(headers["X-Owner-Confirmation"], options={"verify_signature": False})
    claims["exp"] = int(datetime.now(UTC).timestamp()) + 2
    short = jwt.encode(claims, get_settings().jwt_secret, algorithm=get_settings().jwt_algorithm)
    inserted, release = asyncio.Event(), asyncio.Event()
    original = service.enqueue_google_sheets_event_if_enabled

    async def hold(*args, **kwargs):
        inserted.set()
        await asyncio.wait_for(release.wait(), 5)
        return await original(*args, **kwargs)

    monkeypatch.setattr(service, "enqueue_google_sheets_event_if_enabled", hold)
    accepted = asyncio.create_task(
        client.post(BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key})
    )
    pending = None
    try:
        await asyncio.wait_for(inserted.wait(), 2)
        pending = asyncio.create_task(
            cancel(client, {**headers, "X-Owner-Confirmation": short}, command, key)
        )
        waited = False
        async with AsyncSessionLocal() as observer:
            for _ in range(80):
                await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
                waited = bool(
                    await observer.scalar(
                        text("""SELECT count(*) FROM pg_stat_activity
                  WHERE datname=current_database() AND wait_event_type='Lock'
                  AND query ILIKE '%INSERT INTO paid_bill_action_decisions%'""")
                    )
                )
                if waited:
                    break
                await asyncio.sleep(0.01)
        assert waited and not pending.done()
        await asyncio.sleep(max(0, claims["exp"] - datetime.now(UTC).timestamp()) + 0.02)
    finally:
        release.set()
    assert (await asyncio.wait_for(accepted, 3)).status_code == 201
    denied = await asyncio.wait_for(pending, 3)
    assert denied.status_code == 401, denied.text
    fresh = await client.get(BASE + "/actions/outcome", headers={**headers, "Idempotency-Key": key})
    assert fresh.status_code == 200 and fresh.json()["outcome"] == "accepted"


@pytest.mark.parametrize("isolation", ["REPEATABLE READ", "SERIALIZABLE"])
async def test_new_decision_service_rejects_stale_authorization_isolation(isolation):
    from app.core.db import AsyncSessionLocal
    from app.core.errors import ConflictError
    from app.services.pos.paid_bill_action_decisions import outcome

    async with AsyncSessionLocal() as raw:
        await raw.execute(text("SET TRANSACTION ISOLATION LEVEL " + isolation))

        async def never():
            raise AssertionError("must reject isolation before authority disclosure")

        with pytest.raises(ConflictError, match="read-committed"):
            await outcome(raw, None, key="isolation-test", confirmation=None, fresh=never)


async def test_cancellation_audit_redacts_private_body_and_reason(client, session, paid):
    import json

    case, headers = paid
    command = await quoted(client, case, headers)
    command["reason"] = "PRIVATE ORIGINAL REVIEW REASON"
    result = await cancel(
        client, headers, command, "private-audit:" + str(uuid4()), reason="PRIVATE CANCEL REASON"
    )
    assert result.status_code == 200, result.text
    audit = await session.scalar(
        text(
            "SELECT to_jsonb(a) FROM audit_log a WHERE entity_type='PaidBillActionDecision' AND entity_id=:id"
        ),
        {"id": result.json()["cancelled"]["id"]},
    )
    encoded = json.dumps(audit)
    assert "PRIVATE ORIGINAL" not in encoded and "PRIVATE CANCEL" not in encoded
    assert headers["X-Owner-Confirmation"] not in encoded
    assert "cancelled" in encoded and str(case.order_id) in encoded


@pytest.mark.parametrize("isolation", ["READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"])
async def test_cancel_winner_fences_legacy_acceptance_from_preexisting_snapshot(
    client, paid, isolation
):
    from sqlalchemy.exc import DBAPIError

    from app.core.db import AsyncSessionLocal
    from app.core.tenant import TenantContext
    from app.schemas.paid_bill_amendment import AmendmentCommitRequest
    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "cancel-snapshot-winner:" + str(uuid4())
    # This is an index/legacy-writer proof, NOT a claim of fresh API auth under RR/serializable.
    tenant = TenantContext(
        user_id=case.owner_id,
        company_id=case.company_id,
        branch_id=case.branch_id,
        terminal_id=case.terminal_id,
        roles=("super_owner",),
        protected_access=True,
        audit_access=True,
    )

    async def unchanged_authority():
        pass

    async with AsyncSessionLocal() as raw:
        await raw.execute(text("SET TRANSACTION ISOLATION LEVEL " + isolation))
        assert (
            await raw.scalar(
                text("SELECT count(*) FROM paid_bill_amendment_intents WHERE order_id=:id"),
                {"id": case.order_id},
            )
            == 0
        )
        retired = await cancel(client, headers, command, key)
        assert retired.status_code == 200, retired.text
        with pytest.raises(DBAPIError) as rejected:
            await service.commit(
                raw,
                tenant,
                AmendmentCommitRequest.model_validate(command),
                action_key=key,
                confirmation=headers["X-Owner-Confirmation"],
                reauthorize=unchanged_authority,
            )
        assert getattr(rejected.value.orig, "sqlstate", None) in {"23505", "40001"}
        await raw.rollback()
    found = await client.get(BASE + "/actions/outcome", headers={**headers, "Idempotency-Key": key})
    assert found.status_code == 200 and found.json()["outcome"] == "cancelled"


@pytest.mark.parametrize("scope", ["branch", "terminal", "order", "actor", "company"])
async def test_protected_outcome_is_not_disclosed_or_retargeted_to_foreign_scope(
    client, session, paid, seed_owner, scope
):
    from sqlalchemy import select

    from app.core.security import hash_password
    from app.models import Branch, Role, Terminal, User, UserRole

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "foreign-scope:" + str(uuid4())
    assert (await cancel(client, headers, command, key)).status_code == 200
    foreign = dict(headers)
    if scope in {"branch", "terminal"}:
        branch_id = case.branch_id
        if scope == "branch":
            b = Branch(
                id=uuid4(),
                company_id=case.company_id,
                name="Other branch",
                invoice_series_code="ZZ",
            )
            session.add(b)
            await session.flush()
            branch_id = b.id
        if scope == "terminal":
            old_terminal = await session.get(Terminal, case.terminal_id)
            old_terminal.is_active = False
            await session.flush()
        terminal = Terminal(
            id=uuid4(),
            branch_id=branch_id,
            name="Other terminal",
            device_id="other-" + str(uuid4()),
        )
        session.add(terminal)
        await session.commit()
        foreign["X-Terminal-Id"] = str(terminal.id)
        if scope == "branch":
            grant = await client.post(
                BASE + "/unlock",
                json={"branch_id": str(branch_id), "password": seed_owner["password"]},
                headers=foreign,
            )
            assert grant.status_code == 403, grant.text
            found = await client.get(
                BASE + "/actions/outcome", headers={**foreign, "Idempotency-Key": key}
            )
            assert found.status_code == 403, found.text
            changed = await cancel(client, foreign, command, key)
            assert changed.status_code == 403, changed.text
            return
    elif scope == "order":
        changed = {**command, "order_id": str(uuid4())}
        result = await cancel(client, foreign, changed, key)
        assert result.status_code == 404, result.text
        return
    elif scope == "actor":
        role = await session.scalar(
            select(Role).where(Role.company_id == case.company_id, Role.code == "super_owner")
        )
        actor = User(
            id=uuid4(),
            company_id=case.company_id,
            email="decision-foreign-" + uuid4().hex + "@test.local",
            name="Other Owner",
            password_hash=hash_password("other-owner-password"),
            status="active",
        )
        session.add(actor)
        await session.flush()
        session.add(
            UserRole(id=uuid4(), user_id=actor.id, role_id=role.id, granted_by=case.owner_id)
        )
        await session.commit()
        login = await client.post(
            "/api/v1/auth/login", json={"email": actor.email, "password": "other-owner-password"}
        )
        assert login.status_code == 200, login.text
        foreign["Authorization"] = "Bearer " + login.json()["access_token"]
        grant = await client.post(
            BASE + "/unlock",
            json={"branch_id": str(case.branch_id), "password": "other-owner-password"},
            headers=foreign,
        )
        assert grant.status_code == 200, grant.text
        foreign["X-Owner-Confirmation"] = grant.json()["token"]
    else:
        # A separately seeded tenant exercises the normal HTTP login/terminal authority boundary.
        from app.models import Company

        company = Company(id=uuid4(), name="Foreign decision tenant")
        branch = Branch(id=uuid4(), company_id=company.id, name="Foreign", invoice_series_code="FF")
        actor = User(
            id=uuid4(),
            company_id=company.id,
            email="decision-company-" + uuid4().hex + "@test.local",
            name="Other company Owner",
            password_hash=hash_password("other-owner-password"),
            status="active",
        )
        role = Role(
            id=uuid4(), company_id=company.id, code="super_owner", name="Owner", permissions=[]
        )
        session.add(company)
        await session.flush()
        session.add_all([branch, actor, role])
        await session.flush()
        terminal = Terminal(
            id=uuid4(), branch_id=branch.id, name="Foreign", device_id="foreign-" + str(uuid4())
        )
        session.add_all([terminal, UserRole(id=uuid4(), user_id=actor.id, role_id=role.id)])
        await session.commit()
        login = await client.post(
            "/api/v1/auth/login", json={"email": actor.email, "password": "other-owner-password"}
        )
        assert login.status_code == 200, login.text
        foreign = {
            "Authorization": "Bearer " + login.json()["access_token"],
            "X-Terminal-Id": str(terminal.id),
        }
        grant = await client.post(
            BASE + "/unlock",
            json={"branch_id": str(branch.id), "password": "other-owner-password"},
            headers=foreign,
        )
        assert grant.status_code == 200, grant.text
        foreign["X-Owner-Confirmation"] = grant.json()["token"]
    found = await client.get(BASE + "/actions/outcome", headers={**foreign, "Idempotency-Key": key})
    if scope == "company":
        assert found.status_code == 200 and found.json()["outcome"] == "unconfirmed"
    else:
        assert found.status_code == 404, found.text
    changed = await cancel(client, foreign, command, key)
    assert changed.status_code == 404, changed.text


async def test_authenticated_terminal_advertises_both_distinct_protocols(client, paid):
    _, headers = paid
    response = await client.get("/api/v1/settings/terminals", headers=headers)
    assert response.status_code == 200, response.text
    assert response.json()
    assert all(
        row["paid_bill_amendment_protocol"] == 1 and row["paid_bill_action_outcome_protocol"] == 1
        for row in response.json()
    )


@pytest.mark.parametrize("winner", ["accepted", "cancelled"])
async def test_expired_grant_recovery_renews_authority_without_rekeying(
    client, paid, seed_owner, winner
):
    from datetime import UTC, datetime

    import jwt

    from app.core.config import get_settings

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "restart-grant:" + str(uuid4())
    if winner == "accepted":
        first = await client.post(
            BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
        )
        assert first.status_code == 201, first.text
    else:
        first = await cancel(client, headers, command, key)
        assert first.status_code == 200, first.text
    original = await client.get(
        BASE + "/actions/outcome", headers={**headers, "Idempotency-Key": key}
    )
    claims = jwt.decode(headers["X-Owner-Confirmation"], options={"verify_signature": False})
    claims["exp"] = int(datetime.now(UTC).timestamp()) - 1
    expired = jwt.encode(claims, get_settings().jwt_secret, algorithm=get_settings().jwt_algorithm)
    denied = await client.get(
        BASE + "/actions/outcome",
        headers={**headers, "Idempotency-Key": key, "X-Owner-Confirmation": expired},
    )
    assert denied.status_code == 401, denied.text
    unlock = await client.post(
        BASE + "/unlock",
        json={"branch_id": str(case.branch_id), "password": seed_owner["password"]},
        headers=headers,
    )
    assert unlock.status_code == 200, unlock.text
    current = {**headers, "X-Owner-Confirmation": unlock.json()["token"]}
    recovered = await client.get(
        BASE + "/actions/outcome", headers={**current, "Idempotency-Key": key}
    )
    assert recovered.status_code == 200 and recovered.json() == original.json()
    replay = await cancel(client, current, command, key)
    assert replay.status_code == 200 and replay.json() == original.json()


async def test_retained_cancellation_receipt_survives_database_timezone_aba(
    client, paid, monkeypatch
):
    from app.services.pos import paid_bill_action_decisions as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "timezone-aba:" + str(uuid4())
    original = service.require_read_committed
    zone = ["UTC"]

    async def scoped_zone(session):
        await session.execute(text("SELECT set_config('TimeZone',:zone,true)"), {"zone": zone[0]})
        await original(session)

    monkeypatch.setattr(service, "require_read_committed", scoped_zone)
    first = await cancel(client, headers, command, key)
    assert first.status_code == 200, first.text
    zone[0] = "Asia/Kolkata"
    different = await cancel(client, headers, command, key)
    assert different.status_code == 200 and different.json() == first.json()
    zone[0] = "UTC"
    restored = await cancel(client, headers, command, key)
    assert restored.status_code == 200 and restored.json() == first.json()
    delayed = await client.post(
        BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
    )
    assert delayed.status_code == 409, delayed.text


@pytest.mark.parametrize("failure", ["audit", "outbox"])
async def test_acceptance_failure_rolls_back_derived_decision_with_all_business_rows(
    client, paid, monkeypatch, failure
):
    from app.core.db import AsyncSessionLocal
    from app.models import PaidBillAmendmentIntent
    from app.services.audit import recorder
    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "acceptance-atomic:" + str(uuid4())
    if failure == "audit":
        original = recorder._serialize

        def fail(obj):
            if isinstance(obj, PaidBillAmendmentIntent):
                raise RuntimeError("injected acceptance audit failure")
            return original(obj)

        monkeypatch.setattr(recorder, "_serialize", fail)
    else:

        async def fail(*args, **kwargs):
            raise RuntimeError("injected acceptance outbox failure")

        monkeypatch.setattr(service, "enqueue_google_sheets_event_if_enabled", fail)
    with pytest.raises(RuntimeError, match="injected acceptance"):
        await client.post(
            BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
        )
    async with AsyncSessionLocal() as verify:
        for table in (
            "paid_bill_action_decisions",
            "paid_bill_amendment_intents",
            "pos_refund_requests",
            "refunds",
        ):
            assert (
                await verify.scalar(
                    text("SELECT count(*) FROM " + table + " WHERE order_id=:o"),
                    {"o": case.order_id},
                )
                == 0
            )


async def test_logout_queued_during_index_wait_revokes_fresh_transaction_retry(
    client, paid, monkeypatch
):
    import asyncio

    from app.core.db import AsyncSessionLocal
    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "index-logout:" + str(uuid4())
    inserted, release = asyncio.Event(), asyncio.Event()
    original = service.enqueue_google_sheets_event_if_enabled

    async def hold(*args, **kwargs):
        inserted.set()
        await asyncio.wait_for(release.wait(), 5)
        return await original(*args, **kwargs)

    monkeypatch.setattr(service, "enqueue_google_sheets_event_if_enabled", hold)
    accepted = asyncio.create_task(
        client.post(BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key})
    )
    pending = logout = None

    async def wait_for_query(fragment):
        async with AsyncSessionLocal() as observer:
            for _ in range(100):
                await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
                if await observer.scalar(
                    text("""SELECT count(*) FROM pg_stat_activity
                  WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE :fragment"""),
                    {"fragment": fragment},
                ):
                    return
                await asyncio.sleep(0.01)
        raise AssertionError("expected actual database lock wait was not observed")

    try:
        await asyncio.wait_for(inserted.wait(), 2)
        pending = asyncio.create_task(cancel(client, headers, command, key))
        await wait_for_query("%INSERT INTO paid_bill_action_decisions%")
        logout = asyncio.create_task(client.post("/api/v1/auth/logout", headers=headers))
        await wait_for_query("%users%FOR%UPDATE%")
        assert not logout.done() and not pending.done()
    finally:
        release.set()
    assert (await asyncio.wait_for(accepted, 3)).status_code == 201
    assert (await asyncio.wait_for(logout, 3)).status_code == 200
    denied = await asyncio.wait_for(pending, 3)
    assert denied.status_code == 401, denied.text
