from uuid import uuid4

import pytest

from app.core.errors import BusinessRuleError
from app.services.auth.otp import (
    OTP_PASSWORD_RESET,
    generate_otp,
    normalize_account_email,
    otp_hash,
    otp_matches,
    render_otp_email,
)


def test_otp_is_six_numeric_digits() -> None:
    for _ in range(20):
        code = generate_otp()
        assert len(code) == 6
        assert code.isdigit()


def test_otp_hash_is_challenge_bound_and_constant_time_verified() -> None:
    challenge_id = uuid4()
    other_challenge_id = uuid4()
    digest = otp_hash(challenge_id, "123456")

    assert otp_matches(challenge_id, "123456", digest)
    assert not otp_matches(challenge_id, "654321", digest)
    assert not otp_matches(other_challenge_id, "123456", digest)
    assert not otp_matches(challenge_id, "12345x", digest)


def test_login_email_is_normalized_without_rejecting_local_domains() -> None:
    assert normalize_account_email("  RAFI@dcompany.local ") == "rafi@dcompany.local"
    with pytest.raises(BusinessRuleError):
        normalize_account_email("not-an-email")


def test_otp_email_contains_approval_context_but_no_password() -> None:
    subject, html, text = render_otp_email(
        code="123456",
        purpose=OTP_PASSWORD_RESET,
        target_email="person@example.com",
        ip="192.0.2.1",
        expires_minutes=10,
    )

    combined = f"{subject}\n{html}\n{text}"
    assert "123456" in combined
    assert "person@example.com" in combined
    assert "reset an ERP password" in combined
    assert "mailbox password" in combined
    assert "pending_password_hash" not in combined
