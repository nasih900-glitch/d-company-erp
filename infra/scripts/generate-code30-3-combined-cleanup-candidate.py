#!/usr/bin/env python3
"""Generate an unapproved Code30.3 cleanup candidate from one offline restore snapshot.

The generated file is evidence for human review, not authority to run cleanup.
It deliberately fails the approved-manifest validator until the owner decision,
approval evidence, and committed maintenance identity are independently checked.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any
from uuid import UUID


SCRIPT_DIR = Path(__file__).resolve().parent
VALIDATOR = SCRIPT_DIR / "prepare-code30-3-combined-cleanup.py"
REHEARSAL_SQL = SCRIPT_DIR / "rehearse-code30-3-combined-cleanup.sql"
RESTORE_NAME = re.compile(r"code30_combined_restore_[0-9]+_[0-9]+")
TABLE_NAME = re.compile(r"[a-z][a-z0-9_]*")
COMPANY_ID = "8f323fba-4358-45fe-9d3b-a8e0fae52993"
ACTOR_USER_ID = "7016c42c-11c9-48fa-a30a-5d66a8c970b7"
TERMINAL_ID = "789353a8-09e4-4ef2-9fa8-ac73c426bfc8"
CLEANUP_ID = "code30.3-combined-cleanup-20260923"


class CandidateError(ValueError):
    """The restored snapshot cannot yield a safe cleanup candidate."""


def load_validator() -> Any:
    spec = importlib.util.spec_from_file_location("code30_combined_validator", VALIDATOR)
    if spec is None or spec.loader is None:
        raise CandidateError("approved-manifest validator is unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_uuid(raw: str, label: str) -> str:
    try:
        value = str(UUID(raw))
    except ValueError as exc:
        raise CandidateError(f"{label} is not a UUID") from exc
    if value != raw:
        raise CandidateError(f"{label} must be a lowercase canonical UUID")
    return value


def psql(database: str, port: int, sql: str) -> list[str]:
    process = subprocess.run(
        [
            "psql", "-X", "--no-psqlrc", "--set=ON_ERROR_STOP=1",
            "--tuples-only", "--no-align", "--quiet", "--host=127.0.0.1",
            f"--port={port}", f"--dbname={database}",
        ],
        input=sql,
        text=True,
        capture_output=True,
        check=False,
    )
    if process.returncode:
        raise CandidateError(f"isolated restore query failed: {process.stderr.strip()[:500]}")
    return [line for line in process.stdout.splitlines() if line]


def quoted_table(name: str) -> str:
    if TABLE_NAME.fullmatch(name) is None:
        raise CandidateError(f"unexpected public table name: {name!r}")
    return f'public."{name}"'


def original_cte(tagged: dict[str, set[str]]) -> str:
    values = ",\n".join(
        f"('{table_name}', '{row_id}'::uuid)"
        for table_name in sorted(tagged)
        for row_id in sorted(tagged[table_name])
    )
    return f"original(table_name, id) AS (VALUES\n{values}\n)"


def cohort_ctes(tagged: dict[str, set[str]], later_shift_id: str) -> str:
    return f"""
{original_cte(tagged)},
later_shift AS (SELECT id FROM shifts WHERE id = '{later_shift_id}'::uuid),
later_orders AS (SELECT id FROM orders WHERE shift_id IN (SELECT id FROM later_shift)),
later_sessions AS (
  SELECT id FROM gaming_sessions
   WHERE shift_id IN (SELECT id FROM later_shift)
      OR order_id IN (SELECT id FROM later_orders)
),
later_rows(table_name, id) AS (
  SELECT 'shifts', id FROM later_shift
  UNION ALL SELECT 'orders', id FROM later_orders
  UNION ALL SELECT 'payments', id FROM payments
   WHERE shift_id IN (SELECT id FROM later_shift)
      OR order_id IN (SELECT id FROM later_orders)
  UNION ALL SELECT 'gaming_sessions', id FROM later_sessions
  UNION ALL SELECT 'order_lines', id FROM order_lines
   WHERE order_id IN (SELECT id FROM later_orders)
  UNION ALL SELECT 'gaming_session_extensions', id FROM gaming_session_extensions
   WHERE gaming_session_id IN (SELECT id FROM later_sessions)
),
reviewed(cohort, table_name, id) AS (
  SELECT 'original', table_name, id FROM original
  UNION ALL SELECT 'later', table_name, id FROM later_rows
)
"""


def row_queries(tables: tuple[str, ...]) -> str:
    selects = []
    for table_name in tables:
        selects.append(f"""
SELECT jsonb_build_object(
  'kind', 'row', 'cohort', r.cohort, 'table_name', '{table_name}',
  'id', x.id::text,
  'row_sha256', encode(sha256(convert_to(to_jsonb(x)::text, 'UTF8')), 'hex')
)::text
FROM {quoted_table(table_name)} x
JOIN reviewed r ON r.table_name = '{table_name}' AND r.id = x.id
""")
    return " UNION ALL ".join(selects) + " ORDER BY 1;"


def original_digest_queries(tables: tuple[str, ...]) -> str:
    selects = []
    for table_name in tables:
        selects.append(f"""
SELECT jsonb_build_object(
  'kind', 'original_digest', 'table_name', '{table_name}',
  'row_count', count(*),
  'row_sha256', encode(sha256(convert_to(
    coalesce(string_agg(to_jsonb(x)::text, E'\\n' ORDER BY x.id), ''), 'UTF8')), 'hex')
)::text
FROM {quoted_table(table_name)} x
JOIN original r ON r.table_name = '{table_name}' AND r.id = x.id
""")
    return " UNION ALL ".join(selects) + ";"


def full_digest_queries(tables: list[str]) -> str:
    selects = []
    for table_name in tables:
        selects.append(f"""
SELECT jsonb_build_object(
  'kind', 'full_digest', 'table_name', '{table_name}',
  'row_count', count(*),
  'row_sha256', encode(sha256(convert_to(
    coalesce(string_agg(to_jsonb(x)::text, E'\\n' ORDER BY to_jsonb(x)::text), ''),
    'UTF8')), 'hex')
)::text
FROM {quoted_table(table_name)} x
""")
    return " UNION ALL ".join(selects) + ";"


def snapshot_sql(tagged: dict[str, set[str]], later_id: str,
                 decision: str, tables: list[str], target_tables: tuple[str, ...]) -> str:
    ctes = cohort_ctes(tagged, later_id)
    target_condition = "TRUE" if decision == "delete" else "FALSE"
    return f"""
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';
SET LOCAL bytea_output = 'hex';
SET LOCAL DateStyle = 'ISO, YMD';
SET LOCAL IntervalStyle = 'postgres';
SET LOCAL extra_float_digits = 1;
SELECT jsonb_build_object(
  'kind', 'identity', 'database', current_database(),
  'schema_revision', (SELECT version_num FROM alembic_version),
  'post_cutoff_shift_ids', (SELECT coalesce(jsonb_agg(id::text ORDER BY id::text), '[]'::jsonb)
    FROM shifts WHERE opened_at >= '2026-09-20T00:00:00Z'),
  'open_shift_count', (SELECT count(*) FROM shifts WHERE status <> 'closed'),
  'unfinished_order_count', (SELECT count(*) FROM orders
    WHERE status NOT IN ('paid', 'void', 'refunded')),
  'active_session_count', (SELECT count(*) FROM gaming_sessions
    WHERE status NOT IN ('ended', 'cancelled')),
  'undelivered_sheets_count', (SELECT count(*) FROM google_sheets_deliveries
    WHERE status <> 'delivered'),
  'prior_v2_receipt_count', (SELECT count(*) FROM audit_log
    WHERE action = 'verified_trial_cleanup' OR entity_type = 'TrialCleanupReceipt'),
  'company_shift_count', (SELECT count(*) FROM shifts WHERE id IN (
    {','.join("'" + row_id + "'::uuid" for row_id in sorted(tagged['shifts'] | {later_id}))}
    ) AND company_id = '{COMPANY_ID}'::uuid),
  'actor_count', (SELECT count(*) FROM users WHERE id = '{ACTOR_USER_ID}'::uuid
    AND company_id = '{COMPANY_ID}'::uuid AND status = 'active' AND deleted_at IS NULL),
  'terminal_count', (SELECT count(*) FROM terminals t JOIN branches b ON b.id = t.branch_id
    WHERE t.id = '{TERMINAL_ID}'::uuid AND t.is_active
      AND b.company_id = '{COMPANY_ID}'::uuid)
)::text;
WITH {ctes}
{row_queries(target_tables)}
WITH {original_cte(tagged)}
{original_digest_queries(target_tables)}
{full_digest_queries(tables)}
SELECT jsonb_build_object(
  'kind', 'trigger', 'table_name', tgrelid::regclass::text,
  'trigger_name', tgname, 'enabled', tgenabled,
  'definition_sha256', encode(sha256(convert_to(pg_get_triggerdef(oid, true), 'UTF8')), 'hex'),
  'function_sha256', encode(sha256(convert_to(pg_get_functiondef(tgfoid), 'UTF8')), 'hex')
)::text FROM pg_trigger
WHERE NOT tgisinternal AND (tgrelid::regclass::text, tgname) IN (
  ('gaming_session_extensions','trg_gaming_session_extensions_immutable'),
  ('order_lines','trg_order_lines_paid_source_integrity'),
  ('orders','trg_orders_paid_source_integrity'),
  ('payments','trg_payments_immutable')
);
WITH {ctes},
target_ids AS (
  SELECT table_name, id FROM original
  UNION ALL SELECT table_name, id FROM later_rows WHERE {target_condition}
)
SELECT jsonb_build_object(
  'kind', 'evidence',
  'paid_total_minor', (SELECT coalesce(sum(total_minor), 0) FROM orders
    WHERE status = 'paid' AND id IN (SELECT id FROM target_ids WHERE table_name = 'orders')),
  'retired_invoice_numbers', (SELECT coalesce(jsonb_agg(invoice_no ORDER BY invoice_no), '[]'::jsonb)
    FROM orders WHERE invoice_no IS NOT NULL
      AND id IN (SELECT id FROM target_ids WHERE table_name = 'orders')),
  'google_sheets_event_ids_owner_deletes', (SELECT coalesce(jsonb_agg(event_id::text ORDER BY event_id::text), '[]'::jsonb)
    FROM google_sheets_deliveries
    WHERE event_type = 'pos.order.paid' AND source_type = 'pos_order'
      AND source_id IN (SELECT id::text FROM target_ids WHERE table_name = 'orders')),
  'invoice_counters', (SELECT coalesce(jsonb_agg(jsonb_build_object('id', id::text, 'last_seq', last_seq)), '[]'::jsonb)
    FROM in_invoice_counters),
  'fence', (SELECT coalesce(jsonb_agg(jsonb_build_object(
      'action_type', 'shift_open', 'action_key', opening_action_id,
      'request_hash', opening_request_hash, 'user_id', opened_by::text,
      'terminal_id', terminal_id::text, 'source_entity_id', id::text)
      ORDER BY opening_action_id), '[]'::jsonb)
    FROM shifts WHERE id IN (SELECT id FROM target_ids WHERE table_name = 'shifts'))
)::text;
COMMIT;
"""


def expect_int(value: object, label: str) -> int:
    if type(value) is not int or value < 0:
        raise CandidateError(f"{label} is not a non-negative integer")
    return value


def build_candidate(rows: list[dict[str, Any]], tagged: dict[str, set[str]],
                    tagged_digests: dict[str, tuple[int, str]], later_id: str,
                    decision: str, backup_sha: str, maintenance_sha: str,
                    sql_sha: str, target_tables: tuple[str, ...],
                    public_tables: list[str]) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        kind = row.get("kind")
        if not isinstance(kind, str):
            raise CandidateError("snapshot returned an untyped record")
        groups.setdefault(kind, []).append(row)
    for kind in ("identity", "evidence"):
        if len(groups.get(kind, [])) != 1:
            raise CandidateError(f"snapshot returned the wrong number of {kind} records")
    identity = groups["identity"][0]
    if identity.get("schema_revision") != "0082":
        raise CandidateError("restored backup must have migration 0082")
    if any(identity.get(key) != 0 for key in (
        "open_shift_count", "unfinished_order_count", "active_session_count",
        "undelivered_sheets_count", "prior_v2_receipt_count",
    )):
        raise CandidateError("restored backup contains unfinished work or an existing v2 receipt")
    if identity.get("company_shift_count") != 6 or identity.get("actor_count") != 1 \
            or identity.get("terminal_count") != 1:
        raise CandidateError("reviewed shifts, actor, or terminal differ from the tagged company")
    reviewed_shift_ids = sorted(tagged["shifts"] | {later_id})
    if identity.get("post_cutoff_shift_ids") != reviewed_shift_ids:
        raise CandidateError("post-Sep20 shifts differ from the original five plus later shift")
    for key in ("open_shift_count", "unfinished_order_count", "active_session_count",
                "undelivered_sheets_count", "prior_v2_receipt_count", "company_shift_count",
                "actor_count", "terminal_count"):
        expect_int(identity[key], key)

    original_digests = {r["table_name"]: (r["row_count"], r["row_sha256"])
                        for r in groups.get("original_digest", [])}
    if original_digests != tagged_digests:
        raise CandidateError("original 223 rows no longer match immutable tagged aggregate hashes")

    target_rows: dict[str, list[dict[str, str]]] = {name: [] for name in target_tables}
    retained_rows: dict[str, list[dict[str, str]]] = {name: [] for name in target_tables}
    seen: set[tuple[str, str]] = set()
    original_seen: dict[str, set[str]] = {name: set() for name in target_tables}
    later_seen: dict[str, set[str]] = {name: set() for name in target_tables}
    for row in groups.get("row", []):
        table_name, row_id, cohort, digest = (
            row.get("table_name"), row.get("id"), row.get("cohort"), row.get("row_sha256")
        )
        if table_name not in target_rows or cohort not in {"original", "later"}:
            raise CandidateError("snapshot returned an unexpected cohort row")
        canonical_uuid(row_id, "reviewed row ID")
        if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise CandidateError("snapshot returned an invalid row hash")
        if (table_name, row_id) in seen:
            raise CandidateError("cohorts overlap or duplicate a row")
        seen.add((table_name, row_id))
        (original_seen if cohort == "original" else later_seen)[table_name].add(row_id)
        destination = target_rows if cohort == "original" or decision == "delete" else retained_rows
        destination[table_name].append({"id": row_id, "row_sha256": digest})
    if original_seen != tagged:
        raise CandidateError("original cohort is incomplete in the restored backup")
    if later_seen["shifts"] != {later_id}:
        raise CandidateError("later shift is missing from the restored backup")
    if not later_seen["gaming_sessions"]:
        raise CandidateError("later Gaming session is missing from the reviewed cohort")
    for container in (target_rows, retained_rows):
        for entries in container.values():
            entries.sort(key=lambda entry: entry["id"])

    full = {r["table_name"]: {"row_count": r["row_count"], "row_sha256": r["row_sha256"]}
            for r in groups.get("full_digest", [])}
    if set(full) != set(public_tables):
        raise CandidateError("public table digest inventory changed during generation")
    triggers = [
        {key: row[key] for key in (
            "table_name", "trigger_name", "enabled", "definition_sha256", "function_sha256"
        )}
        for row in groups.get("trigger", [])
    ]
    triggers.sort(key=lambda trigger: (trigger["table_name"], trigger["trigger_name"]))

    raw_evidence = groups["evidence"][0]
    counters = raw_evidence.get("invoice_counters")
    if not isinstance(counters, list) or len(counters) != 1:
        raise CandidateError("restored backup must have exactly one invoice counter")
    evidence = {
        "original_five_shift_ids": sorted(tagged["shifts"]),
        "later_shift_id": later_id,
        "paid_total_minor": raw_evidence["paid_total_minor"],
        "retired_invoice_numbers": raw_evidence["retired_invoice_numbers"],
        "google_sheets_event_ids_owner_deletes": raw_evidence["google_sheets_event_ids_owner_deletes"],
        "invoice_counter": counters[0],
    }
    candidate = {
        "schema_version": 1,
        "cleanup_id": CLEANUP_ID,
        "classification_evidence_sha256": "",
        "operator_reviewed_at": "",
        "operator_review_evidence_sha256": "",
        "later_cohort_decision": decision,
        "backup_sha256": backup_sha,
        "tagged_app_source_git_sha": "ad5adfb93c3488f1f931ca27da53824aa57d3dc5",
        "maintenance_source_git_sha": maintenance_sha,
        "maintenance_sql_sha256": sql_sha,
        "expected_schema_revision": "0082",
        "company_id": COMPANY_ID,
        "actor_user_id": ACTOR_USER_ID,
        "terminal_id": TERMINAL_ID,
        "targets": target_rows,
        "retained_rows": retained_rows,
        "replay_fence": raw_evidence["fence"],
        "expected_triggers": triggers,
        "expected_full_table_digests": dict(sorted(full.items())),
        "evidence": evidence,
    }
    return candidate


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, help="disposable local restore database")
    parser.add_argument("--port", type=int, default=5432)
    parser.add_argument("--backup-file", required=True, type=Path)
    parser.add_argument("--later-shift-id", required=True)
    parser.add_argument("--decision", required=True, choices=("delete", "retain"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        if RESTORE_NAME.fullmatch(args.database) is None or not 1 <= args.port <= 65535:
            raise CandidateError("database must be a disposable local code30_combined_restore_* database")
        later_id = canonical_uuid(args.later_shift_id, "later shift ID")
        if not args.backup_file.is_file() or args.backup_file.is_symlink():
            raise CandidateError("backup must be a readable regular file, not a symlink")
        backup_sha = sha256_file(args.backup_file)
        if args.output.exists() or args.output.is_symlink():
            raise CandidateError("candidate output already exists")
        validator = load_validator()
        tagged, tagged_digests = validator._tagged_targets()
        if later_id in tagged["shifts"]:
            raise CandidateError("later shift ID is already in the immutable five")
        tables = psql(args.database, args.port,
            "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;")
        if not tables or len(tables) != len(set(tables)) or any(
            TABLE_NAME.fullmatch(name) is None for name in tables
        ):
            raise CandidateError("public table inventory is invalid")
        output = psql(args.database, args.port,
            snapshot_sql(tagged, later_id, args.decision, tables, validator.TARGET_TABLES))
        if sha256_file(args.backup_file) != backup_sha:
            raise CandidateError("backup changed during candidate generation")
        records = [json.loads(line) for line in output]
        if any(record.get("kind") == "identity" and record.get("database") != args.database
               for record in records):
            raise CandidateError("database identity changed during snapshot")
        revision = subprocess.run(
            ["git", "-C", str(SCRIPT_DIR), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        dirty = subprocess.run(
            ["git", "-C", str(SCRIPT_DIR), "status", "--porcelain", "--untracked-files=normal"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        if dirty or revision == validator.TAGGED_APP_SOURCE_GIT_SHA:
            raise CandidateError("maintenance source must be independently committed and clean")
        candidate = build_candidate(
            records, tagged, tagged_digests, later_id, args.decision,
            backup_sha, revision, sha256_file(REHEARSAL_SQL),
            validator.TARGET_TABLES, tables,
        )
        encoded = (json.dumps(candidate, sort_keys=True, indent=2) + "\n").encode("utf-8")
        if len(encoded) > validator.MAX_MANIFEST_BYTES:
            raise CandidateError("candidate exceeds the approved-manifest size limit")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("xb") as destination:
            destination.write(encoded)
        print(f"UNAPPROVED candidate created: {args.output}")
        print(f"Candidate SHA-256: {hashlib.sha256(encoded).hexdigest()}")
    except (CandidateError, OSError, KeyError, json.JSONDecodeError,
            subprocess.CalledProcessError) as exc:
        print(f"Candidate generation refused: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
