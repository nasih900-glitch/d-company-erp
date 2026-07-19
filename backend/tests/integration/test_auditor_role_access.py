"""Endpoint-level confirmation that the 'auditor' role is read-only.

Companion to tests/unit/test_permissions.py's dict-level assertions (which
check ROLE_PERMISSIONS directly without a DB). These hit real routes through
the FastAPI dependency chain (requires() -> get_tenant_context -> DB-backed
module-override lookup) so require Postgres, same as the other integration
tests in this package.
"""

from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, text

from app.models import Role, UserRole


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _login_as_auditor(client, session, seed_owner) -> str:
    """Re-assign the seeded owner user to the 'auditor' role and log in,
    returning the access token. Mirrors test_auth_security.py's
    test_me_masks_protected_owner_role pattern of swapping a seeded user's
    role to exercise a specific role's claims."""
    owner = seed_owner["owner"]
    company = seed_owner["company"]
    auditor_role = Role(
        id=uuid4(),
        company_id=company.id,
        code="auditor",
        name="Auditor",
        description="Audit staff",
        permissions=[],
    )
    session.add(auditor_role)
    await session.flush()
    await session.execute(delete(UserRole).where(UserRole.user_id == owner.id))
    session.add(UserRole(id=uuid4(), user_id=owner.id, role_id=auditor_role.id))
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200
    return login.json()["access_token"]


@pytest.mark.asyncio
async def test_auditor_role_gets_403_on_write_endpoint(client, session, seed_owner) -> None:
    token = await _login_as_auditor(client, session, seed_owner)

    r = await client.post(
        "/api/v1/pos/orders",
        json={},
        headers={"Authorization": f"Bearer {token}"},
    )

    # The permission dependency runs before body validation, so this 403s on
    # the missing pos.write permission rather than 422ing on the empty body.
    assert r.status_code == 403


@pytest.mark.asyncio
async def test_auditor_role_gets_200_on_read_endpoint(client, session, seed_owner) -> None:
    token = await _login_as_auditor(client, session, seed_owner)

    r = await client.get(
        "/api/v1/finance/pnl",
        headers={"Authorization": f"Bearer {token}"},
    )

    assert r.status_code == 200
