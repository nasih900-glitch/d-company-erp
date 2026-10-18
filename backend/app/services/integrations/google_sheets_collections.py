"""Read-only business-day cash and UPI collection snapshots."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from enum import StrEnum
from typing import TYPE_CHECKING, Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sqlalchemy import text

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

MAX_COLLECTION_RANGE_DAYS = 31


class CollectionReadinessBlocker(StrEnum):
    CURRENT_OR_FUTURE_DAY = "current_or_future_business_day"
    OPEN_SHIFT = "open_shift"
    UNSUPPORTED_RAIL = "unsupported_collection_rail"
    LEGACY_REFUND_WITHOUT_SETTLEMENT_SCOPE = "legacy_refund_without_settlement_scope"


@dataclass(frozen=True, slots=True)
class UnsupportedCollectionRail:
    rail: str
    net_minor: int


@dataclass(frozen=True, slots=True)
class DailyCollectionSnapshot:
    business_date: date
    cash_minor: int
    upi_minor: int
    total_minor: int
    movement_count: int
    shift_count: int
    open_shift_count: int
    legacy_refund_count: int
    unsupported_rails: tuple[UnsupportedCollectionRail, ...]
    blockers: tuple[CollectionReadinessBlocker, ...]

    @property
    def ready(self) -> bool:
        return not self.blockers


_COLLECTIONS_SQL = text(
    """
    SELECT day.day::date AS day,
           (snapshot->>'cash_minor')::bigint AS cash_minor,
           (snapshot->>'upi_minor')::bigint AS upi_minor,
           (snapshot->>'total_minor')::bigint AS total_minor,
           (snapshot->>'movement_count')::bigint AS movement_count,
           (snapshot->>'shift_count')::bigint AS shift_count,
           (snapshot->>'open_shift_count')::bigint AS open_shift_count,
           (snapshot->>'legacy_refund_count')::bigint AS legacy_refund_count,
           snapshot->'unsupported_rails' AS unsupported_rails
    FROM generate_series(CAST(:start_date AS date),CAST(:end_date AS date),INTERVAL '1 day') day(day)
    CROSS JOIN LATERAL dcompany_collection_day_snapshot(CAST(:company_id AS uuid),
        CAST(:branch_id AS uuid),day.day::date,CAST(:timezone_name AS text)) snapshot
    ORDER BY day.day
    """
)


def _validate_range(
    *,
    timezone_name: str,
    start_date: date,
    end_date: date,
) -> ZoneInfo:
    if isinstance(start_date, datetime) or isinstance(end_date, datetime):
        raise TypeError("collection range must use date values")
    if start_date > end_date:
        raise ValueError("collection range start_date must not exceed end_date")
    if (end_date - start_date).days + 1 > MAX_COLLECTION_RANGE_DAYS:
        raise ValueError(
            f"collection range cannot exceed {MAX_COLLECTION_RANGE_DAYS} days"
        )
    try:
        return ZoneInfo(timezone_name)
    except (TypeError, ZoneInfoNotFoundError) as exc:
        raise ValueError("timezone_name must be a valid IANA timezone") from exc


def _unsupported_rails(value: Any) -> tuple[UnsupportedCollectionRail, ...]:
    if not isinstance(value, dict):
        return ()
    return tuple(
        UnsupportedCollectionRail(rail=str(rail), net_minor=int(net_minor))
        for rail, net_minor in sorted(value.items())
    )


async def calculate_daily_collections(
    session: AsyncSession,
    *,
    company_id: UUID,
    branch_id: UUID,
    timezone_name: str,
    start_date: date,
    end_date: date,
    now: datetime | None = None,
) -> tuple[DailyCollectionSnapshot, ...]:
    """Return one tenant-scoped, read-only collection snapshot per local day."""

    timezone = _validate_range(
        timezone_name=timezone_name,
        start_date=start_date,
        end_date=end_date,
    )
    server_time = now or datetime.now(UTC)
    if server_time.tzinfo is None or server_time.utcoffset() is None:
        raise ValueError("now must include a timezone")
    server_time = server_time.astimezone(UTC)
    today = server_time.astimezone(timezone).date()
    start_utc = datetime.combine(start_date, time.min, timezone).astimezone(UTC)
    end_exclusive_utc = datetime.combine(
        end_date + timedelta(days=1), time.min, timezone
    ).astimezone(UTC)
    rows = (
        await session.execute(
            _COLLECTIONS_SQL,
            {
                "company_id": company_id,
                "branch_id": branch_id,
                "timezone_name": timezone_name,
                "start_date": start_date,
                "end_date": end_date,
                "start_utc": start_utc,
                "end_exclusive_utc": end_exclusive_utc,
            },
        )
    ).mappings()

    snapshots: list[DailyCollectionSnapshot] = []
    for row in rows:
        unsupported = _unsupported_rails(row["unsupported_rails"])
        blockers: list[CollectionReadinessBlocker] = []
        if row["day"] >= today:
            blockers.append(CollectionReadinessBlocker.CURRENT_OR_FUTURE_DAY)
        if int(row["open_shift_count"]):
            blockers.append(CollectionReadinessBlocker.OPEN_SHIFT)
        if unsupported:
            blockers.append(CollectionReadinessBlocker.UNSUPPORTED_RAIL)
        if int(row["legacy_refund_count"]):
            blockers.append(
                CollectionReadinessBlocker.LEGACY_REFUND_WITHOUT_SETTLEMENT_SCOPE
            )
        snapshots.append(
            DailyCollectionSnapshot(
                business_date=row["day"],
                cash_minor=int(row["cash_minor"]),
                upi_minor=int(row["upi_minor"]),
                total_minor=int(row["total_minor"]),
                movement_count=int(row["movement_count"]),
                shift_count=int(row["shift_count"]),
                open_shift_count=int(row["open_shift_count"]),
                legacy_refund_count=int(row["legacy_refund_count"]),
                unsupported_rails=unsupported,
                blockers=tuple(blockers),
            )
        )
    return tuple(snapshots)
