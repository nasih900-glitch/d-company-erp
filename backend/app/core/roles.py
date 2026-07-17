"""Role helpers for separating internal access from user-facing labels."""

from __future__ import annotations

from collections.abc import Iterable

PROTECTED_OWNER_ROLE = "super_owner"
# A second, narrower owner tier: bypasses the same operational RBAC gates as
# super_owner (shift-opener-only billing, force-stop, membership overrides —
# see has_full_access), but deliberately does NOT get admin.audit.read or the
# Access Control panel (see permissions.py's _has_permission). For co-owners
# who should never be blocked from billing on a colleague's device/shift but
# aren't the one designated to read the audit trail.
CO_OWNER_ROLE = "co_owner"
PUBLIC_OWNER_ROLE = "owner"

FULL_ACCESS_ROLES = {PROTECTED_OWNER_ROLE, CO_OWNER_ROLE}


def has_protected_owner_access(roles: Iterable[str]) -> bool:
    return PROTECTED_OWNER_ROLE in set(roles)


def has_full_access(roles: Iterable[str]) -> bool:
    """super_owner OR co_owner. Broader than has_protected_owner_access on
    purpose — see CO_OWNER_ROLE above. Never use this to gate admin.audit.read."""
    return bool(FULL_ACCESS_ROLES & set(roles))


def public_roles(roles: Iterable[str]) -> list[str]:
    """Return roles safe to display through APIs/UI.

    Both internal owner tiers are permission guards, not job titles. Users
    should only ever see "owner" in the ERP while the backend still enforces
    the finer-grained access underneath.
    """
    out: list[str] = []
    for role in roles:
        public = PUBLIC_OWNER_ROLE if role in FULL_ACCESS_ROLES else role
        if public not in out:
            out.append(public)
    return out
