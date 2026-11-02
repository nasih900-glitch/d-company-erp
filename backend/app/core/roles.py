"""Role helpers for separating internal access from user-facing labels."""

from __future__ import annotations

from collections.abc import Iterable

PROTECTED_OWNER_ROLE = "super_owner"
# A second, narrower owner tier: bypasses the same operational RBAC gates as
# super_owner (protected recovery, force-stop, membership/refund overrides —
# see has_full_access), but deliberately does NOT get admin.audit.read or the
# Access Control panel (see permissions.py's _has_permission). Routine shift,
# Gaming and POS work instead follows its explicit action permission and keeps
# actor attribution; it does not require an owner bypass.
CO_OWNER_ROLE = "co_owner"
PUBLIC_OWNER_ROLE = "owner"

FULL_ACCESS_ROLES = {PROTECTED_OWNER_ROLE, CO_OWNER_ROLE}
# Every role presented to staff as an owner title is security-sensitive even
# though only the two internal tiers receive an operational bypass.  A manager
# must never be able to mint, demote, suspend, or delete an owner account.
OWNER_ROLE_CODES = FULL_ACCESS_ROLES | {PUBLIC_OWNER_ROLE}


def role_name(code: str) -> str:
    if code == "gaming_cashier":
        return "Gaming & POS staff"
    return code.title().replace("_", " ")


def has_owner_role(roles: Iterable[str]) -> bool:
    """Whether the public tenant identity carries any owner role.

    TenantContext publishes both internal owner tiers as ``owner``.  Accepting
    the complete set here also keeps this helper safe for internal callers
    that still hold the unredacted role codes.  This is deliberately separate
    from protected/audit access: routine, accountable owner operations must
    not require the super-owner recovery privilege.
    """
    return bool(OWNER_ROLE_CODES & set(roles))


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
