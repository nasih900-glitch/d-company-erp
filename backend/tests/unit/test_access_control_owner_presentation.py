"""The matrix must describe existing operational bypass authority accurately."""

from uuid import uuid4

import pytest

from app.api.v1.admin.router import AccessControlUpdate, _access_cell, update_access_control
from app.core.errors import BusinessRuleError
from app.core.permissions import MODULE_PERMISSIONS
from app.core.tenant import TenantContext


@pytest.mark.parametrize("module", sorted(MODULE_PERMISSIONS))
@pytest.mark.parametrize("override", [None, False, True])
def test_operational_owner_matrix_matches_existing_bypass(module: str, override: bool | None) -> None:
    cell = _access_cell(role_code="co_owner", module=module, override=override)
    assert cell.allowed is True
    assert cell.access_level == cell.default_access_level == "full"
    assert cell.effective_permissions == sorted(MODULE_PERMISSIONS[module])
    assert cell.unavailable_permissions == cell.ceiling_limited_permissions == []
    assert cell.configurable is False


def test_coarse_modules_never_include_protected_only_actions() -> None:
    assert not set.union(*MODULE_PERMISSIONS.values()) & {
        "admin.audit.read", "admin.system", "finance.tender.correct",
    }


def test_standard_owner_remains_subject_to_configurable_module_access() -> None:
    cell = _access_cell(role_code="owner", module="finance", override=False)
    assert cell.access_level == "blocked"
    assert cell.configurable is True


@pytest.mark.asyncio
@pytest.mark.parametrize("allowed", [None, False, True])
async def test_ineffective_operational_owner_override_is_rejected_before_db_write(allowed: bool | None) -> None:
    tenant = TenantContext(
        user_id=uuid4(), company_id=uuid4(), branch_id=None, terminal_id=None,
        roles=("owner",), protected_access=True, audit_access=True,
    )
    with pytest.raises(BusinessRuleError, match="operational override owner"):
        await update_access_control(
            AccessControlUpdate(role_code="co_owner", module="finance", allowed=allowed),
            None, tenant,
        )
