from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

import httpx
import pytest
from fastapi import Response
from pydantic import ValidationError
from starlette.requests import Request

from app import main as application_main
from app.api.v1.client_updates import router as release_router
from app.api.v1.public import router as public_router
from app.core import config, release_identity
from app.core.config import Settings
from app.core.errors import ServiceUnavailableError
from app.core.release_identity import ReleaseIdentity, parse_release_identity
from app.services.client_updates import runtime_parity, runtime_parity_supervisor
from app.services.client_updates.runtime_parity import (
    RuntimeParityError,
    record_verified_runtime_parity_for_public_offer,
    verify_runtime_parity,
    verify_runtime_parity_for_public_offer,
)
from scripts import register_android_release as registration

IDENTITY = {"version_name": "3.1.21", "source_git_sha": "ab" * 20}
BODY = json.dumps(IDENTITY).encode()
HEADERS = {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
}


def _settings(**overrides) -> Settings:
    return Settings(
        _env_file=None,
        **{
            "env": "test",
            "app_version": IDENTITY["version_name"],
            "app_revision": IDENTITY["source_git_sha"],
            "android_update_allowed_origin": "https://erp.example.test",
            **overrides,
        },
    )


def _response(*, body=BODY, status=200, headers=None) -> httpx.Response:
    return httpx.Response(status, content=body, headers=HEADERS if headers is None else headers)


@pytest.mark.asyncio
async def test_exact_current_backend_and_frontend_identity_passes_without_cache() -> None:
    requests = []

    def respond(request):
        requests.append(request)
        assert str(request.url) == "https://erp.example.test/.well-known/erp-release.json"
        assert request.headers["Cache-Control"] == "no-cache, no-store"
        assert request.headers["Accept-Encoding"] == "identity"
        return _response()

    for _ in range(2):
        await verify_runtime_parity(
            **IDENTITY, settings=_settings(), transport=httpx.MockTransport(respond)
        )
    assert len(requests) == 2


@pytest.mark.parametrize(
    ("overrides", "code"),
    [
        ({"app_version": None}, "backend_identity_invalid"),
        ({"app_revision": "AB" * 20}, "backend_identity_invalid"),
        ({"app_version": "3.1.13"}, "backend_release_mismatch"),
        ({"app_revision": "cd" * 20}, "backend_release_mismatch"),
        ({"android_update_allowed_origin": None}, "runtime_origin_not_configured"),
    ],
)
@pytest.mark.asyncio
async def test_backend_identity_failure_never_contacts_frontend(overrides, code) -> None:
    def forbidden(_request):
        pytest.fail("Invalid backend identity must fail before network I/O")

    with pytest.raises(RuntimeParityError, match=code):
        await verify_runtime_parity(
            **IDENTITY, settings=_settings(**overrides), transport=httpx.MockTransport(forbidden)
        )


@pytest.mark.parametrize(
    "identity",
    [
        {**IDENTITY, "version_name": "0.0.0"},
        {**IDENTITY, "source_git_sha": "0" * 40},
    ],
)
@pytest.mark.asyncio
async def test_each_development_sentinel_cannot_authorize_matching_release(identity) -> None:
    settings = _settings(
        env="dev",
        app_version=identity["version_name"],
        app_revision=identity["source_git_sha"],
    )
    # Local development startup remains valid; release authorization does not.
    assert settings.runtime_release_identity() == ReleaseIdentity(**identity)

    def forbidden(_request):
        pytest.fail("Development identities must fail before network I/O")

    with pytest.raises(RuntimeParityError, match="runtime_identity_not_release"):
        await verify_runtime_parity(
            **identity, settings=settings, transport=httpx.MockTransport(forbidden)
        )


@pytest.mark.parametrize(
    "identity",
    [
        {**IDENTITY, "version_name": "0.0.0"},
        {**IDENTITY, "source_git_sha": "0" * 40},
    ],
)
@pytest.mark.asyncio
async def test_direct_registration_rejects_each_development_sentinel_before_database(
    monkeypatch,
    identity,
) -> None:
    settings = _settings(
        app_version=identity["version_name"],
        app_revision=identity["source_git_sha"],
    )
    candidate = _release()
    candidate.version_name = identity["version_name"]
    candidate.source_git_sha = identity["source_git_sha"]
    monkeypatch.setattr(registration, "get_settings", lambda: settings)

    def forbidden_database():
        pytest.fail("Development identities must not open a release transaction")

    monkeypatch.setattr(registration, "AsyncSessionLocal", forbidden_database)
    with pytest.raises(SystemExit, match="runtime_identity_not_release"):
        await registration.register(candidate)


@pytest.mark.parametrize(
    ("response", "code"),
    [
        (_response(status=404), "frontend_identity_http_error"),
        (
            _response(status=302, headers={"Location": "https://evil.test/"}),
            "frontend_identity_http_error",
        ),
        (_response(body=b"{"), "frontend_identity_invalid"),
        (_response(body=b"[]"), "frontend_identity_invalid"),
        (_response(body=b"\xff"), "frontend_identity_invalid"),
        (_response(body=b""), "frontend_identity_size_invalid"),
        (_response(body=b"x" * 1025), "frontend_identity_size_invalid"),
        (
            _response(body=json.dumps({**IDENTITY, "extra": True}).encode()),
            "frontend_identity_invalid",
        ),
        (
            _response(body=json.dumps({**IDENTITY, "version_name": 25}).encode()),
            "frontend_identity_invalid",
        ),
        (
            _response(body=json.dumps({"version_name": "3.1.21"}).encode()),
            "frontend_identity_invalid",
        ),
        (
            _response(body=json.dumps({**IDENTITY, "source_git_sha": "AB" * 20}).encode()),
            "frontend_identity_invalid",
        ),
        (
            _response(body=json.dumps({**IDENTITY, "source_git_sha": "a" * 39}).encode()),
            "frontend_identity_invalid",
        ),
        (
            _response(body=json.dumps({**IDENTITY, "source_git_sha": "cd" * 20}).encode()),
            "frontend_release_mismatch",
        ),
        (
            _response(body=json.dumps({**IDENTITY, "version_name": "3.1.13"}).encode()),
            "frontend_release_mismatch",
        ),
        (_response(body=BODY[:-1] + b',"version_name":"3.1.21"}'), "frontend_identity_invalid"),
        (
            _response(headers={**HEADERS, "Content-Type": "text/html"}),
            "frontend_identity_content_type_invalid",
        ),
        (
            _response(headers={**HEADERS, "Cache-Control": "public,max-age=3600"}),
            "frontend_identity_cache_policy_invalid",
        ),
        (
            _response(headers={**HEADERS, "X-Content-Type-Options": ""}),
            "frontend_identity_nosniff_missing",
        ),
        (
            _response(headers={**HEADERS, "Content-Length": "huge"}),
            "frontend_identity_size_invalid",
        ),
        (_response(headers={**HEADERS, "Content-Length": "1"}), "frontend_identity_size_invalid"),
    ],
)
@pytest.mark.asyncio
async def test_frontend_failures_are_fail_closed(response, code) -> None:
    calls = []

    def respond(request):
        calls.append(request)
        return response

    with pytest.raises(RuntimeParityError, match=code):
        await verify_runtime_parity(
            **IDENTITY, settings=_settings(), transport=httpx.MockTransport(respond)
        )
    assert len(calls) == 1  # including redirects; no second request is followed


@pytest.mark.asyncio
async def test_unreachable_identity_is_categorical_without_response_leakage() -> None:
    def unreachable(request):
        raise httpx.ConnectError("sensitive internal details", request=request)

    with pytest.raises(RuntimeParityError, match="^frontend_identity_unreachable$"):
        await verify_runtime_parity(
            **IDENTITY, settings=_settings(), transport=httpx.MockTransport(unreachable)
        )


@pytest.mark.asyncio
async def test_chunked_oversize_and_read_timeout_fail_closed() -> None:
    class Oversize(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b"x" * 700
            yield b"x" * 700

    with pytest.raises(RuntimeParityError, match="frontend_identity_size_invalid"):
        await verify_runtime_parity(
            **IDENTITY,
            settings=_settings(),
            transport=httpx.MockTransport(
                lambda _: httpx.Response(200, headers=HEADERS, stream=Oversize())
            ),
        )

    def timeout(request):
        raise httpx.ReadTimeout("slow server", request=request)

    with pytest.raises(RuntimeParityError, match="frontend_identity_unreachable"):
        await verify_runtime_parity(
            **IDENTITY, settings=_settings(), transport=httpx.MockTransport(timeout)
        )


@pytest.mark.asyncio
async def test_public_offer_concurrent_callers_share_one_probe() -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    entered = asyncio.Event()
    release = asyncio.Event()
    calls = 0

    async def respond(_request):
        nonlocal calls
        calls += 1
        entered.set()
        await release.wait()
        return _response()

    transport = httpx.MockTransport(respond)
    checks = [
        asyncio.create_task(
            verify_runtime_parity_for_public_offer(
                **IDENTITY,
                settings=_settings(),
                transport=transport,
            )
        )
        for _ in range(20)
    ]
    await asyncio.wait_for(entered.wait(), timeout=1)
    await asyncio.sleep(0)
    release.set()
    await asyncio.gather(*checks)

    assert calls == 1


@pytest.mark.asyncio
async def test_public_offer_cache_expires_and_backend_identity_invalidates_immediately(
    monkeypatch,
) -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    now = 100.0
    calls = 0

    monkeypatch.setattr(runtime_parity, "monotonic", lambda: now)

    def respond(_request):
        nonlocal calls
        calls += 1
        return _response()

    transport = httpx.MockTransport(respond)
    settings = _settings()
    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=settings,
        transport=transport,
    )
    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=settings,
        transport=transport,
    )
    assert calls == 1

    with pytest.raises(RuntimeParityError, match="^backend_release_mismatch$"):
        await verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=_settings(app_revision="cd" * 20),
            transport=transport,
        )
    assert calls == 1

    now += runtime_parity.PUBLIC_RUNTIME_PARITY_ATTESTATION_TTL_SECONDS + 0.001
    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=settings,
        transport=transport,
    )
    assert calls == 2


@pytest.mark.asyncio
async def test_public_offer_slow_probe_is_single_flight_and_fails_closed(
    monkeypatch,
) -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    monkeypatch.setattr(runtime_parity, "RUNTIME_PARITY_TOTAL_TIMEOUT_SECONDS", 0.03)
    calls = 0

    async def respond(_request):
        nonlocal calls
        calls += 1
        await asyncio.sleep(1)
        return _response()

    transport = httpx.MockTransport(respond)
    checks = [
        verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=_settings(),
            transport=transport,
        )
        for _ in range(10)
    ]
    failures = await asyncio.gather(*checks, return_exceptions=True)

    assert calls == 1
    assert all(
        isinstance(failure, RuntimeParityError) and failure.code == "frontend_identity_unreachable"
        for failure in failures
    )

    # Failures are not cached: a later caller retries instead of inheriting a
    # negative result or receiving a stale positive offer.
    with pytest.raises(RuntimeParityError, match="^frontend_identity_unreachable$"):
        await verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=_settings(),
            transport=transport,
        )
    assert calls == 2


@pytest.mark.asyncio
async def test_public_offer_caller_deadline_preserves_probe_for_legacy_retry(
    monkeypatch,
) -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    monkeypatch.setattr(runtime_parity, "PUBLIC_RUNTIME_PARITY_CALLER_WAIT_SECONDS", 0.02)
    monkeypatch.setattr(runtime_parity, "RUNTIME_PARITY_TOTAL_TIMEOUT_SECONDS", 0.2)
    calls = 0
    completed = asyncio.Event()

    async def respond(_request):
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.05)
        completed.set()
        return _response()

    transport = httpx.MockTransport(respond)
    started = asyncio.get_running_loop().time()
    with pytest.raises(RuntimeParityError, match="^frontend_identity_pending$"):
        await verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=_settings(),
            transport=transport,
        )
    assert asyncio.get_running_loop().time() - started < 0.045

    # The caller timed out its own wait without cancelling the shared
    # verification. A later poll consumes the resulting attestation; the
    # production supervisor keeps that attestation continuously refreshed.
    await asyncio.wait_for(completed.wait(), timeout=0.2)
    await asyncio.sleep(0)
    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=_settings(),
        transport=transport,
    )
    assert calls == 1


@pytest.mark.asyncio
async def test_activation_attestation_seeds_public_offer_without_second_fetch() -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    settings = _settings()
    await record_verified_runtime_parity_for_public_offer(**IDENTITY, settings=settings)

    def forbidden(_request):
        pytest.fail("Activation attestation should satisfy the immediate public check")

    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=settings,
        transport=httpx.MockTransport(forbidden),
    )


@pytest.mark.asyncio
async def test_active_offer_supervisor_refreshes_strict_verified_attestation(
    monkeypatch,
) -> None:
    release = SimpleNamespace(**IDENTITY)
    session = AsyncMock()
    session.__aenter__.return_value = session
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    verify, record = AsyncMock(), AsyncMock()
    monkeypatch.setattr(runtime_parity_supervisor, "AsyncSessionLocal", lambda: session)
    monkeypatch.setattr(runtime_parity_supervisor, "verify_runtime_parity", verify)
    monkeypatch.setattr(
        runtime_parity_supervisor,
        "record_verified_runtime_parity_for_public_offer",
        record,
    )

    assert await runtime_parity_supervisor.refresh_active_public_runtime_parity(_settings())
    session.rollback.assert_awaited_once()
    verify.assert_awaited_once_with(**IDENTITY, settings=_settings())
    record.assert_awaited_once_with(**IDENTITY, settings=_settings())


@pytest.mark.asyncio
async def test_active_offer_supervisor_does_not_cache_failed_or_missing_parity(
    monkeypatch,
) -> None:
    session = AsyncMock()
    session.__aenter__.return_value = session
    no_release = SimpleNamespace(scalar_one_or_none=lambda: None)
    session.execute.return_value = no_release
    verify, record = AsyncMock(), AsyncMock()
    monkeypatch.setattr(runtime_parity_supervisor, "AsyncSessionLocal", lambda: session)
    monkeypatch.setattr(runtime_parity_supervisor, "verify_runtime_parity", verify)
    monkeypatch.setattr(
        runtime_parity_supervisor,
        "record_verified_runtime_parity_for_public_offer",
        record,
    )
    assert not await runtime_parity_supervisor.refresh_active_public_runtime_parity(_settings())
    verify.assert_not_awaited()
    record.assert_not_awaited()

    release = SimpleNamespace(**IDENTITY)
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    verify.side_effect = RuntimeParityError("frontend_release_mismatch")
    assert not await runtime_parity_supervisor.refresh_active_public_runtime_parity(_settings())
    record.assert_not_awaited()


@pytest.mark.asyncio
async def test_production_lifespan_warms_before_serving_and_cancels_supervisor(
    monkeypatch,
) -> None:
    events: list[str] = []
    supervisor_started = asyncio.Event()
    supervisor_stopped = asyncio.Event()

    async def warm(settings) -> bool:
        assert settings.env == "prod"
        events.append("warm")
        return True

    async def maintain(settings) -> None:
        assert settings.env == "prod"
        events.append("supervisor_started")
        supervisor_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            events.append("supervisor_stopped")
            supervisor_stopped.set()

    monkeypatch.setattr(application_main, "settings", SimpleNamespace(env="prod"))
    monkeypatch.setattr(application_main, "configure_logging", MagicMock())
    monkeypatch.setattr(application_main, "install_audit_listeners", MagicMock())
    monkeypatch.setattr(
        application_main,
        "get_event_bus",
        lambda: SimpleNamespace(subscribe=MagicMock()),
    )
    monkeypatch.setattr(application_main, "refresh_active_public_runtime_parity", warm)
    monkeypatch.setattr(application_main, "maintain_public_runtime_parity", maintain)

    async with application_main.lifespan(SimpleNamespace(version="3.1.21")):
        assert events == ["warm"]
        await asyncio.wait_for(supervisor_started.wait(), timeout=1)
        assert events == ["warm", "supervisor_started"]

    assert supervisor_stopped.is_set()
    assert events == ["warm", "supervisor_started", "supervisor_stopped"]


@pytest.mark.asyncio
async def test_production_lifespan_keeps_api_available_after_failed_warmup(
    monkeypatch,
) -> None:
    supervisor_started = asyncio.Event()
    supervisor_stopped = asyncio.Event()
    warning = MagicMock()

    async def fail_warm(_settings) -> bool:
        raise RuntimeError("temporary parity dependency failure")

    async def maintain(_settings) -> None:
        supervisor_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            supervisor_stopped.set()

    monkeypatch.setattr(application_main, "settings", SimpleNamespace(env="prod"))
    monkeypatch.setattr(application_main, "configure_logging", MagicMock())
    monkeypatch.setattr(application_main, "install_audit_listeners", MagicMock())
    monkeypatch.setattr(
        application_main,
        "get_event_bus",
        lambda: SimpleNamespace(subscribe=MagicMock()),
    )
    monkeypatch.setattr(application_main, "refresh_active_public_runtime_parity", fail_warm)
    monkeypatch.setattr(application_main, "maintain_public_runtime_parity", maintain)
    monkeypatch.setattr(application_main.logger, "warning", warning)

    async with application_main.lifespan(SimpleNamespace(version="3.1.21")):
        await asyncio.wait_for(supervisor_started.wait(), timeout=1)

    assert supervisor_stopped.is_set()
    warning.assert_called_once_with(
        "android_update.runtime_attestation_startup_failed",
        error_type="RuntimeError",
    )


@pytest.mark.asyncio
async def test_supervisor_retries_after_failure_and_propagates_cancellation(
    monkeypatch,
) -> None:
    refresh = AsyncMock(
        side_effect=[
            True,
            RuntimeError("temporary database failure"),
            False,
            asyncio.CancelledError(),
        ]
    )
    warning = MagicMock()
    settings = _settings()
    monkeypatch.setattr(
        runtime_parity_supervisor,
        "PUBLIC_RUNTIME_PARITY_REFRESH_INTERVAL_SECONDS",
        0,
    )
    monkeypatch.setattr(
        runtime_parity_supervisor,
        "refresh_active_public_runtime_parity",
        refresh,
    )
    monkeypatch.setattr(runtime_parity_supervisor.logger, "warning", warning)

    with pytest.raises(asyncio.CancelledError):
        await runtime_parity_supervisor.maintain_public_runtime_parity(settings)

    assert refresh.await_count == 4
    warning.assert_called_once_with(
        "android_update.runtime_attestation_refresh_failed",
        error_type="RuntimeError",
    )


@pytest.mark.asyncio
async def test_observed_same_release_refresh_failure_invalidates_positive_attestation(
    monkeypatch,
) -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    settings = _settings()
    await record_verified_runtime_parity_for_public_offer(**IDENTITY, settings=settings)

    release = SimpleNamespace(**IDENTITY)
    session = AsyncMock()
    session.__aenter__.return_value = session
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    monkeypatch.setattr(runtime_parity_supervisor, "AsyncSessionLocal", lambda: session)
    monkeypatch.setattr(
        runtime_parity_supervisor,
        "verify_runtime_parity",
        AsyncMock(side_effect=RuntimeParityError("frontend_release_mismatch")),
    )

    assert not await runtime_parity_supervisor.refresh_active_public_runtime_parity(settings)

    def unreachable(request):
        raise httpx.ConnectError("offline", request=request)

    with pytest.raises(RuntimeParityError, match="^frontend_identity_unreachable$"):
        await verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=settings,
            transport=httpx.MockTransport(unreachable),
        )


@pytest.mark.asyncio
async def test_strict_invalidation_fails_waiting_public_caller_without_cancelling_request() -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    entered = asyncio.Event()
    transport_cancelled = asyncio.Event()

    async def wait_for_invalidation(_request):
        entered.set()
        try:
            await asyncio.Event().wait()
        finally:
            transport_cancelled.set()

    waiting_caller = asyncio.create_task(
        verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=_settings(),
            transport=httpx.MockTransport(wait_for_invalidation),
        )
    )
    await asyncio.wait_for(entered.wait(), timeout=1)
    await runtime_parity.invalidate_runtime_parity_for_public_offer(**IDENTITY)

    with pytest.raises(RuntimeParityError):
        await waiting_caller
    assert transport_cancelled.is_set()

    def unreachable(request):
        raise httpx.ConnectError("offline", request=request)

    with pytest.raises(RuntimeParityError, match="^frontend_identity_unreachable$"):
        await verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=_settings(),
            transport=httpx.MockTransport(unreachable),
        )


@pytest.mark.asyncio
async def test_genuine_public_caller_cancellation_preserves_shared_probe() -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    entered = asyncio.Event()
    release_probe = asyncio.Event()
    probe_completed = asyncio.Event()
    calls = 0

    async def respond(_request):
        nonlocal calls
        calls += 1
        entered.set()
        await release_probe.wait()
        probe_completed.set()
        return _response()

    transport = httpx.MockTransport(respond)
    caller = asyncio.create_task(
        verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=_settings(),
            transport=transport,
        )
    )
    await asyncio.wait_for(entered.wait(), timeout=1)
    caller.cancel()
    with pytest.raises(asyncio.CancelledError):
        await caller

    release_probe.set()
    await asyncio.wait_for(probe_completed.wait(), timeout=1)
    await asyncio.sleep(0)
    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=_settings(),
        transport=transport,
    )
    assert calls == 1


@pytest.mark.asyncio
async def test_supervisor_recovers_and_reseeds_offer_after_strict_failure(
    monkeypatch,
) -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    settings = _settings()
    release = SimpleNamespace(**IDENTITY)

    def session_factory():
        session = AsyncMock()
        session.__aenter__.return_value = session
        session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
        return session

    verify = AsyncMock(
        side_effect=[RuntimeParityError("frontend_identity_unreachable"), None]
    )
    monkeypatch.setattr(runtime_parity_supervisor, "AsyncSessionLocal", session_factory)
    monkeypatch.setattr(runtime_parity_supervisor, "verify_runtime_parity", verify)

    assert not await runtime_parity_supervisor.refresh_active_public_runtime_parity(settings)
    assert await runtime_parity_supervisor.refresh_active_public_runtime_parity(settings)

    def forbidden(_request):
        pytest.fail("Successful supervisor recovery should reseed the public attestation")

    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=settings,
        transport=httpx.MockTransport(forbidden),
    )
    assert verify.await_count == 2


@pytest.mark.asyncio
async def test_public_offer_expired_attestation_does_not_mask_unreachable_frontend(
    monkeypatch,
) -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    now = 500.0
    reachable = True
    calls = 0
    monkeypatch.setattr(runtime_parity, "monotonic", lambda: now)

    def respond(request):
        nonlocal calls
        calls += 1
        if not reachable:
            raise httpx.ConnectError("offline", request=request)
        return _response()

    transport = httpx.MockTransport(respond)
    settings = _settings()
    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=settings,
        transport=transport,
    )
    reachable = False

    # The short, still-valid positive attestation is intentionally accepted.
    await verify_runtime_parity_for_public_offer(
        **IDENTITY,
        settings=settings,
        transport=transport,
    )
    assert calls == 1

    now += runtime_parity.PUBLIC_RUNTIME_PARITY_ATTESTATION_TTL_SECONDS + 0.001
    with pytest.raises(RuntimeParityError, match="^frontend_identity_unreachable$"):
        await verify_runtime_parity_for_public_offer(
            **IDENTITY,
            settings=settings,
            transport=transport,
        )
    assert calls == 2


@pytest.mark.asyncio
async def test_public_offer_distinct_probes_obey_global_concurrency_cap(
    monkeypatch,
) -> None:
    runtime_parity._reset_public_runtime_parity_state_for_tests()
    monkeypatch.setattr(runtime_parity, "PUBLIC_RUNTIME_PARITY_MAX_CONCURRENT_PROBES", 2)
    active = 0
    calls = 0
    maximum_active = 0
    cap_reached = asyncio.Event()
    release = asyncio.Event()

    def transport_for(identity):
        async def respond(_request):
            nonlocal active, calls, maximum_active
            active += 1
            calls += 1
            maximum_active = max(maximum_active, active)
            if active == 2:
                cap_reached.set()
            try:
                await release.wait()
                return _response(body=json.dumps(identity).encode())
            finally:
                active -= 1

        return httpx.MockTransport(respond)

    identities = [
        {"version_name": f"3.1.{index}", "source_git_sha": f"{index + 1:040x}"}
        for index in range(5)
    ]
    checks = [
        asyncio.create_task(
            verify_runtime_parity_for_public_offer(
                **identity,
                settings=_settings(
                    app_version=identity["version_name"],
                    app_revision=identity["source_git_sha"],
                ),
                transport=transport_for(identity),
            )
        )
        for identity in identities
    ]

    await asyncio.wait_for(cap_reached.wait(), timeout=1)
    await asyncio.sleep(0)
    assert calls == 2
    release.set()
    await asyncio.gather(*checks)

    assert calls == len(identities)
    assert maximum_active == 2


def _production(**overrides):
    return {
        "env": "prod",
        "jwt_secret": "j" * 48,
        "remote_assistance_pairing_secret": "p" * 48,
        "remote_assistance_relay_secret": "cnJycnJycnJycnJycnJycnJycnJycnJycnJycnJycnI=",
        "redis_url": f"redis://erp_backend:{'d' * 64}@redis:6379/0",
        **overrides,
    }


@pytest.mark.parametrize("environment", ["prod", "staging"])
def test_production_requires_immutable_matching_image_identity(monkeypatch, environment) -> None:
    monkeypatch.setattr(config, "read_backend_build_identity", lambda: ReleaseIdentity(**IDENTITY))
    matching = _settings(**_production(env=environment))
    assert matching.runtime_release_identity() == ReleaseIdentity(**IDENTITY)
    with pytest.raises(ValidationError, match="frozen"):
        matching.app_revision = "cd" * 20
    for values in ({"app_version": None}, {"app_revision": "short"}):
        with pytest.raises(ValidationError, match="APP_VERSION and APP_REVISION"):
            _settings(**_production(env=environment, **values))
    with pytest.raises(ValidationError, match="do not match"):
        _settings(**_production(env=environment, app_revision="cd" * 20))


def test_immutable_file_is_required_bounded_and_strict(monkeypatch, tmp_path) -> None:
    path = tmp_path / "identity.json"
    monkeypatch.setattr(release_identity, "RELEASE_IDENTITY_PATH", path)
    with pytest.raises(ValueError, match="missing or unreadable"):
        release_identity.read_backend_build_identity()
    path.write_bytes(BODY)
    assert release_identity.read_backend_build_identity() == ReleaseIdentity(**IDENTITY)
    path.write_bytes(BODY + b" " * 1024)
    with pytest.raises(ValueError, match="1024"):
        release_identity.read_backend_build_identity()
    with pytest.raises(ValueError, match="only a valid version_name"):
        parse_release_identity(BODY[:-1] + b',"source_git_sha":"' + b"ab" * 20 + b'"}')


def _release(status="staged") -> SimpleNamespace:
    now = datetime.now(UTC)
    return SimpleNamespace(
        id=uuid4(),
        channel="direct",
        version_code=29,
        **IDENTITY,
        update_url="https://erp.example.test/downloads/android/code29.apk",
        release_notes="Checked release",
        apk_sha256="cd" * 32,
        apk_size_bytes=100,
        apk_signing_cert_sha256="ef" * 32,
        manifest_sha256="01" * 32,
        source_release_ref="v3.1.21",
        source_workflow_run_id=1,
        source_workflow_run_attempt=1,
        status=status,
        registered_at=now,
        activated_at=now if status == "active" else None,
        activated_by=None,
        withdrawn_at=None,
        withdrawn_by=None,
        updated_at=now,
    )


def _public_offer_session_factory(session: AsyncMock) -> MagicMock:
    session.__aenter__.return_value = session
    session.__aexit__.return_value = False
    return MagicMock(return_value=session)


@pytest.mark.parametrize("status", ["staged", "active", "withdrawn"])
@pytest.mark.asyncio
async def test_activation_checks_parity_after_apk_before_any_state_or_audit_write(
    monkeypatch, status
) -> None:
    release = _release(status)
    snapshot = vars(release).copy()
    session = AsyncMock()
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    events = []

    async def apk(*_args, **_kwargs):
        events.append("apk")

    async def parity(**_kwargs):
        events.append("parity")
        raise RuntimeParityError("frontend_release_mismatch")

    monkeypatch.setattr(release_router, "get_settings", _settings)
    monkeypatch.setattr(release_router, "verify_public_apk", apk)
    monkeypatch.setattr(release_router, "verify_runtime_parity", parity)
    monkeypatch.setattr(
        release_router,
        "record_verified_runtime_parity_for_public_offer",
        AsyncMock(side_effect=AssertionError("failed parity must not seed an attestation")),
    )
    with pytest.raises(ServiceUnavailableError, match="No release state was changed"):
        await release_router.activate_android_release(release.id, session, SimpleNamespace())
    assert events == ["apk", "parity"]
    assert vars(release) == snapshot
    assert session.execute.await_count == 1  # no advisory lock or write follows failure
    session.add.assert_not_called()
    session.flush.assert_not_awaited()
    session.rollback.assert_awaited_once()


@pytest.mark.asyncio
async def test_registration_failure_cannot_open_database_or_insert(monkeypatch) -> None:
    async def parity(**_kwargs):
        raise RuntimeParityError("backend_release_mismatch")

    def database():
        pytest.fail("Registration opened the database before parity passed")

    monkeypatch.setattr(registration, "get_settings", _settings)
    monkeypatch.setattr(registration, "verify_runtime_parity", parity)
    monkeypatch.setattr(registration, "AsyncSessionLocal", database)
    release = _release()
    with pytest.raises(SystemExit, match="Release was not staged"):
        await registration.register(release)


@pytest.mark.asyncio
async def test_already_active_success_reverifies_without_duplicate_audit(monkeypatch) -> None:
    release = _release("active")
    session = AsyncMock()
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    apk, parity, attest = AsyncMock(), AsyncMock(), AsyncMock()
    monkeypatch.setattr(release_router, "get_settings", _settings)
    monkeypatch.setattr(release_router, "verify_public_apk", apk)
    monkeypatch.setattr(release_router, "verify_runtime_parity", parity)
    monkeypatch.setattr(release_router, "record_verified_runtime_parity_for_public_offer", attest)
    result = await release_router.activate_android_release(release.id, session, SimpleNamespace())
    assert result.status == "active"
    apk.assert_awaited_once()
    parity.assert_awaited_once()
    attest.assert_awaited_once_with(**IDENTITY, settings=_settings())
    session.add.assert_not_called()
    session.flush.assert_not_awaited()


@pytest.mark.asyncio
async def test_offer_withdrawn_during_identity_check_is_not_advertised(monkeypatch) -> None:
    release = _release("active")
    session = AsyncMock()
    session.execute.side_effect = [
        SimpleNamespace(scalar_one_or_none=lambda: release),
        SimpleNamespace(scalar_one_or_none=lambda: None),
    ]
    monkeypatch.setattr(public_router, "get_settings", _settings)
    monkeypatch.setattr(
        public_router,
        "AsyncSessionLocal",
        _public_offer_session_factory(session),
    )
    monkeypatch.setattr(public_router, "verify_runtime_parity_for_public_offer", AsyncMock())
    result = await public_router.client_compatibility(
        Request({"type": "http", "headers": []}), Response(), "android", 21
    )
    assert result.status == "supported"
    assert result.update_url is None


@pytest.mark.asyncio
async def test_public_offer_work_is_bounded_inside_legacy_client_budget(monkeypatch) -> None:
    release = _release("active")
    session = AsyncMock()

    async def slow_execute(*_args, **_kwargs):
        await asyncio.sleep(1)
        return SimpleNamespace(scalar_one_or_none=lambda: release)

    session.execute.side_effect = slow_execute
    monkeypatch.setattr(public_router, "get_settings", _settings)
    monkeypatch.setattr(
        public_router,
        "AsyncSessionLocal",
        _public_offer_session_factory(session),
    )
    monkeypatch.setattr(public_router, "PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS", 0.03)

    started = asyncio.get_running_loop().time()
    result = await public_router.client_compatibility(
        Request({"type": "http", "headers": []}), Response(), "android", 21
    )
    elapsed = asyncio.get_running_loop().time() - started

    assert elapsed < 0.1
    assert result.status == "supported"
    assert result.update_url is None
    state = public_router._public_offer_lookup_state()
    assert state.in_flight is not None
    assert session.execute.await_count == 1
    state.in_flight.cancel()
    await asyncio.gather(state.in_flight, return_exceptions=True)
    public_router._reset_public_offer_lookup_state_for_tests()


@pytest.mark.asyncio
async def test_hanging_db_rollback_is_detached_and_reused_across_legacy_polls(
    monkeypatch,
) -> None:
    release = _release("active")
    session = AsyncMock()
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    rollback_started = asyncio.Event()

    async def hanging_rollback() -> None:
        rollback_started.set()
        await asyncio.Event().wait()

    session.rollback.side_effect = hanging_rollback
    factory = _public_offer_session_factory(session)
    monkeypatch.setattr(public_router, "get_settings", _settings)
    monkeypatch.setattr(public_router, "AsyncSessionLocal", factory)
    monkeypatch.setattr(public_router, "PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS", 0.03)

    started = asyncio.get_running_loop().time()
    first = await public_router.client_compatibility(
        Request({"type": "http", "headers": []}), Response(), "android", 21
    )
    first_elapsed = asyncio.get_running_loop().time() - started
    assert rollback_started.is_set()
    assert first_elapsed < 0.1
    assert first.status == "supported"

    second = await public_router.client_compatibility(
        Request({"type": "http", "headers": []}), Response(), "android", 21
    )
    assert second.status == "supported"
    assert factory.call_count == 1
    assert session.execute.await_count == 1

    state = public_router._public_offer_lookup_state()
    assert state.in_flight is not None
    state.in_flight.cancel()
    await asyncio.gather(state.in_flight, return_exceptions=True)
    public_router._reset_public_offer_lookup_state_for_tests()


@pytest.mark.asyncio
async def test_cancelled_legacy_caller_does_not_cancel_shared_database_lookup(
    monkeypatch,
) -> None:
    session = AsyncMock()
    execute_started = asyncio.Event()

    async def hanging_execute(*_args, **_kwargs):
        execute_started.set()
        await asyncio.Event().wait()

    session.execute.side_effect = hanging_execute
    monkeypatch.setattr(public_router, "get_settings", _settings)
    monkeypatch.setattr(
        public_router,
        "AsyncSessionLocal",
        _public_offer_session_factory(session),
    )
    monkeypatch.setattr(public_router, "PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS", 30.0)

    caller = asyncio.create_task(
        public_router.client_compatibility(
            Request({"type": "http", "headers": []}), Response(), "android", 21
        )
    )
    await asyncio.wait_for(execute_started.wait(), timeout=0.1)
    caller.cancel()
    with pytest.raises(asyncio.CancelledError):
        await caller

    state = public_router._public_offer_lookup_state()
    assert state.in_flight is not None
    assert not state.in_flight.done()
    state.in_flight.cancel()
    await asyncio.gather(state.in_flight, return_exceptions=True)
    public_router._reset_public_offer_lookup_state_for_tests()


def test_public_offer_deadlines_leave_explicit_code21_transport_margin() -> None:
    assert runtime_parity.LEGACY_CODE21_COMPATIBILITY_TIMEOUT_SECONDS == 3.0
    assert (
        public_router.PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS
        <= runtime_parity.LEGACY_CODE21_COMPATIBILITY_TIMEOUT_SECONDS - 1.0
    )
    assert (
        runtime_parity.PUBLIC_RUNTIME_PARITY_CALLER_WAIT_SECONDS
        <= public_router.PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS - 0.5
    )
    assert (
        runtime_parity_supervisor.PUBLIC_RUNTIME_PARITY_REFRESH_INTERVAL_SECONDS
        + runtime_parity.RUNTIME_PARITY_TOTAL_TIMEOUT_SECONDS
        + 1.0
        <= runtime_parity.PUBLIC_RUNTIME_PARITY_ATTESTATION_TTL_SECONDS
    )


@pytest.mark.asyncio
async def test_slow_parity_probe_continues_without_blocking_legacy_route(
    monkeypatch,
) -> None:
    release = _release("active")
    session = AsyncMock()
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    parity_started = asyncio.Event()
    parity_cancelled = asyncio.Event()

    async def slow_parity(**_kwargs) -> None:
        parity_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            parity_cancelled.set()

    monkeypatch.setattr(public_router, "get_settings", _settings)
    monkeypatch.setattr(
        public_router,
        "AsyncSessionLocal",
        _public_offer_session_factory(session),
    )
    monkeypatch.setattr(public_router, "verify_runtime_parity_for_public_offer", slow_parity)
    monkeypatch.setattr(public_router, "PUBLIC_COMPATIBILITY_OFFER_BUDGET_SECONDS", 0.03)

    started = asyncio.get_running_loop().time()
    result = await public_router.client_compatibility(
        Request({"type": "http", "headers": []}), Response(), "android", 21
    )
    elapsed = asyncio.get_running_loop().time() - started

    assert parity_started.is_set()
    assert not parity_cancelled.is_set()
    assert elapsed < 0.1
    assert result.status == "supported"
    assert result.update_url is None
    assert session.rollback.await_count == 1
    state = public_router._public_offer_lookup_state()
    assert state.in_flight is not None
    state.in_flight.cancel()
    await asyncio.gather(state.in_flight, return_exceptions=True)
    assert parity_cancelled.is_set()
    public_router._reset_public_offer_lookup_state_for_tests()


@pytest.mark.parametrize("current", [7, 21])
@pytest.mark.asyncio
async def test_rollback_suppresses_optional_offer_without_weakening_minimum(
    monkeypatch, current
) -> None:
    release = _release("active")
    session = AsyncMock()
    session.execute.return_value = SimpleNamespace(scalar_one_or_none=lambda: release)
    settings = _settings()

    async def parity(**_kwargs):
        raise RuntimeParityError("backend_release_mismatch")

    monkeypatch.setattr(public_router, "get_settings", lambda: settings)
    monkeypatch.setattr(
        public_router,
        "AsyncSessionLocal",
        _public_offer_session_factory(session),
    )
    monkeypatch.setattr(public_router, "verify_runtime_parity_for_public_offer", parity)
    result = await public_router.client_compatibility(
        Request({"type": "http", "headers": []}), Response(), "android", current
    )
    assert result.minimum_supported_version_code == 8
    assert result.latest_version_code == 8
    assert result.status == ("update_required" if current < 8 else "supported")
    assert result.update_url is None
    assert result.apk_sha256 is None
    assert release.status == "active"
    session.add.assert_not_called()
    session.flush.assert_not_awaited()
