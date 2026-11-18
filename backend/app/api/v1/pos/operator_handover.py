"""Online operator responsibility changes; never rewrites financial authority/history."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Annotated, Literal
from uuid import UUID  # noqa: TC003 - FastAPI runtime schema

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import or_, select
from sqlalchemy.exc import IntegrityError

from app.core.db import SessionDep  # noqa: TC001 - FastAPI runtime dependency
from app.core.errors import (
    AuthError,
    BusinessRuleError,
    ConflictError,
    ForbiddenError,
    NotFoundError,
)
from app.core.idempotency import check_or_reserve, store_response
from app.core.middleware import idempotency_request_hash
from app.core.permissions import has_permission, requires, requires_any
from app.core.roles import has_full_access, has_owner_role, has_protected_owner_access, public_roles
from app.core.tenant import TenantContext
from app.models import (
    AuditLog,
    GamingSession,
    Order,
    OrderCheckoutClaim,
    Role,
    Shift,
    ShiftOperatorHandover,
    User,
    UserRole,
)
from app.services.auth.credentials import password_failure, validated_role_assignment
from app.services.auth.rate_limit import enforce_login_rate_limit
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_operational_shift_scope,
)

router = APIRouter()
OperatorTenant = Annotated[TenantContext, Depends(requires("pos.write"))]
RecoveryTenant = Annotated[TenantContext, Depends(requires("pos.shift.handover.recover"))]
OperatorReadTenant = Annotated[
    TenantContext, Depends(requires_any("pos.write", "pos.shift.handover.recover"))
]


class HandoverWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    incoming_user_id: UUID
    password: SecretStr = Field(min_length=1, max_length=256)
    expected_revision: int = Field(strict=True, ge=0, le=2_147_483_646)
    approved: bool = Field(strict=True)


class OwnerRecoveryWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    incoming_user_id: UUID
    expected_revision: int = Field(strict=True, ge=1, le=2_147_483_646)
    approved: bool = Field(strict=True)
    reason: str = Field(min_length=12, max_length=500)
    resolves_handover_id: UUID


class HandoverRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    shift_id: UUID
    branch_id: UUID
    terminal_id: UUID
    initiated_by_user_id: UUID
    previous_operator_user_id: UUID | None
    incoming_user_id: UUID
    from_revision: int
    to_revision: int
    cash_count_status: Literal["NOT_COUNTED"]
    counted_cash_minor: None
    handover_kind: Literal["handover", "owner_recovery"]
    reason: str | None
    resolves_handover_id: UUID | None
    created_at: datetime


class OperatorCandidateRead(BaseModel):
    id: UUID
    name: str


class OperatorStateRead(BaseModel):
    shift_id: UUID
    shift_status: str
    operator_user_id: UUID | None
    operator_name: str | None
    revision: int
    tracking: Literal["untracked", "recorded"]
    own_active_checkout_order_ids: list[UUID]
    latest_handover: HandoverRead | None
    has_running_games: bool
    recovery_target: OperatorCandidateRead | None


class OperatorCandidatesRead(BaseModel):
    items: list[OperatorCandidateRead]
    next_cursor: UUID | None


def _workspace(tenant):
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise ForbiddenError("Select this tablet's branch and terminal before a handover.")


async def _shift(session, tenant, shift_id, *, lock=False, open_only=False):
    _workspace(tenant)
    query = select(Shift).where(Shift.id == shift_id, Shift.company_id == tenant.company_id)
    if lock:
        query = query.with_for_update().execution_options(populate_existing=True)
    row = (await session.execute(query)).scalar_one_or_none()
    validate = require_open_operational_shift if open_only else require_operational_shift_scope
    return validate(
        row,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="changing the till operator",
    )


async def _own_leases(session, tenant, shift_id):
    return list(
        (
            await session.execute(
                select(OrderCheckoutClaim.order_id)
                .join(Order, Order.id == OrderCheckoutClaim.order_id)
                .where(
                    Order.company_id == tenant.company_id,
                    Order.shift_id == shift_id,
                    OrderCheckoutClaim.company_id == tenant.company_id,
                    OrderCheckoutClaim.branch_id == tenant.branch_id,
                    OrderCheckoutClaim.terminal_id == tenant.terminal_id,
                    OrderCheckoutClaim.claimed_by_user_id == tenant.user_id,
                    OrderCheckoutClaim.expires_at > datetime.now(UTC),
                )
                .order_by(OrderCheckoutClaim.order_id)
            )
        ).scalars()
    )


async def _can_read_receipt(session, tenant, row):
    if tenant.user_id in (row.initiated_by_user_id, row.incoming_user_id):
        return True
    return has_owner_role(tenant.roles) and await has_permission(
        session, tenant, "pos.shift.handover.recover"
    )


@router.get("/shifts/{shift_id}/operator-state", response_model=OperatorStateRead)
async def operator_state(shift_id: UUID, session: SessionDep, tenant: OperatorReadTenant):
    shift = await _shift(session, tenant, shift_id)
    name = None
    if shift.operator_user_id is not None:
        name = (
            await session.execute(
                select(User.name).where(
                    User.id == shift.operator_user_id, User.company_id == tenant.company_id
                )
            )
        ).scalar_one_or_none()
    latest = (
        (
            await session.execute(
                select(ShiftOperatorHandover).where(
                    ShiftOperatorHandover.company_id == tenant.company_id,
                    ShiftOperatorHandover.shift_id == shift.id,
                    ShiftOperatorHandover.to_revision == shift.operator_revision,
                )
            )
        ).scalar_one_or_none()
        if shift.operator_revision is not None
        else None
    )
    has_running_games = await session.scalar(
        select(
            select(GamingSession.id)
            .where(
                GamingSession.company_id == tenant.company_id,
                GamingSession.shift_id == shift.id,
                GamingSession.status.in_(("active", "paused")),
            )
            .exists()
        )
    )
    recovery_target = None
    if (
        shift.status == "open"
        and latest is not None
        and latest.handover_kind == "handover"
        and latest.incoming_user_id == shift.operator_user_id
        and has_owner_role(tenant.roles)
        and await has_permission(session, tenant, "pos.shift.handover.recover")
    ):
        previous = await session.scalar(
            select(User).where(
                User.company_id == tenant.company_id,
                User.id == latest.initiated_by_user_id,
                User.status == "active",
                User.deleted_at.is_(None),
            )
        )
        if previous is not None:
            try:
                await _qualified_user(session, tenant, previous)
            except (AuthError, ForbiddenError):
                pass
            else:
                recovery_target = OperatorCandidateRead(id=previous.id, name=previous.name)
    return OperatorStateRead(
        shift_id=shift.id,
        shift_status=shift.status,
        operator_user_id=shift.operator_user_id,
        operator_name=name,
        revision=shift.operator_revision or 0,
        tracking="untracked" if shift.operator_revision is None else "recorded",
        own_active_checkout_order_ids=await _own_leases(session, tenant, shift.id),
        has_running_games=bool(has_running_games),
        recovery_target=recovery_target,
        latest_handover=(
            HandoverRead.model_validate(latest)
            if latest and await _can_read_receipt(session, tenant, latest)
            else None
        ),
    )


@router.get("/shifts/{shift_id}/operator-candidates", response_model=OperatorCandidatesRead)
async def operator_candidates(
    shift_id: UUID,
    session: SessionDep,
    tenant: OperatorTenant,
    after_id: UUID | None = None,
    limit: int = Query(default=50, ge=1, le=100),
):
    shift = await _shift(session, tenant, shift_id, open_only=True)
    if shift.operator_user_id is not None and shift.operator_user_id != tenant.user_id:
        raise ForbiddenError("Only the current operator can choose the next operator.")
    assigned = (
        select(UserRole.id)
        .join(Role, Role.id == UserRole.role_id)
        .where(
            UserRole.user_id == User.id,
            Role.company_id == tenant.company_id,
            or_(UserRole.branch_id.is_(None), UserRole.branch_id == tenant.branch_id),
        )
        .exists()
    )
    query = select(User).where(
        User.company_id == tenant.company_id,
        User.id != tenant.user_id,
        User.status == "active",
        User.deleted_at.is_(None),
        assigned,
    )
    if after_id is not None:
        query = query.where(User.id > after_id)
    rows = (await session.execute(query.order_by(User.id).limit(limit + 1))).scalars().all()
    items = []
    for user in rows[:limit]:
        try:
            await _qualified_user(session, tenant, user)
        except (AuthError, ForbiddenError):
            continue
        items.append(OperatorCandidateRead(id=user.id, name=user.name))
    return OperatorCandidatesRead(
        items=items, next_cursor=rows[limit - 1].id if len(rows) > limit else None
    )


@router.get("/shifts/{shift_id}/operator-handover-receipt", response_model=HandoverRead)
async def handover_receipt(
    shift_id: UUID,
    session: SessionDep,
    tenant: OperatorReadTenant,
    action_key: str = Query(min_length=1, max_length=160),
):
    await _shift(session, tenant, shift_id)
    row = (
        await session.execute(
            select(ShiftOperatorHandover).where(
                ShiftOperatorHandover.company_id == tenant.company_id,
                ShiftOperatorHandover.shift_id == shift_id,
                ShiftOperatorHandover.action_key == action_key,
            )
        )
    ).scalar_one_or_none()
    if row is None:
        raise NotFoundError("handover receipt not found")
    if not await _can_read_receipt(session, tenant, row):
        raise ForbiddenError("That handover receipt belongs to another operator.")
    # Historical receipt is not a claim that the shift is still open/current.
    return row


async def _verify_incoming(session, request, caller, payload):
    query = select(User).where(
        User.id == payload.incoming_user_id, User.company_id == caller.company_id
    )

    async def eligible(user):
        if user is None or user.deleted_at is not None or user.status != "active":
            raise AuthError("invalid credentials")
        return await _qualified_user(session, caller, user)

    try:
        user = (await session.execute(query)).scalar_one_or_none()
        await eligible(user)
        user = (
            await session.execute(
                query.with_for_update(key_share=True).execution_options(populate_existing=True)
            )
        ).scalar_one_or_none()
        await eligible(user)
    except (AuthError, ForbiddenError):
        await enforce_login_rate_limit(
            request, f"handover:{caller.company_id}:{payload.incoming_user_id}"
        )
        raise
    await enforce_login_rate_limit(request, user.email)
    failure = password_failure(user, payload.password.get_secret_value())
    if failure is not None:
        session.add(
            AuditLog(
                company_id=caller.company_id,
                actor_user_id=None,
                target_user_id=user.id,
                action="pos.operator_handover.authentication_failed",
                entity_type="User",
                entity_id=str(user.id),
                business_details={"reason": failure, "initiated_by_user_id": str(caller.user_id)},
            )
        )
        await session.commit()
        raise AuthError(
            "account temporarily locked" if failure == "account_locked" else "invalid credentials"
        )
    return user


async def _qualified_user(session, caller, user):
    roles, branch = await validated_role_assignment(session, user)
    if branch is not None and branch != caller.branch_id:
        raise ForbiddenError("The incoming operator is not assigned to this branch.")
    target = TenantContext(
        user.id,
        caller.company_id,
        caller.branch_id,
        caller.terminal_id,
        tuple(public_roles(roles)),
        has_full_access(roles),
        has_protected_owner_access(roles),
    )
    if not await has_permission(session, target, "pos.write"):
        raise ForbiddenError("The incoming operator cannot work at this till.")
    return user


async def _owner_target(session, caller, payload):
    user = (
        await session.execute(
            select(User)
            .where(
                User.id == payload.incoming_user_id,
                User.company_id == caller.company_id,
                User.status == "active",
                User.deleted_at.is_(None),
            )
            .with_for_update(key_share=True)
        )
    ).scalar_one_or_none()
    if user is None:
        raise BusinessRuleError("Choose an active staff account in this company.")
    return await _qualified_user(session, caller, user)


def _require_current_operator(shift, tenant, expected_revision):
    if (shift.operator_revision or 0) != expected_revision:
        raise ConflictError("The till operator changed. Refresh before approving a handover.")
    if shift.operator_user_id is not None and shift.operator_user_id != tenant.user_id:
        raise ForbiddenError("Only the recorded current operator can approve the next handover.")


async def _perform_handover(
    shift_id: UUID,
    payload: HandoverWrite | OwnerRecoveryWrite,
    request: Request,
    session: SessionDep,
    tenant: OperatorTenant,
    *,
    recovery=False,
):
    _workspace(tenant)
    if not payload.approved:
        raise BusinessRuleError("The active operator must explicitly approve this handover.")
    if not recovery and payload.incoming_user_id == tenant.user_id:
        raise BusinessRuleError("Choose a different incoming operator.")
    if request.headers.get("X-Offline-Captured", "").strip().lower() in {"1", "true", "yes"}:
        raise BusinessRuleError("Till handover requires an online credential check.")
    key = getattr(request.state, "idempotency_key", None)
    if not key:
        raise BusinessRuleError("Idempotency-Key is required for a handover.")
    if recovery:
        if not has_owner_role(tenant.roles):
            raise ForbiddenError("Only an Owner can resolve operator responsibility.")
        if len(payload.reason.strip()) < 12:
            raise BusinessRuleError("Explain the operator recovery reason.")
        await _owner_target(session, tenant, payload)
    facts = {
        **payload.model_dump(mode="json", exclude={"password"}),
        "initiated_by_user_id": str(tenant.user_id),
        "company_id": str(tenant.company_id),
        "branch_id": str(tenant.branch_id),
        "terminal_id": str(tenant.terminal_id),
    }
    digest = idempotency_request_hash(
        method=request.method,
        path=request.url.path,
        query=request.url.query,
        key=key,
        body=json.dumps(facts, sort_keys=True, separators=(",", ":")).encode(),
    )
    if not recovery:
        preview = await _shift(session, tenant, shift_id)
        # Only this actor's exact historical action can replay past a changed or closed shift.
        recorded_digest = await session.scalar(
            select(ShiftOperatorHandover.request_hash).where(
                ShiftOperatorHandover.company_id == tenant.company_id,
                ShiftOperatorHandover.shift_id == shift_id,
                ShiftOperatorHandover.branch_id == tenant.branch_id,
                ShiftOperatorHandover.terminal_id == tenant.terminal_id,
                ShiftOperatorHandover.initiated_by_user_id == tenant.user_id,
                ShiftOperatorHandover.action_key == key,
            )
        )
        if recorded_digest is not None and recorded_digest != digest:
            raise ConflictError("This action key belongs to a different handover.")
        if recorded_digest is None:
            if preview.status != "open":
                raise BusinessRuleError("Open this shift before changing its operator.")
            _require_current_operator(preview, tenant, payload.expected_revision)
        await _verify_incoming(session, request, tenant, payload)
    prior = await check_or_reserve(
        session,
        key=key,
        request_hash=digest,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if prior is not None:
        return JSONResponse(prior["body"], status_code=prior["status_code"])
    # Durable history remains authoritative if the ordinary receipt was retained/cleaned separately.
    recorded = (
        await session.execute(
            select(ShiftOperatorHandover).where(
                ShiftOperatorHandover.company_id == tenant.company_id,
                ShiftOperatorHandover.action_key == key,
            )
        )
    ).scalar_one_or_none()
    if recorded is not None:
        if recorded.company_id != tenant.company_id or recorded.request_hash != digest:
            raise ConflictError("This action key belongs to a different handover.")
        result = HandoverRead.model_validate(recorded).model_dump(mode="json")
        await store_response(session, key=key, status_code=201, body=result)
        return result
    shift = await _shift(session, tenant, shift_id, lock=True, open_only=True)
    revision = shift.operator_revision or 0
    if recovery:
        if revision != payload.expected_revision:
            raise ConflictError("The till operator changed. Refresh before approving a handover.")
        linked = (
            await session.execute(
                select(ShiftOperatorHandover.initiated_by_user_id).where(
                    ShiftOperatorHandover.id == payload.resolves_handover_id,
                    ShiftOperatorHandover.company_id == tenant.company_id,
                    ShiftOperatorHandover.shift_id == shift.id,
                    ShiftOperatorHandover.to_revision == revision,
                    ShiftOperatorHandover.handover_kind == "handover",
                    ShiftOperatorHandover.incoming_user_id == shift.operator_user_id,
                )
            )
        ).scalar_one_or_none()
        if linked is None:
            raise ConflictError("Recovery must resolve the current recorded handover.")
        if payload.incoming_user_id != linked:
            raise BusinessRuleError("Return the till to the operator who initiated this handover.")
    else:
        _require_current_operator(shift, tenant, payload.expected_revision)
    # Claim acquire/renew locks Order -> Shift. Do not invert that order by locking Orders here.
    # The client must also freeze and drain ALL in-flight requests; later ordinary POS stays valid.
    leases = await _own_leases(session, tenant, shift.id)
    if leases:
        raise ConflictError(
            "Release your checkout reservations before changing operator.",
            details={"own_checkout_order_ids": [str(id_) for id_ in leases]},
        )
    row = ShiftOperatorHandover(
        company_id=tenant.company_id,
        shift_id=shift.id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        initiated_by_user_id=tenant.user_id,
        previous_operator_user_id=shift.operator_user_id,
        incoming_user_id=payload.incoming_user_id,
        from_revision=revision,
        to_revision=revision + 1,
        action_key=key,
        request_hash=digest,
        handover_kind="owner_recovery" if recovery else "handover",
        reason=payload.reason.strip() if recovery else None,
        resolves_handover_id=payload.resolves_handover_id if recovery else None,
        cash_count_status="NOT_COUNTED",
        counted_cash_minor=None,
    )
    session.add(row)
    try:
        await session.flush()  # DB atomically guards the chain and advances only its pointer.
    except IntegrityError as exc:
        cause = getattr(exc.orig, "__cause__", None)
        state = getattr(exc.orig, "sqlstate", None) or getattr(cause, "sqlstate", None)
        constraint = (
            getattr(getattr(exc.orig, "diag", None), "constraint_name", None)
            or getattr(exc.orig, "constraint_name", None)
            or getattr(cause, "constraint_name", None)
        )
        if state == "23505" and constraint == "uq_shift_operator_action":
            raise ConflictError("This action key belongs to a different handover.") from exc
        raise
    await session.refresh(row)
    await session.refresh(shift)
    result = HandoverRead.model_validate(row).model_dump(mode="json")
    await store_response(session, key=key, status_code=201, body=result)
    return result


@router.post("/shifts/{shift_id}/operator-handovers", response_model=HandoverRead, status_code=201)
async def handover(
    shift_id: UUID,
    payload: HandoverWrite,
    request: Request,
    session: SessionDep,
    tenant: OperatorTenant,
):
    return await _perform_handover(shift_id, payload, request, session, tenant)


@router.post(
    "/shifts/{shift_id}/operator-handovers/recover", response_model=HandoverRead, status_code=201
)
async def recover_operator(
    shift_id: UUID,
    payload: OwnerRecoveryWrite,
    request: Request,
    session: SessionDep,
    tenant: RecoveryTenant,
):
    return await _perform_handover(shift_id, payload, request, session, tenant, recovery=True)
