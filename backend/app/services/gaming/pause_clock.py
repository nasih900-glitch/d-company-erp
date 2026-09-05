"""Exact server-authoritative gaming play time, compatible with legacy minutes."""

from datetime import datetime, timedelta


def duration_ms(delta: timedelta) -> int:
    return max(0, delta.days * 86_400_000 + delta.seconds * 1_000 + delta.microseconds // 1_000)


def completed_pause_ms(session) -> int:
    precise = getattr(session, "paused_duration_ms", None)
    if precise is not None:
        return max(0, int(precise))
    return max(0, int(getattr(session, "paused_minutes", 0) or 0)) * 60_000


def play_elapsed_ms(session, at: datetime) -> int:
    elapsed = duration_ms(at - session.start_at)
    paused = completed_pause_ms(session)
    paused_at = getattr(session, "paused_at", None)
    if paused_at is not None:
        paused += duration_ms(at - paused_at)
    return max(0, elapsed - paused)


def billable_whole_minutes(session, at: datetime) -> int:
    elapsed = play_elapsed_ms(session, at)
    return (elapsed + 59_999) // 60_000


def timer_deadline(session) -> datetime | None:
    if not session.timer_minutes or getattr(session, "paused_at", None) is not None:
        return None
    return session.start_at + timedelta(
        minutes=session.timer_minutes, milliseconds=completed_pause_ms(session)
    )


def finish_pause(session, at: datetime) -> None:
    """Called under the session row lock; a second call cannot add time twice."""
    if getattr(session, "paused_at", None) is None:
        return
    session.paused_duration_ms = completed_pause_ms(session) + duration_ms(at - session.paused_at)
    # This is a compatibility projection of the total, not a per-pause round.
    session.paused_minutes = session.paused_duration_ms // 60_000
    session.paused_at = None
    session.pause_version = int(getattr(session, "pause_version", 0) or 0) + 1
    session.last_pause_transition_at = at
