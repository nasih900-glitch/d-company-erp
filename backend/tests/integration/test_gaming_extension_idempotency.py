"""Postgres-backed safety tests for paid gaming-session extensions."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.models import (
    Branch,
    GamingPackage,
    GamingSession,
    GamingSessionExtension,
    IdempotencyKey,
    Order,
    Shift,
    Station,
)


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
        package_price_minor_snapshot=base_package.price_minor,
        package_duration_minutes_snapshot=base_package.duration_minutes,
        package_variant_snapshot=base_package.variant,
        package_station_type_snapshot=base_package.station_type,
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


def _extension_payload(extension_id: UUID, **overrides) -> dict:
    payload = {
        "package_id": str(extension_id),
        "expected_timer_minutes": 60,
        "expected_amount_minor": 10_000,
        "expected_package_price_minor": 5_000,
        "expected_package_duration_minutes": 15,
        "expected_package_variant": "single",
    }
    payload.update(overrides)
    return payload


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
        json=_extension_payload(extension_id),
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
    payload = _extension_payload(extension_id)

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
    ledger = (
        (
            await session.execute(
                select(GamingSessionExtension).where(
                    GamingSessionExtension.gaming_session_id == gaming_session.id
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(ledger) == 1
    assert ledger[0].duration_minutes == 15
    assert ledger[0].package_price_minor == 5_000
    assert ledger[0].total_minor == 5_000
    assert ledger[0].timer_before_minutes == 60
    assert ledger[0].timer_after_minutes == 75
    assert ledger[0].amount_before_minor == 10_000
    assert ledger[0].amount_after_minor == 15_000


@pytest.mark.asyncio
async def test_extension_ledger_recovers_exact_retry_after_idempotency_cache_purge(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)
    key = f"gaming-extension:{uuid4()}"
    headers = _headers(seed_owner, token, key)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/extend"
    payload = _extension_payload(extension_id)

    first = await client.post(path, json=payload, headers=headers)
    assert first.status_code == 200, first.text

    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=gaming_session.shift_id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="held",
        subtotal_minor=15_000,
        total_minor=15_000,
        opened_at=datetime.now(UTC),
    )
    session.add(order)
    await session.flush()
    await session.refresh(gaming_session)
    gaming_session.status = "ended"
    gaming_session.end_at = datetime.now(UTC)
    gaming_session.billable_minutes = 75
    gaming_session.order_id = order.id
    cached = await session.get(IdempotencyKey, key)
    assert cached is not None
    await session.delete(cached)
    await session.commit()

    recovered = await client.post(path, json=payload, headers=headers)

    assert recovered.status_code == 200, recovered.text
    assert recovered.json()["status"] == "ended"
    assert recovered.json()["order_id"] == str(order.id)
    assert recovered.json()["timer_minutes"] == 75
    assert recovered.json()["amount_minor"] == 15_000

    # Remove the rebuilt generic cache again so a changed payload is compared
    # directly with immutable ledger evidence rather than the cached hash.
    rebuilt = await session.get(IdempotencyKey, key)
    assert rebuilt is not None
    await session.delete(rebuilt)
    await session.commit()
    mismatch = await client.post(
        path,
        json=_extension_payload(extension_id, expected_amount_minor=9_999),
        headers=headers,
    )

    assert mismatch.status_code == 409, mismatch.text
    assert mismatch.json()["error"]["code"] == "conflict"
    assert mismatch.json()["error"]["code"] != "gaming_extension_not_applied"
    await session.refresh(gaming_session)
    assert gaming_session.amount_minor == 15_000
    ledger = (
        (
            await session.execute(
                select(GamingSessionExtension).where(
                    GamingSessionExtension.gaming_session_id == gaming_session.id
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(ledger) == 1


@pytest.mark.asyncio
async def test_ended_session_without_extension_receipt_returns_typed_not_applied_proof(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    gaming_session.status = "ended"
    gaming_session.end_at = datetime.now(UTC)
    gaming_session.billable_minutes = 60
    await session.commit()
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/extend"
    payload = _extension_payload(extension_id)

    rejected = await client.post(
        path,
        json=payload,
        headers=_headers(seed_owner, token, f"never-applied-extension:{uuid4()}"),
    )
    invalid_before_handler = await client.post(
        path,
        json={key: value for key, value in payload.items() if key != "expected_amount_minor"},
        headers=_headers(seed_owner, token, f"invalid-extension:{uuid4()}"),
    )

    assert rejected.status_code == 409, rejected.text
    assert rejected.json()["error"]["code"] == "gaming_extension_not_applied"
    assert rejected.json()["error"]["details"] == {
        "session_id": str(gaming_session.id),
        "session_status": "ended",
        "reason_code": "session_not_running",
    }
    assert invalid_before_handler.status_code == 422, invalid_before_handler.text
    assert invalid_before_handler.json()["error"]["code"] == "validation_error"
    ledger = (
        (
            await session.execute(
                select(GamingSessionExtension).where(
                    GamingSessionExtension.gaming_session_id == gaming_session.id
                )
            )
        )
        .scalars()
        .all()
    )
    assert ledger == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("failure", "expected_reason_code"),
    [
        ("stale_session_snapshot", "session_snapshot_stale"),
        ("retired_package", "extension_package_retired"),
        ("variant_mismatch", "package_variant_incompatible"),
        ("closed_shift", "source_shift_closed"),
    ],
)
async def test_scoped_ledger_absent_extension_refusals_are_typed_discard_proof(
    failure,
    expected_reason_code,
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    extension = await session.get(GamingPackage, extension_id)
    shift = await session.get(Shift, gaming_session.shift_id)
    payload = _extension_payload(extension_id)
    if failure == "stale_session_snapshot":
        payload["expected_amount_minor"] = 9_999
    elif failure == "retired_package":
        extension.is_active = False
    elif failure == "variant_mismatch":
        extension.variant = "dual"
        payload["expected_package_variant"] = "dual"
    else:
        shift.status = "closed"
        shift.closed_at = datetime.now(UTC)
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/extend",
        json=payload,
        headers=_headers(seed_owner, token, f"safe-rejection:{failure}:{uuid4()}"),
    )

    assert response.status_code == 409, response.text
    assert response.json()["error"]["code"] == "gaming_extension_not_applied"
    assert response.json()["error"]["details"]["reason_code"] == expected_reason_code
    ledger = (
        (
            await session.execute(
                select(GamingSessionExtension).where(
                    GamingSessionExtension.gaming_session_id == gaming_session.id
                )
            )
        )
        .scalars()
        .all()
    )
    assert ledger == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("failure", "expected_code"),
    [
        ("request_validation", "validation_error"),
        ("idempotency_conflict", "idempotency_conflict"),
        ("ledger_mismatch", "conflict"),
        ("wrong_scope", "not_found"),
    ],
)
async def test_unverified_extension_failures_never_emit_discard_proof(
    failure,
    expected_code,
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/extend"
    payload = _extension_payload(extension_id)
    key = f"unsafe-rejection:{failure}:{uuid4()}"

    if failure == "request_validation":
        payload.pop("expected_amount_minor")
    elif failure in {"idempotency_conflict", "ledger_mismatch"}:
        first = await client.post(
            path,
            json=payload,
            headers=_headers(seed_owner, token, key),
        )
        assert first.status_code == 200, first.text
        if failure == "ledger_mismatch":
            cached = await session.get(IdempotencyKey, key)
            assert cached is not None
            await session.delete(cached)
            await session.commit()
        payload["expected_amount_minor"] = 9_999
    else:
        other_branch = Branch(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            name=f"Wrong extension scope {uuid4().hex[:8]}",
        )
        station = await session.get(Station, gaming_session.station_id)
        session.add(other_branch)
        await session.flush()
        station.branch_id = other_branch.id
        await session.commit()

    response = await client.post(
        path,
        json=payload,
        headers=_headers(seed_owner, token, key),
    )

    assert response.status_code in {404, 409, 422}, response.text
    assert response.json()["error"]["code"] == expected_code
    assert response.json()["error"]["code"] != "gaming_extension_not_applied"


@pytest.mark.asyncio
async def test_concurrent_extensions_from_one_snapshot_charge_exactly_once(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/extend"
    payload = _extension_payload(extension_id)

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

    assert sorted([first.status_code, second.status_code]) == [200, 409]
    success = first if first.status_code == 200 else second
    assert success.json()["timer_minutes"] == 75
    assert success.json()["amount_minor"] == 15_000

    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 75
    assert gaming_session.amount_minor == 15_000
    ledger_count = len(
        (
            await session.execute(
                select(GamingSessionExtension).where(
                    GamingSessionExtension.gaming_session_id == gaming_session.id
                )
            )
        )
        .scalars()
        .all()
    )
    assert ledger_count == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("override", "value"),
    [
        ("expected_package_price_minor", 4_999),
        ("expected_package_duration_minutes", 30),
        ("expected_package_variant", "dual"),
    ],
)
async def test_paid_extension_rejects_stale_catalog_snapshot(
    override,
    value,
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/extend",
        json=_extension_payload(extension_id, **{override: value}),
        headers=_headers(seed_owner, token, f"stale-gaming-extension:{uuid4()}"),
    )

    assert response.status_code == 409, response.text
    assert response.json()["error"]["code"] == "gaming_extension_not_applied"
    assert response.json()["error"]["details"]["reason_code"] == (
        "extension_package_snapshot_stale"
    )
    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 60
    assert gaming_session.amount_minor == 10_000


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("snapshot_field", "stale_value"),
    [
        ("expected_timer_minutes", 75),
        ("expected_amount_minor", 15_000),
    ],
)
async def test_paid_extension_rejects_stale_session_total_without_ledger_write(
    snapshot_field,
    stale_value,
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/extend",
        json=_extension_payload(extension_id, **{snapshot_field: stale_value}),
        headers=_headers(seed_owner, token, f"stale-session-extension:{uuid4()}"),
    )

    assert response.status_code == 409, response.text
    assert response.json()["error"]["code"] == "gaming_extension_not_applied"
    assert response.json()["error"]["details"]["reason_code"] == ("session_snapshot_stale")
    assert "changed on another device" in response.json()["error"]["message"]
    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 60
    assert gaming_session.amount_minor == 10_000
    ledger = (
        (
            await session.execute(
                select(GamingSessionExtension).where(
                    GamingSessionExtension.gaming_session_id == gaming_session.id
                )
            )
        )
        .scalars()
        .all()
    )
    assert ledger == []


@pytest.mark.asyncio
async def test_paid_extension_must_match_original_base_variant(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    extension = await session.get(GamingPackage, extension_id)
    extension.variant = "dual"
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/extend",
        json=_extension_payload(extension_id, expected_package_variant="dual"),
        headers=_headers(seed_owner, token, f"wrong-variant-extension:{uuid4()}"),
    )

    assert response.status_code == 409, response.text
    assert response.json()["error"]["code"] == "gaming_extension_not_applied"
    assert response.json()["error"]["details"]["reason_code"] == ("package_variant_incompatible")
    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 60
    assert gaming_session.amount_minor == 10_000


@pytest.mark.asyncio
async def test_paid_extension_ledger_rejects_direct_update_and_delete(
    client,
    session,
    seed_owner,
) -> None:
    gaming_session, extension_id = await _running_package_session(session, seed_owner)
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/extend",
        json=_extension_payload(extension_id),
        headers=_headers(seed_owner, token, f"immutable-gaming-extension:{uuid4()}"),
    )

    assert response.status_code == 200, response.text
    ledger_id = (
        await session.execute(
            select(GamingSessionExtension.id).where(
                GamingSessionExtension.gaming_session_id == gaming_session.id
            )
        )
    ).scalar_one()

    with pytest.raises(DBAPIError, match="append-only"):
        await session.execute(
            text(
                "UPDATE gaming_session_extensions "
                "SET package_name = 'tampered' WHERE id = :ledger_id"
            ),
            {"ledger_id": ledger_id},
        )
    await session.rollback()

    with pytest.raises(DBAPIError, match="append-only"):
        await session.execute(
            text("DELETE FROM gaming_session_extensions WHERE id = :ledger_id"),
            {"ledger_id": ledger_id},
        )
    await session.rollback()

    assert await session.get(GamingSessionExtension, ledger_id) is not None
