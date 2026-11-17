"""Actual GRN receive/outcome/retirement with isolated physical and AP evidence."""

from __future__ import annotations

import json
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select, text
from sqlalchemy.exc import DBAPIError

from app.models import (
    GRN,
    Account,
    Batch,
    GrnActionDecision,
    GrnCompletionProof,
    GrnRecoverySession,
    IdempotencyKey,
    Ingredient,
    Supplier,
)
from app.services.accounting.accounts import ACCOUNTS_PAYABLE, INVENTORY
from app.services.audit.recorder import install_audit_listeners
from app.services.inventory.grn_commands import raw_hash

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
BASE = "/api/v1/inventory/grn-commands"


@pytest_asyncio.fixture
async def receipt(client, session, seed_owner):
    install_audit_listeners()
    company, branch, owner = seed_owner["company"], seed_owner["branch"], seed_owner["owner"]
    supplier = Supplier(id=uuid4(), company_id=company.id, name="Synthetic GRN supplier")
    ingredient = Ingredient(
        id=uuid4(),
        company_id=company.id,
        sku="GRN-" + uuid4().hex,
        name="Synthetic GRN ingredient",
        base_unit="unit",
        current_qty=0,
        avg_cost_minor=0,
        reorder_threshold=0,
        reorder_qty=0,
    )
    session.add_all(
        [
            supplier,
            ingredient,
            *[
                Account(id=uuid4(), company_id=company.id, **a.seed_dict())
                for a in (INVENTORY, ACCOUNTS_PAYABLE)
            ],
        ]
    )
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login", json={"email": owner.email, "password": seed_owner["password"]}
    )
    assert login.status_code == 200, login.text
    headers = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    body = {
        "branch_id": str(branch.id),
        "supplier_id": str(supplier.id),
        "supplier_invoice_no": "SYNTHETIC-NOT-UNIQUE",
        "notes": "PRIVATE_GRN_NOTE",
        "lines": [{"ingredient_id": str(ingredient.id), "qty": "1.005", "unit_cost_minor": 100}],
    }
    key = f"grn:v1:{company.id}:{owner.id}:{uuid4()}"
    return {
        "company_id": company.id,
        "actor_id": owner.id,
        "branch_id": branch.id,
        "terminal_id": seed_owner["terminal"].id,
        "ingredient_id": ingredient.id,
        "seed": seed_owner,
        "ingredient": ingredient,
        "supplier": supplier,
        "headers": headers,
        "body": body,
        "raw": json.dumps(body, ensure_ascii=False),
        "key": key,
    }


async def receive(client, case, **patch):
    raw = patch.get("raw", case["raw"])
    key = patch.get("key", case["key"])
    return await client.post(
        "/api/v1/inventory/grn",
        content=raw.encode(),
        headers={
            **case["headers"],
            "Content-Type": "application/json",
            "X-GRN-Command-Protocol": "1",
            "Idempotency-Key": key,
        },
    )


def bootstrap(case, **changes):
    seed = case["seed"]
    return {
        "company_id": str(seed["company"].id),
        "actor_user_id": str(seed["owner"].id),
        "branch_id": str(seed["branch"].id),
        "terminal_id": str(seed["terminal"].id),
        "request_method": "POST",
        "request_path": "/api/v1/inventory/grn",
        "request_query": "",
        "original_wire_json": case["raw"],
        "password": seed["password"],
        **changes,
    }


async def unlock(client, case, **changes):
    client.cookies.clear()
    body = bootstrap(case, **changes)
    result = await client.post(
        BASE + "/recovery/unlock", json=body, headers={"Idempotency-Key": case["key"]}
    )
    assert result.status_code == 200, result.text
    assert result.json()["purpose"] == "inventory_grn_recovery"
    assert result.json()["expires_in_seconds"] == 300
    return {"Idempotency-Key": case["key"], "X-GRN-Recovery": result.json()["token"]}


async def counts(session, case):
    return await session.scalar(
        text("""SELECT jsonb_build_object(
      'grns',(SELECT count(*) FROM grns WHERE idempotency_key=:key),
      'decisions',(SELECT count(*) FROM grn_action_decisions WHERE action_key=:key),
      'proofs',(SELECT count(*) FROM grn_completion_proofs
       WHERE id IN(SELECT id FROM grn_action_decisions WHERE action_key=:key)),
      'qty',(SELECT current_qty FROM ingredients WHERE id=:ingredient),
      'journals',(SELECT count(*) FROM journal_entries
       WHERE company_id=:company AND ref_type='grn_receipt'))"""),
        {"key": case["key"], "ingredient": case["ingredient_id"], "company": case["company_id"]},
    )


async def cancel(client, case, headers, reason="Retire only the exact unaccepted goods receipt"):
    return await client.post(
        BASE + "/recovery/actions/cancel",
        headers=headers,
        json={"original_wire_json": case["raw"], "cancellation_reason": reason},
    )


async def test_real_new_wire_acceptance_retains_exact_proof_and_replay_once(
    client, session, receipt
):
    case = receipt
    cap = await client.get(
        BASE + "/capabilities", headers={"Authorization": case["headers"]["Authorization"]}
    )
    assert cap.status_code == 200, cap.text
    assert (
        await session.scalar(text("SELECT dcompany_grn_valid_wire(:raw)"), {"raw": case["raw"]})
        is True
    )
    result = await receive(client, case)
    assert result.status_code == 201, result.text
    accepted = result.json()
    assert accepted["total_minor"] == 101
    assert accepted["accounting_status"] == "posted"
    proof = await session.get(GrnCompletionProof, UUID(accepted["id"]))
    assert proof.original_wire_json == case["raw"]
    assert proof.profile == "wire_v1"
    assert proof.terminal_known
    d = await session.get(GrnActionDecision, proof.id)
    assert d.request_hash == raw_hash(case["key"], case["raw"])
    baseline = await counts(session, case)
    retry = await receive(client, case)
    assert retry.status_code == 201
    assert retry.json() == accepted
    await session.execute(text("DELETE FROM idempotency_keys WHERE key=:key"), {"key": case["key"]})
    await session.commit()
    replay = await receive(client, case)
    assert replay.status_code == 201, replay.text
    assert replay.json() == accepted
    headers = await unlock(client, case)
    out = await client.get(BASE + "/recovery/actions/outcome", headers=headers)
    assert out.status_code == 200, out.text
    assert out.json()["accepted"] == accepted
    assert out.json()["reference"]["request_hash"] == d.request_hash
    assert await counts(session, case) == baseline
    assert "set-cookie" not in out.headers
    assert out.headers["cache-control"] == "no-store"


async def test_source_free_cancel_is_durable_and_fences_delayed_original_receive(
    client, session, receipt
):
    case = receipt
    headers = await unlock(client, case)
    before = await counts(session, case)
    outcome = await client.get(BASE + "/recovery/actions/outcome", headers=headers)
    assert outcome.json()["outcome"] == "unconfirmed"
    result = await cancel(client, case, headers)
    assert result.status_code == 200, result.text
    assert result.json()["outcome"] == "cancelled"
    assert result.json()["cancelled"]["stock_effect"] is False
    replay = await cancel(client, case, headers)
    assert replay.json() == result.json()
    changed = await cancel(client, case, headers, "Different reason is not the same command")
    assert changed.status_code == 409
    late = await receive(client, case)
    assert late.status_code == 409, late.text
    after = await counts(session, case)
    assert after["grns"] == 0
    assert after["proofs"] == 0
    assert after["qty"] == before["qty"]
    assert after["journals"] == before["journals"]
    assert after["decisions"] == 1


async def test_zero_cost_receipt_is_complete_physical_evidence_not_missing_journal_success(
    client, session, receipt
):
    case = receipt
    body = {**case["body"], "lines": [{**case["body"]["lines"][0], "unit_cost_minor": 0}]}
    case = {**case, "raw": json.dumps(body)}
    result = await receive(client, case)
    assert result.status_code == 201, result.text
    assert result.json()["accounting_status"] == "no_balance"
    assert result.json()["journal_entry_id"] is None
    proof = await session.get(GrnCompletionProof, UUID(result.json()["id"]))
    assert len(proof.source["lines"]) == 1
    assert len(proof.source["movements"]) == 1
    with pytest.raises(DBAPIError, match="GRN"):
        await session.execute(
            text("UPDATE batches SET qty_initial=2 WHERE id=:id"),
            {"id": UUID(result.json()["batch_ids"][0])},
        )
    await session.rollback()


async def test_normal_legacy_numeric_writer_gets_automatic_deferred_completion_without_new_helper(
    client, session, receipt
):
    case = receipt
    body = {**case["body"], "lines": [{**case["body"]["lines"][0], "qty": 1.005}]}
    raw = json.dumps(body)
    key = "legacy-grn-" + str(uuid4())
    result = await client.post(
        "/api/v1/inventory/grn",
        content=raw,
        headers={**case["headers"], "Content-Type": "application/json", "Idempotency-Key": key},
    )
    assert result.status_code == 201, result.text
    proof = await session.get(GrnCompletionProof, UUID(result.json()["id"]))
    assert proof.profile == "legacy"
    assert proof.original_wire_json is None
    assert proof.terminal_known
    d = await session.get(GrnActionDecision, proof.id)
    assert d.request_hash == raw_hash(key, raw)


@pytest.mark.parametrize(
    "raw",
    [
        "[]",
        "{}",
        "null",
        "1",
        '"text"',
        "{broken PRIVATE_WIRE_MARKER",
        '{"branch_id":null}',
        '{"branch_id":42}',
        '{"branch_id":true}',
    ],
)
async def test_malformed_original_wire_is_sanitized_validation_not_internal_error(
    client, session, receipt, raw, capsys
):
    case = receipt
    client.cookies.clear()
    payload = bootstrap(case, original_wire_json=raw)
    result = await client.post(
        BASE + "/recovery/unlock",
        json=payload,
        headers={"Idempotency-Key": case["key"], "X-Request-Id": case["key"]},
    )
    assert result.status_code == 422, result.text
    captured = capsys.readouterr()
    transcript = result.text + captured.out + captured.err
    for secret in ("PRIVATE_WIRE_MARKER", case["key"], case["seed"]["password"]):
        assert secret not in transcript
    assert (
        await session.scalar(
            select(func.count())
            .select_from(GrnRecoverySession)
            .where(GrnRecoverySession.user_id == case["seed"]["owner"].id)
        )
        == 0
    )


async def test_lock_is_purpose_only_idempotent_and_prevents_further_disclosure(
    client, session, receipt
):
    case = receipt
    headers = await unlock(client, case)
    denied = await client.get(
        "/api/v1/auth/me", headers={"Authorization": "Bearer " + headers["X-GRN-Recovery"]}
    )
    assert denied.status_code == 401
    locked = await client.post(
        BASE + "/recovery/lock", headers={"X-GRN-Recovery": headers["X-GRN-Recovery"]}
    )
    assert locked.status_code == 200, locked.text
    assert locked.json() == {"protocol": 1, "locked": True}
    again = await client.post(
        BASE + "/recovery/lock", headers={"X-GRN-Recovery": headers["X-GRN-Recovery"]}
    )
    assert again.status_code == 200
    outcome = await client.get(BASE + "/recovery/actions/outcome", headers=headers)
    assert outcome.status_code == 401
    assert (await counts(session, case))["decisions"] == 0


@pytest.mark.parametrize("cohort", ["line", "batch", "po_line", "movement"])
async def test_unsealed_zero_source_cannot_update_into_completed_zero_cohort(
    client, session, receipt, monkeypatch, cohort
):
    from sqlalchemy import event

    from app.api.v1.inventory import router as inventory_route
    from app.core.db import async_engine

    case = {
        **receipt,
        "raw": json.dumps(
            {**receipt["body"], "lines": [{**receipt["body"]["lines"][0], "unit_cost_minor": 0}]}
        ),
    }
    target = await receive(client, case)
    assert target.status_code == 201, target.text
    target_id = UUID(target.json()["id"])
    target_po = UUID(target.json()["purchase_order_id"])
    baseline = await counts(session, case)
    original = inventory_route._grn_post_response
    seen = []

    def capture(ctx):
        seen.append(getattr(getattr(ctx.original_exception, "__cause__", None), "message", ""))

    async def reassign(db, grn):
        sql = {
            "line": "UPDATE grn_lines SET grn_id=:target WHERE grn_id=:source",
            "batch": "UPDATE batches SET grn_id=:target WHERE grn_id=:source",
            "po_line": (
                "UPDATE purchase_order_lines SET purchase_order_id=:target "
                "WHERE purchase_order_id=:source"
            ),
            "movement": "UPDATE stock_movements SET ref_id=:target WHERE ref_id=:source",
        }[cohort]
        await db.execute(
            text(sql),
            {
                "target": target_po if cohort == "po_line" else target_id,
                "source": grn.purchase_order_id if cohort == "po_line" else grn.id,
            },
        )
        return await original(db, grn)

    monkeypatch.setattr(inventory_route, "_grn_post_response", reassign)
    event.listen(async_engine.sync_engine, "handle_error", capture)
    other = f"grn:v1:{case['seed']['company'].id}:{case['seed']['owner'].id}:{uuid4()}"
    try:
        result = await client.post(
            "/api/v1/inventory/grn",
            content=case["raw"],
            headers={
                **case["headers"],
                "Content-Type": "application/json",
                "Idempotency-Key": other,
            },
        )
    finally:
        event.remove(async_engine.sync_engine, "handle_error", capture)
    assert result.status_code == 409, result.text
    assert any(
        "GRN" in message and ("immutable" in message or "Completed" in message) for message in seen
    ), seen
    assert await counts(session, case) == baseline
    assert (
        await session.scalar(
            select(func.count()).select_from(GRN).where(GRN.idempotency_key == other)
        )
        == 0
    )


async def test_cancelled_null_reason_is_rejected_with_otherwise_exact_live_session(
    client, session, receipt
):
    case = receipt
    await unlock(client, case)
    credential = await session.scalar(
        select(GrnRecoverySession).where(GrnRecoverySession.user_id == case["seed"]["owner"].id)
    )
    with pytest.raises(DBAPIError, match="ck_grn_decision_shape"):
        await session.execute(
            text("""INSERT INTO grn_action_decisions(id,action_key,company_id,branch_id,
        actor_user_id,terminal_id,terminal_known,request_hash,outcome,
        original_wire_json,cancellation_reason,recovery_session_id,decided_at)
        VALUES(:id,:key,:company,:branch,:actor,:terminal,true,:hash,
        'cancelled',:raw,NULL,:sid,statement_timestamp())"""),
            {
                "id": uuid4(),
                "key": case["key"],
                "company": credential.company_id,
                "branch": credential.branch_id,
                "actor": credential.user_id,
                "terminal": credential.terminal_id,
                "hash": credential.request_hash,
                "raw": case["raw"],
                "sid": credential.id,
            },
        )
    await session.rollback()
    assert (await counts(session, case))["decisions"] == 0


async def test_positive_missing_journal_fails_at_dependency_commit_without_false_2xx(
    client, session, receipt, monkeypatch
):
    from unittest.mock import AsyncMock

    from sqlalchemy import event

    from app.api.v1.inventory import router as inventory_route
    from app.core.db import async_engine

    case = receipt
    seen = []

    def capture(ctx):
        seen.append(getattr(getattr(ctx.original_exception, "__cause__", None), "message", ""))

    monkeypatch.setattr(
        inventory_route, "post_two_sided_operational_journal", AsyncMock(return_value=None)
    )
    event.listen(async_engine.sync_engine, "handle_error", capture)
    try:
        response = await client.post(
            "/api/v1/inventory/grn",
            content=case["raw"],
            headers={
                **case["headers"],
                "Content-Type": "application/json",
                "Idempotency-Key": case["key"],
            },
        )
    finally:
        event.remove(async_engine.sync_engine, "handle_error", capture)
    assert response.status_code == 409, response.text
    assert any("exact Inventory AP journal" in message for message in seen), seen
    final = await counts(session, case)
    assert final["grns"] == final["decisions"] == final["proofs"] == final["journals"] == 0
    assert final["qty"] == 0
    assert await session.get(IdempotencyKey, case["key"]) is None


@pytest.mark.parametrize(
    "mutation",
    [
        "DELETE FROM grn_lines WHERE grn_id=:id",
        "DELETE FROM stock_movements WHERE ref_id=:id",
        "UPDATE batches SET cost_per_unit_minor=1 WHERE grn_id=:id",
    ],
)
async def test_zero_incomplete_physical_receipt_cannot_be_acknowledged(
    client, session, receipt, monkeypatch, mutation
):
    from sqlalchemy import event

    from app.api.v1.inventory import router as inventory_route
    from app.core.db import async_engine

    case = {
        **receipt,
        "raw": json.dumps(
            {**receipt["body"], "lines": [{**receipt["body"]["lines"][0], "unit_cost_minor": 0}]}
        ),
    }
    original = inventory_route._grn_post_response
    seen = []

    async def damage(db, grn):
        await db.execute(text(mutation), {"id": grn.id})
        return await original(db, grn)

    def capture(ctx):
        seen.append(getattr(getattr(ctx.original_exception, "__cause__", None), "message", ""))

    monkeypatch.setattr(inventory_route, "_grn_post_response", damage)
    event.listen(async_engine.sync_engine, "handle_error", capture)
    try:
        response = await client.post(
            "/api/v1/inventory/grn",
            content=case["raw"],
            headers={
                **case["headers"],
                "Content-Type": "application/json",
                "Idempotency-Key": case["key"],
            },
        )
    finally:
        event.remove(async_engine.sync_engine, "handle_error", capture)
    assert response.status_code == 409, response.text
    assert any("GRN completion" in message for message in seen), seen
    final = await counts(session, case)
    assert final["grns"] == final["decisions"] == final["proofs"] == 0
    assert final["qty"] == 0


@pytest.mark.parametrize(
    ("qty", "cost"), [("0.01", 29), ("0.0001", 49), ("0.001", 9007199254740991)]
)
async def test_http_fractional_receipt_cache_keeps_exact_original_unit_cost(
    client, session, receipt, qty, cost
):
    case = {
        **receipt,
        "raw": json.dumps(
            {
                **receipt["body"],
                "lines": [{**receipt["body"]["lines"][0], "qty": qty, "unit_cost_minor": cost}],
            }
        ),
    }
    result = await receive(client, case)
    assert result.status_code == 201, result.text
    summary = (
        await session.execute(
            text("SELECT current_qty,avg_cost_minor FROM ingredients WHERE id=:id"),
            {"id": case["ingredient_id"]},
        )
    ).one()
    from decimal import Decimal

    assert summary.current_qty == Decimal(qty)
    assert summary.avg_cost_minor == cost
    batch = await session.get(Batch, UUID(result.json()["batch_ids"][0]))
    assert batch.cost_per_unit_minor == cost


async def test_http_duplicate_lines_keep_sequential_cache_and_exact_batch_economics(
    client, session, receipt
):
    case = {
        **receipt,
        "raw": json.dumps(
            {
                **receipt["body"],
                "lines": [
                    {**receipt["body"]["lines"][0], "qty": "1", "unit_cost_minor": cost}
                    for cost in [2, 1, 3]
                ],
            }
        ),
    }
    result = await receive(client, case)
    assert result.status_code == 201, result.text
    assert result.json()["total_minor"] == 6
    summary = (
        await session.execute(
            text("SELECT current_qty,avg_cost_minor FROM ingredients WHERE id=:id"),
            {"id": case["ingredient_id"]},
        )
    ).one()
    assert summary.current_qty == 3
    assert summary.avg_cost_minor == 1
    costs = await session.scalars(
        select(Batch.cost_per_unit_minor).where(Batch.grn_id == UUID(result.json()["id"]))
    )
    assert sorted(costs) == [1, 2, 3]


async def test_completed_zero_proof_survives_real_later_stock_reduction(client, session, receipt):
    case = {
        **receipt,
        "raw": json.dumps(
            {**receipt["body"], "lines": [{**receipt["body"]["lines"][0], "unit_cost_minor": 0}]}
        ),
    }
    result = await receive(client, case)
    assert result.status_code == 201, result.text
    before = await session.get(GrnCompletionProof, UUID(result.json()["id"]))
    source = before.source
    proof_response = before.response
    adjustment = await client.post(
        "/api/v1/inventory/adjustments",
        headers={**case["headers"], "Idempotency-Key": "adjust-after-grn-" + str(uuid4())},
        json={
            "branch_id": str(case["branch_id"]),
            "ingredient_id": str(case["ingredient_id"]),
            "qty_delta": -0.5,
            "type": "waste",
            "note": "Real later reduction",
        },
    )
    assert adjustment.status_code == 201, adjustment.text
    await session.refresh(before)
    assert before.source == source
    assert before.response == proof_response
    replay = await receive(client, case)
    assert replay.status_code == 201, replay.text
    assert replay.json() == result.json()


async def test_same_transaction_keyless_insert_cannot_later_adopt_request_identity(
    client, session, receipt, monkeypatch
):
    from sqlalchemy import event

    from app.api.v1.inventory import router as inventory_route
    from app.core.db import async_engine

    case = {
        **receipt,
        "raw": json.dumps(
            {
                **receipt["body"],
                "lines": [{**receipt["body"]["lines"][0], "unit_cost_minor": 0}],
            }
        ),
    }
    seen = []
    original = inventory_route._grn_post_response

    def capture(ctx):
        seen.append(getattr(getattr(ctx.original_exception, "__cause__", None), "message", ""))

    async def adopt(db, grn):
        partial_id = uuid4()
        await db.execute(
            text("""INSERT INTO grns(id,purchase_order_id,received_at,received_by)
            SELECT :id,purchase_order_id,received_at,received_by FROM grns WHERE id=:source"""),
            {"id": partial_id, "source": grn.id},
        )
        await db.execute(
            text("""UPDATE grns SET idempotency_key=:key,request_hash=:hash
            WHERE id=:id"""),
            {"id": partial_id, "key": "adopt-" + str(uuid4()), "hash": "ab" * 32},
        )
        return await original(db, grn)

    monkeypatch.setattr(inventory_route, "_grn_post_response", adopt)
    event.listen(async_engine.sync_engine, "handle_error", capture)
    try:
        response = await client.post(
            "/api/v1/inventory/grn",
            content=case["raw"],
            headers={
                **case["headers"],
                "Content-Type": "application/json",
                "Idempotency-Key": case["key"],
            },
        )
    finally:
        event.remove(async_engine.sync_engine, "handle_error", capture)
    assert response.status_code == 409
    assert any("request identity is immutable after INSERT" in message for message in seen)
    state = await counts(session, case)
    assert state["grns"] == state["decisions"] == state["proofs"] == 0
    assert state["qty"] == 0
