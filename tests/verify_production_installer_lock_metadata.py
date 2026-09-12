from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INSTALLER = ROOT / "infra" / "scripts" / "install-on-vm.sh"


def _run_installer_with_lock_fd(
    work_directory: Path,
    descriptor: int,
    *,
    identity: tuple[int, int] | None = None,
) -> subprocess.CompletedProcess[str]:
    fake_bin = work_directory / "bin"
    fake_bin.mkdir(exist_ok=True)
    git_trace = work_directory / "git-trace"
    fake_git = fake_bin / "git"
    fake_git.write_text(
        "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$LOCK_GIT_TRACE\"\nexit 1\n",
        encoding="utf-8",
    )
    fake_git.chmod(0o755)

    metadata = os.fstat(descriptor)
    device, inode = identity or (metadata.st_dev, metadata.st_ino)
    environment = {
        "DCOMPANY_PRODUCTION_INSTALL_LOCK_FD": "9",
        "DCOMPANY_PRODUCTION_INSTALL_LOCK_ID": f"{device}:{inode}",
        "DCOMPANY_PRODUCTION_REPO_DIR": str(work_directory),
        "LC_ALL": "C",
        "LOCK_GIT_TRACE": str(git_trace),
        "PATH": f"{fake_bin}{os.pathsep}{os.defpath}",
    }
    wrapper = (
        "import os, sys; "
        "os.dup2(int(sys.argv[1]), 9, inheritable=True); "
        "os.execve('/bin/bash', ['bash', sys.argv[2], 'erp.example.com'], os.environ)"
    )
    return subprocess.run(
        [sys.executable, "-c", wrapper, str(descriptor), str(INSTALLER)],
        env=environment,
        pass_fds=(descriptor,),
        capture_output=True,
        text=True,
        check=False,
        timeout=10,
    )


def _assert_accepted(work_directory: Path, content: bytes) -> None:
    lock = work_directory / "production-install.lock"
    lock.write_bytes(content)
    lock.chmod(0o600)
    descriptor = os.open(lock, os.O_RDWR | os.O_NONBLOCK)
    try:
        result = _run_installer_with_lock_fd(work_directory, descriptor)
    finally:
        os.close(descriptor)

    assert result.returncode == 1, result
    assert "Production lock descriptor failed" not in result.stderr
    assert "Production install requires a clean Git checkout" in result.stderr
    assert (work_directory / "git-trace").read_text(
        encoding="utf-8"
    ) == "rev-parse HEAD\n"
    assert lock.read_bytes() == content


def _assert_unsafe_metadata_rejected(work_directory: Path) -> None:
    def assert_rejected(
        descriptor: int,
        *,
        identity: tuple[int, int] | None = None,
    ) -> None:
        result = _run_installer_with_lock_fd(
            work_directory,
            descriptor,
            identity=identity,
        )
        assert result.returncode == 1, result
        assert "Production lock descriptor failed ownership/type validation" in (
            result.stderr
        )
        assert not (work_directory / "git-trace").exists()

    unsafe_mode = work_directory / "unsafe-mode.lock"
    unsafe_mode.write_bytes(b"must-survive")
    unsafe_mode.chmod(0o640)
    descriptor = os.open(unsafe_mode, os.O_RDWR | os.O_NONBLOCK)
    try:
        assert_rejected(descriptor)
    finally:
        os.close(descriptor)
    assert unsafe_mode.read_bytes() == b"must-survive"

    fifo = work_directory / "fifo.lock"
    os.mkfifo(fifo, 0o600)
    descriptor = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
    try:
        assert_rejected(descriptor)
    finally:
        os.close(descriptor)

    wrong_identity = work_directory / "wrong-identity.lock"
    wrong_identity.touch(mode=0o600)
    descriptor = os.open(wrong_identity, os.O_RDWR | os.O_NONBLOCK)
    try:
        metadata = os.fstat(descriptor)
        assert_rejected(descriptor, identity=(metadata.st_dev, metadata.st_ino + 1))
    finally:
        os.close(descriptor)

    wrong_owner = work_directory / "wrong-owner.lock"
    wrong_owner.touch(mode=0o600)
    os.chown(wrong_owner, 1, 0)
    descriptor = os.open(wrong_owner, os.O_RDWR | os.O_NONBLOCK)
    try:
        assert_rejected(descriptor)
    finally:
        os.close(descriptor)
    assert wrong_owner.stat().st_uid == 1

    hardlink_source = work_directory / "hardlink-source.lock"
    hardlink_source.write_bytes(b"hardlink-data")
    hardlink_source.chmod(0o600)
    hardlink = work_directory / "hardlink.lock"
    os.link(hardlink_source, hardlink)
    descriptor = os.open(hardlink, os.O_RDWR | os.O_NONBLOCK)
    try:
        assert_rejected(descriptor)
    finally:
        os.close(descriptor)
    assert hardlink_source.read_bytes() == b"hardlink-data"


def main() -> int:
    if not __debug__:
        print(
            "production installer lock regression refuses optimized Python",
            file=sys.stderr,
        )
        return 2
    if sys.platform != "linux" or os.geteuid() != 0:
        print(
            "production installer lock regression requires Linux and effective uid 0",
            file=sys.stderr,
        )
        return 2

    with tempfile.TemporaryDirectory(
        prefix="dcompany-installer-lock-regression-"
    ) as temporary_directory:
        temporary_root = Path(temporary_directory)
        for index, content in enumerate((b"", b"existing-lock-state")):
            accepted = temporary_root / f"accepted-{index}"
            accepted.mkdir(mode=0o700)
            _assert_accepted(accepted, content)
        rejected = temporary_root / "rejected"
        rejected.mkdir(mode=0o700)
        _assert_unsafe_metadata_rejected(rejected)

    print("production installer lock metadata regression passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
