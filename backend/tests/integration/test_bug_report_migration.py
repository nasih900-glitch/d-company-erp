"""PostgreSQL proofs for migration 0051's durable-report rollback boundary."""

from __future__ import annotations

from uuid import UUID, uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


def _sync_dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def _insert_report(
    connection: psycopg.Connection,
    *,
    company_id: UUID,
    reporter_id: UUID,
    reporter_email: str,
    branch_id: UUID | None = None,
    branch_name: str | None = None,
    terminal_id: UUID | None = None,
    terminal_name: str | None = None,
) -> UUID:
    report_id = uuid4()
    connection.execute(
        "INSERT INTO bug_reports "
        "(id, company_id, reporter_user_id, reporter_name, reporter_email, "
        "category, severity, title, description, client_platform, branch_id, "
        "branch_name, terminal_id, terminal_name, status, status_changed_at, "
        "status_changed_by) "
        "VALUES (%s, %s, %s, 'Reporter', %s, 'other', 'low', "
        "'Durable migration report', 'This report must survive rollback.', "
        "'web', %s, %s, %s, %s, 'open', now(), %s)",
        (
            report_id,
            company_id,
            reporter_id,
            reporter_email,
            branch_id,
            branch_name,
            terminal_id,
            terminal_name,
            reporter_id,
        ),
    )
    return report_id


@pytest.mark.integration
def test_0051_downgrade_refuses_to_erase_submitted_reports() -> None:
    with _disposable_database("erp_bug_report_durable_downgrade") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0051")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        company_id = uuid4()
        reporter_id = uuid4()
        branch_id = uuid4()
        terminal_id = uuid4()
        branch_name = "Canonical Branch"
        terminal_name = "Canonical Terminal"
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            connection.execute(
                "INSERT INTO companies (id, name) VALUES (%s, 'Bug Report Tenant')",
                (company_id,),
            )
            connection.execute(
                "INSERT INTO users "
                "(id, company_id, email, password_hash, name, status) "
                "VALUES (%s, %s, %s, 'not-a-password', 'Reporter', 'active')",
                (reporter_id, company_id, f"reporter-{uuid4()}@test.local"),
            )
            connection.execute(
                "INSERT INTO branches "
                "(id, company_id, name, code, invoice_series_code) "
                "VALUES (%s, %s, %s, 'BR', 'BR')",
                (branch_id, company_id, branch_name),
            )
            connection.execute(
                "INSERT INTO terminals (id, branch_id, name, device_id) "
                "VALUES (%s, %s, %s, %s)",
                (terminal_id, branch_id, terminal_name, f"terminal-{uuid4()}"),
            )
            connection.commit()

        with psycopg.connect(_sync_dsn(database_url)) as connection:
            reporter_email = connection.execute(
                "SELECT email FROM users WHERE id = %s",
                (reporter_id,),
            ).fetchone()[0]

        with pytest.raises(
            psycopg.errors.RaiseException,
            match="terminal must belong to its company and branch",
        ), psycopg.connect(_sync_dsn(database_url)) as connection:
            _insert_report(
                connection,
                company_id=company_id,
                reporter_id=reporter_id,
                reporter_email=reporter_email,
                terminal_id=terminal_id,
                terminal_name=terminal_name,
            )

        with pytest.raises(
            psycopg.errors.RaiseException,
            match="branch snapshot must match the canonical branch",
        ), psycopg.connect(_sync_dsn(database_url)) as connection:
            _insert_report(
                connection,
                company_id=company_id,
                reporter_id=reporter_id,
                reporter_email=reporter_email,
                branch_id=branch_id,
                branch_name="Forged Branch",
                terminal_id=terminal_id,
                terminal_name=terminal_name,
            )

        with pytest.raises(
            psycopg.errors.RaiseException,
            match="terminal snapshot must match the canonical terminal",
        ), psycopg.connect(_sync_dsn(database_url)) as connection:
            _insert_report(
                connection,
                company_id=company_id,
                reporter_id=reporter_id,
                reporter_email=reporter_email,
                branch_id=branch_id,
                branch_name=branch_name,
                terminal_id=terminal_id,
                terminal_name="Forged Terminal",
            )

        with psycopg.connect(_sync_dsn(database_url)) as connection:
            report_id = _insert_report(
                connection,
                company_id=company_id,
                reporter_id=reporter_id,
                reporter_email=reporter_email,
                branch_id=branch_id,
                branch_name=branch_name,
                terminal_id=terminal_id,
                terminal_name=terminal_name,
            )
            connection.commit()

        # A report freezes the names that were true at submission time. Later
        # location renames or retirement must not block support triage or rewrite
        # that historical context.
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            connection.execute(
                "UPDATE terminals SET name = 'Renamed Terminal' WHERE id = %s",
                (terminal_id,),
            )
            connection.execute(
                "UPDATE branches SET name = 'Renamed Branch', deleted_at = now() WHERE id = %s",
                (branch_id,),
            )
            connection.execute(
                "UPDATE bug_reports "
                "SET status = 'acknowledged', "
                "status_changed_at = status_changed_at + interval '1 second' "
                "WHERE id = %s",
                (report_id,),
            )
            snapshot = connection.execute(
                "SELECT branch_name, terminal_name, status FROM bug_reports WHERE id = %s",
                (report_id,),
            ).fetchone()
            assert snapshot == (branch_name, terminal_name, "acknowledged")
            connection.commit()

        blocked = _run_alembic(database_url, "downgrade", "0050")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "Cannot downgrade 0051 after bug-report activity" in output

        current = _run_alembic(database_url, "current")
        assert current.returncode == 0, current.stdout + current.stderr
        assert "0051" in current.stdout + current.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            assert connection.execute(
                "SELECT id FROM bug_reports WHERE id = %s",
                (report_id,),
            ).fetchone() == (report_id,)


@pytest.mark.integration
def test_0051_empty_schema_can_round_trip() -> None:
    with _disposable_database("erp_bug_report_empty_downgrade") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0051")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        downgraded = _run_alembic(database_url, "downgrade", "0050")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr

        reupgraded = _run_alembic(database_url, "upgrade", "0051")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr
