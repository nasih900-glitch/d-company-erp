"""Forward/backward PostgreSQL proof for migration 0040."""

from __future__ import annotations

from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


@pytest.mark.integration
def test_0040_backfills_legacy_modifiers_and_enforces_normalized_scope() -> None:
    with _disposable_database("erp_menu_modifiers_0040") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0039")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        company_id = uuid4()
        category_id = uuid4()
        item_id = uuid4()
        variant_id = uuid4()
        oat_id = uuid4()
        soy_id = uuid4()
        other_id = uuid4()
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "INSERT INTO companies (id, name, gst_registration_type) "
                "VALUES (%s, 'Modifier Migration', 'unregistered')",
                (company_id,),
            )
            connection.execute(
                "INSERT INTO menu_categories (id, company_id, name) "
                "VALUES (%s, %s, 'Drinks')",
                (category_id, company_id),
            )
            connection.execute(
                "INSERT INTO menu_items "
                "(id, company_id, category_id, sku, name, type, base_price_minor, "
                "tax_rate, is_available) "
                "VALUES (%s, %s, %s, 'COFFEE', 'Coffee', 'drink', 10000, 0.05, true)",
                (item_id, company_id, category_id),
            )
            connection.execute(
                "INSERT INTO menu_variants "
                "(id, menu_item_id, name, price_delta_minor, sort_order) "
                "VALUES (%s, %s, 'Large', 1000, 2)",
                (variant_id, item_id),
            )
            with connection.cursor() as cursor:
                cursor.executemany(
                    "INSERT INTO menu_modifiers "
                    "(id, menu_item_id, name, price_delta_minor, \"group\", "
                    "max_per_order, required) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                    [
                        (oat_id, item_id, "Oat", 250, "Milk", 1, True),
                        (soy_id, item_id, "Soy", 200, "milk", 2, False),
                        (other_id, item_id, "Hot", 0, None, 1, False),
                    ],
                )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0040")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(dsn) as connection:
            groups = connection.execute(
                "SELECT id, company_id, menu_item_id, name, min_select, max_select "
                "FROM menu_modifier_groups ORDER BY lower(name)"
            ).fetchall()
            assert len(groups) == 2
            milk = next(row for row in groups if row[3].lower() == "milk")
            options = connection.execute(
                "SELECT id, company_id, menu_item_id, modifier_group_id, "
                "max_quantity, is_active FROM menu_modifiers ORDER BY name"
            ).fetchall()
            assert {row[0] for row in options} == {oat_id, soy_id, other_id}
            assert all(row[1] == company_id and row[2] == item_id for row in options)
            assert milk[1:3] == (company_id, item_id)
            assert milk[4:] == (1, 3)
            assert {row[3] for row in options if row[0] in {oat_id, soy_id}} == {milk[0]}
            variant = connection.execute(
                "SELECT company_id, is_active, price_delta_minor, sort_order "
                "FROM menu_variants WHERE id = %s",
                (variant_id,),
            ).fetchone()
            assert variant == (company_id, True, 1000, 2)

            constraint_names = {
                row[0]
                for row in connection.execute(
                    "SELECT conname FROM pg_constraint "
                    "WHERE conrelid IN ("
                    "'menu_modifier_groups'::regclass, 'menu_modifiers'::regclass, "
                    "'menu_variants'::regclass)"
                ).fetchall()
            }
            assert "fk_menu_modifiers_group_scope" in constraint_names
            assert "ck_menu_modifier_group_selection_bounds" in constraint_names
            assert "ck_menu_modifier_max_quantity" in constraint_names
            index_names = {
                row[0]
                for row in connection.execute(
                    "SELECT indexname FROM pg_indexes WHERE tablename IN "
                    "('menu_modifier_groups', 'menu_modifiers', 'menu_variants')"
                ).fetchall()
            }
            assert "uq_menu_modifier_groups_company_item_name_ci" in index_names
            assert "ix_menu_modifiers_company_item_group_active_sort" in index_names

        downgraded = _run_alembic(database_url, "downgrade", "0039")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            legacy = connection.execute(
                "SELECT \"group\", required, max_per_order "
                "FROM menu_modifiers WHERE id = %s",
                (oat_id,),
            ).fetchone()
            # Legacy rows are consolidated case-insensitively. When the two
            # original rows share a transaction timestamp, UUID ordering is
            # the deterministic tie-breaker, so either original spelling may
            # become the canonical display name on downgrade.
            assert legacy is not None
            assert legacy[0].lower() == "milk"
            assert legacy[1:] == (True, 1)
            assert connection.execute(
                "SELECT to_regclass('menu_modifier_groups')"
            ).fetchone()[0] is None

        reupgraded = _run_alembic(database_url, "upgrade", "0040")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr
