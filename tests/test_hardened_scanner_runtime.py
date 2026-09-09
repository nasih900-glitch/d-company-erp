from __future__ import annotations

import json
import hashlib
import os
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / "infra" / "scripts" / "run-hardened-image-scanners.sh"
SYFT_IMAGE = (
    "anchore/syft:v1.42.3@sha256:"
    "5999d209a342e55e9edf70bf8930fb5b86d8f2a783fa401178372c50e21b1d36"
)
GRYPE_IMAGE = (
    "anchore/grype:v0.118.0@sha256:"
    "8a93fc48da96bd6ec5981279d099b69de11541dc68fdf222fb9161f8ff284af7"
)
IMAGE_ID = "sha256:" + "a" * 64


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
    if os.environ.get("FAKE_SCANNER_FAIL") == kind:
        raise SystemExit(23)
    if kind == "probe":
        print("scanner_cache_mount_verified=true")
    elif kind == "syft":
        print(json.dumps({
            "artifacts": [],
            "descriptor": {"name": "syft", "version": "1.42.3"},
            "source": {"type": "image", "metadata": {
                "imageID": os.environ.get("FAKE_SYFT_IMAGE_ID", os.environ["FAKE_IMAGE_ID"])
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
    if os.environ.get("FAKE_SCANNER_INSPECT_FAIL") == "true":
        raise SystemExit(125)
    if "scanner-invocation" in template:
        label, name = (state / f"ownership-{target}").read_text().split("|", 1)
        print(f"{target}|{label}|/{name}")
    else:
        print("false" if "Running" in template else "false:0")
elif args[0] == "wait":
    print("0")
elif args[0] in {"stop", "rm"}:
    if args[0] == "rm":
        target = args[-1]
        name_path = state / f"name-{target}"
        if name_path.exists():
            target = name_path.read_text()
        ownership = state / f"ownership-{target}"
        if ownership.exists():
            _label, name = ownership.read_text().split("|", 1)
            (state / f"name-{name}").unlink(missing_ok=True)
            ownership.unlink()
        (state / f"container-{target}").unlink(missing_ok=True)
else:
    raise SystemExit(f"unexpected docker invocation: {args}")
""",
    )
    _write_executable(
        bin_dir / "mount",
        """#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE_SCANNER_STATE/mount-calls.txt"
touch "$FAKE_SCANNER_STATE/mounted"
""",
    )
    _write_executable(
        bin_dir / "findmnt",
        """#!/bin/bash
case "$*" in
  *"-o TARGET"*)
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
        """#!/bin/sh
printf 'Filesystem 1-blocks Used Available Capacity Mounted on\\n'
printf 'fake 21474836480 0 21474836480 0%% /\\n'
""",
    )
    return bin_dir, state


def _run_helper(
    tmp_path: Path,
    *,
    services: tuple[str, ...] = ("backend",),
    extra_env: dict[str, str] | None = None,
) -> tuple[subprocess.CompletedProcess[str], Path, Path, list[Path]]:
    bin_dir, state = _fake_commands(tmp_path)
    evidence = tmp_path / "evidence"
    evidence.mkdir(mode=0o700)
    work_root = evidence / "scanner-work"
    work_root.mkdir(mode=0o700)
    metadata = evidence / "metadata.txt"
    metadata.write_text("source_git_sha=" + "b" * 40 + "\n", encoding="utf-8")
    arguments = [
        str(SCANNER),
        str(work_root),
        str(metadata),
        SYFT_IMAGE,
        GRYPE_IMAGE,
        IMAGE_ID,
    ]
    archives: list[Path] = []
    for service in services:
        archive = evidence / f"{service} image.tar"
        archive.write_bytes(b"immutable-image-archive")
        archive.chmod(0o444)
        archives.append(archive)
        arguments.extend(
            [
                service,
                IMAGE_ID,
                str(archive),
                str(evidence / f"{service}.spdx.json"),
                str(evidence / f"{service}-grype.json"),
            ]
        )
    env = {
        **os.environ,
        "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
        "FAKE_SCANNER_STATE": str(state),
        "FAKE_IMAGE_ID": IMAGE_ID,
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
        for line in (state / "docker-calls.jsonl").read_text(encoding="utf-8").splitlines()
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
    assert all("/tmp:rw,noexec,nosuid,nodev,size=512m,mode=1777" in call for call in scanner_creates)
    assert all("GOMEMLIMIT=256MiB" in call and "GOMAXPROCS=1" in call for call in scanner_creates)
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
    assert (state / "umount-calls.txt").is_file()


def test_linux_ci_invokes_same_helper_for_all_four_candidate_archives() -> None:
    action = (ROOT / ".github/actions/scan-production-images/action.yml").read_text(
        encoding="utf-8"
    )

    assert "services=(backend frontend caddy postgres)" in action
    assert "infra/scripts/run-hardened-image-scanners.sh" in action
    assert 'sudo /bin/bash "$scanner_tool"' in action
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
    completed, state, _metadata, archives = _run_helper(tmp_path, extra_env=extra_env)

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
    assert any(call[0] == "inspect" and "dcompany-scanner-" in call[-1] for call in calls)
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
