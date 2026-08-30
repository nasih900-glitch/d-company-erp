"""Tenant, consent, lifecycle, idempotency, role, and audit proof."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid1, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select, text, update
from sqlalchemy.exc import IntegrityError

from app.api.v1.remote_assistance import router as remote_router
from app.core.security import hash_password, issue_access_token
from app.models import (
    AuditLog,
    Branch,
    ClientInstallation,
    Company,
    RemoteAssistanceCommand,
    RemoteAssistanceGrant,
    RemoteAssistanceSession,
    Role,
    Terminal,
    User,
    UserRole,
)


@pytest_asyncio.fixture(autouse=True)
async def require_remote_assistance_migration(session) -> None:
    try:
        await session.execute(text("select 1 from remote_assistance_grants limit 1"))
        await session.execute(
            text("select remote_support_protocol_version from client_installations limit 1")
        )
    except Exception as exc:
        pytest.skip(f"remote-assistance migration/local Postgres unavailable: {exc}")


@pytest.fixture(autouse=True)
def bypass_external_coordination(monkeypatch) -> None:
    async def allow(**_kwargs) -> None:
        return None

    monkeypatch.setattr(remote_router, "enforce_client_heartbeat_rate_limit", allow)
    monkeypatch.setattr(remote_router, "ensure_relay_available", allow)
    monkeypatch.setattr(remote_router, "admit_frame_upload", allow)
    monkeypatch.setattr(remote_router, "delete_latest_frame", allow)


def _headers(
    seed: dict,
    *,
    roles: list[str],
    audit_access: bool,
    android: bool,
) -> dict[str, str]:
    user = seed["owner"]
    token = issue_access_token(
        user_id=user.id,
        company_id=user.company_id,
        roles=roles,
        branch_id=seed["branch"].id,
        auth_version=user.auth_version,
        extra={
            "protected_access": "super_owner" in roles or "co_owner" in roles,
            "audit_access": audit_access,
        },
    )
    headers: dict[str, str] = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed["terminal"].id),
    }
    if android:
        headers["X-Client-Platform"] = "android"
        headers["X-Client-Version-Code"] = "16"
        headers["X-Client-Distribution-Channel"] = "direct"
    return headers


async def _installation(session, seed: dict) -> ClientInstallation:
    now = datetime.now(UTC)
    row = ClientInstallation(
        id=uuid4(),
        company_id=seed["company"].id,
        installation_id=uuid4(),
        registered_by_user_id=seed["owner"].id,
        last_user_id=seed["owner"].id,
        terminal_id=seed["terminal"].id,
        platform="android",
        distribution_channel="direct",
        version_name="3.1.6",
        version_code=16,
        pending_outbox_count=0,
        last_successful_sync_at=now,
        update_state="idle",
        update_error_code=None,
        last_seen_at=now,
        remote_support_protocol_version=1,
        remote_support_capability="available",
        remote_support_last_seen_at=now,
    )
    session.add(row)
    await session.commit()
    return row


async def _second_tenant(session) -> dict:
    company = Company(id=uuid4(), name=f"OtherCo-{uuid4().hex[:6]}")
    branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name="Other Main",
        invoice_series_code="OT",
    )
    terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Other Hybrid",
        device_id=f"other-{uuid4()}",
    )
    role = Role(
        id=uuid4(),
        company_id=company.id,
        code="super_owner",
        name="Protected Owner",
        permissions=[],
    )
    user = User(
        id=uuid4(),
        company_id=company.id,
        email=f"other-{uuid4().hex[:8]}@test.local",
        name="Other Owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add_all([company, branch, terminal, role, user])
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=user.id, role_id=role.id))
    await session.commit()
    await session.refresh(user)
    return {"company": company, "branch": branch, "terminal": terminal, "owner": user}


def _request_payload(installation_id: UUID, *, request_id: UUID | None = None) -> dict:
    return {
        "request_id": str(request_id or uuid4()),
        "installation_id": str(installation_id),
        "grant_kind": "anytime",
        "grant_ttl_seconds": 86_400,
        "session_ttl_seconds": 900,
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_consent_session_command_and_audit_contract(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    terminal_id = seed_owner["terminal"].id
    installation_id = installation.installation_id
    admin_headers = _headers(
        seed_owner,
        roles=["super_owner"],
        audit_access=True,
        android=False,
    )
    device_headers = _headers(
        seed_owner,
        roles=["staff"],
        audit_access=False,
        android=True,
    )
    ordinary_headers = _headers(
        seed_owner,
        roles=["staff"],
        audit_access=False,
        android=False,
    )

    denied = await client.get("/api/v1/remote-assistance/devices", headers=ordinary_headers)
    assert denied.status_code == 403

    invalid_header = await client.put(
        f"/api/v1/remote-assistance/device/sessions/{uuid4()}/frame",
        headers={
            **device_headers,
            "Content-Type": "image/jpeg",
            "X-Installation-Id": str(uuid1()),
            "X-Frame-Id": str(uuid4()),
            "X-Frame-Sequence": "1",
            "X-Frame-Width": "640",
            "X-Frame-Height": "480",
            "X-ERP-Frame-Redacted": "true",
        },
        content=b"not-reached",
    )
    assert invalid_header.status_code == 422
    assert invalid_header.json()["error"]["code"] == "validation_error"

    request_id = uuid4()
    request_payload = _request_payload(installation_id, request_id=request_id)
    requested = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=request_payload,
    )
    assert requested.status_code == 200, requested.text
    request_body = requested.json()
    assert request_body["grant"]["id"] == str(request_id)
    assert request_body["grant"]["status"] == "requested"
    assert request_body["grant"]["requested_by_name"] == "Owner"
    support_session_id = UUID(request_body["session"]["id"])

    replay = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=request_payload,
    )
    assert replay.status_code == 200, replay.text
    assert replay.json()["session"]["id"] == str(support_session_id)
    conflicting_request = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json={**request_payload, "grant_ttl_seconds": 3_600},
    )
    assert conflicting_request.status_code == 409

    device_state = await client.get(
        "/api/v1/remote-assistance/device/state",
        headers=device_headers,
        params={"installation_id": str(installation_id)},
    )
    assert device_state.status_code == 200, device_state.text
    assert device_state.json()["pending_grants"][0]["id"] == str(request_id)

    other = await _second_tenant(session)
    other_headers = _headers(
        other,
        roles=["super_owner"],
        audit_access=True,
        android=False,
    )
    other_listing = await client.get(
        "/api/v1/remote-assistance/sessions",
        headers=other_headers,
    )
    assert other_listing.status_code == 200
    assert other_listing.json()["total"] == 0
    cross_tenant_device_state = await client.get(
        "/api/v1/remote-assistance/device/state",
        headers=_headers(
            other,
            roles=["staff"],
            audit_access=False,
            android=True,
        ),
        params={"installation_id": str(installation_id)},
    )
    assert cross_tenant_device_state.status_code == 404

    decision_id = uuid4()
    decision_payload = {
        "installation_id": str(installation_id),
        "decision": "accepted",
        "decision_id": str(decision_id),
    }
    accepted = await client.post(
        f"/api/v1/remote-assistance/device/grants/{request_id}/decision",
        headers=device_headers,
        json=decision_payload,
    )
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["status"] == "active"
    accepted_replay = await client.post(
        f"/api/v1/remote-assistance/device/grants/{request_id}/decision",
        headers=device_headers,
        json=decision_payload,
    )
    assert accepted_replay.status_code == 200

    start_id = uuid4()
    started = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/start",
        headers=admin_headers,
        json={"start_id": str(start_id)},
    )
    assert started.status_code == 200, started.text
    assert started.json()["status"] == "active"
    assert started.json()["expires_at"] is not None
    start_replay = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/start",
        headers=admin_headers,
        json={"start_id": str(start_id)},
    )
    assert start_replay.status_code == 200

    devices = await client.get("/api/v1/remote-assistance/devices", headers=admin_headers)
    assert devices.status_code == 200, devices.text
    item = devices.json()["items"][0]
    assert item["current_grant_id"] == str(request_id)
    assert item["current_grant_kind"] == "anytime"
    assert item["current_grant_responded_by_user_id"] == str(owner_id)
    assert item["current_grant_responded_by_name"] == "Owner"
    assert item["current_grant_responded_at"] is not None
    assert item["current_session_id"] == str(support_session_id)
    assert item["current_session_next_sequence"] == 1

    unsafe_command = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/commands",
        headers=admin_headers,
        json={
            "command_id": str(uuid4()),
            "sequence": 1,
            "type": "navigate",
            "module": "finance",
        },
    )
    assert unsafe_command.status_code == 422

    command_id = uuid4()
    command_payload = {
        "command_id": str(command_id),
        "sequence": 1,
        "type": "navigate",
        "module": "gaming",
    }
    issued = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/commands",
        headers=admin_headers,
        json=command_payload,
    )
    assert issued.status_code == 200, issued.text
    assert issued.json()["status"] == "pending"
    command_replay = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/commands",
        headers=admin_headers,
        json=command_payload,
    )
    assert command_replay.status_code == 200
    command_conflict = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/commands",
        headers=admin_headers,
        json={**command_payload, "module": "help"},
    )
    assert command_conflict.status_code == 409

    polled = await client.get(
        "/api/v1/remote-assistance/device/state",
        headers=device_headers,
        params={"installation_id": str(installation_id)},
    )
    assert polled.status_code == 200, polled.text
    assert polled.json()["commands"][0]["command_id"] == str(command_id)
    resolved = await client.post(
        f"/api/v1/remote-assistance/device/commands/{command_id}/result",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "sequence": 1,
            "outcome": "acknowledged",
            "reason_code": None,
        },
    )
    assert resolved.status_code == 200, resolved.text
    assert resolved.json()["status"] == "acknowledged"
    owner_command_status = await client.get(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/commands/{command_id}",
        headers=admin_headers,
    )
    assert owner_command_status.status_code == 200, owner_command_status.text
    assert owner_command_status.headers["cache-control"] == "private, no-store"
    assert owner_command_status.json()["status"] == "acknowledged"
    cross_tenant_command_status = await client.get(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/commands/{command_id}",
        headers=other_headers,
    )
    assert cross_tenant_command_status.status_code == 404

    end_id = uuid4()
    ended = await client.post(
        f"/api/v1/remote-assistance/device/sessions/{support_session_id}/end",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "end_id": str(end_id),
            "reason": "user_ended",
        },
    )
    assert ended.status_code == 200, ended.text
    assert ended.json()["status"] == "ended"
    end_replay = await client.post(
        f"/api/v1/remote-assistance/device/sessions/{support_session_id}/end",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "end_id": str(end_id),
            "reason": "user_ended",
        },
    )
    assert end_replay.status_code == 200
    conflicting_end = await client.post(
        f"/api/v1/remote-assistance/device/sessions/{support_session_id}/end",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "end_id": str(uuid4()),
            "reason": "user_ended",
        },
    )
    assert conflicting_end.status_code == 409

    later_session_id = uuid4()
    later_session = await client.post(
        "/api/v1/remote-assistance/sessions",
        headers=admin_headers,
        json={
            "session_id": str(later_session_id),
            "installation_id": str(installation_id),
            "grant_id": str(request_id),
            "session_ttl_seconds": 900,
        },
    )
    assert later_session.status_code == 200, later_session.text
    assert later_session.json()["id"] == str(later_session_id)
    late_request_replay = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=request_payload,
    )
    assert late_request_replay.status_code == 200, late_request_replay.text
    assert late_request_replay.json()["session"]["id"] == str(support_session_id)
    final_revoke = await client.post(
        f"/api/v1/remote-assistance/grants/{request_id}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(uuid4())},
    )
    assert final_revoke.status_code == 200, final_revoke.text

    await session.rollback()
    audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == company_id,
                    AuditLog.action.in_(
                        (
                            "remote_assistance.grant.requested",
                            "remote_assistance.grant.accepted",
                            "remote_assistance.session.started",
                            "remote_assistance.command.issued",
                            "remote_assistance.command.acknowledged",
                            "remote_assistance.session.ended",
                        )
                    ),
                )
            )
        )
        .scalars()
        .all()
    )
    assert {row.action for row in audits} >= {
        "remote_assistance.grant.requested",
        "remote_assistance.grant.accepted",
        "remote_assistance.session.started",
        "remote_assistance.command.issued",
        "remote_assistance.command.acknowledged",
        "remote_assistance.session.ended",
    }
    assert all(row.actor_user_id == owner_id for row in audits)
    assert all(row.terminal_id == terminal_id for row in audits)
    assert all(row.after and row.after.get("device_ref") for row in audits)
    assert all(str(installation_id) not in str(row.after) for row in audits)
    assert all("screenshot" not in str(row.after).lower() for row in audits)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_expiration_and_cross_scope_idempotency_are_reconciled(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    installation_db_id = installation.id
    installation_id = installation.installation_id
    admin_headers = _headers(
        seed_owner,
        roles=["super_owner"],
        audit_access=True,
        android=False,
    )
    device_headers = _headers(
        seed_owner,
        roles=["staff"],
        audit_access=False,
        android=True,
    )
    now = datetime.now(UTC)
    expired_grant = RemoteAssistanceGrant(
        id=uuid4(),
        company_id=company_id,
        client_installation_id=installation_db_id,
        requested_by_user_id=owner_id,
        kind="one_time",
        status="requested",
        requested_at=now - timedelta(minutes=10),
        expires_at=now - timedelta(minutes=5),
    )
    expired_session = RemoteAssistanceSession(
        id=uuid4(),
        company_id=company_id,
        grant_id=expired_grant.id,
        client_installation_id=installation_db_id,
        requested_by_user_id=owner_id,
        status="requested",
        duration_seconds=600,
        requested_at=now - timedelta(minutes=10),
        request_expires_at=now - timedelta(minutes=6),
    )
    session.add_all([expired_grant, expired_session])
    await session.commit()

    state = await client.get(
        "/api/v1/remote-assistance/device/state",
        headers=device_headers,
        params={"installation_id": str(installation_id)},
    )
    assert state.status_code == 200, state.text
    assert state.json()["pending_grants"] == []
    assert state.json()["session"]["status"] == "expired"
    await session.rollback()
    await session.refresh(expired_grant)
    await session.refresh(expired_session)
    assert expired_grant.status == "expired"
    assert expired_session.status == "expired"

    first = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=_request_payload(installation_id),
    )
    assert first.status_code == 200, first.text
    first_grant_id = first.json()["grant"]["id"]
    first_session_id = first.json()["session"]["id"]
    reused_action_id = uuid4()
    accepted = await client.post(
        f"/api/v1/remote-assistance/device/grants/{first_grant_id}/decision",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "decision": "accepted",
            "decision_id": str(reused_action_id),
        },
    )
    assert accepted.status_code == 200
    first_start_id = uuid4()
    started = await client.post(
        f"/api/v1/remote-assistance/sessions/{first_session_id}/start",
        headers=admin_headers,
        json={"start_id": str(first_start_id)},
    )
    assert started.status_code == 200, started.text
    revoked = await client.post(
        f"/api/v1/remote-assistance/grants/{first_grant_id}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(uuid4())},
    )
    assert revoked.status_code == 200, revoked.text
    assert revoked.json()["status"] == "revoked"
    accepted_after_revoke = await client.post(
        f"/api/v1/remote-assistance/device/grants/{first_grant_id}/decision",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "decision": "accepted",
            "decision_id": str(reused_action_id),
        },
    )
    assert accepted_after_revoke.status_code == 200, accepted_after_revoke.text
    assert accepted_after_revoke.json()["status"] == "revoked"

    second = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=_request_payload(installation_id),
    )
    assert second.status_code == 200, second.text
    second_grant_id = second.json()["grant"]["id"]
    reused_decision = await client.post(
        f"/api/v1/remote-assistance/device/grants/{second_grant_id}/decision",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "decision": "accepted",
            "decision_id": str(reused_action_id),
        },
    )
    assert reused_decision.status_code == 409

    accepted_second = await client.post(
        f"/api/v1/remote-assistance/device/grants/{second_grant_id}/decision",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "decision": "accepted",
            "decision_id": str(uuid4()),
        },
    )
    assert accepted_second.status_code == 200, accepted_second.text
    reused_start = await client.post(
        f"/api/v1/remote-assistance/sessions/{second.json()['session']['id']}/start",
        headers=admin_headers,
        json={"start_id": str(first_start_id)},
    )
    assert reused_start.status_code == 409
    second_revoke_id = uuid4()
    revoked_second = await client.post(
        f"/api/v1/remote-assistance/grants/{second_grant_id}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(second_revoke_id)},
    )
    assert revoked_second.status_code == 200, revoked_second.text

    unanswered = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=_request_payload(installation_id),
    )
    assert unanswered.status_code == 200, unanswered.text
    unanswered_grant_id = unanswered.json()["grant"]["id"]
    unanswered_revoke_id = uuid4()
    revoked_unanswered = await client.post(
        f"/api/v1/remote-assistance/grants/{unanswered_grant_id}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(unanswered_revoke_id)},
    )
    assert revoked_unanswered.status_code == 200, revoked_unanswered.text
    assert revoked_unanswered.json()["responded_by_user_id"] is None
    assert revoked_unanswered.json()["responded_at"] is None

    fourth = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=_request_payload(installation_id),
    )
    assert fourth.status_code == 200, fourth.text
    reused_revoke = await client.post(
        f"/api/v1/remote-assistance/grants/{fourth.json()['grant']['id']}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(unanswered_revoke_id)},
    )
    assert reused_revoke.status_code == 409

    cleanup = await client.post(
        f"/api/v1/remote-assistance/grants/{fourth.json()['grant']['id']}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(uuid4())},
    )
    assert cleanup.status_code == 200, cleanup.text

    await session.rollback()
    unanswered_row = await session.get(RemoteAssistanceGrant, UUID(unanswered_grant_id))
    assert unanswered_row is not None
    assert unanswered_row.responded_at is None
    assert unanswered_row.responded_by_user_id is None
    assert unanswered_row.decision_id is None
    assert unanswered_row.revoked_by_user_id == owner_id
    assert unanswered_row.revocation_id == unanswered_revoke_id

    # Terminal audit evidence cannot be rewritten later to falsely claim that
    # the device user answered before the owner revoked the request.
    with pytest.raises(IntegrityError):
        await session.execute(
            update(RemoteAssistanceGrant)
            .where(RemoteAssistanceGrant.id == UUID(unanswered_grant_id))
            .values(
                responded_at=datetime.now(UTC),
                responded_by_user_id=owner_id,
                decision_id=uuid4(),
            )
        )
    await session.rollback()

    malformed = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=_request_payload(installation_id),
    )
    assert malformed.status_code == 200, malformed.text
    malformed_grant_id = malformed.json()["grant"]["id"]
    malformed_session_id = malformed.json()["session"]["id"]
    with pytest.raises(IntegrityError):
        await session.execute(
            update(RemoteAssistanceSession)
            .where(RemoteAssistanceSession.id == UUID(malformed_session_id))
            .values(status="active")
        )
    await session.rollback()
    malformed_cleanup = await client.post(
        f"/api/v1/remote-assistance/grants/{malformed_grant_id}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(uuid4())},
    )
    assert malformed_cleanup.status_code == 200, malformed_cleanup.text

    command_count = int(
        (
            await session.execute(
                select(func.count(RemoteAssistanceCommand.id)).where(
                    RemoteAssistanceCommand.company_id == company_id
                )
            )
        ).scalar_one()
    )
    assert command_count == 0


@pytest.mark.integration
@pytest.mark.asyncio
async def test_permission_onboarding_is_online_but_start_requires_available(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    installation_db_id = installation.id
    installation_id = installation.installation_id
    admin_headers = _headers(
        seed_owner,
        roles=["super_owner"],
        audit_access=True,
        android=False,
    )
    device_headers = _headers(
        seed_owner,
        roles=["staff"],
        audit_access=False,
        android=True,
    )
    installation.remote_support_capability = "permission_required"
    installation.remote_support_last_seen_at = datetime.now(UTC)
    await session.commit()

    devices = await client.get("/api/v1/remote-assistance/devices", headers=admin_headers)
    assert devices.status_code == 200, devices.text
    item = devices.json()["items"][0]
    assert item["sharing_capability"] == "permission_required"
    assert item["is_remote_online"] is True

    requested = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json={
            **_request_payload(installation_id),
            "grant_kind": "one_time",
            "grant_ttl_seconds": 900,
        },
    )
    assert requested.status_code == 200, requested.text
    grant_id = requested.json()["grant"]["id"]
    support_session_id = requested.json()["session"]["id"]
    accepted = await client.post(
        f"/api/v1/remote-assistance/device/grants/{grant_id}/decision",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "decision": "accepted",
            "decision_id": str(uuid4()),
        },
    )
    assert accepted.status_code == 200, accepted.text

    blocked_start = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/start",
        headers=admin_headers,
        json={"start_id": str(uuid4())},
    )
    assert blocked_start.status_code == 409

    ready = await client.post(
        "/api/v1/remote-assistance/device/heartbeat",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "protocol_version": 1,
            "sharing_capability": "available",
        },
    )
    assert ready.status_code == 200, ready.text
    started = await client.post(
        f"/api/v1/remote-assistance/sessions/{support_session_id}/start",
        headers=admin_headers,
        json={"start_id": str(uuid4())},
    )
    assert started.status_code == 200, started.text

    # Starting a one-time session consumes its grant.  The still-active
    # session must nevertheless block a second request with a clean 409 rather
    # than falling through to the database partial-unique constraint.
    overlap = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=_request_payload(installation_id),
    )
    assert overlap.status_code == 409

    revoked = await client.post(
        f"/api/v1/remote-assistance/grants/{grant_id}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(uuid4())},
    )
    assert revoked.status_code == 200, revoked.text

    await session.rollback()
    installation = await session.get(ClientInstallation, installation_db_id)
    assert installation is not None
    installation.remote_support_capability = "permission_required"
    installation.remote_support_last_seen_at = datetime.now(UTC) - timedelta(minutes=5)
    await session.commit()

    stale_devices = await client.get(
        "/api/v1/remote-assistance/devices",
        headers=admin_headers,
    )
    assert stale_devices.status_code == 200, stale_devices.text
    assert stale_devices.json()["items"][0]["is_remote_online"] is False
    stale_request = await client.post(
        "/api/v1/remote-assistance/requests",
        headers=admin_headers,
        json=_request_payload(installation_id),
    )
    assert stale_request.status_code == 409


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_exact_retries_return_the_same_request_session_and_command(
    client,
    session,
    seed_owner,
) -> None:
    installation = await _installation(session, seed_owner)
    installation_id = installation.installation_id
    owner_id = seed_owner["owner"].id
    admin_headers = _headers(
        seed_owner,
        roles=["super_owner"],
        audit_access=True,
        android=False,
    )
    device_headers = _headers(
        seed_owner,
        roles=["staff"],
        audit_access=False,
        android=True,
    )

    request_payload = _request_payload(installation_id)
    request_results = await asyncio.gather(
        *(
            client.post(
                "/api/v1/remote-assistance/requests",
                headers=admin_headers,
                json=request_payload,
            )
            for _ in range(2)
        )
    )
    assert [result.status_code for result in request_results] == [200, 200]
    grant_id = request_results[0].json()["grant"]["id"]
    initial_session_id = request_results[0].json()["session"]["id"]
    assert request_results[1].json()["grant"]["id"] == grant_id
    assert request_results[1].json()["session"]["id"] == initial_session_id

    accepted = await client.post(
        f"/api/v1/remote-assistance/device/grants/{grant_id}/decision",
        headers=device_headers,
        json={
            "installation_id": str(installation_id),
            "decision": "accepted",
            "decision_id": str(uuid4()),
        },
    )
    assert accepted.status_code == 200, accepted.text
    started = await client.post(
        f"/api/v1/remote-assistance/sessions/{initial_session_id}/start",
        headers=admin_headers,
        json={"start_id": str(uuid4())},
    )
    assert started.status_code == 200, started.text
    ended = await client.post(
        f"/api/v1/remote-assistance/sessions/{initial_session_id}/end",
        headers=admin_headers,
        json={"end_id": str(uuid4())},
    )
    assert ended.status_code == 200, ended.text

    next_session_id = uuid4()
    session_payload = {
        "session_id": str(next_session_id),
        "installation_id": str(installation_id),
        "grant_id": grant_id,
        "session_ttl_seconds": 900,
    }
    session_results = await asyncio.gather(
        *(
            client.post(
                "/api/v1/remote-assistance/sessions",
                headers=admin_headers,
                json=session_payload,
            )
            for _ in range(2)
        )
    )
    assert [result.status_code for result in session_results] == [200, 200]
    assert {result.json()["id"] for result in session_results} == {str(next_session_id)}

    next_started = await client.post(
        f"/api/v1/remote-assistance/sessions/{next_session_id}/start",
        headers=admin_headers,
        json={"start_id": str(uuid4())},
    )
    assert next_started.status_code == 200, next_started.text
    command_id = uuid4()
    command_payload = {
        "command_id": str(command_id),
        "sequence": 1,
        "type": "sync_now",
        "module": None,
    }
    command_results = await asyncio.gather(
        *(
            client.post(
                f"/api/v1/remote-assistance/sessions/{next_session_id}/commands",
                headers=admin_headers,
                json=command_payload,
            )
            for _ in range(2)
        )
    )
    assert [result.status_code for result in command_results] == [200, 200]
    assert {result.json()["command_id"] for result in command_results} == {str(command_id)}
    assert {result.json()["status"] for result in command_results} == {"pending"}

    revoked = await client.post(
        f"/api/v1/remote-assistance/grants/{grant_id}/revoke",
        headers=admin_headers,
        json={"revoke_id": str(uuid4())},
    )
    assert revoked.status_code == 200, revoked.text
    rejected = await client.get(
        f"/api/v1/remote-assistance/sessions/{next_session_id}/commands/{command_id}",
        headers=admin_headers,
    )
    assert rejected.status_code == 200, rejected.text
    assert rejected.json()["status"] == "rejected"
    assert rejected.json()["rejection_reason_code"] == "session_ended"
    assert rejected.json()["resolved_by_user_id"] == str(owner_id)
