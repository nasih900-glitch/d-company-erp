#!/usr/bin/env python3
"""Verify projected immutable history and exact Build51 guards at migration0091.

This is a history-preservation bridge, never authorization to erase or accept
unknown pending work. Scoped opaque-fleet validation is optional and must be
selected by the installer's independently authorized compatibility path.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
import os
import re
import stat
from datetime import UTC, datetime, timedelta
from pathlib import Path
import sys
import types

EXPECTED_DATABASE_HEAD = "0091"
BUILD50_HELPER = "infra/scripts/verify-build50-post-migration-state.py"
BUILD50_HELPER_SHA256 = (
    "0d393c31824d3595b3f8cec7666e06fb7acc2f2d7fbe412eb1d56ef98d122e81"
)
MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
REMOVED_COLUMNS = [
    "action_code",
    "category",
    "stage",
    "outcome",
    "operation_id",
    "source_reference",
    "actor_kind",
    "target_user_id",
    "business_details",
    "catalogue_version",
]


class PostMigrationError(ValueError):
    pass


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise PostMigrationError("duplicate JSON key")
        result[key] = value
    return result


def _reject_constant(value):
    raise PostMigrationError(f"nonfinite JSON value: {value}")


def _decode(raw: bytes):
    if not raw or len(raw) > MAX_DOCUMENT_BYTES:
        raise PostMigrationError("state document is empty or exceeds its bound")
    try:
        return json.loads(
            raw, object_pairs_hook=_pairs, parse_constant=_reject_constant
        )
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise PostMigrationError("state is not bounded valid JSON") from exc


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _load_verifiers(root: Path):
    path = root / BUILD50_HELPER
    if path.is_symlink() or not path.is_file():
        raise PostMigrationError("immutable Build50 helper missing or linked")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != BUILD50_HELPER_SHA256:
        raise PostMigrationError("immutable Build50 helper changed")
    module = types.ModuleType("immutable_build50_history_bridge")
    module.__file__ = str(path)
    exec(compile(raw, str(path), "exec"), module.__dict__)
    build49, historical, cleanup = module._load_verifiers(root)
    # Isolated imported module: preserve every historical digest/field check.
    # The actual document must already be0091; never rewrite its revision.
    historical.PRIOR_DATABASE_HEAD = EXPECTED_DATABASE_HEAD
    return build49, historical, cleanup


def verify_envelope(document):
    if not isinstance(document, dict) or set(document) != {
        "schema_version",
        "bridge",
        "database_revision",
        "historical_state",
        "audit_projection",
        "guard_inventory",
    }:
        raise PostMigrationError("unexpected Build51 history envelope shape")
    if (
        type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or document["bridge"] != "build51-history-v1"
        or document["database_revision"] != EXPECTED_DATABASE_HEAD
    ):
        raise PostMigrationError("state is not the exact0091 Build51 bridge")
    projection = {
        "version": 1,
        "removed_columns": REMOVED_COLUMNS,
        "retained_baseline_semantic_count": 0,
        "pre_receipt_semantic_count": 0,
        "cleanup_receipt_semantic_count": 0,
    }
    if _canonical(document["audit_projection"]) != _canonical(projection):
        raise PostMigrationError(
            "historical semantic evidence is non-null or projection changed"
        )
    if _canonical(document["guard_inventory"]) != _canonical(EXPECTED_GUARD_INVENTORY):
        raise PostMigrationError(
            "Build51 guard identity, binding, attributes or definition changed"
        )
    history = document["historical_state"]
    if (
        not isinstance(history, dict)
        or history.get("database_revision") != EXPECTED_DATABASE_HEAD
    ):
        raise PostMigrationError("historical document is not exact0091")
    return history


def verify_raw_state(root: Path, historical_raw: bytes, fleet_raw: bytes | None = None):
    history = verify_envelope(_decode(historical_raw))
    build49, historical, cleanup = _load_verifiers(root)
    fleet = None
    if fleet_raw is None:
        proof = {
            "historical_cleanup_receipt_id": cleanup.verify_state(history),
            "historical_proof_scope": "exact_retained_legacy_installation_only",
        }
    else:
        proof = historical._verify_scoped_history(history, cleanup)
    if fleet_raw is not None:
        fleet_document = historical._decode_json(
            fleet_raw, cleanup, label="fleet state"
        )
        fleet = build49._classify_opaque_fleet(
            historical._verify_fleet(fleet_document, cleanup)
        )
    return {
        "schema_version": 1,
        "status": "build51_projected_history_and_guards_verified",
        "database_revision": EXPECTED_DATABASE_HEAD,
        "historical_cleanup_proof": proof,
        "fleet": fleet,
        "raw_historical_state_sha256": hashlib.sha256(historical_raw).hexdigest(),
        "raw_fleet_state_sha256": hashlib.sha256(fleet_raw).hexdigest()
        if fleet_raw is not None
        else None,
        "queue_drained": False,
        "tablet_queue_contents_verified": False,
        "telemetry_rewritten": False,
        "installation_retired": False,
    }


def verify_pre_migration_0089(root: Path, raw: bytes):
    document = _decode(raw)
    if not isinstance(document, dict) or document.get("database_revision") != "0089":
        raise PostMigrationError("pre-migration history must be exact0089")
    _, _, cleanup = _load_verifiers(root)
    return {
        "schema_version": 1,
        "status": "immutable_0089_history_verified",
        "historical_cleanup_receipt_id": cleanup.verify_state(document),
        "queue_drained": False,
        "tablet_queue_contents_verified": False,
    }


def _prefix(value):
    if (
        not isinstance(value, dict)
        or set(value) != {"cutoff", "row_count", "row_sha256"}
        or type(value["cutoff"]) is not int
        or value["cutoff"] < 0
        or type(value["row_count"]) is not int
        or not 0 <= value["row_count"] <= value["cutoff"]
        or not isinstance(value["row_sha256"], str)
        or not re.fullmatch(r"[a-f0-9]{64}", value["row_sha256"])
    ):
        raise PostMigrationError("fresh history prefix is malformed")


def _finance(value):
    keys = {
        "payment_correction_count",
        "payment_correction_sha256",
        "daily_reconciliation_count",
        "daily_reconciliation_sha256",
    }
    if not isinstance(value, dict) or set(value) != keys:
        raise PostMigrationError("fresh finance history shape changed")
    for key, item in value.items():
        if key.endswith("_count"):
            if type(item) is not int or item < 0:
                raise PostMigrationError("fresh finance count is invalid")
        elif not isinstance(item, str) or not re.fullmatch(r"[a-f0-9]{64}", item):
            raise PostMigrationError("fresh finance fingerprint is invalid")


def _database_identity(value):
    if (
        not isinstance(value, dict)
        or set(value) != {"system_identifier", "database_oid", "database_name"}
        or not isinstance(value["system_identifier"], str)
        or not re.fullmatch(r"[1-9][0-9]{0,19}", value["system_identifier"])
        or not isinstance(value["database_oid"], str)
        or not re.fullmatch(r"[1-9][0-9]{0,9}", value["database_oid"])
        or not isinstance(value["database_name"], str)
        or not re.fullmatch(r"[A-Za-z0-9_]{1,63}", value["database_name"])
    ):
        raise PostMigrationError("fresh lineage database identity is invalid")


def verify_current_state(document):
    keys = {
        "schema_version",
        "bridge",
        "database_revision",
        "database_identity",
        "origin_prefix",
        "preserved_prefix",
        "finance_history",
        "business_history_row_count",
        "audit_max_id",
        "pending_outbox_count",
        "nonzero_installation_count",
        "guard_inventory",
    }
    if not isinstance(document, dict) or set(document) != keys:
        raise PostMigrationError("fresh history evidence shape changed")
    if (
        type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or document["bridge"] != "build51-fresh-history-v1"
        or document["database_revision"] != "0091"
    ):
        raise PostMigrationError("fresh history is not exact0091")
    _database_identity(document["database_identity"])
    _prefix(document["origin_prefix"])
    _prefix(document["preserved_prefix"])
    _finance(document["finance_history"])
    for key in (
        "business_history_row_count",
        "audit_max_id",
        "pending_outbox_count",
        "nonzero_installation_count",
    ):
        if type(document[key]) is not int or document[key] < 0:
            raise PostMigrationError("fresh history count is invalid")
    if (
        max(document["origin_prefix"]["cutoff"], document["preserved_prefix"]["cutoff"])
        > document["audit_max_id"]
    ):
        raise PostMigrationError("fresh prefix exceeds the actual Audit boundary")
    if _canonical(document["guard_inventory"]) != _canonical(EXPECTED_GUARD_INVENTORY):
        raise PostMigrationError("fresh history guard identity changed")
    return document


def verify_fresh_state(document):
    state = verify_current_state(document)
    if state["pending_outbox_count"] or state["nonzero_installation_count"]:
        raise PostMigrationError("fresh lineage cannot accept unknown pending work")
    return state


def verify_current_preservation(document, baseline=None):
    state = verify_current_state(document)
    if baseline is None:
        if (
            state["origin_prefix"] != state["preserved_prefix"]
            or state["preserved_prefix"]["cutoff"] != state["audit_max_id"]
        ):
            raise PostMigrationError(
                "current baseline must capture the complete Audit prefix"
            )
    else:
        prior = verify_current_preservation(baseline)
        if not _same_database(state["database_identity"], prior["database_identity"]):
            raise PostMigrationError("current database identity changed")
        for key in (
            "origin_prefix",
            "preserved_prefix",
            "finance_history",
            "business_history_row_count",
            "pending_outbox_count",
            "nonzero_installation_count",
        ):
            if state[key] != prior[key]:
                raise PostMigrationError(
                    "current history changed across migration or restore: " + key
                )
    return state


def verify_origin(origin, project):
    keys = {
        "schema_version",
        "kind",
        "admission",
        "project",
        "created_at",
        "source_git_sha",
        "source_archive_sha256",
        "database_identity",
        "origin_prefix",
        "initial_finance_history",
    }
    if (
        not isinstance(origin, dict)
        or set(origin) != keys
        or type(origin["schema_version"]) is not int
        or origin["schema_version"] != 1
        or origin["kind"] != "build51-fresh-origin-v1"
        or origin["admission"] != "no_prior_deployment_evidence"
        or origin["project"] != project
        or not re.fullmatch(r"[a-z0-9][a-z0-9_.-]{0,99}", project)
    ):
        raise PostMigrationError(
            "fresh origin identity/admission changed; recover its protected original receipt"
        )
    for key, length in (("source_git_sha", 40), ("source_archive_sha256", 64)):
        if not isinstance(origin[key], str) or not re.fullmatch(
            r"[a-f0-9]{" + str(length) + r"}", origin[key]
        ):
            raise PostMigrationError("fresh origin source binding is invalid")
    try:
        created = datetime.fromisoformat(origin["created_at"])
        if created.utcoffset() != timedelta(0):
            raise ValueError("UTC required")
    except (ValueError, TypeError):
        raise PostMigrationError("fresh origin creation time is invalid") from None
    _database_identity(origin["database_identity"])
    _prefix(origin["origin_prefix"])
    if origin["origin_prefix"]["row_count"] < 1:
        raise PostMigrationError("fresh origin has no immutable birth Audit anchor")
    _finance(origin["initial_finance_history"])
    if any(
        value
        for key, value in origin["initial_finance_history"].items()
        if key.endswith("_count")
    ):
        raise PostMigrationError(
            "fresh origin contains pre-existing correction receipts"
        )
    empty = hashlib.sha256(b"").hexdigest()
    if any(
        value != empty
        for key, value in origin["initial_finance_history"].items()
        if key.endswith("_sha256")
    ):
        raise PostMigrationError("fresh origin finance fingerprints are not empty")
    return origin


def make_origin(
    document,
    *,
    project,
    source_git_sha,
    source_archive_sha256,
    admission_confirmed=False,
):
    state = verify_fresh_state(document)
    if (
        admission_confirmed is not True
        or state["business_history_row_count"] != 0
        or state["origin_prefix"] != state["preserved_prefix"]
        or state["origin_prefix"]["cutoff"] != state["audit_max_id"]
        or state["origin_prefix"]["row_count"] < 1
    ):
        raise PostMigrationError(
            "fresh origin requires proven no-prior admission and an unused complete bootstrap"
        )
    origin = {
        "schema_version": 1,
        "kind": "build51-fresh-origin-v1",
        "admission": "no_prior_deployment_evidence",
        "project": project,
        "created_at": datetime.now(UTC).isoformat(),
        "source_git_sha": source_git_sha,
        "source_archive_sha256": source_archive_sha256,
        "database_identity": state["database_identity"],
        "origin_prefix": state["origin_prefix"],
        "initial_finance_history": state["finance_history"],
    }
    return verify_origin(deepcopy(origin), project)


def _same_database(left, right):
    # OID is recorded evidence, not durable identity: guarded logical restores
    # recreate it. Cluster/name plus the immutable, nonempty birth Audit prefix
    # identify this lineage; full pre/post history separately proves preservation.
    return all(
        left[key] == right[key] for key in ("system_identifier", "database_name")
    )


def verify_fresh_preservation(document, origin, *, project, baseline=None):
    state = verify_fresh_state(document)
    verify_origin(origin, project)
    if (
        not _same_database(state["database_identity"], origin["database_identity"])
        or state["origin_prefix"] != origin["origin_prefix"]
    ):
        raise PostMigrationError(
            "fresh origin database or original Audit prefix changed; restore the protected matching lineage"
        )
    if (
        baseline is None
        and state["preserved_prefix"]["cutoff"] != state["audit_max_id"]
    ):
        raise PostMigrationError(
            "quiesced baseline must capture the complete current Audit prefix"
        )
    if baseline is not None:
        prior = verify_fresh_preservation(baseline, origin, project=project)
        if not _same_database(state["database_identity"], prior["database_identity"]):
            raise PostMigrationError("quiesced fresh database identity changed")
        for key in (
            "preserved_prefix",
            "finance_history",
            "business_history_row_count",
        ):
            if state[key] != prior[key]:
                raise PostMigrationError(
                    "quiesced fresh history changed across migration: " + key
                )
    return state


def _origin_parent(path, *, expected_uid=0, expected_gid=0):
    path = Path(path)
    if (
        not path.is_absolute()
        or path.name in {"", ".", ".."}
        or str(path) != str(path.resolve(strict=False))
    ):
        raise PostMigrationError("fresh origin path is not canonical")
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    info = os.fstat(descriptor)
    if (
        not stat.S_ISDIR(info.st_mode)
        or info.st_uid != expected_uid
        or info.st_gid != expected_gid
        or stat.S_IMODE(info.st_mode) != 0o700
    ):
        os.close(descriptor)
        raise PostMigrationError("fresh origin parent must be root-private0700")
    return descriptor


def read_origin(path, *, expected_uid=0, expected_gid=0):
    parent = _origin_parent(path, expected_uid=expected_uid, expected_gid=expected_gid)
    try:
        descriptor = os.open(
            Path(path).name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent
        )
        try:
            info = os.fstat(descriptor)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_uid != expected_uid
                or info.st_gid != expected_gid
                or info.st_nlink != 1
                or stat.S_IMODE(info.st_mode) != 0o600
                or info.st_size > MAX_DOCUMENT_BYTES
            ):
                raise PostMigrationError(
                    "fresh origin file has unsafe ownership, mode, links or size"
                )
            chunks = []
            remaining = MAX_DOCUMENT_BYTES + 1
            while remaining:
                part = os.read(descriptor, min(remaining, 65536))
                if not part:
                    break
                chunks.append(part)
                remaining -= len(part)
            return _decode(b"".join(chunks))
        finally:
            os.close(descriptor)
    finally:
        os.close(parent)


def write_origin(path, origin, *, expected_uid=0, expected_gid=0):
    parent = _origin_parent(path, expected_uid=expected_uid, expected_gid=expected_gid)
    try:
        descriptor = os.open(
            Path(path).name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
            0o600,
            dir_fd=parent,
        )
        try:
            raw = (_canonical(origin) + "\n").encode()
            offset = 0
            while offset < len(raw):
                written = os.write(descriptor, raw[offset:])
                if written < 1:
                    raise OSError("fresh origin write made no progress")
                offset += written
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.fsync(parent)
    finally:
        os.close(parent)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-historical-state", type=Path)
    parser.add_argument("--raw-fleet-state", type=Path)
    parser.add_argument("--current-state", action="store_true")
    parser.add_argument("--baseline-current-state", type=Path)
    parser.add_argument("--pre-migration-0089", action="store_true")
    parser.add_argument("--fresh-origin", type=Path)
    parser.add_argument("--create-fresh-origin", action="store_true")
    parser.add_argument("--fresh-admission-confirmed", action="store_true")
    parser.add_argument("--baseline-fresh-state", type=Path)
    parser.add_argument("--show-origin-cutoff", action="store_true")
    parser.add_argument("--show-preserve-cutoff", action="store_true")
    parser.add_argument("--project")
    parser.add_argument("--source-git-sha")
    parser.add_argument("--source-archive-sha256")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        build49, _, _ = _load_verifiers(root)
        if (
            args.create_fresh_origin
            or args.show_origin_cutoff
            or args.baseline_fresh_state is not None
        ) and args.fresh_origin is None:
            raise PostMigrationError("fresh origin is required for lineage options")
        if args.show_preserve_cutoff and not (
            args.current_state or args.fresh_origin is not None
        ):
            raise PostMigrationError(
                "preservation cutoff requires current state or fresh origin mode"
            )
        if args.fresh_admission_confirmed and not args.create_fresh_origin:
            raise PostMigrationError(
                "fresh admission is valid only during exclusive origin creation"
            )
        if (
            args.source_git_sha is not None or args.source_archive_sha256 is not None
        ) and not args.create_fresh_origin:
            raise PostMigrationError(
                "origin source identity is valid only during creation"
            )
        if args.show_origin_cutoff and (
            args.raw_historical_state is not None
            or args.raw_fleet_state is not None
            or args.pre_migration_0089
            or args.create_fresh_origin
            or args.baseline_fresh_state is not None
            or args.show_preserve_cutoff
        ):
            raise PostMigrationError(
                "origin cutoff read cannot select another verification mode"
            )
        if args.create_fresh_origin and (
            args.baseline_fresh_state is not None or args.show_preserve_cutoff
        ):
            raise PostMigrationError(
                "origin creation cannot select baseline recovery or cutoff modes"
            )
        if args.project is not None and args.fresh_origin is None:
            raise PostMigrationError("project binding requires a fresh origin")
        if args.current_state and (
            args.fresh_origin is not None
            or args.pre_migration_0089
            or args.raw_fleet_state is not None
            or args.create_fresh_origin
            or args.show_origin_cutoff
        ):
            raise PostMigrationError(
                "current preservation is not fresh admission or pending compatibility"
            )
        if args.baseline_current_state is not None and not args.current_state:
            raise PostMigrationError(
                "current baseline requires current preservation mode"
            )
        origin = None
        if args.fresh_origin is not None and not args.create_fresh_origin:
            origin = verify_origin(read_origin(args.fresh_origin), args.project)
        if args.show_origin_cutoff:
            if origin is None:
                raise PostMigrationError("origin receipt is required")
            print(origin["origin_prefix"]["cutoff"])
            return 0
        if args.raw_historical_state is None:
            raise PostMigrationError("raw state is required")
        build49._private_file(args.raw_historical_state)
        raw = args.raw_historical_state.read_bytes()
        if args.current_state:
            baseline = None
            if args.baseline_current_state is not None:
                build49._private_file(args.baseline_current_state)
                baseline = _decode(args.baseline_current_state.read_bytes())
            checked = verify_current_preservation(_decode(raw), baseline)
            if args.show_preserve_cutoff:
                print(checked["preserved_prefix"]["cutoff"])
                return 0
            report = {
                "schema_version": 1,
                "status": "current_history_and_guards_preserved",
                "database_revision": "0091",
                "baseline_compared": baseline is not None,
                "pending_compatibility_granted": False,
                "queue_drained": False,
            }
        elif args.fresh_origin is not None:
            if args.pre_migration_0089 or args.raw_fleet_state is not None:
                raise PostMigrationError(
                    "fresh lineage cannot select a historical or fleet exception"
                )
            state = _decode(raw)
            if args.create_fresh_origin:
                origin = make_origin(
                    state,
                    project=args.project,
                    source_git_sha=args.source_git_sha,
                    source_archive_sha256=args.source_archive_sha256,
                    admission_confirmed=args.fresh_admission_confirmed,
                )
                write_origin(args.fresh_origin, origin)
                report = {
                    "schema_version": 1,
                    "status": "fresh_origin_created",
                    "queue_drained": False,
                }
            else:
                baseline = None
                if args.baseline_fresh_state is not None:
                    build49._private_file(args.baseline_fresh_state)
                    baseline = _decode(args.baseline_fresh_state.read_bytes())
                checked = verify_fresh_preservation(
                    state, origin, project=args.project, baseline=baseline
                )
                if args.show_preserve_cutoff:
                    print(checked["preserved_prefix"]["cutoff"])
                    return 0
                report = {
                    "schema_version": 1,
                    "status": "fresh_history_and_guards_verified",
                    "database_revision": "0091",
                    "baseline_compared": baseline is not None,
                    "queue_drained": False,
                    "tablet_queue_contents_verified": False,
                }
        elif args.pre_migration_0089:
            if args.raw_fleet_state is not None:
                raise PostMigrationError(
                    "pre-migration bridge cannot grant fleet compatibility"
                )
            report = verify_pre_migration_0089(root, raw)
        else:
            if args.raw_fleet_state is not None:
                build49._private_file(args.raw_fleet_state)
            report = verify_raw_state(
                root,
                raw,
                args.raw_fleet_state.read_bytes()
                if args.raw_fleet_state is not None
                else None,
            )
    except (OSError, ValueError, TypeError) as exc:
        print(f"build51 post-migration verification refused: {exc}", file=sys.stderr)
        if args.fresh_origin is not None:
            print(
                "Recover the exact root-private origin from its checksummed rollback bundle; do not regenerate a birth receipt on a used database.",
                file=sys.stderr,
            )
        return 1
    sys.stdout.write(_canonical(report) + "\n")
    return 0


# Canonical definitions derived ONLY from reviewed0090+0091 on disposable
# PostgreSQL16.15, roleerp, search_path=pg_catalog,public. Never learn production
# values here. Attributes and bindings are independent of definition hashes.
EXPECTED_GUARD_INVENTORY = json.loads(r"""
{
  "functions": [
    {
      "config": null,
      "definition_sha256": "097018ac894805073315196998dfeed0d61f4a9c514e80ef827b1f95cc391c81",
      "identity_arguments": "j jsonb",
      "language": "plpgsql",
      "leakproof": false,
      "name": "dcompany_collection_company",
      "owner": "erp",
      "parallel": "u",
      "result": "uuid",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "s"
    },
    {
      "config": [
        "TimeZone=UTC"
      ],
      "definition_sha256": "7dbab7f4cc51f1359756936582c0a355414e0adfc0a8d8fa79e38c94d2a72b9c",
      "identity_arguments": "p_company uuid, p_branch uuid, p_day date, p_zone text",
      "language": "sql",
      "leakproof": false,
      "name": "dcompany_collection_day_snapshot",
      "owner": "erp",
      "parallel": "u",
      "result": "jsonb",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "s"
    },
    {
      "config": null,
      "definition_sha256": "5ae29080760c48773ff69691cb660b2980bc3e8b341457ab8c13ff3767bd862b",
      "identity_arguments": "",
      "language": "plpgsql",
      "leakproof": false,
      "name": "dcompany_collection_fence",
      "owner": "erp",
      "parallel": "u",
      "result": "trigger",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": [
        "TimeZone=UTC"
      ],
      "definition_sha256": "53d8e0da8e84a02ce899de5cc687993864831e8fa6e52e0c675b1b94afe065e1",
      "identity_arguments": "p_company uuid, p_branch uuid, p_day date, p_zone text",
      "language": "sql",
      "leakproof": false,
      "name": "dcompany_collection_fingerprint",
      "owner": "erp",
      "parallel": "u",
      "result": "text",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": null,
      "definition_sha256": "6d88431047c8fefaf7d20f50c7b13637e7eb745ddd7cf4a016df4570621c62b3",
      "identity_arguments": "p_company uuid",
      "language": "plpgsql",
      "leakproof": false,
      "name": "dcompany_collection_lock",
      "owner": "erp",
      "parallel": "u",
      "result": "bigint",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": null,
      "definition_sha256": "e77ffdb5cce43b3909e418d01bf2f14f6f79730bbb8760137c359279adc9e65e",
      "identity_arguments": "p_company uuid",
      "language": "plpgsql",
      "leakproof": false,
      "name": "dcompany_collection_touch",
      "owner": "erp",
      "parallel": "u",
      "result": "void",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": null,
      "definition_sha256": "4935e7d77b2b8534c974cd761fe77fd89c2ac3074ff6ffcdf286723230233d43",
      "identity_arguments": "",
      "language": "plpgsql",
      "leakproof": false,
      "name": "dcompany_tender_config_fence",
      "owner": "erp",
      "parallel": "u",
      "result": "trigger",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": null,
      "definition_sha256": "872056572232052bf4b682d9f67874944e9dec1b8dff2e48c439827dbb0d492b",
      "identity_arguments": "p_company uuid, p_branch uuid, p_day date, p_zone text",
      "language": "sql",
      "leakproof": false,
      "name": "dcompany_tender_totals",
      "owner": "erp",
      "parallel": "u",
      "result": "TABLE(cash_minor bigint, upi_minor bigint)",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": null,
      "definition_sha256": "ec5ea739e5d223135791f72bb352dca2941893e62f34a7de71ad316b06a44130",
      "identity_arguments": "",
      "language": "plpgsql",
      "leakproof": false,
      "name": "dcompany_validate_effective_refund_rail",
      "owner": "erp",
      "parallel": "u",
      "result": "trigger",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": null,
      "definition_sha256": "cfd4d1e8e0e04d70f922fa7c60703d4a1484200e83d7228a4ccf3e1985210ccb",
      "identity_arguments": "",
      "language": "plpgsql",
      "leakproof": false,
      "name": "dcompany_validate_tender_receipt",
      "owner": "erp",
      "parallel": "u",
      "result": "trigger",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    },
    {
      "config": null,
      "definition_sha256": "14689462e287fe2a94e5da8d543273840ecbb6c60727e37968bc7ae711232e94",
      "identity_arguments": "",
      "language": "plpgsql",
      "leakproof": false,
      "name": "guard_audit_log_append_only",
      "owner": "erp",
      "parallel": "u",
      "result": "trigger",
      "schema": "public",
      "security_definer": false,
      "strict": false,
      "volatility": "v"
    }
  ],
  "triggers": [
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "ac90cbdb07f0cf4112c5951863d3bf6cd31672958e0e052bf9ef07443b621d8c",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "guard_audit_log_append_only",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "audit_log_append_only",
      "schema": "public",
      "table": "audit_log",
      "type": 27,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "cb6f0ba4423fe436485c1f84de1a3c084ef71435ea220f0b847bf2c6ff284b49",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "guard_audit_log_append_only",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "audit_log_no_truncate",
      "schema": "public",
      "table": "audit_log",
      "type": 34,
      "when": null
    },
    {
      "args": "",
      "columns": [
        "company_id",
        "deleted_at",
        "timezone"
      ],
      "deferrable": false,
      "definition_sha256": "76119363b8fd390d93735b918297b3b04230ba52fb501d416dd4e4fe38067114",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_tender_config_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_tender_branch_configuration",
      "schema": "public",
      "table": "branches",
      "type": 19,
      "when": null
    },
    {
      "args": "",
      "columns": [
        "currency",
        "currency_minor_units",
        "deleted_at",
        "google_sheets_configuration_id",
        "google_sheets_export_mode",
        "google_sheets_summary_branch_id",
        "google_sheets_summary_start_date",
        "google_sheets_summary_timezone",
        "timezone"
      ],
      "deferrable": false,
      "definition_sha256": "b342b7efe2ab5cdf66440cb0df2820c132a1b441fe9f7fe9bf9207fcc2a9fd44",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_tender_config_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_tender_company_configuration",
      "schema": "public",
      "table": "companies",
      "type": 19,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "7fd237eb5d281e7dc697313a65e5404e67954572dcdaa633b84995d5065294d1",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "guard_audit_log_append_only",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "immutable_tender_receipt",
      "schema": "public",
      "table": "daily_tender_reconciliations",
      "type": 27,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "c3b4b323d9873e9dadfdbcb0816b0d5040a8f5ace2187961f0cd2bb99217a2e8",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "guard_audit_log_append_only",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "immutable_tender_truncate",
      "schema": "public",
      "table": "daily_tender_reconciliations",
      "type": 34,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "ea6c9ef244d0ef2a21fe8b9fc5a77a7a2f59ab99768f160d1b7c8cd7a8f58e7c",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_validate_tender_receipt",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "validate_tender_receipt",
      "schema": "public",
      "table": "daily_tender_reconciliations",
      "type": 7,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "a2cb0a202a4d9bfc42e66302827d3e4a577397bfdf49db4de1416d367fa89af6",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "daily_tender_reconciliations",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "4c3ec24e25c00cf1fc994ab32fbc05e375902d061e107a3a22c757394dbaaa7a",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "finance_source_corrections",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "8db213a547cd37dda9f3ed7aebb4df95cdf6534472ce535ed7e2a83085c5d7bd",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "manual_collections",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "6e7b8edc32c81765e09d015e4d1660bd1a29a62d7a53b21eabf608119b336a1f",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_payments",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "421f531fe0b4b4653145078a881dfb73c4a8f1cb6fbd0dd08719b0a49e5d71e0",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refund_attempt_recoveries",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "65b078773718f49eda04dee9f05bfd8eea6e93e08ab077cf40daff80127fa11c",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refund_attempt_resolutions",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "f263870141fc4731d0f3d46607aaca7b3cf0ac95e317e0c8279f21a8dfee1e17",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refund_cash_handoffs",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "3237a6cbf9d71a936c071e92d2f114364beeea1194a2899a8b573888e479ffda",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refund_completions",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "aa06cd0b36715dae5115a6fa375c42aae2613ae025c7316173b35272c44585af",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refund_provider_actions",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "85265f7b97abd3bdbcbc58ebf4cd2c14f8a3e38562db84d505527d9b4f53af22",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refund_resolutions",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "9bc95f03ac60a2032d247182c199dfb0651048b628489e3d5a2b18b33a771245",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refund_settlements",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "0a6a6c61ad507326cc569283e99a5091adc46d3a01fb128eb88608d6c51cd8f8",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "membership_refunds",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "c1304f6b221b7fe79eb5bb89ef7628a26cd0d7a392c24f3a1ce2ced0e70b631c",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "orders",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "1740f618b06923e9b752e467d38755a624a074ca4c85a67f43c2f8d5b8a81a73",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "guard_audit_log_append_only",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "immutable_tender_receipt",
      "schema": "public",
      "table": "payment_tender_corrections",
      "type": 27,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "4572e8c265bc0304927f105ca18e9367c4133a77780c90d69d1214ddaa31ee34",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "guard_audit_log_append_only",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "immutable_tender_truncate",
      "schema": "public",
      "table": "payment_tender_corrections",
      "type": 34,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "4b6604007c8a9b6117a5b532d0c7e7b24833154c20da868407ba09c2f306c929",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_validate_tender_receipt",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "validate_tender_receipt",
      "schema": "public",
      "table": "payment_tender_corrections",
      "type": 7,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "a2212a86653e23e2ceb41e426f5d5816022455fc973ffb4a52478a240b7c2e2f",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "payment_tender_corrections",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "978fc67d4a4d3c18c88c3aae0e210105462c67d7cdf9c256f5dd655c77655b89",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "payments",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "a19eee5a94db0a3553491d064d2fe2168d4974866fd79c479f657bf9f267dd4a",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "pos_refund_cash_handoff_completions",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "299141478891c990aef772c5bf974f86f679e1e740e2280b881aebb281fedb64",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "pos_refund_cash_handoffs",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "f61bfb625ae10ca3cb4c808191c13f208caede4e4b6ef655c38b16f05efc8d2c",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "pos_refund_provider_payout_starts",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "d3a090c37aa1603eb3503c7549c07e2da85478d0e49fcdf4773aa75142f7431e",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "pos_refund_provider_settlements",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "1fffba592a5fae7e2a47b440b5d1ee353d4e3a4c507e9468a1b8c468c0d989d8",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "pos_refund_requests",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "1b0e944ea910cdd5fa2259687879365d5bccb6e8fa11c1ec24de304c6adc2625",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_validate_effective_refund_rail",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_effective_refund_rail",
      "schema": "public",
      "table": "pos_refund_requests",
      "type": 7,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "b9167bd9e188ff78da0b497356d4f3238bd9b1b0b88b4ed2d524f846ae7b1c68",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "pos_refund_withdrawals",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "c56b3248b2d80ef07f8ab0adb7797e05447078cc9592bc5dc9285dd1a9b36751",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "refunds",
      "type": 29,
      "when": null
    },
    {
      "args": "",
      "columns": [],
      "deferrable": false,
      "definition_sha256": "1e0aac58c09784a8bba630e96ca70bad0a8ce70b31b74f5970bea257fccde7da",
      "enabled": "O",
      "function_identity_arguments": "",
      "function_name": "dcompany_collection_fence",
      "function_schema": "public",
      "initially_deferred": false,
      "internal": false,
      "name": "zz_collection_fence",
      "schema": "public",
      "table": "shifts",
      "type": 29,
      "when": null
    }
  ]
}
""")


if __name__ == "__main__":
    raise SystemExit(main())
