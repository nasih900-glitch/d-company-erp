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
    source_sha = module.EXPECTED_SOURCE_GIT_SHA
    replay_fence = deepcopy(module.EXPECTED_REPLAY_FENCE)
    suffix_count = module.EXPECTED_PRE_RECEIPT_LOGIN_SUFFIX_COUNT
    suffix_hash = module.EXPECTED_PRE_RECEIPT_LOGIN_SUFFIX_SHA256
    receipt = {
        "id": 28204,
        "actor_user_id": module.EXPECTED_ACTOR_USER_ID,
        "company_id": module.EXPECTED_COMPANY_ID,
        "action": "production_trial_cleanup",
        "entity_type": "ReleaseCleanup",
        "entity_id": "code30.1-20260920",
        "before": {
            "schema_revision": "0078",
            "state_fingerprint": module.EXPECTED_STATE_FINGERPRINT,
            "backup_sha256": module.EXPECTED_BACKUP_SHA256,
            "quarantine_evidence_sha256": module.EXPECTED_QUARANTINE_SHA256,
            "counts": deepcopy(module.EXPECTED_PRE_COUNTS),
            "audit_log": {
                "baseline_max_id": module.EXPECTED_AUDIT_BASELINE_MAX_ID,
                "baseline_count": module.EXPECTED_AUDIT_BASELINE_COUNT,
                "baseline_sha256": module.EXPECTED_AUDIT_BASELINE_SHA256,
                "login_success_suffix_count": suffix_count,
                "login_success_suffix_sha256": suffix_hash,
                "login_success_suffix_max_id": (
                    module.EXPECTED_PRE_RECEIPT_LOGIN_SUFFIX_MAX_ID
                ),
            },
        },
        "after": {
            "source_git_sha": source_sha,
            "backend_image_id": module.EXPECTED_BACKEND_IMAGE_ID,
            "executor": f"install-on-vm:{source_sha}",
            "executed_at": module.EXPECTED_CLEANUP_RECEIPT_AT.isoformat(),
            "deleted_counts": deepcopy(module.EXPECTED_DELETED_COUNTS),
            "deleted_shift_ids": sorted(module.EXPECTED_SHIFT_IDS),
            "deleted_order_ids": sorted(module.EXPECTED_ORDER_IDS),
            "deleted_order_line_ids": sorted(module.EXPECTED_ORDER_LINE_IDS),
            "deleted_gaming_session_ids": sorted(module.EXPECTED_GAMING_SESSION_IDS),
            "deleted_menu_item_ids": sorted(module.EXPECTED_MENU_ITEM_IDS),
            "retired_test_installation": deepcopy(module.EXPECTED_RETIRED_INSTALLATION),
            "local_avd_quarantine": {
                "evidence_sha256": module.EXPECTED_QUARANTINE_SHA256,
                "avd_count": 18,
                "direct_installation_identity_link_proven": False,
            },
            "deleted_idempotency_keys": sorted(module.EXPECTED_IDEMPOTENCY_KEYS),
            "deleted_audit_ids": sorted(module.EXPECTED_AUDIT_IDS),
            "replay_fence": replay_fence,
            "expected_post_counts": {
                **deepcopy(module.EXPECTED_POST_COUNTS),
                "audit_log": (
                    module.EXPECTED_AUDIT_BASELINE_COUNT + suffix_count - 37 + 1
                ),
            },
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
        "schema_version": 2,
        "database_revision": "0078",
        "pending_outbox_count": 1,
        "nonzero_installation_count": 1,
        "cleanup_receipt_created_at": module.EXPECTED_CLEANUP_RECEIPT_AT.isoformat(),
        "retired_installation_count": 1,
        "retired_installation_identity_sha256": (
            module.EXPECTED_INSTALLATION_IDENTITY_SHA256
        ),
        "retired_installation_invalid_telemetry_count": 0,
        "client_installation_guard_trigger_count": 1,
        "client_installation_guard_function_sha256": (
            module.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256
        ),
        "client_installation_guard_function_definition_sha256": (
            module.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256
        ),
        "client_installation_guard_trigger_definition_sha256": (
            module.EXPECTED_CLIENT_INSTALLATION_GUARD_TRIGGER_DEFINITION_SHA256
        ),
        "retained_remote_assistance_device_key_count": 29,
        "retained_remote_assistance_device_keys_identity_sha256": (
            module.EXPECTED_RETAINED_REMOTE_KEYS_IDENTITY_SHA256
        ),
        "retained_remote_assistance_device_key_invalid_count": 0,
        "post_cleanup_remote_assistance_device_key_count": 30,
        "post_cleanup_remote_assistance_device_key_expired_count": 29,
        "post_cleanup_remote_assistance_device_key_pending_count": 1,
        "post_cleanup_remote_assistance_device_key_invalid_count": 0,
        "post_cleanup_remote_assistance_device_key_enrollment_audit_count": 30,
        "post_cleanup_remote_assistance_device_key_expiration_audit_count": 29,
        "post_cleanup_remote_assistance_device_key_invalid_audit_count": 0,
        "remote_assistance_device_key_guard_trigger_count": 1,
        "remote_assistance_device_key_guard_function_sha256": (
            module.EXPECTED_REMOTE_DEVICE_KEY_GUARD_FUNCTION_SHA256
        ),
        "remote_assistance_device_key_guard_function_definition_sha256": (
            module.EXPECTED_REMOTE_DEVICE_KEY_GUARD_FUNCTION_DEFINITION_SHA256
        ),
        "remote_assistance_device_key_guard_trigger_definition_sha256": (
            module.EXPECTED_REMOTE_DEVICE_KEY_GUARD_TRIGGER_DEFINITION_SHA256
        ),
        "audit_integrity": {
            "baseline_max_id": module.EXPECTED_AUDIT_BASELINE_MAX_ID,
            "retained_baseline_count": module.EXPECTED_RETAINED_AUDIT_BASELINE_COUNT,
            "retained_baseline_sha256": module.EXPECTED_RETAINED_AUDIT_BASELINE_SHA256,
            "invalid_pre_receipt_suffix_count": 0,
            "pre_receipt_login_success_count": suffix_count,
            "pre_receipt_login_success_sha256": suffix_hash,
            "pre_receipt_login_success_max_id": (
                module.EXPECTED_PRE_RECEIPT_LOGIN_SUFFIX_MAX_ID
            ),
        },
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

    assert verifier.verify_state(current) == 28204
    assert verifier.verify_state(future) == 28204


def test_cleanup_receipt_id_and_boundary_are_immutable() -> None:
    verifier = _load_verifier()
    changed_id = _valid_document(verifier)
    changed_id["cleanup_receipts"][0]["id"] += 1
    with pytest.raises(verifier.StateError, match="retained receipt"):
        verifier.verify_state(changed_id)

    changed_boundary = _valid_document(verifier)
    changed_boundary["cleanup_receipt_created_at"] = "2026-09-20T14:34:48+00:00"
    with pytest.raises(verifier.StateError, match="creation time changed"):
        verifier.verify_state(changed_boundary)

    changed_execution = _valid_document(verifier)
    changed_execution["cleanup_receipts"][0]["after"]["executed_at"] = (
        "2026-09-20T14:34:48+00:00"
    )
    with pytest.raises(verifier.StateError, match="execution time changed"):
        verifier.verify_state(changed_execution)


@pytest.mark.parametrize(
    ("field", "changed", "message"),
    [
        ("state_fingerprint", "0" * 64, "state fingerprint changed"),
        ("backup_sha256", "0" * 64, "backup hash changed"),
    ],
)
def test_cleanup_receipt_pre_state_provenance_is_exact(
    field: str, changed: str, message: str
) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document["cleanup_receipts"][0]["before"][field] = changed

    with pytest.raises(verifier.StateError, match=message):
        verifier.verify_state(document)


def test_cleanup_receipt_release_provenance_is_exact() -> None:
    verifier = _load_verifier()

    source_changed = _valid_document(verifier)
    source_changed["cleanup_receipts"][0]["after"].update(
        source_git_sha="0" * 40,
        executor=f"install-on-vm:{'0' * 40}",
    )
    with pytest.raises(verifier.StateError, match="source Git SHA changed"):
        verifier.verify_state(source_changed)

    image_changed = _valid_document(verifier)
    image_changed["cleanup_receipts"][0]["after"]["backend_image_id"] = (
        f"sha256:{'0' * 64}"
    )
    with pytest.raises(verifier.StateError, match="backend image identity changed"):
        verifier.verify_state(image_changed)


@pytest.mark.parametrize(
    ("path", "replacement", "message"),
    [
        (("id",), 28204.0, "retained receipt"),
        (
            ("before", "counts", "open_shifts"),
            True,
            "pre-cleanup counts changed",
        ),
        (
            ("before", "audit_log", "baseline_count"),
            1271.0,
            "audit baseline count changed",
        ),
        (
            ("before", "audit_log", "login_success_suffix_count"),
            True,
            "login suffix count changed",
        ),
        (
            ("after", "deleted_counts", "menu_items"),
            True,
            "deleted counts changed",
        ),
        (
            ("after", "retired_test_installation", "version_code"),
            36.0,
            "retained installation snapshot changed",
        ),
        (
            (
                "after",
                "retired_test_installation",
                "pending_outbox_count_unchanged",
            ),
            1,
            "retained installation snapshot changed",
        ),
        (
            ("after", "local_avd_quarantine", "avd_count"),
            18.0,
            "AVD quarantine evidence changed",
        ),
        (
            (
                "after",
                "local_avd_quarantine",
                "direct_installation_identity_link_proven",
            ),
            0,
            "AVD quarantine evidence changed",
        ),
        (
            ("after", "expected_post_counts", "pending_tablet_outbox"),
            True,
            "expected post-cleanup counts changed",
        ),
        (
            ("after", "deleted_audit_ids", 0),
            1001.0,
            "reviewed allowlist",
        ),
    ],
)
def test_cleanup_receipt_rejects_bool_float_and_type_drift(
    path: tuple[str | int, ...], replacement, message: str
) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    target = document["cleanup_receipts"][0]
    for part in path[:-1]:
        target = target[part]
    target[path[-1]] = replacement

    with pytest.raises(verifier.StateError, match=message):
        verifier.verify_state(document)


def test_coordinated_pre_receipt_suffix_replacement_is_rejected() -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    replacement = "0" * 64
    document["cleanup_receipts"][0]["before"]["audit_log"][
        "login_success_suffix_sha256"
    ] = replacement
    document["audit_integrity"]["pre_receipt_login_success_sha256"] = replacement

    with pytest.raises(verifier.StateError, match="login suffix hash changed"):
        verifier.verify_state(document)


def test_zero_or_audited_post_cleanup_key_churn_is_allowed() -> None:
    verifier = _load_verifier()
    churn = _valid_document(verifier)
    assert verifier.verify_state(churn) == verifier.EXPECTED_CLEANUP_RECEIPT_ID

    no_churn = _valid_document(verifier)
    no_churn["post_cleanup_remote_assistance_device_key_count"] = 0
    no_churn["post_cleanup_remote_assistance_device_key_expired_count"] = 0
    no_churn["post_cleanup_remote_assistance_device_key_pending_count"] = 0
    no_churn["post_cleanup_remote_assistance_device_key_enrollment_audit_count"] = 0
    no_churn["post_cleanup_remote_assistance_device_key_expiration_audit_count"] = 0
    assert verifier.verify_state(no_churn) == verifier.EXPECTED_CLEANUP_RECEIPT_ID


@pytest.mark.parametrize(
    ("field", "changed", "message"),
    [
        (
            "post_cleanup_remote_assistance_device_key_invalid_count",
            1,
            "does not match",
        ),
        (
            "post_cleanup_remote_assistance_device_key_invalid_audit_count",
            1,
            "does not match",
        ),
        (
            "post_cleanup_remote_assistance_device_key_pending_count",
            2,
            "more than one",
        ),
        (
            "retained_remote_assistance_device_key_invalid_count",
            1,
            "does not match",
        ),
        (
            "retired_installation_invalid_telemetry_count",
            1,
            "does not match",
        ),
    ],
)
def test_invalid_mutable_evidence_is_rejected(
    field: str, changed: int, message: str
) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document[field] = changed

    with pytest.raises(verifier.StateError, match=message):
        verifier.verify_state(document)


def test_post_cleanup_key_counts_must_reconcile() -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document["post_cleanup_remote_assistance_device_key_expired_count"] = 28

    with pytest.raises(verifier.StateError, match="totals do not reconcile"):
        verifier.verify_state(document)


@pytest.mark.parametrize(
    ("field", "changed", "message"),
    [
        (
            "post_cleanup_remote_assistance_device_key_enrollment_audit_count",
            29,
            "enrollment audits",
        ),
        (
            "post_cleanup_remote_assistance_device_key_expiration_audit_count",
            28,
            "expiration audits",
        ),
    ],
)
def test_post_cleanup_key_audit_counts_must_reconcile(
    field: str, changed: int, message: str
) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document[field] = changed

    with pytest.raises(verifier.StateError, match=message):
        verifier.verify_state(document)


def test_unreviewed_action_before_receipt_is_rejected() -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document["audit_integrity"]["invalid_pre_receipt_suffix_count"] = 1

    with pytest.raises(verifier.StateError, match="not login-only"):
        verifier.verify_state(document)


@pytest.mark.parametrize(
    ("field", "changed", "message"),
    [
        ("retained_baseline_count", 1233, "baseline count changed"),
        ("retained_baseline_sha256", "0" * 64, "baseline rows changed"),
    ],
)
def test_changed_retained_audit_prefix_is_rejected(
    field, changed, message: str
) -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document["audit_integrity"][field] = changed

    with pytest.raises(verifier.StateError, match=message):
        verifier.verify_state(document)


def test_receipt_must_bind_the_exact_pre_receipt_login_suffix() -> None:
    verifier = _load_verifier()
    document = _valid_document(verifier)
    document["audit_integrity"]["pre_receipt_login_success_sha256"] = "8" * 64

    with pytest.raises(verifier.StateError, match="login suffix hash changed"):
        verifier.verify_state(document)


def test_post_cleanup_query_limits_login_only_rule_to_pre_receipt_rows() -> None:
    query = STATE_QUERY.read_text(encoding="utf-8")

    assert "audit.id > 28202" in query
    assert (
        "audit.id < coalesce((SELECT min(id) FROM receipt_candidates), "
        "9223372036854775807)" in query
    )
    assert "'invalid_pre_receipt_suffix_count'" in query
    assert "'current_audit_row_count'" not in query
    assert "'current_login_success_count'" not in query
    assert "JOIN users" not in query
    assert "actor.status" not in query
    assert "actor.deleted_at" not in query
    assert "actor.email" not in query
    assert "actor.name" not in query
    assert "audit.actor_user_id IS NOT NULL" in query
    assert "f2f0166cd8a87b8f43af616443427fc50ecb379baf35e7a419f16250962c45fc" in (
        VERIFIER.read_text(encoding="utf-8")
    )


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
    changed["cleanup_receipts"][0]["after"]["replay_fence"][0]["request_hash"] = (
        "not-a-hash"
    )
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
        (
            "retired_installation_identity_sha256",
            "0" * 64,
            "installation identity changed",
        ),
        ("pending_outbox_count", 2, "pending_outbox_count"),
        ("nonzero_installation_count", 2, "nonzero_installation_count"),
        (
            "retained_remote_assistance_device_key_count",
            28,
            "retained_remote_assistance_device_key_count",
        ),
        (
            "retained_remote_assistance_device_keys_identity_sha256",
            "0" * 64,
            "key identities changed",
        ),
        (
            "client_installation_guard_function_sha256",
            "0" * 64,
            "client installation guard changed",
        ),
        (
            "client_installation_guard_function_definition_sha256",
            "0" * 64,
            "function definition changed",
        ),
        (
            "client_installation_guard_trigger_definition_sha256",
            "0" * 64,
            "trigger definition changed",
        ),
        (
            "remote_assistance_device_key_guard_function_sha256",
            "0" * 64,
            "device-key guard changed",
        ),
        (
            "remote_assistance_device_key_guard_function_definition_sha256",
            "0" * 64,
            "function definition changed",
        ),
        (
            "remote_assistance_device_key_guard_trigger_definition_sha256",
            "0" * 64,
            "trigger definition changed",
        ),
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

    post_cleanup_query = (
        'post_cleanup_state_json=$(docker exec -i "$EXISTING_POSTGRES_CONTAINER"'
    )
    post_cleanup_acceptance = "CODE30_2_POST_CLEANUP_STALE_OUTBOX_ACCEPTED=true"
    one_time_signature = (
        'stale_outbox_signature=$(docker exec "$EXISTING_POSTGRES_CONTAINER"'
    )
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
    assert "key_row.created_at <= boundary.created_at" in query
    assert "key_row.enrolled_at <= boundary.created_at" in query
    assert "key_row.created_at > boundary.created_at" in query
    assert "key_row.enrolled_at > boundary.created_at" in query
    assert "dcompany_validate_client_installation_scope" in query
    assert "dcompany_guard_remote_assistance_device_key" in query
    assert "pg_get_triggerdef(trigger_row.oid, true)" in query
    assert "pg_get_functiondef(procedure.oid)" in query
    assert "procedure.prosecdef" in query
    assert "procedure.proconfig IS NULL" in query
    assert "remote_assistance.device_key.enrolled" in query
    assert "remote_assistance.device_key.expired" in query
    assert "audit.client_was_offline IS FALSE" in query
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
