"""Actual PostgreSQL regression contracts for retained Gaming mistake corrections."""

from __future__ import annotations
import asyncio
from dataclasses import asdict
from datetime import UTC, datetime, timedelta
from sqlalchemy.exc import DBAPIError
from uuid import UUID, uuid4
import pytest
import pytest_asyncio
from sqlalchemy import text
from tests.integration.test_gaming_session_addons import _seed_live_session, _login, _headers
from app.models import Branch, Terminal
from app.core.security import issue_access_token

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]


@pytest_asyncio.fixture
async def retained(client, session, seed_owner):
    shift, station, drink, gaming = await _seed_live_session(
        session, seed_owner, drink_price_minor=8000
    )
    await session.commit()
    token = await _login(client, seed_owner)
    h = _headers(seed_owner, token)
    added = await client.post(
        f"/api/v1/gaming/sessions/{gaming.id}/addons",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            "client_line_id": str(uuid4()),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 8000,
        },
    )
    assert added.status_code == 201, added.text
    cancelled = await client.post(
        f"/api/v1/gaming/sessions/{gaming.id}/cancel",
        headers={**h, "Idempotency-Key": str(uuid4())},
        json={
            "reason": "Original session cancellation",
            "expected_order_id": None,
            "retained_target_shift_id": str(shift.id),
            "expected_billing_revision": 0,
        },
    )
    assert cancelled.status_code == 200, cancelled.text
    oid = cancelled.json()["retained_addon_order_id"]
    path = f"/api/v1/pos/orders/{oid}"
    claimed = await client.post(path + "/checkout-claim", headers=h)
    assert claimed.status_code == 201, claimed.text
    h = {**h, "X-Checkout-Claim": claimed.json()["claim_token"]}
    reviewed = await client.get(path + "/retained-mistake-review", headers=h)
    assert reviewed.status_code == 200, reviewed.text
    review = reviewed.json()
    payload = {
        k: review[k] for k in ("original_receipt_id", "expected_checkout_version", "review_hash")
    }
    payload.update(
        all_items_mistaken_not_supplied=True,
        reason="All items were entered by mistake and never supplied",
    )
    return {
        "path": path,
        "oid": oid,
        "sid": str(gaming.id),
        "rid": review["original_receipt_id"],
        "h": h,
        "payload": payload,
        "key": str(uuid4()),
        "seed": {
            "owner_id": seed_owner["owner"].id,
            "company_id": seed_owner["company"].id,
            "branch_id": seed_owner["branch"].id,
            "auth_version": seed_owner["owner"].auth_version,
        },
        "shift": str(shift.id),
    }


async def snap(session, w):
    await session.rollback()
    rows = {}
    for name, table, where, ident in [
        ("order", "orders", "id", w["oid"]),
        ("lines", "order_lines", "order_id", w["oid"]),
        ("receipt", "gaming_void_receipts", "id", w["rid"]),
        ("mapping", "gaming_void_addon_lines", "receipt_id", w["rid"]),
        ("session", "gaming_sessions", "id", w["sid"]),
        ("addons", "gaming_session_addons", "gaming_session_id", w["sid"]),
        ("shift", "shifts", "id", w["shift"]),
    ]:
        rows[name] = (
            await session.execute(
                text(
                    f"SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id),'[]'::jsonb) FROM {table} x WHERE {where}=:id"
                ),
                {"id": UUID(ident)},
            )
        ).scalar_one()
    for table, col in [
        ("payments", "order_id"),
        ("refunds", "order_id"),
        ("stock_movements", "ref_id"),
        ("gaming_retained_bill_corrections", "retained_order_id"),
    ]:
        rows[table] = (
            await session.execute(
                text(f"SELECT count(*) FROM {table} WHERE {col}=:id"), {"id": UUID(w["oid"])}
            )
        ).scalar_one()
    await session.rollback()
    return rows


async def confirm(client, w, **kw):
    return await client.post(
        w["path"] + "/retained-mistake-correction",
        headers={**w["h"], "Idempotency-Key": kw.get("key", w["key"])},
        json=kw.get("payload", w["payload"]),
    )


async def test_retained_correction_preserves_history_cold_replay_and_queue(
    client, session, retained
):
    w = retained
    before = await snap(session, w)
    assert before["order"][0]["total_minor"] == 8000
    queued = await client.get("/api/v1/pos/orders?status=held", headers=w["h"])
    assert w["oid"] in {x["id"] for x in queued.json()}
    r = await confirm(client, w)
    assert r.status_code == 200, r.text
    after = await snap(session, w)
    for k in ("lines", "receipt", "mapping", "session", "addons", "shift"):
        assert before[k] == after[k], k
    b = dict(before["order"][0])
    a = dict(after["order"][0])
    assert (b.pop("status"), a.pop("status")) == ("held", "void")
    assert a.pop("checkout_version") == b.pop("checkout_version") + 1
    b.pop("updated_at")
    a.pop("updated_at")
    assert a == b
    assert [after[x] for x in ("payments", "refunds", "stock_movements")] == [0, 0, 0]
    assert after["gaming_retained_bill_corrections"] == 1
    queued = await client.get("/api/v1/pos/orders?status=held", headers=w["h"])
    assert w["oid"] not in {x["id"] for x in queued.json()}
    assert (await confirm(client, w)).json() == r.json()
    await session.execute(text("DELETE FROM idempotency_keys WHERE key=:key"), {"key": w["key"]})
    await session.commit()
    replay = await confirm(client, w)
    assert replay.status_code == 200 and replay.json() == r.json(), replay.text
    assert (await confirm(client, w, key=str(uuid4()))).status_code == 409
    assert (
        await confirm(
            client,
            w,
            payload={**w["payload"], "reason": "Different reason for same immutable command"},
        )
    ).status_code == 409
    assert await snap(session, w) == after


@pytest.mark.parametrize("change", ["version", "receipt", "hash", "false", "reason"])
async def test_changed_review_or_invalid_attestation_denied(client, session, retained, change):
    w = retained
    before = await snap(session, w)
    p = dict(w["payload"])
    expected = 409
    if change == "version":
        p["expected_checkout_version"] += 1
    elif change == "receipt":
        p["original_receipt_id"] = str(uuid4())
    elif change == "hash":
        p["review_hash"] = "f" * 64
    elif change == "false":
        p["all_items_mistaken_not_supplied"] = False
        expected = 422
    else:
        p["reason"] = "   "
        expected = 422
    r = await confirm(client, w, payload=p)
    assert r.status_code == expected, r.text
    assert await snap(session, w) == before


@pytest.mark.parametrize(
    "change", ["nonowner", "terminal", "branch", "crosscompany", "claim", "noclaim"]
)
async def test_scope_claim_denied(client, session, retained, change):
    w = retained
    before = await snap(session, w)
    h = dict(w["h"])
    s = w["seed"]
    expected = {401, 403, 404, 409, 422}
    if change in ("nonowner", "crosscompany"):
        h["Authorization"] = "Bearer " + issue_access_token(
            user_id=s["owner_id"],
            company_id=uuid4() if change == "crosscompany" else s["company_id"],
            roles=["manager"],
            auth_version=s["auth_version"],
        )
    elif change in ("terminal", "branch"):
        branch_id = s["branch_id"]
        if change == "branch":
            branch = Branch(
                id=uuid4(),
                company_id=s["company_id"],
                name="Other branch",
                invoice_series_code="OT",
            )
            session.add(branch)
            await session.flush()
            branch_id = branch.id
        terminal = Terminal(
            id=uuid4(),
            branch_id=branch_id,
            name="Other till",
            device_id=str(uuid4()),
            purpose="hybrid",
            is_active=(change != "terminal"),
        )
        session.add(terminal)
        await session.commit()
        h["X-Terminal-Id"] = str(terminal.id)
    elif change == "claim":
        h["X-Checkout-Claim"] = "bad-claim"
    else:
        h.pop("X-Checkout-Claim")
    for suffix, method in [
        ("/retained-mistake-review", "get"),
        ("/retained-mistake-correction", "post"),
    ]:
        args = {"headers": {**h, "Idempotency-Key": str(uuid4())}}
        if method == "post":
            args["json"] = w["payload"]
        r = await getattr(client, method)(w["path"] + suffix, **args)
        assert r.status_code in expected, (change, r.status_code, r.text)
    assert await snap(session, w) == before


async def test_concurrent_repeat_serializes_single_receipt(client, session, retained):
    w = retained
    results = await asyncio.gather(confirm(client, w), confirm(client, w))
    assert all(r.status_code in (200, 409) for r in results), [r.text for r in results]
    accepted = next(r for r in results if r.status_code == 200)
    for response in results:
        if response.status_code == 409:
            assert response.json()["error"]["code"] == "idempotency_in_progress"
        else:
            assert response.json() == accepted.json()
    assert (await confirm(client, w)).json() == accepted.json()
    s = await snap(session, w)
    assert s["gaming_retained_bill_corrections"] == 1


async def test_payment_race_exactly_one_winner(client, session, retained):
    w = retained
    pay = client.post(
        w["path"] + "/payments",
        headers={**w["h"], "Idempotency-Key": str(uuid4())},
        json={
            "method": "upi",
            "amount_minor": 8000,
            "expected_order_total_minor": 8000,
            "expected_due_minor": 8000,
            "ref_external": str(uuid4()),
        },
    )
    correction, payment = await asyncio.gather(confirm(client, w), pay)
    assert (correction.status_code == 200) + (payment.status_code == 201) == 1, [
        (r.status_code, r.text) for r in (correction, payment)
    ]
    s = await snap(session, w)
    assert (s["payments"], s["gaming_retained_bill_corrections"]) in ((1, 0), (0, 1))


async def test_direct_db_void_and_correction_mutation_blocked(client, session, retained):
    w = retained
    with pytest.raises(Exception):
        await session.execute(
            text("UPDATE orders SET status='void' WHERE id=:id"), {"id": UUID(w["oid"])}
        )
        await session.commit()
    await session.rollback()
    r = await confirm(client, w)
    assert r.status_code == 200, r.text
    for sql in [
        "UPDATE gaming_retained_bill_corrections SET reason='rewritten' WHERE retained_order_id=:id",
        "DELETE FROM gaming_retained_bill_corrections WHERE retained_order_id=:id",
        "UPDATE orders SET total_minor=0 WHERE id=:id",
        "UPDATE order_lines SET qty=2 WHERE order_id=:id",
    ]:
        with pytest.raises(Exception):
            await session.execute(text(sql), {"id": UUID(w["oid"])})
            await session.commit()
        await session.rollback()


async def test_discount_domain_error_and_financial_report_unchanged(client, session, retained):
    from app.services.reports import ReportsAggregator
    from app.core.timezone import local_today

    w = retained

    async def report():
        r = await ReportsAggregator(session).aggregate_daily(
            company_id=w["seed"]["company_id"],
            branch_id=w["seed"]["branch_id"],
            d=local_today("Asia/Kolkata"),
        )
        await session.rollback()
        return asdict(r)

    before = await report()
    original = await snap(session, w)
    for amount in (0, 8000):
        r = await client.patch(
            w["path"] + "/discount",
            headers={**w["h"], "Idempotency-Key": str(uuid4())},
            json={
                "manual_discount_minor": amount,
                "expected_checkout_version": w["payload"]["expected_checkout_version"],
            },
        )
        assert r.status_code == 422, r.text
        assert "immutable" in r.json()["error"]["message"].lower(), r.text
    assert await snap(session, w) == original
    r = await confirm(client, w)
    assert r.status_code == 200, r.text
    assert await report() == before


async def test_paid_settlement_denied(client, session, retained):
    w = retained
    pay = await client.post(
        w["path"] + "/payments",
        headers={**w["h"], "Idempotency-Key": str(uuid4())},
        json={
            "method": "upi",
            "amount_minor": 8000,
            "expected_order_total_minor": 8000,
            "expected_due_minor": 8000,
            "ref_external": str(uuid4()),
        },
    )
    assert pay.status_code == 201, pay.text
    before = await snap(session, w)
    review = await client.get(w["path"] + "/retained-mistake-review", headers=w["h"])
    assert review.status_code == 422, review.text
    correction = await confirm(client, w)
    assert correction.status_code == 422, correction.text
    assert await snap(session, w) == before


@pytest.mark.parametrize(
    "field",
    [
        "invoice_no",
        "invoice_issued_at",
        "fiscal_year",
        "irn",
        "irn_ack_no",
        "irn_acknowledged_at",
        "e_invoice_qr",
    ],
)
async def test_invoice_evidence_denied(client, session, retained, field):
    w = retained
    values = {
        "invoice_no": "NEVER-ISSUED-SYNTHETIC-1",
        "invoice_issued_at": datetime.now(UTC),
        "fiscal_year": "2026-27",
        "irn": "a" * 64,
        "irn_ack_no": "123",
        "irn_acknowledged_at": datetime.now(UTC),
        "e_invoice_qr": "synthetic",
    }
    if field in {"invoice_no", "invoice_issued_at"}:
        # Existing invoice pairing/final-payment guards reject this impossible state.
        with pytest.raises(DBAPIError):
            await session.execute(
                text(f"UPDATE orders SET {field}=:value WHERE id=:id"),
                {"value": values[field], "id": UUID(w["oid"])},
            )
            await session.commit()
        await session.rollback()
        return
    # Settlement metadata allowed on legacy rows must still deny mistake correction.
    await session.execute(
        text(f"UPDATE orders SET {field}=:value WHERE id=:id"),
        {"value": values[field], "id": UUID(w["oid"])},
    )
    await session.commit()
    before = await snap(session, w)
    r = await client.get(w["path"] + "/retained-mistake-review", headers=w["h"])
    assert r.status_code == 422, r.text
    r = await confirm(client, w)
    assert r.status_code == 422, r.text
    assert await snap(session, w) == before


async def test_unpaid_source_integrity_marker_cannot_be_forged(session, retained):
    await session.execute(
        text("UPDATE orders SET source_integrity_revision=48 WHERE id=:id"),
        {"id": UUID(retained["oid"])},
    )
    await session.commit()
    marker = (
        await session.execute(
            text("SELECT source_integrity_revision FROM orders WHERE id=:id"),
            {"id": UUID(retained["oid"])},
        )
    ).scalar_one()
    assert marker is None  # 0048 normalizes unpaid drafts; only final history is marked.


@pytest.mark.parametrize("claim_change", ["expired", "stale"])
async def test_expired_or_stale_checkout_is_rejected(client, session, retained, claim_change):
    w = retained
    if claim_change == "expired":
        await session.execute(
            text("UPDATE order_checkout_claims SET expires_at=:at WHERE order_id=:id"),
            {"at": datetime.now(UTC) - timedelta(minutes=1), "id": UUID(w["oid"])},
        )
    else:
        await session.execute(
            text(
                "UPDATE order_checkout_claims SET order_version=order_version+1 WHERE order_id=:id"
            ),
            {"id": UUID(w["oid"])},
        )
    await session.commit()
    before = await snap(session, w)
    result = await confirm(client, w)
    assert result.status_code == 409, result.text
    assert await snap(session, w) == before
