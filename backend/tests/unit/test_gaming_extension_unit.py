"""Unit-level idempotency contract for paid gaming-session extensions."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.api.v1.gaming import router as gaming_router
from app.core.errors import BusinessRuleError
from app.core.tenant import TenantContext


class _NoDatabaseSession:
    """Fail if an idempotency rejection/replay reaches business queries."""

    async def execute(self, statement):
        raise AssertionError(f"unexpected database statement: {statement}")


def _tenant() -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("gaming_supervisor",),
    )


def _request(*, key: str | None = None, request_hash: str | None = None):
    state = SimpleNamespace()
    if key is not None:
        state.idempotency_key = key
    if request_hash is not None:
        state.idempotency_request_hash = request_hash
    return SimpleNamespace(state=state)


def _stored_response_body() -> dict:
    started_at = datetime(2026, 8, 25, 12, 0, tzinfo=UTC)
    return {
        "id": str(uuid4()),
        "station_id": str(uuid4()),
        "status": "active",
        "start_at": started_at.isoformat().replace("+00:00", "Z"),
        "end_at": None,
        "timer_minutes": 75,
        "timer_ends_at": (started_at + timedelta(minutes=75))
        .isoformat()
        .replace("+00:00", "Z"),
        "billable_minutes": None,
        "amount_minor": 15_000,
        "customer_name": "Replay Customer",
        "customer_phone": None,
        "rate_per_hour_minor": 20_000,
        "order_id": None,
        "cancel_reason": None,
        "package_id": str(uuid4()),
        "extra_controllers": 0,
    }


@pytest.mark.asyncio
async def test_paid_extension_requires_idempotency_key_before_database_work() -> None:
    with pytest.raises(BusinessRuleError, match="Idempotency-Key"):
        await gaming_router.extend_session_with_package(
            uuid4(),
            gaming_router.SessionExtend(package_id=uuid4()),
            _NoDatabaseSession(),
            _request(),
            _tenant(),
        )


@pytest.mark.asyncio
async def test_paid_extension_replay_returns_original_canonical_response(
    monkeypatch,
) -> None:
    tenant = _tenant()
    stored_body = _stored_response_body()
    seen: dict = {}

    async def replay(_session, **kwargs):
        seen.update(kwargs)
        return {"status_code": 200, "body": stored_body}

    monkeypatch.setattr(gaming_router, "check_or_reserve", replay)

    response = await gaming_router.extend_session_with_package(
        uuid4(),
        gaming_router.SessionExtend(package_id=uuid4()),
        _NoDatabaseSession(),
        _request(key="gaming-extension:retry-1", request_hash="same-request-hash"),
        tenant,
    )

    assert response.model_dump(mode="json") == stored_body
    assert seen == {
        "key": "gaming-extension:retry-1",
        "request_hash": "same-request-hash",
        "user_id": tenant.user_id,
        "terminal_id": tenant.terminal_id,
    }
