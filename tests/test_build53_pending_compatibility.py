"""Failure-first checks for the exact Build50-to-Build53 profile."""

from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import shutil
import subprocess
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/verify-build53-pending-compatibility.py"
BUILD52_HELPER = ROOT / "infra/scripts/verify-build52-pending-compatibility.py"
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"
RUNTIME_IMAGE_VERIFIER = ROOT / "infra/scripts/verify-production-runtime-images.sh"


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def verifier():
    return load(HELPER, "build53_pending_compatibility")


def exact_args(verifier):
    prior = Path("prior")
    candidate = Path("candidate")
    args = SimpleNamespace(
        prior_revision=verifier.PRIOR_REVISION,
        prior_version=verifier.PRIOR_VERSION,
        prior_db_head=verifier.PRIOR_DATABASE_HEAD,
        candidate_version=verifier.CANDIDATE_VERSION,
        candidate_db_head=verifier.CANDIDATE_DATABASE_HEAD,
        prior_source_archive_sha256=verifier.PRIOR_SOURCE_ARCHIVE_SHA256,
        prior_backend_manifest_sha256=verifier.PRIOR_BACKEND_MANIFEST_SHA256,
        candidate_backend_manifest_sha256=verifier.CANDIDATE_BACKEND_MANIFEST_SHA256,
        prior_image_id=verifier.PRIOR_BACKEND_IMAGE_ID,
        candidate_image_id="sha256:" + "b" * 64,
        candidate_revision="b" * 40,
        candidate_source_archive_sha256="b" * 64,
        prior_source_root=prior,
        candidate_source_root=candidate,
    )
    manifests = {
        prior: verifier.PRIOR_BACKEND_MANIFEST_SHA256,
        candidate: verifier.CANDIDATE_BACKEND_MANIFEST_SHA256,
    }
    return args, SimpleNamespace(_backend_manifest_sha256=manifests.__getitem__)


def test_profile_reuses_only_the_exact_immutable_build52_proof(verifier):
    assert hashlib.sha256(BUILD52_HELPER.read_bytes()).hexdigest() == (
        verifier.BUILD52_PROFILE_SHA256
    )
    assert verifier.BUILD52_PROFILE_SHA256 == (
        "fd850d3d08341c93166dbf89ead46f57c93c5f12670221afe2fee8f2a5b69dd2"
    )
    assert (verifier.CANDIDATE_VERSION, verifier.CANDIDATE_DATABASE_HEAD) == (
        "3.1.45",
        "0091",
    )
    assert verifier.CANDIDATE_RELEASE_FROZEN is True
    assert (
        verifier.EXPECTED_INSTALLATION_ROWS,
        verifier.EXPECTED_PENDING,
        verifier.EXPECTED_NONZERO,
    ) == (13, 2, 2)


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("prior_revision", "3093f62971d16e5d80408a436959e7cc5ccbf318"),
        ("prior_version", "3.1.41"),
        ("prior_db_head", "0091"),
        ("candidate_version", "3.1.44"),
        ("candidate_version", "3.1.46"),
        ("candidate_db_head", "0092"),
        ("candidate_backend_manifest_sha256", "0" * 64),
        ("candidate_revision", "HEAD"),
    ),
)
def test_identity_gate_rejects_every_nonexact_substitution(verifier, field, value):
    args, build50 = exact_args(verifier)
    verifier._verify_identity(args, build50)
    setattr(args, field, value)
    with pytest.raises(verifier.CompatibilityError):
        verifier._verify_identity(args, build50)


def test_protected_source_and_backend_manifest_match_reviewed_worktree(
    verifier, tmp_path
):
    prior = tmp_path / "prior"
    prior.mkdir()
    archive = tmp_path / "prior.tar"
    with archive.open("wb") as output:
        result = subprocess.run(
            ["git", "archive", "--format=tar", verifier.PRIOR_REVISION],
            cwd=ROOT,
            stdout=output,
            check=False,
        )
    assert result.returncode == 0
    subprocess.run(["tar", "-xf", archive, "-C", prior], check=True)
    candidate = tmp_path / "candidate"
    candidate.mkdir()
    release_paths = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    ).stdout.split(b"\0")
    for raw_path in release_paths:
        if not raw_path:
            continue
        relative = Path(raw_path.decode("utf-8"))
        source = ROOT / relative
        destination = candidate / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination, follow_symlinks=False)
    build50, _, _ = verifier._load_modules(ROOT)
    proof = verifier._verify_sources(build50, prior, candidate)
    assert proof["prior_protected_path_count"] == 1217
    assert proof["candidate_protected_path_count"] == 1285
    assert proof["reviewed_changed_path_count"] == 186
    assert (
        build50._backend_manifest_sha256(candidate)
        == verifier.CANDIDATE_BACKEND_MANIFEST_SHA256
    )


def test_backend_write_surface_is_bounded_to_reviewed_audit_delta():
    changed = set(
        subprocess.run(
            [
                "git",
                "diff",
                "--name-only",
                "f78d567b2093671992be289133af22637e6d7b0d",
                "--",
                "backend/app",
            ],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    changed.update(
        subprocess.run(
            ["git", "diff", "--name-only", "--", "backend/app"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert changed == {
        "backend/app/__init__.py",
        "backend/app/api/v1/admin/router.py",
        "backend/app/api/v1/pos/router.py",
        "backend/app/services/audit/catalogue.py",
        "backend/app/services/audit/query.py",
        "backend/app/services/audit/recorder.py",
    }


def test_installer_selects_build53_without_a_version_range():
    source = INSTALLER.read_text(encoding="utf-8")
    assert '[ "$CANDIDATE_APP_VERSION" = 3.1.45 ]' in source
    assert 'EXACT_BUILD50_VERIFIER="$BUILD53_PENDING_COMPATIBILITY_VERIFIER"' in source
    assert source.count("BUILD53_PENDING_CARRIED_UNRESOLVED=true") == 1
    assert source.count("BUILD53_MIGRATION_RUNTIME_VERIFIED=true") == 1
    assert source.count("BUILD53_POST_PROOF_SUCCEEDED=true") == 1
    assert "3.1.4*" not in source
    assert 'EXACT_BUILD50_VERIFIER="$BUILD52_PENDING_COMPATIBILITY_VERIFIER"' in source
    assert '[ "$PRIOR_DB_HEAD" = 0089 ]' in source
    assert '[ "$PRIOR_REVISION" = 29e32b440ab8dc162b6c8f5d40f8283fad53ce04 ]' in source


def test_runtime_gate_names_only_build52_and_build53():
    source = RUNTIME_IMAGE_VERIFIER.read_text(encoding="utf-8")
    assert "3.1.44) EXACT_MIGRATION_BUILD=Build52" in source
    assert "3.1.45) EXACT_MIGRATION_BUILD=Build53" in source
    assert "3.1.46)" not in source
    migration = source.index("--entrypoint python backend -m alembic upgrade head")
    normal_start = source.index(
        "up -d --no-build --wait --wait-timeout 180 \\\n  postgres redis backend frontend"
    )
    assert migration < normal_start


def test_profile_keeps_unknown_work_explicit(verifier):
    source = BUILD52_HELPER.read_text(encoding="utf-8")
    for literal in (
        '"queue_drained": False',
        '"tablet_queue_contents_verified": False',
        '"telemetry_rewritten": False',
        '"installation_retired": False',
        '"unknown_preserved_work"',
    ):
        assert literal in source
    assert "allow-pending" not in HELPER.read_text(encoding="utf-8")
