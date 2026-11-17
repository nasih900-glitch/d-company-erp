"""Owned QA: exact unsupplied stock restoration, no payout and durable replay."""

# pytest fixtures retain their injected names.
# Transaction rollback checks deliberately include write and deferred commit.
# ruff: noqa: F811, PT012
from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.models import Batch, Ingredient, OrderLine, Recipe, RecipeLine, StockMovement
from app.services.inventory.deduction import deduct_for_order
from tests.integration.test_pos_receipt_correction_preview import paid_retained  # noqa: F401
from tests.integration.test_pos_receipt_corrections import original_facts

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]


@pytest_asyncio.fixture
async def stocked(session, seed_owner, paid_retained, request):
    lines = list(
        await session.scalars(
            select(OrderLine).where(OrderLine.order_id == UUID(paid_retained["order_id"]))
        )
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        sku=uuid4().hex,
        name="QA bottled drink",
        base_unit="unit",
        current_qty=2 if getattr(request, "param", None) == "fractional" else 5,
    )
    session.add(ingredient)
    await session.flush()
    batches = [
        Batch(
            id=uuid4(),
            ingredient_id=ingredient.id,
            branch_id=seed_owner["branch"].id,
            received_at=datetime(2026, 1, index + 1, tzinfo=UTC),
            qty_initial=qty,
            qty_on_hand=qty,
            cost_per_unit_minor=cost,
        )
        for index, (qty, cost) in enumerate(
            [(2, 1)] if getattr(request, "param", None) == "fractional" else [(1, 100), (4, 150)]
        )
    ]
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=lines[0].menu_item_id,
        name="QA consumption",
        yield_qty=1,
        version=1,
        is_active=True,
    )
    session.add_all([*batches, recipe])
    await session.flush()
    session.add(
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe.id,
            ingredient_id=ingredient.id,
            qty=Decimal("0.25") if getattr(request, "param", None) == "fractional" else 1,
        )
    )
    await session.flush()
    assert await deduct_for_order(
        session,
        order_id=UUID(paid_retained["order_id"]),
        order_lines=lines,
        branch_id=seed_owner["branch"].id,
        created_by=seed_owner["owner"].id,
    ) == (1 if getattr(request, "param", None) == "fractional" else 2)
    await session.commit()
    return {
        **paid_retained,
        "ingredient_id": ingredient.id,
        "batch_ids": [b.id for b in batches],
        "recipe_id": recipe.id,
        "branch_id": str(seed_owner["branch"].id),
        "company_id": seed_owner["company"].id,
    }


async def preview(client, case):
    response = await client.post(
        "/api/v1/pos/receipt-corrections/stock/preview",
        headers=case["headers"],
        json={"order_id": case["order_id"]},
    )
    assert response.status_code == 200, response.text
    return response.json()


async def command(client, case, *, scope="receipt_stock"):
    value = await preview(client, case)
    assert value["eligible"], value["blockers"]
    unlock = await client.post(
        "/api/v1/finance/tender-corrections/unlock",
        headers=case["headers"],
        json={"branch_id": case["branch_id"], "scope": scope, "password": "password1234"},
    )
    assert unlock.status_code == 200, unlock.text
    return {
        "order_id": case["order_id"],
        "source_fingerprint": value["source_fingerprint"],
        "stock_fingerprint": value["stock_fingerprint"],
        "restore_stock": True,
        "stock_never_consumed": True,
        "no_funds_received": True,
        "all_items_not_supplied": True,
        "acknowledges_no_payout": True,
        "reason": "Unsupplied stock and no funds received",
    }, {
        **case["headers"],
        "X-Tender-Token": unlock.json()["token"],
        "Idempotency-Key": str(uuid4()),
    }


async def test_stock_preview_exact_readonly_and_legacy_endpoint_still_refuses(
    client, session, seed_owner, stocked
):
    before = await original_facts(session, stocked)
    value = await preview(client, stocked)
    assert value["eligible"], value["blockers"]
    assert value["inventory_increase_minor"] == 250
    assert len(value["stock_movements"]) == 2
    assert {m["base_unit"] for m in value["stock_movements"]} == {"unit"}
    assert {m["quantity"] for m in value["stock_movements"]} == {"1.0"}
    assert await original_facts(session, stocked) == before
    legacy = await client.post(
        "/api/v1/pos/receipt-corrections/preview",
        headers=stocked["headers"],
        json={"order_id": stocked["order_id"]},
    )
    assert not legacy.json()["eligible"]
    assert "recorded_stock_or_cogs_movement" in legacy.json()["blockers"]


async def test_full_stock_restore_preserves_originals_and_replays_once(
    client, session, seed_owner, stocked
):
    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked)
    before = await original_facts(session, stocked)
    # Changing the current recipe must never change historical restoration.
    await session.execute(
        text("UPDATE recipe_lines SET qty=17 WHERE recipe_id=:id"), {"id": stocked["recipe_id"]}
    )
    await session.commit()
    response = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert response.status_code == 201, response.text
    result = response.json()
    assert result["payout_minor"] == 0
    assert result["stock_restored"] is True
    assert result["inventory_increase_minor"] == 250
    assert result["restored_movement_count"] == 2
    assert await original_facts(session, stocked) == before
    repeated = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert repeated.status_code == 201
    assert repeated.json() == result
    await session.rollback()
    assert await session.scalar(
        select(Ingredient.current_qty).where(Ingredient.id == stocked["ingredient_id"])
    ) == Decimal(5)
    assert (
        await session.scalar(
            text("SELECT count(*) FROM pos_receipt_stock_restorations WHERE correction_id=:id"),
            {"id": UUID(result["id"])},
        )
        == 2
    )
    balances = list(
        await session.scalars(
            select(Batch.qty_on_hand)
            .where(Batch.id.in_(stocked["batch_ids"]))
            .order_by(Batch.received_at)
        )
    )
    assert balances == [Decimal(1), Decimal(4)]
    assert (
        await session.scalar(
            text("SELECT count(*) FROM refunds WHERE order_id=:id"),
            {"id": UUID(stocked["order_id"])},
        )
        == 0
    )
    with pytest.raises(DBAPIError, match="ownership and unit are immutable"):
        async with session.begin_nested():
            await session.execute(
                text("UPDATE ingredients SET base_unit='ml' WHERE id=:id"),
                {"id": stocked["ingredient_id"]},
            )
    with pytest.raises(DBAPIError, match="immutable|no-value corrected"):
        async with session.begin_nested():
            await session.execute(
                text("UPDATE stock_movements SET qty_delta=0 WHERE ref_id=:id"),
                {"id": UUID(stocked["order_id"])},
            )


async def test_stock_commit_requires_purpose_specific_fresh_confirmation(
    client, session, seed_owner, stocked
):
    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked, scope="payment")
    wrong = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert wrong.status_code == 401, wrong.text
    assert (
        await session.scalar(
            text("SELECT count(*) FROM pos_receipt_corrections WHERE order_id=:id"),
            {"id": UUID(stocked["order_id"])},
        )
        == 0
    )


async def state(session, case):
    await session.rollback()
    return await session.scalar(
        text("""
      SELECT jsonb_build_object(
       'ingredient',(SELECT current_qty FROM ingredients WHERE id=:ingredient),
       'batches',(SELECT jsonb_agg(jsonb_build_object('id',id,'qty',qty_on_hand) ORDER BY id)
          FROM batches WHERE ingredient_id=:ingredient),
       'corrections',(SELECT count(*) FROM pos_receipt_corrections WHERE order_id=:order),
       'restorations',(SELECT count(*) FROM pos_receipt_stock_restorations
          WHERE company_id=:company),
       'movements',(SELECT count(*) FROM stock_movements WHERE batch_id IN
          (SELECT id FROM batches WHERE ingredient_id=:ingredient)),
       'audit',(SELECT count(*) FROM audit_log WHERE company_id=:company),
       'outbox',(SELECT count(*) FROM google_sheets_deliveries WHERE company_id=:company))
    """),
        {
            "ingredient": case["ingredient_id"],
            "order": UUID(case["order_id"]),
            "company": case["company_id"],
        },
    )


async def test_stock_correction_requires_literal_physical_never_consumed_attestation(
    client, session, seed_owner, stocked
):
    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked)
    for value in (False, 1, "true", None):
        invalid = {**body, "stock_never_consumed": value}
        response = await client.post(
            "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=invalid
        )
        assert response.status_code == 422, response.text


async def test_new_legitimate_sale_after_restoration_and_original_key_replay(
    client, session, seed_owner, stocked
):
    from datetime import timedelta

    from app.services.accounting.accounts import COST_OF_GOODS_SOLD
    from app.services.accounting.ledger import build_operational_ledger

    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked)
    saved = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert saved.status_code == 201, saved.text
    await session.rollback()
    batch = await session.get(Batch, stocked["batch_ids"][1], populate_existing=True)
    ingredient = await session.get(Ingredient, stocked["ingredient_id"], populate_existing=True)
    batch.qty_on_hand = Decimal(str(batch.qty_on_hand)) - Decimal("0.5")
    ingredient.current_qty = Decimal(str(ingredient.current_qty)) - Decimal("0.5")
    session.add(
        StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=batch.branch_id,
            type="sale",
            ref_type="order",
            ref_id=uuid4(),
            qty_delta=Decimal("-0.5"),
            cost_per_unit_minor=150,
        )
    )
    await session.commit()
    replay = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == saved.json()
    await session.rollback()
    assert await session.scalar(
        select(Ingredient.current_qty).where(Ingredient.id == ingredient.id)
    ) == Decimal("4.5")
    now = datetime.now(UTC)
    ledger = await build_operational_ledger(
        session,
        company_id=seed_owner["company"].id,
        start_at=now - timedelta(days=1),
        end_exclusive=now + timedelta(seconds=1),
    )
    assert (
        sum(
            line.debit_minor - line.credit_minor
            for line in ledger
            if line.account_code == COST_OF_GOODS_SOLD.ledger_tuple[0]
        )
        == 75
    )
    receipt = await client.get(
        f"/api/v1/pos/receipts/{stocked['order_id']}", headers=stocked["headers"]
    )
    assert receipt.json()["receipt_correction"]["stock_restored"] is True
    assert receipt.json()["receipt_correction"]["inventory_increase_minor"] == 250


@pytest.mark.parametrize("failure", ["audit", "outbox", "missing_restoration"])
async def test_any_failure_rolls_back_entire_stock_correction(
    client, session, seed_owner, stocked, monkeypatch, failure
):
    from app.services.pos.receipt_corrections import (
        ReceiptStockCorrectionCommit,
        commit_receipt_correction,
    )
    from tests.integration.test_pos_receipt_corrections import tenant_for

    stocked.update(branch_id=str(seed_owner["branch"].id), company_id=seed_owner["company"].id)
    body, headers = await command(client, stocked)
    before = await state(session, stocked)
    if failure == "audit":
        import app.services.audit.events as module

        def broken(*args, **kwargs):
            raise RuntimeError("Synthetic audit failure")

        monkeypatch.setattr(module, "record_operation", broken)
    elif failure == "outbox":
        import app.services.integrations.google_sheets_mirror as module

        async def broken(*args, **kwargs):
            raise RuntimeError("Synthetic outbox failure")

        monkeypatch.setattr(module, "enqueue_google_sheets_event_if_enabled", broken)
    else:
        import app.services.pos.receipt_stock as module

        async def broken(*args, **kwargs):
            return None

        monkeypatch.setattr(module, "restore_stock", broken)
    with pytest.raises((RuntimeError, DBAPIError), match="Synthetic|incomplete"):
        await commit_receipt_correction(
            session,
            tenant_for(seed_owner),
            payload=ReceiptStockCorrectionCommit(**body),
            idempotency_key=headers["Idempotency-Key"],
            confirmation_token=headers["X-Tender-Token"],
        )
        await session.commit()
    await session.rollback()
    assert await state(session, stocked) == before


async def test_changed_balanced_inventory_source_refuses_prepared_command(
    client, session, seed_owner, stocked
):
    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked)
    batch = await session.get(Batch, stocked["batch_ids"][0])
    ingredient = await session.get(Ingredient, stocked["ingredient_id"])
    batch.qty_on_hand = Decimal(str(batch.qty_on_hand)) + 1
    ingredient.current_qty = Decimal(str(ingredient.current_qty)) + 1
    session.add(
        StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=batch.branch_id,
            type="adjustment",
            qty_delta=1,
            cost_per_unit_minor=100,
        )
    )
    await session.commit()
    response = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert response.status_code == 409, response.text
    assert (
        await session.scalar(
            text("SELECT count(*) FROM pos_receipt_corrections WHERE order_id=:id"),
            {"id": UUID(stocked["order_id"])},
        )
        == 0
    )


@pytest.mark.parametrize("same_key", [True, False])
async def test_two_concurrent_actions_restore_once(client, session, seed_owner, stocked, same_key):
    import asyncio

    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked)
    alternate = headers if same_key else {**headers, "Idempotency-Key": str(uuid4())}
    await session.rollback()
    responses = await asyncio.gather(
        *[
            client.post("/api/v1/pos/receipt-corrections/stock/commit", headers=h, json=body)
            for h in (headers, alternate)
        ]
    )
    assert sorted(r.status_code for r in responses) == ([201, 201] if same_key else [201, 409])
    if same_key:
        assert responses[0].json() == responses[1].json()
    assert await session.scalar(
        select(Ingredient.current_qty).where(Ingredient.id == stocked["ingredient_id"])
    ) == Decimal(5)
    assert (
        await session.scalar(
            text("SELECT count(*) FROM pos_receipt_stock_restorations WHERE company_id=:id"),
            {"id": seed_owner["company"].id},
        )
        == 2
    )


async def test_confirmation_expiring_while_waiting_for_inventory_lock_is_rechecked(
    client, session, seed_owner, stocked, monkeypatch
):
    import asyncio

    import jwt

    import app.services.pos.receipt_stock as module
    from app.core.config import get_settings
    from app.core.security import _signing_key, decode_token

    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked)
    claims = decode_token(headers["X-Tender-Token"])
    claims["exp"] = datetime.now(UTC).timestamp() + 2
    headers["X-Tender-Token"] = jwt.encode(
        claims, _signing_key(), algorithm=get_settings().jwt_algorithm
    )
    entered = asyncio.Event()
    original = module.lock_stock_source

    async def observed(*args, **kwargs):
        entered.set()
        await original(*args, **kwargs)

    monkeypatch.setattr(module, "lock_stock_source", observed)
    await session.execute(
        select(Ingredient.id).where(Ingredient.id == stocked["ingredient_id"]).with_for_update()
    )
    task = asyncio.create_task(
        client.post("/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body)
    )
    try:
        await asyncio.wait_for(entered.wait(), timeout=3)
        await asyncio.sleep(2.1)
    finally:
        await session.rollback()
    response = await asyncio.wait_for(task, timeout=5)
    assert response.status_code == 401, response.text
    assert (
        await session.scalar(
            text("SELECT count(*) FROM pos_receipt_corrections WHERE order_id=:id"),
            {"id": UUID(stocked["order_id"])},
        )
        == 0
    )


async def test_raw_naked_and_extra_restorations_and_update_conversion_are_rejected(
    client, session, seed_owner, stocked
):
    stocked["branch_id"] = str(seed_owner["branch"].id)
    body, headers = await command(client, stocked)
    with pytest.raises(DBAPIError, match="requires its retained correction"):
        async with session.begin_nested():
            await session.execute(
                text("""INSERT INTO stock_movements(
                id,batch_id,branch_id,type,ref_type,ref_id,qty_delta,cost_per_unit_minor)
             VALUES(:id,:batch,:branch,'receipt_restore','pos_receipt_correction',:correction,1,100)"""),
                {
                    "id": uuid4(),
                    "batch": stocked["batch_ids"][0],
                    "branch": UUID(stocked["branch_id"]),
                    "correction": uuid4(),
                },
            )
    await session.rollback()
    saved = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert saved.status_code == 201, saved.text
    correction_id = UUID(saved.json()["id"])
    with pytest.raises(DBAPIError, match="incomplete"):
        async with session.begin_nested():
            await session.execute(
                text("""INSERT INTO stock_movements(
                id,batch_id,branch_id,type,ref_type,ref_id,qty_delta,cost_per_unit_minor)
             VALUES(:id,:batch,:branch,'receipt_restore','pos_receipt_correction',:correction,1,100)"""),
                {
                    "id": uuid4(),
                    "batch": stocked["batch_ids"][0],
                    "branch": UUID(stocked["branch_id"]),
                    "correction": correction_id,
                },
            )
            await session.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))
    # A new, unrelated ordinary row cannot be converted into an unlinked restore.
    await session.rollback()
    ordinary = StockMovement(
        id=uuid4(),
        batch_id=stocked["batch_ids"][0],
        branch_id=UUID(stocked["branch_id"]),
        type="adjustment",
        qty_delta=1,
        cost_per_unit_minor=100,
    )
    session.add(ordinary)
    await session.flush()
    with pytest.raises(DBAPIError, match="can only be inserted"):
        async with session.begin_nested():
            await session.execute(
                text(
                    "UPDATE stock_movements SET type='receipt_restore',"
                    "ref_type='pos_receipt_correction',ref_id=:id WHERE id=:movement"
                ),
                {"id": correction_id, "movement": ordinary.id},
            )
    await session.rollback()


@pytest.mark.parametrize("mutation", ["cost", "initial_qty", "trail", "backdated_restore", "unit"])
async def test_same_transaction_provenance_mutations_cannot_commit(
    client, session, seed_owner, stocked, monkeypatch, mutation
):
    import app.services.pos.receipt_stock as module
    from app.services.pos.receipt_corrections import (
        ReceiptStockCorrectionCommit,
        commit_receipt_correction,
    )
    from tests.integration.test_pos_receipt_corrections import tenant_for

    stocked.update(branch_id=str(seed_owner["branch"].id), company_id=seed_owner["company"].id)
    body, headers = await command(client, stocked)
    before = await state(session, stocked)
    original = module.restore_stock

    async def tampered(s, **kwargs):
        # Tamper after correction INSERT but before disposition links exist.
        if mutation == "unit":
            await s.execute(
                text("UPDATE ingredients SET base_unit='ml' WHERE id=:id"),
                {"id": stocked["ingredient_id"]},
            )
        elif mutation == "cost":
            await s.execute(
                text("UPDATE batches SET cost_per_unit_minor=cost_per_unit_minor+1 WHERE id=:id"),
                {"id": stocked["batch_ids"][0]},
            )
        elif mutation == "initial_qty":
            await s.execute(
                text("UPDATE batches SET qty_initial=qty_initial+1 WHERE id=:id"),
                {"id": stocked["batch_ids"][0]},
            )
        elif mutation == "trail":
            await s.execute(
                text("UPDATE stock_movements SET note='changed' WHERE batch_id=:id"),
                {"id": stocked["batch_ids"][0]},
            )
        else:
            await s.execute(
                text(
                    "UPDATE pos_receipt_corrections "
                    "SET accounting_at=accounting_at-interval '1 second' "
                    "WHERE id=:id"
                ),
                {"id": kwargs["correction_id"]},
            )
        await original(s, **kwargs)

    monkeypatch.setattr(module, "restore_stock", tampered)
    with pytest.raises(
        (DBAPIError, ValueError),
        match=(
            "provenance|source|immutable|append-only|does not match|no-value corrected"
            "|inventory ownership"
        ),
    ):
        await commit_receipt_correction(
            session,
            tenant_for(seed_owner),
            payload=ReceiptStockCorrectionCommit(**body),
            idempotency_key=headers["Idempotency-Key"],
            confirmation_token=headers["X-Tender-Token"],
        )
        await session.commit()
    await session.rollback()
    assert await state(session, stocked) == before


@pytest.mark.parametrize("stocked", ["fractional"], indirect=True)
async def test_fractional_chronological_value_matches_ledger_reports_and_keeps_history(
    client, session, seed_owner, stocked
):
    from datetime import timedelta
    from zoneinfo import ZoneInfo

    from app.services.accounting.accounts import COST_OF_GOODS_SOLD
    from app.services.accounting.ledger import build_operational_ledger
    from app.services.inventory.accounting import load_inventory_value_changes
    from app.services.reports import ReportsAggregator

    batch = await session.get(Batch, stocked["batch_ids"][0])
    ingredient = await session.get(Ingredient, stocked["ingredient_id"])
    batch.qty_on_hand = Decimal(str(batch.qty_on_hand)) - Decimal("0.5")
    ingredient.current_qty = Decimal(str(ingredient.current_qty)) - Decimal("0.5")
    session.add(
        StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=batch.branch_id,
            type="sale",
            ref_type="order",
            ref_id=uuid4(),
            qty_delta=Decimal("-0.5"),
            cost_per_unit_minor=1,
        )
    )
    await session.commit()
    before = await load_inventory_value_changes(session, company_id=stocked["company_id"])
    before_values = {str(change.movement.id): change.inventory_delta_minor for change in before}
    assert sorted(before_values.values()) == [-1, 0]
    body, headers = await command(client, stocked)
    saved = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert saved.status_code == 201, saved.text
    result = saved.json()
    assert result["inventory_increase_minor"] == 1
    accounting_at = datetime.fromisoformat(result["accounting_at"])
    await session.rollback()
    historical = await load_inventory_value_changes(
        session, company_id=stocked["company_id"], end_exclusive=accounting_at
    )
    assert {
        str(change.movement.id): change.inventory_delta_minor for change in historical
    } == before_values
    after = await load_inventory_value_changes(
        session, company_id=stocked["company_id"], start_at=accounting_at
    )
    assert [change.inventory_delta_minor for change in after] == [1]
    now = datetime.now(UTC)
    ledger = await build_operational_ledger(
        session,
        company_id=stocked["company_id"],
        start_at=now - timedelta(days=1),
        end_exclusive=now + timedelta(seconds=1),
    )
    assert (
        sum(
            line.debit_minor - line.credit_minor
            for line in ledger
            if line.account_code == COST_OF_GOODS_SOLD.ledger_tuple[0]
        )
        == 0
    )
    report = await ReportsAggregator(session).aggregate_daily(
        company_id=stocked["company_id"],
        branch_id=UUID(stocked["branch_id"]),
        d=now.astimezone(ZoneInfo("Asia/Kolkata")).date(),
    )
    assert report.cogs_minor == 0
    assert report.receipt_corrections_minor == 4600
    assert report.net_revenue_minor == 0


@pytest.mark.parametrize("claim", ["actor", "branch", "auth_version", "expired", "missing"])
async def test_stock_commit_and_replay_require_current_bound_confirmation(
    client, session, seed_owner, stocked, claim
):
    import jwt

    from app.core.config import get_settings
    from app.core.security import _signing_key, decode_token

    body, headers = await command(client, stocked)
    saved = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert saved.status_code == 201, saved.text
    wrong = dict(headers)
    if claim == "missing":
        wrong.pop("X-Tender-Token")
    else:
        payload = decode_token(headers["X-Tender-Token"])
        if claim == "actor":
            payload["sub"] = str(uuid4())
        elif claim == "branch":
            payload["branch_id"] = str(uuid4())
        elif claim == "auth_version":
            payload["auth_version"] += 1
        else:
            payload["exp"] = 0
        wrong["X-Tender-Token"] = jwt.encode(
            payload, _signing_key(), algorithm=get_settings().jwt_algorithm
        )
    response = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=wrong, json=body
    )
    assert response.status_code == 401, response.text
    replay = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert replay.status_code == 201
    assert replay.json() == saved.json()


@pytest.mark.parametrize(
    "mutation", ["cost", "batch_balance", "ingredient_balance", "unrelated_ref"]
)
async def test_actual_malformed_source_is_refused_by_python_and_database(
    client, session, seed_owner, stocked, mutation
):
    if mutation == "cost":
        await session.execute(
            text(
                "UPDATE stock_movements SET cost_per_unit_minor=cost_per_unit_minor+1 "
                "WHERE ref_id=:id"
            ),
            {"id": UUID(stocked["order_id"])},
        )
    elif mutation == "batch_balance":
        await session.execute(
            text("UPDATE batches SET qty_on_hand=qty_on_hand+1 WHERE id=:id"),
            {"id": stocked["batch_ids"][0]},
        )
    elif mutation == "ingredient_balance":
        await session.execute(
            text("UPDATE ingredients SET current_qty=current_qty+1 WHERE id=:id"),
            {"id": stocked["ingredient_id"]},
        )
    else:
        await session.execute(
            text("UPDATE stock_movements SET ref_type='unrelated' WHERE ref_id=:id"),
            {"id": UUID(stocked["order_id"])},
        )
    await session.commit()
    value = await preview(client, stocked)
    assert not value["eligible"]
    with pytest.raises(DBAPIError, match="stock correction|consumption"):
        async with session.begin_nested():
            await session.execute(
                text(
                    "SELECT dcompany_assert_receipt_stock_source("
                    "dcompany_pos_receipt_stock_source(:order,:company),:order,:company)"
                ),
                {"order": UUID(stocked["order_id"]), "company": stocked["company_id"]},
            )


async def test_initial_insert_cannot_backdate_restore_before_captured_trail(
    client, session, seed_owner, stocked, monkeypatch
):
    from datetime import timedelta

    from app.models import PosReceiptCorrection
    from app.services.pos.receipt_corrections import (
        ReceiptStockCorrectionCommit,
        commit_receipt_correction,
    )
    from tests.integration.test_pos_receipt_corrections import tenant_for

    body, headers = await command(client, stocked)
    original_flush = session.flush

    async def backdated(*args, **kwargs):
        for row in session.new:
            if isinstance(row, PosReceiptCorrection):
                latest = max(
                    datetime.fromisoformat(m["created_at"])
                    for b in row.stock_source_snapshot["batches"]
                    for m in b["movements"]
                )
                at = latest - timedelta(microseconds=1)
                row.accounting_at = row.recorded_at = at
                row.response_payload = {**row.response_payload, "accounting_at": at.isoformat()}
        await original_flush(*args, **kwargs)

    monkeypatch.setattr(session, "flush", backdated)
    with pytest.raises(DBAPIError, match="cannot precede its captured trail"):
        await commit_receipt_correction(
            session,
            tenant_for(seed_owner),
            payload=ReceiptStockCorrectionCommit(**body),
            idempotency_key=headers["Idempotency-Key"],
            confirmation_token=headers["X-Tender-Token"],
        )
    await session.rollback()


@pytest.mark.parametrize("mutation", ["update", "delete"])
async def test_competing_historical_other_order_mutation_waits_and_then_refuses(
    client, session, seed_owner, stocked, monkeypatch, mutation
):
    import asyncio

    import app.services.pos.receipt_stock as module
    from app.core.db import AsyncSessionLocal

    batch = await session.get(Batch, stocked["batch_ids"][0])
    ingredient = await session.get(Ingredient, stocked["ingredient_id"])
    history_id = uuid4()
    session.add(
        StockMovement(
            id=history_id,
            batch_id=batch.id,
            branch_id=batch.branch_id,
            type="adjustment",
            qty_delta=1,
            cost_per_unit_minor=100,
        )
    )
    batch.qty_on_hand = Decimal(str(batch.qty_on_hand)) + 1
    ingredient.current_qty = Decimal(str(ingredient.current_qty)) + 1
    await session.commit()
    body, headers = await command(client, stocked)
    entered, release = asyncio.Event(), asyncio.Event()
    original = module.restore_stock

    async def paused(*args, **kwargs):
        entered.set()
        await release.wait()
        await original(*args, **kwargs)

    monkeypatch.setattr(module, "restore_stock", paused)
    correction = asyncio.create_task(
        client.post("/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body)
    )
    await asyncio.wait_for(entered.wait(), timeout=3)

    async def competing():
        async with AsyncSessionLocal() as other:
            query = (
                "UPDATE stock_movements SET note='changed concurrently' WHERE id=:id"
                if mutation == "update"
                else "DELETE FROM stock_movements WHERE id=:id"
            )
            try:
                await other.execute(text(query), {"id": history_id})
                await other.commit()
                return "unexpected commit"
            except DBAPIError as exc:
                await other.rollback()
                return str(exc)

    competitor = asyncio.create_task(competing())
    try:
        await asyncio.sleep(0.1)
        assert not competitor.done(), (
            "historical mutation must wait for correction's held inventory/FK locks"
        )
    finally:
        release.set()
    saved = await asyncio.wait_for(correction, timeout=5)
    assert saved.status_code == 201, saved.text
    refused = await asyncio.wait_for(competitor, timeout=5)
    assert "immutable" in refused


async def test_old_transaction_cannot_append_a_backdated_movement_after_correction(
    client, session, seed_owner, stocked
):
    from app.core.db import AsyncSessionLocal

    body, headers = await command(client, stocked)
    async with AsyncSessionLocal() as old:
        old_started = await old.scalar(text("SELECT now()"))
        saved = await client.post(
            "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
        )
        assert saved.status_code == 201, saved.text
        assert old_started < datetime.fromisoformat(saved.json()["accounting_at"])
        with pytest.raises(DBAPIError, match="predates retained restoration"):
            await old.execute(
                text("""INSERT INTO stock_movements(
                id,batch_id,branch_id,type,qty_delta,cost_per_unit_minor)
             VALUES(:id,:batch,:branch,'adjustment',1,100)"""),
                {
                    "id": uuid4(),
                    "batch": stocked["batch_ids"][0],
                    "branch": UUID(stocked["branch_id"]),
                },
            )
        await old.rollback()


async def test_normal_deduction_in_old_transaction_uses_statement_time_after_inventory_locks(
    client, session, seed_owner, stocked
):
    from app.core.db import AsyncSessionLocal
    from app.services.inventory.deduction import _deduct_ingredient

    body, headers = await command(client, stocked)
    async with AsyncSessionLocal() as old:
        before = await old.scalar(text("SELECT now()"))
        saved = await client.post(
            "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
        )
        assert saved.status_code == 201, saved.text
        at = datetime.fromisoformat(saved.json()["accounting_at"])
        assert before < at
        assert (
            await _deduct_ingredient(
                old,
                ingredient_id=stocked["ingredient_id"],
                branch_id=UUID(stocked["branch_id"]),
                qty_needed=Decimal("0.5"),
                order_id=uuid4(),
                created_by=None,
            )
            == 1
        )
        await old.commit()
        latest = await old.scalar(
            select(StockMovement.created_at)
            .where(StockMovement.batch_id.in_(stocked["batch_ids"]))
            .order_by(StockMovement.created_at.desc())
            .limit(1)
        )
        assert latest > at


async def test_stock_correction_audit_and_held_sanitized_outbox_are_exactly_once(
    client, session, seed_owner, stocked
):
    import json

    from app.models.google_sheets_delivery import GoogleSheetsDelivery

    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=stocked["headers"],
        json={"webhook_url": "https://script.google.com/macros/s/stock-correction-qa/exec"},
    )
    assert configured.status_code == 200, configured.text
    body, headers = await command(client, stocked)
    saved = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert saved.status_code == 201, saved.text
    repeated = await client.post(
        "/api/v1/pos/receipt-corrections/stock/commit", headers=headers, json=body
    )
    assert repeated.status_code == 201
    assert repeated.json() == saved.json()
    await session.rollback()
    rows = list(
        await session.scalars(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == stocked["company_id"],
                GoogleSheetsDelivery.source_type == "pos_receipt_correction",
            )
        )
    )
    assert len(rows) == 1
    assert rows[0].status == "pending"
    assert rows[0].payload["payout_minor"] == 0
    assert rows[0].payload["stock_restored"] is True
    assert rows[0].payload["inventory_delta_minor"] == 250
    assert rows[0].payload["cogs_delta_minor"] == -250
    assert body["reason"] not in json.dumps(rows[0].payload)
    assert "source_snapshot" not in rows[0].payload
    semantic_audit = await session.scalar(
        text(
            "SELECT count(*) FROM audit_log WHERE company_id=:company "
            "AND action='receipt_corrected_no_value' AND entity_id=:id"
        ),
        {"company": stocked["company_id"], "id": saved.json()["id"]},
    )
    assert semantic_audit == 1
