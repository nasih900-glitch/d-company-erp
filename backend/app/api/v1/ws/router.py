"""Real-time push endpoint.

Auth deliberately does NOT use a query-string token (`?token=...`) — Caddy's
access log records the full request URI including query string, so a token
in the URL would end up sitting in plaintext log files. Instead the client
opens the socket unauthenticated, then sends one JSON text message
`{"token": "<access token>"}` as its first frame; the connection is
accepted (added to the broadcast registry) only after that validates, and
closed otherwise. Same access-token semantics as every REST call — no
separate credential type to manage.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import math
import time
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from sqlalchemy import select
from starlette.websockets import WebSocketState

from app.core.db import AsyncSessionLocal
from app.core.logging import get_logger
from app.core.security import decode_token
from app.models import User
from app.services.auth.refresh_sessions import access_family_is_active
from app.services.realtime import manager

log = get_logger(__name__)

router = APIRouter()

# A client that never sends a valid token within this window is a dead or
# hostile connection, not a slow one — real clients send it immediately
# on open.
_AUTH_TIMEOUT_SECONDS = 10
# Idle sockets get dropped by some intermediaries well before a minute;
# ping comfortably inside that window so the connection reads as "active"
# end-to-end (browser <-> Caddy <-> backend), and so a truly dead peer is
# noticed and cleaned up instead of leaking a phantom registry entry.
_PING_INTERVAL_SECONDS = 20
_SERVER_AUTH_RECHECK_SECONDS = 20


@dataclass(frozen=True)
class _AuthenticatedSocket:
    company_id: UUID
    user_id: UUID
    auth_version: int
    claims: dict[str, Any]
    expires_at_epoch_seconds: float


async def _authenticate(websocket: WebSocket) -> _AuthenticatedSocket | None:
    """Validate the first frame and retain the access-token expiry boundary."""
    try:
        raw = await asyncio.wait_for(websocket.receive_text(), timeout=_AUTH_TIMEOUT_SECONDS)
    except (TimeoutError, WebSocketDisconnect):
        return None
    try:
        payload = json.loads(raw)
        token = payload["token"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return None
    try:
        claims = decode_token(token)
    except ValueError:
        return None
    if claims.get("type") != "access":
        return None
    expires_at = claims.get("exp")
    if (
        isinstance(expires_at, bool)
        or not isinstance(expires_at, (int, float))
        or not math.isfinite(float(expires_at))
    ):
        return None
    try:
        company_id = UUID(str(claims["company_id"]))
        user_id = UUID(str(claims["sub"]))
        auth_version = int(claims.get("auth_version", 0))
    except (KeyError, TypeError, ValueError):
        return None
    return _AuthenticatedSocket(
        company_id=company_id,
        user_id=user_id,
        auth_version=auth_version,
        claims=claims,
        expires_at_epoch_seconds=float(expires_at),
    )


async def _server_session_is_active(auth: _AuthenticatedSocket) -> bool:
    """Revalidate mutable account and session-family state for realtime auth."""

    async with AsyncSessionLocal() as session:
        user = (
            await session.execute(
                select(User).where(
                    User.id == auth.user_id,
                    User.company_id == auth.company_id,
                )
            )
        ).scalar_one_or_none()
        if (
            user is None
            or user.deleted_at is not None
            or user.status != "active"
            or user.auth_version != auth.auth_version
        ):
            return False
        return await access_family_is_active(session, user=user, claims=auth.claims)


async def _checked_server_session_is_active(
    websocket: WebSocket,
    auth: _AuthenticatedSocket,
) -> bool | None:
    """Fail closed with an explicit retryable close when auth storage is down.

    Database unavailability must never accidentally authenticate a socket, but
    an accepted connection also must not be abandoned with an opaque ASGI
    failure. ``None`` means the check could not be completed and the socket has
    already been closed with the standard temporary-unavailability code.
    """

    try:
        return await _server_session_is_active(auth)
    except Exception:  # noqa: BLE001 - every auth-state failure must fail closed
        log.exception(
            "realtime.session_validation_unavailable",
            company_id=str(auth.company_id),
            user_id=str(auth.user_id),
        )
        with contextlib.suppress(Exception):
            await websocket.close(
                code=1013,
                reason="authentication service temporarily unavailable",
            )
        return None


@router.websocket("")
async def realtime_socket(websocket: WebSocket) -> None:
    await websocket.accept()

    auth = await _authenticate(websocket)
    if auth is None:
        await websocket.close(code=4401, reason="unauthenticated")
        return
    if auth.expires_at_epoch_seconds <= time.time():
        await websocket.close(code=4401, reason="access token expired")
        return
    session_active = await _checked_server_session_is_active(websocket, auth)
    if session_active is None:
        return
    if not session_active:
        await websocket.close(code=4401, reason="session expired")
        return
    company_id = auth.company_id

    manager.connect(company_id, websocket)
    try:
        await websocket.send_json({"type": "connected"})
        last_server_auth_check = time.monotonic()
        while True:
            seconds_until_expiry = auth.expires_at_epoch_seconds - time.time()
            if seconds_until_expiry <= 0:
                await websocket.close(code=4401, reason="access token expired")
                return
            if time.monotonic() - last_server_auth_check >= _SERVER_AUTH_RECHECK_SECONDS:
                session_active = await _checked_server_session_is_active(websocket, auth)
                if session_active is None:
                    return
                if not session_active:
                    await websocket.close(code=4401, reason="session expired")
                    return
                last_server_auth_check = time.monotonic()
            try:
                await asyncio.wait_for(
                    websocket.receive_text(),
                    timeout=min(_PING_INTERVAL_SECONDS, seconds_until_expiry),
                )
                # Any client message (e.g. a pong) just resets the wait —
                # this endpoint doesn't need to act on client-sent content.
            except TimeoutError:
                if time.time() >= auth.expires_at_epoch_seconds:
                    await websocket.close(code=4401, reason="access token expired")
                    return
                if websocket.client_state != WebSocketState.CONNECTED:
                    break
                await websocket.send_json({"type": "ping"})
    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("realtime.socket_error", company_id=str(company_id))
    finally:
        manager.disconnect(company_id, websocket)
        with contextlib.suppress(Exception):
            await websocket.close()
