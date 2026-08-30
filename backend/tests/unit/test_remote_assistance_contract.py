"""Pure contract/security tests for constrained remote assistance."""

from __future__ import annotations

import inspect
import io
from uuid import uuid1, uuid4

import pytest
from PIL import Image
from pydantic import ValidationError as PydanticValidationError
from redis.exceptions import RedisError
from sqlalchemy import CheckConstraint, UniqueConstraint

from app.api.v1.remote_assistance import router as remote_router
from app.api.v1.remote_assistance.router import CommandCreate, RemoteRequestCreate
from app.core.config import Settings
from app.core.errors import (
    ConflictError,
    RateLimitError,
    ServiceUnavailableError,
    ValidationError,
)
from app.models import RemoteAssistanceCommand, RemoteAssistanceGrant, RemoteAssistanceSession
from app.services.realtime import resource_for_path, resources_for_path
from app.services.remote_assistance import relay
from app.services.remote_assistance.relay import ValidatedJpeg, validate_and_sanitize_jpeg


def _jpeg(width: int, height: int, *, with_exif: bool = False) -> bytes:
    output = io.BytesIO()
    image = Image.new("RGB", (width, height), color=(24, 32, 48))
    exif = Image.Exif()
    if with_exif:
        exif[0x010E] = "must-not-survive"
    image.save(output, format="JPEG", quality=85, exif=exif)
    return output.getvalue()


def test_command_contract_is_closed_and_excludes_financial_control() -> None:
    command = CommandCreate(
        command_id=uuid4(),
        sequence=1,
        type="navigate",
        module="gaming",
    )
    assert command.module == "gaming"

    for unsafe in (
        {"type": "navigate", "module": "finance"},
        {"type": "payment", "module": None},
        {"type": "refund", "module": None},
        {"type": "end_session", "module": None},
    ):
        with pytest.raises(PydanticValidationError):
            CommandCreate(command_id=uuid4(), sequence=1, **unsafe)  # type: ignore[arg-type]

    with pytest.raises(PydanticValidationError):
        CommandCreate(
            command_id=uuid4(),
            sequence=1,
            type="refresh",
            module=None,
            amount_minor=1,
        )
    with pytest.raises(PydanticValidationError):
        CommandCreate(command_id=uuid4(), sequence=1, type="navigate")


def test_all_mutation_keys_require_random_uuid_v4() -> None:
    with pytest.raises(PydanticValidationError, match="UUID v4"):
        CommandCreate(command_id=uuid1(), sequence=1, type="refresh")
    with pytest.raises(PydanticValidationError, match="UUID v4"):
        RemoteRequestCreate(
            request_id=uuid1(),
            installation_id=uuid4(),
            grant_kind="one_time",
            grant_ttl_seconds=600,
            session_ttl_seconds=600,
        )


def test_default_online_window_covers_more_than_two_android_heartbeat_intervals() -> None:
    default_window = Settings.model_fields["remote_assistance_device_online_seconds"].default
    assert default_window == 45
    assert default_window > 2 * 20
    assert (
        Settings.model_fields["remote_assistance_frame_decode_min_interval_ms"].default
        == 2_000
    )


def test_jpeg_validation_accepts_landscape_and_portrait_and_strips_metadata() -> None:
    for width, height in ((960, 540), (256, 540)):
        clean = validate_and_sanitize_jpeg(
            _jpeg(width, height, with_exif=True),
            declared_width=width,
            declared_height=height,
        )
        assert clean.width == width
        assert clean.height == height
        with Image.open(io.BytesIO(clean.content)) as decoded:
            assert decoded.format == "JPEG"
            assert decoded.size == (width, height)
            assert not decoded.getexif()


def test_jpeg_validation_rejects_spoofed_geometry_and_non_jpeg() -> None:
    with pytest.raises(ValidationError, match="do not match"):
        validate_and_sanitize_jpeg(
            _jpeg(960, 540),
            declared_width=959,
            declared_height=540,
        )
    with pytest.raises(ValidationError, match="complete JPEG"):
        validate_and_sanitize_jpeg(
            b"not-an-image",
            declared_width=960,
            declared_height=540,
        )
    with pytest.raises(ValidationError, match="outside"):
        validate_and_sanitize_jpeg(
            _jpeg(200, 540),
            declared_width=200,
            declared_height=540,
        )


class _RelayClient:
    def __init__(self, result: object = None, error: Exception | None = None) -> None:
        self.result = result
        self.error = error

    async def eval(self, *_args: object) -> object:
        if self.error is not None:
            raise self.error
        return self.result

    async def ping(self) -> object:
        if self.error is not None:
            raise self.error
        return self.result

    async def aclose(self) -> None:
        return None


@pytest.mark.asyncio
async def test_frame_relay_fails_closed_and_enforces_rate_and_sequence(monkeypatch) -> None:
    frame = ValidatedJpeg(content=_jpeg(640, 480), width=640, height=480)

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(error=RedisError("offline")),
    )
    with pytest.raises(ServiceUnavailableError, match="sharing stopped"):
        await relay.store_latest_frame(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
            frame=frame,
        )

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(result=[0, 750]),
    )
    with pytest.raises(RateLimitError) as rate_limited:
        await relay.store_latest_frame(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
            frame=frame,
        )
    assert rate_limited.value.details["limit_per_second"] == 1

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(result=[2, 7]),
    )
    with pytest.raises(ConflictError) as replayed:
        await relay.store_latest_frame(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=7,
            frame=frame,
        )
    assert replayed.value.details == {"latest_sequence": 7}


@pytest.mark.asyncio
async def test_relay_availability_check_fails_closed(monkeypatch) -> None:
    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(error=RedisError("offline")),
    )
    with pytest.raises(ServiceUnavailableError, match="relay is not ready"):
        await relay.ensure_relay_available()


@pytest.mark.asyncio
async def test_predecode_admission_is_rate_limited_and_fails_closed(monkeypatch) -> None:
    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(result=[0, 1_750]),
    )
    with pytest.raises(RateLimitError) as rate_limited:
        await relay.admit_frame_upload(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
        )
    assert rate_limited.value.details == {
        "minimum_interval_ms": 2_000,
        "retry_after_seconds": 2,
    }

    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: _RelayClient(error=RedisError("offline")),
    )
    with pytest.raises(ServiceUnavailableError, match="sharing stopped"):
        await relay.admit_frame_upload(
            company_id=uuid4(),
            session_id=uuid4(),
            frame_id=uuid4(),
            sequence=1,
        )


@pytest.mark.asyncio
async def test_rate_admission_cannot_be_bypassed_by_reusing_frame_uuid(monkeypatch) -> None:
    calls: list[tuple[object, ...]] = []

    class _CapturingRelayClient(_RelayClient):
        async def eval(self, *args: object) -> object:
            calls.append(args)
            return [1, 123]

    client = _CapturingRelayClient()
    monkeypatch.setattr(
        relay,
        "request_path_redis_binary_client",
        lambda _url: client,
    )
    company_id = uuid4()
    session_id = uuid4()
    frame_id = uuid4()
    frame = ValidatedJpeg(content=_jpeg(640, 480), width=640, height=480)
    await relay.store_latest_frame(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame_id,
        sequence=1,
        frame=frame,
    )
    await relay.store_latest_frame(
        company_id=company_id,
        session_id=session_id,
        frame_id=frame_id,
        sequence=2,
        frame=frame,
    )

    # eval args: script, key-count, four keys, then rate window, limit,
    # sequence, and the hashed rate-window member.
    assert calls[0][9] != calls[1][9]
    script = str(calls[0][0])
    assert script.index("local prior") < script.index("local cutoff")


def test_frame_decode_is_explicitly_offloaded_from_async_request_loop() -> None:
    source = inspect.getsource(remote_router.upload_frame)
    assert source.index("await admit_frame_upload(") < source.index(
        "await _read_bounded_body("
    )
    assert "await run_in_threadpool(" in source
    assert "await _read_bounded_body(" in source


def test_models_contain_no_screenshot_or_frame_blob_column() -> None:
    for model in (RemoteAssistanceGrant, RemoteAssistanceSession, RemoteAssistanceCommand):
        column_names = set(model.__table__.columns.keys())
        assert "frame" not in column_names
        assert "screenshot" not in column_names
        assert "content" not in column_names


def test_models_lock_idempotency_and_terminal_evidence_at_the_database_layer() -> None:
    grant_unique_names = {
        constraint.name
        for constraint in RemoteAssistanceGrant.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    session_unique_names = {
        constraint.name
        for constraint in RemoteAssistanceSession.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert {
        "uq_remote_assistance_grants_company_decision_id",
        "uq_remote_assistance_grants_company_revocation_id",
    } <= grant_unique_names
    assert {
        "uq_remote_assistance_sessions_company_start_id",
        "uq_remote_assistance_sessions_company_end_id",
    } <= session_unique_names

    grant_checks = {
        constraint.name: str(constraint.sqltext)
        for constraint in RemoteAssistanceGrant.__table__.constraints
        if isinstance(constraint, CheckConstraint)
    }
    session_checks = {
        constraint.name: str(constraint.sqltext)
        for constraint in RemoteAssistanceSession.__table__.constraints
        if isinstance(constraint, CheckConstraint)
    }
    assert "status = 'revoked'" in grant_checks[
        "ck_remote_assistance_grants_revocation_evidence"
    ]
    assert "status = 'active'" in session_checks[
        "ck_remote_assistance_sessions_start_evidence"
    ]
    assert "ended_by_user_id IS NOT NULL" in session_checks[
        "ck_remote_assistance_sessions_end_evidence"
    ]


def test_remote_assistance_writes_emit_realtime_wakeup_and_audit_invalidation() -> None:
    path = "/api/v1/remote-assistance/device/grants/grant-id/decision"
    assert resource_for_path(path) == "remote_assistance"
    assert resources_for_path(path) == ("remote_assistance", "audit")
