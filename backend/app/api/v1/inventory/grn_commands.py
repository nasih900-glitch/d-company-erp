"""Bounded GRN command endpoints; dedicated purpose credentials never log raw evidence."""

# FastAPI resolves imported dependency/DTO annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

from contextlib import AsyncExitStack
from typing import Annotated

from fastapi import APIRouter, Depends, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from sqlalchemy.exc import DBAPIError

from app.core.db import SessionDep
from app.core.errors import AppError, BusinessRuleError, ConflictError, ServiceUnavailableError
from app.core.logging import get_logger
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.schemas.grn_commands import (
    Capabilities,
    LockResponse,
    Outcome,
    RecoveryCancel,
    RecoveryUnlock,
    UnlockResponse,
)
from app.services.inventory import grn_recovery as service


class GrnCommandRoute(APIRoute):
    def get_route_handler(self):
        original = super().get_route_handler()
        if not (
            self.path.endswith("/grn")
            or "/grn-commands/" in self.path
            or self.path.startswith("/recovery/")
            or self.path == "/capabilities"
        ):
            return original

        async def handler(request):
            outer_function = request.scope.get("fastapi_function_astack")
            outer_request = request.scope.get("fastapi_inner_astack")
            try:
                # Exit the function dependency (commit/rollback) before translating DB errors.
                async with AsyncExitStack() as request_stack, AsyncExitStack() as function_stack:
                    request.scope["fastapi_inner_astack"] = request_stack
                    request.scope["fastapi_function_astack"] = function_stack
                    response = await original(request)
                return response
            except RequestValidationError as exc:
                if (
                    request.headers.get("X-GRN-Command-Protocol") is None
                    and request.url.path.endswith("/inventory/grn")
                    and any(e.get("type") == "decimal_max_places" for e in exc.errors())
                ):
                    raise BusinessRuleError(
                        "GRN quantity must have at most four decimal places."
                    ) from None
                raise BusinessRuleError(
                    "The goods receipt command is invalid. Keep the original saved request."
                ) from None
            except DBAPIError as exc:
                code = getattr(exc.orig, "sqlstate", None) or getattr(
                    getattr(exc.orig, "__cause__", None), "sqlstate", None
                )
                if code in {"23505", "40001", "40P01", "23514", "23503", "22003"}:
                    raise ConflictError(
                        "Goods receipt outcome is unconfirmed. "
                        "Use a new authenticated outcome request; "
                        "do not create another receipt.",
                        details={"retryable": True, "outcome": "unconfirmed"},
                    ) from None
                get_logger(__name__).error("grn_command.database_unavailable")
                raise ServiceUnavailableError(
                    "Goods receipt recovery is temporarily unavailable. Keep the original command."
                ) from None
            except AppError as exc:
                # Existing cache conflicts may include the private raw key in details.
                exc.details = {}
                if not exc.message.startswith(
                    "Supplier invoice amount does not match the capitalised GRN line total."
                ):
                    exc.message = (
                        "Goods receipt command could not be confirmed. "
                        "Keep the original saved request."
                    )
                exc.args = (exc.message,)
                raise
            except Exception:
                get_logger(__name__).error("grn_command.operation_unavailable")
                raise ServiceUnavailableError(
                    "Goods receipt recovery is temporarily unavailable. Keep the original command."
                ) from None
            finally:
                request.scope["fastapi_function_astack"] = outer_function
                request.scope["fastapi_inner_astack"] = outer_request

        return handler


router = APIRouter(route_class=GrnCommandRoute)
InventoryWriter = Annotated[TenantContext, Depends(requires("inventory.write"))]


@router.get("/capabilities", response_model=Capabilities)
async def capabilities(tenant: InventoryWriter):
    return Capabilities()


@router.post("/recovery/unlock", response_model=UnlockResponse)
async def unlock(
    payload: RecoveryUnlock, request: Request, session: SessionDep, idempotency_key: str = Header()
):
    try:
        return await service.unlock(session, request, payload, idempotency_key)
    except service.PasswordDeniedError:
        # Returning denial lets the sole dependency commit safe lockout counters, not a grant.
        return JSONResponse(
            status_code=401, content={"error": {"code": "unauthorized", "message": service.DENIED}}
        )


@router.get("/recovery/actions/outcome", response_model=Outcome)
async def outcome(
    request: Request,
    session: SessionDep,
    idempotency_key: str = Header(),
    x_grn_recovery: str | None = Header(default=None),
):
    return await service.outcome(session, request, idempotency_key, x_grn_recovery)


@router.post("/recovery/actions/cancel", response_model=Outcome)
async def cancel(
    payload: RecoveryCancel,
    request: Request,
    session: SessionDep,
    idempotency_key: str = Header(),
    x_grn_recovery: str | None = Header(default=None),
):
    return await service.cancel(session, request, idempotency_key, x_grn_recovery, payload)


@router.post("/recovery/lock", response_model=LockResponse)
async def lock(
    request: Request, session: SessionDep, x_grn_recovery: str | None = Header(default=None)
):
    return await service.lock(session, request, x_grn_recovery)
