"""Real PostgreSQL proof for migration 0037's durable cafe invariants."""

from __future__ import annotations

import os
import subprocess
import sys
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy.engine import make_url

_BACKEND_ROOT = Path(__file__).resolve().parents[2]


def _run_alembic(database_url: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        cwd=_BACKEND_ROOT,
        env={**os.environ, "DATABASE_URL": database_url},
        capture_output=True,
        text=True,
        check=False,
    )


@contextmanager
def _disposable_database(prefix: str):
    source_url = make_url(
        os.environ.get(
            "DATABASE_URL",
            "postgresql+psycopg://erp:erp@localhost:5432/erp",
        )
    )
    if source_url.get_backend_name() != "postgresql" or source_url.host not in {
        "localhost",
        "127.0.0.1",
        "::1",
    }:
        pytest.skip("nested migration proof is restricted to local PostgreSQL")

    database_name = f"{prefix}_{uuid4().hex[:16]}"
    sync_source_url = source_url.set(drivername="postgresql+psycopg")
    test_url = sync_source_url.set(database=database_name)
    admin_dsn = sync_source_url.set(
        drivername="postgresql",
        database="postgres",
    ).render_as_string(hide_password=False)
    try:
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(
                sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database_name))
            )
    except Exception as exc:
        pytest.skip(f"local role cannot create a disposable database: {exc}")

    try:
        yield test_url.render_as_string(hide_password=False)
    finally:
        try:
            with psycopg.connect(admin_dsn, autocommit=True) as admin:
                admin.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    "WHERE datname = %s AND pid <> pg_backend_pid()",
                    (database_name,),
                )
                admin.execute(
                    sql.SQL("DROP DATABASE IF EXISTS {}").format(
                        sql.Identifier(database_name)
                    )
                )
        except Exception:
            pass


def _seed_0036_cafe_scope(connection: psycopg.Connection) -> dict[str, object]:
    ids = {
        "company": uuid4(),
        "branch": uuid4(),
        "terminal": uuid4(),
        "user": uuid4(),
        "void_actor": uuid4(),
        "ack_actor": uuid4(),
        "floor": uuid4(),
        "table": uuid4(),
        "shift": uuid4(),
        "category": uuid4(),
        "item": uuid4(),
        "order_one": uuid4(),
        "order_two": uuid4(),
        "direct_open_order": uuid4(),
        "direct_paid_order": uuid4(),
        "direct_paid_payment": uuid4(),
        "line": uuid4(),
        "legacy_void_line": uuid4(),
        "missing_actor_void_line": uuid4(),
        "direct_open_line": uuid4(),
        "direct_paid_line": uuid4(),
    }
    now = datetime.now(UTC)
    connection.execute(
        "INSERT INTO companies (id, name, gst_registration_type) "
        "VALUES (%s, '0037 Cafe', 'unregistered')",
        (ids["company"],),
    )
    connection.execute(
        "INSERT INTO branches (id, company_id, name, code, state_code) "
        "VALUES (%s, %s, 'Main', 'M1', '32')",
        (ids["branch"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO terminals (id, branch_id, name, device_id) "
        "VALUES (%s, %s, 'Till 1', %s)",
        (ids["terminal"], ids["branch"], f"0037-{uuid4()}"),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-real-password-hash', 'Tester')",
        (ids["user"], ids["company"], f"0037-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-real-password-hash', 'Legacy Void Actor')",
        (ids["void_actor"], ids["company"], f"0037-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-real-password-hash', 'Kitchen Ack Actor')",
        (ids["ack_actor"], ids["company"], f"0037-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO floors (id, branch_id, name) VALUES (%s, %s, 'Cafe')",
        (ids["floor"], ids["branch"]),
    )
    connection.execute(
        "INSERT INTO tables (id, floor_id, code, seats, status) "
        "VALUES (%s, %s, 'T1', 4, 'occupied')",
        (ids["table"], ids["floor"]),
    )
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 'open')",
        (
            ids["shift"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["user"],
            now,
        ),
    )
    connection.execute(
        "INSERT INTO menu_categories (id, company_id, name) "
        "VALUES (%s, %s, 'Food')",
        (ids["category"], ids["company"]),
    )
    connection.execute(
        "INSERT INTO menu_items "
        "(id, company_id, category_id, sku, name, type, base_price_minor, "
        "tax_rate, hsn_code, price_includes_tax, is_available) "
        "VALUES (%s, %s, %s, 'BURGER', 'Burger', 'food', 10000, 0, "
        "'996331', true, true)",
        (ids["item"], ids["company"], ids["category"]),
    )
    for order_id in (ids["order_one"], ids["order_two"]):
        connection.execute(
            "INSERT INTO orders "
            "(id, company_id, branch_id, terminal_id, shift_id, opened_by, "
            "table_id, type, status, opened_at, kitchen_state) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, 'dine_in', 'open', %s, "
            "'received')",
            (
                order_id,
                ids["company"],
                ids["branch"],
                ids["terminal"],
                ids["shift"],
                ids["user"],
                ids["table"],
                now,
            ),
        )
    connection.execute(
        "INSERT INTO orders "
        "(id, company_id, branch_id, terminal_id, shift_id, opened_by, "
        "table_id, type, status, opened_at, kitchen_state) "
        "VALUES (%s, %s, %s, %s, %s, %s, NULL, 'takeaway', 'open', %s, "
        "'received')",
        (
            ids["direct_open_order"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["shift"],
            ids["user"],
            now,
        ),
    )
    # This legacy paid direct order is used by runtime tests that later upgrade
    # through 0048. Give it the complete immutable invoice/payment evidence
    # expected of a real collected sale rather than relying on a pre-0048
    # status-only fixture.
    connection.execute(
        "INSERT INTO orders "
        "(id, company_id, branch_id, terminal_id, shift_id, opened_by, "
        "table_id, type, status, subtotal_minor, total_minor, opened_at, "
        "closed_at, invoice_issued_at, invoice_no, fiscal_year, kitchen_state) "
        "VALUES (%s, %s, %s, %s, %s, %s, NULL, 'takeaway', 'paid', 10000, "
        "10000, %s, %s, %s, 'D/M1/26-27/00001', '2026-27', 'received')",
        (
            ids["direct_paid_order"],
            ids["company"],
            ids["branch"],
            ids["terminal"],
            ids["shift"],
            ids["user"],
            now,
            now,
            now,
        ),
    )
    connection.execute(
        "INSERT INTO payments "
        "(id, order_id, shift_id, method, amount_minor, tendered_minor, "
        "change_minor, paid_at) VALUES (%s, %s, %s, 'cash', 10000, 10000, "
        "0, %s)",
        (
            ids["direct_paid_payment"],
            ids["direct_paid_order"],
            ids["shift"],
            now,
        ),
    )
    for line_id, order_id, voided_at, voided_by in (
        (ids["line"], ids["order_one"], None, None),
        (
            ids["legacy_void_line"],
            ids["order_one"],
            now,
            ids["void_actor"],
        ),
        (
            ids["missing_actor_void_line"],
            ids["order_one"],
            now,
            None,
        ),
        (ids["direct_open_line"], ids["direct_open_order"], None, None),
        (ids["direct_paid_line"], ids["direct_paid_order"], None, None),
    ):
        connection.execute(
            "INSERT INTO order_lines "
            "(id, order_id, menu_item_id, qty, unit_price_minor, line_total_minor, "
            "tax_rate, taxable_value_minor, cgst_minor, sgst_minor, igst_minor, "
            "cess_minor, kitchen_status, created_at, voided_at, voided_by) "
            "VALUES (%s, %s, %s, 1, 10000, 10000, 0, 10000, 0, 0, 0, 0, "
            "'queued', %s, %s, %s)",
            (line_id, order_id, ids["item"], now, voided_at, voided_by),
        )
    connection.commit()
    ids["created_at"] = now
    return ids


@pytest.mark.integration
def test_0037_preflight_backfill_constraints_and_checkout_trigger() -> None:
    with _disposable_database("erp_cafe_0037") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)

        duplicate_failure = _run_alembic(database_url, "upgrade", "0037")
        output = duplicate_failure.stdout + duplicate_failure.stderr
        assert duplicate_failure.returncode != 0
        assert "cannot enforce one active table bill" in output
        assert str(ids["table"]) in output

        with psycopg.connect(dsn) as connection:
            connection.execute(
                "DELETE FROM orders WHERE id = %s", (ids["order_two"],)
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0037")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(dsn) as connection:
            releases = {
                row[0]: (row[1], row[2])
                for row in connection.execute(
                    "SELECT order_lines.id, "
                    "(kitchen_released_at IS NOT NULL), kitchen_round_no "
                    "FROM order_lines WHERE order_lines.id = ANY(%s)",
                    (
                        [
                            ids["line"],
                            ids["legacy_void_line"],
                            ids["missing_actor_void_line"],
                            ids["direct_open_line"],
                            ids["direct_paid_line"],
                        ],
                    ),
                ).fetchall()
            }
            assert releases[ids["line"]] == (True, 1)
            assert releases[ids["legacy_void_line"]] == (True, 1)
            assert releases[ids["missing_actor_void_line"]] == (True, 1)
            # A pre-0037 direct draft had kitchen_state='received' even before
            # payment. It must not be resurrected into KDS by this migration.
            assert releases[ids["direct_open_line"]] == (False, None)
            assert releases[ids["direct_paid_line"]] == (True, 1)
            assert connection.execute(
                "SELECT checkout_version FROM orders WHERE id = %s",
                (ids["order_one"],),
            ).fetchone() == (1,)

            legacy_voids = {
                row[0]: (row[1], row[2])
                for row in connection.execute(
                    "SELECT id, (voided_by IS NOT NULL), void_reason "
                    "FROM order_lines WHERE id = ANY(%s)",
                    (
                        [
                            ids["legacy_void_line"],
                            ids["missing_actor_void_line"],
                        ],
                    ),
                ).fetchall()
            }
            assert legacy_voids[ids["legacy_void_line"]] == (
                True,
                "Legacy cancellation - reason not recorded",
            )
            assert legacy_voids[ids["missing_actor_void_line"]] == (
                False,
                "Legacy cancellation - actor and reason not recorded",
            )
            assert connection.execute(
                "SELECT convalidated FROM pg_constraint "
                "WHERE conname = 'ck_order_line_void_provenance'"
            ).fetchone() == (True,)

            # A migrated cancellation can still receive a new acknowledgement;
            # a NOT VALID constraint over an unbackfilled reason would reject
            # this otherwise harmless evidence-only UPDATE.
            for line_id in (
                ids["legacy_void_line"],
                ids["missing_actor_void_line"],
            ):
                connection.execute(
                    "UPDATE order_lines SET kitchen_void_acknowledged_at = now(), "
                    "kitchen_void_acknowledged_by = %s WHERE id = %s",
                    (ids["ack_actor"], line_id),
                )

            fk_actions = dict(
                connection.execute(
                    "SELECT conname, confdeltype FROM pg_constraint "
                    "WHERE conname IN ("
                    "'order_lines_voided_by_fkey', "
                    "'fk_order_lines_kitchen_void_acknowledged_by_users')"
                ).fetchall()
            )
            assert fk_actions == {
                "order_lines_voided_by_fkey": "r",
                "fk_order_lines_kitchen_void_acknowledged_by_users": "r",
            }
            with pytest.raises(psycopg.errors.ForeignKeyViolation):
                with connection.transaction():
                    connection.execute(
                        "DELETE FROM users WHERE id = %s", (ids["void_actor"],)
                    )
            with pytest.raises(psycopg.errors.ForeignKeyViolation):
                with connection.transaction():
                    connection.execute(
                        "DELETE FROM users WHERE id = %s", (ids["ack_actor"],)
                    )

            client_line_id = uuid4()
            connection.execute(
                "UPDATE order_lines SET client_line_id = %s WHERE id = %s",
                (client_line_id, ids["line"]),
            )
            assert connection.execute(
                "SELECT checkout_version FROM orders WHERE id = %s",
                (ids["order_one"],),
            ).fetchone() == (2,)
            connection.execute(
                "UPDATE order_lines SET kitchen_status = 'cooking', "
                "kitchen_released_at = kitchen_released_at + interval '1 second' "
                "WHERE id = %s",
                (ids["line"],),
            )
            assert connection.execute(
                "SELECT checkout_version FROM orders WHERE id = %s",
                (ids["order_one"],),
            ).fetchone() == (2,)
            connection.execute(
                "UPDATE order_lines SET qty = 2 WHERE id = %s",
                (ids["line"],),
            )
            assert connection.execute(
                "SELECT checkout_version FROM orders WHERE id = %s",
                (ids["order_one"],),
            ).fetchone() == (3,)

            with pytest.raises(psycopg.errors.CheckViolation):
                with connection.transaction():
                    connection.execute(
                        "UPDATE order_lines SET voided_at = now(), voided_by = %s "
                        "WHERE id = %s",
                        (ids["user"], ids["line"]),
                    )
            with pytest.raises(psycopg.errors.CheckViolation):
                with connection.transaction():
                    connection.execute(
                        "UPDATE order_lines SET voided_at = now(), "
                        "void_reason = "
                        "'Legacy cancellation - actor and reason not recorded' "
                        "WHERE id = %s",
                        (ids["line"],),
                    )
            with pytest.raises(psycopg.errors.CheckViolation):
                with connection.transaction():
                    connection.execute(
                        "INSERT INTO order_lines "
                        "(id, order_id, menu_item_id, qty, unit_price_minor, "
                        "line_total_minor, tax_rate, taxable_value_minor, "
                        "cgst_minor, sgst_minor, igst_minor, cess_minor, "
                        "voided_at, void_reason) VALUES "
                        "(%s, %s, %s, 1, 10000, 10000, 0, 10000, 0, 0, 0, 0, "
                        "now(), 'Legacy cancellation - actor and reason not recorded')",
                        (uuid4(), ids["order_one"], ids["item"]),
                    )
            with pytest.raises(psycopg.errors.UniqueViolation):
                with connection.transaction():
                    connection.execute(
                        "INSERT INTO order_lines "
                        "(id, order_id, client_line_id, menu_item_id, qty, "
                        "unit_price_minor, line_total_minor, tax_rate, "
                        "taxable_value_minor, cgst_minor, sgst_minor, igst_minor, "
                        "cess_minor) VALUES (%s, %s, %s, %s, 1, 10000, 10000, "
                        "0, 10000, 0, 0, 0, 0)",
                        (
                            uuid4(),
                            ids["order_one"],
                            client_line_id,
                            ids["item"],
                        ),
                    )

            # The active-table uniqueness is a DB invariant, not a best-effort
            # SELECT in one API process.
            with pytest.raises(psycopg.errors.UniqueViolation):
                with connection.transaction():
                    connection.execute(
                        "INSERT INTO orders "
                        "(id, company_id, branch_id, terminal_id, shift_id, "
                        "opened_by, table_id, type, status, opened_at) VALUES "
                        "(%s, %s, %s, %s, %s, %s, %s, 'dine_in', 'held', now())",
                        (
                            uuid4(),
                            ids["company"],
                            ids["branch"],
                            ids["terminal"],
                            ids["shift"],
                            ids["user"],
                            ids["table"],
                        ),
                    )


@pytest.mark.integration
def test_0037_downgrade_refuses_to_erase_forward_workflow_evidence() -> None:
    with _disposable_database("erp_cafe_0037_down") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            connection.execute(
                "DELETE FROM orders WHERE id = %s", (ids["order_two"],)
            )
            connection.commit()
        upgraded = _run_alembic(database_url, "upgrade", "0037")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        with psycopg.connect(dsn) as connection:
            connection.execute(
                "UPDATE order_lines SET client_line_id = %s WHERE id = %s",
                (uuid4(), ids["line"]),
            )
            connection.commit()

        refused = _run_alembic(database_url, "downgrade", "0036")
        output = refused.stdout + refused.stderr
        assert refused.returncode != 0
        assert "downgrade refused" in output
        assert "durable cafe workflow evidence" in output

        # Test-only cleanup proves a representation-only legacy backfill can be
        # downgraded when no forward action evidence exists.
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "UPDATE order_lines SET client_line_id = NULL, "
                "kitchen_released_at = created_at WHERE id = %s",
                (ids["line"],),
            )
            connection.commit()
        downgraded = _run_alembic(database_url, "downgrade", "0036")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
