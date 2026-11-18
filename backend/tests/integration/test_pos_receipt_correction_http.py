"""Public no-payout correction: durable commit, exact replay, protected scope."""

# Shared fixtures are imported for pytest injection.
# ruff: noqa: F811
from __future__ import annotations

from uuid import UUID, uuid4

import pytest
from sqlalchemy import select, text

from app.core.db import AsyncSessionLocal
from app.models import PosReceiptCorrection
from tests.integration.test_pos_receipt_correction_preview import (  # noqa: F401
    paid_retained,
    set_role,
)
from tests.integration.test_pos_receipt_corrections import body_for, original_facts

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
PATH = "/api/v1/pos/receipt-corrections/commit"


async def post(client, case, body, key):
    return await client.post(PATH, headers={**case["headers"], "Idempotency-Key": key}, json=body)


async def test_public_commit_then_lost_reply_replay_is_exact_and_broadcast_after_commit(
    client,
    session,
    seed_owner,
    paid_retained,
    monkeypatch,
):
    import app.api.v1.pos.receipt_corrections as api

    body = (await body_for(client, paid_retained)).model_dump(mode="json")
    before = await original_facts(session, paid_retained)
    key = str(uuid4())
    seen = []

    async def broadcast(company, resource):
        async with AsyncSessionLocal() as reader:
            assert (
                await reader.scalar(
                    select(PosReceiptCorrection.id).where(
                        PosReceiptCorrection.order_id == UUID(paid_retained["order_id"])
                    )
                )
                is not None
            )
        seen.append(resource)

    monkeypatch.setattr(api.realtime_manager, "broadcast", broadcast)
    first = await post(client, paid_retained, body, key)
    assert first.status_code == 201, first.text
    second = await post(client, paid_retained, body, key)
    assert second.status_code == 201, second.text
    assert second.json() == first.json()
    assert first.json()["payout_minor"] == 0
    assert set(seen) == {"orders", "receipts", "shifts", "reports", "finance", "audit"}
    assert await original_facts(session, paid_retained) == before
    changed = await post(client, paid_retained, {**body, "reason": "Changed command"}, key)
    assert changed.status_code == 409
    missing = await client.post(PATH, headers=paid_retained["headers"], json=body)
    assert missing.status_code == 422


@pytest.mark.parametrize("role", ["owner", "manager", "store_staff"])
async def test_public_commit_requires_current_protected_role(
    client,
    session,
    seed_owner,
    paid_retained,
    role,
):
    body = (await body_for(client, paid_retained)).model_dump(mode="json")
    paid_retained["headers"] = await set_role(session, seed_owner, role)
    response = await post(client, paid_retained, body, str(uuid4()))
    assert response.status_code == 403, response.text


@pytest.mark.parametrize("sqlstate", ["40P01", "40001"])
async def test_whole_transaction_retries_same_command_and_key_with_fresh_sessions(
    client,
    session,
    seed_owner,
    paid_retained,
    monkeypatch,
    sqlstate,
):
    import app.api.v1.pos.receipt_corrections as api

    original = api.commit_receipt_correction
    body = (await body_for(client, paid_retained)).model_dump(mode="json")
    attempts = []
    key = str(uuid4())

    async def transient(current, tenant, **kwargs):
        attempts.append((id(current), kwargs["idempotency_key"], kwargs["payload"].model_dump()))
        result = await original(current, tenant, **kwargs)
        if len(attempts) < 3:
            await current.execute(
                text(
                    "DO $$ BEGIN RAISE EXCEPTION 'synthetic transient' USING "
                    f"ERRCODE='{sqlstate}'; END $$"  # noqa: S608 - fixed parametrized SQLSTATE allowlist
                )
            )
        return result

    monkeypatch.setattr(api, "commit_receipt_correction", transient)
    result = await post(client, paid_retained, body, key)
    assert result.status_code == 201, result.text
    assert len(attempts) == 3
    assert len({a[0] for a in attempts}) == 3
    assert {a[1] for a in attempts} == {key}
    assert all(a[2] == attempts[0][2] for a in attempts)
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PosReceiptCorrection)
            .where(PosReceiptCorrection.order_id == UUID(paid_retained["order_id"]))
        )
        == 1
    )


async def test_deferred_constraint_failure_cannot_send_success_or_realtime(
    client,
    session,
    seed_owner,
    paid_retained,
    monkeypatch,
):
    import app.api.v1.pos.receipt_corrections as api

    body = (await body_for(client, paid_retained)).model_dump(mode="json")
    seen = []

    async def broadcast(*args):
        seen.append(args)

    monkeypatch.setattr(api.realtime_manager, "broadcast", broadcast)
    # Owned QA database only: a real deferred database failure at commit.
    await session.execute(
        text("""
        CREATE FUNCTION test_pos_correction_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'synthetic deferred failure' USING ERRCODE='23514'; END $$;
    """)
    )
    await session.execute(
        text("""
        CREATE CONSTRAINT TRIGGER test_pos_correction_deferred AFTER INSERT
        ON pos_receipt_corrections DEFERRABLE INITIALLY DEFERRED
        FOR EACH ROW EXECUTE FUNCTION test_pos_correction_commit_failure();
    """)
    )
    await session.commit()
    try:
        result = await post(client, paid_retained, body, str(uuid4()))
        assert result.status_code == 409, result.text
        assert not seen
        assert (
            await session.scalar(
                select(text("count(*)"))
                .select_from(PosReceiptCorrection)
                .where(PosReceiptCorrection.order_id == UUID(paid_retained["order_id"]))
            )
            == 0
        )
    finally:
        await session.rollback()
        await session.execute(
            text("DROP TRIGGER test_pos_correction_deferred ON pos_receipt_corrections")
        )
        await session.execute(text("DROP FUNCTION test_pos_correction_commit_failure()"))
        await session.commit()


async def test_public_http_rejects_client_money_and_stale_source(
    client, session, seed_owner, paid_retained
):
    body = (await body_for(client, paid_retained)).model_dump(mode="json")
    for extra in ("amount_minor", "recorded_by", "accounting_at", "payment_id"):
        response = await post(client, paid_retained, {**body, extra: 1}, str(uuid4()))
        assert response.status_code == 422, response.text
    stale = await post(
        client, paid_retained, {**body, "source_fingerprint": "0" * 64}, str(uuid4())
    )
    assert stale.status_code == 409, stale.text
    unknown = await post(client, paid_retained, {**body, "order_id": str(uuid4())}, str(uuid4()))
    assert unknown.status_code == 404, unknown.text
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PosReceiptCorrection)
            .where(PosReceiptCorrection.order_id == UUID(paid_retained["order_id"]))
        )
        == 0
    )
