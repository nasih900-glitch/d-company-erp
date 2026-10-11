from __future__ import annotations

import hashlib
import hmac
import json
from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import httpx
import pytest
from sqlalchemy.dialects import postgresql

from app.models.google_sheets_delivery import GoogleSheetsDelivery
from app.services.integrations import google_sheets_mirror as mirror_service
from app.services.integrations.google_sheets_mirror import (
    MIRROR_SCHEMA,
    ClaimedDelivery,
    DeliveryOutcome,
    DestinationValidationError,
    DispatcherConfig,
    GoogleSheetsMirrorDispatcher,
    MirrorDestination,
    apply_delivery_outcome,
    build_signed_body,
    canonical_json,
    claim_delivery_batch,
    make_event_id,
    make_event_key,
    retry_delay_seconds,
    send_claimed_delivery,
    validate_destination_url,
)


def _delivery(**overrides: object) -> ClaimedDelivery:
    company_id = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    configuration_id = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    event_key = make_event_key(
        event_type="finance.report.closed",
        source_type="finance_report",
        source_id="2026-09-19",
        source_revision="1",
    )
    payload = {
        "currency": "GBP",
        "gross_revenue_minor": 12345,
        "period_start": "2026-09-19",
    }
    values: dict[str, object] = {
        "id": uuid4(),
        "company_id": company_id,
        "configuration_id": configuration_id,
        "event_id": make_event_id(company_id=company_id, event_key=event_key),
        "event_key": event_key,
        "event_type": "finance.report.closed",
        "source_type": "finance_report",
        "source_id": "2026-09-19",
        "source_revision": "1",
        "schema_version": 1,
        "payload": payload,
        "payload_sha256": hashlib.sha256(
            canonical_json(payload).encode("utf-8")
        ).hexdigest(),
        "occurred_at": datetime(2026, 9, 19, 12, 30, tzinfo=UTC),
        "attempt_count": 1,
    }
    values.update(overrides)
    return ClaimedDelivery(**values)  # type: ignore[arg-type]


def test_canonical_json_is_stable_and_rejects_lossy_money_values() -> None:
    assert canonical_json({"z": 1, "a": "£"}) == '{"a":"£","z":1}'
    with pytest.raises(ValueError, match="floats are forbidden"):
        canonical_json({"amount": 12.34})
    with pytest.raises(ValueError, match="JavaScript-safe"):
        canonical_json({"amount_minor": 2**53})


def test_event_identity_is_deterministic_and_tenant_scoped() -> None:
    arguments = {
        "event_type": "finance.report.closed",
        "source_type": "finance_report",
        "source_id": "2026-09-19",
        "source_revision": "1",
    }
    event_key = make_event_key(**arguments)
    assert event_key == make_event_key(**arguments)
    assert event_key != make_event_key(**{**arguments, "source_revision": "2"})
    assert make_event_id(company_id=uuid4(), event_key=event_key) != make_event_id(
        company_id=uuid4(), event_key=event_key
    )


async def test_conditional_enqueue_persists_while_current_generation_is_unverified(
    monkeypatch,
) -> None:
    company_id = uuid4()
    configuration_id = uuid4()
    configured_at = datetime(2026, 9, 19, tzinfo=UTC)
    captured: list[object] = []

    class _Result:
        @staticmethod
        def one_or_none():
            return configuration_id, configured_at, "legacy_transactions"

    class _Session:
        async def execute(self, statement):
            captured.append(statement)
            return _Result()

    session = _Session()
    sentinel = object()
    enqueued: dict[str, object] = {}

    async def _capture_enqueue(caller_session, **kwargs):
        enqueued["session"] = caller_session
        enqueued.update(kwargs)
        return sentinel

    monkeypatch.setattr(mirror_service, "enqueue_google_sheets_event", _capture_enqueue)
    result = await mirror_service.enqueue_google_sheets_event_if_enabled(
        session,  # type: ignore[arg-type]
        company_id=company_id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id="expense-1",
        source_revision="created-v1",
        occurred_at=datetime(2026, 9, 19, tzinfo=UTC),
        payload={"amount_minor": 100},
    )
    assert result is sentinel
    assert len(captured) == 1
    assert enqueued["session"] is session
    assert enqueued["configuration_id"] == configuration_id
    configuration_query = str(
        captured[0].compile(
            dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}
        )
    )
    assert "companies.google_sheets_configuration_id" in configuration_query
    assert "FOR SHARE" in configuration_query


async def test_conditional_enqueue_reuses_caller_transaction_when_configured(
    monkeypatch,
) -> None:
    company_id = uuid4()
    configuration_id = uuid4()
    configured_at = datetime(2026, 9, 19, tzinfo=UTC)
    sentinel = object()
    captured: dict[str, object] = {}

    class _Result:
        @staticmethod
        def one_or_none():
            return configuration_id, configured_at, "legacy_transactions"

    class _Session:
        async def execute(self, _statement):
            return _Result()

        async def scalar(self, _statement):
            return uuid4()

    session = _Session()

    async def _capture_enqueue(caller_session, **kwargs):
        captured["session"] = caller_session
        captured.update(kwargs)
        return sentinel

    monkeypatch.setattr(mirror_service, "enqueue_google_sheets_event", _capture_enqueue)
    result = await mirror_service.enqueue_google_sheets_event_if_enabled(
        session,  # type: ignore[arg-type]
        company_id=company_id,
        event_type="finance.expense.recorded",
        source_type="expense",
        source_id="expense-1",
        source_revision="created-v1",
        occurred_at=datetime(2026, 9, 19, tzinfo=UTC),
        payload={"amount_minor": 100},
    )
    assert result is sentinel
    assert captured["session"] is session
    assert captured["company_id"] == company_id
    assert captured["configuration_id"] == configuration_id
    assert captured["source_revision"] == "created-v1"


async def test_connection_test_can_enqueue_before_verification(monkeypatch) -> None:
    company_id = uuid4()
    configuration_id = uuid4()
    configured_at = datetime(2026, 9, 19, tzinfo=UTC)
    sentinel = object()
    captured_statements: list[object] = []

    class _Result:
        @staticmethod
        def one_or_none():
            return configuration_id, configured_at, "legacy_transactions"

    class _Session:
        async def execute(self, statement):
            captured_statements.append(statement)
            return _Result()

    session = _Session()

    async def _capture_enqueue(_caller_session, **_kwargs):
        return sentinel

    monkeypatch.setattr(mirror_service, "enqueue_google_sheets_event", _capture_enqueue)
    result = await mirror_service.enqueue_google_sheets_event_if_enabled(
        session,  # type: ignore[arg-type]
        company_id=company_id,
        event_type=mirror_service.CONNECTION_TEST_EVENT_TYPE,
        source_type="mirror_configuration",
        source_id="configuration-1",
        source_revision="1",
        occurred_at=datetime(2026, 9, 19, tzinfo=UTC),
        payload={"status": "test"},
    )

    assert result is sentinel
    assert len(captured_statements) == 1
    compiled = str(
        captured_statements[0].compile(
            dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}
        )
    )
    assert "google_sheets_deliveries" not in compiled
    assert "FOR SHARE" in compiled


async def test_summary_mode_suppresses_ordinary_transaction_enqueue(
    monkeypatch,
) -> None:
    class _Result:
        @staticmethod
        def one_or_none():
            return uuid4(), datetime(2026, 9, 19, tzinfo=UTC), "daily_collections"

    class _Session:
        async def execute(self, _statement):
            return _Result()

    async def fail_enqueue(*_args, **_kwargs):
        pytest.fail("summary mode must not enqueue an individual business fact")

    monkeypatch.setattr(mirror_service, "enqueue_google_sheets_event", fail_enqueue)
    assert (
        await mirror_service.enqueue_google_sheets_event_if_enabled(
            _Session(),  # type: ignore[arg-type]
            company_id=uuid4(),
            event_type="finance.expense.recorded",
            source_type="expense",
            source_id=str(uuid4()),
            source_revision="recorded-v1",
            occurred_at=datetime(2026, 9, 19, tzinfo=UTC),
            payload={"amount_minor": -100},
        )
        is None
    )


def test_signed_body_covers_the_exact_canonical_event() -> None:
    secret = "s" * 32
    outer = json.loads(build_signed_body(_delivery(), signing_secret=secret))
    assert outer["schema"] == MIRROR_SCHEMA
    assert outer["signature_algorithm"] == "HMAC-SHA256"
    expected = hmac.new(
        secret.encode(), outer["signed_payload"].encode(), hashlib.sha256
    ).hexdigest()
    assert hmac.compare_digest(outer["signature"], expected)
    signed = json.loads(outer["signed_payload"])
    assert signed["configuration_id"] == str(_delivery().configuration_id)
    assert hashlib.sha256(signed["payload_json"].encode()).hexdigest() == signed[
        "payload_sha256"
    ]


@pytest.mark.parametrize(
    "url",
    [
        "http://script.google.com/macros/s/id/exec",
        "https://script.google.com.evil.invalid/macros/s/id/exec",
        "https://user@script.google.com/macros/s/id/exec",
        "https://script.google.com:444/macros/s/id/exec",
        "https://127.0.0.1/webhook",
    ],
)
def test_destination_validation_rejects_unsafe_urls(url: str) -> None:
    with pytest.raises(DestinationValidationError):
        validate_destination_url(
            url,
            allowed_hosts=frozenset(
                {"script.google.com", "script.googleusercontent.com"}
            ),
        )


def test_destination_validation_accepts_exact_google_host() -> None:
    url = "https://script.google.com/macros/s/id/exec"
    assert (
        validate_destination_url(
            url,
            allowed_hosts=frozenset(
                {"script.google.com", "script.googleusercontent.com"}
            ),
        )
        == url
    )


async def test_delivery_accepts_google_redirect_and_matching_duplicate_ack() -> None:
    delivery = _delivery()
    methods: list[str] = []

    async def respond(request: httpx.Request) -> httpx.Response:
        methods.append(request.method)
        if request.url.host == "script.google.com":
            return httpx.Response(
                302,
                headers={"location": "https://script.googleusercontent.com/result"},
            )
        return httpx.Response(
            200,
            json={
                "ok": True,
                "status": "duplicate",
                "event_id": str(delivery.event_id),
                "payload_sha256": delivery.payload_sha256,
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        outcome = await send_claimed_delivery(
            delivery,
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=DispatcherConfig(),
        )

    assert outcome.delivered is True
    assert methods == ["POST", "GET"]


def _receiver_health_response(**overrides: object) -> dict[str, object]:
    response: dict[str, object] = {
        "ok": True,
        "service": "D Company ERP Mirror",
        "schema": "erp-mirror.v1",
        "schema_version": 1,
        "mode": "append-only",
        "tab": "ERP Mirror v1",
        "hmac_configured": True,
    }
    response.update(overrides)
    return response


async def test_exact_receiver_health_response_is_retryable_and_never_success() -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(200, json=_receiver_health_response())
        )
    ) as client:
        outcome = await send_claimed_delivery(
            _delivery(),
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=DispatcherConfig(),
        )

    assert outcome.delivered is False
    assert outcome.retryable is True
    assert outcome.error_code == "unexpected_health_response"


@pytest.mark.parametrize(
    ("response_payload", "error_code"),
    [
        ({"ok": True}, "acknowledgement_mismatch"),
        (
            _receiver_health_response(schema_version=True),
            "acknowledgement_mismatch",
        ),
        (
            _receiver_health_response(event_id="unexpected-ack-field"),
            "acknowledgement_mismatch",
        ),
        (
            {
                "ok": True,
                "status": "accepted",
                "event_id": "wrong-event",
                "payload_sha256": "0" * 64,
            },
            "acknowledgement_mismatch",
        ),
        (
            {
                "ok": False,
                "retryable": False,
                "error_code": "signature_invalid",
                "error": "sink rejected signature",
            },
            "signature_invalid",
        ),
    ],
)
async def test_non_health_responses_keep_existing_fail_closed_classification(
    response_payload: dict[str, object], error_code: str
) -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(200, json=response_payload)
        )
    ) as client:
        outcome = await send_claimed_delivery(
            _delivery(),
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=DispatcherConfig(),
        )

    assert outcome.delivered is False
    assert outcome.retryable is False
    assert outcome.error_code == error_code


@pytest.mark.parametrize(
    ("ack_overrides", "delivered"),
    [
        ({}, False),
        ({"capability": "daily_collections.v1", "scope": {}}, False),
        (
            {
                "capability": "daily_collections.v1",
                "scope": {
                    "branch_id": "branch-1",
                    "business_timezone": "Asia/Kolkata",
                    "start_date": "2026-09-01",
                },
            },
            True,
        ),
    ],
)
async def test_summary_connection_requires_exact_capability_and_scope_ack(
    ack_overrides: dict[str, object], delivered: bool
) -> None:
    scope = {
        "branch_id": "branch-1",
        "business_timezone": "Asia/Kolkata",
        "start_date": "2026-09-01",
    }
    payload = {
        "export_mode": "daily_collections",
        "required_capability": "daily_collections.v1",
        "summary_scope": scope,
    }
    connection_test = _delivery(
        event_type=mirror_service.CONNECTION_TEST_EVENT_TYPE,
        source_type=mirror_service.CONNECTION_TEST_SOURCE_TYPE,
        payload=payload,
        payload_sha256=hashlib.sha256(
            canonical_json(payload).encode("utf-8")
        ).hexdigest(),
    )
    response_payload = {
        "ok": True,
        "status": "accepted",
        "event_id": str(connection_test.event_id),
        "payload_sha256": connection_test.payload_sha256,
        **ack_overrides,
    }
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(200, json=response_payload)
        )
    ) as client:
        outcome = await send_claimed_delivery(
            connection_test,
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=DispatcherConfig(),
        )
    assert outcome.delivered is delivered
    if not delivered:
        assert outcome.error_code == "acknowledgement_mismatch"


async def test_delivery_rejects_redirect_outside_allowlist_before_following() -> None:
    requests = 0

    async def respond(_request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        return httpx.Response(302, headers={"location": "https://attacker.invalid/"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        with pytest.raises(DestinationValidationError):
            await send_claimed_delivery(
                _delivery(),
                destination=MirrorDestination(
                    "https://script.google.com/macros/s/id/exec", "s" * 32
                ),
                client=client,
                config=DispatcherConfig(),
            )
    assert requests == 1


async def test_delivery_classifies_throttling_as_retryable() -> None:
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _request: httpx.Response(429))
    ) as client:
        outcome = await send_claimed_delivery(
            _delivery(),
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=DispatcherConfig(),
        )
    assert outcome.delivered is False
    assert outcome.retryable is True
    assert outcome.error_code == "http_429"


class _CountingStream(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes]) -> None:
        self.chunks = chunks
        self.yielded = 0
        self.closed = False

    async def __aiter__(self):
        for chunk in self.chunks:
            self.yielded += 1
            yield chunk

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.parametrize(
    ("status_code", "headers"),
    [
        (200, {}),
        (302, {"location": "https://script.googleusercontent.com/result"}),
    ],
)
async def test_delivery_stops_streaming_oversized_final_and_redirect_responses(
    status_code: int,
    headers: dict[str, str],
) -> None:
    stream = _CountingStream(
        [b"x" * 8192, b"y" * 8192, b"z", b"unread" * 8192]
    )
    requests = 0

    async def respond(_request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        return httpx.Response(status_code, headers=headers, stream=stream)

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        outcome = await send_claimed_delivery(
            _delivery(),
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=DispatcherConfig(),
        )

    assert outcome.delivered is False
    assert outcome.retryable is True
    assert outcome.error_code == "response_too_large"
    assert requests == 1
    assert stream.yielded == 3
    assert stream.closed is True


async def test_dispatcher_never_sends_a_superseded_configuration(
    monkeypatch,
) -> None:
    delivery = _delivery()
    captured: dict[str, object] = {}
    requests = 0

    class _Transaction:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

    class _Session:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return None

        def begin(self):
            return _Transaction()

    class _SessionFactory:
        def __call__(self):
            return _Session()

    async def claim(*_args, **_kwargs):
        return [delivery]

    async def resolve(_session, company_id, configuration_id):
        assert company_id == delivery.company_id
        assert configuration_id == delivery.configuration_id
        return None

    async def apply(_session, **kwargs):
        captured.update(kwargs)
        return True

    async def respond(_request: httpx.Request) -> httpx.Response:
        nonlocal requests
        requests += 1
        return httpx.Response(500)

    monkeypatch.setattr(mirror_service, "claim_delivery_batch", claim)
    monkeypatch.setattr(mirror_service, "apply_delivery_outcome", apply)
    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        dispatcher = GoogleSheetsMirrorDispatcher(
            session_factory=_SessionFactory(),  # type: ignore[arg-type]
            destination_resolver=resolve,  # type: ignore[arg-type]
            worker_id="mirror-worker-1",
            client=client,
        )
        assert await dispatcher.dispatch_once() == 1

    assert requests == 0
    outcome = captured["outcome"]
    assert isinstance(outcome, DeliveryOutcome)
    assert outcome.delivered is False
    assert outcome.retryable is False
    assert outcome.error_code == "configuration_superseded"


async def test_claim_query_uses_skip_locked() -> None:
    captured: list[object] = []

    class _Scalars:
        @staticmethod
        def all() -> list[object]:
            return []

    class _Result:
        @staticmethod
        def scalars() -> _Scalars:
            return _Scalars()

    class _Session:
        async def execute(self, statement: object) -> _Result:
            captured.append(statement)
            return _Result()

        async def flush(self) -> None:
            return None

    await claim_delivery_batch(  # type: ignore[arg-type]
        _Session(),
        worker_id="mirror-worker-1",
        now=datetime(2026, 9, 19, tzinfo=UTC),
        batch_size=25,
        lease_seconds=60,
    )
    compiled = str(  # type: ignore[attr-defined]
        captured[0].compile(
            dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}
        )
    )
    assert "FOR UPDATE SKIP LOCKED" in compiled
    assert "mirror.connection_test" in compiled
    assert "google_sheets_configured_at" in compiled
    assert "occurred_at >=" in compiled
    assert "delivered_at >=" in compiled
    assert "status = 'delivered'" in compiled


def test_retry_backoff_is_exponential_and_capped() -> None:
    config = DispatcherConfig(retry_base_seconds=30, retry_cap_seconds=300)
    assert retry_delay_seconds(1, config=config) == 30
    assert retry_delay_seconds(2, config=config) == 60
    assert retry_delay_seconds(99, config=config) == 300


def _leased_model(*, attempt_count: int = 1) -> GoogleSheetsDelivery:
    claimed = _delivery(attempt_count=attempt_count)
    now = datetime(2026, 9, 19, 13, tzinfo=UTC)
    return GoogleSheetsDelivery(
        id=claimed.id,
        company_id=claimed.company_id,
        configuration_id=claimed.configuration_id,
        event_id=claimed.event_id,
        event_key=claimed.event_key,
        event_type=claimed.event_type,
        source_type=claimed.source_type,
        source_id=claimed.source_id,
        source_revision=claimed.source_revision,
        schema_version=claimed.schema_version,
        payload=claimed.payload,
        payload_sha256=claimed.payload_sha256,
        occurred_at=claimed.occurred_at,
        available_at=now,
        status="leased",
        lease_owner="worker-1",
        lease_expires_at=now + timedelta(minutes=5),
        attempt_count=attempt_count,
        last_attempt_at=now,
    )


async def test_idempotent_replay_after_rotation_keeps_original_generation() -> None:
    existing = _leased_model()
    original_configuration_id = existing.configuration_id
    replacement_configuration_id = uuid4()
    assert replacement_configuration_id != original_configuration_id

    class _ConflictResult:
        @staticmethod
        def scalar_one_or_none():
            return None

    class _ExistingResult:
        @staticmethod
        def scalar_one():
            return existing

    class _ReplaySession:
        calls = 0

        async def execute(self, _statement):
            self.calls += 1
            return _ConflictResult() if self.calls == 1 else _ExistingResult()

    returned = await mirror_service.enqueue_google_sheets_event(
        _ReplaySession(),  # type: ignore[arg-type]
        company_id=existing.company_id,
        configuration_id=replacement_configuration_id,
        event_type=existing.event_type,
        source_type=existing.source_type,
        source_id=existing.source_id,
        source_revision=existing.source_revision,
        occurred_at=existing.occurred_at,
        payload=existing.payload,
        schema_version=existing.schema_version,
    )

    assert returned is existing
    assert returned.configuration_id == original_configuration_id


class _OneRowResult:
    def __init__(self, row: GoogleSheetsDelivery | None) -> None:
        self.row = row

    def scalar_one_or_none(self) -> GoogleSheetsDelivery | None:
        return self.row


class _OneRowSession:
    def __init__(self, row: GoogleSheetsDelivery) -> None:
        self.row = row

    async def execute(self, _statement: object) -> _OneRowResult:
        return _OneRowResult(self.row)


class _LeaseAwareOneRowSession(_OneRowSession):
    async def execute(self, _statement: object) -> _OneRowResult:
        if self.row.status == "leased" and self.row.lease_owner == "worker-1":
            return _OneRowResult(self.row)
        return _OneRowResult(None)


async def test_retryable_outcome_returns_row_to_pending_with_backoff() -> None:
    row = _leased_model(attempt_count=2)
    now = datetime(2026, 9, 19, 14, tzinfo=UTC)
    changed = await apply_delivery_outcome(  # type: ignore[arg-type]
        _OneRowSession(row),
        delivery_id=row.id,
        worker_id="worker-1",
        outcome=DeliveryOutcome.failure(
            retryable=True, error_code="http_429", error_detail="throttled"
        ),
        now=now,
        config=DispatcherConfig(retry_base_seconds=30),
    )
    assert changed is True
    assert row.status == "pending"
    assert row.available_at == now + timedelta(seconds=60)
    assert row.lease_owner is None
    assert row.quarantined_at is None


async def test_health_retry_can_reach_duplicate_ack_once_and_is_bounded() -> None:
    delivery = _delivery(attempt_count=1)
    config = DispatcherConfig(max_attempts=3, retry_base_seconds=30)

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(200, json=_receiver_health_response())
        )
    ) as client:
        health_outcome = await send_claimed_delivery(
            delivery,
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=config,
        )

    row = _leased_model(attempt_count=1)
    original_payload = dict(row.payload)
    first_attempt_at = datetime(2026, 9, 19, 14, tzinfo=UTC)
    assert await apply_delivery_outcome(  # type: ignore[arg-type]
        _LeaseAwareOneRowSession(row),
        delivery_id=row.id,
        worker_id="worker-1",
        outcome=health_outcome,
        now=first_attempt_at,
        config=config,
    )
    assert row.status == "pending"
    assert row.available_at == first_attempt_at + timedelta(seconds=30)
    assert row.last_error_code == "unexpected_health_response"
    assert row.payload == original_payload

    row.status = "leased"
    row.lease_owner = "worker-1"
    row.lease_expires_at = first_attempt_at + timedelta(minutes=5)
    row.attempt_count = 2
    duplicate_ack = {
        "ok": True,
        "status": "duplicate",
        "event_id": str(delivery.event_id),
        "payload_sha256": delivery.payload_sha256,
    }
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(200, json=duplicate_ack)
        )
    ) as client:
        duplicate_outcome = await send_claimed_delivery(
            delivery,
            destination=MirrorDestination(
                "https://script.google.com/macros/s/id/exec", "s" * 32
            ),
            client=client,
            config=config,
        )

    delivered_at = first_attempt_at + timedelta(minutes=1)
    assert await apply_delivery_outcome(  # type: ignore[arg-type]
        _LeaseAwareOneRowSession(row),
        delivery_id=row.id,
        worker_id="worker-1",
        outcome=duplicate_outcome,
        now=delivered_at,
        config=config,
    )
    assert row.status == "delivered"
    assert row.delivered_at == delivered_at
    assert row.payload == original_payload
    assert not await apply_delivery_outcome(  # type: ignore[arg-type]
        _LeaseAwareOneRowSession(row),
        delivery_id=row.id,
        worker_id="worker-1",
        outcome=duplicate_outcome,
        now=delivered_at + timedelta(minutes=1),
        config=config,
    )
    assert row.delivered_at == delivered_at

    exhausted = _leased_model(attempt_count=config.max_attempts)
    exhausted_payload = dict(exhausted.payload)
    quarantine_at = first_attempt_at + timedelta(minutes=2)
    assert await apply_delivery_outcome(  # type: ignore[arg-type]
        _LeaseAwareOneRowSession(exhausted),
        delivery_id=exhausted.id,
        worker_id="worker-1",
        outcome=health_outcome,
        now=quarantine_at,
        config=config,
    )
    assert exhausted.status == "quarantined"
    assert exhausted.quarantined_at == quarantine_at
    assert exhausted.quarantine_reason is not None
    assert exhausted.quarantine_reason.startswith("unexpected_health_response:")
    assert exhausted.payload == exhausted_payload


async def test_permanent_outcome_quarantines_without_erasing_evidence() -> None:
    row = _leased_model()
    now = datetime(2026, 9, 19, 14, tzinfo=UTC)
    original_payload = dict(row.payload)
    await apply_delivery_outcome(  # type: ignore[arg-type]
        _OneRowSession(row),
        delivery_id=row.id,
        worker_id="worker-1",
        outcome=DeliveryOutcome.failure(
            retryable=False,
            error_code="signature_invalid",
            error_detail="sink rejected signature",
        ),
        now=now,
        config=DispatcherConfig(),
    )
    assert row.status == "quarantined"
    assert row.quarantined_at == now
    assert row.quarantine_reason == "signature_invalid: sink rejected signature"
    assert row.payload == original_payload


async def test_success_marks_delivery_without_changing_source_identity() -> None:
    row = _leased_model()
    now = datetime(2026, 9, 19, 14, tzinfo=UTC)
    event_key = row.event_key
    await apply_delivery_outcome(  # type: ignore[arg-type]
        _OneRowSession(row),
        delivery_id=row.id,
        worker_id="worker-1",
        outcome=DeliveryOutcome.success(),
        now=now,
        config=DispatcherConfig(),
    )
    assert row.status == "delivered"
    assert row.delivered_at == now
    assert row.event_key == event_key
