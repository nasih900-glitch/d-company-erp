"""Redis-backed login throttling shared by all API workers."""

from __future__ import annotations

import hashlib

from fastapi import Request
from redis.asyncio import Redis
from redis.exceptions import RedisError

from app.core.client_ip import trusted_client_ip
from app.core.config import get_settings
from app.core.errors import RateLimitError, ServiceUnavailableError
from app.core.logging import get_logger

log = get_logger(__name__)

_INCREMENT_WINDOW = """
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
"""


def _request_ip(request: Request) -> str:
    return trusted_client_ip(request) or "unknown"


def _key(kind: str, value: str) -> str:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
    return f"dcompany:auth:login:{kind}:{digest}"


async def enforce_login_rate_limit(request: Request, email: str) -> None:
    settings = get_settings()
    client = Redis.from_url(str(settings.redis_url), decode_responses=True)
    try:
        ip_count = int(
            await client.eval(
                _INCREMENT_WINDOW,
                1,
                _key("ip", _request_ip(request)),
                60,
            )
        )
        identity_count = int(
            await client.eval(
                _INCREMENT_WINDOW,
                1,
                _key("identity", email),
                15 * 60,
            )
        )
    except RedisError as exc:
        log.error("auth.rate_limit_unavailable", error=str(exc))
        raise ServiceUnavailableError(
            "login protection is temporarily unavailable; try again shortly"
        ) from exc
    finally:
        await client.aclose()
    if (
        ip_count > settings.login_ip_limit_per_minute
        or identity_count > settings.login_identity_limit_per_15_minutes
    ):
        raise RateLimitError("too many login attempts; wait and try again")
