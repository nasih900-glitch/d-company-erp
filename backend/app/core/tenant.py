"""Tenant context resolved from the JWT for every authenticated request."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Annotated
from uuid import UUID

from fastapi import Depends, Header, Request
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import AuthError, TenantViolation
from app.core.roles import has_protected_owner_access, public_roles
from app.core.security import decode_token
from app.models import Branch, Terminal, User
from app.services.audit.recorder import set_actor


@dataclass(frozen=True, slots=True)
class TenantContext:
    user_id: UUID
    company_id: UUID
    branch_id: UUID | None
    terminal_id: UUID | None
    roles: tuple[str, ...]
    protected_access: bool = False

    def require_role(self, *allowed: str) -> None:
        if not set(self.roles).intersection(allowed):
            raise TenantViolation(
                f"role required: one of {allowed}",
                details={"have": list(self.roles)},
            )

    def in_branch(self, branch_id: UUID) -> bool:
        return self.branch_id is None or self.branch_id == branch_id


async def get_tenant_context(
    request: Request,
    session: SessionDep,
    authorization: Annotated[str | None, Header()] = None,
    x_terminal_id: Annotated[str | None, Header()] = None,
) -> TenantContext:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise AuthError("missing bearer token")
    token = authorization.split(" ", 1)[1]
    try:
        payload = decode_token(token)
    except ValueError as exc:
        raise AuthError(str(exc)) from exc

    if payload.get("type") != "access":
        raise AuthError("not an access token")

    try:
        user_id = UUID(payload["sub"])
        company_id = UUID(payload["company_id"])
        branch_id = UUID(payload["branch_id"]) if payload.get("branch_id") else None
    except (KeyError, ValueError) as exc:
        raise AuthError("malformed token claims") from exc

    terminal_id = UUID(x_terminal_id) if x_terminal_id else None
    raw_roles = list(payload.get("roles", []))
    protected_access = bool(payload.get("protected_access")) or has_protected_owner_access(raw_roles)
    roles = tuple(public_roles(raw_roles))

    user = (
        await session.execute(
            select(User).where(
                User.id == user_id,
                User.company_id == company_id,
            )
        )
    ).scalar_one_or_none()
    if not user or user.deleted_at or user.status != "active":
        raise AuthError("user not found")

    if terminal_id:
        terminal_row = (
            await session.execute(
                select(Terminal, Branch)
                .join(Branch, Branch.id == Terminal.branch_id)
                .where(
                    Terminal.id == terminal_id,
                    Branch.company_id == company_id,
                    Branch.deleted_at.is_(None),
                )
            )
        ).first()
        if not terminal_row:
            raise AuthError("terminal not found")
        terminal_branch_id = terminal_row.Branch.id
        if branch_id and branch_id != terminal_branch_id:
            raise TenantViolation("terminal belongs to a different branch")
        branch_id = terminal_branch_id

    # Tell the audit recorder who is doing this. This stays in a ContextVar
    # for the lifetime of the request — every DB write SQLAlchemy commits
    # will then carry the right actor in the audit_log row.
    set_actor(
        user_id=user_id,
        company_id=company_id,
        ip=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    return TenantContext(
        user_id=user_id,
        company_id=company_id,
        branch_id=branch_id,
        terminal_id=terminal_id,
        roles=roles,
        protected_access=protected_access,
    )


TenantDep = Annotated[TenantContext, Depends(get_tenant_context)]
