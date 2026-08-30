from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient

import app.main as main_module


class _Connection:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback) -> None:
        return None

    async def execute(self, _statement) -> None:
        if self.error:
            raise self.error


class _Engine:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error

    def connect(self) -> _Connection:
        return _Connection(self.error)


class _RedisClient:
    def __init__(
        self,
        error: Exception | None = None,
        close_error: Exception | None = None,
    ) -> None:
        self.error = error
        self.close_error = close_error
        self.closed = False

    async def ping(self) -> bool:
        if self.error:
            raise self.error
        return True

    async def aclose(self) -> None:
        self.closed = True
        if self.close_error:
            raise self.close_error


async def _get_readyz(
    monkeypatch,
    *,
    database_error=None,
    redis_error=None,
    redis_close_error=None,
):
    redis_client = _RedisClient(redis_error, redis_close_error)

    class _RedisFactory:
        @staticmethod
        def from_url(*_args, **_kwargs):
            return redis_client

    monkeypatch.setattr(main_module, "async_engine", _Engine(database_error))
    monkeypatch.setattr(main_module, "Redis", _RedisFactory)
    app = main_module.create_app()
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://test",
    ) as client:
        response = await client.get("/readyz")
    assert redis_client.closed is True
    return response


@pytest.mark.asyncio
async def test_readyz_requires_database_and_redis(monkeypatch) -> None:
    response = await _get_readyz(monkeypatch)

    assert response.status_code == 200
    assert response.json() == {
        "status": "ready",
        "checks": {"database": "ok", "redis": "ok"},
    }


@pytest.mark.asyncio
async def test_readyz_reports_each_failed_dependency(monkeypatch) -> None:
    response = await _get_readyz(
        monkeypatch,
        database_error=RuntimeError("db unavailable"),
        redis_error=RuntimeError("redis unavailable"),
    )

    assert response.status_code == 503
    assert response.json() == {
        "detail": {
            "status": "not_ready",
            "checks": {"database": "down", "redis": "down"},
        }
    }


@pytest.mark.asyncio
async def test_readyz_redis_cleanup_failure_cannot_override_dependency_result(
    monkeypatch,
) -> None:
    response = await _get_readyz(
        monkeypatch,
        redis_error=RuntimeError("redis unavailable"),
        redis_close_error=RuntimeError("pool cleanup failed"),
    )

    assert response.status_code == 503
    assert response.json()["detail"]["checks"]["redis"] == "down"
