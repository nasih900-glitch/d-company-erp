"""PostgreSQL proof for immutable sold-item reporting snapshots (0049)."""

from __future__ import annotations

from uuid import uuid4

import psycopg
import pytest
from psycopg import errors

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)
from tests.integration.test_pos_payment_source_integrity import _seed_open_order


def _sync_dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


@pytest.mark.integration
def test_0049_backfills_and_freezes_historical_catalogue_facts() -> None:
    with _disposable_database("erp_order_line_reporting_backfill") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0048")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_open_order(connection)

        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            snapshot = connection.execute(
                "SELECT menu_item_name_snapshot, menu_item_type_snapshot, "
                "reporting_snapshot_revision FROM order_lines WHERE id=%s",
                (ids["line"],),
            ).fetchone()
            assert snapshot == ("0048 item", "food", None)

            connection.execute(
                "UPDATE menu_items SET name='Renamed later', type='gaming' WHERE id=%s",
                (ids["item"],),
            )
            connection.commit()
            unchanged = connection.execute(
                "SELECT menu_item_name_snapshot, menu_item_type_snapshot "
                "FROM order_lines WHERE id=%s",
                (ids["line"],),
            ).fetchone()
            assert unchanged == ("0048 item", "food")

            with pytest.raises(errors.CheckViolation, match="snapshots are immutable"):
                connection.execute(
                    "UPDATE order_lines SET menu_item_type_snapshot='gaming' WHERE id=%s",
                    (ids["line"],),
                )
            connection.rollback()

        blocked = _run_alembic(database_url, "downgrade", "0048")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "catalogue history has diverged" in output
        current = _run_alembic(database_url, "current")
        assert "0049" in current.stdout


@pytest.mark.integration
def test_0049_forward_insert_is_authoritative_and_blocks_downgrade() -> None:
    with _disposable_database("erp_order_line_reporting_forward") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0049")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_open_order(connection)
            snapshot = connection.execute(
                "SELECT menu_item_name_snapshot, menu_item_type_snapshot, "
                "reporting_snapshot_revision FROM order_lines WHERE id=%s",
                (ids["line"],),
            ).fetchone()
            assert snapshot == ("0048 item", "food", 49)

            connection.execute("DELETE FROM order_lines WHERE id=%s", (ids["line"],))
            connection.commit()
            with pytest.raises(
                errors.CheckViolation,
                match="client-supplied sold item snapshot",
            ):
                connection.execute(
                    "INSERT INTO order_lines "
                    "(id, order_id, menu_item_id, menu_item_name_snapshot, "
                    "menu_item_type_snapshot, qty, unit_price_minor, line_total_minor, "
                    "discount_minor, tax_rate, taxable_value_minor, cgst_minor, "
                    "sgst_minor, igst_minor, cess_minor, kitchen_status) "
                    "VALUES (%s, %s, %s, 'Spoofed', 'gaming', 1, %s, %s, 0, 0, "
                    "%s, 0, 0, 0, 0, 'queued')",
                    (
                        uuid4(),
                        ids["order"],
                        ids["item"],
                        ids["total_minor"],
                        ids["total_minor"],
                        ids["total_minor"],
                    ),
                )
            connection.rollback()

            # Reinsert a valid forward row so the revision marker proves that
            # downgrade cannot remove a newly accepted source fact.
            connection.execute(
                "INSERT INTO order_lines "
                "(id, order_id, menu_item_id, qty, unit_price_minor, "
                "line_total_minor, discount_minor, tax_rate, taxable_value_minor, "
                "cgst_minor, sgst_minor, igst_minor, cess_minor, kitchen_status) "
                "VALUES (%s, %s, %s, 1, %s, %s, 0, 0, %s, 0, 0, 0, 0, 'queued')",
                (
                    uuid4(),
                    ids["order"],
                    ids["item"],
                    ids["total_minor"],
                    ids["total_minor"],
                    ids["total_minor"],
                ),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "downgrade", "0048")
        assert blocked.returncode != 0
        assert "forward order-line activity" in blocked.stdout + blocked.stderr


@pytest.mark.integration
def test_0049_fails_closed_on_cross_company_legacy_line() -> None:
    with _disposable_database("erp_order_line_reporting_tenant_preflight") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0048")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            ids = _seed_open_order(connection)
            foreign_company = uuid4()
            foreign_category = uuid4()
            foreign_item = uuid4()
            connection.execute(
                "INSERT INTO companies (id, name) VALUES (%s, 'Foreign 0049')",
                (foreign_company,),
            )
            connection.execute(
                "INSERT INTO menu_categories (id, company_id, name, sort_order) "
                "VALUES (%s, %s, 'Foreign', 0)",
                (foreign_category, foreign_company),
            )
            connection.execute(
                "INSERT INTO menu_items "
                "(id, company_id, category_id, sku, name, type, base_price_minor, "
                "tax_rate, price_includes_tax, is_available) "
                "VALUES (%s, %s, %s, %s, 'Foreign item', 'gaming', 2500, 0, "
                "true, true)",
                (foreign_item, foreign_company, foreign_category, f"F-{uuid4()}"),
            )
            connection.execute(
                "UPDATE order_lines SET menu_item_id=%s WHERE id=%s",
                (foreign_item, ids["line"]),
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0049")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "cross-company catalogue provenance" in output
        current = _run_alembic(database_url, "current")
        assert "0048" in current.stdout
