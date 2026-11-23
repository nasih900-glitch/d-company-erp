"""Exact signed-device recovery evidence, using real login and P-256 proof."""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select

from app.api.v1.remote_assistance import router as route
from app.core.errors import ConflictError, ForbiddenError
from app.core.security import hash_password
from app.models import (
    AuditLog,
    RemoteAssistanceGrant,
    RemoteAssistanceSession,
    Role,
    User,
    UserRole,
)
from tests.integration import test_remote_assistance_device_auth as crypto

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]
BASE = "/api/v1/remote-assistance"
PASSWORD = "synthetic-terminal-status-password"
GRANT_FIELDS = {
    "server_time",
    "company_id",
    "installation_id",
    "id",
    "kind",
    "status",
    "requested_for_user_id",
    "responded_by_user_id",
    "requested_at",
    "expires_at",
    "responded_at",
    "decision_id",
    "revoked_at",
    "revocation_id",
    "consumed_at",
}
SESSION_FIELDS = {
    "server_time",
    "company_id",
    "installation_id",
    "id",
    "grant_id",
    "status",
    "requested_for_user_id",
    "responded_by_user_id",
    "requested_at",
    "request_expires_at",
    "started_at",
    "expires_at",
    "ended_at",
    "end_id",
    "end_reason",
}


async def login(client, user, password):
    response = await client.post(
        "/api/v1/auth/login",
        json={"email": user.email, "password": password},
    )
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["access_token"]}


async def signed(client, w, method, path, payload=None, *, key=None, headers=None, **proof):
    content = b"" if payload is None else json.dumps(payload, separators=(",", ":")).encode()
    if "proof_epoch" in w and "epoch" not in proof:
        proof["epoch"] = w["proof_epoch"]
    signature, _ = crypto._device_signature_headers(
        key=key or w["key"],
        method=method,
        raw_target=path,
        content=content,
        **proof,
    )
    return await client.request(
        method,
        path,
        headers={
            **w["headers"],
            **signature,
            **(headers or {}),
            "Content-Type": "application/json",
        },
        content=content,
    )


async def status(client, w, kind, target=None, **kwargs):
    target = target or w["grant" if kind == "grants" else "support"]
    return await signed(
        client,
        w,
        "GET",
        f"{BASE}/device/{kind}/{target}/status?installation_id={w['installation'].installation_id}",
        **kwargs,
    )


async def make_world(client, session, seed_owner):
    role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="gaming_cashier",
        name="Synthetic store operator",
        permissions=[],
    )
    users = [
        User(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            email=f"terminal-status-{uuid4()}@example.test",
            name=name,
            password_hash=hash_password(PASSWORD),
            status="active",
        )
        for name in ("Device A", "Device B")
    ]
    session.add_all([role, *users])
    await session.flush()
    session.add_all(
        [
            UserRole(
                id=uuid4(),
                user_id=user.id,
                role_id=role.id,
                branch_id=seed_owner["branch"].id,
            )
            for user in users
        ]
    )
    await session.commit()
    a, b = users
    owner_headers = await login(client, seed_owner["owner"], seed_owner["password"])
    a_headers = await login(client, a, PASSWORD)
    b_headers = await login(client, b, PASSWORD)
    installation = await crypto._installation(session, {**seed_owner, "owner": a})
    headers = {
        **a_headers,
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "65",
        "X-Client-Distribution-Channel": "direct",
    }
    key = crypto._new_key()
    enrolled = await client.post(
        BASE + "/device/keys/enroll",
        headers=headers,
        json=crypto._enrollment_payload(
            seed={**seed_owner, "owner": a}, installation_id=installation.installation_id, key=key
        ),
    )
    assert enrolled.status_code == 200, enrolled.text
    approved = await client.post(
        f"{BASE}/device-keys/{key.key_id}/approve",
        headers=owner_headers,
        json={"approval_id": str(uuid4()), "pairing_code": enrolled.json()["pairing_code"]},
    )
    assert approved.status_code == 200, approved.text
    w = {
        **seed_owner,
        "a": a,
        "b": b,
        "owner_headers": owner_headers,
        "b_headers": b_headers,
        "headers": headers,
        "key": key,
        "installation": installation,
    }
    heartbeat = await signed(
        client,
        w,
        "POST",
        BASE + "/device/heartbeat",
        {
            "installation_id": str(installation.installation_id),
            "protocol_version": 1,
            "sharing_capability": "available",
        },
    )
    assert heartbeat.status_code == 200, heartbeat.text
    requested = await client.post(
        BASE + "/requests",
        headers=owner_headers,
        json={
            "request_id": str(uuid4()),
            "installation_id": str(installation.installation_id),
            "grant_kind": "one_time",
            "grant_ttl_seconds": 900,
            "session_ttl_seconds": 900,
        },
    )
    assert requested.status_code == 200, requested.text
    return {
        **w,
        "grant": requested.json()["grant"]["id"],
        "support": requested.json()["session"]["id"],
    }


@pytest_asyncio.fixture
async def world(client, session, seed_owner):
    return await make_world(client, session, seed_owner)


async def decide(client, w, decision="accepted", action=None):
    action = action or uuid4()
    response = await signed(
        client,
        w,
        "POST",
        f"{BASE}/device/grants/{w['grant']}/decision",
        {
            "installation_id": str(w["installation"].installation_id),
            "decision_id": str(action),
            "decision": decision,
        },
    )
    return response, action


@pytest.mark.parametrize("decision", ["accepted", "declined"])
async def test_exact_recorded_decision_and_minimal_private_projection(
    client, session, world, decision
):
    w = world
    reply, action = await decide(client, w, decision)
    assert reply.status_code == 200, reply.text
    before = (await session.execute(select(func.count()).select_from(AuditLog))).scalar_one()
    response = await status(client, w, "grants")
    assert response.status_code == 200, response.text
    data = response.json()
    assert set(data) == GRANT_FIELDS
    assert response.headers["cache-control"] == "private, no-store"
    assert data["decision_id"] == str(action)
    assert data["requested_for_user_id"] == data["responded_by_user_id"] == str(w["a"].id)
    assert data["status"] == ("active" if decision == "accepted" else "declined")
    assert data["responded_at"] is not None
    again = await status(client, w, "grants")
    assert {k: v for k, v in again.json().items() if k != "server_time"} == {
        k: v for k, v in data.items() if k != "server_time"
    }
    assert (
        await session.execute(select(func.count()).select_from(AuditLog))
    ).scalar_one() == before


async def test_exact_revocation_ack_and_late_different_action_refusal(client, world):
    w = world
    accepted, decision = await decide(client, w)
    assert accepted.status_code == 200
    action = uuid4()
    path = f"{BASE}/device/grants/{w['grant']}/revoke"
    payload = {
        "installation_id": str(w["installation"].installation_id),
        "revocation_id": str(action),
    }
    for _ in range(2):
        reply = await signed(client, w, "POST", path, payload)
        assert reply.status_code == 200, reply.text
    response = await status(client, w, "grants")
    assert response.status_code == 200
    assert response.json()["status"] == "revoked"
    assert response.json()["revocation_id"] == str(action)
    assert response.json()["decision_id"] == str(decision)
    assert response.json()["revoked_at"] is not None
    late = await signed(client, w, "POST", path, {**payload, "revocation_id": str(uuid4())})
    assert late.status_code == 410
    assert (await status(client, w, "grants")).json()["revocation_id"] == str(action)


async def test_exact_end_ack_and_immutable_history(client, world):
    w = world
    accepted, _ = await decide(client, w)
    assert accepted.status_code == 200
    action = uuid4()
    path = f"{BASE}/device/sessions/{w['support']}/end"
    payload = {
        "installation_id": str(w["installation"].installation_id),
        "end_id": str(action),
        "reason": "user_ended",
    }
    for _ in range(2):
        reply = await signed(client, w, "POST", path, payload)
        assert reply.status_code == 200, reply.text
    response = await status(client, w, "sessions")
    assert response.status_code == 200, response.text
    data = response.json()
    assert set(data) == SESSION_FIELDS
    assert response.headers["cache-control"] == "private, no-store"
    assert data["end_id"] == str(action)
    assert data["end_reason"] == "user_ended"
    assert data["ended_at"] is not None
    assert data["status"] == "ended"
    assert data["grant_id"] == w["grant"]
    assert data["requested_for_user_id"] == str(w["a"].id)


async def test_unanswered_expiry_is_supersession_not_fabricated_ack(
    client,
    world,
    monkeypatch,
):
    w = world
    future = datetime.now(UTC) + timedelta(seconds=901)

    class ExpiredClock(datetime):
        @classmethod
        def now(cls, tz=None):
            return future if tz is not None else future.replace(tzinfo=None)

    # Lifecycle identity remains immutable; advance only this router's test clock.
    monkeypatch.setattr(route, "datetime", ExpiredClock)
    w = {**w, "proof_epoch": int(future.timestamp())}
    lost_id = uuid4()  # This conceptual pending action was never sent before expiry.
    retry, _ = await decide(client, w, action=lost_id)
    assert retry.status_code == 410
    grant = await status(client, w, "grants")
    support = await status(client, w, "sessions")
    assert grant.status_code == support.status_code == 200
    assert grant.json()["status"] == support.json()["status"] == "expired"
    for field in ("decision_id", "responded_at", "responded_by_user_id", "revocation_id"):
        assert grant.json()[field] is None
    for field in ("end_id", "ended_at", "end_reason"):
        assert support.json()[field] is None
    late, _ = await decide(client, w, action=lost_id)
    assert late.status_code == 410
    assert (await status(client, w, "grants")).json()["status"] == "expired"


@pytest.mark.parametrize("kind", ["grants", "sessions"])
async def test_owner_terminal_action_returns_truthful_different_action(client, world, kind):
    w = world
    accepted, _ = await decide(client, w)
    assert accepted.status_code == 200
    actual = uuid4()
    field, suffix, payload = (
        ("revocation_id", f"/grants/{w['grant']}/revoke", {"revoke_id": str(actual)})
        if kind == "grants"
        else ("end_id", f"/sessions/{w['support']}/end", {"end_id": str(actual)})
    )
    ended = await client.post(BASE + suffix, headers=w["owner_headers"], json=payload)
    assert ended.status_code == 200, ended.text
    late_path = f"{BASE}/device/{kind}/{w['grant'] if kind == 'grants' else w['support']}"
    late = await signed(
        client,
        w,
        "POST",
        late_path + ("/revoke" if kind == "grants" else "/end"),
        {
            "installation_id": str(w["installation"].installation_id),
            field: str(uuid4()),
            **({"reason": "user_ended"} if kind == "sessions" else {}),
        },
    )
    assert late.status_code == 410, late.text
    response = await status(client, w, kind)
    assert response.status_code == 200
    assert response.json()[field] == str(actual)
    assert response.json()["status"] == ("revoked" if kind == "grants" else "ended")
    if kind == "sessions":
        assert response.json()["end_reason"] == "owner_ended"


async def test_requested_active_consumed_are_not_terminal_and_linked_session_survives(
    client, world
):
    w = world
    assert (await status(client, w, "grants")).json()["status"] == "requested"
    assert (await status(client, w, "sessions")).json()["status"] == "requested"
    accepted, decision = await decide(client, w)
    assert accepted.status_code == 200
    started = await client.post(
        f"{BASE}/sessions/{w['support']}/start",
        headers=w["owner_headers"],
        json={"start_id": str(uuid4())},
    )
    assert started.status_code == 200, started.text
    grant = await status(client, w, "grants")
    support = await status(client, w, "sessions")
    assert grant.json()["status"] == "consumed"
    assert grant.json()["decision_id"] == str(decision)
    assert support.json()["status"] == "active"
    assert support.json()["end_id"] is None


@pytest.mark.parametrize("kind", ["grants", "sessions"])
async def test_exact_company_install_target_and_current_actor_boundaries(
    client, session, world, kind
):
    w = world
    absent = await status(client, w, kind, target=str(uuid4()))
    assert absent.status_code == 404
    other_installation = await crypto._installation(session, {**w, "owner": w["a"]})
    wrong_install = await status(client, {**w, "installation": other_installation}, kind)
    assert wrong_install.status_code == 401  # A's key cannot prove another installation.
    b_headers = {**w["headers"], **w["b_headers"]}
    wrong_current_actor = await status(client, w, kind, headers=b_headers)
    assert wrong_current_actor.status_code == 403
    # After a legitimate installation actor change B still cannot inspect A's historical objects.
    heartbeat = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=b_headers,
        json={
            "installation_id": str(w["installation"].installation_id),
            "platform": "android",
            "distribution_channel": "direct",
            "version_name": "3.1.58",
            "version_code": 65,
            "pending_outbox_count": 0,
            "update_state": "idle",
        },
    )
    assert heartbeat.status_code == 200, heartbeat.text
    own_b_foreign_history = await status(client, w, kind, headers=b_headers)
    assert own_b_foreign_history.status_code == 404
    stale_a = await status(client, w, kind)
    assert stale_a.status_code == 403


@pytest.mark.parametrize("kind", ["grants", "sessions"])
async def test_real_proof_query_body_and_key_policy(client, world, kind):
    w = world
    path = f"{BASE}/device/{kind}/{w['grant'] if kind == 'grants' else w['support']}/status"
    exact = path + f"?installation_id={w['installation'].installation_id}"
    no_proof = await client.get(exact, headers=w["headers"])
    assert no_proof.status_code == 401
    no_bearer = await client.get(exact)
    assert no_bearer.status_code == 401
    assert (await status(client, w, kind, headers={"X-Client-Platform": "ios"})).status_code == 403
    assert (await status(client, w, kind, signer=crypto._new_key().private_key)).status_code == 401
    assert (
        await status(client, w, kind, epoch=int(datetime.now(UTC).timestamp()) - 600)
    ).status_code == 401
    nonce = uuid4()
    assert (await status(client, w, kind, nonce=nonce)).status_code == 200
    assert (await status(client, w, kind, nonce=nonce)).status_code == 401
    assert (await signed(client, w, "GET", exact + "&unexpected=true")).status_code == 422
    duplicate = exact + f"&installation_id={w['installation'].installation_id}"
    assert (await signed(client, w, "GET", duplicate)).status_code == 422
    assert (await signed(client, w, "GET", exact, {})).status_code == 422
    assert (await client.get(path, headers=w["headers"])).status_code == 422
    signature, _ = crypto._device_signature_headers(
        key=w["key"],
        method="GET",
        raw_target=exact,
        content=b"",
    )
    changed_query = exact.replace(str(w["installation"].installation_id), str(uuid4()))
    altered = await client.get(changed_query, headers={**w["headers"], **signature})
    assert altered.status_code == 404  # No exact company installation is leaked by a forged query.
    revoked = await client.post(
        f"{BASE}/device-keys/{w['key'].key_id}/revoke",
        headers=w["owner_headers"],
        json={"revocation_id": str(uuid4())},
    )
    assert revoked.status_code == 200, revoked.text
    assert (await status(client, w, kind)).status_code == 401


async def test_serialized_owner_revoke_and_observation_never_reactivates(client, world):
    w = world
    accepted, _ = await decide(client, w)
    assert accepted.status_code == 200
    action = uuid4()
    observed, revoked = await asyncio.gather(
        status(client, w, "grants"),
        client.post(
            f"{BASE}/grants/{w['grant']}/revoke",
            headers=w["owner_headers"],
            json={"revoke_id": str(action)},
        ),
    )
    assert observed.status_code == revoked.status_code == 200
    assert observed.json()["status"] in {"active", "revoked"}
    final = await status(client, w, "grants")
    assert final.json()["status"] == "revoked"
    assert final.json()["revocation_id"] == str(action)
    late, _ = await decide(client, w, action=uuid4())
    assert late.status_code in {409, 410}
    assert (await status(client, w, "grants")).json()["status"] == "revoked"


async def test_legacy_missing_actor_and_mixed_response_evidence_never_inferred():
    actor = uuid4()
    unanswered = SimpleNamespace(
        requested_for_user_id=actor,
        responded_by_user_id=None,
        responded_at=None,
        decision_id=None,
        status="expired",
    )
    route._require_status_consent_evidence(unanswered, user_id=actor)
    for bad_actor in (None, uuid4()):
        with pytest.raises(ForbiddenError):
            route._require_status_consent_evidence(
                SimpleNamespace(**{**vars(unanswered), "requested_for_user_id": bad_actor}),
                user_id=actor,
            )
    for changed in (
        {"responded_at": datetime.now(UTC)},
        {"decision_id": uuid4()},
        {"status": "active"},
    ):
        with pytest.raises(ConflictError):
            route._require_status_consent_evidence(
                SimpleNamespace(**{**vars(unanswered), **changed}),
                user_id=actor,
            )


@pytest.mark.parametrize("kind", ["grants", "sessions"])
async def test_foreign_company_and_same_company_foreign_target_are_not_observable(
    client,
    session,
    world,
    kind,
):
    w = world
    other_seed = await crypto._second_tenant(session)
    other = await make_world(client, session, {**other_seed, "password": "password1234"})
    target = other["grant" if kind == "grants" else "support"]
    assert (await status(client, w, kind, target=target)).status_code == 404
    foreign_install = await status(
        client,
        {**w, "installation": other["installation"]},
        kind,
        target=target,
    )
    assert foreign_install.status_code == 404
    # Valid same-company target owned by A but on a different installation.
    installation = await crypto._installation(session, {**w, "owner": w["a"]})
    now = datetime.now(UTC)
    grant = RemoteAssistanceGrant(
        id=uuid4(),
        company_id=w["company"].id,
        client_installation_id=installation.id,
        requested_by_user_id=w["owner"].id,
        requested_for_user_id=w["a"].id,
        kind="one_time",
        status="requested",
        requested_at=now,
        expires_at=now + timedelta(minutes=10),
    )
    session.add(grant)
    await session.flush()
    support = RemoteAssistanceSession(
        id=uuid4(),
        company_id=w["company"].id,
        grant_id=grant.id,
        client_installation_id=installation.id,
        requested_by_user_id=w["owner"].id,
        status="requested",
        duration_seconds=900,
        requested_at=now,
        request_expires_at=now + timedelta(minutes=5),
    )
    session.add(support)
    await session.commit()
    exact_other_target = str(grant.id if kind == "grants" else support.id)
    assert (await status(client, w, kind, target=exact_other_target)).status_code == 404
    assert (await status(client, w, kind)).status_code == 200
    assert installation.id != installation.installation_id
    assert w["installation"].id != w["installation"].installation_id


@pytest.mark.parametrize("kind", ["grants", "sessions"])
async def test_pending_key_cannot_observe_and_rotated_active_key_is_required(client, world, kind):
    w = world
    replacement = crypto._new_key()
    enrolled = await client.post(
        BASE + "/device/keys/enroll",
        headers=w["headers"],
        json=crypto._enrollment_payload(
            seed={**w, "owner": w["a"]},
            installation_id=w["installation"].installation_id,
            key=replacement,
        ),
    )
    assert enrolled.status_code == 200, enrolled.text
    assert (await status(client, w, kind, key=replacement)).status_code == 401
    assert (await status(client, w, kind)).status_code == 200
    approved = await client.post(
        f"{BASE}/device-keys/{replacement.key_id}/approve",
        headers=w["owner_headers"],
        json={"approval_id": str(uuid4()), "pairing_code": enrolled.json()["pairing_code"]},
    )
    assert approved.status_code == 200, approved.text
    assert (await status(client, w, kind)).status_code == 401
    current = await status(client, w, kind, key=replacement)
    assert current.status_code == 200, current.text
    assert current.json()["installation_id"] == str(w["installation"].installation_id)
    assert current.json()["requested_for_user_id"] == str(w["a"].id)
