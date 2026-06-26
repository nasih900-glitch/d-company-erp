"""Password re-check guard for sensitive pricing changes."""

from __future__ import annotations

from app.core.errors import AuthError, ForbiddenError
from app.core.security import decode_token
from app.core.tenant import TenantContext


def require_pricing_unlock(x_pricing_token: str | None, tenant: TenantContext) -> None:
    if not tenant.protected_access:
        raise ForbiddenError("pricing setup is restricted")
    if not x_pricing_token:
        raise AuthError("pricing password unlock required")
    try:
        claims = decode_token(x_pricing_token)
    except ValueError as exc:
        raise AuthError("pricing password unlock expired or invalid") from exc
    if (
        claims.get("type") != "pricing"
        or claims.get("scope") != "admin.pricing.write"
        or claims.get("sub") != str(tenant.user_id)
        or claims.get("company_id") != str(tenant.company_id)
    ):
        raise AuthError("pricing password unlock expired or invalid")
