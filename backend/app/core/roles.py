"""Role helpers for separating internal access from user-facing labels."""

from __future__ import annotations

from collections.abc import Iterable

PROTECTED_OWNER_ROLE = "super_owner"
PUBLIC_OWNER_ROLE = "owner"


def has_protected_owner_access(roles: Iterable[str]) -> bool:
    return PROTECTED_OWNER_ROLE in set(roles)


def public_roles(roles: Iterable[str]) -> list[str]:
    """Return roles safe to display through APIs/UI.

    The protected owner role is an internal permission guard. Users should only
    see "owner" in the ERP while the backend still enforces protected access.
    """
    out: list[str] = []
    for role in roles:
        public = PUBLIC_OWNER_ROLE if role == PROTECTED_OWNER_ROLE else role
        if public not in out:
            out.append(public)
    return out
