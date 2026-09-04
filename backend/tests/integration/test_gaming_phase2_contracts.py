"""Postgres-backed contracts for Phase 2 gaming operations.

These tests use only per-test tenant data. They exercise the HTTP boundary so
idempotency hashing, transaction commits, row locks, and tenant/terminal scope
are covered together.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.core.security import hash_password
from app.models import (
    AuditLog,
    Branch,
    GamingPackage,
    GamingSession,
    GamingSessionExtension,
    IdempotencyKey,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    Refund,
    Role,
    Shift,
    Station,
    User,
    UserRole,
)
from app.services.audit.recorder import install_audit_listeners


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


def _headers(seed_owner, token: str, key: str, **extra: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "Idempotency-Key": key,
        **extra,
    }


def _station(seed_owner, *, station_type: str = "ps5", branch_id: UUID | None = None) -> Station:
    return Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=branch_id or seed_owner["branch"].id,
        code=f"{station_type.upper()}-{uuid4().hex[:8]}",
        name=f"Contract {station_type} {uuid4().hex[:4]}",
        type=station_type,
        rate_per_hour_minor=12_000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )


def _shift(
    seed_owner,
    *,
    opened_at: datetime | None = None,
    terminal_id: UUID | None = None,
) -> Shift:
    return Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=terminal_id or seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id,
        opened_at=opened_at or datetime.now(UTC) - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )


def _running_session(
    seed_owner,
    *,
    station: Station,
    shift: Shift,
    start_at: datetime | None = None,
    timer_minutes: int | None = None,
    package_id: UUID | None = None,
    billing_mode: str | None = None,
    package_price_minor_snapshot: int | None = None,
    package_duration_minutes_snapshot: int | None = None,
    package_variant_snapshot: str | None = None,
    package_station_type_snapshot: str | None = None,
    amount_minor: int | None = None,
    status: str = "active",
) -> GamingSession:
    return GamingSession(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        station_id=station.id,
        opened_by=seed_owner["owner"].id,
        shift_id=shift.id,
        start_at=start_at or datetime.now(UTC) - timedelta(minutes=20),
        paused_minutes=0,
        rate_per_hour_minor=station.rate_per_hour_minor,
        billing_mode=billing_mode or ("package" if package_id else "hourly"),
        package_id=package_id,
        package_price_minor_snapshot=package_price_minor_snapshot,
        package_duration_minutes_snapshot=package_duration_minutes_snapshot,
        package_variant_snapshot=package_variant_snapshot,
        package_station_type_snapshot=package_station_type_snapshot,
        timer_minutes=timer_minutes,
        amount_minor=amount_minor,
        status=status,
        extra_controllers=0,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )


async def _grant_protected_owner(session, seed_owner) -> None:
    protected_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="super_owner",
        name="Protected Owner",
        permissions=[],
    )
    session.add(protected_role)
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=seed_owner["owner"].id,
            role_id=protected_role.id,
        )
    )
    await session.commit()


def _offline_action_headers(
    seed_owner,
    token: str,
    key: str,
    captured_at: datetime,
) -> dict[str, str]:
    return _headers(
        seed_owner,
        token,
        key,
        **{
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "8",
            "X-Offline-Captured": "true",
            "X-Client-Occurred-At": captured_at.isoformat(),
            "X-Client-Action-Id": key,
        },
    )


async def _persist_paid_order_fixture(
    session,
    *,
    order: Order,
    line: OrderLine,
    payment: Payment,
) -> None:
    """Create a real forward paid snapshot in trigger-safe lifecycle order."""

    assert order.status == "paid"
    assert order.closed_at is not None
    assert order.invoice_issued_at is not None
    assert order.invoice_no
    assert order.fiscal_year
    final_closed_at = order.closed_at
    final_invoice_issued_at = order.invoice_issued_at
    order.status = "open"
    order.closed_at = None
    order.invoice_issued_at = None
    order.invoice_no = None
    order.fiscal_year = None
    session.add(order)
    await session.flush()
    session.add(line)
    await session.flush()

    order.status = "paid"
    order.closed_at = final_closed_at
    order.invoice_issued_at = final_invoice_issued_at
    order.invoice_no = f"D/MN/26-27/{uuid4().int % 100000:05d}"
    order.fiscal_year = "2026-27"
    payment.paid_at = final_invoice_issued_at
    await session.flush()
    session.add(payment)
    await session.commit()


@pytest.mark.asyncio
async def test_open_rate_start_rejects_stale_displayed_rate(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)

    stale = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor - 1,
        },
        headers=_headers(seed_owner, token, f"stale-hourly-start:{uuid4()}"),
    )

    assert stale.status_code == 409, stale.text
    assert "hourly rate changed" in stale.json()["error"]["message"]
    rows = (
        (await session.execute(select(GamingSession).where(GamingSession.station_id == station.id)))
        .scalars()
        .all()
    )
    assert rows == []


@pytest.mark.asyncio
async def test_concurrent_same_workspace_starts_create_exactly_one_active_session(
    client,
    session,
    seed_owner,
) -> None:
    """The Station row serializes rapid duplicate starts on one workspace."""
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)
    payload = {
        "station_id": str(station.id),
        "expected_rate_per_hour_minor": station.rate_per_hour_minor,
    }

    first, second = await asyncio.gather(
        client.post(
            "/api/v1/gaming/sessions/start",
            json={**payload, "shift_id": str(shift.id)},
            headers=_headers(seed_owner, token, f"race-start-a:{uuid4()}"),
        ),
        client.post(
            "/api/v1/gaming/sessions/start",
            json={**payload, "shift_id": str(shift.id)},
            headers=_headers(seed_owner, token, f"race-start-b:{uuid4()}"),
        ),
    )

    assert sorted([first.status_code, second.status_code]) == [201, 409]
    rejected = first if first.status_code == 409 else second
    assert "active session" in rejected.json()["error"]["message"]
    active_unbilled = (
        (
            await session.execute(
                select(GamingSession).where(
                    GamingSession.company_id == seed_owner["company"].id,
                    GamingSession.station_id == station.id,
                    GamingSession.status.in_(("active", "paused")),
                    GamingSession.order_id.is_(None),
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(active_unbilled) == 1
    assert active_unbilled[0].shift_id == shift.id


@pytest.mark.asyncio
async def test_headerless_legacy_ios_online_start_and_stop_remain_naturally_safe(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "User-Agent": "DCompanyERP-iOSNative/1.0",
    }
    payload = {
        "station_id": str(station.id),
        "shift_id": str(shift.id),
        "customer_name": "Legacy online guest",
        "timer_minutes": 60,
    }

    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json=payload,
        headers=headers,
    )
    duplicate_start = await client.post(
        "/api/v1/gaming/sessions/start",
        json=payload,
        headers=headers,
    )

    assert started.status_code == 201, started.text
    assert started.json()["billing_mode"] == "hourly"
    assert started.json()["rate_per_hour_minor"] == station.rate_per_hour_minor
    assert duplicate_start.status_code == 409, duplicate_start.text
    rows = (
        (await session.execute(select(GamingSession).where(GamingSession.station_id == station.id)))
        .scalars()
        .all()
    )
    assert len(rows) == 1

    stop_path = f"/api/v1/gaming/sessions/{started.json()['id']}/stop"
    stopped = await client.post(stop_path, json={}, headers=headers)
    stop_replay = await client.post(stop_path, json={}, headers=headers)

    assert stopped.status_code == 200, stopped.text
    assert stop_replay.status_code == 200, stop_replay.text
    assert stop_replay.json() == stopped.json()
    assert stopped.json()["status"] == "ended"


@pytest.mark.asyncio
async def test_nonlegacy_open_rate_start_still_requires_displayed_rate_snapshot(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={"station_id": str(station.id), "shift_id": str(shift.id)},
        headers=_headers(seed_owner, token, f"missing-rate-snapshot:{uuid4()}"),
    )

    assert response.status_code == 422, response.text
    assert "expected_rate_per_hour_minor" in response.json()["error"]["message"]
    rows = (
        (await session.execute(select(GamingSession).where(GamingSession.station_id == station.id)))
        .scalars()
        .all()
    )
    assert rows == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("snapshot_field", "stale_value"),
    [
        ("expected_package_price_minor", 9_999),
        ("expected_package_duration_minutes", 30),
        ("expected_package_variant", "dual"),
    ],
)
async def test_package_start_rejects_stale_catalog_snapshot(
    snapshot_field,
    stale_value,
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="single",
        kind="base",
        name="Single 60 min",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    session.add_all([station, shift, package])
    await session.commit()
    token = await _login(client, seed_owner)
    payload = {
        "station_id": str(station.id),
        "shift_id": str(shift.id),
        "package_id": str(package.id),
        "expected_package_price_minor": package.price_minor,
        "expected_package_duration_minutes": package.duration_minutes,
        "expected_package_variant": package.variant,
    }
    payload[snapshot_field] = stale_value

    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json=payload,
        headers=_headers(seed_owner, token, f"stale-package-start:{uuid4()}"),
    )

    assert response.status_code == 409, response.text
    assert "Package pricing changed" in response.json()["error"]["message"]
    rows = (
        (await session.execute(select(GamingSession).where(GamingSession.station_id == station.id)))
        .scalars()
        .all()
    )
    assert rows == []


@pytest.mark.asyncio
async def test_package_start_ignores_unrelated_hourly_rate_snapshot(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="single",
        kind="base",
        name="Single 60 min",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    session.add_all([station, shift, package])
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "package_id": str(package.id),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor - 1,
            "expected_package_price_minor": package.price_minor,
            "expected_package_duration_minutes": package.duration_minutes,
            "expected_package_variant": package.variant,
        },
        headers=_headers(seed_owner, token, f"package-hourly-independent:{uuid4()}"),
    )

    assert response.status_code == 201, response.text
    assert response.json()["billing_mode"] == "package"
    assert response.json()["amount_minor"] == package.price_minor


@pytest.mark.asyncio
async def test_offline_start_then_stop_preserves_exact_captured_duration_and_replays(
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    captured_start = now - timedelta(minutes=30)
    captured_stop = captured_start + timedelta(minutes=12, seconds=1)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)

    start_key = f"offline-gaming-start:{uuid4()}"
    start_payload = {
        "station_id": str(station.id),
        "shift_id": str(shift.id),
        "started_at": captured_start.isoformat(),
        "expected_rate_per_hour_minor": station.rate_per_hour_minor,
    }
    start_headers = _headers(
        seed_owner,
        token,
        start_key,
        **{
            "X-Offline-Captured": "true",
            "X-Client-Occurred-At": captured_start.isoformat(),
            "X-Client-Action-Id": start_key,
        },
    )

    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json=start_payload,
        headers=start_headers,
    )
    start_replay = await client.post(
        "/api/v1/gaming/sessions/start",
        json=start_payload,
        headers=start_headers,
    )

    assert started.status_code == 201, started.text
    assert start_replay.status_code == 201, start_replay.text
    assert start_replay.json() == started.json()
    assert datetime.fromisoformat(started.json()["start_at"]) == captured_start

    stop_key = f"offline-gaming-stop:{uuid4()}"
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{started.json()['id']}/stop",
        json={"ended_at": captured_stop.isoformat()},
        headers=_headers(
            seed_owner,
            token,
            stop_key,
            **{
                "X-Offline-Captured": "true",
                "X-Client-Occurred-At": captured_stop.isoformat(),
                "X-Client-Action-Id": stop_key,
            },
        ),
    )

    assert stopped.status_code == 200, stopped.text
    assert datetime.fromisoformat(stopped.json()["end_at"]) == captured_stop
    assert stopped.json()["billable_minutes"] == 13
    assert stopped.json()["amount_minor"] == 2_600


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    ["tamper", "future", "naive", "online", "missing_action", "wrong_action"],
)
async def test_captured_start_rejects_untrusted_or_impossible_time_without_mutation(
    failure,
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    captured_start = now - timedelta(minutes=10)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=now - timedelta(hours=1))
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)

    body_time = captured_start
    header_time = captured_start.isoformat()
    offline = "true"
    key = f"invalid-offline-start:{uuid4()}"
    action_id = key
    if failure == "tamper":
        header_time = (captured_start + timedelta(minutes=1)).isoformat()
    elif failure == "future":
        body_time = now + timedelta(minutes=10)
        header_time = body_time.isoformat()
    elif failure == "naive":
        body_time = captured_start.replace(tzinfo=None)
        header_time = body_time.isoformat()
    elif failure == "online":
        offline = "false"
    elif failure == "missing_action":
        action_id = ""
    elif failure == "wrong_action":
        action_id = f"different:{uuid4()}"

    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "started_at": body_time.isoformat(),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        },
        headers=_headers(
            seed_owner,
            token,
            key,
            **{
                "X-Offline-Captured": offline,
                "X-Client-Occurred-At": header_time,
                "X-Client-Action-Id": action_id,
            },
        ),
    )

    assert response.status_code == 422, response.text
    rows = (
        (await session.execute(select(GamingSession).where(GamingSession.station_id == station.id)))
        .scalars()
        .all()
    )
    assert rows == []


@pytest.mark.asyncio
async def test_captured_start_cannot_predate_owning_shift(
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    captured_start = now - timedelta(minutes=10)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start + timedelta(minutes=1))
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)
    key = f"before-shift-gaming-start:{uuid4()}"

    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "started_at": captured_start.isoformat(),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        },
        headers=_headers(
            seed_owner,
            token,
            key,
            **{
                "X-Offline-Captured": "true",
                "X-Client-Occurred-At": captured_start.isoformat(),
                "X-Client-Action-Id": key,
            },
        ),
    )

    assert response.status_code == 422, response.text
    assert "before the owning shift opened" in response.json()["error"]["message"]
    rows = (
        (await session.execute(select(GamingSession).where(GamingSession.station_id == station.id)))
        .scalars()
        .all()
    )
    assert rows == []


@pytest.mark.asyncio
async def test_web_start_without_captured_time_uses_server_receipt_time(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)
    before = datetime.now(UTC)

    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        },
        headers=_headers(seed_owner, token, f"web-gaming-start:{uuid4()}"),
    )
    after = datetime.now(UTC)

    assert response.status_code == 201, response.text
    assert before <= datetime.fromisoformat(response.json()["start_at"]) <= after


@pytest.mark.asyncio
async def test_offline_stop_uses_exact_tap_time_and_replays_once(
    client,
    session,
    seed_owner,
) -> None:
    started_at = datetime.now(UTC) - timedelta(minutes=30)
    captured_end = started_at + timedelta(minutes=12, seconds=1)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=started_at - timedelta(minutes=1))
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        start_at=started_at,
    )
    session.add_all([station, shift])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)
    key = f"offline-gaming-stop:{uuid4()}"
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/stop"
    payload = {"ended_at": captured_end.isoformat()}
    headers = _headers(
        seed_owner,
        token,
        key,
        **{
            "X-Offline-Captured": "true",
            "X-Client-Occurred-At": captured_end.isoformat(),
            "X-Client-Action-Id": key,
        },
    )

    first = await client.post(path, json=payload, headers=headers)
    replay = await client.post(path, json=payload, headers=headers)

    assert first.status_code == 200, first.text
    assert replay.status_code == 200, replay.text
    assert replay.json() == first.json()
    assert datetime.fromisoformat(first.json()["end_at"]) == captured_end
    assert first.json()["billable_minutes"] == 13
    assert first.json()["amount_minor"] == 2_600
    assert first.json()["shift_id"] == str(shift.id)

    await session.refresh(gaming_session)
    assert gaming_session.end_at == captured_end
    assert gaming_session.billable_minutes == 13
    assert gaming_session.amount_minor == 2_600


@pytest.mark.asyncio
async def test_missing_ended_amount_fails_closed_until_owner_repairs_it(
    client,
    session,
    seed_owner,
) -> None:
    seed_owner["branch"].state_code = "32"
    seed_owner["company"].gstin = "32AAAAA0000A1Z5"
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        status="ended",
        amount_minor=None,
    )
    gaming_session.end_at = datetime.now(UTC)
    gaming_session.billable_minutes = 13
    session.add_all([station, shift])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}"

    send = await client.post(
        f"{path}/send-to-pos",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )
    cancel = await client.post(
        f"{path}/cancel",
        json={"reason": "Duplicate test session"},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )

    assert send.status_code == 409, send.text
    assert cancel.status_code == 409, cancel.text
    assert send.json()["error"]["code"] == "gaming_billing_repair_required"
    assert cancel.json()["error"]["code"] == "gaming_billing_repair_required"
    await session.refresh(gaming_session)
    assert gaming_session.status == "ended"
    assert gaming_session.amount_minor is None
    assert gaming_session.order_id is None

    protected_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="super_owner",
        name="Protected Owner",
        permissions=[],
    )
    session.add(protected_role)
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=seed_owner["owner"].id,
            role_id=protected_role.id,
        )
    )
    await session.commit()
    token = await _login(client, seed_owner)

    key = f"gaming-billing-repair:{uuid4()}"
    repair_payload = {
        "expected_amount_minor": None,
        "amount_minor": 2_600,
        "reason": "Recovered from billable minutes and locked hourly rate",
    }
    repaired = await client.post(
        f"{path}/repair-billing",
        json=repair_payload,
        headers=_headers(seed_owner, token, key),
    )
    replay = await client.post(
        f"{path}/repair-billing",
        json=repair_payload,
        headers=_headers(seed_owner, token, key),
    )

    assert repaired.status_code == 200, repaired.text
    assert replay.status_code == 200, replay.text
    assert replay.json() == repaired.json()
    assert repaired.json()["amount_minor"] == 2_600
    audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.action == "gaming_session_billing_repair",
                AuditLog.entity_id == str(gaming_session.id),
            )
        )
    ).scalar_one()
    assert audit.reason == repair_payload["reason"]
    assert audit.before["amount_minor"] is None
    assert audit.after["amount_minor"] == 2_600

    sent = await client.post(
        f"{path}/send-to-pos",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )
    assert sent.status_code == 201, sent.text
    await session.refresh(gaming_session)
    assert gaming_session.order_id is not None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "failure",
    ["online", "mismatch", "naive", "before", "future", "missing_action", "wrong_action"],
)
async def test_captured_stop_rejects_untrusted_or_impossible_time_without_mutation(
    failure,
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    started_at = now - timedelta(minutes=20)
    captured_end = started_at + timedelta(minutes=10)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=started_at - timedelta(minutes=1))
    gaming_session = _running_session(seed_owner, station=station, shift=shift, start_at=started_at)
    session.add_all([station, shift])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)

    ended_at = captured_end
    header_time = captured_end.isoformat()
    offline = "true"
    key = f"invalid-offline-stop:{uuid4()}"
    action_id = key
    if failure == "online":
        offline = "false"
    elif failure == "mismatch":
        header_time = (captured_end + timedelta(minutes=1)).isoformat()
    elif failure == "naive":
        ended_at = captured_end.replace(tzinfo=None)
        header_time = ended_at.isoformat()
    elif failure == "before":
        ended_at = started_at - timedelta(seconds=1)
        header_time = ended_at.isoformat()
    elif failure == "future":
        ended_at = now + timedelta(minutes=10)
        header_time = ended_at.isoformat()
    elif failure == "missing_action":
        action_id = ""
    elif failure == "wrong_action":
        action_id = f"different:{uuid4()}"

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={"ended_at": ended_at.isoformat()},
        headers=_headers(
            seed_owner,
            token,
            key,
            **{
                "X-Offline-Captured": offline,
                "X-Client-Occurred-At": header_time,
                "X-Client-Action-Id": action_id,
            },
        ),
    )

    assert response.status_code == 422, response.text
    await session.refresh(gaming_session)
    assert gaming_session.status == "active"
    assert gaming_session.end_at is None
    assert gaming_session.amount_minor is None


@pytest.mark.asyncio
async def test_legacy_stop_without_body_still_uses_server_receipt_time(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    gaming_session = _running_session(seed_owner, station=station, shift=shift)
    session.add_all([station, shift])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)
    before = datetime.now(UTC)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        headers=_headers(seed_owner, token, f"legacy-stop:{uuid4()}"),
    )
    after = datetime.now(UTC)

    assert response.status_code == 200, response.text
    ended_at = datetime.fromisoformat(response.json()["end_at"])
    assert before <= ended_at <= after


@pytest.mark.asyncio
async def test_delayed_offline_start_and_stop_replay_preserves_original_financial_time(
    client,
    session,
    seed_owner,
) -> None:
    started_at = datetime.now(UTC) - timedelta(days=30)
    captured_end = started_at + timedelta(minutes=90, seconds=1)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=started_at - timedelta(hours=1))
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)
    start_key = f"delayed-offline-gaming-start:{uuid4()}"

    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "started_at": started_at.isoformat(),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        },
        headers=_headers(
            seed_owner,
            token,
            start_key,
            **{
                "X-Offline-Captured": "true",
                "X-Client-Occurred-At": started_at.isoformat(),
                "X-Client-Action-Id": start_key,
            },
        ),
    )
    assert started.status_code == 201, started.text

    stop_key = f"delayed-offline-gaming-stop:{uuid4()}"
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{started.json()['id']}/stop",
        json={"ended_at": captured_end.isoformat()},
        headers=_headers(
            seed_owner,
            token,
            stop_key,
            **{
                "X-Offline-Captured": "true",
                "X-Client-Occurred-At": captured_end.isoformat(),
                "X-Client-Action-Id": stop_key,
            },
        ),
    )

    assert stopped.status_code == 200, stopped.text
    assert datetime.fromisoformat(started.json()["start_at"]) == started_at
    assert datetime.fromisoformat(stopped.json()["end_at"]) == captured_end
    assert stopped.json()["billable_minutes"] == 91
    assert stopped.json()["amount_minor"] == 18_200


@pytest.mark.asyncio
async def test_delayed_offline_stop_cannot_post_after_source_shift_closed(
    client,
    session,
    seed_owner,
) -> None:
    started_at = datetime.now(UTC) - timedelta(days=30)
    captured_end = started_at + timedelta(minutes=90)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=started_at - timedelta(hours=1))
    shift.status = "closed"
    shift.closed_at = datetime.now(UTC) - timedelta(days=29)
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        start_at=started_at,
    )
    session.add_all([station, shift])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)
    key = f"closed-shift-delayed-stop:{uuid4()}"

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        json={"ended_at": captured_end.isoformat()},
        headers=_headers(
            seed_owner,
            token,
            key,
            **{
                "X-Offline-Captured": "true",
                "X-Client-Occurred-At": captured_end.isoformat(),
                "X-Client-Action-Id": key,
            },
        ),
    )

    assert response.status_code == 422, response.text
    assert "Shift is closed" in response.json()["error"]["message"]
    await session.refresh(gaming_session)
    assert gaming_session.status == "active"
    assert gaming_session.end_at is None


@pytest.mark.asyncio
async def test_package_timer_cannot_be_edited_or_extended_for_free(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type="ps5",
        variant="single",
        kind="base",
        name="Single 60 min",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        timer_minutes=60,
        package_id=package.id,
        amount_minor=10_000,
    )
    session.add_all([station, shift, package])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}"

    patch_response = await client.patch(
        f"{path}/timer",
        json={"timer_minutes": 120},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )
    absolute_response = await client.post(
        f"{path}/extend-timer",
        json={"expected_timer_minutes": 60, "additional_minutes": 60},
        headers=_headers(seed_owner, token, f"free-package-extension:{uuid4()}"),
    )

    assert patch_response.status_code == 422, patch_response.text
    assert absolute_response.status_code == 422, absolute_response.text
    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 60
    assert gaming_session.amount_minor == 10_000


@pytest.mark.asyncio
async def test_deleted_base_package_keeps_locked_mode_amount_and_extension_snapshots(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="dual",
        kind="base",
        name="Dual 60 min",
        duration_minutes=60,
        price_minor=15_000,
        sort_order=0,
        is_active=True,
    )
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        timer_minutes=60,
        package_id=package.id,
        billing_mode="package",
        package_price_minor_snapshot=package.price_minor,
        package_duration_minutes_snapshot=package.duration_minutes,
        package_variant_snapshot=package.variant,
        package_station_type_snapshot=package.station_type,
        amount_minor=15_000,
    )
    session.add_all([station, shift, package])
    await session.flush()
    session.add(gaming_session)
    await session.commit()

    await session.delete(package)
    await session.commit()
    await session.refresh(gaming_session)
    assert gaming_session.package_id is None
    assert gaming_session.billing_mode == "package"

    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}"
    free_timer = await client.patch(
        f"{path}/timer",
        json={"timer_minutes": 120},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )
    stopped = await client.post(
        f"{path}/stop",
        headers=_headers(seed_owner, token, f"stop-deleted-package:{uuid4()}"),
    )

    assert free_timer.status_code == 422, free_timer.text
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["billing_mode"] == "package"
    assert stopped.json()["package_id"] is None
    assert stopped.json()["package_price_minor_snapshot"] == 15_000
    assert stopped.json()["package_duration_minutes_snapshot"] == 60
    assert stopped.json()["package_variant_snapshot"] == "dual"
    assert stopped.json()["package_station_type_snapshot"] == station.type
    assert stopped.json()["amount_minor"] == 15_000
    await session.refresh(gaming_session)
    assert gaming_session.status == "ended"
    assert gaming_session.amount_minor == 15_000


@pytest.mark.asyncio
async def test_partial_package_snapshot_fails_closed_before_stop_mutates_session(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        timer_minutes=60,
        billing_mode="package",
        package_price_minor_snapshot=15_000,
        amount_minor=15_000,
    )
    session.add_all([station, shift])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/stop",
        headers=_headers(seed_owner, token, f"stop-partial-package:{uuid4()}"),
    )

    assert response.status_code == 409, response.text
    assert response.json()["error"]["code"] == "gaming_billing_repair_required"
    await session.refresh(gaming_session)
    assert gaming_session.status == "active"
    assert gaming_session.end_at is None
    assert gaming_session.amount_minor == 15_000


@pytest.mark.asyncio
async def test_relative_timer_extension_is_server_authoritative_idempotent_and_conflict_safe(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        start_at=datetime.now(UTC) - timedelta(minutes=20, seconds=1),
        timer_minutes=None,
    )
    session.add_all([station, shift])
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/extend-timer"
    payload = {"expected_timer_minutes": None, "additional_minutes": 30}
    headers = _headers(seed_owner, token, f"absolute-timer:{uuid4()}")

    first = await client.post(path, json=payload, headers=headers)
    exact_replay = await client.post(path, json=payload, headers=headers)
    stale_replay_with_new_key = await client.post(
        path,
        json=payload,
        headers=_headers(seed_owner, token, f"stale-timer-replay:{uuid4()}"),
    )
    stale = await client.post(
        path,
        json={"expected_timer_minutes": None, "additional_minutes": 30},
        headers=_headers(seed_owner, token, f"stale-timer:{uuid4()}"),
    )
    valid_next = await client.post(
        path,
        json={"expected_timer_minutes": 51, "additional_minutes": 30},
        headers=_headers(seed_owner, token, f"next-timer:{uuid4()}"),
    )
    too_large = await client.post(
        path,
        json={"expected_timer_minutes": 81, "additional_minutes": 1440},
        headers=_headers(seed_owner, token, f"oversized-timer:{uuid4()}"),
    )

    assert first.status_code == 200, first.text
    assert exact_replay.json() == first.json()
    assert first.json()["timer_minutes"] == 51
    assert stale_replay_with_new_key.status_code == 409, stale_replay_with_new_key.text
    assert stale.status_code == 409, stale.text
    assert valid_next.status_code == 200, valid_next.text
    assert valid_next.json()["timer_minutes"] == 81
    assert too_large.status_code == 422, too_large.text
    await session.refresh(gaming_session)
    assert gaming_session.timer_minutes == 81


async def _seed_transfer_world(session, seed_owner, *, package: bool = True):
    source = _station(seed_owner)
    target = _station(seed_owner)
    shift = _shift(seed_owner)
    package_row = None
    if package:
        package_row = GamingPackage(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id,
            station_type="ps5",
            variant="dual",
            kind="base",
            name="Dual 60 min",
            duration_minutes=60,
            price_minor=15_000,
            sort_order=0,
            is_active=True,
        )
    gaming_session = _running_session(
        seed_owner,
        station=source,
        shift=shift,
        timer_minutes=60,
        package_id=package_row.id if package_row else None,
        amount_minor=15_000 if package_row else None,
    )
    rows = [source, target, shift]
    if package_row:
        rows.append(package_row)
    session.add_all(rows)
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    return source, target, shift, package_row, gaming_session


@pytest.mark.asyncio
async def test_transfer_preserves_price_package_shift_and_is_replay_safe(
    client,
    session,
    seed_owner,
) -> None:
    source, target, shift, package, gaming_session = await _seed_transfer_world(session, seed_owner)
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/transfer"
    payload = {
        "expected_source_station_id": str(source.id),
        "target_station_id": str(target.id),
    }
    headers = _headers(seed_owner, token, f"transfer:{uuid4()}")

    first = await client.post(path, json=payload, headers=headers)
    exact_replay = await client.post(path, json=payload, headers=headers)
    same_target_replay = await client.post(
        path,
        json=payload,
        headers=_headers(seed_owner, token, f"same-target-transfer:{uuid4()}"),
    )

    assert first.status_code == 200, first.text
    assert exact_replay.json() == first.json()
    assert same_target_replay.status_code == 409, same_target_replay.text
    assert first.json()["station_id"] == str(target.id)
    assert first.json()["shift_id"] == str(shift.id)
    assert first.json()["package_id"] == str(package.id)
    assert first.json()["rate_per_hour_minor"] == source.rate_per_hour_minor
    assert first.json()["amount_minor"] == 15_000


@pytest.mark.asyncio
@pytest.mark.parametrize("invalid_target", ["occupied", "wrong_type", "wrong_branch"])
async def test_transfer_rejects_invalid_target_without_moving_session(
    invalid_target,
    client,
    session,
    seed_owner,
) -> None:
    source, target, shift, _, gaming_session = await _seed_transfer_world(
        session, seed_owner, package=False
    )
    if invalid_target == "occupied":
        blocker = _running_session(seed_owner, station=target, shift=shift)
        session.add(blocker)
    elif invalid_target == "wrong_type":
        target.type = "vr"
    else:
        other_branch = Branch(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            name=f"Other {uuid4().hex[:8]}",
            invoice_series_code="O2",
        )
        session.add(other_branch)
        await session.flush()
        target.branch_id = other_branch.id
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.post(
        f"/api/v1/gaming/sessions/{gaming_session.id}/transfer",
        json={
            "expected_source_station_id": str(source.id),
            "target_station_id": str(target.id),
        },
        headers=_headers(seed_owner, token, f"invalid-transfer:{uuid4()}"),
    )

    expected_status = 409 if invalid_target == "occupied" else 422
    assert response.status_code == expected_status, response.text
    await session.refresh(gaming_session)
    assert gaming_session.station_id == source.id


@pytest.mark.asyncio
async def test_concurrent_transfers_to_one_target_allow_exactly_one_session(
    client,
    session,
    seed_owner,
) -> None:
    source_a = _station(seed_owner)
    source_b = _station(seed_owner)
    target = _station(seed_owner)
    shift = _shift(seed_owner)
    session_a = _running_session(seed_owner, station=source_a, shift=shift)
    session_b = _running_session(seed_owner, station=source_b, shift=shift)
    session.add_all([source_a, source_b, target, shift])
    await session.flush()
    session.add_all([session_a, session_b])
    await session.commit()
    token = await _login(client, seed_owner)
    first, second = await asyncio.gather(
        client.post(
            f"/api/v1/gaming/sessions/{session_a.id}/transfer",
            json={
                "expected_source_station_id": str(source_a.id),
                "target_station_id": str(target.id),
            },
            headers=_headers(seed_owner, token, f"race-transfer-a:{uuid4()}"),
        ),
        client.post(
            f"/api/v1/gaming/sessions/{session_b.id}/transfer",
            json={
                "expected_source_station_id": str(source_b.id),
                "target_station_id": str(target.id),
            },
            headers=_headers(seed_owner, token, f"race-transfer-b:{uuid4()}"),
        ),
    )

    assert sorted([first.status_code, second.status_code]) == [200, 409]
    moved = (
        (
            await session.execute(
                select(GamingSession).where(
                    GamingSession.id.in_([session_a.id, session_b.id]),
                    GamingSession.station_id == target.id,
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(moved) == 1


@pytest.mark.asyncio
async def test_transfer_rejects_stale_source_after_another_device_moved_session(
    client,
    session,
    seed_owner,
) -> None:
    source, first_target, _, _, gaming_session = await _seed_transfer_world(
        session, seed_owner, package=False
    )
    stale_target = _station(seed_owner)
    session.add(stale_target)
    await session.commit()
    token = await _login(client, seed_owner)
    path = f"/api/v1/gaming/sessions/{gaming_session.id}/transfer"

    first_move = await client.post(
        path,
        json={
            "expected_source_station_id": str(source.id),
            "target_station_id": str(first_target.id),
        },
        headers=_headers(seed_owner, token, f"first-transfer:{uuid4()}"),
    )
    stale_move = await client.post(
        path,
        json={
            "expected_source_station_id": str(source.id),
            "target_station_id": str(stale_target.id),
        },
        headers=_headers(seed_owner, token, f"stale-transfer:{uuid4()}"),
    )

    assert first_move.status_code == 200, first_move.text
    assert stale_move.status_code == 409, stale_move.text
    assert "transferred on another device" in stale_move.json()["error"]["message"]
    await session.refresh(gaming_session)
    assert gaming_session.station_id == first_target.id


@pytest.mark.asyncio
async def test_unbilled_filter_excludes_cancelled_and_already_ordered_history(
    client,
    session,
    seed_owner,
) -> None:
    station_rows = [_station(seed_owner) for _ in range(5)]
    shift = _shift(seed_owner)
    now = datetime.now(UTC)
    active = _running_session(seed_owner, station=station_rows[0], shift=shift)
    paused = _running_session(seed_owner, station=station_rows[1], shift=shift, status="paused")
    ended = _running_session(seed_owner, station=station_rows[2], shift=shift, status="ended")
    ended.end_at = now
    ended.billable_minutes = 20
    ended.amount_minor = 4_000
    cancelled = _running_session(
        seed_owner, station=station_rows[3], shift=shift, status="cancelled"
    )
    cancelled.end_at = now
    cancelled.billable_minutes = 0
    cancelled.amount_minor = 0
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="held",
        opened_at=now,
        closed_at=now,
        subtotal_minor=4_000,
        total_minor=4_000,
    )
    ordered = _running_session(seed_owner, station=station_rows[4], shift=shift, status="ended")
    ordered.end_at = now
    ordered.billable_minutes = 20
    ordered.amount_minor = 4_000
    ordered.order_id = order.id
    session.add_all(station_rows + [shift])
    await session.flush()
    session.add(order)
    await session.flush()
    session.add_all([active, paused, ended, cancelled, ordered])
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.get(
        "/api/v1/gaming/sessions?unbilled_only=true&limit=100",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )

    assert response.status_code == 200, response.text
    returned_ids = {row["id"] for row in response.json()}
    assert returned_ids == {str(active.id), str(paused.id), str(ended.id)}


@pytest.mark.asyncio
async def test_code21_unbilled_pull_includes_cancelled_resolution_after_live_work(
    client,
    session,
    seed_owner,
) -> None:
    """The shipped Code 21 can observe a web cancellation without hiding work."""
    started_at = datetime.now(UTC) - timedelta(minutes=30)
    station = _station(seed_owner)
    active_station = _station(seed_owner)
    ended_station = _station(seed_owner)
    recently_cancelled_station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=started_at - timedelta(minutes=1))
    cancelled_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        start_at=started_at,
        status="cancelled",
        amount_minor=0,
    )
    cancelled_at = started_at + timedelta(minutes=1)
    cancelled_session.end_at = cancelled_at
    cancelled_session.cancelled_at = cancelled_at
    cancelled_session.cancelled_by = seed_owner["owner"].id
    cancelled_session.cancel_reason = "Owner cancelled mistaken test session from web"
    recently_cancelled_session = _running_session(
        seed_owner,
        station=recently_cancelled_station,
        shift=shift,
        start_at=started_at - timedelta(days=2),
        status="cancelled",
        amount_minor=0,
    )
    recently_cancelled_at = datetime.now(UTC)
    recently_cancelled_session.end_at = recently_cancelled_at
    recently_cancelled_session.cancelled_at = recently_cancelled_at
    recently_cancelled_session.cancelled_by = seed_owner["owner"].id
    recently_cancelled_session.cancel_reason = "Recently cancelled older session"
    active_session = _running_session(
        seed_owner,
        station=active_station,
        shift=shift,
        start_at=started_at - timedelta(minutes=2),
    )
    ended_session = _running_session(
        seed_owner,
        station=ended_station,
        shift=shift,
        start_at=started_at - timedelta(minutes=3),
        status="ended",
        amount_minor=2_000,
    )
    ended_session.end_at = ended_session.start_at + timedelta(minutes=10)
    ended_session.billable_minutes = 10
    session.add_all(
        [station, active_station, ended_station, recently_cancelled_station, shift]
    )
    await session.flush()
    session.add_all(
        [cancelled_session, recently_cancelled_session, active_session, ended_session]
    )
    await session.commit()
    token = await _login(client, seed_owner)
    read_headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }

    web = await client.get(
        "/api/v1/gaming/sessions?unbilled_only=true&limit=500",
        headers=read_headers,
    )
    code22 = await client.get(
        "/api/v1/gaming/sessions?unbilled_only=true&limit=500",
        headers={
            **read_headers,
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "22",
        },
    )
    code21 = await client.get(
        "/api/v1/gaming/sessions?unbilled_only=true&limit=500",
        headers={
            **read_headers,
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "21",
        },
    )
    code21_limited = await client.get(
        "/api/v1/gaming/sessions?unbilled_only=true&limit=2",
        headers={
            **read_headers,
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "21",
        },
    )
    code21_recent_cancel = await client.get(
        "/api/v1/gaming/sessions?status=cancelled&unbilled_only=true&limit=1",
        headers={
            **read_headers,
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "21",
        },
    )

    assert web.status_code == 200, web.text
    assert code22.status_code == 200, code22.text
    assert code21.status_code == 200, code21.text
    assert code21_limited.status_code == 200, code21_limited.text
    assert code21_recent_cancel.status_code == 200, code21_recent_cancel.text
    assert str(cancelled_session.id) not in {row["id"] for row in web.json()}
    assert str(cancelled_session.id) not in {row["id"] for row in code22.json()}
    assert str(cancelled_session.id) in {row["id"] for row in code21.json()}
    assert {row["id"] for row in code21_limited.json()} == {
        str(active_session.id),
        str(ended_session.id),
    }
    assert [row["id"] for row in code21_recent_cancel.json()] == [
        str(recently_cancelled_session.id)
    ]

    cancelled_stop = await client.post(
        f"/api/v1/gaming/sessions/{cancelled_session.id}/stop",
        headers=_headers(seed_owner, token, f"cancelled-stop:{uuid4()}"),
    )
    assert cancelled_stop.status_code == 422, cancelled_stop.text
    assert cancelled_stop.json()["error"]["code"] == "business_rule"
    assert cancelled_stop.json()["error"]["message"] == "session was cancelled"

    await session.refresh(cancelled_session)
    assert cancelled_session.status == "cancelled"
    assert cancelled_session.end_at == cancelled_at
    assert cancelled_session.amount_minor == 0
    assert cancelled_session.order_id is None


@pytest.mark.asyncio
async def test_exact_session_read_returns_sent_history_without_bounded_list_lookup(
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="held",
        subtotal_minor=6_000,
        total_minor=6_000,
        opened_at=now - timedelta(minutes=5),
    )
    gaming_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        start_at=now - timedelta(minutes=35),
        status="ended",
    )
    gaming_session.end_at = now - timedelta(minutes=5)
    gaming_session.billable_minutes = 30
    gaming_session.amount_minor = 6_000
    gaming_session.order_id = order.id
    session.add_all([station, shift])
    await session.flush()
    session.add(order)
    await session.flush()
    session.add(gaming_session)
    await session.commit()
    token = await _login(client, seed_owner)

    response = await client.get(
        f"/api/v1/gaming/sessions/{gaming_session.id}",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )

    assert response.status_code == 200, response.text
    assert response.json()["id"] == str(gaming_session.id)
    assert response.json()["order_id"] == str(order.id)
    assert response.json()["status"] == "ended"


async def _accepted_legacy_hourly_start(
    client,
    session,
    seed_owner,
    *,
    captured_start: datetime,
) -> tuple[Station, Shift, UUID, dict, GamingSession]:
    """Create the v27 shape: provenance header retained, body time omitted."""
    install_audit_listeners()
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    session.add_all([station, shift])
    await session.commit()
    token = await _login(client, seed_owner)
    local_action_id = uuid4()
    start_key = f"gaming-session-start:{local_action_id}"
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        },
        headers=_offline_action_headers(
            seed_owner,
            token,
            start_key,
            captured_start,
        ),
    )
    assert started.status_code == 201, started.text
    server_session = await session.get(GamingSession, UUID(started.json()["id"]))
    assert server_session is not None
    assert server_session.billing_mode == "hourly"
    assert server_session.start_at > captured_start
    return station, shift, local_action_id, started.json(), server_session


@pytest.mark.asyncio
@pytest.mark.parametrize("mismatch_source", ["request", "current_session"])
async def test_hourly_accepted_start_recovery_requires_exact_original_and_current_rate(
    mismatch_source,
    client,
    session,
    seed_owner,
) -> None:
    captured_start = datetime.now(UTC) - timedelta(minutes=20)
    station, shift, local_action_id, _, server_session = (
        await _accepted_legacy_hourly_start(
            client,
            session,
            seed_owner,
            captured_start=captured_start,
        )
    )
    if mismatch_source == "current_session":
        server_session.rate_per_hour_minor += 100
        await session.commit()

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    response = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": None,
            "package_id": None,
            "expected_rate_per_hour_minor": (
                station.rate_per_hour_minor - 100
                if mismatch_source == "request"
                else station.rate_per_hour_minor
            ),
            "resolution": "server_session_recovered",
            "reference_order_id": None,
            "reason": "Owner is checking the retained hourly price snapshot",
        },
        headers=_headers(seed_owner, token, key),
    )

    assert response.status_code == 409, response.text
    assert "does not exactly match" in response.text or "rate" in response.text
    assert await session.get(IdempotencyKey, key) is None
    receipts = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action == "gaming_legacy_outbox_resolution",
                    AuditLog.entity_id == str(local_action_id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert receipts == []


@pytest.mark.asyncio
async def test_hourly_recovery_returns_active_session_for_exact_stop_replay(
    client,
    session,
    seed_owner,
) -> None:
    captured_start = datetime.now(UTC) - timedelta(minutes=20)
    station, shift, local_action_id, _, server_session = (
        await _accepted_legacy_hourly_start(
            client,
            session,
            seed_owner,
            captured_start=captured_start,
        )
    )
    captured_stop = datetime.now(UTC)
    assert captured_stop >= server_session.start_at
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    resolution_key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    recovered = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": captured_stop.isoformat(),
            "package_id": None,
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
            "resolution": "server_session_recovered",
            "reference_order_id": None,
            "reason": "Owner recovered the accepted hourly Start for exact Stop replay",
        },
        headers=_headers(seed_owner, token, resolution_key),
    )

    assert recovered.status_code == 201, recovered.text
    assert recovered.json()["server_session"]["status"] == "active"
    assert recovered.json()["server_session"]["order_id"] is None
    receipt = await session.get(AuditLog, recovered.json()["receipt_id"])
    assert receipt is not None
    assert receipt.after["captured_stop_outcome"] == "pending_against_active_session"

    stop_key = f"gaming-session-stop:{local_action_id}"
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{server_session.id}/stop",
        json={"ended_at": captured_stop.isoformat()},
        headers=_offline_action_headers(
            seed_owner,
            token,
            stop_key,
            captured_stop,
        ),
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["status"] == "ended"
    assert datetime.fromisoformat(stopped.json()["end_at"]) == captured_stop
    assert stopped.json()["billable_minutes"] >= 0


@pytest.mark.asyncio
async def test_server_recovery_probe_without_any_server_evidence_is_editable(
    client,
    session,
    seed_owner,
) -> None:
    captured_start = datetime.now(UTC) - timedelta(minutes=10)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    session.add_all([station, shift])
    await session.commit()
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    local_action_id = uuid4()
    key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    payload = {
        "local_action_id": str(local_action_id),
        "station_id": str(station.id),
        "shift_id": str(shift.id),
        "captured_started_at": captured_start.isoformat(),
        "captured_stopped_at": None,
        "package_id": None,
        "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        "resolution": "server_session_recovered",
        "reference_order_id": None,
        "reason": "Owner is probing for an accepted server Start",
    }

    probe = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, token, key),
    )

    assert probe.status_code == 422, probe.text
    assert (
        probe.json()["error"]["code"]
        == "gaming_legacy_server_session_not_found"
    )
    assert probe.json()["error"]["details"] == {
        "local_action_id": str(local_action_id),
        "safe_to_choose_another_resolution": True,
    }
    assert await session.get(IdempotencyKey, key) is None

    no_play = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            **payload,
            "resolution": "confirmed_no_play",
            "reason": "Owner verified that the customer never started playing",
        },
        headers=_headers(seed_owner, token, key),
    )
    assert no_play.status_code == 201, no_play.text
    assert no_play.json()["resolution"] == "confirmed_no_play"
    assert no_play.json()["server_session"] is None


@pytest.mark.asyncio
async def test_server_recovery_probe_with_plausible_unproven_history_stays_ambiguous(
    client,
    session,
    seed_owner,
) -> None:
    captured_start = datetime.now(UTC) - timedelta(minutes=30)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    plausible = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        start_at=datetime.now(UTC) - timedelta(minutes=5),
    )
    session.add_all([station, shift])
    await session.flush()
    session.add(plausible)
    await session.commit()
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    local_action_id = uuid4()
    key = f"gaming-legacy-outbox-resolution:{local_action_id}"

    response = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": None,
            "package_id": None,
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
            "resolution": "server_session_recovered",
            "reference_order_id": None,
            "reason": "Owner is probing ambiguous server session history",
        },
        headers=_headers(seed_owner, token, key),
    )

    assert response.status_code == 409, response.text
    assert "no exact original action receipt" in response.text
    assert await session.get(IdempotencyKey, key) is None


@pytest.mark.asyncio
async def test_untouched_accepted_hourly_start_can_be_cancelled_as_verified_no_play(
    client,
    session,
    seed_owner,
) -> None:
    captured_start = datetime.now(UTC) - timedelta(minutes=20)
    captured_stop = captured_start + timedelta(minutes=5)
    station, shift, local_action_id, _, server_session = (
        await _accepted_legacy_hourly_start(
            client,
            session,
            seed_owner,
            captured_start=captured_start,
        )
    )
    assert captured_stop < server_session.start_at
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    payload = {
        "local_action_id": str(local_action_id),
        "station_id": str(station.id),
        "shift_id": str(shift.id),
        "captured_started_at": captured_start.isoformat(),
        "captured_stopped_at": captured_stop.isoformat(),
        "package_id": None,
        "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        "resolution": "confirmed_no_play",
        "reference_order_id": None,
        "reason": "Owner verified both taps were a mistaken no-play attempt",
    }

    unsafe_probe = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            **payload,
            "resolution": "server_session_recovered",
            "reason": "Owner first probed the accepted Start for exact Stop replay",
        },
        headers=_headers(seed_owner, token, key),
    )
    assert unsafe_probe.status_code == 422, unsafe_probe.text
    assert (
        unsafe_probe.json()["error"]["code"]
        == "gaming_legacy_stop_owner_review_required"
    )
    assert (
        unsafe_probe.json()["error"]["details"]["reason_code"]
        == "captured_stop_precedes_authoritative_start"
    )
    assert await session.get(IdempotencyKey, key) is None

    first = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, token, key),
    )
    replay = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, token, key),
    )

    assert first.status_code == 201, first.text
    assert replay.status_code == 201, replay.text
    assert replay.json() == first.json()
    assert first.json()["resolution"] == "server_session_recovered"
    assert first.json()["server_session"]["status"] == "cancelled"
    assert first.json()["server_session"]["amount_minor"] == 0
    assert first.json()["server_session"]["order_id"] is None
    await session.refresh(server_session)
    assert server_session.status == "cancelled"
    assert server_session.billable_minutes == 0
    assert server_session.amount_minor == 0
    cancellation = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.action
                == "gaming_legacy_server_session_no_play_cancel",
                AuditLog.entity_id == str(server_session.id),
            )
        )
    ).scalar_one()
    assert cancellation.after["captured_started_at"] == captured_start.isoformat()
    assert cancellation.after["captured_stopped_at"] == captured_stop.isoformat()
    assert cancellation.actor_user_id == seed_owner["owner"].id


@pytest.mark.asyncio
@pytest.mark.parametrize("unsafe_evidence", ["transfer", "order", "extension"])
async def test_accepted_start_no_play_refuses_any_server_side_use_evidence(
    unsafe_evidence,
    client,
    session,
    seed_owner,
) -> None:
    captured_start = datetime.now(UTC) - timedelta(minutes=20)
    station, shift, local_action_id, _, server_session = (
        await _accepted_legacy_hourly_start(
            client,
            session,
            seed_owner,
            captured_start=captured_start,
        )
    )
    if unsafe_evidence == "transfer":
        target = _station(seed_owner)
        session.add(target)
        await session.flush()
        server_session.station_id = target.id
    elif unsafe_evidence == "order":
        order = Order(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id,
            terminal_id=seed_owner["terminal"].id,
            shift_id=shift.id,
            opened_by=seed_owner["owner"].id,
            type="session",
            status="held",
            subtotal_minor=0,
            total_minor=0,
            opened_at=datetime.now(UTC),
        )
        session.add(order)
        await session.flush()
        server_session.order_id = order.id
    else:
        session.add(
            GamingSessionExtension(
                id=uuid4(),
                company_id=seed_owner["company"].id,
                gaming_session_id=server_session.id,
                package_id=None,
                package_name="Contradictory retained extension",
                package_variant="single",
                station_type=station.type,
                duration_minutes=1,
                package_price_minor=100,
                controller_surcharge_minor=0,
                total_minor=100,
                timer_before_minutes=0,
                timer_after_minutes=1,
                amount_before_minor=0,
                amount_after_minor=100,
                idempotency_key=f"unsafe-no-play:{uuid4()}",
                created_by=seed_owner["owner"].id,
            )
        )
    await session.commit()

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    response = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": None,
            "package_id": None,
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
            "resolution": "confirmed_no_play",
            "reference_order_id": None,
            "reason": "Owner is testing whether server-use evidence blocks no-play",
        },
        headers=_headers(seed_owner, token, key),
    )

    assert response.status_code == 409, response.text
    assert "cannot be cancelled as no-play" in response.text
    await session.refresh(server_session)
    assert server_session.status == "active"
    assert await session.get(IdempotencyKey, key) is None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("server_outcome", "requested_resolution"),
    [
        ("active", "server_session_recovered"),
        ("active_no_play", "confirmed_no_play"),
        ("ended_unbilled", "server_session_recovered"),
        ("sent", "confirmed_no_play"),
        ("cancelled", "server_session_recovered"),
        ("transferred", "server_session_recovered"),
    ],
)
async def test_legacy_outbox_recovers_authoritative_v27_package_start_for_every_status(
    server_outcome,
    requested_resolution,
    client,
    session,
    seed_owner,
) -> None:
    install_audit_listeners()
    captured_start = datetime.now(UTC) - timedelta(minutes=20)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="single",
        kind="base",
        name="Legacy single 60 min",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    transferred_station = (
        _station(seed_owner) if server_outcome == "transferred" else None
    )
    session.add_all(
        [station, shift, package]
        + ([transferred_station] if transferred_station is not None else [])
    )
    await session.commit()
    token = await _login(client, seed_owner)
    local_action_id = uuid4()
    original_start_key = f"gaming-session-start:{local_action_id}"

    # v27 sent durable-action provenance but did not put started_at in the
    # body. The server session timestamp therefore differs from the local tap;
    # recovery must use the original receipt/audit identity, not proximity.
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "package_id": str(package.id),
            "expected_package_price_minor": package.price_minor,
            "expected_package_duration_minutes": package.duration_minutes,
            "expected_package_variant": package.variant,
        },
        headers=_offline_action_headers(
            seed_owner,
            token,
            original_start_key,
            captured_start,
        ),
    )
    assert started.status_code == 201, started.text
    assert datetime.fromisoformat(started.json()["start_at"]) > captured_start
    start_audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.client_action_id == original_start_key,
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(start_audits) == 1, [
        (row.action, row.entity_type, row.entity_id, row.client_action_id)
        for row in start_audits
    ]
    server_session = await session.get(GamingSession, UUID(started.json()["id"]))
    assert server_session is not None

    captured_stop: datetime | None = None
    if server_outcome == "active":
        # A v27 stop_pending row can still need its exact Stop replay after the
        # accepted Start is reattached.
        captured_stop = captured_start + timedelta(minutes=10)
    elif server_outcome in {"ended_unbilled", "sent"}:
        captured_stop = captured_start + timedelta(minutes=10)
        server_session.status = "ended"
        server_session.end_at = datetime.now(UTC)
        server_session.billable_minutes = 1
        if server_outcome == "sent":
            order = Order(
                id=uuid4(),
                company_id=seed_owner["company"].id,
                branch_id=seed_owner["branch"].id,
                terminal_id=seed_owner["terminal"].id,
                shift_id=shift.id,
                opened_by=seed_owner["owner"].id,
                type="session",
                status="held",
                subtotal_minor=package.price_minor,
                total_minor=package.price_minor,
                opened_at=server_session.end_at,
            )
            session.add(order)
            await session.flush()
            server_session.order_id = order.id
    elif server_outcome == "cancelled":
        captured_stop = captured_start + timedelta(minutes=10)
        authoritative_cancelled_at = datetime.now(UTC)
        server_session.status = "cancelled"
        server_session.end_at = authoritative_cancelled_at
        server_session.billable_minutes = 0
        server_session.amount_minor = 0
        server_session.cancelled_at = authoritative_cancelled_at
        server_session.cancelled_by = seed_owner["owner"].id
        server_session.cancel_reason = "Owner cancelled the mistaken session"
    elif server_outcome == "transferred":
        assert transferred_station is not None
        server_session.station_id = transferred_station.id
    await session.commit()

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    resolution_payload = {
        "local_action_id": str(local_action_id),
        "station_id": str(station.id),
        "shift_id": str(shift.id),
        "captured_started_at": captured_start.isoformat(),
        "captured_stopped_at": (
            captured_stop.isoformat() if captured_stop is not None else None
        ),
        "package_id": str(package.id),
        "resolution": requested_resolution,
        "reference_order_id": (
            str(uuid4()) if requested_resolution == "manual_bill_recorded" else None
        ),
        "reason": "Owner is recovering the exact accepted v27 server start",
    }
    resolution_key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    first = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=resolution_payload,
        headers=_headers(seed_owner, token, resolution_key),
    )
    replay = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=resolution_payload,
        headers=_headers(seed_owner, token, resolution_key),
    )

    assert first.status_code == 201, first.text
    assert replay.status_code == 201, replay.text
    assert replay.json() == first.json()
    body = first.json()
    assert body["resolution"] == "server_session_recovered"
    assert body["reference_order_id"] is None
    assert body["package_id"] == str(package.id)
    assert body["server_session"]["id"] == started.json()["id"]
    assert body["server_session"]["shift_id"] == str(shift.id)
    assert body["server_session"]["station_id"] == (
        str(transferred_station.id)
        if transferred_station is not None
        else str(station.id)
    )
    await session.refresh(server_session)
    assert body["server_session"]["status"] == server_session.status
    assert body["server_session"]["order_id"] == (
        str(server_session.order_id) if server_session.order_id is not None else None
    )
    assert body["server_session"]["start_at"] == started.json()["start_at"]
    if server_outcome == "active":
        assert body["server_session"]["end_at"] is None
        resolution_idempotency = await session.get(IdempotencyKey, resolution_key)
        assert resolution_idempotency is not None
        await session.delete(resolution_idempotency)
        await session.commit()

        durable_replay = await client.post(
            "/api/v1/gaming/legacy-outbox-resolutions",
            json=resolution_payload,
            headers=_headers(seed_owner, token, resolution_key),
        )
        changed_decision = await client.post(
            "/api/v1/gaming/legacy-outbox-resolutions",
            json={
                **resolution_payload,
                "resolution": "confirmed_no_play",
            },
            headers=_headers(seed_owner, token, resolution_key),
        )

        assert durable_replay.status_code == 201, durable_replay.text
        assert durable_replay.json() == body
        assert changed_decision.status_code == 409, changed_decision.text
        assert "already has a different protected-owner" in changed_decision.text
        assert await session.get(IdempotencyKey, resolution_key) is None
    elif server_outcome == "active_no_play":
        assert body["server_session"]["status"] == "cancelled"
        assert body["server_session"]["amount_minor"] == 0
        assert body["server_session"]["order_id"] is None

    receipt = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.id == body["receipt_id"],
                AuditLog.company_id == seed_owner["company"].id,
            )
        )
    ).scalar_one()
    assert receipt.after["resolution"] == "server_session_recovered"
    assert receipt.after["requested_resolution"] == requested_resolution
    assert receipt.after["server_session_id"] == started.json()["id"]
    assert receipt.after["original_start_idempotency_key"] == original_start_key
    assert receipt.after["recovery_proof"] == "idempotency_response"
    assert receipt.after["captured_stop_outcome"] == (
        "pending_against_active_session"
        if server_outcome == "active"
        else (
            "not_captured"
            if server_outcome in {"transferred", "active_no_play"}
            else "superseded_by_authoritative_terminal_state"
        )
    )
    assert receipt.actor_user_id == seed_owner["owner"].id
    assert receipt.terminal_id == seed_owner["terminal"].id
    assert receipt.after["resolution_request_hash"]
    assert receipt.after["resolution_request"]["station_id"] == str(station.id)
    assert receipt.after["resolution_receipt"]["server_session"]["id"] == started.json()[
        "id"
    ]
    resolution_receipts = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action == "gaming_legacy_outbox_resolution",
                    AuditLog.entity_id == str(local_action_id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(resolution_receipts) == 1


@pytest.mark.asyncio
async def test_legacy_outbox_recovers_from_exact_start_audit_after_idempotency_expiry(
    client,
    session,
    seed_owner,
) -> None:
    install_audit_listeners()
    captured_start = datetime.now(UTC) - timedelta(minutes=25)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="single",
        kind="base",
        name="Audit recovered package",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    session.add_all([station, shift, package])
    await session.commit()
    token = await _login(client, seed_owner)
    local_action_id = uuid4()
    original_start_key = f"gaming-session-start:{local_action_id}"
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "package_id": str(package.id),
            "expected_package_price_minor": package.price_minor,
            "expected_package_duration_minutes": package.duration_minutes,
            "expected_package_variant": package.variant,
        },
        headers=_offline_action_headers(
            seed_owner,
            token,
            original_start_key,
            captured_start,
        ),
    )
    assert started.status_code == 201, started.text
    receipt_row = await session.get(IdempotencyKey, original_start_key)
    assert receipt_row is not None
    await session.delete(receipt_row)
    await session.commit()

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    response = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": None,
            "package_id": str(package.id),
            "resolution": "server_session_recovered",
            "reference_order_id": None,
            "reason": "Owner recovered the exact immutable start audit",
        },
        headers=_headers(
            seed_owner,
            token,
            f"gaming-legacy-outbox-resolution:{local_action_id}",
        ),
    )

    assert response.status_code == 201, response.text
    assert response.json()["resolution"] == "server_session_recovered"
    assert response.json()["server_session"]["id"] == started.json()["id"]
    audit = await session.get(AuditLog, response.json()["receipt_id"])
    assert audit is not None
    assert audit.after["recovery_proof"] == "audit_action"


@pytest.mark.asyncio
async def test_staff_start_is_recovered_only_by_protected_owner_with_actor_provenance(
    client,
    session,
    seed_owner,
) -> None:
    install_audit_listeners()
    captured_start = datetime.now(UTC) - timedelta(minutes=15)
    station = _station(seed_owner)
    staff_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="gaming_supervisor",
        name="Gaming staff",
        permissions=[],
    )
    staff = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"gaming-staff-{uuid4().hex[:8]}@test.local",
        name="Gaming Staff",
        password_hash=hash_password("staff-password-1234"),
        status="active",
    )
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="single",
        kind="base",
        name="Staff recovered package",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    session.add_all([station, shift, package, staff_role, staff])
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=staff.id, role_id=staff_role.id))
    await session.commit()
    staff_login = await client.post(
        "/api/v1/auth/login",
        json={"email": staff.email, "password": "staff-password-1234"},
    )
    assert staff_login.status_code == 200, staff_login.text
    staff_token = staff_login.json()["access_token"]
    local_action_id = uuid4()
    original_start_key = f"gaming-session-start:{local_action_id}"
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "package_id": str(package.id),
            "expected_package_price_minor": package.price_minor,
            "expected_package_duration_minutes": package.duration_minutes,
            "expected_package_variant": package.variant,
        },
        headers=_offline_action_headers(
            seed_owner,
            staff_token,
            original_start_key,
            captured_start,
        ),
    )
    assert started.status_code == 201, started.text
    original_receipt = await session.get(IdempotencyKey, original_start_key)
    assert original_receipt is not None
    assert original_receipt.user_id == staff.id
    server_session = await session.get(GamingSession, UUID(started.json()["id"]))
    assert server_session is not None
    assert server_session.opened_by == staff.id
    start_audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.entity_type == "GamingSession",
                AuditLog.entity_id == str(server_session.id),
                AuditLog.client_action_id == original_start_key,
            )
        )
    ).scalar_one()
    assert start_audit.actor_user_id == staff.id
    assert start_audit.after["opened_by"] == str(staff.id)

    await _grant_protected_owner(session, seed_owner)
    owner_token = await _login(client, seed_owner)
    recovered = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": None,
            "package_id": str(package.id),
            "resolution": "server_session_recovered",
            "reference_order_id": None,
            "reason": "Protected owner recovered the staff member's accepted start",
        },
        headers=_headers(
            seed_owner,
            owner_token,
            f"gaming-legacy-outbox-resolution:{local_action_id}",
        ),
    )

    assert recovered.status_code == 201, recovered.text
    assert recovered.json()["server_session"]["id"] == str(server_session.id)
    owner_receipt = await session.get(AuditLog, recovered.json()["receipt_id"])
    assert owner_receipt is not None
    assert owner_receipt.actor_user_id == seed_owner["owner"].id
    assert owner_receipt.after["recovery_proof"] == "idempotency_response"


@pytest.mark.asyncio
async def test_accepted_start_recovery_blocks_wrong_shift_and_partial_receipt_collision(
    client,
    session,
    seed_owner,
) -> None:
    install_audit_listeners()
    captured_start = datetime.now(UTC) - timedelta(minutes=10)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    wrong_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Wrong recovery branch",
        invoice_series_code="WR",
    )
    wrong_shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    wrong_shift.branch_id = wrong_branch.id
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="single",
        kind="base",
        name="Collision recovery package",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    session.add_all([station, shift, wrong_branch, package])
    await session.flush()
    session.add(wrong_shift)
    await session.commit()
    token = await _login(client, seed_owner)
    local_action_id = uuid4()
    original_start_key = f"gaming-session-start:{local_action_id}"
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "package_id": str(package.id),
            "expected_package_price_minor": package.price_minor,
            "expected_package_duration_minutes": package.duration_minutes,
            "expected_package_variant": package.variant,
        },
        headers=_offline_action_headers(
            seed_owner,
            token,
            original_start_key,
            captured_start,
        ),
    )
    assert started.status_code == 201, started.text
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    base_payload = {
        "local_action_id": str(local_action_id),
        "station_id": str(station.id),
        "captured_started_at": captured_start.isoformat(),
        "captured_stopped_at": None,
        "package_id": str(package.id),
        "resolution": "server_session_recovered",
        "reference_order_id": None,
        "reason": "Owner is validating exact start scope and receipt identity",
    }
    resolution_key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    wrong_scope = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={**base_payload, "shift_id": str(wrong_shift.id)},
        headers=_headers(seed_owner, token, resolution_key),
    )
    assert wrong_scope.status_code == 422, wrong_scope.text
    assert "selected branch, terminal" in wrong_scope.json()["error"]["message"]

    original_receipt = await session.get(IdempotencyKey, original_start_key)
    assert original_receipt is not None
    original_receipt.response_body = {
        **(original_receipt.response_body or {}),
        "id": str(uuid4()),
    }
    await session.commit()
    collision = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={**base_payload, "shift_id": str(shift.id)},
        headers=_headers(seed_owner, token, resolution_key),
    )
    assert collision.status_code == 409, collision.text
    assert "server session is missing" in collision.json()["error"]["message"]

    other_actor = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"wrong-start-actor-{uuid4().hex[:8]}@test.local",
        name="Wrong Start Actor",
        password_hash=hash_password("wrong-actor-password"),
        status="active",
    )
    session.add(other_actor)
    await session.flush()
    await session.refresh(original_receipt)
    original_receipt.response_body = started.json()
    original_receipt.user_id = other_actor.id
    await session.commit()
    actor_collision = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={**base_payload, "shift_id": str(shift.id)},
        headers=_headers(seed_owner, token, resolution_key),
    )
    assert actor_collision.status_code == 409, actor_collision.text
    assert "does not match" in actor_collision.json()["error"]["message"]
    receipts = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action == "gaming_legacy_outbox_resolution",
                    AuditLog.entity_id == str(local_action_id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert receipts == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("server_status", "manual_order_amount_minor"),
    [("active", 10_000), ("ended", 9_500)],
)
async def test_accepted_start_links_exact_manual_paid_order_without_second_charge(
    server_status,
    manual_order_amount_minor,
    client,
    session,
    seed_owner,
) -> None:
    install_audit_listeners()
    captured_start = datetime.now(UTC) - timedelta(minutes=30)
    station = _station(seed_owner)
    shift = _shift(seed_owner, opened_at=captured_start - timedelta(hours=1))
    package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant="single",
        kind="base",
        name="Manually billed recovery package",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    extension_package = GamingPackage(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        station_type=station.type,
        variant=package.variant,
        kind="extension",
        name="Paid extension after recovery",
        duration_minutes=30,
        price_minor=5_000,
        sort_order=1,
        is_active=True,
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Recovery bill {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=f"RECOVERY-{uuid4().hex[:8]}",
        name="Paid gaming recovery bill",
        type="gaming",
        base_price_minor=manual_order_amount_minor,
        tax_rate=0,
        price_includes_tax=True,
        is_available=False,
    )
    paid_order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="paid",
        subtotal_minor=manual_order_amount_minor,
        total_minor=manual_order_amount_minor,
        opened_at=datetime.now(UTC) - timedelta(minutes=5),
        closed_at=datetime.now(UTC) - timedelta(minutes=4),
        invoice_no=f"D/R/26/{uuid4().hex[:8]}",
        fiscal_year="2026-27",
        invoice_issued_at=datetime.now(UTC) - timedelta(minutes=4),
    )
    paid_line = OrderLine(
        id=uuid4(),
        order_id=paid_order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=manual_order_amount_minor,
        line_total_minor=manual_order_amount_minor,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=manual_order_amount_minor,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="served",
        kitchen_served_at=paid_order.closed_at,
    )
    payment = Payment(
        id=uuid4(),
        order_id=paid_order.id,
        shift_id=shift.id,
        method="cash",
        amount_minor=manual_order_amount_minor,
        tendered_minor=manual_order_amount_minor,
        change_minor=0,
        paid_at=paid_order.invoice_issued_at,
    )
    session.add_all([station, shift, package, extension_package, category])
    await session.flush()
    session.add(item)
    await session.flush()
    await _persist_paid_order_fixture(
        session,
        order=paid_order,
        line=paid_line,
        payment=payment,
    )
    token = await _login(client, seed_owner)
    local_action_id = uuid4()
    start_key = f"gaming-session-start:{local_action_id}"
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "package_id": str(package.id),
            "expected_package_price_minor": package.price_minor,
            "expected_package_duration_minutes": package.duration_minutes,
            "expected_package_variant": package.variant,
        },
        headers=_offline_action_headers(
            seed_owner,
            token,
            start_key,
            captured_start,
        ),
    )
    assert started.status_code == 201, started.text
    server_session = await session.get(GamingSession, UUID(started.json()["id"]))
    assert server_session is not None
    captured_stop = None
    if server_status == "ended":
        captured_stop = datetime.now(UTC)
        server_session.status = "ended"
        server_session.end_at = captured_stop
        server_session.billable_minutes = 1
        await session.commit()

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    response = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": (
                captured_stop.isoformat() if captured_stop is not None else None
            ),
            "package_id": str(package.id),
            "resolution": "manual_bill_recorded",
            "reference_order_id": str(paid_order.id),
            "reason": "Owner found a paid bill but has not linked it to the server session",
        },
        headers=_headers(
            seed_owner,
            token,
            f"gaming-legacy-outbox-resolution:{local_action_id}",
        ),
    )

    assert response.status_code == 201, response.text
    assert response.json()["resolution"] == "server_session_recovered"
    assert response.json()["reference_order_id"] == str(paid_order.id)
    assert response.json()["server_session"]["order_id"] == str(paid_order.id)
    await session.refresh(server_session)
    assert server_session.order_id == paid_order.id
    resolution_audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action == "gaming_legacy_outbox_resolution",
                    AuditLog.entity_id == str(local_action_id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(resolution_audits) == 1
    assert resolution_audits[0].after["reference_order_id"] == str(paid_order.id)
    link_audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action
                    == "gaming_legacy_server_session_manual_bill_link",
                    AuditLog.entity_id == str(server_session.id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(link_audits) == 1
    assert link_audits[0].after["order_id"] == str(paid_order.id)
    assert link_audits[0].after["expected_session_amount_minor"] == package.price_minor
    assert (
        link_audits[0].after["reference_order_total_minor"]
        == manual_order_amount_minor
    )
    assert (
        link_audits[0].after["order_variance_minor"]
        == manual_order_amount_minor - package.price_minor
    )

    if server_status == "active":
        blocked_extension = await client.post(
            f"/api/v1/gaming/sessions/{server_session.id}/extend",
            json={
                "package_id": str(extension_package.id),
                "expected_timer_minutes": package.duration_minutes,
                "expected_amount_minor": package.price_minor,
                "expected_package_price_minor": extension_package.price_minor,
                "expected_package_duration_minutes": extension_package.duration_minutes,
                "expected_package_variant": extension_package.variant,
            },
            headers=_headers(
                seed_owner,
                token,
                f"linked-session-extension:{uuid4()}",
            ),
        )
        assert blocked_extension.status_code == 409, blocked_extension.text
        assert (
            blocked_extension.json()["error"]["code"]
            == "gaming_extension_not_applied"
        )
        assert (
            blocked_extension.json()["error"]["details"]["reason_code"]
            == "session_already_linked_to_pos"
        )


@pytest.mark.asyncio
async def test_accepted_hourly_start_links_paid_order_and_preserves_captured_variance(
    client,
    session,
    seed_owner,
) -> None:
    captured_start = datetime.now(UTC) - timedelta(minutes=30)
    captured_stop = captured_start + timedelta(minutes=15)
    expected_captured_amount_minor = 3_000
    paid_amount_minor = 2_800
    station, shift, local_action_id, _, server_session = (
        await _accepted_legacy_hourly_start(
            client,
            session,
            seed_owner,
            captured_start=captured_start,
        )
    )
    assert captured_stop < server_session.start_at
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Hourly recovery {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=f"HOURLY-RECOVERY-{uuid4().hex[:8]}",
        name="Manually billed hourly gaming",
        type="gaming",
        base_price_minor=paid_amount_minor,
        tax_rate=0,
        price_includes_tax=True,
        is_available=False,
    )
    paid_order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="paid",
        subtotal_minor=paid_amount_minor,
        total_minor=paid_amount_minor,
        opened_at=datetime.now(UTC) - timedelta(minutes=5),
        closed_at=datetime.now(UTC) - timedelta(minutes=4),
        invoice_no=f"D/H/26/{uuid4().hex[:8]}",
        fiscal_year="2026-27",
        invoice_issued_at=datetime.now(UTC) - timedelta(minutes=4),
    )
    session.add(category)
    await session.flush()
    session.add(item)
    await session.flush()
    line = OrderLine(
        id=uuid4(),
        order_id=paid_order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=paid_amount_minor,
        line_total_minor=paid_amount_minor,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=paid_amount_minor,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="served",
        kitchen_served_at=paid_order.closed_at,
    )
    payment = Payment(
        id=uuid4(),
        order_id=paid_order.id,
        shift_id=shift.id,
        method="cash",
        amount_minor=paid_amount_minor,
        tendered_minor=paid_amount_minor,
        change_minor=0,
        paid_at=datetime.now(UTC) - timedelta(minutes=4),
    )
    await _persist_paid_order_fixture(
        session,
        order=paid_order,
        line=line,
        payment=payment,
    )

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    response = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "captured_started_at": captured_start.isoformat(),
            "captured_stopped_at": captured_stop.isoformat(),
            "package_id": None,
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
            "resolution": "manual_bill_recorded",
            "reference_order_id": str(paid_order.id),
            "reason": "Owner verified the exact paid hourly invoice and its variance",
        },
        headers=_headers(
            seed_owner,
            token,
            f"gaming-legacy-outbox-resolution:{local_action_id}",
        ),
    )

    assert response.status_code == 201, response.text
    body = response.json()
    assert body["resolution"] == "server_session_recovered"
    assert body["reference_order_id"] == str(paid_order.id)
    assert body["server_session"]["billing_mode"] == "hourly"
    assert body["server_session"]["rate_per_hour_minor"] == station.rate_per_hour_minor
    assert body["server_session"]["order_id"] == str(paid_order.id)
    await session.refresh(server_session)
    assert server_session.order_id == paid_order.id

    link_audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.action == "gaming_legacy_server_session_manual_bill_link",
                AuditLog.entity_id == str(server_session.id),
            )
        )
    ).scalar_one()
    assert link_audit.after["expected_captured_billable_minutes"] == 15
    assert (
        link_audit.after["expected_session_amount_minor"]
        == expected_captured_amount_minor
    )
    assert link_audit.after["expected_rate_per_hour_minor"] == 12_000
    assert link_audit.after["order_variance_minor"] == -200
    assert link_audit.after["service_line_variance_minor"] == -200

    duplicate_send = await client.post(
        f"/api/v1/gaming/sessions/{server_session.id}/send-to-pos",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )
    assert duplicate_send.status_code == 201, duplicate_send.text
    assert duplicate_send.json() == {
        "order_id": str(paid_order.id),
        "amount_minor": paid_amount_minor,
    }
    linked_orders = (
        (
            await session.execute(
                select(Order).where(
                    Order.company_id == seed_owner["company"].id,
                    Order.id == paid_order.id,
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(linked_orders) == 1


@pytest.mark.asyncio
async def test_legacy_outbox_manual_bill_resolution_is_owner_paid_invoice_bound_and_idempotent(
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Legacy gaming bill {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=f"LEGACY-GAME-{uuid4().hex[:8]}",
        name="Manually billed PS5 session",
        type="gaming",
        base_price_minor=15_000,
        tax_rate=0,
        price_includes_tax=True,
        is_available=False,
    )
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="paid",
        subtotal_minor=15_000,
        total_minor=15_000,
        opened_at=now - timedelta(minutes=10),
        closed_at=now - timedelta(minutes=5),
        invoice_no=f"D/L/26/{uuid4().hex[:8]}",
        fiscal_year="2026-27",
        invoice_issued_at=now - timedelta(minutes=5),
    )
    payment = Payment(
        id=uuid4(),
        order_id=order.id,
        shift_id=shift.id,
        method="cash",
        amount_minor=15_000,
        tendered_minor=15_000,
        change_minor=0,
        paid_at=now - timedelta(minutes=5),
    )
    line = OrderLine(
        id=uuid4(),
        order_id=order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=15_000,
        line_total_minor=15_000,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=15_000,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="queued",
    )
    session.add_all([station, shift, category])
    await session.flush()
    session.add(item)
    await session.flush()
    await _persist_paid_order_fixture(
        session,
        order=order,
        line=line,
        payment=payment,
    )

    local_action_id = uuid4()
    payload = {
        "local_action_id": str(local_action_id),
        "station_id": str(station.id),
        "captured_started_at": (now - timedelta(minutes=35)).isoformat(),
        "captured_stopped_at": (now - timedelta(minutes=5)).isoformat(),
        "package_id": None,
        "expected_rate_per_hour_minor": station.rate_per_hour_minor,
        "resolution": "manual_bill_recorded",
        "reference_order_id": str(order.id),
        "reason": "Owner verified the matching manually paid POS invoice",
    }
    key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    token = await _login(client, seed_owner)

    denied = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, token, key),
    )
    assert denied.status_code == 403, denied.text

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    first = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, token, key),
    )
    replay = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, token, key),
    )

    assert first.status_code == 201, first.text
    assert replay.status_code == 201, replay.text
    assert replay.json() == first.json()
    resolution_idempotency = await session.get(IdempotencyKey, key)
    assert resolution_idempotency is not None
    await session.delete(resolution_idempotency)
    await session.commit()

    replay_after_key_expiry = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, token, key),
    )
    second_protected_owner = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"second-protected-{uuid4().hex[:8]}@test.local",
        name="Second Protected Owner",
        password_hash=hash_password("second-owner-password"),
        status="active",
    )
    protected_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == seed_owner["company"].id,
                Role.code == "super_owner",
            )
        )
    ).scalar_one()
    session.add(second_protected_owner)
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=second_protected_owner.id,
            role_id=protected_role.id,
        )
    )
    await session.commit()
    second_login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": second_protected_owner.email,
            "password": "second-owner-password",
        },
    )
    assert second_login.status_code == 200, second_login.text
    different_owner_replay = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=payload,
        headers=_headers(seed_owner, second_login.json()["access_token"], key),
    )
    changed_reference = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={**payload, "reference_order_id": str(uuid4())},
        headers=_headers(seed_owner, token, key),
    )
    changed_scope = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={**payload, "station_id": str(uuid4())},
        headers=_headers(seed_owner, token, key),
    )

    assert replay_after_key_expiry.status_code == 201, replay_after_key_expiry.text
    assert replay_after_key_expiry.json() == first.json()
    assert different_owner_replay.status_code == 409, different_owner_replay.text
    assert "different protected owner" in different_owner_replay.text
    assert changed_reference.status_code == 409, changed_reference.text
    assert changed_scope.status_code == 409, changed_scope.text
    assert "already has a different protected-owner" in changed_reference.text
    assert "already has a different protected-owner" in changed_scope.text
    assert await session.get(IdempotencyKey, key) is None
    assert isinstance(first.json()["receipt_id"], int)
    assert first.json()["local_action_id"] == str(local_action_id)
    assert first.json()["branch_id"] == str(seed_owner["branch"].id)
    assert first.json()["terminal_id"] == str(seed_owner["terminal"].id)
    audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action == "gaming_legacy_outbox_resolution",
                    AuditLog.entity_id == str(local_action_id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(audits) == 1
    assert audits[0].id == first.json()["receipt_id"]
    assert audits[0].reason == payload["reason"]
    assert audits[0].after["reference_order_id"] == str(order.id)
    assert audits[0].after["reference_order_invoice_no"] == order.invoice_no
    assert audits[0].after["reference_payment_total_minor"] == 15_000
    assert audits[0].after["reference_refunded_total_minor"] == 0
    assert audits[0].after["reference_net_paid_minor"] == 15_000
    assert audits[0].after["reference_service_line_id"] == str(line.id)
    assert audits[0].after["reference_service_type"] == "gaming"
    server_sessions = (
        (await session.execute(select(GamingSession).where(GamingSession.station_id == station.id)))
        .scalars()
        .all()
    )
    assert server_sessions == []

    second_local_action_id = uuid4()
    duplicate_bill_payload = {
        **payload,
        "local_action_id": str(second_local_action_id),
    }
    duplicate_bill = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json=duplicate_bill_payload,
        headers=_headers(
            seed_owner,
            token,
            f"gaming-legacy-outbox-resolution:{second_local_action_id}",
        ),
    )
    assert duplicate_bill.status_code == 409, duplicate_bill.text


@pytest.mark.asyncio
async def test_legacy_outbox_manual_bill_requires_final_invoice_and_complete_payment(
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    held_order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="held",
        subtotal_minor=10_000,
        total_minor=10_000,
        opened_at=now - timedelta(minutes=20),
    )
    unpaid_paid_order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="paid",
        subtotal_minor=10_000,
        total_minor=10_000,
        opened_at=now - timedelta(minutes=20),
        closed_at=now - timedelta(minutes=2),
        invoice_no=f"D/L/26/{uuid4().hex[:8]}",
        fiscal_year="2026-27",
        invoice_issued_at=now - timedelta(minutes=2),
    )
    session.add_all([station, shift])
    await session.flush()
    # 0048 rejects a paid invoice without matching Payment evidence at commit.
    # Recreate one corrupt legacy row only to prove the runtime recovery route
    # also fails closed if privileged/manual SQL somehow bypassed migration and
    # forward-write guards. Restore the production trigger before the request.
    await session.execute(
        text(
            "ALTER TABLE orders DISABLE TRIGGER "
            "trg_orders_final_payment_balance"
        )
    )
    try:
        session.add_all([held_order, unpaid_paid_order])
        await session.flush()
    finally:
        await session.execute(
            text(
                "ALTER TABLE orders ENABLE TRIGGER "
                "trg_orders_final_payment_balance"
            )
        )
    await session.commit()
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)

    async def resolve(reference_order_id: UUID):
        local_action_id = uuid4()
        return await client.post(
            "/api/v1/gaming/legacy-outbox-resolutions",
            json={
                "local_action_id": str(local_action_id),
                "station_id": str(station.id),
                "captured_started_at": (now - timedelta(minutes=30)).isoformat(),
                "captured_stopped_at": (now - timedelta(minutes=5)).isoformat(),
                "expected_rate_per_hour_minor": station.rate_per_hour_minor,
                "resolution": "manual_bill_recorded",
                "reference_order_id": str(reference_order_id),
                "reason": "Owner is validating the manual billing evidence",
            },
            headers=_headers(
                seed_owner,
                token,
                f"gaming-legacy-outbox-resolution:{local_action_id}",
            ),
        )

    held = await resolve(held_order.id)
    no_payment = await resolve(unpaid_paid_order.id)

    assert held.status_code == 422, held.text
    assert "finalized and paid" in held.json()["error"]["message"]
    assert no_payment.status_code == 422, no_payment.text
    assert "complete payment evidence" in no_payment.json()["error"]["message"]


@pytest.mark.asyncio
async def test_legacy_outbox_manual_bill_rejects_partially_refunded_paid_order(
    client,
    session,
    seed_owner,
) -> None:
    """A paid status is not proof of the remaining net receipt after refund."""
    now = datetime.now(UTC).replace(microsecond=0)
    gross_minor = 15_000
    refunded_minor = 5_000
    station = _station(seed_owner)
    shift = _shift(seed_owner)
    shift.expected_minor = gross_minor
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Refunded legacy bill {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=f"REFUNDED-GAME-{uuid4().hex[:8]}",
        name="Partially refunded PS5 session",
        type="gaming",
        base_price_minor=gross_minor,
        tax_rate=0,
        price_includes_tax=True,
        is_available=False,
    )
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="paid",
        subtotal_minor=gross_minor,
        total_minor=gross_minor,
        opened_at=now - timedelta(minutes=20),
        closed_at=now - timedelta(minutes=10),
        invoice_no=f"D/L/26/{uuid4().hex[:8]}",
        fiscal_year="2026-27",
        invoice_issued_at=now - timedelta(minutes=10),
    )
    line = OrderLine(
        id=uuid4(),
        order_id=order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=gross_minor,
        line_total_minor=gross_minor,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=gross_minor,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="served",
        kitchen_served_at=order.closed_at,
    )
    payment = Payment(
        id=uuid4(),
        order_id=order.id,
        shift_id=shift.id,
        method="cash",
        amount_minor=gross_minor,
        tendered_minor=gross_minor,
        change_minor=0,
        paid_at=now - timedelta(minutes=10),
    )
    session.add_all([station, shift, category])
    await session.flush()
    session.add(item)
    await session.flush()
    await _persist_paid_order_fixture(
        session,
        order=order,
        line=line,
        payment=payment,
    )

    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    refund_request_key = f"pos-refund-request:{uuid4()}"
    refund_headers = _headers(seed_owner, token, refund_request_key)
    refund_headers["X-Client-Action-Id"] = refund_request_key
    accepted = await client.post(
        "/api/v1/pos/refund-requests",
        json={
            "order_id": str(order.id),
            "shift_id": str(shift.id),
            "reason_code": "CUSTOMER_REQUEST",
            "amount_minor": refunded_minor,
            "expected_paid_minor": gross_minor,
            "expected_refundable_minor": gross_minor,
            "mode": "cash",
            "client_action_id": refund_request_key,
            "note": "Partial refund proves net paid is below the invoice total",
        },
        headers=refund_headers,
    )
    assert accepted.status_code == 201, accepted.text
    refund_request_id = accepted.json()["id"]

    begin_key = f"pos-refund-cash-begin:{uuid4()}"
    begun = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/begin-cash-handoff",
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": refunded_minor,
            "ready_to_handover": True,
        },
        headers=_headers(seed_owner, token, begin_key),
    )
    assert begun.status_code == 201, begun.text

    settle_key = f"pos-refund-cash-settle:{uuid4()}"
    settled = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/settle-cash",
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": refunded_minor,
            "cash_handed_over": True,
            "settled_at": begun.json()["handoff_started_at"],
        },
        headers=_headers(seed_owner, token, settle_key),
    )
    assert settled.status_code == 201, settled.text

    finalize_key = f"pos-refund-cash-finalize:{uuid4()}"
    finalized = await client.post(
        f"/api/v1/pos/refund-requests/{refund_request_id}/finalize-cash",
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": refunded_minor,
        },
        headers=_headers(seed_owner, token, finalize_key),
    )
    assert finalized.status_code == 201, finalized.text
    refund = (
        await session.execute(select(Refund).where(Refund.order_id == order.id))
    ).scalar_one()
    assert refund.amount_minor == refunded_minor
    await session.refresh(order)
    assert order.status == "paid"

    local_action_id = uuid4()
    resolution_key = f"gaming-legacy-outbox-resolution:{local_action_id}"
    rejected = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "captured_started_at": (now - timedelta(minutes=30)).isoformat(),
            "captured_stopped_at": (now - timedelta(minutes=10)).isoformat(),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
            "resolution": "manual_bill_recorded",
            "reference_order_id": str(order.id),
            "reason": "Owner checked the partially refunded manual gaming bill",
        },
        headers=_headers(seed_owner, token, resolution_key),
    )

    assert rejected.status_code == 422, rejected.text
    assert "settled refund" in rejected.json()["error"]["message"]
    assert await session.get(IdempotencyKey, resolution_key) is None
    resolution_audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action == "gaming_legacy_outbox_resolution",
                    AuditLog.entity_id == str(local_action_id),
                )
            )
        )
        .scalars()
        .all()
    )
    assert resolution_audits == []
    linked_sessions = (
        (
            await session.execute(
                select(GamingSession).where(GamingSession.order_id == order.id)
            )
        )
        .scalars()
        .all()
    )
    assert linked_sessions == []


@pytest.mark.asyncio
@pytest.mark.parametrize("invalid_evidence", ["unrelated_food", "voided_gaming"])
async def test_legacy_outbox_manual_bill_rejects_incompatible_or_voided_line(
    invalid_evidence,
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    station = _station(seed_owner, station_type="ps5")
    shift = _shift(seed_owner)
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Unrelated food {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=f"FOOD-{uuid4().hex[:10]}",
        name="Invalid manual-bill evidence",
        type="gaming" if invalid_evidence == "voided_gaming" else "food",
        base_price_minor=5_000,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="takeaway",
        status="paid",
        subtotal_minor=5_000,
        total_minor=5_000,
        opened_at=now - timedelta(minutes=10),
        closed_at=now - timedelta(minutes=5),
        invoice_no=f"D/F/26/{uuid4().hex[:8]}",
        fiscal_year="2026-27",
        invoice_issued_at=now - timedelta(minutes=5),
    )
    line = OrderLine(
        id=uuid4(),
        order_id=order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=5_000,
        line_total_minor=5_000,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=5_000,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="served",
        kitchen_served_at=order.closed_at,
        voided_at=(now - timedelta(minutes=6)) if invalid_evidence == "voided_gaming" else None,
        voided_by=(seed_owner["owner"].id if invalid_evidence == "voided_gaming" else None),
        void_reason=("Removed before payment" if invalid_evidence == "voided_gaming" else None),
    )
    payment = Payment(
        id=uuid4(),
        order_id=order.id,
        shift_id=shift.id,
        method="cash",
        amount_minor=5_000,
        tendered_minor=5_000,
        change_minor=0,
        paid_at=now - timedelta(minutes=5),
    )
    session.add_all([station, shift, category])
    await session.flush()
    session.add(item)
    await session.flush()
    await _persist_paid_order_fixture(
        session,
        order=order,
        line=line,
        payment=payment,
    )
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)
    local_action_id = uuid4()

    response = await client.post(
        "/api/v1/gaming/legacy-outbox-resolutions",
        json={
            "local_action_id": str(local_action_id),
            "station_id": str(station.id),
            "captured_started_at": (now - timedelta(minutes=30)).isoformat(),
            "captured_stopped_at": (now - timedelta(minutes=5)).isoformat(),
            "expected_rate_per_hour_minor": station.rate_per_hour_minor,
            "resolution": "manual_bill_recorded",
            "reference_order_id": str(order.id),
            "reason": "Owner is validating the manual billing evidence",
        },
        headers=_headers(
            seed_owner,
            token,
            f"gaming-legacy-outbox-resolution:{local_action_id}",
        ),
    )

    assert response.status_code == 422, response.text
    assert "no non-voided paid service line compatible" in response.json()["error"]["message"]


@pytest.mark.asyncio
async def test_legacy_outbox_no_play_validates_time_and_refuses_existing_server_session(
    client,
    session,
    seed_owner,
) -> None:
    now = datetime.now(UTC)
    station = _station(seed_owner)
    clean_station = _station(seed_owner)
    shift = _shift(seed_owner)
    captured_start = now - timedelta(minutes=15)
    server_session = _running_session(
        seed_owner,
        station=station,
        shift=shift,
        start_at=captured_start,
    )
    session.add_all([station, clean_station, shift])
    await session.flush()
    session.add(server_session)
    await session.commit()
    await _grant_protected_owner(session, seed_owner)
    token = await _login(client, seed_owner)

    async def resolve(
        *,
        station_id: UUID,
        started_at: str,
        stopped_at: str | None,
    ):
        local_action_id = uuid4()
        return await client.post(
            "/api/v1/gaming/legacy-outbox-resolutions",
            json={
                "local_action_id": str(local_action_id),
                "station_id": str(station_id),
                "captured_started_at": started_at,
                "captured_stopped_at": stopped_at,
                "expected_rate_per_hour_minor": (
                    station.rate_per_hour_minor
                    if station_id == station.id
                    else clean_station.rate_per_hour_minor
                ),
                "resolution": "confirmed_no_play",
                "reference_order_id": None,
                "reason": "Owner confirmed that no customer play occurred",
            },
            headers=_headers(
                seed_owner,
                token,
                f"gaming-legacy-outbox-resolution:{local_action_id}",
            ),
        )

    naive = await resolve(
        station_id=clean_station.id,
        started_at=(now - timedelta(minutes=15)).replace(tzinfo=None).isoformat(),
        stopped_at=None,
    )
    future = await resolve(
        station_id=clean_station.id,
        started_at=(now + timedelta(minutes=6)).isoformat(),
        stopped_at=None,
    )
    reversed_times = await resolve(
        station_id=clean_station.id,
        started_at=(now - timedelta(minutes=5)).isoformat(),
        stopped_at=(now - timedelta(minutes=10)).isoformat(),
    )
    existing = await resolve(
        station_id=station.id,
        started_at=captured_start.isoformat(),
        stopped_at=None,
    )
    valid = await resolve(
        station_id=clean_station.id,
        started_at=(now - timedelta(minutes=15)).isoformat(),
        stopped_at=None,
    )

    assert naive.status_code == 422, naive.text
    assert future.status_code == 422, future.text
    assert reversed_times.status_code == 422, reversed_times.text
    assert existing.status_code == 409, existing.text
    assert valid.status_code == 201, valid.text
    assert valid.json()["resolution"] == "confirmed_no_play"
    assert valid.json()["reference_order_id"] is None
