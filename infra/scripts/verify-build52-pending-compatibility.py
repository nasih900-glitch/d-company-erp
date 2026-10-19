#!/usr/bin/env python3
"""Verify the exact deployed Build50/0089 to Build52/0091 transition.

The two reported tablet counters are opaque retained work. This verifier does
not inspect, drain, rewrite, retire, or authorize an Android offer. Candidate
revision/archive pins intentionally stay fail-closed until the independent
release-freeze phase records the final successor commit.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import sys
import types


class CompatibilityError(ValueError):
    pass


PRIOR_REVISION = "29e32b440ab8dc162b6c8f5d40f8283fad53ce04"
PRIOR_TREE = "296f9745c6c1060d0d3ecc7b334f811dffad1964"
SAME_TREE_TAG_REVISION = "3093f62971d16e5d80408a436959e7cc5ccbf318"
PRIOR_VERSION = "3.1.42"
PRIOR_DATABASE_HEAD = "0089"
CANDIDATE_VERSION = "3.1.44"
CANDIDATE_DATABASE_HEAD = "0091"
PRIOR_SOURCE_ARCHIVE_SHA256 = (
    "390374a01c358d5e320f0523f4ba93b3dffb4b334f73ee32d7ad225b262b990e"
)
PRIOR_BACKEND_MANIFEST_SHA256 = (
    "2cacfea604ad4d22c68328c316e5752675bb7aba5c3e628785d0d173ed427743"
)
PRIOR_BACKEND_IMAGE_ID = (
    "sha256:16c4c6f6db02aab2fcc445a492b324fa6e5d9ea0d7236881c940e94f7ac1a531"
)

# Root's separate freeze phase pins this helper and the final whole-source
# commit. The helper itself cannot contain the hash of a commit/archive that
# contains the helper. Until that independent review flips this gate, normal
# CLI admission remains impossible.
CANDIDATE_RELEASE_FROZEN = True
CANDIDATE_BACKEND_MANIFEST_SHA256: str | None = (
    "167a4eef45320eb9d7c4c82f515353039d8860a5fbf4a4a115e0809ba03778a3"
)

EXPECTED_INSTALLATION_ROWS = 13
EXPECTED_PENDING = 2
EXPECTED_NONZERO = 2
EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = (
    "ea5bfed358dc2a4a16a3876bb8a45daa75c40833498680e62643b44c90114664"
)
EXPECTED_NONZERO_FLEET_SHA256 = (
    "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"
)
EXPECTED_RAW_FLEET_SHA256 = (
    "ef362605c5b77bd695674415aebbdc1b7d13abddba238ac17410f28aeab7b9d2"
)

BUILD50_PENDING_HELPER = "infra/scripts/verify-build50-pending-compatibility.py"
BUILD50_PENDING_HELPER_SHA256 = (
    "af7ad6b43fe579a497b68ea09443bb8f38513d1bd014819b97f452f38ab1973b"
)
BUILD50_POST_HELPER = "infra/scripts/verify-build50-post-migration-state.py"
BUILD50_POST_HELPER_SHA256 = (
    "0d393c31824d3595b3f8cec7666e06fb7acc2f2d7fbe412eb1d56ef98d122e81"
)
BUILD51_POST_HELPER = "infra/scripts/verify-build51-post-migration-state.py"
BUILD51_POST_HELPER_SHA256 = (
    "de464d28e260233e7f8f4869363538635dac846ac57b7e96d1c1d2de16a88696"
)
PROTECTED_SCOPES = (
    "backend",
    "android-native/app/src/main",
    "android-native/app/schemas",
    "android-native/app/build.gradle.kts",
    "android-native/app/gradle.lockfile",
    "android-native/build.gradle.kts",
    "android-native/settings.gradle.kts",
    "android-native/gradle.properties",
    "android-native/gradle/verification-metadata.xml",
    "infra/docker",
    "infra/caddy",
    "infra/nginx",
    ".dockerignore",
    ".env.production.example",
    "docker-compose.prod.yml",
    "infra/scripts/install-on-vm.sh",
    "infra/scripts/prepare-production-env.sh",
    "infra/scripts/generate-secrets.sh",
    "infra/scripts/validate-production-env.sh",
    "infra/scripts/verify-code30-2-post-cleanup-state.py",
    "infra/scripts/verify-code30-2-post-cleanup-state.sql",
    "infra/scripts/verify-build45-pending-state.sql",
    "infra/scripts/verify-build46-pending-compatibility.py",
    "infra/scripts/verify-build47-pending-compatibility.py",
    "infra/scripts/verify-build48-pending-compatibility.py",
    "infra/scripts/verify-build49-pending-compatibility.py",
    "infra/scripts/verify-build50-pending-compatibility.py",
    "infra/scripts/verify-build50-post-migration-state.py",
    "infra/scripts/verify-build51-post-migration-state.py",
    "infra/scripts/verify-build51-post-migration-state.sql",
    "infra/scripts/verify-build52-transition-state.sql",
    "ops/runtime_release_parity.py",
    "ops/verify_web_public_assets.py",
    "infra/scripts/verify-production-runtime-images.sh",
    "tests/test_caddy_dependency_security.py",
    "frontend/src",
    "frontend/public",
    "frontend/scripts",
    "frontend/.env.example",
    "frontend/package.json",
    "frontend/package-lock.json",
    "frontend/vite.config.ts",
)
PRIOR_PROTECTED_SOURCE_SHA256 = (
    "f8a2ba6addc34cd8ec636281a583a8a15ceb549c31c1f9034f593c70563d182a"
)
CANDIDATE_PROTECTED_SOURCE_SHA256 = (
    "10ada65436132fe1b4ef0927d418abc2880fb163aafc80334e6984a004834481"
)
PRIOR_PROTECTED_PATH_COUNT = 1217
CANDIDATE_PROTECTED_PATH_COUNT = 1285
REVIEWED_CHANGED_PATH_COUNT = 182
MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
REQUIRED_PRESERVED_TABLES = {
    "audit_log",
    "client_installations",
    "orders",
    "payments",
    "shifts",
}
FINANCE_TABLES = {
    "payment_tender_corrections",
    "daily_tender_reconciliations",
}
AUDIT_SEMANTIC_COLUMNS = [
    {"column_name": "action_code", "data_type": "character varying(24)", "not_null": False, "default": None},
    {"column_name": "category", "data_type": "character varying(32)", "not_null": False, "default": None},
    {"column_name": "stage", "data_type": "character varying(32)", "not_null": False, "default": None},
    {"column_name": "outcome", "data_type": "character varying(32)", "not_null": False, "default": None},
    {"column_name": "operation_id", "data_type": "character varying(160)", "not_null": False, "default": None},
    {"column_name": "source_reference", "data_type": "character varying(120)", "not_null": False, "default": None},
    {"column_name": "actor_kind", "data_type": "character varying(24)", "not_null": False, "default": None},
    {"column_name": "target_user_id", "data_type": "uuid", "not_null": False, "default": None},
    {"column_name": "business_details", "data_type": "jsonb", "not_null": False, "default": None},
    {"column_name": "catalogue_version", "data_type": "integer", "not_null": False, "default": None},
]
COLLECTION_SOURCE_GUARD_COLUMNS = [
    {"column_name": "company_id", "data_type": "uuid", "not_null": True, "default": None},
    {"column_name": "generation", "data_type": "bigint", "not_null": True, "default": "0"},
]


def _load_exact(
    root: Path, relative_path: str, expected_sha256: str, module_name: str
) -> types.ModuleType:
    path = root / relative_path
    if path.is_symlink() or not path.is_file():
        raise CompatibilityError(f"{relative_path} is missing, linked, or non-regular")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise CompatibilityError(f"immutable {relative_path} changed")
    module = types.ModuleType(module_name)
    module.__file__ = str(path)
    exec(compile(raw, str(path), "exec"), module.__dict__)
    return module


def _load_modules(root: Path):
    build50 = _load_exact(
        root,
        BUILD50_PENDING_HELPER,
        BUILD50_PENDING_HELPER_SHA256,
        "immutable_build50_pending_compatibility",
    )
    post50 = _load_exact(
        root,
        BUILD50_POST_HELPER,
        BUILD50_POST_HELPER_SHA256,
        "immutable_build50_post_migration_state",
    )
    build51 = _load_exact(
        root,
        BUILD51_POST_HELPER,
        BUILD51_POST_HELPER_SHA256,
        "immutable_build51_post_migration_state",
    )
    return build50, post50, build51


def _legacy_modules(post50: types.ModuleType, root: Path, *, head: str):
    fleet_classifier, historical, cleanup = post50._load_verifiers(root)
    historical.PRIOR_DATABASE_HEAD = head
    historical.EXPECTED_INSTALLATION_ROWS = EXPECTED_INSTALLATION_ROWS
    historical.EXPECTED_PENDING = EXPECTED_PENDING
    historical.EXPECTED_NONZERO = EXPECTED_NONZERO
    historical.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = (
        EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256
    )
    historical.EXPECTED_NONZERO_FLEET_SHA256 = EXPECTED_NONZERO_FLEET_SHA256
    return fleet_classifier, historical, cleanup


def _sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _verify_exact_pre_raw(history_raw: bytes, fleet_raw: bytes) -> None:
    # Historical receipt/cache rows can expire legitimately between a review
    # capture and the quiesced installer read. Their strict structure and
    # scoped values are validated by the immutable historical verifier below;
    # only the stable full-fleet document has a byte-for-byte production pin.
    if _sha(fleet_raw) != EXPECTED_RAW_FLEET_SHA256:
        raise CompatibilityError("quiesced 0089 fleet is not the reviewed 13-row evidence")


def _verify_pre_documents(root: Path, history_raw: bytes, fleet_raw: bytes) -> dict:
    _, post50, _ = _load_modules(root)
    classifier, historical, cleanup = _legacy_modules(
        post50, root, head=PRIOR_DATABASE_HEAD
    )
    history_document = historical._decode_json(
        history_raw, cleanup, label="historical state"
    )
    fleet_document = historical._decode_json(fleet_raw, cleanup, label="fleet state")
    history = historical._verify_scoped_history(history_document, cleanup)
    fleet = classifier._classify_opaque_fleet(
        historical._verify_fleet(fleet_document, cleanup)
    )
    return {"history": history, "fleet": fleet}


def _verify_post_documents(root: Path, history_raw: bytes, fleet_raw: bytes) -> dict:
    _, post50, build51 = _load_modules(root)
    history_envelope = build51.verify_envelope(build51._decode(history_raw))
    classifier, historical, cleanup = _legacy_modules(
        post50, root, head=CANDIDATE_DATABASE_HEAD
    )
    history = historical._verify_scoped_history(deepcopy(history_envelope), cleanup)
    fleet_document = historical._decode_json(fleet_raw, cleanup, label="fleet state")
    fleet = classifier._classify_opaque_fleet(
        historical._verify_fleet(fleet_document, cleanup)
    )
    return {"history": history, "fleet": fleet}


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CompatibilityError("duplicate transition-state JSON key")
        result[key] = value
    return result


def _reject_constant(value):
    raise CompatibilityError(f"nonfinite transition-state JSON value: {value}")


def _decode_transition(raw: bytes) -> dict:
    if not raw or len(raw) > MAX_DOCUMENT_BYTES:
        raise CompatibilityError("transition-state document size is invalid")
    try:
        document = json.loads(
            raw, object_pairs_hook=_pairs, parse_constant=_reject_constant
        )
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise CompatibilityError("transition-state document is invalid JSON") from exc
    _validate_transition_document(document)
    return document


def _validate_transition_document(document: object) -> None:
    if not isinstance(document, dict) or set(document) != {
        "schema_version",
        "kind",
        "database_revision",
        "database_identity",
        "table_inventory",
        "sequence_inventory",
        "reserved_schema",
        "finance_history",
    }:
        raise CompatibilityError("transition-state shape changed")
    if (
        type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or document["kind"] != "build52-quiesced-transition-v1"
        or document["database_revision"] not in {"0089", "0091"}
    ):
        raise CompatibilityError("transition-state identity changed")
    identity = document["database_identity"]
    if (
        not isinstance(identity, dict)
        or set(identity) != {"system_identifier", "database_name", "database_oid"}
        or not isinstance(identity["system_identifier"], str)
        or not re.fullmatch(r"[1-9][0-9]{0,19}", identity["system_identifier"])
        or not isinstance(identity["database_name"], str)
        or not re.fullmatch(r"[A-Za-z0-9_]{1,63}", identity["database_name"])
        or not isinstance(identity["database_oid"], str)
        or not re.fullmatch(r"[1-9][0-9]{0,9}", identity["database_oid"])
    ):
        raise CompatibilityError("transition database identity is invalid")
    inventory = document["table_inventory"]
    if not isinstance(inventory, list):
        raise CompatibilityError("transition table inventory is invalid")
    names = []
    for row in inventory:
        if (
            not isinstance(row, dict)
            or set(row) != {"table_name", "row_count", "row_sha256"}
            or not isinstance(row["table_name"], str)
            or not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", row["table_name"])
            or type(row["row_count"]) is not int
            or row["row_count"] < 0
            or not isinstance(row["row_sha256"], str)
            or not re.fullmatch(r"[a-f0-9]{64}", row["row_sha256"])
        ):
            raise CompatibilityError("transition table inventory row is malformed")
        names.append(row["table_name"])
    if names != sorted(names) or len(names) != len(set(names)):
        raise CompatibilityError("transition table inventory is unordered or duplicated")
    if not REQUIRED_PRESERVED_TABLES.issubset(names):
        raise CompatibilityError("transition table inventory is incomplete")
    sequences = document["sequence_inventory"]
    if not isinstance(sequences, list) or not sequences:
        raise CompatibilityError("transition sequence inventory is invalid")
    sequence_names = []
    for row in sequences:
        if (
            not isinstance(row, dict)
            or set(row) != {
                "sequence_name",
                "start_value",
                "min_value",
                "max_value",
                "increment_by",
                "cache_size",
                "cycle",
                "owned_by",
                "last_value",
                "is_called",
            }
            or not isinstance(row["sequence_name"], str)
            or not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", row["sequence_name"])
            or any(type(row[key]) is not int for key in (
                "start_value", "min_value", "max_value", "increment_by",
                "cache_size", "last_value",
            ))
            or type(row["cycle"]) is not bool
            or type(row["is_called"]) is not bool
            or (
                row["owned_by"] is not None
                and (
                    not isinstance(row["owned_by"], str)
                    or not re.fullmatch(
                        r"[a-z][a-z0-9_]{0,62}\.[a-z][a-z0-9_]{0,62}",
                        row["owned_by"],
                    )
                )
            )
        ):
            raise CompatibilityError("transition sequence inventory row is malformed")
        sequence_names.append(row["sequence_name"])
    if sequence_names != sorted(sequence_names) or len(sequence_names) != len(set(sequence_names)):
        raise CompatibilityError("transition sequence inventory is unordered or duplicated")
    reserved = document["reserved_schema"]
    if (
        not isinstance(reserved, dict)
        or set(reserved) != {"audit_semantic_columns", "collection_source_guards"}
        or not isinstance(reserved["audit_semantic_columns"], list)
        or not isinstance(reserved["collection_source_guards"], dict)
        or set(reserved["collection_source_guards"]) != {"state", "columns"}
        or reserved["collection_source_guards"]["state"] not in {"absent", "present"}
        or not isinstance(reserved["collection_source_guards"]["columns"], list)
    ):
        raise CompatibilityError("transition reserved schema is invalid")
    finance = document["finance_history"]
    if not isinstance(finance, dict) or set(finance) != FINANCE_TABLES:
        raise CompatibilityError("transition finance inventory changed")
    for value in finance.values():
        if not isinstance(value, dict) or set(value) != {
            "state",
            "row_count",
            "row_sha256",
        }:
            raise CompatibilityError("transition finance row shape changed")
        if value["state"] == "absent":
            if value["row_count"] is not None or value["row_sha256"] is not None:
                raise CompatibilityError("absent transition finance table has row evidence")
        elif value["state"] == "present":
            if (
                type(value["row_count"]) is not int
                or value["row_count"] < 0
                or not isinstance(value["row_sha256"], str)
                or not re.fullmatch(r"[a-f0-9]{64}", value["row_sha256"])
            ):
                raise CompatibilityError("present transition finance evidence is invalid")
        else:
            raise CompatibilityError("transition finance state is invalid")


def _verify_transition_phase_schema(document: dict, *, head: str) -> None:
    if document["database_revision"] != head:
        raise CompatibilityError("transition phase database head changed")
    expected_audit = [] if head == PRIOR_DATABASE_HEAD else AUDIT_SEMANTIC_COLUMNS
    expected_guard = (
        {"state": "absent", "columns": []}
        if head == PRIOR_DATABASE_HEAD
        else {"state": "present", "columns": COLLECTION_SOURCE_GUARD_COLUMNS}
    )
    if (
        document["reserved_schema"]["audit_semantic_columns"] != expected_audit
        or document["reserved_schema"]["collection_source_guards"] != expected_guard
    ):
        raise CompatibilityError("reserved Build52 schema is present in the wrong phase")
    expected_finance = (
        {"state": "absent", "row_count": None, "row_sha256": None}
        if head == PRIOR_DATABASE_HEAD
        else {"state": "present", "row_count": 0, "row_sha256": EMPTY_SHA256}
    )
    if any(value != expected_finance for value in document["finance_history"].values()):
        raise CompatibilityError("Build52 finance history is present in the wrong phase")


def _verify_transition_pair(pre: dict, after: dict, *, rollback: bool) -> dict:
    _validate_transition_document(pre)
    _validate_transition_document(after)
    expected_after_head = PRIOR_DATABASE_HEAD if rollback else CANDIDATE_DATABASE_HEAD
    _verify_transition_phase_schema(pre, head=PRIOR_DATABASE_HEAD)
    _verify_transition_phase_schema(after, head=expected_after_head)
    for key in ("system_identifier", "database_name"):
        if pre["database_identity"][key] != after["database_identity"][key]:
            raise CompatibilityError("transition database lineage changed")
    if not rollback and pre["database_identity"] != after["database_identity"]:
        raise CompatibilityError("database identity changed during in-place migration")
    if pre["table_inventory"] != after["table_inventory"]:
        raise CompatibilityError("quiesced Audit, fleet, or business history changed")
    if pre["sequence_inventory"] != after["sequence_inventory"]:
        raise CompatibilityError("quiesced sequence definition, ownership, or state changed")
    return {
        "preserved_table_count": len(pre["table_inventory"]),
        "database_lineage_preserved": True,
        "logical_restore_verified": rollback,
    }


def _verify_sources(build50: types.ModuleType, prior: Path, candidate: Path) -> dict:
    before = build50._inventory(prior, PROTECTED_SCOPES)
    after = build50._inventory(candidate, PROTECTED_SCOPES)
    if (
        len(before) != PRIOR_PROTECTED_PATH_COUNT
        or build50._inventory_sha256(before) != PRIOR_PROTECTED_SOURCE_SHA256
    ):
        raise CompatibilityError("actual deployed Build50 protected source changed")
    changes = build50._changes(before, after)
    if (
        len(after) != CANDIDATE_PROTECTED_PATH_COUNT
        or len(changes) != REVIEWED_CHANGED_PATH_COUNT
        or build50._inventory_sha256(after) != CANDIDATE_PROTECTED_SOURCE_SHA256
    ):
        raise CompatibilityError("candidate protected source changed after review")
    return {
        "prior_protected_source_sha256": PRIOR_PROTECTED_SOURCE_SHA256,
        "candidate_protected_source_sha256": CANDIDATE_PROTECTED_SOURCE_SHA256,
        "prior_protected_path_count": len(before),
        "candidate_protected_path_count": len(after),
        "reviewed_changed_path_count": len(changes),
        "same_tree_tag_is_identity": False,
    }


def _verify_identity(args, build50: types.ModuleType) -> None:
    if not CANDIDATE_RELEASE_FROZEN:
        raise CompatibilityError("Build52 protected source has not passed independent freeze")
    if not isinstance(CANDIDATE_BACKEND_MANIFEST_SHA256, str):
        raise CompatibilityError("Build52 frozen backend identity is incomplete")
    expected = (
        PRIOR_REVISION,
        PRIOR_VERSION,
        PRIOR_DATABASE_HEAD,
        CANDIDATE_VERSION,
        CANDIDATE_DATABASE_HEAD,
        PRIOR_SOURCE_ARCHIVE_SHA256,
        PRIOR_BACKEND_MANIFEST_SHA256,
        CANDIDATE_BACKEND_MANIFEST_SHA256,
        PRIOR_BACKEND_IMAGE_ID,
    )
    actual = (
        args.prior_revision,
        args.prior_version,
        args.prior_db_head,
        args.candidate_version,
        args.candidate_db_head,
        args.prior_source_archive_sha256,
        args.prior_backend_manifest_sha256,
        args.candidate_backend_manifest_sha256,
        args.prior_image_id,
    )
    if actual != expected:
        raise CompatibilityError("unsupported or unfrozen Build50-to-Build52 identity")
    if args.prior_revision == SAME_TREE_TAG_REVISION:
        raise CompatibilityError("same-tree tag commit cannot replace deployed Build50 identity")
    if (
        not re.fullmatch(r"[a-f0-9]{40}", args.candidate_revision)
        or args.candidate_revision in {PRIOR_REVISION, SAME_TREE_TAG_REVISION}
        or not re.fullmatch(r"[a-f0-9]{64}", args.candidate_source_archive_sha256)
        or args.candidate_source_archive_sha256 == PRIOR_SOURCE_ARCHIVE_SHA256
    ):
        raise CompatibilityError("candidate source identity is invalid")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", args.candidate_image_id):
        raise CompatibilityError("candidate backend image ID is not immutable")
    if args.candidate_image_id == args.prior_image_id:
        raise CompatibilityError("candidate and prior backend images are identical")
    if build50._backend_manifest_sha256(args.prior_source_root) != PRIOR_BACKEND_MANIFEST_SHA256:
        raise CompatibilityError("deployed Build50 backend manifest changed")
    if (
        build50._backend_manifest_sha256(args.candidate_source_root)
        != CANDIDATE_BACKEND_MANIFEST_SHA256
    ):
        raise CompatibilityError("Build52 backend manifest changed after freeze")


def _verify_environment(args, build50: types.ModuleType) -> int:
    implementation = build50._implementation
    implementation.PRIOR_VERSION = PRIOR_VERSION
    implementation.PRIOR_REVISION = PRIOR_REVISION
    implementation.CANDIDATE_VERSION = CANDIDATE_VERSION
    implementation._verify_environment(
        args.prior_env.read_bytes(),
        args.live_env.read_bytes(),
        args.candidate_env.read_bytes(),
        args.candidate_revision,
    )
    before = implementation._docker_environment(
        implementation._json_document(args.prior_runtime_env.read_bytes()),
        args.prior_image_id,
    )
    after = implementation._docker_environment(
        implementation._json_document(args.candidate_image_env.read_bytes()),
        args.candidate_image_id,
    )
    compose = implementation._json_document(args.candidate_compose_config.read_bytes())
    try:
        backend = compose["services"]["backend"]
        overrides = backend["environment"]
    except (KeyError, TypeError) as exc:
        raise CompatibilityError("candidate Compose backend environment is missing") from exc
    if (
        backend.get("image") != f"d-company-erp-backend:{args.candidate_revision}"
        or "env_file" in backend
        or not isinstance(overrides, dict)
        or not all(isinstance(key, str) and isinstance(value, str) for key, value in overrides.items())
    ):
        raise CompatibilityError("candidate Compose environment source changed")
    after.update(overrides)
    for values, version, revision in (
        (before, PRIOR_VERSION, PRIOR_REVISION),
        (after, CANDIDATE_VERSION, args.candidate_revision),
    ):
        if values.pop("APP_VERSION", None) != version or values.pop(
            "APP_REVISION", None
        ) != revision:
            raise CompatibilityError("effective runtime release identity changed")
    if before != after:
        raise CompatibilityError("effective backend environment changed")
    return len(before) + 2


def _pre_report(args, root: Path) -> dict:
    build50, _, _ = _load_modules(root)
    for source_root in (args.prior_source_root, args.candidate_source_root):
        build50._private_root(source_root)
    if (
        args.prior_source_root.resolve() == args.candidate_source_root.resolve()
        or root.resolve() != args.candidate_source_root.resolve()
    ):
        raise CompatibilityError("source archive binding is invalid")
    _verify_identity(args, build50)
    evidence = (
        args.raw_historical_state,
        args.raw_fleet_state,
        args.raw_transition_state,
        args.prior_env,
        args.live_env,
        args.candidate_env,
        args.prior_runtime_env,
        args.candidate_image_env,
        args.candidate_compose_config,
    )
    for path in evidence:
        build50._private_file(path)
    history_raw = args.raw_historical_state.read_bytes()
    fleet_raw = args.raw_fleet_state.read_bytes()
    _verify_exact_pre_raw(history_raw, fleet_raw)
    legacy = _verify_pre_documents(root, history_raw, fleet_raw)
    transition_raw = args.raw_transition_state.read_bytes()
    transition = _decode_transition(transition_raw)
    _verify_transition_phase_schema(transition, head=PRIOR_DATABASE_HEAD)
    source = _verify_sources(build50, args.prior_source_root, args.candidate_source_root)
    environment_count = _verify_environment(args, build50)
    return {
        "schema_version": 1,
        "status": "pending_carried_unresolved",
        "phase": "pre_migration_0089",
        "transition": {
            "prior_revision": PRIOR_REVISION,
            "prior_version": PRIOR_VERSION,
            "prior_database_head": PRIOR_DATABASE_HEAD,
            "candidate_revision": args.candidate_revision,
            "candidate_version": CANDIDATE_VERSION,
            "candidate_database_head": CANDIDATE_DATABASE_HEAD,
        },
        "source_proof": source,
        "historical_cleanup_proof": legacy["history"],
        "fleet": legacy["fleet"],
        "raw_historical_state_sha256": _sha(history_raw),
        "raw_fleet_state_sha256": _sha(fleet_raw),
        "raw_transition_state_sha256": _sha(transition_raw),
        "effective_environment_key_count": environment_count,
        "queue_drained": False,
        "pending_not_drained": True,
        "tablet_queue_contents_verified": False,
        "telemetry_rewritten": False,
        "installation_retired": False,
        "historic_pending_work_classification": "unknown_preserved_work",
        "server_contract_compatibility_only": True,
    }


def _post_report(args, root: Path, *, rollback: bool) -> dict:
    build50, _, _ = _load_modules(root)
    for path in (
        args.pre_historical_state,
        args.pre_fleet_state,
        args.pre_transition_state,
        args.after_historical_state,
        args.after_fleet_state,
        args.after_transition_state,
    ):
        build50._private_file(path)
    pre_history_raw = args.pre_historical_state.read_bytes()
    pre_fleet_raw = args.pre_fleet_state.read_bytes()
    _verify_exact_pre_raw(pre_history_raw, pre_fleet_raw)
    pre = _verify_pre_documents(root, pre_history_raw, pre_fleet_raw)
    after_history_raw = args.after_historical_state.read_bytes()
    after_fleet_raw = args.after_fleet_state.read_bytes()
    if rollback:
        after = _verify_pre_documents(root, after_history_raw, after_fleet_raw)
    else:
        after = _verify_post_documents(root, after_history_raw, after_fleet_raw)
    if pre != after:
        raise CompatibilityError("scoped cleanup or exact opaque fleet changed")
    transition = _verify_transition_pair(
        _decode_transition(args.pre_transition_state.read_bytes()),
        _decode_transition(args.after_transition_state.read_bytes()),
        rollback=rollback,
    )
    return {
        "schema_version": 1,
        "status": (
            "verified_prior_restore_with_pending_preserved"
            if rollback
            else "post_migration_pending_carried_unresolved_verified"
        ),
        "phase": "rollback_0089" if rollback else "post_migration_0091",
        "database_revision": PRIOR_DATABASE_HEAD if rollback else CANDIDATE_DATABASE_HEAD,
        "historical_cleanup_proof": after["history"],
        "fleet": after["fleet"],
        "transition_preservation": transition,
        "raw_after_historical_state_sha256": _sha(after_history_raw),
        "raw_after_fleet_state_sha256": _sha(after_fleet_raw),
        "queue_drained": False,
        "pending_not_drained": True,
        "tablet_queue_contents_verified": False,
        "telemetry_rewritten": False,
        "installation_retired": False,
        "historic_pending_work_classification": "unknown_preserved_work",
    }


def _add_pre_arguments(parser: argparse.ArgumentParser) -> None:
    for name in (
        "prior-source-root",
        "candidate-source-root",
        "raw-historical-state",
        "raw-fleet-state",
        "raw-transition-state",
        "prior-env",
        "live-env",
        "candidate-env",
        "prior-runtime-env",
        "candidate-image-env",
        "candidate-compose-config",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)
    for name in (
        "prior-revision",
        "prior-version",
        "prior-db-head",
        "candidate-revision",
        "candidate-version",
        "candidate-db-head",
        "prior-image-id",
        "candidate-image-id",
        "prior-backend-manifest-sha256",
        "candidate-backend-manifest-sha256",
        "prior-source-archive-sha256",
        "candidate-source-archive-sha256",
    ):
        parser.add_argument(f"--{name}", required=True)


def _add_after_arguments(parser: argparse.ArgumentParser) -> None:
    for name in (
        "pre-historical-state",
        "pre-fleet-state",
        "pre-transition-state",
        "after-historical-state",
        "after-fleet-state",
        "after-transition-state",
    ):
        parser.add_argument(f"--{name}", type=Path, required=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="phase", required=True)
    _add_pre_arguments(subparsers.add_parser("pre"))
    _add_after_arguments(subparsers.add_parser("post"))
    _add_after_arguments(subparsers.add_parser("rollback"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        if args.phase == "pre":
            report = _pre_report(args, root)
        else:
            report = _post_report(args, root, rollback=args.phase == "rollback")
    except (CompatibilityError, OSError, ValueError) as exc:
        print(f"build52 compatibility refused: {exc}", file=sys.stderr)
        return 1
    sys.stdout.write(json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
