"""Fail-closed authority for the global Android release registry."""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends

from app.core.config import Settings, get_settings
from app.core.errors import ForbiddenError
from app.core.permissions import requires
from app.core.tenant import TenantContext


def has_release_control_access(
    tenant: TenantContext,
    *,
    settings: Settings | None = None,
) -> bool:
    """Return whether this exact protected owner controls global releases.

    ``admin.system`` and ``audit_access`` are tenant-local. Android releases
    are global and pre-login, so a role in some other tenant must never grant
    access to their registry. Operations explicitly binds immutable company
    and user UUIDs; an empty binding set denies everyone.
    """
    configured = settings or get_settings()
    return bool(
        tenant.audit_access
        and (tenant.company_id, tenant.user_id)
        in configured.android_release_controller_binding_set
    )


async def require_release_control_access(
    tenant: Annotated[TenantContext, Depends(requires("admin.system"))],
) -> TenantContext:
    """Require both protected-owner authority and the global identity binding."""
    if not has_release_control_access(tenant):
        raise ForbiddenError("Android release control is not assigned to this account.")
    return tenant


ReleaseControllerDep = Annotated[TenantContext, Depends(require_release_control_access)]
