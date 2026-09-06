#!/usr/bin/env python3
"""Verify and stage one immutable Android direct-release artifact.

This is an operator tool, not an app endpoint.  It runs from the trusted
release workstation, verifies the exact CI manifest and APK with Android SDK
tools, streams the APK into a private root-owned VPS directory, publishes it
with Linux ``renameat2(RENAME_NOREPLACE)``, verifies the public HTTPS bytes, and registers
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
import fcntl
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import uuid
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, BinaryIO
from urllib.parse import urlsplit

try:
    from ops.android_update_channel import (
        AdvertisedAndroidRelease,
        AndroidUpdateChannelError,
        verify_public_artifact,
    )
    from ops.runtime_release_parity import RuntimeParityError, inspect_release_pair
except ModuleNotFoundError:  # direct execution: python ops/stage_android_release.py
    from android_update_channel import (  # type: ignore[no-redef]
        AdvertisedAndroidRelease,
        AndroidUpdateChannelError,
        verify_public_artifact,
    )
    from runtime_release_parity import (  # type: ignore[no-redef]
        RuntimeParityError,
        inspect_release_pair,
    )


EXPECTED_APPLICATION_ID = "cloud.dcompany.erp"
EXPECTED_VARIANT = "directRelease"
FIRST_SERVER_DELIVERED_VERSION_CODE = 15
DEFAULT_BASE_URL = "https://dcompany.duckdns.org"
DEFAULT_REMOTE_ROOT = "/opt/d-company-erp"
DEFAULT_ATTESTATION_ROOT = "/var/lib/dcompany-erp/android-releases/attestations"
DEFAULT_PRODUCTION_INSTALL_LOCK = Path(
    "/run/d-company-erp/production-install.lock"
)
PRODUCTION_INSTALL_LOCK = str(DEFAULT_PRODUCTION_INSTALL_LOCK)
PRIVATE_UPLOAD_DIRECTORY_NAME = ".d-company-erp-android-release-staging"
TRUSTED_REMOTE_OWNER_UID = 0
TRUSTED_REMOTE_OWNER_GID = 0
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
            "--",
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
    user, separator, hostname = target.host.partition("@")
    if (
        _SSH_HOST.fullmatch(target.host) is None
        or target.host.startswith("-")
        or hostname.startswith("-")
        or (separator and user.startswith("-"))
    ):
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


def _validated_runtime_root(raw: Any) -> str:
    if (
        not isinstance(raw, str)
        or _REMOTE_ROOT.fullmatch(raw) is None
        or ".." in Path(raw).parts
        or raw == "/"
    ):
        raise AndroidReleaseStagingError("Runtime inspection root is invalid")
    return raw


def _inspect_running_release(payload: dict[str, Any]) -> dict[str, Any]:
    root = _validated_runtime_root(payload.get("remote_root", DEFAULT_REMOTE_ROOT))
    try:
        return inspect_release_pair(
            root,
            str(Path(root) / ".env"),
            payload.get("version_name"),
            payload.get("source_git_sha"),
            running=True,
        )
    except RuntimeParityError as exc:
        raise AndroidReleaseStagingError(str(exc)) from exc


@contextmanager
def _production_install_lock(
    lock_file: str | Path | None = None,
) -> Iterator[None]:
    """Exclude the hardened installer without waiting behind a deployment."""
    path = Path(lock_file or PRODUCTION_INSTALL_LOCK)
    if not path.is_absolute() or not path.name:
        raise AndroidReleaseStagingError(
            "D Company production installer lock path is invalid"
        )
    is_production_path = path == DEFAULT_PRODUCTION_INSTALL_LOCK
    expected_uid = 0 if is_production_path else os.geteuid()
    expected_gid = 0 if is_production_path else os.getegid()
    nofollow = getattr(os, "O_NOFOLLOW", None)
    directory = getattr(os, "O_DIRECTORY", None)
    if nofollow is None or directory is None:
        raise AndroidReleaseStagingError(
            "This host cannot safely open the production installer lock"
        )

    # The installer establishes this private root-owned parent.  Creating it
    # here is safe because the subsequent descriptor open refuses a symlink and
    # validates the exact owner and mode before the lock pathname is touched.
    try:
        path.parent.mkdir(mode=0o700, parents=False, exist_ok=True)
    except OSError as exc:
        raise AndroidReleaseStagingError(
            "Cannot establish the D Company production lock directory"
        ) from exc

    directory_flags = os.O_RDONLY | directory | nofollow | getattr(os, "O_CLOEXEC", 0)
    try:
        parent_descriptor = os.open(path.parent, directory_flags)
    except OSError as exc:
        raise AndroidReleaseStagingError(
            "Cannot safely open the D Company production lock directory"
        ) from exc
    try:
        parent_stat = os.fstat(parent_descriptor)
        if (
            not stat.S_ISDIR(parent_stat.st_mode)
            or parent_stat.st_uid != expected_uid
            or parent_stat.st_gid != expected_gid
            or stat.S_IMODE(parent_stat.st_mode) != 0o700
        ):
            raise AndroidReleaseStagingError(
                "D Company production lock directory failed ownership or mode validation"
            )

        lock_flags = os.O_RDWR | nofollow | getattr(os, "O_CLOEXEC", 0)
        try:
            descriptor = os.open(
                path.name,
                lock_flags | os.O_CREAT | os.O_EXCL,
                0o600,
                dir_fd=parent_descriptor,
            )
        except FileExistsError:
            try:
                descriptor = os.open(path.name, lock_flags, dir_fd=parent_descriptor)
            except OSError as exc:
                raise AndroidReleaseStagingError(
                    "Cannot safely open the D Company production installer lock"
                ) from exc
        except OSError as exc:
            raise AndroidReleaseStagingError(
                "Cannot create the D Company production installer lock"
            ) from exc

        try:
            lock_stat = os.fstat(descriptor)
            if (
                not stat.S_ISREG(lock_stat.st_mode)
                or lock_stat.st_uid != expected_uid
                or lock_stat.st_gid != expected_gid
                or lock_stat.st_nlink != 1
                or stat.S_IMODE(lock_stat.st_mode) != 0o600
            ):
                raise AndroidReleaseStagingError(
                    "D Company production installer lock failed ownership, mode, or link validation"
                )
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                if exc.errno in {errno.EACCES, errno.EAGAIN, errno.EWOULDBLOCK}:
                    raise AndroidReleaseStagingError(
                        "Another D Company production install or upgrade is already running"
                    ) from exc
                raise AndroidReleaseStagingError(
                    "Cannot acquire the D Company production installer lock"
                ) from exc
            yield
        finally:
            os.close(descriptor)
    finally:
        os.close(parent_descriptor)


def _ssh_json(
    target: RemoteTarget, action: str, payload: dict[str, Any]
) -> dict[str, Any]:
    # Keep the transport boundary self-validating.  ``stage_release`` already
    # validates its target, but these helpers must not become an option-
    # injection path if a future operator flow calls them directly.
    target = _validated_remote_target(target)
    encoded = base64.urlsafe_b64encode(canonical_json(payload)).decode("ascii")
    remote_script = f"{target.root}/ops/stage_android_release.py"
    command = [
        *target.ssh_base,
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
            # Finalization combines host inspection and the backend CLI in one
            # remote process, so its outer timeout must exceed the CLI timeout.
            timeout=390 if action == "finalize" else 180,
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


def _ssh_upload_apk(
    target: RemoteTarget,
    apk: Path,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Stream the APK to a remote fd-safe uploader; never expose an SCP pathname."""
    target = _validated_remote_target(target)
    encoded = base64.urlsafe_b64encode(canonical_json(payload)).decode("ascii")
    remote_script = f"{target.root}/ops/stage_android_release.py"
    command = [
        *target.ssh_base,
        "python3",
        remote_script,
        "_remote",
        "upload",
        encoded,
    ]
    try:
        with apk.open("rb") as stream:
            result = subprocess.run(
                command,
                stdin=stream,
                check=True,
                capture_output=True,
                timeout=15 * 60,
            )
        response = json.loads(result.stdout)
    except (
        OSError,
        subprocess.CalledProcessError,
        subprocess.TimeoutExpired,
        json.JSONDecodeError,
    ) as exc:
        raise AndroidReleaseStagingError("secure APK stream upload failed") from exc
    if not isinstance(response, dict) or response.get("ok") is not True:
        raise AndroidReleaseStagingError("remote APK upload was not acknowledged")
    return response


def _validated_registration_receipt(
    response: Any, manifest: dict[str, Any]
) -> dict[str, Any]:
    expected_manifest_sha256 = hashlib.sha256(canonical_json(manifest)).hexdigest()
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


def _register_staged_release_on_host(
    remote_root: str, manifest: dict[str, Any]
) -> dict[str, Any]:
    root = _validated_runtime_root(remote_root)
    command = [
        "docker",
        "compose",
        "-f",
        f"{root}/docker-compose.prod.yml",
        "--env-file",
        f"{root}/.env",
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
    return _validated_registration_receipt(response, manifest)


def _finalize_staged_release(
    target: RemoteTarget,
    manifest: dict[str, Any],
    *,
    initial_runtime: dict[str, Any],
    version_name: str,
    source_git_sha: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    response = _ssh_json(
        target,
        "finalize",
        {
            "remote_root": target.root,
            "version_name": version_name,
            "source_git_sha": source_git_sha,
            "initial_runtime_parity": initial_runtime,
            "manifest": manifest,
        },
    )
    final_runtime = response.get("runtime_parity")
    if final_runtime != initial_runtime:
        raise AndroidReleaseStagingError(
            "Remote finalization returned inconsistent runtime evidence"
        )
    registered = _validated_registration_receipt(response.get("registered"), manifest)
    return registered, final_runtime


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

    runtime_payload = {
        "remote_root": target.root,
        "version_name": ci.version_name,
        "source_git_sha": ci.git_sha,
    }
    # This read-only guard precedes prepare, upload, publication and registration.
    runtime = _ssh_json(target, "runtime", runtime_payload)["runtime_parity"]

    prepared = _ssh_json(
        target,
        "prepare",
        {
            **runtime_payload,
            "remote_root": target.root,
            "apk_filename": ci.apk_filename,
            "apk_sha256": ci.apk_sha256,
            "apk_size_bytes": ci.apk_size_bytes,
        },
    )
    if prepared.get("already_published") is not True:
        _ssh_upload_apk(
            target,
            args.apk,
            {
                **runtime_payload,
                "remote_root": target.root,
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

    registered, final_runtime = _finalize_staged_release(
        target,
        strict_manifest,
        initial_runtime=runtime,
        version_name=ci.version_name,
        source_git_sha=ci.git_sha,
    )
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
        "runtime_parity": final_runtime,
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


def _directory_open_flags() -> int:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    directory = getattr(os, "O_DIRECTORY", None)
    if nofollow is None or directory is None:
        raise AndroidReleaseStagingError(
            "This host cannot safely open Android release directories"
        )
    return os.O_RDONLY | directory | nofollow | getattr(os, "O_CLOEXEC", 0)


def _validate_trusted_directory(
    descriptor: int,
    label: str,
    *,
    exact_mode: int | None = None,
    allow_system_root: bool = False,
) -> None:
    metadata = os.fstat(descriptor)
    mode = stat.S_IMODE(metadata.st_mode)
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or (
            (metadata.st_uid, metadata.st_gid)
            != (TRUSTED_REMOTE_OWNER_UID, TRUSTED_REMOTE_OWNER_GID)
            and not (allow_system_root and metadata.st_uid == 0)
        )
        or mode & 0o022
        or (exact_mode is not None and mode != exact_mode)
    ):
        raise AndroidReleaseStagingError(
            f"{label} must be a trusted root-owned directory"
        )


@contextmanager
def _open_release_directories(remote_root: Any) -> Iterator[tuple[int, int]]:
    """Hold private-upload and public-release dirfds across the whole operation."""
    root = Path(_validated_runtime_root(remote_root))
    flags = _directory_open_flags()
    descriptors: list[int] = []
    try:
        try:
            ancestor_fd = os.open("/", flags)
        except OSError as exc:
            raise AndroidReleaseStagingError(
                "Cannot safely open the filesystem root"
            ) from exc
        descriptors.append(ancestor_fd)
        _validate_trusted_directory(
            ancestor_fd,
            "Filesystem root",
            allow_system_root=True,
        )
        for component in root.parts[1:]:
            try:
                child_fd = os.open(component, flags, dir_fd=ancestor_fd)
            except OSError as exc:
                raise AndroidReleaseStagingError(
                    "Cannot safely traverse the ERP root"
                ) from exc
            descriptors.append(child_fd)
            _validate_trusted_directory(
                child_fd,
                "ERP path component",
                allow_system_root=True,
            )
            ancestor_fd = child_fd
        root_fd = ancestor_fd
        _validate_trusted_directory(root_fd, "ERP root")

        try:
            releases_fd = os.open("releases", flags, dir_fd=root_fd)
            descriptors.append(releases_fd)
            _validate_trusted_directory(releases_fd, "Android releases parent")
            public_fd = os.open("android", flags, dir_fd=releases_fd)
            descriptors.append(public_fd)
            _validate_trusted_directory(public_fd, "Public Android release directory")
        except OSError as exc:
            raise AndroidReleaseStagingError(
                "Cannot safely open the public Android release directory"
            ) from exc

        try:
            os.mkdir(PRIVATE_UPLOAD_DIRECTORY_NAME, 0o700, dir_fd=root_fd)
        except FileExistsError:
            pass
        except OSError as exc:
            raise AndroidReleaseStagingError(
                "Cannot create the private Android upload directory"
            ) from exc
        try:
            staging_fd = os.open(PRIVATE_UPLOAD_DIRECTORY_NAME, flags, dir_fd=root_fd)
        except OSError as exc:
            raise AndroidReleaseStagingError(
                "Cannot safely open the private Android upload directory"
            ) from exc
        descriptors.append(staging_fd)
        _validate_trusted_directory(
            staging_fd,
            "Private Android upload directory",
            exact_mode=0o700,
        )
        yield staging_fd, public_fd
    finally:
        for descriptor in reversed(descriptors):
            os.close(descriptor)


def _validate_regular_file_descriptor(
    descriptor: int,
    label: str,
    *,
    exact_mode: int | None = None,
) -> None:
    metadata = os.fstat(descriptor)
    mode = stat.S_IMODE(metadata.st_mode)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != TRUSTED_REMOTE_OWNER_UID
        or metadata.st_gid != TRUSTED_REMOTE_OWNER_GID
        or metadata.st_nlink != 1
        or mode & 0o022
        or (exact_mode is not None and mode != exact_mode)
    ):
        raise AndroidReleaseStagingError(
            f"{label} failed regular-file ownership, link, or mode validation"
        )


def _open_existing_release(public_fd: int, filename: str) -> int | None:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        raise AndroidReleaseStagingError("This host cannot safely open an existing APK")
    flags = os.O_RDONLY | os.O_NONBLOCK | nofollow | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(filename, flags, dir_fd=public_fd)
    except FileNotFoundError:
        return None
    except OSError as exc:
        raise AndroidReleaseStagingError(
            "Cannot safely open the immutable APK filename"
        ) from exc
    try:
        _validate_regular_file_descriptor(descriptor, "Immutable APK")
    except Exception:
        os.close(descriptor)
        raise
    return descriptor


def _sha256_descriptor(descriptor: int) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    try:
        os.lseek(descriptor, 0, os.SEEK_SET)
        while chunk := os.read(descriptor, 1024 * 1024):
            total += len(chunk)
            digest.update(chunk)
        os.lseek(descriptor, 0, os.SEEK_SET)
    except OSError as exc:
        raise AndroidReleaseStagingError(
            "Cannot hash the staged APK descriptor"
        ) from exc
    return digest.hexdigest(), total


def _existing_release_matches(
    public_fd: int,
    filename: str,
    *,
    expected_sha: str,
    expected_size: int,
) -> bool:
    descriptor = _open_existing_release(public_fd, filename)
    if descriptor is None:
        return False
    try:
        actual_sha, actual_size = _sha256_descriptor(descriptor)
    finally:
        os.close(descriptor)
    if actual_size != expected_size or actual_sha != expected_sha:
        raise AndroidReleaseStagingError(
            "immutable APK filename already exists with different bytes"
        )
    return True


def _write_all(descriptor: int, content: bytes) -> None:
    view = memoryview(content)
    while view:
        try:
            written = os.write(descriptor, view)
        except OSError as exc:
            raise AndroidReleaseStagingError("Cannot write the staged APK") from exc
        if written <= 0:
            raise AndroidReleaseStagingError("Cannot write the staged APK")
        view = view[written:]


def _copy_exact_upload(
    source: BinaryIO,
    destination_fd: int,
    *,
    expected_sha: str,
    expected_size: int,
) -> None:
    total = 0
    digest = hashlib.sha256()
    while True:
        chunk = source.read(min(1024 * 1024, expected_size - total + 1))
        if not isinstance(chunk, bytes):
            raise AndroidReleaseStagingError("APK upload stream returned invalid bytes")
        if not chunk:
            break
        total += len(chunk)
        if total > expected_size:
            raise AndroidReleaseStagingError("uploaded APK exceeds the expected size")
        digest.update(chunk)
        _write_all(destination_fd, chunk)
    if total != expected_size or digest.hexdigest() != expected_sha:
        raise AndroidReleaseStagingError("uploaded APK failed exact byte verification")
    try:
        os.fsync(destination_fd)
    except OSError as exc:
        raise AndroidReleaseStagingError("Cannot sync the staged APK") from exc
    descriptor_sha, descriptor_size = _sha256_descriptor(destination_fd)
    if descriptor_size != expected_size or descriptor_sha != expected_sha:
        raise AndroidReleaseStagingError(
            "staged APK descriptor failed exact verification"
        )


class _ImmutableDestinationExists(AndroidReleaseStagingError):
    pass


def _atomic_rename_noreplace_at(
    source_dir_fd: int,
    source_name: str,
    destination_dir_fd: int,
    destination_name: str,
) -> None:
    """Linux dirfd-relative atomic rename that refuses an existing destination."""
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
        source_dir_fd,
        os.fsencode(source_name),
        destination_dir_fd,
        os.fsencode(destination_name),
        1,  # RENAME_NOREPLACE
    )
    if result == 0:
        return
    error = ctypes.get_errno()
    if error == errno.EEXIST:
        raise _ImmutableDestinationExists("immutable APK filename already exists")
    raise AndroidReleaseStagingError(f"atomic APK promotion failed with errno {error}")


def _receive_and_publish_apk(
    *,
    remote_root: str,
    filename: str,
    expected_sha: str,
    expected_size: int,
    source: BinaryIO,
) -> dict[str, Any]:
    """Receive, validate and atomically publish bytes using only held descriptors."""
    with _open_release_directories(remote_root) as (staging_fd, public_fd):
        if _existing_release_matches(
            public_fd,
            filename,
            expected_sha=expected_sha,
            expected_size=expected_size,
        ):
            return {"ok": True, "already_published": True}

        temporary_name = f".{filename}.{uuid.uuid4().hex}.part"
        nofollow = getattr(os, "O_NOFOLLOW", None)
        if nofollow is None:
            raise AndroidReleaseStagingError(
                "This host cannot safely reserve an APK upload"
            )
        flags = (
            os.O_RDWR | os.O_CREAT | os.O_EXCL | nofollow | getattr(os, "O_CLOEXEC", 0)
        )
        try:
            uploaded_fd = os.open(temporary_name, flags, 0o600, dir_fd=staging_fd)
        except OSError as exc:
            raise AndroidReleaseStagingError(
                "Cannot reserve a private Android upload file"
            ) from exc

        promoted = False
        try:
            _validate_regular_file_descriptor(
                uploaded_fd,
                "Reserved Android upload",
                exact_mode=0o600,
            )
            _copy_exact_upload(
                source,
                uploaded_fd,
                expected_sha=expected_sha,
                expected_size=expected_size,
            )
            os.fchmod(uploaded_fd, 0o644)
            _validate_regular_file_descriptor(
                uploaded_fd,
                "Verified Android upload",
                exact_mode=0o644,
            )
            os.fsync(uploaded_fd)
            try:
                _atomic_rename_noreplace_at(
                    staging_fd,
                    temporary_name,
                    public_fd,
                    filename,
                )
            except _ImmutableDestinationExists:
                if not _existing_release_matches(
                    public_fd,
                    filename,
                    expected_sha=expected_sha,
                    expected_size=expected_size,
                ):
                    raise AndroidReleaseStagingError(
                        "immutable APK destination appeared without valid bytes"
                    )
                return {"ok": True, "already_published": True}
            promoted = True
            os.fsync(public_fd)
            os.fsync(staging_fd)
        except OSError as exc:
            raise AndroidReleaseStagingError(
                "Secure Android APK publication failed"
            ) from exc
        finally:
            os.close(uploaded_fd)
            if not promoted:
                try:
                    os.unlink(temporary_name, dir_fd=staging_fd)
                    os.fsync(staging_fd)
                except FileNotFoundError:
                    pass
        return {
            "ok": True,
            "published": str(Path(remote_root) / "releases/android" / filename),
        }


def _remote_action(
    action: str,
    payload: dict[str, Any],
    *,
    upload_stream: BinaryIO | None = None,
) -> dict[str, Any]:
    filename = payload.get("apk_filename")
    if action in {"runtime", "prepare", "upload"}:
        runtime = _inspect_running_release(payload)
        if action == "runtime":
            return {"ok": True, "runtime_parity": runtime}

    if action == "finalize":
        root = _validated_runtime_root(payload.get("remote_root", DEFAULT_REMOTE_ROOT))
        initial_runtime = payload.get("initial_runtime_parity")
        manifest = payload.get("manifest")
        if not isinstance(initial_runtime, dict):
            raise AndroidReleaseStagingError(
                "Initial runtime parity evidence must be an object"
            )
        if not isinstance(manifest, dict):
            raise AndroidReleaseStagingError("Registry manifest must be an object")
        with _production_install_lock():
            final_runtime = _inspect_running_release(payload)
            if final_runtime != initial_runtime:
                raise AndroidReleaseStagingError(
                    "Running ERP images changed during staging; registration was not attempted"
                )
            registered = _register_staged_release_on_host(root, manifest)
        return {
            "ok": True,
            "runtime_parity": final_runtime,
            "registered": registered,
        }

    if action in {"prepare", "upload"}:
        if not isinstance(filename, str) or _SAFE_FILENAME.fullmatch(filename) is None:
            raise AndroidReleaseStagingError("remote APK filename is unsafe")
        expected_size = _positive_int(payload.get("apk_size_bytes"), "apk_size_bytes")
        expected_sha = _normalized_sha256(payload.get("apk_sha256"), "apk_sha256")
        remote_root = _validated_runtime_root(
            payload.get("remote_root", DEFAULT_REMOTE_ROOT)
        )
        if action == "prepare":
            with _open_release_directories(remote_root) as (_staging_fd, public_fd):
                already_published = _existing_release_matches(
                    public_fd,
                    filename,
                    expected_sha=expected_sha,
                    expected_size=expected_size,
                )
            return {"ok": True, "already_published": already_published}
        if upload_stream is None:
            raise AndroidReleaseStagingError("APK upload stream is required")
        return _receive_and_publish_apk(
            remote_root=remote_root,
            filename=filename,
            expected_sha=expected_sha,
            expected_size=expected_size,
            source=upload_stream,
        )

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
        result = _remote_action(
            action,
            _decode_remote_payload(encoded),
            upload_stream=sys.stdin.buffer if action == "upload" else None,
        )
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
