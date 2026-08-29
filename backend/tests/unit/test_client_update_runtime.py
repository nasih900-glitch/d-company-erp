from __future__ import annotations

import hashlib
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import UUID, uuid4

import httpx
import pytest
from pydantic import ValidationError
from redis.exceptions import ConnectionError as RedisConnectionError

from app.api.v1.client_installations.router import ClientInstallationHeartbeat
from app.core.errors import RateLimitError, ServiceUnavailableError
from app.services.client_updates import rate_limit as heartbeat_rate_limit
from app.services.client_updates.releases import (
    AndroidReleaseFingerprint,
    AndroidReleaseManifest,
    ArtifactVerificationError,
    manifest_sha256,
    require_allowed_update_origin,
    verify_public_apk,
)
from scripts.register_android_release import _parse_manifest


def _heartbeat(**overrides) -> dict:
    payload = {
        "installation_id": str(uuid4()),
        "platform": "android",
        "distribution_channel": "direct",
        "version_name": "3.1.3",
        "version_code": 14,
        "pending_outbox_count": 0,
        "last_successful_sync_at": datetime.now(UTC).isoformat(),
        "update_state": "idle",
        "update_error_code": None,
        "events": [],
    }
    payload.update(overrides)
    return payload


def test_heartbeat_rejects_untrusted_identity_logs_and_unbounded_events() -> None:
    for extra in (
        {"company_id": str(uuid4())},
        {"user_id": str(uuid4())},
        {"terminal_id": str(uuid4())},
        {"device_id": "serial-number"},
        {"message": "arbitrary log text"},
    ):
        with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
            ClientInstallationHeartbeat.model_validate(_heartbeat(**extra))

    event = {
        "client_event_id": str(uuid4()),
        "event_type": "update_offered",
        "target_version_name": "3.1.4",
        "target_version_code": 15,
        "error_code": None,
        "occurred_at": datetime.now(UTC).isoformat(),
    }
    with pytest.raises(ValidationError, match="at most 20"):
        ClientInstallationHeartbeat.model_validate(_heartbeat(events=[event] * 21))


def test_heartbeat_enforces_random_ids_timezones_and_failure_pairing() -> None:
    with pytest.raises(ValidationError, match="random UUID v4"):
        ClientInstallationHeartbeat.model_validate(
            _heartbeat(installation_id="00000000-0000-0000-0000-000000000000")
        )
    with pytest.raises(ValidationError, match="include a timezone"):
        ClientInstallationHeartbeat.model_validate(
            _heartbeat(last_successful_sync_at="2026-08-29T12:00:00")
        )
    with pytest.raises(ValidationError, match="24 hours"):
        ClientInstallationHeartbeat.model_validate(
            _heartbeat(last_successful_sync_at=(datetime.now(UTC) + timedelta(days=2)).isoformat())
        )
    with pytest.raises(ValidationError, match="requires an allowlisted error code"):
        ClientInstallationHeartbeat.model_validate(_heartbeat(update_state="failed"))
    with pytest.raises(ValidationError, match="only failed update state"):
        ClientInstallationHeartbeat.model_validate(_heartbeat(update_error_code="network_error"))


def test_event_allowlist_pairing_and_request_idempotency_identity() -> None:
    event_id = uuid4()
    failed = {
        "client_event_id": str(event_id),
        "event_type": "update_failed",
        "target_version_name": "3.1.4",
        "target_version_code": 15,
        "error_code": "checksum_mismatch",
        "occurred_at": datetime.now(UTC).isoformat(),
    }
    accepted = ClientInstallationHeartbeat.model_validate(
        _heartbeat(
            update_state="failed",
            update_error_code="checksum_mismatch",
            events=[failed],
        )
    )
    assert accepted.events[0].client_event_id == event_id

    with pytest.raises(ValidationError, match="must not repeat"):
        ClientInstallationHeartbeat.model_validate(
            _heartbeat(
                update_state="failed",
                update_error_code="checksum_mismatch",
                events=[failed, failed],
            )
        )
    with pytest.raises(ValidationError, match="only update_failed"):
        ClientInstallationHeartbeat.model_validate(
            _heartbeat(events=[{**failed, "event_type": "download_started"}])
        )
    with pytest.raises(ValidationError, match="Input should be"):
        ClientInstallationHeartbeat.model_validate(
            _heartbeat(update_state="failed", update_error_code="stack_trace_here")
        )


def _manifest() -> AndroidReleaseManifest:
    return AndroidReleaseManifest.model_validate(
        {
            "version_code": 15,
            "version_name": "3.1.4",
            "channel": "direct",
            "update_url": (
                "https://dcompany.duckdns.org/downloads/android/D-COMPANY-ERP-3.1.4-code15.apk"
            ),
            "release_notes": "Verified Gaming Centre update",
            "apk_sha256": "ab" * 32,
            "apk_size_bytes": 123,
            "apk_signing_cert_sha256": "cd" * 32,
            "source_git_sha": "ef" * 20,
            "source_release_ref": "v3.1.4",
            "source_workflow_run_id": 9_007_199_254_740_993,
            "source_workflow_run_attempt": 2,
        }
    )


def test_release_manifest_is_strict_canonical_and_controlled_path_only() -> None:
    manifest = _manifest()
    assert len(manifest_sha256(manifest)) == 64
    assert manifest.apk_sha256 == "ab" * 32

    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        AndroidReleaseManifest.model_validate(
            {**manifest.model_dump(), "minimum_supported_version_code": 14}
        )
    for bad_url in (
        "http://dcompany.duckdns.org/downloads/android/app.apk",
        "https://dcompany.duckdns.org/private/app.apk",
        "https://dcompany.duckdns.org/downloads/android/../app.apk",
        "https://dcompany.duckdns.org/downloads/android/app.apk?token=secret",
        "https://dcompany.duckdns.org:invalid/downloads/android/app.apk",
        "https://other.example/downloads/android/app.apk",
    ):
        if "other.example" in bad_url:
            with pytest.raises(ArtifactVerificationError, match="update_origin_not_allowed"):
                require_allowed_update_origin(
                    update_url=bad_url,
                    configured_url="https://dcompany.duckdns.org",
                )
        else:
            with pytest.raises(ValidationError):
                AndroidReleaseManifest.model_validate(
                    {**manifest.model_dump(), "update_url": bad_url}
                )


@pytest.mark.parametrize(
    "release_notes",
    [
        "line one\nline two",
        "contains a tab\there",
        "non-ASCII café",
        "x" * 2001,
    ],
)
def test_release_manifest_rejects_unbounded_or_non_single_line_notes(
    release_notes: str,
) -> None:
    with pytest.raises(ValidationError):
        AndroidReleaseManifest.model_validate(
            {**_manifest().model_dump(), "release_notes": release_notes}
        )


def test_release_manifest_rejects_manual_baseline_and_duplicate_json_keys() -> None:
    with pytest.raises(ValidationError, match="greater than or equal to 15"):
        AndroidReleaseManifest.model_validate({**_manifest().model_dump(), "version_code": 14})

    with pytest.raises(SystemExit, match="duplicate JSON key"):
        _parse_manifest(b'{"version_code":15,"version_code":16,"version_name":"3.1.4"}')


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("source_git_sha", "a" * 39),
        ("source_git_sha", "A" * 40),
        ("source_release_ref", "v3.1.5"),
        ("source_release_ref", "v3.1.4\nunsafe"),
        ("source_workflow_run_id", 0),
        ("source_workflow_run_id", 9_223_372_036_854_775_808),
        ("source_workflow_run_attempt", 0),
        ("source_workflow_run_attempt", 2_147_483_648),
    ],
)
def test_release_manifest_rejects_missing_or_invalid_ci_provenance(
    field: str,
    value: object,
) -> None:
    payload = _manifest().model_dump()
    payload[field] = value
    with pytest.raises(ValidationError):
        AndroidReleaseManifest.model_validate(payload)

    payload = _manifest().model_dump()
    payload.pop(field)
    with pytest.raises(ValidationError, match="Field required"):
        AndroidReleaseManifest.model_validate(payload)


def _fingerprint(payload: bytes) -> AndroidReleaseFingerprint:
    manifest = _manifest()
    return AndroidReleaseFingerprint(
        id=UUID("11111111-1111-4111-8111-111111111111"),
        channel=manifest.channel,
        version_code=manifest.version_code,
        version_name=manifest.version_name,
        update_url=manifest.update_url,
        release_notes=manifest.release_notes,
        apk_sha256=hashlib.sha256(payload).hexdigest(),
        apk_size_bytes=len(payload),
        apk_signing_cert_sha256=manifest.apk_signing_cert_sha256,
        manifest_sha256=manifest_sha256(manifest),
        source_git_sha=manifest.source_git_sha,
        source_release_ref=manifest.source_release_ref,
        source_workflow_run_id=manifest.source_workflow_run_id,
        source_workflow_run_attempt=manifest.source_workflow_run_attempt,
        status="staged",
    )


def _artifact_headers(payload: bytes) -> dict[str, str]:
    return {
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": str(len(payload)),
        "Cache-Control": "public, max-age=31536000, immutable, no-transform",
        "X-Content-Type-Options": "nosniff",
    }


@pytest.mark.asyncio
async def test_public_apk_verifier_requires_exact_bytes_and_serving_policy() -> None:
    payload = b"PK\x03\x04" + b"signed-apk-test-bytes"
    release = _fingerprint(payload)

    async def valid(request: httpx.Request) -> httpx.Response:
        assert str(request.url) == release.update_url
        return httpx.Response(200, headers=_artifact_headers(payload), content=payload)

    await verify_public_apk(
        release,
        configured_update_url="https://dcompany.duckdns.org",
        transport=httpx.MockTransport(valid),
    )

    invalid_cases = (
        ({"Content-Type": "text/html"}, "artifact_content_type_invalid"),
        ({"Content-Type": "application/octet-stream"}, "artifact_content_type_invalid"),
        ({"X-Content-Type-Options": ""}, "artifact_nosniff_missing"),
        ({"Cache-Control": "no-store"}, "artifact_cache_policy_invalid"),
        ({"Content-Length": str(len(payload) + 1)}, "artifact_size_mismatch"),
    )
    for override, expected_code in invalid_cases:
        headers = {**_artifact_headers(payload), **override}

        async def invalid(_request: httpx.Request, *, _headers=headers) -> httpx.Response:
            return httpx.Response(200, headers=_headers, content=payload)

        with pytest.raises(ArtifactVerificationError, match=expected_code):
            await verify_public_apk(
                release,
                configured_update_url="https://dcompany.duckdns.org",
                transport=httpx.MockTransport(invalid),
            )

    async def missing_length(_request: httpx.Request) -> httpx.Response:
        headers = _artifact_headers(payload)
        del headers["Content-Length"]
        return httpx.Response(200, headers=headers, stream=httpx.ByteStream(payload))

    with pytest.raises(ArtifactVerificationError, match="artifact_size_invalid"):
        await verify_public_apk(
            release,
            configured_update_url="https://dcompany.duckdns.org",
            transport=httpx.MockTransport(missing_length),
        )


def test_release_manifest_caps_apk_at_android_and_monitor_limit() -> None:
    with pytest.raises(ValidationError, match="less than or equal to 536870912"):
        AndroidReleaseManifest.model_validate(
            {**_manifest().model_dump(), "apk_size_bytes": 512 * 1024 * 1024 + 1}
        )


@pytest.mark.asyncio
async def test_public_apk_verifier_rejects_checksum_and_archive_mismatch() -> None:
    payload = b"PK\x03\x04" + b"expected"
    release = _fingerprint(payload)

    async def wrong_bytes(_request: httpx.Request) -> httpx.Response:
        wrong = b"PK\x03\x04" + b"tampered"
        return httpx.Response(
            200,
            headers=_artifact_headers(wrong),
            content=wrong,
        )

    with pytest.raises(ArtifactVerificationError, match="artifact_checksum_mismatch"):
        await verify_public_apk(
            release,
            configured_update_url="https://dcompany.duckdns.org",
            transport=httpx.MockTransport(wrong_bytes),
        )

    non_zip = b"NOT!" + payload[4:]
    non_zip_release = _fingerprint(non_zip)

    async def not_zip(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers=_artifact_headers(non_zip), content=non_zip)

    with pytest.raises(ArtifactVerificationError, match="artifact_not_apk_archive"):
        await verify_public_apk(
            non_zip_release,
            configured_update_url="https://dcompany.duckdns.org",
            transport=httpx.MockTransport(not_zip),
        )


class _FakeRedis:
    def __init__(self, results: list[object] | None = None, error: Exception | None = None) -> None:
        self.results = list(results or [])
        self.error = error
        self.keys: list[str] = []
        self.closed = False

    async def eval(self, _script, _key_count, key, _window):
        self.keys.append(key)
        if self.error is not None:
            raise self.error
        return self.results.pop(0)

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.asyncio
async def test_heartbeat_rate_limit_is_principal_scoped_and_returns_retry_after(
    monkeypatch,
) -> None:
    company_id = uuid4()
    user_id = uuid4()
    fake = _FakeRedis(results=[[1, 60], [2, 59], [3, 58]])
    monkeypatch.setattr(
        heartbeat_rate_limit,
        "get_settings",
        lambda: SimpleNamespace(
            redis_url="redis://localhost:6379/0",
            client_heartbeat_user_limit_per_minute=2,
        ),
    )
    monkeypatch.setattr(
        heartbeat_rate_limit.Redis,
        "from_url",
        lambda *_args, **_kwargs: fake,
    )

    await heartbeat_rate_limit.enforce_client_heartbeat_rate_limit(
        company_id=company_id,
        user_id=user_id,
    )
    await heartbeat_rate_limit.enforce_client_heartbeat_rate_limit(
        company_id=company_id,
        user_id=user_id,
    )
    with pytest.raises(RateLimitError) as captured:
        await heartbeat_rate_limit.enforce_client_heartbeat_rate_limit(
            company_id=company_id,
            user_id=user_id,
        )

    assert len(set(fake.keys)) == 1
    assert str(company_id) not in fake.keys[0]
    assert str(user_id) not in fake.keys[0]
    assert fake.keys[0] != heartbeat_rate_limit._principal_key(company_id, uuid4())
    assert fake.keys[0] != heartbeat_rate_limit._principal_key(uuid4(), user_id)
    assert captured.value.headers == {"Retry-After": "58"}
    assert captured.value.details["retry_after_seconds"] == 58
    assert fake.closed is True


@pytest.mark.asyncio
async def test_heartbeat_rate_limit_fails_closed_when_redis_is_unavailable(
    monkeypatch,
) -> None:
    fake = _FakeRedis(error=RedisConnectionError("unavailable"))
    monkeypatch.setattr(
        heartbeat_rate_limit,
        "get_settings",
        lambda: SimpleNamespace(
            redis_url="redis://localhost:6379/0",
            client_heartbeat_user_limit_per_minute=30,
        ),
    )
    monkeypatch.setattr(
        heartbeat_rate_limit.Redis,
        "from_url",
        lambda *_args, **_kwargs: fake,
    )

    with pytest.raises(ServiceUnavailableError, match="protection"):
        await heartbeat_rate_limit.enforce_client_heartbeat_rate_limit(
            company_id=uuid4(),
            user_id=uuid4(),
        )
    assert fake.closed is True
