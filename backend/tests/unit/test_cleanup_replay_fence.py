"""Deleted Code30 trial actions remain permanently non-replayable."""

from __future__ import annotations

from hashlib import sha256
from types import SimpleNamespace
from uuid import UUID

import pytest

from app.api.v1.pos.router import ShiftOpenRequest, open_shift
from app.core.cleanup_replay_fence import refuse_retired_action_replay
from app.core.errors import IdempotencyConflict
from app.core.idempotency import check_or_reserve
from app.core.tenant import TenantContext

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
TERMINAL_ID = UUID("33333333-3333-3333-3333-333333333333")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")
SECOND_USER_ID = UUID("55555555-5555-5555-5555-555555555555")
SECOND_TERMINAL_ID = UUID("66666666-6666-6666-6666-666666666666")
ACTION_KEY = "gaming-session-start:retired-test-action"
SHIFT_ACTION_KEY = "shift-open:retired-test-action"
REQUEST_HASH = "a" * 64


def _receipt(*, action_key: str, request_hash: str = REQUEST_HASH) -> dict:
    return {
        "replay_fence": [
            {
                "action_type": "idempotency",
                "action_key": action_key,
                "request_hash": request_hash,
                "user_id": str(USER_ID),
                "terminal_id": str(TERMINAL_ID),
                "source_entity_id": None,
            }
        ]
    }


class _Result:
    def __init__(self, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        if self.scalar is None:
            return []
        if isinstance(self.scalar, list):
            return self.scalar
        return [self.scalar]


class _Session:
    def __init__(self, *results) -> None:
        self.results = list(results)
        self.added = []
        self.statements = []

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        self.statements.append(statement)
        return _Result(self.results.pop(0))

    def add(self, entity) -> None:
        self.added.append(entity)

    async def flush(self) -> None:
        return None


@pytest.mark.asyncio
async def test_replay_fence_rejects_exact_retired_identity() -> None:
    session = _Session(_receipt(action_key=ACTION_KEY))

    with pytest.raises(IdempotencyConflict) as raised:
        await refuse_retired_action_replay(
            session,
            action_key=ACTION_KEY,
            request_hash=REQUEST_HASH,
            user_id=USER_ID,
            terminal_id=TERMINAL_ID,
        )

    assert raised.value.details == {
        "key": ACTION_KEY,
        "issue": "retired_cleanup_action",
        "identity_matches_retired_action": True,
    }


@pytest.mark.asyncio
async def test_replay_fence_rejects_same_key_for_changed_identity_in_company() -> None:
    # Returning the receipt models a second authenticated user whose scalar
    # company lookup resolves to the same company as the cleanup receipt.
    session = _Session(_receipt(action_key=ACTION_KEY))

    with pytest.raises(IdempotencyConflict) as raised:
        await refuse_retired_action_replay(
            session,
            action_key=ACTION_KEY,
            request_hash="b" * 64,
            user_id=SECOND_USER_ID,
            terminal_id=SECOND_TERMINAL_ID,
        )

    assert raised.value.details["identity_matches_retired_action"] is False


@pytest.mark.asyncio
async def test_receipt_from_another_company_is_filtered_out() -> None:
    # ``None`` models the scalar result after the database applies the tenant
    # filter; the compiled statement proves that filter comes from the current
    # authenticated user's company rather than caller-controlled input.
    session = _Session(None)

    await refuse_retired_action_replay(
        session,
        action_key=ACTION_KEY,
        request_hash=REQUEST_HASH,
        user_id=USER_ID,
        terminal_id=TERMINAL_ID,
    )

    statement = str(session.statements[0])
    assert "audit_log.company_id = (SELECT users.company_id" in statement
    assert "users.id =" in statement


@pytest.mark.asyncio
async def test_malformed_newer_receipt_cannot_shadow_valid_replay_fence() -> None:
    session = _Session(
        [
            {"unexpected": "malformed duplicate receipt"},
            _receipt(action_key=ACTION_KEY),
        ]
    )

    with pytest.raises(IdempotencyConflict, match="permanently retired"):
        await refuse_retired_action_replay(
            session,
            action_key=ACTION_KEY,
            request_hash=REQUEST_HASH,
            user_id=USER_ID,
            terminal_id=TERMINAL_ID,
        )


@pytest.mark.asyncio
async def test_unauthenticated_action_does_not_query_cleanup_receipt() -> None:
    session = _Session()

    await refuse_retired_action_replay(
        session,
        action_key=ACTION_KEY,
        request_hash=REQUEST_HASH,
        user_id=None,
        terminal_id=TERMINAL_ID,
    )

    assert session.statements == []


@pytest.mark.asyncio
async def test_generic_idempotency_cannot_reserve_a_deleted_action_key() -> None:
    session = _Session(_receipt(action_key=ACTION_KEY))

    with pytest.raises(IdempotencyConflict, match="permanently retired"):
        await check_or_reserve(
            session,
            key=ACTION_KEY,
            request_hash=REQUEST_HASH,
            user_id=USER_ID,
            terminal_id=TERMINAL_ID,
        )

    assert session.added == []


@pytest.mark.asyncio
async def test_unrelated_new_idempotency_key_can_still_be_reserved() -> None:
    session = _Session(None)

    result = await check_or_reserve(
        session,
        key="ordinary-new-key",
        request_hash=REQUEST_HASH,
        user_id=USER_ID,
        terminal_id=TERMINAL_ID,
    )

    assert result is None
    assert len(session.added) == 1


@pytest.mark.asyncio
async def test_shift_open_cannot_recreate_a_deleted_opening_action() -> None:
    raw_body_hash = "c" * 64
    bound_hash = sha256(f"{raw_body_hash}|False|server".encode()).hexdigest()
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key=SHIFT_ACTION_KEY,
            idempotency_request_hash=raw_body_hash,
        ),
        headers={"X-Client-Platform": "web"},
    )
    branch = SimpleNamespace(
        id=BRANCH_ID,
        company_id=COMPANY_ID,
        name="Main Shop",
        deleted_at=None,
    )
    terminal = SimpleNamespace(
        id=TERMINAL_ID,
        branch_id=BRANCH_ID,
        name="Gaming Centre",
        is_active=True,
        purpose="hybrid",
    )
    session = _Session(
        branch,
        terminal,
        _receipt(action_key=SHIFT_ACTION_KEY, request_hash=bound_hash),
    )
    tenant = TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        roles=("owner",),
        protected_access=True,
    )

    with pytest.raises(IdempotencyConflict, match="permanently retired"):
        await open_shift(ShiftOpenRequest(), session, tenant, request)

    assert session.added == []
    # The durable replay fence is checked before any ordinary Shift lookup.
    assert session.results == []


@pytest.mark.asyncio
async def test_shift_open_checks_replay_fence_before_matching_shift_receipt() -> None:
    raw_body_hash = "d" * 64
    bound_hash = sha256(f"{raw_body_hash}|False|server".encode()).hexdigest()
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key=SHIFT_ACTION_KEY,
            idempotency_request_hash=raw_body_hash,
        ),
        headers={"X-Client-Platform": "web"},
    )
    branch = SimpleNamespace(
        id=BRANCH_ID,
        company_id=COMPANY_ID,
        name="Main Shop",
        deleted_at=None,
    )
    terminal = SimpleNamespace(
        id=TERMINAL_ID,
        branch_id=BRANCH_ID,
        name="Gaming Centre",
        is_active=True,
        purpose="hybrid",
    )
    matching_shift = SimpleNamespace(
        id=UUID("77777777-7777-7777-7777-777777777777"),
        opening_action_id=SHIFT_ACTION_KEY,
    )
    session = _Session(
        branch,
        terminal,
        _receipt(action_key=SHIFT_ACTION_KEY, request_hash=bound_hash),
        matching_shift,
    )
    tenant = TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        roles=("owner",),
        protected_access=True,
    )

    with pytest.raises(IdempotencyConflict, match="permanently retired"):
        await open_shift(ShiftOpenRequest(), session, tenant, request)

    # The matching shift result remains unused because the replay fence wins.
    assert session.results == [matching_shift]
