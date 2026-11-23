"""The unpublished successor cannot enter a privileged installer path."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess

import pytest


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "infra/scripts/install-on-vm.sh"


def _preflight() -> str:
    source = INSTALLER.read_text()
    return source.split("# BEGIN UNREVIEWED SUCCESSOR PREFLIGHT\n", 1)[1].split(
        "# END UNREVIEWED SUCCESSOR PREFLIGHT", 1
    )[0]


def _run(tmp_path: Path, script_version: str, repo_version: str, *, template: str | None = None,
         environment: dict | None = None, existing_env: bool = False):
    source = tmp_path / "source"
    repo = tmp_path / "repository"
    commands = tmp_path / "bin"
    scripts = source / "infra/scripts"
    scripts.mkdir(parents=True)
    repo.mkdir()
    commands.mkdir()
    (scripts / INSTALLER.name).write_bytes(INSTALLER.read_bytes())
    (source / ".env.production.example").write_text(
        template if template is not None else f"APP_VERSION={script_version}\n"
    )
    if existing_env:
        (repo / ".env").write_text("synthetic existing environment\n")
    trace = tmp_path / "git-trace"
    git = commands / "git"
    git.write_text(
        "#!/bin/sh\n"
        "printf '%s\\n' \"$*\" >> \"$HOLD_GIT_TRACE\"\n"
        "case \"$*\" in\n"
        " 'show HEAD:.env.production.example') printf 'APP_VERSION=%s\\n' \"$HOLD_REPO_VERSION\";;\n"
        " 'show HEAD:infra/scripts/install-on-vm.sh') cat \"$HOLD_SCRIPT\";;\n"
        " *) exit 99;;\nesac\n"
    )
    git.chmod(0o755)
    return subprocess.run(
        ["bash", "-c", "set -euo pipefail\n" + _preflight() + "\nprintf 'preflight passed\\n'"],
        env={
            **os.environ,
            "PATH": f"{commands}{os.pathsep}{os.defpath}",
            "SCRIPT_SOURCE_ROOT": str(source),
            "REPO_DIR": str(repo),
            "HOLD_GIT_TRACE": str(trace),
            "HOLD_REPO_VERSION": repo_version,
            "HOLD_SCRIPT": str(scripts / INSTALLER.name),
            "APP_VERSION": "3.1.64",
            **(environment or {}),
        },
        cwd=repo,
        capture_output=True,
        text=True,
        check=False,
    ), trace


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.77", "3.1.64"), ("3.1.64", "3.1.77")])
def test_either_executing_or_repository_successor_source_closes_admission(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version)
    assert result.returncode == 1
    assert "Build72 production admission is closed" in result.stderr
    assert "preflight passed" not in result.stdout
    if script_version == "3.1.77":
        assert not trace.exists()
        assert "Redis ACTIVE_REDIS_REPAIR.json / CONFIG_RECREATION_HOLD.json" in result.stderr
    else:
        assert trace.read_text() == "show HEAD:.env.production.example\n"


def test_matching_historical_sources_are_not_selected_as_the_successor(tmp_path):
    result, _ = _run(tmp_path, "3.1.64", "3.1.64")
    assert result.returncode == 0, result.stderr
    assert result.stdout == "preflight passed\n"


def test_disagreeing_historical_source_identities_are_refused(tmp_path):
    result, _ = _run(tmp_path, "3.1.63", "3.1.64")
    assert result.returncode == 1
    assert "source identities differ" in result.stderr


@pytest.mark.parametrize(("existing", "maintenance", "legacy", "allowed"), [
    (False, "true", "", False),
    (True, "false", "", False),
    (True, "true", "legacy-mode", False),
    (True, "true", "", True),
])
def test_explicit_build72_route_requires_existing_environment_and_no_other_upgrade_flags(
    tmp_path, existing, maintenance, legacy, allowed,
):
    result, _ = _run(tmp_path, "3.1.77", "3.1.77", existing_env=existing, environment={
        "BUILD72_UPGRADE_EVIDENCE": "/private/reviewed.json",
        "MAINTENANCE_CONFIRMED": maintenance, "LEGACY_CODE16_REVISION": legacy,
    })
    assert (result.returncode == 0) is allowed
    if not allowed:
        assert "Build72 production admission is closed" in result.stderr


@pytest.mark.parametrize("template", ["", "APP_VERSION=3.1.77\nAPP_VERSION=3.1.64\n", "APP_VERSION=3.1.77 # override\n"])
def test_missing_or_ambiguous_source_identity_is_not_a_fresh_install_bypass(tmp_path, template):
    result, trace = _run(tmp_path, "unused", "3.1.64", template=template)
    assert result.returncode == 1
    assert "missing, malformed or linked" in result.stderr
    assert not trace.exists()


def test_inherited_metadata_validation_precedes_hold_and_mutating_bootstrap():
    source = INSTALLER.read_text()
    metadata = source.index("lock_fd_metadata=")
    hold = source.index("# BEGIN UNREVIEWED SUCCESSOR PREFLIGHT")
    helper = source.index('exec python3 "$SCRIPT_SOURCE_ROOT/infra/scripts/production_install_lock.py"')
    acquire = source.index("flock -n 9")
    snapshot = source.index('CANDIDATE_BUILD_ROOT=$(mktemp -d')
    assert metadata < hold < helper < acquire < snapshot
    assert source.index("# END UNREVIEWED SUCCESSOR PREFLIGHT") < source.index("# ----- 1. Docker -----")


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.68", "3.1.68"), ("3.1.77", "3.1.68"), ("3.1.68", "3.1.77")])
def test_superseded_build75_cannot_enter_generic_installation(tmp_path, script_version, repo_version):
    result, _ = _run(tmp_path, script_version, repo_version,
                     environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded tagged candidate" in result.stderr + result.stdout


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.69", "3.1.69"), ("3.1.77", "3.1.69"), ("3.1.69", "3.1.77")])
def test_superseded_build76_installer_refuses_before_mutation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded signed candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.70", "3.1.70"), ("3.1.77", "3.1.70"), ("3.1.70", "3.1.77")])
def test_superseded_build77_installer_refuses_before_mutation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded tagged candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.71", "3.1.71"), ("3.1.77", "3.1.71"), ("3.1.71", "3.1.77")])
def test_superseded_build78_installer_refuses_before_mutation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded tagged candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.72", "3.1.72"), ("3.1.77", "3.1.72"), ("3.1.72", "3.1.77")])
def test_superseded_build79_installer_refuses_before_mutation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded tagged candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.73", "3.1.73"), ("3.1.77", "3.1.73"), ("3.1.73", "3.1.77")])
def test_predecessor_build80_cannot_enter_fallback_installation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "predecessor candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.74", "3.1.74"), ("3.1.77", "3.1.74"), ("3.1.74", "3.1.77")])
def test_superseded_build81_cannot_enter_fallback_installation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded tagged candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.75", "3.1.75"), ("3.1.77", "3.1.75"), ("3.1.75", "3.1.77")])
def test_superseded_build82_cannot_enter_fallback_installation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded tagged candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


@pytest.mark.parametrize(("script_version", "repo_version"), [
    ("3.1.76", "3.1.76"),
    ("3.1.77", "3.1.76"),
    ("3.1.76", "3.1.77"),
])
def test_superseded_build83_cannot_enter_fallback_installation(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version,
                         environment={"BUILD72_UPGRADE_EVIDENCE": "/not-consumed"})
    assert result.returncode == 1
    assert "superseded signed candidate" in result.stderr + result.stdout
    assert trace.read_text().splitlines() == ["show HEAD:.env.production.example"]


def test_runtime_verifier_modes_survive_private_source_extraction(tmp_path):
    snapshot = tmp_path.resolve() / "candidate"
    snapshot.mkdir(mode=0o700)
    modes = {
        "infra/docker/caddy-zlib/verify-official-zlib.sh": 0o755,
        "infra/docker/verify-backend-runtime-profile.py": 0o644,
        "infra/docker/openssl/verify-backport-runtime.sh": 0o755,
        "infra/docker/openssl/verify-vendor-r1.sh": 0o755,
    }
    for name, mode in modes.items():
        path = snapshot / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("frozen verifier bytes\n")
        path.chmod(mode & 0o700)
    unrelated = snapshot / "unrelated"
    unrelated.write_text("private test data\n")
    unrelated.chmod(0o600)

    source = INSTALLER.read_text()
    start = source.index('  candidate_zlib_verifier=')
    end = source.index('  rm -f "$CANDIDATE_SOURCE_ARCHIVE"', start)
    subprocess.run(["bash", "-e", "-c", source[start:end]], check=True, env={
        **os.environ, "CANDIDATE_BUILD_ROOT": str(snapshot),
    })

    for name, mode in modes.items():
        path = snapshot / name
        assert path.stat().st_mode & 0o777 == mode
        assert path.read_text() == "frozen verifier bytes\n"
    assert snapshot.stat().st_mode & 0o777 == 0o700
    assert unrelated.stat().st_mode & 0o777 == 0o600
