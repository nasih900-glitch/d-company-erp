from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import select, text

from app.models import Branch, UserRole


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.mark.asyncio
async def test_standard_owner_can_read_current_branch_receipt_identity_only(
    client,
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    owner = seed_owner["owner"]

    company.name = "D Company"
    company.legal_name = "D Company Private Limited"
    company.timezone = "Asia/Kolkata"
    company.gstin = "32ABCDE1234F1Z5"
    company.gst_registration_type = "regular"
    company.is_composition = False
    company.google_sheets_webhook_url = "https://admin.example.invalid/webhook"
    company.upi_vpa = "merchant@ybl"
    company.payment_provider = "future-gateway"
    company.payment_key_id = "public-key-id"
    company.payment_key_secret = "must-not-leak"

    branch.name = "Nilambur"
    branch.address = "Nilambur, Kerala"
    branch.timezone = "Asia/Kolkata"
    branch.state_code = "32"
    branch.fssai_license_no = "12345678901234"
    branch.trade_license_no = "TRADE-1"

    # A second branch with different bill identity proves the endpoint does
    # not fall back to an arbitrary company branch.
    session.add(
        Branch(
            id=uuid4(),
            company_id=company.id,
            name="Not the current branch",
            invoice_series_code="NC",
            address="Must not leak",
            timezone="UTC",
            state_code="29",
            branch_gstin="29ABCDE1234F1Z7",
        )
    )
    user_role = (
        await session.execute(select(UserRole).where(UserRole.user_id == owner.id))
    ).scalar_one()
    user_role.branch_id = branch.id
    await session.commit()

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    receipt = await client.get("/api/v1/pos/receipt-business", headers=headers)

    assert receipt.status_code == 200
    assert receipt.json() == {
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
    assert "payment_key_secret" not in receipt.text
    assert "admin.example.invalid" not in receipt.text

    # The operational projection must not weaken the protected settings API.
    company_settings = await client.get("/api/v1/settings/company", headers=headers)
    branch_settings = await client.get("/api/v1/settings/branches", headers=headers)
    assert company_settings.status_code == 403
    assert branch_settings.status_code == 403
