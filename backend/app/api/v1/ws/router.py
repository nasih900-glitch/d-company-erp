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
from uuid import UUID

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState

from app.core.logging import get_logger
from app.core.security import decode_token
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


@dataclass(frozen=True)
class _AuthenticatedSocket:
    company_id: str
    user_id: str
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
    company_id = claims.get("company_id")
    user_id = claims.get("sub")
    expires_at = claims.get("exp")
    if (
        not company_id
        or not user_id
        or isinstance(expires_at, bool)
        or not isinstance(expires_at, (int, float))
        or not math.isfinite(float(expires_at))
    ):
        return None
    return _AuthenticatedSocket(
        company_id=str(company_id),
        user_id=str(user_id),
        expires_at_epoch_seconds=float(expires_at),
    )


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
    company_id = auth.company_id

    manager.connect(UUID(company_id), websocket)
    try:
        await websocket.send_json({"type": "connected"})
        while True:
            seconds_until_expiry = auth.expires_at_epoch_seconds - time.time()
            if seconds_until_expiry <= 0:
                await websocket.close(code=4401, reason="access token expired")
                return
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
        log.exception("realtime.socket_error", company_id=company_id)
    finally:
        manager.disconnect(UUID(company_id), websocket)
        with contextlib.suppress(Exception):
            await websocket.close()
