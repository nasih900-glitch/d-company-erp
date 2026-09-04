from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from sqlalchemy import delete, select

from app.api.v1.pos import router as pos_router
from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.core.tenant import TenantContext
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


@pytest.mark.integration
@pytest.mark.asyncio
async def test_negative_opening_float_is_422_without_creating_shift(
    client,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )

    rejected = await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": -1},
        headers={
            "Authorization": f"Bearer {token}",
            "X-Terminal-Id": str(terminal.id),
        },
    )

    assert rejected.status_code == 422, rejected.text
    error = rejected.json()["error"]
    assert error["code"] == "validation_error"
    assert any(
        field["field"] == "opening_float_minor" and
        field["type"] == "greater_than_equal"
        for field in error["details"]["fields"]
    )
    async with AsyncSessionLocal() as verify:
        shifts = (
            await verify.execute(
                select(Shift).where(
                    Shift.company_id == company.id,
                    Shift.terminal_id == terminal.id,
                )
            )
        ).scalars().all()
    assert shifts == []


@pytest.mark.integration
@pytest.mark.asyncio
async def test_same_terminal_open_waits_for_inflight_close_then_creates_new_shift(
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    original = Shift(
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
    tenant = TenantContext(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        roles=("owner",),
        protected_access=True,
    )
    reopened_id: UUID | None = None
    open_task: asyncio.Task[dict] | None = None

    async with AsyncSessionLocal() as create:
        create.add(original)
        await create.commit()

    try:
        async with AsyncSessionLocal() as closer:
            closed = await pos_router.close_shift(
                original.id,
                pos_router.ShiftCloseRequest(counted_minor=5_000),
                closer,
                tenant,
            )
            assert closed["status"] == "closed"

            entered_open = asyncio.Event()

            async def open_after_close() -> dict:
                async with AsyncSessionLocal() as opener:
                    entered_open.set()
                    opened = await pos_router.open_shift(
                        pos_router.ShiftOpenRequest(opening_float_minor=7_500),
                        opener,
                        tenant,
                    )
                    await opener.commit()
                    return opened

            open_task = asyncio.create_task(open_after_close())
            await entered_open.wait()

            # The terminal lock is free, but the matching open Shift row is
            # locked by the uncommitted close. A correct open must wait for
            # that row and re-evaluate its status after this commit.
            done, _pending = await asyncio.wait({open_task}, timeout=0.2)
            assert not done, "open returned an in-flight shift that was already closing"

            await closer.commit()
            reopened = await asyncio.wait_for(open_task, timeout=2.0)
            reopened_id = UUID(reopened["id"])

        assert reopened["status"] == "open"
        assert reopened_id != original.id
        async with AsyncSessionLocal() as verify:
            rows = (
                await verify.execute(
                    select(Shift)
                    .where(
                        Shift.company_id == company.id,
                        Shift.terminal_id == terminal.id,
                    )
                    .order_by(Shift.opened_at)
                )
            ).scalars().all()
        assert [(str(row.id), row.status) for row in rows] == [
            (str(original.id), "closed"),
            (str(reopened_id), "open"),
        ]
    finally:
        if open_task is not None:
            if not open_task.done():
                open_task.cancel()
            await asyncio.gather(open_task, return_exceptions=True)
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(Shift).where(
                    Shift.id.in_([original.id] + ([reopened_id] if reopened_id else []))
                )
            )
            await cleanup.commit()
