"""Upgrade proof for valid finance writes across the shared trigger tables."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)
from tests.integration.test_finance_source_integrity import _seed_company_branch_user


@pytest.mark.integration
def test_0067_validates_each_record_shape_and_preserves_finance_sources() -> None:
    with _disposable_database("erp_finance_dispatch") as url:
        upgraded = _run_alembic(url, "upgrade", "0066")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_company_branch_user(
                connection, label="Dispatch proof", invoice_series="DP",
            )
            foreign = _seed_company_branch_user(
                connection, label="Foreign dispatch proof", invoice_series="FP",
            )
            connection.commit()
            insert_tip = (
                "INSERT INTO tip_payouts "
                "(id, company_id, branch_id, amount_minor, method, paid_at, "
                "note, idempotency_key, created_by) VALUES "
                "(%s, %s, %s, 100, 'cash', %s, 'Staff earned tips', %s, %s)"
            )
            params = (
                uuid4(), ids["company"], ids["branch"], datetime.now(UTC),
                str(uuid4()), ids["user"],
            )
            with pytest.raises(psycopg.errors.UndefinedColumn, match="source_kind"):
                connection.execute(insert_tip, params)
            connection.rollback()

        repaired = _run_alembic(url, "upgrade", "0067")
        assert repaired.returncode == 0, repaired.stdout + repaired.stderr
        with psycopg.connect(dsn) as connection:
            connection.execute(insert_tip, params)
            partner_id = uuid4()
            connection.execute(
                "INSERT INTO partners (id, company_id, name, share_pct, joined_at) "
                "VALUES (%s, %s, 'Audit partner', 100, %s)",
                (partner_id, ids["company"], datetime.now(UTC)),
            )
            connection.execute(
                "INSERT INTO capital_entries (id, partner_id, type, amount_minor, "
                "effective_at, settlement_account, created_by) "
                "VALUES (%s, %s, 'invest', 1000, %s, 'bank', %s)",
                (uuid4(), partner_id, datetime.now(UTC), ids["user"]),
            )
            connection.execute(
                "INSERT INTO manual_collections "
                "(id, company_id, branch_id, business_date, method, amount_minor, "
                "source_kind, source_ref, idempotency_key, created_by) "
                "VALUES (%s, %s, %s, %s, 'cash', 500, 'manual_daily', %s, %s, %s)",
                (uuid4(), ids["company"], ids["branch"], datetime.now(UTC).date(),
                 str(uuid4()), str(uuid4()), ids["user"]),
            )
            connection.commit()
            for actor in (foreign["user"],):
                with pytest.raises(psycopg.errors.RaiseException, match="invalid tenant"):
                    connection.execute(insert_tip, (
                        uuid4(), ids["company"], ids["branch"], datetime.now(UTC),
                        str(uuid4()), actor,
                    ))
                connection.rollback()
            with pytest.raises(psycopg.errors.RaiseException, match="immutable"):
                connection.execute("DELETE FROM tip_payouts WHERE id=%s", (params[0],))
            connection.rollback()

        rolled_back = _run_alembic(url, "downgrade", "0066")
        assert rolled_back.returncode == 0, rolled_back.stdout + rolled_back.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT amount_minor FROM tip_payouts WHERE id=%s", (params[0],),
            ).fetchone() == (100,)
            connection.execute(insert_tip, (
                uuid4(), ids["company"], ids["branch"], datetime.now(UTC),
                str(uuid4()), ids["user"],
            ))
            connection.commit()
