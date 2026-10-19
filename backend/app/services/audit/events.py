"""Deliberate business-operation evidence, committed with its source transaction."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from uuid import UUID

from app.models import AuditLog


def record_operation(
    session: Any,
    *,
    company_id: UUID,
    actor_user_id: UUID | None,
    action: str,
    entity_type: str,
    entity_id: UUID | str,
    before: dict | None = None,
    after: dict | None = None,
    reason: str | None = None,
    terminal_id: UUID | None = None,
    operation_id: str | None = None,
    source_reference: str | None = None,
    target_user_id: UUID | None = None,
) -> AuditLog:
    row = AuditLog(
        company_id=company_id,
        actor_user_id=actor_user_id,
        action=action,
        entity_type=entity_type,
        entity_id=str(entity_id),
        before=before,
        after=after,
        reason=reason,
        terminal_id=terminal_id,
        operation_id=operation_id,
        source_reference=source_reference,
        target_user_id=target_user_id,
    )
    session.add(row)
    return row
