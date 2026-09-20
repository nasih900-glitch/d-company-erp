"""Encrypt Google Sheets signing secrets before database persistence."""

from __future__ import annotations

import base64
import binascii
import os
from typing import TYPE_CHECKING

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.config import get_settings

if TYPE_CHECKING:
    from uuid import UUID

_FORMAT_VERSION = b"dcompany-google-sheets-secret:v1"
_ENVELOPE_PREFIX = "v1."
_NONCE_BYTES = 12


class GoogleSheetsSecretDecryptionError(ValueError):
    """Raised when encrypted integration material cannot be authenticated."""


def _key() -> bytes:
    encoded = get_settings().google_sheets_secret_encryption_key.get_secret_value()
    try:
        key = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as exc:  # defensive; Settings validates first
        raise RuntimeError("Google Sheets encryption key is invalid") from exc
    if len(key) != 32:  # defensive; Settings validates first
        raise RuntimeError("Google Sheets encryption key must be 32 bytes")
    return key


def _aad(company_id: UUID) -> bytes:
    return _FORMAT_VERSION + b":" + str(company_id).encode("ascii")


def encrypt_google_sheets_signing_secret(*, company_id: UUID, secret: str) -> str:
    """Return an authenticated, company-bound ciphertext envelope."""

    if len(secret) < 32:
        raise ValueError("Google Sheets signing secret must contain at least 32 characters")
    nonce = os.urandom(_NONCE_BYTES)
    encrypted = AESGCM(_key()).encrypt(nonce, secret.encode("utf-8"), _aad(company_id))
    return _ENVELOPE_PREFIX + base64.urlsafe_b64encode(nonce + encrypted).decode(
        "ascii"
    )


def decrypt_google_sheets_signing_secret(
    *, company_id: UUID, ciphertext: str
) -> str:
    """Authenticate and decrypt a company-bound signing secret."""

    try:
        if not ciphertext.startswith(_ENVELOPE_PREFIX):
            raise ValueError("ciphertext envelope version is unsupported")
        envelope = base64.b64decode(
            ciphertext.removeprefix(_ENVELOPE_PREFIX),
            altchars=b"-_",
            validate=True,
        )
        if len(envelope) <= _NONCE_BYTES + 16:
            raise ValueError("ciphertext envelope is too short")
        nonce = envelope[:_NONCE_BYTES]
        encrypted = envelope[_NONCE_BYTES:]
        plaintext = AESGCM(_key()).decrypt(nonce, encrypted, _aad(company_id))
        secret = plaintext.decode("utf-8")
    except (binascii.Error, UnicodeDecodeError, ValueError, InvalidTag) as exc:
        raise GoogleSheetsSecretDecryptionError(
            "Google Sheets signing secret could not be decrypted"
        ) from exc
    if len(secret) < 32:
        raise GoogleSheetsSecretDecryptionError(
            "Google Sheets signing secret is invalid"
        )
    return secret


__all__ = [
    "GoogleSheetsSecretDecryptionError",
    "decrypt_google_sheets_signing_secret",
    "encrypt_google_sheets_signing_secret",
]
