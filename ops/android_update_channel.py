#!/usr/bin/env python3
"""Fail-closed verification for D Company Android release metadata and bytes.

This module is deliberately dependency-free so the VPS runtime monitor, the
external GitHub monitor, and the release-promotion CLI all evaluate exactly the
same contract.  It never signs, uploads, activates, or withdraws a release.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

MAX_APK_BYTES = 512 * 1024 * 1024
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_APK_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$")


class AndroidUpdateChannelError(RuntimeError):
    """The advertised release channel is incomplete, unsafe, or inconsistent."""


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    payload: dict[str, Any] = {}
    for key, value in pairs:
        if key in payload:
            raise AndroidUpdateChannelError(
                f"compatibility response repeats JSON key {key!r}"
            )
        payload[key] = value
    return payload


@dataclass(frozen=True)
class AdvertisedAndroidRelease:
    version_code: int
    version_name: str
    url: str
    sha256: str
    size_bytes: int
    signing_certificate_sha256: str
    release_notes: str = ""

    @property
    def filename(self) -> str:
        return Path(urlsplit(self.url).path).name


@dataclass(frozen=True)
class ChannelProbe:
    minimum_supported_version_code: int
    latest_version_code: int
    release: AdvertisedAndroidRelease | None
    compatibility_payload: dict[str, Any]
    policy_revision: int = 0


@dataclass(frozen=True)
class ChannelMatrix:
    canonical: ChannelProbe
    probed_version_codes: tuple[int, ...]


@dataclass(frozen=True)
class ArtifactVerification:
    sha256: str
    size_bytes: int
    headers: dict[str, str]


def _positive_int(value: Any, label: str) -> int:
    if type(value) is not int or not 1 <= value <= 2_147_483_647:
        raise AndroidUpdateChannelError(f"{label} must be a positive 32-bit integer")
    return value


def _normalized_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise AndroidUpdateChannelError(f"{label} must be a 64-hex string")
    normalized = value.strip().lower().replace(":", "")
    if _SHA256.fullmatch(normalized) is None:
        raise AndroidUpdateChannelError(f"{label} must be a 64-hex string")
    return normalized


def _controlled_apk_url(raw: Any, base_url: str) -> str:
    if not isinstance(raw, str) or not raw.strip():
        raise AndroidUpdateChannelError("update_url must be a non-empty HTTPS URL")
    candidate = raw.strip()
    parsed = urlsplit(candidate)
    base = urlsplit(base_url.rstrip("/"))
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise AndroidUpdateChannelError(
            "update_url must be a credential-free HTTPS APK URL"
        )
    if (parsed.hostname, parsed.port) != (base.hostname, base.port):
        raise AndroidUpdateChannelError(
            "update_url must use the configured production origin"
        )
    expected_prefix = "/downloads/android/"
    if not parsed.path.startswith(expected_prefix):
        raise AndroidUpdateChannelError(
            "update_url must use the controlled /downloads/android/ channel"
        )
    filename = parsed.path.removeprefix(expected_prefix)
    if "/" in filename or _APK_FILENAME.fullmatch(filename) is None:
        raise AndroidUpdateChannelError("update_url contains an unsafe APK filename")
    return candidate


def parse_compatibility_payload(
    payload: Any,
    *,
    base_url: str,
    requested_version_code: int,
) -> ChannelProbe:
    """Validate one public compatibility response as a complete atomic contract."""
    if not isinstance(payload, dict):
        raise AndroidUpdateChannelError("compatibility response must be a JSON object")
    if payload.get("platform") != "android":
        raise AndroidUpdateChannelError(
            "compatibility response platform is not android"
        )
    if payload.get("current_version_code") != requested_version_code:
        raise AndroidUpdateChannelError(
            "compatibility response echoed the wrong version code"
        )

    minimum = _positive_int(
        payload.get("minimum_supported_version_code"),
        "minimum_supported_version_code",
    )
    latest = _positive_int(payload.get("latest_version_code"), "latest_version_code")
    policy_revision = _positive_int(payload.get("policy_revision"), "policy_revision")
    if latest < minimum:
        raise AndroidUpdateChannelError(
            "latest_version_code is below the compatibility minimum"
        )

    expected_status = (
        "update_required"
        if requested_version_code < minimum
        else "update_available"
        if requested_version_code < latest
        else "supported"
    )
    if payload.get("status") != expected_status:
        raise AndroidUpdateChannelError(
            f"compatibility status must be {expected_status!r} for this version"
        )

    metadata = (
        payload.get("update_url"),
        payload.get("latest_version_name"),
        payload.get("release_notes"),
        payload.get("apk_sha256"),
        payload.get("apk_size_bytes"),
        payload.get("apk_signing_cert_sha256"),
    )
    if not any(value is not None for value in metadata):
        return ChannelProbe(minimum, latest, None, payload, policy_revision)
    if not all(value is not None for value in metadata):
        raise AndroidUpdateChannelError(
            "advertised APK metadata is only partially populated"
        )

    version_name = payload["latest_version_name"]
    if (
        not isinstance(version_name, str)
        or not version_name.strip()
        or len(version_name.strip()) > 80
    ):
        raise AndroidUpdateChannelError("latest_version_name is invalid")
    release_notes = payload["release_notes"]
    if (
        not isinstance(release_notes, str)
        or not release_notes.strip()
        or len(release_notes.strip()) > 2000
        or any(ord(character) < 32 for character in release_notes)
    ):
        raise AndroidUpdateChannelError("release_notes is invalid")
    size = _positive_int(payload["apk_size_bytes"], "apk_size_bytes")
    if size > MAX_APK_BYTES:
        raise AndroidUpdateChannelError(f"advertised APK exceeds {MAX_APK_BYTES} bytes")
    release = AdvertisedAndroidRelease(
        version_code=latest,
        version_name=version_name.strip(),
        url=_controlled_apk_url(payload["update_url"], base_url),
        sha256=_normalized_sha256(payload["apk_sha256"], "apk_sha256"),
        size_bytes=size,
        signing_certificate_sha256=_normalized_sha256(
            payload["apk_signing_cert_sha256"],
            "apk_signing_cert_sha256",
        ),
        release_notes=release_notes.strip(),
    )
    return ChannelProbe(minimum, latest, release, payload, policy_revision)


def fetch_channel_probe(
    base_url: str,
    *,
    version_code: int = 1,
    timeout_seconds: float = 15,
) -> ChannelProbe:
    endpoint = (
        f"{base_url.rstrip('/')}/api/v1/public/client-compatibility"
        f"?platform=android&version_code={version_code}"
    )
    request = urllib.request.Request(
        endpoint,
        headers={"Accept": "application/json", "Cache-Control": "no-cache"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            if response.status != 200:
                raise AndroidUpdateChannelError(
                    f"compatibility endpoint returned HTTP {response.status}"
                )
            if response.geturl() != endpoint:
                raise AndroidUpdateChannelError("compatibility endpoint redirected")
            raw = response.read(256 * 1024 + 1)
            response_headers = {
                str(key).lower(): str(value).strip()
                for key, value in response.headers.items()
            }
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise AndroidUpdateChannelError(
            f"compatibility endpoint is unreachable: {type(exc).__name__}"
        ) from exc
    if len(raw) > 256 * 1024:
        raise AndroidUpdateChannelError("compatibility response is unexpectedly large")
    try:
        payload = json.loads(raw, object_pairs_hook=_reject_duplicate_json_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AndroidUpdateChannelError(
            "compatibility response is not valid JSON"
        ) from exc
    content_type = response_headers.get("content-type", "").split(";", 1)[0].lower()
    if content_type != "application/json":
        raise AndroidUpdateChannelError(
            "compatibility response has an unsafe Content-Type"
        )
    probe = parse_compatibility_payload(
        payload,
        base_url=base_url,
        requested_version_code=version_code,
    )
    cache_tokens = {
        token.strip().lower()
        for token in response_headers.get("cache-control", "").split(",")
        if token.strip()
    }
    if "no-store" not in cache_tokens:
        raise AndroidUpdateChannelError(
            "compatibility policy must use Cache-Control: no-store"
        )
    header_revision = response_headers.get("x-client-compatibility-policy-revision")
    if header_revision != str(probe.policy_revision):
        raise AndroidUpdateChannelError(
            "compatibility policy revision header does not match JSON"
        )
    return probe


def fetch_channel_matrix(
    base_url: str,
    *,
    baseline_version_code: int = 14,
    timeout_seconds: float = 15,
) -> ChannelMatrix:
    """Verify old, minimum, baseline, previous, and current client decisions."""
    _positive_int(baseline_version_code, "baseline_version_code")
    canonical = fetch_channel_probe(
        base_url,
        version_code=1,
        timeout_seconds=timeout_seconds,
    )
    versions = {
        1,
        canonical.minimum_supported_version_code,
        baseline_version_code,
        canonical.latest_version_code,
    }
    if canonical.latest_version_code > canonical.minimum_supported_version_code:
        versions.add(canonical.latest_version_code - 1)

    for version_code in sorted(versions - {1}):
        candidate = fetch_channel_probe(
            base_url,
            version_code=version_code,
            timeout_seconds=timeout_seconds,
        )
        if (
            candidate.minimum_supported_version_code
            != canonical.minimum_supported_version_code
            or candidate.latest_version_code != canonical.latest_version_code
            or candidate.policy_revision != canonical.policy_revision
            or candidate.release != canonical.release
        ):
            raise AndroidUpdateChannelError(
                "compatibility metadata changed across client-version probes"
            )
    return ChannelMatrix(canonical, tuple(sorted(versions)))


def _validate_artifact_headers(
    headers: Any,
    *,
    expected_size: int,
) -> dict[str, str]:
    normalized = {
        str(key).lower(): str(value).strip() for key, value in headers.items()
    }
    content_type = normalized.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type != "application/vnd.android.package-archive":
        raise AndroidUpdateChannelError("APK response has an unsafe Content-Type")
    if normalized.get("x-content-type-options", "").lower() != "nosniff":
        raise AndroidUpdateChannelError(
            "APK response is missing X-Content-Type-Options: nosniff"
        )
    cache_tokens = {
        token.strip().lower()
        for token in normalized.get("cache-control", "").split(",")
        if token.strip()
    }
    required_cache_tokens = {"public", "immutable", "no-transform"}
    if not required_cache_tokens.issubset(cache_tokens):
        raise AndroidUpdateChannelError(
            "APK response is missing immutable cache controls"
        )
    max_age = next(
        (token for token in cache_tokens if token.startswith("max-age=")),
        None,
    )
    try:
        max_age_seconds = int(max_age.split("=", 1)[1]) if max_age else 0
    except ValueError as exc:
        raise AndroidUpdateChannelError("APK response has an invalid max-age") from exc
    if max_age_seconds < 31_536_000:
        raise AndroidUpdateChannelError(
            "APK immutable cache lifetime is less than one year"
        )
    try:
        content_length = int(normalized.get("content-length", ""))
    except ValueError as exc:
        raise AndroidUpdateChannelError(
            "APK response has no valid Content-Length"
        ) from exc
    if content_length != expected_size:
        raise AndroidUpdateChannelError(
            "APK Content-Length differs from advertised size"
        )
    return normalized


def verify_public_artifact(
    release: AdvertisedAndroidRelease,
    *,
    timeout_seconds: float = 90,
    download_body: bool = True,
) -> ArtifactVerification:
    """Verify public headers and, when requested, every advertised APK byte."""
    request = urllib.request.Request(
        release.url,
        method="GET" if download_body else "HEAD",
        headers={
            "Accept": (
                "application/vnd.android.package-archive, application/octet-stream"
            ),
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            if response.status != 200:
                raise AndroidUpdateChannelError(
                    f"APK URL returned HTTP {response.status}"
                )
            if response.geturl() != release.url:
                raise AndroidUpdateChannelError(
                    "APK URL redirected away from its immutable URL"
                )
            headers = _validate_artifact_headers(
                response.headers,
                expected_size=release.size_bytes,
            )
            if not download_body:
                return ArtifactVerification(release.sha256, release.size_bytes, headers)
            digest = hashlib.sha256()
            total = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > release.size_bytes:
                    raise AndroidUpdateChannelError(
                        "APK response exceeded advertised size"
                    )
                digest.update(chunk)
    except AndroidUpdateChannelError:
        raise
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise AndroidUpdateChannelError(
            f"APK URL is unreachable: {type(exc).__name__}"
        ) from exc
    actual_sha = digest.hexdigest()
    if total != release.size_bytes:
        raise AndroidUpdateChannelError("APK response was shorter than advertised")
    if actual_sha != release.sha256:
        raise AndroidUpdateChannelError(
            "public APK SHA-256 differs from advertised metadata"
        )
    return ArtifactVerification(actual_sha, total, headers)


def verify_local_artifact(
    path: Path, release: AdvertisedAndroidRelease
) -> ArtifactVerification:
    try:
        if path.is_symlink() or not path.is_file():
            raise AndroidUpdateChannelError("hosted APK is not a regular file")
        size = path.stat().st_size
        if size != release.size_bytes:
            raise AndroidUpdateChannelError(
                "hosted APK size differs from advertised metadata"
            )
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    except AndroidUpdateChannelError:
        raise
    except OSError as exc:
        raise AndroidUpdateChannelError("hosted APK could not be read") from exc
    actual_sha = digest.hexdigest()
    if actual_sha != release.sha256:
        raise AndroidUpdateChannelError(
            "hosted APK SHA-256 differs from advertised metadata"
        )
    return ArtifactVerification(actual_sha, size, {})


def _main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="https://dcompany.duckdns.org")
    parser.add_argument("--version-code", type=int, default=1)
    parser.add_argument("--baseline-version-code", type=int, default=14)
    parser.add_argument("--headers-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.version_code != 1:
            probe = fetch_channel_probe(args.base_url, version_code=args.version_code)
            probed_version_codes = (args.version_code,)
        else:
            matrix = fetch_channel_matrix(
                args.base_url,
                baseline_version_code=args.baseline_version_code,
            )
            probe = matrix.canonical
            probed_version_codes = matrix.probed_version_codes
        result: dict[str, Any] = {
            "ok": True,
            "minimum_supported_version_code": probe.minimum_supported_version_code,
            "latest_version_code": probe.latest_version_code,
            "policy_revision": probe.policy_revision,
            "release_advertised": probe.release is not None,
            "probed_version_codes": probed_version_codes,
        }
        if probe.release is not None:
            artifact = verify_public_artifact(
                probe.release,
                download_body=not args.headers_only,
            )
            result.update(
                {
                    "version_name": probe.release.version_name,
                    "apk_sha256": artifact.sha256,
                    "apk_size_bytes": artifact.size_bytes,
                }
            )
        print(json.dumps(result, sort_keys=True))
        return 0
    except AndroidUpdateChannelError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, sort_keys=True))
        return 1


if __name__ == "__main__":
    raise SystemExit(_main())
