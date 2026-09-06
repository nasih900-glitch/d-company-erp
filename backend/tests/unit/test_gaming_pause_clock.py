from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.api.v1.gaming.router import SessionPauseChange, SessionRead
from app.services.gaming.pause_clock import (
    billable_whole_minutes, completed_pause_ms, finish_pause, play_elapsed_ms, timer_deadline,
)

START = datetime(2026, 9, 5, 8, tzinfo=UTC)


def game(**changes):
    values = dict(start_at=START, paused_minutes=0, paused_duration_ms=0,
                  paused_at=None, pause_version=0, timer_minutes=30)
    return SimpleNamespace(**(values | changes))


@pytest.mark.parametrize("elapsed_ms,expected", [(0,0),(1,1),(59999,1),(60000,1),(60001,2),(120000,2)])
def test_pause_is_subtracted_before_single_final_minute_rounding(elapsed_ms, expected):
    session = game(paused_duration_ms=59_999)
    at = START + timedelta(milliseconds=59_999 + elapsed_ms)
    assert billable_whole_minutes(session, at) == expected


def test_repeated_subminute_pauses_accumulate_without_per_pause_rounding():
    session = game(paused_at=START + timedelta(seconds=30))
    finish_pause(session, START + timedelta(seconds=59, milliseconds=500))
    session.paused_at = START + timedelta(seconds=60)
    finish_pause(session, START + timedelta(seconds=90, milliseconds=500))
    assert session.paused_duration_ms == 60_000
    assert session.paused_minutes == 1
    assert session.pause_version == 2
    assert timer_deadline(session) == START + timedelta(minutes=31)
    assert play_elapsed_ms(session, START + timedelta(minutes=2)) == 60_000


def test_active_pause_freezes_clock_and_cancels_deadline_until_resume():
    session = game(paused_at=START + timedelta(seconds=30))
    assert play_elapsed_ms(session, START + timedelta(hours=7)) == 30_000
    assert timer_deadline(session) is None
    finish_pause(session, START + timedelta(hours=7))
    duration = session.paused_duration_ms
    finish_pause(session, START + timedelta(hours=8))
    assert session.paused_duration_ms == duration


def test_legacy_minutes_are_used_only_when_precise_field_is_absent():
    old = SimpleNamespace(paused_minutes=3)
    assert completed_pause_ms(old) == 180_000
    assert completed_pause_ms(game(paused_minutes=3, paused_duration_ms=180_500)) == 180_500


@pytest.mark.parametrize("reason", ["", "  ", "a", "ab", " " * 500])
def test_pause_requires_a_meaningful_reason(reason):
    with pytest.raises(ValueError):
        SessionPauseChange(reason=reason, expected_pause_version=0)


def test_legacy_response_projects_minutes_to_exact_duration():
    from uuid import uuid4
    value = SessionRead.model_validate(dict(
        id=str(uuid4()), station_id=str(uuid4()), status="active", start_at=START,
        end_at=None, billable_minutes=None, amount_minor=None, paused_minutes=2,
    ))
    assert value.paused_duration_ms == 120_000
    assert value.pause_version == 0
    assert value.paused_at is None
