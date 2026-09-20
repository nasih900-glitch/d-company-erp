"""Finance endpoints — expenses, partners, capital, P&L.

The historic Reports module handles the heavy daily/monthly P&L numbers via
its own aggregator. This module focuses on the write-paths (record expense,
record capital movement, register partner) plus list/read views the Finance
screen needs.
"""

from __future__ import annotations

import json
import warnings
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from functools import partial
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from typing import Annotated, Any, Literal, cast
from urllib.parse import quote
from uuid import UUID, uuid4

from anyio import CapacityLimiter, to_thread
from fastapi import (
    APIRouter,
    BackgroundTasks,
    Depends,
    File,
    Form,
    Query,
    Request,
    UploadFile,
    status,
)
from fastapi.responses import Response
from PIL import Image, UnidentifiedImageError
from pydantic import AwareDatetime, BaseModel, Field
from sqlalchemy import exists, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import aliased, undefer

from app.core.db import SessionDep
from app.core.errors import (
    BusinessRuleError,
    ConflictError,
    ForbiddenError,
    IdempotencyConflict,
    NotFoundError,
)
from app.core.idempotency import check_or_reserve, store_response
from app.core.logging import get_logger
from app.core.middleware import parse_client_version_code
from app.core.money import apportion
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import (
    GRN,
    Asset,
    AuditLog,
    Branch,
    CapitalEntry,
    Company,
    Customer,
    CustomerMembership,
    Expense,
    ExpenseCategory,
    ExpenseReceipt,
    ExpenseReceiptReview,
    FinanceSourceCorrection,
    GRNLine,
    IdempotencyKey,
    JournalEntry,
    ManualCollection,
    MembershipPayment,
    OcrExtraction,
    OcrUpload,
    Order,
    Partner,
    PurchaseOrder,
    Refund,
    Shift,
    Supplier,
    SupplierPayment,
    TipPayout,
    User,
)
from app.services.accounting import LedgerLine, build_operational_ledger
from app.services.accounting.accounts import (
    ACCOUNT_BY_CODE,
    ACCOUNTS_PAYABLE,
    BANK,
    CASH,
    TIPS_PAYABLE,
)
from app.services.accounting.depreciation import (
    STRAIGHT_LINE,
    asset_accumulated_depreciation_minor,
    asset_book_value_minor,
)
from app.services.accounting.purchases import (
    post_two_sided_operational_journal,
    received_line_total_minor,
    require_invoice_matches_capitalised_total,
)
from app.services.integrations.google_sheets_mirror import (
    enqueue_google_sheets_event_if_enabled,
)
from app.services.reports import ReportsAggregator
from app.services.reports.aggregator import CostingConfidence, PnLReport
from app.services.reports.business_metrics import (
    compute_business_metrics,
    compute_distributable_capacity,
)

log = get_logger(__name__)

router = APIRouter()

# How many months of typical running costs must stay in the business before
# anything is "safe to distribute" — see GET /finance/distributable. Chosen
# deliberately conservative: this is a young business (operating since
# 2025-11) with real fixed costs and capital tied up in equipment/stock, not
# an established one with proven cash flow, so 6 months rather than the
# 3-month minimum sometimes cited for a mature business.
DISTRIBUTION_RESERVE_MONTHS = 6
# Only till cash and a posted bank balance are immediately spendable. Provider
# clearing accounts are receivables until an explicit settlement moves them to
# Bank; treating UPI/Card/Wallet clearing as cash available for partner draws
# can distribute money the business has not received yet.
SPENDABLE_CASH_ACCOUNT_CODES = frozenset({"1000", "1010"})
# Backwards-compatible headline used by older clients. It intentionally keeps
# the historical cash+bank+UPI definition, but is no longer used for the safe
# distribution decision. New clients must use ``spendable_cash_bank_minor``.
LEGACY_LIQUID_CASH_ACCOUNT_CODES = frozenset({"1000", "1010", "1110"})
SETTLEMENT_RECEIVABLE_ACCOUNT_CODES = frozenset({"1100", "1110", "1120", "1210"})
RECONCILIATION_ACCOUNT_CODES = frozenset({"1185", "1190"})
COMPANY_WIDE_FINANCE_ROLES = frozenset({"owner", "partner", "auditor"})
EXPENSE_CASH_PAID_OUT_UNAVAILABLE = (
    "Cash paid-outs cannot be recorded as ordinary Finance expenses yet because "
    "these entries are not linked to the open shift drawer. Use UPI, bank transfer, "
    "or business debit card. Cash paid-outs require the shift-linked drawer workflow."
)
CODE21_CASH_EXPENSE_VERSION = 21
SAVED_CLIENT_VERSION_CODE_HEADER = "X-Saved-Client-Version-Code"
CODE21_CASH_EXPENSE_RECEIPT_REVISION = 51
MODERN_CASH_EXPENSE_RECEIPT_REVISION = 52
CODE21_CASH_EXPENSE_CLOCK_WINDOW = timedelta(minutes=5)
MAX_EXPENSE_RECEIPT_BYTES = 10 * 1024 * 1024
MAX_EXPENSE_RECEIPTS = 5
_EXPENSE_RECEIPT_DECODER_LIMITER = CapacityLimiter(2)

ExpenseReceiptSource = Literal["camera", "gallery", "file"]
ExpenseReceiptStatus = Literal["pending", "verified", "not_required", "rejected"]


# ---------------------------------------------------------------- DTOs
class ExpenseCreate(BaseModel):
    branch_id: UUID
    shift_id: UUID | None = None
    category_id: UUID
    supplier_id: UUID | None = None
    amount_minor: int = Field(gt=0)
    paid_via: Literal["cash", "card", "bank", "upi"]
    paid_at: datetime
    vendor_name: str | None = Field(default=None, max_length=200)
    invoice_no: str | None = Field(default=None, max_length=100)
    note: str | None = Field(default=None, max_length=500)
    ocr_extraction_id: UUID | None = None


class FinanceSourceCorrectionCreate(BaseModel):
    settlement_shift_id: UUID
    reason: str = Field(min_length=3, max_length=500)


class FinanceSourceCorrectionRead(BaseModel):
    id: UUID
    source_type: str
    source_id: UUID
    original_shift_id: UUID
    settlement_shift_id: UUID
    amount_minor: int
    corrected_by: UUID
    reason: str
    corrected_at: datetime


class FinanceBranchRead(BaseModel):
    """Least-privilege branch reference used by finance entry forms."""

    id: UUID
    name: str
    code: str | None = None


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
    # Present for shift-linked cash-expense receipts (the Code 21 recovery and
    # the modern explicit-shift flow). They stay optional so cached responses
    # and older clients keep their existing wire shape/decoder compatibility.
    shift_id: UUID | None = None
    created_by: UUID | None = None
    voided_at: datetime | None = None
    voided_by: UUID | None = None
    void_reason: str | None = None
    is_voided: bool = False
    receipt_count: int = 0
    receipt_status: ExpenseReceiptStatus = "pending"
    source_shift_status: str | None = None
    is_corrected: bool = False
    correction: FinanceSourceCorrectionRead | None = None


class ExpenseActionReconciliationRead(BaseModel):
    """Authoritative metadata-only result for one saved offline expense action."""

    state: Literal["absent", "in_progress", "accepted"]
    idempotency_key: str
    expense: ExpenseRead | None = None


class ExpenseReceiptRead(BaseModel):
    id: UUID
    expense_id: UUID
    original_filename: str
    content_type: str
    size_bytes: int
    # Content identity lets an offline client reconcile a rejected local copy
    # against the authoritative receipt list without downloading private bytes.
    # Optional only when replaying a historical idempotency response captured
    # before this field existed. Fresh reads and uploads always include it.
    sha256: str | None = None
    source: ExpenseReceiptSource
    status: ExpenseReceiptStatus
    review_note: str | None
    created_at: datetime


class ExpenseReceiptReviewCreate(BaseModel):
    status: ExpenseReceiptStatus
    review_note: str | None = Field(default=None, max_length=500)


class ExpenseReceiptReviewRead(BaseModel):
    id: UUID
    expense_id: UUID
    status: ExpenseReceiptStatus
    review_note: str | None
    reviewed_by: UUID
    created_at: datetime


class ExpenseUpdate(BaseModel):
    category_id: UUID | None = None
    amount_minor: int | None = Field(default=None, gt=0)
    paid_via: Literal["cash", "card", "bank", "upi"] | None = None
    paid_at: datetime | None = None
    vendor_name: str | None = Field(default=None, max_length=200)
    invoice_no: str | None = Field(default=None, max_length=100)
    note: str | None = Field(default=None, max_length=500)


class ExpenseVoid(BaseModel):
    reason: str = Field(min_length=3, max_length=500)


class ManualCollectionCreate(BaseModel):
    branch_id: UUID
    shift_id: UUID | None = None
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
    shift_id: UUID | None = None
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
    source_shift_status: str | None = None
    is_corrected: bool = False
    correction: FinanceSourceCorrectionRead | None = None


class TipPayoutCreate(BaseModel):
    branch_id: UUID
    shift_id: UUID | None = None
    amount_minor: int = Field(gt=0)
    method: Literal["cash", "upi", "card", "bank"]
    paid_at: AwareDatetime
    note: str = Field(min_length=3, max_length=500)


class TipPayoutVoid(BaseModel):
    reason: str = Field(min_length=3, max_length=500)


class TipPayoutRead(BaseModel):
    id: UUID
    company_id: UUID
    branch_id: UUID
    shift_id: UUID | None = None
    amount_minor: int
    method: str
    paid_at: datetime
    note: str
    idempotency_key: str
    created_by: UUID
    created_by_name: str | None = None
    created_at: datetime
    voided_at: datetime | None
    voided_by: UUID | None
    voided_by_name: str | None = None
    void_reason: str | None
    is_voided: bool
    source_shift_status: str | None = None
    is_corrected: bool = False
    correction: FinanceSourceCorrectionRead | None = None


class SupplierPaymentCreate(BaseModel):
    branch_id: UUID
    shift_id: UUID | None = None
    supplier_id: UUID
    grn_id: UUID
    amount_minor: int = Field(gt=0)
    method: Literal["cash", "bank"]
    paid_at: datetime
    payment_reference: str = Field(min_length=1, max_length=160)
    note: str | None = Field(default=None, max_length=500)


class SupplierPaymentVoid(BaseModel):
    reason: str = Field(min_length=3, max_length=500)


class SupplierPaymentRead(BaseModel):
    id: UUID
    company_id: UUID
    branch_id: UUID
    shift_id: UUID | None = None
    supplier_id: UUID
    grn_id: UUID
    journal_entry_id: UUID
    amount_minor: int
    method: str
    paid_at: datetime
    payment_reference: str
    note: str | None
    idempotency_key: str
    created_by: UUID
    created_at: datetime
    voided_at: datetime | None
    voided_by: UUID | None
    void_reason: str | None
    is_voided: bool
    source_shift_status: str | None = None
    is_corrected: bool = False
    correction: FinanceSourceCorrectionRead | None = None


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
    accounting_basis: Literal["operational_receipt"] = "operational_receipt"
    period_start: date
    period_end: date
    revenue_minor: int
    # Named receipt stream so native Finance can explain a changed headline
    # instead of forcing owners to leave the screen and infer the source.
    memberships_minor: int
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
    # Compatibility value for pre-Code-24 clients. It is zero, never a guessed
    # allocation, while authoritative_profit_share_minor is unavailable.
    profit_share_minor: int
    authoritative_profit_share_minor: int | None = None


class AllocationConfidenceRead(BaseModel):
    """Whether a profit-derived partner allocation is safe to act on."""

    status: Literal["authoritative", "costing_incomplete", "costing_unavailable"]
    inventory_orders_checked: int
    inventory_lines_checked: int
    unresolved_order_count: int
    reason: str | None = None


class PartnerPLReport(BaseModel):
    period_start: date
    period_end: date
    net_profit_minor: int
    allocation_status: Literal["authoritative", "costing_incomplete", "costing_unavailable"]
    allocation_unavailable_reason: str | None = None
    costing_confidence: AllocationConfidenceRead
    partners: list[PartnerProfitShare]


class DistributablePartnerShare(BaseModel):
    partner_id: UUID
    name: str
    share_pct: float
    capital_balance_minor: int  # invest - withdraw, all-time (unchanged by this report)
    lifetime_withdrawn_minor: int  # withdrawals only, all-time
    # Compatibility value for old clients. It is zero when costing is not
    # authoritative; current clients use the nullable field below.
    distributable_share_minor: int
    authoritative_distributable_share_minor: int | None = None


class CashPositionRead(BaseModel):
    """Named ledger balances; clients must not infer spendability from rails."""

    cash_on_hand_minor: int
    bank_balance_minor: int
    spendable_cash_bank_minor: int
    card_clearing_minor: int
    upi_qr_clearing_minor: int
    wallet_clearing_minor: int
    pos_settlement_clearing_minor: int
    settlement_receivables_minor: int
    historical_funds_pending_reconciliation_minor: int
    unreconciled_settlement_minor: int
    reconciliation_only_minor: int


def _cash_position_from_ledger(
    ledger_lines: list[LedgerLine],
) -> tuple[CashPositionRead, int]:
    """Return authoritative spendable/clearing balances and the legacy total."""
    balances_by_code: dict[str, int] = {}
    for line in ledger_lines:
        balances_by_code[line.account_code] = (
            balances_by_code.get(line.account_code, 0)
            + line.debit_minor
            - line.credit_minor
        )

    def balance(account_code: str) -> int:
        return balances_by_code.get(account_code, 0)

    position = CashPositionRead(
        cash_on_hand_minor=balance("1000"),
        bank_balance_minor=balance("1010"),
        spendable_cash_bank_minor=sum(
            balance(code) for code in SPENDABLE_CASH_ACCOUNT_CODES
        ),
        card_clearing_minor=balance("1100"),
        upi_qr_clearing_minor=balance("1110"),
        wallet_clearing_minor=balance("1120"),
        pos_settlement_clearing_minor=balance("1210"),
        settlement_receivables_minor=sum(
            balance(code) for code in SETTLEMENT_RECEIVABLE_ACCOUNT_CODES
        ),
        historical_funds_pending_reconciliation_minor=balance("1185"),
        unreconciled_settlement_minor=balance("1190"),
        reconciliation_only_minor=sum(
            balance(code) for code in RECONCILIATION_ACCOUNT_CODES
        ),
    )
    legacy_liquid_cash_minor = sum(
        balance(code) for code in LEGACY_LIQUID_CASH_ACCOUNT_CODES
    )
    return position, legacy_liquid_cash_minor


def _allocation_confidence(report: PnLReport) -> AllocationConfidenceRead:
    confidence: CostingConfidence | None = report.costing_confidence
    if confidence is None:
        return AllocationConfidenceRead(
            status="costing_unavailable",
            inventory_orders_checked=0,
            inventory_lines_checked=0,
            unresolved_order_count=0,
            reason=(
                "Partner allocations are unavailable because historical product "
                "costing was not evaluated with this profit snapshot. Refresh Finance; "
                "if this persists, ask an owner to check the server update."
            ),
        )
    if confidence.is_authoritative:
        return AllocationConfidenceRead(
            status="authoritative",
            inventory_orders_checked=confidence.inventory_orders_checked,
            inventory_lines_checked=confidence.inventory_lines_checked,
            unresolved_order_count=0,
        )

    count = confidence.unresolved_order_count
    order_word = "order" if count == 1 else "orders"
    return AllocationConfidenceRead(
        status="costing_incomplete",
        inventory_orders_checked=confidence.inventory_orders_checked,
        inventory_lines_checked=confidence.inventory_lines_checked,
        unresolved_order_count=count,
        reason=(
            f"Partner allocations are unavailable because {count} paid or refunded "
            f"{order_word} containing food, drinks, or shisha cannot be reconciled "
            "to complete positive-cost inventory movements. Reconcile the product "
            "recipe and stock cost history, then refresh Finance."
        ),
    )


class DistributableProfitReport(BaseModel):
    """How much the partners can safely take out right now, not just this period's paper profit.

    Two independent caps, the SAFE number is whichever is smaller:
      - profit-based: all-time real profit, minus everything ever withdrawn, minus a reserve
      - cash-based: till cash plus posted bank balance, minus the same reserve
    The cash cap exists because profit on paper and cash in hand aren't the
    same thing once money is tied up in stock, equipment, or provider clearing — a partner should
    never be told they can withdraw money that doesn't actually exist as cash.
    """

    as_of: date
    lifetime_net_profit_minor: int  # already net of straight-line depreciation, see lifetime_depreciation_minor
    lifetime_depreciation_minor: int  # equipment wear-and-tear already charged against lifetime_net_profit_minor above
    lifetime_withdrawn_minor: int
    reserve_months: int
    avg_monthly_cost_minor: int  # trailing 90-day average of (cost of goods sold + running expenses)
    reserve_minor: int
    # Deprecated compatibility value: cash + bank + UPI/QR clearing. It is
    # disclosed but never used for safe distribution capacity.
    liquid_cash_minor: int
    spendable_cash_bank_minor: int
    cash_position: CashPositionRead
    profit_based_capacity_minor: int
    cash_based_capacity_minor: int
    # Compatibility value retained for signed Code 21. It is forced to zero
    # when a physical sale has unresolved COGS so old clients also fail closed.
    safe_to_distribute_minor: int
    authoritative_safe_to_distribute_minor: int | None = None
    allocation_status: Literal["authoritative", "costing_incomplete", "costing_unavailable"]
    allocation_unavailable_reason: str | None = None
    costing_confidence: AllocationConfidenceRead
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
    branch_id: UUID
    name: str
    type: str
    purchase_minor: int
    purchase_date: datetime
    useful_life_months: int
    salvage_minor: int
    depreciation_method: str
    notes: str | None
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
    notes: str | None = Field(default=None, max_length=500)


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
    canonical_account = ACCOUNT_BY_CODE.get(getattr(category, "code", None) or "")
    if canonical_account is not None and canonical_account.type != "expense":
        raise BusinessRuleError(
            f"expense category uses non-expense account code {canonical_account.code}"
        )
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
# REFERENCE DATA
# ============================================================================
def _scope_to_tenant_branch(
    stmt: Any,
    branch_column: Any,
    tenant: TenantContext,
) -> Any:
    """Apply the authenticated branch boundary to a finance read.

    Company scope alone is insufficient for branch-assigned managers: their
    role intentionally carries ``finance.read``, but ``TenantContext.branch_id``
    is still the row-level boundary used throughout the operational API.
    """

    if tenant.branch_id is not None:
        return stmt.where(branch_column == tenant.branch_id)
    return stmt


def _require_company_wide_finance(tenant: TenantContext, *, subject: str) -> None:
    """Reject branch-bound access to facts that have no branch attribution.

    Partner ownership, capital movements and distributable cash are company
    facts.  Pretending they are branch facts would either leak the other
    branches or produce a financially meaningless partial distribution.  A
    caller needs an owner, partner or auditor identity (or the protected-owner
    claim) to read or mutate them. Operational managers remain branch-scoped.
    """

    # Auth deliberately attaches a default operational branch even when a
    # UserRole has branch_id=NULL, so absence of a token branch is not a
    # usable company-scope signal. Use only server-issued, security-sensitive
    # identities here. Managers retain branch operational finance, but cannot
    # read ownership/capital/distribution facts from the whole company.
    if not (
        tenant.protected_access
        or COMPANY_WIDE_FINANCE_ROLES.intersection(tenant.roles)
    ):
        raise ForbiddenError(
            f"{subject} is company-wide and requires company-wide finance access"
        )


def _authoritative_partner_weights(partners: list[Partner]) -> list[Decimal]:
    """Return ownership weights only when their displayed percentages are true.

    ``apportion`` intentionally normalizes arbitrary weights. That behaviour is
    useful generally, but using it on incomplete/contradictory ``share_pct``
    rows would allocate 100% while presenting a different ownership total.
    Partner allocations therefore fail closed until configured shares total
    exactly 100%; partner setup/listing remains available for reconciliation.
    """

    weights = [Decimal(str(partner.share_pct)) for partner in partners]
    configured_total = sum(weights, start=Decimal(0))
    if not weights or configured_total != Decimal(100):
        configured = format(configured_total.normalize(), "f")
        raise BusinessRuleError(
            "Partner ownership shares total "
            f"{configured}%, not 100%. Owner reconciliation is required before "
            "profit or distribution allocations are authoritative."
        )
    return weights


@router.get("/branches", response_model=list[FinanceBranchRead])
async def list_finance_branches(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[FinanceBranchRead]:
    """Branches the caller may use on finance forms, without admin access."""

    stmt = select(Branch).where(
        Branch.company_id == tenant.company_id,
        Branch.deleted_at.is_(None),
    )
    stmt = _scope_to_tenant_branch(stmt, Branch.id, tenant)
    rows = (await session.execute(stmt.order_by(Branch.name))).scalars().all()
    return [FinanceBranchRead(id=row.id, name=row.name, code=row.code) for row in rows]


# ============================================================================
# EXPENSES
# ============================================================================
def _finance_correction_source_id(row: FinanceSourceCorrection) -> UUID:
    source_id = {
        "expense": row.expense_id,
        "manual_collection": row.manual_collection_id,
        "tip_payout": row.tip_payout_id,
        "supplier_payment": row.supplier_payment_id,
    }.get(row.source_type)
    if source_id is None:  # pragma: no cover - enforced by database constraints
        raise RuntimeError("finance correction is missing its source id")
    return source_id


def _finance_correction_read(
    row: FinanceSourceCorrection,
) -> FinanceSourceCorrectionRead:
    return FinanceSourceCorrectionRead(
        id=row.id,
        source_type=row.source_type,
        source_id=_finance_correction_source_id(row),
        original_shift_id=row.original_shift_id,
        settlement_shift_id=row.settlement_shift_id,
        amount_minor=int(row.amount_minor),
        corrected_by=row.corrected_by,
        reason=row.reason,
        corrected_at=row.corrected_at,
    )


async def _finance_source_context(
    session: SessionDep,
    *,
    source_type: Literal[
        "expense", "manual_collection", "tip_payout", "supplier_payment"
    ],
    source_ids: list[UUID],
    shift_ids: list[UUID | None],
) -> tuple[dict[UUID, FinanceSourceCorrection], dict[UUID, str]]:
    """Load correction and original-shift state in two bounded list queries."""

    if not source_ids:
        return {}, {}
    source_column = {
        "expense": FinanceSourceCorrection.expense_id,
        "manual_collection": FinanceSourceCorrection.manual_collection_id,
        "tip_payout": FinanceSourceCorrection.tip_payout_id,
        "supplier_payment": FinanceSourceCorrection.supplier_payment_id,
    }[source_type]
    corrections = (
        await session.execute(
            select(FinanceSourceCorrection).where(source_column.in_(source_ids))
        )
    ).scalars().all()
    correction_by_source = {
        _finance_correction_source_id(row): row for row in corrections
    }
    concrete_shift_ids = {shift_id for shift_id in shift_ids if shift_id is not None}
    if not concrete_shift_ids:
        return correction_by_source, {}
    statuses = (
        await session.execute(
            select(Shift.id, Shift.status).where(Shift.id.in_(concrete_shift_ids))
        )
    ).all()
    return correction_by_source, dict(statuses)


def _expense_read(
    row: Expense,
    *,
    receipt_count: int = 0,
    receipt_status: ExpenseReceiptStatus = "pending",
    source_shift_status: str | None = None,
    correction: FinanceSourceCorrection | None = None,
) -> ExpenseRead:
    return ExpenseRead(
        id=row.id,
        branch_id=row.branch_id,
        category_id=row.category_id,
        supplier_id=row.supplier_id,
        amount_minor=int(row.amount_minor),
        paid_via=row.paid_via,
        paid_at=row.paid_at,
        vendor_name=row.vendor_name,
        invoice_no=row.invoice_no,
        note=row.note,
        shift_id=row.shift_id,
        created_by=row.created_by,
        voided_at=row.voided_at,
        voided_by=row.voided_by,
        void_reason=row.void_reason,
        is_voided=row.voided_at is not None,
        receipt_count=receipt_count,
        receipt_status=receipt_status,
        source_shift_status=source_shift_status,
        is_corrected=correction is not None,
        correction=_finance_correction_read(correction) if correction else None,
    )


async def _enqueue_expense_mirror(
    session: SessionDep,
    *,
    row: Expense,
    actor_user_id: UUID,
    event_kind: Literal["recorded", "voided"],
) -> None:
    """Mirror an immutable expense fact without exporting receipt bytes or notes."""

    company = await session.get(Company, row.company_id)
    if company is None or company.deleted_at is not None:
        return
    branch = await session.get(Branch, row.branch_id)
    category = await session.get(ExpenseCategory, row.category_id)
    is_void = event_kind == "voided"
    stable_actor_id = row.voided_by if is_void and row.voided_by else actor_user_id
    actor = await session.get(User, stable_actor_id)
    occurred_at = row.voided_at if is_void else row.paid_at
    if occurred_at is None:  # pragma: no cover - guarded by the persisted state
        raise RuntimeError("voided expense is missing its void timestamp")
    category_name = category.name if category else "Expense"
    description = category_name
    if row.vendor_name:
        description = f"{category_name} · {row.vendor_name}"
    await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=row.company_id,
        event_type=f"finance.expense.{event_kind}",
        source_type="expense",
        source_id=str(row.id),
        source_revision="void-v1" if is_void else "recorded-v1",
        occurred_at=occurred_at,
        payload={
            "branch": branch.name if branch else str(row.branch_id),
            "reference": row.invoice_no or str(row.id),
            "description": f"Void · {description}" if is_void else description,
            "customer": "",
            "quantity": 1,
            "amount_minor": int(row.amount_minor) if is_void else -int(row.amount_minor),
            "expense_total_minor": -int(row.amount_minor) if is_void else int(row.amount_minor),
            "payment_method": row.paid_via,
            "actor": actor.name if actor else "",
            "status": event_kind,
            "currency": company.currency,
            "expense_id": str(row.id),
            "branch_id": str(row.branch_id),
            "category_id": str(row.category_id),
            "shift_id": str(row.shift_id) if row.shift_id else "",
        },
    )


def _short_finance_reference(prefix: str, source_id: UUID) -> str:
    """Return a readable identifier without exposing request or payment secrets."""

    return f"{prefix}-{str(source_id).split('-', 1)[0].upper()}"


async def _enqueue_finance_source_mirror(
    session: SessionDep,
    *,
    company_id: UUID,
    branch_id: UUID | None,
    actor_user_id: UUID | None,
    event_type: str,
    source_type: str,
    source_id: UUID,
    source_revision: str,
    occurred_at: datetime,
    reference: str,
    description: str,
    amount_minor: int,
    payment_method: str,
    status_label: str,
    identifiers: dict[str, str],
) -> None:
    """Queue one sanitized finance fact in the caller-owned transaction.

    Free-form notes, void reasons, receipt evidence, credentials and payment
    references are deliberately absent from this contract. Source-specific
    identifiers are UUIDs or bounded accounting classifications only.
    """

    company = await session.get(Company, company_id)
    if company is None or company.deleted_at is not None:
        return
    branch = await session.get(Branch, branch_id) if branch_id is not None else None
    actor = (
        await session.get(User, actor_user_id)
        if actor_user_id is not None
        else None
    )
    await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=company_id,
        event_type=event_type,
        source_type=source_type,
        source_id=str(source_id),
        source_revision=source_revision,
        occurred_at=occurred_at,
        payload={
            "branch": branch.name if branch is not None else "Company-wide",
            "reference": reference,
            "description": description,
            "customer": "",
            "quantity": 1,
            "amount_minor": int(amount_minor),
            "payment_method": payment_method,
            "actor": actor.name if actor is not None else str(actor_user_id or ""),
            "status": status_label,
            "currency": company.currency,
            **dict(identifiers),
        },
    )


async def _expense_receipt_summaries(
    session: SessionDep,
    *,
    company_id: UUID,
    expense_ids: list[UUID],
) -> tuple[dict[UUID, int], dict[UUID, ExpenseReceiptStatus]]:
    """Load receipt counts and the latest append-only review in two queries."""

    if not expense_ids:
        return {}, {}
    count_rows = (
        await session.execute(
            select(ExpenseReceipt.expense_id, func.count(ExpenseReceipt.id))
            .where(
                ExpenseReceipt.company_id == company_id,
                ExpenseReceipt.expense_id.in_(expense_ids),
            )
            .group_by(ExpenseReceipt.expense_id)
        )
    ).all()
    counts = {expense_id: int(count) for expense_id, count in count_rows}

    ranked = (
        select(
            ExpenseReceiptReview.expense_id.label("expense_id"),
            ExpenseReceiptReview.status.label("status"),
            func.row_number()
            .over(
                partition_by=ExpenseReceiptReview.expense_id,
                order_by=(
                    ExpenseReceiptReview.created_at.desc(),
                    ExpenseReceiptReview.id.desc(),
                ),
            )
            .label("position"),
        )
        .where(
            ExpenseReceiptReview.company_id == company_id,
            ExpenseReceiptReview.expense_id.in_(expense_ids),
        )
        .subquery()
    )
    review_rows = (
        await session.execute(
            select(ranked.c.expense_id, ranked.c.status).where(ranked.c.position == 1)
        )
    ).all()
    statuses = {
        expense_id: cast("ExpenseReceiptStatus", review_status)
        for expense_id, review_status in review_rows
    }
    return counts, statuses


async def _expense_or_404(
    session: SessionDep,
    *,
    expense_id: UUID,
    tenant: TenantContext,
    lock: bool,
    allow_voided: bool,
) -> Expense:
    stmt = select(Expense).where(
        Expense.id == expense_id,
        Expense.company_id == tenant.company_id,
        Expense.deleted_at.is_(None),
    )
    if not allow_voided:
        stmt = stmt.where(Expense.voided_at.is_(None))
    if lock:
        stmt = stmt.with_for_update()
    row = (await session.execute(stmt)).scalar_one_or_none()
    if row is None or not tenant.in_branch(row.branch_id):
        raise NotFoundError("expense not found")

    return row


async def _current_expense_receipt_review(
    session: SessionDep,
    *,
    company_id: UUID,
    expense_id: UUID,
) -> tuple[ExpenseReceiptStatus, str | None]:
    row = (
        await session.execute(
            select(ExpenseReceiptReview.status, ExpenseReceiptReview.review_note)
            .where(
                ExpenseReceiptReview.company_id == company_id,
                ExpenseReceiptReview.expense_id == expense_id,
            )
            .order_by(
                ExpenseReceiptReview.created_at.desc(),
                ExpenseReceiptReview.id.desc(),
            )
            .limit(1)
        )
    ).one_or_none()
    if row is None:
        return "pending", None
    return cast("ExpenseReceiptStatus", row.status), row.review_note


def _receipt_read(
    row: ExpenseReceipt,
    *,
    review_status: ExpenseReceiptStatus,
    review_note: str | None,
) -> ExpenseReceiptRead:
    return ExpenseReceiptRead(
        id=row.id,
        expense_id=row.expense_id,
        original_filename=row.original_filename,
        content_type=row.content_type,
        size_bytes=row.size_bytes,
        sha256=row.sha256,
        source=cast("ExpenseReceiptSource", row.source),
        status=review_status,
        review_note=review_note,
        created_at=row.created_at,
    )


@dataclass(frozen=True, slots=True)
class _Code21CashExpenseCapture:
    """Untrusted compatibility metadata after strict structural validation.

    The version header routes a deployed-client compatibility path; it never
    grants authority.  ``finance.write`` plus the authenticated exact
    branch/terminal/open-shift scope remain the authorization boundary.
    """

    occurred_at: datetime
    paid_at: datetime
    receipt_hash: str


def _is_code21_android_request(request: Request) -> bool:
    headers = getattr(request, "headers", {})
    installed_version = parse_client_version_code(
        headers.get("X-Client-Version-Code")
    )
    saved_version = parse_client_version_code(
        headers.get(SAVED_CLIENT_VERSION_CODE_HEADER)
    )
    is_android = headers.get("X-Client-Platform", "").strip().lower() == "android"
    return is_android and (
        installed_version == CODE21_CASH_EXPENSE_VERSION
        or (
            installed_version is not None
            and installed_version > CODE21_CASH_EXPENSE_VERSION
            and saved_version == CODE21_CASH_EXPENSE_VERSION
        )
    )


def _read_code21_cash_expense_capture(
    request: Request,
    *,
    idempotency_key: str,
    request_hash: str,
    paid_at: datetime,
    now: datetime,
) -> _Code21CashExpenseCapture:
    """Validate the exact provenance emitted by the signed Code 21 outbox."""

    if not _is_code21_android_request(request):
        raise BusinessRuleError(EXPENSE_CASH_PAID_OUT_UNAVAILABLE)
    if not idempotency_key.startswith("expense:"):
        raise BusinessRuleError(
            "The saved cash expense has an invalid action identity. Retry it from "
            "the original tablet; do not enter the expense again."
        )
    try:
        local_id = UUID(idempotency_key.removeprefix("expense:"))
    except ValueError as exc:
        raise BusinessRuleError(
            "The saved cash expense has an invalid action identity. Retry it from "
            "the original tablet; do not enter the expense again."
        ) from exc
    if idempotency_key != f"expense:{local_id}":
        raise BusinessRuleError(
            "The saved cash expense action identity is not canonical. Retry it from "
            "the original tablet; do not enter the expense again."
        )
    if request.headers.get("X-Offline-Captured", "").strip().lower() != "true":
        raise BusinessRuleError(
            "Cash expenses from this app must come from its durable saved-work queue. "
            "Nothing was recorded; retry from the original tablet."
        )
    if request.headers.get("X-Client-Action-Id", "").strip() != idempotency_key:
        raise BusinessRuleError(
            "The saved cash expense action does not match its Idempotency-Key. "
            "Nothing was recorded; retry from the original tablet."
        )
    raw_occurred_at = request.headers.get("X-Client-Occurred-At", "").strip()
    try:
        occurred_at = datetime.fromisoformat(raw_occurred_at.replace("Z", "+00:00"))
        if occurred_at.tzinfo is None:
            raise ValueError("timezone required")
        occurred_at = occurred_at.astimezone(timezone.utc)
    except (TypeError, ValueError) as exc:
        raise BusinessRuleError(
            "The saved cash expense needs a valid captured time including timezone. "
            "Nothing was recorded; correct the tablet clock and retry."
        ) from exc
    if paid_at.tzinfo is None:
        raise BusinessRuleError(
            "Cash expense payment time must include a timezone. Nothing was recorded."
        )
    paid_at_utc = paid_at.astimezone(timezone.utc)
    if occurred_at > now or paid_at_utc > now:
        raise BusinessRuleError(
            "The saved cash expense time is in the future. Nothing was recorded; "
            "correct the tablet clock and retry from the original saved entry."
        )
    if abs(occurred_at - paid_at_utc) > CODE21_CASH_EXPENSE_CLOCK_WINDOW:
        raise BusinessRuleError(
            "The saved cash expense payment time does not match when the tablet "
            "captured it. Nothing was recorded; ask an owner to review the saved entry."
        )
    receipt_hash = sha256(
        (
            "code21-cash-expense-v1\n"
            f"{request_hash}\n{occurred_at.isoformat()}\n{paid_at_utc.isoformat()}"
        ).encode()
    ).hexdigest()
    return _Code21CashExpenseCapture(
        occurred_at=occurred_at,
        paid_at=paid_at_utc,
        receipt_hash=receipt_hash,
    )


async def _existing_code21_cash_expense_receipt(
    session: SessionDep,
    *,
    idempotency_key: str,
    tenant: TenantContext,
) -> Expense | None:
    """Find a durable receipt before the generic idempotency cache lookup."""

    return (
        await session.execute(
            select(Expense).where(
                Expense.company_id == tenant.company_id,
                Expense.idempotency_key == idempotency_key,
                Expense.source_integrity_revision
                == CODE21_CASH_EXPENSE_RECEIPT_REVISION,
            )
        )
    ).scalar_one_or_none()


async def _validate_code21_cash_expense_replay(
    session: SessionDep,
    *,
    row: Expense,
    capture: _Code21CashExpenseCapture,
    tenant: TenantContext,
) -> ExpenseRead:
    """Return only the original actor/scope/body receipt, including after close."""

    if row.request_hash != capture.receipt_hash:
        raise IdempotencyConflict(
            "Idempotency-Key reused with different cash-expense provenance",
            details={"key": row.idempotency_key},
        )
    if row.created_by != tenant.user_id:
        raise IdempotencyConflict(
            "Idempotency-Key reused by a different user",
            details={"key": row.idempotency_key},
        )
    shift = await session.get(Shift, row.shift_id)
    if (
        shift is None
        or shift.company_id != tenant.company_id
        or shift.branch_id != row.branch_id
    ):
        raise BusinessRuleError(
            "The saved cash expense has an invalid shift receipt. Do not enter it "
            "again; ask an owner to reconcile the original record."
        )
    if tenant.branch_id != row.branch_id or tenant.terminal_id != shift.terminal_id:
        raise IdempotencyConflict(
            "Idempotency-Key reused from a different branch or terminal",
            details={"key": row.idempotency_key},
        )
    # Reconstruct the original create response. A later reasoned void remains
    # authoritative, but it must not turn an old create replay into a different
    # response or cause Code 21 to enqueue a duplicate.
    return _expense_read(row).model_copy(
        update={
            "voided_at": None,
            "voided_by": None,
            "void_reason": None,
            "is_voided": False,
        }
    )


def _require_modern_cash_expense_action_key(idempotency_key: str) -> None:
    """Require the durable ``expense:<uuid>`` identity used by native/web outboxes."""

    if not idempotency_key.startswith("expense:"):
        raise BusinessRuleError(
            "Cash expense Idempotency-Key must use the expense:<uuid> format."
        )
    try:
        action_id = UUID(idempotency_key.removeprefix("expense:"))
    except ValueError as exc:
        raise BusinessRuleError(
            "Cash expense Idempotency-Key must contain a valid UUID."
        ) from exc
    if idempotency_key != f"expense:{action_id}":
        raise BusinessRuleError(
            "Cash expense Idempotency-Key must use the canonical expense:<uuid> format."
        )


def _modern_cash_expense_request_hash(payload: ExpenseCreate) -> str:
    """Hash normalized business facts, independent of JSON formatting or key order."""

    if payload.paid_at.tzinfo is None:
        raise BusinessRuleError("Cash expense payment time must include a timezone.")
    canonical = payload.model_dump(mode="json")
    canonical["paid_at"] = payload.paid_at.astimezone(timezone.utc).isoformat()
    encoded = json.dumps(
        canonical,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    return sha256(f"modern-cash-expense-v1\n{encoded}".encode()).hexdigest()


def _original_expense_create_response(row: Expense) -> ExpenseRead:
    """Reconstruct the immutable create response even after a later reasoned void."""

    return _expense_read(row).model_copy(
        update={
            "voided_at": None,
            "voided_by": None,
            "void_reason": None,
            "is_voided": False,
        }
    )


def _validate_modern_cash_expense_replay(
    *,
    row: Expense,
    shift: Shift | None,
    idempotency_key: str,
    request_hash: str,
    tenant: TenantContext,
) -> ExpenseRead:
    if row.request_hash != request_hash:
        raise IdempotencyConflict(
            "Idempotency-Key reused with different cash-expense facts",
            details={"key": idempotency_key},
        )
    if row.created_by != tenant.user_id:
        raise IdempotencyConflict(
            "Idempotency-Key reused by a different user",
            details={"key": idempotency_key},
        )
    if (
        shift is None
        or shift.company_id != tenant.company_id
        or shift.branch_id != row.branch_id
        or row.shift_id != shift.id
    ):
        raise BusinessRuleError(
            "The saved cash expense has an invalid shift receipt. Do not enter it "
            "again; ask an owner to reconcile the original record."
        )
    if not tenant.in_branch(row.branch_id):
        raise NotFoundError("expense not found")
    return _original_expense_create_response(row)


async def _locked_modern_cash_expense_replay(
    session: SessionDep,
    *,
    idempotency_key: str,
    request_hash: str,
    payload: ExpenseCreate,
    tenant: TenantContext,
) -> ExpenseRead | None:
    """Resolve a durable modern replay using the global Shift -> Expense lock order."""

    preflight = (
        await session.execute(
            select(Expense.shift_id).where(
                Expense.company_id == tenant.company_id,
                Expense.idempotency_key == idempotency_key,
                Expense.source_integrity_revision
                == MODERN_CASH_EXPENSE_RECEIPT_REVISION,
            )
        )
    ).scalar_one_or_none()
    if preflight is None:
        return None
    if payload.shift_id != preflight:
        raise IdempotencyConflict(
            "Idempotency-Key reused with a different cash-expense shift",
            details={"key": idempotency_key},
        )

    shift = (
        await session.execute(
            select(Shift).where(Shift.id == preflight).with_for_update()
        )
    ).scalar_one_or_none()
    row = (
        await session.execute(
            select(Expense)
            .where(
                Expense.company_id == tenant.company_id,
                Expense.idempotency_key == idempotency_key,
                Expense.source_integrity_revision
                == MODERN_CASH_EXPENSE_RECEIPT_REVISION,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if row is None:
        return None
    return _validate_modern_cash_expense_replay(
        row=row,
        shift=shift,
        idempotency_key=idempotency_key,
        request_hash=request_hash,
        tenant=tenant,
    )


async def _lock_modern_cash_expense_shift(
    session: SessionDep,
    *,
    payload: ExpenseCreate,
    tenant: TenantContext,
) -> Shift:
    """Lock and validate the explicit drawer before inserting its expense child."""

    if payload.shift_id is None:
        raise BusinessRuleError("Select the open shift that paid this cash expense.")
    if not tenant.in_branch(payload.branch_id):
        raise NotFoundError("branch not found")
    shift = (
        await session.execute(
            select(Shift)
            .where(
                Shift.id == payload.shift_id,
                Shift.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if shift is None or shift.branch_id != payload.branch_id:
        raise NotFoundError("shift not found")
    if shift.status != "open":
        raise BusinessRuleError(
            "This shift is closed and cannot pay a new cash expense. Select an open shift."
        )
    if payload.paid_at.tzinfo is None:
        raise BusinessRuleError("Cash expense payment time must include a timezone.")
    paid_at = payload.paid_at.astimezone(timezone.utc)
    now = datetime.now(timezone.utc)
    if paid_at < shift.opened_at:
        raise BusinessRuleError(
            "Cash expense payment time cannot be before the selected shift opened."
        )
    if paid_at > now:
        raise BusinessRuleError("Cash expense payment time cannot be in the future.")
    if payload.amount_minor > int(shift.expected_minor or 0):
        raise BusinessRuleError(
            "This cash expense exceeds the cash expected in the selected shift drawer."
        )
    return shift


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
        Expense.voided_at.is_(None),
    )
    stmt = _scope_to_tenant_branch(stmt, Expense.branch_id, tenant)
    timezone_name = await company_timezone(session, tenant.company_id)
    if from_date:
        from_at, _ = local_date_bounds_utc(from_date, from_date, timezone_name)
        stmt = stmt.where(Expense.paid_at >= from_at)
    if to_date:
        _, to_exclusive = local_date_bounds_utc(to_date, to_date, timezone_name)
        stmt = stmt.where(Expense.paid_at < to_exclusive)
    stmt = stmt.order_by(Expense.paid_at.desc())
    rows = (await session.execute(stmt)).scalars().all()
    counts, statuses = await _expense_receipt_summaries(
        session,
        company_id=tenant.company_id,
        expense_ids=[row.id for row in rows],
    )
    corrections, shift_statuses = await _finance_source_context(
        session,
        source_type="expense",
        source_ids=[row.id for row in rows],
        shift_ids=[row.shift_id for row in rows],
    )
    return [
        _expense_read(
            row,
            receipt_count=counts.get(row.id, 0),
            receipt_status=statuses.get(row.id, "pending"),
            source_shift_status=(
                shift_statuses.get(row.shift_id) if row.shift_id else None
            ),
            correction=corrections.get(row.id),
        )
        for row in rows
    ]


@router.get(
    "/expenses/actions/{action_id}/reconciliation",
    response_model=ExpenseActionReconciliationRead,
)
async def reconcile_saved_expense_action(
    action_id: UUID,
    branch_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseActionReconciliationRead:
    """Resolve whether this user's rejected offline expense reached the server.

    The response contains metadata only. A missing action key is authoritative
    absence in this database; a reserved key fails closed; and an accepted key
    is returned only after its source row is checked against the authenticated
    company and requested branch. Older servers do not expose this route, so a
    client must never interpret HTTP 404 as absence.
    """

    if not tenant.in_branch(branch_id):
        raise NotFoundError("branch not found")
    branch = (
        await session.execute(
            select(Branch.id).where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if branch is None:
        raise NotFoundError("branch not found")

    idempotency_key = f"expense:{action_id}"
    source = (
        await session.execute(
            select(Expense).where(
                Expense.company_id == tenant.company_id,
                Expense.branch_id == branch_id,
                Expense.idempotency_key == idempotency_key,
            )
        )
    ).scalar_one_or_none()
    receipt = (
        await session.execute(
            select(IdempotencyKey).where(IdempotencyKey.key == idempotency_key)
        )
    ).scalar_one_or_none()
    if source is None and receipt is None:
        return ExpenseActionReconciliationRead(
            state="absent",
            idempotency_key=idempotency_key,
        )
    if source is not None and source.created_by != tenant.user_id:
        raise NotFoundError("saved expense action not found")
    if receipt is not None and (
        receipt.user_id != tenant.user_id or receipt.terminal_id is not None
    ):
        raise NotFoundError("saved expense action not found")
    if source is None and (
        receipt.response_status is None or receipt.response_body is None
    ):
        return ExpenseActionReconciliationRead(
            state="in_progress",
            idempotency_key=idempotency_key,
        )

    if receipt is not None and receipt.response_body is not None:
        try:
            stored = ExpenseRead.model_validate(receipt.response_body)
        except ValueError as exc:
            raise ConflictError(
                "The saved expense action has an unreadable server receipt; "
                "nothing was removed."
            ) from exc
        if source is not None and stored.id != source.id:
            raise ConflictError(
                "The saved expense action receipt does not match its durable expense; "
                "nothing was removed."
            )
    if source is None:
        raise ConflictError(
            "The saved expense action receipt does not match a current expense; nothing was removed."
        )
    counts, statuses = await _expense_receipt_summaries(
        session,
        company_id=tenant.company_id,
        expense_ids=[source.id],
    )
    corrections, shift_statuses = await _finance_source_context(
        session,
        source_type="expense",
        source_ids=[source.id],
        shift_ids=[source.shift_id],
    )
    authoritative = _expense_read(
        source,
        receipt_count=counts.get(source.id, 0),
        receipt_status=statuses.get(source.id, "pending"),
        source_shift_status=(
            shift_statuses.get(source.shift_id) if source.shift_id else None
        ),
        correction=corrections.get(source.id),
    )
    return ExpenseActionReconciliationRead(
        state="accepted",
        idempotency_key=idempotency_key,
        expense=authoritative,
    )


def _require_idempotency(request: Request, *, what: str) -> tuple[str, str]:
    """Shared mandatory-idempotency guard for writes with no natural key.

    Expenses and assets get a fresh uuid4() PK with no unique constraint a
    duplicate could collide against — unlike ingredient SKU or customer
    phone, there is no fallback that makes a missing header merely an
    annoyance, so (like Inventory's GRN/adjustment writes) the header is
    required, not optional.
    """
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(f"Idempotency-Key header required for {what} writes")
    return str(key), str(request_hash)


def _detect_expense_receipt_content_type(
    body: bytes,
    *,
    claimed_content_type: str | None,
) -> str:
    """Validate receipt bytes and return one canonical media type.

    Camera and gallery clients sometimes omit the MIME type or send
    ``application/octet-stream``. Detection therefore comes from the bytes;
    a specific but contradictory claimed type is rejected rather than trusted.
    """

    if not body:
        raise BusinessRuleError("The selected receipt is empty.")
    if len(body) > MAX_EXPENSE_RECEIPT_BYTES:
        raise BusinessRuleError("The receipt exceeds the 10 MB limit.")

    detected: str | None = None
    if body.startswith(b"\x89PNG\r\n\x1a\n"):
        detected = "image/png"
    elif body.startswith(b"\xff\xd8\xff") and body.rstrip().endswith(b"\xff\xd9"):
        detected = "image/jpeg"
    elif len(body) >= 12 and body[:4] == b"RIFF" and body[8:12] == b"WEBP":
        detected = "image/webp"
    elif body.startswith(b"%PDF-") and b"%%EOF" in body[-4096:]:
        detected = "application/pdf"
    if detected is None:
        raise BusinessRuleError(
            "Attach a valid JPEG, PNG, WebP, or PDF receipt."
        )

    claimed = (claimed_content_type or "").split(";", 1)[0].strip().lower()
    claimed_aliases = {
        "image/jpg": "image/jpeg",
        "application/x-pdf": "application/pdf",
    }
    claimed = claimed_aliases.get(claimed, claimed)
    if claimed not in {"", "application/octet-stream", detected}:
        raise BusinessRuleError(
            "The receipt file type does not match its contents. Choose the original file."
        )

    if detected in {"image/jpeg", "image/png", "image/webp"}:
        expected_format = {
            "image/jpeg": "JPEG",
            "image/png": "PNG",
            "image/webp": "WEBP",
        }[detected]
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(BytesIO(body)) as image:
                    if (image.format or "").upper() != expected_format:
                        raise BusinessRuleError(
                            "The receipt file type does not match its contents."
                        )
                    if getattr(image, "n_frames", 1) != 1:
                        raise BusinessRuleError(
                            "Animated images cannot be used as receipt evidence."
                        )
                    image.verify()
        except BusinessRuleError:
            raise
        except (
            Image.DecompressionBombError,
            Image.DecompressionBombWarning,
            MemoryError,
            OSError,
            SyntaxError,
            UnidentifiedImageError,
            ValueError,
        ) as exc:
            raise BusinessRuleError(
                "Attach a valid JPEG, PNG, or WebP receipt image."
            ) from exc
    return detected


def _safe_expense_receipt_filename(filename: str | None, content_type: str) -> str:
    extension = {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
        "application/pdf": ".pdf",
    }[content_type]
    basename = Path(filename or "receipt").name
    stem = Path(basename).stem
    cleaned = "".join(
        char for char in stem if char >= " " and char not in {'"', "\\", "/"}
    ).strip(" .")
    safe_stem = (cleaned or "receipt")[: 200 - len(extension)].rstrip(" .")
    return f"{safe_stem or 'receipt'}{extension}"


async def _expense_receipt_or_404(
    session: SessionDep,
    *,
    company_id: UUID,
    expense_id: UUID,
    receipt_id: UUID,
) -> ExpenseReceipt:
    row = (
        await session.execute(
            select(ExpenseReceipt)
            .options(undefer(ExpenseReceipt.payload))
            .where(
                ExpenseReceipt.id == receipt_id,
                ExpenseReceipt.company_id == company_id,
                ExpenseReceipt.expense_id == expense_id,
            )
        )
    ).scalar_one_or_none()
    if row is None:
        raise NotFoundError("expense receipt not found")
    return row


def _private_expense_receipt_response(receipt: ExpenseReceipt) -> Response:
    safe_name = (
        receipt.original_filename.replace('"', "").replace("\r", "").replace("\n", "")
    )
    ascii_name = safe_name.encode("ascii", "ignore").decode().strip() or "receipt"
    encoded_name = quote(safe_name, safe="")
    return Response(
        content=receipt.payload,
        media_type=receipt.content_type,
        headers={
            "Cache-Control": "private, no-store",
            "Content-Disposition": (
                f'inline; filename="{ascii_name}"; filename*=UTF-8\'\'{encoded_name}'
            ),
            "Content-Security-Policy": "sandbox",
            "X-Content-Type-Options": "nosniff",
        },
    )


@router.post("/expenses", response_model=ExpenseRead, status_code=status.HTTP_201_CREATED)
async def create_expense(
    payload: ExpenseCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseRead:
    idempotency_key, request_hash = _require_idempotency(request, what="expense")
    cash_capture: _Code21CashExpenseCapture | None = None
    is_code21_cash_recovery = (
        payload.paid_via == "cash"
        and payload.shift_id is None
        and _is_code21_android_request(request)
    )

    if payload.paid_via != "cash" and payload.shift_id is not None:
        raise BusinessRuleError("Only cash expenses can be linked to a shift drawer.")
    if payload.paid_via == "cash" and not is_code21_cash_recovery:
        if payload.shift_id is None:
            raise BusinessRuleError(
                "Cash paid-outs require selecting the open shift that paid the expense."
            )
        _require_modern_cash_expense_action_key(idempotency_key)
        request_hash = _modern_cash_expense_request_hash(payload)
        durable_replay = await _locked_modern_cash_expense_replay(
            session,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
            payload=payload,
            tenant=tenant,
        )
        if durable_replay is not None:
            return durable_replay

    # A Code 21 cash receipt outlives the generic idempotency cache. Looking it
    # up before reserving that short-lived key prevents a lost response from
    # decrementing the drawer a second time after cache cleanup. The initial
    # platform/version check is routing only; the helper below still validates
    # provenance, actor and exact authenticated workspace.
    if is_code21_cash_recovery:
        cash_capture = _read_code21_cash_expense_capture(
            request,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
            paid_at=payload.paid_at,
            now=datetime.now(timezone.utc),
        )
        durable_receipt = await _existing_code21_cash_expense_receipt(
            session,
            idempotency_key=idempotency_key,
            tenant=tenant,
        )
        if durable_receipt is not None:
            return await _validate_code21_cash_expense_replay(
                session,
                row=durable_receipt,
                capture=cash_capture,
                tenant=tenant,
            )

    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        response = ExpenseRead.model_validate(replay["body"])
        # Revision-51 cached responses must pass the same exact provenance and
        # terminal checks as their durable form. Historical cash responses have
        # no shift_id and retain the previous exact-idempotency replay behavior.
        if is_code21_cash_recovery and response.shift_id is not None:
            if cash_capture is None:
                cash_capture = _read_code21_cash_expense_capture(
                    request,
                    idempotency_key=idempotency_key,
                    request_hash=request_hash,
                    paid_at=payload.paid_at,
                    now=datetime.now(timezone.utc),
                )
            durable_receipt = await _existing_code21_cash_expense_receipt(
                session,
                idempotency_key=idempotency_key,
                tenant=tenant,
            )
            if durable_receipt is None:
                raise BusinessRuleError(
                    "The saved cash expense receipt is missing. Do not enter it again; "
                    "ask an owner to reconcile the original request."
                )
            return await _validate_code21_cash_expense_replay(
                session,
                row=durable_receipt,
                capture=cash_capture,
                tenant=tenant,
            )
        return response

    # Keep accepting ``cash`` in the wire schema so deployed clients receive a
    # clear business-rule response instead of an opaque validation error. Code
    # 24+ never offers cash here; this is a narrowly gated recovery contract for
    # already-deployed Code 21 outbox rows.
    if payload.paid_via == "cash" and is_code21_cash_recovery:
        if cash_capture is None:
            cash_capture = _read_code21_cash_expense_capture(
                request,
                idempotency_key=idempotency_key,
                request_hash=request_hash,
                paid_at=payload.paid_at,
                now=datetime.now(timezone.utc),
            )
        if tenant.branch_id is None:
            raise BusinessRuleError(
                "This account has no branch assigned. Assign one before recording "
                "a cash expense."
            )
        if tenant.terminal_id is None:
            raise BusinessRuleError(
                "This saved cash expense is not linked to a workspace. Reconnect on "
                "the original tablet and retry; nothing was recorded."
            )
        if payload.branch_id != tenant.branch_id:
            raise NotFoundError("branch not found")
        shift = (
            await session.execute(
                select(Shift)
                .where(
                    Shift.company_id == tenant.company_id,
                    Shift.branch_id == tenant.branch_id,
                    Shift.terminal_id == tenant.terminal_id,
                    Shift.status == "open",
                )
                .with_for_update()
            )
        ).scalar_one_or_none()
        if shift is None:
            raise BusinessRuleError(
                "No open shift matches this branch and workspace. The saved cash "
                "expense was not recorded; open the correct shift or ask an owner "
                "to reconcile it without entering it twice."
            )
        if (
            cash_capture.occurred_at < shift.opened_at
            or cash_capture.paid_at < shift.opened_at
        ):
            raise BusinessRuleError(
                "This cash expense was captured before the current shift opened. "
                "It was not moved into a later drawer; ask an owner to reconcile "
                "the saved entry."
            )
        available_minor = int(shift.expected_minor or 0)
        if payload.amount_minor > available_minor:
            raise BusinessRuleError(
                "This cash expense exceeds the cash expected in the open drawer. "
                "Nothing was recorded; check the amount and reconcile the shift cash."
            )

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
            shift_id=shift.id,
            idempotency_key=idempotency_key,
            request_hash=cash_capture.receipt_hash,
            created_by=tenant.user_id,
            source_integrity_revision=CODE21_CASH_EXPENSE_RECEIPT_REVISION,
            **payload.model_dump(exclude={"shift_id"}),
        )
        session.add(ex)
        shift.expected_minor = available_minor - payload.amount_minor
        await session.flush()
        await _enqueue_expense_mirror(
            session,
            row=ex,
            actor_user_id=tenant.user_id,
            event_kind="recorded",
        )
        response = _expense_read(ex)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    if payload.paid_via == "cash":
        await _validate_expense_references(
            session,
            company_id=tenant.company_id,
            branch_id=payload.branch_id,
            category_id=payload.category_id,
            supplier_id=payload.supplier_id,
            ocr_extraction_id=payload.ocr_extraction_id,
        )
        shift = await _lock_modern_cash_expense_shift(
            session,
            payload=payload,
            tenant=tenant,
        )
        ex = Expense(
            id=uuid4(),
            company_id=tenant.company_id,
            shift_id=shift.id,
            idempotency_key=idempotency_key,
            request_hash=request_hash,
            created_by=tenant.user_id,
            source_integrity_revision=MODERN_CASH_EXPENSE_RECEIPT_REVISION,
            **payload.model_dump(exclude={"shift_id"}),
        )
        session.add(ex)
        # Migration 0077's database guard decrements the already-locked drawer
        # in the same transaction as the immutable expense insert. This keeps
        # direct SQL and future write paths from creating a cash fact without
        # its matching drawer movement.
        await session.flush()
        await _enqueue_expense_mirror(
            session,
            row=ex,
            actor_user_id=tenant.user_id,
            event_kind="recorded",
        )
        response = _expense_read(ex)
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

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
        **payload.model_dump(exclude={"shift_id"}),
    )
    session.add(ex)
    await session.flush()
    await _enqueue_expense_mirror(
        session,
        row=ex,
        actor_user_id=tenant.user_id,
        event_kind="recorded",
    )
    response = _expense_read(ex)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get(
    "/expenses/{expense_id}/receipts",
    response_model=list[ExpenseReceiptRead],
)
async def list_expense_receipts(
    expense_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[ExpenseReceiptRead]:
    await _expense_or_404(
        session,
        expense_id=expense_id,
        tenant=tenant,
        lock=False,
        allow_voided=True,
    )
    rows = (
        await session.execute(
            select(ExpenseReceipt)
            .where(
                ExpenseReceipt.company_id == tenant.company_id,
                ExpenseReceipt.expense_id == expense_id,
            )
            .order_by(ExpenseReceipt.created_at, ExpenseReceipt.id)
        )
    ).scalars().all()
    current_status, review_note = await _current_expense_receipt_review(
        session,
        company_id=tenant.company_id,
        expense_id=expense_id,
    )
    return [
        _receipt_read(
            row,
            review_status=current_status,
            review_note=review_note,
        )
        for row in rows
    ]


@router.post(
    "/expenses/{expense_id}/receipts",
    response_model=ExpenseReceiptRead,
    status_code=status.HTTP_201_CREATED,
)
async def upload_expense_receipt(
    expense_id: UUID,
    request: Request,
    session: SessionDep,
    file: Annotated[UploadFile, File()],
    source: Annotated[ExpenseReceiptSource, Form()] = "file",
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseReceiptRead:
    """Retain one original bill selected from camera, gallery, or file picker."""

    idempotency_key, _multipart_hash = _require_idempotency(
        request,
        what="expense receipt",
    )
    original_filename = file.filename
    claimed_content_type = file.content_type
    try:
        body = await file.read(MAX_EXPENSE_RECEIPT_BYTES + 1)
    finally:
        await file.close()
    content_type = await to_thread.run_sync(
        partial(
            _detect_expense_receipt_content_type,
            body,
            claimed_content_type=claimed_content_type,
        ),
        limiter=_EXPENSE_RECEIPT_DECODER_LIMITER,
    )
    filename = _safe_expense_receipt_filename(original_filename, content_type)
    digest = sha256(body).hexdigest()
    request_hash = sha256(
        (
            f"expense-receipt-v1\n{expense_id}\n{content_type}\n{digest}\n"
            f"{filename}\n{source}"
        ).encode()
    ).hexdigest()

    # Check scope before reserving a globally unique idempotency key. A caller
    # must not be able to reserve keys against another tenant's identifiers.
    await _expense_or_404(
        session,
        expense_id=expense_id,
        tenant=tenant,
        lock=False,
        allow_voided=False,
    )
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return ExpenseReceiptRead.model_validate(replay["body"])

    expense = await _expense_or_404(
        session,
        expense_id=expense_id,
        tenant=tenant,
        lock=True,
        allow_voided=False,
    )
    duplicate = (
        await session.execute(
            select(ExpenseReceipt).where(
                ExpenseReceipt.company_id == tenant.company_id,
                ExpenseReceipt.expense_id == expense_id,
                ExpenseReceipt.sha256 == digest,
            )
        )
    ).scalar_one_or_none()
    if duplicate is not None:
        current_status, review_note = await _current_expense_receipt_review(
            session,
            company_id=tenant.company_id,
            expense_id=expense_id,
        )
        response = _receipt_read(
            duplicate,
            review_status=current_status,
            review_note=review_note,
        )
        await store_response(
            session,
            key=idempotency_key,
            status_code=status.HTTP_201_CREATED,
            body=response.model_dump(mode="json"),
        )
        return response

    receipt_count = int(
        (
            await session.execute(
                select(func.count(ExpenseReceipt.id)).where(
                    ExpenseReceipt.company_id == tenant.company_id,
                    ExpenseReceipt.expense_id == expense_id,
                )
            )
        ).scalar_one()
        or 0
    )
    if receipt_count >= MAX_EXPENSE_RECEIPTS:
        raise BusinessRuleError(
            f"An expense can contain up to {MAX_EXPENSE_RECEIPTS} receipt files."
        )

    now = datetime.now(timezone.utc)
    receipt = ExpenseReceipt(
        id=uuid4(),
        company_id=tenant.company_id,
        expense_id=expense_id,
        uploader_user_id=tenant.user_id,
        original_filename=filename,
        content_type=content_type,
        size_bytes=len(body),
        sha256=digest,
        source=source,
        payload=body,
        created_at=now,
    )
    # Every new piece of evidence needs review, even if a previous file for the
    # expense had already been verified or marked not required.
    review = ExpenseReceiptReview(
        id=uuid4(),
        company_id=tenant.company_id,
        expense_id=expense_id,
        status="pending",
        review_note=None,
        reviewed_by=tenant.user_id,
        created_at=now,
    )
    session.add_all([receipt, review])
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="expense_receipt_add",
            entity_type="Expense",
            entity_id=str(expense_id),
            before=None,
            after={
                "receipt_id": str(receipt.id),
                "content_type": content_type,
                "size_bytes": len(body),
                "source": source,
                "sha256": digest,
            },
        )
    )
    await session.flush()
    await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=tenant.company_id,
        event_type="finance.expense.receipt_attached",
        source_type="expense_receipt",
        source_id=str(receipt.id),
        source_revision="attached-v1",
        occurred_at=now,
        payload={
            "branch": str(expense.branch_id),
            "reference": expense.invoice_no or str(expense.id),
            "description": "Expense receipt attached",
            "actor": str(tenant.user_id),
            "status": "pending",
            "expense_id": str(expense.id),
            "receipt_id": str(receipt.id),
            "content_type": content_type,
            "size_bytes": len(body),
            "source": source,
            "receipt_sha256": digest,
        },
    )
    response = _receipt_read(
        receipt,
        review_status="pending",
        review_note=None,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get(
    "/expenses/{expense_id}/receipts/{receipt_id}",
    response_class=Response,
)
async def download_expense_receipt(
    expense_id: UUID,
    receipt_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> Response:
    await _expense_or_404(
        session,
        expense_id=expense_id,
        tenant=tenant,
        lock=False,
        allow_voided=True,
    )
    receipt = await _expense_receipt_or_404(
        session,
        company_id=tenant.company_id,
        expense_id=expense_id,
        receipt_id=receipt_id,
    )
    return _private_expense_receipt_response(receipt)


@router.get(
    "/expense-receipts/{receipt_id}/content",
    response_class=Response,
)
async def download_expense_receipt_content(
    receipt_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> Response:
    """Stable client download path when the expense id is already in metadata."""

    receipt = (
        await session.execute(
            select(ExpenseReceipt)
            .options(undefer(ExpenseReceipt.payload))
            .where(
                ExpenseReceipt.id == receipt_id,
                ExpenseReceipt.company_id == tenant.company_id,
            )
        )
    ).scalar_one_or_none()
    if receipt is None:
        raise NotFoundError("expense receipt not found")
    await _expense_or_404(
        session,
        expense_id=receipt.expense_id,
        tenant=tenant,
        lock=False,
        allow_voided=True,
    )
    return _private_expense_receipt_response(receipt)


@router.get(
    "/expenses/{expense_id}/receipt-reviews",
    response_model=list[ExpenseReceiptReviewRead],
)
async def list_expense_receipt_reviews(
    expense_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[ExpenseReceiptReviewRead]:
    await _expense_or_404(
        session,
        expense_id=expense_id,
        tenant=tenant,
        lock=False,
        allow_voided=True,
    )
    rows = (
        await session.execute(
            select(ExpenseReceiptReview)
            .where(
                ExpenseReceiptReview.company_id == tenant.company_id,
                ExpenseReceiptReview.expense_id == expense_id,
            )
            .order_by(
                ExpenseReceiptReview.created_at.desc(),
                ExpenseReceiptReview.id.desc(),
            )
        )
    ).scalars().all()
    return [
        ExpenseReceiptReviewRead(
            id=row.id,
            expense_id=row.expense_id,
            status=cast("ExpenseReceiptStatus", row.status),
            review_note=row.review_note,
            reviewed_by=row.reviewed_by,
            created_at=row.created_at,
        )
        for row in rows
    ]


@router.post(
    "/expenses/{expense_id}/receipt-review",
    response_model=ExpenseReceiptReviewRead,
    status_code=status.HTTP_201_CREATED,
)
async def review_expense_receipts(
    expense_id: UUID,
    payload: ExpenseReceiptReviewCreate,
    request: Request,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseReceiptReviewRead:
    idempotency_key, request_hash = _require_idempotency(
        request,
        what="expense receipt review",
    )
    review_note = payload.review_note.strip() if payload.review_note else None
    if payload.status in {"rejected", "not_required"} and (
        review_note is None or len(review_note) < 3
    ):
        raise BusinessRuleError(
            f"A {payload.status.replace('_', ' ')} receipt decision requires a reason."
        )

    await _expense_or_404(
        session,
        expense_id=expense_id,
        tenant=tenant,
        lock=False,
        allow_voided=False,
    )
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if replay:
        return ExpenseReceiptReviewRead.model_validate(replay["body"])

    expense = await _expense_or_404(
        session,
        expense_id=expense_id,
        tenant=tenant,
        lock=True,
        allow_voided=False,
    )
    previous_status, previous_note = await _current_expense_receipt_review(
        session,
        company_id=tenant.company_id,
        expense_id=expense_id,
    )
    receipt_count = int(
        (
            await session.execute(
                select(func.count(ExpenseReceipt.id)).where(
                    ExpenseReceipt.company_id == tenant.company_id,
                    ExpenseReceipt.expense_id == expense_id,
                )
            )
        ).scalar_one()
        or 0
    )
    if payload.status in {"verified", "rejected"} and receipt_count == 0:
        raise BusinessRuleError(
            f"A receipt cannot be {payload.status} until evidence is uploaded."
        )

    now = datetime.now(timezone.utc)
    review = ExpenseReceiptReview(
        id=uuid4(),
        company_id=tenant.company_id,
        expense_id=expense_id,
        status=payload.status,
        review_note=review_note,
        reviewed_by=tenant.user_id,
        created_at=now,
    )
    session.add(review)
    session.add(
        AuditLog(
            actor_user_id=tenant.user_id,
            company_id=tenant.company_id,
            action="expense_receipt_review",
            entity_type="Expense",
            entity_id=str(expense_id),
            before={"status": previous_status, "review_note": previous_note},
            after={
                "review_id": str(review.id),
                "status": payload.status,
                "review_note": review_note,
                "receipt_count": receipt_count,
            },
        )
    )
    await session.flush()
    await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=tenant.company_id,
        event_type="finance.expense.receipt_reviewed",
        source_type="expense_receipt_review",
        source_id=str(review.id),
        source_revision="decision-v1",
        occurred_at=now,
        payload={
            "branch": str(expense.branch_id),
            "reference": expense.invoice_no or str(expense.id),
            "description": "Expense receipt review",
            "actor": str(tenant.user_id),
            "status": payload.status,
            "expense_id": str(expense.id),
            "review_id": str(review.id),
            "receipt_count": receipt_count,
        },
    )
    response = ExpenseReceiptReviewRead(
        id=review.id,
        expense_id=review.expense_id,
        status=payload.status,
        review_note=review.review_note,
        reviewed_by=review.reviewed_by,
        created_at=review.created_at,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/expenses/{expense_id}", response_model=ExpenseRead)
async def update_expense(
    expense_id: UUID,
    payload: ExpenseUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseRead:
    ex = await session.get(Expense, expense_id)
    if (
        not ex
        or ex.company_id != tenant.company_id
        or ex.deleted_at
        or not tenant.in_branch(ex.branch_id)
    ):
        raise NotFoundError("expense not found")
    raise BusinessRuleError(
        "posted expenses are immutable; void this expense and create a corrected replacement"
    )


async def _void_expense(
    *,
    expense_id: UUID,
    reason: str,
    session: SessionDep,
    tenant: TenantContext,
) -> Expense:
    normalized_reason = reason.strip()
    if len(normalized_reason) < 3:
        raise BusinessRuleError("void reason must contain at least 3 characters")
    probe = (
        await session.execute(
            select(
                Expense.company_id,
                Expense.branch_id,
                Expense.shift_id,
                Expense.paid_via,
                Expense.source_integrity_revision,
                Expense.voided_at,
                Expense.deleted_at,
            ).where(Expense.id == expense_id)
        )
    ).one_or_none()
    if (
        probe is None
        or probe.company_id != tenant.company_id
        or probe.deleted_at is not None
        or not tenant.in_branch(probe.branch_id)
    ):
        raise NotFoundError("expense not found")

    # Cash expense voids alter the drawer a second time. Follow the same
    # Shift -> child lock order as shift close and cash settlement, so close
    # cannot snapshot expected cash between the reasoned void and its reversal.
    shift: Shift | None = None
    is_shift_linked_cash = (
        probe.source_integrity_revision
        in {
            CODE21_CASH_EXPENSE_RECEIPT_REVISION,
            MODERN_CASH_EXPENSE_RECEIPT_REVISION,
        }
        and probe.paid_via == "cash"
        and probe.shift_id is not None
    )
    if is_shift_linked_cash and probe.voided_at is None:
        shift = (
            await session.execute(
                select(Shift).where(Shift.id == probe.shift_id).with_for_update()
            )
        ).scalar_one_or_none()
    row = (
        await session.execute(
            select(Expense)
            .where(
                Expense.id == expense_id,
                Expense.company_id == tenant.company_id,
                Expense.deleted_at.is_(None),
            )
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if row is None or not tenant.in_branch(row.branch_id):
        raise NotFoundError("expense not found")

    correction = (
        await session.execute(
            select(FinanceSourceCorrection).where(
                FinanceSourceCorrection.expense_id == row.id
            )
        )
    ).scalar_one_or_none()
    if correction is not None:
        raise BusinessRuleError("A corrected expense cannot also be voided.")

    if row.voided_at is not None:
        if row.void_reason != normalized_reason:
            raise BusinessRuleError("expense is already voided with a different reason")
        return row

    if is_shift_linked_cash:
        if (
            shift is None
            or shift.company_id != tenant.company_id
            or shift.branch_id != row.branch_id
        ):
            raise BusinessRuleError(
                "This cash expense has an invalid shift receipt. It was not voided; "
                "ask an owner to reconcile the original drawer movement."
            )
        if (
            row.source_integrity_revision == CODE21_CASH_EXPENSE_RECEIPT_REVISION
            and (
                tenant.branch_id != row.branch_id
                or tenant.terminal_id != shift.terminal_id
            )
        ):
            raise BusinessRuleError(
                "This cash expense belongs to a different branch or workspace. "
                "Return to its original workspace before voiding it."
            )
        if shift.status != "open":
            raise BusinessRuleError(
                "This cash expense belongs to a closed shift and cannot change that "
                "shift's saved closing cash. Record an audited correction in the "
                "current period instead."
            )
        if row.source_integrity_revision == CODE21_CASH_EXPENSE_RECEIPT_REVISION:
            shift.expected_minor = int(shift.expected_minor or 0) + int(
                row.amount_minor
            )

    row.voided_at = datetime.now(timezone.utc)
    row.voided_by = tenant.user_id
    row.void_reason = normalized_reason
    if row.source_integrity_revision is None:
        row.source_integrity_revision = 50
    await session.flush()
    return row


@router.post("/expenses/{expense_id}/void", response_model=ExpenseRead)
async def void_expense(
    expense_id: UUID,
    payload: ExpenseVoid,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ExpenseRead:
    row = await _void_expense(
        expense_id=expense_id,
        reason=payload.reason,
        session=session,
        tenant=tenant,
    )
    await _enqueue_expense_mirror(
        session,
        row=row,
        actor_user_id=tenant.user_id,
        event_kind="voided",
    )
    counts, statuses = await _expense_receipt_summaries(
        session,
        company_id=tenant.company_id,
        expense_ids=[row.id],
    )
    return _expense_read(
        row,
        receipt_count=counts.get(row.id, 0),
        receipt_status=statuses.get(row.id, "pending"),
    )


@router.delete("/expenses/{expense_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_expense(
    expense_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
):
    # Backwards compatibility for deployed web/iOS clients. This no longer
    # deletes or hides a source fact without provenance: it performs the same
    # one-way void as the explicit endpoint. New clients should collect a user
    # reason and call POST /expenses/{id}/void.
    row = await _void_expense(
        expense_id=expense_id,
        reason="Voided through legacy expense delete action",
        session=session,
        tenant=tenant,
    )
    await _enqueue_expense_mirror(
        session,
        row=row,
        actor_user_id=tenant.user_id,
        event_kind="voided",
    )


async def _create_finance_source_correction(
    *,
    source_type: Literal[
        "expense", "manual_collection", "tip_payout", "supplier_payment"
    ],
    source_id: UUID,
    payload: FinanceSourceCorrectionCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext,
) -> FinanceSourceCorrectionRead:
    """Append one full current-period reversal without rewriting history."""

    prefix = {
        "expense": "expense-correction",
        "manual_collection": "manual-collection-correction",
        "tip_payout": "tip-payout-correction",
        "supplier_payment": "supplier-payment-correction",
    }[source_type]
    idempotency_key, request_hash = _require_idempotency(
        request, what=f"{source_type.replace('_', ' ')} correction"
    )
    _require_finance_action_key(idempotency_key, prefix=prefix)
    reason = payload.reason.strip()
    if len(reason) < 3:
        raise BusinessRuleError("correction reason must contain at least 3 characters")

    durable_replay = (
        await session.execute(
            select(FinanceSourceCorrection)
            .where(
                FinanceSourceCorrection.company_id == tenant.company_id,
                FinanceSourceCorrection.idempotency_key == idempotency_key,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if durable_replay is not None:
        if not tenant.in_branch(durable_replay.branch_id):
            raise NotFoundError("finance correction not found")
        if (
            durable_replay.source_type != source_type
            or _finance_correction_source_id(durable_replay) != source_id
            or durable_replay.settlement_shift_id != payload.settlement_shift_id
            or durable_replay.reason != reason
            or durable_replay.request_hash != request_hash
            or durable_replay.corrected_by != tenant.user_id
        ):
            raise IdempotencyConflict(
                "Idempotency-Key reused with a different finance correction or actor",
                details={"key": idempotency_key},
            )
        return _finance_correction_read(durable_replay)

    model = {
        "expense": Expense,
        "manual_collection": ManualCollection,
        "tip_payout": TipPayout,
        "supplier_payment": SupplierPayment,
    }[source_type]
    probe = (
        await session.execute(
            select(model).where(
                model.id == source_id,
                model.company_id == tenant.company_id,
            )
        )
    ).scalar_one_or_none()
    if probe is None or not tenant.in_branch(probe.branch_id):
        raise NotFoundError(f"{source_type.replace('_', ' ')} not found")
    if probe.shift_id is None:
        raise BusinessRuleError(
            "Only a cash source with an auditable shift receipt can be corrected."
        )

    # Shift order is stable across shift-close, ordinary void and correction.
    shift_ids = sorted({probe.shift_id, payload.settlement_shift_id}, key=str)
    shifts = (
        await session.execute(
            select(Shift)
            .where(Shift.id.in_(shift_ids), Shift.company_id == tenant.company_id)
            .order_by(Shift.id)
            .with_for_update()
        )
    ).scalars().all()
    shift_by_id = {shift.id: shift for shift in shifts}
    original_shift = shift_by_id.get(probe.shift_id)
    settlement_shift = shift_by_id.get(payload.settlement_shift_id)
    if original_shift is None or settlement_shift is None:
        raise NotFoundError("shift not found")

    source = (
        await session.execute(
            select(model)
            .where(
                model.id == source_id,
                model.company_id == tenant.company_id,
            )
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    ).scalar_one_or_none()
    if source is None or source.shift_id != original_shift.id:
        raise BusinessRuleError("finance source changed during correction; refresh and retry")
    existing = (
        await session.execute(
            select(FinanceSourceCorrection).where(
                {
                    "expense": FinanceSourceCorrection.expense_id,
                    "manual_collection": FinanceSourceCorrection.manual_collection_id,
                    "tip_payout": FinanceSourceCorrection.tip_payout_id,
                    "supplier_payment": FinanceSourceCorrection.supplier_payment_id,
                }[source_type]
                == source_id
            )
        )
    ).scalar_one_or_none()
    if existing is not None:
        if existing.idempotency_key == idempotency_key:
            if (
                existing.source_type != source_type
                or _finance_correction_source_id(existing) != source_id
                or existing.settlement_shift_id != payload.settlement_shift_id
                or existing.reason != reason
                or existing.request_hash != request_hash
                or existing.corrected_by != tenant.user_id
            ):
                raise IdempotencyConflict(
                    "Idempotency-Key reused with a different finance correction or actor",
                    details={"key": idempotency_key},
                )
            # A concurrent exact retry can miss the optimistic durable lookup,
            # then wait behind the winner on the shift/source locks above.  At
            # READ COMMITTED this post-lock lookup sees the committed receipt;
            # return it without reserving/applying a second drawer movement.
            return _finance_correction_read(existing)
        raise BusinessRuleError("This finance entry has already been corrected.")

    if original_shift.status not in {"closed", "reconciled"}:
        raise BusinessRuleError(
            "The original shift is still open. Use the ordinary void action instead."
        )
    if (
        settlement_shift.status != "open"
        or settlement_shift.branch_id != source.branch_id
        or original_shift.branch_id != source.branch_id
    ):
        raise BusinessRuleError(
            "Select a currently open shift from the same branch as the original entry."
        )
    if source.voided_at is not None:
        raise BusinessRuleError("A voided finance entry cannot also be corrected.")
    source_method = source.paid_via if source_type == "expense" else source.method
    if source_method != "cash":
        raise BusinessRuleError("Only closed-shift cash entries use this correction.")
    if source_type == "expense":
        if source.deleted_at is not None or source.source_integrity_revision not in {
            CODE21_CASH_EXPENSE_RECEIPT_REVISION,
            MODERN_CASH_EXPENSE_RECEIPT_REVISION,
        }:
            raise BusinessRuleError("The expense has no valid cash drawer receipt.")
    elif source.source_integrity_revision != 1:
        raise BusinessRuleError(
            "Legacy drawer-neutral entries cannot be corrected against a shift."
        )
    if source_type == "manual_collection" and source.source_kind != "manual_daily":
        raise BusinessRuleError(
            "Legacy daily collection history is drawer-neutral and cannot be corrected."
        )
    if (
        source_type == "manual_collection"
        and int(settlement_shift.expected_minor or 0) < int(source.amount_minor)
    ):
        raise BusinessRuleError(
            "This correction exceeds the expected cash in the selected drawer."
        )

    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        return FinanceSourceCorrectionRead.model_validate(replay["body"])

    source_fields: dict[str, UUID | None] = {
        "expense_id": None,
        "manual_collection_id": None,
        "tip_payout_id": None,
        "supplier_payment_id": None,
    }
    source_fields[f"{source_type}_id"] = source_id
    correction = FinanceSourceCorrection(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=source.branch_id,
        source_type=source_type,
        original_shift_id=original_shift.id,
        settlement_shift_id=settlement_shift.id,
        amount_minor=int(source.amount_minor),
        idempotency_key=idempotency_key,
        request_hash=request_hash,
        corrected_by=tenant.user_id,
        reason=reason,
        **source_fields,
    )
    session.add(correction)
    await session.flush()

    cash_delta = {
        "expense": int(source.amount_minor),
        "manual_collection": -int(source.amount_minor),
        "tip_payout": int(source.amount_minor),
        "supplier_payment": int(source.amount_minor),
    }[source_type]
    await _enqueue_finance_source_mirror(
        session,
        company_id=tenant.company_id,
        branch_id=source.branch_id,
        actor_user_id=tenant.user_id,
        event_type=f"finance.{source_type}.corrected",
        source_type="finance_source_correction",
        source_id=correction.id,
        source_revision="correction-v1",
        occurred_at=correction.corrected_at,
        reference=_short_finance_reference("COR", correction.id),
        description=f"{source_type.replace('_', ' ').title()} correction",
        amount_minor=cash_delta,
        payment_method="cash",
        status_label="corrected",
        identifiers={
            "finance_source_correction_id": str(correction.id),
            "finance_source_id": str(source_id),
            "branch_id": str(source.branch_id),
            "original_shift_id": str(original_shift.id),
            "settlement_shift_id": str(settlement_shift.id),
        },
    )
    response = _finance_correction_read(correction)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/expenses/{source_id}/corrections",
    response_model=FinanceSourceCorrectionRead,
    status_code=status.HTTP_201_CREATED,
)
async def correct_closed_shift_expense(
    source_id: UUID,
    payload: FinanceSourceCorrectionCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> FinanceSourceCorrectionRead:
    return await _create_finance_source_correction(
        source_type="expense",
        source_id=source_id,
        payload=payload,
        session=session,
        request=request,
        tenant=tenant,
    )


@router.post(
    "/manual-collections/{source_id}/corrections",
    response_model=FinanceSourceCorrectionRead,
    status_code=status.HTTP_201_CREATED,
)
async def correct_closed_shift_manual_collection(
    source_id: UUID,
    payload: FinanceSourceCorrectionCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> FinanceSourceCorrectionRead:
    return await _create_finance_source_correction(
        source_type="manual_collection",
        source_id=source_id,
        payload=payload,
        session=session,
        request=request,
        tenant=tenant,
    )


@router.post(
    "/tip-payouts/{source_id}/corrections",
    response_model=FinanceSourceCorrectionRead,
    status_code=status.HTTP_201_CREATED,
)
async def correct_closed_shift_tip_payout(
    source_id: UUID,
    payload: FinanceSourceCorrectionCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> FinanceSourceCorrectionRead:
    return await _create_finance_source_correction(
        source_type="tip_payout",
        source_id=source_id,
        payload=payload,
        session=session,
        request=request,
        tenant=tenant,
    )


@router.post(
    "/supplier-payments/{source_id}/corrections",
    response_model=FinanceSourceCorrectionRead,
    status_code=status.HTTP_201_CREATED,
)
async def correct_closed_shift_supplier_payment(
    source_id: UUID,
    payload: FinanceSourceCorrectionCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> FinanceSourceCorrectionRead:
    return await _create_finance_source_correction(
        source_type="supplier_payment",
        source_id=source_id,
        payload=payload,
        session=session,
        request=request,
        tenant=tenant,
    )


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


def _require_finance_action_key(key: str, *, prefix: str) -> None:
    expected = f"{prefix}:"
    if not key.startswith(expected):
        raise BusinessRuleError(
            f"Idempotency-Key must use the {expected}<uuid> action format"
        )
    try:
        parsed = UUID(key[len(expected) :])
    except ValueError as exc:
        raise BusinessRuleError(
            f"Idempotency-Key must use the {expected}<uuid> action format"
        ) from exc
    if str(parsed) != key[len(expected) :].lower():
        raise BusinessRuleError(
            f"Idempotency-Key must use a canonical lowercase UUID after {expected}"
        )


async def _lock_finance_cash_shift(
    session: SessionDep,
    *,
    shift_id: UUID | None,
    method: str,
    company_id: UUID,
    branch_id: UUID,
    incoming: bool,
    amount_minor: int,
) -> Shift | None:
    """Validate and lock the exact drawer used by a live finance cash fact."""

    if method != "cash":
        if shift_id is not None:
            raise BusinessRuleError("Only cash entries can name a shift drawer.")
        return None
    if shift_id is None:
        raise BusinessRuleError("Cash entries require selecting an open shift drawer.")
    shift = (
        await session.execute(
            select(Shift)
            .where(Shift.id == shift_id, Shift.company_id == company_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if shift is None or shift.branch_id != branch_id:
        raise NotFoundError("shift not found")
    if shift.status != "open":
        raise BusinessRuleError("Select a currently open shift for this cash entry.")
    if not incoming and int(shift.expected_minor or 0) < amount_minor:
        raise BusinessRuleError(
            "This cash entry exceeds the expected cash in the selected drawer."
        )
    return shift


def _manual_collection_read(
    row: ManualCollection,
    *,
    created_by_name: str | None = None,
    voided_by_name: str | None = None,
    source_shift_status: str | None = None,
    correction: FinanceSourceCorrection | None = None,
) -> ManualCollectionRead:
    return ManualCollectionRead(
        id=row.id,
        company_id=row.company_id,
        branch_id=row.branch_id,
        shift_id=row.shift_id,
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
        source_shift_status=source_shift_status,
        is_corrected=correction is not None,
        correction=_finance_correction_read(correction) if correction else None,
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
    corrections, shift_statuses = await _finance_source_context(
        session,
        source_type="manual_collection",
        source_ids=[row.id for row, _creator, _voider in rows],
        shift_ids=[row.shift_id for row, _creator, _voider in rows],
    )
    return [
        _manual_collection_read(
            row,
            created_by_name=created_by_name,
            voided_by_name=voided_by_name,
            source_shift_status=(
                shift_statuses.get(row.shift_id) if row.shift_id else None
            ),
            correction=corrections.get(row.id),
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
    background_tasks: BackgroundTasks,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> ManualCollectionRead:
    """Record tax-unsplit daily revenue that bypassed itemized POS billing.

    This is intentionally unavailable to GST-registered companies: an
    aggregate payment-method total cannot establish taxable value, tax rate,
    HSN/SAC, or invoice-level GST.  Such companies must enter itemized sales.
    """
    idempotency_key, request_hash = _require_manual_collection_idempotency(request)
    _require_finance_action_key(idempotency_key, prefix="manual-collection")
    if not tenant.in_branch(payload.branch_id):
        raise NotFoundError("branch not found")
    durable_replay = (
        await session.execute(
            select(ManualCollection)
            .where(
                ManualCollection.company_id == tenant.company_id,
                ManualCollection.idempotency_key == idempotency_key,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if durable_replay is not None:
        if not tenant.in_branch(durable_replay.branch_id):
            raise NotFoundError("manual collection not found")
        if (
            durable_replay.request_hash != request_hash
            or durable_replay.created_by != tenant.user_id
        ):
            raise IdempotencyConflict(
                "Idempotency-Key reused with a different manual collection or actor",
                details={"key": idempotency_key},
        )
        creator = await session.get(User, durable_replay.created_by)
        voider = (
            await session.get(User, durable_replay.voided_by)
            if durable_replay.voided_by
            else None
        )
        return _manual_collection_read(
            durable_replay,
            created_by_name=creator.name if creator else None,
            voided_by_name=voider.name if voider else None,
        )
    await _lock_finance_cash_shift(
        session,
        shift_id=payload.shift_id,
        method=payload.method,
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        incoming=True,
        amount_minor=payload.amount_minor,
    )
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        return ManualCollectionRead.model_validate(replay["body"])

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
        shift_id=payload.shift_id,
        business_date=payload.business_date,
        method=payload.method,
        amount_minor=payload.amount_minor,
        source_kind=payload.source_kind,
        source_ref=source_ref,
        note=note,
        idempotency_key=idempotency_key,
        request_hash=request_hash,
        created_by=tenant.user_id,
        source_integrity_revision=1,
    )
    session.add(row)
    await session.flush()
    creator = await session.get(User, tenant.user_id)
    await _enqueue_finance_source_mirror(
        session,
        company_id=tenant.company_id,
        branch_id=row.branch_id,
        actor_user_id=row.created_by,
        event_type="finance.manual_collection.recorded",
        source_type="manual_collection",
        source_id=row.id,
        source_revision="created-v1",
        occurred_at=row.created_at,
        reference=_short_finance_reference("MAN", row.id),
        description="Manual daily collection",
        amount_minor=int(row.amount_minor),
        payment_method=row.method,
        status_label="recorded",
        identifiers={
            "manual_collection_id": str(row.id),
            "branch_id": str(row.branch_id),
            "source_kind": row.source_kind,
            "period_start": row.business_date.isoformat(),
            "period_end": row.business_date.isoformat(),
        },
    )
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
    probe = (
        await session.execute(
            select(
                ManualCollection.branch_id,
                ManualCollection.shift_id,
                ManualCollection.method,
                ManualCollection.source_integrity_revision,
                ManualCollection.voided_at,
            ).where(
                ManualCollection.id == collection_id,
                ManualCollection.company_id == tenant.company_id,
            )
        )
    ).one_or_none()
    if probe is None or not tenant.in_branch(probe.branch_id):
        raise NotFoundError("manual collection not found")
    if (
        probe.voided_at is None
        and probe.method == "cash"
        and probe.source_integrity_revision == 1
        and probe.shift_id is not None
    ):
        await session.execute(
            select(Shift.id).where(Shift.id == probe.shift_id).with_for_update()
        )
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

    correction = (
        await session.execute(
            select(FinanceSourceCorrection).where(
                FinanceSourceCorrection.manual_collection_id == row.id
            )
        )
    ).scalar_one_or_none()
    if correction is not None:
        raise BusinessRuleError("A corrected manual collection cannot also be voided.")
    if (
        row.voided_at is None
        and row.method == "cash"
        and row.source_integrity_revision == 1
        and row.shift_id is not None
    ):
        shift = await session.get(Shift, row.shift_id)
        if shift is None or shift.status != "open":
            raise BusinessRuleError(
                "This collection belongs to a closed shift. Use a current-period "
                "cash correction and select an open same-branch shift."
            )

    reason = payload.reason.strip()
    if len(reason) < 3:
        raise BusinessRuleError("void reason must contain at least 3 characters")
    newly_voided = False
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
        newly_voided = True

    creator = await session.get(User, row.created_by)
    voider = await session.get(User, row.voided_by) if row.voided_by else None
    if newly_voided:
        assert row.voided_at is not None
        await _enqueue_finance_source_mirror(
            session,
            company_id=tenant.company_id,
            branch_id=row.branch_id,
            actor_user_id=row.voided_by,
            event_type="finance.manual_collection.voided",
            source_type="manual_collection",
            source_id=row.id,
            source_revision="void-v1",
            occurred_at=row.voided_at,
            reference=_short_finance_reference("MAN", row.id),
            description="Manual collection reversal",
            amount_minor=-int(row.amount_minor),
            payment_method=row.method,
            status_label="voided",
            identifiers={
                "manual_collection_id": str(row.id),
                "branch_id": str(row.branch_id),
                "source_kind": row.source_kind,
                "period_start": row.business_date.isoformat(),
                "period_end": row.business_date.isoformat(),
            },
        )
    return _manual_collection_read(
        row,
        created_by_name=creator.name if creator else None,
        voided_by_name=voider.name if voider else None,
    )


# ============================================================================
# TIP PAYOUTS
#
# The only write path that ever debits TIPS_PAYABLE outside a refund (see
# ledger.py). Deliberately standalone — not the Payroll feature: it records
# "we paid out ₹X in tips to staff" as one lump sum with a note, not a
# per-staff-member breakdown or shift/roster link.
# ============================================================================
def _require_tip_payout_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for tip payout writes")
    return str(key), str(request_hash)


def _tip_payout_read(
    row: TipPayout,
    *,
    created_by_name: str | None = None,
    voided_by_name: str | None = None,
    source_shift_status: str | None = None,
    correction: FinanceSourceCorrection | None = None,
) -> TipPayoutRead:
    return TipPayoutRead(
        id=row.id,
        company_id=row.company_id,
        branch_id=row.branch_id,
        shift_id=row.shift_id,
        amount_minor=int(row.amount_minor),
        method=row.method,
        paid_at=row.paid_at,
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
        source_shift_status=source_shift_status,
        is_corrected=correction is not None,
        correction=_finance_correction_read(correction) if correction else None,
    )


@router.get("/tip-payouts", response_model=list[TipPayoutRead])
async def list_tip_payouts(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    branch_id: UUID | None = None,
    include_voided: bool = True,
    limit: int = Query(default=200, ge=1, le=500),
) -> list[TipPayoutRead]:
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
        select(TipPayout, creator.name, voider.name)
        .outerjoin(creator, creator.id == TipPayout.created_by)
        .outerjoin(voider, voider.id == TipPayout.voided_by)
        .where(TipPayout.company_id == tenant.company_id)
    )
    if scoped_branch_id is not None:
        stmt = stmt.where(TipPayout.branch_id == scoped_branch_id)
    if not include_voided:
        stmt = stmt.where(TipPayout.voided_at.is_(None))
    stmt = stmt.order_by(
        TipPayout.paid_at.desc(),
        TipPayout.created_at.desc(),
        TipPayout.id.desc(),
    ).limit(limit)

    rows = (await session.execute(stmt)).all()
    corrections, shift_statuses = await _finance_source_context(
        session,
        source_type="tip_payout",
        source_ids=[row.id for row, _creator, _voider in rows],
        shift_ids=[row.shift_id for row, _creator, _voider in rows],
    )
    return [
        _tip_payout_read(
            row,
            created_by_name=created_by_name,
            voided_by_name=voided_by_name,
            source_shift_status=(
                shift_statuses.get(row.shift_id) if row.shift_id else None
            ),
            correction=corrections.get(row.id),
        )
        for row, created_by_name, voided_by_name in rows
    ]


@router.post(
    "/tip-payouts",
    response_model=TipPayoutRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_tip_payout(
    payload: TipPayoutCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> TipPayoutRead:
    """Record tips actually paid out to staff, debiting TIPS_PAYABLE.

    This does not attribute the payout to individual staff members or
    shifts — `note` (e.g. "split among staff on shift") is the only record
    of how it was distributed until the full Payroll feature exists.
    """
    idempotency_key, request_hash = _require_tip_payout_idempotency(request)
    _require_finance_action_key(idempotency_key, prefix="tip-payout")
    if not tenant.in_branch(payload.branch_id):
        raise NotFoundError("branch not found")
    durable_replay = (
        await session.execute(
            select(TipPayout)
            .where(
                TipPayout.company_id == tenant.company_id,
                TipPayout.idempotency_key == idempotency_key,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if durable_replay is not None:
        if not tenant.in_branch(durable_replay.branch_id):
            raise NotFoundError("tip payout not found")
        if (
            durable_replay.request_hash != request_hash
            or durable_replay.created_by != tenant.user_id
        ):
            raise IdempotencyConflict(
                "Idempotency-Key reused with a different tip payout or actor",
                details={"key": idempotency_key},
        )
        creator = await session.get(User, durable_replay.created_by)
        voider = (
            await session.get(User, durable_replay.voided_by)
            if durable_replay.voided_by
            else None
        )
        return _tip_payout_read(
            durable_replay,
            created_by_name=creator.name if creator else None,
            voided_by_name=voider.name if voider else None,
        )
    if payload.paid_at > datetime.now(timezone.utc):
        raise BusinessRuleError(
            "Tip payout time cannot be in the future. Enter when the money was "
            "actually paid to staff, then try again."
        )
    note = payload.note.strip()
    if len(note) < 3:
        raise BusinessRuleError("note must contain at least 3 characters")
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        return TipPayoutRead.model_validate(replay["body"])
    # Tips Payable is a company-wide balance. Lock that common owner row
    # before reading the ledger so concurrent payouts, including ones from
    # different branches, cannot both spend the same outstanding amount.
    # SQLAlchemy's ``key_share=True`` with its default ``read=False`` compiles
    # to PostgreSQL FOR NO KEY UPDATE, a writer-conflicting lock (not the
    # mutually compatible FOR KEY SHARE mode).
    branch = (
        await session.execute(
            select(Branch).join(Company, Company.id == Branch.company_id).where(
                Branch.id == payload.branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
                Company.deleted_at.is_(None),
            ).with_for_update(of=Company, key_share=True)
        )
    ).scalar_one_or_none()
    if not branch:
        raise NotFoundError("branch not found")

    await _lock_finance_cash_shift(
        session,
        shift_id=payload.shift_id,
        method=payload.method,
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        incoming=False,
        amount_minor=payload.amount_minor,
    )

    ledger = await build_operational_ledger(
        session, company_id=tenant.company_id, end_exclusive=datetime.now(timezone.utc)
    )
    outstanding_tips_minor = sum(
        line.credit_minor - line.debit_minor
        for line in ledger
        if line.account_code == TIPS_PAYABLE.code
    )
    if payload.amount_minor > outstanding_tips_minor:
        raise BusinessRuleError(
            f"Payout of ₹{Decimal(payload.amount_minor) / 100:.2f} exceeds the "
            f"₹{Decimal(outstanding_tips_minor) / 100:.2f} currently owed to staff "
            "in Tips Payable. Refresh Tip payouts and review previous payouts "
            "before entering a lower amount."
        )

    row = TipPayout(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        shift_id=payload.shift_id,
        amount_minor=payload.amount_minor,
        method=payload.method,
        paid_at=payload.paid_at,
        note=note,
        idempotency_key=idempotency_key,
        request_hash=request_hash,
        created_by=tenant.user_id,
        source_integrity_revision=1,
    )
    session.add(row)
    await session.flush()

    creator = await session.get(User, tenant.user_id)
    await _enqueue_finance_source_mirror(
        session,
        company_id=row.company_id,
        branch_id=row.branch_id,
        actor_user_id=row.created_by,
        event_type="finance.tip_payout.recorded",
        source_type="tip_payout",
        source_id=row.id,
        source_revision="recorded-v1",
        occurred_at=row.paid_at,
        reference=_short_finance_reference("TIP", row.id),
        description="Tip payout to staff",
        amount_minor=-int(row.amount_minor),
        payment_method=row.method,
        status_label="recorded",
        identifiers={
            "tip_payout_id": str(row.id),
            "branch_id": str(row.branch_id),
        },
    )
    response = _tip_payout_read(row, created_by_name=creator.name if creator else None)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/tip-payouts/{payout_id}/void",
    response_model=TipPayoutRead,
)
async def void_tip_payout(
    payout_id: UUID,
    payload: TipPayoutVoid,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> TipPayoutRead:
    """Void a tip payout without deleting or overwriting its original data."""
    probe = (
        await session.execute(
            select(
                TipPayout.branch_id,
                TipPayout.shift_id,
                TipPayout.method,
                TipPayout.source_integrity_revision,
                TipPayout.voided_at,
            ).where(
                TipPayout.id == payout_id,
                TipPayout.company_id == tenant.company_id,
            )
        )
    ).one_or_none()
    if probe is None or not tenant.in_branch(probe.branch_id):
        raise NotFoundError("tip payout not found")
    await session.execute(
        select(Company.id)
        .where(Company.id == tenant.company_id)
        .with_for_update(key_share=True)
    )
    if (
        probe.voided_at is None
        and probe.method == "cash"
        and probe.source_integrity_revision == 1
        and probe.shift_id is not None
    ):
        await session.execute(
            select(Shift.id).where(Shift.id == probe.shift_id).with_for_update()
        )
    row = (
        await session.execute(
            select(TipPayout)
            .where(
                TipPayout.id == payout_id,
                TipPayout.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not row or not tenant.in_branch(row.branch_id):
        raise NotFoundError("tip payout not found")

    correction = (
        await session.execute(
            select(FinanceSourceCorrection).where(
                FinanceSourceCorrection.tip_payout_id == row.id
            )
        )
    ).scalar_one_or_none()
    if correction is not None:
        raise BusinessRuleError("A corrected tip payout cannot also be voided.")
    if (
        row.voided_at is None
        and row.method == "cash"
        and row.source_integrity_revision == 1
        and row.shift_id is not None
    ):
        shift = await session.get(Shift, row.shift_id)
        if shift is None or shift.status != "open":
            raise BusinessRuleError(
                "This payout belongs to a closed shift. Use a current-period cash "
                "correction and select an open same-branch shift."
            )

    reason = payload.reason.strip()
    if len(reason) < 3:
        raise BusinessRuleError("void reason must contain at least 3 characters")
    newly_voided = False
    if row.voided_at is not None:
        if row.void_reason != reason:
            raise BusinessRuleError("tip payout is already voided with a different reason")
    else:
        row.voided_at = datetime.now(timezone.utc)
        row.voided_by = tenant.user_id
        row.void_reason = reason
        await session.flush()
        newly_voided = True

    creator = await session.get(User, row.created_by)
    voider = await session.get(User, row.voided_by) if row.voided_by else None
    if newly_voided:
        assert row.voided_at is not None
        assert row.voided_by is not None
        await _enqueue_finance_source_mirror(
            session,
            company_id=row.company_id,
            branch_id=row.branch_id,
            actor_user_id=row.voided_by,
            event_type="finance.tip_payout.voided",
            source_type="tip_payout",
            source_id=row.id,
            source_revision="void-v1",
            occurred_at=row.voided_at,
            reference=_short_finance_reference("TIP", row.id),
            description="Tip payout reversal",
            amount_minor=int(row.amount_minor),
            payment_method=row.method,
            status_label="voided",
            identifiers={
                "tip_payout_id": str(row.id),
                "branch_id": str(row.branch_id),
            },
        )
    return _tip_payout_read(
        row,
        created_by_name=creator.name if creator else None,
        voided_by_name=voider.name if voider else None,
    )


# ============================================================================
# SUPPLIER PAYMENTS / ACCOUNTS PAYABLE SETTLEMENT
# ============================================================================
def _supplier_payment_read(
    row: SupplierPayment,
    *,
    source_shift_status: str | None = None,
    correction: FinanceSourceCorrection | None = None,
) -> SupplierPaymentRead:
    return SupplierPaymentRead(
        id=row.id,
        company_id=row.company_id,
        branch_id=row.branch_id,
        shift_id=row.shift_id,
        supplier_id=row.supplier_id,
        grn_id=row.grn_id,
        journal_entry_id=row.journal_entry_id,
        amount_minor=int(row.amount_minor),
        method=row.method,
        paid_at=row.paid_at,
        payment_reference=row.payment_reference,
        note=row.note,
        idempotency_key=row.idempotency_key,
        created_by=row.created_by,
        created_at=row.created_at,
        voided_at=row.voided_at,
        voided_by=row.voided_by,
        void_reason=row.void_reason,
        is_voided=row.voided_at is not None,
        source_shift_status=source_shift_status,
        is_corrected=correction is not None,
        correction=_finance_correction_read(correction) if correction else None,
    )


async def _grn_accounting_total_minor(session: SessionDep, grn: GRN) -> int:
    lines = (
        await session.execute(
            select(GRNLine)
            .where(GRNLine.grn_id == grn.id)
            .order_by(GRNLine.created_at, GRNLine.id)
        )
    ).scalars().all()
    if not lines:
        raise BusinessRuleError("GRN has no received lines; supplier payment is blocked")
    total_minor = sum(
        received_line_total_minor(line.qty_received, line.cost_per_unit_minor)
        for line in lines
    )
    require_invoice_matches_capitalised_total(
        supplier_invoice_amount_minor=grn.supplier_invoice_amount_minor,
        capitalised_total_minor=total_minor,
    )
    return total_minor


@router.get("/supplier-payments", response_model=list[SupplierPaymentRead])
async def list_supplier_payments(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
    branch_id: UUID | None = None,
    grn_id: UUID | None = None,
    supplier_id: UUID | None = None,
    include_voided: bool = True,
    limit: int = Query(default=200, ge=1, le=500),
) -> list[SupplierPaymentRead]:
    scoped_branch_id = branch_id or tenant.branch_id
    if scoped_branch_id is not None:
        if not tenant.in_branch(scoped_branch_id):
            raise NotFoundError("branch not found")
        branch = await session.get(Branch, scoped_branch_id)
        if not branch or branch.company_id != tenant.company_id or branch.deleted_at:
            raise NotFoundError("branch not found")

    stmt = select(SupplierPayment).where(
        SupplierPayment.company_id == tenant.company_id
    )
    if scoped_branch_id is not None:
        stmt = stmt.where(SupplierPayment.branch_id == scoped_branch_id)
    if grn_id is not None:
        stmt = stmt.where(SupplierPayment.grn_id == grn_id)
    if supplier_id is not None:
        stmt = stmt.where(SupplierPayment.supplier_id == supplier_id)
    if not include_voided:
        stmt = stmt.where(SupplierPayment.voided_at.is_(None))
    rows = (
        await session.execute(
            stmt.order_by(
                SupplierPayment.paid_at.desc(),
                SupplierPayment.created_at.desc(),
                SupplierPayment.id.desc(),
            ).limit(limit)
        )
    ).scalars().all()
    corrections, shift_statuses = await _finance_source_context(
        session,
        source_type="supplier_payment",
        source_ids=[row.id for row in rows],
        shift_ids=[row.shift_id for row in rows],
    )
    return [
        _supplier_payment_read(
            row,
            source_shift_status=(
                shift_statuses.get(row.shift_id) if row.shift_id else None
            ),
            correction=corrections.get(row.id),
        )
        for row in rows
    ]


@router.post(
    "/supplier-payments",
    response_model=SupplierPaymentRead,
    status_code=status.HTTP_201_CREATED,
)
async def create_supplier_payment(
    payload: SupplierPaymentCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> SupplierPaymentRead:
    """Settle part or all of one GRN's Accounts Payable balance.

    Locking the GRN serializes every payment for that receipt. Concurrent
    requests with different idempotency keys cannot both observe the same AP
    balance and overpay it; replay with the same key returns the durable source
    row even if the generic response cache has been pruned.
    """

    idempotency_key, request_hash = _require_idempotency(
        request, what="supplier payment"
    )
    _require_finance_action_key(idempotency_key, prefix="supplier-payment")
    if not tenant.in_branch(payload.branch_id):
        raise NotFoundError("branch not found")
    # The immutable source row is the durable replay receipt. Read it before
    # validating the currently-open drawer so a valid retry still succeeds
    # after that original drawer has closed.
    durable_replay = (
        await session.execute(
            select(SupplierPayment).where(
                SupplierPayment.company_id == tenant.company_id,
                SupplierPayment.idempotency_key == idempotency_key,
            )
        )
    ).scalar_one_or_none()
    if durable_replay is not None:
        if not tenant.in_branch(durable_replay.branch_id):
            raise NotFoundError("supplier payment not found")
        if (
            durable_replay.request_hash != request_hash
            or durable_replay.created_by != tenant.user_id
        ):
            raise IdempotencyConflict(
                "Idempotency-Key reused with different supplier payment payload or actor",
                details={"key": idempotency_key},
        )
        return _supplier_payment_read(durable_replay)

    # New writes use one lock hierarchy everywhere: drawer, then GRN, then
    # supplier payment. This cannot deadlock with shift close or correction.
    await _lock_finance_cash_shift(
        session,
        shift_id=payload.shift_id,
        method=payload.method,
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        incoming=False,
        amount_minor=payload.amount_minor,
    )
    await session.execute(
        select(GRN.id)
        .join(PurchaseOrder, PurchaseOrder.id == GRN.purchase_order_id)
        .where(
            GRN.id == payload.grn_id,
            PurchaseOrder.company_id == tenant.company_id,
        )
        .with_for_update(of=GRN)
    )

    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        return SupplierPaymentRead.model_validate(replay["body"])

    if payload.paid_at.tzinfo is None or payload.paid_at.utcoffset() is None:
        raise BusinessRuleError("supplier payment paid_at must include a timezone")

    source = (
        await session.execute(
            select(GRN, PurchaseOrder, Supplier)
            .join(PurchaseOrder, PurchaseOrder.id == GRN.purchase_order_id)
            .join(Supplier, Supplier.id == PurchaseOrder.supplier_id)
            .where(GRN.id == payload.grn_id)
        )
    ).one_or_none()
    if source is None:
        raise NotFoundError("GRN not found")
    grn, purchase_order, supplier = source
    if (
        purchase_order.company_id != tenant.company_id
        or purchase_order.branch_id != payload.branch_id
        or purchase_order.supplier_id != payload.supplier_id
        or supplier.company_id != tenant.company_id
        or supplier.deleted_at is not None
    ):
        raise NotFoundError("GRN not found")
    if payload.paid_at < grn.received_at:
        raise BusinessRuleError("supplier payment cannot predate the GRN receipt")

    receipt_total_minor = await _grn_accounting_total_minor(session, grn)
    if receipt_total_minor <= 0 or grn.journal_entry_id is None:
        raise BusinessRuleError("this GRN has no posted Accounts Payable balance")
    paid_minor = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(SupplierPayment.amount_minor), 0)).where(
                    SupplierPayment.company_id == tenant.company_id,
                    SupplierPayment.grn_id == grn.id,
                    SupplierPayment.voided_at.is_(None),
                    ~exists(
                        select(FinanceSourceCorrection.id).where(
                            FinanceSourceCorrection.supplier_payment_id
                            == SupplierPayment.id
                        )
                    ),
                )
            )
        ).scalar_one()
        or 0
    )
    outstanding_minor = receipt_total_minor - paid_minor
    if outstanding_minor <= 0:
        raise BusinessRuleError("this GRN is already fully paid")
    if payload.amount_minor > outstanding_minor:
        raise BusinessRuleError(
            f"supplier payment exceeds the outstanding Accounts Payable balance "
            f"of {outstanding_minor} minor units"
        )

    payment_id = uuid4()
    reference = payload.payment_reference.strip()
    if not reference:
        raise BusinessRuleError("supplier payment reference cannot be blank")
    note = payload.note.strip() if payload.note else None
    journal = await post_two_sided_operational_journal(
        session,
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        ref_type="supplier_payment",
        ref_id=payment_id,
        posted_at=payload.paid_at,
        amount_minor=payload.amount_minor,
        debit_account=ACCOUNTS_PAYABLE,
        credit_account=CASH if payload.method == "cash" else BANK,
        memo=f"Supplier payment {reference}",
    )
    if journal is None:  # payload validation already rejects zero; fail closed if drifted.
        raise BusinessRuleError("supplier payment did not create a journal")
    row = SupplierPayment(
        id=payment_id,
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        shift_id=payload.shift_id,
        supplier_id=payload.supplier_id,
        grn_id=payload.grn_id,
        journal_entry_id=journal.id,
        amount_minor=payload.amount_minor,
        method=payload.method,
        paid_at=payload.paid_at,
        payment_reference=reference,
        note=note,
        idempotency_key=idempotency_key,
        request_hash=request_hash,
        created_by=tenant.user_id,
        source_integrity_revision=1,
    )
    session.add(row)
    await session.flush()
    await _enqueue_finance_source_mirror(
        session,
        company_id=row.company_id,
        branch_id=row.branch_id,
        actor_user_id=row.created_by,
        event_type="finance.supplier_payment.recorded",
        source_type="supplier_payment",
        source_id=row.id,
        source_revision="recorded-v1",
        occurred_at=row.paid_at,
        reference=_short_finance_reference("GRN", row.grn_id),
        description="Supplier payment",
        amount_minor=-int(row.amount_minor),
        payment_method=row.method,
        status_label="recorded",
        identifiers={
            "supplier_payment_id": str(row.id),
            "supplier_id": str(row.supplier_id),
            "grn_id": str(row.grn_id),
            "journal_entry_id": str(row.journal_entry_id),
            "branch_id": str(row.branch_id),
        },
    )
    response = _supplier_payment_read(row)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.post(
    "/supplier-payments/{payment_id}/void",
    response_model=SupplierPaymentRead,
)
async def void_supplier_payment(
    payment_id: UUID,
    payload: SupplierPaymentVoid,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.write")),
) -> SupplierPaymentRead:
    probe = (
        await session.execute(
            select(
                SupplierPayment.grn_id,
                SupplierPayment.shift_id,
                SupplierPayment.method,
                SupplierPayment.source_integrity_revision,
                SupplierPayment.voided_at,
                SupplierPayment.branch_id,
            ).where(
                SupplierPayment.id == payment_id,
                SupplierPayment.company_id == tenant.company_id,
            )
        )
    ).one_or_none()
    if probe is None or not tenant.in_branch(probe.branch_id):
        raise NotFoundError("supplier payment not found")
    # Match create's lock hierarchy: Shift, GRN, then its settlement source.
    if (
        probe.voided_at is None
        and probe.method == "cash"
        and probe.source_integrity_revision == 1
        and probe.shift_id is not None
    ):
        await session.execute(
            select(Shift.id).where(Shift.id == probe.shift_id).with_for_update()
        )
    await session.execute(
        select(GRN.id).where(GRN.id == probe.grn_id).with_for_update()
    )
    row = (
        await session.execute(
            select(SupplierPayment)
            .where(
                SupplierPayment.id == payment_id,
                SupplierPayment.company_id == tenant.company_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if row is None or not tenant.in_branch(row.branch_id):
        raise NotFoundError("supplier payment not found")

    correction = (
        await session.execute(
            select(FinanceSourceCorrection).where(
                FinanceSourceCorrection.supplier_payment_id == row.id
            )
        )
    ).scalar_one_or_none()
    if correction is not None:
        raise BusinessRuleError("A corrected supplier payment cannot also be voided.")
    if (
        row.voided_at is None
        and row.method == "cash"
        and row.source_integrity_revision == 1
        and row.shift_id is not None
    ):
        shift = await session.get(Shift, row.shift_id)
        if shift is None or shift.status != "open":
            raise BusinessRuleError(
                "This payment belongs to a closed shift. Use a current-period cash "
                "correction and select an open same-branch shift."
            )

    reason = payload.reason.strip()
    if row.voided_at is not None:
        if row.void_reason != reason:
            raise BusinessRuleError(
                "supplier payment is already voided with a different reason"
            )
        return _supplier_payment_read(row)

    journal = (
        await session.execute(
            select(JournalEntry)
            .where(
                JournalEntry.id == row.journal_entry_id,
                JournalEntry.company_id == tenant.company_id,
                JournalEntry.branch_id == row.branch_id,
                JournalEntry.ref_type == "supplier_payment",
                JournalEntry.ref_id == row.id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if journal is None or journal.voided_at is not None:
        raise BusinessRuleError(
            "supplier payment journal is missing or already changed; owner reconciliation required"
        )

    voided_at = datetime.now(timezone.utc)
    row.voided_at = voided_at
    row.voided_by = tenant.user_id
    row.void_reason = reason
    journal.voided_at = voided_at
    journal.voided_by = tenant.user_id
    journal.void_reason = reason
    await session.flush()
    assert row.voided_at is not None
    assert row.voided_by is not None
    await _enqueue_finance_source_mirror(
        session,
        company_id=row.company_id,
        branch_id=row.branch_id,
        actor_user_id=row.voided_by,
        event_type="finance.supplier_payment.voided",
        source_type="supplier_payment",
        source_id=row.id,
        source_revision="void-v1",
        occurred_at=row.voided_at,
        reference=_short_finance_reference("GRN", row.grn_id),
        description="Supplier payment reversal",
        amount_minor=int(row.amount_minor),
        payment_method=row.method,
        status_label="voided",
        identifiers={
            "supplier_payment_id": str(row.id),
            "supplier_id": str(row.supplier_id),
            "grn_id": str(row.grn_id),
            "journal_entry_id": str(row.journal_entry_id),
            "branch_id": str(row.branch_id),
        },
    )
    return _supplier_payment_read(row)


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
    _require_company_wide_finance(tenant, subject="Partner records")
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
    _require_company_wide_finance(tenant, subject="Partner records")
    # The database trigger repeats this company lock and aggregate invariant
    # for native/bulk writers. Lock here as well for a deterministic API error.
    company = (
        await session.execute(
            select(Company)
            .where(Company.id == tenant.company_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if company is None or company.deleted_at is not None:
        raise NotFoundError("company not found")
    if payload.user_id is not None:
        user = await session.get(User, payload.user_id)
        if not user or user.company_id != tenant.company_id or user.deleted_at:
            raise NotFoundError("user not found")
    existing_share_total = Decimal(
        str(
            (
                await session.execute(
                    select(func.coalesce(func.sum(Partner.share_pct), 0)).where(
                        Partner.company_id == tenant.company_id
                    )
                )
            ).scalar_one()
        )
    )
    requested_share = Decimal(str(payload.share_pct))
    if existing_share_total + requested_share > Decimal(100):
        configured = format(existing_share_total.normalize(), "f")
        raise BusinessRuleError(
            f"Partner ownership shares already total {configured}%; adding "
            f"{format(requested_share.normalize(), 'f')}% would exceed 100%."
        )
    name = payload.name.strip()
    if not name:
        raise BusinessRuleError("partner name cannot be blank")
    values = payload.model_dump()
    values["name"] = name
    p = Partner(
        id=uuid4(),
        company_id=tenant.company_id,
        **values,
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
    _require_company_wide_finance(tenant, subject="Partner records")
    p = await session.get(Partner, partner_id)
    if not p or p.company_id != tenant.company_id:
        raise NotFoundError("partner not found")
    changes = payload.model_dump(exclude_unset=True)
    if {"name", "share_pct"}.intersection(changes):
        raise BusinessRuleError(
            "Partner name and ownership share are immutable after creation. "
            "Owner reconciliation is required; this ERP does not yet support "
            "effective-dated ownership amendments."
        )
    if "notes" in changes:
        p.notes = changes["notes"]
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
    _require_company_wide_finance(tenant, subject="Partner capital")
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
    request: Request,
    tenant: TenantContext = Depends(requires("finance.partner.write")),
) -> CapitalEntryRead:
    _require_company_wide_finance(tenant, subject="Partner capital")
    idempotency_key, request_hash = _require_idempotency(request, what="capital entry")
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        return CapitalEntryRead.model_validate(replay["body"])

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
    try:
        await session.flush()
    except IntegrityError as exc:
        await session.rollback()
        # The pre-check above is a plain SELECT with no lock on CapitalEntry
        # itself (only Partner is locked) — two concurrent requests citing
        # the same source_ref can both pass it and race to insert. This
        # closes that window the same way Inventory's create_ingredient
        # converts a duplicate-SKU race into a clean ConflictError instead
        # of a raw 500.
        raise ConflictError(
            f"a capital entry with source_ref '{source_ref}' already exists"
        ) from exc
    creator = await session.get(User, tenant.user_id)
    capital_amount_minor = (
        int(ce.amount_minor) if ce.type == "invest" else -int(ce.amount_minor)
    )
    await _enqueue_finance_source_mirror(
        session,
        company_id=tenant.company_id,
        branch_id=None,
        actor_user_id=ce.created_by,
        event_type="finance.capital_entry.recorded",
        source_type="capital_entry",
        source_id=ce.id,
        source_revision="recorded-v1",
        occurred_at=ce.effective_at,
        reference=_short_finance_reference("CAP", ce.id),
        description=(
            "Partner capital investment"
            if ce.type == "invest"
            else "Partner capital withdrawal"
        ),
        amount_minor=capital_amount_minor,
        payment_method=ce.settlement_account,
        status_label=ce.type,
        identifiers={
            "capital_entry_id": str(ce.id),
            "partner_id": str(ce.partner_id),
            "capital_type": ce.type,
        },
    )
    response = _capital_entry_read(
        ce,
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
    _require_company_wide_finance(tenant, subject="Partner capital")
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
    newly_voided = False
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
        newly_voided = True

    creator = await session.get(User, row.created_by) if row.created_by else None
    voider = await session.get(User, row.voided_by) if row.voided_by else None
    if newly_voided:
        assert row.voided_at is not None
        assert row.voided_by is not None
        capital_amount_minor = (
            int(row.amount_minor)
            if row.type == "invest"
            else -int(row.amount_minor)
        )
        await _enqueue_finance_source_mirror(
            session,
            company_id=tenant.company_id,
            branch_id=None,
            actor_user_id=row.voided_by,
            event_type="finance.capital_entry.voided",
            source_type="capital_entry",
            source_id=row.id,
            source_revision="void-v1",
            occurred_at=row.voided_at,
            reference=_short_finance_reference("CAP", row.id),
            description=(
                "Partner capital investment reversal"
                if row.type == "invest"
                else "Partner capital withdrawal reversal"
            ),
            amount_minor=-capital_amount_minor,
            payment_method=row.settlement_account,
            status_label=f"{row.type}_voided",
            identifiers={
                "capital_entry_id": str(row.id),
                "partner_id": str(row.partner_id),
                "capital_type": row.type,
            },
        )
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
        id=a.id, branch_id=a.branch_id, name=a.name, type=a.type,
        purchase_minor=a.purchase_minor, purchase_date=a.purchase_date,
        useful_life_months=a.useful_life_months,
        salvage_minor=a.salvage_minor,
        depreciation_method=a.depreciation_method,
        notes=a.notes,
        accumulated_depreciation_minor=asset_accumulated_depreciation_minor(a, as_of),
        book_value_minor=asset_book_value_minor(a, as_of),
    )


@router.get("/assets", response_model=list[AssetRead])
async def list_assets(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> list[AssetRead]:
    stmt = select(Asset).where(
        Asset.company_id == tenant.company_id,
        Asset.deleted_at.is_(None),
    )
    stmt = _scope_to_tenant_branch(stmt, Asset.branch_id, tenant)
    rows = (await session.execute(stmt)).scalars().all()
    now = datetime.now(timezone.utc)
    return [_asset_read(r, as_of=now) for r in rows]


@router.post("/assets", response_model=AssetRead, status_code=status.HTTP_201_CREATED)
async def create_asset(
    payload: AssetCreate,
    session: SessionDep,
    request: Request,
    # finance.assets.write is the dedicated scope for this ("Add / depreciate
    # assets" in permissions.py) — matching the specific-over-generic pattern
    # create_partner/create_capital_entry already use (finance.partner.write,
    # not the generic finance.write) rather than reusing the expense-record
    # scope for an unrelated register.
    tenant: TenantContext = Depends(requires("finance.assets.write")),
) -> AssetRead:
    idempotency_key, request_hash = _require_idempotency(request, what="asset")
    replay = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=None,
    )
    if replay:
        return AssetRead.model_validate(replay["body"])

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
        source_integrity_revision=50,
        # Only straight_line is computable today (see
        # app/services/accounting/depreciation.py); the create form has no
        # method picker yet, so every asset is explicitly straight_line
        # rather than relying on the column's implicit default.
        depreciation_method=STRAIGHT_LINE,
        **payload.model_dump(),
    )
    session.add(a)
    await session.flush()
    await _enqueue_finance_source_mirror(
        session,
        company_id=a.company_id,
        branch_id=a.branch_id,
        actor_user_id=tenant.user_id,
        event_type="finance.asset.registered",
        source_type="asset",
        source_id=a.id,
        source_revision="registered-v1",
        occurred_at=a.purchase_date,
        reference=_short_finance_reference("ASSET", a.id),
        description=f"Asset registered · {a.name}",
        amount_minor=-int(a.purchase_minor),
        payment_method="",
        status_label="registered",
        identifiers={
            "asset_id": str(a.id),
            "branch_id": str(a.branch_id),
            "asset_type": a.type,
            "purchase_date": a.purchase_date.date().isoformat(),
        },
    )
    response = _asset_read(a, as_of=datetime.now(timezone.utc))
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


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
        branch_id=tenant.branch_id,
    )
    return PLReport(
        accounting_basis="operational_receipt",
        period_start=period_start,
        period_end=period_end,
        revenue_minor=report.net_revenue_minor,
        memberships_minor=report.revenue.memberships_minor,
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
    """Return operational net profit and, only when costed, partner shares.

    Net profit only — construction/setup money the partners put in lives in
    CapitalEntry (type=invest), never in Expense, so it never depresses this
    number. The P&L remains visible as provisional if any historical physical
    sale lacks complete positive-cost FIFO evidence, but its partner amounts
    remain unavailable. A cost-complete negative result still splits by
    share_pct (each partner absorbs their share of a loss).
    """
    _require_company_wide_finance(tenant, subject="Partner profit allocation")
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
        include_costing_confidence=True,
    )
    partners = (
        await session.execute(
            select(Partner)
            .where(Partner.company_id == tenant.company_id)
            .order_by(Partner.name)
        )
    ).scalars().all()
    partner_weights = _authoritative_partner_weights(partners)
    confidence = _allocation_confidence(report)
    allocation_available = confidence.status == "authoritative"
    shares = (
        apportion(report.net_profit_minor, partner_weights)
        if allocation_available
        else [0] * len(partners)
    )
    return PartnerPLReport(
        period_start=period_start,
        period_end=period_end,
        net_profit_minor=report.net_profit_minor,
        allocation_status=confidence.status,
        allocation_unavailable_reason=confidence.reason,
        costing_confidence=confidence,
        partners=[
            PartnerProfitShare(
                partner_id=p.id,
                name=p.name,
                share_pct=float(p.share_pct),
                capital_balance_minor=await _partner_balance(session, p.id),
                profit_share_minor=share,
                authoritative_profit_share_minor=(
                    share if allocation_available else None
                ),
            )
            for p, share in zip(partners, shares, strict=True)
        ],
    )


@router.get("/distributable", response_model=DistributableProfitReport)
async def distributable_profit(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("finance.read")),
) -> DistributableProfitReport:
    """Return the all-time partner distribution cap when it is authoritative.

    Unlike /pnl/partners (one period's paper profit split by share), this
    looks at the whole history: real operating profit since inception, minus
    every withdrawal ever taken, minus a reserve sized off real recent
    running costs — then capped by actual liquid cash on hand, since profit
    on the books and cash in the bank are not the same thing once money is
    tied up in stock, equipment, or provider settlement receivables.
    If historical physical-sale COGS is incomplete, the underlying provisional
    P&L remains visible but every allocation/cap field fails closed.
    """
    _require_company_wide_finance(tenant, subject="Partner distribution capacity")
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
        include_costing_confidence=True,
    )
    confidence = _allocation_confidence(lifetime)
    allocation_available = confidence.status == "authoritative"

    trailing_start = today - timedelta(days=90)
    trailing = await ReportsAggregator(session).aggregate(
        company_id=tenant.company_id,
        period_start=trailing_start,
        period_end=today,
        period="custom",
        label="trailing 90 days",
    )
    avg_monthly_cost_minor = (trailing.cogs_minor + trailing.expense_total_minor) // 3

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
    cash_position, legacy_liquid_cash_minor = _cash_position_from_ledger(
        ledger_lines
    )

    capacity = compute_distributable_capacity(
        lifetime_net_profit_minor=lifetime.net_profit_minor,
        lifetime_withdrawn_minor=lifetime_withdrawn_minor,
        avg_monthly_cost_minor=avg_monthly_cost_minor,
        reserve_months=DISTRIBUTION_RESERVE_MONTHS,
        liquid_cash_minor=cash_position.spendable_cash_bank_minor,
    )
    partner_weights = _authoritative_partner_weights(partners)
    shares = (
        apportion(capacity.safe_to_distribute_minor, partner_weights)
        if allocation_available
        else [0] * len(partners)
    )

    return DistributableProfitReport(
        as_of=today,
        lifetime_net_profit_minor=lifetime.net_profit_minor,
        lifetime_depreciation_minor=lifetime.depreciation_minor,
        lifetime_withdrawn_minor=lifetime_withdrawn_minor,
        reserve_months=DISTRIBUTION_RESERVE_MONTHS,
        avg_monthly_cost_minor=avg_monthly_cost_minor,
        reserve_minor=capacity.reserve_minor,
        liquid_cash_minor=legacy_liquid_cash_minor,
        spendable_cash_bank_minor=cash_position.spendable_cash_bank_minor,
        cash_position=cash_position,
        profit_based_capacity_minor=(
            capacity.profit_based_capacity_minor if allocation_available else 0
        ),
        cash_based_capacity_minor=(
            capacity.cash_based_capacity_minor if allocation_available else 0
        ),
        safe_to_distribute_minor=(
            capacity.safe_to_distribute_minor if allocation_available else 0
        ),
        authoritative_safe_to_distribute_minor=(
            capacity.safe_to_distribute_minor if allocation_available else None
        ),
        allocation_status=confidence.status,
        allocation_unavailable_reason=confidence.reason,
        costing_confidence=confidence,
        partners=[
            DistributablePartnerShare(
                partner_id=p.id,
                name=p.name,
                share_pct=float(p.share_pct),
                capital_balance_minor=await _partner_balance(session, p.id),
                lifetime_withdrawn_minor=withdrawn_by_partner.get(p.id, 0),
                distributable_share_minor=share,
                authoritative_distributable_share_minor=(
                    share if allocation_available else None
                ),
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
        branch_id=tenant.branch_id,
    )

    # Active members follow the current entitlement state, including legacy
    # entitlement-only records that intentionally have no inferred payment.
    # An accepted/settled refund leaves revoked_at set; a cash refund withdrawn
    # because no money left restores revoked_at to NULL and must therefore
    # restore this KPI as well. MRR/ARR is narrower: only a contract that will
    # actually auto-renew *and* has an immutable payment snapshot is recurring
    # revenue. Manual protected-owner terms set auto_renew=False, so they
    # contribute membership revenue without pretending to be SaaS-style MRR.
    now = datetime.now(timezone.utc)
    membership_rows = (
        await session.execute(
            select(
                CustomerMembership.billing_cycle,
                CustomerMembership.auto_renew,
                MembershipPayment.amount_minor,
            )
            .outerjoin(
                MembershipPayment,
                MembershipPayment.membership_id == CustomerMembership.id,
            )
            .join(Customer, Customer.id == CustomerMembership.customer_id)
            .where(
                Customer.company_id == tenant.company_id,
                Customer.deleted_at.is_(None),
                CustomerMembership.starts_at <= now,
                CustomerMembership.expires_at > now,
                CustomerMembership.revoked_at.is_(None),
                *(
                    (MembershipPayment.branch_id == tenant.branch_id,)
                    if tenant.branch_id is not None
                    else ()
                ),
            )
        )
    ).all()
    active_members_count = len(membership_rows)
    mrr_total = 0
    for billing_cycle, auto_renew, paid_amount in membership_rows:
        if not auto_renew:
            continue
        mrr_total += (
            int(paid_amount or 0) // 12
            if billing_cycle == "annual"
            else int(paid_amount or 0)
        )

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
                    *(
                        (Expense.branch_id == tenant.branch_id,)
                        if tenant.branch_id is not None
                        else ()
                    ),
                    Expense.deleted_at.is_(None),
                    Expense.voided_at.is_(None),
                    ExpenseCategory.name == "Marketing",
                    Expense.paid_at >= period_start_at,
                    Expense.paid_at < period_end_exclusive,
                )
            )
        ).scalar_one()
    )
    new_customers_stmt = select(func.count(func.distinct(Customer.id))).where(
        Customer.company_id == tenant.company_id,
        Customer.deleted_at.is_(None),
        Customer.first_visit_at >= period_start_at,
        Customer.first_visit_at < period_end_exclusive,
    )
    if tenant.branch_id is not None:
        # Customer has no branch column. Attribute a new customer to this
        # branch only when it has a settled order here; never use another
        # branch's order or company-wide total_spent projection.
        new_customers_stmt = new_customers_stmt.join(
            Order, Order.customer_id == Customer.id
        ).where(
            Order.company_id == tenant.company_id,
            Order.branch_id == tenant.branch_id,
            Order.status.in_(("paid", "refunded")),
        )
    new_customers_count = int(
        (await session.execute(new_customers_stmt)).scalar_one()
    )

    # LTV is all-time by definition, never period-scoped.  The denormalised
    # Customer.total_spent_minor is company-wide, so a branch-bound caller
    # must derive its numerator and denominator from that branch's immutable
    # orders/refunds rather than leaking the customer's other-branch spend.
    if tenant.branch_id is None:
        customers_count_raw, total_spend_raw = (
            await session.execute(
                select(
                    func.count(Customer.id),
                    func.coalesce(func.sum(Customer.total_spent_minor), 0),
                ).where(
                    Customer.company_id == tenant.company_id,
                    Customer.deleted_at.is_(None),
                )
            )
        ).one()
    else:
        customers_count_raw, gross_customer_spend = (
            await session.execute(
                select(
                    func.count(func.distinct(Order.customer_id)),
                    func.coalesce(func.sum(Order.total_minor), 0),
                ).where(
                    Order.company_id == tenant.company_id,
                    Order.branch_id == tenant.branch_id,
                    Order.customer_id.is_not(None),
                    Order.status.in_(("paid", "refunded")),
                )
            )
        ).one()
        refunded_customer_spend = int(
            (
                await session.execute(
                    select(func.coalesce(func.sum(Refund.amount_minor), 0))
                    .select_from(Refund)
                    .join(Order, Order.id == Refund.order_id)
                    .where(
                        Order.company_id == tenant.company_id,
                        Order.branch_id == tenant.branch_id,
                        Order.customer_id.is_not(None),
                    )
                )
            ).scalar_one()
            or 0
        )
        total_spend_raw = max(0, int(gross_customer_spend) - refunded_customer_spend)

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
