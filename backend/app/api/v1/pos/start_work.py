"""Online atomic start of a shop shift and the authenticated opener's attendance."""

from __future__ import annotations

import json
from datetime import UTC, datetime  # noqa: TC003 - response schema
from typing import Annotated, Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field, SecretStr
from sqlalchemy import select, text

from app.core.db import SessionDep  # noqa: TC001 - FastAPI runtime dependency
from app.core.errors import AuthError, BusinessRuleError, ConflictError, IdempotencyConflict
from app.core.idempotency import check_or_reserve, store_response
from app.core.middleware import idempotency_request_hash
from app.core.permissions import require_permission, requires
from app.core.roles import has_owner_role
from app.core.tenant import TenantContext, get_tenant_context
from app.models import (
    Attendance,
    AuditLog,
    Branch,
    Shift,
    ShiftStartWorkCancellation,
    ShiftStartWorkReceipt,
    Terminal,
    User,
)
from app.services.audit.events import record_operation
from app.services.audit.recorder import set_actor
from app.services.integrations.google_sheets_staff import enqueue_staff_attendance_if_enabled

router = APIRouter()
StartTenant = Annotated[TenantContext, Depends(requires("pos.shift.open"))]
PATH = "/api/v1/pos/shifts/start-work"


class StartWorkWrite(BaseModel):
    model_config = ConfigDict(extra="forbid")
    opening_float_minor: int = Field(strict=True, ge=0, le=9_223_372_036_854_775_807)


class StartWorkRead(BaseModel):
    receipt_id: UUID
    company_id: UUID
    actor_user_id: UUID
    branch_id: UUID
    terminal_id: UUID
    installation_id: UUID | None
    shift_id: UUID
    shift_status_at_start: Literal["open"]
    opening_float_minor: int
    attendance_id: UUID
    clock_in_at: datetime
    attendance_created: bool
    original_attendance_preserved: bool
    started_at: datetime


class StartWorkStatus(BaseModel):
    state: Literal["committed", "cancelled", "unconfirmed"]
    receipt: StartWorkRead | None = None
    cancellation: dict | None = None
    current_shift: dict | None = None


@router.get("/shifts/start-work/{action_id}", response_model=StartWorkStatus)
async def start_work_status(
    action_id: UUID, request: Request, session: SessionDep, tenant: StartTenant
):
    """Resolve a prior login's outcome without re-dispatching its mutation."""
    await _fresh(request, session, tenant)
    retained = await session.scalar(
        select(ShiftStartWorkReceipt).where(
            ShiftStartWorkReceipt.company_id == tenant.company_id,
            ShiftStartWorkReceipt.action_key == "shift-open:" + str(action_id),
        )
    )
    cancelled = await session.scalar(
        select(ShiftStartWorkCancellation).where(
            ShiftStartWorkCancellation.company_id == tenant.company_id,
            ShiftStartWorkCancellation.action_key == "shift-open:" + str(action_id),
        )
    )
    outcome = retained or cancelled
    if outcome is None:
        return {"state": "unconfirmed"}
    if (
        not tenant.in_branch(outcome.branch_id)
        or outcome.terminal_id != tenant.terminal_id
        or (outcome.actor_user_id != tenant.user_id and not has_owner_role(tenant.roles))
    ):
        raise AuthError("The saved action is outside this signed-in workspace.")
    if outcome.actor_user_id != tenant.user_id:
        await require_permission(session, tenant, "staff.write")
    return await _status(session, retained, cancelled)


async def _status(session, retained, cancelled):
    if retained is not None and cancelled is not None:
        raise ConflictError("The retained outcome needs Owner review. Nothing was changed.")
    if retained is not None:
        shift = await session.get(Shift, retained.shift_id, populate_existing=True)
        if shift is None:
            raise ConflictError("The retained shift needs Owner review.")
        return {
            "state": "committed",
            "receipt": retained.response_body,
            "current_shift": {
                "id": str(shift.id),
                "company_id": str(shift.company_id),
                "branch_id": str(shift.branch_id),
                "terminal_id": str(shift.terminal_id),
                "status": shift.status,
                "opening_client_platform": shift.opening_client_platform,
                "opening_client_installation_id": str(shift.opening_client_installation_id)
                if shift.opening_client_installation_id
                else None,
            },
        }
    if cancelled is not None:
        return {"state": "cancelled", "cancellation": cancelled.response_body}
    return {"state": "unconfirmed"}


async def _fresh(request, session, tenant):
    # The initial dependency may precede a long row-lock wait. Re-decode expiry
    # and re-query family revocation/status/auth_version after that wait.
    user = await session.scalar(
        select(User)
        .where(
            User.id == tenant.user_id,
            User.company_id == tenant.company_id,
        )
        .execution_options(populate_existing=True)
    )
    if user is None:
        raise AuthError("user not found")
    current = await get_tenant_context(
        request,
        session,
        request.headers.get("Authorization"),
        request.headers.get("X-Terminal-Id"),
    )
    if current != tenant:
        raise AuthError("The signed-in workspace changed. Sign in again.")
    await require_permission(session, current, "pos.shift.open")
    await require_permission(session, current, "staff.attendance.write")


class StartWorkReference(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action_id: UUID
    company_id: UUID
    actor_user_id: UUID
    branch_id: UUID
    terminal_id: UUID
    installation_id: UUID | None
    client_platform: Literal["web", "android", "ios"]
    opening_float_minor: int = Field(strict=True, ge=0, le=9_223_372_036_854_775_807)
    request_hash: str = Field(pattern=r"^[0-9a-f]{64}$")


class StartWorkOwnerReview(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reference: StartWorkReference
    mode: Literal["status", "cancel"]
    owner_email: str = Field(min_length=3, max_length=254)
    owner_password: SecretStr = Field(min_length=1, max_length=256)
    reason: str | None = Field(default=None, max_length=500)


def _reference_hash(ref):
    return idempotency_request_hash(
        method="POST",
        path=PATH,
        query="",
        key="shift-open:" + str(ref.action_id),
        body=json.dumps(
            {"opening_float_minor": ref.opening_float_minor}, separators=(",", ":")
        ).encode(),
    )


@router.post("/shifts/start-work/recovery", response_model=StartWorkStatus)
async def owner_review(payload: StartWorkOwnerReview, request: Request, session: SessionDep):
    """Purpose-limited Owner approval; never replaces the cashier's login."""
    from app.core.errors import ForbiddenError
    from app.core.roles import has_full_access, has_protected_owner_access, public_roles
    from app.models import IdempotencyKey
    from app.services.auth.credentials import password_failure, validated_role_assignment
    from app.services.auth.rate_limit import enforce_login_rate_limit

    ref = payload.reference
    if ref.action_id.version != 4 or ref.request_hash != _reference_hash(ref):
        raise BusinessRuleError(
            "The saved start-work reference is invalid. Preserve it for review."
        )
    if ref.client_platform == "android" and (
        ref.installation_id is None or ref.installation_id.version != 4
    ):
        raise BusinessRuleError("The saved tablet installation reference is invalid.")
    reason = (payload.reason or "").strip()
    if payload.mode == "cancel" and len(reason) < 12:
        raise BusinessRuleError(
            "Explain why this exact unconfirmed start should be retired (12–500 characters)."
        )
    email = str(payload.owner_email).strip().lower()
    await enforce_login_rate_limit(request, email)
    key = "shift-open:" + str(ref.action_id)
    await session.execute(text("SELECT dcompany_start_work_lock(:key)"), {"key": key})
    owner = await session.scalar(
        select(User)
        .where(User.company_id == ref.company_id, User.email == email)
        .with_for_update(key_share=True)
        .execution_options(populate_existing=True)
    )
    if owner is None:
        raise AuthError("invalid Owner credentials")
    failure = password_failure(owner, payload.owner_password.get_secret_value())
    if failure is not None:
        # No business mutation/reservation precedes this purpose-specific check.
        session.add(
            AuditLog(
                company_id=ref.company_id,
                actor_user_id=None,
                target_user_id=owner.id,
                action="staff.start_work.owner_confirmation_failed",
                entity_type="User",
                entity_id=str(owner.id),
                business_details={"reason": failure},
            )
        )
        await session.commit()
        raise AuthError("invalid Owner credentials")
    roles, assigned_branch = await validated_role_assignment(session, owner)
    if not has_owner_role(roles) or (
        assigned_branch is not None and assigned_branch != ref.branch_id
    ):
        raise ForbiddenError("A current Owner assigned to this shop must review this saved action.")
    owner_scope = TenantContext(
        owner.id,
        ref.company_id,
        ref.branch_id,
        ref.terminal_id,
        tuple(public_roles(roles)),
        has_full_access(roles),
        has_protected_owner_access(roles),
    )
    await require_permission(session, owner_scope, "staff.write")
    set_actor(user_id=owner.id, company_id=ref.company_id, terminal_id=ref.terminal_id)
    # Historical target identities may be disabled/archived. Their ownership,
    # not their ability to take a new sale, is the recovery authority boundary.
    target = await session.scalar(
        select(Terminal.id)
        .join(Branch, Branch.id == Terminal.branch_id)
        .where(
            Terminal.id == ref.terminal_id,
            Terminal.branch_id == ref.branch_id,
            Branch.company_id == ref.company_id,
        )
    )
    actor = await session.scalar(
        select(User.id).where(User.id == ref.actor_user_id, User.company_id == ref.company_id)
    )
    if target is None or actor is None:
        raise ForbiddenError("The saved action does not belong to this Owner's company and shop.")
    retained = await session.scalar(
        select(ShiftStartWorkReceipt).where(ShiftStartWorkReceipt.action_key == key)
    )
    cancelled = await session.scalar(
        select(ShiftStartWorkCancellation).where(ShiftStartWorkCancellation.action_key == key)
    )
    outcome = retained or cancelled
    if outcome is not None:
        platform = (
            cancelled.client_platform
            if cancelled
            else (await session.get(Shift, retained.shift_id)).opening_client_platform
        )
        if (
            outcome.company_id != ref.company_id
            or outcome.actor_user_id != ref.actor_user_id
            or outcome.branch_id != ref.branch_id
            or outcome.terminal_id != ref.terminal_id
            or outcome.installation_id != ref.installation_id
            or outcome.request_hash != ref.request_hash
            or outcome.request_body != {"opening_float_minor": ref.opening_float_minor}
            or platform != ref.client_platform
        ):
            raise IdempotencyConflict(
                "The retained action has different original facts. Nothing changed."
            )
        if (
            cancelled
            and payload.mode == "cancel"
            and (cancelled.cancelled_by != owner.id or cancelled.reason != reason)
        ):
            raise IdempotencyConflict(
                "This cancellation already has different retained approval. Review its status."
            )
        return await _status(session, retained, cancelled)
    if payload.mode == "status":
        return {"state": "unconfirmed"}
    if (
        await session.scalar(select(Shift.id).where(Shift.opening_action_id == key)) is not None
        or await session.scalar(select(IdempotencyKey.key).where(IdempotencyKey.key == key))
        is not None
    ):
        raise ConflictError(
            "Other retained evidence exists for this action. Keep it for Owner investigation; "
            "it cannot be cancelled here."
        )
    at = await session.scalar(text("SELECT clock_timestamp()"))
    ident = uuid4()
    result = {
        "id": str(ident),
        "action_key": key,
        "company_id": str(ref.company_id),
        "actor_user_id": str(ref.actor_user_id),
        "branch_id": str(ref.branch_id),
        "terminal_id": str(ref.terminal_id),
        "installation_id": str(ref.installation_id) if ref.installation_id else None,
        "opening_float_minor": ref.opening_float_minor,
        "request_hash": ref.request_hash,
        "client_platform": ref.client_platform,
        "cancelled_by": str(owner.id),
        "cancelled_at": at.isoformat(),
        "reason": reason,
    }
    session.add(
        ShiftStartWorkCancellation(
            id=ident,
            company_id=ref.company_id,
            actor_user_id=ref.actor_user_id,
            branch_id=ref.branch_id,
            terminal_id=ref.terminal_id,
            installation_id=ref.installation_id,
            action_key=key,
            request_hash=ref.request_hash,
            request_path=PATH,
            request_body={"opening_float_minor": ref.opening_float_minor},
            client_platform=ref.client_platform,
            cancelled_by=owner.id,
            cancelled_at=at,
            reason=reason,
            response_body=result,
        )
    )
    record_operation(
        session,
        company_id=ref.company_id,
        actor_user_id=owner.id,
        terminal_id=ref.terminal_id,
        action="start_work_cancelled",
        entity_type="ShiftStartWorkCancellation",
        entity_id=ident,
        operation_id=key,
        after={
            "original_actor_user_id": str(ref.actor_user_id),
            "action_key": key,
            "reason": reason,
            "no_shift_or_attendance_created": True,
        },
    )
    await session.flush()
    return {"state": "cancelled", "cancellation": result}


@router.post("/shifts/start-work", response_model=StartWorkRead, status_code=201)
async def start_work(
    payload: StartWorkWrite, request: Request, session: SessionDep, tenant: StartTenant
):
    from app.api.v1.pos.router import (
        _shift_opening_client_platform,
        _shift_opening_installation_id,
    )
    from app.api.v1.staff.work_time import _target
    from app.services.pos.shift_capture import read_opening_capture

    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError("Select a verified shop and workspace before starting work.")
    if request.url.query or request.headers.get("X-Offline-Captured", "").strip().lower() not in {
        "",
        "false",
        "0",
        "no",
    }:
        raise BusinessRuleError(
            "Start work requires a fresh online request; saved shifts use their original workflow."
        )
    key = getattr(request.state, "idempotency_key", None)
    digest = getattr(request.state, "idempotency_request_hash", None)
    try:
        action = UUID(key.removeprefix("shift-open:"))
        valid_key = action.version == 4 and key == "shift-open:" + str(action)
    except (AttributeError, TypeError, ValueError):
        valid_key = False
    if not valid_key or not digest:
        raise BusinessRuleError("A durable shift-open action identity is required.")
    digest = idempotency_request_hash(
        method="POST",
        path=PATH,
        query="",
        key=key,
        body=json.dumps(payload.model_dump(), separators=(",", ":")).encode(),
    )
    request.state.idempotency_request_hash = digest
    capture = read_opening_capture(request, datetime.now(UTC))
    if capture.offline:
        raise BusinessRuleError("Attendance cannot be created from an offline shift capture.")
    platform = _shift_opening_client_platform(request, opening_action_id=key, was_offline=False)
    installation = _shift_opening_installation_id(request, platform=platform)
    if platform == "android" and installation is None:
        raise BusinessRuleError("Verify this tablet's installation identity before starting work.")
    await require_permission(session, tenant, "staff.attendance.write")
    await session.execute(text("SELECT dcompany_start_work_lock(:key)"), {"key": key})
    from app.models.shift_start_work import ShiftStartWorkCancellation

    cancelled = await session.scalar(
        select(ShiftStartWorkCancellation).where(ShiftStartWorkCancellation.action_key == key)
    )
    if cancelled is not None:
        raise ConflictError(
            "This exact start-work action was cancelled by an Owner. "
            "Review its retained resolution.",
            details={"state": "cancelled"},
        )
    prior = await check_or_reserve(
        session,
        key=key,
        request_hash=digest,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    await session.scalar(
        select(User)
        .where(User.id == tenant.user_id, User.company_id == tenant.company_id)
        .with_for_update(key_share=True)
        .execution_options(populate_existing=True)
    )
    await _fresh(request, session, tenant)
    retained = await session.scalar(
        select(ShiftStartWorkReceipt).where(ShiftStartWorkReceipt.action_key == key)
    )
    body = payload.model_dump(mode="json")
    if retained is not None:
        retained_shift = await session.get(Shift, retained.shift_id)
        if (
            retained_shift.opening_client_platform != platform
            or retained.company_id != tenant.company_id
            or retained.actor_user_id != tenant.user_id
            or retained.branch_id != tenant.branch_id
            or retained.terminal_id != tenant.terminal_id
            or retained.installation_id != installation
            or retained.request_path != PATH
            or retained.request_hash != digest
            or retained.request_body != body
        ):
            raise IdempotencyConflict(
                "This start-work identity belongs to different retained facts."
            )
        result = dict(retained.response_body)
        await store_response(session, key=key, status_code=201, body=result)
        return result
    if prior is not None:
        raise ConflictError(
            "The saved response has no matching start-work receipt. "
            "Keep the original action for review."
        )
    if (
        await session.scalar(
            select(Shift.id).where(
                Shift.company_id == tenant.company_id, Shift.opening_action_id == key
            )
        )
        is not None
    ):
        raise ConflictError(
            "This action belongs to an existing shift workflow. Keep its original request."
        )
    await _target(session, tenant, tenant.user_id, active=True)
    branch = await session.scalar(
        select(Branch)
        .where(
            Branch.id == tenant.branch_id,
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
        .with_for_update()
    )
    terminal = await session.scalar(
        select(Terminal)
        .where(Terminal.id == tenant.terminal_id, Terminal.branch_id == tenant.branch_id)
        .with_for_update()
    )
    if branch is None or terminal is None or not terminal.is_active or terminal.purpose != "hybrid":
        raise BusinessRuleError("Use this shop's active Hybrid workspace before starting work.")
    existing = await session.scalar(
        select(Shift.id)
        .where(
            Shift.company_id == tenant.company_id,
            Shift.terminal_id == tenant.terminal_id,
            Shift.status == "open",
        )
        .with_for_update()
    )
    if existing is not None:
        raise ConflictError(
            "A shop shift is already open. Use its attendance and handover controls; "
            "do not start another."
        )
    opened = list(
        await session.scalars(
            select(Attendance)
            .where(
                Attendance.company_id == tenant.company_id,
                Attendance.user_id == tenant.user_id,
                Attendance.clock_out_at.is_(None),
            )
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    )
    at = await session.scalar(text("SELECT clock_timestamp()"))
    if len(opened) > 1 or (
        opened and (opened[0].branch_id != tenant.branch_id or opened[0].clock_in_at > at)
    ):
        raise ConflictError(
            "The existing attendance needs review. No shift or clock-in was changed."
        )
    await _fresh(request, session, tenant)
    created = not opened
    attendance = (
        opened[0]
        if opened
        else Attendance(
            id=uuid4(),
            company_id=tenant.company_id,
            user_id=tenant.user_id,
            branch_id=tenant.branch_id,
            clock_in_at=at,
            source="app",
        )
    )
    if created:
        session.add(attendance)
        await session.flush()
        await enqueue_staff_attendance_if_enabled(
            session, attendance=attendance, actual_break_confirmed=False
        )
    shift = Shift(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        opened_by=tenant.user_id,
        opened_at=at,
        opening_action_id=key,
        opening_request_hash=capture.request_hash,
        opening_received_at=at,
        opening_was_offline=False,
        opening_protocol_revision=1,
        opening_client_platform=platform,
        opening_client_installation_id=installation,
        opening_float_minor=payload.opening_float_minor,
        expected_minor=payload.opening_float_minor,
        status="open",
    )
    session.add(shift)
    await session.flush()
    ident = uuid4()
    result = StartWorkRead(
        receipt_id=ident,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        installation_id=installation,
        shift_id=shift.id,
        shift_status_at_start="open",
        opening_float_minor=payload.opening_float_minor,
        attendance_id=attendance.id,
        clock_in_at=attendance.clock_in_at,
        attendance_created=created,
        original_attendance_preserved=not created,
        started_at=at,
    ).model_dump(mode="json")
    session.add(
        ShiftStartWorkReceipt(
            id=ident,
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            installation_id=installation,
            shift_id=shift.id,
            attendance_id=attendance.id,
            action_key=key,
            request_hash=digest,
            request_path=PATH,
            request_body=body,
            response_body=result,
            opening_float_minor=payload.opening_float_minor,
            clock_in_at=attendance.clock_in_at,
            attendance_created=created,
            started_at=at,
        )
    )
    record_operation(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        action="shift_opened",
        entity_type="Shift",
        entity_id=shift.id,
        operation_id=key,
        after={
            "opening_float_minor": payload.opening_float_minor,
            "branch_id": str(tenant.branch_id),
            "status": "open",
            "opened_at": at.isoformat(),
            "attendance_id": str(attendance.id),
            "attendance_created": created,
        },
    )
    await store_response(session, key=key, status_code=201, body=result)
    await session.flush()
    await _fresh(request, session, tenant)
    return result
