"""Migration 0077 preserves atomic drawer facts and refuses lossy rollback."""

from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


@pytest.mark.integration
def test_0077_round_trips_before_modern_cash_activity() -> None:
    with _disposable_database("erp_modern_cash_roundtrip") as url:
        result = _run_alembic(url, "upgrade", "0077")
        assert result.returncode == 0, result.stdout + result.stderr
        for command, revision in (
            ("downgrade", "0076"),
            ("upgrade", "0077"),
        ):
            result = _run_alembic(url, command, revision)
            assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.integration
def test_0077_database_guard_moves_drawer_and_refuses_lossy_downgrade() -> None:
    with _disposable_database("erp_modern_cash_receipt") as url:
        result = _run_alembic(url, "upgrade", "0036")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            ids = _seed_0036_cafe_scope(conn)
            conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
            conn.commit()

        result = _run_alembic(url, "upgrade", "0077")
        assert result.returncode == 0, result.stdout + result.stderr

        category_id = uuid4()
        expense_id = uuid4()
        action_id = f"expense:{uuid4()}"
        paid_at = datetime.now(UTC)
        with psycopg.connect(dsn) as conn:
            conn.execute(
                "UPDATE shifts SET opening_float_minor=5000, expected_minor=5000 WHERE id=%s",
                (ids["shift"],),
            )
            conn.execute(
                "INSERT INTO expense_categories(id,company_id,name,code) "
                "VALUES(%s,%s,'Modern cash paid-out','CASH52')",
                (category_id, ids["company"]),
            )
            conn.commit()

            with pytest.raises(
                psycopg.errors.RaiseException,
                match="exceeds expected cash",
            ):
                conn.execute(
                    "INSERT INTO expenses("
                    "id,company_id,branch_id,shift_id,idempotency_key,request_hash,"
                    "created_by,category_id,amount_minor,paid_via,paid_at,"
                    "source_integrity_revision) VALUES("
                    "%s,%s,%s,%s,%s,%s,%s,%s,5001,'cash',%s,52)",
                    (
                        uuid4(),
                        ids["company"],
                        ids["branch"],
                        ids["shift"],
                        f"expense:{uuid4()}",
                        "b" * 64,
                        ids["user"],
                        category_id,
                        paid_at,
                    ),
                )
            conn.rollback()
            assert conn.execute(
                "SELECT expected_minor FROM shifts WHERE id=%s",
                (ids["shift"],),
            ).fetchone() == (5000,)

            conn.execute(
                "INSERT INTO expenses("
                "id,company_id,branch_id,shift_id,idempotency_key,request_hash,"
                "created_by,category_id,amount_minor,paid_via,paid_at,"
                "source_integrity_revision) VALUES("
                "%s,%s,%s,%s,%s,%s,%s,%s,500,'cash',%s,52)",
                (
                    expense_id,
                    ids["company"],
                    ids["branch"],
                    ids["shift"],
                    action_id,
                    "a" * 64,
                    ids["user"],
                    category_id,
                    paid_at,
                ),
            )
            assert conn.execute(
                "SELECT expected_minor FROM shifts WHERE id=%s",
                (ids["shift"],),
            ).fetchone() == (4500,)
            conn.commit()

            conn.execute(
                "UPDATE expenses SET voided_at=%s,voided_by=%s,void_reason=%s WHERE id=%s",
                (
                    datetime.now(UTC),
                    ids["user"],
                    "Direct audited cash correction",
                    expense_id,
                ),
            )
            assert conn.execute(
                "SELECT expected_minor FROM shifts WHERE id=%s",
                (ids["shift"],),
            ).fetchone() == (5000,)
            conn.commit()

        result = _run_alembic(url, "downgrade", "0076")
        output = result.stdout + result.stderr
        assert result.returncode != 0
        assert "Cannot downgrade 0077" in output
        with psycopg.connect(dsn) as conn:
            assert conn.execute("SELECT version_num FROM alembic_version").fetchone() == ("0077",)
            assert conn.execute(
                "SELECT source_integrity_revision,voided_at IS NOT NULL FROM expenses WHERE id=%s",
                (expense_id,),
            ).fetchone() == (52, True)
