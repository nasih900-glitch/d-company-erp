"""RBAC permission registry + dependency factory.

Permissions are `(module, action)` strings. Roles map to sets of permissions.
Endpoints declare what they need; the dependency rejects requests that lack it.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Annotated

from fastapi import Depends
from sqlalchemy import select

from app.core.db import SessionDep
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

# The self-registration signup path (no in-app role picker) assigns this role
# to every new account. It is deliberately narrower than the legacy titles
# below: no inventory, no analytics (Insights/Reports) — see STAFF_ACCESS.
# Nobody can become "owner" by signing up anymore; only the protected owner
# (super_owner) can promote someone to a broader role from Staff.
SELF_SERVICE_SIGNUP_ROLE = "staff"

STAFF_ACCESS = STANDARD_ACCESS - {
    "inventory.read", "inventory.write", "inventory.adjust.large",
    "analytics.read", "analytics.export",
}

# External CA / audit-firm title — true read-only access. Only the *.read
# permissions (plus analytics.export, which reads/exports data rather than
# mutating it) needed to review finance, ops, and staff records for an audit.
# Deliberately excludes every *.write, *.void, *.refund, shift-open/close,
# OCR upload/verify (both create or mutate bill records), and admin.system.
# admin.audit.read is excluded too — that permission is hardcoded to
# tenant.audit_access (super_owner only, see _has_permission) and is never
# granted through ROLE_PERMISSIONS for any role.
AUDITOR_ACCESS = {
    "pos.read", "tables.read", "menu.read", "inventory.read", "gaming.read",
    "finance.read", "staff.read", "analytics.read", "analytics.export",
}

ROLE_DESCRIPTIONS: dict[str, str] = {
    "owner": "Business owner title — all standard modules",
    "co_owner": "Co-owner — full operational access, no audit log/Access Control",
    "partner": "Business partner title — all standard modules",
    "manager": "Operations manager title — all standard modules",
    "cashier": "Cashier title — all standard modules",
    "kitchen": "Kitchen team title — all standard modules",
    "gaming_supervisor": "Gaming team title — all standard modules",
    "auditor": "External auditor / CA — read-only access to finance, analytics, "
    "and operational records; no write, refund, void, or shift permissions",
    "staff": "General staff — no Inventory or Insights/Reports access by default",
}

# D Company operates with open module access for every legacy title. "staff"
# and "auditor" are the two deliberately-restricted roles — staff has no
# Inventory/Insights access (see STAFF_ACCESS), auditor is read-only (see
# AUDITOR_ACCESS) — the protected owner can widen or narrow any role's access
# live via the Access Control settings panel (ROLE_PERMISSION_OVERRIDES
# table), which layers on top of these defaults rather than replacing them.
ROLE_PERMISSIONS: dict[str, set[str]] = {
    "super_owner": set(PERMISSIONS),
    "co_owner": set(STANDARD_ACCESS),
    "owner": set(STANDARD_ACCESS),
    "partner": set(STANDARD_ACCESS),
    "manager": set(STANDARD_ACCESS),
    "cashier": set(STANDARD_ACCESS),
    "kitchen": set(STANDARD_ACCESS),
    "gaming_supervisor": set(STANDARD_ACCESS),
    "auditor": set(AUDITOR_ACCESS),
    "staff": set(STAFF_ACCESS),
}

# Coarse feature groupings for the Access Control panel — one toggle per
# module per role, rather than one per fine-grained permission string (too
# granular to be a sane UI). Deliberately excludes admin.audit.read and
# admin.system: those stay hardcoded to the static role defaults (in practice,
# protected-owner-only) and are never shown as a togglable module — letting
# them be overridden would let someone grant themselves audit access.
MODULE_PERMISSIONS: dict[str, set[str]] = {
    "pos": {
        "pos.read", "pos.write", "pos.void", "pos.refund",
        "pos.discount.large", "pos.shift.open", "pos.shift.close",
    },
    "tables": {"tables.read", "tables.write", "tables.reservations.write"},
    "menu": {"menu.read", "menu.write"},
    "inventory": {"inventory.read", "inventory.write", "inventory.adjust.large"},
    "gaming": {"gaming.read", "gaming.write", "gaming.tournament.manage"},
    "finance": {
        "finance.read", "finance.write", "finance.partner.write", "finance.assets.write",
    },
    "ocr": {"ocr.upload", "ocr.verify"},
    "staff": {"staff.read", "staff.write", "staff.attendance.write", "staff.payroll.write"},
    "insights_reports": {"analytics.read", "analytics.export"},
}

_PERMISSION_TO_MODULE: dict[str, str] = {
    perm: module for module, perms in MODULE_PERMISSIONS.items() for perm in perms
}


async def _module_override(
    session, *, company_id, role: str, module: str,
) -> bool | None:
    """Return this company's explicit allow/deny for (role, module), or None
    if no override has been set (caller should fall back to the role default).
    """
    from app.models.access_control import RolePermissionOverride

    row = (
        await session.execute(
            select(RolePermissionOverride.allowed).where(
                RolePermissionOverride.company_id == company_id,
                RolePermissionOverride.role_code == role,
                RolePermissionOverride.module == module,
            )
        )
    ).scalar_one_or_none()
    return row


async def _has_permission(session, tenant: TenantContext, perm: str) -> bool:
    # admin.audit.read is deliberately excluded from the protected_access
    # blanket bypass — co_owner gets protected_access=True (see roles.py's
    # has_full_access) for operational RBAC, but must NOT thereby gain the
    # audit log / Access Control panel. Only audit_access (super_owner only)
    # grants this specific permission.
    if perm == "admin.audit.read":
        return tenant.audit_access
    if tenant.protected_access:
        return True
    module = _PERMISSION_TO_MODULE.get(perm)
    for role in tenant.roles:
        default_allowed = perm in ROLE_PERMISSIONS.get(role, set())
        if module is None:
            if default_allowed:
                return True
            continue
        override = await _module_override(
            session, company_id=tenant.company_id, role=role, module=module,
        )
        allowed = default_allowed if override is None else override
        # Hard ceiling, enforced at check-time regardless of what the
        # override table contains: MODULE_PERMISSIONS bundles read AND write
        # permissions together per module (e.g. 'pos' = pos.read + pos.write
        # + pos.void + ...), so a module override can otherwise turn on an
        # entire bundle for a role. For 'auditor' specifically that must
        # never happen — its whole purpose is read-only access. Effective
        # auditor permissions are always (role defaults + overrides) ∩
        # AUDITOR_ACCESS, never a superset of it, no matter what row exists
        # in role_permission_overrides (defense in depth alongside the
        # write-time validation in update_access_control).
        if role == "auditor" and perm not in AUDITOR_ACCESS:
            allowed = False
        if allowed:
            return True
    return False


async def accessible_modules(session, tenant: TenantContext) -> list[str]:
    """Which MODULE_PERMISSIONS groups this caller can reach at all — one
    "can I see this nav tab" check per module, for /auth/me. A module is
    accessible if any of its permissions resolves true (same OR-across-roles
    semantics as _has_permission)."""
    if tenant.protected_access:
        return sorted(MODULE_PERMISSIONS)
    out = []
    for module, perms in MODULE_PERMISSIONS.items():
        for perm in perms:
            if await _has_permission(session, tenant, perm):
                out.append(module)
                break
    return out


def requires(*perms: str) -> Callable[[TenantContext], TenantContext]:
    """FastAPI dependency factory: usage `tenant = Depends(requires('pos.write'))`."""

    async def _dep(
        session: SessionDep,
        tenant: Annotated[TenantContext, Depends(get_tenant_context)],
    ) -> TenantContext:
        for p in perms:
            if not await _has_permission(session, tenant, p):
                raise ForbiddenError(
                    f"missing permission: {p}",
                    details={"have": list(tenant.roles)},
                )
        return tenant

    return _dep
