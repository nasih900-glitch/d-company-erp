"""HTTP middleware: request context, timing, idempotency."""

from __future__ import annotations

import asyncio
import hashlib
import time
import uuid
from datetime import UTC, datetime
from typing import Awaitable, Callable
from uuid import UUID

import structlog
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.logging import get_logger
from app.core.security import decode_token
from app.services.audit.recorder import (
    clear_actor,
    clear_request_context,
    set_request_context,
)
from app.services.realtime import manager, resources_for_path

log = get_logger(__name__)

_POSTGRES_INTEGER_MAX = 2_147_483_647


def _printable_ascii_header(value: str | None, *, max_length: int) -> str | None:
    """Keep correlation values safe to persist and echo in response headers."""
    normalized = (value or "").strip()
    if not 1 <= len(normalized) <= max_length:
        return None
    if any(not 33 <= ord(char) <= 126 for char in normalized):
        return None
    return normalized


def _client_version_code(value: str | None) -> int | None:
    try:
        parsed = int((value or "").strip())
    except ValueError:
        return None
    return parsed if 1 <= parsed <= _POSTGRES_INTEGER_MAX else None


def idempotency_request_hash(*, method: str, path: str, query: str, key: str, body: bytes) -> str:
    """Bind an idempotency key to one exact HTTP operation and payload."""
    operation = f"{method.upper()}\n{path}\n{query}\n{key}\n".encode()
    return hashlib.sha256(operation + body).hexdigest()


class _RequestBodyTooLarge(Exception):
    pass


class ClientCompatibilityMiddleware(BaseHTTPMiddleware):
    """Reject a declared native build below the server's safe minimum.

    Header enforcement for undeclared legacy clients is rollout-gated: deploy
    header-capable apps first, then enable it. Web and operational scripts do
    not claim a native platform and are unaffected.
    """

    def __init__(
        self,
        app: ASGIApp,
        *,
        android_minimum: int,
        android_latest: int,
        android_update_url: str | None,
        ios_minimum: int,
        ios_latest: int,
        ios_update_url: str | None,
        require_native_headers: bool,
        message: str | None,
        android_latest_version_name: str | None = None,
        android_release_notes: str | None = None,
        android_apk_sha256: str | None = None,
        android_apk_size_bytes: int | None = None,
        android_apk_signing_cert_sha256: str | None = None,
    ) -> None:
        super().__init__(app)
        self.versions = {
            "android": (android_minimum, android_latest, android_update_url),
            "ios": (ios_minimum, ios_latest, ios_update_url),
        }
        self.require_native_headers = require_native_headers
        self.message = message
        self.android_release = {
            "latest_version_name": android_latest_version_name,
            "release_notes": android_release_notes,
            "apk_sha256": android_apk_sha256,
            "apk_size_bytes": android_apk_size_bytes,
            "apk_signing_cert_sha256": android_apk_signing_cert_sha256,
        }

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        if request.url.path.endswith("/public/client-compatibility"):
            return await call_next(request)

        platform = request.headers.get("X-Client-Platform", "").strip().lower()
        version_raw = request.headers.get("X-Client-Version-Code", "").strip()
        if not platform:
            is_legacy_android = "okhttp/" in request.headers.get("user-agent", "").lower()
            if self.require_native_headers and is_legacy_android:
                return self._reject(
                    platform="android",
                    code="client_version_missing",
                    message=(
                        "This app is too old to verify server compatibility. "
                        "Install the current D Company ERP app before continuing."
                    ),
                )
            return await call_next(request)

        if platform not in self.versions:
            return JSONResponse(
                status_code=400,
                content={
                    "error": {
                        "code": "client_platform_invalid",
                        "message": "X-Client-Platform must be android or ios.",
                    }
                },
            )
        version_code = _client_version_code(version_raw)
        if version_code is None:
            return self._reject(
                platform=platform,
                code="client_version_invalid",
                message="This app could not prove its build version. Update before continuing.",
            )

        minimum, latest, _ = self.versions[platform]
        if version_code < minimum:
            return self._reject(
                platform=platform,
                code="client_update_required",
                message=self.message
                or (
                    "This app version is no longer compatible with the ERP server. "
                    "Update before continuing; saved offline work will remain on this device."
                ),
                current=version_code,
            )

        response = await call_next(request)
        response.headers["X-Minimum-Supported-Version-Code"] = str(minimum)
        response.headers["X-Latest-Version-Code"] = str(latest)
        return response

    def _reject(
        self,
        *,
        platform: str,
        code: str,
        message: str,
        current: int | None = None,
    ) -> JSONResponse:
        minimum, latest, update_url = self.versions[platform]
        release_details = self.android_release if platform == "android" else {}
        return JSONResponse(
            status_code=426,
            content={
                "error": {
                    "code": code,
                    "message": message,
                    "details": {
                        "platform": platform,
                        "current_version_code": current,
                        "minimum_supported_version_code": minimum,
                        "latest_version_code": latest,
                        "update_url": update_url,
                        **release_details,
                    },
                }
            },
        )


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
        clear_request_context()
        request_id = _printable_ascii_header(
            request.headers.get("X-Request-Id"), max_length=64
        ) or str(uuid.uuid4())
        platform = (request.headers.get("X-Client-Platform") or "web").strip().lower()
        if platform not in {"android", "ios", "web"}:
            platform = "web"
        client_version_code = _client_version_code(request.headers.get("X-Client-Version-Code"))
        action_id = _printable_ascii_header(
            request.headers.get("X-Client-Action-Id"), max_length=100
        ) or _printable_ascii_header(request.headers.get("Idempotency-Key"), max_length=100)
        offline_raw = (request.headers.get("X-Offline-Captured") or "").strip().lower()
        client_was_offline = offline_raw in {"1", "true", "yes"}
        reported_raw = (request.headers.get("X-Client-Occurred-At") or "").strip()
        client_reported_at = None
        if reported_raw and len(reported_raw) <= 64:
            try:
                candidate = datetime.fromisoformat(reported_raw.replace("Z", "+00:00"))
                if candidate.tzinfo is not None:
                    client_reported_at = candidate.astimezone(UTC)
            except ValueError:
                pass
        set_request_context(
            request_id=request_id,
            client_platform=platform,
            client_version_code=client_version_code,
            client_action_id=action_id,
            client_reported_at=client_reported_at,
            client_was_offline=client_was_offline,
        )
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
            clear_request_context()


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


class RealtimeBroadcastMiddleware(BaseHTTPMiddleware):
    """After any successful write to an operationally-shared resource
    (shifts, tables, orders, gaming, kitchen, attendance), push a "changed" signal to
    every other connected client for that company over WebSocket — see
    app.services.realtime. This is what lets a shift opened on one login
    show up on every other login within roughly a second, instead of each
    screen only finding out next time it happens to poll or gets manually
    refreshed.

    Decodes the bearer token itself rather than reading the audit
    recorder's actor ContextVar, so this middleware's correctness doesn't
    depend on where it's registered relative to RequestContextMiddleware's
    own set/clear lifecycle — it's fully self-contained.
    """

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        response = await call_next(request)
        # Login has no trusted incoming tenant context. A caller may attach an
        # unrelated bearer token, so deriving the login user's company from the
        # request header would broadcast to the wrong tenant. The endpoint
        # commits login_success and schedules its own audit signal using the
        # authenticated database user instead.
        is_login = request.url.path.rstrip("/").endswith("/auth/login")
        if (
            request.method in ("POST", "PATCH", "PUT", "DELETE")
            and 200 <= response.status_code < 300
            and not is_login
        ):
            resources = resources_for_path(request.url.path)
            if resources:
                company_id = self._company_id(request)
                if company_id is not None:
                    for resource in resources:
                        asyncio.create_task(manager.broadcast(company_id, resource))
        return response

    @staticmethod
    def _company_id(request: Request) -> UUID | None:
        auth = request.headers.get("authorization")
        if not auth or not auth.lower().startswith("bearer "):
            return None
        try:
            claims = decode_token(auth.split(" ", 1)[1])
            return UUID(str(claims["company_id"]))
        except Exception:
            return None


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
            key = request.headers.get("Idempotency-Key") or request.headers.get("X-Idempotency-Key")
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
                # The operation identity is part of the hash. A key reused for
                # the same JSON body on a different order/payment URL must
                # conflict, never replay a response from the first endpoint.
                request.state.idempotency_request_hash = idempotency_request_hash(
                    method=request.method,
                    path=request.url.path,
                    query=request.url.query,
                    key=key,
                    body=body,
                )

                # Re-attach the body so downstream handlers can re-read it.
                async def receive() -> dict:  # type: ignore[type-arg]
                    return {"type": "http.request", "body": body, "more_body": False}

                request._receive = receive  # noqa: SLF001
        return await call_next(request)
