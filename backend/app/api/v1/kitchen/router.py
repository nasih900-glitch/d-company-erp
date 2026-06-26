"""Kitchen Display System endpoints.

  GET   /kitchen/queue                    — open orders for the kitchen iPad
                                            (filterable by station, defaults to all)
  PATCH /kitchen/orders/{id}/state        — advance state: received → preparing → ready → served

The kitchen iPad polls /queue every 3 seconds. When an order moves to
'ready', the cashier sees a notification on POS.

State machine on Order.kitchen_state:
  null/received → preparing → ready → served
"""

from __future__ import annotations

from datetime import datetime, time, timezone
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.models import MenuItem, Order, OrderLine

router = APIRouter()


# Map UI state → numeric stage so we can validate transitions only go forward
_STATE_STAGE = {"received": 0, "preparing": 1, "ready": 2, "served": 3}
_KITCHEN_ITEM_TYPES = {"food", "drink", "dessert"}


class KitchenLineDTO(BaseModel):
    menu_item_id: UUID
    name: str
    type: str       # food/drink/dessert/hookah — kitchen handles food/drink
    qty: float
    notes: str | None = None


class KitchenOrderDTO(BaseModel):
    id: UUID
    invoice_no: str | None
    type: str        # dine_in / takeaway / delivery
    table_code: str | None
    customer_name: str | None
    opened_at: datetime
    kitchen_state: str  # received / preparing / ready / served
    minutes_waiting: int
    lines: list[KitchenLineDTO]


class StateUpdate(BaseModel):
    state: Literal["received", "preparing", "ready", "served"]


@router.get("/queue", response_model=list[KitchenOrderDTO])
async def kitchen_queue(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.read")),
    include_served: bool = False,
) -> list[KitchenOrderDTO]:
    """Today's orders the kitchen should know about.

    Skips fully-served orders by default (so the iPad isn't cluttered)
    Skips orders that contain no food, drink, or dessert lines so gaming,
    hookah, and event-only orders do not clutter the kitchen iPad.
    """
    today_start = datetime.combine(
        datetime.now(timezone.utc).date(),
        time.min,
        tzinfo=timezone.utc,
    )

    stmt = (
        select(Order)
        .where(
            Order.company_id == tenant.company_id,
            Order.opened_at >= today_start,
            Order.status.in_(("paid", "open")),
        )
        .order_by(Order.opened_at)
    )
    if not include_served:
        # Exclude orders whose kitchen_state is already 'served'.
        stmt = stmt.where(
            (Order.kitchen_state.is_(None))
            | (Order.kitchen_state != "served")
        )
    orders = (await session.execute(stmt)).scalars().all()
    if not orders:
        return []

    order_ids = [o.id for o in orders]
    lines = (
        await session.execute(
            select(OrderLine, MenuItem)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.order_id.in_(order_ids),
                MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
            )
        )
    ).all()
    lines_by_order: dict[UUID, list[KitchenLineDTO]] = {}
    for ol, mi in lines:
        lines_by_order.setdefault(ol.order_id, []).append(
            KitchenLineDTO(
                menu_item_id=mi.id, name=mi.name, type=mi.type,
                qty=float(ol.qty),
            )
        )

    now = datetime.now(timezone.utc)
    out: list[KitchenOrderDTO] = []
    for o in orders:
        kitchen_lines = lines_by_order.get(o.id, [])
        if not kitchen_lines:
            continue
        state = o.kitchen_state or "received"
        minutes_waiting = max(0, int((now - o.opened_at).total_seconds() // 60))
        out.append(KitchenOrderDTO(
            id=o.id,
            invoice_no=o.invoice_no,
            type=o.type,
            table_code=None,  # filled in next iteration when we join Table
            customer_name=o.customer_name,
            opened_at=o.opened_at,
            kitchen_state=state,
            minutes_waiting=minutes_waiting,
            lines=kitchen_lines,
        ))
    return out


@router.patch("/orders/{order_id}/state", response_model=KitchenOrderDTO)
async def set_kitchen_state(
    order_id: UUID,
    payload: StateUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("pos.write")),
) -> KitchenOrderDTO:
    order = await session.get(Order, order_id)
    if not order or order.company_id != tenant.company_id:
        raise NotFoundError("order not found")

    current = order.kitchen_state or "received"
    if _STATE_STAGE[payload.state] < _STATE_STAGE[current]:
        raise BusinessRuleError(f"cannot move from {current} back to {payload.state}")

    order.kitchen_state = payload.state
    if payload.state == "ready":
        order.kitchen_ready_at = datetime.now(timezone.utc)
    await session.flush()

    # Re-emit DTO
    lines = (
        await session.execute(
            select(OrderLine, MenuItem)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.order_id == order_id,
                MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
            )
        )
    ).all()
    now = datetime.now(timezone.utc)
    return KitchenOrderDTO(
        id=order.id, invoice_no=order.invoice_no, type=order.type,
        table_code=None, customer_name=order.customer_name,
        opened_at=order.opened_at, kitchen_state=order.kitchen_state,
        minutes_waiting=max(0, int((now - order.opened_at).total_seconds() // 60)),
        lines=[
            KitchenLineDTO(
                menu_item_id=mi.id, name=mi.name, type=mi.type,
                qty=float(ol.qty),
            )
            for ol, mi in lines
        ],
    )
