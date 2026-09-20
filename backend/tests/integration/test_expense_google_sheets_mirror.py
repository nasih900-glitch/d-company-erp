"""Transactional Google Sheets mirror evidence for expense create/void facts."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select

from app.core.security import issue_access_token
from app.models import ExpenseCategory
from app.models.google_sheets_delivery import GoogleSheetsDelivery


def _headers(seed, *, idempotency_key: str | None = None) -> dict[str, str]:
    owner = seed["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=seed["company"].id,
        branch_id=seed["branch"].id,
        roles=["owner"],
        auth_version=owner.auth_version,
    )
    headers = {"Authorization": f"Bearer {token}"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    return headers


@pytest.mark.integration
@pytest.mark.asyncio
async def test_expense_create_and_void_enqueue_one_safe_mirror_fact_each(
    client,
    session,
    seed_owner,
) -> None:
    headers = _headers(seed_owner)
    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={
            "webhook_url": "https://script.google.com/macros/s/expense-test/exec",
        },
    )
    assert configured.status_code == 200, configured.text
    queued = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert queued.status_code == 200, queued.text
    connection_test = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.event_id == UUID(queued.json()["event_id"])
            )
        )
    ).scalar_one()
    connection_test.status = "delivered"
    connection_test.delivered_at = datetime.now(UTC)
    await session.commit()

    category = ExpenseCategory(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name="Utilities",
        code=f"UTIL-{uuid4().hex[:8]}",
    )
    session.add(category)
    await session.commit()
    key = f"expense:{uuid4()}"
    payload = {
        "branch_id": str(seed_owner["branch"].id),
        "category_id": str(category.id),
        "amount_minor": 2_500,
        "paid_via": "upi",
        "paid_at": datetime.now(UTC).isoformat(),
        "vendor_name": "Power Company",
        "invoice_no": "POWER-1",
        "note": "private internal note must not leave the ERP",
    }

    created = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, idempotency_key=key),
        json=payload,
    )
    assert created.status_code == 201, created.text
    replay = await client.post(
        "/api/v1/finance/expenses",
        headers=_headers(seed_owner, idempotency_key=key),
        json=payload,
    )
    assert replay.status_code == 201, replay.text
    assert replay.json() == created.json()

    expense_id = created.json()["id"]
    void_payload = {"reason": "Duplicate entry"}
    for _ in range(2):
        voided = await client.post(
            f"/api/v1/finance/expenses/{expense_id}/void",
            headers=headers,
            json=void_payload,
        )
        assert voided.status_code == 200, voided.text

    events = (
        await session.execute(
            select(GoogleSheetsDelivery)
            .where(
                GoogleSheetsDelivery.company_id == seed_owner["company"].id,
                GoogleSheetsDelivery.source_type == "expense",
                GoogleSheetsDelivery.source_id == expense_id,
            )
            .order_by(GoogleSheetsDelivery.occurred_at)
        )
    ).scalars().all()
    assert [row.event_type for row in events] == [
        "finance.expense.recorded",
        "finance.expense.voided",
    ]
    assert events[0].payload["amount_minor"] == -2_500
    assert events[0].payload["expense_total_minor"] == 2_500
    assert events[1].payload["amount_minor"] == 2_500
    assert events[1].payload["expense_total_minor"] == -2_500
    assert events[0].payload["payment_method"] == "upi"
    assert events[0].payload["description"] == "Utilities · Power Company"
    assert "private internal note" not in json.dumps([row.payload for row in events])
    assert "Duplicate entry" not in json.dumps([row.payload for row in events])
