from __future__ import annotations

import base64
from uuid import uuid4

import pytest

from app.services.integrations.google_sheets_secrets import (
    GoogleSheetsSecretDecryptionError,
    decrypt_google_sheets_signing_secret,
    encrypt_google_sheets_signing_secret,
)


def test_google_sheets_secret_round_trips_and_is_not_stored_in_plaintext() -> None:
    company_id = uuid4()
    secret = "s" * 64

    ciphertext = encrypt_google_sheets_signing_secret(
        company_id=company_id,
        secret=secret,
    )

    assert secret not in ciphertext
    assert decrypt_google_sheets_signing_secret(
        company_id=company_id,
        ciphertext=ciphertext,
    ) == secret


def test_google_sheets_secret_is_bound_to_company() -> None:
    ciphertext = encrypt_google_sheets_signing_secret(
        company_id=uuid4(),
        secret="s" * 64,
    )

    with pytest.raises(GoogleSheetsSecretDecryptionError):
        decrypt_google_sheets_signing_secret(
            company_id=uuid4(),
            ciphertext=ciphertext,
        )


def test_google_sheets_secret_rejects_tampering() -> None:
    company_id = uuid4()
    ciphertext = encrypt_google_sheets_signing_secret(
        company_id=company_id,
        secret="s" * 64,
    )
    encoded_envelope = ciphertext.removeprefix("v1.")
    envelope = bytearray(base64.urlsafe_b64decode(encoded_envelope))
    envelope[-1] ^= 0x01
    tampered = "v1." + base64.urlsafe_b64encode(bytes(envelope)).decode("ascii")

    with pytest.raises(GoogleSheetsSecretDecryptionError):
        decrypt_google_sheets_signing_secret(
            company_id=company_id,
            ciphertext=tampered,
        )
