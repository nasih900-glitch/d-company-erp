#!/usr/bin/env python3
"""Verify the durable state that permits the retained Code30.1 outbox row.

The production cleanup intentionally preserves one historical installation row
whose saved-action counter is still one.  Future installers may accept that
otherwise-blocking counter only when the database still contains the exact
cleanup receipt, replay fence and retained evidence created by the reviewed
Code30.2 cleanup.
"""

from __future__ import annotations

import json
import re
import sys
from datetime import datetime
from typing import Any
from uuid import UUID


EXPECTED_COMPANY_ID = "8f323fba-4358-45fe-9d3b-a8e0fae52993"
EXPECTED_ACTOR_USER_ID = "7016c42c-11c9-48fa-a30a-5d66a8c970b7"
EXPECTED_TERMINAL_ID = "789353a8-09e4-4ef2-9fa8-ac73c426bfc8"
EXPECTED_INSTALLATION_ROW_SHA256 = (
    "530ff69bd8e040457755bc0268216c8347442ebae858d5ce1152fb1fbfc65a71"
)
EXPECTED_REMOTE_KEYS_SHA256 = (
    "301905b96650f3f06fc6b3378a1450cc000159a9909f33af3b951a665eca2696"
)
EXPECTED_QUARANTINE_SHA256 = (
    "379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8"
)
EXPECTED_PRE_CLEANUP_STATE_FINGERPRINT = (
    "6e6309326781f34596bd987bef2397a68f1f3988754850000a8846ecfefbe39a"
)

EXPECTED_SHIFT_IDS = {
    "208335fe-f892-478b-b575-ee35498b4648",
    "4b53348d-3a1b-4e56-b97c-cdc1bc7b3e58",
    "d2337cb0-9b65-4c18-9baa-29b15fd163b6",
}
EXPECTED_ORDER_IDS = {
    "089e8a8d-1351-4b2a-b6ce-d3e0fd402f81",
    "8fcd1dbd-da6d-4ff2-bfa2-bc6db95fd3c2",
    "b293d66f-0469-47a8-82ee-887a864796c1",
}
EXPECTED_ORDER_LINE_IDS = {
    "3b4a620f-bd83-43ab-9394-97ed38f2e6ad",
    "88285974-aa81-48e2-a4e7-ac6f523d6c58",
    "c3789167-7d0a-47f1-b026-7b8681e7dd4f",
}
EXPECTED_GAMING_SESSION_IDS = {
    "633be5cf-f204-4a54-9087-184b8ec76a44",
    "7bb8a1af-d497-4c70-943c-2d78ae2cad5a",
    "b0245be8-0511-4268-8cc3-be23c546d955",
    "f0d735ab-d6b8-4958-83cb-f63968e052fd",
    "f28a30b1-d128-42d7-9510-b5909d7b2615",
}
EXPECTED_MENU_ITEM_IDS = {"2f968f7c-df0b-49fe-bedb-395da7329d28"}
EXPECTED_IDEMPOTENCY_KEYS = {
    "gaming-session-start:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5",
    "gaming-session-start:6ab2cd8b-6683-43e0-ae7b-f60fa8601537",
    "gaming-session-start:6fccc695-35e8-4708-a5b1-4deb03d7c512",
    "gaming-session-start:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5",
    "gaming-session-start:d628bc2a-cc7c-4068-ba4b-eaccc533eec5",
    "gaming-session-stop:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5",
    "gaming-session-stop:6ab2cd8b-6683-43e0-ae7b-f60fa8601537",
    "gaming-session-stop:6fccc695-35e8-4708-a5b1-4deb03d7c512",
    "gaming-session-stop:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5",
    "gaming-session-stop:d628bc2a-cc7c-4068-ba4b-eaccc533eec5",
}
EXPECTED_REPLAY_FENCE = [
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-start:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5",
        "request_hash": "5fcb1fd6961c263fce8a7ce4fac50a2d7996672ff11e4efe88d93d6a99f90820",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-start:6ab2cd8b-6683-43e0-ae7b-f60fa8601537",
        "request_hash": "c3535864ff673188a5c8ceb1f030013b0330a1ea265390db9f626a3d856b5376",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-start:6fccc695-35e8-4708-a5b1-4deb03d7c512",
        "request_hash": "ec50b97a0fda35cc3fd506929bf2946d6b01b17ec096b35d83bd0716708f71fa",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-start:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5",
        "request_hash": "aa73d0c0e4bacba45ecc40587b6330c44ecec7f0ddf8e226793d830baa197b4f",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-start:d628bc2a-cc7c-4068-ba4b-eaccc533eec5",
        "request_hash": "d1f0fc7d866787a0e3bda22f16dd9eb0427c7a365e652b478dd11a13ccc1cdbb",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-stop:2a65a12d-139f-4c1b-be7e-e0ab6a80eff5",
        "request_hash": "a5e3c7167ddfee3fa93f6961842e08be00d21f8c3b49dad711127c74ed195852",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-stop:6ab2cd8b-6683-43e0-ae7b-f60fa8601537",
        "request_hash": "79291fd795c8696a921873dc44354cb40f12fca3bb71a0d44692f9c24a2a36ec",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-stop:6fccc695-35e8-4708-a5b1-4deb03d7c512",
        "request_hash": "75b7510cc8e5ba0d917d8f5086e5f8701427c87593ab70f9d51198158e0f84e1",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-stop:cd112a65-4c1e-4c5b-bc1e-a6c9d9a25de5",
        "request_hash": "b77c9fa8888aaf0d919183dd5e84d386d537302f730f0023eed23418a9bc019d",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "idempotency",
        "action_key": "gaming-session-stop:d628bc2a-cc7c-4068-ba4b-eaccc533eec5",
        "request_hash": "d8ebc5e594841c6887559dcf40967b89a9c3140797c1df354dec796553e027ba",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": None,
    },
    {
        "action_type": "shift_open",
        "action_key": "shift-open:55b8797a-6436-4900-81d5-2c691467b006",
        "request_hash": "db5b4571c8ab096ef66ca8434a052f21a7c9f3743c78b5ad8e0b945ab3a9fb1d",
        "user_id": "7016c42c-11c9-48fa-a30a-5d66a8c970b7",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": "d2337cb0-9b65-4c18-9baa-29b15fd163b6",
    },
    {
        "action_type": "shift_open",
        "action_key": "shift-open:6fd9c8c3-c628-456f-9e99-f409037470fa",
        "request_hash": "6fdf4dbbf33410896e7300295bfc82e2183bda9ecfbdec6ca84c41bb9114c22f",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": "4b53348d-3a1b-4e56-b97c-cdc1bc7b3e58",
    },
    {
        "action_type": "shift_open",
        "action_key": "shift-open:e36f0910-aa58-4f41-ba8c-b421e8c9212e",
        "request_hash": "104a7ce1f29cfa8504944479c30f953c8694f9e4ebab7eb07dd2e6d58bc51856",
        "user_id": "c2aed53f-401f-4e09-8237-11c366e612ef",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "source_entity_id": "208335fe-f892-478b-b575-ee35498b4648",
    },
]
EXPECTED_AUDIT_IDS = {
    1001,
    1004,
    1142,
    1143,
    1150,
    1161,
    1162,
    1163,
    1164,
    1165,
    1166,
    1167,
    1168,
    1217,
    1232,
    1233,
    1234,
    1235,
    1236,
    1237,
    1238,
    1239,
    1240,
    28174,
    28175,
    28176,
    28177,
    28180,
    28183,
    28188,
    28191,
    28194,
    28197,
    28198,
    28199,
    28200,
    28201,
}

EXPECTED_DELETED_COUNTS = {
    "audit_log": 37,
    "idempotency_keys": 10,
    "gaming_sessions": 5,
    "order_lines": 3,
    "orders": 3,
    "menu_items": 1,
    "shifts": 3,
}
EXPECTED_PRE_COUNTS = {
    "payments": 3,
    "refunds": 0,
    "customers": 0,
    "active_or_paused_sessions": 0,
    "open_or_held_orders": 0,
    "open_shifts": 1,
    "pending_tablet_outbox": 1,
    "google_sheets_deliveries": 0,
    "google_sheets_unresolved": 0,
}
EXPECTED_POST_COUNTS = {
    "shifts": 7,
    "orders": 4,
    "order_lines": 4,
    "gaming_sessions": 4,
    "menu_items": 4,
    "client_installations": 7,
    "remote_assistance_device_keys": 379,
    "payments": 3,
    "refunds": 0,
    "customers": 0,
    "idempotency_keys": 37,
    "audit_log": 1235,
    "active_sessions": 0,
    "open_orders": 0,
    "open_shifts": 0,
    "pending_tablet_outbox": 1,
    "google_sheets_unresolved": 0,
}
EXPECTED_RETIRED_INSTALLATION = {
    "client_installation_id": "92b491f1-c35b-4437-af9a-a6be68035001",
    "installation_id": "d664b4a5-d293-48a7-a96c-8c3050ed5e76",
    "version_name": "3.1.28",
    "version_code": 36,
    "pending_outbox_count": 1,
    "pending_outbox_count_unchanged": True,
    "last_seen_at": "2026-09-19T08:33:01.020059+00:00",
    "last_successful_sync_at": "2026-09-19T08:25:19.033+00:00",
    "updated_at": "2026-09-19T08:33:01.020059+00:00",
    "maintenance_mutated_installation": False,
    "retained_remote_assistance_device_key_count": 29,
    "retained_remote_assistance_device_keys_sha256": EXPECTED_REMOTE_KEYS_SHA256,
    "client_was_offline": None,
    "synced_at": None,
}
EXPECTED_REASON = (
    "Owner-authorized removal of verified post-cutoff test rows after backup restore "
    "proof; one unattributed Code30.1 test-installation saved-action report and its "
    "29 remote-assistance keys remain unchanged as historical evidence, all local "
    "AVDs were separately wiped, and no direct AVD identity link is claimed."
)

HEX_40 = re.compile(r"^[0-9a-f]{40}$")
HEX_64 = re.compile(r"^[0-9a-f]{64}$")


class StateError(ValueError):
    """Raised when post-cleanup database evidence is incomplete or changed."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise StateError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _require_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise StateError(f"{label} has an unexpected schema")
    return value


def _require_uuid(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise StateError(f"{label} must be a UUID string")
    try:
        parsed = UUID(value)
    except ValueError as exc:
        raise StateError(f"{label} must be a UUID string") from exc
    if str(parsed) != value:
        raise StateError(f"{label} must be a canonical UUID")
    return value


def _require_timestamp(value: Any, label: str) -> None:
    if not isinstance(value, str):
        raise StateError(f"{label} must be a timestamp")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StateError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise StateError(f"{label} must include a timezone")


def _require_exact_list(value: Any, expected: set[Any], label: str) -> None:
    if not isinstance(value, list) or len(value) != len(expected):
        raise StateError(f"{label} does not match the reviewed allowlist")
    try:
        actual = set(value)
    except TypeError as exc:
        raise StateError(f"{label} contains a non-scalar value") from exc
    if actual != expected:
        raise StateError(f"{label} does not match the reviewed allowlist")


def _verify_receipt(receipt: Any) -> int:
    receipt = _require_keys(
        receipt,
        {
            "id",
            "actor_user_id",
            "company_id",
            "action",
            "entity_type",
            "entity_id",
            "before",
            "after",
            "ip",
            "user_agent",
            "terminal_id",
            "request_id",
            "client_platform",
            "client_version_code",
            "client_action_id",
            "client_reported_at",
            "client_was_offline",
            "synced_at",
            "reason",
        },
        "cleanup receipt",
    )
    if type(receipt["id"]) is not int or receipt["id"] <= 0:
        raise StateError("cleanup receipt id is invalid")
    expected_identity = {
        "actor_user_id": EXPECTED_ACTOR_USER_ID,
        "company_id": EXPECTED_COMPANY_ID,
        "action": "production_trial_cleanup",
        "entity_type": "ReleaseCleanup",
        "entity_id": "code30.1-20260920",
        "ip": None,
        "user_agent": "cleanup-code30-production-trial-data/2",
        "terminal_id": EXPECTED_TERMINAL_ID,
        "request_id": "code30.1-production-trial-cleanup-20260920",
        "client_platform": None,
        "client_version_code": None,
        "client_action_id": "production-trial-cleanup-20260920",
        "client_reported_at": None,
        "client_was_offline": None,
        "synced_at": None,
        "reason": EXPECTED_REASON,
    }
    for key, expected in expected_identity.items():
        if receipt[key] != expected:
            raise StateError(f"cleanup receipt {key} is not the reviewed value")

    before = _require_keys(
        receipt["before"],
        {
            "schema_revision",
            "state_fingerprint",
            "backup_sha256",
            "quarantine_evidence_sha256",
            "counts",
        },
        "cleanup receipt before",
    )
    if before["schema_revision"] != "0078":
        raise StateError("cleanup receipt schema revision is not 0078")
    if before["state_fingerprint"] != EXPECTED_PRE_CLEANUP_STATE_FINGERPRINT:
        raise StateError("cleanup receipt state fingerprint changed")
    if not isinstance(before["backup_sha256"], str) or not HEX_64.fullmatch(
        before["backup_sha256"]
    ):
        raise StateError("cleanup receipt backup hash is invalid")
    if before["quarantine_evidence_sha256"] != EXPECTED_QUARANTINE_SHA256:
        raise StateError("cleanup receipt quarantine evidence changed")
    if before["counts"] != EXPECTED_PRE_COUNTS:
        raise StateError("cleanup receipt pre-cleanup counts changed")

    after = _require_keys(
        receipt["after"],
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
        "cleanup receipt after",
    )
    source_git_sha = after["source_git_sha"]
    if not isinstance(source_git_sha, str) or not HEX_40.fullmatch(source_git_sha):
        raise StateError("cleanup receipt source Git SHA is invalid")
    backend_image_id = after["backend_image_id"]
    if not isinstance(backend_image_id, str) or not re.fullmatch(
        r"sha256:[0-9a-f]{64}", backend_image_id
    ):
        raise StateError("cleanup receipt backend image identity is invalid")
    if after["executor"] != f"install-on-vm:{source_git_sha}":
        raise StateError("cleanup receipt executor does not match its source Git SHA")
    _require_timestamp(after["executed_at"], "cleanup receipt executed_at")
    if after["deleted_counts"] != EXPECTED_DELETED_COUNTS:
        raise StateError("cleanup receipt deleted counts changed")
    _require_exact_list(after["deleted_shift_ids"], EXPECTED_SHIFT_IDS, "deleted shifts")
    _require_exact_list(after["deleted_order_ids"], EXPECTED_ORDER_IDS, "deleted orders")
    _require_exact_list(
        after["deleted_order_line_ids"], EXPECTED_ORDER_LINE_IDS, "deleted order lines"
    )
    _require_exact_list(
        after["deleted_gaming_session_ids"],
        EXPECTED_GAMING_SESSION_IDS,
        "deleted gaming sessions",
    )
    _require_exact_list(
        after["deleted_menu_item_ids"], EXPECTED_MENU_ITEM_IDS, "deleted menu items"
    )
    _require_exact_list(
        after["deleted_idempotency_keys"],
        EXPECTED_IDEMPOTENCY_KEYS,
        "deleted idempotency keys",
    )
    _require_exact_list(after["deleted_audit_ids"], EXPECTED_AUDIT_IDS, "deleted audit rows")
    if after["retired_test_installation"] != EXPECTED_RETIRED_INSTALLATION:
        raise StateError("cleanup receipt retained installation snapshot changed")
    if after["local_avd_quarantine"] != {
        "evidence_sha256": EXPECTED_QUARANTINE_SHA256,
        "avd_count": 18,
        "direct_installation_identity_link_proven": False,
    }:
        raise StateError("cleanup receipt AVD quarantine evidence changed")
    if after["expected_post_counts"] != EXPECTED_POST_COUNTS:
        raise StateError("cleanup receipt expected post-cleanup counts changed")

    fence = after["replay_fence"]
    if not isinstance(fence, list) or len(fence) != 13:
        raise StateError("cleanup replay fence must contain exactly 13 actions")
    action_keys: list[str] = []
    expected_fence_by_key = {
        entry["action_key"]: entry for entry in EXPECTED_REPLAY_FENCE
    }
    for index, raw_entry in enumerate(fence):
        entry = _require_keys(
            raw_entry,
            {
                "action_type",
                "action_key",
                "request_hash",
                "user_id",
                "terminal_id",
                "source_entity_id",
            },
            f"cleanup replay fence entry {index}",
        )
        action_key = entry["action_key"]
        if not isinstance(action_key, str) or not action_key:
            raise StateError("cleanup replay fence action key is invalid")
        action_keys.append(action_key)
        expected_entry = expected_fence_by_key.get(action_key)
        if expected_entry is None or entry != expected_entry:
            raise StateError("cleanup replay fence action identity changed")
    if action_keys != sorted(action_keys) or len(action_keys) != len(set(action_keys)):
        raise StateError("cleanup replay fence keys are duplicate or out of order")
    if set(action_keys) != set(expected_fence_by_key):
        raise StateError("cleanup replay fence is missing a retired action")
    return receipt["id"]


def verify_state(document: Any) -> int:
    document = _require_keys(
        document,
        {
            "schema_version",
            "database_revision",
            "pending_outbox_count",
            "nonzero_installation_count",
            "retired_installation_count",
            "retired_installation_row_sha256",
            "remote_assistance_device_key_count",
            "remote_assistance_device_keys_sha256",
            "surviving_deleted_targets",
            "cleanup_receipts",
        },
        "post-cleanup state",
    )
    if type(document["schema_version"]) is not int or document["schema_version"] != 1:
        raise StateError("unsupported post-cleanup state schema")
    revision = document["database_revision"]
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9]{4}", revision):
        raise StateError("database revision is not a four-digit migration")
    if int(revision) < 78:
        raise StateError("database revision predates the cleanup replay fence")
    expected_scalars = {
        "pending_outbox_count": 1,
        "nonzero_installation_count": 1,
        "retired_installation_count": 1,
        "remote_assistance_device_key_count": 29,
    }
    for key, expected in expected_scalars.items():
        if type(document[key]) is not int or document[key] != expected:
            raise StateError(f"{key} does not match the retained historical state")
    if document["retired_installation_row_sha256"] != EXPECTED_INSTALLATION_ROW_SHA256:
        raise StateError("retained installation row changed")
    if document["remote_assistance_device_keys_sha256"] != EXPECTED_REMOTE_KEYS_SHA256:
        raise StateError("retained remote-assistance key rows changed")
    survivors = document["surviving_deleted_targets"]
    expected_survivors = {
        "shifts": 0,
        "orders": 0,
        "order_lines": 0,
        "gaming_sessions": 0,
        "menu_items": 0,
        "idempotency_keys": 0,
        "audit_log": 0,
    }
    if survivors != expected_survivors:
        raise StateError("one or more reviewed cleanup targets reappeared")
    receipts = document["cleanup_receipts"]
    if not isinstance(receipts, list) or len(receipts) != 1:
        raise StateError("exactly one cleanup receipt is required")
    return _verify_receipt(receipts[0])


def main() -> int:
    quiet = sys.argv[1:] == ["--quiet"]
    if sys.argv[1:] not in ([], ["--quiet"]):
        print(f"Usage: {sys.argv[0]} [--quiet] < STATE_JSON", file=sys.stderr)
        return 2
    try:
        raw = sys.stdin.buffer.read(1024 * 1024 + 1)
        if not raw or len(raw) > 1024 * 1024:
            raise StateError("post-cleanup state JSON size is invalid")
        document = json.loads(
            raw,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=lambda value: (_ for _ in ()).throw(
                StateError(f"invalid JSON constant: {value}")
            ),
        )
        receipt_id = verify_state(document)
    except (StateError, json.JSONDecodeError, UnicodeDecodeError) as exc:
        if not quiet:
            print(f"Invalid Code30.2 post-cleanup state: {exc}", file=sys.stderr)
        return 1
    if not quiet:
        print(f"post-cleanup-state=verified receipt_id={receipt_id}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
