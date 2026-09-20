"""Real PostgreSQL proof for migration 0078's cash-source invariants."""

from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import errors

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


def _dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def _upgrade(database_url: str, revision: str) -> None:
    result = _run_alembic(database_url, "upgrade", revision)
    assert result.returncode == 0, result.stdout + result.stderr


def _seed_scope(connection: psycopg.Connection) -> dict[str, UUID | datetime]:
    now = datetime.now(UTC)
    ids: dict[str, UUID | datetime] = {
        "company": uuid4(),
        "branch": uuid4(),
        "other_branch": uuid4(),
        "terminal": uuid4(),
        "user": uuid4(),
        "category": uuid4(),
        "cash_account": uuid4(),
        "inventory_account": uuid4(),
        "payable_account": uuid4(),
        "original_shift": uuid4(),
        "settlement_shift": uuid4(),
        "supplier": uuid4(),
        "ingredient": uuid4(),
        "purchase_order": uuid4(),
        "grn": uuid4(),
        "batch": uuid4(),
        "grn_line": uuid4(),
        "grn_journal": uuid4(),
        "now": now,
    }
    connection.execute(
        "INSERT INTO companies (id, name, gst_registration_type) "
        "VALUES (%s, '0078 Finance', 'unregistered')",
        (ids["company"],),
    )
    connection.execute(
        "INSERT INTO branches "
        "(id, company_id, name, code, invoice_series_code, state_code) "
        "VALUES (%s, %s, 'Main', 'M1', 'M1', '32')",
        (ids["branch"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO branches "
        "(id, company_id, name, code, invoice_series_code, state_code) "
        "VALUES (%s, %s, 'Other', 'O1', 'O1', '32')",
        (ids["other_branch"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO terminals (id, branch_id, name, device_id) "
        "VALUES (%s, %s, '0078 till', %s)",
        (ids["terminal"], ids["branch"], f"0078-{uuid4()}"),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name, status) "
        "VALUES (%s, %s, %s, 'not-a-real-hash', '0078 Actor', 'active')",
        (ids["user"], ids["company"], f"0078-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO expense_categories (id, company_id, name, code) "
        "VALUES (%s, %s, '0078 expense', %s)",
        (ids["category"], ids["company"], f"E{uuid4().hex[:10]}"),
    )
    for account_id, code, name, account_type, normal_side in (
        (ids["cash_account"], "1000", "Cash", "asset", "dr"),
        (ids["inventory_account"], "1200", "Inventory", "asset", "dr"),
        (
            ids["payable_account"],
            "2000",
            "Accounts Payable",
            "liability",
            "cr",
        ),
    ):
        connection.execute(
            "INSERT INTO accounts "
            "(id, company_id, code, name, type, normal_side, is_active) "
            "VALUES (%s, %s, %s, %s, %s, %s, true)",
            (
                account_id,
                ids["company"],
                code,
                name,
                account_type,
                normal_side,
            ),
        )
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
        "opening_float_minor, expected_minor, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 10000, 10000, 'open')",
        (
            ids["original_shift"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["user"],
            now - timedelta(hours=2),
        ),
    )
    _seed_supplier_receipt(connection, ids)
    return ids


def _seed_supplier_receipt(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime],
) -> None:
    received_at = ids["now"] - timedelta(minutes=30)  # type: ignore[operator]
    connection.execute(
        "INSERT INTO suppliers (id, company_id, name) VALUES (%s, %s, '0078 supplier')",
        (ids["supplier"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO ingredients "
        "(id, company_id, sku, name, base_unit, reorder_threshold, reorder_qty, "
        "avg_cost_minor, current_qty) "
        "VALUES (%s, %s, %s, '0078 stock', 'unit', 0, 0, 500, 10)",
        (ids["ingredient"], ids["company"], f"I-{uuid4().hex[:10]}"),
    )
    connection.execute(
        "INSERT INTO purchase_orders "
        "(id, company_id, supplier_id, branch_id, po_number, status, total_minor, "
        "created_by) VALUES (%s, %s, %s, %s, %s, 'closed', 5000, %s)",
        (
            ids["purchase_order"],
            ids["company"],
            ids["supplier"],
            ids["branch"],
            f"PO-{uuid4().hex[:10]}",
            ids["user"],
        ),
    )
    # The GRN, its inventory line/batch and its receipt journal are one
    # deferred source pair. They must be committed together.
    with connection.transaction():
        connection.execute(
            "INSERT INTO grns "
            "(id, purchase_order_id, received_at, received_by, "
            "supplier_invoice_no, supplier_invoice_amount_minor, "
            "idempotency_key, request_hash) "
            "VALUES (%s, %s, %s, %s, %s, 5000, %s, %s)",
            (
                ids["grn"],
                ids["purchase_order"],
                received_at,
                ids["user"],
                f"INV-{uuid4().hex[:8]}",
                f"grn:{uuid4()}",
                "1" * 64,
            ),
        )
        connection.execute(
            "INSERT INTO batches "
            "(id, ingredient_id, branch_id, supplier_id, grn_id, received_at, "
            "qty_initial, qty_on_hand, cost_per_unit_minor, lot_code) "
            "VALUES (%s, %s, %s, %s, %s, %s, 10, 10, 500, '0078-LOT')",
            (
                ids["batch"],
                ids["ingredient"],
                ids["branch"],
                ids["supplier"],
                ids["grn"],
                received_at,
            ),
        )
        connection.execute(
            "INSERT INTO grn_lines "
            "(id, grn_id, ingredient_id, batch_id, qty_received, "
            "cost_per_unit_minor) VALUES (%s, %s, %s, %s, 10, 500)",
            (ids["grn_line"], ids["grn"], ids["ingredient"], ids["batch"]),
        )
        connection.execute(
            "INSERT INTO journal_entries "
            "(id, company_id, branch_id, ref_type, ref_id, posted_at, memo, "
            "total_minor) VALUES (%s, %s, %s, 'grn_receipt', %s, %s, "
            "'0078 receipt', 5000)",
            (
                ids["grn_journal"],
                ids["company"],
                ids["branch"],
                ids["grn"],
                received_at,
            ),
        )
        connection.execute(
            "INSERT INTO journal_lines "
            "(id, journal_entry_id, account_id, side, amount_minor, memo) "
            "VALUES (%s, %s, %s, 'dr', 5000, 'Inventory received'), "
            "(%s, %s, %s, 'cr', 5000, 'Supplier payable')",
            (
                uuid4(),
                ids["grn_journal"],
                ids["inventory_account"],
                uuid4(),
                ids["grn_journal"],
                ids["payable_account"],
            ),
        )
        connection.execute(
            "UPDATE grns SET journal_entry_id = %s WHERE id = %s",
            (ids["grn_journal"], ids["grn"]),
        )


def _drawer(connection: psycopg.Connection, shift_id: UUID) -> int:
    return int(
        connection.execute(
            "SELECT expected_minor FROM shifts WHERE id = %s", (shift_id,)
        ).fetchone()[0]
    )


def _insert_manual_collection(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime],
    *,
    shift_id: UUID | None = None,
    amount_minor: int = 2_000,
    method: str = "cash",
    row_id: UUID | None = None,
) -> UUID:
    source_id = row_id or uuid4()
    connection.execute(
        "INSERT INTO manual_collections "
        "(id, company_id, branch_id, shift_id, business_date, method, "
        "amount_minor, source_kind, source_ref, note, idempotency_key, "
        "request_hash, created_by, source_integrity_revision) "
        "VALUES (%s, %s, %s, %s, CURRENT_DATE, %s, %s, 'manual_daily', %s, "
        "'0078 collection', %s, %s, %s, 1)",
        (
            source_id,
            ids["company"],
            ids["branch"],
            shift_id if shift_id is not None else ids["original_shift"],
            method,
            amount_minor,
            f"source-{uuid4()}",
            f"manual-collection:{uuid4()}",
            "2" * 64,
            ids["user"],
        ),
    )
    return source_id


def _insert_cash_sources(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime],
) -> dict[str, UUID]:
    now = ids["now"]
    assert isinstance(now, datetime)
    sources: dict[str, UUID] = {
        "expense": uuid4(),
        "manual_collection": uuid4(),
        "tip_payout": uuid4(),
        "supplier_payment": uuid4(),
    }
    connection.execute(
        "INSERT INTO expenses "
        "(id, company_id, branch_id, shift_id, idempotency_key, request_hash, "
        "created_by, category_id, amount_minor, paid_via, paid_at, vendor_name, "
        "note, source_integrity_revision) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 1000, 'cash', %s, "
        "'0078 vendor', '0078 expense', 52)",
        (
            sources["expense"],
            ids["company"],
            ids["branch"],
            ids["original_shift"],
            f"expense:{uuid4()}",
            "3" * 64,
            ids["user"],
            ids["category"],
            now - timedelta(minutes=5),
        ),
    )
    _insert_manual_collection(
        connection,
        ids,
        row_id=sources["manual_collection"],
    )
    connection.execute(
        "INSERT INTO tip_payouts "
        "(id, company_id, branch_id, shift_id, amount_minor, method, paid_at, "
        "note, idempotency_key, request_hash, created_by, "
        "source_integrity_revision) "
        "VALUES (%s, %s, %s, %s, 500, 'cash', %s, '0078 staff tips', %s, %s, "
        "%s, 1)",
        (
            sources["tip_payout"],
            ids["company"],
            ids["branch"],
            ids["original_shift"],
            now - timedelta(minutes=4),
            f"tip-payout:{uuid4()}",
            "4" * 64,
            ids["user"],
        ),
    )
    payment_journal = uuid4()
    paid_at = now - timedelta(minutes=3)
    with connection.transaction():
        connection.execute(
            "INSERT INTO journal_entries "
            "(id, company_id, branch_id, ref_type, ref_id, posted_at, memo, "
            "total_minor) VALUES (%s, %s, %s, 'supplier_payment', %s, %s, "
            "'0078 supplier cash', 700)",
            (
                payment_journal,
                ids["company"],
                ids["branch"],
                sources["supplier_payment"],
                paid_at,
            ),
        )
        connection.execute(
            "INSERT INTO journal_lines "
            "(id, journal_entry_id, account_id, side, amount_minor, memo) "
            "VALUES (%s, %s, %s, 'dr', 700, 'Pay supplier'), "
            "(%s, %s, %s, 'cr', 700, 'Cash paid')",
            (
                uuid4(),
                payment_journal,
                ids["payable_account"],
                uuid4(),
                payment_journal,
                ids["cash_account"],
            ),
        )
        connection.execute(
            "INSERT INTO supplier_payments "
            "(id, company_id, branch_id, shift_id, supplier_id, grn_id, "
            "journal_entry_id, amount_minor, method, paid_at, payment_reference, "
            "note, idempotency_key, request_hash, created_by, "
            "source_integrity_revision) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, 700, 'cash', %s, %s, "
            "'0078 supplier payment', %s, %s, %s, 1)",
            (
                sources["supplier_payment"],
                ids["company"],
                ids["branch"],
                ids["original_shift"],
                ids["supplier"],
                ids["grn"],
                payment_journal,
                paid_at,
                f"PAY-{uuid4().hex[:8]}",
                f"supplier-payment:{uuid4()}",
                "5" * 64,
                ids["user"],
            ),
        )
    return sources


_CORRECTION_COLUMN = {
    "expense": "expense_id",
    "manual_collection": "manual_collection_id",
    "tip_payout": "tip_payout_id",
    "supplier_payment": "supplier_payment_id",
}


def _insert_correction(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime],
    *,
    source_type: str,
    source_id: UUID,
    amount_minor: int,
    correction_id: UUID | None = None,
    idempotency_key: str | None = None,
    branch_id: UUID | None = None,
) -> UUID:
    source_column = _CORRECTION_COLUMN[source_type]
    prefix = source_type.replace("_", "-")
    row_id = correction_id or uuid4()
    connection.execute(
        f"INSERT INTO finance_source_corrections "  # noqa: S608 - closed mapping
        f"(id, company_id, branch_id, source_type, {source_column}, "
        "original_shift_id, settlement_shift_id, amount_minor, idempotency_key, "
        "request_hash, corrected_by, reason) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, "
        "'0078 current-period reversal')",
        (
            row_id,
            ids["company"],
            branch_id or ids["branch"],
            source_type,
            source_id,
            ids["original_shift"],
            ids["settlement_shift"],
            amount_minor,
            idempotency_key or f"{prefix}-correction:{uuid4()}",
            "6" * 64,
            ids["user"],
        ),
    )
    return row_id


def _close_shift(connection: psycopg.Connection, ids: dict[str, UUID | datetime]) -> None:
    connection.execute(
        "UPDATE shifts SET status = 'closed', closed_by = %s, closed_at = %s, "
        "counted_minor = expected_minor, variance_minor = 0 WHERE id = %s",
        (ids["user"], datetime.now(UTC), ids["original_shift"]),
    )


def _open_settlement_shift(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime],
) -> None:
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
        "opening_float_minor, expected_minor, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 10000, 10000, 'open')",
        (
            ids["settlement_shift"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["user"],
            datetime.now(UTC),
        ),
    )


@pytest.mark.integration
def test_0078_clean_upgrade_downgrade_round_trip() -> None:
    with _disposable_database("erp_finance_0078_roundtrip") as database_url:
        _upgrade(database_url, "0077")
        _upgrade(database_url, "0078")
        dsn = _dsn(database_url)
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone()[0] == "0078"
            assert connection.execute(
                "SELECT to_regclass('finance_source_corrections')"
            ).fetchone()[0] == "finance_source_corrections"
            assert connection.execute(
                "SELECT COUNT(*) FROM information_schema.columns "
                "WHERE table_name = 'manual_collections' "
                "AND column_name IN ('shift_id', 'request_hash', "
                "'source_integrity_revision')"
            ).fetchone()[0] == 3

        downgraded = _run_alembic(database_url, "downgrade", "0077")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone()[0] == "0077"
            assert connection.execute(
                "SELECT to_regclass('finance_source_corrections')"
            ).fetchone()[0] is None
            assert connection.execute(
                "SELECT COUNT(*) FROM information_schema.columns "
                "WHERE table_name = 'manual_collections' "
                "AND column_name IN ('shift_id', 'request_hash', "
                "'source_integrity_revision')"
            ).fetchone()[0] == 0

        _upgrade(database_url, "0078")


@pytest.mark.integration
def test_0078_data_bearing_downgrade_is_refused_without_mutation() -> None:
    with _disposable_database("erp_finance_0078_downgrade_guard") as database_url:
        _upgrade(database_url, "0078")
        dsn = _dsn(database_url)
        with psycopg.connect(dsn, autocommit=True) as connection:
            ids = _seed_scope(connection)
            source_id = _insert_manual_collection(connection, ids)
            assert _drawer(connection, ids["original_shift"]) == 12_000

        refused = _run_alembic(database_url, "downgrade", "0077")
        output = refused.stdout + refused.stderr
        assert refused.returncode != 0
        assert "Cannot downgrade 0078 after drawer-linked finance activity" in output
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone()[0] == "0078"
            assert connection.execute(
                "SELECT COUNT(*) FROM manual_collections WHERE id = %s",
                (source_id,),
            ).fetchone()[0] == 1
            assert _drawer(connection, ids["original_shift"]) == 12_000


@pytest.mark.integration
def test_0078_cash_sources_and_corrections_are_guarded_and_exactly_once() -> None:
    with _disposable_database("erp_finance_0078_sources") as database_url:
        _upgrade(database_url, "0078")
        with psycopg.connect(_dsn(database_url), autocommit=True) as connection:
            ids = _seed_scope(connection)
            sources = _insert_cash_sources(connection, ids)
            assert _drawer(connection, ids["original_shift"]) == 9_800

            with pytest.raises(errors.RaiseException, match="exceeds expected cash"):
                connection.execute(
                    "INSERT INTO tip_payouts "
                    "(id, company_id, branch_id, shift_id, amount_minor, method, "
                    "paid_at, note, idempotency_key, request_hash, created_by, "
                    "source_integrity_revision) "
                    "VALUES (%s, %s, %s, %s, 9801, 'cash', %s, 'overdraw', %s, "
                    "%s, %s, 1)",
                    (
                        uuid4(),
                        ids["company"],
                        ids["branch"],
                        ids["original_shift"],
                        datetime.now(UTC),
                        f"tip-payout:{uuid4()}",
                        "7" * 64,
                        ids["user"],
                    ),
                )
            assert _drawer(connection, ids["original_shift"]) == 9_800

            with pytest.raises(
                (errors.RaiseException, errors.CheckViolation),
                match="noncash|drawer receipt",
            ):
                _insert_manual_collection(connection, ids, method="upi")
            assert _drawer(connection, ids["original_shift"]) == 9_800

            _close_shift(connection, ids)
            _open_settlement_shift(connection, ids)
            with pytest.raises(errors.RaiseException, match="current open"):
                _insert_manual_collection(connection, ids, amount_minor=100)

            with pytest.raises(errors.RaiseException, match="does not match"):
                _insert_correction(
                    connection,
                    ids,
                    source_type="manual_collection",
                    source_id=sources["manual_collection"],
                    amount_minor=1_999,
                )
            assert _drawer(connection, ids["settlement_shift"]) == 10_000

            correction_ids = {
                "expense": _insert_correction(
                    connection,
                    ids,
                    source_type="expense",
                    source_id=sources["expense"],
                    amount_minor=1_000,
                ),
                "manual_collection": _insert_correction(
                    connection,
                    ids,
                    source_type="manual_collection",
                    source_id=sources["manual_collection"],
                    amount_minor=2_000,
                ),
                "tip_payout": _insert_correction(
                    connection,
                    ids,
                    source_type="tip_payout",
                    source_id=sources["tip_payout"],
                    amount_minor=500,
                ),
                "supplier_payment": _insert_correction(
                    connection,
                    ids,
                    source_type="supplier_payment",
                    source_id=sources["supplier_payment"],
                    amount_minor=700,
                ),
            }
            assert _drawer(connection, ids["settlement_shift"]) == 10_200
            assert connection.execute(
                "SELECT COUNT(*) FROM finance_source_corrections "
                "WHERE company_id = %s",
                (ids["company"],),
            ).fetchone()[0] == 4

            with pytest.raises(errors.UniqueViolation):
                _insert_correction(
                    connection,
                    ids,
                    source_type="manual_collection",
                    source_id=sources["manual_collection"],
                    amount_minor=2_000,
                )
            assert _drawer(connection, ids["settlement_shift"]) == 10_200

            with pytest.raises(errors.RaiseException, match="append-only"):
                connection.execute(
                    "UPDATE finance_source_corrections SET reason = 'changed' "
                    "WHERE id = %s",
                    (correction_ids["expense"],),
                )
            with pytest.raises(errors.RaiseException, match="append-only"):
                connection.execute(
                    "DELETE FROM finance_source_corrections WHERE id = %s",
                    (correction_ids["tip_payout"],),
                )
            with pytest.raises(errors.RaiseException, match="cannot also be voided"):
                connection.execute(
                    "UPDATE manual_collections SET voided_at = %s, voided_by = %s, "
                    "void_reason = 'duplicate reversal' WHERE id = %s",
                    (
                        datetime.now(UTC),
                        ids["user"],
                        sources["manual_collection"],
                    ),
                )
            with pytest.raises(errors.RaiseException, match="cannot also be voided"):
                connection.execute(
                    "UPDATE expenses SET voided_at = %s, voided_by = %s, "
                    "void_reason = 'duplicate reversal' WHERE id = %s",
                    (datetime.now(UTC), ids["user"], sources["expense"]),
                )
            assert _drawer(connection, ids["original_shift"]) == 9_800
            assert _drawer(connection, ids["settlement_shift"]) == 10_200


def _add_open_shift(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime],
) -> UUID:
    shift_id = uuid4()
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
        "opening_float_minor, expected_minor, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 10000, 10000, 'open')",
        (
            shift_id,
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["user"],
            datetime.now(UTC) - timedelta(minutes=10),
        ),
    )
    return shift_id


def _wait_until_lock_blocked(dsn: str, application_name: str) -> None:
    deadline = time.monotonic() + 5
    with psycopg.connect(dsn, autocommit=True) as observer:
        while time.monotonic() < deadline:
            row = observer.execute(
                "SELECT wait_event_type FROM pg_stat_activity "
                "WHERE application_name = %s AND pid <> pg_backend_pid()",
                (application_name,),
            ).fetchone()
            if row is not None and row[0] == "Lock":
                return
            time.sleep(0.02)
    raise AssertionError(f"{application_name} never blocked on the shift row")


def _close_shift_worker(dsn: str, shift_id: UUID, user_id: UUID, name: str) -> None:
    with psycopg.connect(dsn, application_name=name) as connection:
        connection.execute(
            "UPDATE shifts SET status = 'closed', closed_by = %s, closed_at = %s, "
            "counted_minor = expected_minor, variance_minor = 0 WHERE id = %s",
            (user_id, datetime.now(UTC), shift_id),
        )


def _insert_manual_worker(
    dsn: str,
    ids: dict[str, UUID | datetime],
    shift_id: UUID,
    name: str,
) -> str | None:
    try:
        with psycopg.connect(dsn, application_name=name) as connection:
            _insert_manual_collection(connection, ids, shift_id=shift_id, amount_minor=500)
    except psycopg.Error as exc:
        return str(exc)
    return None


@pytest.mark.integration
def test_0078_source_insert_and_shift_close_are_serialized() -> None:
    with _disposable_database("erp_finance_0078_shift_race") as database_url:
        _upgrade(database_url, "0078")
        dsn = _dsn(database_url)
        with psycopg.connect(dsn, autocommit=True) as setup:
            ids = _seed_scope(setup)
            insert_wins_shift = ids["original_shift"]

        with (
            ThreadPoolExecutor(max_workers=1) as executor,
            psycopg.connect(dsn) as insert_connection,
        ):
            _insert_manual_collection(
                insert_connection,
                ids,
                shift_id=insert_wins_shift,
                amount_minor=500,
            )
            close_name = f"0078-close-{uuid4()}"
            close_future = executor.submit(
                _close_shift_worker,
                dsn,
                insert_wins_shift,
                ids["user"],
                close_name,
            )
            _wait_until_lock_blocked(dsn, close_name)
            insert_connection.commit()
            close_future.result(timeout=5)

        with psycopg.connect(dsn, autocommit=True) as verify:
            assert verify.execute(
                "SELECT status, expected_minor FROM shifts WHERE id = %s",
                (insert_wins_shift,),
            ).fetchone() == ("closed", 10_500)
            assert verify.execute(
                "SELECT COUNT(*) FROM manual_collections WHERE shift_id = %s",
                (insert_wins_shift,),
            ).fetchone()[0] == 1
            close_wins_shift = _add_open_shift(verify, ids)

        with (
            ThreadPoolExecutor(max_workers=1) as executor,
            psycopg.connect(dsn) as close_connection,
        ):
            close_connection.execute(
                "UPDATE shifts SET status = 'closed', closed_by = %s, "
                "closed_at = %s, counted_minor = expected_minor, "
                "variance_minor = 0 WHERE id = %s",
                (ids["user"], datetime.now(UTC), close_wins_shift),
            )
            insert_name = f"0078-insert-{uuid4()}"
            insert_future = executor.submit(
                _insert_manual_worker,
                dsn,
                ids,
                close_wins_shift,
                insert_name,
            )
            _wait_until_lock_blocked(dsn, insert_name)
            close_connection.commit()
            insert_error = insert_future.result(timeout=5)

        assert insert_error is not None
        assert "current open same-branch shift" in insert_error
        with psycopg.connect(dsn) as verify:
            assert verify.execute(
                "SELECT status, expected_minor FROM shifts WHERE id = %s",
                (close_wins_shift,),
            ).fetchone() == ("closed", 10_000)
            assert verify.execute(
                "SELECT COUNT(*) FROM manual_collections WHERE shift_id = %s",
                (close_wins_shift,),
            ).fetchone()[0] == 0
