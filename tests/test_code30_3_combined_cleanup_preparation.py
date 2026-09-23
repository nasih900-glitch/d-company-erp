from __future__ import annotations

import importlib.util
import json
from hashlib import sha256
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "infra" / "scripts" / "prepare-code30-3-combined-cleanup.py"
REHEARSAL = ROOT / "infra" / "scripts" / "rehearse-code30-3-combined-cleanup.sql"
RUNNER = ROOT / "infra" / "scripts" / "rehearse-code30-3-combined-cleanup.sh"
spec = importlib.util.spec_from_file_location("combined_cleanup_validator", VALIDATOR)
assert spec and spec.loader
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


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
        "operator_reviewed_at": "2026-09-23T12:00:00+00:00",
        "operator_review_evidence_sha256": "9" * 64,
        "later_cohort_decision": decision,
        "backup_sha256": "f" * 64,
        "tagged_app_source_git_sha": validator.TAGGED_APP_SOURCE_GIT_SHA,
        "maintenance_source_git_sha": "1" * 40,
        "maintenance_sql_sha256": sha256(REHEARSAL.read_bytes()).hexdigest(),
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
    runner = RUNNER.read_text(encoding="utf-8")

    assert "ROLLBACK;" in sql
    assert "COMMIT;" not in sql
    assert "cleanup_apply" not in sql
    assert "--apply" not in runner
    assert "code30_combined_restore_" in sql
    assert "expected_full_table_digests" in sql
    assert "immutable original 223-row cohort changed" in sql
    assert "pg_constraint" in sql
    assert "composite foreign key into a cleanup target" in sql
    assert "triggers were not restored byte-identically" in sql
    assert "a v2 cleanup receipt already exists" in sql
    assert "replay fence is not one-to-one with every deleted keyed shift" in sql
    assert validator.TAGGED_APP_SOURCE_GIT_SHA in sql
