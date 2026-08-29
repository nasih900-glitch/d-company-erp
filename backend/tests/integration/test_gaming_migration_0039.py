"""PostgreSQL proof for the 0039 pre-release-0038 compatibility repair."""

from __future__ import annotations

import os
import subprocess
import sys
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy.engine import make_url

_BACKEND_ROOT = Path(__file__).resolve().parents[2]


def _run_alembic(database_url: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # noqa: S603 - fixed interpreter/module, test-owned args
        [sys.executable, "-m", "alembic", *args],
        cwd=_BACKEND_ROOT,
        env={**os.environ, "DATABASE_URL": database_url},
        capture_output=True,
        text=True,
        check=False,
    )


@contextmanager
def _disposable_database(prefix: str):
    source_url = make_url(
        os.environ.get(
            "DATABASE_URL",
            "postgresql+psycopg://erp:erp@localhost:5432/erp",
        )
    )
    if source_url.get_backend_name() != "postgresql" or source_url.host not in {
        "localhost",
        "127.0.0.1",
        "::1",
    }:
        pytest.skip("nested migration proof is restricted to local PostgreSQL")

    database_name = f"{prefix}_{uuid4().hex[:16]}"
    source_url = source_url.set(drivername="postgresql+psycopg")
    test_url = source_url.set(database=database_name)
    admin_dsn = source_url.set(
        drivername="postgresql",
        database="postgres",
    ).render_as_string(hide_password=False)
    try:
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database_name)))
    except Exception as exc:
        pytest.skip(f"local role cannot create a disposable database: {exc}")

    try:
        yield test_url.render_as_string(hide_password=False)
    finally:
        try:
            with psycopg.connect(admin_dsn, autocommit=True) as admin:
                admin.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    "WHERE datname = %s AND pid <> pg_backend_pid()",
                    (database_name,),
                )
                admin.execute(
                    sql.SQL("DROP DATABASE IF EXISTS {}").format(sql.Identifier(database_name))
                )
        except Exception:  # noqa: S110 - best-effort disposable DB cleanup
            pass


def _schema_state(connection: psycopg.Connection) -> tuple[object, ...] | None:
    return connection.execute(
        """
        SELECT attribute.attnum,
               attribute.attnotnull,
               default_value.oid,
               pg_get_expr(default_value.adbin, default_value.adrelid),
               constraint_row.oid,
               constraint_row.convalidated,
               pg_get_constraintdef(constraint_row.oid)
          FROM pg_catalog.pg_attribute AS attribute
          LEFT JOIN pg_catalog.pg_attrdef AS default_value
            ON default_value.adrelid = attribute.attrelid
           AND default_value.adnum = attribute.attnum
          LEFT JOIN pg_catalog.pg_constraint AS constraint_row
            ON constraint_row.conrelid = attribute.attrelid
           AND constraint_row.conname = 'ck_gaming_session_billing_mode'
           AND constraint_row.contype = 'c'
         WHERE attribute.attrelid = to_regclass('gaming_sessions')
           AND attribute.attname = 'billing_mode'
           AND attribute.attnum > 0
           AND NOT attribute.attisdropped
        """
    ).fetchone()


def _seed_legacy_0038_sessions(connection: psycopg.Connection) -> dict[str, UUID]:
    ids = {
        name: uuid4()
        for name in (
            "company",
            "branch",
            "terminal",
            "user",
            "shift",
            "station",
            "package",
            "linked_package",
            "active_locked",
            "ended_unbilled_locked",
            "open_hourly",
        )
    }
    now = datetime.now(UTC)
    connection.execute(
        "INSERT INTO companies (id, name) VALUES (%s, '0039 Compatibility')",
        (ids["company"],),
    )
    connection.execute(
        "INSERT INTO branches (id, company_id, name) VALUES (%s, %s, 'Main')",
        (ids["branch"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO terminals (id, branch_id, name, device_id) "
        "VALUES (%s, %s, 'Till 1', %s)",
        (ids["terminal"], ids["branch"], f"0039-{uuid4()}"),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-password', 'Owner')",
        (ids["user"], ids["company"], f"0039-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 'open')",
        (
            ids["shift"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["user"],
            now - timedelta(hours=2),
        ),
    )
    connection.execute(
        "INSERT INTO stations "
        "(id, company_id, branch_id, code, name, type, rate_per_hour_minor) "
        "VALUES (%s, %s, %s, 'PS5-39', 'PS5 39', 'ps5', 12000)",
        (ids["station"], ids["company"], ids["branch"]),
    )
    connection.execute(
        "INSERT INTO gaming_packages "
        "(id, company_id, branch_id, station_type, variant, kind, name, "
        "duration_minutes, price_minor) "
        "VALUES (%s, %s, %s, 'ps5', 'single', 'base', 'Single 60', 60, 10000)",
        (ids["package"], ids["company"], ids["branch"]),
    )

    sessions = (
        (ids["linked_package"], "ended", ids["package"], 10_000),
        (ids["active_locked"], "active", None, 8_000),
        (ids["ended_unbilled_locked"], "ended", None, 6_000),
        (ids["open_hourly"], "active", None, None),
    )
    for session_id, status, package_id, amount_minor in sessions:
        connection.execute(
            "INSERT INTO gaming_sessions "
            "(id, company_id, station_id, opened_by, shift_id, start_at, "
            "rate_per_hour_minor, package_id, amount_minor, status) "
            "VALUES (%s, %s, %s, %s, %s, %s, 12000, %s, %s, %s)",
            (
                session_id,
                ids["company"],
                ids["station"],
                ids["user"],
                ids["shift"],
                now - timedelta(minutes=30),
                package_id,
                amount_minor,
                status,
            ),
        )
    connection.commit()
    return ids


@pytest.mark.integration
def test_0039_is_a_schema_noop_for_complete_0038_and_downgrade_preserves_contract() -> None:
    with _disposable_database("erp_gaming_0039_complete") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0038")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)

        with psycopg.connect(dsn) as connection:
            before = _schema_state(connection)
            assert before is not None

        repaired = _run_alembic(database_url, "upgrade", "0039")
        assert repaired.returncode == 0, repaired.stdout + repaired.stderr
        with psycopg.connect(dsn) as connection:
            assert _schema_state(connection) == before
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0039",)

        downgraded = _run_alembic(database_url, "downgrade", "0038")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert _schema_state(connection) == before
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0038",)

        rerun = _run_alembic(database_url, "upgrade", "0039")
        assert rerun.returncode == 0, rerun.stdout + rerun.stderr
        with psycopg.connect(dsn) as connection:
            assert _schema_state(connection) == before


@pytest.mark.integration
def test_0039_repairs_stamped_legacy_0038_with_exact_conservative_backfill() -> None:
    with _disposable_database("erp_gaming_0039_legacy") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0038")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)

        with psycopg.connect(dsn) as connection:
            # Recreate the observed pre-release 0038 variant: the extension
            # ledger and every snapshot column exist, but billing_mode does not.
            connection.execute("ALTER TABLE gaming_sessions DROP COLUMN billing_mode")
            remaining_columns = {
                row[0]
                for row in connection.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema = current_schema() "
                    "AND table_name = 'gaming_sessions'"
                ).fetchall()
            }
            assert {
                "package_price_minor_snapshot",
                "package_duration_minutes_snapshot",
                "package_variant_snapshot",
                "package_station_type_snapshot",
            } <= remaining_columns
            assert connection.execute(
                "SELECT to_regclass('gaming_session_extensions')"
            ).fetchone()[0] == "gaming_session_extensions"
            connection.commit()
            ids = _seed_legacy_0038_sessions(connection)

        repaired = _run_alembic(database_url, "upgrade", "0039")
        assert repaired.returncode == 0, repaired.stdout + repaired.stderr
        with psycopg.connect(dsn) as connection:
            modes = dict(
                connection.execute(
                    "SELECT id, billing_mode FROM gaming_sessions"
                ).fetchall()
            )
            assert modes[ids["linked_package"]] == "package"
            assert modes[ids["active_locked"]] == "package"
            assert modes[ids["ended_unbilled_locked"]] == "legacy_ambiguous"
            assert modes[ids["open_hourly"]] == "hourly"

            state = _schema_state(connection)
            assert state is not None
            assert state[1] is True
            assert state[2] is not None
            assert state[3] is not None
            assert state[4] is not None
            assert state[5] is True
            assert state[6] is not None

            inserted_id = uuid4()
            connection.execute(
                "INSERT INTO gaming_sessions "
                "(id, company_id, station_id, opened_by, shift_id, start_at, "
                "rate_per_hour_minor, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, 12000, 'active')",
                (
                    inserted_id,
                    ids["company"],
                    ids["station"],
                    ids["user"],
                    ids["shift"],
                    datetime.now(UTC),
                ),
            )
            assert connection.execute(
                "SELECT billing_mode FROM gaming_sessions WHERE id = %s",
                (inserted_id,),
            ).fetchone() == ("hourly",)
            connection.commit()

            with pytest.raises(psycopg.errors.CheckViolation):
                connection.execute(
                    "UPDATE gaming_sessions SET billing_mode = 'invalid' WHERE id = %s",
                    (inserted_id,),
                )
            connection.rollback()
            assert connection.execute(
                "SELECT billing_mode FROM gaming_sessions WHERE id = %s",
                (inserted_id,),
            ).fetchone() == ("hourly",)

        downgraded = _run_alembic(database_url, "downgrade", "0038")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            preserved = _schema_state(connection)
            assert preserved is not None
            assert preserved[1] is True
            assert preserved[4] is not None

        rerun = _run_alembic(database_url, "upgrade", "0039")
        assert rerun.returncode == 0, rerun.stdout + rerun.stderr
