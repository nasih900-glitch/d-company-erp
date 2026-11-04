"""Actual0092 downgrade refuses committed or concurrently written policy history."""

import time
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic


def _actor(connection):
    company, actor = uuid4(), uuid4()
    connection.execute(
        "INSERT INTO companies(id,name) VALUES(%s,'Policy migration proof')", (company,)
    )
    connection.execute(
        "INSERT INTO users(id,company_id,email,name,password_hash,status) "
        "VALUES(%s,%s,%s,'Policy owner','unused','active')",
        (actor, company, f"policy-{actor}@synthetic.invalid"),
    )
    connection.commit()
    return actor


def _optional(connection, actor):
    connection.execute(
        "INSERT INTO android_update_policy(id,mode,revision,reason,updated_by) "
        "VALUES(1,'optional',6,'Synthetic owner relaxation',%s)",
        (actor,),
    )


def test_nonempty_optional_policy_cannot_be_discarded_on_downgrade():
    with _disposable_database("erp_policy_retention") as url:
        migration = _run_alembic(url, "upgrade", "0092")
        assert migration.returncode == 0, migration.stderr
        with psycopg.connect(
            url.replace("postgresql+psycopg://", "postgresql://", 1)
        ) as connection:
            _optional(connection, _actor(connection))
            connection.commit()
        result = _run_alembic(url, "downgrade", "0091")
        assert result.returncode != 0
        assert "cannot discard Android update policy" in result.stderr
        with psycopg.connect(
            url.replace("postgresql+psycopg://", "postgresql://", 1)
        ) as connection:
            assert connection.execute(
                "SELECT mode,revision FROM android_update_policy"
            ).fetchall() == [("optional", 6)]
            assert connection.execute("SELECT version_num FROM alembic_version").fetchone() == (
                "0092",
            )


@pytest.mark.parametrize("commit_writer", [True, False])
def test_policy_writer_finishes_before_downgrade_emptiness_decision(commit_writer):
    with _disposable_database("erp_policy_pending") as url:
        migration = _run_alembic(url, "upgrade", "0092")
        assert migration.returncode == 0, migration.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as writer, psycopg.connect(dsn, autocommit=True) as observer:
            _optional(writer, _actor(writer))
            with ThreadPoolExecutor(1) as pool:
                pending = pool.submit(_run_alembic, url, "downgrade", "0091")
                deadline, waiting = time.monotonic() + 10, False
                while time.monotonic() < deadline:
                    waiting = observer.execute(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity "
                        "WHERE datname=current_database() "
                        "AND wait_event_type='Lock' "
                        "AND query LIKE 'LOCK TABLE android_update_policy%%')"
                    ).fetchone()[0]
                    if waiting:
                        break
                    time.sleep(0.02)
                try:
                    assert waiting, "0092 must lock policy before deciding its history is empty"
                finally:
                    writer.commit() if commit_writer else writer.rollback()
                result = pending.result(timeout=10)
                if commit_writer:
                    assert result.returncode != 0
                    assert "cannot discard Android update policy" in result.stderr
                    assert observer.execute(
                        "SELECT mode,revision FROM android_update_policy"
                    ).fetchall() == [("optional", 6)]
                else:
                    assert result.returncode == 0, result.stderr
                    assert observer.execute(
                        "SELECT to_regclass('android_update_policy')"
                    ).fetchone() == (None,)
                    assert observer.execute(
                        "SELECT version_num FROM alembic_version"
                    ).fetchone() == ("0091",)
