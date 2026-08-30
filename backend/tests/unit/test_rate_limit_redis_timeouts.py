"""Fail-fast/fail-closed contracts shared by request-path Redis limiters."""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest
from redis.exceptions import TimeoutError as RedisTimeoutError
from starlette.requests import Request

from app.core import redis_clients
from app.core.errors import ServiceUnavailableError
from app.services.auth import rate_limit as auth_rate_limit


class _TimeoutRedis:
    def __init__(self) -> None:
        self.closed = False

    async def eval(self, *_args):
        raise RedisTimeoutError("timed out")

    async def aclose(self) -> None:
        self.closed = True


def test_request_path_client_has_consistent_bounded_timeouts(monkeypatch) -> None:
    captured: dict[str, object] = {}
    sentinel = object()

    def from_url(url: str, **kwargs):
        captured["url"] = url
        captured.update(kwargs)
        return sentinel

    monkeypatch.setattr(redis_clients.Redis, "from_url", from_url)

    client = redis_clients.request_path_redis_client("redis://cache:6379/0")

    assert client is sentinel
    assert captured == {
        "url": "redis://cache:6379/0",
        "decode_responses": True,
        "socket_connect_timeout": 1.0,
        "socket_timeout": 2.0,
        "retry_on_timeout": False,
    }


@pytest.mark.asyncio
async def test_client_cleanup_timeout_does_not_override_request_result() -> None:
    class _HangingClose:
        async def aclose(self) -> None:
            await asyncio.Event().wait()

    started = asyncio.get_running_loop().time()
    await redis_clients.close_request_path_redis_client(_HangingClose())
    elapsed = asyncio.get_running_loop().time() - started

    assert elapsed < 1.0


@pytest.mark.asyncio
async def test_client_cleanup_error_does_not_override_request_result() -> None:
    class _BrokenClose:
        async def aclose(self) -> None:
            raise RuntimeError("event loop is closing")

    await redis_clients.close_request_path_redis_client(_BrokenClose())


@pytest.mark.asyncio
async def test_login_rate_limit_timeout_fails_closed_as_503(monkeypatch) -> None:
    fake = _TimeoutRedis()
    monkeypatch.setattr(
        auth_rate_limit,
        "get_settings",
        lambda: SimpleNamespace(
            redis_url="redis://localhost:6379/0",
            login_ip_limit_per_minute=30,
            login_identity_limit_per_15_minutes=10,
        ),
    )
    monkeypatch.setattr(auth_rate_limit, "request_path_redis_client", lambda *_args: fake)
    request = Request(
        {
            "type": "http",
            "method": "POST",
            "path": "/api/v1/auth/login",
            "headers": [],
            "client": ("192.0.2.10", 40123),
            "server": ("test", 80),
            "scheme": "http",
            "query_string": b"",
        }
    )

    with pytest.raises(ServiceUnavailableError, match="login protection") as captured:
        await auth_rate_limit.enforce_login_rate_limit(request, "user@example.test")

    assert captured.value.status_code == 503
    assert fake.closed is True
