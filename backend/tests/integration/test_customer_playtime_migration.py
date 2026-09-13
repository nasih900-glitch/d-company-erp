"""0072 preserves old rows and refuses rollback after its new data is used."""

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
def test_0072_preserves_existing_rows_enforces_draft_and_refuses_data_loss() -> None:
    with _disposable_database("erp_customer_playtime_0072") as url:
        result = _run_alembic(url, "upgrade", "0036")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        customer_id = uuid4()
        station_id = uuid4()
        game_id = uuid4()
        with psycopg.connect(dsn) as conn:
            ids = _seed_0036_cafe_scope(conn)
            conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
            conn.commit()

        result = _run_alembic(url, "upgrade", "0071")
        assert result.returncode == 0, result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            conn.execute(
                "INSERT INTO customers(id,company_id,phone,name) "
                "VALUES(%s,%s,'9876543210','Legacy customer')",
                (customer_id, ids["company"]),
            )
            conn.execute(
                "INSERT INTO stations("
                "id,company_id,branch_id,code,name,type,rate_per_hour_minor,"
                "is_active,tax_rate,sac_code,rate_includes_tax) "
                "VALUES(%s,%s,%s,'PLAY-MIG','Play migration','ps5',10000,true,0,'999692',true)",
                (station_id, ids["company"], ids["branch"]),
            )
            conn.execute(
                "INSERT INTO gaming_sessions("
                "id,company_id,station_id,shift_id,opened_by,start_at,"
                "rate_per_hour_minor,status,billing_mode,extra_controllers) "
                "VALUES(%s,%s,%s,%s,%s,%s,10000,'active','hourly',0)",
                (
                    game_id,
                    ids["company"],
                    station_id,
                    ids["shift"],
                    ids["user"],
                    datetime.now(UTC),
                ),
            )
            conn.commit()

        result = _run_alembic(url, "upgrade", "0072")
        assert result.returncode == 0, result.stdout + result.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute(
                "SELECT customer_id,customer_identity_provenance "
                "FROM gaming_sessions WHERE id=%s", (game_id,)
            ).fetchone() == (None, "historical")
            assert conn.execute(
                "SELECT name,phone FROM customers WHERE id=%s", (customer_id,)
            ).fetchone() == ("Legacy customer", "9876543210")

            settings_id = uuid4()
            conn.execute(
                "INSERT INTO gaming_playtime_program_settings(id,company_id) VALUES(%s,%s)",
                (settings_id, ids["company"]),
            )
            conn.commit()
            assert conn.execute(
                "SELECT status,rewards_enabled,messaging_enabled,"
                "threshold_paid_minutes,reward_minutes "
                "FROM gaming_playtime_program_settings WHERE id=%s",
                (settings_id,),
            ).fetchone() == ("draft", False, False, 600, 60)
            with pytest.raises(psycopg.errors.CheckViolation):
                conn.execute(
                    "UPDATE gaming_playtime_program_settings SET rewards_enabled=true WHERE id=%s",
                    (settings_id,),
                )
            conn.rollback()

        result = _run_alembic(url, "downgrade", "0071")
        assert result.returncode != 0
        assert "saved playtime settings exist" in result.stdout + result.stderr

        with psycopg.connect(dsn) as conn:
            conn.execute("DELETE FROM gaming_playtime_program_settings")
            conn.execute(
                "UPDATE gaming_sessions SET customer_identity_provenance='start_unlinked' "
                "WHERE id=%s",
                (game_id,),
            )
            conn.commit()
        result = _run_alembic(url, "downgrade", "0071")
        assert result.returncode != 0
        assert "new customer identity decisions exist" in result.stdout + result.stderr

        with psycopg.connect(dsn) as conn:
            conn.execute(
                "UPDATE gaming_sessions SET customer_identity_provenance='historical' "
                "WHERE id=%s",
                (game_id,),
            )
            conn.execute(
                "UPDATE gaming_sessions SET customer_id=%s WHERE id=%s",
                (customer_id, game_id),
            )
            conn.commit()
        result = _run_alembic(url, "downgrade", "0071")
        assert result.returncode != 0
        assert "linked customer identities exist" in result.stdout + result.stderr

        with psycopg.connect(dsn) as conn:
            assert conn.execute("SELECT version_num FROM alembic_version").fetchone() == ("0072",)
            assert conn.execute(
                "SELECT customer_id FROM gaming_sessions WHERE id=%s", (game_id,)
            ).fetchone() == (customer_id,)
            conn.execute("UPDATE gaming_sessions SET customer_id=NULL WHERE id=%s", (game_id,))
            conn.commit()
        result = _run_alembic(url, "downgrade", "0071")
        assert result.returncode == 0, result.stdout + result.stderr
