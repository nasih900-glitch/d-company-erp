from __future__ import annotations

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError

from app.api.v1.public.router import client_compatibility
from app.core.config import Settings, get_settings
from app.core.middleware import ClientCompatibilityMiddleware


def test_backend_defaults_require_and_advertise_v8() -> None:
    assert Settings.model_fields["android_min_supported_version_code"].default == 8
    assert Settings.model_fields["android_latest_version_code"].default == 8
    assert Settings.model_fields["require_native_version_headers"].default is True


@pytest.mark.asyncio
async def test_android_compatibility_distinguishes_required_optional_and_current(
    monkeypatch,
) -> None:
    settings = get_settings()
    monkeypatch.setattr(settings, "android_min_supported_version_code", 5)
    monkeypatch.setattr(settings, "android_latest_version_code", 7)
    monkeypatch.setattr(settings, "android_update_url", "https://example.test/erp.apk")
    monkeypatch.setattr(settings, "android_latest_version_name", "3.1.0")
    monkeypatch.setattr(
        settings,
        "android_update_release_notes",
        "Gaming Centre reliability release",
    )
    monkeypatch.setattr(settings, "android_update_apk_sha256", "ab" * 32)
    monkeypatch.setattr(settings, "android_update_apk_size_bytes", 12_345_678)
    monkeypatch.setattr(settings, "android_update_signing_cert_sha256", "cd" * 32)

    required = await client_compatibility(platform="android", version_code=4)
    optional = await client_compatibility(platform="android", version_code=5)
    optional_v6 = await client_compatibility(platform="android", version_code=6)
    supported = await client_compatibility(platform="android", version_code=7)

    assert required.status == "update_required"
    assert required.minimum_supported_version_code == 5
    assert required.update_url == "https://example.test/erp.apk"
    assert required.latest_version_name == "3.1.0"
    assert required.release_notes == "Gaming Centre reliability release"
    assert required.apk_sha256 == "ab" * 32
    assert required.apk_size_bytes == 12_345_678
    assert required.apk_signing_cert_sha256 == "cd" * 32
    assert optional.status == "update_available"
    assert optional_v6.status == "update_available"
    assert supported.status == "supported"
    assert supported.checked_at.tzinfo is not None


def test_latest_native_build_cannot_be_lower_than_minimum() -> None:
    with pytest.raises(ValidationError, match="ANDROID_LATEST_VERSION_CODE"):
        Settings(
            android_min_supported_version_code=4,
            android_latest_version_code=3,
        )


def test_native_update_url_rejects_non_http_schemes() -> None:
    with pytest.raises(ValidationError):
        Settings(android_update_url="javascript:alert(1)")


def test_production_native_update_url_requires_https() -> None:
    with pytest.raises(ValidationError, match="ANDROID_UPDATE_URL must use HTTPS"):
        Settings(
            env="prod",
            jwt_secret="production-secret-that-is-longer-than-thirty-two-characters",
            android_update_url="http://downloads.example.test/erp.apk",
        )


def test_production_cannot_raise_android_policy_without_a_delivery_url() -> None:
    production = {
        "env": "prod",
        "jwt_secret": "production-secret-that-is-longer-than-thirty-two-characters",
    }
    baseline = Settings(**production)
    assert baseline.android_min_supported_version_code == 8
    assert baseline.android_latest_version_code == 8

    for policy in (
        {"android_latest_version_code": 11},
        {
            "android_min_supported_version_code": 11,
            "android_latest_version_code": 11,
        },
    ):
        with pytest.raises(ValidationError, match="ANDROID_UPDATE_URL is required"):
            Settings(**production, **policy)

    configured = Settings(
        **production,
        android_latest_version_code=11,
        android_update_url="https://dcompany.duckdns.org/downloads/android/app-v3.1.0.apk",
        android_latest_version_name="3.1.0",
        android_update_apk_sha256="ab" * 32,
        android_update_apk_size_bytes=123,
        android_update_signing_cert_sha256="cd" * 32,
    )
    assert configured.android_latest_version_code == 11


def test_verified_android_update_metadata_is_atomic_and_normalized() -> None:
    assert Settings(android_update_apk_size_bytes="").android_update_apk_size_bytes is None

    with pytest.raises(ValidationError, match="must be configured together"):
        Settings(android_update_apk_sha256="ab" * 32)

    with pytest.raises(ValidationError, match="also require"):
        Settings(
            android_update_apk_sha256="ab" * 32,
            android_update_apk_size_bytes=123,
            android_update_signing_cert_sha256="cd" * 32,
        )

    with pytest.raises(ValidationError, match="An Android \\.apk update URL requires"):
        Settings(android_update_url="https://example.test/d-company.apk")

    # Store/managed-play links do not describe bytes downloaded by the direct
    # updater, so they deliberately remain valid without APK integrity fields.
    play_link = Settings(
        android_update_url="https://play.google.com/store/apps/details?id=cloud.dcompany.erp"
    )
    assert play_link.android_update_apk_sha256 is None

    with pytest.raises(ValidationError, match="HTTPS .apk URL"):
        Settings(
            android_update_url="http://example.test/d-company.zip",
            android_latest_version_name="3.1.0",
            android_update_apk_sha256="ab" * 32,
            android_update_apk_size_bytes=123,
            android_update_signing_cert_sha256="cd" * 32,
        )

    for field, unsafe_url in (
        ("android_update_url", "https://operator:secret@example.test/d-company.apk"),
        ("android_update_url", "https://example.test/d-company.apk#untrusted"),
        ("ios_update_url", "https://example.test/store#untrusted"),
    ):
        with pytest.raises(ValidationError, match="credentials or a URL fragment"):
            Settings(**{field: unsafe_url})

    configured = Settings(
        android_update_url="https://example.test/d-company.apk",
        android_latest_version_name="3.1.0",
        android_update_apk_sha256=("AB:" * 31) + "AB",
        android_update_apk_size_bytes=123,
        android_update_signing_cert_sha256=("CD:" * 31) + "CD",
    )
    assert configured.android_update_apk_sha256 == "ab" * 32
    assert configured.android_update_signing_cert_sha256 == "cd" * 32

    whitespace_version = Settings(android_latest_version_name=" 3.1.0 ")
    assert whitespace_version.android_latest_version_name == "3.1.0"


def test_native_version_codes_must_fit_the_client_wire_integer() -> None:
    for field in (
        "android_min_supported_version_code",
        "android_latest_version_code",
        "ios_min_supported_version_code",
        "ios_latest_version_code",
    ):
        with pytest.raises(ValidationError, match="less than or equal to 2147483647"):
            Settings(**{field: 2_147_483_648})


def _compatibility_app(*, require_headers: bool = False) -> FastAPI:
    app = FastAPI()
    app.add_middleware(
        ClientCompatibilityMiddleware,
        android_minimum=5,
        android_latest=7,
        android_update_url="https://example.test/erp.apk",
        ios_minimum=2,
        ios_latest=2,
        ios_update_url=None,
        require_native_headers=require_headers,
        message=None,
        android_latest_version_name="3.1.0",
        android_release_notes="Gaming Centre reliability release",
        android_apk_sha256="ab" * 32,
        android_apk_size_bytes=12_345_678,
        android_apk_signing_cert_sha256="cd" * 32,
    )

    @app.get("/api/v1/private")
    async def private() -> dict[str, bool]:
        return {"ok": True}

    @app.get("/api/v1/public/client-compatibility")
    async def public_contract() -> dict[str, bool]:
        return {"ok": True}

    return app


@pytest.mark.asyncio
async def test_declared_native_build_is_enforced_and_supported_build_is_annotated() -> None:
    transport = ASGITransport(app=_compatibility_app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        outdated = await client.get(
            "/api/v1/private",
            headers={"X-Client-Platform": "android", "X-Client-Version-Code": "4"},
        )
        current = await client.get(
            "/api/v1/private",
            headers={"X-Client-Platform": "android", "X-Client-Version-Code": "5"},
        )
        contract = await client.get("/api/v1/public/client-compatibility")

    assert outdated.status_code == 426
    assert outdated.json()["error"]["code"] == "client_update_required"
    assert outdated.json()["error"]["details"]["update_url"].endswith("erp.apk")
    assert outdated.json()["error"]["details"]["latest_version_name"] == "3.1.0"
    assert outdated.json()["error"]["details"]["apk_sha256"] == "ab" * 32
    assert outdated.json()["error"]["details"]["apk_size_bytes"] == 12_345_678
    assert outdated.json()["error"]["details"]["apk_signing_cert_sha256"] == "cd" * 32
    assert current.status_code == 200
    assert current.headers["X-Minimum-Supported-Version-Code"] == "5"
    assert current.headers["X-Latest-Version-Code"] == "7"
    assert contract.status_code == 200


@pytest.mark.asyncio
async def test_legacy_android_header_enforcement_is_safe_to_roll_out_separately() -> None:
    legacy_headers = {"User-Agent": "okhttp/4.12.0"}
    permissive = ASGITransport(app=_compatibility_app(require_headers=False))
    strict = ASGITransport(app=_compatibility_app(require_headers=True))

    async with AsyncClient(transport=permissive, base_url="http://test") as client:
        before_rollout = await client.get("/api/v1/private", headers=legacy_headers)
    async with AsyncClient(transport=strict, base_url="http://test") as client:
        after_rollout = await client.get("/api/v1/private", headers=legacy_headers)

    assert before_rollout.status_code == 200
    assert after_rollout.status_code == 426
    assert after_rollout.json()["error"]["code"] == "client_version_missing"


@pytest.mark.asyncio
async def test_native_version_must_fit_persisted_postgres_integer() -> None:
    transport = ASGITransport(app=_compatibility_app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/api/v1/private",
            headers={
                "X-Client-Platform": "android",
                "X-Client-Version-Code": str(2**63),
            },
        )

    assert response.status_code == 426
    assert response.json()["error"]["code"] == "client_version_invalid"
