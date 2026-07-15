from scripts.ensure_service_catalog import MENU_SEED


def test_manual_session_items_are_hidden_from_normal_pos() -> None:
    assert MENU_SEED
    assert {row["sku"] for row in MENU_SEED} == {
        "GAM-PS5-15",
        "GAM-VR-15",
        "GAM-SIM-15",
        "SHI-SESSION",
        "STR-BOOTH-15",
    }
    assert all(row["is_available"] is False for row in MENU_SEED)
