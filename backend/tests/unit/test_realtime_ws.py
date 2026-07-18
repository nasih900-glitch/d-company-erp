"""Real-time WebSocket push — auth handshake (no DB needed).

See tests/integration/test_realtime_ws_broadcast.py for the
broadcast-on-write test, which needs a real Postgres connection.
"""

from __future__ import annotations

import pytest
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import create_app


def test_ws_rejects_connection_without_valid_token():
    app = create_app()
    with TestClient(app) as client:
        with client.websocket_connect("/api/v1/ws") as ws:
            ws.send_text("not json")
            # Server closes with 4401 after a bad first frame; the client
            # library surfaces that as the socket closing on the next recv.
            with pytest.raises(WebSocketDisconnect) as exc_info:
                ws.receive_json()
            assert exc_info.value.code == 4401


def test_ws_rejects_stale_or_garbage_token():
    app = create_app()
    with TestClient(app) as client:
        with client.websocket_connect("/api/v1/ws") as ws:
            ws.send_text('{"token": "not-a-real-jwt"}')
            with pytest.raises(WebSocketDisconnect) as exc_info:
                ws.receive_json()
            assert exc_info.value.code == 4401
