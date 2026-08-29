"""Settings endpoints — company profile, branches, terminals, expense categories.

Single-tenant in practice (you run for one D Company), but everything respects
company_id from the JWT so multi-company is plug-and-play.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid4
from zoneinfo import available_timezones

from fastapi import APIRouter, Depends, Request, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import exists, func, or_, select
from sqlalchemy.exc import IntegrityError

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, ConflictError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import has_permission, requires, requires_any
from app.core.tenant import TenantContext
from app.models import (
    Branch,
    Company,
    ExpenseCategory,
    InvoiceCounter,
    MembershipPayment,
    MembershipRefundSettlement,
    Order,
    Refund,
    Shift,
    Terminal,
)
from app.services.accounting.accounts import ACCOUNT_BY_CODE

router = APIRouter()

# Resolved once at import. Anything that is not an IANA zone name (e.g. "IST")
# makes Intl.DateTimeFormat throw in the POS webview while printing a receipt,
# so a bad timezone must never be allowed to persist.
_IANA_TIMEZONES = available_timezones()
_INVOICE_SERIES_RE = re.compile(r"^[A-Z0-9]{2}$")
TerminalPurpose = Literal["hybrid", "cafe_pos", "gaming"]
_HYBRID_WORKSPACE_REQUIRED = (
    "This Gaming Centre uses one Hybrid workspace for Gaming, POS and Shift. "
    "Use purpose 'hybrid'."
)
_ACTIVE_WORKSPACE_EXISTS = (
    "This shop already has its one active Hybrid workspace. Use that workspace "
    "instead of creating or reactivating another."
)


def _require_idempotency(request: Request, *, what: str) -> tuple[str, str]:
    """Shared mandatory-idempotency guard for writes with no natural key.

    update_company/update_branch (PATCH, "set fields to X") and
    delete_branch/delete_terminal are naturally safe to retry — same value
    or already-gone, no idempotency needed. create_terminal has no unique
    constraint a duplicate retry could collide against, so it needs this
    like GRN/expense/ticket-sale/subscribe do. create_branch has a
    company+name uniqueness guard already, but that only turns a duplicate
    retry into a confusing 409 rather than replaying the original success —
    still worth the same real idempotency treatment.
    """
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not str(key).strip() or not request_hash:
        raise BusinessRuleError(f"Idempotency-Key header required for {what} writes")
    return str(key), str(request_hash)


def _require_iana_timezone(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    if normalized not in _IANA_TIMEZONES:
        raise ValueError("timezone must be a valid IANA name like Asia/Kolkata")
    return normalized


def _normalize_invoice_series(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip().upper()
    if _INVOICE_SERIES_RE.fullmatch(normalized) is None:
        raise ValueError("invoice series must be exactly two letters or digits")
    return normalized


def _new_branch_invoice_series(payload: BranchCreate) -> str:
    """Resolve the explicit series while old clients transition safely.

    Current clients already send ``code``. Accept it only when it is itself
    the exact two-character series; never repeat the old silent truncation.
    """

    if payload.invoice_series_code is not None:
        return payload.invoice_series_code
    legacy_explicit = (payload.code or "").strip().upper()
    if _INVOICE_SERIES_RE.fullmatch(legacy_explicit):
        return legacy_explicit
    raise BusinessRuleError(
        "invoice_series_code is required for a new branch; enter a unique "
        "two-character letter/number code such as MN"
    )


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
    upi_vpa: str | None
    payment_provider: str | None
    payment_key_id: str | None
    # Never expose the secret itself — only whether one is stored.
    payment_secret_set: bool


class CompanyUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    legal_name: str | None = Field(default=None, max_length=200)
    timezone: str | None = Field(default=None, min_length=1, max_length=64)
    gstin: str | None = Field(default=None, min_length=15, max_length=15)
    pan: str | None = Field(default=None, min_length=10, max_length=10)
    gst_registration_type: str | None = Field(
        default=None, pattern="^(regular|composition|unregistered|sez)$"
    )
    is_composition: bool | None = None
    e_invoicing_enabled: bool | None = None
    google_sheets_webhook_url: str | None = Field(default=None, max_length=500)
    # UPI VPA like "name@bank"; allow clearing with "".
    upi_vpa: str | None = Field(
        default=None,
        max_length=255,
        pattern=r"^$|^[A-Za-z0-9.\-_]{2,256}@[A-Za-z]{2,64}$",
    )

    @field_validator("timezone")
    @classmethod
    def require_iana_timezone(cls, value: str | None) -> str | None:
        return _require_iana_timezone(value)


class BranchRead(BaseModel):
    id: UUID
    name: str
    code: str | None
    invoice_series_code: str
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
    code: str | None = Field(default=None, max_length=10)
    invoice_series_code: str | None = Field(default=None)
    address: str | None = Field(default=None, max_length=500)
    timezone: str | None = Field(default="Asia/Kolkata", max_length=64)
    opens_at: str | None = Field(default=None, pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    closes_at: str | None = Field(default=None, pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    state_code: str | None = Field(default="32", pattern=r"^\d{2}$")
    fssai_license_no: str | None = Field(default=None, pattern=r"^\d{14}$")
    trade_license_no: str | None = Field(default=None, max_length=50)
    branch_gstin: str | None = Field(default=None, min_length=15, max_length=15)

    @field_validator("timezone")
    @classmethod
    def require_iana_timezone(cls, value: str | None) -> str | None:
        return _require_iana_timezone(value)

    @field_validator("invoice_series_code")
    @classmethod
    def normalize_invoice_series(cls, value: str | None) -> str | None:
        return _normalize_invoice_series(value)


class BranchUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=200)
    code: str | None = Field(default=None, max_length=10)
    invoice_series_code: str | None = Field(default=None)
    address: str | None = Field(default=None, max_length=500)
    timezone: str | None = Field(default=None, max_length=64)
    opens_at: str | None = Field(default=None, pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    closes_at: str | None = Field(default=None, pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    state_code: str | None = Field(default=None, pattern=r"^\d{2}$")
    fssai_license_no: str | None = Field(default=None, pattern=r"^\d{14}$")
    trade_license_no: str | None = Field(default=None, max_length=50)
    branch_gstin: str | None = Field(default=None, min_length=15, max_length=15)

    @field_validator("timezone")
    @classmethod
    def require_iana_timezone(cls, value: str | None) -> str | None:
        return _require_iana_timezone(value)

    @field_validator("invoice_series_code")
    @classmethod
    def normalize_invoice_series(cls, value: str | None) -> str | None:
        return _normalize_invoice_series(value)


class TerminalRead(BaseModel):
    id: UUID
    branch_id: UUID
    name: str
    # Default keeps historical idempotency-response replays readable after
    # the purpose contract is deployed.
    purpose: TerminalPurpose = "hybrid"
    is_active: bool = True
    device_id: str | None
    last_seen_at: datetime | None


class TerminalCreate(BaseModel):
    branch_id: UUID
    name: str = Field(min_length=1, max_length=100)
    purpose: TerminalPurpose = "hybrid"
    device_id: str | None = Field(default=None, max_length=100)

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("terminal name cannot be blank")
        return normalized

    @field_validator("device_id")
    @classmethod
    def normalize_device_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        return value.strip() or None


class TerminalUpdate(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    purpose: TerminalPurpose | None = None
    is_active: bool | None = None
    # Explicit null or a blank string clears a no-longer-used device binding.
    device_id: str | None = Field(default=None, max_length=100)

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("terminal name cannot be blank")
        return normalized

    @field_validator("device_id")
    @classmethod
    def normalize_device_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        return value.strip() or None


class ExpenseCategoryRead(BaseModel):
    id: UUID
    name: str
    code: str | None = None


class ExpenseCategoryCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    code: str | None = Field(default=None, min_length=1, max_length=20)

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("expense category name cannot be blank")
        return normalized

    @field_validator("code")
    @classmethod
    def normalize_code(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip().upper()
        if not normalized:
            raise ValueError("expense category code cannot be blank")
        return normalized


def _terminal_read(terminal: Terminal) -> TerminalRead:
    return TerminalRead(
        id=terminal.id,
        branch_id=terminal.branch_id,
        name=terminal.name,
        purpose=getattr(terminal, "purpose", None) or "hybrid",
        is_active=getattr(terminal, "is_active", None) is not False,
        device_id=terminal.device_id,
        last_seen_at=terminal.last_seen_at,
    )


async def _ensure_terminal_identity_available(
    session,
    *,
    branch_id: UUID,
    name: str,
    device_id: str | None,
    exclude_terminal_id: UUID | None = None,
) -> None:
    """Reject ambiguous till labels and duplicate physical-device bindings.

    Callers lock the owning Branch row first. That shared parent lock
    serializes terminal creates/renames inside one branch even though the
    historic schema has no branch+name unique constraint.
    """
    name_stmt = select(Terminal.id).where(
        Terminal.branch_id == branch_id,
        func.lower(Terminal.name) == name.lower(),
    )
    if exclude_terminal_id is not None:
        name_stmt = name_stmt.where(Terminal.id != exclude_terminal_id)
    if (await session.execute(name_stmt.limit(1))).scalar_one_or_none() is not None:
        raise ConflictError(f"a terminal named '{name}' already exists in this branch")

    if device_id is None:
        return
    device_stmt = select(Terminal.id).where(Terminal.device_id == device_id)
    if exclude_terminal_id is not None:
        device_stmt = device_stmt.where(Terminal.id != exclude_terminal_id)
    if (await session.execute(device_stmt.limit(1))).scalar_one_or_none() is not None:
        raise ConflictError("this device ID is already assigned to another terminal")


async def _ensure_terminal_can_be_archived(
    session,
    *,
    company_id: UUID,
    terminal: Terminal,
) -> None:
    """Protect the last workspace and any unsettled shift before archival."""
    active_count = int(
        (
            await session.execute(
                select(func.count(Terminal.id))
                .join(Branch, Branch.id == Terminal.branch_id)
                .where(
                    Terminal.branch_id == terminal.branch_id,
                    Terminal.is_active.is_(True),
                    Branch.company_id == company_id,
                    Branch.deleted_at.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if active_count <= 1:
        raise BusinessRuleError(
            "The only active workspace cannot be archived. Keep one workspace available."
        )
    blocking_shift_id = (
        await session.execute(
            select(Shift.id)
            .where(
                Shift.company_id == company_id,
                Shift.terminal_id == terminal.id,
                Shift.status.not_in(("closed", "reconciled")),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    if blocking_shift_id is not None:
        raise BusinessRuleError(
            "Close or reconcile this workspace's current shift before archiving it."
        )

# ============================================================================
# COMPANY
# ============================================================================
@router.get("/company", response_model=CompanyRead)
async def get_company(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
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
        upi_vpa=c.upi_vpa,
        payment_provider=c.payment_provider,
        payment_key_id=c.payment_key_id,
        payment_secret_set=bool(c.payment_key_secret),
    )


@router.patch("/company", response_model=CompanyRead)
async def update_company(
    payload: CompanyUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
) -> CompanyRead:
    c = await session.get(Company, tenant.company_id)
    if not c:
        raise NotFoundError("company not found")
    for f in ("name", "legal_name", "timezone", "gstin", "pan",
              "gst_registration_type", "is_composition",
              "e_invoicing_enabled", "google_sheets_webhook_url", "upi_vpa"):
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
        upi_vpa=c.upi_vpa,
        payment_provider=c.payment_provider,
        payment_key_id=c.payment_key_id,
        payment_secret_set=bool(c.payment_key_secret),
    )


# ============================================================================
# BRANCHES
# ============================================================================
@router.get("/branches", response_model=list[BranchRead])
async def list_branches(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
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
            id=r.id, name=r.name, code=r.code,
            invoice_series_code=r.invoice_series_code, address=r.address,
            timezone=r.timezone, opens_at=r.opens_at, closes_at=r.closes_at,
            state_code=r.state_code, fssai_license_no=r.fssai_license_no,
            trade_license_no=r.trade_license_no, branch_gstin=r.branch_gstin,
        )
        for r in rows
    ]


@router.post("/branches", response_model=BranchRead, status_code=status.HTTP_201_CREATED)
async def create_branch(
    payload: BranchCreate,
    request: Request,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
) -> BranchRead:
    idempotency_key, request_hash = _require_idempotency(request, what="branch create")
    replay = await check_or_reserve(
        session, key=idempotency_key, request_hash=request_hash,
        user_id=tenant.user_id, terminal_id=None,
    )
    if replay:
        return BranchRead.model_validate(replay["body"])

    invoice_series_code = _new_branch_invoice_series(payload)

    # Serialize branch identity creation inside one company. This turns two
    # concurrent requests for the same name/series into one deterministic 409
    # instead of letting a uniqueness failure escape during request commit.
    company_id = (
        await session.execute(
            select(Company.id)
            .where(Company.id == tenant.company_id, Company.deleted_at.is_(None))
            .with_for_update()
        )
    ).scalar_one_or_none()
    if company_id is None:
        raise NotFoundError("company not found")

    # D Company is operated as one shop. Keep Branch as the durable accounting,
    # timezone, receipt-series, and tenant-scope entity, but do not expose a
    # second active business location through a direct API call after the UI has
    # already hidden that action. The company-row lock serializes create/delete
    # so two concurrent requests cannot bypass the one-shop invariant.
    active_branch_id = (
        await session.execute(
            select(Branch.id)
            .where(
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    if active_branch_id is not None:
        raise BusinessRuleError(
            "This ERP is configured for one shop. Edit the existing shop instead "
            "of adding another."
        )

    existing_name = (
        await session.execute(
            select(Branch.id).where(
                Branch.company_id == tenant.company_id,
                Branch.name == payload.name,
                Branch.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if existing_name:
        raise ConflictError("an active branch with this name already exists")
    existing_series = (
        await session.execute(
            select(Branch.id).where(
                Branch.company_id == tenant.company_id,
                Branch.invoice_series_code == invoice_series_code,
            )
        )
    ).scalar_one_or_none()
    if existing_series:
        raise ConflictError(
            f"invoice series '{invoice_series_code}' is already assigned to another branch"
        )
    values = payload.model_dump(exclude={"invoice_series_code"})
    b = Branch(
        id=uuid4(),
        company_id=tenant.company_id,
        invoice_series_code=invoice_series_code,
        **values,
    )
    session.add(b)
    await session.flush()
    response = BranchRead(
        id=b.id, name=b.name, code=b.code,
        invoice_series_code=b.invoice_series_code, address=b.address,
        timezone=b.timezone, opens_at=b.opens_at, closes_at=b.closes_at,
        state_code=b.state_code, fssai_license_no=b.fssai_license_no,
        trade_license_no=b.trade_license_no, branch_gstin=b.branch_gstin,
    )
    await store_response(
        session, key=idempotency_key, status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/branches/{branch_id}", response_model=BranchRead)
async def update_branch(
    branch_id: UUID,
    payload: BranchUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
) -> BranchRead:
    company_id = (
        await session.execute(
            select(Company.id)
            .where(Company.id == tenant.company_id, Company.deleted_at.is_(None))
            .with_for_update()
        )
    ).scalar_one_or_none()
    if company_id is None:
        raise NotFoundError("company not found")
    b = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not b:
        raise NotFoundError("branch not found")
    requested_series = payload.invoice_series_code
    if requested_series is not None and requested_series != b.invoice_series_code:
        fiscal_history_exists = bool(
            (
                await session.execute(
                    select(
                        or_(
                            exists().where(InvoiceCounter.branch_id == branch_id),
                            exists().where(
                                Order.branch_id == branch_id,
                                Order.invoice_no.is_not(None),
                            ),
                            exists().where(
                                Refund.branch_id == branch_id,
                                Refund.receipt_no.is_not(None),
                            ),
                            exists().where(
                                MembershipPayment.branch_id == branch_id,
                                MembershipPayment.receipt_no.is_not(None),
                            ),
                            exists().where(
                                MembershipRefundSettlement.branch_id == branch_id,
                                MembershipRefundSettlement.receipt_no.is_not(None),
                            ),
                        )
                    )
                )
            ).scalar_one()
        )
        if fiscal_history_exists:
            raise ConflictError(
                "invoice series cannot be changed after fiscal document history exists; "
                "keep the current series so receipts remain continuous"
            )
        duplicate_series = (
            await session.execute(
                select(Branch.id)
                .where(
                    Branch.company_id == tenant.company_id,
                    Branch.invoice_series_code == requested_series,
                    Branch.id != branch_id,
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if duplicate_series is not None:
            raise ConflictError(
                f"invoice series '{requested_series}' is already assigned to another branch"
            )
    for f in payload.model_fields:
        v = getattr(payload, f)
        if v is not None:
            setattr(b, f, v)
    await session.flush()
    return BranchRead(
        id=b.id, name=b.name, code=b.code,
        invoice_series_code=b.invoice_series_code, address=b.address,
        timezone=b.timezone, opens_at=b.opens_at, closes_at=b.closes_at,
        state_code=b.state_code, fssai_license_no=b.fssai_license_no,
        trade_license_no=b.trade_license_no, branch_gstin=b.branch_gstin,
    )


@router.delete("/branches/{branch_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_branch(
    branch_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
):
    company_id = (
        await session.execute(
            select(Company.id)
            .where(Company.id == tenant.company_id, Company.deleted_at.is_(None))
            .with_for_update()
        )
    ).scalar_one_or_none()
    if company_id is None:
        raise NotFoundError("company not found")
    b = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if b is None:
        raise NotFoundError("branch not found")
    active_count = int(
        (
            await session.execute(
                select(func.count(Branch.id)).where(
                    Branch.company_id == tenant.company_id,
                    Branch.deleted_at.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if active_count <= 1:
        raise BusinessRuleError(
            "The only shop cannot be deleted. Edit its details instead."
        )
    b.deleted_at = datetime.now(timezone.utc)
    await session.flush()


# ============================================================================
# TERMINALS
# ============================================================================
@router.get("/terminals", response_model=list[TerminalRead])
async def list_terminals(
    session: SessionDep,
    tenant: TenantContext = Depends(
        requires_any("pos.read", "gaming.read", "settings.manage")
    ),
    branch_id: UUID | None = None,
    include_inactive: bool = False,
) -> list[TerminalRead]:
    # Ordinary POS/Gaming users may discover the workspace only in their
    # authenticated branch. An owner with settings.manage may inspect the
    # company's other active branches without gaining Audit Log/system access.
    can_manage_settings = await has_permission(session, tenant, "settings.manage")
    if tenant.branch_id is not None and not can_manage_settings:
        if branch_id is not None and branch_id != tenant.branch_id:
            # Do not reveal whether another branch or terminal exists.
            raise NotFoundError("branch not found")
        branch_id = tenant.branch_id
        include_inactive = False
    stmt = (
        select(Terminal)
        .join(Branch, Branch.id == Terminal.branch_id)
        .where(
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
    )
    if branch_id:
        stmt = stmt.where(Terminal.branch_id == branch_id)
    if not include_inactive:
        stmt = stmt.where(Terminal.is_active.is_(True))
    stmt = stmt.order_by(Terminal.is_active.desc(), func.lower(Terminal.name), Terminal.id)
    rows = (await session.execute(stmt)).scalars().all()
    return [
        TerminalRead(
            id=r.id, branch_id=r.branch_id, name=r.name,
            purpose=r.purpose, is_active=r.is_active,
            device_id=r.device_id, last_seen_at=r.last_seen_at,
        )
        for r in rows
    ]


@router.post("/terminals", response_model=TerminalRead, status_code=status.HTTP_201_CREATED)
async def create_terminal(
    payload: TerminalCreate,
    request: Request,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
) -> TerminalRead:
    idempotency_key, request_hash = _require_idempotency(request, what="terminal create")
    replay = await check_or_reserve(
        session, key=idempotency_key, request_hash=request_hash,
        user_id=tenant.user_id, terminal_id=None,
    )
    if replay:
        return TerminalRead.model_validate(replay["body"])

    # Lock the durable branch parent before checking terminal identities. A
    # concurrent create/rename in this branch must finish first, otherwise two
    # requests can both observe an available name and create ambiguous tills.
    b = (
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
    if not b:
        raise NotFoundError("branch not found")
    if payload.purpose != "hybrid":
        raise BusinessRuleError(_HYBRID_WORKSPACE_REQUIRED)
    active_terminal_id = (
        await session.execute(
            select(Terminal.id)
            .where(
                Terminal.branch_id == payload.branch_id,
                Terminal.is_active.is_(True),
            )
            .limit(1)
        )
    ).scalar_one_or_none()
    if active_terminal_id is not None:
        raise ConflictError(
            _ACTIVE_WORKSPACE_EXISTS,
            details={"active_terminal_id": str(active_terminal_id)},
        )
    await _ensure_terminal_identity_available(
        session,
        branch_id=payload.branch_id,
        name=payload.name,
        device_id=payload.device_id,
    )
    t = Terminal(
        id=uuid4(),
        branch_id=payload.branch_id,
        name=payload.name,
        purpose=payload.purpose,
        device_id=payload.device_id,
    )
    session.add(t)
    try:
        await session.flush()
    except IntegrityError as exc:
        # device_id has a database unique constraint, which closes the final
        # cross-branch race that the branch-scoped parent lock cannot cover.
        raise ConflictError(
            "terminal name or device ID conflicts with an existing terminal"
        ) from exc
    response = _terminal_read(t)
    await store_response(
        session, key=idempotency_key, status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/terminals/{terminal_id}", response_model=TerminalRead)
async def update_terminal(
    terminal_id: UUID,
    payload: TerminalUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
) -> TerminalRead:
    # Resolve tenant ownership before locking, then acquire locks in the same
    # branch -> terminal order as create_terminal. The second lookup closes a
    # delete/race window between ownership resolution and lock acquisition.
    branch_id = (
        await session.execute(
            select(Terminal.branch_id)
            .join(Branch, Branch.id == Terminal.branch_id)
            .where(
                Terminal.id == terminal_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if branch_id is None:
        raise NotFoundError("terminal not found")
    branch = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if branch is None:
        raise NotFoundError("terminal not found")
    terminal = (
        await session.execute(
            select(Terminal)
            .where(Terminal.id == terminal_id, Terminal.branch_id == branch_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if terminal is None:
        raise NotFoundError("terminal not found")

    name = payload.name if payload.name is not None else terminal.name
    device_id = (
        payload.device_id
        if "device_id" in payload.model_fields_set
        else terminal.device_id
    )
    purpose = payload.purpose if payload.purpose is not None else terminal.purpose
    terminal_is_active = getattr(terminal, "is_active", None) is not False
    is_active = payload.is_active if payload.is_active is not None else terminal_is_active
    if is_active and purpose != "hybrid":
        raise BusinessRuleError(_HYBRID_WORKSPACE_REQUIRED)
    if not terminal_is_active and is_active:
        active_terminal_id = (
            await session.execute(
                select(Terminal.id)
                .where(
                    Terminal.branch_id == branch_id,
                    Terminal.is_active.is_(True),
                    Terminal.id != terminal.id,
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if active_terminal_id is not None:
            raise ConflictError(
                _ACTIVE_WORKSPACE_EXISTS,
                details={"active_terminal_id": str(active_terminal_id)},
            )
    if purpose != terminal.purpose or is_active != terminal_is_active:
        blocking_shift_id = (
            await session.execute(
                select(Shift.id)
                .where(
                    Shift.company_id == tenant.company_id,
                    Shift.terminal_id == terminal.id,
                    Shift.status.not_in(("closed", "reconciled")),
                )
                .limit(1)
            )
        ).scalar_one_or_none()
        if blocking_shift_id is not None:
            raise BusinessRuleError(
                "Close or reconcile this terminal's current shift before changing "
                "its operational configuration."
            )
    if terminal_is_active and not is_active:
        await _ensure_terminal_can_be_archived(
            session,
            company_id=tenant.company_id,
            terminal=terminal,
        )
    await _ensure_terminal_identity_available(
        session,
        branch_id=branch_id,
        name=name,
        device_id=device_id,
        exclude_terminal_id=terminal.id,
    )
    terminal.name = name
    terminal.device_id = device_id
    terminal.purpose = purpose
    terminal.is_active = is_active
    try:
        await session.flush()
    except IntegrityError as exc:
        raise ConflictError(
            "terminal name or device ID conflicts with an existing terminal"
        ) from exc
    return _terminal_read(terminal)


@router.delete("/terminals/{terminal_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_terminal(
    terminal_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("settings.manage")),
):
    branch_id = (
        await session.execute(
            select(Terminal.branch_id)
            .join(Branch, Branch.id == Terminal.branch_id)
            .where(
                Terminal.id == terminal_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    if branch_id is None:
        raise NotFoundError("terminal not found")
    branch = (
        await session.execute(
            select(Branch)
            .where(
                Branch.id == branch_id,
                Branch.company_id == tenant.company_id,
                Branch.deleted_at.is_(None),
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if branch is None:
        raise NotFoundError("terminal not found")
    terminal = (
        await session.execute(
            select(Terminal)
            .where(Terminal.id == terminal_id, Terminal.branch_id == branch_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    if terminal is None:
        raise NotFoundError("terminal not found")
    if not terminal.is_active:
        return
    await _ensure_terminal_can_be_archived(
        session,
        company_id=tenant.company_id,
        terminal=terminal,
    )
    terminal.is_active = False
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
        ExpenseCategoryRead(id=r.id, name=r.name, code=r.code)
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
    canonical = ACCOUNT_BY_CODE.get(payload.code or "")
    if canonical is not None and canonical.type != "expense":
        raise BusinessRuleError(
            f"account code {canonical.code} is a {canonical.type} account, not an expense"
        )
    duplicate = (
        await session.execute(
            select(ExpenseCategory.id).where(
                ExpenseCategory.company_id == tenant.company_id,
                (ExpenseCategory.name == payload.name)
                | (
                    ExpenseCategory.code == payload.code
                    if payload.code is not None
                    else False
                ),
            ).limit(1)
        )
    ).scalar_one_or_none()
    if duplicate:
        raise ConflictError("an expense category with this name or code already exists")
    c = ExpenseCategory(
        id=uuid4(),
        company_id=tenant.company_id,
        name=payload.name,
        code=payload.code,
    )
    session.add(c)
    await session.flush()
    return ExpenseCategoryRead(id=c.id, name=c.name, code=c.code)
