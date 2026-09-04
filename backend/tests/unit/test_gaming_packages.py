"""Regression tests for the printed-pricing-card package model.

extra_controller_surcharge_minor is the only pure money math the package
feature introduces — package prices themselves are just data (looked up,
not computed), so this is what's worth unit testing without a database.
"""

from __future__ import annotations

import pytest

from app.api.v1.gaming.router import (
    EXTRA_CONTROLLER_MIN_CHARGE_MINOR,
    extra_controller_surcharge_delta_minor,
    extra_controller_surcharge_minor,
    resolve_package_extra_controllers,
)
from app.core.errors import BusinessRuleError


class TestExtraControllerSurcharge:
    def test_no_extra_controllers_is_free(self) -> None:
        assert extra_controller_surcharge_minor(extra_controllers=0, duration_minutes=60) == 0

    def test_one_extra_controller_for_a_full_hour(self) -> None:
        assert extra_controller_surcharge_minor(extra_controllers=1, duration_minutes=60) == 3000

    def test_short_session_still_hits_the_minimum_charge(self) -> None:
        # A 15-minute slot with an extra controller still costs the ₹30 floor,
        # not a prorated fraction of it.
        assert extra_controller_surcharge_minor(
            extra_controllers=1, duration_minutes=15
        ) == EXTRA_CONTROLLER_MIN_CHARGE_MINOR

    def test_two_hour_session_charges_per_hour_ceiling(self) -> None:
        # 90 minutes ceils to 2 billable hours per controller.
        assert extra_controller_surcharge_minor(extra_controllers=1, duration_minutes=90) == 6000

    def test_multiple_extra_controllers_multiply_independently(self) -> None:
        assert extra_controller_surcharge_minor(extra_controllers=2, duration_minutes=60) == 6000

    def test_negative_controllers_treated_as_zero(self) -> None:
        assert extra_controller_surcharge_minor(extra_controllers=-1, duration_minutes=60) == 0

    def test_zero_duration_is_free_regardless_of_controllers(self) -> None:
        assert extra_controller_surcharge_minor(extra_controllers=3, duration_minutes=0) == 0


class TestCumulativeExtraControllerSurcharge:
    def test_two_half_hour_blocks_charge_one_minimum(self) -> None:
        assert extra_controller_surcharge_delta_minor(
            extra_controllers=1,
            duration_before_minutes=30,
            duration_after_minutes=60,
        ) == 0

    def test_crossing_into_second_hour_charges_only_the_delta(self) -> None:
        assert extra_controller_surcharge_delta_minor(
            extra_controllers=1,
            duration_before_minutes=60,
            duration_after_minutes=90,
        ) == 3_000

    def test_extension_deltas_reconcile_to_whole_session_charge(self) -> None:
        base = extra_controller_surcharge_minor(
            extra_controllers=2,
            duration_minutes=30,
        )
        first_extension = extra_controller_surcharge_delta_minor(
            extra_controllers=2,
            duration_before_minutes=30,
            duration_after_minutes=60,
        )
        second_extension = extra_controller_surcharge_delta_minor(
            extra_controllers=2,
            duration_before_minutes=60,
            duration_after_minutes=90,
        )
        assert base + first_extension + second_extension == extra_controller_surcharge_minor(
            extra_controllers=2,
            duration_minutes=90,
        )

    def test_rejects_a_backwards_timer(self) -> None:
        with pytest.raises(ValueError, match="monotonic"):
            extra_controller_surcharge_delta_minor(
                extra_controllers=1,
                duration_before_minutes=60,
                duration_after_minutes=30,
            )


class TestPackagePlayerRules:
    @staticmethod
    def _resolve(
        *,
        variant: str = "dual",
        included_players: int = 2,
        max_players: int = 10,
        extra_controllers: int = 0,
        player_count: int | None = None,
    ) -> int:
        return resolve_package_extra_controllers(
            station_type="ps5",
            variant=variant,
            included_players=included_players,
            max_players=max_players,
            extra_controllers=extra_controllers,
            player_count=player_count,
        )

    def test_code21_dual_payload_remains_supported(self) -> None:
        assert self._resolve(extra_controllers=2) == 2

    def test_code22_player_count_derives_extra_controllers(self) -> None:
        assert self._resolve(player_count=4) == 2

    def test_matching_old_and_new_fields_are_accepted(self) -> None:
        assert self._resolve(player_count=3, extra_controllers=1) == 1

    def test_conflicting_old_and_new_fields_are_rejected(self) -> None:
        with pytest.raises(BusinessRuleError, match="different party sizes"):
            self._resolve(player_count=3, extra_controllers=2)

    def test_single_mode_rejects_an_extra_controller(self) -> None:
        with pytest.raises(BusinessRuleError, match="at most 1"):
            self._resolve(
                variant="single",
                included_players=1,
                max_players=1,
                extra_controllers=1,
            )

    def test_dual_mode_rejects_party_over_capacity(self) -> None:
        with pytest.raises(BusinessRuleError, match="at most 4"):
            self._resolve(max_players=4, player_count=5)

    def test_fixed_player_metadata_cannot_enable_additional_controllers(self) -> None:
        with pytest.raises(BusinessRuleError, match="only available"):
            self._resolve(
                included_players=1,
                max_players=2,
                extra_controllers=1,
            )
