"""Durable, authenticated dispatcher for the append-only ERP Mirror v1 sink.

This module intentionally has no router or event-bus wiring. Callers enqueue in
their existing database transaction; a separately scheduled worker dispatches
committed rows and records the durable outcome.
"""

from __future__ import annotations

import hashlib
import hmac
import json
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from urllib.parse import urljoin, urlsplit
from uuid import NAMESPACE_URL, UUID, uuid4, uuid5

import httpx
from sqlalchemy import and_, exists, or_, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import aliased

from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.models.tenant import Company

MIRROR_SCHEMA = "erp-mirror.v1"
MIRROR_SCHEMA_VERSION = 1
CONNECTION_TEST_EVENT_TYPE = "mirror.connection_test"
CONNECTION_TEST_SOURCE_TYPE = "mirror_configuration"
# Google Sheets cells are limited to 50,000 characters. A byte ceiling below
# that limit keeps the canonical JSON appendable even when it is all ASCII.
MAX_CANONICAL_PAYLOAD_BYTES = 45 * 1024
MAX_RESPONSE_BYTES = 16 * 1024


class EventIdentityConflictError(ValueError):
    """A deterministic event identity was reused for different evidence."""


class DestinationValidationError(ValueError):
    """A configured webhook destination is outside the approved HTTPS hosts."""


@dataclass(frozen=True, slots=True)
class MirrorDestination:
    webhook_url: str
    signing_secret: str


@dataclass(frozen=True, slots=True)
class DispatcherConfig:
    allowed_hosts: frozenset[str] = field(
        default_factory=lambda: frozenset(
            {"script.google.com", "script.googleusercontent.com"}
        )
    )
    # Dispatch is deliberately sequential, so the lease covers the worst-case
    # redirect/timeout budget for every row in one batch.
    batch_size: int = 5
    lease_seconds: int = 300
    request_timeout_seconds: float = 10.0
    max_redirects: int = 3
    max_attempts: int = 8
    retry_base_seconds: int = 30
    retry_cap_seconds: int = 6 * 60 * 60


@dataclass(frozen=True, slots=True)
class ClaimedDelivery:
    id: UUID
    company_id: UUID
    configuration_id: UUID
    event_id: UUID
    event_key: str
    event_type: str
    source_type: str
    source_id: str
    source_revision: str
    schema_version: int
    payload: dict[str, object]
    payload_sha256: str
    occurred_at: datetime
    attempt_count: int


@dataclass(frozen=True, slots=True)
class DeliveryOutcome:
    delivered: bool
    retryable: bool
    error_code: str | None = None
    error_detail: str | None = None

    @classmethod
    def success(cls) -> DeliveryOutcome:
        return cls(delivered=True, retryable=False)

    @classmethod
    def failure(
        cls, *, retryable: bool, error_code: str, error_detail: str
    ) -> DeliveryOutcome:
        return cls(
            delivered=False,
            retryable=retryable,
            error_code=_bounded_text(error_code, 80),
            error_detail=_bounded_text(error_detail, 500),
        )


DestinationResolver = Callable[
    [AsyncSession, UUID, UUID], Awaitable[MirrorDestination | None]
]


def _bounded_text(value: str, limit: int) -> str:
    return " ".join(str(value).split())[:limit]


def _required_text(name: str, value: str, limit: int) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > limit:
        raise ValueError(f"{name} must contain 1 to {limit} characters")
    return normalized


def _aware_utc(name: str, value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{name} must include a timezone")
    return value.astimezone(UTC)


def _validate_json_value(value: object, *, depth: int = 0) -> None:
    if depth > 32:
        raise ValueError("payload nesting exceeds 32 levels")
    if value is None or isinstance(value, (str, bool)):
        return
    if isinstance(value, int):
        # Apps Script parses JSON numbers as IEEE-754 doubles. Staying inside
        # this range prevents a signed amount or count changing before it is
        # projected into a cell.
        if not -(2**53 - 1) <= value <= 2**53 - 1:
            raise ValueError("payload integers must be JavaScript-safe integers")
        return
    if isinstance(value, float):
        raise ValueError("payload floats are forbidden; use integer minor units")
    if isinstance(value, list):
        for item in value:
            _validate_json_value(item, depth=depth + 1)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("payload object keys must be strings")
            _validate_json_value(item, depth=depth + 1)
        return
    raise ValueError(f"payload contains unsupported type: {type(value).__name__}")


def canonical_json(value: object) -> str:
    """Return the only JSON representation used for hashing and signing."""

    _validate_json_value(value)
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (UnicodeEncodeError, ValueError) as exc:
        raise ValueError("payload is not valid UTF-8 JSON") from exc
    if len(encoded) > MAX_CANONICAL_PAYLOAD_BYTES:
        raise ValueError("canonical payload exceeds 45 KiB")
    return encoded.decode("utf-8")


def payload_sha256(payload: Mapping[str, object]) -> str:
    return hashlib.sha256(canonical_json(dict(payload)).encode("utf-8")).hexdigest()


def make_event_key(
    *,
    event_type: str,
    source_type: str,
    source_id: str,
    source_revision: str,
    schema_version: int = MIRROR_SCHEMA_VERSION,
) -> str:
    """Derive a collision-resistant key from immutable source identity."""

    if not 1 <= schema_version <= 32767:
        raise ValueError("schema_version must be between 1 and 32767")
    identity = {
        "event_type": _required_text("event_type", event_type, 80),
        "schema_version": schema_version,
        "source_id": _required_text("source_id", source_id, 200),
        "source_revision": _required_text("source_revision", source_revision, 100),
        "source_type": _required_text("source_type", source_type, 80),
    }
    digest = hashlib.sha256(canonical_json(identity).encode("utf-8")).hexdigest()
    return f"erp-mirror:v{schema_version}:{digest}"


def make_event_id(*, company_id: UUID, event_key: str) -> UUID:
    return uuid5(
        NAMESPACE_URL,
        f"https://erp.dcompany.invalid/google-sheets/{company_id}/{event_key}",
    )


async def enqueue_google_sheets_event(
    session: AsyncSession,
    *,
    company_id: UUID,
    configuration_id: UUID,
    event_type: str,
    source_type: str,
    source_id: str,
    source_revision: str,
    occurred_at: datetime,
    payload: Mapping[str, object],
    schema_version: int = MIRROR_SCHEMA_VERSION,
    available_at: datetime | None = None,
) -> GoogleSheetsDelivery:
    """Insert idempotently without committing the caller-owned transaction.

    A repeated source identity is accepted only when every immutable field and
    the canonical payload hash are identical. This turns accidental key reuse
    into a visible transaction failure instead of silent financial data loss.
    """

    event_type = _required_text("event_type", event_type, 80)
    source_type = _required_text("source_type", source_type, 80)
    source_id = _required_text("source_id", source_id, 200)
    source_revision = _required_text("source_revision", source_revision, 100)
    occurred_at = _aware_utc("occurred_at", occurred_at)
    available_at = _aware_utc(
        "available_at", available_at or datetime.now(UTC)
    )
    payload_json = canonical_json(dict(payload))
    canonical_payload = json.loads(payload_json)
    if not isinstance(canonical_payload, dict):  # defensive; Mapping should guarantee this
        raise ValueError("payload must be a JSON object")
    payload_hash = hashlib.sha256(payload_json.encode("utf-8")).hexdigest()
    event_key = make_event_key(
        event_type=event_type,
        source_type=source_type,
        source_id=source_id,
        source_revision=source_revision,
        schema_version=schema_version,
    )
    event_id = make_event_id(company_id=company_id, event_key=event_key)
    row_id = uuid4()

    statement = (
        pg_insert(GoogleSheetsDelivery)
        .values(
            id=row_id,
            company_id=company_id,
            configuration_id=configuration_id,
            event_id=event_id,
            event_key=event_key,
            event_type=event_type,
            source_type=source_type,
            source_id=source_id,
            source_revision=source_revision,
            schema_version=schema_version,
            payload=canonical_payload,
            payload_sha256=payload_hash,
            occurred_at=occurred_at,
            available_at=available_at,
            status="pending",
            attempt_count=0,
        )
        .on_conflict_do_nothing(
            constraint="uq_google_sheets_deliveries_company_event_key"
        )
        .returning(GoogleSheetsDelivery.id)
    )
    inserted_id = (await session.execute(statement)).scalar_one_or_none()
    if inserted_id is not None:
        inserted = await session.get(GoogleSheetsDelivery, inserted_id)
        if inserted is None:  # pragma: no cover - database invariant
            raise RuntimeError("inserted Google Sheets delivery could not be loaded")
        return inserted

    existing = (
        await session.execute(
            select(GoogleSheetsDelivery).where(
                GoogleSheetsDelivery.company_id == company_id,
                GoogleSheetsDelivery.event_key == event_key,
            )
        )
    ).scalar_one()
    # The deterministic event identity describes the immutable business fact,
    # not whichever mirror configuration happened to be active when it was
    # first observed.  An idempotent replay after rotation must return that
    # original, generation-bound delivery.  It must never duplicate the fact,
    # retarget it to the new destination, or fail the replay solely because the
    # active configuration changed.
    immutable_match = (
        existing.event_id == event_id
        and existing.event_type == event_type
        and existing.source_type == source_type
        and existing.source_id == source_id
        and existing.source_revision == source_revision
        and existing.schema_version == schema_version
        and existing.payload_sha256 == payload_hash
        and _aware_utc("existing occurred_at", existing.occurred_at) == occurred_at
    )
    if not immutable_match:
        raise EventIdentityConflictError(
            "Google Sheets event identity was already used for different evidence"
        )
    return existing


async def enqueue_google_sheets_event_if_enabled(
    session: AsyncSession,
    *,
    company_id: UUID,
    event_type: str,
    source_type: str,
    source_id: str,
    source_revision: str,
    occurred_at: datetime,
    payload: Mapping[str, object],
    schema_version: int = MIRROR_SCHEMA_VERSION,
    available_at: datetime | None = None,
) -> GoogleSheetsDelivery | None:
    """Atomically enqueue for the active company mirror configuration.

    The helper deliberately performs no commit. A caller invokes it inside the
    business transaction so a rollback removes both the financial mutation and
    its mirror event. Ordinary business facts are durably retained even while
    the current destination/secret generation is awaiting its connection test;
    the dispatcher holds those rows until that exact configuration is verified.

    The query reads current database state instead of trusting a possibly cached
    ``Company`` entity. Its shared row lock lets concurrent business
    transactions proceed while preventing a destination or secret change
    between configuration selection and the caller's commit.
    """

    active_configuration = select(
        Company.google_sheets_configuration_id,
        Company.google_sheets_configured_at,
    ).where(
        Company.id == company_id,
        Company.deleted_at.is_(None),
        Company.google_sheets_mirror_enabled.is_(True),
        Company.google_sheets_webhook_url.is_not(None),
        Company.google_sheets_signing_secret_ciphertext.is_not(None),
        Company.google_sheets_configuration_id.is_not(None),
        Company.google_sheets_configured_at.is_not(None),
    )
    configuration = (
        await session.execute(active_configuration.with_for_update(read=True))
    ).one_or_none()
    if configuration is None:
        return None
    configuration_id, configured_at = configuration
    if configuration_id is None or configured_at is None:  # narrowed by SQL
        return None
    return await enqueue_google_sheets_event(
        session,
        company_id=company_id,
        configuration_id=configuration_id,
        event_type=event_type,
        source_type=source_type,
        source_id=source_id,
        source_revision=source_revision,
        occurred_at=occurred_at,
        payload=payload,
        schema_version=schema_version,
        available_at=available_at,
    )


def _delivery_dispatchable_clause():
    """SQL predicate that holds active business rows until verification."""

    current_company = aliased(Company)
    verification = aliased(GoogleSheetsDelivery)
    is_connection_test = and_(
        GoogleSheetsDelivery.event_type == CONNECTION_TEST_EVENT_TYPE,
        GoogleSheetsDelivery.source_type == CONNECTION_TEST_SOURCE_TYPE,
    )
    is_current_active_configuration = exists(
        select(1)
        .select_from(current_company)
        .where(
            current_company.id == GoogleSheetsDelivery.company_id,
            current_company.deleted_at.is_(None),
            current_company.google_sheets_mirror_enabled.is_(True),
            current_company.google_sheets_configuration_id
            == GoogleSheetsDelivery.configuration_id,
            current_company.google_sheets_webhook_url.is_not(None),
            current_company.google_sheets_signing_secret_ciphertext.is_not(None),
            current_company.google_sheets_configured_at.is_not(None),
        )
    )
    is_current_configuration_verified = exists(
        select(1)
        .select_from(current_company)
        .join(
            verification,
            and_(
                verification.company_id == current_company.id,
                verification.configuration_id
                == current_company.google_sheets_configuration_id,
            ),
        )
        .where(
            current_company.id == GoogleSheetsDelivery.company_id,
            current_company.deleted_at.is_(None),
            current_company.google_sheets_mirror_enabled.is_(True),
            current_company.google_sheets_configuration_id
            == GoogleSheetsDelivery.configuration_id,
            current_company.google_sheets_webhook_url.is_not(None),
            current_company.google_sheets_signing_secret_ciphertext.is_not(None),
            current_company.google_sheets_configured_at.is_not(None),
            verification.event_type == CONNECTION_TEST_EVENT_TYPE,
            verification.source_type == CONNECTION_TEST_SOURCE_TYPE,
            verification.status == "delivered",
            verification.occurred_at >= current_company.google_sheets_configured_at,
            verification.delivered_at >= current_company.google_sheets_configured_at,
        )
    )
    return or_(
        is_connection_test,
        ~is_current_active_configuration,
        is_current_configuration_verified,
    )


async def claim_delivery_batch(
    session: AsyncSession,
    *,
    worker_id: str,
    now: datetime,
    batch_size: int,
    lease_seconds: int,
) -> list[ClaimedDelivery]:
    """Lease due rows under PostgreSQL ``FOR UPDATE SKIP LOCKED``."""

    worker_id = _required_text("worker_id", worker_id, 128)
    now = _aware_utc("now", now)
    if not 1 <= batch_size <= 500:
        raise ValueError("batch_size must be between 1 and 500")
    if not 10 <= lease_seconds <= 3600:
        raise ValueError("lease_seconds must be between 10 and 3600")

    due = or_(
        and_(
            GoogleSheetsDelivery.status == "pending",
            GoogleSheetsDelivery.available_at <= now,
        ),
        and_(
            GoogleSheetsDelivery.status == "leased",
            GoogleSheetsDelivery.lease_expires_at <= now,
        ),
    )
    # A connection test is the only row allowed to leave an active, unverified
    # configuration. Ordinary rows stay pending without consuming attempts or
    # retry budget. Superseded rows remain claimable so the dispatcher can
    # quarantine them explicitly rather than leaving invisible pending work.
    rows = (
        (
            await session.execute(
                select(GoogleSheetsDelivery)
                .where(due, _delivery_dispatchable_clause())
                .order_by(
                    GoogleSheetsDelivery.available_at,
                    GoogleSheetsDelivery.occurred_at,
                    GoogleSheetsDelivery.id,
                )
                .limit(batch_size)
                .with_for_update(skip_locked=True)
            )
        )
        .scalars()
        .all()
    )
    lease_expires_at = now + timedelta(seconds=lease_seconds)
    claimed: list[ClaimedDelivery] = []
    for row in rows:
        row.status = "leased"
        row.lease_owner = worker_id
        row.lease_expires_at = lease_expires_at
        row.attempt_count += 1
        row.last_attempt_at = now
        claimed.append(
            ClaimedDelivery(
                id=row.id,
                company_id=row.company_id,
                configuration_id=row.configuration_id,
                event_id=row.event_id,
                event_key=row.event_key,
                event_type=row.event_type,
                source_type=row.source_type,
                source_id=row.source_id,
                source_revision=row.source_revision,
                schema_version=row.schema_version,
                payload=dict(row.payload),
                payload_sha256=row.payload_sha256,
                occurred_at=row.occurred_at,
                attempt_count=row.attempt_count,
            )
        )
    await session.flush()
    return claimed


def build_signed_body(delivery: ClaimedDelivery, *, signing_secret: str) -> bytes:
    secret_bytes = signing_secret.encode("utf-8")
    if not 32 <= len(secret_bytes) <= 4096:
        raise ValueError("signing secret must contain 32 to 4096 UTF-8 bytes")
    payload_json = canonical_json(delivery.payload)
    calculated_hash = hashlib.sha256(payload_json.encode("utf-8")).hexdigest()
    if not hmac.compare_digest(calculated_hash, delivery.payload_sha256):
        raise EventIdentityConflictError("stored payload does not match its SHA-256")
    signed_payload = canonical_json(
        {
            "company_id": str(delivery.company_id),
            "configuration_id": str(delivery.configuration_id),
            "event_id": str(delivery.event_id),
            "event_key": delivery.event_key,
            "event_type": delivery.event_type,
            "occurred_at": _aware_utc(
                "occurred_at", delivery.occurred_at
            ).isoformat().replace("+00:00", "Z"),
            "payload_json": payload_json,
            "payload_sha256": delivery.payload_sha256,
            "schema": MIRROR_SCHEMA,
            "schema_version": delivery.schema_version,
            "source_id": delivery.source_id,
            "source_revision": delivery.source_revision,
            "source_type": delivery.source_type,
        }
    )
    signature = hmac.new(
        secret_bytes, signed_payload.encode("utf-8"), hashlib.sha256
    ).hexdigest()
    return canonical_json(
        {
            "schema": MIRROR_SCHEMA,
            "signature": signature,
            "signature_algorithm": "HMAC-SHA256",
            "signed_payload": signed_payload,
        }
    ).encode("utf-8")


def validate_destination_url(url: str, *, allowed_hosts: frozenset[str]) -> str:
    if not allowed_hosts:
        raise DestinationValidationError("allowed host list is empty")
    parsed = urlsplit(url)
    hostname = (parsed.hostname or "").rstrip(".").lower()
    normalized_hosts = {host.rstrip(".").lower() for host in allowed_hosts}
    if parsed.scheme.lower() != "https":
        raise DestinationValidationError("destination must use HTTPS")
    if parsed.username is not None or parsed.password is not None:
        raise DestinationValidationError("destination must not contain user information")
    try:
        port = parsed.port
    except ValueError as exc:
        raise DestinationValidationError("destination port is invalid") from exc
    if port not in (None, 443):
        raise DestinationValidationError("destination must use the standard HTTPS port")
    if hostname not in normalized_hosts:
        raise DestinationValidationError("destination host is not approved")
    if not parsed.path.startswith("/") or parsed.fragment:
        raise DestinationValidationError("destination URL is malformed")
    return url


async def _bounded_response_body(response: httpx.Response) -> bytes | None:
    """Read at most the documented decoded response ceiling.

    Returning ``None`` means the peer crossed the limit. Iteration stops as
    soon as that is known and the surrounding stream context closes the
    response without buffering the remainder.
    """

    body = bytearray()
    async for chunk in response.aiter_bytes(chunk_size=MAX_RESPONSE_BYTES + 1):
        if len(body) + len(chunk) > MAX_RESPONSE_BYTES:
            return None
        body.extend(chunk)
    return bytes(body)


def _http_outcome(
    response: httpx.Response,
    delivery: ClaimedDelivery,
    *,
    response_body: bytes,
) -> DeliveryOutcome:
    if response.status_code in {408, 425, 429} or response.status_code >= 500:
        return DeliveryOutcome.failure(
            retryable=True,
            error_code=f"http_{response.status_code}",
            error_detail="The mirror endpoint returned a retryable HTTP status",
        )
    if not 200 <= response.status_code < 300:
        return DeliveryOutcome.failure(
            retryable=False,
            error_code=f"http_{response.status_code}",
            error_detail="The mirror endpoint rejected the delivery",
        )
    try:
        data = json.loads(response_body)
    except (ValueError, UnicodeDecodeError):
        return DeliveryOutcome.failure(
            retryable=True,
            error_code="invalid_response",
            error_detail="The mirror endpoint did not return JSON",
        )
    if not isinstance(data, dict):
        return DeliveryOutcome.failure(
            retryable=True,
            error_code="invalid_response",
            error_detail="The mirror endpoint returned an invalid response object",
        )
    if data.get("ok") is True:
        valid = (
            data.get("status") in {"accepted", "duplicate"}
            and data.get("event_id") == str(delivery.event_id)
            and data.get("payload_sha256") == delivery.payload_sha256
        )
        if valid:
            return DeliveryOutcome.success()
        return DeliveryOutcome.failure(
            retryable=False,
            error_code="acknowledgement_mismatch",
            error_detail="The mirror acknowledgement did not match the event",
        )
    error_code = data.get("error_code")
    if not isinstance(error_code, str) or not error_code:
        error_code = "sink_rejected"
    error_detail = data.get("error")
    if not isinstance(error_detail, str) or not error_detail:
        error_detail = "The mirror endpoint rejected the event"
    return DeliveryOutcome.failure(
        retryable=data.get("retryable") is True,
        error_code=error_code,
        error_detail=error_detail,
    )


async def send_claimed_delivery(
    delivery: ClaimedDelivery,
    *,
    destination: MirrorDestination,
    client: httpx.AsyncClient,
    config: DispatcherConfig,
) -> DeliveryOutcome:
    """Send once, validating every redirect before following it."""

    current_url = validate_destination_url(
        destination.webhook_url, allowed_hosts=config.allowed_hosts
    )
    body = build_signed_body(delivery, signing_secret=destination.signing_secret)
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "X-D-Company-Mirror-Schema": MIRROR_SCHEMA,
        "X-D-Company-Event-Id": str(delivery.event_id),
    }
    method = "POST"
    content: bytes | None = body
    for redirect_count in range(config.max_redirects + 1):
        async with client.stream(
            method,
            current_url,
            content=content,
            headers=headers,
            follow_redirects=False,
            timeout=config.request_timeout_seconds,
        ) as response:
            response_body = await _bounded_response_body(response)
            if response_body is None:
                return DeliveryOutcome.failure(
                    retryable=True,
                    error_code="response_too_large",
                    error_detail="The mirror response exceeded 16 KiB",
                )
            if response.status_code not in {301, 302, 303, 307, 308}:
                return _http_outcome(
                    response,
                    delivery,
                    response_body=response_body,
                )
            location = response.headers.get("location")
        if redirect_count >= config.max_redirects:
            return DeliveryOutcome.failure(
                retryable=True,
                error_code="redirect_limit",
                error_detail="The mirror endpoint exceeded the redirect limit",
            )
        if not location:
            return DeliveryOutcome.failure(
                retryable=True,
                error_code="invalid_redirect",
                error_detail="The mirror endpoint returned a redirect without a location",
            )
        current_url = validate_destination_url(
            urljoin(current_url, location), allowed_hosts=config.allowed_hosts
        )
        if response.status_code in {301, 302, 303}:
            method = "GET"
            content = None
    raise AssertionError("redirect loop escaped its bound")  # pragma: no cover


def retry_delay_seconds(attempt_count: int, *, config: DispatcherConfig) -> int:
    exponent = attempt_count - 1
    if exponent < 0:
        exponent = 0
    if exponent > 30:
        exponent = 30
    delay = config.retry_base_seconds * (1 << exponent)
    return config.retry_cap_seconds if delay > config.retry_cap_seconds else delay


async def apply_delivery_outcome(
    session: AsyncSession,
    *,
    delivery_id: UUID,
    worker_id: str,
    outcome: DeliveryOutcome,
    now: datetime,
    config: DispatcherConfig,
) -> bool:
    """Finalize only while this worker still owns the live lease."""

    now = _aware_utc("now", now)
    row = (
        await session.execute(
            select(GoogleSheetsDelivery)
            .where(
                GoogleSheetsDelivery.id == delivery_id,
                GoogleSheetsDelivery.status == "leased",
                GoogleSheetsDelivery.lease_owner == worker_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    if row is None:
        return False

    row.lease_owner = None
    row.lease_expires_at = None
    if outcome.delivered:
        row.status = "delivered"
        row.delivered_at = now
        row.last_error_code = None
        row.last_error_detail = None
        return True

    error_code = outcome.error_code or "delivery_failed"
    error_detail = outcome.error_detail or "The mirror delivery failed"
    row.last_error_code = _bounded_text(error_code, 80)
    row.last_error_detail = _bounded_text(error_detail, 500)
    if outcome.retryable and row.attempt_count < config.max_attempts:
        row.status = "pending"
        row.available_at = now + timedelta(
            seconds=retry_delay_seconds(row.attempt_count, config=config)
        )
        return True

    row.status = "quarantined"
    row.quarantined_at = now
    reason = f"{row.last_error_code}: {row.last_error_detail}"
    row.quarantine_reason = _bounded_text(reason, 500)
    return True


class GoogleSheetsMirrorDispatcher:
    """Claims committed outbox rows, delivers them, and records each outcome."""

    def __init__(
        self,
        *,
        session_factory: async_sessionmaker[AsyncSession],
        destination_resolver: DestinationResolver,
        worker_id: str,
        config: DispatcherConfig | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._session_factory = session_factory
        self._destination_resolver = destination_resolver
        self._worker_id = _required_text("worker_id", worker_id, 128)
        self._config = config or DispatcherConfig()
        self._client = client

    async def dispatch_once(self, *, now: datetime | None = None) -> int:
        now = _aware_utc("now", now or datetime.now(UTC))
        async with self._session_factory() as session, session.begin():
            claimed = await claim_delivery_batch(
                session,
                worker_id=self._worker_id,
                now=now,
                batch_size=self._config.batch_size,
                lease_seconds=self._config.lease_seconds,
            )

        owned_client = self._client is None
        client = self._client or httpx.AsyncClient()
        try:
            for delivery in claimed:
                try:
                    async with self._session_factory() as session, session.begin():
                        destination = await self._destination_resolver(
                            session,
                            delivery.company_id,
                            delivery.configuration_id,
                        )
                        if destination is None:
                            outcome = DeliveryOutcome.failure(
                                retryable=False,
                                error_code="configuration_superseded",
                                error_detail=(
                                    "The delivery belongs to an inactive Google Sheets "
                                    "configuration"
                                ),
                            )
                        else:
                            # The resolver holds a shared company-row lock for
                            # this transaction. Keep it through the HTTP send so
                            # configuration rotation cannot cross the delivery.
                            outcome = await send_claimed_delivery(
                                delivery,
                                destination=destination,
                                client=client,
                                config=self._config,
                            )
                except DestinationValidationError as exc:
                    outcome = DeliveryOutcome.failure(
                        retryable=False,
                        error_code="destination_invalid",
                        error_detail=str(exc),
                    )
                except EventIdentityConflictError as exc:
                    outcome = DeliveryOutcome.failure(
                        retryable=False,
                        error_code="event_integrity_failure",
                        error_detail=str(exc),
                    )
                except (httpx.TimeoutException, httpx.NetworkError) as exc:
                    outcome = DeliveryOutcome.failure(
                        retryable=True,
                        error_code="transport_error",
                        error_detail=f"Mirror transport failed: {type(exc).__name__}",
                    )
                except Exception as exc:  # noqa: BLE001 - isolate rows; never leak details
                    outcome = DeliveryOutcome.failure(
                        retryable=True,
                        error_code="dispatcher_error",
                        error_detail=f"Mirror dispatch failed: {type(exc).__name__}",
                    )

                async with self._session_factory() as session, session.begin():
                    await apply_delivery_outcome(
                        session,
                        delivery_id=delivery.id,
                        worker_id=self._worker_id,
                        outcome=outcome,
                        now=datetime.now(UTC),
                        config=self._config,
                    )
        finally:
            if owned_client:
                await client.aclose()
        return len(claimed)


__all__ = [
    "ClaimedDelivery",
    "DeliveryOutcome",
    "DestinationValidationError",
    "DispatcherConfig",
    "EventIdentityConflictError",
    "GoogleSheetsMirrorDispatcher",
    "CONNECTION_TEST_EVENT_TYPE",
    "CONNECTION_TEST_SOURCE_TYPE",
    "MIRROR_SCHEMA",
    "MIRROR_SCHEMA_VERSION",
    "MirrorDestination",
    "apply_delivery_outcome",
    "build_signed_body",
    "canonical_json",
    "claim_delivery_batch",
    "enqueue_google_sheets_event",
    "enqueue_google_sheets_event_if_enabled",
    "make_event_id",
    "make_event_key",
    "payload_sha256",
    "retry_delay_seconds",
    "send_claimed_delivery",
    "validate_destination_url",
]
