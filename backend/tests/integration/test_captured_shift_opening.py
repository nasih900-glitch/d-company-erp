"""Captured shift time, durable replay, concurrency and chronology proof."""

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4
import pytest
from sqlalchemy import func, select, text
from sqlalchemy.exc import DBAPIError
from app.core.security import hash_password, issue_access_token
from app.models import AuditLog, Role, Shift, Station, User, UserRole
from app.services.audit.recorder import install_audit_listeners


def headers(seed, captured, key=None):
    user = seed["owner"]
    key = key or f"shift-open:{uuid4()}"
    token = issue_access_token(
        user_id=user.id,
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        roles=["owner"],
        auth_version=user.auth_version,
    )
    return {
        "Authorization": "Bearer " + token,
        "X-Terminal-Id": str(seed["terminal"].id),
        "Idempotency-Key": key,
        "X-Client-Action-Id": key,
        "X-Offline-Captured": "true",
        "X-Client-Occurred-At": captured.isoformat(),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "24",
        "X-Installation-Id": str(uuid4()),
    }


@pytest.mark.asyncio
async def test_captured_shift_drives_offline_session_chain_and_durable_replay(
    client, session, seed_owner
):
    install_audit_listeners()
    captured = datetime.now(UTC) - timedelta(minutes=15)
    h = headers(seed_owner, captured)
    response = await client.post(
        "/api/v1/pos/shifts/open", json={"opening_float_minor": 50000}, headers=h
    )
    assert response.status_code == 201, response.text
    shift = await session.get(Shift, UUID(response.json()["id"]))
    assert shift.opened_at == captured
    assert shift.opening_received_at > captured + timedelta(minutes=14)
    assert shift.opening_action_id == h["Idempotency-Key"] and shift.opening_was_offline
    assert shift.opening_protocol_revision == 1
    assert shift.opening_client_platform == "android"
    receipt = await client.post(
        "/api/v1/pos/shifts/open", json={"opening_float_minor": 50000}, headers=h
    )
    assert receipt.json() == response.json()
    listed = await client.get("/api/v1/pos/shifts?only_open=true", headers=h)
    assert listed.status_code == 200, listed.text
    assert listed.json()[0]["opening_receipt_recorded"] is True
    assert listed.json()[0]["opening_protocol_revision"] == 1
    station = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        code=f"OFF-{uuid4().hex[:8]}",
        name="Offline VR",
        type="vr",
        rate_per_hour_minor=6000,
        tax_rate=0,
    )
    session.add(station)
    await session.commit()
    start = captured + timedelta(seconds=30)
    game = await client.post(
        "/api/v1/gaming/sessions/start",
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "started_at": start.isoformat(),
            "expected_rate_per_hour_minor": 6000,
        },
        headers=headers(seed_owner, start),
    )
    assert game.status_code == 201, game.text
    end = start + timedelta(minutes=5)
    stopped = await client.post(
        "/api/v1/gaming/sessions/" + game.json()["id"] + "/stop",
        json={"ended_at": end.isoformat()},
        headers=headers(seed_owner, end),
    )
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()["billable_minutes"] == 5 and stopped.json()["amount_minor"] == 500
    audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.entity_type == "Shift",
                AuditLog.entity_id == str(shift.id),
                AuditLog.action == "create",
            )
        )
    ).scalar_one()
    assert audit.client_reported_at == captured and audit.client_was_offline


@pytest.mark.asyncio
@pytest.mark.parametrize("offset", [timedelta(seconds=2)])
async def test_invalid_captured_clock_keeps_action_unwritten(client, session, seed_owner, offset):
    response = await client.post(
        "/api/v1/pos/shifts/open", json={}, headers=headers(seed_owner, datetime.now(UTC) + offset)
    )
    assert response.status_code == 422, response.text
    assert "nothing was discarded" in response.text.lower()
    assert (
        await session.execute(
            select(func.count())
            .select_from(Shift)
            .where(Shift.company_id == seed_owner["company"].id)
        )
    ).scalar_one() == 0


@pytest.mark.asyncio
async def test_long_offline_shift_remains_recoverable_and_replays_exactly(
    client, session, seed_owner
):
    """Age alone must never strand the parent of queued financial records."""
    captured = datetime.now(UTC) - timedelta(days=3, minutes=7)
    h = headers(seed_owner, captured)
    opened = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 12_300},
        headers=h,
    )
    assert opened.status_code == 201, opened.text
    shift = await session.get(Shift, UUID(opened.json()["id"]))
    assert shift is not None
    assert shift.opened_at == captured
    assert shift.opening_was_offline is True

    replay = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 12_300},
        headers=h,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == opened.json()


@pytest.mark.asyncio
async def test_pre_0069_open_shift_is_reported_as_legacy_receipt(client, session, seed_owner):
    """An open shift may span the 0069 deploy and still need lost-response recovery."""
    seed = seed_owner
    legacy_id = uuid4()
    # Explicit NULLs reproduce a row migrated by 0069. Model/DB defaults mark
    # every ordinary insert after 0069 as protocol revision 1.
    await session.execute(
        Shift.__table__.insert().values(
            id=legacy_id,
            company_id=seed["company"].id,
            branch_id=seed["branch"].id,
            terminal_id=seed["terminal"].id,
            opened_by=seed["owner"].id,
            opened_at=datetime.now(UTC) - timedelta(hours=1),
            opening_action_id=None,
            opening_request_hash=None,
            opening_received_at=None,
            opening_was_offline=False,
            opening_protocol_revision=None,
            opening_client_platform=None,
            opening_float_minor=50_000,
            expected_minor=50_000,
            status="open",
        )
    )
    await session.commit()

    listed = await client.get(
        "/api/v1/pos/shifts?only_open=true",
        headers=headers(seed, datetime.now(UTC)),
    )
    assert listed.status_code == 200, listed.text
    assert len(listed.json()) == 1
    assert listed.json()[0]["id"] == str(legacy_id)
    assert listed.json()[0]["opening_receipt_recorded"] is False
    assert listed.json()[0]["opening_protocol_revision"] is None


@pytest.mark.asyncio
async def test_unkeyed_web_open_is_new_protocol_not_legacy(client, session, seed_owner):
    """Key absence must not make a post-0069 web shift eligible for matching."""
    user = seed_owner["owner"]
    token = issue_access_token(
        user_id=user.id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        roles=["owner"],
        auth_version=user.auth_version,
    )
    live_headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }
    opened = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 4_000},
        headers=live_headers,
    )
    assert opened.status_code == 201, opened.text
    shift = await session.get(Shift, UUID(opened.json()["id"]))
    assert shift is not None
    assert shift.opening_action_id is None
    assert shift.opening_protocol_revision == 1
    assert shift.opening_client_platform == "web"

    listed = await client.get("/api/v1/pos/shifts?only_open=true", headers=live_headers)
    assert listed.status_code == 200, listed.text
    assert listed.json()[0]["opening_receipt_recorded"] is False
    assert listed.json()[0]["opening_protocol_revision"] == 1


@pytest.mark.asyncio
async def test_captured_identity_is_scoped_and_closed_receipt_cannot_reopen(
    client, session, seed_owner
):
    captured = datetime.now(UTC) - timedelta(minutes=1)
    h = headers(seed_owner, captured)
    opened = await client.post("/api/v1/pos/shifts/open", json={}, headers=h)
    assert opened.status_code == 201, opened.text
    changed = await client.post(
        "/api/v1/pos/shifts/open",
        json={},
        headers=h | {"X-Client-Occurred-At": (captured + timedelta(seconds=1)).isoformat()},
    )
    assert changed.status_code == 409, changed.text
    closed = await client.post(
        "/api/v1/pos/shifts/" + opened.json()["id"] + "/close",
        json={"counted_minor": 0},
        headers=h
        | {
            "Idempotency-Key": h["Idempotency-Key"].replace(
                "shift-open:", "shift-close:", 1
            )
        },
    )
    assert closed.status_code == 200, closed.text
    replay = await client.post("/api/v1/pos/shifts/open", json={}, headers=h)
    assert (
        replay.status_code == 409
        and replay.json()["error"]["details"]["issue"] == "saved_shift_already_closed"
    )
    overlap = await client.post(
        "/api/v1/pos/shifts/open",
        json={},
        headers=headers(seed_owner, captured + timedelta(seconds=10)),
    )
    assert (
        overlap.status_code == 422
        and overlap.json()["error"]["details"]["issue"] == "saved_shift_overlaps_closed_shift"
    )
    assert (
        await session.execute(
            select(func.count())
            .select_from(Shift)
            .where(Shift.company_id == seed_owner["company"].id)
        )
    ).scalar_one() == 1


@pytest.mark.asyncio
async def test_two_different_offline_openings_never_merge(client, session, seed_owner):
    captured = datetime.now(UTC) - timedelta(seconds=10)
    responses = await asyncio.gather(
        *[
            client.post("/api/v1/pos/shifts/open", json={}, headers=headers(seed_owner, captured))
            for _ in range(2)
        ]
    )
    assert sorted(r.status_code for r in responses) == [201, 422]
    assert (
        await session.execute(
            select(func.count())
            .select_from(Shift)
            .where(Shift.company_id == seed_owner["company"].id)
        )
    ).scalar_one() == 1


@pytest.mark.asyncio
async def test_provenance_and_immutable_receipt_are_enforced(client, session, seed_owner):
    h = headers(seed_owner, datetime.now(UTC) - timedelta(seconds=10))
    for override in (
        {"X-Client-Action-Id": "different"},
        {"X-Client-Occurred-At": "not-a-time"},
        {"X-Client-Occurred-At": "2026-09-05T00:00:00"},
    ):
        r = await client.post("/api/v1/pos/shifts/open", json={}, headers=h | override)
        assert r.status_code == 422, r.text
    r = await client.post("/api/v1/pos/shifts/open", json={}, headers=h)
    assert r.status_code == 201, r.text
    with pytest.raises(DBAPIError, match="shift opening receipt is immutable"):
        await session.execute(
            text("UPDATE shifts SET opened_at=opened_at+interval '1 second' WHERE id=:id"),
            {"id": r.json()["id"]},
        )
        await session.commit()
    await session.rollback()


@pytest.mark.asyncio
async def test_verified_terminal_advertises_offline_capture(client, seed_owner):
    response = await client.get(
        "/api/v1/settings/terminals", headers=headers(seed_owner, datetime.now(UTC))
    )
    assert response.status_code == 200, response.text
    assert (
        next(t for t in response.json() if t["id"] == str(seed_owner["terminal"].id))[
            "offline_shift_capture_supported"
        ]
        is True
    )


@pytest.mark.asyncio
async def test_android_open_cannot_be_closed_remotely_but_same_tablet_staff_can(
    client, session, seed_owner
):
    """A lost open response cannot let web close strand tablet dependents."""
    captured = datetime.now(UTC) - timedelta(seconds=15)
    opening_headers = headers(seed_owner, captured)
    opened = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 5_000},
        headers=opening_headers,
    )
    assert opened.status_code == 201, opened.text

    owner_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == seed_owner["company"].id,
                Role.code == "owner",
            )
        )
    ).scalar_one()
    colleague = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"shift-recovery-{uuid4().hex[:8]}@test.local",
        name="Sameer",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add(colleague)
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=colleague.id,
            role_id=owner_role.id,
            branch_id=seed_owner["branch"].id,
            granted_by=seed_owner["owner"].id,
        )
    )
    await session.commit()
    # Creating a role assignment increments ``auth_version`` through the
    # database trigger. Refresh before minting the colleague token so this
    # fixture represents a current authenticated session rather than an
    # intentionally revoked one.
    await session.refresh(colleague)
    colleague_token = issue_access_token(
        user_id=colleague.id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        roles=["owner"],
        auth_version=colleague.auth_version,
        extra={"protected_access": False, "audit_access": False},
    )
    base = {
        "Authorization": f"Bearer {colleague_token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }

    # Simulates the browser/other-device close while the originating tablet
    # still has only its local shift id and may already have captured work.
    remote = await client.post(
        f"/api/v1/pos/shifts/{opened.json()['id']}/close",
        json={"counted_minor": 5_000},
        headers=base,
    )
    assert remote.status_code == 422, remote.text
    assert (
        remote.json()["error"]["details"]["issue"]
        == "android_shift_close_requires_origin_tablet"
    )
    saved = await session.get(Shift, UUID(opened.json()["id"]))
    assert saved is not None and saved.status == "open" and saved.closed_by is None

    # A clean account switch purges user-scoped Room rows. The newly signed-in
    # colleague therefore adopts the server shift under a different local
    # close identity, while the stable app-installation UUID proves this is
    # still the originating physical app installation.
    adopted_close_key = f"shift-close:{uuid4()}"
    same_tablet = await client.post(
        f"/api/v1/pos/shifts/{opened.json()['id']}/close",
        json={"counted_minor": 5_000},
        headers=base
        | {
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "24",
            "X-Installation-Id": opening_headers["X-Installation-Id"],
            "Idempotency-Key": adopted_close_key,
            "X-Client-Action-Id": adopted_close_key,
            "X-Offline-Captured": "true",
            "X-Client-Occurred-At": datetime.now(UTC).isoformat(),
        },
    )
    assert same_tablet.status_code == 200, same_tablet.text
    assert same_tablet.json()["closed_by"] == str(colleague.id)
    assert same_tablet.json()["closed_by_was_opener"] is False


@pytest.mark.asyncio
async def test_code21_android_shift_without_installation_retains_causal_close(
    client, session, seed_owner
):
    """Code21 rows remain closable while the no-open-shift rollout gate drains."""
    opening_headers = headers(seed_owner, datetime.now(UTC) - timedelta(seconds=10))
    opening_headers.pop("X-Installation-Id")
    opening_headers["X-Client-Version-Code"] = "21"
    opened = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 3_000},
        headers=opening_headers,
    )
    assert opened.status_code == 201, opened.text
    shift = await session.get(Shift, UUID(opened.json()["id"]))
    assert shift is not None
    assert shift.opening_client_installation_id is None

    causal_close_key = opening_headers["Idempotency-Key"].replace(
        "shift-open:", "shift-close:", 1
    )
    closed = await client.post(
        f"/api/v1/pos/shifts/{opened.json()['id']}/close",
        json={"counted_minor": 3_000},
        headers=opening_headers
        | {
            "Idempotency-Key": causal_close_key,
            "X-Client-Action-Id": causal_close_key,
        },
    )
    assert closed.status_code == 200, closed.text
    assert closed.json()["status"] == "closed"


@pytest.mark.asyncio
@pytest.mark.parametrize("installation_id", [None, "not-a-uuid"])
async def test_code24_android_open_requires_canonical_installation_identity(
    client, session, seed_owner, installation_id
):
    opening_headers = headers(seed_owner, datetime.now(UTC) - timedelta(seconds=5))
    if installation_id is None:
        opening_headers.pop("X-Installation-Id")
    else:
        opening_headers["X-Installation-Id"] = installation_id
    response = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 2_000},
        headers=opening_headers,
    )
    assert response.status_code == 422, response.text
    assert (
        await session.execute(
            select(func.count(Shift.id)).where(
                Shift.company_id == seed_owner["company"].id
            )
        )
    ).scalar_one() == 0


@pytest.mark.asyncio
async def test_protected_owner_can_auditably_recover_quarantined_android_shift(
    client, session, seed_owner
):
    """The browser escape hatch is explicit, idempotent and audit-owner-only."""
    install_audit_listeners()
    opening_headers = headers(seed_owner, datetime.now(UTC) - timedelta(seconds=20))
    opened = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 7_500},
        headers=opening_headers,
    )
    assert opened.status_code == 201, opened.text
    shift_id = opened.json()["id"]
    recovery_key = f"shift-recovery-close:{uuid4()}"
    recovery_payload = {
        "counted_minor": 7_500,
        "reason": "Origin tablet was isolated after its retained close could not sync.",
        "acknowledge_origin_tablet_quarantined": True,
    }

    # An ordinary owner still has operational close access, but never this
    # audit-only last-resort path.
    denied = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/recover-close",
        json=recovery_payload,
        headers=opening_headers
        | {
            "Idempotency-Key": recovery_key,
            "X-Client-Action-Id": recovery_key,
        },
    )
    assert denied.status_code == 403, denied.text

    owner = seed_owner["owner"]
    protected_token = issue_access_token(
        user_id=owner.id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        roles=["super_owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    protected_headers = {
        "Authorization": f"Bearer {protected_token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "Idempotency-Key": recovery_key,
        "X-Client-Action-Id": recovery_key,
    }
    recovered = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/recover-close",
        json=recovery_payload,
        headers=protected_headers,
    )
    assert recovered.status_code == 200, recovered.text
    assert recovered.json()["status"] == "closed"
    assert recovered.json()["recovery_close"] is True

    # Exact replay returns the stored receipt without adding another recovery
    # audit event or trying to close the row a second time.
    replay = await client.post(
        f"/api/v1/pos/shifts/{shift_id}/recover-close",
        json=recovery_payload,
        headers=protected_headers,
    )
    assert replay.status_code == 200 and replay.json() == recovered.json()
    audits = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.entity_type == "Shift",
                AuditLog.entity_id == shift_id,
                AuditLog.action == "shift_android_origin_recovery_close",
            )
        )
    ).scalars().all()
    assert len(audits) == 1
    assert audits[0].actor_user_id == owner.id
    assert audits[0].reason == recovery_payload["reason"]
    assert audits[0].after["server_blockers_checked"] is True
    assert audits[0].after["origin_tablet_quarantined"] is True
