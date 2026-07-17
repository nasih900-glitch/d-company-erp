from app.core.roles import has_full_access, has_protected_owner_access, public_roles


def test_public_roles_mask_protected_owner_access() -> None:
    assert public_roles(["super_owner"]) == ["owner"]


def test_public_roles_deduplicates_owner_after_masking() -> None:
    assert public_roles(["super_owner", "owner", "finance"]) == ["owner", "finance"]


def test_protected_owner_access_detection() -> None:
    assert has_protected_owner_access(["super_owner"])
    assert not has_protected_owner_access(["owner"])


def test_co_owner_is_not_protected_owner() -> None:
    # co_owner bypasses operational RBAC (has_full_access) but must never be
    # mistaken for the audit-log-privileged tier.
    assert not has_protected_owner_access(["co_owner"])


def test_has_full_access_covers_both_owner_tiers() -> None:
    assert has_full_access(["super_owner"])
    assert has_full_access(["co_owner"])
    assert not has_full_access(["owner"])
    assert not has_full_access(["staff"])


def test_public_roles_masks_co_owner_too() -> None:
    assert public_roles(["co_owner"]) == ["owner"]
    assert public_roles(["co_owner", "owner"]) == ["owner"]
