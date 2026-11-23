"""Owner correction remains outside ordinary POS/module grants."""

import pytest

from app.core.permissions import HIGH_TRUST_PERMISSIONS, OWNER_ACCESS, _role_allows_permission


@pytest.mark.parametrize(
    "role", ["gaming_cashier", "cashier", "manager", "staff", "auditor", "kitchen"]
)
def test_operator_recovery_cannot_be_added_by_a_pos_module_override(role):
    assert not _role_allows_permission(
        role=role, perm="pos.shift.handover.recover", module="pos", override=True
    )


def test_operator_recovery_is_owner_high_trust_and_has_no_cash_privilege():
    permission = "pos.shift.handover.recover"
    assert permission in OWNER_ACCESS
    assert permission in HIGH_TRUST_PERMISSIONS
    assert _role_allows_permission(role="owner", perm=permission, module="pos", override=None)
