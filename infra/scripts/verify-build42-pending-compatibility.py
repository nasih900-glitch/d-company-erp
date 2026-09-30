#!/usr/bin/env python3
"""One reviewed build-41 -> build-42 server update; never a queue-drain proof.

Run only from the installer's immutable candidate archive, after prior-image
source attestation and writer stop. The original historical verifier is reused
unchanged on an explicitly scoped copy. Raw telemetry stays unresolved.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import types


class CompatibilityError(ValueError):
    pass


PRIOR_REVISION = "099daa28ea5b9cb3c4b167a66c82302896ad4acc"
PRIOR_VERSION = "3.1.33"
CANDIDATE_VERSION = "3.1.34"
DATABASE_HEAD = "0085"
PRIOR_SOURCE_SHA256 = '3930b746ef2f2ae1375fe989bbeccf3342efd1ba53d243e319318426e67c0736'
CANDIDATE_SOURCE_SHA256 = '8240ce484070fe59f90952cea8315dec106fdfbc01ef74e78cb79b66bbf11b72'
PRIOR_BACKEND_MANIFEST_SHA256 = "bc5f10ff1bc2d7359333b6eb97d42e777c1438e89e318c7b0c18ea5420a3993d"
HISTORICAL_VERIFIER = "infra/scripts/verify-code30-2-post-cleanup-state.py"
MAX_STATE_BYTES = 1024 * 1024
# The entire backend (including shipped tests), build/startup inputs and proxy
# configuration are covered. The Android version file binds this to build 42.
PROTECTED_SCOPES = ('backend',
 'infra/docker',
 'infra/caddy',
 'infra/nginx',
 '.dockerignore',
 'docker-compose.prod.yml',
 'infra/scripts/prepare-production-env.sh',
 'infra/scripts/verify-code30-2-post-cleanup-state.py',
 'infra/scripts/verify-code30-2-post-cleanup-state.sql',
 'ops/runtime_release_parity.py',
 'android-native/app/build.gradle.kts',
 '.env.production.example',
 'infra/scripts/generate-secrets.sh',
 'infra/scripts/validate-production-env.sh')

# Exact reviewed byte pairs, not a filename or semantic-change allowlist.
# Four backend test changes are included because Docker ships the full tree.
# Runtime deltas are the reviewed origin-message helper, version metadata and
# pinned PyJWT 2.13 -> 2.14. The latter also requires separate token-compat proof.
REVIEWED_PAIRS = {'.env.production.example': ('f27985296627c02601b59cdb8af541fa43f5a3f12a75ed781ad095b534200ffd',
                             'e28e66ad6f21f31866de67a9e8ddcc4d5bd15db6c5e7ea1ef80191050792735c'),
 'android-native/app/build.gradle.kts': ('6ae336925ebfbe7321f717dbe2eff8ebeef6c5b476ad5d3af48efa6d5388baad',
                                         'bd67f512b4550707066fde8a3c3ae377d5509c0296c64aef2940acecf330e80b'),
 'backend/app/__init__.py': ('b8289bd6045515e0efa056d7d33d9b5e54b3b97d44735bc895985c6a9f6afd0b',
                             '73b4bb40fc02638a698c129f4468e4da076070a192c671865a0e2daf1d21a609'),
 'backend/app/api/v1/pos/router.py': ('3c66a676615aba73af8fb298969c7501520726ba1478c016dfd69a1eaeb8e8a8',
                                      '834ffb264be9c95167be9f0a034f45f305de69d6596a6da3bf5151c1b15b20ab'),
 'backend/pyproject.toml': ('8fbf7cec9126400ae4750ba7f0275e542490c919e726676ac2863eeaab8af177',
                            '1ba13b735c70e7d834a1128e01d648dc70a87d1880206f96d217d5f3e5690d1d'),
 'backend/requirements-ci.lock': ('f77e16af56632cecac34a6e2011f8570b0fb05bce8caec089272a5a4e57a8fe9',
                                  '4cb457d7f8910a8f4c594c6185c97bc7db59355d44b33d56fddbd510b35fe89d'),
 'backend/requirements.lock': ('cd319d2a4dc1c29497d7470f4cc8136a126e44d516b731873dade51c6b2d9b81',
                               'a797cf23e682ad5af7cd5ce7a280be5eee67ba967cc92f905d8b91a9913f4fe5'),
 'backend/requirements.txt': ('2276d7999c8585a46d85e187594ddb37c6af90ec21caeaaa1975f2270ced53c0',
                              'ca3949448edd2e018141675d38a154a89ecb74f6e80f24e805c57bf35f00476d'),
 'backend/tests/integration/test_captured_shift_opening.py': ('b7929678aa8de871f3640759900ed0cfda62bc0c9ca23b5fabe91a73840fffd3',
                                                              'c7f84593127066b0c88aa230b3a9db56d50161ead83a2e5e5a5d3971c37cb06b'),
 'backend/tests/unit/test_release_contracts.py': ('906704e7e7d886e6dee2413ed2136a2af361c1d18017e5f69dbc4d97bff6796d',
                                                  '7192b7183954c20996826f8d7ae8f53b0329c2a2c9c55cf8e583490dc2370a33'),
 'backend/tests/unit/test_remote_assistance_contract.py': ('b2c6a7e705640deca8b62a8ff76de76be74554abd8cd38095a11f7855d5fe340',
                                                           '3ea070ed11f63cd7bca8401013cf39e605d86c6afa4e5d22c0238c560929c674'),
 'backend/tests/unit/test_shift_recovery_scope.py': ('e3246be8d42b0145369f5c271c4544ed40808969ae36134eaf25517ef4ee2782',
                                                     '0d21960e1996b24055788086b51e37352fc2195df971d06f34ef83c59b537f59'),
 'docker-compose.prod.yml': ('907d7e29a7765117dc8c4c547b52a28e437ad06dd351e71561754e19f2bed299',
                             'c9b473a4ca5ee91b2a7ffea3c459dc55940dfdf818b226418367b17ed9296908')}


def _regular_path(path: Path, *, directory: bool = False) -> None:
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise CompatibilityError("linked source/evidence path")
    mode = path.stat().st_mode
    if not (stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode)):
        raise CompatibilityError("source/evidence is not a regular file or directory")


def _inventory(root: Path, scopes: tuple[str, ...]) -> dict[str, str]:
    _regular_path(root, directory=True)
    files: dict[str, str] = {}
    for scope in scopes:
        path = root / scope
        if path.is_dir():
            _regular_path(path, directory=True)
            for directory, dirs, names in os.walk(path, followlinks=False):
                current = Path(directory)
                if not dirs and not names:
                    raise CompatibilityError("unexpected empty source directory")
                for name in dirs:
                    _regular_path(current / name, directory=True)
                for name in names:
                    item = current / name
                    _regular_path(item)
                    files[item.relative_to(root).as_posix()] = hashlib.sha256(
                        item.read_bytes()
                    ).hexdigest()
        else:
            _regular_path(path)
            files[scope] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files


def _inventory_sha256(files: dict[str, str]) -> str:
    return hashlib.sha256(
        "".join(f"{path}\0{digest}\n" for path, digest in sorted(files.items())).encode()
    ).hexdigest()


def _verify_sources(
    prior: Path,
    candidate: Path,
    *,
    scopes: tuple[str, ...] = PROTECTED_SCOPES,
    prior_sha256: str = PRIOR_SOURCE_SHA256,
    candidate_sha256: str = CANDIDATE_SOURCE_SHA256,
    pairs: dict[str, tuple[str, str]] = REVIEWED_PAIRS,
) -> dict[str, str]:
    # Only internal tests inject a miniature policy; the CLI has no override.
    before = _inventory(prior, scopes)
    after = _inventory(candidate, scopes)
    if _inventory_sha256(before) != prior_sha256:
        raise CompatibilityError("prior source is not the reviewed immutable predecessor")
    if before.keys() != after.keys():
        raise CompatibilityError("candidate source contains missing or additional paths")
    changed = {path: (before[path], after[path]) for path in before if before[path] != after[path]}
    if changed != pairs or _inventory_sha256(after) != candidate_sha256:
        raise CompatibilityError("candidate source has unreviewed runtime bytes")
    return {
        "prior_protected_source_sha256": prior_sha256,
        "candidate_protected_source_sha256": candidate_sha256,
    }


def _validate_identity(
    prior_revision: str, prior_version: str, prior_db_head: str,
    candidate_revision: str, candidate_version: str,
    prior_image_id: str, prior_backend_manifest_sha256: str,
) -> None:
    if (prior_revision, prior_version, prior_db_head, candidate_version) != (
        PRIOR_REVISION, PRIOR_VERSION, DATABASE_HEAD, CANDIDATE_VERSION
    ):
        raise CompatibilityError("unsupported release or database predecessor")
    if not re.fullmatch(r"[0-9a-f]{40}", candidate_revision) or candidate_revision == prior_revision:
        raise CompatibilityError("candidate requires a distinct full source revision")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", prior_image_id):
        raise CompatibilityError("prior image requires an immutable image ID")
    if prior_backend_manifest_sha256 != PRIOR_BACKEND_MANIFEST_SHA256:
        raise CompatibilityError("prior image source attestation does not match build 41")


def _load_historical_verifier(candidate: Path) -> types.ModuleType:
    # Loading verified source directly avoids writing __pycache__ into the archive.
    path = candidate / HISTORICAL_VERIFIER
    module = types.ModuleType("build42_historical_cleanup_verifier")
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


def _verify_scoped_history(document: object, expected_pending: int, historical) -> dict:
    if not isinstance(document, dict) or document.get("database_revision") != DATABASE_HEAD:
        raise CompatibilityError("raw telemetry must be from database 0085")
    pending = document.get("pending_outbox_count")
    installations = document.get("nonzero_installation_count")
    # The unchanged schema bounds each installation counter to 0..1,000,000.
    if (type(pending) is not int or type(installations) is not int
            or type(expected_pending) is not int or pending != expected_pending
            or not 1 <= installations <= pending <= 1 + (installations - 1) * 1_000_000):
        raise CompatibilityError("raw installation aggregates are invalid or inconsistent")
    scoped = deepcopy(document)
    scoped["pending_outbox_count"] = 1
    scoped["nonzero_installation_count"] = 1
    # All identity, retained-row telemetry, receipt, audit, replay-fence and
    # remote-key checks still run in the original verifier. Raw input is untouched.
    try:
        receipt_id = historical.verify_state(scoped)
    except historical.StateError as exc:
        raise CompatibilityError("retained historical cleanup proof failed") from exc
    return {
        "status": "pending_carried_unresolved",
        "raw_pending_outbox_count": pending,
        "raw_nonzero_installation_count": installations,
        "additional_reported_pending_count": pending - 1,
        "additional_nonzero_installation_count": installations - 1,
        "historical_proof_scope": "exact_retained_legacy_installation_only",
        "historical_scoped_pending_outbox_count": 1,
        "historical_scoped_nonzero_installation_count": 1,
        "historical_cleanup_receipt_id": receipt_id,
        "queue_drained": False,
        "telemetry_rewritten": False,
        "tablet_queue_contents_verified": False,
    }


def _decode_state(raw: bytes, historical) -> object:
    if not raw or len(raw) > MAX_STATE_BYTES:
        raise CompatibilityError("raw telemetry size is invalid")
    def invalid_constant(_value):
        raise CompatibilityError("non-finite telemetry value")
    try:
        return json.loads(raw, object_pairs_hook=historical._reject_duplicate_keys,
                          parse_constant=invalid_constant)
    except historical.StateError as exc:
        raise CompatibilityError("duplicate telemetry key") from exc


def _verify_environment(prior: bytes, live: bytes, candidate: bytes, candidate_revision: str) -> None:
    if prior != live:
        raise CompatibilityError("live environment changed after rollback snapshot")
    def without_release_metadata(raw: bytes, version: str, revision: str) -> list[bytes]:
        expected = {b"APP_VERSION": version.encode(), b"APP_REVISION": revision.encode()}
        seen: set[bytes] = set()
        retained = []
        for line in raw.splitlines(keepends=True):
            match = re.match(rb"\s*(?:export\s+)?(APP_VERSION|APP_REVISION)\s*=", line)
            if match:
                key = match[1]
                if key in seen or line.rstrip(b"\r\n") != key + b"=" + expected[key]:
                    raise CompatibilityError("environment release identity is ambiguous or unexpected")
                seen.add(key)
            else:
                retained.append(line)
        if seen != expected.keys():
            raise CompatibilityError("environment release identity is missing")
        return retained
    if without_release_metadata(prior, PRIOR_VERSION, PRIOR_REVISION) != without_release_metadata(
        candidate, CANDIDATE_VERSION, candidate_revision
    ):
        raise CompatibilityError("non-release environment bytes changed; compatibility refused")


def _private_file(path: Path) -> None:
    _regular_path(path)
    if path.stat().st_uid != 0 or stat.S_IMODE(path.stat().st_mode) != 0o600:
        raise CompatibilityError("environment/telemetry evidence must be private and root-owned")


def _json_document(raw: bytes) -> object:
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise CompatibilityError("duplicate runtime configuration key")
            result[key] = value
        return result
    def invalid_constant(_value):
        raise CompatibilityError("non-finite runtime configuration value")
    try:
        return json.loads(raw, object_pairs_hook=unique_pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeDecodeError) as exc:
        # Never include any fragment of these secret-bearing documents in errors.
        raise CompatibilityError("invalid runtime configuration JSON") from exc


def _docker_environment(document: object, expected_image_id: str) -> dict[str, str]:
    if (not isinstance(document, dict) or set(document) != {"image_id", "environment"}
            or document["image_id"] != expected_image_id
            or not isinstance(document["environment"], list)):
        raise CompatibilityError("runtime environment image binding is invalid")
    environment: dict[str, str] = {}
    for item in document["environment"]:
        if not isinstance(item, str) or "=" not in item:
            raise CompatibilityError("invalid Docker environment entry")
        key, value = item.split("=", 1)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) or key in environment:
            raise CompatibilityError("invalid or duplicate Docker environment key")
        environment[key] = value
    return environment


def _verify_effective_environment(
    prior_container: object, candidate_image: object, candidate_compose: object,
    prior_image_id: str, candidate_image_id: str, candidate_revision: str,
) -> int:
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", candidate_image_id):
        raise CompatibilityError("candidate image requires an immutable image ID")
    before = _docker_environment(prior_container, prior_image_id)
    after = _docker_environment(candidate_image, candidate_image_id)
    try:
        backend = candidate_compose["services"]["backend"]
        overrides = backend["environment"]
        if (backend["image"] != f"d-company-erp-backend:{candidate_revision}"
                or "env_file" in backend or not isinstance(overrides, dict)):
            raise CompatibilityError("candidate Compose environment source is unexpected")
    except (TypeError, KeyError) as exc:
        raise CompatibilityError("candidate Compose backend environment is missing") from exc
    for key, value in overrides.items():
        if (not isinstance(key, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key)
                or not isinstance(value, str)):
            raise CompatibilityError("candidate Compose environment must contain string values")
    after.update(overrides)
    for values, version, revision in (
        (before, PRIOR_VERSION, PRIOR_REVISION), (after, CANDIDATE_VERSION, candidate_revision)
    ):
        if values.pop("APP_VERSION", None) != version or values.pop("APP_REVISION", None) != revision:
            raise CompatibilityError("effective runtime release identity does not match")
    if before != after:
        raise CompatibilityError("effective backend environment changed; compatibility refused")
    return len(before) + 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("prior-source-root", "candidate-source-root", "raw-state",
                 "prior-env", "live-env", "candidate-env", "prior-runtime-env",
                 "candidate-image-env", "candidate-compose-config"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    for name in ("prior-revision", "prior-version", "prior-db-head", "candidate-revision",
                 "candidate-version", "prior-image-id", "prior-backend-manifest-sha256",
                 "candidate-image-id"):
        parser.add_argument(f"--{name}", required=True)
    parser.add_argument("--expected-pending", type=int, required=True)
    args = parser.parse_args()
    try:
        _validate_identity(args.prior_revision, args.prior_version, args.prior_db_head,
                           args.candidate_revision, args.candidate_version,
                           args.prior_image_id, args.prior_backend_manifest_sha256)
        # These are the archived installer inputs, never the mutable Git checkout.
        for root in (args.prior_source_root, args.candidate_source_root):
            _regular_path(root, directory=True)
            if root.stat().st_uid != 0 or stat.S_IMODE(root.stat().st_mode) != 0o700:
                raise CompatibilityError("source archive root must be private and root-owned")
        if Path(__file__).absolute().parents[2] != args.candidate_source_root:
            raise CompatibilityError("verifier must run from the original candidate archive")
        if args.prior_source_root == args.candidate_source_root:
            raise CompatibilityError("prior and candidate archives must be distinct")
        source_proof = _verify_sources(args.prior_source_root, args.candidate_source_root)
        for path in (args.raw_state, args.prior_env, args.live_env, args.candidate_env,
                     args.prior_runtime_env, args.candidate_image_env, args.candidate_compose_config):
            _private_file(path)
        _verify_environment(args.prior_env.read_bytes(), args.live_env.read_bytes(),
                            args.candidate_env.read_bytes(), args.candidate_revision)
        effective_env_count = _verify_effective_environment(
            _json_document(args.prior_runtime_env.read_bytes()),
            _json_document(args.candidate_image_env.read_bytes()),
            _json_document(args.candidate_compose_config.read_bytes()),
            args.prior_image_id, args.candidate_image_id, args.candidate_revision,
        )
        with args.raw_state.open("rb") as stream:
            raw = stream.read(MAX_STATE_BYTES + 1)
        historical = _load_historical_verifier(args.candidate_source_root)
        report = _verify_scoped_history(_decode_state(raw, historical), args.expected_pending, historical)
        report.update(source_proof)
        report.update({
            "schema_version": 1,
            "prior_revision": args.prior_revision,
            "candidate_revision": args.candidate_revision,
            "prior_image_id": args.prior_image_id,
            "candidate_image_id": args.candidate_image_id,
            "prior_backend_manifest_sha256": args.prior_backend_manifest_sha256,
            "database_revision": DATABASE_HEAD,
            "raw_state_sha256": hashlib.sha256(raw).hexdigest(),
            "raw_state_file": args.raw_state.name,
            "non_release_environment_preserved": True,
            "effective_backend_environment_preserved": True,
            "effective_backend_environment_key_count": effective_env_count,
            "release_acceptance_proven": False,
        })
    except (CompatibilityError, OSError, ValueError, UnicodeDecodeError) as exc:
        print(f"Build42 pending compatibility refused: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
