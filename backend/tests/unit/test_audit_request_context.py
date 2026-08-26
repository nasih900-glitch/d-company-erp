from datetime import UTC, datetime
from uuid import uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.requests import Request
from starlette.responses import Response

from app.api.v1.auth.router import _auth_audit_entity_id
from app.core.client_ip import audit_user_agent, trusted_client_ip
from app.core.middleware import RequestContextMiddleware, _printable_ascii_header
from app.models import AuditLog
from app.services.audit.recorder import (
    _audit_context_fields,
    _enrich_manual_audit_row,
    actor_ctx,
    clear_actor,
    clear_request_context,
    request_ctx,
    set_actor,
    set_request_context,
)


def _clear_context() -> None:
    clear_actor()
    clear_request_context()


def test_offline_audit_context_keeps_server_sync_time_and_client_provenance() -> None:
    _clear_context()
    company_id = uuid4()
    user_id = uuid4()
    terminal_id = uuid4()
    client_time = datetime(2026, 8, 25, 12, 30, tzinfo=UTC)
    before = datetime.now(UTC)
    set_actor(
        user_id=user_id,
        company_id=company_id,
        terminal_id=terminal_id,
        ip="192.0.2.4",
        user_agent="D Company ERP test",
    )
    set_request_context(
        request_id="request-123",
        client_platform="android",
        client_version_code=7,
        client_action_id="sale-local-42",
        client_reported_at=client_time,
        client_was_offline=True,
    )

    row = AuditLog(
        actor_user_id=user_id,
        company_id=company_id,
        action="create",
        entity_type="Payment",
        entity_id=str(uuid4()),
        before=None,
        after={"reason": "offline till recovery"},
    )
    _enrich_manual_audit_row(row)

    assert row.terminal_id == terminal_id
    assert row.request_id == "request-123"
    assert row.client_platform == "android"
    assert row.client_version_code == 7
    assert row.client_action_id == "sale-local-42"
    assert row.client_reported_at == client_time
    assert row.client_was_offline is True
    assert row.synced_at is not None
    assert before <= row.synced_at <= datetime.now(UTC)
    assert row.reason == "offline till recovery"
    assert row.ip == "192.0.2.4"
    _clear_context()


def test_online_request_is_distinct_from_background_or_legacy_work() -> None:
    _clear_context()
    set_request_context(
        request_id="online-request",
        client_platform="web",
        client_version_code=None,
        client_action_id=None,
        client_reported_at=None,
        client_was_offline=False,
    )
    online = _audit_context_fields()
    assert online["client_was_offline"] is False
    assert online["synced_at"] is None

    clear_request_context()
    background = _audit_context_fields()
    assert background["client_was_offline"] is None
    assert background["request_id"] is None
    _clear_context()


def test_manual_audit_note_is_promoted_to_reason() -> None:
    _clear_context()
    row = AuditLog(
        actor_user_id=None,
        company_id=uuid4(),
        action="create",
        entity_type="ManualCollection",
        entity_id=str(uuid4()),
        before=None,
        after={"note": "Owner approved opening balance correction"},
    )

    _enrich_manual_audit_row(row)

    assert row.reason == "Owner approved opening balance correction"


def test_request_context_middleware_normalizes_and_exposes_audit_headers() -> None:
    app = FastAPI()
    app.add_middleware(RequestContextMiddleware)

    @app.get("/probe")
    async def probe() -> dict:
        return dict(request_ctx.get() or {})

    with TestClient(app) as client:
        response = client.get(
            "/probe",
            headers={
                "X-Request-Id": "mobile-request-9",
                "X-Client-Platform": "android",
                "X-Client-Version-Code": "12",
                "X-Client-Action-Id": "local-order-9",
                "X-Offline-Captured": "true",
                "X-Client-Occurred-At": "2026-08-25T18:10:00+05:30",
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert response.headers["X-Request-Id"] == "mobile-request-9"
    assert body["request_id"] == "mobile-request-9"
    assert body["client_platform"] == "android"
    assert body["client_version_code"] == 12
    assert body["client_action_id"] == "local-order-9"
    assert body["client_was_offline"] is True
    assert body["client_reported_at"] == "2026-08-25T12:40:00Z"
    assert body["synced_at"] is not None


def test_request_context_rejects_unsafe_correlation_and_naive_device_time() -> None:
    app = FastAPI()
    app.add_middleware(RequestContextMiddleware)

    @app.get("/probe")
    async def probe() -> dict:
        return dict(request_ctx.get() or {})

    with TestClient(app) as client:
        response = client.get(
            "/probe",
            headers={
                "X-Request-Id": "x" * 65,
                "X-Client-Version-Code": str(2**63),
                "X-Client-Action-Id": "a" * 101,
                "Idempotency-Key": "safe-action-id",
                "X-Client-Occurred-At": "2026-08-25T12:00:00",
            },
        )

    body = response.json()
    assert body["request_id"] != "x" * 65
    assert len(body["request_id"]) == 36
    assert body["client_platform"] == "web"
    assert body["client_version_code"] is None
    assert body["client_action_id"] == "safe-action-id"
    assert body["client_reported_at"] is None
    assert body["client_was_offline"] is False


def test_correlation_header_only_accepts_visible_ascii() -> None:
    assert _printable_ascii_header("trace-123:/._", max_length=64) == "trace-123:/._"
    assert _printable_ascii_header("trace\x7f", max_length=64) is None
    assert _printable_ascii_header("trace-é", max_length=64) is None
    assert _printable_ascii_header("trace id", max_length=64) is None


def test_trusted_client_metadata_ignores_spoofed_forwarding_header_and_is_bounded() -> None:
    request = Request(
        {
            "type": "http",
            "method": "GET",
            "scheme": "https",
            "path": "/",
            "raw_path": b"/",
            "query_string": b"",
            "headers": [
                (b"x-forwarded-for", b"198.51.100.99"),
                (b"user-agent", b"u" * 600),
            ],
            "client": ("203.0.113.7", 443),
            "server": ("test", 443),
        }
    )

    assert trusted_client_ip(request) == "203.0.113.7"
    assert audit_user_agent(request) == "u" * 500


def test_long_unknown_login_identity_fits_audit_entity_id() -> None:
    email = f"{'a' * 240}@example.com"
    entity_id = _auth_audit_entity_id(email, None)

    assert len(entity_id) == 64
    assert entity_id.startswith("email-sha256:")
    assert entity_id == _auth_audit_entity_id(email.upper(), None)


@pytest.mark.asyncio
async def test_request_context_is_cleared_before_and_after_a_failed_request() -> None:
    async def unused_app(scope, receive, send) -> None:
        raise AssertionError("dispatch test supplies call_next directly")

    middleware = RequestContextMiddleware(unused_app)
    request = Request(
        {
            "type": "http",
            "method": "POST",
            "scheme": "https",
            "path": "/probe",
            "raw_path": b"/probe",
            "query_string": b"",
            "headers": [(b"x-request-id", b"fresh-request")],
            "client": ("203.0.113.8", 443),
            "server": ("test", 443),
        }
    )
    set_actor(user_id=uuid4(), company_id=uuid4())
    set_request_context(
        request_id="stale-request",
        client_platform="web",
        client_version_code=None,
        client_action_id=None,
        client_reported_at=None,
        client_was_offline=False,
    )

    async def fail_after_asserting_fresh_context(_request: Request) -> Response:
        assert actor_ctx.get() is None
        assert (request_ctx.get() or {})["request_id"] == "fresh-request"
        raise RuntimeError("probe failure")

    with pytest.raises(RuntimeError, match="probe failure"):
        await middleware.dispatch(request, fail_after_asserting_fresh_context)

    assert actor_ctx.get() is None
    assert request_ctx.get() is None
