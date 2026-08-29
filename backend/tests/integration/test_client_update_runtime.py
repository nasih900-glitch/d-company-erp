from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.api.v1.client_installations import router as installation_router_module
from app.api.v1.client_updates import router as release_router_module
from app.core.config import get_settings
from app.core.db import AsyncSessionLocal
from app.core.errors import RateLimitError
from app.core.security import hash_password, issue_access_token
from app.models import (
    AndroidRelease,
    AuditLog,
    Branch,
    ClientInstallation,
    ClientUpdateEvent,
    Company,
    Role,
    Terminal,
    User,
    UserRole,
)
from app.models.client_update import CLIENT_INSTALLATIONS_MAX_PER_USER
from app.services.client_updates.releases import AndroidReleaseManifest
from scripts.register_android_release import register


@pytest_asyncio.fixture(autouse=True)
async def require_client_update_migration(session) -> None:
    try:
        await session.execute(text("select 1 from client_installations limit 1"))
        await session.execute(text("select 1 from android_releases limit 1"))
    except Exception as exc:
        pytest.skip(f"client-update migration/local Postgres unavailable: {exc}")


@pytest.fixture(autouse=True)
def bypass_external_heartbeat_rate_limiter(monkeypatch) -> None:
    async def allow(**_kwargs) -> None:
        return None

    monkeypatch.setattr(
        installation_router_module,
        "enforce_client_heartbeat_rate_limit",
        allow,
    )


def _headers(seed_owner, *, roles: list[str], audit_access: bool = False) -> dict[str, str]:
    owner = seed_owner["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=owner.company_id,
        roles=roles,
        branch_id=seed_owner["branch"].id,
        auth_version=owner.auth_version,
        extra={
            "protected_access": "co_owner" in roles or "super_owner" in roles,
            "audit_access": audit_access,
        },
    )
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "8",
    }


def _heartbeat(installation_id, event_id=None) -> dict:
    events = []
    if event_id is not None:
        events.append(
            {
                "client_event_id": str(event_id),
                "event_type": "update_offered",
                "target_version_name": "3.1.4",
                "target_version_code": 15,
                "error_code": None,
                "occurred_at": datetime.now(UTC).isoformat(),
            }
        )
    return {
        "installation_id": str(installation_id),
        "platform": "android",
        "distribution_channel": "direct",
        "version_name": "3.1.3",
        "version_code": 14,
        "pending_outbox_count": 2,
        "last_successful_sync_at": datetime.now(UTC).isoformat(),
        "update_state": "update_available",
        "update_error_code": None,
        "events": events,
    }


async def _second_tenant(session) -> tuple[Company, Branch, Terminal, User]:
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
        code="owner",
        name="Owner",
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
    return company, branch, terminal, user


@pytest.mark.integration
@pytest.mark.asyncio
async def test_heartbeat_is_tenant_scoped_idempotent_and_updates_terminal(
    client,
    session,
    seed_owner,
) -> None:
    installation_id = uuid4()
    event_id = uuid4()
    owner_headers = _headers(seed_owner, roles=["owner"])
    staff_headers = _headers(seed_owner, roles=["staff"])
    company_id = seed_owner["company"].id
    terminal_id = seed_owner["terminal"].id
    terminal_name = seed_owner["terminal"].name
    owner_id = seed_owner["owner"].id
    heartbeat_payload = _heartbeat(installation_id, event_id)

    first = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=owner_headers,
        json=heartbeat_payload,
    )
    assert first.status_code == 200, first.text
    assert first.json()["accepted_event_count"] == 1
    assert first.json()["duplicate_event_count"] == 0
    assert first.json()["terminal_id"] == str(terminal_id)

    replay = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=owner_headers,
        json=heartbeat_payload,
    )
    assert replay.status_code == 200, replay.text
    assert replay.json()["accepted_event_count"] == 0
    assert replay.json()["duplicate_event_count"] == 1

    conflicting_event = {
        **heartbeat_payload,
        "events": [
            {
                **heartbeat_payload["events"][0],
                "event_type": "download_started",
            }
        ],
    }
    conflict = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=owner_headers,
        json=conflicting_event,
    )
    assert conflict.status_code == 409, conflict.text

    await session.rollback()
    installation = (
        await session.execute(
            select(ClientInstallation).where(
                ClientInstallation.company_id == company_id,
                ClientInstallation.installation_id == installation_id,
            )
        )
    ).scalar_one()
    events = (
        (
            await session.execute(
                select(ClientUpdateEvent).where(
                    ClientUpdateEvent.company_id == company_id,
                    ClientUpdateEvent.client_installation_id == installation.id,
                )
            )
        )
        .scalars()
        .all()
    )
    await session.refresh(seed_owner["terminal"])
    assert len(events) == 1
    assert events[0].actor_user_id == owner_id
    assert events[0].terminal_id == terminal_id
    assert installation.last_user_id == owner_id
    assert seed_owner["terminal"].last_seen_at is not None

    listing = await client.get("/api/v1/client-installations", headers=owner_headers)
    assert listing.status_code == 200, listing.text
    assert listing.headers["Cache-Control"] == "private, no-store"
    assert listing.json()["total"] == 1
    assert listing.json()["items"][0]["installation_id"] == str(installation_id)
    assert listing.json()["items"][0]["terminal_name"] == terminal_name

    denied = await client.get(
        "/api/v1/client-installations",
        headers=staff_headers,
    )
    assert denied.status_code == 403


@pytest.mark.integration
@pytest.mark.asyncio
async def test_heartbeat_rotation_is_concurrency_bounded_and_retries_remain_idempotent(
    client,
    session,
    seed_owner,
) -> None:
    headers = _headers(seed_owner, roles=["staff"])
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    installation_ids = [uuid4() for _ in range(CLIENT_INSTALLATIONS_MAX_PER_USER + 4)]

    responses = await asyncio.gather(
        *(
            client.post(
                "/api/v1/client-installations/heartbeat",
                headers=headers,
                json=_heartbeat(installation_id),
            )
            for installation_id in installation_ids
        )
    )
    accepted = [response for response in responses if response.status_code == 200]
    rejected = [response for response in responses if response.status_code == 409]
    assert len(accepted) == CLIENT_INSTALLATIONS_MAX_PER_USER
    assert len(rejected) == 4
    assert {response.json()["error"]["code"] for response in rejected} == {
        "client_telemetry_capacity"
    }
    assert {response.json()["error"]["details"]["scope"] for response in rejected} == {"user"}

    await session.rollback()
    rows = (
        (
            await session.execute(
                select(ClientInstallation).where(
                    ClientInstallation.company_id == company_id,
                    ClientInstallation.registered_by_user_id == owner_id,
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == CLIENT_INSTALLATIONS_MAX_PER_USER

    # Reusing an admitted installation remains valid at capacity; only new
    # random IDs are rejected. This is the normal retry/reconnect behavior.
    replay = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=headers,
        json=_heartbeat(rows[0].installation_id),
    )
    assert replay.status_code == 200, replay.text

    # API admission and the database trigger share the same ceiling. A caller
    # bypassing the route must still be unable to grow the immutable ledger.
    async with AsyncSessionLocal() as direct_session:
        direct_session.add(
            ClientInstallation(
                company_id=company_id,
                installation_id=uuid4(),
                registered_by_user_id=owner_id,
                last_user_id=owner_id,
                terminal_id=seed_owner["terminal"].id,
                platform="android",
                distribution_channel="direct",
                version_name="3.1.3",
                version_code=14,
                pending_outbox_count=0,
                last_successful_sync_at=datetime.now(UTC),
                update_state="idle",
                update_error_code=None,
            )
        )
        with pytest.raises(DBAPIError) as direct_error:
            await direct_session.commit()
        assert "client installation user capacity reached" in str(direct_error.value.orig)
        await direct_session.rollback()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_heartbeat_rate_limit_returns_retryable_429(
    client,
    seed_owner,
    monkeypatch,
) -> None:
    async def deny(**_kwargs) -> None:
        raise RateLimitError(
            "Too many device status updates were received. Wait briefly and retry.",
            details={"limit": 30, "window_seconds": 60, "retry_after_seconds": 17},
            headers={"Retry-After": "17"},
        )

    monkeypatch.setattr(
        installation_router_module,
        "enforce_client_heartbeat_rate_limit",
        deny,
    )
    response = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=_headers(seed_owner, roles=["staff"]),
        json=_heartbeat(uuid4()),
    )
    assert response.status_code == 429, response.text
    assert response.headers["Retry-After"] == "17"
    assert response.json()["error"]["code"] == "rate_limited"
    assert response.json()["error"]["details"]["retry_after_seconds"] == 17


@pytest.mark.integration
@pytest.mark.asyncio
async def test_same_random_installation_uuid_is_isolated_per_company(
    client,
    session,
    seed_owner,
) -> None:
    installation_id = uuid4()
    first_company_id = seed_owner["company"].id
    first_headers = _headers(seed_owner, roles=["owner"])
    first = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=first_headers,
        json=_heartbeat(installation_id),
    )
    assert first.status_code == 200, first.text

    company, branch, terminal, user = await _second_tenant(session)
    second_company_id = company.id
    token = issue_access_token(
        user_id=user.id,
        company_id=company.id,
        roles=["owner"],
        branch_id=branch.id,
        auth_version=user.auth_version,
    )
    other_headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal.id),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "8",
    }
    second = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=other_headers,
        json=_heartbeat(installation_id),
    )
    assert second.status_code == 200, second.text

    await session.rollback()
    rows = (
        (
            await session.execute(
                select(ClientInstallation).where(
                    ClientInstallation.installation_id == installation_id
                )
            )
        )
        .scalars()
        .all()
    )
    assert {row.company_id for row in rows} == {first_company_id, second_company_id}
    first_list = await client.get(
        "/api/v1/client-installations",
        headers=first_headers,
    )
    second_list = await client.get("/api/v1/client-installations", headers=other_headers)
    assert {item["installation_id"] for item in first_list.json()["items"]} == {
        str(installation_id)
    }
    assert {item["installation_id"] for item in second_list.json()["items"]} == {
        str(installation_id)
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_rejects_cross_tenant_or_mutated_update_evidence(
    client,
    session,
    seed_owner,
) -> None:
    installation_id = uuid4()
    event_id = uuid4()
    company_id = seed_owner["company"].id
    owner_headers = _headers(seed_owner, roles=["owner"])
    created = await client.post(
        "/api/v1/client-installations/heartbeat",
        headers=owner_headers,
        json=_heartbeat(installation_id, event_id),
    )
    assert created.status_code == 200, created.text
    await session.rollback()
    installation = (
        await session.execute(
            select(ClientInstallation).where(
                ClientInstallation.company_id == company_id,
                ClientInstallation.installation_id == installation_id,
            )
        )
    ).scalar_one()
    event = (
        await session.execute(
            select(ClientUpdateEvent).where(
                ClientUpdateEvent.client_installation_id == installation.id
            )
        )
    ).scalar_one()
    other_company, _, _, _ = await _second_tenant(session)
    other_company_id = other_company.id

    async with AsyncSessionLocal() as tamper:
        with pytest.raises(DBAPIError):
            await tamper.execute(
                text("UPDATE client_installations SET company_id = :company WHERE id = :id"),
                {"company": other_company_id, "id": installation.id},
            )
        await tamper.rollback()

        with pytest.raises(DBAPIError):
            await tamper.execute(
                text(
                    "UPDATE client_update_events SET event_type = 'download_started' WHERE id = :id"
                ),
                {"id": event.id},
            )
        await tamper.rollback()

        with pytest.raises(DBAPIError):
            await tamper.execute(
                text("DELETE FROM client_update_events WHERE id = :id"), {"id": event.id}
            )
        await tamper.rollback()


def _release(*, version_code: int, version_name: str) -> AndroidRelease:
    return AndroidRelease(
        id=uuid4(),
        channel="direct",
        version_code=version_code,
        version_name=version_name,
        update_url=(
            "https://dcompany.duckdns.org/downloads/android/"
            f"D-COMPANY-ERP-{version_name}-code{version_code}.apk"
        ),
        release_notes=f"Verified release {version_name}",
        apk_sha256=(f"{version_code % 16:x}" * 64),
        apk_size_bytes=12_345 + version_code,
        apk_signing_cert_sha256="cd" * 32,
        manifest_sha256=(f"{(version_code + 1) % 16:x}" * 64),
        source_git_sha=(f"{(version_code + 2) % 16:x}" * 40),
        source_release_ref=f"v{version_name}",
        source_workflow_run_id=1_000_000 + version_code,
        source_workflow_run_attempt=1,
        status="staged",
    )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_only_protected_owner_can_activate_and_public_offer_tracks_active_release(
    client,
    session,
    seed_owner,
    monkeypatch,
) -> None:
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    terminal_id = seed_owner["terminal"].id
    first_release = _release(version_code=15, version_name="3.1.4")
    second_release = _release(version_code=16, version_name="3.1.5")
    session.add_all([first_release, second_release])
    await session.commit()

    settings = get_settings()
    monkeypatch.setattr(
        settings,
        "android_update_allowed_origin",
        "https://dcompany.duckdns.org",
    )
    monkeypatch.setattr(settings, "android_release_controller_bindings", "")

    verified: list[int] = []

    async def _verified(release, **_kwargs) -> None:
        verified.append(release.version_code)

    monkeypatch.setattr(release_router_module, "verify_public_apk", _verified)

    co_owner_headers = _headers(seed_owner, roles=["co_owner"], audit_access=False)
    denied = await client.post(
        f"/api/v1/client-updates/android/releases/{first_release.id}/activate",
        headers=co_owner_headers,
    )
    assert denied.status_code == 403
    denied_list = await client.get(
        "/api/v1/client-updates/android/releases",
        headers=co_owner_headers,
    )
    assert denied_list.status_code == 403

    protected_headers = _headers(seed_owner, roles=["super_owner"], audit_access=True)
    unassigned = await client.get(
        "/api/v1/client-updates/android/releases",
        headers=protected_headers,
    )
    assert unassigned.status_code == 403
    unassigned_activate = await client.post(
        f"/api/v1/client-updates/android/releases/{first_release.id}/activate",
        headers=protected_headers,
    )
    assert unassigned_activate.status_code == 403
    unassigned_withdraw = await client.post(
        f"/api/v1/client-updates/android/releases/{first_release.id}/withdraw",
        headers=protected_headers,
    )
    assert unassigned_withdraw.status_code == 403
    assert verified == []
    unassigned_me = await client.get("/api/v1/auth/me", headers=protected_headers)
    assert unassigned_me.status_code == 200, unassigned_me.text
    assert unassigned_me.json()["audit_access"] is True
    assert unassigned_me.json()["release_control_access"] is False

    other_company, other_branch, other_terminal, other_user = await _second_tenant(session)
    other_token = issue_access_token(
        user_id=other_user.id,
        company_id=other_company.id,
        roles=["super_owner"],
        branch_id=other_branch.id,
        auth_version=other_user.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    other_headers = {
        "Authorization": f"Bearer {other_token}",
        "X-Terminal-Id": str(other_terminal.id),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "8",
    }

    monkeypatch.setattr(
        settings,
        "android_release_controller_bindings",
        f"{company_id}:{owner_id}",
    )
    other_denied = await client.get(
        "/api/v1/client-updates/android/releases",
        headers=other_headers,
    )
    assert other_denied.status_code == 403
    other_me = await client.get("/api/v1/auth/me", headers=other_headers)
    assert other_me.status_code == 200, other_me.text
    assert other_me.json()["audit_access"] is True
    assert other_me.json()["release_control_access"] is False

    assigned_me = await client.get("/api/v1/auth/me", headers=protected_headers)
    assert assigned_me.status_code == 200, assigned_me.text
    assert assigned_me.json()["release_control_access"] is True
    release_list = await client.get(
        "/api/v1/client-updates/android/releases",
        headers=protected_headers,
    )
    assert release_list.status_code == 200, release_list.text
    assert release_list.headers["Cache-Control"] == "private, no-store"
    assert release_list.json()["total"] == 2
    first_read = next(item for item in release_list.json()["items"] if item["version_code"] == 15)
    assert first_read["source_git_sha"] == first_release.source_git_sha
    assert first_read["source_release_ref"] == "v3.1.4"
    assert first_read["source_workflow_run_id"] == str(first_release.source_workflow_run_id)
    assert first_read["source_workflow_run_attempt"] == 1
    activated = await client.post(
        f"/api/v1/client-updates/android/releases/{first_release.id}/activate",
        headers=protected_headers,
    )
    assert activated.status_code == 200, activated.text
    assert activated.json()["status"] == "active"
    assert verified == [15]

    contract = await client.get(
        "/api/v1/public/client-compatibility",
        params={"platform": "android", "version_code": 14},
    )
    assert contract.status_code == 200, contract.text
    assert contract.headers["Cache-Control"] == "no-store"
    assert contract.headers["X-Client-Compatibility-Policy-Revision"] == "1"
    assert contract.json()["status"] == "update_available"
    assert contract.json()["policy_revision"] == 1
    assert contract.json()["latest_version_code"] == 15
    assert contract.json()["update_url"] == first_release.update_url

    promoted = await client.post(
        f"/api/v1/client-updates/android/releases/{second_release.id}/activate",
        headers=protected_headers,
    )
    assert promoted.status_code == 200, promoted.text
    assert promoted.json()["status"] == "active"
    assert verified == [15, 16]

    await session.rollback()
    await session.refresh(first_release)
    await session.refresh(second_release)
    assert first_release.status == "withdrawn"
    assert second_release.status == "active"
    audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == company_id,
                    AuditLog.entity_type == "AndroidRelease",
                )
            )
        )
        .scalars()
        .all()
    )
    assert {row.action for row in audits} >= {
        "android_release_activate",
        "android_release_auto_withdraw",
    }
    assert all(row.terminal_id == terminal_id for row in audits)

    withdrawn = await client.post(
        f"/api/v1/client-updates/android/releases/{second_release.id}/withdraw",
        headers=protected_headers,
    )
    assert withdrawn.status_code == 200, withdrawn.text
    assert withdrawn.json()["status"] == "withdrawn"
    no_offer = await client.get(
        "/api/v1/public/client-compatibility",
        params={"platform": "android", "version_code": 14},
    )
    assert no_offer.status_code == 200
    assert no_offer.json()["status"] == "supported"
    assert no_offer.json()["policy_revision"] == 1
    assert no_offer.json()["update_url"] is None

    rolled_back = await client.post(
        f"/api/v1/client-updates/android/releases/{first_release.id}/activate",
        headers=protected_headers,
    )
    assert rolled_back.status_code == 200, rolled_back.text
    assert rolled_back.json()["status"] == "active"
    assert verified == [15, 16, 15]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_release_registration_is_idempotent_and_rejects_conflicting_metadata(
    monkeypatch,
) -> None:
    settings = get_settings()
    monkeypatch.setattr(
        settings,
        "android_update_allowed_origin",
        "https://dcompany.duckdns.org",
    )
    manifest = AndroidReleaseManifest.model_validate(
        {
            "version_code": 17,
            "version_name": "3.1.6",
            "channel": "direct",
            "update_url": (
                "https://dcompany.duckdns.org/downloads/android/D-COMPANY-ERP-3.1.6-code17.apk"
            ),
            "release_notes": "Registration idempotency verification",
            "apk_sha256": "ab" * 32,
            "apk_size_bytes": 123_456,
            "apk_signing_cert_sha256": "cd" * 32,
            "source_git_sha": "de" * 20,
            "source_release_ref": "v3.1.6",
            "source_workflow_run_id": 9_007_199_254_740_993,
            "source_workflow_run_attempt": 2,
        }
    )

    first = await register(manifest)
    replay = await register(manifest)
    assert replay == first
    assert first["status"] == "staged"

    conflicting = manifest.model_copy(update={"release_notes": "Different immutable notes"})
    with pytest.raises(SystemExit, match="different metadata"):
        await register(conflicting)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_prevents_release_metadata_rewrite_and_delete(
    session,
) -> None:
    release = _release(version_code=18, version_name="3.1.7")
    session.add(release)
    await session.commit()

    async with AsyncSessionLocal() as tamper:
        with pytest.raises(DBAPIError):
            await tamper.execute(
                text("UPDATE android_releases SET apk_sha256 = :hash WHERE id = :id"),
                {"hash": "ef" * 32, "id": release.id},
            )
        await tamper.rollback()

        with pytest.raises(DBAPIError):
            await tamper.execute(
                text("UPDATE android_releases SET source_git_sha = :sha WHERE id = :id"),
                {"sha": "ef" * 20, "id": release.id},
            )
        await tamper.rollback()

        with pytest.raises(DBAPIError):
            await tamper.execute(
                text("DELETE FROM android_releases WHERE id = :id"), {"id": release.id}
            )
        await tamper.rollback()
