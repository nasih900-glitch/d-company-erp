"""Insights endpoints — inventory valuation, recipe margin, top items,
growth comparisons, hour heatmap, losses.

Everything below is read-only and computed on demand. Nothing is cached —
the small data volume of a single café doesn't need it; we can add a
materialized view layer later if Postgres struggles.
"""

from __future__ import annotations

from datetime import date, timedelta
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import func, select

from app.core.db import SessionDep
from app.core.errors import BusinessRuleError
from app.core.permissions import requires
from app.core.tenant import TenantContext
from app.core.timezone import company_timezone, local_date_bounds_utc, local_today
from app.models import (
    Batch, Branch, Ingredient, ManualCollection, MenuItem, Order, OrderLine,
    Recipe, RecipeLine, Refund, StockMovement,
)

router = APIRouter()


def _validated_date_range(
    start: date,
    end: date,
    timezone_name: str,
):
    if end < start:
        raise BusinessRuleError("to_date must be on or after from_date")
    return local_date_bounds_utc(start, end, timezone_name)


# ---------------------------------------------------------------- DTOs
class ValuationLineDTO(BaseModel):
    ingredient_id: UUID
    sku: str
    name: str
    base_unit: str
    current_qty: float
    avg_cost_minor: int
    valuation_minor: int
    reorder_threshold: float
    is_low_stock: bool


class InventoryValuationDTO(BaseModel):
    as_of: date
    lines: list[ValuationLineDTO]
    total_valuation_minor: int
    low_stock_count: int


class RecipeMarginDTO(BaseModel):
    menu_item_id: UUID
    sku: str
    name: str
    type: str
    sale_price_minor: int
    cost_minor: int
    margin_minor: int
    margin_pct: float


class TopItemDTO(BaseModel):
    menu_item_id: UUID
    name: str
    type: str
    qty_sold: float
    revenue_minor: int


class GrowthPeriodDTO(BaseModel):
    label: str
    revenue_minor: int
    refunds_minor: int
    manual_collections_minor: int
    orders_count: int
    avg_ticket_minor: int


class GrowthDTO(BaseModel):
    current: GrowthPeriodDTO
    previous: GrowthPeriodDTO
    revenue_delta_pct: float
    orders_delta_pct: float


class HeatmapCellDTO(BaseModel):
    day_of_week: int  # 0=Monday
    hour: int         # 0-23
    revenue_minor: int
    orders_count: int


class LossLineDTO(BaseModel):
    ingredient_id: UUID
    sku: str
    name: str
    qty_lost: float
    cost_lost_minor: int
    movement_count: int


class LossesDTO(BaseModel):
    from_date: date
    to_date: date
    waste_minor: int
    damage_minor: int
    negative_stock_minor: int
    total_loss_minor: int
    lines: list[LossLineDTO]


# ---------------------------------------------------------------- INVENTORY
@router.get("/inventory/valuation", response_model=InventoryValuationDTO)
async def inventory_valuation(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.read")),
) -> InventoryValuationDTO:
    rows = (
        await session.execute(
            select(Ingredient).where(
                Ingredient.company_id == tenant.company_id,
                Ingredient.deleted_at.is_(None),
            ).order_by(Ingredient.name)
        )
    ).scalars().all()
    lines: list[ValuationLineDTO] = []
    total = 0
    low = 0
    for r in rows:
        qty = float(r.current_qty or 0)
        cost = int(r.avg_cost_minor or 0)
        val = int(qty * cost)
        is_low = qty < float(r.reorder_threshold or 0) and float(r.reorder_threshold or 0) > 0
        if is_low: low += 1
        total += val
        lines.append(ValuationLineDTO(
            ingredient_id=r.id, sku=r.sku, name=r.name,
            base_unit=r.base_unit, current_qty=qty,
            avg_cost_minor=cost, valuation_minor=val,
            reorder_threshold=float(r.reorder_threshold or 0),
            is_low_stock=is_low,
        ))
    timezone_name = await company_timezone(session, tenant.company_id)
    return InventoryValuationDTO(
        as_of=local_today(timezone_name), lines=lines,
        total_valuation_minor=total, low_stock_count=low,
    )


@router.get("/menu/recipe-margin", response_model=list[RecipeMarginDTO])
async def recipe_margin(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("menu.read")),
) -> list[RecipeMarginDTO]:
    """For each menu item that has a Recipe, compute cost-to-make and margin %.
    Items without recipes (resold bottled drinks etc.) are skipped."""
    items = (
        await session.execute(
            select(MenuItem).where(
                MenuItem.company_id == tenant.company_id,
                MenuItem.deleted_at.is_(None),
            )
        )
    ).scalars().all()
    if not items:
        return []
    item_ids = [i.id for i in items]
    recipes = (
        await session.execute(
            select(Recipe).where(
                Recipe.menu_item_id.in_(item_ids), Recipe.is_active.is_(True)
            )
        )
    ).scalars().all()
    if not recipes:
        return []
    by_item = {r.menu_item_id: r for r in recipes}

    rec_ids = [r.id for r in recipes]
    lines = (
        await session.execute(
            select(RecipeLine, Ingredient.avg_cost_minor).join(
                Ingredient, Ingredient.id == RecipeLine.ingredient_id,
            ).where(RecipeLine.recipe_id.in_(rec_ids))
        )
    ).all()
    cost_by_recipe: dict[UUID, int] = {}
    for rl, avg_cost in lines:
        qty = float(rl.qty) * (1 + float(rl.wastage_pct or 0))
        cost_by_recipe[rl.recipe_id] = cost_by_recipe.get(rl.recipe_id, 0) + int(qty * int(avg_cost or 0))

    out: list[RecipeMarginDTO] = []
    for it in items:
        rec = by_item.get(it.id)
        if not rec:
            continue
        cost = cost_by_recipe.get(rec.id, 0)
        sale = int(it.base_price_minor or 0)
        margin = sale - cost
        margin_pct = (margin / sale * 100) if sale > 0 else 0.0
        out.append(RecipeMarginDTO(
            menu_item_id=it.id, sku=it.sku, name=it.name, type=it.type,
            sale_price_minor=sale, cost_minor=cost,
            margin_minor=margin, margin_pct=margin_pct,
        ))
    out.sort(key=lambda r: r.margin_pct, reverse=True)
    return out


# ---------------------------------------------------------------- GROWTH
def _date_range_for_period(period: str, today: date) -> tuple[tuple[date, date], tuple[date, date], str, str]:
    """Return ((cur_start, cur_end), (prev_start, prev_end), cur_label, prev_label)."""
    if period == "mom":
        cur_start = today.replace(day=1)
        prev_end = cur_start - timedelta(days=1)
        prev_start = prev_end.replace(day=1)
        return (
            (cur_start, today),
            (prev_start, prev_end),
            cur_start.strftime("%b %Y"),
            prev_start.strftime("%b %Y"),
        )
    if period == "yoy":
        cur_start = today.replace(month=1, day=1)
        prev_start = cur_start.replace(year=cur_start.year - 1)
        try:
            prev_end = today.replace(year=today.year - 1)
        except ValueError:
            # today is 29-Feb and the prior year is not a leap year.
            prev_end = today.replace(year=today.year - 1, day=28)
        return (
            (cur_start, today),
            (prev_start, prev_end),
            f"YTD {today.year}",
            f"YTD {today.year - 1}",
        )
    # default — wow (week-over-week)
    cur_start = today - timedelta(days=today.weekday())
    prev_end = cur_start - timedelta(days=1)
    prev_start = prev_end - timedelta(days=6)
    return (
        (cur_start, today),
        (prev_start, prev_end),
        f"Week of {cur_start.isoformat()}",
        f"Week of {prev_start.isoformat()}",
    )


async def _period_stats(
    session,
    company_id: UUID,
    d_from: date,
    d_to: date,
    timezone_name: str,
) -> GrowthPeriodDTO:
    f_dt, t_dt = local_date_bounds_utc(d_from, d_to, timezone_name)
    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    row = (
        await session.execute(
            select(
                func.coalesce(func.sum(Order.total_minor), 0).label("rev"),
                func.coalesce(func.sum(Order.tip_minor), 0).label("tips"),
                func.count(Order.id).label("n"),
            ).where(
                Order.company_id == company_id,
                sale_at >= f_dt, sale_at < t_dt,
                Order.status.in_(("paid", "refunded")),
            )
        )
    ).one()
    # total_minor includes any tip folded on at payment time — a tip is
    # money collected on the staff's behalf, not menu/service revenue, so
    # it's excluded from the growth/AOV figures the same way it's already
    # excluded from the refund-proportion math.
    order_revenue = int(row.rev) - int(row.tips)
    n = int(row.n)
    # order_revenue sums total_minor for paid AND refunded orders (refunded
    # orders still represent a real service event, so they stay in the order
    # count) — but a refund is money handed back, so it must not also inflate
    # the revenue figure sitting above it. Net it out here the same way it's
    # netted in ReportsAggregator, just without that service's proportional
    # cross-period tax allocation, which this simple growth headline doesn't need.
    refunds_minor = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Refund.amount_minor), 0))
                .select_from(Refund)
                .join(Order, Order.id == Refund.order_id)
                .where(
                    Order.company_id == company_id,
                    Refund.created_at >= f_dt, Refund.created_at < t_dt,
                )
            )
        ).scalar_one()
        or 0
    )
    manual_revenue = int(
        (
            await session.execute(
                select(func.coalesce(func.sum(ManualCollection.amount_minor), 0)).where(
                    ManualCollection.company_id == company_id,
                    ManualCollection.business_date >= d_from,
                    ManualCollection.business_date <= d_to,
                    ManualCollection.voided_at.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )
    # Manual collections are revenue but not orders. They affect growth and
    # cash movement, while AOV remains based only on itemized POS orders.
    net_order_revenue = order_revenue - refunds_minor
    avg = net_order_revenue // n if n else 0
    return GrowthPeriodDTO(
        label="",
        revenue_minor=net_order_revenue + manual_revenue,
        refunds_minor=refunds_minor,
        manual_collections_minor=manual_revenue,
        orders_count=n,
        avg_ticket_minor=avg,
    )


@router.get("/growth", response_model=GrowthDTO)
async def growth(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
    period: str = "mom",  # mom|yoy|wow
) -> GrowthDTO:
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    (c_s, c_e), (p_s, p_e), c_label, p_label = _date_range_for_period(period, today)
    cur = await _period_stats(session, tenant.company_id, c_s, c_e, timezone_name)
    prev = await _period_stats(session, tenant.company_id, p_s, p_e, timezone_name)
    cur.label = c_label
    prev.label = p_label
    rev_delta = ((cur.revenue_minor - prev.revenue_minor) / prev.revenue_minor * 100) if prev.revenue_minor > 0 else 0.0
    ord_delta = ((cur.orders_count - prev.orders_count) / prev.orders_count * 100) if prev.orders_count > 0 else 0.0
    return GrowthDTO(current=cur, previous=prev,
                     revenue_delta_pct=rev_delta, orders_delta_pct=ord_delta)


@router.get("/top-items", response_model=list[TopItemDTO])
async def top_items(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
    from_date: date | None = None,
    to_date: date | None = None,
    limit: int = 20,
) -> list[TopItemDTO]:
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    from_date = from_date or today.replace(day=1)
    to_date = to_date or today
    f_dt, t_dt = _validated_date_range(from_date, to_date, timezone_name)
    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    rows = (
        await session.execute(
            select(
                MenuItem.id, MenuItem.name, MenuItem.type,
                func.coalesce(func.sum(OrderLine.qty), 0).label("qty"),
                func.coalesce(func.sum(OrderLine.line_total_minor), 0).label("rev"),
            )
            .join(OrderLine, OrderLine.menu_item_id == MenuItem.id)
            .join(Order, Order.id == OrderLine.order_id)
            .where(
                Order.company_id == tenant.company_id,
                sale_at >= f_dt, sale_at < t_dt,
                Order.status.in_(("paid", "refunded")),
            )
            .group_by(MenuItem.id, MenuItem.name, MenuItem.type)
            .order_by(func.sum(OrderLine.line_total_minor).desc())
            .limit(min(limit, 100))
        )
    ).all()
    return [
        TopItemDTO(
            menu_item_id=r.id, name=r.name, type=r.type,
            qty_sold=float(r.qty or 0), revenue_minor=int(r.rev or 0),
        )
        for r in rows
    ]


@router.get("/heatmap", response_model=list[HeatmapCellDTO])
async def heatmap(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("analytics.read")),
    from_date: date | None = None,
    to_date: date | None = None,
) -> list[HeatmapCellDTO]:
    """Day-of-week × hour-of-day revenue grid. Helps staff scheduling."""
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    from_date = from_date or (today - timedelta(days=30))
    to_date = to_date or today
    f_dt, t_dt = _validated_date_range(from_date, to_date, timezone_name)
    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    local_sale_at = func.timezone(timezone_name, sale_at)
    rows = (
        await session.execute(
            select(
                func.extract("dow", local_sale_at).label("dow"),
                func.extract("hour", local_sale_at).label("hour"),
                func.coalesce(func.sum(Order.total_minor), 0).label("rev"),
                func.coalesce(func.sum(Order.tip_minor), 0).label("tips"),
                func.count(Order.id).label("n"),
            )
            .where(
                Order.company_id == tenant.company_id,
                sale_at >= f_dt, sale_at < t_dt,
                Order.status.in_(("paid", "refunded")),
            )
            .group_by("dow", "hour")
        )
    ).all()
    # Postgres dow: 0=Sunday — shift to Monday=0
    def to_monday_first(dow: int) -> int:
        return (dow + 6) % 7
    return [
        HeatmapCellDTO(
            day_of_week=to_monday_first(int(r.dow)),
            hour=int(r.hour),
            # total_minor includes any tip folded on at payment time — a
            # tip is collected on the staff's behalf, not menu/service
            # revenue, so it's excluded here too.
            revenue_minor=int(r.rev or 0) - int(r.tips or 0),
            orders_count=int(r.n or 0),
        )
        for r in rows
    ]


# ---------------------------------------------------------------- LOSSES
@router.get("/losses", response_model=LossesDTO)
async def losses(
    session: SessionDep,
    tenant: TenantContext = Depends(requires("inventory.read")),
    from_date: date | None = None,
    to_date: date | None = None,
) -> LossesDTO:
    """Aggregate waste + damage + negative-stock movements over a period."""
    timezone_name = await company_timezone(session, tenant.company_id)
    today = local_today(timezone_name)
    from_date = from_date or (today - timedelta(days=30))
    to_date = to_date or today
    f_dt, t_dt = _validated_date_range(from_date, to_date, timezone_name)

    # Pull waste/damage/adjustment movements joined to ingredient via batch
    rows = (
        await session.execute(
            select(
                Ingredient.id, Ingredient.sku, Ingredient.name,
                StockMovement.type,
                StockMovement.qty_delta,
                StockMovement.cost_per_unit_minor,
            )
            .join(Batch, Batch.id == StockMovement.batch_id)
            .join(Ingredient, Ingredient.id == Batch.ingredient_id)
            .where(
                Ingredient.company_id == tenant.company_id,
                StockMovement.created_at >= f_dt,
                StockMovement.created_at < t_dt,
                StockMovement.type.in_(("waste", "damage", "adjustment")),
            )
        )
    ).all()

    waste_total = 0
    damage_total = 0
    neg_total = 0
    per_ing: dict[UUID, dict] = {}
    for ing_id, sku, name, mtype, qty_delta, cost in rows:
        cost_lost = int(abs(float(qty_delta)) * int(cost or 0)) if float(qty_delta) < 0 else 0
        if mtype == "waste": waste_total += cost_lost
        elif mtype == "damage": damage_total += cost_lost
        elif mtype == "adjustment" and float(qty_delta) < 0:
            neg_total += cost_lost
        slot = per_ing.setdefault(ing_id, {
            "sku": sku, "name": name, "qty_lost": 0.0,
            "cost_lost_minor": 0, "movement_count": 0,
        })
        slot["qty_lost"] += abs(float(qty_delta)) if float(qty_delta) < 0 else 0
        slot["cost_lost_minor"] += cost_lost
        if cost_lost > 0:
            slot["movement_count"] += 1

    lines = [
        LossLineDTO(
            ingredient_id=ing_id, sku=v["sku"], name=v["name"],
            qty_lost=v["qty_lost"], cost_lost_minor=v["cost_lost_minor"],
            movement_count=v["movement_count"],
        )
        for ing_id, v in per_ing.items()
        if v["cost_lost_minor"] > 0
    ]
    lines.sort(key=lambda x: x.cost_lost_minor, reverse=True)

    return LossesDTO(
        from_date=from_date, to_date=to_date,
        waste_minor=waste_total, damage_minor=damage_total,
        negative_stock_minor=neg_total,
        total_loss_minor=waste_total + damage_total + neg_total,
        lines=lines,
    )
