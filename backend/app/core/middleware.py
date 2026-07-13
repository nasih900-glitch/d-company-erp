"""HTTP middleware: request context, timing, idempotency."""

from __future__ import annotations

import hashlib
import time
import uuid
from typing import Awaitable, Callable

import structlog
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import get_logger
from app.services.audit.recorder import clear_actor

log = get_logger(__name__)


class _RequestBodyTooLarge(Exception):
    pass


class RequestBodyLimitMiddleware:
    """Reject oversized HTTP bodies before parsers or idempotency buffering."""

    def __init__(self, app: ASGIApp, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        raw_length = headers.get(b"content-length")
        if raw_length:
            try:
                if int(raw_length) > self.max_bytes:
                    await self._reject(scope, receive, send)
                    return
            except ValueError:
                await self._reject(scope, receive, send, code="invalid_content_length")
                return

        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_bytes:
                    raise _RequestBodyTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except _RequestBodyTooLarge:
            await self._reject(scope, receive, send)

    async def _reject(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
        *,
        code: str = "request_too_large",
    ) -> None:
        response = JSONResponse(
            status_code=413 if code == "request_too_large" else 400,
            content={
                "error": {
                    "code": code,
                    "message": (
                        f"Request body must be {self.max_bytes} bytes or fewer"
                        if code == "request_too_large"
                        else "Content-Length header is invalid"
                    ),
                }
            },
        )
        await response(scope, receive, send)


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Binds request_id and (later) tenant context to structlog."""

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        clear_actor()
        request_id = request.headers.get("X-Request-Id") or str(uuid.uuid4())
        structlog.contextvars.clear_contextvars()
        structlog.contextvars.bind_contextvars(
            request_id=request_id,
            path=request.url.path,
            method=request.method,
        )
        request.state.request_id = request_id
        try:
            response = await call_next(request)
            response.headers["X-Request-Id"] = request_id
            return response
        finally:
            clear_actor()


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
            key = request.headers.get("Idempotency-Key") or request.headers.get(
                "X-Idempotency-Key"
            )
            if key:
                if len(key) > 160:
                    return JSONResponse(
                        status_code=400,
                        content={
                            "error": {
                                "code": "invalid_idempotency_key",
                                "message": "Idempotency-Key must be 160 characters or fewer",
                            }
                        },
                    )
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
