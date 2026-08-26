# ruff: noqa: E501
"""Central-email OTP approval for new logins and password changes."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import secrets
from datetime import UTC, datetime, timedelta
from html import escape
from uuid import UUID

from fastapi import Request
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.client_ip import audit_user_agent, trusted_client_ip
from app.core.config import get_settings
from app.core.errors import BusinessRuleError, RateLimitError, ServiceUnavailableError
from app.models import AuditLog, AuthOtpChallenge
from app.services.email.mailer import Mailer

OTP_REGISTER = "register"
OTP_PASSWORD_RESET = "password_reset"  # noqa: S105
OTP_PURPOSES = {OTP_REGISTER, OTP_PASSWORD_RESET}


def normalize_account_email(value: str) -> str:
    email = value.strip().lower()
    if (
        len(email) < 3
        or len(email) > 254
        or email.count("@") != 1
        or any(char.isspace() for char in email)
    ):
        raise BusinessRuleError("enter a valid login email")
    local, domain = email.split("@", 1)
    if not local or not domain or "." not in domain:
        raise BusinessRuleError("enter a valid login email")
    return email


def generate_otp() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def otp_hash(challenge_id: UUID, code: str) -> str:
    secret = get_settings().jwt_secret.encode("utf-8")
    key = hmac.new(secret, b"dcompany-account-otp-v1", hashlib.sha256).digest()
    value = f"{challenge_id}:{code}".encode()
    return hmac.new(key, value, hashlib.sha256).hexdigest()


def otp_matches(challenge_id: UUID, code: str, expected_hash: str) -> bool:
    if len(code) != 6 or not code.isdigit():
        return False
    return hmac.compare_digest(otp_hash(challenge_id, code), expected_hash)


def masked_security_email() -> str:
    email = get_settings().account_security_email.strip().lower()
    local, _, domain = email.partition("@")
    if not local or not domain:
        return "the business security mailbox"
    return f"{local[:1]}***@{domain}"


def request_ip(request: Request) -> str | None:
    return trusted_client_ip(request)


def _purpose_label(purpose: str) -> str:
    return "create a new ERP login" if purpose == OTP_REGISTER else "reset an ERP password"


def render_otp_email(
    *, code: str, purpose: str, target_email: str, ip: str | None, expires_minutes: int
) -> tuple[str, str, str]:
    action = _purpose_label(purpose)
    subject = "D Company ERP security approval code"
    safe_target = escape(target_email)
    safe_ip = escape(ip or "unknown")
    html = f"""\
    <div style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:560px;margin:auto;color:#151515">
      <h2 style="margin-bottom:8px">D Company ERP security approval</h2>
      <p>A request was made to {escape(action)} for <strong>{safe_target}</strong>.</p>
      <div style="font-size:32px;font-weight:800;letter-spacing:8px;padding:18px 20px;background:#f4f1e8;border-radius:8px;text-align:center">{code}</div>
      <p>This single-use code expires in {expires_minutes} minutes. Request IP: {safe_ip}.</p>
      <p style="color:#8a2d2d">Only share this code when you approve the named account action. D Company ERP will never ask for your mailbox password.</p>
    </div>"""
    text = (
        f"D Company ERP approval code: {code}\n\n"
        f"Action: {action}\nAccount: {target_email}\n"
        f"Expires in: {expires_minutes} minutes\nRequest IP: {ip or 'unknown'}\n\n"
        "Only share this code when you approve this account action."
    )
    return subject, html, text


async def create_challenge(
    session: AsyncSession,
    request: Request,
    *,
    purpose: str,
    company_id: UUID,
    target_email: str,
    target_user_id: UUID | None,
    requested_by_user_id: UUID | None = None,
    pending_name: str | None = None,
    pending_phone: str | None = None,
    pending_role_code: str | None = None,
    pending_password_hash: str | None = None,
) -> AuthOtpChallenge:
    if purpose not in OTP_PURPOSES:
        raise ValueError(f"unsupported OTP purpose: {purpose}")

    settings = get_settings()
    mailer = Mailer()
    try:
        security_email = normalize_account_email(settings.account_security_email)
    except BusinessRuleError as exc:
        raise ServiceUnavailableError(
            "security email delivery is not configured; contact the protected owner"
        ) from exc
    if not mailer.configured:
        raise ServiceUnavailableError(
            "security email delivery is not configured; contact the protected owner"
        )

    now = datetime.now(UTC)
    ip = request_ip(request)
    cutoff = now - timedelta(minutes=10)
    target_count = await session.scalar(
        select(func.count(AuthOtpChallenge.id)).where(
            AuthOtpChallenge.purpose == purpose,
            AuthOtpChallenge.target_email == target_email,
            AuthOtpChallenge.created_at >= cutoff,
        )
    )
    ip_count = 0
    if ip:
        ip_count = await session.scalar(
            select(func.count(AuthOtpChallenge.id)).where(
                AuthOtpChallenge.requested_ip == ip,
                AuthOtpChallenge.created_at >= cutoff,
            )
        )
    if int(target_count or 0) >= settings.account_otp_request_limit or int(ip_count or 0) >= (
        settings.account_otp_request_limit * 3
    ):
        raise RateLimitError("too many security-code requests; wait 10 minutes and try again")

    await session.execute(
        delete(AuthOtpChallenge).where(AuthOtpChallenge.created_at < now - timedelta(days=1))
    )
    active = (
        (
            await session.execute(
                select(AuthOtpChallenge).where(
                    AuthOtpChallenge.company_id == company_id,
                    AuthOtpChallenge.purpose == purpose,
                    AuthOtpChallenge.target_email == target_email,
                    AuthOtpChallenge.consumed_at.is_(None),
                )
            )
        )
        .scalars()
        .all()
    )
    for old in active:
        old.consumed_at = now

    challenge = AuthOtpChallenge(
        company_id=company_id,
        purpose=purpose,
        target_email=target_email,
        target_user_id=target_user_id,
        code_hash="pending",
        expires_at=now + timedelta(minutes=settings.account_otp_ttl_minutes),
        failed_attempts=0,
        requested_by_user_id=requested_by_user_id,
        requested_ip=ip,
        request_user_agent=audit_user_agent(request),
        pending_name=pending_name,
        pending_phone=pending_phone,
        pending_role_code=pending_role_code,
        pending_password_hash=pending_password_hash,
    )
    session.add(challenge)
    await session.flush()
    code = generate_otp()
    challenge.code_hash = otp_hash(challenge.id, code)
    session.add(
        AuditLog(
            actor_user_id=requested_by_user_id,
            company_id=company_id,
            action="security_otp_requested",
            entity_type="AuthAccess",
            entity_id=str(challenge.id),
            before=None,
            after={"purpose": purpose, "target_email": target_email},
            ip=ip,
            user_agent=audit_user_agent(request),
        )
    )
    # Persist before the external email side effect so a delivered code is always verifiable.
    await session.commit()

    subject, html, text = render_otp_email(
        code=code,
        purpose=purpose,
        target_email=target_email,
        ip=ip,
        expires_minutes=settings.account_otp_ttl_minutes,
    )
    try:
        sent = await asyncio.to_thread(
            mailer.send,
            security_email,
            subject,
            html,
            text,
        )
    except Exception as exc:
        sent = False
        send_error = exc
    else:
        send_error = None

    if not sent:
        challenge.consumed_at = datetime.now(UTC)
        await session.commit()
        raise ServiceUnavailableError(
            "the security code could not be delivered; contact the protected owner"
        ) from send_error
    return challenge


async def consume_challenge(
    session: AsyncSession,
    *,
    challenge_id: UUID,
    code: str,
    purpose: str,
) -> AuthOtpChallenge:
    challenge = (
        await session.execute(
            select(AuthOtpChallenge).where(AuthOtpChallenge.id == challenge_id).with_for_update()
        )
    ).scalar_one_or_none()
    settings = get_settings()
    now = datetime.now(UTC)
    invalid = (
        challenge is None
        or challenge.purpose != purpose
        or challenge.consumed_at is not None
        or challenge.expires_at <= now
        or challenge.failed_attempts >= settings.account_otp_max_attempts
    )
    if invalid:
        raise BusinessRuleError("invalid or expired approval code")
    if not otp_matches(challenge.id, code.strip(), challenge.code_hash):
        challenge.failed_attempts += 1
        if challenge.failed_attempts >= settings.account_otp_max_attempts:
            challenge.consumed_at = now
        await session.commit()
        raise BusinessRuleError("invalid or expired approval code")
    challenge.consumed_at = now
    return challenge
