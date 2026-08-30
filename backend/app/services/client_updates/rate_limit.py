"""Fail-closed, authenticated heartbeat throttling shared by all workers."""

from __future__ import annotations

import hashlib
from typing import TYPE_CHECKING

from redis.exceptions import RedisError

from app.core.config import get_settings
from app.core.errors import RateLimitError, ServiceUnavailableError
from app.core.logging import get_logger
from app.core.redis_clients import close_request_path_redis_client, request_path_redis_client

if TYPE_CHECKING:
    from uuid import UUID

log = get_logger(__name__)

_WINDOW_SECONDS = 60
_INCREMENT_WINDOW = """
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('TTL', KEYS[1])
return {count, ttl}
"""


def _principal_key(company_id: UUID, user_id: UUID) -> str:
    # Redis and logs never receive raw tenant/user identifiers in the key.
    digest = hashlib.sha256(f"{company_id}:{user_id}".encode("ascii")).hexdigest()
    return f"dcompany:client-heartbeat:principal:{digest}"


async def enforce_client_heartbeat_rate_limit(*, company_id: UUID, user_id: UUID) -> None:
    """Limit authenticated writes by server-derived principal, not client UUID."""

    settings = get_settings()
    client = request_path_redis_client(settings.redis_url)
    try:
        result = await client.eval(
            _INCREMENT_WINDOW,
            1,
            _principal_key(company_id, user_id),
            _WINDOW_SECONDS,
        )
        count, ttl = (int(result[0]), int(result[1]))
    except (RedisError, TypeError, ValueError, IndexError) as exc:
        log.error("client_heartbeat.rate_limit_unavailable", error=type(exc).__name__)
        raise ServiceUnavailableError(
            "Device status protection is temporarily unavailable; try again shortly."
        ) from exc
    finally:
        await close_request_path_redis_client(client)

    if count > settings.client_heartbeat_user_limit_per_minute:
        retry_after = max(1, ttl if ttl > 0 else _WINDOW_SECONDS)
        raise RateLimitError(
            "Too many device status updates were received. Wait briefly and retry.",
            details={
                "limit": settings.client_heartbeat_user_limit_per_minute,
                "window_seconds": _WINDOW_SECONDS,
                "retry_after_seconds": retry_after,
            },
            headers={"Retry-After": str(retry_after)},
        )


__all__ = ["enforce_client_heartbeat_rate_limit"]
