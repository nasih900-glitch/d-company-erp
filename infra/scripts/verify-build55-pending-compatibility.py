#!/usr/bin/env python3
"""Exact Build54/0091 -> Build55/0092 preservation admission.

Opaque tablet work is retained as reported, including current-build work. This
profile never drains queues, reclassifies old rows, edits telemetry, or grants an
Android offer. Normal CLI admission stays closed until independent source/schema
freeze and migration/restore tests have pinned this profile.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from datetime import datetime
from pathlib import Path
from uuid import UUID

PRIOR_REVISION = "5ad146ae076f5178acc2c8e4cb840ecbb0d0343a"
PRIOR_VERSION = "3.1.46"
PRIOR_DATABASE_HEAD = "0091"
CANDIDATE_VERSION = "3.1.47"
CANDIDATE_DATABASE_HEAD = "0092"
PRIOR_SOURCE_ARCHIVE_SHA256 = (
    "7ded1fbc0afb2fb0dc93421bd6fc9b9e9ecef677e69b28ec1a4830d8252f6db5"
)
PRIOR_BACKEND_MANIFEST_SHA256 = (
    "3b82279f4bb7edad7640bcb73c7b76cbff7c25000beaf4ff988d3a33b6a7cf03"
)
PRIOR_PROTECTED_SOURCE_SHA256 = (
    "8f3c6156826a3c0e40ab9ff0f0750c7e7243b1b1648032a3ecaae19b3f77c2b3"
)
PRIOR_SCHEMA_SHA256 = "f3be2f415f6180433cd117aaa5b9e5b65a46e8122ac265bcc10521570fe6c26d"
PRIOR_DATABASE_IDENTITY = {
    "database_name": "erp",
    "database_oid": 16384,
    "system_identifier": "7695211338147233825",
}
PRIOR_SCHEMA_ROW_COUNT = 4485
PRIOR_TABLE_NAMES_SHA256 = (
    "be45aec51c716fb1f961dac032d71a7979c29ea3c58f9c497b02d26ee1db2474"
)
ALL_INSTALLATIONS_IDENTITY_SHA256 = (
    "ea5bfed358dc2a4a16a3876bb8a45daa75c40833498680e62643b44c90114664"
)
FLEET_GUARD_SHA256 = "495f0fce983228b68d3a5141f844c35c5ed1b0438dc26ff041737aba3b046f8b"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
SERVICES = ("backend", "caddy", "frontend", "postgres", "redis")
PRIOR_IMAGES = {
    "backend": "sha256:936db8ea9033b5eebe049a89f536c5dbe924be6cf308425fc5c1e1c40925ea74",
    "caddy": "sha256:00e645ae268a194c01682ca1704ea4719dd7add48301bcc90fe9daeff2caba20",
    "frontend": "sha256:e96276203ef3d429a5b276735f5e19344385c192b82cdb67ead0dd2a755762d8",
    "postgres": "sha256:1cda7bf6d3a9aa1f6f6d80be010bc6f04673057a2b608b7a46e0d0fa9dc07b9a",
    "redis": "sha256:dbcb980813ceb17ff0eddac4a31ce9c9ff7f5f5b5433a3f8e71be444abcc8401",
}
PRIOR_ENVIRONMENT_SHA256 = {
    "backend": "3330cc914ab7814b3b503dbd538b26b9d38752810f56430ce811bdc42eaaf91b",
    "caddy": "d5dc922cb1819841942b32f4c784da0962b34bcea4ff259e0d72fb9711bd1c71",
    "frontend": "267b46eeea2aaaf7f05825c1430da29a350a5b6bcec9959ac8027bfd53ef7b9b",
    "postgres": "83ad37062c1cd053b7a54dae2235dd99a1c57cb784a0addf080b256b2d50fb3a",
    "redis": "917b30d24b4f5abfc05346a032f859ab3a7c5cb9b77f34f4aed8c9b2949ab8b4",
}
PRIOR_COMMAND_SHA256 = {
    "backend": "98abce658d9d19c71c7523522cdfad41498a7c65df43a81864696b7c982990ce",
    "caddy": "a3c23ab06fbf190ea08f12d5786aac735394681be159c9db95d6be56625daab4",
    "frontend": "38a49158f19383d63a7deccb19bc93105a87393a9f8f7fb9e728ae6c5fd4d587",
    "redis": "cae550842eb117a31b10306bf83978957c939e643f0853b7c7fead1ba08a71b9",
    "postgres": "39baf63151d31de5015432df0a9b277c2a6948d4749ea0353349b9ac19e8e012",
}
PRIOR_COMPOSE_SHA256 = (
    "4f999db66a4acbbf2306fbecc7640794f600ee85055923dca2ca0eabbb7d9bc9"
)
PRIOR_OPERATIONAL_OVERRIDE_SHA256 = (
    "8efb4fbb75d87136e7425ff0277455c972148bfc6a38a8ceecd8db20a7c9fff4"
)
BACKEND_UVICORN_ENTRYPOINT = [
    "/usr/local/bin/python3.14",
    "/usr/local/bin/uvicorn",
    "app.main:app",
    "--host",
    "0.0.0.0",
    "--port",
    "8000",
    "--proxy-headers",
]
# Independent review fills these final closure values; the helper cannot hash
# its own enclosing archive. Historical release maps remain immutable.
CANDIDATE_RELEASE_FROZEN = True
CANDIDATE_BACKEND_MANIFEST_SHA256: str | None = 'c3ab36ac45cef02c586771bb60d14cf9ca12a65e9f759fe1135f68cd920f7ae8'
CANDIDATE_PROTECTED_SOURCE_SHA256: str | None = '19e57d03fa45830d05db8d3d1eaf7d7639e5ae5e3cb2ad52357587494664180b'
EXPECTED_POLICY_SCHEMA_ROWS: tuple[str, ...] = (
    "column|android_update_policy|1|id|integer|NO||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|android_update_policy|2|mode|character varying|NO||20|80|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|android_update_policy|3|revision|integer|NO||||32|2|0||||||||||pg_catalog|int4|NO||||||NO|NEVER||YES",
    "column|android_update_policy|4|release_id|uuid|YES||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|android_update_policy|5|release_fingerprint|character varying|YES||64|256|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|android_update_policy|6|reason|character varying|NO||500|2000|||||||||||||pg_catalog|varchar|NO||||||NO|NEVER||YES",
    "column|android_update_policy|7|updated_by|uuid|NO||||||||||||||||pg_catalog|uuid|NO||||||NO|NEVER||YES",
    "column|android_update_policy|8|created_at|timestamp with time zone|NO|now()||||||6|||||||||pg_catalog|timestamptz|NO||||||NO|NEVER||YES",
    "column|android_update_policy|9|updated_at|timestamp with time zone|NO|now()||||||6|||||||||pg_catalog|timestamptz|NO||||||NO|NEVER||YES",
    "constraint|android_update_policy|android_update_policy_id_check|CHECK (id = 1)",
    "constraint|android_update_policy|android_update_policy_mode_check|CHECK (mode::text = ANY (ARRAY['optional'::character varying, 'required'::character varying]::text[]))",
    "constraint|android_update_policy|android_update_policy_pkey|PRIMARY KEY (id)",
    "constraint|android_update_policy|android_update_policy_reason_check|CHECK (length(TRIM(BOTH FROM reason)) >= 3 AND length(TRIM(BOTH FROM reason)) <= 500)",
    "constraint|android_update_policy|android_update_policy_release_id_fkey|FOREIGN KEY (release_id) REFERENCES android_releases(id) ON DELETE RESTRICT",
    "constraint|android_update_policy|android_update_policy_revision_check|CHECK (revision >= 1 AND revision <= 2147483647)",
    "constraint|android_update_policy|android_update_policy_updated_by_fkey|FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT",
    "constraint|android_update_policy|ck_android_update_policy_target|CHECK (mode::text = 'optional'::text AND release_id IS NULL AND release_fingerprint IS NULL OR mode::text = 'required'::text AND release_id IS NOT NULL AND release_fingerprint IS NOT NULL AND release_fingerprint::text ~ '^[a-f0-9]{64}$'::text)",
    "index|android_update_policy|android_update_policy_pkey|CREATE UNIQUE INDEX android_update_policy_pkey ON public.android_update_policy USING btree (id)",
)
# Only these five reviewed full strings may differ after pg_restore.
# Live migration post-proof never uses these aliases. Raw evidence is retained.
RESTORE_CONSTRAINT_PAIRS = (
    (
        "constraint|companies|ck_company_google_sheets_export_mode|CHECK (google_sheets_export_mode::text = ANY (ARRAY['legacy_transactions'::character varying, 'daily_collections'::character varying]::text[]))",
        "constraint|companies|ck_company_google_sheets_export_mode|CHECK (google_sheets_export_mode::text = ANY (ARRAY['legacy_transactions'::character varying::text, 'daily_collections'::character varying::text]))",
    ),
    (
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_key|CHECK (rank_key::text = ANY (ARRAY['rookie'::character varying, 'player'::character varying, 'pro'::character varying, 'legend'::character varying]::text[]))",
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_key|CHECK (rank_key::text = ANY (ARRAY['rookie'::character varying::text, 'player'::character varying::text, 'pro'::character varying::text, 'legend'::character varying::text]))",
    ),
    (
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_source|CHECK (source::text = ANY (ARRAY['legacy_baseline'::character varying, 'paid_gaming'::character varying]::text[]))",
        "constraint|customer_gaming_rank_awards|ck_customer_gaming_rank_award_source|CHECK (source::text = ANY (ARRAY['legacy_baseline'::character varying::text, 'paid_gaming'::character varying::text]))",
    ),
    (
        "constraint|points_redemptions|ck_points_redemption_kind|CHECK (redemption_kind::text = ANY (ARRAY['generic'::character varying, 'fixed_reward'::character varying, 'legacy_frozen'::character varying]::text[]))",
        "constraint|points_redemptions|ck_points_redemption_kind|CHECK (redemption_kind::text = ANY (ARRAY['generic'::character varying::text, 'fixed_reward'::character varying::text, 'legacy_frozen'::character varying::text]))",
    ),
    (
        "constraint|daily_tender_reconciliations|daily_tender_reconciliations_kind_check|CHECK (kind::text = ANY (ARRAY['applied'::character varying, 'unresolved'::character varying]::text[]))",
        "constraint|daily_tender_reconciliations|daily_tender_reconciliations_kind_check|CHECK (kind::text = ANY (ARRAY['applied'::character varying::text, 'unresolved'::character varying::text]))",
    ),
)

PRIOR_PROTECTED_SCOPES = (
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
    "infra/scripts/verify-build55-transition-state.sql",
    "infra/scripts/verify-build55-authenticated-acceptance.py",
    "infra/scripts/capture-build55-runtime.py",
)
# The deployed54 closure retains its original scope/hash. Only55 expands it.
PROTECTED_SCOPES = (
    'backend',
    'android-native/app/src/main',
    'android-native/app/schemas',
    'android-native/app/build.gradle.kts',
    'android-native/app/gradle.lockfile',
    'android-native/build.gradle.kts',
    'android-native/settings.gradle.kts',
    'android-native/gradle.properties',
    'android-native/gradle/verification-metadata.xml',
    'infra/docker',
    'infra/caddy',
    'infra/nginx',
    '.dockerignore',
    '.env.production.example',
    'docker-compose.prod.yml',
    'infra/scripts/install-on-vm.sh',
    'infra/scripts/prepare-production-env.sh',
    'infra/scripts/generate-secrets.sh',
    'infra/scripts/validate-production-env.sh',
    'infra/scripts/verify-code30-2-post-cleanup-state.py',
    'infra/scripts/verify-code30-2-post-cleanup-state.sql',
    'infra/scripts/verify-build45-pending-state.sql',
    'infra/scripts/verify-build46-pending-compatibility.py',
    'infra/scripts/verify-build47-pending-compatibility.py',
    'infra/scripts/verify-build48-pending-compatibility.py',
    'infra/scripts/verify-build49-pending-compatibility.py',
    'infra/scripts/verify-build50-pending-compatibility.py',
    'infra/scripts/verify-build50-post-migration-state.py',
    'infra/scripts/verify-build51-post-migration-state.py',
    'infra/scripts/verify-build51-post-migration-state.sql',
    'infra/scripts/verify-build52-transition-state.sql',
    'ops/runtime_release_parity.py',
    'ops/verify_web_public_assets.py',
    'infra/scripts/verify-production-runtime-images.sh',
    'tests/test_caddy_dependency_security.py',
    'frontend/src',
    'frontend/public',
    'frontend/scripts',
    'frontend/.env.example',
    'frontend/package.json',
    'frontend/package-lock.json',
    'frontend/vite.config.ts',
    'infra/scripts/verify-build55-transition-state.sql',
    'infra/scripts/verify-build55-authenticated-acceptance.py',
    'infra/scripts/capture-build55-runtime.py',
    'infra/scripts/bootstrap-verified-pip.py',
    'infra/scripts/install-operations-monitor.sh',
)
MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
SHA256 = re.compile(r"[a-f0-9]{64}")
NAME = re.compile(r"[a-z][a-z0-9_]{0,62}")
POLICY_TABLE = "android_update_policy"


class CompatibilityError(ValueError):
    pass


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CompatibilityError("duplicate JSON key")
        result[key] = value
    return result


def decode(raw: bytes) -> dict:
    if not raw or len(raw) > MAX_DOCUMENT_BYTES:
        raise CompatibilityError("evidence size is invalid")
    try:
        value = json.loads(
            raw,
            object_pairs_hook=_pairs,
            parse_constant=lambda _: (_ for _ in ()).throw(
                CompatibilityError("nonfinite JSON")
            ),
        )
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise CompatibilityError("evidence is not strict JSON") from exc
    if not isinstance(value, dict):
        raise CompatibilityError("evidence must be an object")
    return value


def private_file(path: Path) -> bytes:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != 0
            or stat.S_IMODE(metadata.st_mode) not in (0o400, 0o600)
            or metadata.st_nlink != 1
            or metadata.st_size > MAX_DOCUMENT_BYTES
        ):
            raise CompatibilityError(
                "evidence must be bounded root-owned private regular data"
            )
        return os.read(descriptor, MAX_DOCUMENT_BYTES + 1)
    finally:
        os.close(descriptor)


def inventory(root: Path, scopes: tuple[str, ...]) -> dict[str, str]:
    result = {}
    for scope in scopes:
        target = root / scope
        if not target.exists() and not target.is_symlink():
            continue
        paths = [target] if not target.is_dir() else sorted(target.rglob("*"))
        if target.is_symlink():
            raise CompatibilityError("linked source")
        for path in paths:
            if path.is_symlink():
                raise CompatibilityError("linked source")
            if not path.is_file() and not path.is_dir():
                raise CompatibilityError("nonregular source entry")
            if path.is_file():
                name = path.relative_to(root).as_posix()
                if name in result:
                    raise CompatibilityError("overlapping source scopes")
                result[name] = sha(path.read_bytes())
    return result


def inventory_hash(values: dict[str, str]) -> str:
    return sha(
        "".join(
            f"{name}\0{digest}\n" for name, digest in sorted(values.items())
        ).encode()
    )


def verify_inventory(
    document: dict, head: str, *, require_production_schema: bool = True
) -> None:
    fields = {
        "schema_version",
        "kind",
        "database_revision",
        "database_identity",
        "tables",
        "sequences",
        "schema_catalog_row_count",
        "schema_catalog_sha256",
        "schema_catalog_rows",
    }
    if (
        set(document) != fields
        or type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or document["kind"] != "build55-full-transition-v1"
        or document["database_revision"] != head
    ):
        raise CompatibilityError("full transition evidence contract changed")
    identity = document["database_identity"]
    if not isinstance(identity, dict) or set(identity) != {
        "database_name",
        "database_oid",
        "system_identifier",
    }:
        raise CompatibilityError("database identity is missing")
    if not isinstance(identity["database_name"], str) or not re.fullmatch(
        r"[A-Za-z0-9_]{1,63}", identity["database_name"]
    ):
        raise CompatibilityError("database name is invalid")
    if (
        type(identity["database_oid"]) is not int
        or identity["database_oid"] <= 0
        or not re.fullmatch(r"[1-9][0-9]{0,19}", str(identity["system_identifier"]))
    ):
        raise CompatibilityError("database lineage is invalid")
    tables = document["tables"]
    if not isinstance(tables, dict) or not tables:
        raise CompatibilityError("full table inventory missing")
    for name, row in tables.items():
        if (
            not isinstance(name, str)
            or not NAME.fullmatch(name)
            or not isinstance(row, dict)
            or set(row) != {"row_count", "row_sha256"}
            or type(row["row_count"]) is not int
            or row["row_count"] < 0
            or not SHA256.fullmatch(str(row["row_sha256"]))
        ):
            raise CompatibilityError("table inventory is malformed")
    legacy_names = sorted(set(tables) - {POLICY_TABLE})
    if sha("\n".join(legacy_names).encode()) != PRIOR_TABLE_NAMES_SHA256:
        raise CompatibilityError("legacy public table inventory changed")
    if tables.get("alembic_version") != {
        "row_count": 1,
        "row_sha256": sha(json.dumps({"version_num": head}).encode()),
    }:
        raise CompatibilityError("Alembic history is not exactly one reviewed revision")
    if head == PRIOR_DATABASE_HEAD and POLICY_TABLE in tables:
        raise CompatibilityError("new policy table exists before migration")
    if head == CANDIDATE_DATABASE_HEAD and tables.get(POLICY_TABLE) != {
        "row_count": 0,
        "row_sha256": EMPTY_SHA256,
    }:
        raise CompatibilityError("policy table must be empty before normal writers")
    sequences = document["sequences"]
    expected_sequence = {
        "last_value",
        "is_called",
        "start_value",
        "increment_by",
        "min_value",
        "max_value",
        "cache_size",
        "cycle",
        "owned_by",
    }
    if not isinstance(sequences, dict) or not sequences:
        raise CompatibilityError("sequence inventory missing")
    for name, row in sequences.items():
        if (
            not isinstance(name, str)
            or not NAME.fullmatch(name)
            or not isinstance(row, dict)
            or set(row) != expected_sequence
            or type(row["is_called"]) is not bool
            or type(row["cycle"]) is not bool
            or not isinstance(row["owned_by"], str)
            or any(
                type(row[key]) is not int
                for key in expected_sequence - {"is_called", "cycle", "owned_by"}
            )
        ):
            raise CompatibilityError("sequence inventory is malformed")
    rows = document["schema_catalog_rows"]
    if (
        not isinstance(rows, list)
        or not all(isinstance(row, str) for row in rows)
        or rows != sorted(set(rows))
    ):
        raise CompatibilityError("schema catalogue is unordered or duplicated")
    if (
        type(document["schema_catalog_row_count"]) is not int
        or document["schema_catalog_row_count"] != len(rows)
        or document["schema_catalog_sha256"] != sha("\n".join(rows).encode())
    ):
        raise CompatibilityError("schema catalogue checksum changed")
    if (
        head == PRIOR_DATABASE_HEAD
        and require_production_schema
        and document["schema_catalog_sha256"] != PRIOR_SCHEMA_SHA256
    ):
        raise CompatibilityError("deployed54 schema is not the reviewed predecessor")


def verify_prior(document: dict) -> None:
    """Production admission is separate from generic preservation comparisons."""
    verify_inventory(document, PRIOR_DATABASE_HEAD)
    if document["database_identity"] != PRIOR_DATABASE_IDENTITY:
        raise CompatibilityError("not the reviewed production database identity")
    if document["schema_catalog_row_count"] != PRIOR_SCHEMA_ROW_COUNT:
        raise CompatibilityError("reviewed production schema row count changed")


def canonical_uuid(value: object) -> bool:
    try:
        return isinstance(value, str) and str(UUID(value)) == value
    except ValueError:
        return False


def utc_timestamp(value: object, *, nullable: bool = False) -> str | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str) or not re.fullmatch(
        r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z", value
    ):
        raise CompatibilityError("fleet UTC timestamp is malformed")
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise CompatibilityError("fleet UTC timestamp is invalid") from exc
    return value


def verify_fleet(document: dict, head: str) -> None:
    expected_fields = {
        "schema_version",
        "database_revision",
        "pending_outbox_count",
        "nonzero_installations",
        "installation_row_count",
        "nonzero_installation_count",
        "all_installations_identity_sha256",
        "nonzero_installations_identity_sha256",
        "client_installation_guard_trigger_count",
        "client_installation_guard_function_sha256",
        "client_installation_guard_trigger_definition_sha256",
        "client_installation_guard_function_definition_sha256",
    }
    if (
        set(document) != expected_fields
        or type(document.get("schema_version")) is not int
        or document["schema_version"] != 1
        or document["database_revision"] != head
    ):
        raise CompatibilityError("fleet identity changed")
    if (
        document["all_installations_identity_sha256"]
        != ALL_INSTALLATIONS_IDENTITY_SHA256
    ):
        raise CompatibilityError("full installation identity set changed")
    for key in (
        "pending_outbox_count",
        "nonzero_installation_count",
        "installation_row_count",
        "client_installation_guard_trigger_count",
    ):
        if type(document[key]) is not int or document[key] < 0:
            raise CompatibilityError("fleet counters are malformed")
    if (
        document["installation_row_count"] != 13
        or document["client_installation_guard_trigger_count"] != 1
        or document["client_installation_guard_function_definition_sha256"]
        != FLEET_GUARD_SHA256
    ):
        raise CompatibilityError("fleet identity or database write guard changed")
    for key in (
        "nonzero_installations_identity_sha256",
        "client_installation_guard_function_sha256",
        "client_installation_guard_trigger_definition_sha256",
    ):
        if not isinstance(document[key], str) or not SHA256.fullmatch(document[key]):
            raise CompatibilityError("fleet guard checksum is malformed")
    rows = document["nonzero_installations"]
    if not isinstance(rows, list):
        raise CompatibilityError("raw fleet rows are malformed")
    seen, total, row_hashes = set(), 0, []
    required = {
        "row_id",
        "installation_id",
        "company_id",
        "terminal_id",
        "platform",
        "version_name",
        "version_code",
        "pending_outbox_count",
        "last_seen_at",
        "last_successful_sync_at",
        "identity_sha256",
    }
    for row in rows:
        if (
            not isinstance(row, dict)
            or set(row) != required
            or any(
                not canonical_uuid(row[key])
                for key in ("row_id", "installation_id", "company_id")
            )
            or (
                row["terminal_id"] is not None
                and not canonical_uuid(row["terminal_id"])
            )
            or row["row_id"] in seen
            or row["platform"] != "android"
            or type(row["version_code"]) is not int
            or type(row["pending_outbox_count"]) is not int
            or row["pending_outbox_count"] <= 0
        ):
            raise CompatibilityError("opaque pending fleet row changed")
        if {37: "3.1.29", 41: "3.1.33", 54: "3.1.46"}.get(row["version_code"]) != row[
            "version_name"
        ]:
            raise CompatibilityError(
                "pending installation has an unreviewed client contract"
            )
        last_sync = utc_timestamp(row["last_successful_sync_at"], nullable=True)
        last_seen = utc_timestamp(row["last_seen_at"])
        # PostgreSQL concat_ws skips a NULL last-success timestamp, but its
        # terminal expression explicitly coalesces NULL to an empty string.
        parts = [
            row["row_id"],
            row["installation_id"],
            row["company_id"],
            row["terminal_id"] or "",
            row["platform"],
            row["version_name"],
            str(row["version_code"]),
            str(row["pending_outbox_count"]),
            last_sync,
            last_seen,
        ]
        digest = sha(chr(31).join(part for part in parts if part is not None).encode())
        if row["identity_sha256"] != digest:
            raise CompatibilityError("raw pending identity checksum changed")
        seen.add(row["row_id"])
        total += row["pending_outbox_count"]
        row_hashes.append((row["row_id"], digest))
    if (
        len(rows) != document["nonzero_installation_count"]
        or len(rows) > document["installation_row_count"]
        or total != document["pending_outbox_count"]
        or document["nonzero_installations_identity_sha256"]
        != sha("\n".join(digest for _, digest in sorted(row_hashes)).encode())
    ):
        raise CompatibilityError("every nonzero reported queue must be retained")


def restore_catalogue(rows: list[str]) -> list[str]:
    aliases = {
        variant: live
        for live, restored in RESTORE_CONSTRAINT_PAIRS
        for variant in (live, restored)
    }
    identities = {
        "|".join(live.split("|")[:3]) + "|" for live, _ in RESTORE_CONSTRAINT_PAIRS
    }
    seen = set()
    normalized = []
    for row in rows:
        identity = next(
            (prefix for prefix in identities if row.startswith(prefix)), None
        )
        if identity is not None:
            if identity in seen or row not in aliases:
                raise CompatibilityError(
                    "restored reviewed CHECK is missing, duplicated or changed"
                )
            seen.add(identity)
        normalized.append(aliases.get(row, row))
    return sorted(normalized)


def verify_pair(
    before: dict, after: dict, *, mode: str, writers_started: bool = False
) -> dict:
    if mode not in {"post", "restore", "rollback"}:
        raise CompatibilityError("unknown preservation phase")
    if writers_started:
        raise CompatibilityError(
            "old database restoration is forbidden after application writers start"
        )
    after_head = CANDIDATE_DATABASE_HEAD if mode == "post" else PRIOR_DATABASE_HEAD
    # This comparator proves preservation, not deployment admission. Fresh QA
    # schemas can have different historical ordinals/deparser representations.
    # Every CLI phase separately admits its immutable production baseline first.
    verify_inventory(before, PRIOR_DATABASE_HEAD, require_production_schema=False)
    verify_inventory(after, after_head, require_production_schema=False)
    if mode == "post" and before["database_identity"] != after["database_identity"]:
        raise CompatibilityError("in-place database identity changed")
    if mode == "restore" and (
        before["database_identity"]["system_identifier"]
        != after["database_identity"]["system_identifier"]
        or after["database_identity"]["database_name"]
        == before["database_identity"]["database_name"]
        or not after["database_identity"]["database_name"].startswith(
            "code17_restore_verify_"
        )
    ):
        raise CompatibilityError(
            "restore proof must use an owned disposable database in the same cluster"
        )
    if mode == "rollback" and any(
        before["database_identity"][key] != after["database_identity"][key]
        for key in ("database_name", "system_identifier")
    ):
        raise CompatibilityError("restored database lineage changed")
    expected = dict(before["tables"])
    if mode == "post":
        if not EXPECTED_POLICY_SCHEMA_ROWS:
            raise CompatibilityError(
                "new policy schema has not passed independent freeze"
            )
        expected[POLICY_TABLE] = {"row_count": 0, "row_sha256": EMPTY_SHA256}
        expected["alembic_version"] = {
            "row_count": 1,
            "row_sha256": sha(json.dumps({"version_num": after_head}).encode()),
        }
        if (
            sorted(before["schema_catalog_rows"] + list(EXPECTED_POLICY_SCHEMA_ROWS))
            != after["schema_catalog_rows"]
        ):
            raise CompatibilityError(
                "migration changed more than the exact reviewed policy schema"
            )
    elif restore_catalogue(before["schema_catalog_rows"]) != restore_catalogue(
        after["schema_catalog_rows"]
    ):
        raise CompatibilityError("restored public schema changed")
    if expected != after["tables"] or before["sequences"] != after["sequences"]:
        raise CompatibilityError(
            "full-row Audit, finance, business, fleet or sequence history changed"
        )
    return {
        "preserved_legacy_table_count": len(before["tables"]),
        "full_audit_finance_rows_preserved": True,
        "database_identity_preserved": mode == "post",
        "logical_restore_verified": mode in {"restore", "rollback"},
    }


def environment(values: object) -> dict[str, str]:
    if not isinstance(values, list) or not all(
        isinstance(value, str) and "=" in value for value in values
    ):
        raise CompatibilityError("runtime environment is malformed")
    result = {}
    for value in values:
        key, content = value.split("=", 1)
        if not re.fullmatch(r"[A-Z_][A-Z0-9_]*", key) or key in result:
            raise CompatibilityError(
                "runtime environment has duplicate or invalid keys"
            )
        result[key] = content
    return result


def verify_runtime_journal(document: dict) -> dict:
    if (
        set(document) != {"schema_version", "containers", "configuration_files"}
        or type(document["schema_version"]) is not int
        or document["schema_version"] != 1
        or not isinstance(document["containers"], list)
    ):
        raise CompatibilityError("runtime journal contract changed")
    found = {}
    for row in document["containers"]:
        try:
            service = row["Config"]["Labels"]["com.docker.compose.service"]
            env = row["Config"]["Env"]
        except (TypeError, KeyError) as exc:
            raise CompatibilityError("runtime identity missing") from exc
        if (
            service not in SERVICES
            or service in found
            or row.get("Image") != PRIOR_IMAGES[service]
        ):
            raise CompatibilityError("exact prior five-image identity changed")
        environment(env)
        command = [
            row["Config"].get("Entrypoint"),
            row["Config"].get("Cmd"),
            row["Config"].get("User"),
        ]
        if (
            sha(json.dumps(command, separators=(",", ":")).encode())
            != PRIOR_COMMAND_SHA256[service]
        ):
            raise CompatibilityError("prior executable/user contract changed")
        if (
            sha(json.dumps(sorted(env), separators=(",", ":")).encode())
            != PRIOR_ENVIRONMENT_SHA256[service]
        ):
            raise CompatibilityError("effective prior runtime environment changed")
        if service == "backend" and (
            row["Config"].get("Entrypoint") != BACKEND_UVICORN_ENTRYPOINT
            or row["Config"].get("Cmd") != []
            or row["Config"].get("User") != "erp"
        ):
            raise CompatibilityError("prior operational backend command changed")
        found[service] = row
    if set(found) != set(SERVICES):
        raise CompatibilityError("all five actual prior services must be journalled")
    files = document["configuration_files"]
    if not isinstance(files, list) or len(files) != 2:
        raise CompatibilityError(
            "actual prior Compose and operational override must both be journalled"
        )
    recorded_paths = {item.get("path") for item in files}
    actual_paths = {
        path
        for row in found.values()
        for path in row["Config"]["Labels"]
        .get("com.docker.compose.project.config_files", "")
        .split(",")
    }
    if recorded_paths != actual_paths or len(recorded_paths) != 2:
        raise CompatibilityError("journal does not bind every actual Compose override")
    if {item.get("sha256") for item in files} != {
        PRIOR_COMPOSE_SHA256,
        PRIOR_OPERATIONAL_OVERRIDE_SHA256,
    } or any(
        item.get("uid") != 0
        or item.get("mode") != 0o600
        or item.get("regular_nonlinked") is not True
        for item in files
    ):
        raise CompatibilityError("actual runtime configuration file changed")
    return found


def rollback_override(document: dict) -> dict:
    found = verify_runtime_journal(document)
    backend_env = environment(found["backend"]["Config"]["Env"])
    # Only this reviewed fixed executable and one inert message setting differ
    # from the immutable54 Compose source. Never execute captured command data.
    return {
        "services": {
            "backend": {
                "entrypoint": BACKEND_UVICORN_ENTRYPOINT,
                "command": [],
                "environment": {
                    "CLIENT_UPDATE_MESSAGE": backend_env[
                        "CLIENT_UPDATE_MESSAGE"
                    ].replace("$", "$$")
                },
            }
        }
    }


def verify_pre_bindings(args) -> None:
    identity = decode(private_file(args.identity))
    expected_fields = {
        "prior_revision",
        "prior_version",
        "prior_database_head",
        "candidate_revision",
        "candidate_version",
        "candidate_database_head",
        "prior_source_archive_sha256",
        "candidate_source_archive_sha256",
        "prior_backend_manifest_sha256",
        "candidate_backend_manifest_sha256",
        "prior_images",
        "candidate_images",
    }
    if set(identity) != expected_fields:
        raise CompatibilityError("release identity contract changed")
    required = {
        "prior_revision": PRIOR_REVISION,
        "prior_version": PRIOR_VERSION,
        "prior_database_head": PRIOR_DATABASE_HEAD,
        "candidate_version": CANDIDATE_VERSION,
        "candidate_database_head": CANDIDATE_DATABASE_HEAD,
        "prior_source_archive_sha256": PRIOR_SOURCE_ARCHIVE_SHA256,
        "prior_backend_manifest_sha256": PRIOR_BACKEND_MANIFEST_SHA256,
        "candidate_backend_manifest_sha256": CANDIDATE_BACKEND_MANIFEST_SHA256,
        "prior_images": PRIOR_IMAGES,
    }
    if any(identity.get(key) != value for key, value in required.items()):
        raise CompatibilityError("unsupported or unfrozen release identity")
    revision = identity["candidate_revision"]
    if (
        not re.fullmatch(r"[a-f0-9]{40}", str(revision))
        or revision == PRIOR_REVISION
        or not SHA256.fullmatch(str(identity["candidate_source_archive_sha256"]))
        or identity["candidate_source_archive_sha256"] == PRIOR_SOURCE_ARCHIVE_SHA256
    ):
        raise CompatibilityError("candidate identity is incomplete")
    images = identity["candidate_images"]
    if (
        not isinstance(images, dict)
        or set(images) != set(SERVICES)
        or any(
            not re.fullmatch(r"sha256:[a-f0-9]{64}", str(value))
            or value == PRIOR_IMAGES[key]
            for key, value in images.items()
        )
    ):
        raise CompatibilityError(
            "five immutable candidate images were not independently attested"
        )
    for source_root, scopes, expected_hash, expected_backend in (
        (
            args.prior_source_root,
            PRIOR_PROTECTED_SCOPES,
            PRIOR_PROTECTED_SOURCE_SHA256,
            PRIOR_BACKEND_MANIFEST_SHA256,
        ),
        (
            args.candidate_source_root,
            PROTECTED_SCOPES,
            CANDIDATE_PROTECTED_SOURCE_SHA256,
            CANDIDATE_BACKEND_MANIFEST_SHA256,
        ),
    ):
        if (
            source_root.is_symlink()
            or not source_root.is_dir()
            or source_root.stat().st_uid != 0
            or stat.S_IMODE(source_root.stat().st_mode) != 0o700
        ):
            raise CompatibilityError("source snapshot must be private and root-owned")
        if inventory_hash(inventory(source_root, scopes)) != expected_hash:
            raise CompatibilityError(
                "protected source changed after independent review"
            )
        backend = inventory(source_root, ("backend",))
        manifest = "".join(
            f"{digest}\t{name.removeprefix('backend/')}\n"
            for name, digest in sorted(backend.items())
        ).encode()
        if sha(manifest) != expected_backend:
            raise CompatibilityError("backend image/source manifest changed")
    if args.prior_source_root.resolve() == args.candidate_source_root.resolve():
        raise CompatibilityError(
            "prior and candidate source snapshots must be distinct"
        )
    prior = private_file(args.prior_env)
    if prior != private_file(args.live_env):
        raise CompatibilityError("live environment changed after rollback capture")
    candidate = private_file(args.candidate_env)
    replacements = {
        b"APP_VERSION": (PRIOR_VERSION.encode(), CANDIDATE_VERSION.encode()),
        b"APP_REVISION": (PRIOR_REVISION.encode(), revision.encode()),
    }
    normalized = candidate
    for key, (old, new) in replacements.items():
        pattern = rb"(?m)^" + key + rb"=" + re.escape(new) + rb"$"
        normalized, count = re.subn(pattern, key + b"=" + old, normalized)
        if count != 1:
            raise CompatibilityError(
                "candidate environment has invalid release identity"
            )
    if normalized != prior:
        raise CompatibilityError(
            "environment bytes changed beyond reviewed release identity"
        )
    runtime = verify_runtime_journal(decode(private_file(args.runtime_journal)))
    image_records = decode(private_file(args.candidate_image_inspect))
    compose = decode(private_file(args.candidate_compose_config))
    if set(image_records) != set(SERVICES):
        raise CompatibilityError("candidate image runtime inventory is incomplete")
    for service in SERVICES:
        image = image_records[service]
        config = compose.get("services", {}).get(service)
        if (
            not isinstance(config, dict)
            or image.get("Id") != images[service]
            or config.get("image") != f"d-company-erp-{service}:{revision}"
        ):
            raise CompatibilityError("candidate Compose/image identity changed")
        labels = image.get("Config", {}).get("Labels", {})
        if (
            labels.get("org.opencontainers.image.revision") != revision
            or labels.get("org.opencontainers.image.version") != CANDIDATE_VERSION
        ):
            raise CompatibilityError(
                "candidate image labels are not the coordinated release"
            )
        effective = environment(image.get("Config", {}).get("Env"))
        overrides = config.get("environment", {})
        if not isinstance(overrides, dict) or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in overrides.items()
        ):
            raise CompatibilityError("candidate effective environment is malformed")
        effective.update(overrides)
        before = environment(runtime[service]["Config"]["Env"])
        if service == "backend":
            if (
                effective.pop("APP_VERSION", None) != CANDIDATE_VERSION
                or effective.pop("APP_REVISION", None) != revision
                or before.pop("APP_VERSION", None) != PRIOR_VERSION
                or before.pop("APP_REVISION", None) != PRIOR_REVISION
            ):
                raise CompatibilityError("effective backend release identity changed")
        if before != effective:
            raise CompatibilityError(
                "effective runtime environment changed beyond reviewed identity"
            )
        executable = [
            config.get("entrypoint", image["Config"].get("Entrypoint")),
            config.get("command", image["Config"].get("Cmd")),
            config.get("user", image["Config"].get("User")),
        ]
        if (
            sha(json.dumps(executable, separators=(",", ":")).encode())
            != PRIOR_COMMAND_SHA256[service]
        ):
            raise CompatibilityError(
                "candidate executable/user drifted from reviewed operational runtime"
            )


def verify_migration_compose(base: dict, pinned: dict, images: dict) -> None:
    from copy import deepcopy

    if set(images) != set(SERVICES) or any(
        not re.fullmatch(r"sha256:[a-f0-9]{64}", str(image))
        for image in images.values()
    ):
        raise CompatibilityError("all five migration image IDs are required")
    expected = deepcopy(base)
    if set(expected.get("services", {})) != set(SERVICES):
        raise CompatibilityError("migration Compose service inventory changed")
    for service, image in images.items():
        expected["services"][service]["image"] = image
    if pinned != expected:
        raise CompatibilityError(
            "migration override changed more than the five attested image IDs"
        )


def verify_running_runtime(
    journal: dict, actual: dict, identity: dict, *, prior: bool = False
) -> dict:
    before = verify_runtime_journal(journal)
    if set(actual) != set(SERVICES):
        raise CompatibilityError(
            "all five actual runtime records are required before ingress"
        )
    revision = identity.get("candidate_revision")
    if not prior and not re.fullmatch(r"[a-f0-9]{40}", str(revision)):
        raise CompatibilityError("candidate runtime revision is invalid")
    images = PRIOR_IMAGES if prior else identity.get("candidate_images", {})
    if set(images) != set(SERVICES):
        raise CompatibilityError("runtime image attestation is incomplete")
    for service in SERVICES:
        row = actual[service]
        config = row.get("Config", {}) if isinstance(row, dict) else {}
        if row.get("Image") != images[service]:
            raise CompatibilityError(
                "running image does not match its immutable attestation"
            )
        command = [config.get("Entrypoint"), config.get("Cmd"), config.get("User")]
        if (
            sha(json.dumps(command, separators=(",", ":")).encode())
            != PRIOR_COMMAND_SHA256[service]
        ):
            raise CompatibilityError("actual running executable/user changed")
        expected = environment(before[service]["Config"]["Env"])
        if not prior and service == "backend":
            expected.update(APP_VERSION=CANDIDATE_VERSION, APP_REVISION=revision)
        if environment(config.get("Env")) != expected:
            raise CompatibilityError("actual effective runtime environment changed")
    return {
        "status": "prior_runtime_verified" if prior else "candidate_runtime_verified",
        "service_count": len(SERVICES),
        "effective_environment_verified": True,
        "executable_user_verified": True,
        "tokens_recorded": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="phase", required=True)
    for mode in ("pre", "post", "rollback", "restore"):
        command = commands.add_parser(mode)
        command.add_argument("--pre-transition-state", type=Path, required=True)
        command.add_argument("--pre-fleet-state", type=Path, required=True)
        if mode == "pre":
            command.add_argument("--runtime-journal", type=Path, required=True)
            command.add_argument("--prior-source-root", type=Path, required=True)
            command.add_argument("--candidate-source-root", type=Path, required=True)
            command.add_argument("--identity", type=Path, required=True)
            command.add_argument("--prior-env", type=Path, required=True)
            command.add_argument("--candidate-env", type=Path, required=True)
            command.add_argument("--live-env", type=Path, required=True)
            command.add_argument("--candidate-image-inspect", type=Path, required=True)
            command.add_argument("--candidate-compose-config", type=Path, required=True)
        else:
            command.add_argument("--after-transition-state", type=Path, required=True)
            command.add_argument("--after-fleet-state", type=Path, required=True)
            command.add_argument("--writers-started", action="store_true")
            command.add_argument("--pre-transition-sha256", required=True)
            command.add_argument("--pre-fleet-sha256", required=True)
    runtime = commands.add_parser("runtime")
    runtime.add_argument("--runtime-journal", type=Path, required=True)
    migration = commands.add_parser("migration-runtime")
    migration.add_argument("--base-compose", type=Path, required=True)
    migration.add_argument("--pinned-compose", type=Path, required=True)
    migration.add_argument("--identity", type=Path, required=True)
    running = commands.add_parser("running-runtime")
    running.add_argument("--runtime-journal", type=Path, required=True)
    running.add_argument("--actual-runtime", type=Path, required=True)
    running.add_argument("--identity", type=Path, required=True)
    running.add_argument("--prior", action="store_true")
    args = parser.parse_args()
    try:
        if args.phase == "migration-runtime":
            identity = decode(private_file(args.identity))
            verify_migration_compose(
                decode(private_file(args.base_compose)),
                decode(private_file(args.pinned_compose)),
                identity.get("candidate_images", {}),
            )
            result = {"status": "five_migration_images_pinned"}
        elif args.phase == "running-runtime":
            result = verify_running_runtime(
                decode(private_file(args.runtime_journal)),
                decode(private_file(args.actual_runtime)),
                decode(private_file(args.identity)),
                prior=args.prior,
            )
        elif args.phase == "runtime":
            result = rollback_override(decode(private_file(args.runtime_journal)))
        else:
            if not CANDIDATE_RELEASE_FROZEN:
                raise CompatibilityError(
                    "Build55 independent source/schema freeze is incomplete"
                )
            before_raw = private_file(args.pre_transition_state)
            fleet_raw = private_file(args.pre_fleet_state)
            if args.phase != "pre" and (
                not SHA256.fullmatch(args.pre_transition_sha256)
                or not SHA256.fullmatch(args.pre_fleet_sha256)
                or sha(before_raw) != args.pre_transition_sha256
                or sha(fleet_raw) != args.pre_fleet_sha256
            ):
                raise CompatibilityError("sealed pre-migration baseline changed")
            before = decode(before_raw)
            fleet = decode(fleet_raw)
            verify_fleet(fleet, PRIOR_DATABASE_HEAD)
            # Never let post/restore/rollback bypass the production predecessor
            # pin merely because the generic pair comparator accepts fresh QA.
            verify_prior(before)
            if args.phase == "pre":
                verify_runtime_journal(decode(private_file(args.runtime_journal)))
                verify_pre_bindings(args)
                result = {
                    "status": "pre_migration_verified",
                    "pending_outbox_count": fleet["pending_outbox_count"],
                    "pre_transition_sha256": sha(before_raw),
                    "pre_fleet_sha256": sha(fleet_raw),
                }
            else:
                after = decode(private_file(args.after_transition_state))
                after_fleet = decode(private_file(args.after_fleet_state))
                expected_head = (
                    CANDIDATE_DATABASE_HEAD
                    if args.phase == "post"
                    else PRIOR_DATABASE_HEAD
                )
                verify_fleet(after_fleet, expected_head)
                expected_fleet = {**fleet, "database_revision": expected_head}
                if after_fleet != expected_fleet:
                    raise CompatibilityError(
                        "opaque fleet changed; no queue may be silently discarded"
                    )
                result = verify_pair(
                    before, after, mode=args.phase, writers_started=args.writers_started
                )
            result.update(
                {
                    "schema_version": 1,
                    "phase": args.phase,
                    "queue_drained": False,
                    "tablet_queue_contents_verified": False,
                    "telemetry_rewritten": False,
                    "historic_pending_work_classification": "unknown_preserved_work",
                }
            )
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        return 0
    except (CompatibilityError, OSError, ValueError) as exc:
        print(f"Build55 transition refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
