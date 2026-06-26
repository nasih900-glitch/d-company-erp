"""Settings endpoints — company profile, branches, terminals, expense categories.

Single-tenant in practice (you run for one D Company), but everything respects
company_id from the JWT so multi-company is plug-and-play.
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import NotFoundError, ConflictError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import Branch, Company, ExpenseCategory, Terminal

router = APIRouter()


# ---------------------------------------------------------------- DTOs
class CompanyRead(BaseModel):
    id: UUID
    name: str
    legal_name: str | None
    currency: str
    timezone: str
    country: str | None
    gstin: str | None
    pan: str | None
    gst_registration_type: str
    is_composition: bool
    e_invoicing_enabled: bool
    fiscal_year_start_month: int
    google_sheets_webhook_url: str | None


class CompanyUpdate(BaseModel):
    name: str | None = None
    legal_name: str | None = None
    timezone: str | None = None
    gstin: str | None = None
    pan: str | None = None
    gst_registration_type: str | None = None
    is_composition: bool | None = None
    e_invoicing_enabled: bool | None = None
    google_sheets_webhook_url: str | None = None


class BranchRead(BaseModel):
    id: UUID
    name: str
    code: str | None
    address: str | None
    timezone: str | None
    opens_at: str | None
    closes_at: str | None
    state_code: str | None
    fssai_license_no: str | None
    trade_license_no: str | None
    branch_gstin: str | None


class BranchCreate(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    code: str | None = None
    address: str | None = None
    timezone: str | None = "Asia/Kolkata"
    opens_at: str | None = None
    closes_at: str | None = None
    state_code: str | None = "32"
    fssai_license_no: str | None = None
    trade_license_no: str | None = None
    branch_gstin: str | None = None


class BranchUpdate(BaseModel):
    name: str | None = None
    code: str | None = None
    address: str | None = None
    timezone: str | None = None
    opens_at: str | None = None
    closes_at: str | None = None
    state_code: str | None = None
    fssai_license_no: str | None = None
    trade_license_no: str | None = None
    branch_gstin: str | None = None


class TerminalRead(BaseModel):
    id: UUID
    branch_id: UUID
    name: str
    device_id: str | None
    last_seen_at: datetime | None


class TerminalCreate(BaseModel):
    branch_id: UUID
    name: str = Field(min_length=1, max_length=100)
    device_id: str | None = None


class ExpenseCategoryRead(BaseModel):
    id: UUID
    name: str
    code: str | None = None


class ExpenseCategoryCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    code: str | None = None


# ============================================================================
# COMPANY
# ============================================================================
@router.get("/company", response_model=CompanyRead)
async def get_company(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> CompanyRead:
    c = await session.get(Company, tenant.company_id)
    if not c:
        raise NotFoundError("company not found")
    return CompanyRead(
        id=c.id, name=c.name, legal_name=c.legal_name, currency=c.currency,
        timezone=c.timezone, country=c.country, gstin=c.gstin, pan=c.pan,
        gst_registration_type=c.gst_registration_type, is_composition=c.is_composition,
        e_invoicing_enabled=c.e_invoicing_enabled,
        fiscal_year_start_month=c.fiscal_year_start_month,
        google_sheets_webhook_url=c.google_sheets_webhook_url,
    )


@router.patch("/company", response_model=CompanyRead)
async def update_company(
    payload: CompanyUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> CompanyRead:
    c = await session.get(Company, tenant.company_id)
    if not c:
        raise NotFoundError("company not found")
    for f in ("name", "legal_name", "timezone", "gstin", "pan",
              "gst_registration_type", "is_composition",
              "e_invoicing_enabled", "google_sheets_webhook_url"):
        v = getattr(payload, f)
        if v is not None:
            setattr(c, f, v)
    await session.flush()
    return CompanyRead(
        id=c.id, name=c.name, legal_name=c.legal_name, currency=c.currency,
        timezone=c.timezone, country=c.country, gstin=c.gstin, pan=c.pan,
        gst_registration_type=c.gst_registration_type, is_composition=c.is_composition,
        e_invoicing_enabled=c.e_invoicing_enabled,
        fiscal_year_start_month=c.fiscal_year_start_month,
        google_sheets_webhook_url=c.google_sheets_webhook_url,
    )


# ============================================================================
# BRANCHES
# ============================================================================
@router.get("/branches", response_model=list[BranchRead])
async def list_branches(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> list[BranchRead]:
    rows = (
        await session.execute(
            select(Branch).where(
                Branch.company_id == tenant.company_id, Branch.deleted_at.is_(None)
            )
        )
    ).scalars().all()
    return [
        BranchRead(
            id=r.id, name=r.name, code=r.code, address=r.address,
            timezone=r.timezone, opens_at=r.opens_at, closes_at=r.closes_at,
            state_code=r.state_code, fssai_license_no=r.fssai_license_no,
            trade_license_no=r.trade_license_no, branch_gstin=r.branch_gstin,
        )
        for r in rows
    ]


@router.post("/branches", response_model=BranchRead, status_code=status.HTTP_201_CREATED)
async def create_branch(
    payload: BranchCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> BranchRead:
    b = Branch(
        id=uuid4(),
        company_id=tenant.company_id,
        **payload.model_dump(),
    )
    session.add(b)
    await session.flush()
    return BranchRead(
        id=b.id, name=b.name, code=b.code, address=b.address,
        timezone=b.timezone, opens_at=b.opens_at, closes_at=b.closes_at,
        state_code=b.state_code, fssai_license_no=b.fssai_license_no,
        trade_license_no=b.trade_license_no, branch_gstin=b.branch_gstin,
    )


@router.patch("/branches/{branch_id}", response_model=BranchRead)
async def update_branch(
    branch_id: UUID,
    payload: BranchUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> BranchRead:
    b = await session.get(Branch, branch_id)
    if not b or b.company_id != tenant.company_id or b.deleted_at:
        raise NotFoundError("branch not found")
    for f in payload.model_fields.keys():
        v = getattr(payload, f)
        if v is not None:
            setattr(b, f, v)
    await session.flush()
    return BranchRead(
        id=b.id, name=b.name, code=b.code, address=b.address,
        timezone=b.timezone, opens_at=b.opens_at, closes_at=b.closes_at,
        state_code=b.state_code, fssai_license_no=b.fssai_license_no,
        trade_license_no=b.trade_license_no, branch_gstin=b.branch_gstin,
    )


@router.delete("/branches/{branch_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_branch(
    branch_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
):
    b = await session.get(Branch, branch_id)
    if not b or b.company_id != tenant.company_id or b.deleted_at:
        raise NotFoundError("branch not found")
    b.deleted_at = datetime.now(timezone.utc)
    await session.flush()


# ============================================================================
# TERMINALS
# ============================================================================
@router.get("/terminals", response_model=list[TerminalRead])
async def list_terminals(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    branch_id: UUID | None = None,
) -> list[TerminalRead]:
    stmt = (
        select(Terminal)
        .join(Branch, Branch.id == Terminal.branch_id)
        .where(Branch.company_id == tenant.company_id)
    )
    if branch_id:
        stmt = stmt.where(Terminal.branch_id == branch_id)
    rows = (await session.execute(stmt)).scalars().all()
    return [
        TerminalRead(
            id=r.id, branch_id=r.branch_id, name=r.name,
            device_id=r.device_id, last_seen_at=r.last_seen_at,
        )
        for r in rows
    ]


@router.post("/terminals", response_model=TerminalRead, status_code=status.HTTP_201_CREATED)
async def create_terminal(
    payload: TerminalCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
) -> TerminalRead:
    b = await session.get(Branch, payload.branch_id)
    if not b or b.company_id != tenant.company_id:
        raise NotFoundError("branch not found")
    t = Terminal(
        id=uuid4(),
        branch_id=payload.branch_id,
        name=payload.name,
        device_id=payload.device_id,
    )
    session.add(t)
    await session.flush()
    return TerminalRead(
        id=t.id, branch_id=t.branch_id, name=t.name,
        device_id=t.device_id, last_seen_at=t.last_seen_at,
    )


@router.delete("/terminals/{terminal_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_terminal(
    terminal_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("admin.system")),
):
    t = await session.get(Terminal, terminal_id)
    if not t:
        raise NotFoundError("terminal not found")
    b = await session.get(Branch, t.branch_id)
    if not b or b.company_id != tenant.company_id:
        raise NotFoundError("terminal not found")
    await session.delete(t)
    await session.flush()


# ============================================================================
# EXPENSE CATEGORIES (needed for Add Expense form)
# ============================================================================
@router.get("/expense-categories", response_model=list[ExpenseCategoryRead])
async def list_expense_categories(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[ExpenseCategoryRead]:
    rows = (
        await session.execute(
            select(ExpenseCategory).where(ExpenseCategory.company_id == tenant.company_id)
        )
    ).scalars().all()
    return [
        ExpenseCategoryRead(id=r.id, name=r.name, code=getattr(r, "code", None))
        for r in rows
    ]


@router.post(
    "/expense-categories",
    response_model=ExpenseCategoryRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_expense_category(
    payload: ExpenseCategoryCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseCategoryRead:
    c = ExpenseCategory(
        id=uuid4(),
        company_id=tenant.company_id,
        name=payload.name,
        code=payload.code,
    )
    session.add(c)
    await session.flush()
    return ExpenseCategoryRead(id=c.id, name=c.name, code=getattr(c, "code", None))
