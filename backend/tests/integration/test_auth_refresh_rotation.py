from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.core.security import issue_access_token, issue_refresh_token
from app.models import AuthRefreshSession, Company


async def _login(client, seed_owner):
    response = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_refresh_consumes_one_token_once_and_revokes_family_on_reuse(
    client,
    seed_owner,
) -> None:
    login = await _login(client, seed_owner)
    original_refresh = login["refresh_token"]

    first, second = await asyncio.gather(
        client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": original_refresh},
        ),
        client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": original_refresh},
        ),
    )

    responses = [first, second]
    assert sorted(response.status_code for response in responses) == [200, 401]
    rejected = next(response for response in responses if response.status_code == 401)
    assert rejected.json()["error"]["code"] == "unauthorized"
    assert "reuse" in rejected.json()["error"]["message"]

    # Replaying one generation is evidence that the family may be stolen. The
    # successful race winner is therefore not allowed to continue rotating.
    winner_refresh = next(
        response.json()["refresh_token"] for response in responses if response.status_code == 200
    )
    family_revoked = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": winner_refresh},
    )
    assert family_revoked.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_sequential_refresh_replay_is_rejected_and_revokes_successor(
    client,
    seed_owner,
) -> None:
    login = await _login(client, seed_owner)
    original_refresh = login["refresh_token"]

    rotated = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": original_refresh},
    )
    assert rotated.status_code == 200, rotated.text

    replay = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": original_refresh},
    )
    assert replay.status_code == 401
    assert "reuse" in replay.json()["error"]["message"]

    successor = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": rotated.json()["refresh_token"]},
    )
    assert successor.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_native_bearer_logout_revokes_future_refresh(
    client,
    seed_owner,
) -> None:
    login = await _login(client, seed_owner)

    logged_out = await client.post(
        "/api/v1/auth/logout",
        json={},
        headers={"Authorization": f"Bearer {login['access_token']}"},
    )
    assert logged_out.status_code == 200, logged_out.text

    rejected = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": login["refresh_token"]},
    )
    assert rejected.status_code == 401

    rejected_access = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {login['access_token']}"},
    )
    assert rejected_access.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_logout_revokes_only_the_presented_family(client, seed_owner) -> None:
    first_login = await _login(client, seed_owner)
    second_login = await _login(client, seed_owner)

    logged_out = await client.post(
        "/api/v1/auth/logout",
        json={},
        headers={"Authorization": f"Bearer {first_login['access_token']}"},
    )
    assert logged_out.status_code == 200, logged_out.text

    first_access = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {first_login['access_token']}"},
    )
    second_access = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {second_login['access_token']}"},
    )
    assert first_access.status_code == 401
    assert second_access.status_code == 200


@pytest.mark.integration
@pytest.mark.asyncio
async def test_pre_ledger_refresh_has_exactly_one_exchange(
    client,
    seed_owner,
    session,
) -> None:
    owner = seed_owner["owner"]
    legacy_refresh = issue_refresh_token(
        user_id=owner.id,
        jti=str(uuid4()),
        auth_version=owner.auth_version,
    )

    first, second = await asyncio.gather(
        client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": legacy_refresh},
        ),
        client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": legacy_refresh},
        ),
    )
    responses = [first, second]
    assert sorted(response.status_code for response in responses) == [200, 401]
    exchanged = next(response for response in responses if response.status_code == 200)

    replay = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": legacy_refresh},
    )
    assert replay.status_code == 401

    rows = (
        (
            await session.execute(
                select(AuthRefreshSession).where(
                    AuthRefreshSession.user_id == owner.id,
                    AuthRefreshSession.legacy_exchange.is_(True),
                )
            )
        )
        .scalars()
        .all()
    )
    assert len(rows) == 1
    assert rows[0].consumed_at is not None
    assert rows[0].revoked_at is not None

    successor = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": exchanged.json()["refresh_token"]},
    )
    assert successor.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_pre_ledger_cookie_logout_invalidates_companion_access(
    client,
    seed_owner,
) -> None:
    owner = seed_owner["owner"]
    legacy_access = issue_access_token(
        user_id=owner.id,
        company_id=owner.company_id,
        roles=["owner"],
        auth_version=owner.auth_version,
    )
    legacy_refresh = issue_refresh_token(
        user_id=owner.id,
        jti=str(uuid4()),
        auth_version=owner.auth_version,
    )
    client.cookies.set("dcompany_refresh", legacy_refresh, path="/api/v1/auth")

    logged_out = await client.post(
        "/api/v1/auth/logout",
        json={},
        headers={"X-Session-Transport": "cookie"},
    )
    assert logged_out.status_code == 200, logged_out.text

    rejected_access = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {legacy_access}"},
    )
    assert rejected_access.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_refresh_ledger_rejects_cross_tenant_user_scope(
    seed_owner,
    session,
) -> None:
    other_company = Company(id=uuid4(), name="Other tenant")
    session.add(other_company)
    await session.flush()

    cross_tenant_row = AuthRefreshSession(
        company_id=other_company.id,
        user_id=seed_owner["owner"].id,
        family_id=uuid4(),
        token_hash="a" * 64,
        auth_version=seed_owner["owner"].auth_version,
        expires_at=datetime.now(UTC) + timedelta(days=7),
    )

    async def insert_cross_tenant_row() -> None:
        async with session.begin_nested():
            session.add(cross_tenant_row)
            await session.flush()

    with pytest.raises(IntegrityError, match="refresh session user crosses company scope"):
        await insert_cross_tenant_row()
