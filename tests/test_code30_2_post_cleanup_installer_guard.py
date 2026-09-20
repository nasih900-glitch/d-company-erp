from __future__ import annotations

from copy import deepcopy
import importlib.util
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "infra/scripts/verify-code30-2-post-cleanup-state.py"
STATE_QUERY = ROOT / "infra/scripts/verify-code30-2-post-cleanup-state.sql"
CLEANUP_QUERY = ROOT / "infra/scripts/cleanup-code30-production-trial-data.sql"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"


def _load_verifier():
    spec = importlib.util.spec_from_file_location("post_cleanup_verifier", VERIFIER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _valid_document(module) -> dict:
    source_sha = "1" * 40
    replay_fence = deepcopy(module.EXPECTED_REPLAY_FENCE)
    receipt = {
        "id": 28203,
        "actor_user_id": module.EXPECTED_ACTOR_USER_ID,
        "company_id": module.EXPECTED_COMPANY_ID,
        "action": "production_trial_cleanup",
        "entity_type": "ReleaseCleanup",
        "entity_id": "code30.1-20260920",
        "before": {
            "schema_revision": "0078",
            "state_fingerprint": module.EXPECTED_PRE_CLEANUP_STATE_FINGERPRINT,
            "backup_sha256": "c" * 64,
            "quarantine_evidence_sha256": module.EXPECTED_QUARANTINE_SHA256,
            "counts": deepcopy(module.EXPECTED_PRE_COUNTS),
        },
        "after": {
            "source_git_sha": source_sha,
            "backend_image_id": f"sha256:{'d' * 64}",
            "executor": f"install-on-vm:{source_sha}",
            "executed_at": "2026-09-20T04:30:00+00:00",
            "deleted_counts": deepcopy(module.EXPECTED_DELETED_COUNTS),
            "deleted_shift_ids": sorted(module.EXPECTED_SHIFT_IDS),
            "deleted_order_ids": sorted(module.EXPECTED_ORDER_IDS),
            "deleted_order_line_ids": sorted(module.EXPECTED_ORDER_LINE_IDS),
            "deleted_gaming_session_ids": sorted(module.EXPECTED_GAMING_SESSION_IDS),
            "deleted_menu_item_ids": sorted(module.EXPECTED_MENU_ITEM_IDS),
            "retired_test_installation": deepcopy(
                module.EXPECTED_RETIRED_INSTALLATION
            ),
            "local_avd_quarantine": {
                "evidence_sha256": module.EXPECTED_QUARANTINE_SHA256,
                "avd_count": 18,
                "direct_installation_identity_link_proven": False,
            },
            "deleted_idempotency_keys": sorted(module.EXPECTED_IDEMPOTENCY_KEYS),
            "deleted_audit_ids": sorted(module.EXPECTED_AUDIT_IDS),
            "replay_fence": replay_fence,
            "expected_post_counts": deepcopy(module.EXPECTED_POST_COUNTS),
        },
        "ip": None,
        "user_agent": "cleanup-code30-production-trial-data/2",
        "terminal_id": module.EXPECTED_TERMINAL_ID,
        "request_id": "code30.1-production-trial-cleanup-20260920",
        "client_platform": None,
        "client_version_code": None,
        "client_action_id": "production-trial-cleanup-20260920",
        "client_reported_at": None,
        "client_was_offline": None,
        "synced_at": None,
        "reason": module.EXPECTED_REASON,
    }
    return {
        "schema_version": 1,
        "database_revision": "0078",
        "pending_outbox_count": 1,
        "nonzero_installation_count": 1,
        "retired_installation_count": 1,
        "retired_installation_row_sha256": module.EXPECTED_INSTALLATION_ROW_SHA256,
        "remote_assistance_device_key_count": 29,
        "remote_assistance_device_keys_sha256": module.EXPECTED_REMOTE_KEYS_SHA256,
        "surviving_deleted_targets": {
            "shifts": 0,
            "orders": 0,
            "order_lines": 0,
            "gaming_sessions": 0,
            "menu_items": 0,
            "idempotency_keys": 0,
            "audit_log": 0,
        },
        "cleanup_receipts": [receipt],
    }


def test_valid_post_cleanup_state_allows_future_installer() -> None:
    verifier = _load_verifier()
    current = _valid_document(verifier)
    future = _valid_document(verifier)
    future["database_revision"] = "0079"

    assert verifier.verify_state(current) == 28203
    assert verifier.verify_state(future) == 28203


def test_database_without_cleanup_migration_is_rejected() -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document["database_revision"] = "0077"

    with pytest.raises(verifier.StateError, match="predates"):
        verifier.verify_state(document)


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (
            lambda document: document["cleanup_receipts"].append(
                deepcopy(document["cleanup_receipts"][0])
            ),
            "exactly one cleanup receipt",
        ),
        (
            lambda document: document["cleanup_receipts"][0].update(
                entity_id="different-cleanup"
            ),
            "entity_id",
        ),
        (
            lambda document: document["cleanup_receipts"][0]["after"].pop(
                "local_avd_quarantine"
            ),
            "unexpected schema",
        ),
    ],
)
def test_duplicate_mismatched_or_malformed_receipt_is_rejected(
    mutation, message: str
) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    mutation(document)

    with pytest.raises(verifier.StateError, match=message):
        verifier.verify_state(document)


def test_missing_or_changed_replay_fence_is_rejected() -> None:
    verifier = _load_verifier()
    missing = _valid_document(verifier)
    missing["cleanup_receipts"][0]["after"]["replay_fence"].pop()
    with pytest.raises(verifier.StateError, match="exactly 13"):
        verifier.verify_state(missing)

    changed = _valid_document(verifier)
    changed["cleanup_receipts"][0]["after"]["replay_fence"][0][
        "request_hash"
    ] = "not-a-hash"
    with pytest.raises(verifier.StateError, match="action identity changed"):
        verifier.verify_state(changed)

    changed_shift_key = _valid_document(verifier)
    shift_entry = next(
        entry
        for entry in changed_shift_key["cleanup_receipts"][0]["after"]["replay_fence"]
        if entry["action_type"] == "shift_open"
    )
    shift_entry["action_key"] = "shift-open:00000000-0000-4000-8000-000000000000"
    changed_shift_key["cleanup_receipts"][0]["after"]["replay_fence"].sort(
        key=lambda entry: entry["action_key"]
    )
    with pytest.raises(verifier.StateError, match="action identity changed"):
        verifier.verify_state(changed_shift_key)


@pytest.mark.parametrize("target", ["shifts", "gaming_sessions", "idempotency_keys"])
def test_any_reviewed_deleted_row_reappearing_is_rejected(target: str) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document["surviving_deleted_targets"][target] = 1

    with pytest.raises(verifier.StateError, match="cleanup targets reappeared"):
        verifier.verify_state(document)


@pytest.mark.parametrize(
    ("field", "changed", "message"),
    [
        ("retired_installation_row_sha256", "0" * 64, "installation row changed"),
        ("pending_outbox_count", 2, "pending_outbox_count"),
        ("nonzero_installation_count", 2, "nonzero_installation_count"),
        ("remote_assistance_device_key_count", 28, "remote_assistance_device_key_count"),
    ],
)
def test_altered_retained_installation_evidence_is_rejected(
    field: str, changed, message: str
) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document[field] = changed

    with pytest.raises(verifier.StateError, match=message):
        verifier.verify_state(document)


def test_installer_checks_post_cleanup_state_before_one_time_bridge() -> None:
    source = INSTALLER.read_text(encoding="utf-8")
    query = STATE_QUERY.read_text(encoding="utf-8")
    cleanup_query = CLEANUP_QUERY.read_text(encoding="utf-8")

    post_cleanup_query = 'post_cleanup_state_json=$(docker exec -i "$EXISTING_POSTGRES_CONTAINER"'
    post_cleanup_acceptance = "CODE30_2_POST_CLEANUP_STALE_OUTBOX_ACCEPTED=true"
    one_time_signature = 'stale_outbox_signature=$(docker exec "$EXISTING_POSTGRES_CONTAINER"'
    bridge_acceptance = "CODE30_2_STALE_OUTBOX_BRIDGE_USED=true"
    assert source.index(post_cleanup_query) < source.index(post_cleanup_acceptance)
    assert source.index(post_cleanup_acceptance) < source.index(one_time_signature)
    assert source.index(one_time_signature) < source.index(bridge_acceptance)
    assert 'python3 "$POST_CLEANUP_STATE_VERIFIER" --quiet' in source
    assert '< "$POST_CLEANUP_STATE_SQL"' in source
    assert "BEGIN TRANSACTION READ ONLY;" in query
    assert "SET LOCAL TIME ZONE 'UTC';" in query
    assert "SET LOCAL bytea_output = 'hex';" in query
    assert "SET LOCAL TIME ZONE 'UTC';" in cleanup_query
    assert "SET LOCAL bytea_output = 'hex';" in cleanup_query
    assert source.count(post_cleanup_acceptance) == 1
    assert source.count(bridge_acceptance) == 1
    assert (
        '"$CODE30_2_POST_CLEANUP_STALE_OUTBOX_ACCEPTED" = \\\n'
        '       "$CODE30_2_STALE_OUTBOX_BRIDGE_USED"'
    ) in source
    assert "UPDATE " not in query.upper()
    assert "DELETE FROM" not in query.upper()
    for table_name in (
        "shifts",
        "orders",
        "order_lines",
        "gaming_sessions",
        "menu_items",
        "idempotency_keys",
        "audit_log",
    ):
        assert f"'{table_name}'" in query
