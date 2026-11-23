"""Shared clocking facts only; schedule, durations and pay stay on Owner routes."""

from __future__ import annotations

import base64
import json
import re
from datetime import UTC, date, datetime, timedelta
from typing import Annotated, Literal
from uuid import UUID  # noqa: TC003 - Pydantic resolves model annotations at runtime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Query
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StrictStr, ValidationError
from sqlalchemy import Date, and_, cast, func, select, tuple_

from app.api.v1.staff.work_time import StaffTimeTenant  # noqa: TC001 - FastAPI dependency type
from app.core.db import SessionDep  # noqa: TC001 - FastAPI resolves annotations at runtime
from app.core.errors import BusinessRuleError, ForbiddenError, NotFoundError
from app.core.roles import has_owner_role
from app.models import Attendance, Company, User
from app.services.staff_time import month_bounds

router = APIRouter()
Period = Literal["week", "month"]


class AttendanceClockFact(BaseModel):
    id: UUID
    user_id: UUID
    branch_id: UUID
    local_date: date
    clock_in_at: datetime
    clock_out_at: datetime | None
    is_open: bool


class AttendancePersonCard(BaseModel):
    user_id: UUID
    name: str
    clock_in_day_count: int = Field(ge=0, le=31)
    entries: list[AttendanceClockFact]
    continued_from_previous: bool
    entries_continued: bool


class AttendanceCardsRead(BaseModel):
    period: Period
    period_start: date
    period_end_exclusive: date
    timezone: str
    as_of: datetime
    scope_branch_id: UUID | None
    current_open_attendance: AttendanceClockFact | None
    items: list[AttendancePersonCard]
    next_cursor: str | None


class _Cursor(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: Annotated[int, Field(strict=True, ge=1, le=1)] = 1
    company_id: UUID
    branch_id: UUID | None
    period: Period
    start: date
    timezone: StrictStr
    user_id: UUID
    clock_in_at: AwareDatetime
    attendance_id: UUID


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate cursor field")
        result[key] = value
    return result


def _decode_cursor(raw: str) -> _Cursor:
    try:
        if len(raw) > 2048 or re.fullmatch(r"[A-Za-z0-9_-]+", raw) is None:
            raise ValueError("invalid cursor encoding")
        decoded = base64.b64decode(raw + "=" * (-len(raw) % 4), altchars=b"-_", validate=True)
        return _Cursor.model_validate(json.loads(decoded, object_pairs_hook=_unique_object))
    except (ValueError, ValidationError, UnicodeDecodeError):
        raise BusinessRuleError("The attendance cursor is invalid. Refresh this view.") from None


def _encode_cursor(cursor: _Cursor) -> str:
    return base64.urlsafe_b64encode(cursor.model_dump_json().encode()).decode().rstrip("=")


def _bounds(period: Period, start: date, zone: ZoneInfo) -> tuple[datetime, datetime, date]:
    if not 2000 <= start.year <= 2100:
        raise BusinessRuleError("Choose an attendance period from 2000 through 2100.")
    if period == "month":
        if start.day != 1:
            raise BusinessRuleError("A calendar month must start on its first day.")
        first, following = month_bounds(start, zone.key)
        return first, following, following.astimezone(zone).date()
    if start.weekday() != 0:
        raise BusinessRuleError("An attendance week must start on Monday.")
    end = start + timedelta(days=7)
    return (
        datetime.combine(start, datetime.min.time(), zone).astimezone(UTC),
        datetime.combine(end, datetime.min.time(), zone).astimezone(UTC),
        end,
    )


def _fact(row, zone: ZoneInfo) -> AttendanceClockFact:
    return AttendanceClockFact(
        id=row.id,
        user_id=row.user_id,
        branch_id=row.branch_id,
        local_date=row.clock_in_at.astimezone(zone).date(),
        clock_in_at=row.clock_in_at,
        clock_out_at=row.clock_out_at,
        is_open=row.clock_out_at is None,
    )


@router.get("/attendance-cards", response_model=AttendanceCardsRead)
async def attendance_cards(
    session: SessionDep,
    tenant: StaffTimeTenant,
    period: Period,
    start: date,
    after: str | None = Query(default=None, max_length=2048),
    limit: int = Query(default=100, ge=1, le=100),
) -> AttendanceCardsRead:
    if tenant.branch_id is None and not has_owner_role(tenant.roles):
        raise ForbiddenError("A verified branch is required to read shared attendance.")
    company_timezone = (
        await session.execute(select(Company.timezone).where(Company.id == tenant.company_id))
    ).scalar_one_or_none()
    if company_timezone is None:
        raise NotFoundError("company not found")
    try:
        zone = ZoneInfo(company_timezone)
    except (ZoneInfoNotFoundError, ValueError):
        raise BusinessRuleError(
            "The company timezone needs Owner correction in Settings."
        ) from None
    first, following, end_date = _bounds(period, start, zone)
    scope = [Attendance.company_id == tenant.company_id]
    if tenant.branch_id is not None:
        scope.append(Attendance.branch_id == tenant.branch_id)
    selected_period = [*scope, Attendance.clock_in_at >= first, Attendance.clock_in_at < following]
    cursor = _decode_cursor(after) if after is not None else None
    if cursor is not None:
        if (
            cursor.company_id != tenant.company_id
            or cursor.branch_id != tenant.branch_id
            or cursor.period != period
            or cursor.start != start
            or cursor.timezone != zone.key
            or not first <= cursor.clock_in_at < following
        ):
            raise BusinessRuleError("The attendance cursor belongs to a different view. Refresh.")
        # Validate the position in this exact tenant/branch/period, never query a foreign row.
        position = (
            await session.execute(
                select(Attendance.id).where(
                    *selected_period,
                    Attendance.id == cursor.attendance_id,
                    Attendance.user_id == cursor.user_id,
                    Attendance.clock_in_at == cursor.clock_in_at,
                )
            )
        ).scalar_one_or_none()
        if position is None:
            raise BusinessRuleError("Attendance changed since this page. Refresh the view.")
    fields = (
        Attendance.id,
        Attendance.user_id,
        Attendance.branch_id,
        Attendance.clock_in_at,
        Attendance.clock_out_at,
        User.name,
    )
    query = (
        select(*fields)
        .join(User, and_(User.id == Attendance.user_id, User.company_id == tenant.company_id))
        .where(*selected_period)
    )
    if cursor is not None:
        query = query.where(
            tuple_(Attendance.user_id, Attendance.clock_in_at, Attendance.id)
            > (cursor.user_id, cursor.clock_in_at, cursor.attendance_id)
        )
    rows = list(
        (
            await session.execute(
                query.order_by(Attendance.user_id, Attendance.clock_in_at, Attendance.id).limit(
                    limit + 1
                )
            )
        ).all()
    )
    page = rows[:limit]
    people = {row.user_id for row in page}
    # One bounded aggregate for all page people; never count just the returned rows.
    days = {}
    if people:
        days = dict(
            (
                await session.execute(
                    select(
                        Attendance.user_id,
                        func.count(
                            func.distinct(
                                cast(func.timezone(zone.key, Attendance.clock_in_at), Date)
                            )
                        ),
                    )
                    .where(*selected_period, Attendance.user_id.in_(people))
                    .group_by(Attendance.user_id)
                )
            ).all()
        )
    cards: dict[UUID, AttendancePersonCard] = {}
    for row in page:
        if row.user_id not in cards:
            cards[row.user_id] = AttendancePersonCard(
                user_id=row.user_id,
                name=row.name,
                clock_in_day_count=days[row.user_id],
                entries=[],
                continued_from_previous=cursor is not None and cursor.user_id == row.user_id,
                entries_continued=len(rows) > limit and rows[limit].user_id == row.user_id,
            )
        cards[row.user_id].entries.append(_fact(row, zone))
    own_open = (
        await session.execute(
            select(*fields)
            .join(User, and_(User.id == Attendance.user_id, User.company_id == tenant.company_id))
            .where(*scope, Attendance.user_id == tenant.user_id, Attendance.clock_out_at.is_(None))
            .limit(2)
        )
    ).all()
    if len(own_open) > 1:
        raise BusinessRuleError("Your open attendance needs Owner review before continuing.")
    next_cursor = None
    if len(rows) > limit:
        last = page[-1]
        next_cursor = _encode_cursor(
            _Cursor(
                company_id=tenant.company_id,
                branch_id=tenant.branch_id,
                period=period,
                start=start,
                timezone=zone.key,
                user_id=last.user_id,
                clock_in_at=last.clock_in_at,
                attendance_id=last.id,
            )
        )
    return AttendanceCardsRead(
        period=period,
        period_start=start,
        period_end_exclusive=end_date,
        timezone=zone.key,
        as_of=datetime.now(UTC),
        scope_branch_id=tenant.branch_id,
        current_open_attendance=_fact(own_open[0], zone) if own_open else None,
        items=list(cards.values()),
        next_cursor=next_cursor,
    )
