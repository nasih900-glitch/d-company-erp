"""Regression proof for spendable loyalty-points accounting."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from app.models import Customer, Order, PointsRedemption, Shift
from app.services.pos.points import reserve_points_redemption


@pytest.mark.integration
@pytest.mark.asyncio
async def test_consumed_redemption_is_not_subtracted_from_already_net_balance(
    session,
    seed_owner,
) -> None:
    """A settled redemption is audit history, not a second reservation.

    The customer's balance below is already net of the earlier 20-point
    settlement. The remaining 10 must therefore be fully available to a new
    bill. The historical bug counted the consumed row again and returned zero.
    """
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)

    customer = Customer(
        id=uuid4(),
        company_id=company.id,
        name="Net points balance",
        phone=f"7{uuid4().int % 10**9:09d}",
        loyalty_points=10,
        lifetime_gaming_points_earned=30,
    )
    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=0,
        status="open",
    )
    session.add_all([customer, shift])
    await session.flush()

    settled_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        customer_id=customer.id,
        customer_phone=customer.phone,
        type="dine_in",
        status="paid",
        subtotal_minor=18_000,
        points_redeemed_minor=200,
        discount_minor=200,
        total_minor=17_800,
        opened_at=now - timedelta(minutes=30),
        closed_at=now - timedelta(minutes=25),
    )
    new_order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        customer_phone=customer.phone,
        type="dine_in",
        status="open",
        subtotal_minor=18_000,
        total_minor=18_000,
        opened_at=now,
    )
    session.add_all([settled_order, new_order])
    await session.flush()
    session.add(
        PointsRedemption(
            id=uuid4(),
            customer_id=customer.id,
            order_id=settled_order.id,
            points_spent=20,
            amount_minor=200,
            consumed_at=settled_order.closed_at,
        )
    )
    await session.flush()

    result = await reserve_points_redemption(
        session,
        order=new_order,
        company_id=company.id,
        requested_points=10,
        at=now,
    )

    assert result.points_spent == 10
    assert result.amount_minor == 100
