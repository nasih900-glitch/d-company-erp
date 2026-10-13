from __future__ import annotations

from copy import deepcopy
import hashlib
import io
from itertools import product
import json
from pathlib import Path
import subprocess
import tarfile
import types

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build45-pending-compatibility.py"
STATE_SQL = ROOT / "infra/scripts/verify-build45-pending-state.sql"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"
HISTORICAL_TEST = ROOT / "tests/test_code30_2_post_cleanup_installer_guard.py"
BUILD45_REVISION = "ee025b5b78dff7397e16612cad80aebd1a4904d3"


def _load(path: Path, name: str):
    module = types.ModuleType(name)
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


@pytest.fixture
def helper():
    return _load(HELPER, "build45_pending_compatibility")


@pytest.fixture
def historical(helper):
    return helper._load_historical_verifier(ROOT)


@pytest.fixture
def historical_state(historical):
    fixtures = _load(HISTORICAL_TEST, "build45_historical_fixtures")
    document = fixtures._valid_document(historical)
    document.update(
        database_revision="0085",
        pending_outbox_count=2,
        nonzero_installation_count=2,
    )
    return document


def _extract_git_archive(ref: str, destination: Path) -> bytes:
    raw = subprocess.check_output(["git", "archive", "--format=tar", ref], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            assert not path.is_absolute() and ".." not in path.parts
            if member.isdir():
                (destination / path).mkdir(parents=True, exist_ok=True)
                continue
            assert member.isfile()
            stream = archive.extractfile(member)
            assert stream is not None
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(stream.read())
    return raw


def _candidate_snapshot(destination: Path) -> None:
    # Build 45 stays bound to its immutable release object after later releases.
    _extract_git_archive(BUILD45_REVISION, destination)


def test_exact_add_change_delete_pairs_are_content_bound(helper, tmp_path):
    before = tmp_path / "before"
    after = tmp_path / "after"
    for root in (before, after):
        (root / "scope").mkdir(parents=True)
    (before / "scope/changed").write_bytes(b"before")
    (before / "scope/deleted").write_bytes(b"deleted")
    (after / "scope/changed").write_bytes(b"after")
    (after / "scope/added").write_bytes(b"added")
    before_files = helper._inventory(before, ("scope",))
    after_files = helper._inventory(after, ("scope",))
    reviewed = helper._changes(before_files, after_files)

    proof = helper._verify_source_transition(
        before,
        after,
        scopes=("scope",),
        before_sha256=helper._inventory_sha256(before_files),
        after_sha256=helper._inventory_sha256(after_files),
        reviewed_changes=reviewed,
    )
    assert proof["reviewed_changed_path_count"] == 3

    (after / "scope/unreviewed").write_bytes(b"same name is insufficient")
    with pytest.raises(helper.CompatibilityError, match="unreviewed runtime bytes"):
        helper._verify_source_transition(
            before,
            after,
            scopes=("scope",),
            before_sha256=helper._inventory_sha256(before_files),
            after_sha256=helper._inventory_sha256(after_files),
            reviewed_changes=reviewed,
        )


@pytest.mark.parametrize("kind", ["file_link", "directory_link", "empty_directory"])
def test_source_inventory_refuses_links_and_empty_scopes(helper, tmp_path, kind):
    root = tmp_path / "root"
    (root / "scope").mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.write_bytes(b"outside")
    if kind == "file_link":
        (root / "scope/value").symlink_to(outside)
    elif kind == "directory_link":
        (root / "scope/linked").symlink_to(tmp_path, target_is_directory=True)
    else:
        (root / "scope/empty").mkdir()
    with pytest.raises(helper.CompatibilityError):
        helper._inventory(root, ("scope",))


def test_real_archives_match_whole_source_and_android_policy_without_mutation(helper, tmp_path):
    build41 = tmp_path / "build41"
    build44 = tmp_path / "build44"
    candidate = tmp_path / "candidate"
    build41_raw = _extract_git_archive(helper.BUILD41_REVISION, build41)
    build44_raw = _extract_git_archive(helper.PRIOR_REVISION, build44)
    _candidate_snapshot(candidate)
    before = {
        "build41": hashlib.sha256(build41_raw).hexdigest(),
        "build44": hashlib.sha256(build44_raw).hexdigest(),
        "candidate": helper._inventory_sha256(
            helper._inventory(candidate, helper.PROTECTED_SCOPES)
        ),
    }

    source = helper._verify_sources(build44, candidate)
    android = helper._verify_android_history(build41, build44, candidate)

    assert source["protected_path_count"] > 500
    assert android["android_protected_path_count"] > 60
    assert hashlib.sha256(build41_raw).hexdigest() == before["build41"]
    assert hashlib.sha256(build44_raw).hexdigest() == before["build44"]
    assert helper._inventory_sha256(
        helper._inventory(candidate, helper.PROTECTED_SCOPES)
    ) == before["candidate"]


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


def test_exact_release_and_immutable_artifact_identity_is_accepted(helper):
    helper._validate_identity(**_identity(helper))


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("prior_revision", "0" * 40),
        ("prior_version", "3.1.35"),
        ("prior_db_head", "0084"),
        ("candidate_revision", "short"),
        ("candidate_revision", "d915a66c9dee0d22c1a7018e2e79bc8ca9e0d7ab"),
        ("candidate_version", "3.1.38"),
        ("candidate_db_head", "0085"),
        ("prior_image_id", "backend:latest"),
        ("candidate_image_id", "sha256:" + "b" * 64),
        ("prior_backend_manifest_sha256", "0" * 64),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("prior_source_archive_sha256", "0" * 64),
        ("candidate_source_archive_sha256", "short"),
        (
            "candidate_source_archive_sha256",
            "019ee6ad421d302ff6304dfe0bd345d3b2325d251bf2bbef94662e81887f2939",
        ),
    ],
)
def test_other_release_schema_or_mutable_artifact_identity_is_refused(helper, field, value):
    identity = _identity(helper)
    identity[field] = value
    with pytest.raises(helper.CompatibilityError):
        helper._validate_identity(**identity)


def test_historical_receipt_is_scoped_in_memory_and_raw_document_is_unchanged(
    helper, historical, historical_state
):
    original = deepcopy(historical_state)
    with pytest.raises(historical.StateError):
        historical.verify_state(historical_state)

    proof = helper._verify_scoped_history(historical_state, historical)

    assert historical_state == original
    assert proof == {
        "historical_cleanup_receipt_id": 28204,
        "historical_proof_scope": "exact_retained_legacy_installation_only",
    }


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("database_revision", "0086"),
        ("pending_outbox_count", 1),
        ("pending_outbox_count", True),
        ("nonzero_installation_count", 1),
        ("cleanup_receipts", []),
        ("client_installation_guard_trigger_count", 0),
        ("retired_installation_invalid_telemetry_count", 1),
    ],
)
def test_historical_schema_aggregates_receipt_and_guard_drift_is_refused(
    helper, historical, historical_state, field, value
):
    historical_state[field] = value
    original = deepcopy(historical_state)
    with pytest.raises(helper.CompatibilityError):
        helper._verify_scoped_history(historical_state, historical)
    assert historical_state == original


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
            "last_seen_at": "2026-10-11T10:00:00.000000Z",
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
            "last_seen_at": "2026-10-11T11:00:00.000000Z",
        },
    ]
    expected = {}
    classifications = ("historical_cleanup_evidence", "unknown_preserved_work")
    for row, classification in zip(rows, classifications, strict=True):
        digest = hashlib.sha256(helper._row_projection(row).encode()).hexdigest()
        row["identity_sha256"] = digest
        expected[digest] = (classification, row["version_name"], row["version_code"])
    fleet_digest = hashlib.sha256(
        "\n".join(row["identity_sha256"] for row in rows).encode()
    ).hexdigest()
    monkeypatch.setattr(helper, "EXPECTED_FLEET_ROWS", expected)
    monkeypatch.setattr(helper, "EXPECTED_NONZERO_FLEET_SHA256", fleet_digest)
    monkeypatch.setattr(helper, "EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256", "a" * 64)
    return {
        "schema_version": 1,
        "database_revision": "0085",
        "installation_row_count": 10,
        "pending_outbox_count": 2,
        "nonzero_installation_count": 2,
        "all_installations_identity_sha256": "a" * 64,
        "nonzero_installations_identity_sha256": fleet_digest,
        "nonzero_installations": rows,
        "client_installation_guard_trigger_count": 1,
        "client_installation_guard_function_sha256": (
            historical.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_SHA256
        ),
        "client_installation_guard_function_definition_sha256": (
            historical.EXPECTED_CLIENT_INSTALLATION_GUARD_FUNCTION_DEFINITION_SHA256
        ),
        "client_installation_guard_trigger_definition_sha256": (
            historical.EXPECTED_CLIENT_INSTALLATION_GUARD_TRIGGER_DEFINITION_SHA256
        ),
    }


def test_full_fleet_projection_accepts_only_two_opaque_identities_and_preserves_raw(
    helper, historical, monkeypatch
):
    document = _fleet_document(helper, historical, monkeypatch)
    original = deepcopy(document)

    proof = helper._verify_fleet(document, historical)

    assert document == original
    assert [item["classification"] for item in proof["installations"]] == [
        "historical_cleanup_evidence",
        "unknown_preserved_work",
    ]
    serialized = json.dumps(proof)
    for row in document["nonzero_installations"]:
        assert row["row_id"] not in serialized
        assert row["installation_id"] not in serialized
        assert row["company_id"] not in serialized


@pytest.mark.parametrize(
    "change",
    [
        "total_rows",
        "pending_total",
        "nonzero_total",
        "all_identity",
        "fleet_identity",
        "missing_row",
        "extra_row",
        "reordered",
        "duplicate_row_id",
        "changed_projection",
        "changed_version",
        "bool_counter",
        "guard",
        "extra_field",
        "bool_schema",
        "bool_guard",
    ],
)
def test_fleet_refusal_matrix(helper, historical, monkeypatch, change):
    document = _fleet_document(helper, historical, monkeypatch)
    rows = document["nonzero_installations"]
    if change == "bool_schema":
        document["schema_version"] = True
    elif change == "bool_guard":
        document["client_installation_guard_trigger_count"] = True
    elif change == "total_rows":
        document["installation_row_count"] = 9
    elif change == "pending_total":
        document["pending_outbox_count"] = 1
    elif change == "nonzero_total":
        document["nonzero_installation_count"] = 1
    elif change == "all_identity":
        document["all_installations_identity_sha256"] = "b" * 64
    elif change == "fleet_identity":
        document["nonzero_installations_identity_sha256"] = "b" * 64
    elif change == "missing_row":
        rows.pop()
    elif change == "extra_row":
        rows.append(deepcopy(rows[-1]))
    elif change == "reordered":
        rows.reverse()
    elif change == "duplicate_row_id":
        rows[1]["row_id"] = rows[0]["row_id"]
    elif change == "changed_projection":
        rows[1]["last_seen_at"] = "2026-10-11T11:00:01.000000Z"
    elif change == "changed_version":
        rows[1]["version_code"] = 42
    elif change == "bool_counter":
        rows[1]["pending_outbox_count"] = True
    elif change == "guard":
        document["client_installation_guard_trigger_count"] = 0
    else:
        document["unexpected"] = 1
    with pytest.raises(helper.CompatibilityError):
        helper._verify_fleet(document, historical)


@pytest.mark.parametrize(
    "raw",
    [b"", b'{"key":1,"key":2}', b'{"key":NaN}', b"not-json", b"{}" * 524_289],
)
def test_raw_json_duplicate_nonfinite_malformed_and_oversized_documents_are_refused(
    helper, historical, raw
):
    with pytest.raises(helper.CompatibilityError):
        helper._decode_json(raw, historical, label="fleet state")


def _environments(helper):
    common = b"JWT_SECRET=synthetic-private-value\nMIN_CLIENT_VERSION=41\nFEATURE_FLAG=false\n"
    prior = common + (
        f"APP_VERSION={helper.PRIOR_VERSION}\nAPP_REVISION={helper.PRIOR_REVISION}\n"
    ).encode()
    candidate = common + (
        f"APP_VERSION={helper.CANDIDATE_VERSION}\nAPP_REVISION={'a' * 40}\n"
    ).encode()
    return prior, candidate


def test_environment_allows_only_exact_release_identity_change(helper):
    prior, candidate = _environments(helper)
    helper._verify_environment(prior, prior, candidate, "a" * 40)


@pytest.mark.parametrize(
    "change", ["secret", "minimum", "flag", "missing", "extra", "duplicate", "live"]
)
def test_environment_drift_and_ambiguity_is_refused_without_secret_output(helper, change):
    prior, candidate = _environments(helper)
    live = prior
    if change == "secret":
        candidate = candidate.replace(b"synthetic-private-value", b"changed-private-value")
    elif change == "minimum":
        candidate = candidate.replace(b"MIN_CLIENT_VERSION=41", b"MIN_CLIENT_VERSION=42")
    elif change == "flag":
        candidate = candidate.replace(b"FEATURE_FLAG=false", b"FEATURE_FLAG=true")
    elif change == "missing":
        candidate = candidate.replace(b"MIN_CLIENT_VERSION=41\n", b"")
    elif change == "extra":
        candidate += b"NEW_POLICY=true\n"
    elif change == "duplicate":
        candidate += f"APP_VERSION={helper.CANDIDATE_VERSION}\n".encode()
    else:
        live += b"CHANGED_AFTER_SNAPSHOT=true\n"
    with pytest.raises(helper.CompatibilityError) as error:
        helper._verify_environment(prior, live, candidate, "a" * 40)
    assert "private-value" not in str(error.value)


def _effective_environment(helper):
    prior_id = "sha256:" + "b" * 64
    candidate_id = "sha256:" + "c" * 64
    revision = "a" * 40
    common = {"PATH": "/usr/local/bin", "JWT_SECRET": "synthetic-private-value"}
    prior_values = {
        **common,
        "PYTHON_VERSION": "3.14.7",
        "PYTHON_SHA256": "3b48dac8fb59f62eaa67ac83c1eb12bda1b7a08406dd286e252c11a66be27f81",
        "APP_VERSION": helper.PRIOR_VERSION,
        "APP_REVISION": helper.PRIOR_REVISION,
    }
    candidate_image_values = {
        "PATH": common["PATH"],
        "PYTHON_VERSION": "3.14.8",
        "PYTHON_SHA256": "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73",
    }
    compose_values = {
        "JWT_SECRET": common["JWT_SECRET"],
        "APP_VERSION": helper.CANDIDATE_VERSION,
        "APP_REVISION": revision,
    }
    prior = {
        "image_id": prior_id,
        "environment": [f"{key}={value}" for key, value in prior_values.items()],
    }
    candidate = {
        "image_id": candidate_id,
        "environment": [f"{key}={value}" for key, value in candidate_image_values.items()],
    }
    compose = {
        "services": {
            "backend": {
                "image": f"d-company-erp-backend:{revision}",
                "environment": compose_values,
            }
        }
    }
    return prior, candidate, compose, prior_id, candidate_id, revision


def test_effective_environment_allows_only_reviewed_python_image_metadata(helper):
    inputs = _effective_environment(helper)
    original = deepcopy(inputs)
    assert helper._verify_effective_environment(*inputs) == 6
    assert inputs == original


@pytest.mark.parametrize(
    "change",
    [
        "secret",
        "path",
        "python_version",
        "python_sha",
        "extra",
        "missing",
        "duplicate",
        "wrong_image",
        "wrong_compose_image",
        "env_file",
        "masked_image_metadata",
    ],
)
def test_effective_environment_refuses_every_unreviewed_difference(helper, change):
    prior, candidate, compose, prior_id, candidate_id, revision = _effective_environment(helper)
    overrides = compose["services"]["backend"]["environment"]
    if change == "secret":
        overrides["JWT_SECRET"] = "changed-private-value"
    elif change == "path":
        candidate["environment"][0] = "PATH=/different"
    elif change == "python_version":
        candidate["environment"][1] = "PYTHON_VERSION=3.14.9"
    elif change == "python_sha":
        candidate["environment"][2] = "PYTHON_SHA256=" + "0" * 64
    elif change == "extra":
        overrides["NEW_POLICY"] = "true"
    elif change == "missing":
        del overrides["JWT_SECRET"]
    elif change == "duplicate":
        candidate["environment"].append("PATH=/usr/local/bin")
    elif change == "wrong_image":
        candidate["image_id"] = "sha256:" + "d" * 64
    elif change == "wrong_compose_image":
        compose["services"]["backend"]["image"] = "backend:latest"
    elif change == "env_file":
        compose["services"]["backend"]["env_file"] = [".env"]
    else:
        overrides["PYTHON_VERSION"] = "3.14.8"
    with pytest.raises(helper.CompatibilityError) as error:
        helper._verify_effective_environment(
            prior, candidate, compose, prior_id, candidate_id, revision
        )
    assert "private-value" not in str(error.value)


@pytest.mark.parametrize(
    "raw",
    [b"", b'{"key":1,"key":2}', b'{"key":NaN}', b"not-json", b"{}" * 524_289],
)
def test_runtime_configuration_json_is_strict_and_bounded(helper, raw):
    with pytest.raises(helper.CompatibilityError):
        helper._json_document(raw)


def test_sql_scans_full_fleet_in_utc_and_emits_only_one_json_document():
    source = STATE_SQL.read_text()
    lowered = source.lower()
    assert "repeatable read read only" in lowered
    assert "set local time zone 'utc'" in lowered
    assert "from client_installations" in lowered
    assert "where installation.pending_outbox_count is distinct from 0" in lowered
    assert "last_seen_at >" not in lowered and "last_seen_at >=" not in lowered
    assert "interval '" not in lowered
    assert "order by installation.id::text" in lowered
    assert source.rstrip().endswith("\\set QUIET on\nROLLBACK;")
    assert source.count("SELECT jsonb_build_object(") == 1


def test_private_evidence_and_frozen_distinct_source_roots_are_enforced(
    helper, tmp_path, monkeypatch
):
    evidence = tmp_path / "evidence.json"
    evidence.write_text("{}")
    evidence.chmod(0o600)
    if evidence.stat().st_uid != 0:
        with pytest.raises(helper.CompatibilityError, match="root-owned"):
            helper._private_file(evidence)

    roots = [tmp_path / name for name in ("build41", "prior", "candidate")]
    for root in roots:
        root.mkdir()
    executable = roots[2] / "infra/scripts/verify-build45-pending-compatibility.py"
    executable.parent.mkdir(parents=True)
    executable.write_text("# frozen")
    monkeypatch.setattr(helper, "_private_root", lambda _path: None)
    helper._validate_source_roots(*roots, executable)
    with pytest.raises(helper.CompatibilityError, match="candidate archive"):
        helper._validate_source_roots(*roots, ROOT / "infra/scripts/other.py")
    with pytest.raises(helper.CompatibilityError, match="distinct"):
        helper._validate_source_roots(roots[0], roots[1], roots[1], executable)


def test_public_policy_contains_only_opaque_fleet_identities(helper):
    assert helper.EXPECTED_FLEET_ROWS == {
        "1461683b87a9509241bbc1144486bde38d63f110e8b0062e58db26c43e1ce944": (
            "historical_cleanup_evidence",
            "3.1.29",
            37,
        ),
        "6c1ba1c1c5a9391c2db9fb4065294ebec9f442c4b12f9a71da57afbcfd98a56a": (
            "unknown_preserved_work",
            "3.1.33",
            41,
        ),
    }
    source = HELPER.read_text()
    assert "d664b4a5-" not in source
    assert "c5ceb292-" not in source


def test_cli_exposes_no_force_skip_fallback_or_waiver_switch(helper):
    result = subprocess.run(
        ["python3", str(HELPER), "--force"], capture_output=True, text=True
    )
    assert result.returncode != 0
    assert result.stdout == ""
    assert not {"force", "skip", "fallback", "waiver"} & {
        word.lower() for word in HELPER.read_text().split()
    }


@pytest.mark.parametrize(
    ("historical_path", "bridge_path", "build42_path", "build45_path"),
    list(product((False, True), repeat=4)),
)
def test_installer_accepts_exactly_one_of_four_pending_paths(
    historical_path, bridge_path, build42_path, build45_path
):
    source = INSTALLER.read_text()
    start = source.index("  # Preserve the original legacy-path guard")
    end = source.index(
        '  echo "==> Creating final quiesced pre-upgrade PostgreSQL backup', start
    )
    guard = (
        "set -eu\npending_outbox_count=2\n"
        "CODE30_2_POST_CLEANUP_STALE_OUTBOX_ACCEPTED=$1\n"
        "CODE30_2_STALE_OUTBOX_BRIDGE_USED=$2\n"
        "BUILD42_PENDING_CARRIED_UNRESOLVED=$3\n"
        "BUILD45_PENDING_CARRIED_UNRESOLVED=$4\n"
        + source[start:end]
    )
    values = (historical_path, bridge_path, build42_path, build45_path)
    result = subprocess.run(
        ["bash", "-c", guard, "pending-path-guard", *map(lambda v: str(v).lower(), values)],
        capture_output=True,
        text=True,
    )
    assert (result.returncode == 0) is (sum(values) == 1)


def test_installer_keeps_historical_first_and_proves_before_backup_or_migration():
    source = INSTALLER.read_text()
    stopped = source.index('verify_production_business_quiescence "after writer stop"')
    historical = source.index('python3 "$POST_CLEANUP_STATE_VERIFIER" --quiet', stopped)
    build42 = source.index('python3 "$BUILD42_PENDING_COMPATIBILITY_VERIFIER"', historical)
    build45_branch = source.index('[ "$CANDIDATE_APP_VERSION" = 3.1.37 ]', build42)
    fleet_query = source.index('< "$BUILD45_PENDING_STATE_SQL"', build45_branch)
    proof = source.index('python3 "$BUILD45_PENDING_COMPATIBILITY_VERIFIER"', fleet_query)
    backup = source.index("pg_dump -U erp --format=custom erp", proof)
    candidate_start = source.index(
        "up -d --no-build --pull never postgres redis backend frontend", backup
    )
    assert (
        stopped
        < historical
        < build42
        < build45_branch
        < fleet_query
        < proof
        < backup
        < candidate_start
    )
    assert 'chmod 600 "$RAW_BUILD45_FLEET_STATE"' in source
    assert 'chmod 600 "$PENDING_COMPATIBILITY_REPORT"' in source
    assert '--raw-historical-state "$RAW_PENDING_STATE"' in source
    assert '--raw-fleet-state "$RAW_BUILD45_FLEET_STATE"' in source
    assert "carried UNRESOLVED" in source


def test_historical_verifier_query_helper_and_build42_policy_bytes_are_unchanged():
    protected = (
        "infra/scripts/verify-code30-2-post-cleanup-state.py",
        "infra/scripts/verify-code30-2-post-cleanup-state.sql",
        "infra/scripts/verify-build42-pending-compatibility.py",
        "tests/test_build42_pending_compatibility.py",
    )
    # The one narrow test edit only teaches the old three-path harness about
    # the new false-by-default fourth flag. Historical proof bytes stay exact.
    for relative in protected[:3]:
        expected = subprocess.check_output(
            ["git", "show", f"{BUILD45_REVISION}:{relative}"], cwd=ROOT
        )
        assert (ROOT / relative).read_bytes() == expected
