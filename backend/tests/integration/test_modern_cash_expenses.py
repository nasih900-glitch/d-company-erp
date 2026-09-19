"""Modern explicit-shift cash paid-outs and drawer serialization."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import delete, func, select

from app.core.security import hash_password, issue_access_token
from app.models import (
    Branch,
    Expense,
    ExpenseCategory,
    ExpenseReceipt,
    IdempotencyKey,
    ManualCollection,
    Order,
    Payment,
    Shift,
    Terminal,
    TipPayout,
    User,
)


def _token(seed, *, user: User | None = None, branch_id: UUID | None = None) -> str:
    actor = user or seed["owner"]
    return issue_access_token(
        user_id=actor.id,
        company_id=seed["company"].id,
        branch_id=branch_id if branch_id is not None else seed["branch"].id,
        roles=["manager"] if user is not None else ["owner"],
        auth_version=actor.auth_version,
    )


def _headers(
    seed,
    *,
    key: str | None = None,
    user: User | None = None,
    terminal_id: UUID | None = None,
) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {_token(seed, user=user)}"}
    if key is not None:
        headers["Idempotency-Key"] = key
    if terminal_id is not None:
        headers["X-Terminal-Id"] = str(terminal_id)
    return headers


def _payload(
    seed,
    category: ExpenseCategory,
    shift: Shift,
    *,
    amount_minor: int = 1_500,
    paid_via: str = "cash",
) -> dict[str, object]:
    return {
        "branch_id": str(seed["branch"].id),
        "shift_id": str(shift.id),
        "category_id": str(category.id),
        "amount_minor": amount_minor,
        "paid_via": paid_via,
        "paid_at": datetime.now(UTC).isoformat(),
        "vendor_name": "Modern paid-out vendor",
        "invoice_no": f"CASH-{uuid4().hex[:8]}",
        "note": "Cash paid from the selected open drawer",
    }


async def _seed_open_shift(session, seed, *, expected_minor: int = 10_000):
    colleague = User(
        id=uuid4(),
        company_id=seed["company"].id,
        email=f"cash-colleague-{uuid4().hex[:8]}@test.local",
        name="Authorised finance colleague",
        password_hash=hash_password("password1234"),
        status="active",
    )
    category = ExpenseCategory(
        id=uuid4(),
        company_id=seed["company"].id,
        name=f"Cash paid-out {uuid4().hex[:8]}",
        code=f"CP{uuid4().hex[:10]}",
    )
    shift = Shift(
        id=uuid4(),
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        terminal_id=seed["terminal"].id,
        opened_by=seed["owner"].id,
        opened_at=datetime.now(UTC) - timedelta(hours=1),
        opening_float_minor=expected_minor,
        expected_minor=expected_minor,
        status="open",
    )
    session.add_all([colleague, category, shift])
    await session.commit()
    return colleague, category, shift


@pytest.mark.integration
@pytest.mark.asyncio
async def test_modern_cash_expense_cross_user_replay_and_void_restore(
    client,
    session,
    seed_owner,
) -> None:
    colleague, category, shift = await _seed_open_shift(session, seed_owner)
    key = f"expense:{uuid4()}"
    payload = _payload(seed_owner, category, shift)
    headers = _headers(seed_owner, key=key, user=colleague)

    created = await client.post(
        "/api/v1/finance/expenses",
        headers=headers,
        json=payload,
    )
    assert created.status_code == 201, created.text
    body = created.json()
    assert body["shift_id"] == str(shift.id)
    assert body["created_by"] == str(colleague.id)
    assert shift.opened_by != colleague.id
    await session.refresh(shift)
    assert shift.expected_minor == 8_500

    row = await session.get(Expense, UUID(body["id"]))
    assert row is not None
    assert row.source_integrity_revision == 52
    assert row.idempotency_key == key
    assert row.created_by == colleague.id
    assert len(row.request_hash or "") == 64

    replay = await client.post(
        "/api/v1/finance/expenses",
        headers=headers,
        json=payload,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == body
    await session.refresh(shift)
    assert shift.expected_minor == 8_500

    # The immutable Expense receipt remains authoritative after the generic
    # idempotency cache is cleaned up.
    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key == key))
    await session.commit()
    durable_replay = await client.post(
        "/api/v1/finance/expenses",
        headers=headers,
        json=payload,
    )
    assert durable_replay.status_code == 201, durable_replay.text
    assert durable_replay.json() == body
    await session.refresh(shift)
    assert shift.expected_minor == 8_500

    # A different authorised user may attach the paper bill to the expense
    # without taking ownership of the original paid-out record. Network retry
    # must return the retained evidence rather than appending another file.
    receipt_key = f"expense-receipt:{uuid4()}"
    receipt_bytes = b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
    receipt_request = {
        "headers": _headers(seed_owner, key=receipt_key),
        "data": {"source": "file"},
        "files": {"file": ("cross-user-bill.pdf", receipt_bytes, "application/pdf")},
    }
    uploaded = await client.post(
        f"/api/v1/finance/expenses/{row.id}/receipts",
        **receipt_request,
    )
    assert uploaded.status_code == 201, uploaded.text
    upload_replay = await client.post(
        f"/api/v1/finance/expenses/{row.id}/receipts",
        **receipt_request,
    )
    assert upload_replay.status_code == 201, upload_replay.text
    assert upload_replay.json() == uploaded.json()
    receipt_row = await session.get(ExpenseReceipt, UUID(uploaded.json()["id"]))
    assert receipt_row is not None
    assert receipt_row.uploader_user_id == seed_owner["owner"].id
    assert (
        await session.execute(
            select(func.count(ExpenseReceipt.id)).where(
                ExpenseReceipt.expense_id == row.id
            )
        )
    ).scalar_one() == 1

    # The offline-recovery lookup remains authoritative after its generic
    # response-cache row is pruned: the immutable Expense action receipt is
    # sufficient to prove that the parent exists on the server.
    action_id = UUID(key.removeprefix("expense:"))
    reconciled = await client.get(
        f"/api/v1/finance/expenses/actions/{action_id}/reconciliation",
        headers=_headers(seed_owner, user=colleague),
        params={"branch_id": str(seed_owner["branch"].id)},
    )
    assert reconciled.status_code == 200, reconciled.text
    assert reconciled.json()["state"] == "accepted"
    assert reconciled.json()["expense"]["id"] == str(row.id)
    wrong_actor = await client.get(
        f"/api/v1/finance/expenses/actions/{action_id}/reconciliation",
        headers=_headers(seed_owner),
        params={"branch_id": str(seed_owner["branch"].id)},
    )
    assert wrong_actor.status_code == 404, wrong_actor.text
    absent = await client.get(
        f"/api/v1/finance/expenses/actions/{uuid4()}/reconciliation",
        headers=_headers(seed_owner, user=colleague),
        params={"branch_id": str(seed_owner["branch"].id)},
    )
    assert absent.status_code == 200, absent.text
    assert absent.json()["state"] == "absent"

    changed = dict(payload)
    changed["amount_minor"] = 1_600
    mismatch = await client.post(
        "/api/v1/finance/expenses",
        headers=headers,
        json=changed,
    )
    assert mismatch.status_code == 409, mismatch.text
    await session.refresh(shift)
    assert shift.expected_minor == 8_500

    voided, concurrent_duplicate = await asyncio.gather(
        client.post(
            f"/api/v1/finance/expenses/{row.id}/void",
            headers=_headers(seed_owner),
            json={"reason": "Vendor refunded the full cash paid-out"},
        ),
        client.post(
            f"/api/v1/finance/expenses/{row.id}/void",
            headers=_headers(seed_owner),
            json={"reason": "Vendor refunded the full cash paid-out"},
        ),
    )
    assert voided.status_code == 200, voided.text
    assert concurrent_duplicate.status_code == 200, concurrent_duplicate.text
    assert voided.json()["is_voided"] is True
    await session.refresh(shift)
    assert shift.expected_minor == 10_000

    duplicate_void = await client.post(
        f"/api/v1/finance/expenses/{row.id}/void",
        headers=_headers(seed_owner),
        json={"reason": "Vendor refunded the full cash paid-out"},
    )
    assert duplicate_void.status_code == 200, duplicate_void.text
    await session.refresh(shift)
    assert shift.expected_minor == 10_000

    conflicting_void = await client.post(
        f"/api/v1/finance/expenses/{row.id}/void",
        headers=_headers(seed_owner),
        json={"reason": "A different correction reason"},
    )
    assert conflicting_void.status_code == 422, conflicting_void.text
    await session.refresh(shift)
    assert shift.expected_minor == 10_000

    replay_after_void = await client.post(
        "/api/v1/finance/expenses",
        headers=headers,
        json=payload,
    )
    assert replay_after_void.status_code == 201, replay_after_void.text
    assert replay_after_void.json() == body
    await session.refresh(shift)
    assert shift.expected_minor == 10_000


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cross_user_manual_collection_and_tip_payout_move_owner_drawer_once(
    client,
    session,
    seed_owner,
) -> None:
    colleague, _category, shift = await _seed_open_shift(session, seed_owner)
    seed_owner["company"].gst_registration_type = "unregistered"
    await session.commit()
    await session.refresh(shift)
    opening_expected = int(shift.expected_minor)

    manual_key = f"manual-collection:{uuid4()}"
    private_source_ref = f"PRIVATE-SHEET-ROW-{uuid4()}"
    manual_payload = {
        "branch_id": str(seed_owner["branch"].id),
        "shift_id": str(shift.id),
        "business_date": datetime.now(UTC).date().isoformat(),
        "method": "cash",
        "amount_minor": 2_500,
        "source_ref": private_source_ref,
        "note": "Authorised manager counted this off-POS cash",
    }
    manager_headers = _headers(
        seed_owner,
        key=manual_key,
        user=colleague,
    )
    manual = await client.post(
        "/api/v1/finance/manual-collections",
        headers=manager_headers,
        json=manual_payload,
    )
    assert manual.status_code == 201, manual.text
    assert manual.json()["created_by"] == str(colleague.id)
    assert manual.json()["created_by_name"] == colleague.name
    assert shift.opened_by == seed_owner["owner"].id
    await session.refresh(shift)
    assert shift.expected_minor == opening_expected + 2_500

    manual_replay = await client.post(
        "/api/v1/finance/manual-collections",
        headers=manager_headers,
        json=manual_payload,
    )
    assert manual_replay.status_code == 201, manual_replay.text
    assert manual_replay.json() == manual.json()
    await session.execute(
        delete(IdempotencyKey).where(IdempotencyKey.key == manual_key)
    )
    await session.commit()
    durable_manual_replay = await client.post(
        "/api/v1/finance/manual-collections",
        headers=manager_headers,
        json=manual_payload,
    )
    assert durable_manual_replay.status_code == 201, durable_manual_replay.text
    assert durable_manual_replay.json() == manual.json()
    await session.refresh(shift)
    assert shift.expected_minor == opening_expected + 2_500

    manual_void = await client.post(
        f"/api/v1/finance/manual-collections/{manual.json()['id']}/void",
        headers=_headers(seed_owner),
        json={"reason": "Manager confirmed this collection was entered twice"},
    )
    assert manual_void.status_code == 200, manual_void.text
    assert manual_void.json()["created_by"] == str(colleague.id)
    assert manual_void.json()["voided_by"] == str(seed_owner["owner"].id)
    await session.refresh(shift)
    assert shift.expected_minor == opening_expected
    manual_row = await session.get(ManualCollection, UUID(manual.json()["id"]))
    assert manual_row is not None
    assert manual_row.created_by == colleague.id
    assert manual_row.voided_by == seed_owner["owner"].id

    now = datetime.now(UTC)
    tipped_order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="takeaway",
        status="paid",
        subtotal_minor=1_000,
        tip_minor=1_000,
        total_minor=2_000,
        opened_at=now - timedelta(minutes=10),
        closed_at=now - timedelta(minutes=5),
        invoice_issued_at=now - timedelta(minutes=5),
        invoice_no=f"D/CROSS/26-27/{uuid4().int % 100000:05d}",
        fiscal_year="2026-27",
    )
    session.add(tipped_order)
    await session.flush()
    session.add(
        Payment(
            id=uuid4(),
            order_id=tipped_order.id,
            shift_id=shift.id,
            method="cash",
            amount_minor=2_000,
            tendered_minor=2_000,
            change_minor=0,
            paid_at=now - timedelta(minutes=5),
        )
    )
    await session.commit()
    await session.refresh(shift)
    before_tip = int(shift.expected_minor)

    tip_key = f"tip-payout:{uuid4()}"
    tip_payload = {
        "branch_id": str(seed_owner["branch"].id),
        "shift_id": str(shift.id),
        "amount_minor": 500,
        "method": "cash",
        "paid_at": now.isoformat(),
        "note": "Manager paid part of the tips owed to staff",
    }
    tip_headers = _headers(seed_owner, key=tip_key, user=colleague)
    tip = await client.post(
        "/api/v1/finance/tip-payouts",
        headers=tip_headers,
        json=tip_payload,
    )
    assert tip.status_code == 201, tip.text
    assert tip.json()["created_by"] == str(colleague.id)
    assert tip.json()["created_by_name"] == colleague.name
    await session.refresh(shift)
    assert shift.expected_minor == before_tip - 500

    tip_replay = await client.post(
        "/api/v1/finance/tip-payouts",
        headers=tip_headers,
        json=tip_payload,
    )
    assert tip_replay.status_code == 201, tip_replay.text
    assert tip_replay.json() == tip.json()
    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key == tip_key))
    await session.commit()
    durable_tip_replay = await client.post(
        "/api/v1/finance/tip-payouts",
        headers=tip_headers,
        json=tip_payload,
    )
    assert durable_tip_replay.status_code == 201, durable_tip_replay.text
    assert durable_tip_replay.json() == tip.json()
    await session.refresh(shift)
    assert shift.expected_minor == before_tip - 500

    tip_void = await client.post(
        f"/api/v1/finance/tip-payouts/{tip.json()['id']}/void",
        headers=_headers(seed_owner),
        json={"reason": "Staff returned the duplicate cash payout"},
    )
    assert tip_void.status_code == 200, tip_void.text
    assert tip_void.json()["created_by"] == str(colleague.id)
    assert tip_void.json()["voided_by"] == str(seed_owner["owner"].id)
    await session.refresh(shift)
    assert shift.expected_minor == before_tip
    tip_row = await session.get(TipPayout, UUID(tip.json()["id"]))
    assert tip_row is not None
    assert tip_row.created_by == colleague.id
    assert tip_row.voided_by == seed_owner["owner"].id


@pytest.mark.integration
@pytest.mark.asyncio
async def test_closed_shift_expense_correction_posts_only_in_current_period(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    colleague = User(
        id=uuid4(),
        company_id=company.id,
        email=f"correction-manager-{uuid4().hex[:8]}@test.local",
        name="Authorised correction manager",
        password_hash=hash_password("password1234"),
        status="active",
    )
    category = ExpenseCategory(
        id=uuid4(),
        company_id=company.id,
        name=f"Correction period proof {uuid4().hex[:8]}",
        code=f"CX{uuid4().hex[:10]}",
    )
    local_zone = ZoneInfo(company.timezone)
    current_day = datetime.now(local_zone).date()
    historical_day = current_day - timedelta(days=2)
    historical_paid_at = datetime(
        historical_day.year,
        historical_day.month,
        historical_day.day,
        12,
        tzinfo=local_zone,
    ).astimezone(UTC)
    original_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=historical_paid_at - timedelta(hours=1),
        opening_float_minor=10_000,
        expected_minor=10_000,
        status="open",
    )
    session.add_all([colleague, category, original_shift])
    await session.commit()

    expense_key = f"expense:{uuid4()}"
    expense = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, key=expense_key, user=colleague),
        json={
            "branch_id": str(branch.id),
            "shift_id": str(original_shift.id),
            "category_id": str(category.id),
            "amount_minor": 1_500,
            "paid_via": "cash",
            "paid_at": historical_paid_at.isoformat(),
            "vendor_name": "Historical paid-out correction proof",
            "invoice_no": f"COR-{uuid4().hex[:8]}",
            "note": "Original closed-shift source remains immutable",
        },
    )
    assert expense.status_code == 201, expense.text
    assert expense.json()["created_by"] == str(colleague.id)
    second_expense = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, key=f"expense:{uuid4()}", user=colleague),
        json={
            "branch_id": str(branch.id),
            "shift_id": str(original_shift.id),
            "category_id": str(category.id),
            "amount_minor": 700,
            "paid_via": "cash",
            "paid_at": historical_paid_at.isoformat(),
            "vendor_name": "Concurrent correction proof",
            "invoice_no": f"COR-RACE-{uuid4().hex[:8]}",
            "note": "Different correction keys must still reverse only once",
        },
    )
    assert second_expense.status_code == 201, second_expense.text
    await session.refresh(original_shift)
    assert original_shift.expected_minor == 7_800

    original_shift.closed_by = colleague.id
    original_shift.closed_at = historical_paid_at + timedelta(hours=1)
    original_shift.counted_minor = 7_800
    original_shift.variance_minor = 0
    original_shift.status = "closed"
    await session.commit()
    settlement_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=colleague.id,
        opened_at=datetime.now(UTC) - timedelta(hours=1),
        opening_float_minor=5_000,
        expected_minor=5_000,
        status="open",
    )
    session.add(settlement_shift)
    await session.commit()

    correction_key = f"expense-correction:{uuid4()}"
    correction_payload = {
        "settlement_shift_id": str(settlement_shift.id),
        "reason": "Owner approved full reversal in the current open drawer",
    }
    correction, concurrent_duplicate = await asyncio.gather(
        client.post(
            f"/api/v1/finance/expenses/{expense.json()['id']}/corrections",
            headers=_headers(seed_owner, key=correction_key),
            json=correction_payload,
        ),
        client.post(
            f"/api/v1/finance/expenses/{expense.json()['id']}/corrections",
            headers=_headers(seed_owner, key=correction_key),
            json=correction_payload,
        ),
    )
    assert correction.status_code == 201, correction.text
    assert concurrent_duplicate.status_code == 201, concurrent_duplicate.text
    assert concurrent_duplicate.json() == correction.json()
    assert correction.json()["amount_minor"] == 1_500
    assert correction.json()["corrected_by"] == str(owner.id)
    await session.refresh(settlement_shift)
    assert settlement_shift.expected_minor == 6_500

    correction_replay = await client.post(
        f"/api/v1/finance/expenses/{expense.json()['id']}/corrections",
        headers=_headers(seed_owner, key=correction_key),
        json=correction_payload,
    )
    assert correction_replay.status_code == 201, correction_replay.text
    assert correction_replay.json() == correction.json()
    await session.refresh(settlement_shift)
    assert settlement_shift.expected_minor == 6_500
    await session.refresh(original_shift)
    assert original_shift.status == "closed"
    assert original_shift.expected_minor == 7_800

    async def race_different_correction_key():
        return await client.post(
            f"/api/v1/finance/expenses/{second_expense.json()['id']}/corrections",
            headers=_headers(seed_owner, key=f"expense-correction:{uuid4()}"),
            json={
                "settlement_shift_id": str(settlement_shift.id),
                "reason": "Concurrent owner correction must apply exactly once",
            },
        )

    different_key_results = await asyncio.gather(
        race_different_correction_key(),
        race_different_correction_key(),
    )
    assert sorted(result.status_code for result in different_key_results) == [201, 422]
    rejected_correction = next(
        result for result in different_key_results if result.status_code == 422
    )
    assert "already been corrected" in rejected_correction.text
    await session.refresh(settlement_shift)
    assert settlement_shift.expected_minor == 7_200

    historical_pnl = await client.get(
        "/api/v1/finance/pnl",
        headers=_headers(seed_owner),
        params={
            "period_start": historical_day.isoformat(),
            "period_end": historical_day.isoformat(),
        },
    )
    assert historical_pnl.status_code == 200, historical_pnl.text
    assert historical_pnl.json()["expenses_minor"] == 2_200
    assert historical_pnl.json()["net_profit_minor"] == -2_200
    current_pnl = await client.get(
        "/api/v1/finance/pnl",
        headers=_headers(seed_owner),
        params={
            "period_start": current_day.isoformat(),
            "period_end": current_day.isoformat(),
        },
    )
    assert current_pnl.status_code == 200, current_pnl.text
    assert current_pnl.json()["expenses_minor"] == -2_200
    assert current_pnl.json()["net_profit_minor"] == 2_200

    historical_ledger = await client.get(
        "/api/v1/accounting/general-ledger",
        headers=_headers(seed_owner),
        params={
            "from_date": historical_day.isoformat(),
            "to_date": historical_day.isoformat(),
        },
    )
    assert historical_ledger.status_code == 200, historical_ledger.text
    original_lines = [
        line
        for line in historical_ledger.json()
        if line["ref_type"] == "expense"
        and line["ref_id"] == expense.json()["id"]
    ]
    assert len(original_lines) == 2
    assert sum(line["debit_minor"] for line in original_lines) == 1_500
    assert sum(line["credit_minor"] for line in original_lines) == 1_500
    assert not any(
        line["ref_type"] == "finance_source_correction"
        for line in historical_ledger.json()
    )

    current_ledger = await client.get(
        "/api/v1/accounting/general-ledger",
        headers=_headers(seed_owner),
        params={
            "from_date": current_day.isoformat(),
            "to_date": current_day.isoformat(),
        },
    )
    assert current_ledger.status_code == 200, current_ledger.text
    correction_lines = [
        line
        for line in current_ledger.json()
        if line["ref_type"] == "finance_source_correction"
        and line["ref_id"] == correction.json()["id"]
    ]
    assert len(correction_lines) == 2
    assert sum(line["debit_minor"] for line in correction_lines) == 1_500
    assert sum(line["credit_minor"] for line in correction_lines) == 1_500


@pytest.mark.integration
@pytest.mark.asyncio
async def test_modern_cash_expense_rejects_invalid_drawer_requests(
    client,
    session,
    seed_owner,
) -> None:
    colleague, category, shift = await _seed_open_shift(session, seed_owner)

    overdraw = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, key=f"expense:{uuid4()}", user=colleague),
        json=_payload(
            seed_owner,
            category,
            shift,
            amount_minor=10_001,
        ),
    )
    assert overdraw.status_code == 422, overdraw.text

    noncash = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, key=f"expense:{uuid4()}", user=colleague),
        json=_payload(seed_owner, category, shift, paid_via="upi"),
    )
    assert noncash.status_code == 422, noncash.text

    closed_shift = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id,
        opened_at=datetime.now(UTC) - timedelta(hours=2),
        closed_by=seed_owner["owner"].id,
        closed_at=datetime.now(UTC) - timedelta(hours=1),
        opening_float_minor=5_000,
        expected_minor=5_000,
        counted_minor=5_000,
        variance_minor=0,
        status="closed",
    )
    other_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Other cash branch {uuid4().hex[:8]}",
        invoice_series_code=f"C{uuid4().hex[:1].upper()}",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name=f"Other cash terminal {uuid4().hex[:8]}",
        device_id=f"cash-other-{uuid4()}",
    )
    other_shift = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=other_branch.id,
        terminal_id=other_terminal.id,
        opened_by=seed_owner["owner"].id,
        opened_at=datetime.now(UTC) - timedelta(hours=1),
        opening_float_minor=5_000,
        expected_minor=5_000,
        status="open",
    )
    session.add(other_branch)
    await session.flush()
    session.add(other_terminal)
    await session.flush()
    session.add_all([closed_shift, other_shift])
    await session.commit()

    closed = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, key=f"expense:{uuid4()}", user=colleague),
        json=_payload(seed_owner, category, closed_shift),
    )
    assert closed.status_code == 422, closed.text

    wrong_shift_payload = _payload(seed_owner, category, shift)
    wrong_shift_payload["shift_id"] = str(other_shift.id)
    wrong_shift = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, key=f"expense:{uuid4()}", user=colleague),
        json=wrong_shift_payload,
    )
    assert wrong_shift.status_code == 404, wrong_shift.text

    await session.refresh(shift)
    assert shift.expected_minor == 10_000
    assert (
        await session.execute(
            select(func.count(Expense.id)).where(
                Expense.company_id == seed_owner["company"].id,
                Expense.source_integrity_revision == 52,
            )
        )
    ).scalar_one() == 0


@pytest.mark.integration
@pytest.mark.asyncio
async def test_modern_cash_expense_void_rejects_closed_shift(
    client,
    session,
    seed_owner,
) -> None:
    colleague, category, shift = await _seed_open_shift(session, seed_owner)
    created = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(
            seed_owner,
            key=f"expense:{uuid4()}",
            user=colleague,
        ),
        json=_payload(seed_owner, category, shift),
    )
    assert created.status_code == 201, created.text
    await session.refresh(shift)
    assert shift.expected_minor == 8_500

    shift.closed_by = seed_owner["owner"].id
    shift.closed_at = datetime.now(UTC)
    shift.counted_minor = 8_500
    shift.variance_minor = 0
    shift.status = "closed"
    await session.commit()

    refused = await client.post(
        f"/api/v1/finance/expenses/{created.json()['id']}/void",
        headers=_headers(seed_owner),
        json={"reason": "Attempted correction after drawer close"},
    )
    assert refused.status_code == 422, refused.text
    await session.refresh(shift)
    assert shift.expected_minor == 8_500
    row = await session.get(Expense, UUID(created.json()["id"]))
    assert row is not None
    assert row.voided_at is None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_modern_cash_expense_serializes_with_shift_close(
    client,
    session,
    seed_owner,
) -> None:
    colleague, category, shift = await _seed_open_shift(session, seed_owner)
    create_request = client.post(
        "/api/v1/finance/expenses",
        headers=_headers(
            seed_owner,
            key=f"expense:{uuid4()}",
            user=colleague,
        ),
        json=_payload(seed_owner, category, shift),
    )
    close_request = client.post(
        f"/api/v1/pos/shifts/{shift.id}/close",
        headers=_headers(
            seed_owner,
            terminal_id=seed_owner["terminal"].id,
        ),
        json={"counted_minor": 10_000},
    )
    created, closed = await asyncio.gather(create_request, close_request)

    assert closed.status_code == 200, closed.text
    assert created.status_code in {201, 422}, created.text
    await session.refresh(shift)
    assert shift.status == "closed"
    expense_count = int(
        (
            await session.execute(
                select(func.count(Expense.id)).where(
                    Expense.shift_id == shift.id,
                    Expense.source_integrity_revision == 52,
                )
            )
        ).scalar_one()
    )
    if created.status_code == 201:
        assert expense_count == 1
        assert shift.expected_minor == 8_500
        assert shift.variance_minor == 1_500
    else:
        assert expense_count == 0
        assert shift.expected_minor == 10_000
        assert shift.variance_minor == 0
