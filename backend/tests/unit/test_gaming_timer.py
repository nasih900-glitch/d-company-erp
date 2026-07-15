"""Unit tests for the gaming-session timer (planned duration) feature."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.api.v1.gaming.router import SessionStart, session_amount_minor, session_read
from app.models import GamingSession


def _session(**over) -> GamingSession:
    base = {
        "id": uuid4(),
        "company_id": uuid4(),
        "station_id": uuid4(),
        "opened_by": uuid4(),
        "shift_id": uuid4(),
        "start_at": datetime(2026, 7, 14, 10, 0, tzinfo=UTC),
        "rate_per_hour_minor": 20000,
        "status": "active",
    }
    base.update(over)
    return GamingSession(**base)


def test_timer_ends_at_derived_from_start_and_timer_minutes():
    gs = _session(timer_minutes=60)
    out = session_read(gs)
    assert out.timer_minutes == 60
    assert out.timer_ends_at == datetime(2026, 7, 14, 11, 0, tzinfo=UTC)


def test_open_ended_session_has_no_timer_ends_at():
    gs = _session(timer_minutes=None)
    out = session_read(gs)
    assert out.timer_minutes is None
    assert out.timer_ends_at is None


def test_timer_minutes_bounds_are_enforced_by_schema():
    SessionStart(station_id=uuid4(), shift_id=uuid4(), timer_minutes=1440)  # max ok
    with pytest.raises(ValidationError):
        SessionStart(station_id=uuid4(), shift_id=uuid4(), timer_minutes=0)
    with pytest.raises(ValidationError):
        SessionStart(station_id=uuid4(), shift_id=uuid4(), timer_minutes=1441)


def test_session_read_echoes_order_id_once_sent_to_pos():
    order_id = uuid4()
    gs = _session(status="ended", order_id=order_id)
    assert session_read(gs).order_id == order_id


def test_session_read_order_id_is_none_before_send_to_pos():
    gs = _session(status="ended")
    assert session_read(gs).order_id is None


@pytest.mark.parametrize(
    ("minutes", "rate", "expected"),
    [
        (0, 15000, 0),
        (1, 15000, 250),
        (31, 15000, 7750),
        (23, 18000, 6900),
        (33, 25000, 13750),
        (60, 20000, 20000),
        (61, 20000, 20334),
    ],
)
def test_session_billing_uses_exact_integer_ceiling(minutes, rate, expected):
    assert session_amount_minor(minutes, rate) == expected
