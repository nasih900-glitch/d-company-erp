"""Real PostgreSQL proof that staff/daily capabilities cannot unlock each other."""

from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy import select

from app.api.v1.settings.google_sheets import (
    GoogleSheetsMirrorConfigure,
    _any_configuration_is_verified,
    _configuration_is_verified,
    _mirror_status,
    configure_google_sheets_mirror,
    queue_staff_hours_connection_check,
    staff_hours_export_status,
)
from app.core.errors import ConflictError, ForbiddenError
from app.models import Attendance, Company, StaffWorkPolicy, User
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.integrations.google_sheets_mirror import (
    CONNECTION_TEST_EVENT_TYPE,
    CONNECTION_TEST_SOURCE_TYPE,
    STAFF_HOURS_CONNECTION_EVENT_TYPE,
    STAFF_HOURS_CONNECTION_SOURCE_TYPE,
    _delivery_dispatchable_clause,
    enqueue_google_sheets_event,
)
from app.services.integrations.google_sheets_staff import (
    enqueue_staff_attendance_if_enabled,
    enqueue_staff_policy_if_enabled,
)


@pytest.mark.integration
@pytest.mark.parametrize("first_capability", ["financial", "staff"])
async def test_daily_mode_independent_staff_verification_and_atomic_fact_hooks(
    session, seed_owner, first_capability
):
    company, branch, user = seed_owner["company"], seed_owner["branch"], seed_owner["owner"]
    configured_at = datetime.now(UTC) - timedelta(days=1)
    generation = uuid4()
    company.google_sheets_mirror_enabled = True
    company.google_sheets_configuration_id = generation
    company.google_sheets_configured_at = configured_at
    company.google_sheets_webhook_url = "https://script.google.com/macros/s/synthetic-test/exec"
    company.google_sheets_signing_secret_ciphertext = "synthetic-unread-ciphertext"
    company.google_sheets_export_mode = "daily_collections"
    company.google_sheets_summary_branch_id = branch.id
    company.google_sheets_summary_start_date = date(2026, 11, 1)
    company.google_sheets_summary_timezone = "Asia/Kolkata"
    await session.flush()
    a = Attendance(
        id=uuid4(),
        company_id=company.id,
        user_id=user.id,
        branch_id=branch.id,
        clock_in_at=datetime(2026, 11, 2, 8, tzinfo=UTC),
        clock_out_at=datetime(2026, 11, 2, 20, tzinfo=UTC),
        break_minutes=60,
    )
    policy = StaffWorkPolicy(
        id=uuid4(),
        company_id=company.id,
        user_id=user.id,
        actor_user_id=user.id,
        revision=1,
        effective_from=date(2026, 11, 1),
        monthly_salary_minor=1200000,
        daily_presence_minutes=720,
        daily_break_minutes=60,
        working_weekday_mask=127,
        reason="Explicit draft terms, no actual payment",
    )
    session.add_all([a, policy])
    await session.flush()
    await session.refresh(a)
    await session.refresh(policy)
    assert (
        await enqueue_staff_attendance_if_enabled(
            session, attendance=a, actual_break_confirmed=True
        )
        is None
    )
    owner = SimpleNamespace(company_id=company.id, roles=["owner"])
    await queue_staff_hours_connection_check(session, owner)
    staff_fact = await enqueue_staff_attendance_if_enabled(
        session, attendance=a, actual_break_confirmed=True
    )
    policy_fact = await enqueue_staff_policy_if_enabled(session, policy=policy)
    assert staff_fact is not None
    assert policy_fact is not None
    assert staff_fact.payload["work_seconds"] == 39600
    assert policy_fact.payload["daily_work_minutes"] == 660
    assert staff_fact.payload["payable_minor"] is policy_fact.payload["payable_minor"] is None
    financial = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=generation,
        event_type="collections.daily_snapshot",
        source_type="daily_collections",
        source_id="synthetic-day",
        source_revision="1",
        occurred_at=datetime.now(UTC),
        payload={"currency": "INR"},
    )
    financial_test = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=generation,
        event_type=CONNECTION_TEST_EVENT_TYPE,
        source_type=CONNECTION_TEST_SOURCE_TYPE,
        source_id=str(uuid4()),
        source_revision=str(generation),
        occurred_at=datetime.now(UTC),
        payload={"export_mode": "daily_collections"},
    )
    staff_test = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.event_type == STAFF_HOURS_CONNECTION_EVENT_TYPE,
            )
        )
    ).scalar_one()

    async def dispatchable():
        return set(
            (
                await session.execute(
                    select(GoogleSheetsDelivery.id).where(
                        GoogleSheetsDelivery.company_id == company.id,
                        _delivery_dispatchable_clause(),
                    )
                )
            ).scalars()
        )

    assert staff_fact.id not in await dispatchable()
    assert financial.id not in await dispatchable()
    first = financial_test if first_capability == "financial" else staff_test
    first.status, first.delivered_at = "delivered", datetime.now(UTC)
    await session.flush()
    eligible = await dispatchable()
    assert (financial.id in eligible) is (first_capability == "financial")
    assert (staff_fact.id in eligible) is (first_capability == "staff")
    assert (policy_fact.id in eligible) is (first_capability == "staff")
    assert await _configuration_is_verified(session, company) is (first_capability == "financial")
    assert (await staff_hours_export_status(session, owner))["verified"] is (
        first_capability == "staff"
    )
    assert (await _mirror_status(session, company)).held_count == (
        2 if first_capability == "financial" else 1
    )
    second = staff_test if first_capability == "financial" else financial_test
    second.status, second.delivered_at = "delivered", datetime.now(UTC)
    await session.flush()
    eligible = await dispatchable()
    assert {financial.id, staff_fact.id, policy_fact.id} <= eligible
    assert (await _mirror_status(session, company)).held_count == 0
    assert company.google_sheets_configuration_id == generation
    assert company.google_sheets_configured_at == configured_at
    assert company.google_sheets_export_mode == "daily_collections"
    # Roll back all the above business facts and outbox rows together.
    ids = [a.id, policy.id, staff_fact.id, policy_fact.id]
    await session.rollback()
    assert await session.get(Attendance, ids[0]) is None
    assert await session.get(StaffWorkPolicy, ids[1]) is None
    assert await session.get(GoogleSheetsDelivery, ids[2]) is None
    assert await session.get(GoogleSheetsDelivery, ids[3]) is None


@pytest.mark.integration
async def test_staff_capability_endpoint_rejects_non_owner_before_database_access():
    with pytest.raises(ForbiddenError):
        await queue_staff_hours_connection_check(None, SimpleNamespace(roles=["staff"]))


@pytest.mark.integration
@pytest.mark.parametrize(
    "drift", ["generation", "occurred_at", "delivered_at", "scope", "capability", "revision"]
)
async def test_mismatched_or_stale_staff_verification_never_releases_current_facts(
    session, seed_owner, drift
):
    company = seed_owner["company"]
    generation = uuid4()
    configured_at = datetime.now(UTC) - timedelta(hours=1)
    company.google_sheets_mirror_enabled = True
    company.google_sheets_configuration_id = generation
    company.google_sheets_configured_at = configured_at
    company.google_sheets_webhook_url = "https://script.google.com/macros/s/synthetic-test/exec"
    company.google_sheets_signing_secret_ciphertext = "synthetic-unread-ciphertext"
    await session.flush()
    receipt_generation = uuid4() if drift == "generation" else generation
    test = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=receipt_generation,
        event_type=STAFF_HOURS_CONNECTION_EVENT_TYPE,
        source_type=STAFF_HOURS_CONNECTION_SOURCE_TYPE,
        source_id=str(uuid4()),
        source_revision=str(uuid4() if drift == "revision" else receipt_generation),
        occurred_at=configured_at - timedelta(seconds=1)
        if drift == "occurred_at"
        else datetime.now(UTC),
        payload={
            "required_capability": "staff_hours.v2" if drift == "capability" else "staff_hours.v1",
            "scope": {"company_id": str(uuid4() if drift == "scope" else company.id)},
            "status": "test",
        },
    )
    test.status = "delivered"
    test.delivered_at = (
        configured_at - timedelta(seconds=1) if drift == "delivered_at" else datetime.now(UTC)
    )
    session.add(_draft(company, seed_owner["owner"], seed_owner["owner"]))
    await session.flush()
    fact = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=generation,
        event_type="staff_hours.fact",
        source_type="staff_hours.attendance",
        source_id=str(uuid4()),
        source_revision="00000000000000000001",
        occurred_at=datetime.now(UTC),
        payload={"staff_id": str(seed_owner["owner"].id), "pay_status": "draft"},
    )
    await session.flush()
    eligible = set(
        (
            await session.execute(
                select(GoogleSheetsDelivery.id).where(
                    GoogleSheetsDelivery.company_id == company.id, _delivery_dispatchable_clause()
                )
            )
        ).scalars()
    )
    assert fact.id not in eligible
    assert not await _configuration_is_verified(session, company)
    good = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=generation,
        event_type=STAFF_HOURS_CONNECTION_EVENT_TYPE,
        source_type=STAFF_HOURS_CONNECTION_SOURCE_TYPE,
        source_id=str(uuid4()),
        source_revision=str(generation),
        occurred_at=datetime.now(UTC),
        payload={
            "required_capability": "staff_hours.v1",
            "scope": {"company_id": str(company.id)},
            "status": "test",
        },
    )
    good.status, good.delivered_at = "delivered", datetime.now(UTC)
    await session.flush()
    assert fact.id in set(
        (
            await session.execute(
                select(GoogleSheetsDelivery.id).where(
                    GoogleSheetsDelivery.company_id == company.id,
                    _delivery_dispatchable_clause(),
                )
            )
        ).scalars()
    )


async def _staff_capable_company(session, company):
    generation = uuid4()
    configured_at = datetime.now(UTC) - timedelta(hours=1)
    company.google_sheets_mirror_enabled = True
    company.google_sheets_configuration_id = generation
    company.google_sheets_configured_at = configured_at
    company.google_sheets_webhook_url = "https://script.google.com/macros/s/synthetic-original/exec"
    company.google_sheets_signing_secret_ciphertext = "synthetic-unread-ciphertext"
    await session.flush()
    owner = SimpleNamespace(company_id=company.id, roles=["owner"])
    await queue_staff_hours_connection_check(session, owner)
    receipt = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.event_type == STAFF_HOURS_CONNECTION_EVENT_TYPE,
            )
        )
    ).scalar_one()
    receipt.status, receipt.delivered_at = "delivered", datetime.now(UTC)
    await session.flush()
    return generation, configured_at, owner


def _draft(company, user, actor):
    return StaffWorkPolicy(
        id=uuid4(),
        company_id=company.id,
        user_id=user.id,
        actor_user_id=actor.id,
        revision=1,
        effective_from=date(2026, 11, 1),
        monthly_salary_minor=1200000,
        daily_presence_minutes=720,
        daily_break_minutes=60,
        working_weekday_mask=127,
        reason="Owner configured this person; pay remains draft",
    )


def _attendance(company, branch, user):
    return Attendance(
        id=uuid4(),
        company_id=company.id,
        user_id=user.id,
        branch_id=branch.id,
        clock_in_at=datetime(2026, 11, 1, 8, tzinfo=UTC),
        clock_out_at=datetime(2026, 11, 1, 20, tzinfo=UTC),
        break_minutes=60,
    )


@pytest.mark.integration
async def test_only_owner_configured_people_export_while_unconfigured_attendance_still_works(
    session,
    seed_owner,
):
    company, owner = seed_owner["company"], seed_owner["owner"]
    branch = seed_owner["branch"]
    await _staff_capable_company(session, company)
    people = {}
    for label in ("Alpha", "Beta", "Future"):
        user = User(
            id=uuid4(),
            company_id=company.id,
            name=label,
            email=f"{label}-{uuid4().hex}@example.test",
            password_hash="synthetic-unused",
            status="active",
        )
        session.add(user)
        people[label] = user
    await session.flush()
    attendance = {}
    for label, user in {"Owner": owner, **people}.items():
        row = _attendance(company, branch, user)
        session.add(row)
        await session.flush()
        await session.refresh(row)
        attendance[label] = row
        assert (
            await enqueue_staff_attendance_if_enabled(
                session, attendance=row, actual_break_confirmed=True
            )
            is None
        )
        assert await session.get(Attendance, row.id) is row
    # Explicit draft enrollment, not global capability or staff-directory presence.
    for label in ("Alpha", "Beta"):
        policy = _draft(company, people[label], owner)
        session.add(policy)
        await session.flush()
        await session.refresh(policy)
        assert await enqueue_staff_policy_if_enabled(session, policy=policy) is not None
        fact = await enqueue_staff_attendance_if_enabled(
            session, attendance=attendance[label], actual_break_confirmed=True
        )
        assert fact is not None
        assert fact.payload["staff_name"] == label
        assert fact.payload["payable_minor"] is None
    for label in ("Owner", "Future"):
        assert (
            await enqueue_staff_attendance_if_enabled(session, attendance=attendance[label]) is None
        )
    # Configured departed history is still correctable without restoring login access.
    people["Alpha"].deleted_at, people["Alpha"].status = datetime.now(UTC), "suspended"
    attendance["Alpha"].break_minutes = 30
    attendance["Alpha"].updated_at += timedelta(microseconds=1)
    await session.flush()
    retained = await enqueue_staff_attendance_if_enabled(
        session, attendance=attendance["Alpha"], actual_break_confirmed=True
    )
    assert retained is not None
    assert retained.payload["work_seconds"] == 41400
    assert people["Alpha"].status == "suspended"
    exported_ids = set(
        (
            await session.execute(
                select(GoogleSheetsDelivery.payload["staff_id"].as_string()).where(
                    GoogleSheetsDelivery.company_id == company.id,
                    GoogleSheetsDelivery.event_type == "staff_hours.fact",
                )
            )
        ).scalars()
    )
    assert exported_ids == {str(people["Alpha"].id), str(people["Beta"].id)}


@pytest.mark.integration
async def test_foreign_company_policy_does_not_enroll_a_person_or_release_a_rogue_fact(
    session, seed_owner
):
    company = seed_owner["company"]
    generation, _, _ = await _staff_capable_company(session, company)
    foreign = Company(id=uuid4(), name="Other synthetic company")
    user = User(
        id=uuid4(),
        company_id=foreign.id,
        name="Foreign configured",
        email=f"foreign-{uuid4().hex}@example.test",
        password_hash="synthetic-unused",
    )
    session.add_all([foreign, user])
    await session.flush()
    session.add(_draft(foreign, user, user))
    await session.flush()
    # The real DB also prevents cross-company attendance inserts; probe defense-in-depth
    # in the standalone helper with a nonpersisted foreign subject object.
    row = _attendance(company, seed_owner["branch"], user)
    assert await enqueue_staff_attendance_if_enabled(session, attendance=row) is None
    rogue = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=generation,
        event_type="staff_hours.fact",
        source_type="staff_hours.attendance",
        source_id=str(uuid4()),
        source_revision="00000000000000000001",
        occurred_at=datetime.now(UTC),
        payload={"staff_id": str(user.id), "pay_status": "draft"},
    )
    assert rogue.id not in set(
        (
            await session.execute(
                select(GoogleSheetsDelivery.id).where(
                    GoogleSheetsDelivery.company_id == company.id,
                    _delivery_dispatchable_clause(),
                )
            )
        ).scalars()
    )


@pytest.mark.integration
async def test_staff_first_verification_blocks_retarget_until_drained_then_rotates_generation(
    session,
    seed_owner,
    monkeypatch,
):
    company, owner = seed_owner["company"], seed_owner["owner"]
    generation, configured_at, tenant = await _staff_capable_company(session, company)
    assert not await _configuration_is_verified(session, company)
    assert await _any_configuration_is_verified(session, company)
    policy = _draft(company, owner, owner)
    row = _attendance(company, seed_owner["branch"], owner)
    session.add_all([policy, row])
    await session.flush()
    await session.refresh(row)
    fact = await enqueue_staff_attendance_if_enabled(
        session, attendance=row, actual_break_confirmed=True
    )
    assert fact is not None
    old_url = company.google_sheets_webhook_url
    request = GoogleSheetsMirrorConfigure(
        webhook_url="https://script.google.com/macros/s/synthetic-new/exec"
    )
    with pytest.raises(ConflictError, match="undelivered"):
        await configure_google_sheets_mirror(request, session, tenant)
    assert company.google_sheets_configuration_id == generation
    assert company.google_sheets_configured_at == configured_at
    assert company.google_sheets_webhook_url == old_url
    fact.status, fact.delivered_at = "delivered", datetime.now(UTC)
    await session.flush()
    # Encryption itself is covered by existing tests; no real integration material here.
    monkeypatch.setattr(
        "app.api.v1.settings.google_sheets.encrypt_google_sheets_signing_secret",
        lambda **_: "synthetic-new-ciphertext",
    )
    await configure_google_sheets_mirror(request, session, tenant)
    assert company.google_sheets_configuration_id != generation
    assert fact.configuration_id == generation
    assert not await _configuration_is_verified(session, company)
    assert not (await staff_hours_export_status(session, tenant))["verified"]
