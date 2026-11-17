"""Protected Owner money-only paid-bill intentions and exact durable recovery."""

# FastAPI resolves dependency and DTO annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Request
from pydantic import BaseModel, ConfigDict, SecretStr
from sqlalchemy import select

from app.api.v1.finance.tender_corrections import (
    require_confirmation,
    require_protected_owner,
)
from app.api.v1.pos.paid_bill_decision_route import PaidBillDecisionRoute
from app.core.db import SessionDep
from app.core.errors import AuthError, NotFoundError
from app.core.permissions import require_permission, requires
from app.core.security import issue_tender_token, verify_password
from app.core.tenant import TenantContext, get_tenant_context
from app.models import Branch, PaidBillAmendmentIntent, User
from app.schemas.paid_bill_action_decision import PaidBillCancelRequest, PaidBillOutcome
from app.schemas.paid_bill_amendment import (
    AmendmentAcceptance,
    AmendmentActionRead,
    AmendmentCommitRequest,
    AmendmentPreview,
    AmendmentPreviewRequest,
    PaidBillState,
)
from app.services.auth.rate_limit import enforce_login_rate_limit
from app.services.pos import paid_bill_action_decisions as decisions
from app.services.pos import paid_bill_amendments as service

router = APIRouter(route_class=PaidBillDecisionRoute)
Owner = Annotated[TenantContext, Depends(requires("finance.receipt.correct"))]


def _fresh_authority(request, session, tenant):
    # These original credentials stay only in this request's closure.
    authorization = request.headers.get("Authorization")
    terminal = request.headers.get("X-Terminal-Id")

    async def fresh():
        await session.scalar(
            select(User)
            .where(
                User.id == tenant.user_id,
                User.company_id == tenant.company_id,
            )
            .execution_options(populate_existing=True)
        )
        current = await get_tenant_context(request, session, authorization, terminal)
        if current != tenant:
            raise AuthError("The signed-in workspace changed. Sign in again.")
        await require_permission(session, current, "finance.receipt.correct")
        await require_protected_owner(session, current)

    return fresh


async def _locked_authority(request, session, tenant):
    fresh = _fresh_authority(request, session, tenant)
    await service.lock_actor(session, tenant)
    await fresh()
    return fresh


class Unlock(BaseModel):
    model_config = ConfigDict(extra="forbid")
    branch_id: UUID
    password: SecretStr


@router.post("/unlock")
async def unlock(payload: Unlock, request: Request, session: SessionDep, tenant: Owner):
    fresh = await _locked_authority(request, session, tenant)
    user = await session.scalar(
        select(User).where(User.id == tenant.user_id, User.company_id == tenant.company_id)
    )
    branch = await session.scalar(
        select(Branch.id).where(
            Branch.id == payload.branch_id,
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if (
        user is None
        or branch is None
        or payload.branch_id != tenant.branch_id
        or tenant.terminal_id is None
    ):
        raise NotFoundError("Owner branch and terminal not found")
    await enforce_login_rate_limit(request, user.email)
    if not verify_password(payload.password.get_secret_value(), user.password_hash):
        raise AuthError("Owner password is incorrect.")
    await fresh()
    return {
        "token": issue_tender_token(
            user_id=user.id,
            company_id=tenant.company_id,
            branch_id=payload.branch_id,
            auth_version=user.auth_version,
            scope=service.PURPOSE,
        ),
        "expires_in_seconds": 300,
        "purpose": service.PURPOSE,
    }


@router.post("/preview", response_model=AmendmentPreview)
async def preview(
    payload: AmendmentPreviewRequest, request: Request, session: SessionDep, tenant: Owner
):
    fresh = await _locked_authority(request, session, tenant)
    result = await service.review(session, tenant, payload)
    await fresh()
    return result


@router.post("/commit", status_code=201, response_model=AmendmentAcceptance)
async def commit(
    payload: AmendmentCommitRequest,
    request: Request,
    session: SessionDep,
    tenant: Owner,
    idempotency_key: str | None = Header(default=None),
    x_owner_confirmation: str | None = Header(default=None),
):
    return await service.commit(
        session,
        tenant,
        payload,
        action_key=idempotency_key,
        confirmation=x_owner_confirmation,
        reauthorize=_fresh_authority(request, session, tenant),
    )


@router.get("/actions/by-key", response_model=AmendmentActionRead)
async def action(
    request: Request,
    session: SessionDep,
    tenant: Owner,
    idempotency_key: str = Header(),
    x_owner_confirmation: str | None = Header(default=None),
):
    fresh = await _locked_authority(request, session, tenant)
    row = await session.scalar(
        select(PaidBillAmendmentIntent).where(
            PaidBillAmendmentIntent.company_id == tenant.company_id,
            PaidBillAmendmentIntent.recorded_by == tenant.user_id,
            PaidBillAmendmentIntent.branch_id == tenant.branch_id,
            PaidBillAmendmentIntent.terminal_id == tenant.terminal_id,
            PaidBillAmendmentIntent.action_key == idempotency_key,
        )
    )
    if row is None:
        raise NotFoundError("Saved paid-bill action not found")
    await service.scoped_order(session, tenant, row.order_id)
    await require_confirmation(
        session, tenant, x_owner_confirmation, branch_id=row.branch_id, scope=service.PURPOSE
    )
    current = await service.current_state(session, tenant, row.order_id)
    await require_confirmation(
        session, tenant, x_owner_confirmation, branch_id=row.branch_id, scope=service.PURPOSE
    )
    await fresh()
    return {"accepted": row.response_body, "current": current}


@router.get("/receipts/{order_id}", response_model=PaidBillState)
async def receipt(order_id: UUID, request: Request, session: SessionDep, tenant: Owner):
    fresh = await _locked_authority(request, session, tenant)
    result = await service.current_state(session, tenant, order_id)
    await fresh()
    return result


@router.post("/actions/cancel", response_model=PaidBillOutcome)
async def cancel_action(payload: PaidBillCancelRequest, request: Request, session: SessionDep,
    tenant: Owner, idempotency_key: str | None = Header(default=None),
    x_owner_confirmation: str | None = Header(default=None)):
    return await decisions.cancel(session, tenant, payload, key=idempotency_key,
        confirmation=x_owner_confirmation, fresh=_fresh_authority(request,session,tenant))


@router.get("/actions/outcome", response_model=PaidBillOutcome)
async def action_outcome(request: Request, session: SessionDep, tenant: Owner,
    idempotency_key: str = Header(), x_owner_confirmation: str | None = Header(default=None)):
    return await decisions.outcome(session,tenant,key=idempotency_key,
        confirmation=x_owner_confirmation,fresh=_fresh_authority(request,session,tenant))
