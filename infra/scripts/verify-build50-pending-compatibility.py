#!/usr/bin/env python3
"""Verify the exact build-49 -> build-50 unresolved-pending server transition.

The Room business contracts stay unchanged. Both historic
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


PRIOR_REVISION = "a834b63d32c45513ab5c8fd9d4de417acc0a96c6"
PRIOR_VERSION = "3.1.41"
PRIOR_DATABASE_HEAD = "0088"
CANDIDATE_VERSION = "3.1.42"
CANDIDATE_DATABASE_HEAD = "0089"
PRIOR_SOURCE_ARCHIVE_SHA256 = "a3cc663d594e2d5a40b4d03fd6265623c5e7ac12676f5f34ce3b298fc6caab53"
PRIOR_BACKEND_MANIFEST_SHA256 = "ee4bba277ad5c51eb73b7be414628a093388bdc2daa16d0d87f5ef7d149183af"
CANDIDATE_BACKEND_MANIFEST_SHA256 = "e4d91ec6f91296553b225d3a56f10e95aa277da8eebe2614eb7f18cf396d7373"
PRIOR_SOURCE_SHA256 = "11f178acff0b78e65441ea8017e960efd7897639a5c4684330e3f1c828137e1e"
CANDIDATE_SOURCE_SHA256 = "1b6a21cbefafc480b22bd7d03db4de38f86ca3788dabff266bfd5076da316e43"

HISTORICAL_HELPER = "infra/scripts/verify-build47-pending-compatibility.py"
HISTORICAL_HELPER_SHA256 = "bdadff290dccda6fb6c2fbdbc0a6a4ce47746f27650e72f859bf8dd5d40a9e1f"
IMMUTABLE_BUILD48_HELPER = "infra/scripts/verify-build48-pending-compatibility.py"
IMMUTABLE_BUILD48_HELPER_SHA256 = "54aac713f88da7084e243004570357bbb96785fc1d5e29cf50d79d38308f6e0f"
IMMUTABLE_BUILD49_HELPER = "infra/scripts/verify-build49-pending-compatibility.py"
IMMUTABLE_BUILD49_HELPER_SHA256 = "ba2a1b8158efa667977910fd259b08a55fad0f950ac69ddf99f46c47a5b61f1c"
MAX_DOCUMENT_BYTES = 1024 * 1024
EXPECTED_INSTALLATION_ROWS = 12
EXPECTED_PENDING = 2
EXPECTED_NONZERO = 2
EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 = "d741c0d407f2b29c687f7b41ec7276a93f19853e99fc279fb65d0f5aec03d030"
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
    IMMUTABLE_BUILD48_HELPER,
    IMMUTABLE_BUILD49_HELPER,
    "infra/scripts/verify-build45-pending-state.sql",
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

# Pin the published verifier and reuse its parsing, environment, filesystem,
# source inventory, and legacy-contract checks. Only this release's exact
# identities and reviewed source delta change; no historical file is edited.
REVIEWED_CHANGES: dict[str, tuple[str | None, str | None]] = {'.env.production.example': ('2413bffac46a6df2fb8773f62354f28151620fd44a4152c99423f63beac563cd',
                             'bb3067963b7b927a67f90a26f5d69ff5a4d704e907c2bb953482206ab1660084'),
 'android-native/app/build.gradle.kts': ('62e88f925f7e00c66a92b595414d9de821d9780a2e04ad92dc88473123296024',
                                         'bf7f779d78ddde6c7c5425e86c902d043289e50a2d439e4273fe50e0b9e70304'),
 'android-native/app/gradle.lockfile': ('be2f5b3b2203502b7dddc26aff7d39521fc6f33244463065935a255afd62982f',
                                        '2250fddebcb8a0e4ab45b8dd08a927fdb2f43f0002eb267459d00d9cefdad546'),
 'android-native/app/src/main/java/cloud/dcompany/erp/MainActivity.kt': ('3ed37c673c87972dff855ea79bbc8d32ed14c41e16cb8386a5ee0165bc1ecb0c',
                                                                         '7e9ade895857bbda1af55045df2cf2f654affdfbc97d4bad510cbbdbd4736b20'),
 'android-native/app/src/main/java/cloud/dcompany/erp/core/sync/RealtimeClient.kt': ('58de36594a6527cb6d05f828de6058610c79a71f2415df8d37a7185e837adaae',
                                                                                     'e07dde267b1715810478205ca2c1f843a60400452a47cf4e67b78d2b2146691e'),
 'android-native/app/src/main/java/cloud/dcompany/erp/core/update/AppUpdate.kt': ('b4b9d3e671ec8a5ff1921d956f1b9de376831df7eceb1c1748c66d25b04fba44',
                                                                                  'a9e67fb0a22000dff10bc6cb97ada0c2505e6da3c5d61ea2902c83e92b3235b0'),
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PasswordRecoveryDialog.kt': ('910edf156503778bd74323342fa8fb9efca1d6fec2c25e63e774158c2d9da069',
                                                                                              '39e7e36e4280f9ea2b6f04d1b70c738c9ba7154c94e592fbdf091a33967b83b3'),
 'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': ('e31d273eae8f9740318644319c736e1e56e84b73e155daa5e0af6ca49355aa9e',
                                                                                           'bd4d8ca2867378c1730df5a79d3cc1621552a35dff358d14bd2ee2c787e142a3'),
 'android-native/gradle/verification-metadata.xml': ('f2b7345d8111215c0240b317f60ff5167982cb39c48dfa1ea55b5707b25547c5',
                                                     'cb8ed35219d49d241b25ae103f0f32587057a7a82f1ffb072c151da0c4af57b7'),
 'backend/alembic/env.py': ('dc4260e617c79944631516a72e5569c0a4c124ede4c6373c648aa3560bc681c1',
                            '2057d252d99bbcc3563ec3278eaf8a80d4ade7feba514e00ba325b755813e822'),
 'backend/alembic/versions/0089_expand_client_installation_user_capacity.py': (None,
                                                                               '1a30f663f4efc2d9287183318051be9227147793341335cc9806961e90fd7cb5'),
 'backend/app/__init__.py': ('5119c15a2366648ebac4b8fd4bcf6d972a263e451388bf218285691908f96e81',
                             'ebb4297c434e9c80fe8200cb069d6a93dfc41e11ffde0dededa812ef77615cf4'),
 'backend/app/models/client_update.py': ('204615b947f1d97faf1f172288b8844acb14ede5c50e8909e41d01580a2c2c5d',
                                         '127d46013a5054d4904017a4edc07634171eb603037f0634f93aa5c296964f15'),
 'backend/pyproject.toml': ('8d6eb01615a266f3d5bc09285381926836e0eeb096b2e389cc7fd3c38f59b3f9',
                            'eac4e43769ce61a5e3897f424a8585e3a9542bd77e071e0ac86e2a791a3f6d76'),
 'backend/tests/integration/test_client_installation_capacity_migration.py': (None,
                                                                              'd4d0badd299864caa0aeef6b80ad16c723c3e6e784d16c8940c2db238018afd1'),
 'backend/tests/unit/test_alembic_env.py': (None,
                                            'f8f95981ea5984da9027f0e05956018b9473d24c6efb478aafc366ef419be271'),
 'backend/tests/unit/test_release_contracts.py': ('33f75898c646aab8ecf07e15cea2c22cec6f00a4e8919246e01799ee436051c7',
                                                  '2978cfad70a5a6e9b4d1c511ed0044ed07a36bf09d3fd317b153930b716a6258'),
 'backend/tests/unit/test_remote_assistance_contract.py': ('34ab23ad96ae2fc4ffcc8a3e045fd8593c26ceec7c7fc3e87be5280a27fc622d',
                                                           '880a143e80bbcc4f1e382f0b3e1fdd1632f4acea677ac702fdaae95c8cdc75fe'),
 'docker-compose.prod.yml': ('7dfd9362177d89f1711de847c52c8aa6cda75c0207d4df2e16a878b2c8acb529',
                             '71431f5ceb9831fce34e216381da17b025792f91758bcbcad73491890fb1cfc1'),
 'frontend/.env.example': ('a16192f5d67b9bea58512fbb39a028596107cd6d50e7613ddc78f516e4bd4372',
                           '84f13d803b5c46ca1795d9e8aad62e6f5e2b7dd1573a54661c2c19c02faf9698'),
 'frontend/package-lock.json': ('15c389c2600ea13f3f3dc19ee0ca37abe3685334f3054cf0aba526a4a02ed5dc',
                                '81328db01b7cb462ef5cecc1ca077d3a165a198798333478ecf4da116772ff5c'),
 'frontend/package.json': ('4d5e5dfe4b80ba7d79e5d00a6d97af8811c8eced6c31548cecdc1050deaf076a',
                           '3077691b3a06731ffeacafac239afd374c185b1110fec7062abf6a3064bf1a1f'),
 'frontend/src/modules/auth/Login.tsx': ('179e605d9c4a99661b41b6dc1ee7ea7a9300e72cafa55e074e3fe3d2c1c8a578',
                                         '5b04083b2ed1de635181971af5144758b70f1dbabde3ec1d82ed0162af6d6949'),
 'frontend/src/modules/staff/StaffScreen.tsx': ('74e0eb94f002ead85f810cab374f8a2febb860f2cfcac84a49b2b680c9ee572a',
                                                '10e883b640dd8fc4b256cb34c7ac548603335c84fd520a3c1c643340c0d0daaa'),
 'infra/scripts/install-on-vm.sh': ('3ee444429f46b5ca9e397dce6b46a07307d2e8192a8662baa1be4c6652494db3',
                                    'cb807d06771cfff83ca572e763ac7f6d617db25e79b5bd45129dd863e339c9cc')}


def _verify_immutable_build48_helper(candidate: Path) -> None:
    path = candidate / IMMUTABLE_BUILD48_HELPER
    raw = path.read_bytes()
    if path.is_symlink() or not path.is_file() or hashlib.sha256(raw).hexdigest() != IMMUTABLE_BUILD48_HELPER_SHA256:
        raise CompatibilityError("immutable build48 verifier changed")


def _verify_immutable_build49_helper(candidate: Path) -> None:
    path = candidate / IMMUTABLE_BUILD49_HELPER
    raw = path.read_bytes()
    if path.is_symlink() or hashlib.sha256(raw).hexdigest() != IMMUTABLE_BUILD49_HELPER_SHA256:
        raise CompatibilityError("immutable build49 verifier changed")


def _load_implementation(candidate: Path) -> types.ModuleType:
    _verify_immutable_build49_helper(candidate)
    _verify_immutable_build48_helper(candidate)
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


def _classify_opaque_fleet(fleet: dict) -> dict:
    # The exact counters are retained, but their tablet-local contents were
    # not inspected. Do not inherit an earlier semantic classification.
    for installation in fleet["installations"]:
        installation["classification"] = "unknown_preserved_work"
    return fleet


def _verify_sources(prior: Path, candidate: Path, **overrides) -> dict[str, int | str]:
    scopes = overrides.get("scopes", PROTECTED_SCOPES)
    before = _inventory(prior, scopes)
    after = _inventory(candidate, scopes)
    before_digest = overrides.get("prior_sha256", PRIOR_SOURCE_SHA256)
    after_digest = overrides.get("candidate_sha256", CANDIDATE_SOURCE_SHA256)
    changes = overrides.get("reviewed_changes", REVIEWED_CHANGES)
    if _inventory_sha256(before) != before_digest:
        raise CompatibilityError("prior source is not immutable build49")
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
        fleet = _classify_opaque_fleet(historical._verify_fleet(historical._decode_json(fleet_raw, historical._load_historical_verifier(args.candidate_source_root), label="fleet state"), historical._load_historical_verifier(args.candidate_source_root)))
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
            "historic_pending_work_classification": "unknown_preserved_work",
            "server_contract_compatibility_only": True,
        }
        sys.stdout.write(json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n")
        return 0
    except (CompatibilityError, OSError, ValueError) as exc:
        print(f"build50 compatibility refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
