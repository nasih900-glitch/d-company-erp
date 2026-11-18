"""Cold, exact-command read/retirement authority. Never grants operational access."""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

from sqlalchemy import select

from app.core.errors import AuthError, IdempotencyConflict
from app.core.permissions import require_permission
from app.core.roles import public_roles
from app.core.security import decode_token, issue_paid_bill_recovery_token
from app.core.tenant import TenantContext
from app.models import (
    AuditLog,
    Branch,
    Company,
    Order,
    PaidBillActionDecision,
    PaidBillRecoverySession,
    Role,
    Terminal,
    User,
    UserRole,
)
from app.schemas.paid_bill_recovery import PURPOSE
from app.services.audit.recorder import set_actor
from app.services.auth.credentials import password_failure
from app.services.auth.rate_limit import enforce_login_rate_limit
from app.services.pos import paid_bill_action_decisions as decisions
from app.services.pos import paid_bill_amendments as amendments

DENIED = (
    "Historical bill recovery could not be authenticated. Check the original account and password."
)


class PasswordDeniedError(AuthError):
    """Only failed password counters/safe auth audit may commit before this response."""


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def transport(request):
    if (
        any(
            name in request.headers
            for name in (
                "Authorization",
                "X-Terminal-Id",
                "Cookie",
                "X-Offline-Captured",
            )
        )
        or request.query_params
    ):
        raise AuthError(DENIED)


def audit(session, row, action):
    # The credential table is deliberately not TRACKED: no digest snapshots.
    session.add(
        AuditLog(
            actor_user_id=row.user_id,
            company_id=row.company_id,
            action="paid_bill_recovery_" + action,
            entity_type="PaidBillRecoverySession",
            entity_id=str(row.id),
            before=None,
            after={"purpose": PURPOSE},
        )
    )


async def actor(session, user_id, company_id, *, mutable=False):
    user = await session.scalar(
        select(User)
        .where(
            User.id == user_id,
            User.company_id == company_id,
        )
        .with_for_update(read=not mutable, key_share=mutable)
        .execution_options(populate_existing=True)
    )
    if user is None:
        raise AuthError(DENIED)
    return user


async def historical_authority(session, user, branch_id, terminal_id, order_id):
    now = datetime.now(UTC)
    if user.deleted_at or user.status != "active" or user.locked_until and user.locked_until > now:
        raise AuthError(DENIED)
    company = await session.scalar(
        select(Company.id).where(
            Company.id == user.company_id,
            Company.deleted_at.is_(None),
        )
    )
    if company is None:
        raise AuthError(DENIED)
    rows = (
        await session.execute(
            select(
                Role.code,
                Role.company_id,
                UserRole.branch_id,
                Branch.company_id.label("branch_company"),
            )
            .join(UserRole, UserRole.role_id == Role.id)
            .outerjoin(Branch, Branch.id == UserRole.branch_id)
            .where(UserRole.user_id == user.id)
        )
    ).all()
    if (
        not rows
        or not any(r.code == "super_owner" for r in rows)
        or any(
            r.company_id != user.company_id
            or r.branch_id is not None
            and (r.branch_id != branch_id or r.branch_company != user.company_id)
            for r in rows
        )
    ):
        raise AuthError(DENIED)
    original = await session.scalar(
        select(Order.id)
        .join(
            Terminal,
            Terminal.id == Order.terminal_id,
        )
        .join(Branch, Branch.id == Terminal.branch_id)
        .where(
            Order.id == order_id,
            Order.company_id == user.company_id,
            Order.branch_id == branch_id,
            Order.terminal_id == terminal_id,
            Branch.id == branch_id,
            Branch.company_id == user.company_id,
            Order.source_integrity_revision == 48,
            Order.status.in_(["paid", "refunded"]),
            Order.invoice_no.is_not(None),
            Order.invoice_no != "",
            Order.invoice_issued_at.is_not(None),
            Order.closed_at.is_not(None),
        )
    )
    if original is None:
        raise AuthError(DENIED)
    tenant = TenantContext(
        user_id=user.id,
        company_id=user.company_id,
        branch_id=branch_id,
        terminal_id=terminal_id,
        roles=tuple(public_roles(r.code for r in rows)),
        protected_access=True,
        audit_access=True,
    )
    await require_permission(session, tenant, "finance.receipt.correct")
    set_actor(user_id=user.id, company_id=user.company_id, actor_kind="paid_bill_recovery")
    return tenant


async def exact_decision(session, tenant, key, order_id, command_hash, body=None):
    row = await session.scalar(
        select(PaidBillActionDecision).where(
            PaidBillActionDecision.company_id == tenant.company_id,
            PaidBillActionDecision.action_key == key,
        )
    )
    if row is not None and (
        row.recorded_by != tenant.user_id
        or row.branch_id != tenant.branch_id
        or row.terminal_id != tenant.terminal_id
        or row.order_id != order_id
        or row.request_path != amendments.PATH
        or row.request_hash != command_hash
        or body is not None
        and row.request_body != body
    ):
        raise IdempotencyConflict("Keep the exact original saved command and account.")
    return row


def claims(row):
    return {
        "type": "paid_bill_recovery",
        "purpose": PURPOSE,
        "sid": str(row.id),
        "family_id": str(row.id),
        "sub": str(row.user_id),
        "company_id": str(row.company_id),
        "branch_id": str(row.branch_id),
        "terminal_id": str(row.terminal_id),
        "order_id": str(row.order_id),
        "request_path": row.request_path,
        "action_key_hash": row.action_key_hash,
        "command_hash": row.command_hash,
        "auth_version": row.auth_version,
        "iat": int(row.issued_at.timestamp()),
        "exp": int(row.expires_at.timestamp()),
    }


def decoded(token):
    try:
        payload = decode_token(token)
        if payload.get("type") != "paid_bill_recovery" or payload.get("purpose") != PURPOSE:
            raise ValueError
        UUID(payload["sid"])
        UUID(payload["sub"])
        UUID(payload["company_id"])
        return payload
    except (ValueError, TypeError, KeyError) as exc:
        raise AuthError(DENIED) from exc


async def revoke(session, row, reason):
    if row.revoked_at is None:
        row.revoked_at, row.revocation_reason = datetime.now(UTC), reason
        audit(session, row, reason)


async def unlock(session, request, payload, key):
    transport(request)
    decisions.validate_key(key)
    await decisions.require_read_committed(session)
    await enforce_login_rate_limit(
        request, f"paid-recovery:{payload.company_id}:{payload.actor_user_id}"
    )
    user = await actor(session, payload.actor_user_id, payload.company_id, mutable=True)
    failure = password_failure(user, payload.password.get_secret_value())
    if failure:
        session.add(
            AuditLog(
                actor_user_id=None,
                company_id=user.company_id,
                action="paid_bill_recovery_unlock_failed",
                entity_type="User",
                entity_id=str(user.id),
                before=None,
                after={"result": "denied"},
            )
        )
        raise PasswordDeniedError(DENIED)
    tenant = await historical_authority(
        session, user, payload.branch_id, payload.terminal_id, payload.order_id
    )
    body = payload.original_command.model_dump(mode="json")
    command_hash = await amendments._digest(session, body)
    await exact_decision(session, tenant, key, payload.order_id, command_hash, body)
    key_hash = digest(key)
    previous = list(
        await session.scalars(
            select(PaidBillRecoverySession)
            .where(
                PaidBillRecoverySession.company_id == user.company_id,
                PaidBillRecoverySession.user_id == user.id,
                PaidBillRecoverySession.action_key_hash == key_hash,
                PaidBillRecoverySession.command_hash == command_hash,
                PaidBillRecoverySession.revoked_at.is_(None),
            )
            .with_for_update()
        )
    )
    for row in previous:
        await revoke(session, row, "replaced")
    now = datetime.now(UTC).replace(microsecond=0)
    row = PaidBillRecoverySession(
        id=uuid4(),
        company_id=user.company_id,
        user_id=user.id,
        branch_id=payload.branch_id,
        terminal_id=payload.terminal_id,
        order_id=payload.order_id,
        purpose=PURPOSE,
        request_path=amendments.PATH,
        action_key_hash=key_hash,
        command_hash=command_hash,
        auth_version=user.auth_version,
        issued_at=now,
        expires_at=now + timedelta(seconds=300),
        revoked_at=None,
        revocation_reason=None,
    )
    token = issue_paid_bill_recovery_token(claims(row))
    row.token_hash = digest(token)
    session.add(row)
    audit(session, row, "issued")
    await historical_authority(session, user, row.branch_id, row.terminal_id, row.order_id)
    await session.flush()
    return {"protocol": 1, "purpose": PURPOSE, "expires_in_seconds": 300, "token": token}


async def credential(session, request, key, token, *, locking=False):
    transport(request)
    if not token or len(token) > 8192:
        raise AuthError(DENIED)
    payload = decoded(token)
    user = await actor(session, UUID(payload["sub"]), UUID(payload["company_id"]), mutable=locking)
    row = await session.scalar(
        select(PaidBillRecoverySession)
        .where(
            PaidBillRecoverySession.id == UUID(payload["sid"]),
            PaidBillRecoverySession.user_id == user.id,
            PaidBillRecoverySession.company_id == user.company_id,
        )
        .with_for_update(read=not locking)
        .execution_options(populate_existing=True)
    )
    if row is None or payload != claims(row) or row.token_hash != digest(token):
        raise AuthError(DENIED)
    # Lock is access-reducing and can revoke even after role/account removal.
    if locking:
        set_actor(user_id=user.id, company_id=user.company_id, actor_kind="paid_bill_recovery")
        return row, None
    decisions.validate_key(key)
    now = datetime.now(UTC)
    if (
        row.revoked_at is not None
        or row.expires_at <= now
        or row.auth_version != user.auth_version
        or digest(key) != row.action_key_hash
    ):
        raise AuthError(DENIED)
    tenant = await historical_authority(session, user, row.branch_id, row.terminal_id, row.order_id)
    await exact_decision(session, tenant, key, row.order_id, row.command_hash)
    # Time may have advanced while a database lock/read was waiting.
    decoded(token)
    return row, tenant


async def outcome(session, request, key, token):
    await decisions.require_read_committed(session)
    row, tenant = await credential(session, request, key, token)

    async def authorized():
        await credential(session, request, key, token)

    return await decisions.outcome_authorized(session, tenant, key=key, authorized=authorized)


async def cancel(session, request, key, token, payload):
    await decisions.require_read_committed(session)
    row, tenant = await credential(session, request, key, token)
    if (
        payload.original_command.order_id != row.order_id
        or await amendments._digest(
            session,
            payload.original_command.model_dump(mode="json"),
        )
        != row.command_hash
    ):
        raise AuthError(DENIED)

    async def authorized():
        await credential(session, request, key, token)

    return await decisions.cancel_authorized(
        session, tenant, payload, key=key, authorized=authorized
    )


async def lock(session, request, token):
    await decisions.require_read_committed(session)
    row, _ = await credential(session, request, None, token, locking=True)
    await revoke(session, row, "locked")
    await session.flush()
    return {"protocol": 1, "locked": True}
