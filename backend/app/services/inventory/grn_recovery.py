"""Purpose-only same-actor GRN read/retirement; never receives or reverses stock."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from sqlalchemy import select

from app.core.errors import AuthError, BusinessRuleError, IdempotencyConflict
from app.core.permissions import require_permission
from app.core.roles import has_full_access, public_roles
from app.core.security import decode_token, issue_grn_recovery_token
from app.core.tenant import TenantContext
from app.models import (
    AuditLog,
    Branch,
    Company,
    GrnActionDecision,
    GrnCompletionProof,
    GrnRecoverySession,
    IdempotencyKey,
    Role,
    Terminal,
    User,
    UserRole,
)
from app.schemas.grn_commands import PURPOSE, parse_wire
from app.services.audit.recorder import set_actor
from app.services.auth.credentials import password_failure
from app.services.auth.rate_limit import enforce_login_rate_limit
from app.services.inventory.grn_commands import (
    decision,
    digest,
    key_owner,
    raw_hash,
    require_read_committed,
)

DENIED = (
    "Goods receipt recovery could not be authenticated. Use the original account and saved command."
)


class PasswordDeniedError(AuthError):
    """A normal dependency commit may retain only safe failed-password evidence."""


def transport(request):
    if request.query_params or any(
        k in request.headers
        for k in (
            "Authorization",
            "X-Terminal-Id",
            "Cookie",
            "X-Offline-Captured",
            "X-Pricing-Token",
        )
    ):
        raise AuthError(DENIED)


async def actor(session, user_id, company_id, mutable=False):
    user = await session.scalar(
        select(User)
        .where(User.id == user_id, User.company_id == company_id)
        .with_for_update(read=not mutable, key_share=mutable)
        .execution_options(populate_existing=True)
    )
    if user is None:
        raise AuthError(DENIED)
    return user


async def authority(session, user, branch_id, terminal_id):
    now = datetime.now(UTC)
    if (
        user.status != "active"
        or user.deleted_at
        or user.locked_until
        and user.locked_until > now
        or await session.scalar(
            select(Company.id).where(Company.id == user.company_id, Company.deleted_at.is_(None))
        )
        is None
    ):
        raise AuthError(DENIED)
    branch = await session.scalar(
        select(Branch.id).where(Branch.id == branch_id, Branch.company_id == user.company_id)
    )
    if branch is None:
        raise AuthError(DENIED)
    if (
        terminal_id is not None
        and await session.scalar(
            select(Terminal.id).where(Terminal.id == terminal_id, Terminal.branch_id == branch_id)
        )
        is None
    ):
        raise AuthError(DENIED)
    roles = (
        await session.execute(
            select(Role.code, Role.company_id, UserRole.branch_id)
            .join(UserRole, UserRole.role_id == Role.id)
            .where(UserRole.user_id == user.id)
        )
    ).all()
    if not roles or any(
        r.company_id != user.company_id or r.branch_id is not None and r.branch_id != branch_id
        for r in roles
    ):
        raise AuthError(DENIED)
    tenant = TenantContext(
        user_id=user.id,
        company_id=user.company_id,
        branch_id=branch_id,
        terminal_id=terminal_id,
        roles=tuple(public_roles(r.code for r in roles)),
        protected_access=has_full_access(r.code for r in roles),
        audit_access=False,
    )
    await require_permission(session, tenant, "inventory.write")
    set_actor(user_id=user.id, company_id=user.company_id, actor_kind="grn_recovery")
    return tenant


def audit(session, row, action):
    session.add(
        AuditLog(
            company_id=row.company_id,
            actor_user_id=row.user_id,
            action="grn_recovery_" + action,
            entity_type="GrnRecoverySession",
            entity_id=str(row.id),
            before=None,
            after={"purpose": PURPOSE},
        )
    )


async def revoke(session, row, reason):
    if row.revoked_at is None:
        row.revoked_at, row.revocation_reason = datetime.now(UTC), reason
        audit(session, row, reason)


def claims(row):
    return {
        "type": "grn_recovery",
        "purpose": PURPOSE,
        "sid": str(row.id),
        "family_id": str(row.id),
        "sub": str(row.user_id),
        "company_id": str(row.company_id),
        "branch_id": str(row.branch_id),
        "terminal_id": str(row.terminal_id) if row.terminal_id else None,
        "action_key_hash": row.action_key_hash,
        "request_hash": row.request_hash,
        "auth_version": row.auth_version,
        "iat": int(row.issued_at.timestamp()),
        "exp": int(row.expires_at.timestamp()),
    }


def decode(token):
    try:
        p = decode_token(token)
        if p.get("type") != "grn_recovery" or p.get("purpose") != PURPOSE:
            raise ValueError
        UUID(p["sid"])
        UUID(p["sub"])
        UUID(p["company_id"])
        return p
    except (KeyError, ValueError, TypeError) as exc:
        raise AuthError(DENIED) from exc


async def exact(session, row, key):
    d = await decision(session, key)
    if d is not None and (
        d.company_id != row.company_id
        or d.actor_user_id != row.user_id
        or d.branch_id != row.branch_id
        or d.request_hash != row.request_hash
    ):
        raise IdempotencyConflict("Keep the exact original goods receipt command.")
    if d and d.outcome == "accepted":
        proof = await session.get(GrnCompletionProof, d.id)
        if proof is None or not proof.terminal_known or proof.terminal_id != row.terminal_id:
            raise IdempotencyConflict(
                "Retained legacy transport is unverified; "
                "keep the command for source investigation."
            )
    elif d and d.terminal_id != row.terminal_id:
        raise IdempotencyConflict("Keep the original transport context.")
    return d


def validate_wire(raw):
    try:
        return parse_wire(raw)
    except (ValueError, TypeError, UnicodeError) as exc:
        raise BusinessRuleError("The exact saved GRN wire command is invalid.") from exc


async def unlock(session, request, payload, key):
    transport(request)
    await require_read_committed(session)
    if not key or len(key) > 160 or key != key.strip():
        raise AuthError(DENIED)
    await enforce_login_rate_limit(
        request, f"grn-recovery:{payload.company_id}:{payload.actor_user_id}"
    )
    user = await actor(session, payload.actor_user_id, payload.company_id, mutable=True)
    if password_failure(user, payload.password.get_secret_value()):
        session.add(
            AuditLog(
                company_id=user.company_id,
                actor_user_id=None,
                action="grn_recovery_unlock_failed",
                entity_type="User",
                entity_id=str(user.id),
                before=None,
                after={"result": "denied"},
            )
        )
        raise PasswordDeniedError(DENIED)
    await authority(session, user, payload.branch_id, payload.terminal_id)
    raw = payload.original_wire_json.get_secret_value()
    command_hash = raw_hash(key, raw)
    now = datetime.now(UTC).replace(microsecond=0)
    row = GrnRecoverySession(
        id=uuid4(),
        company_id=user.company_id,
        user_id=user.id,
        branch_id=payload.branch_id,
        terminal_id=payload.terminal_id,
        action_key_hash=digest(key),
        request_hash=command_hash,
        auth_version=user.auth_version,
        issued_at=now,
        expires_at=now + timedelta(seconds=300),
        revoked_at=None,
        revocation_reason=None,
    )
    existing = await exact(session, row, key)
    if existing is None:
        key_owner(key, payload.company_id, payload.actor_user_id)
        validate_wire(raw)
    for old in await session.scalars(
        select(GrnRecoverySession)
        .where(
            GrnRecoverySession.company_id == user.company_id,
            GrnRecoverySession.user_id == user.id,
            GrnRecoverySession.action_key_hash == row.action_key_hash,
            GrnRecoverySession.request_hash == command_hash,
            GrnRecoverySession.revoked_at.is_(None),
        )
        .with_for_update()
    ):
        await revoke(session, old, "replaced")
    token = issue_grn_recovery_token(claims(row))
    row.token_hash = digest(token)
    session.add(row)
    audit(session, row, "issued")
    await authority(session, user, row.branch_id, row.terminal_id)
    await session.flush()
    return {"protocol": 1, "purpose": PURPOSE, "token": token, "expires_in_seconds": 300}


async def credential(session, request, key, token, locking=False):
    transport(request)
    await require_read_committed(session)
    if not token or len(token) > 8192:
        raise AuthError(DENIED)
    p = decode(token)
    user = await actor(session, UUID(p["sub"]), UUID(p["company_id"]), mutable=locking)
    row = await session.scalar(
        select(GrnRecoverySession)
        .where(
            GrnRecoverySession.id == UUID(p["sid"]),
            GrnRecoverySession.user_id == user.id,
            GrnRecoverySession.company_id == user.company_id,
        )
        .with_for_update(read=not locking)
        .execution_options(populate_existing=True)
    )
    if row is None or p != claims(row) or row.token_hash != digest(token):
        raise AuthError(DENIED)
    if locking:
        set_actor(user_id=user.id, company_id=user.company_id, actor_kind="grn_recovery")
        return row
    if (
        row.revoked_at
        or row.expires_at <= datetime.now(UTC)
        or row.auth_version != user.auth_version
        or digest(key) != row.action_key_hash
    ):
        raise AuthError(DENIED)
    if not key or len(key) > 160 or key != key.strip():
        raise AuthError(DENIED)
    await authority(session, user, row.branch_id, row.terminal_id)
    decode(token)
    return row


def reference(row, key):
    return {
        "action_key": key,
        "company_id": str(row.company_id),
        "actor_user_id": str(row.user_id),
        "branch_id": str(row.branch_id),
        "terminal_id": str(row.terminal_id) if row.terminal_id else None,
        "terminal_known": True,
        "request_method": "POST",
        "request_path": "/api/v1/inventory/grn",
        "request_query": "",
        "request_hash": row.request_hash,
    }


async def present(session, request, key, token, row, d):
    await credential(session, request, key, token)
    ref = reference(row, key)
    if d is None:
        return {"protocol": 1, "outcome": "unconfirmed", "reference": ref}
    if d.outcome == "accepted":
        p = await session.get(GrnCompletionProof, d.id)
        return {
            "protocol": 1,
            "outcome": "accepted",
            "reference": ref,
            "proof_profile": p.profile,
            "accepted": p.response,
        }
    return {
        "protocol": 1,
        "outcome": "cancelled",
        "reference": ref,
        "cancelled": {
            "id": str(d.id),
            "decided_at": d.decided_at,
            "cancellation_reason": d.cancellation_reason,
            "financial_effect": False,
            "stock_effect": False,
        },
    }


async def outcome(session, request, key, token):
    row = await credential(session, request, key, token)
    d = await exact(session, row, key)
    return await present(session, request, key, token, row, d)


async def cancel(session, request, key, token, payload):
    row = await credential(session, request, key, token)
    raw = payload.original_wire_json.get_secret_value()
    if raw_hash(key, raw) != row.request_hash:
        raise IdempotencyConflict("Keep the exact original wire body.")
    d = await exact(session, row, key)
    if d is not None:
        if d.outcome == "cancelled" and d.cancellation_reason != payload.cancellation_reason:
            raise IdempotencyConflict("Keep the exact saved cancellation reason.")
        return await present(session, request, key, token, row, d)
    key_owner(key, row.company_id, row.user_id)
    validate_wire(raw)
    cache = await session.get(IdempotencyKey, key)
    if cache and (
        cache.request_hash != row.request_hash
        or cache.user_id != row.user_id
        or cache.terminal_id != row.terminal_id
        or cache.response_status is not None
    ):
        raise IdempotencyConflict(
            "Retained receipt evidence is unconfirmed; keep the original command."
        )
    await credential(session, request, key, token)
    d = GrnActionDecision(
        id=uuid4(),
        action_key=key,
        company_id=row.company_id,
        branch_id=row.branch_id,
        actor_user_id=row.user_id,
        terminal_id=row.terminal_id,
        terminal_known=True,
        request_hash=row.request_hash,
        outcome="cancelled",
        accepted_grn_id=None,
        original_wire_json=raw,
        cancellation_reason=payload.cancellation_reason,
        recovery_session_id=row.id,
        decided_at=datetime.now(UTC),
    )
    session.add(d)
    await session.flush()
    await credential(session, request, key, token)
    return await present(session, request, key, token, row, d)


async def lock(session, request, token):
    row = await credential(session, request, None, token, locking=True)
    await revoke(session, row, "locked")
    await session.flush()
    return {"protocol": 1, "locked": True}
