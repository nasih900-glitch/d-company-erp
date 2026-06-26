"""HTTP middleware: request context, timing, idempotency."""

from __future__ import annotations

import hashlib
import time
import uuid
from typing import Awaitable, Callable

import structlog
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from app.core.logging import get_logger

log = get_logger(__name__)


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Binds request_id and (later) tenant context to structlog."""

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        request_id = request.headers.get("X-Request-Id") or str(uuid.uuid4())
        structlog.contextvars.clear_contextvars()
        structlog.contextvars.bind_contextvars(
            request_id=request_id,
            path=request.url.path,
            method=request.method,
        )
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-Id"] = request_id
        return response


class TimingMiddleware(BaseHTTPMiddleware):
    """Adds Server-Timing header and logs duration."""

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        t0 = time.perf_counter()
        response = await call_next(request)
        dur_ms = (time.perf_counter() - t0) * 1000
        response.headers["Server-Timing"] = f"app;dur={dur_ms:.1f}"
        log.info(
            "http.request",
            status=response.status_code,
            duration_ms=round(dur_ms, 1),
        )
        return response


class IdempotencyMiddleware(BaseHTTPMiddleware):
    """Skeleton idempotency middleware.

    Real implementation:
      1. Read `Idempotency-Key` header on mutating verbs.
      2. Look up in `idempotency_keys` table by key.
      3. If found with matching request body hash → return stored response.
      4. If found with mismatched hash → 409.
      5. If not found → execute, then store (key, hash, response).

    This stub computes the hash and attaches it to request.state so the
    route handler can persist it in the same transaction as the write.
    """

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        if request.method in {"POST", "PUT", "PATCH", "DELETE"}:
            key = request.headers.get("Idempotency-Key")
            if key:
                body = await request.body()
                request.state.idempotency_key = key
                request.state.idempotency_request_hash = hashlib.sha256(
                    key.encode() + b":" + body
                ).hexdigest()
                # Re-attach the body so downstream handlers can re-read it.
                async def receive() -> dict:  # type: ignore[type-arg]
                    return {"type": "http.request", "body": body, "more_body": False}

                request._receive = receive  # noqa: SLF001
        return await call_next(request)
