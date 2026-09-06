"""Audit or apply the canonical D Company gaming tariff.

Examples (run from ``backend/``):

    python -m scripts.ensure_gaming_tariff --dry-run
    python -m scripts.ensure_gaming_tariff

The command is non-destructive. Unexpected active PS5/simulator package rows
stop the whole transaction and are printed for explicit operator review.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
from dataclasses import dataclass
from uuid import UUID

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import Branch, Company
from app.services.gaming.tariff_catalog import (
    GamingTariffCatalogConflictError,
    GamingTariffUpsertResult,
    upsert_d_company_gaming_tariff,
)

EXPECTED_COMPANY_NAME = "D Company"
COMPANY_ID_ENV = "D_COMPANY_TARIFF_COMPANY_ID"


@dataclass(frozen=True, slots=True)
class Arguments:
    company_id: UUID | None
    branch_id: UUID | None
    dry_run: bool


def _optional_uuid(value: str) -> UUID:
    try:
        return UUID(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"invalid UUID: {value}") from exc


def _arguments() -> Arguments:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--company-id",
        type=_optional_uuid,
        default=os.getenv(COMPANY_ID_ENV),
        help=f"exact D Company UUID (or {COMPANY_ID_ENV})",
    )
    parser.add_argument(
        "--branch-id",
        type=_optional_uuid,
        help="limit the audit/upsert to one active branch",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the exact create/update plan without writing",
    )
    parsed = parser.parse_args()
    company_id = parsed.company_id
    if isinstance(company_id, str):
        company_id = _optional_uuid(company_id)
    return Arguments(
        company_id=company_id,
        branch_id=parsed.branch_id,
        dry_run=bool(parsed.dry_run),
    )


async def _resolve_company(session, company_id: UUID | None) -> Company:
    if company_id is not None:
        company = (
            await session.execute(
                select(Company).where(
                    Company.id == company_id,
                    Company.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if company is None:
            raise RuntimeError(f"active company {company_id} was not found")
        if company.name != EXPECTED_COMPANY_NAME:
            raise RuntimeError(
                f"company {company_id} is {company.name!r}, expected {EXPECTED_COMPANY_NAME!r}"
            )
        return company

    companies = (
        await session.execute(
            select(Company)
            .where(
                Company.name == EXPECTED_COMPANY_NAME,
                Company.deleted_at.is_(None),
            )
            .order_by(Company.created_at, Company.id)
        )
    ).scalars().all()
    if len(companies) != 1:
        raise RuntimeError(
            f"expected exactly one active {EXPECTED_COMPANY_NAME!r} tenant, "
            f"found {len(companies)}; pass --company-id explicitly"
        )
    return companies[0]


async def _resolve_branches(
    session,
    *,
    company_id: UUID,
    branch_id: UUID | None,
) -> list[Branch]:
    statement = select(Branch).where(
        Branch.company_id == company_id,
        Branch.deleted_at.is_(None),
    )
    if branch_id is not None:
        statement = statement.where(Branch.id == branch_id)
    branches = (
        await session.execute(statement.order_by(Branch.created_at, Branch.id))
    ).scalars().all()
    if not branches:
        scope = f"branch {branch_id}" if branch_id else "any active branch"
        raise RuntimeError(f"D Company has no matching {scope}")
    return list(branches)


def _print_result(branch: Branch, result: GamingTariffUpsertResult) -> None:
    mode = "DRY RUN" if result.dry_run else "APPLIED"
    print(
        f"[{mode}] {branch.name} ({branch.id}): "
        f"create={len(result.created_codes)} update={len(result.updated_codes)} "
        f"unchanged={len(result.unchanged_codes)}"
    )
    if result.created_codes:
        print(f"  create: {', '.join(result.created_codes)}")
    if result.updated_codes:
        print(f"  update: {', '.join(result.updated_codes)}")


async def run(arguments: Arguments) -> None:
    async with AsyncSessionLocal() as session:
        try:
            company = await _resolve_company(session, arguments.company_id)
            branches = await _resolve_branches(
                session,
                company_id=company.id,
                branch_id=arguments.branch_id,
            )
            results: list[tuple[Branch, GamingTariffUpsertResult]] = []
            for branch in branches:
                result = await upsert_d_company_gaming_tariff(
                    session,
                    company_id=company.id,
                    branch_id=branch.id,
                    dry_run=arguments.dry_run,
                )
                results.append((branch, result))
            if not arguments.dry_run:
                await session.commit()
        except GamingTariffCatalogConflictError as exc:
            await session.rollback()
            print(f"TARIFF CONFLICT: {exc}", file=sys.stderr)
            raise SystemExit(2) from exc
        except RuntimeError as exc:
            await session.rollback()
            print(f"TARIFF ABORTED: {exc}", file=sys.stderr)
            raise SystemExit(2) from exc

    for branch, result in results:
        _print_result(branch, result)


def main() -> None:
    asyncio.run(run(_arguments()))


if __name__ == "__main__":
    main()
