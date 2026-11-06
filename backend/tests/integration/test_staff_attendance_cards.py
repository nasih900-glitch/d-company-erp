"""Real PostgreSQL privacy/period/pagination proofs for shared clocking cards."""

from __future__ import annotations

import base64
import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import event, func, select

from app.core.security import hash_password, issue_access_token
from app.models import (
    Attendance,
    Branch,
    Company,
    PayrollEntry,
    Role,
    StaffWorkPolicy,
    User,
    UserRole,
)

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]
BASE = "/api/v1/staff/attendance-cards"
PASSWORD = "synthetic-attendance-card-password"


async def login(client, user):
    response = await client.post(
        "/api/v1/auth/login", json={"email": user.email, "password": PASSWORD}
    )
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["access_token"]}


def params(**changes):
    return {"period": "month", "start": "2026-11-01", **changes}


def fact(world, person, entered, exited=None, *, branch=None, **changes):
    return Attendance(
        id=uuid4(),
        company_id=world["company"].id,
        user_id=person.id,
        branch_id=(branch or world["branch"]).id,
        clock_in_at=entered,
        clock_out_at=exited,
        break_minutes=60,
        source="manual",
        notes="PRIVATE NOTE",
        **changes,
    )


def assert_facts_only(body):
    assert set(body) == {
        "period",
        "period_start",
        "period_end_exclusive",
        "timezone",
        "as_of",
        "scope_branch_id",
        "current_open_attendance",
        "items",
        "next_cursor",
    }
    expected_fact = {
        "id",
        "user_id",
        "branch_id",
        "local_date",
        "clock_in_at",
        "clock_out_at",
        "is_open",
    }
    for card in body["items"]:
        assert set(card) == {
            "user_id",
            "name",
            "clock_in_day_count",
            "entries",
            "continued_from_previous",
            "entries_continued",
        }
        for row in card["entries"]:
            assert set(row) == expected_fact
    if body["current_open_attendance"] is not None:
        assert set(body["current_open_attendance"]) == expected_fact
    raw = json.dumps(body)
    for private in (
        "break_minutes",
        "break_confirmed",
        "notes",
        "source",
        "login_identifier",
        "email",
        "closed_gross_seconds",
        "closed_work_seconds",
        "provisional_open_seconds",
        "salary",
        "payable",
        "policy",
        "schedule",
        "overtime",
        "PRIVATE NOTE",
    ):
        assert private not in raw


@pytest_asyncio.fixture
async def card_world(client, session, seed_owner):
    seed_owner["company"].timezone = "Asia/Kolkata"
    role = Role(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        code="gaming_cashier",
        name="Synthetic attendance viewers",
        permissions=[],
    )
    people = [
        User(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            email=f"cards-{uuid4()}@example.test",
            name=name,
            password_hash=hash_password(PASSWORD),
            status="active",
        )
        for name in ("Synthetic Viewer", "Synthetic Colleague", "Synthetic Departed")
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
    headers = await login(client, people[0])
    owner_response = await client.post(
        "/api/v1/auth/login", json={"email": seed_owner["owner"].email, "password": "password1234"}
    )
    assert owner_response.status_code == 200
    return {
        **seed_owner,
        "a": people[0],
        "b": people[1],
        "departed": people[2],
        "role": role,
        "headers": headers,
        "owner_headers": {"Authorization": "Bearer " + owner_response.json()["access_token"]},
    }


async def test_shared_cards_exclude_all_private_fields_and_include_departed_clocking_people(
    client, session, card_world
):
    w = card_world
    entered = datetime(2026, 11, 2, 4, tzinfo=UTC)
    first = fact(w, w["a"], entered, entered + timedelta(hours=12))
    departed = fact(w, w["departed"], entered, entered + timedelta(hours=8))
    w["departed"].status = "inactive"
    w["departed"].deleted_at = datetime.now(UTC)
    session.add_all([first, departed])
    session.add(
        StaffWorkPolicy(
            id=uuid4(),
            company_id=w["company"].id,
            user_id=w["a"].id,
            actor_user_id=w["owner"].id,
            revision=1,
            effective_from=entered.date(),
            monthly_salary_minor=1200000,
            daily_presence_minutes=720,
            daily_break_minutes=60,
            working_weekday_mask=127,
            pay_status="draft",
            reason="PRIVATE POLICY",
        )
    )
    await session.commit()
    before = list(
        (
            await session.execute(
                select(Attendance.__table__).where(Attendance.company_id == w["company"].id)
            )
        ).all()
    )
    for headers in (w["headers"], w["owner_headers"]):
        result = await client.get(BASE, headers=headers, params=params())
        assert result.status_code == 200, result.text
        body = result.json()
        assert_facts_only(body)
        assert {c["user_id"] for c in body["items"]} == {str(w["a"].id), str(w["departed"].id)}
        assert all(c["clock_in_day_count"] == 1 for c in body["items"])
        assert "1200000" not in result.text
        assert "PRIVATE POLICY" not in result.text
    assert (
        list(
            (
                await session.execute(
                    select(Attendance.__table__).where(Attendance.company_id == w["company"].id)
                )
            ).all()
        )
        == before
    )
    assert (
        await session.execute(
            select(func.count())
            .select_from(PayrollEntry)
            .where(PayrollEntry.company_id == w["company"].id)
        )
    ).scalar_one() == 0


async def test_company_timezone_week_month_overnight_and_distinct_clock_in_day_counts(
    client, session, card_world
):
    w = card_world
    # Local Sunday Nov1 23:30 -> Monday Nov2 01:30 remains a Nov1 clock-in day.
    entered = datetime(2026, 11, 1, 18, 0, tzinfo=UTC)
    rows = [
        fact(w, w["b"], entered, entered + timedelta(hours=2)),
        fact(w, w["b"], entered + timedelta(hours=5), entered + timedelta(hours=6)),
        fact(w, w["b"], entered + timedelta(hours=8), entered + timedelta(hours=9)),
        fact(
            w,
            w["b"],
            datetime(2026, 11, 8, 18, 31, tzinfo=UTC),
            datetime(2026, 11, 8, 19, 0, tzinfo=UTC),
        ),
    ]
    session.add_all(rows)
    await session.commit()
    result = await client.get(
        BASE, headers=w["headers"], params=params(period="week", start="2026-11-02")
    )
    assert result.status_code == 200, result.text
    body = result.json()
    assert_facts_only(body)
    assert body["period_end_exclusive"] == "2026-11-09"
    card = body["items"][0]
    assert card["clock_in_day_count"] == 1
    assert {r["id"] for r in card["entries"]} == {str(rows[1].id), str(rows[2].id)}
    monthly = await client.get(BASE, headers=w["headers"], params=params())
    assert monthly.status_code == 200
    card = monthly.json()["items"][0]
    assert card["clock_in_day_count"] == 3
    overnight = next(r for r in card["entries"] if r["id"] == str(rows[0].id))
    assert overnight["local_date"] == "2026-11-01"
    assert overnight["clock_out_at"] != overnight["clock_in_at"]


async def test_own_open_fact_outside_period_keeps_self_clock_out_usable(
    client, session, card_world
):
    w = card_world
    own = fact(w, w["a"], datetime(2026, 10, 31, 4, tzinfo=UTC))
    session.add(own)
    await session.commit()
    result = await client.get(BASE, headers=w["headers"], params=params())
    assert result.status_code == 200, result.text
    body = result.json()
    assert_facts_only(body)
    assert body["items"] == []
    assert body["next_cursor"] is None
    assert body["current_open_attendance"]["id"] == str(own.id)
    closed = await client.post(
        "/api/v1/staff/my-attendance/clock-out",
        headers={**w["headers"], "Idempotency-Key": str(uuid4())},
        json={"attendance_id": str(own.id), "break_minutes": 60},
    )
    assert closed.status_code == 200, closed.text
    assert closed.json()["break_minutes"] == 60
    assert closed.json()["break_confirmed"]
    refreshed = await client.get(BASE, headers=w["headers"], params=params())
    assert refreshed.json()["current_open_attendance"] is None


async def test_row_pagination_keeps_complete_day_counts_and_exact_continuations_without_n_plus_one(
    client, session, card_world
):
    w = card_world
    start = datetime(2026, 11, 2, 4, tzinfo=UTC)
    rows = [
        fact(w, w["b"], start + timedelta(days=i), start + timedelta(days=i, hours=1))
        for i in range(5)
    ]
    session.add_all(rows)
    await session.commit()
    all_ids = []
    after = None
    expected_counts = []
    statements = []

    def capture(_, __, statement, ___, ____, _____):
        statements.append(statement)

    event.listen(session.bind.sync_engine, "before_cursor_execute", capture)
    try:
        for page in range(3):
            request = params(limit=2)
            if after:
                request["after"] = after
            result = await client.get(BASE, headers=w["headers"], params=request)
            assert result.status_code == 200, result.text
            body = result.json()
            assert_facts_only(body)
            assert len(body["items"]) == 1
            card = body["items"][0]
            expected_counts.append(card["clock_in_day_count"])
            assert card["continued_from_previous"] is (page > 0)
            assert card["entries_continued"] is (page < 2)
            all_ids.extend(r["id"] for r in card["entries"])
            after = body["next_cursor"]
        assert after is None
    finally:
        event.remove(session.bind.sync_engine, "before_cursor_execute", capture)
    assert expected_counts == [5, 5, 5]
    assert all_ids == [str(r.id) for r in rows]
    assert len(set(all_ids)) == 5
    assert not any(
        "staff_work_policies" in sql.lower() or "audit_log" in sql.lower() for sql in statements
    )
    # Five card-data queries per continuation at most: fixed cost, never per person/entry.
    card_queries = [s for s in statements if "from attendance" in s.lower()]
    assert len(card_queries) <= 11


async def test_foreign_company_branch_and_branchless_staff_cannot_leak_shared_facts(
    client, session, card_world
):
    w = card_world
    other_branch = Branch(
        id=uuid4(), company_id=w["company"].id, name="Other", invoice_series_code="OT"
    )
    foreign = Company(id=uuid4(), name="Foreign")
    session.add_all([other_branch, foreign])
    await session.flush()
    foreign_branch = Branch(
        id=uuid4(), company_id=foreign.id, name="Foreign", invoice_series_code="FG"
    )
    foreign_user = User(
        id=uuid4(),
        company_id=foreign.id,
        email=f"foreign-{uuid4()}@example.test",
        name="FOREIGN SECRET",
        password_hash=hash_password(PASSWORD),
        status="active",
    )
    session.add_all([foreign_branch, foreign_user])
    await session.flush()
    at = datetime(2026, 11, 2, 4, tzinfo=UTC)
    session.add_all(
        [
            fact(w, w["b"], at, at + timedelta(hours=1), branch=other_branch),
            Attendance(
                id=uuid4(),
                company_id=foreign.id,
                user_id=foreign_user.id,
                branch_id=foreign_branch.id,
                clock_in_at=at,
                clock_out_at=at + timedelta(hours=1),
                source="manual",
                break_minutes=0,
            ),
        ]
    )
    await session.commit()
    result = await client.get(BASE, headers=w["headers"], params=params())
    assert result.status_code == 200
    assert result.json()["items"] == []
    assert "FOREIGN SECRET" not in result.text
    owner = await client.get(BASE, headers=w["owner_headers"], params=params())
    assert owner.status_code == 200
    assert owner.json()["items"] == []  # Normal Owner login has an explicit default branch.
    await session.refresh(w["owner"])
    branchless_owner = issue_access_token(
        user_id=w["owner"].id,
        company_id=w["company"].id,
        branch_id=None,
        roles=["owner"],
        auth_version=w["owner"].auth_version,
    )
    all_owned = await client.get(
        BASE, headers={"Authorization": "Bearer " + branchless_owner}, params=params()
    )
    assert all_owned.status_code == 200
    assert {r["branch_id"] for c in all_owned.json()["items"] for r in c["entries"]} == {
        str(other_branch.id)
    }
    assert "FOREIGN SECRET" not in all_owned.text
    assert "FOREIGN SECRET" not in owner.text
    missing_auth = await client.get(BASE, params=params())
    assert missing_auth.status_code == 401
    await session.refresh(w["a"])
    branchless = issue_access_token(
        user_id=w["a"].id,
        company_id=w["company"].id,
        branch_id=None,
        roles=["gaming_cashier"],
        auth_version=w["a"].auth_version,
    )
    refused = await client.get(
        BASE, headers={"Authorization": "Bearer " + branchless}, params=params()
    )
    assert refused.status_code == 403
    assert "verified branch" in refused.text


@pytest.mark.parametrize(
    "change",
    [
        {"period": "day"},
        {"period": "week", "start": "2026-11-03"},
        {"start": "2026-11-02"},
        {"start": "1999-11-01"},
        {"limit": 0},
        {"limit": 101},
        {"after": "not-a-cursor"},
    ],
)
async def test_bad_period_bounds_and_cursor_are_rejected_without_fallback(
    client, card_world, change
):
    result = await client.get(BASE, headers=card_world["headers"], params=params(**change))
    assert result.status_code == 422, result.text


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("company_id", "foreign"),
        ("branch_id", "foreign"),
        ("period", "week"),
        ("start", "2026-12-01"),
        ("timezone", "UTC"),
        ("attendance_id", "foreign"),
        ("version", True),
        ("extra_field", "unknown"),
    ],
)
async def test_cursor_is_bound_to_scope_period_and_exact_existing_position(
    client, session, card_world, field, value
):
    w = card_world
    at = datetime(2026, 11, 2, 4, tzinfo=UTC)
    session.add_all(
        [fact(w, w["b"], at + timedelta(days=i), at + timedelta(days=i, hours=1)) for i in range(2)]
    )
    await session.commit()
    first = await client.get(BASE, headers=w["headers"], params=params(limit=1))
    assert first.status_code == 200
    cursor = first.json()["next_cursor"]
    decoded = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
    decoded[field] = str(uuid4()) if value == "foreign" else value
    altered = base64.urlsafe_b64encode(json.dumps(decoded).encode()).decode().rstrip("=")
    result = await client.get(BASE, headers=w["headers"], params=params(after=altered))
    assert result.status_code == 422, result.text


async def test_private_summary_403_and_independent_clock_receipt_compatibility(
    client, session, card_world
):
    w = card_world
    private = await client.get(
        "/api/v1/staff/time-summary", headers=w["headers"], params={"month": "2026-11"}
    )
    assert private.status_code == 403
    assert private.json()["error"]["message"] == (
        "Update the app to view attendance clock-in records. "
        "Detailed hours, schedules and pay are Owner-only."
    )
    assert "closed_gross_seconds" not in private.json()
    assert "schedule" not in private.json()
    key = str(uuid4())
    request = {"branch_id": str(w["branch"].id)}
    opened = await client.post(
        "/api/v1/staff/my-attendance/clock-in",
        headers={**w["headers"], "Idempotency-Key": key},
        json=request,
    )
    assert opened.status_code == 201, opened.text
    replay = await client.post(
        "/api/v1/staff/my-attendance/clock-in",
        headers={**w["headers"], "Idempotency-Key": key},
        json=request,
    )
    assert replay.status_code == 201
    assert replay.json() == opened.json()
    assert {"break_minutes", "break_confirmed", "updated_at", "source"} <= opened.json().keys()
    cards = await client.get(BASE, headers=w["headers"], params=params())
    assert cards.status_code == 200
    assert cards.json()["current_open_attendance"]["id"] == opened.json()["id"]
    assert_facts_only(cards.json())
    owner = await client.get(
        "/api/v1/staff/time-summary",
        headers=w["owner_headers"],
        params={"month": "2026-11", "user_id": str(w["a"].id)},
    )
    assert owner.status_code == 200
    assert isinstance(owner.json()["closed_gross_seconds"], int)


async def test_full_size_multiple_person_pages_use_constant_queries_and_untruncated_day_counts(
    client, session, card_world
):
    w = card_world
    fourth = User(
        id=uuid4(),
        company_id=w["company"].id,
        email=f"fourth-{uuid4()}@example.test",
        name="Synthetic Fourth",
        password_hash=w["a"].password_hash,
        status="inactive",
    )
    session.add(fourth)
    await session.flush()
    people = [w["a"], w["b"], w["departed"], fourth]
    entered = datetime(2026, 11, 1, 4, tzinfo=UTC)
    session.add_all(
        [
            fact(w, person, entered + timedelta(days=day), entered + timedelta(days=day, hours=1))
            for person in people
            for day in range(30)
        ]
    )
    await session.commit()
    statements = []

    def capture(_, __, statement, ___, ____, _____):
        statements.append(statement)

    event.listen(session.bind.sync_engine, "before_cursor_execute", capture)
    try:
        first = await client.get(BASE, headers=w["headers"], params=params(limit=100))
        assert first.status_code == 200, first.text
        body = first.json()
        assert sum(len(c["entries"]) for c in body["items"]) == 100
        assert all(c["clock_in_day_count"] == 30 for c in body["items"])
        assert body["items"][-1]["entries_continued"]
        assert_facts_only(body)
        following = await client.get(
            BASE, headers=w["headers"], params=params(limit=100, after=body["next_cursor"])
        )
        assert following.status_code == 200
        page = following.json()
        assert sum(len(c["entries"]) for c in page["items"]) == 20
        assert page["items"][0]["continued_from_previous"]
        assert all(c["clock_in_day_count"] == 30 for c in page["items"])
        assert page["next_cursor"] is None
        ids = [r["id"] for c in body["items"] + page["items"] for r in c["entries"]]
        assert len(ids) == len(set(ids)) == 120
    finally:
        event.remove(session.bind.sync_engine, "before_cursor_execute", capture)
    assert len([s for s in statements if "from attendance" in s.lower()]) == 7


async def test_month_boundary_uses_company_local_arrival_date_not_utc_date(
    client, session, card_world
):
    w = card_world
    utc_before = datetime(2026, 10, 31, 18, 29, tzinfo=UTC)
    local_first = utc_before + timedelta(minutes=1)
    local_last = datetime(2026, 11, 30, 18, 29, tzinfo=UTC)
    local_next = local_last + timedelta(minutes=1)
    rows = [
        fact(w, w["b"], at, at + timedelta(seconds=30))
        for at in (utc_before, local_first, local_last, local_next)
    ]
    session.add_all(rows)
    await session.commit()
    result = await client.get(BASE, headers=w["headers"], params=params())
    assert result.status_code == 200
    card = result.json()["items"][0]
    assert card["clock_in_day_count"] == 2
    assert {r["id"] for r in card["entries"]} == {str(rows[1].id), str(rows[2].id)}
    assert {r["local_date"] for r in card["entries"]} == {"2026-11-01", "2026-11-30"}


async def test_invalid_company_timezone_has_no_utc_fallback(client, session, card_world):
    w = card_world
    w["company"].timezone = "Not/A_Real_Zone"
    await session.commit()
    result = await client.get(BASE, headers=w["headers"], params=params())
    assert result.status_code == 422
    assert "timezone" in result.json()["error"]["message"]
    assert "items" not in result.json()
