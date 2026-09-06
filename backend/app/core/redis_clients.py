"""Shared Redis client construction for short, request-path operations.

Rate limiting is deliberately fail-closed, so a Redis outage must become a
prompt retryable response rather than tying up an API worker on the driver's
long defaults.  Keep these timeouts consistent for every request-path limiter.
"""

from __future__ import annotations

import asyncio
from typing import Protocol, cast

from redis.asyncio import Redis

REDIS_CONNECT_TIMEOUT_SECONDS = 1.0
REDIS_READ_TIMEOUT_SECONDS = 2.0
REDIS_CLOSE_TIMEOUT_SECONDS = 0.5


class RedisCloseable(Protocol):
    async def aclose(self) -> None: ...


def request_path_redis_client(redis_url: object) -> Redis:
    """Return a fail-fast Redis client for request-path coordination."""

    return cast(
        "Redis",
        Redis.from_url(
            str(redis_url),
            decode_responses=True,
            socket_connect_timeout=REDIS_CONNECT_TIMEOUT_SECONDS,
            socket_timeout=REDIS_READ_TIMEOUT_SECONDS,
            retry_on_timeout=False,
        ),
    )


def request_path_redis_binary_client(redis_url: object) -> Redis:
    """Return a fail-fast client that preserves ephemeral binary frame bytes."""

    return cast(
        "Redis",
        Redis.from_url(
            str(redis_url),
            decode_responses=False,
            socket_connect_timeout=REDIS_CONNECT_TIMEOUT_SECONDS,
            socket_timeout=REDIS_READ_TIMEOUT_SECONDS,
            retry_on_timeout=False,
        ),
    )


async def close_request_path_redis_client(client: RedisCloseable) -> None:
    """Bound best-effort pool disposal without corrupting the API outcome."""

    try:
        async with asyncio.timeout(REDIS_CLOSE_TIMEOUT_SECONDS):
            await client.aclose()
    except Exception:  # noqa: BLE001 - cleanup must never replace request outcome
        # The request's rate-limit decision is already known.  Cleanup failure
        # must neither turn a 429 into a 500 nor defeat the fail-fast timeout.
        return


__all__ = [
    "REDIS_CONNECT_TIMEOUT_SECONDS",
    "REDIS_CLOSE_TIMEOUT_SECONDS",
    "REDIS_READ_TIMEOUT_SECONDS",
    "close_request_path_redis_client",
    "request_path_redis_binary_client",
    "request_path_redis_client",
]
