"""Finance endpoints — expenses, partners, capital, P&L.

The historic Reports module handles the heavy daily/monthly P&L numbers via
its own aggregator. This module focuses on the write-paths (record expense,
record capital movement, register partner) plus list/read views the Finance
screen needs.
"""

from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import select, func

from app.core.db import SessionDep
from app.core.errors import NotFoundError, BusinessRuleError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import (
    Asset, Branch, CapitalEntry, Expense, ExpenseCategory, OcrExtraction,
    OcrUpload, Partner, Supplier, User,
)

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class ExpenseCreate(BaseModel):
    branch_id: UUID
    category_id: UUID
    supplier_id: UUID | None = None
    amount_minor: int = Field(gt=0)
    paid_via: Literal["cash", "card", "bank", "upi"]
    paid_at: datetime
    vendor_name: str | None = Field(default=None, max_length=200)
    invoice_no: str | None = Field(default=None, max_length=100)
    note: str | None = Field(default=None, max_length=500)
    ocr_extraction_id: UUID | None = None


class ExpenseRead(BaseModel):
    id: UUID
    branch_id: UUID
    category_id: UUID
    supplier_id: UUID | None
    amount_minor: int
    paid_via: str
    paid_at: datetime
    vendor_name: str | None
    invoice_no: str | None
    note: str | None


class ExpenseUpdate(BaseModel):
    category_id: UUID | None = None
    amount_minor: int | None = Field(default=None, gt=0)
    paid_via: Literal["cash", "card", "bank", "upi"] | None = None
    paid_at: datetime | None = None
    vendor_name: str | None = Field(default=None, max_length=200)
    invoice_no: str | None = Field(default=None, max_length=100)
    note: str | None = Field(default=None, max_length=500)


class PartnerRead(BaseModel):
    id: UUID
    name: str
    share_pct: float
    joined_at: datetime
    notes: str | None = None
    capital_balance_minor: int  # Computed = sum(invest) - sum(withdraw) + sum(profit_share)


class PartnerCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    share_pct: float = Field(gt=0, le=100)
    joined_at: datetime
    user_id: UUID | None = None
    notes: str | None = Field(default=None, max_length=500)


class PartnerUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    share_pct: float | None = Field(default=None, gt=0, le=100)
    notes: str | None = Field(default=None, max_length=500)


class CapitalEntryCreate(BaseModel):
    partner_id: UUID
    type: Literal["invest", "withdraw", "profit_share"]
    amount_minor: int = Field(gt=0)
    effective_at: datetime
    note: str | None = Field(default=None, max_length=500)


class CapitalEntryRead(BaseModel):
    id: UUID
    partner_id: UUID
    type: str
    amount_minor: int
    effective_at: datetime
    note: str | None


class PLReport(BaseModel):
    period_start: date
    period_end: date
    revenue_minor: int
    cogs_minor: int
    gross_profit_minor: int
    expenses_minor: int
    net_profit_minor: int


class AssetRead(BaseModel):
    id: UUID
    name: str
    type: str
    purchase_minor: int
    purchase_date: datetime
    useful_life_months: int


class AssetCreate(BaseModel):
    branch_id: UUID
    name: str = Field(min_length=1, max_length=200)
    type: str = Field(min_length=1, max_length=50)
    purchase_minor: int = Field(gt=0)
    purchase_date: datetime
    useful_life_months: int = Field(default=60, gt=0, le=1200)
    salvage_minor: int = Field(default=0, ge=0)


async def _validate_expense_references(
    session,
    *,
    company_id: UUID,
    branch_id: UUID,
    category_id: UUID,
    supplier_id: UUID | None = None,
    ocr_extraction_id: UUID | None = None,
) -> None:
    branch = await session.get(Branch, branch_id)
    if not branch or branch.company_id != company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    category = await session.get(ExpenseCategory, category_id)
    if not category or category.company_id != company_id:
        raise NotFoundError("expense category not found")
    if supplier_id is not None:
        supplier = await session.get(Supplier, supplier_id)
        if not supplier or supplier.company_id != company_id or supplier.deleted_at:
            raise NotFoundError("supplier not found")
    if ocr_extraction_id is not None:
        extraction = (
            await session.execute(
                select(OcrExtraction)
                .join(OcrUpload, OcrUpload.id == OcrExtraction.ocr_upload_id)
                .where(
                    OcrExtraction.id == ocr_extraction_id,
                    OcrUpload.company_id == company_id,
                )
            )
        ).scalar_one_or_none()
        if not extraction:
            raise NotFoundError("OCR extraction not found")


# ============================================================================
# EXPENSES
# ============================================================================
@router.get("/expenses", response_model=list[ExpenseRead])
async def list_expenses(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    from_date: date | None = None,
    to_date: date | None = None,
) -> list[ExpenseRead]:
    stmt = select(Expense).where(
        Expense.company_id == tenant.company_id,
        Expense.deleted_at.is_(None),
    )
    if from_date:
        stmt = stmt.where(Expense.paid_at >= datetime(from_date.year, from_date.month, from_date.day, tzinfo=timezone.utc))
    if to_date:
        from datetime import time
        stmt = stmt.where(Expense.paid_at <= datetime.combine(to_date, time.max, tzinfo=timezone.utc))
    stmt = stmt.order_by(Expense.paid_at.desc())
    rows = (await session.execute(stmt)).scalars().all()
    return [
        ExpenseRead(
            id=r.id, branch_id=r.branch_id, category_id=r.category_id,
            supplier_id=r.supplier_id, amount_minor=r.amount_minor,
            paid_via=r.paid_via, paid_at=r.paid_at, vendor_name=r.vendor_name,
            invoice_no=r.invoice_no, note=r.note,
        )
        for r in rows
    ]


@router.post("/expenses", response_model=ExpenseRead, status_code=status.HTTP_201_CREATED)
async def create_expense(
    payload: ExpenseCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseRead:
    await _validate_expense_references(
        session,
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        category_id=payload.category_id,
        supplier_id=payload.supplier_id,
        ocr_extraction_id=payload.ocr_extraction_id,
    )
    ex = Expense(
        id=uuid4(),
        company_id=tenant.company_id,
        **payload.model_dump(),
    )
    session.add(ex)
    await session.flush()
    return ExpenseRead(
        id=ex.id, branch_id=ex.branch_id, category_id=ex.category_id,
        supplier_id=ex.supplier_id, amount_minor=ex.amount_minor,
        paid_via=ex.paid_via, paid_at=ex.paid_at, vendor_name=ex.vendor_name,
        invoice_no=ex.invoice_no, note=ex.note,
    )


@router.patch("/expenses/{expense_id}", response_model=ExpenseRead)
async def update_expense(
    expense_id: UUID,
    payload: ExpenseUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseRead:
    ex = await session.get(Expense, expense_id)
    if not ex or ex.company_id != tenant.company_id or ex.deleted_at:
        raise NotFoundError("expense not found")
    next_category_id = payload.category_id or ex.category_id
    await _validate_expense_references(
        session,
        company_id=tenant.company_id,
        branch_id=ex.branch_id,
        category_id=next_category_id,
        supplier_id=ex.supplier_id,
        ocr_extraction_id=ex.ocr_extraction_id,
    )
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(ex, f, v)
    await session.flush()
    return ExpenseRead(
        id=ex.id, branch_id=ex.branch_id, category_id=ex.category_id,
        supplier_id=ex.supplier_id, amount_minor=ex.amount_minor,
        paid_via=ex.paid_via, paid_at=ex.paid_at, vendor_name=ex.vendor_name,
        invoice_no=ex.invoice_no, note=ex.note,
    )


@router.delete("/expenses/{expense_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_expense(
    expense_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
):
    ex = await session.get(Expense, expense_id)
    if not ex or ex.company_id != tenant.company_id or ex.deleted_at:
        raise NotFoundError("expense not found")
    ex.deleted_at = datetime.now(timezone.utc)
    await session.flush()


# ============================================================================
# PARTNERS
# ============================================================================
async def _partner_balance(session, partner_id: UUID) -> int:
    """Net capital balance for a partner = +invest -withdraw +profit_share."""
    rows = (
        await session.execute(
            select(CapitalEntry.type, func.sum(CapitalEntry.amount_minor))
            .where(CapitalEntry.partner_id == partner_id)
            .group_by(CapitalEntry.type)
        )
    ).all()
    bal = 0
    for typ, total in rows:
        total = int(total or 0)
        if typ == "withdraw":
            bal -= total
        else:  # invest, profit_share
            bal += total
    return bal


@router.get("/partners", response_model=list[PartnerRead])
async def list_partners(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[PartnerRead]:
    rows = (
        await session.execute(
            select(Partner).where(Partner.company_id == tenant.company_id)
        )
    ).scalars().all()
    out: list[PartnerRead] = []
    for p in rows:
        out.append(
            PartnerRead(
                id=p.id, name=p.name, share_pct=float(p.share_pct),
                joined_at=p.joined_at, notes=p.notes,
                capital_balance_minor=await _partner_balance(session, p.id),
            )
        )
    return out


@router.post("/partners", response_model=PartnerRead, status_code=status.HTTP_201_CREATED)
async def create_partner(
    payload: PartnerCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.partner.write")),
) -> PartnerRead:
    if payload.user_id is not None:
        user = await session.get(User, payload.user_id)
        if not user or user.company_id != tenant.company_id or user.deleted_at:
            raise NotFoundError("user not found")
    p = Partner(
        id=uuid4(),
        company_id=tenant.company_id,
        **payload.model_dump(),
    )
    session.add(p)
    await session.flush()
    return PartnerRead(
        id=p.id, name=p.name, share_pct=float(p.share_pct), joined_at=p.joined_at,
        notes=p.notes, capital_balance_minor=0,
    )


@router.patch("/partners/{partner_id}", response_model=PartnerRead)
async def update_partner(
    partner_id: UUID,
    payload: PartnerUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.partner.write")),
) -> PartnerRead:
    p = await session.get(Partner, partner_id)
    if not p or p.company_id != tenant.company_id:
        raise NotFoundError("partner not found")
    for f, v in payload.model_dump(exclude_unset=True).items():
        setattr(p, f, v)
    await session.flush()
    return PartnerRead(
        id=p.id, name=p.name, share_pct=float(p.share_pct), joined_at=p.joined_at,
        notes=p.notes,
        capital_balance_minor=await _partner_balance(session, p.id),
    )


@router.get("/partners/{partner_id}/capital", response_model=list[CapitalEntryRead])
async def list_capital_entries(
    partner_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[CapitalEntryRead]:
    p = await session.get(Partner, partner_id)
    if not p or p.company_id != tenant.company_id:
        raise NotFoundError("partner not found")
    rows = (
        await session.execute(
            select(CapitalEntry)
            .where(CapitalEntry.partner_id == partner_id)
            .order_by(CapitalEntry.effective_at.desc())
        )
    ).scalars().all()
    return [
        CapitalEntryRead(
            id=r.id, partner_id=r.partner_id, type=r.type,
            amount_minor=r.amount_minor, effective_at=r.effective_at, note=r.note,
        )
        for r in rows
    ]


@router.post(
    "/capital-entries",
    response_model=CapitalEntryRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_capital_entry(
    payload: CapitalEntryCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.partner.write")),
) -> CapitalEntryRead:
    p = await session.get(Partner, payload.partner_id)
    if not p or p.company_id != tenant.company_id:
        raise NotFoundError("partner not found")
    ce = CapitalEntry(
        id=uuid4(),
        partner_id=payload.partner_id,
        type=payload.type,
        amount_minor=payload.amount_minor,
        effective_at=payload.effective_at,
        note=payload.note,
    )
    session.add(ce)
    await session.flush()
    return CapitalEntryRead(
        id=ce.id, partner_id=ce.partner_id, type=ce.type,
        amount_minor=ce.amount_minor, effective_at=ce.effective_at, note=ce.note,
    )


# ============================================================================
# ASSETS (fixed assets register — PS5s, TVs, projector, espresso machine, etc.)
# ============================================================================
@router.get("/assets", response_model=list[AssetRead])
async def list_assets(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[AssetRead]:
    rows = (
        await session.execute(
            select(Asset).where(
                Asset.company_id == tenant.company_id,
                Asset.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    return [
        AssetRead(
            id=r.id, name=r.name, type=r.type,
            purchase_minor=r.purchase_minor, purchase_date=r.purchase_date,
            useful_life_months=r.useful_life_months,
        )
        for r in rows
    ]


@router.post("/assets", response_model=AssetRead, status_code=status.HTTP_201_CREATED)
async def create_asset(
    payload: AssetCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> AssetRead:
    branch = await session.get(Branch, payload.branch_id)
    if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    if payload.salvage_minor > payload.purchase_minor:
        raise BusinessRuleError("salvage value cannot exceed purchase value")
    a = Asset(
        id=uuid4(),
        company_id=tenant.company_id,
        **payload.model_dump(),
    )
    session.add(a)
    await session.flush()
    return AssetRead(
        id=a.id, name=a.name, type=a.type,
        purchase_minor=a.purchase_minor, purchase_date=a.purchase_date,
        useful_life_months=a.useful_life_months,
    )


# ============================================================================
# Legacy P&L stub kept for backward compat (real numbers come from /reports/*)
# ============================================================================
@router.get("/pnl", response_model=PLReport)
async def profit_loss(
    session: SessionDep,
    period_start: date | None = None,
    period_end: date | None = None,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> PLReport:
    """Returns the legacy P&L stub, defaulting to the current month."""
    today = date.today()
    period_start = period_start or today.replace(day=1)
    period_end = period_end or today
    if period_end < period_start:
        raise BusinessRuleError("period_end must be on or after period_start")
    return PLReport(
        period_start=period_start, period_end=period_end,
        revenue_minor=0, cogs_minor=0, gross_profit_minor=0,
        expenses_minor=0, net_profit_minor=0,
    )
