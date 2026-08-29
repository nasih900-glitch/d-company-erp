#!/usr/bin/env python3
"""Fail closed when an Android release tag and package version diverge."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn

import tomllib

DEFAULT_BUILD_FILE = Path("android-native/app/build.gradle.kts")
DEFAULT_PRODUCTION_ENV_FILE = Path(".env.production.example")
DEFAULT_COMPOSE_FILE = Path("docker-compose.prod.yml")
DEFAULT_BACKEND_PROJECT_FILE = Path("backend/pyproject.toml")
DEFAULT_BACKEND_VERSION_FILE = Path("backend/app/__init__.py")
DEFAULT_FRONTEND_PACKAGE_FILE = Path("frontend/package.json")
DEFAULT_FRONTEND_LOCK_FILE = Path("frontend/package-lock.json")
DEFAULT_FRONTEND_ENV_FILE = Path("frontend/.env.example")
_RELEASE_TAG = re.compile(r"v[0-9]+(?:\.[0-9]+)*(?:[-+][0-9A-Za-z][0-9A-Za-z.-]*)?")
_SNAPSHOT_SAFE_MIN_VERSION_CODE = 5


class ReleaseVersionError(ValueError):
    """Raised when release metadata is missing, ambiguous, or inconsistent."""


@dataclass(frozen=True)
class AndroidVersion:
    code: int
    name: str


def _assignment(source: str, field: str) -> str:
    without_block_comments = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    matches: list[str] = []
    pattern = re.compile(rf"^\s*{re.escape(field)}\s*=\s*(.*?)\s*$")

    for line in without_block_comments.splitlines():
        uncommented = line.split("//", 1)[0]
        match = pattern.match(uncommented)
        if match:
            matches.append(match.group(1).strip())

    if len(matches) != 1:
        raise ReleaseVersionError(
            f"expected exactly one {field} assignment, found {len(matches)}"
        )
    return matches[0]


def read_gradle_version(build_file: Path) -> AndroidVersion:
    try:
        source = build_file.read_text(encoding="utf-8")
    except OSError as exc:
        raise ReleaseVersionError(f"cannot read {build_file}: {exc}") from exc

    code_literal = _assignment(source, "versionCode")
    if not re.fullmatch(r"[1-9][0-9]*", code_literal):
        raise ReleaseVersionError(
            "versionCode must be a direct, positive integer literal"
        )

    name_literal = _assignment(source, "versionName")
    try:
        name = json.loads(name_literal)
    except json.JSONDecodeError as exc:
        raise ReleaseVersionError(
            "versionName must be a direct JSON-compatible quoted string"
        ) from exc
    if not isinstance(name, str) or not name or "$" in name:
        raise ReleaseVersionError(
            "versionName must be a non-empty, non-interpolated string"
        )

    return AndroidVersion(code=int(code_literal), name=name)


def validate_tag(tag: str, version: AndroidVersion) -> None:
    if _RELEASE_TAG.fullmatch(tag) is None:
        raise ReleaseVersionError(f"release tag {tag!r} must use the v<version> format")

    expected = f"v{version.name}"
    if tag != expected:
        raise ReleaseVersionError(
            f"release tag {tag!r} does not match Android versionName "
            f"{version.name!r}; expected {expected!r}"
        )


def validate_built_metadata(metadata_file: Path, version: AndroidVersion) -> None:
    try:
        metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
        elements = metadata["elements"]
        element = elements[0]
        built_code = element["versionCode"]
        built_name = element["versionName"]
    except (OSError, json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
        raise ReleaseVersionError(
            f"cannot read Android output metadata from {metadata_file}: {exc}"
        ) from exc

    if len(elements) != 1:
        raise ReleaseVersionError(
            f"expected one Android release output, found {len(elements)}"
        )
    if type(built_code) is not int or built_code <= 0:
        raise ReleaseVersionError(
            "built Android versionCode must be a positive integer"
        )
    if not isinstance(built_name, str) or not built_name:
        raise ReleaseVersionError("built Android versionName must be non-empty")
    if built_code != version.code or built_name != version.name:
        raise ReleaseVersionError(
            "built Android version does not match build.gradle.kts: "
            f"built={built_name} ({built_code}), "
            f"source={version.name} ({version.code})"
        )


def _production_version_value(path: Path, pattern: re.Pattern[str], label: str) -> int:
    try:
        source = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ReleaseVersionError(f"cannot read {path}: {exc}") from exc
    matches = pattern.findall(source)
    if len(matches) != 1:
        raise ReleaseVersionError(
            f"expected exactly one {label} in {path}, found {len(matches)}"
        )
    return int(matches[0])


def _production_boolean_value(
    path: Path,
    pattern: re.Pattern[str],
    label: str,
) -> bool:
    try:
        source = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ReleaseVersionError(f"cannot read {path}: {exc}") from exc
    matches = pattern.findall(source)
    if len(matches) != 1:
        raise ReleaseVersionError(
            f"expected exactly one {label} in {path}, found {len(matches)}"
        )
    return matches[0].lower() == "true"


def validate_production_defaults(
    production_env_file: Path,
    compose_file: Path,
    version: AndroidVersion,
) -> None:
    """Validate safe production policy defaults without auto-advertising the APK.

    The signed build version and the server rollout decision are intentionally
    separate. A release tag may be built before its APK is copied to the HTTPS
    channel, so production defaults may remain on an older compatible build.
    """
    env_latest = _production_version_value(
        production_env_file,
        re.compile(r"^ANDROID_LATEST_VERSION_CODE=([1-9][0-9]*)$", re.MULTILINE),
        "ANDROID_LATEST_VERSION_CODE assignment",
    )
    compose_latest = _production_version_value(
        compose_file,
        re.compile(
            r"^\s*ANDROID_LATEST_VERSION_CODE:\s*"
            r"\$\{ANDROID_LATEST_VERSION_CODE:-([1-9][0-9]*)\}\s*$",
            re.MULTILINE,
        ),
        "ANDROID_LATEST_VERSION_CODE fallback",
    )
    if env_latest != compose_latest:
        raise ReleaseVersionError(
            "production Android latest-version defaults disagree: "
            f"env={env_latest}, compose={compose_latest}"
        )

    env_minimum = _production_version_value(
        production_env_file,
        re.compile(r"^ANDROID_MIN_SUPPORTED_VERSION_CODE=([1-9][0-9]*)$", re.MULTILINE),
        "ANDROID_MIN_SUPPORTED_VERSION_CODE assignment",
    )
    compose_minimum = _production_version_value(
        compose_file,
        re.compile(
            r"^\s*ANDROID_MIN_SUPPORTED_VERSION_CODE:\s*"
            r"\$\{ANDROID_MIN_SUPPORTED_VERSION_CODE:-([1-9][0-9]*)\}\s*$",
            re.MULTILINE,
        ),
        "ANDROID_MIN_SUPPORTED_VERSION_CODE fallback",
    )
    if env_minimum != compose_minimum:
        raise ReleaseVersionError(
            "production Android minimum-version defaults disagree: "
            f"env={env_minimum}, compose={compose_minimum}"
        )
    if not _SNAPSHOT_SAFE_MIN_VERSION_CODE <= env_minimum <= version.code:
        raise ReleaseVersionError(
            "production Android minimum version must preserve the conflict-safe "
            "Gaming contract: "
            f"required>={_SNAPSHOT_SAFE_MIN_VERSION_CODE}, "
            f"configured={env_minimum}, latest={version.code}"
        )
    if not env_minimum <= env_latest <= version.code:
        raise ReleaseVersionError(
            "production Android latest-version default must be between the "
            "minimum supported build and the signed source build: "
            f"minimum={env_minimum}, configured={env_latest}, source={version.code}"
        )

    env_requires_headers = _production_boolean_value(
        production_env_file,
        re.compile(r"^REQUIRE_NATIVE_VERSION_HEADERS=(true|false)$", re.MULTILINE),
        "REQUIRE_NATIVE_VERSION_HEADERS assignment",
    )
    compose_requires_headers = _production_boolean_value(
        compose_file,
        re.compile(
            r"^\s*REQUIRE_NATIVE_VERSION_HEADERS:\s*"
            r"\$\{REQUIRE_NATIVE_VERSION_HEADERS:-(true|false)\}\s*$",
            re.MULTILINE,
        ),
        "REQUIRE_NATIVE_VERSION_HEADERS fallback",
    )
    if not env_requires_headers or not compose_requires_headers:
        raise ReleaseVersionError(
            "production must require native version headers so pre-header Android "
            "builds cannot bypass the compatibility gate"
        )


def validate_product_version_coherence(
    version: AndroidVersion,
    *,
    backend_project_file: Path = DEFAULT_BACKEND_PROJECT_FILE,
    backend_version_file: Path = DEFAULT_BACKEND_VERSION_FILE,
    frontend_package_file: Path = DEFAULT_FRONTEND_PACKAGE_FILE,
    frontend_lock_file: Path = DEFAULT_FRONTEND_LOCK_FILE,
    frontend_env_file: Path = DEFAULT_FRONTEND_ENV_FILE,
    production_env_file: Path = DEFAULT_PRODUCTION_ENV_FILE,
    compose_file: Path = DEFAULT_COMPOSE_FILE,
) -> None:
    """Reject a coordinated release whose platform-visible versions disagree."""

    try:
        backend_project = tomllib.loads(
            backend_project_file.read_text(encoding="utf-8")
        )
        backend_project_version = backend_project["project"]["version"]
        backend_source = backend_version_file.read_text(encoding="utf-8")
        frontend_package = json.loads(frontend_package_file.read_text(encoding="utf-8"))
        frontend_lock = json.loads(frontend_lock_file.read_text(encoding="utf-8"))
        frontend_env_source = frontend_env_file.read_text(encoding="utf-8")
        production_env_source = production_env_file.read_text(encoding="utf-8")
        compose_source = compose_file.read_text(encoding="utf-8")
    except (
        OSError,
        AttributeError,
        KeyError,
        TypeError,
        tomllib.TOMLDecodeError,
        json.JSONDecodeError,
    ) as exc:
        raise ReleaseVersionError(f"cannot read coordinated product versions: {exc}") from exc

    def single_match(source: str, pattern: str, label: str) -> str:
        matches = re.findall(pattern, source, flags=re.MULTILINE)
        if len(matches) != 1:
            raise ReleaseVersionError(
                f"expected exactly one {label}, found {len(matches)}"
            )
        return matches[0]

    lock_root = frontend_lock.get("packages", {}).get("", {})
    values: dict[str, object] = {
        "backend project": backend_project_version,
        "backend runtime": single_match(
            backend_source,
            r'^__version__\s*=\s*"([^"]+)"\s*$',
            "backend runtime version",
        ),
        "frontend package": frontend_package.get("version"),
        "frontend lock": frontend_lock.get("version"),
        "frontend lock root package": lock_root.get("version"),
        "frontend build environment": single_match(
            frontend_env_source,
            r"^VITE_APP_VERSION=([^\s#]+)\s*$",
            "VITE_APP_VERSION assignment",
        ),
        "production environment": single_match(
            production_env_source,
            r"^APP_VERSION=([^\s#]+)\s*$",
            "APP_VERSION assignment",
        ),
    }

    compose_versions = re.findall(
        r"\$\{APP_VERSION:-([^}\s]+)\}",
        compose_source,
    )
    if not compose_versions:
        raise ReleaseVersionError("expected at least one APP_VERSION fallback in compose")
    if len(set(compose_versions)) != 1:
        raise ReleaseVersionError(
            "production compose APP_VERSION fallbacks disagree: "
            + ", ".join(compose_versions)
        )
    values["production compose"] = compose_versions[0]

    mismatches = [
        f"{label}={value!r}"
        for label, value in values.items()
        if value != version.name
    ]
    if mismatches:
        raise ReleaseVersionError(
            f"coordinated product versions must all equal {version.name!r}: "
            + ", ".join(mismatches)
        )


def _fail(message: str) -> NoReturn:
    print(f"Android release version check failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--tag", required=True, help="Git release tag, for example v3.0.7"
    )
    parser.add_argument("--build-file", type=Path, default=DEFAULT_BUILD_FILE)
    parser.add_argument(
        "--production-env-file",
        type=Path,
        default=DEFAULT_PRODUCTION_ENV_FILE,
    )
    parser.add_argument("--compose-file", type=Path, default=DEFAULT_COMPOSE_FILE)
    parser.add_argument(
        "--metadata",
        type=Path,
        help="optional Android Gradle output-metadata.json to cross-check",
    )
    args = parser.parse_args()

    try:
        version = read_gradle_version(args.build_file)
        validate_tag(args.tag, version)
        validate_production_defaults(
            args.production_env_file,
            args.compose_file,
            version,
        )
        validate_product_version_coherence(
            version,
            production_env_file=args.production_env_file,
            compose_file=args.compose_file,
        )
        if args.metadata is not None:
            validate_built_metadata(args.metadata, version)
    except ReleaseVersionError as exc:
        _fail(str(exc))

    print(
        "Android release version verified: "
        f"tag={args.tag}, versionName={version.name}, versionCode={version.code}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
