"""A partial but valid semantic row must not be erased by downgrade."""
from uuid import uuid4
import psycopg
from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic


def test_partial_semantic_metadata_prevents_downgrade():
    with _disposable_database("erp_audit_partial_metadata") as url:
        result = _run_alembic(url, "upgrade", "0090")
        assert result.returncode == 0, result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as db:
            company = uuid4()
            db.execute("INSERT INTO companies(id,name) VALUES(%s,'Synthetic semantic evidence')", (company,))
            db.execute("INSERT INTO audit_log(company_id,action,entity_type,entity_id,category) VALUES(%s,'recorded','Synthetic','one','finance')", (company,))
            db.commit()
        result = _run_alembic(url, "downgrade", "0089")
        assert result.returncode != 0
        assert "cannot downgrade semantic Audit" in result.stderr
        with psycopg.connect(dsn) as db:
            assert db.execute("SELECT category FROM audit_log WHERE company_id=%s", (company,)).fetchone() == ("finance",)
