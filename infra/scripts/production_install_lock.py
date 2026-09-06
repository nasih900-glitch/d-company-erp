#!/usr/bin/env python3
"""Acquire the production installer lock without following mutable pathnames."""

from __future__ import annotations

import fcntl
import os
import stat
import sys
from pathlib import Path
from typing import NoReturn


RUNTIME_PARENT = Path("/run")
RUNTIME_DIRECTORY_NAME = "d-company-erp"
LOCK_FILE_NAME = "production-install.lock"
STATE_PARENT = Path("/var/lib")
STATE_DIRECTORY_NAME = "dcompany-erp"
STATE_SUBDIRECTORY_NAMES = (
    "deployment-rollbacks",
    "build-snapshots",
    "container-security",
)
INHERITED_LOCK_FD = 9
LOCK_FD_ENV = "DCOMPANY_PRODUCTION_INSTALL_LOCK_FD"
LOCK_ID_ENV = "DCOMPANY_PRODUCTION_INSTALL_LOCK_ID"


class ProductionInstallLockError(RuntimeError):
    """The installer lock cannot be established without weakening safety."""


def _directory_flags() -> int:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    directory = getattr(os, "O_DIRECTORY", None)
    if nofollow is None or directory is None:
        raise ProductionInstallLockError(
            "This host cannot safely open the production installer lock directory"
        )
    return os.O_RDONLY | directory | nofollow | getattr(os, "O_CLOEXEC", 0)


def _validate_directory(
    descriptor: int,
    *,
    expected_uid: int,
    expected_gid: int,
    exact_mode: int | None = None,
) -> None:
    metadata = os.fstat(descriptor)
    mode = stat.S_IMODE(metadata.st_mode)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != expected_uid
        or metadata.st_gid != expected_gid
        or mode & 0o022
        or (exact_mode is not None and mode != exact_mode)
    ):
        raise ProductionInstallLockError(
            "Production installer lock directory has unsafe ownership or mode"
        )


def _validate_lock_file(
    descriptor: int, *, expected_uid: int, expected_gid: int
) -> os.stat_result:
    metadata = os.fstat(descriptor)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != expected_uid
        or metadata.st_gid != expected_gid
        or metadata.st_nlink != 1
        or stat.S_IMODE(metadata.st_mode) != 0o600
    ):
        raise ProductionInstallLockError(
            "Production installer lock has unsafe ownership, mode, type, or links"
        )
    return metadata


def _open_private_directory(
    parent_fd: int,
    name: str,
    *,
    expected_uid: int,
    expected_gid: int,
) -> int:
    """Create and open one root-private child without following a symlink."""
    try:
        os.mkdir(name, 0o700, dir_fd=parent_fd)
    except FileExistsError:
        pass
    except OSError as exc:
        raise ProductionInstallLockError(
            f"Cannot create private production state directory {name}"
        ) from exc

    try:
        descriptor = os.open(name, _directory_flags(), dir_fd=parent_fd)
    except OSError as exc:
        raise ProductionInstallLockError(
            f"Cannot safely open private production state directory {name}"
        ) from exc
    try:
        _validate_directory(
            descriptor,
            expected_uid=expected_uid,
            expected_gid=expected_gid,
            exact_mode=0o700,
        )
    except Exception:
        os.close(descriptor)
        raise
    return descriptor


def ensure_private_state_directories(
    *,
    state_parent: Path = STATE_PARENT,
    state_directory_name: str = STATE_DIRECTORY_NAME,
    state_subdirectory_names: tuple[str, ...] = STATE_SUBDIRECTORY_NAMES,
    expected_uid: int = 0,
    expected_gid: int = 0,
) -> None:
    """Create and validate the rollback/build evidence hierarchy by descriptor."""
    names = (state_directory_name, *state_subdirectory_names)
    if (
        not state_parent.is_absolute()
        or len(set(names)) != len(names)
        or any("/" in name or name in {"", ".", ".."} for name in names)
    ):
        raise ProductionInstallLockError("Production state path is invalid")

    try:
        parent_fd = os.open(state_parent, _directory_flags())
    except OSError as exc:
        raise ProductionInstallLockError(
            "Cannot safely open the production state parent"
        ) from exc
    try:
        _validate_directory(
            parent_fd,
            expected_uid=expected_uid,
            expected_gid=expected_gid,
        )
        state_fd = _open_private_directory(
            parent_fd,
            state_directory_name,
            expected_uid=expected_uid,
            expected_gid=expected_gid,
        )
        try:
            for name in state_subdirectory_names:
                descriptor = _open_private_directory(
                    state_fd,
                    name,
                    expected_uid=expected_uid,
                    expected_gid=expected_gid,
                )
                os.close(descriptor)
        finally:
            os.close(state_fd)
    finally:
        os.close(parent_fd)


def acquire_lock(
    *,
    runtime_parent: Path = RUNTIME_PARENT,
    runtime_directory_name: str = RUNTIME_DIRECTORY_NAME,
    lock_file_name: str = LOCK_FILE_NAME,
    expected_uid: int = 0,
    expected_gid: int = 0,
) -> tuple[int, os.stat_result]:
    """Return a locked fd after dirfd-relative, no-follow creation and validation."""
    if (
        not runtime_parent.is_absolute()
        or "/" in runtime_directory_name
        or runtime_directory_name in {"", ".", ".."}
        or "/" in lock_file_name
        or lock_file_name in {"", ".", ".."}
    ):
        raise ProductionInstallLockError("Production installer lock path is invalid")

    flags = _directory_flags()
    try:
        parent_fd = os.open(runtime_parent, flags)
    except OSError as exc:
        raise ProductionInstallLockError(
            "Cannot safely open the production runtime parent"
        ) from exc
    try:
        _validate_directory(
            parent_fd,
            expected_uid=expected_uid,
            expected_gid=expected_gid,
        )
        try:
            os.mkdir(runtime_directory_name, 0o700, dir_fd=parent_fd)
        except FileExistsError:
            pass
        except OSError as exc:
            raise ProductionInstallLockError(
                "Cannot create the production installer lock directory"
            ) from exc

        try:
            runtime_fd = os.open(runtime_directory_name, flags, dir_fd=parent_fd)
        except OSError as exc:
            raise ProductionInstallLockError(
                "Cannot safely open the production installer lock directory"
            ) from exc
        try:
            _validate_directory(
                runtime_fd,
                expected_uid=expected_uid,
                expected_gid=expected_gid,
                exact_mode=0o700,
            )
            nofollow = getattr(os, "O_NOFOLLOW")
            lock_flags = (
                os.O_RDWR
                | os.O_CREAT
                | os.O_NONBLOCK
                | nofollow
                | getattr(os, "O_CLOEXEC", 0)
            )
            try:
                lock_fd = os.open(
                    lock_file_name,
                    lock_flags,
                    0o600,
                    dir_fd=runtime_fd,
                )
            except OSError as exc:
                raise ProductionInstallLockError(
                    "Cannot safely open the production installer lock"
                ) from exc
            try:
                metadata = _validate_lock_file(
                    lock_fd,
                    expected_uid=expected_uid,
                    expected_gid=expected_gid,
                )
                try:
                    fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError as exc:
                    raise ProductionInstallLockError(
                        "Another D Company production install or upgrade is already running"
                    ) from exc
            except Exception:
                os.close(lock_fd)
                raise
            return lock_fd, metadata
        finally:
            os.close(runtime_fd)
    finally:
        os.close(parent_fd)


def _exec_locked_installer(script: Path, arguments: list[str]) -> NoReturn:
    if os.geteuid() != 0:
        raise ProductionInstallLockError("Production installer lock requires root")
    if not script.is_absolute() or script.name != "install-on-vm.sh":
        raise ProductionInstallLockError("Production installer path is invalid")

    lock_fd, metadata = acquire_lock()
    inherited_descriptor_ready = False
    try:
        ensure_private_state_directories()
        if lock_fd != INHERITED_LOCK_FD:
            os.dup2(lock_fd, INHERITED_LOCK_FD, inheritable=True)
            os.close(lock_fd)
        else:
            os.set_inheritable(lock_fd, True)
        inherited_descriptor_ready = True
        environment = os.environ.copy()
        environment[LOCK_FD_ENV] = str(INHERITED_LOCK_FD)
        environment[LOCK_ID_ENV] = f"{metadata.st_dev}:{metadata.st_ino}"
        os.execve(
            "/bin/bash",
            ["bash", str(script), *arguments],
            environment,
        )
    except Exception:
        try:
            os.close(
                INHERITED_LOCK_FD if inherited_descriptor_ready else lock_fd
            )
        except OSError:
            pass
        raise


def main() -> int:
    if len(sys.argv) < 2:
        print(
            "usage: production_install_lock.py ABSOLUTE_INSTALLER [ARGS...]",
            file=sys.stderr,
        )
        return 2
    try:
        _exec_locked_installer(Path(sys.argv[1]), sys.argv[2:])
    except (OSError, ProductionInstallLockError) as exc:
        print(f"Production installer lock refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
