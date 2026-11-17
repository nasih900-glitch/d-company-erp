"""Owned disposable PostgreSQL proof: intents reserve; only real Refund posts money."""

from __future__ import annotations

import asyncio
import json
from datetime import UTC
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.core.db import AsyncSessionLocal
from app.core.security import issue_tender_token
from app.models import (
    Order,
    PaidBillAmendmentIntent,
    PosRefundRequest,
    Refund,
)
from tests.integration.test_pos_receipt_correction_preview import set_role
from tests.integration.test_pos_refund_settlement import _seed_case

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
BASE = "/api/v1/pos/paid-bill-amendments"


@pytest_asyncio.fixture
async def paid(client, session, seed_owner, request):
    from dataclasses import replace

    from app.models import Customer, MenuCategory, MenuItem, OrderLine
    from app.services.audit.recorder import install_audit_listeners
    from app.services.pos.pricing import LineRequest, OrderPricingService, apply_manual_discount

    install_audit_listeners()
    options = getattr(request, "param", "cash")
    settings = options if isinstance(options, dict) else {"method": options}
    method = settings.get("method", "cash")
    case = await _seed_case(session, seed_owner, payment_method=method, defer_settlement=True)
    seed_owner["company"].gstin = "32ABCDE1234F1Z5"
    seed_owner["branch"].state_code = "32"
    category = MenuCategory(
        id=uuid4(), company_id=case.company_id, name="Paid amendment proof", sort_order=0
    )
    session.add(category)
    await session.flush()
    item = MenuItem(
        id=uuid4(),
        company_id=case.company_id,
        category_id=category.id,
        sku="PAID-" + uuid4().hex[:10],
        name="Captured paid item",
        type="food",
        base_price_minor=settings.get("base_minor", case.amount_minor),
        tax_rate=settings.get("tax_rate", "0"),
        price_includes_tax=True,
        is_available=True,
    )
    session.add(item)
    await session.commit()
    priced = await OrderPricingService(session).price_order(
        company_id=case.company_id,
        branch_id=case.branch_id,
        customer_id=case.customer_id,
        line_requests=[LineRequest(menu_item_id=item.id, qty=1)],
    )
    order = await session.get(Order, case.order_id)
    order.subtotal_minor = priced.subtotal_taxable_minor
    for field in (
        "cgst_minor",
        "sgst_minor",
        "igst_minor",
        "cess_minor",
        "discount_minor",
        "round_off_minor",
        "total_minor",
    ):
        setattr(order, field, getattr(priced, field))
    order.tax_minor = priced.cgst_minor + priced.sgst_minor + priced.igst_minor + priced.cess_minor
    order.manual_discount_minor, order.discount_minor, order.total_minor = apply_manual_discount(
        line_discount_total_minor=priced.discount_minor,
        manual_discount_minor=settings.get("manual_discount_minor", 0),
        rounded_total_minor=priced.total_minor,
    )
    case = replace(case, amount_minor=order.total_minor)
    line = priced.lines[0]
    session.add(
        OrderLine(
            id=uuid4(),
            order_id=case.order_id,
            menu_item_id=item.id,
            qty=line.qty,
            unit_price_minor=line.unit_inclusive_minor,
            line_total_minor=line.line_inclusive_minor,
            taxable_value_minor=line.taxable_value_minor,
            tax_rate=line.tax_rate,
            hsn_or_sac=line.hsn_or_sac,
            cgst_minor=line.cgst_minor,
            sgst_minor=line.sgst_minor,
            igst_minor=line.igst_minor,
            cess_minor=line.cess_minor,
            discount_minor=line.discount_minor,
            kitchen_status="served",
            kitchen_round_no=1,
            kitchen_released_at=case.captured_at,
            kitchen_served_at=case.captured_at,
        )
    )
    await session.commit()
    await set_role(session, seed_owner, "super_owner")
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200, login.text
    headers = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(case.terminal_id),
    }
    if settings.get("points", 0):
        customer = await session.get(Customer, case.customer_id)
        customer.loyalty_points = settings["points"]
        await session.commit()
        redemption = await client.patch(
            f"/api/v1/pos/orders/{case.order_id}/points",
            json={"points": settings["points"], "expected_program_revision": 0},
            headers={**headers, "Idempotency-Key": "paid-points:" + str(uuid4())},
        )
        assert redemption.status_code == 200, redemption.text
        case = replace(case, amount_minor=redemption.json()["total_minor"])
    pay = {
        "method": method,
        "amount_minor": case.amount_minor,
        "expected_order_total_minor": case.amount_minor,
        "expected_due_minor": case.amount_minor,
    }
    if method == "cash":
        pay["tendered_minor"] = case.amount_minor
    reply = await client.post(
        f"/api/v1/pos/orders/{case.order_id}/payments",
        json=pay,
        headers={**headers, "Idempotency-Key": "paid-fixture:" + str(uuid4())},
    )
    assert reply.status_code == 201, reply.text
    unlock = await client.post(
        BASE + "/unlock",
        json={"branch_id": str(case.branch_id), "password": seed_owner["password"]},
        headers=headers,
    )
    assert unlock.status_code == 200, unlock.text
    headers["X-Owner-Confirmation"] = unlock.json()["token"]
    return case, headers


def body(case, *, target=20_000, operation="decrease", selected=True, refund_mode="cash"):
    return {
        "protocol": 1,
        "order_id": str(case.order_id),
        "operation": operation,
        "target_total_minor": target,
        "money_received": "received",
        "reason": "Original amount was entered incorrectly",
        "settlement_shift_id": str(case.shift_id) if selected else None,
        "refund_mode": refund_mode if selected else None,
    }


async def quoted(client, case, headers, **kwargs):
    command = body(case, **kwargs)
    reply = await client.post(BASE + "/preview", json=command, headers=headers)
    assert reply.status_code == 200, reply.text
    quote = reply.json()
    assert quote["commit_available"], quote
    return {
        **command,
        "source_revision": quote["source_revision"],
        "source_fingerprint": quote["source_fingerprint"],
    }


async def accept(client, case, headers, **kwargs):
    command = await quoted(client, case, headers, **kwargs)
    key = "paid-bill-test:" + str(uuid4())
    reply = await client.post(
        BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
    )
    assert reply.status_code == 201, reply.text
    return command, key, reply.json()


async def test_intent_reserves_once_replays_without_financial_effect(client, session, paid):
    case, headers = paid
    command, key, accepted = await accept(client, case, headers)
    current = await client.get(BASE + f"/receipts/{case.order_id}", headers=headers)
    assert current.status_code == 200, current.text
    assert current.json()["effective_total_minor"] == case.amount_minor
    assert current.json()["proposed_total_minor"] == 20_000
    assert current.json()["payout_remaining_minor"] == 4_500
    assert current.json()["actual_refunded_minor"] == 0
    replay = await client.post(
        BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == accepted
    found = await client.get(BASE + "/actions/by-key", headers={**headers, "Idempotency-Key": key})
    assert found.status_code == 200, found.text
    assert found.json()["accepted"] == accepted
    assert (
        len(
            list(
                await session.scalars(
                    select(PaidBillAmendmentIntent).where(
                        PaidBillAmendmentIntent.order_id == case.order_id
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
                    select(PosRefundRequest).where(PosRefundRequest.order_id == case.order_id)
                )
            )
        )
        == 1
    )
    assert await session.scalar(select(Refund.id).where(Refund.order_id == case.order_id)) is None


def task_headers(headers):
    key = "refund-stage:" + str(uuid4())
    return {**headers, "Idempotency-Key": key, "X-Client-Action-Id": key}


async def cash_stage(client, case, headers, request_id, amount, stage, **extra):
    payload = {"shift_id": str(case.shift_id), "expected_amount_minor": amount, **extra}
    result = await client.post(
        f"/api/v1/pos/refund-requests/{request_id}/{stage}",
        json=payload,
        headers=task_headers(headers),
    )
    assert result.status_code == 201, result.text
    return result.json()


async def state(client, case, headers):
    reply = await client.get(BASE + f"/receipts/{case.order_id}", headers=headers)
    assert reply.status_code == 200, reply.text
    return reply.json()


async def finish_cash(client, case, headers, request_id, amount):
    begun = await cash_stage(
        client, case, headers, request_id, amount, "begin-cash-handoff", ready_to_handover=True
    )
    await cash_stage(
        client,
        case,
        headers,
        request_id,
        amount,
        "settle-cash",
        cash_handed_over=True,
        settled_at=begun["handoff_started_at"],
    )
    return await cash_stage(client, case, headers, request_id, amount, "finalize-cash")


async def test_physical_completion_is_distinct_and_linked_finalize_posts_once(
    client, session, paid
):
    case, headers = paid
    command, key, accepted = await accept(client, case, headers)
    request_id = accepted["refund_request_id"]
    begun = await cash_stage(
        client, case, headers, request_id, 4_500, "begin-cash-handoff", ready_to_handover=True
    )
    pending = await state(client, case, headers)
    assert pending["status"] == "payout_in_progress_outcome_unknown"
    assert pending["payout_remaining_minor"] is None
    await cash_stage(
        client,
        case,
        headers,
        request_id,
        4_500,
        "settle-cash",
        cash_handed_over=True,
        settled_at=begun["handoff_started_at"],
    )
    physical = await state(client, case, headers)
    assert physical["status"] == "physical_completed_accounting_pending"
    assert physical["physical_completed_pending_accounting_minor"] == 4_500
    assert physical["payout_remaining_minor"] == 0
    assert physical["effective_total_minor"] == case.amount_minor
    assert physical["actual_refunded_minor"] == 0
    await cash_stage(client, case, headers, request_id, 4_500, "finalize-cash")
    settled = await state(client, case, headers)
    assert settled["status"] == "settled"
    assert settled["effective_total_minor"] == 20_000
    assert settled["actual_refunded_minor"] == 4_500
    assert settled["proposed_total_minor"] is None
    assert settled["payout_remaining_minor"] == 0
    assert settled["physical_completed_pending_accounting_minor"] == 0
    replay = await client.post(
        BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
    )
    assert replay.status_code == 201
    assert replay.json() == accepted
    assert (
        len(list(await session.scalars(select(Refund).where(Refund.order_id == case.order_id))))
        == 1
    )


async def test_discard_waits_for_real_full_payout(client, paid):
    case, headers = paid
    _, _, accepted = await accept(client, case, headers, target=0, operation="discard")
    assert (await state(client, case, headers))["effective_total_minor"] == case.amount_minor
    await finish_cash(client, case, headers, accepted["refund_request_id"], case.amount_minor)
    current = await state(client, case, headers)
    assert current["status"] == "discarded"
    assert current["effective_total_minor"] == 0
    assert current["actual_refunded_minor"] == case.amount_minor


async def test_prior_partial_refund_counts_once_and_later_decrease_uses_remaining(client, paid):
    case, headers = paid
    _, _, first = await accept(client, case, headers)
    await finish_cash(client, case, headers, first["refund_request_id"], 4_500)
    command, _, second = await accept(client, case, headers, target=15_000)
    assert second["revision"] > first["revision"]
    assert (await state(client, case, headers))["payout_remaining_minor"] == 5_000
    await finish_cash(client, case, headers, second["refund_request_id"], 5_000)
    current = await state(client, case, headers)
    assert current["effective_total_minor"] == 15_000
    assert current["actual_refunded_minor"] == 9_500
    assert len(current["history"]) == 2


async def test_withdrawal_realizes_no_change_and_new_action_can_be_reviewed(client, session, paid):
    case, headers = paid
    _, _, accepted = await accept(client, case, headers)
    request_id = accepted["refund_request_id"]
    row = await session.get(PosRefundRequest, request_id)
    await cash_stage(
        client,
        case,
        headers,
        request_id,
        4_500,
        "withdraw-cash",
        cash_not_handed_over=True,
        reason="Customer declined before handing over money",
        withdrawn_at=row.accepted_at.isoformat(),
    )
    withdrawn = await state(client, case, headers)
    assert withdrawn["status"] == "withdrawn"
    assert withdrawn["effective_total_minor"] == case.amount_minor
    assert withdrawn["actual_refunded_minor"] == 0
    assert withdrawn["proposed_total_minor"] is None
    assert withdrawn["payout_remaining_minor"] == 0
    await accept(client, case, headers, target=21_000)


async def test_choice_less_review_is_provisional_and_quote_binds_mode(client, paid):
    case, headers = paid
    initial = await client.post(BASE + "/preview", json=body(case, selected=False), headers=headers)
    assert initial.status_code == 200
    assert initial.json()["eligible"]
    assert not initial.json()["commit_available"]
    command = await quoted(client, case, headers)
    altered = {**command, "refund_mode": "original"}
    rejected = await client.post(
        BASE + "/commit",
        json=altered,
        headers={**headers, "Idempotency-Key": "changed-mode:" + str(uuid4())},
    )
    assert rejected.status_code == 409, rejected.text


@pytest.mark.parametrize("classification", ["not_received", "unknown"])
async def test_no_phantom_refund_for_unreceived_or_unknown_money(
    client, session, paid, classification
):
    case, headers = paid
    command = {**body(case), "money_received": classification}
    review = await client.post(BASE + "/preview", json=command, headers=headers)
    assert review.status_code == 200
    assert not review.json()["commit_available"]
    assert review.json()["proposed_refund_minor"] == 4_500
    committed = await client.post(
        BASE + "/commit",
        json={
            **command,
            "source_revision": review.json()["source_revision"],
            "source_fingerprint": review.json()["source_fingerprint"],
        },
        headers={**headers, "Idempotency-Key": "unreceived:" + str(uuid4())},
    )
    assert committed.status_code == 422
    assert (
        await session.scalar(
            select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
        )
        is None
    )


async def test_same_key_concurrent_lost_reply_and_different_key_order_races(client, session, paid):
    case, headers = paid
    command = await quoted(client, case, headers)
    key = "concurrent:" + str(uuid4())

    async def dispatch(k):
        return await client.post(
            BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": k}
        )

    a, b = await asyncio.gather(dispatch(key), dispatch(key))
    assert a.status_code == b.status_code == 201, (a.text, b.text)
    assert a.json() == b.json()
    conflict = await dispatch("different:" + str(uuid4()))
    assert conflict.status_code in {409, 422}, conflict.text
    assert (
        len(
            list(
                await session.scalars(
                    select(PaidBillAmendmentIntent).where(
                        PaidBillAmendmentIntent.order_id == case.order_id
                    )
                )
            )
        )
        == 1
    )
    changed = await client.post(
        BASE + "/commit",
        json={**command, "reason": "Changed semantic command"},
        headers={**headers, "Idempotency-Key": key},
    )
    assert changed.status_code == 409


async def test_already_fully_refunded_receipt_can_be_discarded_without_zero_task(
    client, session, paid
):
    case, headers = paid
    from tests.integration.test_pos_refund_settlement import _request_payload

    key = "prior-full:" + str(uuid4())
    original = await client.post(
        "/api/v1/pos/refund-requests",
        json=_request_payload(case, action_id=key, mode="cash"),
        headers={**headers, "Idempotency-Key": key, "X-Client-Action-Id": key},
    )
    assert original.status_code == 201, original.text
    await finish_cash(client, case, headers, original.json()["id"], case.amount_minor)
    _, _, accepted = await accept(
        client, case, headers, target=0, operation="discard", selected=False
    )
    assert accepted["refund_request_id"] is None
    assert accepted["status"] == "discarded"
    assert (
        len(
            list(
                await session.scalars(
                    select(PosRefundRequest).where(PosRefundRequest.order_id == case.order_id)
                )
            )
        )
        == 1
    )
    assert (
        len(list(await session.scalars(select(Refund).where(Refund.order_id == case.order_id))))
        == 1
    )
    assert (await state(client, case, headers))["effective_total_minor"] == 0
    # Null links must not turn SQL three-valued predicates into a legacy bypass.
    from sqlalchemy.exc import DBAPIError

    async with AsyncSessionLocal() as raw:
        with pytest.raises(DBAPIError, match="revision-aware Owner action"):
            await raw.execute(
                text("INSERT INTO pos_refund_requests(id,order_id) VALUES(:id,:o)"),
                {"id": uuid4(), "o": case.order_id},
            )
        await raw.rollback()


async def test_legacy_reservation_withdrawal_is_not_fingerprint_aba(client, session, paid):
    case, headers = paid
    old_quote = await quoted(client, case, headers)
    from tests.integration.test_pos_refund_settlement import _request_payload

    key = "legacy-aba:" + str(uuid4())
    payload = _request_payload(case, action_id=key, mode="cash")
    accepted = await client.post(
        "/api/v1/pos/refund-requests",
        json=payload,
        headers={**headers, "Idempotency-Key": key, "X-Client-Action-Id": key},
    )
    assert accepted.status_code == 201, accepted.text
    await cash_stage(
        client,
        case,
        headers,
        accepted.json()["id"],
        case.amount_minor,
        "withdraw-cash",
        cash_not_handed_over=True,
        reason="Customer changed their mind before payout",
        withdrawn_at=accepted.json()["accepted_at"],
    )
    refreshed = await quoted(client, case, headers)
    assert refreshed["source_revision"] > old_quote["source_revision"]
    assert refreshed["source_fingerprint"] != old_quote["source_fingerprint"]
    stale = await client.post(
        BASE + "/commit",
        json=old_quote,
        headers={**headers, "Idempotency-Key": "stale-aba:" + str(uuid4())},
    )
    assert stale.status_code == 409, stale.text
    assert (
        await session.scalar(
            select(PaidBillAmendmentIntent.id).where(
                PaidBillAmendmentIntent.order_id == case.order_id
            )
        )
        is None
    )


async def test_fresh_confirmation_recovers_same_command_and_old_or_wrong_grants_cannot(
    client, paid, seed_owner
):
    case, headers = paid
    command, key, accepted = await accept(client, case, headers)
    from datetime import datetime

    import jwt

    from app.core.config import get_settings

    claims = jwt.decode(headers["X-Owner-Confirmation"], options={"verify_signature": False})
    claims["exp"] = int(datetime.now(UTC).timestamp()) - 1
    expired = jwt.encode(claims, get_settings().jwt_secret, algorithm=get_settings().jwt_algorithm)
    for grant in (
        None,
        expired,
        issue_tender_token(
            user_id=seed_owner["owner"].id,
            company_id=case.company_id,
            branch_id=case.branch_id,
            auth_version=seed_owner["owner"].auth_version,
            scope="receipt_stock",
        ),
    ):
        attempt_headers = {k: v for k, v in headers.items() if k != "X-Owner-Confirmation"}
        if grant:
            attempt_headers["X-Owner-Confirmation"] = grant
        denied = await client.post(
            BASE + "/commit", json=command, headers={**attempt_headers, "Idempotency-Key": key}
        )
        assert denied.status_code == 401, denied.text
    expired_read = await client.get(
        BASE + "/actions/by-key",
        headers={**headers, "Idempotency-Key": key, "X-Owner-Confirmation": expired},
    )
    assert expired_read.status_code == 401, expired_read.text
    unlocked = await client.post(
        BASE + "/unlock",
        headers=headers,
        json={"branch_id": str(case.branch_id), "password": seed_owner["password"]},
    )
    assert unlocked.status_code == 200, unlocked.text
    fresh_headers = {**headers, "X-Owner-Confirmation": unlocked.json()["token"]}
    recovered = await client.post(
        BASE + "/commit", json=command, headers={**fresh_headers, "Idempotency-Key": key}
    )
    assert recovered.status_code == 201
    assert recovered.json() == accepted

    looked_up = await client.get(
        BASE + "/actions/by-key", headers={**fresh_headers, "Idempotency-Key": key}
    )
    assert looked_up.status_code == 200, looked_up.text
    assert looked_up.json()["accepted"] == accepted
    assert looked_up.json()["current"]["status"] == "pending_settlement"
    async with AsyncSessionLocal() as verify:
        assert (
            len(
                list(
                    await verify.scalars(
                        select(PosRefundRequest).where(PosRefundRequest.order_id == case.order_id)
                    )
                )
            )
            == 1
        )


async def test_nonprotected_and_foreign_scope_have_no_quote_or_recovery_disclosure(
    client, session, paid, seed_owner
):
    case, headers = paid
    foreign = await client.post(
        BASE + "/preview", json={**body(case), "order_id": str(uuid4())}, headers=headers
    )
    assert foreign.status_code == 404
    _, key, _ = await accept(client, case, headers)
    ordinary = await set_role(session, seed_owner, "owner")
    for method, url, data in [
        ("POST", BASE + "/preview", body(case)),
        ("GET", BASE + "/actions/by-key", None),
    ]:
        denied = await client.request(
            method, url, json=data, headers={**ordinary, "Idempotency-Key": key}
        )
        assert denied.status_code == 403, denied.text


async def test_legacy_new_refunds_and_raw_source_writes_refuse_but_linked_task_remains_usable(
    client, session, paid
):
    case, headers = paid
    _, _, accepted = await accept(client, case, headers)
    from tests.integration.test_pos_refund_settlement import _request_payload

    key = "legacy-conflict:" + str(uuid4())
    denied = await client.post(
        "/api/v1/pos/refund-requests",
        json=_request_payload(case, action_id=key, mode="cash"),
        headers={**headers, "Idempotency-Key": key, "X-Client-Action-Id": key},
    )
    assert denied.status_code == 422
    assert "amendment history" in denied.text
    from sqlalchemy.exc import DBAPIError

    for table in (
        "pos_refund_requests",
        "payment_tender_corrections",
        "pos_receipt_corrections",
        "refunds",
    ):
        async with AsyncSessionLocal() as raw:
            with pytest.raises(DBAPIError, match="revision-aware Owner action"):
                await raw.execute(
                    text(f"INSERT INTO {table}(id,order_id) VALUES(:id,:o)"),
                    {"id": uuid4(), "o": case.order_id},
                )
            await raw.rollback()
    await finish_cash(client, case, headers, accepted["refund_request_id"], 4_500)


@pytest.mark.parametrize("verb", ["UPDATE", "DELETE", "TRUNCATE"])
async def test_intents_are_append_only_at_database_boundary(client, session, paid, verb):
    case, headers = paid
    _, _, accepted = await accept(client, case, headers)
    from sqlalchemy.exc import DBAPIError

    async with AsyncSessionLocal() as raw:
        statement = {
            "UPDATE": "UPDATE paid_bill_amendment_intents SET target_minor=1 WHERE id=:id",
            "DELETE": "DELETE FROM paid_bill_amendment_intents WHERE id=:id",
            "TRUNCATE": "TRUNCATE paid_bill_amendment_intents",
        }[verb]
        with pytest.raises(DBAPIError):
            await raw.execute(text(statement), {"id": accepted["id"]})
        await raw.rollback()
    assert (
        len(
            list(
                await session.scalars(
                    select(PaidBillAmendmentIntent).where(
                        PaidBillAmendmentIntent.order_id == case.order_id
                    )
                )
            )
        )
        == 1
    )


async def test_atomic_outbox_failure_rolls_back_intent_request_and_audit(
    client, session, paid, monkeypatch
):
    case, headers = paid
    command = await quoted(client, case, headers)
    import app.services.pos.paid_bill_amendments as amendments

    async def fail(*args, **kwargs):
        raise RuntimeError("Synthetic intent outbox persistence failure")

    monkeypatch.setattr(amendments, "enqueue_google_sheets_event_if_enabled", fail)
    with pytest.raises(RuntimeError, match="outbox persistence failure"):
        await client.post(
            BASE + "/commit",
            json=command,
            headers={**headers, "Idempotency-Key": "rollback:" + str(uuid4())},
        )
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(
                select(PaidBillAmendmentIntent.id).where(
                    PaidBillAmendmentIntent.order_id == case.order_id
                )
            )
            is None
        )
        assert (
            await verify.scalar(
                select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
            )
            is None
        )
        assert (
            await verify.scalar(
                text(
                    "SELECT count(*) FROM audit_log WHERE "
                    "entity_type='PaidBillAmendmentIntent' AND after->>'order_id'=:o"
                ),
                {"o": str(case.order_id)},
            )
            == 0
        )


async def test_mirror_metadata_is_sanitized_once_and_receipt_reader_uses_shared_projection(
    client, session, paid
):
    case, headers = paid
    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": "https://script.google.com/macros/s/paid-bill-synthetic/exec"},
    )
    assert configured.status_code == 200, configured.text
    command, key, accepted = await accept(client, case, headers)
    replay = await client.post(
        BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
    )
    assert replay.status_code == 201
    import json

    from app.models.google_sheets_delivery import GoogleSheetsDelivery

    rows = list(
        await session.scalars(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == case.company_id,
                GoogleSheetsDelivery.source_type == "paid_bill_amendment_intent",
            )
        )
    )
    assert len(rows) == 1
    serialized = json.dumps(rows[0].payload)
    assert command["reason"] not in serialized
    for forbidden in (
        "password",
        "token",
        "request_body",
        "source_snapshot",
        "amount_minor",
        "customer_name",
    ):
        assert forbidden not in serialized
    assert rows[0].payload["financial_effect"] is False
    receipt = await client.get(f"/api/v1/pos/receipts/{case.order_id}", headers=headers)
    assert receipt.status_code == 200, receipt.text
    from app.schemas.paid_bill_amendment import PaidBillReceiptSummary

    current = await state(client, case, headers)
    assert receipt.json()["paid_bill_amendment"] == {
        key: current[key] for key in PaidBillReceiptSummary.model_fields
    }


async def raw_values(session, case, headers, command):
    import json
    from datetime import datetime

    ident = uuid4()
    request_id = uuid4()
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    source["selection"] = {
        k: v for k, v in command.items() if k not in {"source_revision", "source_fingerprint"}
    }
    digest = await session.scalar(
        text("SELECT encode(sha256(convert_to(CAST(:b AS jsonb)::text,'UTF8')),'hex')"),
        {"b": json.dumps(command)},
    )
    return {
        "id": ident,
        "company_id": case.company_id,
        "order_id": case.order_id,
        "branch_id": case.branch_id,
        "terminal_id": case.terminal_id,
        "original_shift_id": case.shift_id,
        "settlement_shift_id": case.shift_id,
        "recorded_by": case.owner_id,
        "refund_request_id": request_id,
        "invoice_no": source["order"]["invoice_no"],
        "operation": "decrease",
        "refund_mode": "cash",
        "prior_effective_minor": case.amount_minor,
        "target_minor": 20000,
        "difference_minor": 4500,
        "source_revision": command["source_revision"],
        "revision": command["source_revision"] + 1,
        "source_fingerprint": command["source_fingerprint"],
        "source_snapshot": source,
        "reason": command["reason"],
        "action_key": "raw:" + str(ident),
        "request_path": BASE + "/commit",
        "request_hash": digest,
        "request_body": command,
        "response_body": {
            "id": str(ident),
            "order_id": str(case.order_id),
            "revision": command["source_revision"] + 1,
            "refund_request_id": str(request_id),
            "target_total_minor": 20000,
            "status": "pending_settlement",
            "financial_effect": False,
        },
        "recorded_at": datetime.now(UTC),
    }


@pytest.mark.parametrize(
    "forgery",
    [
        "foreign_company",
        "snapshot",
        "difference",
        "missing_link",
        "wrong_link",
        "missing_audit",
        "secret_payload",
        "fake_response",
        "ordinary_owner",
    ],
)
async def test_raw_insert_cannot_forge_source_difference_or_orphan_refund_link(
    client, session, paid, forgery, seed_owner
):
    case, headers = paid
    command = await quoted(client, case, headers)
    from sqlalchemy import insert
    from sqlalchemy.exc import DBAPIError

    values = await raw_values(session, case, headers, command)
    if forgery == "foreign_company":
        values["company_id"] = uuid4()
    if forgery == "snapshot":
        values["source_snapshot"]["payments"][0]["amount_minor"] -= 1
    if forgery == "difference":
        values["difference_minor"] -= 1
    if forgery == "secret_payload":
        values["request_body"] = {**values["request_body"], "password": "forbidden-credential"}
    if forgery == "fake_response":
        values["response_body"] = {**values["response_body"], "status": "discarded"}
    if forgery == "ordinary_owner":
        await set_role(session, seed_owner, "owner")
    await session.rollback()
    async with AsyncSessionLocal() as raw:
        with pytest.raises(DBAPIError) as failure:  # noqa: PT012 - one atomic SQL transaction
            await raw.execute(insert(PaidBillAmendmentIntent.__table__).values(**values))
            if forgery in {"wrong_link", "missing_audit"}:
                amount = 4_499 if forgery == "wrong_link" else 4_500
                await raw.execute(
                    insert(PosRefundRequest.__table__).values(
                        id=values["refund_request_id"],
                        company_id=case.company_id,
                        order_id=case.order_id,
                        branch_id=case.branch_id,
                        terminal_id=case.terminal_id,
                        shift_id=case.shift_id,
                        approved_by=case.owner_id,
                        reason_code="paid_bill_amendment",
                        amount_minor=amount,
                        mode="cash",
                        settlement_method="cash",
                        order_paid_snapshot_minor=case.amount_minor,
                        order_refundable_snapshot_minor=case.amount_minor,
                        accepted_at=values["recorded_at"],
                        client_action_id="paid-bill:" + str(values["id"]),
                        idempotency_key="paid-bill:" + str(values["id"]),
                    )
                )
            await raw.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))
            await raw.commit()
        await raw.rollback()
    expected = {
        "foreign_company": ("source scope is invalid",),
        "snapshot": ("quote or semantic command changed",),
        "difference": ("violates check constraint",),
        "missing_link": ("foreign key constraint", "exact refund link is incomplete"),
        "wrong_link": ("exact refund link is incomplete",),
        "missing_audit": ("atomic audit record",),
        "secret_payload": ("quote or semantic command changed",),
        "fake_response": ("durable acceptance response is invalid",),
        "ordinary_owner": ("requires current protected Owner",),
    }
    assert any(message in str(failure.value) for message in expected[forgery])
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(
                select(PaidBillAmendmentIntent.id).where(
                    PaidBillAmendmentIntent.order_id == case.order_id
                )
            )
            is None
        )
        assert (
            await verify.scalar(
                select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
            )
            is None
        )


async def test_confirmation_expiring_during_shift_lock_wait_creates_nothing(
    client, session, paid, monkeypatch
):
    case, headers = paid
    command = await quoted(client, case, headers)
    from datetime import datetime

    import jwt

    from app.core.config import get_settings
    from app.services.pos import paid_bill_amendments as service

    claims = jwt.decode(headers["X-Owner-Confirmation"], options={"verify_signature": False})
    claims["exp"] = int(datetime.now(UTC).timestamp()) + 2
    grant = jwt.encode(claims, get_settings().jwt_secret, algorithm=get_settings().jwt_algorithm)
    initial_checked = asyncio.Event()
    original = service.require_confirmation

    async def observed(*args, **kwargs):
        await original(*args, **kwargs)
        initial_checked.set()

    monkeypatch.setattr(service, "require_confirmation", observed)
    await session.rollback()
    async with AsyncSessionLocal() as blocker:
        await blocker.execute(
            text("SELECT id FROM shifts WHERE id=:s FOR UPDATE"), {"s": case.shift_id}
        )
        pending = asyncio.create_task(
            client.post(
                BASE + "/commit",
                json=command,
                headers={
                    **headers,
                    "X-Owner-Confirmation": grant,
                    "Idempotency-Key": "expiry-wait:" + str(uuid4()),
                },
            )
        )
        await asyncio.wait_for(initial_checked.wait(), 1)
        # Wait for integer-second token expiry without changing any lock deadline.
        remaining = max(0, claims["exp"] - datetime.now(UTC).timestamp())
        await asyncio.sleep(remaining + 0.02)
        await blocker.rollback()
        denied = await asyncio.wait_for(pending, 2)
    assert denied.status_code == 401, denied.text
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(
                select(PaidBillAmendmentIntent.id).where(
                    PaidBillAmendmentIntent.order_id == case.order_id
                )
            )
            is None
        )
        assert (
            await verify.scalar(
                select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
            )
            is None
        )


async def test_audit_failure_rolls_back_all_new_domain_rows(client, paid, monkeypatch):
    case, headers = paid
    command = await quoted(client, case, headers)
    import app.services.audit.recorder as recorder

    original = recorder._serialize

    def fail(obj):
        if isinstance(obj, PaidBillAmendmentIntent):
            raise RuntimeError("Synthetic paid bill audit persistence failure")
        return original(obj)

    monkeypatch.setattr(recorder, "_serialize", fail)
    with pytest.raises(RuntimeError, match="audit persistence failure"):
        await client.post(
            BASE + "/commit",
            json=command,
            headers={**headers, "Idempotency-Key": "audit-failure:" + str(uuid4())},
        )
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(
                select(PaidBillAmendmentIntent.id).where(
                    PaidBillAmendmentIntent.order_id == case.order_id
                )
            )
            is None
        )
        assert (
            await verify.scalar(
                select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
            )
            is None
        )


async def test_existing_reports_and_ledger_only_recognize_actual_refund(client, paid):
    case, headers = paid
    from datetime import UTC, datetime, timedelta

    from app.services.accounting.ledger import build_operational_ledger
    from app.services.reports import ReportsAggregator

    start = (case.captured_at - timedelta(days=1)).date()
    end = (case.captured_at + timedelta(days=1)).date()

    async def totals():
        async with AsyncSessionLocal() as read:
            report = await ReportsAggregator(read).aggregate(
                company_id=case.company_id, period_start=start, period_end=end
            )
            ledger = await build_operational_ledger(
                read,
                company_id=case.company_id,
                start_at=datetime.combine(start, datetime.min.time(), tzinfo=UTC),
                end_exclusive=datetime.combine(
                    end + timedelta(days=1), datetime.min.time(), tzinfo=UTC
                ),
            )
            assert sum(x.debit_minor for x in ledger) == sum(x.credit_minor for x in ledger)
            return (
                report.net_revenue_minor,
                report.refunds_issued_minor,
                [(x.account_code, x.debit_minor, x.credit_minor) for x in ledger],
            )

    before = await totals()
    _, _, accepted = await accept(client, case, headers)
    assert await totals() == before
    await finish_cash(client, case, headers, accepted["refund_request_id"], 4_500)
    after = await totals()
    assert after[0] == before[0] - 4500
    assert after[1] == 4500


async def test_stock_backed_discard_keeps_consumption_cogs_gst_and_closed_shift_evidence(
    client, session, seed_owner
):
    from dataclasses import replace
    from datetime import UTC, datetime, timedelta

    from app.models import (
        Batch,
        Customer,
        Ingredient,
        MenuCategory,
        MenuItem,
        Recipe,
        RecipeLine,
        Shift,
        StockMovement,
    )
    from app.services.accounting.accounts import COST_OF_GOODS_SOLD
    from app.services.accounting.ledger import build_operational_ledger
    from app.services.audit.recorder import install_audit_listeners
    from app.services.reports import ReportsAggregator
    from tests.integration.test_pos_refund_settlement import _headers

    install_audit_listeners()
    case = await _seed_case(session, seed_owner, payment_method="cash", defer_settlement=True)
    seed_owner["company"].gstin = "32ABCDE1234F1Z5"
    seed_owner["branch"].state_code = "32"
    category = MenuCategory(
        id=uuid4(),
        company_id=case.company_id,
        name=f"Refund stock contract {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=case.company_id,
        category_id=category.id,
        sku=f"RF-STOCK-{uuid4().hex[:10]}",
        name="Prepared stock refund proof",
        type="food",
        base_price_minor=case.amount_minor,
        tax_rate="0.05",
        price_includes_tax=True,
        is_available=True,
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=case.company_id,
        sku=f"RF-ING-{uuid4().hex[:10]}",
        name="Refund proof ingredient",
        base_unit="unit",
        reorder_threshold=0,
        reorder_qty=0,
        avg_cost_minor=125,
        current_qty=10,
    )
    batch = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=case.branch_id,
        received_at=case.captured_at - timedelta(days=1),
        expires_at=None,
        qty_initial=10,
        qty_on_hand=10,
        cost_per_unit_minor=125,
        lot_code=f"RF-{uuid4().hex[:8]}",
    )
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="One ingredient per prepared item",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=125,
    )
    recipe_line = RecipeLine(
        id=uuid4(),
        recipe_id=recipe.id,
        ingredient_id=ingredient.id,
        qty=1,
        wastage_pct=0,
    )

    session.add_all([category, ingredient])
    await session.flush()
    session.add_all([item, batch])
    await session.flush()
    session.add(recipe)
    await session.flush()
    session.add(recipe_line)
    await session.commit()
    from app.models import OrderLine
    from app.services.pos.pricing import LineRequest, OrderPricingService

    priced = await OrderPricingService(session).price_order(
        company_id=case.company_id,
        branch_id=case.branch_id,
        customer_id=case.customer_id,
        line_requests=[LineRequest(menu_item_id=item.id, qty=1)],
    )
    line = priced.lines[0]
    cart = await session.get(Order, case.order_id)
    cart.subtotal_minor = priced.subtotal_taxable_minor
    for field in (
        "cgst_minor",
        "sgst_minor",
        "igst_minor",
        "cess_minor",
        "discount_minor",
        "round_off_minor",
        "total_minor",
    ):
        setattr(cart, field, getattr(priced, field))
    cart.tax_minor = priced.cgst_minor + priced.sgst_minor + priced.igst_minor + priced.cess_minor
    session.add(
        OrderLine(
            id=uuid4(),
            order_id=case.order_id,
            menu_item_id=item.id,
            qty=1,
            unit_price_minor=line.unit_inclusive_minor,
            line_total_minor=line.line_inclusive_minor,
            taxable_value_minor=line.taxable_value_minor,
            tax_rate=line.tax_rate,
            cgst_minor=line.cgst_minor,
            sgst_minor=line.sgst_minor,
            igst_minor=line.igst_minor,
            cess_minor=line.cess_minor,
            discount_minor=line.discount_minor,
            kitchen_status="served",
            kitchen_released_at=case.captured_at,
            kitchen_round_no=1,
            kitchen_served_at=case.captured_at,
        )
    )
    await session.commit()
    paid_reply = await client.post(
        f"/api/v1/pos/orders/{case.order_id}/payments",
        json={
            "method": "cash",
            "amount_minor": case.amount_minor,
            "tendered_minor": case.amount_minor,
            "expected_order_total_minor": case.amount_minor,
            "expected_due_minor": case.amount_minor,
        },
        headers=_headers(case, "stock-payment:" + str(uuid4())),
    )
    assert paid_reply.status_code == 201, paid_reply.text
    headers = await set_role(session, seed_owner, "super_owner")
    headers["X-Owner-Confirmation"] = issue_tender_token(
        user_id=case.owner_id,
        company_id=case.company_id,
        branch_id=case.branch_id,
        auth_version=seed_owner["owner"].auth_version,
        scope="paid_bill_amendment",
    )
    old_shift = await session.get(Shift, case.shift_id)
    old_shift.status = "closed"
    old_shift.closed_at = datetime.now(UTC)
    old_shift.closed_by = case.owner_id
    await session.flush()
    current_shift = Shift(
        id=uuid4(),
        company_id=case.company_id,
        branch_id=case.branch_id,
        terminal_id=case.terminal_id,
        opened_by=case.owner_id,
        opened_at=datetime.now(UTC) - timedelta(seconds=1),
        opening_float_minor=100_000,
        expected_minor=100_000,
        status="open",
    )
    session.add(current_shift)
    await session.commit()
    original_shift_id = case.shift_id
    case = replace(case, shift_id=current_shift.id)
    closed_snapshot = await session.scalar(
        text("SELECT to_jsonb(s) FROM shifts s WHERE id=:id"), {"id": original_shift_id}
    )
    original_payments = await session.scalar(
        text("SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM payments p WHERE order_id=:id"),
        {"id": case.order_id},
    )
    start = (case.captured_at - timedelta(days=1)).date()
    end = (case.captured_at + timedelta(days=1)).date()

    async def facts():
        async with AsyncSessionLocal() as read:
            report = await ReportsAggregator(read).aggregate(
                company_id=case.company_id, period_start=start, period_end=end
            )
            customer = await read.get(Customer, case.customer_id)
            adjustments = await read.scalar(
                text(
                    "SELECT count(*) FROM refund_loyalty_adjustments WHERE "
                    "order_loyalty_settlement_id IN (SELECT id FROM "
                    "order_loyalty_settlements WHERE order_id=:o)"
                ),
                {"o": case.order_id},
            )
            return (
                report.net_revenue_minor,
                report.tax_collected.total_minor,
                report.cogs_minor,
                customer.total_spent_minor,
                customer.loyalty_points,
                adjustments,
            )

    before = await facts()
    assert before[1] > 0
    assert before[2] == 125
    assert before[5] == 0
    _, _, accepted = await accept(client, case, headers, target=0, operation="discard")
    assert await facts() == before
    begun = await cash_stage(
        client,
        case,
        headers,
        accepted["refund_request_id"],
        case.amount_minor,
        "begin-cash-handoff",
        ready_to_handover=True,
    )
    await cash_stage(
        client,
        case,
        headers,
        accepted["refund_request_id"],
        case.amount_minor,
        "settle-cash",
        cash_handed_over=True,
        settled_at=begun["handoff_started_at"],
    )
    assert await facts() == before
    await cash_stage(
        client, case, headers, accepted["refund_request_id"], case.amount_minor, "finalize-cash"
    )
    after = await facts()
    assert after[0] == 0
    assert after[1] == 0
    assert after[2] == 125
    assert after[3] == 0
    assert after[5] == 1
    async with AsyncSessionLocal() as verify:
        assert (await verify.get(Order, case.order_id)).status == "refunded"
        assert (await verify.get(Batch, batch.id)).qty_on_hand == 9
        assert (await verify.get(Ingredient, ingredient.id)).current_qty == 9
        moves = list(
            await verify.scalars(select(StockMovement).where(StockMovement.ref_id == case.order_id))
        )
        assert len(moves) == 1
        assert moves[0].type == "sale"
        assert moves[0].qty_delta == -1
        ledger = await build_operational_ledger(
            verify,
            company_id=case.company_id,
            start_at=datetime.combine(start, datetime.min.time(), tzinfo=UTC),
            end_exclusive=datetime.combine(
                end + timedelta(days=1), datetime.min.time(), tzinfo=UTC
            ),
        )
        assert (
            sum(
                x.debit_minor - x.credit_minor
                for x in ledger
                if x.account_code == COST_OF_GOODS_SOLD.code
            )
            == 125
        )
        assert (
            await verify.scalar(
                text("SELECT to_jsonb(s) FROM shifts s WHERE id=:id"), {"id": original_shift_id}
            )
            == closed_snapshot
        )
        assert (
            await verify.scalar(
                text(
                    "SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM payments p WHERE order_id=:id"
                ),
                {"id": case.order_id},
            )
            == original_payments
        )


@pytest.mark.parametrize("paid", ["upi"], indirect=True)
async def test_original_provider_rail_keeps_uncertain_physical_and_accounting_states_separate(
    client, paid
):
    case, headers = paid
    _, _, accepted = await accept(client, case, headers, refund_mode="original")
    request_id = accepted["refund_request_id"]
    begun = await cash_stage(
        client,
        case,
        headers,
        request_id,
        4_500,
        "begin-provider-payout",
        ready_to_start_provider_payout=True,
    )
    unknown = await state(client, case, headers)
    assert unknown["status"] == "payout_in_progress_outcome_unknown"
    assert unknown["payout_remaining_minor"] is None
    await cash_stage(
        client,
        case,
        headers,
        request_id,
        4_500,
        "settle-provider",
        provider_completed=True,
        external_reference="SYNTHETIC-UPI-REFUND-123456",
        provider_settled_at=begun["provider_payout_started_at"],
    )
    physical = await state(client, case, headers)
    assert physical["status"] == "physical_completed_accounting_pending"
    assert physical["effective_total_minor"] == case.amount_minor
    assert physical["payout_remaining_minor"] == 0
    await cash_stage(client, case, headers, request_id, 4_500, "finalize-provider")
    final = await state(client, case, headers)
    assert final["effective_total_minor"] == 20000
    assert final["actual_refunded_minor"] == 4500


async def test_same_transaction_scope_mutation_is_rejected_by_deferred_source_guard(
    client, paid, monkeypatch
):
    case, headers = paid
    command = await quoted(client, case, headers)
    import app.services.pos.paid_bill_amendments as service

    original = service.enqueue_google_sheets_event_if_enabled

    async def mutate(session, **kwargs):
        await original(session, **kwargs)
        await session.execute(
            text("UPDATE shifts SET expected_minor=expected_minor+1 WHERE id=:s"),
            {"s": case.shift_id},
        )

    monkeypatch.setattr(service, "enqueue_google_sheets_event_if_enabled", mutate)
    refused = await client.post(
        BASE + "/commit",
        json=command,
        headers={**headers, "Idempotency-Key": "same-tx-scope:" + str(uuid4())},
    )
    assert refused.status_code == 409, refused.text
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(
                select(PaidBillAmendmentIntent.id).where(
                    PaidBillAmendmentIntent.order_id == case.order_id
                )
            )
            is None
        )
        assert (
            await verify.scalar(
                select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
            )
            is None
        )
        assert (
            await verify.scalar(
                text("SELECT expected_minor FROM shifts WHERE id=:s"), {"s": case.shift_id}
            )
            == case.opening_float_minor + case.amount_minor
        )


async def test_unlock_is_purpose_bound_and_credentials_never_enter_saved_command_or_audit(
    client, session, paid, seed_owner
):
    case, headers = paid
    unlocked = await client.post(
        BASE + "/unlock",
        json={"branch_id": str(case.branch_id), "password": seed_owner["password"]},
        headers=headers,
    )
    assert unlocked.status_code == 200, unlocked.text
    assert unlocked.json()["purpose"] == "paid_bill_amendment"
    new_headers = {**headers, "X-Owner-Confirmation": unlocked.json()["token"]}
    command, key, accepted = await accept(client, case, new_headers)
    intent = await session.get(PaidBillAmendmentIntent, accepted["id"])
    import json

    saved = json.dumps(intent.request_body)
    assert unlocked.json()["token"] not in saved
    assert seed_owner["password"] not in saved
    assert intent.request_body == command
    audits = (
        (
            await session.execute(
                text(
                    (
                        "SELECT after FROM audit_log WHERE "
                        "entity_type='PaidBillAmendmentIntent' AND entity_id=:id"
                    )
                ),
                {"id": accepted["id"]},
            )
        )
        .scalars()
        .all()
    )
    assert len(audits) == 1
    serialized = json.dumps(audits)
    assert command["reason"] not in serialized
    assert unlocked.json()["token"] not in serialized
    assert seed_owner["password"] not in serialized


async def test_ordinary_receipt_reader_gets_only_minimal_summary_not_owner_recovery(
    client, session, paid, seed_owner
):
    case, headers = paid
    _, key, accepted = await accept(client, case, headers)
    private = await state(client, case, headers)
    ordinary = await set_role(session, seed_owner, "owner")
    receipt = await client.get(f"/api/v1/pos/receipts/{case.order_id}", headers=ordinary)
    assert receipt.status_code == 200, receipt.text
    from app.schemas.paid_bill_amendment import PaidBillReceiptSummary

    summary = receipt.json()["paid_bill_amendment"]
    assert summary == {name: private[name] for name in PaidBillReceiptSummary.model_fields}
    assert private["history"]
    assert "history" not in summary
    import json

    serialized = json.dumps(summary)
    for forbidden in (
        "reason",
        "request_body",
        "action_key",
        "source_snapshot",
        "source_fingerprint",
        "source_revision",
        "confirmation",
        "recorded_by",
    ):
        assert forbidden not in serialized
    denied = await client.get(
        BASE + "/actions/by-key", headers={**ordinary, "Idempotency-Key": key}
    )
    assert denied.status_code == 403, denied.text


async def test_exact_linked_task_reader_preserves_list_and_refuses_foreign_scopes(
    client, session, paid, seed_owner
):
    case, headers = paid
    _, _, accepted = await accept(client, case, headers)
    request_id = accepted["refund_request_id"]
    exact = await client.get(f"/api/v1/pos/refund-requests/{request_id}", headers=headers)
    assert exact.status_code == 200, exact.text
    listed = await client.get("/api/v1/pos/refund-requests", headers=headers)
    assert listed.status_code == 200
    assert listed.json() == [exact.json()]
    assert exact.json()["id"] == request_id
    assert exact.json()["amount_minor"] == 4_500
    missing = await client.get(f"/api/v1/pos/refund-requests/{uuid4()}", headers=headers)
    assert missing.status_code == 404
    from app.core.security import issue_access_token
    from app.models import Branch, Company, Role, Terminal, User, UserRole

    branch = Branch(
        id=uuid4(),
        company_id=case.company_id,
        name="Other synthetic branch",
        invoice_series_code="QA",
    )
    terminal = Terminal(id=uuid4(), branch_id=branch.id, name="Other synthetic terminal")
    session.add(branch)
    await session.flush()
    session.add(terminal)
    await session.commit()
    other_headers = {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=case.owner_id,
            company_id=case.company_id,
            branch_id=branch.id,
            roles=["super_owner"],
            auth_version=seed_owner["owner"].auth_version,
        ),
        "X-Terminal-Id": str(terminal.id),
    }
    foreign_branch = await client.get(
        f"/api/v1/pos/refund-requests/{request_id}", headers=other_headers
    )
    assert foreign_branch.status_code == 404, foreign_branch.text
    company = Company(id=uuid4(), name="Foreign synthetic company")
    session.add(company)
    await session.flush()
    other_branch = Branch(
        id=uuid4(), company_id=company.id, name="Foreign branch", invoice_series_code="QB"
    )
    actor = User(
        id=uuid4(),
        company_id=company.id,
        email=str(uuid4()) + "@test.local",
        name="Foreign test reader",
        password_hash=seed_owner["owner"].password_hash,
        status="active",
    )
    role = Role(id=uuid4(), company_id=company.id, code="owner", name="Owner", permissions=[])
    session.add_all([other_branch, actor, role])
    await session.flush()
    other_terminal = Terminal(id=uuid4(), branch_id=other_branch.id, name="Foreign terminal")
    session.add_all([other_terminal, UserRole(id=uuid4(), user_id=actor.id, role_id=role.id)])
    await session.commit()
    await session.refresh(actor)
    foreign_headers = {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=actor.id,
            company_id=company.id,
            branch_id=other_branch.id,
            roles=["owner"],
            auth_version=actor.auth_version,
        ),
        "X-Terminal-Id": str(other_terminal.id),
    }
    foreign_tenant = await client.get(
        f"/api/v1/pos/refund-requests/{request_id}", headers=foreign_headers
    )
    assert foreign_tenant.status_code == 404, foreign_tenant.text
    assert (await client.get("/api/v1/pos/refund-requests", headers=foreign_headers)).json() == []
    await finish_cash(client, case, headers, request_id, 4_500)
    settled = await client.get(f"/api/v1/pos/refund-requests/{request_id}", headers=headers)
    assert settled.status_code == 200
    assert settled.json()["status"] == "settled"
    assert (await client.get("/api/v1/pos/refund-requests", headers=headers)).json() == []


async def test_einvoice_profile_is_explicitly_blocked_without_reserving_money(
    client, session, paid, seed_owner
):
    case, headers = paid
    seed_owner["company"].e_invoicing_enabled = True
    await session.commit()
    review = await client.post(BASE + "/preview", json=body(case), headers=headers)
    assert review.status_code == 200
    assert not review.json()["commit_available"]
    assert any("statutory adjustment" in item for item in review.json()["blockers"])
    assert (
        await session.scalar(
            select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
        )
        is None
    )


async def test_unknown_original_loyalty_allocation_requires_reconciliation(session, paid):
    case, _ = paid
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    assert source["lines"]
    assert source["loyalty_settlements"]
    # Probe missing historical evidence without deleting an immutable settlement.
    source["loyalty_settlements"] = []
    blockers = await session.scalar(
        text("SELECT dcompany_paid_bill_blockers(CAST(:src AS jsonb))"), {"src": json.dumps(source)}
    )
    assert any("customer/loyalty allocation" in item for item in blockers)
    assert (
        await session.scalar(
            select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
        )
        is None
    )


@pytest.mark.parametrize("stage", ["commit", "replay", "recovery", "unlock", "preview", "detail"])
async def test_real_login_family_logout_wins_before_actor_lock(
    client, session, paid, seed_owner, monkeypatch, stage
):
    from app.core.security import decode_token
    from app.models import AuthRefreshSession, Refund, User
    from app.services.auth import refresh_sessions
    from app.services.pos import paid_bill_amendments as service

    case, headers = paid
    command = await quoted(client, case, headers)
    key = "independent-logout:" + str(uuid4())
    expected = 0
    if stage in ("replay", "recovery"):
        response = await client.post(
            BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
        )
        assert response.status_code == 201, response.text
        expected = 1
    if stage in ("replay", "recovery"):
        from datetime import datetime

        import jwt

        from app.core.config import get_settings

        claims = jwt.decode(headers["X-Owner-Confirmation"], options={"verify_signature": False})
        claims["exp"] = int(datetime.now(UTC).timestamp()) - 1
        expired = jwt.encode(
            claims, get_settings().jwt_secret, algorithm=get_settings().jwt_algorithm
        )
        refused = await client.get(
            BASE + "/actions/by-key",
            headers={**headers, "Idempotency-Key": key, "X-Owner-Confirmation": expired},
        )
        assert refused.status_code == 401, refused.text
        grant = await client.post(
            BASE + "/unlock",
            json={"branch_id": str(case.branch_id), "password": seed_owner["password"]},
            headers=headers,
        )
        assert grant.status_code == 200, grant.text
        headers = {**headers, "X-Owner-Confirmation": grant.json()["token"]}
    await session.rollback()
    original_version = await session.scalar(
        select(User.auth_version).where(User.id == case.owner_id)
    )
    await session.rollback()
    locked = asyncio.Event()
    release = asyncio.Event()
    actor_entered = asyncio.Event()
    original_user = refresh_sessions._locked_user
    original_actor = service.lock_actor

    async def hold_logout(*args, **kwargs):
        user = await original_user(*args, **kwargs)
        locked.set()
        await asyncio.wait_for(release.wait(), 5)
        return user

    async def observe_actor(*args, **kwargs):
        actor_entered.set()
        return await original_actor(*args, **kwargs)

    monkeypatch.setattr(refresh_sessions, "_locked_user", hold_logout)
    monkeypatch.setattr(service, "lock_actor", observe_actor)
    logout = asyncio.create_task(client.post("/api/v1/auth/logout", headers=headers))
    pending = None
    try:
        await asyncio.wait_for(locked.wait(), 2)
        if stage in ("commit", "replay"):
            pending = asyncio.create_task(
                client.post(
                    BASE + "/commit", json=command, headers={**headers, "Idempotency-Key": key}
                )
            )
        elif stage == "recovery":
            pending = asyncio.create_task(
                client.get(BASE + "/actions/by-key", headers={**headers, "Idempotency-Key": key})
            )
        elif stage == "unlock":
            pending = asyncio.create_task(
                client.post(
                    BASE + "/unlock",
                    json={"branch_id": str(case.branch_id), "password": seed_owner["password"]},
                    headers=headers,
                )
            )
        elif stage == "preview":
            pending = asyncio.create_task(
                client.post(BASE + "/preview", json=body(case), headers=headers)
            )
        else:
            pending = asyncio.create_task(
                client.get(BASE + "/receipts/" + str(case.order_id), headers=headers)
            )
        await asyncio.wait_for(actor_entered.wait(), 2)
        observed_wait = False
        async with AsyncSessionLocal() as observer:
            for _ in range(50):
                count = await observer.scalar(
                    text(
                        "select count(*) from pg_stat_activity "
                        "where datname=current_database() and wait_event_type='Lock' "
                        "and query ilike '%users%' and query ilike '%FOR SHARE%'"
                    )
                )
                if count:
                    observed_wait = True
                    break
                await asyncio.sleep(0.01)
        assert observed_wait, "target never visibly waited on actor SHARE lock"
        assert not pending.done()
        release.set()
        logged_out = await asyncio.wait_for(logout, 3)
        assert logged_out.status_code == 200, logged_out.text
        denied = await asyncio.wait_for(pending, 3)
        assert denied.status_code == 401, denied.text
    finally:
        release.set()
        await asyncio.gather(logout, *([pending] if pending else []), return_exceptions=True)
    family = decode_token(headers["Authorization"].split(" ", 1)[1])["session_family_id"]
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(select(User.auth_version).where(User.id == case.owner_id))
            == original_version
        )
        rows = list(
            await verify.scalars(
                select(AuthRefreshSession).where(AuthRefreshSession.family_id == family)
            )
        )
        assert rows
        assert all(r.revoked_at is not None for r in rows)
        assert (
            len(
                list(
                    await verify.scalars(
                        select(PaidBillAmendmentIntent).where(
                            PaidBillAmendmentIntent.order_id == case.order_id
                        )
                    )
                )
            )
            == expected
        )
        assert (
            len(
                list(
                    await verify.scalars(
                        select(PosRefundRequest).where(PosRefundRequest.order_id == case.order_id)
                    )
                )
            )
            == expected
        )
        assert (
            await verify.scalar(select(Refund.id).where(Refund.order_id == case.order_id)) is None
        )


@pytest.mark.parametrize(
    "mutation",
    [
        "header_tax",
        "header_subtotal",
        "line_total",
        "missing_header",
        "missing_line",
        "all_voided",
        "voided_extra_ignored",
    ],
)
async def test_captured_priced_fiscal_source_mutations_fail_closed(session, paid, mutation):
    from copy import deepcopy

    case, _ = paid
    source = await session.scalar(
        text("select dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    source = deepcopy(source)
    if mutation == "header_tax":
        source["order"]["tax_minor"] += 1
    elif mutation == "header_subtotal":
        source["order"]["subtotal_minor"] += 1
    elif mutation == "line_total":
        source["lines"][0]["line_total_minor"] += 1
    elif mutation == "missing_header":
        source["order"].pop("tax_minor")
    elif mutation == "missing_line":
        source["lines"][0].pop("cgst_minor")
    elif mutation == "all_voided":
        source["lines"][0]["voided_at"] = "2026-11-13T00:00:00Z"
    else:
        source["lines"].append(
            {
                "voided_at": "2026-11-13T00:00:00Z",
                "line_total_minor": -999,
                "taxable_value_minor": None,
            }
        )
    blockers = await session.scalar(
        text("select dcompany_paid_bill_blockers(cast(:s as jsonb))"), {"s": json.dumps(source)}
    )
    assert bool(blockers) == (mutation != "voided_extra_ignored"), blockers


@pytest.mark.parametrize(
    "paid", [{"manual_discount_minor": 1000, "tax_rate": "0.05"}], indirect=True
)
async def test_valid_central_manual_discount_is_supported(client, paid):
    case, headers = paid
    assert case.amount_minor == 23500
    _, _, accepted = await accept(client, case, headers, target=20000)
    assert accepted["target_total_minor"] == 20000


@pytest.mark.parametrize(
    "paid", [{"manual_discount_minor": 23400, "tax_rate": "0.05"}], indirect=True
)
async def test_real_deep_discount_blocks_overallocated_full_refund(client, session, paid):
    case, headers = paid
    assert case.amount_minor == 1100
    review = await client.post(
        BASE + "/preview", json=body(case, target=0, operation="discard"), headers=headers
    )
    assert review.status_code == 200, review.text
    quote = review.json()
    assert not quote["commit_available"]
    assert any("allocation exceeds this exact refund" in x for x in quote["blockers"]), quote
    denied = await client.post(
        BASE + "/commit",
        json={
            **body(case, target=0, operation="discard"),
            "source_revision": quote["source_revision"],
            "source_fingerprint": quote["source_fingerprint"],
        },
        headers={**headers, "Idempotency-Key": "independent-deep:" + str(uuid4())},
    )
    assert denied.status_code == 422, denied.text
    from sqlalchemy import insert
    from sqlalchemy.exc import DBAPIError

    command = {
        **body(case, target=0, operation="discard"),
        "source_revision": quote["source_revision"],
        "source_fingerprint": quote["source_fingerprint"],
    }
    values = await raw_values(session, case, headers, command)
    values.update(operation="discard", target_minor=0, difference_minor=case.amount_minor)
    values["response_body"]["target_total_minor"] = 0
    await session.rollback()
    async with AsyncSessionLocal() as raw:
        with pytest.raises(DBAPIError, match="allocation exceeds this exact refund"):
            await raw.execute(insert(PaidBillAmendmentIntent.__table__).values(**values))
        await raw.rollback()
    async with AsyncSessionLocal() as verify:
        assert (
            await verify.scalar(
                select(PaidBillAmendmentIntent.id).where(
                    PaidBillAmendmentIntent.order_id == case.order_id
                )
            )
            is None
        )
        assert (
            await verify.scalar(
                select(PosRefundRequest.id).where(PosRefundRequest.order_id == case.order_id)
            )
            is None
        )


@pytest.mark.parametrize("paid", [{"base_minor": 21000, "tax_rate": "0.05"}], indirect=True)
async def test_prior_real_refund_component_rounding_blocks_one_minor_extra(client, paid):
    from app.models import Refund
    from tests.integration.test_paid_bill_amendments import finish_cash
    from tests.integration.test_pos_refund_settlement import _request_payload

    case, headers = paid
    assert case.amount_minor == 21000
    key = "independent-prior-round:" + str(uuid4())
    payload = _request_payload(case, action_id=key, mode="cash")
    payload["amount_minor"] = 20
    prior = await client.post(
        "/api/v1/pos/refund-requests",
        json=payload,
        headers={**headers, "Idempotency-Key": key, "X-Client-Action-Id": key},
    )
    assert prior.status_code == 201, prior.text
    await finish_cash(client, case, headers, prior.json()["id"], 20)
    review = await client.post(BASE + "/preview", json=body(case, target=20979), headers=headers)
    assert review.status_code == 200, review.text
    quote = review.json()
    assert quote["effective_total_minor"] == 20980
    assert quote["proposed_refund_minor"] == 1
    assert not quote["commit_available"]
    assert any("allocation exceeds this exact refund" in x for x in quote["blockers"]), quote
    async with AsyncSessionLocal() as verify:
        refunds = list(await verify.scalars(select(Refund).where(Refund.order_id == case.order_id)))
        assert len(refunds) == 1
        assert refunds[0].amount_minor == 20
        assert (
            await verify.scalar(
                select(PaidBillAmendmentIntent.id).where(
                    PaidBillAmendmentIntent.order_id == case.order_id
                )
            )
            is None
        )


@pytest.mark.parametrize(
    ("component", "cumulative", "total"),
    [
        (1, 1, 2),
        (999_999_999_998, 500_000_000_000, 999_999_999_999),
        (1, 499_999_999_999, 1_000_000_000_000),
        (1, 500_000_000_000, 1_000_000_000_000),
        (1, 500_000_000_001, 1_000_000_000_000),
        (999_999_999_999, 499_999_999_999, 1_000_000_000_000),
        (1_000_000_000_000, 999_999_999_999, 1_000_000_000_000),
        (1_000_000_000_000, 1_000_000_000_000, 1_000_000_000_000),
        (1_000_000_000_000, 1_000_000_000_001, 1_000_000_000_000),
        (0, 1, 2),
        (1, 0, 2),
    ],
)
async def test_supported_sql_allocation_matches_unchanged_decimal_accounting(
    session, component, cumulative, total
):
    from app.services.accounting.ledger import _proportional

    actual = await session.scalar(
        text("SELECT dcompany_paid_bill_proportional(:c,:r,:t)"),
        {"c": component, "r": cumulative, "t": total},
    )
    assert actual == _proportional(component, cumulative, total)


@pytest.mark.parametrize(
    "field", ["total_minor", "cgst_minor", "sgst_minor", "igst_minor", "cess_minor"]
)
async def test_over_bound_source_is_explicitly_outside_new_profile(session, paid, field):
    case, _ = paid
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    source["order"][field] = 1_000_000_000_001
    blockers = await session.scalar(
        text("SELECT dcompany_paid_bill_blockers(CAST(:src AS jsonb))"), {"src": json.dumps(source)}
    )
    assert blockers == ["Captured amounts exceed the supported paid-bill allocation profile."]


@pytest.mark.parametrize(
    "change",
    [
        "embedded_line_discount",
        "manual_discount",
        "negative_round_off",
        "maximum_supported",
    ],
)
async def test_captured_fiscal_equations_preserve_valid_discount_points_and_rounding(
    session, paid, change
):
    case, _ = paid
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    order = source["order"]
    line = source["lines"][0]
    if change == "embedded_line_discount":
        line["discount_minor"] = 1000
        order["discount_minor"] = 1000
    elif change == "manual_discount":
        order["manual_discount_minor"] = 1000
        order["discount_minor"] = 1000
        order["total_minor"] -= 1000
    elif change == "negative_round_off":
        order["round_off_minor"] = -1
        order["total_minor"] -= 1
    else:
        order["total_minor"] = order["subtotal_minor"] = 1_000_000_000_000
        line["line_total_minor"] = line["taxable_value_minor"] = order["total_minor"]
    source["payments"][0]["amount_minor"] = order["total_minor"]
    for settlement in source["loyalty_settlements"]:
        settlement["order_paid_minor"] = order["total_minor"]
    blockers = await session.scalar(
        text("SELECT dcompany_paid_bill_blockers(CAST(:src AS jsonb))"), {"src": json.dumps(source)}
    )
    assert blockers == []


async def test_header_redemption_without_customer_or_ledger_requires_reconciliation(session, paid):
    case, _ = paid
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    source["order"].update(
        customer_id=None,
        points_redeemed_minor=1000,
        discount_minor=1000,
        total_minor=case.amount_minor - 1000,
    )
    source["payments"][0]["amount_minor"] = case.amount_minor - 1000
    source["points_redemptions"] = []
    source["loyalty_settlements"] = []
    blockers = await session.scalar(
        text("SELECT dcompany_paid_bill_blockers(CAST(:src AS jsonb))"), {"src": json.dumps(source)}
    )
    assert blockers == ["Original customer/loyalty allocation needs reconciliation."]


@pytest.mark.parametrize("paid", [{"points": 100}], indirect=True)
async def test_genuine_consumed_redemption_is_supported_without_recomputing_rates(
    client, session, paid
):
    case, headers = paid
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    assert source["order"]["points_redeemed_minor"] == 1000
    assert len(source["points_redemptions"]) == 1
    redemption = source["points_redemptions"][0]
    assert redemption["consumed_at"] is not None
    assert redemption["amount_minor"] == 1000
    assert source["loyalty_settlements"][0]["points_redeemed"] == redemption["points_spent"] == 100
    _, _, accepted = await accept(client, case, headers, target=20000)
    assert accepted["target_total_minor"] == 20000


@pytest.mark.parametrize("paid", [{"points": 100}], indirect=True)
@pytest.mark.parametrize(
    "mutation",
    [
        "missing",
        "wrong_amount",
        "unconsumed",
        "wrong_points",
        "wrong_order",
        "wrong_customer",
        "wrong_company",
        "header_zero",
    ],
)
async def test_captured_redemption_lineage_must_match_exact_issued_discount(
    session, paid, mutation
):
    case, _ = paid
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    redemption = source["points_redemptions"][0]
    if mutation == "missing":
        source["points_redemptions"] = []
    elif mutation == "wrong_amount":
        redemption["amount_minor"] += 1
    elif mutation == "unconsumed":
        redemption["consumed_at"] = None
    elif mutation == "wrong_points":
        source["loyalty_settlements"][0]["points_redeemed"] += 1
    elif mutation == "wrong_order":
        redemption["order_id"] = str(uuid4())
    elif mutation == "wrong_customer":
        redemption["customer_id"] = str(uuid4())
    elif mutation == "wrong_company":
        source["loyalty_settlements"][0]["company_id"] = str(uuid4())
    else:
        source["order"]["points_redeemed_minor"] = 0
        source["order"]["discount_minor"] -= 1000
        source["order"]["total_minor"] += 1000
        source["payments"][0]["amount_minor"] += 1000
        source["loyalty_settlements"][0]["order_paid_minor"] += 1000
    blockers = await session.scalar(
        text("SELECT dcompany_paid_bill_blockers(CAST(:src AS jsonb))"), {"src": json.dumps(source)}
    )
    assert blockers == ["Original customer/loyalty allocation needs reconciliation."]


@pytest.mark.parametrize(
    "settlement", ["points_without_customer", "zero_points_without_customer", "malformed", "none"]
)
async def test_anonymous_zero_header_cannot_hide_existing_loyalty_facts(session, paid, settlement):
    case, _ = paid
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": case.order_id, "c": case.company_id, "s": case.shift_id},
    )
    assert source["order"]["points_redeemed_minor"] == 0
    assert source["points_redemptions"] == []
    source["order"]["customer_id"] = None
    if settlement == "none":
        source["loyalty_settlements"] = []
    elif settlement == "malformed":
        source["loyalty_settlements"] = [{}]
    else:
        source["loyalty_settlements"][0]["points_redeemed"] = (
            1 if settlement == "points_without_customer" else 0
        )
    blockers = await session.scalar(
        text("SELECT dcompany_paid_bill_blockers(CAST(:src AS jsonb))"), {"src": json.dumps(source)}
    )
    assert blockers == (
        []
        if settlement == "none"
        else ["Original customer/loyalty allocation needs reconciliation."]
    )
