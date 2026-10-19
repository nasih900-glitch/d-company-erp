"""Short-lived private visible-ID snapshots; no Audit payload enters Redis."""

from __future__ import annotations

import json
from uuid import UUID, uuid4

from redis.exceptions import RedisError

from app.core.config import get_settings
from app.core.errors import BusinessRuleError, ServiceUnavailableError
from app.core.redis_clients import close_request_path_redis_client, request_path_redis_client

SNAPSHOT_TTL_SECONDS = 600
MAX_SNAPSHOT_ENTRIES = 50_000
MAX_OWNER_SNAPSHOTS = 8


def _key(company_id: UUID, user_id: UUID, snapshot_id: str) -> str:
    return f"dcompany:audit-snapshot:{company_id}:{user_id}:{snapshot_id}"


async def save_snapshot(
    *, company_id: UUID, user_id: UUID, fingerprint: str, ids: list[int]
) -> str:
    if len(ids) > MAX_SNAPSHOT_ENTRIES:
        raise BusinessRuleError(
            "Too many Audit matches. Narrow the dates or filters before loading history."
        )
    identity = uuid4().hex
    key = _key(company_id, user_id, identity)
    registry = _key(company_id, user_id, "recent")
    client = request_path_redis_client(get_settings().redis_url)
    try:
        await client.set(
            key, json.dumps({"filters": fingerprint, "ids": ids}), ex=SNAPSHOT_TTL_SECONDS
        )
        # Bounded memory per protected owner. Expired/evicted cursors explicitly
        # require refresh rather than silently switching to a different history.
        await client.lpush(registry, key)
        await client.expire(registry, SNAPSHOT_TTL_SECONDS)
        while await client.llen(registry) > MAX_OWNER_SNAPSHOTS:
            expired = await client.rpop(registry)
            if expired:
                await client.delete(expired)
    except RedisError as exc:
        raise ServiceUnavailableError(
            "Audit history snapshots are temporarily unavailable. Retry shortly."
        ) from exc
    finally:
        await close_request_path_redis_client(client)
    return identity


async def load_snapshot(
    *, company_id: UUID, user_id: UUID, fingerprint: str, snapshot_id: str
) -> list[int]:
    try:
        if len(snapshot_id) != 32 or UUID(hex=snapshot_id).hex != snapshot_id:
            raise ValueError("snapshot identity")
    except (ValueError, TypeError) as exc:
        raise BusinessRuleError("Invalid Audit snapshot. Refresh the list.") from exc
    client = request_path_redis_client(get_settings().redis_url)
    try:
        payload = await client.get(_key(company_id, user_id, snapshot_id))
    except RedisError as exc:
        raise ServiceUnavailableError(
            "Audit history snapshots are temporarily unavailable. Retry shortly."
        ) from exc
    finally:
        await close_request_path_redis_client(client)
    try:
        value = json.loads(payload) if payload else None
        if not value or value.get("filters") != fingerprint:
            raise ValueError("expired snapshot")
        ids = value["ids"]
        if (
            not isinstance(ids, list)
            or len(ids) > MAX_SNAPSHOT_ENTRIES
            or any(type(identity) is not int or identity < 1 for identity in ids)
            or ids != sorted(set(ids), reverse=True)
        ):
            raise ValueError("invalid snapshot")
        return ids
    except (ValueError, KeyError, TypeError) as exc:
        raise BusinessRuleError(
            "Audit history snapshot expired. Refresh to load current activity."
        ) from exc
