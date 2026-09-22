"""Method-specific POS tender validation and receipt response schema."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.api.v1.pos.router import (
    PaymentBundleCreate,
    PaymentBundleLegCreate,
    PaymentCreate,
    PaymentRead,
)


def test_cash_requires_tendered_amount() -> None:
    with pytest.raises(ValidationError, match="cash tendered amount is required"):
        PaymentCreate(method="cash", amount_minor=1_000)


def test_cash_tender_must_cover_bill_and_tip() -> None:
    with pytest.raises(
        ValidationError,
        match="cash tendered amount must cover the payment and tip",
    ):
        PaymentCreate(
            method="cash",
            amount_minor=1_000,
            tip_minor=200,
            tendered_minor=1_199,
        )

    payload = PaymentCreate(
        method="cash",
        amount_minor=1_000,
        tip_minor=200,
        tendered_minor=1_500,
    )
    assert payload.tendered_minor == 1_500


@pytest.mark.parametrize("method", ["card", "upi", "qr", "wallet"])
def test_non_cash_rejects_cash_tender(method: str) -> None:
    with pytest.raises(
        ValidationError,
        match="cash tendered amount is only valid for cash payments",
    ):
        PaymentCreate(method=method, amount_minor=1_000, tendered_minor=1_000)


@pytest.mark.parametrize("method", ["card", "upi", "qr", "wallet"])
def test_non_cash_accepts_no_tender(method: str) -> None:
    payload = PaymentCreate(method=method, amount_minor=1_000)
    assert payload.tendered_minor is None


def test_payment_read_is_receipt_grade_and_json_serializable() -> None:
    paid_at = datetime(2026, 8, 27, 12, 30, tzinfo=UTC)
    response = PaymentRead(
        id=uuid4(),
        order_id=uuid4(),
        shift_id=uuid4(),
        method="cash",
        amount_minor=1_200,
        bill_amount_minor=1_000,
        tip_minor=200,
        tendered_minor=1_500,
        change_minor=300,
        ref_external=None,
        paid_at=paid_at,
        order_status="paid",
        invoice_no="MN/2026-27/000001",
        fiscal_year="2026-27",
        invoice_issued_at=paid_at,
    )

    body = response.model_dump(mode="json")
    assert body["method"] == "cash"
    assert body["amount_minor"] == 1_200
    assert body["bill_amount_minor"] == 1_000
    assert body["tip_minor"] == 200
    assert body["tendered_minor"] == 1_500
    assert body["change_minor"] == 300
    assert body["paid_at"] == paid_at.isoformat().replace("+00:00", "Z")
    assert body["invoice_no"] == "MN/2026-27/000001"


def test_payment_bundle_requires_two_to_five_unique_rails() -> None:
    with pytest.raises(ValidationError, match="at least 2 items"):
        PaymentBundleCreate(payments=[PaymentBundleLegCreate(method="upi", amount_minor=1_000)])

    with pytest.raises(ValidationError, match="payment methods must be unique"):
        PaymentBundleCreate(
            payments=[
                PaymentBundleLegCreate(method="upi", amount_minor=500),
                PaymentBundleLegCreate(method="upi", amount_minor=500),
            ]
        )

    with pytest.raises(ValidationError, match="at most 5 items"):
        PaymentBundleCreate.model_validate(
            {
                "payments": [
                    {"method": method, "amount_minor": 100}
                    for method in ("card", "upi", "qr", "wallet", "cash", "upi")
                ]
            }
        )


def test_payment_bundle_cash_change_and_non_cash_tender_contract() -> None:
    cash = PaymentBundleLegCreate(
        method="cash",
        amount_minor=700,
        tendered_minor=1_000,
    )
    assert cash.tendered_minor - cash.amount_minor == 300

    with pytest.raises(ValidationError, match="cash tendered amount must cover"):
        PaymentBundleLegCreate(
            method="cash",
            amount_minor=700,
            tendered_minor=699,
        )

    with pytest.raises(ValidationError, match="only valid for cash"):
        PaymentBundleLegCreate(
            method="upi",
            amount_minor=700,
            tendered_minor=700,
        )


def test_payment_bundle_rejects_coerced_or_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        PaymentBundleLegCreate.model_validate(
            {"method": "cash", "amount_minor": "700", "tendered_minor": 700}
        )
    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        PaymentBundleCreate.model_validate(
            {
                "payments": [
                    {"method": "upi", "amount_minor": 500},
                    {"method": "card", "amount_minor": 500},
                ],
                "tip_minor": 100,
            }
        )
