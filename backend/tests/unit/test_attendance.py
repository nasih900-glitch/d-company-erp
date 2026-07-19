"""Database-free tests for the clock-in / clock-out / on-shift roster flow.

Exercises the full lifecycle by calling the router functions directly against
a fake session (same pattern as test_inventory_deduction.py /
test_manual_collections.py): clock in -> appears in the on-shift list ->
clock out -> disappears from the on-shift list -> clocking out again without
a new clock-in is rejected with a clean 400 (never a 500).
"""

from __future__ import annotations

from uuid import UUID

import pytest
from fastapi import HTTPException

from app.api.v1.staff.router import ClockInRequest, clock_in, clock_out, list_on_shift
from app.core.tenant import TenantContext
from app.models import Attendance, Branch

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
USER_ID = UUID("33333333-3333-3333-3333-333333333333")


class _Result:
    def __init__(self, *, rows: list | None = None, scalar=None) -> None:
        self.rows = [] if rows is None else rows
        self.scalar = scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows

    def scalar_one_or_none(self):
        return self.scalar


class _Session:
    """Queued-result fake session — matches the order the endpoint issues
    statements in; `.get()` is hardcoded to the one branch lookup clock-in
    needs.
    """

    def __init__(self, *, branch: Branch) -> None:
        self.branch = branch
        self.results: list[_Result] = []
        self.added: list = []
        self.flushes = 0

    def queue(self, result: _Result) -> None:
        self.results.append(result)

    async def get(self, model, key):
        assert model is Branch and key == self.branch.id
        return self.branch

    async def execute(self, statement):
        assert self.results, f"unexpected statement: {statement}"
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        self.flushes += 1


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=None,
        roles=("owner",),
    )


@pytest.mark.asyncio
async def test_clock_in_out_lifecycle_and_repeat_clock_out_is_a_clean_400() -> None:
    branch = Branch(id=BRANCH_ID, company_id=COMPANY_ID, name="Main")
    session = _Session(branch=branch)
    tenant = _tenant()

    # --- clock in ---
    clock_in_result = await clock_in(
        ClockInRequest(branch_id=BRANCH_ID, notes="opening shift"),
        session,
        tenant,
    )
    assert len(session.added) == 1
    attendance: Attendance = session.added[0]
    assert attendance.id == UUID(clock_in_result["id"])
    assert attendance.company_id == COMPANY_ID
    assert attendance.user_id == USER_ID
    assert attendance.clock_out_at is None

    # --- appears in the on-shift list ---
    session.queue(_Result(rows=[(attendance, "Owner", "owner@test.local", "Main")]))
    on_shift = await list_on_shift(session, tenant)
    assert len(on_shift) == 1
    assert on_shift[0].id == attendance.id
    assert on_shift[0].user_name == "Owner"
    assert on_shift[0].branch_name == "Main"

    # --- clock out ---
    session.queue(_Result(scalar=attendance))
    clock_out_result = await clock_out(session, tenant)
    assert clock_out_result["id"] == str(attendance.id)
    assert attendance.clock_out_at is not None
    assert session.flushes == 1

    # --- disappears from the on-shift list ---
    session.queue(_Result(rows=[]))
    on_shift_after = await list_on_shift(session, tenant)
    assert on_shift_after == []

    # --- clocking out again without a new clock-in -> clean 400, not a 500 ---
    session.queue(_Result(scalar=None))
    with pytest.raises(HTTPException) as exc_info:
        await clock_out(session, tenant)
    assert exc_info.value.status_code == 400
    # No further mutation attempted once there is nothing open to close.
    assert session.flushes == 1
