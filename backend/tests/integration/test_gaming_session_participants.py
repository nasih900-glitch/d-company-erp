"""PostgreSQL HTTP proof for paid friends joining a running PS5 session."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.core.config import get_settings
from app.core.security import hash_password, issue_access_token
from app.models import (
    Customer,
    CustomerMembership,
    GamingPackage,
    GamingParticipantSettlement,
    GamingParticipantSettlementLine,
    GamingSession,
    GamingSessionParticipant,
    MembershipTier,
    Role,
    Station,
    User,
    UserRole,
)
from app.services.gaming.tariff_catalog import upsert_d_company_gaming_tariff


def _headers(seed_owner, token: str, key: str | None = None) -> dict[str, str]:
    terminal_id = seed_owner.get("_terminal_id")
    if terminal_id is None:
        terminal_id = seed_owner["terminal"].id
        seed_owner["_terminal_id"] = terminal_id
    result = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal_id),
    }
    if key:
        result["Idempotency-Key"] = key
    return result


def _offline_headers(seed_owner, token: str, key: str, occurred_at: datetime) -> dict[str, str]:
    return {
        **_headers(seed_owner, token, key),
        "X-Offline-Captured": "true",
        "X-Client-Action-Id": key,
        "X-Client-Occurred-At": occurred_at.isoformat(),
    }


async def _login(client, email: str, password: str) -> str:
    response = await client.post("/api/v1/auth/login", json={"email": email, "password": password})
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


async def _setup(client, session, seed_owner):
    seed_owner["company"].gstin = "32AAAAA0000A1Z5"
    seed_owner["branch"].state_code = "32"
    await upsert_d_company_gaming_tariff(
        session, company_id=seed_owner["company"].id, branch_id=seed_owner["branch"].id
    )
    station = Station(
        id=uuid4(), company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id, code=f"P-{uuid4().hex[:8]}",
        name="Participant PS5", type="ps5", rate_per_hour_minor=15000,
        is_active=True, tax_rate=0.18, sac_code="999692", rate_includes_tax=True,
    )
    friends = [
        Customer(id=uuid4(), company_id=seed_owner["company"].id, name=f"Friend {i}", phone=f"98765000{i:02}")
        for i in range(1, 5)
    ]
    session.add_all([station, *friends])
    await session.commit()
    packages = {
        row.code: row for row in (
            await session.execute(select(GamingPackage).where(
                GamingPackage.company_id == seed_owner["company"].id,
                GamingPackage.branch_id == seed_owner["branch"].id,
                GamingPackage.is_active.is_(True),
            ))
        ).scalars().all()
    }
    token = await _login(client, seed_owner["owner"].email, seed_owner["password"])
    opened = await client.post(
        "/api/v1/pos/shifts/open", json={"opening_float_minor": 0},
        headers=_headers(seed_owner, token),
    )
    assert opened.status_code == 201, opened.text
    return token, UUID(opened.json()["id"]), station, friends, packages


async def _start(
    client,
    seed_owner,
    token,
    shift_id,
    station,
    package,
    *,
    primary_customer_id: UUID | None = None,
    player_count: int = 1,
):
    customer = (
        {
            "customer_id": str(primary_customer_id),
            "customer_directory_revision": 0,
            "customer_directory_company_id": str(seed_owner["company"].id),
        }
        if primary_customer_id is not None
        else {}
    )
    response = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id), "shift_id": str(shift_id),
            "package_id": str(package.id),
            "expected_package_price_minor": int(package.price_minor),
            "expected_package_duration_minutes": int(package.duration_minutes),
            "expected_package_variant": package.variant, "player_count": player_count,
            **customer,
        },
        headers=_headers(seed_owner, token, f"start:{uuid4()}"),
    )
    assert response.status_code == 201, response.text
    return response.json()


async def _participant_action(client, seed_owner, token, *, path, key, payload, at):
    response = await client.post(
        path, json=payload,
        headers=_offline_headers(seed_owner, token, key, at),
    )
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_rejoin_rounds_once_extension_deleted_customer_stop_pos_and_payment(
    client, session, seed_owner, monkeypatch,
) -> None:
    monkeypatch.setattr(get_settings(), "gaming_pause_enabled", False)
    token, shift_id, station, friends, packages = await _setup(client, session, seed_owner)
    friend_ids = [friend.id for friend in friends]
    now = datetime.now(UTC)
    member_tier = MembershipTier(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code=f"guest-{uuid4().hex[:8]}",
        name="Guest billing proof",
        monthly_price_minor=1000,
        gaming_discount_pct=0.10,
        free_gaming_minutes_per_week=0,
    )
    session.add(member_tier)
    await session.flush()
    session.add(
        CustomerMembership(
            id=uuid4(),
            customer_id=friend_ids[3],
            tier_id=member_tier.id,
            billing_cycle="monthly",
            starts_at=now - timedelta(days=1),
            expires_at=now + timedelta(days=30),
            amount_paid_minor=1000,
        )
    )
    await session.commit()
    started = await _start(
        client, seed_owner, token, shift_id, station,
        packages["standard-single-session-60m"],
        primary_customer_id=friend_ids[3],
    )
    session_id = UUID(started["id"])
    t0 = datetime.now(UTC) - timedelta(hours=2)
    gs = await session.get(GamingSession, session_id)
    gs.start_at = t0
    await session.commit()

    company_id = str(seed_owner["company"].id)
    directory = {"customer_directory_revision": 0, "customer_directory_company_id": company_id}
    state = await _participant_action(
        client, seed_owner, token,
        path=f"/api/v1/gaming/sessions/{session_id}/participants/join",
        key=f"join:{uuid4()}", at=t0,
        payload={"expected_participant_revision": 0, "customer_id": str(friend_ids[0]), **directory,
                 "occurred_at": t0.isoformat(), "play_elapsed_ms": 0, "expected_pause_version": 0},
    )
    assert state["current_player_count"] == 2
    first_interval_id = state["participants"][0]["id"]
    state = await _participant_action(
        client, seed_owner, token,
        path=f"/api/v1/gaming/sessions/{session_id}/participants/join",
        key=f"join:{uuid4()}", at=t0,
        payload={"expected_participant_revision": 1, "customer_id": str(friend_ids[1]), **directory,
                 "occurred_at": t0.isoformat(), "play_elapsed_ms": 0, "expected_pause_version": 0},
    )
    friend2_interval_id = state["participants"][-1]["id"]

    extension = packages["standard-single-extension-30m"]
    extended = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/extend",
        json={
            "package_id": str(extension.id), "expected_timer_minutes": 60,
            "expected_amount_minor": 12000,
            "expected_package_price_minor": int(extension.price_minor),
            "expected_package_duration_minutes": 30,
            "expected_package_variant": "single",
        },
        headers=_headers(seed_owner, token, f"extend:{uuid4()}"),
    )
    assert extended.status_code == 200, extended.text
    assert int(extension.price_minor) == 6000
    assert extended.json()["amount_minor"] == 18000

    state = await _participant_action(
        client, seed_owner, token,
        path=f"/api/v1/gaming/sessions/{session_id}/participants/{first_interval_id}/leave",
        key=f"leave:{uuid4()}", at=t0 + timedelta(minutes=30),
        payload={"expected_participant_revision": 2, "occurred_at": (t0 + timedelta(minutes=30)).isoformat(),
                 "play_elapsed_ms": 1_800_000, "expected_pause_version": 0},
    )
    state = await _participant_action(
        client, seed_owner, token,
        path=f"/api/v1/gaming/sessions/{session_id}/participants/join",
        key=f"join:{uuid4()}", at=t0 + timedelta(minutes=40),
        payload={"expected_participant_revision": 3, "customer_id": str(friend_ids[0]), **directory,
                 "occurred_at": (t0 + timedelta(minutes=40)).isoformat(), "play_elapsed_ms": 2_400_000, "expected_pause_version": 0},
    )
    first_rejoin = state["participants"][-1]
    deleted = await client.delete(
        f"/api/v1/customers/{friend_ids[1]}", headers=_headers(seed_owner, token)
    )
    assert deleted.status_code == 204, deleted.text
    state = await _participant_action(
        client, seed_owner, token,
        path=f"/api/v1/gaming/sessions/{session_id}/participants/{friend2_interval_id}/leave",
        key=f"leave:{uuid4()}", at=t0 + timedelta(minutes=60, microseconds=1000),
        payload={"expected_participant_revision": 4, "occurred_at": (t0 + timedelta(minutes=60, microseconds=1000)).isoformat(),
                 "play_elapsed_ms": 3_600_001, "expected_pause_version": 0},
    )
    state = await _participant_action(
        client, seed_owner, token,
        path=f"/api/v1/gaming/sessions/{session_id}/participants/{first_rejoin['id']}/leave",
        key=f"leave:{uuid4()}", at=t0 + timedelta(minutes=70),
        payload={"expected_participant_revision": 5, "occurred_at": (t0 + timedelta(minutes=70)).isoformat(),
                 "play_elapsed_ms": 4_200_000, "expected_pause_version": 0},
    )
    stop_at = t0 + timedelta(minutes=90)
    stop_key = f"stop:{uuid4()}"
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"ended_at": stop_at.isoformat(), "expected_participant_revision": 6},
        headers=_offline_headers(seed_owner, token, stop_key, stop_at),
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["amount_minor"] == 27000
    replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"ended_at": stop_at.isoformat(), "expected_participant_revision": 6},
        headers=_offline_headers(seed_owner, token, stop_key, stop_at),
    )
    assert replay.status_code == 200 and replay.json() == stopped.json()
    await session.execute(text("DELETE FROM idempotency_keys WHERE key = :key"), {"key": stop_key})
    await session.commit()
    durable_stop_replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"ended_at": stop_at.isoformat(), "expected_participant_revision": 6},
        headers=_offline_headers(seed_owner, token, stop_key, stop_at),
    )
    assert durable_stop_replay.status_code == 200
    assert durable_stop_replay.json() == stopped.json()
    cancelled = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/cancel",
        json={"reason": "Must preserve participant settlement"},
        headers=_headers(seed_owner, token),
    )
    assert cancelled.status_code == 422
    repaired = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/repair-billing",
        json={
            "expected_amount_minor": 27000,
            "amount_minor": 27000,
            "reason": "Participant settlement cannot use legacy repair",
        },
        headers=_headers(
            seed_owner,
            issue_access_token(
                user_id=seed_owner["owner"].id,
                company_id=seed_owner["company"].id,
                branch_id=seed_owner["branch"].id,
                roles=["super_owner"],
                auth_version=seed_owner["owner"].auth_version,
            ),
            f"repair:{uuid4()}",
        ),
    )
    assert repaired.status_code == 422

    settlement = (await session.execute(select(GamingParticipantSettlement).where(
        GamingParticipantSettlement.gaming_session_id == session_id
    ))).scalar_one()
    lines = list((await session.execute(select(GamingParticipantSettlementLine).where(
        GamingParticipantSettlementLine.settlement_id == settlement.id
    ).order_by(GamingParticipantSettlementLine.charge_minor))).scalars().all())
    assert (settlement.base_amount_minor, settlement.guest_charge_minor, settlement.amount_after_minor) == (18000, 9000, 27000)
    assert [(line.play_elapsed_ms, line.started_hours, line.charge_minor, line.interval_count) for line in lines] == [
        (3_600_000, 1, 3000, 2),
        (3_600_001, 2, 6000, 1),
    ]
    settled_revision = int(settlement.participant_revision)
    await session.execute(text(
        "ALTER TABLE gaming_sessions DISABLE TRIGGER trg_gaming_sessions_participant_revision"
    ))
    await session.execute(
        text("UPDATE gaming_sessions SET participant_revision=0 WHERE id=:id"),
        {"id": session_id},
    )
    await session.execute(text(
        "ALTER TABLE gaming_sessions ENABLE TRIGGER trg_gaming_sessions_participant_revision"
    ))
    await session.commit()
    reset_cancel = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/cancel",
        json={"reason": "Revision reset must not hide participant evidence"},
        headers=_headers(seed_owner, token),
    )
    assert reset_cancel.status_code == 409
    assert reset_cancel.json()["error"]["code"] == "gaming_billing_repair_required"
    reset_repair = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/repair-billing",
        json={
            "expected_amount_minor": 27000,
            "amount_minor": 27000,
            "reason": "Revision reset must not hide participant evidence",
        },
        headers=_headers(
            seed_owner,
            issue_access_token(
                user_id=seed_owner["owner"].id,
                company_id=seed_owner["company"].id,
                branch_id=seed_owner["branch"].id,
                roles=["super_owner"],
                auth_version=seed_owner["owner"].auth_version,
            ),
            f"repair:{uuid4()}",
        ),
    )
    assert reset_repair.status_code == 409
    assert reset_repair.json()["error"]["code"] == "gaming_billing_repair_required"
    reset_pos = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/send-to-pos",
        headers=_headers(seed_owner, token, f"pos:{uuid4()}"),
    )
    assert reset_pos.status_code == 409
    assert reset_pos.json()["error"]["code"] == "gaming_billing_repair_required"
    await session.execute(
        text("UPDATE gaming_sessions SET participant_revision=:revision WHERE id=:id"),
        {"revision": settled_revision, "id": session_id},
    )
    await session.commit()
    with pytest.raises(DBAPIError, match="immutable"):
        await session.execute(
            text("UPDATE gaming_participant_settlements SET guest_charge_minor=0 WHERE id=:id"),
            {"id": settlement.id},
        )
    await session.rollback()
    closed_interval = (await session.execute(select(GamingSessionParticipant).where(
        GamingSessionParticipant.gaming_session_id == session_id
    ).limit(1))).scalar_one()
    with pytest.raises(DBAPIError, match="only one complete leave transition"):
        await session.execute(
            text("UPDATE gaming_session_participants SET left_play_elapsed_ms=left_play_elapsed_ms+1 WHERE id=:id"),
            {"id": closed_interval.id},
        )
    await session.rollback()

    sent = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/send-to-pos",
        headers=_headers(seed_owner, token, f"pos:{uuid4()}"),
    )
    assert sent.status_code == 201, sent.text
    order = await client.get(f"/api/v1/pos/orders/{sent.json()['order_id']}", headers=_headers(seed_owner, token))
    assert order.status_code == 200, order.text
    assert order.json()["discount_minor"] == 2700
    assert order.json()["total_minor"] == 24300
    assert order.json()["lines"][0]["line_total_minor"] == 24300
    description = order.json()["lines"][0]["note"]
    assert "base + extensions 180.00" in description
    assert "friend controller surcharge 90.00" in description

    claim = await client.post(
        f"/api/v1/pos/orders/{sent.json()['order_id']}/checkout-claim",
        headers=_headers(seed_owner, token),
    )
    assert claim.status_code == 201, claim.text
    paid = await client.post(
        f"/api/v1/pos/orders/{sent.json()['order_id']}/payments",
        json={"method": "upi", "amount_minor": 24300,
              "expected_order_total_minor": 24300, "expected_due_minor": 24300,
              "ref_external": f"UPI-{uuid4().hex[:8]}"},
        headers={**_headers(seed_owner, token, f"pay:{uuid4()}"), "X-Checkout-Claim": claim.json()["claim_token"]},
    )
    assert paid.status_code == 201, paid.text
    assert paid.json()["bill_amount_minor"] == 24300
    playtime = await client.get(
        f"/api/v1/customers/{friend_ids[0]}/playtime",
        headers=_headers(seed_owner, token),
    )
    assert playtime.status_code == 200, playtime.text
    assert playtime.json()["total_played_minutes"] == 60
    assert playtime.json()["qualifying_paid_minutes"] == 0
    assert playtime.json()["draft_estimated_reward_minutes"] == 0
    assert playtime.json()["history"][0]["qualification_status"] == "participant_non_qualifying"
    # Stop replay validates persisted financial/timing evidence. Runtime UI
    # flags and nullable catalog references may legitimately change later.
    monkeypatch.setattr(get_settings(), "gaming_pause_enabled", True)
    ended_session = await session.get(GamingSession, session_id)
    ended_session.package_id = None
    await session.commit()
    post_pos_stop_replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"ended_at": stop_at.isoformat(), "expected_participant_revision": 6},
        headers=_offline_headers(seed_owner, token, stop_key, stop_at),
    )
    assert post_pos_stop_replay.status_code == 200, post_pos_stop_replay.text
    assert post_pos_stop_replay.json()["order_id"] == sent.json()["order_id"]
    assert post_pos_stop_replay.json()["pause_available"] is True
    assert post_pos_stop_replay.json()["package_id"] is None
    await session.execute(
        text("UPDATE gaming_sessions SET amount_minor=amount_minor+1 WHERE id=:id"),
        {"id": session_id},
    )
    await session.commit()
    tampered_stop_replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"ended_at": stop_at.isoformat(), "expected_participant_revision": 6},
        headers=_offline_headers(seed_owner, token, stop_key, stop_at),
    )
    assert tampered_stop_replay.status_code == 409
    assert tampered_stop_replay.json()["error"]["code"] == "gaming_billing_repair_required"


@pytest.mark.integration
@pytest.mark.asyncio
async def test_capacity_revision_replay_cross_user_and_zero_duration_minimum(
    client, session, seed_owner,
) -> None:
    token, shift_id, station, friends, packages = await _setup(client, session, seed_owner)
    started = await _start(client, seed_owner, token, shift_id, station, packages["standard-single-session-30m"])
    session_id = started["id"]
    company_id = str(seed_owner["company"].id)
    payload_base = {"customer_directory_revision": 0, "customer_directory_company_id": company_id}
    async def concurrent_join(friend):
        return await client.post(
            f"/api/v1/gaming/sessions/{session_id}/participants/join",
            json={"expected_participant_revision": 0, "customer_id": str(friend.id), **payload_base},
            headers=_headers(seed_owner, token, f"join:{uuid4()}"),
        )
    raced = await asyncio.gather(concurrent_join(friends[0]), concurrent_join(friends[1]))
    assert sorted(response.status_code for response in raced) == [200, 409]
    state = next(response.json() for response in raced if response.status_code == 200)
    states = [state]
    winner_id = UUID(state["participants"][0]["customer_id"])
    remaining = [friend for friend in friends if friend.id != winner_id]
    for revision, friend in enumerate(remaining[:2], start=1):
        response = await client.post(
            f"/api/v1/gaming/sessions/{session_id}/participants/join",
            json={"expected_participant_revision": revision, "customer_id": str(friend.id), **payload_base},
            headers=_headers(seed_owner, token, f"join:{uuid4()}"),
        )
        assert response.status_code == 200, response.text
        states.append(response.json())
    assert states[-1]["current_player_count"] == 4
    full = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/join",
        json={"expected_participant_revision": 3, "customer_id": str(remaining[2].id), **payload_base},
        headers=_headers(seed_owner, token, f"join:{uuid4()}"),
    )
    assert full.status_code == 422

    owner_role = (await session.execute(select(Role).where(
        Role.company_id == seed_owner["company"].id, Role.code == "owner"
    ))).scalar_one()
    second = User(
        id=uuid4(), company_id=seed_owner["company"].id,
        email=f"second-{uuid4().hex[:8]}@test.local", name="Second owner",
        password_hash=hash_password("password1234"), status="active",
    )
    session.add(second)
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=second.id, role_id=owner_role.id, branch_id=seed_owner["branch"].id, granted_by=seed_owner["owner"].id))
    await session.commit()
    await session.refresh(second)
    second_token = await _login(client, second.email, "password1234")
    interval = states[-1]["participants"][0]
    leave_key = f"leave:{uuid4()}"
    left = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/{interval['id']}/leave",
        json={"expected_participant_revision": 3},
        headers=_headers(seed_owner, second_token, leave_key),
    )
    assert left.status_code == 200, left.text
    assert left.json()["participant_revision"] == 4

    await session.execute(text("DELETE FROM idempotency_keys WHERE key = :key"), {"key": leave_key})
    await session.commit()
    durable_replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/{interval['id']}/leave",
        json={"expected_participant_revision": 3},
        headers=_headers(seed_owner, second_token, leave_key),
    )
    assert durable_replay.status_code == 200
    assert durable_replay.json() == left.json()
    cached_after_durable_replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/{interval['id']}/leave",
        json={"expected_participant_revision": 3},
        headers=_headers(seed_owner, second_token, leave_key),
    )
    assert cached_after_durable_replay.status_code == 200
    assert cached_after_durable_replay.json() == left.json()

    missing_revision = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop", json={},
        headers=_headers(seed_owner, token, f"stop:{uuid4()}"),
    )
    assert missing_revision.status_code == 409
    stop_key = "s" * 150 + uuid4().hex[:10]
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"expected_participant_revision": 4},
        headers=_headers(seed_owner, token, stop_key),
    )
    assert stopped.status_code == 200, stopped.text
    # Three friends each incur the ₹30 minimum, including the zero-duration leave.
    assert stopped.json()["amount_minor"] == 17000

    second_station = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        code=f"P-{uuid4().hex[:8]}",
        name="Upfront controller capacity",
        type="ps5",
        rate_per_hour_minor=15000,
        is_active=True,
        tax_rate=0.18,
        sac_code="999692",
        rate_includes_tax=True,
    )
    session.add(second_station)
    await session.commit()
    upfront = await _start(
        client,
        seed_owner,
        token,
        shift_id,
        second_station,
        packages["standard-dual-session-30m"],
        player_count=3,
    )
    assert upfront["extra_controllers"] == 1
    assert upfront["amount_minor"] == 13000
    joined = await client.post(
        f"/api/v1/gaming/sessions/{upfront['id']}/participants/join",
        json={"expected_participant_revision": 0, "customer_id": str(friends[3].id), **payload_base},
        headers=_headers(seed_owner, token, f"join:{uuid4()}"),
    )
    assert joined.status_code == 200, joined.text
    assert joined.json()["current_player_count"] == 4
    blocked = await client.post(
        f"/api/v1/gaming/sessions/{upfront['id']}/participants/join",
        json={"expected_participant_revision": 1, "customer_id": str(friends[0].id), **payload_base},
        headers=_headers(seed_owner, token, f"join:{uuid4()}"),
    )
    assert blocked.status_code == 422
    upfront_stop_at = datetime.now(UTC)
    upfront_stop_key = f"stop:{uuid4()}"
    settled = await client.post(
        f"/api/v1/gaming/sessions/{upfront['id']}/stop",
        json={
            "ended_at": upfront_stop_at.isoformat(),
            "expected_participant_revision": 1,
        },
        headers=_offline_headers(seed_owner, token, upfront_stop_key, upfront_stop_at),
    )
    assert settled.status_code == 200, settled.text
    # ₹130 includes the original upfront controller once; only the late friend adds ₹30.
    assert settled.json()["amount_minor"] == 16000
    auto_closed = (await session.execute(select(GamingSessionParticipant).where(
        GamingSessionParticipant.gaming_session_id == UUID(upfront["id"]),
    ))).scalar_one()
    assert auto_closed.leave_timing_source == "offline_capture"

    with pytest.raises(DBAPIError, match="participant revision cannot decrease"):
        await session.execute(
            text("UPDATE gaming_sessions SET participant_revision=0 WHERE id=:id"),
            {"id": UUID(upfront["id"])},
        )
    await session.rollback()
    await session.execute(text(
        "ALTER TABLE gaming_sessions DISABLE TRIGGER trg_gaming_sessions_participant_revision"
    ))
    await session.execute(
        text("UPDATE gaming_sessions SET participant_revision=0 WHERE id=:id"),
        {"id": UUID(upfront["id"])},
    )
    await session.execute(text(
        "ALTER TABLE gaming_sessions ENABLE TRIGGER trg_gaming_sessions_participant_revision"
    ))
    await session.commit()
    reset_stop = await client.post(
        f"/api/v1/gaming/sessions/{upfront['id']}/stop",
        json={
            "ended_at": upfront_stop_at.isoformat(),
            "expected_participant_revision": 0,
        },
        headers=_offline_headers(seed_owner, token, f"stop:{uuid4()}", upfront_stop_at),
    )
    assert reset_stop.status_code == 409
    assert reset_stop.json()["error"]["code"] == "gaming_billing_repair_required"
    await session.execute(
        text("UPDATE gaming_sessions SET participant_revision=:revision WHERE id=:id"),
        {"revision": settled.json()["participant_revision"], "id": UUID(upfront["id"])},
    )
    await session.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_paused_friend_can_leave_but_new_friend_cannot_join(
    client, session, seed_owner, monkeypatch,
) -> None:
    monkeypatch.setattr(get_settings(), "gaming_pause_enabled", True)
    token, shift_id, station, friends, packages = await _setup(client, session, seed_owner)
    started = await _start(client, seed_owner, token, shift_id, station, packages["standard-single-session-30m"])
    session_id = started["id"]
    directory = {
        "customer_directory_revision": 0,
        "customer_directory_company_id": str(seed_owner["company"].id),
    }
    join_key = f"join:{uuid4()}"
    join_payload = {
        "expected_participant_revision": 0,
        "customer_name": "New saved friend",
        "customer_phone": "9876543209",
        **directory,
    }
    joined = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/join",
        json=join_payload,
        headers=_headers(seed_owner, token, join_key),
    )
    assert joined.status_code == 200, joined.text
    await session.execute(text("DELETE FROM idempotency_keys WHERE key = :key"), {"key": join_key})
    await session.commit()
    durable_join_replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/join",
        json=join_payload,
        headers=_headers(seed_owner, token, join_key),
    )
    assert durable_join_replay.status_code == 200
    assert durable_join_replay.json() == joined.json()
    cached_after_durable_join = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/join",
        json=join_payload,
        headers=_headers(seed_owner, token, join_key),
    )
    assert cached_after_durable_join.status_code == 200
    assert cached_after_durable_join.json() == joined.json()
    participant = joined.json()["participants"][0]
    paused = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/pause",
        json={"reason": "Friend break", "expected_pause_version": 0},
        headers=_headers(seed_owner, token, f"pause:{uuid4()}"),
    )
    assert paused.status_code == 200, paused.text
    rejected = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/join",
        json={"expected_participant_revision": 1, "customer_id": str(friends[1].id), **directory},
        headers=_headers(seed_owner, token, f"join:{uuid4()}"),
    )
    assert rejected.status_code == 422
    left = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/participants/{participant['id']}/leave",
        json={"expected_participant_revision": 1},
        headers=_headers(seed_owner, token, f"leave:{uuid4()}"),
    )
    assert left.status_code == 200, left.text
    assert left.json()["active_friend_count"] == 0
    assert left.json()["participants"][0]["left_play_elapsed_ms"] >= participant["joined_play_elapsed_ms"]
    stop_key = f"stop:{uuid4()}"
    stopped = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"expected_participant_revision": 2},
        headers=_headers(seed_owner, token, stop_key),
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["amount_minor"] == 11000
    replay = await client.post(
        f"/api/v1/gaming/sessions/{session_id}/stop",
        json={"expected_participant_revision": 2},
        headers=_headers(seed_owner, token, stop_key),
    )
    assert replay.status_code == 200, replay.text
    assert replay.json() == stopped.json()
