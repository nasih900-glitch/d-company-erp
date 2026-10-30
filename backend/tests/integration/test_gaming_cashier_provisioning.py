"""Company-scoped role provisioning through ordinary OTP and Staff APIs.

SMTP is intercepted; the normal isolated ASGI/Postgres/Redis fixtures are required.
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select

from app.core.config import get_settings
from app.core.permissions import MODULE_PERMISSIONS
from app.models.access_control import RolePermissionOverride
from app.models import AuditLog, Company, Role, User, UserRole
from app.services.auth import otp as otp_service
from app.services.email.mailer import Mailer
from scripts.ensure_roles import ensure_roles
from scripts.seed import DEFAULT_ROLES

EXPECTED = {
    "pos.read", "pos.write", "pos.void", "pos.shift.open", "pos.shift.close",
    "menu.read", "gaming.read", "gaming.write", "staff.attendance.write",
}
SYNTHETIC_PASSWORD = "Synthetic-role-proof-2026!"
SYNTHETIC_OTP = "123456"


@pytest.fixture
def captured_security_mail(monkeypatch, seed_owner):
    monkeypatch.setenv("SMTP_HOST", "smtp.example.test")
    monkeypatch.setenv("SMTP_PORT", "465")
    monkeypatch.setenv("SMTP_USER", "synthetic@example.test")
    monkeypatch.setenv("SMTP_PASSWORD", "synthetic-only-not-a-real-secret")
    monkeypatch.setenv("FROM_EMAIL", "synthetic@example.test")
    monkeypatch.setattr(get_settings(), "account_security_company_id", seed_owner["company"].id)
    monkeypatch.setattr(get_settings(), "account_security_email", "security@example.test")
    monkeypatch.setattr(otp_service, "generate_otp", lambda: SYNTHETIC_OTP)
    sent = []

    def capture(_self, to, subject, html, text=None):
        sent.append({"to": to, "subject": subject, "html": html, "text": text or ""})
        return True

    monkeypatch.setattr(Mailer, "send", capture)
    return sent


async def _rows(session, table, company_ids):
    statement = select(table).order_by(table.c.id)
    if table is UserRole.__table__:
        statement = statement.join(User, User.id == table.c.user_id).where(User.company_id.in_(company_ids))
    else:
        statement = statement.where(table.c.company_id.in_(company_ids))
    return {row.id: dict(row._mapping) for row in (await session.execute(statement)).all()}


async def _one_user(session, company_id, email):
    return (await session.execute(select(User).where(User.company_id == company_id, User.email == email))).scalar_one_or_none()


async def _assignments(session, user_id):
    return (await session.execute(
        select(Role.code, UserRole.branch_id, UserRole.granted_by)
        .join(UserRole, UserRole.role_id == Role.id)
        .where(UserRole.user_id == user_id)
        .order_by(Role.code)
    )).all()


async def _access_audits(session, company_id, user_id):
    return (await session.execute(select(AuditLog).where(
        AuditLog.company_id == company_id,
        AuditLog.action == "staff_access_changed", AuditLog.target_user_id == user_id,
    ))).scalars().all()


@pytest.mark.asyncio
async def test_company_scoped_provisioning_receives_only_daily_operations(
    client, session, seed_owner, captured_security_mail,
):
    company = seed_owner["company"].id
    owner = seed_owner["owner"]
    branch = seed_owner["branch"].id
    terminal = seed_owner["terminal"].id
    old_users = await _rows(session, User.__table__, [company])
    old_assignments = await _rows(session, UserRole.__table__, [company])
    session.add(Role(
        id=uuid4(), company_id=company, code="gaming_cashier", name="Gaming & POS staff", permissions=[],
    ))
    session.add_all([
        RolePermissionOverride(id=uuid4(), company_id=company, role_code="gaming_cashier", module=module, allowed=True)
        for module in MODULE_PERMISSIONS
    ])
    await session.commit()

    owner_login = await client.post("/api/v1/auth/login", json={"email": owner.email, "password": seed_owner["password"]})
    assert owner_login.status_code == 200
    owner_headers = {"Authorization": "Bearer " + owner_login.json()["access_token"]}
    owner_stable_fields = (User.id, User.company_id, User.email, User.name, User.status, User.password_hash, User.auth_version)
    owner_before = (await session.execute(select(*owner_stable_fields).where(User.id == owner.id))).one()
    created_ids = []

    for name in ("Operator One", "Operator Two"):
        email = name.lower().replace(" ", "-") + "-" + uuid4().hex[:10] + "@test.local"
        request = await client.post("/api/v1/auth/register/request", headers=owner_headers,
                                    json={"email": email, "name": name, "password": SYNTHETIC_PASSWORD})
        assert request.status_code == 202
        challenge = request.json()["challenge_id"]
        assert request.json()["destination"] == "s***@example.test"
        assert captured_security_mail[-1]["to"] == "security@example.test"
        assert SYNTHETIC_OTP in captured_security_mail[-1]["text"]
        assert SYNTHETIC_PASSWORD not in captured_security_mail[-1]["text"] + captured_security_mail[-1]["html"]
        assert await _one_user(session, company, email) is None
        wrong = await client.post("/api/v1/auth/register/confirm", json={"challenge_id": challenge, "code": "000000"})
        assert wrong.status_code == 422
        assert await _one_user(session, company, email) is None
        confirm = await client.post("/api/v1/auth/register/confirm", json={"challenge_id": challenge, "code": SYNTHETIC_OTP})
        assert confirm.status_code == 201
        replay = await client.post("/api/v1/auth/register/confirm", json={"challenge_id": challenge, "code": SYNTHETIC_OTP})
        assert replay.status_code == 422
        user = await _one_user(session, company, email)
        assert user is not None and user.name == name and user.status == "active"
        created_ids.append(user.id)
        assert await _assignments(session, user.id) == [("staff", branch, owner.id)]
        initial_version = user.auth_version
        initial_login = await client.post("/api/v1/auth/login", json={"email": email, "password": SYNTHETIC_PASSWORD})
        assert initial_login.status_code == 200
        initial_headers = {"Authorization": "Bearer " + initial_login.json()["access_token"]}
        initial_me = await client.get("/api/v1/auth/me", headers=initial_headers)
        assert initial_me.status_code == 200 and initial_me.json()["roles"] == ["staff"]

        grant = await client.patch("/api/v1/staff/users/" + str(user.id), headers=owner_headers, json={"role_code": "gaming_cashier"})
        assert grant.status_code == 200 and grant.json()["roles"] == ["gaming_cashier"]
        await session.refresh(user)
        assert user.auth_version > initial_version
        assert await _assignments(session, user.id) == [("gaming_cashier", branch, owner.id)]
        assert (await client.get("/api/v1/auth/me", headers=initial_headers)).status_code == 401
        audits = await _access_audits(session, company, user.id)
        assert len(audits) == 1
        audit = audits[0]
        assert audit.actor_user_id == owner.id and audit.company_id == company
        assert audit.before["before_roles"] == ["staff"]
        assert audit.after["after_roles"] == ["gaming_cashier"]
        assert audit.before["branch_id"] == audit.after["branch_id"] == str(branch)
        creation = (await session.execute(select(AuditLog).where(
            AuditLog.company_id == company,
            AuditLog.action == "otp_account_created", AuditLog.target_user_id == user.id,
        ))).scalars().all()
        assert len(creation) == 1 and creation[0].actor_user_id == owner.id and creation[0].after["role"] == "staff"

        login = await client.post("/api/v1/auth/login", json={"email": email, "password": SYNTHETIC_PASSWORD})
        assert login.status_code == 200
        headers = {"Authorization": "Bearer " + login.json()["access_token"], "X-Terminal-Id": str(terminal)}
        me = await client.get("/api/v1/auth/me", headers=headers)
        assert me.status_code == 200
        profile = me.json()
        assert UUID(profile["user_id"]) == user.id and UUID(profile["branch_id"]) == branch
        assert profile["roles"] == ["gaming_cashier"]
        assert set(profile["effective_permissions"]) == EXPECTED and len(profile["effective_permissions"]) == len(EXPECTED)
        assert profile["protected_access"] is profile["audit_access"] is profile["release_control_access"] is False
        for path in ("/settings/terminals", "/menu/categories", "/gaming/stations", "/pos/shifts", "/pos/orders"):
            assert (await client.get("/api/v1" + path, headers=headers)).status_code == 200, path
        for path in ("/finance/expenses", "/inventory/ingredients", "/staff/users", "/settings/company",
                     "/analytics/home", "/analytics/dashboard", "/reports/overview"):
            assert (await client.get("/api/v1" + path, headers=headers)).status_code == 403, path
        escalation = await client.patch("/api/v1/staff/users/" + str(user.id), headers=headers, json={"role_code": "owner"})
        assert escalation.status_code == 403

        # Repeating the same absolute assignment is a no-op, not a cached HTTP replay.
        version = user.auth_version
        repeat = await client.patch("/api/v1/staff/users/" + str(user.id), headers=owner_headers, json={"role_code": "gaming_cashier"})
        assert repeat.status_code == 200
        await session.refresh(user)
        assert user.auth_version == version and len(await _access_audits(session, company, user.id)) == 1
        assert (await client.get("/api/v1/auth/me", headers=headers)).status_code == 200
        duplicate = await client.post("/api/v1/auth/register/request", headers=owner_headers,
                                      json={"email": email, "name": name, "password": SYNTHETIC_PASSWORD})
        assert duplicate.status_code == 409
        assert (await _one_user(session, company, email)).id == user.id

        revoke = await client.patch("/api/v1/staff/users/" + str(user.id), headers=owner_headers,
                                    json={"role_code": "staff"})
        assert revoke.status_code == 200 and revoke.json()["roles"] == ["staff"]
        await session.refresh(user)
        assert user.auth_version > version
        assert await _assignments(session, user.id) == [("staff", branch, owner.id)]
        assert (await client.get("/api/v1/auth/me", headers=headers)).status_code == 401
        assert (await client.post("/api/v1/auth/refresh", json={
            "refresh_token": login.json()["refresh_token"],
        })).status_code == 401
        audits = await _access_audits(session, company, user.id)
        assert len(audits) == 2
        revoked_audit = next(audit for audit in audits if audit.after["after_roles"] == ["staff"])
        assert revoked_audit.actor_user_id == owner.id
        assert revoked_audit.before["before_roles"] == ["gaming_cashier"]
        assert revoked_audit.before["branch_id"] == revoked_audit.after["branch_id"] == str(branch)

    assert len(set(created_ids)) == 2 and len(captured_security_mail) == 2
    assert len(await _rows(session, User.__table__, [company])) == len(old_users) + 2
    assert (await session.execute(select(*owner_stable_fields).where(User.id == owner.id))).one() == owner_before
    assignments = await _rows(session, UserRole.__table__, [company])
    assert {key: row for key, row in assignments.items() if row["user_id"] not in created_ids} == old_assignments


@pytest.mark.asyncio
async def test_role_backfill_adds_only_missing_rows_for_two_companies_and_is_idempotent(
    session, seed_owner, monkeypatch,
):
    other = Company(id=uuid4(), name="Other role fixture")
    session.add(other)
    await session.flush()
    company_ids = [seed_owner["company"].id, other.id]
    existing = (await session.execute(select(Role).where(Role.company_id.in_(company_ids)))).scalars().all()
    existing_keys = {(row.company_id, row.code) for row in existing}
    for company_id in company_ids:
        for code, description in DEFAULT_ROLES:
            if code != "gaming_cashier" and (company_id, code) not in existing_keys:
                session.add(Role(id=uuid4(), company_id=company_id, code=code,
                                 name="Preserved " + code, description=description, permissions=["custom-sentinel"]))
    await session.commit()
    other_user = User(
        id=uuid4(), company_id=other.id, email=f"existing-{uuid4().hex[:10]}@test.local",
        name="Existing Operator", status="active", password_hash=seed_owner["owner"].password_hash,
    )
    session.add(other_user)
    await session.flush()
    other_role = (await session.execute(select(Role).where(
        Role.company_id == other.id, Role.code == "cashier",
    ))).scalar_one()
    session.add(UserRole(id=uuid4(), user_id=other_user.id, role_id=other_role.id))
    await session.commit()
    before = {table.name: await _rows(session, table, company_ids)
              for table in (Role.__table__, User.__table__, UserRole.__table__)}

    # Keep discovery inside these fixture companies; all role reads/writes still use real SQL.
    # The shared CI database can contain committed fixtures from unrelated tests.
    class ScopedSession:
        async def execute(self, statement):
            if statement.column_descriptions[0].get("entity") is Company:
                statement = statement.where(Company.id.in_(company_ids))
            return await session.execute(statement)

        def add(self, row):
            assert isinstance(row, Role) and row.company_id in company_ids
            session.add(row)

        async def commit(self):
            await session.commit()

    @asynccontextmanager
    async def scoped_factory():
        yield ScopedSession()

    monkeypatch.setattr("scripts.ensure_roles.AsyncSessionLocal", scoped_factory)
    await ensure_roles()
    first = await _rows(session, Role.__table__, company_ids)
    await ensure_roles()
    assert await _rows(session, Role.__table__, company_ids) == first
    for key, row in before[Role.__table__.name].items():
        assert first[key] == row
    added = [row for key, row in first.items() if key not in before[Role.__table__.name]]
    assert len(added) == 2 and {row["company_id"] for row in added} == set(company_ids)
    assert all(row["code"] == "gaming_cashier" and row["name"] == "Gaming & POS staff" for row in added)
    assert await _rows(session, User.__table__, company_ids) == before[User.__table__.name]
    assert await _rows(session, UserRole.__table__, company_ids) == before[UserRole.__table__.name]
