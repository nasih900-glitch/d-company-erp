from __future__ import annotations

from uuid import uuid4

import pytest

from app.core.config import get_settings
from app.core.security import decode_token, issue_access_token, issue_refresh_token


def test_access_token_round_trip(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("JWT_SECRET", "test-secret-long-enough-for-hs256")
    monkeypatch.setenv("JWT_ALGORITHM", "HS256")
    get_settings.cache_clear()

    user_id = uuid4()
    company_id = uuid4()
    token = issue_access_token(
        user_id=user_id,
        company_id=company_id,
        roles=["owner"],
        auth_version=3,
    )

    claims = decode_token(token)
    assert claims["sub"] == str(user_id)
    assert claims["company_id"] == str(company_id)
    assert claims["roles"] == ["owner"]
    assert claims["auth_version"] == 3
    assert claims["type"] == "access"

    get_settings.cache_clear()


def test_refresh_token_round_trip(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("JWT_SECRET", "test-secret-long-enough-for-hs256")
    monkeypatch.setenv("JWT_ALGORITHM", "HS256")
    get_settings.cache_clear()

    user_id = uuid4()
    token = issue_refresh_token(user_id=user_id, jti="refresh-id", auth_version=4)

    claims = decode_token(token)
    assert claims["sub"] == str(user_id)
    assert claims["jti"] == "refresh-id"
    assert claims["auth_version"] == 4
    assert claims["type"] == "refresh"

    get_settings.cache_clear()


def test_decode_rejects_invalid_signature(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("JWT_SECRET", "first-secret-long-enough-for-hs256")
    monkeypatch.setenv("JWT_ALGORITHM", "HS256")
    get_settings.cache_clear()
    token = issue_refresh_token(user_id=uuid4(), jti="refresh-id")

    monkeypatch.setenv("JWT_SECRET", "second-secret-long-enough-for-hs256")
    get_settings.cache_clear()

    with pytest.raises(ValueError, match="invalid token"):
        decode_token(token)

    get_settings.cache_clear()
