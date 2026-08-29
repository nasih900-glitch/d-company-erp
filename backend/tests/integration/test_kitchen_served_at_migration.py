"""PostgreSQL proof for exact KDS served-history timestamps."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


@pytest.mark.integration
def test_0042_backfills_served_evidence_and_enforces_timestamp_pair() -> None:
    with _disposable_database("erp_kitchen_served_0042") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            connection.execute("DELETE FROM orders WHERE id = %s", (ids["order_two"],))
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0041")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        explicit_served_at = datetime(2026, 8, 27, 18, 30, tzinfo=UTC)
        legacy_served_at = explicit_served_at - timedelta(minutes=5)
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "UPDATE order_lines SET kitchen_status = 'served', updated_at = %s WHERE id = %s",
                (explicit_served_at, ids["direct_paid_line"]),
            )
            connection.execute(
                "UPDATE orders SET kitchen_state = 'served', updated_at = %s WHERE id = %s",
                (legacy_served_at, ids["order_one"]),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0042")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        with psycopg.connect(dsn) as connection:
            rows = dict(
                connection.execute(
                    "SELECT id, kitchen_served_at FROM order_lines WHERE id = ANY(%s)",
                    ([ids["line"], ids["direct_paid_line"]],),
                ).fetchall()
            )
            assert rows[ids["line"]] == legacy_served_at
            assert rows[ids["direct_paid_line"]] == explicit_served_at
            assert connection.execute(
                "SELECT kitchen_status FROM order_lines WHERE id = %s",
                (ids["line"],),
            ).fetchone() == ("served",)
            assert connection.execute(
                "SELECT count(*) FROM pg_indexes "
                "WHERE indexname = 'ix_order_lines_kitchen_served_history'"
            ).fetchone() == (1,)

            with pytest.raises(psycopg.errors.CheckViolation):
                connection.execute(
                    "UPDATE order_lines SET kitchen_status = 'served' WHERE id = %s",
                    (ids["direct_open_line"],),
                )
            connection.rollback()

        downgraded = _run_alembic(database_url, "downgrade", "0041")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT count(*) FROM information_schema.columns "
                "WHERE table_name = 'order_lines' "
                "AND column_name = 'kitchen_served_at'"
            ).fetchone() == (0,)
