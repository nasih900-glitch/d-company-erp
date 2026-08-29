from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.api.v1.bug_reports.router import BugReportCreate, BugReportUpdate
from app.services.bug_reports.sanitization import REDACTED, sanitize_bug_report_text


def _payload(**overrides) -> dict:
    payload = {
        "category": "crash",
        "severity": "high",
        "title": "Checkout screen crashes",
        "description": "The checkout screen closes after tapping Pay.",
        "client_context": {
            "platform": "android",
            "connectivity": "online",
        },
    }
    payload.update(overrides)
    return payload


def test_sanitizer_removes_controls_and_common_accidentally_pasted_secrets() -> None:
    # Keep the AWS-shaped fixture split so repository secret scanners do not
    # mistake this deterministic sanitizer test for a live credential.
    aws_access_key = "AKIA" + "IOSFODNN7EXAMPLE"
    raw = (
        "Failure\x00 Authorization: Bearer super-secret-token-value\n"
        "Authorization: Basic dXNlcjpjb3JyZWN0LWhvcnNl\n"
        "password=hunter2 and access_token=abc123456789\n"
        'password="correct horse battery staple" remains confidential\n'
        "secret='words with spaces' remains private\n"
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature\n"
        "postgresql://erp:database-password@db.internal/erp\n"
        f"{aws_access_key}"
    )

    cleaned = sanitize_bug_report_text(raw)

    assert "\x00" not in cleaned
    assert "super-secret-token-value" not in cleaned
    assert "dXNlcjpjb3JyZWN0LWhvcnNl" not in cleaned
    assert "hunter2" not in cleaned
    assert "correct horse battery staple" not in cleaned
    assert "words with spaces" not in cleaned
    assert "abc123456789" not in cleaned
    assert "eyJhbGci" not in cleaned
    assert "database-password" not in cleaned
    assert aws_access_key not in cleaned
    assert cleaned.count(REDACTED) >= 9


def test_sanitizer_redacts_complete_sensitive_header_values() -> None:
    raw = (
        "Safe context before the captured headers.\n"
        "Authorization: ApiKey top-secret-value\n"
        'pRoXy-AuThOrIzAtIoN: Digest username="admin", response="digest-secret"\n'
        "Cookie: sessionid=top-secret; csrftoken=also-secret\n"
        "Set-Cookie: refresh=refresh-secret; HttpOnly; Secure\n"
        "Connectivity: offline\n"
        "Safe context after the captured headers."
    )

    cleaned = sanitize_bug_report_text(raw)

    for secret_tail in (
        "ApiKey",
        "top-secret-value",
        "admin",
        "digest-secret",
        "sessionid",
        "csrftoken",
        "also-secret",
        "refresh-secret",
        "HttpOnly",
    ):
        assert secret_tail not in cleaned
    assert cleaned.count(REDACTED) == 4
    assert "Safe context before the captured headers." in cleaned
    assert "Connectivity: offline" in cleaned
    assert "Safe context after the captured headers." in cleaned


def test_create_schema_is_strict_and_sanitizes_before_enforcing_bounds() -> None:
    report = BugReportCreate.model_validate(
        _payload(
            title="  Payment password=do-not-store failed  ",
            description="  A valid long description with token=secret-value.  ",
            reproduction_steps="\x00Open checkout",
        )
    )

    assert report.title == f"Payment password={REDACTED} failed"
    assert report.description == f"A valid long description with token={REDACTED}"
    assert report.reproduction_steps == "Open checkout"

    with pytest.raises(ValidationError):
        BugReportCreate.model_validate(_payload(screenshot_base64="not-supported"))
    with pytest.raises(ValidationError):
        BugReportCreate.model_validate(_payload(category="security"))
    with pytest.raises(ValidationError):
        BugReportCreate.model_validate(_payload(title="tiny"))
    with pytest.raises(ValidationError):
        BugReportCreate.model_validate(
            _payload(client_context={"platform": "android", "unknown": "field"})
        )


def test_update_schema_distinguishes_note_clear_from_missing_payload() -> None:
    update = BugReportUpdate.model_validate({"internal_resolution_note": "   "})
    assert update.internal_resolution_note is None
    assert "internal_resolution_note" in update.model_fields_set

    with pytest.raises(ValidationError):
        BugReportUpdate.model_validate({})
    with pytest.raises(ValidationError):
        BugReportUpdate.model_validate({"title": "submission data cannot be edited"})
