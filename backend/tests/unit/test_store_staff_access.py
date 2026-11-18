from unittest.mock import AsyncMock, patch
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.api.v1.gaming import router as gaming
from app.api.v1.gaming.router import SessionCancel, _void_reason_policy
from app.api.v1.pos import router as pos
from app.core.errors import BusinessRuleError, ForbiddenError
from app.core.permissions import (
    GAMING_CASHIER_ACCESS, MODULE_PERMISSIONS, PERMISSIONS, ROLE_PERMISSIONS,
    STORE_STAFF_ACCESS, _has_permission, _role_allows_permission,
)
from app.core.roles import has_owner_role, role_name
from app.core.tenant import TenantContext
from scripts.seed import DEFAULT_ROLES


def tenant() -> TenantContext:
    return TenantContext(user_id=uuid4(), company_id=uuid4(), branch_id=uuid4(), terminal_id=uuid4(), roles=("store_staff",))


def test_store_staff_is_additive_and_legacy_defaults_are_unchanged() -> None:
    assert STORE_STAFF_ACCESS == (GAMING_CASHIER_ACCESS - {"pos.void"}) | {"pos.discount.large"}
    assert ROLE_PERMISSIONS["gaming_cashier"] == GAMING_CASHIER_ACCESS
    assert ROLE_PERMISSIONS["pos_discount"] == {"pos.discount.large"}
    assert ROLE_PERMISSIONS["store_staff"] == STORE_STAFF_ACCESS
    assert "store_staff" in dict(DEFAULT_ROLES)
    assert role_name("store_staff") == "Store Staff"


@pytest.mark.parametrize("override", [None, True])
def test_allow_overrides_cannot_expand_store_staff_authority(override: bool | None) -> None:
    for permission in PERMISSIONS:
        module = next((name for name, values in MODULE_PERMISSIONS.items() if permission in values), None)
        assert _role_allows_permission(role="store_staff", perm=permission, module=module, override=override) is (permission in STORE_STAFF_ACCESS)


@pytest.mark.asyncio
async def test_actual_permission_guard_keeps_discount_and_gaming_but_denies_protected_actions() -> None:
    with patch("app.core.permissions._module_override", AsyncMock(return_value=True)):
        for permission in PERMISSIONS:
            assert await _has_permission(None, tenant(), permission) is (permission in STORE_STAFF_ACCESS)
    assert not has_owner_role(["store_staff"])


@pytest.mark.asyncio
async def test_nonzero_pos_discount_passes_real_authorization_before_reservation() -> None:
    class ReservationReached(Exception):
        pass

    with patch("app.core.permissions._module_override", AsyncMock(return_value=None)), \
         patch.object(pos, "_require_idempotency", return_value=("synthetic-key", "synthetic-hash")), \
         patch.object(pos, "check_or_reserve", AsyncMock(side_effect=ReservationReached)) as reserve:
        with pytest.raises(ReservationReached):
            await pos.apply_order_discount(uuid4(), pos.OrderDiscountUpdate(manual_discount_minor=1), None, None, tenant())
        reserve.assert_awaited_once()


def test_store_staff_gaming_void_reason_boundary_is_preserved_without_pos_void_authority() -> None:
    assert "gaming.write" in STORE_STAFF_ACCESS
    assert "pos.void" not in STORE_STAFF_ACCESS
    assert _void_reason_policy(SessionCancel(), elapsed_ms=1_799_999)[1] is False
    for elapsed in [1_800_000, 1_800_001]:
        with pytest.raises(BusinessRuleError, match="30 minutes"):
            _void_reason_policy(SessionCancel(), elapsed_ms=elapsed)
        assert _void_reason_policy(SessionCancel(reason="Wrong session"), elapsed_ms=elapsed) == ("Wrong session", True)


@pytest.mark.asyncio
async def test_store_staff_cannot_void_a_gaming_charge_already_linked_to_pos() -> None:
    actor = tenant()
    linked_order_id = uuid4()
    gs = SimpleNamespace(id=uuid4(), company_id=actor.company_id, station_id=uuid4(), order_id=linked_order_id)
    session = SimpleNamespace(
        execute=AsyncMock(return_value=SimpleNamespace(scalar_one_or_none=lambda: gs)),
        get=AsyncMock(return_value=SimpleNamespace(company_id=actor.company_id)),
    )
    with patch.object(gaming, "_require_idempotency", return_value=("synthetic-key", "synthetic-hash")), \
         patch.object(gaming, "check_or_reserve", AsyncMock(return_value=None)):
        with pytest.raises(ForbiddenError, match="Only an Owner"):
            await gaming.cancel_session(gs.id, SessionCancel(expected_order_id=linked_order_id), session, None, actor)
