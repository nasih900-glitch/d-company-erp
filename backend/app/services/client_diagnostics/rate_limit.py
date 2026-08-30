"""Fail-closed event-volume limiting for authenticated diagnostic uploads."""

from __future__ import annotations

import hashlib
from typing import TYPE_CHECKING

from redis.asyncio import Redis
from redis.exceptions import RedisError

from app.core.config import get_settings
from app.core.errors import RateLimitError, ServiceUnavailableError
from app.core.logging import get_logger

if TYPE_CHECKING:
    from uuid import UUID

log = get_logger(__name__)

_WINDOW_SECONDS = 60
_INCREMENT_WINDOW = """
local count = redis.call('INCRBY', KEYS[1], ARGV[1])
if count == tonumber(ARGV[1]) then
  redis.call('EXPIRE', KEYS[1], ARGV[2])
end
local ttl = redis.call('TTL', KEYS[1])
return {count, ttl}
"""


def _principal_key(company_id: UUID, user_id: UUID) -> str:
    digest = hashlib.sha256(f"{company_id}:{user_id}".encode("ascii")).hexdigest()
    return f"dcompany:client-diagnostics:principal:{digest}"


async def enforce_client_diagnostic_rate_limit(
    *,
    company_id: UUID,
    user_id: UUID,
    event_count: int,
) -> None:
    """Limit authenticated event volume by server-derived principal."""
    if event_count < 1 or event_count > 25:
        raise ValueError("diagnostic event count must be between 1 and 25")

    settings = get_settings()
    client = Redis.from_url(str(settings.redis_url), decode_responses=True)
    try:
        result = await client.eval(
            _INCREMENT_WINDOW,
            1,
            _principal_key(company_id, user_id),
            event_count,
            _WINDOW_SECONDS,
        )
        count, ttl = int(result[0]), int(result[1])
    except (RedisError, TypeError, ValueError, IndexError) as exc:
        log.error("client_diagnostics.rate_limit_unavailable", error=type(exc).__name__)
        raise ServiceUnavailableError(
            "Diagnostic delivery protection is temporarily unavailable; saved reports "
            "remain on this device and will retry later."
        ) from exc
    finally:
        await client.aclose()

    limit = settings.client_diagnostics_user_event_limit_per_minute
    if count > limit:
        retry_after = max(1, ttl if ttl > 0 else _WINDOW_SECONDS)
        raise RateLimitError(
            "Too many diagnostic events were received. Saved reports will retry shortly.",
            details={
                "limit": limit,
                "window_seconds": _WINDOW_SECONDS,
                "retry_after_seconds": retry_after,
            },
            headers={"Retry-After": str(retry_after)},
        )


__all__ = ["enforce_client_diagnostic_rate_limit"]
