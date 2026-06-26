"""Automated P&L and operational-alert email worker.

Examples:
    python -m app.workers.pnl_alerts --period daily
    python -m app.workers.pnl_alerts --period weekly
    python -m app.workers.pnl_alerts --period all_due
"""

from __future__ import annotations

import argparse
import asyncio
import os
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Literal
from zoneinfo import ZoneInfo

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Company, Role, User, UserRole
from app.services.alerts import build_inventory_alerts, build_pnl_alerts
from app.services.email.mailer import Mailer, render_pnl_email
from app.services.reports import (
    PnLReport,
    ReportsAggregator,
    fiscal_quarter,
    fiscal_year_for_date,
    fy_full_range,
    fy_quarter_range,
)

PeriodCode = Literal["daily", "weekly", "monthly", "quarterly", "half_yearly", "yearly"]
CliPeriod = Literal[PeriodCode, "all_due"]

PUBLIC_URL = os.getenv("PUBLIC_URL", "https://dcompany.duckdns.org")
BUSINESS_TZ = ZoneInfo(os.getenv("BUSINESS_TIMEZONE", "Asia/Kolkata"))


@dataclass(frozen=True, slots=True)
class PeriodSpec:
    code: PeriodCode
    name: str
    start: date
    end: date
    label: str


def _fy_start_year(d: date) -> int:
    return int(fiscal_year_for_date(d).split("-")[0])


def _fy_label(start_year: int) -> str:
    return f"{start_year}-{str(start_year + 1)[-2:]}"


def period_spec(period: PeriodCode, today: date) -> PeriodSpec:
    """Return the previous completed business period for the given report code."""
    if period == "daily":
        d = today - timedelta(days=1)
        return PeriodSpec("daily", "Daily", d, d, d.strftime("%d-%b-%Y"))

    if period == "weekly":
        current_week_start = today - timedelta(days=today.weekday())
        end = current_week_start - timedelta(days=1)
        start = end - timedelta(days=6)
        return PeriodSpec(
            "weekly",
            "Weekly",
            start,
            end,
            f"Week {start.strftime('%d %b')} - {end.strftime('%d %b %Y')}",
        )

    if period == "monthly":
        current_month_start = today.replace(day=1)
        end = current_month_start - timedelta(days=1)
        start = end.replace(day=1)
        return PeriodSpec("monthly", "Monthly", start, end, start.strftime("%b %Y"))

    if period == "quarterly":
        current_q = fiscal_quarter(today)
        current_fy_start = _fy_start_year(today)
        if current_q == 1:
            q = 4
            fy = _fy_label(current_fy_start - 1)
        else:
            q = current_q - 1
            fy = _fy_label(current_fy_start)
        start, end = fy_quarter_range(fy, q)
        return PeriodSpec("quarterly", "Quarterly", start, end, f"{fy} Q{q}")

    if period == "half_yearly":
        fy_start = _fy_start_year(today)
        if 4 <= today.month <= 9:
            start = date(fy_start - 1, 10, 1)
            end = date(fy_start, 3, 31)
            label = f"{_fy_label(fy_start - 1)} H2"
        elif today.month >= 10:
            start = date(fy_start, 4, 1)
            end = date(fy_start, 9, 30)
            label = f"{_fy_label(fy_start)} H1"
        else:
            start = date(fy_start, 4, 1)
            end = date(fy_start, 9, 30)
            label = f"{_fy_label(fy_start)} H1"
        return PeriodSpec("half_yearly", "Half-yearly", start, end, label)

    current_fy_start = _fy_start_year(today)
    fy = _fy_label(current_fy_start - 1)
    start, end = fy_full_range(fy)
    return PeriodSpec("yearly", "Yearly", start, end, f"FY {fy}")


def due_periods(today: date) -> list[PeriodCode]:
    periods: list[PeriodCode] = ["daily"]
    if today.weekday() == 0:
        periods.append("weekly")
    if today.day == 1:
        periods.append("monthly")
        if today.month in (1, 4, 7, 10):
            periods.append("quarterly")
        if today.month in (4, 10):
            periods.append("half_yearly")
        if today.month == 4:
            periods.append("yearly")
    return periods


def _configured_recipients() -> list[str]:
    raw = os.getenv("REPORT_RECIPIENT_EMAILS", "") or os.getenv("ALERT_RECIPIENT_EMAILS", "")
    return [email.strip() for email in raw.split(",") if email.strip()]


async def _protected_owner_recipients(session, company_id) -> list[str]:
    rows = (
        await session.execute(
            select(User.email)
            .join(UserRole, UserRole.user_id == User.id)
            .join(Role, Role.id == UserRole.role_id)
            .where(
                User.company_id == company_id,
                User.deleted_at.is_(None),
                User.status == "active",
                Role.code == "super_owner",
            )
            .distinct()
        )
    ).scalars().all()
    return list(rows)


async def _report_for_spec(
    agg: ReportsAggregator,
    *,
    company_id,
    spec: PeriodSpec,
) -> PnLReport:
    if spec.code == "daily":
        return await agg.aggregate_daily(company_id=company_id, d=spec.start)
    if spec.code == "monthly":
        return await agg.aggregate_monthly(
            company_id=company_id,
            yyyy_mm=f"{spec.start.year:04d}-{spec.start.month:02d}",
        )
    if spec.code == "quarterly":
        fy, q_text = spec.label.split(" Q", 1)
        return await agg.aggregate_quarterly(company_id=company_id, fy=fy, q=int(q_text))
    if spec.code == "yearly":
        return await agg.aggregate_yearly(company_id=company_id, fy=spec.label.replace("FY ", ""))
    return await agg.aggregate(
        company_id=company_id,
        period_start=spec.start,
        period_end=spec.end,
        period="custom",
        label=spec.label,
    )


async def _previous_report(
    agg: ReportsAggregator,
    *,
    company_id,
    spec: PeriodSpec,
) -> PnLReport:
    days = (spec.end - spec.start).days + 1
    end = spec.start - timedelta(days=1)
    start = end - timedelta(days=days - 1)
    return await agg.aggregate(
        company_id=company_id,
        period_start=start,
        period_end=end,
        period="custom",
        label=f"Previous {spec.name.lower()}",
    )


async def send_reports(period: CliPeriod, *, as_of: date | None = None) -> None:
    mailer = Mailer()
    if not mailer.configured:
        print("[pnl_alerts] SMTP env vars not configured - skipping email send.")
        return

    today = as_of or datetime.now(BUSINESS_TZ).date()
    periods = due_periods(today) if period == "all_due" else [period]
    env_recipients = _configured_recipients()

    async with AsyncSessionLocal() as session:
        companies = (
            await session.execute(select(Company).where(Company.deleted_at.is_(None)))
        ).scalars().all()

        for company in companies:
            recipients = env_recipients or await _protected_owner_recipients(session, company.id)
            if not recipients:
                print(f"[pnl_alerts] {company.name}: no recipients - skipping.")
                continue

            agg = ReportsAggregator(session)
            for p in periods:
                spec = period_spec(p, today)
                report = await _report_for_spec(agg, company_id=company.id, spec=spec)
                previous = await _previous_report(agg, company_id=company.id, spec=spec)
                alerts = build_pnl_alerts(report, previous)
                alerts.extend(await build_inventory_alerts(session, company_id=company.id))

                subject, html = render_pnl_email(
                    company_name=company.name,
                    period_name=spec.name,
                    report=report,
                    alerts=alerts,
                    public_url=f"{PUBLIC_URL}/#/reports",
                )
                try:
                    mailer.send(recipients, subject, html)
                    print(
                        f"[pnl_alerts] {company.name}: sent {spec.name.lower()} "
                        f"report to {len(recipients)} recipient(s)"
                    )
                except Exception as exc:
                    print(f"[pnl_alerts] {company.name}: SMTP failure - {exc}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Send automated P&L and alert emails.")
    parser.add_argument(
        "--period",
        choices=["daily", "weekly", "monthly", "quarterly", "half_yearly", "yearly", "all_due"],
        default="all_due",
    )
    parser.add_argument(
        "--as-of",
        help="Business date in YYYY-MM-DD, used to choose the previous completed period.",
    )
    args = parser.parse_args()
    as_of = date.fromisoformat(args.as_of) if args.as_of else None
    asyncio.run(send_reports(args.period, as_of=as_of))


if __name__ == "__main__":
    main()
