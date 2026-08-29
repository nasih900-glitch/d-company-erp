"""Register immutable Android release metadata from a strict promotion manifest.

The APK itself is never uploaded through this command or the ERP API.  The ops
promotion path first publishes and verifies the artifact, then pipes canonical
JSON into the backend container:

    python -m scripts.register_android_release --manifest -

Success emits exactly one JSON object for machine parsing.  Replaying an exact
manifest is idempotent; conflicting metadata for the same version is rejected.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path
from uuid import uuid4

from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.core.config import get_settings
from app.core.db import AsyncSessionLocal
from app.models import AndroidRelease
from app.services.client_updates.releases import (
    AndroidReleaseManifest,
    ArtifactVerificationError,
    manifest_sha256,
    require_allowed_update_origin,
)

_MAX_MANIFEST_BYTES = 16_384


class _DuplicateManifestKeyError(ValueError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    parsed: dict[str, object] = {}
    for key, value in pairs:
        if key in parsed:
            raise _DuplicateManifestKeyError(key)
        parsed[key] = value
    return parsed


def _manifest_bytes(source: str) -> bytes:
    try:
        if source == "-":
            payload = sys.stdin.buffer.read(_MAX_MANIFEST_BYTES + 1)
        else:
            with Path(source).open("rb") as manifest_file:
                payload = manifest_file.read(_MAX_MANIFEST_BYTES + 1)
    except OSError as exc:
        raise SystemExit("Manifest could not be read.") from exc
    if not payload or len(payload) > _MAX_MANIFEST_BYTES:
        raise SystemExit("Manifest must contain 1 to 16384 bytes.")
    return payload


def _parse_manifest(payload: bytes) -> AndroidReleaseManifest:
    try:
        raw = json.loads(payload, object_pairs_hook=_reject_duplicate_keys)
    except _DuplicateManifestKeyError as exc:
        raise SystemExit("Manifest contains a duplicate JSON key.") from exc
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SystemExit("Manifest is not valid UTF-8 JSON.") from exc
    try:
        return AndroidReleaseManifest.model_validate(raw)
    except ValidationError as exc:
        fields = sorted({".".join(str(part) for part in row["loc"]) for row in exc.errors()})
        raise SystemExit("Manifest validation failed for: " + ", ".join(fields)) from exc


async def register(manifest: AndroidReleaseManifest) -> dict[str, object]:
    settings = get_settings()
    if manifest.version_code < settings.android_min_supported_version_code:
        raise SystemExit("Release version is below ANDROID_MIN_SUPPORTED_VERSION_CODE.")
    try:
        require_allowed_update_origin(
            update_url=manifest.update_url,
            configured_url=(
                str(settings.android_update_allowed_origin)
                if settings.android_update_allowed_origin
                else None
            ),
        )
    except ArtifactVerificationError as exc:
        raise SystemExit(f"Release URL rejected: {exc.code}.") from exc

    digest = manifest_sha256(manifest)
    values = manifest.model_dump(mode="python")
    async with AsyncSessionLocal() as session:
        # The unique constraint is the concurrency authority.  A simultaneous
        # identical registration waits for the first transaction, becomes a
        # no-op, and is then compared to the committed immutable row.
        await session.execute(
            pg_insert(AndroidRelease)
            .values(
                id=uuid4(),
                **values,
                manifest_sha256=digest,
                status="staged",
            )
            .on_conflict_do_nothing(constraint="uq_android_releases_channel_version")
        )
        existing = (
            await session.execute(
                select(AndroidRelease).where(
                    AndroidRelease.channel == manifest.channel,
                    AndroidRelease.version_code == manifest.version_code,
                )
            )
        ).scalar_one()
        same = (
            all(getattr(existing, field) == value for field, value in values.items())
            and existing.manifest_sha256 == digest
        )
        if not same:
            raise SystemExit(
                "A release with this channel/version already exists with different metadata."
            )
        await session.commit()
        return {
            "id": str(existing.id),
            "status": existing.status,
            "manifest_sha256": existing.manifest_sha256,
        }


def main() -> None:
    parser = argparse.ArgumentParser(description="Register a staged Android release manifest.")
    parser.add_argument(
        "--manifest",
        required=True,
        help="Strict JSON manifest path, or '-' to read it from stdin.",
    )
    args = parser.parse_args()
    manifest = _parse_manifest(_manifest_bytes(args.manifest))
    result = asyncio.run(register(manifest))
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
