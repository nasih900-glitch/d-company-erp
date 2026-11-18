"""Actual PostgreSQL evidence for additive, tenant-bound draft salary history."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest
from psycopg import errors, sql

from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic
from tests.integration.test_staff_tenant_integrity import (
    _insert_attendance,
    _seed_scope_world,
    _sync_dsn,
)


def _legacy_rows(connection) -> dict[str, str]:
    tables = connection.execute(
        "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
        "AND tablename NOT IN ('alembic_version', 'staff_work_policies') ORDER BY tablename"
    ).fetchall()
    result = {}
    for (name,) in tables:
        result[name] = connection.execute(
            sql.SQL(
                "SELECT md5(COALESCE("
                "jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]')) "
                "FROM {} t"
            ).format(sql.Identifier(name))
        ).fetchone()[0]
    return result


def _insert_policy(connection, ids, **changes):
    values = {
        "id": uuid4(),
        "company_id": ids["company_a"],
        "user_id": ids["user_a"],
        "actor_user_id": ids["grantor_a"],
        "revision": 1,
        "effective_from": "2026-11-01",
        "monthly_salary_minor": 1_200_000,
        "daily_presence_minutes": 720,
        "daily_break_minutes": 60,
        "working_weekday_mask": 127,
        "overtime_multiplier_basis_points": None,
        "pay_status": "draft",
        "reason": "Owner recorded draft agreement; pay remains unapproved",
    }
    values.update(changes)
    connection.execute(
        sql.SQL("INSERT INTO staff_work_policies ({}) VALUES ({})").format(
            sql.SQL(",").join(map(sql.Identifier, values)),
            sql.SQL(",").join(sql.Placeholder() for _ in values),
        ),
        list(values.values()),
    )
    return values["id"]


@pytest.mark.integration
def test_0094_preserves_history_and_enforces_immutable_tenant_bound_drafts():
    with _disposable_database("erp_staff_policy_0094") as url:
        prior = _run_alembic(url, "upgrade", "0093")
        assert prior.returncode == 0, prior.stdout + prior.stderr
        with psycopg.connect(_sync_dsn(url)) as connection:
            ids = _seed_scope_world(connection)
            _insert_attendance(connection, ids, clock_out_at=datetime(2026, 8, 27, 20, tzinfo=UTC))
            connection.execute(
                "INSERT INTO payroll_entries "
                "(id, company_id, user_id, period_start, period_end, base_minor) "
                "VALUES (%s, %s, %s, %s, %s, %s)",
                (
                    uuid4(),
                    ids["company_a"],
                    ids["user_a"],
                    datetime(2026, 8, 1, tzinfo=UTC),
                    datetime(2026, 9, 1, tzinfo=UTC),
                    33300,
                ),
            )
            connection.commit()
            before = _legacy_rows(connection)

        for command, target in (("upgrade", "0094"), ("downgrade", "0093"), ("upgrade", "0094")):
            result = _run_alembic(url, command, target)
            assert result.returncode == 0, result.stdout + result.stderr
            with psycopg.connect(_sync_dsn(url)) as connection:
                assert _legacy_rows(connection) == before
                if target == "0094":
                    assert (
                        connection.execute("SELECT count(*) FROM staff_work_policies").fetchone()[0]
                        == 0
                    )

        with psycopg.connect(_sync_dsn(url)) as connection:
            policy_id = _insert_policy(connection, ids)
            connection.commit()
        invalid = (
            {"company_id": ids["company_b"]},
            {"user_id": ids["user_b"]},
            {"actor_user_id": ids["user_b"]},
            {"revision": 0},
            {"monthly_salary_minor": -1},
            {"daily_presence_minutes": 1441},
            {"daily_break_minutes": 720},
            {"working_weekday_mask": 0},
            {"pay_status": "active"},
            {"overtime_multiplier_basis_points": 0},
            {"reason": " "},
        )
        for changes in invalid:
            with (
                psycopg.connect(_sync_dsn(url)) as connection,
                pytest.raises(errors.CheckViolation),
            ):
                _insert_policy(connection, ids, **{"revision": 2, **changes})
        with psycopg.connect(_sync_dsn(url)) as connection, pytest.raises(errors.UniqueViolation):
            _insert_policy(connection, ids)
        for statement in (
            "UPDATE staff_work_policies SET monthly_salary_minor = 1 WHERE id = %s",
            "DELETE FROM staff_work_policies WHERE id = %s",
            "TRUNCATE staff_work_policies",
        ):
            with (
                psycopg.connect(_sync_dsn(url)) as connection,
                pytest.raises(errors.CheckViolation, match="immutable"),
            ):
                connection.execute(statement, (policy_id,) if "%s" in statement else None)
        refused = _run_alembic(url, "downgrade", "0093")
        assert refused.returncode != 0
        assert "cannot drop retained staff work policy history" in refused.stdout + refused.stderr
        with psycopg.connect(_sync_dsn(url)) as connection:
            assert (
                connection.execute("SELECT version_num FROM alembic_version").fetchone()[0]
                == "0094"
            )
            assert connection.execute("SELECT count(*) FROM staff_work_policies").fetchone()[0] == 1
            assert _legacy_rows(connection) == before
