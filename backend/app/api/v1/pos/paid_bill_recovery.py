"""Dedicated cold recovery only; deliberately no normal tenant/auth dependency."""

# FastAPI resolves annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from fastapi import APIRouter, Header, Request
from fastapi.exceptions import RequestValidationError

from app.api.v1.pos.paid_bill_decision_route import PaidBillDecisionRoute
from app.core.db import SessionDep
from app.core.errors import AppError, BusinessRuleError, ServiceUnavailableError
from app.core.logging import get_logger
from app.schemas.paid_bill_action_decision import PaidBillCancelRequest, PaidBillOutcome
from app.schemas.paid_bill_recovery import (
    RecoveryLockResponse,
    RecoveryUnlockRequest,
    RecoveryUnlockResponse,
)
from app.services.pos import paid_bill_recovery as service


class RecoveryRoute(PaidBillDecisionRoute):
    def get_route_handler(self):
        original = super().get_route_handler()

        async def handler(request):
            try:
                return await original(request)
            except RequestValidationError:
                # Arbitrary extra JSON keys must not become generic logged field paths.
                raise BusinessRuleError("Historical recovery request is invalid.") from None
            except AppError:
                raise
            except Exception:
                # SQL exception reprs can contain command/reason/credential parameters.
                get_logger(__name__).error("paid_recovery.operation_unavailable")
                raise ServiceUnavailableError(
                    "Historical recovery is temporarily unavailable. Keep the exact saved request."
                ) from None

        return handler


router = APIRouter(route_class=RecoveryRoute)


@router.post("/unlock", response_model=RecoveryUnlockResponse)
async def unlock(
    payload: RecoveryUnlockRequest,
    request: Request,
    session: SessionDep,
    idempotency_key: str = Header(),
):
    try:
        return await service.unlock(session, request, payload, idempotency_key)
    except service.PasswordDeniedError:
        # Auth failure counters and safe audit must survive the error response, like login.
        await session.commit()
        raise


@router.get("/actions/outcome", response_model=PaidBillOutcome)
async def outcome(
    request: Request,
    session: SessionDep,
    idempotency_key: str = Header(),
    x_paid_bill_recovery: str | None = Header(default=None),
):
    return await service.outcome(session, request, idempotency_key, x_paid_bill_recovery)


@router.post("/actions/cancel", response_model=PaidBillOutcome)
async def cancel(
    payload: PaidBillCancelRequest,
    request: Request,
    session: SessionDep,
    idempotency_key: str = Header(),
    x_paid_bill_recovery: str | None = Header(default=None),
):
    return await service.cancel(session, request, idempotency_key, x_paid_bill_recovery, payload)


@router.post("/lock", response_model=RecoveryLockResponse)
async def lock(
    request: Request, session: SessionDep, x_paid_bill_recovery: str | None = Header(default=None)
):
    return await service.lock(session, request, x_paid_bill_recovery)
