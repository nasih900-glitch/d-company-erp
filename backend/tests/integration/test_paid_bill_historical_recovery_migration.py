"""0102 revocable credentials preserve0101 outcomes and refuse live-state downgrade."""

from __future__ import annotations

import os
import time
from uuid import uuid4

import psycopg
import pytest
from sqlalchemy.engine import make_url

from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic

pytestmark = pytest.mark.integration


def safe_database():
    url = make_url(os.environ["DATABASE_URL"])
    assert os.environ.get("ENV") == "test"
    assert url.host in {"127.0.0.1", "localhost", "::1"}
    assert url.database.startswith(("erp_paid_historical_recovery", "erp_test"))
    return _disposable_database("erp_paid_historical_recovery_migration")


def assert_migrated(url, *args):
    result = _run_alembic(url, *args)
    assert result.returncode == 0, result.stdout + result.stderr


def add_metadata(conn, lifetime=300):
    # No signed token is created by this raw schema guard fixture.
    row = {name: uuid4() for name in ["id", "company", "user", "branch", "terminal", "order"]}
    conn.execute(
        """INSERT INTO paid_bill_recovery_sessions
     (id,company_id,user_id,branch_id,terminal_id,order_id,purpose,request_path,
      action_key_hash,command_hash,token_hash,auth_version,issued_at,expires_at)
     VALUES(%(id)s,%(company)s,%(user)s,%(branch)s,%(terminal)s,%(order)s,
      'paid_bill_action_recovery','/api/v1/pos/paid-bill-amendments/commit',
      repeat('a',64),repeat('b',64),%(token_hash)s,0,statement_timestamp(),
      statement_timestamp()+make_interval(secs=>%(lifetime)s))""",
        {**row, "lifetime": lifetime, "token_hash": uuid4().hex + uuid4().hex},
    )
    conn.execute(
        """INSERT INTO audit_log(company_id,actor_user_id,action,entity_type,entity_id,after)
     VALUES(%(company)s,%(user)s,'paid_bill_recovery_issued','PaidBillRecoverySession',
      %(id)s,'{"purpose":"paid_bill_action_recovery"}'::jsonb)""",
        {**row, "id": str(row["id"])},
    )
    return row


def test_empty_upgrade_live_refusal_and_revoked_downgrade_preserve_prior_tables():
    with safe_database() as url:
        assert_migrated(url, "upgrade", "0101")
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            before = conn.execute("SELECT count(*) FROM paid_bill_action_decisions").fetchone()
        assert_migrated(url, "upgrade", "0102")
        assert_migrated(url, "downgrade", "0101")
        assert_migrated(url, "upgrade", "0102")
        with psycopg.connect(dsn) as conn:
            row = add_metadata(conn)
        failed = _run_alembic(url, "downgrade", "0101")
        assert failed.returncode != 0
        assert "Active recovery credentials prevent downgrade" in failed.stdout + failed.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute("SELECT version_num FROM alembic_version").fetchone() == ("0102",)
            assert (
                conn.execute("SELECT count(*) FROM paid_bill_action_decisions").fetchone() == before
            )
            assert conn.execute(
                "SELECT count(*) FROM pg_constraint "
                "WHERE conrelid='paid_bill_recovery_sessions'::regclass AND contype='f'"
            ).fetchone() == (0,)
            conn.execute(
                "UPDATE paid_bill_recovery_sessions SET revoked_at=statement_timestamp(),"
                "revocation_reason='locked' WHERE id=%s",
                (row["id"],),
            )
            conn.execute(
                """INSERT INTO audit_log
             (company_id,actor_user_id,action,entity_type,entity_id,after)
             VALUES(%(company)s,%(user)s,'paid_bill_recovery_locked','PaidBillRecoverySession',
              %(id)s,'{"purpose":"paid_bill_action_recovery"}'::jsonb)""",
                {**row, "id": str(row["id"])},
            )
        with (
            psycopg.connect(dsn) as conn,
            pytest.raises(psycopg.errors.CheckViolation, match="revocation cannot reset"),
        ):
            conn.execute(
                "UPDATE paid_bill_recovery_sessions "
                "SET revoked_at=NULL,revocation_reason=NULL WHERE id=%s",
                (row["id"],),
            )
        assert_migrated(url, "downgrade", "0101")
        with psycopg.connect(dsn) as conn:
            assert (
                conn.execute("SELECT count(*) FROM paid_bill_action_decisions").fetchone() == before
            )


def test_expired_only_cleanup_preserves_decision_registry():
    with safe_database() as url:
        assert_migrated(url, "upgrade", "0102")
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            row = add_metadata(conn, 0.05)
        time.sleep(0.06)
        with psycopg.connect(dsn) as conn:
            conn.execute("DELETE FROM paid_bill_recovery_sessions WHERE id=%s", (row["id"],))
            assert conn.execute("SELECT count(*) FROM paid_bill_recovery_sessions").fetchone() == (
                0,
            )
        assert_migrated(url, "downgrade", "0101")
