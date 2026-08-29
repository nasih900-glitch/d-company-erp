"""DB-backed contract tests for stable customer purchase history."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.models import Customer, Order, Payment, Shift


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _login(client, seed_owner) -> str:
    response = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


def _headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_customer_history_is_stably_linked_sorted_and_financially_complete(
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    customer = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        phone=f"9000{uuid4().int % 10**7:07d}",
        name="History Customer",
    )
    unrelated = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        phone=f"8000{uuid4().int % 10**7:07d}",
        name="Other Customer",
    )
    shift = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id,
        opened_at=now - timedelta(hours=2),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add_all([customer, unrelated, shift])
    await session.flush()

    older = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        customer_id=customer.id,
        customer_phone=customer.phone,
        type="dine_in",
        status="paid",
        subtotal_minor=12_000,
        discount_minor=200,
        manual_discount_minor=0,
        points_redeemed_minor=200,
        tax_minor=0,
        total_minor=11_800,
        opened_at=now - timedelta(hours=1),
        closed_at=now - timedelta(minutes=55),
        invoice_issued_at=now - timedelta(minutes=55),
        invoice_no=f"TEST/{uuid4().hex[:10]}",
        fiscal_year="2026-27",
        created_at=now - timedelta(hours=1),
    )
    newest = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        customer_id=customer.id,
        customer_phone=customer.phone,
        type="takeaway",
        status="void",
        subtotal_minor=5_000,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        tax_minor=0,
        total_minor=5_000,
        opened_at=now - timedelta(minutes=10),
        created_at=now - timedelta(minutes=10),
    )
    not_theirs = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        customer_id=unrelated.id,
        # Matching the requested customer's mutable phone snapshot must not
        # make this order appear in their stable history.
        customer_phone=customer.phone,
        type="dine_in",
        # This row exists only to prove customer_id beats a mutable phone
        # snapshot. Keep it an open non-financial order; a paid fixture without
        # immutable invoice/payment evidence is intentionally forbidden.
        status="open",
        subtotal_minor=9_999,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        tax_minor=0,
        total_minor=9_999,
        opened_at=now,
        created_at=now,
    )
    session.add_all([older, newest, not_theirs])
    await session.flush()
    session.add(
        Payment(
            id=uuid4(),
            order_id=older.id,
            shift_id=shift.id,
            method="upi",
            amount_minor=11_800,
            paid_at=now - timedelta(minutes=55),
        )
    )
    await session.commit()

    token = await _login(client, seed_owner)
    response = await client.get(
        f"/api/v1/customers/{customer.id}/history",
        headers=_headers(token),
    )
    assert response.status_code == 200, response.text
    rows = response.json()

    assert [row["id"] for row in rows] == [str(newest.id), str(older.id)]
    assert str(not_theirs.id) not in {row["id"] for row in rows}
    assert rows[0]["status"] == "void"
    assert rows[0]["paid_minor"] == 0
    assert rows[1]["invoice_no"] == older.invoice_no
    assert rows[1]["total_minor"] == 11_800
    assert rows[1]["paid_minor"] == 11_800
    assert rows[1]["refunded_minor"] == 0
    assert rows[1]["points_redeemed_minor"] == 200
    assert rows[1]["payment_methods"] == ["upi"]


@pytest.mark.asyncio
async def test_customer_history_rejects_another_company_customer(
    client,
    session,
    seed_owner,
) -> None:
    from app.models import Company

    other_company = Company(id=uuid4(), name=f"Other {uuid4().hex[:8]}")
    session.add(other_company)
    await session.flush()
    other_customer = Customer(
        id=uuid4(),
        company_id=other_company.id,
        phone=f"7000{uuid4().int % 10**7:07d}",
        name="Not Visible",
    )
    session.add(other_customer)
    await session.commit()

    token = await _login(client, seed_owner)
    response = await client.get(
        f"/api/v1/customers/{other_customer.id}/history",
        headers=_headers(token),
    )
    assert response.status_code == 404
