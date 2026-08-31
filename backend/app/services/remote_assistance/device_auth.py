"""P-256 proof-of-possession for registered remote-assistance tablets.

The bearer token still authenticates the human user.  This module adds a
separate proof that the request originated from the private key held by the
tenant-bound Android installation.  Only public SPKI bytes are persisted.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import UUID

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from redis.exceptions import RedisError
from sqlalchemy import select

from app.core.config import get_settings
from app.core.errors import AuthError, ServiceUnavailableError, ValidationError
from app.core.logging import get_logger
from app.core.redis_clients import close_request_path_redis_client, request_path_redis_client
from app.models import RemoteAssistanceDeviceKey

if TYPE_CHECKING:
    from fastapi import Request
    from sqlalchemy.ext.asyncio import AsyncSession

log = get_logger(__name__)

REQUEST_SIGNATURE_DOMAIN = "D-COMPANY-ERP-REMOTE-REQUEST-V1"
ENROLLMENT_SIGNATURE_DOMAIN = "D-COMPANY-ERP-REMOTE-ENROLLMENT-V1"
PAIRING_CODE_DOMAIN = b"D-COMPANY-ERP-REMOTE-PAIRING-V1\x00"

_CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
_MAX_SIGNATURE_BYTES = 80
_MAX_SPKI_BYTES = 160
_MIN_SPKI_BYTES = 80


@dataclass(frozen=True, slots=True)
class ParsedDevicePublicKey:
    spki_der: bytes
    fingerprint_sha256: str
    key: ec.EllipticCurvePublicKey


@dataclass(frozen=True, slots=True)
class AuthenticatedDeviceRequest:
    device_key: RemoteAssistanceDeviceKey
    expected_content_sha256: str
    nonce: UUID
    signed_at: datetime


def _b64url_decode(value: str, *, label: str, max_bytes: int) -> bytes:
    if not value or "=" in value or any(char.isspace() for char in value):
        raise ValidationError(f"{label} must use unpadded base64url encoding.")
    try:
        decoded = base64.b64decode(
            value + "=" * (-len(value) % 4),
            altchars=b"-_",
            validate=True,
        )
    except (binascii.Error, ValueError) as exc:
        raise ValidationError(f"{label} is not valid base64url data.") from exc
    if len(decoded) > max_bytes or _b64url_encode(decoded) != value:
        raise ValidationError(f"{label} is not canonical base64url data.")
    return decoded


def _b64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def parse_p256_spki(public_key_spki: str) -> ParsedDevicePublicKey:
    """Parse one canonical DER SubjectPublicKeyInfo containing a P-256 key."""

    spki_der = _b64url_decode(
        public_key_spki,
        label="public_key_spki",
        max_bytes=_MAX_SPKI_BYTES,
    )
    if len(spki_der) < _MIN_SPKI_BYTES:
        raise ValidationError("public_key_spki is too short for a P-256 SPKI key.")
    try:
        loaded = serialization.load_der_public_key(spki_der)
    except (TypeError, ValueError) as exc:
        raise ValidationError("public_key_spki is not a valid DER public key.") from exc
    if not isinstance(loaded, ec.EllipticCurvePublicKey) or not isinstance(
        loaded.curve, ec.SECP256R1
    ):
        raise ValidationError("Remote assistance requires an ECDSA P-256 public key.")
    canonical = loaded.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    if not hmac.compare_digest(canonical, spki_der):
        raise ValidationError("public_key_spki must use canonical DER SPKI encoding.")
    return ParsedDevicePublicKey(
        spki_der=spki_der,
        fingerprint_sha256=hashlib.sha256(spki_der).hexdigest(),
        key=loaded,
    )


def canonical_enrollment_statement(
    *,
    company_id: UUID,
    installation_id: UUID,
    key_id: UUID,
    enrollment_id: UUID,
    signed_at_epoch_seconds: int,
    nonce: UUID,
    fingerprint_sha256: str,
) -> bytes:
    return "\n".join(
        (
            ENROLLMENT_SIGNATURE_DOMAIN,
            str(company_id),
            str(installation_id),
            str(key_id),
            str(enrollment_id),
            str(signed_at_epoch_seconds),
            str(nonce),
            fingerprint_sha256,
        )
    ).encode("ascii")


def canonical_raw_target(request: Request) -> bytes:
    """Return exact ASGI raw path plus untouched query bytes."""

    raw_path = request.scope.get("raw_path")
    query = request.scope.get("query_string", b"")
    if not isinstance(raw_path, bytes) or not isinstance(query, bytes):
        raise AuthError("The request target cannot be authenticated.")
    return raw_path + (b"?" + query if query else b"")


def canonical_request_statement(
    *,
    method: str,
    raw_target: bytes,
    content_sha256: str,
    signed_at_epoch_seconds: int,
    nonce: UUID,
    key_id: UUID,
) -> bytes:
    try:
        return b"\n".join(
            (
                REQUEST_SIGNATURE_DOMAIN.encode("ascii"),
                method.upper().encode("ascii"),
                raw_target,
                content_sha256.encode("ascii"),
                str(signed_at_epoch_seconds).encode("ascii"),
                str(nonce).encode("ascii"),
                str(key_id).encode("ascii"),
            )
        )
    except UnicodeEncodeError as exc:
        raise AuthError("The request target cannot be authenticated.") from exc


def pairing_code(
    *,
    company_id: UUID,
    installation_id: UUID,
    key_id: UUID,
    fingerprint_sha256: str,
) -> str:
    """Return a 60-bit human code protected by the dedicated server secret."""

    message = PAIRING_CODE_DOMAIN + b"\n".join(
        (
            str(company_id).encode("ascii"),
            str(installation_id).encode("ascii"),
            str(key_id).encode("ascii"),
            fingerprint_sha256.encode("ascii"),
        )
    )
    digest = hmac.digest(
        get_settings().remote_assistance_pairing_secret.get_secret_value().encode("utf-8"),
        message,
        "sha256",
    )
    value = int.from_bytes(digest[:8], "big") >> 4
    characters = ["0"] * 12
    for index in range(11, -1, -1):
        characters[index] = _CROCKFORD_ALPHABET[value & 31]
        value >>= 5
    return "".join(characters)


def _uuid4_header(value: str | None, *, label: str) -> UUID:
    try:
        parsed = UUID(value or "")
    except (TypeError, ValueError) as exc:
        raise AuthError(f"{label} must be a UUID v4.") from exc
    if parsed.version != 4 or str(parsed) != value:
        raise AuthError(f"{label} must be a canonical UUID v4.")
    return parsed


def _timestamp(value: str | None, *, now: datetime) -> tuple[int, datetime]:
    try:
        epoch = int(value or "")
    except (TypeError, ValueError) as exc:
        raise AuthError("X-ERP-Device-Timestamp must be Unix epoch seconds.") from exc
    if str(epoch) != value:
        raise AuthError("X-ERP-Device-Timestamp must use canonical Unix epoch seconds.")
    try:
        signed_at = datetime.fromtimestamp(epoch, tz=UTC)
    except (OverflowError, OSError, ValueError) as exc:
        raise AuthError("X-ERP-Device-Timestamp is outside the supported range.") from exc
    if (
        abs((now - signed_at).total_seconds())
        > get_settings().remote_assistance_device_signature_max_skew_seconds
    ):
        raise AuthError("The device request signature timestamp is outside the allowed clock skew.")
    return epoch, signed_at


def _content_hash(value: str | None) -> str:
    if value is None or len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise AuthError("X-ERP-Content-SHA256 must be 64 lowercase hexadecimal digits.")
    return value


def _verify_signature(
    public_key: ec.EllipticCurvePublicKey,
    statement: bytes,
    signature_text: str | None,
) -> None:
    try:
        signature = _b64url_decode(
            signature_text or "",
            label="device signature",
            max_bytes=_MAX_SIGNATURE_BYTES,
        )
        public_key.verify(signature, statement, ec.ECDSA(hashes.SHA256()))
    except ValidationError as exc:
        raise AuthError("The device request signature is invalid.") from exc
    except (InvalidSignature, ValueError) as exc:
        raise AuthError("The device request signature is invalid.") from exc


def verify_enrollment_signature(
    *,
    public_key: ParsedDevicePublicKey,
    statement: bytes,
    signature: str,
) -> None:
    _verify_signature(public_key.key, statement, signature)


async def authenticate_enrollment_request(
    *,
    company_id: UUID,
    installation_id: UUID,
    key_id: UUID,
    enrollment_id: UUID,
    signed_at_epoch_seconds: int,
    nonce: UUID,
    public_key: ParsedDevicePublicKey,
    signature: str,
    now: datetime | None = None,
) -> datetime:
    """Verify the new key's enrollment statement before any row is created."""

    now = now or datetime.now(UTC)
    _, signed_at = _timestamp(str(signed_at_epoch_seconds), now=now)
    statement = canonical_enrollment_statement(
        company_id=company_id,
        installation_id=installation_id,
        key_id=key_id,
        enrollment_id=enrollment_id,
        signed_at_epoch_seconds=signed_at_epoch_seconds,
        nonce=nonce,
        fingerprint_sha256=public_key.fingerprint_sha256,
    )
    verify_enrollment_signature(
        public_key=public_key,
        statement=statement,
        signature=signature,
    )
    await claim_device_nonce(
        company_id=company_id,
        key_id=key_id,
        nonce=nonce,
        purpose="enrollment",
    )
    return signed_at


async def claim_device_nonce(
    *,
    company_id: UUID,
    key_id: UUID,
    nonce: UUID,
    purpose: str,
) -> None:
    """Claim a signed nonce exactly once, failing closed when Redis is absent."""

    digest = hashlib.sha256(
        f"{company_id}:{key_id}:{nonce}:{purpose}".encode("ascii")
    ).hexdigest()
    key = f"dcompany:remote-assistance:device-proof:{digest}"
    client = request_path_redis_client(get_settings().redis_url)
    try:
        claimed = await client.set(
            key,
            "1",
            ex=get_settings().remote_assistance_device_nonce_ttl_seconds,
            nx=True,
        )
    except RedisError as exc:
        log.error("remote_assistance.device_proof_unavailable", error=type(exc).__name__)
        raise ServiceUnavailableError(
            "Remote device authentication is temporarily unavailable."
        ) from exc
    finally:
        await close_request_path_redis_client(client)
    if not claimed:
        raise AuthError("The device request nonce was already used.")


def verify_actual_content(proof: AuthenticatedDeviceRequest, content: bytes) -> None:
    actual = hashlib.sha256(content).hexdigest()
    if not hmac.compare_digest(actual, proof.expected_content_sha256):
        raise AuthError("The device request content hash does not match its signed body.")


async def authenticate_device_request(
    *,
    request: Request,
    session: AsyncSession,
    company_id: UUID,
    client_installation_id: UUID,
    allowed_statuses: frozenset[str] = frozenset({"active"}),
    actual_content: bytes | None,
    now: datetime | None = None,
) -> AuthenticatedDeviceRequest:
    """Verify key scope, exact request target/body claim, signature, and nonce."""

    now = now or datetime.now(UTC)
    key_id = _uuid4_header(
        request.headers.get("X-ERP-Device-Key-Id"),
        label="X-ERP-Device-Key-Id",
    )
    nonce = _uuid4_header(
        request.headers.get("X-ERP-Device-Nonce"),
        label="X-ERP-Device-Nonce",
    )
    epoch, signed_at = _timestamp(request.headers.get("X-ERP-Device-Timestamp"), now=now)
    content_sha256 = _content_hash(request.headers.get("X-ERP-Content-SHA256"))
    row = (
        await session.execute(
            select(RemoteAssistanceDeviceKey).where(
                RemoteAssistanceDeviceKey.company_id == company_id,
                RemoteAssistanceDeviceKey.client_installation_id == client_installation_id,
                RemoteAssistanceDeviceKey.id == key_id,
            )
        )
    ).scalar_one_or_none()
    if row is None or row.status not in allowed_statuses:
        raise AuthError("An approved device key is required for this request.")
    if row.status == "pending" and row.pending_expires_at <= now:
        raise AuthError("The pending device key enrollment expired.")
    try:
        loaded = serialization.load_der_public_key(row.public_key_spki)
    except (TypeError, ValueError) as exc:
        raise AuthError("The registered device key is invalid.") from exc
    if not isinstance(loaded, ec.EllipticCurvePublicKey) or not isinstance(
        loaded.curve, ec.SECP256R1
    ):
        raise AuthError("The registered device key is invalid.")
    proof = AuthenticatedDeviceRequest(
        device_key=row,
        expected_content_sha256=content_sha256,
        nonce=nonce,
        signed_at=signed_at,
    )
    if actual_content is not None:
        verify_actual_content(proof, actual_content)
    statement = canonical_request_statement(
        method=request.method,
        raw_target=canonical_raw_target(request),
        content_sha256=content_sha256,
        signed_at_epoch_seconds=epoch,
        nonce=nonce,
        key_id=key_id,
    )
    _verify_signature(loaded, statement, request.headers.get("X-ERP-Device-Signature"))
    await claim_device_nonce(
        company_id=company_id,
        key_id=key_id,
        nonce=nonce,
        purpose="request",
    )
    return proof


def empty_content_sha256() -> str:
    return _EMPTY_SHA256


__all__ = [
    "AuthenticatedDeviceRequest",
    "ENROLLMENT_SIGNATURE_DOMAIN",
    "ParsedDevicePublicKey",
    "REQUEST_SIGNATURE_DOMAIN",
    "authenticate_device_request",
    "authenticate_enrollment_request",
    "canonical_enrollment_statement",
    "canonical_raw_target",
    "canonical_request_statement",
    "claim_device_nonce",
    "empty_content_sha256",
    "pairing_code",
    "parse_p256_spki",
    "verify_actual_content",
    "verify_enrollment_signature",
]
