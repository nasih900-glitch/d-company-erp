from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

import pytest

from app.core.errors import AuthError, ForbiddenError
from app.core.permissions import (
    AUDITOR_ACCESS,
    HIGH_TRUST_PERMISSIONS,
    PERMISSIONS,
    ROLE_PERMISSIONS,
    _has_permission,
    effective_permissions,
    modules_for_permissions,
    requires_any,
)
from app.core.pricing_lock import require_pricing_unlock
from app.core.security import issue_pricing_token
from app.core.tenant import TenantContext


def test_super_owner_has_all_permissions() -> None:
    assert ROLE_PERMISSIONS["super_owner"] == set(PERMISSIONS.keys())


def test_low_privilege_roles_keep_only_their_operational_permissions() -> None:
    expected = {
        "cashier": {
            "pos.read",
            "pos.write",
            "pos.void",
            "pos.shift.open",
            "pos.shift.close",
            "tables.read",
            "tables.write",
            "tables.reservations.write",
            "menu.read",
            "gaming.read",
            "kitchen.read",
            "staff.attendance.write",
        },
        "kitchen": {
            "kitchen.read",
            "kitchen.write",
            "menu.read",
            "staff.attendance.write",
        },
        "gaming_supervisor": {
            "pos.read",
            "menu.read",
            "gaming.read",
            "gaming.write",
            "gaming.tournament.manage",
            "staff.attendance.write",
        },
        "staff": {
            "pos.read",
            "pos.write",
            "tables.read",
            "tables.write",
            "tables.reservations.write",
            "menu.read",
            "kitchen.read",
            "staff.attendance.write",
        },
    }
    for role, permissions in expected.items():
        assert ROLE_PERMISSIONS[role] == permissions


def test_low_privilege_roles_never_default_to_sensitive_business_actions() -> None:
    denied = {
        "admin.system",
        "admin.audit.read",
        "staff.write",
        "staff.payroll.write",
        "finance.write",
        "finance.partner.write",
        "finance.assets.write",
        "pos.refund",
        "pos.discount.large",
        "inventory.adjust.large",
    }
    for role in ("cashier", "kitchen", "gaming_supervisor", "staff"):
        assert ROLE_PERMISSIONS[role].isdisjoint(denied), role


def test_staff_role_has_no_inventory_or_insights_reports_access() -> None:
    staff_perms = ROLE_PERMISSIONS["staff"]
    for perm in (
        "inventory.read",
        "inventory.write",
        "inventory.adjust.large",
        "analytics.read",
        "analytics.export",
        "admin.audit.read",
    ):
        assert perm not in staff_perms
    # Service-staff order and table workflows remain available.
    assert "pos.write" in staff_perms
    assert "tables.write" in staff_perms


def test_audit_read_is_protected_owner_only() -> None:
    assert "admin.audit.read" in ROLE_PERMISSIONS["super_owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["auditor"]


def test_auditor_role_is_read_only() -> None:
    auditor_perms = ROLE_PERMISSIONS["auditor"]
    assert auditor_perms == AUDITOR_ACCESS

    # Read (and export) access needed to review finance/ops for an audit.
    for perm in (
        "finance.read",
        "analytics.read",
        "analytics.export",
        "pos.read",
        "inventory.read",
        "staff.read",
        "tables.read",
        "menu.read",
        "gaming.read",
    ):
        assert perm in auditor_perms

    # No write/refund/void/shift/admin permission of any kind survives.
    mutating = {
        "pos.write",
        "pos.void",
        "pos.refund",
        "pos.discount.large",
        "pos.shift.open",
        "pos.shift.close",
        "tables.write",
        "tables.reservations.write",
        "menu.write",
        "inventory.write",
        "inventory.adjust.large",
        "gaming.write",
        "gaming.tournament.manage",
        "kitchen.write",
        "finance.write",
        "finance.partner.write",
        "finance.assets.write",
        "ocr.upload",
        "ocr.verify",
        "staff.write",
        "staff.attendance.write",
        "staff.payroll.write",
        "admin.audit.read",
        "admin.system",
    }
    assert auditor_perms.isdisjoint(mutating)
    # Every permission granted is declared and every mutating one accounted for.
    assert auditor_perms | mutating == set(PERMISSIONS)


async def test_auditor_role_module_override_cannot_grant_write_access() -> None:
    """Enforcement-layer check for the Access Control escalation gap: even if
    role_permission_overrides somehow contains a row granting the 'auditor'
    role the full 'pos' module (which bundles pos.read together with
    pos.write/void/refund/shift-open/close — see MODULE_PERMISSIONS), a
    module override must never let _has_permission resolve true for any
    write-shaped permission in that bundle for the 'auditor' role. Effective
    auditor permissions are always (defaults + overrides) ∩ AUDITOR_ACCESS,
    never a superset of it."""
    auditor = _tenant(protected_access=False, roles=("auditor",))
    with patch(
        "app.core.permissions._module_override",
        new=AsyncMock(return_value=True),
    ):
        for perm in (
            "pos.write",
            "pos.void",
            "pos.refund",
            "pos.discount.large",
            "pos.shift.open",
            "pos.shift.close",
        ):
            assert await _has_permission(None, auditor, perm) is False

        # Read-shaped permission in the very same overridden module still
        # resolves true — the override legitimately grants the module, it's
        # only the write-shaped permissions bundled inside it that get
        # clamped for 'auditor'.
        assert await _has_permission(None, auditor, "pos.read") is True


async def test_module_override_cannot_widen_high_trust_permissions() -> None:
    """A coarse module allow must not turn cashier POS access into refunds or
    turn ordinary staff visibility into role administration."""
    for role, permission in (
        ("cashier", "pos.refund"),
        ("cashier", "pos.discount.large"),
        ("staff", "pos.shift.open"),
        ("kitchen", "staff.write"),
        ("gaming_supervisor", "finance.partner.write"),
    ):
        assert permission in HIGH_TRUST_PERMISSIONS
        tenant = _tenant(protected_access=False, roles=(role,))
        with patch(
            "app.core.permissions._module_override",
            new=AsyncMock(return_value=True),
        ):
            assert await _has_permission(None, tenant, permission) is False


async def test_effective_permissions_are_batched_and_keep_safety_ceilings() -> None:
    staff = _tenant(protected_access=False, roles=("staff",))
    result = MagicMock()
    result.all.return_value = [
        ("staff", "finance", True),
        ("staff", "tables", False),
    ]
    session = AsyncMock()
    session.execute.return_value = result

    granted = await effective_permissions(session, staff)

    assert session.execute.await_count == 1
    assert "finance.read" in granted
    assert "finance.write" not in granted
    assert "finance.partner.write" not in granted
    assert "tables.read" not in granted
    assert "staff.attendance.write" in granted
    assert modules_for_permissions(granted) == sorted(
        {"finance", "kitchen", "menu", "pos", "staff"}
    )


async def test_effective_permissions_keep_admin_access_audit_only() -> None:
    co_owner = _tenant(protected_access=True, audit_access=False, roles=("co_owner",))
    session = AsyncMock()

    granted = await effective_permissions(session, co_owner)

    session.execute.assert_not_awaited()
    assert "finance.write" in granted
    assert "admin.audit.read" not in granted
    assert "admin.system" not in granted


async def test_attendance_roster_accepts_read_or_attendance_permission() -> None:
    dependency = requires_any("staff.read", "staff.attendance.write")
    with patch(
        "app.core.permissions._module_override",
        new=AsyncMock(return_value=None),
    ):
        kitchen = _tenant(protected_access=False, roles=("kitchen",))
        auditor = _tenant(protected_access=False, roles=("auditor",))
        unknown = _tenant(protected_access=False, roles=("unknown",))

        assert await dependency(None, kitchen) is kitchen
        assert await dependency(None, auditor) is auditor
        with pytest.raises(ForbiddenError):
            await dependency(None, unknown)


def test_requires_any_rejects_an_empty_contract() -> None:
    with pytest.raises(ValueError, match="at least one permission"):
        requires_any()


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
    assert await _has_permission(None, co_owner, "admin.system") is False
    # The blanket bypass still applies to operational permissions.
    assert await _has_permission(None, co_owner, "finance.write") is True
    assert await _has_permission(None, co_owner, "staff.write") is True


async def test_gaming_stop_permission_keeps_partner_and_owner_boundaries() -> None:
    """Normal session Stop is a gaming.write operation, not an audit repair.

    A company may explicitly delegate the Gaming module to a partner, while a
    default read-only partner and ordinary service staff remain unable to
    mutate sessions. Owners, co-owners and gaming supervisors keep their
    normal operational access; co-owner access still cannot cross the hard
    audit/system boundary.
    """
    partner = _tenant(protected_access=False, roles=("partner",))
    with patch(
        "app.core.permissions._module_override",
        new=AsyncMock(return_value=None),
    ):
        assert await _has_permission(None, partner, "gaming.write") is False

    with patch(
        "app.core.permissions._module_override",
        new=AsyncMock(return_value=True),
    ):
        assert await _has_permission(None, partner, "gaming.write") is True
        # Module delegation never turns a partner into the protected auditor.
        assert await _has_permission(None, partner, "admin.audit.read") is False
        assert await _has_permission(None, partner, "admin.system") is False

    staff = _tenant(protected_access=False, roles=("staff",))
    owner = _tenant(protected_access=False, roles=("owner",))
    gaming_supervisor = _tenant(protected_access=False, roles=("gaming_supervisor",))
    with patch(
        "app.core.permissions._module_override",
        new=AsyncMock(return_value=None),
    ):
        assert await _has_permission(None, staff, "gaming.write") is False
        assert await _has_permission(None, owner, "gaming.write") is True
        assert await _has_permission(None, gaming_supervisor, "gaming.write") is True

    co_owner = _tenant(protected_access=True, audit_access=False, roles=("co_owner",))
    assert await _has_permission(None, co_owner, "gaming.write") is True
    assert await _has_permission(None, co_owner, "admin.audit.read") is False


async def test_audit_access_grants_admin_audit_read_regardless_of_role() -> None:
    super_owner = _tenant(protected_access=True, audit_access=True, roles=("super_owner",))
    assert await _has_permission(None, super_owner, "admin.audit.read") is True
    assert await _has_permission(None, super_owner, "admin.system") is True


def _tenant(
    protected_access: bool,
    audit_access: bool = False,
    roles: tuple[str, ...] = ("owner",),
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
