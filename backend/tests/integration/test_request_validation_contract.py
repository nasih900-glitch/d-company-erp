from __future__ import annotations

import pytest


@pytest.mark.integration
@pytest.mark.asyncio
async def test_fastapi_validation_uses_safe_field_aware_error_envelope(client) -> None:
    rejected_email = "employee-" + ("x" * 260) + "@example.test"

    response = await client.post(
        "/api/v1/auth/login",
        json={"email": rejected_email, "password": "not-the-real-password"},
    )

    assert response.status_code == 422
    payload = response.json()
    assert payload["error"]["code"] == "validation_error"
    assert payload["error"]["message"] == "email must be at most 254 characters."
    assert payload["error"]["details"]["fields"] == [
        {
            "field": "email",
            "type": "string_too_long",
            "message": "email must be at most 254 characters.",
        }
    ]
    assert rejected_email not in response.text
    assert "not-the-real-password" not in response.text
