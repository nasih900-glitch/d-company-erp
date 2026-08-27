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

DEFAULT_BUILD_FILE = Path("android-native/app/build.gradle.kts")
DEFAULT_PRODUCTION_ENV_FILE = Path(".env.production.example")
DEFAULT_COMPOSE_FILE = Path("docker-compose.prod.yml")
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
    """Keep production's advertised latest Android build aligned with the APK."""
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
    if env_latest != version.code or compose_latest != version.code:
        raise ReleaseVersionError(
            "production Android latest-version defaults do not match versionCode: "
            f"source={version.code}, env={env_latest}, compose={compose_latest}"
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


def _fail(message: str) -> NoReturn:
    print(f"Android release version check failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--tag", required=True, help="Git release tag, for example v3.0.1"
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
