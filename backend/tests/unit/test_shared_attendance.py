"""Credential and actor/idempotency boundaries for online coworker attendance."""

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from starlette.requests import Request

from app.api.v1.staff import shared_attendance as route
from app.core.errors import BusinessRuleError
from app.core.security import hash_password
from app.core.tenant import TenantContext
from app.services.audit.recorder import actor_ctx
from app.services.auth import credentials


def _tenant():
    return TenantContext(uuid4(), uuid4(), uuid4(), uuid4(), ("gaming_cashier",))


def _request():
    request = Request(
        {
            "type": "http",
            "method": "POST",
            "path": "/api/v1/staff/shared-attendance/clock-in",
            "query_string": b"",
            "headers": [],
        }
    )
    request.state.idempotency_key = "synthetic-action"
    return request


def test_password_is_not_part_of_the_durable_action_identity():
    caller, request, target = _tenant(), _request(), uuid4()
    first = route.SharedClockInWrite(user_id=target, password="first-secret")
    second = route.SharedClockInWrite(user_id=target, password="replacement-secret")
    assert route._action_hash(request, first, caller) == route._action_hash(request, second, caller)
    assert "first-secret" not in repr(first)
    assert route._action_hash(request, first, caller) != route._action_hash(
        request, first, _tenant()
    )
    changed = route.SharedClockInWrite(user_id=uuid4(), password="first-secret")
    assert route._action_hash(request, first, caller) != route._action_hash(
        request, changed, caller
    )


def test_shared_password_primitive_enforces_normal_account_lockout(monkeypatch):
    monkeypatch.setattr(
        credentials,
        "get_settings",
        lambda: SimpleNamespace(failed_login_lockout_threshold=2, failed_login_lockout_minutes=5),
    )
    user = SimpleNamespace(
        deleted_at=None,
        status="active",
        locked_until=None,
        failed_login_count=0,
        password_hash=hash_password("correct-secret"),
    )
    assert credentials.password_failure(user, "wrong") == "invalid_password"
    assert credentials.password_failure(user, "wrong") == "invalid_password"
    assert user.failed_login_count == 2
    assert user.locked_until > datetime.now(UTC)
    assert credentials.password_failure(user, "correct-secret") == "account_locked"
    user.locked_until = datetime.now(UTC) - timedelta(seconds=1)
    assert credentials.password_failure(user, "correct-secret") is None
    assert user.locked_until is None
    assert user.failed_login_count == 0


@pytest.mark.parametrize(("deleted", "status"), [(object(), "active"), (None, "suspended")])
def test_ineligible_account_cannot_verify_password(monkeypatch, deleted, status):
    monkeypatch.setattr(credentials, "verify_password", lambda *a: pytest.fail("must not verify"))
    user = SimpleNamespace(deleted_at=deleted, status=status)
    assert credentials.password_failure(user, "secret") == "account_inactive"


@pytest.mark.asyncio
async def test_failed_credential_never_reaches_domain_or_cached_receipt(monkeypatch):
    caller = _tenant()
    monkeypatch.setattr(route, "_authenticate", AsyncMock(side_effect=BusinessRuleError("denied")))
    domain = AsyncMock()
    monkeypatch.setattr(route, "_clock_in", domain)
    with pytest.raises(BusinessRuleError, match="denied"):
        await route._clock(
            _request(),
            object(),
            caller,
            route.SharedClockInWrite(user_id=uuid4(), password="secret"),
            clock_out=False,
        )
    domain.assert_not_awaited()


@pytest.mark.asyncio
async def test_verified_attendance_actor_is_scoped_and_restored_on_failure(monkeypatch):
    caller = _tenant()
    target = TenantContext(
        uuid4(), caller.company_id, caller.branch_id, caller.terminal_id, ("staff",)
    )
    prior = {
        "user_id": caller.user_id,
        "company_id": caller.company_id,
        "terminal_id": caller.terminal_id,
    }
    token = actor_ctx.set(prior)
    monkeypatch.setattr(
        route, "_authenticate", AsyncMock(return_value=(SimpleNamespace(id=target.user_id), target))
    )

    async def refuse(*args):
        assert actor_ctx.get()["user_id"] == target.user_id
        raise BusinessRuleError("domain refused")

    monkeypatch.setattr(route, "_clock_in", refuse)
    try:
        with pytest.raises(BusinessRuleError, match="domain refused"):
            await route._clock(
                _request(),
                object(),
                caller,
                route.SharedClockInWrite(user_id=target.user_id, password="secret"),
                clock_out=False,
            )
        assert actor_ctx.get() == prior
    finally:
        actor_ctx.reset(token)
