"""PostgreSQL proof for immutable POS payment/refund source facts."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import errors

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


def _sync_dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def _seed_open_order(
    connection: psycopg.Connection,
    *,
    total_minor: int = 2_500,
) -> dict[str, UUID | datetime | int]:
    ids: dict[str, UUID | datetime | int] = {
        "company": uuid4(),
        "branch": uuid4(),
        "user": uuid4(),
        "terminal": uuid4(),
        "shift": uuid4(),
        "category": uuid4(),
        "item": uuid4(),
        "order": uuid4(),
        "line": uuid4(),
        "opened_at": datetime.now(UTC).replace(microsecond=0) - timedelta(hours=1),
        "total_minor": total_minor,
    }
    connection.execute(
        "INSERT INTO companies (id, name) VALUES (%s, '0048 POS source proof')",
        (ids["company"],),
    )
    connection.execute(
        "INSERT INTO branches "
        "(id, company_id, name, code, invoice_series_code) "
        "VALUES (%s, %s, 'Main', 'Main', 'MA')",
        (ids["branch"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-password', '0048 Tester')",
        (ids["user"], ids["company"], f"0048-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO terminals (id, branch_id, name, device_id) "
        "VALUES (%s, %s, 'POS', %s)",
        (ids["terminal"], ids["branch"], f"0048-{uuid4()}"),
    )
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
        "opening_float_minor, expected_minor, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 0, 0, 'open')",
        (
            ids["shift"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["user"],
            ids["opened_at"],
        ),
    )
    connection.execute(
        "INSERT INTO menu_categories (id, company_id, name, sort_order) "
        "VALUES (%s, %s, '0048', 0)",
        (ids["category"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO menu_items "
        "(id, company_id, category_id, sku, name, type, base_price_minor, "
        "tax_rate, price_includes_tax, is_available) "
        "VALUES (%s, %s, %s, %s, '0048 item', 'food', %s, 0, true, true)",
        (
            ids["item"],
            ids["company"],
            ids["category"],
            f"0048-{uuid4().hex[:10]}",
            total_minor,
        ),
    )
    connection.execute(
        "INSERT INTO orders "
        "(id, company_id, branch_id, terminal_id, shift_id, opened_by, type, "
        "status, subtotal_minor, total_minor, opened_at) "
        "VALUES (%s, %s, %s, %s, %s, %s, 'takeaway', 'open', %s, %s, %s)",
        (
            ids["order"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["shift"],
            ids["user"],
            total_minor,
            total_minor,
            ids["opened_at"],
        ),
    )
    connection.execute(
        "INSERT INTO order_lines "
        "(id, order_id, menu_item_id, qty, unit_price_minor, line_total_minor, "
        "discount_minor, tax_rate, taxable_value_minor, cgst_minor, sgst_minor, "
        "igst_minor, cess_minor, kitchen_status) "
        "VALUES (%s, %s, %s, 1, %s, %s, 0, 0, %s, 0, 0, 0, 0, 'queued')",
        (
            ids["line"],
            ids["order"],
            ids["item"],
            total_minor,
            total_minor,
            total_minor,
        ),
    )
    connection.commit()
    return ids


def _finalize_order(
    connection: psycopg.Connection,
    ids: dict[str, UUID | datetime | int],
    *,
    paid_minor: int | None,
    method: str = "cash",
    change_minor: int | None = 0,
) -> None:
    issued_at = datetime.now(UTC).replace(microsecond=0)
    connection.execute(
        "UPDATE orders SET status='paid', closed_at=%s, invoice_issued_at=%s, "
        "invoice_no='D/MA/26-27/00001', fiscal_year='2026-27' WHERE id=%s",
        (issued_at, issued_at, ids["order"]),
    )
    if paid_minor is not None:
        connection.execute(
            "INSERT INTO payments "
            "(id, order_id, shift_id, method, amount_minor, tendered_minor, "
            "change_minor, paid_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
            (
                uuid4(),
                ids["order"],
                ids["shift"],
                method,
                paid_minor,
                paid_minor if method == "cash" else None,
                change_minor if method == "cash" else None,
                issued_at,
            ),
        )
    connection.commit()


@pytest.mark.integration
@pytest.mark.parametrize(
    "corruption",
    ("underpaid_final", "payment_on_open_order", "final_without_payment"),
)
def test_0048_fails_closed_on_stranded_legacy_payment_sources(
    corruption: str,
) -> None:
    with _disposable_database(f"erp_pos_source_{corruption}") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0047")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_open_order(connection)
            if corruption == "underpaid_final":
                _finalize_order(connection, ids, paid_minor=2_000)
            elif corruption == "final_without_payment":
                _finalize_order(connection, ids, paid_minor=None)
            else:
                paid_at = datetime.now(UTC).replace(microsecond=0)
                connection.execute(
                    "INSERT INTO payments "
                    "(id, order_id, shift_id, method, amount_minor, paid_at) "
                    "VALUES (%s, %s, %s, 'upi', 2500, %s)",
                    (uuid4(), ids["order"], ids["shift"], paid_at),
                )
                connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0048")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "Cannot enforce POS payment integrity" in output
        current = _run_alembic(database_url, "current")
        assert "0047" in current.stdout


@pytest.mark.integration
def test_0048_direct_sql_guards_paid_sources_and_status() -> None:
    with _disposable_database("erp_pos_source_direct_guards") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0048")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            paid = _seed_open_order(connection)
            _finalize_order(connection, paid, paid_minor=2_500)
            unpaid = _seed_open_order(connection)

            marker = connection.execute(
                "SELECT source_integrity_revision FROM orders WHERE id=%s",
                (paid["order"],),
            ).fetchone()
            assert marker == (48,)

            for statement, params in (
                (
                    "UPDATE payments SET amount_minor=1 WHERE order_id=%s",
                    (paid["order"],),
                ),
                ("DELETE FROM payments WHERE order_id=%s", (paid["order"],)),
                (
                    "UPDATE orders SET total_minor=1 WHERE id=%s",
                    (paid["order"],),
                ),
                (
                    "UPDATE orders SET created_at=created_at - interval '1 day' "
                    "WHERE id=%s",
                    (paid["order"],),
                ),
                (
                    "UPDATE order_lines SET qty=2 WHERE id=%s",
                    (paid["line"],),
                ),
                (
                    "UPDATE order_lines SET created_at=created_at - interval '1 day' "
                    "WHERE id=%s",
                    (paid["line"],),
                ),
                (
                    "UPDATE order_lines SET order_id=%s WHERE id=%s",
                    (unpaid["order"], paid["line"]),
                ),
                (
                    "UPDATE order_lines SET order_id=%s WHERE id=%s",
                    (paid["order"], unpaid["line"]),
                ),
                (
                    "UPDATE orders SET status='refunded' WHERE id=%s",
                    (paid["order"],),
                ),
            ):
                with pytest.raises(errors.CheckViolation):
                    with connection.transaction():
                        connection.execute(statement, params)

            with pytest.raises(errors.CheckViolation):
                with connection.transaction():
                    connection.execute(
                        "INSERT INTO payments "
                        "(id, order_id, shift_id, method, amount_minor, paid_at) "
                        "VALUES (%s, %s, %s, 'upi', 1, now())",
                        (uuid4(), paid["order"], paid["shift"]),
                    )

            connection.execute(
                "UPDATE orders SET kitchen_state='received' WHERE id=%s",
                (paid["order"],),
            )
            connection.execute(
                "UPDATE order_lines SET kitchen_status='cooking' WHERE id=%s",
                (paid["line"],),
            )
            connection.commit()


@pytest.mark.integration
def test_0048_rejects_final_without_money_and_cash_without_change() -> None:
    with _disposable_database("erp_pos_source_deferred_balance") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0048")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            missing = _seed_open_order(connection)
            with pytest.raises(errors.CheckViolation, match="payment total"):
                with connection.transaction():
                    issued_at = datetime.now(UTC).replace(microsecond=0)
                    connection.execute(
                        "UPDATE orders SET status='paid', closed_at=%s, "
                        "invoice_issued_at=%s, invoice_no='D/MA/26-27/00001', "
                        "fiscal_year='2026-27' WHERE id=%s",
                        (issued_at, issued_at, missing["order"]),
                    )

            bad_cash = _seed_open_order(connection)
            with pytest.raises(errors.CheckViolation):
                with connection.transaction():
                    issued_at = datetime.now(UTC).replace(microsecond=0)
                    connection.execute(
                        "UPDATE orders SET status='paid', closed_at=%s, "
                        "invoice_issued_at=%s, invoice_no='D/MA/26-27/00001', "
                        "fiscal_year='2026-27' WHERE id=%s",
                        (issued_at, issued_at, bad_cash["order"]),
                    )
                    connection.execute(
                        "INSERT INTO payments "
                        "(id, order_id, shift_id, method, amount_minor, "
                        "tendered_minor, change_minor, paid_at) "
                        "VALUES (%s, %s, %s, 'cash', 2500, 2500, NULL, %s)",
                        (
                            uuid4(),
                            bad_cash["order"],
                            bad_cash["shift"],
                            issued_at,
                        ),
                    )


@pytest.mark.integration
def test_0048_downgrade_refuses_forward_zero_total_invoice() -> None:
    with _disposable_database("erp_pos_source_zero_downgrade") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0048")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            zero = _seed_open_order(connection, total_minor=0)
            _finalize_order(connection, zero, paid_minor=None)
            marker = connection.execute(
                "SELECT source_integrity_revision FROM orders WHERE id=%s",
                (zero["order"],),
            ).fetchone()
            assert marker == (48,)

        blocked = _run_alembic(database_url, "downgrade", "0047")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "Cannot downgrade 0048" in output
        current = _run_alembic(database_url, "current")
        assert "0048" in current.stdout


@pytest.mark.integration
def test_0048_downgrade_succeeds_before_forward_activity() -> None:
    with _disposable_database("erp_pos_source_clean_downgrade") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0048")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        downgraded = _run_alembic(database_url, "downgrade", "0047")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
