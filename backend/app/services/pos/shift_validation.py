"""Shared branch/terminal validation for POS and gaming shifts."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.core.errors import BusinessRuleError, NotFoundError

if TYPE_CHECKING:
    from uuid import UUID

    from app.models import Shift


def require_operational_shift_scope(
    shift: Shift | None,
    *,
    company_id: UUID,
    branch_id: UUID | None,
    terminal_id: UUID | None,
    operation: str,
    resource_branch_id: UUID | None = None,
    resource_name: str = "resource",
) -> Shift:
    """Return an exact company/branch/terminal shift regardless of status."""
    if branch_id is None:
        raise BusinessRuleError(
            f"This account has no branch assigned. Assign one before {operation}."
        )
    if terminal_id is None:
        raise BusinessRuleError(
            f"X-Terminal-Id header required before {operation}."
        )
    # Do not disclose whether a UUID belongs to another tenant.
    if shift is None or shift.company_id != company_id:
        raise NotFoundError("Shift not found for this company.")
    if resource_branch_id is not None and shift.branch_id != resource_branch_id:
        raise BusinessRuleError(
            f"Shift branch does not match the {resource_name} branch."
        )
    if shift.branch_id != branch_id:
        raise BusinessRuleError("Shift belongs to a different branch.")
    if shift.terminal_id != terminal_id:
        raise BusinessRuleError("Shift belongs to a different terminal.")
    return shift


def require_open_operational_shift(
    shift: Shift | None,
    *,
    company_id: UUID,
    branch_id: UUID | None,
    terminal_id: UUID | None,
    operation: str,
    resource_branch_id: UUID | None = None,
    resource_name: str = "resource",
) -> Shift:
    """Return an exact-scope open shift or raise a user-actionable error."""
    shift = require_operational_shift_scope(
        shift,
        company_id=company_id,
        branch_id=branch_id,
        terminal_id=terminal_id,
        operation=operation,
        resource_branch_id=resource_branch_id,
        resource_name=resource_name,
    )
    if shift.status != "open":
        raise BusinessRuleError(
            f"Shift is {shift.status}. Open a shift for this terminal before {operation}."
        )
    return shift


def require_shift_opener(
    shift: Shift,
    *,
    user_id: UUID,
    protected_access: bool,
    operation: str,
) -> None:
    """Only the person who opened this shift may do certain high-trust
    money actions on it (direct cashier bills, payment, refund, and void)
    — they're the one accountable for its cash and payment activity.
    A protected owner can always override this.

    Shift closing is intentionally not guarded here. It has its own
    ``pos.shift.close`` permission and records the authenticated closer in
    ``Shift.closed_by`` so an authorised colleague can complete the day
    without rewriting who opened the shift.
    """
    if protected_access:
        return
    if shift.opened_by != user_id:
        raise BusinessRuleError(
            f"Only the staff member who opened this shift can {operation}. "
            "Ask them, or have a protected owner do it."
        )
