#!/usr/bin/env python3
"""Retain each available unsigned build output before a comparison can fail."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def compiler_apk(directory: Path) -> Path:
    metadata = directory / "output-metadata.json"
    if metadata.is_symlink() or metadata.stat().st_size > 1024 * 1024:
        raise ValueError("invalid compiler APK metadata")
    elements = json.loads(metadata.read_text(encoding="utf-8"))["elements"]
    if not isinstance(elements, list) or len(elements) != 1:
        raise ValueError("requires one compiler APK")
    name = elements[0]["outputFile"]
    if not isinstance(name, str) or not name.endswith(".apk") or Path(name).name != name:
        raise ValueError("unsafe APK metadata filename")
    return directory / name


def digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ValueError("missing or nonregular build output")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_canonical(root: Path) -> None:
    source = root / "android-native/app/build"
    audit = source / "outputs/canonical-bundle-audit/release"
    manifest = audit / "SHA256SUMS"
    if manifest.is_symlink() or manifest.stat().st_size > 4096:
        raise ValueError("invalid canonical audit")
    rows = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        checksum, name = line.split("  ")
        if len(checksum) != 64 or any(c not in "0123456789abcdef" for c in checksum) or name in rows:
            raise ValueError("invalid canonical audit row")
        rows[name] = checksum
    if set(rows) != {"raw-agp.aab", "reference.apk", "canonical.aab"}:
        raise ValueError("canonical audit inventory differs")
    paths = {"raw-agp.aab": audit / "raw-agp.aab",
             "reference.apk": compiler_apk(source / "outputs/apk/release"),
             "canonical.aab": source / "outputs/bundle/release/app-release.aab"}
    for name, path in paths.items():
        if digest(path) != rows[name]:
            raise ValueError(f"canonical audit mismatch: {name}")


def collect(root: Path, output: Path) -> dict[str, object]:
    source = root / "android-native/app/build"
    output.mkdir(parents=True, exist_ok=True)
    paths = {
        "play-unsigned.aab": source / "outputs/bundle/release/app-release.aab",
        "play-output-metadata.json": source / "outputs/apk/release/output-metadata.json",
        "direct-output-metadata.json": source / "outputs/apk/directRelease/output-metadata.json",
        "play-AndroidManifest.xml": source / "intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
        "direct-AndroidManifest.xml": source / "intermediates/merged_manifests/directRelease/processDirectReleaseManifest/AndroidManifest.xml",
        "raw-agp.aab": source / "outputs/canonical-bundle-audit/release/raw-agp.aab",
        "entry-map.tsv": source / "outputs/canonical-bundle-audit/release/entry-map.tsv",
        "canonical-SHA256SUMS": source / "outputs/canonical-bundle-audit/release/SHA256SUMS",
    }
    errors = {}
    for prefix, variant in (("play", "release"), ("direct", "directRelease")):
        directory = source / "outputs/apk" / variant
        try:
            paths[f"{prefix}-unsigned.apk"] = compiler_apk(directory)
        except (OSError, ValueError, KeyError, TypeError) as error:
            errors[f"{prefix}-unsigned.apk"] = str(error)
    files = {}
    for name, path in paths.items():
        try:
            if path.is_symlink() or not path.is_file():
                raise ValueError("missing or nonregular build output")
            shutil.copyfile(path, output / name)
            files[name] = {"sha256": digest(output / name), "bytes": path.stat().st_size}
        except (OSError, ValueError) as error:
            errors[name] = str(error)
    report = {"files": files, "missing_or_unavailable": errors, "diagnostics_only": True}
    (output / "EVIDENCE.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--output", type=Path)
    mode.add_argument("--verify-canonical", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.verify_canonical:
        verify_canonical(root)
    else:
        collect(root, args.output)
