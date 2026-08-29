from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.core.security import issue_access_token
from app.models import Branch, Company, Terminal


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


def _headers(token: str, terminal_id=None) -> dict[str, str]:
    headers = {"Authorization": f"Bearer {token}"}
    if terminal_id is not None:
        headers["X-Terminal-Id"] = str(terminal_id)
    return headers


def _token(seed_owner: dict, branch_id) -> str:
    owner = seed_owner["owner"]
    return issue_access_token(
        user_id=owner.id,
        company_id=seed_owner["company"].id,
        roles=["owner"],
        branch_id=branch_id,
        auth_version=owner.auth_version,
    )


@pytest.mark.asyncio
async def test_me_returns_tenant_scoped_branch_name(client, seed_owner) -> None:
    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200

    response = await client.get(
        "/api/v1/auth/me",
        headers=_headers(login.json()["access_token"]),
    )

    assert response.status_code == 200
    assert response.json()["branch_id"] == str(seed_owner["branch"].id)
    assert response.json()["branch_name"] == "Main"


@pytest.mark.asyncio
async def test_me_returns_no_branch_name_when_scope_has_no_branch(client, seed_owner) -> None:
    response = await client.get(
        "/api/v1/auth/me",
        headers=_headers(_token(seed_owner, branch_id=None)),
    )

    assert response.status_code == 200
    assert response.json()["branch_id"] is None
    assert response.json()["branch_name"] is None


@pytest.mark.asyncio
async def test_me_rejects_branch_scope_from_another_company(
    client,
    session,
    seed_owner,
) -> None:
    other_company = Company(id=uuid4(), name=f"Other-{uuid4().hex[:8]}")
    other_branch = Branch(
        id=uuid4(),
        company_id=other_company.id,
        name="Confidential Other Branch",
        invoice_series_code="MN",
    )
    session.add_all([other_company, other_branch])
    await session.commit()

    response = await client.get(
        "/api/v1/auth/me",
        headers=_headers(_token(seed_owner, branch_id=other_branch.id)),
    )

    assert response.status_code == 401
    assert response.json()["error"]["message"] == "branch not found"
    assert "Confidential Other Branch" not in response.text


@pytest.mark.asyncio
async def test_me_rejects_terminal_from_another_company(client, session, seed_owner) -> None:
    other_company = Company(id=uuid4(), name=f"Other-{uuid4().hex[:8]}")
    other_branch = Branch(
        id=uuid4(),
        company_id=other_company.id,
        name="Other",
        invoice_series_code="MN",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name="Other Till",
        device_id=f"other-{uuid4()}",
    )
    session.add_all([other_company, other_branch, other_terminal])
    await session.commit()

    response = await client.get(
        "/api/v1/auth/me",
        headers=_headers(
            _token(seed_owner, branch_id=seed_owner["branch"].id),
            terminal_id=other_terminal.id,
        ),
    )

    assert response.status_code == 401
    assert response.json()["error"]["message"] == "terminal not found"
