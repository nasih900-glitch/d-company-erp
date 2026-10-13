"""Versioned loyalty program publication and tenant-facing contract."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from uuid import uuid4

import pytest
from sqlalchemy import delete, select, text

from app.api.v1.settings.router import _loyalty_reservation_blockers
from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.models import (
    Company,
    Customer,
    CustomerGamingRankAward,
    Order,
    PointsRedemption,
    Shift,
)
from app.services.pos.loyalty_program import load_loyalty_program
from app.services.pos.points import award_eligible_gaming_ranks, reserve_points_redemption


def _headers(seed, *, roles: list[str], key: str | None = None) -> dict[str, str]:
    owner = seed["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        roles=roles,
        auth_version=owner.auth_version,
    )
    headers = {"Authorization": f"Bearer {token}"}
    if key is not None:
        headers["Idempotency-Key"] = key
    return headers


@pytest.mark.integration
@pytest.mark.asyncio
async def test_publication_is_company_locked_idempotent_and_history_neutral(
    client,
    session,
    seed_owner,
) -> None:
    read_headers = _headers(seed_owner, roles=["owner"])
    initial = await client.get("/api/v1/settings/loyalty-program", headers=read_headers)
    assert initial.status_code == 200, initial.text
    current = initial.json()
    assert current["revision"] == 0
    assert current["published_by"] is None

    customer = Customer(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        phone=f"9{uuid4().int % 10**9:09d}",
        loyalty_points=500,
        lifetime_gaming_points_earned=250,
        visit_count=4,
        total_spent_minor=12_300,
    )
    session.add(customer)
    await session.commit()
    before = (
        customer.loyalty_points,
        customer.lifetime_gaming_points_earned,
        customer.visit_count,
        customer.total_spent_minor,
    )

    rules = current["rules"]
    rules["minor_per_point"] = 11
    payload = {"expected_revision": 0, "rules": rules}
    key_a = f"loyalty-publish-a-{uuid4()}"
    key_b = f"loyalty-publish-b-{uuid4()}"
    responses = await asyncio.gather(
        client.put(
            "/api/v1/settings/loyalty-program",
            headers=_headers(seed_owner, roles=["owner"], key=key_a),
            json=payload,
        ),
        client.put(
            "/api/v1/settings/loyalty-program",
            headers=_headers(seed_owner, roles=["owner"], key=key_b),
            json=payload,
        ),
    )
    assert sorted(response.status_code for response in responses) == [200, 409]
    accepted_index = next(i for i, response in enumerate(responses) if response.status_code == 200)
    accepted = responses[accepted_index]
    accepted_key = (key_a, key_b)[accepted_index]
    assert accepted.json()["revision"] == 1
    assert accepted.json()["value_of_100_points_minor"] == 1_100

    replay = await client.put(
        "/api/v1/settings/loyalty-program",
        headers=_headers(seed_owner, roles=["owner"], key=accepted_key),
        json=payload,
    )
    assert replay.status_code == 200
    assert replay.json() == accepted.json()

    await session.refresh(customer)
    assert (
        customer.loyalty_points,
        customer.lifetime_gaming_points_earned,
        customer.visit_count,
        customer.total_spent_minor,
    ) == before


@pytest.mark.integration
@pytest.mark.asyncio
async def test_non_manager_cannot_publish_points_program(client, seed_owner) -> None:
    current = await client.get(
        "/api/v1/settings/loyalty-program",
        headers=_headers(seed_owner, roles=["staff"]),
    )
    assert current.status_code == 200
    response = await client.put(
        "/api/v1/settings/loyalty-program",
        headers=_headers(seed_owner, roles=["staff"], key=f"staff-publish-{uuid4()}"),
        json={"expected_revision": 0, "rules": current.json()["rules"]},
    )
    assert response.status_code == 403


@pytest.mark.integration
@pytest.mark.asyncio
async def test_publication_lock_observes_a_concurrent_points_reservation(
    session,
    seed_owner,
) -> None:
    """Company SHARE->customer and company UPDATE use one lock order.

    If reservation starts first, publication cannot pass its blocker query
    until that reservation commits.
    """
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    customer = Customer(
        id=uuid4(), company_id=company.id, phone=f"5{uuid4().int % 10**9:09d}",
        loyalty_points=100,
    )
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id,
        opening_float_minor=0, opened_at=datetime.now(UTC), status="open",
    )
    session.add_all([customer, shift])
    await session.flush()
    order = Order(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
        customer_id=customer.id, customer_phone=customer.phone,
        type="dine_in", status="open", subtotal_minor=5_000,
        total_minor=5_000, opened_at=datetime.now(UTC),
    )
    session.add(order)
    await session.commit()

    share_locked = asyncio.Event()
    release_reserver = asyncio.Event()

    async def reserve() -> None:
        async with AsyncSessionLocal() as tx:
            async with tx.begin():
                program = await load_loyalty_program(tx, company_id=company.id)
                share_locked.set()
                await release_reserver.wait()
                locked_order = await tx.get(Order, order.id)
                assert locked_order is not None
                result = await reserve_points_redemption(
                    tx, order=locked_order, company_id=company.id,
                    requested_points=40, at=program.published_at, program=program,
                )
                assert result.points_spent == 40

    async def publication_blocker_count() -> int:
        await share_locked.wait()
        async with AsyncSessionLocal() as tx:
            async with tx.begin():
                await tx.execute(
                    select(Company).where(Company.id == company.id).with_for_update()
                )
                count, _ = await _loyalty_reservation_blockers(
                    tx, company_id=company.id,
                )
                return count

    reserve_task = asyncio.create_task(reserve())
    await share_locked.wait()
    publish_task = asyncio.create_task(publication_blocker_count())
    await asyncio.sleep(0.05)
    assert not publish_task.done()
    release_reserver.set()
    await reserve_task
    assert await publish_task == 1

    async with AsyncSessionLocal() as cleanup:
        await cleanup.execute(delete(PointsRedemption).where(PointsRedemption.order_id == order.id))
        await cleanup.execute(delete(Order).where(Order.id == order.id))
        await cleanup.execute(delete(Customer).where(Customer.id == customer.id))
        await cleanup.execute(delete(Shift).where(Shift.id == shift.id))
        await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_lowered_rank_is_awarded_on_next_paid_event_and_zero_bonus_never_repeats(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)
    customer = Customer(
        id=uuid4(), company_id=company.id,
        phone=f"3{uuid4().int % 10**9:09d}",
        loyalty_points=17, lifetime_gaming_points_earned=150,
    )
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id,
        opening_float_minor=0, opened_at=now, status="open",
    )
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
    session.add_all([customer, shift])
    await session.flush()
    session.add_all(orders)
    await session.commit()

    headers = _headers(seed_owner, roles=["owner"])
    initial = await client.get("/api/v1/settings/loyalty-program", headers=headers)
    assert initial.status_code == 200, initial.text
    revision_one_rules = initial.json()["rules"]
    revision_one_rules["ranks"][1]["threshold"] = 100
    revision_one_rules["ranks"][1]["bonus_points"] = 0
    revision_one = await client.put(
        "/api/v1/settings/loyalty-program",
        headers=_headers(seed_owner, roles=["owner"], key=f"lower-rank-{uuid4()}"),
        json={"expected_revision": 0, "rules": revision_one_rules},
    )
    assert revision_one.status_code == 200, revision_one.text

    # Publishing a lower threshold is future-only: it does not mutate the
    # customer or fabricate an award until the next paid gaming event calls
    # the settlement award path.
    async with AsyncSessionLocal() as verify:
        projected = await verify.get(Customer, customer.id)
        before_rows = (
            await verify.execute(
                select(CustomerGamingRankAward).where(
                    CustomerGamingRankAward.customer_id == customer.id,
                )
            )
        ).scalars().all()
        assert projected is not None and projected.loyalty_points == 17
        assert before_rows == []

    async with AsyncSessionLocal() as paid_event:
        async with paid_event.begin():
            locked_customer = (
                await paid_event.execute(
                    select(Customer).where(Customer.id == customer.id).with_for_update()
                )
            ).scalar_one()
            program = await load_loyalty_program(
                paid_event, company_id=company.id, company_lock="none",
            )
            paid_order = await paid_event.get(Order, orders[0].id)
            assert paid_order is not None
            assert await award_eligible_gaming_ranks(
                paid_event, customer=locked_customer, order=paid_order,
                program=program, at=now,
            ) == 0

    revision_two_rules = revision_one.json()["rules"]
    revision_two_rules["ranks"][1]["bonus_points"] = 60
    revision_two = await client.put(
        "/api/v1/settings/loyalty-program",
        headers=_headers(seed_owner, roles=["owner"], key=f"raise-bonus-{uuid4()}"),
        json={"expected_revision": 1, "rules": revision_two_rules},
    )
    assert revision_two.status_code == 200, revision_two.text

    async with AsyncSessionLocal() as later_paid_event:
        async with later_paid_event.begin():
            locked_customer = (
                await later_paid_event.execute(
                    select(Customer).where(Customer.id == customer.id).with_for_update()
                )
            ).scalar_one()
            program = await load_loyalty_program(
                later_paid_event, company_id=company.id, company_lock="none",
            )
            paid_order = await later_paid_event.get(Order, orders[1].id)
            assert paid_order is not None
            assert await award_eligible_gaming_ranks(
                later_paid_event, customer=locked_customer, order=paid_order,
                program=program, at=now,
            ) == 0

    async with AsyncSessionLocal() as verify:
        rows = (
            await verify.execute(
                select(CustomerGamingRankAward)
                .where(CustomerGamingRankAward.customer_id == customer.id)
                .order_by(CustomerGamingRankAward.rank_key)
            )
        ).scalars().all()
        player_rows = [row for row in rows if row.rank_key == "player"]
        assert len(player_rows) == 1
        assert player_rows[0].program_revision == 1
        assert player_rows[0].bonus_points == 0
        projected = await verify.get(Customer, customer.id)
        assert projected is not None and projected.loyalty_points == 17

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
