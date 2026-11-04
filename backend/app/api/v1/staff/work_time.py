"""Individual working-time facts and Owner-controlled, explicitly draft pay terms."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, date, datetime
from typing import Annotated, Literal
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import or_, select

from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves endpoint annotations at runtime
from app.core.errors import BusinessRuleError, ConflictError, ForbiddenError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import has_permission, requires, requires_any
from app.core.roles import has_owner_role
from app.core.tenant import TenantContext
from app.models import Attendance, AuditLog, Branch, Company, StaffWorkPolicy, User, UserRole
from app.services.integrations.google_sheets_staff import (
    enqueue_staff_attendance_if_enabled,
    enqueue_staff_policy_if_enabled,
)
from app.services.staff_pay_estimate import presence_pay_estimate
from app.services.staff_time import (
    AttendanceInterval,
    draft_schedule,
    month_bounds,
    next_attendance_version,
    summarize_intervals,
)

router = APIRouter()
MAX_POLICY_REVISION = 2_147_483_647

OwnerTimeTenant = Annotated[TenantContext, Depends(requires("staff.payroll.write"))]
StaffTimeTenant = Annotated[
    TenantContext, Depends(requires_any("staff.attendance.write", "staff.payroll.write"))
]


class WorkPolicyWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expected_revision: int = Field(strict=True, ge=0, le=MAX_POLICY_REVISION - 1)
    effective_from: date
    monthly_salary_minor: int = Field(strict=True, ge=0, le=1000000000000)
    daily_presence_minutes: int = Field(strict=True, ge=1, le=1440)
    daily_break_minutes: int = Field(strict=True, ge=0, le=1439)
    working_weekday_mask: int = Field(strict=True, ge=1, le=127)
    overtime_multiplier_basis_points: int | None = Field(
        default=None, strict=True, ge=10000, le=40000
    )
    reason: str = Field(min_length=1, max_length=500)

    @field_validator("reason")
    @classmethod
    def reason_is_not_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("a reason is required")
        return value


class WorkPolicyRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    user_id: UUID
    revision: int
    effective_from: date
    monthly_salary_minor: int
    daily_presence_minutes: int
    daily_break_minutes: int
    working_weekday_mask: int
    overtime_multiplier_basis_points: int | None
    pay_status: str
    reason: str
    created_at: datetime


class AttendanceRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    user_id: UUID
    branch_id: UUID
    clock_in_at: datetime
    clock_out_at: datetime | None
    break_minutes: int
    break_confirmed: bool = False
    source: str
    updated_at: datetime


class WorkScheduleRead(BaseModel):
    effective_from: date
    daily_presence_minutes: int
    daily_break_minutes: int
    working_weekday_mask: int


class PayEstimateDayRead(BaseModel):
    date: date
    paid_seconds: int
    ordinary_seconds: int
    extra_seconds: int


class PayEstimateRead(BaseModel):
    status: Literal["available", "unavailable"]
    reason: str | None = None
    basis: Literal["paid_presence_clock_in_workday"] = "paid_presence_clock_in_workday"
    attendance_scope: Literal["company", "selected_branch"] = "company"
    monthly_salary_minor: int | None = None
    daily_paid_seconds: int | None = None
    scheduled_days: int | None = None
    scheduled_paid_seconds: int | None = None
    recorded_paid_seconds: int | None = None
    ordinary_seconds: int | None = None
    extra_seconds: int | None = None
    hourly_rate_minor: int | None = None
    base_pay_minor: int | None = None
    overtime_125_minor: int | None = None
    overtime_150_minor: int | None = None
    days: list[PayEstimateDayRead] = Field(default_factory=list)


class TimeSummaryRead(BaseModel):
    user_id: UUID
    name: str
    month: str
    timezone: str
    as_of: datetime
    entries: list[AttendanceRead]
    closed_gross_seconds: int
    closed_work_seconds: int | None
    provisional_open_seconds: int
    review_attendance_ids: list[UUID]
    pay_status: str
    overtime_pay_minor: None = None
    payable_minor: None = None
    policy: WorkPolicyRead | None = None
    schedule: WorkScheduleRead | None = None
    scheduled_days: int | None = None
    scheduled_work_minutes: int | None = None
    reference_hourly_numerator_minor: int | None = None
    reference_hourly_denominator_minutes: int | None = None
    policy_covers_full_month: bool = False
    # Owner-only read projection. Null also keeps older clients compatible.
    pay_estimate: PayEstimateRead | None = None


class SelfClockInWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    branch_id: UUID


class SelfClockOutWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    attendance_id: UUID
    # Required: a planned break must never silently become an actual break.
    break_minutes: int = Field(strict=True, ge=0, le=1440)


class AttendanceCorrectionWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expected_updated_at: AwareDatetime
    clock_in_at: AwareDatetime
    clock_out_at: AwareDatetime | None
    break_minutes: int = Field(strict=True, ge=0, le=1440)
    reason: str = Field(min_length=1, max_length=500)


class OwnerClockInWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    branch_id: UUID
    reason: str = Field(min_length=1, max_length=500)


class OwnerClockOutWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    attendance_id: UUID
    break_minutes: int = Field(strict=True, ge=0, le=1440)
    reason: str = Field(min_length=1, max_length=500)


def _attendance_break_fingerprint(row: Attendance) -> str:
    """Bind actual-break confirmation to this exact closed entry and row version."""
    if row.clock_out_at is None:
        raise ValueError("an open attendance entry cannot confirm its final break")
    facts = {
        "company_id": str(row.company_id),
        "user_id": str(row.user_id),
        "id": str(row.id),
        "clock_in_at": row.clock_in_at.astimezone(UTC).isoformat(),
        "clock_out_at": row.clock_out_at.astimezone(UTC).isoformat(),
        "break_minutes": row.break_minutes,
        "updated_at": row.updated_at.astimezone(UTC).isoformat(),
    }
    return hashlib.sha256(
        json.dumps(facts, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def _owner(tenant: TenantContext) -> None:
    if not has_owner_role(tenant.roles):
        raise ForbiddenError("Only an Owner can manage another person's time or draft pay.")


def _month(value: str) -> date:
    try:
        month = date.fromisoformat(value + "-01")
    except ValueError as exc:
        raise BusinessRuleError("Choose a month in YYYY-MM format.") from exc
    if not 2000 <= month.year <= 2100:
        raise BusinessRuleError("Choose a month from 2000 through 2100.")
    return month


async def _target(
    session,
    tenant: TenantContext,
    user_id: UUID,
    *,
    lock: bool = False,
    active: bool = False,
    allow_history: bool = False,
) -> User:
    stmt = select(User).where(User.id == user_id, User.company_id == tenant.company_id)
    if active:
        stmt = stmt.where(User.deleted_at.is_(None), User.status == "active")
    if lock:
        # Serialize per-person writes without conflicting with the FK KEY SHARE
        # taken by this actor's idempotency/audit rows. FOR UPDATE would create
        # a lock-upgrade deadlock when two devices clock the same person in.
        stmt = stmt.with_for_update(key_share=True)
    user = (await session.execute(stmt)).scalar_one_or_none()
    if user is None:
        raise NotFoundError("staff member not found")
    if tenant.branch_id is not None:
        assignment = (
            await session.execute(
                select(UserRole.id)
                .where(
                    UserRole.user_id == user.id,
                    or_(UserRole.branch_id.is_(None), UserRole.branch_id == tenant.branch_id),
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if assignment is None and allow_history:
            assignment = (
                await session.execute(
                    select(Attendance.id)
                    .where(
                        Attendance.company_id == tenant.company_id,
                        Attendance.user_id == user.id,
                        Attendance.branch_id == tenant.branch_id,
                    )
                    .limit(1)
                )
            ).scalar_one_or_none()
        if assignment is None:
            raise ForbiddenError("This staff member is not assigned to the selected branch.")
    return user


async def _reserve(
    request: Request, session, tenant: TenantContext
) -> tuple[str, JSONResponse | None]:
    key = getattr(request.state, "idempotency_key", None)
    digest = getattr(request.state, "idempotency_request_hash", None)
    if not key or not digest:
        raise BusinessRuleError("Idempotency-Key is required for staff time and draft-pay writes.")
    prior = await check_or_reserve(
        session,
        key=key,
        request_hash=digest,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if (
        prior is not None
        and prior["body"].get("branch_id") is not None
        and not tenant.in_branch(UUID(prior["body"]["branch_id"]))
    ):
        raise ForbiddenError("That attendance receipt belongs to a different branch.")
    return key, JSONResponse(prior["body"], status_code=prior["status_code"]) if prior else None


class TimePersonRead(BaseModel):
    id: UUID
    name: str
    status: str
    departed: bool


class TimePeopleRead(BaseModel):
    items: list[TimePersonRead]
    next_cursor: UUID | None


@router.get("/time-people", response_model=TimePeopleRead)
async def time_people(
    session: SessionDep,
    tenant: OwnerTimeTenant,
    after_id: UUID | None = None,
    limit: int = Query(default=50, ge=1, le=100),
):
    """Minimal Owner attendance selector, including retained departed history."""
    _owner(tenant)
    stmt = select(User).where(User.company_id == tenant.company_id)
    if tenant.branch_id is not None:
        assignment = (
            select(UserRole.id)
            .where(
                UserRole.user_id == User.id,
                or_(UserRole.branch_id.is_(None), UserRole.branch_id == tenant.branch_id),
            )
            .exists()
        )
        retained = (
            select(Attendance.id)
            .where(
                Attendance.company_id == tenant.company_id,
                Attendance.user_id == User.id,
                Attendance.branch_id == tenant.branch_id,
            )
            .exists()
        )
        stmt = stmt.where(or_(assignment, retained))
    if after_id is not None:
        stmt = stmt.where(User.id > after_id)
    rows = list((await session.execute(stmt.order_by(User.id).limit(limit + 1))).scalars())
    return TimePeopleRead(
        items=[
            TimePersonRead(
                id=row.id,
                name=row.name,
                status=row.status,
                departed=row.deleted_at is not None or row.status != "active",
            )
            for row in rows[:limit]
        ],
        next_cursor=rows[limit - 1].id if len(rows) > limit else None,
    )


@router.get("/time-summary", response_model=TimeSummaryRead)
async def time_summary(
    session: SessionDep,
    tenant: StaffTimeTenant,
    month: str = Query(pattern=r"^\d{4}-\d{2}$"),
    user_id: UUID | None = None,
) -> TimeSummaryRead:
    target_id = user_id or tenant.user_id
    is_owner = has_owner_role(tenant.roles)
    can_manage = is_owner and await has_permission(session, tenant, "staff.payroll.write")
    if target_id != tenant.user_id and not can_manage:
        raise ForbiddenError("You can only read your own working-time history.")
    user = await _target(session, tenant, target_id, allow_history=True)
    company = await session.get(Company, tenant.company_id)
    if company is None:
        raise NotFoundError("company not found")
    period = _month(month)
    try:
        start, end = month_bounds(period, company.timezone)
    except ZoneInfoNotFoundError as exc:
        raise BusinessRuleError("The company timezone needs Owner correction in Settings.") from exc
    stmt = select(Attendance).where(
        Attendance.company_id == tenant.company_id,
        Attendance.user_id == target_id,
        Attendance.clock_in_at < end,
        or_(
            Attendance.clock_out_at.is_(None),
            Attendance.clock_out_at > start,
            Attendance.clock_in_at >= start,
        ),
    )
    if tenant.branch_id is not None:
        stmt = stmt.where(Attendance.branch_id == tenant.branch_id)
    entries = list(
        (
            await session.execute(stmt.order_by(Attendance.clock_in_at, Attendance.id).limit(5001))
        ).scalars()
    )
    if len(entries) > 5000:
        raise BusinessRuleError(
            "This month's attendance needs Owner review before it can be summarized."
        )
    # Legacy zero is a DB default, not proof that a break was confirmed.
    # Positive retained break values are existing recorded facts; modern zero
    # requires the explicit clock-out/correction audit created in that transaction.
    unknown_closed = [
        row for row in entries if row.clock_out_at is not None and row.break_minutes == 0
    ]
    closed_ids = [str(row.id) for row in unknown_closed]
    fingerprints = [_attendance_break_fingerprint(row) for row in unknown_closed]
    confirmed_ids = {
        str(row.id) for row in entries if row.clock_out_at is not None and row.break_minutes > 0
    }
    if closed_ids:
        confirmed_ids.update(
            (
                await session.execute(
                    select(AuditLog.entity_id)
                    .distinct()
                    .where(
                        AuditLog.business_details["attendance_fingerprint"].astext.in_(
                            fingerprints
                        ),
                        AuditLog.company_id == tenant.company_id,
                        AuditLog.entity_type == "Attendance",
                        AuditLog.entity_id.in_(closed_ids),
                        AuditLog.action.in_(
                            ("staff.attendance.clock_out", "staff.attendance.correct")
                        ),
                    )
                )
            ).scalars()
        )
    now = datetime.now(UTC)
    intervals = [
        AttendanceInterval(
            row.id,
            row.clock_in_at,
            row.clock_out_at,
            row.break_minutes,
            str(row.id) in confirmed_ids,
        )
        for row in entries
    ]
    facts = summarize_intervals(
        intervals,
        month=period,
        timezone=company.timezone,
        now=now,
    )
    result = TimeSummaryRead(
        user_id=user.id,
        name=user.name,
        month=month,
        timezone=company.timezone,
        as_of=now,
        entries=[
            AttendanceRead.model_validate(row).model_copy(
                update={"break_confirmed": str(row.id) in confirmed_ids}
            )
            for row in entries
        ],
        **facts,
    )
    policy = (
        await session.execute(
            select(StaffWorkPolicy)
            .where(
                StaffWorkPolicy.company_id == tenant.company_id,
                StaffWorkPolicy.user_id == target_id,
                StaffWorkPolicy.effective_from < end.astimezone(ZoneInfo(company.timezone)).date(),
            )
            .order_by(StaffWorkPolicy.effective_from.desc(), StaffWorkPolicy.revision.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    if policy:
        result.schedule = WorkScheduleRead.model_validate(policy, from_attributes=True)
    # Salary data never piggybacks ordinary staff-directory or attendance access.
    if can_manage:
        result.pay_estimate = PayEstimateRead(
            status="unavailable", reason="Record salary terms for this person first."
        )
    if can_manage and policy:
        result.policy = WorkPolicyRead.model_validate(policy)
        result.policy_covers_full_month = policy.effective_from <= period
        result.pay_estimate = PayEstimateRead.model_validate(
            presence_pay_estimate(
                intervals,
                month=period,
                timezone=company.timezone,
                now=now,
                monthly_salary_minor=policy.monthly_salary_minor,
                daily_presence_minutes=policy.daily_presence_minutes,
                working_weekday_mask=policy.working_weekday_mask,
                policy_covers_full_month=result.policy_covers_full_month,
            )
        )
        result.pay_estimate.attendance_scope = (
            "selected_branch" if tenant.branch_id is not None else "company"
        )
        if result.policy_covers_full_month:
            schedule = draft_schedule(
                month=period,
                monthly_salary_minor=policy.monthly_salary_minor,
                daily_presence_minutes=policy.daily_presence_minutes,
                daily_break_minutes=policy.daily_break_minutes,
                working_weekday_mask=policy.working_weekday_mask,
            )
            for key in (
                "scheduled_days",
                "scheduled_work_minutes",
                "reference_hourly_numerator_minor",
                "reference_hourly_denominator_minutes",
            ):
                setattr(result, key, schedule[key])
    return result


@router.get("/work-policies/{user_id}", response_model=list[WorkPolicyRead])
async def draft_policy_history(
    user_id: UUID,
    session: SessionDep,
    tenant: OwnerTimeTenant,
    before_revision: int | None = Query(default=None, ge=1, le=MAX_POLICY_REVISION),
    limit: int = Query(default=50, ge=1, le=100),
):
    _owner(tenant)
    await _target(session, tenant, user_id, allow_history=True)
    stmt = select(StaffWorkPolicy).where(
        StaffWorkPolicy.company_id == tenant.company_id, StaffWorkPolicy.user_id == user_id
    )
    if before_revision is not None:
        stmt = stmt.where(StaffWorkPolicy.revision < before_revision)
    return list(
        (
            await session.execute(stmt.order_by(StaffWorkPolicy.revision.desc()).limit(limit))
        ).scalars()
    )


@router.post("/work-policies/{user_id}", response_model=WorkPolicyRead, status_code=201)
async def save_draft_policy(
    user_id: UUID,
    payload: WorkPolicyWrite,
    request: Request,
    session: SessionDep,
    tenant: OwnerTimeTenant,
):
    _owner(tenant)
    if payload.daily_break_minutes >= payload.daily_presence_minutes:
        raise BusinessRuleError("The planned break must be shorter than the time at the shop.")
    if not 2000 <= payload.effective_from.year <= 2100:
        raise BusinessRuleError("The effective date must be from 2000 through 2100.")
    await _target(session, tenant, user_id, allow_history=True)
    key, prior = await _reserve(request, session, tenant)
    if prior is not None:
        return prior
    await _target(session, tenant, user_id, lock=True, active=True)
    latest = (
        await session.execute(
            select(StaffWorkPolicy.revision)
            .where(
                StaffWorkPolicy.company_id == tenant.company_id,
                StaffWorkPolicy.user_id == user_id,
            )
            .order_by(StaffWorkPolicy.revision.desc())
            .limit(1)
        )
    ).scalar_one_or_none() or 0
    if latest != payload.expected_revision:
        raise ConflictError("Draft pay settings changed. Refresh before saving a new revision.")
    row = StaffWorkPolicy(
        id=uuid4(),
        company_id=tenant.company_id,
        user_id=user_id,
        actor_user_id=tenant.user_id,
        revision=latest + 1,
        **payload.model_dump(exclude={"expected_revision"}),
    )
    session.add(row)
    await session.flush()
    await session.refresh(row)
    await enqueue_staff_policy_if_enabled(session, policy=row)
    result = WorkPolicyRead.model_validate(row)
    await store_response(session, key=key, status_code=201, body=result.model_dump(mode="json"))
    return result


@router.post("/attendance/{user_id}/clock-in", status_code=201)
async def owner_clock_in(
    user_id: UUID,
    payload: OwnerClockInWrite,
    request: Request,
    session: SessionDep,
    tenant: OwnerTimeTenant,
):
    _owner(tenant)
    if not payload.reason.strip():
        raise BusinessRuleError("A reason is required when recording another person's time.")
    return await _clock_in(
        user_id, payload.branch_id, payload.reason.strip(), request, session, tenant
    )


async def _clock_in(user_id, branch_id, reason, request, session, tenant):
    await _target(session, tenant, user_id, allow_history=True)
    key, prior = await _reserve(request, session, tenant)
    if prior is not None:
        return prior
    await _target(session, tenant, user_id, lock=True, active=True)
    branch = await session.get(Branch, branch_id)
    if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    if not tenant.in_branch(branch.id):
        raise ForbiddenError("Use the staff member's assigned branch.")
    assigned = (
        await session.execute(
            select(UserRole.id)
            .where(
                UserRole.user_id == user_id,
                or_(UserRole.branch_id.is_(None), UserRole.branch_id == branch.id),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    if assigned is None:
        raise ForbiddenError("This staff member is not assigned to that branch.")
    opened = (
        await session.execute(
            select(Attendance.id).where(
                Attendance.company_id == tenant.company_id,
                Attendance.user_id == user_id,
                Attendance.clock_out_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if opened is not None:
        raise ConflictError("This staff member is already clocked in. Refresh attendance.")
    row = Attendance(
        id=uuid4(),
        company_id=tenant.company_id,
        user_id=user_id,
        branch_id=branch.id,
        clock_in_at=datetime.now(UTC),
        source="owner" if reason is not None else "app",
        notes=reason,
    )
    session.add(row)
    await session.flush()
    await session.refresh(row)
    await enqueue_staff_attendance_if_enabled(session, attendance=row, actual_break_confirmed=False)
    result = AttendanceRead.model_validate(row).model_dump(mode="json")
    await store_response(session, key=key, status_code=201, body=result)
    return result


@router.post("/attendance/{user_id}/clock-out")
async def owner_clock_out(
    user_id: UUID,
    payload: OwnerClockOutWrite,
    request: Request,
    session: SessionDep,
    tenant: OwnerTimeTenant,
):
    _owner(tenant)
    if not payload.reason.strip():
        raise BusinessRuleError("A reason is required when recording another person's time.")
    return await _clock_out(
        user_id,
        payload.attendance_id,
        payload.break_minutes,
        payload.reason.strip(),
        request,
        session,
        tenant,
    )


async def _clock_out(user_id, attendance_id, break_minutes, reason, request, session, tenant):
    await _target(session, tenant, user_id, allow_history=True)
    key, prior = await _reserve(request, session, tenant)
    if prior is not None:
        return prior
    await _target(session, tenant, user_id, lock=True, allow_history=True)
    row = (
        await session.execute(
            select(Attendance)
            .where(
                Attendance.company_id == tenant.company_id,
                Attendance.user_id == user_id,
                Attendance.id == attendance_id,
                Attendance.clock_out_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if row is None:
        raise ConflictError(
            "That clock-in is no longer open. Refresh attendance before continuing."
        )
    if not tenant.in_branch(row.branch_id):
        raise ForbiddenError("Use the staff member's assigned branch.")
    now = datetime.now(UTC)
    if row.clock_in_at > now:
        raise BusinessRuleError(
            "This clock-in is in the future and needs an Owner time correction."
        )
    if break_minutes * 60 > (now - row.clock_in_at).total_seconds():
        raise BusinessRuleError("The actual break cannot exceed the recorded time at the shop.")
    row.clock_out_at, row.break_minutes = now, break_minutes
    row.updated_at = await next_attendance_version(session, row.updated_at)
    await session.flush()
    await session.refresh(row)
    session.add(
        AuditLog(
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            action="staff.attendance.clock_out",
            entity_type="Attendance",
            entity_id=str(row.id),
            target_user_id=user_id,
            business_details={
                **({"reason": reason} if reason is not None else {}),
                "actual_break_minutes": break_minutes,
                "attendance_fingerprint": _attendance_break_fingerprint(row),
            },
        )
    )
    await enqueue_staff_attendance_if_enabled(session, attendance=row, actual_break_confirmed=True)
    result = (
        AttendanceRead.model_validate(row)
        .model_copy(update={"break_confirmed": row.clock_out_at is not None})
        .model_dump(mode="json")
    )
    await store_response(session, key=key, status_code=200, body=result)
    return result


@router.post("/my-attendance/clock-in", response_model=AttendanceRead, status_code=201)
async def self_clock_in(
    payload: SelfClockInWrite,
    request: Request,
    session: SessionDep,
    tenant: StaffTimeTenant,
):
    return await _clock_in(tenant.user_id, payload.branch_id, None, request, session, tenant)


@router.post("/my-attendance/clock-out", response_model=AttendanceRead)
async def self_clock_out(
    payload: SelfClockOutWrite,
    request: Request,
    session: SessionDep,
    tenant: StaffTimeTenant,
):
    return await _clock_out(
        tenant.user_id,
        payload.attendance_id,
        payload.break_minutes,
        None,
        request,
        session,
        tenant,
    )


@router.patch("/attendance/{user_id}/{attendance_id}", response_model=AttendanceRead)
async def correct_attendance(
    user_id: UUID,
    attendance_id: UUID,
    payload: AttendanceCorrectionWrite,
    request: Request,
    session: SessionDep,
    tenant: OwnerTimeTenant,
):
    _owner(tenant)
    reason = payload.reason.strip()
    if not reason:
        raise BusinessRuleError("A reason is required for an attendance correction.")
    await _target(session, tenant, user_id, allow_history=True)
    key, prior = await _reserve(request, session, tenant)
    if prior is not None:
        return prior
    await _target(session, tenant, user_id, lock=True, allow_history=True)
    row = (
        await session.execute(
            select(Attendance)
            .where(
                Attendance.company_id == tenant.company_id,
                Attendance.user_id == user_id,
                Attendance.id == attendance_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if row is None:
        raise NotFoundError("attendance entry not found")
    if not tenant.in_branch(row.branch_id):
        raise ForbiddenError("That attendance entry belongs to another branch.")
    if row.updated_at != payload.expected_updated_at:
        raise ConflictError("Attendance changed. Refresh before making a correction.")
    entered = payload.clock_in_at.astimezone(UTC)
    exited = payload.clock_out_at.astimezone(UTC) if payload.clock_out_at else None
    now = datetime.now(UTC)
    if entered > now or (exited is not None and (exited > now or exited < entered)):
        raise BusinessRuleError(
            "Use actual arrival and departure times, in order and not in the future."
        )
    if exited is None and payload.break_minutes:
        raise BusinessRuleError("Confirm the actual break when closing an attendance entry.")
    if exited is not None and payload.break_minutes * 60 > (exited - entered).total_seconds():
        raise BusinessRuleError("The actual break cannot exceed the recorded time at the shop.")
    # Locking the same User as every clock-in/out serializes overlap checks and writes.
    overlap = select(Attendance.id).where(
        Attendance.company_id == tenant.company_id,
        Attendance.user_id == user_id,
        Attendance.id != attendance_id,
        or_(Attendance.clock_out_at.is_(None), Attendance.clock_out_at > entered),
    )
    if exited is not None:
        overlap = overlap.where(Attendance.clock_in_at < exited)
    if (await session.execute(overlap.limit(1))).scalar_one_or_none() is not None:
        raise ConflictError(
            "That correction overlaps another attendance entry. Review the history first."
        )
    before = AttendanceRead.model_validate(row).model_dump(mode="json", exclude={"break_confirmed"})
    row.clock_in_at, row.clock_out_at, row.break_minutes = entered, exited, payload.break_minutes
    row.updated_at = await next_attendance_version(session, row.updated_at)
    await session.flush()
    await session.refresh(row)
    session.add(
        AuditLog(
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            action="staff.attendance.correct",
            entity_type="Attendance",
            entity_id=str(row.id),
            target_user_id=user_id,
            business_details={
                "reason": reason,
                "attendance_fingerprint": _attendance_break_fingerprint(row)
                if exited is not None
                else None,
                "before": before,
                "after": {
                    "clock_in_at": entered.isoformat(),
                    "clock_out_at": exited.isoformat() if exited else None,
                    "break_minutes": payload.break_minutes,
                },
            },
        )
    )
    await enqueue_staff_attendance_if_enabled(
        session,
        attendance=row,
        actual_break_confirmed=row.clock_out_at is not None,
    )
    result = (
        AttendanceRead.model_validate(row)
        .model_copy(update={"break_confirmed": row.clock_out_at is not None})
        .model_dump(mode="json")
    )
    await store_response(session, key=key, status_code=200, body=result)
    return result
