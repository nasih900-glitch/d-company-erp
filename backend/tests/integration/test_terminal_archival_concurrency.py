"""PostgreSQL proof for the one-active-Hybrid-workspace constraints."""

from __future__ import annotations

from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import inspect, text
from sqlalchemy.exc import IntegrityError

from app.models import Branch, Terminal
from scripts.merge_terminals_to_one import consolidate


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

    one_active = next(
        index
        for index in indexes
        if index["name"] == "uq_terminals_one_active_per_branch"
    )
    assert one_active["column_names"] == ["branch_id"]
    assert one_active["unique"] is True
    assert "is_active IS TRUE" in str(one_active["dialect_options"])


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_refuses_a_second_active_workspace_without_touching_keeper(
    session,
    seed_owner,
) -> None:
    branch = seed_owner["branch"]
    keeper = seed_owner["terminal"]
    keeper_id = keeper.id
    second_id = uuid4()
    terminal = Terminal(
        id=second_id,
        branch_id=branch.id,
        name=f"Second workspace {uuid4().hex[:8]}",
        purpose="hybrid",
        is_active=True,
        device_id=f"second-workspace-{uuid4()}",
    )
    session.add(terminal)
    with pytest.raises(IntegrityError):
        await session.flush()
    await session.rollback()

    persisted_keeper = await session.get(Terminal, keeper_id)
    persisted_second = await session.get(Terminal, second_id)
    assert persisted_keeper is not None
    assert persisted_keeper.is_active is True
    assert persisted_keeper.purpose == "hybrid"
    assert persisted_second is None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_reviewed_consolidation_persists_audit_evidence_atomically(
    session,
    seed_owner,
) -> None:
    branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Historical branch {uuid4().hex[:8]}",
        invoice_series_code=f"{uuid4().hex[:2]}".upper(),
    )
    keeper = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Historical Gaming",
        purpose="gaming",
        is_active=False,
        device_id=f"historical-gaming-{uuid4()}",
    )
    retired = Terminal(
        id=uuid4(),
        branch_id=branch.id,
        name="Historical POS",
        purpose="cafe_pos",
        is_active=False,
        device_id=f"historical-pos-{uuid4()}",
    )
    session.add(branch)
    await session.flush()
    session.add_all([keeper, retired])
    await session.commit()

    dry_run = await consolidate(
        session,
        company_id=seed_owner["company"].id,
        branch_id=branch.id,
        keep_terminal_id=keeper.id,
        keep_name="Historical Workspace",
    )
    assert dry_run["result"] == "planned"

    applied = await consolidate(
        session,
        company_id=seed_owner["company"].id,
        branch_id=branch.id,
        keep_terminal_id=keeper.id,
        keep_name="Historical Workspace",
        apply=True,
        actor_user_id=seed_owner["owner"].id,
        reason="Reviewed migration integration proof",
        backup_reference="verified-backup:test-only",
        expected_state_fingerprint=dry_run["state_fingerprint"],
    )
    await session.commit()

    assert applied["result"] == "applied"
    await session.refresh(keeper)
    await session.refresh(retired)
    assert keeper.is_active is True
    assert keeper.purpose == "hybrid"
    assert keeper.name == "Historical Workspace"
    assert retired.is_active is False
    audit = (
        await session.execute(
            text(
                "SELECT actor_user_id, reason, before, after "
                "FROM audit_log "
                "WHERE company_id = :company_id "
                "AND action = 'terminal_workspace_consolidation' "
                "AND entity_id = :branch_id"
            ),
            {
                "company_id": seed_owner["company"].id,
                "branch_id": str(branch.id),
            },
        )
    ).mappings().one()
    assert audit["actor_user_id"] == seed_owner["owner"].id
    assert audit["reason"] == "Reviewed migration integration proof"
    assert audit["before"]["state_fingerprint"] == dry_run["state_fingerprint"]
    assert audit["after"]["backup_reference"] == "verified-backup:test-only"
