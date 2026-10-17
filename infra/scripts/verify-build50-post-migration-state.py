#!/usr/bin/env python3
"""Verify build 50's carried pending state after migration 0089.

The pre-migration compatibility proof remains bound to the immutable 0088
database. This verifier runs after the candidate migration and proves that the
same opaque fleet and historical cleanup receipt survived while the one
reviewed client-installation guard changed to its exact 0089 definition.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import types


class PostMigrationError(ValueError):
    pass


EXPECTED_DATABASE_HEAD = "0089"
BUILD49_HELPER = "infra/scripts/verify-build49-pending-compatibility.py"
BUILD49_HELPER_SHA256 = (
    "ba2a1b8158efa667977910fd259b08a55fad0f950ac69ddf99f46c47a5b61f1c"
)
HISTORICAL_VERIFIER = "infra/scripts/verify-code30-2-post-cleanup-state.py"
HISTORICAL_VERIFIER_SHA256 = (
    "082505999c3967f2a2a39b2fcfb333d2ddc4fbf935f5d863cdace9a032a43943"
)
EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256 = (
    "e645c4c96223efca2fc522a55ea0abb16d51e0d87379d41e593e1b439702a46a"
)
EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256 = (
    "495f0fce983228b68d3a5141f844c35c5ed1b0438dc26ff041737aba3b046f8b"
)


def _load_exact_module(
    root: Path, relative_path: str, expected_sha256: str, module_name: str
) -> types.ModuleType:
    path = root / relative_path
    if path.is_symlink() or not path.is_file():
        raise PostMigrationError(f"{relative_path} is missing, linked, or non-regular")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha256:
        raise PostMigrationError(f"{relative_path} changed")
    module = types.ModuleType(module_name)
    module.__file__ = str(path)
    exec(compile(raw, str(path), "exec"), module.__dict__)
    return module


def _load_verifiers(root: Path) -> tuple[types.ModuleType, types.ModuleType, types.ModuleType]:
    build49 = _load_exact_module(
        root,
        BUILD49_HELPER,
        BUILD49_HELPER_SHA256,
        "immutable_build49_pending_compatibility",
    )
    historical = build49._load_historical(root)
    historical.PRIOR_DATABASE_HEAD = EXPECTED_DATABASE_HEAD

    cleanup = _load_exact_module(
        root,
        HISTORICAL_VERIFIER,
        HISTORICAL_VERIFIER_SHA256,
        "immutable_code30_2_post_cleanup_state",
    )
    # Migration 0089 changes only these two fingerprints. Every receipt,
    # replay, audit, device-key and trigger expectation stays historical.
    cleanup.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256 = (
        EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256
    )
    cleanup.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256 = (
        EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256
    )
    return build49, historical, cleanup


def _verify_parsed(
    historical_state: object,
    fleet_state: object,
    *,
    build49: types.ModuleType,
    historical: types.ModuleType,
    cleanup: types.ModuleType,
) -> tuple[dict, dict]:
    if (
        not isinstance(historical_state, dict)
        or historical_state.get("database_revision") != EXPECTED_DATABASE_HEAD
    ):
        raise PostMigrationError("historical state is not exact migration 0089")
    if (
        not isinstance(fleet_state, dict)
        or fleet_state.get("database_revision") != EXPECTED_DATABASE_HEAD
    ):
        raise PostMigrationError("fleet state is not exact migration 0089")
    history = historical._verify_scoped_history(historical_state, cleanup)
    fleet = historical._verify_fleet(fleet_state, cleanup)
    return history, build49._classify_opaque_fleet(fleet)


def verify_raw_state(root: Path, historical_raw: bytes, fleet_raw: bytes) -> dict:
    build49, historical, cleanup = _load_verifiers(root)
    historical_state = historical._decode_json(
        historical_raw, cleanup, label="historical state"
    )
    fleet_state = historical._decode_json(fleet_raw, cleanup, label="fleet state")
    history, fleet = _verify_parsed(
        historical_state,
        fleet_state,
        build49=build49,
        historical=historical,
        cleanup=cleanup,
    )
    return {
        "schema_version": 1,
        "status": "post_migration_pending_carried_unresolved_verified",
        "database_revision": EXPECTED_DATABASE_HEAD,
        "historical_cleanup_proof": history,
        "fleet": fleet,
        "raw_historical_state_sha256": hashlib.sha256(historical_raw).hexdigest(),
        "raw_fleet_state_sha256": hashlib.sha256(fleet_raw).hexdigest(),
        "queue_drained": False,
        "pending_not_drained": True,
        "tablet_queue_contents_verified": False,
        "telemetry_rewritten": False,
        "installation_retired": False,
        "historic_pending_work_classification": "unknown_preserved_work",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-historical-state", type=Path, required=True)
    parser.add_argument("--raw-fleet-state", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    try:
        build49, _, _ = _load_verifiers(root)
        build49._private_file(args.raw_historical_state)
        build49._private_file(args.raw_fleet_state)
        report = verify_raw_state(
            root,
            args.raw_historical_state.read_bytes(),
            args.raw_fleet_state.read_bytes(),
        )
    except (OSError, ValueError) as exc:
        print(f"build50 post-migration verification refused: {exc}", file=sys.stderr)
        return 1
    sys.stdout.write(json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
