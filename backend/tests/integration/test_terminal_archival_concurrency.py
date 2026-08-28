"""PostgreSQL proof that shift opening and terminal archival serialize safely."""

from __future__ import annotations

import asyncio
from contextlib import suppress
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, inspect, text

from app.api.v1.pos.router import ShiftOpenRequest, open_shift
from app.api.v1.settings.router import TerminalUpdate, update_terminal
from app.core.db import AsyncSessionLocal
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext
from app.models import Shift, Terminal


@pytest_asyncio.fixture(autouse=True)
async def require_local_postgres(session) -> None:
    try:
        await session.execute(text("select 1"))
        required_columns = int(
            (
                await session.execute(
                    text(
                        "SELECT count(*) FROM information_schema.columns "
                        "WHERE table_schema = current_schema() AND "
                        "((table_name = 'branches' AND "
                        "column_name = 'invoice_series_code') OR "
                        "(table_name = 'terminals' AND column_name = 'is_active'))"
                    )
                )
            ).scalar_one()
            or 0
        )
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")
    if required_columns != 2:
        pytest.skip("local Postgres schema is not migrated through terminal archival")


@pytest.mark.integration
@pytest.mark.asyncio
async def test_terminal_active_index_exists_in_deployed_schema(session) -> None:
    connection = await session.connection()
    indexes = await connection.run_sync(
        lambda sync_connection: inspect(sync_connection).get_indexes("terminals")
    )
    active_index = next(
        index
        for index in indexes
        if index["name"] == "ix_terminals_branch_active"
    )
    assert active_index["column_names"] == ["branch_id", "is_active"]
    assert active_index["unique"] is False


@pytest.mark.integration
@pytest.mark.asyncio
async def test_concurrent_archive_waits_then_refuses_newly_opened_shift(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    owner = seed_owner["owner"]
    terminal = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name=f"Archive race {uuid4().hex[:8]}",
        purpose="gaming",
        is_active=True,
        device_id=f"archive-race-{uuid4()}",
    )
    session.add(terminal)
    await session.commit()

    tenant = TenantContext(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        roles=("owner",),
        protected_access=True,
    )
    archive_task: asyncio.Task | None = None
    opened_shift_id = None
    try:
        async with AsyncSessionLocal() as shift_session, AsyncSessionLocal() as settings_session:
            opened = await open_shift(
                ShiftOpenRequest(opening_float_minor=0),
                shift_session,
                tenant,
            )
            opened_shift_id = UUID(opened["id"])
            await shift_session.flush()

            archive_task = asyncio.create_task(
                update_terminal(
                    terminal.id,
                    TerminalUpdate(is_active=False),
                    settings_session,
                    tenant,
                )
            )
            await asyncio.sleep(0.05)
            assert not archive_task.done(), (
                "terminal archival must wait for shift-open's branch transaction"
            )

            await shift_session.commit()
            with pytest.raises(BusinessRuleError, match="Close or reconcile"):
                await asyncio.wait_for(archive_task, timeout=2)
            await settings_session.rollback()

        async with AsyncSessionLocal() as verify:
            persisted_terminal = await verify.get(Terminal, terminal.id)
            persisted_shift = await verify.get(Shift, opened_shift_id)
            assert persisted_terminal is not None
            assert persisted_terminal.is_active is True
            assert persisted_shift is not None
            assert persisted_shift.status == "open"
    finally:
        if archive_task is not None and not archive_task.done():
            archive_task.cancel()
            with suppress(asyncio.CancelledError):
                await archive_task
        async with AsyncSessionLocal() as cleanup:
            if opened_shift_id is not None:
                await cleanup.execute(
                    delete(Shift).where(Shift.id == opened_shift_id)
                )
            await cleanup.execute(delete(Terminal).where(Terminal.id == terminal.id))
            await cleanup.commit()
