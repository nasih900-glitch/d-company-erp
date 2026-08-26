"""Postgres-backed safety tests for paid gaming-session extensions."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.models import GamingPackage, GamingSession, Shift, Station


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


async def _running_package_session(session, seed_owner) -> tuple[GamingSession, UUID]:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]

    station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        code=f"PS5-{uuid4().hex[:8]}",
        name="Concurrency Test PS5",
        type="ps5",
        rate_per_hour_minor=20_000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    base_package = GamingPackage(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        station_type="ps5",
        variant="single",
        kind="base",
        name="Single 60 min",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    extension = GamingPackage(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        station_type="ps5",
        variant="single",
        kind="extension",
        name="Add 15 min",
        duration_minutes=15,
        price_minor=5_000,
        sort_order=1,
        is_active=True,
    )
    gaming_session = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=station.id,
        opened_by=owner.id,
        shift_id=shift.id,
        start_at=datetime.now(UTC),
        paused_minutes=0,
        rate_per_hour_minor=station.rate_per_hour_minor,
        package_id=base_package.id,
        timer_minutes=60,
        amount_minor=10_000,
        status="active",
        extra_controllers=0,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    session.add_all([station, shift, base_package, extension])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    return gaming_session, extension.id


def _headers(seed_owner, token: str, idempotency_key: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "Idempotency-Key": idempotency_key,
    }


@pytest.mark.asyncio
async def test_paid_extension_without_idempotency_key_is_rejected_without_mutation(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/extend",
        json={"package_id": str(extension_id)},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )

    assert response.status_code == 422, response.text
    assert "Idempotency-Key" in response.json()["error"]["message"]
    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 60
    assert gaming_session.amount_minor == 10_000


@pytest.mark.asyncio
async def test_exact_retry_replays_response_without_adding_minutes_twice(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)
    headers = _headers(seed_owner, token, f"gaming-extension:{uuid4()}")
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/extend"
    payload = {"package_id": str(extension_id)}

    first = await client.post(path, json=payload, headers=headers)
    replay = await client.post(path, json=payload, headers=headers)

    assert first.status_code == 200, first.text
    assert replay.status_code == 200, replay.text
    assert replay.json() == first.json()
    assert first.json()["timer_minutes"] == 75
    assert first.json()["amount_minor"] == 15_000

    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 75
    assert gaming_session.amount_minor == 15_000


@pytest.mark.asyncio
async def test_concurrent_distinct_extensions_are_serialized_without_lost_update(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/extend"
    payload = {"package_id": str(extension_id)}

    first, second = await asyncio.gather(
        client.post(
            path,
            json=payload,
            headers=_headers(seed_owner, token, f"gaming-extension:{uuid4()}"),
        ),
        client.post(
            path,
            json=payload,
            headers=_headers(seed_owner, token, f"gaming-extension:{uuid4()}"),
        ),
    )

    assert first.status_code == 200, first.text
    assert second.status_code == 200, second.text
    assert sorted([first.json()["timer_minutes"], second.json()["timer_minutes"]]) == [75, 90]
    assert sorted([first.json()["amount_minor"], second.json()["amount_minor"]]) == [
        15_000,
        20_000,
    ]

    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 90
    assert gaming_session.amount_minor == 20_000
