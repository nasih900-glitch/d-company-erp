"""Selected-customer identity contract for gaming session starts."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select

from app.models import Company, Customer, GamingSession, Shift, Station
from app.services.customers.identity import get_selected_gaming_customer


async def _login(client, seed_owner) -> str:
    response = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


def _headers(token: str, terminal_id, key: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal_id),
        "Idempotency-Key": key,
    }


@pytest.mark.asyncio
async def test_selected_customer_lookup_is_live_and_tenant_scoped(session, seed_owner) -> None:
    other_company = Company(id=uuid4(), name="Other customer tenant")
    live = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        name="Live customer", phone="9000000001",
    )
    deleted = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        name="Deleted customer", phone="9000000002", deleted_at=datetime.now(UTC),
    )
    foreign = Customer(
        id=uuid4(), company_id=other_company.id,
        name="Foreign customer", phone="9000000003",
    )
    session.add(other_company)
    await session.flush()
    session.add_all([live, deleted, foreign])
    await session.flush()

    assert await get_selected_gaming_customer(
        session, company_id=seed_owner["company"].id, customer_id=live.id,
    ) is live
    assert await get_selected_gaming_customer(
        session, company_id=seed_owner["company"].id, customer_id=deleted.id,
    ) is None
    assert await get_selected_gaming_customer(
        session, company_id=seed_owner["company"].id, customer_id=foreign.id,
    ) is None
    await session.rollback()


@pytest.mark.asyncio
async def test_selected_start_uses_canonical_snapshots_and_replays_accepted_receipt(
    client, session, seed_owner,
) -> None:
    now = datetime.now(UTC)
    customer = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        name="Canonical name", phone="9123456780",
    )
    shift = Shift(
        id=uuid4(), company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id, terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id, opened_at=now - timedelta(minutes=5),
        opening_float_minor=0, expected_minor=0, status="open",
    )
    station = Station(
        id=uuid4(), company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id, code=f"LOOKUP-{uuid4().hex[:6]}",
        name="Customer lookup projector", type="projector",
        rate_per_hour_minor=6_000, is_active=True,
    )
    session.add_all([customer, shift, station])
    await session.commit()
    token = await _login(client, seed_owner)
    key = f"selected-customer:{uuid4()}"
    body = {
        "station_id": str(station.id),
        "shift_id": str(shift.id),
        "customer_id": str(customer.id),
        "customer_name": "Forged stale name",
        "customer_phone": "0000000000",
        "expected_rate_per_hour_minor": 6_000,
    }

    first = await client.post(
        "/api/v1/gaming/sessions/start",
        json=body,
        headers=_headers(token, seed_owner["terminal"].id, key),
    )
    assert first.status_code == 201, first.text
    assert first.json()["customer_id"] == str(customer.id)
    assert first.json()["customer_name"] == "Canonical name"
    assert first.json()["customer_phone"] == "9123456780"

    customer.name = "Changed after acceptance"
    customer.phone = "9234567890"
    customer.deleted_at = datetime.now(UTC)
    await session.commit()
    replay = await client.post(
        "/api/v1/gaming/sessions/start",
        json=body,
        headers=_headers(token, seed_owner["terminal"].id, key),
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == first.json()

    stored = await session.get(GamingSession, UUID(first.json()["id"]))
    assert stored is not None
    assert stored.customer_id == customer.id
    assert stored.customer_name == "Canonical name"
    assert stored.customer_phone == "9123456780"


@pytest.mark.asyncio
async def test_selected_start_hides_deleted_and_cross_tenant_ids(client, session, seed_owner) -> None:
    now = datetime.now(UTC)
    other_company = Company(id=uuid4(), name="Foreign start tenant")
    deleted = Customer(
        id=uuid4(), company_id=seed_owner["company"].id,
        name="Deleted", phone="9345678901", deleted_at=now,
    )
    foreign = Customer(
        id=uuid4(), company_id=other_company.id, name="Foreign", phone="9456789012",
    )
    shift = Shift(
        id=uuid4(), company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id, terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id, opened_at=now - timedelta(minutes=5),
        opening_float_minor=0, expected_minor=0, status="open",
    )
    stations = [
        Station(
            id=uuid4(), company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id, code=f"HIDDEN-{index}-{uuid4().hex[:4]}",
            name=f"Hidden customer station {index}", type="projector",
            rate_per_hour_minor=6_000, is_active=True,
        )
        for index in range(2)
    ]
    session.add(other_company)
    await session.flush()
    session.add_all([deleted, foreign, shift, *stations])
    await session.commit()
    token = await _login(client, seed_owner)

    for customer_id, station in zip((deleted.id, foreign.id), stations, strict=True):
        response = await client.post(
            "/api/v1/gaming/sessions/start",
            json={
                "station_id": str(station.id), "shift_id": str(shift.id),
                "customer_id": str(customer_id), "expected_rate_per_hour_minor": 6_000,
            },
            headers=_headers(token, seed_owner["terminal"].id, f"hidden:{uuid4()}"),
        )
        assert response.status_code == 404, response.text
        assert response.json()["error"]["message"] == "customer not found"

    rows = (await session.execute(select(GamingSession).where(
        GamingSession.station_id.in_([station.id for station in stations]),
    ))).scalars().all()
    assert rows == []
