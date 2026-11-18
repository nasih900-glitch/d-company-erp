"""Online password-verified self attendance without replacing the cashier session."""

from __future__ import annotations

import json
from typing import Annotated
from uuid import UUID  # noqa: TC003 - Pydantic/FastAPI runtime schema resolution

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import and_, or_, select

from app.api.v1.staff.work_time import AttendanceRead, _clock_in, _clock_out
from app.core.db import SessionDep  # noqa: TC001 - FastAPI runtime dependency resolution
from app.core.errors import AuthError, BusinessRuleError, ForbiddenError
from app.core.idempotency import store_response
from app.core.middleware import idempotency_request_hash
from app.core.permissions import has_permission, requires
from app.core.roles import has_full_access, has_protected_owner_access, public_roles
from app.core.tenant import TenantContext
from app.models import Attendance, AuditLog, Role, User, UserRole
from app.services.audit.recorder import actor_ctx
from app.services.auth.credentials import password_failure, validated_role_assignment
from app.services.auth.rate_limit import enforce_login_rate_limit

router = APIRouter(prefix="/shared-attendance")
AttendanceTenant = Annotated[TenantContext, Depends(requires("staff.attendance.write"))]


class SharedPersonRead(BaseModel):
    id: UUID
    name: str
    on_duty: bool
    attendance_id: UUID | None


class SharedPeopleRead(BaseModel):
    items: list[SharedPersonRead]
    next_cursor: UUID | None


class SharedClockInWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    user_id: UUID
    password: SecretStr = Field(min_length=1, max_length=256)


class SharedClockOutWrite(SharedClockInWrite):
    attendance_id: UUID
    break_minutes: int = Field(strict=True, ge=0, le=1440)


class SharedAttendanceRead(AttendanceRead):
    staff_name: str


def _terminal_scope(tenant: TenantContext) -> None:
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise ForbiddenError(
            "Select this tablet's branch and terminal before recording attendance."
        )


async def _staff_tenant(session, caller: TenantContext, user: User) -> TenantContext:
    roles, assigned_branch = await validated_role_assignment(session, user)
    if assigned_branch is not None and assigned_branch != caller.branch_id:
        raise ForbiddenError("This staff member is not assigned to this tablet's branch.")
    target = TenantContext(
        user_id=user.id,
        company_id=caller.company_id,
        branch_id=caller.branch_id,
        terminal_id=caller.terminal_id,
        roles=tuple(public_roles(roles)),
        protected_access=has_full_access(roles),
        audit_access=has_protected_owner_access(roles),
    )
    if not await has_permission(session, target, "staff.attendance.write"):
        raise ForbiddenError("This staff account cannot record attendance.")
    return target


@router.get("/people", response_model=SharedPeopleRead)
async def people(
    session: SessionDep,
    tenant: AttendanceTenant,
    after_id: UUID | None = None,
    limit: int = Query(default=50, ge=1, le=100),
):
    _terminal_scope(tenant)
    assigned = (
        select(UserRole.id)
        .join(Role, Role.id == UserRole.role_id)
        .where(
            UserRole.user_id == User.id,
            Role.company_id == tenant.company_id,
            or_(UserRole.branch_id.is_(None), UserRole.branch_id == tenant.branch_id),
        )
        .exists()
    )
    stmt = (
        select(User, Attendance.id.label("attendance_id"))
        .outerjoin(
            Attendance,
            and_(
                Attendance.company_id == tenant.company_id,
                Attendance.user_id == User.id,
                Attendance.branch_id == tenant.branch_id,
                Attendance.clock_out_at.is_(None),
            ),
        )
        .where(
            User.company_id == tenant.company_id,
            User.deleted_at.is_(None),
            User.status == "active",
            assigned,
        )
    )
    if after_id is not None:
        stmt = stmt.where(User.id > after_id)
    rows = (await session.execute(stmt.order_by(User.id).limit(limit + 1))).all()
    items = []
    for row in rows[:limit]:
        try:
            await _staff_tenant(session, tenant, row.User)
        except (AuthError, ForbiddenError):
            continue
        items.append(
            SharedPersonRead(
                id=row.User.id,
                name=row.User.name,
                on_duty=row.attendance_id is not None,
                attendance_id=row.attendance_id,
            )
        )
    return SharedPeopleRead(
        items=items, next_cursor=rows[limit - 1].User.id if len(rows) > limit else None
    )


def _action_hash(request: Request, payload: SharedClockInWrite, caller: TenantContext) -> str:
    # Re-entering/changing a password must not persist a credential fingerprint.
    facts = {
        **payload.model_dump(mode="json", exclude={"password"}),
        "initiated_by_user_id": str(caller.user_id),
        "company_id": str(caller.company_id),
        "branch_id": str(caller.branch_id),
        "terminal_id": str(caller.terminal_id),
    }
    return idempotency_request_hash(
        method=request.method,
        path=request.url.path,
        query=request.url.query,
        key=request.state.idempotency_key,
        body=json.dumps(facts, sort_keys=True, separators=(",", ":")).encode(),
    )


async def _authenticate(session, request, caller, payload):
    _terminal_scope(caller)
    if request.headers.get("X-Offline-Captured", "").strip().lower() in {"1", "true", "yes"}:
        raise BusinessRuleError(
            "Shared-tablet attendance requires a fresh online credential check."
        )
    if not getattr(request.state, "idempotency_key", None):
        raise BusinessRuleError("Idempotency-Key is required for attendance.")
    query = select(User).where(User.id == payload.user_id, User.company_id == caller.company_id)

    async def eligible(user):
        if user is None or user.deleted_at is not None or user.status != "active":
            raise AuthError("invalid credentials")
        return await _staff_tenant(session, caller, user)

    try:
        user = (await session.execute(query)).scalar_one_or_none()
        await eligible(user)
        # Serialize with ordinary login and attendance, then verify the live assignment again.
        user = (
            await session.execute(
                query.with_for_update(key_share=True).execution_options(populate_existing=True)
            )
        ).scalar_one_or_none()
        target = await eligible(user)
    except (AuthError, ForbiddenError):
        # An ineligible target must not consume the staff member's normal login allowance.
        await enforce_login_rate_limit(request, f"staff:{caller.company_id}:{payload.user_id}")
        raise
    await enforce_login_rate_limit(request, user.email)
    failure = password_failure(user, payload.password.get_secret_value())
    if failure is not None:
        session.add(
            AuditLog(
                company_id=caller.company_id,
                actor_user_id=None,
                target_user_id=user.id,
                action="staff.attendance.authentication_failed",
                entity_type="User",
                entity_id=str(user.id),
                business_details={"reason": failure, "initiated_by_user_id": str(caller.user_id)},
            )
        )
        # A rejected action must still persist the shared normal-login lockout counters.
        await session.commit()
        raise AuthError(
            "account temporarily locked" if failure == "account_locked" else "invalid credentials"
        )
    request.state.idempotency_request_hash = _action_hash(request, payload, caller)
    return user, target


async def _clock(request, session, caller, payload, *, clock_out: bool):
    user, target = await _authenticate(session, request, caller, payload)
    previous = actor_ctx.get()
    token = actor_ctx.set(
        {
            **(previous or {}),
            "user_id": target.user_id,
            "company_id": target.company_id,
            "terminal_id": target.terminal_id,
        }
    )
    try:
        if clock_out:
            result = await _clock_out(
                user.id,
                payload.attendance_id,
                payload.break_minutes,
                None,
                request,
                session,
                target,
            )
        else:
            result = await _clock_in(user.id, target.branch_id, None, request, session, target)
        if isinstance(result, JSONResponse):
            return result
        result = {**result, "staff_name": user.name}
        await store_response(
            session,
            key=request.state.idempotency_key,
            status_code=200 if clock_out else 201,
            body=result,
        )
        session.add(
            AuditLog(
                company_id=target.company_id,
                actor_user_id=target.user_id,
                target_user_id=target.user_id,
                action="staff.attendance.shared_clock_out"
                if clock_out
                else "staff.attendance.shared_clock_in",
                entity_type="Attendance",
                entity_id=result["id"],
                business_details={
                    "initiated_by_user_id": str(caller.user_id),
                    "terminal_id": str(caller.terminal_id),
                },
            )
        )
        await session.flush()
        return result
    finally:
        actor_ctx.reset(token)


@router.post("/clock-in", response_model=SharedAttendanceRead, status_code=201)
async def clock_in(
    payload: SharedClockInWrite, request: Request, session: SessionDep, tenant: AttendanceTenant
):
    return await _clock(request, session, tenant, payload, clock_out=False)


@router.post("/clock-out", response_model=SharedAttendanceRead)
async def clock_out(
    payload: SharedClockOutWrite, request: Request, session: SessionDep, tenant: AttendanceTenant
):
    return await _clock(request, session, tenant, payload, clock_out=True)
