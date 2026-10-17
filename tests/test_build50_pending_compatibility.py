from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build50-pending-compatibility.py"
BUILD49_HELPER = ROOT / "infra/scripts/verify-build49-pending-compatibility.py"
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
    return _load(HELPER, "build50_pending_compatibility")


def _extract(ref: str, destination: Path) -> bytes:
    raw = subprocess.check_output(["git", "archive", "--format=tar", ref], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        for member in archive.getmembers():
            path = Path(member.name)
            assert not path.is_absolute() and ".." not in path.parts
        archive.extractall(destination, filter="data")
    return raw


def _candidate_snapshot(helper, destination: Path) -> None:
    _extract(helper.PRIOR_REVISION, destination)
    changed = set(
        subprocess.check_output(
            ["git", "diff", "--name-only", "-z", helper.PRIOR_REVISION], cwd=ROOT
        ).decode().split("\0")
    )
    changed.update(
        subprocess.check_output(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT
        ).decode().split("\0")
    )
    for relative_name in sorted(name for name in changed if name):
        relative = Path(relative_name)
        source = ROOT / relative
        target = destination / relative
        if source.is_symlink():
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                target.unlink()
            target.symlink_to(os.readlink(source))
        elif source.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        else:
            target.unlink(missing_ok=True)


def test_exact_immutable_build49_to_build50_source_delta(helper, tmp_path):
    assert hashlib.sha256(BUILD47_HELPER.read_bytes()).hexdigest() == helper.HISTORICAL_HELPER_SHA256
    assert hashlib.sha256(BUILD48_HELPER.read_bytes()).hexdigest() == helper.IMMUTABLE_BUILD48_HELPER_SHA256
    assert hashlib.sha256(BUILD49_HELPER.read_bytes()).hexdigest() == helper.IMMUTABLE_BUILD49_HELPER_SHA256
    prior = tmp_path / "prior"
    candidate = tmp_path / "candidate"
    prior.mkdir()
    candidate.mkdir()
    prior_archive = _extract(helper.PRIOR_REVISION, prior)
    _candidate_snapshot(helper, candidate)

    proof = helper._verify_sources(prior, candidate)

    assert hashlib.sha256(prior_archive).hexdigest() == helper.PRIOR_SOURCE_ARCHIVE_SHA256
    assert proof == {
        "prior_protected_source_sha256": helper.PRIOR_SOURCE_SHA256,
        "candidate_protected_source_sha256": helper.CANDIDATE_SOURCE_SHA256,
        "protected_path_count": 1216,
        "reviewed_changed_path_count": 28,
    }
    assert helper._backend_manifest_sha256(prior) == helper.PRIOR_BACKEND_MANIFEST_SHA256
    assert helper._backend_manifest_sha256(candidate) == helper.CANDIDATE_BACKEND_MANIFEST_SHA256


def test_unreviewed_protected_candidate_path_is_rejected(helper, tmp_path):
    prior = tmp_path / "prior"
    candidate = tmp_path / "candidate"
    prior.mkdir()
    candidate.mkdir()
    _extract(helper.PRIOR_REVISION, prior)
    _candidate_snapshot(helper, candidate)
    extra = candidate / "backend/app/unreviewed_build50_path.py"
    extra.write_text("unexpected = True\n", encoding="utf-8")

    with pytest.raises(
        helper.CompatibilityError,
        match="unreviewed runtime bytes or paths",
    ):
        helper._verify_sources(prior, candidate)


def test_fresh_fleet_binding_is_exact_and_pending_remains_opaque(helper):
    assert helper.PRIOR_REVISION == "a834b63d32c45513ab5c8fd9d4de417acc0a96c6"
    assert (helper.PRIOR_VERSION, helper.CANDIDATE_VERSION) == ("3.1.41", "3.1.42")
    assert (helper.PRIOR_DATABASE_HEAD, helper.CANDIDATE_DATABASE_HEAD) == ("0088", "0089")
    assert (helper.EXPECTED_INSTALLATION_ROWS, helper.EXPECTED_PENDING, helper.EXPECTED_NONZERO) == (12, 2, 2)
    assert helper.EXPECTED_ALL_INSTALLATIONS_IDENTITY_SHA256 == "d741c0d407f2b29c687f7b41ec7276a93f19853e99fc279fb65d0f5aec03d030"
    assert helper.EXPECTED_NONZERO_FLEET_SHA256 == "5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128"
    source = HELPER.read_text()
    assert '"status": "pending_carried_unresolved"' in source
    assert '"historic_pending_work_classification": "unknown_preserved_work"' in source
    assert '"queue_drained": False' in source
    assert '"installation_retired": False' in source
    assert '"tablet_queue_contents_verified": False' in source


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
        ("prior_version", "3.1.40"),
        ("prior_db_head", "0087"),
        ("candidate_version", "3.1.41"),
        ("candidate_db_head", "0088"),
        ("candidate_revision", "a834b63d32c45513ab5c8fd9d4de417acc0a96c6"),
        ("prior_image_id", "backend:latest"),
        ("candidate_image_id", "sha256:" + "b" * 64),
        ("prior_backend_manifest_sha256", "0" * 64),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("prior_source_archive_sha256", "0" * 64),
        ("candidate_source_archive_sha256", "a3cc663d594e2d5a40b4d03fd6265623c5e7ac12676f5f34ce3b298fc6caab53"),
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
def test_build49_helper_lineage_guard_fails_closed(helper, tmp_path, mutation):
    root = tmp_path / "candidate"
    path = root / helper.IMMUTABLE_BUILD49_HELPER
    path.parent.mkdir(parents=True)
    if mutation == "bytes":
        path.write_bytes(BUILD49_HELPER.read_bytes() + b"\n")
    else:
        path.symlink_to(BUILD49_HELPER)
    with pytest.raises(helper.CompatibilityError, match="build49 verifier changed"):
        helper._verify_immutable_build49_helper(root)


def test_installer_has_one_exact_fail_closed_build50_path():
    source = INSTALLER.read_text()
    assert source.count("BUILD50_PENDING_CARRIED_UNRESOLVED=true") == 1
    assert source.count('python3 "$BUILD50_PENDING_COMPATIBILITY_VERIFIER"') == 1
    assert source.count('[ "$CANDIDATE_APP_VERSION" = 3.1.42 ]') == 1
    assert '[ "$PRIOR_APP_VERSION" = 3.1.41 ]' in source
    assert '[ "$PRIOR_REVISION" = a834b63d32c45513ab5c8fd9d4de417acc0a96c6 ]' in source
    assert '${BUILD50_PENDING_CARRIED_UNRESOLVED:-false}' in source
    assert 'if [ "$pending_outbox_count" -ne 0 ] && [ "$accepted_pending_paths" -ne 1 ]' in source
    branch = source.split('elif [ "$CANDIDATE_APP_VERSION" = 3.1.42 ]', 1)[1].split('elif [ "$CANDIDATE_APP_VERSION" = 3.1.41 ]', 1)[0]
    assert '--candidate-db-head 0089' in branch
    for forbidden in ("UPDATE tablet_installations", "DELETE FROM", "adb ", "queue_drained=true", "compatibility_floor"):
        assert forbidden not in branch
    for required in ("CANDIDATE_BACKEND_IMAGE_ID", "CANDIDATE_BACKEND_MANIFEST_SHA256", "PRIOR_SOURCE_ARCHIVE_SHA256", "CANDIDATE_SOURCE_ARCHIVE_SHA256"):
        assert required in branch


def test_historical_build49_installer_branch_is_byte_identical():
    current = INSTALLER.read_text()
    immutable = subprocess.check_output(
        ["git", "show", "a834b63d32c45513ab5c8fd9d4de417acc0a96c6:infra/scripts/install-on-vm.sh"],
        cwd=ROOT,
        text=True,
    )
    marker = 'elif [ "$CANDIDATE_APP_VERSION" = 3.1.41 ]'
    end = 'elif [ "$CANDIDATE_APP_VERSION" = 3.1.40 ]'
    assert current.split(marker, 1)[1].split(end, 1)[0] == immutable.split(marker, 1)[1].split(end, 1)[0]
