"""DB-backed tests for DELETE /customers/{id}.

Covers the fix for a real gap found during a live E2E run: there was no way
to remove a customer at all (DELETE returned 405). This is soft-delete +
anonymise, not a hard row delete — PointsRedemption.customer_id is
ON DELETE RESTRICT, so any customer who ever redeemed a reward would block
a real DELETE FROM customers. The important behaviour to protect here is
that deleting frees the phone number for reuse (the old unique constraint
didn't know about deleted_at and would keep blocking it forever) and that
a deleted customer disappears from every read path without breaking
anything that already referenced them.
"""

from __future__ import annotations

from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.models import Customer


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _login(client, seed_owner) -> str:
    r = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.asyncio
async def test_delete_customer_anonymises_and_frees_the_phone(client, session, seed_owner) -> None:
    token = await _login(client, seed_owner)
    phone = f"+91-9{uuid4().int % 10**9:09d}"

    created = (await client.post(
        "/api/v1/customers",
        json={"phone": phone, "name": "Test Person", "notes": "created by mistake"},
        headers=_auth(token),
    )).json()

    r = await client.delete(f"/api/v1/customers/{created['id']}", headers=_auth(token))
    assert r.status_code == 204, r.text

    # Gone from every normal read path.
    assert (await client.get(f"/api/v1/customers/{created['id']}", headers=_auth(token))).status_code == 404
    assert (await client.get(
        f"/api/v1/customers/by-phone/{phone}", headers=_auth(token),
    )).json() is None
    listed = (await client.get("/api/v1/customers", params={"q": "Test Person"}, headers=_auth(token))).json()
    assert created["id"] not in {c["id"] for c in listed}

    # PII scrubbed at the row level (not just filtered out of the API).
    row = await session.get(Customer, created["id"])
    await session.refresh(row)
    assert row.deleted_at is not None
    assert row.name is None
    assert row.notes is None
    assert row.phone != phone

    # The freed phone number can immediately be used by a new, unrelated customer.
    recreated = await client.post(
        "/api/v1/customers",
        json={"phone": phone, "name": "A Different Person"},
        headers=_auth(token),
    )
    assert recreated.status_code == 201, recreated.text
    assert recreated.json()["id"] != created["id"]
    assert recreated.json()["name"] == "A Different Person"


@pytest.mark.asyncio
async def test_delete_customer_twice_is_not_found(client, seed_owner) -> None:
    token = await _login(client, seed_owner)
    created = (await client.post(
        "/api/v1/customers",
        json={"phone": f"+91-8{uuid4().int % 10**9:09d}", "name": "Once"},
        headers=_auth(token),
    )).json()

    first = await client.delete(f"/api/v1/customers/{created['id']}", headers=_auth(token))
    assert first.status_code == 204

    second = await client.delete(f"/api/v1/customers/{created['id']}", headers=_auth(token))
    assert second.status_code == 404


@pytest.mark.asyncio
async def test_delete_customer_is_tenant_isolated(client, session, seed_owner) -> None:
    from app.models import Company

    other_company = Company(id=uuid4(), name=f"Other Co {uuid4().hex[:6]}")
    session.add(other_company)
    other_customer = Customer(
        id=uuid4(), company_id=other_company.id, phone=f"+91-7{uuid4().int % 10**9:09d}", name="Not Yours",
    )
    session.add(other_customer)
    await session.commit()

    token = await _login(client, seed_owner)
    r = await client.delete(f"/api/v1/customers/{other_customer.id}", headers=_auth(token))
    assert r.status_code == 404
