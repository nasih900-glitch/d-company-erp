from uuid import uuid4

import pytest

from app.core.errors import AuthError, ForbiddenError
from app.core.permissions import PERMISSIONS, ROLE_PERMISSIONS
from app.core.pricing_lock import require_pricing_unlock
from app.core.security import issue_pricing_token
from app.core.tenant import TenantContext


def test_super_owner_has_all_permissions() -> None:
    assert ROLE_PERMISSIONS["super_owner"] == set(PERMISSIONS.keys())


def test_owner_has_business_access_but_not_protected_system_controls() -> None:
    assert "pos.write" in ROLE_PERMISSIONS["owner"]
    assert "finance.read" in ROLE_PERMISSIONS["owner"]
    assert "inventory.read" in ROLE_PERMISSIONS["owner"]
    assert "inventory.write" not in ROLE_PERMISSIONS["owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["owner"]
    assert "admin.system" not in ROLE_PERMISSIONS["owner"]
    assert "staff.write" not in ROLE_PERMISSIONS["owner"]


def test_cashier_cannot_refund() -> None:
    assert "pos.refund" not in ROLE_PERMISSIONS["cashier"]
    assert "pos.refund" in ROLE_PERMISSIONS["manager"]


def test_kitchen_is_read_only_for_pos() -> None:
    perms = ROLE_PERMISSIONS["kitchen"]
    assert "pos.read" in perms
    assert "pos.write" not in perms


def test_audit_read_is_owner_only() -> None:
    assert "admin.audit.read" in ROLE_PERMISSIONS["super_owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["owner"]
    assert "admin.audit.read" not in ROLE_PERMISSIONS["auditor"]
    assert "pos.write" not in ROLE_PERMISSIONS["auditor"]


def test_inventory_control_is_owner_only() -> None:
    assert "inventory.write" in ROLE_PERMISSIONS["super_owner"]
    assert "inventory.adjust.large" in ROLE_PERMISSIONS["super_owner"]
    assert "inventory.write" not in ROLE_PERMISSIONS["owner"]
    assert "inventory.adjust.large" not in ROLE_PERMISSIONS["owner"]
    assert "inventory.read" in ROLE_PERMISSIONS["manager"]
    assert "inventory.write" not in ROLE_PERMISSIONS["manager"]
    assert "inventory.adjust.large" not in ROLE_PERMISSIONS["manager"]


def test_pricing_control_requires_protected_access_and_unlock_token() -> None:
    normal_owner = _tenant(protected_access=False)
    protected_owner = _tenant(protected_access=True)
    token = issue_pricing_token(
        user_id=protected_owner.user_id,
        company_id=protected_owner.company_id,
    )

    with pytest.raises(ForbiddenError):
        require_pricing_unlock(token, normal_owner)
    with pytest.raises(AuthError):
        require_pricing_unlock(None, protected_owner)
    require_pricing_unlock(token, protected_owner)


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
