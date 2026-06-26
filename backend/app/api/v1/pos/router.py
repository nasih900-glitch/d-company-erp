"""POS endpoints — orders, payments, refunds, shifts.

Skeleton: validation + DB scaffolding wired. The full order pipeline
(recipe deduction, journal posting, receipt rendering) is deferred to
the POS deep build.
"""

from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import func, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import (
    Branch,
    Customer,
    CustomerMembership,
    MembershipTier,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    Refund,
    Shift,
)
from app.services.inventory.deduction import deduct_for_order
from app.services.pos.pricing import (
    InvoiceNumberService,
    LineRequest,
    OrderPricingService,
)

router = APIRouter()


# ----------- schemas -----------
class OrderLineCreate(BaseModel):
    menu_item_id: UUID
    variant_id: UUID | None = None
    qty: float = Field(gt=0)
    modifiers: list[dict] | None = None
    note: str | None = None


class OrderCreate(BaseModel):
    type: Literal["dine_in", "takeaway", "delivery"]
    table_id: UUID | None = None
    shift_id: UUID
    lines: list[OrderLineCreate]
    delivery_via: Literal["inhouse", "zomato", "swiggy", "ubereats", "other_aggregator"] | None = None
    customer_name: str | None = None
    customer_phone: str | None = None
    customer_gstin: str | None = Field(default=None, max_length=15)
    customer_address: str | None = Field(default=None, max_length=500)
    customer_state_code: str | None = Field(default=None, pattern=r"^\d{2}$")
    place_of_supply_state_code: str | None = Field(default=None, pattern=r"^\d{2}$")
    notes: str | None = None


class OrderLineRead(BaseModel):
    menu_item_id: UUID
    name: str
    sku: str
    hsn_or_sac: str
    qty: float
    unit_price_minor: int
    line_total_minor: int
    taxable_value_minor: int
    tax_rate: float
    cgst_minor: int
    sgst_minor: int
    igst_minor: int


class OrderRead(BaseModel):
    id: UUID
    invoice_no: str | None = None
    fiscal_year: str | None = None
    status: str
    type: str
    subtotal_minor: int
    discount_minor: int
    cgst_minor: int = 0
    sgst_minor: int = 0
    igst_minor: int = 0
    cess_minor: int = 0
    tax_minor: int
    round_off_minor: int = 0
    total_minor: int
    delivery_via: str | None = None
    place_of_supply_state_code: str | None = None
    customer_name: str | None = None
    customer_phone: str | None = None
    customer_gstin: str | None = None
    customer_state_code: str | None = None
    lines: list[OrderLineRead] = []


class PaymentCreate(BaseModel):
    method: Literal["cash", "card", "upi", "qr", "wallet"]
    amount_minor: int = Field(gt=0)
    tendered_minor: int | None = None
    ref_external: str | None = None


class RefundCreate(BaseModel):
    reason_code: str
    amount_minor: int = Field(gt=0)
    mode: Literal["cash", "original", "credit_note"] = "original"
    manager_override_user_id: UUID | None = None
    note: str | None = None


class ShiftOpenRequest(BaseModel):
    opening_float_minor: int = 0


def _require_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError("Idempotency-Key header required for POS money writes")
    return str(key), str(request_hash)


async def _compute_points_with_multiplier(
    session,
    *,
    order: Order,
    order_lines: list[OrderLine],
    membership_multiplier: float = 1.0,
) -> int:
    """Loyalty point allocation with category multipliers.

    Rules:
      - Food/drink/dessert: 1 point per ₹10
      - Gaming / hookah / event tickets: 2 points per ₹10  (high-margin, encourages repeat visits)
      - Multiplied by the customer's membership multiplier (e.g. 1.5× for Gold tier).
    """
    if not order_lines:
        return order.total_minor // 1000  # safe fallback

    # Pull each line's menu item type in one query
    item_ids = [ol.menu_item_id for ol in order_lines]
    items = (
        await session.execute(select(MenuItem).where(MenuItem.id.in_(item_ids)))
    ).scalars().all()
    type_by_id = {i.id: i.type for i in items}

    high_margin = {"gaming", "hookah", "event"}
    points = 0.0
    for ol in order_lines:
        line_total = int(ol.line_total_minor or 0)
        item_type = type_by_id.get(ol.menu_item_id, "food")
        multiplier = 2.0 if item_type in high_margin else 1.0
        points += (line_total / 1000) * multiplier
    return int(points * membership_multiplier)


async def _upsert_and_attach_customer(
    session,
    *,
    company_id: UUID,
    phone: str,
    name: str | None,
    order: Order,
    order_lines: list[OrderLine] | None = None,
) -> None:
    """Find or create customer by phone, bump visit_count + total_spent,
    award loyalty points (1× food, 2× gaming/hookah/events, × membership tier).
    """
    existing = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == company_id,
                Customer.phone == phone,
                Customer.deleted_at.is_(None),
            )
        )
    ).scalar_one_or_none()
    now = datetime.now(timezone.utc)

    # Resolve membership multiplier if customer already exists and has an active sub
    multiplier = 1.0
    if existing:
        sub = (
            await session.execute(
                select(CustomerMembership, MembershipTier)
                .join(MembershipTier, MembershipTier.id == CustomerMembership.tier_id)
                .where(
                    CustomerMembership.customer_id == existing.id,
                    CustomerMembership.cancelled_at.is_(None),
                    CustomerMembership.expires_at > now,
                )
                .limit(1)
            )
        ).first()
        if sub:
            multiplier = float(sub.MembershipTier.point_multiplier or 1)

    points_earned = await _compute_points_with_multiplier(
        session, order=order, order_lines=order_lines or [],
        membership_multiplier=multiplier,
    )

    if existing:
        existing.visit_count += 1
        existing.total_spent_minor += order.total_minor
        existing.last_visit_at = now
        existing.loyalty_points += int(points_earned)
        if name and not existing.name:
            existing.name = name
    else:
        session.add(
            Customer(
                id=uuid4(),
                company_id=company_id,
                phone=phone,
                name=name,
                visit_count=1,
                total_spent_minor=order.total_minor,
                first_visit_at=now,
                last_visit_at=now,
                loyalty_points=int(points_earned),
            )
        )


class ShiftCloseRequest(BaseModel):
    counted_minor: int


async def _paid_total(session, order_id: UUID) -> int:
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Payment.amount_minor), 0)).where(
                    Payment.order_id == order_id
                )
            )
        ).scalar_one()
        or 0
    )


async def _refunded_total(session, order_id: UUID) -> int:
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Refund.amount_minor), 0)).where(
                    Refund.order_id == order_id
                )
            )
        ).scalar_one()
        or 0
    )


# ----------- endpoints -----------
@router.post(
    "/orders",
    response_model=OrderRead,
    status_code=status.HTTP_201_CREATED,
    summary="Create an order (POS billing entry point)",
)
async def create_order(
    payload: OrderCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return OrderRead.model_validate(existing_response["body"])

    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if not payload.lines:
        raise BusinessRuleError("order must have at least one line")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")

    branch = await session.get(Branch, tenant.branch_id)
    if not branch:
        raise NotFoundError("branch not found")
    shift = await session.get(Shift, payload.shift_id)
    if not shift or shift.company_id != tenant.company_id or shift.branch_id != tenant.branch_id:
        raise NotFoundError("shift not found")
    if shift.status != "open":
        raise BusinessRuleError(f"shift is not open (status={shift.status})")
    if shift.terminal_id != tenant.terminal_id:
        raise BusinessRuleError("order shift belongs to a different terminal")

    branch_state = branch.state_code or "32"
    delivery_via = payload.delivery_via if payload.type == "delivery" else None
    if payload.type == "delivery" and delivery_via is None:
        delivery_via = "inhouse"
    place_of_supply = (
        payload.place_of_supply_state_code
        or (payload.customer_state_code if payload.type == "delivery" else None)
        or branch_state
    )

    # 1. Price the order with the India tax engine.
    pricing = OrderPricingService(session)
    priced = await pricing.price_order(
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        customer_phone=payload.customer_phone,
        place_of_supply_state_code=place_of_supply,
        delivery_via=delivery_via,
        line_requests=[
            LineRequest(menu_item_id=line.menu_item_id, qty=int(line.qty))
            for line in payload.lines
        ],
    )

    # 2. Allocate the next sequential invoice number for this branch + FY.
    invoice_no, fy = await InvoiceNumberService(session).allocate(
        branch_id=tenant.branch_id,
        branch_code=branch.code or "MN",
    )

    # 3. Insert the order header.
    order = Order(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        shift_id=payload.shift_id,
        opened_by=tenant.user_id,
        table_id=payload.table_id,
        type=payload.type,
        delivery_via=delivery_via,
        status="open",
        opened_at=datetime.now(timezone.utc),
        subtotal_minor=priced.subtotal_taxable_minor,
        cgst_minor=priced.cgst_minor,
        sgst_minor=priced.sgst_minor,
        igst_minor=priced.igst_minor,
        cess_minor=priced.cess_minor,
        discount_minor=priced.discount_minor,
        tax_minor=priced.cgst_minor + priced.sgst_minor + priced.igst_minor + priced.cess_minor,
        round_off_minor=priced.round_off_minor,
        total_minor=priced.total_minor,
        idempotency_key=idempotency_key,
        invoice_no=invoice_no,
        fiscal_year=fy,
        place_of_supply_state_code=place_of_supply,
        customer_name=payload.customer_name,
        customer_phone=payload.customer_phone,
        customer_gstin=payload.customer_gstin.upper() if payload.customer_gstin else None,
        customer_address=payload.customer_address,
        customer_state_code=payload.customer_state_code,
        notes=payload.notes,
    )
    session.add(order)
    await session.flush()

    # 4. Insert priced order lines.
    order_lines: list[OrderLine] = []
    for priced_line in priced.lines:
        ol = OrderLine(
            id=uuid4(),
            order_id=order.id,
            menu_item_id=priced_line.menu_item_id,
            qty=priced_line.qty,
            unit_price_minor=priced_line.unit_inclusive_minor,
            line_total_minor=priced_line.line_inclusive_minor,
            discount_minor=priced_line.discount_minor,
            hsn_or_sac=priced_line.hsn_or_sac,
            tax_rate=float(priced_line.tax_rate),
            taxable_value_minor=priced_line.taxable_value_minor,
            cgst_minor=priced_line.cgst_minor,
            sgst_minor=priced_line.sgst_minor,
            igst_minor=priced_line.igst_minor,
            cess_minor=priced_line.cess_minor,
        )
        session.add(ol)
        order_lines.append(ol)
    await session.flush()

    # 5. Recipe-driven inventory deduction (FIFO, audit-logged).
    try:
        await deduct_for_order(
            session,
            order_id=order.id,
            order_lines=order_lines,
            branch_id=tenant.branch_id,
            created_by=tenant.user_id,
        )
    except Exception as e:
        # Non-fatal: a missing recipe or batch shouldn't block the sale.
        # The next inventory audit will surface the discrepancy. We log
        # the failure to stderr so it shows up in `docker compose logs`.
        import logging
        logging.getLogger("pos").warning(
            "Inventory deduction failed for order %s: %s", order.id, e,
        )

    # 6. Upsert + attach customer (loyalty foundation, with point multipliers).
    if payload.customer_phone:
        await _upsert_and_attach_customer(
            session,
            company_id=tenant.company_id,
            phone=payload.customer_phone,
            name=payload.customer_name,
            order=order,
            order_lines=order_lines,
        )

    await session.flush()
    response = OrderRead(
        id=order.id,
        invoice_no=order.invoice_no,
        fiscal_year=order.fiscal_year,
        status=order.status,
        type=order.type,
        subtotal_minor=order.subtotal_minor,
        discount_minor=order.discount_minor,
        cgst_minor=order.cgst_minor,
        sgst_minor=order.sgst_minor,
        igst_minor=order.igst_minor,
        cess_minor=order.cess_minor,
        tax_minor=order.tax_minor,
        round_off_minor=order.round_off_minor,
        total_minor=order.total_minor,
        delivery_via=order.delivery_via,
        place_of_supply_state_code=order.place_of_supply_state_code,
        customer_name=order.customer_name,
        customer_phone=order.customer_phone,
        customer_gstin=order.customer_gstin,
        customer_state_code=order.customer_state_code,
        lines=[
            OrderLineRead(
                menu_item_id=pl.menu_item_id,
                name=pl.name,
                sku=pl.sku,
                hsn_or_sac=pl.hsn_or_sac,
                qty=pl.qty,
                unit_price_minor=pl.unit_inclusive_minor,
                line_total_minor=pl.line_inclusive_minor,
                taxable_value_minor=pl.taxable_value_minor,
                tax_rate=float(pl.tax_rate),
                cgst_minor=pl.cgst_minor,
                sgst_minor=pl.sgst_minor,
                igst_minor=pl.igst_minor,
            )
            for pl in priced.lines
        ],
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


@router.get("/orders/{order_id}", response_model=OrderRead)
async def get_order(
    order_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> OrderRead:
    order = await session.get(Order, order_id)
    if not order or order.company_id != tenant.company_id:
        raise NotFoundError("order not found")
    return OrderRead(
        id=order.id,
        status=order.status,
        type=order.type,
        subtotal_minor=order.subtotal_minor,
        discount_minor=order.discount_minor,
        cgst_minor=order.cgst_minor,
        sgst_minor=order.sgst_minor,
        igst_minor=order.igst_minor,
        cess_minor=order.cess_minor,
        tax_minor=order.tax_minor,
        round_off_minor=order.round_off_minor,
        total_minor=order.total_minor,
        delivery_via=order.delivery_via,
        place_of_supply_state_code=order.place_of_supply_state_code,
        customer_name=order.customer_name,
        customer_phone=order.customer_phone,
        customer_gstin=order.customer_gstin,
        customer_state_code=order.customer_state_code,
    )


class OrderListItem(BaseModel):
    """Slim row for the order-history list."""
    id: UUID
    invoice_no: str | None
    type: str
    status: str
    total_minor: int
    items_count: int
    customer_name: str | None
    created_at: datetime


@router.get("/orders", response_model=list[OrderListItem])
async def list_orders(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    limit: int = 200,
) -> list[OrderListItem]:
    """List orders, newest first. Defaults to today if no date filter given."""
    from datetime import time as _time
    today = datetime.now(timezone.utc).date()
    f_d = from_date or today
    t_d = to_date or today
    f_dt = datetime.combine(f_d, _time.min, tzinfo=timezone.utc)
    t_dt = datetime.combine(t_d, _time.max, tzinfo=timezone.utc)

    stmt = (
        select(Order)
        .where(
            Order.company_id == tenant.company_id,
            Order.created_at >= f_dt,
            Order.created_at <= t_dt,
        )
        .order_by(Order.created_at.desc())
        .limit(min(limit, 500))
    )
    rows = (await session.execute(stmt)).scalars().all()
    out: list[OrderListItem] = []
    for o in rows:
        items_count = (
            await session.execute(
                select(func.count())
                .select_from(OrderLine)
                .where(OrderLine.order_id == o.id)
            )
        ).scalar_one()
        out.append(OrderListItem(
            id=o.id,
            invoice_no=o.invoice_no,
            type=o.type,
            status=o.status,
            total_minor=o.total_minor,
            items_count=int(items_count or 0),
            customer_name=o.customer_name,
            created_at=o.created_at,
        ))
    return out


class ShiftRead(BaseModel):
    id: UUID
    status: str
    opened_at: datetime
    closed_at: datetime | None
    opening_float_minor: int
    expected_minor: int | None
    counted_minor: int | None
    variance_minor: int | None


@router.get("/shifts", response_model=list[ShiftRead])
async def list_shifts(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    only_open: bool = False,
    limit: int = 50,
) -> list[ShiftRead]:
    stmt = (
        select(Shift)
        .where(Shift.company_id == tenant.company_id)
        .order_by(Shift.opened_at.desc())
        .limit(min(limit, 200))
    )
    if only_open:
        stmt = stmt.where(Shift.status == "open")
    rows = (await session.execute(stmt)).scalars().all()
    return [
        ShiftRead(
            id=s.id, status=s.status,
            opened_at=s.opened_at, closed_at=s.closed_at,
            opening_float_minor=int(s.opening_float_minor or 0),
            expected_minor=int(s.expected_minor) if s.expected_minor is not None else None,
            counted_minor=int(s.counted_minor) if s.counted_minor is not None else None,
            variance_minor=int(s.variance_minor) if s.variance_minor is not None else None,
        )
        for s in rows
    ]


@router.post("/orders/{order_id}/payments", status_code=status.HTTP_201_CREATED)
async def record_payment(
    order_id: UUID,
    payload: PaymentCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> dict:
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return existing_response["body"]

    order = await session.get(Order, order_id)
    if not order or order.company_id != tenant.company_id:
        raise NotFoundError("order not found")
    if order.status in {"paid", "void", "refunded"}:
        raise BusinessRuleError(f"cannot pay an order in status={order.status}")
    shift = await session.get(Shift, order.shift_id)
    if not shift or shift.company_id != tenant.company_id:
        raise NotFoundError("shift not found")
    if shift.status != "open":
        raise BusinessRuleError(f"shift is not open (status={shift.status})")

    already_paid = await _paid_total(session, order_id)
    due_minor = max(0, int(order.total_minor or 0) - already_paid)
    if due_minor <= 0:
        order.status = "paid"
        order.closed_at = order.closed_at or datetime.now(timezone.utc)
        raise BusinessRuleError("order is already fully paid")
    if payload.amount_minor > due_minor:
        raise BusinessRuleError("payment exceeds amount due")
    if (
        payload.method == "cash"
        and payload.tendered_minor is not None
        and payload.tendered_minor < payload.amount_minor
    ):
        raise BusinessRuleError("cash tendered cannot be less than payment amount")

    now = datetime.now(timezone.utc)
    payment = Payment(
        id=uuid4(),
        order_id=order_id,
        shift_id=order.shift_id,
        method=payload.method,
        amount_minor=payload.amount_minor,
        tendered_minor=payload.tendered_minor,
        change_minor=(payload.tendered_minor - payload.amount_minor)
        if payload.tendered_minor and payload.method == "cash"
        else None,
        ref_external=payload.ref_external,
        paid_at=now,
    )
    session.add(payment)
    if payload.method == "cash":
        shift.expected_minor = int(shift.expected_minor or 0) + payload.amount_minor
    if already_paid + payload.amount_minor >= order.total_minor:
        order.status = "paid"
        order.closed_at = now
    response = {
        "id": str(payment.id),
        "amount_minor": payment.amount_minor,
        "order_status": order.status,
    }
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response,
    )
    return response


@router.post("/orders/{order_id}/refunds", status_code=status.HTTP_201_CREATED)
async def issue_refund(
    order_id: UUID,
    payload: RefundCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.refund")),
) -> dict:
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return existing_response["body"]

    order = await session.get(Order, order_id)
    if not order or order.company_id != tenant.company_id:
        raise NotFoundError("order not found")
    paid_total = await _paid_total(session, order_id)
    refunded_total = await _refunded_total(session, order_id)
    refundable_minor = paid_total - refunded_total
    if refundable_minor <= 0:
        raise BusinessRuleError("order has no refundable payment balance")
    if payload.amount_minor > refundable_minor:
        raise BusinessRuleError("refund exceeds paid balance")
    refund = Refund(
        id=uuid4(),
        order_id=order_id,
        approved_by=tenant.user_id,
        manager_override_user_id=payload.manager_override_user_id,
        reason_code=payload.reason_code,
        amount_minor=payload.amount_minor,
        mode=payload.mode,
        note=payload.note,
    )
    session.add(refund)
    if payload.mode == "cash":
        shift = await session.get(Shift, order.shift_id)
        if shift and shift.company_id == tenant.company_id and shift.status == "open":
            shift.expected_minor = int(shift.expected_minor or 0) - payload.amount_minor
    if refunded_total + payload.amount_minor >= paid_total:
        order.status = "refunded"
    response = {"id": str(refund.id)}
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response,
    )
    return response


@router.post("/shifts/open", status_code=status.HTTP_201_CREATED)
async def open_shift(
    payload: ShiftOpenRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.shift.open")),
) -> dict:
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required to open a shift")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
    existing = (
        await session.execute(
            select(Shift).where(
                Shift.company_id == tenant.company_id,
                Shift.terminal_id == tenant.terminal_id,
                Shift.status == "open",
            )
        )
    ).scalar_one_or_none()
    if existing:
        return {"id": str(existing.id), "status": existing.status}
    shift = Shift(
        id=uuid4(),
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        opened_by=tenant.user_id,
        opened_at=datetime.now(timezone.utc),
        opening_float_minor=payload.opening_float_minor,
        expected_minor=payload.opening_float_minor,
        status="open",
    )
    session.add(shift)
    return {"id": str(shift.id), "status": "open"}


@router.post("/shifts/{shift_id}/close")
async def close_shift(
    shift_id: UUID,
    payload: ShiftCloseRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.shift.close")),
) -> dict:
    shift = await session.get(Shift, shift_id)
    if not shift or shift.company_id != tenant.company_id:
        raise NotFoundError("shift not found")
    if shift.status != "open":
        raise BusinessRuleError(f"shift is not open (status={shift.status})")
    shift.closed_at = datetime.now(timezone.utc)
    shift.counted_minor = payload.counted_minor
    shift.variance_minor = payload.counted_minor - (shift.expected_minor or 0)
    shift.status = "closed"
    return {
        "id": str(shift.id),
        "status": shift.status,
        "variance_minor": shift.variance_minor,
    }
