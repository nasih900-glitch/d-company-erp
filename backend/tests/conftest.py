"""Shared pytest fixtures: app, client, session, seed_minimal."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Iterator
from uuid import uuid4

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password
from app.main import create_app
from app.models import Branch, Company, Role, Terminal, User, UserRole


@pytest.fixture(scope="session")
def event_loop() -> Iterator[asyncio.AbstractEventLoop]:
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture
async def session() -> AsyncIterator[AsyncSession]:
    async with AsyncSessionLocal() as s:
        yield s
        await s.rollback()


@pytest_asyncio.fixture
async def client() -> AsyncIterator[AsyncClient]:
    app = create_app()
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest_asyncio.fixture
async def seed_owner(session: AsyncSession) -> dict:
    """Minimal seed: one company, one branch, one terminal, one owner user."""
    company = Company(id=uuid4(), name="TestCo")
    branch = Branch(id=uuid4(), company_id=company.id, name="Main")
    terminal = Terminal(id=uuid4(), branch_id=branch.id, name="POS-T1", device_id=f"t-{uuid4()}")
    owner_role = Role(id=uuid4(), company_id=company.id, code="owner", name="Owner", permissions=[])
    owner = User(
        id=uuid4(),
        company_id=company.id,
        email=f"owner-{uuid4().hex[:8]}@test.local",
        name="Owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add_all([company, branch, terminal, owner_role, owner])
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=owner.id, role_id=owner_role.id))
    await session.commit()
    return {
        "company": company,
        "branch": branch,
        "terminal": terminal,
        "owner": owner,
        "password": "password1234",
    }
