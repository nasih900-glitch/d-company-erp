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


async def _authenticate(websocket: WebSocket) -> tuple[str, str] | None:
    """Returns (company_id, user_id) as strings, or None if auth failed."""
    try:
        raw = await asyncio.wait_for(websocket.receive_text(), timeout=_AUTH_TIMEOUT_SECONDS)
    except (asyncio.TimeoutError, WebSocketDisconnect):
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
    if not company_id or not user_id:
        return None
    return str(company_id), str(user_id)


@router.websocket("")
async def realtime_socket(websocket: WebSocket) -> None:
    await websocket.accept()

    auth = await _authenticate(websocket)
    if auth is None:
        await websocket.close(code=4401, reason="unauthenticated")
        return
    company_id, _user_id = auth

    manager.connect(UUID(company_id), websocket)
    try:
        await websocket.send_json({"type": "connected"})
        while True:
            try:
                await asyncio.wait_for(websocket.receive_text(), timeout=_PING_INTERVAL_SECONDS)
                # Any client message (e.g. a pong) just resets the wait —
                # this endpoint doesn't need to act on client-sent content.
            except asyncio.TimeoutError:
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
