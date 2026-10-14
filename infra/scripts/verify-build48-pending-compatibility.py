#!/usr/bin/env python3
"""Verify the exact build-47 -> build-48 unresolved-pending server transition.

The backend and Room/Alembic business contracts stay unchanged. Both historic
pending counters remain opaque and unresolved. This server-only proof neither
inspects tablet queues nor authorizes an app offer or tablet upgrade.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import types


class CompatibilityError(ValueError):
    pass


PRIOR_REVISION = "986584a4e24dc6ab777ab89afe4f6fec374229c6"
PRIOR_VERSION = "3.1.39"
PRIOR_DATABASE_HEAD = "0088"
CANDIDATE_VERSION = "3.1.40"
CANDIDATE_DATABASE_HEAD = "0088"
PRIOR_SOURCE_ARCHIVE_SHA256 = "55272f149cb72fd0ba84bbdff1eab96e58fac279043f2fa72c45b5b0f7adb1a8"
PRIOR_BACKEND_MANIFEST_SHA256 = "a2820d72635a637074d423a5b2817b85cd8a588f4790b9094f9926e1ff8d6665"
CANDIDATE_BACKEND_MANIFEST_SHA256 = "866d75b03a6aaa170081ef5a34c4785d624e72a3fec85c80657a421f8ef9d2da"
PRIOR_SOURCE_SHA256 = "58b4b266a34f61c20def39c0c9d9d655f0bc05b28c790ac409cfb54d02916882"
CANDIDATE_SOURCE_SHA256 = "854736d1e40f8a36dbda4f7026fdaf3a12e59a4f7bc154c2420d850184cf31b6"

HISTORICAL_HELPER = "infra/scripts/verify-build47-pending-compatibility.py"
HISTORICAL_HELPER_SHA256 = "bdadff290dccda6fb6c2fbdbc0a6a4ce47746f27650e72f859bf8dd5d40a9e1f"
MAX_DOCUMENT_BYTES = 1024 * 1024
EXPECTED_INSTALLATION_ROWS = 11
EXPECTED_PENDING = 2
EXPECTED_NONZERO = 2
EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = "c7a3eea897b48e4fe8adb0d363661481a7f0cb32b2350e63539e5a0c0051d525"
EXPECTED_NONZERO_FLEET_SHA256 = "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"

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
    "infra/scripts/verify-build46-pending-compatibility.py",
    HISTORICAL_HELPER,
    "infra/scripts/verify-build45-pending-state.sql",
    "ops/runtime_release_parity.py",
    "ops/verify_web_public_assets.py",
    "infra/scripts/verify-production-runtime-images.sh",
    "frontend/src",
    "frontend/public",
    "frontend/scripts",
    "frontend/.env.example",
    "frontend/package.json",
    "frontend/package-lock.json",
    "frontend/vite.config.ts",
)

# Pin the published verifier and reuse its parsing, environment, filesystem,
# source inventory, and legacy-contract checks. Only this release's exact
# identities and reviewed source delta change; no historical file is edited.
REVIEWED_CHANGES: dict[str, tuple[str | None, str | None]] = {
    '.env.production.example': ('9929b2add6a0b4f812fc0ecb8b3ffee15678de011744f465a0eb05177838f1ef', 'a88add6adeaf382d82a4d37fb5dfefaa9261a1d1b9b87c6cf5546bb441464136'),
    'android-native/app/build.gradle.kts': ('e552ec3c7aa546aa5d8c0586762df8da7089c1eef718d82641d02b38da1364a1', 'd14c842fc2cc2f3af504aeaac5a70c6a8ab88c71a5da9256656670948648265f'),
    'backend/app/__init__.py': ('0179a24d9ffac910922004640232557a581a1c2cc5267129de4c190cb9a12638', '2efdc80135802c98c4e719d64998763f936c3dda20ec3a1b1fe306a3f5099f5f'),
    'backend/pyproject.toml': ('7353a5886bfebdee842b1456c158eee64289d544e90c89d15904380a2dd1b070', '7df5af14d95342b97f41de1c817e9c6389c33db8612f31c169118860d1444f69'),
    'backend/tests/unit/test_release_contracts.py': ('06374e9fce50563f73ab59189dbc5174e0d4e8fde8b1268ebba6e422f3cb3944', '0b34a572ed5fe40e0bc8994f9de04b1ce3144da8160b8fb87c09cc6f5f093b33'),
    'backend/tests/unit/test_remote_assistance_contract.py': ('381573d32b0f2d1a16658e27b3eec5e848513cb6b9a1890f6551aaadff112345', '133f8025f376c0a016b4f6ac3f851f61bd355da37a088214fd6dca3525e0352d'),
    'docker-compose.prod.yml': ('3233dbcb271691a88d411e1ee49fd28a8b3de1b1a32578f7b159fbc75f17ead7', '8f00c97f3d31a67f886371cce0d82e31f1d010e02a2acf1fe8989fd2e66fa64f'),
    'frontend/.env.example': ('34d330628f0a57da3e67dfdbf09f2bdd4d27ec0c7990d252c022e2eb5055c64a', 'eca0c37e41d4e72ac544804d899857f84723d7d2152c1043ba1b7e65d1221045'),
    'frontend/package-lock.json': ('1975397a294754b38316bc61576a3bbbe8f11e8550d72ffd931ff1e8ccd8b507', '122d6fd26b6f076cd6d6cb5bf23fcbb866e2ce01a0112157944583dd5a22059e'),
    'frontend/package.json': ('8286f8b3a0e6b49cb6b03a43d41cd885d851e56a2c6b3151e48c405f2fb235a0', '614a93c7e539a98c5e025e6585fb5c33964c4c09917fa024a4ce48ede157a2ca'),
    'frontend/scripts/verify-web-build.mjs': ('24adbf9c20da40a9cc7b2ca8ade262c08eaf985dd6c3cd3feca0d40ac17c5e91', '0d9cef48010a9b375185d5e03f69bc31ba76c080f1866129865f0c2fafee1a2e'),
    'frontend/src/modules/gaming/GamingCustomerDisclosure.test.tsx': (None, 'eed21c581f36bbd7732f8bb98a925c7109ab6900006f48cdfe0c114c51bea96b'),
    'frontend/src/modules/gaming/GamingCustomerPicker.tsx': ('32b532233d01c3eb218f03810cf9e9d3a7a791431ef74e23f2ff78e66e78ef0b', 'c93311cc5f63c564e2c63ebb1eb8e124ec264fd33e8fd182c4adb3a2fd8877f1'),
    'frontend/src/modules/gaming/GamingScreen.tsx': ('215e2eff9edd32c5fd8af8f76277973ed0fc264fd4780a7fdf1a02e0bd26f4d6', '2a8de53fcac4a322adfba114adf0fd49dda3409017b244c7388ed380ae5aeb6e'),
    'frontend/src/modules/gaming/gaming-station-presentation.test.ts': (None, '2b535517896ac59e878550e215869e6c731ec3eff1132155484ab3109f9e9a0e'),
    'frontend/src/modules/gaming/gaming-station-presentation.ts': (None, 'bc4b0ecce3d14bcb7879c9a91f61bad4443f0772c8abbda3083346fbc37351ac'),
    'infra/docker/frontend.Dockerfile': ('2bbabcb5d448b73b8a71a619ca8ab53b3eb5cdc2421725024e916bd04072cebf', 'e7ff195698694d7bd026042d4306cd94c156f52ae22ed97598e88fb3a0efa89a'),
    'infra/nginx/frontend.conf': ('caa763f26a56b9529a21c40892f45422a6efafef123f4cc649629c1860a98bb7', '1e935a53ce1fa472187018781000a9b3c079d5141a273ad376fbb0f02ca9fabb'),
    'infra/scripts/install-on-vm.sh': ('0fac1424b01246881ce1ba9c0c1feb88d2afeed0537907ef75f12c1044cd9b60', 'd86d1210e213668bd370fcde9607de45f705b40c9c2c3762b4fbee3e1a25260c'),
    'infra/scripts/verify-production-runtime-images.sh': ('9602119873f07a0fa0a163eb32b3ad58806281e14dea92d2c9c5ab45a204261c', 'af3496868680e15fb52ec012d9314a2693111e500a7cd84b7eae521e64445dde'),
    'ops/verify_web_public_assets.py': (None, 'cd5f2d5978f438b54d019075535ac670cf4d34ad9c94d56ee33c0d78d6661b69'),
}


def _load_implementation(candidate: Path) -> types.ModuleType:
    path = candidate / HISTORICAL_HELPER
    raw = path.read_bytes()
    if path.is_symlink() or hashlib.sha256(raw).hexdigest() != HISTORICAL_HELPER_SHA256:
        raise CompatibilityError("historical build47 verifier changed")
    module = types.ModuleType("immutable_build47_pending_compatibility")
    module.__file__ = str(path)
    exec(compile(raw, str(path), "exec"), module.__dict__)
    for name in (
        "PRIOR_REVISION", "PRIOR_VERSION", "PRIOR_DATABASE_HEAD",
        "CANDIDATE_VERSION", "CANDIDATE_DATABASE_HEAD",
        "PRIOR_SOURCE_ARCHIVE_SHA256", "PRIOR_BACKEND_MANIFEST_SHA256",
        "CANDIDATE_BACKEND_MANIFEST_SHA256", "PRIOR_SOURCE_SHA256",
        "CANDIDATE_SOURCE_SHA256", "EXPECTED_INSTALLATION_ROWS",
        "EXPECTED_PENDING", "EXPECTED_NONZERO",
        "EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256",
        "EXPECTED_NONZERO_FLEET_SHA256", "PROTECTED_SCOPES", "REVIEWED_CHANGES",
    ):
        setattr(module, name, globals()[name])
    module.CompatibilityError = CompatibilityError
    # Its HISTORICAL_HELPER remains build46: the immutable chain reaches the
    # original strict private-state and fleet validator without widening it.
    return module


_implementation = _load_implementation(Path(__file__).resolve().parents[2])
_inventory = _implementation._inventory
_inventory_sha256 = _implementation._inventory_sha256
_changes = _implementation._changes
_backend_manifest_sha256 = _implementation._backend_manifest_sha256
_validate_identity = _implementation._validate_identity
_verify_effective_environment = _implementation._verify_effective_environment
_verify_loyalty_compatibility = _implementation._verify_loyalty_compatibility
_verify_guest_compatibility = _implementation._verify_guest_compatibility
_private_file = _implementation._private_file
_private_root = _implementation._private_root


def _load_historical(candidate: Path) -> types.ModuleType:
    return _load_implementation(candidate)._load_historical(candidate)


def _verify_sources(prior: Path, candidate: Path, **overrides) -> dict[str, int | str]:
    scopes = overrides.get("scopes", PROTECTED_SCOPES)
    before = _inventory(prior, scopes)
    after = _inventory(candidate, scopes)
    before_digest = overrides.get("prior_sha256", PRIOR_SOURCE_SHA256)
    after_digest = overrides.get("candidate_sha256", CANDIDATE_SOURCE_SHA256)
    changes = overrides.get("reviewed_changes", REVIEWED_CHANGES)
    if _inventory_sha256(before) != before_digest:
        raise CompatibilityError("prior source is not immutable build47")
    if _changes(before, after) != changes:
        raise CompatibilityError("candidate source has unreviewed runtime bytes or paths")
    if _inventory_sha256(after) != after_digest:
        raise CompatibilityError("candidate protected source digest changed")
    return {
        "prior_protected_source_sha256": before_digest,
        "candidate_protected_source_sha256": after_digest,
        "protected_path_count": len(after),
        "reviewed_changed_path_count": len(changes),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("prior-source-root", "candidate-source-root", "raw-historical-state", "raw-fleet-state", "prior-env", "live-env", "candidate-env", "prior-runtime-env", "candidate-image-env", "candidate-compose-config"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    for name in ("prior-revision", "prior-version", "prior-db-head", "candidate-revision", "candidate-version", "candidate-db-head", "prior-image-id", "candidate-image-id", "prior-backend-manifest-sha256", "candidate-backend-manifest-sha256", "prior-source-archive-sha256", "candidate-source-archive-sha256"):
        parser.add_argument(f"--{name}", required=True)
    args = parser.parse_args()
    try:
        identity = {name.replace("-", "_"): getattr(args, name.replace("-", "_")) for name in ("prior-revision", "prior-version", "prior-db-head", "candidate-revision", "candidate-version", "candidate-db-head", "prior-image-id", "candidate-image-id", "prior-backend-manifest-sha256", "candidate-backend-manifest-sha256", "prior-source-archive-sha256", "candidate-source-archive-sha256")}
        _validate_identity(**identity)
        for root in (args.prior_source_root, args.candidate_source_root):
            _private_root(root)
        if args.prior_source_root == args.candidate_source_root or Path(__file__).resolve().parents[2] != args.candidate_source_root.resolve():
            raise CompatibilityError("source archive binding is invalid")
        historical = _load_historical(args.candidate_source_root)
        source_proof = _verify_sources(args.prior_source_root, args.candidate_source_root)
        if _backend_manifest_sha256(args.prior_source_root) != args.prior_backend_manifest_sha256 or _backend_manifest_sha256(args.candidate_source_root) != args.candidate_backend_manifest_sha256:
            raise CompatibilityError("backend source manifest is not canonical")
        evidence_paths = (args.raw_historical_state, args.raw_fleet_state, args.prior_env, args.live_env, args.candidate_env, args.prior_runtime_env, args.candidate_image_env, args.candidate_compose_config)
        for path in evidence_paths:
            _private_file(path)
        history_raw = args.raw_historical_state.read_bytes()
        fleet_raw = args.raw_fleet_state.read_bytes()
        history = historical._verify_scoped_history(historical._decode_json(history_raw, historical._load_historical_verifier(args.candidate_source_root), label="historical state"), historical._load_historical_verifier(args.candidate_source_root))
        fleet = historical._verify_fleet(historical._decode_json(fleet_raw, historical._load_historical_verifier(args.candidate_source_root), label="fleet state"), historical._load_historical_verifier(args.candidate_source_root))
        historical._verify_environment(args.prior_env.read_bytes(), args.live_env.read_bytes(), args.candidate_env.read_bytes(), args.candidate_revision)
        effective_count = _verify_effective_environment(historical, historical._json_document(args.prior_runtime_env.read_bytes()), historical._json_document(args.candidate_image_env.read_bytes()), historical._json_document(args.candidate_compose_config.read_bytes()), args.prior_image_id, args.candidate_image_id, args.candidate_revision)
        loyalty = _verify_loyalty_compatibility(args.candidate_source_root)
        guests = _verify_guest_compatibility(args.candidate_source_root)
        report = {
            "schema_version": 1,
            "status": "pending_carried_unresolved",
            "transition": identity,
            "source_proof": source_proof,
            "historical_cleanup_proof": history,
            "fleet": fleet,
            "loyalty_compatibility": loyalty,
            "anonymous_participant_compatibility": guests,
            "raw_historical_state_sha256": hashlib.sha256(history_raw).hexdigest(),
            "raw_fleet_state_sha256": hashlib.sha256(fleet_raw).hexdigest(),
            "effective_environment_key_count": effective_count,
            "queue_drained": False,
            "pending_not_drained": True,
            "tablet_queue_contents_verified": False,
            "telemetry_rewritten": False,
            "installation_retired": False,
            "build41_work_classification": "unknown_preserved_work",
            "server_contract_compatibility_only": True,
        }
        sys.stdout.write(json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n")
        return 0
    except (CompatibilityError, OSError, ValueError) as exc:
        print(f"build48 compatibility refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
