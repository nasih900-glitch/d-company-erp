"""Versioned (v2) trial-cleanup receipts permanently fence deleted shift openings."""

from __future__ import annotations

from hashlib import sha256
from types import SimpleNamespace
from uuid import UUID

import pytest

from app.api.v1.pos.router import ShiftOpenRequest, open_shift
from app.core.cleanup_replay_fence import (
    VERSIONED_CLEANUP_ACTION,
    VERSIONED_CLEANUP_ENTITY_TYPE,
    refuse_versioned_shift_opening_replay,
)
from app.core.errors import BusinessRuleError, IdempotencyConflict
from app.core.tenant import TenantContext

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
TERMINAL_ID = UUID("33333333-3333-3333-3333-333333333333")
USER_ID = UUID("44444444-4444-4444-4444-444444444444")
OTHER_USER_ID = UUID("55555555-5555-5555-5555-555555555555")
DELETED_SHIFT_A = "aaaaaaaa-0000-4000-8000-000000000001"
DELETED_SHIFT_B = "aaaaaaaa-0000-4000-8000-000000000002"
KEY_A = "shift-open:10000000-0000-4000-8000-00000000000a"
KEY_B = "shift-open:10000000-0000-4000-8000-00000000000b"
HASH_A = "a" * 64
HASH_B = "b" * 64
CLEANUP_ID = "code30.3-trial-cleanup-20260923"


def _entry(key: str, request_hash: str, source: str) -> dict:
    return {
        "action_type": "shift_open",
        "action_key": key,
        "request_hash": request_hash,
        "user_id": str(USER_ID),
        "terminal_id": str(TERMINAL_ID),
        "source_entity_id": source,
    }


def _receipt(*, cleanup_id: str = CLEANUP_ID, fence: list[dict] | None = None, **overrides):
    row = SimpleNamespace(
        id=1,
        company_id=COMPANY_ID,
        action=VERSIONED_CLEANUP_ACTION,
        entity_type=VERSIONED_CLEANUP_ENTITY_TYPE,
        entity_id=cleanup_id,
        request_id=cleanup_id,
        client_action_id=None,
        client_was_offline=None,
        synced_at=None,
        actor_user_id=USER_ID,
        terminal_id=TERMINAL_ID,
        before={
            "schema_revision": "0082",
            "state_fingerprint": "c" * 64,
            "backup_sha256": "d" * 64,
        },
        after={
            "receipt_version": 2,
            "cleanup_id": cleanup_id,
            "source_git_sha": "e" * 40,
            "executor": "trial-cleanup-runner",
            "executed_at": "2026-09-23T10:00:00+00:00",
            "deleted_counts": {"shifts": 2, "orders": 3},
            "deleted_shift_ids": [DELETED_SHIFT_A, DELETED_SHIFT_B],
            "replay_fence": (
                fence
                if fence is not None
                else [_entry(KEY_A, HASH_A, DELETED_SHIFT_A), _entry(KEY_B, HASH_B, DELETED_SHIFT_B)]
            ),
            "evidence": {"retired_invoice_numbers": ["D/MN/26-27/00024"]},
        },
    )
    for name, value in overrides.items():
        setattr(row, name, value)
    return row


class _Result:
    def __init__(self, value=None) -> None:
        self.value = value

    def scalar_one_or_none(self):
        return self.value

    def scalars(self):
        return self

    def all(self):
        if self.value is None:
            return []
        if isinstance(self.value, list):
            return self.value
        return [self.value]


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


async def _refuse(session, *, key=KEY_A, request_hash=HASH_A, user_id=USER_ID):
    await refuse_versioned_shift_opening_replay(
        session,
        company_id=COMPANY_ID,
        action_key=key,
        request_hash=request_hash,
        user_id=user_id,
        terminal_id=TERMINAL_ID,
    )


@pytest.mark.asyncio
async def test_exact_retired_shift_opening_is_refused_with_v1_compatible_details() -> None:
    with pytest.raises(IdempotencyConflict, match="permanently retired") as raised:
        await _refuse(_Session([_receipt()]))

    assert raised.value.details == {
        "key": KEY_A,
        "issue": "retired_cleanup_action",
        "identity_matches_retired_action": True,
    }


@pytest.mark.asyncio
async def test_same_key_with_changed_identity_is_still_refused() -> None:
    with pytest.raises(IdempotencyConflict) as raised:
        await _refuse(_Session([_receipt()]), request_hash="f" * 64, user_id=OTHER_USER_ID)

    assert raised.value.details["identity_matches_retired_action"] is False


@pytest.mark.asyncio
async def test_unfenced_key_passes_when_receipt_is_valid() -> None:
    await _refuse(_Session([_receipt()]), key="shift-open:20000000-0000-4000-8000-000000000000")


@pytest.mark.asyncio
async def test_no_receipt_passes_and_query_is_scoped_to_company_and_v2_identity() -> None:
    session = _Session(None)

    await _refuse(session)

    statement = session.statements[0]
    compiled = str(statement)
    params = statement.compile().params
    assert "audit_log.company_id =" in compiled
    assert "audit_log.action =" in compiled and "audit_log.entity_type =" in compiled
    assert COMPANY_ID in params.values()
    assert VERSIONED_CLEANUP_ACTION in params.values()
    assert VERSIONED_CLEANUP_ENTITY_TYPE in params.values()


@pytest.mark.asyncio
async def test_empty_fence_is_valid_and_fences_nothing() -> None:
    receipt = _receipt(fence=[])
    receipt.after["deleted_shift_ids"] = [DELETED_SHIFT_A]
    receipt.after["deleted_counts"] = {"shifts": 1}

    await _refuse(_Session([receipt]))


@pytest.mark.asyncio
async def test_fences_from_several_valid_cleanups_are_all_enforced() -> None:
    first = _receipt(fence=[_entry(KEY_A, HASH_A, DELETED_SHIFT_A)])
    first.after["deleted_shift_ids"] = [DELETED_SHIFT_A]
    first.after["deleted_counts"] = {"shifts": 1}
    second_id = "code30.4-trial-cleanup-20261001"
    second = _receipt(cleanup_id=second_id, fence=[_entry(KEY_B, HASH_B, DELETED_SHIFT_B)])
    second.id = 2
    second.after["deleted_shift_ids"] = [DELETED_SHIFT_B]
    second.after["deleted_counts"] = {"shifts": 1}

    with pytest.raises(IdempotencyConflict):
        await _refuse(_Session([first, second]), key=KEY_B, request_hash=HASH_B)


def _mutations():
    def after(key, value):
        def apply(row):
            row.after[key] = value

        return apply

    def before(key, value):
        def apply(row):
            row.before[key] = value

        return apply

    def attr(name, value):
        def apply(row):
            setattr(row, name, value)

        return apply

    def fence(index, key, value):
        def apply(row):
            row.after["replay_fence"][index][key] = value

        return apply

    def drop_after(key):
        def apply(row):
            del row.after[key]

        return apply

    def reorder(row):
        row.after["replay_fence"].reverse()

    def duplicate_source(row):
        row.after["replay_fence"][1]["source_entity_id"] = DELETED_SHIFT_A

    return {
        "wrong action": attr("action", "production_trial_cleanup"),
        "wrong entity type": attr("entity_type", "ReleaseCleanup"),
        "reuses v1 entity id": attr("entity_id", "code30.1-20260920"),
        "request id differs": attr("request_id", "other-request"),
        "client action id set": attr("client_action_id", "x"),
        "offline flag set": attr("client_was_offline", False),
        "synced at set": attr("synced_at", "2026-09-23T10:00:00+00:00"),
        "no actor": attr("actor_user_id", None),
        "no terminal": attr("terminal_id", None),
        "entity id not canonical": attr("entity_id", "Bad ID"),
        "before not dict": attr("before", None),
        "before extra key": before("extra", 1),
        "schema revision malformed": before("schema_revision", "82"),
        "fingerprint malformed": before("state_fingerprint", "C" * 64),
        "after extra key": after("extra", 1),
        "after missing evidence": drop_after("evidence"),
        "version 1": after("receipt_version", 1),
        "version as bool": after("receipt_version", True),
        "version as string": after("receipt_version", "2"),
        "cleanup id mismatch": after("cleanup_id", "code30.3-trial-cleanup-20260924"),
        "git sha malformed": after("source_git_sha", "e" * 39),
        "executor empty": after("executor", ""),
        "executed at naive": after("executed_at", "2026-09-23T10:00:00"),
        "evidence not dict": after("evidence", []),
        "negative count": after("deleted_counts", {"shifts": 2, "orders": -1}),
        "shift count mismatch": after("deleted_counts", {"shifts": 3}),
        "shift ids unsorted": after("deleted_shift_ids", [DELETED_SHIFT_B, DELETED_SHIFT_A]),
        "shift ids empty": after("deleted_shift_ids", []),
        "shift id not canonical": after(
            "deleted_shift_ids", [DELETED_SHIFT_A.upper(), DELETED_SHIFT_B]
        ),
        "fence not list": after("replay_fence", {}),
        "fence unsorted": reorder,
        "fence action type": fence(0, "action_type", "idempotency"),
        "fence hash malformed": fence(0, "request_hash", "z" * 64),
        "fence user malformed": fence(0, "user_id", "not-a-uuid"),
        "fence terminal malformed": fence(0, "terminal_id", TERMINAL_ID.hex),
        "fence source not deleted": fence(0, "source_entity_id", str(BRANCH_ID)),
        "fence source reused": duplicate_source,
        "fence key padded": fence(0, "action_key", f" {KEY_A}"),
        "fence key empty": fence(0, "action_key", ""),
        "fence key too long": fence(0, "action_key", "k" * 161),
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("mutation", list(_mutations().values()), ids=list(_mutations()))
async def test_any_malformed_v2_receipt_blocks_keyed_shift_openings(mutation) -> None:
    receipt = _receipt()
    mutation(receipt)

    # Fail closed even for a key the receipt never mentioned.
    with pytest.raises(BusinessRuleError, match="malformed or duplicated"):
        await _refuse(_Session([receipt]), key="shift-open:unrelated")


@pytest.mark.asyncio
async def test_duplicated_cleanup_receipt_blocks_keyed_shift_openings() -> None:
    duplicate = _receipt()
    duplicate.id = 2

    with pytest.raises(BusinessRuleError, match="malformed or duplicated"):
        await _refuse(_Session([_receipt(), duplicate]), key="shift-open:unrelated")


@pytest.mark.asyncio
async def test_malformed_later_receipt_cannot_be_skipped_to_reach_a_valid_fence() -> None:
    malformed = _receipt(cleanup_id="code30.4-trial-cleanup-20261001")
    malformed.id = 2
    malformed.after["receipt_version"] = 3

    with pytest.raises(BusinessRuleError):
        await _refuse(_Session([_receipt(), malformed]))


def _shift_open_call(key: str, raw_body_hash: str):
    request = SimpleNamespace(
        state=SimpleNamespace(idempotency_key=key, idempotency_request_hash=raw_body_hash),
        headers={"X-Client-Platform": "web"},
    )
    branch = SimpleNamespace(id=BRANCH_ID, company_id=COMPANY_ID, name="Main", deleted_at=None)
    terminal = SimpleNamespace(
        id=TERMINAL_ID, branch_id=BRANCH_ID, name="Gaming", is_active=True, purpose="hybrid"
    )
    tenant = TenantContext(
        user_id=USER_ID,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        terminal_id=TERMINAL_ID,
        roles=("owner",),
        protected_access=True,
    )
    return request, branch, terminal, tenant


@pytest.mark.asyncio
async def test_open_shift_refuses_v2_retired_key_before_any_shift_lookup() -> None:
    raw_body_hash = "9" * 64
    bound_hash = sha256(f"{raw_body_hash}|False|server".encode()).hexdigest()
    request, branch, terminal, tenant = _shift_open_call(KEY_A, raw_body_hash)
    receipt = _receipt(
        fence=[_entry(KEY_A, bound_hash, DELETED_SHIFT_A), _entry(KEY_B, HASH_B, DELETED_SHIFT_B)]
    )
    recreated_shift = SimpleNamespace(id=UUID(DELETED_SHIFT_A), opening_action_id=KEY_A)
    # Results: branch, terminal, v1 receipts (none), v2 receipts, then a shift
    # row that must never be read because the v2 fence wins.
    session = _Session(branch, terminal, None, [receipt], recreated_shift)

    with pytest.raises(IdempotencyConflict, match="permanently retired") as raised:
        await open_shift(ShiftOpenRequest(), session, tenant, request)

    assert raised.value.details["identity_matches_retired_action"] is True
    assert session.added == []
    assert session.results == [recreated_shift]


@pytest.mark.asyncio
async def test_open_shift_with_malformed_v2_receipt_creates_nothing() -> None:
    request, branch, terminal, tenant = _shift_open_call(KEY_A, "8" * 64)
    receipt = _receipt()
    receipt.after["receipt_version"] = 1
    session = _Session(branch, terminal, None, [receipt])

    with pytest.raises(BusinessRuleError, match="malformed or duplicated"):
        await open_shift(ShiftOpenRequest(), session, tenant, request)

    assert session.added == []
    assert session.results == []
