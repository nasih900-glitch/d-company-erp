"""PostgreSQL/HTTP proof for reservation-first membership money flows."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, func, select, text, update
from sqlalchemy.exc import DBAPIError

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password, issue_access_token
from app.models import (
    AuditLog,
    Branch,
    Company,
    Customer,
    CustomerMembership,
    IdempotencyKey,
    InvoiceCounter,
    MembershipPayment,
    MembershipCustomerSpendApplication,
    MembershipEvidenceReconciliation,
    MembershipPaymentAttemptResolution,
    MembershipPaymentCashCollection,
    MembershipPaymentCompletion,
    MembershipPaymentProviderAction,
    MembershipPaymentRequest,
    MembershipPaymentRequestResolution,
    MembershipRefund,
    MembershipRefundAttemptRecovery,
    MembershipRefundAttemptResolution,
    MembershipRefundCashHandoff,
    MembershipRefundCompletion,
    MembershipRefundProviderAction,
    MembershipRefundResolution,
    MembershipRefundSettlement,
    MembershipTier,
    Shift,
    Terminal,
    User,
)
from app.services.audit.recorder import install_audit_listeners


@pytest_asyncio.fixture(autouse=True)
async def require_membership_schema(session) -> None:
    try:
        await session.execute(text("select 1 from membership_payment_requests limit 1"))
    except Exception as exc:
        pytest.skip(f"Postgres is not migrated through membership revision 0035: {exc}")


@dataclass(frozen=True, slots=True)
class _Case:
    company_id: UUID
    branch_id: UUID
    terminal_id: UUID
    owner_id: UUID
    customer_id: UUID
    tier_id: UUID
    shift_id: UUID
    amount_minor: int
    opening_drawer_minor: int
    opening_ltv_minor: int
    token: str


async def _seed_case(session, seed_owner) -> _Case:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    amount = 199_900
    opening_drawer = 8_300
    opening_ltv = 4_200
    now = datetime.now(UTC).replace(microsecond=0)
    customer = Customer(
        id=uuid4(),
        company_id=company.id,
        name="Reservation-first membership customer",
        phone=f"9{uuid4().int % 10**9:09d}",
        visit_count=0,
        total_spent_minor=opening_ltv,
        loyalty_points=0,
        lifetime_gaming_points_earned=0,
    )
    tier = MembershipTier(
        id=uuid4(),
        company_id=company.id,
        code=f"M{uuid4().hex[:7].upper()}",
        name="Monthly QA Membership",
        monthly_price_minor=amount,
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
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=opening_drawer,
        expected_minor=opening_drawer,
        status="open",
    )
    session.add_all([customer, tier, shift])
    await session.commit()
    token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    return _Case(
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        owner_id=owner.id,
        customer_id=customer.id,
        tier_id=tier.id,
        shift_id=shift.id,
        amount_minor=amount,
        opening_drawer_minor=opening_drawer,
        opening_ltv_minor=opening_ltv,
        token=token,
    )


def _headers(case: _Case, key: str, *, token: str | None = None) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token or case.token}",
        "X-Terminal-Id": str(case.terminal_id),
        "Idempotency-Key": key,
        "X-Client-Action-Id": key,
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "21",
    }


async def _wait_for_shift_lock_waiters(minimum: int, *, timeout: float = 3.0) -> None:
    """Wait until real PostgreSQL sessions are queued on a Shift row lock."""
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while loop.time() < deadline:
        async with AsyncSessionLocal() as observer:
            waiters = int(
                (
                    await observer.execute(
                        text(
                            "SELECT count(*) FROM pg_stat_activity "
                            "WHERE datname = current_database() "
                            "AND pid <> pg_backend_pid() "
                            "AND wait_event_type = 'Lock' "
                            "AND query ILIKE '%FROM shifts%' "
                            "AND query ILIKE '%FOR UPDATE%'"
                        )
                    )
                ).scalar_one()
                or 0
            )
        if waiters >= minimum:
            return
        await asyncio.sleep(0.01)
    raise AssertionError(
        f"expected at least {minimum} PostgreSQL Shift lock waiter(s)"
    )


async def _add_protected_owner(
    session,
    case: _Case,
    *,
    name: str,
) -> tuple[User, str]:
    owner = User(
        id=uuid4(),
        company_id=case.company_id,
        email=f"{name.lower().replace(' ', '-')}-{uuid4().hex[:8]}@test.local",
        name=name,
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add(owner)
    await session.commit()
    token = issue_access_token(
        user_id=owner.id,
        company_id=case.company_id,
        branch_id=case.branch_id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    return owner, token


def _prepare_payload(case: _Case, action_id: str, *, method: str) -> dict:
    return {
        "customer_id": str(case.customer_id),
        "tier_id": str(case.tier_id),
        "shift_id": str(case.shift_id),
        "expected_amount_minor": case.amount_minor,
        "billing_cycle": "monthly",
        "paid_via": method,
        "client_action_id": action_id,
    }


async def _prepare(client, case: _Case, *, method: str, key: str) -> dict:
    response = await client.post(
        "/api/v1/memberships/payment-requests",
        json=_prepare_payload(case, key, method=method),
        headers=_headers(case, key),
    )
    assert response.status_code == 201, response.text
    assert response.json()["status"] == "accepted_payment_due"
    return response.json()


async def _begin(client, case: _Case, request_id: str, *, method: str, key: str) -> dict:
    endpoint = (
        "begin-cash-collection" if method == "cash" else "begin-provider-action"
    )
    flag = "ready_to_collect" if method == "cash" else "ready_to_start"
    response = await client.post(
        f"/api/v1/memberships/payment-requests/{request_id}/{endpoint}",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            flag: True,
        },
        headers=_headers(case, key),
    )
    assert response.status_code == 201, response.text
    return response.json()


async def _settle(
    client,
    case: _Case,
    request_id: str,
    *,
    method: str,
    key: str,
    token: str | None = None,
    external_reference: str | None = None,
    takeover: bool = False,
    collected_at: str | None = None,
) -> object:
    return await client.post(
        f"/api/v1/memberships/payment-requests/{request_id}/settle",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "collected_at": collected_at or datetime.now(UTC).isoformat(),
            "payment_received": True,
            "external_reference": external_reference,
            "action_takeover_confirmed": takeover,
            "action_takeover_reason": (
                "Verified the original owner's provider screen and receipt"
                if takeover
                else None
            ),
        },
        headers=_headers(case, key, token=token),
    )


async def _finalize_payment(
    client,
    case: _Case,
    request_id: str,
    *,
    key: str,
    token: str | None = None,
) -> object:
    return await client.post(
        f"/api/v1/memberships/payment-requests/{request_id}/finalize",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
        },
        headers=_headers(case, key, token=token),
    )


async def _finalize_refund(
    client,
    case: _Case,
    refund_id: str | UUID,
    *,
    key: str,
    token: str | None = None,
) -> object:
    return await client.post(
        f"/api/v1/memberships/refunds/{refund_id}/finalize",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
        },
        headers=_headers(case, key, token=token),
    )


async def _cleanup(
    case: _Case,
    *,
    extra_user_id: UUID | None = None,
    extra_shift_ids: tuple[UUID, ...] = (),
    extra_customer_ids: tuple[UUID, ...] = (),
) -> None:
    """Remove only this synthetic case with migration-owner DDL.

    Runtime callers cannot bypass append-only history with a custom setting.
    This suite runs only against a disposable database and temporarily disables
    the exact named triggers inside the cleanup transaction.
    """
    immutable_triggers = (
        ("membership_evidence_reconciliations", "trg_membership_evidence_reconciliations_immutable"),
        ("membership_customer_spend_applications", "trg_membership_customer_spend_applications_immutable"),
        ("membership_refund_settlements", "trg_membership_refund_settlements_immutable"),
        ("membership_refund_completions", "trg_membership_refund_completions_immutable"),
        ("membership_refund_attempt_resolutions", "trg_membership_refund_attempt_resolutions_immutable"),
        ("membership_refund_attempt_recoveries", "trg_membership_refund_attempt_recoveries_immutable"),
        ("membership_refund_resolutions", "trg_membership_refund_resolutions_immutable"),
        ("membership_refund_cash_handoffs", "trg_membership_refund_cash_handoffs_immutable"),
        ("membership_refund_provider_actions", "trg_membership_refund_provider_actions_immutable"),
        ("membership_refunds", "trg_membership_refunds_immutable"),
        ("membership_payment_request_resolutions", "trg_membership_payment_request_resolutions_immutable"),
        ("membership_payment_completions", "trg_membership_payment_completions_immutable"),
        ("membership_payment_attempt_resolutions", "trg_membership_payment_attempt_resolutions_immutable"),
        ("membership_payment_cash_collections", "trg_membership_payment_cash_collections_immutable"),
        ("membership_payment_provider_actions", "trg_membership_payment_provider_actions_immutable"),
        ("membership_payments", "trg_membership_payments_immutable"),
        ("membership_payment_requests", "trg_membership_payment_requests_immutable"),
    )
    async with AsyncSessionLocal() as cleanup:
        for table_name, trigger_name in immutable_triggers:
            await cleanup.execute(
                text(f"ALTER TABLE {table_name} DISABLE TRIGGER {trigger_name}")
            )
        await cleanup.execute(
            delete(AuditLog).where(AuditLog.company_id == case.company_id)
        )
        for model in (
            MembershipEvidenceReconciliation,
            MembershipCustomerSpendApplication,
            MembershipRefundSettlement,
            MembershipRefundCompletion,
            MembershipRefundAttemptResolution,
            MembershipRefundAttemptRecovery,
            MembershipRefundResolution,
            MembershipRefundCashHandoff,
            MembershipRefundProviderAction,
            MembershipRefund,
            MembershipPaymentRequestResolution,
            MembershipPayment,
            MembershipPaymentCompletion,
            MembershipPaymentAttemptResolution,
            MembershipPaymentCashCollection,
            MembershipPaymentProviderAction,
        ):
            await cleanup.execute(delete(model).where(model.company_id == case.company_id))
        await cleanup.execute(
            delete(CustomerMembership).where(
                CustomerMembership.customer_id == case.customer_id
            )
        )
        await cleanup.execute(
            delete(MembershipPaymentRequest).where(
                MembershipPaymentRequest.company_id == case.company_id
            )
        )
        await cleanup.execute(
            delete(IdempotencyKey).where(
                (IdempotencyKey.terminal_id == case.terminal_id)
                | (IdempotencyKey.user_id == case.owner_id)
                | (
                    IdempotencyKey.user_id == extra_user_id
                    if extra_user_id is not None
                    else False
                )
            )
        )
        await cleanup.execute(
            delete(InvoiceCounter).where(InvoiceCounter.branch_id == case.branch_id)
        )
        await cleanup.execute(
            delete(Shift).where(Shift.id.in_((case.shift_id, *extra_shift_ids)))
        )
        await cleanup.execute(delete(MembershipTier).where(MembershipTier.id == case.tier_id))
        await cleanup.execute(
            delete(Customer).where(
                Customer.id.in_((case.customer_id, *extra_customer_ids))
            )
        )
        if extra_user_id is not None:
            await cleanup.execute(delete(User).where(User.id == extra_user_id))
        for table_name, trigger_name in immutable_triggers:
            await cleanup.execute(
                text(f"ALTER TABLE {table_name} ENABLE TRIGGER {trigger_name}")
            )
        await cleanup.commit()


async def _commit_direct_sql(statement: str, parameters: dict) -> tuple[bool, str]:
    """Commit one direct-SQL transition on its own PostgreSQL connection."""
    async with AsyncSessionLocal() as direct:
        try:
            await direct.execute(text(statement), parameters)
            await direct.commit()
            return True, ""
        except DBAPIError as exc:
            await direct.rollback()
            return False, str(exc.orig)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_legacy_payment_attempt_supports_truthful_no_movement_outcomes(
    client,
    session,
    seed_owner,
) -> None:
    """Recovery never forces an owner to attest a return for money never taken."""
    case = await _seed_case(session, seed_owner)
    cash_action = f"membership-subscribe:{uuid4()}"
    cash_key = f"membership-payment-attempt-cash:{uuid4()}"
    provider_action = f"membership-subscribe:{uuid4()}"
    provider_key = f"membership-payment-attempt-provider:{uuid4()}"
    invalid_cash_key = f"membership-payment-attempt-invalid-cash:{uuid4()}"
    invalid_provider_key = f"membership-payment-attempt-invalid-provider:{uuid4()}"
    try:
        cash_payload = {
            "original_client_action_id": cash_action,
            "customer_id": str(case.customer_id),
            "tier_id": str(case.tier_id),
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "paid_via": "cash",
            "resolution": "payment_not_collected",
            "reason": "Customer cancelled before handing over any notes",
            "cash_return_confirmed": False,
        }
        cash = await client.post(
            "/api/v1/memberships/payment-attempts/resolve",
            json=cash_payload,
            headers=_headers(case, cash_key),
        )
        assert cash.status_code == 201, cash.text
        assert cash.json()["resolution"] == "payment_not_collected"
        assert cash.json()["cash_return_confirmed"] is False

        cash_replay = await client.post(
            "/api/v1/memberships/payment-attempts/resolve",
            json=cash_payload,
            headers=_headers(case, cash_key),
        )
        assert cash_replay.status_code == 201, cash_replay.text
        assert cash_replay.json() == cash.json()

        conflicting_reason = await client.post(
            "/api/v1/memberships/payment-attempts/resolve",
            json={**cash_payload, "reason": "A different audit explanation"},
            headers=_headers(case, f"membership-payment-attempt-conflict:{uuid4()}"),
        )
        assert conflicting_reason.status_code == 422, conflicting_reason.text
        assert "different evidence" in conflicting_reason.text

        invalid_cash = await client.post(
            "/api/v1/memberships/payment-attempts/resolve",
            json={**cash_payload, "original_client_action_id": f"membership-subscribe:{uuid4()}",
                  "cash_return_confirmed": True},
            headers=_headers(case, invalid_cash_key),
        )
        assert invalid_cash.status_code == 422, invalid_cash.text
        assert "payment_not_collected" in invalid_cash.text

        provider_payload = {
            "original_client_action_id": provider_action,
            "customer_id": str(case.customer_id),
            "tier_id": str(case.tier_id),
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "paid_via": "upi",
            "resolution": "provider_not_completed",
            "reason": "Provider search confirmed that no payment completed",
            "external_reference": f"NO-PAYMENT-{uuid4().hex}",
            "provider_verification_status": "not_completed",
            "provider_evidence_occurred_at": datetime.now(UTC).isoformat(),
            "cash_return_confirmed": False,
        }
        provider = await client.post(
            "/api/v1/memberships/payment-attempts/resolve",
            json=provider_payload,
            headers=_headers(case, provider_key),
        )
        assert provider.status_code == 201, provider.text
        assert provider.json()["resolution"] == "provider_not_completed"
        assert provider.json()["provider_verification_status"] == "not_completed"

        invalid_provider = await client.post(
            "/api/v1/memberships/payment-attempts/resolve",
            json={
                **provider_payload,
                "original_client_action_id": f"membership-subscribe:{uuid4()}",
                "provider_verification_status": "reversed",
            },
            headers=_headers(case, invalid_provider_key),
        )
        assert invalid_provider.status_code == 422, invalid_provider.text
        assert "matching status" in invalid_provider.text

        async with AsyncSessionLocal() as direct:
            direct.add(
                MembershipPaymentAttemptResolution(
                    id=uuid4(),
                    company_id=case.company_id,
                    customer_id=case.customer_id,
                    tier_id=case.tier_id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=case.shift_id,
                    original_client_action_id=f"membership-subscribe:{uuid4()}",
                    paid_via="cash",
                    expected_amount_minor=case.amount_minor,
                    resolution="payment_not_collected",
                    reason="Invalid direct attestation must fail",
                    external_reference=None,
                    provider_verification_status=None,
                    provider_checked_at=None,
                    provider_evidence_reconciled=True,
                    evidence_occurred_at=None,
                    evidence_time_untrusted=False,
                    cash_return_confirmed=True,
                    resolved_at=datetime.now(UTC),
                    resolved_by=case.owner_id,
                    idempotency_key=f"membership-invalid-direct:{uuid4()}",
                )
            )
            with pytest.raises(DBAPIError):
                await direct.commit()
            await direct.rollback()

        async with AsyncSessionLocal() as verify:
            rows = (
                await verify.execute(
                    select(MembershipPaymentAttemptResolution).where(
                        MembershipPaymentAttemptResolution.company_id == case.company_id
                    )
                )
            ).scalars().all()
            assert {row.resolution for row in rows} == {
                "payment_not_collected",
                "provider_not_completed",
            }
            assert await verify.scalar(
                select(func.count()).select_from(MembershipPayment).where(
                    MembershipPayment.company_id == case.company_id
                )
            ) == 0
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert shift.expected_minor == case.opening_drawer_minor
            assert customer.total_spent_minor == case.opening_ltv_minor
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_money_task_lists_support_exact_recovery_and_truncation(
    client,
    session,
    seed_owner,
) -> None:
    """A client never has to guess a task from a capped recovery list."""
    case = await _seed_case(session, seed_owner)
    second_customer = Customer(
        id=uuid4(),
        company_id=case.company_id,
        name="Second membership recovery customer",
        phone=f"7{uuid4().int % 10**9:09d}",
        visit_count=0,
        total_spent_minor=0,
        loyalty_points=0,
        lifetime_gaming_points_earned=0,
    )
    session.add(second_customer)
    await session.commit()
    first_action = f"membership-prepare:{uuid4()}"
    second_action = f"membership-prepare:{uuid4()}"
    try:
        first = await _prepare(client, case, method="upi", key=first_action)
        second_payload = _prepare_payload(case, second_action, method="upi")
        second_payload["customer_id"] = str(second_customer.id)
        second = await client.post(
            "/api/v1/memberships/payment-requests",
            json=second_payload,
            headers=_headers(case, second_action),
        )
        assert second.status_code == 201, second.text

        capped = await client.get(
            "/api/v1/memberships/payment-requests?limit=1",
            headers=_headers(case, f"membership-list-capped:{uuid4()}"),
        )
        assert capped.status_code == 200, capped.text
        assert len(capped.json()) == 1
        assert capped.headers["x-result-truncated"] == "true"
        assert capped.headers["x-result-limit"] == "1"

        exact_request = await client.get(
            f"/api/v1/memberships/payment-requests?request_id={first['id']}",
            headers=_headers(case, f"membership-list-request:{uuid4()}"),
        )
        assert exact_request.status_code == 200, exact_request.text
        assert [row["id"] for row in exact_request.json()] == [first["id"]]
        assert exact_request.headers["x-result-truncated"] == "false"

        exact_action = await client.get(
            "/api/v1/memberships/payment-requests",
            params={"client_action_id": second_action},
            headers=_headers(case, f"membership-list-action:{uuid4()}"),
        )
        assert exact_action.status_code == 200, exact_action.text
        assert [row["id"] for row in exact_action.json()] == [second.json()["id"]]

        missing_request = await client.get(
            f"/api/v1/memberships/payment-requests?request_id={uuid4()}",
            headers=_headers(case, f"membership-list-missing:{uuid4()}"),
        )
        assert missing_request.status_code == 200, missing_request.text
        assert missing_request.json() == []

        await _begin(
            client,
            case,
            first["id"],
            method="upi",
            key=f"membership-list-begin:{uuid4()}",
        )
        completed = await _settle(
            client,
            case,
            first["id"],
            method="upi",
            key=f"membership-list-complete:{uuid4()}",
            external_reference=f"PAY-{uuid4().hex}",
        )
        assert completed.status_code == 201, completed.text
        sale = await _finalize_payment(
            client,
            case,
            first["id"],
            key=f"membership-list-finalize:{uuid4()}",
        )
        assert sale.status_code == 201, sale.text

        recovery_action = f"membership-refund:{uuid4()}"
        recovery = await client.post(
            "/api/v1/memberships/refund-attempts/register",
            json={
                "original_client_action_id": recovery_action,
                "customer_id": str(case.customer_id),
                "membership_id": sale.json()["membership_id"],
                "payment_id": sale.json()["payment_id"],
                "source_shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "paid_via": "upi",
                "captured_at": datetime.now(UTC).isoformat(),
            },
            headers=_headers(case, f"membership-list-recovery-register:{uuid4()}"),
        )
        assert recovery.status_code == 201, recovery.text

        by_recovery_id = await client.get(
            "/api/v1/memberships/refund-attempts",
            params={"recovery_id": recovery.json()["id"]},
            headers=_headers(case, f"membership-list-recovery-id:{uuid4()}"),
        )
        assert by_recovery_id.status_code == 200, by_recovery_id.text
        assert [row["id"] for row in by_recovery_id.json()] == [recovery.json()["id"]]
        by_original_action = await client.get(
            "/api/v1/memberships/refund-attempts",
            params={"original_client_action_id": recovery_action},
            headers=_headers(case, f"membership-list-recovery-action:{uuid4()}"),
        )
        assert by_original_action.status_code == 200, by_original_action.text
        assert [row["id"] for row in by_original_action.json()] == [
            recovery.json()["id"]
        ]

        resolution_evidence_at = datetime.now(UTC).replace(microsecond=0)
        resolution_payload = {
            "original_client_action_id": recovery_action,
            "customer_id": str(case.customer_id),
            "membership_id": sale.json()["membership_id"],
            "payment_id": sale.json()["payment_id"],
            "source_shift_id": str(case.shift_id),
            "reconciliation_shift_id": None,
            "expected_amount_minor": case.amount_minor,
            "paid_via": "upi",
            "outcome": "no_payout",
            "reason": "Provider search proved no refund was created",
            "provider_status": "not_completed",
            "verification_reference": f"NO-REFUND-{uuid4().hex}",
            "evidence_occurred_at": resolution_evidence_at.isoformat(),
            "cash_handover_confirmed": False,
        }
        resolved = await client.post(
            "/api/v1/memberships/refund-attempts/resolve",
            json=resolution_payload,
            headers=_headers(case, f"membership-list-recovery-resolve:{uuid4()}"),
        )
        assert resolved.status_code == 201, resolved.text

        natural_key_replay = await client.post(
            "/api/v1/memberships/refund-attempts/resolve",
            json=resolution_payload,
            headers=_headers(
                case, f"membership-list-recovery-natural-replay:{uuid4()}"
            ),
        )
        assert natural_key_replay.status_code == 201, natural_key_replay.text
        assert natural_key_replay.json() == resolved.json()

        conflicting_evidence = await client.post(
            "/api/v1/memberships/refund-attempts/resolve",
            json={
                **resolution_payload,
                "evidence_occurred_at": (
                    resolution_evidence_at + timedelta(seconds=2)
                ).isoformat(),
            },
            headers=_headers(
                case, f"membership-list-recovery-evidence-conflict:{uuid4()}"
            ),
        )
        assert conflicting_evidence.status_code == 422, conflicting_evidence.text
        assert "different immutable evidence" in conflicting_evidence.text

        refund_action = f"membership-refund-request:{uuid4()}"
        refund = await client.post(
            f"/api/v1/memberships/{sale.json()['membership_id']}/refund",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "method": "upi",
                "reason": "Exact refund task lookup proof",
            },
            headers=_headers(case, refund_action),
        )
        assert refund.status_code == 201, refund.text

        by_refund_id = await client.get(
            "/api/v1/memberships/refunds",
            params={"refund_id": refund.json()["id"]},
            headers=_headers(case, f"membership-list-refund-id:{uuid4()}"),
        )
        assert by_refund_id.status_code == 200, by_refund_id.text
        assert [row["id"] for row in by_refund_id.json()] == [refund.json()["id"]]
        by_refund_action = await client.get(
            "/api/v1/memberships/refunds",
            params={"client_action_id": refund_action},
            headers=_headers(case, f"membership-list-refund-action:{uuid4()}"),
        )
        assert by_refund_action.status_code == 200, by_refund_action.text
        assert [row["id"] for row in by_refund_action.json()] == [refund.json()["id"]]

        for path, param_name in (
            ("/api/v1/memberships/payment-requests", "client_action_id"),
            ("/api/v1/memberships/refund-attempts", "original_client_action_id"),
            ("/api/v1/memberships/refunds", "client_action_id"),
        ):
            blank = await client.get(
                path,
                params={param_name: "   "},
                headers=_headers(case, f"membership-list-blank:{uuid4()}"),
            )
            assert blank.status_code == 422, blank.text
    finally:
        await _cleanup(case, extra_customer_ids=(second_customer.id,))


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cash_reservation_retry_and_settlement_post_exactly_once(
    client, session, seed_owner
) -> None:
    install_audit_listeners()
    case = await _seed_case(session, seed_owner)
    prepare_key = f"membership-prepare:{uuid4()}"
    try:
        first, retry = await asyncio.gather(
            client.post(
                "/api/v1/memberships/payment-requests",
                json=_prepare_payload(case, prepare_key, method="cash"),
                headers=_headers(case, prepare_key),
            ),
            client.post(
                "/api/v1/memberships/payment-requests",
                json=_prepare_payload(case, prepare_key, method="cash"),
                headers=_headers(case, prepare_key),
            ),
        )
        assert {first.status_code, retry.status_code} <= {201, 409}
        assert 201 in {first.status_code, retry.status_code}
        replay = await client.post(
            "/api/v1/memberships/payment-requests",
            json=_prepare_payload(case, prepare_key, method="cash"),
            headers=_headers(case, prepare_key),
        )
        assert replay.status_code == 201, replay.text
        request_id = replay.json()["id"]

        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count()).select_from(MembershipPaymentRequest).where(
                    MembershipPaymentRequest.customer_id == case.customer_id
                )
            ) == 1
            assert await verify.scalar(
                select(func.count()).select_from(MembershipPayment).where(
                    MembershipPayment.company_id == case.company_id
                )
            ) == 0
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert shift.expected_minor == case.opening_drawer_minor
            assert customer.total_spent_minor == case.opening_ltv_minor

        begun = await _begin(
            client,
            case,
            request_id,
            method="cash",
            key=f"membership-begin-cash:{uuid4()}",
        )
        assert begun["status"] == "cash_collection_in_progress"
        settle_key = f"membership-settle-cash:{uuid4()}"
        collected_at = datetime.now(UTC).isoformat()
        settled = await _settle(
            client,
            case,
            request_id,
            method="cash",
            key=settle_key,
            collected_at=collected_at,
        )
        assert settled.status_code == 201, settled.text
        completion_body = settled.json()
        assert completion_body["status"] == "payment_completed_pending_posting"
        assert completion_body["receipt_no"] is None
        assert completion_body["payment_id"] is None
        assert completion_body["membership_id"] is None
        assert completion_body["value_completed_by_name"] == seed_owner["owner"].name

        exact_replay = await _settle(
            client,
            case,
            request_id,
            method="cash",
            key=settle_key,
            collected_at=collected_at,
        )
        assert exact_replay.status_code == 201
        assert exact_replay.json() == completion_body

        # Crash/restart boundary: the value-completion fact is committed while
        # receipts, accounting, drawer, and LTV remain untouched. A later
        # finalizer must be able to consume it without collecting again.
        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count()).select_from(MembershipPaymentCompletion).where(
                    MembershipPaymentCompletion.request_id == UUID(request_id)
                )
            ) == 1
            assert await verify.scalar(
                select(func.count()).select_from(MembershipPayment).where(
                    MembershipPayment.request_id == UUID(request_id)
                )
            ) == 0
            assert await verify.scalar(
                select(func.count()).select_from(CustomerMembership).where(
                    CustomerMembership.customer_id == case.customer_id
                )
            ) == 0
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert shift.expected_minor == case.opening_drawer_minor
            assert customer.total_spent_minor == case.opening_ltv_minor

        # Database invariant: an application-role caller cannot claim that LTV
        # was reconciled merely by setting the boolean. The same transaction
        # must also mutate the accumulator and append its spend-application fact.
        async with AsyncSessionLocal() as forged:
            completion = (
                await forged.execute(
                    select(MembershipPaymentCompletion).where(
                        MembershipPaymentCompletion.request_id == UUID(request_id)
                    )
                )
            ).scalar_one()
            forged_membership_id = uuid4()
            forged_payment_id = uuid4()
            forged.add(
                CustomerMembership(
                    id=forged_membership_id,
                    customer_id=case.customer_id,
                    tier_id=case.tier_id,
                    billing_cycle="monthly",
                    starts_at=completion.completed_at,
                    expires_at=completion.completed_at + timedelta(days=30),
                    auto_renew=False,
                    amount_paid_minor=case.amount_minor,
                    notes="Direct-SQL spend invariant probe",
                )
            )
            await forged.flush()
            forged.add(
                MembershipPayment(
                    id=forged_payment_id,
                    company_id=case.company_id,
                    membership_id=forged_membership_id,
                    request_id=UUID(request_id),
                    completion_id=completion.id,
                    branch_id=case.branch_id,
                    terminal_id=case.terminal_id,
                    shift_id=case.shift_id,
                    method="cash",
                    amount_minor=case.amount_minor,
                    paid_at=datetime.now(UTC),
                    created_by=case.owner_id,
                    idempotency_key=f"membership-forged-spend:{uuid4()}",
                    receipt_no=f"M/BAD/26-27/{uuid4().hex[:8]}",
                    receipt_fiscal_year="2026-27",
                    receipt_issued_at=datetime.now(UTC),
                    external_reference=None,
                    provider_evidence_reconciled=True,
                    evidence_occurred_at=completion.evidence_occurred_at,
                    evidence_time_untrusted=completion.evidence_time_untrusted,
                    customer_spend_reconciled=True,
                    action_takeover_confirmed=False,
                    action_takeover_reason=None,
                    note="Must fail without accumulator application",
                )
            )
            with pytest.raises(DBAPIError, match="claims customer spend without application"):
                await forged.commit()
            await forged.rollback()

        finalize_key = f"membership-finalize-cash:{uuid4()}"
        finalized = await _finalize_payment(
            client,
            case,
            request_id,
            key=finalize_key,
        )
        assert finalized.status_code == 201, finalized.text
        body = finalized.json()
        assert body["status"] == "settled"
        assert body["receipt_no"].startswith("M/")
        assert body["settled_by_name"] == seed_owner["owner"].name

        finalize_replay = await _finalize_payment(
            client,
            case,
            request_id,
            key=finalize_key,
        )
        assert finalize_replay.status_code == 201, finalize_replay.text
        assert finalize_replay.json() == body

        async with AsyncSessionLocal() as verify:
            payments = (
                await verify.execute(
                    select(MembershipPayment).where(
                        MembershipPayment.company_id == case.company_id
                    )
                )
            ).scalars().all()
            memberships = (
                await verify.execute(
                    select(CustomerMembership).where(
                        CustomerMembership.customer_id == case.customer_id
                    )
                )
            ).scalars().all()
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert len(payments) == len(memberships) == 1
            assert payments[0].request_id == UUID(request_id)
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipCustomerSpendApplication)
                .where(MembershipCustomerSpendApplication.payment_id == payments[0].id)
            ) == 1
            assert shift.expected_minor == case.opening_drawer_minor + case.amount_minor
            assert customer.total_spent_minor == case.opening_ltv_minor + case.amount_minor
            audit_types = set(
                (
                    await verify.execute(
                        select(AuditLog.entity_type).where(
                            AuditLog.company_id == case.company_id,
                            AuditLog.entity_type.in_(
                                (
                                    "MembershipPaymentRequest",
                                    "MembershipPaymentCashCollection",
                                    "CustomerMembership",
                                    "MembershipPayment",
                                )
                            ),
                        )
                    )
                ).scalars()
            )
            assert audit_types == {
                "MembershipPaymentRequest",
                "MembershipPaymentCashCollection",
                "CustomerMembership",
                "MembershipPayment",
            }
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_expired_term_remains_in_history_and_can_be_renewed_once(
    client, session, seed_owner
) -> None:
    """Expiry removes benefits without erasing history; renewal mints one new term."""
    case = await _seed_case(session, seed_owner)
    now = datetime.now(UTC).replace(microsecond=0)
    expired = CustomerMembership(
        id=uuid4(),
        customer_id=case.customer_id,
        tier_id=case.tier_id,
        billing_cycle="monthly",
        starts_at=now - timedelta(days=60),
        expires_at=now - timedelta(days=30),
        auto_renew=False,
        amount_paid_minor=case.amount_minor,
        notes="Expired renewal workflow fixture",
    )
    session.add(expired)
    await session.commit()
    try:
        current_before = await client.get(
            f"/api/v1/memberships/customer/{case.customer_id}",
            headers=_headers(case, f"membership-expiry-current:{uuid4()}"),
        )
        assert current_before.status_code == 200, current_before.text
        assert current_before.json() is None

        history_before = await client.get(
            f"/api/v1/memberships/customer/{case.customer_id}/history",
            headers=_headers(case, f"membership-expiry-history:{uuid4()}"),
        )
        assert history_before.status_code == 200, history_before.text
        assert [row["id"] for row in history_before.json()] == [str(expired.id)]
        assert history_before.json()[0]["is_active"] is False

        prepared = await _prepare(
            client,
            case,
            method="upi",
            key=f"membership-renew-prepare:{uuid4()}",
        )
        request_id = prepared["id"]
        begun = await _begin(
            client,
            case,
            request_id,
            method="upi",
            key=f"membership-renew-begin:{uuid4()}",
        )
        assert begun["status"] == "provider_action_in_progress"
        settled = await _settle(
            client,
            case,
            request_id,
            method="upi",
            key=f"membership-renew-settle:{uuid4()}",
            external_reference=f"RENEW-{uuid4().hex}",
        )
        assert settled.status_code == 201, settled.text
        assert settled.json()["status"] == "payment_completed_pending_posting"
        finalized = await _finalize_payment(
            client,
            case,
            request_id,
            key=f"membership-renew-finalize:{uuid4()}",
        )
        assert finalized.status_code == 201, finalized.text
        assert finalized.json()["status"] == "settled"

        current_after = await client.get(
            f"/api/v1/memberships/customer/{case.customer_id}",
            headers=_headers(case, f"membership-renew-current:{uuid4()}"),
        )
        assert current_after.status_code == 200, current_after.text
        renewed = current_after.json()
        assert renewed is not None
        assert renewed["id"] != str(expired.id)
        assert renewed["is_active"] is True
        assert renewed["payment_receipt_no"].startswith("M/")

        history_after = await client.get(
            f"/api/v1/memberships/customer/{case.customer_id}/history",
            headers=_headers(case, f"membership-renew-history:{uuid4()}"),
        )
        assert history_after.status_code == 200, history_after.text
        rows = history_after.json()
        assert len(rows) == 2
        assert {row["id"] for row in rows} == {str(expired.id), renewed["id"]}
        assert sum(bool(row["is_active"]) for row in rows) == 1

        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count())
                .select_from(CustomerMembership)
                .where(CustomerMembership.customer_id == case.customer_id)
            ) == 2
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipPayment)
                .where(MembershipPayment.request_id == UUID(request_id))
            ) == 1
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cancel_renewal_replays_and_keeps_paid_term_active_until_expiry(
    client, session, seed_owner
) -> None:
    """Offline/retry cancellation converges without ending prepaid benefits."""
    case = await _seed_case(session, seed_owner)
    now = datetime.now(UTC).replace(microsecond=0)
    membership = CustomerMembership(
        id=uuid4(),
        customer_id=case.customer_id,
        tier_id=case.tier_id,
        billing_cycle="monthly",
        starts_at=now - timedelta(days=3),
        expires_at=now + timedelta(days=27),
        auto_renew=True,
        amount_paid_minor=case.amount_minor,
        notes="Auto-renew cancellation workflow fixture",
    )
    session.add(membership)
    await session.commit()
    key = f"membership-cancel:{uuid4()}"
    try:
        first = await client.post(
            f"/api/v1/memberships/{membership.id}/cancel",
            headers=_headers(case, key),
        )
        assert first.status_code == 200, first.text
        body = first.json()
        assert body["auto_renew"] is False
        assert body["cancelled_at"] is not None
        assert body["is_active"] is True

        exact_retry = await client.post(
            f"/api/v1/memberships/{membership.id}/cancel",
            headers=_headers(case, key),
        )
        assert exact_retry.status_code == 200, exact_retry.text
        assert exact_retry.json() == body

        # A response-loss recovery generated with a new local cancellation row
        # still converges to the same desired state and cannot shorten the term.
        convergent_retry = await client.post(
            f"/api/v1/memberships/{membership.id}/cancel",
            headers=_headers(case, f"membership-cancel-recovery:{uuid4()}"),
        )
        assert convergent_retry.status_code == 200, convergent_retry.text
        assert convergent_retry.json()["cancelled_at"] == body["cancelled_at"]
        assert convergent_retry.json()["expires_at"] == body["expires_at"]
        assert convergent_retry.json()["is_active"] is True

        async with AsyncSessionLocal() as verify:
            saved = await verify.get(CustomerMembership, membership.id)
            assert saved is not None
            assert saved.auto_renew is False
            assert saved.cancelled_at is not None
            assert saved.expires_at == membership.expires_at
            assert await verify.scalar(
                select(func.count())
                .select_from(CustomerMembership)
                .where(CustomerMembership.customer_id == case.customer_id)
            ) == 1
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("method", ("cash", "upi"))
async def test_payment_begin_replay_is_same_actor_only(
    client, session, seed_owner, method: str
) -> None:
    """All sale begin endpoints converge for the starter and name conflicts."""
    case = await _seed_case(session, seed_owner)
    other_owner, other_token = await _add_protected_owner(
        session, case, name="Second Recovery Owner"
    )
    try:
        request = await _prepare(
            client,
            case,
            method=method,
            key=f"membership-begin-owner-prepare:{uuid4()}",
        )
        first = await _begin(
            client,
            case,
            request["id"],
            method=method,
            key=f"membership-begin-owner-first:{uuid4()}",
        )
        replay = await _begin(
            client,
            case,
            request["id"],
            method=method,
            key=f"membership-begin-owner-replay:{uuid4()}",
        )
        assert replay["status"] == first["status"]
        assert replay["action_started_by"] == str(case.owner_id)
        assert replay["action_started_by_name"] == seed_owner["owner"].name

        endpoint = (
            "begin-cash-collection" if method == "cash" else "begin-provider-action"
        )
        flag = "ready_to_collect" if method == "cash" else "ready_to_start"
        conflict = await client.post(
            f"/api/v1/memberships/payment-requests/{request['id']}/{endpoint}",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                flag: True,
            },
            headers=_headers(
                case,
                f"membership-begin-owner-conflict:{uuid4()}",
                token=other_token,
            ),
        )
        assert conflict.status_code == 422, conflict.text
        assert seed_owner["owner"].name in conflict.text
        assert "Do not repeat the money action" in conflict.text

        model = (
            MembershipPaymentCashCollection
            if method == "cash"
            else MembershipPaymentProviderAction
        )
        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count()).select_from(model).where(
                    model.request_id == UUID(request["id"])
                )
            ) == 1
    finally:
        await _cleanup(case, extra_user_id=other_owner.id)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_shift_close_blocks_all_membership_money_tasks_until_resolved(
    client, session, seed_owner
) -> None:
    """The locked Shift must gate both sale and refund reservations on every rail."""
    case = await _seed_case(session, seed_owner)
    try:
        request = await _prepare(
            client,
            case,
            method="upi",
            key=f"membership-close-prepare:{uuid4()}",
        )
        blocked_payment = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_drawer_minor},
            headers=_headers(case, f"membership-close-blocked-payment:{uuid4()}"),
        )
        assert blocked_payment.status_code == 422, blocked_payment.text
        assert "accepted membership payment task" in blocked_payment.text

        await _begin(
            client,
            case,
            request["id"],
            method="upi",
            key=f"membership-close-begin:{uuid4()}",
        )
        completed_sale = await _settle(
            client,
            case,
            request["id"],
            method="upi",
            key=f"membership-close-settle:{uuid4()}",
            external_reference=f"UPI-CLOSE-{uuid4().hex}",
        )
        assert completed_sale.status_code == 201, completed_sale.text
        assert completed_sale.json()["status"] == "payment_completed_pending_posting"

        blocked_completion = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_drawer_minor},
            headers=_headers(case, f"membership-close-blocked-completion:{uuid4()}"),
        )
        assert blocked_completion.status_code == 422, blocked_completion.text
        assert "accepted membership payment task" in blocked_completion.text

        sale = await _finalize_payment(
            client,
            case,
            request["id"],
            key=f"membership-close-finalize:{uuid4()}",
        )
        assert sale.status_code == 201, sale.text
        assert sale.json()["status"] == "settled"

        accepted_refund = await client.post(
            f"/api/v1/memberships/{sale.json()['membership_id']}/refund",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "method": "upi",
                "reason": "Close-gate provider refund proof",
            },
            headers=_headers(case, f"membership-close-refund:{uuid4()}"),
        )
        assert accepted_refund.status_code == 201, accepted_refund.text
        assert accepted_refund.json()["status"] == "accepted_provider_due"

        blocked_refund = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_drawer_minor},
            headers=_headers(case, f"membership-close-blocked-refund:{uuid4()}"),
        )
        assert blocked_refund.status_code == 422, blocked_refund.text
        assert "accepted membership refund task" in blocked_refund.text

        withdrawn = await client.post(
            f"/api/v1/memberships/refunds/{accepted_refund.json()['id']}/withdraw",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "resolution": "provider_not_completed",
                "reason": "Provider payout was not started",
                "action_state_verified": False,
            },
            headers=_headers(case, f"membership-close-refund-withdraw:{uuid4()}"),
        )
        assert withdrawn.status_code == 201, withdrawn.text
        assert withdrawn.json()["status"] == "withdrawn"

        recovery_action_id = f"membership-refund:{uuid4()}"
        registered_recovery = await client.post(
            "/api/v1/memberships/refund-attempts/register",
            json={
                "original_client_action_id": recovery_action_id,
                "customer_id": str(case.customer_id),
                "membership_id": sale.json()["membership_id"],
                "payment_id": sale.json()["payment_id"],
                "source_shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "paid_via": "upi",
                "captured_at": datetime.now(UTC).isoformat(),
            },
            headers=_headers(case, f"membership-close-recovery-register:{uuid4()}"),
        )
        assert registered_recovery.status_code == 201, registered_recovery.text
        recovery_id = registered_recovery.json()["id"]

        blocked_recovery = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_drawer_minor},
            headers=_headers(case, f"membership-close-blocked-recovery:{uuid4()}"),
        )
        assert blocked_recovery.status_code == 422, blocked_recovery.text
        assert "saved membership refund recovery" in blocked_recovery.text
        assert recovery_id in blocked_recovery.text

        resolved_recovery = await client.post(
            "/api/v1/memberships/refund-attempts/resolve",
            json={
                "original_client_action_id": recovery_action_id,
                "customer_id": str(case.customer_id),
                "membership_id": sale.json()["membership_id"],
                "payment_id": sale.json()["payment_id"],
                "source_shift_id": str(case.shift_id),
                "reconciliation_shift_id": None,
                "expected_amount_minor": case.amount_minor,
                "paid_via": "upi",
                "outcome": "no_payout",
                "reason": "Provider search proved that no refund payout was created",
                "provider_status": "not_completed",
                "verification_reference": f"NO-PAYOUT-{uuid4().hex}",
                "evidence_occurred_at": datetime.now(UTC).isoformat(),
                "cash_handover_confirmed": False,
            },
            headers=_headers(case, f"membership-close-recovery-resolve:{uuid4()}"),
        )
        assert resolved_recovery.status_code == 201, resolved_recovery.text

        closed = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": case.opening_drawer_minor},
            headers=_headers(case, f"membership-close-success:{uuid4()}"),
        )
        assert closed.status_code == 200, closed.text
        assert closed.json()["status"] == "closed"
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_provider_refund_takeover_is_audited_and_does_not_move_drawer(
    client, session, seed_owner
) -> None:
    install_audit_listeners()
    case = await _seed_case(session, seed_owner)
    other_owner = User(
        id=uuid4(),
        company_id=case.company_id,
        email=f"recovery-{uuid4().hex[:8]}@test.local",
        name="Recovery Owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add(other_owner)
    await session.commit()
    other_token = issue_access_token(
        user_id=other_owner.id,
        company_id=case.company_id,
        branch_id=case.branch_id,
        roles=["owner"],
        auth_version=other_owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    try:
        request = await _prepare(
            client,
            case,
            method="upi",
            key=f"membership-prepare-upi:{uuid4()}",
        )
        await _begin(
            client,
            case,
            request["id"],
            method="upi",
            key=f"membership-begin-upi:{uuid4()}",
        )
        completed_sale = await _settle(
            client,
            case,
            request["id"],
            method="upi",
            key=f"membership-settle-upi:{uuid4()}",
            # A provider may legitimately return a short opaque reference after
            # value has moved. Preserve it, but surface reconciliation risk.
            external_reference="S",
        )
        assert completed_sale.status_code == 201, completed_sale.text
        assert completed_sale.json()["status"] == "payment_completed_pending_posting"
        assert completed_sale.json()["provider_evidence_reconciled"] is False
        sale = await _finalize_payment(
            client,
            case,
            request["id"],
            key=f"membership-finalize-upi:{uuid4()}",
        )
        assert sale.status_code == 201, sale.text
        assert sale.json()["status"] == "settled"
        membership_id = sale.json()["membership_id"]

        accept_key = f"membership-refund-accept:{uuid4()}"
        accepted = await client.post(
            f"/api/v1/memberships/{membership_id}/refund",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "method": "upi",
                "reason": "Wrong plan selected by owner",
            },
            headers=_headers(case, accept_key),
        )
        assert accepted.status_code == 201, accepted.text
        assert accepted.json()["status"] == "accepted_provider_due"
        refund_id = accepted.json()["id"]
        async with AsyncSessionLocal() as verify:
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            membership = await verify.get(CustomerMembership, UUID(membership_id))
            assert shift.expected_minor == case.opening_drawer_minor
            assert customer.total_spent_minor == case.opening_ltv_minor + case.amount_minor
            assert membership.revoked_at is not None
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefundSettlement).where(
                    MembershipRefundSettlement.refund_id == UUID(refund_id)
                )
            ) == 0

        begin_key = f"membership-refund-begin-provider:{uuid4()}"
        begun = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/begin-provider-action",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_start": True,
            },
            headers=_headers(case, begin_key),
        )
        assert begun.status_code == 201, begun.text
        assert begun.json()["action_started_by_name"] == seed_owner["owner"].name

        no_takeover = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/settle-provider",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "settled_at": datetime.now(UTC).isoformat(),
                "provider_refund_completed": True,
                "external_reference": f"UPI-REFUND-{uuid4().hex}",
            },
            headers=_headers(
                case, f"membership-refund-no-takeover:{uuid4()}", token=other_token
            ),
        )
        assert no_takeover.status_code == 422, no_takeover.text

        provider_ref = "R"
        settle_key = f"membership-refund-settle-provider:{uuid4()}"
        refund_completed_at = datetime.now(UTC).isoformat()
        settled = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/settle-provider",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "settled_at": refund_completed_at,
                "provider_refund_completed": True,
                "external_reference": provider_ref,
                "action_takeover_confirmed": True,
                "action_takeover_reason": (
                    "Verified provider dashboard after original owner became unavailable"
                ),
            },
            headers=_headers(case, settle_key, token=other_token),
        )
        assert settled.status_code == 201, settled.text
        completion_body = settled.json()
        assert completion_body["status"] == "payout_completed_pending_posting"
        assert completion_body["receipt_no"] is None
        assert completion_body["payout_completed_by_name"] == "Recovery Owner"
        assert completion_body["settled_by_name"] is None
        assert completion_body["action_takeover_confirmed"] is True
        assert completion_body["provider_evidence_reconciled"] is False

        settle_replay = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/settle-provider",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "settled_at": refund_completed_at,
                "provider_refund_completed": True,
                "external_reference": provider_ref,
                "action_takeover_confirmed": True,
                "action_takeover_reason": (
                    "Verified provider dashboard after original owner became unavailable"
                ),
            },
            headers=_headers(case, settle_key, token=other_token),
        )
        assert settle_replay.status_code == 201, settle_replay.text
        assert settle_replay.json() == completion_body

        # Completion survives independently from accounting. Until finalize,
        # the provider payout is durable but no refund receipt/LTV change exists.
        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefundCompletion).where(
                    MembershipRefundCompletion.refund_id == UUID(refund_id)
                )
            ) == 1
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefundSettlement).where(
                    MembershipRefundSettlement.refund_id == UUID(refund_id)
                )
            ) == 0
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert shift.expected_minor == case.opening_drawer_minor
            assert customer.total_spent_minor == case.opening_ltv_minor + case.amount_minor

        finalize_key = f"membership-refund-finalize-provider:{uuid4()}"
        finalized = await _finalize_refund(
            client,
            case,
            refund_id,
            key=finalize_key,
            token=other_token,
        )
        assert finalized.status_code == 201, finalized.text
        assert finalized.json()["status"] == "settled"
        assert finalized.json()["receipt_no"].startswith("R/")
        assert finalized.json()["settled_by_name"] == "Recovery Owner"
        assert finalized.json()["action_takeover_confirmed"] is True
        assert finalized.json()["provider_evidence_reconciled"] is False

        finalize_replay = await _finalize_refund(
            client,
            case,
            refund_id,
            key=finalize_key,
            token=other_token,
        )
        assert finalize_replay.status_code == 201, finalize_replay.text
        assert finalize_replay.json() == finalized.json()

        async with AsyncSessionLocal() as verify:
            settlement = (
                await verify.execute(
                    select(MembershipRefundSettlement).where(
                        MembershipRefundSettlement.refund_id == UUID(refund_id)
                    )
                )
            ).scalar_one()
            shift = await verify.get(Shift, case.shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert settlement.settled_by == other_owner.id
            assert settlement.external_ref == provider_ref
            assert settlement.provider_evidence_reconciled is False
            assert settlement.action_takeover_confirmed is True
            assert shift.expected_minor == case.opening_drawer_minor
            assert customer.total_spent_minor == case.opening_ltv_minor

        history = await client.get(
            "/api/v1/memberships/refunds?unresolved=false",
            headers={
                "Authorization": f"Bearer {other_token}",
                "X-Terminal-Id": str(case.terminal_id),
            },
        )
        assert history.status_code == 200, history.text
        row = next(item for item in history.json() if item["id"] == refund_id)
        assert row["settled_by_name"] == "Recovery Owner"
        assert row["action_started_by_name"] == seed_owner["owner"].name

        reconciliation_ids: list[UUID] = []
        evidence_targets = (
            ("payment", sale.json()["payment_id"]),
            ("refund_settlement", str(settlement.id)),
        )
        for target_type, target_id in evidence_targets:
            evidence_key = f"membership-evidence:{target_type}:{uuid4()}"
            evidence_payload = {
                "target_type": target_type,
                "target_id": target_id,
                "evidence_kind": "provider_reference",
                "proof_reference": f"PROOF-{target_type}-{uuid4().hex}",
                "reason": "Verified the short provider reference in its dashboard",
            }
            reconciled = await client.post(
                "/api/v1/memberships/evidence-reconciliations",
                json=evidence_payload,
                headers=_headers(case, evidence_key, token=other_token),
            )
            assert reconciled.status_code == 201, reconciled.text
            reconciliation_ids.append(UUID(reconciled.json()["id"]))
            evidence_replay = await client.post(
                "/api/v1/memberships/evidence-reconciliations",
                json=evidence_payload,
                headers=_headers(case, evidence_key, token=other_token),
            )
            assert evidence_replay.status_code == 201, evidence_replay.text
            assert evidence_replay.json() == reconciled.json()

        evidence_history = await client.get(
            "/api/v1/memberships/evidence-reconciliations",
            headers={
                "Authorization": f"Bearer {other_token}",
                "X-Terminal-Id": str(case.terminal_id),
            },
        )
        assert evidence_history.status_code == 200, evidence_history.text
        assert set(reconciliation_ids) <= {
            UUID(item["id"]) for item in evidence_history.json()
        }
        async with AsyncSessionLocal() as verify:
            payment = await verify.get(MembershipPayment, UUID(sale.json()["payment_id"]))
            unchanged_settlement = await verify.get(MembershipRefundSettlement, settlement.id)
            assert payment.provider_evidence_reconciled is False
            assert unchanged_settlement.provider_evidence_reconciled is False
            assert await verify.scalar(
                select(func.count()).select_from(MembershipEvidenceReconciliation).where(
                    MembershipEvidenceReconciliation.id.in_(reconciliation_ids)
                )
            ) == 2

        async with AsyncSessionLocal() as mutate:
            with pytest.raises(DBAPIError, match="append-only"):
                await mutate.execute(
                    delete(MembershipEvidenceReconciliation).where(
                        MembershipEvidenceReconciliation.id == reconciliation_ids[0]
                    )
                )
                await mutate.commit()
            await mutate.rollback()

        async with AsyncSessionLocal() as mutate:
            with pytest.raises(DBAPIError, match="append-only"):
                await mutate.execute(
                    update(MembershipRefundSettlement)
                    .where(MembershipRefundSettlement.id == settlement.id)
                    .values(amount_minor=1)
                )
                await mutate.commit()
            await mutate.rollback()

        async with AsyncSessionLocal() as mutate:
            with pytest.raises(DBAPIError, match="append-only"):
                await mutate.execute(
                    delete(MembershipRefundSettlement).where(
                        MembershipRefundSettlement.id == settlement.id
                    )
                )
                await mutate.commit()
            await mutate.rollback()
    finally:
        await _cleanup(case, extra_user_id=other_owner.id)


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize("method", ("cash", "upi"))
async def test_refund_begin_replay_is_same_actor_only(
    client, session, seed_owner, method: str
) -> None:
    """Cash handoff and provider-refund begin states are actor-owned facts."""
    case = await _seed_case(session, seed_owner)
    other_owner, other_token = await _add_protected_owner(
        session, case, name="Refund Takeover Owner"
    )
    try:
        request = await _prepare(
            client,
            case,
            method=method,
            key=f"membership-refund-begin-prepare:{uuid4()}",
        )
        await _begin(
            client,
            case,
            request["id"],
            method=method,
            key=f"membership-refund-begin-sale-action:{uuid4()}",
        )
        completed = await _settle(
            client,
            case,
            request["id"],
            method=method,
            key=f"membership-refund-begin-sale-complete:{uuid4()}",
            external_reference=(f"UPI-BEGIN-{uuid4().hex}" if method != "cash" else None),
        )
        assert completed.status_code == 201, completed.text
        sale = await _finalize_payment(
            client,
            case,
            request["id"],
            key=f"membership-refund-begin-sale-finalize:{uuid4()}",
        )
        assert sale.status_code == 201, sale.text

        refund_payload = {
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "method": method,
            "reason": "Begin ownership regression",
        }
        accepted_a, accepted_b = await asyncio.gather(
            client.post(
                f"/api/v1/memberships/{sale.json()['membership_id']}/refund",
                json=refund_payload,
                headers=_headers(case, f"membership-refund-begin-accept:{uuid4()}"),
            ),
            client.post(
                f"/api/v1/memberships/{sale.json()['membership_id']}/refund",
                json=refund_payload,
                headers=_headers(case, f"membership-refund-begin-accept:{uuid4()}"),
            ),
        )
        assert [accepted_a.status_code, accepted_b.status_code].count(201) == 1, (
            accepted_a.text,
            accepted_b.text,
        )
        accepted = accepted_a if accepted_a.status_code == 201 else accepted_b
        assert accepted.status_code == 201, accepted.text
        refund_id = accepted.json()["id"]
        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefund).where(
                    MembershipRefund.payment_id == UUID(sale.json()["payment_id"])
                )
            ) == 1
        endpoint = "begin-cash-handoff" if method == "cash" else "begin-provider-action"
        flag = "ready_to_handover" if method == "cash" else "ready_to_start"
        payload = {
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            flag: True,
        }
        first_key = f"membership-refund-begin-first:{uuid4()}"
        first = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/{endpoint}",
            json=payload,
            headers=_headers(case, first_key),
        )
        assert first.status_code == 201, first.text
        replay = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/{endpoint}",
            json=payload,
            headers=_headers(case, f"membership-refund-begin-replay:{uuid4()}"),
        )
        assert replay.status_code == 201, replay.text
        assert replay.json()["status"] == first.json()["status"]
        assert replay.json()["action_started_by"] == str(case.owner_id)
        assert replay.json()["action_started_by_name"] == seed_owner["owner"].name

        conflict = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/{endpoint}",
            json=payload,
            headers=_headers(
                case,
                f"membership-refund-begin-conflict:{uuid4()}",
                token=other_token,
            ),
        )
        assert conflict.status_code == 422, conflict.text
        assert seed_owner["owner"].name in conflict.text
        assert "Do not repeat the money action" in conflict.text

        model = (
            MembershipRefundCashHandoff
            if method == "cash"
            else MembershipRefundProviderAction
        )
        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count()).select_from(model).where(
                    model.refund_id == UUID(refund_id)
                )
            ) == 1
    finally:
        await _cleanup(case, extra_user_id=other_owner.id)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_legacy_cash_refund_attempt_is_quarantined_then_reconciled_once(
    client, session, seed_owner
) -> None:
    """A pre-0035 payout survives reinstall and never rewrites a closed drawer."""
    install_audit_listeners()
    case = await _seed_case(session, seed_owner)
    reconciliation_shift_id = uuid4()
    reconciliation_opening = case.amount_minor + 75_000
    other_company = Company(id=uuid4(), name="Foreign membership tenant")
    other_branch = Branch(
        id=uuid4(),
        company_id=other_company.id,
        name="Foreign branch",
        invoice_series_code="MN",
    )
    other_terminal = Terminal(
        id=uuid4(),
        branch_id=other_branch.id,
        name="Foreign terminal",
        device_id=f"foreign-membership-{uuid4()}",
    )
    other_owner = User(
        id=uuid4(),
        company_id=other_company.id,
        email=f"foreign-membership-{uuid4().hex[:8]}@test.local",
        name="Foreign Owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    session.add(other_company)
    await session.flush()
    session.add_all([other_branch, other_owner])
    await session.flush()
    session.add(other_terminal)
    await session.commit()
    other_token = issue_access_token(
        user_id=other_owner.id,
        company_id=other_company.id,
        branch_id=other_branch.id,
        roles=["owner"],
        auth_version=other_owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    try:
        request = await _prepare(
            client,
            case,
            method="cash",
            key=f"membership-legacy-sale-prepare:{uuid4()}",
        )
        await _begin(
            client,
            case,
            request["id"],
            method="cash",
            key=f"membership-legacy-sale-begin:{uuid4()}",
        )
        completed_sale = await _settle(
            client,
            case,
            request["id"],
            method="cash",
            key=f"membership-legacy-sale-complete:{uuid4()}",
        )
        assert completed_sale.status_code == 201, completed_sale.text
        sale = await _finalize_payment(
            client,
            case,
            request["id"],
            key=f"membership-legacy-sale-finalize:{uuid4()}",
        )
        assert sale.status_code == 201, sale.text
        sale_body = sale.json()
        source_expected = case.opening_drawer_minor + case.amount_minor

        closed = await client.post(
            f"/api/v1/pos/shifts/{case.shift_id}/close",
            json={"counted_minor": source_expected},
            headers=_headers(case, f"membership-legacy-close-source:{uuid4()}"),
        )
        assert closed.status_code == 200, closed.text

        session.add(
            Shift(
                id=reconciliation_shift_id,
                company_id=case.company_id,
                branch_id=case.branch_id,
                terminal_id=case.terminal_id,
                opened_by=case.owner_id,
                opened_at=datetime.now(UTC),
                opening_float_minor=reconciliation_opening,
                expected_minor=reconciliation_opening,
                status="open",
            )
        )
        await session.commit()

        original_action_id = f"membership-refund:{uuid4()}"
        captured_at = datetime.now(UTC).isoformat()
        registration_payload = {
            "original_client_action_id": original_action_id,
            "customer_id": str(case.customer_id),
            "membership_id": sale_body["membership_id"],
            "payment_id": sale_body["payment_id"],
            "source_shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "paid_via": "cash",
            "captured_at": captured_at,
        }

        # A protected owner from another company cannot adopt the immutable
        # payment merely by knowing its UUIDs. Tenant scope must also be in the
        # SELECT before FOR UPDATE: neither recovery endpoint may wait behind a
        # lock held on another company's shift.
        async with AsyncSessionLocal() as blocker:
            await blocker.execute(
                select(Shift).where(Shift.id == case.shift_id).with_for_update()
            )
            try:
                foreign_register_key = f"membership-foreign-register:{uuid4()}"
                cross_tenant = await asyncio.wait_for(
                    client.post(
                        "/api/v1/memberships/refund-attempts/register",
                        json=registration_payload,
                        headers={
                            "Authorization": f"Bearer {other_token}",
                            "X-Terminal-Id": str(other_terminal.id),
                            "Idempotency-Key": foreign_register_key,
                            "X-Client-Action-Id": foreign_register_key,
                            "X-Client-Platform": "android",
                            "X-Client-Version-Code": "21",
                        },
                    ),
                    timeout=2,
                )
                assert cross_tenant.status_code == 404, cross_tenant.text

                foreign_payment_key = f"membership-foreign-payment-resolve:{uuid4()}"
                cross_tenant_payment = await asyncio.wait_for(
                    client.post(
                        "/api/v1/memberships/payment-attempts/resolve",
                        json={
                            "original_client_action_id": f"membership-subscribe:{uuid4()}",
                            "customer_id": str(case.customer_id),
                            "tier_id": str(case.tier_id),
                            "shift_id": str(case.shift_id),
                            "expected_amount_minor": case.amount_minor,
                            "paid_via": "cash",
                            "resolution": "payment_not_collected",
                            "reason": "Foreign tenant lock-scope proof",
                            "cash_return_confirmed": False,
                        },
                        headers={
                            "Authorization": f"Bearer {other_token}",
                            "X-Terminal-Id": str(other_terminal.id),
                            "Idempotency-Key": foreign_payment_key,
                            "X-Client-Action-Id": foreign_payment_key,
                            "X-Client-Platform": "android",
                            "X-Client-Version-Code": "21",
                        },
                    ),
                    timeout=2,
                )
                assert cross_tenant_payment.status_code == 404, cross_tenant_payment.text
            finally:
                await blocker.rollback()

        register_key = f"membership-legacy-register:{uuid4()}"
        registered = await client.post(
            "/api/v1/memberships/refund-attempts/register",
            json=registration_payload,
            headers=_headers(case, register_key),
        )
        assert registered.status_code == 201, registered.text
        assert registered.json()["status"] == "unresolved"
        recovery_id = UUID(registered.json()["id"])

        register_replay = await client.post(
            "/api/v1/memberships/refund-attempts/register",
            json=registration_payload,
            headers=_headers(case, register_key),
        )
        assert register_replay.status_code == 201, register_replay.text
        assert register_replay.json() == registered.json()

        amount_mismatch = await client.post(
            "/api/v1/memberships/refund-attempts/register",
            json={**registration_payload, "expected_amount_minor": case.amount_minor + 1},
            headers=_headers(case, f"membership-legacy-register-mismatch:{uuid4()}"),
        )
        assert amount_mismatch.status_code == 422, amount_mismatch.text
        assert "immutable membership payment" in amount_mismatch.text

        unresolved = await client.get(
            f"/api/v1/memberships/refund-attempts?source_shift_id={case.shift_id}",
            headers={
                "Authorization": f"Bearer {case.token}",
                "X-Terminal-Id": str(case.terminal_id),
            },
        )
        assert unresolved.status_code == 200, unresolved.text
        assert [row["id"] for row in unresolved.json()] == [str(recovery_id)]

        resolution_payload = {
            "original_client_action_id": original_action_id,
            "customer_id": str(case.customer_id),
            "membership_id": sale_body["membership_id"],
            "payment_id": sale_body["payment_id"],
            "source_shift_id": str(case.shift_id),
            "reconciliation_shift_id": str(reconciliation_shift_id),
            "expected_amount_minor": case.amount_minor,
            "paid_via": "cash",
            "outcome": "cash_handed_over",
            "reason": "Verified the legacy cash refund was physically handed over",
            "cash_handover_confirmed": True,
        }
        resolve_key_a = f"membership-legacy-resolve:{uuid4()}"
        resolve_key_b = f"membership-legacy-resolve:{uuid4()}"
        resolved_a, resolved_b = await asyncio.gather(
            client.post(
                "/api/v1/memberships/refund-attempts/resolve",
                json=resolution_payload,
                headers=_headers(case, resolve_key_a),
            ),
            client.post(
                "/api/v1/memberships/refund-attempts/resolve",
                json=resolution_payload,
                headers=_headers(case, resolve_key_b),
            ),
        )
        assert resolved_a.status_code == 201, resolved_a.text
        assert resolved_b.status_code == 201, resolved_b.text
        assert resolved_a.json()["id"] == resolved_b.json()["id"]
        resolved_body = resolved_a.json()
        assert resolved_body["financial_status"] == "payout_completed_pending_posting"
        refund_id = UUID(resolved_body["refund_id"])
        resolution_id = UUID(resolved_body["id"])

        resolve_replay = await client.post(
            "/api/v1/memberships/refund-attempts/resolve",
            json=resolution_payload,
            headers=_headers(case, resolve_key_a),
        )
        assert resolve_replay.status_code == 201, resolve_replay.text
        assert resolve_replay.json() == resolved_body

        async with AsyncSessionLocal() as verify:
            source_shift = await verify.get(Shift, case.shift_id)
            reconciliation_shift = await verify.get(Shift, reconciliation_shift_id)
            membership = await verify.get(
                CustomerMembership, UUID(sale_body["membership_id"])
            )
            customer = await verify.get(Customer, case.customer_id)
            assert source_shift.status == "closed"
            assert source_shift.expected_minor == source_expected
            assert reconciliation_shift.expected_minor == reconciliation_opening
            assert membership.revoked_at is not None
            assert customer.total_spent_minor == case.opening_ltv_minor + case.amount_minor
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefundAttemptRecovery).where(
                    MembershipRefundAttemptRecovery.id == recovery_id
                )
            ) == 1
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefundAttemptResolution).where(
                    MembershipRefundAttemptResolution.id == resolution_id
                )
            ) == 1
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefundCompletion).where(
                    MembershipRefundCompletion.refund_id == refund_id
                )
            ) == 1
            assert await verify.scalar(
                select(func.count()).select_from(MembershipRefundSettlement).where(
                    MembershipRefundSettlement.refund_id == refund_id
                )
            ) == 0

        duplicate_refund = await client.post(
            f"/api/v1/memberships/{sale_body['membership_id']}/refund",
            json={
                "shift_id": str(reconciliation_shift_id),
                "expected_amount_minor": case.amount_minor,
                "method": "cash",
                "reason": "Must not create a second refund root",
            },
            headers=_headers(case, f"membership-legacy-duplicate-refund:{uuid4()}"),
        )
        assert duplicate_refund.status_code == 422, duplicate_refund.text

        finalize_key = f"membership-legacy-refund-finalize:{uuid4()}"
        finalized = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/finalize",
            json={
                "shift_id": str(reconciliation_shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=_headers(case, finalize_key),
        )
        assert finalized.status_code == 201, finalized.text
        assert finalized.json()["status"] == "settled"
        assert finalized.json()["receipt_no"].startswith("R/")
        finalize_replay = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/finalize",
            json={
                "shift_id": str(reconciliation_shift_id),
                "expected_amount_minor": case.amount_minor,
            },
            headers=_headers(case, finalize_key),
        )
        assert finalize_replay.status_code == 201, finalize_replay.text
        assert finalize_replay.json() == finalized.json()

        async with AsyncSessionLocal() as verify:
            source_shift = await verify.get(Shift, case.shift_id)
            reconciliation_shift = await verify.get(Shift, reconciliation_shift_id)
            customer = await verify.get(Customer, case.customer_id)
            assert source_shift.expected_minor == source_expected
            assert reconciliation_shift.expected_minor == (
                reconciliation_opening - case.amount_minor
            )
            assert customer.total_spent_minor == case.opening_ltv_minor
            settlement_id = await verify.scalar(
                select(MembershipRefundSettlement.id).where(
                    MembershipRefundSettlement.refund_id == refund_id
                )
            )
            assert settlement_id is not None
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipCustomerSpendApplication)
                .where(
                    MembershipCustomerSpendApplication.refund_settlement_id
                    == settlement_id
                )
            ) == 1

        resolved_list = await client.get(
            "/api/v1/memberships/refund-attempts?unresolved=true",
            headers={
                "Authorization": f"Bearer {case.token}",
                "X-Terminal-Id": str(case.terminal_id),
            },
        )
        assert resolved_list.status_code == 200, resolved_list.text
        assert str(recovery_id) not in {row["id"] for row in resolved_list.json()}

        for model, row_id in (
            (MembershipRefundAttemptRecovery, recovery_id),
            (MembershipRefundAttemptResolution, resolution_id),
        ):
            async with AsyncSessionLocal() as mutate:
                with pytest.raises(DBAPIError, match="append-only"):
                    await mutate.execute(
                        update(model).where(model.id == row_id).values(updated_at=datetime.now(UTC))
                    )
                    await mutate.commit()
                await mutate.rollback()
            async with AsyncSessionLocal() as mutate:
                with pytest.raises(DBAPIError, match="append-only"):
                    await mutate.execute(delete(model).where(model.id == row_id))
                    await mutate.commit()
                await mutate.rollback()
    finally:
        await _cleanup(case, extra_shift_ids=(reconciliation_shift_id,))
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(IdempotencyKey).where(
                    (IdempotencyKey.user_id == other_owner.id)
                    | (IdempotencyKey.terminal_id == other_terminal.id)
                )
            )
            await cleanup.execute(delete(AuditLog).where(AuditLog.company_id == other_company.id))
            await cleanup.execute(delete(User).where(User.id == other_owner.id))
            await cleanup.execute(delete(Terminal).where(Terminal.id == other_terminal.id))
            await cleanup.execute(delete(Branch).where(Branch.id == other_branch.id))
            await cleanup.execute(delete(Company).where(Company.id == other_company.id))
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_refund_recovery_resolve_and_same_shift_close_share_lock_order(
    client,
    session,
    seed_owner,
) -> None:
    """Resolve cannot hold Recovery while waiting behind shift close."""
    case = await _seed_case(session, seed_owner)
    try:
        payment_request = await _prepare(
            client,
            case,
            method="cash",
            key=f"membership-lock-order-prepare:{uuid4()}",
        )
        await _begin(
            client,
            case,
            payment_request["id"],
            method="cash",
            key=f"membership-lock-order-begin:{uuid4()}",
        )
        completed = await _settle(
            client,
            case,
            payment_request["id"],
            method="cash",
            key=f"membership-lock-order-complete:{uuid4()}",
        )
        assert completed.status_code == 201, completed.text
        finalized = await _finalize_payment(
            client,
            case,
            payment_request["id"],
            key=f"membership-lock-order-finalize:{uuid4()}",
        )
        assert finalized.status_code == 201, finalized.text
        payment_body = finalized.json()

        original_action_id = f"membership-refund:{uuid4()}"
        registered = await client.post(
            "/api/v1/memberships/refund-attempts/register",
            json={
                "original_client_action_id": original_action_id,
                "customer_id": str(case.customer_id),
                "membership_id": payment_body["membership_id"],
                "payment_id": payment_body["payment_id"],
                "source_shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "paid_via": "cash",
                "captured_at": datetime.now(UTC).isoformat(),
            },
            headers=_headers(case, f"membership-lock-order-register:{uuid4()}"),
        )
        assert registered.status_code == 201, registered.text
        recovery_id = UUID(registered.json()["id"])
        resolution_payload = {
            "original_client_action_id": original_action_id,
            "customer_id": str(case.customer_id),
            "membership_id": payment_body["membership_id"],
            "payment_id": payment_body["payment_id"],
            "source_shift_id": str(case.shift_id),
            "reconciliation_shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "paid_via": "cash",
            "outcome": "cash_handed_over",
            "reason": "Verified legacy cash handover on the same open shift",
            "cash_handover_confirmed": True,
        }

        close_task = None
        resolve_task = None
        async with AsyncSessionLocal() as blocker:
            await blocker.execute(
                select(Shift).where(Shift.id == case.shift_id).with_for_update()
            )
            try:
                # Queue close first on the held Shift. The historical inversion
                # made resolve lock Recovery and queue behind close on this same
                # Shift, forming a deterministic database deadlock on release.
                close_task = asyncio.create_task(
                    client.post(
                        f"/api/v1/pos/shifts/{case.shift_id}/close",
                        json={
                            "counted_minor": (
                                case.opening_drawer_minor + case.amount_minor
                            )
                        },
                        headers=_headers(
                            case, f"membership-lock-order-close:{uuid4()}"
                        ),
                    )
                )
                await _wait_for_shift_lock_waiters(1)
                resolve_task = asyncio.create_task(
                    client.post(
                        "/api/v1/memberships/refund-attempts/resolve",
                        json=resolution_payload,
                        headers=_headers(
                            case, f"membership-lock-order-resolve:{uuid4()}"
                        ),
                    )
                )
                await _wait_for_shift_lock_waiters(2)

                # Resolve must still be waiting on Shift and therefore cannot
                # already hold Recovery. NOWAIT makes this an exact lock-order
                # assertion rather than a timing-only concurrency smoke test.
                async with AsyncSessionLocal() as probe:
                    locked_recovery = (
                        await probe.execute(
                            select(MembershipRefundAttemptRecovery)
                            .where(MembershipRefundAttemptRecovery.id == recovery_id)
                            .with_for_update(nowait=True)
                        )
                    ).scalar_one()
                    assert locked_recovery.id == recovery_id
                    await probe.rollback()
            finally:
                await blocker.rollback()

        assert close_task is not None
        assert resolve_task is not None
        close_response, resolve_response = await asyncio.wait_for(
            asyncio.gather(close_task, resolve_task), timeout=5
        )
        assert close_response.status_code == 422, close_response.text
        assert (
            close_response.json()["error"]["details"]["issue"]
            == "unresolved_membership_refund_recovery"
        )
        assert resolve_response.status_code == 201, resolve_response.text
        assert (
            resolve_response.json()["financial_status"]
            == "payout_completed_pending_posting"
        )

        async with AsyncSessionLocal() as verify:
            shift = await verify.get(Shift, case.shift_id)
            assert shift.status == "open"
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipRefundAttemptResolution)
                .where(MembershipRefundAttemptResolution.recovery_id == recovery_id)
            ) == 1
            refund_id = UUID(resolve_response.json()["refund_id"])
            assert await verify.get(MembershipRefund, refund_id) is not None
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipRefundSettlement)
                .where(MembershipRefundSettlement.refund_id == refund_id)
            ) == 0
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_provider_unknown_state_cannot_release_request_and_outcomes_are_exclusive(
    client, session, seed_owner
) -> None:
    install_audit_listeners()
    case = await _seed_case(session, seed_owner)
    try:
        request = await _prepare(
            client,
            case,
            method="upi",
            key=f"membership-prepare-resolution:{uuid4()}",
        )
        await _begin(
            client,
            case,
            request["id"],
            method="upi",
            key=f"membership-begin-resolution:{uuid4()}",
        )
        unverified = await client.post(
            f"/api/v1/memberships/payment-requests/{request['id']}/withdraw",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "resolution": "provider_not_completed",
                "reason": "Provider screen checked",
                "action_state_verified": False,
            },
            headers=_headers(case, f"membership-withdraw-unverified:{uuid4()}"),
        )
        assert unverified.status_code == 422, unverified.text

        settled_key = f"membership-race-settle:{uuid4()}"
        withdraw_key = f"membership-race-withdraw:{uuid4()}"
        settlement, withdrawal = await asyncio.gather(
            _settle(
                client,
                case,
                request["id"],
                method="upi",
                key=settled_key,
                external_reference=f"UPI-RACE-{uuid4().hex}",
            ),
            client.post(
                f"/api/v1/memberships/payment-requests/{request['id']}/withdraw",
                json={
                    "shift_id": str(case.shift_id),
                    "expected_amount_minor": case.amount_minor,
                    "resolution": "provider_not_completed",
                    "reason": "Verified provider shows no completed charge",
                    "action_state_verified": True,
                    "provider_verification_status": "not_completed",
                    "provider_verification_reference": f"CHECK-{uuid4().hex}",
                    "provider_evidence_occurred_at": datetime.now(UTC).isoformat(),
                },
                headers=_headers(case, withdraw_key),
            ),
        )
        assert sorted((settlement.status_code, withdrawal.status_code))[0] in {201, 409}
        assert [settlement.status_code, withdrawal.status_code].count(201) == 1, (
            settlement.text,
            withdrawal.text,
        )
        async with AsyncSessionLocal() as verify:
            completions = int(
                await verify.scalar(
                    select(func.count()).select_from(MembershipPaymentCompletion).where(
                        MembershipPaymentCompletion.request_id == UUID(request["id"])
                    )
                )
                or 0
            )
            resolutions = int(
                await verify.scalar(
                    select(func.count()).select_from(MembershipPaymentRequestResolution).where(
                        MembershipPaymentRequestResolution.request_id == UUID(request["id"])
                    )
                )
                or 0
            )
            assert completions + resolutions == 1
            assert await verify.scalar(
                select(func.count()).select_from(MembershipPayment).where(
                    MembershipPayment.request_id == UUID(request["id"])
                )
            ) == 0
            if resolutions:
                resolution = (
                    await verify.execute(
                        select(MembershipPaymentRequestResolution).where(
                            MembershipPaymentRequestResolution.request_id
                            == UUID(request["id"])
                        )
                    )
                ).scalar_one()
                assert resolution.action_state_verified is True
                assert await verify.scalar(
                    select(func.count()).select_from(CustomerMembership).where(
                        CustomerMembership.customer_id == case.customer_id
                    )
                ) == 0
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_payment_action_cas_blocks_waiting_resolution_and_guc_bypass(
    client, session, seed_owner
) -> None:
    """A waiter cannot use a stale snapshot to withdraw across a begun action."""
    install_audit_listeners()
    case = await _seed_case(session, seed_owner)
    try:
        request = await _prepare(
            client,
            case,
            method="upi",
            key=f"membership-direct-action-race:{uuid4()}",
        )
        request_id = UUID(request["id"])
        action_id = uuid4()
        action_at = datetime.now(UTC)
        action_sql = """
            INSERT INTO membership_payment_provider_actions (
                id, request_id, company_id, branch_id, terminal_id, shift_id,
                method, started_at, started_by, idempotency_key
            ) VALUES (
                :id, :request_id, :company_id, :branch_id, :terminal_id, :shift_id,
                'upi', :started_at, :started_by, :idempotency_key
            )
        """
        resolution_sql = """
            INSERT INTO membership_payment_request_resolutions (
                id, request_id, company_id, branch_id, terminal_id, shift_id,
                paid_via, resolution, reason, external_reference, resolved_at,
                resolved_by, action_state_verified, action_takeover_confirmed,
                action_takeover_reason, idempotency_key
            ) VALUES (
                :id, :request_id, :company_id, :branch_id, :terminal_id, :shift_id,
                'upi', 'payment_not_collected', 'Concurrent stale withdrawal', NULL,
                :resolved_at, :resolved_by, FALSE, FALSE, NULL, :idempotency_key
            )
        """
        shared = {
            "request_id": request_id,
            "company_id": case.company_id,
            "branch_id": case.branch_id,
            "terminal_id": case.terminal_id,
            "shift_id": case.shift_id,
        }
        async with AsyncSessionLocal() as first:
            await first.execute(
                text(action_sql),
                {
                    **shared,
                    "id": action_id,
                    "started_at": action_at,
                    "started_by": case.owner_id,
                    "idempotency_key": f"direct-action:{uuid4()}",
                },
            )
            waiter = asyncio.create_task(
                _commit_direct_sql(
                    resolution_sql,
                    {
                        **shared,
                        "id": uuid4(),
                        "resolved_at": datetime.now(UTC),
                        "resolved_by": case.owner_id,
                        "idempotency_key": f"direct-withdraw:{uuid4()}",
                    },
                )
            )
            await asyncio.sleep(0.05)
            assert not waiter.done(), "second connection should wait on the CAS row"
            await first.commit()
        succeeded, error = await waiter
        assert succeeded is False
        assert "action outcome was not verified" in error

        async with AsyncSessionLocal() as mutate:
            await mutate.execute(
                text("SET LOCAL dcompany.allow_financial_history_maintenance = 'on'")
            )
            with pytest.raises(DBAPIError, match="append-only"):
                await mutate.execute(
                    update(MembershipPaymentRequest)
                    .where(MembershipPaymentRequest.id == request_id)
                    .values(amount_minor=1)
                )
                await mutate.commit()
            await mutate.rollback()

        # The CAS row is internal state, not an application-writable escape
        # hatch. Even the table owner/runtime role cannot forge or delete it
        # through normal DML.
        async with AsyncSessionLocal() as mutate:
            with pytest.raises(DBAPIError, match="internal-only"):
                await mutate.execute(
                    text(
                        "UPDATE membership_payment_workflow_guards "
                        "SET outcome_kind = 'withdrawn', outcome_id = :outcome_id "
                        "WHERE request_id = :request_id"
                    ),
                    {"outcome_id": uuid4(), "request_id": request_id},
                )
                await mutate.commit()
            await mutate.rollback()
        async with AsyncSessionLocal() as mutate:
            with pytest.raises(DBAPIError, match="internal-only"):
                await mutate.execute(
                    text(
                        "DELETE FROM membership_payment_workflow_guards "
                        "WHERE request_id = :request_id"
                    ),
                    {"request_id": request_id},
                )
                await mutate.commit()
            await mutate.rollback()

        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipPaymentProviderAction)
                .where(MembershipPaymentProviderAction.request_id == request_id)
            ) == 1
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipPaymentRequestResolution)
                .where(MembershipPaymentRequestResolution.request_id == request_id)
            ) == 0
            guard = (
                await verify.execute(
                    text(
                        "SELECT action_kind, outcome_kind "
                        "FROM membership_payment_workflow_guards "
                        "WHERE request_id = :request_id"
                    ),
                    {"request_id": request_id},
                )
            ).one()
            assert guard.action_kind == "provider_payment"
            assert guard.outcome_kind is None
    finally:
        await _cleanup(case)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_refund_completion_cas_blocks_waiting_unverified_resolution(
    client, session, seed_owner
) -> None:
    """Committed payout evidence wins over a stale no-payout withdrawal."""
    install_audit_listeners()
    case = await _seed_case(session, seed_owner)
    try:
        prepared = await _prepare(
            client,
            case,
            method="upi",
            key=f"membership-refund-cas-prepare:{uuid4()}",
        )
        await _begin(
            client,
            case,
            prepared["id"],
            method="upi",
            key=f"membership-refund-cas-begin-sale:{uuid4()}",
        )
        completed_sale = await _settle(
            client,
            case,
            prepared["id"],
            method="upi",
            key=f"membership-refund-cas-settle-sale:{uuid4()}",
            external_reference=f"UPI-CAS-SALE-{uuid4().hex}",
        )
        assert completed_sale.status_code == 201, completed_sale.text
        sale = await _finalize_payment(
            client,
            case,
            prepared["id"],
            key=f"membership-refund-cas-finalize-sale:{uuid4()}",
        )
        assert sale.status_code == 201, sale.text
        membership_id = sale.json()["membership_id"]
        accepted = await client.post(
            f"/api/v1/memberships/{membership_id}/refund",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "method": "upi",
                "reason": "CAS race regression",
            },
            headers=_headers(case, f"membership-refund-cas-accept:{uuid4()}"),
        )
        assert accepted.status_code == 201, accepted.text
        refund_id = UUID(accepted.json()["id"])
        begun = await client.post(
            f"/api/v1/memberships/refunds/{refund_id}/begin-provider-action",
            json={
                "shift_id": str(case.shift_id),
                "expected_amount_minor": case.amount_minor,
                "ready_to_start": True,
            },
            headers=_headers(case, f"membership-refund-cas-begin:{uuid4()}"),
        )
        assert begun.status_code == 201, begun.text
        async with AsyncSessionLocal() as verify:
            provider_action_id = await verify.scalar(
                select(MembershipRefundProviderAction.id).where(
                    MembershipRefundProviderAction.refund_id == refund_id
                )
            )
        assert provider_action_id is not None

        completion_sql = """
            INSERT INTO membership_refund_completions (
                id, refund_id, company_id, cash_handoff_id, provider_action_id,
                legacy_attempt_resolution_id, branch_id, terminal_id, shift_id,
                method, amount_minor, completed_at, completed_by, idempotency_key,
                external_reference, provider_evidence_reconciled,
                evidence_occurred_at, evidence_time_untrusted,
                action_takeover_confirmed, action_takeover_reason
            ) VALUES (
                :id, :refund_id, :company_id, NULL, :provider_action_id,
                NULL, :branch_id, :terminal_id, :shift_id,
                'upi', :amount_minor, :completed_at, :completed_by, :idempotency_key,
                :external_reference, TRUE, :completed_at, FALSE, FALSE, NULL
            )
        """
        resolution_sql = """
            INSERT INTO membership_refund_resolutions (
                id, refund_id, company_id, branch_id, terminal_id, shift_id,
                paid_via, resolution, reason, external_reference, resolved_at,
                resolved_by, action_state_verified, provider_verification_status,
                provider_verification_reference, provider_checked_at,
                provider_evidence_occurred_at, provider_evidence_time_untrusted,
                provider_evidence_reconciled, cash_return_confirmed,
                action_takeover_confirmed, action_takeover_reason, idempotency_key
            ) VALUES (
                :id, :refund_id, :company_id, :branch_id, :terminal_id, :shift_id,
                'upi', 'provider_not_completed', 'Concurrent provider withdrawal',
                NULL, :resolved_at, :resolved_by, TRUE, 'not_completed',
                :verification_reference, :resolved_at, :resolved_at, FALSE,
                TRUE, FALSE, FALSE, NULL, :idempotency_key
            )
        """
        shared = {
            "refund_id": refund_id,
            "company_id": case.company_id,
            "branch_id": case.branch_id,
            "terminal_id": case.terminal_id,
            "shift_id": case.shift_id,
        }
        async with AsyncSessionLocal() as first:
            completed_at = datetime.now(UTC)
            completion_id = uuid4()
            await first.execute(
                text(completion_sql),
                {
                    **shared,
                    "id": completion_id,
                    "provider_action_id": provider_action_id,
                    "amount_minor": case.amount_minor,
                    "completed_at": completed_at,
                    "completed_by": case.owner_id,
                    "idempotency_key": f"direct-refund-complete:{uuid4()}",
                    "external_reference": f"UPI-CAS-REFUND-{uuid4().hex}",
                },
            )
            waiter = asyncio.create_task(
                _commit_direct_sql(
                    resolution_sql,
                    {
                        **shared,
                        "id": uuid4(),
                        "resolved_at": datetime.now(UTC),
                        "resolved_by": case.owner_id,
                        "verification_reference": f"CHECK-{uuid4().hex}",
                        "idempotency_key": f"direct-refund-withdraw:{uuid4()}",
                    },
                )
            )
            await asyncio.sleep(0.05)
            assert not waiter.done(), "second connection should wait on the CAS row"
            await first.commit()
        succeeded, error = await waiter
        assert succeeded is False
        assert "payout moved and lacks reversal proof" in error

        async with AsyncSessionLocal() as mutate:
            with pytest.raises(DBAPIError, match="internal-only"):
                await mutate.execute(
                    text(
                        "UPDATE membership_refund_workflow_guards "
                        "SET outcome_kind = NULL, outcome_id = NULL "
                        "WHERE refund_id = :refund_id"
                    ),
                    {"refund_id": refund_id},
                )
                await mutate.commit()
            await mutate.rollback()
        async with AsyncSessionLocal() as mutate:
            with pytest.raises(DBAPIError, match="internal-only"):
                await mutate.execute(
                    text(
                        "DELETE FROM membership_refund_workflow_guards "
                        "WHERE refund_id = :refund_id"
                    ),
                    {"refund_id": refund_id},
                )
                await mutate.commit()
            await mutate.rollback()

        async with AsyncSessionLocal() as verify:
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipRefundCompletion)
                .where(MembershipRefundCompletion.refund_id == refund_id)
            ) == 1
            assert await verify.scalar(
                select(func.count())
                .select_from(MembershipRefundResolution)
                .where(MembershipRefundResolution.refund_id == refund_id)
            ) == 0
            guard = (
                await verify.execute(
                    text(
                        "SELECT action_kind, completion_id, outcome_kind "
                        "FROM membership_refund_workflow_guards "
                        "WHERE refund_id = :refund_id"
                    ),
                    {"refund_id": refund_id},
                )
            ).one()
            assert guard.action_kind == "provider_refund"
            assert guard.completion_id == completion_id
            assert guard.outcome_kind is None
    finally:
        await _cleanup(case)
