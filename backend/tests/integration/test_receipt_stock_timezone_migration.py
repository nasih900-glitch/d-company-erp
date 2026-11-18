"""Keep exact stock restoration independent of the database session timezone."""

from __future__ import annotations

import psycopg
import pytest
from psycopg import sql

from tests.integration.test_build71_candidate_migration import _run_feature
from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic

pytestmark = pytest.mark.integration

_GUARDS = (
    "dcompany_check_receipt_stock_complete()",
    "dcompany_guard_receipt_stock_restoration()",
)


def _assert_guard_timezones(dsn: str, expected: set[str]) -> None:
    with psycopg.connect(dsn) as db:
        assert db.execute("SHOW timezone").fetchone() == ("Asia/Kolkata",)
        for guard in _GUARDS:
            row = db.execute(
                "SELECT proconfig FROM pg_proc WHERE oid = %s::regprocedure", (guard,)
            ).fetchone()
            assert row is not None
            assert {value.lower() for value in row[0] or ()} == expected


def test_0104_roundtrip_and_stock_api_under_non_utc_connection_default():
    with _disposable_database("erp_receipt_stock_timezone") as database_url:
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn, autocommit=True) as db:
            # A database-scoped role default covers the API's independent
            # connection pool, even when the role defaults to UTC elsewhere.
            db.execute(
                sql.SQL("ALTER ROLE CURRENT_USER IN DATABASE {} SET timezone TO 'Asia/Kolkata'")
                .format(sql.Identifier(db.info.dbname))
            )
        for action, revision, expected in (
            ("upgrade", "0103", set()),
            ("upgrade", "0104", {"timezone=utc"}),
            ("downgrade", "0103", set()),
            ("upgrade", "0104", {"timezone=utc"}),
        ):
            result = _run_alembic(database_url, action, revision)
            assert result.returncode == 0, result.stdout + result.stderr
            _assert_guard_timezones(dsn, expected)

        _run_feature(
            database_url,
            "tests/integration/test_pos_receipt_stock_corrections.py",
            "test_full_stock_restore_preserves_originals_and_replays_once",
        )
