from scripts.seed import (
    BOOTSTRAP_OWNER_ROLE,
    DEFAULT_GST_REGISTRATION_TYPE,
    DEFAULT_ROLES,
    DEFAULT_SHOP_NAME,
    DEFAULT_TERMINALS,
)


def test_clean_bootstrap_has_one_shop_and_one_hybrid_workspace() -> None:
    assert DEFAULT_SHOP_NAME == "Main Shop"
    assert DEFAULT_TERMINALS == (("Main Workspace", "hybrid", "seed-terminal-1"),)


def test_clean_bootstrap_owner_has_protected_audit_authority() -> None:
    role_codes = {code for code, _ in DEFAULT_ROLES}
    assert BOOTSTRAP_OWNER_ROLE == "super_owner"
    assert BOOTSTRAP_OWNER_ROLE in role_codes


def test_clean_bootstrap_is_explicitly_gst_unregistered() -> None:
    assert DEFAULT_GST_REGISTRATION_TYPE == "unregistered"
