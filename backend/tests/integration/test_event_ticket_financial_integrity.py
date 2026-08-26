"""Postgres proof that unlinked event tickets are operational, not revenue."""

from __future__ import annotations

import csv
import io
from datetime import UTC, date, datetime
from uuid import uuid4

import pytest
from sqlalchemy import delete, func, select

from app.api.v1.reports.router import gstr1_csv, gstr3b_csv, tax_compliance
from app.core.db import AsyncSessionLocal
from app.core.security import issue_access_token
from app.core.tenant import TenantContext
from app.models import Event, EventTicket, IdempotencyKey
from app.services.accounting import build_operational_ledger
from app.services.reports import ReportsAggregator


def _event(seed_owner, *, starts_at: datetime) -> Event:
    return Event(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        name="Financial integrity screening",
        event_type="movie",
        screen="Main Screen",
        starts_at=starts_at,
        capacity=20,
        base_ticket_price_minor=12_345,
        sac_code="999692",
        tax_rate=0.18,
        status="scheduled",
    )


def _tenant(seed_owner) -> TenantContext:
    return TenantContext(
        user_id=seed_owner["owner"].id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=None,
        roles=("owner",),
    )


async def _stream_text(response) -> str:
    chunks: list[str] = []
    async for chunk in response.body_iterator:
        chunks.append(chunk.decode() if isinstance(chunk, bytes) else chunk)
    return "".join(chunks)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_direct_ticket_sale_http_fails_before_idempotency_or_ticket_write(
    client,
    session,
    seed_owner,
) -> None:
    event = _event(
        seed_owner,
        starts_at=datetime(2026, 8, 26, 18, tzinfo=UTC),
    )
    session.add(event)
    await session.commit()
    key = f"disabled-direct-ticket-{uuid4()}"
    token = issue_access_token(
        user_id=seed_owner["owner"].id,
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        roles=["owner"],
        auth_version=seed_owner["owner"].auth_version,
    )

    try:
        response = await client.post(
            f"/api/v1/events/{event.id}/tickets",
            headers={
                "Authorization": f"Bearer {token}",
                "Idempotency-Key": key,
            },
            json={"customer_name": "Rahul", "qty": 1},
        )

        assert response.status_code == 422, response.text
        error = response.json()["error"]
        assert error["code"] == "business_rule"
        assert "temporarily disabled" in error["message"]
        assert "POS order, payment, shift, or invoice" in error["message"]
        async with AsyncSessionLocal() as verify:
            ticket_count = int(
                (
                    await verify.execute(
                        select(func.count(EventTicket.id)).where(
                            EventTicket.event_id == event.id
                        )
                    )
                ).scalar_one()
                or 0
            )
            assert ticket_count == 0
            assert await verify.get(IdempotencyKey, key) is None
    finally:
        async with AsyncSessionLocal() as cleanup:
            await cleanup.execute(
                delete(EventTicket).where(EventTicket.event_id == event.id)
            )
            await cleanup.execute(delete(IdempotencyKey).where(IdempotencyKey.key == key))
            await cleanup.execute(delete(Event).where(Event.id == event.id))
            await cleanup.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_historical_unlinked_ticket_is_counted_but_excluded_from_financials(
    session,
    seed_owner,
) -> None:
    occurred_at = datetime(2026, 8, 25, 12, tzinfo=UTC)
    event = _event(
        seed_owner,
        starts_at=datetime(2026, 8, 25, 18, tzinfo=UTC),
    )
    ticket = EventTicket(
        id=uuid4(),
        event_id=event.id,
        ticket_no=f"LEGACY-{uuid4().hex[:12].upper()}",
        order_id=None,
        customer_name="Historical customer",
        price_paid_minor=12_345,
        status="sold",
        sold_by=seed_owner["owner"].id,
        created_at=occurred_at,
        updated_at=occurred_at,
    )
    session.add_all([event, ticket])
    await session.flush()

    report = await ReportsAggregator(session).aggregate_daily(
        company_id=seed_owner["company"].id,
        d=date(2026, 8, 25),
    )
    assert report.tickets_count == 1
    assert report.revenue.event_tickets_minor == 0
    assert report.gross_revenue_minor == 0
    assert report.tax_collected.total_minor == 0
    assert report.payments_received.total_minor == 0

    ledger = await build_operational_ledger(
        session,
        company_id=seed_owner["company"].id,
        start_at=datetime(2026, 8, 1, tzinfo=UTC),
        end_exclusive=datetime(2026, 9, 1, tzinfo=UTC),
    )
    assert [line for line in ledger if line.ref_id == ticket.id] == []
    assert [line for line in ledger if line.ref_type == "event_ticket"] == []

    compliance = await tax_compliance(
        session,
        from_date=date(2026, 8, 25),
        to_date=date(2026, 8, 25),
        tenant=_tenant(seed_owner),
    )
    issue = next(
        item
        for item in compliance.issues
        if item.title == "Historical event tickets have no verified POS sale"
    )
    assert issue.severity == "critical"
    assert issue.count == 1
    assert compliance.event_ticket_revenue_minor == 0
    assert compliance.taxable_minor == 0
    assert compliance.gst_collected_minor == 0

    gstr1 = await _stream_text(
        await gstr1_csv("2026-08", session, tenant=_tenant(seed_owner))
    )
    gstr1_rows = list(csv.reader(io.StringIO(gstr1)))
    assert not any(row and row[0] == "OE" for row in gstr1_rows)

    gstr3b = await _stream_text(
        await gstr3b_csv("2026-08", session, tenant=_tenant(seed_owner))
    )
    gstr3b_rows = list(csv.reader(io.StringIO(gstr3b)))
    outward = next(
        row
        for row in gstr3b_rows
        if row and row[0] == "Outward taxable supplies (other than zero-rated)"
    )
    assert outward[1:] == ["0.00", "0.00", "0.00", "0.00", "0.00"]
