"""Postgres proof for discount authority and immutable held-bill handoff."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import delete, select, text

from app.core.db import AsyncSessionLocal
from app.core.security import hash_password, issue_access_token
from app.models import IdempotencyKey, Order, OrderCheckoutClaim, Role, Shift, User, UserRole


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


async def _cleanup(*, order_id, shift_id, idempotency_key: str) -> None:
    async with AsyncSessionLocal() as cleanup:
        await cleanup.execute(
            delete(OrderCheckoutClaim).where(OrderCheckoutClaim.order_id == order_id)
        )
        await cleanup.execute(delete(Order).where(Order.id == order_id))
        await cleanup.execute(delete(Shift).where(Shift.id == shift_id))
        await cleanup.execute(
            delete(IdempotencyKey).where(IdempotencyKey.key == idempotency_key)
        )
        await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_held_order_discount_is_frozen_even_with_active_claim(
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
            json={"manual_discount_minor": 500},
        )

        assert response.status_code == 422, response.text
        error = response.json()["error"]
        assert error["code"] == "business_rule"
        assert error["message"] == (
            "This bill is frozen after Send to POS. Apply the discount before "
            "sending it for payment."
        )
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
async def test_expired_claim_does_not_unfreeze_held_order(
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
            json={"manual_discount_minor": 500},
        )

        assert response.status_code == 422, response.text
        error = response.json()["error"]
        assert error["code"] == "business_rule"
        assert error["message"] == (
            "This bill is frozen after Send to POS. Apply the discount before "
            "sending it for payment."
        )
        async with AsyncSessionLocal() as verify:
            changed = await verify.get(Order, order.id)
            assert changed is not None
            assert changed.manual_discount_minor == 0
            assert changed.total_minor == 12_500
            assert await verify.get(OrderCheckoutClaim, claim.id) is not None
    finally:
        await _cleanup(order_id=order.id, shift_id=shift.id, idempotency_key=key)
