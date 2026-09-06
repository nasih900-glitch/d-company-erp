from __future__ import annotations

from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError

from app.core.config import Settings
from app.core.release_control import has_release_control_access
from app.core.tenant import TenantContext


def _tenant(
    *,
    company_id: UUID,
    user_id: UUID,
    audit_access: bool,
) -> TenantContext:
    return TenantContext(
        user_id=user_id,
        company_id=company_id,
        branch_id=None,
        terminal_id=None,
        roles=("owner",),
        protected_access=audit_access,
        audit_access=audit_access,
    )


def test_release_controller_bindings_are_canonical_and_exact() -> None:
    company_id = uuid4()
    user_id = uuid4()
    settings = Settings(
        android_release_controller_bindings=(
            f"  {str(company_id).upper()} : {str(user_id).upper()}  "
        )
    )

    assert settings.android_release_controller_bindings == f"{company_id}:{user_id}"
    assert settings.android_release_controller_binding_set == frozenset(
        {(company_id, user_id)}
    )
    assert has_release_control_access(
        _tenant(company_id=company_id, user_id=user_id, audit_access=True),
        settings=settings,
    )
    assert not has_release_control_access(
        _tenant(company_id=uuid4(), user_id=user_id, audit_access=True),
        settings=settings,
    )
    assert not has_release_control_access(
        _tenant(company_id=company_id, user_id=uuid4(), audit_access=True),
        settings=settings,
    )
    assert not has_release_control_access(
        _tenant(company_id=company_id, user_id=user_id, audit_access=False),
        settings=settings,
    )


def test_empty_release_controller_binding_is_fail_closed() -> None:
    settings = Settings(android_release_controller_bindings="")
    tenant = _tenant(company_id=uuid4(), user_id=uuid4(), audit_access=True)

    assert settings.android_release_controller_binding_set == frozenset()
    assert not has_release_control_access(tenant, settings=settings)


@pytest.mark.parametrize(
    "value",
    [
        "not-a-binding",
        f"{uuid4()}:not-a-user",
        f"{uuid4().hex}:{uuid4()}",
        f"{{{uuid4()}}}:{uuid4()}",
        f"{uuid4()}:{uuid4()}:",
        f"{uuid4()}:",
    ],
)
def test_malformed_release_controller_binding_fails_startup(value: str) -> None:
    with pytest.raises(ValidationError, match="android_release_controller_bindings"):
        Settings(android_release_controller_bindings=value)


def test_duplicate_release_controller_binding_fails_startup() -> None:
    binding = f"{uuid4()}:{uuid4()}"
    with pytest.raises(ValidationError, match="duplicate company:user binding"):
        Settings(android_release_controller_bindings=f"{binding},{binding.upper()}")
