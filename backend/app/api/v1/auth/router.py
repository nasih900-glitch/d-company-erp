"""Auth endpoints — login, refresh, me."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import uuid4

from fastapi import APIRouter, Request, status
from pydantic import BaseModel
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import AuthError
from app.core.roles import has_protected_owner_access, public_roles
from app.core.security import (
    decode_token,
    hash_password,
    issue_access_token,
    issue_refresh_token,
    verify_password,
)
from app.core.tenant import TenantDep
from app.models import AuditLog, Company, Role, User, UserRole

router = APIRouter()


class LoginRequest(BaseModel):
    # Plain str instead of EmailStr — EmailStr's TLD allowlist rejects
    # `.local`, `.lan`, and various dev-friendly TLDs even when the
    # email format is valid. The database is the source of truth for
    # what's a real account; we don't need format checks at the door.
    email: str
    password: str


class TokenPair(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


class RefreshRequest(BaseModel):
    refresh_token: str


class MeResponse(BaseModel):
    user_id: str
    email: str
    name: str
    roles: list[str]
    protected_access: bool = False
    company_id: str
    branch_id: str | None


async def _fallback_company_id(session: SessionDep):
    company = (await session.execute(select(Company).limit(1))).scalar_one_or_none()
    return company.id if company else None


def _request_ip(request: Request) -> str | None:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return request.client.host if request.client else None


def _audit_auth_event(
    *,
    session: SessionDep,
    request: Request,
    action: str,
    email: str,
    company_id,
    user: User | None = None,
    details: dict | None = None,
) -> None:
    if company_id is None:
        return
    session.add(
        AuditLog(
            actor_user_id=user.id if user else None,
            company_id=company_id,
            action=action,
            entity_type="User",
            entity_id=str(user.id) if user else email,
            before=None,
            after={
                "email": email,
                "result": action,
                **(details or {}),
            },
            ip=_request_ip(request),
            user_agent=request.headers.get("user-agent"),
        )
    )


@router.post("/login", response_model=TokenPair, status_code=status.HTTP_200_OK)
async def login(payload: LoginRequest, request: Request, session: SessionDep) -> TokenPair:
    email = payload.email.strip().lower()
    user = (
        await session.execute(select(User).where(User.email == email))
    ).scalar_one_or_none()
    if not user or user.deleted_at:
        company_id = await _fallback_company_id(session)
        _audit_auth_event(
            session=session,
            request=request,
            action="login_failed",
            email=email,
            company_id=company_id,
            details={"reason": "unknown_user"},
        )
        await session.commit()
        raise AuthError("invalid credentials")
    if user.status != "active":
        _audit_auth_event(
            session=session,
            request=request,
            action="login_failed",
            email=user.email,
            company_id=user.company_id,
            user=user,
            details={"reason": "account_inactive", "status": user.status},
        )
        await session.commit()
        raise AuthError("invalid credentials")
    if user.locked_until and user.locked_until > datetime.now(timezone.utc):
        _audit_auth_event(
            session=session,
            request=request,
            action="login_failed",
            email=user.email,
            company_id=user.company_id,
            user=user,
            details={"reason": "account_locked"},
        )
        await session.commit()
        raise AuthError("account temporarily locked")
    if not verify_password(payload.password, user.password_hash):
        user.failed_login_count = (user.failed_login_count or 0) + 1
        if user.failed_login_count >= 5:
            user.locked_until = datetime.now(timezone.utc) + timedelta(minutes=15)
        _audit_auth_event(
            session=session,
            request=request,
            action="login_failed",
            email=user.email,
            company_id=user.company_id,
            user=user,
            details={
                "reason": "invalid_password",
                "failed_login_count": user.failed_login_count,
                "locked_until": user.locked_until.isoformat() if user.locked_until else None,
            },
        )
        await session.commit()
        raise AuthError("invalid credentials")

    user.failed_login_count = 0
    user.locked_until = None
    user.last_login_at = datetime.now(timezone.utc)

    # Roles
    role_rows = (
        await session.execute(
            select(Role.code)
            .join(UserRole, UserRole.role_id == Role.id)
            .where(UserRole.user_id == user.id)
        )
    ).all()
    roles = [r[0] for r in role_rows]

    _audit_auth_event(
        session=session,
        request=request,
        action="login_success",
        email=user.email,
        company_id=user.company_id,
        user=user,
        details={"name": user.name, "roles": public_roles(roles)},
    )

    protected_access = has_protected_owner_access(roles)
    access = issue_access_token(
        user_id=user.id,
        company_id=user.company_id,
        roles=public_roles(roles),
        extra={"protected_access": protected_access},
    )
    refresh = issue_refresh_token(user_id=user.id, jti=str(uuid4()))

    return TokenPair(access_token=access, refresh_token=refresh, expires_in=15 * 60)


@router.post("/refresh", response_model=TokenPair)
async def refresh(payload: RefreshRequest, session: SessionDep) -> TokenPair:
    try:
        claims = decode_token(payload.refresh_token)
    except ValueError as exc:
        raise AuthError(str(exc)) from exc
    if claims.get("type") != "refresh":
        raise AuthError("not a refresh token")
    user_id = claims["sub"]
    user = (
        await session.execute(select(User).where(User.id == user_id))
    ).scalar_one_or_none()
    if not user or user.deleted_at or user.status != "active":
        raise AuthError("user not found")
    role_rows = (
        await session.execute(
            select(Role.code)
            .join(UserRole, UserRole.role_id == Role.id)
            .where(UserRole.user_id == user.id)
        )
    ).all()
    roles = [r[0] for r in role_rows]
    protected_access = has_protected_owner_access(roles)
    access = issue_access_token(
        user_id=user.id,
        company_id=user.company_id,
        roles=public_roles(roles),
        extra={"protected_access": protected_access},
    )
    new_refresh = issue_refresh_token(user_id=user.id, jti=str(uuid4()))
    return TokenPair(access_token=access, refresh_token=new_refresh, expires_in=15 * 60)


@router.get("/me", response_model=MeResponse)
async def me(tenant: TenantDep, session: SessionDep) -> MeResponse:
    user = (
        await session.execute(select(User).where(User.id == tenant.user_id))
    ).scalar_one()
    if user.status != "active" or user.deleted_at:
        raise AuthError("user not found")
    return MeResponse(
        user_id=str(user.id),
        email=user.email,
        name=user.name,
        roles=list(tenant.roles),
        protected_access=tenant.protected_access,
        company_id=str(tenant.company_id),
        branch_id=str(tenant.branch_id) if tenant.branch_id else None,
    )


# Bcrypt/Argon2 cost utility — handy in admin scripts.
def _hash(password: str) -> str:
    return hash_password(password)
