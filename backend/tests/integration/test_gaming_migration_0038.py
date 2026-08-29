"""PostgreSQL proof for migration 0038's gaming billing-mode backfill."""

from __future__ import annotations

import os
import subprocess
import sys
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy.engine import make_url

_BACKEND_ROOT = Path(__file__).resolve().parents[2]


def _run_alembic(database_url: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
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
        except Exception:
            pass


@pytest.mark.integration
def test_0038_marks_deleted_catalog_history_ambiguous_instead_of_hourly() -> None:
    with _disposable_database("erp_gaming_0038") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0037")
        assert upgraded.returncode == 0, upgraded.stderr

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
                "ended_deleted_package",
                "active_deleted_package",
                "ended_open_hourly",
            )
        }
        now = datetime.now(UTC)
        sync_url = (
            make_url(database_url)
            .set(drivername="postgresql")
            .render_as_string(hide_password=False)
        )
        with psycopg.connect(sync_url) as connection:
            connection.execute(
                "INSERT INTO companies (id, name) VALUES (%s, '0038 Gaming')",
                (ids["company"],),
            )
            connection.execute(
                "INSERT INTO branches (id, company_id, name) VALUES (%s, %s, 'Main')",
                (ids["branch"], ids["company"]),
            )
            connection.execute(
                "INSERT INTO terminals (id, branch_id, name, device_id) "
                "VALUES (%s, %s, 'Till 1', %s)",
                (ids["terminal"], ids["branch"], f"0038-{uuid4()}"),
            )
            connection.execute(
                "INSERT INTO users (id, company_id, email, password_hash, name) "
                "VALUES (%s, %s, %s, 'not-a-password', 'Owner')",
                (ids["user"], ids["company"], f"0038-{uuid4()}@test.local"),
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
                "VALUES (%s, %s, %s, 'PS5-1', 'PS5 1', 'ps5', 12000)",
                (ids["station"], ids["company"], ids["branch"]),
            )
            connection.execute(
                "INSERT INTO gaming_packages "
                "(id, company_id, branch_id, station_type, variant, kind, name, "
                "duration_minutes, price_minor) "
                "VALUES (%s, %s, %s, 'ps5', 'single', 'base', 'Single 60', 60, 10000)",
                (ids["package"], ids["company"], ids["branch"]),
            )
            for session_id, status, end_at, billable_minutes, timer_minutes, amount_minor in (
                (
                    ids["ended_deleted_package"],
                    "ended",
                    now - timedelta(minutes=30),
                    60,
                    None,
                    10_000,
                ),
                (
                    ids["active_deleted_package"],
                    "active",
                    None,
                    None,
                    60,
                    10_000,
                ),
            ):
                connection.execute(
                    "INSERT INTO gaming_sessions "
                    "(id, company_id, station_id, opened_by, shift_id, start_at, end_at, "
                    "rate_per_hour_minor, package_id, timer_minutes, billable_minutes, "
                    "amount_minor, status) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, 12000, %s, %s, %s, %s, %s)",
                    (
                        session_id,
                        ids["company"],
                        ids["station"],
                        ids["user"],
                        ids["shift"],
                        now - timedelta(minutes=90),
                        end_at,
                        ids["package"],
                        timer_minutes,
                        billable_minutes,
                        amount_minor,
                        status,
                    ),
                )
            connection.execute(
                "INSERT INTO gaming_sessions "
                "(id, company_id, station_id, opened_by, shift_id, start_at, end_at, "
                "rate_per_hour_minor, package_id, timer_minutes, billable_minutes, "
                "amount_minor, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, 12000, NULL, NULL, 30, 6000, 'ended')",
                (
                    ids["ended_open_hourly"],
                    ids["company"],
                    ids["station"],
                    ids["user"],
                    ids["shift"],
                    now - timedelta(minutes=60),
                    now - timedelta(minutes=30),
                ),
            )
            connection.execute(
                "DELETE FROM gaming_packages WHERE id = %s",
                (ids["package"],),
            )
            assert connection.execute(
                "SELECT package_id FROM gaming_sessions WHERE id = %s",
                (ids["ended_deleted_package"],),
            ).fetchone() == (None,)
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0038")
        assert upgraded.returncode == 0, upgraded.stderr
        with psycopg.connect(sync_url) as connection:
            modes = dict(
                connection.execute("SELECT id, billing_mode FROM gaming_sessions").fetchall()
            )
        assert modes[ids["ended_deleted_package"]] == "legacy_ambiguous"
        assert modes[ids["active_deleted_package"]] == "package"
        # Pre-0038 unbilled ended rows with a deleted package FK are
        # indistinguishable from hourly history. Conservatively expose that
        # finite set as ambiguous so POS never grants an inferred free-minute
        # benefit. Already-order-linked history cannot be repriced.
        assert modes[ids["ended_open_hourly"]] == "legacy_ambiguous"

        refused = _run_alembic(database_url, "downgrade", "0037")
        assert refused.returncode != 0
        refusal_output = refused.stdout + refused.stderr
        assert "extension_receipts=0" in refusal_output
        assert "unresolved_legacy_ambiguous=2" in refusal_output
        assert "package_rows_without_catalog=1" in refusal_output


@pytest.mark.integration
def test_0038_empty_schema_can_round_trip_to_0037() -> None:
    with _disposable_database("erp_gaming_0038_empty") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0038")
        assert upgraded.returncode == 0, upgraded.stderr

        downgraded = _run_alembic(database_url, "downgrade", "0037")

        assert downgraded.returncode == 0, downgraded.stderr


@pytest.mark.integration
def test_0038_refuses_downgrade_that_would_destroy_extension_receipt() -> None:
    with _disposable_database("erp_gaming_0038_ledger") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0038")
        assert upgraded.returncode == 0, upgraded.stderr
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
                "session",
                "extension_receipt",
            )
        }
        now = datetime.now(UTC)
        sync_url = (
            make_url(database_url)
            .set(drivername="postgresql")
            .render_as_string(hide_password=False)
        )
        with psycopg.connect(sync_url) as connection:
            connection.execute(
                "INSERT INTO companies (id, name) VALUES (%s, '0038 Ledger')",
                (ids["company"],),
            )
            connection.execute(
                "INSERT INTO branches (id, company_id, name) VALUES (%s, %s, 'Main')",
                (ids["branch"], ids["company"]),
            )
            connection.execute(
                "INSERT INTO terminals (id, branch_id, name, device_id) "
                "VALUES (%s, %s, 'Till 1', %s)",
                (ids["terminal"], ids["branch"], f"0038-ledger-{uuid4()}"),
            )
            connection.execute(
                "INSERT INTO users (id, company_id, email, password_hash, name) "
                "VALUES (%s, %s, %s, 'not-a-password', 'Owner')",
                (ids["user"], ids["company"], f"0038-ledger-{uuid4()}@test.local"),
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
                    now - timedelta(hours=1),
                ),
            )
            connection.execute(
                "INSERT INTO stations "
                "(id, company_id, branch_id, code, name, type, rate_per_hour_minor) "
                "VALUES (%s, %s, %s, 'PS5-L', 'PS5 Ledger', 'ps5', 12000)",
                (ids["station"], ids["company"], ids["branch"]),
            )
            connection.execute(
                "INSERT INTO gaming_packages "
                "(id, company_id, branch_id, station_type, variant, kind, name, "
                "duration_minutes, price_minor) "
                "VALUES (%s, %s, %s, 'ps5', 'single', 'extension', "
                "'Add 15', 15, 5000)",
                (ids["package"], ids["company"], ids["branch"]),
            )
            connection.execute(
                "INSERT INTO gaming_sessions "
                "(id, company_id, station_id, opened_by, shift_id, start_at, "
                "rate_per_hour_minor, billing_mode, package_id, "
                "package_price_minor_snapshot, package_duration_minutes_snapshot, "
                "package_variant_snapshot, package_station_type_snapshot, "
                "timer_minutes, amount_minor, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, 12000, 'package', %s, "
                "10000, 60, 'single', 'ps5', 75, 15000, 'active')",
                (
                    ids["session"],
                    ids["company"],
                    ids["station"],
                    ids["user"],
                    ids["shift"],
                    now - timedelta(minutes=20),
                    ids["package"],
                ),
            )
            connection.execute(
                "INSERT INTO gaming_session_extensions "
                "(id, company_id, gaming_session_id, package_id, package_name, "
                "package_variant, station_type, duration_minutes, package_price_minor, "
                "controller_surcharge_minor, total_minor, timer_before_minutes, "
                "timer_after_minutes, amount_before_minor, amount_after_minor, "
                "idempotency_key, created_by) "
                "VALUES (%s, %s, %s, %s, 'Add 15', 'single', 'ps5', 15, 5000, "
                "0, 5000, 60, 75, 10000, 15000, %s, %s)",
                (
                    ids["extension_receipt"],
                    ids["company"],
                    ids["session"],
                    ids["package"],
                    f"migration-ledger:{uuid4()}",
                    ids["user"],
                ),
            )
            connection.commit()

        refused = _run_alembic(database_url, "downgrade", "0037")

        assert refused.returncode != 0
        refusal_output = refused.stdout + refused.stderr
        assert "extension_receipts=1" in refusal_output
        with psycopg.connect(sync_url) as connection:
            assert connection.execute(
                "SELECT count(*) FROM gaming_session_extensions"
            ).fetchone() == (1,)
            assert connection.execute("SELECT version_num FROM alembic_version").fetchone() == (
                "0038",
            )
