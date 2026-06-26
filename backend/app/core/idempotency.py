"""Idempotency store — DB-backed table lookup.

Used by services that need transactional guarantees alongside the
business write (e.g. POS order create). Middleware does NOT short-circuit
duplicate requests on its own because we need the lookup to happen in
the same DB transaction as the write.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import IdempotencyConflict


async def check_or_reserve(
    session: AsyncSession,
    *,
    key: str,
    request_hash: str,
    user_id: UUID | None = None,
    terminal_id: UUID | None = None,
) -> dict[str, Any] | None:
    """Return the prior response if the key+hash matches; raise on mismatch; else reserve.

    Returns:
        dict with `status_code` and `body` if this is a replay; None if first-time.
    """
    from app.models.idempotency_key import IdempotencyKey  # local import to avoid cycles

    stmt = select(IdempotencyKey).where(IdempotencyKey.key == key).with_for_update()
    existing = (await session.execute(stmt)).scalar_one_or_none()
    if existing is not None:
        if existing.request_hash != request_hash:
            raise IdempotencyConflict(
                "Idempotency-Key reused with different payload",
                details={"key": key},
            )
        if existing.user_id != user_id or existing.terminal_id != terminal_id:
            raise IdempotencyConflict(
                "Idempotency-Key reused by a different user or terminal",
                details={"key": key},
            )
        if existing.response_status is None or existing.response_body is None:
            raise IdempotencyConflict(
                "Idempotency-Key is already reserved but has no stored response",
                details={"key": key},
            )
        return {"status_code": existing.response_status, "body": existing.response_body}

    session.add(
        IdempotencyKey(
            key=key,
            user_id=user_id,
            terminal_id=terminal_id,
            request_hash=request_hash,
            response_status=None,
            response_body=None,
            created_at=datetime.now(timezone.utc),
        )
    )
    try:
        await session.flush()
    except IntegrityError as exc:
        raise IdempotencyConflict(
            "Idempotency-Key is already being processed",
            details={"key": key},
        ) from exc
    return None


async def store_response(
    session: AsyncSession,
    *,
    key: str,
    status_code: int,
    body: dict[str, Any],
) -> None:
    from app.models.idempotency_key import IdempotencyKey

    stmt = select(IdempotencyKey).where(IdempotencyKey.key == key)
    row = (await session.execute(stmt)).scalar_one()
    row.response_status = status_code
    row.response_body = body
