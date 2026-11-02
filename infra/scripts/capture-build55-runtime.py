#!/usr/bin/env python3
"""Capture read-only actual Compose runtime inputs as private data, never commands."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
from pathlib import Path

MAX_INSPECT_BYTES = 4 * 1024 * 1024
ALLOWED_ROOT = Path("/var/lib/dcompany-erp")


def capture(containers: list[dict]) -> dict:
    files = set()
    for container in containers:
        names = (
            container.get("Config", {})
            .get("Labels", {})
            .get("com.docker.compose.project.config_files")
        )
        if not isinstance(names, str):
            raise ValueError("actual Compose file identities are missing")
        files.update(names.split(","))
    metadata = []
    for name in sorted(files):
        path = Path(name)
        if not path.is_absolute() or ALLOWED_ROOT not in path.parents:
            raise ValueError(
                "actual Compose input is outside the protected deployment root"
            )
        for parent in (path, *path.parents):
            info = parent.lstat()
            if (
                stat.S_ISLNK(info.st_mode)
                or info.st_uid != 0
                or stat.S_IMODE(info.st_mode) & 0o022
            ):
                raise ValueError(
                    "actual Compose input ancestry is linked or writable by another user"
                )
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            info = os.fstat(descriptor)
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_nlink != 1
                or stat.S_IMODE(info.st_mode) != 0o600
                or info.st_size > 2_000_000
            ):
                raise ValueError("actual Compose input is not private regular data")
            raw = os.read(descriptor, 2_000_001)
            if len(raw) != info.st_size:
                raise ValueError("actual Compose input changed during capture")
        finally:
            os.close(descriptor)
        metadata.append(
            {
                "path": str(path),
                "uid": info.st_uid,
                "mode": stat.S_IMODE(info.st_mode),
                "size_bytes": len(raw),
                "regular_nonlinked": True,
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        )
    return {
        "schema_version": 1,
        "containers": containers,
        "configuration_files": metadata,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container-inspect", type=Path, required=True)
    args = parser.parse_args()
    try:
        descriptor = os.open(
            args.container_inspect, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
        )
        try:
            info = os.fstat(descriptor)
            if (
                info.st_uid != 0
                or stat.S_IMODE(info.st_mode) != 0o600
                or not stat.S_ISREG(info.st_mode)
                or info.st_nlink != 1
                or info.st_size > MAX_INSPECT_BYTES
            ):
                raise ValueError(
                    "container inspection requires private regular root-owned data"
                )
            raw = os.read(descriptor, MAX_INSPECT_BYTES + 1)
        finally:
            os.close(descriptor)
        containers = json.loads(raw)
        if not isinstance(containers, list) or len(containers) != 5:
            raise ValueError("exactly five actual container records are required")
        print(json.dumps(capture(containers), sort_keys=True, separators=(",", ":")))
        return 0
    except (OSError, ValueError, TypeError, KeyError):
        print(
            "Build55 runtime capture refused; no services were changed.",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
