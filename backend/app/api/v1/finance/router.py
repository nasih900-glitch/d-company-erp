"""Finance endpoints — expenses, partners, capital, P&L.

The historic Reports module handles the heavy daily/monthly P&L numbers via
its own aggregator. This module focuses on the write-paths (record expense,
record capital movement, register partner) plus list/read views the Finance
screen needs.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Query, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.orm import aliased

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.money import apportion
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import (
    Asset,
    Branch,
    CapitalEntry,
    Company,
    Customer,
    CustomerMembership,
    Expense,
    ExpenseCategory,
    ManualCollection,
    MembershipTier,
    OcrExtraction,
    OcrUpload,
    Partner,
    Supplier,
    User,
)
from app.services.accounting import build_operational_ledger
from app.services.accounting.depreciation import (
    STRAIGHT_LINE,
    asset_accumulated_depreciation_minor,
    asset_book_value_minor,
)
from app.services.reports import ReportsAggregator
from app.services.reports.business_metrics import (
    compute_business_metrics,
    compute_distributable_capacity,
)

router = APIRouter()

# How many months of typical running costs must stay in the business before
# anything is "safe to distribute" — see GET /finance/distributable. Chosen
# deliberately conservative: this is a young business (operating since
# 2025-11) with real fixed costs and capital tied up in equipment/stock, not
# an established one with proven cash flow, so 6 months rather than the
# 3-month minimum sometimes cited for a mature business.
DISTRIBUTION_RESERVE_MONTHS = 6
# Balance-sheet accounts that represent money the business can actually hand
# out today — excludes inventory, fixed assets, and pending-settlement
# clearing accounts, which are real value but not spendable cash.
LIQUID_CASH_ACCOUNT_CODES = frozenset({"1000", "1010", "1110"})  # Cash, Bank, UPI/QR Clearing


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


class ManualCollectionCreate(BaseModel):
    branch_id: UUID
    business_date: date
    method: Literal["cash", "upi", "card", "bank"]
    amount_minor: int = Field(gt=0)
    # `legacy_daily` is reserved for the guarded reconciliation script.  The
    # public API must not let a normal write impersonate audited legacy data.
    source_kind: Literal["manual_daily"] = "manual_daily"
    source_ref: str = Field(min_length=1, max_length=160)
    note: str | None = Field(default=None, max_length=500)


class ManualCollectionVoid(BaseModel):
    reason: str = Field(min_length=3, max_length=500)


class ManualCollectionRead(BaseModel):
    id: UUID
    company_id: UUID
    branch_id: UUID
    business_date: date
    method: str
    amount_minor: int
    source_kind: str
    source_ref: str
    note: str | None
    idempotency_key: str
    created_by: UUID
    created_by_name: str | None = None
    created_at: datetime
    voided_at: datetime | None
    voided_by: UUID | None
    voided_by_name: str | None = None
    void_reason: str | None
    is_voided: bool


class PartnerRead(BaseModel):
    id: UUID
    name: str
    share_pct: float
    joined_at: datetime
    notes: str | None = None
    capital_balance_minor: int  # Computed = sum(invest) - sum(withdraw)


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
    type: Literal["invest", "withdraw"]
    amount_minor: int = Field(gt=0)
    effective_at: datetime
    settlement_account: Literal["cash", "bank", "upi"] = "bank"
    source_ref: str = Field(min_length=1, max_length=160)
    note: str | None = Field(default=None, max_length=500)


class CapitalEntryVoid(BaseModel):
    reason: str = Field(min_length=3, max_length=500)


class CapitalEntryRead(BaseModel):
    id: UUID
    partner_id: UUID
    type: str
    amount_minor: int
    effective_at: datetime
    settlement_account: str
    source_ref: str | None
    note: str | None
    created_by: UUID | None
    created_by_name: str | None = None
    created_at: datetime
    voided_at: datetime | None
    voided_by: UUID | None
    voided_by_name: str | None = None
    void_reason: str | None
    is_voided: bool


class PLReport(BaseModel):
    period_start: date
    period_end: date
    revenue_minor: int
    cogs_minor: int
    gross_profit_minor: int
    expenses_minor: int
    depreciation_minor: int
    net_profit_minor: int


class PartnerProfitShare(BaseModel):
    partner_id: UUID
    name: str
    share_pct: float
    capital_balance_minor: int  # invest - withdraw, all-time
    profit_share_minor: int  # this period's net profit, split by share_pct


class PartnerPLReport(BaseModel):
    period_start: date
    period_end: date
    net_profit_minor: int
    partners: list[PartnerProfitShare]


class DistributablePartnerShare(BaseModel):
    partner_id: UUID
    name: str
    share_pct: float
    capital_balance_minor: int  # invest - withdraw, all-time (unchanged by this report)
    lifetime_withdrawn_minor: int  # withdrawals only, all-time
    distributable_share_minor: int  # this partner's slice of safe_to_distribute_minor


class DistributableProfitReport(BaseModel):
    """How much the partners can safely take out right now, not just this period's paper profit.

    Two independent caps, the SAFE number is whichever is smaller:
      - profit-based: all-time real profit, minus everything ever withdrawn, minus a reserve
      - cash-based: actual cash/bank/UPI balance on hand, minus the same reserve
    The cash cap exists because profit on paper and cash in hand aren't the
    same thing once money is tied up in stock or equipment — a partner should
    never be told they can withdraw money that doesn't actually exist as cash.
    """

    as_of: date
    lifetime_net_profit_minor: int  # already net of straight-line depreciation, see lifetime_depreciation_minor
    lifetime_depreciation_minor: int  # equipment wear-and-tear already charged against lifetime_net_profit_minor above
    lifetime_withdrawn_minor: int
    reserve_months: int
    avg_monthly_cost_minor: int  # trailing 90-day average of (cost of goods sold + running expenses)
    reserve_minor: int
    liquid_cash_minor: int  # cash + bank + UPI/QR clearing, right now
    profit_based_capacity_minor: int
    cash_based_capacity_minor: int
    safe_to_distribute_minor: int
    partners: list[DistributablePartnerShare]


class BusinessMetricsRead(BaseModel):
    period_start: date
    period_end: date
    aov_minor: int
    orders_count: int
    mrr_minor: int
    arr_minor: int
    active_members_count: int
    cac_minor: int | None
    new_customers_count: int
    marketing_spend_minor: int
    ltv_minor: int
    customers_count: int
    burn_rate_minor: int


class AssetRead(BaseModel):
    id: UUID
    name: str
    type: str
    purchase_minor: int
    purchase_date: datetime
    useful_life_months: int
    salvage_minor: int
    depreciation_method: str
    # Derived, recomputed live as of "now" every time this is read — never
    # stored. See app/services/accounting/depreciation.py.
    accumulated_depreciation_minor: int
    book_value_minor: int


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
    timezone_name = await company_timezone(session, tenant.company_id)
    if from_date:
        from_at, _ = local_date_bounds_utc(from_date, from_date, timezone_name)
        stmt = stmt.where(Expense.paid_at >= from_at)
    if to_date:
        _, to_exclusive = local_date_bounds_utc(to_date, to_date, timezone_name)
        stmt = stmt.where(Expense.paid_at < to_exclusive)
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
    if not tenant.in_branch(payload.branch_id):
        raise NotFoundError("branch not found")
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
# MANUAL COLLECTIONS
# ============================================================================
def _require_manual_collection_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required for manual collection writes"
        )
    return str(key), str(request_hash)


def _manual_collection_read(
    row: ManualCollection,
    *,
    created_by_name: str | None = None,
    voided_by_name: str | None = None,
) -> ManualCollectionRead:
    return ManualCollectionRead(
        id=row.id,
        company_id=row.company_id,
        branch_id=row.branch_id,
        business_date=row.business_date,
        method=row.method,
        amount_minor=int(row.amount_minor),
        source_kind=row.source_kind,
        source_ref=row.source_ref,
        note=row.note,
        idempotency_key=row.idempotency_key,
        created_by=row.created_by,
        created_by_name=created_by_name,
        created_at=row.created_at,
        voided_at=row.voided_at,
        voided_by=row.voided_by,
        voided_by_name=voided_by_name,
        void_reason=row.void_reason,
        is_voided=row.voided_at is not None,
    )


@router.get("/manual-collections", response_model=list[ManualCollectionRead])
async def list_manual_collections(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    branch_id: UUID | None = None,
    include_voided: bool = True,
    limit: int = Query(default=200, ge=1, le=500),
) -> list[ManualCollectionRead]:
    """List auditable off-POS collections by their local business date."""
    if from_date and to_date and to_date < from_date:
        raise BusinessRuleError("to_date must be on or after from_date")

    scoped_branch_id = branch_id or tenant.branch_id
    if scoped_branch_id is not None:
        if not tenant.in_branch(scoped_branch_id):
            raise NotFoundError("branch not found")
        branch = await session.get(Branch, scoped_branch_id)
        if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
            raise NotFoundError("branch not found")

    creator = aliased(User)
    voider = aliased(User)
    stmt = (
        select(ManualCollection, creator.name, voider.name)
        .outerjoin(creator, creator.id == ManualCollection.created_by)
        .outerjoin(voider, voider.id == ManualCollection.voided_by)
        .where(ManualCollection.company_id == tenant.company_id)
    )
    if scoped_branch_id is not None:
        stmt = stmt.where(ManualCollection.branch_id == scoped_branch_id)
    if from_date is not None:
        stmt = stmt.where(ManualCollection.business_date >= from_date)
    if to_date is not None:
        stmt = stmt.where(ManualCollection.business_date <= to_date)
    if not include_voided:
        stmt = stmt.where(ManualCollection.voided_at.is_(None))
    stmt = stmt.order_by(
        ManualCollection.business_date.desc(),
        ManualCollection.created_at.desc(),
        ManualCollection.id.desc(),
    ).limit(limit)

    rows = (await session.execute(stmt)).all()
    return [
        _manual_collection_read(
            row,
            created_by_name=created_by_name,
            voided_by_name=voided_by_name,
        )
        for row, created_by_name, voided_by_name in rows
    ]


@router.post(
    "/manual-collections",
    response_model=ManualCollectionRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_manual_collection(
    payload: ManualCollectionCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ManualCollectionRead:
    """Record tax-unsplit daily revenue that bypassed itemized POS billing.

    This is intentionally unavailable to GST-registered companies: an
    aggregate payment-method total cannot establish taxable value, tax rate,
    HSN/SAC, or invoice-level GST.  Such companies must enter itemized sales.
    """
    idempotency_key, request_hash = _require_manual_collection_idempotency(request)
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        return ManualCollectionRead.model_validate(replay["body"])

    if not tenant.in_branch(payload.branch_id):
        raise NotFoundError("branch not found")

    company = await session.get(Company, tenant.company_id)
    if not company or company.deleted_at:
        raise NotFoundError("company not found")
    if (company.gst_registration_type or "regular") != "unregistered":
        raise BusinessRuleError(
            "Manual aggregate collections are disabled for GST-registered "
            "companies; record an itemized tax-safe sale instead."
        )
    timezone_name = await company_timezone(session, tenant.company_id)
    if payload.business_date > local_today(timezone_name):
        raise BusinessRuleError("business_date cannot be in the future")

    # Locking the branch serializes absent-source checks.  This produces a
    # friendly conflict instead of leaking a database IntegrityError when two
    # devices submit the same daily source concurrently.
    branch = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == payload.branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not branch:
        raise NotFoundError("branch not found")

    source_ref = payload.source_ref.strip()
    if not source_ref:
        raise BusinessRuleError("source_ref must not be blank")
    note = payload.note.strip() if payload.note and payload.note.strip() else None
    existing_source = (
        await session.execute(
            select(ManualCollection.id).where(
                ManualCollection.company_id == tenant.company_id,
                ManualCollection.branch_id == payload.branch_id,
                ManualCollection.source_kind == payload.source_kind,
                ManualCollection.source_ref == source_ref,
                ManualCollection.method == payload.method,
            )
        )
    ).scalar_one_or_none()
    if existing_source is not None:
        raise ConflictError(
            "A manual collection already exists for this source and payment method",
            details={"manual_collection_id": str(existing_source)},
        )

    row = ManualCollection(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        business_date=payload.business_date,
        method=payload.method,
        amount_minor=payload.amount_minor,
        source_kind=payload.source_kind,
        source_ref=source_ref,
        note=note,
        idempotency_key=idempotency_key,
        created_by=tenant.user_id,
    )
    session.add(row)
    await session.flush()
    creator = await session.get(User, tenant.user_id)
    response = _manual_collection_read(
        row,
        created_by_name=creator.name if creator else None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/manual-collections/{collection_id}/void",
    response_model=ManualCollectionRead,
)
async def void_manual_collection(
    collection_id: UUID,
    payload: ManualCollectionVoid,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ManualCollectionRead:
    """Void a collection without deleting or overwriting its original data."""
    row = (
        await session.execute(
            select(ManualCollection)
            .where(
                ManualCollection.id == collection_id,
                ManualCollection.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not row or not tenant.in_branch(row.branch_id):
        raise NotFoundError("manual collection not found")

    reason = payload.reason.strip()
    if len(reason) < 3:
        raise BusinessRuleError("void reason must contain at least 3 characters")
    if row.voided_at is not None:
        if row.void_reason != reason:
            raise BusinessRuleError(
                "manual collection is already voided with a different reason"
            )
    else:
        row.voided_at = datetime.now(timezone.utc)
        row.voided_by = tenant.user_id
        row.void_reason = reason
        await session.flush()

    creator = await session.get(User, row.created_by)
    voider = await session.get(User, row.voided_by) if row.voided_by else None
    return _manual_collection_read(
        row,
        created_by_name=creator.name if creator else None,
        voided_by_name=voider.name if voider else None,
    )


# ============================================================================
# PARTNERS
# ============================================================================
async def _partner_balance(session, partner_id: UUID) -> int:
    """Outstanding contributed capital = investments minus repayments."""
    rows = (
        await session.execute(
            select(CapitalEntry.type, func.sum(CapitalEntry.amount_minor))
            .where(
                CapitalEntry.partner_id == partner_id,
                CapitalEntry.type.in_(("invest", "withdraw")),
                CapitalEntry.voided_at.is_(None),
            )
            .group_by(CapitalEntry.type)
        )
    ).all()
    bal = 0
    for typ, total in rows:
        total = int(total or 0)
        if typ == "invest":
            bal += total
        elif typ == "withdraw":
            bal -= total
    return bal


def _capital_entry_read(
    row: CapitalEntry,
    *,
    created_by_name: str | None = None,
    voided_by_name: str | None = None,
) -> CapitalEntryRead:
    return CapitalEntryRead(
        id=row.id,
        partner_id=row.partner_id,
        type=row.type,
        amount_minor=int(row.amount_minor),
        effective_at=row.effective_at,
        settlement_account=row.settlement_account,
        source_ref=row.source_ref,
        note=row.note,
        created_by=row.created_by,
        created_by_name=created_by_name,
        created_at=row.created_at,
        voided_at=row.voided_at,
        voided_by=row.voided_by,
        voided_by_name=voided_by_name,
        void_reason=row.void_reason,
        is_voided=row.voided_at is not None,
    )


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
    include_voided: bool = True,
) -> list[CapitalEntryRead]:
    p = await session.get(Partner, partner_id)
    if not p or p.company_id != tenant.company_id:
        raise NotFoundError("partner not found")
    creator = aliased(User)
    voider = aliased(User)
    stmt = (
        select(CapitalEntry, creator.name, voider.name)
        .outerjoin(creator, creator.id == CapitalEntry.created_by)
        .outerjoin(voider, voider.id == CapitalEntry.voided_by)
        .where(
            CapitalEntry.partner_id == partner_id,
            CapitalEntry.type.in_(("invest", "withdraw")),
        )
    )
    if not include_voided:
        stmt = stmt.where(CapitalEntry.voided_at.is_(None))
    rows = (
        await session.execute(
            stmt.order_by(CapitalEntry.effective_at.desc(), CapitalEntry.id.desc())
        )
    ).all()
    return [
        _capital_entry_read(
            row,
            created_by_name=created_by_name,
            voided_by_name=voided_by_name,
        )
        for row, created_by_name, voided_by_name in rows
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
    p = (
        await session.execute(
            select(Partner)
            .where(
                Partner.id == payload.partner_id,
                Partner.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not p:
        raise NotFoundError("partner not found")

    source_ref = payload.source_ref.strip()
    if not source_ref:
        raise BusinessRuleError("source_ref must not be blank")
    existing_source = (
        await session.execute(
            select(CapitalEntry.id).where(CapitalEntry.source_ref == source_ref)
        )
    ).scalar_one_or_none()
    if existing_source is not None:
        raise ConflictError(
            "A capital entry already exists for this source",
            details={"capital_entry_id": str(existing_source)},
        )

    note = payload.note.strip() if payload.note and payload.note.strip() else None
    ce = CapitalEntry(
        id=uuid4(),
        partner_id=payload.partner_id,
        type=payload.type,
        amount_minor=payload.amount_minor,
        effective_at=payload.effective_at,
        settlement_account=payload.settlement_account,
        source_ref=source_ref,
        note=note,
        created_by=tenant.user_id,
    )
    session.add(ce)
    await session.flush()
    creator = await session.get(User, tenant.user_id)
    return _capital_entry_read(
        ce,
        created_by_name=creator.name if creator else None,
    )


@router.post(
    "/capital-entries/{entry_id}/void",
    response_model=CapitalEntryRead,
)
async def void_capital_entry(
    entry_id: UUID,
    payload: CapitalEntryVoid,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.partner.write")),
) -> CapitalEntryRead:
    """Void an investment/withdrawal without destroying its audit trail."""
    row = (
        await session.execute(
            select(CapitalEntry)
            .join(Partner, Partner.id == CapitalEntry.partner_id)
            .where(
                CapitalEntry.id == entry_id,
                CapitalEntry.type.in_(("invest", "withdraw")),
                Partner.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not row:
        raise NotFoundError("capital entry not found")

    reason = payload.reason.strip()
    if len(reason) < 3:
        raise BusinessRuleError("void reason must contain at least 3 characters")
    if row.voided_at is not None:
        if row.void_reason != reason:
            raise BusinessRuleError(
                "capital entry is already voided with a different reason"
            )
    else:
        row.voided_at = datetime.now(timezone.utc)
        row.voided_by = tenant.user_id
        row.void_reason = reason
        await session.flush()

    creator = await session.get(User, row.created_by) if row.created_by else None
    voider = await session.get(User, row.voided_by) if row.voided_by else None
    return _capital_entry_read(
        row,
        created_by_name=creator.name if creator else None,
        voided_by_name=voider.name if voider else None,
    )


# ============================================================================
# ASSETS (fixed assets register — PS5s, TVs, projector, espresso machine, etc.)
# ============================================================================
def _asset_read(a: Asset, *, as_of: datetime) -> AssetRead:
    return AssetRead(
        id=a.id, name=a.name, type=a.type,
        purchase_minor=a.purchase_minor, purchase_date=a.purchase_date,
        useful_life_months=a.useful_life_months,
        salvage_minor=a.salvage_minor,
        depreciation_method=a.depreciation_method,
        accumulated_depreciation_minor=asset_accumulated_depreciation_minor(a, as_of),
        book_value_minor=asset_book_value_minor(a, as_of),
    )


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
    now = datetime.now(timezone.utc)
    return [_asset_read(r, as_of=now) for r in rows]


@router.post("/assets", response_model=AssetRead, status_code=status.HTTP_201_CREATED)
async def create_asset(
    payload: AssetCreate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> AssetRead:
    if not tenant.in_branch(payload.branch_id):
        raise NotFoundError("branch not found")
    branch = await session.get(Branch, payload.branch_id)
    if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    if payload.salvage_minor > payload.purchase_minor:
        raise BusinessRuleError("salvage value cannot exceed purchase value")
    a = Asset(
        id=uuid4(),
        company_id=tenant.company_id,
        # Only straight_line is computable today (see
        # app/services/accounting/depreciation.py); the create form has no
        # method picker yet, so every asset is explicitly straight_line
        # rather than relying on the column's implicit default.
        depreciation_method=STRAIGHT_LINE,
        **payload.model_dump(),
    )
    session.add(a)
    await session.flush()
    return _asset_read(a, as_of=datetime.now(timezone.utc))


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
    """Return the same operational P&L used by Reports and Analytics."""
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    period_start = period_start or today.replace(day=1)
    period_end = period_end or today
    if period_end < period_start:
        raise BusinessRuleError("period_end must be on or after period_start")
    report = await ReportsAggregator(session).aggregate(
        company_id=tenant.company_id,
        period_start=period_start,
        period_end=period_end,
        period="custom",
        label=f"{period_start.isoformat()} to {period_end.isoformat()}",
    )
    return PLReport(
        period_start=period_start,
        period_end=period_end,
        revenue_minor=report.net_revenue_minor,
        cogs_minor=report.cogs_minor,
        gross_profit_minor=report.gross_profit_minor,
        expenses_minor=report.expense_total_minor,
        depreciation_minor=report.depreciation_minor,
        net_profit_minor=report.net_profit_minor,
    )


@router.get("/pnl/partners", response_model=PartnerPLReport)
async def partner_profit_split(
    session: SessionDep,
    period_start: date | None = None,
    period_end: date | None = None,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> PartnerPLReport:
    """Split the same operational net profit across partners by share_pct.

    Net profit only — construction/setup money the partners put in lives in
    CapitalEntry (type=invest), never in Expense, so it never depresses this
    number. A negative net_profit_minor still splits by share_pct (each
    partner absorbs their share of a loss), same formula either way.
    """
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    period_start = period_start or today.replace(day=1)
    period_end = period_end or today
    if period_end < period_start:
        raise BusinessRuleError("period_end must be on or after period_start")
    report = await ReportsAggregator(session).aggregate(
        company_id=tenant.company_id,
        period_start=period_start,
        period_end=period_end,
        period="custom",
        label=f"{period_start.isoformat()} to {period_end.isoformat()}",
    )
    partners = (
        await session.execute(
            select(Partner)
            .where(Partner.company_id == tenant.company_id)
            .order_by(Partner.name)
        )
    ).scalars().all()
    shares = apportion(
        report.net_profit_minor, [float(p.share_pct) for p in partners]
    )
    return PartnerPLReport(
        period_start=period_start,
        period_end=period_end,
        net_profit_minor=report.net_profit_minor,
        partners=[
            PartnerProfitShare(
                partner_id=p.id,
                name=p.name,
                share_pct=float(p.share_pct),
                capital_balance_minor=await _partner_balance(session, p.id),
                profit_share_minor=share,
            )
            for p, share in zip(partners, shares, strict=True)
        ],
    )


@router.get("/distributable", response_model=DistributableProfitReport)
async def distributable_profit(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> DistributableProfitReport:
    """How much can the partners safely take out right now, all-time.

    Unlike /pnl/partners (one period's paper profit split by share), this
    looks at the whole history: real operating profit since inception, minus
    every withdrawal ever taken, minus a reserve sized off real recent
    running costs — then capped by actual liquid cash on hand, since profit
    on the books and cash in the bank are not the same thing once money is
    tied up in stock or equipment.
    """
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    # A fixed early sentinel date reliably predates any real company's first
    # transaction, giving an effective "since inception" lifetime window
    # without a separate query for the company's actual founding date.
    since = date(2000, 1, 1)

    lifetime = await ReportsAggregator(session).aggregate(
        company_id=tenant.company_id,
        period_start=since,
        period_end=today,
        period="custom",
        label=f"{since.isoformat()} to {today.isoformat()}",
    )

    trailing_start = today - timedelta(days=90)
    trailing = await ReportsAggregator(session).aggregate(
        company_id=tenant.company_id,
        period_start=trailing_start,
        period_end=today,
        period="custom",
        label="trailing 90 days",
    )
    avg_monthly_cost_minor = (trailing.cogs_minor + trailing.expense_total_minor) // 3
    reserve_minor = avg_monthly_cost_minor * DISTRIBUTION_RESERVE_MONTHS

    partners = (
        await session.execute(
            select(Partner).where(Partner.company_id == tenant.company_id).order_by(Partner.name)
        )
    ).scalars().all()

    withdrawn_rows = (
        await session.execute(
            select(CapitalEntry.partner_id, func.coalesce(func.sum(CapitalEntry.amount_minor), 0))
            .join(Partner, Partner.id == CapitalEntry.partner_id)
            .where(
                Partner.company_id == tenant.company_id,
                CapitalEntry.type == "withdraw",
                CapitalEntry.voided_at.is_(None),
            )
            .group_by(CapitalEntry.partner_id)
        )
    ).all()
    withdrawn_by_partner = {partner_id: int(total) for partner_id, total in withdrawn_rows}
    lifetime_withdrawn_minor = sum(withdrawn_by_partner.values())

    _, end_exclusive = local_date_bounds_utc(today, today, timezone_name)
    ledger_lines = await build_operational_ledger(
        session, company_id=tenant.company_id, end_exclusive=end_exclusive
    )
    liquid_cash_minor = sum(
        line.debit_minor - line.credit_minor
        for line in ledger_lines
        if line.account_code in LIQUID_CASH_ACCOUNT_CODES
    )

    capacity = compute_distributable_capacity(
        lifetime_net_profit_minor=lifetime.net_profit_minor,
        lifetime_withdrawn_minor=lifetime_withdrawn_minor,
        avg_monthly_cost_minor=avg_monthly_cost_minor,
        reserve_months=DISTRIBUTION_RESERVE_MONTHS,
        liquid_cash_minor=liquid_cash_minor,
    )
    shares = apportion(capacity.safe_to_distribute_minor, [float(p.share_pct) for p in partners])

    return DistributableProfitReport(
        as_of=today,
        lifetime_net_profit_minor=lifetime.net_profit_minor,
        lifetime_depreciation_minor=lifetime.depreciation_minor,
        lifetime_withdrawn_minor=lifetime_withdrawn_minor,
        reserve_months=DISTRIBUTION_RESERVE_MONTHS,
        avg_monthly_cost_minor=avg_monthly_cost_minor,
        reserve_minor=capacity.reserve_minor,
        liquid_cash_minor=liquid_cash_minor,
        profit_based_capacity_minor=capacity.profit_based_capacity_minor,
        cash_based_capacity_minor=capacity.cash_based_capacity_minor,
        safe_to_distribute_minor=capacity.safe_to_distribute_minor,
        partners=[
            DistributablePartnerShare(
                partner_id=p.id,
                name=p.name,
                share_pct=float(p.share_pct),
                capital_balance_minor=await _partner_balance(session, p.id),
                lifetime_withdrawn_minor=withdrawn_by_partner.get(p.id, 0),
                distributable_share_minor=share,
            )
            for p, share in zip(partners, shares, strict=True)
        ],
    )


@router.get("/metrics", response_model=BusinessMetricsRead)
async def business_metrics(
    session: SessionDep,
    period_start: date | None = None,
    period_end: date | None = None,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> BusinessMetricsRead:
    """AOV, MRR/ARR, CAC, LTV, burn rate — the metrics that actually fit a
    single-location, self-funded gaming café. See business_metrics.py for
    why SaaS/VC-fundraising metrics (Rule of 40, North Star Metric, viral
    coefficient, TAM/SAM/SOM, vesting, SAFEs, ...) are deliberately absent.
    """
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    period_start = period_start or today.replace(day=1)
    period_end = period_end or today
    if period_end < period_start:
        raise BusinessRuleError("period_end must be on or after period_start")

    report = await ReportsAggregator(session).aggregate(
        company_id=tenant.company_id,
        period_start=period_start,
        period_end=period_end,
        period="custom",
        label=f"{period_start.isoformat()} to {period_end.isoformat()}",
    )

    # MRR/ARR is a snapshot of memberships active RIGHT NOW, not a period sum.
    now = datetime.now(timezone.utc)
    membership_rows = (
        await session.execute(
            select(
                CustomerMembership.billing_cycle,
                MembershipTier.monthly_price_minor,
                MembershipTier.annual_price_minor,
            )
            .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .where(
                Customer.company_id == tenant.company_id,
                Customer.deleted_at.is_(None),
                CustomerMembership.starts_at <= now,
                CustomerMembership.expires_at > now,
            )
        )
    ).all()
    active_members_count = len(membership_rows)
    mrr_total = 0
    for billing_cycle, monthly_price, annual_price in membership_rows:
        if billing_cycle == "annual" and annual_price:
            mrr_total += int(annual_price) // 12
        else:
            mrr_total += int(monthly_price or 0)

    period_start_at, period_end_exclusive = local_date_bounds_utc(
        period_start, period_end, timezone_name
    )
    marketing_spend_minor = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Expense.amount_minor), 0))
                .select_from(Expense)
                .join(ExpenseCategory, ExpenseCategory.id == Expense.category_id)
                .where(
                    Expense.company_id == tenant.company_id,
                    Expense.deleted_at.is_(None),
                    ExpenseCategory.name == "Marketing",
                    Expense.paid_at >= period_start_at,
                    Expense.paid_at < period_end_exclusive,
                )
            )
        ).scalar_one()
    )
    new_customers_count = int(
        (
            await session.execute(
                select(func.count(Customer.id)).where(
                    Customer.company_id == tenant.company_id,
                    Customer.deleted_at.is_(None),
                    Customer.first_visit_at >= period_start_at,
                    Customer.first_visit_at < period_end_exclusive,
                )
            )
        ).scalar_one()
    )

    # LTV is all-time by definition, never period-scoped.
    customers_count_raw, total_spend_raw = (
        await session.execute(
            select(
                func.count(Customer.id),
                func.coalesce(func.sum(Customer.total_spent_minor), 0),
            ).where(Customer.company_id == tenant.company_id, Customer.deleted_at.is_(None))
        )
    ).one()

    metrics = compute_business_metrics(
        net_revenue_minor=report.net_revenue_minor,
        orders_count=report.orders_count,
        net_profit_minor=report.net_profit_minor,
        active_membership_monthly_equivalent_minor=mrr_total,
        active_members_count=active_members_count,
        marketing_spend_minor=marketing_spend_minor,
        new_customers_count=new_customers_count,
        total_customer_spend_minor=int(total_spend_raw),
        customers_count=int(customers_count_raw),
    )
    return BusinessMetricsRead(
        period_start=period_start,
        period_end=period_end,
        # Manual collections have no order denominator and must never inflate
        # average order value. ReportsAggregator's average is order-only.
        aov_minor=report.avg_ticket_minor,
        orders_count=metrics.orders_count,
        mrr_minor=metrics.mrr_minor,
        arr_minor=metrics.arr_minor,
        active_members_count=metrics.active_members_count,
        cac_minor=metrics.cac_minor,
        new_customers_count=metrics.new_customers_count,
        marketing_spend_minor=metrics.marketing_spend_minor,
        ltv_minor=metrics.ltv_minor,
        customers_count=metrics.customers_count,
        burn_rate_minor=metrics.burn_rate_minor,
    )
