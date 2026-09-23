"""Audit snapshots must never retain authentication or integration secrets."""

from __future__ import annotations

from uuid import uuid4

from sqlalchemy.orm.attributes import set_committed_value

from app.models import ClientGamingCleanupReconciliation, Company, User
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


def test_company_audit_snapshots_and_diffs_redact_sheets_secret_ciphertext() -> None:
    first_ciphertext = "gcm-envelope-containing-first-secret"
    replacement_ciphertext = "gcm-envelope-containing-replacement-secret"
    company = Company(
        id=uuid4(),
        name="Audit Integration Secret",
        google_sheets_signing_secret_ciphertext=first_ciphertext,
    )

    snapshot = _serialize(company)
    assert snapshot is not None
    assert snapshot["google_sheets_signing_secret_ciphertext"] == "***REDACTED***"
    assert first_ciphertext not in repr(snapshot)

    company.google_sheets_signing_secret_ciphertext = replacement_ciphertext
    rotated = _captured_diff(company)
    assert rotated["google_sheets_signing_secret_ciphertext"] == {
        "before": "***REDACTED***",
        "after": "***REDACTED***",
    }
    assert first_ciphertext not in repr(rotated)
    assert replacement_ciphertext not in repr(rotated)

    company.google_sheets_signing_secret_ciphertext = None
    disconnected = _captured_diff(company)
    assert disconnected["google_sheets_signing_secret_ciphertext"] == {
        "before": "***REDACTED***",
        "after": "***REDACTED***",
    }
    assert replacement_ciphertext not in repr(disconnected)


def test_pending_password_challenges_are_not_automatically_audited() -> None:
    # AuthChallenge holds a pending password hash during OTP approval. The
    # challenge lifecycle has purpose-built security events; serializing the
    # row into the general audit stream would unnecessarily retain a secret.
    assert AuthOtpChallenge not in TRACKED


def test_cleanup_snapshot_is_redacted_without_hiding_safe_hash_evidence() -> None:
    customer_name = "AUDIT-PII-CUSTOMER-NAME-SENTINEL"
    customer_phone = "+919876543210"
    customer_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    snapshot_hash = "a" * 64
    candidate_hash = "b" * 64
    start_hash = "c" * 64
    stop_hash = "d" * 64
    row = ClientGamingCleanupReconciliation(
        local_snapshot={
            "customer_name": customer_name,
            "customer_phone": customer_phone,
            "customer_id": customer_id,
            "evidence_revision": 7,
        },
        local_snapshot_sha256=snapshot_hash,
        candidate_sha256=candidate_hash,
        start_request_hash=start_hash,
        stop_request_hash=stop_hash,
    )

    assert (
        ClientGamingCleanupReconciliation.__table__.c.local_snapshot.info.get("audit_redact")
        is True
    )
    snapshot = _serialize(row)
    assert snapshot is not None
    assert snapshot["local_snapshot"] == "***REDACTED***"
    assert snapshot["local_snapshot_sha256"] == snapshot_hash
    assert snapshot["candidate_sha256"] == candidate_hash
    assert snapshot["start_request_hash"] == start_hash
    assert snapshot["stop_request_hash"] == stop_hash
    serialized_snapshot = repr(snapshot)
    assert customer_name not in serialized_snapshot
    assert customer_phone not in serialized_snapshot
    assert customer_id not in serialized_snapshot

    set_committed_value(
        row,
        "local_snapshot",
        {
            "customer_name": customer_name,
            "customer_phone": customer_phone,
            "customer_id": customer_id,
            "evidence_revision": 7,
        },
    )
    replacement_name = "AUDIT-PII-REPLACEMENT-NAME-SENTINEL"
    replacement_phone = "+919999999998"
    replacement_id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    row.local_snapshot = {
        "customer_name": replacement_name,
        "customer_phone": replacement_phone,
        "customer_id": replacement_id,
        "evidence_revision": 8,
    }
    replacement_snapshot_hash = "e" * 64
    set_committed_value(row, "local_snapshot_sha256", snapshot_hash)
    row.local_snapshot_sha256 = replacement_snapshot_hash
    diff = _captured_diff(row)
    assert diff["local_snapshot"] == {
        "before": "***REDACTED***",
        "after": "***REDACTED***",
    }
    assert diff["local_snapshot_sha256"] == {
        "before": snapshot_hash,
        "after": replacement_snapshot_hash,
    }
    serialized_diff = repr(diff)
    for sentinel in (
        customer_name,
        customer_phone,
        customer_id,
        replacement_name,
        replacement_phone,
        replacement_id,
    ):
        assert sentinel not in serialized_diff
