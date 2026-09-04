"""Regression tests for failures found during the live release audit."""

from datetime import datetime, timezone
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError
from starlette.requests import Request

from app import __version__
from app.api.v1.events.router import _ticket_number
from app.api.v1.gaming.router import BookingCreate
from app.api.v1.pos.router import OrderCreate
from app.api.v1.settings.router import BranchCreate
from app.core.middleware import IdempotencyMiddleware
from app.main import create_app
from app.models import ExpenseCategory, IdempotencyKey, Order


def test_fastapi_metadata_uses_the_package_release_version() -> None:
    assert create_app().version == __version__ == "3.1.12"


def _order_payload(**overrides):
    payload = {
        "type": "dine_in",
        "shift_id": uuid4(),
        "lines": [{"menu_item_id": uuid4(), "qty": 1}],
    }
    payload.update(overrides)
    return payload


def test_pos_rejects_customer_name_before_database_overflow() -> None:
    with pytest.raises(ValidationError):
        OrderCreate.model_validate(_order_payload(customer_name="x" * 201))


def test_gaming_booking_rejects_contact_before_database_overflow() -> None:
    with pytest.raises(ValidationError):
        BookingCreate.model_validate(
            {
                "station_id": uuid4(),
                "starts_at": "2026-07-10T10:00:00Z",
                "ends_at": "2026-07-10T11:00:00Z",
                "guest_name": "Guest",
                "contact": "x" * 51,
            }
        )


def test_kerala_branch_requires_numeric_gst_state_code() -> None:
    with pytest.raises(ValidationError):
        BranchCreate(name="Nilambur", state_code="KL")
    assert BranchCreate(name="Nilambur", state_code="32").state_code == "32"


def test_idempotency_columns_fit_web_checkout_keys() -> None:
    assert Order.__table__.c.idempotency_key.type.length == 160
    assert IdempotencyKey.__table__.c.key.type.length == 160
    assert ExpenseCategory.__table__.c.code.type.length == 20


def test_same_day_events_get_distinct_ticket_numbers() -> None:
    starts_at = datetime(2026, 7, 10, 18, 0, tzinfo=timezone.utc)
    first = _ticket_number(UUID("11111111-1111-1111-1111-111111111111"), starts_at, 1)
    second = _ticket_number(UUID("22222222-2222-2222-2222-222222222222"), starts_at, 1)
    assert first != second
    assert len(first) <= 40


@pytest.mark.asyncio
async def test_oversized_idempotency_header_returns_controlled_error() -> None:
    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "https",
        "path": "/api/v1/pos/orders",
        "raw_path": b"/api/v1/pos/orders",
        "query_string": b"",
        "headers": [(b"idempotency-key", b"x" * 161)],
        "client": ("127.0.0.1", 1234),
        "server": ("test", 443),
    }
    request = Request(scope)
    middleware = IdempotencyMiddleware(lambda *_args, **_kwargs: None)

    async def should_not_run(_request):
        raise AssertionError("oversized key should be rejected before routing")

    response = await middleware.dispatch(request, should_not_run)
    assert response.status_code == 400
