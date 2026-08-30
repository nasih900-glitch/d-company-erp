"""Privacy and throttling contracts for automatic native diagnostics."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest
from pydantic import ValidationError
from redis.exceptions import ConnectionError as RedisConnectionError

from app.api.v1.client_diagnostics.router import ClientDiagnosticBatchWrite
from app.core.errors import DiagnosticIngestRetryError, RateLimitError, ServiceUnavailableError
from app.services.client_diagnostics import rate_limit


def _payload() -> dict[str, object]:
    return {
        "installation_id": str(uuid4()),
        "events": [
            {
                "client_event_id": str(uuid4()),
                "event_type": "api_failure",
                "severity": "error",
                "occurred_at": datetime.now(UTC).isoformat(),
                "version_name": "3.1.4",
                "version_code": 15,
                "os_api_level": 35,
                "component": "network",
                "reason_code": "http_5xx",
                "failure_fingerprint": None,
                "http_status": 503,
                "duration_bucket": "5_to_30s",
                "connectivity": "online",
                "pending_outbox_count": 2,
            }
        ],
    }


def test_diagnostic_contract_forbids_raw_or_identifying_context() -> None:
    assert ClientDiagnosticBatchWrite.model_validate(_payload()).events[0].reason_code == "http_5xx"

    forbidden_fields = (
        "message",
        "stack_trace",
        "request_url",
        "request_body",
        "headers",
        "device_id",
        "device_model",
        "customer_id",
        "payment_id",
    )
    for field in forbidden_fields:
        payload = _payload()
        payload["events"][0][field] = "must-not-be-stored"  # type: ignore[index]
        with pytest.raises(ValidationError):
            ClientDiagnosticBatchWrite.model_validate(payload)


def test_diagnostic_contract_rejects_bad_id_time_and_http_scope() -> None:
    duplicate_id = str(uuid4())
    duplicate = _payload()
    duplicate["events"] = [
        {**duplicate["events"][0], "client_event_id": duplicate_id},  # type: ignore[index]
        {**duplicate["events"][0], "client_event_id": duplicate_id},  # type: ignore[index]
    ]
    with pytest.raises(ValidationError, match="must not repeat"):
        ClientDiagnosticBatchWrite.model_validate(duplicate)

    too_old = _payload()
    too_old["events"][0]["occurred_at"] = (  # type: ignore[index]
        datetime.now(UTC) - timedelta(days=91)
    ).isoformat()
    with pytest.raises(ValidationError, match="90-day"):
        ClientDiagnosticBatchWrite.model_validate(too_old)

    wrong_scope = _payload()
    wrong_scope["events"][0].update(  # type: ignore[union-attr, index]
        {"event_type": "crash", "http_status": 500}
    )
    with pytest.raises(ValidationError, match="only for api_failure"):
        ClientDiagnosticBatchWrite.model_validate(wrong_scope)


def test_retryable_ingest_conflict_has_stable_code_and_retry_header() -> None:
    error = DiagnosticIngestRetryError("Retry the same diagnostic batch.")
    assert error.status_code == 409
    assert error.code == "diagnostic_ingest_retry"
    assert error.headers == {"Retry-After": "1"}
    assert error.details == {"retry_after_seconds": 1}


class _FakeRedis:
    def __init__(self, results: list[object] | None = None, error: Exception | None = None) -> None:
        self.results = list(results or [])
        self.error = error
        self.calls: list[tuple[str, int]] = []
        self.closed = False

    async def eval(self, _script, _key_count, key, event_count, _window):
        self.calls.append((key, int(event_count)))
        if self.error is not None:
            raise self.error
        return self.results.pop(0)

    async def aclose(self) -> None:
        self.closed = True


@pytest.mark.asyncio
async def test_rate_limit_counts_events_and_hashes_authenticated_principal(monkeypatch) -> None:
    company_id = uuid4()
    user_id = uuid4()
    fake = _FakeRedis(results=[[20, 60], [35, 59]])
    monkeypatch.setattr(
        rate_limit,
        "get_settings",
        lambda: SimpleNamespace(
            redis_url="redis://localhost:6379/0",
            client_diagnostics_user_event_limit_per_minute=30,
        ),
    )
    monkeypatch.setattr(rate_limit.Redis, "from_url", lambda *_args, **_kwargs: fake)

    await rate_limit.enforce_client_diagnostic_rate_limit(
        company_id=company_id,
        user_id=user_id,
        event_count=20,
    )
    with pytest.raises(RateLimitError) as captured:
        await rate_limit.enforce_client_diagnostic_rate_limit(
            company_id=company_id,
            user_id=user_id,
            event_count=15,
        )

    assert fake.calls[0][1] == 20
    assert fake.calls[1][1] == 15
    assert len({key for key, _count in fake.calls}) == 1
    assert str(company_id) not in fake.calls[0][0]
    assert str(user_id) not in fake.calls[0][0]
    assert captured.value.headers == {"Retry-After": "59"}
    assert fake.closed is True


@pytest.mark.asyncio
async def test_rate_limit_fails_closed_without_losing_local_retry_guidance(monkeypatch) -> None:
    fake = _FakeRedis(error=RedisConnectionError("unavailable"))
    monkeypatch.setattr(
        rate_limit,
        "get_settings",
        lambda: SimpleNamespace(
            redis_url="redis://localhost:6379/0",
            client_diagnostics_user_event_limit_per_minute=120,
        ),
    )
    monkeypatch.setattr(rate_limit.Redis, "from_url", lambda *_args, **_kwargs: fake)

    with pytest.raises(ServiceUnavailableError, match="remain on this device"):
        await rate_limit.enforce_client_diagnostic_rate_limit(
            company_id=uuid4(),
            user_id=uuid4(),
            event_count=1,
        )
    assert fake.closed is True
