"""Direct SQL and pending-writer evidence for migration0091."""

import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic
from tests.integration.test_pos_payment_source_integrity import _finalize_order, _seed_open_order


def seed(db):
    values = _seed_open_order(db)
    _finalize_order(db, values, paid_minor=2500)
    role = uuid4()
    db.execute(
        "INSERT INTO roles(id,company_id,code,name) "
        "VALUES(%s,%s,'super_owner','Protected synthetic owner')",
        (role, values["company"]),
    )
    db.execute(
        "INSERT INTO user_roles(id,user_id,role_id) VALUES(%s,%s,%s)",
        (uuid4(), values["user"], role),
    )
    db.execute(
        "UPDATE shifts SET "
        "status='closed',closed_at=clock_timestamp(),closed_by=%s,"
        "counted_minor=expected_minor,variance_minor=0 "
        "WHERE id=%s",
        (values["user"], values["shift"]),
    )
    db.commit()
    return values


def insert(
    db,
    values,
    *,
    bad_amount=False,
    branch_id=None,
    recorded_by=None,
    fingerprint=None,
    business_date=None,
):
    # Same database-side source function as the owner preview; identities are
    # synthetic and the insert independently rechecks them under parent locks.
    return db.execute(
        """INSERT INTO payment_tender_corrections(
      id,company_id,branch_id,business_date,business_timezone,recorded_by,reason,evidence_kind,
      source_fingerprint,idempotency_key,request_hash,request_path,request_body,payment_id,order_id,shift_id,
      invoice_no,original_method,effective_method,amount_minor,original_tendered_minor,original_change_minor,accounting_at)
      SELECT %s,o.company_id,COALESCE(%s::uuid,o.branch_id),
      COALESCE(%s::date,(s.opened_at AT TIME ZONE c.timezone)::date),c.timezone,%s,
      'Synthetic owner attestation','owner_attestation',
      COALESCE(%s,dcompany_collection_fingerprint(o.company_id,o.branch_id,
        (s.opened_at AT TIME ZONE c.timezone)::date,c.timezone)),
      %s,repeat('a',64),'/synthetic/test','{}'::jsonb,p.id,o.id,s.id,o.invoice_no,'cash','upi',p.amount_minor+%s,
      p.tendered_minor,p.change_minor,p.paid_at FROM payments p JOIN orders o ON o.id=p.order_id
      JOIN shifts s ON s.id=p.shift_id JOIN companies c ON c.id=o.company_id
      WHERE o.id=%s RETURNING id""",
        (
            uuid4(),
            branch_id,
            business_date,
            recorded_by or values["user"],
            fingerprint,
            str(uuid4()),
            1 if bad_amount else 0,
            values["order"],
        ),
    ).fetchone()[0]


def test_direct_sql_guards_sources_mutation_and_config():
    with _disposable_database("erp_tender_guard") as url:
        migration = _run_alembic(url, "upgrade", "head")
        assert migration.returncode == 0, migration.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as db:
            values = seed(db)
            other = seed(db)
            for invalid in (
                {"bad_amount": True},
                {"branch_id": other["branch"]},
                {"recorded_by": other["user"]},
                {"fingerprint": "f" * 64},
                {"business_date": date.today() + timedelta(days=3)},
            ):
                with pytest.raises(psycopg.errors.CheckViolation), db.transaction():
                    insert(db, values, **invalid)
                db.rollback()
            receipt = insert(db, values)
            db.commit()
            for sql in (
                "UPDATE payment_tender_corrections SET amount_minor=1",
                "DELETE FROM payment_tender_corrections",
                "TRUNCATE payment_tender_corrections",
                "UPDATE companies SET timezone='UTC'",
            ):
                with pytest.raises(psycopg.Error), db.transaction():
                    db.execute(sql)
                db.rollback()
            assert db.execute(
                "SELECT method,amount_minor FROM payments WHERE order_id=%s", (values["order"],)
            ).fetchone() == ("cash", 2500)
            assert db.execute("SELECT id FROM payment_tender_corrections").fetchall() == [
                (receipt,)
            ]
        downgrade = _run_alembic(url, "downgrade", "0090")
        assert downgrade.returncode != 0
        assert "cannot discard tender evidence" in downgrade.stderr


@pytest.mark.parametrize("commit_writer", [True, False])
def test_pending_receipt_writer_is_fenced_before_downgrade(commit_writer):
    with _disposable_database("erp_tender_pending") as url:
        migration = _run_alembic(url, "upgrade", "0091")
        assert migration.returncode == 0, migration.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as writer, psycopg.connect(dsn, autocommit=True) as observer:
            values = seed(writer)
            insert(writer, values)
            with ThreadPoolExecutor(1) as pool:
                pending = pool.submit(_run_alembic, url, "downgrade", "0090")
                waiting = False
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    waiting = observer.execute(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE "
                        "datname=current_database() AND wait_event_type='Lock' AND query "
                        "LIKE 'LOCK TABLE payment_tender_corrections%%')"
                    ).fetchone()[0]
                    if waiting:
                        break
                    time.sleep(0.02)
                try:
                    assert waiting, "downgrade must wait before its emptiness decision"
                finally:
                    writer.commit() if commit_writer else writer.rollback()
                result = pending.result(timeout=10)
                if commit_writer:
                    assert result.returncode != 0
                    assert "cannot discard tender evidence" in result.stderr
                    assert observer.execute(
                        "SELECT count(*) FROM payment_tender_corrections"
                    ).fetchone() == (1,)
                else:
                    assert result.returncode == 0, result.stderr
                    assert observer.execute(
                        "SELECT to_regclass('payment_tender_corrections')"
                    ).fetchone() == (None,)


def test_direct_insert_rechecks_source_after_waiting_for_backdated_writer():
    with _disposable_database("erp_tender_source_wait") as url:
        migration = _run_alembic(url, "upgrade", "head")
        assert migration.returncode == 0, migration.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as writer, psycopg.connect(dsn, autocommit=True) as observer:
            values = seed(writer)
            writer.execute(
                """INSERT INTO manual_collections(id,company_id,branch_id,business_date,
                method,amount_minor,source_kind,source_ref,idempotency_key,created_by,request_hash)
                VALUES(%s,%s,%s,current_date,'upi',2000,'manual_daily','Synthetic late source',
                %s,%s,repeat('a',64))""",
                (
                    uuid4(),
                    values["company"],
                    values["branch"],
                    "manual-collection:" + str(uuid4()),
                    values["user"],
                ),
            )

            def pending_insert():
                with psycopg.connect(dsn) as db:
                    insert(db, values)
                    db.commit()

            with ThreadPoolExecutor(1) as pool:
                pending = pool.submit(pending_insert)
                deadline = time.monotonic() + 10
                waiting = False
                while time.monotonic() < deadline:
                    waiting = observer.execute("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                        WHERE datname=current_database() AND wait_event_type='Lock'
                        AND query LIKE 'INSERT INTO payment_tender_corrections%%')""").fetchone()[0]
                    if waiting:
                        break
                    time.sleep(0.02)
                try:
                    assert waiting, "direct INSERT must wait on the same source fence"
                finally:
                    writer.commit()
                with pytest.raises(psycopg.errors.CheckViolation, match="preview is stale"):
                    pending.result(timeout=10)
            assert observer.execute(
                "SELECT count(*) FROM payment_tender_corrections"
            ).fetchone() == (0,)
