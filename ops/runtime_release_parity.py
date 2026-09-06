#!/usr/bin/env python3
"""Read-only, fail-closed proof of the exact deployed ERP image pair."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any


class RuntimeParityError(RuntimeError):
    pass


def validate_identity(version_name: Any, source_git_sha: Any) -> dict[str, str]:
    if not isinstance(version_name, str) or re.fullmatch(
        r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version_name
    ) is None:
        raise RuntimeParityError("Runtime parity requires a canonical X.Y.Z version")
    if not isinstance(source_git_sha, str) or re.fullmatch(r"[0-9a-f]{40}", source_git_sha) is None:
        raise RuntimeParityError("Runtime parity requires a full lowercase Git SHA")
    if version_name == "0.0.0" or source_git_sha == "0" * 40:
        raise RuntimeParityError("Development release identity cannot stage an Android update")
    return {"version_name": version_name, "source_git_sha": source_git_sha}


def _run(command: list[str]) -> str:
    try:
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise RuntimeParityError("Cannot inspect deployed ERP release identity") from exc


def _json(command: list[str]) -> Any:
    try:
        return json.loads(_run(command))
    except (ValueError, TypeError) as exc:
        raise RuntimeParityError("Release identity inspection returned invalid JSON") from exc


def _one(command: list[str], label: str) -> dict[str, Any]:
    result = _json(command)
    if not isinstance(result, list) or len(result) != 1 or not isinstance(result[0], dict):
        raise RuntimeParityError(f"Expected exactly one {label}")
    return result[0]


def inspect_release_pair(
    root: str, env_file: str, version_name: str, source_git_sha: str,
    *, running: bool, expected_images: dict[str, Any] | None = None,
) -> dict[str, Any]:
    identity = validate_identity(version_name, source_git_sha)
    compose = ["docker", "compose", "-f", str(Path(root) / "docker-compose.prod.yml"),
               "--env-file", env_file]
    # Render only for candidate image refs. `compose images` reflects OLD containers.
    config = None if running else _json([*compose, "config", "--format", "json"])
    evidence: dict[str, Any] = {**identity, "services": {}}
    for service in ("backend", "frontend"):
        container_id = None
        if running:
            ids = _run([*compose, "ps", "-q", service]).split()
            if len(ids) != 1:
                raise RuntimeParityError(f"Expected exactly one running {service} container")
            container = _one(["docker", "inspect", ids[0]], f"{service} container")
            state = container.get("State", {})
            if (not state.get("Running") or state.get("Paused") or state.get("Restarting")
                    or state.get("Health", {}).get("Status") != "healthy"):
                raise RuntimeParityError(f"Running {service} container is not healthy")
            container_id = container.get("Id")
            image_ref = container.get("Image")
            if not isinstance(image_ref, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", image_ref) is None:
                raise RuntimeParityError(f"Running {service} has no immutable image ID")
        else:
            image_ref = (config.get("services", {}).get(service, {}).get("image")
                         if isinstance(config, dict) else None)
            if not isinstance(image_ref, str) or not image_ref:
                raise RuntimeParityError(f"Candidate {service} requires an explicit image reference")
        image = _one(["docker", "image", "inspect", image_ref], f"{service} image")
        image_id = image.get("Id")
        if not isinstance(image_id, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", image_id) is None:
            raise RuntimeParityError(f"{service} inspection returned no immutable image ID")
        if running and image_id != image_ref:
            raise RuntimeParityError(f"{service} running image identity changed during inspection")
        labels = image.get("Config", {}).get("Labels", {}) or {}
        if (labels.get("org.opencontainers.image.version") != version_name
                or labels.get("org.opencontainers.image.revision") != source_git_sha):
            raise RuntimeParityError(f"{service} runtime version/revision does not match the Android release")
        if expected_images is not None and image_id != expected_images.get("services", {}).get(service, {}).get("image_id"):
            raise RuntimeParityError(f"{service} running image differs from the verified candidate")
        row = {"image_id": image_id, "version_name": version_name, "source_git_sha": source_git_sha}
        if container_id is not None:
            row["container_id"] = container_id
        else:
            row["image_ref"] = image_ref
        evidence["services"][service] = row
    return evidence


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("candidate", "running"))
    parser.add_argument("--root", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--source-git-sha", required=True)
    parser.add_argument("--expected-images-json")
    args = parser.parse_args()
    try:
        expected = json.loads(args.expected_images_json) if args.expected_images_json else None
        if expected is not None and not isinstance(expected, dict):
            raise RuntimeParityError("Expected image evidence must be an object")
        print(json.dumps(inspect_release_pair(args.root, args.env_file, args.version_name,
              args.source_git_sha, running=args.mode == "running", expected_images=expected), sort_keys=True))
    except (RuntimeParityError, ValueError) as exc:
        parser.exit(1, f"Release parity refused: {exc}\n")


if __name__ == "__main__":
    main()
