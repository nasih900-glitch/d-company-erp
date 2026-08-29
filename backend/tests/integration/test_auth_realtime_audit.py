"""Login audit realtime signals are emitted only for successful authentication."""

from __future__ import annotations

from uuid import uuid4

import pytest

from app.core.security import issue_access_token
from app.services.realtime import manager as realtime_manager


@pytest.mark.integration
@pytest.mark.asyncio
async def test_successful_login_broadcasts_audit_after_commit_but_401_does_not(
    client,
    seed_owner,
    monkeypatch,
) -> None:
    broadcasts: list[tuple[object, str]] = []

    async def capture_broadcast(company_id, resource: str) -> None:
        broadcasts.append((company_id, resource))

    monkeypatch.setattr(realtime_manager, "broadcast", capture_broadcast)

    failed = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": "definitely-not-the-password",
        },
    )
    assert failed.status_code == 401, failed.text
    assert broadcasts == []

    succeeded = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
        # The login endpoint must not trust this unrelated incoming token for
        # realtime tenant scope; only the authenticated database user decides.
        headers={
            "Authorization": "Bearer "
            + issue_access_token(
                user_id=uuid4(),
                company_id=uuid4(),
                roles=["owner"],
                branch_id=uuid4(),
                auth_version=0,
            )
        },
    )
    assert succeeded.status_code == 200, succeeded.text
    assert broadcasts == [(seed_owner["company"].id, "audit")]
