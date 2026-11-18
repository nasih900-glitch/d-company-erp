"""Shared projections for no-value corrections; actual refunds remain separate."""

from __future__ import annotations

from typing import TYPE_CHECKING

from sqlalchemy import exists, func, select
from sqlalchemy.orm import load_only

from app.core.errors import BusinessRuleError
from app.models import Order, PosReceiptCorrection

if TYPE_CHECKING:
    from datetime import datetime
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


def uncorrected_sale(*, as_of: datetime):
    """Operational sale-cohort view as of its exclusive period end."""
    return ~exists().where(
        PosReceiptCorrection.order_id == Order.id,
        PosReceiptCorrection.company_id == Order.company_id,
        PosReceiptCorrection.accounting_at < as_of,
    )


async def corrections_for_orders(
    session: AsyncSession,
    *,
    company_id: UUID,
    order_ids,
) -> dict[UUID, PosReceiptCorrection]:
    if not order_ids:
        return {}
    return {
        row.order_id: row
        for row in await session.scalars(
            select(PosReceiptCorrection)
            .options(
                load_only(
                    PosReceiptCorrection.id,
                    PosReceiptCorrection.order_id,
                    PosReceiptCorrection.kind,
                    PosReceiptCorrection.amount_minor,
                    PosReceiptCorrection.accounting_at,
                    PosReceiptCorrection.response_payload,
                )
            )
            .where(
                PosReceiptCorrection.company_id == company_id,
                PosReceiptCorrection.order_id.in_(order_ids),
            )
        )
    }


async def period_corrections(
    session: AsyncSession,
    *,
    company_id: UUID,
    start_at: datetime,
    end_at: datetime,
    branch_id: UUID | None = None,
    include_source: bool = False,
) -> list[PosReceiptCorrection]:
    statement = select(PosReceiptCorrection).where(
        PosReceiptCorrection.company_id == company_id,
        PosReceiptCorrection.accounting_at >= start_at,
        PosReceiptCorrection.accounting_at < end_at,
    )
    if branch_id is not None:
        statement = statement.where(PosReceiptCorrection.branch_id == branch_id)
    if not include_source:
        statement = statement.options(
            load_only(
                PosReceiptCorrection.id,
                PosReceiptCorrection.order_id,
                PosReceiptCorrection.invoice_no,
                PosReceiptCorrection.amount_minor,
                PosReceiptCorrection.accounting_at,
                PosReceiptCorrection.response_payload,
            )
        )
    return list(
        await session.scalars(
            statement.order_by(
                PosReceiptCorrection.accounting_at,
                PosReceiptCorrection.id,
            )
        )
    )


async def corrected_sale_cohort(
    session: AsyncSession,
    *,
    company_id: UUID,
    start_at: datetime,
    end_at: datetime,
    branch_id: UUID | None = None,
) -> tuple[int, int]:
    """Count/value of this sale cohort corrected before its exclusive period end."""
    sale_at = func.coalesce(Order.invoice_issued_at, Order.closed_at)
    statement = (
        select(
            func.count(PosReceiptCorrection.id),
            func.coalesce(func.sum(PosReceiptCorrection.amount_minor), 0),
        )
        .join(Order, Order.id == PosReceiptCorrection.order_id)
        .where(
            PosReceiptCorrection.company_id == company_id,
            Order.company_id == company_id,
            sale_at >= start_at,
            sale_at < end_at,
            PosReceiptCorrection.accounting_at < end_at,
        )
    )
    if branch_id is not None:
        statement = statement.where(PosReceiptCorrection.branch_id == branch_id)
    count, amount = (await session.execute(statement)).one()
    return int(count), int(amount)


def correction_metadata(row: PosReceiptCorrection) -> dict:
    return {
        "id": row.id,
        "kind": row.kind,
        "amount_minor": row.amount_minor,
        "accounting_at": row.accounting_at,
        "payout_minor": 0,
        "label": "Corrected — payment not received; no payout",
        "stock_restored": bool(row.response_payload.get("stock_restored", False)),
        "restored_movement_count": row.response_payload.get("restored_movement_count", 0),
        "inventory_increase_minor": row.response_payload.get("inventory_increase_minor", 0),
    }


async def require_uncorrected_receipt(
    session: AsyncSession,
    *,
    company_id: UUID,
    order_id: UUID,
) -> None:
    if await session.scalar(
        select(
            exists().where(
                PosReceiptCorrection.company_id == company_id,
                PosReceiptCorrection.order_id == order_id,
            )
        )
    ):
        raise BusinessRuleError(
            "This receipt was corrected because no payment was received. "
            "It cannot be refunded or reclassified as a payment."
        )
