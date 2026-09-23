#!/usr/bin/env python3
"""Validate the operator-reviewed manifest for the combined Code30.3 cleanup rehearsal."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import NoReturn
from uuid import UUID


TAGGED_APP_SOURCE_GIT_SHA = "ad5adfb93c3488f1f931ca27da53824aa57d3dc5"
TAGGED_CLEANUP_SQL_SHA256 = "07e7c62f4241d37864c17c54edd52da0cb05492bd23b465cc9f70aba1addc90d"
TAGGED_CLEANUP_SQL = Path(__file__).with_name("cleanup-code30-3-trial-data.sql")
TARGET_TABLES = (
    "gaming_session_extensions",
    "gaming_sessions",
    "order_lines",
    "payments",
    "orders",
    "shifts",
)
REQUIRED_FULL_TABLES = {
    *TARGET_TABLES,
    "audit_log",
    "google_sheets_deliveries",
    "idempotency_keys",
    "in_invoice_counters",
}
EXPECTED_TRIGGERS = {
    ("gaming_session_extensions", "trg_gaming_session_extensions_immutable"),
    ("order_lines", "trg_order_lines_paid_source_integrity"),
    ("orders", "trg_orders_paid_source_integrity"),
    ("payments", "trg_payments_immutable"),
}
TOP_LEVEL_KEYS = {
    "schema_version",
    "cleanup_id",
    "classification_evidence_sha256",
    "operator_reviewed_at",
    "operator_review_evidence_sha256",
    "later_cohort_decision",
    "backup_sha256",
    "tagged_app_source_git_sha",
    "maintenance_source_git_sha",
    "maintenance_sql_sha256",
    "expected_schema_revision",
    "company_id",
    "actor_user_id",
    "terminal_id",
    "targets",
    "retained_rows",
    "replay_fence",
    "expected_triggers",
    "expected_full_table_digests",
    "evidence",
}
HEX_40 = re.compile(r"[0-9a-f]{40}")
HEX_64 = re.compile(r"[0-9a-f]{64}")
CLEANUP_ID = re.compile(r"[a-z0-9][a-z0-9.-]{7,63}")
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
MAX_APPROVAL_BYTES = 64 * 1024
OPERATOR_REVIEW_KEYS = {
    "schema_version",
    "reviewer_role",
    "reviewed_at",
    "decision",
    "backup_sha256",
    "classification_evidence_sha256",
    "candidate_manifest_sha256",
}


class ManifestError(ValueError):
    """The manifest is incomplete, ambiguous, or not canonical."""


def _pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ManifestError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _constant(value: str) -> NoReturn:
    raise ManifestError(f"invalid JSON constant: {value}")


def _exact_keys(value: object, expected: set[str], label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise ManifestError(f"{label} must be an object")
    actual = set(value)
    if actual != expected:
        raise ManifestError(
            f"{label} fields changed: missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )
    return value


def _uuid(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise ManifestError(f"{label} must be a canonical UUID")
    try:
        parsed = UUID(value)
    except ValueError as exc:
        raise ManifestError(f"{label} must be a canonical UUID") from exc
    if str(parsed) != value:
        raise ManifestError(f"{label} must be a lowercase canonical UUID")
    return value


def _sha(value: object, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ManifestError(f"{label} has an invalid lowercase hexadecimal digest")
    return value


def _sorted_unique(values: list[str], label: str) -> None:
    if values != sorted(values) or len(values) != len(set(values)):
        raise ManifestError(f"{label} must be sorted and unique")


def load_manifest(path: Path) -> dict[str, object]:
    raw = path.read_bytes()
    if not raw or len(raw) > MAX_MANIFEST_BYTES:
        raise ManifestError("manifest size is invalid")
    try:
        document = json.loads(
            raw,
            object_pairs_hook=_pairs,
            parse_constant=_constant,
        )
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ManifestError(f"manifest is not valid UTF-8 JSON: {exc}") from exc
    return _exact_keys(document, TOP_LEVEL_KEYS, "manifest")


def canonical_candidate_sha256(document: dict[str, object]) -> str:
    """Hash reviewable content without the two fields populated by approval."""

    candidate = copy.deepcopy(document)
    candidate["operator_reviewed_at"] = ""
    candidate["operator_review_evidence_sha256"] = ""
    encoded = json.dumps(candidate, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_review_files(
    document: dict[str, object], classification_path: Path, review_path: Path
) -> None:
    classification = classification_path.read_bytes()
    if not classification or len(classification) > MAX_APPROVAL_BYTES:
        raise ManifestError("classification evidence size is invalid")
    if hashlib.sha256(classification).hexdigest() != document["classification_evidence_sha256"]:
        raise ManifestError("classification evidence hash differs from the manifest")

    raw = review_path.read_bytes()
    if not raw or len(raw) > MAX_APPROVAL_BYTES:
        raise ManifestError("operator review evidence size is invalid")
    if hashlib.sha256(raw).hexdigest() != document["operator_review_evidence_sha256"]:
        raise ManifestError("operator review evidence hash differs from the manifest")
    try:
        review = json.loads(raw, object_pairs_hook=_pairs, parse_constant=_constant)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ManifestError(f"operator review evidence is invalid JSON: {exc}") from exc
    review = _exact_keys(review, OPERATOR_REVIEW_KEYS, "operator review evidence")
    if review["schema_version"] != 1 or review["reviewer_role"] != "maintenance_operator":
        raise ManifestError("operator review evidence identity is invalid")
    if review["decision"] != document["later_cohort_decision"]:
        raise ManifestError("operator review decision differs from the manifest")
    if review["backup_sha256"] != document["backup_sha256"]:
        raise ManifestError("operator review backup hash differs from the manifest")
    if review["classification_evidence_sha256"] != document["classification_evidence_sha256"]:
        raise ManifestError("operator review classification hash differs from the manifest")
    if review["candidate_manifest_sha256"] != canonical_candidate_sha256(document):
        raise ManifestError("operator review does not bind the canonical candidate manifest")
    if review["reviewed_at"] != document["operator_reviewed_at"]:
        raise ManifestError("operator review timestamp differs from operator_reviewed_at")


def _tagged_targets() -> tuple[dict[str, set[str]], dict[str, tuple[int, str]]]:
    """Read the immutable tagged SQL only after checking its reviewed byte hash."""

    source = TAGGED_CLEANUP_SQL.read_bytes()
    if hashlib.sha256(source).hexdigest() != TAGGED_CLEANUP_SQL_SHA256:
        raise ManifestError("immutable tagged cleanup SQL bytes changed")
    text = source.decode("utf-8")
    targets_match = re.search(
        r"INSERT INTO c3_target \(table_name, id\) VALUES\n(?P<body>.*?);",
        text,
        flags=re.DOTALL,
    )
    digests_match = re.search(
        r"INSERT INTO c3_expected_target VALUES\n(?P<body>.*?);",
        text,
        flags=re.DOTALL,
    )
    if targets_match is None or digests_match is None:
        raise ManifestError("immutable tagged cleanup SQL target blocks are missing")
    targets: dict[str, set[str]] = {name: set() for name in TARGET_TABLES}
    for table_name, row_id in re.findall(
        r"\('([a-z_]+)', '([0-9a-f-]{36})'\)", targets_match.group("body")
    ):
        if table_name not in targets or row_id in targets[table_name]:
            raise ManifestError("immutable tagged cleanup SQL targets are malformed")
        targets[table_name].add(row_id)
    digests = {
        table_name: (int(count), digest)
        for table_name, count, digest in re.findall(
            r"\('([a-z_]+)', ([0-9]+), '([0-9a-f]{64})'\)",
            digests_match.group("body"),
        )
    }
    if set(digests) != set(TARGET_TABLES) or any(
        len(targets[name]) != digests[name][0] for name in TARGET_TABLES
    ):
        raise ManifestError("immutable tagged cleanup SQL target counts changed")
    return targets, digests


def validate_manifest(document: dict[str, object]) -> None:
    if type(document["schema_version"]) is not int or document["schema_version"] != 1:
        raise ManifestError("schema_version must be 1")
    cleanup_id = document["cleanup_id"]
    if not isinstance(cleanup_id, str) or CLEANUP_ID.fullmatch(cleanup_id) is None:
        raise ManifestError("cleanup_id is not canonical")
    if cleanup_id in {
        "code30.1-20260920",
        "code30.1-production-trial-cleanup-20260920",
        "production-trial-cleanup-20260920",
        "code30.3-trial-cleanup-20260923",
    }:
        raise ManifestError("cleanup_id reuses an existing receipt identity")
    reviewed_at = document["operator_reviewed_at"]
    if not isinstance(reviewed_at, str):
        raise ManifestError("operator_reviewed_at must be a timezone-aware timestamp")
    try:
        timestamp = datetime.fromisoformat(reviewed_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ManifestError("operator_reviewed_at must be a timezone-aware timestamp") from exc
    if timestamp.utcoffset() is None:
        raise ManifestError("operator_reviewed_at must include a timezone")
    _sha(
        document["classification_evidence_sha256"],
        HEX_64,
        "classification_evidence_sha256",
    )
    _sha(
        document["operator_review_evidence_sha256"],
        HEX_64,
        "operator_review_evidence_sha256",
    )
    decision = document["later_cohort_decision"]
    if decision not in {"delete", "retain"}:
        raise ManifestError("later_cohort_decision must be explicitly delete or retain")

    _sha(document["backup_sha256"], HEX_64, "backup_sha256")
    if document["tagged_app_source_git_sha"] != TAGGED_APP_SOURCE_GIT_SHA:
        raise ManifestError("tagged_app_source_git_sha must be the immutable v3.1.30 merge")
    _sha(document["maintenance_source_git_sha"], HEX_40, "maintenance_source_git_sha")
    _sha(document["maintenance_sql_sha256"], HEX_64, "maintenance_sql_sha256")
    if document["expected_schema_revision"] != "0082":
        raise ManifestError("expected_schema_revision must be 0082")
    for name in ("company_id", "actor_user_id", "terminal_id"):
        _uuid(document[name], name)

    tagged_targets, _tagged_digests = _tagged_targets()

    def validate_rows(container_name: str, *, allow_empty: bool) -> dict[str, list[str]]:
        container = _exact_keys(document[container_name], set(TARGET_TABLES), container_name)
        ids_by_table: dict[str, list[str]] = {}
        for table_name in TARGET_TABLES:
            rows = container[table_name]
            if not isinstance(rows, list) or (not allow_empty and not rows):
                raise ManifestError(f"{container_name}.{table_name} must be an array")
            ids: list[str] = []
            for index, raw in enumerate(rows):
                row = _exact_keys(
                    raw,
                    {"id", "row_sha256"},
                    f"{container_name}.{table_name}[{index}]",
                )
                ids.append(_uuid(row["id"], f"{container_name}.{table_name}[{index}].id"))
                _sha(
                    row["row_sha256"],
                    HEX_64,
                    f"{container_name}.{table_name}[{index}].row_sha256",
                )
            _sorted_unique(ids, f"{container_name}.{table_name}")
            ids_by_table[table_name] = ids
        return ids_by_table

    target_ids = validate_rows("targets", allow_empty=False)
    retained_ids = validate_rows("retained_rows", allow_empty=True)
    for table_name in TARGET_TABLES:
        if not tagged_targets[table_name].issubset(target_ids[table_name]):
            raise ManifestError(
                f"targets.{table_name} omits or changes an immutable original-cohort ID"
            )
        if set(target_ids[table_name]) & set(retained_ids[table_name]):
            raise ManifestError(f"{table_name} cannot be both deleted and retained")
        extra_targets = set(target_ids[table_name]) - tagged_targets[table_name]
        if decision == "retain" and extra_targets:
            raise ManifestError("retain decision cannot add later rows to deletion targets")
        if decision == "delete" and retained_ids[table_name]:
            raise ManifestError("delete decision cannot put later rows in retained_rows")
    expected_target_shift_count = 6 if decision == "delete" else 5
    if len(target_ids["shifts"]) != expected_target_shift_count:
        raise ManifestError(f"{decision} decision has the wrong deleted shift count")
    if len(target_ids["shifts"]) + len(retained_ids["shifts"]) != 6:
        raise ManifestError("target and retained allowlists must cover exactly six shifts")
    if decision == "retain" and len(retained_ids["shifts"]) != 1:
        raise ManifestError("retain decision must pin exactly one later shift")
    if decision == "delete" and not (
        set(target_ids["gaming_sessions"]) - tagged_targets["gaming_sessions"]
    ):
        raise ManifestError("delete decision must pin the later Gaming session")
    if decision == "retain" and not retained_ids["gaming_sessions"]:
        raise ManifestError("retain decision must pin the later Gaming session")

    evidence = _exact_keys(
        document["evidence"],
        {
            "original_five_shift_ids",
            "later_shift_id",
            "paid_total_minor",
            "retired_invoice_numbers",
            "google_sheets_event_ids_owner_deletes",
            "invoice_counter",
        },
        "evidence",
    )
    original = evidence["original_five_shift_ids"]
    if not isinstance(original, list) or len(original) != 5:
        raise ManifestError("evidence.original_five_shift_ids must contain exactly five IDs")
    original_ids = [_uuid(value, "evidence.original_five_shift_ids") for value in original]
    _sorted_unique(original_ids, "evidence.original_five_shift_ids")
    later = _uuid(evidence["later_shift_id"], "evidence.later_shift_id")
    if set(original_ids) != tagged_targets["shifts"]:
        raise ManifestError("original_five_shift_ids differ from the immutable tagged cohort")
    combined_shift_ids = sorted([*target_ids["shifts"], *retained_ids["shifts"]])
    if sorted([*original_ids, later]) != combined_shift_ids:
        raise ManifestError("original and later shift evidence does not equal target plus retained shifts")
    if decision == "delete" and later not in target_ids["shifts"]:
        raise ManifestError("delete decision must place the later shift in targets")
    if decision == "retain" and later not in retained_ids["shifts"]:
        raise ManifestError("retain decision must place the later shift in retained_rows")
    if type(evidence["paid_total_minor"]) is not int or evidence["paid_total_minor"] < 0:
        raise ManifestError("evidence.paid_total_minor must be a non-negative integer")
    for name in ("retired_invoice_numbers", "google_sheets_event_ids_owner_deletes"):
        values = evidence[name]
        if not isinstance(values, list) or any(not isinstance(value, str) or not value for value in values):
            raise ManifestError(f"evidence.{name} must be a non-empty string array")
        _sorted_unique(values, f"evidence.{name}")
    counter = _exact_keys(evidence["invoice_counter"], {"id", "last_seq"}, "evidence.invoice_counter")
    _uuid(counter["id"], "evidence.invoice_counter.id")
    if counter["id"] != "9f78c3b5-c5f4-425d-9def-4b34298a2931":
        raise ManifestError("evidence.invoice_counter.id differs from the immutable counter")
    if type(counter["last_seq"]) is not int or counter["last_seq"] < 70:
        raise ManifestError("evidence.invoice_counter.last_seq cannot rewind below 70")

    fence = document["replay_fence"]
    if not isinstance(fence, list) or len(fence) != len(target_ids["shifts"]):
        raise ManifestError("replay_fence must contain one entry for every deleted shift")
    action_keys: list[str] = []
    sources: list[str] = []
    for index, raw in enumerate(fence):
        entry = _exact_keys(
            raw,
            {"action_type", "action_key", "request_hash", "user_id", "terminal_id", "source_entity_id"},
            f"replay_fence[{index}]",
        )
        if entry["action_type"] != "shift_open":
            raise ManifestError("every replay_fence entry must be shift_open")
        action_key = entry["action_key"]
        if not isinstance(action_key, str) or not 1 <= len(action_key) <= 160 or action_key.strip() != action_key:
            raise ManifestError("replay_fence action_key is invalid")
        action_keys.append(action_key)
        sources.append(_uuid(entry["source_entity_id"], "replay_fence.source_entity_id"))
        _sha(entry["request_hash"], HEX_64, "replay_fence.request_hash")
        _uuid(entry["user_id"], "replay_fence.user_id")
        _uuid(entry["terminal_id"], "replay_fence.terminal_id")
    _sorted_unique(action_keys, "replay_fence action keys")
    if len(set(sources)) != len(target_ids["shifts"]) or sorted(sources) != target_ids["shifts"]:
        raise ManifestError("replay_fence must cover every deleted shift exactly once")

    triggers = document["expected_triggers"]
    if not isinstance(triggers, list) or len(triggers) != len(EXPECTED_TRIGGERS):
        raise ManifestError("expected_triggers must contain exactly the guarded four")
    trigger_keys: list[tuple[str, str]] = []
    for index, raw in enumerate(triggers):
        trigger = _exact_keys(
            raw,
            {"table_name", "trigger_name", "enabled", "definition_sha256", "function_sha256"},
            f"expected_triggers[{index}]",
        )
        key = (trigger["table_name"], trigger["trigger_name"])
        if not all(isinstance(value, str) for value in key) or trigger["enabled"] != "O":
            raise ManifestError("expected trigger identity or enabled state is invalid")
        trigger_keys.append(key)
        _sha(trigger["definition_sha256"], HEX_64, "expected trigger definition_sha256")
        _sha(trigger["function_sha256"], HEX_64, "expected trigger function_sha256")
    if set(trigger_keys) != EXPECTED_TRIGGERS or trigger_keys != sorted(trigger_keys):
        raise ManifestError("expected_triggers must be the sorted guarded trigger set")

    table_digests = document["expected_full_table_digests"]
    if not isinstance(table_digests, dict) or not REQUIRED_FULL_TABLES.issubset(table_digests):
        raise ManifestError("expected_full_table_digests omits required production tables")
    if list(table_digests) != sorted(table_digests):
        raise ManifestError("expected_full_table_digests keys must be sorted")
    for table_name, raw in table_digests.items():
        if not isinstance(table_name, str) or re.fullmatch(r"[a-z][a-z0-9_]*", table_name) is None:
            raise ManifestError("expected_full_table_digests contains an invalid table name")
        digest = _exact_keys(raw, {"row_count", "row_sha256"}, f"expected_full_table_digests.{table_name}")
        if type(digest["row_count"]) is not int or digest["row_count"] < 0:
            raise ManifestError(f"expected_full_table_digests.{table_name}.row_count is invalid")
        _sha(digest["row_sha256"], HEX_64, f"expected_full_table_digests.{table_name}.row_sha256")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--classification-evidence", type=Path)
    parser.add_argument("--operator-review-evidence", type=Path)
    parser.add_argument(
        "--print-field",
        choices=(
            "backup_sha256",
            "maintenance_sql_sha256",
            "maintenance_source_git_sha",
            "classification_evidence_sha256",
            "operator_review_evidence_sha256",
        ),
    )
    args = parser.parse_args()
    try:
        document = load_manifest(args.manifest)
        validate_manifest(document)
        if (args.classification_evidence is None) != (args.operator_review_evidence is None):
            raise ManifestError("classification and operator review evidence are required together")
        if args.classification_evidence is not None:
            validate_review_files(
                document, args.classification_evidence, args.operator_review_evidence
            )
    except (ManifestError, OSError) as exc:
        print(f"Combined cleanup manifest rejected: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
    if args.print_field:
        print(document[args.print_field])
    elif not args.quiet:
        print("Combined cleanup manifest is structurally complete and owner-reviewable.")


if __name__ == "__main__":
    main()
