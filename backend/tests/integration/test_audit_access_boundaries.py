"""Protected-owner-only audit unlock and token-scope proof on PostgreSQL."""

from __future__ import annotations

from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.core.roles import PROTECTED_OWNER_ROLE
from app.core.security import hash_password, issue_audit_token
from app.models import AuditLog, Role, User, UserRole
from app.services.audit.recorder import install_audit_listeners


@pytest_asyncio.fixture(autouse=True)
async def require_local_postgres(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _login(client, user: User, password: str) -> str:
    response = await client.post(
        "/api/v1/auth/login",
        json={"email": user.email, "password": password},
    )
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


@pytest.mark.asyncio
async def test_only_protected_owner_can_unlock_and_read_audit(
    client,
    session,
    seed_owner,
) -> None:
    company_id = seed_owner["company"].id
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
    protected_assignment = (
        await session.execute(
            select(UserRole).where(
                UserRole.user_id == seed_owner["owner"].id,
                UserRole.role_id == public_owner_role.id,
            )
        )
    ).scalar_one()
    protected_assignment.role_id = protected_role.id

    password = "security-boundary-password"
    restricted_users: list[User] = []
    for role_code in ("owner", "co_owner", "manager", "auditor"):
        role = public_owner_role
        if role_code != "owner":
            role = Role(
                id=uuid4(),
                company_id=company_id,
                code=role_code,
                name=role_code.replace("_", " ").title(),
                permissions=[],
            )
            session.add(role)
        user = User(
            id=uuid4(),
            company_id=company_id,
            email=f"audit-boundary-{role_code}-{uuid4().hex[:8]}@test.local",
            name=f"Audit boundary {role_code}",
            password_hash=hash_password(password),
            status="active",
        )
        session.add(user)
        await session.flush()
        session.add(UserRole(id=uuid4(), user_id=user.id, role_id=role.id))
        restricted_users.append(user)
    await session.commit()
    install_audit_listeners()

    for user in restricted_users:
        token = await _login(client, user, password)
        unlock = await client.post(
            "/api/v1/admin/audit/unlock",
            json={"password": password},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert unlock.status_code == 403, unlock.text
        assert password not in unlock.text

        forged_read = await client.get(
            "/api/v1/admin/audit",
            headers={
                "Authorization": f"Bearer {token}",
                "X-Audit-Token": issue_audit_token(
                    user_id=user.id,
                    company_id=company_id,
                ),
            },
        )
        assert forged_read.status_code == 403, forged_read.text

    owner_token = await _login(client, seed_owner["owner"], seed_owner["password"])
    owner_headers = {
        "Authorization": f"Bearer {owner_token}",
        "X-Request-Id": f"audit-unlock-{uuid4().hex}",
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "34",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }

    missing_unlock = await client.get(
        "/api/v1/admin/audit",
        headers=owner_headers,
    )
    assert missing_unlock.status_code == 401, missing_unlock.text

    wrong_password = "wrong-password-that-must-not-be-recorded"
    failed = await client.post(
        "/api/v1/admin/audit/unlock",
        json={"password": wrong_password},
        headers=owner_headers,
    )
    assert failed.status_code == 401, failed.text
    assert wrong_password not in failed.text

    failure_row = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == company_id,
                AuditLog.actor_user_id == seed_owner["owner"].id,
                AuditLog.action == "audit_unlock_failed",
            )
        )
    ).scalar_one()
    assert failure_row.after == {"result": "audit_unlock_failed"}
    assert failure_row.request_id == owner_headers["X-Request-Id"]
    assert failure_row.client_platform == "android"
    assert failure_row.client_version_code == 34
    assert failure_row.terminal_id == seed_owner["terminal"].id
    assert wrong_password not in repr(failure_row.before)
    assert wrong_password not in repr(failure_row.after)

    success = await client.post(
        "/api/v1/admin/audit/unlock",
        json={"password": seed_owner["password"]},
        headers=owner_headers,
    )
    assert success.status_code == 200, success.text
    audit_token = success.json()["audit_token"]
    assert seed_owner["password"] not in success.text

    clock_in = await client.post(
        "/api/v1/staff/attendance/clock-in",
        json={"branch_id": str(seed_owner["branch"].id)},
        headers=owner_headers,
    )
    assert clock_in.status_code == 201, clock_in.text
    attendance_id = clock_in.json()["id"]

    wrong_subject = await client.get(
        "/api/v1/admin/audit",
        headers={
            **owner_headers,
            "X-Audit-Token": issue_audit_token(
                user_id=uuid4(),
                company_id=company_id,
            ),
        },
    )
    assert wrong_subject.status_code == 401, wrong_subject.text

    wrong_company = await client.get(
        "/api/v1/admin/audit",
        headers={
            **owner_headers,
            "X-Audit-Token": issue_audit_token(
                user_id=seed_owner["owner"].id,
                company_id=uuid4(),
            ),
        },
    )
    assert wrong_company.status_code == 401, wrong_company.text

    listed = await client.get(
        "/api/v1/admin/audit",
        params={"area": "system", "limit": 20},
        headers={**owner_headers, "X-Audit-Token": audit_token},
    )
    assert listed.status_code == 200, listed.text
    actions = {row["action"] for row in listed.json()}
    assert {"audit_unlock_failed", "audit_unlock_success"} <= actions

    staff_area = await client.get(
        "/api/v1/admin/audit",
        params={"area": "staff", "entity_id": attendance_id, "limit": 20},
        headers={**owner_headers, "X-Audit-Token": audit_token},
    )
    assert staff_area.status_code == 200, staff_area.text
    assert [(row["entity_type"], row["entity_id"]) for row in staff_area.json()] == [
        ("Attendance", attendance_id)
    ]
