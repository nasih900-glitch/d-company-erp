"""Real-time WebSocket push — broadcast-on-write, end to end.

Needs a real Postgres connection (creates a company/branch/terminal/owner
and actually opens a shift through the HTTP API), so it's skipped when run
against the sandboxed local environment the same way every other
integration test in this suite is.

Uses a plain sync test with its own asyncio.run() for DB setup rather than
the usual async pytest-asyncio fixtures, so the WebSocket portion can use
Starlette's TestClient, which manages its own background event loop and
isn't meant to be driven from inside an already-running one.
"""

from __future__ import annotations

import asyncio
from uuid import uuid4

import pytest
from starlette.testclient import TestClient

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password, issue_access_token
from app.main import create_app
from app.models import Branch, Company, Role, Terminal, User, UserRole


async def _seed_owner() -> dict:
    async with AsyncSessionLocal() as session:
        company = Company(id=uuid4(), name="RealtimeTestCo")
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
        return {"company": company, "branch": branch, "terminal": terminal, "owner": owner}


def _token_for(seed: dict) -> str:
    return issue_access_token(
        user_id=seed["owner"].id,
        company_id=seed["company"].id,
        roles=["owner"],
        branch_id=seed["branch"].id,
    )


def test_ws_accepts_valid_token_and_receives_broadcast_on_shift_open():
    try:
        seed = asyncio.run(_seed_owner())
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")
    token = _token_for(seed)

    app = create_app()
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
