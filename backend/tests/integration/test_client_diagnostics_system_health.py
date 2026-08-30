"""Tenant, privacy, idempotency, and owner-health proof for client diagnostics."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, select, text, update
from sqlalchemy.exc import DBAPIError

from app.api.v1.client_diagnostics import router as diagnostics_router_module
from app.core.security import hash_password, issue_access_token
from app.models import (
    Branch,
    ClientDiagnosticEvent,
    ClientInstallation,
    Company,
    Role,
    Terminal,
    User,
    UserRole,
)
from app.workers import support_attachments as retention_worker


@pytest_asyncio.fixture(autouse=True)
async def require_client_diagnostics_migration(session) -> None:
    try:
        await session.execute(text("select 1 from client_diagnostic_events limit 1"))
    except Exception as exc:
        pytest.skip(f"client diagnostics migration/local Postgres unavailable: {exc}")


@pytest.fixture(autouse=True)
def bypass_external_diagnostic_rate_limiter(monkeypatch) -> None:
    async def allow(**_kwargs) -> None:
        return None

    monkeypatch.setattr(
        diagnostics_router_module,
        "enforce_client_diagnostic_rate_limit",
        allow,
    )


def _headers(
    seed_owner,
    *,
    roles: list[str],
    audit_access: bool = False,
) -> dict[str, str]:
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
        "X-Client-Version-Code": "15",
    }


def _event(
    event_id,
    *,
    event_type: str = "crash",
    severity: str = "critical",
    reason_code: str = "uncaught_exception",
) -> dict[str, object]:
    return {
        "client_event_id": str(event_id),
        "event_type": event_type,
        "severity": severity,
        "occurred_at": datetime.now(UTC).isoformat(),
        "version_name": "3.1.4",
        "version_code": 15,
        "os_api_level": 35,
        "component": "app" if event_type in {"crash", "anr"} else "sync",
        "reason_code": reason_code,
        "failure_fingerprint": "a" * 64 if event_type in {"crash", "anr"} else None,
        "http_status": 503 if event_type == "api_failure" else None,
        "duration_bucket": "30s_to_2m" if event_type in {"anr", "sync_stall"} else None,
        "connectivity": "offline" if event_type == "sync_stall" else "online",
        "pending_outbox_count": 4 if event_type == "sync_stall" else 0,
    }


async def _second_tenant(session) -> dict[str, object]:
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
    return {
        "company": company,
        "branch": branch,
        "terminal": terminal,
        "owner": user,
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_offline_batch_is_idempotent_private_and_tenant_scoped(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    terminal_id = seed_owner["terminal"].id
    installation_id = uuid4()
    event_id = uuid4()
    payload = {
        "installation_id": str(installation_id),
        "events": [_event(event_id)],
    }
    staff_headers = _headers(seed_owner, roles=["staff"])
    protected_headers = _headers(
        seed_owner,
        roles=["super_owner"],
        audit_access=True,
    )

    first = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=staff_headers,
        json=payload,
    )
    assert first.status_code == 200, first.text
    assert first.json()["accepted_event_ids"] == [str(event_id)]
    assert first.json()["duplicate_event_ids"] == []

    replay = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=staff_headers,
        json=payload,
    )
    assert replay.status_code == 200, replay.text
    assert replay.json()["accepted_event_ids"] == []
    assert replay.json()["duplicate_event_ids"] == [str(event_id)]

    conflicting = {
        **payload,
        "events": [{**payload["events"][0], "reason_code": "different_failure"}],
    }
    conflict = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=staff_headers,
        json=conflicting,
    )
    assert conflict.status_code == 409, conflict.text
    assert conflict.json()["error"]["code"] == "diagnostic_idempotency_conflict"
    assert conflict.json()["error"]["details"] == {"client_event_id": str(event_id)}
    assert "retry-after" not in conflict.headers

    unsafe = {
        **payload,
        "events": [
            {
                **payload["events"][0],
                "client_event_id": str(uuid4()),
                "reason_code": "Bearer secret-token /api/orders?customer=1",
                "raw_log": "password=do-not-store",
            }
        ],
    }
    rejected = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=staff_headers,
        json=unsafe,
    )
    assert rejected.status_code == 422, rejected.text
    assert "do-not-store" not in rejected.text
    assert "secret-token" not in rejected.text

    await session.rollback()
    row = (
        await session.execute(
            select(ClientDiagnosticEvent).where(
                ClientDiagnosticEvent.company_id == company_id,
                ClientDiagnosticEvent.installation_id == installation_id,
                ClientDiagnosticEvent.client_event_id == event_id,
            )
        )
    ).scalar_one()
    assert row.actor_user_id == owner_id
    assert row.terminal_id == terminal_id
    assert row.reason_code == "uncaught_exception"

    other = await _second_tenant(session)
    other_headers = _headers(other, roles=["super_owner"], audit_access=True)
    other_ingest = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=other_headers,
        json=payload,
    )
    assert other_ingest.status_code == 200, other_ingest.text
    assert other_ingest.json()["accepted_event_ids"] == [str(event_id)]

    main_list = await client.get(
        "/api/v1/client-diagnostics/events",
        headers=protected_headers,
    )
    assert main_list.status_code == 200, main_list.text
    assert main_list.headers["cache-control"] == "private, no-store"
    assert main_list.json()["total"] == 1
    assert main_list.json()["items"][0]["reason_code"] == "uncaught_exception"
    assert "message" not in main_list.json()["items"][0]
    assert "device_model" not in main_list.json()["items"][0]

    other_list = await client.get(
        "/api/v1/client-diagnostics/events",
        headers=other_headers,
    )
    assert other_list.status_code == 200, other_list.text
    assert other_list.json()["total"] == 1
    assert other_list.json()["items"][0]["id"] != main_list.json()["items"][0]["id"]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_summary_and_system_health_are_protected_and_sanitized(
    client,
    session,
    seed_owner,
    monkeypatch,
) -> None:
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    terminal_id = seed_owner["terminal"].id

    async def redis_operational() -> str:
        return "operational"

    monkeypatch.setattr(
        diagnostics_router_module,
        "_redis_dependency_status",
        redis_operational,
    )
    installation_id = uuid4()
    staff_headers = _headers(seed_owner, roles=["staff"])
    ordinary_owner_headers = _headers(seed_owner, roles=["owner"])
    protected_headers = _headers(
        seed_owner,
        roles=["super_owner"],
        audit_access=True,
    )
    payload = {
        "installation_id": str(installation_id),
        "events": [
            _event(uuid4()),
            _event(
                uuid4(),
                event_type="sync_stall",
                severity="error",
                reason_code="outbox_no_progress",
            ),
        ],
    }
    ingested = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=staff_headers,
        json=payload,
    )
    assert ingested.status_code == 200, ingested.text

    now = datetime.now(UTC)
    session.add(
        ClientInstallation(
            id=uuid4(),
            company_id=company_id,
            installation_id=installation_id,
            registered_by_user_id=owner_id,
            last_user_id=owner_id,
            terminal_id=terminal_id,
            platform="android",
            distribution_channel="direct",
            version_name="3.1.4",
            version_code=15,
            pending_outbox_count=4,
            last_successful_sync_at=now - timedelta(hours=3),
            update_state="idle",
            update_error_code=None,
            last_seen_at=now,
        )
    )
    session.add(
        ClientInstallation(
            id=uuid4(),
            company_id=company_id,
            installation_id=uuid4(),
            registered_by_user_id=owner_id,
            last_user_id=owner_id,
            terminal_id=terminal_id,
            platform="android",
            distribution_channel="direct",
            version_name="3.1.3",
            version_code=14,
            pending_outbox_count=9,
            last_successful_sync_at=now - timedelta(days=3),
            update_state="idle",
            update_error_code=None,
            last_seen_at=now - timedelta(days=2),
        )
    )
    await session.commit()

    for path in (
        "/api/v1/client-diagnostics/events",
        "/api/v1/client-diagnostics/summary",
        "/api/v1/client-diagnostics/system-health",
    ):
        forbidden = await client.get(path, headers=ordinary_owner_headers)
        assert forbidden.status_code == 403, (path, forbidden.text)

    summary = await client.get(
        "/api/v1/client-diagnostics/summary",
        headers=protected_headers,
    )
    assert summary.status_code == 200, summary.text
    body = summary.json()
    assert body["total"] == 2
    assert body["critical_count"] == 1
    assert body["affected_installations"] == 1
    assert body["offline_event_count"] == 1
    assert body["counts_by_type"]["crash"] == 1
    assert body["counts_by_type"]["sync_stall"] == 1
    assert body["counts_by_type"]["anr"] == 0

    health = await client.get(
        "/api/v1/client-diagnostics/system-health",
        headers=protected_headers,
    )
    assert health.status_code == 200, health.text
    assert health.headers["cache-control"] == "private, no-store"
    health_body = health.json()
    assert health_body["status"] == "action_required"
    assert health_body["dependencies"] == {
        "api": "operational",
        "database": "operational",
        "redis": "operational",
    }
    assert health_body["backups"] == {
        "status": "unknown",
        "last_success_at": None,
        "restore_tested_at": None,
        "evidence_code": "host_monitor_not_connected",
    }
    assert health_body["devices"]["total"] == 2
    assert health_body["devices"]["stale"] == 1
    assert health_body["devices"]["with_pending_sync"] == 2
    # Pending work and old heartbeat timestamps do not imply a stall. This is
    # one only because the client explicitly uploaded a recent sync_stall.
    assert health_body["devices"]["sync_stalled"] == 1
    assert health_body["diagnostics"]["total"] == 2
    serialized = health.text.lower()
    assert "postgresql" not in serialized
    assert "redis://" not in serialized
    assert "password" not in serialized
    assert "stack" not in serialized


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_guards_recent_diagnostics_and_cross_tenant_attribution(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    terminal_id = seed_owner["terminal"].id
    event_id = uuid4()
    installation_id = uuid4()
    ingested = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=_headers(seed_owner, roles=["staff"]),
        json={
            "installation_id": str(installation_id),
            "events": [_event(event_id)],
        },
    )
    assert ingested.status_code == 200, ingested.text

    await session.rollback()
    row_id = (
        await session.execute(
            select(ClientDiagnosticEvent.id).where(
                ClientDiagnosticEvent.company_id == company_id,
                ClientDiagnosticEvent.client_event_id == event_id,
            )
        )
    ).scalar_one()
    with pytest.raises(DBAPIError):
        await session.execute(
            update(ClientDiagnosticEvent)
            .where(ClientDiagnosticEvent.id == row_id)
            .values(reason_code="rewritten")
        )
    await session.rollback()

    with pytest.raises(DBAPIError):
        await session.execute(
            delete(ClientDiagnosticEvent).where(ClientDiagnosticEvent.id == row_id)
        )
    await session.rollback()

    other = await _second_tenant(session)
    session.add(
        ClientDiagnosticEvent(
            id=uuid4(),
            company_id=company_id,
            installation_id=uuid4(),
            client_event_id=uuid4(),
            actor_user_id=other["owner"].id,
            terminal_id=terminal_id,
            event_type="crash",
            severity="critical",
            component="app",
            reason_code="uncaught_exception",
            failure_fingerprint="b" * 64,
            version_name="3.1.4",
            version_code=15,
            os_api_level=35,
            http_status=None,
            duration_bucket=None,
            connectivity="online",
            pending_outbox_count=0,
            occurred_at=datetime.now(UTC),
        )
    )
    with pytest.raises(DBAPIError):
        await session.flush()
    await session.rollback()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_scheduled_worker_globally_purges_expired_diagnostics_without_new_upload(
    client,
    session,
    seed_owner,
) -> None:
    event_id = uuid4()
    ingested = await client.post(
        "/api/v1/client-diagnostics/events",
        headers=_headers(seed_owner, roles=["staff"]),
        json={
            "installation_id": str(uuid4()),
            "events": [_event(event_id)],
        },
    )
    assert ingested.status_code == 200, ingested.text

    await session.rollback()
    # Production has no timestamp rewrite path. This narrowly prepares an
    # expired row while proving the real migration trigger is re-enabled in
    # the same transaction before the scheduled worker runs.
    await session.execute(
        text(
            "ALTER TABLE client_diagnostic_events "
            "DISABLE TRIGGER trg_client_diagnostic_events_immutable_scope"
        )
    )
    await session.execute(
        update(ClientDiagnosticEvent)
        .where(ClientDiagnosticEvent.client_event_id == event_id)
        .values(received_at=datetime.now(UTC) - timedelta(days=91))
    )
    await session.execute(
        text(
            "ALTER TABLE client_diagnostic_events "
            "ENABLE TRIGGER trg_client_diagnostic_events_immutable_scope"
        )
    )
    await session.commit()

    _support_rows, _released_bytes, diagnostic_rows = await retention_worker.run(
        batch_size=10,
        max_rows=10,
    )
    assert diagnostic_rows == 1
    assert (
        await session.execute(
            select(ClientDiagnosticEvent.id).where(
                ClientDiagnosticEvent.client_event_id == event_id
            )
        )
    ).scalar_one_or_none() is None
