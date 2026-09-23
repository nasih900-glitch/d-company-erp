"""Boundary tests for durable shift-opening timestamps."""

from datetime import UTC, datetime, timedelta

import pytest

from app.core.errors import BusinessRuleError
from app.services.pos.shift_capture import MAX_FUTURE_CLOCK_SKEW, OpeningCapture


def _capture(*, opened_at: datetime, received_at: datetime) -> OpeningCapture:
    return OpeningCapture(
        key="shift-open:test",
        request_hash="request-hash",
        opened_at=opened_at,
        received_at=received_at,
        offline=True,
    )


def test_subsecond_clock_skew_does_not_strand_saved_shift() -> None:
    received_at = datetime(2026, 9, 22, 12, 0, tzinfo=UTC)

    _capture(
        opened_at=received_at + timedelta(milliseconds=750),
        received_at=received_at,
    ).require_fresh()


def test_clock_skew_at_tolerance_boundary_is_accepted() -> None:
    received_at = datetime(2026, 9, 22, 12, 0, tzinfo=UTC)

    _capture(
        opened_at=received_at + MAX_FUTURE_CLOCK_SKEW,
        received_at=received_at,
    ).require_fresh()


def test_clock_skew_beyond_tolerance_is_rejected() -> None:
    received_at = datetime(2026, 9, 22, 12, 0, tzinfo=UTC)

    with pytest.raises(BusinessRuleError, match="nothing was discarded"):
        _capture(
            opened_at=received_at + MAX_FUTURE_CLOCK_SKEW + timedelta(microseconds=1),
            received_at=received_at,
        ).require_fresh()
