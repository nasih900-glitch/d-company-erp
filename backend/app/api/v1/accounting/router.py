"""Accounting endpoints backed by one balanced operational journal."""

from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import Account
from app.services.accounting import LedgerLine, build_operational_ledger

router = APIRouter()


class AccountDTO(BaseModel):
    id: UUID
    code: str
    name: str
    type: str
    normal_side: str
    is_active: bool


class TrialBalanceLineDTO(BaseModel):
    account_code: str
    account_name: str
    account_type: str
    debit_minor: int
    credit_minor: int
    balance_minor: int


class TrialBalanceDTO(BaseModel):
    as_of: date
    lines: list[TrialBalanceLineDTO]
    total_debit_minor: int
    total_credit_minor: int
    is_balanced: bool


class BalanceSheetSectionDTO(BaseModel):
    section: str
    lines: list[dict[str, str | int]]
    total_minor: int


class BalanceSheetDTO(BaseModel):
    as_of: date
    assets: BalanceSheetSectionDTO
    liabilities: BalanceSheetSectionDTO
    equity: BalanceSheetSectionDTO
    is_balanced: bool


class GeneralLedgerEntryDTO(BaseModel):
    occurred_at: datetime
    ref_type: str
    ref_id: UUID | None
    account_code: str
    account_name: str
    debit_minor: int
    credit_minor: int
    memo: str | None


def _trial_balance_lines(lines: list[LedgerLine]) -> list[TrialBalanceLineDTO]:
    grouped: dict[tuple[str, str, str], list[int]] = defaultdict(lambda: [0, 0])
    for line in lines:
        values = grouped[(line.account_code, line.account_name, line.account_type)]
        values[0] += line.debit_minor
        values[1] += line.credit_minor
    result = []
    for (code, name, account_type), (debit, credit) in sorted(grouped.items()):
        result.append(
            TrialBalanceLineDTO(
                account_code=code,
                account_name=name,
                account_type=account_type,
                debit_minor=debit,
                credit_minor=credit,
                balance_minor=(
                    debit - credit
                    if account_type in {"asset", "expense"}
                    else credit - debit
                ),
            )
        )
    return result


async def _as_of_ledger(
    session: SessionDep,
    company_id: UUID,
    as_of: date | None,
) -> tuple[date, list[LedgerLine]]:
    timezone_name = await company_timezone(session, company_id)
    day = as_of or local_today(timezone_name)
    _, end_exclusive = local_date_bounds_utc(day, day, timezone_name)
    return day, await build_operational_ledger(
        session,
        company_id=company_id,
        end_exclusive=end_exclusive,
    )


@router.get("/chart-of-accounts", response_model=list[AccountDTO])
async def chart_of_accounts(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[AccountDTO]:
    rows = (
        await session.execute(
            select(Account)
            .where(Account.company_id == tenant.company_id)
            .order_by(Account.code)
        )
    ).scalars().all()
    return [
        AccountDTO(
            id=row.id,
            code=row.code,
            name=row.name,
            type=row.type,
            normal_side=row.normal_side,
            is_active=row.is_active,
        )
        for row in rows
    ]


@router.get("/trial-balance", response_model=TrialBalanceDTO)
async def trial_balance(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    as_of: date | None = None,
) -> TrialBalanceDTO:
    day, ledger = await _as_of_ledger(session, tenant.company_id, as_of)
    lines = _trial_balance_lines(ledger)
    total_debit = sum(line.debit_minor for line in lines)
    total_credit = sum(line.credit_minor for line in lines)
    return TrialBalanceDTO(
        as_of=day,
        lines=lines,
        total_debit_minor=total_debit,
        total_credit_minor=total_credit,
        is_balanced=total_debit == total_credit,
    )


@router.get("/balance-sheet", response_model=BalanceSheetDTO)
async def balance_sheet(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    as_of: date | None = None,
) -> BalanceSheetDTO:
    day, ledger = await _as_of_ledger(session, tenant.company_id, as_of)
    trial_lines = _trial_balance_lines(ledger)

    asset_lines = [
        {"code": line.account_code, "name": line.account_name, "amount_minor": line.balance_minor}
        for line in trial_lines
        if line.account_type == "asset" and line.balance_minor
    ]
    liability_lines = [
        {"code": line.account_code, "name": line.account_name, "amount_minor": line.balance_minor}
        for line in trial_lines
        if line.account_type == "liability" and line.balance_minor
    ]
    equity_lines = [
        {"code": line.account_code, "name": line.account_name, "amount_minor": line.balance_minor}
        for line in trial_lines
        if line.account_type == "equity" and line.balance_minor
    ]
    retained_earnings = sum(
        line.balance_minor for line in trial_lines if line.account_type == "revenue"
    ) - sum(
        line.balance_minor for line in trial_lines if line.account_type == "expense"
    )
    if retained_earnings:
        equity_lines.append(
            {"code": "3100", "name": "Retained Earnings", "amount_minor": retained_earnings}
        )

    assets_total = sum(int(line["amount_minor"]) for line in asset_lines)
    liabilities_total = sum(int(line["amount_minor"]) for line in liability_lines)
    equity_total = sum(int(line["amount_minor"]) for line in equity_lines)
    return BalanceSheetDTO(
        as_of=day,
        assets=BalanceSheetSectionDTO(
            section="Assets", lines=asset_lines, total_minor=assets_total
        ),
        liabilities=BalanceSheetSectionDTO(
            section="Liabilities", lines=liability_lines, total_minor=liabilities_total
        ),
        equity=BalanceSheetSectionDTO(
            section="Equity", lines=equity_lines, total_minor=equity_total
        ),
        is_balanced=assets_total == liabilities_total + equity_total,
    )


@router.get("/general-ledger", response_model=list[GeneralLedgerEntryDTO])
async def general_ledger(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    limit: int = Query(default=500, ge=1, le=2000),
) -> list[GeneralLedgerEntryDTO]:
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    start_day = from_date or today
    end_day = to_date or today
    if end_day < start_day:
        raise BusinessRuleError("to_date must be on or after from_date")
    start_at, end_exclusive = local_date_bounds_utc(
        start_day, end_day, timezone_name
    )
    ledger = await build_operational_ledger(
        session,
        company_id=tenant.company_id,
        start_at=start_at,
        end_exclusive=end_exclusive,
    )
    return [
        GeneralLedgerEntryDTO(
            occurred_at=line.occurred_at,
            ref_type=line.ref_type,
            ref_id=line.ref_id,
            account_code=line.account_code,
            account_name=line.account_name,
            debit_minor=line.debit_minor,
            credit_minor=line.credit_minor,
            memo=line.memo,
        )
        for line in ledger[:limit]
    ]
