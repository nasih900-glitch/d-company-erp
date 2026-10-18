"""Protected-owner, online-only correction previews and immutable receipts."""

# FastAPI resolves dependency and DTO annotations at runtime.
# ruff: noqa: TC001, TC003
from __future__ import annotations

import asyncio
import hashlib
import json
from contextlib import AsyncExitStack
from datetime import date, datetime
from typing import Annotated, Literal
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo

from fastapi import APIRouter, BackgroundTasks, Depends, Header, Query, Request
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator
from sqlalchemy import exists, select
from sqlalchemy.exc import DBAPIError

from app.core.db import SessionDep
from app.core.errors import (
    AuthError,
    BusinessRuleError,
    ConflictError,
    ForbiddenError,
    IdempotencyConflict,
    NotFoundError,
)
from app.core.permissions import requires
from app.core.security import decode_token, issue_tender_token, verify_password
from app.core.tenant import TenantContext
from app.models import (
    DailyTenderReconciliation,
    Order,
    Payment,
    PaymentTenderCorrection,
    PosRefundRequest,
    PosRefundWithdrawal,
    Refund,
    Role,
    Shift,
    User,
    UserRole,
)
from app.services.audit.events import record_operation
from app.services.auth.rate_limit import enforce_login_rate_limit
from app.services.finance.tender_corrections import lock_sources, source_context
from app.services.integrations.google_sheets_collection_summary import enqueue_tender_daily_snapshot
from app.services.realtime import manager as realtime_manager


class TenderRoute(APIRoute):
    def get_route_handler(self):
        original = super().get_route_handler()

        async def handler(request):
            outer_function = request.scope.get("fastapi_function_astack")
            outer_request = request.scope.get("fastapi_inner_astack")
            for attempt in range(3):
                try:
                    # Dependency teardown/commit must happen INSIDE the retry
                    # boundary. Otherwise a failed old Session commits after a
                    # later successful attempt, or deferred failure sends 2xx.
                    async with (
                        AsyncExitStack() as request_stack,
                        AsyncExitStack() as function_stack,
                    ):
                        request.scope["fastapi_inner_astack"] = request_stack
                        request.scope["fastapi_function_astack"] = function_stack
                        response = await original(request)
                    return response
                except DBAPIError as exc:
                    state = getattr(exc.orig, "sqlstate", None) or getattr(
                        getattr(exc.orig, "__cause__", None), "sqlstate", None
                    )
                    if state in {"23514", "23505"}:
                        raise ConflictError(
                            "The tender source or saved action no longer matches. "
                            "Refresh and review its receipt before retrying."
                        ) from exc
                    if state not in {"40P01", "40001"} or not request.url.path.endswith("/commit"):
                        raise
                    if attempt == 2:
                        raise ConflictError(
                            "Collection source changed concurrently. Retry the same saved action.",
                            details={"retryable": True},
                        ) from exc
                    await asyncio.sleep(0.025 * (2**attempt))
                finally:
                    request.scope["fastapi_function_astack"] = outer_function
                    request.scope["fastapi_inner_astack"] = outer_request

        return handler


router = APIRouter(route_class=TenderRoute)
TenderOwner = Annotated[TenantContext, Depends(requires("finance.tender.correct"))]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    @field_validator("reason", check_fields=False)
    @classmethod
    def reason_has_content(cls, value: str) -> str:
        cleaned = value.strip()
        if len(cleaned) < 3:
            raise ValueError("reason must contain at least three non-blank characters")
        return cleaned


class Unlock(StrictModel):
    branch_id: UUID
    scope: Literal["payment", "daily", "history"]
    password: SecretStr


class UnlockRead(BaseModel):
    token: str
    history_token: str
    expires_in: int = 300


class PaymentPrepare(StrictModel):
    payment_id: UUID


class PaymentCommit(PaymentPrepare):
    source_fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")
    reason: str = Field(min_length=3, max_length=500)
    evidence_reference: str | None = Field(default=None, max_length=200)


class DailyPrepare(StrictModel):
    branch_id: UUID
    business_date: date


class DailyCommit(DailyPrepare):
    source_fingerprint: str = Field(pattern=r"^[a-f0-9]{64}$")
    actual_cash_minor: int = Field(ge=0, le=9_000_000_000_000_000, strict=True)
    actual_upi_minor: int = Field(ge=0, le=9_000_000_000_000_000, strict=True)
    reason: str = Field(min_length=3, max_length=500)
    evidence_reference: str | None = Field(default=None, max_length=200)


class PaymentTenderPreview(BaseModel):
    payment_id: UUID
    order_id: UUID
    invoice_no: str
    branch_id: UUID
    business_date: date
    business_timezone: str
    source_fingerprint: str
    amount_minor: int
    original_method: Literal["cash"]
    effective_method: Literal["upi"]
    before_cash_minor: int
    before_upi_minor: int
    after_cash_minor: int
    after_upi_minor: int
    total_change_minor: Literal[0]
    evidence_kind: Literal["owner_attestation"]
    original_shift_record_unchanged: bool


class DailyTenderPreview(BaseModel):
    branch_id: UUID
    business_date: date
    business_timezone: str
    source_fingerprint: str
    recorded_cash_minor: int
    recorded_upi_minor: int
    recorded_total_minor: int
    attestation_scope: Literal["cash_upi_only"]
    unchanged_other_rails: dict[str, int]
    recorded_all_rails_total_minor: int
    source_allocation: Literal["unallocated_owner_attestation"]
    physical_cash_count: Literal[False]


class TenderReceipt(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    receipt_reference: str
    operation: Literal[
        "payment_method_corrected", "daily_collections_reconciled", "daily_actuals_recorded"
    ]
    financial_effect: bool
    company_id: UUID
    branch_id: UUID
    business_date: date
    business_timezone: str
    recorded_by: UUID
    recorded_at: datetime
    reason: str
    evidence_kind: str
    evidence_reference: str | None
    source_fingerprint: str
    idempotency_key: str
    payment_id: UUID | None = None
    order_id: UUID | None = None
    invoice_no: str | None = None
    shift_id: UUID | None = None
    original_method: str | None = None
    effective_method: str | None = None
    amount_minor: int | None = None
    original_tendered_minor: int | None = None
    original_change_minor: int | None = None
    accounting_at: datetime | None = None
    kind: str | None = None
    before_cash_minor: int | None = None
    before_upi_minor: int | None = None
    actual_cash_minor: int | None = None
    actual_upi_minor: int | None = None
    cash_delta_minor: int | None = None
    upi_delta_minor: int | None = None
    total_difference_minor: int = 0
    source_allocation: str


def receipt_read(row):
    payment = isinstance(row, PaymentTenderCorrection)
    operation = (
        "payment_method_corrected"
        if payment
        else ("daily_collections_reconciled" if row.kind == "applied" else "daily_actuals_recorded")
    )
    values = {key: getattr(row, key) for key in TenderReceipt.model_fields if hasattr(row, key)}
    return TenderReceipt(
        **values,
        receipt_reference=("TC-" if payment else "DR-") + str(row.id),
        operation=operation,
        financial_effect=payment or row.kind == "applied",
        source_allocation="identified_payment" if payment else "unallocated_owner_attestation",
    )


async def require_confirmation(session, tenant, token, *, branch_id, scope):
    await require_protected_owner(session, tenant)
    try:
        claims = decode_token(token or "")
    except ValueError as exc:
        raise AuthError("Confirm the owner password again before continuing.") from exc
    user = await session.get(User, tenant.user_id, populate_existing=True)
    if (
        not tenant.audit_access
        or user is None
        or user.deleted_at is not None
        or user.status != "active"
        or claims.get("type") != "tender_confirmation"
        or claims.get("scope") != scope
        or claims.get("sub") != str(tenant.user_id)
        or claims.get("company_id") != str(tenant.company_id)
        or claims.get("branch_id") != str(branch_id)
        or claims.get("auth_version") != user.auth_version
        or not tenant.in_branch(branch_id)
    ):
        raise AuthError("Owner confirmation expired or belongs to another action/branch.")


async def require_protected_owner(session, tenant):
    # Signed legacy access flags must not turn an ordinary owner into the
    # designated owner. Check current role assignment in addition to claims.
    permitted = await session.scalar(
        select(
            exists().where(
                UserRole.user_id == tenant.user_id,
                UserRole.role_id == Role.id,
                Role.company_id == tenant.company_id,
                Role.code == "super_owner",
            )
        )
    )
    if not tenant.audit_access or not permitted:
        raise ForbiddenError("Only the designated protected owner may correct tenders.")


async def payment_context(session, tenant, payment_id, *, lock=False):
    payment = await session.get(Payment, payment_id)
    if payment is None:
        raise NotFoundError("payment not found")
    stmt = select(Order).where(Order.id == payment.order_id, Order.company_id == tenant.company_id)
    if lock:
        stmt = stmt.with_for_update()
    order = await session.scalar(stmt.execution_options(populate_existing=True))
    if order is None or not tenant.in_branch(order.branch_id):
        raise NotFoundError("payment not found")
    shift_stmt = select(Shift).where(
        Shift.id == payment.shift_id, Shift.company_id == tenant.company_id
    )
    if lock:
        shift_stmt = shift_stmt.with_for_update()
    shift = await session.scalar(shift_stmt.execution_options(populate_existing=True))
    if lock:
        payment = await session.scalar(
            select(Payment)
            .where(Payment.id == payment_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
    if shift is None or shift.branch_id != order.branch_id or order.shift_id != shift.id:
        raise BusinessRuleError("Payment and shift source evidence do not match.")
    return payment, order, shift


async def payment_eligible(session, payment, order, shift):
    if (
        payment.method != "cash"
        or shift.status != "closed"
        or shift.closed_at is None
        or order.status != "paid"
        or not order.invoice_no
    ):
        raise BusinessRuleError(
            "Only a complete original Cash payment on a closed, issued bill "
            "can be corrected to UPI."
        )
    if await session.scalar(
        select(exists().where(PaymentTenderCorrection.payment_id == payment.id))
    ):
        raise ConflictError("This payment already has a tender correction.")
    pending = select(PosRefundRequest.id).where(
        PosRefundRequest.order_id == order.id,
        ~exists().where(PosRefundWithdrawal.refund_request_id == PosRefundRequest.id),
    )
    if await session.scalar(
        select(exists().where(Refund.order_id == order.id))
    ) or await session.scalar(select(exists(pending))):
        raise BusinessRuleError("A settled or unresolved refund prevents this correction.")


async def replay(session, model, tenant, key, path, body):
    if not key or len(key) > 160:
        raise BusinessRuleError("A saved Idempotency-Key of 1–160 characters is required.")
    digest = hashlib.sha256(
        json.dumps(body, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    for cls in (PaymentTenderCorrection, DailyTenderReconciliation):
        row = await session.scalar(
            select(cls).where(cls.company_id == tenant.company_id, cls.idempotency_key == key)
        )
        if row is not None:
            if (
                cls != model
                or row.recorded_by != tenant.user_id
                or row.request_path != path
                or row.request_hash != digest
                or row.request_body != body
            ):
                raise IdempotencyConflict(
                    "Saved tender action reused with different details or actor."
                )
            return row, digest
    return None, digest


@router.post("/unlock", response_model=UnlockRead)
async def unlock(
    payload: Unlock,
    request: Request,
    session: SessionDep,
    tenant: TenderOwner,
):
    await require_protected_owner(session, tenant)
    user = await session.get(User, tenant.user_id)
    if user is None or not tenant.in_branch(payload.branch_id):
        raise NotFoundError("owner or branch not found")
    await enforce_login_rate_limit(request, user.email)
    from app.models import Branch

    if not await session.scalar(
        select(Branch.id).where(
            Branch.id == payload.branch_id,
            Branch.company_id == tenant.company_id,
            Branch.deleted_at.is_(None),
        )
    ):
        raise NotFoundError("branch not found")
    if not verify_password(payload.password.get_secret_value(), user.password_hash):
        raise AuthError("Owner password is incorrect.")
    return UnlockRead(
        token=issue_tender_token(
            user_id=user.id,
            company_id=tenant.company_id,
            branch_id=payload.branch_id,
            auth_version=user.auth_version,
            scope=payload.scope,
        ),
        # One password confirmation, two distinct purpose-bound grants. The
        # history token cannot prepare/commit a payment or daily correction.
        history_token=issue_tender_token(
            user_id=user.id,
            company_id=tenant.company_id,
            branch_id=payload.branch_id,
            auth_version=user.auth_version,
            scope="history",
        ),
    )


@router.post("/payment/prepare", response_model=PaymentTenderPreview)
async def prepare_payment(
    payload: PaymentPrepare,
    session: SessionDep,
    tenant: TenderOwner,
    x_tender_token: str | None = Header(default=None),
):
    payment, order, shift = await payment_context(session, tenant, payload.payment_id)
    await require_confirmation(
        session, tenant, x_tender_token, branch_id=order.branch_id, scope="payment"
    )
    await lock_sources(session, tenant.company_id)
    await payment_eligible(session, payment, order, shift)
    from app.models import Company

    company = await session.get(Company, tenant.company_id, populate_existing=True)
    zone = company.google_sheets_summary_timezone or company.timezone
    day = shift.opened_at.astimezone(ZoneInfo(zone)).date()
    _, zone, source = await source_context(
        session, tenant, order.branch_id, day, require_ready=False
    )
    if await session.scalar(
        select(
            exists().where(
                DailyTenderReconciliation.company_id == tenant.company_id,
                DailyTenderReconciliation.branch_id == order.branch_id,
                DailyTenderReconciliation.business_date == day,
                DailyTenderReconciliation.kind == "applied",
            )
        )
    ):
        raise BusinessRuleError(
            "An unallocated transfer already covers this day; "
            "explicit allocation review is required."
        )
    return {
        "payment_id": payment.id,
        "order_id": order.id,
        "invoice_no": order.invoice_no,
        "branch_id": order.branch_id,
        "business_date": day,
        "business_timezone": zone,
        "source_fingerprint": source["fingerprint"],
        "amount_minor": payment.amount_minor,
        "original_method": "cash",
        "effective_method": "upi",
        "before_cash_minor": source["cash_minor"],
        "before_upi_minor": source["upi_minor"],
        "after_cash_minor": source["cash_minor"] - payment.amount_minor,
        "after_upi_minor": source["upi_minor"] + payment.amount_minor,
        "total_change_minor": 0,
        "evidence_kind": "owner_attestation",
        "original_shift_record_unchanged": True,
    }


@router.post("/payment/commit", response_model=TenderReceipt, status_code=201)
async def commit_payment(
    payload: PaymentCommit,
    request: Request,
    background_tasks: BackgroundTasks,
    session: SessionDep,
    tenant: TenderOwner,
    x_tender_token: str | None = Header(default=None),
    idempotency_key: str | None = Header(default=None),
):
    payment, order, shift = await payment_context(session, tenant, payload.payment_id, lock=True)
    await require_confirmation(
        session, tenant, x_tender_token, branch_id=order.branch_id, scope="payment"
    )
    await lock_sources(session, tenant.company_id)
    await require_confirmation(
        session, tenant, x_tender_token, branch_id=order.branch_id, scope="payment"
    )
    body = payload.model_dump(mode="json")
    previous, digest = await replay(
        session, PaymentTenderCorrection, tenant, idempotency_key, request.url.path, body
    )
    if previous:
        return receipt_read(previous)
    await payment_eligible(session, payment, order, shift)
    from app.models import Company

    company = await session.get(Company, tenant.company_id, populate_existing=True)
    zone = company.google_sheets_summary_timezone or company.timezone
    day = shift.opened_at.astimezone(ZoneInfo(zone)).date()
    _, zone, source = await source_context(
        session, tenant, order.branch_id, day, require_ready=False
    )
    if source["fingerprint"] != payload.source_fingerprint:
        raise ConflictError("The reviewed source changed; prepare and review a fresh correction.")
    row = PaymentTenderCorrection(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=order.branch_id,
        business_date=day,
        business_timezone=zone,
        recorded_by=tenant.user_id,
        reason=payload.reason.strip(),
        evidence_kind="owner_attestation",
        evidence_reference=payload.evidence_reference,
        source_fingerprint=payload.source_fingerprint,
        idempotency_key=idempotency_key,
        request_hash=digest,
        request_path=request.url.path,
        request_body=body,
        payment_id=payment.id,
        order_id=order.id,
        shift_id=shift.id,
        invoice_no=order.invoice_no,
        original_method="cash",
        effective_method="upi",
        amount_minor=payment.amount_minor,
        original_tendered_minor=payment.tendered_minor,
        original_change_minor=payment.change_minor,
        accounting_at=payment.paid_at,
    )
    session.add(row)
    await session.flush()
    record_operation(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action="payment_method_corrected",
        entity_type="PaymentTenderCorrection",
        entity_id=row.id,
        before={"method": "cash", "amount_minor": payment.amount_minor},
        after={
            "method": "upi",
            "original_method": "cash",
            "effective_method": "upi",
            "invoice_no": order.invoice_no,
            "order_id": str(order.id),
            "amount_minor": payment.amount_minor,
            "payment_id": str(payment.id),
            "business_date": day.isoformat(),
            "evidence_kind": "owner_attestation",
            "correction_id": str(row.id),
        },
        reason=row.reason,
        operation_id=str(row.id),
        source_reference=order.invoice_no,
    )
    await enqueue_tender_daily_snapshot(
        session, company_id=row.company_id, branch_id=row.branch_id, business_date=row.business_date
    )
    for resource in ("orders", "receipts", "shifts", "finance", "audit"):
        background_tasks.add_task(realtime_manager.broadcast, row.company_id, resource)
    return receipt_read(row)


@router.post("/daily/prepare", response_model=DailyTenderPreview)
async def prepare_daily(
    payload: DailyPrepare,
    session: SessionDep,
    tenant: TenderOwner,
    x_tender_token: str | None = Header(default=None),
):
    await require_confirmation(
        session, tenant, x_tender_token, branch_id=payload.branch_id, scope="daily"
    )
    await lock_sources(session, tenant.company_id)
    _, zone, source = await source_context(
        session, tenant, payload.branch_id, payload.business_date
    )
    return {
        "branch_id": payload.branch_id,
        "business_date": payload.business_date,
        "business_timezone": zone,
        "source_fingerprint": source["fingerprint"],
        "recorded_cash_minor": source["cash_minor"],
        "recorded_upi_minor": source["upi_minor"],
        "recorded_total_minor": source["cash_minor"] + source["upi_minor"],
        "attestation_scope": "cash_upi_only",
        "unchanged_other_rails": source["unchanged_other_rails"],
        "recorded_all_rails_total_minor": source["recorded_all_rails_total_minor"],
        "source_allocation": "unallocated_owner_attestation",
        "physical_cash_count": False,
    }


@router.post("/daily/commit", response_model=TenderReceipt, status_code=201)
async def commit_daily(
    payload: DailyCommit,
    request: Request,
    background_tasks: BackgroundTasks,
    session: SessionDep,
    tenant: TenderOwner,
    x_tender_token: str | None = Header(default=None),
    idempotency_key: str | None = Header(default=None),
):
    await require_confirmation(
        session, tenant, x_tender_token, branch_id=payload.branch_id, scope="daily"
    )
    await lock_sources(session, tenant.company_id)
    await require_confirmation(
        session, tenant, x_tender_token, branch_id=payload.branch_id, scope="daily"
    )
    body = payload.model_dump(mode="json")
    previous, digest = await replay(
        session, DailyTenderReconciliation, tenant, idempotency_key, request.url.path, body
    )
    if previous:
        return receipt_read(previous)
    _, zone, source = await source_context(
        session, tenant, payload.branch_id, payload.business_date
    )
    if source["fingerprint"] != payload.source_fingerprint:
        raise ConflictError("The reviewed source changed; prepare and review fresh daily actuals.")
    cash_delta = payload.actual_cash_minor - source["cash_minor"]
    upi_delta = payload.actual_upi_minor - source["upi_minor"]
    difference = cash_delta + upi_delta
    if difference == 0 and not (cash_delta < 0 and upi_delta == -cash_delta):
        raise BusinessRuleError(
            "Only a positive Cash-to-UPI transfer is supported; nothing was posted."
        )
    row = DailyTenderReconciliation(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=payload.branch_id,
        business_date=payload.business_date,
        business_timezone=zone,
        recorded_by=tenant.user_id,
        reason=payload.reason.strip(),
        evidence_kind="owner_attestation",
        evidence_reference=payload.evidence_reference,
        source_fingerprint=payload.source_fingerprint,
        idempotency_key=idempotency_key,
        request_hash=digest,
        request_path=request.url.path,
        request_body=body,
        kind="applied" if difference == 0 else "unresolved",
        before_cash_minor=source["cash_minor"],
        before_upi_minor=source["upi_minor"],
        actual_cash_minor=payload.actual_cash_minor,
        actual_upi_minor=payload.actual_upi_minor,
        cash_delta_minor=cash_delta if difference == 0 else 0,
        upi_delta_minor=upi_delta if difference == 0 else 0,
        total_difference_minor=difference,
    )
    session.add(row)
    await session.flush()
    action = "daily_collections_reconciled" if difference == 0 else "daily_actuals_recorded"
    record_operation(
        session,
        company_id=tenant.company_id,
        actor_user_id=tenant.user_id,
        action=action,
        entity_type="DailyTenderReconciliation",
        entity_id=row.id,
        before={"cash_minor": row.before_cash_minor, "upi_minor": row.before_upi_minor},
        after={
            "actual_cash_minor": row.actual_cash_minor,
            "actual_upi_minor": row.actual_upi_minor,
            "business_date": str(row.business_date),
            "kind": row.kind,
            "cash_delta_minor": row.cash_delta_minor,
            "upi_delta_minor": row.upi_delta_minor,
            "total_difference_minor": row.total_difference_minor,
            "source_allocation": "unallocated_owner_attestation",
        },
        reason=row.reason,
        operation_id=str(row.id),
        source_reference="DR-" + str(row.id),
    )
    if row.kind == "applied":
        await enqueue_tender_daily_snapshot(
            session,
            company_id=row.company_id,
            branch_id=row.branch_id,
            business_date=row.business_date,
        )
    for resource in ("finance", "audit"):
        background_tasks.add_task(realtime_manager.broadcast, row.company_id, resource)
    return receipt_read(row)


@router.get("/history", response_model=list[TenderReceipt])
async def history(
    branch_id: UUID,
    session: SessionDep,
    tenant: TenderOwner,
    business_date: date | None = None,
    payment_id: UUID | None = None,
    x_tender_token: str | None = Header(default=None),
    limit: int = Query(default=50, ge=1, le=100),
):
    await require_confirmation(
        session, tenant, x_tender_token, branch_id=branch_id, scope="history"
    )
    result = []
    for cls in (PaymentTenderCorrection, DailyTenderReconciliation):
        if payment_id is not None and cls == DailyTenderReconciliation:
            continue
        stmt = select(cls).where(cls.company_id == tenant.company_id, cls.branch_id == branch_id)
        if business_date is not None:
            stmt = stmt.where(cls.business_date == business_date)
        if payment_id is not None:
            stmt = stmt.where(cls.payment_id == payment_id)
        result.extend(
            receipt_read(row)
            for row in await session.scalars(
                stmt.order_by(cls.recorded_at.desc(), cls.id.desc()).limit(limit)
            )
        )
    return sorted(result, key=lambda row: (row.recorded_at, str(row.id)), reverse=True)[:limit]
