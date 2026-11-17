"""Immutable old HTTP producer versus retirement, without business-parent lock inversion."""

from __future__ import annotations

import asyncio
import json
import os
import sys
import tarfile
from datetime import UTC, datetime
from io import BytesIO
from pathlib import Path
from uuid import uuid4

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.db import AsyncSessionLocal
from app.models import GRN, GrnActionDecision, GrnCompletionProof, GrnRecoverySession
from app.services.inventory.grn_commands import digest
from tests.integration.test_grn_command_recovery import (
    BASE,
    cancel,
    counts,
    unlock,
)
from tests.integration.test_grn_command_recovery import (
    receipt as receipt_fixture,
)

receipt = receipt_fixture
pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
OLD_PRODUCER = "053b5c0dea7c691892769ae60245b8fbca3d585f"
CHILD = """
import asyncio,contextlib,io,json,sys
phase="starting"
from httpx import ASGITransport,AsyncClient
from sqlalchemy import event,text
from app.main import create_app
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.core.db import async_engine
from app.services.audit.recorder import install_audit_listeners
async def run():
 global phase
 data=json.load(sys.stdin)
 isolation=data['isolation']
 assert isolation in ('READ COMMITTED','REPEATABLE READ','SERIALIZABLE')
 @event.listens_for(async_engine.sync_engine,"connect")
 def isolation_mode(connection,record):
  cursor=connection.cursor()
  cursor.execute("SET default_transaction_isolation TO '"+isolation+"'")
  cursor.close()
 configure_logging(get_settings().model_copy(update={"log_format":"json"}))
 async with async_engine.connect() as c:
  await c.execute(text("SET application_name='grn_immutable_old_writer'"));await c.commit()
 install_audit_listeners()
 with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
  transport=ASGITransport(app=create_app(),raise_app_exceptions=False)
  async with AsyncClient(transport=transport,base_url='http://test') as client:
   phase="request"
   result=await client.post('/api/v1/inventory/grn',
    content=data['raw'].encode(),headers=data['headers'])
  phase="response"
  output={'status':result.status_code}
  if result.status_code==201:output['receipt']=result.json()
 phase="dispose"
 await async_engine.dispose()
 phase="done"
 print(json.dumps(output),flush=True)
try:asyncio.run(asyncio.wait_for(run(),6))
except Exception:print(json.dumps({"status":0,"phase":phase}),flush=True)
"""


@pytest.fixture(scope="module")
async def old_backend(tmp_path_factory):
    root = Path(__file__).resolve().parents[3]
    output = tmp_path_factory.mktemp("grn-old-producer")
    process = await asyncio.create_subprocess_exec(
        "/usr/bin/git",
        "archive",
        OLD_PRODUCER,
        "backend",
        cwd=root,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    archive, _ = await process.communicate()
    assert process.returncode == 0
    with tarfile.open(fileobj=BytesIO(archive), mode="r:") as bundle:
        bundle.extractall(output, filter="data")
    return output / "backend"


async def old_request(old_backend, case, isolation="READ COMMITTED"):
    env = {
        **os.environ,
        "PYTHONPATH": str(old_backend) + os.pathsep + os.environ.get("PYTHONPATH", ""),
    }
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-c",
        CHILD,
        cwd=old_backend,
        env=env,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    payload = json.dumps(
        {
            "raw": case["raw"],
            "isolation": isolation,
            "headers": {
                **case["headers"],
                "Idempotency-Key": case["key"],
                "Content-Type": "application/json",
            },
        }
    ).encode()
    result = asyncio.create_task(process.communicate(payload))
    return process, result


async def wait_for_lock(application_name, process=None, pending=None):
    async with AsyncSessionLocal() as db:
        for _ in range(1000):
            await db.execute(text("SELECT pg_stat_clear_snapshot()"))
            waiting = await db.scalar(
                text("""SELECT count(*) FROM pg_stat_activity
              WHERE datname=current_database() AND application_name=:application_name
                AND wait_event_type='Lock'"""),
                {"application_name": application_name},
            )
            if waiting:
                return
            if pending is not None and pending.done():
                reply = await child_reply(process, pending)
                pytest.fail(f"Old producer completed before lock wait: status={reply['status']}")
            await asyncio.sleep(0.01)
    pytest.fail("Old producer did not reach the actual database lock wait")


async def child_reply(process, task):
    try:
        stdout, _ = await asyncio.wait_for(task, 10)
    finally:
        if process.returncode is None:
            process.terminate()
            await process.wait()
    assert process.returncode == 0
    # Old diagnostic output is discarded in RAM; never echo raw command errors.
    return json.loads(stdout)


async def economic_counts(db, case):
    return await db.scalar(
        text("""SELECT jsonb_build_object(
      'purchase_orders',(SELECT count(*) FROM purchase_orders WHERE company_id=:company),
      'batches',(SELECT count(*) FROM batches WHERE ingredient_id=:ingredient),
      'movements',(SELECT count(*) FROM stock_movements WHERE branch_id=:branch),
      'generic_receipt',(SELECT count(*) FROM idempotency_keys WHERE key=:key),
      'accepted_audits',(SELECT count(*) FROM audit_log
        WHERE company_id=:company AND action='grn_command_accepted'),
      'mirror_rows',(SELECT count(*) FROM google_sheets_deliveries WHERE company_id=:company))"""),
        {
            "company": case["company_id"],
            "ingredient": case["ingredient_id"],
            "branch": case["branch_id"],
            "key": case["key"],
        },
    )


@pytest.mark.parametrize("isolation", ["READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"])
async def test_cancel_wins_while_actual_old_http_writer_holds_generic_key_and_waits_branch(
    client, session, receipt, old_backend, isolation
):
    case = receipt
    headers = await unlock(client, case)
    baseline = await counts(session, case)
    economic_before = await economic_counts(session, case)
    async with AsyncSessionLocal() as branch_lock:
        await branch_lock.execute(
            text("SELECT id FROM branches WHERE id=:id FOR UPDATE"), {"id": case["branch_id"]}
        )
        process, pending = await old_request(old_backend, case, isolation)
        try:
            await wait_for_lock("grn_immutable_old_writer", process, pending)
            stopped = await asyncio.wait_for(cancel(client, case, headers), 1.5)
            assert stopped.status_code == 200
            assert stopped.json()["outcome"] == "cancelled"
        finally:
            await branch_lock.rollback()
        reply = await child_reply(process, pending)
    assert 400 <= reply["status"] <= 599
    fresh = await client.get(BASE + "/recovery/actions/outcome", headers=headers)
    assert fresh.status_code == 200
    assert fresh.json()["outcome"] == "cancelled"
    after = await counts(session, case)
    assert after["grns"] == after["proofs"] == after["journals"] == 0
    assert after["qty"] == baseline["qty"]
    assert after["decisions"] == 1
    assert await economic_counts(session, case) == economic_before


async def test_actual_old_http_writer_commits_automatic_proof_and_new_cancel_returns_acceptance(
    client, session, receipt, old_backend
):
    case = receipt
    headers = await unlock(client, case)
    process, pending = await old_request(old_backend, case)
    reply = await child_reply(process, pending)
    assert reply["status"] == 201
    result = await cancel(client, case, headers)
    assert result.status_code == 200
    assert result.json()["outcome"] == "accepted"
    assert result.json()["accepted"] == reply["receipt"]
    grn_id = await session.scalar(select(GRN.id).where(GRN.idempotency_key == case["key"]))
    proof = await session.get(GrnCompletionProof, grn_id)
    assert proof.profile == "legacy"
    assert proof.original_wire_json is None
    assert proof.terminal_known is True
    assert (await counts(session, case))["decisions"] == 1


async def test_new_outcome_disclosure_rechecks_expiry_after_actor_lock_wait(
    client, session, receipt, monkeypatch
):
    from datetime import UTC, datetime, timedelta

    from app.services.inventory import grn_recovery

    case = receipt
    headers = await unlock(client, case)
    original_actor = grn_recovery.actor

    async def observed_actor(db, *args, **kwargs):
        await db.execute(text("SET LOCAL application_name='grn_actor_expiry'"))
        return await original_actor(db, *args, **kwargs)

    monkeypatch.setattr(grn_recovery, "actor", observed_actor)
    now = datetime.now(UTC)

    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return now

    monkeypatch.setattr(grn_recovery, "datetime", Clock)
    async with AsyncSessionLocal() as owner_lock:
        await owner_lock.execute(
            text("SELECT id FROM users WHERE id=:id FOR NO KEY UPDATE"), {"id": case["actor_id"]}
        )
        pending = asyncio.create_task(
            client.get(BASE + "/recovery/actions/outcome", headers=headers)
        )
        await wait_for_lock("grn_actor_expiry")
        now += timedelta(seconds=301)
        await owner_lock.rollback()
        denied = await pending
    assert denied.status_code == 401
    assert (await counts(session, case))["decisions"] == 0


async def test_cancel_unique_index_wait_expiry_is_unconfirmed_then_fresh_authority_reads_acceptance(
    client, session, receipt, old_backend, monkeypatch
):
    from datetime import UTC, datetime, timedelta

    from app.services.inventory import grn_recovery

    case = receipt
    headers = await unlock(client, case)
    original_actor = grn_recovery.actor

    async def observed_actor(db, *args, **kwargs):
        await db.execute(text("SET LOCAL application_name='grn_cancel_index_expiry'"))
        return await original_actor(db, *args, **kwargs)

    monkeypatch.setattr(grn_recovery, "actor", observed_actor)
    now = datetime.now(UTC)

    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return now

    monkeypatch.setattr(grn_recovery, "datetime", Clock)
    async with AsyncSessionLocal() as ingredient_lock:
        await ingredient_lock.execute(
            text("SELECT id FROM ingredients WHERE id=:id FOR UPDATE"),
            {"id": case["ingredient_id"]},
        )
        process, child = await old_request(old_backend, case)
        try:
            await wait_for_lock("grn_immutable_old_writer", process, child)
            pending = asyncio.create_task(cancel(client, case, headers))
            await wait_for_lock("grn_cancel_index_expiry")
            now += timedelta(seconds=301)
        finally:
            await ingredient_lock.rollback()
        old = await child_reply(process, child)
        result = await pending
    assert old["status"] == 201
    assert result.status_code in (401, 409)
    assert (
        await client.get(BASE + "/recovery/actions/outcome", headers=headers)
    ).status_code == 401
    # The old row's acceptance is durable; a new real unlock restores read authority only.
    monkeypatch.setattr(grn_recovery, "datetime", datetime)
    fresh = await unlock(client, case)
    out = await client.get(BASE + "/recovery/actions/outcome", headers=fresh)
    assert out.status_code == 200
    assert out.json()["accepted"] == old["receipt"]
    after = await counts(session, case)
    assert after["grns"] == after["decisions"] == after["proofs"] == after["journals"] == 1


@pytest.mark.parametrize("isolation", ["READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"])
@pytest.mark.parametrize("producer_commits", [True, False])
async def test_database_index_arbitration_resolves_after_old_producer_commit_or_rollback(
    client, session, receipt, old_backend, isolation, producer_commits
):
    """DB isolation proof only; cold HTTP authority remains restricted to READ COMMITTED."""
    case = receipt
    headers = await unlock(client, case)
    marker = "grn_raw_index_wait"
    economic_before = await economic_counts(session, case)

    async def contender():
        async with AsyncSessionLocal() as db:
            await db.connection(execution_options={"isolation_level": isolation})
            await db.execute(text("SET LOCAL application_name='grn_raw_index_wait'"))
            # This establishes the contender snapshot before the old producer resolves.
            await db.execute(
                text("SELECT id FROM users WHERE id=:id FOR SHARE"), {"id": case["actor_id"]}
            )
            grant = await db.scalar(
                select(GrnRecoverySession).where(
                    GrnRecoverySession.token_hash == digest(headers["X-GRN-Recovery"])
                )
            )
            db.add(
                GrnActionDecision(
                    id=uuid4(),
                    action_key=case["key"],
                    company_id=case["company_id"],
                    branch_id=case["branch_id"],
                    actor_user_id=case["actor_id"],
                    terminal_id=case["terminal_id"],
                    terminal_known=True,
                    request_hash=grant.request_hash,
                    outcome="cancelled",
                    accepted_grn_id=None,
                    original_wire_json=case["raw"],
                    cancellation_reason="Retire exact pending receipt",
                    recovery_session_id=grant.id,
                    decided_at=datetime.now(UTC),
                )
            )
            try:
                await db.commit()
                return "committed"
            except DBAPIError as error:
                await db.rollback()
                return error.orig.sqlstate

    async with AsyncSessionLocal() as ingredient_lock:
        await ingredient_lock.execute(
            text("SELECT id FROM ingredients WHERE id=:id FOR UPDATE"),
            {"id": case["ingredient_id"]},
        )
        process, old_pending = await old_request(old_backend, case)
        try:
            await wait_for_lock("grn_immutable_old_writer", process, old_pending)
            pending = asyncio.create_task(contender())
            await wait_for_lock(marker)
            if producer_commits:
                await ingredient_lock.rollback()
            else:
                # Legal concurrent archive makes the old writer abort after its pending index row.
                await ingredient_lock.execute(
                    text("UPDATE ingredients SET deleted_at=statement_timestamp() WHERE id=:id"),
                    {"id": case["ingredient_id"]},
                )
                await ingredient_lock.commit()
            old = await child_reply(process, old_pending)
            resolution = await pending
        finally:
            await ingredient_lock.rollback()
    out = await client.get(BASE + "/recovery/actions/outcome", headers=headers)
    assert out.status_code == 200
    after = await counts(session, case)
    assert after["decisions"] == 1
    if producer_commits:
        assert old["status"] == 201
        assert resolution in ("23505", "40001")
        assert out.json()["outcome"] == "accepted"
        assert out.json()["accepted"] == old["receipt"]
        assert after["grns"] == after["proofs"] == after["journals"] == 1
    else:
        assert 400 <= old["status"] <= 599
        assert resolution == "committed"
        assert out.json()["outcome"] == "cancelled"
        assert out.json()["cancelled"]["cancellation_reason"] == "Retire exact pending receipt"
        assert after["grns"] == after["proofs"] == after["journals"] == 0
        assert after["qty"] == 0
        assert await economic_counts(session, case) == economic_before
