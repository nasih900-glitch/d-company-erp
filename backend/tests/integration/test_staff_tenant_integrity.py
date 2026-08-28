"""PostgreSQL and API proofs for staff tenant and attendance integrity."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import errors
from sqlalchemy import delete, select

from app.core.security import hash_password, issue_access_token
from app.models import Branch, Company, Role, User, UserRole
from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


def _sync_dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def _seed_scope_world(connection: psycopg.Connection) -> dict[str, UUID]:
    ids = {
        "company_a": uuid4(),
        "company_b": uuid4(),
        "branch_a": uuid4(),
        "branch_b": uuid4(),
        "user_a": uuid4(),
        "grantor_a": uuid4(),
        "user_b": uuid4(),
        "role_a": uuid4(),
        "role_b": uuid4(),
    }
    connection.execute(
        "INSERT INTO companies (id, name) VALUES (%s, 'Tenant A'), (%s, 'Tenant B')",
        (ids["company_a"], ids["company_b"]),
    )
    connection.execute(
        "INSERT INTO branches "
        "(id, company_id, name, code, invoice_series_code) VALUES "
        "(%s, %s, 'Branch A', 'A1', 'A1'), "
        "(%s, %s, 'Branch B', 'B1', 'B1')",
        (
            ids["branch_a"],
            ids["company_a"],
            ids["branch_b"],
            ids["company_b"],
        ),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) VALUES "
        "(%s, %s, %s, 'not-a-password', 'User A'), "
        "(%s, %s, %s, 'not-a-password', 'Grantor A'), "
        "(%s, %s, %s, 'not-a-password', 'User B')",
        (
            ids["user_a"],
            ids["company_a"],
            f"user-a-{uuid4()}@test.local",
            ids["grantor_a"],
            ids["company_a"],
            f"grantor-a-{uuid4()}@test.local",
            ids["user_b"],
            ids["company_b"],
            f"user-b-{uuid4()}@test.local",
        ),
    )
    connection.execute(
        "INSERT INTO roles (id, company_id, code, name) VALUES "
        "(%s, %s, 'cashier', 'Cashier A'), "
        "(%s, %s, 'cashier', 'Cashier B')",
        (
            ids["role_a"],
            ids["company_a"],
            ids["role_b"],
            ids["company_b"],
        ),
    )
    return ids


def _insert_user_role(
    connection: psycopg.Connection,
    ids: dict[str, UUID],
    *,
    role: str = "role_a",
    branch: str | None = "branch_a",
    grantor: str | None = "grantor_a",
) -> UUID:
    assignment_id = uuid4()
    connection.execute(
        "INSERT INTO user_roles (id, user_id, role_id, branch_id, granted_by) "
        "VALUES (%s, %s, %s, %s, %s)",
        (
            assignment_id,
            ids["user_a"],
            ids[role],
            ids[branch] if branch else None,
            ids[grantor] if grantor else None,
        ),
    )
    return assignment_id


def _insert_attendance(
    connection: psycopg.Connection,
    ids: dict[str, UUID],
    *,
    company: str = "company_a",
    branch: str = "branch_a",
    clock_out_at: datetime | None = None,
) -> UUID:
    attendance_id = uuid4()
    connection.execute(
        "INSERT INTO attendance "
        "(id, company_id, user_id, branch_id, clock_in_at, clock_out_at) "
        "VALUES (%s, %s, %s, %s, %s, %s)",
        (
            attendance_id,
            ids[company],
            ids["user_a"],
            ids[branch],
            datetime(2026, 8, 27, 12, tzinfo=UTC),
            clock_out_at,
        ),
    )
    return attendance_id


@pytest.mark.integration
def test_0047_preflight_rejects_each_legacy_scope_corruption() -> None:
    with _disposable_database("erp_staff_scope_preflight") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0046")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_scope_world(connection)
            connection.commit()

        cases = (
            ("role", {"role": "role_b"}, "user role scope is corrupt"),
            ("branch", {"branch": "branch_b"}, "user role scope is corrupt"),
            ("grantor", {"grantor": "user_b"}, "user role scope is corrupt"),
        )
        for _label, kwargs, expected in cases:
            with psycopg.connect(_sync_dsn(database_url)) as connection:
                assignment_id = _insert_user_role(connection, ids, **kwargs)
                connection.commit()
            blocked = _run_alembic(database_url, "upgrade", "0047")
            assert blocked.returncode != 0
            assert expected in blocked.stdout + blocked.stderr
            with psycopg.connect(_sync_dsn(database_url)) as connection:
                connection.execute(
                    "DELETE FROM user_roles WHERE id = %s",
                    (assignment_id,),
                )
                connection.commit()

        for kwargs in (
            {"company": "company_b"},
            {"branch": "branch_b"},
        ):
            with psycopg.connect(_sync_dsn(database_url)) as connection:
                attendance_id = _insert_attendance(connection, ids, **kwargs)
                connection.commit()
            blocked = _run_alembic(database_url, "upgrade", "0047")
            assert blocked.returncode != 0
            assert "attendance scope is corrupt" in blocked.stdout + blocked.stderr
            with psycopg.connect(_sync_dsn(database_url)) as connection:
                connection.execute(
                    "DELETE FROM attendance WHERE id = %s",
                    (attendance_id,),
                )
                connection.commit()

        with psycopg.connect(_sync_dsn(database_url)) as connection:
            first = _insert_attendance(connection, ids)
            second = _insert_attendance(connection, ids)
            connection.commit()
        blocked = _run_alembic(database_url, "upgrade", "0047")
        assert blocked.returncode != 0
        assert "duplicate open attendance exists" in blocked.stdout + blocked.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            connection.execute(
                "DELETE FROM attendance WHERE id IN (%s, %s)",
                (first, second),
            )
            connection.commit()

        migrated = _run_alembic(database_url, "upgrade", "0047")
        assert migrated.returncode == 0, migrated.stdout + migrated.stderr


@pytest.mark.integration
def test_0047_triggers_unique_open_row_and_safe_downgrade_guard() -> None:
    with _disposable_database("erp_staff_scope_guards") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0047")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url), autocommit=True) as connection:
            ids = _seed_scope_world(connection)
            assignment_id = _insert_user_role(connection, ids)
            assert connection.execute(
                "SELECT auth_version FROM users WHERE id = %s",
                (ids["user_a"],),
            ).fetchone() == (1,)

            with pytest.raises(errors.CheckViolation, match="target user company"):
                _insert_user_role(connection, ids, role="role_b")
            with pytest.raises(errors.CheckViolation, match="branch must belong"):
                _insert_user_role(connection, ids, branch="branch_b")
            with pytest.raises(errors.CheckViolation, match="grantor must belong"):
                _insert_user_role(connection, ids, grantor="user_b")
            with pytest.raises(errors.CheckViolation, match="target user company"):
                connection.execute(
                    "UPDATE user_roles SET role_id = %s WHERE id = %s",
                    (ids["role_b"], assignment_id),
                )
            with pytest.raises(errors.CheckViolation, match="assigned role code"):
                connection.execute(
                    "UPDATE roles SET code = 'manager' WHERE id = %s",
                    (ids["role_a"],),
                )
            for table, entity_id in (
                ("users", ids["user_a"]),
                ("roles", ids["role_a"]),
                ("branches", ids["branch_a"]),
            ):
                with pytest.raises(errors.CheckViolation, match="ownership is immutable"):
                    connection.execute(
                        f"UPDATE {table} SET company_id = %s WHERE id = %s",  # noqa: S608
                        (ids["company_b"], entity_id),
                    )

            connection.execute(
                "UPDATE user_roles SET user_id = %s WHERE id = %s",
                (ids["grantor_a"], assignment_id),
            )
            assert connection.execute(
                "SELECT id, auth_version FROM users WHERE id IN (%s, %s) ORDER BY id",
                (ids["user_a"], ids["grantor_a"]),
            ).fetchall() == sorted(
                [(ids["user_a"], 2), (ids["grantor_a"], 1)],
                key=lambda row: row[0],
            )
            connection.execute(
                "DELETE FROM user_roles WHERE id = %s",
                (assignment_id,),
            )
            assert connection.execute(
                "SELECT auth_version FROM users WHERE id = %s",
                (ids["grantor_a"],),
            ).fetchone() == (2,)
            _insert_user_role(connection, ids)
            connection.execute(
                "DELETE FROM roles WHERE id = %s",
                (ids["role_a"],),
            )
            assert connection.execute(
                "SELECT auth_version FROM users WHERE id = %s",
                (ids["user_a"],),
            ).fetchone() == (4,)

            first = _insert_attendance(connection, ids)
            with pytest.raises(errors.UniqueViolation):
                _insert_attendance(connection, ids)
            with pytest.raises(errors.CheckViolation, match="must belong to one company"):
                _insert_attendance(connection, ids, company="company_b")
            with pytest.raises(errors.CheckViolation, match="must belong to one company"):
                _insert_attendance(connection, ids, branch="branch_b")
            connection.execute(
                "UPDATE attendance SET clock_out_at = %s WHERE id = %s",
                (datetime(2026, 8, 27, 13, tzinfo=UTC), first),
            )
            _insert_attendance(connection, ids)

            # Simulate privileged maintenance bypass. Downgrade must re-run the
            # preflight and refuse to remove its guards while corrupt evidence
            # exists; it must never silently normalize or delete the row.
            connection.execute(
                "ALTER TABLE user_roles DISABLE TRIGGER "
                "trg_enforce_user_role_tenant_scope"
            )
            bypassed_id = _insert_user_role(connection, ids, role="role_b")
            connection.execute(
                "ALTER TABLE user_roles ENABLE TRIGGER "
                "trg_enforce_user_role_tenant_scope"
            )

        blocked = _run_alembic(database_url, "downgrade", "0046")
        assert blocked.returncode != 0
        assert "user role scope is corrupt" in blocked.stdout + blocked.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            assert connection.execute(
                "SELECT 1 FROM user_roles WHERE id = %s",
                (bypassed_id,),
            ).fetchone() == (1,)
        current = _run_alembic(database_url, "current")
        assert current.returncode == 0
        assert "0047" in current.stdout + current.stderr


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_attendance_requests_have_one_authoritative_result(
    client,
    seed_owner,
) -> None:
    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    payload = {"branch_id": str(seed_owner["branch"].id)}

    clock_ins = await asyncio.gather(
        client.post("/api/v1/staff/attendance/clock-in", headers=headers, json=payload),
        client.post("/api/v1/staff/attendance/clock-in", headers=headers, json=payload),
    )
    assert sorted(response.status_code for response in clock_ins) == [201, 422]
    rejected_in = next(response for response in clock_ins if response.status_code == 422)
    assert "already clocked in" in rejected_in.json()["error"]["message"]

    clock_outs = await asyncio.gather(
        client.post("/api/v1/staff/attendance/clock-out", headers=headers),
        client.post("/api/v1/staff/attendance/clock-out", headers=headers),
    )
    assert sorted(response.status_code for response in clock_outs) == [200, 422]
    rejected_out = next(response for response in clock_outs if response.status_code == 422)
    assert "No open clock-in" in rejected_out.json()["error"]["message"]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_clock_in_rejects_a_different_assigned_branch(
    client,
    session,
    seed_owner,
) -> None:
    other_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Other branch",
        code="O2",
        invoice_series_code="O2",
    )
    session.add(other_branch)
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200

    response = await client.post(
        "/api/v1/staff/attendance/clock-in",
        headers={"Authorization": f"Bearer {login.json()['access_token']}"},
        json={"branch_id": str(other_branch.id)},
    )

    assert response.status_code == 403
    assert "assigned branch" in response.json()["error"]["message"]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_kitchen_user_reaches_authenticated_password_guidance(
    client,
    session,
    seed_owner,
) -> None:
    kitchen_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="kitchen",
        name="Kitchen",
        permissions=[],
    )
    kitchen_user = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"kitchen-{uuid4()}@test.local",
        password_hash=hash_password("kitchen-password-1234"),
        name="Kitchen User",
        status="active",
    )
    session.add_all([kitchen_role, kitchen_user])
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=kitchen_user.id,
            role_id=kitchen_role.id,
        )
    )
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": kitchen_user.email, "password": "kitchen-password-1234"},
    )
    assert login.status_code == 200
    response = await client.post(
        "/api/v1/staff/me/password",
        headers={"Authorization": f"Bearer {login.json()['access_token']}"},
        json={
            "current_password": "kitchen-password-1234",
            "new_password": "new-kitchen-password-5678",
        },
    )

    assert response.status_code == 422
    assert "OTP approval is required" in response.json()["error"]["message"]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_direct_role_delete_invalidates_an_issued_token(
    client,
    session,
    seed_owner,
) -> None:
    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200
    token = login.json()["access_token"]

    await session.execute(
        delete(UserRole).where(UserRole.user_id == seed_owner["owner"].id)
    )
    await session.commit()

    rejected = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert rejected.status_code == 401
    assert "session expired" in rejected.json()["error"]["message"]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_signed_token_with_foreign_branch_is_rejected_without_terminal(
    client,
    session,
    seed_owner,
) -> None:
    foreign_company = Company(id=uuid4(), name="Foreign token tenant")
    foreign_branch = Branch(
        id=uuid4(),
        company_id=foreign_company.id,
        name="Foreign branch",
        code="F1",
        invoice_series_code="F1",
    )
    session.add_all([foreign_company, foreign_branch])
    await session.commit()
    await session.refresh(seed_owner["owner"])
    token = issue_access_token(
        user_id=seed_owner["owner"].id,
        company_id=seed_owner["company"].id,
        roles=["owner"],
        branch_id=foreign_branch.id,
        auth_version=seed_owner["owner"].auth_version,
    )

    response = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {token}"},
    )

    assert response.status_code == 401
    assert response.json()["error"]["message"] == "branch not found"


@pytest.mark.integration
@pytest.mark.asyncio
async def test_manager_cannot_change_delete_or_assign_public_owner_role(
    client,
    session,
    seed_owner,
) -> None:
    manager_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="manager",
        name="Manager",
        permissions=[],
    )
    cashier_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="cashier",
        name="Cashier",
        permissions=[],
    )
    manager = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"manager-{uuid4()}@test.local",
        password_hash=hash_password("manager-password-1234"),
        name="Manager",
        status="active",
    )
    cashier = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"cashier-{uuid4()}@test.local",
        password_hash=hash_password("cashier-password-1234"),
        name="Cashier",
        status="active",
    )
    session.add_all([manager_role, cashier_role, manager, cashier])
    await session.flush()
    session.add_all(
        [
            UserRole(id=uuid4(), user_id=manager.id, role_id=manager_role.id),
            UserRole(id=uuid4(), user_id=cashier.id, role_id=cashier_role.id),
        ]
    )
    await session.commit()
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": manager.email, "password": "manager-password-1234"},
    )
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    demote = await client.patch(
        f"/api/v1/staff/users/{seed_owner['owner'].id}",
        headers=headers,
        json={"role_code": "cashier"},
    )
    suspend = await client.patch(
        f"/api/v1/staff/users/{seed_owner['owner'].id}",
        headers=headers,
        json={"status": "suspended"},
    )
    remove = await client.delete(
        f"/api/v1/staff/users/{seed_owner['owner'].id}",
        headers=headers,
    )
    promote = await client.patch(
        f"/api/v1/staff/users/{cashier.id}",
        headers=headers,
        json={"role_code": "owner"},
    )
    roles = await client.get("/api/v1/staff/roles", headers=headers)

    assert [demote.status_code, suspend.status_code, remove.status_code, promote.status_code] == [
        403,
        403,
        403,
        403,
    ]
    assert roles.status_code == 200
    assert "owner" not in {row["code"] for row in roles.json()}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_role_replacements_serialize_and_preserve_branch(
    client,
    session,
    seed_owner,
) -> None:
    manager_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="manager",
        name="Manager",
        permissions=[],
    )
    cashier_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="cashier",
        name="Cashier",
        permissions=[],
    )
    target = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"role-race-{uuid4()}@test.local",
        password_hash=hash_password("target-password-1234"),
        name="Role race target",
        status="active",
    )
    staff_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == seed_owner["company"].id,
                Role.code == "staff",
            )
        )
    ).scalar_one()
    session.add_all([manager_role, cashier_role, target])
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=target.id,
            role_id=staff_role.id,
            branch_id=seed_owner["branch"].id,
            granted_by=seed_owner["owner"].id,
        )
    )
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    responses = await asyncio.gather(
        client.patch(
            f"/api/v1/staff/users/{target.id}",
            headers=headers,
            json={"role_code": "manager"},
        ),
        client.patch(
            f"/api/v1/staff/users/{target.id}",
            headers=headers,
            json={"role_code": "cashier"},
        ),
    )

    assert [response.status_code for response in responses] == [200, 200]
    assignments = (
        await session.execute(
            select(UserRole.branch_id, Role.code)
            .join(Role, Role.id == UserRole.role_id)
            .where(UserRole.user_id == target.id)
        )
    ).all()
    assert len(assignments) == 1
    assert assignments[0].branch_id == seed_owner["branch"].id
    assert assignments[0].code in {"manager", "cashier"}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_role_replacement_fails_closed_for_legacy_multi_branch_scope(
    client,
    session,
    seed_owner,
) -> None:
    second_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Second branch",
        code="S2",
        invoice_series_code="S2",
    )
    manager_role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="manager",
        name="Manager",
        permissions=[],
    )
    target = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"multi-branch-{uuid4()}@test.local",
        password_hash=hash_password("target-password-1234"),
        name="Multi branch target",
        status="active",
    )
    staff_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == seed_owner["company"].id,
                Role.code == "staff",
            )
        )
    ).scalar_one()
    session.add_all([second_branch, manager_role, target])
    await session.flush()
    session.add_all(
        [
            UserRole(
                id=uuid4(),
                user_id=target.id,
                role_id=staff_role.id,
                branch_id=branch_id,
                granted_by=seed_owner["owner"].id,
            )
            for branch_id in (seed_owner["branch"].id, second_branch.id)
        ]
    )
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200
    response = await client.patch(
        f"/api/v1/staff/users/{target.id}",
        headers={"Authorization": f"Bearer {login.json()['access_token']}"},
        json={"role_code": "manager"},
    )

    assert response.status_code == 422
    assert "multiple branches" in response.json()["error"]["message"]
    assignments = (
        await session.execute(
            select(UserRole.branch_id).where(UserRole.user_id == target.id)
        )
    ).scalars().all()
    assert set(assignments) == {seed_owner["branch"].id, second_branch.id}
