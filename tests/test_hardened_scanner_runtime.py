from __future__ import annotations

import json
import hashlib
import os
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tests" / "fixtures"))

from image_archive_identity_fixtures import (  # noqa: E402
    classic_config_id,
    write_classic_archive,
    write_oci_index_archive,
)


SCANNER = ROOT / "infra" / "scripts" / "run-hardened-image-scanners.sh"
SYFT_IMAGE = (
    "anchore/syft:v1.42.3@sha256:"
    "5999d209a342e55e9edf70bf8930fb5b86d8f2a783fa401178372c50e21b1d36"
)
GRYPE_IMAGE = (
    "anchore/grype:v0.118.0@sha256:"
    "8a93fc48da96bd6ec5981279d099b69de11541dc68fdf222fb9161f8ff284af7"
)
IMAGE_ID = classic_config_id()


def _write_executable(path: Path, source: str) -> None:
    path.write_text(source, encoding="utf-8")
    path.chmod(0o755)


def _fake_commands(tmp_path: Path) -> tuple[Path, Path]:
    bin_dir = tmp_path / "bin"
    state = tmp_path / "state"
    bin_dir.mkdir()
    state.mkdir()

    _write_executable(
        bin_dir / "docker",
        """#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

state = Path(os.environ["FAKE_SCANNER_STATE"])
args = sys.argv[1:]
with (state / "docker-calls.jsonl").open("a", encoding="utf-8") as stream:
    stream.write(json.dumps(args) + "\\n")

if args[0] == "create":
    counter_path = state / "counter"
    counter = int(counter_path.read_text() if counter_path.exists() else "0") + 1
    counter_path.write_text(str(counter))
    container_id = f"{counter:064x}"
    name = args[args.index("--name") + 1]
    label = args[args.index("--label") + 1].split("=", 1)[1]
    if "--entrypoint" in args:
        kind = "probe"
    elif any(value.startswith("anchore/syft:") for value in args):
        kind = "syft"
    else:
        kind = "grype"
    (state / f"container-{container_id}").write_text(kind)
    (state / f"name-{name}").write_text(container_id)
    (state / f"ownership-{container_id}").write_text(f"{label}|{name}")
    if os.environ.get("FAKE_SCANNER_LOSE_CREATE_RESPONSE") == kind:
        raise SystemExit(124)
    print(container_id)
elif args[0] == "start":
    container_id = args[-1]
    name_path = state / f"name-{container_id}"
    if name_path.exists():
        container_id = name_path.read_text()
    kind = (state / f"container-{container_id}").read_text()
    if os.environ.get("FAKE_SCANNER_RUNNING") == kind:
        (state / f"running-{container_id}").write_text("true")
    if os.environ.get("FAKE_SCANNER_TIMEOUT") == kind:
        raise SystemExit(124)
    if os.environ.get("FAKE_SCANNER_OOM") == kind:
        raise SystemExit(137)
    if os.environ.get("FAKE_SCANNER_FAIL") == kind:
        raise SystemExit(23)
    if kind == "probe":
        print("scanner_cache_mount_verified=true")
    elif kind == "syft":
        print(json.dumps({
            "artifacts": [],
            "descriptor": {"name": "syft", "version": "1.42.3"},
            "source": {"type": "image", "metadata": {
                "imageID": os.environ.get("FAKE_SYFT_IMAGE_ID", os.environ["FAKE_IMAGE_ID"]),
                "architecture": "",
                "os": "",
                "manifestDigest": "sha256:" + "e" * 64,
            }},
        }))
    else:
        valid = os.environ.get("FAKE_GRYPE_VALID", "true") == "true"
        image_id = os.environ.get("FAKE_GRYPE_IMAGE_ID", os.environ["FAKE_IMAGE_ID"])
        status = {
            "built": "2026-09-09T00:33:07Z",
            "schemaVersion": "v6.1.9",
            "valid": valid,
        }
        if os.environ.get("FAKE_GRYPE_MISSING_STATUS") == "true":
            status = {}
        report = {
            "descriptor": {
                "name": "grype",
                "version": "0.118.0",
                "db": {"status": status},
            },
            "source": {"type": "image", "target": {"imageID": image_id}},
            "matches": [],
        }
        print(json.dumps(report))
elif args[0] == "inspect":
    template = args[args.index("--format") + 1]
    target = args[-1]
    name_path = state / f"name-{target}"
    if name_path.exists():
        target = name_path.read_text()
    kind = (state / f"container-{target}").read_text()
    inspect_failure = os.environ.get("FAKE_SCANNER_INSPECT_FAIL")
    if inspect_failure == "true" or inspect_failure == kind:
        raise SystemExit(125)
    if "scanner-invocation" in template:
        label, name = (state / f"ownership-{target}").read_text().split("|", 1)
        if os.environ.get("FAKE_SCANNER_WRONG_OWNERSHIP") == kind:
            label = "wrong-label"
        print(f"{target}|{label}|/{name}")
    elif "OOMKilled" in template:
        if os.environ.get("FAKE_SCANNER_OOM") == kind:
            print("true:137")
        elif os.environ.get("FAKE_SCANNER_FAIL") == kind:
            print("false:23")
        else:
            print("false:0")
    else:
        running = state / f"running-{target}"
        print("true" if running.exists() and running.read_text() == "true" else "false")
elif args[0] == "wait":
    if os.environ.get("FAKE_SCANNER_WAIT_FAIL") == "true":
        raise SystemExit(125)
    print("0")
elif args[0] in {"stop", "rm"}:
    if args[0] == "stop" and os.environ.get("FAKE_SCANNER_STOP_FAIL") == "true":
        raise SystemExit(125)
    if args[0] == "rm" and os.environ.get("FAKE_SCANNER_REMOVE_FAIL") == "true":
        raise SystemExit(125)
    target = args[-1]
    name_path = state / f"name-{target}"
    if name_path.exists():
        target = name_path.read_text()
    if args[0] == "stop":
        (state / f"running-{target}").write_text("false")
    if args[0] == "rm":
        ownership = state / f"ownership-{target}"
        if ownership.exists():
            _label, name = ownership.read_text().split("|", 1)
            (state / f"name-{name}").unlink(missing_ok=True)
            ownership.unlink()
        (state / f"container-{target}").unlink(missing_ok=True)
        (state / f"running-{target}").unlink(missing_ok=True)
else:
    raise SystemExit(f"unexpected docker invocation: {args}")
""",
    )
    _write_executable(
        bin_dir / "mount",
        """#!/bin/bash
printf '%s\\n' "$*" >> "$FAKE_SCANNER_STATE/mount-calls.txt"
if [ "$1" = "--bind" ] && [ "$FAKE_MOUNT_FAIL" = bind ]; then exit 32; fi
if [ "$1" = "-o" ] && [[ ",$2," == *,remount,* ]] && [ "$FAKE_MOUNT_FAIL" = remount ]; then exit 32; fi
touch "$FAKE_SCANNER_STATE/mounted"
""",
    )
    _write_executable(
        bin_dir / "findmnt",
        """#!/bin/bash
case "$*" in
  *"-o TARGET"*)
    [ "$FAKE_FINDMNT_FAIL" = true ] && exit 1
    while [ "$#" -gt 0 ]; do
      if [ "$1" = "-M" ]; then printf '%s\\n' "$2"; exit 0; fi
      shift
    done
    exit 1
    ;;
  *"-o OPTIONS"*) printf '%s\\n' 'rw,nosuid,nodev,noexec,relatime' ;;
  *) exit 1 ;;
esac
""",
    )
    _write_executable(
        bin_dir / "mountpoint",
        """#!/bin/sh
test -e "$FAKE_SCANNER_STATE/mounted"
""",
    )
    _write_executable(
        bin_dir / "umount",
        """#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_SCANNER_STATE/umount-calls.txt"
[ "$FAKE_UMOUNT_FAIL" = true ] && exit 32
rm -f "$FAKE_SCANNER_STATE/mounted"
""",
    )
    _write_executable(bin_dir / "chown", "#!/bin/sh\nexit 0\n")
    _write_executable(
        bin_dir / "timeout",
        """#!/bin/bash
while [ "$#" -gt 0 ]; do
  case "$1" in
    --foreground) shift ;;
    --signal|-s|--kill-after|-k) shift 2 ;;
    --signal=*|--kill-after=*) shift ;;
    *s) shift; break ;;
    *) break ;;
  esac
done
exec "$@"
""",
    )
    _write_executable(
        bin_dir / "tail",
        """#!/bin/bash
for argument in "$@"; do
  case "$argument" in --pid=*) pid=${argument#--pid=} ;; esac
done
while kill -0 "$pid" 2>/dev/null; do sleep 0.01; done
""",
    )
    _write_executable(
        bin_dir / "stat",
        """#!/usr/bin/env python3
import hashlib
import os
import sys
from pathlib import Path

args = sys.argv[1:]
fmt = args[args.index("-Lc") + 1]
path = Path(args[-1])
if fmt == "%s":
    print(path.stat().st_size)
elif fmt == "%u:%g:%a:%F":
    if path.name == "metadata.txt":
        print("0:0:600:regular file")
    elif path.name.endswith("image.tar"):
        print("0:0:444:regular file")
    elif path.name == "db":
        print("65532:65532:700:directory")
    else:
        print("0:0:700:directory")
elif fmt == "%d:%i:%u:%g:%a:%F":
    inode = int(hashlib.sha256(str(path).encode()).hexdigest()[:8], 16)
    print(f"1:{inode}:0:0:700:directory")
else:
    raise SystemExit(f"unexpected stat format: {fmt}")
""",
    )
    _write_executable(
        bin_dir / "df",
        """#!/bin/bash
counter="$FAKE_SCANNER_STATE/df-counter"
count=$(cat "$counter" 2>/dev/null || printf 0)
count=$((count + 1))
printf '%s' "$count" > "$counter"
available=21474836480
if [ "$count" -eq 1 ] && [ -n "$FAKE_DF_PRE_BYTES" ]; then available=$FAKE_DF_PRE_BYTES; fi
if [ "$count" -gt 1 ] && [ -n "$FAKE_DF_POST_BYTES" ]; then available=$FAKE_DF_POST_BYTES; fi
printf 'Filesystem 1-blocks Used Available Capacity Mounted on\\n'
printf 'fake 21474836480 0 %s 0%% /\\n' "$available"
""",
    )
    return bin_dir, state


def _run_helper(
    tmp_path: Path,
    *,
    services: tuple[str, ...] = ("backend",),
    extra_env: dict[str, str] | None = None,
    archive_kind: str = "classic",
    runtime_image_id_override: str | None = None,
) -> tuple[subprocess.CompletedProcess[str], Path, Path, list[Path]]:
    bin_dir, state = _fake_commands(tmp_path)
    evidence = tmp_path / "evidence"
    evidence.mkdir(mode=0o700)
    work_root = evidence / "scanner-work"
    work_root.mkdir(mode=0o700)
    metadata = evidence / "metadata.txt"
    metadata.write_text("source_git_sha=" + "b" * 40 + "\n", encoding="utf-8")
    arguments: list[str] = [
        str(SCANNER),
        str(work_root),
        str(metadata),
        SYFT_IMAGE,
        GRYPE_IMAGE,
        IMAGE_ID,
    ]
    archives: list[Path] = []
    scanner_config_id = ""
    for service in services:
        archive = evidence / f"{service} image.tar"
        if archive_kind == "classic":
            fixture = write_classic_archive(archive)
        elif archive_kind == "oci-index":
            fixture = write_oci_index_archive(archive)
        else:
            raise AssertionError(f"unsupported test archive kind: {archive_kind}")
        if scanner_config_id and fixture.config_id != scanner_config_id:
            raise AssertionError("test archives must share one scanner config ID")
        scanner_config_id = fixture.config_id
        archive.chmod(0o444)
        archives.append(archive)
        arguments.extend(
            [
                service,
                runtime_image_id_override or fixture.runtime_id,
                str(archive),
                str(evidence / f"{service}.spdx.json"),
                str(evidence / f"{service}-grype.json"),
            ]
        )
        with metadata.open("a", encoding="utf-8") as stream:
            stream.write(
                f"{service}_archive_sha256={hashlib.sha256(archive.read_bytes()).hexdigest()}\n"
            )
    env = {
        **os.environ,
        "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
        "FAKE_SCANNER_STATE": str(state),
        "FAKE_IMAGE_ID": scanner_config_id,
    }
    if extra_env:
        env.update(extra_env)
    completed = subprocess.run(
        arguments,
        text=True,
        capture_output=True,
        env=env,
        check=False,
    )
    return completed, state, metadata, archives


def _docker_calls(state: Path) -> list[list[str]]:
    return [
        json.loads(line)
        for line in (state / "docker-calls.jsonl")
        .read_text(encoding="utf-8")
        .splitlines()
    ]


def test_batch_scanner_executes_hardened_path_for_every_archive(tmp_path: Path) -> None:
    completed, state, metadata, archives = _run_helper(
        tmp_path, services=("backend", "frontend", "caddy", "postgres")
    )

    assert completed.returncode == 0, completed.stderr
    creates = [call for call in _docker_calls(state) if call[0] == "create"]
    assert len(creates) == 9  # one mount probe plus Syft and Grype per archive
    for call in creates:
        assert ["--read-only", "--cap-drop", "ALL"] == call[
            call.index("--read-only") : call.index("--read-only") + 3
        ]
        assert "no-new-privileges" in call
        assert ["--cpus", "1"] == call[call.index("--cpus") : call.index("--cpus") + 2]
        assert ["--pids-limit", "256"] == call[
            call.index("--pids-limit") : call.index("--pids-limit") + 2
        ]
        assert ["--memory", "768m"] == call[
            call.index("--memory") : call.index("--memory") + 2
        ]
        assert ["--memory-swap", "1536m"] == call[
            call.index("--memory-swap") : call.index("--memory-swap") + 2
        ]
    scanner_creates = creates[1:]
    assert all(
        "/tmp:rw,noexec,nosuid,nodev,size=512m,mode=1777" in call
        for call in scanner_creates
    )
    assert all(
        "GOMEMLIMIT=256MiB" in call and "GOMAXPROCS=1" in call
        for call in scanner_creates
    )
    syft_creates = [call for call in scanner_creates if SYFT_IMAGE in call]
    grype_creates = [call for call in scanner_creates if GRYPE_IMAGE in call]
    assert len(syft_creates) == len(grype_creates) == 4
    assert all("none" in call[call.index("--network") + 1 :] for call in syft_creates)
    assert all("--network" not in call for call in grype_creates)
    assert all("GRYPE_DB_CACHE_DIR=/scanner-cache/db" in call for call in grype_creates)
    assert all(archive.exists() for archive in archives)
    metadata_text = metadata.read_text(encoding="utf-8")
    for service in ("backend", "frontend", "caddy", "postgres"):
        assert f"{service}_grype_version=0.118.0" in metadata_text
        assert f"{service}_syft_version=1.42.3" in metadata_text
        assert f"{service}_grype_db_schema=v6.1.9" in metadata_text
        assert f"{service}_grype_db_valid=true" in metadata_text
        assert f"{service}_source_image_id={IMAGE_ID}" in metadata_text
    assert "scanner_cache_sampled_max_bytes=" in metadata_text
    assert "scanner_disk_preflight_is_quota=false" in metadata_text
    assert (
        f"backend_archive_sha256={hashlib.sha256(archives[0].read_bytes()).hexdigest()}"
        in metadata_text
    )
    assert metadata_text.count("backend_archive_sha256=") == 1
    assert "backend_verified_archive_digest=sha256:" in metadata_text
    assert (state / "umount-calls.txt").is_file()


def test_oci_index_runtime_uses_derived_config_for_both_scanners(
    tmp_path: Path,
) -> None:
    completed, _state, metadata, _archives = _run_helper(
        tmp_path, archive_kind="oci-index"
    )

    assert completed.returncode == 0, completed.stderr
    metadata_text = metadata.read_text(encoding="utf-8")
    runtime_id = next(
        line.split("=", 1)[1]
        for line in metadata_text.splitlines()
        if line.startswith("backend_runtime_image_id=")
    )
    config_id = next(
        line.split("=", 1)[1]
        for line in metadata_text.splitlines()
        if line.startswith("backend_scanner_config_image_id=")
    )
    assert runtime_id != config_id
    assert "backend_runtime_image_id_type=oci-index" in metadata_text
    assert f"backend_syft_source_image_id={config_id}" in metadata_text
    assert f"backend_source_image_id={config_id}" in metadata_text


def test_archive_identity_failure_happens_before_any_scanner_runtime(
    tmp_path: Path,
) -> None:
    completed, state, _metadata, archives = _run_helper(
        tmp_path,
        archive_kind="oci-index",
        runtime_image_id_override="sha256:" + "d" * 64,
    )

    assert completed.returncode != 0
    assert "runtime image ID does not match" in completed.stderr
    assert archives[0].exists()
    assert not (state / "docker-calls.jsonl").exists()
    assert not (state / "mounted").exists()


def test_linux_ci_invokes_same_helper_for_all_four_candidate_archives() -> None:
    action = (ROOT / ".github/actions/scan-production-images/action.yml").read_text(
        encoding="utf-8"
    )

    assert "services=(backend frontend caddy postgres)" in action
    assert "infra/scripts/run-hardened-image-scanners.sh" in action
    assert "sudo /usr/bin/env -i" in action
    assert '"DOCKER_HOST=$EXPECTED_DOCKER_HOST"' in action
    assert '/bin/bash "$scanner_tool"' in action
    assert '"${scanner_arguments[@]}"' in action
    assert 'scanner_evidence="$RUNNER_TEMP/dcompany-hardened-scanner-private"' in action
    assert 'scanner_evidence="$evidence_dir/' not in action
    assert '600s docker pull "$syft_image"' in action
    assert '600s docker pull "$grype_image"' in action
    assert action.count(".syft.json") >= 8
    assert action.count("-grype.json") >= 9
    assert "/var/run/docker.sock" not in action


@pytest.mark.parametrize(
    ("extra_env", "error"),
    [
        ({"FAKE_GRYPE_VALID": "false"}, "not valid"),
        ({"FAKE_GRYPE_MISSING_STATUS": "true"}, "no vulnerability DB build identity"),
        ({"FAKE_GRYPE_IMAGE_ID": "sha256:" + "c" * 64}, "image ID mismatch"),
        ({"FAKE_SYFT_IMAGE_ID": "sha256:" + "d" * 64}, "Syft source image ID mismatch"),
    ],
)
def test_invalid_grype_evidence_fails_closed_and_retains_archive(
    tmp_path: Path, extra_env: dict[str, str], error: str
) -> None:
    completed, state, _metadata, archives = _run_helper(
        tmp_path, extra_env=extra_env, archive_kind="oci-index"
    )

    assert completed.returncode != 0
    assert error in completed.stderr
    assert archives[0].exists()
    assert (state / "umount-calls.txt").is_file()


def test_scanner_nonzero_propagates_and_retains_archive(tmp_path: Path) -> None:
    completed, state, _metadata, archives = _run_helper(
        tmp_path, extra_env={"FAKE_SCANNER_FAIL": "syft"}
    )

    assert completed.returncode == 23
    assert archives[0].exists()
    calls = _docker_calls(state)
    assert any(call[0] == "rm" for call in calls)
    assert (state / "umount-calls.txt").is_file()


def test_lost_create_response_recovers_only_labelled_container(tmp_path: Path) -> None:
    completed, state, _metadata, archives = _run_helper(
        tmp_path, extra_env={"FAKE_SCANNER_LOSE_CREATE_RESPONSE": "probe"}
    )

    assert completed.returncode == 124
    assert archives[0].exists()
    calls = _docker_calls(state)
    assert any(
        call[0] == "inspect" and "dcompany-scanner-" in call[-1] for call in calls
    )
    assert any(call[0] == "rm" for call in calls)
    assert (state / "umount-calls.txt").is_file()


def test_ambiguous_container_ownership_retains_mounted_cache(tmp_path: Path) -> None:
    completed, state, _metadata, archives = _run_helper(
        tmp_path,
        extra_env={
            "FAKE_SCANNER_LOSE_CREATE_RESPONSE": "probe",
            "FAKE_SCANNER_INSPECT_FAIL": "true",
        },
    )

    assert completed.returncode == 124
    assert archives[0].exists()
    assert "retaining the private scanner runtime" in completed.stderr
    assert not (state / "umount-calls.txt").exists()
    assert (state / "mounted").exists()


@pytest.mark.parametrize("scanner", ["syft", "grype"])
def test_scanner_timeout_stops_waits_removes_only_owned_container(
    tmp_path: Path, scanner: str
) -> None:
    completed, state, _metadata, archives = _run_helper(
        tmp_path,
        extra_env={
            "FAKE_SCANNER_TIMEOUT": scanner,
            "FAKE_SCANNER_RUNNING": scanner,
        },
    )

    assert completed.returncode == 124
    assert archives[0].exists()
    calls = _docker_calls(state)
    operations = [call[0] for call in calls]
    assert "stop" in operations
    assert "wait" in operations
    assert "rm" in operations
    stop_index = operations.index("stop")
    wait_index = operations.index("wait", stop_index)
    remove_index = operations.index("rm", wait_index)
    assert stop_index < wait_index < remove_index
    assert all(not any("prune" in value for value in call) for call in calls)
    assert (state / "umount-calls.txt").is_file()
    if scanner == "syft":
        assert not any(GRYPE_IMAGE in call for call in calls if call[0] == "create")


def test_scanner_oom_state_fails_without_later_scanner_success(tmp_path: Path) -> None:
    completed, state, metadata, archives = _run_helper(
        tmp_path, extra_env={"FAKE_SCANNER_OOM": "syft"}
    )

    assert completed.returncode == 137
    assert archives[0].exists()
    assert "backend_grype_version=" not in metadata.read_text(encoding="utf-8")
    assert not any(
        GRYPE_IMAGE in call for call in _docker_calls(state) if call[0] == "create"
    )
    assert (state / "umount-calls.txt").is_file()


@pytest.mark.parametrize(
    ("extra_env", "expected_error", "remains_unresolved"),
    [
        (
            {"FAKE_SCANNER_TIMEOUT": "syft", "FAKE_SCANNER_RUNNING": "syft", "FAKE_SCANNER_STOP_FAIL": "true"},
            "Cannot stop the owned scanner container",
            True,
        ),
        (
            {"FAKE_SCANNER_TIMEOUT": "syft", "FAKE_SCANNER_RUNNING": "syft", "FAKE_SCANNER_WAIT_FAIL": "true"},
            "Cannot confirm the owned scanner container stopped",
            False,
        ),
        (
            {"FAKE_SCANNER_TIMEOUT": "syft", "FAKE_SCANNER_REMOVE_FAIL": "true"},
            "Cannot remove the owned scanner container",
            True,
        ),
        (
            {"FAKE_SCANNER_TIMEOUT": "syft", "FAKE_SCANNER_INSPECT_FAIL": "syft"},
            "Cannot verify the owned scanner container identity",
            True,
        ),
        (
            {"FAKE_SCANNER_TIMEOUT": "syft", "FAKE_SCANNER_WRONG_OWNERSHIP": "syft"},
            "ownership label or name mismatch",
            True,
        ),
    ],
)
def test_unresolved_owned_scanner_retains_mount_and_private_runtime(
    tmp_path: Path,
    extra_env: dict[str, str],
    expected_error: str,
    remains_unresolved: bool,
) -> None:
    completed, state, _metadata, archives = _run_helper(tmp_path, extra_env=extra_env)

    assert completed.returncode != 0
    assert expected_error in completed.stderr
    assert archives[0].exists()
    if remains_unresolved:
        assert "Owned scanner process remains unresolved" in completed.stderr
        assert (state / "mounted").exists()
        assert not (state / "umount-calls.txt").exists()
    else:
        assert not (state / "mounted").exists()
        assert (state / "umount-calls.txt").exists()
    assert not any(
        GRYPE_IMAGE in call for call in _docker_calls(state) if call[0] == "create"
    )


@pytest.mark.parametrize(
    ("extra_env", "expected_error", "expects_unmount"),
    [
        ({"FAKE_MOUNT_FAIL": "bind"}, "mount", False),
        ({"FAKE_MOUNT_FAIL": "remount"}, "mount", True),
        ({"FAKE_FINDMNT_FAIL": "true"}, "mount", True),
        ({"FAKE_SCANNER_FAIL": "probe"}, "inheritance probe failed", True),
    ],
)
def test_mount_remount_and_probe_failures_stop_before_scanners(
    tmp_path: Path,
    extra_env: dict[str, str],
    expected_error: str,
    expects_unmount: bool,
) -> None:
    completed, state, _metadata, archives = _run_helper(tmp_path, extra_env=extra_env)

    assert completed.returncode != 0
    if expected_error != "mount":
        assert expected_error.lower() in completed.stderr.lower()
    assert archives[0].exists()
    calls = _docker_calls(state) if (state / "docker-calls.jsonl").exists() else []
    assert not any(
        SYFT_IMAGE in call or GRYPE_IMAGE in call for call in calls if call[0] == "create"
    )
    assert (state / "umount-calls.txt").exists() is expects_unmount


def test_umount_failure_retains_private_mount_and_reports(tmp_path: Path) -> None:
    completed, state, metadata, archives = _run_helper(
        tmp_path, extra_env={"FAKE_UMOUNT_FAIL": "true"}
    )

    assert completed.returncode != 0
    assert archives[0].exists()
    assert (state / "mounted").exists()
    assert (state / "umount-calls.txt").is_file()
    assert "backend_grype_version=0.118.0" in metadata.read_text(encoding="utf-8")
    assert any(path.name == "backend.spdx.json" for path in metadata.parent.iterdir())


def test_insufficient_preflight_space_fails_before_mount_or_scanner(tmp_path: Path) -> None:
    completed, state, metadata, archives = _run_helper(
        tmp_path, extra_env={"FAKE_DF_PRE_BYTES": "1024"}
    )

    assert completed.returncode != 0
    assert "3 GiB free workspace" in completed.stderr
    assert archives[0].exists()
    assert not (state / "docker-calls.jsonl").exists()
    assert not (state / "mounted").exists()
    assert "scanner_disk_available_before_bytes=" not in metadata.read_text(encoding="utf-8")


def test_insufficient_post_scan_reserve_retains_evidence_and_stops_batch(
    tmp_path: Path,
) -> None:
    completed, state, metadata, archives = _run_helper(
        tmp_path,
        services=("backend", "frontend"),
        extra_env={"FAKE_DF_POST_BYTES": "1024"},
    )

    assert completed.returncode != 0
    assert "1 GiB operational disk reserve" in completed.stderr
    assert all(archive.exists() for archive in archives)
    text = metadata.read_text(encoding="utf-8")
    assert "backend_grype_version=0.118.0" in text
    assert "frontend_syft_version=" not in text
    calls = _docker_calls(state)
    assert not any(
        "frontend image.tar" in value
        for call in calls
        for value in call
        if call[0] == "create"
    )
    assert (state / "umount-calls.txt").is_file()
