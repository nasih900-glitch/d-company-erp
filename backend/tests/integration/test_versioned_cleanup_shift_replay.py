"""Real PostgreSQL proof that a v2 cleanup receipt fences deleted shift openings.

Synthetic data only. The second test also proves the v2 receipt stays invisible
to the Code30.2 post-cleanup installer gate, which requires exactly one receipt.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import psycopg
import pytest
from psycopg.types.json import Jsonb
from sqlalchemy import delete, func, select

from app.core.cleanup_replay_fence import (
    VERSIONED_CLEANUP_ACTION,
    VERSIONED_CLEANUP_ENTITY_TYPE,
)
from app.models import AuditLog, Shift
from tests.integration.test_captured_shift_opening import headers
from tests.integration.test_code30_post_cleanup_state_query import (
    _COMPANY_ID,
    _RECEIPT_ACTOR_ID,
    _TERMINAL_ID,
    _disposable_database,
    _require_production_owner,
    _run_alembic,
    _seed_state,
    _state,
)


def _receipt_after(cleanup_id: str, shifts: list[Shift]) -> dict:
    ordered = sorted(shifts, key=lambda shift: shift.opening_action_id)
    return {
        "receipt_version": 2,
        "cleanup_id": cleanup_id,
        "source_git_sha": "0" * 39 + "1",
        "executor": "synthetic-test",
        "executed_at": datetime.now(UTC).isoformat(),
        "deleted_counts": {"shifts": len(shifts)},
        "deleted_shift_ids": sorted(str(shift.id) for shift in shifts),
        "replay_fence": [
            {
                "action_type": "shift_open",
                "action_key": shift.opening_action_id,
                "request_hash": shift.opening_request_hash,
                "user_id": str(shift.opened_by),
                "terminal_id": str(shift.terminal_id),
                "source_entity_id": str(shift.id),
            }
            for shift in ordered
        ],
        "evidence": {"synthetic": True},
    }


def _receipt_before() -> dict:
    return {"schema_revision": "0082", "state_fingerprint": "1" * 64, "backup_sha256": "2" * 64}


async def _open(client, seed, captured, key):
    return await client.post(
        "/api/v1/pos/shifts/open",
        json={"opening_float_minor": 10_000},
        headers=headers(seed, captured, key=key),
    )


async def _shifts_with_key(session, key: str) -> int:
    return (
        await session.execute(
            select(func.count()).select_from(Shift).where(Shift.opening_action_id == key)
        )
    ).scalar_one()


@pytest.mark.asyncio
async def test_v2_receipt_stops_a_deleted_shift_opening_from_being_recreated(
    client, session, seed_owner
):
    company_id = seed_owner["company"].id
    owner_id = seed_owner["owner"].id
    terminal_id = seed_owner["terminal"].id

    # Control: deleting a keyed shift without a fence lets the tablet's exact
    # retry recreate it. This is the defect the v2 receipt closes.
    unfenced_key = f"shift-open:{uuid4()}"
    captured = datetime.now(UTC) - timedelta(minutes=20)
    first = await _open(client, seed_owner, captured, unfenced_key)
    assert first.status_code == 201, first.text
    await session.execute(delete(Shift).where(Shift.opening_action_id == unfenced_key))
    await session.commit()
    recreated = await _open(client, seed_owner, captured, unfenced_key)
    assert recreated.status_code == 201, recreated.text
    assert recreated.json()["id"] != first.json()["id"]
    await session.execute(delete(Shift).where(Shift.opening_action_id == unfenced_key))
    await session.commit()

    # Fenced: delete a real keyed shift and record the v2 receipt as the
    # cleanup would, in one transaction.
    fenced_key = f"shift-open:{uuid4()}"
    captured = datetime.now(UTC) - timedelta(minutes=10)
    opened = await _open(client, seed_owner, captured, fenced_key)
    assert opened.status_code == 201, opened.text
    shift = (
        await session.execute(select(Shift).where(Shift.opening_action_id == fenced_key))
    ).scalar_one()
    cleanup_id = f"synthetic-trial-cleanup-{uuid4().hex[:12]}"
    after = _receipt_after(cleanup_id, [shift])
    await session.execute(delete(Shift).where(Shift.id == shift.id))
    session.add(
        AuditLog(
            actor_user_id=owner_id,
            company_id=company_id,
            action=VERSIONED_CLEANUP_ACTION,
            entity_type=VERSIONED_CLEANUP_ENTITY_TYPE,
            entity_id=cleanup_id,
            before=_receipt_before(),
            after=after,
            terminal_id=terminal_id,
            request_id=cleanup_id,
        )
    )
    await session.commit()

    replay = await _open(client, seed_owner, captured, fenced_key)
    assert replay.status_code == 409, replay.text
    assert replay.json()["error"]["details"] == {
        "key": fenced_key,
        "issue": "retired_cleanup_action",
        "identity_matches_retired_action": True,
    }
    assert await _shifts_with_key(session, fenced_key) == 0

    # A different captured time is a different request hash for the same key;
    # the key alone is still enough to refuse it.
    altered = await _open(client, seed_owner, captured - timedelta(minutes=1), fenced_key)
    assert altered.status_code == 409, altered.text
    assert altered.json()["error"]["details"]["identity_matches_retired_action"] is False
    assert await _shifts_with_key(session, fenced_key) == 0

    # Unrelated new openings are unaffected by a valid receipt.
    fresh_key = f"shift-open:{uuid4()}"
    fresh = await _open(client, seed_owner, datetime.now(UTC) - timedelta(minutes=1), fresh_key)
    assert fresh.status_code == 201, fresh.text


@pytest.mark.asyncio
async def test_malformed_v2_receipt_blocks_keyed_openings_without_writing(
    client, session, seed_owner
):
    cleanup_id = f"synthetic-trial-cleanup-{uuid4().hex[:12]}"
    session.add(
        AuditLog(
            actor_user_id=seed_owner["owner"].id,
            company_id=seed_owner["company"].id,
            action=VERSIONED_CLEANUP_ACTION,
            entity_type=VERSIONED_CLEANUP_ENTITY_TYPE,
            entity_id=cleanup_id,
            before=_receipt_before(),
            after={"receipt_version": 2, "cleanup_id": cleanup_id},
            terminal_id=seed_owner["terminal"].id,
            request_id=cleanup_id,
        )
    )
    await session.commit()

    key = f"shift-open:{uuid4()}"
    response = await _open(client, seed_owner, datetime.now(UTC) - timedelta(minutes=1), key)
    assert response.status_code == 422, response.text
    assert "malformed or duplicated" in response.text
    assert await _shifts_with_key(session, key) == 0


@pytest.mark.integration
def test_v2_receipt_is_invisible_to_the_code30_2_installer_gate() -> None:
    with _disposable_database("erp_v2_receipt_gate") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "head")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            _require_production_owner(connection)
            _seed_state(connection)
            connection.commit()
            before = _state(connection)
            connection.rollback()

            cleanup_id = "code30.3-trial-cleanup-20260923"
            shift_id = str(uuid4())
            # A later production cleanup appends after the Code30.1 receipt;
            # the fixture's sequence is not advanced, so pin a realistic id.
            connection.execute(
                """
                INSERT INTO audit_log (
                    id, actor_user_id, company_id, action, entity_type, entity_id,
                    before, after, terminal_id, request_id, created_at
                ) VALUES (29000, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                """,
                (
                    _RECEIPT_ACTOR_ID,
                    _COMPANY_ID,
                    VERSIONED_CLEANUP_ACTION,
                    VERSIONED_CLEANUP_ENTITY_TYPE,
                    cleanup_id,
                    Jsonb(_receipt_before()),
                    Jsonb(
                        {
                            "receipt_version": 2,
                            "cleanup_id": cleanup_id,
                            "source_git_sha": "3" * 40,
                            "executor": "synthetic-test",
                            "executed_at": datetime.now(UTC).isoformat(),
                            "deleted_counts": {"shifts": 1},
                            "deleted_shift_ids": [shift_id],
                            "replay_fence": [
                                {
                                    "action_type": "shift_open",
                                    "action_key": f"shift-open:{uuid4()}",
                                    "request_hash": "4" * 64,
                                    "user_id": str(_RECEIPT_ACTOR_ID),
                                    "terminal_id": str(_TERMINAL_ID),
                                    "source_entity_id": shift_id,
                                }
                            ],
                            "evidence": {},
                        }
                    ),
                    _TERMINAL_ID,
                    cleanup_id,
                    datetime.now(UTC),
                ),
            )
            connection.commit()
            after = _state(connection)

            assert len(after["cleanup_receipts"]) == 1
            assert after["cleanup_receipts"][0]["entity_id"] == "code30.1-20260920"
            assert after == before
