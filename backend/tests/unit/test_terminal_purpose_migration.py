"""Schema contract for explicit terminal operational purposes."""

from __future__ import annotations

import importlib.util
from pathlib import Path

from app.models import Terminal


def _migration_module():
    path = (
        Path(__file__).resolve().parents[2]
        / "alembic"
        / "versions"
        / "0052_terminal_operational_purpose.py"
    )
    spec = importlib.util.spec_from_file_location("terminal_purpose_0052", path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, path.read_text(encoding="utf-8")


def test_terminal_purpose_migration_follows_current_head_and_is_reversible() -> None:
    migration, source = _migration_module()

    assert migration.revision == "0052"
    assert migration.down_revision == "0051"
    assert 'server_default="hybrid"' in source
    assert "ck_terminals_purpose" in source
    assert "op.drop_constraint" in source
    assert "op.drop_column" in source


def test_terminal_model_matches_the_database_constraint() -> None:
    purpose = Terminal.__table__.c.purpose
    assert purpose.nullable is False
    assert purpose.server_default is not None
    assert str(purpose.server_default.arg) == "hybrid"

    constraint = next(
        item
        for item in Terminal.__table__.constraints
        if item.name == "ck_terminals_purpose"
    )
    sql = str(constraint.sqltext)
    assert "hybrid" in sql
    assert "cafe_pos" in sql
    assert "gaming" in sql
