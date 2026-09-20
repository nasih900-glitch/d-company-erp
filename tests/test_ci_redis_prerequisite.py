from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "infra" / "scripts" / "install-ci-redis.sh"
REDIS_SHA256 = "3c266ece0abd54ed3b1c912c6eb86b7508cf382cb690ee6649d3843f018f6357"


def _write_executable(path: Path, source: str) -> None:
    path.write_text(source, encoding="utf-8")
    path.chmod(0o755)


def _fake_commands(tmp_path: Path) -> Path:
    commands = tmp_path / "commands"
    commands.mkdir()
    _write_executable(
        commands / "curl",
        """#!/bin/bash
set -eu
printf 'curl %s\\n' "$*" >>"$FAKE_COMMAND_LOG"
[ "${FAKE_CURL_MODE:-ok}" != fail ] || exit 22
output=
while [ "$#" -gt 0 ]; do
  if [ "$1" = --output ]; then
    shift
    output=$1
  fi
  shift
done
[ -n "$output" ]
printf 'bounded archive fixture' >"$output"
""",
    )
    _write_executable(
        commands / "sha256sum",
        f"""#!/bin/bash
set -eu
printf 'sha256sum %s\\n' "$*" >>"$FAKE_COMMAND_LOG"
if [ "${{FAKE_SHA_MODE:-ok}}" = wrong ]; then
  printf '%064d  %s\\n' 0 "$1"
else
  printf '{REDIS_SHA256}  %s\\n' "$1"
fi
""",
    )
    _write_executable(
        commands / "tar",
        """#!/bin/bash
set -eu
printf 'tar %s\\n' "$*" >>"$FAKE_COMMAND_LOG"
directory=
while [ "$#" -gt 0 ]; do
  if [ "$1" = --directory ]; then
    shift
    directory=$1
  fi
  shift
done
[ -n "$directory" ]
mkdir -p "$directory/redis-7.4.11/src"
: >"$directory/redis-7.4.11/Makefile"
""",
    )
    _write_executable(
        commands / "make",
        """#!/bin/bash
set -eu
printf 'make %s\\n' "$*" >>"$FAKE_COMMAND_LOG"
[ "${FAKE_MAKE_MODE:-ok}" != fail ] || exit 2
directory=
while [ "$#" -gt 0 ]; do
  if [ "$1" = --directory ]; then
    shift
    directory=$1
  fi
  shift
done
[ -n "$directory" ]
cat >"$directory/src/redis-server" <<'SERVER'
#!/bin/bash
printf 'redis-server %s\\n' "$*" >>"$FAKE_COMMAND_LOG"
if [ "${FAKE_REDIS_MODE:-ok}" = fail ]; then
  exit 9
fi
if [ "${FAKE_REDIS_MODE:-ok}" = wrong ]; then
  echo 'Redis server v=7.4.10 sha=00000000:0 malloc=libc bits=64 build=test'
else
  echo 'Redis server v=7.4.11 sha=00000000:0 malloc=libc bits=64 build=test'
fi
SERVER
chmod 0755 "$directory/src/redis-server"
""",
    )
    return commands


def _environment(tmp_path: Path) -> tuple[dict[str, str], Path, Path]:
    commands = _fake_commands(tmp_path)
    runner_temp = tmp_path / "runner-temp"
    runner_temp.mkdir()
    github_path = tmp_path / "github-path"
    github_path.touch()
    log = tmp_path / "commands.log"
    env = os.environ.copy()
    env.update(
        {
            "CI": "true",
            "GITHUB_ACTIONS": "true",
            "RUNNER_TEMP": str(runner_temp),
            "GITHUB_PATH": str(github_path),
            "FAKE_COMMAND_LOG": str(log),
            "PATH": f"{commands}{os.pathsep}{env['PATH']}",
        }
    )
    return env, runner_temp, github_path


def _run(env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["bash", str(INSTALLER)],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        check=False,
        timeout=10,
    )


def test_installer_publishes_only_verified_private_redis_binary(tmp_path: Path) -> None:
    env, runner_temp, github_path = _environment(tmp_path)

    result = _run(env)

    assert result.returncode == 0, result.stderr
    published_lines = github_path.read_text(encoding="utf-8").splitlines()
    assert len(published_lines) == 1
    published = Path(published_lines[0])
    assert published.parent == runner_temp
    assert published.name.startswith("dcompany-ci-redis-bin.")
    assert [path.name for path in published.iterdir()] == ["redis-server"]
    assert (published / "redis-server").is_file()
    assert os.access(published / "redis-server", os.X_OK)
    assert not list(runner_temp.glob("dcompany-ci-redis-build.*"))

    command_log = Path(env["FAKE_COMMAND_LOG"]).read_text(encoding="utf-8")
    assert "--proto =https" in command_log
    assert "--proto-redir =https" in command_log
    assert "--connect-timeout 15" in command_log
    assert "--max-time 120" in command_log
    assert "--max-filesize 8388608" in command_log
    assert "make --directory " in command_log
    assert "--jobs 2 MALLOC=libc BUILD_TLS=no redis-server" in command_log
    assert command_log.count("redis-server --version") == 1


@pytest.mark.parametrize(
    ("removed", "replacement", "diagnostic"),
    [
        ("RUNNER_TEMP", None, "RUNNER_TEMP and GITHUB_PATH are required"),
        ("GITHUB_PATH", None, "RUNNER_TEMP and GITHUB_PATH are required"),
        ("CI", "false", "restricted to GitHub Actions CI"),
        ("GITHUB_ACTIONS", "false", "restricted to GitHub Actions CI"),
        ("RUNNER_TEMP", "relative-runner-temp", "RUNNER_TEMP must be an absolute path"),
        ("GITHUB_PATH", "relative-github-path", "GITHUB_PATH must be an absolute path"),
    ],
)
def test_installer_rejects_missing_or_malformed_ci_setup(
    tmp_path: Path,
    removed: str,
    replacement: str | None,
    diagnostic: str,
) -> None:
    env, runner_temp, github_path = _environment(tmp_path)
    if replacement is None:
        env.pop(removed)
    else:
        env[removed] = replacement

    result = _run(env)

    assert result.returncode != 0
    assert diagnostic in result.stderr
    assert github_path.read_text(encoding="utf-8") == ""
    assert not list(runner_temp.iterdir())


@pytest.mark.parametrize(
    ("variable", "value", "diagnostic"),
    [
        ("FAKE_CURL_MODE", "fail", "download"),
        ("FAKE_SHA_MODE", "wrong", "checksum mismatch"),
        ("FAKE_MAKE_MODE", "fail", "Redis build"),
        ("FAKE_REDIS_MODE", "fail", "version check failed"),
        ("FAKE_REDIS_MODE", "wrong", "is not Redis 7.4.11"),
    ],
)
def test_installer_failure_never_publishes_or_retains_partial_output(
    tmp_path: Path,
    variable: str,
    value: str,
    diagnostic: str,
) -> None:
    env, runner_temp, github_path = _environment(tmp_path)
    env[variable] = value

    result = _run(env)

    assert result.returncode != 0
    assert diagnostic in result.stderr
    assert github_path.read_text(encoding="utf-8") == ""
    assert not list(runner_temp.iterdir())


@pytest.mark.parametrize("workflow_name", ["ci.yml", "release.yml"])
def test_backend_workflow_installs_redis_after_prerequisites_before_tests(
    workflow_name: str,
) -> None:
    workflow = (ROOT / ".github" / "workflows" / workflow_name).read_text(
        encoding="utf-8"
    )
    if workflow_name == "ci.yml":
        backend = workflow[workflow.index("  backend:"):workflow.index("  frontend:")]
    else:
        backend = workflow[
            workflow.index("  coordinated-release-gates:"):
            workflow.index("  production-image-gates:")
        ]

    prerequisite = backend.index("      - name: Install physical-audit contract prerequisites")
    redis = backend.index("      - name: Install pinned CI Redis prerequisite")
    repository_tests = backend.index("      - name: Run complete repository contract suite")
    repository_test_command = backend.index(
        "        run: python -m pytest tests", repository_tests
    )
    if workflow_name == "ci.yml":
        backend_tests = backend.index("      - name: Tests")
        backend_test_command = backend.index(
            "        run: cd backend && pytest", backend_tests
        )
    else:
        backend_tests = backend.index("      - name: Run complete backend suite")
        backend_test_command = backend.index("        run: pytest", backend_tests)
    assert (
        prerequisite
        < redis
        < repository_tests
        < repository_test_command
        < backend_tests
        < backend_test_command
    )
    assert backend.count("bash infra/scripts/install-ci-redis.sh") == 1
    redis_step = backend[redis:repository_tests]
    assert "continue-on-error" not in redis_step


def test_installer_contract_is_pinned_bounded_and_unprivileged() -> None:
    source = INSTALLER.read_text(encoding="utf-8")
    assert "REDIS_VERSION=7.4.11" in source
    assert REDIS_SHA256 in source
    assert "https://download.redis.io/releases/" in source
    assert "--proto '=https'" in source
    assert "--proto-redir '=https'" in source
    assert "--max-filesize \"$MAX_ARCHIVE_BYTES\"" in source
    assert "MALLOC=libc BUILD_TLS=no redis-server" in source
    assert '"$BIN_DIR/redis-server" --version' in source
    assert '>>"$GITHUB_PATH"' in source
    assert "sudo" not in source
    assert "--daemonize" not in source
