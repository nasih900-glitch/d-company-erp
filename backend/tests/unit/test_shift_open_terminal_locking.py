"""Shift-open locking and terminal revalidation contracts."""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError as PydanticValidationError

from app.api.v1.pos.router import ShiftOpenRequest, open_shift
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext
from app.models import Shift, User

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
TERMINAL_ID = UUID("33333333-3333-3333-3333-333333333333")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")


class _Result:
    def __init__(self, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar


class _QueuedSession:
    def __init__(self, results: list[_Result], *, entities=None) -> None:
        self.results = list(results)
        self.entities = {} if entities is None else entities
        self.statements = []
        self.added = []

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        self.statements.append(statement)
        return self.results.pop(0)

    def add(self, entity) -> None:
        self.added.append(entity)

    async def get(self, model, entity_id):
        return self.entities.get((model, entity_id))


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        roles=("owner",),
        protected_access=True,
    )


def _branch():
    return SimpleNamespace(
        id=BRANCH_ID,
        company_id=COMPANY_ID,
        name="Main Shop",
        deleted_at=None,
    )


def _terminal(*, active: bool = True, purpose: str = "hybrid"):
    return SimpleNamespace(
        id=TERMINAL_ID,
        branch_id=BRANCH_ID,
        name="Gaming Centre",
        is_active=active,
        purpose=purpose,
    )


def test_shift_open_schema_rejects_negative_but_preserves_zero() -> None:
    with pytest.raises(PydanticValidationError):
        ShiftOpenRequest(opening_float_minor=-1)

    assert ShiftOpenRequest(opening_float_minor=0).opening_float_minor == 0


@pytest.mark.asyncio
async def test_shift_open_locks_branch_then_terminal_and_revalidates_scope() -> None:
    session = _QueuedSession(
        [_Result(_branch()), _Result(_terminal()), _Result(None)]
    )

    result = await open_shift(ShiftOpenRequest(opening_float_minor=2500), session, _tenant())

    assert result["status"] == "open"
    assert len(session.added) == 1
    shift = session.added[0]
    assert isinstance(shift, Shift)
    assert shift.company_id == COMPANY_ID
    assert shift.branch_id == BRANCH_ID
    assert shift.terminal_id == TERMINAL_ID
    assert shift.opening_float_minor == 2500

    sql = [str(statement) for statement in session.statements]
    assert "FROM branches" in sql[0]
    assert "branches.company_id" in sql[0]
    assert "FOR UPDATE" in sql[0]
    assert "FROM terminals" in sql[1]
    assert "terminals.branch_id" in sql[1]
    assert "FOR UPDATE" in sql[1]
    assert "FROM shifts" in sql[2]
    assert "FOR UPDATE" in sql[2]


@pytest.mark.asyncio
async def test_shift_open_conflict_names_opener_workspace_and_next_action() -> None:
    opener_id = uuid4()
    opened_at = datetime(2026, 9, 4, 16, 30, tzinfo=UTC)
    existing = Shift(
        id=uuid4(),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        opened_by=opener_id,
        opened_at=opened_at,
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session = _QueuedSession(
        [_Result(_branch()), _Result(_terminal()), _Result(existing)],
        entities={
            (User, opener_id): SimpleNamespace(
                company_id=COMPANY_ID,
                name="Rafi",
            )
        },
    )

    with pytest.raises(
        BusinessRuleError,
        match=(
            "already open.*Rafi.*Gaming Centre.*Any staff member with Shift close "
            "access"
        ),
    ) as raised:
        await open_shift(ShiftOpenRequest(), session, _tenant())

    assert raised.value.details == {
        "issue": "shift_already_open_by_another_staff",
        "shift_id": str(existing.id),
        "shift_status": "open",
        "opened_by_name": "Rafi",
        "opened_at": opened_at.isoformat(),
        "opened_at_display": "04 Sep 2026 at 16:30 UTC",
        "branch_name": "Main Shop",
        "workspace_name": "Gaming Centre",
        "next_action": (
            "Use the existing shift. Any staff member with Shift close access can "
            "close it after all orders, gaming sessions and pending payments are "
            "resolved."
        ),
    }
    assert session.added == []


@pytest.mark.asyncio
async def test_shift_open_refuses_archived_workspace_before_shift_lookup() -> None:
    session = _QueuedSession([_Result(_branch()), _Result(_terminal(active=False))])

    with pytest.raises(BusinessRuleError, match="workspace has been archived"):
        await open_shift(ShiftOpenRequest(), session, _tenant())

    assert len(session.statements) == 2
    assert session.added == []


@pytest.mark.asyncio
async def test_shift_open_refuses_terminal_outside_authenticated_branch() -> None:
    session = _QueuedSession([_Result(_branch()), _Result(None)])

    with pytest.raises(BusinessRuleError, match="does not belong to this shop"):
        await open_shift(ShiftOpenRequest(), session, _tenant())

    assert len(session.statements) == 2
    assert session.added == []


@pytest.mark.asyncio
async def test_shift_open_refuses_legacy_split_purpose_with_clear_recovery() -> None:
    session = _QueuedSession(
        [_Result(_branch()), _Result(_terminal(purpose="gaming"))]
    )

    with pytest.raises(BusinessRuleError, match="one Hybrid workspace"):
        await open_shift(ShiftOpenRequest(), session, _tenant())

    assert len(session.statements) == 2
    assert session.added == []
