"""PostgreSQL API proof for the one-time PS5 60-to-30-minute amendment."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text
from sqlalchemy.exc import DBAPIError

from app.models import (
    Branch,
    Company,
    Customer,
    GamingPackage,
    GamingSession,
    GamingSessionPackageAmendment,
    IdempotencyKey,
    Station,
)
from app.services.gaming.tariff_catalog import upsert_d_company_gaming_tariff


def _headers(seed_owner, token: str, key: str | None = None) -> dict[str, str]:
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    if key is not None:
        headers["Idempotency-Key"] = key
    return headers


def _offline_headers(
    seed_owner,
    token: str,
    key: str,
    occurred_at: datetime,
) -> dict[str, str]:
    return {
        **_headers(seed_owner, token, key),
        "X-Offline-Captured": "true",
        "X-Client-Action-Id": key,
        "X-Client-Occurred-At": occurred_at.isoformat(),
    }


async def _login(client, seed_owner) -> str:
    response = await client.post(
        "/api/v1/auth/login",
        json={"email": seed_owner["owner"].email, "password": seed_owner["password"]},
    )
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


async def _setup(client, session, seed_owner):
    seed_owner["company"].gstin = "32AAAAA0000A1Z5"
    seed_owner["branch"].state_code = "32"
    await upsert_d_company_gaming_tariff(
        session,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
    )
    station = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        code=f"A-{uuid4().hex[:8]}",
        name="Amendment PS5",
        type="ps5",
        rate_per_hour_minor=15_000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    primary = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Amendment primary",
        phone="9876543270",
    )
    friend = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Amendment friend",
        phone="9876543271",
    )
    session.add_all([station, primary, friend])
    await session.commit()
    packages = {
        row.code: row
        for row in (
            await session.execute(
                select(GamingPackage).where(
                    GamingPackage.company_id == seed_owner["company"].id,
                    GamingPackage.branch_id == seed_owner["branch"].id,
                    GamingPackage.is_active.is_(True),
                )
            )
        ).scalars()
    }
    token = await _login(client, seed_owner)
    opened = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 0},
        headers=_headers(seed_owner, token),
    )
    assert opened.status_code == 201, opened.text
    return token, UUID(opened.json()["id"]), station, primary, friend, packages


async def _start(
    client,
    seed_owner,
    token: str,
    shift_id: UUID,
    station: Station,
    package: GamingPackage,
    *,
    customer_id: UUID | None = None,
    player_count: int = 1,
) -> dict:
    customer = (
        {
            "customer_id": str(customer_id),
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
        }
        if customer_id is not None
        else {}
    )
    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift_id),
            "package_id": str(package.id),
            "expected_package_price_minor": int(package.price_minor),
            "expected_package_duration_minutes": int(package.duration_minutes),
            "expected_package_variant": package.variant,
            "player_count": player_count,
            **customer,
        },
        headers=_headers(seed_owner, token, f"start:{uuid4()}"),
    )
    assert response.status_code == 201, response.text
    return response.json()


def _amend_payload(
    target: GamingPackage,
    *,
    amount_minor: int,
    pause_version: int = 0,
    occurred_at: datetime | None = None,
    play_elapsed_ms: int | None = None,
) -> dict:
    payload = {
        "target_package_id": str(target.id),
        "expected_timer_minutes": 60,
        "expected_amount_minor": amount_minor,
        "expected_pause_version": pause_version,
        "expected_participant_revision": 0,
        "expected_billing_revision": 0,
        "expected_target_price_minor": int(target.price_minor),
        "expected_target_duration_minutes": 30,
        "expected_target_variant": target.variant,
    }
    if occurred_at is not None:
        payload["occurred_at"] = occurred_at.isoformat()
        payload["play_elapsed_ms"] = play_elapsed_ms
    return payload


@pytest.mark.integration
@pytest.mark.asyncio
async def test_single_offline_amendment_replay_friend_payment_receipt_and_playtime(
    client,
    session,
    seed_owner,
) -> None:
    token, shift_id, station, primary, friend, packages = await _setup(
        client, session, seed_owner
    )
    base = packages["standard-single-session-60m"]
    target = packages["standard-single-session-30m"]
    started = await _start(
        client,
        seed_owner,
        token,
        shift_id,
        station,
        base,
        customer_id=primary.id,
    )
    session_id = UUID(started["id"])
    started_at = datetime.now(UTC) - timedelta(minutes=40)
    captured_at = started_at + timedelta(minutes=29, seconds=59)
    gs = await session.get(GamingSession, session_id)
    gs.start_at = started_at
    await session.commit()

    key = f"amend:{uuid4()}"
    payload = _amend_payload(
        target,
        amount_minor=12_000,
        occurred_at=captured_at,
        play_elapsed_ms=1_799_000,
    )
    path = f"/api/v1/gaming/sessions/{session_id}/amend-package"
    amended = await client.post(
        path,
        json=payload,
        headers=_offline_headers(seed_owner, token, key, captured_at),
    )
    replay = await client.post(
        path,
        json=payload,
        headers=_offline_headers(seed_owner, token, key, captured_at),
    )
    assert amended.status_code == replay.status_code == 200, amended.text
    assert replay.json() == amended.json()
    assert amended.json()["timer_minutes"] == 30
    assert amended.json()["amount_minor"] == 8_000
    assert amended.json()["billing_revision"] == 1
    assert amended.json()["package_price_minor_snapshot"] == 12_000
    assert amended.json()["package_duration_minutes_snapshot"] == 60
    assert amended.json()["effective_package_price_minor"] == 8_000
    assert amended.json()["effective_package_duration_minutes"] == 30

    cached = await session.get(IdempotencyKey, key)
    assert cached is not None
    await session.delete(cached)
    await session.commit()
    durable = await client.post(
        path,
        json=payload,
        headers=_offline_headers(seed_owner, token, key, captured_at),
    )
    second_retry = await client.post(
        path,
        json=payload,
        headers=_offline_headers(seed_owner, token, key, captured_at),
    )
    assert durable.status_code == second_retry.status_code == 200
    assert second_retry.json() == durable.json()
    cached = await session.get(IdempotencyKey, key)
    assert cached is not None
    await session.delete(cached)
    await session.commit()
    tampered_replay_payload = dict(payload)
    tampered_replay_payload["expected_target_price_minor"] = 7_999
    tampered_replay = await client.post(
        path,
        json=tampered_replay_payload,
        headers=_offline_headers(seed_owner, token, key, captured_at),
    )
    assert tampered_replay.status_code == 409

    # Catalog lifecycle does not rewrite the receipt.
    await session.execute(
        text("DELETE FROM gaming_packages WHERE id=:id"), {"id": target.id}
    )
    await session.commit()

    # An offline participant event captured before the amendment cannot arrive later
    # and silently change the amended bill.
    pre_amend_join_at = captured_at - timedelta(seconds=1)
    rejected_pre_amend_join = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/join",
        json={
            "expected_participant_revision": 0,
            "customer_id": str(friend.id),
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
            "occurred_at": pre_amend_join_at.isoformat(),
            "play_elapsed_ms": 1_798_000,
            "expected_pause_version": 0,
        },
        headers=_offline_headers(
            seed_owner,
            token,
            f"join:{uuid4()}",
            pre_amend_join_at,
        ),
    )
    assert rejected_pre_amend_join.status_code == 409

    joined = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/join",
        json={
            "expected_participant_revision": 0,
            "customer_id": str(friend.id),
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
        },
        headers=_headers(seed_owner, token, f"join:{uuid4()}"),
    )
    assert joined.status_code == 200, joined.text
    deleted = await client.delete(
        f"/api/v1/customers/{friend.id}", headers=_headers(seed_owner, token)
    )
    assert deleted.status_code == 204, deleted.text

    stale_stop = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"expected_participant_revision": 1},
        headers=_headers(seed_owner, token, f"stop:{uuid4()}"),
    )
    assert stale_stop.status_code == 409
    stop_key = f"stop:{uuid4()}"
    stop_payload = {
        "expected_participant_revision": 1,
        "expected_billing_revision": 1,
    }
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json=stop_payload,
        headers=_headers(seed_owner, token, stop_key),
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["amount_minor"] == 11_000

    sent = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/send-to-pos",
        headers=_headers(seed_owner, token, f"pos:{uuid4()}"),
    )
    assert sent.status_code == 201, sent.text
    order_id = sent.json()["order_id"]
    order = await client.get(
        f"/api/v1/pos/orders/{order_id}", headers=_headers(seed_owner, token)
    )
    assert order.status_code == 200, order.text
    assert order.json()["total_minor"] == 11_000
    assert "amended 60→30 min (120.00→80.00)" in order.json()["lines"][0]["note"]
    stop_after_pos = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json=stop_payload,
        headers=_headers(seed_owner, token, stop_key),
    )
    assert stop_after_pos.status_code == 200, stop_after_pos.text
    assert stop_after_pos.json()["amount_minor"] == 11_000
    assert stop_after_pos.json()["order_id"] == order_id
    report_before_response = await client.get(
        "/api/v1/reports/daily", headers=_headers(seed_owner, token)
    )
    assert report_before_response.status_code == 200, report_before_response.text
    report_before = report_before_response.json()
    claim = await client.post(
        f"/api/v1/pos/orders/{order_id}/checkout-claim",
        headers=_headers(seed_owner, token),
    )
    paid = await client.post(
        f"/api/v1/pos/orders/{order_id}/payments",
        json={
            "method": "upi",
            "amount_minor": 11_000,
            "expected_order_total_minor": 11_000,
            "expected_due_minor": 11_000,
            "ref_external": f"UPI-{uuid4().hex[:8]}",
        },
        headers={
            **_headers(seed_owner, token, f"pay:{uuid4()}"),
            "X-Checkout-Claim": claim.json()["claim_token"],
        },
    )
    assert paid.status_code == 201, paid.text
    report_after_response = await client.get(
        "/api/v1/reports/daily", headers=_headers(seed_owner, token)
    )
    assert report_after_response.status_code == 200, report_after_response.text
    report_after = report_after_response.json()
    assert report_after["orders_count"] == report_before["orders_count"] + 1
    assert (
        report_after["revenue"]["gaming_minor"]
        == report_before["revenue"]["gaming_minor"] + 11_000
    )
    assert (
        report_after["payments_received"]["upi_minor"]
        == report_before["payments_received"]["upi_minor"] + 11_000
    )
    assert (
        report_after["gross_revenue_minor"]
        == report_before["gross_revenue_minor"] + 11_000
    )

    receipt = await client.get(
        f"/api/v1/pos/receipts/{order_id}", headers=_headers(seed_owner, token)
    )
    assert receipt.status_code == 200, receipt.text
    history = receipt.json()["gaming_sessions"][0]
    assert history["package_price_minor_snapshot"] == 12_000
    assert history["package_duration_minutes_snapshot"] == 60
    assert history["billing_revision"] == 1
    assert history["effective_package_id"] is None
    assert history["effective_package_price_minor_snapshot"] == 8_000
    assert history["effective_package_duration_minutes_snapshot"] == 30
    playtime = await client.get(
        f"/api/v1/customers/{primary.id}/playtime",
        headers=_headers(seed_owner, token),
    )
    assert playtime.status_code == 200, playtime.text
    assert playtime.json()["qualifying_paid_minutes"] == 30
    assert playtime.json()["history"][0]["qualifying_paid_minutes"] == 30
    deleted_friend_playtime = await client.get(
        f"/api/v1/customers/{friend.id}/playtime",
        headers=_headers(seed_owner, token),
    )
    assert deleted_friend_playtime.status_code == 404


@pytest.mark.integration
@pytest.mark.asyncio
async def test_dual_paused_2959_preserves_controller_and_allows_revisioned_extension(
    client,
    session,
    seed_owner,
) -> None:
    token, shift_id, station, _primary, _friend, packages = await _setup(
        client, session, seed_owner
    )
    base = packages["standard-dual-session-60m"]
    target = packages["standard-dual-session-30m"]
    started = await _start(
        client,
        seed_owner,
        token,
        shift_id,
        station,
        base,
        player_count=3,
    )
    gs = await session.get(GamingSession, UUID(started["id"]))
    paused_at = datetime.now(UTC)
    gs.start_at = paused_at - timedelta(minutes=29, seconds=59)
    gs.status = "paused"
    gs.paused_at = paused_at
    gs.pause_version = 1
    gs.last_pause_transition_at = paused_at
    await session.commit()

    amended = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/amend-package",
        json=_amend_payload(
            target,
            amount_minor=18_000,
            pause_version=1,
        ),
        headers=_headers(seed_owner, token, f"amend:{uuid4()}"),
    )
    assert amended.status_code == 200, amended.text
    assert amended.json()["amount_minor"] == 13_000
    assert amended.json()["extra_controllers"] == 1

    extension = packages["standard-dual-extension-30m"]
    old_client = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/extend",
        json={
            "package_id": str(extension.id),
            "expected_timer_minutes": 30,
            "expected_amount_minor": 13_000,
            "expected_package_price_minor": 7_000,
            "expected_package_duration_minutes": 30,
            "expected_package_variant": "dual",
        },
        headers=_headers(seed_owner, token, f"extend:{uuid4()}"),
    )
    assert old_client.status_code == 409
    extended = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/extend",
        json={
            "package_id": str(extension.id),
            "expected_timer_minutes": 30,
            "expected_amount_minor": 13_000,
            "expected_package_price_minor": 7_000,
            "expected_package_duration_minutes": 30,
            "expected_package_variant": "dual",
            "expected_billing_revision": 1,
        },
        headers=_headers(seed_owner, token, f"extend:{uuid4()}"),
    )
    assert extended.status_code == 200, extended.text
    assert extended.json()["timer_minutes"] == 60
    assert extended.json()["amount_minor"] == 20_000

    amendment = (
        await session.execute(
            select(GamingSessionPackageAmendment).where(
                GamingSessionPackageAmendment.gaming_session_id == gs.id
            )
        )
    ).scalar_one()
    gaming_session_id = gs.id
    with pytest.raises(DBAPIError, match="immutable"):
        await session.execute(
            text(
                "UPDATE gaming_session_package_amendments "
                "SET target_package_price_minor=9999 WHERE id=:id"
            ),
            {"id": amendment.id},
        )
    await session.rollback()
    with pytest.raises(DBAPIError, match="billing revision"):
        await session.execute(
            text("UPDATE gaming_sessions SET billing_revision=0 WHERE id=:id"),
            {"id": gaming_session_id},
        )
    await session.rollback()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_amendment_rejects_3000_price_scope_auth_and_hidden_revision_tamper(
    client,
    session,
    seed_owner,
) -> None:
    token, shift_id, station, _primary, _friend, packages = await _setup(
        client, session, seed_owner
    )
    base = packages["standard-single-session-60m"]
    target = packages["standard-single-session-30m"]
    started = await _start(client, seed_owner, token, shift_id, station, base)
    gs = await session.get(GamingSession, UUID(started["id"]))

    unauthenticated = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/amend-package",
        json=_amend_payload(target, amount_minor=12_000),
        headers={
            "X-Terminal-Id": str(seed_owner["terminal"].id),
            "Idempotency-Key": f"amend:{uuid4()}",
        },
    )
    assert unauthenticated.status_code == 401

    other_company = Company(id=uuid4(), name="Other amendment tenant")
    other_branch = Branch(
        id=uuid4(),
        company_id=other_company.id,
        name="Other branch",
        invoice_series_code="OT",
    )
    other_target = GamingPackage(
        id=uuid4(),
        company_id=other_company.id,
        branch_id=other_branch.id,
        code="standard-single-session-30m",
        station_type="ps5",
        variant="single",
        pricing_tier="standard",
        kind="base",
        name="Other tenant Single 30",
        duration_minutes=30,
        price_minor=8_000,
        included_players=1,
        max_players=1,
        sort_order=10,
        is_active=True,
    )
    session.add(other_company)
    await session.flush()
    session.add(other_branch)
    await session.flush()
    session.add(other_target)
    await session.commit()
    cross_tenant = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/amend-package",
        json=_amend_payload(other_target, amount_minor=12_000),
        headers=_headers(seed_owner, token, f"amend:{uuid4()}"),
    )
    assert cross_tenant.status_code == 404

    wrong_price = _amend_payload(target, amount_minor=12_000)
    wrong_price["expected_target_price_minor"] = 7_999
    rejected_price = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/amend-package",
        json=wrong_price,
        headers=_headers(seed_owner, token, f"amend:{uuid4()}"),
    )
    assert rejected_price.status_code == 409
    target.price_minor = 8_001
    await session.commit()
    tampered_catalog = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/amend-package",
        json=_amend_payload(target, amount_minor=12_000),
        headers=_headers(seed_owner, token, f"amend:{uuid4()}"),
    )
    assert tampered_catalog.status_code == 409
    target.price_minor = 8_000
    exact_boundary = datetime.now(UTC)
    gs.start_at = exact_boundary - timedelta(minutes=30)
    gs.status = "paused"
    gs.paused_at = exact_boundary
    gs.pause_version = 1
    gs.last_pause_transition_at = exact_boundary
    await session.commit()
    rejected_boundary = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/amend-package",
        json=_amend_payload(target, amount_minor=12_000, pause_version=1),
        headers=_headers(seed_owner, token, f"amend:{uuid4()}"),
    )
    assert rejected_boundary.status_code == 422
    assert "before 30:00" in rejected_boundary.json()["error"]["message"]

    receipt_count = int(
        (
                await session.execute(
                    select(func.count())
                    .select_from(GamingSessionPackageAmendment)
                    .where(GamingSessionPackageAmendment.gaming_session_id == gs.id)
                )
        ).scalar_one()
    )
    assert receipt_count == 0
