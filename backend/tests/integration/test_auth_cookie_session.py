from __future__ import annotations

import pytest

COOKIE_NAME = "dcompany_refresh"
COOKIE_HEADERS = {"X-Session-Transport": "cookie"}


@pytest.mark.asyncio
async def test_web_cookie_session_is_httponly_rotated_and_explicitly_logged_out(
    client,
    seed_owner,
) -> None:
    credentials = {
        "email": seed_owner["owner"].email,
        "password": seed_owner["password"],
    }

    native_login = await client.post("/api/v1/auth/login", json=credentials)
    assert native_login.status_code == 200
    assert native_login.json()["refresh_token"]
    assert COOKIE_NAME not in native_login.headers.get("set-cookie", "")

    web_login = await client.post(
        "/api/v1/auth/login",
        json=credentials,
        headers=COOKIE_HEADERS,
    )
    assert web_login.status_code == 200
    assert web_login.json()["refresh_token"] == ""
    set_cookie = web_login.headers["set-cookie"].lower()
    assert f"{COOKIE_NAME}=" in set_cookie
    assert "httponly" in set_cookie
    assert "samesite=strict" in set_cookie
    assert "path=/api/v1/auth" in set_cookie
    first_refresh = client.cookies.get(COOKIE_NAME)
    assert first_refresh

    csrf_blocked = await client.post("/api/v1/auth/refresh", json={})
    assert csrf_blocked.status_code == 401
    assert client.cookies.get(COOKIE_NAME) == first_refresh

    refreshed = await client.post(
        "/api/v1/auth/refresh",
        json={},
        headers=COOKIE_HEADERS,
    )
    assert refreshed.status_code == 200
    assert refreshed.json()["refresh_token"] == ""
    assert refreshed.json()["access_token"]
    rotated_refresh = client.cookies.get(COOKIE_NAME)
    assert rotated_refresh
    assert rotated_refresh != first_refresh

    logout_csrf_blocked = await client.post("/api/v1/auth/logout", json={})
    assert logout_csrf_blocked.status_code == 401
    assert client.cookies.get(COOKIE_NAME) == rotated_refresh

    logged_out = await client.post(
        "/api/v1/auth/logout",
        json={},
        headers=COOKIE_HEADERS,
    )
    assert logged_out.status_code == 200
    assert logged_out.json()["message"] == "Signed out."
    assert client.cookies.get(COOKIE_NAME) is None

    revoked_access = await client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {refreshed.json()['access_token']}"},
    )
    assert revoked_access.status_code == 401

    revoked_cookie = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": rotated_refresh},
    )
    assert revoked_cookie.status_code == 401

    no_cookie = await client.post(
        "/api/v1/auth/refresh",
        json={},
        headers=COOKIE_HEADERS,
    )
    assert no_cookie.status_code == 401
