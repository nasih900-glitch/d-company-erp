"""PostgreSQL round-trip proof for the 0064 gaming tariff schema."""

from __future__ import annotations

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
def test_0064_preserves_legacy_rows_and_enforces_tariff_contract() -> None:
    with _disposable_database("erp_gaming_tariff_0064") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            connection.execute("DELETE FROM orders WHERE id = %s", (ids["order_two"],))
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0063")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        dual_package_id = uuid4()
        vr_package_id = uuid4()
        station_id = uuid4()
        gaming_session_id = uuid4()
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "INSERT INTO stations "
                "(id, company_id, branch_id, code, name, type, rate_per_hour_minor) "
                "VALUES (%s, %s, %s, 'PS5-MIG', 'PS5 Migration', 'ps5', 12000)",
                (station_id, ids["company"], ids["branch"]),
            )
            connection.execute(
                "INSERT INTO gaming_packages "
                "(id, company_id, branch_id, station_type, variant, kind, name, "
                " duration_minutes, price_minor, is_active) "
                "VALUES (%s, %s, %s, 'ps5', 'dual', 'base', 'Legacy Dual', "
                "        60, 15000, true), "
                "       (%s, %s, %s, 'vr', 'dual', 'base', 'Legacy VR', "
                "        60, 20000, true)",
                (
                    dual_package_id,
                    ids["company"],
                    ids["branch"],
                    vr_package_id,
                    ids["company"],
                    ids["branch"],
                ),
            )
            connection.execute(
                "INSERT INTO gaming_sessions "
                "(id, company_id, station_id, opened_by, shift_id, start_at, "
                " rate_per_hour_minor, billing_mode, package_id, "
                " package_price_minor_snapshot, package_duration_minutes_snapshot, "
                " package_variant_snapshot, package_station_type_snapshot, "
                " timer_minutes, amount_minor, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, 12000, 'package', %s, "
                "        15000, 60, 'dual', 'ps5', 60, 15000, 'active')",
                (
                    gaming_session_id,
                    ids["company"],
                    station_id,
                    ids["user"],
                    ids["shift"],
                    datetime.now(UTC),
                    dual_package_id,
                ),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0064")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(dsn) as connection:
            rows = connection.execute(
                "SELECT id, code, pricing_tier, included_players, max_players "
                "FROM gaming_packages ORDER BY id"
            ).fetchall()
            by_id = {row[0]: row[1:] for row in rows}
            assert by_id[dual_package_id] == (
                f"legacy-{dual_package_id}",
                "standard",
                2,
                10,
            )
            assert by_id[vr_package_id] == (
                f"legacy-{vr_package_id}",
                "standard",
                1,
                1,
            )
            assert connection.execute(
                "SELECT package_pricing_tier_snapshot FROM gaming_sessions WHERE id = %s",
                (gaming_session_id,),
            ).fetchone() == (None,)

            canonical_id = uuid4()
            connection.execute(
                "INSERT INTO gaming_packages "
                "(id, company_id, branch_id, code, station_type, variant, pricing_tier, "
                " kind, name, duration_minutes, price_minor, included_players, "
                " max_players, is_active) "
                "VALUES (%s, %s, %s, 'standard-single-session-60m', 'ps5', "
                "        'single', 'standard', 'base', 'Single 60', 60, 12000, "
                "        1, 1, true)",
                (canonical_id, ids["company"], ids["branch"]),
            )
            connection.commit()

            with pytest.raises(psycopg.errors.UniqueViolation):
                connection.execute(
                    "INSERT INTO gaming_packages "
                    "(id, company_id, branch_id, code, station_type, variant, "
                    " pricing_tier, kind, name, duration_minutes, price_minor, "
                    " included_players, max_players, is_active) "
                    "VALUES (%s, %s, %s, 'standard-single-session-60m', 'ps5', "
                    "        'single', 'standard', 'base', 'Duplicate', 60, 12000, "
                    "        1, 1, true)",
                    (uuid4(), ids["company"], ids["branch"]),
                )
            connection.rollback()
            with pytest.raises(psycopg.errors.CheckViolation):
                connection.execute(
                    "UPDATE gaming_packages SET pricing_tier = 'vip' WHERE id = %s",
                    (canonical_id,),
                )
            connection.rollback()
            with pytest.raises(psycopg.errors.CheckViolation):
                connection.execute(
                    "UPDATE gaming_packages SET max_players = 2 WHERE id = %s",
                    (canonical_id,),
                )
            connection.rollback()

        downgraded = _run_alembic(database_url, "downgrade", "0063")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            columns = {
                row[0]
                for row in connection.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_name IN ('gaming_packages', 'gaming_sessions')"
                ).fetchall()
            }
            assert "code" not in columns
            assert "pricing_tier" not in columns
            assert "included_players" not in columns
            assert "max_players" not in columns
            assert "package_pricing_tier_snapshot" not in columns
            assert connection.execute(
                "SELECT count(*) FROM gaming_packages"
            ).fetchone() == (3,)

        reupgraded = _run_alembic(database_url, "upgrade", "0064")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr
