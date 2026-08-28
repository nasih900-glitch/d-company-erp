"""Auth endpoints — login, refresh, account registration, and password recovery."""

from __future__ import annotations

import hashlib
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, Request, Response, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.client_ip import audit_user_agent, trusted_client_ip
from app.core.config import get_settings
from app.core.db import SessionDep
from app.core.errors import AuthError, BusinessRuleError, ConflictError, ServiceUnavailableError
from app.core.permissions import (
    SELF_SERVICE_SIGNUP_ROLE,
    effective_permissions,
    modules_for_permissions,
)
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
    refresh_token: str | None = None


class MeResponse(BaseModel):
    user_id: str
    email: str
    name: str
    roles: list[str]
    protected_access: bool = False
    audit_access: bool = False
    company_id: str
    branch_id: str | None
    # Display metadata only. Authorization remains anchored to branch_id in
    # TenantContext; the value is resolved with an explicit company boundary.
    branch_name: str | None = None
    accessible_modules: list[str] = Field(default_factory=list)
    effective_permissions: list[str] = Field(default_factory=list)


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


_REFRESH_COOKIE = "dcompany_refresh"
_COOKIE_SESSION_HEADER = "x-session-transport"


def _uses_cookie_session(request: Request) -> bool:
    return request.headers.get(_COOKIE_SESSION_HEADER, "").strip().lower() == "cookie"


def _set_refresh_cookie(response: Response, refresh_token: str) -> None:
    settings = get_settings()
    response.set_cookie(
        key=_REFRESH_COOKIE,
        value=refresh_token,
        max_age=settings.refresh_token_days * 24 * 60 * 60,
        httponly=True,
        secure=settings.env in {"prod", "staging"},
        samesite="strict",
        path=f"{settings.api_prefix}/auth",
    )


def _clear_refresh_cookie(response: Response) -> None:
    settings = get_settings()
    response.delete_cookie(
        key=_REFRESH_COOKIE,
        httponly=True,
        secure=settings.env in {"prod", "staging"},
        samesite="strict",
        path=f"{settings.api_prefix}/auth",
    )


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
        (await session.execute(select(Company).where(Company.deleted_at.is_(None)).limit(2)))
        .scalars()
        .all()
    )
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
            select(
                Role.code,
                Role.company_id.label("role_company_id"),
                UserRole.branch_id,
                Branch.company_id.label("branch_company_id"),
                Branch.deleted_at.label("branch_deleted_at"),
            )
            .join(UserRole, UserRole.role_id == Role.id)
            .outerjoin(Branch, Branch.id == UserRole.branch_id)
            .where(UserRole.user_id == user.id)
            .order_by(UserRole.created_at.asc(), UserRole.id.asc())
        )
    ).all()
    branch_ids: set[UUID] = set()
    for row in rows:
        if row.role_company_id != user.company_id:
            raise AuthError(
                "Account role assignment is invalid. Ask the protected owner to correct it."
            )
        if row.branch_id is not None:
            if (
                row.branch_company_id != user.company_id
                or row.branch_deleted_at is not None
            ):
                raise AuthError(
                    "Account branch assignment is invalid. "
                    "Ask the protected owner to correct it."
                )
            branch_ids.add(row.branch_id)
    if len(branch_ids) > 1:
        raise AuthError(
            "Account roles are assigned to multiple branches. "
            "Ask the protected owner to select one branch."
        )
    roles = [row.code for row in rows]
    assigned_branch_id = next(iter(branch_ids), None)
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
    if not user or user.company_id != company_id or user.deleted_at or user.status != "active":
        return None
    return user


def _auth_audit_entity_id(email: str, user: User | None) -> str:
    if user is not None:
        return str(user.id)
    normalized = email.strip().lower()
    if len(normalized) <= 64:
        return normalized
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
    return f"email-sha256:{digest[:51]}"


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
            entity_id=_auth_audit_entity_id(email, user),
            before=None,
            after={
                "email": email,
                "result": action,
                **(details or {}),
            },
            ip=trusted_client_ip(request),
            user_agent=audit_user_agent(request),
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
        ip=trusted_client_ip(request),
        user_agent=audit_user_agent(request),
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
            ip=trusted_client_ip(request),
            user_agent=audit_user_agent(request),
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
        ip=trusted_client_ip(request),
        user_agent=audit_user_agent(request),
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
            ip=trusted_client_ip(request),
            user_agent=audit_user_agent(request),
        )
    )
    return AccountActionResponse(message="Password updated. You can sign in now.")


@router.post("/login", response_model=TokenPair, status_code=status.HTTP_200_OK)
async def login(
    payload: LoginRequest,
    request: Request,
    response: Response,
    session: SessionDep,
) -> TokenPair:
    settings = get_settings()
    email = payload.email.strip().lower()
    await enforce_login_rate_limit(request, email)
    users = (await session.execute(select(User).where(User.email == email))).scalars().all()
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

    if _uses_cookie_session(request):
        _set_refresh_cookie(response, refresh)

    return TokenPair(
        access_token=access,
        # Same-origin web clients receive the refresh credential only through
        # the HttpOnly cookie. Native clients keep the existing JSON contract.
        refresh_token="" if _uses_cookie_session(request) else refresh,
        expires_in=settings.access_token_minutes * 60,
    )


@router.post("/refresh", response_model=TokenPair)
async def refresh(
    request: Request,
    response: Response,
    session: SessionDep,
    payload: RefreshRequest | None = None,
) -> TokenPair:
    settings = get_settings()
    cookie_refresh = request.cookies.get(_REFRESH_COOKIE)
    if (
        cookie_refresh
        and not (payload and payload.refresh_token)
        and not _uses_cookie_session(request)
    ):
        # Requiring a non-simple custom header makes a browser cookie refresh
        # ineligible for a cross-site HTML form submission. SameSite=Strict is
        # the first boundary; this is the explicit CSRF boundary as well.
        raise AuthError("cookie session header required")
    body_refresh = payload.refresh_token if payload and payload.refresh_token else None
    # Once a browser has a cookie, it is authoritative. The optional body is
    # only a one-release migration path from the former localStorage token;
    # preferring a stale migration value could otherwise reject a still-valid
    # rotated cookie.
    supplied_refresh = (
        (cookie_refresh or body_refresh)
        if _uses_cookie_session(request)
        else (body_refresh or cookie_refresh)
    )
    if not supplied_refresh:
        raise AuthError("refresh token required")
    try:
        claims = decode_token(supplied_refresh)
    except ValueError as exc:
        raise AuthError(str(exc)) from exc
    if claims.get("type") != "refresh":
        raise AuthError("not a refresh token")
    try:
        user_id = UUID(claims["sub"])
        auth_version = int(claims.get("auth_version", 0))
    except (KeyError, TypeError, ValueError) as exc:
        raise AuthError("malformed token claims") from exc
    user = (await session.execute(select(User).where(User.id == user_id))).scalar_one_or_none()
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
    cookie_session = _uses_cookie_session(request) or _REFRESH_COOKIE in request.cookies
    if cookie_session:
        _set_refresh_cookie(response, new_refresh)
    return TokenPair(
        access_token=access,
        refresh_token="" if cookie_session else new_refresh,
        expires_in=settings.access_token_minutes * 60,
    )


@router.post("/logout", response_model=AccountActionResponse)
async def logout(request: Request, response: Response) -> AccountActionResponse:
    """Forget the browser refresh cookie.

    Refresh tokens are otherwise stateless JWTs, so account-wide revocation is
    still performed by incrementing the user's auth_version. This endpoint is
    the browser's local sign-out boundary and deliberately returns success even
    when no cookie exists.
    """
    if request.cookies.get(_REFRESH_COOKIE) and not _uses_cookie_session(request):
        raise AuthError("cookie session header required")
    _clear_refresh_cookie(response)
    return AccountActionResponse(message="Signed out.")


@router.get("/me", response_model=MeResponse)
async def me(tenant: TenantDep, session: SessionDep) -> MeResponse:
    user = (await session.execute(select(User).where(User.id == tenant.user_id))).scalar_one()
    if user.status != "active" or user.deleted_at:
        raise AuthError("user not found")
    branch_name = None
    if tenant.branch_id is not None:
        branch_name = (
            await session.execute(
                select(Branch.name).where(
                    Branch.id == tenant.branch_id,
                    Branch.company_id == tenant.company_id,
                    Branch.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
    permissions = await effective_permissions(session, tenant)
    return MeResponse(
        user_id=str(user.id),
        email=user.email,
        name=user.name,
        roles=list(tenant.roles),
        protected_access=tenant.protected_access,
        audit_access=tenant.audit_access,
        company_id=str(tenant.company_id),
        branch_id=str(tenant.branch_id) if tenant.branch_id else None,
        branch_name=branch_name,
        accessible_modules=modules_for_permissions(permissions),
        effective_permissions=permissions,
    )


# Bcrypt/Argon2 cost utility — handy in admin scripts.
def _hash(password: str) -> str:
    return hash_password(password)
