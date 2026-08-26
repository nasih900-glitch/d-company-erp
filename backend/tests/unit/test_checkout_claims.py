"""Checkout-claim regressions for two-device held-order billing."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import BackgroundTasks

from app.api.v1.pos import router as pos_router
from app.core.errors import (
    CheckoutClaimConflictError,
    CheckoutClaimExpiredError,
    CheckoutClaimInvalidError,
    CheckoutClaimRequiredError,
    CheckoutClaimStaleError,
    CheckoutClaimUnavailableError,
)
from app.core.tenant import TenantContext
from app.models import Payment
from app.services.pos.checkout_claims import (
    acquire_checkout_claim,
    release_checkout_claim,
    requires_checkout_claim,
    validate_checkout_claim,
)


class _Result:
    def __init__(self, scalar=None) -> None:
        self.scalar = scalar

    def scalar_one_or_none(self):
        return self.scalar

    def scalar_one(self):
        return self.scalar


class _ClaimSession:
    """One-row claim store; order locking is exercised by route tests below."""

    def __init__(self) -> None:
        self.claim = None
        self.flush_count = 0

    async def execute(self, _statement):
        return _Result(self.claim)

    def add(self, entity) -> None:
        self.claim = entity

    async def delete(self, entity) -> None:
        if self.claim is entity:
            self.claim = None

    async def flush(self) -> None:
        self.flush_count += 1


class _RouteSession:
    def __init__(self, *results) -> None:
        self.results = list(results)
        self.added = []
        self.deleted = []

    async def execute(self, _statement):
        if not self.results:
            raise AssertionError("unexpected database statement")
        return _Result(self.results.pop(0))

    def add(self, entity) -> None:
        self.added.append(entity)

    async def delete(self, entity) -> None:
        self.deleted.append(entity)

    async def flush(self) -> None:
        return None


def _order(*, status: str = "held", total_minor: int = 12_500, version: int = 7):
    return SimpleNamespace(
        id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        shift_id=uuid4(),
        status=status,
        total_minor=total_minor,
        checkout_version=version,
        tip_minor=0,
        table_id=None,
        type="dine_in",
        customer_phone=None,
        invoice_no=None,
        fiscal_year=None,
        invoice_issued_at=None,
    )


def _tenant(order, *, user_id=None) -> TenantContext:
    return TenantContext(
        user_id=user_id or uuid4(),
        company_id=order.company_id,
        branch_id=order.branch_id,
        terminal_id=order.terminal_id,
        roles=("cashier",),
    )


def _shift(order, tenant):
    return SimpleNamespace(
        id=order.shift_id,
        company_id=order.company_id,
        branch_id=order.branch_id,
        terminal_id=order.terminal_id,
        opened_by=tenant.user_id,
        status="open",
        expected_minor=0,
    )


@pytest.mark.asyncio
async def test_cashier_b_cannot_claim_cashier_as_active_bill() -> None:
    now = datetime(2026, 8, 25, 10, tzinfo=UTC)
    order = _order()
    terminal_id = order.terminal_id
    cashier_a = uuid4()
    cashier_b = uuid4()
    session = _ClaimSession()

    grant_a = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier_a,
        terminal_id=terminal_id,
        paid_minor=0,
        now=now,
        ttl_seconds=120,
    )

    assert grant_a.claim.token_hash != grant_a.token
    assert len(grant_a.claim.token_hash) == 64
    with pytest.raises(CheckoutClaimConflictError) as error:
        await acquire_checkout_claim(
            session,
            order=order,
            claimant_user_id=cashier_b,
            terminal_id=terminal_id,
            paid_minor=0,
            now=now + timedelta(seconds=1),
            ttl_seconds=120,
        )
    assert error.value.code == "checkout_claim_conflict"
    assert session.claim.claimed_by_user_id == cashier_a


@pytest.mark.asyncio
async def test_expired_claim_can_be_taken_over_and_old_token_is_rejected() -> None:
    now = datetime(2026, 8, 25, 10, tzinfo=UTC)
    order = _order()
    session = _ClaimSession()
    grant_a = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=uuid4(),
        terminal_id=order.terminal_id,
        paid_minor=0,
        now=now,
        ttl_seconds=30,
    )
    cashier_b = uuid4()
    grant_b = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier_b,
        terminal_id=order.terminal_id,
        paid_minor=0,
        now=now + timedelta(seconds=31),
        ttl_seconds=30,
    )

    assert grant_b.claim.claimed_by_user_id == cashier_b
    assert grant_b.token != grant_a.token
    with pytest.raises(CheckoutClaimInvalidError):
        await validate_checkout_claim(
            session,
            order=order,
            claimant_user_id=cashier_b,
            terminal_id=order.terminal_id,
            paid_minor=0,
            token=grant_a.token,
            now=now + timedelta(seconds=32),
        )


@pytest.mark.asyncio
@pytest.mark.parametrize("status", ["paid", "void", "refunded", "open"])
async def test_non_held_order_cannot_be_claimed(status: str) -> None:
    order = _order(status=status)
    with pytest.raises(CheckoutClaimUnavailableError) as error:
        await acquire_checkout_claim(
            _ClaimSession(),
            order=order,
            claimant_user_id=uuid4(),
            terminal_id=order.terminal_id,
            paid_minor=0,
        )
    assert error.value.code == "checkout_claim_unavailable"


@pytest.mark.asyncio
async def test_exact_zero_held_bill_can_be_claimed_for_zero_finalization() -> None:
    order = _order(total_minor=0)
    session = _ClaimSession()
    cashier = uuid4()

    grant = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
    )

    assert grant.claim.order_total_minor == 0
    assert grant.claim.due_minor == 0
    assert await validate_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
        token=grant.token,
    ) is grant.claim


@pytest.mark.asyncio
async def test_positive_held_bill_with_no_remaining_due_cannot_be_claimed() -> None:
    order = _order(total_minor=12_500)
    with pytest.raises(CheckoutClaimUnavailableError):
        await acquire_checkout_claim(
            _ClaimSession(),
            order=order,
            claimant_user_id=uuid4(),
            terminal_id=order.terminal_id,
            paid_minor=12_500,
        )


@pytest.mark.asyncio
async def test_claim_cannot_be_created_for_another_terminal() -> None:
    order = _order()
    with pytest.raises(CheckoutClaimInvalidError):
        await acquire_checkout_claim(
            _ClaimSession(),
            order=order,
            claimant_user_id=uuid4(),
            terminal_id=uuid4(),
            paid_minor=0,
        )


@pytest.mark.asyncio
async def test_same_claimant_retry_renews_and_rotates_token() -> None:
    now = datetime(2026, 8, 25, 10, tzinfo=UTC)
    order = _order()
    cashier = uuid4()
    session = _ClaimSession()
    first = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
        now=now,
        ttl_seconds=120,
    )
    second = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
        now=now + timedelta(seconds=10),
        ttl_seconds=120,
    )

    assert second.reused is True
    assert second.token != first.token
    assert second.claim.expires_at == now + timedelta(seconds=130)
    with pytest.raises(CheckoutClaimInvalidError):
        await validate_checkout_claim(
            session,
            order=order,
            claimant_user_id=cashier,
            terminal_id=order.terminal_id,
            paid_minor=0,
            token=first.token,
            now=now + timedelta(seconds=11),
        )
    assert await validate_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
        token=second.token,
        now=now + timedelta(seconds=11),
    ) is second.claim


@pytest.mark.asyncio
async def test_stale_total_due_or_version_requires_a_fresh_claim() -> None:
    now = datetime(2026, 8, 25, 10, tzinfo=UTC)
    order = _order(total_minor=12_500, version=7)
    cashier = uuid4()
    session = _ClaimSession()
    grant = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
        now=now,
    )

    order.total_minor = 13_000
    order.checkout_version = 8
    with pytest.raises(CheckoutClaimStaleError) as error:
        await validate_checkout_claim(
            session,
            order=order,
            claimant_user_id=cashier,
            terminal_id=order.terminal_id,
            paid_minor=0,
            token=grant.token,
            now=now + timedelta(seconds=1),
        )
    assert error.value.code == "checkout_claim_stale"
    assert error.value.details == {
        "order_total_minor": 13_000,
        "due_minor": 13_000,
        "order_version": 8,
        "reacquire": True,
    }

    # A stale row does not hold the queue hostage: another eligible cashier
    # can atomically replace it with a snapshot of the new authoritative bill.
    replacement = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=uuid4(),
        terminal_id=order.terminal_id,
        paid_minor=500,
        now=now + timedelta(seconds=2),
    )
    assert replacement.claim.order_total_minor == 13_000
    assert replacement.claim.due_minor == 12_500
    assert replacement.claim.order_version == 8


@pytest.mark.asyncio
async def test_expired_validation_has_distinct_error_code() -> None:
    now = datetime(2026, 8, 25, 10, tzinfo=UTC)
    order = _order()
    cashier = uuid4()
    session = _ClaimSession()
    grant = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
        now=now,
        ttl_seconds=30,
    )
    with pytest.raises(CheckoutClaimExpiredError) as error:
        await validate_checkout_claim(
            session,
            order=order,
            claimant_user_id=cashier,
            terminal_id=order.terminal_id,
            paid_minor=0,
            token=grant.token,
            now=now + timedelta(seconds=30),
        )
    assert error.value.code == "checkout_claim_expired"


@pytest.mark.asyncio
async def test_release_requires_the_owner_token_and_is_idempotent_after_delete() -> None:
    order = _order()
    cashier = uuid4()
    session = _ClaimSession()
    grant = await acquire_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        paid_minor=0,
    )
    with pytest.raises(CheckoutClaimInvalidError):
        await release_checkout_claim(
            session,
            order=order,
            claimant_user_id=cashier,
            terminal_id=order.terminal_id,
            token="wrong-but-long-enough-token-value",
        )
    assert await release_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        token=grant.token,
    )
    assert not await release_checkout_claim(
        session,
        order=order,
        claimant_user_id=cashier,
        terminal_id=order.terminal_id,
        token=grant.token,
    )


@pytest.mark.asyncio
async def test_held_payment_requires_claim_before_any_money_is_written(monkeypatch) -> None:
    order = _order()
    tenant = _tenant(order)
    shift = _shift(order, tenant)

    async def _reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="held-payment-without-claim",
            idempotency_request_hash="request-hash",
        )
    )
    session = _RouteSession(order, shift, 0)
    with pytest.raises(CheckoutClaimRequiredError) as error:
        await pos_router.record_payment(
            order.id,
            pos_router.PaymentCreate(
                method="cash",
                amount_minor=order.total_minor,
                expected_order_total_minor=order.total_minor,
                expected_due_minor=order.total_minor,
            ),
            session,
            request,
            BackgroundTasks(),
            tenant,
            None,
        )
    assert error.value.code == "checkout_claim_required"


@pytest.mark.asyncio
async def test_valid_held_payment_consumes_claim_in_sale_transaction(monkeypatch) -> None:
    now = datetime.now(UTC)
    order = _order()
    tenant = _tenant(order)
    shift = _shift(order, tenant)
    claim_session = _ClaimSession()
    grant = await acquire_checkout_claim(
        claim_session,
        order=order,
        claimant_user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        paid_minor=0,
        now=now,
    )

    async def _reserve(*_args, **_kwargs):
        return None

    stored = {}

    async def _store(*_args, **kwargs):
        stored.update(kwargs)

    async def _finalize(_session, *, order, **_kwargs):
        order.status = "paid"
        order.invoice_no = "TEST-0001"
        order.fiscal_year = "2026-27"
        order.invoice_issued_at = now

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    monkeypatch.setattr(pos_router, "store_response", _store)
    monkeypatch.setattr(pos_router, "_finalize_order", _finalize)
    monkeypatch.setattr(pos_router, "_schedule_order_paid_event", lambda *_args, **_kwargs: None)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="held-payment-valid-claim",
            idempotency_request_hash="request-hash",
        )
    )
    # order lock, shift lock, paid sum, then claim lock
    session = _RouteSession(order, shift, 0, grant.claim)
    response = await pos_router.record_payment(
        order.id,
        pos_router.PaymentCreate(
            method="upi",
            amount_minor=order.total_minor,
            expected_order_total_minor=order.total_minor,
            expected_due_minor=order.total_minor,
        ),
        session,
        request,
        BackgroundTasks(),
        tenant,
        grant.token,
    )

    assert response["order_status"] == "paid"
    assert any(isinstance(entity, Payment) for entity in session.added)
    assert session.deleted == [grant.claim]
    assert stored["status_code"] == 201


@pytest.mark.asyncio
async def test_held_zero_finalization_requires_claim_before_invoice(monkeypatch) -> None:
    order = _order(total_minor=0)
    tenant = _tenant(order)
    shift = _shift(order, tenant)

    async def _reserve(*_args, **_kwargs):
        return None

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="held-zero-without-claim",
            idempotency_request_hash="request-hash",
        )
    )
    session = _RouteSession(order, shift, 0)

    with pytest.raises(CheckoutClaimRequiredError) as error:
        await pos_router.finalize_zero_total_order(
            order.id,
            session,
            request,
            BackgroundTasks(),
            tenant,
            None,
        )

    assert error.value.code == "checkout_claim_required"
    assert order.status == "held"
    assert session.deleted == []


@pytest.mark.asyncio
async def test_valid_held_zero_finalization_consumes_claim_atomically(monkeypatch) -> None:
    now = datetime.now(UTC)
    order = _order(total_minor=0)
    tenant = _tenant(order)
    shift = _shift(order, tenant)
    claim_session = _ClaimSession()
    grant = await acquire_checkout_claim(
        claim_session,
        order=order,
        claimant_user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
        paid_minor=0,
        now=now,
    )

    async def _reserve(*_args, **_kwargs):
        return None

    async def _finalize(_session, *, order, **_kwargs):
        order.status = "paid"
        order.invoice_no = "TEST-ZERO-0001"
        order.fiscal_year = "2026-27"
        order.invoice_issued_at = now

    stored = {}

    async def _store(*_args, **kwargs):
        stored.update(kwargs)

    monkeypatch.setattr(pos_router, "check_or_reserve", _reserve)
    monkeypatch.setattr(pos_router, "_finalize_order", _finalize)
    monkeypatch.setattr(pos_router, "store_response", _store)
    monkeypatch.setattr(
        pos_router,
        "_schedule_order_paid_event",
        lambda *_args, **_kwargs: None,
    )
    request = SimpleNamespace(
        state=SimpleNamespace(
            idempotency_key="held-zero-valid-claim",
            idempotency_request_hash="request-hash",
        )
    )
    # order lock, shift lock, paid sum, then claim lock
    session = _RouteSession(order, shift, 0, grant.claim)

    response = await pos_router.finalize_zero_total_order(
        order.id,
        session,
        request,
        BackgroundTasks(),
        tenant,
        grant.token,
    )

    assert response["order_status"] == "paid"
    assert response["amount_minor"] == 0
    assert session.deleted == [grant.claim]
    assert stored["status_code"] == 200


@pytest.mark.asyncio
async def test_direct_pos_payment_path_does_not_query_or_require_a_claim() -> None:
    order = _order(status="open")
    assert await validate_checkout_claim(
        _RouteSession(),
        order=order,
        claimant_user_id=uuid4(),
        terminal_id=order.terminal_id,
        paid_minor=0,
        token=None,
    ) is None


@pytest.mark.asyncio
async def test_paid_shared_order_does_not_require_consumed_claim_for_read_replay() -> None:
    order = _order(status="paid", total_minor=0)
    order.table_id = uuid4()
    order.type = "session"

    assert not requires_checkout_claim(order)
    assert await validate_checkout_claim(
        _RouteSession(),
        order=order,
        claimant_user_id=uuid4(),
        terminal_id=order.terminal_id,
        paid_minor=0,
        token=None,
    ) is None


@pytest.mark.asyncio
async def test_open_table_bill_fails_closed_until_it_is_sent_to_pos() -> None:
    order = _order(status="open")
    order.table_id = uuid4()
    with pytest.raises(CheckoutClaimRequiredError):
        await validate_checkout_claim(
            _RouteSession(),
            order=order,
            claimant_user_id=uuid4(),
            terminal_id=order.terminal_id,
            paid_minor=0,
            token=None,
        )
    with pytest.raises(CheckoutClaimUnavailableError):
        await acquire_checkout_claim(
            _ClaimSession(),
            order=order,
            claimant_user_id=uuid4(),
            terminal_id=order.terminal_id,
            paid_minor=0,
        )


def test_checkout_version_is_database_enforced_for_every_bill_mutation() -> None:
    migration = (
        __import__("pathlib").Path(__file__).parents[2]
        / "alembic/versions/0031_order_checkout_claims.py"
    ).read_text()
    assert "BEFORE UPDATE OF" in migration
    for field in ("status", "total_minor", "discount_minor", "customer_phone"):
        assert field in migration
