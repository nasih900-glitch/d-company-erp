"""Daily collection summary generation, rotation, and lock safety."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Branch, Company, ManualCollection
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.integrations.google_sheets_collection_summary import (
    DAILY_COLLECTION_EVENT_TYPE,
    scan_daily_collection_summaries,
)
from app.services.integrations.google_sheets_mirror import (
    CONNECTION_TEST_EVENT_TYPE,
    CONNECTION_TEST_SOURCE_TYPE,
    DAILY_COLLECTIONS_EXPORT_MODE,
    enqueue_google_sheets_event,
    enqueue_google_sheets_event_if_enabled,
)
from tests.integration.test_google_sheets_collections import (
    TIMEZONE,
    _close_shift,
    _event_time,
    _paid_order,
    _shift,
    _terminal,
)


async def _enable_summary(session, seed_owner, *, target_day, now) -> Company:
    company = seed_owner["company"]
    company.google_sheets_webhook_url = "https://script.google.com/macros/s/daily-summary-test/exec"
    company.google_sheets_signing_secret_ciphertext = "test-ciphertext"
    company.google_sheets_mirror_enabled = True
    company.google_sheets_configuration_id = uuid4()
    company.google_sheets_configured_at = now
    company.google_sheets_export_mode = DAILY_COLLECTIONS_EXPORT_MODE
    company.google_sheets_summary_branch_id = seed_owner["branch"].id
    company.google_sheets_summary_start_date = target_day
    company.google_sheets_summary_timezone = TIMEZONE
    company.google_sheets_summary_scan_cursor_date = target_day
    company.google_sheets_summary_last_scanned_at = None
    company.google_sheets_summary_last_window_blockers = []
    company.google_sheets_summary_last_error = None
    await session.flush()
    return company


async def _verify_summary(session, company: Company, *, now: datetime) -> None:
    assert company.google_sheets_configuration_id is not None
    delivery = await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=company.google_sheets_configuration_id,
        event_type=CONNECTION_TEST_EVENT_TYPE,
        source_type=CONNECTION_TEST_SOURCE_TYPE,
        source_id=str(uuid4()),
        source_revision=str(company.google_sheets_configuration_id),
        occurred_at=now,
        payload={"export_mode": DAILY_COLLECTIONS_EXPORT_MODE, "status": "test"},
    )
    delivery.status = "delivered"
    delivery.delivered_at = now
    await session.flush()


async def _scan_only(session, company_id, *, now):
    other_ids = frozenset(await session.scalars(select(Company.id).where(Company.id != company_id)))
    return await scan_daily_collection_summaries(
        session,
        now=now,
        exclude_company_ids=other_ids,
    )


async def _summary_rows(session, company_id):
    return list(
        (
            await session.scalars(
                select(GoogleSheetsDelivery)
                .where(
                    GoogleSheetsDelivery.company_id == company_id,
                    GoogleSheetsDelivery.event_type == DAILY_COLLECTION_EVENT_TYPE,
                )
                .order_by(GoogleSheetsDelivery.source_revision)
            )
        ).all()
    )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_summary_mode_suppresses_individual_events_and_revisions_full_state(
    session, seed_owner
) -> None:
    now = datetime.now(UTC)
    target_day = now.astimezone(ZoneInfo(TIMEZONE)).date() - timedelta(days=4)
    terminal = await _terminal(session, seed_owner["branch"], "scanner")
    shift = await _shift(session, seed_owner, terminal, opened_at=_event_time(target_day))
    await _paid_order(session, seed_owner, shift, (("cash", 1_000),))
    await _close_shift(session, shift, seed_owner["owner"].id)
    company = await _enable_summary(session, seed_owner, target_day=target_day, now=now)

    suppressed = await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company.id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id=str(uuid4()),
        source_revision="recorded-v1",
        occurred_at=now,
        payload={"amount_minor": -50},
    )
    assert suppressed is None

    unverified = await _scan_only(session, company.id, now=now)
    assert unverified.companies_scanned == 0
    assert not await _summary_rows(session, company.id)

    await _verify_summary(session, company, now=now)
    first = await _scan_only(session, company.id, now=now)
    assert first.companies_scanned == 1
    assert first.deliveries_enqueued == 1
    rows = await _summary_rows(session, company.id)
    assert [row.source_revision for row in rows] == ["00000000000000000001"]
    assert rows[0].payload["status"] == "ready"
    assert rows[0].payload["cash_minor"] == 1_000
    seed_owner["branch"].timezone = "America/Los_Angeles"
    await session.flush()

    # Pending is already part of the latest immutable state: scanning the same
    # full snapshot must not duplicate it.
    company.google_sheets_summary_scan_cursor_date = target_day
    await session.flush()
    unchanged = await _scan_only(session, company.id, now=now)
    assert unchanged.companies_scanned == 1
    assert len(await _summary_rows(session, company.id)) == 1

    # A late manual collection yields a new full revision.
    manual = ManualCollection(
        id=uuid4(),
        company_id=company.id,
        branch_id=seed_owner["branch"].id,
        shift_id=None,
        business_date=target_day,
        method="upi",
        amount_minor=250,
        source_kind="manual_daily",
        source_ref=f"summary-{uuid4()}",
        idempotency_key=f"manual-collection:{uuid4()}",
        request_hash="a" * 64,
        created_by=seed_owner["owner"].id,
        source_integrity_revision=1,
    )
    session.add(manual)
    company.google_sheets_summary_scan_cursor_date = target_day
    await session.flush()
    changed = await _scan_only(session, company.id, now=now)
    assert changed.deliveries_enqueued == 1

    manual.voided_at = now
    manual.voided_by = seed_owner["owner"].id
    manual.void_reason = "Removed duplicate collection"
    company.google_sheets_summary_scan_cursor_date = target_day
    await session.flush()
    voided = await _scan_only(session, company.id, now=now)
    assert voided.deliveries_enqueued == 1

    # A newly discovered open shift supersedes the trusted ready revision.
    open_shift = await _shift(
        session,
        seed_owner,
        terminal,
        opened_at=_event_time(target_day, hour=18),
    )
    company.google_sheets_summary_scan_cursor_date = target_day
    await session.flush()
    blocked = await _scan_only(session, company.id, now=now)
    assert blocked.deliveries_enqueued == 1
    rows = await _summary_rows(session, company.id)
    assert [row.payload["status"] for row in rows] == [
        "ready",
        "ready",
        "ready",
        "blocked",
    ]
    assert rows[-1].payload["blockers"] == ["open_shift"]
    assert {row.payload["business_timezone"] for row in rows} == {TIMEZONE}

    await _paid_order(session, seed_owner, open_shift, (("cash", 75),))
    company.google_sheets_summary_scan_cursor_date = target_day
    await session.flush()
    still_blocked = await _scan_only(session, company.id, now=now)
    assert still_blocked.deliveries_enqueued == 0
    assert len(await _summary_rows(session, company.id)) == 4

    await _close_shift(session, open_shift, seed_owner["owner"].id)
    company.google_sheets_summary_scan_cursor_date = target_day
    await session.flush()
    ready_again = await _scan_only(session, company.id, now=now)
    assert ready_again.deliveries_enqueued == 1

    # Rotating any material configuration generation must deliver the same
    # financial state again, with one global revision sequence for this day.
    company.google_sheets_configuration_id = uuid4()
    company.google_sheets_configured_at = now + timedelta(seconds=1)
    company.google_sheets_summary_scan_cursor_date = target_day
    await session.flush()
    unverified_rotation = await _scan_only(session, company.id, now=now + timedelta(seconds=1))
    assert unverified_rotation.companies_scanned == 0
    await _verify_summary(session, company, now=now + timedelta(seconds=1))
    rotated = await _scan_only(session, company.id, now=now + timedelta(seconds=1))
    assert rotated.deliveries_enqueued == 1
    rows = await _summary_rows(session, company.id)
    assert [row.source_revision for row in rows] == [f"{revision:020d}" for revision in range(1, 7)]
    assert rows[-1].configuration_id == company.google_sheets_configuration_id
    assert rows[-1].payload == rows[-2].payload


@pytest.mark.integration
@pytest.mark.asyncio
async def test_summary_scanner_skips_a_concurrently_locked_company(session, seed_owner) -> None:
    now = datetime.now(UTC)
    target_day = now.astimezone(ZoneInfo(TIMEZONE)).date() - timedelta(days=3)
    company = await _enable_summary(session, seed_owner, target_day=target_day, now=now)
    await _verify_summary(session, company, now=now)
    await session.commit()

    async with AsyncSessionLocal() as locker, AsyncSessionLocal() as worker:
        await locker.execute(select(Company).where(Company.id == company.id).with_for_update())
        async with asyncio.timeout(2), worker.begin():
            other_ids = frozenset(
                await worker.scalars(select(Company.id).where(Company.id != company.id))
            )
            await scan_daily_collection_summaries(worker, now=now, exclude_company_ids=other_ids)
        assert not await _summary_rows(worker, company.id)
        await locker.rollback()

    async with AsyncSessionLocal() as worker, worker.begin():
        other_ids = frozenset(
            await worker.scalars(select(Company.id).where(Company.id != company.id))
        )
        scanned = await scan_daily_collection_summaries(
            worker, now=now, exclude_company_ids=other_ids
        )
    assert scanned.companies_scanned == 1
    async with AsyncSessionLocal() as observer:
        assert await _summary_rows(observer, company.id)
        row = await observer.get(Company, company.id, with_for_update=True)
        assert row is not None
        row.google_sheets_mirror_enabled = False
        row.google_sheets_export_mode = "legacy_transactions"
        row.google_sheets_summary_branch_id = None
        row.google_sheets_summary_start_date = None
        row.google_sheets_summary_timezone = None
        row.google_sheets_summary_scan_cursor_date = None
        row.google_sheets_summary_last_scanned_at = None
        row.google_sheets_summary_last_window_blockers = []
        row.google_sheets_summary_last_error = None
        await observer.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_current_day_health_does_not_append_blocked_intraday_revisions(
    session, seed_owner
) -> None:
    now = datetime.now(UTC)
    today = now.astimezone(ZoneInfo(TIMEZONE)).date()
    company = await _enable_summary(session, seed_owner, target_day=today, now=now)
    await _verify_summary(session, company, now=now)

    first = await _scan_only(session, company.id, now=now)
    assert first.deliveries_enqueued == 0
    assert company.google_sheets_summary_last_window_blockers == [
        {
            "business_date": today.isoformat(),
            "blockers": ["current_or_future_business_day"],
        }
    ]
    second = await _scan_only(session, company.id, now=now + timedelta(minutes=5))
    assert second.deliveries_enqueued == 0
    assert not await _summary_rows(session, company.id)


@pytest.mark.integration
@pytest.mark.asyncio
async def test_rotating_cursor_revisits_late_history_without_manual_rewind(
    session, seed_owner
) -> None:
    now = datetime.now(UTC)
    today = now.astimezone(ZoneInfo(TIMEZONE)).date()
    start_day = today - timedelta(days=40)
    company = await _enable_summary(session, seed_owner, target_day=start_day, now=now)
    company.google_sheets_summary_scan_cursor_date = None
    await _verify_summary(session, company, now=now)

    first = await _scan_only(session, company.id, now=now)
    assert first.days_scanned == 31
    assert company.google_sheets_summary_scan_cursor_date == today - timedelta(days=31)
    second = await _scan_only(session, company.id, now=now)
    assert second.days_scanned == 10
    assert company.google_sheets_summary_scan_cursor_date == today

    late = ManualCollection(
        id=uuid4(),
        company_id=company.id,
        branch_id=seed_owner["branch"].id,
        shift_id=None,
        business_date=start_day,
        method="upi",
        amount_minor=375,
        source_kind="manual_daily",
        source_ref=f"late-{uuid4()}",
        idempotency_key=f"manual-collection:{uuid4()}",
        request_hash="b" * 64,
        created_by=seed_owner["owner"].id,
        source_integrity_revision=1,
    )
    session.add(late)
    await session.flush()
    third = await _scan_only(session, company.id, now=now)
    assert third.days_scanned == 31
    fourth = await _scan_only(session, company.id, now=now)
    assert fourth.days_scanned == 10
    source_id = f"{seed_owner['branch'].id}:{start_day.isoformat()}"
    start_rows = list(
        await session.scalars(
            select(GoogleSheetsDelivery)
            .where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.source_id == source_id,
            )
            .order_by(GoogleSheetsDelivery.source_revision)
        )
    )
    assert len(start_rows) == 2
    assert start_rows[-1].payload["upi_minor"] == 375


@pytest.mark.integration
@pytest.mark.asyncio
async def test_poisoned_company_is_recorded_without_starving_next_company(
    session, seed_owner
) -> None:
    now = datetime.now(UTC)
    target_day = now.astimezone(ZoneInfo(TIMEZONE)).date() - timedelta(days=2)
    poison = await _enable_summary(session, seed_owner, target_day=target_day, now=now)
    poison.google_sheets_summary_timezone = "not/a-zone"
    await _verify_summary(session, poison, now=now)

    healthy = Company(id=uuid4(), name=f"Healthy summary {uuid4()}")
    healthy_branch = Branch(
        id=uuid4(),
        company_id=healthy.id,
        name="Healthy summary branch",
        invoice_series_code="HS",
    )
    session.add_all([healthy, healthy_branch])
    await session.flush()
    healthy.google_sheets_webhook_url = "https://script.google.com/macros/s/healthy-summary/exec"
    healthy.google_sheets_signing_secret_ciphertext = "test-ciphertext"
    healthy.google_sheets_mirror_enabled = True
    healthy.google_sheets_configuration_id = uuid4()
    healthy.google_sheets_configured_at = now
    healthy.google_sheets_export_mode = DAILY_COLLECTIONS_EXPORT_MODE
    healthy.google_sheets_summary_branch_id = healthy_branch.id
    healthy.google_sheets_summary_start_date = target_day
    healthy.google_sheets_summary_timezone = TIMEZONE
    healthy.google_sheets_summary_scan_cursor_date = target_day
    healthy.google_sheets_summary_last_scanned_at = now - timedelta(days=1)
    healthy.google_sheets_summary_last_window_blockers = []
    await session.flush()
    await _verify_summary(session, healthy, now=now)

    failed = await scan_daily_collection_summaries(
        session,
        now=now,
        exclude_company_ids=frozenset({healthy.id}),
    )
    assert failed.companies_failed == 1
    await session.refresh(poison)
    assert poison.google_sheets_summary_last_error is not None

    succeeded = await scan_daily_collection_summaries(
        session,
        now=now,
        exclude_company_ids=frozenset({poison.id}),
    )
    assert succeeded.companies_failed == 0
    assert succeeded.company_id == healthy.id
    assert succeeded.deliveries_enqueued == 1
