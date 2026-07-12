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

STANDARD_ACCESS = set(PERMISSIONS) - {"admin.audit.read"}

ROLE_DESCRIPTIONS: dict[str, str] = {
    "owner": "Business owner title — all standard modules",
    "partner": "Business partner title — all standard modules",
    "manager": "Operations manager title — all standard modules",
    "cashier": "Cashier title — all standard modules",
    "kitchen": "Kitchen team title — all standard modules",
    "gaming_supervisor": "Gaming team title — all standard modules",
    "auditor": "Audit staff title — all standard modules",
}

# D Company operates with open module access for every active account. Role names
# remain useful for staff records, but Audit is the sole protected-owner module.
ROLE_PERMISSIONS: dict[str, set[str]] = {
    "super_owner": set(PERMISSIONS),
    "owner": set(STANDARD_ACCESS),
    "partner": set(STANDARD_ACCESS),
    "manager": set(STANDARD_ACCESS),
    "cashier": set(STANDARD_ACCESS),
    "kitchen": set(STANDARD_ACCESS),
    "gaming_supervisor": set(STANDARD_ACCESS),
    "auditor": set(STANDARD_ACCESS),
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
