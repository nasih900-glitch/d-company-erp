"""Shared branch/terminal validation for operational POS orders."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.core.errors import BusinessRuleError, NotFoundError

if TYPE_CHECKING:
    from uuid import UUID

    from app.models import Order


def require_operational_order(
    order: Order | None,
    *,
    company_id: UUID,
    branch_id: UUID | None,
    terminal_id: UUID | None,
    operation: str,
) -> Order:
    """Return an exact-scope order or raise a user-actionable error.

    Company mismatches stay indistinguishable from a missing row so an order
    UUID can never disclose another tenant's data. Branch and terminal
    mismatches are safe to describe after company ownership is established.
    """
    if branch_id is None:
        raise BusinessRuleError(
            f"This account has no branch assigned. Assign one before {operation}."
        )
    if terminal_id is None:
        raise BusinessRuleError(f"X-Terminal-Id header required before {operation}.")
    if order is None or order.company_id != company_id:
        raise NotFoundError("Order not found for this company.")
    if order.branch_id != branch_id:
        raise BusinessRuleError("Order belongs to a different branch.")
    if order.terminal_id != terminal_id:
        raise BusinessRuleError("Order belongs to a different terminal.")
    return order
