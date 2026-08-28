"""Normalize support text and remove common accidentally pasted secrets."""

from __future__ import annotations

import re
import unicodedata

REDACTED = "[REDACTED]"

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_PRIVATE_KEY = re.compile(
    r"-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----.*?"
    r"-----END(?: [A-Z0-9]+)? PRIVATE KEY-----",
    flags=re.IGNORECASE | re.DOTALL,
)
# Credential headers are structured as ``name: value-to-end-of-line``. Redact
# the whole value before token-level patterns: schemes such as ApiKey/Digest and
# semicolon-delimited cookies otherwise leave credential tails behind.
_SENSITIVE_HEADER = re.compile(
    r"(?im)\b(proxy-authorization|authorization|set-cookie|cookie)(\s*:\s*)[^\n]*"
)
_BEARER = re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}")
_BASIC_AUTH = re.compile(r"(?i)\bbasic\s+[A-Za-z0-9._~+/=-]{4,}")
_JWT = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
_URL_CREDENTIAL = re.compile(r"(?i)\b([a-z][a-z0-9+.-]*://[^/\s:@]+:)([^@\s/]+)(@)")
_PROVIDER_TOKEN = re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bgh[pousr]_[A-Za-z0-9]{20,}\b")
_SECRET_NAME = (
    r"password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|"  # noqa: S105 -- field-name regex, not a credential
    r"token|secret|authorization|cookie"
)
_DOUBLE_QUOTED_SECRET_ASSIGNMENT = re.compile(
    rf'(?i)\b({_SECRET_NAME})\b(\s*[:=]\s*)"(?:\\.|[^"\\\r\n])*"'
)
_SINGLE_QUOTED_SECRET_ASSIGNMENT = re.compile(
    rf"(?i)\b({_SECRET_NAME})\b(\s*[:=]\s*)'(?:\\.|[^'\\\r\n])*'"
)
_SECRET_ASSIGNMENT = re.compile(
    rf"(?i)\b({_SECRET_NAME})\b(\s*[:=]\s*)([^\s,;]+)"
)


def _redact_assignment(match: re.Match[str]) -> str:
    return f"{match.group(1)}{match.group(2)}{REDACTED}"


def _redact_sensitive_header(match: re.Match[str]) -> str:
    return f"{match.group(1)}{match.group(2)}{REDACTED}"


def sanitize_bug_report_text(value: str) -> str:
    """Return stable plain text without controls or obvious credentials.

    This is a defence-in-depth filter, not an HTML renderer. Clients must
    still render these values as text rather than injecting them as markup.
    """
    normalized = unicodedata.normalize("NFKC", value)
    normalized = normalized.replace("\r\n", "\n").replace("\r", "\n")
    normalized = _CONTROL_CHARS.sub("", normalized)
    normalized = _PRIVATE_KEY.sub(REDACTED, normalized)
    normalized = _SENSITIVE_HEADER.sub(_redact_sensitive_header, normalized)
    normalized = _BEARER.sub(f"Bearer {REDACTED}", normalized)
    normalized = _BASIC_AUTH.sub(f"Basic {REDACTED}", normalized)
    normalized = _JWT.sub(REDACTED, normalized)
    normalized = _URL_CREDENTIAL.sub(
        lambda match: f"{match.group(1)}{REDACTED}{match.group(3)}", normalized
    )
    normalized = _PROVIDER_TOKEN.sub(REDACTED, normalized)
    # Quoted values may contain whitespace. Redact them before the compact
    # assignment fallback so a value such as password="correct horse" does
    # not leak every word after the first one.
    normalized = _DOUBLE_QUOTED_SECRET_ASSIGNMENT.sub(_redact_assignment, normalized)
    normalized = _SINGLE_QUOTED_SECRET_ASSIGNMENT.sub(_redact_assignment, normalized)
    normalized = _SECRET_ASSIGNMENT.sub(_redact_assignment, normalized)
    return normalized.strip()


__all__ = ["REDACTED", "sanitize_bug_report_text"]
