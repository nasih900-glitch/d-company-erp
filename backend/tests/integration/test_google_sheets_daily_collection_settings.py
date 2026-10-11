"""Configuration boundaries for the daily collection export mode."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError

from app.core.security import issue_access_token
from app.models import Branch, Company
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.integrations.google_sheets_mirror import (
    DeliveryOutcome,
    DispatcherConfig,
    apply_delivery_outcome,
    enqueue_google_sheets_event,
    enqueue_google_sheets_event_if_enabled,
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
    return {"Authorization": f"Bearer {token}"}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_database_rejects_daily_scope_without_timezone(session) -> None:
    company = Company(id=uuid4(), name=f"Null timezone summary {uuid4()}")
    branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name="Null timezone summary branch",
        invoice_series_code="NT",
    )
    session.add_all([company, branch])
    await session.commit()

    statement = (
        update(Company)
        .where(Company.id == company.id)
        .values(
            google_sheets_export_mode="daily_collections",
            google_sheets_summary_branch_id=branch.id,
            google_sheets_summary_start_date=datetime.now(UTC).date(),
            google_sheets_summary_timezone=None,
        )
    )
    with pytest.raises(IntegrityError):
        await session.execute(statement)
    await session.rollback()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_summary_configuration_rejects_foreign_branch_and_future_start(
    client, session, seed_owner
) -> None:
    foreign_company = Company(id=uuid4(), name=f"Foreign summary {uuid4()}")
    foreign_branch = Branch(
        id=uuid4(),
        company_id=foreign_company.id,
        name="Foreign summary branch",
        invoice_series_code="FS",
    )
    session.add_all([foreign_company, foreign_branch])
    await session.commit()
    today = datetime.now(UTC).astimezone(ZoneInfo("Asia/Kolkata")).date()
    base = {
        "webhook_url": "https://script.google.com/macros/s/summary-scope/exec",
        "export_mode": "daily_collections",
    }

    foreign = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=_headers(seed_owner),
        json={
            **base,
            "summary_branch_id": str(foreign_branch.id),
            "summary_start_date": today.isoformat(),
        },
    )
    assert foreign.status_code == 422
    assert "does not belong" in foreign.json()["error"]["message"]

    future = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=_headers(seed_owner),
        json={
            **base,
            "summary_branch_id": str(seed_owner["branch"].id),
            "summary_start_date": (today + timedelta(days=1)).isoformat(),
        },
    )
    assert future.status_code == 422
    assert "future" in future.json()["error"]["message"]


@pytest.mark.integration
@pytest.mark.asyncio
async def test_mode_change_drains_unverified_business_and_rotates_generation(
    client, session, seed_owner
) -> None:
    headers = _headers(seed_owner)
    company_id = seed_owner["company"].id
    branch_id = seed_owner["branch"].id
    url = "https://script.google.com/macros/s/summary-drain/exec"
    configured = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url},
    )
    assert configured.status_code == 200, configured.text
    session.expire_all()
    company = await session.get(Company, company_id)
    assert company is not None
    old_configuration_id = company.google_sheets_configuration_id
    assert old_configuration_id is not None
    business = await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company.id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id=str(uuid4()),
        source_revision="recorded-v1",
        occurred_at=datetime.now(UTC),
        payload={"amount_minor": -100},
    )
    assert business is not None
    await session.commit()

    # This test changes timezones; use a date valid in both Kolkata and Los Angeles.
    target_day = datetime.now(UTC).date() - timedelta(days=1)
    payload = {
        "webhook_url": url,
        "export_mode": "daily_collections",
        "summary_branch_id": str(branch_id),
        "summary_start_date": target_day.isoformat(),
    }
    blocked = await client.post(
        "/api/v1/settings/google-sheets/configure", headers=headers, json=payload
    )
    assert blocked.status_code == 409
    assert blocked.json()["error"]["details"]["undelivered_business_entries"]["pending"] == 1

    row = await session.get(GoogleSheetsDelivery, business.id)
    assert row is not None
    row.status = "delivered"
    row.delivered_at = datetime.now(UTC)
    await session.commit()
    changed = await client.post(
        "/api/v1/settings/google-sheets/configure", headers=headers, json=payload
    )
    assert changed.status_code == 200, changed.text
    body = changed.json()
    assert body["export_mode"] == "daily_collections"
    assert body["summary_branch_id"] == str(branch_id)
    assert body["summary_timezone"] == "Asia/Kolkata"
    assert body["summary_start_date"] == target_day.isoformat()

    session.expire_all()
    company = await session.get(Company, company_id)
    assert company is not None
    assert company.google_sheets_configuration_id != old_configuration_id

    queued = await client.post("/api/v1/settings/google-sheets/test", headers=headers)
    assert queued.status_code == 200, queued.text
    test_row = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.event_id == UUID(queued.json()["event_id"])
            )
        )
    ).scalar_one()
    assert test_row.payload["required_capability"] == "daily_collections.v1"
    assert test_row.payload["summary_scope"] == {
        "branch_id": str(branch_id),
        "business_timezone": "Asia/Kolkata",
        "start_date": target_day.isoformat(),
    }

    prior_summary_configuration = company.google_sheets_configuration_id
    branch = await session.get(Branch, branch_id)
    assert branch is not None
    branch.timezone = "America/Los_Angeles"
    await session.commit()
    timezone_rotation = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json=payload,
    )
    assert timezone_rotation.status_code == 200, timezone_rotation.text
    assert timezone_rotation.json()["summary_timezone"] == "America/Los_Angeles"
    session.expire_all()
    company = await session.get(Company, company_id)
    assert company is not None
    assert company.google_sheets_configuration_id != prior_summary_configuration

    restored = await client.post(
        "/api/v1/settings/google-sheets/configure",
        headers=headers,
        json={"webhook_url": url, "export_mode": "legacy_transactions"},
    )
    assert restored.status_code == 200, restored.text
    disconnected = await client.delete("/api/v1/settings/google-sheets", headers=headers)
    assert disconnected.status_code == 200, disconnected.text


@pytest.mark.integration
@pytest.mark.asyncio
async def test_health_response_retry_state_is_durable_and_attempt_bounded(
    session, seed_owner
) -> None:
    now = datetime(2026, 10, 11, 12, tzinfo=UTC)
    config = DispatcherConfig(max_attempts=3, retry_base_seconds=30)
    configuration_id = uuid4()
    company_id = seed_owner["company"].id
    row = await enqueue_google_sheets_event(
        session,
        company_id=company_id,
        configuration_id=configuration_id,
        event_type="daily_collections.snapshot",
        source_type="daily_collection_summary",
        source_id="2026-10-10",
        source_revision="00000000000000000001",
        occurred_at=now,
        payload={"cash_minor": 150, "upi_minor": 500},
    )
    row_id = row.id
    original_payload = dict(row.payload)
    row.status = "leased"
    row.lease_owner = "worker-1"
    row.lease_expires_at = now + timedelta(minutes=5)
    row.attempt_count = 1
    await session.commit()

    health_outcome = DeliveryOutcome.failure(
        retryable=True,
        error_code="unexpected_health_response",
        error_detail=(
            "The mirror endpoint returned its health document instead of an "
            "event acknowledgement"
        ),
    )
    assert await apply_delivery_outcome(
        session,
        delivery_id=row_id,
        worker_id="worker-1",
        outcome=health_outcome,
        now=now,
        config=config,
    )
    await session.commit()
    session.expire_all()
    pending = await session.get(GoogleSheetsDelivery, row_id)
    assert pending is not None
    assert pending.status == "pending"
    assert pending.available_at == now + timedelta(seconds=30)
    assert pending.last_error_code == "unexpected_health_response"
    assert pending.payload == original_payload

    pending.status = "leased"
    pending.lease_owner = "worker-1"
    pending.lease_expires_at = now + timedelta(minutes=5)
    pending.attempt_count = 2
    await session.commit()
    delivered_at = now + timedelta(minutes=1)
    assert await apply_delivery_outcome(
        session,
        delivery_id=row_id,
        worker_id="worker-1",
        outcome=DeliveryOutcome.success(),
        now=delivered_at,
        config=config,
    )
    await session.commit()
    assert not await apply_delivery_outcome(
        session,
        delivery_id=row_id,
        worker_id="worker-1",
        outcome=DeliveryOutcome.success(),
        now=delivered_at + timedelta(minutes=1),
        config=config,
    )
    session.expire_all()
    delivered = await session.get(GoogleSheetsDelivery, row_id)
    assert delivered is not None
    assert delivered.status == "delivered"
    assert delivered.delivered_at == delivered_at
    assert delivered.payload == original_payload

    exhausted = await enqueue_google_sheets_event(
        session,
        company_id=company_id,
        configuration_id=configuration_id,
        event_type="daily_collections.snapshot",
        source_type="daily_collection_summary",
        source_id="2026-10-11",
        source_revision="00000000000000000001",
        occurred_at=now,
        payload={"cash_minor": 250, "upi_minor": 750},
    )
    exhausted_id = exhausted.id
    exhausted_payload = dict(exhausted.payload)
    exhausted.status = "leased"
    exhausted.lease_owner = "worker-1"
    exhausted.lease_expires_at = now + timedelta(minutes=5)
    exhausted.attempt_count = config.max_attempts
    await session.commit()

    quarantine_at = now + timedelta(minutes=2)
    assert await apply_delivery_outcome(
        session,
        delivery_id=exhausted_id,
        worker_id="worker-1",
        outcome=health_outcome,
        now=quarantine_at,
        config=config,
    )
    await session.commit()
    session.expire_all()
    quarantined = await session.get(GoogleSheetsDelivery, exhausted_id)
    assert quarantined is not None
    assert quarantined.status == "quarantined"
    assert quarantined.quarantined_at == quarantine_at
    assert quarantined.quarantine_reason is not None
    assert quarantined.quarantine_reason.startswith("unexpected_health_response:")
    assert quarantined.payload == exhausted_payload
