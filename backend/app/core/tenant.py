"""Tenant context resolved from the JWT for every authenticated request."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Annotated
from uuid import UUID

from fastapi import Depends, Header, Request
from sqlalchemy import select

from app.core.client_ip import audit_user_agent, trusted_client_ip
from app.core.db import SessionDep
from app.core.errors import AuthError, TenantViolation
from app.core.roles import has_full_access, has_protected_owner_access, public_roles
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
    # Broad operational bypass (shift-opener-only billing, force-stop, etc.) —
    # true for super_owner AND co_owner. NOT the same as audit_access below.
    protected_access: bool = False
    # Narrow: only super_owner. Gates admin.audit.read specifically (see
    # permissions.py's _has_permission) — deliberately not implied by
    # protected_access, so a co_owner can bypass operational RBAC without
    # also getting audit-log / Access Control panel access.
    audit_access: bool = False

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
        auth_version = int(payload.get("auth_version", 0))
    except (KeyError, TypeError, ValueError) as exc:
        raise AuthError("malformed token claims") from exc

    try:
        terminal_id = UUID(x_terminal_id) if x_terminal_id else None
    except (TypeError, ValueError) as exc:
        raise AuthError("malformed terminal id") from exc
    raw_roles = list(payload.get("roles", []))
    protected_access = bool(payload.get("protected_access")) or has_full_access(raw_roles)
    audit_access = bool(payload.get("audit_access")) or has_protected_owner_access(raw_roles)
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
    if auth_version != user.auth_version:
        raise AuthError("session expired")

    # A signed token can outlive a branch being disabled or reveal a legacy
    # cross-tenant role assignment. Validate the branch even when the client
    # does not send a terminal header; terminal-bound requests perform the
    # equivalent check in the joined lookup below.
    if branch_id is not None and terminal_id is None:
        valid_branch_id = (
            await session.execute(
                select(Branch.id).where(
                    Branch.id == branch_id,
                    Branch.company_id == company_id,
                    Branch.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if valid_branch_id is None:
            raise AuthError("branch not found")

    if terminal_id:
        terminal_row = (
            await session.execute(
                select(Terminal, Branch)
                .join(Branch, Branch.id == Terminal.branch_id)
                .where(
                    Terminal.id == terminal_id,
                    Terminal.is_active.is_(True),
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
        terminal_id=terminal_id,
        ip=trusted_client_ip(request),
        user_agent=audit_user_agent(request),
    )

    return TenantContext(
        user_id=user_id,
        company_id=company_id,
        branch_id=branch_id,
        terminal_id=terminal_id,
        roles=roles,
        protected_access=protected_access,
        audit_access=audit_access,
    )


TenantDep = Annotated[TenantContext, Depends(get_tenant_context)]
