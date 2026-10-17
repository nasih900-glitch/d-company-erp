from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "infra/scripts/verify-build50-post-migration-state.py"
HISTORICAL_VERIFIER = ROOT / "infra/scripts/verify-code30-2-post-cleanup-state.py"
BUILD49_HELPER = ROOT / "infra/scripts/verify-build49-pending-compatibility.py"
HISTORY_FIXTURES = ROOT / "tests/test_code30_2_post_cleanup_installer_guard.py"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def verifier():
    return _load(VERIFIER, "build50_post_migration_state")


@pytest.fixture
def modules(verifier):
    build49, historical, cleanup = verifier._load_verifiers(ROOT)
    fixtures = _load(HISTORY_FIXTURES, "build50_post_migration_history_fixtures")
    return build49, historical, cleanup, fixtures


def _historical_state(cleanup, fixtures, *, revision: str = "0089") -> dict:
    document = fixtures._valid_document(cleanup)
    document["database_revision"] = revision
    document["pending_outbox_count"] = 2
    document["nonzero_installation_count"] = 2
    return document


def _fleet_state(historical, cleanup) -> dict:
    rows = [
        {
            "row_id": "00000000-0000-0000-0000-000000000001",
            "installation_id": "11111111-1111-1111-1111-111111111111",
            "company_id": "22222222-2222-2222-2222-222222222222",
            "terminal_id": None,
            "platform": "android",
            "version_name": "3.1.29",
            "version_code": 37,
            "pending_outbox_count": 1,
            "last_successful_sync_at": "2026-09-20T10:00:00.000000Z",
            "last_seen_at": "2026-10-13T10:00:00.000000Z",
        },
        {
            "row_id": "00000000-0000-0000-0000-000000000002",
            "installation_id": "33333333-3333-3333-3333-333333333333",
            "company_id": "22222222-2222-2222-2222-222222222222",
            "terminal_id": "44444444-4444-4444-4444-444444444444",
            "platform": "android",
            "version_name": "3.1.33",
            "version_code": 41,
            "pending_outbox_count": 1,
            "last_successful_sync_at": "2026-10-10T10:00:00.000000Z",
            "last_seen_at": "2026-10-13T11:00:00.000000Z",
        },
    ]
    expected = {}
    for row in rows:
        digest = hashlib.sha256(historical._row_projection(row).encode()).hexdigest()
        row["identity_sha256"] = digest
        expected[digest] = (
            "unknown_preserved_work",
            row["version_name"],
            row["version_code"],
        )
    fleet_digest = hashlib.sha256(
        "\n".join(row["identity_sha256"] for row in rows).encode()
    ).hexdigest()
    historical.EXPECTED_FLEET_ROWS = expected
    historical.EXPECTED_NONZERO_FLEET_SHA256 = fleet_digest
    return {
        "schema_version": 1,
        "database_revision": "0089",
        "installation_row_count": 12,
        "pending_outbox_count": 2,
        "nonzero_installation_count": 2,
        "all_installations_identity_sha256": (
            historical.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256
        ),
        "nonzero_installations_identity_sha256": fleet_digest,
        "nonzero_installations": rows,
        "client_installation_guard_trigger_count": 1,
        "client_installation_guard_function_sha256": (
            cleanup.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256
        ),
        "client_installation_guard_function_definition_sha256": (
            cleanup.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256
        ),
        "client_installation_guard_trigger_definition_sha256": (
            cleanup.EXPECTED_CLIENT_INSTALLATION_GUARD_TRIGGER_DEFINITION_SHA256
        ),
    }


def test_exact_0089_guard_and_immutable_verifier_bindings(verifier) -> None:
    assert verifier.EXPECTED_DATABASE_HEAD == "0089"
    assert verifier.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256 == (
        "e645c4c96223efca2fc522a55ea0abb16d51e0d87379d41e593e1b439702a46a"
    )
    assert verifier.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256 == (
        "495f0fce983228b68d3a5141f844c35c5ed1b0438dc26ff041737aba3b046f8b"
    )
    assert hashlib.sha256(BUILD49_HELPER.read_bytes()).hexdigest() == (
        verifier.BUILD49_HELPER_SHA256
    )
    assert hashlib.sha256(HISTORICAL_VERIFIER.read_bytes()).hexdigest() == (
        verifier.HISTORICAL_VERIFIER_SHA256
    )
    _, historical, cleanup = verifier._load_verifiers(ROOT)
    assert (
        historical.EXPECTED_INSTALLATION_ROWS,
        historical.EXPECTED_PENDING,
        historical.EXPECTED_NONZERO,
    ) == (12, 2, 2)
    assert historical.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 == (
        "d741c0d407f2b29c687f7b41ec7276a93f19853e99fc279fb65d0f5aec03d030"
    )
    assert historical.EXPECTED_NONZERO_FLEET_SHA256 == (
        "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"
    )
    assert cleanup.EXPECTED_CLIENT_INSTALLATION_GUARD_TRIGGER_DEFINITION_SHA256 == (
        "dcd3cdf560b382ab046934d824c29a3a90af5ba56a86fe56c87e78716e190ae7"
    )


def test_0088_historical_verifier_and_0089_post_verifier_are_version_bound(
    verifier, modules
) -> None:
    build49, historical, cleanup, fixtures = modules
    legacy = _load(HISTORICAL_VERIFIER, "legacy_post_cleanup_verifier")
    old_state = fixtures._valid_document(legacy)
    old_state["database_revision"] = "0088"
    assert legacy.verify_state(old_state) == legacy.EXPECTED_CLEANUP_RECEIPT_ID

    new_history = _historical_state(cleanup, fixtures)
    new_fleet = _fleet_state(historical, cleanup)
    history, fleet = verifier._verify_parsed(
        new_history,
        new_fleet,
        build49=build49,
        historical=historical,
        cleanup=cleanup,
    )
    assert history["historical_cleanup_receipt_id"] == legacy.EXPECTED_CLEANUP_RECEIPT_ID
    assert fleet["installation_row_count"] == 12
    assert {row["classification"] for row in fleet["installations"]} == {
        "unknown_preserved_work"
    }

    legacy_view_of_new = deepcopy(new_history)
    legacy_view_of_new["pending_outbox_count"] = 1
    legacy_view_of_new["nonzero_installation_count"] = 1
    with pytest.raises(legacy.StateError, match="client installation guard changed"):
        legacy.verify_state(legacy_view_of_new)

    old_for_new = _historical_state(cleanup, fixtures)
    old_for_new["client_installation_guard_function_sha256"] = (
        legacy.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256
    )
    old_for_new["client_installation_guard_function_definition_sha256"] = (
        legacy.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256
    )
    with pytest.raises(historical.CompatibilityError, match="cleanup proof failed"):
        verifier._verify_parsed(
            old_for_new,
            new_fleet,
            build49=build49,
            historical=historical,
            cleanup=cleanup,
        )


@pytest.mark.parametrize(
    "mutation",
    ["guard_body", "guard_definition", "history_head", "fleet_head", "fleet_row"],
)
def test_post_migration_verifier_rejects_tampered_state(
    verifier, modules, mutation
) -> None:
    build49, historical, cleanup, fixtures = modules
    history = _historical_state(cleanup, fixtures)
    fleet = _fleet_state(historical, cleanup)
    if mutation == "guard_body":
        history["client_installation_guard_function_sha256"] = "0" * 64
    elif mutation == "guard_definition":
        history["client_installation_guard_function_definition_sha256"] = "0" * 64
    elif mutation == "history_head":
        history["database_revision"] = "0088"
    elif mutation == "fleet_head":
        fleet["database_revision"] = "0088"
    else:
        fleet["nonzero_installations"][0]["pending_outbox_count"] = 2
    with pytest.raises(ValueError):
        verifier._verify_parsed(
            history,
            fleet,
            build49=build49,
            historical=historical,
            cleanup=cleanup,
        )


def test_installer_runs_post_migration_proof_before_public_asset_and_caddy_paths() -> None:
    source = INSTALLER.read_text(encoding="utf-8")
    assert source.count('python3 "$BUILD50_POST_MIGRATION_VERIFIER"') == 1
    assert source.count(
        'if [ "${BUILD50_PENDING_CARRIED_UNRESOLVED:-false}" = true ]'
    ) == 1
    proof = source.index('python3 "$BUILD50_POST_MIGRATION_VERIFIER"')
    parity = source.index('until python3 "$CANDIDATE_PARITY_TOOL" running')
    public_assets = source.index('python3 "$WEB_PUBLIC_ASSET_VERIFIER"', proof)
    caddy = source.index('up -d --no-build --pull never caddy', proof)
    assert parity < proof < public_assets < caddy
    block = source.split(
        'if [ "${BUILD50_PENDING_CARRIED_UNRESOLVED:-false}" = true ]', 1
    )[1].split("\nfi\n", 1)[0]
    assert '< "$POST_CLEANUP_STATE_SQL"' in block
    assert '< "$BUILD45_PENDING_STATE_SQL"' in block
    assert "UPDATE client_installations" not in block
    assert "DELETE FROM" not in block
