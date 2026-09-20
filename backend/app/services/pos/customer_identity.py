"""Stable customer resolution for an existing POS order."""

from __future__ import annotations

from typing import TYPE_CHECKING

from sqlalchemy import select

from app.models import Customer, Order

if TYPE_CHECKING:
    from uuid import UUID

    from sqlalchemy.ext.asyncio import AsyncSession


async def resolve_order_customer(
    session: AsyncSession,
    *,
    order: Order,
    company_id: UUID,
    for_update: bool = False,
) -> Customer | None:
    """Resolve an order's customer without reassigning a stable identity.

    Orders created before stable customer links may still use their exact phone
    snapshot. Once ``customer_id`` exists it is authoritative, including when
    that customer is later edited or deleted; a failed stable lookup must never
    fall through to whoever currently owns the old phone number.
    """
    if order.company_id != company_id:
        return None
    if order.customer_id is not None:
        predicate = Customer.id == order.customer_id
    elif order.customer_phone:
        predicate = Customer.phone == order.customer_phone
    else:
        return None
    statement = select(Customer).where(
        Customer.company_id == company_id,
        predicate,
        Customer.deleted_at.is_(None),
    )
    if for_update:
        statement = statement.with_for_update()
    return (await session.execute(statement)).scalar_one_or_none()
