"""Regression tests for the POS custom-discount option.

`apply_manual_discount` is the shared math both recompute call sites
(membership attach, add-lines-to-order) use to fold a cashier's manual
discount back into a freshly line-rebuilt order without wiping it out — the
whole point of keeping it a pure function is that this money math is
testable without a database.
"""

from __future__ import annotations

from app.services.pos.pricing import apply_manual_discount, apply_points_redemption


class TestApplyManualDiscount:
    def test_no_manual_discount_is_a_no_op(self) -> None:
        manual, discount, total = apply_manual_discount(
            line_discount_total_minor=500,
            manual_discount_minor=0,
            rounded_total_minor=10_000,
        )
        assert (manual, discount, total) == (0, 500, 10_000)

    def test_manual_discount_combines_with_line_discount_and_reduces_total(self) -> None:
        manual, discount, total = apply_manual_discount(
            line_discount_total_minor=500,
            manual_discount_minor=2_000,
            rounded_total_minor=10_000,
        )
        assert manual == 2_000
        assert discount == 2_500
        assert total == 8_000

    def test_manual_discount_is_clamped_to_the_new_total(self) -> None:
        # A membership discount (or a voided line) already ate most of the
        # bill on a later recompute — the stale manual amount must not push
        # the order negative or silently overshoot past zero.
        manual, discount, total = apply_manual_discount(
            line_discount_total_minor=9_500,
            manual_discount_minor=2_000,
            rounded_total_minor=1_000,
        )
        assert manual == 1_000
        assert discount == 10_500
        assert total == 0

    def test_negative_manual_discount_is_treated_as_zero(self) -> None:
        manual, discount, total = apply_manual_discount(
            line_discount_total_minor=0,
            manual_discount_minor=-500,
            rounded_total_minor=10_000,
        )
        assert (manual, discount, total) == (0, 0, 10_000)

    def test_zero_total_clamps_any_manual_discount_to_zero(self) -> None:
        manual, discount, total = apply_manual_discount(
            line_discount_total_minor=0,
            manual_discount_minor=500,
            rounded_total_minor=0,
        )
        assert (manual, discount, total) == (0, 0, 0)

    def test_manual_discount_exactly_equal_to_total_zeroes_the_bill(self) -> None:
        manual, discount, total = apply_manual_discount(
            line_discount_total_minor=0,
            manual_discount_minor=10_000,
            rounded_total_minor=10_000,
        )
        assert (manual, discount, total) == (10_000, 10_000, 0)


class TestApplyPointsRedemption:
    def test_no_points_redeemed_is_a_no_op(self) -> None:
        points, discount, total = apply_points_redemption(
            discount_so_far_minor=500,
            points_redeemed_minor=0,
            remaining_total_minor=10_000,
        )
        assert (points, discount, total) == (0, 500, 10_000)

    def test_points_reduce_the_remaining_total(self) -> None:
        points, discount, total = apply_points_redemption(
            discount_so_far_minor=1_000,
            points_redeemed_minor=2_000,
            remaining_total_minor=8_000,
        )
        assert (points, discount, total) == (2_000, 3_000, 6_000)

    def test_points_clamped_to_what_a_manual_discount_left_behind(self) -> None:
        # A cashier's manual discount already consumed most of the bill —
        # points redemption must not push the order negative on top of it.
        points, discount, total = apply_points_redemption(
            discount_so_far_minor=9_500,
            points_redeemed_minor=2_000,
            remaining_total_minor=500,
        )
        assert (points, discount, total) == (500, 10_000, 0)

    def test_negative_points_treated_as_zero(self) -> None:
        points, discount, total = apply_points_redemption(
            discount_so_far_minor=0,
            points_redeemed_minor=-500,
            remaining_total_minor=10_000,
        )
        assert (points, discount, total) == (0, 0, 10_000)

    def test_composes_with_manual_discount(self) -> None:
        # The real call chain: apply_manual_discount first, then feed its
        # output straight into apply_points_redemption.
        manual, discount1, total1 = apply_manual_discount(
            line_discount_total_minor=0,
            manual_discount_minor=1_000,
            rounded_total_minor=10_000,
        )
        points, discount2, total2 = apply_points_redemption(
            discount_so_far_minor=discount1,
            points_redeemed_minor=1_500,
            remaining_total_minor=total1,
        )
        assert (manual, points) == (1_000, 1_500)
        assert discount2 == 2_500
        assert total2 == 7_500
