"""Real PostgreSQL proofs: B's own clock actions never replace cashier A."""

from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError

from app.api.v1.staff import shared_attendance as shared_route
from app.core.errors import ForbiddenError
from app.core.security import hash_password
from app.models import (
    Attendance,
    AuditLog,
    AuthRefreshSession,
    Branch,
    Company,
    Role,
    Shift,
    User,
    UserRole,
)
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.models.idempotency_key import IdempotencyKey

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]
PASSWORD = "synthetic-attendance-password"
BASE = "/api/v1/staff/shared-attendance"


def write(headers, key=None):
    return {**headers, "Idempotency-Key": key or str(uuid4())}


@pytest_asyncio.fixture
async def shared_world(client, session, seed_owner):
    role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="gaming_cashier",
        name="Synthetic operators",
        permissions=[],
    )
    users = [
        User(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            email=f"shared-{uuid4()}@example.test",
            name=name,
            password_hash=hash_password(PASSWORD),
            status="active",
        )
        for name in ("Tablet A", "Staff B")
    ]
    session.add_all([role, *users])
    await session.flush()
    session.add_all(
        [
            UserRole(id=uuid4(), user_id=u.id, role_id=role.id, branch_id=seed_owner["branch"].id)
            for u in users
        ]
    )
    await session.commit()
    response = await client.post(
        "/api/v1/auth/login", json={"email": users[0].email, "password": PASSWORD}
    )
    assert response.status_code == 200, response.text
    return {
        **seed_owner,
        "a": users[0],
        "b": users[1],
        "role": role,
        "headers": {
            "Authorization": "Bearer " + response.json()["access_token"],
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    }


def body(w, **changes):
    return {"user_id": str(w["b"].id), "password": PASSWORD, **changes}


async def count(session, model, **where):
    return (
        await session.execute(select(func.count()).select_from(model).filter_by(**where))
    ).scalar_one()


async def test_secondary_clock_preserves_cashier_shift_credentials_and_attributes_b(
    client, session, shared_world
):
    w = shared_world
    opened_a = await client.post(
        "/api/v1/staff/my-attendance/clock-in",
        headers=write(w["headers"]),
        json={"branch_id": str(w["branch"].id)},
    )
    assert opened_a.status_code == 201, opened_a.text
    shift = Shift(
        id=uuid4(),
        company_id=w["company"].id,
        branch_id=w["branch"].id,
        terminal_id=w["terminal"].id,
        opened_by=w["a"].id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        status="open",
    )
    session.add(shift)
    await session.commit()
    me_before = (await client.get("/api/v1/auth/me", headers=w["headers"])).json()
    families_before = list(
        (
            await session.execute(
                select(AuthRefreshSession.__table__).where(
                    AuthRefreshSession.company_id == w["company"].id
                )
            )
        ).all()
    )
    company_before = (
        await session.execute(select(Company.__table__).where(Company.id == w["company"].id))
    ).one()
    key = str(uuid4())
    opened = await client.post(BASE + "/clock-in", headers=write(w["headers"], key), json=body(w))
    assert opened.status_code == 201, opened.text
    assert opened.json()["user_id"] == str(w["b"].id)
    assert opened.json()["staff_name"] == w["b"].name
    assert await count(session, Attendance, company_id=w["company"].id, clock_out_at=None) == 2
    receipt = (
        await session.execute(select(IdempotencyKey).where(IdempotencyKey.key == key))
    ).scalar_one()
    assert receipt.user_id == w["b"].id
    assert receipt.terminal_id == w["terminal"].id
    assert PASSWORD not in json.dumps(receipt.response_body)
    audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == w["company"].id,
                    AuditLog.entity_id == opened.json()["id"],
                )
            )
        )
        .scalars()
        .all()
    )
    assert audits
    assert all(a.actor_user_id == w["b"].id for a in audits)
    shared = next(a for a in audits if a.action == "staff.attendance.shared_clock_in")
    assert shared.business_details["initiated_by_user_id"] == str(w["a"].id)
    assert PASSWORD not in json.dumps([a.business_details for a in audits])
    me_after = (await client.get("/api/v1/auth/me", headers=w["headers"])).json()
    assert me_before == me_after
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
        == families_before
    )
    assert (
        await session.execute(select(Company.__table__).where(Company.id == w["company"].id))
    ).one() == company_before
    assert await count(session, GoogleSheetsDelivery, company_id=w["company"].id) == 0
    await session.refresh(shift)
    assert shift.status == "open"
    assert shift.opened_by == w["a"].id
    directory = await client.get(BASE + "/people", headers=w["headers"])
    assert directory.status_code == 200
    person = next(p for p in directory.json()["items"] if p["id"] == str(w["b"].id))
    assert set(person) == {"id", "name", "on_duty", "attendance_id"}
    assert person["on_duty"]
    assert person["attendance_id"] == opened.json()["id"]


async def test_response_loss_retry_reverifies_credentials_and_excludes_password_from_hash(
    client, session, shared_world
):
    w = shared_world
    key = str(uuid4())
    headers = write(w["headers"], key)
    first = await client.post(BASE + "/clock-in", headers=headers, json=body(w))
    assert first.status_code == 201
    rejected = await client.post(
        BASE + "/clock-in", headers=headers, json=body(w, password="wrong")
    )
    assert rejected.status_code == 401
    await session.refresh(w["b"])
    w["b"].password_hash = hash_password("changed-synthetic-password")
    await session.commit()
    replay = await client.post(
        BASE + "/clock-in", headers=headers, json=body(w, password="changed-synthetic-password")
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == first.json()
    assert await count(session, Attendance, user_id=w["b"].id) == 1
    assert (
        await count(
            session, AuditLog, company_id=w["company"].id, action="staff.attendance.shared_clock_in"
        )
        == 1
    )


async def test_duplicate_and_concurrent_actions_create_only_one_open_entry(
    client, session, shared_world
):
    w = shared_world
    key = str(uuid4())
    results = await asyncio.gather(
        *[
            client.post(BASE + "/clock-in", headers=write(w["headers"], key), json=body(w))
            for _ in range(2)
        ]
    )
    assert [r.status_code for r in results] == [201, 201]
    assert results[0].json() == results[1].json()
    duplicate = await client.post(BASE + "/clock-in", headers=write(w["headers"]), json=body(w))
    assert duplicate.status_code == 409
    assert await count(session, Attendance, user_id=w["b"].id) == 1


async def test_clock_out_exact_entry_and_break_future_constraints(client, session, shared_world):
    w = shared_world
    opened = await client.post(BASE + "/clock-in", headers=write(w["headers"]), json=body(w))
    assert opened.status_code == 201
    row = (
        await session.execute(select(Attendance).where(Attendance.id == opened.json()["id"]))
    ).scalar_one()
    row.clock_in_at = datetime.now(UTC) + timedelta(hours=1)
    await session.commit()
    payload = body(w, attendance_id=opened.json()["id"], break_minutes=0)
    future = await client.post(BASE + "/clock-out", headers=write(w["headers"]), json=payload)
    assert future.status_code == 422
    row.clock_in_at = datetime.now(UTC) - timedelta(hours=2)
    await session.commit()
    invalid_break = await client.post(
        BASE + "/clock-out", headers=write(w["headers"]), json={**payload, "break_minutes": 121}
    )
    assert invalid_break.status_code == 422
    wrong_entry = await client.post(
        BASE + "/clock-out",
        headers=write(w["headers"]),
        json={**payload, "attendance_id": str(uuid4())},
    )
    assert wrong_entry.status_code == 409
    headers = write(w["headers"])
    closed = await client.post(BASE + "/clock-out", headers=headers, json=payload)
    assert closed.status_code == 200, closed.text
    replay = await client.post(BASE + "/clock-out", headers=headers, json=payload)
    assert replay.json() == closed.json()
    assert closed.json()["break_confirmed"] is True
    duplicate = await client.post(BASE + "/clock-out", headers=write(w["headers"]), json=payload)
    assert duplicate.status_code == 409
    await session.refresh(row)
    assert row.clock_out_at is not None


@pytest.mark.parametrize("condition", ["inactive", "deleted", "wrong_branch", "no_permission"])
async def test_ineligible_target_cannot_clock_or_charge_normal_login(
    client, session, shared_world, condition, monkeypatch
):
    w = shared_world
    identity_buckets = []
    original_limiter = shared_route.enforce_login_rate_limit

    async def record_limit(request, identity):
        identity_buckets.append(identity)
        await original_limiter(request, identity)

    monkeypatch.setattr(shared_route, "enforce_login_rate_limit", record_limit)
    if condition == "inactive":
        w["b"].status = "suspended"
    elif condition == "deleted":
        w["b"].deleted_at = datetime.now(UTC)
    else:
        assignment = (
            await session.execute(select(UserRole).where(UserRole.user_id == w["b"].id))
        ).scalar_one()
        if condition == "wrong_branch":
            branch = Branch(
                id=uuid4(), company_id=w["company"].id, name="Other", invoice_series_code="OT"
            )
            session.add(branch)
            await session.flush()
            assignment.branch_id = branch.id
        elif condition == "no_permission":
            role = Role(
                id=uuid4(), company_id=w["company"].id, code="auditor", name="Audit", permissions=[]
            )
            session.add(role)
            await session.flush()
            assignment.role_id = role.id
    await session.commit()
    response = await client.post(
        BASE + "/clock-in", headers=write(w["headers"]), json=body(w, password="wrong-password")
    )
    assert response.status_code in {401, 403}, response.text
    assert identity_buckets == [f"staff:{w['company'].id}:{w['b'].id}"]
    await session.refresh(w["b"])
    assert w["b"].failed_login_count == 0
    assert w["b"].locked_until is None
    assert await count(session, AuditLog, target_user_id=w["b"].id,
        action="staff.attendance.authentication_failed") == 0
    directory = await client.get(BASE + "/people", headers=w["headers"])
    assert directory.status_code == 200
    assert str(w["b"].id) not in {p["id"] for p in directory.json()["items"]}
    assert await count(session, Attendance, user_id=w["b"].id) == 0


async def test_locked_eligibility_is_rechecked_before_replay_or_password_accounting(
    client, session, shared_world, monkeypatch
):
    w = shared_world
    headers = write(w["headers"])
    accepted = await client.post(BASE + "/clock-in", headers=headers, json=body(w))
    assert accepted.status_code == 201, accepted.text
    checks = []
    buckets = []
    original_eligibility = shared_route._staff_tenant
    original_limiter = shared_route.enforce_login_rate_limit

    async def changed_assignment(db, caller, user):
        checks.append(user.id)
        if len(checks) == 2:
            raise ForbiddenError("The branch assignment changed before verification.")
        return await original_eligibility(db, caller, user)

    async def record_limit(request, identity):
        buckets.append(identity)
        await original_limiter(request, identity)

    monkeypatch.setattr(shared_route, "_staff_tenant", changed_assignment)
    monkeypatch.setattr(shared_route, "enforce_login_rate_limit", record_limit)
    refused = await client.post(BASE + "/clock-in", headers=headers, json=body(w))
    assert refused.status_code == 403, refused.text
    assert checks == [w["b"].id, w["b"].id]
    assert buckets == [f"staff:{w['company'].id}:{w['b'].id}"]
    await session.refresh(w["b"])
    assert w["b"].failed_login_count == 0
    assert await count(session, Attendance, user_id=w["b"].id) == 1


async def test_foreign_target_and_missing_terminal_offline_attempt_rejected(
    client, session, shared_world
):
    w = shared_world
    company = Company(id=uuid4(), name="Other synthetic company")
    session.add(company)
    await session.flush()
    foreign = User(
        id=uuid4(),
        company_id=company.id,
        email=f"foreign-{uuid4()}@example.test",
        name="Foreign",
        password_hash=hash_password(PASSWORD),
        status="active",
    )
    session.add(foreign)
    await session.commit()
    wrong = await client.post(
        BASE + "/clock-in", headers=write(w["headers"]), json=body(w, user_id=str(foreign.id))
    )
    assert wrong.status_code == 401
    no_terminal = {k: v for k, v in w["headers"].items() if k != "X-Terminal-Id"}
    rejected = await client.post(BASE + "/clock-in", headers=write(no_terminal), json=body(w))
    assert rejected.status_code == 403
    offline = await client.post(
        BASE + "/clock-in",
        headers={**write(w["headers"]), "X-Offline-Captured": "true"},
        json=body(w),
    )
    assert offline.status_code == 422
    assert await count(session, Attendance, company_id=w["company"].id) == 0


async def test_target_password_failures_share_lockout_with_normal_login(
    client, session, shared_world
):
    w = shared_world
    for _ in range(5):
        response = await client.post(
            BASE + "/clock-in", headers=write(w["headers"]), json=body(w, password="wrong-password")
        )
        assert response.status_code == 401
    correct = await client.post(BASE + "/clock-in", headers=write(w["headers"]), json=body(w))
    assert correct.status_code == 401
    normal = await client.post(
        "/api/v1/auth/login", json={"email": w["b"].email, "password": PASSWORD}
    )
    assert normal.status_code == 401
    assert normal.json()["error"]["message"] == "account temporarily locked"
    await session.refresh(w["a"])
    assert w["a"].failed_login_count == 0
    assert w["a"].locked_until is None
    assert await count(session, Attendance, user_id=w["b"].id) == 0


async def test_verified_b_cannot_close_a_attendance(client, session, shared_world):
    w = shared_world
    first = await client.post(
        "/api/v1/staff/my-attendance/clock-in",
        headers=write(w["headers"]),
        json={"branch_id": str(w["branch"].id)},
    )
    assert first.status_code == 201
    response = await client.post(
        BASE + "/clock-out",
        headers=write(w["headers"]),
        json=body(w, attendance_id=first.json()["id"], break_minutes=0),
    )
    assert response.status_code == 409
    assert await count(session, Attendance, user_id=w["a"].id, clock_out_at=None) == 1
    assert (await client.get("/api/v1/staff/time-people", headers=w["headers"])).status_code == 403


async def test_domain_failure_rolls_back_and_same_intent_retries(
    client, session, shared_world, monkeypatch
):
    from app.api.v1.staff import work_time
    from app.core.errors import BusinessRuleError

    w = shared_world
    key = str(uuid4())
    original = work_time.enqueue_staff_attendance_if_enabled

    async def refuse(*args, **kwargs):
        raise BusinessRuleError("synthetic transactional failure")

    monkeypatch.setattr(work_time, "enqueue_staff_attendance_if_enabled", refuse)
    response = await client.post(BASE + "/clock-in", headers=write(w["headers"], key), json=body(w))
    assert response.status_code == 422
    assert await count(session, Attendance, user_id=w["b"].id) == 0
    assert await count(session, IdempotencyKey, key=key) == 0
    monkeypatch.setattr(work_time, "enqueue_staff_attendance_if_enabled", original)
    retried = await client.post(BASE + "/clock-in", headers=write(w["headers"], key), json=body(w))
    assert retried.status_code == 201
    assert await count(session, Attendance, user_id=w["b"].id) == 1


async def test_database_rejects_cross_company_role_before_any_clock_action(session, shared_world):
    w = shared_world
    assignment = (
        await session.execute(select(UserRole).where(UserRole.user_id == w["b"].id))
    ).scalar_one()
    company = Company(id=uuid4(), name="Synthetic other-company boundary")
    session.add(company)
    await session.flush()
    role = Role(id=uuid4(), company_id=company.id, code="staff", name="Foreign", permissions=[])
    session.add(role)
    await session.flush()
    assignment.role_id = role.id
    with pytest.raises(IntegrityError, match="user role must belong to the target user company"):
        await session.commit()
    await session.rollback()
