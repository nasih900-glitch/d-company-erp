"""Fail-closed authority and attestation contracts, without a database."""

from uuid import uuid4
import pytest
from pydantic import ValidationError
from app.api.v1.pos.router import (
    RetainedMistakeCorrectionRequest,
    _require_retained_correction_owner,
)
from app.core.errors import BusinessRuleError, ForbiddenError
from app.core.tenant import TenantContext


def tenant(*, roles=("owner",), branch=True, terminal=True):
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4() if branch else None,
        terminal_id=uuid4() if terminal else None,
        roles=roles,
    )


@pytest.mark.parametrize("roles", [("manager",), ("cashier",), ("gaming_supervisor",), ()])
def test_nonowner_cannot_correct_even_with_operational_permissions(roles):
    with pytest.raises(ForbiddenError):
        _require_retained_correction_owner(tenant(roles=roles))


@pytest.mark.parametrize("roles", [("owner",), ("super_owner",), ("co_owner",)])
def test_normalized_owner_identity_supported(roles):
    _require_retained_correction_owner(tenant(roles=roles))


@pytest.mark.parametrize("missing", ["branch", "terminal"])
def test_scope_is_required(missing):
    with pytest.raises(BusinessRuleError):
        _require_retained_correction_owner(tenant(**{missing: False}))


def payload():
    return dict(
        original_receipt_id=uuid4(),
        expected_checkout_version=1,
        review_hash="a" * 64,
        all_items_mistaken_not_supplied=True,
        reason="Entered by mistake; nothing supplied",
    )


@pytest.mark.parametrize(
    "field,value",
    [
        ("all_items_mistaken_not_supplied", False),
        ("all_items_mistaken_not_supplied", "true"),
        ("all_items_mistaken_not_supplied", 1),
        ("reason", "  "),
        ("reason", "x"),
        ("review_hash", "not-a-review"),
        ("expected_checkout_version", 0),
    ],
)
def test_invalid_correction_identity_is_rejected(field, value):
    with pytest.raises(ValidationError):
        RetainedMistakeCorrectionRequest(**{**payload(), field: value})


def test_reason_is_trimmed_and_attestation_cannot_be_omitted():
    request = RetainedMistakeCorrectionRequest(**{**payload(), "reason": "  Entered by mistake  "})
    assert request.reason == "Entered by mistake"
    data = payload()
    data.pop("all_items_mistaken_not_supplied")
    with pytest.raises(ValidationError):
        RetainedMistakeCorrectionRequest(**data)
