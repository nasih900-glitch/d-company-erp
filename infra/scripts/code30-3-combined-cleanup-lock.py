#!/usr/bin/env python3
"""Run a combined-cleanup wrapper under the signed installer's lock."""

from __future__ import annotations

import hashlib
import os
import stat
import subprocess
import sys
import types
from pathlib import Path
from typing import NoReturn


CANONICAL_CHECKOUT = Path("/opt/d-company-erp")
TAGGED_APP_SHA = "ad5adfb93c3488f1f931ca27da53824aa57d3dc5"
TAGGED_LOCK_HELPER_SHA256 = (
    "149e6f938d62b7c5266c4133c368b6af08160bac6cfb276d95f8d27cbbe1eb39"
)
LOCK_HELPER_RELATIVE = Path("infra/scripts/production_install_lock.py")
ALLOWED_RUNNERS = frozenset(
    {"apply-code30-3-combined-cleanup.sh", "postcheck-code30-3-combined-cleanup.sh"}
)


class MaintenanceLockError(RuntimeError):
    """The installer lock or its immutable implementation cannot be verified."""


def load_tagged_lock_helper() -> types.ModuleType:
    checkout = CANONICAL_CHECKOUT
    helper_path = checkout / LOCK_HELPER_RELATIVE
    if checkout.is_symlink() or not checkout.is_dir():
        raise MaintenanceLockError("tagged application checkout is absent or linked")
    if helper_path.is_symlink() or not helper_path.is_file():
        raise MaintenanceLockError("tagged installer lock helper is absent or linked")
    try:
        head = subprocess.run(
            ["git", "-C", str(checkout), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        source = helper_path.read_bytes()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise MaintenanceLockError("tagged installer lock helper is unreadable") from exc
    if head != TAGGED_APP_SHA:
        raise MaintenanceLockError("application checkout is not the signed Code30.3 commit")
    if hashlib.sha256(source).hexdigest() != TAGGED_LOCK_HELPER_SHA256:
        raise MaintenanceLockError("tagged installer lock helper differs from signed bytes")

    module = types.ModuleType("verified_production_install_lock")
    module.__file__ = str(helper_path)
    exec(compile(source, str(helper_path), "exec"), module.__dict__)
    return module


def _validate_runner(path: Path) -> None:
    expected = Path(__file__).resolve(strict=True).parent / path.name
    if (
        not path.is_absolute()
        or path.name not in ALLOWED_RUNNERS
        or path.is_symlink()
        or not path.is_file()
        or path.resolve(strict=True) != expected
    ):
        raise MaintenanceLockError("maintenance runner path is invalid")
    if not stat.S_ISREG(path.stat().st_mode):
        raise MaintenanceLockError("maintenance runner is not a regular file")


def exec_locked_runner(script: Path, arguments: list[str]) -> NoReturn:
    if os.geteuid() != 0:
        raise MaintenanceLockError("production maintenance lock requires root")
    _validate_runner(script)
    helper = load_tagged_lock_helper()
    lock_fd, metadata = helper.acquire_lock()
    inherited_descriptor_ready = False
    try:
        if lock_fd != helper.INHERITED_LOCK_FD:
            os.dup2(lock_fd, helper.INHERITED_LOCK_FD, inheritable=True)
            os.close(lock_fd)
        else:
            os.set_inheritable(lock_fd, True)
        inherited_descriptor_ready = True
        environment = os.environ.copy()
        environment[helper.LOCK_FD_ENV] = str(helper.INHERITED_LOCK_FD)
        environment[helper.LOCK_ID_ENV] = f"{metadata.st_dev}:{metadata.st_ino}"
        os.execve("/bin/bash", ["bash", str(script), *arguments], environment)
    except Exception:
        try:
            os.close(helper.INHERITED_LOCK_FD if inherited_descriptor_ready else lock_fd)
        except OSError:
            pass
        raise


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: code30-3-combined-cleanup-lock.py ABSOLUTE_RUNNER [ARGS...]", file=sys.stderr)
        return 2
    try:
        exec_locked_runner(Path(sys.argv[1]), sys.argv[2:])
    except (OSError, MaintenanceLockError) as exc:
        print(f"REFUSED: combined-cleanup installer lock: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
