from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError

from app.api.v1.client_updates.router import AndroidUpdatePolicyWrite
from app.api.v1.public import router as public
from app.core.config import Settings
from app.core.errors import ServiceUnavailableError
from app.core.middleware import ClientCompatibilityMiddleware
from app.services.client_updates import policy


def settings():
    return Settings(
        _env_file=None,
        android_min_supported_version_code=54,
        android_latest_version_code=54,
        client_compatibility_policy_revision=4,
    )


def test_absent_and_optional_preserve_environment_safety_floor():
    assert policy.policy_snapshot(settings(), None) == policy.AndroidPolicySnapshot(4, 54)
    row = SimpleNamespace(mode="optional", revision=6)
    assert policy.policy_snapshot(settings(), row) == policy.AndroidPolicySnapshot(
        6, 54, "optional"
    )


def test_invalid_required_target_and_revision_fail_closed():
    with pytest.raises(ServiceUnavailableError):
        policy.policy_snapshot(settings(), SimpleNamespace(mode="required", revision=5), None)
    with pytest.raises(ServiceUnavailableError):
        policy.policy_snapshot(settings(), SimpleNamespace(mode="optional", revision=2**31), None)


@pytest.mark.asyncio
@pytest.mark.parametrize("channel", ["managed", "play"])
async def test_non_direct_channels_keep_environment_contract_without_policy_lookup(
    monkeypatch, channel
):
    async def forbidden_lookup(_settings):
        raise AssertionError("A direct APK decision must never affect another distribution channel")

    monkeypatch.setattr("app.core.middleware.resolve_android_update_policy", forbidden_lookup)
    monkeypatch.setattr(public, "resolve_android_update_policy", forbidden_lookup)
    monkeypatch.setattr(public, "get_settings", settings)
    app = FastAPI()
    app.include_router(public.router, prefix="/public")

    @app.get("/operation")
    async def operation():
        return {"read": True}

    app.add_middleware(
        ClientCompatibilityMiddleware,
        android_minimum=54,
        android_latest=54,
        android_update_url=None,
        ios_minimum=1,
        ios_latest=1,
        ios_update_url=None,
        policy_revision=4,
        require_native_headers=True,
        message=None,
        resolve_android_policy=True,
    )
    headers = {
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "54",
        "X-Client-Distribution-Channel": channel,
    }
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        allowed = await client.get("/operation", headers=headers)
        assert allowed.status_code == 200
        assert allowed.headers["X-Minimum-Supported-Version-Code"] == "54"
        assert allowed.headers["X-Client-Compatibility-Policy-Revision"] == "4"
        body = await client.get(
            "/public/client-compatibility?platform=android&version_code=54", headers=headers
        )
        assert body.status_code == 200
        assert body.json()["status"] == "supported"
        assert body.json()["policy_revision"] == 4
        assert body.json()["minimum_supported_version_code"] == 54
        assert body.json()["update_url"] is None
        blocked = await client.get("/operation", headers={**headers, "X-Client-Version-Code": "53"})
        assert blocked.status_code == 426
        assert blocked.json()["error"]["details"]["minimum_supported_version_code"] == 54
        assert blocked.json()["error"]["details"]["update_url"] is None


@pytest.mark.parametrize(
    "payload",
    [
        {"mode": "optional", "expected_revision": 2**31, "reason": "Test"},
        {"mode": "required", "expected_revision": 4, "reason": "Test"},
        {"mode": "optional", "expected_revision": 4, "reason": "   "},
        {"mode": "optional", "expected_revision": 4, "reason": "Test", "release_id": str(uuid4())},
    ],
)
def test_policy_write_requires_reviewed_bounded_payload(payload):
    with pytest.raises(ValidationError):
        AndroidUpdatePolicyWrite(**payload)


@pytest.mark.asyncio
async def test_request_snapshot_drives_public_and_middleware_headers(monkeypatch):
    async def resolved(_settings):
        return policy.AndroidPolicySnapshot(6, 54, "optional")

    monkeypatch.setattr("app.core.middleware.resolve_android_update_policy", resolved)
    monkeypatch.setattr(public, "get_settings", settings)

    async def no_offer(_settings):
        return None

    monkeypatch.setattr(public, "_shared_verified_active_direct_offer", no_offer)
    app = FastAPI()
    app.include_router(public.router, prefix="/public")
    app.add_middleware(
        ClientCompatibilityMiddleware,
        android_minimum=8,
        android_latest=8,
        policy_revision=1,
        android_update_url=None,
        ios_minimum=1,
        ios_latest=1,
        ios_update_url=None,
        require_native_headers=True,
        message=None,
        resolve_android_policy=True,
    )
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        result = await client.get("/public/client-compatibility?platform=android&version_code=54")
        assert result.status_code == 200
        assert result.json()["policy_revision"] == 6
        assert result.json()["status"] == "supported"
        assert result.headers["X-Client-Compatibility-Policy-Revision"] == "6"
        assert result.headers["X-Minimum-Supported-Version-Code"] == "54"
        blocked = await client.get(
            "/anything", headers={"X-Client-Platform": "android", "X-Client-Version-Code": "53"}
        )
        assert blocked.status_code == 426
        assert blocked.json()["error"]["details"]["minimum_supported_version_code"] == 54
        assert blocked.json()["error"]["details"]["policy_revision"] == 6
        assert blocked.headers["X-Client-Compatibility-Policy-Revision"] == "6"
        ios = await client.get(
            "/public/client-compatibility?platform=ios&version_code=1",
            headers={"X-Client-Platform": "android"},
        )
        assert ios.status_code == 200
        assert ios.headers["X-Client-Compatibility-Policy-Revision"] == str(
            ios.json()["policy_revision"]
        )


@pytest.mark.asyncio
async def test_policy_database_failure_never_returns_supported(monkeypatch):
    async def broken(_settings):
        raise ServiceUnavailableError("Policy unavailable")

    monkeypatch.setattr("app.core.middleware.resolve_android_update_policy", broken)
    app = FastAPI()
    app.add_middleware(
        ClientCompatibilityMiddleware,
        android_minimum=8,
        android_latest=8,
        policy_revision=1,
        android_update_url=None,
        ios_minimum=1,
        ios_latest=1,
        ios_update_url=None,
        require_native_headers=True,
        message=None,
        resolve_android_policy=True,
    )

    @app.get("/ok")
    async def ok():
        return {"ok": True}

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        result = await client.get(
            "/ok", headers={"X-Client-Platform": "android", "X-Client-Version-Code": "55"}
        )
        assert result.status_code == 503
        assert "status" not in result.json()
        assert (await client.get("/ok")).status_code == 200


@pytest.mark.asyncio
async def test_policy_lookup_timeout_singleflight_does_not_cancel_cleanup(monkeypatch):
    import asyncio

    entered, release = asyncio.Event(), asyncio.Event()
    calls = 0

    async def hanging(_settings):
        nonlocal calls
        calls += 1
        entered.set()
        await release.wait()
        return policy.AndroidPolicySnapshot(4, 54)

    monkeypatch.setattr(policy, "_load", hanging)
    monkeypatch.setattr(policy, "POLICY_LOOKUP_BUDGET_SECONDS", 0.01)
    for _ in range(2):
        with pytest.raises(ServiceUnavailableError):
            await policy.resolve_android_update_policy(settings())
    assert calls == 1
    release.set()
    await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_required_runtime_parity_failure_is_not_a_supported_decision(monkeypatch):
    from app.services.client_updates.runtime_parity import RuntimeParityError

    async def loaded(_settings):
        return policy.AndroidPolicySnapshot(
            5, 55, "required", {"version_name": "3.1.47"}, "ab" * 20
        )

    async def mismatch(**_kwargs):
        raise RuntimeParityError("frontend_release_mismatch")

    monkeypatch.setattr(policy, "_load", loaded)
    monkeypatch.setattr(policy, "verify_runtime_parity_for_public_offer", mismatch)
    with pytest.raises(ServiceUnavailableError):
        await policy.resolve_android_update_policy(settings())
