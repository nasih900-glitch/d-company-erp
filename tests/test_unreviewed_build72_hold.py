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


@pytest.mark.parametrize(("script_version", "repo_version"), [("3.1.65", "3.1.64"), ("3.1.64", "3.1.65")])
def test_either_executing_or_repository_successor_source_closes_admission(tmp_path, script_version, repo_version):
    result, trace = _run(tmp_path, script_version, repo_version)
    assert result.returncode == 1
    assert "Build72 production admission is closed" in result.stderr
    assert "preflight passed" not in result.stdout
    if script_version == "3.1.65":
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
    result, _ = _run(tmp_path, "3.1.65", "3.1.65", existing_env=existing, environment={
        "BUILD72_UPGRADE_EVIDENCE": "/private/reviewed.json",
        "MAINTENANCE_CONFIRMED": maintenance, "LEGACY_CODE16_REVISION": legacy,
    })
    assert (result.returncode == 0) is allowed
    if not allowed:
        assert "Build72 production admission is closed" in result.stderr


@pytest.mark.parametrize("template", ["", "APP_VERSION=3.1.65\nAPP_VERSION=3.1.64\n", "APP_VERSION=3.1.65 # override\n"])
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
