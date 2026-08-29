from uuid import uuid4

from app.api.v1.pos.router import _receipt_business_read
from app.models import Branch, Company


def test_receipt_business_projection_contains_only_billable_identity() -> None:
    company = Company(
        id=uuid4(),
        name="D Company",
        legal_name="D Company Private Limited",
        timezone="Asia/Kolkata",
        gstin="32ABCDE1234F1Z5",
        gst_registration_type="regular",
        is_composition=False,
        google_sheets_webhook_url="https://admin.example.invalid/webhook",
        upi_vpa="merchant@ybl",
        payment_provider="future-gateway",
        payment_key_id="public-key-id",
        payment_key_secret="must-not-leak",
    )
    branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name="Nilambur",
        invoice_series_code="MN",
        address="Nilambur, Kerala",
        timezone=None,
        state_code="32",
        fssai_license_no="12345678901234",
        trade_license_no="TRADE-1",
        branch_gstin=None,
    )

    payload = _receipt_business_read(company, branch).model_dump()

    assert payload == {
        "brand_name": "D Company",
        "supplier_name": "D Company Private Limited",
        "branch_name": "Nilambur",
        "address": "Nilambur, Kerala",
        "gstin": "32ABCDE1234F1Z5",
        "gst_registration_type": "regular",
        "is_composition": False,
        "fssai_license_no": "12345678901234",
        "trade_license_no": "TRADE-1",
        "state_code": "32",
        "timezone": "Asia/Kolkata",
        "upi_vpa": "merchant@ybl",
    }
    assert {
        "google_sheets_webhook_url",
        "payment_provider",
        "payment_key_id",
        "payment_key_secret",
        "payment_secret_set",
    }.isdisjoint(payload)


def test_receipt_business_projection_prefers_branch_compliance_overrides() -> None:
    company = Company(
        id=uuid4(),
        name="D Company",
        legal_name=None,
        timezone="Asia/Kolkata",
        gstin="32ABCDE1234F1Z5",
        gst_registration_type="regular",
        is_composition=False,
    )
    branch = Branch(
        id=uuid4(),
        company_id=company.id,
        name="Bengaluru",
        invoice_series_code="BL",
        timezone="Asia/Kolkata",
        state_code="29",
        branch_gstin="29ABCDE1234F1Z7",
    )

    payload = _receipt_business_read(company, branch)

    assert payload.supplier_name == "D Company"
    assert payload.gstin == "29ABCDE1234F1Z7"
    assert payload.timezone == "Asia/Kolkata"
