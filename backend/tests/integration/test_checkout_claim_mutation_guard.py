"""Postgres proof for discount authority and versioned held-bill settlement."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, select, text

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password, issue_access_token
from app.models import (
    Customer,
    IdempotencyKey,
    Order,
    OrderCheckoutClaim,
    Role,
    Shift,
    User,
    UserRole,
)


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


async def _held_order_with_claim(session, seed_owner, *, expires_at: datetime):
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add(shift)
    await session.flush()
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="dine_in",
        status="held",
        subtotal_minor=12_500,
        discount_minor=0,
        manual_discount_minor=0,
        points_redeemed_minor=0,
        tax_minor=0,
        total_minor=12_500,
        opened_at=datetime.now(UTC),
        held_at=datetime.now(UTC),
    )
    session.add(order)
    await session.commit()
    await session.refresh(order)
    claim = OrderCheckoutClaim(
        id=uuid4(),
        order_id=order.id,
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        claimed_by_user_id=owner.id,
        token_hash="a" * 64,
        expires_at=expires_at,
        order_total_minor=12_500,
        due_minor=12_500,
        order_version=order.checkout_version,
    )
    session.add(claim)
    await session.commit()
    return order, shift, claim


def _headers(seed_owner, *, idempotency_key: str) -> dict[str, str]:
    owner = seed_owner["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        roles=["owner"],
        auth_version=owner.auth_version,
    )
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "Idempotency-Key": idempotency_key,
    }


async def _cashier_order(session, seed_owner, *, manual_discount_minor: int = 0):
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    cashier_role = Role(
        id=uuid4(),
        company_id=company.id,
        code="cashier",
        name="Cashier",
        permissions=[],
    )
    cashier = User(
        id=uuid4(),
        company_id=company.id,
        email=f"discount-cashier-{uuid4().hex[:10]}@test.local",
        name="Discount Cashier",
        password_hash=hash_password("not-used-password"),
        status="active",
    )
    session.add_all([cashier_role, cashier])
    await session.flush()
    assignment = UserRole(
        id=uuid4(),
        user_id=cashier.id,
        role_id=cashier_role.id,
        branch_id=branch.id,
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=cashier.id,
        opened_at=datetime.now(UTC),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    session.add_all([assignment, shift])
    await session.flush()
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=cashier.id,
        type="dine_in",
        status="open",
        subtotal_minor=12_500,
        discount_minor=manual_discount_minor,
        manual_discount_minor=manual_discount_minor,
        points_redeemed_minor=0,
        tax_minor=0,
        total_minor=12_500 - manual_discount_minor,
        opened_at=datetime.now(UTC),
        held_at=None,
    )
    session.add(order)
    await session.commit()
    # Migration 0047 invalidates existing credentials on every role-assignment
    # mutation. Refresh the actor before minting the test token so it carries
    # the authoritative version produced by the database trigger.
    await session.refresh(cashier)
    return order, shift, cashier, cashier_role, assignment


def _cashier_headers(seed_owner, cashier: User, *, idempotency_key: str) -> dict[str, str]:
    token = issue_access_token(
        user_id=cashier.id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        roles=["cashier"],
        auth_version=cashier.auth_version,
    )
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
        "Idempotency-Key": idempotency_key,
    }


async def _cleanup_cashier_order(
    *,
    order: Order,
    shift: Shift,
    cashier: User,
    cashier_role: Role,
    assignment: UserRole,
    idempotency_keys: tuple[str, ...],
) -> None:
    async with AsyncSessionLocal() as cleanup:
        await cleanup.execute(delete(Order).where(Order.id == order.id))
        await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
        await cleanup.execute(delete(UserRole).where(UserRole.id == assignment.id))
        await cleanup.execute(delete(User).where(User.id == cashier.id))
        await cleanup.execute(delete(Role).where(Role.id == cashier_role.id))
        await cleanup.execute(
            delete(IdempotencyKey).where(IdempotencyKey.key.in_(idempotency_keys))
        )
        await cleanup.commit()


async def _cleanup(
    *,
    order_id,
    shift_id,
    idempotency_key: str | tuple[str, ...],
    customer_id=None,
) -> None:
    idempotency_keys = (
        idempotency_key if isinstance(idempotency_key, tuple) else (idempotency_key,)
    )
    async with AsyncSessionLocal() as cleanup:
        await cleanup.execute(
            delete(OrderCheckoutClaim).where(OrderCheckoutClaim.order_id == order_id)
        )
        await cleanup.execute(delete(Order).where(Order.id == order_id))
        await cleanup.execute(delete(Shift).where(Shift.id == shift_id))
        if customer_id is not None:
            await cleanup.execute(delete(Customer).where(Customer.id == customer_id))
        await cleanup.execute(
            delete(IdempotencyKey).where(IdempotencyKey.key.in_(idempotency_keys))
        )
        await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_active_claim_blocks_versioned_held_order_discount(
    client,
    session,
    seed_owner,
) -> None:
    expires_at = datetime.now(UTC) + timedelta(minutes=5)
    order, shift, claim = await _held_order_with_claim(
        session,
        seed_owner,
        expires_at=expires_at,
    )
    key = f"claimed-discount-block-{uuid4()}"
    try:
        response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/discount",
            headers=_headers(seed_owner, idempotency_key=key),
            json={
                "manual_discount_minor": 500,
                "expected_checkout_version": order.checkout_version,
            },
        )

        assert response.status_code == 409, response.text
        error = response.json()["error"]
        assert error["code"] == "checkout_claim_conflict"
        assert "checkout is in progress" in error["message"]
        async with AsyncSessionLocal() as verify:
            unchanged = await verify.get(Order, order.id)
            assert unchanged is not None
            assert unchanged.manual_discount_minor == 0
            assert unchanged.total_minor == 12_500
            assert await verify.get(OrderCheckoutClaim, claim.id) is not None
    finally:
        await _cleanup(order_id=order.id, shift_id=shift.id, idempotency_key=key)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cashier_cannot_apply_nonzero_manual_discount(
    client,
    session,
    seed_owner,
) -> None:
    order, shift, cashier, cashier_role, assignment = await _cashier_order(
        session,
        seed_owner,
    )
    key = f"cashier-discount-denied-{uuid4()}"
    try:
        response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/discount",
            headers=_cashier_headers(seed_owner, cashier, idempotency_key=key),
            json={"manual_discount_minor": 500},
        )

        assert response.status_code == 403, response.text
        error = response.json()["error"]
        assert error["code"] == "forbidden"
        assert error["message"] == "missing permission: pos.discount.large"
        assert error["details"] == {"have": ["cashier"]}
        async with AsyncSessionLocal() as verify:
            unchanged = await verify.get(Order, order.id)
            assert unchanged is not None
            assert unchanged.manual_discount_minor == 0
            assert unchanged.total_minor == 12_500
            reserved = (
                await verify.execute(
                    select(IdempotencyKey).where(IdempotencyKey.key == key)
                )
            ).scalar_one_or_none()
            assert reserved is None, "an authorization denial must not reserve the write key"
    finally:
        await _cleanup_cashier_order(
            order=order,
            shift=shift,
            cashier=cashier,
            cashier_role=cashier_role,
            assignment=assignment,
            idempotency_keys=(key,),
        )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cashier_can_remove_an_existing_manual_discount(
    client,
    session,
    seed_owner,
) -> None:
    order, shift, cashier, cashier_role, assignment = await _cashier_order(
        session,
        seed_owner,
        manual_discount_minor=500,
    )
    key = f"cashier-discount-clear-{uuid4()}"
    try:
        response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/discount",
            headers=_cashier_headers(seed_owner, cashier, idempotency_key=key),
            json={"manual_discount_minor": 0},
        )

        assert response.status_code == 200, response.text
        assert response.json()["manual_discount_minor"] == 0
        assert response.json()["total_minor"] == 12_500
    finally:
        await _cleanup_cashier_order(
            order=order,
            shift=shift,
            cashier=cashier,
            cashier_role=cashier_role,
            assignment=assignment,
            idempotency_keys=(key,),
        )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_expired_claim_is_removed_before_versioned_held_order_discount(
    client,
    session,
    seed_owner,
) -> None:
    order, shift, claim = await _held_order_with_claim(
        session,
        seed_owner,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    key = f"expired-claim-discount-{uuid4()}"
    try:
        response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/discount",
            headers=_headers(seed_owner, idempotency_key=key),
            json={
                "manual_discount_minor": 500,
                "expected_checkout_version": order.checkout_version,
            },
        )

        assert response.status_code == 200, response.text
        assert response.json()["manual_discount_minor"] == 500
        assert response.json()["total_minor"] == 12_000
        assert response.json()["checkout_version"] > order.checkout_version
        async with AsyncSessionLocal() as verify:
            changed = await verify.get(Order, order.id)
            assert changed is not None
            assert changed.manual_discount_minor == 500
            assert changed.total_minor == 12_000
            assert await verify.get(OrderCheckoutClaim, claim.id) is None
    finally:
        await _cleanup(order_id=order.id, shift_id=shift.id, idempotency_key=key)


@pytest.mark.integration
@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("payload", "message_fragment"),
    [
        ({"manual_discount_minor": 500}, "current checkout version is required"),
        (
            {"manual_discount_minor": 500, "expected_checkout_version": 999_999},
            "This bill changed before changing its discount",
        ),
    ],
)
async def test_held_discount_rejects_missing_or_stale_checkout_version(
    client,
    session,
    seed_owner,
    payload,
    message_fragment,
) -> None:
    order, shift, _claim = await _held_order_with_claim(
        session,
        seed_owner,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    key = f"held-discount-version-{uuid4()}"
    try:
        response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/discount",
            headers=_headers(seed_owner, idempotency_key=key),
            json=payload,
        )

        assert response.status_code == 422, response.text
        error = response.json()["error"]
        assert error["code"] == "business_rule"
        assert message_fragment in error["message"]
        assert error["details"]["current_checkout_version"] == order.checkout_version
        async with AsyncSessionLocal() as verify:
            unchanged = await verify.get(Order, order.id)
            assert unchanged is not None
            assert unchanged.manual_discount_minor == 0
            assert unchanged.total_minor == 12_500
    finally:
        await _cleanup(order_id=order.id, shift_id=shift.id, idempotency_key=key)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_held_settlement_metadata_can_change_then_claims_exact_snapshot(
    client,
    session,
    seed_owner,
) -> None:
    """Customer, discount, points and reward are cashier-stage facts.

    They may change a held bill before collection, but each mutation consumes
    the exact version returned by the previous one. Only after those edits does
    checkout acquire a lease over the resulting immutable money snapshot.
    """
    order, shift, claim = await _held_order_with_claim(
        session,
        seed_owner,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    customer = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Initial payer",
        phone=f"9{uuid4().int % 10**9:09d}",
        loyalty_points=100,
        lifetime_gaming_points_earned=0,
    )
    session.add(customer)
    order.customer_id = customer.id
    order.customer_name = customer.name
    order.customer_phone = customer.phone
    await session.delete(claim)
    await session.commit()
    await session.refresh(order)

    keys = tuple(
        f"held-metadata-{name}-{uuid4()}"
        for name in ("customer", "discount", "points", "reward")
    )
    try:
        customer_response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/customer",
            headers=_headers(seed_owner, idempotency_key=keys[0]),
            json={
                "customer_name": "Final payer",
                "customer_phone": customer.phone,
                "expected_checkout_version": order.checkout_version,
            },
        )
        assert customer_response.status_code == 200, customer_response.text
        current = customer_response.json()
        assert current["customer_name"] == "Final payer"
        assert current["checkout_version"] > order.checkout_version

        discount_response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/discount",
            headers=_headers(seed_owner, idempotency_key=keys[1]),
            json={
                "manual_discount_minor": 500,
                "expected_checkout_version": current["checkout_version"],
            },
        )
        assert discount_response.status_code == 200, discount_response.text
        current = discount_response.json()
        assert current["manual_discount_minor"] == 500
        assert current["total_minor"] == 12_000

        points_response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/points",
            headers=_headers(seed_owner, idempotency_key=keys[2]),
            json={
                "points": 10,
                "expected_checkout_version": current["checkout_version"],
            },
        )
        assert points_response.status_code == 200, points_response.text
        current = points_response.json()
        assert current["points_redeemed"] == 10
        assert current["total_minor"] == 11_900

        reward_response = await client.patch(
            f"/api/v1/pos/orders/{order.id}/reward",
            headers=_headers(seed_owner, idempotency_key=keys[3]),
            json={
                "reward_key": "snack",
                "expected_checkout_version": current["checkout_version"],
            },
        )
        assert reward_response.status_code == 200, reward_response.text
        current = reward_response.json()
        assert current["points_redeemed"] == 60
        assert current["manual_discount_minor"] == 500
        assert current["total_minor"] == 8_000

        claimed = await client.post(
            f"/api/v1/pos/orders/{order.id}/checkout-claim",
            headers=_headers(seed_owner, idempotency_key=f"unused-claim-{uuid4()}"),
        )
        assert claimed.status_code == 201, claimed.text
        assert claimed.json()["order_version"] == current["checkout_version"]
        assert claimed.json()["order_total_minor"] == 8_000
        assert claimed.json()["due_minor"] == 8_000
    finally:
        await _cleanup(
            order_id=order.id,
            shift_id=shift.id,
            idempotency_key=keys,
            customer_id=customer.id,
        )
