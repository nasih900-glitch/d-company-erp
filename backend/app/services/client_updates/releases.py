"""Strict manifest parsing and public-byte verification for Android releases."""

from __future__ import annotations

import hashlib
import hmac
import json
import re
from dataclasses import dataclass
from typing import Literal
from urllib.parse import SplitResult, urlsplit

import httpx
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

_CONTROLLED_APK_PATH_RE = re.compile(r"^/downloads/android/[A-Za-z0-9][A-Za-z0-9._-]*\.apk$")


class AndroidReleaseManifest(BaseModel):
    """The exact CI/operator handoff accepted by the staging CLI."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    # Code 14 is the manually installed update-capable baseline.  It must
    # never be entered into the optional server-delivery registry; code 15 is
    # the first artifact this channel may advertise.
    version_code: int = Field(ge=15, le=2_147_483_647)
    version_name: str = Field(min_length=1, max_length=80, pattern=r"^[0-9A-Za-z][0-9A-Za-z._+-]*$")
    channel: Literal["direct"]
    update_url: str = Field(min_length=1, max_length=1000)
    release_notes: str = Field(min_length=1, max_length=2000)
    apk_sha256: str = Field(pattern=r"^[0-9a-fA-F:]{64,95}$")
    apk_size_bytes: int = Field(ge=1, le=512 * 1024 * 1024)
    apk_signing_cert_sha256: str = Field(pattern=r"^[0-9a-fA-F:]{64,95}$")
    source_git_sha: str = Field(pattern=r"^[0-9a-f]{40}$")
    source_release_ref: str = Field(min_length=2, max_length=81)
    source_workflow_run_id: int = Field(ge=1, le=9_223_372_036_854_775_807)
    source_workflow_run_attempt: int = Field(ge=1, le=2_147_483_647)

    @field_validator("version_name", "release_notes", "source_release_ref")
    @classmethod
    def strip_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("value cannot be blank")
        if any(ord(char) < 32 or ord(char) > 126 for char in normalized):
            raise ValueError("value must be printable single-line ASCII")
        return normalized

    @field_validator("apk_sha256", "apk_signing_cert_sha256")
    @classmethod
    def normalize_sha256(cls, value: str) -> str:
        normalized = value.replace(":", "").lower()
        if len(normalized) != 64 or any(char not in "0123456789abcdef" for char in normalized):
            raise ValueError("must be a SHA-256 hex digest")
        return normalized

    @field_validator("update_url")
    @classmethod
    def validate_update_url(cls, value: str) -> str:
        normalized = value.strip()
        parsed = _safe_direct_apk_url(normalized)
        return parsed.geturl()

    @model_validator(mode="after")
    def validate_source_release_ref(self) -> AndroidReleaseManifest:
        if self.source_release_ref != f"v{self.version_name}":
            raise ValueError("source release ref must equal v followed by version name")
        return self


@dataclass(frozen=True, slots=True)
class AndroidReleaseFingerprint:
    id: object
    channel: str
    version_code: int
    version_name: str
    update_url: str
    release_notes: str
    apk_sha256: str
    apk_size_bytes: int
    apk_signing_cert_sha256: str
    manifest_sha256: str
    source_git_sha: str
    source_release_ref: str
    source_workflow_run_id: int
    source_workflow_run_attempt: int
    status: str


class ArtifactVerificationError(RuntimeError):
    """Safe categorical failure; never contains response bodies or credentials."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _safe_direct_apk_url(value: str) -> SplitResult:
    parsed = urlsplit(value)
    try:
        _ = parsed.port
    except ValueError as exc:
        raise ValueError("update URL contains an invalid port") from exc
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or parsed.query
        or _CONTROLLED_APK_PATH_RE.fullmatch(parsed.path) is None
    ):
        raise ValueError(
            "update URL must use the credential-free HTTPS /downloads/android/<safe>.apk channel"
        )
    return parsed


def _origin(parsed: SplitResult) -> tuple[str, str, int]:
    return (parsed.scheme, parsed.hostname or "", parsed.port or 443)


def _safe_allowed_origin(value: str) -> SplitResult:
    parsed = urlsplit(value)
    try:
        _ = parsed.port
    except ValueError as exc:
        raise ValueError("allowed update origin contains an invalid port") from exc
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or parsed.query
        or parsed.path not in {"", "/"}
    ):
        raise ValueError("allowed update origin is invalid")
    return parsed


def require_allowed_update_origin(*, update_url: str, configured_url: str | None) -> None:
    """Restrict server-side verification to the deployment's configured origin."""
    candidate = _safe_direct_apk_url(update_url)
    if configured_url is None:
        raise ArtifactVerificationError("update_origin_not_configured")
    try:
        configured = _safe_allowed_origin(configured_url)
    except ValueError as exc:
        raise ArtifactVerificationError("update_origin_not_configured") from exc
    if _origin(candidate) != _origin(configured):
        raise ArtifactVerificationError("update_origin_not_allowed")


def canonical_manifest_bytes(manifest: AndroidReleaseManifest) -> bytes:
    return json.dumps(
        manifest.model_dump(mode="json"),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("utf-8")


def manifest_sha256(manifest: AndroidReleaseManifest) -> str:
    return hashlib.sha256(canonical_manifest_bytes(manifest)).hexdigest()


async def verify_public_apk(
    release: AndroidReleaseFingerprint,
    *,
    configured_update_url: str | None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> None:
    """Stream and verify public bytes without retaining the APK in the backend."""
    require_allowed_update_origin(
        update_url=release.update_url,
        configured_url=configured_update_url,
    )
    digest = hashlib.sha256()
    byte_count = 0
    prefix = bytearray()
    timeout = httpx.Timeout(connect=10.0, read=60.0, write=10.0, pool=10.0)
    try:
        async with (
            httpx.AsyncClient(
                timeout=timeout,
                follow_redirects=False,
                trust_env=False,
                transport=transport,
            ) as client,
            client.stream(
                "GET",
                release.update_url,
                headers={
                    "Accept": "application/vnd.android.package-archive",
                    "Accept-Encoding": "identity",
                    "User-Agent": "DCompany-Release-Verifier/1",
                },
            ) as response,
        ):
            if response.status_code != 200:
                raise ArtifactVerificationError("artifact_http_error")
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0]
            if content_type.strip().lower() != "application/vnd.android.package-archive":
                raise ArtifactVerificationError("artifact_content_type_invalid")
            if response.headers.get("X-Content-Type-Options", "").lower() != "nosniff":
                raise ArtifactVerificationError("artifact_nosniff_missing")
            cache_tokens = {
                token.strip().lower()
                for token in response.headers.get("Cache-Control", "").split(",")
                if token.strip()
            }
            required_cache_tokens = {"public", "immutable", "no-transform"}
            if not required_cache_tokens.issubset(cache_tokens):
                raise ArtifactVerificationError("artifact_cache_policy_invalid")
            max_age = next((token for token in cache_tokens if token.startswith("max-age=")), None)
            try:
                max_age_seconds = int(max_age.split("=", 1)[1]) if max_age else 0
            except ValueError as exc:
                raise ArtifactVerificationError("artifact_cache_policy_invalid") from exc
            if max_age_seconds < 31_536_000:
                raise ArtifactVerificationError("artifact_cache_policy_invalid")
            content_length = response.headers.get("Content-Length")
            if content_length is None:
                raise ArtifactVerificationError("artifact_size_invalid")
            try:
                declared_size = int(content_length)
            except ValueError as exc:
                raise ArtifactVerificationError("artifact_size_invalid") from exc
            if declared_size != release.apk_size_bytes:
                raise ArtifactVerificationError("artifact_size_mismatch")
            async for chunk in response.aiter_bytes():
                byte_count += len(chunk)
                if byte_count > release.apk_size_bytes:
                    raise ArtifactVerificationError("artifact_size_mismatch")
                if len(prefix) < 4:
                    prefix.extend(chunk[: 4 - len(prefix)])
                digest.update(chunk)
    except ArtifactVerificationError:
        raise
    except (httpx.HTTPError, OSError) as exc:
        raise ArtifactVerificationError("artifact_unreachable") from exc

    if bytes(prefix) != b"PK\x03\x04":
        raise ArtifactVerificationError("artifact_not_apk_archive")
    if byte_count != release.apk_size_bytes:
        raise ArtifactVerificationError("artifact_size_mismatch")
    if not hmac.compare_digest(digest.hexdigest(), release.apk_sha256):
        raise ArtifactVerificationError("artifact_checksum_mismatch")
