"""Destructive migration guards, exercised only on a nested disposable database."""

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
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from app.models import (
    Branch,
    Company,
    Customer,
    Order,
    Payment,
    PosRefundCashHandoff,
    PosRefundCashHandoffCompletion,
    PosRefundProviderPayoutStart,
    PosRefundRequest,
    Refund,
    Shift,
    Terminal,
    User,
)

_BACKEND_ROOT = Path(__file__).resolve().parents[2]


def _run_alembic(database_url: str, *args: str) -> subprocess.CompletedProcess[str]:
    env = {**os.environ, "DATABASE_URL": database_url}
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        cwd=_BACKEND_ROOT,
        env=env,
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
            admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database_name)))
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
            # The assertion failure remains primary; this is always a uniquely
            # named local database containing only test fixtures.
            pass


@pytest.mark.integration
def test_0034_downgrade_refuses_to_drop_normalized_order_customer() -> None:
    """A rollback must fail closed once orders.customer_id carries provenance."""
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

    database_name = f"erp_migration_guard_{uuid4().hex[:16]}"
    sync_source_url = source_url.set(drivername="postgresql+psycopg")
    test_url = sync_source_url.set(database=database_name)
    admin_dsn = sync_source_url.set(
        drivername="postgresql",
        database="postgres",
    ).render_as_string(hide_password=False)
    try:
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database_name)))
    except Exception as exc:
        pytest.skip(f"local role cannot create a disposable database: {exc}")

    database_url = test_url.render_as_string(hide_password=False)
    try:
        upgraded = _run_alembic(database_url, "upgrade", "0034")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                company = Company(id=uuid4(), name="0034 downgrade guard")
                branch = Branch(id=uuid4(), company_id=company.id, name="Main")
                terminal = Terminal(
                    id=uuid4(),
                    branch_id=branch.id,
                    name="Guard terminal",
                    device_id=f"guard-{uuid4()}",
                )
                owner = User(
                    id=uuid4(),
                    company_id=company.id,
                    email=f"guard-{uuid4().hex[:8]}@test.local",
                    name="Guard owner",
                    password_hash="not-used-by-migration-test",
                    status="active",
                )
                customer = Customer(
                    id=uuid4(),
                    company_id=company.id,
                    name="Guard customer",
                    phone=f"7{uuid4().int % 10**9:09d}",
                )
                # These models expose IDs instead of relationships; make the
                # FK order explicit so this test proves the migration rather
                # than SQLAlchemy's unit-of-work dependency heuristics.
                session.add(company)
                session.flush()
                session.add_all([branch, owner, customer])
                session.flush()
                session.add(terminal)
                session.flush()
                now = datetime.now(UTC)
                shift = Shift(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    opened_by=owner.id,
                    opened_at=now,
                    opening_float_minor=0,
                    expected_minor=0,
                    status="open",
                )
                session.add(shift)
                session.flush()
                session.add(
                    Order(
                        id=uuid4(),
                        company_id=company.id,
                        branch_id=branch.id,
                        terminal_id=terminal.id,
                        shift_id=shift.id,
                        opened_by=owner.id,
                        customer_id=customer.id,
                        type="takeaway",
                        status="paid",
                        subtotal_minor=100,
                        total_minor=100,
                        opened_at=now,
                        closed_at=now,
                    )
                )
                session.commit()
        finally:
            engine.dispose()

        blocked = _run_alembic(database_url, "downgrade", "0033")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0, output
        assert "Cannot downgrade 0034: customer or refund provenance exists" in output

        current = _run_alembic(database_url, "current")
        assert current.returncode == 0, current.stdout + current.stderr
        assert "0034" in current.stdout + current.stderr
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
            # The assertion failure remains primary; the database name is
            # unique and contains no production data if local cleanup fails.
            pass


@pytest.mark.integration
@pytest.mark.parametrize("legacy_fault", ["orphan_provider", "client_manager"])
def test_0036_upgrade_refuses_untrusted_legacy_refund_provenance(
    legacy_fault: str,
) -> None:
    """Never infer money state or a second approver from unsafe 0034 inputs."""
    with _disposable_database(f"erp_0036_{legacy_fault}_guard") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0035")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                company = Company(id=uuid4(), name="0036 orphan provider guard")
                branch = Branch(id=uuid4(), company_id=company.id, name="Main")
                terminal = Terminal(
                    id=uuid4(),
                    branch_id=branch.id,
                    name="Orphan provider terminal",
                    device_id=f"orphan-provider-{uuid4()}",
                )
                owner = User(
                    id=uuid4(),
                    company_id=company.id,
                    email=f"orphan-provider-{uuid4().hex[:8]}@test.local",
                    name="Orphan provider owner",
                    password_hash="not-used-by-migration-test",
                    status="active",
                )
                session.add(company)
                session.flush()
                session.add_all([branch, owner])
                session.flush()
                session.add(terminal)
                session.flush()

                now = datetime.now(UTC)
                shift = Shift(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    opened_by=owner.id,
                    opened_at=now,
                    opening_float_minor=0,
                    expected_minor=0,
                    status="open",
                )
                session.add(shift)
                session.flush()
                order = Order(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    shift_id=shift.id,
                    opened_by=owner.id,
                    type="takeaway",
                    status="paid",
                    subtotal_minor=10_000,
                    total_minor=10_000,
                    opened_at=now,
                    closed_at=now,
                )
                session.add(order)
                session.flush()
                session.add(
                    PosRefundRequest(
                        id=uuid4(),
                        company_id=company.id,
                        order_id=order.id,
                        branch_id=branch.id,
                        terminal_id=terminal.id,
                        shift_id=shift.id,
                        approved_by=owner.id,
                        manager_override_user_id=(
                            owner.id if legacy_fault == "client_manager" else None
                        ),
                        reason_code="CUSTOMER_REQUEST",
                        amount_minor=10_000,
                        mode="original" if legacy_fault == "orphan_provider" else "cash",
                        settlement_method=(
                            "upi" if legacy_fault == "orphan_provider" else "cash"
                        ),
                        order_paid_snapshot_minor=10_000,
                        order_refundable_snapshot_minor=10_000,
                        accepted_at=now,
                        external_reference=(
                            "legacy-provider-success"
                            if legacy_fault == "orphan_provider"
                            else None
                        ),
                        provider_settled_at=(
                            now if legacy_fault == "orphan_provider" else None
                        ),
                        client_action_id=f"orphan-provider:{uuid4()}",
                        idempotency_key=f"orphan-provider:{uuid4()}",
                        note="Legacy provider evidence without accounting",
                    )
                )
                session.commit()
        finally:
            engine.dispose()

        blocked = _run_alembic(database_url, "upgrade", "0036")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0, output
        expected_error = (
            "invalid legacy POS refund transition facts"
            if legacy_fault == "orphan_provider"
            else "invalid legacy POS refund root provenance"
        )
        assert expected_error in output

        current = _run_alembic(database_url, "current")
        assert current.returncode == 0, current.stdout + current.stderr
        assert "0035" in current.stdout + current.stderr


@pytest.mark.integration
def test_0036_preserves_legacy_refund_but_rejects_forward_unlinked_writes() -> None:
    """NOT VALID preserves history but protects every post-upgrade write."""
    with _disposable_database("erp_0036_legacy_ltv") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0035")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        refund_id = uuid4()
        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                company = Company(id=uuid4(), name="0036 legacy LTV guard")
                branch = Branch(id=uuid4(), company_id=company.id, name="Main")
                terminal = Terminal(
                    id=uuid4(),
                    branch_id=branch.id,
                    name="Legacy LTV terminal",
                    device_id=f"legacy-ltv-{uuid4()}",
                )
                owner = User(
                    id=uuid4(),
                    company_id=company.id,
                    email=f"legacy-ltv-{uuid4().hex[:8]}@test.local",
                    name="Legacy LTV owner",
                    password_hash="not-used-by-migration-test",
                    status="active",
                )
                customer = Customer(
                    id=uuid4(),
                    company_id=company.id,
                    name="Legacy LTV customer",
                    phone=f"5{uuid4().int % 10**9:09d}",
                    total_spent_minor=10_000,
                )
                session.add(company)
                session.flush()
                session.add_all([branch, owner, customer])
                session.flush()
                session.add(terminal)
                session.flush()
                now = datetime.now(UTC)
                shift = Shift(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    opened_by=owner.id,
                    opened_at=now,
                    opening_float_minor=0,
                    expected_minor=10_000,
                    status="open",
                )
                session.add(shift)
                session.flush()
                order = Order(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    shift_id=shift.id,
                    opened_by=owner.id,
                    customer_id=customer.id,
                    type="takeaway",
                    status="paid",
                    subtotal_minor=10_000,
                    total_minor=10_000,
                    opened_at=now,
                    closed_at=now,
                )
                session.add(order)
                session.flush()
                session.add(
                    Payment(
                        id=uuid4(),
                        order_id=order.id,
                        shift_id=shift.id,
                        method="cash",
                        amount_minor=10_000,
                        paid_at=now,
                    )
                )
                session.flush()
                # Use the exact pre-0036 shape: the reconciliation-state column
                # does not exist yet, and no financial/customer value is inferred.
                session.execute(
                    text(
                        "INSERT INTO refunds (id, order_id, approved_by, reason_code, "
                        "amount_minor, mode) VALUES (:id, :order_id, :actor, :reason, "
                        ":amount, 'cash')"
                    ),
                    {
                        "id": refund_id,
                        "order_id": order.id,
                        "actor": owner.id,
                        "reason": "LEGACY_HISTORY",
                        "amount": 1_000,
                    },
                )
                session.commit()
        finally:
            engine.dispose()

        forward = _run_alembic(database_url, "upgrade", "0036")
        assert forward.returncode == 0, forward.stdout + forward.stderr

        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                state = session.execute(
                    text(
                        "SELECT customer_spend_reconciled FROM refunds WHERE id = :id"
                    ),
                    {"id": refund_id},
                ).scalar_one()
                source_state_nullable = session.execute(
                    text(
                        "SELECT is_nullable FROM information_schema.columns "
                        "WHERE table_name = 'customer_spend_reconciliations' "
                        "AND column_name = 'source_reconciliation_state'"
                    )
                ).scalar_one()
                linkage = session.execute(
                    text(
                        "SELECT convalidated, pg_get_constraintdef(oid) "
                        "FROM pg_constraint "
                        "WHERE conrelid = 'refunds'::regclass "
                        "AND conname = 'ck_refund_forward_write_linkage'"
                    )
                ).one()
                assert state is None
                assert source_state_nullable == "NO"
                assert linkage.convalidated is False
                assert "request_id IS NOT NULL" in linkage.pg_get_constraintdef

            # The transition trigger deliberately preserves existing NULL
            # rows; the NOT VALID constraint is the independent forward-write
            # boundary and must reject a brand-new unlinked refund.
            with Session(engine) as session:
                with pytest.raises(
                    IntegrityError, match="ck_refund_forward_write_linkage"
                ):
                    session.execute(
                        text(
                            "INSERT INTO refunds (id, order_id, approved_by, "
                            "reason_code, amount_minor, mode) "
                            "SELECT gen_random_uuid(), order_id, approved_by, "
                            "'FORWARD_BYPASS', 1, 'cash' FROM refunds "
                            "WHERE id = :legacy_id"
                        ),
                        {"legacy_id": refund_id},
                    )
                    session.commit()
                session.rollback()

            # Prove the check itself covers UPDATE, independently of the
            # append-only trigger that normally rejects every refund update
            # even earlier in the executor pipeline.
            with Session(engine) as session:
                with pytest.raises(
                    IntegrityError, match="ck_refund_forward_write_linkage"
                ):
                    session.execute(
                        text(
                            "ALTER TABLE refunds DISABLE TRIGGER "
                            "trg_refunds_immutable"
                        )
                    )
                    session.execute(
                        text(
                            "UPDATE refunds SET reason_code = reason_code "
                            "WHERE id = :legacy_id"
                        ),
                        {"legacy_id": refund_id},
                    )
                    session.commit()
                # The failed transaction also rolls back the test-only trigger
                # disable, leaving the production invariant enabled.
                session.rollback()
        finally:
            engine.dispose()

        downgraded = _run_alembic(database_url, "downgrade", "0035")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                constraint_exists = session.execute(
                    text(
                        "SELECT EXISTS (SELECT 1 FROM pg_constraint "
                        "WHERE conrelid = 'refunds'::regclass "
                        "AND conname = 'ck_refund_forward_write_linkage')"
                    )
                ).scalar_one()
                legacy_still_exists = session.execute(
                    text("SELECT EXISTS (SELECT 1 FROM refunds WHERE id = :id)"),
                    {"id": refund_id},
                ).scalar_one()
                assert constraint_exists is False
                assert legacy_still_exists is True
        finally:
            engine.dispose()


@pytest.mark.integration
@pytest.mark.parametrize(
    "workflow_state", ["provider_start", "cash_completion", "cash_settlement"]
)
def test_0036_downgrade_refuses_to_drop_forward_workflow_history(
    workflow_state: str,
) -> None:
    """Neither provider actions nor forward settlement facts may be erased."""
    with _disposable_database("erp_0036_downgrade_guard") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0036")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                company = Company(id=uuid4(), name="0036 downgrade guard")
                branch = Branch(id=uuid4(), company_id=company.id, name="Main")
                terminal = Terminal(
                    id=uuid4(),
                    branch_id=branch.id,
                    name="Guard terminal",
                    device_id=f"guard-0036-{uuid4()}",
                )
                owner = User(
                    id=uuid4(),
                    company_id=company.id,
                    email=f"guard-0036-{uuid4().hex[:8]}@test.local",
                    name="Guard owner",
                    password_hash="not-used-by-migration-test",
                    status="active",
                )
                customer = Customer(
                    id=uuid4(),
                    company_id=company.id,
                    name="Guard customer",
                    phone=f"6{uuid4().int % 10**9:09d}",
                )
                session.add(company)
                session.flush()
                session.add_all([branch, owner, customer])
                session.flush()
                session.add(terminal)
                session.flush()

                now = datetime.now(UTC)
                shift = Shift(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    opened_by=owner.id,
                    opened_at=now,
                    opening_float_minor=0,
                    expected_minor=0,
                    status="open",
                )
                session.add(shift)
                session.flush()
                order = Order(
                    id=uuid4(),
                    company_id=company.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    shift_id=shift.id,
                    opened_by=owner.id,
                    customer_id=customer.id,
                    type="takeaway",
                    status="paid",
                    subtotal_minor=10_000,
                    total_minor=10_000,
                    opened_at=now,
                    closed_at=now,
                )
                session.add(order)
                session.flush()
                session.add(
                    Payment(
                        id=uuid4(),
                        order_id=order.id,
                        shift_id=shift.id,
                        method=(
                            "upi" if workflow_state == "provider_start" else "cash"
                        ),
                        amount_minor=10_000,
                        paid_at=now,
                        ref_external="sale-reference",
                    )
                )
                session.flush()
                request = PosRefundRequest(
                    id=uuid4(),
                    company_id=company.id,
                    order_id=order.id,
                    branch_id=branch.id,
                    terminal_id=terminal.id,
                    shift_id=shift.id,
                    approved_by=owner.id,
                    manager_override_user_id=None,
                    reason_code="CUSTOMER_REQUEST",
                    amount_minor=10_000,
                    mode="original" if workflow_state == "provider_start" else "cash",
                    settlement_method=(
                        "upi" if workflow_state == "provider_start" else "cash"
                    ),
                    order_paid_snapshot_minor=10_000,
                    order_refundable_snapshot_minor=10_000,
                    accepted_at=now,
                    external_reference=None,
                    provider_settled_at=None,
                    client_action_id=f"downgrade-request:{uuid4()}",
                    idempotency_key=f"downgrade-request:{uuid4()}",
                    note="0036 downgrade proof",
                )
                session.add(request)
                session.flush()
                if workflow_state == "provider_start":
                    session.add(
                        PosRefundProviderPayoutStart(
                            id=uuid4(),
                            company_id=company.id,
                            refund_request_id=request.id,
                            branch_id=branch.id,
                            terminal_id=terminal.id,
                            shift_id=shift.id,
                            started_at=now,
                            started_by=owner.id,
                            idempotency_key=f"downgrade-provider-start:{uuid4()}",
                        )
                    )
                else:
                    session.add(
                        PosRefundCashHandoff(
                            id=uuid4(),
                            company_id=company.id,
                            refund_request_id=request.id,
                            branch_id=branch.id,
                            terminal_id=terminal.id,
                            shift_id=shift.id,
                            started_at=now,
                            started_by=owner.id,
                            idempotency_key=f"downgrade-cash-start:{uuid4()}",
                        )
                    )
                    session.flush()
                    session.add(
                        PosRefundCashHandoffCompletion(
                            id=uuid4(),
                            company_id=company.id,
                            refund_request_id=request.id,
                            branch_id=branch.id,
                            terminal_id=terminal.id,
                            shift_id=shift.id,
                            handed_over_at=now,
                            recorded_at=now,
                            recorded_by=owner.id,
                            captured_time_reconciled=True,
                            idempotency_key=f"downgrade-cash-complete:{uuid4()}",
                        )
                    )
                    session.flush()
                    if workflow_state == "cash_settlement":
                        session.add(
                            Refund(
                                id=uuid4(),
                                request_id=request.id,
                                order_id=order.id,
                                company_id=company.id,
                                branch_id=branch.id,
                                terminal_id=terminal.id,
                                settlement_shift_id=shift.id,
                                approved_by=owner.id,
                                manager_override_user_id=None,
                                reason_code="CUSTOMER_REQUEST",
                                amount_minor=10_000,
                                mode="cash",
                                settlement_method="cash",
                                settled_at=now,
                                settled_by=owner.id,
                                external_reference=None,
                                provider_settled_at=None,
                                client_occurred_at=now,
                                captured_time_reconciled=True,
                                provider_evidence_reconciled=None,
                                settlement_idempotency_key=(
                                    f"downgrade-settle:{uuid4()}"
                                ),
                                receipt_no=(
                                    f"R/RF/26-27/{uuid4().int % 100000:05d}"
                                ),
                                receipt_fiscal_year="2026-27",
                                receipt_issued_at=now,
                                customer_spend_reconciled=True,
                                note="0036 downgrade proof",
                            )
                        )
                session.commit()
        finally:
            engine.dispose()

        blocked = _run_alembic(database_url, "downgrade", "0035")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0, output
        assert "Cannot downgrade 0036 after new POS refund workflow activity" in output

        current = _run_alembic(database_url, "current")
        assert current.returncode == 0, current.stdout + current.stderr
        assert "0036" in current.stdout + current.stderr
