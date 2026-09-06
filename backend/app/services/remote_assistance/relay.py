"""Fail-closed, Redis-only latest-frame relay.

No screenshot path, object-storage key, database blob, or durable queue exists.
The relay accepts only a decoded, bounded JPEG that already passed the Android
ERP-window privacy pipeline, strips metadata by re-encoding, and replaces the
single latest frame under a short Redis TTL.
"""

from __future__ import annotations

import base64
import hashlib
import io
import os
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, cast
from uuid import UUID

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, UnidentifiedImageError
from redis.exceptions import RedisError

from app.core.config import get_settings
from app.core.errors import ConflictError, RateLimitError, ServiceUnavailableError, ValidationError
from app.core.logging import get_logger
from app.core.redis_clients import (
    close_request_path_redis_client,
    request_path_redis_binary_client,
)

if TYPE_CHECKING:
    from collections.abc import Mapping
    from typing import Any

log = get_logger(__name__)

# Portrait app-window sharing can preserve aspect ratio at roughly 256x540.
# Keep the floor low enough for that legitimate frame while the independent
# byte and maximum-dimension ceilings still bound decode cost.
_MIN_WIDTH = 240
_MIN_HEIGHT = 180
_RATE_WINDOW_MS = 1_000
_SEQUENCE_TTL_SECONDS = 20 * 60
_RELAY_ENVELOPE_DOMAIN = b"D-COMPANY-ERP-REMOTE-FRAME-V1"
_RELAY_ENVELOPE_MAGIC = b"DCRF"
_RELAY_ENVELOPE_VERSION = 1
_AES_GCM_NONCE_BYTES = 12
_AES_GCM_TAG_BYTES = 16

_ADMIT_FRAME_DECODE = """
local prior = redis.call('GET', KEYS[2])
if prior and tonumber(ARGV[3]) <= tonumber(prior) then
  return {2, tonumber(prior)}
end

local admitted = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
if not admitted then
  return {0, redis.call('PTTL', KEYS[1])}
end
return {1, 0}
"""

_STORE_LATEST_FRAME = """
local now = redis.call('TIME')
local now_ms = (tonumber(now[1]) * 1000) + math.floor(tonumber(now[2]) / 1000)
local prior = redis.call('GET', KEYS[2])
if prior and tonumber(ARGV[3]) <= tonumber(prior) then
  return {2, tonumber(prior)}
end

local cutoff = now_ms - tonumber(ARGV[1])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
local count = redis.call('ZCARD', KEYS[1])
if count >= tonumber(ARGV[2]) then
  local ttl = redis.call('PTTL', KEYS[1])
  return {0, ttl}
end

redis.call('ZADD', KEYS[1], now_ms, ARGV[4])
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1]) * 2)
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[5])
redis.call('SET', KEYS[3], ARGV[6], 'EX', ARGV[7])
redis.call('HSET', KEYS[4],
  'frame_id', ARGV[8],
  'sequence', ARGV[3],
  'width', ARGV[9],
  'height', ARGV[10],
  'received_at', ARGV[11],
  'version', ARGV[12],
  'ttl_seconds', ARGV[7])
redis.call('EXPIRE', KEYS[4], ARGV[7])
return {1, now_ms}
"""

_DELETE_INVALID_FRAME = """
local current = redis.call('GET', KEYS[1])
if ARGV[1] == 'missing' then
  if not current then
    return redis.call('DEL', KEYS[2])
  end
  return 0
end
if current == ARGV[2] then
  return redis.call('DEL', KEYS[1], KEYS[2])
end
return 0
"""


@dataclass(frozen=True, slots=True)
class ValidatedJpeg:
    content: bytes
    width: int
    height: int


@dataclass(frozen=True, slots=True)
class FrameMetadata:
    frame_id: UUID
    sequence: int
    width: int
    height: int
    received_at: datetime
    expires_at: datetime


@dataclass(frozen=True, slots=True)
class RelayedFrame:
    content: bytes
    metadata: FrameMetadata


@dataclass(frozen=True, slots=True)
class _DecodedRelayMetadata:
    metadata: FrameMetadata
    received_at_text: str
    version: int
    ttl_seconds: int


def _relay_digest(company_id: UUID, session_id: UUID) -> str:
    return hashlib.sha256(f"{company_id}:{session_id}".encode("ascii")).hexdigest()


def _relay_keys(company_id: UUID, session_id: UUID) -> tuple[str, str, str, str]:
    digest = _relay_digest(company_id, session_id)
    prefix = f"dcompany:remote-assistance:{digest}"
    return (
        f"{prefix}:rate",
        f"{prefix}:sequence",
        f"{prefix}:frame",
        f"{prefix}:metadata",
    )


def _decode_admission_key(company_id: UUID, session_id: UUID) -> str:
    digest = _relay_digest(company_id, session_id)
    return f"dcompany:remote-assistance:{digest}:decode-admission"


def _relay_key() -> bytes:
    # Settings validation enforces canonical 32-byte base64 material. Decode at
    # the cryptographic boundary so the SecretStr never leaves this module.
    return base64.b64decode(
        get_settings().remote_assistance_relay_secret.get_secret_value(),
        validate=True,
    )


def _relay_time(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("relay timestamps must include a timezone")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _frame_aad(
    *,
    company_id: UUID,
    session_id: UUID,
    frame_id: UUID,
    sequence: int,
    width: int,
    height: int,
    received_at_text: str,
    ttl_seconds: int,
    version: int,
) -> bytes:
    return b"\n".join(
        (
            _RELAY_ENVELOPE_DOMAIN,
            str(version).encode("ascii"),
            str(company_id).encode("ascii"),
            str(session_id).encode("ascii"),
            str(frame_id).encode("ascii"),
            str(sequence).encode("ascii"),
            str(width).encode("ascii"),
            str(height).encode("ascii"),
            received_at_text.encode("ascii"),
            str(ttl_seconds).encode("ascii"),
        )
    )


def _seal_frame(
    *,
    company_id: UUID,
    session_id: UUID,
    frame_id: UUID,
    sequence: int,
    frame: ValidatedJpeg,
    received_at: datetime,
    ttl_seconds: int,
) -> tuple[bytes, str]:
    received_at_text = _relay_time(received_at)
    aad = _frame_aad(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame_id,
        sequence=sequence,
        width=frame.width,
        height=frame.height,
        received_at_text=received_at_text,
        ttl_seconds=ttl_seconds,
        version=_RELAY_ENVELOPE_VERSION,
    )
    nonce = os.urandom(_AES_GCM_NONCE_BYTES)
    ciphertext = AESGCM(_relay_key()).encrypt(nonce, frame.content, aad)
    return (
        _RELAY_ENVELOPE_MAGIC + bytes((_RELAY_ENVELOPE_VERSION,)) + nonce + ciphertext,
        received_at_text,
    )


def _open_frame(
    *,
    company_id: UUID,
    session_id: UUID,
    envelope: bytes,
    metadata: _DecodedRelayMetadata,
) -> bytes:
    settings = get_settings()
    minimum = len(_RELAY_ENVELOPE_MAGIC) + 1 + _AES_GCM_NONCE_BYTES + _AES_GCM_TAG_BYTES
    maximum = (
        len(_RELAY_ENVELOPE_MAGIC)
        + 1
        + _AES_GCM_NONCE_BYTES
        + _AES_GCM_TAG_BYTES
        + settings.remote_assistance_frame_max_bytes
    )
    if (
        len(envelope) < minimum
        or len(envelope) > maximum
        or not envelope.startswith(_RELAY_ENVELOPE_MAGIC)
        or envelope[len(_RELAY_ENVELOPE_MAGIC)] != metadata.version
    ):
        raise ServiceUnavailableError(
            "The ephemeral support-frame relay returned an invalid encrypted envelope."
        )
    nonce_start = len(_RELAY_ENVELOPE_MAGIC) + 1
    nonce_end = nonce_start + _AES_GCM_NONCE_BYTES
    nonce = envelope[nonce_start:nonce_end]
    ciphertext = envelope[nonce_end:]
    frame = metadata.metadata
    aad = _frame_aad(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame.frame_id,
        sequence=frame.sequence,
        width=frame.width,
        height=frame.height,
        received_at_text=metadata.received_at_text,
        ttl_seconds=metadata.ttl_seconds,
        version=metadata.version,
    )
    try:
        content = AESGCM(_relay_key()).decrypt(nonce, ciphertext, aad)
    except (InvalidTag, ValueError) as exc:
        raise ServiceUnavailableError(
            "The ephemeral support-frame relay failed integrity verification."
        ) from exc
    if (
        not content.startswith(b"\xff\xd8")
        or not content.endswith(b"\xff\xd9")
        or len(content) > settings.remote_assistance_frame_max_bytes
    ):
        raise ServiceUnavailableError(
            "The ephemeral support-frame relay returned invalid decrypted content."
        )
    return content


def validate_and_sanitize_jpeg(
    content: bytes,
    *,
    declared_width: int,
    declared_height: int,
) -> ValidatedJpeg:
    """Decode a bounded JPEG and re-encode it without EXIF/comments/profiles."""

    settings = get_settings()
    if not content or len(content) > settings.remote_assistance_frame_max_bytes:
        raise ValidationError(
            "The support frame is empty or exceeds the configured byte limit.",
            details={"max_bytes": settings.remote_assistance_frame_max_bytes},
        )
    if not (
        _MIN_WIDTH <= declared_width <= settings.remote_assistance_frame_max_width
        and _MIN_HEIGHT <= declared_height <= settings.remote_assistance_frame_max_height
    ):
        raise ValidationError(
            "The support frame dimensions are outside the permitted tablet range.",
            details={
                "min_width": _MIN_WIDTH,
                "min_height": _MIN_HEIGHT,
                "max_width": settings.remote_assistance_frame_max_width,
                "max_height": settings.remote_assistance_frame_max_height,
            },
        )
    if not content.startswith(b"\xff\xd8") or not content.endswith(b"\xff\xd9"):
        raise ValidationError("The support frame must be a complete JPEG image.")

    try:
        with Image.open(io.BytesIO(content)) as image:
            if image.format != "JPEG":
                raise ValidationError("The support frame must be encoded as JPEG.")
            if image.size != (declared_width, declared_height):
                raise ValidationError(
                    "The support frame dimensions do not match its declared dimensions."
                )
            # Width/height are checked before decoding so a compressed image
            # cannot allocate an unbounded pixel buffer.
            image.load()
            rgb = image.convert("RGB")
            clean = io.BytesIO()
            rgb.save(clean, format="JPEG", quality=80, optimize=True, progressive=False)
    except ValidationError:
        raise
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as exc:
        raise ValidationError("The support frame is not a safe, decodable JPEG image.") from exc

    sanitized = clean.getvalue()
    if len(sanitized) > settings.remote_assistance_frame_max_bytes:
        raise ValidationError(
            "The sanitized support frame exceeds the configured byte limit.",
            details={"max_bytes": settings.remote_assistance_frame_max_bytes},
        )
    return ValidatedJpeg(content=sanitized, width=declared_width, height=declared_height)


async def ensure_relay_available() -> None:
    """Require Redis before a viewer may start or issue a support command."""

    client = request_path_redis_binary_client(get_settings().redis_url)
    try:
        if not await client.ping():
            raise RedisError("ping returned false")
    except RedisError as exc:
        log.error("remote_assistance.relay_unavailable", error=type(exc).__name__)
        raise ServiceUnavailableError(
            "Remote assistance is unavailable because the ephemeral relay is not ready."
        ) from exc
    finally:
        await close_request_path_redis_client(client)


async def admit_frame_upload(
    *,
    company_id: UUID,
    session_id: UUID,
    frame_id: UUID,
    sequence: int,
) -> None:
    """Bound authenticated frame CPU admission before reading or decoding."""

    settings = get_settings()
    sequence_key = _relay_keys(company_id, session_id)[1]
    admission_key = _decode_admission_key(company_id, session_id)
    member = hashlib.sha256(f"{frame_id}:{sequence}".encode("ascii")).hexdigest()
    client = request_path_redis_binary_client(settings.redis_url)
    try:
        result = await cast("Any", client).eval(
            _ADMIT_FRAME_DECODE,
            2,
            admission_key,
            sequence_key,
            member,
            settings.remote_assistance_frame_decode_min_interval_ms,
            sequence,
        )
        code = int(result[0])
        evidence = int(result[1])
    except (RedisError, TypeError, ValueError, IndexError) as exc:
        log.error("remote_assistance.frame_admission_unavailable", error=type(exc).__name__)
        raise ServiceUnavailableError(
            "The ephemeral support-frame relay is unavailable; screen sharing stopped."
        ) from exc
    finally:
        await close_request_path_redis_client(client)

    if code == 0:
        retry_ms = max(
            1,
            (evidence if evidence > 0 else settings.remote_assistance_frame_decode_min_interval_ms),
        )
        retry_seconds = max(1, (retry_ms + 999) // 1_000)
        raise RateLimitError(
            "Support frame decoding was requested faster than the safe admission rate.",
            details={
                "minimum_interval_ms": settings.remote_assistance_frame_decode_min_interval_ms,
                "retry_after_seconds": retry_seconds,
            },
            headers={"Retry-After": str(retry_seconds)},
        )
    if code == 2:
        raise ConflictError(
            "The support frame sequence was already used or is older than the latest frame.",
            details={"latest_sequence": evidence},
        )
    if code != 1:
        raise ServiceUnavailableError("The ephemeral support-frame relay rejected frame admission.")


async def store_latest_frame(
    *,
    company_id: UUID,
    session_id: UUID,
    frame_id: UUID,
    sequence: int,
    frame: ValidatedJpeg,
) -> FrameMetadata:
    settings = get_settings()
    keys = _relay_keys(company_id, session_id)
    received_at = datetime.now(UTC)
    envelope, received_at_text = _seal_frame(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame_id,
        sequence=sequence,
        frame=frame,
        received_at=received_at,
        ttl_seconds=settings.remote_assistance_frame_ttl_seconds,
    )
    # The sequence is part of the rate-window member so reusing a frame UUID
    # cannot collapse multiple admissions into one ZSET entry and bypass the
    # configured per-session frame rate.
    member = hashlib.sha256(f"{frame_id}:{sequence}".encode("ascii")).hexdigest()
    client = request_path_redis_binary_client(settings.redis_url)
    try:
        result = await cast("Any", client).eval(
            _STORE_LATEST_FRAME,
            len(keys),
            *keys,
            _RATE_WINDOW_MS,
            settings.remote_assistance_frame_rate_per_second,
            sequence,
            member,
            _SEQUENCE_TTL_SECONDS,
            envelope,
            settings.remote_assistance_frame_ttl_seconds,
            str(frame_id),
            frame.width,
            frame.height,
            received_at_text,
            _RELAY_ENVELOPE_VERSION,
        )
        code = int(result[0])
        evidence = int(result[1])
    except (RedisError, TypeError, ValueError, IndexError) as exc:
        log.error("remote_assistance.frame_store_unavailable", error=type(exc).__name__)
        raise ServiceUnavailableError(
            "The ephemeral support-frame relay is unavailable; screen sharing stopped."
        ) from exc
    finally:
        await close_request_path_redis_client(client)

    if code == 0:
        retry_ms = max(1, evidence if evidence > 0 else _RATE_WINDOW_MS)
        retry_seconds = max(1, (retry_ms + 999) // 1_000)
        raise RateLimitError(
            "Support frames are arriving faster than the permitted rate.",
            details={
                "limit_per_second": settings.remote_assistance_frame_rate_per_second,
                "retry_after_seconds": retry_seconds,
            },
            headers={"Retry-After": str(retry_seconds)},
        )
    if code == 2:
        raise ConflictError(
            "The support frame sequence was already used or is older than the latest frame.",
            details={"latest_sequence": evidence},
        )
    if code != 1:
        raise ServiceUnavailableError("The ephemeral support-frame relay rejected the frame.")

    return FrameMetadata(
        frame_id=frame_id,
        sequence=sequence,
        width=frame.width,
        height=frame.height,
        received_at=received_at,
        expires_at=received_at + timedelta(seconds=settings.remote_assistance_frame_ttl_seconds),
    )


def _decode_metadata(raw: Mapping[bytes, bytes]) -> _DecodedRelayMetadata:
    try:
        received_at_text = raw[b"received_at"].decode("ascii")
        received_at = datetime.fromisoformat(received_at_text.replace("Z", "+00:00"))
        if received_at.tzinfo is None:
            raise ValueError("naive relay time")
        received_at = received_at.astimezone(UTC)
        if _relay_time(received_at) != received_at_text:
            raise ValueError("non-canonical relay time")
        version = int(raw[b"version"])
        ttl_seconds = int(raw[b"ttl_seconds"])
        settings = get_settings()
        if (
            version != _RELAY_ENVELOPE_VERSION
            or ttl_seconds != settings.remote_assistance_frame_ttl_seconds
        ):
            raise ValueError("unsupported relay envelope policy")
        metadata = FrameMetadata(
            frame_id=UUID(raw[b"frame_id"].decode("ascii")),
            sequence=int(raw[b"sequence"]),
            width=int(raw[b"width"]),
            height=int(raw[b"height"]),
            received_at=received_at,
            expires_at=received_at + timedelta(seconds=ttl_seconds),
        )
        if (
            metadata.sequence < 1
            or not (240 <= metadata.width <= settings.remote_assistance_frame_max_width)
            or not (180 <= metadata.height <= settings.remote_assistance_frame_max_height)
        ):
            raise ValueError("relay metadata outside configured bounds")
        return _DecodedRelayMetadata(
            metadata=metadata,
            received_at_text=received_at_text,
            version=version,
            ttl_seconds=ttl_seconds,
        )
    except (KeyError, UnicodeDecodeError, TypeError, ValueError) as exc:
        raise ServiceUnavailableError(
            "The ephemeral support-frame relay returned invalid metadata."
        ) from exc


async def _delete_invalid_frame(
    client: object,
    *,
    frame_key: str,
    metadata_key: str,
    expected_envelope: bytes | None,
) -> None:
    """Delete only the invalid snapshot read by this request, never a newer frame."""

    try:
        await cast("Any", client).eval(
            _DELETE_INVALID_FRAME,
            2,
            frame_key,
            metadata_key,
            "missing" if expected_envelope is None else "present",
            expected_envelope or b"",
        )
    except RedisError:
        # Retrieval already fails closed. Cleanup is best-effort and the
        # authenticated wall-clock check prevents an extended Redis TTL from
        # reviving the snapshot on a later read.
        log.warning("remote_assistance.invalid_frame_cleanup_unavailable")


async def get_latest_frame(*, company_id: UUID, session_id: UUID) -> RelayedFrame | None:
    keys = _relay_keys(company_id, session_id)
    client = request_path_redis_binary_client(get_settings().redis_url)
    try:
        # Read bytes and metadata from one Redis snapshot; otherwise a writer
        # could replace the latest frame between the two reads.
        pipe = client.pipeline(transaction=True)
        pipe.get(keys[2])
        pipe.hgetall(keys[3])
        content, raw_metadata = await pipe.execute()
    except (RedisError, TypeError, ValueError) as exc:
        log.error("remote_assistance.frame_read_unavailable", error=type(exc).__name__)
        await close_request_path_redis_client(client)
        raise ServiceUnavailableError(
            "The ephemeral support-frame relay is unavailable; viewing stopped."
        ) from exc

    try:
        if content is None or not raw_metadata:
            await _delete_invalid_frame(
                client,
                frame_key=keys[2],
                metadata_key=keys[3],
                expected_envelope=content if isinstance(content, bytes) else None,
            )
            return None
        if not isinstance(content, bytes) or not isinstance(raw_metadata, dict):
            await _delete_invalid_frame(
                client,
                frame_key=keys[2],
                metadata_key=keys[3],
                expected_envelope=content if isinstance(content, bytes) else None,
            )
            raise ServiceUnavailableError(
                "The ephemeral support-frame relay returned invalid data."
            )
        try:
            decoded_metadata = _decode_metadata(raw_metadata)
            plaintext = _open_frame(
                company_id=company_id,
                session_id=session_id,
                envelope=content,
                metadata=decoded_metadata,
            )
        except ServiceUnavailableError:
            await _delete_invalid_frame(
                client,
                frame_key=keys[2],
                metadata_key=keys[3],
                expected_envelope=content,
            )
            raise

        now = datetime.now(UTC)
        if decoded_metadata.metadata.received_at > now + timedelta(seconds=1):
            await _delete_invalid_frame(
                client,
                frame_key=keys[2],
                metadata_key=keys[3],
                expected_envelope=content,
            )
            raise ServiceUnavailableError(
                "The ephemeral support-frame relay returned a future-dated frame."
            )
        if now >= decoded_metadata.metadata.expires_at:
            await _delete_invalid_frame(
                client,
                frame_key=keys[2],
                metadata_key=keys[3],
                expected_envelope=content,
            )
            return None
        return RelayedFrame(content=plaintext, metadata=decoded_metadata.metadata)
    finally:
        await close_request_path_redis_client(client)


async def delete_latest_frame(*, company_id: UUID, session_id: UUID) -> None:
    """Best-effort early eviction; DB state remains the retrieval authority."""

    keys = _relay_keys(company_id, session_id)
    client = request_path_redis_binary_client(get_settings().redis_url)
    try:
        await client.delete(keys[2], keys[3])
    except RedisError:
        # Retrieval checks session state before Redis and therefore fails closed
        # even if this optimization cannot evict the already-short TTL early.
        return
    finally:
        await close_request_path_redis_client(client)


__all__ = [
    "FrameMetadata",
    "RelayedFrame",
    "ValidatedJpeg",
    "admit_frame_upload",
    "delete_latest_frame",
    "ensure_relay_available",
    "get_latest_frame",
    "store_latest_frame",
    "validate_and_sanitize_jpeg",
]
