"""Permanent replay fence for actions retired by a verified data cleanup.

The one-time Code30 production cleanup removes test-only rows, including the
ordinary idempotency receipts that would normally make a retry harmless.  Its
durable audit receipt therefore retains the immutable action identity needed
to reject a delayed tablet replay after the cleanup has committed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING, Any
from uuid import UUID

from sqlalchemy import select

from app.core.errors import BusinessRuleError, IdempotencyConflict

if TYPE_CHECKING:
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


@dataclass(frozen=True, slots=True)
class CanonicalGamingCleanupReceipt:
    audit_id: int
    deleted_gaming_session_ids: tuple[UUID, ...]
    original_action_user_id: UUID


@dataclass(frozen=True, slots=True)
class _ValidatedCleanupReceipt:
    deleted_gaming_session_ids: tuple[UUID, ...]
    replay_fence_by_key: dict[str, dict[str, Any]]


def _canonical_uuid_list(value: object) -> tuple[UUID, ...] | None:
    if not isinstance(value, list) or not value:
        return None
    parsed: list[UUID] = []
    for raw in value:
        if not isinstance(raw, str):
            return None
        try:
            item = UUID(raw)
        except ValueError:
            return None
        if str(item) != raw:
            return None
        parsed.append(item)
    if len(set(parsed)) != len(parsed) or value != sorted(value):
        return None
    return tuple(parsed)


def _exact_keys(value: object, keys: set[str]) -> bool:
    return isinstance(value, dict) and set(value) == keys


def _structurally_valid_cleanup_receipt(receipt: object) -> _ValidatedCleanupReceipt | None:
    before = getattr(receipt, "before", None)
    after = getattr(receipt, "after", None)
    if not isinstance(before, dict) or not isinstance(after, dict):
        return None
    source_sha = after.get("source_git_sha")
    image_id = after.get("backend_image_id")
    if not _exact_keys(
        before,
        {
            "schema_revision",
            "state_fingerprint",
            "backup_sha256",
            "quarantine_evidence_sha256",
            "counts",
            "audit_log",
        },
    ) or not _exact_keys(
        after,
        {
            "source_git_sha",
            "backend_image_id",
            "executor",
            "executed_at",
            "deleted_counts",
            "deleted_shift_ids",
            "deleted_order_ids",
            "deleted_order_line_ids",
            "deleted_gaming_session_ids",
            "deleted_menu_item_ids",
            "retired_test_installation",
            "local_avd_quarantine",
            "deleted_idempotency_keys",
            "deleted_audit_ids",
            "replay_fence",
            "expected_post_counts",
        },
    ):
        return None
    if not isinstance(source_sha, str) or re.fullmatch(r"[0-9a-f]{40}", source_sha) is None:
        return None
    if not isinstance(image_id, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", image_id) is None:
        return None
    if before["schema_revision"] != "0078":
        return None
    if after["executor"] != f"install-on-vm:{source_sha}":
        return None
    if any(
        not isinstance(before.get(key), str) or re.fullmatch(r"[0-9a-f]{64}", before[key]) is None
        for key in ("state_fingerprint", "backup_sha256", "quarantine_evidence_sha256")
    ):
        return None
    if not isinstance(before["counts"], dict) or not isinstance(before["audit_log"], dict):
        return None
    if after["deleted_counts"] != {
        "audit_log": 37,
        "idempotency_keys": 10,
        "gaming_sessions": 5,
        "order_lines": 3,
        "orders": 3,
        "menu_items": 1,
        "shifts": 3,
    }:
        return None
    try:
        executed_at = datetime.fromisoformat(after["executed_at"].replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError):
        return None
    if executed_at.tzinfo is None or executed_at.utcoffset() is None:
        return None
    if (
        getattr(receipt, "actor_user_id", None) is None
        or getattr(receipt, "terminal_id", None) is None
    ):
        return None
    if getattr(receipt, "request_id", None) != "code30.1-production-trial-cleanup-20260920":
        return None
    if getattr(receipt, "client_action_id", None) != "production-trial-cleanup-20260920":
        return None
    if (
        getattr(receipt, "client_was_offline", None) is not None
        or getattr(receipt, "synced_at", None) is not None
    ):
        return None
    if getattr(receipt, "user_agent", None) != "cleanup-code30-production-trial-data/2":
        return None
    deleted = _canonical_uuid_list(after.get("deleted_gaming_session_ids"))
    if deleted is None or len(deleted) != 5:
        return None
    fence = after.get("replay_fence")
    if not isinstance(fence, list) or len(fence) != 13:
        return None
    entries: dict[str, dict[str, Any]] = {}
    ordered_keys: list[str] = []
    for raw in fence:
        if not _exact_keys(
            raw,
            {
                "action_type",
                "action_key",
                "request_hash",
                "user_id",
                "terminal_id",
                "source_entity_id",
            },
        ):
            return None
        action_key = raw["action_key"]
        if not isinstance(action_key, str) or not action_key:
            return None
        if raw["action_type"] not in {"idempotency", "shift_open"}:
            return None
        if (
            not isinstance(raw["request_hash"], str)
            or re.fullmatch(r"[0-9a-f]{64}", raw["request_hash"]) is None
        ):
            return None
        try:
            UUID(raw["user_id"])
            UUID(raw["terminal_id"])
            if raw["source_entity_id"] is not None:
                UUID(raw["source_entity_id"])
        except (TypeError, ValueError):
            return None
        entries[action_key] = raw
        ordered_keys.append(action_key)
    if ordered_keys != sorted(ordered_keys) or len(entries) != len(fence):
        return None
    idempotency_keys = [
        key for key, entry in entries.items() if entry["action_type"] == "idempotency"
    ]
    gaming_keys = {
        key
        for key in idempotency_keys
        if re.fullmatch(r"gaming-session-(start|stop):[0-9a-f-]{36}", key)
    }
    lifecycle_ids = {
        key.split(":", 1)[1] for key in gaming_keys if key.startswith("gaming-session-start:")
    }
    try:
        canonical_lifecycle_ids = {str(UUID(local_id)) for local_id in lifecycle_ids}
    except ValueError:
        return None
    if (
        len(idempotency_keys) != 10
        or len(gaming_keys) != 10
        or len(lifecycle_ids) != 5
        or lifecycle_ids != canonical_lifecycle_ids
        or any(f"gaming-session-stop:{local_id}" not in gaming_keys for local_id in lifecycle_ids)
        or after.get("deleted_idempotency_keys") != idempotency_keys
    ):
        return None
    for local_id in lifecycle_ids:
        start = entries[f"gaming-session-start:{local_id}"]
        stop = entries[f"gaming-session-stop:{local_id}"]
        if (
            start["source_entity_id"] is not None
            or stop["source_entity_id"] is not None
            or (start["user_id"], start["terminal_id"]) != (stop["user_id"], stop["terminal_id"])
        ):
            return None
    if sum(entry["action_type"] == "shift_open" for entry in entries.values()) != 3:
        return None
    return _ValidatedCleanupReceipt(deleted, entries)


async def require_canonical_gaming_cleanup_receipt(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    terminal_id: UUID,
    station_id: UUID,
    local_action_id: UUID,
    server_session_id: UUID,
    start_request_hash: str,
    stop_request_hash: str,
) -> CanonicalGamingCleanupReceipt:
    """Require the one canonical Code30.1 receipt and current absent target.

    Any duplicate or malformed matching receipt fails closed. This helper is
    intentionally separate from the existing replay-fence scan so the shipped
    replay behavior remains byte-for-byte unchanged.
    """
    from app.models import AuditLog, GamingSession, Station

    receipts = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == company_id,
                    AuditLog.action == _CLEANUP_ACTION,
                    AuditLog.entity_type == _CLEANUP_ENTITY_TYPE,
                    AuditLog.entity_id == _CLEANUP_ENTITY_ID,
                )
            )
        )
        .scalars()
        .all()
    )
    if len(receipts) != 1:
        raise BusinessRuleError(
            "The verified production cleanup receipt is missing or duplicated. Recovery is blocked."
        )
    receipt = receipts[0]
    validated = _structurally_valid_cleanup_receipt(receipt)
    if validated is None or server_session_id not in validated.deleted_gaming_session_ids:
        raise BusinessRuleError(
            "This Gaming session is not an exact target of the verified production cleanup."
        )
    start = validated.replay_fence_by_key.get(f"gaming-session-start:{local_action_id}")
    stop = validated.replay_fence_by_key.get(f"gaming-session-stop:{local_action_id}")
    original_user_id = start["user_id"] if start is not None else None
    expected_identity = (original_user_id, str(terminal_id))
    if (
        start is None
        or stop is None
        or start["action_type"] != "idempotency"
        or stop["action_type"] != "idempotency"
        or start["source_entity_id"] is not None
        or stop["source_entity_id"] is not None
        or (start["user_id"], start["terminal_id"]) != expected_identity
        or (stop["user_id"], stop["terminal_id"]) != expected_identity
        or start["request_hash"] != start_request_hash
        or stop["request_hash"] != stop_request_hash
    ):
        raise BusinessRuleError(
            "The local Gaming lifecycle does not match the cleanup receipt action evidence."
        )
    station = (
        await session.execute(
            select(Station.id).where(
                Station.id == station_id,
                Station.company_id == company_id,
                Station.branch_id == branch_id,
            )
        )
    ).scalar_one_or_none()
    if station is None:
        raise BusinessRuleError("The reported Gaming station is outside the current shop scope.")
    existing = (
        await session.execute(select(GamingSession.id).where(GamingSession.id == server_session_id))
    ).scalar_one_or_none()
    if existing is not None:
        raise BusinessRuleError(
            "The Gaming session still exists on the server; cleanup recovery is unsafe."
        )
    return CanonicalGamingCleanupReceipt(
        receipt.id,
        validated.deleted_gaming_session_ids,
        UUID(original_user_id),
    )


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


# Versioned trial-cleanup receipts (v2). These rows deliberately avoid every
# identity the Code30.2 post-cleanup verifier treats as a cleanup receipt
# candidate, because that installer gate requires exactly the one Code30.1
# receipt above.
VERSIONED_CLEANUP_ACTION = "verified_trial_cleanup"
VERSIONED_CLEANUP_ENTITY_TYPE = "TrialCleanupReceipt"
VERSIONED_CLEANUP_RECEIPT_VERSION = 2
_VERSIONED_CLEANUP_ID = re.compile(r"[a-z0-9][a-z0-9.-]{7,63}")
_V1_RECEIPT_IDENTITIES = frozenset(
    {
        _CLEANUP_ENTITY_ID,
        "code30.1-production-trial-cleanup-20260920",
        "production-trial-cleanup-20260920",
    }
)
_MAX_ACTION_KEY_LENGTH = 160


def _canonical_uuid_text(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(UUID(value)) == value
    except ValueError:
        return False


def _timestamp_with_zone(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.utcoffset() is not None


def _hex(value: object, length: int) -> bool:
    return isinstance(value, str) and re.fullmatch(f"[0-9a-f]{{{length}}}", value) is not None


def _versioned_shift_opening_fence(receipt: object) -> dict[str, dict[str, Any]] | None:
    """Return the exact shift-opening fence of one valid v2 receipt, else None."""
    cleanup_id = getattr(receipt, "entity_id", None)
    before = getattr(receipt, "before", None)
    after = getattr(receipt, "after", None)
    if (
        getattr(receipt, "action", None) != VERSIONED_CLEANUP_ACTION
        or getattr(receipt, "entity_type", None) != VERSIONED_CLEANUP_ENTITY_TYPE
        or not isinstance(cleanup_id, str)
        or _VERSIONED_CLEANUP_ID.fullmatch(cleanup_id) is None
        or cleanup_id in _V1_RECEIPT_IDENTITIES
        or getattr(receipt, "request_id", None) != cleanup_id
        or getattr(receipt, "client_action_id", None) is not None
        or getattr(receipt, "client_was_offline", None) is not None
        or getattr(receipt, "synced_at", None) is not None
        or getattr(receipt, "actor_user_id", None) is None
        or getattr(receipt, "terminal_id", None) is None
    ):
        return None
    if not _exact_keys(before, {"schema_revision", "state_fingerprint", "backup_sha256"}):
        return None
    if (
        not isinstance(before["schema_revision"], str)
        or re.fullmatch(r"[0-9]{4}", before["schema_revision"]) is None
        or not _hex(before["state_fingerprint"], 64)
        or not _hex(before["backup_sha256"], 64)
    ):
        return None
    if not _exact_keys(
        after,
        {
            "receipt_version",
            "cleanup_id",
            "source_git_sha",
            "executor",
            "executed_at",
            "deleted_counts",
            "deleted_shift_ids",
            "replay_fence",
            "evidence",
        },
    ):
        return None
    executor = after["executor"]
    if (
        type(after["receipt_version"]) is not int
        or after["receipt_version"] != VERSIONED_CLEANUP_RECEIPT_VERSION
        or after["cleanup_id"] != cleanup_id
        or not _hex(after["source_git_sha"], 40)
        or not isinstance(executor, str)
        or not 1 <= len(executor) <= 100
        or not _timestamp_with_zone(after["executed_at"])
        or not isinstance(after["evidence"], dict)
    ):
        return None
    counts = after["deleted_counts"]
    if not isinstance(counts, dict) or any(
        not isinstance(name, str) or type(count) is not int or count < 0
        for name, count in counts.items()
    ):
        return None
    deleted_shift_ids = _canonical_uuid_list(after["deleted_shift_ids"])
    if deleted_shift_ids is None or counts.get("shifts") != len(deleted_shift_ids):
        return None
    deleted_shift_texts = {str(shift_id) for shift_id in deleted_shift_ids}

    fence = after["replay_fence"]
    if not isinstance(fence, list):
        return None
    entries: dict[str, dict[str, Any]] = {}
    ordered_keys: list[str] = []
    fenced_sources: set[str] = set()
    for raw in fence:
        if not _exact_keys(
            raw,
            {
                "action_type",
                "action_key",
                "request_hash",
                "user_id",
                "terminal_id",
                "source_entity_id",
            },
        ):
            return None
        action_key = raw["action_key"]
        source = raw["source_entity_id"]
        if (
            raw["action_type"] != "shift_open"
            or not isinstance(action_key, str)
            or not 1 <= len(action_key) <= _MAX_ACTION_KEY_LENGTH
            or action_key != action_key.strip()
            or not _hex(raw["request_hash"], 64)
            or not _canonical_uuid_text(raw["user_id"])
            or not _canonical_uuid_text(raw["terminal_id"])
            or source not in deleted_shift_texts
            or source in fenced_sources
        ):
            return None
        fenced_sources.add(source)
        entries[action_key] = raw
        ordered_keys.append(action_key)
    if ordered_keys != sorted(ordered_keys) or len(entries) != len(fence):
        return None
    return entries


async def refuse_versioned_shift_opening_replay(
    session: AsyncSession,
    *,
    company_id: UUID,
    action_key: str,
    request_hash: str | None,
    user_id: UUID | None,
    terminal_id: UUID | None,
) -> None:
    """Refuse a keyed shift opening retired by any verified v2 cleanup receipt.

    Every row claiming the v2 identity must validate. A malformed or duplicated
    receipt blocks keyed shift openings instead of silently dropping a fence.
    """
    from app.models.audit import AuditLog  # local import avoids model cycles

    receipts = (
        (
            await session.execute(
                select(AuditLog)
                .where(
                    AuditLog.company_id == company_id,
                    (AuditLog.action == VERSIONED_CLEANUP_ACTION)
                    | (AuditLog.entity_type == VERSIONED_CLEANUP_ENTITY_TYPE),
                )
                .order_by(AuditLog.id)
            )
        )
        .scalars()
        .all()
    )
    if not receipts:
        return

    fences: list[dict[str, dict[str, Any]]] = []
    cleanup_ids: set[str] = set()
    for receipt in receipts:
        fence = _versioned_shift_opening_fence(receipt)
        cleanup_id = getattr(receipt, "entity_id", None)
        if fence is None or cleanup_id in cleanup_ids:
            raise BusinessRuleError(
                "A trial-cleanup receipt is malformed or duplicated, so saved shift "
                "openings are blocked. Nothing was changed; ask an owner to review "
                "the cleanup receipt."
            )
        cleanup_ids.add(cleanup_id)
        fences.append(fence)

    for fence in fences:
        entry = fence.get(action_key)
        if entry is None:
            continue
        identity_matches = (
            entry["request_hash"] == request_hash
            and entry["user_id"] == _identity_text(user_id)
            and entry["terminal_id"] == _identity_text(terminal_id)
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
