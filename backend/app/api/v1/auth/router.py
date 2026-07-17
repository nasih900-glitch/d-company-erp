"""Auth endpoints — login, refresh, account registration, and password recovery."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.config import get_settings
from app.core.db import SessionDep
from app.core.errors import AuthError, BusinessRuleError, ConflictError, ServiceUnavailableError
from app.core.permissions import SELF_SERVICE_SIGNUP_ROLE, accessible_modules
from app.core.roles import has_full_access, has_protected_owner_access, public_roles
from app.core.security import (
    decode_token,
    hash_password,
    issue_access_token,
    issue_refresh_token,
    verify_password,
)
from app.core.tenant import TenantDep
from app.models import AuditLog, Branch, Company, Role, User, UserRole
from app.services.audit.recorder import set_actor
from app.services.auth.otp import (
    OTP_PASSWORD_RESET,
    OTP_REGISTER,
    consume_challenge,
    create_challenge,
    masked_security_email,
    normalize_account_email,
)
from app.services.auth.rate_limit import enforce_login_rate_limit

router = APIRouter()


class LoginRequest(BaseModel):
    # Plain str instead of EmailStr — EmailStr's TLD allowlist rejects
    # `.local`, `.lan`, and various dev-friendly TLDs even when the
    # email format is valid. The database is the source of truth for
    # what's a real account; we don't need format checks at the door.
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=256)


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
    audit_access: bool = False
    company_id: str
    branch_id: str | None
    accessible_modules: list[str] = []


class OtpChallengeResponse(BaseModel):
    challenge_id: UUID
    expires_in: int
    destination: str


class PasswordResetRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)


class PasswordResetConfirm(BaseModel):
    challenge_id: UUID
    code: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")
    new_password: str = Field(min_length=10, max_length=256)


class RegistrationRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    name: str = Field(min_length=1, max_length=200)
    phone: str | None = Field(default=None, max_length=20)
    password: str = Field(min_length=10, max_length=256)


class RegistrationConfirm(BaseModel):
    challenge_id: UUID
    code: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")


class AccountActionResponse(BaseModel):
    message: str


async def _fallback_company_id(session: SessionDep):
    company = (await session.execute(select(Company).limit(1))).scalar_one_or_none()
    return company.id if company else None


async def _account_security_company(session: SessionDep) -> Company:
    configured_id = get_settings().account_security_company_id
    if configured_id:
        company = (
            await session.execute(
                select(Company).where(
                    Company.id == configured_id,
                    Company.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if company:
            return company
        raise ServiceUnavailableError("account registration company is not available")

    companies = (
        await session.execute(
            select(Company).where(Company.deleted_at.is_(None)).limit(2)
        )
    ).scalars().all()
    if len(companies) != 1:
        raise ServiceUnavailableError("account registration company is not configured")
    return companies[0]


async def _default_branch_id(session: SessionDep, company_id: UUID) -> UUID | None:
    return (
        await session.execute(
            select(Branch.id)
            .where(
                Branch.company_id == company_id,
                Branch.deleted_at.is_(None),
            )
            .order_by(Branch.created_at.asc(), Branch.id.asc())
            .limit(1)
        )
    ).scalar_one_or_none()


async def _roles_and_branch(
    session: SessionDep,
    user: User,
) -> tuple[list[str], UUID | None]:
    rows = (
        await session.execute(
            select(Role.code, UserRole.branch_id)
            .join(UserRole, UserRole.role_id == Role.id)
            .where(UserRole.user_id == user.id)
            .order_by(UserRole.created_at.asc(), UserRole.id.asc())
        )
    ).all()
    roles = [row.code for row in rows]
    assigned_branch_id = next(
        (row.branch_id for row in rows if row.branch_id is not None),
        None,
    )
    return roles, assigned_branch_id or await _default_branch_id(session, user.company_id)


async def _optional_requester(
    request: Request,
    session: SessionDep,
    company_id: UUID,
) -> User | None:
    authorization = request.headers.get("authorization", "")
    if not authorization.lower().startswith("bearer "):
        return None
    try:
        claims = decode_token(authorization.split(" ", 1)[1])
        if claims.get("type") != "access":
            return None
        user = await session.get(User, UUID(claims["sub"]))
    except (KeyError, TypeError, ValueError):
        return None
    if (
        not user
        or user.company_id != company_id
        or user.deleted_at
        or user.status != "active"
    ):
        return None
    return user


def _request_ip(request: Request) -> str | None:
    forwarded = request.headers.get("x-forwarded-for")
    value = forwarded.split(",", 1)[0].strip() if forwarded else None
    if not value and request.client:
        value = request.client.host
    return value[:64] if value else None


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


@router.post(
    "/register/request",
    response_model=OtpChallengeResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def request_registration(
    payload: RegistrationRequest,
    request: Request,
    session: SessionDep,
) -> OtpChallengeResponse:
    company = await _account_security_company(session)
    email = normalize_account_email(payload.email)
    name = payload.name.strip()
    if not name:
        raise BusinessRuleError("enter the account holder's name")
    existing = (
        await session.execute(
            select(User).where(User.company_id == company.id, User.email == email)
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError("a login with this email already exists")
    requester = await _optional_requester(request, session, company.id)
    challenge = await create_challenge(
        session,
        request,
        purpose=OTP_REGISTER,
        company_id=company.id,
        target_email=email,
        target_user_id=None,
        requested_by_user_id=requester.id if requester else None,
        pending_name=name,
        pending_phone=(payload.phone.strip() or None) if payload.phone else None,
        pending_role_code=SELF_SERVICE_SIGNUP_ROLE,
        pending_password_hash=hash_password(payload.password),
    )
    return OtpChallengeResponse(
        challenge_id=challenge.id,
        expires_in=get_settings().account_otp_ttl_minutes * 60,
        destination=masked_security_email(),
    )


@router.post(
    "/register/confirm",
    response_model=AccountActionResponse,
    status_code=status.HTTP_201_CREATED,
)
async def confirm_registration(
    payload: RegistrationConfirm,
    request: Request,
    session: SessionDep,
) -> AccountActionResponse:
    challenge = await consume_challenge(
        session,
        challenge_id=payload.challenge_id,
        code=payload.code,
        purpose=OTP_REGISTER,
    )
    if not challenge.pending_name or not challenge.pending_password_hash:
        raise BusinessRuleError("invalid or expired approval code")
    existing = (
        await session.execute(
            select(User).where(
                User.company_id == challenge.company_id,
                User.email == challenge.target_email,
            )
        )
    ).scalar_one_or_none()
    if existing:
        raise ConflictError("a login with this email already exists")
    role_code = challenge.pending_role_code or SELF_SERVICE_SIGNUP_ROLE
    role = (
        await session.execute(
            select(Role).where(
                Role.company_id == challenge.company_id,
                Role.code == role_code,
            )
        )
    ).scalar_one_or_none()
    if not role:
        raise ServiceUnavailableError("the default account role is not configured")

    set_actor(
        user_id=challenge.requested_by_user_id,
        company_id=challenge.company_id,
        ip=_request_ip(request),
        user_agent=request.headers.get("user-agent"),
    )
    user = User(
        id=uuid4(),
        company_id=challenge.company_id,
        email=challenge.target_email,
        name=challenge.pending_name,
        password_hash=challenge.pending_password_hash,
        phone=challenge.pending_phone,
        status="active",
    )
    session.add(user)
    await session.flush()
    session.add(
        UserRole(
            id=uuid4(),
            user_id=user.id,
            role_id=role.id,
            branch_id=await _default_branch_id(session, challenge.company_id),
            granted_by=challenge.requested_by_user_id,
        )
    )
    session.add(
        AuditLog(
            actor_user_id=challenge.requested_by_user_id,
            company_id=challenge.company_id,
            action="otp_account_created",
            entity_type="User",
            entity_id=str(user.id),
            before=None,
            after={"email": user.email, "name": user.name, "role": role_code},
            ip=_request_ip(request),
            user_agent=request.headers.get("user-agent"),
        )
    )
    return AccountActionResponse(message="Login created. You can sign in now.")


@router.post(
    "/password-reset/request",
    response_model=OtpChallengeResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def request_password_reset(
    payload: PasswordResetRequest,
    request: Request,
    session: SessionDep,
) -> OtpChallengeResponse:
    email = normalize_account_email(payload.email)
    company = await _account_security_company(session)
    user = (
        await session.execute(
            select(User).where(
                User.company_id == company.id,
                User.email == email,
                User.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    requester = await _optional_requester(request, session, company.id)
    challenge = await create_challenge(
        session,
        request,
        purpose=OTP_PASSWORD_RESET,
        company_id=company.id,
        target_email=email,
        target_user_id=user.id if user else None,
        requested_by_user_id=requester.id if requester else None,
    )
    return OtpChallengeResponse(
        challenge_id=challenge.id,
        expires_in=get_settings().account_otp_ttl_minutes * 60,
        destination=masked_security_email(),
    )


@router.post("/password-reset/confirm", response_model=AccountActionResponse)
async def confirm_password_reset(
    payload: PasswordResetConfirm,
    request: Request,
    session: SessionDep,
) -> AccountActionResponse:
    challenge = await consume_challenge(
        session,
        challenge_id=payload.challenge_id,
        code=payload.code,
        purpose=OTP_PASSWORD_RESET,
    )
    user = await session.get(User, challenge.target_user_id) if challenge.target_user_id else None
    if (
        not user
        or user.company_id != challenge.company_id
        or user.deleted_at
        or user.email != challenge.target_email
    ):
        await session.commit()
        raise BusinessRuleError("invalid or expired approval code")

    set_actor(
        user_id=challenge.requested_by_user_id,
        company_id=challenge.company_id,
        ip=_request_ip(request),
        user_agent=request.headers.get("user-agent"),
    )
    user.password_hash = hash_password(payload.new_password)
    user.failed_login_count = 0
    user.locked_until = None
    # Invalidate every existing access/refresh token for this user so a reset
    # actually evicts a compromised session (both token types carry auth_version).
    user.auth_version = (user.auth_version or 0) + 1
    session.add(
        AuditLog(
            actor_user_id=challenge.requested_by_user_id,
            company_id=challenge.company_id,
            action="otp_password_reset_success",
            entity_type="User",
            entity_id=str(user.id),
            before=None,
            after={"email": user.email, "approval": "central_email_otp"},
            ip=_request_ip(request),
            user_agent=request.headers.get("user-agent"),
        )
    )
    return AccountActionResponse(message="Password updated. You can sign in now.")


@router.post("/login", response_model=TokenPair, status_code=status.HTTP_200_OK)
async def login(payload: LoginRequest, request: Request, session: SessionDep) -> TokenPair:
    settings = get_settings()
    email = payload.email.strip().lower()
    await enforce_login_rate_limit(request, email)
    users = (
        await session.execute(select(User).where(User.email == email))
    ).scalars().all()
    user = users[0] if len(users) == 1 else None
    if not user or user.deleted_at:
        company_id = await _fallback_company_id(session)
        _audit_auth_event(
            session=session,
            request=request,
            action="login_failed",
            email=email,
            company_id=company_id,
            details={
                "reason": "ambiguous_or_unknown_user",
                "matches": min(len(users), 2),
            },
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
        if user.failed_login_count >= settings.failed_login_lockout_threshold:
            user.locked_until = datetime.now(timezone.utc) + timedelta(
                minutes=settings.failed_login_lockout_minutes
            )
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
    roles, branch_id = await _roles_and_branch(session, user)

    _audit_auth_event(
        session=session,
        request=request,
        action="login_success",
        email=user.email,
        company_id=user.company_id,
        user=user,
        details={"name": user.name, "roles": public_roles(roles)},
    )

    protected_access = has_full_access(roles)
    audit_access = has_protected_owner_access(roles)
    access = issue_access_token(
        user_id=user.id,
        company_id=user.company_id,
        roles=public_roles(roles),
        branch_id=branch_id,
        auth_version=user.auth_version,
        extra={"protected_access": protected_access, "audit_access": audit_access},
    )
    refresh = issue_refresh_token(
        user_id=user.id,
        jti=str(uuid4()),
        auth_version=user.auth_version,
    )

    return TokenPair(
        access_token=access,
        refresh_token=refresh,
        expires_in=settings.access_token_minutes * 60,
    )


@router.post("/refresh", response_model=TokenPair)
async def refresh(payload: RefreshRequest, session: SessionDep) -> TokenPair:
    settings = get_settings()
    try:
        claims = decode_token(payload.refresh_token)
    except ValueError as exc:
        raise AuthError(str(exc)) from exc
    if claims.get("type") != "refresh":
        raise AuthError("not a refresh token")
    try:
        user_id = UUID(claims["sub"])
        auth_version = int(claims.get("auth_version", 0))
    except (KeyError, TypeError, ValueError) as exc:
        raise AuthError("malformed token claims") from exc
    user = (
        await session.execute(select(User).where(User.id == user_id))
    ).scalar_one_or_none()
    if not user or user.deleted_at or user.status != "active":
        raise AuthError("user not found")
    if auth_version != user.auth_version:
        raise AuthError("session expired")
    roles, branch_id = await _roles_and_branch(session, user)
    protected_access = has_full_access(roles)
    audit_access = has_protected_owner_access(roles)
    access = issue_access_token(
        user_id=user.id,
        company_id=user.company_id,
        roles=public_roles(roles),
        branch_id=branch_id,
        auth_version=user.auth_version,
        extra={"protected_access": protected_access, "audit_access": audit_access},
    )
    new_refresh = issue_refresh_token(
        user_id=user.id,
        jti=str(uuid4()),
        auth_version=user.auth_version,
    )
    return TokenPair(
        access_token=access,
        refresh_token=new_refresh,
        expires_in=settings.access_token_minutes * 60,
    )


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
        audit_access=tenant.audit_access,
        company_id=str(tenant.company_id),
        branch_id=str(tenant.branch_id) if tenant.branch_id else None,
        accessible_modules=await accessible_modules(session, tenant),
    )


# Bcrypt/Argon2 cost utility — handy in admin scripts.
def _hash(password: str) -> str:
    return hash_password(password)
