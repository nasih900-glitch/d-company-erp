"""Fresh-tenant proof for the Code 22 Gaming Centre money workflow.

These tests intentionally cross the HTTP boundary.  They prove the tariff the
operator selects is the amount snapshotted onto the session, retained at Stop,
handed to POS, optionally discounted, settled, and finally reflected in the
shift drawer.  Every test tenant is unique and is created only in the configured
test PostgreSQL database.
"""

from __future__ import annotations

import asyncio
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text, update

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password
from app.models import (
    AuditLog,
    Branch,
    GamingPackage,
    GamingSession,
    GamingSessionExtension,
    Order,
    Payment,
    Role,
    Shift,
    Station,
    User,
    UserRole,
)
from app.services.gaming.tariff_catalog import upsert_d_company_gaming_tariff


@pytest_asyncio.fixture(autouse=True)
async def require_isolated_postgres(session) -> None:
    database_name = (await session.execute(text("select current_database()"))).scalar_one()
    if database_name != "dcompany_code22_audit_20260903":
        pytest.skip(
            "Code 22 Gaming/POS E2E requires the isolated dcompany_code22_audit_20260903 database"
        )


async def _login(client, *, email: str, password: str) -> str:
    response = await client.post(
        "/api/v1/auth/login",
        json={"email": email, "password": password},
    )
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


def _headers(seed_owner, token: str, *, key: str | None = None) -> dict[str, str]:
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    if key is not None:
        headers["Idempotency-Key"] = key
    return headers


async def _install_tariff(session, seed_owner) -> dict[str, GamingPackage]:
    result = await upsert_d_company_gaming_tariff(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
    )
    assert len(result.created_codes) == 17
    assert result.updated_codes == ()
    await session.commit()
    rows = (
        (
            await session.execute(
                select(GamingPackage).where(
                    GamingPackage.company_id == seed_owner["company"].id,
                    GamingPackage.branch_id == seed_owner["branch"].id,
                    GamingPackage.is_active.is_(True),
                    GamingPackage.deleted_at.is_(None),
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == 17
    audit_rows = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.entity_type == "GamingPackage",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(audit_rows) == 17
    assert all(row.action == "create" for row in audit_rows)
    assert all(row.actor_user_id is None for row in audit_rows)
    assert all(row.user_agent == "script/ensure_gaming_tariff-v1" for row in audit_rows)
    assert all(
        row.after and row.after["branch_id"] == str(seed_owner["branch"].id)
        for row in audit_rows
    )
    return {row.code: row for row in rows}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_tariff_repair_is_audited_once_and_noop_restart_writes_nothing(
    session,
    seed_owner,
) -> None:
    packages = await _install_tariff(session, seed_owner)
    package = packages["standard-single-session-60m"]
    await session.execute(
        update(GamingPackage)
        .where(GamingPackage.id == package.id)
        .values(price_minor=99_999)
    )
    await session.commit()

    repaired = await upsert_d_company_gaming_tariff(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
    )
    assert repaired.updated_codes == ("standard-single-session-60m",)
    await session.commit()

    audit_rows = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.entity_type == "GamingPackage",
                    AuditLog.entity_id == str(package.id),
                    AuditLog.action == "update",
                    AuditLog.user_agent == "script/ensure_gaming_tariff-v1",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(audit_rows) == 1
    assert audit_rows[0].before == {"price_minor": 99_999}
    assert audit_rows[0].after == {"price_minor": 12_000}

    no_op = await upsert_d_company_gaming_tariff(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
    )
    assert no_op.changed_count == 0
    await session.commit()
    second_count = len(
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.entity_type == "GamingPackage",
                    AuditLog.entity_id == str(package.id),
                    AuditLog.action == "update",
                    AuditLog.user_agent == "script/ensure_gaming_tariff-v1",
                )
            )
        )
        .scalars()
        .all()
    )
    assert second_count == 1


@pytest.mark.integration
@pytest.mark.asyncio
async def test_parallel_tariff_applies_serialize_and_write_one_truthful_audit(
    session,
    seed_owner,
) -> None:
    """Two deploy invocations cannot both claim the same package was created."""

    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id

    async def apply_in_own_transaction():
        async with AsyncSessionLocal() as worker:
            result = await upsert_d_company_gaming_tariff(
                worker,
                company_id=company_id,
                branch_id=branch_id,
            )
            await worker.commit()
            return result

    first, second = await asyncio.gather(
        apply_in_own_transaction(),
        apply_in_own_transaction(),
    )
    assert sorted((len(first.created_codes), len(second.created_codes))) == [0, 17]
    assert sorted((len(first.unchanged_codes), len(second.unchanged_codes))) == [0, 17]

    packages = (
        (
            await session.execute(
                select(GamingPackage).where(
                    GamingPackage.company_id == company_id,
                    GamingPackage.branch_id == branch_id,
                    GamingPackage.deleted_at.is_(None),
                )
            )
        )
        .scalars()
        .all()
    )
    audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == company_id,
                    AuditLog.entity_type == "GamingPackage",
                    AuditLog.action == "create",
                    AuditLog.user_agent == "script/ensure_gaming_tariff-v1",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(packages) == 17
    assert len(audits) == 17
    assert {row.entity_id for row in audits} == {str(row.id) for row in packages}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_tariff_apply_rejects_a_branch_from_another_tenant(
    session,
    seed_owner,
) -> None:
    with pytest.raises(RuntimeError, match="was not found for company"):
        await upsert_d_company_gaming_tariff(
            session,
            company_id=uuid4(),
            branch_id=seed_owner["branch"].id,
        )

    audits = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.entity_type == "GamingPackage",
            )
        )
    ).scalars().all()
    assert audits == []


async def _open_shift(client, seed_owner, token: str, *, opening_float: int = 0) -> UUID:
    response = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": opening_float},
        headers=_headers(seed_owner, token),
    )
    assert response.status_code == 201, response.text
    assert response.json()["status"] == "open"
    return UUID(response.json()["id"])


def _station(seed_owner, station_type: str, sequence: int) -> Station:
    return Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        code=f"E{sequence:02}{station_type[:3].upper()}{uuid4().hex[:5]}",
        name=f"E2E {station_type} {sequence}",
        type=station_type,
        rate_per_hour_minor=15_000 if station_type == "ps5" else 18_000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )


def _start_payload(
    *,
    station_id: UUID,
    shift_id: UUID,
    package: GamingPackage,
    player_count: int,
) -> dict[str, object]:
    return {
        "station_id": str(station_id),
        "shift_id": str(shift_id),
        "package_id": str(package.id),
        "expected_package_price_minor": int(package.price_minor),
        "expected_package_duration_minutes": int(package.duration_minutes),
        "expected_package_variant": package.variant,
        "player_count": player_count,
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_printed_tariff_is_exact_through_package_start_and_stop(
    client,
    session,
    seed_owner,
) -> None:
    """Exercise every printed base product and every supported PS5 party size."""

    packages = await _install_tariff(session, seed_owner)
    token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, token)

    listed = await client.get(
        "/api/v1/gaming/packages",
        headers=_headers(seed_owner, token),
    )
    assert listed.status_code == 200, listed.text
    listed_by_code = {row["code"]: row for row in listed.json()}
    assert set(listed_by_code) == set(packages)
    assert len(listed_by_code) == 17

    # Hard-coded paise values are the photographed price card, not values
    # derived from the implementation under test.
    cases = (
        ("standard-single-session-30m", 1, 8_000),
        ("standard-single-session-60m", 1, 12_000),
        ("standard-dual-session-30m", 2, 10_000),
        ("standard-dual-session-30m", 3, 13_000),
        ("standard-dual-session-30m", 4, 16_000),
        ("standard-dual-session-60m", 2, 15_000),
        ("standard-dual-session-60m", 3, 18_000),
        ("standard-dual-session-60m", 4, 21_000),
        ("premium-single-session-60m", 1, 15_000),
        ("premium-dual-session-60m", 2, 19_000),
        ("premium-dual-session-60m", 3, 22_000),
        ("premium-dual-session-60m", 4, 25_000),
        ("standard-simdrive-session-15m", 1, 7_000),
        ("standard-simdrive-session-30m", 1, 10_000),
        ("standard-simdrive-session-60m", 1, 18_000),
    )
    stations = [
        _station(seed_owner, packages[code].station_type, index)
        for index, (code, _players, _amount) in enumerate(cases, start=1)
    ]
    session.add_all(stations)
    await session.commit()

    for index, ((code, player_count, expected_amount), station) in enumerate(
        zip(cases, stations, strict=True),
        start=1,
    ):
        package = packages[code]
        payload = _start_payload(
            station_id=station.id,
            shift_id=shift_id,
            package=package,
            player_count=player_count,
        )
        start_key = f"tariff-matrix-start:{uuid4()}"
        started = await client.post(
            "/api/v1/gaming/sessions/start",
            json=payload,
            headers=_headers(seed_owner, token, key=start_key),
        )
        assert started.status_code == 201, f"{code}/{player_count}: {started.text}"
        body = started.json()
        assert body["status"] == "active"
        assert body["billing_mode"] == "package"
        assert body["package_price_minor_snapshot"] == int(package.price_minor)
        assert body["package_duration_minutes_snapshot"] == int(package.duration_minutes)
        assert body["package_variant_snapshot"] == package.variant
        assert body["package_pricing_tier_snapshot"] == package.pricing_tier
        assert body["timer_minutes"] == int(package.duration_minutes)
        assert body["extra_controllers"] == max(0, player_count - 2)
        assert body["amount_minor"] == expected_amount

        if index == 1:
            replay = await client.post(
                "/api/v1/gaming/sessions/start",
                json=payload,
                headers=_headers(seed_owner, token, key=start_key),
            )
            assert replay.status_code == 201, replay.text
            assert replay.json() == body

            changed_request = await client.post(
                "/api/v1/gaming/sessions/start",
                json={**payload, "customer_name": "Different request"},
                headers=_headers(seed_owner, token, key=start_key),
            )
            assert changed_request.status_code == 409, changed_request.text

        stop_key = f"tariff-matrix-stop:{uuid4()}"
        stopped = await client.post(
            f"/api/v1/gaming/sessions/{body['id']}/stop",
            json={},
            headers=_headers(seed_owner, token, key=stop_key),
        )
        assert stopped.status_code == 200, f"{code}/{player_count}: {stopped.text}"
        assert stopped.json()["status"] == "ended"
        # Fixed packages never silently switch to elapsed hourly billing.
        assert stopped.json()["amount_minor"] == expected_amount

        stop_replay = await client.post(
            f"/api/v1/gaming/sessions/{body['id']}/stop",
            json={},
            headers=_headers(seed_owner, token, key=stop_key),
        )
        assert stop_replay.status_code == 200, stop_replay.text
        assert stop_replay.json() == stopped.json()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_multiplayer_extensions_charge_only_cumulative_controller_delta(
    client,
    session,
    seed_owner,
) -> None:
    packages = await _install_tariff(session, seed_owner)
    station = _station(seed_owner, "ps5", 1)
    session.add(station)
    await session.commit()
    token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, token)

    base = packages["standard-dual-session-30m"]
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json=_start_payload(
            station_id=station.id,
            shift_id=shift_id,
            package=base,
            player_count=3,
        ),
        headers=_headers(seed_owner, token, key=f"extension-start:{uuid4()}"),
    )
    assert started.status_code == 201, started.text
    assert started.json()["timer_minutes"] == 30
    assert started.json()["amount_minor"] == 13_000  # ₹100 + minimum ₹30
    session_id = started.json()["id"]

    premium_extension = packages["premium-dual-extension-30m"]
    wrong_tier = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/extend",
        json={
            "package_id": str(premium_extension.id),
            "expected_timer_minutes": 30,
            "expected_amount_minor": 13_000,
            "expected_package_price_minor": 9_000,
            "expected_package_duration_minutes": 30,
            "expected_package_variant": "dual",
        },
        headers=_headers(seed_owner, token, key=f"wrong-tier:{uuid4()}"),
    )
    assert wrong_tier.status_code == 409, wrong_tier.text
    assert wrong_tier.json()["error"]["code"] == "gaming_extension_not_applied"
    assert wrong_tier.json()["error"]["details"]["reason_code"] == (
        "package_pricing_tier_incompatible"
    )

    extension = packages["standard-dual-extension-30m"]

    def extension_payload(*, timer: int, amount: int) -> dict[str, object]:
        return {
            "package_id": str(extension.id),
            "expected_timer_minutes": timer,
            "expected_amount_minor": amount,
            "expected_package_price_minor": 7_000,
            "expected_package_duration_minutes": 30,
            "expected_package_variant": "dual",
        }

    first_key = f"standard-extension:{uuid4()}"
    first = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/extend",
        json=extension_payload(timer=30, amount=13_000),
        headers=_headers(seed_owner, token, key=first_key),
    )
    assert first.status_code == 200, first.text
    assert first.json()["timer_minutes"] == 60
    # The minimum ₹30 controller charge was already present at 30 minutes.
    assert first.json()["amount_minor"] == 20_000

    replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/extend",
        json=extension_payload(timer=30, amount=13_000),
        headers=_headers(seed_owner, token, key=first_key),
    )
    assert replay.status_code == 200, replay.text
    assert replay.json() == first.json()

    second = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/extend",
        json=extension_payload(timer=60, amount=20_000),
        headers=_headers(seed_owner, token, key=f"standard-extension:{uuid4()}"),
    )
    assert second.status_code == 200, second.text
    assert second.json()["timer_minutes"] == 90
    # Crossing the one-hour boundary adds the next ₹30 controller block once.
    assert second.json()["amount_minor"] == 30_000

    concurrent = await asyncio.gather(
        *(
            client.post(
                f"/api/v1/gaming/sessions/{session_id}/extend",
                json=extension_payload(timer=90, amount=30_000),
                headers=_headers(
                    seed_owner,
                    token,
                    key=f"concurrent-standard-extension:{uuid4()}",
                ),
            )
            for _ in range(2)
        )
    )
    assert sorted(response.status_code for response in concurrent) == [200, 409]
    success = next(response for response in concurrent if response.status_code == 200)
    assert success.json()["timer_minutes"] == 120
    assert success.json()["amount_minor"] == 37_000

    ledger = (
        (
            await session.execute(
                select(GamingSessionExtension)
                .where(GamingSessionExtension.gaming_session_id == UUID(session_id))
                .order_by(GamingSessionExtension.created_at, GamingSessionExtension.id)
                .execution_options(populate_existing=True)
            )
        )
        .scalars()
        .all()
    )
    assert len(ledger) == 3
    assert [int(row.controller_surcharge_minor) for row in ledger] == [0, 3_000, 0]
    assert [int(row.total_minor) for row in ledger] == [7_000, 10_000, 7_000]


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("base_code", "extension_code", "expected_total_minor"),
    [
        ("standard-single-session-60m", "standard-single-extension-30m", 18_000),
        ("standard-single-session-60m", "standard-single-extension-60m", 22_000),
        ("standard-dual-session-60m", "standard-dual-extension-30m", 22_000),
        ("standard-dual-session-60m", "standard-dual-extension-60m", 28_000),
        ("premium-single-session-60m", "premium-single-extension-30m", 22_000),
        ("premium-single-session-60m", "premium-single-extension-60m", 27_000),
        ("premium-dual-session-60m", "premium-dual-extension-30m", 28_000),
        ("premium-dual-session-60m", "premium-dual-extension-60m", 34_000),
    ],
)
async def test_every_printed_ps5_extension_reaches_the_exact_locked_total(
    client,
    session,
    seed_owner,
    base_code: str,
    extension_code: str,
    expected_total_minor: int,
) -> None:
    packages = await _install_tariff(session, seed_owner)
    station = _station(seed_owner, "ps5", 1)
    session.add(station)
    await session.commit()
    token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, token)
    base = packages[base_code]
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json=_start_payload(
            station_id=station.id,
            shift_id=shift_id,
            package=base,
            player_count=int(base.included_players),
        ),
        headers=_headers(seed_owner, token, key=f"extension-matrix-start:{uuid4()}"),
    )
    assert started.status_code == 201, started.text
    extension = packages[extension_code]
    extended = await client.post(
        f"/api/v1/gaming/sessions/{started.json()['id']}/extend",
        json={
            "package_id": str(extension.id),
            "expected_timer_minutes": int(base.duration_minutes),
            "expected_amount_minor": int(base.price_minor),
            "expected_package_price_minor": int(extension.price_minor),
            "expected_package_duration_minutes": int(extension.duration_minutes),
            "expected_package_variant": extension.variant,
        },
        headers=_headers(seed_owner, token, key=f"extension-matrix-apply:{uuid4()}"),
    )
    assert extended.status_code == 200, extended.text
    assert extended.json()["amount_minor"] == expected_total_minor
    assert extended.json()["timer_minutes"] == (
        int(base.duration_minutes) + int(extension.duration_minutes)
    )


async def _create_role_user(
    session,
    seed_owner,
    *,
    role_code: str,
    name: str,
) -> tuple[User, str]:
    role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code=role_code,
        name=role_code.replace("_", " ").title(),
        permissions=[],
    )
    password = f"{role_code}-e2e-password"
    user = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"{role_code}-{uuid4().hex[:10]}@test.local",
        name=name,
        password_hash=hash_password(password),
        status="active",
    )
    session.add_all([role, user])
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=user.id,
            role_id=role.id,
            branch_id=seed_owner["branch"].id,
            granted_by=seed_owner["owner"].id,
        )
    )
    await session.commit()
    await session.refresh(user)
    return user, password


async def _start_stop_send(
    client,
    seed_owner,
    *,
    token: str,
    station: Station,
    shift_id: UUID,
    package: GamingPackage,
    player_count: int,
) -> tuple[dict, dict, dict]:
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        json=_start_payload(
            station_id=station.id,
            shift_id=shift_id,
            package=package,
            player_count=player_count,
        ),
        headers=_headers(seed_owner, token, key=f"settlement-start:{uuid4()}"),
    )
    assert started.status_code == 201, started.text
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{started.json()['id']}/stop",
        json={},
        headers=_headers(seed_owner, token, key=f"settlement-stop:{uuid4()}"),
    )
    assert stopped.status_code == 200, stopped.text
    sent = await client.post(
        f"/api/v1/gaming/sessions/{started.json()['id']}/send-to-pos",
        headers=_headers(seed_owner, token, key=f"settlement-send:{uuid4()}"),
    )
    assert sent.status_code == 201, sent.text
    return started.json(), stopped.json(), sent.json()


async def _claim_and_pay(
    client,
    seed_owner,
    *,
    token: str,
    order_id: str,
    method: str,
    amount_minor: int,
    tendered_minor: int | None = None,
) -> tuple[dict, dict]:
    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=_headers(seed_owner, token),
    )
    assert claim.status_code == 201, claim.text
    claim_body = claim.json()
    assert claim_body["due_minor"] == amount_minor

    payload: dict[str, object] = {
        "method": method,
        "amount_minor": amount_minor,
        "expected_order_total_minor": claim_body["order_total_minor"],
        "expected_due_minor": claim_body["due_minor"],
    }
    if tendered_minor is not None:
        payload["tendered_minor"] = tendered_minor
    if method == "upi":
        payload["ref_external"] = f"UPI-E2E-{uuid4().hex[:8]}"

    payment_key = f"settlement-payment:{uuid4()}"
    payment_headers = {
        **_headers(seed_owner, token, key=payment_key),
        "X-Checkout-Claim": claim_body["claim_token"],
    }
    paid = await client.post(
        f"/api/v1/pos/orders/{order_id}/payments",
        json=payload,
        headers=payment_headers,
    )
    assert paid.status_code == 201, paid.text
    assert paid.json()["order_status"] == "paid"
    assert paid.json()["bill_amount_minor"] == amount_minor

    replay = await client.post(
        f"/api/v1/pos/orders/{order_id}/payments",
        json=payload,
        headers=payment_headers,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == paid.json()
    return claim_body, paid.json()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_other_co_owner_can_finish_cash_upi_and_close_without_audit_access(
    client,
    session,
    seed_owner,
) -> None:
    """Model Rafi/Sameer finishing one another's operational shift safely."""

    # Tax may be disabled for an unregistered business, but the invoice split
    # still requires the shop's authoritative state for a valid receipt.
    seed_owner["company"].gstin = "32AAAAA0000A1Z5"
    seed_owner["branch"].state_code = "32"
    await session.commit()
    packages = await _install_tariff(session, seed_owner)
    owner_token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, owner_token, opening_float=50_000)
    co_owner, co_owner_password = await _create_role_user(
        session,
        seed_owner,
        role_code="co_owner",
        name="Other operational owner",
    )
    co_owner_token = await _login(
        client,
        email=co_owner.email,
        password=co_owner_password,
    )
    identity = await client.get(
        "/api/v1/auth/me",
        headers=_headers(seed_owner, co_owner_token),
    )
    assert identity.status_code == 200, identity.text
    assert identity.json()["roles"] == ["owner"]
    assert identity.json()["protected_access"] is True
    assert identity.json()["audit_access"] is False
    assert "pos.discount.large" in identity.json()["effective_permissions"]
    assert "admin.audit.read" not in identity.json()["effective_permissions"]

    cash_station = _station(seed_owner, "ps5", 1)
    upi_station = _station(seed_owner, "ps5", 2)
    session.add_all([cash_station, upi_station])
    await session.commit()

    cash_package = packages["standard-single-session-60m"]
    cash_started = await client.post(
        "/api/v1/gaming/sessions/start",
        json=_start_payload(
            station_id=cash_station.id,
            shift_id=shift_id,
            package=cash_package,
            player_count=1,
        ),
        headers=_headers(seed_owner, owner_token, key=f"cash-start:{uuid4()}"),
    )
    assert cash_started.status_code == 201, cash_started.text

    running_close = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/close",
        json={"counted_minor": 50_000},
        headers=_headers(seed_owner, co_owner_token),
    )
    assert running_close.status_code == 422, running_close.text
    assert "running gaming session" in running_close.json()["error"]["message"]

    cash_stopped = await client.post(
        f"/api/v1/gaming/sessions/{cash_started.json()['id']}/stop",
        json={},
        headers=_headers(seed_owner, co_owner_token, key=f"cash-stop:{uuid4()}"),
    )
    assert cash_stopped.status_code == 200, cash_stopped.text
    assert cash_stopped.json()["amount_minor"] == 12_000

    unbilled_close = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/close",
        json={"counted_minor": 50_000},
        headers=_headers(seed_owner, co_owner_token),
    )
    assert unbilled_close.status_code == 422, unbilled_close.text
    assert "not yet sent to POS" in unbilled_close.json()["error"]["message"]

    cash_sent = await client.post(
        f"/api/v1/gaming/sessions/{cash_started.json()['id']}/send-to-pos",
        headers=_headers(seed_owner, co_owner_token, key=f"cash-send:{uuid4()}"),
    )
    assert cash_sent.status_code == 201, cash_sent.text
    # The route is naturally idempotent through the locked session/order link.
    cash_sent_replay = await client.post(
        f"/api/v1/gaming/sessions/{cash_started.json()['id']}/send-to-pos",
        headers=_headers(seed_owner, co_owner_token, key=f"cash-send-replay:{uuid4()}"),
    )
    assert cash_sent_replay.status_code == 201, cash_sent_replay.text
    assert cash_sent_replay.json() == cash_sent.json()

    held_close = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/close",
        json={"counted_minor": 50_000},
        headers=_headers(seed_owner, co_owner_token),
    )
    assert held_close.status_code == 422, held_close.text
    assert "unfinished order" in held_close.json()["error"]["message"]

    cash_order = await client.get(
        f"/api/v1/pos/orders/{cash_sent.json()['order_id']}",
        headers=_headers(seed_owner, co_owner_token),
    )
    assert cash_order.status_code == 200, cash_order.text
    assert cash_order.json()["status"] == "held"
    assert cash_order.json()["total_minor"] == 12_000
    discount_key = f"gaming-held-discount:{uuid4()}"
    discount_payload = {
        "manual_discount_minor": 2_000,
        "expected_checkout_version": cash_order.json()["checkout_version"],
    }
    discounted = await client.patch(
        f"/api/v1/pos/orders/{cash_sent.json()['order_id']}/discount",
        json=discount_payload,
        headers=_headers(seed_owner, co_owner_token, key=discount_key),
    )
    assert discounted.status_code == 200, discounted.text
    assert discounted.json()["manual_discount_minor"] == 2_000
    assert discounted.json()["total_minor"] == 10_000
    discounted_replay = await client.patch(
        f"/api/v1/pos/orders/{cash_sent.json()['order_id']}/discount",
        json=discount_payload,
        headers=_headers(seed_owner, co_owner_token, key=discount_key),
    )
    assert discounted_replay.status_code == 200, discounted_replay.text
    assert discounted_replay.json() == discounted.json()

    _cash_claim, cash_payment = await _claim_and_pay(
        client,
        seed_owner,
        token=co_owner_token,
        order_id=cash_sent.json()["order_id"],
        method="cash",
        amount_minor=10_000,
        tendered_minor=15_000,
    )
    assert cash_payment["tendered_minor"] == 15_000
    assert cash_payment["change_minor"] == 5_000

    upi_started, upi_stopped, upi_sent = await _start_stop_send(
        client,
        seed_owner,
        token=co_owner_token,
        station=upi_station,
        shift_id=shift_id,
        package=packages["premium-dual-session-60m"],
        player_count=3,
    )
    assert upi_started["amount_minor"] == 22_000
    assert upi_stopped["amount_minor"] == 22_000
    _upi_claim, upi_payment = await _claim_and_pay(
        client,
        seed_owner,
        token=co_owner_token,
        order_id=upi_sent["order_id"],
        method="upi",
        amount_minor=22_000,
    )
    assert upi_payment["method"] == "upi"
    assert upi_payment["change_minor"] is None

    # The owner web report reads the same server truth immediately: gaming
    # lines remain visible at their pre-discount category value, while the
    # order-level discount and settlement rails reconcile to net revenue.
    report_response = await client.get(
        "/api/v1/reports/daily",
        headers=_headers(seed_owner, co_owner_token),
    )
    assert report_response.status_code == 200, report_response.text
    report = report_response.json()
    assert report["branch_id"] == str(seed_owner["branch"].id)
    assert report["orders_count"] == 2
    assert report["revenue"]["gaming_minor"] == 34_000
    assert report["revenue"]["discounts_and_points_redeemed_minor"] == 2_000
    assert report["revenue"]["total_minor"] == 32_000
    assert report["payments_received"]["cash_minor"] == 10_000
    assert report["payments_received"]["upi_minor"] == 22_000
    assert report["payments_received"]["total_minor"] == 32_000
    assert report["gross_revenue_minor"] == 32_000
    assert report["net_payments_received_minor"] == 32_000

    stored_shift = (
        await session.execute(
            select(Shift).where(Shift.id == shift_id).execution_options(populate_existing=True)
        )
    ).scalar_one()
    assert stored_shift.opened_by == seed_owner["owner"].id
    # Only cash affects expected drawer cash; UPI is a separate rail.
    assert int(stored_shift.expected_minor) == 60_000

    closed = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/close",
        json={"counted_minor": 60_000},
        headers=_headers(seed_owner, co_owner_token),
    )
    assert closed.status_code == 200, closed.text
    assert closed.json() == {
        "id": str(shift_id),
        "status": "closed",
        "variance_minor": 0,
    }
    close_replay = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/close",
        json={"counted_minor": 60_000},
        headers=_headers(seed_owner, co_owner_token),
    )
    assert close_replay.status_code == 200, close_replay.text
    assert close_replay.json() == closed.json()

    stored_sessions = (
        (
            await session.execute(
                select(GamingSession)
                .where(GamingSession.shift_id == shift_id)
                .order_by(GamingSession.start_at)
                .execution_options(populate_existing=True)
            )
        )
        .scalars()
        .all()
    )
    assert len(stored_sessions) == 2
    assert stored_sessions[0].opened_by == seed_owner["owner"].id
    assert stored_sessions[0].stopped_by == co_owner.id
    assert stored_sessions[0].sent_to_pos_by == co_owner.id
    assert stored_sessions[1].opened_by == co_owner.id
    assert stored_sessions[1].stopped_by == co_owner.id
    assert stored_sessions[1].sent_to_pos_by == co_owner.id

    orders = (
        (
            await session.execute(
                select(Order)
                .where(Order.shift_id == shift_id)
                .order_by(Order.opened_at)
                .execution_options(populate_existing=True)
            )
        )
        .scalars()
        .all()
    )
    payments = (
        (
            await session.execute(
                select(Payment)
                .where(Payment.shift_id == shift_id)
                .order_by(Payment.paid_at)
                .execution_options(populate_existing=True)
            )
        )
        .scalars()
        .all()
    )
    assert [order.status for order in orders] == ["paid", "paid"]
    assert [int(order.total_minor) for order in orders] == [10_000, 22_000]
    assert [payment.method for payment in payments] == ["cash", "upi"]
    assert all(payment.recorded_by == co_owner.id for payment in payments)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_read_only_partner_cannot_start_and_parallel_starts_create_one_session(
    client,
    session,
    seed_owner,
) -> None:
    packages = await _install_tariff(session, seed_owner)
    station = _station(seed_owner, "ps5", 1)
    session.add(station)
    await session.commit()
    owner_token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, owner_token)
    package = packages["standard-single-session-60m"]
    payload = _start_payload(
        station_id=station.id,
        shift_id=shift_id,
        package=package,
        player_count=1,
    )

    partner, partner_password = await _create_role_user(
        session,
        seed_owner,
        role_code="partner",
        name="Read-only partner",
    )
    partner_token = await _login(
        client,
        email=partner.email,
        password=partner_password,
    )
    visible = await client.get(
        "/api/v1/gaming/packages",
        headers=_headers(seed_owner, partner_token),
    )
    assert visible.status_code == 200, visible.text
    denied = await client.post(
        "/api/v1/gaming/sessions/start",
        json=payload,
        headers=_headers(seed_owner, partner_token, key=f"partner-start:{uuid4()}"),
    )
    assert denied.status_code == 403, denied.text
    assert denied.json()["error"]["code"] == "forbidden"
    assert "gaming.write" in denied.json()["error"]["message"]

    first, second = await asyncio.gather(
        *(
            client.post(
                "/api/v1/gaming/sessions/start",
                json=payload,
                headers=_headers(
                    seed_owner,
                    owner_token,
                    key=f"parallel-package-start:{uuid4()}",
                ),
            )
            for _ in range(2)
        )
    )
    assert sorted(response.status_code for response in (first, second)) == [201, 409]
    success = first if first.status_code == 201 else second
    assert success.json()["amount_minor"] == 12_000

    active = (
        (
            await session.execute(
                select(GamingSession)
                .where(
                    GamingSession.company_id == seed_owner["company"].id,
                    GamingSession.station_id == station.id,
                    GamingSession.status == "active",
                )
                .execution_options(populate_existing=True)
            )
        )
        .scalars()
        .all()
    )
    assert len(active) == 1


@pytest.mark.integration
@pytest.mark.asyncio
async def test_fixed_tariff_guard_rejects_hourly_ps5_and_simulator_but_not_vr(
    client,
    session,
    seed_owner,
) -> None:
    await _install_tariff(session, seed_owner)
    stations = [
        _station(seed_owner, "ps5", 1),
        _station(seed_owner, "simulator", 2),
        _station(seed_owner, "vr", 3),
    ]
    session.add_all(stations)
    await session.commit()
    token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, token)

    for station in stations[:2]:
        response = await client.post(
            "/api/v1/gaming/sessions/start",
            json={
                "station_id": str(station.id),
                "shift_id": str(shift_id),
                "expected_rate_per_hour_minor": int(station.rate_per_hour_minor),
            },
            headers=_headers(seed_owner, token, key=f"fixed-tariff-guard:{uuid4()}"),
        )
        assert response.status_code == 422, response.text
        assert "requires a fixed-price tariff package" in response.json()["error"]["message"]

    vr = stations[2]
    allowed = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(vr.id),
            "shift_id": str(shift_id),
            "expected_rate_per_hour_minor": int(vr.rate_per_hour_minor),
        },
        headers=_headers(seed_owner, token, key=f"uncovered-hourly:{uuid4()}"),
    )
    assert allowed.status_code == 201, allowed.text
    assert allowed.json()["billing_mode"] == "hourly"


@pytest.mark.integration
@pytest.mark.asyncio
async def test_start_does_not_disclose_another_branch_station_or_tariff(
    client,
    session,
    seed_owner,
) -> None:
    other_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Other private branch",
        invoice_series_code="OB",
    )
    session.add(other_branch)
    await session.flush()
    other_station = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=other_branch.id,
        code=f"OTHER-{uuid4().hex[:8]}",
        name="Other branch PS5",
        type="ps5",
        rate_per_hour_minor=99_999,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    session.add(other_station)
    await session.commit()
    installed = await upsert_d_company_gaming_tariff(
        session,
        company_id=seed_owner["company"].id,
        branch_id=other_branch.id,
    )
    assert len(installed.created_codes) == 17
    await session.commit()

    token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, token)
    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(other_station.id),
            "shift_id": str(shift_id),
            "expected_rate_per_hour_minor": int(other_station.rate_per_hour_minor),
        },
        headers=_headers(seed_owner, token, key=f"cross-branch-start:{uuid4()}"),
    )

    assert response.status_code == 404, response.text
    assert response.json()["error"]["message"] == "station not found"


@pytest.mark.integration
@pytest.mark.asyncio
async def test_hourly_ps5_remains_compatible_when_tenant_has_no_tariff(
    client,
    session,
    seed_owner,
) -> None:
    station = _station(seed_owner, "ps5", 1)
    session.add(station)
    await session.commit()
    token = await _login(
        client,
        email=seed_owner["owner"].email,
        password=seed_owner["password"],
    )
    shift_id = await _open_shift(client, seed_owner, token)

    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift_id),
            "expected_rate_per_hour_minor": int(station.rate_per_hour_minor),
        },
        headers=_headers(seed_owner, token, key=f"legacy-hourly:{uuid4()}"),
    )

    assert response.status_code == 201, response.text
    assert response.json()["billing_mode"] == "hourly"
    assert response.json()["package_id"] is None
