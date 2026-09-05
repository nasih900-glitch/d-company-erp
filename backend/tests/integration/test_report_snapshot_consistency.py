"""Reports must not combine totals from before and after a concurrent sale."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.core.timezone import local_today
from app.models import MenuCategory, MenuItem, Order, OrderLine, Payment, Shift


@pytest.mark.integration
@pytest.mark.asyncio
async def test_http_report_retains_one_snapshot_during_concurrent_sale(
    client, session, seed_owner, monkeypatch,
) -> None:
    now = datetime.now(UTC)
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    shift = Shift(
        id=uuid4(), company_id=company.id, branch_id=branch.id,
        terminal_id=terminal.id, opened_by=owner.id,
        opened_at=now - timedelta(hours=1), opening_float_minor=0,
        expected_minor=0, status="open",
    )
    category = MenuCategory(id=uuid4(), company_id=company.id, name="Drinks")
    item = MenuItem(
        id=uuid4(), company_id=company.id, category_id=category.id,
        sku=f"SNAPSHOT-{uuid4()}", name="Water", type="drink",
        base_price_minor=1_000, tax_rate=0, is_available=True,
    )
    for row in (shift, category, item):
        session.add(row)
        await session.flush()
    await session.commit()

    async def commit_concurrent_sale():
        async with AsyncSessionLocal() as writer:
            order = Order(
                id=uuid4(), company_id=company.id, branch_id=branch.id,
                terminal_id=terminal.id, shift_id=shift.id, opened_by=owner.id,
                type="takeaway", status="open", subtotal_minor=1_000,
                total_minor=1_000, opened_at=now - timedelta(minutes=5),
            )
            writer.add(order)
            await writer.flush()
            writer.add(OrderLine(
                id=uuid4(), order_id=order.id, menu_item_id=item.id,
                menu_item_name_snapshot="Water", menu_item_type_snapshot="drink",
                qty=1, unit_price_minor=1_000, line_total_minor=1_000,
                discount_minor=0, tax_rate=0, taxable_value_minor=1_000,
                cgst_minor=0, sgst_minor=0, igst_minor=0, cess_minor=0,
            ))
            await writer.flush()
            order.status = "paid"
            order.closed_at = now
            order.invoice_issued_at = now
            order.invoice_no = f"D/QA/26-27/{uuid4().int % 100000:05d}"
            order.fiscal_year = "2026-27"
            writer.add(Payment(
                id=uuid4(), order_id=order.id, shift_id=shift.id, method="cash",
                amount_minor=1_000, tendered_minor=1_000, change_minor=0,
                paid_at=now,
            ))
            await writer.commit()

    original_execute = AsyncSession.execute
    committed_during_report = False

    async def execute_and_settle_after_order_count(active_session, statement, *args, **kwargs):
        nonlocal committed_during_report
        result = await original_execute(active_session, statement, *args, **kwargs)
        if not committed_during_report and "count(orders.id) AS n" in str(statement):
            committed_during_report = True
            await commit_concurrent_sale()
        return result

    monkeypatch.setattr(AsyncSession, "execute", execute_and_settle_after_order_count)
    token = issue_access_token(
        user_id=owner.id, company_id=company.id, branch_id=branch.id,
        roles=["owner"], auth_version=owner.auth_version,
    )
    headers = {"Authorization": f"Bearer {token}", "X-Terminal-Id": str(terminal.id)}
    params = {"on_date": local_today(company.timezone).isoformat()}
    response = await client.get("/api/v1/reports/daily", params=params, headers=headers)
    assert response.status_code == 200, response.text
    before = response.json()
    assert committed_during_report
    assert before["orders_count"] == 0
    assert before["gross_revenue_minor"] == 0
    assert before["payments_received"]["cash_minor"] == 0

    refreshed = await client.get("/api/v1/reports/daily", params=params, headers=headers)
    assert refreshed.status_code == 200, refreshed.text
    after = refreshed.json()
    assert after["orders_count"] == 1
    assert after["gross_revenue_minor"] == 1_000
    assert after["payments_received"]["cash_minor"] == 1_000
    assert after["avg_ticket_minor"] == 1_000
