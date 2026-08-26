from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from sqlalchemy import delete

from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.models import Shift


@pytest.mark.integration
@pytest.mark.asyncio
async def test_negative_shift_count_is_422_without_mutating_open_shift(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=5_000,
        expected_minor=5_000,
        status="open",
    )
    session.add(shift)
    await session.commit()
    token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    base_headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal.id),
    }
    path = f"/api/v1/pos/shifts/{shift.id}/close"

    try:
        rejected = await client.post(
            path,
            json={"counted_minor": -1},
            headers={
                **base_headers,
                "Idempotency-Key": f"negative-shift-close:{uuid4()}",
            },
        )

        assert rejected.status_code == 422, rejected.text
        error = rejected.json()["error"]
        assert error["code"] == "validation_error"
        assert any(
            field["field"] == "counted_minor" and
            field["type"] == "greater_than_equal"
            for field in error["details"]["fields"]
        )

        async with AsyncSessionLocal() as verify:
            unchanged = await verify.get(Shift, shift.id)
            assert unchanged is not None
            assert unchanged.status == "open"
            assert unchanged.closed_at is None
            assert unchanged.counted_minor is None
            assert unchanged.variance_minor is None

    finally:
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.commit()
