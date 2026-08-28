"""Audit snapshots must never retain authentication secrets."""

from __future__ import annotations

from uuid import uuid4

from app.models import User
from app.models.auth_challenge import AuthOtpChallenge
from app.services.audit.recorder import TRACKED, _captured_diff, _serialize


def test_user_audit_snapshots_and_diffs_redact_password_and_mfa_secrets() -> None:
    password_hash = "argon2-secret-that-must-not-enter-audit-json"
    mfa_secret = "mfa-secret-that-must-not-enter-audit-json"
    user = User(
        id=uuid4(),
        company_id=uuid4(),
        email="audit-secret@test.local",
        name="Audit Secret",
        password_hash=password_hash,
        mfa_secret=mfa_secret,
        status="active",
    )

    snapshot = _serialize(user)
    assert snapshot is not None
    assert snapshot["password_hash"] == "***REDACTED***"
    assert snapshot["mfa_secret"] == "***REDACTED***"
    assert password_hash not in repr(snapshot)
    assert mfa_secret not in repr(snapshot)

    user.password_hash = "replacement-password-hash"
    user.mfa_secret = "replacement-mfa-secret"
    diff = _captured_diff(user)
    assert diff["password_hash"] == {
        "before": "***REDACTED***",
        "after": "***REDACTED***",
    }
    assert diff["mfa_secret"] == {
        "before": "***REDACTED***",
        "after": "***REDACTED***",
    }
    assert "replacement-password-hash" not in repr(diff)
    assert "replacement-mfa-secret" not in repr(diff)


def test_pending_password_challenges_are_not_automatically_audited() -> None:
    # AuthChallenge holds a pending password hash during OTP approval. The
    # challenge lifecycle has purpose-built security events; serializing the
    # row into the general audit stream would unnecessarily retain a secret.
    assert AuthOtpChallenge not in TRACKED
