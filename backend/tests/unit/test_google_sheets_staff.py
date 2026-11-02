import json
from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest
from sqlalchemy.dialects import postgresql

from app.services.integrations import google_sheets_mirror as mirror
from app.services.integrations import google_sheets_staff as staff
from tests.unit.test_google_sheets_mirror import _delivery


def attendance(**changes):
    values = {
        "id": uuid4(),
        "company_id": uuid4(),
        "user_id": uuid4(),
        "branch_id": uuid4(),
        "clock_in_at": datetime(2026, 11, 2, 8, tzinfo=UTC),
        "clock_out_at": datetime(2026, 11, 2, 20, tzinfo=UTC),
        "break_minutes": 60,
        "updated_at": datetime(2026, 11, 2, 20, tzinfo=UTC),
    }
    return SimpleNamespace(**(values | changes))


def test_explicit_actual_break_and_legacy_unknown_never_become_pay():
    row = attendance()
    explicit = staff.attendance_payload(
        row, name="QA", timezone="Asia/Kolkata", currency="INR", actual_break_confirmed=True
    )
    unknown = staff.attendance_payload(
        row, name="QA", timezone="Asia/Kolkata", currency="INR", actual_break_confirmed=False
    )
    assert explicit["gross_seconds"] == unknown["gross_seconds"] == 43200
    assert explicit["work_seconds"] == 39600
    assert explicit["actual_break_minutes"] == 60
    assert unknown["work_seconds"] is None
    assert unknown["actual_break_minutes"] is None
    assert explicit["review_required"] is False
    assert unknown["review_required"] is True
    for payload in (explicit, unknown):
        assert payload["monthly_salary_minor"] is None
        assert payload["pay_status"] == "draft"
        assert payload["payable_minor"] is payload["overtime_pay_minor"] is None


def test_open_interval_remains_provisional_even_when_break_confirmation_is_passed():
    payload = staff.attendance_payload(
        attendance(clock_out_at=None),
        name="QA",
        timezone="Asia/Kolkata",
        currency="INR",
        actual_break_confirmed=True,
    )
    assert (
        payload["gross_seconds"]
        is payload["work_seconds"]
        is payload["actual_break_minutes"]
        is None
    )
    assert payload["review_required"] is True


def test_policy_is_720_presence_60_planned_break_660_work_and_not_payment():
    row = SimpleNamespace(
        id=uuid4(),
        user_id=uuid4(),
        monthly_salary_minor=1200000,
        daily_presence_minutes=720,
        daily_break_minutes=60,
        working_weekday_mask=127,
        effective_from=date(2026, 11, 1),
    )
    payload = staff.policy_payload(row, name="QA", timezone="Asia/Kolkata", currency="INR")
    assert payload["daily_work_minutes"] == 660
    assert payload["monthly_salary_minor"] == 1200000
    assert payload["actual_break_minutes"] is payload["work_seconds"] is None
    assert payload["payable_minor"] is payload["overtime_pay_minor"] is None
    assert payload["pay_status"] == "draft"
    assert payload["review_required"] is True


@pytest.mark.parametrize(
    "changes",
    [
        {"break_minutes": -1},
        {"break_minutes": 721},
        {"clock_out_at": datetime(2026, 11, 1, tzinfo=UTC)},
        {"clock_in_at": datetime(2026, 11, 2)},
    ],
)
def test_invalid_facts_are_not_silently_normalized(changes):
    with pytest.raises(ValueError, match="staff|break|clock-out"):
        staff.attendance_payload(
            attendance(**changes),
            name="QA",
            timezone="Asia/Kolkata",
            currency="INR",
            actual_break_confirmed=True,
        )


@pytest.mark.parametrize("kind", ["test", "fact"])
@pytest.mark.parametrize(
    "ack_drift", [None, "old_generic", "wrong_capability", "wrong_scope", "wrong_hash", "extra_key"]
)
async def test_staff_ack_requires_current_capability_scope_and_event_hash(kind, ack_drift):
    delivery = _delivery(
        event_type=mirror.STAFF_HOURS_CONNECTION_EVENT_TYPE
        if kind == "test"
        else mirror.STAFF_HOURS_EVENT_TYPE,
        source_type=mirror.STAFF_HOURS_CONNECTION_SOURCE_TYPE
        if kind == "test"
        else "staff_hours.attendance",
    )
    scope = {"company_id": str(delivery.company_id)}
    payload = (
        {"required_capability": mirror.STAFF_HOURS_CAPABILITY, "scope": scope}
        if kind == "test"
        else {"payload_schema": mirror.STAFF_HOURS_CAPABILITY, "record_kind": "attendance"}
    )
    delivery = _delivery(
        event_type=delivery.event_type, source_type=delivery.source_type, payload=payload
    )
    response = {
        "ok": True,
        "status": "accepted",
        "event_id": str(delivery.event_id),
        "payload_sha256": delivery.payload_sha256,
        "capability": mirror.STAFF_HOURS_CAPABILITY,
        "schema": mirror.MIRROR_SCHEMA,
        "scope": scope,
    }
    if ack_drift == "old_generic":
        response.pop("capability")
        response.pop("scope")
    elif ack_drift == "wrong_capability":
        response["capability"] = "daily_collections.v1"
    elif ack_drift == "wrong_scope":
        response["scope"] = {"company_id": str(uuid4())}
    elif ack_drift == "extra_key":
        response["unrecognized"] = True
    elif ack_drift == "wrong_hash":
        response["payload_sha256"] = "0" * 64
    outcome = mirror._http_outcome(
        httpx.Response(200), delivery, response_body=json.dumps(response).encode()
    )
    assert outcome.delivered is (ack_drift is None)
    if ack_drift:
        assert not outcome.retryable
        assert outcome.error_code == "acknowledgement_mismatch"


async def test_daily_mode_facts_are_durable_only_after_owner_opt_in_and_use_caller_transaction(
    monkeypatch,
):
    row = attendance()
    company = SimpleNamespace(
        id=row.company_id,
        google_sheets_configuration_id=uuid4(),
        google_sheets_configured_at=datetime(2026, 11, 1, tzinfo=UTC),
        google_sheets_export_mode="daily_collections",
        timezone="Asia/Kolkata",
        currency="INR",
    )
    captured = []

    class Session:
        async def execute(self, stmt):
            captured.append(stmt)
            return SimpleNamespace(scalar_one_or_none=lambda: company)

        async def scalar(self, stmt):
            captured.append(stmt)
            return "Staff QA" if "users.name" in str(stmt) else uuid4()

    session = Session()
    enqueued = {}

    async def enqueue(caller, **kwargs):
        enqueued.update(kwargs)
        enqueued["caller"] = caller
        return "queued"

    monkeypatch.setattr(staff, "enqueue_google_sheets_event", enqueue)
    assert (
        await staff.enqueue_staff_attendance_if_enabled(
            session, attendance=row, actual_break_confirmed=True
        )
        == "queued"
    )
    assert enqueued["caller"] is session
    assert enqueued["configuration_id"] == company.google_sheets_configuration_id
    assert enqueued["source_type"] == "staff_hours.attendance"
    assert enqueued["payload"]["work_seconds"] == 39600
    assert "FOR SHARE" in str(captured[0].compile(dialect=postgresql.dialect()))
    assert mirror.STAFF_HOURS_CONNECTION_EVENT_TYPE in str(
        captured[1].compile(dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True})
    )
    assert not hasattr(session, "commit")


async def test_financial_mirror_alone_does_not_create_hidden_staff_queues(monkeypatch):
    company = SimpleNamespace(
        id=uuid4(),
        google_sheets_configuration_id=uuid4(),
        google_sheets_configured_at=datetime.now(UTC),
    )

    class Session:
        async def execute(self, _):
            return SimpleNamespace(scalar_one_or_none=lambda: company)

        async def scalar(self, _):
            return None

    async def forbidden(*args, **kwargs):
        raise AssertionError("No Owner staff opt-in")

    monkeypatch.setattr(staff, "enqueue_google_sheets_event", forbidden)
    assert (
        await staff.enqueue_staff_attendance_if_enabled(Session(), attendance=attendance()) is None
    )


def test_revision_keeps_exact_microseconds_without_float_rounding():
    a = datetime(2026, 11, 2, tzinfo=UTC)
    assert int(staff._revision(a + timedelta(microseconds=1))) - int(staff._revision(a)) == 1


def test_staff_and_financial_verification_are_separate_in_claim_predicate():
    sql = str(
        mirror._delivery_dispatchable_clause().compile(
            dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}
        )
    )
    assert "staff_hours.connection_test" in sql
    assert "mirror.connection_test" in sql
    assert "required_capability" in sql
    assert "scope" in sql
    assert "staff_hours.v1" in sql
    assert "source_revision = CAST(" in sql
    assert "delivered_at >=" in sql


@pytest.mark.parametrize("subject", ["unconfigured-owner", "future-staff"])
async def test_global_capability_does_not_enroll_unconfigured_people(monkeypatch, subject):
    row = attendance()
    company = SimpleNamespace(
        id=row.company_id,
        google_sheets_configuration_id=uuid4(),
        google_sheets_configured_at=datetime.now(UTC),
    )
    captured = []

    class Session:
        async def execute(self, _):
            return SimpleNamespace(scalar_one_or_none=lambda: company)

        async def scalar(self, query):
            if "staff_work_policies" in str(query):
                captured.append(query)
                return None
            if "users.name" in str(query):
                raise AssertionError("Unconfigured person's name must not be projected")
            return uuid4()  # Company staff capability was explicitly requested.

    async def no_export(*args, **kwargs):
        raise AssertionError(subject + " must not be exported")

    monkeypatch.setattr(staff, "enqueue_google_sheets_event", no_export)
    assert await staff.enqueue_staff_attendance_if_enabled(Session(), attendance=row) is None
    assert len(captured) == 1
    parameters = captured[0].compile().params
    assert row.company_id in parameters.values()
    assert row.user_id in parameters.values()
