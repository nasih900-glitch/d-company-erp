"""PostgreSQL proof for authenticated audit request provenance."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select, text

from app.core.roles import PROTECTED_OWNER_ROLE
from app.models import AuditLog, Role
from app.services.audit.recorder import (
    clear_actor,
    clear_request_context,
    install_audit_listeners,
)


@pytest_asyncio.fixture(autouse=True)
async def require_local_postgres(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.mark.asyncio
async def test_authenticated_mutation_records_provenance_and_legacy_rows_remain_readable(
    client,
    session,
    seed_owner,
) -> None:
    role = (
        await session.execute(
            select(Role).where(
                Role.company_id == seed_owner["company"].id,
                Role.code == "owner",
            )
        )
    ).scalar_one()
    role.code = PROTECTED_OWNER_ROLE
    await session.commit()
    install_audit_listeners()

    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
        headers={"X-Forwarded-For": "198.51.100.250"},
    )
    assert login.status_code == 200, login.text
    token = login.json()["access_token"]

    login_audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.actor_user_id == seed_owner["owner"].id,
                AuditLog.action == "login_success",
            )
        )
    ).scalar_one()
    assert login_audit.request_id is not None
    assert login_audit.client_platform == "web"
    assert login_audit.client_was_offline is False
    assert login_audit.terminal_id is None
    assert login_audit.ip is not None
    assert login_audit.ip.startswith("2001:db8:")
    assert login_audit.ip != "198.51.100.250"

    request_id = f"audit-{uuid4().hex}"
    action_id = f"asset-local-{uuid4().hex}"
    idempotency_key = f"asset-audit-{uuid4().hex}"
    reason = "Approved replacement after compressor failure"
    before_request = datetime.now(UTC)
    create = await client.post(
        "/api/v1/finance/assets",
        json={
            "branch_id": str(seed_owner["branch"].id),
            "name": "Audit provenance test asset",
            "type": "coffee_machine",
            "purchase_minor": 125_000,
            "purchase_date": "2026-08-25T09:30:00+05:30",
            "useful_life_months": 60,
            "salvage_minor": 5_000,
            "notes": reason,
        },
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": idempotency_key,
            "X-Terminal-Id": str(seed_owner["terminal"].id),
            "X-Request-Id": request_id,
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "16",
            "X-Client-Action-Id": action_id,
            "X-Offline-Captured": "true",
            "X-Client-Occurred-At": "2026-08-25T18:10:00+05:30",
            "X-Forwarded-For": "198.51.100.250",
            "User-Agent": "DCompanyERP-Android/16",
        },
    )
    after_request = datetime.now(UTC)
    assert create.status_code == 201, create.text
    asset_id = create.json()["id"]

    audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.entity_type == "Asset",
                AuditLog.entity_id == asset_id,
                AuditLog.action == "create",
            )
        )
    ).scalar_one()
    assert audit.actor_user_id == seed_owner["owner"].id
    assert audit.terminal_id == seed_owner["terminal"].id
    assert audit.request_id == request_id
    assert audit.client_platform == "android"
    assert audit.client_version_code == 16
    assert audit.client_action_id == action_id
    assert audit.client_reported_at == datetime(2026, 8, 25, 12, 40, tzinfo=UTC)
    assert audit.client_was_offline is True
    assert audit.synced_at is not None
    assert before_request <= audit.synced_at <= after_request
    assert audit.reason == reason
    assert audit.user_agent == "DCompanyERP-Android/16"
    assert audit.ip is not None
    assert audit.ip.startswith("2001:db8:")
    assert audit.ip != "198.51.100.250"
    assert before_request <= audit.created_at <= after_request
    recursive_rows = await session.scalar(
        select(func.count()).select_from(AuditLog).where(
            AuditLog.company_id == seed_owner["company"].id,
            AuditLog.request_id == request_id,
            AuditLog.entity_type == "AuditLog",
        )
    )
    assert recursive_rows == 0

    clear_actor()
    clear_request_context()
    legacy_entity_id = f"legacy-{uuid4().hex}"
    legacy = AuditLog(
        actor_user_id=seed_owner["owner"].id,
        company_id=seed_owner["company"].id,
        action="legacy_import",
        entity_type="LegacyAuditProbe",
        entity_id=legacy_entity_id,
        before={"status": "old"},
        after={"status": "current"},
    )
    session.add(legacy)
    await session.commit()
    assert legacy.request_id is None
    assert legacy.terminal_id is None
    assert legacy.client_was_offline is None

    unlock = await client.post(
        "/api/v1/admin/audit/unlock",
        json={"password": seed_owner["password"]},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )
    assert unlock.status_code == 200, unlock.text
    audit_token = unlock.json()["audit_token"]
    listed = await client.get(
        "/api/v1/admin/audit",
        params={"entity_id": legacy_entity_id, "limit": 10},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Audit-Token": audit_token,
        },
    )
    assert listed.status_code == 200, listed.text
    assert len(listed.json()) == 1
    serialized = listed.json()[0]
    assert serialized["id"] == legacy.id
    assert serialized["request_id"] is None
    assert serialized["terminal_id"] is None
    assert serialized["client_platform"] is None
    assert serialized["client_version_code"] is None
    assert serialized["client_action_id"] is None
    assert serialized["client_reported_at"] is None
    assert serialized["client_was_offline"] is None
    assert serialized["synced_at"] is None
    assert serialized["reason"] is None

    invalid_page = await client.get(
        "/api/v1/admin/audit",
        params={"limit": 0},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Audit-Token": audit_token,
        },
    )
    assert invalid_page.status_code == 422

    legacy_id = legacy.id
    legacy.reason = "attempted tampering"
    with pytest.raises(ValueError, match="append-only"):
        await session.flush()
    await session.rollback()

    persisted_legacy = await session.get(AuditLog, legacy_id)
    assert persisted_legacy is not None
    await session.delete(persisted_legacy)
    with pytest.raises(ValueError, match="append-only"):
        await session.flush()
    await session.rollback()
