"""Real transaction proof that tips cannot be paid to staff twice concurrently."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import func, select

from app.api.v1.finance import router as finance_router
from app.core.security import issue_access_token
from app.models import Order, Payment, Shift, TipPayout


@pytest.mark.integration
@pytest.mark.asyncio
async def test_parallel_tip_payouts_share_one_company_balance(
    client, session, seed_owner, monkeypatch,
) -> None:
    now = datetime.now(UTC)
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id,
        opened_at=now - timedelta(hours=1), opening_float_minor=0,
        expected_minor=2_000, status="open",
    )
    session.add(shift)
    await session.flush()
    order = Order(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
        type="takeaway", status="paid", subtotal_minor=1_000,
        tip_minor=1_000, total_minor=2_000,
        opened_at=now - timedelta(minutes=30),
        closed_at=now - timedelta(minutes=20),
        invoice_issued_at=now - timedelta(minutes=20),
        invoice_no=f"D/QA/26-27/{uuid4().int % 100000:05d}",
        fiscal_year="2026-27",
    )
    session.add(order)
    await session.flush()
    session.add(Payment(
        id=uuid4(), order_id=order.id, shift_id=shift.id, method="cash",
        amount_minor=2_000, tendered_minor=2_000, change_minor=0,
        paid_at=now - timedelta(minutes=20),
    ))
    await session.commit()

    original_ledger = finance_router.build_operational_ledger
    first_balance_read = asyncio.Event()
    finish_first = asyncio.Event()
    reads = 0

    async def pause_first_payout_after_balance_read(*args, **kwargs):
        nonlocal reads
        ledger = await original_ledger(*args, **kwargs)
        reads += 1
        if reads == 1:
            first_balance_read.set()
            await asyncio.wait_for(finish_first.wait(), timeout=10)
        return ledger

    monkeypatch.setattr(
        finance_router, "build_operational_ledger",
        pause_first_payout_after_balance_read,
    )
    token = issue_access_token(
        user_id=owner.id, company_id=company.id, branch_id=branch.id,
        roles=["owner"], auth_version=owner.auth_version,
    )
    payload = {
        "branch_id": str(branch.id), "amount_minor": 1_000, "method": "cash",
        "paid_at": now.isoformat(), "note": "Distribute earned staff tips",
    }

    async def payout():
        return await client.post(
            "/api/v1/finance/tip-payouts", json=payload,
            headers={"Authorization": f"Bearer {token}",
                     "Idempotency-Key": f"tip-race-{uuid4()}"},
        )

    first = asyncio.create_task(payout())
    await asyncio.wait_for(first_balance_read.wait(), timeout=10)
    second = asyncio.create_task(payout())
    # The first transaction still holds its accepted balance. A competing
    # request must wait at the common company lock before reading that balance.
    await asyncio.sleep(0.2)
    finish_first.set()
    responses = await asyncio.wait_for(asyncio.gather(first, second), timeout=15)
    assert sum(response.status_code == 201 for response in responses) == 1, [
        (response.status_code, response.text) for response in responses
    ]
    rejected = next(response for response in responses if response.status_code != 201)
    assert rejected.status_code == 422, rejected.text
    assert "exceeds" in rejected.json()["error"]["message"]
    paid_out = (await session.execute(
        select(func.sum(TipPayout.amount_minor)).where(
            TipPayout.company_id == company.id, TipPayout.voided_at.is_(None),
        )
    )).scalar_one()
    assert paid_out == 1_000


@pytest.mark.integration
@pytest.mark.asyncio
async def test_tip_payout_rejects_future_and_ambiguous_times(client, seed_owner) -> None:
    owner = seed_owner["owner"]
    token = issue_access_token(
        user_id=owner.id, company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id, roles=["owner"],
        auth_version=owner.auth_version,
    )
    for paid_at in (
        (datetime.now(UTC) + timedelta(days=1)).isoformat(),
        "2026-08-01T12:00:00",
    ):
        response = await client.post(
            "/api/v1/finance/tip-payouts",
            json={
                "branch_id": str(seed_owner["branch"].id), "amount_minor": 100,
                "method": "cash", "paid_at": paid_at,
                "note": "Invalid payout time regression",
            },
            headers={"Authorization": f"Bearer {token}",
                     "Idempotency-Key": f"invalid-tip-time-{uuid4()}"},
        )
        assert response.status_code == 422, response.text
        assert "future" in response.text or "timezone" in response.text
