#!/usr/bin/env python3
"""One-time import of the owner's Google Sheet 'Daily collection' tab.

D Company tracked daily UPI + Cash totals in a spreadsheet for the period
after the production database was reset (11 Jul 2026 - 8 Aug 2026), rather
than through the ERP. This script inserts that period as ManualCollection
rows (source_kind='legacy_daily', the field this model was purpose-built
for: "legacy daily totals... where only the payment-method total is
known" - see app/models/finance.py's ManualCollection docstring).

Idempotent by construction: each row gets a stable idempotency_key
(sheet-daily-<ISO date>-<method>), and the model has a UNIQUE constraint on
(company_id, idempotency_key). Re-running this script is a safe no-op via
that constraint, not custom logic here.

Run from the `backend` directory inside the backend container:
    python -m scripts.import_sheet_daily_collections
"""

from __future__ import annotations

import asyncio
from datetime import date

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Branch, Company, ManualCollection, User
from app.services.audit.recorder import clear_actor, install_audit_listeners, set_actor

# (business_date, upi_minor, cash_minor) - transcribed from the sheet's
# Daily collection tab, cross-checked: upi + cash == the sheet's own Total
# column for every single row before this list was written.
ROWS: list[tuple[date, int, int]] = [
    (date(2026, 7, 11), 89_100, 65_000),
    (date(2026, 7, 12), 114_000, 45_000),
    (date(2026, 7, 13), 182_000, 35_000),
    (date(2026, 7, 14), 90_000, 50_000),
    (date(2026, 7, 15), 126_000, 108_000),
    (date(2026, 7, 16), 162_000, 21_000),
    (date(2026, 7, 17), 125_000, 35_000),
    (date(2026, 7, 18), 156_800, 60_000),
    (date(2026, 7, 19), 246_500, 115_000),
    (date(2026, 7, 20), 196_000, 15_000),
    (date(2026, 7, 21), 100_100, 35_000),
    (date(2026, 7, 22), 118_000, 30_000),
    (date(2026, 7, 23), 212_000, 92_000),
    (date(2026, 7, 24), 208_000, 28_000),
    (date(2026, 7, 25), 209_000, 105_000),
    (date(2026, 7, 26), 167_000, 132_000),
    (date(2026, 7, 27), 194_000, 99_000),
    (date(2026, 7, 28), 254_000, 93_000),
    (date(2026, 7, 29), 208_500, 76_000),
    (date(2026, 7, 30), 184_000, 54_000),
    (date(2026, 7, 31), 172_000, 85_000),
    (date(2026, 8, 1), 8_000, 0),  # sheet: Cash "Nil"
    (date(2026, 8, 2), 168_000, 183_000),
    (date(2026, 8, 3), 181_100, 69_000),
    (date(2026, 8, 4), 100_000, 77_000),
    (date(2026, 8, 5), 164_000, 51_000),
    (date(2026, 8, 6), 118_000, 34_000),
    (date(2026, 8, 7), 140_000, 30_000),
    (date(2026, 8, 8), 159_500, 191_000),
]

SOURCE_KIND = "legacy_daily"
NOTE = "Imported from D Company Google Sheet 'Daily collection' tab"


async def main() -> None:
    install_audit_listeners()
    async with AsyncSessionLocal() as session:
        company = (await session.execute(select(Company))).scalars().first()
        if company is None:
            raise SystemExit("No Company row found - aborting.")
        branch = (
            (await session.execute(select(Branch).where(Branch.company_id == company.id)))
            .scalars()
            .first()
        )
        if branch is None:
            raise SystemExit("No Branch row found for company - aborting.")
        owner = (
            (
                await session.execute(
                    select(User)
                    .where(User.company_id == company.id)
                    .order_by(User.created_at)
                )
            )
            .scalars()
            .first()
        )
        if owner is None:
            raise SystemExit("No User row found to attribute the import to - aborting.")

        set_actor(user_id=owner.id, company_id=company.id)
        created = 0
        skipped_zero = 0
        for business_date, upi_minor, cash_minor in ROWS:
            for method, amount_minor in (("upi", upi_minor), ("cash", cash_minor)):
                if amount_minor <= 0:
                    skipped_zero += 1
                    continue
                source_ref = f"sheet-daily-{business_date.isoformat()}"
                idempotency_key = f"{source_ref}-{method}"
                exists = await session.execute(
                    select(ManualCollection.id).where(
                        ManualCollection.company_id == company.id,
                        ManualCollection.idempotency_key == idempotency_key,
                    )
                )
                if exists.scalar_one_or_none() is not None:
                    continue
                session.add(
                    ManualCollection(
                        company_id=company.id,
                        branch_id=branch.id,
                        business_date=business_date,
                        method=method,
                        amount_minor=amount_minor,
                        source_kind=SOURCE_KIND,
                        source_ref=source_ref,
                        note=NOTE,
                        idempotency_key=idempotency_key,
                        created_by=owner.id,
                    )
                )
                created += 1
        await session.commit()
        clear_actor()
        print(f"Inserted {created} manual collection rows (skipped {skipped_zero} zero-amount cells).")


if __name__ == "__main__":
    asyncio.run(main())
