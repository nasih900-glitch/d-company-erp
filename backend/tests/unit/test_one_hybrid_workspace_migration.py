"""Migration 0056 must refuse unsafe topology instead of mutating it."""

from __future__ import annotations

import importlib.util
from pathlib import Path
from typing import TYPE_CHECKING
from uuid import UUID

import pytest

from app.models import Terminal

if TYPE_CHECKING:
    from types import ModuleType


def _load_migration() -> ModuleType:
    path = (
        Path(__file__).resolve().parents[2]
        / "alembic"
        / "versions"
        / "0056_one_hybrid_workspace.py"
    )
    spec = importlib.util.spec_from_file_location("one_hybrid_workspace_0056", path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


MIGRATION = _load_migration()
BRANCH_ID = UUID("22222222-2222-2222-2222-222222222222")
TERMINAL_ID = UUID("33333333-3333-3333-3333-333333333333")


class _Rows:
    def __init__(self, rows: list[dict[str, object]]) -> None:
        self.rows = rows

    def mappings(self):
        return self

    def all(self) -> list[dict[str, object]]:
        return self.rows


class _Bind:
    def __init__(self, *result_sets: list[dict[str, object]]) -> None:
        self.result_sets = list(result_sets)
        self.statements: list[str] = []

    def execute(self, statement):
        assert self.result_sets, f"Unexpected SQL statement: {statement}"
        self.statements.append(str(statement))
        return _Rows(self.result_sets.pop(0))


def test_model_enforces_at_most_one_active_hybrid_workspace() -> None:
    indexes = {index.name: index for index in Terminal.__table__.indexes}
    one_active = indexes["uq_terminals_one_active_per_branch"]
    assert one_active.unique is True
    assert [column.name for column in one_active.columns] == ["branch_id"]
    assert "is_active IS TRUE" in str(
        one_active.dialect_options["postgresql"]["where"]
    )
    assert "is_active = 1" in str(one_active.dialect_options["sqlite"]["where"])

    checks = {
        constraint.name: str(constraint.sqltext)
        for constraint in Terminal.__table__.constraints
        if getattr(constraint, "sqltext", None) is not None
    }
    assert checks["ck_terminals_active_requires_hybrid"] == (
        "is_active IS FALSE OR purpose = 'hybrid'"
    )


def test_migration_chains_after_gaming_addons_and_is_reversible() -> None:
    path = Path(MIGRATION.__file__)
    source = path.read_text()
    assert MIGRATION.revision == "0056"
    assert MIGRATION.down_revision == "0055"
    assert "_assert_one_hybrid_workspace_ready(op.get_bind())" in source
    assert '"uq_terminals_one_active_per_branch"' in source
    assert 'postgresql_where=sa.text("is_active IS TRUE")' in source
    assert 'sqlite_where=sa.text("is_active = 1")' in source
    assert '"ck_terminals_active_requires_hybrid"' in source
    assert "UPDATE terminals" not in source
    assert "DELETE FROM terminals" not in source


@pytest.mark.parametrize("active_count", [0, 2, 5])
def test_preflight_refuses_zero_or_multiple_active_workspaces(
    active_count: int,
) -> None:
    bind = _Bind(
        [
            {
                "branch_id": BRANCH_ID,
                "branch_status": "active",
                "active_count": active_count,
            }
        ],
        [],
    )

    with pytest.raises(RuntimeError, match="No terminal was archived or repointed") as exc:
        MIGRATION._assert_one_hybrid_workspace_ready(bind)

    assert f"active_count={active_count}" in str(exc.value)
    assert "scripts.merge_terminals_to_one" in str(exc.value)
    assert len(bind.statements) == 2


def test_preflight_refuses_multiple_active_workspaces_on_archived_branch() -> None:
    bind = _Bind(
        [
            {
                "branch_id": BRANCH_ID,
                "branch_status": "archived",
                "active_count": 2,
            }
        ],
        [],
    )

    with pytest.raises(RuntimeError, match="archived shop") as exc:
        MIGRATION._assert_one_hybrid_workspace_ready(bind)

    assert "branch_status=archived" in str(exc.value)
    assert "active_count=2" in str(exc.value)


def test_preflight_refuses_a_single_active_non_hybrid_workspace() -> None:
    bind = _Bind(
        [],
        [
            {
                "terminal_id": TERMINAL_ID,
                "branch_id": BRANCH_ID,
                "purpose": "gaming",
            }
        ],
    )

    with pytest.raises(RuntimeError, match="active non-Hybrid workspaces") as exc:
        MIGRATION._assert_one_hybrid_workspace_ready(bind)

    assert f"terminal_id={TERMINAL_ID}" in str(exc.value)
    assert "purpose=gaming" in str(exc.value)


def test_preflight_accepts_exactly_one_active_hybrid_workspace() -> None:
    bind = _Bind([], [])

    MIGRATION._assert_one_hybrid_workspace_ready(bind)

    assert len(bind.statements) == 2


def test_preflight_accepts_archived_branch_with_zero_or_one_hybrid_workspace() -> None:
    # The SQL query returns only violations. An archived branch with zero or
    # one active Hybrid workspace therefore produces no rows here.
    bind = _Bind([], [])

    MIGRATION._assert_one_hybrid_workspace_ready(bind)

    statement = bind.statements[0]
    assert "b.deleted_at IS NOT NULL AND COUNT(t.id) > 1" in statement
