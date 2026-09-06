"""PostgreSQL proof for the 0063 shift opening-float constraint."""

from __future__ import annotations

from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


@pytest.mark.integration
def test_0063_refuses_invalid_history_then_enforces_and_round_trips() -> None:
    with _disposable_database("erp_shift_float_0063") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            # The shared 0036 fixture deliberately includes duplicate active
            # table bills for 0037's own refusal test. Keep one valid bill here
            # so this test reaches the independent 0063 boundary.
            connection.execute(
                "DELETE FROM orders WHERE id = %s",
                (ids["order_two"],),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0062")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "UPDATE shifts SET opening_float_minor = -1 WHERE id = %s",
                (ids["shift"],),
            )
            connection.commit()

        refused = _run_alembic(database_url, "upgrade", "0063")
        assert refused.returncode != 0
        assert "0063 found a shift with a negative opening float" in (
            refused.stdout + refused.stderr
        )
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0062",)
            connection.execute(
                "UPDATE shifts SET opening_float_minor = 0 WHERE id = %s",
                (ids["shift"],),
            )
            connection.commit()

        duplicate_shift_id = uuid4()
        corrupt_company_id = uuid4()
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "INSERT INTO companies (id, name, gst_registration_type) "
                "VALUES (%s, 'Corrupt cross-tenant shift owner', 'unregistered')",
                (corrupt_company_id,),
            )
            connection.execute(
                "INSERT INTO shifts "
                "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
                " opening_float_minor, expected_minor, status) "
                "SELECT %s, %s, branch_id, terminal_id, opened_by, opened_at, "
                "       0, 0, 'open' "
                "FROM shifts WHERE id = %s",
                (duplicate_shift_id, corrupt_company_id, ids["shift"]),
            )
            connection.commit()

        duplicate_refused = _run_alembic(database_url, "upgrade", "0063")
        assert duplicate_refused.returncode != 0
        assert "0063 found more than one open shift for a terminal" in (
            duplicate_refused.stdout + duplicate_refused.stderr
        )
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0062",)
            connection.execute(
                "DELETE FROM shifts WHERE id = %s",
                (duplicate_shift_id,),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0063")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(dsn) as connection:
            constraint = connection.execute(
                "SELECT convalidated FROM pg_constraint "
                "WHERE conname = 'ck_shifts_opening_float_nonnegative'"
            ).fetchone()
            assert constraint == (True,)
            index = connection.execute(
                "SELECT indexdef FROM pg_indexes "
                "WHERE indexname = 'uq_shifts_terminal_open'"
            ).fetchone()
            assert index is not None
            assert "UNIQUE INDEX" in index[0]
            assert "WHERE" in index[0]
            assert "'open'::text" in index[0]
            with pytest.raises(psycopg.errors.CheckViolation):
                connection.execute(
                    "UPDATE shifts SET opening_float_minor = -1 WHERE id = %s",
                    (ids["shift"],),
                )
            connection.rollback()
            with pytest.raises(psycopg.errors.UniqueViolation):
                connection.execute(
                    "INSERT INTO shifts "
                    "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
                    " opening_float_minor, expected_minor, status) "
                    "SELECT %s, %s, branch_id, terminal_id, opened_by, opened_at, "
                    "       0, 0, 'open' "
                    "FROM shifts WHERE id = %s",
                    (uuid4(), corrupt_company_id, ids["shift"]),
                )
            connection.rollback()

        downgraded = _run_alembic(database_url, "downgrade", "0062")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT count(*) FROM pg_constraint "
                "WHERE conname = 'ck_shifts_opening_float_nonnegative'"
            ).fetchone() == (0,)
            assert connection.execute(
                "SELECT count(*) FROM pg_indexes "
                "WHERE indexname = 'uq_shifts_terminal_open'"
            ).fetchone() == (0,)

        reupgraded = _run_alembic(database_url, "upgrade", "0063")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr
