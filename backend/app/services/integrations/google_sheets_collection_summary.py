"""Generation-bound daily Cash/UPI summary outbox scanner."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING
from zoneinfo import ZoneInfo

from sqlalchemy import select, update

from app.models import Branch, Company
from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.integrations.google_sheets_collections import (
    MAX_COLLECTION_RANGE_DAYS,
    DailyCollectionSnapshot,
    calculate_daily_collections,
)
from app.services.integrations.google_sheets_mirror import (
    CONNECTION_TEST_EVENT_TYPE,
    CONNECTION_TEST_SOURCE_TYPE,
    DAILY_COLLECTIONS_EXPORT_MODE,
    enqueue_google_sheets_event,
    payload_sha256,
)

if TYPE_CHECKING:
    from collections.abc import Mapping
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


DAILY_COLLECTION_EVENT_TYPE = "collections.daily_snapshot"
DAILY_COLLECTION_SOURCE_TYPE = "daily_collections"
SOURCE_REVISION_WIDTH = 20


@dataclass(frozen=True, slots=True)
class DailyCollectionScanResult:
    companies_scanned: int
    days_scanned: int
    deliveries_enqueued: int
    companies_failed: int = 0
    company_id: UUID | None = None


def _snapshot_payload(
    snapshot: DailyCollectionSnapshot,
    *,
    branch: Branch,
    company: Company,
    timezone_name: str,
) -> dict[str, object]:
    return {
        "blockers": [blocker.value for blocker in snapshot.blockers],
        "branch": branch.name,
        "business_date": snapshot.business_date.isoformat(),
        "business_timezone": timezone_name,
        "cash_minor": snapshot.cash_minor,
        "currency": company.currency,
        "export_mode": DAILY_COLLECTIONS_EXPORT_MODE,
        "legacy_refund_count": snapshot.legacy_refund_count,
        "movement_count": snapshot.movement_count,
        "open_shift_count": snapshot.open_shift_count,
        "shift_count": snapshot.shift_count,
        "status": "ready" if snapshot.ready else "blocked",
        "total_minor": snapshot.total_minor,
        "unsupported_rails": [
            {"net_minor": rail.net_minor, "rail": rail.rail} for rail in snapshot.unsupported_rails
        ],
        "upi_minor": snapshot.upi_minor,
    }


def _next_source_revision(latest: GoogleSheetsDelivery | None) -> str:
    if latest is None:
        number = 1
    else:
        if (
            len(latest.source_revision) != SOURCE_REVISION_WIDTH
            or not latest.source_revision.isascii()
            or not latest.source_revision.isdigit()
        ):
            raise ValueError("daily collection source revision is malformed")
        number = int(latest.source_revision) + 1
    revision = str(number).zfill(SOURCE_REVISION_WIDTH)
    if len(revision) != SOURCE_REVISION_WIDTH:
        raise ValueError("daily collection source revision exhausted")
    return revision


async def _enqueue_snapshot_if_changed(
    session: AsyncSession,
    *,
    company: Company,
    branch: Branch,
    snapshot: DailyCollectionSnapshot,
    timezone_name: str,
    now: datetime,
) -> bool:
    if company.google_sheets_configuration_id is None:
        return False
    source_id = f"{branch.id}:{snapshot.business_date.isoformat()}"
    latest = (
        await session.execute(
            select(GoogleSheetsDelivery)
            .where(
                GoogleSheetsDelivery.company_id == company.id,
                GoogleSheetsDelivery.event_type == DAILY_COLLECTION_EVENT_TYPE,
                GoogleSheetsDelivery.source_type == DAILY_COLLECTION_SOURCE_TYPE,
                GoogleSheetsDelivery.source_id == source_id,
            )
            .order_by(GoogleSheetsDelivery.source_revision.desc())
            .limit(1)
        )
    ).scalar_one_or_none()
    payload: Mapping[str, object] = _snapshot_payload(
        snapshot,
        branch=branch,
        company=company,
        timezone_name=timezone_name,
    )
    digest = payload_sha256(payload)
    new_status = payload["status"]
    if latest is None and new_status == "blocked":
        return False
    if latest is not None and latest.payload.get("status") == "blocked" and new_status == "blocked":
        return False
    if (
        latest is not None
        and latest.configuration_id == company.google_sheets_configuration_id
        and latest.payload_sha256 == digest
    ):
        return False
    await enqueue_google_sheets_event(
        session,
        company_id=company.id,
        configuration_id=company.google_sheets_configuration_id,
        event_type=DAILY_COLLECTION_EVENT_TYPE,
        source_type=DAILY_COLLECTION_SOURCE_TYPE,
        source_id=source_id,
        source_revision=_next_source_revision(latest),
        occurred_at=now,
        available_at=now,
        payload=payload,
    )
    return True


async def scan_daily_collection_summaries(
    session: AsyncSession,
    *,
    now: datetime | None = None,
    company_limit: int = 1,
    exclude_company_ids: frozenset[UUID] = frozenset(),
) -> DailyCollectionScanResult:
    """Scan one bounded window while locking only that company's configuration."""

    if company_limit != 1:
        raise ValueError("company_limit must be 1 to keep each company transactional")
    server_time = now or datetime.now(UTC)
    if server_time.tzinfo is None or server_time.utcoffset() is None:
        raise ValueError("now must include a timezone")
    server_time = server_time.astimezone(UTC)
    configured_query = (
        select(Company, Branch)
        .join(
            Branch,
            (Branch.id == Company.google_sheets_summary_branch_id)
            & (Branch.company_id == Company.id)
            & Branch.deleted_at.is_(None),
        )
        .where(
            Company.deleted_at.is_(None),
            Company.google_sheets_mirror_enabled.is_(True),
            Company.google_sheets_export_mode == DAILY_COLLECTIONS_EXPORT_MODE,
            Company.google_sheets_configuration_id.is_not(None),
            Company.google_sheets_configured_at.is_not(None),
            Company.google_sheets_summary_start_date.is_not(None),
            Company.google_sheets_summary_timezone.is_not(None),
            select(GoogleSheetsDelivery.id)
            .where(
                GoogleSheetsDelivery.company_id == Company.id,
                GoogleSheetsDelivery.configuration_id == Company.google_sheets_configuration_id,
                GoogleSheetsDelivery.event_type == CONNECTION_TEST_EVENT_TYPE,
                GoogleSheetsDelivery.source_type == CONNECTION_TEST_SOURCE_TYPE,
                GoogleSheetsDelivery.status == "delivered",
                GoogleSheetsDelivery.occurred_at >= Company.google_sheets_configured_at,
                GoogleSheetsDelivery.delivered_at >= Company.google_sheets_configured_at,
            )
            .exists(),
        )
        .order_by(
            Company.google_sheets_summary_last_scanned_at.asc().nullsfirst(),
            Company.id.asc(),
        )
        .limit(1)
        .with_for_update(of=Company, skip_locked=True)
    )
    if exclude_company_ids:
        configured_query = configured_query.where(Company.id.not_in(exclude_company_ids))
    configured = (await session.execute(configured_query)).all()
    days_scanned = 0
    deliveries_enqueued = 0
    companies_failed = 0
    for company, branch in configured:
        try:
            async with session.begin_nested():
                configured_start = company.google_sheets_summary_start_date
                timezone_name = company.google_sheets_summary_timezone
                if configured_start is None or timezone_name is None:
                    raise ValueError("daily collection summary scope is incomplete")
                today = server_time.astimezone(ZoneInfo(timezone_name)).date()
                window_end = min(
                    company.google_sheets_summary_scan_cursor_date or today,
                    today,
                )
                if window_end < configured_start:
                    window_end = today
                window_start = max(
                    configured_start,
                    window_end - timedelta(days=MAX_COLLECTION_RANGE_DAYS - 1),
                )
                snapshots = await calculate_daily_collections(
                    session,
                    company_id=company.id,
                    branch_id=branch.id,
                    timezone_name=timezone_name,
                    start_date=window_start,
                    end_date=window_end,
                    now=server_time,
                )
                blocker_state: list[dict[str, object]] = []
                company_days = 0
                company_deliveries = 0
                for snapshot in snapshots:
                    company_days += 1
                    if await _enqueue_snapshot_if_changed(
                        session,
                        company=company,
                        branch=branch,
                        snapshot=snapshot,
                        timezone_name=timezone_name,
                        now=server_time,
                    ):
                        company_deliveries += 1
                    if snapshot.blockers:
                        blocker_state.append(
                            {
                                "business_date": snapshot.business_date.isoformat(),
                                "blockers": [blocker.value for blocker in snapshot.blockers],
                            }
                        )
                company.google_sheets_summary_scan_cursor_date = (
                    window_start - timedelta(days=1) if window_start > configured_start else today
                )
                company.google_sheets_summary_last_scanned_at = server_time
                company.google_sheets_summary_last_window_blockers = blocker_state
                company.google_sheets_summary_last_error = None
            days_scanned += company_days
            deliveries_enqueued += company_deliveries
        except Exception:
            companies_failed += 1
            await session.execute(
                update(Company)
                .where(Company.id == company.id)
                .values(
                    google_sheets_summary_last_scanned_at=server_time,
                    google_sheets_summary_last_error=(
                        "Daily collection scan failed; retry scheduled."
                    ),
                )
            )
    await session.flush()
    return DailyCollectionScanResult(
        companies_scanned=len(configured),
        days_scanned=days_scanned,
        deliveries_enqueued=deliveries_enqueued,
        companies_failed=companies_failed,
        company_id=configured[0][0].id if configured else None,
    )


__all__ = [
    "DAILY_COLLECTION_EVENT_TYPE",
    "DAILY_COLLECTION_SOURCE_TYPE",
    "DailyCollectionScanResult",
    "scan_daily_collection_summaries",
]
