#!/usr/bin/env python3
"""Fail-closed identity probe for the disposable Docker daemon used by CI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path
from typing import Any


DOCKER_VERSION = "29.6.1"
DOCKER_HOST_RE = re.compile(r"unix://(/[A-Za-z0-9._/-]+\.sock)")
CONFLICTING_ENV = ("DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")
COMPOSE_VERSION_RE = re.compile(r"v?\d+\.\d+\.\d+(?:[-+][A-Za-z0-9._-]+)?")
BUILDX_VERSION_RE = re.compile(
    r"github\.com/docker/buildx v\d+\.\d+\.\d+(?:[-+][A-Za-z0-9._-]+)? [0-9a-f]{7,64}"
)
CLASSIC_STATUS_KEYS = (
    "Backing Filesystem",
    "Supports d_type",
    "Using metacopy",
    "Native Overlay Diff",
    "userxattr",
)


class ConnectionError(RuntimeError):
    """The effective Docker connection is not the reviewed CI connection."""


def _run(cli: Path, host: str, role: str, *arguments: str) -> str:
    if role == "runner":
        # Exercise the ordinary job environment after the explicit override
        # checks in capture(). Do not conceal Docker client configuration.
        env = os.environ.copy()
    else:
        env = {
            "PATH": f"{cli.parent}:/usr/sbin:/usr/bin:/sbin:/bin",
            "DOCKER_HOST": host,
            "HOME": "/root",
            "LC_ALL": "C",
        }
    completed = subprocess.run(
        [str(cli), *arguments],
        check=False,
        capture_output=True,
        text=True,
        env=env,
        timeout=30,
    )
    if completed.returncode != 0:
        raise ConnectionError(
            f"docker {' '.join(arguments[:2])} failed with status {completed.returncode}"
        )
    return completed.stdout.strip()


def _json_object(value: str, label: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
    except (TypeError, json.JSONDecodeError) as exc:
        raise ConnectionError(f"{label} did not return valid JSON") from exc
    if not isinstance(parsed, dict):
        raise ConnectionError(f"{label} did not return a JSON object")
    return parsed


def _canonical_host(host: str) -> tuple[str, Path]:
    match = DOCKER_HOST_RE.fullmatch(host)
    if match is None or any(character.isspace() for character in host):
        raise ConnectionError("Docker host must be one absolute local Unix socket URI")
    socket_path = Path(match.group(1)).resolve(strict=True)
    if not stat.S_ISSOCK(socket_path.stat().st_mode):
        raise ConnectionError("Docker host does not resolve to a Unix socket")
    return f"unix://{socket_path}", socket_path


def _canonical_cli(cli_value: str, tool_cache_value: str) -> Path:
    cli = Path(cli_value).resolve(strict=True)
    tool_cache = Path(tool_cache_value).resolve(strict=True)
    if cli.name != "docker" or not cli.is_file() or not os.access(cli, os.X_OK):
        raise ConnectionError("Docker CLI must be an executable regular file")
    try:
        cli.relative_to(tool_cache)
    except ValueError as exc:
        raise ConnectionError("Docker CLI is outside the setup action tool cache") from exc
    return cli


def _required_text(mapping: dict[str, Any], key: str, label: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ConnectionError(f"Docker {label} has no {key}")
    return value.strip()


def _python_identity() -> tuple[tuple[int, int], str, str]:
    return sys.version_info[:2], sys.version.split()[0], str(Path(sys.executable).resolve())


def _normalise_driver_status(info: dict[str, Any], expected_store: str) -> list[list[str]]:
    driver = _required_text(info, "Driver", "server")
    raw_status = info.get("DriverStatus")
    if not isinstance(raw_status, list) or not all(
        isinstance(pair, list)
        and len(pair) == 2
        and all(isinstance(value, str) for value in pair)
        for pair in raw_status
    ):
        raise ConnectionError("Docker server has malformed DriverStatus")

    status = [[key, value] for key, value in raw_status]
    if expected_store == "containerd":
        if driver != "overlayfs" or status != [
            ["driver-type", "io.containerd.snapshotter.v1"]
        ]:
            raise ConnectionError("Docker image store is not the expected containerd store")
        return status

    if driver != "overlay2" or len(status) != len(CLASSIC_STATUS_KEYS):
        raise ConnectionError("Docker image store is not the expected classic store")
    if tuple(pair[0] for pair in status) != CLASSIC_STATUS_KEYS:
        raise ConnectionError("Docker classic DriverStatus keys or order are unexpected")
    backing_filesystem = status[0][1]
    if not re.fullmatch(r"[A-Za-z0-9._+ -]{1,64}", backing_filesystem):
        raise ConnectionError("Docker classic backing filesystem is malformed")
    if any(pair[1] not in {"true", "false"} for pair in status[1:]):
        raise ConnectionError("Docker classic DriverStatus boolean is malformed")
    return status


def _bounded_version(value: str, pattern: re.Pattern[str], label: str, limit: int) -> str:
    candidate = value.strip()
    if len(candidate) > limit or pattern.fullmatch(candidate) is None:
        raise ConnectionError(f"Docker {label} version is malformed")
    return candidate


def capture(args: argparse.Namespace) -> dict[str, Any]:
    python_minor, python_version, python_executable = _python_identity()
    if python_minor != (3, 12):
        raise ConnectionError(
            f"CI Docker identity/parser runtime must be Python 3.12, got {python_version}"
        )
    if os.environ.get("DOCKER_HOST") != args.docker_host:
        raise ConnectionError("ordinary runner DOCKER_HOST differs from the setup action output")
    conflicts = [name for name in CONFLICTING_ENV if os.environ.get(name)]
    if conflicts:
        raise ConnectionError("conflicting Docker overrides are set: " + ", ".join(conflicts))

    canonical_host, _socket_path = _canonical_host(args.docker_host)
    cli = _canonical_cli(args.docker_cli, args.tool_cache_root)
    expected_root = Path(args.expected_root).resolve(strict=True)

    version = _json_object(
        _run(cli, canonical_host, args.role, "version", "--format", "{{json .}}"),
        "docker version",
    )
    client = version.get("Client")
    server = version.get("Server")
    if not isinstance(client, dict) or not isinstance(server, dict):
        raise ConnectionError("Docker client/server identity is incomplete")
    client_version = _required_text(client, "Version", "client")
    server_version = _required_text(server, "Version", "server")
    if client_version != DOCKER_VERSION or server_version != DOCKER_VERSION:
        raise ConnectionError(
            f"Docker client/server must both be {DOCKER_VERSION}; "
            f"got {client_version}/{server_version}"
        )

    info = _json_object(
        _run(cli, canonical_host, args.role, "info", "--format", "{{json .}}"),
        "docker info",
    )
    daemon_id = _required_text(info, "ID", "server")
    root_dir = Path(_required_text(info, "DockerRootDir", "server")).resolve(strict=True)
    if root_dir != expected_root:
        raise ConnectionError(
            f"Docker root differs from disposable job root: {root_dir} != {expected_root}"
        )
    driver = _required_text(info, "Driver", "server")
    driver_status = _normalise_driver_status(info, args.expected_store)

    result = {
        "role": args.role,
        "docker_host": canonical_host,
        "docker_cli_path": str(cli),
        "docker_cli_sha256": hashlib.sha256(cli.read_bytes()).hexdigest(),
        "client_version": client_version,
        "client_api_version": _required_text(client, "ApiVersion", "client"),
        "client_git_commit": _required_text(client, "GitCommit", "client"),
        "server_version": server_version,
        "server_api_version": _required_text(server, "ApiVersion", "server"),
        "server_git_commit": _required_text(server, "GitCommit", "server"),
        "daemon_id": daemon_id,
        "docker_root_dir": str(root_dir),
        "storage_driver": driver,
        "driver_status": driver_status,
        "image_store": args.expected_store,
        "python_version": python_version,
        "python_executable": python_executable,
    }
    if args.role == "runner":
        compose_version = _bounded_version(
            _run(cli, canonical_host, args.role, "compose", "version", "--short"),
            COMPOSE_VERSION_RE,
            "Compose plugin",
            128,
        )
        buildx_version = _bounded_version(
            _run(cli, canonical_host, args.role, "buildx", "version"),
            BUILDX_VERSION_RE,
            "Buildx plugin",
            256,
        )
        builder_text = _run(cli, canonical_host, args.role, "buildx", "inspect")
        driver_matches = re.findall(r"(?m)^Driver:\s+(\S+)\s*$", builder_text)
        endpoint_matches = re.findall(r"(?m)^Endpoint:\s+(\S+)\s*$", builder_text)
        if len(driver_matches) != 1 or len(endpoint_matches) != 1:
            raise ConnectionError("Buildx did not expose one driver and endpoint")
        builder_driver = driver_matches[0]
        if builder_driver != "docker":
            raise ConnectionError("Buildx must use the local docker driver")
        context_name = _run(cli, canonical_host, args.role, "context", "show")
        # Docker CLI 29 resolves a nonempty DOCKER_HOST through the effective
        # `default` context even when setup-Docker also selected a named stored
        # context. The probed server identity above is the authoritative binding.
        if context_name != "default":
            raise ConnectionError("DOCKER_HOST must select Docker's effective default context")
        displayed_builder_endpoint = endpoint_matches[0]
        if displayed_builder_endpoint.startswith("unix://"):
            resolved_builder_host, _ = _canonical_host(displayed_builder_endpoint)
            if resolved_builder_host != canonical_host:
                raise ConnectionError("Buildx node endpoint differs from the reviewed socket")
        elif displayed_builder_endpoint != "default":
            raise ConnectionError("Buildx node does not use the selected reviewed context")
        result.update(
            {
                "compose_version": compose_version,
                "buildx_version": buildx_version,
                "builder_driver": builder_driver,
                "builder_context": context_name,
                "builder_endpoint": displayed_builder_endpoint,
                "builder_resolved_host": canonical_host,
            }
        )
    return result


def compare(runner_path: Path, root_path: Path) -> dict[str, Any]:
    runner = _json_object(runner_path.read_text(encoding="utf-8"), "runner probe")
    root = _json_object(root_path.read_text(encoding="utf-8"), "root probe")
    if runner.get("role") != "runner" or root.get("role") != "root":
        raise ConnectionError("Docker probes have incorrect roles")
    compared = (
        "docker_host",
        "docker_cli_path",
        "docker_cli_sha256",
        "client_version",
        "client_api_version",
        "client_git_commit",
        "server_version",
        "server_api_version",
        "server_git_commit",
        "daemon_id",
        "docker_root_dir",
        "storage_driver",
        "driver_status",
        "image_store",
        "python_version",
        "python_executable",
    )
    mismatches = [field for field in compared if runner.get(field) != root.get(field)]
    if mismatches:
        raise ConnectionError("runner/root Docker identity mismatch: " + ", ".join(mismatches))
    runner_only = (
        "compose_version",
        "buildx_version",
        "builder_driver",
        "builder_context",
        "builder_endpoint",
        "builder_resolved_host",
    )
    if any(field not in runner for field in runner_only):
        raise ConnectionError("runner Docker tooling identity is incomplete")
    return {
        "verified": True,
        **{field: runner[field] for field in compared},
        **{field: runner[field] for field in runner_only},
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    capture_parser = subparsers.add_parser("capture")
    capture_parser.add_argument("--docker-cli", required=True)
    capture_parser.add_argument("--docker-host", required=True)
    capture_parser.add_argument("--expected-store", choices=("classic", "containerd"), required=True)
    capture_parser.add_argument("--expected-root", required=True)
    capture_parser.add_argument("--tool-cache-root", required=True)
    capture_parser.add_argument("--role", choices=("runner", "root"), required=True)
    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("runner_json", type=Path)
    compare_parser.add_argument("root_json", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        result = capture(args) if args.command == "capture" else compare(
            args.runner_json, args.root_json
        )
    except (ConnectionError, OSError, subprocess.TimeoutExpired) as exc:
        print(f"Docker connection verification failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
