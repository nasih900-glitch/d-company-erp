from scripts.seed import (
    BOOTSTRAP_OWNER_ROLE,
    DEFAULT_ROLES,
    DEFAULT_SHOP_NAME,
    DEFAULT_TERMINALS,
)


def test_clean_bootstrap_has_one_shop_and_two_operational_terminals() -> None:
    assert DEFAULT_SHOP_NAME == "Main Shop"
    assert DEFAULT_TERMINALS == (
        ("Cafe POS", "cafe_pos", "seed-terminal-1"),
        ("Gaming Area", "gaming", None),
    )


def test_clean_bootstrap_owner_has_protected_audit_authority() -> None:
    role_codes = {code for code, _ in DEFAULT_ROLES}
    assert BOOTSTRAP_OWNER_ROLE == "super_owner"
    assert BOOTSTRAP_OWNER_ROLE in role_codes
