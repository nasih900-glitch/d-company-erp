from app.core.roles import has_protected_owner_access, public_roles


def test_public_roles_mask_protected_owner_access() -> None:
    assert public_roles(["super_owner"]) == ["owner"]


def test_public_roles_deduplicates_owner_after_masking() -> None:
    assert public_roles(["super_owner", "owner", "finance"]) == ["owner", "finance"]


def test_protected_owner_access_detection() -> None:
    assert has_protected_owner_access(["super_owner"])
    assert not has_protected_owner_access(["owner"])
