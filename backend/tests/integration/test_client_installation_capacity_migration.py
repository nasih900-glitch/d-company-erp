"""PostgreSQL proof for the bounded client-installation capacity expansion."""

from __future__ import annotations

import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy.engine import make_url

_BACKEND_ROOT = Path(__file__).resolve().parents[2]
_ADMIN_DATABASE_URL_ENV = "DCOMPANY_TEST_DATABASE_ADMIN_URL"


def _run_alembic(database_url: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # noqa: S603 -- executable and migration arguments are test-owned.
        [sys.executable, "-m", "alembic", *args],
        cwd=_BACKEND_ROOT,
        env={**os.environ, "DATABASE_URL": database_url},
        capture_output=True,
        text=True,
        check=False,
    )


@contextmanager
def _disposable_database(prefix: str):
    source_url = make_url(os.environ["DATABASE_URL"])
    if source_url.get_backend_name() != "postgresql" or source_url.host not in {
        None,
        "localhost",
        "127.0.0.1",
        "::1",
    }:
        pytest.skip("capacity migration proof is restricted to local PostgreSQL")
    database_name = f"{prefix}_{uuid4().hex[:12]}"
    source_url = source_url.set(drivername="postgresql+psycopg")
    owner = source_url.username
    if not owner:
        raise AssertionError("DATABASE_URL must identify the disposable database owner")
    test_url = source_url.set(database=database_name)
    admin_url = make_url(
        os.environ.get(
            _ADMIN_DATABASE_URL_ENV,
            source_url.render_as_string(hide_password=False),
        )
    )
    if admin_url.get_backend_name() != "postgresql" or admin_url.host not in {
        None,
        "localhost",
        "127.0.0.1",
        "::1",
    }:
        raise AssertionError(f"{_ADMIN_DATABASE_URL_ENV} must identify local PostgreSQL")
    admin_dsn = admin_url.set(drivername="postgresql", database="postgres").render_as_string(
        hide_password=False
    )
    with psycopg.connect(admin_dsn, autocommit=True) as admin:
        admin.execute(
            sql.SQL("CREATE DATABASE {} OWNER {}").format(
                sql.Identifier(database_name), sql.Identifier(owner)
            )
        )
    try:
        yield test_url.render_as_string(hide_password=False)
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                "WHERE datname = %s AND pid <> pg_backend_pid()",
                (database_name,),
            )
            admin.execute(
                sql.SQL("DROP DATABASE IF EXISTS {}").format(sql.Identifier(database_name))
            )


def _sync_url(database_url: str) -> str:
    return make_url(database_url).set(drivername="postgresql").render_as_string(
        hide_password=False
    )


def _insert_installation(
    connection, company_id: UUID, user_id: UUID, *, pending: int = 0
) -> tuple[UUID, UUID]:
    row_id = uuid4()
    installation_id = uuid4()
    connection.execute(
        """
        INSERT INTO client_installations (
            id, installation_id, registered_by_user_id, last_user_id,
            platform, distribution_channel, version_name, version_code,
            pending_outbox_count, update_state, company_id
        ) VALUES (%s, %s, %s, %s, 'android', 'direct', '3.1.42', 50, %s, 'idle', %s)
        """,
        (row_id, installation_id, user_id, user_id, pending, company_id),
    )
    return row_id, installation_id


@pytest.mark.integration
def test_0089_preserves_eight_rows_and_enforces_user_16_company_32_on_both_directions() -> None:
    with _disposable_database("erp_build50_quota_qa") as database_url:
        predecessor = _run_alembic(database_url, "upgrade", "0088")
        assert predecessor.returncode == 0, predecessor.stderr
        dsn = _sync_url(database_url)
        company_id = uuid4()
        downgrade_company_id = uuid4()
        user_ids = [uuid4(), uuid4(), uuid4()]
        downgrade_user_id = uuid4()
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "INSERT INTO companies (id, name) VALUES (%s, 'Capacity QA')",
                (company_id,),
            )
            connection.execute(
                "INSERT INTO companies (id, name) VALUES (%s, 'Downgrade QA')",
                (downgrade_company_id,),
            )
            for index, user_id in enumerate(user_ids):
                connection.execute(
                    "INSERT INTO users (id, company_id, email, password_hash, name) "
                    "VALUES (%s, %s, %s, 'not-a-login-secret', %s)",
                    (user_id, company_id, f"capacity-{index}@test.local", f"Capacity {index}"),
                )
            connection.execute(
                "INSERT INTO users (id, company_id, email, password_hash, name) "
                "VALUES (%s, %s, 'downgrade@test.local', 'not-a-login-secret', 'Downgrade')",
                (downgrade_user_id, downgrade_company_id),
            )
            first_row_id, _ = _insert_installation(
                connection, company_id, user_ids[0], pending=2
            )
            for _ in range(7):
                _insert_installation(connection, company_id, user_ids[0])
            event_id = uuid4()
            connection.execute(
                """
                INSERT INTO client_update_events (
                    id, client_installation_id, client_event_id, actor_user_id,
                    event_type, target_version_name, target_version_code,
                    occurred_at, company_id
                ) VALUES (%s, %s, %s, %s, 'update_offered', '3.1.42', 50, now(), %s)
                """,
                (uuid4(), first_row_id, event_id, user_ids[0], company_id),
            )
            connection.commit()
            before = connection.execute(
                "SELECT count(*), sum(pending_outbox_count) FROM client_installations "
                "WHERE company_id = %s",
                (company_id,),
            ).fetchone()
            assert before == (8, 2)

        upgraded = _run_alembic(database_url, "upgrade", "0089")
        assert upgraded.returncode == 0, upgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT count(*), sum(pending_outbox_count) FROM client_installations "
                "WHERE company_id = %s",
                (company_id,),
            ).fetchone() == (8, 2)
            assert connection.execute(
                "SELECT count(*) FROM client_update_events WHERE client_event_id = %s",
                (event_id,),
            ).fetchone()[0] == 1

        def insert_for_first_user() -> bool:
            try:
                with psycopg.connect(dsn) as connection:
                    _insert_installation(connection, company_id, user_ids[0])
                    connection.commit()
                return True
            except psycopg.errors.CheckViolation:
                return False

        with ThreadPoolExecutor(max_workers=9) as executor:
            admitted = list(executor.map(lambda _index: insert_for_first_user(), range(9)))
        assert admitted.count(True) == 8
        assert admitted.count(False) == 1

        with psycopg.connect(dsn) as connection:
            for _ in range(15):
                _insert_installation(connection, company_id, user_ids[1])
            connection.commit()

        def insert_for_company(user_id: UUID) -> bool:
            try:
                with psycopg.connect(dsn) as connection:
                    _insert_installation(connection, company_id, user_id)
                    connection.commit()
                return True
            except psycopg.errors.CheckViolation:
                return False

        with ThreadPoolExecutor(max_workers=2) as executor:
            company_race = list(executor.map(insert_for_company, user_ids[1:3]))
        assert company_race.count(True) == 1
        assert company_race.count(False) == 1

        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT count(*) FROM client_installations WHERE company_id = %s",
                (company_id,),
            ).fetchone()[0] == 32
            downgrade_installation_id = None
            for _ in range(9):
                _, downgrade_installation_id = _insert_installation(
                    connection, downgrade_company_id, downgrade_user_id
                )
            connection.commit()

        downgraded = _run_alembic(database_url, "downgrade", "0088")
        assert downgraded.returncode == 0, downgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT count(*) FROM client_installations WHERE company_id = %s",
                (company_id,),
            ).fetchone()[0] == 32
            assert connection.execute(
                "SELECT count(*) FROM client_installations WHERE company_id = %s",
                (downgrade_company_id,),
            ).fetchone()[0] == 9
            with pytest.raises(psycopg.errors.CheckViolation, match="user capacity"):
                _insert_installation(connection, downgrade_company_id, downgrade_user_id)
            connection.rollback()
            connection.execute(
                """
                INSERT INTO client_installations (
                    id, installation_id, registered_by_user_id, last_user_id,
                    platform, distribution_channel, version_name, version_code,
                    pending_outbox_count, update_state, company_id
                ) VALUES (%s, %s, %s, %s, 'android', 'direct', '3.1.42', 50, 0, 'idle', %s)
                ON CONFLICT (company_id, installation_id) DO UPDATE
                SET last_seen_at = now()
                """,
                (
                    uuid4(), downgrade_installation_id, downgrade_user_id,
                    downgrade_user_id, downgrade_company_id,
                ),
            )
            connection.commit()
        reupgraded = _run_alembic(database_url, "upgrade", "0089")
        assert reupgraded.returncode == 0, reupgraded.stderr
