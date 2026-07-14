from uuid import uuid4

import pytest

from app.core.errors import AuthError
from app.core.permissions import PERMISSIONS, ROLE_PERMISSIONS
from app.core.pricing_lock import require_pricing_unlock
from app.core.security import issue_pricing_token
from app.core.tenant import TenantContext


def test_super_owner_has_all_permissions() -> None:
    assert ROLE_PERMISSIONS["super_owner"] == set(PERMISSIONS.keys())


def test_every_legacy_title_has_every_non_audit_permission() -> None:
    # "staff" is the one deliberate exception — see test below.
    expected = set(PERMISSIONS) - {"admin.audit.read"}
    for role in ROLE_PERMISSIONS.keys() - {"super_owner", "staff"}:
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
    assert "pos.write" in ROLE_PERMISSIONS["auditor"]


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


def _tenant(protected_access: bool) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=None,
        terminal_id=None,
        roles=("owner",),
        protected_access=protected_access,
    )
