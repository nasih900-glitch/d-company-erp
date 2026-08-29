"""Regression tests for the loyalty-points-to-playtime conversion math."""

from __future__ import annotations

from app.services.pos.points import (
    RANK_UP_BONUS_POINTS,
    REWARD_CATALOG,
    gaming_rank,
    minor_to_points,
    points_to_minor,
    proportional_cumulative_points,
    rank_meets,
    rank_progress,
    rank_up_bonus_points,
    reward_by_key,
    rewards_available_to,
)


class TestPointsToMinor:
    def test_ten_points_is_one_rupee(self) -> None:
        assert points_to_minor(10) == 100

    def test_zero_points_is_zero(self) -> None:
        assert points_to_minor(0) == 0

    def test_negative_points_treated_as_zero(self) -> None:
        assert points_to_minor(-50) == 0


class TestMinorToPoints:
    def test_one_rupee_is_ten_points(self) -> None:
        assert minor_to_points(100) == 10

    def test_floors_never_rounds_up(self) -> None:
        # 95 paise is not quite 10 points worth — must not grant the 10th.
        assert minor_to_points(95) == 9

    def test_zero_minor_is_zero_points(self) -> None:
        assert minor_to_points(0) == 0

    def test_negative_minor_treated_as_zero(self) -> None:
        assert minor_to_points(-100) == 0


class TestRoundTrip:
    def test_points_to_minor_and_back_never_gains_value(self) -> None:
        for points in (0, 1, 9, 10, 11, 250, 9_999):
            assert minor_to_points(points_to_minor(points)) == points


class TestRefundPointAllocation:
    def test_two_partial_refunds_finish_at_exact_full_component(self) -> None:
        first = proportional_cumulative_points(
            points=7,
            cumulative_refunded_minor=333,
            order_paid_minor=1000,
        )
        second_target = proportional_cumulative_points(
            points=7,
            cumulative_refunded_minor=1000,
            order_paid_minor=1000,
        )
        assert first == 2
        assert second_target - first == 5
        assert second_target == 7

    def test_allocation_is_bounded_and_invalid_denominator_is_zero(self) -> None:
        assert proportional_cumulative_points(
            points=9,
            cumulative_refunded_minor=1200,
            order_paid_minor=1000,
        ) == 9
        assert proportional_cumulative_points(
            points=9,
            cumulative_refunded_minor=100,
            order_paid_minor=0,
        ) == 0


class TestGamingRank:
    def test_zero_points_is_rookie(self) -> None:
        assert gaming_rank(0) == "Rookie"

    def test_boundary_values_round_up_to_the_new_rank(self) -> None:
        assert gaming_rank(199) == "Rookie"
        assert gaming_rank(200) == "Player"
        assert gaming_rank(499) == "Player"
        assert gaming_rank(500) == "Pro"
        assert gaming_rank(999) == "Pro"
        assert gaming_rank(1000) == "Legend"

    def test_negative_points_treated_as_zero(self) -> None:
        assert gaming_rank(-50) == "Rookie"

    def test_very_high_points_still_legend(self) -> None:
        assert gaming_rank(1_000_000) == "Legend"


class TestRankProgress:
    def test_rookie_progress_toward_player(self) -> None:
        p = rank_progress(120)
        assert p.rank == "Rookie"
        assert p.next_rank == "Player"
        assert p.points_to_next_rank == 80

    def test_exactly_on_a_threshold_has_zero_points_to_next(self) -> None:
        p = rank_progress(500)
        assert p.rank == "Pro"
        assert p.next_rank == "Legend"
        assert p.points_to_next_rank == 500

    def test_legend_has_no_next_rank(self) -> None:
        p = rank_progress(5000)
        assert p.rank == "Legend"
        assert p.next_rank is None
        assert p.points_to_next_rank is None


class TestRankMeets:
    def test_higher_rank_meets_lower_minimum(self) -> None:
        assert rank_meets("Legend", "Player")
        assert rank_meets("Pro", "Rookie")

    def test_same_rank_meets_itself(self) -> None:
        assert rank_meets("Player", "Player")

    def test_lower_rank_does_not_meet_higher_minimum(self) -> None:
        assert not rank_meets("Rookie", "Player")
        assert not rank_meets("Player", "Legend")


class TestRankUpBonusPoints:
    def test_no_bonus_when_rank_unchanged(self) -> None:
        assert rank_up_bonus_points(old_lifetime=50, new_lifetime=150) == 0

    def test_single_threshold_crossed(self) -> None:
        assert rank_up_bonus_points(old_lifetime=150, new_lifetime=250) == RANK_UP_BONUS_POINTS["Player"]

    def test_multiple_thresholds_crossed_in_one_jump_all_pay(self) -> None:
        # 190 -> 550 crosses both Player (200) and Pro (500).
        expected = RANK_UP_BONUS_POINTS["Player"] + RANK_UP_BONUS_POINTS["Pro"]
        assert rank_up_bonus_points(old_lifetime=190, new_lifetime=550) == expected

    def test_reaching_legend_from_scratch_pays_every_threshold(self) -> None:
        expected = sum(RANK_UP_BONUS_POINTS.values())
        assert rank_up_bonus_points(old_lifetime=0, new_lifetime=1200) == expected

    def test_already_past_rank_earns_nothing_more(self) -> None:
        assert rank_up_bonus_points(old_lifetime=600, new_lifetime=800) == 0


class TestRewardCatalog:
    def test_reward_by_key_finds_known_reward(self) -> None:
        reward = reward_by_key("ps5_30")
        assert reward is not None
        assert reward.points_cost == 150
        assert reward.value_minor == 8000

    def test_reward_by_key_unknown_returns_none(self) -> None:
        assert reward_by_key("not-a-real-reward") is None

    def test_rookie_only_sees_rookie_tier_rewards(self) -> None:
        keys = {r.key for r in rewards_available_to("Rookie")}
        assert keys == {"extra_controller", "snack", "ps5_30"}

    def test_legend_sees_every_reward(self) -> None:
        assert len(rewards_available_to("Legend")) == len(REWARD_CATALOG)

    def test_catalog_rewards_are_a_better_deal_than_the_generic_rate(self) -> None:
        # Every named reward must give more value per point than the flat
        # 10-paise-per-point cash-out — otherwise there's no reason to chase
        # the named goal instead of just cashing out generically.
        for reward in REWARD_CATALOG:
            generic_value = reward.points_cost * points_to_minor(1)
            assert reward.value_minor > generic_value
