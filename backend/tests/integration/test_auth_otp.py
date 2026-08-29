from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.core.config import get_settings
from app.services.auth import otp as otp_service
from app.services.email.mailer import Mailer
from app.services.realtime import manager as realtime_manager


@pytest_asyncio.fixture(autouse=True)
async def require_local_db(session) -> None:
    try:
        await session.execute(text("select 1"))
    except Exception as exc:
        pytest.skip(f"local Postgres unavailable: {exc}")


@pytest.fixture
def configured_security_email(monkeypatch, seed_owner) -> list[dict[str, str]]:
    monkeypatch.setenv("SMTP_HOST", "smtp.example.test")
    monkeypatch.setenv("SMTP_PORT", "465")
    monkeypatch.setenv("SMTP_USER", "business@example.test")
    monkeypatch.setenv("SMTP_PASSWORD", "test-only-app-password")
    monkeypatch.setenv("FROM_EMAIL", "business@example.test")
    monkeypatch.setattr(
        get_settings(),
        "account_security_company_id",
        seed_owner["company"].id,
    )
    monkeypatch.setattr(otp_service, "generate_otp", lambda: "123456")
    sent: list[dict[str, str]] = []

    def fake_send(
        _self: Mailer,
        to: str | list[str],
        subject: str,
        html: str,
        text: str | None = None,
    ) -> bool:
        sent.append({
            "to": to if isinstance(to, str) else ",".join(to),
            "subject": subject,
            "html": html,
            "text": text or "",
        })
        return True

    monkeypatch.setattr(Mailer, "send", fake_send)
    return sent


@pytest.mark.asyncio
async def test_new_login_requires_single_use_central_email_otp(
    client,
    configured_security_email,
    seed_owner,
    monkeypatch,
) -> None:
    broadcasts: list[tuple[object, str]] = []

    async def capture_broadcast(company_id, resource: str) -> None:
        broadcasts.append((company_id, resource))

    monkeypatch.setattr(realtime_manager, "broadcast", capture_broadcast)
    email = f"new-{uuid4().hex[:10]}@test.local"
    requested = await client.post(
        "/api/v1/auth/register/request",
        json={
            "email": email,
            "name": "New Operator",
            "phone": "5550100",
            "password": "short-pass-123",
        },
    )

    assert requested.status_code == 202

    challenge_id = requested.json()["challenge_id"]
    assert requested.json()["destination"].startswith("b***@")
    assert configured_security_email[-1]["to"] == "business@retrocafe.online"
    assert "123456" in configured_security_email[-1]["text"]
    assert "short-pass-123" not in configured_security_email[-1]["text"]

    wrong = await client.post(
        "/api/v1/auth/register/confirm",
        json={"challenge_id": challenge_id, "code": "000000"},
    )
    assert wrong.status_code == 422

    confirmed = await client.post(
        "/api/v1/auth/register/confirm",
        json={"challenge_id": challenge_id, "code": "123456"},
    )
    assert confirmed.status_code == 201
    assert broadcasts == [(seed_owner["company"].id, "audit")]

    login = await client.post(
        "/api/v1/auth/login",
        json={"email": email, "password": "short-pass-123"},
    )
    assert login.status_code == 200
    me = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {login.json()['access_token']}"},
    )
    assert me.status_code == 200
    assert me.json()["branch_id"] is not None

    reused = await client.post(
        "/api/v1/auth/register/confirm",
        json={"challenge_id": challenge_id, "code": "123456"},
    )
    assert reused.status_code == 422


@pytest.mark.asyncio
async def test_password_reset_replaces_old_password_only_after_otp(
    client,
    seed_owner,
    configured_security_email,
    monkeypatch,
) -> None:
    broadcasts: list[tuple[object, str]] = []

    async def capture_broadcast(company_id, resource: str) -> None:
        broadcasts.append((company_id, resource))

    monkeypatch.setattr(realtime_manager, "broadcast", capture_broadcast)
    owner = seed_owner["owner"]
    existing_login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert existing_login.status_code == 200
    broadcasts.clear()
    requested = await client.post(
        "/api/v1/auth/password-reset/request",
        json={"email": owner.email},
    )
    assert requested.status_code == 202

    confirmed = await client.post(
        "/api/v1/auth/password-reset/confirm",
        json={
            "challenge_id": requested.json()["challenge_id"],
            "code": "123456",
            "new_password": "new-short-pass-456",
        },
    )
    assert confirmed.status_code == 200
    assert broadcasts == [
        (seed_owner["company"].id, "audit"),
        (seed_owner["company"].id, "access_control"),
    ]

    expired_access = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {existing_login.json()['access_token']}"},
    )
    assert expired_access.status_code == 401
    expired_refresh = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": existing_login.json()["refresh_token"]},
    )
    assert expired_refresh.status_code == 401

    old_login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert old_login.status_code == 401

    new_login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": "new-short-pass-456"},
    )
    assert new_login.status_code == 200


@pytest.mark.asyncio
async def test_legacy_staff_account_writes_cannot_bypass_otp(
    client,
    seed_owner,
) -> None:
    owner = seed_owner["owner"]
    login = await client.post(
        "/api/v1/auth/login",
        json={"email": owner.email, "password": seed_owner["password"]},
    )
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    create = await client.post(
        "/api/v1/staff/users",
        headers=headers,
        json={
            "email": f"blocked-{uuid4().hex[:8]}@test.local",
            "name": "Blocked Bypass",
            "password": "not-allowed-123",
            "role_code": "owner",
        },
    )
    assert create.status_code == 422
    assert "OTP approval is required" in create.json()["error"]["message"]

    password = await client.post(
        f"/api/v1/staff/users/{owner.id}/password",
        headers=headers,
        json={"new_password": "not-allowed-456"},
    )
    assert password.status_code == 422
    assert "OTP approval is required" in password.json()["error"]["message"]


@pytest.mark.asyncio
async def test_standard_owner_reaches_operational_modules_but_not_protected_admin(
    client,
    seed_owner,
) -> None:
    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200
    headers = {
        "Authorization": f"Bearer {login.json()['access_token']}",
        "X-Terminal-Id": str(seed_owner["terminal"].id),
    }

    me = await client.get("/api/v1/auth/me", headers=headers)
    assert me.status_code == 200
    assert me.json()["branch_id"] == str(seed_owner["branch"].id)
    assert me.json()["protected_access"] is False

    module_paths = (
        "/api/v1/pos/orders",
        "/api/v1/pos/shifts",
        "/api/v1/tables/floors",
        "/api/v1/menu/categories",
        "/api/v1/inventory/ingredients",
        "/api/v1/inventory/branches",
        "/api/v1/gaming/stations",
        "/api/v1/events/upcoming",
        "/api/v1/events/branches",
        "/api/v1/finance/expenses",
        "/api/v1/finance/branches",
        "/api/v1/ocr/queue",
        "/api/v1/staff/users",
        "/api/v1/analytics/dashboard",
        "/api/v1/reports/daily",
        "/api/v1/customers",
        "/api/v1/memberships/tiers",
        "/api/v1/kitchen/queue",
        "/api/v1/accounting/chart-of-accounts",
        "/api/v1/insights/inventory/valuation",
    )
    for path in module_paths:
        response = await client.get(path, headers=headers)
        assert response.status_code == 200, f"{path}: {response.text}"

    settings = await client.get("/api/v1/settings/company", headers=headers)
    assert settings.status_code == 200

    audit = await client.post(
        "/api/v1/admin/audit/unlock",
        headers=headers,
        json={"password": seed_owner["password"]},
    )
    assert audit.status_code == 403
