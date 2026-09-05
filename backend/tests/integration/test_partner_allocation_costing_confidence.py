"""Partner allocations fail closed when historical sale COGS is unresolved."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from app.core.security import issue_access_token
from app.models import (
    Batch,
    GamingSession,
    Ingredient,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Partner,
    Payment,
    Recipe,
    RecipeLine,
    Shift,
    Station,
    StockMovement,
)


def _headers(seed_owner) -> dict[str, str]:
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
    }


async def _base(session, seed_owner):
    now = datetime.now(UTC)
    shift = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        opened_by=seed_owner["owner"].id,
        opened_at=now - timedelta(hours=2),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Allocation confidence {uuid4().hex[:8]}",
        is_gaming_centre_catalog=True,
    )
    partner = Partner(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Costing confidence owner",
        share_pct=100,
        joined_at=now,
    )
    session.add_all([shift, category, partner])
    await session.flush()
    return now, shift, category, partner


async def _settle_order(
    session,
    seed_owner,
    *,
    shift: Shift,
    now: datetime,
    order_type: str,
    lines: list[tuple[MenuItem, int, int]],
) -> Order:
    total = sum(quantity * unit_price for _item, quantity, unit_price in lines)
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type=order_type,
        status="open",
        subtotal_minor=total,
        total_minor=total,
        opened_at=now - timedelta(minutes=20),
    )
    session.add(order)
    await session.flush()
    for item, quantity, unit_price in lines:
        session.add(
            OrderLine(
                id=uuid4(),
                order_id=order.id,
                menu_item_id=item.id,
                menu_item_name_snapshot=item.name,
                menu_item_type_snapshot=item.type,
                qty=quantity,
                unit_price_minor=unit_price,
                line_total_minor=quantity * unit_price,
                discount_minor=0,
                tax_rate=0,
                taxable_value_minor=quantity * unit_price,
                cgst_minor=0,
                sgst_minor=0,
                igst_minor=0,
                cess_minor=0,
            )
        )
    await session.flush()
    order.status = "paid"
    order.closed_at = now
    order.invoice_issued_at = now
    order.invoice_no = f"CST-{uuid4().hex[:12]}"
    order.fiscal_year = "2026-27"
    session.add(
        Payment(
            id=uuid4(),
            order_id=order.id,
            shift_id=shift.id,
            recorded_by=seed_owner["owner"].id,
            method="cash",
            amount_minor=total,
            tendered_minor=total,
            change_minor=0,
            paid_at=now,
        )
    )
    shift.expected_minor += total
    await session.flush()
    return order


def _item(seed_owner, category, *, sku: str, name: str, item_type: str, available=True):
    return MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=sku,
        name=name,
        type=item_type,
        base_price_minor=10_000,
        tax_rate=0,
        is_available=available,
    )


async def _link_ended_shisha_session(
    session,
    seed_owner,
    *,
    shift: Shift,
    order: Order,
    now: datetime,
) -> None:
    station = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        code=f"SH-{uuid4().hex[:6]}",
        name="Shisha service station",
        type="hookah",
        rate_per_hour_minor=10_000,
        sac_code="999692",
        tax_rate=0,
        rate_includes_tax=True,
    )
    session.add(station)
    await session.flush()
    session.add(
        GamingSession(
            id=uuid4(),
            company_id=seed_owner["company"].id,
            station_id=station.id,
            order_id=order.id,
            opened_by=seed_owner["owner"].id,
            stopped_by=seed_owner["owner"].id,
            sent_to_pos_by=seed_owner["owner"].id,
            sent_to_pos_at=now,
            shift_id=shift.id,
            start_at=now - timedelta(hours=1),
            end_at=now,
            rate_per_hour_minor=10_000,
            billing_mode="hourly",
            billable_minutes=60,
            amount_minor=10_000,
            status="ended",
            tax_rate=0,
            sac_code="999692",
            rate_includes_tax=True,
            extra_controllers=0,
        )
    )


async def _allocation_bodies(client, seed_owner):
    headers = _headers(seed_owner)
    responses = [
        await client.get("/api/v1/finance/pnl/partners", headers=headers),
        await client.get("/api/v1/finance/distributable", headers=headers),
    ]
    for response in responses:
        assert response.status_code == 200, response.text
    return [response.json() for response in responses]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_deleted_drink_and_disabled_shisha_addon_block_both_partner_allocations(
    client,
    session,
    seed_owner,
) -> None:
    """Historical rows remain visible even after products leave the active catalogue."""

    now, shift, category, _partner = await _base(session, seed_owner)
    drink = _item(
        seed_owner,
        category,
        sku=f"DRINK-{uuid4().hex[:8]}",
        name="Historical canned drink",
        item_type="drink",
    )
    hidden_service = _item(
        seed_owner,
        category,
        sku="SESSION-HOOKAH",
        name="Shisha Session",
        item_type="hookah",
        available=False,
    )
    shisha_addon = _item(
        seed_owner,
        category,
        sku=f"SHISHA-PRODUCT-{uuid4().hex[:8]}",
        name="Physical shisha add-on",
        item_type="hookah",
    )
    session.add_all([drink, hidden_service, shisha_addon])
    await session.flush()

    await _settle_order(
        session,
        seed_owner,
        shift=shift,
        now=now,
        order_type="takeaway",
        lines=[(drink, 1, 8_000)],
    )
    shisha_order = await _settle_order(
        session,
        seed_owner,
        shift=shift,
        now=now,
        order_type="session",
        lines=[(hidden_service, 1, 10_000), (shisha_addon, 1, 5_000)],
    )
    await _link_ended_shisha_session(
        session,
        seed_owner,
        shift=shift,
        order=shisha_order,
        now=now,
    )
    await session.commit()

    # This is the real historical failure mode: a sold item is no longer in
    # today's sellable catalogue. Allocation confidence must still inspect it.
    drink.is_available = False
    drink.deleted_at = now + timedelta(seconds=1)
    shisha_addon.is_available = False
    await session.commit()

    period, distributable = await _allocation_bodies(client, seed_owner)
    assert period["net_profit_minor"] == 23_000  # provisional P&L remains visible
    assert period["allocation_status"] == "costing_incomplete"
    assert period["costing_confidence"] == {
        "status": "costing_incomplete",
        "inventory_orders_checked": 2,
        "inventory_lines_checked": 2,
        "unresolved_order_count": 2,
        "reason": period["allocation_unavailable_reason"],
    }
    assert period["partners"][0]["profit_share_minor"] == 0
    assert period["partners"][0]["authoritative_profit_share_minor"] is None

    assert distributable["lifetime_net_profit_minor"] == 23_000
    assert distributable["allocation_status"] == "costing_incomplete"
    assert distributable["safe_to_distribute_minor"] == 0
    assert distributable["authoritative_safe_to_distribute_minor"] is None
    assert distributable["profit_based_capacity_minor"] == 0
    assert distributable["cash_based_capacity_minor"] == 0
    assert distributable["partners"][0]["distributable_share_minor"] == 0
    assert (
        distributable["partners"][0]["authoritative_distributable_share_minor"]
        is None
    )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_service_only_shisha_session_does_not_invent_inventory_cogs(
    client,
    session,
    seed_owner,
) -> None:
    now, shift, category, _partner = await _base(session, seed_owner)
    hidden_service = _item(
        seed_owner,
        category,
        sku="SESSION-HOOKAH",
        name="Shisha Session",
        item_type="hookah",
        available=False,
    )
    session.add(hidden_service)
    await session.flush()
    order = await _settle_order(
        session,
        seed_owner,
        shift=shift,
        now=now,
        order_type="session",
        lines=[(hidden_service, 1, 10_000)],
    )
    await _link_ended_shisha_session(
        session,
        seed_owner,
        shift=shift,
        order=order,
        now=now,
    )
    await session.commit()

    period, distributable = await _allocation_bodies(client, seed_owner)
    assert period["allocation_status"] == "authoritative"
    assert period["costing_confidence"]["inventory_orders_checked"] == 0
    assert period["partners"][0]["profit_share_minor"] == 10_000
    assert period["partners"][0]["authoritative_profit_share_minor"] == 10_000
    assert distributable["allocation_status"] == "authoritative"
    assert distributable["authoritative_safe_to_distribute_minor"] is not None
    assert distributable["partners"][0]["authoritative_distributable_share_minor"] is not None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_exact_positive_fifo_evidence_keeps_physical_sale_allocation_authoritative(
    client,
    session,
    seed_owner,
) -> None:
    now, shift, category, _partner = await _base(session, seed_owner)
    drink = _item(
        seed_owner,
        category,
        sku=f"COSTED-{uuid4().hex[:8]}",
        name="Costed canned drink",
        item_type="drink",
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        sku=f"ING-{uuid4().hex[:8]}",
        name="Canned drink unit",
        base_unit="unit",
        reorder_threshold=0,
        reorder_qty=0,
        avg_cost_minor=100,
        current_qty=8,
    )
    session.add_all([drink, ingredient])
    await session.flush()
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=drink.id,
        name="One can",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=100,
    )
    session.add(recipe)
    await session.flush()
    session.add(
        RecipeLine(
            id=uuid4(),
            recipe_id=recipe.id,
            ingredient_id=ingredient.id,
            qty=1,
            wastage_pct=0,
        )
    )
    batch = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=seed_owner["branch"].id,
        received_at=now - timedelta(days=1),
        qty_initial=10,
        qty_on_hand=8,
        cost_per_unit_minor=100,
    )
    session.add(batch)
    await session.flush()
    order = await _settle_order(
        session,
        seed_owner,
        shift=shift,
        now=now,
        order_type="takeaway",
        lines=[(drink, 2, 5_000)],
    )
    session.add(
        StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=seed_owner["branch"].id,
            type="sale",
            ref_type="order",
            ref_id=order.id,
            qty_delta=-2,
            cost_per_unit_minor=100,
            created_by=seed_owner["owner"].id,
            note="Exact FIFO costing confidence proof",
        )
    )
    await session.commit()
    drink.is_available = False
    drink.deleted_at = now + timedelta(seconds=1)
    await session.commit()

    period, distributable = await _allocation_bodies(client, seed_owner)
    assert period["net_profit_minor"] == 9_800
    assert period["allocation_status"] == "authoritative"
    assert period["costing_confidence"] == {
        "status": "authoritative",
        "inventory_orders_checked": 1,
        "inventory_lines_checked": 1,
        "unresolved_order_count": 0,
        "reason": None,
    }
    assert period["partners"][0]["authoritative_profit_share_minor"] == 9_800
    assert distributable["allocation_status"] == "authoritative"
    assert distributable["authoritative_safe_to_distribute_minor"] is not None
    assert distributable["partners"][0]["authoritative_distributable_share_minor"] is not None
