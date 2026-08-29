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
    from collections.abc import Callable, Collection

# Canonical permission strings — kept centralized so docs/tests can enumerate them.
PERMISSIONS: dict[str, str] = {
    # POS
    "pos.read": "View orders, shifts, receipts",
    "pos.write": "Create / modify orders",
    "pos.void": "Void an order line",
    "pos.refund": "Issue a refund",
    # Legacy permission code retained for API/role compatibility. There is no
    # cashier cap: any non-zero manual order discount is manager/owner-only.
    "pos.discount.large": "Apply a manual order discount",
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
    # Kitchen display
    "kitchen.read": "View the kitchen queue",
    "kitchen.write": "Advance kitchen ticket status",
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
    # Owner-level business administration. These are deliberately separate
    # from admin.system so operational co-owners can manage the cafe without
    # gaining Audit Log, Access Control, or the private bug-report inbox.
    "settings.manage": "Manage company, branch, terminal, and pricing settings",
    "memberships.manage": "Manage membership plans and protected membership workflows",
    # Admin
    "admin.audit.read": "Read audit logs",
    "admin.system": "Protected support inbox and evidence-reconciliation controls",
}

# The self-registration signup path (no in-app role picker) assigns this role
# to every new account. It is deliberately limited to the waiter/service flow:
# build table orders, follow kitchen progress, and clock in/out. It cannot open
# a cash drawer, refund, administer staff, or see finance/admin data.
SELF_SERVICE_SIGNUP_ROLE = "staff"

STAFF_ACCESS = {
    "pos.read",
    "pos.write",
    "tables.read",
    "tables.write",
    "tables.reservations.write",
    "menu.read",
    "kitchen.read",
    "staff.attendance.write",
}

CASHIER_ACCESS = STAFF_ACCESS | {
    "pos.void",
    "pos.shift.open",
    "pos.shift.close",
    "gaming.read",
}

KITCHEN_ACCESS = {
    "kitchen.read",
    "kitchen.write",
    "menu.read",
    "staff.attendance.write",
}

GAMING_ACCESS = {
    "pos.read",
    "menu.read",
    "gaming.read",
    "gaming.write",
    "gaming.tournament.manage",
    "staff.attendance.write",
}

PARTNER_ACCESS = {
    "pos.read",
    "tables.read",
    "menu.read",
    "inventory.read",
    "gaming.read",
    "kitchen.read",
    "finance.read",
    "staff.read",
    "analytics.read",
    "analytics.export",
    "staff.attendance.write",
}

MANAGER_ACCESS = {
    "pos.read",
    "pos.write",
    "pos.void",
    "pos.refund",
    "pos.discount.large",
    "pos.shift.open",
    "pos.shift.close",
    "tables.read",
    "tables.write",
    "tables.reservations.write",
    "menu.read",
    "menu.write",
    "inventory.read",
    "inventory.write",
    "inventory.adjust.large",
    "gaming.read",
    "gaming.write",
    "gaming.tournament.manage",
    "kitchen.read",
    "kitchen.write",
    "finance.read",
    "finance.write",
    "ocr.upload",
    "ocr.verify",
    "staff.read",
    "staff.write",
    "staff.attendance.write",
    "analytics.read",
    "analytics.export",
}

OWNER_ACCESS = MANAGER_ACCESS | {
    "finance.assets.write",
    "staff.payroll.write",
    "settings.manage",
    "memberships.manage",
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
    "pos.read",
    "tables.read",
    "menu.read",
    "inventory.read",
    "gaming.read",
    "kitchen.read",
    "finance.read",
    "staff.read",
    "analytics.read",
    "analytics.export",
}

# These actions may be granted only by code-defined role defaults. Access
# Control uses one coarse switch for a whole module, so allowing an override to
# widen this set would make "enable POS" also grant refunds and large discounts,
# or "enable Staff" grant role administration. Overrides may still disable and
# re-enable the safe subset already assigned to a role.
HIGH_TRUST_PERMISSIONS = {
    "pos.void",
    "pos.refund",
    "pos.discount.large",
    "pos.shift.open",
    "pos.shift.close",
    "inventory.adjust.large",
    "finance.write",
    "finance.partner.write",
    "finance.assets.write",
    "staff.write",
    "staff.payroll.write",
    "settings.manage",
    "memberships.manage",
}

ROLE_DESCRIPTIONS: dict[str, str] = {
    "owner": "Business owner — operational, finance, asset, and payroll access",
    "co_owner": "Co-owner — full operational access, no audit log/Access Control",
    "partner": "Business partner — read-only business and finance visibility",
    "manager": "Operations manager — daily operations and staff management",
    "cashier": "Cashier — POS, table orders, and shift operation",
    "kitchen": "Kitchen team — kitchen display and ticket status only",
    "gaming_supervisor": "Gaming team — sessions, bookings, and tournaments",
    "auditor": "External auditor / CA — read-only access to finance, analytics, "
    "and operational records; no write, refund, void, or shift permissions",
    "staff": "General service staff — table orders, kitchen progress, and attendance",
}

# Least-privilege defaults. A role gets only the actions needed for its normal
# cafe workflow; protected owners retain the operational bypass in roles.py.
ROLE_PERMISSIONS: dict[str, set[str]] = {
    "super_owner": set(PERMISSIONS),
    "co_owner": set(OWNER_ACCESS),
    "owner": set(OWNER_ACCESS),
    "partner": set(PARTNER_ACCESS),
    "manager": set(MANAGER_ACCESS),
    "cashier": set(CASHIER_ACCESS),
    "kitchen": set(KITCHEN_ACCESS),
    "gaming_supervisor": set(GAMING_ACCESS),
    "auditor": set(AUDITOR_ACCESS),
    "staff": set(STAFF_ACCESS),
}

# Coarse feature groupings for the Access Control panel — one toggle per
# module per role, rather than one per fine-grained permission string (too
# granular to be a sane UI). Deliberately excludes the owner-only
# settings.manage/memberships.manage permissions and the protected-owner-only
# admin.audit.read/admin.system permissions. None may be widened through a
# coarse module toggle; in particular, audit/system access must never become
# self-grantable.
MODULE_PERMISSIONS: dict[str, set[str]] = {
    "pos": {
        "pos.read",
        "pos.write",
        "pos.void",
        "pos.refund",
        "pos.discount.large",
        "pos.shift.open",
        "pos.shift.close",
    },
    "tables": {"tables.read", "tables.write", "tables.reservations.write"},
    "menu": {"menu.read", "menu.write"},
    "inventory": {"inventory.read", "inventory.write", "inventory.adjust.large"},
    "gaming": {"gaming.read", "gaming.write", "gaming.tournament.manage"},
    "kitchen": {"kitchen.read", "kitchen.write"},
    "finance": {
        "finance.read",
        "finance.write",
        "finance.partner.write",
        "finance.assets.write",
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


def _role_allows_permission(
    *,
    role: str,
    perm: str,
    module: str | None,
    override: bool | None,
) -> bool:
    """Apply one role's default, override, and immutable safety ceilings."""
    default_allowed = perm in ROLE_PERMISSIONS.get(role, set())
    allowed = default_allowed if module is None or override is None else override
    if perm in HIGH_TRUST_PERMISSIONS and not default_allowed:
        allowed = False
    if role == "auditor" and perm not in AUDITOR_ACCESS:
        allowed = False
    return allowed


async def _has_permission(session, tenant: TenantContext, perm: str) -> bool:
    # Audit and protected system controls are deliberately excluded from the
    # protected_access blanket bypass. A co-owner may override operational
    # gates and receives dedicated settings/membership management, but only
    # the designated protected owner (audit_access=True) can read Audit Log,
    # Access Control, the support inbox, or evidence-reconciliation controls.
    if perm in {"admin.audit.read", "admin.system"}:
        return tenant.audit_access
    if tenant.protected_access:
        return True
    module = _PERMISSION_TO_MODULE.get(perm)
    for role in tenant.roles:
        override = None
        if module is not None:
            override = await _module_override(
                session,
                company_id=tenant.company_id,
                role=role,
                module=module,
            )
        if _role_allows_permission(
            role=role,
            perm=perm,
            module=module,
            override=override,
        ):
            return True
    return False


async def effective_permissions(session, tenant: TenantContext) -> list[str]:
    """Return the exact post-override permission set used by API guards.

    Module overrides are fetched once so a single ``/auth/me`` request does
    not issue one query per declared permission.
    """
    from app.models.access_control import RolePermissionOverride

    overrides: dict[tuple[str, str], bool] = {}
    if not tenant.protected_access and tenant.roles:
        rows = (
            await session.execute(
                select(
                    RolePermissionOverride.role_code,
                    RolePermissionOverride.module,
                    RolePermissionOverride.allowed,
                ).where(
                    RolePermissionOverride.company_id == tenant.company_id,
                    RolePermissionOverride.role_code.in_(tenant.roles),
                )
            )
        ).all()
        overrides = {(role, module): allowed for role, module, allowed in rows}

    out: list[str] = []
    for perm in PERMISSIONS:
        if perm in {"admin.audit.read", "admin.system"}:
            allowed = tenant.audit_access
        elif tenant.protected_access:
            allowed = True
        else:
            module = _PERMISSION_TO_MODULE.get(perm)
            allowed = any(
                _role_allows_permission(
                    role=role,
                    perm=perm,
                    module=module,
                    override=overrides.get((role, module)) if module is not None else None,
                )
                for role in tenant.roles
            )
        if allowed:
            out.append(perm)
    return sorted(out)


def modules_for_permissions(perms: Collection[str]) -> list[str]:
    """Collapse exact permissions into the legacy coarse module contract."""
    granted = set(perms)
    return sorted(
        module for module, module_perms in MODULE_PERMISSIONS.items() if granted & module_perms
    )


async def accessible_modules(session, tenant: TenantContext) -> list[str]:
    """Which MODULE_PERMISSIONS groups this caller can reach at all — one
    "can I see this nav tab" check per module, for /auth/me. A module is
    accessible if any of its permissions resolves true (same OR-across-roles
    semantics as _has_permission)."""
    return modules_for_permissions(await effective_permissions(session, tenant))


async def require_permission(session, tenant: TenantContext, perm: str) -> None:
    """Enforce one permission from inside a conditionally privileged route.

    Most endpoints should keep using :func:`requires`. This helper exists for
    operations where the request body determines whether an additional,
    high-trust permission is required (for example, adding rather than merely
    removing a manual discount).
    """
    if not await _has_permission(session, tenant, perm):
        raise ForbiddenError(
            f"missing permission: {perm}",
            details={"have": list(tenant.roles)},
        )


async def has_permission(session, tenant: TenantContext, perm: str) -> bool:
    """Resolve a permission for conditional route scoping without raising.

    Most endpoints should use :func:`requires`. This narrow public predicate
    is for mixed read routes such as terminal discovery: POS staff may inspect
    only their current branch, while a settings manager may inspect any active
    branch in the same company.
    """
    return await _has_permission(session, tenant, perm)


def requires(*perms: str) -> Callable[[TenantContext], TenantContext]:
    """FastAPI dependency factory: usage `tenant = Depends(requires('pos.write'))`."""

    async def _dep(
        session: SessionDep,
        tenant: Annotated[TenantContext, Depends(get_tenant_context)],
    ) -> TenantContext:
        for p in perms:
            await require_permission(session, tenant, p)
        return tenant

    return _dep


def requires_any(*perms: str) -> Callable[[TenantContext], TenantContext]:
    """FastAPI dependency factory that accepts any one listed permission."""
    if not perms:
        raise ValueError("requires_any needs at least one permission")

    async def _dep(
        session: SessionDep,
        tenant: Annotated[TenantContext, Depends(get_tenant_context)],
    ) -> TenantContext:
        for permission in perms:
            if await _has_permission(session, tenant, permission):
                return tenant
        raise ForbiddenError(
            f"missing any permission: {', '.join(perms)}",
            details={"have": list(tenant.roles)},
        )

    return _dep
