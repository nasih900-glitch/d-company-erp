"""Recorded till responsibility permits only the existing scoped unlinked Gaming Void."""

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select

from app.api.v1.gaming import router as gaming
from app.models import AuditLog, GamingPackage, Shift, Station
from tests.integration.test_shared_terminal_attendance import PASSWORD, write
from tests.integration.test_shared_terminal_attendance import shared_world as shared_world

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


async def login(client, w, user):
    result = await client.post(
        "/api/v1/auth/login", json={"email": user.email, "password": PASSWORD}
    )
    assert result.status_code == 200, result.text
    return {
        "Authorization": "Bearer " + result.json()["access_token"],
        "X-Terminal-Id": str(w["terminal"].id),
    }


async def handover(client, w, headers=None, target=None, revision=0):
    result = await client.post(
        f"/api/v1/pos/shifts/{w['shift'].id}/operator-handovers",
        headers=write(headers or w["headers"]),
        json={
            "incoming_user_id": str((target or w["b"]).id),
            "password": PASSWORD,
            "expected_revision": revision,
            "approved": True,
        },
    )
    assert result.status_code == 201, result.text
    return result.json()


@pytest_asyncio.fixture
async def game_world(client, session, shared_world, monkeypatch):  # noqa: F811
    w = shared_world
    w["company"].gst_registration_type = "unregistered"
    w["company"].is_composition = False
    w["branch"].state_code = "32"
    t0 = datetime.now(UTC)
    clock = {"now": t0}

    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return clock["now"]

    monkeypatch.setattr(gaming, "datetime", Clock)
    shift = Shift(
        id=uuid4(),
        company_id=w["company"].id,
        branch_id=w["branch"].id,
        terminal_id=w["terminal"].id,
        opened_by=w["a"].id,
        opened_at=t0,
        opening_float_minor=1100,
        expected_minor=2200,
        status="open",
    )
    station = Station(
        id=uuid4(),
        company_id=w["company"].id,
        branch_id=w["branch"].id,
        code=f"HANDOVER-{uuid4().hex[:8]}",
        name="Synthetic handover station",
        type="vr",
        rate_per_hour_minor=12000,
        tax_rate=0,
    )
    package = GamingPackage(
        id=uuid4(),
        company_id=w["company"].id,
        branch_id=w["branch"].id,
        code=f"HANDOVER-{uuid4().hex[:8]}",
        station_type="vr",
        variant="games",
        kind="base",
        name="Synthetic sixty minutes",
        duration_minutes=60,
        price_minor=12000,
    )
    session.add_all([shift, station, package])
    await session.commit()
    started = await client.post(
        "/api/v1/gaming/sessions/start",
        headers=write(w["headers"]),
        json={
            "station_id": str(station.id),
            "shift_id": str(shift.id),
            "package_id": str(package.id),
            "expected_package_price_minor": 12000,
            "expected_package_duration_minutes": 60,
            "expected_package_variant": "games",
        },
    )
    assert started.status_code == 201, started.text
    return {
        **w,
        "shift": shift,
        "station": station,
        "sid": started.json()["id"],
        "clock": clock,
        "t0": t0,
    }


@pytest.mark.parametrize("seconds", [1799, 1800])
@pytest.mark.parametrize("recorded", [True, False])
async def test_incoming_staff_void_preserves_reason_boundary_and_audit(
    client,
    session,
    game_world,
    seconds,
    recorded,
):
    w = game_world
    receipt = await handover(client, w) if recorded else None
    bh = await login(client, w, w["b"])
    w["clock"]["now"] = w["t0"] + timedelta(seconds=seconds)
    path = f"/api/v1/gaming/sessions/{w['sid']}"
    targets = await client.get(path + "/void-retention-targets", headers=bh)
    key = str(uuid4())
    body = {"expected_billing_revision": 0}
    result = await client.post(path + "/cancel", headers=write(bh, key), json=body)
    if seconds >= 1800:
        assert result.status_code == 422, result.text
        assert result.json()["error"]["details"]["issue"] == "gaming_void_reason_required"
        current = (await client.get(path, headers=bh)).json()
        assert current["status"] == "active"
        assert current["cancelled_by"] is None
        body["reason"] = "Customer requested correction after thirty minutes"
        key = str(uuid4())
        result = await client.post(path + "/cancel", headers=write(bh, key), json=body)
    assert result.status_code == 200, result.text
    assert targets.status_code == 200, targets.text
    assert str(w["shift"].id) in {t["shift_id"] for t in targets.json()}
    voided = result.json()
    assert voided["cancelled_by"] == str(w["b"].id)
    assert voided["void_reason_required"] == (seconds >= 1800)
    assert voided["server_play_elapsed_ms"] == seconds * 1000
    replay = await client.post(path + "/cancel", headers=write(bh, key), json=body)
    assert replay.status_code == 200
    assert replay.json() == voided
    await session.refresh(w["shift"])
    assert w["shift"].opened_by == w["a"].id
    assert w["shift"].operator_user_id == (w["b"].id if recorded else None)
    assert w["shift"].operator_revision == (1 if recorded else None)
    assert (w["shift"].opening_float_minor, w["shift"].expected_minor) == (1100, 2200)
    audit = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == w["company"].id,
                    AuditLog.entity_id == w["sid"],
                    AuditLog.action == "gaming_session_voided",
                )
            )
        )
        .scalars()
        .one()
    )
    assert audit.actor_user_id == w["b"].id
    assert audit.terminal_id == w["terminal"].id
    if receipt is not None:
        assert receipt["cash_count_status"] == "NOT_COUNTED"


@pytest.mark.parametrize("case", ["stale", "closed", "linked"])
async def test_recorded_operator_exception_rejects_stale_closed_and_pos_linked(
    client,
    session,
    game_world,
    case,
):
    w = game_world
    bh = await login(client, w, w["b"])
    await handover(client, w)
    if case == "stale":
        await handover(client, w, headers=bh, target=w["a"], revision=1)
    elif case == "closed":
        w["shift"].status = "closed"
        w["shift"].closed_at = w["t0"]
        w["shift"].closed_by = w["a"].id
        await session.commit()
    path = f"/api/v1/gaming/sessions/{w['sid']}"
    body = {"reason": "Reviewed correction", "expected_billing_revision": 0}
    if case == "linked":
        w["clock"]["now"] = w["t0"] + timedelta(seconds=60)
        stopped = await client.post(path + "/stop", headers=write(bh), json={})
        assert stopped.status_code == 200, stopped.text
        sent = await client.post(path + "/send-to-pos", headers=write(bh))
        assert sent.status_code == 201, sent.text
        current = (await client.get(path, headers=bh)).json()
        assert current["order_id"] is not None
        body["expected_order_id"] = current["order_id"]
        body["expected_billing_revision"] = current["billing_revision"]
    denied = await client.post(path + "/cancel", headers=write(bh), json=body)
    assert denied.status_code == (403 if case == "linked" else 422), denied.text
    targets = await client.get(path + "/void-retention-targets", headers=bh)
    assert targets.status_code == (403 if case == "linked" else 422), targets.text
    current = (await client.get(path, headers=bh)).json()
    assert current["status"] != "cancelled"
    assert current["cancelled_by"] is None


async def test_unrelated_staff_and_wrong_workspace_cannot_use_recorded_operator_permission(
    client,
    session,
    game_world,
):
    from app.core.security import hash_password
    from app.models import User, UserRole

    w = game_world
    await handover(client, w)
    c = User(
        id=uuid4(),
        company_id=w["company"].id,
        email=f"c-{uuid4()}@example.test",
        name="Unrelated synthetic C",
        password_hash=hash_password(PASSWORD),
        status="active",
    )
    session.add(c)
    await session.flush()
    session.add(UserRole(user_id=c.id, role_id=w["role"].id, branch_id=w["branch"].id))
    await session.commit()
    ch = await login(client, w, c)
    bh = await login(client, w, w["b"])
    path = f"/api/v1/gaming/sessions/{w['sid']}"
    for headers, expected in ((ch, 422), ({**bh, "X-Terminal-Id": str(uuid4())}, 401)):
        result = await client.post(
            path + "/cancel", headers=write(headers), json={"expected_billing_revision": 0}
        )
        assert result.status_code == expected, result.text
        assert (
            await client.get(path + "/void-retention-targets", headers=headers)
        ).status_code == expected
    await session.refresh(w["shift"])
    assert w["shift"].operator_user_id == w["b"].id
    current = (await client.get(path, headers=bh)).json()
    assert current["status"] == "active"


async def test_recorded_operator_retains_consumed_addon_without_discarding_order_line(
    client,
    session,
    shared_world,  # noqa: F811 - imported pytest fixture
):
    from app.models import MenuItem, Order, OrderLine
    from tests.integration.test_gaming_session_addons import _seed_live_session

    w = shared_world
    shift, _station, drink, gs = await _seed_live_session(session, w, opener=w["a"])
    w = {**w, "shift": shift}
    added = await client.post(
        f"/api/v1/gaming/sessions/{gs.id}/addons",
        headers=write(w["headers"]),
        json={
            "client_line_id": str(uuid4()),
            "menu_item_id": str(drink.id),
            "qty": 1,
            "expected_unit_price_minor": 1249,
        },
    )
    assert added.status_code == 201, added.text
    await handover(client, w)
    bh = await login(client, w, w["b"])
    path = f"/api/v1/gaming/sessions/{gs.id}"
    targets = await client.get(path + "/void-retention-targets", headers=bh)
    assert targets.status_code == 200, targets.text
    assert str(shift.id) in {t["shift_id"] for t in targets.json()}
    result = await client.post(
        path + "/cancel",
        headers=write(bh),
        json={"retained_target_shift_id": str(shift.id), "expected_billing_revision": 0},
    )
    assert result.status_code == 200, result.text
    assert result.json()["cancelled_by"] == str(w["b"].id)
    retained = UUID(result.json()["retained_addon_order_id"])
    order = await session.get(Order, retained)
    assert order.status == "held"
    assert order.opened_by == w["b"].id
    lines = (
        (await session.execute(select(OrderLine).where(OrderLine.order_id == retained)))
        .scalars()
        .all()
    )
    assert len(lines) == 1
    assert lines[0].menu_item_id == drink.id
    assert lines[0].qty == 1
    assert lines[0].voided_at is None
    assert lines[0].line_total_minor == 1249
    assert await session.get(MenuItem, drink.id) is not None
    await session.refresh(shift)
    assert shift.opened_by == w["a"].id
    assert shift.operator_user_id == w["b"].id
