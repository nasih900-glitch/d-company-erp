"""The 0070 catalogue migration is additive and rename-stable."""

from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


@pytest.mark.integration
def test_0070_backfills_known_categories_and_preserves_stable_classification() -> None:
    with _disposable_database("erp_gaming_catalog_0070") as url:
        result = _run_alembic(url, "upgrade", "0036")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            ids = _seed_0036_cafe_scope(conn)
            # The shared legacy fixture intentionally contains the duplicate
            # table bill used by 0037's refusal test. This test exercises a
            # different additive migration, so keep the valid predecessor.
            conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
            conn.commit()

        result = _run_alembic(url, "upgrade", "0069")
        assert result.returncode == 0, result.stdout + result.stderr
        drinks_id, cafe_id = uuid4(), uuid4()
        with psycopg.connect(dsn) as conn:
            conn.execute(
                "INSERT INTO menu_categories(id,company_id,name,sort_order) "
                "VALUES(%s,%s,'Soft Drinks',1),(%s,%s,'Coffee',2)",
                (drinks_id, ids["company"], cafe_id, ids["company"]),
            )
            conn.commit()

        result = _run_alembic(url, "upgrade", "0070")
        assert result.returncode == 0, result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute(
                "SELECT is_gaming_centre_catalog FROM menu_categories WHERE id=%s",
                (drinks_id,),
            ).fetchone() == (True,)
            assert conn.execute(
                "SELECT is_gaming_centre_catalog FROM menu_categories WHERE id=%s",
                (cafe_id,),
            ).fetchone() == (False,)

            conn.execute(
                "UPDATE menu_categories SET name='Cold cabinet' WHERE id=%s",
                (drinks_id,),
            )
            conn.commit()
            assert conn.execute(
                "SELECT name,is_gaming_centre_catalog FROM menu_categories WHERE id=%s",
                (drinks_id,),
            ).fetchone() == ("Cold cabinet", True)

        result = _run_alembic(url, "downgrade", "0069")
        assert result.returncode != 0
        assert "preserving it" in result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute("SELECT version_num FROM alembic_version").fetchone() == ("0070",)
            assert conn.execute(
                "SELECT name,is_gaming_centre_catalog FROM menu_categories WHERE id=%s",
                (drinks_id,),
            ).fetchone() == ("Cold cabinet", True)
