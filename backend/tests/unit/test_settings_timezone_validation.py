"""A non-IANA timezone blanks the POS webview mid-shift — reject it at the API."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.api.v1.settings.router import BranchCreate, BranchUpdate, CompanyUpdate

VALID = ["Asia/Kolkata", "UTC", "Europe/London", "America/Los_Angeles"]
INVALID = ["IST", "GMT+5:30", "asia/kolkata", "   ", "Mars/Phobos"]


@pytest.mark.parametrize("value", VALID)
def test_company_update_accepts_iana_timezones(value: str) -> None:
    assert CompanyUpdate.model_validate({"timezone": value}).timezone == value


@pytest.mark.parametrize("value", INVALID)
def test_company_update_rejects_non_iana_timezones(value: str) -> None:
    with pytest.raises(ValidationError, match="valid IANA name"):
        CompanyUpdate.model_validate({"timezone": value})


@pytest.mark.parametrize("value", VALID)
def test_branch_schemas_accept_iana_timezones(value: str) -> None:
    assert BranchCreate.model_validate({"name": "Nilambur", "timezone": value}).timezone == value
    assert BranchUpdate.model_validate({"timezone": value}).timezone == value


@pytest.mark.parametrize("value", INVALID)
def test_branch_schemas_reject_non_iana_timezones(value: str) -> None:
    with pytest.raises(ValidationError, match="valid IANA name"):
        BranchCreate.model_validate({"name": "Nilambur", "timezone": value})
    with pytest.raises(ValidationError, match="valid IANA name"):
        BranchUpdate.model_validate({"timezone": value})


def test_surrounding_whitespace_is_trimmed_rather_than_rejected() -> None:
    assert CompanyUpdate.model_validate({"timezone": " Asia/Kolkata "}).timezone == "Asia/Kolkata"
    assert BranchUpdate.model_validate({"timezone": " Asia/Kolkata "}).timezone == "Asia/Kolkata"


def test_omitting_the_timezone_leaves_the_stored_value_untouched() -> None:
    assert CompanyUpdate.model_validate({"name": "D Company"}).timezone is None
    assert BranchUpdate.model_validate({"name": "Nilambur"}).timezone is None
    # The branch default must itself survive the validator.
    assert BranchCreate.model_validate({"name": "Nilambur"}).timezone == "Asia/Kolkata"
