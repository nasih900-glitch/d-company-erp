"""Strict, bounded identities baked into deployment images, never supplied by clients."""

from __future__ import annotations

import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, ValidationError

RELEASE_IDENTITY_PATH = Path("/etc/dcompany/release-identity.json")
MAX_RELEASE_IDENTITY_BYTES = 1_024


class ReleaseIdentity(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    version_name: str = Field(min_length=1, max_length=80, pattern=r"^[0-9A-Za-z][0-9A-Za-z._+-]*$")
    source_git_sha: str = Field(pattern=r"^[0-9a-f]{40}$")


def _unique_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate release identity key")
        result[key] = value
    return result


def parse_release_identity(payload: bytes) -> ReleaseIdentity:
    if not payload or len(payload) > MAX_RELEASE_IDENTITY_BYTES:
        raise ValueError("Release identity must contain 1 to 1024 bytes")
    try:
        raw = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_keys)
        return ReleaseIdentity.model_validate(raw)
    except (UnicodeError, ValueError, ValidationError) as exc:
        raise ValueError(
            "Release identity must contain only a valid version_name and source_git_sha"
        ) from exc


def read_backend_build_identity() -> ReleaseIdentity:
    """Read the fixed root-owned image file; no environment-controlled file path."""
    try:
        with RELEASE_IDENTITY_PATH.open("rb") as identity_file:
            return parse_release_identity(identity_file.read(MAX_RELEASE_IDENTITY_BYTES + 1))
    except OSError as exc:
        raise ValueError("Backend image release identity is missing or unreadable") from exc
