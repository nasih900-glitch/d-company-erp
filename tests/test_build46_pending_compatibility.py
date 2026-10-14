from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build46-pending-compatibility.py"
OLD_HELPER = ROOT / "infra/scripts/verify-build45-pending-compatibility.py"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"
IMMUTABLE_BUILD46_REVISION = "b743d7715d22c5ce92f79edc9a371df28930a6a1"


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def helper():
    return _load(HELPER, "build46_pending_compatibility")


def _extract(ref: str, destination: Path) -> bytes:
    raw = subprocess.check_output(["git", "archive", "--format=tar", ref], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            assert not path.is_absolute() and ".." not in path.parts
        archive.extractall(destination, filter="data")
    return raw


def _candidate_snapshot(destination: Path) -> None:
    _extract(IMMUTABLE_BUILD46_REVISION, destination)


def test_historical_verifier_is_immutable_and_build46_source_delta_is_exact(helper, tmp_path):
    assert hashlib.sha256(OLD_HELPER.read_bytes()).hexdigest() == helper.HISTORICAL_HELPER_SHA256
    prior = tmp_path / "prior"
    candidate = tmp_path / "candidate"
    raw = _extract(helper.PRIOR_REVISION, prior)
    _candidate_snapshot(candidate)

    proof = helper._verify_sources(prior, candidate)

    assert hashlib.sha256(raw).hexdigest() == helper.PRIOR_SOURCE_ARCHIVE_SHA256
    assert proof["reviewed_changed_path_count"] == len(helper.REVIEWED_CHANGES)
    assert proof["protected_path_count"] > 500
    assert not any(
        marker in path
        for path in helper.REVIEWED_CHANGES
        for marker in ("__pycache__", ".pytest_cache", ".coverage")
    )
    assert helper._backend_manifest_sha256(prior) == helper.PRIOR_BACKEND_MANIFEST_SHA256
    assert helper._backend_manifest_sha256(candidate) == helper.CANDIDATE_BACKEND_MANIFEST_SHA256


@pytest.mark.parametrize("kind", ["directory_link", "dangling_link", "ancestor_link"])
def test_inventory_rejects_every_link_under_a_protected_scope(helper, tmp_path, kind):
    root = tmp_path / "root"
    scope = root / "scope"
    if kind == "ancestor_link":
        outside = tmp_path / "outside"
        (outside / "scope").mkdir(parents=True)
        (outside / "scope/regular").write_text("bound")
        (root / "linked").parent.mkdir(parents=True)
        (root / "linked").symlink_to(outside, target_is_directory=True)
        with pytest.raises(helper.CompatibilityError, match="linked"):
            helper._inventory(root, ("linked/scope",))
        return
    scope.mkdir(parents=True)
    (scope / "regular").write_text("bound")
    link = scope / "unexpected"
    if kind == "directory_link":
        target = tmp_path / "outside"
        target.mkdir()
        link.symlink_to(target, target_is_directory=True)
    else:
        link.symlink_to(tmp_path / "missing")
    with pytest.raises(helper.CompatibilityError, match="linked|non-regular"):
        helper._inventory(root, ("scope",))


def _identity(helper):
    return {
        "prior_revision": helper.PRIOR_REVISION,
        "prior_version": helper.PRIOR_VERSION,
        "prior_db_head": helper.PRIOR_DATABASE_HEAD,
        "candidate_revision": "a" * 40,
        "candidate_version": helper.CANDIDATE_VERSION,
        "candidate_db_head": helper.CANDIDATE_DATABASE_HEAD,
        "prior_image_id": "sha256:" + "b" * 64,
        "candidate_image_id": "sha256:" + "c" * 64,
        "prior_backend_manifest_sha256": helper.PRIOR_BACKEND_MANIFEST_SHA256,
        "candidate_backend_manifest_sha256": helper.CANDIDATE_BACKEND_MANIFEST_SHA256,
        "prior_source_archive_sha256": helper.PRIOR_SOURCE_ARCHIVE_SHA256,
        "candidate_source_archive_sha256": "d" * 64,
    }


def test_only_exact_release_schema_and_immutable_artifacts_are_accepted(helper):
    helper._validate_identity(**_identity(helper))
    for field, value in (
        ("prior_revision", "0" * 40),
        ("prior_version", "3.1.36"),
        ("prior_db_head", "0085"),
        ("candidate_version", "3.1.39"),
        ("candidate_db_head", "0086"),
        ("candidate_revision", helper.PRIOR_REVISION),
        ("prior_image_id", "backend:latest"),
        ("candidate_image_id", "sha256:" + "b" * 64),
        ("prior_backend_manifest_sha256", "0" * 64),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("prior_source_archive_sha256", "0" * 64),
        ("candidate_source_archive_sha256", helper.PRIOR_SOURCE_ARCHIVE_SHA256),
    ):
        identity = _identity(helper)
        identity[field] = value
        with pytest.raises(helper.CompatibilityError):
            helper._validate_identity(**identity)


def _fleet_document(helper, historical, monkeypatch):
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
            "company_id": "44444444-4444-4444-4444-444444444444",
            "terminal_id": "55555555-5555-5555-5555-555555555555",
            "platform": "android",
            "version_name": "3.1.33",
            "version_code": 41,
            "pending_outbox_count": 1,
            "last_successful_sync_at": "2026-10-10T10:00:00.000000Z",
            "last_seen_at": "2026-10-13T11:00:00.000000Z",
        },
    ]
    expected = {}
    for row, classification in zip(rows, ("historical_cleanup_evidence", "unknown_preserved_work"), strict=True):
        digest = hashlib.sha256(historical._row_projection(row).encode()).hexdigest()
        row["identity_sha256"] = digest
        expected[digest] = (classification, row["version_name"], row["version_code"])
    fleet_digest = hashlib.sha256("\n".join(row["identity_sha256"] for row in rows).encode()).hexdigest()
    monkeypatch.setattr(historical, "EXPECTED_FLEET_ROWS", expected)
    monkeypatch.setattr(historical, "EXPECTED_NONZERO_FLEET_SHA256", fleet_digest)
    monkeypatch.setattr(historical, "EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256", "a" * 64)
    return {
        "schema_version": 1,
        "database_revision": "0086",
        "installation_row_count": 11,
        "pending_outbox_count": 2,
        "nonzero_installation_count": 2,
        "all_installations_identity_sha256": "a" * 64,
        "nonzero_installations_identity_sha256": fleet_digest,
        "nonzero_installations": rows,
        "client_installation_guard_trigger_count": 1,
        "client_installation_guard_function_sha256": historical._load_historical_verifier(ROOT).EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256,
        "client_installation_guard_function_definition_sha256": historical._load_historical_verifier(ROOT).EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256,
        "client_installation_guard_trigger_definition_sha256": historical._load_historical_verifier(ROOT).EXPECTED_CLIENT_INSTALLATION_GUARD_TRIGGER_DEFINITION_SHA256,
    }


@pytest.mark.parametrize("mutation", ["added", "missing", "changed", "wrong_total", "wrong_revision", "wrong_digest"])
def test_fleet_is_exactly_11_rows_and_two_opaque_preserved_counters(helper, monkeypatch, mutation):
    historical = helper._load_historical(ROOT)
    document = _fleet_document(helper, historical, monkeypatch)
    rows = document["nonzero_installations"]
    if mutation == "added":
        rows.append(deepcopy(rows[-1]))
    elif mutation == "missing":
        rows.pop()
    elif mutation == "changed":
        rows[-1]["pending_outbox_count"] = 2
    elif mutation == "wrong_total":
        document["installation_row_count"] = 10
    elif mutation == "wrong_revision":
        document["database_revision"] = "0085"
    else:
        document["all_installations_identity_sha256"] = "b" * 64
    with pytest.raises(historical.CompatibilityError):
        historical._verify_fleet(document, historical._load_historical_verifier(ROOT))


def test_valid_fleet_report_preserves_unknown_work_without_private_ids(helper, monkeypatch):
    historical = helper._load_historical(ROOT)
    document = _fleet_document(helper, historical, monkeypatch)
    result = historical._verify_fleet(document, historical._load_historical_verifier(ROOT))
    assert result["installation_row_count"] == 11
    assert result["raw_pending_outbox_count"] == 2
    assert [row["classification"] for row in result["installations"]] == [
        "historical_cleanup_evidence", "unknown_preserved_work"
    ]
    rendered = json.dumps(result)
    assert all(row["installation_id"] not in rendered for row in document["nonzero_installations"])


def test_loyalty_compatibility_contract_is_bound_and_mutations_fail(helper, tmp_path):
    paths = (
        "backend/app/services/pos/loyalty_program.py",
        "backend/app/api/v1/pos/router.py",
        "backend/alembic/versions/0087_versioned_loyalty_program.py",
        "backend/app/services/pos/points.py",
    )
    root = tmp_path / "candidate"
    for relative in paths:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / relative).read_bytes())
    proof = helper._verify_loyalty_compatibility(root)
    assert proof["production_program_revision_required"] == 0
    assert proof["revisionless_fresh_requests_after_customization"] == "refused"

    cases = (
        (paths[0], "if expected is None and program.revision == 0:", "if expected is None:"),
        (paths[1], "if existing_response:", "if False and existing_response:"),
        (paths[2], "redemption_kind='legacy_frozen'", "redemption_kind='generic'"),
        (paths[3], '"legacy_frozen"', '"generic"'),
    )
    for relative, before, after in cases:
        target = root / relative
        original = target.read_text()
        target.write_text(original.replace(before, after))
        with pytest.raises(helper.CompatibilityError):
            helper._verify_loyalty_compatibility(root)
        target.write_text(original)


def test_installer_has_separate_exact_build46_path_and_preserves_build45_branch(helper):
    source = INSTALLER.read_text()
    build46 = source.index('[ "$CANDIDATE_APP_VERSION" = 3.1.38 ]')
    build45 = source.index('[ "$CANDIDATE_APP_VERSION" = 3.1.37 ]', build46)
    assert build46 < build45
    block = source[build46:build45]
    assert helper.PRIOR_REVISION in block
    assert '[ "$PRIOR_APP_VERSION" = 3.1.37 ]' in block
    assert '[ "$PRIOR_DB_HEAD" = 0086 ]' in block
    assert '--candidate-db-head 0087' in block
    assert 'BUILD46_PENDING_CARRIED_UNRESOLVED=true' in block
    assert 'BUILD45_PENDING_CARRIED_UNRESOLVED=true' not in block
    assert 'queue' in block and 'carried UNRESOLVED' in block
    assert source.count('[ "$CANDIDATE_APP_VERSION" = 3.1.37 ]') == 1
    assert source.count('BUILD46_PENDING_CARRIED_UNRESOLVED=true') == 1
