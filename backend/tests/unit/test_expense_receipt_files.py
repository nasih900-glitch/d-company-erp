"""Database-free receipt file and private-response boundary tests."""

from __future__ import annotations

from io import BytesIO
from types import SimpleNamespace

import pytest
from PIL import Image

from app.api.v1.finance.router import (
    ExpenseReceiptRead,
    _detect_expense_receipt_content_type,
    _private_expense_receipt_response,
    _safe_expense_receipt_filename,
)
from app.core.errors import BusinessRuleError


def _image(image_format: str) -> bytes:
    output = BytesIO()
    Image.new("RGB", (8, 6), (210, 165, 45)).save(output, format=image_format)
    return output.getvalue()


def _isobmff(major_brand: bytes, *compatible_brands: bytes) -> bytes:
    compatible = b"".join(compatible_brands)
    size = 16 + len(compatible)
    return (
        size.to_bytes(4, "big")
        + b"ftyp"
        + major_brand
        + b"\x00\x00\x00\x00"
        + compatible
    )


@pytest.mark.parametrize(
    ("payload", "claim", "expected"),
    [
        (_image("JPEG"), "image/jpg", "image/jpeg"),
        (_image("PNG"), "image/png", "image/png"),
        (_image("WEBP"), "image/webp", "image/webp"),
        (b"%PDF-1.7\nreceipt\n%%EOF\n", "application/x-pdf", "application/pdf"),
    ],
)
def test_detects_every_supported_receipt_format(
    payload: bytes,
    claim: str | None,
    expected: str,
) -> None:
    assert (
        _detect_expense_receipt_content_type(payload, claimed_content_type=claim)
        == expected
    )


def test_rejects_spoofed_or_incomplete_receipt_files() -> None:
    with pytest.raises(BusinessRuleError, match="does not match"):
        _detect_expense_receipt_content_type(
            _image("PNG"),
            claimed_content_type="application/pdf",
        )
    with pytest.raises(BusinessRuleError, match="valid"):
        _detect_expense_receipt_content_type(
            b"%PDF-1.7\nmissing final marker",
            claimed_content_type="application/pdf",
        )
    with pytest.raises(BusinessRuleError, match="valid"):
        _detect_expense_receipt_content_type(
            b"\xff\xd8\xffnot-a-complete-jpeg",
            claimed_content_type="image/jpeg",
        )
    with pytest.raises(BusinessRuleError, match="valid"):
        _detect_expense_receipt_content_type(
            _isobmff(b"heic", b"mif1"),
            claimed_content_type="image/heic",
        )


def test_receipt_filename_always_uses_the_detected_safe_extension() -> None:
    assert _safe_expense_receipt_filename("receipt.html", "image/jpeg") == "receipt.jpg"
    assert _safe_expense_receipt_filename("invoice.pdf.exe", "application/pdf") == "invoice.pdf.pdf"
    assert _safe_expense_receipt_filename("../", "image/png") == "receipt.png"


def test_private_download_header_is_safe_for_unicode_filename() -> None:
    filename = _safe_expense_receipt_filename("കട-ബിൽ.pdf", "application/pdf")
    response = _private_expense_receipt_response(
        SimpleNamespace(
            original_filename=filename,
            payload=b"%PDF-1.7\n%%EOF\n",
            content_type="application/pdf",
        )
    )
    assert response.headers["cache-control"] == "private, no-store"
    assert "filename*=UTF-8''" in response.headers["content-disposition"]
    assert "%" in response.headers["content-disposition"]


def test_historical_idempotency_response_without_hash_remains_decodable() -> None:
    replay = ExpenseReceiptRead.model_validate(
        {
            "id": "b1dc42da-228d-4476-abfc-0f668b0f9d60",
            "expense_id": "5d20e973-38ed-407b-9f17-92ef153155cb",
            "original_filename": "bill.pdf",
            "content_type": "application/pdf",
            "size_bytes": 12,
            "source": "file",
            "status": "pending",
            "review_note": None,
            "created_at": "2026-09-19T12:00:00Z",
        }
    )

    assert replay.sha256 is None
