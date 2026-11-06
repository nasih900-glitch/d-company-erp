"""Online responsibility handover, real PostgreSQL chain/locking and legacy preservation."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select, text
from sqlalchemy.exc import IntegrityError

from app.api.v1.pos import operator_handover as route
from app.models import Attendance, AuthRefreshSession, Order, Shift, ShiftOperatorHandover, User
from tests.integration.test_shared_terminal_attendance import PASSWORD, write
from tests.integration.test_shared_terminal_attendance import shared_world as shared_world

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


@pytest.fixture(autouse=True)
def production_audit_listener():
    # ASGITransport skips lifespan; exercise the actual production audit hook.
    from app.services.audit.recorder import install_audit_listeners

    install_audit_listeners()


@pytest_asyncio.fixture
async def till(session, shared_world):  # noqa: F811 - imported pytest fixture
    w = shared_world
    shift = Shift(
        id=uuid4(),
        company_id=w["company"].id,
        branch_id=w["branch"].id,
        terminal_id=w["terminal"].id,
        opened_by=w["owner"].id,
        opened_at=datetime.now(UTC),
        opening_float_minor=1100,
        expected_minor=2200,
        status="open",
    )
    session.add(shift)
    await session.commit()
    return {**w, "shift": shift, "url": f"/api/v1/pos/shifts/{shift.id}"}


def payload(w, **changes):
    return {
        "incoming_user_id": str(w["b"].id),
        "password": PASSWORD,
        "expected_revision": 0,
        "approved": True,
        **changes,
    }


async def post(client, w, **changes):
    return await client.post(
        w["url"] + "/operator-handovers", headers=write(w["headers"]), json=payload(w, **changes)
    )


async def total(session, model, company):
    return (
        await session.execute(
            select(func.count()).select_from(model).where(model.company_id == company)
        )
    ).scalar_one()


async def test_candidates_are_paged_minimal_current_operator_scope(client, session, till):
    w = till
    endpoint = w["url"] + "/operator-candidates"
    keys = []
    found = {}
    cursor = None
    while True:
        params = {"limit": 1}
        if cursor is not None:
            params["after_id"] = cursor
        response = await client.get(endpoint, headers=w["headers"], params=params)
        assert response.status_code == 200, response.text
        page = response.json()
        assert set(page) == {"items", "next_cursor"}
        for item in page["items"]:
            assert set(item) == {"id", "name"}
            assert item["id"] not in found
            found[item["id"]] = item["name"]
        cursor = page["next_cursor"]
        if cursor is None:
            break
        assert cursor not in keys
        assert len(keys) < 10
        keys.append(cursor)
    assert found[str(w["b"].id)] == w["b"].name
    assert str(w["a"].id) not in found
    assert len(keys) >= 1

    assert (await post(client, w)).status_code == 201
    assert (await client.get(endpoint, headers=w["headers"])).status_code == 403
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 1


async def test_operator_state_reports_running_game_across_same_shift_handover(
    client, session, till
):
    from app.models import GamingSession, Station

    w = till
    endpoint = w["url"] + "/operator-state"
    empty = await client.get(endpoint, headers=w["headers"])
    assert empty.status_code == 200
    assert empty.json()["has_running_games"] is False
    station = Station(
        id=uuid4(), company_id=w["company"].id, branch_id=w["branch"].id,
        code=f"TRANSFER-{uuid4().hex[:8]}", name="Synthetic", type="vr",
        rate_per_hour_minor=12000, tax_rate=0,
    )
    session.add(station)
    await session.flush()
    game = GamingSession(
        id=uuid4(), company_id=w["company"].id, station_id=station.id, shift_id=w["shift"].id,
        opened_by=w["a"].id, start_at=datetime.now(UTC), rate_per_hour_minor=12000,
        status="active", billing_mode="hourly",
    )
    session.add(game)
    await session.commit()
    start = game.start_at
    response = await post(client, w)
    assert response.status_code == 201, response.text
    current = await client.get(endpoint, headers=w["headers"])
    assert current.status_code == 200
    assert current.json()["has_running_games"] is True
    assert str(game.id) not in current.text
    await session.refresh(game)
    assert game.status == "active"
    assert game.start_at == start
    assert game.opened_by == w["a"].id


async def test_legacy_untracked_handover_preserves_money_attendance_and_sessions(
    client, session, till
):
    w = till
    state = await client.get(w["url"] + "/operator-state", headers=w["headers"])
    assert state.status_code == 200
    assert state.json()["tracking"] == "untracked"
    assert state.json()["operator_user_id"] is None
    before = (
        (await session.execute(select(Shift.__table__).where(Shift.id == w["shift"].id)))
        .mappings()
        .one()
    )
    attendance = await total(session, Attendance, w["company"].id)
    families = list(
        (
            await session.execute(
                select(AuthRefreshSession.__table__).where(
                    AuthRefreshSession.company_id == w["company"].id
                )
            )
        ).all()
    )
    response = await post(client, w)
    assert response.status_code == 201, response.text
    receipt = response.json()
    assert receipt["previous_operator_user_id"] is None
    assert receipt["initiated_by_user_id"] == str(w["a"].id)
    assert receipt["incoming_user_id"] == str(w["b"].id)
    assert receipt["from_revision"] == 0
    assert receipt["to_revision"] == 1
    assert receipt["cash_count_status"] == "NOT_COUNTED"
    assert receipt["counted_cash_minor"] is None
    after = (
        (await session.execute(select(Shift.__table__).where(Shift.id == w["shift"].id)))
        .mappings()
        .one()
    )
    assert {
        k: v for k, v in after.items() if k not in ("operator_user_id", "operator_revision")
    } == {k: v for k, v in before.items() if k not in ("operator_user_id", "operator_revision")}
    assert after["operator_user_id"] == w["b"].id
    assert await total(session, Attendance, w["company"].id) == attendance
    assert (
        list(
            (
                await session.execute(
                    select(AuthRefreshSession.__table__).where(
                        AuthRefreshSession.company_id == w["company"].id
                    )
                )
            ).all()
        )
        == families
    )
    assert (await client.get("/api/v1/auth/me", headers=w["headers"])).json()["user_id"] == str(
        w["a"].id
    )


async def test_lost_response_lookup_replay_reverify_and_changed_operator_revision(
    client, session, till
):
    w = till
    key = str(uuid4())
    headers = write(w["headers"], key)
    response = await client.post(w["url"] + "/operator-handovers", headers=headers, json=payload(w))
    assert response.status_code == 201, response.text
    lost = await client.get(
        w["url"] + "/operator-handover-receipt", headers=w["headers"], params={"action_key": key}
    )
    assert lost.status_code == 200
    assert lost.json() == response.json()
    wrong = await client.post(
        w["url"] + "/operator-handovers", headers=headers, json=payload(w, password="wrong")
    )
    assert wrong.status_code == 401
    replay = await client.post(w["url"] + "/operator-handovers", headers=headers, json=payload(w))
    assert replay.json() == response.json()
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 1
    stale = await post(client, w)
    assert stale.status_code == 409
    not_current = await post(client, w, expected_revision=1)
    assert not_current.status_code == 403
    login = await client.post(
        "/api/v1/auth/login", json={"email": w["b"].email, "password": PASSWORD}
    )
    bh = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(w["terminal"].id),
    }
    back = await client.post(
        w["url"] + "/operator-handovers",
        headers=write(bh),
        json=payload(w, incoming_user_id=str(w["a"].id), expected_revision=1),
    )
    assert back.status_code == 201, back.text
    assert back.json()["previous_operator_user_id"] == str(w["b"].id)
    assert back.json()["to_revision"] == 2


async def test_duplicate_and_competing_handover_commits_once(client, session, till):
    w = till
    key = str(uuid4())
    results = await asyncio.gather(
        *[
            client.post(
                w["url"] + "/operator-handovers", headers=write(w["headers"], key), json=payload(w)
            )
            for _ in range(2)
        ]
    )
    assert all(r.status_code in (201, 409) for r in results)
    assert any(r.status_code == 201 for r in results)
    replay = await client.post(
        w["url"] + "/operator-handovers", headers=write(w["headers"], key), json=payload(w)
    )
    assert replay.status_code == 201
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 1


@pytest.mark.parametrize(
    "change",
    [
        "wrong_password",
        "inactive",
        "wrong_branch",
        "no_permission",
        "self",
        "not_approved",
        "offline",
    ],
)
async def test_invalid_handover_never_changes_operator(client, session, till, change, monkeypatch):
    from app.models import AuditLog, Branch, Role, UserRole

    w = till
    body = payload(w)
    headers = write(w["headers"])
    if change == "wrong_password":
        body["password"] = "wrong"
    elif change == "inactive":
        w["b"].status = "suspended"
        await session.commit()
    elif change == "wrong_branch":
        branch = Branch(
            id=uuid4(), company_id=w["company"].id, name="Other", invoice_series_code="HO"
        )
        session.add(branch)
        await session.flush()
        assignment = (
            await session.execute(select(UserRole).where(UserRole.user_id == w["b"].id))
        ).scalar_one()
        assignment.branch_id = branch.id
        await session.commit()
    elif change == "no_permission":
        role = Role(
            id=uuid4(), company_id=w["company"].id, code="kitchen", name="Kitchen", permissions=[]
        )
        session.add(role)
        await session.flush()
        assignment = (
            await session.execute(select(UserRole).where(UserRole.user_id == w["b"].id))
        ).scalar_one()
        assignment.role_id = role.id
        await session.commit()
    elif change == "self":
        body["incoming_user_id"] = str(w["a"].id)
    elif change == "not_approved":
        body["approved"] = False
    else:
        headers["X-Offline-Captured"] = "true"
    ineligible = change in {"inactive", "wrong_branch", "no_permission"}
    buckets = []
    limiter = route.enforce_login_rate_limit

    async def record_bucket(request, identity):
        buckets.append(identity)
        await limiter(request, identity)

    if ineligible:
        body["password"] = "wrong"
        monkeypatch.setattr(route, "enforce_login_rate_limit", record_bucket)
    response = await client.post(w["url"] + "/operator-handovers", headers=headers, json=body)
    assert response.status_code in (401, 403, 422), response.text
    if ineligible:
        assert buckets == [f"handover:{w['company'].id}:{w['b'].id}"]
        await session.refresh(w["b"])
        assert w["b"].failed_login_count == 0
        assert w["b"].locked_until is None
        audit_count = await session.scalar(
            select(func.count()).select_from(AuditLog).where(
                AuditLog.company_id == w["company"].id,
                AuditLog.action == "pos.operator_handover.authentication_failed",
            )
        )
        assert audit_count == 0
    await session.refresh(w["shift"])
    assert w["shift"].operator_user_id is None
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 0


@pytest.mark.parametrize("context", ["absent", "closed", "stale", "other_operator"])
async def test_invalid_shift_context_does_not_charge_incoming_credentials(
    client, session, till, monkeypatch, context
):
    from app.models import AuditLog

    w = till
    endpoint = w["url"] + "/operator-handovers"
    body = payload(w, password="wrong")
    expected = {"absent": 404, "closed": 422, "stale": 409, "other_operator": 403}
    if context == "absent":
        endpoint = f"/api/v1/pos/shifts/{uuid4()}/operator-handovers"
    elif context == "closed":
        closed = await client.post(
            w["url"] + "/close", headers=write(w["headers"]), json={"counted_minor": 0}
        )
        assert closed.status_code == 200
    elif context == "stale":
        body["expected_revision"] = 1
    else:
        assert (await post(client, w)).status_code == 201
        body["expected_revision"] = 1

    buckets = []
    limiter = route.enforce_login_rate_limit

    async def record_bucket(request, identity):
        buckets.append(identity)
        await limiter(request, identity)

    monkeypatch.setattr(route, "enforce_login_rate_limit", record_bucket)
    response = await client.post(endpoint, headers=write(w["headers"]), json=body)
    assert response.status_code == expected[context], response.text
    assert buckets == []
    await session.refresh(w["b"])
    assert w["b"].failed_login_count == 0
    assert w["b"].locked_until is None
    assert await session.scalar(
        select(func.count()).select_from(AuditLog).where(
            AuditLog.company_id == w["company"].id,
            AuditLog.action == "pos.operator_handover.authentication_failed",
        )
    ) == 0


async def test_locked_incoming_recheck_precedes_replay(client, session, till, monkeypatch):
    w = till
    headers = write(w["headers"])
    endpoint = w["url"] + "/operator-handovers"
    accepted = await client.post(endpoint, headers=headers, json=payload(w))
    assert accepted.status_code == 201, accepted.text
    checks = []
    buckets = []
    qualify = route._qualified_user
    limiter = route.enforce_login_rate_limit

    async def revoke_before_locked_check(db, caller, user):
        checks.append(user.id)
        if len(checks) == 2:
            raise route.ForbiddenError("The incoming operator lost branch access.")
        return await qualify(db, caller, user)

    async def record_bucket(request, identity):
        buckets.append(identity)
        await limiter(request, identity)

    monkeypatch.setattr(route, "_qualified_user", revoke_before_locked_check)
    monkeypatch.setattr(route, "enforce_login_rate_limit", record_bucket)
    replay = await client.post(endpoint, headers=headers, json=payload(w))
    assert replay.status_code == 403, replay.text
    assert checks == [w["b"].id, w["b"].id]
    assert buckets == [f"handover:{w['company'].id}:{w['b'].id}"]
    await session.refresh(w["b"])
    assert w["b"].failed_login_count == 0
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 1



async def test_close_first_blocks_handover_and_close_after_preserves_immutable_receipt(
    client, session, till
):
    w = till
    closed = await client.post(
        w["url"] + "/close", headers=write(w["headers"]), json={"counted_minor": 0}
    )
    assert closed.status_code == 200, closed.text
    failed = await post(client, w)
    assert failed.status_code == 422
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 0


async def _held_order(session, w):
    order = Order(
        id=uuid4(),
        company_id=w["company"].id,
        branch_id=w["branch"].id,
        terminal_id=w["terminal"].id,
        shift_id=w["shift"].id,
        opened_by=w["a"].id,
        type="takeaway",
        status="held",
        opened_at=datetime.now(UTC),
        held_at=datetime.now(UTC),
        total_minor=0,
    )
    session.add(order)
    await session.commit()
    return order


async def test_existing_checkout_lease_blocks_until_explicit_original_token_release(
    client, session, till
):
    w = till
    order = await _held_order(session, w)
    url = f"/api/v1/pos/orders/{order.id}/checkout-claim"
    claim = await client.post(url, headers=w["headers"])
    assert claim.status_code == 201, claim.text
    state = (await client.get(w["url"] + "/operator-state", headers=w["headers"])).json()
    assert state["own_active_checkout_order_ids"] == [str(order.id)]
    rejected = await post(client, w)
    assert rejected.status_code == 409
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 0
    token = claim.json()["claim_token"]
    released = await client.delete(url, headers={**w["headers"], "X-Checkout-Claim": token})
    assert released.status_code == 204, released.text
    accepted = await post(client, w)
    assert accepted.status_code == 201, accepted.text


async def test_handover_first_inflight_claim_remains_visible_not_silently_cleared(
    client, session, till, monkeypatch
):
    w = till
    order = await _held_order(session, w)
    entered = asyncio.Event()
    release = asyncio.Event()
    original = route._own_leases

    async def barrier(db, tenant, shift_id):
        leases = await original(db, tenant, shift_id)
        entered.set()
        await asyncio.wait_for(release.wait(), 5)
        return leases

    monkeypatch.setattr(route, "_own_leases", barrier)
    handover_task = asyncio.create_task(post(client, w))
    await asyncio.wait_for(entered.wait(), 5)
    claim_task = asyncio.create_task(
        client.post(f"/api/v1/pos/orders/{order.id}/checkout-claim", headers=w["headers"])
    )
    try:
        release.set()
        result, claim = await asyncio.wait_for(asyncio.gather(handover_task, claim_task), 10)
    finally:
        release.set()
        for task in (handover_task, claim_task):
            if not task.done():
                task.cancel()
        await asyncio.gather(handover_task, claim_task, return_exceptions=True)
    assert result.status_code == 201, result.text
    assert claim.status_code == 201, claim.text
    monkeypatch.setattr(route, "_own_leases", original)
    state = (await client.get(w["url"] + "/operator-state", headers=w["headers"])).json()
    assert state["operator_user_id"] == str(w["b"].id)
    assert state["own_active_checkout_order_ids"] == [str(order.id)]


async def test_database_history_and_pointer_are_guarded(client, session, till):
    w = till
    response = await post(client, w)
    assert response.status_code == 201, response.text
    handover_id = UUID(response.json()["id"])
    for sql, params in [
        (
            "UPDATE shift_operator_handovers SET counted_cash_minor=0 WHERE id=:id",
            {"id": handover_id},
        ),
        ("DELETE FROM shift_operator_handovers WHERE id=:id", {"id": handover_id}),
        (
            "UPDATE shifts SET operator_user_id=:user,operator_revision=2 WHERE id=:id",
            {"id": w["shift"].id, "user": w["a"].id},
        ),
    ]:
        with pytest.raises(IntegrityError):
            async with session.begin_nested():
                await session.execute(text(sql), params)
    await session.refresh(w["shift"])
    assert w["shift"].operator_user_id == w["b"].id
    assert w["shift"].operator_revision == 1


async def test_accepted_lost_response_readable_after_b_disabled(client, session, till):
    w = till
    key = str(uuid4())
    headers = write(w["headers"], key)
    accepted = await client.post(w["url"] + "/operator-handovers", headers=headers, json=payload(w))
    assert accepted.status_code == 201
    w["b"].status = "suspended"
    await session.commit()
    found = await client.get(
        w["url"] + "/operator-handover-receipt", headers=w["headers"], params={"action_key": key}
    )
    assert found.status_code == 200
    assert found.json() == accepted.json()
    replay = await client.post(w["url"] + "/operator-handovers", headers=headers, json=payload(w))
    assert replay.status_code == 401
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 1


async def test_receipt404_is_inconclusive_until_inflight_handover_finishes_then_close_serializes(
    client, session, till, monkeypatch
):
    w = till
    key = str(uuid4())
    entered = asyncio.Event()
    release = asyncio.Event()
    original = route._own_leases

    async def barrier(db, tenant, shift_id):
        result = await original(db, tenant, shift_id)
        entered.set()
        await asyncio.wait_for(release.wait(), 5)
        return result

    monkeypatch.setattr(route, "_own_leases", barrier)
    task = asyncio.create_task(
        client.post(
            w["url"] + "/operator-handovers", headers=write(w["headers"], key), json=payload(w)
        )
    )
    close_task = None
    try:
        await asyncio.wait_for(entered.wait(), 5)
        unknown = await client.get(
            w["url"] + "/operator-handover-receipt",
            headers=w["headers"],
            params={"action_key": key},
        )
        assert unknown.status_code == 404  # It must NOT authorize clearing a native recovery fence.
        close_task = asyncio.create_task(
            client.post(w["url"] + "/close", headers=write(w["headers"]), json={"counted_minor": 0})
        )
        release.set()
        accepted, closed = await asyncio.wait_for(asyncio.gather(task, close_task), 10)
    finally:
        release.set()
        tasks = [t for t in (task, close_task) if t is not None]
        for t in tasks:
            if not t.done():
                t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    assert accepted.status_code == 201, accepted.text
    assert closed.status_code == 200, closed.text
    found = await client.get(
        w["url"] + "/operator-handover-receipt", headers=w["headers"], params={"action_key": key}
    )
    assert found.status_code == 200
    assert found.json() == accepted.json()
    await session.refresh(w["shift"])
    assert w["shift"].status == "closed"
    assert w["shift"].closed_by == w["a"].id
    assert w["shift"].operator_user_id == w["b"].id


async def test_foreign_target_and_wrong_terminal_are_fenced(client, session, till):
    from app.core.security import hash_password
    from app.models import Branch, Company, Terminal

    w = till
    company = Company(id=uuid4(), name="Synthetic handover foreigncompany")
    session.add(company)
    await session.flush()
    other = User(
        id=uuid4(),
        company_id=company.id,
        email=f"other-{uuid4()}@example.test",
        name="Other",
        password_hash=hash_password(PASSWORD),
        status="active",
    )
    session.add(other)
    other_branch = Branch(
        id=uuid4(), company_id=w["company"].id, name="Other scope", invoice_series_code="OS"
    )
    session.add(other_branch)
    await session.flush()
    terminal = Terminal(
        id=uuid4(), branch_id=other_branch.id, name="Other till", device_id=str(uuid4())
    )
    session.add(terminal)
    await session.commit()
    foreign = await post(client, w, incoming_user_id=str(other.id))
    assert foreign.status_code == 401
    wrong = await client.post(
        w["url"] + "/operator-handovers",
        headers=write({**w["headers"], "X-Terminal-Id": str(terminal.id)}),
        json=payload(w),
    )
    assert wrong.status_code in (401, 403, 422)
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 0


async def test_owner_recovery_resolves_disabled_target_without_staff_password(
    client, session, till
):
    from app.models import AuditLog

    w = till
    key = str(uuid4())
    first = await client.post(
        w["url"] + "/operator-handovers", headers=write(w["headers"], key), json=payload(w)
    )
    assert first.status_code == 201, first.text
    w["b"].status = "suspended"
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login", json={"email": w["owner"].email, "password": w["password"]}
    )
    assert login.status_code == 200, login.text
    owner_headers = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(w["terminal"].id),
    }
    body = {
        "incoming_user_id": str(w["a"].id),
        "expected_revision": 1,
        "approved": True,
        "reason": "Resolve the interrupted operator handover after departure",
        "resolves_handover_id": first.json()["id"],
    }
    prohibited = await client.post(
        w["url"] + "/operator-handovers/recover", headers=write(w["headers"]), json=body
    )
    assert prohibited.status_code == 403
    snapshot = (
        (await session.execute(select(Shift.__table__).where(Shift.id == w["shift"].id)))
        .mappings()
        .one()
    )
    families = list(
        (
            await session.execute(
                select(AuthRefreshSession.__table__).where(
                    AuthRefreshSession.company_id == w["company"].id
                )
            )
        ).all()
    )
    # The third actor has live POS permissions in this same workspace.
    third_identity = await client.get("/api/v1/auth/me", headers=owner_headers)
    assert third_identity.status_code == 200
    assert "pos.write" in third_identity.json()["effective_permissions"]
    unrelated = await client.post(
        w["url"] + "/operator-handovers/recover",
        headers=write(owner_headers),
        json={**body, "incoming_user_id": str(w["owner"].id)},
    )
    assert unrelated.status_code == 422, unrelated.text
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 1
    await session.refresh(w["shift"])
    assert w["shift"].operator_user_id == w["b"].id
    assert w["shift"].operator_revision == 1
    recovery_headers = write(owner_headers)
    recovered = await client.post(
        w["url"] + "/operator-handovers/recover", headers=recovery_headers, json=body
    )
    assert recovered.status_code == 201, recovered.text
    assert recovered.json()["handover_kind"] == "owner_recovery"
    assert recovered.json()["resolves_handover_id"] == first.json()["id"]
    assert recovered.json()["previous_operator_user_id"] == str(w["b"].id)
    assert recovered.json()["initiated_by_user_id"] == str(w["owner"].id)
    assert recovered.json()["incoming_user_id"] == str(w["a"].id)
    assert recovered.json()["counted_cash_minor"] is None
    assert recovered.json()["cash_count_status"] == "NOT_COUNTED"
    again = await client.post(
        w["url"] + "/operator-handovers/recover", headers=recovery_headers, json=body
    )
    assert again.json() == recovered.json()
    state = (await client.get(w["url"] + "/operator-state", headers=w["headers"])).json()
    assert state["operator_user_id"] == str(w["a"].id)
    assert state["latest_handover"] == recovered.json()
    old = await client.get(
        w["url"] + "/operator-handover-receipt", headers=w["headers"], params={"action_key": key}
    )
    assert old.json() == first.json()
    after = (
        (await session.execute(select(Shift.__table__).where(Shift.id == w["shift"].id)))
        .mappings()
        .one()
    )
    assert {
        k: v for k, v in snapshot.items() if k not in ("operator_user_id", "operator_revision")
    } == {k: v for k, v in after.items() if k not in ("operator_user_id", "operator_revision")}
    assert (
        list(
            (
                await session.execute(
                    select(AuthRefreshSession.__table__).where(
                        AuthRefreshSession.company_id == w["company"].id
                    )
                )
            ).all()
        )
        == families
    )
    await session.refresh(w["b"])
    assert w["b"].status == "suspended"
    assert await total(session, Attendance, w["company"].id) == 0
    audit = (
        (
            await session.execute(
                select(AuditLog).where(AuditLog.entity_id == recovered.json()["id"])
            )
        )
        .scalars()
        .all()
    )
    assert audit
    assert all(a.actor_user_id == w["owner"].id for a in audit)
    wrong_link = await client.post(
        w["url"] + "/operator-handovers/recover",
        headers=write(owner_headers),
        json={
            **body,
            "expected_revision": 2,
            "resolves_handover_id": str(uuid4()),
            "incoming_user_id": str(w["owner"].id),
        },
    )
    assert wrong_link.status_code == 409
    stale = await client.post(
        w["url"] + "/operator-handovers/recover", headers=write(owner_headers), json=body
    )
    assert stale.status_code == 409
    closed = await client.post(
        w["url"] + "/close", headers=write(w["headers"]), json={"counted_minor": 0}
    )
    assert closed.status_code == 200
    closed_recovery = await client.post(
        w["url"] + "/operator-handovers/recover",
        headers=write(owner_headers),
        json={
            **body,
            "expected_revision": 2,
            "resolves_handover_id": recovered.json()["id"],
            "incoming_user_id": str(w["owner"].id),
        },
    )
    assert closed_recovery.status_code == 422
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 2


async def test_owner_recovery_reads_and_correction_work_without_regular_pos_module(
    client, session, till
):
    from app.models import RolePermissionOverride

    w = till
    key = str(uuid4())
    accepted = await client.post(
        w["url"] + "/operator-handovers", headers=write(w["headers"], key), json=payload(w)
    )
    assert accepted.status_code == 201
    w["b"].status = "suspended"
    session.add(
        RolePermissionOverride(
            company_id=w["company"].id, role_code="owner", module="pos", allowed=False
        )
    )
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login", json={"email": w["owner"].email, "password": w["password"]}
    )
    assert login.status_code == 200, login.text
    headers = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(w["terminal"].id),
    }
    me = (await client.get("/api/v1/auth/me", headers=headers)).json()
    assert "pos.write" not in me["effective_permissions"]
    assert "pos.shift.handover.recover" in me["effective_permissions"]
    state = await client.get(w["url"] + "/operator-state", headers=headers)
    assert state.status_code == 200, state.text
    assert state.json()["recovery_target"] == {"id": str(w["a"].id), "name": w["a"].name}
    original = await client.get(w["url"] + "/operator-state", headers=w["headers"])
    assert original.status_code == 200
    assert original.json()["recovery_target"] is None
    found = await client.get(
        w["url"] + "/operator-handover-receipt", headers=headers, params={"action_key": key}
    )
    assert found.status_code == 200
    routine = await client.post(
        w["url"] + "/operator-handovers",
        headers=write(headers),
        json=payload(w, expected_revision=1),
    )
    assert routine.status_code == 403
    corrected = await client.post(
        w["url"] + "/operator-handovers/recover",
        headers=write(headers),
        json={
            "incoming_user_id": str(w["a"].id),
            "expected_revision": 1,
            "approved": True,
            "reason": "Resolve this interrupted handover while normal POS access is disabled",
            "resolves_handover_id": accepted.json()["id"],
        },
    )
    assert corrected.status_code == 201, corrected.text
    assert corrected.json()["incoming_user_id"] == str(w["a"].id)
    after = await client.get(w["url"] + "/operator-state", headers=headers)
    assert after.status_code == 200
    assert after.json()["recovery_target"] is None


async def test_unrelated_staff_reads_public_operator_but_not_nested_private_receipt(
    client, session, till
):
    from app.core.security import hash_password
    from app.models import UserRole

    w = till
    first = await post(client, w)
    assert first.status_code == 201
    owner_login = await client.post(
        "/api/v1/auth/login", json={"email": w["owner"].email, "password": w["password"]}
    )
    owner_headers = {
        "Authorization": "Bearer " + owner_login.json()["access_token"],
        "X-Terminal-Id": str(w["terminal"].id),
    }
    key = str(uuid4())
    reason = "Owner reviewed this interrupted operator responsibility"
    recovered = await client.post(
        w["url"] + "/operator-handovers/recover",
        headers=write(owner_headers, key),
        json={
            "incoming_user_id": str(w["a"].id),
            "expected_revision": 1,
            "approved": True,
            "reason": reason,
            "resolves_handover_id": first.json()["id"],
        },
    )
    assert recovered.status_code == 201, recovered.text
    third = User(
        id=uuid4(),
        company_id=w["company"].id,
        email=f"third-{uuid4()}@example.test",
        name="Third synthetic operator",
        password_hash=hash_password(PASSWORD),
        status="active",
    )
    session.add(third)
    await session.flush()
    session.add(UserRole(user_id=third.id, role_id=w["role"].id, branch_id=w["branch"].id))
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login", json={"email": third.email, "password": PASSWORD}
    )
    headers = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(w["terminal"].id),
    }
    public = await client.get(w["url"] + "/operator-state", headers=headers)
    assert public.status_code == 200
    assert public.json()["operator_user_id"] == str(w["a"].id)
    assert public.json()["latest_handover"] is None
    assert reason not in public.text
    private = await client.get(
        w["url"] + "/operator-handover-receipt", headers=headers, params={"action_key": key}
    )
    assert private.status_code == 403


async def test_local_durable_receipt_recovery_after_ordinary_receipt_cleanup_still_authenticates(
    client, session, till
):
    from sqlalchemy import delete

    from app.models import IdempotencyKey

    w = till
    key = str(uuid4())
    headers = write(w["headers"], key)
    first = await client.post(w["url"] + "/operator-handovers", headers=headers, json=payload(w))
    assert first.status_code == 201
    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key == key))
    await session.commit()
    wrong = await client.post(
        w["url"] + "/operator-handovers", headers=headers, json=payload(w, password="wrong")
    )
    assert wrong.status_code == 401
    assert (
        await session.execute(select(IdempotencyKey).where(IdempotencyKey.key == key))
    ).scalar_one_or_none() is None
    replay = await client.post(w["url"] + "/operator-handovers", headers=headers, json=payload(w))
    assert replay.status_code == 201, replay.text
    assert replay.json() == first.json()
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 1
    await session.refresh(w["shift"])
    assert w["shift"].operator_revision == 1


async def test_foreign_company_action_collision_after_receipt_cleanup_is_controlled409(
    client, session, till
):
    from sqlalchemy import delete

    from app.core.security import hash_password
    from app.models import Branch, Company, IdempotencyKey, Role, Terminal, UserRole

    w = till
    company = Company(id=uuid4(), name="Foreign synthetic receipt namespace")
    session.add(company)
    await session.flush()
    branch = Branch(
        id=uuid4(), company_id=company.id, name="Foreign branch", invoice_series_code="FR"
    )
    role = Role(
        id=uuid4(),
        company_id=company.id,
        code="gaming_cashier",
        name="Foreign staff",
        permissions=[],
    )
    first = User(
        id=uuid4(),
        company_id=company.id,
        email=f"foreign-a-{uuid4()}@example.test",
        name="Foreign A",
        password_hash=hash_password(PASSWORD),
        status="active",
    )
    second = User(
        id=uuid4(),
        company_id=company.id,
        email=f"foreign-b-{uuid4()}@example.test",
        name="Foreign B",
        password_hash=hash_password(PASSWORD),
        status="active",
    )
    session.add_all([branch, role, first, second])
    await session.flush()
    terminal = Terminal(
        id=uuid4(), branch_id=branch.id, name="Foreign till", device_id=str(uuid4())
    )
    session.add(terminal)
    await session.flush()
    session.add_all(
        [UserRole(user_id=u.id, role_id=role.id, branch_id=branch.id) for u in (first, second)]
    )
    foreign_shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=first.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        status="open",
    )
    session.add(foreign_shift)
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login", json={"email": first.email, "password": PASSWORD}
    )
    assert login.status_code == 200
    foreign_headers = {
        "Authorization": "Bearer " + login.json()["access_token"],
        "X-Terminal-Id": str(terminal.id),
    }
    key = str(uuid4())
    accepted = await client.post(
        f"/api/v1/pos/shifts/{foreign_shift.id}/operator-handovers",
        headers=write(foreign_headers, key),
        json={
            "incoming_user_id": str(second.id),
            "password": PASSWORD,
            "expected_revision": 0,
            "approved": True,
        },
    )
    assert accepted.status_code == 201, accepted.text
    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key == key))
    await session.commit()
    collision = await client.post(
        w["url"] + "/operator-handovers", headers=write(w["headers"], key), json=payload(w)
    )
    assert collision.status_code == 409, collision.text
    assert collision.json()["error"]["code"] == "conflict"
    assert "Foreign" not in collision.text
    assert await total(session, ShiftOperatorHandover, company.id) == 1
    assert await total(session, ShiftOperatorHandover, w["company"].id) == 0
    assert (
        await session.execute(select(IdempotencyKey).where(IdempotencyKey.key == key))
    ).scalar_one_or_none() is None
    await session.refresh(w["shift"])
    assert w["shift"].operator_user_id is None
