from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import text

from app.api.v1.insights.router import _period_stats
from app.core.security import issue_access_token
from app.models import Order, Payment, Shift


@pytest.fixture(autouse=True)
async def _require_source_integrity_migration(session) -> None:
    exists = bool(
        (
            await session.execute(
                text("SELECT to_regclass('public.pos_refund_requests') IS NOT NULL")
            )
        ).scalar_one()
    )
    if not exists:
        pytest.skip("test database is not migrated through the POS refund release")


def _headers(token: str, terminal_id, action_id: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(terminal_id),
        "Idempotency-Key": action_id,
        "X-Client-Action-Id": action_id,
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_settled_full_tip_refund_does_not_create_negative_insights_revenue(
    client,
    session,
    seed_owner,
) -> None:
    """Exercise the real refund API and the real PostgreSQL-backed insight query."""

    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC).replace(microsecond=0)
    sale_at = now - timedelta(minutes=20)
    opening_float = 20_000
    bill_minor = 1_000
    tip_minor = 100
    paid_minor = bill_minor + tip_minor

    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=opening_float,
        expected_minor=opening_float + paid_minor,
        status="open",
    )
    order = Order(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        shift_id=shift.id,
        opened_by=owner.id,
        type="takeaway",
        status="paid",
        subtotal_minor=bill_minor,
        tip_minor=tip_minor,
        total_minor=paid_minor,
        opened_at=now - timedelta(minutes=30),
        closed_at=sale_at,
        invoice_issued_at=sale_at,
        invoice_no=f"D/{branch.invoice_series_code}/26-27/{uuid4().int % 100000:05d}",
        fiscal_year="2026-27",
    )
    payment = Payment(
        id=uuid4(),
        order_id=order.id,
        shift_id=shift.id,
        method="cash",
        amount_minor=paid_minor,
        tendered_minor=paid_minor,
        change_minor=0,
        paid_at=sale_at,
    )
    session.add(shift)
    await session.flush()
    session.add(order)
    await session.flush()
    session.add(payment)
    await session.commit()

    token = issue_access_token(
        user_id=owner.id,
        company_id=company.id,
        branch_id=branch.id,
        roles=["owner"],
        auth_version=owner.auth_version,
        extra={"protected_access": True, "audit_access": True},
    )
    request_key = f"tip-insights-request:{uuid4()}"
    accepted = await client.post(
        "/api/v1/pos/refund-requests",
        json={
            "order_id": str(order.id),
            "shift_id": str(shift.id),
            "reason_code": "CUSTOMER_REQUEST",
            "amount_minor": paid_minor,
            "expected_paid_minor": paid_minor,
            "expected_refundable_minor": paid_minor,
            "mode": "cash",
            "client_action_id": request_key,
            "note": "Tipped sale insight reconciliation proof",
        },
        headers=_headers(token, terminal.id, request_key),
    )
    assert accepted.status_code == 201, accepted.text
    request_id = accepted.json()["id"]

    begin_key = f"tip-insights-begin:{uuid4()}"
    begun = await client.post(
        f"/api/v1/pos/refund-requests/{request_id}/begin-cash-handoff",
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": paid_minor,
            "ready_to_handover": True,
        },
        headers=_headers(token, terminal.id, begin_key),
    )
    assert begun.status_code == 201, begun.text

    settle_key = f"tip-insights-settle:{uuid4()}"
    settled = await client.post(
        f"/api/v1/pos/refund-requests/{request_id}/settle-cash",
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": paid_minor,
            "cash_handed_over": True,
            "settled_at": begun.json()["handoff_started_at"],
        },
        headers=_headers(token, terminal.id, settle_key),
    )
    assert settled.status_code == 201, settled.text

    finalize_key = f"tip-insights-finalize:{uuid4()}"
    finalized = await client.post(
        f"/api/v1/pos/refund-requests/{request_id}/finalize-cash",
        json={
            "shift_id": str(shift.id),
            "expected_amount_minor": paid_minor,
        },
        headers=_headers(token, terminal.id, finalize_key),
    )
    assert finalized.status_code == 201, finalized.text
    assert finalized.json()["status"] == "settled"

    period = await _period_stats(
        session,
        company.id,
        min(sale_at.date(), now.date()),
        max(sale_at.date(), now.date()),
        "UTC",
        branch_id=branch.id,
    )
    assert period.orders_count == 1
    assert period.refunds_minor == paid_minor
    assert period.revenue_minor == 0
    assert period.avg_ticket_minor == 0
