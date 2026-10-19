"""A partial but valid semantic row must not be erased by downgrade."""

from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic


def test_partial_semantic_metadata_prevents_downgrade():
    with _disposable_database("erp_audit_partial_metadata") as url:
        result = _run_alembic(url, "upgrade", "0090")
        assert result.returncode == 0, result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as db:
            company = uuid4()
            db.execute(
                "INSERT INTO companies(id,name) VALUES(%s,'Synthetic semantic evidence')",
                (company,),
            )
            db.execute(
                "INSERT INTO "
                "audit_log(company_id,action,entity_type,entity_id,category) "
                "VALUES(%s,'recorded','Synthetic','one','finance')",
                (company,),
            )
            db.commit()
        result = _run_alembic(url, "downgrade", "0089")
        assert result.returncode != 0
        assert "cannot downgrade semantic Audit" in result.stderr
        with psycopg.connect(dsn) as db:
            assert db.execute(
                "SELECT category FROM audit_log WHERE company_id=%s", (company,)
            ).fetchone() == ("finance",)


@pytest.mark.parametrize("commit_writer", [True, False])
def test_downgrade_waits_for_pending_semantic_writer_before_evidence_check(commit_writer):
    import time
    from concurrent.futures import ThreadPoolExecutor

    with _disposable_database("erp_audit_pending_metadata") as url:
        result = _run_alembic(url, "upgrade", "0090")
        assert result.returncode == 0, result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        company = uuid4()
        with psycopg.connect(dsn) as writer, psycopg.connect(dsn, autocommit=True) as observer:
            writer.execute(
                "INSERT INTO companies(id,name) VALUES(%s,'Pending synthetic writer')", (company,)
            )
            writer.commit()
            writer.execute(
                "INSERT INTO "
                "audit_log(company_id,action,entity_type,entity_id,category) "
                "VALUES(%s,'recorded','Synthetic','pending','finance')",
                (company,),
            )
            with ThreadPoolExecutor(1) as pool:
                pending = pool.submit(_run_alembic, url, "downgrade", "0089")
                deadline = time.monotonic() + 10
                waiting = False
                while time.monotonic() < deadline:
                    waiting = observer.execute(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE "
                        "datname=current_database() AND wait_event_type='Lock' AND query "
                        "LIKE 'LOCK TABLE audit_log IN ACCESS EXCLUSIVE MODE%%')"
                    ).fetchone()[0]
                    if waiting:
                        break
                    time.sleep(0.02)
                try:
                    assert waiting, (
                        "downgrade did not fence pending Audit writes before checking evidence"
                    )
                finally:
                    writer.commit() if commit_writer else writer.rollback()
                result = pending.result(timeout=10)
                if commit_writer:
                    assert result.returncode != 0
                    assert "cannot downgrade semantic Audit" in result.stderr
                else:
                    assert result.returncode == 0, result.stderr
            if commit_writer:
                assert observer.execute(
                    "SELECT category FROM audit_log WHERE company_id=%s", (company,)
                ).fetchone() == ("finance",)
            else:
                assert observer.execute(
                    "SELECT count(*) FROM audit_log WHERE company_id=%s", (company,)
                ).fetchone() == (0,)
