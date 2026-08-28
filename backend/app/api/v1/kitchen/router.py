"""Kitchen Display System endpoints.

  GET   /kitchen/queue                    — open orders for the kitchen iPad
                                            (filterable by station, defaults to all)
  PATCH /kitchen/orders/{id}/state        — advance state: received → preparing → ready → served

The kitchen iPad polls /queue every 3 seconds. When all active kitchen
lines on an order move to 'ready', the cashier sees a notification on POS.

Authoritative state machine on OrderLine.kitchen_status:
  queued → cooking → ready → served

Order.kitchen_state remains a compatibility mirror for older clients. It
must never be used to decide whether a ticket is visible: late lines can be
queued after an earlier batch was served.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Request, status as http_status
from pydantic import BaseModel, Field
from sqlalchemy import and_, or_, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.idempotency import check_or_reserve, store_response
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import Floor, MenuItem, Order, OrderLine, Table
from app.schemas.pos import OrderModifierSnapshotRead, OrderVariantSnapshotRead

router = APIRouter()


# Map UI state → numeric stage so transitions can be validated strictly.
_STATE_STAGE = {"received": 0, "preparing": 1, "ready": 2, "served": 3}
_LINE_STAGE = {"queued": 0, "cooking": 1, "ready": 2, "served": 3}
_STATE_TO_LINE = {
    "received": "queued",
    "preparing": "cooking",
    "ready": "ready",
    "served": "served",
}
_LINE_TO_STATE = {line: state for state, line in _STATE_TO_LINE.items()}
_KITCHEN_ITEM_TYPES = {"food", "drink", "dessert"}


class KitchenLineDTO(BaseModel):
    id: UUID
    client_line_id: UUID | None = None
    menu_item_id: UUID
    name: str
    type: str  # food/drink/dessert only
    qty: float
    variant_snapshot: OrderVariantSnapshotRead | None = None
    modifiers: list[OrderModifierSnapshotRead] = Field(default_factory=list)
    notes: str | None = None
    released_at: datetime
    round_no: int


class KitchenCancellationDTO(BaseModel):
    line_id: UUID
    client_line_id: UUID | None = None
    menu_item_id: UUID
    name: str
    type: str
    qty: float
    variant_snapshot: OrderVariantSnapshotRead | None = None
    modifiers: list[OrderModifierSnapshotRead] = Field(default_factory=list)
    notes: str | None = None
    released_at: datetime
    round_no: int
    voided_at: datetime
    voided_by: UUID | None
    reason: str


class KitchenOrderDTO(BaseModel):
    id: UUID
    invoice_no: str | None
    type: str  # dine_in / takeaway / delivery
    table_code: str | None
    customer_name: str | None
    opened_at: datetime
    kitchen_state: str  # received / preparing / ready / served
    minutes_waiting: int
    lines: list[KitchenLineDTO]
    pending_cancellations: list[KitchenCancellationDTO] = Field(default_factory=list)


class KitchenCancellationAckRead(BaseModel):
    order_id: UUID
    line_id: UUID
    acknowledged_at: datetime
    acknowledged_by: UUID


class StateUpdate(BaseModel):
    state: Literal["received", "preparing", "ready", "served"]


def _current_branch_id(tenant: TenantContext) -> UUID:
    if tenant.branch_id is None:
        raise BusinessRuleError("select a branch or terminal before using Kitchen")
    return tenant.branch_id


def _validate_state_transition(current: str, requested: str) -> None:
    if current not in _STATE_STAGE:
        raise BusinessRuleError(f"order has invalid kitchen state: {current}")
    current_stage = _STATE_STAGE[current]
    requested_stage = _STATE_STAGE[requested]
    if requested_stage < current_stage:
        raise BusinessRuleError(f"cannot move from {current} back to {requested}")
    if requested_stage > current_stage + 1:
        raise BusinessRuleError(f"cannot skip kitchen states from {current} to {requested}")


def _line_status(line: OrderLine) -> str:
    # Legacy rows can be null even though new writes are explicit. Treat null
    # as queued so old work remains visible instead of silently disappearing.
    status = getattr(line, "kitchen_status", None) or "queued"
    if status not in _LINE_STAGE:
        raise BusinessRuleError(f"order line has invalid kitchen status: {status}")
    return status


def _state_from_lines(lines: list[OrderLine]) -> str:
    """Return the least-advanced state across the supplied kitchen lines."""
    return _state_from_statuses([_line_status(line) for line in lines])


def _state_from_statuses(statuses: list[str]) -> str:
    """Return the least-advanced UI state across normalized line states."""
    for status in statuses:
        if status not in _LINE_STAGE:
            raise BusinessRuleError(f"order line has invalid kitchen status: {status}")
    if not statuses:
        return "served"
    least_advanced = min(statuses, key=lambda status: _LINE_STAGE[status])
    return _LINE_TO_STATE[least_advanced]


def _active_lines(lines: list[OrderLine]) -> list[OrderLine]:
    return [line for line in lines if _line_status(line) != "served"]


def _legacy_batch_status(order: Order, lines: list[OrderLine]) -> str | None:
    """Resolve pre-line-state KDS rows without a destructive data migration.

    Before this release, the KDS advanced only Order.kitchen_state while every
    OrderLine retained its DB default of queued. An all-queued batch therefore
    uses the old order mirror as its compatibility seed. Historical paid rows
    with no mirror are complete; unfinished open/held rows stay queued.
    """
    if not lines or any(_line_status(line) != "queued" for line in lines):
        return None
    legacy_state = getattr(order, "kitchen_state", None)
    if legacy_state in _STATE_TO_LINE:
        return _STATE_TO_LINE[legacy_state]
    if getattr(order, "status", "open") not in {"open", "held"}:
        return "served"
    return None


def _effective_line_statuses(order: Order, lines: list[OrderLine]) -> list[str]:
    legacy_status = _legacy_batch_status(order, lines)
    if legacy_status is not None:
        return [legacy_status] * len(lines)
    return [_line_status(line) for line in lines]


def _wait_started_at(lines: list[OrderLine], fallback: datetime) -> datetime:
    released = [
        released_at
        for line in lines
        if (
            released_at := getattr(line, "kitchen_released_at", None)
            or getattr(line, "created_at", None)
        )
        is not None
    ]
    return min(released) if released else fallback


def _released_at(line: OrderLine, fallback: datetime) -> datetime:
    # Missing attributes only occur in old unit fixtures. Migration 0037
    # materializes every ticket the legacy KDS had already received.
    return (
        getattr(line, "kitchen_released_at", None)
        or getattr(line, "created_at", None)
        or fallback
    )


def _round_no(line: OrderLine) -> int:
    return max(1, int(getattr(line, "kitchen_round_no", None) or 1))


def _set_line_status(line: OrderLine, status: str, now: datetime) -> None:
    """Keep the line state and its completion event timestamp inseparable."""
    line.kitchen_status = status
    if status == "served":
        line.kitchen_served_at = (
            getattr(line, "kitchen_served_at", None) or now
        )
    else:
        # This is normally already NULL because the state machine is
        # monotonic. Clearing it here also repairs pre-0042 in-memory fixtures
        # before the database constraint sees them.
        line.kitchen_served_at = None


def _require_idempotency(request: Request) -> tuple[str, str]:
    key = getattr(request.state, "idempotency_key", None)
    request_hash = getattr(request.state, "idempotency_request_hash", None)
    if not key or not request_hash:
        raise BusinessRuleError(
            "Idempotency-Key header required to acknowledge a kitchen cancellation."
        )
    return str(key), str(request_hash)


def _next_ready_at(
    current: str,
    requested: str,
    ready_at: datetime | None,
    now: datetime,
) -> datetime | None:
    """Keep the first ready time, but clear stale values before readiness."""
    if requested in {"received", "preparing"}:
        return None
    if requested == "ready":
        return ready_at if current == "ready" and ready_at is not None else now
    return ready_at


@router.get("/queue", response_model=list[KitchenOrderDTO])
async def kitchen_queue(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("kitchen.read")),
    include_served: bool = False,
) -> list[KitchenOrderDTO]:
    """Active kitchen work, or today's history when served lines are requested.

    Skips fully-served lines and orders by default (so the iPad isn't cluttered).
    A late line on an older ticket is still visible because line state, not the
    legacy order state, defines the active batch.
    Skips orders that contain no food, drink, or dessert lines so gaming,
    hookah, and event-only orders do not clutter the kitchen iPad.
    """
    branch_id = _current_branch_id(tenant)
    active_kitchen_work = (
        select(OrderLine.id)
        .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
        .where(
            OrderLine.order_id == Order.id,
            OrderLine.kitchen_released_at.is_not(None),
            OrderLine.voided_at.is_(None),
            or_(
                OrderLine.kitchen_status.is_(None),
                OrderLine.kitchen_status != "served",
            ),
            MenuItem.company_id == tenant.company_id,
            MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
        )
        .exists()
    )
    pending_cancellation = (
        select(OrderLine.id)
        .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
        .where(
            OrderLine.order_id == Order.id,
            OrderLine.kitchen_released_at.is_not(None),
            OrderLine.voided_at.is_not(None),
            OrderLine.kitchen_void_acknowledged_at.is_(None),
            MenuItem.company_id == tenant.company_id,
            MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
        )
        .exists()
    )
    stmt = (
        select(Order)
        .where(
            Order.company_id == tenant.company_id,
            Order.branch_id == branch_id,
        )
        .order_by(Order.opened_at)
    )
    history_bounds: tuple[datetime, datetime] | None = None
    if include_served:
        timezone_name = await company_timezone(session, tenant.company_id)
        today = local_today(timezone_name)
        today_start, tomorrow_start = local_date_bounds_utc(
            today,
            today,
            timezone_name,
        )
        history_bounds = (today_start, tomorrow_start)
        served_today_kitchen_line = (
            select(OrderLine.id)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.order_id == Order.id,
                OrderLine.kitchen_released_at.is_not(None),
                OrderLine.voided_at.is_(None),
                OrderLine.kitchen_status == "served",
                OrderLine.kitchen_served_at >= today_start,
                OrderLine.kitchen_served_at < tomorrow_start,
                MenuItem.company_id == tenant.company_id,
                MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
            )
            .exists()
        )
        stmt = stmt.where(
            served_today_kitchen_line,
        )
    else:
        # Never age live work or an unacknowledged cancellation out at midnight.
        # A void order is included only while kitchen still owes the ack.
        stmt = stmt.where(
            or_(
                and_(
                    Order.status.in_(("paid", "open", "held")),
                    active_kitchen_work,
                ),
                pending_cancellation,
            ),
        )
    orders = (await session.execute(stmt)).scalars().all()
    if not orders:
        return []

    order_ids = [o.id for o in orders]
    line_stmt = (
        select(OrderLine, MenuItem)
        .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
        .where(
            OrderLine.order_id.in_(order_ids),
            OrderLine.kitchen_released_at.is_not(None),
            MenuItem.company_id == tenant.company_id,
            MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
        )
        .order_by(OrderLine.kitchen_released_at, OrderLine.created_at, OrderLine.id)
    )
    if include_served:
        assert history_bounds is not None
        today_start, tomorrow_start = history_bounds
        line_stmt = line_stmt.where(
            OrderLine.voided_at.is_(None),
            OrderLine.kitchen_status == "served",
            OrderLine.kitchen_served_at >= today_start,
            OrderLine.kitchen_served_at < tomorrow_start,
        )
    else:
        line_stmt = line_stmt.where(
            or_(
                and_(
                    OrderLine.voided_at.is_(None),
                    or_(
                        OrderLine.kitchen_status.is_(None),
                        OrderLine.kitchen_status != "served",
                    ),
                ),
                and_(
                    OrderLine.voided_at.is_not(None),
                    OrderLine.kitchen_void_acknowledged_at.is_(None),
                ),
            )
        )
    lines = (await session.execute(line_stmt)).all()
    rows_by_order: dict[UUID, list[tuple[OrderLine, MenuItem]]] = {}
    for ol, mi in lines:
        rows_by_order.setdefault(ol.order_id, []).append((ol, mi))

    table_ids = [o.table_id for o in orders if o.table_id]
    codes_by_table = (
        dict(
            (
                await session.execute(
                    select(Table.id, Table.code)
                    .join(Floor, Floor.id == Table.floor_id)
                    .where(
                        Table.id.in_(table_ids),
                        Floor.branch_id == branch_id,
                    )
                )
            ).all()
        )
        if table_ids
        else {}
    )

    now = datetime.now(UTC)
    out: list[KitchenOrderDTO] = []
    for o in orders:
        all_rows = rows_by_order.get(o.id, [])
        active_source_rows = [
            (line, item)
            for line, item in all_rows
            if getattr(line, "voided_at", None) is None
        ]
        pending_cancel_rows = [
            (line, item)
            for line, item in all_rows
            if getattr(line, "voided_at", None) is not None
            and getattr(line, "kitchen_void_acknowledged_at", None) is None
        ]
        effective_statuses = _effective_line_statuses(
            o, [line for line, _item in active_source_rows]
        )
        active_rows = [
            (line, item, effective_status)
            for (line, item), effective_status in zip(
                active_source_rows,
                effective_statuses,
                strict=True,
            )
            if effective_status != "served"
        ]
        visible_rows = (
            [
                (line, item, effective_status)
                for (line, item), effective_status in zip(
                    active_source_rows,
                    effective_statuses,
                    strict=True,
                )
            ]
            if include_served
            else active_rows
        )
        if not visible_rows and not pending_cancel_rows:
            continue
        state = _state_from_statuses(
            [effective_status for _line, _item, effective_status in visible_rows]
        )
        wait_started_at = _wait_started_at(
            [line for line, _item, _status in active_rows]
            or [line for line, _item, _status in visible_rows]
            or [line for line, _item in pending_cancel_rows],
            o.opened_at,
        )
        minutes_waiting = max(0, int((now - wait_started_at).total_seconds() // 60))
        out.append(
            KitchenOrderDTO(
                id=o.id,
                invoice_no=o.invoice_no,
                type=o.type,
                table_code=codes_by_table.get(o.table_id) if o.table_id else None,
                customer_name=o.customer_name,
                opened_at=o.opened_at,
                kitchen_state=state,
                minutes_waiting=minutes_waiting,
                lines=[
                    KitchenLineDTO(
                        id=line.id,
                        client_line_id=getattr(line, "client_line_id", None),
                        menu_item_id=item.id,
                        name=item.name,
                        type=item.type,
                        qty=float(line.qty),
                        variant_snapshot=getattr(line, "variant_snapshot", None),
                        modifiers=getattr(line, "modifiers", None) or [],
                        notes=line.note,
                        released_at=_released_at(line, o.opened_at),
                        round_no=_round_no(line),
                    )
                    for line, item, _status in visible_rows
                ],
                pending_cancellations=[
                    KitchenCancellationDTO(
                        line_id=line.id,
                        client_line_id=getattr(line, "client_line_id", None),
                        menu_item_id=item.id,
                        name=item.name,
                        type=item.type,
                        qty=float(line.qty),
                        variant_snapshot=getattr(line, "variant_snapshot", None),
                        modifiers=getattr(line, "modifiers", None) or [],
                        notes=line.note,
                        released_at=_released_at(line, o.opened_at),
                        round_no=_round_no(line),
                        voided_at=line.voided_at,
                        voided_by=line.voided_by,
                        reason=(getattr(line, "void_reason", None) or "Legacy cancellation"),
                    )
                    for line, item in pending_cancel_rows
                    if line.voided_at is not None
                ],
            )
        )
    return out


@router.patch("/orders/{order_id}/state", response_model=KitchenOrderDTO)
async def set_kitchen_state(
    order_id: UUID,
    payload: StateUpdate,
    session: SessionDep,
    tenant: TenantContext = Depends(requires("kitchen.write")),
) -> KitchenOrderDTO:
    branch_id = _current_branch_id(tenant)
    order = (
        await session.execute(
            select(Order)
            .where(
                Order.id == order_id,
                Order.company_id == tenant.company_id,
                Order.branch_id == branch_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if not order:
        raise NotFoundError("order not found")

    # The order lock serializes this transition with add_order_lines(), which
    # takes the same lock before inserting. We intentionally do not use
    # SKIP LOCKED: dropping a transition would let a concurrent late line hide.
    rows = (
        await session.execute(
            select(OrderLine, MenuItem)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.order_id == order_id,
                OrderLine.voided_at.is_(None),
                OrderLine.kitchen_released_at.is_not(None),
                MenuItem.company_id == tenant.company_id,
                MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
            )
            .order_by(OrderLine.created_at, OrderLine.id)
        )
    ).all()
    if not rows:
        raise BusinessRuleError("order has no active or historical kitchen items")

    kitchen_lines = [line for line, _item in rows]
    now = datetime.now(UTC)
    legacy_status = _legacy_batch_status(order, kitchen_lines)
    if legacy_status is not None:
        # We hold the order lock, so this safely converts the old batch before
        # any concurrent late append can acquire the same lock.
        for line in kitchen_lines:
            _set_line_status(line, legacy_status, now)
    active_lines = _active_lines(kitchen_lines)
    current = _state_from_lines(active_lines)
    _validate_state_transition(current, payload.state)

    if payload.state != current:
        target_status = _STATE_TO_LINE[payload.state]
        current_status = _STATE_TO_LINE[current]
        for line in active_lines:
            if _line_status(line) == current_status:
                _set_line_status(line, target_status, now)

    resulting_state = _state_from_lines(_active_lines(kitchen_lines))
    order.kitchen_state = resulting_state
    order.kitchen_ready_at = _next_ready_at(
        current,
        resulting_state,
        order.kitchen_ready_at,
        now,
    )
    await session.flush()

    # Re-emit DTO.
    table_code = None
    if order.table_id is not None:
        table_code = (
            await session.execute(
                select(Table.code)
                .join(Floor, Floor.id == Table.floor_id)
                .where(
                    Table.id == order.table_id,
                    Floor.branch_id == branch_id,
                )
            )
        ).scalar_one_or_none()
    return KitchenOrderDTO(
        id=order.id,
        invoice_no=order.invoice_no,
        type=order.type,
        table_code=table_code,
        customer_name=order.customer_name,
        opened_at=order.opened_at,
        kitchen_state=resulting_state,
        minutes_waiting=max(
            0,
            int(
                (
                    now
                    - _wait_started_at(
                        _active_lines(kitchen_lines) or kitchen_lines,
                        order.opened_at,
                    )
                ).total_seconds()
                // 60
            ),
        ),
        lines=[
            KitchenLineDTO(
                id=ol.id,
                client_line_id=getattr(ol, "client_line_id", None),
                menu_item_id=mi.id,
                name=mi.name,
                type=mi.type,
                qty=float(ol.qty),
                variant_snapshot=getattr(ol, "variant_snapshot", None),
                modifiers=getattr(ol, "modifiers", None) or [],
                notes=ol.note,
                released_at=_released_at(ol, order.opened_at),
                round_no=_round_no(ol),
            )
            for ol, mi in rows
        ],
    )


@router.post(
    "/orders/{order_id}/cancellations/{line_id}/acknowledge",
    response_model=KitchenCancellationAckRead,
    status_code=http_status.HTTP_200_OK,
)
async def acknowledge_kitchen_cancellation(
    order_id: UUID,
    line_id: UUID,
    session: SessionDep,
    request: Request,
    tenant: TenantContext = Depends(requires("kitchen.write")),
) -> KitchenCancellationAckRead:
    """Persist kitchen's acknowledgement of one released-line cancellation."""
    branch_id = _current_branch_id(tenant)
    idempotency_key, request_hash = _require_idempotency(request)
    existing_response = await check_or_reserve(
        session,
        key=idempotency_key,
        request_hash=request_hash,
        user_id=tenant.user_id,
        terminal_id=tenant.terminal_id,
    )
    if existing_response:
        return KitchenCancellationAckRead.model_validate(existing_response["body"])

    order = (
        await session.execute(
            select(Order)
            .where(
                Order.id == order_id,
                Order.company_id == tenant.company_id,
                Order.branch_id == branch_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if order is None:
        raise NotFoundError("Kitchen order not found for this branch.")
    line = (
        await session.execute(
            select(OrderLine)
            .join(MenuItem, MenuItem.id == OrderLine.menu_item_id)
            .where(
                OrderLine.id == line_id,
                OrderLine.order_id == order.id,
                MenuItem.company_id == tenant.company_id,
                MenuItem.type.in_(_KITCHEN_ITEM_TYPES),
            )
            .with_for_update(of=OrderLine)
        )
    ).scalar_one_or_none()
    if line is None:
        raise NotFoundError("Kitchen cancellation not found for this order.")
    if line.voided_at is None or line.kitchen_released_at is None:
        raise BusinessRuleError(
            "This item is not a released kitchen cancellation. Refresh the queue."
        )
    if line.kitchen_void_acknowledged_at is None:
        line.kitchen_void_acknowledged_at = datetime.now(UTC)
        line.kitchen_void_acknowledged_by = tenant.user_id
        await session.flush()

    assert line.kitchen_void_acknowledged_at is not None
    assert line.kitchen_void_acknowledged_by is not None
    response = KitchenCancellationAckRead(
        order_id=order.id,
        line_id=line.id,
        acknowledged_at=line.kitchen_void_acknowledged_at,
        acknowledged_by=line.kitchen_void_acknowledged_by,
    )
    await store_response(
        session,
        key=idempotency_key,
        status_code=http_status.HTTP_200_OK,
        body=response.model_dump(mode="json"),
    )
    return response
