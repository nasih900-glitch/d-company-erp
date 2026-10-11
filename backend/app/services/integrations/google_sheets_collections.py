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
    WITH inputs AS (
      SELECT CAST(:company_id AS uuid) AS company_id,
             CAST(:branch_id AS uuid) AS branch_id,
             CAST(:timezone_name AS text) AS timezone_name,
             CAST(:start_date AS date) AS start_date,
             CAST(:end_date AS date) AS end_date,
             CAST(:start_utc AS timestamptz) AS start_utc,
             CAST(:end_exclusive_utc AS timestamptz) AS end_exclusive_utc
    ), days AS (
      SELECT series.day::date AS day
      FROM inputs i
      CROSS JOIN LATERAL generate_series(
        i.start_date, i.end_date, INTERVAL '1 day'
      ) AS series(day)
    ), scoped_shifts AS (
      SELECT s.id, s.status,
             (s.opened_at AT TIME ZONE i.timezone_name)::date AS day
      FROM shifts s
      CROSS JOIN inputs i
      WHERE s.company_id = i.company_id
        AND s.branch_id = i.branch_id
        AND s.opened_at >= i.start_utc
        AND s.opened_at < i.end_exclusive_utc
    ), movements AS (
      SELECT s.day, p.method AS rail, p.amount_minor AS amount_minor
      FROM payments p
      JOIN scoped_shifts s ON s.id = p.shift_id
      JOIN orders o ON o.id = p.order_id AND o.shift_id = s.id
      CROSS JOIN inputs i
      WHERE o.company_id = i.company_id AND o.branch_id = i.branch_id

      UNION ALL
      SELECT s.day, p.method, p.amount_minor
      FROM membership_payments p
      JOIN scoped_shifts s ON s.id = p.shift_id
      CROSS JOIN inputs i
      WHERE p.company_id = i.company_id AND p.branch_id = i.branch_id

      UNION ALL
      SELECT s.day, r.settlement_method,
             CASE WHEN r.settlement_method = 'store_credit'
                  THEN 0 ELSE -r.amount_minor END
      FROM refunds r
      JOIN scoped_shifts s ON s.id = r.settlement_shift_id
      JOIN orders o ON o.id = r.order_id
      LEFT JOIN pos_refund_requests q
        ON q.id = r.request_id
       AND q.company_id = r.company_id
       AND q.branch_id = r.branch_id
       AND q.shift_id = r.settlement_shift_id
       AND q.order_id = r.order_id
       AND q.settlement_method = r.settlement_method
      CROSS JOIN inputs i
      WHERE r.company_id = i.company_id AND r.branch_id = i.branch_id
        AND o.company_id = i.company_id AND o.branch_id = i.branch_id
        AND (r.request_id IS NULL OR q.id IS NOT NULL)

      UNION ALL
      SELECT s.day, r.method, -r.amount_minor
      FROM membership_refund_settlements r
      JOIN scoped_shifts s ON s.id = r.shift_id
      JOIN membership_refunds q
        ON q.id = r.refund_id
       AND q.company_id = r.company_id
       AND q.branch_id = r.branch_id
       AND q.shift_id = r.shift_id
       AND q.payment_id = r.payment_id
       AND q.method = r.method
       AND q.amount_minor = r.amount_minor
      JOIN membership_payments p
        ON p.id = r.payment_id
       AND p.company_id = r.company_id
      CROSS JOIN inputs i
      WHERE r.company_id = i.company_id AND r.branch_id = i.branch_id

      UNION ALL
      SELECT m.business_date, m.method, m.amount_minor
      FROM manual_collections m
      LEFT JOIN shifts s
        ON s.id = m.shift_id
       AND s.company_id = m.company_id
       AND s.branch_id = m.branch_id
      CROSS JOIN inputs i
      WHERE m.company_id = i.company_id AND m.branch_id = i.branch_id
        AND m.business_date BETWEEN i.start_date AND i.end_date
        AND m.voided_at IS NULL
        AND (m.shift_id IS NULL OR s.id IS NOT NULL)

      UNION ALL
      SELECT s.day, 'cash', -c.amount_minor
      FROM finance_source_corrections c
      JOIN scoped_shifts s ON s.id = c.settlement_shift_id
      JOIN manual_collections m ON m.id = c.manual_collection_id
      JOIN shifts original_shift
        ON original_shift.id = c.original_shift_id
       AND original_shift.company_id = c.company_id
       AND original_shift.branch_id = c.branch_id
      CROSS JOIN inputs i
      WHERE c.company_id = i.company_id AND c.branch_id = i.branch_id
        AND c.source_type = 'manual_collection'
        AND m.company_id = i.company_id AND m.branch_id = i.branch_id
        AND m.shift_id = c.original_shift_id
        AND m.method = 'cash'
    ), rails AS (
      SELECT day, COALESCE(rail, 'unknown') AS rail,
             SUM(amount_minor)::bigint AS net_minor,
             COUNT(*)::bigint AS movement_count
      FROM movements
      GROUP BY day, COALESCE(rail, 'unknown')
    ), daily AS (
      SELECT day,
             COALESCE(SUM(net_minor) FILTER (WHERE rail = 'cash'), 0)::bigint
               AS cash_minor,
             COALESCE(SUM(net_minor) FILTER (WHERE rail = 'upi'), 0)::bigint
               AS upi_minor,
             COALESCE(
               SUM(net_minor) FILTER (WHERE rail IN ('cash', 'upi')),
               0
             )::bigint AS total_minor,
             COALESCE(SUM(movement_count), 0)::bigint AS movement_count,
             COALESCE(
               jsonb_object_agg(rail, net_minor)
                 FILTER (WHERE rail NOT IN ('cash', 'upi', 'store_credit')),
               '{}'::jsonb
             ) AS unsupported_rails
      FROM rails
      GROUP BY day
    ), shift_counts AS (
      SELECT day, COUNT(*)::bigint AS shift_count,
             0::bigint AS open_shift_count
      FROM scoped_shifts
      GROUP BY day
    ), open_shift_days AS (
      SELECT day, id
      FROM scoped_shifts
      WHERE status = 'open'
      UNION
      SELECT m.business_date, s.id
      FROM manual_collections m
      JOIN shifts s
        ON s.id = m.shift_id
       AND s.company_id = m.company_id
       AND s.branch_id = m.branch_id
      CROSS JOIN inputs i
      WHERE m.company_id = i.company_id
        AND m.branch_id = i.branch_id
        AND m.business_date BETWEEN i.start_date AND i.end_date
        AND m.method = 'cash'
        AND m.voided_at IS NULL
        AND s.status = 'open'
    ), open_shift_counts AS (
      SELECT day, COUNT(DISTINCT id)::bigint AS open_shift_count
      FROM open_shift_days
      GROUP BY day
    ), legacy_refunds AS (
      SELECT (COALESCE(r.settled_at, r.created_at)
                AT TIME ZONE i.timezone_name)::date AS day,
             COUNT(*)::bigint AS legacy_refund_count
      FROM refunds r
      JOIN orders o ON o.id = r.order_id
      CROSS JOIN inputs i
      WHERE o.company_id = i.company_id AND o.branch_id = i.branch_id
        AND (r.company_id IS NULL OR r.company_id = i.company_id)
        AND (r.branch_id IS NULL OR r.branch_id = i.branch_id)
        AND r.settlement_shift_id IS NULL
        AND COALESCE(r.settled_at, r.created_at) >= i.start_utc
        AND COALESCE(r.settled_at, r.created_at) < i.end_exclusive_utc
      GROUP BY (COALESCE(r.settled_at, r.created_at)
                  AT TIME ZONE i.timezone_name)::date
    )
    SELECT d.day,
           COALESCE(a.cash_minor, 0)::bigint AS cash_minor,
           COALESCE(a.upi_minor, 0)::bigint AS upi_minor,
           COALESCE(a.total_minor, 0)::bigint AS total_minor,
           COALESCE(a.movement_count, 0)::bigint AS movement_count,
           COALESCE(s.shift_count, 0)::bigint AS shift_count,
           COALESCE(o.open_shift_count, 0)::bigint AS open_shift_count,
           COALESCE(l.legacy_refund_count, 0)::bigint AS legacy_refund_count,
           COALESCE(a.unsupported_rails, '{}'::jsonb) AS unsupported_rails
    FROM days d
    LEFT JOIN daily a ON a.day = d.day
    LEFT JOIN shift_counts s ON s.day = d.day
    LEFT JOIN open_shift_counts o ON o.day = d.day
    LEFT JOIN legacy_refunds l ON l.day = d.day
    ORDER BY d.day
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
