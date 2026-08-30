"""Real-time WebSocket push — broadcast-on-write, end to end.

Needs a real Postgres connection (creates a company/branch/terminal/owner
and actually opens a shift through the HTTP API). Database unavailability is
a real integration failure; this test must not silently turn event-loop or
connection defects into a skip.

Uses a plain sync test with its own asyncio.run() for DB setup rather than
the usual async pytest-asyncio fixtures, so the WebSocket portion can use
Starlette's TestClient, which manages its own background event loop and
isn't meant to be driven from inside an already-running one.
"""

from __future__ import annotations

import asyncio
from uuid import uuid4

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool
from starlette.testclient import TestClient

from app.core.config import get_settings
from app.core.db import _async_url, async_engine
from app.core.security import (
    decode_token,
    hash_password,
    issue_access_token,
    issue_refresh_token,
)
from app.main import create_app
from app.models import Branch, Company, Role, Terminal, User, UserRole
from app.services.auth.refresh_sessions import register_refresh_session


async def _seed_owner() -> dict:
    # The full suite's shared async engine has already been used by pytest's
    # session event loop. asyncio.run() below intentionally creates a separate
    # loop for Starlette TestClient compatibility, so seeding through that
    # pooled engine can reuse a connection owned by the wrong loop. A
    # short-lived NullPool engine keeps every connection on this loop.
    engine = create_async_engine(
        _async_url(str(get_settings().database_url)),
        poolclass=NullPool,
    )
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    try:
        async with session_factory() as session:
            company = Company(id=uuid4(), name="RealtimeTestCo")
            branch = Branch(
                id=uuid4(),
                company_id=company.id,
                name="Main",
                invoice_series_code="MN",
            )
            terminal = Terminal(
                id=uuid4(),
                branch_id=branch.id,
                name="POS-T1",
                device_id=f"t-{uuid4()}",
            )
            owner_role = Role(
                id=uuid4(),
                company_id=company.id,
                code="owner",
                name="Owner",
                permissions=[],
            )
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
            # Migration 0047 bumps auth_version for every role mutation.
            await session.refresh(owner)
            family_id = uuid4()
            refresh = issue_refresh_token(
                user_id=owner.id,
                jti=str(uuid4()),
                auth_version=owner.auth_version,
                family_id=family_id,
            )
            register_refresh_session(
                session,
                user=owner,
                token=refresh,
                claims=decode_token(refresh),
                family_id=family_id,
            )
            await session.commit()
            return {
                "company": company,
                "branch": branch,
                "terminal": terminal,
                "owner": owner,
                "family_id": family_id,
            }
    finally:
        await engine.dispose()


def _token_for(seed: dict) -> str:
    return issue_access_token(
        user_id=seed["owner"].id,
        company_id=seed["company"].id,
        roles=["owner"],
        branch_id=seed["branch"].id,
        auth_version=seed["owner"].auth_version,
        extra={"session_family_id": str(seed["family_id"])},
    )


def test_ws_accepts_valid_token_and_receives_broadcast_on_shift_open():
    seed = asyncio.run(_seed_owner())
    token = _token_for(seed)

    # TestClient runs the ASGI app on its own loop. Replace (without trying to
    # close) any pool connections retained from pytest's async loop so the
    # request below cannot inherit a cross-loop asyncpg connection.
    asyncio.run(async_engine.dispose(close=False))
    app = create_app()
    try:
        with TestClient(app) as client:
            with client.websocket_connect("/api/v1/ws") as ws:
                ws.send_text(f'{{"token": "{token}"}}')
                hello = ws.receive_json()
                assert hello == {"type": "connected"}

                resp = client.post(
                    "/api/v1/pos/shifts/open",
                    json={"opening_float_minor": 50000},
                    headers={
                        "Authorization": f"Bearer {token}",
                        "X-Terminal-Id": str(seed["terminal"].id),
                    },
                )
                assert resp.status_code == 201, resp.text

                message = ws.receive_json()
                assert message == {"type": "changed", "resource": "shifts"}
    finally:
        # TestClient's loop is gone now. Detach its pool before the next async
        # pytest fixture runs on the suite's long-lived event loop.
        asyncio.run(async_engine.dispose(close=False))
