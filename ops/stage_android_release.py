#!/usr/bin/env python3
"""Verify and stage one immutable Android direct-release artifact.

This is an operator tool, not an app endpoint.  It runs from the trusted
release workstation, verifies the exact CI manifest and APK with Android SDK
tools, copies the APK to a temporary VPS path, publishes it with Linux
``renameat2(RENAME_NOREPLACE)``, verifies the public HTTPS bytes, and registers
an immutable *staged* release through the backend's internal CLI.

It intentionally cannot activate or withdraw a release and never reads or
changes ``ANDROID_MIN_SUPPORTED_VERSION_CODE``.  Those status transitions use
the protected owner workflow, which re-verifies the public artifact again.
"""

from __future__ import annotations

import argparse
import base64
import ctypes
import errno
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

try:
    from ops.android_update_channel import (
        AdvertisedAndroidRelease,
        AndroidUpdateChannelError,
        verify_public_artifact,
    )
except ModuleNotFoundError:  # direct execution: python ops/stage_android_release.py
    from android_update_channel import (  # type: ignore[no-redef]
        AdvertisedAndroidRelease,
        AndroidUpdateChannelError,
        verify_public_artifact,
    )


EXPECTED_APPLICATION_ID = "cloud.dcompany.erp"
EXPECTED_VARIANT = "directRelease"
FIRST_SERVER_DELIVERED_VERSION_CODE = 15
DEFAULT_BASE_URL = "https://dcompany.duckdns.org"
DEFAULT_REMOTE_ROOT = "/opt/d-company-erp"
DEFAULT_ATTESTATION_ROOT = "/var/lib/dcompany-erp/android-releases/attestations"
_SAFE_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GIT_SHA = re.compile(r"^[0-9a-f]{40}$")
_PRINTABLE_RELEASE_NOTES = re.compile(r"^[\x20-\x7e]{1,2000}$")
_REMOTE_ROOT = re.compile(r"^/[A-Za-z0-9._/-]+$")
_SSH_HOST = re.compile(r"^(?:[A-Za-z0-9._-]+@)?[A-Za-z0-9.-]+$")


class AndroidReleaseStagingError(RuntimeError):
    """The release cannot be staged without weakening an invariant."""


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    payload: dict[str, Any] = {}
    for key, value in pairs:
        if key in payload:
            raise AndroidReleaseStagingError(f"JSON repeats key {key!r}")
        payload[key] = value
    return payload


@dataclass(frozen=True)
class CiReleaseManifest:
    application_id: str
    variant: str
    version_code: int
    version_name: str
    apk_filename: str
    apk_download_url: str
    apk_sha256: str
    apk_size_bytes: int
    signing_certificate_sha256: str
    git_sha: str
    release_ref: str
    workflow_run_id: str
    workflow_run_attempt: str
    source_manifest_sha256: str


@dataclass(frozen=True)
class AndroidToolEvidence:
    apkanalyzer: str
    apksigner: str
    application_id: str
    version_code: int
    version_name: str
    signing_certificate_sha256: str
    verified_v2_or_newer: bool


@dataclass(frozen=True)
class RemoteTarget:
    host: str
    key: Path
    port: int
    root: str

    @property
    def ssh_base(self) -> list[str]:
        return [
            "ssh",
            "-T",
            "-p",
            str(self.port),
            "-i",
            str(self.key),
            "-o",
            "BatchMode=yes",
            "-o",
            "IdentitiesOnly=yes",
            self.host,
        ]


def _normalized_sha256(raw: Any, label: str) -> str:
    if not isinstance(raw, str):
        raise AndroidReleaseStagingError(f"{label} must be a 64-hex string")
    normalized = raw.strip().lower().replace(":", "")
    if _SHA256.fullmatch(normalized) is None:
        raise AndroidReleaseStagingError(f"{label} must be a 64-hex string")
    return normalized


def _positive_int(raw: Any, label: str) -> int:
    if type(raw) is not int or not 1 <= raw <= 2_147_483_647:
        raise AndroidReleaseStagingError(f"{label} must be a positive 32-bit integer")
    return raw


def _positive_decimal_string(raw: Any, label: str, *, maximum: int) -> int:
    if not isinstance(raw, str) or not raw.isascii() or not raw.isdigit():
        raise AndroidReleaseStagingError(
            f"{label} must be a canonical positive decimal string"
        )
    value = int(raw)
    if not 1 <= value <= maximum or raw != str(value):
        raise AndroidReleaseStagingError(f"{label} is outside its supported range")
    return value


def _validated_base_url(raw: str) -> str:
    candidate = raw.rstrip("/")
    parsed = urlsplit(candidate)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        raise AndroidReleaseStagingError(
            "base URL must be a credential-free HTTPS origin"
        )
    return candidate


def _validated_remote_target(target: RemoteTarget) -> RemoteTarget:
    if _SSH_HOST.fullmatch(target.host) is None:
        raise AndroidReleaseStagingError("SSH host is invalid")
    if not 1 <= target.port <= 65_535:
        raise AndroidReleaseStagingError("SSH port is invalid")
    if (
        _REMOTE_ROOT.fullmatch(target.root) is None
        or ".." in Path(target.root).parts
        or target.root == "/"
    ):
        raise AndroidReleaseStagingError("remote root is invalid")
    return target


def load_ci_manifest(path: Path, *, base_url: str) -> CiReleaseManifest:
    base_url = _validated_base_url(base_url)
    try:
        if path.is_symlink() or not path.is_file():
            raise AndroidReleaseStagingError(
                "CI release manifest must be a regular non-symlink file"
            )
        manifest_size = path.stat().st_size
        if not 1 <= manifest_size <= 64 * 1024:
            raise AndroidReleaseStagingError("CI release manifest size is unsafe")
        raw_bytes = path.read_bytes()
        payload = json.loads(raw_bytes, object_pairs_hook=_reject_duplicate_json_keys)
    except AndroidReleaseStagingError:
        raise
    except (OSError, json.JSONDecodeError) as exc:
        raise AndroidReleaseStagingError(
            f"cannot read CI release manifest: {exc}"
        ) from exc
    if not isinstance(payload, dict):
        raise AndroidReleaseStagingError("CI release manifest must be a JSON object")
    required = {
        "api_base_url",
        "application_id",
        "variant",
        "version_code",
        "version_name",
        "apk_filename",
        "apk_download_url",
        "apk_sha256",
        "apk_size_bytes",
        "signing_certificate_sha256",
        "git_sha",
        "release_ref",
        "workflow_run_id",
        "workflow_run_attempt",
    }
    missing = sorted(required - payload.keys())
    unexpected = sorted(payload.keys() - required)
    if missing or unexpected:
        detail = []
        if missing:
            detail.append("missing " + ", ".join(missing))
        if unexpected:
            detail.append("unexpected " + ", ".join(unexpected))
        raise AndroidReleaseStagingError(
            "CI release manifest schema differs: " + "; ".join(detail)
        )
    if payload["api_base_url"] != f"{base_url}/api/v1/":
        raise AndroidReleaseStagingError(
            "CI manifest has the wrong production API origin"
        )
    if payload["application_id"] != EXPECTED_APPLICATION_ID:
        raise AndroidReleaseStagingError("CI manifest has the wrong Android package")
    if payload["variant"] != EXPECTED_VARIANT:
        raise AndroidReleaseStagingError(
            "CI manifest is not the directRelease artifact"
        )
    version_code = _positive_int(payload["version_code"], "version_code")
    if version_code < FIRST_SERVER_DELIVERED_VERSION_CODE:
        raise AndroidReleaseStagingError(
            "server delivery begins at version code "
            f"{FIRST_SERVER_DELIVERED_VERSION_CODE}"
        )
    version_name = payload["version_name"]
    if (
        not isinstance(version_name, str)
        or not version_name.strip()
        or len(version_name) > 80
    ):
        raise AndroidReleaseStagingError("version_name is invalid")
    filename = payload["apk_filename"]
    if not isinstance(filename, str) or _SAFE_FILENAME.fullmatch(filename) is None:
        raise AndroidReleaseStagingError("apk_filename is unsafe")
    expected_url = f"{base_url.rstrip('/')}/downloads/android/{filename}"
    if payload["apk_download_url"] != expected_url:
        raise AndroidReleaseStagingError(
            "apk_download_url does not match the controlled immutable release URL"
        )
    size = _positive_int(payload["apk_size_bytes"], "apk_size_bytes")
    if size > 512 * 1024 * 1024:
        raise AndroidReleaseStagingError("APK exceeds the supported 512 MiB limit")
    git_sha = payload["git_sha"]
    if not isinstance(git_sha, str) or _GIT_SHA.fullmatch(git_sha) is None:
        raise AndroidReleaseStagingError("git_sha must be a full lowercase Git SHA")
    for key in ("release_ref", "workflow_run_id", "workflow_run_attempt"):
        value = payload[key]
        if not isinstance(value, str) or not value.strip() or len(value) > 200:
            raise AndroidReleaseStagingError(f"{key} is invalid")
    if payload["release_ref"] != f"v{version_name.strip()}":
        raise AndroidReleaseStagingError("release_ref does not match version_name")
    _positive_decimal_string(
        payload["workflow_run_id"],
        "workflow_run_id",
        maximum=9_223_372_036_854_775_807,
    )
    _positive_decimal_string(
        payload["workflow_run_attempt"],
        "workflow_run_attempt",
        maximum=2_147_483_647,
    )
    expected_filename = f"d-company-erp-{payload['release_ref']}-direct.apk"
    if filename != expected_filename:
        raise AndroidReleaseStagingError(
            "APK filename does not match the tagged direct release"
        )
    return CiReleaseManifest(
        application_id=EXPECTED_APPLICATION_ID,
        variant=EXPECTED_VARIANT,
        version_code=version_code,
        version_name=version_name.strip(),
        apk_filename=filename,
        apk_download_url=expected_url,
        apk_sha256=_normalized_sha256(payload["apk_sha256"], "apk_sha256"),
        apk_size_bytes=size,
        signing_certificate_sha256=_normalized_sha256(
            payload["signing_certificate_sha256"],
            "signing_certificate_sha256",
        ),
        git_sha=git_sha,
        release_ref=payload["release_ref"].strip(),
        workflow_run_id=payload["workflow_run_id"].strip(),
        workflow_run_attempt=payload["workflow_run_attempt"].strip(),
        source_manifest_sha256=hashlib.sha256(raw_bytes).hexdigest(),
    )


def _sha256_file(path: Path) -> tuple[str, int]:
    if path.is_symlink() or not path.is_file():
        raise AndroidReleaseStagingError("APK input must be a regular non-symlink file")
    digest = hashlib.sha256()
    total = 0
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                total += len(chunk)
                digest.update(chunk)
    except OSError as exc:
        raise AndroidReleaseStagingError(f"cannot read APK: {exc}") from exc
    return digest.hexdigest(), total


def verify_ci_apk_bytes(apk: Path, manifest: CiReleaseManifest) -> None:
    if apk.name != manifest.apk_filename:
        raise AndroidReleaseStagingError("APK filename differs from the CI manifest")
    digest, size = _sha256_file(apk)
    if size != manifest.apk_size_bytes:
        raise AndroidReleaseStagingError("APK size differs from the CI manifest")
    if digest != manifest.apk_sha256:
        raise AndroidReleaseStagingError("APK SHA-256 differs from the CI manifest")


def _discover_android_tool(name: str, explicit: str | None) -> str:
    if explicit:
        path = Path(explicit).expanduser()
        if path.is_file() and os.access(path, os.X_OK):
            return str(path)
        raise AndroidReleaseStagingError(f"configured {name} is not executable")
    direct = shutil.which(name)
    if direct:
        return direct
    sdk_roots = [
        os.getenv("ANDROID_SDK_ROOT"),
        os.getenv("ANDROID_HOME"),
        str(Path.home() / "Library/Android/sdk"),
    ]
    candidates: list[Path] = []
    for raw_root in sdk_roots:
        if not raw_root:
            continue
        root = Path(raw_root).expanduser()
        if name == "apkanalyzer":
            candidates.extend(root.glob("cmdline-tools/*/bin/apkanalyzer"))
            candidates.append(root / "tools/bin/apkanalyzer")
        else:
            candidates.extend(root.glob(f"build-tools/*/{name}"))
    executable = sorted(
        (
            candidate
            for candidate in candidates
            if candidate.is_file() and os.access(candidate, os.X_OK)
        ),
        reverse=True,
    )
    if not executable:
        raise AndroidReleaseStagingError(
            f"{name} is required; install the Android SDK tool or pass --{name}"
        )
    return str(executable[0])


def _run_checked(command: Sequence[str]) -> str:
    try:
        result = subprocess.run(
            list(command),
            check=True,
            capture_output=True,
            text=True,
            timeout=120,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise AndroidReleaseStagingError(
            f"Android verification command failed: {Path(command[0]).name}"
        ) from exc
    return (result.stdout + result.stderr).strip()


def verify_apk_identity_and_signer(
    apk: Path,
    manifest: CiReleaseManifest,
    *,
    apkanalyzer_path: str | None = None,
    apksigner_path: str | None = None,
) -> AndroidToolEvidence:
    apkanalyzer = _discover_android_tool("apkanalyzer", apkanalyzer_path)
    apksigner = _discover_android_tool("apksigner", apksigner_path)
    application_id = (
        _run_checked([apkanalyzer, "manifest", "application-id", str(apk)])
        .splitlines()[-1]
        .strip()
    )
    raw_version_code = (
        _run_checked([apkanalyzer, "manifest", "version-code", str(apk)])
        .splitlines()[-1]
        .strip()
    )
    version_name = (
        _run_checked([apkanalyzer, "manifest", "version-name", str(apk)])
        .splitlines()[-1]
        .strip()
    )
    try:
        version_code = int(raw_version_code)
    except ValueError as exc:
        raise AndroidReleaseStagingError(
            "apkanalyzer returned an invalid version code"
        ) from exc
    certificate_output = _run_checked(
        [apksigner, "verify", "--verbose", "--print-certs", str(apk)]
    )
    signer_matches = re.findall(
        r"Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f:]{64,95})",
        certificate_output,
    )
    signers = {_normalized_sha256(value, "APK signer") for value in signer_matches}
    if len(signers) != 1:
        raise AndroidReleaseStagingError(
            "APK must have exactly one current signing certificate"
        )
    verified_modern = any(
        re.search(
            rf"Verified using v{scheme} scheme.*:\s*true",
            certificate_output,
            flags=re.IGNORECASE,
        )
        for scheme in (2, 3, 4)
    )
    if not verified_modern:
        raise AndroidReleaseStagingError(
            "APK does not verify with a modern Android signature scheme"
        )
    signer = next(iter(signers))
    expected = (
        manifest.application_id,
        manifest.version_code,
        manifest.version_name,
        manifest.signing_certificate_sha256,
    )
    actual = application_id, version_code, version_name, signer
    if actual != expected:
        raise AndroidReleaseStagingError(
            "APK package, version, name, or signer differs from the CI manifest"
        )
    return AndroidToolEvidence(
        apkanalyzer=apkanalyzer,
        apksigner=apksigner,
        application_id=application_id,
        version_code=version_code,
        version_name=version_name,
        signing_certificate_sha256=signer,
        verified_v2_or_newer=True,
    )


def registry_manifest(
    ci: CiReleaseManifest,
    *,
    release_notes: str,
) -> dict[str, Any]:
    notes = release_notes.strip()
    if _PRINTABLE_RELEASE_NOTES.fullmatch(notes) is None:
        raise AndroidReleaseStagingError(
            "release notes must be 1-2000 printable ASCII characters on one line"
        )
    return {
        "version_code": ci.version_code,
        "version_name": ci.version_name,
        "channel": "direct",
        "update_url": ci.apk_download_url,
        "release_notes": notes,
        "apk_sha256": ci.apk_sha256,
        "apk_size_bytes": ci.apk_size_bytes,
        "apk_signing_cert_sha256": ci.signing_certificate_sha256,
        "source_git_sha": ci.git_sha,
        "source_release_ref": ci.release_ref,
        "source_workflow_run_id": int(ci.workflow_run_id),
        "source_workflow_run_attempt": int(ci.workflow_run_attempt),
    }


def canonical_json(payload: dict[str, Any]) -> bytes:
    return (json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n").encode()


def _ssh_json(
    target: RemoteTarget, action: str, payload: dict[str, Any]
) -> dict[str, Any]:
    encoded = base64.urlsafe_b64encode(canonical_json(payload)).decode("ascii")
    remote_script = f"{target.root}/ops/stage_android_release.py"
    command = [
        *target.ssh_base,
        "--",
        "python3",
        remote_script,
        "_remote",
        action,
        encoded,
    ]
    try:
        result = subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            timeout=180,
        )
        response = json.loads(result.stdout)
    except (
        OSError,
        subprocess.CalledProcessError,
        subprocess.TimeoutExpired,
        json.JSONDecodeError,
    ) as exc:
        raise AndroidReleaseStagingError(f"remote {action} operation failed") from exc
    if not isinstance(response, dict) or response.get("ok") is not True:
        raise AndroidReleaseStagingError(
            f"remote {action} operation was not acknowledged"
        )
    return response


def _scp_apk(target: RemoteTarget, apk: Path, remote_path: str) -> None:
    command = [
        "scp",
        "-P",
        str(target.port),
        "-i",
        str(target.key),
        "-o",
        "BatchMode=yes",
        "-o",
        "IdentitiesOnly=yes",
        "--",
        str(apk),
        f"{target.host}:{remote_path}",
    ]
    try:
        subprocess.run(command, check=True, timeout=15 * 60)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise AndroidReleaseStagingError(
            "secure copy to the release staging path failed"
        ) from exc


def _register_staged_release(
    target: RemoteTarget, manifest: dict[str, Any]
) -> dict[str, Any]:
    expected_manifest_sha256 = hashlib.sha256(canonical_json(manifest)).hexdigest()
    command = [
        *target.ssh_base,
        "--",
        "docker",
        "compose",
        "-f",
        f"{target.root}/docker-compose.prod.yml",
        "--env-file",
        f"{target.root}/.env",
        "exec",
        "-T",
        "backend",
        "python",
        "-m",
        "scripts.register_android_release",
        "--manifest",
        "-",
    ]
    try:
        result = subprocess.run(
            command,
            input=canonical_json(manifest),
            check=True,
            capture_output=True,
            timeout=180,
        )
        response = json.loads(result.stdout)
    except (
        OSError,
        subprocess.CalledProcessError,
        subprocess.TimeoutExpired,
        json.JSONDecodeError,
    ) as exc:
        raise AndroidReleaseStagingError(
            "backend refused the immutable staged-release registration"
        ) from exc
    if (
        not isinstance(response, dict)
        or response.get("status") != "staged"
        or not isinstance(response.get("id"), str)
        or response.get("manifest_sha256") != expected_manifest_sha256
    ):
        raise AndroidReleaseStagingError(
            "backend staging CLI returned an invalid receipt"
        )
    return response


def stage_release(args: argparse.Namespace) -> dict[str, Any]:
    base_url = _validated_base_url(args.base_url)
    ci = load_ci_manifest(args.manifest, base_url=base_url)
    expected_signer = _normalized_sha256(
        args.expected_signer_sha256,
        "expected_signer_sha256",
    )
    if ci.signing_certificate_sha256 != expected_signer:
        raise AndroidReleaseStagingError(
            "CI manifest signer differs from the independently trusted signer"
        )
    verify_ci_apk_bytes(args.apk, ci)
    tools = verify_apk_identity_and_signer(
        args.apk,
        ci,
        apkanalyzer_path=args.apkanalyzer,
        apksigner_path=args.apksigner,
    )
    strict_manifest = registry_manifest(ci, release_notes=args.release_notes)
    manifest_bytes = canonical_json(strict_manifest)
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()

    target = _validated_remote_target(
        RemoteTarget(
            host=args.ssh_host,
            key=args.ssh_key.expanduser().resolve(),
            port=args.ssh_port,
            root=args.remote_root.rstrip("/"),
        )
    )
    token = uuid.uuid4().hex
    remote_temp = f"{target.root}/releases/android/.{ci.apk_filename}.{token}.part"
    remote_final = f"{target.root}/releases/android/{ci.apk_filename}"

    plan = {
        "version_code": ci.version_code,
        "version_name": ci.version_name,
        "apk_filename": ci.apk_filename,
        "apk_sha256": ci.apk_sha256,
        "apk_size_bytes": ci.apk_size_bytes,
        "ci_manifest_sha256": ci.source_manifest_sha256,
        "registry_manifest_sha256": manifest_sha256,
        "remote_final": remote_final,
        "update_url": ci.apk_download_url,
    }
    if not args.apply:
        return {"ok": True, "applied": False, "plan": plan}

    if not target.key.is_file():
        raise AndroidReleaseStagingError("SSH identity file does not exist")

    prepared = _ssh_json(
        target,
        "prepare",
        {
            "remote_root": target.root,
            "remote_temp": remote_temp,
            "remote_final": remote_final,
            "apk_filename": ci.apk_filename,
            "apk_sha256": ci.apk_sha256,
            "apk_size_bytes": ci.apk_size_bytes,
        },
    )
    if prepared.get("already_published") is not True:
        _scp_apk(target, args.apk, remote_temp)
        _ssh_json(
            target,
            "publish",
            {
                "remote_root": target.root,
                "remote_temp": remote_temp,
                "remote_final": remote_final,
                "apk_filename": ci.apk_filename,
                "apk_sha256": ci.apk_sha256,
                "apk_size_bytes": ci.apk_size_bytes,
            },
        )

    release = AdvertisedAndroidRelease(
        version_code=ci.version_code,
        version_name=ci.version_name,
        url=ci.apk_download_url,
        sha256=ci.apk_sha256,
        size_bytes=ci.apk_size_bytes,
        signing_certificate_sha256=ci.signing_certificate_sha256,
    )
    try:
        public = verify_public_artifact(release, download_body=True)
    except AndroidUpdateChannelError as exc:
        raise AndroidReleaseStagingError(str(exc)) from exc

    registered = _register_staged_release(target, strict_manifest)
    attestation = {
        "schema_version": 1,
        "action": "android_release_staged",
        "status": "staged",
        "recorded_at": datetime.now(UTC).isoformat(),
        "release_id": registered["id"],
        "ci_manifest": asdict(ci),
        "registry_manifest": strict_manifest,
        "registry_manifest_sha256": manifest_sha256,
        "android_tool_evidence": {
            **asdict(tools),
            "apkanalyzer": Path(tools.apkanalyzer).name,
            "apksigner": Path(tools.apksigner).name,
        },
        "public_verification": {
            "sha256": public.sha256,
            "size_bytes": public.size_bytes,
            "headers": public.headers,
        },
        "source": {
            "git_sha": ci.git_sha,
            "release_ref": ci.release_ref,
            "workflow_run_id": ci.workflow_run_id,
            "workflow_run_attempt": ci.workflow_run_attempt,
        },
        "authority_boundary": (
            "Staging does not advertise this release. A protected owner must activate "
            "it through the ERP after a second server-side public-byte verification."
        ),
    }
    attested = _ssh_json(
        target,
        "attest",
        {
            "release_id": registered["id"],
            "version_code": ci.version_code,
            "attestation": attestation,
        },
    )
    return {
        "ok": True,
        "applied": True,
        "release_id": registered["id"],
        "status": "staged",
        "attestation": attested["attestation"],
        "owner_action_required": "Review and activate the staged release in Owner ERP.",
    }


def _decode_remote_payload(encoded: str) -> dict[str, Any]:
    try:
        raw = base64.urlsafe_b64decode(encoded.encode("ascii"))
        payload = json.loads(raw, object_pairs_hook=_reject_duplicate_json_keys)
    except (ValueError, UnicodeError, json.JSONDecodeError) as exc:
        raise AndroidReleaseStagingError("remote payload is invalid") from exc
    if not isinstance(payload, dict):
        raise AndroidReleaseStagingError("remote payload must be an object")
    return payload


def _within_release_directory(
    raw: Any,
    *,
    filename: str,
    remote_root: Any = DEFAULT_REMOTE_ROOT,
) -> Path:
    if not isinstance(raw, str):
        raise AndroidReleaseStagingError("remote path is invalid")
    if not isinstance(remote_root, str) or not Path(remote_root).is_absolute():
        raise AndroidReleaseStagingError("remote root is invalid")
    raw_root = Path(remote_root)
    if raw_root.is_symlink():
        raise AndroidReleaseStagingError("remote root must not be a symlink")
    root = raw_root.resolve()
    raw_release_dir = root / "releases/android"
    if raw_release_dir.is_symlink():
        raise AndroidReleaseStagingError("release directory must not be a symlink")
    if not raw_release_dir.is_dir():
        raise AndroidReleaseStagingError("release directory does not exist")
    release_dir = raw_release_dir.resolve()
    if release_dir != raw_release_dir:
        raise AndroidReleaseStagingError("release directory resolved unexpectedly")
    candidate = Path(raw)
    if candidate.parent.resolve() != release_dir:
        raise AndroidReleaseStagingError("remote path escaped the release directory")
    if filename not in candidate.name:
        raise AndroidReleaseStagingError(
            "remote path does not match the release filename"
        )
    return candidate


def _atomic_rename_noreplace(source: Path, destination: Path) -> None:
    """Linux atomic rename that refuses an existing destination."""
    libc = ctypes.CDLL(None, use_errno=True)
    renameat2 = getattr(libc, "renameat2", None)
    if renameat2 is None:
        raise AndroidReleaseStagingError("host kernel does not expose renameat2")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        -100,
        os.fsencode(source),
        -100,
        os.fsencode(destination),
        1,  # RENAME_NOREPLACE
    )
    if result == 0:
        return
    error = ctypes.get_errno()
    if error == errno.EEXIST:
        raise AndroidReleaseStagingError("immutable APK filename already exists")
    raise AndroidReleaseStagingError(f"atomic APK promotion failed with errno {error}")


def _remote_action(action: str, payload: dict[str, Any]) -> dict[str, Any]:
    filename = payload.get("apk_filename")
    if action in {"prepare", "publish"}:
        if not isinstance(filename, str) or _SAFE_FILENAME.fullmatch(filename) is None:
            raise AndroidReleaseStagingError("remote APK filename is unsafe")
        remote_root = payload.get("remote_root", DEFAULT_REMOTE_ROOT)
        temporary = _within_release_directory(
            payload.get("remote_temp"),
            filename=filename,
            remote_root=remote_root,
        )
        final = _within_release_directory(
            payload.get("remote_final"),
            filename=filename,
            remote_root=remote_root,
        )
        expected_temporary_name = re.compile(
            rf"^\.{re.escape(filename)}\.[0-9a-f]{{32}}\.part$"
        )
        if expected_temporary_name.fullmatch(temporary.name) is None:
            raise AndroidReleaseStagingError(
                "temporary APK path is not a generated staging name"
            )
        if final.name != filename:
            raise AndroidReleaseStagingError(
                "final APK path does not use the exact filename"
            )
        if action == "prepare":
            expected_size = _positive_int(
                payload.get("apk_size_bytes"), "apk_size_bytes"
            )
            expected_sha = _normalized_sha256(payload.get("apk_sha256"), "apk_sha256")
            if final.is_symlink():
                raise AndroidReleaseStagingError("immutable APK filename is a symlink")
            if final.exists():
                actual_sha, actual_size = _sha256_file(final)
                if actual_size != expected_size or actual_sha != expected_sha:
                    raise AndroidReleaseStagingError(
                        "immutable APK filename already exists with different bytes"
                    )
                return {"ok": True, "already_published": True}
            if temporary.exists() or temporary.is_symlink():
                raise AndroidReleaseStagingError("temporary APK path already exists")
            return {"ok": True, "already_published": False}
        if temporary.is_symlink() or not temporary.is_file():
            raise AndroidReleaseStagingError("uploaded APK is not a regular file")
        expected_size = _positive_int(payload.get("apk_size_bytes"), "apk_size_bytes")
        expected_sha = _normalized_sha256(payload.get("apk_sha256"), "apk_sha256")
        actual_sha, actual_size = _sha256_file(temporary)
        if actual_size != expected_size or actual_sha != expected_sha:
            temporary.unlink(missing_ok=True)
            raise AndroidReleaseStagingError(
                "uploaded APK failed exact byte verification"
            )
        os.chmod(temporary, 0o644)
        _atomic_rename_noreplace(temporary, final)
        directory_fd = os.open(final.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        return {"ok": True, "published": str(final)}

    if action == "attest":
        release_id = payload.get("release_id")
        version_code = _positive_int(payload.get("version_code"), "version_code")
        attestation = payload.get("attestation")
        if not isinstance(release_id, str) or not re.fullmatch(
            r"[0-9a-fA-F-]{36}", release_id
        ):
            raise AndroidReleaseStagingError("release_id is invalid")
        if not isinstance(attestation, dict):
            raise AndroidReleaseStagingError("attestation must be an object")
        root = Path(DEFAULT_ATTESTATION_ROOT)
        if root.is_symlink():
            raise AndroidReleaseStagingError(
                "attestation directory must not be a symlink"
            )
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
        data = json.dumps(attestation, indent=2, sort_keys=True).encode() + b"\n"
        content_sha256 = hashlib.sha256(data).hexdigest()
        destination = root / (
            f"{stamp}-code-{version_code}-{release_id}-{content_sha256}.json"
        )
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
        try:
            with os.fdopen(descriptor, "wb", closefd=False) as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        finally:
            os.close(descriptor)
        os.chmod(destination, 0o444)
        directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        return {
            "ok": True,
            "attestation": str(destination),
            "attestation_sha256": content_sha256,
        }
    raise AndroidReleaseStagingError("unsupported remote action")


def _remote_main(action: str, encoded: str) -> int:
    try:
        result = _remote_action(action, _decode_remote_payload(encoded))
        print(json.dumps(result, sort_keys=True))
        return 0
    except AndroidReleaseStagingError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, sort_keys=True))
        return 1


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--release-notes", required=True)
    parser.add_argument(
        "--expected-signer-sha256",
        required=True,
        help=(
            "trusted certificate fingerprint from the preserved baseline, "
            "never from this candidate"
        ),
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--ssh-host", default="root@dcompany.duckdns.org")
    parser.add_argument("--ssh-key", type=Path, default=Path("~/.ssh/dcompany_erp"))
    parser.add_argument("--ssh-port", type=int, default=22)
    parser.add_argument("--remote-root", default=DEFAULT_REMOTE_ROOT)
    parser.add_argument("--apkanalyzer")
    parser.add_argument("--apksigner")
    parser.add_argument(
        "--apply",
        action="store_true",
        help=(
            "stage immutable bytes and register a staged record; "
            "default is verification only"
        ),
    )
    return parser


def main() -> int:
    if len(sys.argv) >= 4 and sys.argv[1] == "_remote":
        return _remote_main(sys.argv[2], sys.argv[3])
    parser = _build_parser()
    args = parser.parse_args()
    try:
        result = stage_release(args)
    except AndroidReleaseStagingError as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
