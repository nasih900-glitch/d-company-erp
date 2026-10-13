"""Regression proof for spendable loyalty-points accounting."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import asyncio
import pytest
from sqlalchemy import delete, select, text

from app.core.db import AsyncSessionLocal
from app.core.errors import BusinessRuleError
from app.models import (
    Customer,
    CustomerGamingRankAward,
    CustomerMembership,
    MembershipTier,
    Order,
    PointsRedemption,
    Shift,
)
from app.services.pos.membership_benefits import reserve_membership_benefits
from app.services.pos.loyalty_program import load_loyalty_program
from app.services.pos.points import (
    award_eligible_gaming_ranks,
    consume_points_redemption,
    reprice_existing_points_redemption,
    reserve_points_redemption,
)
from app.services.pos.pricing import OrderPricingService


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


@pytest.mark.integration
@pytest.mark.asyncio
async def test_released_points_are_not_silently_reclaimed_after_another_order_spends_them(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)
    customer = Customer(
        id=uuid4(), company_id=company.id, name="No double spend",
        phone=f"6{uuid4().int % 10**9:09d}", loyalty_points=100,
    )
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id, opened_at=now,
        opening_float_minor=0, status="open",
    )
    orders = [
        Order(
            id=uuid4(), company_id=company.id, branch_id=branch.id,
            terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
            customer_id=customer.id, customer_phone=customer.phone,
            type="dine_in", status="open", subtotal_minor=10_000,
            total_minor=10_000, opened_at=now,
        )
        for _ in range(2)
    ]
    session.add_all([customer, shift])
    await session.flush()
    session.add_all(orders)
    await session.flush()
    program = await load_loyalty_program(
        session, company_id=company.id, company_lock="none",
    )

    reserved_a = await reserve_points_redemption(
        session, order=orders[0], company_id=company.id,
        requested_points=100, at=now, program=program,
    )
    await session.flush()
    assert reserved_a.points_spent == 100

    shrunk_a = await reprice_existing_points_redemption(
        session, order=orders[0], company_id=company.id, remaining_minor=400,
    )
    await session.flush()
    assert (shrunk_a.points_spent, shrunk_a.amount_minor) == (40, 400)

    reserved_b = await reserve_points_redemption(
        session, order=orders[1], company_id=company.id,
        requested_points=60, at=now, program=program,
    )
    await session.flush()
    assert reserved_b.points_spent == 60
    await consume_points_redemption(session, order_id=orders[1].id, at=now)
    await session.flush()
    assert customer.loyalty_points == 40

    grown_a = await reprice_existing_points_redemption(
        session, order=orders[0], company_id=company.id, remaining_minor=10_000,
    )
    assert (grown_a.points_spent, grown_a.amount_minor) == (40, 400)
    await consume_points_redemption(session, order_id=orders[0].id, at=now)
    assert customer.loyalty_points == 0

    underfunded_order = Order(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
        customer_id=customer.id, customer_phone=customer.phone,
        type="dine_in", status="open", subtotal_minor=100,
        total_minor=100, opened_at=now,
    )
    session.add(underfunded_order)
    await session.flush()
    underfunded = PointsRedemption(
        id=uuid4(), customer_id=customer.id, order_id=underfunded_order.id,
        points_spent=1, amount_minor=10, program_revision=program.revision,
        redemption_kind="generic", accepted_points_cost=1,
        accepted_value_minor=10, rules_snapshot=program.snapshot(),
    )
    session.add(underfunded)
    await session.flush()
    with pytest.raises(BusinessRuleError, match="reserved balance is underfunded"):
        await consume_points_redemption(session, order_id=underfunded_order.id, at=now)
    assert customer.loyalty_points == 0
    assert underfunded.consumed_at is None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_simultaneous_rank_crossing_awards_bonus_once(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)
    customer = Customer(
        id=uuid4(), company_id=company.id, phone=f"4{uuid4().int % 10**9:09d}",
        loyalty_points=0, lifetime_gaming_points_earned=200,
    )
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id,
        opened_at=now, opening_float_minor=0, status="open",
    )
    session.add_all([customer, shift])
    await session.flush()
    orders = [
        Order(
            id=uuid4(), company_id=company.id, branch_id=branch.id,
            terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
            customer_id=customer.id, customer_phone=customer.phone,
            type="session", status="open", subtotal_minor=1_000,
            total_minor=1_000, opened_at=now,
        )
        for _ in range(2)
    ]
    session.add_all(orders)
    await session.commit()

    first_locked = asyncio.Event()
    release_first = asyncio.Event()

    async def award(order_id, *, first: bool) -> int:
        async with AsyncSessionLocal() as tx:
            async with tx.begin():
                locked_customer = (
                    await tx.execute(
                        select(Customer).where(Customer.id == customer.id).with_for_update()
                    )
                ).scalar_one()
                if first:
                    first_locked.set()
                    await release_first.wait()
                program = await load_loyalty_program(
                    tx, company_id=company.id, company_lock="none",
                )
                locked_order = await tx.get(Order, order_id)
                assert locked_order is not None
                bonus = await award_eligible_gaming_ranks(
                    tx, customer=locked_customer, order=locked_order,
                    program=program, at=now,
                )
                await tx.flush()
                return bonus

    first_task = asyncio.create_task(award(orders[0].id, first=True))
    await first_locked.wait()
    second_task = asyncio.create_task(award(orders[1].id, first=False))
    await asyncio.sleep(0.05)
    assert not second_task.done()
    release_first.set()
    assert await first_task == 50
    assert await second_task == 0

    async with AsyncSessionLocal() as verify:
        rows = (
            await verify.execute(
                select(CustomerGamingRankAward).where(
                    CustomerGamingRankAward.customer_id == customer.id,
                )
            )
        ).scalars().all()
        assert {row.rank_key for row in rows} == {"rookie", "player"}
        assert sum(row.bonus_points for row in rows) == 50

    async with AsyncSessionLocal() as cleanup:
        await cleanup.execute(text(
            "ALTER TABLE customer_gaming_rank_awards DISABLE TRIGGER "
            "trg_customer_gaming_rank_awards_immutable"
        ))
        await cleanup.execute(delete(CustomerGamingRankAward).where(
            CustomerGamingRankAward.customer_id == customer.id,
        ))
        await cleanup.execute(text(
            "ALTER TABLE customer_gaming_rank_awards ENABLE TRIGGER "
            "trg_customer_gaming_rank_awards_immutable"
        ))
        await cleanup.execute(delete(Order).where(Order.id.in_([row.id for row in orders])))
        await cleanup.execute(delete(Customer).where(Customer.id == customer.id))
        await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
        await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_stable_order_customer_controls_membership_after_phone_reuse(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)
    branch.state_code = "32"
    company.gst_registration_type = "unregistered"
    company.is_composition = False
    old_phone = f"7{uuid4().int % 10**9:09d}"
    booked = Customer(
        id=uuid4(), company_id=company.id, name="Booked customer",
        phone=f"8{uuid4().int % 10**9:09d}",
    )
    replacement = Customer(
        id=uuid4(), company_id=company.id, name="Phone replacement", phone=old_phone,
    )
    booked_tier = MembershipTier(
        id=uuid4(), company_id=company.id, code=f"booked-{uuid4().hex[:8]}",
        name="Booked tier", monthly_price_minor=1_000,
        gaming_discount_pct=0.10, free_gaming_minutes_per_week=60,
    )
    replacement_tier = MembershipTier(
        id=uuid4(), company_id=company.id, code=f"replacement-{uuid4().hex[:8]}",
        name="Replacement tier", monthly_price_minor=1_000,
        gaming_discount_pct=0.50, free_gaming_minutes_per_week=240,
    )
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id,
        opened_at=now - timedelta(hours=1), opening_float_minor=0, status="open",
    )
    session.add_all([booked, replacement, booked_tier, replacement_tier, shift])
    await session.flush()
    session.add_all([
        CustomerMembership(
            id=uuid4(), customer_id=booked.id, tier_id=booked_tier.id,
            billing_cycle="monthly", starts_at=now - timedelta(days=1),
            expires_at=now + timedelta(days=10), amount_paid_minor=1_000,
        ),
        CustomerMembership(
            id=uuid4(), customer_id=replacement.id, tier_id=replacement_tier.id,
            billing_cycle="monthly", starts_at=now - timedelta(days=1),
            expires_at=now + timedelta(days=10), amount_paid_minor=1_000,
        ),
    ])
    order = Order(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
        customer_id=booked.id, customer_phone=old_phone, type="session", status="held",
        subtotal_minor=10_000, total_minor=10_000, opened_at=now,
    )
    session.add(order)
    await session.flush()

    priced = await OrderPricingService(session).price_time_based_line(
        company_id=company.id,
        branch_id=branch.id,
        amount_minor=10_000,
        tax_rate=0,
        rate_includes_tax=True,
        customer_id=order.customer_id,
        customer_phone=order.customer_phone,
        item_type="gaming",
    )
    benefits = await reserve_membership_benefits(
        session,
        order=order,
        company_id=company.id,
        requested_gaming_minutes=100,
        at=now,
    )

    assert priced.total_minor == 9_000
    assert benefits.gaming_minutes == 60
