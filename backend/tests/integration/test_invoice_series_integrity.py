"""PostgreSQL proof for explicit, tenant-scoped fiscal invoice series."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import errors
from sqlalchemy import create_engine
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from sqlalchemy.orm import Session

from app.services.pos.pricing import InvoiceNumberService
from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)
from tests.integration.test_membership_migration_downgrade import (
    _seed_base,
    _write_settled_cash_payment,
)


def _sync_dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def _seed_branch(
    connection: psycopg.Connection,
    *,
    company_id: UUID | None = None,
    company_name: str,
    branch_name: str,
    code: str | None,
) -> tuple[UUID, UUID]:
    company_id = company_id or uuid4()
    branch_id = uuid4()
    if connection.execute(
        "SELECT 1 FROM companies WHERE id = %s",
        (company_id,),
    ).fetchone() is None:
        connection.execute(
            "INSERT INTO companies (id, name) VALUES (%s, %s)",
            (company_id, company_name),
        )
    connection.execute(
        "INSERT INTO branches (id, company_id, name, code) VALUES (%s, %s, %s, %s)",
        (branch_id, company_id, branch_name, code),
    )
    return company_id, branch_id


def _insert_order_with_invoice(
    connection: psycopg.Connection,
    *,
    company_id: UUID,
    branch_id: UUID,
    invoice_no: str,
) -> UUID:
    user_id = uuid4()
    terminal_id = uuid4()
    shift_id = uuid4()
    order_id = uuid4()
    now = datetime(2026, 8, 27, 12, tzinfo=UTC)
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-password', 'Invoice tester')",
        (user_id, company_id, f"invoice-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO terminals (id, branch_id, name, device_id) "
        "VALUES (%s, %s, 'POS', %s)",
        (terminal_id, branch_id, f"invoice-{uuid4()}"),
    )
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 'open')",
        (shift_id, company_id, branch_id, terminal_id, user_id, now),
    )
    connection.execute(
        "INSERT INTO orders "
        "(id, company_id, branch_id, terminal_id, shift_id, opened_by, type, "
        "status, opened_at, invoice_no, fiscal_year) "
        "VALUES (%s, %s, %s, %s, %s, %s, 'takeaway', 'paid', %s, %s, '2026-27')",
        (
            order_id,
            company_id,
            branch_id,
            terminal_id,
            shift_id,
            user_id,
            now,
            invoice_no,
        ),
    )
    return order_id


@pytest.mark.integration
def test_0046_rejects_ambiguous_legacy_branch_prefixes() -> None:
    with _disposable_database("erp_invoice_series_collision") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0045")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            company_id, _ = _seed_branch(
                connection,
                company_name="Collision tenant",
                branch_name="Main",
                code="Main",
            )
            _seed_branch(
                connection,
                company_id=company_id,
                company_name="Collision tenant",
                branch_name="Market",
                code="Market",
            )
            connection.commit()

        blocked = _run_alembic(database_url, "upgrade", "0046")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "branch prefixes collide within a company" in output


@pytest.mark.integration
def test_0046_rejects_membership_receipt_that_disagrees_with_branch() -> None:
    """Receipt history must participate even when no POS invoice exists."""

    with _disposable_database("erp_invoice_series_membership_history") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0045")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                seed = _seed_base(session)
                _write_settled_cash_payment(
                    session,
                    seed,
                    label="0046-membership-history",
                )
                session.commit()
        finally:
            engine.dispose()

        blocked = _run_alembic(database_url, "upgrade", "0046")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "fiscal history disagrees with branch identity" in output


@pytest.mark.integration
def test_0046_migrates_safe_series_and_scopes_uniqueness_to_company() -> None:
    with _disposable_database("erp_invoice_series_scope") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0045")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            company_one, branch_one = _seed_branch(
                connection,
                company_name="Tenant one",
                branch_name="Main",
                code="Main",
            )
            _, branch_two = _seed_branch(
                connection,
                company_id=company_one,
                company_name="Tenant one",
                branch_name="Kochi",
                code="Kochi",
            )
            company_two, branch_three = _seed_branch(
                connection,
                company_name="Tenant two",
                branch_name="Main",
                code="Main",
            )
            connection.commit()

        migrated = _run_alembic(database_url, "upgrade", "0046")
        assert migrated.returncode == 0, migrated.stdout + migrated.stderr

        with psycopg.connect(_sync_dsn(database_url), autocommit=True) as connection:
            rows = connection.execute(
                "SELECT company_id, id, invoice_series_code FROM branches ORDER BY id"
            ).fetchall()
            series = {(row[0], row[1]): row[2] for row in rows}
            assert series[(company_one, branch_one)] == "MA"
            assert series[(company_one, branch_two)] == "KO"
            assert series[(company_two, branch_three)] == "MA"

            invoice_no = "D/MA/26-27/00001"
            _insert_order_with_invoice(
                connection,
                company_id=company_one,
                branch_id=branch_one,
                invoice_no=invoice_no,
            )
            _insert_order_with_invoice(
                connection,
                company_id=company_two,
                branch_id=branch_three,
                invoice_no=invoice_no,
            )
            with pytest.raises(errors.UniqueViolation):
                _insert_order_with_invoice(
                    connection,
                    company_id=company_one,
                    branch_id=branch_two,
                    invoice_no=invoice_no,
                )

            with pytest.raises(errors.UniqueViolation):
                connection.execute(
                    "INSERT INTO branches "
                    "(id, company_id, name, code, invoice_series_code) "
                    "VALUES (%s, %s, 'Duplicate series', 'Other', 'MA')",
                    (uuid4(), company_one),
                )

        blocked = _run_alembic(database_url, "downgrade", "0045")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "duplicate numbers now exist across tenants" in output

        current = _run_alembic(database_url, "current")
        assert current.returncode == 0, current.stdout + current.stderr
        assert "0046" in current.stdout + current.stderr


@pytest.mark.integration
@pytest.mark.asyncio
async def test_invoice_allocation_is_concurrent_and_tenant_scoped() -> None:
    with _disposable_database("erp_invoice_series_concurrency") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0045")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_sync_dsn(database_url)) as connection:
            company_one, branch_one = _seed_branch(
                connection,
                company_name="Concurrent tenant one",
                branch_name="Main one",
                code="M1",
            )
            _, branch_two = _seed_branch(
                connection,
                company_id=company_one,
                company_name="Concurrent tenant one",
                branch_name="Main two",
                code="M2",
            )
            company_two, branch_three = _seed_branch(
                connection,
                company_name="Concurrent tenant two",
                branch_name="Main one",
                code="M1",
            )
            connection.commit()
        migrated = _run_alembic(database_url, "upgrade", "0046")
        assert migrated.returncode == 0, migrated.stdout + migrated.stderr

        engine = create_async_engine(
            database_url.replace("postgresql+psycopg://", "postgresql+asyncpg://", 1)
        )
        sessions = async_sessionmaker(engine, expire_on_commit=False)
        issued_at = datetime(2026, 8, 27, 12, tzinfo=UTC)

        async def allocate(company_id: UUID, branch_id: UUID) -> str:
            async with sessions() as session:
                invoice_no, _ = await InvoiceNumberService(session).allocate(
                    company_id=company_id,
                    branch_id=branch_id,
                    at=issued_at,
                )
                await session.commit()
                return invoice_no

        try:
            same_branch, other_branch, other_tenant = await asyncio.gather(
                asyncio.gather(*(allocate(company_one, branch_one) for _ in range(8))),
                allocate(company_one, branch_two),
                allocate(company_two, branch_three),
            )
        finally:
            await engine.dispose()

        assert sorted(same_branch) == [
            f"D/M1/26-27/{sequence:05d}" for sequence in range(1, 9)
        ]
        assert other_branch == "D/M2/26-27/00001"
        # An invoice number can repeat only across independent legal tenants.
        assert other_tenant == "D/M1/26-27/00001"
        assert all(
            len(number) == 16
            for number in [*same_branch, other_branch, other_tenant]
        )

        # Fiscal identity is protected in PostgreSQL too; maintenance SQL or a
        # future write path cannot bypass the Settings service guard.
        with (
            psycopg.connect(_sync_dsn(database_url), autocommit=True) as connection,
            pytest.raises(errors.CheckViolation, match="cannot change"),
        ):
            connection.execute(
                "UPDATE branches SET invoice_series_code = 'Z9' WHERE id = %s",
                (branch_one,),
            )
