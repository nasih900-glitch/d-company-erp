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
    descriptor: int | None,
    *,
    identity: tuple[int, int] | None = None,
    installer: Path = INSTALLER,
    options: tuple[str, ...] = (),
    extra_environment: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    fake_bin = work_directory / "bin"
    fake_bin.mkdir(exist_ok=True)
    git_trace = work_directory / "git-trace"
    fake_git = fake_bin / "git"
    fake_git.write_text(
        "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$LOCK_GIT_TRACE\"\n"
        "if [ \"$*\" = 'show HEAD:.env.production.example' ] && [ -n \"${LOCK_GIT_VERSION:-}\" ]; then\n"
        "  printf 'APP_VERSION=%s\\n' \"$LOCK_GIT_VERSION\"; exit 0\nfi\nexit 1\n",
        encoding="utf-8",
    )
    fake_git.chmod(0o755)
    for command in ("python3", "flock", "docker", "iptables", "apt-get", "mkdir", "mktemp", "chmod", "tar", "install", "systemctl"):
        stub = fake_bin / command
        stub.write_text(
            f"#!/bin/sh\nprintf '%s\\n' '{command}' >> \"$LOCK_MUTATION_TRACE\"\nexit 99\n",
            encoding="utf-8",
        )
        stub.chmod(0o755)
    environment = {
        "DCOMPANY_PRODUCTION_REPO_DIR": str(work_directory),
        "LC_ALL": "C",
        "LOCK_GIT_TRACE": str(git_trace),
        "LOCK_MUTATION_TRACE": str(work_directory / "mutation-trace"),
        "PATH": f"{fake_bin}{os.pathsep}{os.defpath}",
    }
    if descriptor is not None:
        metadata = os.fstat(descriptor)
        device, inode = identity or (metadata.st_dev, metadata.st_ino)
        environment.update(
            DCOMPANY_PRODUCTION_INSTALL_LOCK_FD="9",
            DCOMPANY_PRODUCTION_INSTALL_LOCK_ID=f"{device}:{inode}",
        )
    environment.update(extra_environment or {})
    wrapper = (
        "import os, sys; "
        "descriptor=int(sys.argv[1]); "
        "os.dup2(descriptor, 9, inheritable=True) if descriptor >= 0 else None; "
        "os.execve('/bin/bash', ['bash', sys.argv[2], 'erp.example.com', *sys.argv[3:]], os.environ)"
    )
    return subprocess.run(
        [sys.executable, "-c", wrapper, str(descriptor if descriptor is not None else -1), str(installer), *options],
        env=environment,
        pass_fds=() if descriptor is None else (descriptor,),
        capture_output=True,
        text=True,
        check=False,
        timeout=10,
    )


def _assert_valid_metadata_reaches_successor_hold(work_directory: Path, content: bytes) -> None:
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
    assert "Build72 production admission is closed" in result.stderr, result
    assert not (work_directory / "git-trace").exists()
    assert not (work_directory / "mutation-trace").exists()
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
        assert not (work_directory / "mutation-trace").exists()

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


def _assert_successor_cannot_enter_mutating_bootstrap(temporary_root: Path) -> None:
    options = (
        (),
        ("--maintenance-confirmed",),
        ("--legacy-code14-revision", "e5e90df5781e93681b8e9dcdd1ae9a6a5fb6a0b9"),
        ("--legacy-code16-revision", "2ac3fc88e4ce14d0f05d049b443a6a09c387a78a"),
        ("--build55-acceptance-credential-file", "/tmp/not-an-admission"),
        ("--build66-acceptance-credential-file", "/tmp/not-an-admission"),
    )
    for index, flags in enumerate(options):
        work = temporary_root / f"closed-{index}"
        work.mkdir(mode=0o700)
        if index % 2:
            (work / ".env").write_text("preserved private configuration\n")
        before = (work / ".env").read_bytes() if (work / ".env").exists() else None
        result = _run_installer_with_lock_fd(
            work,
            None,
            options=flags,
            extra_environment={
                "APP_VERSION": "3.1.64",
                "DCOMPANY_PRODUCTION_RELEASE_SNAPSHOT": str(work),
                "LOCK_GIT_VERSION": "3.1.64",
            },
        )
        assert result.returncode == 1, result
        assert "Build72 production admission is closed" in result.stderr, result
        assert not (work / "git-trace").exists()
        assert not (work / "mutation-trace").exists()
        assert ((work / ".env").read_bytes() if (work / ".env").exists() else None) == before

    # The inverse path also refuses: an older-looking script source cannot
    # hide that the immutable repository object selects the closed successor.
    work = temporary_root / "repository-selects-successor"
    work.mkdir(mode=0o700)
    source = work / "source"
    scripts = source / "infra/scripts"
    scripts.mkdir(parents=True)
    copied = scripts / INSTALLER.name
    copied.write_bytes(INSTALLER.read_bytes())
    (source / ".env.production.example").write_text("APP_VERSION=3.1.64\n")
    result = _run_installer_with_lock_fd(
        work, None, installer=copied, extra_environment={"LOCK_GIT_VERSION": "3.1.75"}
    )
    assert result.returncode == 1, result
    assert "repository selects the unreviewed successor" in result.stderr, result
    assert (work / "git-trace").read_text() == "show HEAD:.env.production.example\n"
    assert not (work / "mutation-trace").exists()

    for flags, diagnostic in (
        (("--force-successor",), "Unknown installer option"),
        (("--legacy-code14-revision", "a", "--legacy-code16-revision", "b"), "Only one legacy revision"),
    ):
        rejected = temporary_root / ("rejected-force" if len(flags) == 1 else "rejected-mixed")
        rejected.mkdir(mode=0o700)
        result = _run_installer_with_lock_fd(rejected, None, options=flags)
        assert result.returncode == 1 and diagnostic in result.stderr, result
        assert not (rejected / "git-trace").exists()
        assert not (rejected / "mutation-trace").exists()


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
            _assert_valid_metadata_reaches_successor_hold(accepted, content)
        rejected = temporary_root / "rejected"
        rejected.mkdir(mode=0o700)
        _assert_unsafe_metadata_rejected(rejected)
        _assert_successor_cannot_enter_mutating_bootstrap(temporary_root)

    print("production installer lock metadata regression passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
