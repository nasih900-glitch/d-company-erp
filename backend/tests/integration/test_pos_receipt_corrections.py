"""Synthetic no-value corrections: no payout, immutable history, exact replay."""
# pytest fixture imports intentionally share their injection parameter names.
# ruff: noqa: F811

from __future__ import annotations

from datetime import UTC, datetime, time, timedelta
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.db import AsyncSessionLocal
from app.core.errors import ConflictError, ForbiddenError, IdempotencyConflict
from app.core.tenant import TenantContext
from app.models import (
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    PosReceiptCorrection,
    Shift,
)
from app.services.accounting.ledger import build_operational_ledger
from app.services.pos.receipt_corrections import ReceiptCorrectionCommit, commit_receipt_correction
from app.services.reports import ReportsAggregator
from tests.integration.test_pos_receipt_correction_preview import (
    paid_retained,  # noqa: F401
    set_role,
)

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]


def tenant_for(seed):
    return TenantContext(
        user_id=seed["owner"].id,
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        terminal_id=seed["terminal"].id,
        roles=("owner",),
        protected_access=True,
        audit_access=True,
    )


async def body_for(client, case):
    preview = await client.post(
        "/api/v1/pos/receipt-corrections/preview",
        headers=case["headers"],
        json={"order_id": case["order_id"]},
    )
    assert preview.status_code == 200, preview.text
    assert preview.json()["eligible"], preview.json()["blockers"]
    return ReceiptCorrectionCommit(
        order_id=case["order_id"],
        source_fingerprint=preview.json()["source_fingerprint"],
        no_funds_received=True,
        all_items_not_supplied=True,
        acknowledges_no_payout=True,
        reason="All products entered by mistake; no payment received",
    )


async def original_facts(session, case):
    return await session.scalar(
        text("""
        SELECT jsonb_build_object('order',to_jsonb(o),'shift',to_jsonb(s),
         'payments',(SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM payments p WHERE order_id=o.id),
         'lines',(SELECT jsonb_agg(to_jsonb(l) ORDER BY id) FROM order_lines l WHERE order_id=o.id),
         'void',(SELECT to_jsonb(v) FROM gaming_void_receipts v WHERE retained_order_id=o.id),
         'gaming',(SELECT to_jsonb(g) FROM gaming_sessions g WHERE retained_addon_order_id=o.id))
        FROM orders o JOIN shifts s ON s.id=o.shift_id WHERE o.id=:id
    """),
        {"id": UUID(case["order_id"])},
    )


async def business_table_hashes(session):
    tables = await session.scalars(
        text("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")
    )
    result = {}
    for table in tables:
        assert table.replace("_", "").isalnum()
        result[table] = await session.scalar(
            text(
                "SELECT md5(coalesce(jsonb_agg(to_jsonb(x) "  # noqa: S608 - validated catalog name
                "ORDER BY to_jsonb(x)::text)::text,'[]')) "
                f'FROM "{table}" x'
            )
        )
    return result


async def test_private_no_value_transaction_preserves_all_original_evidence_and_replay(
    client,
    session,
    seed_owner,
    paid_retained,
):
    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    before = await original_facts(session, paid_retained)
    table_hashes_before = await business_table_hashes(session)
    action = "no-value:" + str(uuid4())
    result = await commit_receipt_correction(session, tenant, payload=body, idempotency_key=action)
    await session.commit()
    assert result["amount_minor"] == 4600
    assert result["payout_minor"] == 0
    table_hashes_after = await business_table_hashes(session)
    changed_tables = {
        name
        for name in table_hashes_before
        if table_hashes_before[name] != table_hashes_after[name]
    }
    assert changed_tables == {"pos_receipt_corrections", "audit_log", "collection_source_guards"}
    assert await original_facts(session, paid_retained) == before
    repeated = await commit_receipt_correction(
        session, tenant, payload=body, idempotency_key=action
    )
    assert repeated == result
    await session.commit()
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PosReceiptCorrection)
            .where(PosReceiptCorrection.order_id == body.order_id)
        )
        == 1
    )
    for table in ("refunds", "pos_refund_requests", "stock_movements"):
        key = "ref_id" if table == "stock_movements" else "order_id"
        count = await session.scalar(
            text(
                f"SELECT count(*) FROM {table} WHERE {key}=:id"  # noqa: S608 - fixed test allowlist
            ),
            {"id": body.order_id},
        )
        assert count == 0
    history = await client.get(
        f"/api/v1/pos/receipts/{body.order_id}",
        headers=paid_retained["headers"],
    )
    assert history.status_code == 200, history.text
    receipt = history.json()
    assert receipt["status"] == "paid"
    assert receipt["paid_minor"] == 4600
    assert receipt["refunded_minor"] == 0
    assert receipt["refundable_minor"] == 0
    assert receipt["net_collected_minor"] == 0
    assert receipt["corrected_minor"] == 4600
    assert receipt["receipt_correction"]["payout_minor"] == 0
    now = datetime.now(UTC)
    ledger = await build_operational_ledger(
        session,
        company_id=tenant.company_id,
        start_at=now - timedelta(days=1),
        end_exclusive=now + timedelta(seconds=2),
    )
    reversal = [line for line in ledger if line.ref_type == "pos_receipt_correction"]
    assert len(reversal) == 4
    assert sum(line.debit_minor for line in reversal) == 9200
    assert sum(line.credit_minor for line in reversal) == 9200
    from app.services.accounting.accounts import (
        POS_SETTLEMENT_CLEARING,
        SALES_REVENUE,
        UPI_CLEARING,
    )

    for account in (POS_SETTLEMENT_CLEARING, SALES_REVENUE, UPI_CLEARING):
        assert (
            sum(
                line.debit_minor - line.credit_minor
                for line in ledger
                if line.account_code == account.ledger_tuple[0]
            )
            == 0
        )
    report = await ReportsAggregator(session).aggregate_daily(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        d=now.astimezone(__import__("zoneinfo").ZoneInfo("Asia/Kolkata")).date(),
    )
    assert report.gross_revenue_minor == 4600
    assert report.receipt_corrections_minor == 4600
    assert report.refunds_issued_minor == 0
    assert report.net_revenue_minor == 0
    assert report.net_payments_received_minor == 0
    assert report.original_orders_count == 1
    assert report.orders_count == 0
    assert report.avg_ticket_minor == 0
    assert report.effective_upi_received_minor == 0
    assert report.payments_received.upi_minor == 4600
    shifts = await client.get("/api/v1/pos/shifts", headers=paid_retained["headers"])
    assert shifts.status_code == 200, shifts.text
    shift = next(row for row in shifts.json() if row["id"] == paid_retained["shift_id"])
    assert shift["net_collections_minor"] == 4600
    assert shift["reconstructed_upi_collections_minor"] == 4600
    assert shift["effective_net_collections_minor"] == 0
    assert shift["effective_upi_collections_minor"] == 0
    assert shift["receipt_corrections_minor"] == 4600
    assert datetime.fromisoformat(shift["receipt_corrections"][0]["accounting_at"]) == (
        datetime.fromisoformat(result["accounting_at"])
    )


async def test_changed_key_body_or_source_fails_without_second_correction(
    client,
    session,
    seed_owner,
    paid_retained,
):
    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    action = str(uuid4())
    await commit_receipt_correction(session, tenant, payload=body, idempotency_key=action)
    await session.commit()
    changed = body.model_copy(update={"reason": "Different action evidence"})
    with pytest.raises(IdempotencyConflict):
        await commit_receipt_correction(session, tenant, payload=changed, idempotency_key=action)
    with pytest.raises(ConflictError):
        await commit_receipt_correction(session, tenant, payload=body, idempotency_key=str(uuid4()))


async def test_recorded_correction_cannot_update_delete_or_truncate(
    client,
    session,
    seed_owner,
    paid_retained,
):
    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    result = await commit_receipt_correction(
        session,
        tenant,
        payload=body,
        idempotency_key=str(uuid4()),
    )
    await session.commit()
    for command in (
        "UPDATE pos_receipt_corrections SET reason='changed' WHERE id=:id",
        "DELETE FROM pos_receipt_corrections WHERE id=:id",
        "TRUNCATE pos_receipt_corrections",
        "TRUNCATE pos_receipt_corrections CASCADE",
    ):
        expected = "append-only|cannot truncate a table referenced in a foreign key constraint" if command == "TRUNCATE pos_receipt_corrections" else "append-only"
        with pytest.raises(DBAPIError, match=expected) as refused:
            async with session.begin_nested():
                await session.execute(text(command), {"id": UUID(result["id"])})
        assert refused.value.orig.sqlstate in {"23514", "0A000"}
        assert await session.scalar(text("SELECT count(*) FROM pos_receipt_corrections WHERE id=:id"), {"id": UUID(result["id"])}) == 1


async def ordinary_paid_source(session, seed, source_day):
    """Valid anonymous, zero-tax invoice without Gaming or KDS delivery evidence."""
    headers = await set_role(session, seed, "super_owner")
    at = datetime.combine(source_day, time(12), ZoneInfo("Asia/Kolkata")).astimezone(UTC)
    shift = Shift(
        id=uuid4(),
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        terminal_id=seed["terminal"].id,
        opened_by=seed["owner"].id,
        opened_at=at - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    category = MenuCategory(
        id=uuid4(), company_id=seed["company"].id, name="Correction test products"
    )
    session.add_all([shift, category])
    await session.flush()
    item = MenuItem(
        id=uuid4(),
        company_id=seed["company"].id,
        category_id=category.id,
        sku="PRODUCT-" + uuid4().hex[:8],
        name="Plain product",
        type="drink",
        base_price_minor=1850,
        tax_rate=0,
        is_available=True,
    )
    session.add(item)
    await session.flush()
    order = Order(
        id=uuid4(),
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        terminal_id=seed["terminal"].id,
        opened_by=seed["owner"].id,
        shift_id=shift.id,
        type="takeaway",
        status="open",
        subtotal_minor=3700,
        total_minor=3700,
        opened_at=at,
    )
    session.add(order)
    await session.flush()
    session.add(
        OrderLine(
            id=uuid4(),
            order_id=order.id,
            menu_item_id=item.id,
            qty=2,
            menu_item_name_snapshot=item.name,
            menu_item_type_snapshot="drink",
            unit_price_minor=1850,
            line_total_minor=3700,
            taxable_value_minor=3700,
            tax_rate=0,
        )
    )
    await session.flush()
    order.status = "paid"
    order.closed_at = at
    order.invoice_issued_at = at
    order.invoice_no = "T/NV/" + uuid4().hex[:12]
    from app.services.reports.aggregator import fiscal_year_for_date

    order.fiscal_year = fiscal_year_for_date(source_day)
    await session.flush()
    session.add(
        Payment(
            id=uuid4(),
            order_id=order.id,
            shift_id=shift.id,
            recorded_by=seed["owner"].id,
            method="upi",
            amount_minor=3700,
            paid_at=at,
        )
    )
    await session.flush()
    shift.closed_by = seed["owner"].id
    shift.closed_at = at + timedelta(hours=1)
    shift.counted_minor = 0
    shift.variance_minor = 0
    shift.status = "closed"
    await session.commit()
    return {"order_id": str(order.id), "shift_id": str(shift.id), "headers": headers}


async def test_cross_month_correction_has_actual_period_movement_not_backdated_sales(
    client,
    session,
    seed_owner,
):
    from app.services.integrations.google_sheets_collections import calculate_daily_collections
    from app.services.reports.overview import load_overview_top_products

    today = datetime.now(ZoneInfo("Asia/Kolkata")).date()
    source_day = today.replace(day=1) - timedelta(days=1)
    case = await ordinary_paid_source(session, seed_owner, source_day)
    tenant = tenant_for(seed_owner)
    body = await body_for(client, case)
    parameters = {"c": tenant.company_id, "b": tenant.branch_id, "d": today}
    fingerprint_sql = text("SELECT dcompany_collection_fingerprint(:c,:b,:d,'Asia/Kolkata')")
    before_fingerprint = await session.scalar(fingerprint_sql, parameters)
    original = await original_facts(session, case)
    result = await commit_receipt_correction(
        session,
        tenant,
        payload=body,
        idempotency_key=str(uuid4()),
    )
    await session.commit()
    assert await original_facts(session, case) == original
    assert await session.scalar(fingerprint_sql, parameters) != before_fingerprint
    agg = ReportsAggregator(session)
    old = await agg.aggregate_daily(
        company_id=tenant.company_id, branch_id=tenant.branch_id, d=source_day
    )
    current = await agg.aggregate_daily(
        company_id=tenant.company_id, branch_id=tenant.branch_id, d=today
    )
    combined = await agg.aggregate(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        period_start=source_day,
        period_end=today,
        period="custom",
    )
    assert (old.gross_revenue_minor, old.net_revenue_minor, old.orders_count) == (3700, 3700, 1)
    assert old.receipt_corrections_minor == 0
    assert (current.gross_revenue_minor, current.net_revenue_minor) == (0, -3700)
    assert current.net_payments_received_minor == -3700
    assert current.orders_count == 0
    assert current.avg_ticket_minor == 0
    assert current.refunds_issued_minor == 0
    assert combined.gross_revenue_minor == 3700
    assert combined.net_revenue_minor == 0
    assert combined.orders_count == 0
    start = datetime.combine(source_day, time.min, ZoneInfo("Asia/Kolkata")).astimezone(UTC)
    end = datetime.combine(
        today + timedelta(days=1), time.min, ZoneInfo("Asia/Kolkata")
    ).astimezone(UTC)
    products = await load_overview_top_products(
        session,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        start_at=start,
        end_at=end,
    )
    assert products == ()
    snapshots = await calculate_daily_collections(
        session,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        start_date=source_day,
        end_date=today,
        timezone_name="Asia/Kolkata",
        now=datetime.now(UTC) + timedelta(days=2),
    )
    assert snapshots[0].upi_minor == 3700
    assert snapshots[-1].upi_minor == -3700
    assert snapshots[-1].receipt_corrections_minor == 3700
    totals = (
        await session.execute(
            text("SELECT * FROM dcompany_tender_totals(:c,:b,:d,'Asia/Kolkata')"), parameters
        )
    ).one()
    assert totals.upi_minor == snapshots[-1].upi_minor
    assert sum(row.total_minor for row in snapshots) == 0
    for path in ("/api/v1/reports/gstr1.csv", "/api/v1/reports/gstr3b.csv"):
        exported = await client.get(
            path, headers=case["headers"], params={"yyyy_mm": today.strftime("%Y-%m")}
        )
        assert exported.status_code == 200, exported.text
        assert "No-value correction" in exported.text
        assert "-37.00" in exported.text
        assert result["invoice_no"] in exported.text


@pytest.mark.parametrize(
    "paid_retained", [[2], {"quantities": [2], "tax_rate": "0.4"}], indirect=True
)
async def test_one_addon_quantity_two_retains_exact_database_bijection(
    client,
    session,
    seed_owner,
    paid_retained,
):
    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    before = await original_facts(session, paid_retained)
    assert len(before["lines"]) == 1
    assert before["lines"][0]["qty"] == 2
    result = await commit_receipt_correction(
        session, tenant, payload=body, idempotency_key=str(uuid4())
    )
    await session.commit()
    assert result["amount_minor"] == 4600
    assert await original_facts(session, paid_retained) == before
    # Both original and adjustment must hit the exact configured rate bucket.
    # The company is unregistered: pricing snapshots retain 0.4 but charge zero.
    import csv
    import io

    rate = before["lines"][0]["tax_rate"]
    assert all(
        before["lines"][0][key] == 0
        for key in ("cgst_minor", "sgst_minor", "igst_minor", "cess_minor")
    )
    exported = await client.get(
        "/api/v1/reports/gstr1.csv",
        headers=paid_retained["headers"],
        params={"yyyy_mm": datetime.now(ZoneInfo("Asia/Kolkata")).strftime("%Y-%m")},
    )
    assert exported.status_code == 200, exported.text
    rows = list(csv.reader(io.StringIO(exported.text)))
    assert not any(row and row[0] == "OE" for row in rows), rows
    assert "-46.00" in exported.text
    assert rate in (0, 0.4)


async def test_parallel_same_action_recovers_one_receipt_and_changed_actions_do_not_double_post(
    client,
    session,
    seed_owner,
    paid_retained,
):
    import asyncio

    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    action = str(uuid4())
    await session.rollback()

    async def run(key):
        async with AsyncSessionLocal() as other:
            result = await commit_receipt_correction(
                other, tenant, payload=body, idempotency_key=key
            )
            await other.commit()
            return result

    results = await asyncio.wait_for(asyncio.gather(run(action), run(action)), timeout=10)
    assert results[0] == results[1]
    with pytest.raises(ConflictError):
        await run(str(uuid4()))
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PosReceiptCorrection)
            .where(PosReceiptCorrection.order_id == body.order_id)
        )
        == 1
    )


@pytest.mark.parametrize(
    ("field", "changed"),
    [
        ("response_extra", "invented output fact"),
        ("amount_minor", 1),
        ("no_funds_received", False),
        ("all_items_not_supplied", False),
        ("acknowledges_no_payout", False),
        ("source_fingerprint", "0" * 64),
        ("request_hash", "0" * 64),
        ("branch_id", "00000000-0000-0000-0000-000000000001"),
        ("original_shift_id", "00000000-0000-0000-0000-000000000001"),
    ],
)
async def test_raw_database_forgery_cannot_bypass_exact_source_or_attestations(
    client,
    session,
    seed_owner,
    paid_retained,
    field,
    changed,
):
    import json

    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    result = await commit_receipt_correction(
        session, tenant, payload=body, idempotency_key=str(uuid4())
    )
    raw = await session.scalar(
        text("SELECT to_jsonb(c) FROM pos_receipt_corrections c WHERE id=:id"),
        {"id": UUID(result["id"])},
    )
    await session.rollback()
    if field == "response_extra":
        raw["response_payload"]["extra"] = changed
    else:
        raw[field] = changed
    with pytest.raises(DBAPIError):
        async with session.begin_nested():
            await session.execute(
                text(
                    "INSERT INTO pos_receipt_corrections SELECT "
                    "(jsonb_populate_record(NULL::pos_receipt_corrections,CAST(:row AS jsonb))).*"
                ),
                {"row": json.dumps(raw)},
            )
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PosReceiptCorrection)
            .where(PosReceiptCorrection.order_id == body.order_id)
        )
        == 0
    )


async def test_corrected_receipt_cannot_reserve_refund_or_change_tender_at_api_and_database(
    client,
    session,
    seed_owner,
    paid_retained,
):
    from app.core.errors import BusinessRuleError
    from app.services.pos.receipt_correction_projection import require_uncorrected_receipt

    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    await commit_receipt_correction(session, tenant, payload=body, idempotency_key=str(uuid4()))
    await session.commit()
    with pytest.raises(BusinessRuleError, match="cannot be refunded or reclassified"):
        await require_uncorrected_receipt(
            session, company_id=tenant.company_id, order_id=body.order_id
        )
    action = str(uuid4())
    refund = await client.post(
        "/api/v1/pos/refund-requests",
        headers={
            **paid_retained["headers"],
            "Idempotency-Key": action,
            "X-Client-Action-Id": action,
        },
        json={
            "order_id": str(body.order_id),
            "shift_id": paid_retained["shift_id"],
            "reason_code": "MISTAKEN_SALE",
            "amount_minor": 4600,
            "expected_paid_minor": 4600,
            "expected_refundable_minor": 4600,
            "mode": "original",
            "client_action_id": action,
        },
    )
    assert refund.status_code == 422, refund.text
    assert "cannot be refunded or reclassified" in refund.text
    for table in ("pos_refund_requests", "refunds", "payment_tender_corrections"):
        with pytest.raises(DBAPIError, match="cannot create another financial"):
            async with session.begin_nested():
                await session.execute(
                    text(
                        f"INSERT INTO {table}(id,order_id) VALUES(:id,:order_id)"  # noqa: S608
                    ),
                    {"id": uuid4(), "order_id": body.order_id},
                )


async def test_distinct_mirror_event_is_held_sanitized_and_exactly_once(
    client,
    session,
    seed_owner,
    paid_retained,
):
    import json

    from app.models.google_sheets_delivery import GoogleSheetsDelivery

    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=paid_retained["headers"],
        json={"webhook_url": "https://script.google.com/macros/s/no-value-test/exec"},
    )
    assert configured.status_code == 200, configured.text
    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    action = str(uuid4())
    result = await commit_receipt_correction(session, tenant, payload=body, idempotency_key=action)
    await session.commit()
    repeated = await commit_receipt_correction(
        session, tenant, payload=body, idempotency_key=action
    )
    await session.commit()
    assert repeated == result
    rows = list(
        await session.scalars(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == tenant.company_id,
                GoogleSheetsDelivery.source_type == "pos_receipt_correction",
            )
        )
    )
    assert len(rows) == 1
    assert rows[0].status == "pending"
    assert rows[0].event_type == "pos.receipt.corrected"
    assert rows[0].payload["payout_minor"] == 0
    assert rows[0].payload["revenue_delta_minor"] == -4600
    assert rows[0].payload["upi_delta_minor"] == -4600
    assert body.reason not in json.dumps(rows[0].payload)
    assert "source_snapshot" not in rows[0].payload
    assert "customer" not in rows[0].payload


async def test_outbox_failure_rolls_back_correction_audit_and_source_fence(
    client,
    session,
    seed_owner,
    paid_retained,
    monkeypatch,
):
    import app.services.integrations.google_sheets_mirror as mirror

    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    original = await original_facts(session, paid_retained)
    before = await session.scalar(
        text("SELECT generation FROM collection_source_guards WHERE company_id=:c"),
        {"c": tenant.company_id},
    )

    async def fail(*args, **kwargs):
        raise RuntimeError("Synthetic outbox persistence failure")

    monkeypatch.setattr(mirror, "enqueue_google_sheets_event_if_enabled", fail)
    with pytest.raises(RuntimeError, match="outbox persistence failure"):
        await commit_receipt_correction(session, tenant, payload=body, idempotency_key=str(uuid4()))
    await session.rollback()
    assert await original_facts(session, paid_retained) == original
    assert (
        await session.scalar(
            text("SELECT generation FROM collection_source_guards WHERE company_id=:c"),
            {"c": tenant.company_id},
        )
        == before
    )
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PosReceiptCorrection)
            .where(PosReceiptCorrection.order_id == body.order_id)
        )
        == 0
    )


async def test_company_configuration_first_invalidates_waiting_correction_without_deadlock(
    client,
    session,
    seed_owner,
    paid_retained,
    monkeypatch,
):
    import asyncio

    import app.services.pos.receipt_corrections as corrections

    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    await session.rollback()
    reached = asyncio.Event()
    original_lock = corrections.lock_receipt_company

    async def observed_lock(other, company_id):
        reached.set()
        await original_lock(other, company_id)

    monkeypatch.setattr(corrections, "lock_receipt_company", observed_lock)

    async def correct():
        async with AsyncSessionLocal() as other:
            await commit_receipt_correction(
                other, tenant, payload=body, idempotency_key=str(uuid4())
            )
            await other.commit()

    async with AsyncSessionLocal() as config:
        await config.execute(
            text("UPDATE companies SET timezone='UTC' WHERE id=:c"),
            {"c": tenant.company_id},
        )
        task = asyncio.create_task(correct())
        await asyncio.wait_for(reached.wait(), timeout=5)
        await config.commit()
        with pytest.raises(ConflictError, match="evidence changed"):
            await asyncio.wait_for(task, timeout=5)


async def test_correction_serializes_tender_and_refund_attempts_on_original_order(
    client,
    session,
    seed_owner,
    paid_retained,
    monkeypatch,
):
    import asyncio

    import app.services.pos.receipt_corrections as corrections
    from app.api.v1.finance.tender_corrections import payment_context, payment_eligible
    from app.core.errors import BusinessRuleError

    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    payment_id = await session.scalar(select(Payment.id).where(Payment.order_id == body.order_id))
    await session.rollback()
    acquired = asyncio.Event()
    release = asyncio.Event()
    original_lock = corrections.lock_receipt_company

    async def paused_lock(other, company_id):
        await original_lock(other, company_id)
        acquired.set()
        await asyncio.wait_for(release.wait(), timeout=5)

    monkeypatch.setattr(corrections, "lock_receipt_company", paused_lock)

    async def correct():
        async with AsyncSessionLocal() as other:
            result = await commit_receipt_correction(
                other, tenant, payload=body, idempotency_key=str(uuid4())
            )
            await other.commit()
            return result

    async def tender():
        async with AsyncSessionLocal() as other:
            payment, order, shift = await payment_context(other, tenant, payment_id, lock=True)
            await payment_eligible(other, payment, order, shift)

    async def refund():
        action = str(uuid4())
        return await client.post(
            "/api/v1/pos/refund-requests",
            headers={
                **paid_retained["headers"],
                "Idempotency-Key": action,
                "X-Client-Action-Id": action,
            },
            json={
                "order_id": str(body.order_id),
                "shift_id": paid_retained["shift_id"],
                "reason_code": "MISTAKEN_SALE",
                "amount_minor": 4600,
                "expected_paid_minor": 4600,
                "expected_refundable_minor": 4600,
                "mode": "original",
                "client_action_id": action,
            },
        )

    task = asyncio.create_task(correct())
    await asyncio.wait_for(acquired.wait(), timeout=5)
    tender_task = asyncio.create_task(tender())
    refund_task = asyncio.create_task(refund())
    release.set()
    await asyncio.wait_for(task, timeout=5)
    with pytest.raises(BusinessRuleError, match="cannot be refunded or reclassified"):
        await asyncio.wait_for(tender_task, timeout=5)
    rejected_refund = await asyncio.wait_for(refund_task, timeout=5)
    assert rejected_refund.status_code == 422
    assert "cannot be refunded or reclassified" in rejected_refund.text


@pytest.mark.parametrize("role", ["owner", "manager", "store_staff"])
async def test_current_protected_assignment_required_for_private_and_raw_writes(
    client,
    session,
    seed_owner,
    paid_retained,
    role,
):
    import json

    tenant = tenant_for(seed_owner)
    body = await body_for(client, paid_retained)
    result = await commit_receipt_correction(
        session, tenant, payload=body, idempotency_key=str(uuid4())
    )
    raw = await session.scalar(
        text("SELECT to_jsonb(c) FROM pos_receipt_corrections c WHERE id=:id"),
        {"id": UUID(result["id"])},
    )
    await session.rollback()
    for item in ("company", "owner", "branch", "terminal"):
        await session.refresh(seed_owner[item])
    await set_role(session, seed_owner, role)
    # Old high-trust claims cannot survive removal of the persisted authority.
    with pytest.raises(ForbiddenError):
        await commit_receipt_correction(session, tenant, payload=body, idempotency_key=str(uuid4()))
    await session.rollback()
    with pytest.raises(DBAPIError):
        async with session.begin_nested():
            await session.execute(
                text(
                    "INSERT INTO pos_receipt_corrections SELECT "
                    "(jsonb_populate_record(NULL::pos_receipt_corrections,CAST(:row AS jsonb))).*"
                ),
                {"row": json.dumps(raw)},
            )
    assert (
        await session.scalar(
            select(text("count(*)"))
            .select_from(PosReceiptCorrection)
            .where(PosReceiptCorrection.order_id == body.order_id)
        )
        == 0
    )
