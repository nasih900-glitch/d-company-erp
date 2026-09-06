"""Fail-closed 0035 rollback proofs on nested disposable PostgreSQL databases."""

from __future__ import annotations

import os
import subprocess
import sys
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID, uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import Session

from app.models import (
    Company,
    Customer,
    CustomerMembership,
    MembershipPayment,
    MembershipPaymentAttemptResolution,
    MembershipPaymentCashCollection,
    MembershipPaymentCompletion,
    MembershipPaymentProviderAction,
    MembershipPaymentRequest,
    MembershipRefund,
    MembershipRefundAttemptRecovery,
    MembershipRefundCashHandoff,
    MembershipRefundCompletion,
    MembershipRefundProviderAction,
    MembershipTier,
    User,
)

_BACKEND_ROOT = Path(__file__).resolve().parents[2]


def _insert_schema_0034_branch(
    session: Session,
    *,
    company_id: UUID,
) -> SimpleNamespace:
    """Insert through the historical schema, not the current Branch ORM.

    These tests deliberately pin PostgreSQL to revision 0034.  The current
    model contains columns added much later, so flushing it would make the
    fixture fail on model/schema skew before exercising the migration guard.
    """

    branch_id = uuid4()
    session.execute(
        text(
            "INSERT INTO branches (id, company_id, name) "
            "VALUES (:id, :company_id, 'Main')"
        ),
        {"id": branch_id, "company_id": company_id},
    )
    return SimpleNamespace(id=branch_id)


def _run_alembic(database_url: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        cwd=_BACKEND_ROOT,
        env={**os.environ, "DATABASE_URL": database_url},
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
    database_name = f"{prefix}_{uuid4().hex[:12]}"
    sync_source_url = source_url.set(drivername="postgresql+psycopg")
    database_url = sync_source_url.set(database=database_name).render_as_string(
        hide_password=False
    )
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
        yield database_url
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
            pass


@dataclass(frozen=True)
class _Seed:
    company_id: UUID
    branch_id: UUID
    terminal_id: UUID
    owner_id: UUID
    customer_id: UUID
    tier_id: UUID
    shift_id: UUID
    now: datetime


@dataclass(frozen=True)
class _PaymentWorkflow:
    request_id: UUID
    action_id: UUID
    completion_id: UUID
    membership_id: UUID
    payment_id: UUID


def _seed_base(session: Session) -> _Seed:
    now = datetime.now(UTC).replace(microsecond=0)
    company = Company(id=uuid4(), name="0035 downgrade guard")
    session.add(company)
    session.flush()
    branch = _insert_schema_0034_branch(session, company_id=company.id)
    terminal_id = uuid4()
    owner = User(
        id=uuid4(),
        company_id=company.id,
        email=f"membership-guard-{uuid4().hex[:8]}@test.local",
        name="Guard owner",
        password_hash="not-used-by-migration-test",
        status="active",
    )
    customer = Customer(
        id=uuid4(),
        company_id=company.id,
        name="Guard customer",
        phone=f"6{uuid4().int % 10**9:09d}",
        total_spent_minor=0,
    )
    tier = MembershipTier(
        id=uuid4(),
        company_id=company.id,
        code=f"G{uuid4().hex[:7].upper()}",
        name="Guard monthly tier",
        monthly_price_minor=199_900,
        annual_price_minor=None,
        food_discount_pct=0,
        gaming_discount_pct=0,
        hookah_discount_pct=0,
        point_multiplier=1,
        free_gaming_minutes_per_week=0,
        free_hookah_per_month=0,
        priority_booking=False,
        sort_order=0,
    )
    session.add_all([owner, customer, tier])
    session.flush()
    # The current Terminal ORM includes ``purpose`` from revision 0052, while
    # these rollback proofs intentionally hold PostgreSQL at 0034/0035/0046.
    # Insert only columns that existed at those historical boundaries so the
    # test continues to exercise the target migration instead of failing on
    # deliberate model/schema skew.
    session.execute(
        text(
            "INSERT INTO terminals (id, branch_id, name, device_id) "
            "VALUES (:id, :branch_id, 'Guard terminal', :device_id)"
        ),
        {
            "id": terminal_id,
            "branch_id": branch.id,
            "device_id": f"membership-guard-{uuid4()}",
        },
    )
    # Shift gained ``closed_by`` in 0066. These rollback proofs deliberately
    # hold PostgreSQL at older revisions, so current ORM metadata cannot seed
    # the historical table. Keep this insert pinned to columns present at the
    # test boundary, just like the Terminal insert above.
    shift_id = uuid4()
    session.execute(
        text(
            "INSERT INTO shifts "
            "(id, company_id, branch_id, terminal_id, opened_by, opened_at, "
            " opening_float_minor, expected_minor, status) "
            "VALUES (:id, :company_id, :branch_id, :terminal_id, :opened_by, "
            " :opened_at, 500000, 500000, 'open')"
        ),
        {
            "id": shift_id,
            "company_id": company.id,
            "branch_id": branch.id,
            "terminal_id": terminal_id,
            "opened_by": owner.id,
            "opened_at": now - timedelta(minutes=5),
        },
    )
    session.flush()
    return _Seed(
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal_id,
        owner_id=owner.id,
        customer_id=customer.id,
        tier_id=tier.id,
        shift_id=shift_id,
        now=now,
    )


def _write_cash_request_action(
    session: Session,
    seed: _Seed,
    *,
    label: str,
) -> tuple[MembershipPaymentRequest, MembershipPaymentCashCollection]:
    request = MembershipPaymentRequest(
        id=uuid4(),
        company_id=seed.company_id,
        customer_id=seed.customer_id,
        tier_id=seed.tier_id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        billing_cycle="monthly",
        method="cash",
        amount_minor=199_900,
        customer_name_snapshot="Guard customer",
        customer_phone_snapshot="6000000000",
        tier_code_snapshot="GUARD",
        tier_name_snapshot="Guard monthly tier",
        accepted_at=seed.now,
        prepared_by=seed.owner_id,
        client_action_id=f"{label}:prepare:{uuid4()}",
        idempotency_key=f"{label}:prepare:{uuid4()}",
    )
    session.add(request)
    session.flush()
    action = MembershipPaymentCashCollection(
        id=uuid4(),
        company_id=seed.company_id,
        request_id=request.id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        started_at=seed.now,
        started_by=seed.owner_id,
        idempotency_key=f"{label}:action:{uuid4()}",
    )
    session.add(action)
    session.flush()
    return request, action


def _write_settled_cash_payment(
    session: Session,
    seed: _Seed,
    *,
    label: str,
) -> _PaymentWorkflow:
    request, action = _write_cash_request_action(session, seed, label=label)
    completion = MembershipPaymentCompletion(
        id=uuid4(),
        company_id=seed.company_id,
        request_id=request.id,
        cash_collection_id=action.id,
        provider_action_id=None,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        method="cash",
        amount_minor=199_900,
        completed_at=seed.now,
        completed_by=seed.owner_id,
        external_reference=None,
        provider_evidence_reconciled=True,
        evidence_occurred_at=seed.now,
        evidence_time_untrusted=False,
        action_takeover_confirmed=False,
        action_takeover_reason=None,
        idempotency_key=f"{label}:completion:{uuid4()}",
    )
    session.add(completion)
    session.flush()
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=seed.customer_id,
        tier_id=seed.tier_id,
        billing_cycle="monthly",
        starts_at=seed.now,
        expires_at=seed.now + timedelta(days=30),
        auto_renew=False,
        amount_paid_minor=199_900,
    )
    session.add(membership)
    session.flush()
    payment = MembershipPayment(
        id=uuid4(),
        company_id=seed.company_id,
        membership_id=membership.id,
        request_id=request.id,
        completion_id=completion.id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        method="cash",
        amount_minor=199_900,
        paid_at=seed.now,
        created_by=seed.owner_id,
        idempotency_key=f"{label}:payment:{uuid4()}",
        receipt_no=f"M/GUARD/26-27/{uuid4().hex[:8]}",
        receipt_fiscal_year="2026-27",
        receipt_issued_at=seed.now,
        external_reference=None,
        provider_evidence_reconciled=True,
        evidence_occurred_at=seed.now,
        evidence_time_untrusted=False,
        customer_spend_reconciled=False,
        action_takeover_confirmed=False,
        action_takeover_reason=None,
    )
    session.add(payment)
    session.flush()
    return _PaymentWorkflow(
        request_id=request.id,
        action_id=action.id,
        completion_id=completion.id,
        membership_id=membership.id,
        payment_id=payment.id,
    )


def _write_settled_provider_payment(
    session: Session,
    seed: _Seed,
    *,
    label: str,
) -> _PaymentWorkflow:
    request = MembershipPaymentRequest(
        id=uuid4(),
        company_id=seed.company_id,
        customer_id=seed.customer_id,
        tier_id=seed.tier_id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        billing_cycle="monthly",
        method="upi",
        amount_minor=199_900,
        customer_name_snapshot="Guard customer",
        customer_phone_snapshot="6000000000",
        tier_code_snapshot="GUARD",
        tier_name_snapshot="Guard monthly tier",
        accepted_at=seed.now,
        prepared_by=seed.owner_id,
        client_action_id=f"{label}:prepare:{uuid4()}",
        idempotency_key=f"{label}:prepare:{uuid4()}",
    )
    session.add(request)
    session.flush()
    action = MembershipPaymentProviderAction(
        id=uuid4(),
        company_id=seed.company_id,
        request_id=request.id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        method="upi",
        started_at=seed.now,
        started_by=seed.owner_id,
        idempotency_key=f"{label}:action:{uuid4()}",
    )
    session.add(action)
    session.flush()
    external_reference = f"UPI-{uuid4().hex}"
    completion = MembershipPaymentCompletion(
        id=uuid4(),
        company_id=seed.company_id,
        request_id=request.id,
        cash_collection_id=None,
        provider_action_id=action.id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        method="upi",
        amount_minor=199_900,
        completed_at=seed.now,
        completed_by=seed.owner_id,
        external_reference=external_reference,
        provider_evidence_reconciled=True,
        evidence_occurred_at=seed.now,
        evidence_time_untrusted=False,
        action_takeover_confirmed=False,
        action_takeover_reason=None,
        idempotency_key=f"{label}:completion:{uuid4()}",
    )
    session.add(completion)
    session.flush()
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=seed.customer_id,
        tier_id=seed.tier_id,
        billing_cycle="monthly",
        starts_at=seed.now,
        expires_at=seed.now + timedelta(days=30),
        auto_renew=False,
        amount_paid_minor=199_900,
    )
    session.add(membership)
    session.flush()
    payment = MembershipPayment(
        id=uuid4(),
        company_id=seed.company_id,
        membership_id=membership.id,
        request_id=request.id,
        completion_id=completion.id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        method="upi",
        amount_minor=199_900,
        paid_at=seed.now,
        created_by=seed.owner_id,
        idempotency_key=f"{label}:payment:{uuid4()}",
        receipt_no=f"M/GUARD/26-27/{uuid4().hex[:8]}",
        receipt_fiscal_year="2026-27",
        receipt_issued_at=seed.now,
        external_reference=external_reference,
        provider_evidence_reconciled=True,
        evidence_occurred_at=seed.now,
        evidence_time_untrusted=False,
        customer_spend_reconciled=False,
        action_takeover_confirmed=False,
        action_takeover_reason=None,
    )
    session.add(payment)
    session.flush()
    return _PaymentWorkflow(
        request_id=request.id,
        action_id=action.id,
        completion_id=completion.id,
        membership_id=membership.id,
        payment_id=payment.id,
    )


def _write_payment_request(session: Session, seed: _Seed) -> None:
    session.add(
        MembershipPaymentRequest(
            id=uuid4(),
            company_id=seed.company_id,
            customer_id=seed.customer_id,
            tier_id=seed.tier_id,
            branch_id=seed.branch_id,
            terminal_id=seed.terminal_id,
            shift_id=seed.shift_id,
            billing_cycle="monthly",
            method="cash",
            amount_minor=199_900,
            customer_name_snapshot="Guard customer",
            customer_phone_snapshot="6000000000",
            tier_code_snapshot="GUARD",
            tier_name_snapshot="Guard monthly tier",
            accepted_at=seed.now,
            prepared_by=seed.owner_id,
            client_action_id=f"membership-prepare:{uuid4()}",
            idempotency_key=f"membership-prepare:{uuid4()}",
        )
    )


def _write_attempt_resolution(session: Session, seed: _Seed) -> None:
    session.add(
        MembershipPaymentAttemptResolution(
            id=uuid4(),
            company_id=seed.company_id,
            customer_id=seed.customer_id,
            tier_id=seed.tier_id,
            branch_id=seed.branch_id,
            terminal_id=seed.terminal_id,
            shift_id=seed.shift_id,
            original_client_action_id=f"membership-subscribe:{uuid4()}",
            paid_via="cash",
            expected_amount_minor=199_900,
            resolution="cash_returned",
            reason="Rejected legacy attempt returned",
            external_reference=None,
            provider_evidence_reconciled=True,
            cash_return_confirmed=True,
            resolved_at=seed.now,
            resolved_by=seed.owner_id,
            idempotency_key=f"membership-attempt-resolution:{uuid4()}",
        )
    )


def _write_provider_refund_action(session: Session, seed: _Seed) -> None:
    payment = _write_settled_provider_payment(
        session, seed, label="downgrade-provider-refund"
    )
    refund = MembershipRefund(
        id=uuid4(),
        company_id=seed.company_id,
        payment_id=payment.payment_id,
        branch_id=seed.branch_id,
        terminal_id=seed.terminal_id,
        shift_id=seed.shift_id,
        method="upi",
        amount_minor=199_900,
        accepted_at=seed.now,
        approved_by=seed.owner_id,
        idempotency_key=f"membership-refund:{uuid4()}",
        reason="Provider refund recovery proof",
    )
    session.add(refund)
    session.flush()
    session.add(
        MembershipRefundProviderAction(
            id=uuid4(),
            company_id=seed.company_id,
            refund_id=refund.id,
            branch_id=seed.branch_id,
            terminal_id=seed.terminal_id,
            shift_id=seed.shift_id,
            method="upi",
            started_at=seed.now,
            started_by=seed.owner_id,
            idempotency_key=f"membership-refund-provider-action:{uuid4()}",
        )
    )


def _write_refund_attempt_recovery(session: Session, seed: _Seed) -> None:
    payment = _write_settled_cash_payment(
        session, seed, label="downgrade-refund-recovery"
    )
    session.add(
        MembershipRefundAttemptRecovery(
            id=uuid4(),
            company_id=seed.company_id,
            customer_id=seed.customer_id,
            membership_id=payment.membership_id,
            payment_id=payment.payment_id,
            source_branch_id=seed.branch_id,
            source_terminal_id=seed.terminal_id,
            source_shift_id=seed.shift_id,
            original_client_action_id=f"membership-refund:{uuid4()}",
            paid_via="cash",
            expected_amount_minor=199_900,
            captured_at=seed.now,
            captured_time_untrusted=False,
            registered_at=seed.now,
            registered_by=seed.owner_id,
            idempotency_key=f"membership-refund-attempt-register:{uuid4()}",
        )
    )


@pytest.mark.integration
def test_0035_preserves_legacy_null_links_but_enforces_all_future_writes() -> None:
    with _disposable_database("erp_membership_0035_linkage") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0034")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                seed = _seed_base(session)
                legacy_membership = CustomerMembership(
                    id=uuid4(),
                    customer_id=seed.customer_id,
                    tier_id=seed.tier_id,
                    billing_cycle="monthly",
                    starts_at=seed.now,
                    expires_at=seed.now + timedelta(days=30),
                    auto_renew=False,
                    amount_paid_minor=199_900,
                )
                session.add(legacy_membership)
                session.flush()
                legacy_payment_id = uuid4()
                session.execute(
                    text(
                        """
                        INSERT INTO membership_payments (
                            id, membership_id, company_id, branch_id, terminal_id,
                            shift_id, method, amount_minor, paid_at, created_by,
                            idempotency_key, receipt_no, receipt_fiscal_year,
                            receipt_issued_at, created_at, updated_at
                        ) VALUES (
                            :id, :membership_id, :company_id, :branch_id, :terminal_id,
                            :shift_id, 'cash', 199900, :now, :owner_id,
                            :idempotency_key, :receipt_no, '2026-27', :now, :now, :now
                        )
                        """
                    ),
                    {
                        "id": legacy_payment_id,
                        "membership_id": legacy_membership.id,
                        "company_id": seed.company_id,
                        "branch_id": seed.branch_id,
                        "terminal_id": seed.terminal_id,
                        "shift_id": seed.shift_id,
                        "now": seed.now,
                        "owner_id": seed.owner_id,
                        "idempotency_key": f"legacy-linkage:{uuid4()}",
                        "receipt_no": f"M/GUARD/26-27/{uuid4().hex[:8]}",
                    },
                )
                session.commit()
        finally:
            engine.dispose()

        upgraded = _run_alembic(database_url, "upgrade", "0035")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                legacy_links = session.execute(
                    text(
                        "SELECT request_id, completion_id FROM membership_payments "
                        "WHERE id = :payment_id"
                    ),
                    {"payment_id": legacy_payment_id},
                ).one()
                assert legacy_links.request_id is None
                assert legacy_links.completion_id is None
                assert session.scalar(
                    text(
                        "SELECT convalidated FROM pg_constraint "
                        "WHERE conname = 'ck_membership_payment_workflow_linkage'"
                    )
                ) is False

                valid = _write_settled_cash_payment(
                    session, seed, label="valid-linkage"
                )
                invalid_membership = CustomerMembership(
                    id=uuid4(),
                    customer_id=seed.customer_id,
                    tier_id=seed.tier_id,
                    billing_cycle="monthly",
                    starts_at=seed.now,
                    expires_at=seed.now + timedelta(days=30),
                    auto_renew=False,
                    amount_paid_minor=199_900,
                )
                session.add(invalid_membership)
                session.commit()
                invalid_membership_id = invalid_membership.id

            with engine.connect() as connection:
                # Disable only the clearer BEFORE INSERT guard so this probe
                # proves that the PostgreSQL CHECK independently closes the
                # nullable-column bypass.
                connection.execute(
                    text(
                        "ALTER TABLE membership_payments DISABLE TRIGGER "
                        "trg_membership_payments_outcome_exclusive"
                    )
                )
                connection.commit()
                try:
                    with pytest.raises(
                        DBAPIError, match="ck_membership_payment_workflow_linkage"
                    ):
                        connection.execute(
                            text(
                                """
                                INSERT INTO membership_payments (
                                    id, membership_id, company_id, branch_id,
                                    terminal_id, shift_id, method, amount_minor,
                                    paid_at, created_by, idempotency_key, receipt_no,
                                    receipt_fiscal_year, receipt_issued_at
                                ) VALUES (
                                    :id, :membership_id, :company_id, :branch_id,
                                    :terminal_id, :shift_id, 'cash', 199900,
                                    :paid_at, :created_by, :idempotency_key,
                                    :receipt_no, '2026-27', :paid_at
                                )
                                """
                            ),
                            {
                                "id": uuid4(),
                                "membership_id": invalid_membership_id,
                                "company_id": seed.company_id,
                                "branch_id": seed.branch_id,
                                "terminal_id": seed.terminal_id,
                                "shift_id": seed.shift_id,
                                "paid_at": seed.now,
                                "created_by": seed.owner_id,
                                "idempotency_key": f"invalid-null-link:{uuid4()}",
                                "receipt_no": f"M/BAD/26-27/{uuid4().hex[:8]}",
                            },
                        )
                    connection.rollback()
                finally:
                    connection.execute(
                        text(
                            "ALTER TABLE membership_payments ENABLE TRIGGER "
                            "trg_membership_payments_outcome_exclusive"
                        )
                    )
                    connection.commit()

                # The same NOT VALID check applies to touched rows. Temporarily
                # disable only append-only protection to isolate each half-link.
                connection.execute(
                    text(
                        "ALTER TABLE membership_payments DISABLE TRIGGER "
                        "trg_membership_payments_immutable"
                    )
                )
                connection.commit()
                try:
                    for statement in (
                        text(
                            "UPDATE membership_payments SET request_id = NULL "
                            "WHERE id = :payment_id"
                        ),
                        text(
                            "UPDATE membership_payments SET completion_id = NULL "
                            "WHERE id = :payment_id"
                        ),
                    ):
                        with pytest.raises(
                            DBAPIError,
                            match="ck_membership_payment_workflow_linkage",
                        ):
                            connection.execute(
                                statement,
                                {"payment_id": valid.payment_id},
                            )
                        connection.rollback()
                finally:
                    connection.execute(
                        text(
                            "ALTER TABLE membership_payments ENABLE TRIGGER "
                            "trg_membership_payments_immutable"
                        )
                    )
                    connection.commit()

            with Session(engine) as session:
                untouched = session.execute(
                    text(
                        "SELECT request_id, completion_id FROM membership_payments "
                        "WHERE id = :payment_id"
                    ),
                    {"payment_id": valid.payment_id},
                ).one()
                assert untouched.request_id == valid.request_id
                assert untouched.completion_id == valid.completion_id
        finally:
            engine.dispose()


@pytest.mark.integration
def test_0035_completion_actions_must_match_their_guarded_roots() -> None:
    with _disposable_database("erp_membership_0035_action_pair") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0035")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                seed = _seed_base(session)
                payment_request_a, payment_action_a = _write_cash_request_action(
                    session, seed, label="payment-a"
                )
                _, payment_action_b = _write_cash_request_action(
                    session, seed, label="payment-b"
                )
                payment_request_a_id = payment_request_a.id
                payment_action_a_id = payment_action_a.id
                payment_action_b_id = payment_action_b.id
                session.commit()

            with Session(engine) as session:
                session.add(
                    MembershipPaymentCompletion(
                        id=uuid4(),
                        company_id=seed.company_id,
                        request_id=payment_request_a_id,
                        cash_collection_id=payment_action_b_id,
                        provider_action_id=None,
                        branch_id=seed.branch_id,
                        terminal_id=seed.terminal_id,
                        shift_id=seed.shift_id,
                        method="cash",
                        amount_minor=199_900,
                        completed_at=seed.now,
                        completed_by=seed.owner_id,
                        external_reference=None,
                        provider_evidence_reconciled=True,
                        evidence_occurred_at=seed.now,
                        evidence_time_untrusted=False,
                        action_takeover_confirmed=False,
                        action_takeover_reason=None,
                        idempotency_key=f"cross-payment-action:{uuid4()}",
                    )
                )
                with pytest.raises(
                    DBAPIError, match="inconsistent completion provenance"
                ):
                    session.commit()
                session.rollback()

            with Session(engine) as session:
                settled_a = _write_settled_cash_payment(
                    session, seed, label="refund-payment-a"
                )
                settled_b = _write_settled_cash_payment(
                    session, seed, label="refund-payment-b"
                )
                refund_a = MembershipRefund(
                    id=uuid4(),
                    company_id=seed.company_id,
                    payment_id=settled_a.payment_id,
                    branch_id=seed.branch_id,
                    terminal_id=seed.terminal_id,
                    shift_id=seed.shift_id,
                    method="cash",
                    amount_minor=199_900,
                    accepted_at=seed.now,
                    approved_by=seed.owner_id,
                    idempotency_key=f"refund-a:{uuid4()}",
                    reason="Exact action pair regression A",
                )
                refund_b = MembershipRefund(
                    id=uuid4(),
                    company_id=seed.company_id,
                    payment_id=settled_b.payment_id,
                    branch_id=seed.branch_id,
                    terminal_id=seed.terminal_id,
                    shift_id=seed.shift_id,
                    method="cash",
                    amount_minor=199_900,
                    accepted_at=seed.now,
                    approved_by=seed.owner_id,
                    idempotency_key=f"refund-b:{uuid4()}",
                    reason="Exact action pair regression B",
                )
                session.add_all([refund_a, refund_b])
                session.flush()
                handoff_a = MembershipRefundCashHandoff(
                    id=uuid4(),
                    company_id=seed.company_id,
                    refund_id=refund_a.id,
                    branch_id=seed.branch_id,
                    terminal_id=seed.terminal_id,
                    shift_id=seed.shift_id,
                    started_at=seed.now,
                    started_by=seed.owner_id,
                    idempotency_key=f"refund-action-a:{uuid4()}",
                )
                handoff_b = MembershipRefundCashHandoff(
                    id=uuid4(),
                    company_id=seed.company_id,
                    refund_id=refund_b.id,
                    branch_id=seed.branch_id,
                    terminal_id=seed.terminal_id,
                    shift_id=seed.shift_id,
                    started_at=seed.now,
                    started_by=seed.owner_id,
                    idempotency_key=f"refund-action-b:{uuid4()}",
                )
                session.add_all([handoff_a, handoff_b])
                refund_a_id = refund_a.id
                handoff_a_id = handoff_a.id
                handoff_b_id = handoff_b.id
                session.commit()

            with Session(engine) as session:
                session.add(
                    MembershipRefundCompletion(
                        id=uuid4(),
                        company_id=seed.company_id,
                        refund_id=refund_a_id,
                        cash_handoff_id=handoff_b_id,
                        provider_action_id=None,
                        legacy_attempt_resolution_id=None,
                        branch_id=seed.branch_id,
                        terminal_id=seed.terminal_id,
                        shift_id=seed.shift_id,
                        method="cash",
                        amount_minor=199_900,
                        completed_at=seed.now,
                        completed_by=seed.owner_id,
                        external_reference=None,
                        provider_evidence_reconciled=True,
                        evidence_occurred_at=seed.now,
                        evidence_time_untrusted=False,
                        action_takeover_confirmed=False,
                        action_takeover_reason=None,
                        idempotency_key=f"cross-refund-action:{uuid4()}",
                    )
                )
                with pytest.raises(
                    DBAPIError, match="inconsistent completion provenance"
                ):
                    session.commit()
                session.rollback()

            with Session(engine) as session:
                assert session.scalar(
                    select(func.count())
                    .select_from(MembershipPaymentCompletion)
                    .where(MembershipPaymentCompletion.request_id == payment_request_a_id)
                ) == 0
                assert session.scalar(
                    select(func.count())
                    .select_from(MembershipRefundCompletion)
                    .where(MembershipRefundCompletion.refund_id == refund_a_id)
                ) == 0
                # Keep the intended action IDs explicit so a future trigger
                # refactor cannot accidentally validate only tenant/shift data.
                assert payment_action_a_id != payment_action_b_id
                assert handoff_a_id != handoff_b_id
        finally:
            engine.dispose()


@pytest.mark.integration
@pytest.mark.parametrize(
    ("scenario", "writer"),
    (
        ("accepted payment request", _write_payment_request),
        ("legacy-attempt resolution", _write_attempt_resolution),
        ("provider-refund action", _write_provider_refund_action),
        ("legacy-refund quarantine", _write_refund_attempt_recovery),
    ),
)
def test_0035_downgrade_refuses_each_representative_workflow_fact(
    scenario: str,
    writer,
) -> None:
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

    database_name = f"erp_membership_0035_guard_{uuid4().hex[:12]}"
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
        upgraded = _run_alembic(database_url, "upgrade", "0035")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                seed = _seed_base(session)
                writer(session, seed)
                session.commit()
        finally:
            engine.dispose()

        blocked = _run_alembic(database_url, "downgrade", "0034")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0, f"{scenario} was silently dropped\n{output}"
        assert "cannot downgrade 0035 after membership reservation workflow use" in output

        current = _run_alembic(database_url, "current")
        assert current.returncode == 0, current.stdout + current.stderr
        assert "0035" in current.stdout + current.stderr
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
            pass


@pytest.mark.integration
def test_0035_unused_schema_downgrades_cleanly_to_0034() -> None:
    with _disposable_database("erp_membership_0035_empty") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0035")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        downgraded = _run_alembic(database_url, "downgrade", "0034")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr

        engine = create_engine(database_url)
        try:
            with engine.connect() as connection:
                leftovers = connection.execute(
                    text(
                        """
                        SELECT table_name
                          FROM information_schema.tables
                         WHERE table_schema = 'public'
                           AND table_name IN (
                               'membership_payment_requests',
                               'membership_payment_completions',
                               'membership_refund_completions',
                               'membership_refund_attempt_recoveries',
                               'membership_refund_attempt_resolutions',
                               'membership_customer_spend_applications',
                               'membership_evidence_reconciliations',
                               'membership_payment_workflow_guards',
                               'membership_refund_workflow_guards',
                               'membership_payment_refund_guards'
                           )
                        """
                    )
                ).scalars().all()
                assert leftovers == []
                added_columns = connection.execute(
                    text(
                        """
                        SELECT table_name, column_name
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND (
                               (table_name = 'membership_payments'
                                AND column_name IN ('request_id', 'completion_id',
                                    'customer_spend_reconciled'))
                               OR (table_name = 'membership_refund_settlements'
                                   AND column_name IN ('completion_id',
                                       'customer_spend_reconciled'))
                           )
                        """
                    )
                ).all()
                assert added_columns == []
        finally:
            engine.dispose()


@pytest.mark.integration
def test_0033_financial_history_survives_0035_round_trip_and_blocks_0032() -> None:
    with _disposable_database("erp_membership_0033_used") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0034")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        engine = create_engine(database_url)
        try:
            with Session(engine) as session:
                seed = _seed_base(session)
                membership = CustomerMembership(
                    id=uuid4(),
                    customer_id=seed.customer_id,
                    tier_id=seed.tier_id,
                    billing_cycle="monthly",
                    starts_at=seed.now,
                    expires_at=seed.now + timedelta(days=30),
                    auto_renew=False,
                    amount_paid_minor=199_900,
                )
                session.add(membership)
                session.flush()
                payment_id = uuid4()
                session.execute(
                    text(
                        """
                        INSERT INTO membership_payments (
                            id, membership_id, company_id, branch_id, terminal_id,
                            shift_id, method, amount_minor, paid_at, created_by,
                            idempotency_key, receipt_no, receipt_fiscal_year,
                            receipt_issued_at, created_at, updated_at
                        ) VALUES (
                            :id, :membership_id, :company_id, :branch_id, :terminal_id,
                            :shift_id, 'upi', 199900, :now, :owner_id,
                            :idempotency_key, :receipt_no, '2026-27', :now, :now, :now
                        )
                        """
                    ),
                    {
                        "id": payment_id,
                        "membership_id": membership.id,
                        "company_id": seed.company_id,
                        "branch_id": seed.branch_id,
                        "terminal_id": seed.terminal_id,
                        "shift_id": seed.shift_id,
                        "now": seed.now,
                        "owner_id": seed.owner_id,
                        "idempotency_key": f"legacy-payment:{uuid4()}",
                        "receipt_no": f"M/GUARD/26-27/{uuid4().hex[:8]}",
                    },
                )
                session.commit()
        finally:
            engine.dispose()

        to_0035 = _run_alembic(database_url, "upgrade", "0035")
        assert to_0035.returncode == 0, to_0035.stdout + to_0035.stderr
        back_to_0034 = _run_alembic(database_url, "downgrade", "0034")
        assert back_to_0034.returncode == 0, back_to_0034.stdout + back_to_0034.stderr
        destructive = _run_alembic(database_url, "downgrade", "0032")
        output = destructive.stdout + destructive.stderr
        assert destructive.returncode != 0, output
        assert "cannot downgrade 0033 after membership financial" in output
