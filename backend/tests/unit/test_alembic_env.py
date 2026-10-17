"""Alembic environment configuration regressions."""

from __future__ import annotations

import runpy
from contextlib import nullcontext
from pathlib import Path

from alembic.config import Config

from alembic import context


def test_percent_encoded_database_url_reaches_alembic_unchanged(monkeypatch) -> None:
    database_url = (
        "postgresql+asyncpg://erp:encoded%25password@localhost/erp_qa"
        "?host=%2Ftmp%2Fdcompany-qa-pg-sock"
    )
    expected_sync_url = database_url.replace("+asyncpg", "+psycopg")
    configured: dict[str, object] = {}

    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setattr(context, "config", Config(), raising=False)
    monkeypatch.setattr(context, "is_offline_mode", lambda: True)
    monkeypatch.setattr(
        context,
        "configure",
        lambda **kwargs: configured.update(kwargs),
    )
    monkeypatch.setattr(context, "begin_transaction", nullcontext)
    monkeypatch.setattr(context, "run_migrations", lambda: None)

    runpy.run_path(
        str(Path(__file__).resolve().parents[2] / "alembic" / "env.py"),
        run_name="__alembic_env_test__",
    )

    assert configured["url"] == expected_sync_url
