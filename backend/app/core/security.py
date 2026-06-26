"""Password hashing + JWT issuance/verification.

Argon2id for passwords (memory-hard, modern). JWT for session tokens.
In production use RS256 with a key from a secret manager.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

from jose import JWTError, jwt
from passlib.context import CryptContext

from app.core.config import get_settings

_pwd = CryptContext(schemes=["argon2"], deprecated="auto")


def hash_password(plain: str) -> str:
    return _pwd.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    return _pwd.verify(plain, hashed)


def _signing_key() -> str:
    s = get_settings()
    if s.jwt_algorithm == "RS256":
        if not s.jwt_private_key:
            raise RuntimeError("jwt_private_key must be set when jwt_algorithm=RS256")
        return s.jwt_private_key
    return s.jwt_secret


def _verifying_key() -> str:
    s = get_settings()
    if s.jwt_algorithm == "RS256":
        if not s.jwt_public_key:
            raise RuntimeError("jwt_public_key must be set when jwt_algorithm=RS256")
        return s.jwt_public_key
    return s.jwt_secret


def issue_access_token(
    *,
    user_id: UUID,
    company_id: UUID,
    roles: list[str],
    branch_id: UUID | None = None,
    extra: dict[str, Any] | None = None,
) -> str:
    s = get_settings()
    now = datetime.now(timezone.utc)
    payload: dict[str, Any] = {
        "sub": str(user_id),
        "company_id": str(company_id),
        "branch_id": str(branch_id) if branch_id else None,
        "roles": roles,
        "type": "access",
        "iat": now,
        "exp": now + timedelta(minutes=s.access_token_minutes),
    }
    if extra:
        payload.update(extra)
    return jwt.encode(payload, _signing_key(), algorithm=s.jwt_algorithm)


def issue_refresh_token(*, user_id: UUID, jti: str) -> str:
    s = get_settings()
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user_id),
        "jti": jti,
        "type": "refresh",
        "iat": now,
        "exp": now + timedelta(days=s.refresh_token_days),
    }
    return jwt.encode(payload, _signing_key(), algorithm=s.jwt_algorithm)


def issue_audit_token(*, user_id: UUID, company_id: UUID, minutes: int = 10) -> str:
    """Short-lived token proving the user re-entered their password for Audit."""
    s = get_settings()
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user_id),
        "company_id": str(company_id),
        "type": "audit",
        "scope": "admin.audit.read",
        "iat": now,
        "exp": now + timedelta(minutes=minutes),
    }
    return jwt.encode(payload, _signing_key(), algorithm=s.jwt_algorithm)


def issue_pricing_token(*, user_id: UUID, company_id: UUID, minutes: int = 10) -> str:
    """Short-lived token proving the user re-entered their password for Pricing."""
    s = get_settings()
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user_id),
        "company_id": str(company_id),
        "type": "pricing",
        "scope": "admin.pricing.write",
        "iat": now,
        "exp": now + timedelta(minutes=minutes),
    }
    return jwt.encode(payload, _signing_key(), algorithm=s.jwt_algorithm)


def decode_token(token: str) -> dict[str, Any]:
    s = get_settings()
    try:
        return jwt.decode(token, _verifying_key(), algorithms=[s.jwt_algorithm])
    except JWTError as exc:
        raise ValueError(f"invalid token: {exc}") from exc
