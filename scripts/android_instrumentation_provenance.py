#!/usr/bin/env python3
"""Bind instrumentation evidence to source bytes and assembled APK artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess
import sys


SOURCE_ROOTS = ("android-native", "protocol-fixtures", "scripts", ".github/workflows")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_file(root: Path, relative: str) -> Path:
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError(f"Unsafe evidence path: {relative!r}")
    candidate = root
    for part in path.parts:
        candidate /= part
        if candidate.is_symlink():
            raise ValueError(f"Symlinked evidence input: {relative}")
    if not candidate.is_file():
        raise ValueError(f"Missing evidence input: {relative}")
    return candidate


def source_manifest(root: Path) -> bytes:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", *SOURCE_ROOTS],
        cwd=root, capture_output=True, check=True,
    )
    names = sorted(set(name.decode("utf-8") for name in result.stdout.split(b"\0") if name))
    if not names:
        raise ValueError("No Android source/build inputs found")
    rows = []
    for name in names:
        if any(ord(character) < 32 for character in name):
            raise ValueError("Control character in source path")
        path = checked_file(root, name)
        rows.append({"path": name, "sha256": sha256(path), "size": path.stat().st_size})
    return (json.dumps({"source_roots": SOURCE_ROOTS, "files": rows}, indent=2) + "\n").encode()


def artifact(root: Path, path: Path) -> dict[str, str]:
    relative = path.absolute().relative_to(root).as_posix()
    checked = checked_file(root, relative)
    return {"path": relative, "resolved_path": str(checked), "sha256": sha256(checked)}


def create(root: Path, target_apk: Path, test_apk: Path, output: Path) -> dict:
    manifest = source_manifest(root)
    receipt = {
        "repo_root": str(root),
        "source_inventory_sha256": hashlib.sha256(manifest).hexdigest(),
        "target_apk": artifact(root, target_apk),
        "test_apk": artifact(root, test_apk),
        "capture_run_id": secrets.token_hex(16),
        "persistence_run_id": secrets.token_hex(16),
        "test_display_id": "0",
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "source-inventory.json").write_bytes(manifest)
    (output / "provenance.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


def verify(root: Path, output: Path, expected_receipt_sha256: str) -> dict:
    receipt_path = output / "provenance.json"
    if not re.fullmatch(r"[0-9a-f]{64}", expected_receipt_sha256):
        raise ValueError("Expected provenance receipt digest must be SHA256")
    if sha256(receipt_path) != expected_receipt_sha256:
        raise ValueError("Provenance receipt changed after initial capture")
    receipt = json.loads(receipt_path.read_text())
    if receipt["repo_root"] != str(root):
        raise ValueError("Evidence belongs to a different source checkout")
    manifest = (output / "source-inventory.json").read_bytes()
    if hashlib.sha256(manifest).hexdigest() != receipt["source_inventory_sha256"]:
        raise ValueError("Retained source inventory hash changed")
    if source_manifest(root) != manifest:
        raise ValueError("Source/build inputs changed after provenance capture")
    for name in ("target_apk", "test_apk"):
        row = receipt[name]
        path = checked_file(root, row["path"])
        if str(path) != row["resolved_path"] or sha256(path) != row["sha256"]:
            raise ValueError(f"Assembled {name} bytes changed after provenance capture")
    for name in ("capture_run_id", "persistence_run_id"):
        value = receipt[name]
        if len(value) != 32 or any(character not in "0123456789abcdef" for character in value):
            raise ValueError(f"Invalid {name}")
    if receipt["test_display_id"] != "0":
        raise ValueError("System gesture evidence requires physical display 0")
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify", "values"))
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target-apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--expected-receipt-sha256")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    try:
        if args.command == "create":
            if args.target_apk is None or args.test_apk is None:
                parser.error("create requires --target-apk and --test-apk")
            receipt = create(root, args.target_apk, args.test_apk, args.output)
            print(sha256(args.output / "provenance.json"))
        else:
            if args.expected_receipt_sha256 is None:
                parser.error("verify/values require --expected-receipt-sha256")
            receipt = verify(root, args.output, args.expected_receipt_sha256)
        if args.command == "values":
            print(" ".join((receipt["source_inventory_sha256"], receipt["target_apk"]["sha256"],
                            receipt["test_apk"]["sha256"], receipt["capture_run_id"],
                            receipt["persistence_run_id"])))
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(f"Instrumentation provenance failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
