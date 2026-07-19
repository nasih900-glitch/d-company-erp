from uuid import uuid4

import pytest

from app.core.errors import AuthError
from app.core.permissions import AUDITOR_ACCESS, PERMISSIONS, ROLE_PERMISSIONS, _has_permission
from app.core.pricing_lock import require_pricing_unlock
from app.core.security import issue_pricing_token
from app.core.tenant import TenantContext


def test_super_owner_has_all_permissions() -> None:
    assert ROLE_PERMISSIONS["super_owner"] == set(PERMISSIONS.keys())


def test_every_legacy_title_has_every_non_audit_permission() -> None:
    # "staff" and "auditor" are the deliberate exceptions — see tests below.
    expected = set(PERMISSIONS) - {"admin.audit.read"}
    for role in ROLE_PERMISSIONS.keys() - {"super_owner", "staff", "auditor"}:
        assert ROLE_PERMISSIONS[role] == expected


def test_staff_role_has_no_inventory_or_insights_reports_access() -> None:
    staff_perms = ROLE_PERMISSIONS["staff"]
    for perm in ("inventory.read", "inventory.write", "inventory.adjust.large",
                 "analytics.read", "analytics.export", "admin.audit.read"):
        assert perm not in staff_perms
    # Everything else standard (POS, tables, gaming, etc.) stays available.
    assert "pos.write" in staff_perms
    assert "gaming.write" in staff_perms


def test_audit_read_is_protected_owner_only() -> None:
    assert "admin.audit.read" in ROLE_PERMISSIONS["super_owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["auditor"]


def test_auditor_role_is_read_only() -> None:
    auditor_perms = ROLE_PERMISSIONS["auditor"]
    assert auditor_perms == AUDITOR_ACCESS

    # Read (and export) access needed to review finance/ops for an audit.
    for perm in (
        "finance.read", "analytics.read", "analytics.export", "pos.read",
        "inventory.read", "staff.read", "tables.read", "menu.read", "gaming.read",
    ):
        assert perm in auditor_perms

    # No write/refund/void/shift/admin permission of any kind survives.
    mutating = {
        "pos.write", "pos.void", "pos.refund", "pos.discount.large",
        "pos.shift.open", "pos.shift.close", "tables.write",
        "tables.reservations.write", "menu.write", "inventory.write",
        "inventory.adjust.large", "gaming.write", "gaming.tournament.manage",
        "finance.write", "finance.partner.write", "finance.assets.write",
        "ocr.upload", "ocr.verify", "staff.write", "staff.attendance.write",
        "staff.payroll.write", "admin.audit.read", "admin.system",
    }
    assert auditor_perms.isdisjoint(mutating)
    # Every permission granted is declared and every mutating one accounted for.
    assert auditor_perms | mutating == set(PERMISSIONS)


def test_pricing_control_requires_the_current_users_unlock_token() -> None:
    normal_owner = _tenant(protected_access=False)
    protected_owner = _tenant(protected_access=True)
    normal_token = issue_pricing_token(
        user_id=normal_owner.user_id,
        company_id=normal_owner.company_id,
    )
    protected_token = issue_pricing_token(
        user_id=protected_owner.user_id,
        company_id=protected_owner.company_id,
    )

    with pytest.raises(AuthError):
        require_pricing_unlock(None, protected_owner)
    with pytest.raises(AuthError):
        require_pricing_unlock(protected_token, normal_owner)
    require_pricing_unlock(normal_token, normal_owner)
    require_pricing_unlock(protected_token, protected_owner)


def test_all_role_perms_are_declared() -> None:
    for role, perms in ROLE_PERMISSIONS.items():
        for p in perms:
            assert p in PERMISSIONS, f"role {role!r} grants undeclared permission {p!r}"


def test_co_owner_role_matches_owner_permissions_exactly() -> None:
    assert ROLE_PERMISSIONS["co_owner"] == ROLE_PERMISSIONS["owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["co_owner"]


async def test_protected_access_never_leaks_admin_audit_read() -> None:
    # A co_owner-shaped tenant: protected_access=True (operational bypass)
    # but audit_access=False. This is the exact scenario the carve-out in
    # _has_permission exists for — protected_access alone must not grant
    # admin.audit.read, only audit_access does.
    co_owner = _tenant(protected_access=True, audit_access=False, roles=("co_owner",))
    assert await _has_permission(None, co_owner, "admin.audit.read") is False
    # The blanket bypass still applies to every other permission.
    assert await _has_permission(None, co_owner, "finance.write") is True
    assert await _has_permission(None, co_owner, "staff.write") is True


async def test_audit_access_grants_admin_audit_read_regardless_of_role() -> None:
    super_owner = _tenant(protected_access=True, audit_access=True, roles=("super_owner",))
    assert await _has_permission(None, super_owner, "admin.audit.read") is True


def _tenant(
    protected_access: bool, audit_access: bool = False, roles: tuple[str, ...] = ("owner",),
) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=None,
        terminal_id=None,
        roles=roles,
        protected_access=protected_access,
        audit_access=audit_access,
    )
