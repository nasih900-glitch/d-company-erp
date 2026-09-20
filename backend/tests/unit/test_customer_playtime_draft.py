"""Pure contract tests for the disabled customer playtime proposal."""

from __future__ import annotations

from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.api.v1.customers.router import PlaytimeProgramUpdate
from app.services.customers.identity import normalize_indian_phone
from app.services.customers.playtime import PlaytimeProgram, _qualification_status, mask_phone


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("9876543210", "9876543210"),
        ("91 98765 43210", "9876543210"),
        ("+91 (98765) 43210", "9876543210"),
        ("+44 7700 900123", None),
        ("98765ABC43210", None),
        ("", None),
    ],
)
def test_indian_phone_normalization_is_narrow(raw: str, expected: str | None) -> None:
    assert normalize_indian_phone(raw) == expected


def test_draft_estimate_boundaries_do_not_create_a_balance() -> None:
    program = PlaytimeProgram(threshold_paid_minutes=600, reward_minutes=60)
    assert program.estimate(599) == 0
    assert program.estimate(600) == 60
    assert program.estimate(1200) == 120


def test_program_update_forbids_activation_and_messaging_fields() -> None:
    with pytest.raises(ValidationError):
        PlaytimeProgramUpdate.model_validate(
            {
                "threshold_paid_minutes": 600,
                "reward_minutes": 60,
                "rewards_enabled": True,
            }
        )
    with pytest.raises(ValidationError):
        PlaytimeProgramUpdate.model_validate(
            {
                "threshold_paid_minutes": 600,
                "reward_minutes": 60,
                "messaging_enabled": True,
            }
        )


def test_leaderboard_phone_is_masked() -> None:
    assert mask_phone("9876543210").endswith("3210")
    assert "987654" not in mask_phone("9876543210")


def _qualification_row(**overrides):
    values = {
        "session_customer_id": "customer-1",
        "order_customer_id": "customer-1",
        "refunded_minor": 0,
        "order_status": "paid",
        "paid_minor": 1_000,
        "gaming_amount_minor": 1_000,
        "billing_mode": "hourly",
        "package_cap_minutes": 0,
        "order_discount_minor": 0,
        "line_flag": 0,
        "benefit_order_id": None,
        "qualifying_minutes": 60,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def test_qualification_reasons_match_conservative_numeric_conditions() -> None:
    assert _qualification_status(_qualification_row()) == "eligible"
    assert _qualification_status(
        _qualification_row(billing_mode="legacy_ambiguous", qualifying_minutes=0)
    ) == "legacy_billing_unverified"
    assert _qualification_status(
        _qualification_row(billing_mode="package", package_cap_minutes=0, qualifying_minutes=0)
    ) == "unproven_package_duration"
    assert _qualification_status(
        _qualification_row(benefit_order_id="consumed-reservation", qualifying_minutes=0)
    ) == "discounted_or_free"
    # Released or otherwise unconsumed reservations never enter the aggregate,
    # so their projected benefit id is null and the paid session remains valid.
    assert _qualification_status(_qualification_row(benefit_order_id=None)) == "eligible"
    assert _qualification_status(
        _qualification_row(order_customer_id="customer-2", qualifying_minutes=0)
    ) == "customer_mismatch"
