from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import io
from pathlib import Path
import shutil
import subprocess
import tarfile

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build49-pending-compatibility.py"
BUILD48_HELPER = ROOT / "infra/scripts/verify-build48-pending-compatibility.py"
BUILD47_HELPER = ROOT / "infra/scripts/verify-build47-pending-compatibility.py"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def helper():
    return _load(HELPER, "build49_pending_compatibility")


def _extract(ref: str, destination: Path) -> bytes:
    raw = subprocess.check_output(["git", "archive", "--format=tar", ref], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            assert not path.is_absolute() and ".." not in path.parts
        archive.extractall(destination, filter="data")
    return raw


def _candidate_snapshot(destination: Path) -> None:
    _extract("a834b63d32c45513ab5c8fd9d4de417acc0a96c6", destination)


def test_exact_immutable_build48_to_build49_source_delta(helper, tmp_path):
    assert hashlib.sha256(BUILD47_HELPER.read_bytes()).hexdigest() == helper.HISTORICAL_HELPER_SHA256
    assert hashlib.sha256(BUILD48_HELPER.read_bytes()).hexdigest() == helper.IMMUTABLE_BUILD48_HELPER_SHA256
    prior = tmp_path / "prior"
    candidate = tmp_path / "candidate"
    prior_archive = _extract(helper.PRIOR_REVISION, prior)
    _candidate_snapshot(candidate)

    proof = helper._verify_sources(prior, candidate)

    assert hashlib.sha256(prior_archive).hexdigest() == helper.PRIOR_SOURCE_ARCHIVE_SHA256
    assert proof == {
        "prior_protected_source_sha256": helper.PRIOR_SOURCE_SHA256,
        "candidate_protected_source_sha256": helper.CANDIDATE_SOURCE_SHA256,
        "protected_path_count": 1211,
        "reviewed_changed_path_count": 36,
    }
    assert helper._backend_manifest_sha256(prior) == helper.PRIOR_BACKEND_MANIFEST_SHA256
    assert helper._backend_manifest_sha256(candidate) == helper.CANDIDATE_BACKEND_MANIFEST_SHA256


def test_fresh_fleet_binding_is_exact_and_pending_remains_opaque(helper):
    assert helper.PRIOR_REVISION == "4058fb29e70789a9bb3fa62b2d580330b25db8a3"
    assert (helper.PRIOR_VERSION, helper.CANDIDATE_VERSION) == ("3.1.40", "3.1.41")
    assert (helper.PRIOR_DATABASE_HEAD, helper.CANDIDATE_DATABASE_HEAD) == ("0088", "0088")
    assert helper.EXPECTED_INSTALLATION_ROWS == 12
    assert helper.EXPECTED_PENDING == 2
    assert helper.EXPECTED_NONZERO == 2
    assert helper.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 == "d741c0d407f2b29c687f7b41ec7276a93f19853e99fc279fb65d0f5aec03d030"
    assert helper.EXPECTED_NONZERO_FLEET_SHA256 == "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"
    source = HELPER.read_text()
    assert '"status": "pending_carried_unresolved"' in source
    assert '"historic_pending_work_classification": "unknown_preserved_work"' in source
    assert '"queue_drained": False' in source
    assert '"installation_retired": False' in source
    assert '"tablet_queue_contents_verified": False' in source
    fleet = {
        "installations": [
            {"classification": "historical_cleanup_evidence", "pending_outbox_count": 1},
            {"classification": "unclassified", "pending_outbox_count": 1},
        ]
    }
    assert helper._classify_opaque_fleet(fleet) is fleet
    assert {row["classification"] for row in fleet["installations"]} == {"unknown_preserved_work"}
    assert sum(row["pending_outbox_count"] for row in fleet["installations"]) == 2


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


@pytest.mark.parametrize(
    "field,value",
    [
        ("prior_revision", "0" * 40),
        ("prior_version", "3.1.39"),
        ("prior_db_head", "0087"),
        ("candidate_version", "3.1.40"),
        ("candidate_db_head", "0089"),
        ("candidate_revision", "4058fb29e70789a9bb3fa62b2d580330b25db8a3"),
        ("prior_image_id", "backend:latest"),
        ("candidate_image_id", "sha256:" + "b" * 64),
        ("prior_backend_manifest_sha256", "0" * 64),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("prior_source_archive_sha256", "0" * 64),
        ("candidate_source_archive_sha256", "e37df62bc72ccb90b61f8a08a42e1115123a4c81d4ac6913cf8e25ea5e2af56c"),
    ],
)
def test_only_exact_identity_distinct_images_and_archives_are_accepted(helper, field, value):
    helper._validate_identity(**_identity(helper))
    changed = _identity(helper)
    changed[field] = value
    with pytest.raises(helper.CompatibilityError):
        helper._validate_identity(**changed)


def _effective_environment(helper):
    prior_id = "sha256:" + "b" * 64
    candidate_id = "sha256:" + "c" * 64
    revision = "a" * 40
    common = {"PATH": "/usr/local/bin", "JWT_SECRET": "private"}
    python = {"PYTHON_VERSION": "3.14.8", "PYTHON_SHA256": "c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73"}
    prior = {"image_id": prior_id, "environment": [f"{k}={v}" for k, v in {**common, **python, "APP_VERSION": helper.PRIOR_VERSION, "APP_REVISION": helper.PRIOR_REVISION}.items()]}
    candidate = {"image_id": candidate_id, "environment": [f"{k}={v}" for k, v in {"PATH": common["PATH"], **python}.items()]}
    compose = {"services": {"backend": {"image": f"d-company-erp-backend:{revision}", "environment": {"JWT_SECRET": common["JWT_SECRET"], "APP_VERSION": helper.CANDIDATE_VERSION, "APP_REVISION": revision}}}}
    return prior, candidate, compose, prior_id, candidate_id, revision


def test_effective_environment_is_equal_except_reviewed_image_metadata(helper):
    historical = helper._load_historical(ROOT)
    inputs = _effective_environment(helper)
    original = deepcopy(inputs)
    assert helper._verify_effective_environment(historical, *inputs) == 6
    assert inputs == original
    inputs[2]["services"]["backend"]["environment"]["JWT_SECRET"] = "changed"
    with pytest.raises(helper.CompatibilityError):
        helper._verify_effective_environment(historical, *inputs)


@pytest.mark.parametrize("mutation", ["bytes", "symlink"])
def test_build48_helper_lineage_guard_fails_closed(helper, tmp_path, mutation):
    root = tmp_path / "candidate"
    path = root / helper.IMMUTABLE_BUILD48_HELPER
    path.parent.mkdir(parents=True)
    if mutation == "bytes":
        path.write_bytes(BUILD48_HELPER.read_bytes() + b"\n")
    else:
        path.symlink_to(BUILD48_HELPER)
    with pytest.raises(helper.CompatibilityError, match="build48 verifier changed"):
        helper._verify_immutable_build48_helper(root)


def test_installer_has_one_exact_fail_closed_build49_path():
    source = INSTALLER.read_text()
    assert source.count('BUILD49_PENDING_CARRIED_UNRESOLVED=true') == 1
    assert source.count('python3 "$BUILD49_PENDING_COMPATIBILITY_VERIFIER"') == 1
    assert source.count('[ "$CANDIDATE_APP_VERSION" = 3.1.41 ]') == 1
    assert '[ "$PRIOR_APP_VERSION" = 3.1.40 ]' in source
    assert '[ "$PRIOR_REVISION" = 4058fb29e70789a9bb3fa62b2d580330b25db8a3 ]' in source
    assert '${BUILD49_PENDING_CARRIED_UNRESOLVED:-false}' in source
    assert 'if [ "$pending_outbox_count" -ne 0 ] && [ "$accepted_pending_paths" -ne 1 ]' in source
    branch = source.split('elif [ "$CANDIDATE_APP_VERSION" = 3.1.41 ]', 1)[1].split('elif [ "$CANDIDATE_APP_VERSION" = 3.1.40 ]', 1)[0]
    for forbidden in ("UPDATE tablet_installations", "DELETE FROM", "adb ", "queue_drained=true", "compatibility_floor"):
        assert forbidden not in branch
    for required in ("CANDIDATE_BACKEND_IMAGE_ID", "CANDIDATE_BACKEND_MANIFEST_SHA256", "PRIOR_SOURCE_ARCHIVE_SHA256", "CANDIDATE_SOURCE_ARCHIVE_SHA256"):
        assert required in branch


def test_historical_build48_installer_branch_is_byte_identical():
    current = INSTALLER.read_text()
    immutable = subprocess.check_output(["git", "show", "4058fb29e70789a9bb3fa62b2d580330b25db8a3:infra/scripts/install-on-vm.sh"], cwd=ROOT, text=True)
    marker = 'elif [ "$CANDIDATE_APP_VERSION" = 3.1.40 ]'
    end = 'elif [ "$CANDIDATE_APP_VERSION" = 3.1.39 ]'
    assert current.split(marker, 1)[1].split(end, 1)[0] == immutable.split(marker, 1)[1].split(end, 1)[0]
