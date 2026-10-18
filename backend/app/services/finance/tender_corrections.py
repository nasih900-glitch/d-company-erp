"""Shared effective rail and append-only tender receipt queries."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING
from zoneinfo import ZoneInfo

if TYPE_CHECKING:
    from datetime import date
    from uuid import UUID

from sqlalchemy import func, select, text

from app.core.errors import BusinessRuleError, NotFoundError
from app.models import Branch, Company, Payment, PaymentTenderCorrection


def effective_payment_method():
    """A correlated projection: financial source Payment.method stays immutable."""
    return func.coalesce(
        select(PaymentTenderCorrection.effective_method)
        .where(PaymentTenderCorrection.payment_id == Payment.id)
        .correlate(Payment)
        .scalar_subquery(),
        Payment.method,
    )


async def corrections_for_payments(session, payment_ids):
    if not payment_ids:
        return {}
    rows = (
        await session.scalars(
            select(PaymentTenderCorrection).where(
                PaymentTenderCorrection.payment_id.in_(payment_ids)
            )
        )
    ).all()
    return {row.payment_id: row for row in rows}


async def lock_sources(session, company_id: UUID):
    await session.execute(
        text("SELECT dcompany_collection_lock(:company)"), {"company": company_id}
    )


async def source_context(session, tenant, branch_id: UUID, day: date, *, require_ready=True):
    if not tenant.in_branch(branch_id):
        raise NotFoundError("branch not found")
    branch = await session.scalar(
        select(Branch).where(
            Branch.id == branch_id,
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if branch is None:
        raise NotFoundError("branch not found")
    company = await session.get(Company, tenant.company_id, populate_existing=True)
    if company is None or company.deleted_at is not None:
        raise NotFoundError("company not found")
    zone = company.google_sheets_summary_timezone or company.timezone
    if day > datetime.now(UTC).astimezone(ZoneInfo(zone)).date():
        raise BusinessRuleError("A future business day cannot be attested as collected actuals.")
    result = (
        (
            await session.execute(
                text("""SELECT
        dcompany_collection_fingerprint(:company,:branch,:day,:zone) fingerprint,
        cash_minor,upi_minor FROM dcompany_tender_totals(:company,:branch,:day,:zone)"""),
                {"company": company.id, "branch": branch.id, "day": day, "zone": zone},
            )
        )
        .mappings()
        .one()
    )
    from app.services.integrations.google_sheets_collections import (
        CollectionReadinessBlocker,
        calculate_daily_collections,
    )

    snapshots = await calculate_daily_collections(
        session,
        company_id=company.id,
        branch_id=branch.id,
        timezone_name=zone,
        start_date=day,
        end_date=day,
    )
    snapshot = snapshots[0]
    # A Cash/UPI attestation does not recount or change Card/QR/Wallet. The
    # Sheet's stricter two-rail export readiness remains unchanged.
    other_rails = {row.rail: row.net_minor for row in snapshot.unsupported_rails}
    blockers = [
        row for row in snapshot.blockers if row != CollectionReadinessBlocker.UNSUPPORTED_RAIL
    ]
    if any(rail not in {"card", "qr", "wallet"} for rail in other_rails):
        blockers.append(CollectionReadinessBlocker.UNSUPPORTED_RAIL)
    if require_ready and blockers:
        raise BusinessRuleError(
            "This business day is not ready: " + ", ".join(x.value for x in blockers)
        )
    # The database trigger independently checks this projection on direct SQL.
    if (snapshot.cash_minor, snapshot.upi_minor) != (result["cash_minor"], result["upi_minor"]):
        raise BusinessRuleError("Collection sources require review before correcting this day.")
    return (
        company,
        zone,
        {
            **result,
            "unchanged_other_rails": other_rails,
            "recorded_all_rails_total_minor": snapshot.total_minor + sum(other_rails.values()),
        },
    )
