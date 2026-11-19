"""Immutable r0 source fixtures, separate from current r1 admission."""
from __future__ import annotations

import hashlib
import io
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
BUILD77 = "f3178f70b4994c46fd0d2e48af76bbb7606c941b"


def historical_source_root(directory: Path, review: dict) -> Path:
    root = directory / "historical-build77-source"
    for name, expected in review["deployment_sources"].items():
        raw = subprocess.check_output(["git", "show", f"{BUILD77}:{name}"], cwd=ROOT)
        assert hashlib.sha256(raw).hexdigest() == expected, name
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
    return root


def historical_backend_hashes() -> dict[str, str]:
    raw = subprocess.check_output(["git", "archive", BUILD77, "backend"], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        return {member.name.removeprefix("backend/"): hashlib.sha256(archive.extractfile(member).read()).hexdigest()
                for member in archive.getmembers() if member.isfile()}
