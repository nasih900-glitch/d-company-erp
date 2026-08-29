"""Unit tests for the gaming-session timer (planned duration) feature."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.api.v1.gaming.router import (
    SessionRead,
    SessionStart,
    _elapsed_billable_whole_minutes,
    _require_session_pos_eligible,
    _session_pos_description,
    session_amount_minor,
    session_read,
)
from app.core.errors import BusinessRuleError
from app.models import GamingPackage, GamingSession, GamingSessionExtension


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
    common = {
        "station_id": uuid4(),
        "shift_id": uuid4(),
        "expected_rate_per_hour_minor": 20_000,
    }
    SessionStart(**common, timer_minutes=1440)  # max ok
    with pytest.raises(ValidationError):
        SessionStart(**common, timer_minutes=0)
    with pytest.raises(ValidationError):
        SessionStart(**common, timer_minutes=1441)


def test_open_rate_schema_allows_legacy_online_payload_for_route_compatibility_gate():
    payload = SessionStart(station_id=uuid4(), shift_id=uuid4())
    assert payload.expected_rate_per_hour_minor is None


def test_package_start_requires_complete_catalog_snapshot():
    with pytest.raises(ValidationError, match="price, duration, and variant"):
        SessionStart(
            station_id=uuid4(),
            shift_id=uuid4(),
            package_id=uuid4(),
        )


def test_elapsed_billable_minutes_uses_server_clock_and_pause_snapshot():
    started_at = datetime(2026, 7, 14, 10, 0, tzinfo=UTC)
    assert _elapsed_billable_whole_minutes(
        started_at=started_at,
        server_now=datetime(2026, 7, 14, 10, 20, 1, tzinfo=UTC),
        paused_minutes=3,
    ) == 18


def test_session_read_echoes_order_id_once_sent_to_pos():
    order_id = uuid4()
    gs = _session(status="ended", order_id=order_id)
    assert session_read(gs).order_id == order_id


def test_session_read_order_id_is_none_before_send_to_pos():
    gs = _session(status="ended")
    assert session_read(gs).order_id is None


def test_session_read_includes_source_shift_id():
    gs = _session()
    assert session_read(gs).shift_id == gs.shift_id


@pytest.mark.asyncio
async def test_positive_session_is_pos_eligible_without_addon_query():
    class NoAddonQuerySession:
        async def execute(self, _statement):
            raise AssertionError("positive gameplay must short-circuit the add-on query")

    await _require_session_pos_eligible(
        NoAddonQuerySession(),
        gaming_session=_session(status="ended", amount_minor=1),
    )


@pytest.mark.asyncio
async def test_complimentary_session_with_active_addon_is_pos_eligible():
    class AddonCountSession:
        async def execute(self, _statement):
            class Result:
                @staticmethod
                def scalar_one():
                    return 1

            return Result()

    await _require_session_pos_eligible(
        AddonCountSession(),
        gaming_session=_session(status="ended", amount_minor=0),
    )


@pytest.mark.asyncio
async def test_zero_session_without_addons_still_requires_reasoned_cancellation():
    class EmptyAddonSession:
        async def execute(self, _statement):
            class Result:
                @staticmethod
                def scalar_one():
                    return None

            return Result()

    with pytest.raises(BusinessRuleError, match="no play charge or saved items"):
        await _require_session_pos_eligible(
            EmptyAddonSession(),
            gaming_session=_session(status="ended", amount_minor=0),
        )


def test_session_read_replays_pre_0038_stored_response_and_derives_package_mode():
    package_id = uuid4()
    legacy_body = session_read(
        _session(
            package_id=package_id,
            billing_mode="package",
            package_price_minor_snapshot=10_000,
            package_duration_minutes_snapshot=60,
            package_variant_snapshot="single",
            package_station_type_snapshot="ps5",
        )
    ).model_dump()
    for field in (
        "billing_mode",
        "package_price_minor_snapshot",
        "package_duration_minutes_snapshot",
        "package_variant_snapshot",
        "package_station_type_snapshot",
    ):
        legacy_body.pop(field)

    replay = SessionRead.model_validate(legacy_body)

    assert replay.billing_mode == "package"
    assert replay.package_id == package_id


def test_session_read_exposes_legacy_billing_ambiguity_truthfully():
    response = session_read(
        _session(
            status="ended",
            billing_mode="legacy_ambiguous",
            package_id=None,
            timer_minutes=60,
            billable_minutes=48,
            amount_minor=10_000,
        )
    )

    assert response.billing_mode == "legacy_ambiguous"


@pytest.mark.asyncio
async def test_package_pos_description_identifies_extensions_and_controllers():
    package_id = uuid4()
    package = GamingPackage(
        id=package_id,
        company_id=uuid4(),
        branch_id=uuid4(),
        station_type="ps5",
        variant="dual",
        kind="base",
        name="Dual 60 min",
        duration_minutes=60,
        price_minor=15_000,
        sort_order=0,
        is_active=True,
    )

    class PackageSession:
        async def get(self, model, row_id):
            assert model is GamingPackage
            assert row_id == package_id
            return package

        async def execute(self, _statement):
            class EmptyRows:
                def scalars(self):
                    return self

                def all(self):
                    return []

            return EmptyRows()

    description = await _session_pos_description(
        PackageSession(),
        _session(
            package_id=package_id,
            timer_minutes=90,
            billable_minutes=73,
            amount_minor=21_000,
            extra_controllers=2,
        ),
    )

    assert description == (
        "Dual 60 min · 30 min paid extension (legacy aggregate) · 73 min played · "
        "2 extra controllers"
    )


@pytest.mark.asyncio
async def test_package_pos_description_uses_immutable_extension_itemisation():
    package_id = uuid4()
    gs = _session(
        package_id=package_id,
        timer_minutes=90,
        billable_minutes=75,
        amount_minor=20_000,
        extra_controllers=0,
    )
    package = GamingPackage(
        id=package_id,
        company_id=gs.company_id,
        branch_id=uuid4(),
        station_type="ps5",
        variant="single",
        kind="base",
        name="Single 60 min",
        duration_minutes=60,
        price_minor=10_000,
        sort_order=0,
        is_active=True,
    )
    rows = [
        GamingSessionExtension(
            id=uuid4(),
            company_id=gs.company_id,
            gaming_session_id=gs.id,
            package_id=uuid4(),
            package_name="Add 15 min",
            package_variant="single",
            station_type="ps5",
            duration_minutes=15,
            package_price_minor=5_000,
            controller_surcharge_minor=0,
            total_minor=5_000,
            timer_before_minutes=60 + (index * 15),
            timer_after_minutes=75 + (index * 15),
            amount_before_minor=10_000 + (index * 5_000),
            amount_after_minor=15_000 + (index * 5_000),
            idempotency_key=f"extension:{index}",
            created_by=uuid4(),
        )
        for index in range(2)
    ]

    class LedgerSession:
        async def get(self, model, row_id):
            assert model is GamingPackage
            assert row_id == package_id
            return package

        async def execute(self, _statement):
            class Result:
                def scalars(self):
                    return self

                def all(self):
                    return rows

            return Result()

    description = await _session_pos_description(LedgerSession(), gs)

    assert description == (
        "Single 60 min · 2× Add 15 min (+30 min, 100.00) · 75 min played"
    )


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
