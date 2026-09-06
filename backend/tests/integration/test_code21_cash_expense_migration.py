"""0071 is additive, round-trippable before use, and loss-refusing after use."""

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
def test_0071_round_trips_before_shift_linked_cash_activity() -> None:
    with _disposable_database("erp_code21_cash_expense_roundtrip") as url:
        result = _run_alembic(url, "upgrade", "0036")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            ids = _seed_0036_cafe_scope(conn)
            conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
            conn.commit()

        for command, revision in (
            ("upgrade", "0071"),
            ("downgrade", "0070"),
            ("upgrade", "0071"),
        ):
            result = _run_alembic(url, command, revision)
            assert result.returncode == 0, result.stdout + result.stderr

        with psycopg.connect(dsn) as conn:
            row = conn.execute(
                "SELECT shift_id,idempotency_key,request_hash,created_by "
                "FROM expenses LIMIT 1"
            ).fetchone()
            # The old fixture does not require an expense row; either way,
            # every pre-0071 row must retain empty compatibility provenance.
            assert row is None or row == (None, None, None, None)


@pytest.mark.integration
def test_0071_preserves_receipt_and_refuses_lossy_downgrade() -> None:
    with _disposable_database("erp_code21_cash_expense_receipt") as url:
        result = _run_alembic(url, "upgrade", "0036")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as conn:
            ids = _seed_0036_cafe_scope(conn)
            conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
            conn.commit()
        result = _run_alembic(url, "upgrade", "0071")
        assert result.returncode == 0, result.stdout + result.stderr

        expense_id = uuid4()
        category_id = uuid4()
        action_id = f"expense:{uuid4()}"
        request_hash = "a" * 64
        paid_at = datetime.now(UTC)
        with psycopg.connect(dsn) as conn:
            conn.execute(
                "INSERT INTO expense_categories(id,company_id,name,code) "
                "VALUES(%s,%s,'Code 21 cash receipt','C21MIG')",
                (category_id, ids["company"]),
            )
            conn.execute(
                "INSERT INTO expenses("
                "id,company_id,branch_id,shift_id,idempotency_key,request_hash,"
                "created_by,category_id,amount_minor,paid_via,paid_at,"
                "source_integrity_revision) VALUES("
                "%s,%s,%s,%s,%s,%s,%s,%s,500,'cash',%s,51)",
                (
                    expense_id,
                    ids["company"],
                    ids["branch"],
                    ids["shift"],
                    action_id,
                    request_hash,
                    ids["user"],
                    category_id,
                    paid_at,
                ),
            )
            conn.commit()

            # Immutable receipt identity cannot be moved to another action.
            with pytest.raises(psycopg.errors.RaiseException):
                conn.execute(
                    "UPDATE expenses SET idempotency_key=%s WHERE id=%s",
                    (f"expense:{uuid4()}", expense_id),
                )
            conn.rollback()

        result = _run_alembic(url, "downgrade", "0070")
        output = result.stdout + result.stderr
        assert result.returncode != 0
        assert "Cannot downgrade 0071" in output
        with psycopg.connect(dsn) as conn:
            assert conn.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0071",)
            assert conn.execute(
                "SELECT shift_id,idempotency_key,request_hash,created_by "
                "FROM expenses WHERE id=%s",
                (expense_id,),
            ).fetchone() == (
                ids["shift"],
                action_id,
                request_hash,
                ids["user"],
            )
