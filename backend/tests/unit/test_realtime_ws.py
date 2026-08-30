"""Real-time WebSocket push — auth handshake (no DB needed).

See tests/integration/test_realtime_ws_broadcast.py for the
broadcast-on-write test, which needs a real Postgres connection.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.core.security import issue_access_token
from app.main import create_app


def test_ws_rejects_connection_without_valid_token():
    app = create_app()
    with TestClient(app) as client, client.websocket_connect("/api/v1/ws") as ws:
        ws.send_text("not json")
        # Server closes with 4401 after a bad first frame; the client
        # library surfaces that as the socket closing on the next recv.
        with pytest.raises(WebSocketDisconnect) as exc_info:
            ws.receive_json()
        assert exc_info.value.code == 4401


def test_ws_rejects_stale_or_garbage_token():
    app = create_app()
    with TestClient(app) as client, client.websocket_connect("/api/v1/ws") as ws:
        ws.send_text('{"token": "not-a-real-jwt"}')
        with pytest.raises(WebSocketDisconnect) as exc_info:
            ws.receive_json()
        assert exc_info.value.code == 4401


def test_ws_closes_when_the_authenticated_access_token_expires():
    app = create_app()
    token = issue_access_token(
        user_id=uuid4(),
        company_id=uuid4(),
        roles=["staff"],
        extra={"exp": datetime.now(UTC) + timedelta(seconds=1)},
    )

    with TestClient(app) as client, client.websocket_connect("/api/v1/ws") as ws:
        ws.send_json({"token": token})
        assert ws.receive_json() == {"type": "connected"}

        with pytest.raises(WebSocketDisconnect) as exc_info:
            ws.receive_json()
        assert exc_info.value.code == 4401
        assert exc_info.value.reason == "access token expired"
