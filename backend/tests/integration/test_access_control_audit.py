"""PostgreSQL proof that access-control changes retain actor and target."""

from __future__ import annotations

from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.core.roles import PROTECTED_OWNER_ROLE
from app.models import AuditLog, Role, UserRole
from app.services.audit.recorder import install_audit_listeners


@pytest_asyncio.fixture(autouse=True)
async def require_local_postgres(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.mark.asyncio
async def test_access_override_create_update_delete_are_audited(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    terminal_id = seed_owner["terminal"].id
    public_owner_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == company_id,
                Role.code == "owner",
            )
        )
    ).scalar_one()
    protected_role = Role(
        id=uuid4(),
        company_id=company_id,
        code=PROTECTED_OWNER_ROLE,
        name="Protected owner",
        permissions=[],
    )
    session.add(protected_role)
    await session.flush()
    assignment = (
        await session.execute(
            select(UserRole).where(
                UserRole.user_id == owner_id,
                UserRole.role_id == public_owner_role.id,
            )
        )
    ).scalar_one()
    assignment.role_id = protected_role.id
    await session.commit()
    install_audit_listeners()

    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200, login.text
    headers = {
        "Authorization": f"Bearer {login.json()['access_token']}",
        "X-Terminal-Id": str(terminal_id),
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "34",
        "User-Agent": "DCompanyERP-Android/34",
    }
    target = "cashier:gaming"
    request_ids: list[str] = []
    access_levels: list[str] = []
    access_payloads: list[dict] = []

    for index, allowed in enumerate((True, False, None), start=1):
        request_id = f"access-{uuid4().hex}"
        request_ids.append(request_id)
        response = await client.patch(
            "/api/v1/admin/access-control",
            json={
                "role_code": "cashier",
                "module": "gaming",
                "allowed": allowed,
            },
            headers={
                **headers,
                "X-Request-Id": request_id,
                "X-Client-Action-Id": f"permission-change-{index}",
            },
        )
        assert response.status_code == 200, response.text
        access_payload = response.json()
        access_payloads.append(access_payload)
        access_levels.append(access_payload["access_level"])

    assert access_levels == ["full", "blocked", "partial"]
    assert access_payloads[-1]["ceiling_limited_permissions"] == []
    assert set(access_payloads[-1]["unavailable_permissions"]) == {
        "gaming.tournament.manage",
        "gaming.write",
    }

    session.expire_all()
    rows = (
        await session.execute(
            select(AuditLog)
            .where(
                AuditLog.company_id == company_id,
                AuditLog.entity_type == "RolePermissionOverride",
                AuditLog.entity_id == target,
            )
            .order_by(AuditLog.id)
        )
    ).scalars().all()

    assert [row.action for row in rows] == ["create", "update", "delete"]
    assert all(row.actor_user_id == owner_id for row in rows)
    assert [row.request_id for row in rows] == request_ids
    assert all(row.terminal_id == terminal_id for row in rows)
    assert all(row.client_platform == "android" for row in rows)
    assert all(row.client_version_code == 34 for row in rows)
    assert all(row.user_agent == "DCompanyERP-Android/34" for row in rows)
    assert [row.client_action_id for row in rows] == [
        "permission-change-1",
        "permission-change-2",
        "permission-change-3",
    ]
    assert rows[0].before is None
    assert rows[0].after["role_code"] == "cashier"
    assert rows[0].after["module"] == "gaming"
    assert rows[0].after["allowed"] is True
    assert rows[1].before == {"allowed": True}
    assert rows[1].after == {"allowed": False}
    assert rows[2].before["role_code"] == "cashier"
    assert rows[2].before["module"] == "gaming"
    assert rows[2].before["allowed"] is False
    assert rows[2].after is None
