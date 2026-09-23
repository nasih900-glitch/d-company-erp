from __future__ import annotations

import importlib.util
import json
import subprocess
from hashlib import sha256
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "infra" / "scripts" / "prepare-code30-3-combined-cleanup.py"
REHEARSAL = ROOT / "infra" / "scripts" / "rehearse-code30-3-combined-cleanup.sql"
BODY = ROOT / "infra" / "scripts" / "code30-3-combined-cleanup-body.sql"
APPLY = ROOT / "infra" / "scripts" / "apply-code30-3-combined-cleanup.sql"
POSTCHECK = ROOT / "infra" / "scripts" / "postcheck-code30-3-combined-cleanup.sql"
RUNTIME = ROOT / "infra" / "scripts" / "code30-3-combined-cleanup-runtime.sh"
RUNNER = ROOT / "infra" / "scripts" / "rehearse-code30-3-combined-cleanup.sh"
spec = importlib.util.spec_from_file_location("combined_cleanup_validator", VALIDATOR)
assert spec and spec.loader
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
GENERATOR = ROOT / "infra" / "scripts" / "generate-code30-3-combined-cleanup-candidate.py"
generator_spec = importlib.util.spec_from_file_location("combined_cleanup_generator", GENERATOR)
assert generator_spec and generator_spec.loader
generator = importlib.util.module_from_spec(generator_spec)
generator_spec.loader.exec_module(generator)


LATER_SHIFT = "ffffffff-0000-4000-8000-000000000001"
LATER_SESSION = "ffffffff-0000-4000-8000-000000000002"


def _row(row_id: str) -> dict[str, object]:
    return {"id": row_id, "row_sha256": "a" * 64}


def _manifest(decision: str) -> dict[str, object]:
    tagged, _digests = validator._tagged_targets()
    targets = {
        table: [_row(row_id) for row_id in sorted(ids)]
        for table, ids in tagged.items()
    }
    retained = {table: [] for table in validator.TARGET_TABLES}
    if decision == "delete":
        targets["shifts"].append(_row(LATER_SHIFT))
        targets["gaming_sessions"].append(_row(LATER_SESSION))
        targets["shifts"].sort(key=lambda row: row["id"])
        targets["gaming_sessions"].sort(key=lambda row: row["id"])
    else:
        retained["shifts"] = [_row(LATER_SHIFT)]
        retained["gaming_sessions"] = [_row(LATER_SESSION)]
    shift_ids = [row["id"] for row in targets["shifts"]]
    fence = [
        {
            "action_type": "shift_open",
            "action_key": f"shift-open:{index:032x}",
            "request_hash": f"{index + 1:064x}",
            "user_id": "7016c42c-11c9-48fa-a30a-5d66a8c970b7",
            "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
            "source_entity_id": shift_id,
        }
        for index, shift_id in enumerate(shift_ids)
    ]
    full_tables = {
        name: {"row_count": 1, "row_sha256": "b" * 64}
        for name in sorted(validator.REQUIRED_FULL_TABLES)
    }
    triggers = [
        {
            "table_name": table,
            "trigger_name": name,
            "enabled": "O",
            "definition_sha256": "c" * 64,
            "function_sha256": "d" * 64,
        }
        for table, name in sorted(validator.EXPECTED_TRIGGERS)
    ]
    return {
        "schema_version": 1,
        "cleanup_id": "code30.3-combined-cleanup-20260923",
        "classification_evidence_sha256": "e" * 64,
        "tablet_replay_evidence_sha256": "8" * 64,
        "operator_reviewed_at": "2026-09-23T12:00:00+00:00",
        "operator_review_evidence_sha256": "9" * 64,
        "later_cohort_decision": decision,
        "backup_sha256": "f" * 64,
        "tagged_app_source_git_sha": validator.TAGGED_APP_SOURCE_GIT_SHA,
        "maintenance_source_git_sha": "1" * 40,
        "maintenance_sql_sha256": sha256(BODY.read_bytes()).hexdigest(),
        "expected_schema_revision": "0082",
        "company_id": "8f323fba-4358-45fe-9d3b-a8e0fae52993",
        "actor_user_id": "7016c42c-11c9-48fa-a30a-5d66a8c970b7",
        "terminal_id": "789353a8-09e4-4ef2-9fa8-ac73c426bfc8",
        "targets": targets,
        "retained_rows": retained,
        "replay_fence": fence,
        "expected_triggers": triggers,
        "expected_full_table_digests": full_tables,
        "evidence": {
            "original_five_shift_ids": sorted(tagged["shifts"]),
            "later_shift_id": LATER_SHIFT,
            "paid_total_minor": 750000,
            "retired_invoice_numbers": ["D/MN/26-27/00024"],
            "google_sheets_event_ids_owner_deletes": [
                "012862d2-28e2-5ae3-a61e-95227f3976f3"
            ],
            "invoice_counter": {
                "id": "9f78c3b5-c5f4-425d-9def-4b34298a2931",
                "last_seq": 71,
            },
        },
    }


@pytest.mark.parametrize("decision", ["delete", "retain"])
def test_manifest_requires_explicit_complete_delete_or_retain_decision(decision: str) -> None:
    validator.validate_manifest(_manifest(decision))


def test_candidate_without_classification_and_operator_review_is_rejected() -> None:
    manifest = _manifest("retain")
    manifest["classification_evidence_sha256"] = ""
    with pytest.raises(validator.ManifestError, match="classification_evidence"):
        validator.validate_manifest(manifest)


def test_operator_review_binds_classification_candidate_backup_and_decision(tmp_path: Path) -> None:
    manifest = _manifest("retain")
    classification = b"Owner classification: retain the later genuine shift.\n"
    classification_path = tmp_path / "classification.txt"
    classification_path.write_bytes(classification)
    manifest["classification_evidence_sha256"] = sha256(classification).hexdigest()
    review = {
        "schema_version": 1,
        "reviewer_role": "maintenance_operator",
        "reviewed_at": manifest["operator_reviewed_at"],
        "decision": "retain",
        "backup_sha256": manifest["backup_sha256"],
        "classification_evidence_sha256": manifest["classification_evidence_sha256"],
        "tablet_replay_evidence_sha256": manifest["tablet_replay_evidence_sha256"],
        "candidate_manifest_sha256": validator.canonical_candidate_sha256(manifest),
    }
    encoded = (json.dumps(review, sort_keys=True) + "\n").encode()
    review_path = tmp_path / "review.json"
    review_path.write_bytes(encoded)
    manifest["operator_review_evidence_sha256"] = sha256(encoded).hexdigest()

    validator.validate_manifest(manifest)
    validator.validate_review_files(manifest, classification_path, review_path)

    review["decision"] = "delete"
    changed = (json.dumps(review, sort_keys=True) + "\n").encode()
    review_path.write_bytes(changed)
    manifest["operator_review_evidence_sha256"] = sha256(changed).hexdigest()
    with pytest.raises(validator.ManifestError, match="decision differs"):
        validator.validate_review_files(manifest, classification_path, review_path)


def test_tablet_replay_evidence_requires_exact_post_closure_zero_pending_sync(
    tmp_path: Path,
) -> None:
    manifest = _manifest("retain")
    evidence = {
        "schema_version": 1,
        "disposition": "synced",
        "installation_id": "ffffffff-0000-4000-8000-000000000003",
        "final_business_closed_at": "2026-09-23T12:30:00+00:00",
        "observed_at": "2026-09-23T12:32:00+00:00",
        "pending_outbox_count": 0,
        "last_successful_sync_at": "2026-09-23T12:31:00+00:00",
        "quarantine_reference": None,
    }
    path = tmp_path / "tablet.json"
    encoded = (json.dumps(evidence, sort_keys=True) + "\n").encode()
    path.write_bytes(encoded)
    manifest["tablet_replay_evidence_sha256"] = sha256(encoded).hexdigest()
    validator.validate_tablet_replay_evidence(manifest, path)

    evidence["pending_outbox_count"] = False
    encoded = (json.dumps(evidence, sort_keys=True) + "\n").encode()
    path.write_bytes(encoded)
    manifest["tablet_replay_evidence_sha256"] = sha256(encoded).hexdigest()
    with pytest.raises(validator.ManifestError, match="zero-pending"):
        validator.validate_tablet_replay_evidence(manifest, path)


def test_free_text_tablet_quarantine_is_rejected(tmp_path: Path) -> None:
    manifest = _manifest("retain")
    evidence = {
        "schema_version": 1,
        "disposition": "quarantined",
        "installation_id": "ffffffff-0000-4000-8000-000000000003",
        "final_business_closed_at": "2026-09-23T12:30:00+00:00",
        "observed_at": "2026-09-23T12:32:00+00:00",
        "pending_outbox_count": None,
        "last_successful_sync_at": None,
        "quarantine_reference": "operator says disconnected",
    }
    path = tmp_path / "tablet.json"
    encoded = (json.dumps(evidence, sort_keys=True) + "\n").encode()
    path.write_bytes(encoded)
    manifest["tablet_replay_evidence_sha256"] = sha256(encoded).hexdigest()
    with pytest.raises(validator.ManifestError, match="quarantine disposition is disabled"):
        validator.validate_tablet_replay_evidence(manifest, path)


def test_original_tagged_target_cannot_be_omitted_or_reclassified() -> None:
    manifest = _manifest("delete")
    manifest["targets"]["orders"].pop(0)
    with pytest.raises(validator.ManifestError, match="immutable original-cohort"):
        validator.validate_manifest(manifest)


def test_every_deleted_shift_requires_one_replay_fence() -> None:
    manifest = _manifest("delete")
    manifest["replay_fence"].pop()
    with pytest.raises(validator.ManifestError, match="one entry for every deleted shift"):
        validator.validate_manifest(manifest)


def test_invoice_counter_cannot_be_reidentified_or_rewound() -> None:
    manifest = _manifest("retain")
    manifest["evidence"]["invoice_counter"]["last_seq"] = 69
    with pytest.raises(validator.ManifestError, match="cannot rewind below 70"):
        validator.validate_manifest(manifest)


def test_rehearsal_has_no_apply_or_commit_path_and_preserves_core_guards() -> None:
    sql = REHEARSAL.read_text(encoding="utf-8")
    body = BODY.read_text(encoding="utf-8")
    runner = RUNNER.read_text(encoding="utf-8")

    assert "ROLLBACK;" in sql
    assert "COMMIT;" not in sql
    assert "cleanup_apply" not in sql
    assert "--apply" not in runner
    assert "code30_combined_restore_" in sql
    assert "\\ir code30-3-combined-cleanup-body.sql" in sql
    assert "expected_full_table_digests" in body
    assert "immutable original 223-row cohort changed" in body
    assert "pg_constraint" in body
    assert "composite foreign key into a cleanup target" in body
    assert "triggers were not restored byte-identically" in body
    assert "a v2 cleanup receipt already exists" in body
    assert "replay fence is not one-to-one with every deleted keyed shift" in body
    assert validator.TAGGED_APP_SOURCE_GIT_SHA in body


def test_fingerprint_matches_sorted_table_count_hash_contract() -> None:
    manifest = _manifest("retain")
    lines = "\n".join(
        f"{name}:1:{'b' * 64}" for name in sorted(manifest["expected_full_table_digests"])
    )
    assert validator.canonical_state_fingerprint(manifest) == sha256(lines.encode()).hexdigest()


def test_apply_rehearsal_share_body_and_postcheck_has_no_writes() -> None:
    rehearsal = REHEARSAL.read_text(encoding="utf-8")
    apply = APPLY.read_text(encoding="utf-8")
    postcheck = POSTCHECK.read_text(encoding="utf-8")
    assert "\\ir code30-3-combined-cleanup-body.sql" in rehearsal
    assert "\\ir code30-3-combined-cleanup-body.sql" in apply
    assert "ROLLBACK;" in rehearsal and "COMMIT;" not in rehearsal
    assert "COMMIT;" in apply and "CODE30_3_COMBINED_CLEANUP_COMMITTED" in apply
    assert "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY" in postcheck
    assert "CREATE TEMP" not in postcheck
    assert "CREATE OR REPLACE" not in postcheck


def test_runtime_is_pinned_to_canonical_checkout_project_and_frozen_snapshot() -> None:
    runtime = RUNTIME.read_text(encoding="utf-8")
    assert "C3C_CANONICAL_CHECKOUT=/opt/d-company-erp" in runtime
    assert "C3C_CANONICAL_PROJECT=d-company-erp" in runtime
    assert "C3C_SNAPSHOT_ROOT=/var/lib/dcompany-erp/build-snapshots" in runtime
    assert "stat -Lc '%u:%g:%a:%F'" in runtime
    assert "frozen production Compose bytes differ from the tagged checkout" in runtime


def test_runtime_rejects_disagreeing_compose_working_directory_labels(tmp_path: Path) -> None:
    checkout = tmp_path / "checkout"
    checkout.mkdir()
    compose = checkout / "docker-compose.prod.yml"
    compose.write_text("services: {}\n", encoding="utf-8")
    (checkout / ".env").write_text("POSTGRES_DB=erp\n", encoding="utf-8")
    subprocess.run(["git", "init", "-q", str(checkout)], check=True)
    subprocess.run(["git", "-C", str(checkout), "config", "user.name", "Test"], check=True)
    subprocess.run(["git", "-C", str(checkout), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(checkout), "add", "docker-compose.prod.yml"], check=True)
    subprocess.run(["git", "-C", str(checkout), "commit", "-qm", "fixture"], check=True)
    revision = subprocess.check_output(
        ["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True
    ).strip()
    snapshot_root = tmp_path / "build-snapshots"
    snapshot = snapshot_root / f"{revision}.ABC123"
    snapshot.mkdir(parents=True, mode=0o700)
    (snapshot / "docker-compose.prod.yml").write_bytes(compose.read_bytes())
    script = r'''
source "$1"
C3C_TAGGED_APP_SHA=$2
C3C_CANONICAL_CHECKOUT=$3
C3C_CANONICAL_ENV=$3/.env
C3C_SNAPSHOT_ROOT=$4
snapshot=$5
stat() { printf '%s\n' '0:0:700:directory'; }
docker() {
  if [[ "$1 $2" == "ps -aq" ]]; then
    case "$*" in
      *service=postgres*) printf '%s\n' postgres-id ;;
      *service=backend*) printf '%s\n' backend-id ;;
      *service=caddy*) printf '%s\n' caddy-id ;;
    esac
  elif [[ "$1 $2" == "ps -q" ]]; then
    :
  elif [[ "$1" == inspect && "$2" == --format ]]; then
    case "$3" in
      *working_dir*)
        if [[ "${MOCK_BAD_WORKING_DIR:-0}" == 1 && "$4" == caddy-id ]]; then
          printf '%s\n' "$snapshot-other"
        else
          printf '%s\n' "$snapshot"
        fi ;;
      *compose.service*) printf '%s\n' "${4%-id}" ;;
      *compose.project*) printf '%s\n' d-company-erp ;;
      *State.Running*) [[ "$4" == postgres-id ]] && printf '%s\n' true || printf '%s\n' false ;;
      *Config.Env*) printf '%s\n' POSTGRES_DB=erp ;;
      *\.Image*) printf '%s\n' backend-image ;;
    esac
  elif [[ "$1 $2" == "image inspect" ]]; then
    printf '%s\n' "$C3C_TAGGED_APP_SHA"
  else
    return 99
  fi
}
c3c_resolve_stopped_runtime
'''
    command = ["bash", "-c", script, "runtime-test", str(RUNTIME), revision,
               str(checkout), str(snapshot_root), str(snapshot)]
    accepted = subprocess.run(command, text=True, capture_output=True, check=False)
    assert accepted.returncode == 0, accepted.stderr
    rejected = subprocess.run(
        command, text=True, capture_output=True, check=False,
        env={**__import__("os").environ, "MOCK_BAD_WORKING_DIR": "1"},
    )
    assert rejected.returncode != 0
    assert "disagree on the frozen snapshot directory" in rejected.stderr


def test_generator_build_candidate_binds_later_installation_and_final_timestamp() -> None:
    manifest = _manifest("retain")
    tagged, tagged_digests = validator._tagged_targets()
    installation_id = "ffffffff-0000-4000-8000-000000000003"
    final_at = "2026-09-23T12:30:00+00:00"
    rows: list[dict[str, object]] = [{
        "kind": "identity",
        "schema_revision": "0082",
        "post_cutoff_shift_ids": sorted(tagged["shifts"] | {LATER_SHIFT}),
        "open_shift_count": 0,
        "unfinished_order_count": 0,
        "active_session_count": 0,
        "undelivered_sheets_count": 0,
        "prior_v2_receipt_count": 0,
        "company_shift_count": 6,
        "actor_count": 1,
        "terminal_count": 1,
        "later_shift_installation_id": installation_id,
        "later_final_business_closed_at": final_at,
        "later_invalid_order_count": 0,
        "later_invalid_session_count": 0,
        "current_installation_count": 1,
        "current_installation_pending": 0,
        "current_installation_last_successful_sync_at": "2026-09-23T12:31:00+00:00",
        "current_installation_last_seen_at": "2026-09-23T12:32:00+00:00",
    }, {
        "kind": "evidence",
        "paid_total_minor": 750000,
        "retired_invoice_numbers": ["D/MN/26-27/00024"],
        "google_sheets_event_ids_owner_deletes": ["012862d2-28e2-5ae3-a61e-95227f3976f3"],
        "invoice_counters": [{"id": "9f78c3b5-c5f4-425d-9def-4b34298a2931", "last_seq": 71}],
        "fence": manifest["replay_fence"],
    }]
    for table, ids in tagged.items():
        rows.extend({"kind": "row", "cohort": "original", "table_name": table,
                     "id": row_id, "row_sha256": "a" * 64} for row_id in ids)
        count, digest = tagged_digests[table]
        rows.append({"kind": "original_digest", "table_name": table,
                     "row_count": count, "row_sha256": digest})
    rows.extend([
        {"kind": "row", "cohort": "later", "table_name": "shifts",
         "id": LATER_SHIFT, "row_sha256": "a" * 64},
        {"kind": "row", "cohort": "later", "table_name": "gaming_sessions",
         "id": LATER_SESSION, "row_sha256": "a" * 64},
    ])
    rows.extend({"kind": "full_digest", "table_name": name,
                 "row_count": value["row_count"], "row_sha256": value["row_sha256"]}
                for name, value in manifest["expected_full_table_digests"].items())
    rows.extend({"kind": "trigger", **trigger} for trigger in manifest["expected_triggers"])
    candidate = generator.build_candidate(
        rows, tagged, tagged_digests, LATER_SHIFT, "retain", "f" * 64, "1" * 40,
        sha256(BODY.read_bytes()).hexdigest(), "8" * 64,
        {"installation_id": installation_id, "final_business_closed_at": final_at,
         "last_successful_sync_at": "2026-09-23T12:31:00+00:00",
         "observed_at": "2026-09-23T12:32:00+00:00"},
        validator.TARGET_TABLES, sorted(manifest["expected_full_table_digests"]),
    )
    assert candidate["retained_rows"]["shifts"][0]["id"] == LATER_SHIFT
    assert candidate["tablet_replay_evidence_sha256"] == "8" * 64
