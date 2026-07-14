"""Backfill any DEFAULT_ROLES missing for companies that were seeded before
a new role code was added (e.g. "staff"). Idempotent — runs on every boot,
alongside seed.py/seed_india.py, and only inserts rows that don't exist yet.

  python -m scripts.ensure_roles
"""

from __future__ import annotations

import asyncio
from uuid import uuid4

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Company, Role
from scripts.seed import DEFAULT_ROLES


async def ensure_roles() -> None:
    async with AsyncSessionLocal() as s:
        companies = (await s.execute(select(Company.id))).scalars().all()
        created = 0
        for company_id in companies:
            existing_codes = set(
                (
                    await s.execute(
                        select(Role.code).where(Role.company_id == company_id)
                    )
                ).scalars().all()
            )
            for code, desc in DEFAULT_ROLES:
                if code in existing_codes:
                    continue
                s.add(
                    Role(
                        id=uuid4(),
                        company_id=company_id,
                        code=code,
                        name=code.title().replace("_", " "),
                        description=desc,
                        permissions=[],
                    )
                )
                created += 1
        if created:
            await s.commit()
            print(f"  backfilled {created} missing role row(s) across {len(companies)} compan(y/ies)")
        else:
            print("  all companies already have every default role; skipping")


if __name__ == "__main__":
    asyncio.run(ensure_roles())
