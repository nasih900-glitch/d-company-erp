from __future__ import annotations

from fastapi import Response

from app.api.v1.auth.router import _set_refresh_cookie
from app.core.config import get_settings


def test_production_refresh_cookie_has_required_browser_security_flags(
    monkeypatch,
) -> None:
    monkeypatch.setattr(get_settings(), "env", "prod")
    response = Response()

    _set_refresh_cookie(response, "refresh-secret")

    header = response.headers["set-cookie"].lower()
    assert "dcompany_refresh=refresh-secret" in header
    assert "httponly" in header
    assert "secure" in header
    assert "samesite=strict" in header
    assert "path=/api/v1/auth" in header
