"""PostgreSQL proof for the bounded client-installation capacity expansion."""

from __future__ import annotations

import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from ipaddress import ip_address
from pathlib import Path
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy.engine import URL, make_url

_BACKEND_ROOT = Path(__file__).resolve().parents[2]
_ADMIN_DATABASE_URL_ENV = "DCOMPANY_TEST_DATABASE_ADMIN_URL"
_LIBPQ_DESTINATION_ENV = ("PGHOST", "PGHOSTADDR", "PGSERVICE", "PGSERVICEFILE")


def _single_query_value(database_url: URL, key: str) -> str | None:
    value = database_url.query.get(key)
    if value is None:
        return None
    values = (value,) if isinstance(value, str) else tuple(value)
    if len(values) != 1 or not values[0] or "," in values[0]:
        raise AssertionError(f"database URL has ambiguous {key}")
    return values[0]


def _is_loopback_host(value: str) -> bool:
    if value.casefold() == "localhost":
        return True
    try:
        return ip_address(value).is_loopback
    except ValueError:
        return False


def _validate_local_database_url(database_url: URL, *, label: str) -> None:
    if database_url.get_backend_name() != "postgresql":
        raise AssertionError(f"{label} must identify PostgreSQL")
    if database_url.host is not None and not _is_loopback_host(database_url.host):
        raise AssertionError(f"{label} authority must be loopback")
    if "service" in database_url.query or "servicefile" in database_url.query:
        raise AssertionError(f"{label} cannot use a libpq service override")

    query_host = _single_query_value(database_url, "host")
    if database_url.host is None and query_host is None:
        raise AssertionError(f"{label} must have an explicit local destination")
    if query_host is not None and not (
        Path(query_host).is_absolute() or _is_loopback_host(query_host)
    ):
        raise AssertionError(f"{label} query host must be loopback or an absolute socket path")
    query_hostaddr = _single_query_value(database_url, "hostaddr")
    if query_hostaddr is not None and not _is_loopback_host(query_hostaddr):
        raise AssertionError(f"{label} query hostaddr must be loopback")


def _validate_no_ambient_libpq_destination() -> None:
    for name in _LIBPQ_DESTINATION_ENV:
        if os.environ.get(name):
            raise AssertionError(f"{name} cannot override the disposable database destination")


def _run_alembic(database_url: str, *args: str) -> subprocess.CompletedProcess[str]:
    _validate_no_ambient_libpq_destination()
    _validate_local_database_url(make_url(database_url), label="Alembic DATABASE_URL")
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
    _validate_no_ambient_libpq_destination()
    source_url = make_url(os.environ["DATABASE_URL"])
    _validate_local_database_url(source_url, label="DATABASE_URL")
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
    _validate_local_database_url(admin_url, label=_ADMIN_DATABASE_URL_ENV)
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


@pytest.mark.parametrize(
    "database_url",
    [
        "postgresql+psycopg://erp:erp@localhost/erp?host=remote.example",
        "postgresql+psycopg://erp:erp@localhost/erp?hostaddr=203.0.113.10",
        "postgresql+psycopg://erp:erp@localhost/erp?host=localhost,remote.example",
        "postgresql+psycopg://erp:erp@localhost/erp?host=localhost&host=%2Ftmp%2Fpg",
        "postgresql+psycopg://erp:erp@localhost/erp?service=remote-service",
        "postgresql+psycopg://erp:erp@/erp",
    ],
)
def test_disposable_database_guard_rejects_effective_remote_targets(database_url: str) -> None:
    with pytest.raises(AssertionError):
        _validate_local_database_url(make_url(database_url), label="test URL")


@pytest.mark.parametrize(
    "database_url",
    [
        "postgresql+psycopg://erp:erp@localhost/erp",
        "postgresql+psycopg://erp:erp@localhost/erp?host=%2Ftmp%2Fpostgres-socket",
        "postgresql+psycopg://erp:erp@127.0.0.1/erp?hostaddr=127.0.0.1",
        "postgresql+psycopg://erp:erp@[::1]/erp?hostaddr=::1",
    ],
)
def test_disposable_database_guard_accepts_only_local_targets(database_url: str) -> None:
    _validate_local_database_url(make_url(database_url), label="test URL")


@pytest.mark.parametrize("name", _LIBPQ_DESTINATION_ENV)
def test_disposable_database_guard_rejects_ambient_libpq_destination(
    monkeypatch: pytest.MonkeyPatch,
    name: str,
) -> None:
    monkeypatch.setenv(name, "remote.example")

    with pytest.raises(AssertionError, match=name):
        _validate_no_ambient_libpq_destination()


def test_alembic_guard_rejects_ambient_destination_before_subprocess(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PGHOST", "remote.example")
    monkeypatch.setattr(
        subprocess,
        "run",
        lambda *_args, **_kwargs: pytest.fail("subprocess must not start"),
    )

    with pytest.raises(AssertionError, match="PGHOST"):
        _run_alembic("postgresql+psycopg://erp:erp@localhost/erp", "current")


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
