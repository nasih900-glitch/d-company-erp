"""Schema and maintenance-command safety contracts for terminal archival."""

from pathlib import Path
from uuid import UUID

import pytest

from app.models import AuditLog, Terminal
from scripts.merge_terminals_to_one import (
    _manifest_json,
    consolidate,
)

COMPANY_ID = UUID("11111111-1111-1111-1111-111111111111")
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
KEEPER_ID = UUID("33333333-3333-3333-3333-333333333333")
ARCHIVE_ID = UUID("44444444-4444-4444-4444-444444444444")
ACTOR_ID = UUID("55555555-5555-5555-5555-555555555555")


class _Result:
    def __init__(self, *, scalar=None, rows=None) -> None:
        self.scalar = scalar
        self.rows = list(rows or [])

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar

    def scalars(self):
        return self

    def all(self):
        return self.rows


class _QueuedSession:
    def __init__(self, results: list[_Result]) -> None:
        self.results = list(results)
        self.statements = []
        self.added = []
        self.flushes = 0

    async def execute(self, statement):
        assert self.results, f"Unexpected SQL statement: {statement}"
        self.statements.append(statement)
        return self.results.pop(0)

    async def flush(self) -> None:
        self.flushes += 1

    def add(self, entity) -> None:
        self.added.append(entity)


def _terminal(terminal_id: UUID, name: str, *, purpose: str = "gaming") -> Terminal:
    return Terminal(
        id=terminal_id,
        branch_id=BRANCH_ID,
        name=name,
        purpose=purpose,
        is_active=True,
    )


def _consolidation_session(
    keeper: Terminal,
    archived: Terminal,
    *,
    apply: bool = False,
    blocker_counts: tuple[int, ...] = (0,) * 9,
) -> _QueuedSession:
    results = [_Result(scalar=COMPANY_ID)]
    if apply:
        results.append(_Result(scalar=ACTOR_ID))
    results.extend(
        [
            _Result(scalar=BRANCH_ID),
            _Result(rows=[keeper, archived]),
            *[_Result(scalar=count) for count in blocker_counts],
        ]
    )
    return _QueuedSession(
        results
    )


def _archived_branch_consolidation_session(
    keeper: Terminal,
    archived: Terminal,
    *,
    apply: bool = False,
) -> _QueuedSession:
    archived_branch = type(
        "ArchivedBranch",
        (),
        {"id": BRANCH_ID, "deleted_at": object()},
    )()
    results = [_Result(scalar=COMPANY_ID)]
    if apply:
        results.append(_Result(scalar=ACTOR_ID))
    results.extend(
        [
            _Result(scalar=archived_branch),
            _Result(rows=[keeper, archived]),
            *[_Result(scalar=0) for _ in range(9)],
        ]
    )
    return _QueuedSession(results)


def test_terminal_archival_model_defaults_active() -> None:
    column = Terminal.__table__.c.is_active
    assert column.nullable is False
    assert column.server_default is not None
    assert str(column.server_default.arg).lower() == "true"


def test_terminal_archival_index_matches_migration_metadata() -> None:
    index = next(
        item
        for item in Terminal.__table__.indexes
        if item.name == "ix_terminals_branch_active"
    )
    assert [column.name for column in index.columns] == ["branch_id", "is_active"]
    assert index.unique is False


def test_terminal_archival_migration_chains_after_support_and_is_reversible() -> None:
    path = (
        Path(__file__).resolve().parents[2]
        / "alembic"
        / "versions"
        / "0054_terminal_archival.py"
    )
    source = path.read_text()
    assert 'revision = "0054"' in source
    assert 'down_revision = "0053"' in source
    assert 'op.add_column(' in source
    assert 'op.create_index(' in source
    assert '"ix_terminals_branch_active"' in source
    assert 'op.drop_index("ix_terminals_branch_active"' in source
    assert 'op.drop_column("terminals", "is_active")' in source


def test_consolidation_script_archives_instead_of_rewriting_or_deleting_history() -> None:
    path = Path(__file__).resolve().parents[2] / "scripts" / "merge_terminals_to_one.py"
    source = path.read_text()
    assert "terminal.is_active = False" in source
    assert "keeper.purpose = \"hybrid\"" in source
    assert 'parser.add_argument("--company-id", required=True' in source
    assert 'parser.add_argument("--branch-id", required=True' in source
    assert 'parser.add_argument("--keep-terminal-id", required=True' in source
    assert '"--backup-reference"' in source
    assert '"--actor-user-id"' in source
    assert '"--expected-state-fingerprint"' in source
    assert 'action="terminal_workspace_consolidation"' in source
    assert '"--apply"' in source
    assert ".with_for_update()" in source
    assert "session.delete" not in source
    assert ".terminal_id = keeper.id" not in source


@pytest.mark.asyncio
async def test_apply_refuses_before_database_access_without_release_evidence() -> None:
    session = _QueuedSession([])

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        apply=True,
    )

    assert result["result"] == "refused"
    assert result["errors"] == [
        "--reason is required with --apply",
        "--backup-reference is required with --apply",
        "--actor-user-id is required with --apply",
        "--expected-state-fingerprint must be a 64-character SHA-256 with --apply",
    ]
    assert session.statements == []


@pytest.mark.asyncio
async def test_dry_run_is_deterministic_and_never_mutates_terminals() -> None:
    keeper = _terminal(KEEPER_ID, "Gaming Area")
    archived = _terminal(ARCHIVE_ID, "Cafe POS", purpose="cafe_pos")
    session = _consolidation_session(keeper, archived)

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="Main Workspace",
    )

    assert result["result"] == "planned"
    assert result["mode"] == "dry_run"
    assert result["archive_terminals"] == [
        {"id": str(ARCHIVE_ID), "name": "Cafe POS"}
    ]
    assert result["blockers"] == {
        "unsettled_shifts": 0,
        "unfinished_orders": 0,
        "unacknowledged_kitchen_cancellations": 0,
        "running_gaming_sessions": 0,
        "unbilled_gaming_sessions": 0,
        "unresolved_membership_payments": 0,
        "unresolved_membership_refunds": 0,
        "unresolved_membership_refund_recoveries": 0,
        "unresolved_pos_refunds": 0,
    }
    assert keeper.name == "Gaming Area"
    assert keeper.purpose == "gaming"
    assert archived.is_active is True
    assert session.flushes == 0
    assert len(result["state_fingerprint"]) == 64
    assert _manifest_json(result) == _manifest_json(dict(reversed(result.items())))


@pytest.mark.asyncio
async def test_apply_archives_only_other_terminal_and_preserves_its_name() -> None:
    keeper = _terminal(KEEPER_ID, "Gaming Area")
    archived = _terminal(ARCHIVE_ID, "Cafe POS", purpose="cafe_pos")
    dry_run = await consolidate(
        _consolidation_session(keeper, archived),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="Main Workspace",
    )
    session = _consolidation_session(keeper, archived, apply=True)

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="Main Workspace",
        apply=True,
        actor_user_id=ACTOR_ID,
        reason="Approved one-workspace release",
        backup_reference="backup-2026-08-28T1900Z",
        expected_state_fingerprint=dry_run["state_fingerprint"],
    )

    assert result["result"] == "applied"
    assert keeper.name == "Main Workspace"
    assert keeper.purpose == "hybrid"
    assert keeper.is_active is True
    assert archived.name == "Cafe POS"
    assert archived.is_active is False
    assert session.flushes == 1
    audit = next(item for item in session.added if isinstance(item, AuditLog))
    assert audit.actor_user_id == ACTOR_ID
    assert audit.reason == "Approved one-workspace release"
    assert audit.before["state_fingerprint"] == dry_run["state_fingerprint"]
    assert audit.after["backup_reference"] == "backup-2026-08-28T1900Z"
    assert audit.after["archived_terminal_ids"] == [str(ARCHIVE_ID)]


@pytest.mark.asyncio
async def test_archived_branch_has_the_same_reviewed_consolidation_path() -> None:
    keeper = _terminal(KEEPER_ID, "Gaming Area")
    archived = _terminal(ARCHIVE_ID, "Cafe POS", purpose="cafe_pos")
    dry_run = await consolidate(
        _archived_branch_consolidation_session(keeper, archived),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="Historical Workspace",
    )
    session = _archived_branch_consolidation_session(
        keeper,
        archived,
        apply=True,
    )

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="Historical Workspace",
        apply=True,
        actor_user_id=ACTOR_ID,
        reason="Approved archived-shop schema reconciliation",
        backup_reference="backup-2026-08-29T0100Z",
        expected_state_fingerprint=dry_run["state_fingerprint"],
    )

    assert result["result"] == "applied"
    assert result["preserves_historical_references"] is True
    assert keeper.name == "Historical Workspace"
    assert keeper.purpose == "hybrid"
    assert keeper.is_active is True
    assert archived.name == "Cafe POS"
    assert archived.is_active is False
    assert session.flushes == 1


@pytest.mark.asyncio
async def test_archived_company_history_has_a_supported_dry_run_path() -> None:
    keeper = _terminal(KEEPER_ID, "Historical Gaming")
    archived = _terminal(ARCHIVE_ID, "Historical POS", purpose="cafe_pos")
    session = _archived_branch_consolidation_session(keeper, archived)

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="Historical Workspace",
    )

    assert result["result"] == "planned"
    assert "companies.deleted_at" not in str(session.statements[0])
    assert result["preserves_historical_references"] is True
    assert keeper.purpose == "gaming"
    assert archived.is_active is True
    assert session.flushes == 0


@pytest.mark.asyncio
async def test_keeper_rename_collision_refuses_without_mutation() -> None:
    keeper = _terminal(KEEPER_ID, "Cafe POS", purpose="cafe_pos")
    archived = _terminal(ARCHIVE_ID, "Gaming Area")
    dry_run = await consolidate(
        _consolidation_session(keeper, archived),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="gaming area",
    )
    session = _consolidation_session(keeper, archived, apply=True)

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        keep_name="gaming area",
        apply=True,
        actor_user_id=ACTOR_ID,
        reason="Approved one-workspace release",
        backup_reference="backup-2026-08-28T1900Z",
        expected_state_fingerprint=dry_run["state_fingerprint"],
    )

    assert result["result"] == "refused"
    assert "rename collides" in result["errors"][0]
    assert keeper.name == "Cafe POS"
    assert keeper.purpose == "cafe_pos"
    assert archived.name == "Gaming Area"
    assert archived.is_active is True
    assert session.flushes == 0


@pytest.mark.asyncio
async def test_any_operational_blocker_refuses_the_whole_apply() -> None:
    keeper = _terminal(KEEPER_ID, "Gaming Area")
    archived = _terminal(ARCHIVE_ID, "Cafe POS", purpose="cafe_pos")
    blockers = (1,) + (0,) * 8
    dry_run = await consolidate(
        _consolidation_session(keeper, archived, blocker_counts=blockers),
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
    )
    session = _consolidation_session(
        keeper,
        archived,
        apply=True,
        blocker_counts=blockers,
    )

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        apply=True,
        actor_user_id=ACTOR_ID,
        reason="Approved one-workspace release",
        backup_reference="backup-2026-08-28T1900Z",
        expected_state_fingerprint=dry_run["state_fingerprint"],
    )

    assert result["result"] == "refused"
    assert result["blockers"]["unsettled_shifts"] == 1
    assert archived.is_active is True
    assert session.flushes == 0


@pytest.mark.asyncio
async def test_apply_rejects_state_changed_after_review_without_mutation() -> None:
    keeper = _terminal(KEEPER_ID, "Gaming Area")
    archived = _terminal(ARCHIVE_ID, "Cafe POS", purpose="cafe_pos")
    session = _consolidation_session(keeper, archived, apply=True)

    result = await consolidate(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        keep_terminal_id=KEEPER_ID,
        apply=True,
        actor_user_id=ACTOR_ID,
        reason="Approved one-workspace release",
        backup_reference="backup-2026-08-28T1900Z",
        expected_state_fingerprint="0" * 64,
    )

    assert result["result"] == "refused"
    assert any("state fingerprint mismatch" in error for error in result["errors"])
    assert keeper.purpose == "gaming"
    assert archived.is_active is True
    assert session.added == []
    assert session.flushes == 0
