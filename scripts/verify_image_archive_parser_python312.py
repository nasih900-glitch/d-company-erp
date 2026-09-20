#!/usr/bin/env python3
"""Dependency-free Python 3.12 smoke and malformed-archive parser contract."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tests" / "fixtures"))

from image_archive_identity_fixtures import (  # noqa: E402
    blob_path,
    rewrite_tar,
    write_classic_archive,
    write_oci_index_archive,
)


VERIFIER = ROOT / "infra" / "scripts" / "verify-image-archive-identity.py"


def _run(
    path: Path,
    runtime_id: str,
    *,
    expected_type: str | None = None,
    expected_diagnostic: str | None = None,
) -> dict[str, str]:
    completed = subprocess.run(
        [sys.executable, str(VERIFIER), str(path), runtime_id, "python312"],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if expected_type is not None:
        if completed.returncode != 0 or completed.stderr:
            raise RuntimeError(
                f"unexpected parser status {completed.returncode}: {completed.stderr.strip()}"
            )
        fields: dict[str, str] = {}
        for line in completed.stdout.splitlines():
            key, separator, value = line.partition("=")
            if not separator or key in fields:
                raise RuntimeError("parser success output is malformed")
            fields[key] = value
        required = {
            "python312_verified_archive_digest",
            "python312_runtime_image_id",
            "python312_runtime_image_id_type",
            "python312_runnable_manifest_digest",
            "python312_scanner_config_image_id",
            "python312_archive_platform",
        }
        if set(fields) != required:
            raise RuntimeError("parser success output fields are incomplete or unexpected")
        if fields["python312_runtime_image_id"] != runtime_id:
            raise RuntimeError("parser success output has the wrong runtime identity")
        if fields["python312_runtime_image_id_type"] != expected_type:
            raise RuntimeError("parser success output has the wrong archive identity type")
        if fields["python312_archive_platform"] != "linux/amd64":
            raise RuntimeError("parser success output has the wrong archive platform")
        return fields

    if expected_diagnostic is None:
        raise ValueError("a success type or rejection diagnostic is required")
    if (
        completed.returncode != 1
        or completed.stdout
        or expected_diagnostic not in completed.stderr
        or "Archive identity verification failed for python312:" not in completed.stderr
    ):
        raise RuntimeError(
            f"unexpected parser rejection {completed.returncode}: {completed.stderr.strip()}"
        )
    return {}


def main() -> int:
    if sys.version_info[:2] != (3, 12):
        raise SystemExit(
            f"archive parser compatibility gate requires Python 3.12, got {sys.version.split()[0]}"
        )
    with tempfile.TemporaryDirectory(prefix="dcompany-parser-312-") as temporary:
        root = Path(temporary)
        classic = write_classic_archive(root / "classic.tar")
        modern = write_oci_index_archive(root / "containerd.tar")
        classic_fields = _run(classic.path, classic.runtime_id, expected_type="config")
        modern_fields = _run(modern.path, modern.runtime_id, expected_type="oci-index")
        if classic_fields["python312_runnable_manifest_digest"] != "none":
            raise RuntimeError("classic archive unexpectedly reported a runnable OCI manifest")
        if modern_fields["python312_runnable_manifest_digest"] != modern.manifest_digest:
            raise RuntimeError("OCI archive reported the wrong runnable manifest")
        _run(
            modern.path,
            "sha256:" + "0" * 64,
            expected_diagnostic="runtime image ID does not match the top OCI index descriptor",
        )

        missing_config = root / "missing-config.tar"
        rewrite_tar(
            modern.path,
            missing_config,
            lambda members: [
                member for member in members if member[0] != blob_path(modern.config_id)
            ],
        )
        _run(
            missing_config,
            modern.runtime_id,
            expected_diagnostic="missing regular image config member",
        )

        unsafe = root / "unsafe-member.tar"
        rewrite_tar(
            classic.path,
            unsafe,
            lambda members: [*members, ("../escape", b"untrusted", None)],
        )
        _run(
            unsafe,
            classic.runtime_id,
            expected_diagnostic="archive member path is unsafe",
        )

        bad_attestation = write_oci_index_archive(
            root / "bad-attestation.tar", invalid_attestation_reference=True
        )
        _run(
            bad_attestation.path,
            bad_attestation.runtime_id,
            expected_diagnostic="attestation does not reference the runnable manifest",
        )

    print(
        json.dumps(
            {
                "passed": True,
                "python_version": sys.version.split()[0],
                "positive_shapes": ["classic", "oci-index-with-attestation"],
                "negative_cases": [
                    "wrong-runtime-id",
                    "missing-config-blob",
                    "unsafe-member",
                    "invalid-attestation-link",
                ],
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
