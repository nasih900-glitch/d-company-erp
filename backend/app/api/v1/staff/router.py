"""Staff endpoints — users, roles, attendance.

Full CRUD: list, create, update (name/phone/status), change role, change password,
soft-delete. Each user can have multiple roles (multi-branch); for the simple
single-branch flow we expose a single `role` field that returns/sets one role.
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import delete, select

from app.core.db import SessionDep
from app.core.errors import ConflictError, NotFoundError, BusinessRuleError
from app.core.permissions import requires
from app.core.roles import PROTECTED_OWNER_ROLE, public_roles
from app.core.security import hash_password
from app.core.tenant import TenantContext
from app.models import Attendance, Role, User, UserRole

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class UserCreate(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    name: str = Field(min_length=1, max_length=200)
    password: str = Field(min_length=8)
    phone: str | None = None
    # owner|partner|manager|cashier|kitchen|gaming_supervisor|auditor
    role_code: str = Field(default="cashier")


class UserUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    phone: str | None = None
    status: str | None = Field(default=None, pattern="^(active|suspended)$")
    role_code: str | None = None


class PasswordChange(BaseModel):
    new_password: str = Field(min_length=8)


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
    email = payload.email.strip().lower()
    existing = (
        await session.execute(
            select(User).where(
                User.company_id == tenant.company_id, User.email == email
            )
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError("a user with this email already exists in this company")
    u = User(
        id=uuid4(),
        company_id=tenant.company_id,
        email=email,
        name=payload.name,
        password_hash=hash_password(payload.password),
        phone=payload.phone,
        status="active",
    )
    session.add(u)
    await session.flush()
    await _set_role(session, tenant, u.id, payload.role_code)
    return UserRead(
        id=u.id, email=u.email, name=u.name, phone=u.phone, status=u.status,
        roles=await _roles_for_user(session, u.id), last_login_at=u.last_login_at,
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
    if payload.role_code is not None:
        await _set_role(session, tenant, u.id, payload.role_code)
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
    u = await session.get(User, user_id)
    if not u or u.company_id != tenant.company_id or u.deleted_at:
        raise NotFoundError("user not found")
    if u.id != tenant.user_id and PROTECTED_OWNER_ROLE in await _raw_roles_for_user(session, u.id):
        raise BusinessRuleError("protected owner password cannot be changed from Staff")
    u.password_hash = hash_password(payload.new_password)
    await session.flush()


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
        {"code": r.code, "name": r.name, "description": r.description}
        for r in rows
    ]


# ---------------------------------------------------------------- own password
class MyPasswordChange(BaseModel):
    current_password: str
    new_password: str = Field(min_length=8)


@router.post("/me/password", status_code=status.HTTP_204_NO_CONTENT)
async def change_my_password(
    payload: MyPasswordChange,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),  # any logged-in user
):
    from app.core.security import verify_password
    u = await session.get(User, tenant.user_id)
    if not u or u.deleted_at:
        raise NotFoundError("user not found")
    if not verify_password(payload.current_password, u.password_hash):
        raise BusinessRuleError("current password is incorrect")
    u.password_hash = hash_password(payload.new_password)
    await session.flush()


# ---------------------------------------------------------------- attendance
@router.post("/attendance/clock-in", status_code=status.HTTP_201_CREATED)
async def clock_in(
    payload: ClockInRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("staff.attendance.write")),
) -> dict:
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
