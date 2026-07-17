"""Regression tests for the printed-pricing-card package model.

extra_controller_surcharge_minor is the only pure money math the package
feature introduces — package prices themselves are just data (looked up,
not computed), so this is what's worth unit testing without a database.
"""

from __future__ import annotations

from app.api.v1.gaming.router import (
    EXTRA_CONTROLLER_MIN_CHARGE_MINOR,
    extra_controller_surcharge_minor,
)


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
