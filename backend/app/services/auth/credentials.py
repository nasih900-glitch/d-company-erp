"""Shared password/lockout and live account-assignment checks; never issues tokens."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from app.core.config import get_settings
from app.core.errors import AuthError
from app.core.security import verify_password
from app.models import Branch, Role, User, UserRole


def password_failure(user: User, password: str) -> str | None:
    """Caller holds the User write lock and durably records failed attempts."""
    now = datetime.now(UTC)
    if user.deleted_at or user.status != "active":
        return "account_inactive"
    if user.locked_until and user.locked_until > now:
        return "account_locked"
    if not verify_password(password, user.password_hash):
        settings = get_settings()
        user.failed_login_count = (user.failed_login_count or 0) + 1
        if user.failed_login_count >= settings.failed_login_lockout_threshold:
            user.locked_until = now + timedelta(minutes=settings.failed_login_lockout_minutes)
        return "invalid_password"
    user.failed_login_count = 0
    user.locked_until = None
    return None


async def validated_role_assignment(session, user: User):
    """Return live roles and their explicit branch, rejecting corrupt assignments."""
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
    branch_ids = set()
    for row in rows:
        if row.role_company_id != user.company_id:
            raise AuthError(
                "Account role assignment is invalid. Ask the protected owner to correct it."
            )
        if row.branch_id is not None:
            if row.branch_company_id != user.company_id or row.branch_deleted_at is not None:
                raise AuthError(
                    "Account branch assignment is invalid. Ask the protected owner to correct it."
                )
            branch_ids.add(row.branch_id)
    if len(branch_ids) > 1:
        raise AuthError(
            "Account roles are assigned to multiple branches. "
            "Ask the protected owner to select one branch."
        )
    return [row.code for row in rows], next(iter(branch_ids), None)
