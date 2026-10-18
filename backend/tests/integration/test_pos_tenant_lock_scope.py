"""Concurrency proofs for tenant-scoped POS row locks."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import pytest
from sqlalchemy import delete, select

from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.models import Branch, Company, Order, Shift, Terminal, User


@pytest.mark.integration
@pytest.mark.asyncio
async def test_foreign_pos_rows_do_not_block_payment_or_shift_close_scope_checks(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]

    foreign_company = Company(id=uuid4(), name=f"Foreign POS tenant {uuid4().hex[:8]}")
    session.add(foreign_company)
    await session.flush()

    foreign_branch = Branch(
        id=uuid4(),
        company_id=foreign_company.id,
        name="Foreign branch",
        invoice_series_code="FT",
    )
    foreign_user = User(
        id=uuid4(),
        company_id=foreign_company.id,
        email=f"foreign-pos-{uuid4().hex[:8]}@test.local",
        name="Foreign owner",
        password_hash=owner.password_hash,
        status="active",
    )
    session.add_all([foreign_branch, foreign_user])
    await session.flush()

    foreign_terminal = Terminal(
        id=uuid4(),
        branch_id=foreign_branch.id,
        name="Foreign terminal",
        device_id=f"foreign-pos-{uuid4()}",
    )
    session.add(foreign_terminal)
    await session.flush()

    foreign_shift = Shift(
        id=uuid4(),
        company_id=foreign_company.id,
        branch_id=foreign_branch.id,
        terminal_id=foreign_terminal.id,
        opened_by=foreign_user.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add(foreign_shift)
    await session.flush()

    foreign_order = Order(
        id=uuid4(),
        company_id=foreign_company.id,
        branch_id=foreign_branch.id,
        terminal_id=foreign_terminal.id,
        shift_id=foreign_shift.id,
        opened_by=foreign_user.id,
        type="takeaway",
        status="open",
        subtotal_minor=1_000,
        tax_minor=0,
        total_minor=1_000,
        opened_at=datetime.now(UTC),
    )
    mismatched_shift_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=foreign_shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="open",
        subtotal_minor=1_000,
        tax_minor=0,
        total_minor=1_000,
        opened_at=datetime.now(UTC),
    )
    session.add_all([foreign_order, mismatched_shift_order])
    await session.commit()

    token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal.id),
    }
    payment_body = {
        "method": "upi",
        "amount_minor": 1_000,
        "expected_order_total_minor": 1_000,
        "expected_due_minor": 1_000,
    }

    try:
        async with AsyncSessionLocal() as blocker:
            await blocker.execute(
                select(Order).where(Order.id == foreign_order.id).with_for_update()
            )
            await blocker.execute(
                select(Shift).where(Shift.id == foreign_shift.id).with_for_update()
            )
            try:
                foreign_order_payment = await asyncio.wait_for(
                    client.post(
                        f"/api/v1/pos/orders/{foreign_order.id}/payments",
                        headers={
                            **headers,
                            "Idempotency-Key": f"foreign-order-payment:{uuid4()}",
                        },
                        json=payment_body,
                    ),
                    timeout=2,
                )
                assert foreign_order_payment.status_code == 404, foreign_order_payment.text
                assert foreign_order_payment.json()["error"]["code"] == "not_found"

                foreign_shift_payment = await asyncio.wait_for(
                    client.post(
                        f"/api/v1/pos/orders/{mismatched_shift_order.id}/payments",
                        headers={
                            **headers,
                            "Idempotency-Key": f"foreign-shift-payment:{uuid4()}",
                        },
                        json=payment_body,
                    ),
                    timeout=2,
                )
                assert foreign_shift_payment.status_code == 404, foreign_shift_payment.text
                assert foreign_shift_payment.json()["error"]["code"] == "not_found"

                foreign_shift_close = await asyncio.wait_for(
                    client.post(
                        f"/api/v1/pos/shifts/{foreign_shift.id}/close",
                        headers=headers,
                        json={"counted_minor": 0},
                    ),
                    timeout=2,
                )
                assert foreign_shift_close.status_code == 404, foreign_shift_close.text
                assert foreign_shift_close.json()["error"]["code"] == "not_found"
            finally:
                await blocker.rollback()
    finally:
        async with AsyncSessionLocal() as cleanup:
            # Retain immutable Audit evidence while removing disposable business fixtures.
            await cleanup.execute(
                delete(Order).where(Order.id.in_([foreign_order.id, mismatched_shift_order.id]))
            )
            await cleanup.execute(delete(Shift).where(Shift.id == foreign_shift.id))
            await cleanup.execute(delete(Terminal).where(Terminal.id == foreign_terminal.id))
            await cleanup.execute(delete(User).where(User.id == foreign_user.id))
            await cleanup.execute(delete(Branch).where(Branch.id == foreign_branch.id))
            await cleanup.execute(delete(Company).where(Company.id == foreign_company.id))
            await cleanup.commit()
