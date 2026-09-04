"""PostgreSQL round-trip proof for checkout client identity migration 0065."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


@pytest.mark.integration
def test_0065_preserves_legacy_claims_and_enforces_hashed_identity() -> None:
    with _disposable_database("erp_checkout_client_0065") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            # The 0036 fixture intentionally contains the duplicate table bill
            # rejected by 0037's own test. Keep one so this proof reaches 0065.
            connection.execute(
                "DELETE FROM orders WHERE id = %s",
                (ids["order_two"],),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0064")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        claim_id = uuid4()
        with psycopg.connect(dsn) as connection:
            connection.execute(
                "INSERT INTO order_checkout_claims "
                "(id, order_id, company_id, branch_id, terminal_id, "
                " claimed_by_user_id, token_hash, expires_at, order_total_minor, "
                " due_minor, order_version) "
                "SELECT %s, id, company_id, branch_id, terminal_id, opened_by, %s, "
                "       %s, total_minor, total_minor, checkout_version "
                "FROM orders WHERE id = %s",
                (
                    claim_id,
                    "a" * 64,
                    datetime.now(UTC) + timedelta(minutes=2),
                    ids["direct_open_order"],
                ),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0065")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT client_instance_hash FROM order_checkout_claims WHERE id = %s",
                (claim_id,),
            ).fetchone() == (None,)
            connection.execute(
                "UPDATE order_checkout_claims SET client_instance_hash = %s WHERE id = %s",
                ("b" * 64, claim_id),
            )
            connection.commit()
            with pytest.raises(psycopg.errors.CheckViolation):
                connection.execute(
                    "UPDATE order_checkout_claims SET client_instance_hash = %s "
                    "WHERE id = %s",
                    ("c" * 63, claim_id),
                )
            connection.rollback()

        downgraded = _run_alembic(database_url, "downgrade", "0064")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            columns = {
                row[0]
                for row in connection.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_name = 'order_checkout_claims'"
                ).fetchall()
            }
            assert "client_instance_hash" not in columns
            assert connection.execute(
                "SELECT count(*) FROM order_checkout_claims WHERE id = %s",
                (claim_id,),
            ).fetchone() == (1,)

        reupgraded = _run_alembic(database_url, "upgrade", "0065")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT client_instance_hash FROM order_checkout_claims WHERE id = %s",
                (claim_id,),
            ).fetchone() == (None,)
