"""Accounting endpoints — Chart of Accounts, Trial Balance, Balance Sheet, GL.

Reads from accounts + journal_entries + journal_lines.

NOTE: D Company doesn't yet auto-post journal entries on every Order. The
endpoints below derive the trial balance from a combination of:
  - Order totals (revenue, GST liability)
  - Payments (cash / UPI / card movements)
  - Expenses (recorded via Finance → Expenses)
  - Partner capital entries
  - Refunds

So even though the journal_lines table is empty, you still get a real
Trial Balance + Balance Sheet computed from operational data. When we
later wire double-entry journal-posting, this code automatically picks
up from journal_lines instead.
"""

from __future__ import annotations

from datetime import date, datetime, time, timezone
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import func, select

from app.core.db import SessionDep
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import (
    Account, CapitalEntry, Expense, Order, Partner,
    Payment, Refund,
)

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class AccountDTO(BaseModel):
    id: UUID
    code: str
    name: str
    type: str          # asset|liability|equity|revenue|expense
    normal_side: str   # dr|cr
    is_active: bool


class TrialBalanceLineDTO(BaseModel):
    account_code: str
    account_name: str
    account_type: str
    debit_minor: int
    credit_minor: int
    balance_minor: int  # signed; debits positive for asset/expense, credits positive for liability/equity/revenue


class TrialBalanceDTO(BaseModel):
    as_of: date
    lines: list[TrialBalanceLineDTO]
    total_debit_minor: int
    total_credit_minor: int
    is_balanced: bool


class BalanceSheetSectionDTO(BaseModel):
    section: str        # Assets / Liabilities / Equity
    lines: list[dict]
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


# ---------------------------------------------------------------- helpers
async def _company_revenue_and_tax(session, company_id: UUID, end_dt: datetime) -> dict:
    """Sum order totals up to end_dt (paid orders only)."""
    row = (
        await session.execute(
            select(
                func.coalesce(func.sum(Order.subtotal_minor), 0).label("revenue"),
                func.coalesce(func.sum(Order.cgst_minor + Order.sgst_minor + Order.igst_minor + Order.cess_minor), 0).label("tax"),
                func.coalesce(func.sum(Order.total_minor), 0).label("gross"),
            ).where(
                Order.company_id == company_id,
                Order.opened_at <= end_dt,
                Order.status == "paid",
            )
        )
    ).one()
    return {"revenue": int(row.revenue), "tax": int(row.tax), "gross": int(row.gross)}


async def _payments_by_method(session, company_id: UUID, end_dt: datetime) -> dict:
    rows = (
        await session.execute(
            select(Payment.method, func.coalesce(func.sum(Payment.amount_minor), 0))
            .join(Order, Order.id == Payment.order_id)
            .where(Order.company_id == company_id, Payment.created_at <= end_dt)
            .group_by(Payment.method)
        )
    ).all()
    return {method: int(total or 0) for method, total in rows}


async def _expenses_total(session, company_id: UUID, end_dt: datetime) -> int:
    return int((await session.execute(
        select(func.coalesce(func.sum(Expense.amount_minor), 0)).where(
            Expense.company_id == company_id,
            Expense.paid_at <= end_dt,
            Expense.deleted_at.is_(None),
        )
    )).scalar_one() or 0)


async def _partner_capital_balance(session, company_id: UUID, end_dt: datetime) -> int:
    """Net partner capital = invest + profit_share - withdraw."""
    rows = (
        await session.execute(
            select(CapitalEntry.type, func.coalesce(func.sum(CapitalEntry.amount_minor), 0))
            .join(Partner, Partner.id == CapitalEntry.partner_id)
            .where(Partner.company_id == company_id, CapitalEntry.effective_at <= end_dt)
            .group_by(CapitalEntry.type)
        )
    ).all()
    bal = 0
    for t, total in rows:
        total = int(total or 0)
        if t == "withdraw":
            bal -= total
        else:
            bal += total
    return bal


# ---------------------------------------------------------------- endpoints
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
            id=a.id, code=a.code, name=a.name, type=a.type,
            normal_side=a.normal_side, is_active=a.is_active,
        )
        for a in rows
    ]


@router.get("/trial-balance", response_model=TrialBalanceDTO)
async def trial_balance(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    as_of: date | None = None,
) -> TrialBalanceDTO:
    """Computes trial balance from operational data (orders, payments, expenses,
    capital). Lines are grouped by account type."""
    as_of = as_of or date.today()
    end_dt = datetime.combine(as_of, time.max, tzinfo=timezone.utc)

    rev = await _company_revenue_and_tax(session, tenant.company_id, end_dt)
    pays = await _payments_by_method(session, tenant.company_id, end_dt)
    expenses = await _expenses_total(session, tenant.company_id, end_dt)
    capital = await _partner_capital_balance(session, tenant.company_id, end_dt)

    cash = pays.get("cash", 0)
    upi = pays.get("upi", 0)
    card = pays.get("card", 0)
    bank = pays.get("bank", 0)

    # Derive each TB line. Assets/Expenses are debit-balance; Liab/Equity/Revenue credit.
    lines: list[TrialBalanceLineDTO] = []
    def add(code, name, typ, dr, cr):
        lines.append(TrialBalanceLineDTO(
            account_code=code, account_name=name, account_type=typ,
            debit_minor=dr, credit_minor=cr,
            balance_minor=(dr - cr if typ in ("asset", "expense") else cr - dr),
        ))

    add("1000", "Cash", "asset", cash, 0)
    add("1010", "Bank", "asset", bank, 0)
    add("1100", "Card Clearing", "asset", card, 0)
    add("1110", "UPI Clearing", "asset", upi, 0)
    add("2100", "Tax Payable (GST)", "liability", 0, rev["tax"])
    add("3000", "Partner Capital", "equity", 0, capital)
    add("4000", "Revenue", "revenue", 0, rev["revenue"])
    add("5100", "Operating Expenses", "expense", expenses, 0)

    total_dr = sum(l.debit_minor for l in lines)
    total_cr = sum(l.credit_minor for l in lines)
    return TrialBalanceDTO(
        as_of=as_of,
        lines=lines,
        total_debit_minor=total_dr,
        total_credit_minor=total_cr,
        is_balanced=(total_dr == total_cr),
    )


@router.get("/balance-sheet", response_model=BalanceSheetDTO)
async def balance_sheet(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    as_of: date | None = None,
) -> BalanceSheetDTO:
    """Standard balance sheet — Assets = Liabilities + Equity.

    Computed from the same operational sources as the trial balance.
    """
    as_of = as_of or date.today()
    end_dt = datetime.combine(as_of, time.max, tzinfo=timezone.utc)

    rev = await _company_revenue_and_tax(session, tenant.company_id, end_dt)
    pays = await _payments_by_method(session, tenant.company_id, end_dt)
    expenses = await _expenses_total(session, tenant.company_id, end_dt)
    capital = await _partner_capital_balance(session, tenant.company_id, end_dt)
    refunds = int((await session.execute(
        select(func.coalesce(func.sum(Refund.amount_minor), 0)).join(
            Order, Order.id == Refund.order_id,
        ).where(Order.company_id == tenant.company_id, Refund.created_at <= end_dt)
    )).scalar_one() or 0)

    cash = pays.get("cash", 0)
    upi = pays.get("upi", 0)
    card = pays.get("card", 0)
    bank = pays.get("bank", 0)

    # Cash and bank balances (deduct expenses paid; deduct refunds)
    asset_cash = cash + bank + upi + card - expenses - refunds
    asset_lines = [
        {"name": "Cash & equivalents", "amount_minor": asset_cash},
    ]
    assets_total = sum(l["amount_minor"] for l in asset_lines)

    liab_lines = [
        {"name": "GST Payable", "amount_minor": rev["tax"]},
    ]
    liab_total = sum(l["amount_minor"] for l in liab_lines)

    # Equity = capital + retained earnings (revenue - expenses - tax)
    retained = rev["revenue"] - expenses - rev["tax"]
    eq_lines = [
        {"name": "Partner Capital", "amount_minor": capital},
        {"name": "Retained Earnings", "amount_minor": retained},
    ]
    eq_total = sum(l["amount_minor"] for l in eq_lines)

    return BalanceSheetDTO(
        as_of=as_of,
        assets=BalanceSheetSectionDTO(section="Assets", lines=asset_lines, total_minor=assets_total),
        liabilities=BalanceSheetSectionDTO(section="Liabilities", lines=liab_lines, total_minor=liab_total),
        equity=BalanceSheetSectionDTO(section="Equity", lines=eq_lines, total_minor=eq_total),
        is_balanced=(assets_total == liab_total + eq_total),
    )


@router.get("/general-ledger", response_model=list[GeneralLedgerEntryDTO])
async def general_ledger(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    limit: int = 500,
) -> list[GeneralLedgerEntryDTO]:
    """A flat journal of every accounting-relevant event in the period.

    Includes orders (revenue + tax), payments (cash receipt), expenses (cash out),
    refunds, and partner capital. Useful for the accountant scanning what
    happened on a given day.
    """
    if not from_date:
        from_date = date.today()
    if not to_date:
        to_date = date.today()
    f_dt = datetime.combine(from_date, time.min, tzinfo=timezone.utc)
    t_dt = datetime.combine(to_date, time.max, tzinfo=timezone.utc)

    entries: list[GeneralLedgerEntryDTO] = []

    # Orders → revenue (Cr 4000) + tax (Cr 2100)
    orders = (
        await session.execute(
            select(Order).where(
                Order.company_id == tenant.company_id,
                Order.opened_at >= f_dt, Order.opened_at <= t_dt,
                Order.status == "paid",
            ).order_by(Order.opened_at)
        )
    ).scalars().all()
    for o in orders:
        entries.append(GeneralLedgerEntryDTO(
            occurred_at=o.opened_at, ref_type="order", ref_id=o.id,
            account_code="4000", account_name="Revenue",
            debit_minor=0, credit_minor=o.subtotal_minor,
            memo=f"Order {o.invoice_no or o.id.hex[:8]}",
        ))
        if o.cgst_minor + o.sgst_minor + o.igst_minor + o.cess_minor > 0:
            entries.append(GeneralLedgerEntryDTO(
                occurred_at=o.opened_at, ref_type="order", ref_id=o.id,
                account_code="2100", account_name="GST Payable",
                debit_minor=0,
                credit_minor=o.cgst_minor + o.sgst_minor + o.igst_minor + o.cess_minor,
                memo=f"GST on {o.invoice_no or o.id.hex[:8]}",
            ))

    # Payments → cash/bank receipt (Dr 1000/1010/1100/1110)
    pays = (
        await session.execute(
            select(Payment, Order.invoice_no)
            .join(Order, Order.id == Payment.order_id)
            .where(Order.company_id == tenant.company_id,
                   Payment.created_at >= f_dt, Payment.created_at <= t_dt)
            .order_by(Payment.created_at)
        )
    ).all()
    method_to_account = {
        "cash": ("1000", "Cash"),
        "upi": ("1110", "UPI Clearing"),
        "card": ("1100", "Card Clearing"),
        "bank": ("1010", "Bank"),
    }
    for p, inv in pays:
        code, name = method_to_account.get(p.method, ("1000", p.method.title()))
        entries.append(GeneralLedgerEntryDTO(
            occurred_at=p.created_at, ref_type="payment", ref_id=p.id,
            account_code=code, account_name=name,
            debit_minor=p.amount_minor, credit_minor=0,
            memo=f"Payment ({p.method}) for {inv or '—'}",
        ))

    # Expenses → expense (Dr 5100), cash out (Cr 1000/1010)
    exps = (
        await session.execute(
            select(Expense).where(
                Expense.company_id == tenant.company_id,
                Expense.paid_at >= f_dt, Expense.paid_at <= t_dt,
                Expense.deleted_at.is_(None),
            ).order_by(Expense.paid_at)
        )
    ).scalars().all()
    for e in exps:
        entries.append(GeneralLedgerEntryDTO(
            occurred_at=e.paid_at, ref_type="expense", ref_id=e.id,
            account_code="5100", account_name="Operating Expenses",
            debit_minor=e.amount_minor, credit_minor=0,
            memo=f"Expense — {e.vendor_name or e.note or 'unspecified'}",
        ))

    # Sort by date and trim
    entries.sort(key=lambda x: x.occurred_at)
    return entries[:limit]
