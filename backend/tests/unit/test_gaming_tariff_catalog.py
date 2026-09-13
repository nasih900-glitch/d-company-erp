"""Exact regression contract for the 2026-09-13 printed gaming tariff."""

from __future__ import annotations

from pathlib import Path
from uuid import uuid4

import pytest

from app.models import GamingPackage
from app.services.gaming.tariff_catalog import (
    D_COMPANY_GAMING_TARIFF,
    FIXED_TARIFF_STATION_TYPES,
    RETIRED_PREMIUM_CODES,
    GamingTariffCatalogConflictError,
    upsert_d_company_gaming_tariff,
)


class _Rows:
    def __init__(self, rows: list[GamingPackage]) -> None:
        self.rows = rows

    def scalars(self):
        return self

    def all(self) -> list[GamingPackage]:
        return self.rows


class _DryRunSession:
    def __init__(self, rows: list[GamingPackage]) -> None:
        self.rows = rows
        self.execute_calls = 0

    async def execute(self, _statement):
        self.execute_calls += 1
        return _Rows(self.rows)


def _money_matrix() -> list[tuple[str, str, str, str, int, int, int, int]]:
    return [
        (
            row.pricing_tier,
            row.station_type,
            row.variant,
            row.kind,
            row.duration_minutes,
            row.price_minor,
            row.included_players,
            row.max_players,
        )
        for row in D_COMPANY_GAMING_TARIFF
    ]


def test_tariff_matches_the_printed_card_exactly() -> None:
    assert _money_matrix() == [
        ("standard", "ps5", "single", "base", 30, 8_000, 1, 1),
        ("standard", "ps5", "single", "base", 60, 12_000, 1, 1),
        ("standard", "ps5", "single", "extension", 30, 6_000, 1, 1),
        ("standard", "ps5", "single", "extension", 60, 10_000, 1, 1),
        ("standard", "ps5", "dual", "base", 30, 10_000, 2, 4),
        ("standard", "ps5", "dual", "base", 60, 15_000, 2, 4),
        ("standard", "ps5", "dual", "extension", 30, 7_000, 2, 4),
        ("standard", "ps5", "dual", "extension", 60, 13_000, 2, 4),
        ("standard", "simulator", "simdrive", "base", 15, 7_000, 1, 1),
        ("standard", "simulator", "simdrive", "base", 30, 10_000, 1, 1),
        ("standard", "simulator", "simdrive", "base", 60, 18_000, 1, 1),
        ("standard", "vr", "vr_games", "base", 15, 8_000, 1, 1),
        ("standard", "vr", "vr_games", "base", 30, 12_000, 1, 1),
        ("standard", "vr", "vr_games", "base", 60, 20_000, 1, 1),
        ("standard", "simulator", "vr_racing", "base", 15, 10_000, 1, 1),
        ("standard", "simulator", "vr_racing", "base", 30, 14_000, 1, 1),
        ("standard", "simulator", "vr_racing", "base", 60, 25_000, 1, 1),
    ]


def test_tariff_has_17_stable_unique_codes_and_expected_row_kinds() -> None:
    codes = [row.code for row in D_COMPANY_GAMING_TARIFF]
    assert len(codes) == 17
    assert len(set(codes)) == 17
    assert codes == [
        "standard-single-session-30m",
        "standard-single-session-60m",
        "standard-single-extension-30m",
        "standard-single-extension-60m",
        "standard-dual-session-30m",
        "standard-dual-session-60m",
        "standard-dual-extension-30m",
        "standard-dual-extension-60m",
        "standard-simdrive-session-15m",
        "standard-simdrive-session-30m",
        "standard-simdrive-session-60m",
        "vr-games-session-15m",
        "vr-games-session-30m",
        "vr-games-session-60m",
        "vr-racing-session-15m",
        "vr-racing-session-30m",
        "vr-racing-session-60m",
    ]
    assert sum(row.kind == "base" for row in D_COMPANY_GAMING_TARIFF) == 13
    assert sum(row.kind == "extension" for row in D_COMPANY_GAMING_TARIFF) == 4
    assert {
        "premium-single-session-60m",
        "premium-single-extension-30m",
        "premium-single-extension-60m",
        "premium-dual-session-60m",
        "premium-dual-extension-30m",
        "premium-dual-extension-60m",
    } == RETIRED_PREMIUM_CODES


def test_card_retires_premium_and_only_ps5_has_extensions() -> None:
    assert not any(row.pricing_tier == "premium" for row in D_COMPANY_GAMING_TARIFF)
    assert not any(
        row.station_type != "ps5" and row.kind == "extension" for row in D_COMPANY_GAMING_TARIFF
    )
    assert {"ps5", "simulator", "vr"} == FIXED_TARIFF_STATION_TYPES


@pytest.mark.asyncio
async def test_dry_run_reports_all_creates_without_executing_an_upsert() -> None:
    session = _DryRunSession([])
    result = await upsert_d_company_gaming_tariff(
        session,  # type: ignore[arg-type]
        company_id=uuid4(),
        branch_id=uuid4(),
        dry_run=True,
    )

    assert result.created_codes == tuple(row.code for row in D_COMPANY_GAMING_TARIFF)
    assert result.updated_codes == ()
    assert result.unchanged_codes == ()
    assert result.retired_codes == ()
    assert session.execute_calls == 1


@pytest.mark.asyncio
async def test_unexpected_active_covered_package_fails_closed_with_details() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    unexpected = GamingPackage(
        id=uuid4(),
        code=f"legacy-{uuid4()}",
        company_id=company_id,
        branch_id=branch_id,
        station_type="ps5",
        variant="single",
        pricing_tier="standard",
        kind="base",
        name="Old wrong package",
        duration_minutes=60,
        price_minor=20_000,
        included_players=1,
        max_players=1,
        sort_order=0,
        is_active=True,
    )
    session = _DryRunSession([unexpected])

    with pytest.raises(GamingTariffCatalogConflictError) as caught:
        await upsert_d_company_gaming_tariff(
            session,  # type: ignore[arg-type]
            company_id=company_id,
            branch_id=branch_id,
            dry_run=True,
        )

    assert caught.value.conflicts[0].id == unexpected.id
    assert "Old wrong package" in str(caught.value)
    assert "20000 paise" in str(caught.value)
    assert session.execute_calls == 1


@pytest.mark.asyncio
async def test_inactive_canonical_row_is_reactivated_not_misreported_as_created() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    spec = D_COMPANY_GAMING_TARIFF[0]
    inactive = GamingPackage(
        id=uuid4(),
        code=spec.code,
        company_id=company_id,
        branch_id=branch_id,
        station_type=spec.station_type,
        variant=spec.variant,
        pricing_tier=spec.pricing_tier,
        kind=spec.kind,
        name=spec.name,
        duration_minutes=spec.duration_minutes,
        price_minor=spec.price_minor,
        included_players=spec.included_players,
        max_players=spec.max_players,
        sort_order=spec.sort_order,
        is_active=False,
    )
    session = _DryRunSession([inactive])

    result = await upsert_d_company_gaming_tariff(
        session,  # type: ignore[arg-type]
        company_id=company_id,
        branch_id=branch_id,
        dry_run=True,
    )

    assert spec.code in result.updated_codes
    assert spec.code not in result.created_codes
    assert len(result.created_codes) == 16


@pytest.mark.asyncio
async def test_known_active_premium_is_planned_for_retirement_without_repricing() -> None:
    company_id = uuid4()
    branch_id = uuid4()
    premium = GamingPackage(
        id=uuid4(),
        code="premium-single-session-60m",
        company_id=company_id,
        branch_id=branch_id,
        station_type="ps5",
        variant="single",
        pricing_tier="premium",
        kind="base",
        name="Premium Single Mode · 1 hour",
        duration_minutes=60,
        price_minor=15_000,
        included_players=1,
        max_players=1,
        sort_order=110,
        is_active=True,
    )
    session = _DryRunSession([premium])

    result = await upsert_d_company_gaming_tariff(
        session,  # type: ignore[arg-type]
        company_id=company_id,
        branch_id=branch_id,
        dry_run=True,
    )

    assert result.retired_codes == (premium.code,)
    assert premium.is_active is True
    assert premium.price_minor == 15_000


def test_tariff_is_explicit_deploy_step_not_an_ordinary_restart_side_effect() -> None:
    root = Path(__file__).resolve().parents[3]
    entrypoint = (root / "infra/docker/backend-entrypoint.sh").read_text(encoding="utf-8")
    installer = (root / "infra/scripts/install-on-vm.sh").read_text(encoding="utf-8")

    apply_command = "python -m scripts.ensure_gaming_tariff"
    assert apply_command not in entrypoint
    assert installer.count(apply_command) == 2
    assert installer.index(f"{apply_command} --dry-run") < installer.index(f"{apply_command}\n")
    assert installer.index(f"{apply_command}\n") < installer.index(
        "up -d --no-build --pull never caddy",
        installer.index("Backend is ready."),
    )
