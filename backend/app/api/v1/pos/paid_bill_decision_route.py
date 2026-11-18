"""Fresh-transaction resolution of decision-index races; preserve legacy route behavior."""

from __future__ import annotations

import asyncio
from contextlib import AsyncExitStack

from fastapi.routing import APIRoute
from sqlalchemy.exc import DBAPIError

from app.api.v1.finance.tender_corrections import TenderRoute
from app.core.errors import ConflictError


class PaidBillDecisionRoute(TenderRoute):
    def get_route_handler(self):
        if not self.path.endswith(("/actions/cancel", "/actions/outcome")):
            return super().get_route_handler()
        original = APIRoute.get_route_handler(self)

        async def handler(request):
            outer_function = request.scope.get("fastapi_function_astack")
            outer_request = request.scope.get("fastapi_inner_astack")
            for attempt in range(3):
                try:
                    async with (
                        AsyncExitStack() as request_stack,
                        AsyncExitStack() as function_stack,
                    ):
                        request.scope["fastapi_inner_astack"] = request_stack
                        request.scope["fastapi_function_astack"] = function_stack
                        response = await original(request)
                    return response
                except DBAPIError as exc:
                    state = getattr(exc.orig, "sqlstate", None) or getattr(
                        getattr(exc.orig, "__cause__", None), "sqlstate", None
                    )
                    if state not in {"23505", "40001", "40P01"}:
                        if state in {"23514", "23503"}:
                            raise ConflictError(
                                "The exact paid-bill decision could not be confirmed. "
                                "Preserve the original key and recover its outcome."
                            ) from exc
                        raise
                    if attempt == 2:
                        raise ConflictError(
                            "Paid-bill arbitration is unconfirmed. Retry the same original key.",
                            details={"retryable": True, "outcome": "unconfirmed"},
                        ) from exc
                    await asyncio.sleep(0.025 * (2**attempt))
                finally:
                    request.scope["fastapi_function_astack"] = outer_function
                    request.scope["fastapi_inner_astack"] = outer_request

        return handler
