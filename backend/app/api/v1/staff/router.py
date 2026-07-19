"""Staff endpoints — users, roles, and attendance.

Login creation and password changes are deliberately handled by the central-email
OTP endpoints under ``/auth``. The legacy write routes remain as explicit guards
so old clients cannot bypass that approval flow.
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import delete, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.permissions import ROLE_DESCRIPTIONS, requires
from app.core.roles import PROTECTED_OWNER_ROLE, public_roles
from app.core.tenant import TenantContext
from app.models import Attendance, Branch, Role, User, UserRole

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class UserCreate(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    name: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=10, max_length=256)
    phone: str | None = Field(default=None, max_length=20)
    # owner|partner|manager|cashier|kitchen|gaming_supervisor|auditor
    role_code: str = Field(default="cashier", min_length=1, max_length=50)


class UserUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    phone: str | None = Field(default=None, max_length=20)
    status: str | None = Field(default=None, pattern="^(active|suspended)$")
    role_code: str | None = None


class PasswordChange(BaseModel):
    new_password: str = Field(min_length=10, max_length=256)


class UserRead(BaseModel):
    id: UUID
    email: str
    name: str
    phone: str | None
    status: str
    roles: list[str]
    last_login_at: datetime | None


class ClockInRequest(BaseModel):
    branch_id: UUID
    notes: str | None = Field(default=None, max_length=500)


class OnShiftRead(BaseModel):
    id: UUID
    user_id: UUID
    user_name: str | None = None
    user_email: str | None = None
    branch_id: UUID
    branch_name: str | None = None
    clock_in_at: datetime
    notes: str | None = None


# ---------------------------------------------------------------- helpers
async def _roles_for_user(session, user_id: UUID) -> list[str]:
    return public_roles(await _raw_roles_for_user(session, user_id))


async def _raw_roles_for_user(session, user_id: UUID) -> list[str]:
    rows = (
        await session.execute(
            select(Role.code)
            .join(UserRole, UserRole.role_id == Role.id)
            .where(UserRole.user_id == user_id)
        )
    ).scalars().all()
    return list(rows)


async def _set_role(session, tenant: TenantContext, user_id: UUID, role_code: str) -> None:
    if role_code == PROTECTED_OWNER_ROLE:
        raise BusinessRuleError("protected owner access cannot be assigned from Staff")
    if PROTECTED_OWNER_ROLE in await _raw_roles_for_user(session, user_id):
        raise BusinessRuleError("protected owner role cannot be changed from Staff")
    role = (
        await session.execute(
            select(Role).where(Role.company_id == tenant.company_id, Role.code == role_code)
        )
    ).scalar_one_or_none()
    if not role:
        raise BusinessRuleError(f"role '{role_code}' does not exist for this company")
    # Remove existing role assignments — one role per user in this simple model.
    await session.execute(delete(UserRole).where(UserRole.user_id == user_id))
    session.add(
        UserRole(
            id=uuid4(),
            user_id=user_id,
            role_id=role.id,
            branch_id=None,
            granted_by=tenant.user_id,
        )
    )


# ---------------------------------------------------------------- routes
@router.get("/users", response_model=list[UserRead])
async def list_users(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.read")),
) -> list[UserRead]:
    rows = (
        await session.execute(
            select(User).where(
                User.company_id == tenant.company_id,
                User.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    out: list[UserRead] = []
    for u in rows:
        out.append(
            UserRead(
                id=u.id,
                email=u.email,
                name=u.name,
                phone=u.phone,
                status=u.status,
                roles=await _roles_for_user(session, u.id),
                last_login_at=u.last_login_at,
            )
        )
    return out


@router.get("/users/{user_id}", response_model=UserRead)
async def get_user(
    user_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.read")),
) -> UserRead:
    u = await session.get(User, user_id)
    if not u or u.company_id != tenant.company_id or u.deleted_at:
        raise NotFoundError("user not found")
    return UserRead(
        id=u.id, email=u.email, name=u.name, phone=u.phone, status=u.status,
        roles=await _roles_for_user(session, u.id), last_login_at=u.last_login_at,
    )


@router.post("/users", response_model=UserRead, status_code=status.HTTP_201_CREATED)
async def create_user(
    payload: UserCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.write")),
) -> UserRead:
    del payload, session, tenant
    raise BusinessRuleError(
        "OTP approval is required. Use Create new login from the sign-in or Staff screen."
    )


@router.patch("/users/{user_id}", response_model=UserRead)
async def update_user(
    user_id: UUID,
    payload: UserUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.write")),
) -> UserRead:
    u = await session.get(User, user_id)
    if not u or u.company_id != tenant.company_id or u.deleted_at:
        raise NotFoundError("user not found")
    is_self = u.id == tenant.user_id
    is_protected_owner = PROTECTED_OWNER_ROLE in await _raw_roles_for_user(session, u.id)
    if is_self and (payload.role_code is not None or payload.status == "suspended"):
        raise BusinessRuleError("you cannot remove or suspend your own access")
    if is_protected_owner and payload.status == "suspended":
        raise BusinessRuleError("protected owner cannot be suspended from Staff")
    if payload.name is not None:
        u.name = payload.name
    if payload.phone is not None:
        u.phone = payload.phone
    if payload.status is not None:
        u.status = payload.status
        u.auth_version += 1
    if payload.role_code is not None:
        await _set_role(session, tenant, u.id, payload.role_code)
        u.auth_version += 1
    await session.flush()
    return UserRead(
        id=u.id, email=u.email, name=u.name, phone=u.phone, status=u.status,
        roles=await _roles_for_user(session, u.id), last_login_at=u.last_login_at,
    )


@router.post("/users/{user_id}/password", status_code=status.HTTP_204_NO_CONTENT)
async def change_password(
    user_id: UUID,
    payload: PasswordChange,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.write")),
):
    del user_id, payload, session, tenant
    raise BusinessRuleError(
        "OTP approval is required. Use Reset password from the sign-in or Staff screen."
    )


@router.delete("/users/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(
    user_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.write")),
):
    u = await session.get(User, user_id)
    if not u or u.company_id != tenant.company_id or u.deleted_at:
        raise NotFoundError("user not found")
    if u.id == tenant.user_id:
        raise BusinessRuleError("you cannot delete your own account")
    if PROTECTED_OWNER_ROLE in await _raw_roles_for_user(session, u.id):
        raise BusinessRuleError("protected owner cannot be deleted")
    u.deleted_at = datetime.now(timezone.utc)
    u.status = "suspended"
    u.auth_version += 1
    await session.flush()


@router.get("/roles", response_model=list[dict])
async def list_roles(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.read")),
) -> list[dict]:
    rows = (
        await session.execute(
            select(Role).where(
                Role.company_id == tenant.company_id,
                Role.code != "super_owner",
            )
        )
    ).scalars().all()
    return [
        {
            "code": r.code,
            "name": r.name,
            "description": ROLE_DESCRIPTIONS.get(r.code, r.description),
        }
        for r in rows
    ]


# ---------------------------------------------------------------- own password
class MyPasswordChange(BaseModel):
    current_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=10, max_length=256)


@router.post("/me/password", status_code=status.HTTP_204_NO_CONTENT)
async def change_my_password(
    payload: MyPasswordChange,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),  # any logged-in user
):
    del payload, session, tenant
    raise BusinessRuleError(
        "OTP approval is required. Use Reset password from Account settings."
    )


# ---------------------------------------------------------------- attendance
@router.post("/attendance/clock-in", status_code=status.HTTP_201_CREATED)
async def clock_in(
    payload: ClockInRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.attendance.write")),
) -> dict:
    branch = await session.get(Branch, payload.branch_id)
    if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    a = Attendance(
        id=uuid4(),
        company_id=tenant.company_id,
        user_id=tenant.user_id,
        branch_id=payload.branch_id,
        clock_in_at=datetime.now(timezone.utc),
        notes=payload.notes,
    )
    session.add(a)
    return {"id": str(a.id)}


@router.post("/attendance/clock-out")
async def clock_out(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.attendance.write")),
) -> dict:
    # Most recent still-open attendance row for this user in this company.
    # `.limit(1)` keeps this safe even if a client somehow clocked in twice
    # without clocking out in between (clock-in does not itself guard against
    # that) — we close the latest open session rather than erroring.
    a = (
        await session.execute(
            select(Attendance)
            .where(
                Attendance.company_id == tenant.company_id,
                Attendance.user_id == tenant.user_id,
                Attendance.clock_out_at.is_(None),
            )
            .order_by(Attendance.clock_in_at.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    if not a:
        raise HTTPException(
            status_code=400,
            detail="No open clock-in found. Clock in before clocking out.",
        )
    a.clock_out_at = datetime.now(timezone.utc)
    await session.flush()
    return {"id": str(a.id), "clock_out_at": a.clock_out_at.isoformat()}


@router.get("/attendance/on-shift", response_model=list[OnShiftRead])
async def list_on_shift(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.read")),
) -> list[OnShiftRead]:
    stmt = (
        select(Attendance, User.name, User.email, Branch.name)
        .join(User, User.id == Attendance.user_id)
        .join(Branch, Branch.id == Attendance.branch_id)
        .where(
            Attendance.company_id == tenant.company_id,
            Attendance.clock_out_at.is_(None),
        )
        .order_by(Attendance.clock_in_at.asc())
    )
    if tenant.branch_id is not None:
        stmt = stmt.where(Attendance.branch_id == tenant.branch_id)
    rows = (await session.execute(stmt)).all()
    return [
        OnShiftRead(
            id=a.id,
            user_id=a.user_id,
            user_name=user_name,
            user_email=user_email,
            branch_id=a.branch_id,
            branch_name=branch_name,
            clock_in_at=a.clock_in_at,
            notes=a.notes,
        )
        for a, user_name, user_email, branch_name in rows
    ]
