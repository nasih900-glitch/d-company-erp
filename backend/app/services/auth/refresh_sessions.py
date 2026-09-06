"""Single-use refresh-token rotation and family revocation.

Refresh JWTs prove authenticity and expiry; this ledger supplies the missing
server-side state needed to reject replay and perform a real logout.  Only a
SHA-256 digest of each credential is stored.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any
from uuid import NAMESPACE_URL, UUID, uuid5

from sqlalchemy import exists, select, update

from app.core.errors import AuthError
from app.models import AuthRefreshSession, User

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


class RefreshTokenReuseDetectedError(Exception):
    """The caller replayed a consumed credential; family revocation must commit."""


@dataclass(frozen=True, slots=True)
class ConsumedRefreshSession:
    user: User
    family_id: UUID


def refresh_token_hash(token: str) -> str:
    """Return a non-reversible lookup digest for a high-entropy credential."""

    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def refresh_family_id(claims: dict[str, Any]) -> UUID | None:
    """Read the rotation-family claim; absence denotes a pre-ledger token."""

    raw = claims.get("family_id")
    if raw is None:
        return None
    try:
        return UUID(str(raw))
    except (TypeError, ValueError) as exc:
        raise AuthError("malformed refresh session") from exc


def access_family_id(claims: dict[str, Any]) -> UUID | None:
    """Read the access-token session family used for immediate revocation."""

    raw = claims.get("session_family_id")
    if raw is None:
        # Access tokens issued before the refresh-session migration remain
        # bounded by their short expiry and the user's auth_version.
        return None
    try:
        return UUID(str(raw))
    except (TypeError, ValueError) as exc:
        raise AuthError("malformed access session") from exc


async def access_family_is_active(
    session: AsyncSession,
    *,
    user: User,
    claims: dict[str, Any],
) -> bool:
    """Return whether a new-format access credential still owns a live family.

    REST and realtime authentication both call this after validating the user
    and auth_version.  This closes the otherwise-surprising window where
    logout/replay prevented refresh but left the current access JWT usable.
    """

    family_id = access_family_id(claims)
    if family_id is None:
        return True
    return bool(
        await session.scalar(
            select(
                exists().where(
                    AuthRefreshSession.company_id == user.company_id,
                    AuthRefreshSession.user_id == user.id,
                    AuthRefreshSession.family_id == family_id,
                    AuthRefreshSession.revoked_at.is_(None),
                )
            )
        )
    )


def refresh_expiry(claims: dict[str, Any]) -> datetime:
    raw = claims.get("exp")
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        raise AuthError("malformed token claims")
    try:
        return datetime.fromtimestamp(float(raw), tz=UTC)
    except (OverflowError, OSError, ValueError) as exc:
        raise AuthError("malformed token claims") from exc


def legacy_family_id(token_hash: str) -> UUID:
    """Derive one stable family for exactly one legacy credential exchange."""

    return uuid5(NAMESPACE_URL, f"dcompany-refresh-legacy:{token_hash}")


def register_refresh_session(
    session: AsyncSession,
    *,
    user: User,
    token: str,
    claims: dict[str, Any],
    family_id: UUID,
    legacy_exchange: bool = False,
) -> AuthRefreshSession:
    row = AuthRefreshSession(
        company_id=user.company_id,
        user_id=user.id,
        family_id=family_id,
        token_hash=refresh_token_hash(token),
        auth_version=user.auth_version,
        expires_at=refresh_expiry(claims),
        legacy_exchange=legacy_exchange,
    )
    session.add(row)
    return row


async def _locked_user(
    session: AsyncSession,
    *,
    user_id: UUID,
) -> User:
    user = (
        await session.execute(select(User).where(User.id == user_id).with_for_update())
    ).scalar_one_or_none()
    if not user or user.deleted_at or user.status != "active":
        raise AuthError("user not found")
    return user


async def _revoke_family(
    session: AsyncSession,
    *,
    user: User,
    family_id: UUID,
    reason: str,
) -> None:
    now = datetime.now(UTC)
    await session.execute(
        update(AuthRefreshSession)
        .where(
            AuthRefreshSession.company_id == user.company_id,
            AuthRefreshSession.user_id == user.id,
            AuthRefreshSession.family_id == family_id,
            AuthRefreshSession.revoked_at.is_(None),
        )
        .values(
            revoked_at=now,
            revocation_reason=reason,
            updated_at=now,
        )
    )


async def consume_refresh_session(
    session: AsyncSession,
    *,
    token: str,
    claims: dict[str, Any],
) -> ConsumedRefreshSession:
    """Atomically consume one refresh credential.

    All rotations for a user take the user-row lock first.  This makes the
    pre-ledger bootstrap deterministic and gives password/auth-version updates
    a consistent transaction boundary.  A replay revokes every credential in
    the family before ``RefreshTokenReuseDetectedError`` is raised; the caller must
    commit that revocation before returning 401.
    """

    try:
        user_id = UUID(str(claims["sub"]))
        auth_version = int(claims.get("auth_version", 0))
    except (KeyError, TypeError, ValueError) as exc:
        raise AuthError("malformed token claims") from exc

    user = await _locked_user(session, user_id=user_id)
    if auth_version != user.auth_version:
        raise AuthError("session expired")

    digest = refresh_token_hash(token)
    claimed_family = refresh_family_id(claims)
    row = (
        await session.execute(
            select(AuthRefreshSession)
            .where(
                AuthRefreshSession.company_id == user.company_id,
                AuthRefreshSession.user_id == user.id,
                AuthRefreshSession.token_hash == digest,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()

    if claimed_family is None:
        # Migration bridge: the first request creates and immediately consumes
        # one deterministic legacy-family row.  The user lock prevents two
        # concurrent requests from both taking this path successfully.
        if row is None:
            claimed_family = legacy_family_id(digest)
            row = register_refresh_session(
                session,
                user=user,
                token=token,
                claims=claims,
                family_id=claimed_family,
                legacy_exchange=True,
            )
            await session.flush()
        elif not row.legacy_exchange:
            raise AuthError("refresh session not found")
        else:
            claimed_family = row.family_id
    elif row is None or row.family_id != claimed_family or row.legacy_exchange:
        # New-format tokens are valid only when their login/rotation commit
        # registered the exact digest.  Never silently bootstrap an orphan.
        raise AuthError("refresh session not found")

    if row.revoked_at is not None:
        raise AuthError("refresh session expired; sign in again")
    if row.expires_at <= datetime.now(UTC):
        raise AuthError("refresh session expired; sign in again")
    if row.consumed_at is not None:
        await _revoke_family(
            session,
            user=user,
            family_id=row.family_id,
            reason="reuse_detected",
        )
        raise RefreshTokenReuseDetectedError

    row.consumed_at = datetime.now(UTC)
    return ConsumedRefreshSession(user=user, family_id=row.family_id)


async def revoke_refresh_credential(
    session: AsyncSession,
    *,
    token: str,
    claims: dict[str, Any],
) -> UUID | None:
    """Revoke the family identified by a valid refresh credential.

    A pre-ledger refresh is persisted directly as a revoked legacy family, so
    logout cannot leave that otherwise-stateless credential replayable.
    """

    try:
        user_id = UUID(str(claims["sub"]))
    except (KeyError, TypeError, ValueError):
        return None
    user = await _locked_user(session, user_id=user_id)
    digest = refresh_token_hash(token)
    claimed_family = refresh_family_id(claims)
    legacy_credential = claimed_family is None
    row = (
        await session.execute(
            select(AuthRefreshSession)
            .where(
                AuthRefreshSession.company_id == user.company_id,
                AuthRefreshSession.user_id == user.id,
                AuthRefreshSession.token_hash == digest,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()

    if claimed_family is None:
        if row is None:
            claimed_family = legacy_family_id(digest)
            row = register_refresh_session(
                session,
                user=user,
                token=token,
                claims=claims,
                family_id=claimed_family,
                legacy_exchange=True,
            )
            now = datetime.now(UTC)
            row.revoked_at = now
            row.revocation_reason = "logout"
            # A legacy refresh has no family-linked access claim.  The only
            # immediate revocation boundary for its companion access token is
            # auth_version, matching legacy bearer logout semantics.  This
            # compatibility path naturally disappears after old access tokens
            # expire.
            try:
                credential_auth_version = int(claims.get("auth_version", 0))
            except (TypeError, ValueError):
                credential_auth_version = -1
            if credential_auth_version == user.auth_version:
                user.auth_version += 1
            await session.flush()
            return user.company_id
        claimed_family = row.family_id
    elif row is None or row.family_id != claimed_family:
        return user.company_id

    await _revoke_family(
        session,
        user=user,
        family_id=claimed_family,
        reason="logout",
    )
    if legacy_credential:
        try:
            credential_auth_version = int(claims.get("auth_version", 0))
        except (TypeError, ValueError):
            credential_auth_version = -1
        if credential_auth_version == user.auth_version:
            user.auth_version += 1
    return user.company_id


async def revoke_access_session(
    session: AsyncSession,
    *,
    claims: dict[str, Any],
) -> tuple[UUID | None, bool]:
    """Revoke a new-format access-token family.

    Legacy access JWTs have no family claim.  Their only safe bounded logout
    path is an ``auth_version`` increment, which intentionally signs that user
    out on every device.  This compatibility branch disappears naturally once
    pre-ledger access tokens expire.
    """

    try:
        user_id = UUID(str(claims["sub"]))
        company_id = UUID(str(claims["company_id"]))
        auth_version = int(claims.get("auth_version", 0))
    except (KeyError, TypeError, ValueError):
        return None, False
    user = await _locked_user(session, user_id=user_id)
    if user.company_id != company_id:
        return None, False

    raw_family = claims.get("session_family_id")
    if raw_family is None:
        if auth_version == user.auth_version:
            user.auth_version += 1
            return user.company_id, True
        return user.company_id, False
    try:
        family_id = UUID(str(raw_family))
    except (TypeError, ValueError):
        return None, False

    await _revoke_family(
        session,
        user=user,
        family_id=family_id,
        reason="logout",
    )
    return user.company_id, False
