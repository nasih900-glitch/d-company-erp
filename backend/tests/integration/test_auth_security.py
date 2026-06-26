from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, text

from app.models import Role, UserRole
from app.core.security import decode_token


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.mark.asyncio
async def test_suspended_user_cannot_login(client, session, seed_owner) -> None:
    owner = seed_owner["owner"]
    owner.status = "suspended"
    await session.commit()

    r = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )

    assert r.status_code == 401


@pytest.mark.asyncio
async def test_repeated_bad_passwords_lock_account(client, seed_owner) -> None:
    owner = seed_owner["owner"]
    for _ in range(5):
        r = await client.post(
            "/api/v1/auth/login",
            json={"email": owner.email, "password": "wrong-password"},
        )
        assert r.status_code == 401

    r = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )

    assert r.status_code == 401
    assert r.json()["error"]["message"] == "account temporarily locked"


@pytest.mark.asyncio
async def test_me_masks_protected_owner_role(client, session, seed_owner) -> None:
    owner = seed_owner["owner"]
    company = seed_owner["company"]
    super_role = Role(
        id=uuid4(),
        company_id=company.id,
        code="super_owner",
        name="Owner",
        description="Owner access",
        permissions=[],
    )
    session.add(super_role)
    await session.flush()
    await session.execute(delete(UserRole).where(UserRole.user_id == owner.id))
    session.add(UserRole(id=uuid4(), user_id=owner.id, role_id=super_role.id))
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200
    claims = decode_token(login.json()["access_token"])
    assert claims["roles"] == ["owner"]
    assert claims["protected_access"] is True

    me = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {login.json()['access_token']}"},
    )

    assert me.status_code == 200
    assert me.json()["roles"] == ["owner"]
    assert me.json()["protected_access"] is True
