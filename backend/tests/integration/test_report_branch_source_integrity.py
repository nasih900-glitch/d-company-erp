"""End-to-end branch and source-of-truth proof for Reports/Analytics.

This test deliberately seeds two branches in one tenant.  The authenticated
terminal belongs to branch A; branch B carries much larger values so any
missing predicate is immediately visible rather than hidden by a zero row.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest

from app.models import (
    Batch,
    Branch,
    GamingSession,
    Ingredient,
    ManualCollection,
    MenuCategory,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    Recipe,
    RecipeLine,
    Shift,
    Station,
    StockMovement,
    Terminal,
)


def _order(
    *,
    seed_owner: dict,
    branch: Branch,
    terminal: Terminal,
    shift: Shift,
    amount_minor: int,
    opened_at: datetime,
) -> Order:
    return Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=seed_owner["owner"].id,
        type="takeaway",
        status="open",
        subtotal_minor=amount_minor,
        total_minor=amount_minor,
        opened_at=opened_at,
    )


def _line(order: Order, item: MenuItem, amount_minor: int) -> OrderLine:
    return OrderLine(
        id=uuid4(),
        order_id=order.id,
        menu_item_id=item.id,
        qty=1,
        unit_price_minor=amount_minor,
        line_total_minor=amount_minor,
        discount_minor=0,
        tax_rate=0,
        taxable_value_minor=amount_minor,
        cgst_minor=0,
        sgst_minor=0,
        igst_minor=0,
        cess_minor=0,
        kitchen_status="queued",
    )


@pytest.mark.asyncio
async def test_authenticated_reports_align_order_stock_and_gaming_to_token_branch(
    client,
    session,
    seed_owner,
) -> None:
    """Branch A can never observe branch B's sale, COGS, stock, or session."""

    login = await client.post(
        "/api/v1/auth/login",
        headers={"X-Terminal-Id": str(seed_owner["terminal"].id)},
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200, login.text
    headers = {
        "Authorization": f"Bearer {login.json()['access_token']}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }

    # Derive the requested day in the company's timezone. Using the UTC date
    # becomes flaky after 18:30 UTC because D Company's Asia/Kolkata business
    # date has already advanced, correctly placing a late-UTC payment outside
    # the requested local report day. Keep event timestamps near real ``now``
    # because payment integrity also rejects future-dated settlements.
    local_zone = ZoneInfo("Asia/Kolkata")
    now = datetime.now(UTC).replace(microsecond=0)
    today = now.astimezone(local_zone).date()
    branch_a = seed_owner["branch"]
    terminal_a = seed_owner["terminal"]
    branch_b = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Foreign report branch {uuid4().hex[:8]}",
        invoice_series_code="RB",
    )
    terminal_b = Terminal(
        id=uuid4(),
        branch_id=branch_b.id,
        name="Report B POS",
        device_id=f"report-b-{uuid4()}",
    )
    shift_a = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=branch_a.id,
        terminal_id=terminal_a.id,
        opened_by=seed_owner["owner"].id,
        opened_at=now - timedelta(hours=2),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    shift_b = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=branch_b.id,
        terminal_id=terminal_b.id,
        opened_by=seed_owner["owner"].id,
        opened_at=now - timedelta(hours=2),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    category = MenuCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Report proof {uuid4().hex[:8]}",
        sort_order=0,
    )
    item = MenuItem(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        category_id=category.id,
        sku=f"REPORT-{uuid4().hex[:10]}",
        name="Historical report item",
        type="food",
        base_price_minor=10_000,
        tax_rate=0,
        price_includes_tax=True,
        is_available=True,
    )
    ingredient = Ingredient(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        sku=f"REPORT-ING-{uuid4().hex[:10]}",
        name="Report branch ingredient",
        base_unit="unit",
        reorder_threshold=0,
        reorder_qty=0,
        avg_cost_minor=350,
        current_qty=15,
    )
    station_a = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=branch_a.id,
        code=f"REPORT-A-{uuid4().hex[:5]}",
        name="Branch A active station",
        type="ps5",
        rate_per_hour_minor=15_000,
        is_active=True,
    )
    station_b = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=branch_b.id,
        code=f"REPORT-B-{uuid4().hex[:5]}",
        name="Branch B active station",
        type="vr",
        rate_per_hour_minor=50_000,
        is_active=True,
    )
    session.add_all(
        [
            branch_b,
            category,
            ingredient,
        ]
    )
    await session.flush()
    # These models expose FK identifiers rather than ORM relationships, so
    # make the write order explicit instead of relying on unit-of-work sorting.
    session.add(terminal_b)
    await session.flush()
    session.add_all([shift_a, shift_b, item, station_a, station_b])
    await session.flush()

    order_a = _order(
        seed_owner=seed_owner,
        branch=branch_a,
        terminal=terminal_a,
        shift=shift_a,
        amount_minor=10_000,
        opened_at=now - timedelta(minutes=30),
    )
    order_b = _order(
        seed_owner=seed_owner,
        branch=branch_b,
        terminal=terminal_b,
        shift=shift_b,
        amount_minor=70_000,
        opened_at=now - timedelta(minutes=25),
    )
    batch_a = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=branch_a.id,
        received_at=now - timedelta(days=1),
        qty_initial=10,
        qty_on_hand=8,
        cost_per_unit_minor=200,
        lot_code="REPORT-A",
    )
    batch_b = Batch(
        id=uuid4(),
        ingredient_id=ingredient.id,
        branch_id=branch_b.id,
        received_at=now - timedelta(days=1),
        qty_initial=10,
        qty_on_hand=7,
        cost_per_unit_minor=500,
        lot_code="REPORT-B",
    )
    session.add_all([order_a, order_b, batch_a, batch_b])
    await session.flush()
    recipe = Recipe(
        id=uuid4(),
        menu_item_id=item.id,
        name="Report proof recipe",
        yield_qty=1,
        version=1,
        is_active=True,
        cost_minor=0,
    )
    session.add(recipe)
    await session.flush()
    session.add_all(
        [
            RecipeLine(
                id=uuid4(),
                recipe_id=recipe.id,
                ingredient_id=ingredient.id,
                qty=1,
                wastage_pct=0,
            ),
            _line(order_a, item, 10_000),
            _line(order_b, item, 70_000),
            StockMovement(
                id=uuid4(),
                batch_id=batch_a.id,
                branch_id=branch_a.id,
                type="sale",
                ref_type="order",
                ref_id=order_a.id,
                qty_delta=-2,
                cost_per_unit_minor=200,
                created_by=seed_owner["owner"].id,
            ),
            StockMovement(
                id=uuid4(),
                batch_id=batch_b.id,
                branch_id=branch_b.id,
                type="sale",
                ref_type="order",
                ref_id=order_b.id,
                qty_delta=-3,
                cost_per_unit_minor=500,
                created_by=seed_owner["owner"].id,
            ),
            GamingSession(
                id=uuid4(),
                company_id=seed_owner["company"].id,
                station_id=station_a.id,
                opened_by=seed_owner["owner"].id,
                shift_id=shift_a.id,
                start_at=now - timedelta(minutes=20),
                paused_minutes=0,
                rate_per_hour_minor=station_a.rate_per_hour_minor,
                billing_mode="hourly",
                status="active",
                extra_controllers=0,
            ),
            GamingSession(
                id=uuid4(),
                company_id=seed_owner["company"].id,
                station_id=station_b.id,
                opened_by=seed_owner["owner"].id,
                shift_id=shift_b.id,
                start_at=now - timedelta(minutes=15),
                paused_minutes=0,
                rate_per_hour_minor=station_b.rate_per_hour_minor,
                billing_mode="hourly",
                status="active",
                extra_controllers=0,
            ),
            ManualCollection(
                id=uuid4(),
                company_id=seed_owner["company"].id,
                branch_id=branch_a.id,
                business_date=today,
                method="cash",
                amount_minor=21_000,
                source_kind="manual_daily",
                source_ref=f"branch-a-{uuid4()}",
                idempotency_key=f"branch-a-{uuid4()}",
                created_by=seed_owner["owner"].id,
            ),
            ManualCollection(
                id=uuid4(),
                company_id=seed_owner["company"].id,
                branch_id=branch_b.id,
                business_date=today,
                method="upi",
                amount_minor=99_900,
                source_kind="manual_daily",
                source_ref=f"branch-b-{uuid4()}",
                idempotency_key=f"branch-b-{uuid4()}",
                created_by=seed_owner["owner"].id,
            ),
        ]
    )
    await session.flush()

    sold_at = now - timedelta(minutes=5)
    order_a.status = "paid"
    order_a.closed_at = sold_at
    order_a.invoice_issued_at = sold_at
    order_a.invoice_no = "D/MN/26-27/00001"
    order_a.fiscal_year = "2026-27"
    order_b.status = "paid"
    order_b.closed_at = sold_at
    order_b.invoice_issued_at = sold_at
    order_b.invoice_no = "D/RB/26-27/00001"
    order_b.fiscal_year = "2026-27"
    session.add_all(
        [
            Payment(
                id=uuid4(),
                order_id=order_a.id,
                shift_id=shift_a.id,
                method="cash",
                amount_minor=10_000,
                tendered_minor=10_000,
                change_minor=0,
                paid_at=sold_at,
            ),
            Payment(
                id=uuid4(),
                order_id=order_b.id,
                shift_id=shift_b.id,
                method="upi",
                amount_minor=70_000,
                paid_at=sold_at,
                ref_external="branch-b-provider-proof",
            ),
        ]
    )
    await session.commit()

    # Later catalogue edits must not rewrite historical category/name facts.
    item.name = "Renamed after both sales"
    item.type = "gaming"
    await session.commit()

    report_response = await client.get(
        "/api/v1/reports/daily",
        params={"on_date": today.isoformat()},
        headers=headers,
    )
    assert report_response.status_code == 200, report_response.text
    report = report_response.json()
    assert report["branch_id"] == str(branch_a.id)
    assert report["orders_count"] == 1
    assert report["revenue"]["food_minor"] == 10_000
    assert report["revenue"]["gaming_minor"] == 0
    assert report["manual_collections_minor"] == 21_000
    assert report["payments_received"]["cash_minor"] == 31_000
    assert report["payments_received"]["upi_minor"] == 0
    assert report["cogs_minor"] == 400
    assert report["avg_ticket_minor"] == 10_000

    analytics_response = await client.get(
        "/api/v1/analytics/dashboard",
        params={"on_date": today.isoformat()},
        headers=headers,
    )
    assert analytics_response.status_code == 200, analytics_response.text
    dashboard = analytics_response.json()
    assert dashboard["branch_id"] == str(branch_a.id)
    assert dashboard["revenue_food_minor"] == 10_000
    assert dashboard["cogs_minor"] == 400
    assert dashboard["inventory_value_minor"] == 1_600
    assert dashboard["open_sessions"] == 1

    top_response = await client.get(
        "/api/v1/insights/top-items",
        params={"from_date": today.isoformat(), "to_date": today.isoformat()},
        headers=headers,
    )
    assert top_response.status_code == 200, top_response.text
    assert top_response.json() == [
        {
            "branch_id": str(branch_a.id),
            "menu_item_id": str(item.id),
            "name": "Historical report item",
            "type": "food",
            "qty_sold": 1.0,
            "revenue_minor": 10_000,
            "revenue_basis": "gross_line",
        }
    ]

    margin_response = await client.get(
        "/api/v1/insights/menu/recipe-margin",
        headers=headers,
    )
    assert margin_response.status_code == 200, margin_response.text
    margin = next(
        row for row in margin_response.json() if row["menu_item_id"] == str(item.id)
    )
    assert margin["branch_id"] == str(branch_a.id)
    assert margin["cost_minor"] == 200
    assert margin["costing_complete"] is True
