"""Narrow company/branch scope for protected cross-terminal shift recovery."""

from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.api.v1.pos.router import (
    _is_new_protocol_android_shift,
    _require_recovery_shift_scope,
    _shift_read_installation_id,
)
from app.core.errors import BusinessRuleError, NotFoundError
from app.core.tenant import TenantContext


def _tenant(*, company_id=None, branch_id=None, terminal_id=None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=company_id or uuid4(),
        branch_id=branch_id,
        terminal_id=terminal_id,
        roles=("super_owner",),
        protected_access=True,
        audit_access=True,
    )


def test_recovery_scope_allows_other_terminal_only_within_company_and_branch() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    actor_terminal_id = uuid4()
    origin_terminal_id = uuid4()
    tenant = _tenant(
        company_id=company_id,
        branch_id=branch_id,
        terminal_id=actor_terminal_id,
    )
    shift = SimpleNamespace(
        company_id=company_id,
        branch_id=branch_id,
        terminal_id=origin_terminal_id,
    )

    assert _require_recovery_shift_scope(shift, tenant=tenant) is shift

    with pytest.raises(BusinessRuleError, match="different branch"):
        _require_recovery_shift_scope(
            SimpleNamespace(
                company_id=company_id,
                branch_id=uuid4(),
                terminal_id=origin_terminal_id,
            ),
            tenant=tenant,
        )
    with pytest.raises(NotFoundError, match="this company"):
        _require_recovery_shift_scope(
            SimpleNamespace(
                company_id=uuid4(),
                branch_id=branch_id,
                terminal_id=origin_terminal_id,
            ),
            tenant=tenant,
        )


def test_recovery_eligibility_is_exactly_new_protocol_android_origin() -> None:
    assert _is_new_protocol_android_shift(
        SimpleNamespace(opening_protocol_revision=1, opening_client_platform="android")
    )
    assert not _is_new_protocol_android_shift(
        SimpleNamespace(opening_protocol_revision=None, opening_client_platform="android")
    )
    assert not _is_new_protocol_android_shift(
        SimpleNamespace(opening_protocol_revision=1, opening_client_platform="web")
    )


def test_shift_read_echoes_installation_only_to_exact_android_installation() -> None:
    installation_id = uuid4()
    shift = SimpleNamespace(opening_client_installation_id=installation_id)

    assert _shift_read_installation_id(shift, None) is None
    assert (
        _shift_read_installation_id(
            shift,
            SimpleNamespace(
                headers={
                    "X-Client-Platform": "web",
                    "X-Installation-Id": str(installation_id),
                }
            ),
        )
        is None
    )
    assert (
        _shift_read_installation_id(
            shift,
            SimpleNamespace(
                headers={
                    "X-Client-Platform": "android",
                    "X-Installation-Id": str(uuid4()),
                }
            ),
        )
        is None
    )
    assert _shift_read_installation_id(
        shift,
        SimpleNamespace(
            headers={
                "X-Client-Platform": "android",
                "X-Installation-Id": str(installation_id),
            }
        ),
    ) == installation_id
