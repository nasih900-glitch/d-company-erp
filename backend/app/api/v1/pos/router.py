"""POS endpoints — orders, payments, refunds, shifts.

Skeleton: validation + DB scaffolding wired. The full order pipeline
(recipe deduction, journal posting, receipt rendering) is deferred to
the POS deep build.
"""

from __future__ import annotations

from datetime import date, datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Query, Request, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import func, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import (
    Branch,
    Customer,
    CustomerMembership,
    Floor,
    GamingSession,
    MembershipTier,
    MenuItem,
    Order,
    OrderLine,
    Payment,
    PointsRedemption,
    Refund,
    Shift,
    Station,
    Table,
    Terminal,
    User,
)
from app.services.inventory.deduction import deduct_for_order, restock_for_refund
from app.services.pos.membership_benefits import (
    applied_benefits_for_order,
    consume_membership_benefits,
    reserve_membership_benefits,
)
from app.services.pos.order_validation import require_operational_order
from app.services.pos.points import (
    consume_points_redemption,
    minor_to_points,
    points_redeemed_for_order,
    rank_up_bonus_points,
    reserve_catalog_reward_redemption,
    reserve_points_redemption,
    reward_by_key,
    rewards_available_to,
)
from app.services.pos.pricing import (
    InvoiceNumberService,
    LineRequest,
    OrderPricingService,
    _round_to_rupee,
    apply_manual_discount,
    apply_points_redemption,
    gaming_minutes_allowance_minor,
)
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_operational_shift_scope,
    require_shift_opener,
)

router = APIRouter()
_KITCHEN_ITEM_TYPES = {"food", "drink", "dessert"}
_KITCHEN_LINE_STATUS_BY_ORDER_STATE = {
    "received": "queued",
    "preparing": "cooking",
    "ready": "ready",
    "served": "served",
}


# ----------- schemas -----------
class OrderLineCreate(BaseModel):
    menu_item_id: UUID
    variant_id: UUID | None = None
    qty: int = Field(gt=0)
    modifiers: list[dict] | None = None
    note: str | None = Field(default=None, max_length=500)


class OrderCreate(BaseModel):
    type: Literal["dine_in", "takeaway", "delivery", "session"]
    table_id: UUID | None = None
    shift_id: UUID
    lines: list[OrderLineCreate] = Field(min_length=1, max_length=100)
    delivery_via: Literal["inhouse", "zomato", "swiggy", "ubereats", "other_aggregator"] | None = None
    customer_name: str | None = Field(default=None, max_length=200)
    customer_phone: str | None = Field(default=None, max_length=20)
    customer_gstin: str | None = Field(default=None, max_length=15)
    customer_address: str | None = Field(default=None, max_length=500)
    customer_state_code: str | None = Field(default=None, pattern=r"^\d{2}$")
    place_of_supply_state_code: str | None = Field(default=None, pattern=r"^\d{2}$")
    notes: str | None = Field(default=None, max_length=500)

    @field_validator("customer_name", "customer_phone")
    @classmethod
    def normalize_optional_customer_identity(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None


class OrderLineRead(BaseModel):
    menu_item_id: UUID
    variant_id: UUID | None = None
    modifiers: list[dict] | None = None
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
    note: str | None = None


class OrderRead(BaseModel):
    id: UUID
    invoice_no: str | None = None
    fiscal_year: str | None = None
    status: str
    type: str
    table_id: UUID | None = None
    source_label: str | None = None
    subtotal_minor: int
    discount_minor: int
    manual_discount_minor: int = 0
    points_redeemed_minor: int = 0
    points_redeemed: int = 0
    cgst_minor: int = 0
    sgst_minor: int = 0
    igst_minor: int = 0
    cess_minor: int = 0
    tax_minor: int
    round_off_minor: int = 0
    total_minor: int
    paid_minor: int = 0
    due_minor: int = 0
    free_gaming_minutes_applied: int = 0
    free_hookah_count_applied: int = 0
    delivery_via: str | None = None
    place_of_supply_state_code: str | None = None
    customer_name: str | None = None
    customer_phone: str | None = None
    customer_gstin: str | None = None
    customer_state_code: str | None = None
    opened_at: datetime
    closed_at: datetime | None = None
    invoice_issued_at: datetime | None = None
    held_at: datetime | None = None
    lines: list[OrderLineRead] = Field(default_factory=list)


class PaymentCreate(BaseModel):
    method: Literal["cash", "card", "upi", "qr", "wallet"]
    amount_minor: int = Field(gt=0)
    tendered_minor: int | None = None
    ref_external: str | None = Field(default=None, max_length=200)
    expected_order_total_minor: int | None = Field(default=None, ge=0)
    expected_due_minor: int | None = Field(default=None, ge=0)


class OrderCustomerUpdate(BaseModel):
    customer_name: str | None = Field(default=None, max_length=200)
    customer_phone: str | None = Field(default=None, max_length=20)

    @field_validator("customer_name", "customer_phone")
    @classmethod
    def normalize_optional_customer_identity(cls, value: str | None) -> str | None:
        normalized = value.strip() if value else ""
        return normalized or None


class OrderDiscountUpdate(BaseModel):
    # Absolute set, not an increment — re-sending the same value is a no-op,
    # and a cashier who changes their mind sends the new total, not a delta.
    manual_discount_minor: int = Field(ge=0)


class OrderPointsRedemptionUpdate(BaseModel):
    # Absolute set of how many points the customer wants to spend on this
    # bill, not an increment. Requires a customer already attached to the
    # order (points belong to a specific phone number's balance).
    points: int = Field(ge=0)


class RefundCreate(BaseModel):
    reason_code: str = Field(min_length=1, max_length=50)
    amount_minor: int = Field(gt=0)
    mode: Literal["cash", "original", "credit_note"] = "original"
    manager_override_user_id: UUID | None = None
    note: str | None = Field(default=None, max_length=500)


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
    """Loyalty point allocation — gaming only.

    Rules:
      - Gaming (PS5 / VR / simulator sessions): 2 points per ₹10 spent.
      - Food, drinks, hookah, streaming, event tickets: earn nothing. The
        points program is a gaming rewards ladder, not a general discount.
      - Multiplied by the customer's membership multiplier (e.g. 1.5× for Gold tier).

    Earned on what was actually collected: line_total_minor reflects
    line/membership discounts, but not order.manual_discount_minor or
    order.points_redeemed_minor (both order-level, layered on after line
    pricing). Scaling by the paid ratio stops a customer from re-earning
    points on money a discount or a points redemption already covered.
    """
    if not order_lines:
        return 0  # no lines to attribute to gaming — nothing earned

    # Pull each line's menu item type in one query
    item_ids = [ol.menu_item_id for ol in order_lines]
    items = (
        await session.execute(select(MenuItem).where(MenuItem.id.in_(item_ids)))
    ).scalars().all()
    type_by_id = {i.id: i.type for i in items}

    GAMING_POINTS_PER_10_RUPEES = 2.0
    raw_points = 0.0
    for ol in order_lines:
        if type_by_id.get(ol.menu_item_id) != "gaming":
            continue
        line_total = int(ol.line_total_minor or 0)
        raw_points += (line_total / 1000) * GAMING_POINTS_PER_10_RUPEES

    pre_discount_total = (
        int(order.total_minor or 0)
        + int(order.manual_discount_minor or 0)
        + int(order.points_redeemed_minor or 0)
    )
    paid_ratio = (
        min(1.0, order.total_minor / pre_discount_total) if pre_discount_total > 0 else 0.0
    )
    return int(raw_points * paid_ratio * membership_multiplier)


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
    award loyalty points (1× food, 2× gaming/hookah/streaming/events, × membership tier).
    """
    existing = (
        await session.execute(
            select(Customer).where(
                Customer.company_id == company_id,
                Customer.phone == phone,
                Customer.deleted_at.is_(None),
            ).with_for_update()
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
                    CustomerMembership.starts_at <= now,
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
        old_lifetime = int(existing.lifetime_gaming_points_earned or 0)
        new_lifetime = old_lifetime + int(points_earned)
        bonus = rank_up_bonus_points(old_lifetime=old_lifetime, new_lifetime=new_lifetime)
        existing.visit_count += 1
        existing.total_spent_minor += order.total_minor
        existing.last_visit_at = now
        existing.loyalty_points += int(points_earned) + bonus
        # Points are gaming-only now (see _compute_points_with_multiplier),
        # so every point earned is a gaming point — this counter just never
        # goes back down when loyalty_points is spent (see points.py rank_progress).
        # The rank-up bonus is credited to loyalty_points ONLY, never here —
        # crediting it here would let a bonus cascade into unlocking the next
        # rank too.
        existing.lifetime_gaming_points_earned = new_lifetime
        if name and not existing.name:
            existing.name = name
    else:
        bonus = rank_up_bonus_points(old_lifetime=0, new_lifetime=int(points_earned))
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
                loyalty_points=int(points_earned) + bonus,
                lifetime_gaming_points_earned=int(points_earned),
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


def _validate_confirmed_payment_balance(
    payload: PaymentCreate,
    *,
    order_total_minor: int,
    due_minor: int,
) -> None:
    """Protect a cashier-confirmed full settlement from stale bill state."""
    if (
        payload.expected_order_total_minor is not None
        and payload.expected_order_total_minor != order_total_minor
    ):
        raise BusinessRuleError(
            "Order total changed before payment. Reload the exact bill before collecting money."
        )
    if payload.expected_due_minor is not None and payload.expected_due_minor != due_minor:
        raise BusinessRuleError(
            "Order balance changed before payment. Reload the exact amount due before collecting money."
        )
    if due_minor > 0 and payload.amount_minor != due_minor:
        raise BusinessRuleError(
            "Split payments are not enabled. Payment must equal the exact amount due."
        )


def _stored_line_gross_amount(line: OrderLine, *, price_includes_tax: bool) -> int:
    """Recover the snapshotted pre-membership amount without current menu prices."""
    discount = int(line.discount_minor or 0)
    if price_includes_tax:
        return max(0, int(line.line_total_minor or 0) + discount)
    return max(0, int(line.taxable_value_minor or 0) + discount)


async def _reprice_unpaid_order_for_customer(
    session,
    *,
    order: Order,
    company_id: UUID,
) -> None:
    """Apply the attached customer's current membership to stored gross lines.

    Repricing uses each order line's existing gross snapshot, not today's menu
    price. A session line uses its GamingSession tax/price-mode snapshot, so a
    later station or catalog edit cannot rewrite the service already consumed.
    """
    lines = (
        await session.execute(
            select(OrderLine).where(OrderLine.order_id == order.id).with_for_update()
        )
    ).scalars().all()
    if not lines:
        raise BusinessRuleError("order has no lines to price")

    item_ids = {line.menu_item_id for line in lines}
    items = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.company_id == company_id,
                MenuItem.id.in_(item_ids),
            )
        )
    ).scalars().all()
    items_by_id = {item.id: item for item in items}
    if len(items_by_id) != len(item_ids):
        raise BusinessRuleError(
            "An order item is missing from the catalog. Ask a protected owner to reconcile it."
        )

    session_row = (
        await session.execute(
            select(GamingSession, Station)
            .join(Station, Station.id == GamingSession.station_id)
            .where(GamingSession.order_id == order.id)
            .limit(1)
        )
    ).first()
    gaming_session = session_row[0] if session_row else None
    station = session_row[1] if session_row else None
    requested_gaming_minutes = (
        max(0, int(gaming_session.billable_minutes or 0))
        if gaming_session
        and station
        and station.type == "ps5"
        and gaming_session.package_id is None
        else 0
    )
    requested_hookah_count = (
        1 if gaming_session and station and station.type == "hookah" else 0
    )
    benefits = await reserve_membership_benefits(
        session,
        order=order,
        company_id=company_id,
        requested_gaming_minutes=requested_gaming_minutes,
        requested_hookah_count=requested_hookah_count,
    )

    pricing = OrderPricingService(session)
    for line in lines:
        item = items_by_id[line.menu_item_id]
        is_session_line = bool(gaming_session and item.sku.startswith("SESSION-"))
        price_includes_tax = bool(item.price_includes_tax)
        item_type = item.type
        gross_amount = _stored_line_gross_amount(
            line,
            price_includes_tax=price_includes_tax,
        )
        tax_rate = Decimal(str(line.tax_rate or 0))
        if is_session_line:
            if gaming_session.rate_includes_tax is not None:
                price_includes_tax = bool(gaming_session.rate_includes_tax)
            gross_amount = int(gaming_session.amount_minor or gross_amount)
            tax_rate = Decimal(str(gaming_session.tax_rate or line.tax_rate or 0))
            item_type = (
                "hookah"
                if station and station.type == "hookah"
                else "streaming"
                if station and station.type == "streaming"
                else "gaming"
            )

        allowance_minor = 0
        if is_session_line and gaming_session and station:
            if (
                station.type == "ps5"
                and benefits.gaming_minutes
                and gaming_session.package_id is None
            ):
                # A package session's amount_minor is a fixed, advertised
                # price (see gaming/router.py) with no proportional
                # relationship to billable_minutes — the elapsed-time waiver
                # math below would let a member leave minutes into a package
                # and have it waived almost in full. Free-minutes benefits
                # only apply to legacy open-ended (non-package) sessions.
                allowance_minor = gaming_minutes_allowance_minor(
                    gross_amount_minor=gross_amount,
                    billable_minutes=int(gaming_session.billable_minutes or 0),
                    reserved_minutes=benefits.gaming_minutes,
                    rate_per_hour_minor=int(gaming_session.rate_per_hour_minor or 0),
                )
            elif station.type == "hookah" and benefits.hookah_count:
                # A hookah allowance is a whole-session benefit.  Use the
                # snapshotted session gross, never today's station rate.
                allowance_minor = gross_amount

        priced = await pricing.price_time_based_line(
            company_id=company_id,
            branch_id=order.branch_id,
            amount_minor=gross_amount,
            tax_rate=tax_rate,
            rate_includes_tax=price_includes_tax,
            customer_phone=order.customer_phone,
            item_type=item_type,
            place_of_supply_state_code=order.place_of_supply_state_code,
            delivery_via=order.delivery_via,
            allowance_minor=allowance_minor,
        )
        qty = max(1, int(line.qty))
        line.unit_price_minor = int(
            (Decimal(priced.total_minor) / Decimal(qty)).quantize(
                Decimal("1"), rounding=ROUND_HALF_UP
            )
        )
        line.line_total_minor = priced.total_minor
        line.discount_minor = priced.discount_minor
        line.taxable_value_minor = priced.taxable_minor
        line.cgst_minor = priced.cgst_minor
        line.sgst_minor = priced.sgst_minor
        line.igst_minor = priced.igst_minor
        line.cess_minor = 0

    raw_total = sum(int(line.line_total_minor or 0) for line in lines)
    rounded_total, round_off = _round_to_rupee(raw_total)
    order.subtotal_minor = sum(int(line.taxable_value_minor or 0) for line in lines)
    line_discount_total = sum(int(line.discount_minor or 0) for line in lines)
    order.cgst_minor = sum(int(line.cgst_minor or 0) for line in lines)
    order.sgst_minor = sum(int(line.sgst_minor or 0) for line in lines)
    order.igst_minor = sum(int(line.igst_minor or 0) for line in lines)
    order.cess_minor = sum(int(line.cess_minor or 0) for line in lines)
    order.tax_minor = order.cgst_minor + order.sgst_minor + order.igst_minor + order.cess_minor
    order.round_off_minor = round_off
    # A cashier's manual discount and a customer's points redemption both
    # live outside this line-based recompute — preserve them (clamped so
    # neither can exceed the new total, e.g. if a membership just waived
    # most of the bill). Points must be re-requested at their PRIOR count,
    # never maxed out to fill the new total — this repricing preserves an
    # existing redemption, it doesn't grow it. Only preserve if the order's
    # customer is still the same one the points were reserved against —
    # attaching a different customer must never silently spend their points.
    previously_redeemed_points = 0
    existing_redemption = (
        await session.execute(
            select(PointsRedemption).where(PointsRedemption.order_id == order.id)
        )
    ).scalar_one_or_none()
    if existing_redemption is not None:
        current_customer_id = (
            await session.execute(
                select(Customer.id).where(
                    Customer.company_id == company_id,
                    Customer.phone == order.customer_phone,
                    Customer.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if current_customer_id == existing_redemption.customer_id:
            previously_redeemed_points = existing_redemption.points_spent
    order.manual_discount_minor, discount_after_manual, total_after_manual = apply_manual_discount(
        line_discount_total_minor=line_discount_total,
        manual_discount_minor=int(order.manual_discount_minor or 0),
        rounded_total_minor=rounded_total,
    )
    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=company_id,
        requested_points=min(previously_redeemed_points, minor_to_points(total_after_manual)),
        at=datetime.now(timezone.utc),
    )
    order.points_redeemed_minor, order.discount_minor, order.total_minor = apply_points_redemption(
        discount_so_far_minor=discount_after_manual,
        points_redeemed_minor=points_result.amount_minor,
        remaining_total_minor=total_after_manual,
    )


async def _source_label(session, order: Order) -> str | None:
    """Human label for where an order came from: its table, or (for a
    gaming/hookah session sent to POS) the station that billed it."""
    if order.table_id is not None:
        table = await session.get(Table, order.table_id)
        return f"Table {table.code}" if table else None
    row = (
        await session.execute(
            select(Station.name)
            .join(GamingSession, GamingSession.station_id == Station.id)
            .where(GamingSession.order_id == order.id)
            .limit(1)
        )
    ).scalar_one_or_none()
    return row


def _reject_unsupported_customizations(lines: list[OrderLineCreate]) -> None:
    """Never accept a priced customization that the tax engine ignores.

    Per-line preparation notes are supported end to end. Variants and priced
    modifiers remain blocked until the menu API exposes and validates them.
    Silently dropping either would undercharge the customer and corrupt the
    receipt snapshot.
    """
    if any(line.variant_id is not None or line.modifiers for line in lines):
        raise BusinessRuleError(
            "Priced variants and modifiers are not enabled yet. "
            "Use the item note for preparation instructions."
        )


async def _kitchen_item_ids(
    session,
    *,
    company_id: UUID,
    menu_item_ids: list[UUID],
) -> set[UUID]:
    """Return requested items that belong on the kitchen display."""
    return set(
        (
            await session.execute(
                select(MenuItem.id).where(
                    MenuItem.company_id == company_id,
                    MenuItem.id.in_(menu_item_ids),
                    MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
                )
            )
        ).scalars().all()
    )


async def _materialize_legacy_kitchen_line_state(session, order: Order) -> None:
    """Backfill an old all-queued batch while its order row is locked.

    The previous KDS changed only Order.kitchen_state. This must run before a
    late line is inserted and the mirror is reset to received, otherwise old
    served dishes and the genuinely new line become indistinguishable.
    """
    target_status = _KITCHEN_LINE_STATUS_BY_ORDER_STATE.get(order.kitchen_state)
    if target_status in {None, "queued"}:
        return
    existing_lines = (
        (
            await session.execute(
                select(OrderLine)
                .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
                .where(
                    OrderLine.order_id == order.id,
                    OrderLine.voided_at.is_(None),
                    MenuItem.company_id == order.company_id,
                    MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
                )
                .order_by(OrderLine.created_at, OrderLine.id)
            )
        )
        .scalars()
        .all()
    )
    if not existing_lines or any(
        (line.kitchen_status or "queued") != "queued" for line in existing_lines
    ):
        return
    for line in existing_lines:
        line.kitchen_status = target_status


async def _release_table_if_no_active_orders(session, order: Order) -> None:
    """Free a table only when no other legacy/open ticket still owns it."""
    if order.table_id is None:
        return
    other_active = int(
        (
            await session.execute(
                select(func.count(Order.id)).where(
                    Order.table_id == order.table_id,
                    Order.id != order.id,
                    Order.status.in_(("open", "held")),
                )
            )
        ).scalar_one()
        or 0
    )
    if other_active:
        return
    table = await session.get(Table, order.table_id)
    if table and table.status == "occupied":
        table.status = "available"


async def _build_order_read(session, order: Order) -> OrderRead:
    line_rows = (
        await session.execute(
            select(OrderLine, MenuItem)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(OrderLine.order_id == order.id)
            .order_by(OrderLine.created_at, OrderLine.id)
        )
    ).all()
    paid_minor = await _paid_total(session, order.id)
    due_minor = max(0, int(order.total_minor or 0) - paid_minor)
    benefits = await applied_benefits_for_order(session, order=order)
    return OrderRead(
        id=order.id,
        invoice_no=order.invoice_no,
        fiscal_year=order.fiscal_year,
        status=order.status,
        type=order.type,
        table_id=order.table_id,
        source_label=await _source_label(session, order),
        subtotal_minor=order.subtotal_minor,
        discount_minor=order.discount_minor,
        manual_discount_minor=order.manual_discount_minor,
        points_redeemed_minor=order.points_redeemed_minor,
        points_redeemed=await points_redeemed_for_order(session, order=order),
        cgst_minor=order.cgst_minor,
        sgst_minor=order.sgst_minor,
        igst_minor=order.igst_minor,
        cess_minor=order.cess_minor,
        tax_minor=order.tax_minor,
        round_off_minor=order.round_off_minor,
        total_minor=order.total_minor,
        paid_minor=paid_minor,
        due_minor=due_minor,
        free_gaming_minutes_applied=benefits.gaming_minutes,
        free_hookah_count_applied=benefits.hookah_count,
        delivery_via=order.delivery_via,
        place_of_supply_state_code=order.place_of_supply_state_code,
        customer_name=order.customer_name,
        customer_phone=order.customer_phone,
        customer_gstin=order.customer_gstin,
        customer_state_code=order.customer_state_code,
        opened_at=order.opened_at,
        closed_at=order.closed_at,
        invoice_issued_at=order.invoice_issued_at,
        held_at=order.held_at,
        lines=[
            OrderLineRead(
                menu_item_id=line.menu_item_id,
                variant_id=line.variant_id,
                modifiers=line.modifiers,
                name=item.name,
                sku=item.sku,
                hsn_or_sac=line.hsn_or_sac or item.hsn_code or "",
                qty=float(line.qty),
                unit_price_minor=int(line.unit_price_minor),
                line_total_minor=int(line.line_total_minor),
                taxable_value_minor=int(line.taxable_value_minor),
                tax_rate=float(line.tax_rate),
                cgst_minor=int(line.cgst_minor),
                sgst_minor=int(line.sgst_minor),
                igst_minor=int(line.igst_minor),
                note=line.note,
            )
            for line, item in line_rows
        ],
    )


async def _finalize_order(
    session,
    *,
    order: Order,
    company_id: UUID,
    actor_user_id: UUID,
    at: datetime,
) -> None:
    """Issue the invoice and run every sale-finalization side effect once.

    The caller holds the order and shift row locks and has proved the balance
    is exactly settled.  Payments and membership-funded zero bills share this
    path so inventory, loyalty, table release, invoice identity, and allowance
    consumption cannot drift apart.
    """
    if order.status not in ("open", "held"):
        raise BusinessRuleError(f"cannot finalize an order in status={order.status}")
    branch = await session.get(Branch, order.branch_id)
    if not branch or branch.company_id != company_id or branch.deleted_at:
        raise NotFoundError("branch not found")
    timezone_name = branch.timezone or await company_timezone(session, company_id)
    if not order.invoice_no:
        order.invoice_no, order.fiscal_year = await InvoiceNumberService(session).allocate(
            branch_id=order.branch_id,
            branch_code=branch.code or "MN",
            at=at,
            timezone_name=timezone_name,
        )

    # This row update is in the same database transaction as the invoice and
    # all other finalization effects. A rollback leaves the allowance reserved,
    # never half-consumed.
    await consume_membership_benefits(session, order_id=order.id, at=at)
    await consume_points_redemption(session, order_id=order.id, at=at)
    order.status = "paid"
    order.closed_at = at
    order.invoice_issued_at = at
    await _release_table_if_no_active_orders(session, order)

    order_lines = (
        await session.execute(select(OrderLine).where(OrderLine.order_id == order.id))
    ).scalars().all()
    await deduct_for_order(
        session,
        order_id=order.id,
        order_lines=list(order_lines),
        branch_id=order.branch_id,
        created_by=actor_user_id,
    )
    if order.customer_phone:
        await _upsert_and_attach_customer(
            session,
            company_id=company_id,
            phone=order.customer_phone,
            name=order.customer_name,
            order=order,
            order_lines=list(order_lines),
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
    _reject_unsupported_customizations(payload.lines)

    branch = await session.get(Branch, tenant.branch_id)
    if not branch:
        raise NotFoundError("branch not found")
    table: Table | None = None
    if payload.table_id is not None:
        # Lock the table before checking for an unfinished ticket. This makes
        # two simultaneous devices serialize instead of creating duplicates.
        table = (
            await session.execute(
                select(Table).where(Table.id == payload.table_id).with_for_update()
            )
        ).scalar_one_or_none()
        if not table:
            raise NotFoundError("table not found")
        floor = await session.get(Floor, table.floor_id)
        if not floor or floor.branch_id != tenant.branch_id:
            raise NotFoundError("table not found")
        existing_table_order = (
            await session.execute(
                select(Order.id).where(
                    Order.company_id == tenant.company_id,
                    Order.branch_id == tenant.branch_id,
                    Order.table_id == payload.table_id,
                    Order.status.in_(("open", "held")),
                ).limit(1)
            )
        ).scalar_one_or_none()
        if existing_table_order is not None:
            raise BusinessRuleError(
                "This table already has an unfinished order. Open that order instead."
            )
    shift = (
        await session.execute(
            select(Shift)
            .where(Shift.id == payload.shift_id)
            .with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="creating an order",
    )
    if payload.table_id is None:
        # A direct POS bill is recovered only on this cashier's device, so only
        # the accountable shift opener may prepare it. Table-originated work is
        # deliberately collaborative and is later selected/billed by cashier.
        require_shift_opener(
            shift,
            user_id=tenant.user_id,
            protected_access=tenant.protected_access,
            operation="create a direct POS order on this shift",
        )

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
    kitchen_item_ids = await _kitchen_item_ids(
        session,
        company_id=tenant.company_id,
        menu_item_ids=[line.menu_item_id for line in payload.lines],
    )

    # 2. Insert an unpaid order. Invoice identity, stock consumption, and
    # loyalty are all finalized atomically when the last payment succeeds.
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
        invoice_no=None,
        fiscal_year=None,
        place_of_supply_state_code=place_of_supply,
        customer_name=payload.customer_name,
        customer_phone=payload.customer_phone,
        customer_gstin=payload.customer_gstin.upper() if payload.customer_gstin else None,
        customer_address=payload.customer_address,
        customer_state_code=payload.customer_state_code,
        notes=payload.notes,
        kitchen_state="received" if kitchen_item_ids else None,
    )
    session.add(order)
    await session.flush()

    # 3. Insert priced order lines.
    order_lines: list[OrderLine] = []
    for requested_line, priced_line in zip(payload.lines, priced.lines, strict=True):
        ol = OrderLine(
            id=uuid4(),
            order_id=order.id,
            menu_item_id=priced_line.menu_item_id,
            variant_id=requested_line.variant_id,
            modifiers=requested_line.modifiers,
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
            note=requested_line.note,
            kitchen_status="queued",
        )
        session.add(ol)
        order_lines.append(ol)

    if table and table.status == "available":
        table.status = "occupied"

    await session.flush()
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_201_CREATED,
        body=response.model_dump(mode="json"),
    )
    return response


class OrderLinesAppend(BaseModel):
    lines: list[OrderLineCreate] = Field(min_length=1, max_length=100)


@router.post("/orders/{order_id}/lines", response_model=OrderRead)
async def add_order_lines(
    order_id: UUID,
    payload: OrderLinesAppend,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Append lines to an order that hasn't been paid yet.

    Used by Tables while building or adding to a dine-in order (status
    stays 'open'), and by POS to add items to an order a Tables/Gaming
    screen already sent over (status 'held', found via search).
    """
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

    _reject_unsupported_customizations(payload.lines)

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="adding items to an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(f"cannot add lines to an order in status={order.status}")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="adding items to an order",
    )
    if order.table_id is None or order.status == "held":
        require_shift_opener(
            shift,
            user_id=tenant.user_id,
            protected_access=tenant.protected_access,
            operation="add POS items to an order on this shift",
        )

    pricing = OrderPricingService(session)
    priced = await pricing.price_order(
        company_id=tenant.company_id,
        branch_id=order.branch_id,
        customer_phone=order.customer_phone,
        place_of_supply_state_code=order.place_of_supply_state_code,
        delivery_via=order.delivery_via,
        line_requests=[
            LineRequest(menu_item_id=line.menu_item_id, qty=int(line.qty))
            for line in payload.lines
        ],
    )
    kitchen_item_ids = await _kitchen_item_ids(
        session,
        company_id=tenant.company_id,
        menu_item_ids=[line.menu_item_id for line in payload.lines],
    )
    if kitchen_item_ids:
        await _materialize_legacy_kitchen_line_state(session, order)
    for requested_line, priced_line in zip(payload.lines, priced.lines, strict=True):
        session.add(OrderLine(
            id=uuid4(),
            order_id=order.id,
            menu_item_id=priced_line.menu_item_id,
            variant_id=requested_line.variant_id,
            modifiers=requested_line.modifiers,
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
            note=requested_line.note,
            kitchen_status="queued",
        ))

    if kitchen_item_ids:
        # A late kitchen item must become visible again even if the prior
        # batch for this ticket had already reached ready/served.
        order.kitchen_state = "received"
        order.kitchen_ready_at = None
    await session.flush()

    # Re-aggregate the WHOLE order (existing + new lines) and re-round once,
    # the same way price_order rounds a freshly created order.
    all_lines = (
        await session.execute(select(OrderLine).where(OrderLine.order_id == order.id))
    ).scalars().all()
    sub_inclusive = sum(int(l.line_total_minor) for l in all_lines)
    rounded, round_off = _round_to_rupee(sub_inclusive)
    order.subtotal_minor = sum(int(l.taxable_value_minor) for l in all_lines)
    order.cgst_minor = sum(int(l.cgst_minor) for l in all_lines)
    order.sgst_minor = sum(int(l.sgst_minor) for l in all_lines)
    order.igst_minor = sum(int(l.igst_minor) for l in all_lines)
    order.cess_minor = sum(int(l.cess_minor or 0) for l in all_lines)
    line_discount_total = sum(int(l.discount_minor) for l in all_lines)
    order.tax_minor = order.cgst_minor + order.sgst_minor + order.igst_minor + order.cess_minor
    order.round_off_minor = round_off
    # Preserve any manual discount and points redemption already applied to
    # this order — adding a line recomputes everything else from scratch,
    # but neither is derived from lines and both must survive the recompute.
    # customer_phone never changes in this endpoint, so no reattribution risk.
    previously_redeemed_points = await points_redeemed_for_order(session, order=order)
    order.manual_discount_minor, discount_after_manual, total_after_manual = apply_manual_discount(
        line_discount_total_minor=line_discount_total,
        manual_discount_minor=int(order.manual_discount_minor or 0),
        rounded_total_minor=rounded,
    )
    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=tenant.company_id,
        requested_points=min(previously_redeemed_points, minor_to_points(total_after_manual)),
        at=datetime.now(timezone.utc),
    )
    order.points_redeemed_minor, order.discount_minor, order.total_minor = apply_points_redemption(
        discount_so_far_minor=discount_after_manual,
        points_redeemed_minor=points_result.amount_minor,
        remaining_total_minor=total_after_manual,
    )

    if order.table_id is not None:
        table = await session.get(Table, order.table_id)
        if table and table.status == "available":
            table.status = "occupied"

    await session.flush()
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/customer", response_model=OrderRead)
async def attach_order_customer(
    order_id: UUID,
    payload: OrderCustomerUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Attach a customer/member and deterministically reprice an unpaid bill.

    This is the POS handoff step for Tables and Gaming/Shisha orders, whose
    operational originator may not know the final paying customer. Only the
    accountable shift opener (or protected owner) may change the bill here.
    """
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

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="attaching a customer to an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change the customer on an order in status={order.status}"
        )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="attaching a customer to an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="attach a customer or membership to this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change the customer after a payment was recorded")

    previous_phone = (order.customer_phone or "").strip() or None
    order.customer_name = payload.customer_name
    order.customer_phone = payload.customer_phone
    if order.customer_phone != previous_phone:
        await _reprice_unpaid_order_for_customer(
            session,
            order=order,
            company_id=tenant.company_id,
        )
    await session.flush()
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/discount", response_model=OrderRead)
async def apply_order_discount(
    order_id: UUID,
    payload: OrderDiscountUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Set (or clear) a cashier-entered custom discount on an unpaid bill.

    This company is GST-unregistered, so there is no proportional tax
    recalculation here — the amount is knocked straight off the final total,
    same as an unregistered business would do on a plain cash memo.
    """
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

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="applying a discount to an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change the discount on an order in status={order.status}"
        )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="applying a discount to an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="apply a discount to this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change the discount after a payment was recorded")

    previous_manual = int(order.manual_discount_minor or 0)
    previous_points_minor = int(order.points_redeemed_minor or 0)
    line_discount_only = int(order.discount_minor or 0) - previous_manual - previous_points_minor
    pre_reduction_total = int(order.total_minor or 0) + previous_manual + previous_points_minor
    new_manual = payload.manual_discount_minor
    if new_manual > pre_reduction_total:
        raise BusinessRuleError("discount cannot exceed the order total")

    order.manual_discount_minor = new_manual
    remaining_after_manual = pre_reduction_total - new_manual

    # A points redemption already on this bill must be re-clamped against
    # whatever room the new discount leaves — it can shrink, never grow,
    # from what was previously reserved.
    previously_redeemed_points = await points_redeemed_for_order(session, order=order)
    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=tenant.company_id,
        requested_points=min(previously_redeemed_points, minor_to_points(remaining_after_manual)),
        at=datetime.now(timezone.utc),
    )
    order.points_redeemed_minor = points_result.amount_minor
    order.discount_minor = line_discount_only + new_manual + points_result.amount_minor
    order.total_minor = remaining_after_manual - points_result.amount_minor

    await session.flush()
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/points", response_model=OrderRead)
async def redeem_points(
    order_id: UUID,
    payload: OrderPointsRedemptionUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Set (or clear) how many loyalty points a customer spends on this bill.

    Points convert to playtime value at a fixed rate (see
    app/services/pos/points.py) and come straight off the total, same as the
    manual discount above. Requires a customer already attached to the order
    — points belong to a specific phone number's balance, never anonymous.
    """
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

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming points on an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change points redemption on an order in status={order.status}"
        )
    if not order.customer_phone:
        raise BusinessRuleError("attach a customer to this order before redeeming points")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming points on an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="redeem points on this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change points redemption after a payment was recorded")

    previous_manual = int(order.manual_discount_minor or 0)
    previous_points_minor = int(order.points_redeemed_minor or 0)
    line_discount_only = int(order.discount_minor or 0) - previous_manual - previous_points_minor
    remaining_after_manual = int(order.total_minor or 0) + previous_points_minor

    max_points_for_bill = minor_to_points(remaining_after_manual)
    if payload.points > max_points_for_bill:
        raise BusinessRuleError(
            f"This bill can only absorb {max_points_for_bill} points "
            f"(₹{remaining_after_manual / 100:.2f})."
        )

    points_result = await reserve_points_redemption(
        session,
        order=order,
        company_id=tenant.company_id,
        requested_points=payload.points,
        at=datetime.now(timezone.utc),
    )
    if points_result.points_spent < payload.points:
        raise BusinessRuleError("Not enough points available on this customer's balance.")

    order.points_redeemed_minor = points_result.amount_minor
    order.discount_minor = line_discount_only + previous_manual + points_result.amount_minor
    order.total_minor = remaining_after_manual - points_result.amount_minor

    await session.flush()
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


class OrderRewardRedemptionUpdate(BaseModel):
    reward_key: str


@router.patch("/orders/{order_id}/reward", response_model=OrderRead)
async def redeem_reward(
    order_id: UUID,
    payload: OrderRewardRedemptionUpdate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Redeem a named REWARD_CATALOG item (see app/services/pos/points.py) —
    a fixed points cost for a fixed discount value, gated by the customer's
    gaming rank. All-or-nothing, unlike the generic points endpoint above.
    """
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

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming a reward on an order",
    )
    if order.status not in ("open", "held"):
        raise BusinessRuleError(
            f"cannot change reward redemption on an order in status={order.status}"
        )
    if not order.customer_phone:
        raise BusinessRuleError("attach a customer to this order before redeeming a reward")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="redeeming a reward on an order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="redeem a reward on this bill",
    )
    if await _paid_total(session, order.id):
        raise BusinessRuleError("cannot change reward redemption after a payment was recorded")

    previous_manual = int(order.manual_discount_minor or 0)
    previous_points_minor = int(order.points_redeemed_minor or 0)
    line_discount_only = int(order.discount_minor or 0) - previous_manual - previous_points_minor
    remaining_after_manual = int(order.total_minor or 0) + previous_points_minor

    # reserve_catalog_reward_redemption checks the reward's value against
    # order.total_minor as "remaining bill room" — same convention the manual
    # discount / generic points paths use, so set it to the post-manual-
    # discount remainder (pre-existing-points-redemption) before calling it.
    order.total_minor = remaining_after_manual
    points_result = await reserve_catalog_reward_redemption(
        session,
        order=order,
        company_id=tenant.company_id,
        reward_key=payload.reward_key,
        at=datetime.now(timezone.utc),
    )

    order.points_redeemed_minor = points_result.amount_minor
    order.discount_minor = line_discount_only + previous_manual + points_result.amount_minor
    order.total_minor = remaining_after_manual - points_result.amount_minor

    await session.flush()
    response = await _build_order_read(session, order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response


@router.patch("/orders/{order_id}/send-to-pos", response_model=OrderRead)
async def send_order_to_pos(
    order_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> OrderRead:
    """Mark a Tables order as ready to bill — it now appears in POS's
    held-orders queue. One-way: once held, more items are added by finding
    it in POS (add_order_lines above), not by going back through Tables.
    """
    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending an order to POS",
    )
    if order.status == "held":
        # Response-loss retry: the state transition already succeeded.
        return await _build_order_read(session, order)
    if order.status != "open":
        raise BusinessRuleError(f"cannot send an order in status={order.status} to POS")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="sending an order to POS",
    )
    has_lines = (
        await session.execute(
            select(func.count()).select_from(OrderLine).where(OrderLine.order_id == order.id)
        )
    ).scalar_one()
    if not has_lines:
        raise BusinessRuleError("order has no items")
    order.status = "held"
    order.held_at = datetime.now(timezone.utc)
    await session.flush()
    return await _build_order_read(session, order)


class VoidOrderRequest(BaseModel):
    reason: str = Field(min_length=1, max_length=500)


@router.delete("/orders/{order_id}", status_code=status.HTTP_204_NO_CONTENT)
async def void_held_order(
    order_id: UUID,
    payload: VoidOrderRequest,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.void")),
) -> None:
    """Clear a held order that shouldn't be billed (mistake, duplicate,
    customer walked out). Only the shift's opener or a protected owner may
    do this — same accountability rule as billing it.
    """
    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="voiding an order",
    )
    if order.status == "void":
        return None
    if order.status not in ("open", "held"):
        raise BusinessRuleError(f"cannot clear an order in status={order.status}")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="clearing a held order",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="clear an order on this shift",
    )
    order.status = "void"
    order.notes = f"{order.notes + ' — ' if order.notes else ''}Voided: {payload.reason}"[:500]
    await _release_table_if_no_active_orders(session, order)
    await session.flush()


@router.get("/orders/{order_id}", response_model=OrderRead)
async def get_order(
    order_id: UUID,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
) -> OrderRead:
    order = await session.get(Order, order_id)
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="viewing an order",
    )
    return await _build_order_read(session, order)


class OrderListItem(BaseModel):
    """Slim row for the order-history list."""
    id: UUID
    invoice_no: str | None
    type: str
    status: str
    table_id: UUID | None = None
    source_label: str | None = None
    total_minor: int
    items_count: int
    customer_name: str | None
    created_at: datetime
    held_at: datetime | None = None


@router.get("/orders", response_model=list[OrderListItem])
async def list_orders(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    status_filter: list[str] | None = Query(default=None, alias="status"),
    table_id: UUID | None = None,
    limit: int = 200,
) -> list[OrderListItem]:
    """List orders, newest first.

    Defaults to today if no date filter given. When `status` is passed
    (e.g. the POS held-orders queue), the date window is skipped entirely —
    a held order shouldn't vanish from the queue just because it crossed
    local midnight.
    """
    if tenant.branch_id is None:
        raise BusinessRuleError(
            "This account has no branch assigned. Assign one before viewing orders."
        )
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required before viewing orders.")
    stmt = select(Order).where(
        Order.company_id == tenant.company_id,
        Order.branch_id == tenant.branch_id,
        Order.terminal_id == tenant.terminal_id,
    )
    if status_filter:
        stmt = stmt.where(Order.status.in_(status_filter))
    else:
        timezone_name = await company_timezone(session, tenant.company_id)
        today = local_today(timezone_name)
        f_d = from_date or today
        t_d = to_date or today
        f_dt, t_dt = local_date_bounds_utc(f_d, t_d, timezone_name)
        stmt = stmt.where(Order.created_at >= f_dt, Order.created_at < t_dt)
    if table_id is not None:
        stmt = stmt.where(Order.table_id == table_id)
    if status_filter and set(status_filter) == {"held"}:
        stmt = stmt.order_by(
            func.coalesce(Order.held_at, Order.created_at),
            Order.created_at,
        )
    else:
        stmt = stmt.order_by(Order.created_at.desc())
    stmt = stmt.limit(min(limit, 500))

    rows = (await session.execute(stmt)).scalars().all()
    if not rows:
        return []

    order_ids = [o.id for o in rows]
    counts_by_order = dict(
        (
            await session.execute(
                select(OrderLine.order_id, func.count())
                .where(OrderLine.order_id.in_(order_ids))
                .group_by(OrderLine.order_id)
            )
        ).all()
    )
    table_ids = [o.table_id for o in rows if o.table_id is not None]
    codes_by_table = dict(
        (
            await session.execute(select(Table.id, Table.code).where(Table.id.in_(table_ids)))
        ).all()
    ) if table_ids else {}
    station_by_order = dict(
        (
            await session.execute(
                select(GamingSession.order_id, Station.name)
                .join(Station, Station.id == GamingSession.station_id)
                .where(GamingSession.order_id.in_(order_ids))
            )
        ).all()
    )

    out: list[OrderListItem] = []
    for o in rows:
        if o.table_id is not None and o.table_id in codes_by_table:
            label = f"Table {codes_by_table[o.table_id]}"
        else:
            label = station_by_order.get(o.id)
        out.append(OrderListItem(
            id=o.id,
            invoice_no=o.invoice_no,
            type=o.type,
            status=o.status,
            table_id=o.table_id,
            source_label=label,
            total_minor=o.total_minor,
            items_count=int(counts_by_order.get(o.id, 0)),
            customer_name=o.customer_name,
            created_at=o.created_at,
            held_at=o.held_at,
        ))
    return out


class ShiftRead(BaseModel):
    id: UUID
    branch_id: UUID
    terminal_id: UUID | None = None
    status: str
    opened_at: datetime
    closed_at: datetime | None
    opening_float_minor: int
    expected_minor: int | None
    counted_minor: int | None
    variance_minor: int | None
    # Sum of Payment.amount_minor across ALL methods for this shift — unlike
    # expected_minor (cash-drawer float, cash payments only, used for till
    # reconciliation), this is the actual total sold through the POS. Naturally
    # resets to 0 for the next shift since it's scoped by shift_id, not a
    # running total.
    pos_sales_minor: int
    # Kept as an explicit total for clients that display a shift summary.
    # Off-POS collections are independent Finance records and never belong to
    # a till/terminal shift, so this is intentionally equal to POS sales.
    total_sales_minor: int
    opened_by: UUID
    opened_by_name: str | None = None
    opened_by_email: str | None = None


@router.get("/shifts", response_model=list[ShiftRead])
async def list_shifts(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    only_open: bool = False,
    limit: int = 50,
) -> list[ShiftRead]:
    sales_subq = (
        select(func.coalesce(func.sum(Payment.amount_minor), 0))
        .where(Payment.shift_id == Shift.id)
        .correlate(Shift)
        .scalar_subquery()
    )
    stmt = (
        select(Shift, User.name, User.email, sales_subq.label("total_sales"))
        .outerjoin(User, User.id == Shift.opened_by)
        .where(Shift.company_id == tenant.company_id)
        .order_by(Shift.opened_at.desc())
        .limit(min(limit, 200))
    )
    if tenant.branch_id is not None:
        stmt = stmt.where(Shift.branch_id == tenant.branch_id)
    if tenant.terminal_id is not None:
        stmt = stmt.where(Shift.terminal_id == tenant.terminal_id)
    if only_open:
        stmt = stmt.where(Shift.status == "open")
    rows = (await session.execute(stmt)).all()
    result = []
    for s, opener_name, opener_email, total_sales in rows:
        pos_sales = int(total_sales or 0)
        result.append(
            ShiftRead(
                id=s.id, branch_id=s.branch_id, terminal_id=s.terminal_id, status=s.status,
                opened_at=s.opened_at, closed_at=s.closed_at,
                opening_float_minor=int(s.opening_float_minor or 0),
                expected_minor=int(s.expected_minor) if s.expected_minor is not None else None,
                counted_minor=int(s.counted_minor) if s.counted_minor is not None else None,
                variance_minor=int(s.variance_minor) if s.variance_minor is not None else None,
                pos_sales_minor=pos_sales,
                total_sales_minor=pos_sales,
                opened_by=s.opened_by,
                opened_by_name=opener_name,
                opened_by_email=opener_email,
            )
        )
    return result


def _zero_total_finalization_response(order: Order) -> dict:
    return {
        "order_id": str(order.id),
        "amount_minor": 0,
        "order_status": order.status,
        "invoice_no": order.invoice_no,
        "fiscal_year": order.fiscal_year,
        "invoice_issued_at": order.invoice_issued_at.isoformat()
        if order.invoice_issued_at
        else None,
    }


@router.post("/orders/{order_id}/finalize-zero", status_code=status.HTTP_200_OK)
async def finalize_zero_total_order(
    order_id: UUID,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> dict:
    """Settle an exact-zero bill without inventing a zero-value payment.

    This is primarily for a PS5/Shisha bill fully covered by a reserved member
    allowance. It remains a high-trust money action: only the shift opener or a
    protected owner may finalize it, and an Idempotency-Key is mandatory.
    """
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
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

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="finalizing a zero-total order",
    )
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    if order.status == "paid":
        # A new idempotency key is still a harmless read of the already-issued
        # zero invoice. The original key is normally replayed above.
        shift = require_operational_shift_scope(
            shift,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="reviewing a finalized zero-total order",
        )
    else:
        shift = require_open_operational_shift(
            shift,
            company_id=tenant.company_id,
            branch_id=tenant.branch_id,
            terminal_id=tenant.terminal_id,
            operation="finalizing a zero-total order",
        )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="finalize a zero-total order on this shift",
    )
    if order.branch_id != shift.branch_id or order.terminal_id != shift.terminal_id:
        raise BusinessRuleError("Order branch or terminal does not match its shift.")

    paid_total = await _paid_total(session, order.id)
    if int(order.total_minor or 0) != 0 or paid_total != 0:
        raise BusinessRuleError(
            "Only an unpaid order with an exact zero balance can use zero-total finalization."
        )
    if order.status == "paid":
        if not order.invoice_no or not order.invoice_issued_at:
            raise BusinessRuleError(
                "Paid zero-total order is missing its invoice; "
                "ask a protected owner to reconcile it."
            )
    else:
        if order.status not in ("open", "held"):
            raise BusinessRuleError(
                f"cannot finalize an order in status={order.status}"
            )
        await _finalize_order(
            session,
            order=order,
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            at=datetime.now(timezone.utc),
        )

    response = _zero_total_finalization_response(order)
    await store_response(
        session,
        key=idempotency_key,
        status_code=status.HTTP_200_OK,
        body=response,
    )
    return response


@router.post("/orders/{order_id}/payments", status_code=status.HTTP_201_CREATED)
async def record_payment(
    order_id: UUID,
    payload: PaymentCreate,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> dict:
    if tenant.terminal_id is None:
        raise BusinessRuleError("X-Terminal-Id header required for POS writes")
    if tenant.branch_id is None:
        raise BusinessRuleError("token has no branch_id")
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

    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="recording a payment",
    )
    if order.status in {"paid", "void", "refunded"}:
        raise BusinessRuleError(f"cannot pay an order in status={order.status}")
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="recording a payment",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="bill an order on this shift",
    )
    if order.branch_id != shift.branch_id or order.terminal_id != shift.terminal_id:
        raise BusinessRuleError("Order branch or terminal does not match its shift.")

    already_paid = await _paid_total(session, order_id)
    due_minor = max(0, int(order.total_minor or 0) - already_paid)
    _validate_confirmed_payment_balance(
        payload,
        order_total_minor=int(order.total_minor or 0),
        due_minor=due_minor,
    )
    if due_minor <= 0:
        raise BusinessRuleError(
            "Order balance is zero. Finalize it with the zero-total settlement action; "
            "do not record a payment."
        )
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
    finalized = already_paid + payload.amount_minor >= order.total_minor
    if finalized:
        await _finalize_order(
            session,
            order=order,
            company_id=tenant.company_id,
            actor_user_id=tenant.user_id,
            at=now,
        )
    response = {
        "id": str(payment.id),
        "amount_minor": payment.amount_minor,
        "order_status": order.status,
        "invoice_no": order.invoice_no,
        "fiscal_year": order.fiscal_year,
        "invoice_issued_at": order.invoice_issued_at.isoformat()
        if order.invoice_issued_at
        else None,
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

    if payload.mode == "credit_note":
        raise BusinessRuleError(
            "store-credit refunds are unavailable until a customer credit ledger is enabled"
        )
    order = (
        await session.execute(
            select(Order).where(Order.id == order_id).with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="issuing a refund",
    )
    if order.status not in {"paid", "refunded"}:
        raise BusinessRuleError("only paid orders can be refunded")
    original_shift = (
        await session.execute(
            select(Shift).where(Shift.id == order.shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    original_shift = require_operational_shift_scope(
        original_shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="issuing a refund",
        resource_branch_id=order.branch_id,
        resource_name="order",
    )
    require_shift_opener(
        original_shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="refund an order from this shift",
    )
    payment_rows = (
        await session.execute(
            select(Payment).where(Payment.order_id == order_id).order_by(Payment.paid_at)
        )
    ).scalars().all()
    paid_total = sum(int(row.amount_minor) for row in payment_rows)
    refunded_total = await _refunded_total(session, order_id)
    refundable_minor = paid_total - refunded_total
    if refundable_minor <= 0:
        raise BusinessRuleError("order has no refundable payment balance")
    if payload.amount_minor > refundable_minor:
        raise BusinessRuleError("refund exceeds paid balance")
    settlement_method = "cash"
    if payload.mode == "original":
        methods = {row.method for row in payment_rows}
        if len(methods) != 1:
            raise BusinessRuleError(
                "mixed-payment orders require an explicit cash refund"
            )
        settlement_method = next(iter(methods))
    refund = Refund(
        id=uuid4(),
        order_id=order_id,
        approved_by=tenant.user_id,
        manager_override_user_id=payload.manager_override_user_id,
        reason_code=payload.reason_code,
        amount_minor=payload.amount_minor,
        mode=payload.mode,
        settlement_method=settlement_method,
        note=payload.note,
    )
    session.add(refund)
    if settlement_method == "cash":
        shift_stmt = select(Shift).where(
            Shift.company_id == tenant.company_id,
            Shift.branch_id == order.branch_id,
            Shift.status == "open",
        )
        if tenant.terminal_id:
            shift_stmt = shift_stmt.where(Shift.terminal_id == tenant.terminal_id)
        open_shifts = (
            await session.execute(
                shift_stmt.order_by(Shift.opened_at.desc()).with_for_update()
            )
        ).scalars().all()
        if len(open_shifts) != 1:
            raise BusinessRuleError(
                "cash refund requires exactly one open shift for this terminal"
            )
        cash_shift = open_shifts[0]
        require_shift_opener(
            cash_shift,
            user_id=tenant.user_id,
            protected_access=tenant.protected_access,
            operation="issue a cash refund on this shift",
        )
        cash_shift.expected_minor = (
            int(cash_shift.expected_minor or 0) - payload.amount_minor
        )
    if refunded_total + payload.amount_minor >= paid_total:
        order.status = "refunded"

    # Reverse the recipe-ingredient deduction that ran when the order was
    # finalized. Refunds here are order-level amounts (no per-line
    # breakdown), so we restock every recipe-linked line proportionally to
    # the share of the order's value this refund covers — a full refund puts
    # ingredients back to their pre-deduction level, a partial refund puts
    # back the matching fraction. Lines without a recipe are skipped
    # gracefully, symmetric with deduct_for_order.
    refund_order_lines = (
        await session.execute(select(OrderLine).where(OrderLine.order_id == order_id))
    ).scalars().all()
    refund_fraction = (
        payload.amount_minor / order.total_minor if order.total_minor > 0 else 0.0
    )
    await restock_for_refund(
        session,
        order_id=order.id,
        order_lines=list(refund_order_lines),
        branch_id=order.branch_id,
        created_by=tenant.user_id,
        fraction=refund_fraction,
    )

    response = {"id": str(refund.id), "settlement_method": settlement_method}
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
    # Serialize shift opening per terminal so two simultaneous clients cannot
    # create two live shifts for the same drawer.
    await session.execute(
        select(Terminal).where(Terminal.id == tenant.terminal_id).with_for_update()
    )
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
        if existing.opened_by != tenant.user_id:
            raise BusinessRuleError(
                "A shift is already open on this terminal by another staff member. "
                "Open the Shifts tab to see who is responsible for it."
            )
        if int(existing.opening_float_minor or 0) != payload.opening_float_minor:
            raise BusinessRuleError(
                "This shift is already open with a different opening float. "
                "Use the existing shift instead of changing its accountable cash start."
            )
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
    shift = (
        await session.execute(
            select(Shift).where(Shift.id == shift_id).with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_operational_shift_scope(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="closing a shift",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="close this shift",
    )
    if shift.status == "closed":
        if int(shift.counted_minor or 0) != payload.counted_minor:
            raise BusinessRuleError(
                "Shift is already closed with a different counted amount."
            )
        return {
            "id": str(shift.id),
            "status": shift.status,
            "variance_minor": shift.variance_minor,
        }
    if shift.status != "open":
        raise BusinessRuleError(f"Shift is {shift.status} and cannot be closed.")
    unfinished_orders = int(
        (
            await session.execute(
                select(func.count(Order.id)).where(
                    Order.shift_id == shift.id,
                    Order.status.in_(("open", "held")),
                )
            )
        ).scalar_one()
        or 0
    )
    if unfinished_orders:
        raise BusinessRuleError(
            f"cannot close shift with {unfinished_orders} unfinished order(s)"
        )
    running_sessions = int(
        (
            await session.execute(
                select(func.count(GamingSession.id)).where(
                    GamingSession.shift_id == shift.id,
                    GamingSession.status.in_(("active", "paused")),
                )
            )
        ).scalar_one()
        or 0
    )
    if running_sessions:
        raise BusinessRuleError(
            f"cannot close shift with {running_sessions} running gaming session(s)"
        )
    unbilled_sessions = int(
        (
            await session.execute(
                select(func.count(GamingSession.id)).where(
                    GamingSession.shift_id == shift.id,
                    GamingSession.status == "ended",
                    GamingSession.order_id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    if unbilled_sessions:
        raise BusinessRuleError(
            f"cannot close shift with {unbilled_sessions} stopped session(s) "
            "not yet sent to POS"
        )
    shift.closed_at = datetime.now(timezone.utc)
    shift.counted_minor = payload.counted_minor
    shift.variance_minor = payload.counted_minor - (shift.expected_minor or 0)
    shift.status = "closed"
    return {
        "id": str(shift.id),
        "status": shift.status,
        "variance_minor": shift.variance_minor,
    }
