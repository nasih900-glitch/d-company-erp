import pytest

from scripts.seed import (
    BOOTSTRAP_OWNER_ROLE,
    DEFAULT_GST_REGISTRATION_TYPE,
    DEFAULT_ROLES,
    DEFAULT_SHOP_NAME,
    DEFAULT_TERMINALS,
    _seed_owner_email,
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


def test_production_bootstrap_uses_normalized_configured_owner_email(monkeypatch) -> None:
    monkeypatch.setenv("ENV", "prod")
    monkeypatch.setenv("SEED_OWNER_EMAIL", "owner@erp.example.com")
    assert _seed_owner_email() == "owner@erp.example.com"

    monkeypatch.setenv("SEED_OWNER_EMAIL", " Owner@erp.example.com ")
    with pytest.raises(RuntimeError, match="lowercase"):
        _seed_owner_email()

    monkeypatch.setenv("SEED_OWNER_EMAIL", "owner@dcompany.local")
    with pytest.raises(RuntimeError, match="real production login domain"):
        _seed_owner_email()
