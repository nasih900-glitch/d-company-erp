"""Fail-closed Android/backend/web release convergence checks."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from time import monotonic
from typing import TYPE_CHECKING
from urllib.parse import urlsplit
from weakref import WeakKeyDictionary

import httpx

from app.core.release_identity import (
    MAX_RELEASE_IDENTITY_BYTES,
    ReleaseIdentity,
    parse_release_identity,
)

if TYPE_CHECKING:
    from app.core.config import Settings

FRONTEND_RELEASE_IDENTITY_PATH = "/.well-known/erp-release.json"
RUNTIME_PARITY_TOTAL_TIMEOUT_SECONDS = 4.0
RUNTIME_PARITY_REQUEST_TIMEOUT_SECONDS = 2.0

# Signed Code 21 is already installed in the field and cannot be changed.  Its
# compatibility request gives the complete proxy + API response three seconds,
# so the public offer path must return with meaningful margin inside that
# predecessor budget.  The slower four-second probe may continue in the
# background and warm the next retry, but must never hold the public response.
LEGACY_CODE21_COMPATIBILITY_TIMEOUT_SECONDS = 3.0
PUBLIC_RUNTIME_PARITY_CALLER_WAIT_SECONDS = 1.0

# The public compatibility endpoint is unauthenticated and can be polled by every
# tablet.  A short, success-only attestation avoids turning those requests into
# an unbounded loopback HTTP fan-out.  Activation and registration deliberately
# continue to call ``verify_runtime_parity`` and therefore never use this cache.
PUBLIC_RUNTIME_PARITY_ATTESTATION_TTL_SECONDS = 15.0
PUBLIC_RUNTIME_PARITY_MAX_CONCURRENT_PROBES = 2

_PublicParityKey = tuple[str, str, str]


@dataclass(slots=True)
class _PublicRuntimeParityState:
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    probe_slots: asyncio.Semaphore = field(
        default_factory=lambda: asyncio.Semaphore(PUBLIC_RUNTIME_PARITY_MAX_CONCURRENT_PROBES)
    )
    positive_attestations: dict[_PublicParityKey, float] = field(default_factory=dict)
    in_flight: dict[_PublicParityKey, asyncio.Task[None]] = field(default_factory=dict)


# Asyncio synchronization primitives are loop-bound.  Keeping one process-wide
# state per live event loop gives the production worker a global cap without
# leaking a lock from one pytest/application loop into another.
_public_runtime_parity_states: WeakKeyDictionary[
    asyncio.AbstractEventLoop, _PublicRuntimeParityState
] = WeakKeyDictionary()


class RuntimeParityError(RuntimeError):
    """Categorical owner-safe failure without remote payloads or infrastructure details."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _frontend_identity_url(settings: Settings) -> str:
    configured = settings.android_update_allowed_origin
    if configured is None:
        raise RuntimeParityError("runtime_origin_not_configured")
    parsed = urlsplit(str(configured))
    try:
        _ = parsed.port
    except ValueError as exc:
        raise RuntimeParityError("runtime_origin_invalid") from exc
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise RuntimeParityError("runtime_origin_invalid")
    return f"{parsed.scheme}://{parsed.netloc}{FRONTEND_RELEASE_IDENTITY_PATH}"


def _validated_backend_identity(
    *,
    version_name: str,
    source_git_sha: str,
    settings: Settings,
) -> ReleaseIdentity:
    """Validate the live backend identity before any cache or network access."""
    try:
        backend = settings.runtime_release_identity()
    except ValueError as exc:
        raise RuntimeParityError("backend_identity_invalid") from exc
    # Dev images can boot with explicit sentinel identities, but agreement
    # between two dev images must never authorize a distributable APK.
    if (
        backend.version_name == "0.0.0"
        or backend.source_git_sha == "0" * 40
        or version_name == "0.0.0"
        or source_git_sha == "0" * 40
    ):
        raise RuntimeParityError("runtime_identity_not_release")
    if backend.version_name != version_name or backend.source_git_sha != source_git_sha:
        raise RuntimeParityError("backend_release_mismatch")
    return backend


async def _verify_frontend_identity(
    *,
    backend: ReleaseIdentity,
    settings: Settings,
    transport: httpx.AsyncBaseTransport | None = None,
) -> None:
    """Fetch and compare the independently served frontend identity."""
    url = _frontend_identity_url(settings)
    payload = bytearray()
    try:
        # Bound both per-operation latency and the total (including slow-drip
        # bodies). Release control must not hang, or tie up DB locks, offline.
        async with (
            asyncio.timeout(RUNTIME_PARITY_TOTAL_TIMEOUT_SECONDS),
            httpx.AsyncClient(
                timeout=httpx.Timeout(RUNTIME_PARITY_REQUEST_TIMEOUT_SECONDS),
                follow_redirects=False,
                trust_env=False,
                transport=transport,
            ) as client,
            client.stream(
                "GET",
                url,
                headers={
                    "Accept": "application/json",
                    "Accept-Encoding": "identity",
                    "Cache-Control": "no-cache, no-store",
                    "Pragma": "no-cache",
                    "User-Agent": "DCompany-Runtime-Parity/1",
                },
            ) as response,
        ):
            if response.status_code != 200:
                raise RuntimeParityError("frontend_identity_http_error")
            if (
                response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                != "application/json"
            ):
                raise RuntimeParityError("frontend_identity_content_type_invalid")
            if response.headers.get("X-Content-Type-Options", "").lower() != "nosniff":
                raise RuntimeParityError("frontend_identity_nosniff_missing")
            cache_tokens = {
                value.strip().lower()
                for value in response.headers.get("Cache-Control", "").split(",")
            }
            if "no-store" not in cache_tokens:
                raise RuntimeParityError("frontend_identity_cache_policy_invalid")
            if response.headers.get("Content-Encoding", "identity").lower() != "identity":
                raise RuntimeParityError("frontend_identity_encoding_invalid")
            declared_size = response.headers.get("Content-Length")
            if declared_size is not None:
                try:
                    size = int(declared_size)
                except ValueError as exc:
                    raise RuntimeParityError("frontend_identity_size_invalid") from exc
                if size < 1 or size > MAX_RELEASE_IDENTITY_BYTES:
                    raise RuntimeParityError("frontend_identity_size_invalid")
            async for chunk in response.aiter_bytes():
                if len(payload) + len(chunk) > MAX_RELEASE_IDENTITY_BYTES:
                    raise RuntimeParityError("frontend_identity_size_invalid")
                payload.extend(chunk)
            if not payload:
                raise RuntimeParityError("frontend_identity_size_invalid")
            if declared_size is not None and len(payload) != int(declared_size):
                raise RuntimeParityError("frontend_identity_size_invalid")
    except RuntimeParityError:
        raise
    except (TimeoutError, httpx.HTTPError, OSError) as exc:
        raise RuntimeParityError("frontend_identity_unreachable") from exc

    try:
        frontend = parse_release_identity(bytes(payload))
    except ValueError as exc:
        raise RuntimeParityError("frontend_identity_invalid") from exc
    if frontend != backend:
        raise RuntimeParityError("frontend_release_mismatch")


async def verify_runtime_parity(
    *,
    version_name: str,
    source_git_sha: str,
    settings: Settings,
    transport: httpx.AsyncBaseTransport | None = None,
) -> None:
    """Strict uncached check used by release registration and activation."""
    backend = _validated_backend_identity(
        version_name=version_name,
        source_git_sha=source_git_sha,
        settings=settings,
    )
    await _verify_frontend_identity(backend=backend, settings=settings, transport=transport)


def _public_runtime_parity_state() -> _PublicRuntimeParityState:
    loop = asyncio.get_running_loop()
    state = _public_runtime_parity_states.get(loop)
    if state is None:
        state = _PublicRuntimeParityState()
        _public_runtime_parity_states[loop] = state
    return state


def _consume_background_exception(task: asyncio.Task[None]) -> None:
    """Prevent an abandoned shielded probe from logging an unhandled exception."""
    if task.cancelled():
        return
    task.exception()


async def _run_public_runtime_parity_probe(
    *,
    state: _PublicRuntimeParityState,
    key: _PublicParityKey,
    version_name: str,
    source_git_sha: str,
    backend: ReleaseIdentity,
    settings: Settings,
    transport: httpx.AsyncBaseTransport | None,
) -> None:
    success = False
    try:
        async with state.probe_slots:
            await _verify_frontend_identity(
                backend=backend,
                settings=settings,
                transport=transport,
            )
            # Test/dev settings can be mutable.  Revalidate before publishing a
            # positive attestation so a mid-probe identity change cannot seed it.
            _validated_backend_identity(
                version_name=version_name,
                source_git_sha=source_git_sha,
                settings=settings,
            )
        success = True
    finally:
        async with state.lock:
            if success:
                state.positive_attestations[key] = (
                    monotonic() + PUBLIC_RUNTIME_PARITY_ATTESTATION_TTL_SECONDS
                )
            if state.in_flight.get(key) is asyncio.current_task():
                state.in_flight.pop(key, None)


async def verify_runtime_parity_for_public_offer(
    *,
    version_name: str,
    source_git_sha: str,
    settings: Settings,
    transport: httpx.AsyncBaseTransport | None = None,
) -> None:
    """Bound public self-probes while retaining fail-closed release checks.

    Backend identity is checked on every call, before a positive attestation is
    consulted.  Only successful frontend attestations are cached, briefly.
    Failures are never cached or served stale, and concurrent callers for the
    same release share one bounded probe.
    """
    backend = _validated_backend_identity(
        version_name=version_name,
        source_git_sha=source_git_sha,
        settings=settings,
    )
    url = _frontend_identity_url(settings)
    key = (url, backend.version_name, backend.source_git_sha)
    state = _public_runtime_parity_state()

    async with state.lock:
        now = monotonic()
        for stale_key, expires_at in tuple(state.positive_attestations.items()):
            if expires_at <= now:
                state.positive_attestations.pop(stale_key, None)
        if state.positive_attestations.get(key, 0.0) > now:
            return

        probe = state.in_flight.get(key)
        if probe is None:
            probe = asyncio.create_task(
                _run_public_runtime_parity_probe(
                    state=state,
                    key=key,
                    version_name=version_name,
                    source_git_sha=source_git_sha,
                    backend=backend,
                    settings=settings,
                    transport=transport,
                ),
                name="public-runtime-parity",
            )
            probe.add_done_callback(_consume_background_exception)
            state.in_flight[key] = probe

    # A disconnected or legacy client must not cancel the shared attestation
    # needed by the next caller.  Bound only this caller's wait; the shared
    # probe retains its strict four-second end-to-end network timeout and seeds
    # the short positive cache if it completes successfully.
    try:
        async with asyncio.timeout(PUBLIC_RUNTIME_PARITY_CALLER_WAIT_SECONDS):
            await asyncio.shield(probe)
    except TimeoutError as exc:
        raise RuntimeParityError("frontend_identity_pending") from exc
    except asyncio.CancelledError as exc:
        caller = asyncio.current_task()
        if caller is not None and caller.cancelling():
            raise
        # A supervisor invalidation cancels the shared probe, not this request.
        # Convert that internal cancellation into the same fail-closed contract
        # consumed by the public compatibility route.
        raise RuntimeParityError("frontend_identity_invalidated") from exc


async def record_verified_runtime_parity_for_public_offer(
    *,
    version_name: str,
    source_git_sha: str,
    settings: Settings,
) -> None:
    """Seed the public cache after activation's strict uncached verification.

    This records no weaker evidence: the caller must have just completed
    ``verify_runtime_parity`` for the same immutable identity.  Backend
    identity and the configured frontend origin are revalidated here so a
    mutable test setting or mid-activation configuration change cannot seed a
    different key.
    """
    backend = _validated_backend_identity(
        version_name=version_name,
        source_git_sha=source_git_sha,
        settings=settings,
    )
    key = (
        _frontend_identity_url(settings),
        backend.version_name,
        backend.source_git_sha,
    )
    state = _public_runtime_parity_state()
    async with state.lock:
        state.positive_attestations[key] = (
            monotonic() + PUBLIC_RUNTIME_PARITY_ATTESTATION_TTL_SECONDS
        )


async def invalidate_runtime_parity_for_public_offer(
    *,
    version_name: str,
    source_git_sha: str,
) -> None:
    """Remove stale evidence after a strict check observes a release failure.

    Match by immutable release identity rather than the configured origin.  If
    the origin itself changed or became invalid, an attestation recorded for
    the previous origin must not remain usable.  Matching in-flight probes are
    cancelled as well so they cannot re-publish evidence captured before the
    strict failure was observed.
    """
    state = _public_runtime_parity_state()
    cancelled: list[asyncio.Task[None]] = []
    async with state.lock:
        candidates = set(state.positive_attestations) | set(state.in_flight)
        matching_keys = {
            key for key in candidates if key[1:] == (version_name, source_git_sha)
        }
        for key in matching_keys:
            state.positive_attestations.pop(key, None)
            probe = state.in_flight.pop(key, None)
            if probe is not None and probe is not asyncio.current_task() and not probe.done():
                probe.cancel()
                cancelled.append(probe)
    if cancelled:
        await asyncio.gather(*cancelled, return_exceptions=True)


async def invalidate_all_runtime_parity_for_public_offers() -> None:
    """Drop every process-local attestation when active-release truth is unknown."""
    state = _public_runtime_parity_state()
    cancelled: list[asyncio.Task[None]] = []
    async with state.lock:
        state.positive_attestations.clear()
        for probe in state.in_flight.values():
            if probe is not asyncio.current_task() and not probe.done():
                probe.cancel()
                cancelled.append(probe)
        state.in_flight.clear()
    if cancelled:
        await asyncio.gather(*cancelled, return_exceptions=True)


def _reset_public_runtime_parity_state_for_tests() -> None:
    """Clear process-local public attestation state between isolated tests."""
    _public_runtime_parity_states.clear()
