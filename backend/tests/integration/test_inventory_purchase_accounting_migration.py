"""Migration proof for 0045 purchase-accounting integrity gates."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


def _seed_legacy_grn(connection: psycopg.Connection) -> dict[str, object]:
    ids: dict[str, object] = {
        "company": uuid4(),
        "branch": uuid4(),
        "user": uuid4(),
        "supplier": uuid4(),
        "ingredient": uuid4(),
        "purchase_order": uuid4(),
        "grn": uuid4(),
        "grn_line": uuid4(),
        "batch": uuid4(),
        "movement": uuid4(),
    }
    occurred_at = datetime.now(UTC)
    connection.execute(
        "INSERT INTO companies (id, name, gst_registration_type) "
        "VALUES (%s, '0045 Purchase', 'unregistered')",
        (ids["company"],),
    )
    connection.execute(
        "INSERT INTO branches (id, company_id, name, code, state_code) "
        "VALUES (%s, %s, 'Main', %s, '32')",
        (ids["branch"], ids["company"], f"P{uuid4().hex[:8]}"),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-real-password-hash', 'Tester')",
        (ids["user"], ids["company"], f"0045-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO accounts "
        "(id, company_id, code, name, type, normal_side, is_active) VALUES "
        "(%s, %s, '1200', 'Inventory', 'asset', 'dr', true), "
        "(%s, %s, '2000', 'Accounts Payable', 'liability', 'cr', true)",
        (uuid4(), ids["company"], uuid4(), ids["company"]),
    )
    connection.execute(
        "INSERT INTO suppliers (id, company_id, name) VALUES (%s, %s, 'Supplier')",
        (ids["supplier"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO ingredients "
        "(id, company_id, sku, name, base_unit, current_qty, avg_cost_minor) "
        "VALUES (%s, %s, %s, 'Ingredient', 'unit', 10, 200)",
        (ids["ingredient"], ids["company"], f"MIG-{uuid4().hex[:10]}"),
    )
    connection.execute(
        "INSERT INTO purchase_orders "
        "(id, company_id, supplier_id, branch_id, po_number, status, "
        "expected_at, total_minor, created_by) "
        "VALUES (%s, %s, %s, %s, %s, 'closed', %s, 2000, %s)",
        (
            ids["purchase_order"],
            ids["company"],
            ids["supplier"],
            ids["branch"],
            f"PO-{uuid4().hex[:12]}",
            occurred_at,
            ids["user"],
        ),
    )
    connection.execute(
        "INSERT INTO grns "
        "(id, purchase_order_id, received_at, received_by, "
        "supplier_invoice_no, supplier_invoice_amount_minor) "
        "VALUES (%s, %s, %s, %s, 'LEGACY-1', 2000)",
        (ids["grn"], ids["purchase_order"], occurred_at, ids["user"]),
    )
    connection.execute(
        "INSERT INTO batches "
        "(id, ingredient_id, branch_id, supplier_id, grn_id, received_at, "
        "qty_initial, qty_on_hand, cost_per_unit_minor, lot_code) "
        "VALUES (%s, %s, %s, %s, %s, %s, 10, 10, 200, 'LEGACY-BATCH')",
        (
            ids["batch"],
            ids["ingredient"],
            ids["branch"],
            ids["supplier"],
            ids["grn"],
            occurred_at,
        ),
    )
    connection.execute(
        "INSERT INTO grn_lines "
        "(id, grn_id, ingredient_id, batch_id, qty_received, cost_per_unit_minor) "
        "VALUES (%s, %s, %s, %s, 10, 200)",
        (ids["grn_line"], ids["grn"], ids["ingredient"], ids["batch"]),
    )
    connection.execute(
        "INSERT INTO stock_movements "
        "(id, batch_id, branch_id, type, ref_type, ref_id, qty_delta, "
        "cost_per_unit_minor, created_by) "
        "VALUES (%s, %s, %s, 'grn', 'grn', %s, 10, 200, %s)",
        (
            ids["movement"],
            ids["batch"],
            ids["branch"],
            ids["grn"],
            ids["user"],
        ),
    )
    connection.commit()
    return ids


@pytest.mark.integration
@pytest.mark.parametrize(
    ("column", "value"),
    [("qty_received", 0), ("cost_per_unit_minor", -1)],
    ids=("non-positive-quantity", "negative-unit-cost"),
)
def test_0045_rejects_invalid_legacy_grn_lines(column: str, value: int) -> None:
    with _disposable_database(f"erp_purchase_invalid_{column}") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0044")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_legacy_grn(connection)
            connection.execute(
                f"UPDATE grn_lines SET {column} = %s WHERE id = %s",  # noqa: S608
                (value, ids["grn_line"]),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0045")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "invalid received quantity or unit cost exists" in output


@pytest.mark.integration
@pytest.mark.parametrize(
    ("statement", "value"),
    [
        ("UPDATE batches SET qty_on_hand = %s WHERE id = %s", 9),
        (
            "UPDATE stock_movements SET cost_per_unit_minor = %s "
            "WHERE id = %s",
            201,
        ),
    ],
    ids=("quantity-replay-mismatch", "movement-cost-mismatch"),
)
def test_0045_rejects_inconsistent_batch_evidence(
    statement: str,
    value: int,
) -> None:
    with _disposable_database("erp_purchase_bad_batch") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0044")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_legacy_grn(connection)
            target_id = (
                ids["batch"]
                if statement.startswith("UPDATE batches")
                else ids["movement"]
            )
            connection.execute(statement, (value, target_id))
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0045")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "batch movement evidence is inconsistent" in output


@pytest.mark.integration
@pytest.mark.parametrize("movement_type", ["transfer", "legacy_unknown"])
def test_0045_rejects_unpostable_legacy_movement_types(
    movement_type: str,
) -> None:
    with _disposable_database("erp_purchase_bad_movement") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0044")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_legacy_grn(connection)
            connection.execute(
                "UPDATE stock_movements SET type = %s WHERE id = %s",
                (movement_type, ids["movement"]),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0045")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "unsupported stock movement exists" in output


@pytest.mark.integration
def test_0045_rejects_grn_line_without_matching_physical_batch() -> None:
    with _disposable_database("erp_purchase_bad_source") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0044")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_legacy_grn(connection)
            connection.execute(
                "UPDATE grn_lines SET batch_id = NULL WHERE id = %s",
                (ids["grn_line"],),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0045")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "receipt lines and physical batches diverge" in output


@pytest.mark.integration
def test_0045_backfill_constraints_and_irreversible_activity_guard() -> None:
    with _disposable_database("erp_purchase_accounting_0045") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0044")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_legacy_grn(connection)

        upgraded = _run_alembic(database_url, "upgrade", "0045")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        with psycopg.connect(dsn) as connection:
            journal = connection.execute(
                "SELECT je.id, je.total_minor "
                "FROM grns g JOIN journal_entries je ON je.id = g.journal_entry_id "
                "WHERE g.id = %s AND je.ref_type = 'grn_receipt'",
                (ids["grn"],),
            ).fetchone()
            assert journal is not None
            assert journal[1] == 2_000
            lines = connection.execute(
                "SELECT a.code, jl.side, jl.amount_minor "
                "FROM journal_lines jl JOIN accounts a ON a.id = jl.account_id "
                "WHERE jl.journal_entry_id = %s ORDER BY a.code",
                (journal[0],),
            ).fetchall()
            assert lines == [("1200", "dr", 2_000), ("2000", "cr", 2_000)]

            # Both asymmetric NULL pairs and a non-SHA256 hash must fail at
            # the database layer, not just through Pydantic/ORM validation.
            for key, request_hash in (
                ("key-only", None),
                (None, "a" * 64),
                ("bad-hash", "short"),
            ):
                with pytest.raises(psycopg.errors.CheckViolation):
                    connection.execute(
                        "UPDATE grns SET idempotency_key = %s, request_hash = %s "
                        "WHERE id = %s",
                        (key, request_hash, ids["grn"]),
                    )
                connection.rollback()

            payment_id = uuid4()
            payment_journal_id = uuid4()
            connection.execute(
                "INSERT INTO journal_entries "
                "(id, company_id, branch_id, ref_type, ref_id, posted_at, "
                "total_minor) VALUES (%s, %s, %s, 'supplier_payment', %s, %s, 100)",
                (
                    payment_journal_id,
                    ids["company"],
                    ids["branch"],
                    payment_id,
                    datetime.now(UTC),
                ),
            )
            connection.commit()
            with pytest.raises(psycopg.errors.CheckViolation):
                connection.execute(
                    "INSERT INTO supplier_payments "
                    "(id, company_id, branch_id, supplier_id, grn_id, "
                    "journal_entry_id, amount_minor, method, paid_at, "
                    "payment_reference, idempotency_key, request_hash, "
                    "created_by, voided_at, voided_by, void_reason) "
                    "VALUES (%s, %s, %s, %s, %s, %s, 100, 'cash', %s, "
                    "'receipt', 'partial-void', %s, %s, %s, %s, NULL)",
                    (
                        payment_id,
                        ids["company"],
                        ids["branch"],
                        ids["supplier"],
                        ids["grn"],
                        payment_journal_id,
                        datetime.now(UTC),
                        "b" * 64,
                        ids["user"],
                        datetime.now(UTC),
                        ids["user"],
                    ),
                )
            connection.rollback()
            connection.execute(
                "DELETE FROM journal_entries WHERE id = %s",
                (payment_journal_id,),
            )
            connection.commit()

        # A database containing only deterministic legacy backfill can return
        # to 0044 without deleting forward operational history.
        downgraded = _run_alembic(database_url, "downgrade", "0044")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        upgraded = _run_alembic(database_url, "upgrade", "0045")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        # A post-migration reserved journal without an exact legacy GRN source
        # is forward financial activity and must never be silently deleted.
        orphan_journal = uuid4()
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "INSERT INTO journal_entries "
                "(id, company_id, branch_id, ref_type, ref_id, posted_at, "
                "total_minor) VALUES (%s, %s, %s, 'supplier_payment', %s, %s, 1)",
                (
                    orphan_journal,
                    ids["company"],
                    ids["branch"],
                    uuid4(),
                    datetime.now(UTC),
                ),
            )
            connection.commit()
        blocked_orphan = _run_alembic(database_url, "downgrade", "0044")
        orphan_output = blocked_orphan.stdout + blocked_orphan.stderr
        assert blocked_orphan.returncode != 0
        assert "Cannot downgrade 0045 after forward purchase-accounting activity" in (
            orphan_output
        )
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "DELETE FROM journal_entries WHERE id = %s",
                (orphan_journal,),
            )
            connection.commit()

        with psycopg.connect(dsn) as connection:
            connection.execute(
                "UPDATE grns SET idempotency_key = 'forward-grn', "
                "request_hash = %s WHERE id = %s",
                ("c" * 64, ids["grn"]),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "downgrade", "0044")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "Cannot downgrade 0045 after forward purchase-accounting activity" in output
