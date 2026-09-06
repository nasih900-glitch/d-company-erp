"""Role-preserving emergency reset for the configured bootstrap owner.

The normal recovery path is the centrally approved OTP password reset.  This
local-console fallback exists for a lost-owner emergency on the production VM.
It deliberately has no email or password command-line argument: the target is
``SEED_OWNER_EMAIL`` and the secret is read without terminal echo (or from
stdin for controlled automation/tests).
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import os
import sys
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import select, update

from app.core.config import get_settings
from app.core.db import AsyncSessionLocal
from app.core.errors import BusinessRuleError
from app.core.security import hash_password
from app.models import AuditLog, AuthRefreshSession, Role, User, UserRole
from app.services.auth.otp import normalize_account_email

_MAX_PASSWORD_LENGTH = 256


@dataclass(frozen=True, slots=True)
class ResetOutcome:
    email: str
    roles: tuple[str, ...]
    revoked_refresh_sessions: int


def _configured_owner_email() -> str:
    configured = os.getenv("SEED_OWNER_EMAIL")
    if not configured:
        raise SystemExit("SEED_OWNER_EMAIL is required for protected-owner recovery.")
    try:
        normalized = normalize_account_email(configured)
    except BusinessRuleError as exc:
        raise SystemExit("SEED_OWNER_EMAIL is not a valid login identity.") from exc
    if normalized != configured:
        raise SystemExit("SEED_OWNER_EMAIL must already be normalized.")
    return normalized


def _validate_password(password: str) -> str:
    minimum = get_settings().password_min_length
    if not minimum <= len(password) <= _MAX_PASSWORD_LENGTH:
        raise SystemExit(
            f"New password must contain {minimum} to {_MAX_PASSWORD_LENGTH} characters."
        )
    return password


def _read_password(*, password_stdin: bool) -> str:
    if password_stdin:
        value = sys.stdin.readline()
        if not value:
            raise SystemExit("No password was received on stdin.")
        return _validate_password(value.rstrip("\r\n"))
    if not sys.stdin.isatty():
        raise SystemExit("Interactive reset requires a TTY; use --password-stdin for automation.")
    first = getpass.getpass("New protected-owner password: ")
    second = getpass.getpass("Confirm new protected-owner password: ")
    if first != second:
        raise SystemExit("Password confirmation did not match.")
    return _validate_password(first)


async def reset_owner_password(password: str) -> ResetOutcome:
    """Reset only the configured active super owner and preserve every role."""

    email = _configured_owner_email()
    password = _validate_password(password)
    async with AsyncSessionLocal() as session:
        owners = (
            await session.execute(
                select(User)
                .where(User.email == email, User.deleted_at.is_(None))
                .with_for_update()
            )
        ).scalars().all()
        if len(owners) != 1:
            raise SystemExit(
                "Configured protected owner was not found uniquely; stop and escalate."
            )
        owner = owners[0]
        if owner.status != "active":
            raise SystemExit("Configured protected owner is not active; stop and escalate.")

        roles = tuple(
            (
                await session.execute(
                    select(Role.code)
                    .join(UserRole, UserRole.role_id == Role.id)
                    .where(
                        UserRole.user_id == owner.id,
                        Role.company_id == owner.company_id,
                    )
                    .order_by(Role.code)
                )
            ).scalars()
        )
        if "super_owner" not in roles:
            raise SystemExit(
                "Configured account is not the protected super owner; no changes were made."
            )

        now = datetime.now(UTC)
        owner.password_hash = hash_password(password)
        owner.failed_login_count = 0
        owner.locked_until = None
        owner.auth_version = (owner.auth_version or 0) + 1
        revoked = await session.execute(
            update(AuthRefreshSession)
            .where(
                AuthRefreshSession.company_id == owner.company_id,
                AuthRefreshSession.user_id == owner.id,
                AuthRefreshSession.revoked_at.is_(None),
            )
            # The refresh-session ledger's closed reason set intentionally
            # models any deliberate credential invalidation as logout. The
            # adjacent AuditLog row records the more specific console-reset
            # mechanism without weakening the downgrade contract.
            .values(revoked_at=now, revocation_reason="logout")
        )
        session.add(
            AuditLog(
                actor_user_id=None,
                company_id=owner.company_id,
                action="protected_owner_password_reset_console",
                entity_type="User",
                entity_id=str(owner.id),
                before=None,
                after={
                    "email": owner.email,
                    "mechanism": "local_console_role_preserving",
                    "roles_preserved": list(roles),
                    "sessions_invalidated": True,
                },
                reason="emergency_owner_password_recovery",
            )
        )
        await session.commit()
        return ResetOutcome(
            email=email,
            roles=roles,
            revoked_refresh_sessions=max(0, int(revoked.rowcount or 0)),
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Reset SEED_OWNER_EMAIL without changing protected role bindings."
    )
    parser.add_argument(
        "--password-stdin",
        action="store_true",
        help="read one password line from stdin instead of an interactive no-echo prompt",
    )
    args = parser.parse_args()
    outcome = asyncio.run(
        reset_owner_password(_read_password(password_stdin=args.password_stdin))
    )
    print(f"Protected owner password reset for {outcome.email}.")
    print(f"Roles preserved: {', '.join(outcome.roles)}")
    print(f"Refresh sessions revoked: {outcome.revoked_refresh_sessions}")
    print("All prior access tokens were invalidated through auth_version rotation.")


if __name__ == "__main__":
    main()
