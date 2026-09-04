"""PostgreSQL proof for migration 0066's shift-close attribution."""

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
def test_0066_preserves_history_and_guards_new_closer_attribution() -> None:
    with _disposable_database("erp_shift_closer_0066") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            ids = _seed_0036_cafe_scope(connection)
            # The shared 0036 fixture contains the duplicate active table bill
            # that 0037 intentionally rejects. Keep the one authoritative bill.
            connection.execute(
                "DELETE FROM orders WHERE id = %s",
                (ids["order_two"],),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0065")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        legacy_closed_at = datetime.now(UTC)
        with psycopg.connect(dsn) as connection:
            # This is a real pre-0066 closed row. The migration must not invent
            # its closer by copying the opener.
            connection.execute(
                "UPDATE shifts SET status = 'closed', closed_at = %s WHERE id = %s",
                (legacy_closed_at, ids["shift"]),
            )
            connection.commit()

        upgraded = _run_alembic(database_url, "upgrade", "0066")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT status, closed_by FROM shifts WHERE id = %s",
                (ids["shift"],),
            ).fetchone() == ("closed", None)

            # Missing historical provenance stays explicitly unknown. A
            # later database write must not manufacture a closer.
            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="shift closed_by is immutable",
            ):
                connection.execute(
                    "UPDATE shifts SET closed_by = %s WHERE id = %s",
                    (ids["void_actor"], ids["shift"]),
                )
            connection.rollback()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="open shift cannot have closed_by attribution",
            ):
                connection.execute(
                    "INSERT INTO shifts "
                    "(id, company_id, branch_id, terminal_id, opened_by, closed_by, "
                    " opened_at, opening_float_minor, expected_minor, status) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, 0, 0, 'open')",
                    (
                        uuid4(),
                        ids["company"],
                        ids["branch"],
                        ids["terminal"],
                        ids["user"],
                        ids["void_actor"],
                        datetime.now(UTC),
                    ),
                )
            connection.rollback()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="closing a shift requires closed_by attribution",
            ):
                connection.execute(
                    "INSERT INTO shifts "
                    "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
                    " closed_at, opening_float_minor, expected_minor, status) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, 0, 0, 'closed')",
                    (
                        uuid4(),
                        ids["company"],
                        ids["branch"],
                        ids["terminal"],
                        ids["user"],
                        datetime.now(UTC),
                        datetime.now(UTC),
                    ),
                )
            connection.rollback()

            inserted_closed_shift_id = uuid4()
            connection.execute(
                "INSERT INTO shifts "
                "(id, company_id, branch_id, terminal_id, opened_by, closed_by, "
                " opened_at, closed_at, opening_float_minor, expected_minor, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 0, 0, 'closed')",
                (
                    inserted_closed_shift_id,
                    ids["company"],
                    ids["branch"],
                    ids["terminal"],
                    ids["user"],
                    ids["void_actor"],
                    datetime.now(UTC),
                    datetime.now(UTC),
                ),
            )
            connection.commit()
            assert connection.execute(
                "SELECT closed_by FROM shifts WHERE id = %s",
                (inserted_closed_shift_id,),
            ).fetchone() == (ids["void_actor"],)

            close_shift_id = uuid4()
            connection.execute(
                "INSERT INTO shifts "
                "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
                " opening_float_minor, expected_minor, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, 0, 0, 'open')",
                (
                    close_shift_id,
                    ids["company"],
                    ids["branch"],
                    ids["terminal"],
                    ids["user"],
                    datetime.now(UTC),
                ),
            )
            connection.commit()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="shift closed_by is immutable",
            ):
                connection.execute(
                    "UPDATE shifts SET closed_by = %s WHERE id = %s",
                    (ids["void_actor"], close_shift_id),
                )
            connection.rollback()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="closing a shift requires closed_by attribution",
            ):
                connection.execute(
                    "UPDATE shifts SET status = 'closed', closed_at = %s WHERE id = %s",
                    (datetime.now(UTC), close_shift_id),
                )
            connection.rollback()

            connection.execute(
                "UPDATE shifts SET status = 'closed', closed_at = %s, closed_by = %s "
                "WHERE id = %s",
                (datetime.now(UTC), ids["void_actor"], close_shift_id),
            )
            connection.commit()
            assert connection.execute(
                "SELECT opened_by, closed_by FROM shifts WHERE id = %s",
                (close_shift_id,),
            ).fetchone() == (ids["user"], ids["void_actor"])

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="closed shift cannot be reopened",
            ):
                connection.execute(
                    "UPDATE shifts SET status = 'open', closed_at = NULL WHERE id = %s",
                    (close_shift_id,),
                )
            connection.rollback()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="shift closed_by is immutable",
            ):
                connection.execute(
                    "UPDATE shifts SET closed_by = %s WHERE id = %s",
                    (ids["ack_actor"], close_shift_id),
                )
            connection.rollback()

            foreign_company_id = uuid4()
            foreign_user_id = uuid4()
            connection.execute(
                "INSERT INTO companies (id, name, gst_registration_type) "
                "VALUES (%s, 'Foreign Company', 'unregistered')",
                (foreign_company_id,),
            )
            connection.execute(
                "INSERT INTO users (id, company_id, email, password_hash, name) "
                "VALUES (%s, %s, %s, 'not-a-real-password-hash', 'Foreign Staff')",
                (
                    foreign_user_id,
                    foreign_company_id,
                    f"foreign-{uuid4()}@test.local",
                ),
            )
            foreign_scope_shift_id = uuid4()
            connection.execute(
                "INSERT INTO shifts "
                "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
                " opening_float_minor, expected_minor, status) "
                "VALUES (%s, %s, %s, %s, %s, %s, 0, 0, 'open')",
                (
                    foreign_scope_shift_id,
                    ids["company"],
                    ids["branch"],
                    ids["terminal"],
                    ids["user"],
                    datetime.now(UTC),
                ),
            )
            connection.commit()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="shift closed_by must belong to the shift company",
            ):
                connection.execute(
                    "INSERT INTO shifts "
                    "(id, company_id, branch_id, terminal_id, opened_by, closed_by, "
                    " opened_at, closed_at, opening_float_minor, expected_minor, status) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 0, 0, 'closed')",
                    (
                        uuid4(),
                        ids["company"],
                        ids["branch"],
                        ids["terminal"],
                        ids["user"],
                        foreign_user_id,
                        datetime.now(UTC),
                        datetime.now(UTC),
                    ),
                )
            connection.rollback()

            with pytest.raises(
                psycopg.errors.CheckViolation,
                match="shift closed_by must belong to the shift company",
            ):
                connection.execute(
                    "UPDATE shifts SET status = 'closed', closed_at = %s, closed_by = %s "
                    "WHERE id = %s",
                    (datetime.now(UTC), foreign_user_id, foreign_scope_shift_id),
                )
            connection.rollback()

        downgraded = _run_alembic(database_url, "downgrade", "0065")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            columns = {
                row[0]
                for row in connection.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_name = 'shifts'"
                ).fetchall()
            }
            assert "closed_by" not in columns
