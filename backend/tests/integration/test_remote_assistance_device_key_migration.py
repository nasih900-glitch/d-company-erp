"""PostgreSQL upgrade/downgrade proof for remote-assistance device keys."""

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
    return subprocess.run(  # noqa: S603 - fixed interpreter/module; args are test constants
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
        except Exception:  # noqa: S110 - best-effort cleanup after assertion failures
            pass


@pytest.mark.integration
def test_0062_rejects_legacy_sync_command_then_clean_schema_round_trips() -> None:
    with _disposable_database("erp_remote_key_0062") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0061")
        assert upgraded.returncode == 0, upgraded.stderr

        ids = {
            name: uuid4()
            for name in (
                "company",
                "user",
                "installation",
                "branch",
                "terminal",
                "shift",
                "order",
                "grant",
                "session",
                "command",
            )
        }
        now = datetime.now(UTC)
        sync_url = (
            make_url(database_url)
            .set(drivername="postgresql")
            .render_as_string(hide_password=False)
        )
        with psycopg.connect(sync_url) as connection:
            # Recreate the historical pre-0062 command contract and inject the
            # command that must never be silently retained by the new code.
            connection.execute(
                "ALTER TABLE remote_assistance_commands "
                "DROP CONSTRAINT ck_remote_assistance_commands_type"
            )
            connection.execute(
                "ALTER TABLE remote_assistance_commands "
                "ADD CONSTRAINT ck_remote_assistance_commands_type "
                "CHECK (command_type IN "
                "('navigate', 'refresh', 'sync_now', 'collect_diagnostics'))"
            )
            connection.execute(
                "ALTER TABLE remote_assistance_commands "
                "DROP CONSTRAINT ck_remote_assistance_commands_module"
            )
            connection.execute(
                "ALTER TABLE remote_assistance_commands "
                "ADD CONSTRAINT ck_remote_assistance_commands_module "
                "CHECK (module IS NULL OR module IN "
                "('dashboard', 'gaming', 'pos', 'shift', 'help'))"
            )
            connection.execute(
                "INSERT INTO companies (id, name) VALUES (%s, '0062 Remote')",
                (ids["company"],),
            )
            connection.execute(
                "INSERT INTO users (id, company_id, email, password_hash, name) "
                "VALUES (%s, %s, %s, 'not-a-password', 'Owner')",
                (ids["user"], ids["company"], f"0062-{uuid4()}@test.local"),
            )
            connection.execute(
                "INSERT INTO branches (id, company_id, name, invoice_series_code) "
                "VALUES (%s, %s, 'Migration POS', 'MG')",
                (ids["branch"], ids["company"]),
            )
            connection.execute(
                "INSERT INTO terminals (id, branch_id, name, purpose, device_id) "
                "VALUES (%s, %s, 'Migration POS', 'hybrid', %s)",
                (ids["terminal"], ids["branch"], f"migration-{uuid4()}"),
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
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO orders "
                "(id, company_id, branch_id, terminal_id, shift_id, opened_by, type, "
                "status, opened_at, notes) "
                "VALUES (%s, %s, %s, %s, %s, %s, 'takeaway', 'open', %s, "
                "'pre-code17-pos')",
                (
                    ids["order"],
                    ids["company"],
                    ids["branch"],
                    ids["terminal"],
                    ids["shift"],
                    ids["user"],
                    now,
                ),
            )
            connection.execute(
                "INSERT INTO client_installations "
                "(id, installation_id, registered_by_user_id, last_user_id, platform, "
                "distribution_channel, version_name, version_code, pending_outbox_count, "
                "update_state, company_id) "
                "VALUES (%s, %s, %s, %s, 'android', 'direct', '3.1.6', 17, 0, "
                "'idle', %s)",
                (
                    ids["installation"],
                    uuid4(),
                    ids["user"],
                    ids["user"],
                    ids["company"],
                ),
            )
            connection.execute(
                "INSERT INTO remote_assistance_grants "
                "(id, client_installation_id, requested_by_user_id, kind, status, "
                "requested_at, expires_at, company_id) "
                "VALUES (%s, %s, %s, 'anytime', 'requested', %s, %s, %s)",
                (
                    ids["grant"],
                    ids["installation"],
                    ids["user"],
                    now,
                    now + timedelta(minutes=10),
                    ids["company"],
                ),
            )
            connection.execute(
                "INSERT INTO remote_assistance_sessions "
                "(id, grant_id, client_installation_id, requested_by_user_id, status, "
                "duration_seconds, requested_at, request_expires_at, company_id) "
                "VALUES (%s, %s, %s, %s, 'requested', 900, %s, %s, %s)",
                (
                    ids["session"],
                    ids["grant"],
                    ids["installation"],
                    ids["user"],
                    now,
                    now + timedelta(minutes=2),
                    ids["company"],
                ),
            )
            connection.execute(
                "INSERT INTO remote_assistance_commands "
                "(id, session_id, sequence, issued_by_user_id, command_type, status, "
                "issued_at, company_id) "
                "VALUES (%s, %s, 1, %s, 'sync_now', 'pending', %s, %s)",
                (ids["command"], ids["session"], ids["user"], now, ids["company"]),
            )
            connection.commit()

        refused = _run_alembic(database_url, "upgrade", "0062")
        assert refused.returncode != 0
        refusal_output = refused.stdout + refused.stderr
        assert "0062 found a command outside the closed semantic set" in refusal_output

        with psycopg.connect(sync_url) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0061",)
            assert connection.execute(
                "SELECT to_regclass('remote_assistance_device_keys')"
            ).fetchone() == (None,)
            # The retained command trigger intentionally prohibits DELETE;
            # TRUNCATE is used only inside this disposable migration fixture.
            connection.execute("TRUNCATE remote_assistance_commands")
            connection.execute(
                "INSERT INTO remote_assistance_commands "
                "(id, session_id, sequence, issued_by_user_id, command_type, module, "
                "status, issued_at, company_id) "
                "VALUES (%s, %s, 1, %s, 'navigate', 'gaming', 'pending', %s, %s)",
                (uuid4(), ids["session"], ids["user"], now, ids["company"]),
            )
            connection.commit()

        refused_navigation = _run_alembic(database_url, "upgrade", "0062")
        assert refused_navigation.returncode != 0
        navigation_output = refused_navigation.stdout + refused_navigation.stderr
        assert "0062 found navigation outside the Help-only boundary" in navigation_output

        with psycopg.connect(sync_url) as connection:
            connection.execute("TRUNCATE remote_assistance_commands")
            # Even recorded response evidence predates immutable target-user
            # binding. 0062 may retain it as history but must terminalize its
            # active authority, session, and pending command.
            connection.execute(
                "UPDATE remote_assistance_grants "
                "SET status = 'active', responded_at = %s, "
                "responded_by_user_id = %s, decision_id = %s WHERE id = %s",
                (now, ids["user"], uuid4(), ids["grant"]),
            )
            connection.execute(
                "UPDATE remote_assistance_sessions "
                "SET status = 'active', started_at = %s, expires_at = %s, "
                "started_by_user_id = %s, start_id = %s WHERE id = %s",
                (
                    now,
                    now + timedelta(minutes=10),
                    ids["user"],
                    uuid4(),
                    ids["session"],
                ),
            )
            connection.execute(
                "INSERT INTO remote_assistance_commands "
                "(id, session_id, sequence, issued_by_user_id, command_type, status, "
                "issued_at, company_id) "
                "VALUES (%s, %s, 1, %s, 'refresh', 'pending', %s, %s)",
                (ids["command"], ids["session"], ids["user"], now, ids["company"]),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0062")
        assert upgraded.returncode == 0, upgraded.stderr
        with psycopg.connect(sync_url) as connection:
            assert connection.execute(
                "SELECT requested_for_user_id, status FROM remote_assistance_grants "
                "WHERE id = %s",
                (ids["grant"],),
            ).fetchone() == (ids["user"], "expired")
            assert connection.execute(
                "SELECT status FROM remote_assistance_sessions WHERE id = %s",
                (ids["session"],),
            ).fetchone() == ("expired",)
            assert connection.execute(
                "SELECT status, resolved_by_user_id, rejection_reason_code "
                "FROM remote_assistance_commands WHERE id = %s",
                (ids["command"],),
            ).fetchone() == ("rejected", None, "session_ended")
        # Code16's immutable migration tree ends at 0060. Prove the exact
        # Code17 -> Code16 schema rollback and a persisted POS read/write path,
        # rather than stopping at Code17's own 0061 migration.
        downgraded = _run_alembic(database_url, "downgrade", "0060")
        assert downgraded.returncode == 0, downgraded.stderr
        with psycopg.connect(sync_url) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0060",)
            assert connection.execute(
                "SELECT to_regclass('remote_assistance_grants'), "
                "to_regclass('remote_assistance_sessions'), "
                "to_regclass('remote_assistance_commands'), "
                "to_regclass('remote_assistance_device_keys')"
            ).fetchone() == (None, None, None, None)
            connection.execute(
                "UPDATE orders SET notes = 'post-code16-rollback-pos' WHERE id = %s",
                (ids["order"],),
            )
            connection.commit()
            assert connection.execute(
                "SELECT notes FROM orders WHERE id = %s",
                (ids["order"],),
            ).fetchone() == ("post-code16-rollback-pos",)
        reupgraded = _run_alembic(database_url, "upgrade", "0062")
        assert reupgraded.returncode == 0, reupgraded.stderr

        with psycopg.connect(sync_url) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0062",)
            assert connection.execute(
                "SELECT bool_and(convalidated) FROM pg_constraint "
                "WHERE conname IN "
                "('ck_remote_assistance_commands_type', "
                "'ck_remote_assistance_commands_module', "
                "'ck_remote_assistance_grants_consent_user_binding')"
            ).fetchone() == (True,)
            assert connection.execute(
                "SELECT idx.indisunique AND idx.indpred IS NOT NULL "
                "FROM pg_index AS idx "
                "JOIN pg_class AS relation ON relation.oid = idx.indexrelid "
                "WHERE relation.relname = 'uq_remote_assistance_commands_session_pending'"
            ).fetchone() == (True,)
