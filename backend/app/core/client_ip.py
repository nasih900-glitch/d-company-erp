"""Trusted HTTP client metadata for security and audit records."""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from fastapi import Request

_AUDIT_IP_MAX_LENGTH = 45
_AUDIT_USER_AGENT_MAX_LENGTH = 500


def trusted_client_ip(request: Request) -> str | None:
    """Return the socket/proxy-middleware-resolved client address.

    Application code must not parse ``X-Forwarded-For`` itself. Uvicorn may
    replace ``request.client`` only when the immediate peer is configured as a
    trusted proxy; the production backend therefore remains network-internal
    behind Caddy, which overwrites forwarding headers.
    """
    if request.client is None:
        return None
    host = request.client.host.strip()
    return host[:_AUDIT_IP_MAX_LENGTH] or None


def audit_user_agent(request: Request) -> str | None:
    """Return user-agent evidence bounded to the audit schema."""
    value = (request.headers.get("user-agent") or "").strip()
    return value[:_AUDIT_USER_AGENT_MAX_LENGTH] or None
