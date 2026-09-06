from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from sqlalchemy import delete, select

from app.api.v1.pos import router as pos_router
from app.core.db import AsyncSessionLocal
from app.core.security import hash_password, issue_access_token
from app.core.tenant import TenantContext
from app.models import AuditLog, Role, Shift, User, UserRole
from app.services.audit.recorder import install_audit_listeners


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
async def test_authorized_colleague_closes_shift_and_is_durably_attributed(
    client,
    session,
    seed_owner,
) -> None:
    """The opener owns the opening fact; the authenticated closer owns close."""
    # ASGITransport does not run FastAPI lifespan hooks. Production installs
    # this listener during startup; integration tests that assert the audit row
    # must install it explicitly, as the dedicated audit tests do.
    install_audit_listeners()
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    opener = seed_owner["owner"]
    owner_role = (
        await session.execute(
            select(Role).where(
                Role.company_id == company.id,
                Role.code == "owner",
            )
        )
    ).scalar_one()
    closer = User(
        id=uuid4(),
        company_id=company.id,
        email=f"shift-closer-{uuid4().hex[:8]}@test.local",
        name="Sameer",
        password_hash=hash_password("password1234"),
        status="active",
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=opener.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=5_000,
        expected_minor=5_000,
        status="open",
    )
    session.add_all([closer, shift])
    await session.flush()
    assignment = UserRole(
        id=uuid4(),
        user_id=closer.id,
        role_id=owner_role.id,
        branch_id=branch.id,
        granted_by=opener.id,
    )
    session.add(assignment)
    await session.commit()
    await session.refresh(closer)
    token = issue_access_token(
        user_id=closer.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=closer.auth_version,
        extra={"protected_access": False, "audit_access": False},
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal.id),
        "X-Request-Id": f"shift-close-by-colleague-{uuid4().hex}",
    }

    try:
        response = await client.post(
            f"/api/v1/pos/shifts/{shift.id}/close",
            json={"counted_minor": 5_000},
            headers=headers,
        )
        assert response.status_code == 200, response.text
        assert response.json() == {
            "id": str(shift.id),
            "status": "closed",
            "variance_minor": 0,
            "opened_by": str(opener.id),
            "closed_by": str(closer.id),
            "closed_by_was_opener": False,
        }

        history = await client.get("/api/v1/pos/shifts", headers=headers)
        assert history.status_code == 200, history.text
        history_row = next(
            row for row in history.json() if row["id"] == str(shift.id)
        )
        assert history_row["opened_by"] == str(opener.id)
        assert history_row["opened_by_name"] == opener.name
        assert history_row["closed_by"] == str(closer.id)
        assert history_row["closed_by_name"] == "Sameer"
        assert history_row["closed_by_email"] == closer.email

        # Operational permission never grants the protected Audit Log.
        audit_read = await client.get("/api/v1/admin/audit", headers=headers)
        assert audit_read.status_code == 403, audit_read.text

        async with AsyncSessionLocal() as verify:
            saved = await verify.get(Shift, shift.id)
            assert saved is not None
            assert saved.status == "closed"
            assert saved.opened_by == opener.id
            assert saved.closed_by == closer.id

            audit = (
                await verify.execute(
                    select(AuditLog)
                    .where(
                        AuditLog.company_id == company.id,
                        AuditLog.entity_type == "Shift",
                        AuditLog.entity_id == str(shift.id),
                        AuditLog.action == "update",
                    )
                    .order_by(AuditLog.id.desc())
                    .limit(1)
                )
            ).scalar_one()
            assert audit.actor_user_id == closer.id
            assert audit.after is not None
            assert audit.after["closed_by"] == str(closer.id)
            assert audit.after["status"] == "closed"
    finally:
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(AuditLog).where(
                    AuditLog.company_id == company.id,
                    AuditLog.entity_id == str(shift.id),
                )
            )
            await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
            await cleanup.execute(delete(UserRole).where(UserRole.id == assignment.id))
            await cleanup.execute(delete(User).where(User.id == closer.id))
            await cleanup.commit()


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
