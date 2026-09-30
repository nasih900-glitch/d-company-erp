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
HELPER = ROOT / "infra/scripts/verify-build42-pending-compatibility.py"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"


def _load(path: Path, name: str):
    module = types.ModuleType(name)
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


@pytest.fixture
def helper():
    return _load(HELPER, "pending_compatibility")


@pytest.fixture
def history(helper):
    historical = helper._load_historical_verifier(ROOT)
    fixtures = _load(ROOT / "tests/test_code30_2_post_cleanup_installer_guard.py", "history_fixtures")
    document = fixtures._valid_document(historical)
    document.update(database_revision="0085", pending_outbox_count=3, nonzero_installation_count=3)
    return historical, document


@pytest.fixture
def sources(tmp_path, helper):
    prior = tmp_path.resolve() / "prior"
    candidate = tmp_path.resolve() / "candidate"
    paths = (
        "backend/app/pos.py", "backend/app/security.py", "backend/app/financial.py",
        "backend/requirements.lock", "backend/alembic/0085.py", "backend/scripts/seed.py",
        "infra/entrypoint.sh", "infra/historical.py", "compose.yml",
    )
    for root in (prior, candidate):
        for path in paths:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("original\n")
    (candidate / paths[0]).write_text("reviewed message only\n")
    scopes = ("backend", "infra", "compose.yml")
    before = helper._inventory(prior, scopes)
    after = helper._inventory(candidate, scopes)
    policy = dict(scopes=scopes, prior_sha256=helper._inventory_sha256(before),
                  candidate_sha256=helper._inventory_sha256(after),
                  pairs={paths[0]: (before[paths[0]], after[paths[0]])})
    return prior, candidate, policy


def test_exact_reviewed_pair_is_accepted(helper, sources):
    prior, candidate, policy = sources
    assert helper._verify_sources(prior, candidate, **policy) == {
        "prior_protected_source_sha256": policy["prior_sha256"],
        "candidate_protected_source_sha256": policy["candidate_sha256"],
    }


@pytest.mark.parametrize("path", [
    "backend/app/pos.py", "backend/app/security.py", "backend/app/financial.py",
    "backend/requirements.lock", "backend/alembic/0085.py", "backend/scripts/seed.py",
    "infra/entrypoint.sh", "infra/historical.py", "compose.yml",
])
def test_unreviewed_runtime_dependency_migration_or_proof_bytes_refused(helper, sources, path):
    prior, candidate, policy = sources
    (candidate / path).write_text("unreviewed change\n")
    with pytest.raises(helper.CompatibilityError, match="unreviewed runtime"):
        helper._verify_sources(prior, candidate, **policy)


@pytest.mark.parametrize("change", ["extra", "missing", "empty_directory", "file_link", "directory_link"])
def test_unknown_missing_and_linked_source_paths_refused(helper, sources, change):
    prior, candidate, policy = sources
    if change == "extra":
        (candidate / "backend/new_module.py").write_text("unexpected")
    elif change == "missing":
        (candidate / "backend/app/security.py").unlink()
    elif change == "empty_directory":
        (candidate / "backend/unreviewed").mkdir()
    elif change == "file_link":
        path = candidate / "backend/app/security.py"
        path.unlink()
        path.symlink_to(prior / "backend/app/security.py")
    else:
        (candidate / "backend/linked").symlink_to(prior / "backend/app", target_is_directory=True)
    with pytest.raises(helper.CompatibilityError):
        helper._verify_sources(prior, candidate, **policy)


def test_equal_unreviewed_changes_to_both_archives_cannot_pass(helper, sources):
    prior, candidate, policy = sources
    for root in (prior, candidate):
        (root / "backend/app/security.py").write_text("same unreviewed bytes")
    with pytest.raises(helper.CompatibilityError, match="immutable predecessor"):
        helper._verify_sources(prior, candidate, **policy)


def test_original_archived_predecessor_and_candidate_match_production_policy(helper, tmp_path):
    # Git archive supplies only immutable tracked inputs, just like the installer.
    raw_archive = subprocess.check_output([
        "git", "archive", helper.PRIOR_REVISION, "--", *helper.PROTECTED_SCOPES,
    ], cwd=ROOT)
    prior = tmp_path.resolve() / "prior"
    candidate = tmp_path.resolve() / "candidate"
    manifest = []
    with tarfile.open(fileobj=io.BytesIO(raw_archive)) as archive:
        for member in archive.getmembers():
            if member.isdir():
                continue
            assert member.isfile() and not Path(member.name).is_absolute() and ".." not in Path(member.name).parts
            stream = archive.extractfile(member)
            assert stream is not None
            original = stream.read()
            for root, content in ((prior, original), (candidate, (ROOT / member.name).read_bytes())):
                path = root / member.name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            if member.name.startswith("backend/"):
                manifest.append((member.name[8:], hashlib.sha256(original).hexdigest()))
    expected_manifest = "".join(f"{digest}\t{path}\n" for path, digest in sorted(manifest))
    assert hashlib.sha256(expected_manifest.encode()).hexdigest() == helper.PRIOR_BACKEND_MANIFEST_SHA256
    helper._verify_sources(prior, candidate)


def _identity(helper):
    return dict(prior_revision=helper.PRIOR_REVISION, prior_version="3.1.33", prior_db_head="0085",
                candidate_revision="a" * 40, candidate_version="3.1.34",
                prior_image_id="sha256:" + "b" * 64,
                prior_backend_manifest_sha256=helper.PRIOR_BACKEND_MANIFEST_SHA256)


def test_exact_identity_accepted(helper):
    helper._validate_identity(**_identity(helper))


@pytest.mark.parametrize("field,value", [
    ("prior_revision", "099daa28"), ("prior_revision", "b" * 40),
    ("prior_version", "3.1.32"), ("prior_db_head", "0084"), ("prior_db_head", "0086"),
    ("candidate_version", "3.1.35"), ("candidate_revision", "a" * 7),
    ("prior_image_id", "backend:latest"), ("prior_backend_manifest_sha256", "c" * 64),
])
def test_other_releases_schemas_or_unattested_images_refused(helper, field, value):
    identity = _identity(helper)
    identity[field] = value
    with pytest.raises(helper.CompatibilityError):
        helper._validate_identity(**identity)


def test_scoped_historical_proof_keeps_raw_counts_and_document_unchanged(helper, history):
    historical, document = history
    raw_before = json.dumps(document, sort_keys=True)
    with pytest.raises(historical.StateError):
        historical.verify_state(document)
    report = helper._verify_scoped_history(document, 3, historical)
    assert json.dumps(document, sort_keys=True) == raw_before
    assert report["raw_pending_outbox_count"] == report["raw_nonzero_installation_count"] == 3
    assert report["historical_scoped_pending_outbox_count"] == 1
    assert report["historical_cleanup_receipt_id"] == 28204
    assert report["historical_proof_scope"] == "exact_retained_legacy_installation_only"
    assert report["status"] == "pending_carried_unresolved"
    assert report["additional_reported_pending_count"] == 2
    assert report["queue_drained"] is False
    assert report["telemetry_rewritten"] is False
    assert report["tablet_queue_contents_verified"] is False


@pytest.mark.parametrize("pending,installations,expected", [
    (True, 1, 1), (3, True, 3), ("3", 3, 3), (3.0, 3, 3),
    (-1, 1, -1), (0, 0, 0), (1, 2, 1), (3, 1, 3), (3, 3, 4), (3, 3, True),
    (2_000_001, 2, 2_000_001), (1_000_002, 2, 1_000_002), (None, 3, 3),
])
def test_malformed_or_inconsistent_raw_aggregates_refused(helper, history, pending, installations, expected):
    historical, document = history
    document.update(pending_outbox_count=pending, nonzero_installation_count=installations)
    with pytest.raises(helper.CompatibilityError, match="aggregates"):
        helper._verify_scoped_history(document, expected, historical)


@pytest.mark.parametrize("field,value", [
    ("database_revision", "0086"), ("retired_installation_invalid_telemetry_count", 1),
    ("retired_installation_count", 2), ("retired_installation_identity_sha256", "0" * 64),
    ("cleanup_receipts", []), ("client_installation_guard_trigger_count", 0),
    ("retained_remote_assistance_device_key_count", 28),
    ("remote_assistance_device_key_guard_function_sha256", "0" * 64),
])
def test_historical_identity_receipt_fence_key_and_schema_proof_not_weakened(helper, history, field, value):
    historical, document = history
    document[field] = value
    original = deepcopy(document)
    with pytest.raises(helper.CompatibilityError):
        helper._verify_scoped_history(document, 3, historical)
    assert document == original


@pytest.mark.parametrize("raw", [b"", b'{"x":1,"x":2}', b'{"x":NaN}', b"{}" * 524289])
def test_raw_duplicate_nonfinite_and_oversized_json_refused(helper, history, raw):
    historical, _ = history
    with pytest.raises(helper.CompatibilityError):
        helper._decode_state(raw, historical)


def _environments(helper):
    common = b"DOMAIN=example.test\nJWT_SECRET=synthetic-private-value\nMIN_CLIENT_VERSION=41\nFEATURE_FLAG=false\n"
    prior = common + f"APP_VERSION=3.1.33\nAPP_REVISION={helper.PRIOR_REVISION}\n".encode()
    candidate = common + f"APP_VERSION=3.1.34\nAPP_REVISION={'a' * 40}\n".encode()
    return prior, candidate


def test_only_release_environment_metadata_may_change(helper):
    prior, candidate = _environments(helper)
    helper._verify_environment(prior, prior, candidate, "a" * 40)


@pytest.mark.parametrize("change", ["secret", "minimum", "flag", "missing", "extra", "duplicate", "identity", "live"])
def test_environment_policy_changes_refused_without_secret_output(helper, change):
    prior, candidate = _environments(helper)
    live = prior
    if change == "secret":
        candidate = candidate.replace(b"synthetic-private-value", b"another-private-value")
    elif change == "minimum":
        candidate = candidate.replace(b"MIN_CLIENT_VERSION=41", b"MIN_CLIENT_VERSION=42")
    elif change == "flag":
        candidate = candidate.replace(b"FEATURE_FLAG=false", b"FEATURE_FLAG=true")
    elif change == "missing":
        candidate = candidate.replace(b"MIN_CLIENT_VERSION=41\n", b"")
    elif change == "extra":
        candidate += b"NEW_POLICY=true\n"
    elif change == "duplicate":
        candidate += b"export APP_VERSION=3.1.34\n"
    elif change == "identity":
        candidate = candidate.replace(b"APP_VERSION=3.1.34", b"APP_VERSION=3.1.35")
    else:
        live += b"CHANGED_AFTER_SNAPSHOT=true\n"
    with pytest.raises(helper.CompatibilityError) as error:
        helper._verify_environment(prior, live, candidate, "a" * 40)
    assert "private-value" not in str(error.value)


def _effective_environments(helper):
    prior_id = "sha256:" + "b" * 64
    candidate_id = "sha256:" + "c" * 64
    revision = "a" * 40
    common = {"PATH": "/usr/local/bin:/usr/bin", "JWT_SECRET": "synthetic-private-value",
              "JWT_ALGORITHM": "HS256", "ANDROID_MIN_SUPPORTED_VERSION_CODE": "8", "EMPTY": ""}
    before = {**common, "APP_VERSION": "3.1.33", "APP_REVISION": helper.PRIOR_REVISION}
    compose_env = {key: value for key, value in common.items() if key != "PATH"}
    compose_env.update(APP_VERSION="3.1.34", APP_REVISION=revision)
    prior = {"image_id": prior_id, "environment": [f"{key}={value}" for key, value in before.items()]}
    image = {"image_id": candidate_id, "environment": ["PATH=/usr/local/bin:/usr/bin"]}
    compose = {"services": {"backend": {"image": f"d-company-erp-backend:{revision}", "environment": compose_env}}}
    return prior, image, compose, prior_id, candidate_id, revision


def test_effective_runtime_environment_includes_image_defaults_and_empty_strings(helper):
    inputs = _effective_environments(helper)
    before = deepcopy(inputs)
    assert helper._verify_effective_environment(*inputs) == 7
    assert inputs == before


@pytest.mark.parametrize("change", [
    "container_drift", "ambient_override", "image_default", "missing_compose_key",
    "extra_container_key", "null_compose_value", "bool_compose_value", "null_docker_entry",
    "duplicate_docker_key", "missing_equals", "null_docker_env", "wrong_prior_image",
    "wrong_candidate_image", "wrong_compose_image", "prior_identity", "candidate_identity",
    "env_file", "missing_backend",
])
def test_effective_runtime_environment_drift_and_ambiguous_input_refused(helper, change):
    prior, image, compose, prior_id, candidate_id, revision = _effective_environments(helper)
    env = compose["services"]["backend"]["environment"]
    if change == "container_drift":
        prior["environment"] = [entry.replace("synthetic-private-value", "different-private-value")
                                for entry in prior["environment"]]
    elif change == "ambient_override":
        env["ANDROID_MIN_SUPPORTED_VERSION_CODE"] = "42"
    elif change == "image_default":
        image["environment"] = ["PATH=/different/bin"]
    elif change == "missing_compose_key":
        del env["JWT_ALGORITHM"]
    elif change == "extra_container_key":
        prior["environment"].append("UNEXPECTED_POLICY=true")
    elif change == "null_compose_value":
        env["EMPTY"] = None
    elif change == "bool_compose_value":
        env["EMPTY"] = False
    elif change == "null_docker_entry":
        prior["environment"].append(None)
    elif change == "duplicate_docker_key":
        image["environment"].append("PATH=/usr/local/bin:/usr/bin")
    elif change == "missing_equals":
        image["environment"].append("BROKEN")
    elif change == "null_docker_env":
        image["environment"] = None
    elif change == "wrong_prior_image":
        prior["image_id"] = "sha256:" + "d" * 64
    elif change == "wrong_candidate_image":
        image["image_id"] = "sha256:" + "d" * 64
    elif change == "wrong_compose_image":
        compose["services"]["backend"]["image"] = "backend:latest"
    elif change == "prior_identity":
        prior["environment"] = [entry.replace("APP_VERSION=3.1.33", "APP_VERSION=3.1.32")
                                for entry in prior["environment"]]
    elif change == "candidate_identity":
        env["APP_REVISION"] = "unknown"
    elif change == "env_file":
        compose["services"]["backend"]["env_file"] = [".env"]
    else:
        del compose["services"]["backend"]
    with pytest.raises(helper.CompatibilityError) as error:
        helper._verify_effective_environment(prior, image, compose, prior_id, candidate_id, revision)
    assert "private-value" not in str(error.value)


@pytest.mark.parametrize("raw", [
    b'{"environment":{"KEY":"secret","KEY":"secret"}}',
    b'{"environment": NaN}', b'{"private-value":', b'\xff',
])
def test_runtime_json_duplicate_invalid_nonfinite_values_refused_without_exposure(helper, raw):
    with pytest.raises(helper.CompatibilityError) as error:
        helper._json_document(raw)
    assert "secret" not in str(error.value)
    assert "private-value" not in str(error.value)


def test_cli_has_no_force_or_policy_override():
    result = subprocess.run(["python3", str(HELPER), "--force"], capture_output=True, text=True)
    assert result.returncode != 0
    assert not result.stdout


@pytest.mark.parametrize("historical,bridge,compatible", list(product((False, True), repeat=3)))
def test_installer_accepts_exactly_one_pending_path_for_all_flag_combinations(historical, bridge, compatible):
    source = INSTALLER.read_text()
    start = source.index("  # Preserve the original legacy-path guard")
    end = source.index('  echo "==> Creating final quiesced pre-upgrade PostgreSQL backup', start)
    # Execute only the actual boolean guards, never the installer or its tools.
    guard = (
        'set -eu\npending_outbox_count=3\n'
        'CODE30_2_POST_CLEANUP_STALE_OUTBOX_ACCEPTED=$1\n'
        'CODE30_2_STALE_OUTBOX_BRIDGE_USED=$2\n'
        'BUILD42_PENDING_CARRIED_UNRESOLVED=$3\n'
        + source[start:end]
    )
    flags = [str(value).lower() for value in (historical, bridge, compatible)]
    result = subprocess.run(["bash", "-c", guard, "pending-path-guard", *flags],
                            capture_output=True, text=True)
    assert (result.returncode == 0) is (sum((historical, bridge, compatible)) == 1)


def test_installer_preserves_stop_backup_restore_and_private_evidence_order():
    source = INSTALLER.read_text()
    before = source.index('verify_production_business_quiescence "before maintenance"')
    stop = source.index('--env-file .env stop -t 60 backend', before)
    after = source.index('verify_production_business_quiescence "after writer stop"', stop)
    proof = source.index('python3 "$BUILD42_PENDING_COMPATIBILITY_VERIFIER"', after)
    backup = source.index('pg_dump -U erp --format=custom erp', proof)
    restore = source.index('pg_restore', backup)
    promotion = source.index('mv "$ENV_CANDIDATE" .env', restore)
    assert before < stop < after < proof < backup < restore < promotion
    assert 'chmod 600 "$RAW_PENDING_STATE"' in source
    assert 'chmod 600 "$PENDING_COMPATIBILITY_REPORT"' in source
    assert '--raw-state "$RAW_PENDING_STATE"' in source
    assert '--prior-env "$UPGRADE_SNAPSHOT/.env" --live-env "$REPO_DIR/.env"' in source
    assert '--candidate-source-root "$CANDIDATE_BUILD_ROOT"' in source
    assert '--candidate-compose-config "$CANDIDATE_COMPOSE_CONFIG"' in source
    assert '--env-file "$ENV_CANDIDATE" config --format json' in source
    assert 'chmod 600 "$PRIOR_RUNTIME_ENV" "$CANDIDATE_IMAGE_ENV" "$CANDIDATE_COMPOSE_CONFIG"' in source
    # The immutable config has no env_file directive that could independently
    # read the prior .env before promotion instead of the rendered candidate.
    assert "env_file:" not in (ROOT / "docker-compose.prod.yml").read_text()
    assert '"$accepted_pending_paths" -ne 1' in source
    assert 'carried UNRESOLVED' in source
    assert 'trap handle_post_ingress_failure EXIT' in source
