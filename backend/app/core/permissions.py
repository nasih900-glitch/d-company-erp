"""RBAC permission registry + dependency factory.

Permissions are `(module, action)` strings. Roles map to sets of permissions.
Endpoints declare what they need; the dependency rejects requests that lack it.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Annotated

from fastapi import Depends

from app.core.errors import ForbiddenError
from app.core.tenant import TenantContext, get_tenant_context

if TYPE_CHECKING:
    from collections.abc import Callable

# Canonical permission strings — kept centralized so docs/tests can enumerate them.
PERMISSIONS: dict[str, str] = {
    # POS
    "pos.read": "View orders, shifts, receipts",
    "pos.write": "Create / modify orders",
    "pos.void": "Void an order line",
    "pos.refund": "Issue a refund",
    "pos.discount.large": "Apply discount above the default cap",
    "pos.shift.open": "Open a shift",
    "pos.shift.close": "Close a shift",
    # Tables
    "tables.read": "View floor plan",
    "tables.write": "Update table status, layout",
    "tables.reservations.write": "Create / cancel reservations",
    # Menu
    "menu.read": "View menu and recipes",
    "menu.write": "Modify menu, prices, recipes",
    # Inventory
    "inventory.read": "View stock, batches, suppliers",
    "inventory.write": "GRN, adjustments, waste",
    "inventory.adjust.large": "Adjustment > threshold (owner approval)",
    # Gaming
    "gaming.read": "View stations, sessions, bookings",
    "gaming.write": "Start/stop sessions, bookings",
    "gaming.tournament.manage": "Run tournaments",
    # Finance
    "finance.read": "View P&L, ledger, partner balances",
    "finance.write": "Record expenses, capital, payments",
    "finance.partner.write": "Modify partner / capital records",
    "finance.assets.write": "Add / depreciate assets",
    # OCR
    "ocr.upload": "Upload receipts / invoices",
    "ocr.verify": "Approve / reject extracted bills",
    # Staff
    "staff.read": "View staff records",
    "staff.write": "Modify users, roles",
    "staff.attendance.write": "Edit attendance",
    "staff.payroll.write": "Run payroll",
    # Analytics
    "analytics.read": "View dashboards",
    "analytics.export": "Export data (Power BI / CSV)",
    # Admin
    "admin.audit.read": "Read audit logs",
    "admin.system": "Tenant / company / branch admin",
}

ROLE_PERMISSIONS: dict[str, set[str]] = {
    "super_owner": set(PERMISSIONS.keys()),
    "owner": set(PERMISSIONS.keys()) - {
        "admin.audit.read",
        "admin.system",
        "staff.write",
        "staff.payroll.write",
        "inventory.write",
        "inventory.adjust.large",
    },
    "partner": {
        "finance.read",
        "finance.partner.write",
        "analytics.read",
        "analytics.export",
    },
    "manager": {
        "pos.read", "pos.write", "pos.void", "pos.refund", "pos.discount.large",
        "pos.shift.open", "pos.shift.close",
        "tables.read", "tables.write", "tables.reservations.write",
        "menu.read", "menu.write",
        "inventory.read",
        "gaming.read", "gaming.write", "gaming.tournament.manage",
        "finance.read", "finance.write",
        "ocr.upload", "ocr.verify",
        "staff.read", "staff.write", "staff.attendance.write",
        "analytics.read",
    },
    "cashier": {
        "pos.read", "pos.write", "pos.shift.open", "pos.shift.close",
        "tables.read", "tables.write",
        "menu.read",
        "inventory.read",
    },
    "kitchen": {
        "pos.read", "menu.read",
    },
    "gaming_supervisor": {
        "gaming.read", "gaming.write",
        "pos.read", "pos.write",
        "tables.read",
        "menu.read",
    },
    "auditor": {
        "pos.read", "tables.read", "menu.read", "inventory.read",
        "gaming.read", "finance.read", "staff.read", "analytics.read",
    },
}


def _has_permission(tenant: TenantContext, perm: str) -> bool:
    if tenant.protected_access:
        return True
    return any(perm in ROLE_PERMISSIONS.get(role, set()) for role in tenant.roles)


def requires(*perms: str) -> Callable[[TenantContext], TenantContext]:
    """FastAPI dependency factory: usage `tenant = Depends(requires('pos.write'))`."""

    def _dep(
        tenant: Annotated[TenantContext, Depends(get_tenant_context)],
    ) -> TenantContext:
        for p in perms:
            if not _has_permission(tenant, p):
                raise ForbiddenError(
                    f"missing permission: {p}",
                    details={"have": list(tenant.roles)},
                )
        return tenant

    return _dep
