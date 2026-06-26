"""Audit log writer.

Every mutating service call should pass through `record_audit(...)`.
For the scaffold, this writes to structlog; the DB-backed writer is
wired in the audit migration.
"""

from __future__ import annotations

from typing import Any
from uuid import UUID

from app.core.logging import get_logger

log = get_logger("audit")


async def record_audit(
    *,
    actor_id: UUID,
    company_id: UUID,
    action: str,
    entity_type: str,
    entity_id: UUID | str,
    before: dict[str, Any] | None = None,
    after: dict[str, Any] | None = None,
    ip: str | None = None,
    user_agent: str | None = None,
) -> None:
    log.info(
        "audit.record",
        actor_id=str(actor_id),
        company_id=str(company_id),
        action=action,
        entity_type=entity_type,
        entity_id=str(entity_id),
        before=before,
        after=after,
        ip=ip,
        user_agent=user_agent,
    )
    # Future: INSERT INTO audit_log via the request's AsyncSession.
