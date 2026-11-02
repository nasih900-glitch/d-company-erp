"""Authenticated API proofs for separate staff time and draft salary records."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import func, select

from app.api.v1.staff import work_time
from app.core.security import hash_password
from app.models import (
    AuditLog,
    Branch,
    PayrollEntry,
    Role,
    StaffWorkPolicy,
    User,
    UserRole,
)

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


async def _login(client, user, password="password1234"):  # noqa: S107 - isolated synthetic fixture
    response = await client.post(
        "/api/v1/auth/login", json={"email": user.email, "password": password}
    )
    assert response.status_code == 200
    return {"Authorization": "Bearer " + response.json()["access_token"]}


def _write(headers):
    return {**headers, "Idempotency-Key": str(uuid4())}


def _policy(**changes):
    return {
        "expected_revision": 0,
        "effective_from": "2026-11-01",
        "monthly_salary_minor": 1200000,
        "daily_presence_minutes": 720,
        "daily_break_minutes": 60,
        "working_weekday_mask": 127,
        "overtime_multiplier_basis_points": None,
        "reason": "Owner recorded draft terms; overtime unapproved",
        **changes,
    }


@pytest_asyncio.fixture
async def staff_world(client, session, seed_owner):
    company_id = seed_owner["company"].id
    role = Role(
        id=uuid4(),
        company_id=company_id,
        code="gaming_cashier",
        name="Gaming staff",
        permissions=[],
    )
    people = [
        User(
            id=uuid4(),
            company_id=company_id,
            email=f"time-{uuid4()}@example.test",
            name=name,
            password_hash=hash_password("password1234"),
            status="active",
        )
        for name in ("Synthetic Alpha", "Synthetic Beta")
    ]
    session.add_all([role, *people])
    await session.flush()
    session.add_all(
        [
            UserRole(
                id=uuid4(), user_id=user.id, role_id=role.id, branch_id=seed_owner["branch"].id
            )
            for user in people
        ]
    )
    await session.commit()
    return {
        **seed_owner,
        "people": people,
        "owner_headers": await _login(client, seed_owner["owner"]),
    }


async def test_individual_clock_records_breaks_and_draft_pay_are_separate(
    client, session, staff_world, monkeypatch
):
    w = staff_world
    clock = [datetime(2026, 11, 2, 4, tzinfo=UTC)]

    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return clock[0].astimezone(tz) if tz else clock[0].replace(tzinfo=None)

    monkeypatch.setattr(work_time, "datetime", Clock)
    opened = []
    for user in w["people"]:
        response = await client.post(
            f"/api/v1/staff/attendance/{user.id}/clock-in",
            headers=_write(w["owner_headers"]),
            json={"branch_id": str(w["branch"].id), "reason": "Owner records staff arrival"},
        )
        assert response.status_code == 201, response.text
        opened.append(response.json()["id"])
    clock[0] += timedelta(hours=12)
    first, second = w["people"]
    response = await client.post(
        f"/api/v1/staff/attendance/{first.id}/clock-out",
        headers=_write(w["owner_headers"]),
        json={
            "attendance_id": opened[0],
            "break_minutes": 60,
            "reason": "One hour actual break confirmed",
        },
    )
    assert response.status_code == 200, response.text
    await session.refresh(w["owner"])
    first_summary = await client.get(
        "/api/v1/staff/time-summary",
        headers=w["owner_headers"],
        params={"month": "2026-11", "user_id": str(first.id)},
    )
    second_summary = await client.get(
        "/api/v1/staff/time-summary",
        headers=w["owner_headers"],
        params={"month": "2026-11", "user_id": str(second.id)},
    )
    assert first_summary.status_code == second_summary.status_code == 200
    assert first_summary.json()["closed_work_seconds"] == 11 * 3600
    assert first_summary.json()["provisional_open_seconds"] == 0
    assert second_summary.json()["closed_work_seconds"] == 0
    assert second_summary.json()["provisional_open_seconds"] == 12 * 3600
    assert all(
        row.json()["payable_minor"] is None and row.json()["overtime_pay_minor"] is None
        for row in (first_summary, second_summary)
    )
    assert (
        await session.scalar(
            select(func.count())
            .select_from(PayrollEntry)
            .where(PayrollEntry.company_id == w["company"].id)
        )
        == 0
    )
    assert (
        await session.scalar(
            select(func.count())
            .select_from(AuditLog)
            .where(
                AuditLog.company_id == w["company"].id,
                AuditLog.action == "staff.attendance.clock_out",
            )
        )
        == 1
    )


async def test_staff_cannot_read_another_person_or_salary_or_write_owner_actions(
    client, session, staff_world
):
    w = staff_world
    first, second = w["people"]
    saved = await client.post(
        f"/api/v1/staff/work-policies/{first.id}",
        headers=_write(w["owner_headers"]),
        json=_policy(),
    )
    assert saved.status_code == 201, saved.text
    headers = await _login(client, first)
    own = await client.get(
        "/api/v1/staff/time-summary", headers=headers, params={"month": "2026-11"}
    )
    assert own.status_code == 200
    assert own.json()["user_id"] == str(first.id)
    assert own.json()["policy"] is None
    assert "monthly_salary_minor" not in own.text
    for method, url, body in (
        ("GET", f"/api/v1/staff/time-summary?month=2026-11&user_id={second.id}", None),
        ("GET", f"/api/v1/staff/work-policies/{first.id}", None),
        ("POST", f"/api/v1/staff/work-policies/{first.id}", _policy(expected_revision=1)),
        (
            "POST",
            f"/api/v1/staff/attendance/{second.id}/clock-in",
            {"branch_id": str(w["branch"].id), "reason": "not authorized"},
        ),
    ):
        response = await client.request(method, url, headers=_write(headers), json=body)
        assert response.status_code == 403, response.text


async def test_policy_replays_and_revisions_preserve_all_original_drafts(
    client, session, staff_world
):
    w = staff_world
    user = w["people"][0]
    path = f"/api/v1/staff/work-policies/{user.id}"
    headers = _write(w["owner_headers"])
    first = await client.post(path, headers=headers, json=_policy())
    repeated = await client.post(path, headers=headers, json=_policy())
    assert first.status_code == repeated.status_code == 201
    assert first.json() == repeated.json()
    stale = await client.post(path, headers=_write(w["owner_headers"]), json=_policy())
    assert stale.status_code == 409
    changed = await client.post(
        path,
        headers=_write(w["owner_headers"]),
        json=_policy(expected_revision=1, monthly_salary_minor=1300000),
    )
    assert changed.status_code == 201
    assert changed.json()["revision"] == 2
    history = await client.get(path, headers=w["owner_headers"])
    assert history.status_code == 200
    assert [row["monthly_salary_minor"] for row in history.json()] == [1300000, 1200000]
    assert all(row["pay_status"] == "draft" for row in history.json())
    older = await client.get(
        path, headers=w["owner_headers"], params={"limit": 1, "before_revision": 2}
    )
    assert [row["revision"] for row in older.json()] == [1]
    oversized = await client.get(
        path, headers=w["owner_headers"], params={"before_revision": 10**80}
    )
    assert oversized.status_code == 422


async def test_concurrent_policy_edits_have_one_revision_winner(client, session, staff_world):
    w = staff_world
    path = f"/api/v1/staff/work-policies/{w['people'][0].id}"
    responses = await asyncio.gather(
        *[
            client.post(
                path, headers=_write(w["owner_headers"]), json=_policy(monthly_salary_minor=amount)
            )
            for amount in (1200000, 1300000)
        ]
    )
    assert sorted(response.status_code for response in responses) == [201, 409]
    assert (
        await session.scalar(
            select(func.count())
            .select_from(StaffWorkPolicy)
            .where(StaffWorkPolicy.company_id == w["company"].id)
        )
        == 1
    )


async def test_midmonth_policy_does_not_fabricate_a_full_month_schedule(
    client, session, staff_world
):
    w = staff_world
    user = w["people"][0]
    response = await client.post(
        f"/api/v1/staff/work-policies/{user.id}",
        headers=_write(w["owner_headers"]),
        json=_policy(effective_from="2026-11-20"),
    )
    assert response.status_code == 201
    summary = await client.get(
        "/api/v1/staff/time-summary",
        headers=w["owner_headers"],
        params={"month": "2026-11", "user_id": str(user.id)},
    )
    assert summary.status_code == 200, summary.text
    assert summary.json()["policy_covers_full_month"] is False
    assert summary.json()["scheduled_work_minutes"] is None
    assert summary.json()["reference_hourly_denominator_minutes"] is None
    assert summary.json()["payable_minor"] is None


async def test_invalid_or_active_salary_payloads_never_become_payroll(client, session, staff_world):
    w = staff_world
    path = f"/api/v1/staff/work-policies/{w['people'][0].id}"
    for changes in (
        {"pay_status": "active"},
        {"monthly_salary_minor": 12.5},
        {"monthly_salary_minor": True},
        {"daily_break_minutes": 720},
        {"working_weekday_mask": 0},
        {"reason": " "},
    ):
        response = await client.post(
            path, headers=_write(w["owner_headers"]), json=_policy(**changes)
        )
        assert response.status_code == 422, response.text
    assert (
        await session.scalar(
            select(func.count())
            .select_from(StaffWorkPolicy)
            .where(StaffWorkPolicy.company_id == w["company"].id)
        )
        == 0
    )


async def test_new_branch_token_cannot_replay_an_old_other_branch_salary(
    client, session, staff_world
):
    w = staff_world
    assignment = (
        await session.execute(select(UserRole).where(UserRole.user_id == w["owner"].id))
    ).scalar_one()
    assignment.branch_id = w["branch"].id
    await session.commit()
    first_headers = _write(await _login(client, w["owner"]))
    path = f"/api/v1/staff/work-policies/{w['people'][0].id}"
    saved = await client.post(path, headers=first_headers, json=_policy())
    assert saved.status_code == 201, saved.text
    other = Branch(
        id=uuid4(),
        company_id=w["company"].id,
        name="Other synthetic branch",
        invoice_series_code="ZQ",
    )
    session.add(other)
    await session.flush()
    assignment.branch_id = other.id
    await session.commit()
    next_headers = {
        **await _login(client, w["owner"]),
        "Idempotency-Key": first_headers["Idempotency-Key"],
    }
    rejected = await client.post(path, headers=next_headers, json=_policy())
    assert rejected.status_code == 403, rejected.text
    assert "monthly_salary_minor" not in rejected.text


async def test_owner_can_close_and_read_departed_staff_time_without_restoring_access(
    client, session, staff_world
):
    w = staff_world
    user = w["people"][0]
    opened = await client.post(
        f"/api/v1/staff/attendance/{user.id}/clock-in",
        headers=_write(w["owner_headers"]),
        json={"branch_id": str(w["branch"].id), "reason": "Owner confirms arrival"},
    )
    assert opened.status_code == 201
    user.deleted_at, user.status = datetime.now(UTC), "suspended"
    assignment = (
        await session.execute(select(UserRole).where(UserRole.user_id == user.id))
    ).scalar_one()
    await session.delete(assignment)
    await session.commit()
    closed = await client.post(
        f"/api/v1/staff/attendance/{user.id}/clock-out",
        headers=_write(w["owner_headers"]),
        json={
            "attendance_id": opened.json()["id"],
            "break_minutes": 0,
            "reason": "Owner closes retained attendance after departure",
        },
    )
    assert closed.status_code == 200, closed.text
    summary = await client.get(
        "/api/v1/staff/time-summary",
        headers=w["owner_headers"],
        params={"month": datetime.now(UTC).strftime("%Y-%m"), "user_id": str(user.id)},
    )
    assert summary.status_code == 200
    assert summary.json()["entries"][0]["clock_out_at"] is not None
    await session.refresh(user)
    assert user.deleted_at is not None
    assert user.status == "suspended"


async def test_self_clock_records_explicit_break_and_replays_without_second_entry(
    client, session, staff_world, monkeypatch
):
    w = staff_world
    first, second = w["people"]
    clock = [datetime(2026, 11, 2, 4, tzinfo=UTC)]

    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return clock[0].astimezone(tz) if tz else clock[0].replace(tzinfo=None)

    monkeypatch.setattr(work_time, "datetime", Clock)
    h = await _login(client, first)
    headers = _write(h)
    body = {"branch_id": str(w["branch"].id)}
    a = await client.post("/api/v1/staff/my-attendance/clock-in", headers=headers, json=body)
    b = await client.post("/api/v1/staff/my-attendance/clock-in", headers=headers, json=body)
    assert a.status_code == b.status_code == 201
    assert a.json() == b.json()
    assert a.json()["user_id"] == str(first.id)
    assert a.json()["source"] == "app"
    clock[0] += timedelta(hours=12)
    missing = await client.post(
        "/api/v1/staff/my-attendance/clock-out",
        headers=_write(h),
        json={"attendance_id": a.json()["id"]},
    )
    assert missing.status_code == 422
    headers = _write(h)
    body = {"attendance_id": a.json()["id"], "break_minutes": 60}
    closed = await client.post("/api/v1/staff/my-attendance/clock-out", headers=headers, json=body)
    replay = await client.post("/api/v1/staff/my-attendance/clock-out", headers=headers, json=body)
    assert closed.status_code == replay.status_code == 200
    assert closed.json() == replay.json()
    summary = await client.get("/api/v1/staff/time-summary", headers=h, params={"month": "2026-11"})
    assert summary.json()["closed_work_seconds"] == 39600
    assert summary.json()["payable_minor"] is None
    other = await client.post(
        f"/api/v1/staff/attendance/{second.id}/clock-out",
        headers=_write(h),
        json={**body, "reason": "Cannot close another person's entry"},
    )
    assert other.status_code == 403
    assert len(summary.json()["entries"]) == 1


async def test_self_clock_rejects_forged_identity_branch_and_missing_idempotency(
    client, session, staff_world
):
    w = staff_world
    first, second = w["people"]
    h = await _login(client, first)
    path = "/api/v1/staff/my-attendance/clock-in"
    body = {"branch_id": str(w["branch"].id)}
    missing = await client.post(path, headers=h, json=body)
    assert missing.status_code == 422, missing.text
    forged = await client.post(path, headers=_write(h), json={**body, "user_id": str(second.id)})
    assert forged.status_code == 422
    other_branch = Branch(
        id=uuid4(),
        company_id=w["company"].id,
        name="Unassigned time branch",
        invoice_series_code="TB",
    )
    session.add(other_branch)
    await session.commit()
    wrong_branch = await client.post(
        path, headers=_write(h), json={"branch_id": str(other_branch.id)}
    )
    assert wrong_branch.status_code == 403


async def test_self_clock_concurrency_has_one_open_entry(client, session, staff_world):
    w = staff_world
    h = await _login(client, w["people"][0])
    results = await asyncio.gather(
        *[
            client.post(
                "/api/v1/staff/my-attendance/clock-in",
                headers=_write(h),
                json={"branch_id": str(w["branch"].id)},
            )
            for _ in range(2)
        ]
    )
    assert sorted(r.status_code for r in results) == [201, 409]
    summary = await client.get("/api/v1/staff/time-summary", headers=h, params={"month": "2026-11"})
    assert len(summary.json()["entries"]) == 1


async def test_own_schedule_projection_never_exposes_salary_or_owner_reason(
    client, session, staff_world
):
    w = staff_world
    user = w["people"][0]
    response = await client.post(
        f"/api/v1/staff/work-policies/{user.id}", headers=_write(w["owner_headers"]), json=_policy()
    )
    assert response.status_code == 201
    own = await client.get(
        "/api/v1/staff/time-summary",
        headers=await _login(client, user),
        params={"month": "2026-11"},
    )
    assert own.status_code == 200
    assert own.json()["schedule"] == {
        "effective_from": "2026-11-01",
        "daily_presence_minutes": 720,
        "daily_break_minutes": 60,
        "working_weekday_mask": 127,
    }
    assert own.json()["policy"] is None
    assert "monthly_salary_minor" not in own.text
    assert "Owner recorded" not in own.text
    assert own.json()["closed_work_seconds"] == 0


async def test_owner_correction_audits_original_values_replays_and_refuses_stale_edit(
    client, session, staff_world
):
    from app.models import Attendance

    w = staff_world
    user = w["people"][0]
    entered = datetime(2026, 11, 1, 4, tzinfo=UTC)
    row = Attendance(
        id=uuid4(),
        company_id=w["company"].id,
        user_id=user.id,
        branch_id=w["branch"].id,
        clock_in_at=entered,
        clock_out_at=entered + timedelta(hours=12),
        notes="Original preserved note",
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    original_updated = row.updated_at.isoformat()
    path = f"/api/v1/staff/attendance/{user.id}/{row.id}"
    body = {
        "expected_updated_at": original_updated,
        "clock_in_at": entered.isoformat(),
        "clock_out_at": row.clock_out_at.isoformat(),
        "break_minutes": 60,
        "reason": "Owner confirmed missing actual break",
    }
    h = _write(w["owner_headers"])
    corrected = await client.patch(path, headers=h, json=body)
    repeated = await client.patch(path, headers=h, json=body)
    assert corrected.status_code == repeated.status_code == 200, corrected.text
    assert corrected.json() == repeated.json()
    assert corrected.json()["break_minutes"] == 60
    stale = await client.patch(
        path, headers=_write(w["owner_headers"]), json={**body, "break_minutes": 30}
    )
    assert stale.status_code == 409
    await session.refresh(row)
    assert row.notes == "Original preserved note"
    audits = list(
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.entity_id == str(row.id), AuditLog.action == "staff.attendance.correct"
                )
            )
        ).scalars()
    )
    assert len(audits) == 1
    assert audits[0].business_details["before"]["break_minutes"] == 0
    assert audits[0].business_details["after"]["break_minutes"] == 60
    summary = await client.get(
        "/api/v1/staff/time-summary",
        headers=w["owner_headers"],
        params={"month": "2026-11", "user_id": str(user.id)},
    )
    assert summary.json()["closed_work_seconds"] == 39600
    assert summary.json()["payable_minor"] is None


async def test_corrections_reject_overlap_naive_future_and_unauthorized_inputs(
    client, session, staff_world
):
    from app.models import Attendance

    w = staff_world
    user = w["people"][0]
    entered = datetime(2026, 11, 1, 4, tzinfo=UTC)
    rows = [
        Attendance(
            id=uuid4(),
            company_id=w["company"].id,
            user_id=user.id,
            branch_id=w["branch"].id,
            clock_in_at=entered + timedelta(hours=i),
            clock_out_at=entered + timedelta(hours=i + 2),
        )
        for i in (0, 3)
    ]
    session.add_all(rows)
    await session.commit()
    row = rows[0]
    await session.refresh(row)
    path = f"/api/v1/staff/attendance/{user.id}/{row.id}"
    body = {
        "expected_updated_at": row.updated_at.isoformat(),
        "clock_in_at": entered.isoformat(),
        "clock_out_at": (entered + timedelta(hours=2)).isoformat(),
        "break_minutes": 0,
        "reason": "Review actual record",
    }
    cases = [
        ({"clock_out_at": (entered + timedelta(hours=4)).isoformat()}, 409),
        ({"clock_in_at": "2026-11-01T04:00:00"}, 422),
        ({"reason": " "}, 422),
        ({"clock_out_at": None, "break_minutes": 60}, 422),
        ({"break_minutes": 121}, 422),
        ({"clock_out_at": (datetime.now(UTC) + timedelta(days=1)).isoformat()}, 422),
    ]
    for delta, expected in cases:
        response = await client.patch(
            path, headers=_write(w["owner_headers"]), json={**body, **delta}
        )
        assert response.status_code == expected, response.text
    denied = await client.patch(path, headers=_write(await _login(client, user)), json=body)
    assert denied.status_code == 403
    await session.refresh(row)
    assert row.clock_out_at == entered + timedelta(hours=2)


async def test_self_close_concurrency_has_one_winner_and_exact_break(client, session, staff_world):
    from app.models import Attendance

    w = staff_world
    user = w["people"][0]
    row = Attendance(
        id=uuid4(),
        company_id=w["company"].id,
        user_id=user.id,
        branch_id=w["branch"].id,
        clock_in_at=datetime.now(UTC) - timedelta(hours=12),
    )
    session.add(row)
    await session.commit()
    h = await _login(client, user)
    responses = await asyncio.gather(
        *[
            client.post(
                "/api/v1/staff/my-attendance/clock-out",
                headers=_write(h),
                json={"attendance_id": str(row.id), "break_minutes": 60},
            )
            for _ in range(2)
        ]
    )
    assert sorted(r.status_code for r in responses) == [200, 409]
    await session.refresh(row)
    assert row.break_minutes == 60
    assert row.clock_out_at is not None


async def test_legacy_and_new_self_clock_share_one_parent_lock(client, session, staff_world):
    w = staff_world
    h = await _login(client, w["people"][0])
    body = {"branch_id": str(w["branch"].id)}
    old, new = await asyncio.gather(
        client.post("/api/v1/staff/attendance/clock-in", headers=h, json=body),
        client.post("/api/v1/staff/my-attendance/clock-in", headers=_write(h), json=body),
    )
    assert 201 in (old.status_code, new.status_code)
    assert sorted([old.status_code, new.status_code]) in ([201, 409], [201, 422])
    summary = await client.get(
        "/api/v1/staff/time-summary",
        headers=h,
        params={"month": datetime.now(UTC).strftime("%Y-%m")},
    )
    assert summary.status_code == 200
    assert len(summary.json()["entries"]) == 1


async def test_time_people_is_owner_scoped_minimal_paginated_and_keeps_departed_history(
    client, session, staff_world
):
    from app.models import Attendance

    w = staff_world
    first, second = w["people"]
    first.deleted_at, first.status = datetime.now(UTC), "suspended"
    role = (
        await session.execute(select(UserRole).where(UserRole.user_id == first.id))
    ).scalar_one()
    await session.delete(role)
    session.add(
        Attendance(
            id=uuid4(),
            company_id=w["company"].id,
            user_id=first.id,
            branch_id=w["branch"].id,
            clock_in_at=datetime.now(UTC) - timedelta(hours=2),
            clock_out_at=datetime.now(UTC) - timedelta(hours=1),
        )
    )
    owner_role = (
        await session.execute(select(UserRole).where(UserRole.user_id == w["owner"].id))
    ).scalar_one()
    owner_role.branch_id = w["branch"].id
    await session.commit()
    h = await _login(client, w["owner"])
    seen, cursor = [], None
    for _ in range(10):
        response = await client.get(
            "/api/v1/staff/time-people",
            headers=h,
            params={"limit": 1, **({"after_id": cursor} if cursor else {})},
        )
        assert response.status_code == 200, response.text
        page = response.json()
        for item in page["items"]:
            assert set(item) == {"id", "name", "status", "departed"}
            seen.append(item)
        cursor = page["next_cursor"]
        if cursor is None:
            break
    else:
        pytest.fail("time people pagination did not terminate")
    assert len({row["id"] for row in seen}) == len(seen)
    departed = next(row for row in seen if row["id"] == str(first.id))
    assert departed["departed"] is True
    assert departed["status"] == "suspended"
    assert any(row["id"] == str(second.id) for row in seen)
    denied = await client.get("/api/v1/staff/time-people", headers=await _login(client, second))
    assert denied.status_code == 403
    await session.refresh(first)
    assert first.deleted_at is not None


async def test_time_people_excludes_unassigned_users_at_other_branch(client, session, staff_world):
    w = staff_world
    owner_role = (
        await session.execute(select(UserRole).where(UserRole.user_id == w["owner"].id))
    ).scalar_one()
    owner_role.branch_id = w["branch"].id
    other = Branch(
        id=uuid4(), company_id=w["company"].id, name="Other hours branch", invoice_series_code="TC"
    )
    session.add(other)
    await session.flush()
    second = w["people"][1]
    assignment = (
        await session.execute(select(UserRole).where(UserRole.user_id == second.id))
    ).scalar_one()
    assignment.branch_id = other.id
    await session.commit()
    response = await client.get(
        "/api/v1/staff/time-people", headers=await _login(client, w["owner"])
    )
    assert response.status_code == 200
    assert str(second.id) not in [row["id"] for row in response.json()["items"]]


async def test_legacy_zero_break_requires_explicit_owner_confirmation_before_work_total(
    client, session, staff_world
):
    from app.models import Attendance

    w = staff_world
    user = w["people"][0]
    entered = datetime(2026, 11, 1, 4, tzinfo=UTC)
    row = Attendance(
        id=uuid4(),
        company_id=w["company"].id,
        user_id=user.id,
        branch_id=w["branch"].id,
        clock_in_at=entered,
        clock_out_at=entered + timedelta(hours=12),
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    params = {"month": "2026-11", "user_id": str(user.id)}
    old = await client.get("/api/v1/staff/time-summary", headers=w["owner_headers"], params=params)
    assert old.status_code == 200
    assert old.json()["closed_gross_seconds"] == 43200
    assert old.json()["closed_work_seconds"] is None
    assert old.json()["entries"][0]["break_confirmed"] is False
    assert old.json()["review_attendance_ids"] == [str(row.id)]
    confirmed = await client.patch(
        f"/api/v1/staff/attendance/{user.id}/{row.id}",
        headers=_write(w["owner_headers"]),
        json={
            "expected_updated_at": row.updated_at.isoformat(),
            "clock_in_at": entered.isoformat(),
            "clock_out_at": row.clock_out_at.isoformat(),
            "break_minutes": 0,
            "reason": "Owner explicitly confirms no break was taken on this entry",
        },
    )
    assert confirmed.status_code == 200, confirmed.text
    assert confirmed.json()["break_confirmed"] is True
    fixed = await client.get(
        "/api/v1/staff/time-summary", headers=w["owner_headers"], params=params
    )
    assert fixed.json()["closed_work_seconds"] == 43200
    assert fixed.json()["entries"][0]["break_confirmed"] is True
    assert fixed.json()["review_attendance_ids"] == []
    assert fixed.json()["payable_minor"] is None


@pytest.mark.parametrize("prior_confirmed_close", [False, True])
async def test_open_correction_or_reopen_cannot_confirm_later_legacy_zero_break(
    client, session, staff_world, prior_confirmed_close
):
    from app.models import Attendance

    w = staff_world
    user = w["people"][0]
    entered = datetime(2026, 11, 1, 4, tzinfo=UTC)
    row = Attendance(
        id=uuid4(),
        company_id=w["company"].id,
        user_id=user.id,
        branch_id=w["branch"].id,
        clock_in_at=entered,
        clock_out_at=entered + timedelta(hours=12) if prior_confirmed_close else None,
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    path = f"/api/v1/staff/attendance/{user.id}/{row.id}"
    version = row.updated_at.isoformat()
    if prior_confirmed_close:
        confirmed = await client.patch(
            path,
            headers=_write(w["owner_headers"]),
            json={
                "expected_updated_at": version,
                "clock_in_at": entered.isoformat(),
                "clock_out_at": row.clock_out_at.isoformat(),
                "break_minutes": 0,
                "reason": "Explicit earlier zero break confirmation",
            },
        )
        assert confirmed.status_code == 200
        assert confirmed.json()["break_confirmed"] is True
        version = confirmed.json()["updated_at"]
    opened = await client.patch(
        path,
        headers=_write(w["owner_headers"]),
        json={
            "expected_updated_at": version,
            "clock_in_at": entered.isoformat(),
            "clock_out_at": None,
            "break_minutes": 0,
            "reason": "Owner confirms departure is not yet recorded",
        },
    )
    assert opened.status_code == 200
    assert opened.json()["break_confirmed"] is False
    legacy = await client.post(
        "/api/v1/staff/attendance/clock-out", headers=await _login(client, user)
    )
    assert legacy.status_code == 200
    result = await client.get(
        "/api/v1/staff/time-summary",
        headers=w["owner_headers"],
        params={"month": "2026-11", "user_id": str(user.id)},
    )
    assert result.status_code == 200
    assert result.json()["closed_gross_seconds"] > 0
    assert result.json()["closed_work_seconds"] is None
    assert result.json()["entries"][0]["break_confirmed"] is False
    assert result.json()["review_attendance_ids"] == [str(row.id)]


@pytest.mark.parametrize("legacy", [False, True])
async def test_attendance_updates_keep_versions_strictly_monotonic_even_ahead_of_db_clock(
    client, session, staff_world, legacy
):
    from app.models import Attendance

    w = staff_world
    user = w["people"][0]
    ahead = datetime.now(UTC) + timedelta(days=1)
    row = Attendance(
        id=uuid4(),
        company_id=w["company"].id,
        user_id=user.id,
        branch_id=w["branch"].id,
        clock_in_at=datetime(2026, 11, 1, 4, tzinfo=UTC),
        updated_at=ahead,
    )
    session.add(row)
    await session.commit()
    if legacy:
        response = await client.post(
            "/api/v1/staff/attendance/clock-out", headers=await _login(client, user)
        )
    else:
        response = await client.post(
            f"/api/v1/staff/attendance/{user.id}/clock-out",
            headers=_write(w["owner_headers"]),
            json={
                "attendance_id": str(row.id),
                "break_minutes": 0,
                "reason": "Actual zero break explicitly confirmed",
            },
        )
    assert response.status_code == 200, response.text
    await session.refresh(row)
    assert row.updated_at == ahead + timedelta(microseconds=1)
    if not legacy:
        corrected = await client.patch(
            f"/api/v1/staff/attendance/{user.id}/{row.id}",
            headers=_write(w["owner_headers"]),
            json={
                "expected_updated_at": row.updated_at.isoformat(),
                "clock_in_at": row.clock_in_at.isoformat(),
                "clock_out_at": row.clock_out_at.isoformat(),
                "break_minutes": 0,
                "reason": "Same facts explicitly reverified with a new version",
            },
        )
        assert corrected.status_code == 200, corrected.text
        assert datetime.fromisoformat(corrected.json()["updated_at"]) == ahead + timedelta(
            microseconds=2
        )
        assert corrected.json()["break_confirmed"] is True


@pytest.mark.parametrize("invalid", ["future", "excess_break", "negative_break"])
async def test_legacy_clock_out_refuses_invalid_time_without_modifying_the_entry(
    client, session, staff_world, invalid
):
    from app.models import Attendance

    w = staff_world
    user = w["people"][0]
    now = datetime.now(UTC)
    entered = now + timedelta(days=1) if invalid == "future" else now - timedelta(hours=1)
    break_minutes = 61 if invalid == "excess_break" else -1 if invalid == "negative_break" else 0
    row = Attendance(
        id=uuid4(),
        company_id=w["company"].id,
        user_id=user.id,
        branch_id=w["branch"].id,
        clock_in_at=entered,
        break_minutes=break_minutes,
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    version = row.updated_at
    result = await client.post(
        "/api/v1/staff/attendance/clock-out", headers=await _login(client, user)
    )
    assert result.status_code == 422, result.text
    await session.refresh(row)
    assert row.clock_out_at is None
    assert row.updated_at == version
    assert row.break_minutes == break_minutes


async def _enable_pending_staff_sink(client, session, world):
    company = world["company"]
    company.google_sheets_mirror_enabled = True
    company.google_sheets_configuration_id = uuid4()
    company.google_sheets_configured_at = datetime.now(UTC) - timedelta(hours=1)
    company.google_sheets_webhook_url = (
        "https://script.google.com/macros/s/synthetic-no-network/exec"
    )
    company.google_sheets_signing_secret_ciphertext = "synthetic-never-decrypted"
    company.google_sheets_export_mode = "daily_collections"
    company.google_sheets_summary_branch_id = world["branch"].id
    company.google_sheets_summary_start_date = datetime.now(UTC).date()
    company.google_sheets_summary_timezone = "Asia/Kolkata"
    await session.commit()
    response = await client.post(
        "/api/v1/settings/google-sheets/staff-hours/test", headers=world["owner_headers"]
    )
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "pending"
    return company.google_sheets_configuration_id


async def test_staff_handlers_enqueue_atomic_facts_without_pay_or_duplicate_replay(
    client, session, staff_world
):
    from app.models import Expense
    from app.models.google_sheets_delivery import GoogleSheetsDelivery
    from app.services.integrations.google_sheets_mirror import _delivery_dispatchable_clause

    w = staff_world
    generation = await _enable_pending_staff_sink(client, session, w)
    user, unconfigured = w["people"]
    policy_headers = _write(w["owner_headers"])
    path = f"/api/v1/staff/work-policies/{user.id}"
    policy = await client.post(path, headers=policy_headers, json=_policy())
    replay = await client.post(path, headers=policy_headers, json=_policy())
    assert policy.status_code == replay.status_code == 201
    assert policy.json() == replay.json()
    rows = list(
        (
            await session.execute(
                select(GoogleSheetsDelivery).where(
                    GoogleSheetsDelivery.company_id == w["company"].id,
                    GoogleSheetsDelivery.event_type == "staff_hours.fact",
                )
            )
        ).scalars()
    )
    assert len(rows) == 1
    assert rows[0].payload["record_kind"] == "policy"
    own = await _login(client, user)
    opened = await client.post(
        "/api/v1/staff/my-attendance/clock-in",
        headers=_write(own),
        json={"branch_id": str(w["branch"].id)},
    )
    assert opened.status_code == 201
    record = opened.json()
    entered = datetime.now(UTC) - timedelta(hours=12)
    correction = await client.patch(
        f"/api/v1/staff/attendance/{user.id}/{record['id']}",
        headers=_write(w["owner_headers"]),
        json={
            "expected_updated_at": record["updated_at"],
            "clock_in_at": entered.isoformat(),
            "clock_out_at": None,
            "break_minutes": 0,
            "reason": "Synthetic arrival correction for complete staff workflow",
        },
    )
    assert correction.status_code == 200, correction.text
    h = _write(own)
    body = {"attendance_id": record["id"], "break_minutes": 60}
    closed = await client.post("/api/v1/staff/my-attendance/clock-out", headers=h, json=body)
    replayed = await client.post("/api/v1/staff/my-attendance/clock-out", headers=h, json=body)
    assert closed.status_code == replayed.status_code == 200
    assert closed.json() == replayed.json()
    assert closed.json()["break_confirmed"] is True
    facts = list(
        (
            await session.execute(
                select(GoogleSheetsDelivery)
                .where(
                    GoogleSheetsDelivery.company_id == w["company"].id,
                    GoogleSheetsDelivery.source_type == "staff_hours.attendance",
                    GoogleSheetsDelivery.source_id == record["id"],
                )
                .order_by(GoogleSheetsDelivery.source_revision)
            )
        ).scalars()
    )
    assert len(facts) == 3  # arrival, open correction, explicit close; replay adds nothing.
    assert [row.payload["work_seconds"] for row in facts[:2]] == [None, None]
    expected_seconds = (
        datetime.fromisoformat(closed.json()["clock_out_at"]) - entered
    ) // timedelta(seconds=1) - 3600
    assert facts[-1].payload["work_seconds"] == expected_seconds
    assert facts[-1].payload["actual_break_minutes"] == 60
    assert all(row.configuration_id == generation for row in facts)
    assert len({row.source_revision for row in facts}) == 3
    assert all(row.payload["payable_minor"] is None for row in facts)
    assert all(row.payload["overtime_pay_minor"] is None for row in facts)
    assert all("reason" not in row.payload and "email" not in row.payload for row in facts)
    dispatchable = list(
        (
            await session.execute(
                select(GoogleSheetsDelivery.id).where(
                    GoogleSheetsDelivery.company_id == w["company"].id,
                    _delivery_dispatchable_clause(),
                    GoogleSheetsDelivery.event_type == "staff_hours.fact",
                )
            )
        ).scalars()
    )
    assert dispatchable == []  # queued capability is not verified, no external delivery.
    other = await client.post(
        f"/api/v1/staff/attendance/{unconfigured.id}/clock-in",
        headers=_write(w["owner_headers"]),
        json={
            "branch_id": str(w["branch"].id),
            "reason": "Synthetic unconfigured attendance still works",
        },
    )
    assert other.status_code == 201
    assert (
        await session.scalar(
            select(func.count())
            .select_from(GoogleSheetsDelivery)
            .where(GoogleSheetsDelivery.source_id == other.json()["id"])
        )
        == 0
    )
    for model in (PayrollEntry, Expense):
        assert (
            await session.scalar(
                select(func.count()).select_from(model).where(model.company_id == w["company"].id)
            )
            == 0
        )
    await session.refresh(w["company"])
    assert w["company"].google_sheets_configuration_id == generation
    assert w["company"].google_sheets_export_mode == "daily_collections"


async def test_failure_after_sheet_fact_enqueue_rolls_back_attendance_and_idempotency(
    client, session, staff_world, monkeypatch
):
    from app.core.errors import BusinessRuleError
    from app.models import Attendance
    from app.models.google_sheets_delivery import GoogleSheetsDelivery

    w = staff_world
    await _enable_pending_staff_sink(client, session, w)
    user = w["people"][0]
    saved = await client.post(
        f"/api/v1/staff/work-policies/{user.id}", headers=_write(w["owner_headers"]), json=_policy()
    )
    assert saved.status_code == 201
    before = await session.scalar(
        select(func.count())
        .select_from(GoogleSheetsDelivery)
        .where(GoogleSheetsDelivery.company_id == w["company"].id)
    )
    original = work_time.enqueue_staff_attendance_if_enabled

    async def append_then_refuse(*args, **kwargs):
        result = await original(*args, **kwargs)
        assert result is not None
        raise BusinessRuleError("Synthetic failure after durable fact enqueue")

    monkeypatch.setattr(work_time, "enqueue_staff_attendance_if_enabled", append_then_refuse)
    h = _write(await _login(client, user))
    body = {"branch_id": str(w["branch"].id)}
    rejected = await client.post("/api/v1/staff/my-attendance/clock-in", headers=h, json=body)
    assert rejected.status_code == 422
    assert (
        await session.scalar(
            select(func.count())
            .select_from(Attendance)
            .where(Attendance.company_id == w["company"].id, Attendance.user_id == user.id)
        )
        == 0
    )
    assert (
        await session.scalar(
            select(func.count())
            .select_from(GoogleSheetsDelivery)
            .where(GoogleSheetsDelivery.company_id == w["company"].id)
        )
        == before
    )
    monkeypatch.setattr(work_time, "enqueue_staff_attendance_if_enabled", original)
    retried = await client.post("/api/v1/staff/my-attendance/clock-in", headers=h, json=body)
    assert retried.status_code == 201, retried.text
