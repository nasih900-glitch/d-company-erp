"""Permanent replay fence for actions retired by a verified data cleanup.

The one-time Code30 production cleanup removes test-only rows, including the
ordinary idempotency receipts that would normally make a retry harmless.  Its
durable audit receipt therefore retains the immutable action identity needed
to reject a delayed tablet replay after the cleanup has committed.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from sqlalchemy import select

from app.core.errors import IdempotencyConflict

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


_CLEANUP_ACTION = "production_trial_cleanup"
_CLEANUP_ENTITY_TYPE = "ReleaseCleanup"
_CLEANUP_ENTITY_ID = "code30.1-20260920"
_RETIRED_ACTION_PREFIXES = (
    "gaming-session-start:",
    "gaming-session-stop:",
    "shift-open:",
)


def _identity_text(value: UUID | None) -> str | None:
    return str(value) if value is not None else None


async def refuse_retired_action_replay(
    session: AsyncSession,
    *,
    action_key: str,
    request_hash: str | None,
    user_id: UUID | None,
    terminal_id: UUID | None,
) -> None:
    """Raise when ``action_key`` was retired by the durable cleanup receipt.

    The key alone is sufficient to fence a replay.  The other immutable fields
    are compared only to distinguish an exact delayed retry from a conflicting
    attempt in diagnostic details; neither form may recreate deleted data.
    """
    from app.models.audit import AuditLog  # local imports avoid model cycles
    from app.models.user import User

    # This cleanup can contain only the three reviewed durable-action families.
    # Avoid adding an audit lookup to unrelated idempotent business operations.
    if user_id is None or not action_key.startswith(_RETIRED_ACTION_PREFIXES):
        return

    user_company_id = select(User.company_id).where(User.id == user_id).scalar_subquery()

    receipts = (
        (
            await session.execute(
                select(AuditLog.after)
                .where(
                    AuditLog.action == _CLEANUP_ACTION,
                    AuditLog.entity_type == _CLEANUP_ENTITY_TYPE,
                    AuditLog.entity_id == _CLEANUP_ENTITY_ID,
                    AuditLog.company_id == user_company_id,
                )
                .order_by(AuditLog.id.desc())
            )
        )
        .scalars()
        .all()
    )

    # Inspect every matching receipt. A malformed or duplicate later receipt
    # must never shadow the original valid replay fence.
    for receipt in receipts:
        if not isinstance(receipt, dict):
            continue

        fence = receipt.get("replay_fence")
        if not isinstance(fence, list):
            continue

        for raw_entry in fence:
            if not isinstance(raw_entry, dict) or raw_entry.get("action_key") != action_key:
                continue
            identity_matches = (
                raw_entry.get("request_hash") == request_hash
                and raw_entry.get("user_id") == _identity_text(user_id)
                and raw_entry.get("terminal_id") == _identity_text(terminal_id)
            )
            raise IdempotencyConflict(
                "This saved action was permanently retired during verified test-data "
                "cleanup and cannot be replayed.",
                details={
                    "key": action_key,
                    "issue": "retired_cleanup_action",
                    "identity_matches_retired_action": identity_matches,
                },
            )
