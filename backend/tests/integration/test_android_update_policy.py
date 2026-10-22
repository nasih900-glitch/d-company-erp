from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path
from uuid import uuid4

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import func, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from app.api.v1.client_updates import router as routes
from app.api.v1.public import router as public
from app.core import db as database
from app.core.config import get_settings
from app.core.security import issue_access_token
from app.main import create_app
from app.models import AndroidRelease, AndroidUpdatePolicy, AuditLog
from app.services.client_updates import policy as policy_service
from app.services.client_updates.policy import release_fingerprint
from tests.integration.test_client_update_runtime import _release


@pytest_asyncio.fixture(autouse=True)
async def owned_policy_database(monkeypatch):
    """Immutable global releases need a separate registry, not deletion/count weakening."""
    settings = get_settings()
    configured = make_url(str(settings.database_url))
    safe_name = (
        configured.database == "erp_test"
        or (configured.database or "").startswith("erp55_")
        or (configured.database or "").startswith("dcompany_physical_audit_code30_crossuser_55_")
    )
    if (
        settings.env not in {"test", "dev"}
        or not safe_name
        or configured.host not in {"127.0.0.1", "localhost", "postgres"}
    ):
        raise RuntimeError(
            "Policy integration isolation requires an explicitly configured local/CI test database"
        )
    name = "erp_policy_test_" + uuid4().hex
    admin_url = configured.set(drivername="postgresql+asyncpg", database="postgres")
    admin = create_async_engine(admin_url, isolation_level="AUTOCOMMIT")
    engine = None
    created = False
    try:
        async with admin.connect() as connection:
            # The identifier is a fixed prefix plus uuid4().hex, never external input.
            await connection.execute(text(f'CREATE DATABASE "{name}"'))  # noqa: S608
            created = True
        target = configured.set(database=name)
        environment = {
            **os.environ,
            "ENV": "test",
            "DATABASE_URL": target.render_as_string(hide_password=False),
        }
        migration = await asyncio.create_subprocess_exec(
            sys.executable,
            "-m",
            "alembic",
            "upgrade",
            "head",
            cwd=Path(__file__).resolve().parents[2],
            env=environment,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
        )
        await migration.communicate()
        if migration.returncode != 0:
            raise RuntimeError(
                "Owned policy test-database migration failed; no existing database was changed"
            )
        engine = create_async_engine(target.set(drivername="postgresql+asyncpg"))
        factory = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        monkeypatch.setattr(database, "AsyncSessionLocal", factory)
        monkeypatch.setattr(policy_service, "AsyncSessionLocal", factory)
        monkeypatch.setattr(public, "AsyncSessionLocal", factory)
        yield factory
    finally:
        if engine is not None:
            await engine.dispose()
        if created:
            async with admin.connect() as connection:
                await connection.execute(text(f'DROP DATABASE "{name}" WITH (FORCE)'))  # noqa: S608
        await admin.dispose()


@pytest_asyncio.fixture
async def session(owned_policy_database):
    async with owned_policy_database() as connection:
        yield connection
        await connection.rollback()


@pytest_asyncio.fixture
async def controlled(session, seed_owner, monkeypatch):
    settings = get_settings()
    owner = seed_owner["owner"]
    monkeypatch.setattr(
        settings, "android_release_controller_bindings", f"{owner.company_id}:{owner.id}"
    )
    monkeypatch.setattr(settings, "client_compatibility_policy_revision", 4)
    monkeypatch.setattr(settings, "android_min_supported_version_code", 54)
    monkeypatch.setattr(settings, "android_latest_version_code", 54)

    async def verified(*args, **kwargs):
        return None

    monkeypatch.setattr(routes, "verify_public_apk", verified)
    monkeypatch.setattr(routes, "verify_runtime_parity", verified)
    monkeypatch.setattr(routes, "record_verified_runtime_parity_for_public_offer", verified)
    monkeypatch.setattr(public, "verify_runtime_parity_for_public_offer", verified)
    monkeypatch.setattr(policy_service, "verify_runtime_parity_for_public_offer", verified)
    assert (await session.execute(select(AndroidUpdatePolicy))).scalar_one_or_none() is None
    code = max(
        55,
        int((await session.execute(select(func.max(AndroidRelease.version_code)))).scalar() or 54)
        + 1,
    )
    release = _release(version_code=code, version_name=f"3.1.{code}")
    session.add(release)
    await session.commit()
    token = issue_access_token(
        user_id=owner.id,
        company_id=owner.company_id,
        roles=["owner"],
        branch_id=seed_owner["branch"].id,
        auth_version=owner.auth_version,
        extra={"audit_access": True, "protected_access": True},
    )
    headers = {"Authorization": f"Bearer {token}", "X-Terminal-Id": str(seed_owner["terminal"].id)}
    yield headers, release


@pytest_asyncio.fixture
async def client(controlled):
    """Snapshot middleware after the fixture's deploy-time settings are set."""
    app = create_app()
    suffix = uuid4().hex[:16]
    host = "2001:db8::" + ":".join(suffix[index : index + 4] for index in range(0, 16, 4))
    transport = ASGITransport(app=app, client=(host, 40_000))
    async with AsyncClient(transport=transport, base_url="http://test") as connection:
        yield connection


def body(mode, revision, release=None):
    return {
        "mode": mode,
        "expected_revision": revision,
        "reason": "Reviewed tablet update policy",
        **(
            {
                "release_id": str(release.id),
                "expected_release_fingerprint": release_fingerprint(release),
            }
            if release
            else {}
        ),
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_policy_required_optional_cas_audit_and_release_guards(
    client, session, seed_owner, controlled
):
    headers, release = controlled
    initial = await client.get("/api/v1/client-updates/android/policy", headers=headers)
    assert initial.status_code == 200, initial.text
    assert (
        initial.json()["mode"],
        initial.json()["revision"],
        initial.json()["minimum_supported_version_code"],
    ) == ("inherit", 4, 54)
    activated = await client.post(
        f"/api/v1/client-updates/android/releases/{release.id}/activate", headers=headers
    )
    assert activated.status_code == 200, activated.text
    required = await client.put(
        "/api/v1/client-updates/android/policy", headers=headers, json=body("required", 4, release)
    )
    assert required.status_code == 200, required.text
    assert required.json()["revision"] == 5
    assert required.json()["minimum_supported_version_code"] == release.version_code
    blocked = await client.get(
        "/api/v1/auth/me",
        headers={**headers, "X-Client-Platform": "android", "X-Client-Version-Code": "54"},
    )
    assert blocked.status_code == 426
    assert blocked.json()["error"]["details"]["policy_revision"] == 5
    for channel in ("managed", "play"):
        isolated_headers = {
            **headers,
            "X-Client-Platform": "android",
            "X-Client-Version-Code": "54",
            "X-Client-Distribution-Channel": channel,
        }
        allowed = await client.get("/api/v1/auth/me", headers=isolated_headers)
        assert allowed.status_code == 200, allowed.text
        assert allowed.headers["X-Minimum-Supported-Version-Code"] == "54"
        assert allowed.headers["X-Client-Compatibility-Policy-Revision"] == "4"
        compatible = await client.get(
            "/api/v1/public/client-compatibility?platform=android&version_code=54",
            headers=isolated_headers,
        )
        assert compatible.status_code == 200, compatible.text
        assert compatible.json()["status"] == "supported"
        assert compatible.json()["minimum_supported_version_code"] == 54
        assert compatible.json()["policy_revision"] == 4
        assert compatible.headers["X-Client-Compatibility-Policy-Revision"] == "4"
        for field in ("update_url", "apk_sha256", "apk_size_bytes", "apk_signing_cert_sha256"):
            assert compatible.json()[field] is None
    assert (
        await client.post(
            f"/api/v1/client-updates/android/releases/{release.id}/withdraw", headers=headers
        )
    ).status_code == 409
    code = release.version_code + 1
    replacement = _release(version_code=code, version_name=f"3.1.{code}")
    session.add(replacement)
    await session.commit()
    assert (
        await client.post(
            f"/api/v1/client-updates/android/releases/{replacement.id}/activate", headers=headers
        )
    ).status_code == 409
    relaxed = await client.put(
        "/api/v1/client-updates/android/policy", headers=headers, json=body("optional", 5)
    )
    assert relaxed.status_code == 200, relaxed.text
    assert (relaxed.json()["revision"], relaxed.json()["minimum_supported_version_code"]) == (6, 54)
    stale = await client.put(
        "/api/v1/client-updates/android/policy", headers=headers, json=body("required", 5, release)
    )
    assert stale.status_code == 409
    legacy = await client.get(
        "/api/v1/public/client-compatibility?platform=android&version_code=54"
    )
    assert legacy.status_code == 200, legacy.text
    assert legacy.json()["status"] == "supported"
    assert legacy.json()["policy_revision"] == 6
    current = await client.get(
        "/api/v1/public/client-compatibility?platform=android&version_code=55"
    )
    assert current.json()["status"] == (
        "update_available" if release.version_code > 55 else "supported"
    )
    managed = await client.get(
        "/api/v1/public/client-compatibility?platform=android&version_code=54",
        headers={"X-Client-Distribution-Channel": "managed"},
    )
    assert managed.json()["update_url"] is None
    assert (
        await client.post(
            f"/api/v1/client-updates/android/releases/{release.id}/withdraw", headers=headers
        )
    ).status_code == 200
    await session.rollback()
    audits = (
        (
            await session.execute(
                select(AuditLog).where(
                    AuditLog.company_id == seed_owner["company"].id,
                    AuditLog.action == "android_update_policy_changed",
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(audits) == 2
    assert {a.after["revision"] for a in audits} == {5, 6}
    assert all(a.actor_user_id == seed_owner["owner"].id and a.after["reason"] for a in audits)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_policy_authorization_cas_race_and_overflow(
    client, session, seed_owner, controlled, monkeypatch
):
    headers, _ = controlled
    denied = await client.get("/api/v1/client-updates/android/policy")
    assert denied.status_code == 401
    settings = get_settings()
    monkeypatch.setattr(
        settings, "android_release_controller_bindings", f"{uuid4()}:{seed_owner['owner'].id}"
    )
    assert (
        await client.get("/api/v1/client-updates/android/policy", headers=headers)
    ).status_code == 403
    assert (
        await client.put(
            "/api/v1/client-updates/android/policy", headers=headers, json=body("optional", 4)
        )
    ).status_code == 403
    monkeypatch.setattr(
        settings,
        "android_release_controller_bindings",
        f"{seed_owner['owner'].company_id}:{seed_owner['owner'].id}",
    )
    results = await asyncio.gather(
        *(
            client.put(
                "/api/v1/client-updates/android/policy", headers=headers, json=body("optional", 4)
            )
            for _ in range(2)
        )
    )
    assert sorted(r.status_code for r in results) == [200, 409]
    await session.rollback()
    row = (await session.execute(select(AndroidUpdatePolicy))).scalar_one()
    row.revision = 2_147_483_647
    await session.commit()
    result = await client.put(
        "/api/v1/client-updates/android/policy",
        headers=headers,
        json=body("optional", 2_147_483_647),
    )
    assert result.status_code == 409
    assert (
        await client.put(
            "/api/v1/client-updates/android/policy",
            headers=headers,
            json=body("optional", 2_147_483_648),
        )
    ).status_code == 422


@pytest.mark.integration
@pytest.mark.asyncio
async def test_required_verification_failure_leaves_policy_unchanged(
    client, controlled, monkeypatch
):
    headers, release = controlled
    assert (
        await client.post(
            f"/api/v1/client-updates/android/releases/{release.id}/activate", headers=headers
        )
    ).status_code == 200

    async def broken(*args, **kwargs):
        raise TimeoutError()

    monkeypatch.setattr(routes, "verify_public_apk", broken)
    failed = await client.put(
        "/api/v1/client-updates/android/policy", headers=headers, json=body("required", 4, release)
    )
    assert failed.status_code == 503
    unchanged = await client.get("/api/v1/client-updates/android/policy", headers=headers)
    assert unchanged.json()["mode"] == "inherit"
    assert unchanged.json()["revision"] == 4
    # Relaxation is deliberately independent of APK network availability.
    assert (
        await client.put(
            "/api/v1/client-updates/android/policy", headers=headers, json=body("optional", 4)
        )
    ).status_code == 200
