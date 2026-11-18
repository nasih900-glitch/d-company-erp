"""Opt-in, generation-bound staff facts; never payroll or financial mirror rows."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING

from sqlalchemy import select

from app.models import Company, StaffWorkPolicy, User
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.integrations.google_sheets_mirror import (
    STAFF_HOURS_CAPABILITY,
    STAFF_HOURS_CONNECTION_EVENT_TYPE,
    STAFF_HOURS_CONNECTION_SOURCE_TYPE,
    STAFF_HOURS_EVENT_TYPE,
    enqueue_google_sheets_event,
)

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.models import Attendance


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("staff fact timestamps must include a timezone")
    return value.astimezone(UTC)


def _iso(value: datetime) -> str:
    return _utc(value).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _revision(value: datetime) -> str:
    micros = (_utc(value) - datetime(1970, 1, 1, tzinfo=UTC)) // timedelta(microseconds=1)
    if not 0 < micros < 10**20:
        raise ValueError("invalid staff fact source revision")
    return str(micros).zfill(20)


def attendance_payload(
    row: Attendance,
    *,
    name: str,
    timezone: str,
    currency: str,
    actual_break_confirmed: bool,
) -> dict[str, object]:
    """Export known facts only. A legacy default zero is not a confirmed break."""
    if type(actual_break_confirmed) is not bool:
        raise ValueError("actual staff break confirmation must be explicit boolean")
    entered = _utc(row.clock_in_at)
    exited = _utc(row.clock_out_at) if row.clock_out_at is not None else None
    gross = None if exited is None else (exited - entered) // timedelta(seconds=1)
    if gross is not None and gross < 0:
        raise ValueError("staff clock-out precedes clock-in")
    actual_break = row.break_minutes if exited is not None and actual_break_confirmed else None
    if actual_break is not None and (
        gross is None
        or type(actual_break) is not int
        or not 0 <= actual_break <= 1440
        or actual_break * 60 > gross
    ):
        raise ValueError("invalid explicit staff break")
    return {
        "payload_schema": STAFF_HOURS_CAPABILITY,
        "record_kind": "attendance",
        "record_id": str(row.id),
        "staff_id": str(row.user_id),
        "staff_name": name,
        "branch_id": str(row.branch_id),
        "business_timezone": timezone,
        "currency": currency,
        "clock_in_at": _iso(entered),
        "clock_out_at": _iso(exited) if exited else None,
        "actual_break_minutes": actual_break,
        "gross_seconds": gross,
        "work_seconds": gross - actual_break * 60
        if actual_break is not None and gross is not None
        else None,
        "monthly_salary_minor": None,
        "daily_presence_minutes": None,
        "planned_break_minutes": None,
        "daily_work_minutes": None,
        "working_weekday_mask": None,
        "effective_from": None,
        "pay_status": "draft",
        "overtime_pay_minor": None,
        "payable_minor": None,
        "review_required": exited is None or actual_break is None,
    }


def policy_payload(
    row: StaffWorkPolicy, *, name: str, timezone: str, currency: str
) -> dict[str, object]:
    return {
        "payload_schema": STAFF_HOURS_CAPABILITY,
        "record_kind": "policy",
        "record_id": str(row.id),
        "staff_id": str(row.user_id),
        "staff_name": name,
        "branch_id": None,
        "business_timezone": timezone,
        "currency": currency,
        "clock_in_at": None,
        "clock_out_at": None,
        "actual_break_minutes": None,
        "gross_seconds": None,
        "work_seconds": None,
        "monthly_salary_minor": row.monthly_salary_minor,
        "daily_presence_minutes": row.daily_presence_minutes,
        "planned_break_minutes": row.daily_break_minutes,
        "daily_work_minutes": row.daily_presence_minutes - row.daily_break_minutes,
        "working_weekday_mask": row.working_weekday_mask,
        "effective_from": row.effective_from.isoformat(),
        "pay_status": "draft",
        "overtime_pay_minor": None,
        "payable_minor": None,
        "review_required": True,
    }


async def _opted_in_company(session: AsyncSession, company_id: UUID) -> Company | None:
    company = (
        await session.execute(
            select(Company)
            .where(
                Company.id == company_id,
                Company.deleted_at.is_(None),
                Company.google_sheets_mirror_enabled.is_(True),
                Company.google_sheets_configuration_id.is_not(None),
                Company.google_sheets_configured_at.is_not(None),
                Company.google_sheets_webhook_url.is_not(None),
                Company.google_sheets_signing_secret_ciphertext.is_not(None),
            )
            .with_for_update(read=True)
        )
    ).scalar_one_or_none()
    if (
        company is None
        or company.google_sheets_configuration_id is None
        or company.google_sheets_configured_at is None
    ):
        return None
    # A queued Owner capability test is explicit opt-in. Facts are durable while
    # it is pending, but the independent dispatcher gate prevents sending them.
    opted_in = await session.scalar(
        select(GoogleSheetsDelivery.id)
        .where(
            GoogleSheetsDelivery.company_id == company.id,
            GoogleSheetsDelivery.configuration_id == company.google_sheets_configuration_id,
            GoogleSheetsDelivery.event_type == STAFF_HOURS_CONNECTION_EVENT_TYPE,
            GoogleSheetsDelivery.source_type == STAFF_HOURS_CONNECTION_SOURCE_TYPE,
            GoogleSheetsDelivery.source_revision == str(company.google_sheets_configuration_id),
            GoogleSheetsDelivery.payload["required_capability"].as_string()
            == STAFF_HOURS_CAPABILITY,
            GoogleSheetsDelivery.payload["scope"]["company_id"].as_string() == str(company.id),
            GoogleSheetsDelivery.occurred_at >= company.google_sheets_configured_at,
        )
        .limit(1)
    )
    return company if opted_in is not None else None


async def _configured_person(
    session: AsyncSession,
    company_id: UUID,
    user_id: UUID,
    *,
    policy_id: UUID | None = None,
) -> bool:
    """An immutable Owner-written draft policy enrolls a person, not approves pay."""
    query = select(StaffWorkPolicy.id).where(
        StaffWorkPolicy.company_id == company_id,
        StaffWorkPolicy.user_id == user_id,
    )
    if policy_id is not None:
        query = query.where(StaffWorkPolicy.id == policy_id)
    return await session.scalar(query.limit(1)) is not None


async def _name(session: AsyncSession, company_id: UUID, user_id: UUID) -> str:
    name = await session.scalar(
        select(User.name).where(User.id == user_id, User.company_id == company_id)
    )
    if name is None:
        raise ValueError("staff fact user is outside the company")
    return name


async def enqueue_staff_attendance_if_enabled(
    session: AsyncSession,
    *,
    attendance: Attendance,
    actual_break_confirmed: bool = False,
) -> GoogleSheetsDelivery | None:
    """Call after flush/refresh, inside the same attendance transaction; no commit."""
    company = await _opted_in_company(session, attendance.company_id)
    if company is None or company.google_sheets_configuration_id is None:
        return None
    if not await _configured_person(session, company.id, attendance.user_id):
        return None
    return await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=company.google_sheets_configuration_id,
        event_type=STAFF_HOURS_EVENT_TYPE,
        source_type="staff_hours.attendance",
        source_id=str(attendance.id),
        source_revision=_revision(attendance.updated_at),
        occurred_at=attendance.updated_at,
        payload=attendance_payload(
            attendance,
            name=await _name(session, company.id, attendance.user_id),
            timezone=company.timezone,
            currency=company.currency,
            actual_break_confirmed=actual_break_confirmed,
        ),
    )


async def enqueue_staff_policy_if_enabled(
    session: AsyncSession,
    *,
    policy: StaffWorkPolicy,
) -> GoogleSheetsDelivery | None:
    """Policy terms remain immutable draft metadata, not a cost or payment."""
    company = await _opted_in_company(session, policy.company_id)
    if company is None or company.google_sheets_configuration_id is None:
        return None
    if not await _configured_person(session, company.id, policy.user_id, policy_id=policy.id):
        return None
    return await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=company.google_sheets_configuration_id,
        event_type=STAFF_HOURS_EVENT_TYPE,
        source_type="staff_hours.policy",
        source_id=str(policy.id),
        source_revision=str(policy.revision).zfill(20),
        occurred_at=policy.created_at,
        payload=policy_payload(
            policy,
            name=await _name(session, company.id, policy.user_id),
            timezone=company.timezone,
            currency=company.currency,
        ),
    )
