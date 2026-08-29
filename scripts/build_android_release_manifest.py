#!/usr/bin/env python3
"""Build fail-closed metadata for the signed direct Android update APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import NoReturn
from urllib.parse import urljoin, urlsplit

EXPECTED_APPLICATION_ID = "cloud.dcompany.erp"
EXPECTED_VARIANT = "directRelease"
DEFAULT_DOWNLOAD_BASE_URL = "https://dcompany.duckdns.org/downloads/android/"
MAX_APK_BYTES = 512 * 1024 * 1024
_SAFE_APK_FILENAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk")
_SHA256 = re.compile(r"[0-9a-f]{64}")


class ReleaseManifestError(ValueError):
    """Raised when an update package cannot be described safely."""


def normalize_signer_sha256(raw: str) -> str:
    digest = raw.strip().lower().replace(":", "")
    if _SHA256.fullmatch(digest) is None:
        raise ReleaseManifestError("signer SHA-256 must contain exactly 64 hex digits")
    return digest


def validate_apk_filename(filename: str) -> str:
    if _SAFE_APK_FILENAME.fullmatch(filename) is None:
        raise ReleaseManifestError(
            "APK filename must be a path-free ASCII name ending in .apk"
        )
    return filename


def validate_download_base_url(raw: str) -> str:
    parsed = urlsplit(raw)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or not raw.endswith("/")
    ):
        raise ReleaseManifestError(
            "download base URL must be an HTTPS directory URL without credentials, query, or fragment"
        )
    return raw


def _read_metadata(metadata_path: Path) -> tuple[str, str, int, str]:
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        elements = metadata["elements"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
        raise ReleaseManifestError(f"cannot read Android output metadata: {exc}") from exc

    if not isinstance(elements, list) or len(elements) != 1:
        count = len(elements) if isinstance(elements, list) else "invalid"
        raise ReleaseManifestError(f"expected one direct APK output, found {count}")

    element = elements[0]
    try:
        application_id = metadata["applicationId"]
        variant = metadata["variantName"]
        version_code = element["versionCode"]
        version_name = element["versionName"]
    except (KeyError, TypeError) as exc:
        raise ReleaseManifestError(f"Android output metadata is incomplete: {exc}") from exc

    if application_id != EXPECTED_APPLICATION_ID:
        raise ReleaseManifestError(
            f"unexpected application id {application_id!r}; expected {EXPECTED_APPLICATION_ID!r}"
        )
    if variant != EXPECTED_VARIANT:
        raise ReleaseManifestError(
            f"unexpected APK variant {variant!r}; expected {EXPECTED_VARIANT!r}"
        )
    if type(version_code) is not int or version_code <= 0:
        raise ReleaseManifestError("version code must be a positive integer")
    if not isinstance(version_name, str) or not version_name.strip():
        raise ReleaseManifestError("version name must be a non-empty string")

    return application_id, variant, version_code, version_name


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as package:
            for chunk in iter(lambda: package.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise ReleaseManifestError(f"cannot hash direct APK: {exc}") from exc
    return digest.hexdigest()


def build_release_manifest(
    *,
    apk_path: Path,
    metadata_path: Path,
    apk_filename: str,
    signer_sha256: str,
    release_ref: str,
    git_sha: str,
    workflow_run_id: str,
    workflow_run_attempt: str,
    download_base_url: str = DEFAULT_DOWNLOAD_BASE_URL,
) -> dict[str, object]:
    filename = validate_apk_filename(apk_filename)
    signer = normalize_signer_sha256(signer_sha256)
    base_url = validate_download_base_url(download_base_url)
    application_id, variant, version_code, version_name = _read_metadata(metadata_path)

    try:
        apk_size = apk_path.stat().st_size
    except OSError as exc:
        raise ReleaseManifestError(f"cannot inspect direct APK: {exc}") from exc
    if apk_size <= 0:
        raise ReleaseManifestError("direct APK must not be empty")
    if apk_size > MAX_APK_BYTES:
        raise ReleaseManifestError(
            f"direct APK must not exceed {MAX_APK_BYTES} bytes"
        )

    apk_sha256 = _sha256_file(apk_path)
    return {
        "api_base_url": "https://dcompany.duckdns.org/api/v1/",
        "application_id": application_id,
        "variant": variant,
        "version_code": version_code,
        "version_name": version_name,
        "apk_filename": filename,
        "apk_download_url": urljoin(base_url, filename),
        "apk_sha256": apk_sha256,
        "apk_size_bytes": apk_size,
        "signing_certificate_sha256": signer,
        "git_sha": git_sha,
        "release_ref": release_ref,
        "workflow_run_id": workflow_run_id,
        "workflow_run_attempt": workflow_run_attempt,
    }


def write_release_manifest(output_path: Path, manifest: dict[str, object]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_suffix(output_path.suffix + ".tmp")
    temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(output_path)


def _fail(message: str) -> NoReturn:
    print(f"Android release manifest failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--apk-filename", required=True)
    parser.add_argument("--signer-sha256", required=True)
    parser.add_argument("--release-ref", required=True)
    parser.add_argument("--git-sha", required=True)
    parser.add_argument("--workflow-run-id", required=True)
    parser.add_argument("--workflow-run-attempt", required=True)
    parser.add_argument("--download-base-url", default=DEFAULT_DOWNLOAD_BASE_URL)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    try:
        manifest = build_release_manifest(
            apk_path=args.apk,
            metadata_path=args.metadata,
            apk_filename=args.apk_filename,
            signer_sha256=args.signer_sha256,
            release_ref=args.release_ref,
            git_sha=args.git_sha,
            workflow_run_id=args.workflow_run_id,
            workflow_run_attempt=args.workflow_run_attempt,
            download_base_url=args.download_base_url,
        )
        write_release_manifest(args.output, manifest)
    except (OSError, ReleaseManifestError) as exc:
        _fail(str(exc))

    print(
        "Android direct-release manifest verified: "
        f"version={manifest['version_name']} ({manifest['version_code']}), "
        f"apk={manifest['apk_filename']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
