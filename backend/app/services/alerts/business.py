"""Rule-based business alerts for automated owner reports.

These are intentionally deterministic. They give the owner practical warnings
without sending private business data to an external AI provider.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Literal

from sqlalchemy import select

from app.models import Ingredient

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession

    from app.services.reports import PnLReport

AlertSeverity = Literal["info", "warning", "critical"]


@dataclass(frozen=True, slots=True)
class BusinessAlert:
    severity: AlertSeverity
    title: str
    detail: str


def _rs(minor: int) -> str:
    return f"₹{minor / 100:,.2f}"


def build_pnl_alerts(report: PnLReport, previous: PnLReport | None = None) -> list[BusinessAlert]:
    alerts: list[BusinessAlert] = []

    if report.orders_count == 0 and report.tickets_count == 0:
        alerts.append(
            BusinessAlert(
                severity="warning",
                title="No sales recorded",
                detail=(
                    f"No POS orders or event tickets were recorded for {report.label}. "
                    "Check whether the shop was closed or sales were entered outside ERP."
                ),
            )
        )

    if report.gross_revenue_minor > 0 and report.net_profit_minor < 0:
        alerts.append(
            BusinessAlert(
                severity="critical",
                title="Period closed at a loss",
                detail=(
                    f"Net result is {_rs(report.net_profit_minor)} on revenue "
                    f"{_rs(report.gross_revenue_minor)}."
                ),
            )
        )

    if report.net_revenue_minor > 0 and report.expense_total_minor > report.net_revenue_minor:
        alerts.append(
            BusinessAlert(
                severity="warning",
                title="Expenses higher than net revenue",
                detail=(
                    f"Expenses are {_rs(report.expense_total_minor)} against net revenue "
                    f"{_rs(report.net_revenue_minor)}."
                ),
            )
        )

    if report.orders_count > 0 and report.avg_ticket_minor < 15000:
        alerts.append(
            BusinessAlert(
                severity="info",
                title="Average ticket looks low",
                detail=(
                    f"Average order value is {_rs(report.avg_ticket_minor)}. "
                    "Review discounts, combos, and upsell opportunities."
                ),
            )
        )

    if previous and previous.gross_revenue_minor > 0:
        delta_minor = report.gross_revenue_minor - previous.gross_revenue_minor
        delta_pct = (delta_minor * 100) / previous.gross_revenue_minor
        if delta_pct <= -20:
            alerts.append(
                BusinessAlert(
                    severity="warning",
                    title="Revenue dropped sharply",
                    detail=(
                        f"Revenue is down {abs(delta_pct):.1f}% versus the previous comparable "
                        f"period ({_rs(previous.gross_revenue_minor)} to "
                        f"{_rs(report.gross_revenue_minor)})."
                    ),
                )
            )

    if not alerts:
        alerts.append(
            BusinessAlert(
                severity="info",
                title="No urgent P&L alerts",
                detail="Revenue, expenses, and profit do not show an urgent rule-based warning.",
            )
        )

    return alerts


async def build_inventory_alerts(
    session: AsyncSession,
    *,
    company_id: UUID,
    limit: int = 10,
) -> list[BusinessAlert]:
    rows = (
        await session.execute(
            select(Ingredient)
            .where(
                Ingredient.company_id == company_id,
                Ingredient.deleted_at.is_(None),
                Ingredient.reorder_threshold > 0,
                Ingredient.current_qty < Ingredient.reorder_threshold,
            )
            .order_by(Ingredient.name)
            .limit(limit)
        )
    ).scalars().all()

    alerts: list[BusinessAlert] = []
    for ing in rows:
        current = float(ing.current_qty or 0)
        threshold = float(ing.reorder_threshold or 0)
        reorder = float(ing.reorder_qty or 0)
        reorder_text = f" Reorder target: {reorder:g} {ing.base_unit}." if reorder > 0 else ""
        alerts.append(
            BusinessAlert(
                severity="critical",
                title=f"Low stock: {ing.name}",
                detail=(
                    f"{ing.name} is at {current:g} {ing.base_unit}; threshold is "
                    f"{threshold:g} {ing.base_unit}.{reorder_text}"
                ),
            )
        )
    return alerts
