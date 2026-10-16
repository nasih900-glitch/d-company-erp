#!/usr/bin/env python3
"""Verify the exact build-48 -> build-49 unresolved-pending server transition.

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


PRIOR_REVISION = "4058fb29e70789a9bb3fa62b2d580330b25db8a3"
PRIOR_VERSION = "3.1.40"
PRIOR_DATABASE_HEAD = "0088"
CANDIDATE_VERSION = "3.1.41"
CANDIDATE_DATABASE_HEAD = "0088"
PRIOR_SOURCE_ARCHIVE_SHA256 = "e37df62bc72ccb90b61f8a08a42e1115123a4c81d4ac6913cf8e25ea5e2af56c"
PRIOR_BACKEND_MANIFEST_SHA256 = "866d75b03a6aaa170081ef5a34c4785d624e72a3fec85c80657a421f8ef9d2da"
CANDIDATE_BACKEND_MANIFEST_SHA256 = "ee4bba277ad5c51eb73b7be414628a093388bdc2daa16d0d87f5ef7d149183af"
PRIOR_SOURCE_SHA256 = "26db0a72954b2e668822baba97c241df2cb16fdfb28709642d24c8c8407af5b1"
CANDIDATE_SOURCE_SHA256 = "f70f9653d766586fdf81e385dc06f1ea06d1c754455f634cc2057385804a4847"

HISTORICAL_HELPER = "infra/scripts/verify-build47-pending-compatibility.py"
HISTORICAL_HELPER_SHA256 = "bdadff290dccda6fb6c2fbdbc0a6a4ce47746f27650e72f859bf8dd5d40a9e1f"
IMMUTABLE_BUILD48_HELPER = "infra/scripts/verify-build48-pending-compatibility.py"
IMMUTABLE_BUILD48_HELPER_SHA256 = "54aac713f88da7084e243004570357bbb96785fc1d5e29cf50d79d38308f6e0f"
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
REVIEWED_CHANGES: dict[str, tuple[str | None, str | None]] = {
    '.env.production.example': ('a88add6adeaf382d82a4d37fb5dfefaa9261a1d1b9b87c6cf5546bb441464136', '2413bffac46a6df2fb8773f62354f28151620fd44a4152c99423f63beac563cd'),
    'android-native/app/build.gradle.kts': ('d14c842fc2cc2f3af504aeaac5a70c6a8ab88c71a5da9256656670948648265f', '62e88f925f7e00c66a92b595414d9de821d9780a2e04ad92dc88473123296024'),
    'android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/DiagnosticNormalization.kt': ('ac9808e9e0131287d8256a9de12844148346761ce838a9ddf6c86eaf943f19bf', '199012608f7a0980d0ea7d038924fff556a9087d147f084e8facbfc43e795ef1'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt': ('8d5468282a654dbf6269f1a07d822c09f2c60018b3f16a546978fef2a2e55e75', 'b127227423a446060e5106c05c9fd015284be46793e58d50e98d41ee1afc05dd'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/customers/CustomersScreen.kt': ('0890ef7e9d0abd810a21ae4b3935d72c395c89de0edc8030c7c05c9e32042a5d', '9b3e334b98f7bb99f787e57605cd1bffca849bf275450cb55731ecbcff06b3fa'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt': ('a904b8c306fb1de63c17c8ced074def7d243f29cb25b710937046b0ab4e9659d', '9cb09602f71c1bb6a0101f61eadcd237591a2704ea4194977e11778db84513c9'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingDurationDial.kt': ('97e41a79366e41bd2b33d2e50cdbf548a7e7ce479fdad082ec2e313cf51d587e', 'a8022b298f88515101fd2ec776ce08e89bfab8ef174269612ab30a193e79c99c'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt': ('d506db0a9e5da4a4545f12ee343196be32f2af282c8da55cd491ca6b2d2e6430', 'e31d273eae8f9740318644319c736e1e56e84b73e155daa5e0af6ca49355aa9e'),
    'android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt': ('f826ea2fecb696974a9cb872981d44d91a53e2c16f018cea0ef3dc74752555db', '63eff091800e3327d4d991af2b4da27e0cce5953f1d3d9b99c816b8966311526'),
    'backend/app/__init__.py': ('2efdc80135802c98c4e719d64998763f936c3dda20ec3a1b1fe306a3f5099f5f', '5119c15a2366648ebac4b8fd4bcf6d972a263e451388bf218285691908f96e81'),
    'backend/pyproject.toml': ('7df5af14d95342b97f41de1c817e9c6389c33db8612f31c169118860d1444f69', '8d6eb01615a266f3d5bc09285381926836e0eeb096b2e389cc7fd3c38f59b3f9'),
    'backend/tests/unit/test_release_contracts.py': ('0b34a572ed5fe40e0bc8994f9de04b1ce3144da8160b8fb87c09cc6f5f093b33', '33f75898c646aab8ecf07e15cea2c22cec6f00a4e8919246e01799ee436051c7'),
    'backend/tests/unit/test_remote_assistance_contract.py': ('133f8025f376c0a016b4f6ac3f851f61bd355da37a088214fd6dca3525e0352d', '34ab23ad96ae2fc4ffcc8a3e045fd8593c26ceec7c7fc3e87be5280a27fc622d'),
    'docker-compose.prod.yml': ('8f00c97f3d31a67f886371cce0d82e31f1d010e02a2acf1fe8989fd2e66fa64f', '7dfd9362177d89f1711de847c52c8aa6cda75c0207d4df2e16a878b2c8acb529'),
    'frontend/.env.example': ('eca0c37e41d4e72ac544804d899857f84723d7d2152c1043ba1b7e65d1221045', 'a16192f5d67b9bea58512fbb39a028596107cd6d50e7613ddc78f516e4bd4372'),
    'frontend/package-lock.json': ('122d6fd26b6f076cd6d6cb5bf23fcbb866e2ce01a0112157944583dd5a22059e', '15c389c2600ea13f3f3dc19ee0ca37abe3685334f3054cf0aba526a4a02ed5dc'),
    'frontend/package.json': ('614a93c7e539a98c5e025e6585fb5c33964c4c09917fa024a4ce48ede157a2ca', '4d5e5dfe4b80ba7d79e5d00a6d97af8811c8eced6c31548cecdc1050deaf076a'),
    'frontend/src/components/ui/Modal.test.tsx': (None, '57c2222bbb6837c830b6a5954df8cc11ce3726b0edccc8711192a0e9e2cbcef1'),
    'frontend/src/components/ui/Modal.tsx': ('ef1dc6d246e74ca3415504d653dd58fca6a0a306f990c7fd1143d5096c613b18', 'bf7ceed5316c3efffeab5efa336fe49db0e8147cca947d43731532f806b0bbf2'),
    'frontend/src/lib/erp-api-customer-directory.test.ts': ('aaa1cd1e93ea29bd3d269efda503d4830ae3b182988e1ee5f37bc7f0e0d2b499', '7e66ef97c69c656cbe8f4a43053d55e93d5decac58a06569483811d474db702f'),
    'frontend/src/lib/erp-api.ts': ('24971e7a4c1128df9c5d0ae340948637aa0685c63134f3c1a943fd43ca248f84', 'e88b63995a105591d32db02cb81482b3e0be0b6762914302dfa77eb5dc91f280'),
    'frontend/src/modules/customers/CustomerPlaytimePanel.tsx': ('b74fea77d222da4b61cb519bb57f76eb84dca84a2a97e8218324433768bc3993', '2d77508a4a495a1e251c7cff1949fdbf67552d42504f040849215ffc7283c51e'),
    'frontend/src/modules/customers/CustomersScreen.tsx': ('49274bd292f2a1b470454e61d5db4cf589b4d719d57cb7de3d0c0afde5cfe2af', '357eab579143f42e515cf28820accb79ed9dad57a072e94cb6449d84bc22a2b1'),
    'frontend/src/modules/gaming/GamingAddonControls.test.tsx': ('5480c048aed1cb6b034b5971d14fadd91e8fda977740bf34a48d5fa4c84e0cbe', '1723bc7b092a9bbfde27a64434c90d676892ef8b670b7fc3c7e04891c6a7d72b'),
    'frontend/src/modules/gaming/GamingScreen.tsx': ('2a8de53fcac4a322adfba114adf0fd49dda3409017b244c7388ed380ae5aeb6e', '70f0553c48710e1803d958c99add2b88b8b3627e061a6f824ea8ec9e3e8e7b31'),
    'frontend/src/modules/gaming/GamingSecondPulse.test.tsx': ('a7bdb47e8ac0dd01498ec1da8367b2e8b9e5e2948f0c502d5cc07e8fa710aa17', '46258fc67c8259ace2c5a4394640a97e5dd525f57409f0a1518bce4972351845'),
    'frontend/src/modules/gaming/GamingSecondPulse.tsx': ('3ca42755c1329e5cd479b7ed4e5f156cd3d93a904dafe1476cc8303891dd3ef3', 'a32b3e36d1852e0b2ff1220deff78c87fb317e30f0597aae80d94c53d0f73eaa'),
    'frontend/src/modules/inventory/InventoryScreen.tsx': ('5fadad898de6272e06706861f7b9b5604f6f4bbfae716a8daac0a07ba99f4f4b', 'bf67b18db149bff97eb013c821551db6056781c59eb3b52508178337af52fa39'),
    'frontend/src/modules/pos/LivePOSScreen.tsx': ('ea39cb8d97ad4f4aaafc8968bfda03198f16b797db41845e0dd549059a7732e7', 'ac261d352313c5c230e7af492cf8657c6d936c9e1e2bb24158d76d0c0e8f067d'),
    'frontend/src/modules/pos/OrdersAndShiftsScreen.tsx': ('7f698179ed9137433ab3094d7015fdf8f71c82f165483e96fbaf31e9b358919e', '1f86e5334cfa36dff97ef9e586a5b532c67980ea18008bb912d005f441ba94cf'),
    'frontend/src/modules/pos/receipt-history.test.ts': ('b049446563f71013320668ae31aaf472d17fdce7ae36b964d0eab791c733b3a5', 'aaf84b411ceb61089b03c7e4fd989f04ce3c613dc0be252161b60f628ad6142f'),
    'frontend/src/modules/pos/receipt-history.ts': ('5303af1750dfd7100cc6df906076c0acebb0242e688ab6e478ae74ed40a7d544', 'da7b493ed6f927e4e3032b03b6e9fb6767047993e996c76773cd768fe8e90830'),
    'frontend/src/modules/reports/ReportsScreen.tsx': ('d1e6f9e6afb9c13c005692eefd1d9a75daecbe541de8f7e9ed19b3747765c971', '86ad2c192baa8680df74810caf9bbd54282a78d461d9f0feb15a8bd1d92351f5'),
    'frontend/src/modules/settings/SettingsScreen.tsx': ('bd4e784aa09a03abdba4fe0e272d7b1ced1e3701b16e468ee4f79cf583ae4aa8', '6ad155ec2c5871a39e0d078e5bbf39f431240819a4cf72b7b6d5be72f998b8d9'),
    'frontend/src/styles/index.css': ('0ffa8c2083c0f1b9eba5d800109779029ea3845571c201e5f9d98cb3d25b7638', '39b653435769c46b606676b985ee7cd3b7e29f6bf52a5edd32ea3ab7232c158b'),
    'infra/scripts/install-on-vm.sh': ('d86d1210e213668bd370fcde9607de45f705b40c9c2c3762b4fbee3e1a25260c', '3ee444429f46b5ca9e397dce6b46a07307d2e8192a8662baa1be4c6652494db3'),
}


def _verify_immutable_build48_helper(candidate: Path) -> None:
    path = candidate / IMMUTABLE_BUILD48_HELPER
    raw = path.read_bytes()
    if path.is_symlink() or not path.is_file() or hashlib.sha256(raw).hexdigest() != IMMUTABLE_BUILD48_HELPER_SHA256:
        raise CompatibilityError("immutable build48 verifier changed")


def _load_implementation(candidate: Path) -> types.ModuleType:
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
        raise CompatibilityError("prior source is not immutable build48")
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
        print(f"build49 compatibility refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

